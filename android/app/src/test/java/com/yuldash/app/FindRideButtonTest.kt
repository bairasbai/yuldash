package com.yuldash.app

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.yuldash.app.data.ApiClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList

/**
 * «Найти попутку» на главной ведёт к ленте попуток, а не на вкладку «Поездки».
 *
 * Эмулятор, 14.10.2026: кнопка открывала «Мои поездки» (брони человека с фильтром «Активные») —
 * искать попутку там нечем. Лента «Ближайшие поездки» живёт на этом же экране под картой, поэтому
 * кнопка теперь остаётся на экране: сужает ленту до маршрута с карточки «Популярно» и прокручивает
 * её наверх. «Все маршруты» возвращает полную ленту (иначе пустой маршрут — тупик).
 *
 * У экрана больше нет колбэка «уйти на другую вкладку»: поведение проверяем по запросам к серверу.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FindRideButtonTest {
    @get:Rule val compose = createComposeRule()
    private val requests = CopyOnWriteArrayList<String>()
    private lateinit var server: MockWebServer

    @Before fun setup() {
        ApiClient.resetForTest()
        ApiClient.logout()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests.add(URLDecoder.decode("${request.method} ${request.path}", "UTF-8"))
                    val body = if (request.path.orEmpty().startsWith("/rides/near")) """{"items":[],"count":0}"""
                    else return MockResponse().setResponseCode(404).setBody("{}")
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 1000
    }

    @After fun cleanup() {
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server.shutdown()
    }

    private fun nearRequests() = requests.filter { it.startsWith("GET /rides/near?") }

    private fun waitFor(what: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(15000) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                condition()
            }
        } catch (e: Throwable) {
            println("WAIT_FAILED=$what requests=${requests.toList()}")
            throw e
        }
    }

    @Test fun findRideNarrowsTheFeedToTheRouteAndAllRoutesRestoresIt() {
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                MapScreen(
                    rides = emptyList(), activeTrip = null, ads = emptyList(), adStats = emptyMap(),
                    onBookRide = {}, onShareRide = {}, onAdImpression = {}, onAdClick = {}, onSos = {},
                    onDriver = {}, onBoost = {}, onClinicRides = {},
                )
            }
        }
        waitFor("первая лента") { nearRequests().isNotEmpty() }
        assertTrue("до нажатия лента не сужена: ${nearRequests()}", nearRequests().none { "from_city=" in it })
        compose.onAllNodesWithText("Все маршруты").assertCountEquals(0)

        compose.onNodeWithText("Найти попутку").performClick()

        waitFor("лента по маршруту") { nearRequests().any { "from_city=Баймаҡ" in it && "to_city=Сибай" in it } }
        compose.onNodeWithText("Ближайшие поездки").assertIsDisplayed()
        compose.onNodeWithText("Все маршруты").assertIsDisplayed()

        val before = nearRequests().size
        compose.onNodeWithText("Все маршруты").performClick()
        waitFor("полная лента") { nearRequests().drop(before).any { "from_city=" !in it } }
        compose.onAllNodesWithText("Все маршруты").assertCountEquals(0)
    }

}
