package com.yuldash.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.yuldash.app.data.ApiClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.TimeUnit

/**
 * BE29: «Мои данные» должен объяснять три разных вида работы с координатами.
 * Проверяем загруженный экран через настоящий ApiClient и ответ MockWebServer, а не DTO отдельно.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AuditBe29MyDataLocationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var server: MockWebServer

    @Before
    fun setup() {
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = 1_000
        server = MockWebServer()
        server.start()
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
    }

    @After
    fun teardown() {
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server.shutdown()
    }

    private fun response() = MockResponse()
        .setResponseCode(200)
        .addHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(
            """
            {
              "rides": 3,
              "rides_days": 180,
              "bookings": 1,
              "messages": 0,
              "messages_days": 30,
              "voices": 0,
              "voices_days": 30,
              "notifications": 0,
              "notifications_days": 30,
              "driver_docs": 0,
              "driver_docs_removable": false,
              "location_stored": true,
              "live_location_history_stored": false,
              "route_location_points": 5,
              "route_location_points_days": 180,
              "sos_location_events": 2,
              "open_sos_location_events": 1,
              "sos_location_days_from_signal": 180,
              "card_stored": false
            }
            """.trimIndent(),
        )

    private fun showAndVerifyLoaded(language: AppLanguage, loadedAnchor: String) {
        server.enqueue(response())
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides language) {
                MyDataScreen(onBack = {})
            }
        }

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(loadedAnchor).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()

        val request = server.takeRequest(5, TimeUnit.SECONDS)
        assertEquals("GET", request?.method)
        assertEquals("/me/data", request?.path)
    }

    private fun assertVisibleTexts(expected: List<String>) {
        val missing = expected.filter { text ->
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()
        }
        assertTrue("Загруженный экран не показал: ${missing.joinToString(" | ")}", missing.isEmpty())
        expected.forEach { composeRule.onNodeWithText(it).assertIsDisplayed() }
    }

    @Test
    fun russianExplainsLiveRouteAndSosLocationSeparately() {
        showAndVerifyLoaded(AppLanguage.Ru, loadedAnchor = "3 поездки")

        assertVisibleTexts(
            listOf(
                "Движение в реальном времени",
                "Без отдельного архива",
                "Координаты видны участникам только во время поездки",
                "Точки маршрутов поездок",
                "5 точек",
                "Видны только участникам поездки. Проверяем для удаления после 180 дней; связанные записи могут продлить срок",
                "Геолокация при SOS",
                "2 события · 1 открыто до обработки",
                "Обработанные записи исчезнут через 180 дней от даты сигнала; открытые остаются до решения администратора",
            ),
        )
        composeRule.onNodeWithText("Точная геолокация").assertDoesNotExist()
    }

    @Test
    fun bashkirExplainsLiveRouteAndSosLocationSeparately() {
        showAndVerifyLoaded(AppLanguage.Ba, loadedAnchor = "3 сәфәр")

        assertVisibleTexts(
            listOf(
                "Реаль ваҡыттағы хәрәкәт",
                "Айырым архив юҡ",
                "Координаталар сәфәрҙә ҡатнашыусыларға сәфәр ваҡытында ғына күренә",
                "Сәфәр маршруттарының нөктәләре",
                "5 нөктә",
                "Сәфәрҙә ҡатнашыусыларға ғына күренә. 180 көндән һуң юйыу өсөн тикшерәбеҙ; бәйле яҙмалар ваҡытты оҙайтыуы мөмкин",
                "SOS ваҡытындағы геолокация",
                "2 ваҡиға · 1 эшкәртелгәнсе асыҡ",
                "Эшкәртелгән яҙмалар сигнал көнөнән 180 көн үткәс юйыла; асыҡтары администратор ҡарарына тиклем ҡала",
            ),
        )
        composeRule.onNodeWithText("Теүәл геолокация").assertDoesNotExist()
    }

    @Test
    fun missingNewFieldsUseSafeDefaults() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json; charset=utf-8")
                .setBody("""{"rides":0,"rides_days":180,"card_stored":false}"""),
        )

        val data = ApiClient.getMyData().getOrThrow()

        assertFalse(data.liveLocationHistoryStored)
        assertEquals(0, data.routeLocationPoints)
        assertEquals(0, data.routeLocationPointsDays)
        assertEquals(0, data.sosLocationEvents)
        assertEquals(0, data.openSosLocationEvents)
        assertEquals(0, data.sosLocationDaysFromSignal)
        assertEquals("/me/data", server.takeRequest(5, TimeUnit.SECONDS)?.path)
    }
}
