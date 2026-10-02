package com.yuldash.app.walk.l1_4

import android.app.Application
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.AppLanguage
import com.yuldash.app.DriverEarningsScreen
import com.yuldash.app.LocalAppLanguage
import com.yuldash.app.data.ApiClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Base64

/**
 * «Мой заработок» водителя (DriverEarningsScreen.kt) — total/sum приходят в РУБЛЯХ.
 *
 * R1 — число поездок ("Поездок") — это ШТУКИ, не деньги: показывается как есть, без денежного
 * разряда-пробела (ошибка была: `fmtRub(d.trips)` — на малых числах незаметно, но у водителя
 * с историей ≥ 1000 поездок счётчик вдруг получал денежный вид "1 234").
 * R2 — пусто (новичок без поездок) → дружелюбная заглушка, не голый экран.
 * R3 — ошибка → полноэкранное состояние с «Повторить», и «Повторить» реально переспрашивает
 * сервер.
 * R4 — смена периода (неделя/месяц/всё время) уходит на сервер правильным параметром.
 * R5 — данные уже показаны, второй запрос не удался → полоска "может быть старыми", а не
 * замена экрана ошибкой.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h900dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DriverEarningsScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var server: MockWebServer
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val requestedPeriods = mutableListOf<String>()

    private fun token(id: Int): String = "e30." + Base64.getUrlEncoder().withoutPadding()
        .encodeToString("{\"sub\":\"$id\"}".toByteArray()) + ".local"

    private fun start(respond: (String) -> MockResponse) {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val period = request.requestUrl?.queryParameter("period") ?: "?"
                    requestedPeriods += period
                    return respond(period)
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 1_000
        ApiClient.init(context)
        ApiClient.saveToken(token(101))
    }

    @Before fun prepare() { ApiClient.resetForTest() }

    @After fun cleanup() {
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        if (::server.isInitialized) server.shutdown()
    }

    private fun render() {
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                DriverEarningsScreen(onBack = {})
            }
        }
    }

    /** Ждём текст по-настоящему (а не один снимок после waitForIdle): под GC долгого прогона
     *  всего пакета тестов один idle-чек иногда обгоняет ещё не отрисованный кадр. */
    private fun awaitText(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(text))
    }

    @Test
    fun tripsCount_isPlainNumber_notMoneyGrouped() {
        start {
            // by_day намеренно с ДРУГОЙ суммой (20000, не 45000) — иначе денежная сумма итога
            // и денежная сумма дня совпадут цифрами, и поиск текста "45 000 ₽" найдёт два узла.
            MockResponse().setResponseCode(200).setBody(
                """{"period":"week","total":45000,"trips":1234,"by_day":[{"date":"2026-07-14","sum":20000,"trips":1234}]}""",
            )
        }
        render()
        awaitText("45 000 ₽")   // деньги — с разрядом-пробелом

        compose.onNodeWithText("45 000 ₽").assertIsDisplayed()
        compose.onNodeWithText("1234").assertIsDisplayed()       // штуки — без него
        compose.onNodeWithText("1 234").assertDoesNotExist()
    }

    @Test
    fun noTrips_showsFriendlyEmptyState() {
        start { MockResponse().setResponseCode(200).setBody("""{"period":"week","total":0,"trips":0,"by_day":[]}""") }
        render()
        awaitText("Пока нет завершённых поездок")

        compose.onNodeWithText("Пока нет завершённых поездок").assertIsDisplayed()
    }

    @Test
    fun serverError_showsRetryable_errorState() {
        var fail = true
        start {
            if (fail) MockResponse().setResponseCode(500)
            else MockResponse().setResponseCode(200).setBody("""{"period":"week","total":1000,"trips":3,"by_day":[]}""")
        }
        render()
        awaitText("Что-то пошло не так")
        compose.onNodeWithText("Что-то пошло не так").assertIsDisplayed()

        fail = false
        compose.onNodeWithText("Повторить").performClick()
        awaitText("1 000 ₽")

        compose.onNodeWithText("1 000 ₽").assertIsDisplayed()
        compose.onNodeWithText("Что-то пошло не так").assertDoesNotExist()
    }

    @Test
    fun periodSwitch_requestsCorrectPeriod_eachTime() {
        start { period ->
            MockResponse().setResponseCode(200).setBody(
                """{"period":"$period","total":1000,"trips":1,"by_day":[]}""",
            )
        }
        render()
        compose.waitUntil(5_000) { requestedPeriods.isNotEmpty() }
        compose.waitForIdle()
        assertEquals(listOf("week"), requestedPeriods)

        compose.onNodeWithText("Месяц").performClick()
        compose.waitUntil(5_000) { requestedPeriods.size >= 2 }
        compose.waitForIdle()
        compose.onNodeWithText("Всё время").performClick()
        compose.waitUntil(5_000) { requestedPeriods.size >= 3 }
        compose.waitForIdle()

        assertEquals(listOf("week", "month", "all"), requestedPeriods)
    }

    @Test
    fun dataAlreadyShown_nextLoadFails_showsStaleStripNotError() {
        var round = 0
        start {
            round++
            if (round == 1) MockResponse().setResponseCode(200).setBody(
                """{"period":"week","total":45000,"trips":5,"by_day":[]}""",
            ) else MockResponse().setResponseCode(500)
        }
        render()
        awaitText("45 000 ₽")
        compose.onNodeWithText("45 000 ₽").assertIsDisplayed()

        // Второй запрос (здесь — через смену периода, как и у "Кошелька") не отвечает.
        compose.onNodeWithText("Месяц").performClick()
        compose.waitUntil(5_000) { round >= 2 }
        compose.waitForIdle()

        assertTrue("данные прошлого ответа должны остаться видимыми", round >= 2)
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("45 000 ₽"))
        compose.onNodeWithText("45 000 ₽").assertIsDisplayed()
        compose.onNodeWithText("Не удалось обновить — цифры могут быть старыми").assertIsDisplayed()
        compose.onNodeWithText("Что-то пошло не так").assertDoesNotExist()
    }
}
