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
 * Экран «Кошелёк» (WalletScreen.kt) — состояния загрузка / пусто / ошибка+повтор / успех,
 * плюс честность про устаревшие данные (не 404/500, когда цифры на экране уже есть).
 *
 * R1 — пустая история показывает дружелюбную заглушку, а не голый список.
 * R2 — баланс и история не отвечают → полноэкранная ошибка с «Повторить», и «Повторить»
 * реально запрашивает сервер заново.
 * R3 — данные УЖЕ есть, а обновление не удалось → полоска «не удалось обновить», а не
 * замена экрана ошибкой (человек не должен терять то, что уже видел).
 * R4 — список упёрся в лимит запроса (50) → честная подпись об этом, а не тихий обрыв.
 * R5 — выплаты выключены на сервере → честная заглушка «скоро», без кнопок-обманок.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h900dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WalletScreenStatesTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var server: MockWebServer
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val ledgerCalls = AtomicInteger(0)

    private fun token(id: Int): String = "e30." + Base64.getUrlEncoder().withoutPadding()
        .encodeToString("{\"sub\":\"$id\"}".toByteArray()) + ".local"

    private fun ledgerEntry(id: Int, kind: String, amountKop: Int, note: String = "") = """
        {"id":$id,"kind":"$kind","amount_kop":$amountKop,"order_id":null,"booking_id":null,
         "note":"$note","created_at":"2026-07-05T10:00:00"}
    """.trimIndent()

    private fun start(
        balance: () -> MockResponse,
        ledger: () -> MockResponse,
        payout: () -> MockResponse = { MockResponse().setResponseCode(200).setBody("""{"enabled":false,"balance_kop":0,"has_requisite":false,"card_last4":"","min_kop":100000,"max_kop":5000000}""") },
    ) {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path?.startsWith("/wallet/balance") == true -> balance()
                    request.path?.startsWith("/wallet/ledger") == true -> { ledgerCalls.incrementAndGet(); ledger() }
                    request.path?.startsWith("/wallet/payout/status") == true -> payout()
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

    /** Ждём текст по-настоящему (а не один снимок после waitForIdle): под GC долгого прогона
     *  всего пакета тестов один idle-чек иногда обгоняет ещё не отрисованный кадр. */
    private fun awaitText(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun emptyLedger_showsFriendlyEmptyState() {
        start(
            balance = { MockResponse().setResponseCode(200).setBody("""{"balance_kop":0,"balance_rub":0}""") },
            ledger = { MockResponse().setResponseCode(200).setBody("[]") },
        )
        render()
        awaitText("Пока операций нет")

        compose.onNodeWithText("Пока операций нет").assertIsDisplayed()
    }

    @Test
    fun bothFail_showsFullScreenErrorWithWorkingRetry() {
        var fail = true
        start(
            balance = { if (fail) MockResponse().setResponseCode(500) else MockResponse().setResponseCode(200).setBody("""{"balance_kop":15050,"balance_rub":150}""") },
            ledger = { if (fail) MockResponse().setResponseCode(500) else MockResponse().setResponseCode(200).setBody("[${ledgerEntry(1, "earn", 15050, "Поездка")}]") },
        )
        render()
        awaitText("Что-то пошло не так")

        compose.onNodeWithText("Что-то пошло не так").assertIsDisplayed()

        fail = false
        compose.onNodeWithText("Повторить").performClick()
        awaitText("150,50 ₽")

        compose.onNodeWithText("150,50 ₽").assertIsDisplayed()
        compose.onNodeWithText("Что-то пошло не так").assertDoesNotExist()
        assertEquals("«Повторить» обязан реально переспросить сервер", 2, ledgerCalls.get())
    }

    @Test
    fun dataAlreadyShown_refreshFails_showsStaleStripNotError() {
        // round считает заход в load(): 1-й — баланс/история успешны (данные появляются на
        // экране), сам вывод НЕ настроен (503), что и показывает маленькую ошибку с
        // «Повторить» прямо в блоке выплат — им и дёргаем ВТОРОЙ load() без жеста
        // «потянуть вниз» (эмулировать drag в Robolectric не надо: кнопка делает то же самое,
        // `onRetry = { scope.launch { load() } }`). На 2-м заходе баланс/история уже рвутся —
        // ровно сценарий «потянул вниз, а обновить не вышло».
        val round = AtomicInteger(0)
        start(
            balance = {
                if (round.get() == 0) MockResponse().setResponseCode(200).setBody("""{"balance_kop":15050,"balance_rub":150}""")
                else MockResponse().setResponseCode(500)
            },
            ledger = {
                if (round.get() == 0) MockResponse().setResponseCode(200).setBody("[${ledgerEntry(1, "earn", 15050, "Поездка")}]")
                else MockResponse().setResponseCode(500)
            },
            payout = {
                val r = round.getAndIncrement()
                if (r == 0) MockResponse().setResponseCode(503)
                else MockResponse().setResponseCode(200).setBody("""{"enabled":false,"balance_kop":0,"has_requisite":false,"card_last4":"","min_kop":100000,"max_kop":5000000}""")
            },
        )
        render()
        awaitText("Не узнали про выплаты")

        compose.onNodeWithText("150,50 ₽").assertIsDisplayed()
        compose.onNodeWithText("Не узнали про выплаты").assertIsDisplayed()

        compose.onNodeWithText("Повторить").performClick()
        awaitText("Не удалось обновить — цифры могут быть старыми")

        // Данные остались на экране (не подменились полноэкранной ошибкой) — и честно сказано,
        // что обновить не вышло.
        compose.onNodeWithText("150,50 ₽").assertIsDisplayed()
        compose.onNodeWithText("Не удалось обновить — цифры могут быть старыми").assertIsDisplayed()
        compose.onNodeWithText("Что-то пошло не так").assertDoesNotExist()
    }

    @Test
    fun ledgerAtLimit_showsHonestCutoffNotice() {
        val fifty = (1..50).joinToString(",") { ledgerEntry(it, "earn", 1000) }
        start(
            balance = { MockResponse().setResponseCode(200).setBody("""{"balance_kop":50000,"balance_rub":500}""") },
            ledger = { MockResponse().setResponseCode(200).setBody("[$fifty]") },
        )
        render()
        // "500 ₽" — баланс, виден сразу (шапка экрана, без прокрутки) и появляется, только
        // когда ответ сервера уже обработан; ждём именно его, а не саму подпись о лимите —
        // та строка 51-я и, пока список не докручен, в дереве ещё не существует вовсе
        // (настоящая виртуализация LazyColumn, не «есть, но за кадром»).
        awaitText("500 ₽")
        compose.onAllNodes(hasScrollToNodeAction()).onFirst()
            .performScrollToNode(hasText("Показаны последние 50 операций."))

        compose.onNodeWithText("Показаны последние 50 операций.").assertIsDisplayed()
    }

    @Test
    fun payoutsDisabled_showsHonestSoonCard_noFakeButtons() {
        start(
            balance = { MockResponse().setResponseCode(200).setBody("""{"balance_kop":0,"balance_rub":0}""") },
            ledger = { MockResponse().setResponseCode(200).setBody("[]") },
            payout = { MockResponse().setResponseCode(200).setBody("""{"enabled":false,"balance_kop":0,"has_requisite":false,"card_last4":"","min_kop":100000,"max_kop":5000000}""") },
        )
        render()
        awaitText("Выплаты на карту — скоро")

        compose.onNodeWithText("Выплаты на карту — скоро").assertIsDisplayed()
        compose.onNodeWithText("Вывести", substring = true).assertDoesNotExist()
    }

    @Test
    fun payoutsEnabled_noCardYet_offersToAddCard() {
        start(
            balance = { MockResponse().setResponseCode(200).setBody("""{"balance_kop":0,"balance_rub":0}""") },
            ledger = { MockResponse().setResponseCode(200).setBody("[]") },
            payout = { MockResponse().setResponseCode(200).setBody("""{"enabled":true,"balance_kop":0,"has_requisite":false,"card_last4":"","min_kop":100000,"max_kop":5000000}""") },
        )
        render()
        awaitText("Добавить карту")

        compose.onNodeWithText("Добавить карту").assertIsDisplayed()
    }
}
