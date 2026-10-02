package com.yuldash.app.walk.l1_4

import android.app.Application
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.AppLanguage
import com.yuldash.app.LocalAppLanguage
import com.yuldash.app.WalletScreen
import com.yuldash.app.data.ApiClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Вывод на карту (WalletScreen.kt, `PayoutCard`) — самая денежная часть кошелька.
 *
 * R1 — границы суммы (мин/макс/баланс) с сервера, не хардкод: ниже минимума / выше максимума /
 * выше баланса — кнопка выключена и видна причина человеку. «Всё» подставляет весь доступный
 * остаток.
 * R2 — ключ идемпотентности: при НЕОДНОЗНАЧНОМ отказе (`provider_unclear` — деньги могли уйти)
 * повтор уходит С ТЕМ ЖЕ ключом; при ОПРЕДЕЛЁННОМ отказе (банк явно отказал) повтор уходит
 * с НОВЫМ ключом. Перепутать — значит либо списать дважды, либо застрять в вечном отказе
 * (волна 219 из комментария файла).
 * R3 — двойное нажатие «Да, вывести» не отправляет второй вывод: пока первый ответ летит,
 * кнопки подтверждения выключены, и сервер видит РОВНО один запрос.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h900dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WalletPayoutFlowTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var server: MockWebServer
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val payoutKeys = mutableListOf<String>()

    private fun token(id: Int): String = "e30." + Base64.getUrlEncoder().withoutPadding()
        .encodeToString("{\"sub\":\"$id\"}".toByteArray()) + ".local"

    /** balanceKop=60000 (600 ₽), minKop=10000 (100 ₽), maxKop=500000 (5000 ₽), карта уже есть. */
    private fun start(payoutResponses: (JSONObject) -> MockResponse) {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path?.startsWith("/wallet/balance") == true ->
                        MockResponse().setResponseCode(200).setBody("""{"balance_kop":60000,"balance_rub":600}""")
                    request.path?.startsWith("/wallet/ledger") == true ->
                        MockResponse().setResponseCode(200).setBody("[]")
                    request.path?.startsWith("/wallet/payout/status") == true ->
                        MockResponse().setResponseCode(200).setBody(
                            """{"enabled":true,"balance_kop":60000,"has_requisite":true,"card_last4":"4242","min_kop":10000,"max_kop":500000}""",
                        )
                    request.path == "/wallet/payout" -> {
                        val body = JSONObject(request.body.readUtf8())
                        payoutKeys += body.optString("idempotency_key")
                        payoutResponses(body)
                    }
                    else -> MockResponse().setResponseCode(404).setBody("{}")
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 2_000
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
                WalletScreen(onBack = {})
            }
        }
    }

    private fun typeAmount(rub: String) {
        // Под GC долгого прогона всего пакета тестов один idle-чек после render() иногда
        // обгоняет ещё не отрисованный ответ /wallet/payout/status — ждём поле по-настоящему.
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("Сумма, ₽") and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasText("Сумма, ₽") and hasSetTextAction()).performTextInput(rub)
    }

    private fun failBody(code: String, msg: String) = MockResponse().setResponseCode(400)
        .setBody(JSONObject().put("detail", JSONObject().put("code", code).put("ru", msg).put("ba", msg)).toString())

    // ---- R1: границы суммы ----

    @Test
    fun amount_belowMinimum_disablesButtonAndExplainsWhy() {
        start { MockResponse().setResponseCode(200).setBody("""{"status":"ok","entry_id":1,"amount_kop":0,"balance_kop":60000}""") }
        render()
        compose.waitForIdle()

        typeAmount("50")   // меньше минимума (100 ₽)
        compose.onNodeWithText("Минимум 100 ₽").assertIsDisplayed()
        compose.onNodeWithTag("payout_submit").assertIsNotEnabled()
    }

    @Test
    fun amount_aboveBalance_disablesButtonAndShowsBalance() {
        start { MockResponse().setResponseCode(200).setBody("{}") }
        render()
        compose.waitForIdle()

        typeAmount("999")   // меньше максимума (5000), но больше доступного остатка (600, долга нет)
        compose.onNodeWithText("Доступно только 600 ₽").assertIsDisplayed()
        compose.onNodeWithTag("payout_submit").assertIsNotEnabled()
    }

    @Test
    fun allButton_fillsWholeAvailableBalance() {
        start { MockResponse().setResponseCode(200).setBody("{}") }
        render()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Всё").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText("Всё").performClick()
        compose.onNodeWithText("Вывести 600 ₽").assertIsDisplayed()
        compose.onNodeWithTag("payout_submit").assertIsEnabled()
    }

    @Test
    fun amount_aboveMaximum_disablesButtonAndExplainsWhy() {
        // Денежное правило из §5.3 (не было поймано ни одной поломкой): ветка «максимум за
        // раз» (5000 ₽ в этом стенде) отдельна от ветки «выше баланса» — сумма ниже баланса
        // (600 ₽), но выше разового максимума, обязана показать ИМЕННО текст про максимум.
        start { MockResponse().setResponseCode(200).setBody("{}") }
        render()
        compose.waitForIdle()

        typeAmount("5500")   // больше максимума (5000), хотя баланс вообще не об этом (600)
        compose.onNodeWithText("Максимум 5 000 ₽ за раз").assertIsDisplayed()
        compose.onNodeWithTag("payout_submit").assertIsNotEnabled()
    }

    /** Тот же стенд, что [start], но статус выплат отвечает ОДИН конкретный JSON целиком —
     *  нужен, чтобы задать свои payable_kop/owed_kop (вместо дефолтного "весь баланс доступен"). */
    private fun startWithPayoutStatus(payoutStatusJson: String, payoutResponses: (JSONObject) -> MockResponse = {
        MockResponse().setResponseCode(200).setBody("{}")
    }) {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path?.startsWith("/wallet/balance") == true ->
                        MockResponse().setResponseCode(200).setBody("""{"balance_kop":60000,"balance_rub":600}""")
                    request.path?.startsWith("/wallet/ledger") == true ->
                        MockResponse().setResponseCode(200).setBody("[]")
                    request.path?.startsWith("/wallet/payout/status") == true ->
                        MockResponse().setResponseCode(200).setBody(payoutStatusJson)
                    request.path == "/wallet/payout" -> {
                        val body = JSONObject(request.body.readUtf8())
                        payoutKeys += body.optString("idempotency_key")
                        payoutResponses(body)
                    }
                    else -> MockResponse().setResponseCode(404).setBody("{}")
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 2_000
        ApiClient.init(context)
        ApiClient.saveToken(token(101))
    }

    // ---- payable_kop/owed_kop (ревью Opus): вывод считаем от ДОСТУПНОГО остатка, не от баланса ----

    @Test
    fun driverWithDebt_payoutBoundsComeFromPayableNotRawBalance() {
        // Баланс 600 ₽, но 400 ₽ из них держит долг платформе — доступно реально только 200 ₽.
        startWithPayoutStatus(
            """{"enabled":true,"balance_kop":60000,"has_requisite":true,"card_last4":"4242",
                "min_kop":10000,"max_kop":500000,"payable_kop":20000,"owed_kop":40000}""",
        )
        render()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Всё").fetchSemanticsNodes().isNotEmpty() }

        // Объяснение долга — человеку, а не только цифра в подписи поля.
        compose.onNodeWithText(
            "На балансе 600 ₽, из них 400 ₽ уходит на долг платформе — доступно 200 ₽.",
        ).assertIsDisplayed()

        // «Всё» подставляет ДОСТУПНЫЙ остаток (200), а не весь баланс (600).
        compose.onNodeWithText("Всё").performClick()
        compose.onNodeWithText("Вывести 200 ₽").assertIsDisplayed()
        compose.onNodeWithTag("payout_submit").assertIsEnabled()

        // Сумма БОЛЬШЕ доступного, но МЕНЬШЕ сырого баланса (300 < 600, но > 200) — раньше
        // прошла бы как валидная и сервер отказал бы уже ПОСЛЕ подтверждения.
        compose.onNode(hasText("Сумма, ₽") and hasSetTextAction()).performTextReplacement("300")
        compose.onNodeWithText("Доступно только 200 ₽").assertIsDisplayed()
        compose.onNodeWithTag("payout_submit").assertIsNotEnabled()
    }

    @Test
    fun noDebt_payableEqualsBalance_noDebtBannerShown() {
        startWithPayoutStatus(
            """{"enabled":true,"balance_kop":60000,"has_requisite":true,"card_last4":"4242",
                "min_kop":10000,"max_kop":500000,"payable_kop":60000,"owed_kop":0}""",
        )
        render()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Всё").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText("уходит на долг платформе", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Всё").performClick()
        compose.onNodeWithText("Вывести 600 ₽").assertIsDisplayed()
    }

    // ---- R2: идемпотентность ----

    @Test
    fun ambiguousFailure_providerUnclear_keepsSameIdempotencyKeyOnRetry() {
        var attempt = 0
        start {
            attempt++
            if (attempt == 1) failBody("provider_unclear", "Не получилось отправить выплату. Попробуй позже")
            else MockResponse().setResponseCode(200).setBody("""{"status":"ok","entry_id":1,"amount_kop":20000,"balance_kop":40000}""")
        }
        render()
        compose.waitForIdle()
        typeAmount("200")
        compose.onNodeWithTag("payout_submit").performClick()
        compose.onNodeWithTag("payout_confirm").performClick()
        compose.waitUntil(5_000) { payoutKeys.size >= 1 }
        compose.waitForIdle()

        // Неоднозначный отказ не закрывает экран: сумма осталась, можно повторить тем же путём.
        compose.onNodeWithTag("payout_submit").performClick()
        compose.onNodeWithTag("payout_confirm").performClick()
        compose.waitUntil(5_000) { payoutKeys.size >= 2 }
        compose.waitForIdle()

        assertEquals(2, payoutKeys.size)
        assertEquals("provider_unclear обязан переиспользовать ключ — деньги могли уйти", payoutKeys[0], payoutKeys[1])
    }

    @Test
    fun definiteFailure_min_usesFreshIdempotencyKeyOnRetry() {
        var attempt = 0
        start {
            attempt++
            if (attempt == 1) failBody("min", "Минимальная сумма вывода — 100 ₽")
            else MockResponse().setResponseCode(200).setBody("""{"status":"ok","entry_id":1,"amount_kop":20000,"balance_kop":40000}""")
        }
        render()
        compose.waitForIdle()
        typeAmount("200")
        compose.onNodeWithTag("payout_submit").performClick()
        compose.onNodeWithTag("payout_confirm").performClick()
        compose.waitUntil(5_000) { payoutKeys.size >= 1 }
        compose.waitForIdle()

        compose.onNodeWithTag("payout_submit").performClick()
        compose.onNodeWithTag("payout_confirm").performClick()
        compose.waitUntil(5_000) { payoutKeys.size >= 2 }
        compose.waitForIdle()

        assertEquals(2, payoutKeys.size)
        assertNotEquals(
            "явный отказ обязан сменить ключ — иначе повтор рискует попасть в чужой застрявший ключ",
            payoutKeys[0], payoutKeys[1],
        )
    }

    // ---- R3: двойное нажатие ----

    @Test
    fun doubleConfirm_whileFirstRequestInFlight_sendsExactlyOnePayout() {
        // Часы НЕ замораживаем с самого начала: сам экран «Кошелёк» при открытии делает
        // настоящий сетевой поход за балансом/историей/статусом выплат, и заморозка часов до
        // того, как это долетит, оставила бы экран навечно в skeleton-состоянии (поле суммы
        // не появилось бы вовсе). Задержку моделируем настоящим latch на /wallet/payout —
        // именно его и проверяем.
        val latch = CountDownLatch(1)
        val calls = AtomicInteger(0)
        start {
            calls.incrementAndGet()
            latch.await(5, TimeUnit.SECONDS)
            MockResponse().setResponseCode(200).setBody("""{"status":"ok","entry_id":1,"amount_kop":20000,"balance_kop":40000}""")
        }
        render()
        compose.waitForIdle()
        typeAmount("200")
        compose.onNodeWithTag("payout_submit").performClick()   // открывает диалог подтверждения
        compose.onNodeWithTag("payout_confirm").performClick()  // "Да, вывести" — уходит запрос, зависает на latch
        compose.waitUntil(2_000) { calls.get() == 1 }

        compose.onNodeWithTag("payout_confirm").assertIsNotEnabled()
        compose.onNodeWithTag("payout_submit").assertIsNotEnabled()
        // Вторая защита — внутри обработчика (`if (busy) return`), не только видимое выключение
        // кнопки: пробуем нажать ещё раз, несмотря на disabled (неважно, бросит исключение или
        // тихо проигнорирует — важен итоговый счётчик запросов ниже).
        runCatching { compose.onNodeWithTag("payout_confirm").performClick() }

        latch.countDown()
        compose.waitForIdle()

        assertEquals("одно подтверждение — ровно один вывод на сервере", 1, calls.get())
    }
}
