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
import com.yuldash.app.data.TripPassDeletion
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

/** Remote completion and failed durable cleanup through the real active-trip screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ActiveTripDeletionTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val plain = MemoryDiskPreferences()
    private val secure = MemoryDiskPreferences()
    private val mounted = mutableStateOf(false)
    private lateinit var server: MockWebServer
    private val requests = CopyOnWriteArrayList<String>()
    private val cancelBodies = CopyOnWriteArrayList<String>()
    @Volatile private var terminalStatus = "done"
    private var tripEndCalls = 0

    @Before fun setup() {
        ApiClient.resetForTest()
        ApiClient.logout() // No token: ChatSocket cannot open a real WebSocket.
        TripPassStore.initStores(plain, secure)
        val pass = TripPass.fromJson(JSONObject().put("booking_id", 42).put("boarding_code", "old"))
        assertTrue(TripPassStore.save(context, pass))
        assertTrue(TripPassStore.save(context, pass.copy(bookingId = 99, boardingCode = "unrelated")))
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests.add("${request.method} ${request.path}")
                    val body = when (request.path) {
                        "/bookings/42/cancel" -> {
                            if (request.method != "POST") return MockResponse().setResponseCode(405)
                            cancelBodies.add(request.body.readUtf8())
                            terminalStatus = "cancelled"
                            """{"status":"cancelled","contact_then_cancel":false}"""
                        }
                        "/bookings/42/role" -> """{"role":"passenger","status":"$terminalStatus","driver_phase":"departed"}"""
                        "/bookings/42/messages" -> """{"items":[]}"""
                        "/bookings/42/boarding-code" -> """{"code":""}"""
                        "/bookings/42/details" -> """{"booking_id":42,"role":"passenger","status":"$terminalStatus","from_city":"Город А","to_city":"Город Б"}"""
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

    @Test fun serverCompletedTripClearsStoredPassportWithoutLocalFinishAction() {
        openScreen()
        waitForText("Поездка завершена")
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            !secure.restarted().contains("pass_42")
        }
        assertTrue(requests.contains("GET /bookings/42/role"))
        assertFalse("Remote completion must not send another finish request", requests.any { it.startsWith("POST ") })
        verifyAfterRestart()
    }

    @Test fun failedDeletionShowsRetryAndRecoverySurvivesStorageRestart() {
        plain.failWriteOf = TripPassDeletion.KEY
        openScreen()
        waitForText("Не удалось удалить сохранённую поездку")
        compose.onNodeWithText("Не удалось удалить сохранённую поездку").assertIsDisplayed()
        compose.onNodeWithText("Повторить удаление").assertIsDisplayed()
        // Memory can already hide the pass after commit(false); inspect the independent disk.
        assertFalse(plain.restarted().contains(TripPassDeletion.KEY))
        assertTrue(secure.restarted().contains("pass_42"))
        assertEquals("unrelated", TripPassStore.load(context, 99)?.boardingCode)
        plain.failWriteOf = null
        compose.onNodeWithText("Повторить удаление").performClick()
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithText("Не удалось удалить сохранённую поездку").fetchSemanticsNodes().isEmpty() &&
                !secure.restarted().contains("pass_42")
        }
        compose.onNodeWithText("Поездка завершена").assertIsDisplayed()
        verifyAfterRestart()
    }

    @Test fun remoteCancellationDeletesPassportAndLeavesScreenExactlyOnce() {
        terminalStatus = "cancelled"
        openScreen()
        waitForTripEnd()
        assertEquals(1, tripEndCalls)
        assertFalse(mounted.value)
        assertFalse(secure.restarted().contains("pass_42"))
        assertFalse("Remote cancellation must not send a second cancellation", requests.any { it.startsWith("POST ") })
        verifyAfterRestart()
    }

    @Test fun remoteCancellationRetainsRetryUntilDeletionIsDurableThenLeavesOnce() {
        terminalStatus = "cancelled"
        plain.failWriteOf = TripPassDeletion.KEY
        openScreen()
        waitForText("Не удалось удалить сохранённую поездку")
        compose.onNodeWithText("Не удалось удалить сохранённую поездку").assertIsDisplayed()
        compose.onNodeWithText("Повторить удаление").assertIsDisplayed()
        assertEquals("A failed cleanup must not navigate away from its retry", 0, tripEndCalls)
        assertTrue(mounted.value)
        assertFalse(plain.restarted().contains(TripPassDeletion.KEY))
        assertTrue(secure.restarted().contains("pass_42"))
        assertEquals("unrelated", TripPassStore.load(context, 99)?.boardingCode)
        plain.failWriteOf = null
        compose.onNodeWithText("Повторить удаление").performClick()
        waitForTripEnd()
        assertEquals(1, tripEndCalls)
        assertFalse(mounted.value)
        assertFalse(secure.restarted().contains("pass_42"))
        assertFalse("Cleanup retry must not repeat the server cancellation", requests.any { it.startsWith("POST ") })
        verifyAfterRestart()
    }

    @Test fun durableDeferredDeletionAllowsExitAndClearsSecureDiskWhenAvailable() {
        terminalStatus = "cancelled"
        secure.failRemovalOf = "pass_42"
        openScreen()
        waitForTripEnd()
        assertEquals(1, tripEndCalls)
        assertFalse(mounted.value)
        compose.onAllNodesWithText("Не удалось удалить сохранённую поездку").assertCountEquals(0)
        assertNull(TripPassStore.load(context, 42))
        assertEquals("unrelated", TripPassStore.load(context, 99)?.boardingCode)
        assertTrue("Accepted deletion must be durable before leaving", plain.restarted().contains(TripPassDeletion.KEY))
        assertTrue("Failed physical deletion must remain visible in the disk model", secure.restarted().contains("pass_42"))
        assertFalse(requests.any { it.startsWith("POST ") })

        secure.failRemovalOf = null
        val restartedPlain = plain.restarted()
        val restartedSecure = secure.restarted()
        TripPassStore.initStores(restartedPlain, restartedSecure)
        assertFalse(restartedSecure.restarted().contains("pass_42"))
        assertNull(TripPassStore.load(context, 42))
        assertEquals("unrelated", TripPassStore.load(context, 99)?.boardingCode)
    }

    @Test fun realCancelButtonRetriesOnlyLocalDeletionAfterSuccessfulServerCancellation() {
        terminalStatus = "confirmed"
        plain.failWriteOf = TripPassDeletion.KEY
        openScreen()
        // This label requires the server's departed phase; the initial empty state is insufficient.
        waitForText("Водитель выехал к тебе")
        assertTrue(requests.contains("GET /bookings/42/role"))
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Отменить поездку"))
        compose.onNodeWithText("Отменить поездку").assertIsDisplayed()
        assertEquals("confirmed", terminalStatus)
        assertEquals(0, tripEndCalls)
        assertTrue(cancelBodies.isEmpty())
        compose.onNodeWithText("Отменить поездку").performClick()
        compose.onNodeWithText("Отменить поездку?").assertIsDisplayed()
        compose.onNodeWithText("Планы поменялись").performClick()
        compose.onNodeWithText("Да, отменить").performClick()

        waitForText("Не удалось удалить сохранённую поездку")
        compose.onNodeWithText("Не удалось удалить сохранённую поездку").assertIsDisplayed()
        assertEquals("cancelled", terminalStatus)
        assertEquals(0, tripEndCalls)
        assertEquals(1, cancelBodies.size)
        assertEquals("plans_changed", JSONObject(cancelBodies.single()).getString("reason"))
        assertTrue(secure.restarted().contains("pass_42"))
        assertFalse(plain.restarted().contains(TripPassDeletion.KEY))
        plain.failWriteOf = null
        compose.onNodeWithText("Повторить удаление").performClick()
        waitForTripEnd()
        assertEquals(1, tripEndCalls)
        assertEquals(listOf("POST /bookings/42/cancel"), requests.filter { it.startsWith("POST ") })
        assertEquals(1, cancelBodies.size)
        assertFalse(secure.restarted().contains("pass_42"))
        verifyAfterRestart()
    }

    private fun openScreen() {
        mounted.value = true
        compose.setContent {
            if (mounted.value) {
                CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                    // No city coordinates or ride: no real MapKit is mounted during first load.
                    ActiveTripScreen(ride = null, contacts = emptyList(), bookingId = 42,
                        onBack = {}, onTripEnd = { tripEndCalls++; mounted.value = false }, onSos = {})
                }
            }
        }
    }

    private fun waitForTripEnd() {
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            tripEndCalls > 0
        }
        compose.waitForIdle()
    }

    private fun waitForText(text: String) {
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun verifyAfterRestart() {
        assertNull(TripPassStore.load(context, 42))
        assertEquals("unrelated", TripPassStore.load(context, 99)?.boardingCode)
        compose.runOnIdle { mounted.value = false }
        TripPassStore.initStores(plain.restarted(), secure.restarted())
        assertNull(TripPassStore.load(context, 42))
        assertEquals("unrelated", TripPassStore.load(context, 99)?.boardingCode)
    }
}
