package com.yuldash.app

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast
import java.util.concurrent.CopyOnWriteArrayList

/**
 * «Отозвать отклик?» в ленте заявок водителя (RequestsFeedScreen.kt): диалог говорит, что будет
 * (пассажир больше не увидит отклик), а отказ 409 — «пассажир уже принял» — закрывает диалог,
 * объясняет причину и обновляет ленту. При переносе экрана из RidesRequestsChatScreens.kt оба
 * поведения пропали (приёмка 23.10.2026, п.10). Настоящий экран + HTTP.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h1400dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RequestWithdrawResponseTest {
    @get:Rule val compose = createComposeRule()
    private val mounted = mutableStateOf(true)
    private val requests = CopyOnWriteArrayList<String>()
    private lateinit var server: MockWebServer
    private val feed = """{"items":[{"id":5,"passenger_name":"Пассажир","from_city":"Ургаза","to_city":"Бурибай","seats":1,"responded":true,"my_response_id":77}]}"""

    @Before fun setup() {
        ApiClient.resetForTest(); ApiClient.init(ApplicationProvider.getApplicationContext<Context>())
        ApiClient.saveToken("synthetic-withdraw-driver"); ApiClient.saveRole("driver")
        ShadowToast.reset()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests.add("${request.method} ${request.path}")
                    if (request.path?.startsWith("/requests/feed") == true) return MockResponse().setBody(feed)
                    if (request.method == "DELETE" && request.path == "/responses/77")
                        return MockResponse().setResponseCode(409).setBody("""{"detail":"Response already accepted"}""")
                    return MockResponse().setResponseCode(404).setBody("{}")
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 30000
    }

    @After fun cleanup() {
        compose.runOnIdle { mounted.value = false }
        ApiClient.logout(); ApiClient.resetForTest(); ApiClient.testTimeoutMs = null; server.shutdown()
    }

    private fun settle() { Shadows.shadowOf(Looper.getMainLooper()).idle() }
    private fun feedReads() = requests.count { it.startsWith("GET /requests/feed") }

    private fun alreadyAcceptedClosesDialogAndRefreshesFeed(language: AppLanguage) {
        val ba = language == AppLanguage.Ba
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides language) { if (mounted.value) RequestsFeedScreen({}) }
        }
        val withdraw = if (ba) "Кире алыу" else "Отозвать"
        compose.waitUntil(15000) { settle(); compose.onAllNodesWithText(withdraw).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(withdraw).performScrollTo().performClick()
        // Диалог называет последствие, а не только маршрут.
        val title = if (ba) "Яуапты кире аларғамы?" else "Отозвать отклик?"
        compose.onNodeWithText(title).assertExists()
        compose.onNodeWithText(
            if (ba) "Пассажир «Ургаза → Бурибай» яуабыңды башҡа күрмәйәсәк."
            else "Пассажир больше не увидит твой отклик на «Ургаза → Бурибай».",
        ).assertExists()
        val before = feedReads()
        compose.onNode(hasText(withdraw) and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(15000) {
            settle()
            requests.contains("DELETE /responses/77") && feedReads() > before &&
                compose.onAllNodesWithText(title).fetchSemanticsNodes().isEmpty()
        }
        assertEquals(
            if (ba) "Пассажир яуапты ҡабул иткән — кире алып булмай." else "Пассажир уже принял отклик — отозвать нельзя.",
            ShadowToast.getTextOfLatestToast(),
        )
        assertEquals("Отзыв уходит ровно один раз", 1, requests.count { it == "DELETE /responses/77" })
    }

    @Test fun alreadyAcceptedResponseClosesDialogExplainsAndRefreshesFeed() = alreadyAcceptedClosesDialogAndRefreshesFeed(AppLanguage.Ru)
    @Test fun bashkirAlreadyAcceptedResponseClosesDialogExplainsAndRefreshesFeed() = alreadyAcceptedClosesDialogAndRefreshesFeed(AppLanguage.Ba)
}
