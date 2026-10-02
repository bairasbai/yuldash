package com.yuldash.app.walk.l1_4

import android.app.Application
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.AppLanguage
import com.yuldash.app.CourierEarningsScreen
import com.yuldash.app.LocalAppLanguage
import com.yuldash.app.NavSignals
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
 * «Мой заработок» курьера (CourierEarningsScreen.kt) — все суммы приходят в КОПЕЙКАХ
 * (в отличие от экрана водителя), форматируем через kopToRub.
 *
 * R1 — чистыми/комиссия показываются через kopToRub (копейки не теряются).
 * R2 — доставки ("Доставок") — счётчик, не деньги: `.toString()`, без денежного разряда
 *      (сверяем, что экран курьера УЖЕ делает правильно — образец для фикса в DriverEarnings).
 * R3 — пусто по неделе/месяцу → конкретный текст «за эту неделю/месяц», не общее "пока нет";
 *      кнопка «Смотреть заказы» зовёт сигнал на вкладку заказов и возвращает назад.
 * R4 — ошибка → «Повторить», который реально переспрашивает сервер.
 * R5 — смена периода уходит правильным параметром.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h900dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CourierEarningsScreenTest {
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

    @Before fun prepare() {
        ApiClient.resetForTest()
        NavSignals.openCourierOrders.value = false
    }

    @After fun cleanup() {
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        NavSignals.openCourierOrders.value = false
        if (::server.isInitialized) server.shutdown()
    }

    private fun render(onBack: () -> Unit = {}) {
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                CourierEarningsScreen(onBack = onBack)
            }
        }
    }

    /** Ждём текст по-настоящему (а не один снимок после waitForIdle): под GC долгого прогона
     *  всего пакета тестов один idle-чек иногда обгоняет ещё не отрисованный кадр. */
    private fun awaitText(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(text))
    }

    /**
     * Ждём ТОЛЬКО факт первого сетевого ответа (по счётчику запросов — он не зависит от того,
     * что именно сломала поломка), БЕЗ ожидания конкретного текста. Если поломка меняет сам
     * ожидаемый текст, [awaitText] ждёт текст, которого никогда не будет, и падает по
     * ТАЙМАУТУ — формально тест падает, но без понятного сообщения (ревью Opus, §5.2).
     * После этого хелпера тело теста обязано проверять содержимое ЖЁСТКИМ assert — вот
     * ЭТО и обязано ловить поломку.
     */
    private fun awaitFirstResponse() {
        compose.waitUntil(5_000) { requestedPeriods.isNotEmpty() }
        compose.waitForIdle()
    }

    @Test
    fun totalsUseKopToRub_keepingKopecks() {
        start {
            MockResponse().setResponseCode(200).setBody(
                """{"period":"week","net_kop":15050,"commission_kop":4950,"deliveries":3,"by_day":[]}""",
            )
        }
        render()
        awaitFirstResponse()

        compose.onNodeWithText("150,50 ₽").assertIsDisplayed()   // netKop — главная сумма
        compose.onNodeWithText("49,50 ₽").assertIsDisplayed()    // commissionKop
        compose.onNodeWithText("3").assertIsDisplayed()          // deliveries — штуки, не деньги
    }

    @Test
    fun dayRow_showsOwnNetSum_keepingKopecks() {
        // Денежное правило из §5.3 (не было поймано ни одной поломкой: во всех прежних тестах
        // by_day был пустым) — сумма КОНКРЕТНОГО дня обязана идти через kopToRub, копейки целы.
        start {
            MockResponse().setResponseCode(200).setBody(
                """{"period":"week","net_kop":20050,"commission_kop":0,"deliveries":2,
                    "by_day":[{"date":"2026-07-14","net_kop":20050,"deliveries":2}]}""",
            )
        }
        render()
        awaitFirstResponse()

        // Итог периода и сумма единственного дня здесь совпадают (та же цифра) — это и есть
        // честная сверка: если бы день считался НЕ через kopToRub, узел с "200,50 ₽" был бы
        // ровно один (итог), а не два.
        compose.onAllNodesWithText("200,50 ₽").assertCountEquals(2)
    }

    @Test
    fun deliveriesOverThousand_noThousandsSeparator_plainCount() {
        // Денежное правило из §5.3 (не было поймано ни одной поломкой — всюду 3 доставки):
        // deliveries — ШТУКИ, не деньги. У водителя точно такую же ошибку нашли и исправили
        // (E2); курьерский экран должен остаться образцом, а не тихо сломаться тем же образом.
        start { MockResponse().setResponseCode(200).setBody("""{"period":"all","net_kop":500000,"commission_kop":0,"deliveries":1234,"by_day":[]}""") }
        render()
        awaitFirstResponse()

        compose.onNodeWithText("1234").assertIsDisplayed()
        compose.onNodeWithText("1 234").assertDoesNotExist()
    }

    @Test
    fun emptyState_weekAndMonth_mentionThePeriod_elseIsGeneric() {
        start { period -> MockResponse().setResponseCode(200).setBody("""{"period":"$period","net_kop":0,"commission_kop":0,"deliveries":0,"by_day":[]}""") }
        render()
        awaitFirstResponse()
        compose.onNodeWithText("За эту неделю доставок нет").assertIsDisplayed()

        compose.onNodeWithText("Месяц").performClick()
        awaitText("За этот месяц доставок нет")
        compose.onNodeWithText("За этот месяц доставок нет").assertIsDisplayed()

        compose.onNodeWithText("Всё время").performClick()
        awaitText("Пока нет доставок")
        compose.onNodeWithText("Пока нет доставок").assertIsDisplayed()
    }

    @Test
    fun emptyState_actionOpensCourierOrdersTab_andGoesBack() {
        start { MockResponse().setResponseCode(200).setBody("""{"period":"week","net_kop":0,"commission_kop":0,"deliveries":0,"by_day":[]}""") }
        var wentBack = false
        render(onBack = { wentBack = true })
        awaitText("Смотреть заказы")

        compose.onNodeWithText("Смотреть заказы").performClick()

        assertTrue(NavSignals.openCourierOrders.value)
        assertTrue(wentBack)
    }

    @Test
    fun serverError_showsRetryable_errorState() {
        var fail = true
        start {
            if (fail) MockResponse().setResponseCode(500)
            else MockResponse().setResponseCode(200).setBody("""{"period":"week","net_kop":10000,"commission_kop":0,"deliveries":1,"by_day":[]}""")
        }
        render()
        awaitFirstResponse()
        compose.onNodeWithText("Что-то пошло не так").assertIsDisplayed()

        fail = false
        compose.onNodeWithText("Повторить").performClick()
        awaitText("100 ₽")

        compose.onNodeWithText("100 ₽").assertIsDisplayed()
        compose.onNodeWithText("Что-то пошло не так").assertDoesNotExist()
    }

    @Test
    fun periodSwitchFails_showsHonestError_notStaleOrFalseEmptyFromWrongTab() {
        // Исправленный тест (ревью Opus): раньше, если смена «Неделя→Месяц» срывалась,
        // d оставался от «Недели» (3 доставки) — экран либо тихо показывал чужую сумму под
        // «Месяцем» с плашкой устаревания, либо (если у старого периода было 0 доставок)
        // вовсе ложное «Пока нет доставок» без единого намёка на ошибку. Теперь — честно.
        var monthFails = true
        start { period ->
            when (period) {
                "week" -> MockResponse().setResponseCode(200).setBody(
                    """{"period":"week","net_kop":15000,"commission_kop":0,"deliveries":3,"by_day":[]}""",
                )
                "month" -> if (monthFails) MockResponse().setResponseCode(500)
                    else MockResponse().setResponseCode(200).setBody(
                        """{"period":"month","net_kop":40000,"commission_kop":0,"deliveries":8,"by_day":[]}""",
                    )
                else -> MockResponse().setResponseCode(200).setBody("""{"period":"$period","net_kop":0,"commission_kop":0,"deliveries":0,"by_day":[]}""")
            }
        }
        render()
        awaitText("150 ₽")

        compose.onNodeWithText("Месяц").performClick()
        awaitText("Что-то пошло не так")

        compose.onNodeWithText("150 ₽").assertDoesNotExist()
        compose.onNodeWithText("Пока нет доставок", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Что-то пошло не так").assertIsDisplayed()

        monthFails = false
        compose.onNodeWithText("Повторить").performClick()
        awaitText("400 ₽")
        compose.onNodeWithText("400 ₽").assertIsDisplayed()
    }

    @Test
    fun unpaidDeliveries_shownSeparately_notSilentlyDropped() {
        // Разбор жалобы подтвердил: за 1 доставку на 300 ₽ курьеру не заплатили (unpaid_*,
        // волна 191). Она уже не входит в net/deliveries — экран обязан назвать её отдельно.
        start {
            MockResponse().setResponseCode(200).setBody(
                """{"period":"week","net_kop":15000,"commission_kop":0,"deliveries":3,"by_day":[],
                    "unpaid_net_kop":30000,"unpaid_deliveries":1}""",
            )
        }
        render()
        awaitText("150 ₽")

        compose.onNodeWithText(
            "Ещё 1 доставка на 300 ₽ не оплачена — деньги не пришли, в заработок выше не включены.",
        ).assertIsDisplayed()
    }

    @Test
    fun noUnpaidDeliveries_noBannerShown() {
        start { MockResponse().setResponseCode(200).setBody("""{"period":"week","net_kop":15000,"commission_kop":0,"deliveries":3,"by_day":[]}""") }
        render()
        awaitText("150 ₽")

        compose.onNodeWithText("не оплачены", substring = true).assertDoesNotExist()
    }

    @Test
    fun periodSwitch_requestsCorrectPeriod_eachTime() {
        start { period -> MockResponse().setResponseCode(200).setBody("""{"period":"$period","net_kop":100,"commission_kop":0,"deliveries":0,"by_day":[]}""") }
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
}
