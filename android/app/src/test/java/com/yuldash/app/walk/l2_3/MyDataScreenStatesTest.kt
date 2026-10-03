package com.yuldash.app.walk.l2_3

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.AppLanguage
import com.yuldash.app.LocalAppLanguage
import com.yuldash.app.MyDataScreen
import com.yuldash.app.data.ApiClient
import com.yuldash.app.ui.theme.YuldashTheme
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * leaf-2.3 (MyDataScreen.kt): до этого теста у экрана не было проверок состояний загрузка/
 * ошибка+повтор, нулевых счётчиков у новичка и диалога удаления документов водителя — а это
 * обязательная часть чек-листа "готово" для Compose-экрана (docs/audit-files/README.md:
 * "загрузка / пусто / ошибка с «Повторить» / успех", оба языка). Сеть — настоящий MockWebServer,
 * не живой yulbash.ru.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MyDataScreenStatesTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var context: Application
    private lateinit var server: MockWebServer
    private val requests = CopyOnWriteArrayList<String>()
    private val myDataResponses = CopyOnWriteArrayList<MockResponse>()
    private val myDataCalls = AtomicInteger(0)
    private val held = CountDownLatch(1)
    private val release = CountDownLatch(1)
    @Volatile private var holdFirstMyData = false
    @Volatile private var driverDocsResponse: MockResponse? = null

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        ApiClient.resetForTest()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    requests.add("${request.method} $path")
                    if (path == "/me/data") {
                        val n = myDataCalls.getAndIncrement()
                        if (n == 0 && holdFirstMyData) {
                            held.countDown()
                            check(release.await(10, TimeUnit.SECONDS)) { "synthetic hold gate timed out" }
                        }
                        return myDataResponses.getOrNull(n) ?: myDataResponses.lastOrNull()
                            ?: json("""{"rides":0,"rides_days":180,"card_stored":false}""")
                    }
                    if (path == "/me/driver-docs/delete") return driverDocsResponse ?: json("{}")
                    if (path == "/auth/logout" || path == "/push/unregister") return json("{}")
                    return json("{}", 404)
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 15_000
        ApiClient.init(context)
        ApiClient.logout()
        ApiClient.saveToken("QA-l2_3-mydata-states")
    }

    @After fun cleanup() {
        release.countDown()
        ApiClient.logout()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server.shutdown()
    }

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code)
        .setHeader("Content-Type", "application/json; charset=utf-8").setBody(body)

    private fun settle() {
        Snapshot.sendApplyNotifications()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    private fun mount(language: AppLanguage = AppLanguage.Ru) {
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides language) {
                YuldashTheme { MyDataScreen(onBack = {}) }
            }
        }
    }

    private fun waitForText(text: String, timeoutMs: Long = 10_000) =
        compose.waitUntil(timeoutMs) { settle(); compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    // R: пока ответ /me/data не пришёл, человек видит "смотрим" — а не пустой экран и не старые данные.
    @Test fun loadingStateIsShownBeforeDataArrivesThenReplacedByData() {
        holdFirstMyData = true
        myDataResponses.add(json("""{"rides":2,"rides_days":180,"card_stored":false}"""))
        mount()
        compose.waitUntil(5_000) { settle(); held.count == 0L }
        assertTrue("the spinner caption must be visible while the real HTTP call is in flight",
            compose.onAllNodesWithText("Смотрим, что у нас есть…").fetchSemanticsNodes().isNotEmpty())
        assertTrue("data must not render before the response actually arrives",
            compose.onAllNodesWithText("2 поездки").fetchSemanticsNodes().isEmpty())
        release.countDown()
        waitForText("2 поездки")
    }

    // R: отказ сервера даёт понятный текст с "Повторить", и "Повторить" реально чинит экран.
    @Test fun errorStateShowsRetryAndRetryActuallyReloadsData() {
        myDataResponses.add(json("{}", 500))
        myDataResponses.add(json("""{"rides":4,"rides_days":180,"card_stored":false}"""))
        mount()
        waitForText("Что-то пошло не так")
        assertTrue(compose.onAllNodesWithText("4 поездки").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("Повторить").assertIsDisplayed().performClick()
        waitForText("4 поездки")
        assertEquals("retry must ask the server again, not reuse the failed attempt",
            2, requests.count { it == "GET /me/data" })
    }

    // R: нули у новичка — валидный ответ, экран показывает карточки со "0", а не пустую заглушку.
    @Test fun zeroStateForBrandNewUserRendersCountersNotBlankScreen() {
        myDataResponses.add(json(
            """{"rides":0,"rides_days":180,"bookings":0,"messages":0,"messages_days":30,
                |"voices":0,"voices_days":30,"notifications":0,"notifications_days":30,
                |"driver_docs":0,"card_stored":false}""".trimMargin()
        ))
        mount()
        waitForText("0 поездок")
        assertTrue(compose.onAllNodesWithText("0 поездок").fetchSemanticsNodes().isNotEmpty())
        assertTrue("the card-not-stored explanation is part of the same valid zero state",
            compose.onAllNodesWithText("Не храним").fetchSemanticsNodes().isNotEmpty())
    }

    // R: то же самое по-башкирски — двуязычие это не только статический текст, а и числа состояния.
    @Test fun zeroStateForBrandNewUserRendersCountersInBashkirToo() {
        myDataResponses.add(json("""{"rides":0,"rides_days":180,"driver_docs":0,"card_stored":false}"""))
        mount(AppLanguage.Ba)
        waitForText("0 сәфәр")
        assertTrue(compose.onAllNodesWithText("Һаҡламайбыҙ").fetchSemanticsNodes().isNotEmpty())
    }

    // R: подтверждённое удаление документов закрывает диалог и показывает свежие (перечитанные) счётчики.
    @Test fun driverDocsDeletionSucceedsClosesDialogAndReloadsFreshCounts() {
        myDataResponses.add(json("""{"rides":1,"rides_days":180,"driver_docs":2,"driver_docs_removable":true,"card_stored":false}"""))
        myDataResponses.add(json("""{"rides":1,"rides_days":180,"driver_docs":0,"driver_docs_removable":false,"card_stored":false}"""))
        driverDocsResponse = json("{}")
        mount()
        waitForText("1 поездка")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Удалить документы"))
        compose.onNodeWithText("Удалить документы").performClick()
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText("Удалить документы?").fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithText("Удалить").performClick()
        compose.waitUntil(10_000) { settle(); compose.onAllNodesWithText("Удалить документы?").fetchSemanticsNodes().isEmpty() }
        assertEquals(1, requests.count { it == "POST /me/driver-docs/delete" })
        assertEquals("closing the dialog must re-fetch, not assume the deletion client-side",
            2, requests.count { it == "GET /me/data" })
    }

    // R: отказ сервера (водитель на линии / на проверке) держит диалог открытым с ПРИЧИНОЙ сервера,
    // а не тихо закрывает его и не прячет кнопку "Удалить" навсегда в состоянии "Удаляем…".
    @Test fun driverDocsDeletionFailureKeepsDialogOpenWithServerReasonAndReEnablesButton() {
        myDataResponses.add(json("""{"rides":1,"rides_days":180,"driver_docs":1,"driver_docs_removable":true,"card_stored":false}"""))
        driverDocsResponse = json(
            JSONObject().put("detail", JSONObject()
                .put("ru", "Пока ты на линии — нельзя")
                .put("ba", "Линияла саҡта — юйып булмай")).toString(),
            409,
        )
        mount()
        waitForText("1 поездка")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Удалить документы"))
        compose.onNodeWithText("Удалить документы").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Удалить").performClick()
        waitForText("Пока ты на линии — нельзя")
        assertTrue("a rejected deletion must keep the dialog open so the person can read why",
            compose.onAllNodesWithText("Удалить документы?").fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithText("Удалить").assertIsEnabled()
        assertEquals("only the documents endpoint must be retried, never a silent repeat",
            1, requests.count { it == "POST /me/driver-docs/delete" })
    }

    // R: пока документы нельзя снять (на линии/на проверке), кнопки нет вовсе — вместо кнопки,
    // которую сервер всё равно отклонит, человек видит объяснение почему.
    @Test fun driverDocsNotRemovableHidesDeleteButtonAndExplainsWhy() {
        myDataResponses.add(json("""{"rides":1,"rides_days":180,"driver_docs":1,"driver_docs_removable":false,"card_stored":false}"""))
        mount()
        waitForText("1 поездка")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Документы водителя"))
        assertTrue("a button the server would reject anyway must not be offered",
            compose.onAllNodesWithText("Удалить документы").fetchSemanticsNodes().isEmpty())
        compose.onNode(hasScrollAction()).performScrollToNode(
            hasText("Пока идёт проверка или ты на линии — удалить нельзя. Закончится проверка, сойдёшь с линии — кнопка появится."))
    }
}
