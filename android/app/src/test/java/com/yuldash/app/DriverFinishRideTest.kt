package com.yuldash.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.yuldash.app.data.ApiClient
import kotlinx.coroutines.CompletableDeferred
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
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Collections

/**
 * Д2 (решение Александра 24.10.2026): водитель везёт нескольких и нажал «Завершить» по одному.
 * Окно «Завершить поездку?» держится с «Завершаем…», пока сервер не принял и не пришёл список рейса;
 * остались пассажиры — экран итога не показываем (увели в «Едут с тобой»); последний — итог, как раньше.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DriverFinishRideTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var server: MockWebServer
    private val sent = Collections.synchronizedList(mutableListOf<String>())
    private var mounted: MutableState<Boolean>? = null

    @Before fun setup() {
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = 1_000
        ApiClient.logout()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    request.method == "GET" && path == "/bookings/7707/role" -> json(
                        """{"role":"driver","status":"confirmed","driver_phase":"departed","arrival_verified":false,"alone_with_driver":false}"""
                    )
                    request.method == "GET" && path == "/bookings/7707/messages" -> json("""{"items":[]}""")
                    request.method == "GET" && path == "/bookings/7707/boarding-code" -> json("""{"code":""}""")
                    request.method == "GET" && path == "/bookings/7707/details" -> json("{}")
                    request.method == "POST" && path == "/bookings/7707/driver-status" -> {
                        sent += request.body.readUtf8()
                        json("""{"ok":true,"status":"done"}""")
                    }
                    else -> MockResponse().setResponseCode(404).setBody("{}")
                }
            }
        }
        server.start()
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
    }

    @After fun teardown() {
        mounted?.let { state ->
            composeRule.runOnIdle { state.value = false }
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server.shutdown()
    }

    private fun mount(onDriverFinished: suspend (Int) -> Boolean) {
        val screenMounted = mutableStateOf(true)
        mounted = screenMounted
        composeRule.setContent {
            if (screenMounted.value) {
                CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                    ActiveTripScreen(ride = null, contacts = emptyList(), bookingId = 7707, onBack = {}, onTripEnd = {}, onSos = {},
                        onDriverFinished = onDriverFinished)
                }
            }
        }
        waitForText("Завершить поездку")
    }

    private fun confirmFinish() {
        composeRule.onNodeWithText("Завершить поездку").performClick()
        composeRule.onNodeWithText("Да, завершить").assertIsDisplayed().performClick()
    }

    @Test fun otherPassengersStillRidingSkipTheSummaryScreen() {
        val asked = Collections.synchronizedList(mutableListOf<Int>())
        val answer = CompletableDeferred<Boolean>()
        mount { id -> asked += id; answer.await() }
        confirmFinish()

        composeRule.waitUntil(10_000) {   // сервер принял «done», экран спросил, остались ли пассажиры
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            synchronized(asked) { asked.isNotEmpty() }
        }
        // Ждём список рейса — окно не закрылось, кнопка «Завершаем…», второй тап не уйдёт.
        composeRule.onNodeWithText("Завершаем…").assertIsDisplayed()
        assertEquals(listOf("{\"status\":\"done\"}"), synchronized(sent) { sent.toList() })
        assertEquals(listOf(7707), synchronized(asked) { asked.toList() })

        answer.complete(true)   // у рейса есть ещё живые брони — экран уже ушёл в «Едут с тобой»
        composeRule.waitUntil(10_000) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            composeRule.onAllNodesWithText("Завершаем…").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onAllNodesWithText("Поездка завершена").fetchSemanticsNodes().let {
            assertEquals("экран итога одного пассажира задержал бы водителя с остальными в машине", 0, it.size)
        }
    }

    @Test fun lastPassengerStillGetsTheSummaryScreen() {
        mount { false }
        confirmFinish()
        waitForText("Поездка завершена")
        assertEquals(1, synchronized(sent) { sent.size })
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 15_000) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun json(body: String, code: Int = 200): MockResponse =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
}
