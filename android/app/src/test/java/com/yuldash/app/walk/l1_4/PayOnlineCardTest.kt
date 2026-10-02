package com.yuldash.app.walk.l1_4

import android.app.Application
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.AppLanguage
import com.yuldash.app.LocalAppLanguage
import com.yuldash.app.OnlinePayGate
import com.yuldash.app.PayOnlineCard
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.ApiException
import com.yuldash.app.data.PayTripResultDto
import kotlinx.coroutines.CompletableDeferred
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
 * «Оплатить онлайн» завершённой поездки (PayOnlineCard.kt).
 *
 * R1 — сумма в шапке и на кнопке — ОДНА и та же строка (kopToRub от тех же копеек). Исторический
 * баг файла: кнопка обещала рубли, посчитанные делением `kop / 100`, а чек показывал точную
 * сумму — на любых копейках расхождение подрывало доверие ко всему платежу.
 * R2 — сервер ответил 503 (оплата выключена) → карточка пропадает НАВСЕГДА в рамках сессии
 * ([OnlinePayGate]), а не до следующей перерисовки: пассажир не должен снова увидеть кнопку,
 * нажать и получить тот же отказ второй раз.
 * R3 — двойное нажатие «Оплатить» не шлёт второй платёж: пока первый ответ летит, кнопка
 * выключена, и `pay()` вызывается ровно один раз на одно нажатие.
 * R4 — успех с `confirmationUrl` ведёт в «Ждём подтверждения», успех со статусом paid/succeeded
 * ведёт сразу в «Оплачено» (ЮKassa mock/нал — тут нечего ждать).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PayOnlineCardTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var server: MockWebServer
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private fun token(id: Int): String = "e30." + Base64.getUrlEncoder().withoutPadding()
        .encodeToString("{\"sub\":\"$id\"}".toByteArray()) + ".local"

    /** /health всегда говорит "оплата включена" — гейт открыт, карточка показывается. */
    private fun startServerGateOpen(statusBody: String = "{}") {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/health" -> MockResponse().setResponseCode(200).setBody("{\"payments\":\"on\"}")
                    request.path?.contains("/status") == true -> MockResponse().setResponseCode(200).setBody(statusBody)
                    else -> MockResponse().setResponseCode(404).setBody("{}")
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
        // Синглтон-гейт переживает композиции внутри процесса — обнуляем перед КАЖДЫМ тестом,
        // иначе один тест "портит" следующий.
        OnlinePayGate.asked = false
        OnlinePayGate.unavailable = false
    }

    @After fun cleanup() {
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        OnlinePayGate.asked = false
        OnlinePayGate.unavailable = false
        if (::server.isInitialized) server.shutdown()
    }

    private fun render(amountKop: Int?, pay: suspend (String) -> Result<PayTripResultDto>) {
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                PayOnlineCard(amountKop = amountKop, pay = pay)
            }
        }
    }

    @Test
    fun headerAndButton_showExactlySameAmount() {
        startServerGateOpen()
        render(amountKop = 18_850) { Result.success(PayTripResultDto("paid", it, null, null)) }
        compose.waitForIdle()

        // 18 850 копеек = 188,50 ₽ — ОБЕ надписи обязаны содержать именно эту строку,
        // не "188 ₽" (кнопка) рядом со "188,50 ₽" (чек).
        compose.onNodeWithText("188,50 ₽").assertIsDisplayed()
        compose.onNodeWithText("Оплатить 188,50 ₽").assertIsDisplayed()
    }

    @Test
    fun serverSays503_hidesCardForTheRestOfTheSession() {
        startServerGateOpen()
        // Сумма — mutableState ВНУТРИ одной композиции (не второй setContent): реальный экран
        // тоже не пересоздаёт PayOnlineCard с нуля, он просто перерисовывает её с новым amountKop
        // (например, после перерасчёта чека), и именно это "навсегда в рамках сессии" обязано
        // пережить.
        val amountKop = androidx.compose.runtime.mutableStateOf<Int?>(100_00)
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                PayOnlineCard(amountKop = amountKop.value, pay = { Result.failure(ApiException(503, "Оплата скоро", "")) })
            }
        }

        compose.onNodeWithTag("pay_online_submit").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Оплатить", substring = true).assertDoesNotExist()

        amountKop.value = 500_00
        compose.waitForIdle()
        compose.onNodeWithText("Оплатить", substring = true).assertDoesNotExist()
    }

    @Test
    fun otherFailure_doesNotHideCard_canRetry() {
        startServerGateOpen()
        var attempts = 0
        render(amountKop = 100_00) {
            attempts++
            Result.failure(ApiException(400, "Банк отклонил операцию", ""))
        }

        compose.onNodeWithTag("pay_online_submit").performClick()
        compose.waitUntil(5_000) { attempts >= 1 }
        compose.waitForIdle()

        // Карточка осталась — это не "оплата выключена", а разовый отказ.
        compose.onNodeWithTag("pay_online_submit").assertExists()
        compose.onNodeWithTag("pay_online_submit").performClick()
        compose.waitUntil(5_000) { attempts >= 2 }
        compose.waitForIdle()
        assertEquals(2, attempts)
    }

    @Test
    fun doubleTap_whileFirstRequestInFlight_doesNotSendASecondPayment() {
        startServerGateOpen()
        var callCount = 0
        val gate = CompletableDeferred<Unit>()
        render(amountKop = 100_00) { method ->
            callCount++
            gate.await()
            Result.success(PayTripResultDto("paid", method, null, null))
        }

        compose.onNodeWithTag("pay_online_submit").performClick()
        // Ждём, пока pay() реально стартует (не гадаем числом кадров) — он сам зависнет на gate.
        compose.waitUntil(2_000) { callCount == 1 }
        // Запрос висит на gate — кнопка обязана быть выключена прямо сейчас.
        compose.onNodeWithTag("pay_online_submit").assertIsNotEnabled()
        // Вторая защита — внутри startPay() (`if (busy) return`), не только видимое выключение:
        // пробуем нажать ещё раз, несмотря на disabled.
        runCatching { compose.onNodeWithTag("pay_online_submit").performClick() }

        gate.complete(Unit)
        compose.waitForIdle()

        assertEquals("одно нажатие — ровно один вызов pay()", 1, callCount)
        compose.onNodeWithText("Оплачено — спасибо!").assertIsDisplayed()
    }

    @Test
    fun successWithConfirmationUrl_goesToWaiting_notStraightToPaid() {
        startServerGateOpen()
        render(amountKop = 100_00) {
            Result.success(PayTripResultDto("pending", it, 77, "https://example.invalid/confirm"))
        }

        compose.onNodeWithTag("pay_online_submit").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Ждём подтверждения оплаты").assertIsDisplayed()
        compose.onNodeWithText("Оплачено — спасибо!").assertDoesNotExist()
    }

    @Test
    fun successAlreadyPaid_goesStraightToPaid_withoutWaitingStage() {
        startServerGateOpen()
        render(amountKop = 100_00) {
            Result.success(PayTripResultDto("already_paid", it, null, null))
        }

        compose.onNodeWithTag("pay_online_submit").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Оплачено — спасибо!").assertIsDisplayed()
        compose.onNodeWithText("Ждём подтверждения оплаты").assertDoesNotExist()
    }

    @Test
    fun checkPayment_succeeded_movesFromWaitingToPaid() {
        startServerGateOpen(statusBody = "{\"payment_id\":77,\"status\":\"succeeded\",\"purpose\":\"ride\"}")
        render(amountKop = 100_00) {
            Result.success(PayTripResultDto("pending", it, 77, "https://example.invalid/confirm"))
        }
        compose.onNodeWithTag("pay_online_submit").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Ждём подтверждения оплаты").assertIsDisplayed()

        compose.onNodeWithText("Проверить оплату").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Оплачено — спасибо!").assertIsDisplayed()
    }
}
