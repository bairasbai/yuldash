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
import com.yuldash.app.data.MemoryDiskPreferences
import com.yuldash.app.data.Outbox
import com.yuldash.app.data.TripPassStore
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList

/** DESIGN062: actual passenger CTA and controlled HTTP, not the legacy status component. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ActiveTripPassengerStatusFailureTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mounted = mutableStateOf(false)
    private val posted = CopyOnWriteArrayList<String>()
    private lateinit var server: MockWebServer
    @Volatile private var responseCode = 200

    @Before fun setup() {
        ApiClient.resetForTest()
        ApiClient.logout() // No WebSocket; REST still goes to the owned HTTP recorder.
        TripPassStore.initStores(MemoryDiskPreferences(), null)
        Outbox.initStores(MemoryDiskPreferences(), null)
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path
                    if (path == "/bookings/42/trip-status") {
                        posted += request.body.readUtf8()
                        return MockResponse().setResponseCode(responseCode).setBody(
                            if (responseCode == 200) "{}" else """{"detail":{"ru":"Переход отклонён","ba":"Күсеү кире ҡағылды"}}"""
                        )
                    }
                    val body = when (path) {
                        "/bookings/42/role" -> """{"role":"passenger","status":"confirmed","driver_phase":"departed"}"""
                        "/bookings/42/messages" -> """{"items":[]}"""
                        "/bookings/42/boarding-code" -> """{"code":"1234"}"""
                        "/bookings/42/details" -> """{"booking_id":42,"role":"passenger","status":"confirmed"}"""
                        else -> return MockResponse().setResponseCode(404).setBody("{}")
                    }
                    return MockResponse().setBody(body).setHeader("Content-Type", "application/json")
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 1000
    }

    @After fun cleanup() {
        compose.runOnIdle { mounted.value = false }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        TripPassStore.initStores(MemoryDiskPreferences(), null)
        Outbox.initStores(MemoryDiskPreferences(), null)
        server.shutdown()
    }

    private fun open() {
        mounted.value = true
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                ActiveTripScreen(ride = null, contacts = emptyList(), bookingId = 42,
                    onBack = {}, onTripEnd = {}, onSos = {})
            }
        }
        waitFor("Я сел")
    }

    private fun waitFor(text: String) {
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun rejectedStatus(code: Int) {
        responseCode = code
        open()
        compose.onNodeWithText("Я сел").performClick()
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            posted.size == 1
        }
        // The retry action must still be boarding, rather than the next false arrival step.
        waitFor("Я сел")
        compose.onNodeWithText("Я сел").assertIsDisplayed()
        assertEquals(0, Outbox.count(context, 42))
        responseCode = 200
        compose.onNodeWithText("Я сел").performClick()
        waitFor("Доехал")
        assertEquals(2, posted.size)
        posted.forEach { assertEquals("sat", org.json.JSONObject(it).getString("status")) }
    }

    @Test fun forbiddenBoardingKeepsTheSameRetryAction() = rejectedStatus(403)
    @Test fun conflictingBoardingKeepsTheSameRetryAction() = rejectedStatus(409)
    @Test fun serverOutageKeepsTheSameRetryAction() = rejectedStatus(503)

    @Test fun acceptedBoardingAdvancesThePassengerAction() {
        open()
        compose.onNodeWithText("Я сел").performClick()
        waitFor("Доехал")
        assertEquals(1, posted.size)
        assertEquals("sat", org.json.JSONObject(posted.single()).getString("status"))
    }
}
