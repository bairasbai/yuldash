package com.yuldash.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.yuldash.app.data.ApiClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Collections

/** DELTA07: водитель после выезда может завершить поездку, не отмечая ложное «Подъезжаю». */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AuditDelta07DriverFinishTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var server: MockWebServer
    private val sentStatuses = Collections.synchronizedList(mutableListOf<String>())
    private var mounted: MutableState<Boolean>? = null

    @Before
    fun setup() {
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
                        val body = request.body.readUtf8()
                        sentStatuses += body
                        when {
                            body.contains("\"status\":\"arriving\"") -> json(
                                """{"detail":"too far"}""",
                                code = 409,
                            )
                            body.contains("\"status\":\"done\"") -> json("{}")
                            else -> json("""{"detail":"unexpected status"}""", code = 422)
                        }
                    }
                    else -> MockResponse().setResponseCode(404).setBody("{}")
                }
            }
        }
        server.start()
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
    }

    @After
    fun teardown() {
        mounted?.let { state ->
            composeRule.runOnIdle { state.value = false }
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server.shutdown()
    }

    @Test
    fun departedDriverCanConfirmDoneWithoutSendingArriving() {
        val screenMounted = mutableStateOf(true)
        mounted = screenMounted
        composeRule.setContent {
            if (screenMounted.value) {
                CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                    ActiveTripScreen(
                        ride = null,
                        contacts = emptyList(),
                        bookingId = 7707,
                        onBack = {},
                        onTripEnd = {},
                        onSos = {},
                    )
                }
            }
        }

        waitForText("Подъезжаю")
        composeRule.onNodeWithText("Подъезжаю").assertIsDisplayed()

        composeRule.onNodeWithText("Завершить поездку").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Завершить поездку?").assertIsDisplayed()
        assertEquals(emptyList<String>(), synchronized(sentStatuses) { sentStatuses.toList() })

        composeRule.onNodeWithText("Да, завершить").assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            synchronized(sentStatuses) { sentStatuses.any { it.contains("\"status\":\"done\"") } }
        }

        val bodies = synchronized(sentStatuses) { sentStatuses.toList() }
        assertEquals(1, bodies.size)
        assertFalse(bodies.single().contains("\"status\":\"arriving\""))
        assertEquals("{\"status\":\"done\"}", bodies.single())
    }

    @Test
    fun decisionBarKeepsBothDriverActionsVisibleWithLargeText() {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalAppLanguage provides AppLanguage.Ru,
                LocalDensity provides Density(density = 1f, fontScale = 1.6f),
            ) {
                ActiveTripDecisionBar(
                    primaryText = "Подъезжаю",
                    onMessage = {},
                    onPrimary = {},
                    finishText = "Завершить поездку",
                    onFinish = {},
                )
            }
        }

        composeRule.onNodeWithText("Написать").assertIsDisplayed()
        composeRule.onNodeWithText("Подъезжаю").assertIsDisplayed()
        composeRule.onNodeWithText("Завершить поездку").assertIsDisplayed()
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 15_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun json(body: String, code: Int = 200): MockResponse =
        MockResponse()
            .setResponseCode(code)
            .setHeader("Content-Type", "application/json")
            .setBody(body)
}
