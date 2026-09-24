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
import com.yuldash.app.data.TripPass
import com.yuldash.app.data.TripPassStore
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A successful code response arrives only after remote completion has durably removed the pass. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ActiveTripLateTerminalTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val plain = MemoryDiskPreferences()
    private val secure = MemoryDiskPreferences()
    private val mounted = mutableStateOf(false)
    private val codeRequested = CountDownLatch(1)
    private val releaseCode = CountDownLatch(1)
    private val detailsRequested = CountDownLatch(1)
    private val requests = CopyOnWriteArrayList<String>()
    private val fixtureFailures = CopyOnWriteArrayList<String>()
    private lateinit var server: MockWebServer

    @Before fun setup() {
        ApiClient.resetForTest()
        ApiClient.logout() // No authenticated WebSocket or external MapKit in this fixture.
        TripPassStore.initStores(plain, secure)
        val pass = TripPass.fromJson(JSONObject().put("booking_id", 42).put("boarding_code", "1234"))
        assertTrue(TripPassStore.save(context, pass))
        assertTrue(TripPassStore.save(context, pass.copy(bookingId = 99, boardingCode = "unrelated")))
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests.add("${request.method} ${request.path}")
                    if (request.method != "GET") return MockResponse().setResponseCode(405)
                    val body = when (request.path) {
                        "/bookings/42/role" -> {
                            // Completion cannot win before there is an outstanding code request.
                            if (!codeRequested.await(30, TimeUnit.SECONDS)) {
                                fixtureFailures.add("Boarding-code request did not start")
                                return MockResponse().setResponseCode(503)
                            }
                            """{"role":"passenger","status":"done","driver_phase":"departed"}"""
                        }
                        "/bookings/42/messages" -> """{"items":[]}"""
                        "/bookings/42/boarding-code" -> {
                            codeRequested.countDown()
                            if (!releaseCode.await(30, TimeUnit.SECONDS)) {
                                fixtureFailures.add("Late response was not released")
                                return MockResponse().setResponseCode(503)
                            }
                            """{"code":"5678"}"""
                        }
                        "/bookings/42/details" -> {
                            // This sequential call follows boarding-code's onSuccess in the real screen.
                            detailsRequested.countDown()
                            """{"booking_id":42,"role":"passenger","status":"confirmed","from_city":"Город А","to_city":"Город Б"}"""
                        }
                        "/trips/42/receipt" -> """{"booking_id":42,"role":"passenger","from_city":"Город А","to_city":"Город Б","counterparty_name":"Серверный водитель","amount":700,"paid":true}"""
                        "/bookings/42/tip" -> """{"already_thanked":false}"""
                        else -> return MockResponse().setResponseCode(404).setBody("{}")
                    }
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 45000 // A controlled latch, not an artificial network timeout.
    }

    @After fun cleanup() {
        // Release both waiters even when an assertion fails; shutdown must never hang on a fixture.
        releaseCode.countDown()
        codeRequested.countDown()
        compose.runOnIdle { mounted.value = false }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        TripPassStore.initStores(MemoryDiskPreferences(), null)
        if (::server.isInitialized) server.shutdown()
    }

    @Test fun delayedSuccessfulCodeCannotReviveCompletedTripPassport() {
        mounted.value = true
        compose.setContent {
            if (mounted.value) {
                CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                    ActiveTripScreen(ride = null, contacts = emptyList(), bookingId = 42,
                        onBack = {}, onTripEnd = {}, onSos = {})
                }
            }
        }
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            codeRequested.count == 0L &&
                compose.onAllNodesWithText("Поездка завершена").fetchSemanticsNodes().isNotEmpty() &&
                !secure.restarted().contains("pass_42")
        }
        assertEquals("The successful code must still be held after durable deletion", 1L, releaseCode.count)
        assertEquals("The response chain must not have advanced before release", 1L, detailsRequested.count)
        assertNull(TripPassStore.load(context, 42))

        releaseCode.countDown()
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            detailsRequested.count == 0L
        }
        // The next real API call is a causal witness that the late code callback has run.
        compose.waitForIdle()
        compose.onNodeWithText("Поездка завершена").assertIsDisplayed()
        compose.onAllNodesWithText("5678").assertCountEquals(0)
        compose.onAllNodesWithText("1234").assertCountEquals(0)
        assertNull(TripPassStore.load(context, 42))
        assertFalse(secure.restarted().contains("pass_42"))
        assertEquals("unrelated", TripPassStore.load(context, 99)?.boardingCode)
        assertEquals(1, requests.count { it == "GET /bookings/42/boarding-code" })
        assertFalse(requests.any { it.startsWith("POST ") })
        assertTrue("Fixture timeouts: $fixtureFailures", fixtureFailures.isEmpty())

        compose.runOnIdle { mounted.value = false }
        TripPassStore.initStores(plain.restarted(), secure.restarted())
        assertNull(TripPassStore.load(context, 42))
        assertEquals("unrelated", TripPassStore.load(context, 99)?.boardingCode)
    }
}
