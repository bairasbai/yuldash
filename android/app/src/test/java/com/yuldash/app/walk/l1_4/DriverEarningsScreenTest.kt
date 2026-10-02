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

    /**
     * Ждём ТОЛЬКО факт первого сетевого ответа (по счётчику запросов — он не зависит от того,
     * что именно сломала поломка) и один `waitForIdle()`, БЕЗ ожидания конкретного текста.
     *
     * Иначе, если поломка меняет сам ожидаемый текст (пример: денежная сумма теряет пробел
     * разряда), [awaitText] ждёт текст, которого никогда не будет, и падает по ТАЙМАУТУ —
     * формально тест падает, но без понятного сообщения, и это не отличить от зависшей сети
     * (ревью Opus, §5.2). После этого хелпера тело теста обязано проверять содержимое
     * ЖЁСТКИМ `assertIsDisplayed()`/`assertDoesNotExist()` — вот ЭТО и обязано ловить поломку.
     */
    private fun awaitFirstResponse() {
        compose.waitUntil(5_000) { requestedPeriods.isNotEmpty() }
        compose.waitForIdle()
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
        awaitFirstResponse()

        // деньги — с разрядом-пробелом; scrollTo сам падает понятным сообщением, если
        // узла нет вовсе (не 5-секундный таймаут вслепую).
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("45 000 ₽"))
        compose.onNodeWithText("45 000 ₽").assertIsDisplayed()
        compose.onNodeWithText("1234").assertIsDisplayed()       // штуки — без него
        compose.onNodeWithText("1 234").assertDoesNotExist()
    }

    @Test
    fun unpaidTrips_shownSeparately_notSilentlyDropped() {
        // Разбор жалобы подтвердил: за 2 поездки на 900 ₽ не заплатили (unpaid_*, волна 190).
        // Они уже не входят в total/trips — экран обязан назвать их отдельно, а не промолчать.
        start {
            MockResponse().setResponseCode(200).setBody(
                """{"period":"week","total":5000,"trips":10,"by_day":[],
                    "unpaid_total":900,"unpaid_trips":2}""",
            )
        }
        render()
        awaitText("5 000 ₽")

        compose.onNodeWithText("Ещё 2 поездки на 900 ₽ не оплачены — деньги не пришли, в заработок выше не включены.")
            .assertIsDisplayed()
    }

    @Test
    fun noUnpaidTrips_noBannerShown() {
        start { MockResponse().setResponseCode(200).setBody("""{"period":"week","total":5000,"trips":10,"by_day":[]}""") }
        render()
        awaitText("5 000 ₽")

        compose.onNodeWithText("не оплачены", substring = true).assertDoesNotExist()
    }

    @Test
    fun dayRow_showsOwnSum_withThousandsSeparator() {
        // Денежное правило из §5.3 (не было поймано ни одной поломкой): сумма КОНКРЕТНОГО дня
        // (не только итог периода) обязана быть на экране и с тем же разрядом-пробелом.
        start {
            MockResponse().setResponseCode(200).setBody(
                """{"period":"week","total":65000,"trips":11,"by_day":[{"date":"2026-07-14","sum":20000,"trips":3}]}""",
            )
        }
        render()
        awaitFirstResponse()

        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("20 000 ₽"))
        compose.onNodeWithText("20 000 ₽").assertIsDisplayed()
    }

    @Test
    fun noTrips_showsFriendlyEmptyState() {
        start { MockResponse().setResponseCode(200).setBody("""{"period":"week","total":0,"trips":0,"by_day":[]}""") }
        render()
        awaitFirstResponse()

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
        awaitFirstResponse()
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
    fun periodSwitchFails_showsHonestError_notStaleNumberFromWrongTab() {
        // Исправленный тест (ревью Opus): раньше, если смена «Неделя→Месяц» срывалась, экран
        // тихо показывал сумму за НЕДЕЛЮ под выбранным «Месяцем» с плашкой «может быть
        // устарело» — выглядело как свежая цифра не того периода, а не как ошибка. Теперь —
        // честная ошибка, пока данные под рукой не совпадают с выбранной вкладкой.
        var monthFails = true
        start { period ->
            when (period) {
                "week" -> MockResponse().setResponseCode(200).setBody(
                    """{"period":"week","total":45000,"trips":5,"by_day":[]}""",
                )
                "month" -> if (monthFails) MockResponse().setResponseCode(500)
                    else MockResponse().setResponseCode(200).setBody(
                        """{"period":"month","total":99000,"trips":9,"by_day":[]}""",
                    )
                else -> MockResponse().setResponseCode(200).setBody("""{"period":"$period","total":0,"trips":0,"by_day":[]}""")
            }
        }
        render()
        awaitText("45 000 ₽")
        compose.onNodeWithText("45 000 ₽").assertIsDisplayed()

        compose.onNodeWithText("Месяц").performClick()
        awaitText("Что-то пошло не так")

        // Неделя — ЧУЖОЙ период для выбранного «Месяца»: показывать её тут хуже, чем честно
        // сказать «не получилось», даже с плашкой про устаревание — число вообще не оттуда.
        compose.onNodeWithText("45 000 ₽").assertDoesNotExist()
        compose.onNodeWithText("Что-то пошло не так").assertIsDisplayed()

        monthFails = false
        compose.onNodeWithText("Повторить").performClick()
        awaitText("99 000 ₽")
        compose.onNodeWithText("99 000 ₽").assertIsDisplayed()
    }
}
