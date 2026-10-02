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
 * R2 — показ карточки решает ТОЛЬКО health-check ([OnlinePayGate]): до его ответа карточки
 * нет вовсе (не «выключено», а честно «ещё не узнали»), после «выключено» — нет до конца
 * сессии. 503 именно на ПОПЫТКЕ оплаты — другое дело: одноразовый сбой провайдера, карточка
 * остаётся и позволяет повторить (ревью Opus: раньше любой 503 на оплате прятал кнопку
 * навсегда, и временный сбой выглядел как «фичи больше нет»).
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
        // иначе один тест "портит" следующий. `checked` забыли сбрасывать при первой версии
        // этого теста — после первого же теста он навсегда оставался true, и проверка «карточка
        // не рисуется ДО ответа health-check» молчала бы во всех остальных тестах файла.
        OnlinePayGate.asked = false
        OnlinePayGate.unavailable = false
        OnlinePayGate.checked = false
    }

    @After fun cleanup() {
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        OnlinePayGate.asked = false
        OnlinePayGate.unavailable = false
        OnlinePayGate.checked = false
        if (::server.isInitialized) server.shutdown()
    }

    private fun render(amountKop: Int?, pay: suspend (String) -> Result<PayTripResultDto>) {
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                PayOnlineCard(amountKop = amountKop, pay = pay)
            }
        }
    }

    /**
     * Ждём, пока появится кнопка «Оплатить» — ПОСЛЕ правки R2 карточка решает, показываться ли,
     * только когда придёт ответ настоящего (пусть и локального) health-check запроса. Это
     * РЕАЛЬНЫЙ сетевой круг через MockWebServer на отдельном потоке — `compose.setContent`/
     * `waitForIdle()` синхронизируют композицию Compose, но не обязаны дожидаться стороннего
     * фонового ввода-вывода. Без явного ожидания здесь каждый тест этого файла, кликающий по
     * кнопке сразу после рендера, стал бы гонкой (иногда проходит, иногда «узел не найден»).
     */
    private fun awaitSubmitButton() {
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("pay_online_submit").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun headerAndButton_showExactlySameAmount() {
        startServerGateOpen()
        render(amountKop = 18_850) { Result.success(PayTripResultDto("paid", it, null, null)) }
        awaitSubmitButton()

        // 18 850 копеек = 188,50 ₽ — ОБЕ надписи обязаны содержать именно эту строку,
        // не "188 ₽" (кнопка) рядом со "188,50 ₽" (чек).
        compose.onNodeWithText("188,50 ₽").assertIsDisplayed()
        compose.onNodeWithText("Оплатить 188,50 ₽").assertIsDisplayed()
    }

    @Test
    fun paySays503_doesNotHideCardForever_retryAfterTransientOutageSucceeds() {
        // Переписанный тест (ревью Opus): раньше 503 именно на ПОПЫТКЕ оплаты прятал карточку
        // навсегда в рамках сессии — временный сбой провайдера выглядел как «фичи больше нет»,
        // и честный повторный клик был физически невозможен (кнопки просто больше нет). Теперь
        // 503 на оплате — одноразовое сообщение, карточка остаётся, повтор работает.
        startServerGateOpen()
        var attempts = 0
        var shouldFail503 = true
        render(amountKop = 100_00) {
            attempts++
            if (shouldFail503) Result.failure(ApiException(503, "Оплата скоро", ""))
            else Result.success(PayTripResultDto("paid", it, null, null))
        }
        awaitSubmitButton()

        compose.onNodeWithTag("pay_online_submit").performClick()
        compose.waitUntil(5_000) { attempts >= 1 }
        compose.waitForIdle()

        compose.onNodeWithTag("pay_online_submit").assertExists()
        compose.onNodeWithTag("pay_online_submit").assertIsEnabled()

        shouldFail503 = false
        compose.onNodeWithTag("pay_online_submit").performClick()
        compose.waitUntil(5_000) { attempts >= 2 }
        compose.waitForIdle()

        compose.onNodeWithText("Оплачено — спасибо!").assertIsDisplayed()
    }

    @Test
    fun cardStaysHidden_untilHealthCheckResponds_thenAppearsByItself() {
        // P5/2 (ревью Opus): раньше карточка рисовалась с первого кадра — `unavailable` стартовал
        // как false, и «выключено» выглядело так же, как «ещё не спросили». На медленной сети
        // пассажир мог увидеть и даже нажать рабочую с виду кнопку ДО того, как сервер вообще
        // ответил про /health.
        val latch = java.util.concurrent.CountDownLatch(1)
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/health") {
                        latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
                        return MockResponse().setResponseCode(200).setBody("{\"payments\":\"on\"}")
                    }
                    return MockResponse().setResponseCode(404).setBody("{}")
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 10_000
        ApiClient.init(context)
        ApiClient.saveToken(token(101))

        render(amountKop = 100_00) { Result.success(PayTripResultDto("paid", it, null, null)) }
        compose.waitForIdle()

        // /health ещё не ответил — рисовать нечего: ни кнопки, ни намёка на оплату.
        compose.onNodeWithTag("pay_online_submit").assertDoesNotExist()
        compose.onNodeWithText("Оплатить", substring = true).assertDoesNotExist()

        latch.countDown()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("pay_online_submit").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithTag("pay_online_submit").assertIsDisplayed()
    }

    @Test
    fun healthCheckNetworkFailure_stillShowsCard_notPermanentlyBlank() {
        // Если /health сам не ответил (не 200 с payments:off, а сетевой сбой) — это НЕ повод
        // прятать карточку навсегда: показываем как доступную, а настоящий 503 (если он есть)
        // поймает обработчик оплаты при реальной попытке.
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse().setResponseCode(500)
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 1_000
        ApiClient.init(context)
        ApiClient.saveToken(token(101))

        render(amountKop = 100_00) { Result.success(PayTripResultDto("paid", it, null, null)) }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("pay_online_submit").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithTag("pay_online_submit").assertIsDisplayed()
    }

    @Test
    fun healthSaysOff_cardNeverAppears_notEvenForAMoment() {
        // Денежное правило из §5.3 (не было поймано ни одной поломкой: во всех прежних тестах
        // онлайн-оплата включена) — /health, явно ответивший «выключено», не должен дать
        // карточке показаться ХОТЬ НА МИГ: удали эту строку обработки ответа — и ни один
        // прежний тест этого бы не заметил.
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/health" -> MockResponse().setResponseCode(200).setBody("{\"payments\":\"off\"}")
                    else -> MockResponse().setResponseCode(404).setBody("{}")
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 1_000
        ApiClient.init(context)
        ApiClient.saveToken(token(101))

        render(amountKop = 100_00) { Result.success(PayTripResultDto("paid", it, null, null)) }
        // ВАЖНО: ждём, что health-check РЕАЛЬНО завершился (checked=true), а не просто
        // compose.waitForIdle() — иначе «кнопки нет» могло бы означать и «ещё не узнали»,
        // и «узнали и выключено», тест бы не отличил одно от другого и не поймал бы поломку,
        // которая выключает именно ВТОРОЕ (сервер явно сказал «off», а клиент забыл спрятать).
        compose.waitUntil(5_000) { OnlinePayGate.checked }
        compose.waitForIdle()

        compose.onNodeWithTag("pay_online_submit").assertDoesNotExist()
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
        awaitSubmitButton()

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
        awaitSubmitButton()

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
        awaitSubmitButton()

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
        awaitSubmitButton()

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
        awaitSubmitButton()
        compose.onNodeWithTag("pay_online_submit").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Ждём подтверждения оплаты").assertIsDisplayed()

        compose.onNodeWithText("Проверить оплату").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Оплачено — спасибо!").assertIsDisplayed()
    }
}
