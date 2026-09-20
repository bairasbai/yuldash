package com.yuldash.app

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
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
import okhttp3.mockwebserver.SocketPolicy
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

/** The actual active-trip screen must distinguish a visible code from a durable offline copy. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ActiveTripBoardingCodeSaveTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val plain = MemoryDiskPreferences()
    private val secure = MemoryDiskPreferences()
    private val mounted = mutableStateOf(false)
    private val requests = CopyOnWriteArrayList<String>()
    @Volatile private var offlineRole = false
    private lateinit var server: MockWebServer

    @Before fun setup() {
        ApiClient.resetForTest()
        ApiClient.logout() // No real authenticated WebSocket is opened by this local UI fixture.
        TripPassStore.initStores(plain, secure)
        val pass = TripPass.fromJson(JSONObject().put("booking_id", 42).put("boarding_code", "1234"))
        assertTrue(TripPassStore.save(context, pass))
        assertEquals("1234", diskCode())
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests.add("${request.method} ${request.path}")
                    if (request.method != "GET") return MockResponse().setResponseCode(405)
                    if (offlineRole && request.path == "/bookings/42/role") return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                    val body = when (request.path) {
                        "/bookings/42/role" -> """{"role":"passenger","status":"confirmed","driver_phase":"departed"}"""
                        "/bookings/42/messages" -> """{"items":[]}"""
                        "/bookings/42/boarding-code" -> """{"code":"5678"}"""
                        "/bookings/42/details" -> """{"booking_id":42,"role":"passenger","status":"confirmed","from_city":"Город А","to_city":"Город Б"}"""
                        else -> return MockResponse().setResponseCode(404).setBody("{}")
                    }
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
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
        server.shutdown()
    }

    @Test fun failedCodeSaveShowsWarningAndRetryPersistsAfterRestart() {
        secure.failWriteOf = "pass_42"
        openScreen()
        waitForText("Код не сохранён на телефоне")
        compose.onNodeWithText("Код не сохранён на телефоне").assertIsDisplayed()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).performScrollToNode(hasText("Сохранить код ещё раз"))
        compose.onNodeWithText("Сохранить код ещё раз").assertIsDisplayed()
        // commit(false) can mutate process memory; only the independent disk model proves loss.
        assertEquals("1234", diskCode())
        assertEquals(1, requests.count { it == "GET /bookings/42/boarding-code" })

        secure.failWriteOf = null
        compose.onNodeWithText("Сохранить код ещё раз").performClick()
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            diskCode() == "5678" &&
                compose.onAllNodesWithText("Код не сохранён на телефоне").fetchSemanticsNodes().isEmpty()
        }
        compose.onAllNodesWithText("Сохранить код ещё раз").assertCountEquals(0)
        assertEquals("A local save retry must not fetch or generate another code", 1,
            requests.count { it == "GET /bookings/42/boarding-code" })
        assertFalse(requests.any { it.startsWith("POST ") })
        verifyAfterRestart()
    }

    @Test fun successfullySavedCodeSurvivesRestartWithoutWarning() {
        openScreen()
        waitForText("5678")
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            diskCode() == "5678" && requests.contains("GET /bookings/42/details")
        }
        compose.waitForIdle()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).performScrollToNode(hasText("5678"))
        compose.onNodeWithText("5678").assertIsDisplayed()
        compose.onAllNodesWithText("Код не сохранён на телефоне").assertCountEquals(0)
        compose.onAllNodesWithText("Сохранить код ещё раз").assertCountEquals(0)
        assertEquals(1, requests.count { it == "GET /bookings/42/boarding-code" })
        verifyAfterRestart()
    }

    @Test fun offlinePassportDoesNotShowKnownStaleCodeAfterWriteFailure() {
        offlineRole = true
        secure.failWriteOf = "pass_42"
        openScreen()
        waitForText("Код не сохранён на телефоне")
        var lastFailure: Throwable? = null
        try {
            compose.waitUntil(15000) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                runCatching {
                    compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).performScrollToNode(hasText("Паспорт поездки"))
                    compose.onNodeWithText("Паспорт поездки").assertIsDisplayed()
                }.onFailure { lastFailure = it }.isSuccess
            }
        } catch (e: Throwable) {
            println("OFFLINE_SCROLL_FAILURE=" + lastFailure)
            println("OFFLINE_PASS=" + TripPassStore.load(context, 42)?.boardingCode)
            println("OFFLINE_REQUESTS=" + requests.toList())
            println(compose.onRoot().printToString())
            throw e
        }
        assertEquals("1234", diskCode())
        compose.onAllNodesWithText("1234").assertCountEquals(0)
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).performScrollToNode(hasText("5678"))
        compose.onNodeWithText("5678").assertIsDisplayed()
        assertTrue(requests.contains("GET /bookings/42/role"))
    }

    private fun openScreen() {
        mounted.value = true
        compose.setContent {
            if (mounted.value) {
                CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                    // Empty coordinates and no Ride avoid mounting a real MapKit during this test.
                    ActiveTripScreen(ride = null, contacts = emptyList(), bookingId = 42,
                        onBack = {}, onTripEnd = {}, onSos = {})
                }
            }
        }
    }

    private fun waitForText(text: String) {
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun diskCode(): String =
        JSONObject(requireNotNull(secure.restarted().getString("pass_42", null))).getString("boarding_code")

    private fun verifyAfterRestart() {
        compose.runOnIdle { mounted.value = false }
        TripPassStore.initStores(plain.restarted(), secure.restarted())
        assertEquals("5678", TripPassStore.load(context, 42)?.boardingCode)
    }
}
