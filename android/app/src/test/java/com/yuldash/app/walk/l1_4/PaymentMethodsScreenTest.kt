package com.yuldash.app.walk.l1_4

import android.app.Application
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.AppLanguage
import com.yuldash.app.LocalAppLanguage
import com.yuldash.app.PayMethods
import com.yuldash.app.PaymentMethodsScreen
import com.yuldash.app.data.ApiClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/**
 * Способ расчёта на живой поездке (PaymentMethodsScreen.kt).
 *
 * R1 — нет активного заказа: выбор уходит вызывающему мгновенно, без сети (это память на
 * будущий заказ, серверу про неё знать ещё нечего).
 * R2 — есть активный заказ: приложение узнаёт о выборе (зовёт onPick) ТОЛЬКО после ответа
 * сервера, не раньше. Раньше `onPick`/галочка срабатывали сразу, а отправка уходила молча:
 * пропал интернет на секунду — пассажир уверен, что предупредил водителя, а тот ждёт прежний
 * расчёт.
 * R3 — сеть подвела: видно «Водитель об этом не знает» + «Повторить», и «Повторить» реально
 * шлёт запрос ещё раз (а не просто прячет плашку, не тронув сеть).
 * R4 — пока первый выбор летит, второй тап по другому способу не улетает вторым запросом:
 * после всего сценария сервер обязан увидеть РОВНО один запрос.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PaymentMethodsScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var server: MockWebServer
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val calls = AtomicInteger(0)

    private fun token(id: Int): String = "e30." + Base64.getUrlEncoder().withoutPadding()
        .encodeToString("{\"sub\":\"$id\"}".toByteArray()) + ".local"

    private fun startServer(code: (RecordedRequest) -> Int) {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path?.contains("/payment") == true) calls.incrementAndGet()
                    val c = code(request)
                    return if (c in 200..299) MockResponse().setResponseCode(c).setBody("{\"payment_method\":\"sbp\"}")
                    else MockResponse().setResponseCode(c).setBody("{}")
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 1_000
        ApiClient.init(context)
        ApiClient.saveToken(token(101))
    }

    @Before fun prepare() {
        ApiClient.resetForTest()
    }

    @After fun cleanup() {
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        if (::server.isInitialized) server.shutdown()
    }

    private fun render(current: String, activeOrderId: Int, onPick: (String) -> Unit = {}) {
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                PaymentMethodsScreen(current = current, onPick = onPick, onBack = {}, activeOrderId = activeOrderId)
            }
        }
    }

    @Test
    fun noActiveOrder_picksImmediately_withoutAnyNetworkCall() {
        startServer { 200 }
        var picked: String? = null
        render(current = PayMethods.CASH, activeOrderId = 0, onPick = { picked = it })

        compose.onNodeWithText("Переведёшь на телефон водителя").performClick()

        assertEquals(PayMethods.SBP, picked)
        assertEquals("activeOrderId=0 не должен звонить на сервер", 0, calls.get())
    }

    @Test
    fun activeOrder_onPickFiresOnlyAfterServerConfirms() {
        // Ответ сервера нарочно задержан настоящим latch (а не заморозкой часов Compose):
        // сама отправка идёт в фоновом потоке через реальный MockWebServer, и частичный
        // прогон кадра может случайно докрутить короткий ответ до конца. Latch даёт точно
        // наблюдаемое «запрос точно ещё летит».
        val latch = java.util.concurrent.CountDownLatch(1)
        startServer { latch.await(5, java.util.concurrent.TimeUnit.SECONDS); 200 }
        var picked: String? = null
        render(current = PayMethods.CASH, activeOrderId = 55, onPick = { picked = it })

        compose.onNodeWithText("Переведёшь на телефон водителя").performClick()
        compose.waitUntil(2_000) { calls.get() == 1 }
        // Ответ ещё не пришёл (висит на latch) — вызывающий ещё не узнал о выборе.
        assertEquals("onPick не должен звать раньше ответа сервера", null, picked)

        latch.countDown()
        compose.waitForIdle()
        assertEquals(PayMethods.SBP, picked)
    }

    @Test
    fun activeOrder_networkFailure_showsRetryRow_andRetryResends() {
        var succeed = false
        startServer { if (succeed) 200 else 503 }
        var picked: String? = null
        render(current = PayMethods.CASH, activeOrderId = 55, onPick = { picked = it })

        compose.onNodeWithText("Переведёшь на телефон водителя").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Водитель об этом не знает").assertIsDisplayed()
        assertEquals(1, calls.get())
        assertEquals("неудачный ответ не должен считаться выбором", null, picked)

        succeed = true
        compose.onNodeWithText("Повторить").performClick()
        compose.waitForIdle()

        assertEquals("«Повторить» обязан послать запрос ещё раз", 2, calls.get())
        assertEquals(PayMethods.SBP, picked)
        compose.onNodeWithText("Водитель об этом не знает").assertDoesNotExist()
    }

    @Test
    fun activeOrder_secondTapDuringFlight_sendsExactlyOneRequestTotal() {
        // Ответ нарочно задержан настоящим latch (а не заморозкой часов Compose): сама
        // отправка идёт в фоновом потоке через реальный MockWebServer, и на быстром localhost
        // первый запрос мог бы полностью завершиться за то же время, что уходит на обработку
        // кадра — тогда "второй тап" на самом деле пришёлся бы уже ПОСЛЕ ответа, ничего не
        // проверяя. Latch даёт точно наблюдаемое «первый запрос точно ещё летит».
        val latch = java.util.concurrent.CountDownLatch(1)
        startServer { latch.await(5, java.util.concurrent.TimeUnit.SECONDS); 200 }
        var picked: String? = null
        render(current = PayMethods.CASH, activeOrderId = 55, onPick = { picked = it })

        compose.onNodeWithText("Переведёшь на телефон водителя").performClick()   // SBP: запрос #1 летит
        compose.waitUntil(5_000) { calls.get() == 1 }   // запрос реально дошёл до сервера и висит
        // Второй тап по ДРУГОМУ способу, пока первый ответ ещё не пришёл. Если бы `занято`
        // не выключал остальные строки, это был бы второй, гоночный запрос.
        runCatching { compose.onNodeWithText("Обсудишь с водителем").performClick() }

        latch.countDown()
        compose.waitUntil(5_000) { picked != null }
        compose.waitForIdle()

        assertEquals("за весь сценарий должен уйти ровно один запрос", 1, calls.get())
        assertEquals("выигравшим обязан остаться первый тап (СБП)", PayMethods.SBP, picked)
    }
}
