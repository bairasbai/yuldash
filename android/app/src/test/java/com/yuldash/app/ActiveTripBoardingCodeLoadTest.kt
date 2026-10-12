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

/** DESIGN066: actual code card must leave loading on error, and retry only its GET. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ActiveTripBoardingCodeLoadTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mounted = mutableStateOf(false)
    private val requested = CopyOnWriteArrayList<String>()
    private val server = MockWebServer()
    @Volatile private var codeStatus = 200
    @Volatile private var codeBody = """{"code":"1234"}"""

    @Before fun prepare() {
        ApiClient.resetForTest()
        ApiClient.logout() // Only owned loopback REST; no authenticated socket/map.
        TripPassStore.initStores(MemoryDiskPreferences(), null)
        Outbox.initStores(MemoryDiskPreferences(), null)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requested += "${request.method} ${request.path}"
                if (request.path == "/bookings/42/boarding-code") return MockResponse()
                    .setResponseCode(codeStatus).setBody(codeBody)
                val body = when (request.path) {
                    "/bookings/42/messages" -> """{"items":[]}"""
                    "/bookings/42/role" -> """{"role":"passenger","status":"confirmed","driver_phase":"departed"}"""
                    "/bookings/42/details" -> """{"booking_id":42,"role":"passenger","status":"confirmed"}"""
                    else -> return MockResponse().setResponseCode(404).setBody("{}")
                }
                return MockResponse().setBody(body)
            }
        }
        server.start()
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

    private fun mount() {
        mounted.value = true
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                ActiveTripScreen(ride = null, contacts = emptyList(), bookingId = 42,
                    onBack = {}, onTripEnd = {}, onSos = {})
            }
        }
    }
    private fun waitFor(text: String) = compose.waitUntil(10000) {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun rejectsThenRetries(status: Int, body: String) {
        codeStatus = status
        codeBody = body
        mount()
        compose.waitUntil(10000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            requested.contains("GET /bookings/42/details")
        }
        compose.waitForIdle()
        compose.onNodeWithText("Не удалось загрузить код").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Код загружается").assertCountEquals(0)
        codeStatus = 200
        codeBody = """{"code":"5678"}"""
        compose.onNodeWithText("Повторить загрузку кода").performClick()
        waitFor("5678")
        compose.onNodeWithText("5678").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Не удалось загрузить код").assertCountEquals(0)
        assertEquals(2, requested.count { it == "GET /bookings/42/boarding-code" })
        assertEquals(1, requested.count { it == "GET /bookings/42/messages" })
        assertFalse(requested.any { it.startsWith("POST ") })
    }

    @Test fun serverOutageLeavesLoadingAndOffersTheSameCodeRetry() = rejectsThenRetries(503, "{}")
    @Test fun forbiddenCodeLeavesLoadingAndOffersTheSameCodeRetry() = rejectsThenRetries(403, "{}")
    @Test fun blankSuccessfulPayloadDoesNotBecomeAnEndlessSpinner() = rejectsThenRetries(200, """{"code":""}""")
    @Test fun validCodeAppearsWithoutAnErrorOrExtraRequests() {
        mount()
        waitFor("1234")
        compose.onNodeWithText("1234").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Не удалось загрузить код").assertCountEquals(0)
        assertEquals(1, requested.count { it == "GET /bookings/42/boarding-code" })
    }
}
