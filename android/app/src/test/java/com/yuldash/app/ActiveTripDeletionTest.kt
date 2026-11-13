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
import com.yuldash.app.data.BookingCommandApi
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
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import com.yuldash.app.data.OfflineTripSnapshots
import com.yuldash.app.data.TripPassAuthorityFixture

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
    @Volatile private var preparingPass: TripPass? = null
    private val preparationRequests = CopyOnWriteArrayList<String>()
    private val cancelBodies = CopyOnWriteArrayList<String>()
    @Volatile private var terminalStatus = "done"
    private var tripEndCalls = 0

    @Before fun setup() {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    preparationResponse(request)?.let { return it }
                    requests.add("${request.method} ${request.path}")
                    val body = when (request.path) {
                        "/me" -> """{"id":7,"created_at":"2026-01-01T00:00:00Z"}"""
                        "/bookings/42/cancel-preview" -> JSONObject().put("booking_status", terminalStatus)
                            .put("consequence", "driver_departed").put("terms_key", "a".repeat(64)).toString()
                        "/bookings/42/commands/v1/cancel" -> {
                            if (request.method != "POST") return MockResponse().setResponseCode(405)
                            cancelBodies.add(request.body.readUtf8())
                            terminalStatus = "cancelled"
                            """{"id":42,"ride_id":7,"passenger_id":7,"status":"cancelled","contact_then_cancel":false}"""
                        }
                        "/bookings/42/role" -> """{"role":"passenger","status":"$terminalStatus","driver_phase":"departed"}"""
                        "/bookings/42/messages" -> """{"items":[]}"""
                        "/bookings/42/boarding-code" -> """{"code":"5678"}"""
                        "/bookings/42/details" -> """{"booking_id":42,"role":"passenger","status":"$terminalStatus","from_city":"Город А","to_city":"Город Б"}"""
                        "/trips/42/receipt" -> """{"booking_id":42,"role":"passenger","from_city":"Город А","to_city":"Город Б","counterparty_name":"Серверный водитель","amount":700,"paid":true}"""
                        "/bookings/42/tip" -> """{"already_thanked":false}"""
                        else -> return MockResponse().setResponseCode(404).setBody("{}")
                    }
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(canonicalTripBody(request.path, body))
                }
            }
            start(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0)
        }
        TripPassAuthorityFixture.installSession(server.url("/").newBuilder().host("127.0.0.1").build().toString().trimEnd('/'))
        BookingCommandApi.init(MemoryDiskPreferences())
        ApiClient.testTimeoutMs = 1000
        TripPassStore.initStores(plain, secure)
        val pass = TripPass.fromJson(JSONObject().put("booking_id", 42).put("boarding_code", "old").put("pay_method", "negotiate"))
        preparePositive(pass)
        assertTrue(TripPassStore.save(context, pass))
        preparePositive(pass.copy(bookingId = 99, boardingCode = "unrelated"))
        assertTrue(TripPassStore.save(context, pass.copy(bookingId = 99, boardingCode = "unrelated")))
    }

    @After fun cleanup() {
        compose.runOnIdle { mounted.value = false }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        TripPassStore.initStores(MemoryDiskPreferences(), null)
        if (::server.isInitialized) server.shutdown()
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
        // An empty or still pending CODE can withdraw the authority needed to cancel.
        waitForText("5678")
        assertTrue(requests.contains("GET /bookings/42/role"))
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Отменить поездку"))
        compose.onNodeWithText("Отменить поездку").assertIsDisplayed()
        assertEquals("confirmed", terminalStatus)
        assertEquals(0, tripEndCalls)
        assertTrue(cancelBodies.isEmpty())
        compose.onNodeWithText("Отменить поездку").performClick()
        compose.onNodeWithText("Отменить поездку?").assertIsDisplayed()
        waitForText("Планы поменялись")
        compose.onNodeWithText("Планы поменялись").performClick()
        compose.onNodeWithText("Да, отменить").performClick()

        waitForText("Не удалось удалить сохранённую поездку")
        compose.onNodeWithText("Не удалось удалить сохранённую поездку").assertIsDisplayed()
        assertEquals("cancelled", terminalStatus)
        assertEquals(0, tripEndCalls)
        assertEquals(1, cancelBodies.size)
        assertEquals("plans_changed", JSONObject(cancelBodies.single()).getString("reason"))
        assertEquals("a".repeat(64), JSONObject(cancelBodies.single()).getString("expected_terms_key"))
        assertTrue(secure.restarted().contains("pass_42"))
        assertFalse(plain.restarted().contains(TripPassDeletion.KEY))
        plain.failWriteOf = null
        compose.onNodeWithText("Повторить удаление").performClick()
        waitForTripEnd()
        assertEquals(1, tripEndCalls)
        assertEquals(listOf("POST /bookings/42/commands/v1/cancel"), requests.filter { it.startsWith("POST ") })
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
    private fun preparationResponse(request: RecordedRequest): MockResponse? {
        val pass = preparingPass ?: return null
        val prefix = "/bookings/${pass.bookingId}"
        val body = when (request.path) {
            "$prefix/role" -> JSONObject().put("role", "passenger").put("status", "confirmed").put("driver_phase", "departed")
            "$prefix/details" -> pass.toJson().put("ride_id", pass.bookingId + 1000)
                .put("role", "passenger").put("status", "confirmed").put("contact_unlocked", true)
                .put("pay_method", pass.payMethod ?: "negotiate")
            "$prefix/boarding-code" -> JSONObject().put("code", pass.boardingCode)
            else -> return null
        }
        preparationRequests += "${request.method} ${request.path}"
        return MockResponse().setHeader("Content-Type", "application/json").setBody(body.toString())
    }
    private fun preparePositive(pass: TripPass) = kotlinx.coroutines.runBlocking {
        val start = preparationRequests.size
        check(preparingPass == null)
        preparingPass = pass
        try {
            ApiClient.getTripState(pass.bookingId).getOrThrow()
            val actual = ApiClient.getBookingDetails(pass.bookingId).getOrThrow()
            assertTrue("Setup must obtain its own actual current DETAILS", OfflineTripSnapshots.privateDetails(actual))
            assertEquals(pass.boardingCode, ApiClient.getBoardingCode(pass.bookingId).getOrThrow())
        } finally { preparingPass = null }
        val prefix = "GET /bookings/${pass.bookingId}"
        assertEquals(listOf("$prefix/role", "$prefix/details", "$prefix/boarding-code"),
            preparationRequests.drop(start))
    }

    private fun canonicalTripBody(path: String?, body: String): String {
        val endpoint = path.orEmpty()
        val details = endpoint.endsWith("/details")
        val role = endpoint.endsWith("/role")
        val receipt = endpoint.endsWith("/receipt")
        if (!details && !role && !receipt) return body
        val value = org.json.JSONObject(body)
        if ((details || receipt) && !value.has("ride_id")) value.put("ride_id", 1042)
        if (details) {
            if (!value.has("contact_unlocked")) value.put("contact_unlocked", value.optString("status") in setOf("confirmed", "onboard"))
            if (!value.has("pay_method")) value.put("pay_method", "negotiate")
        }
        if (role && value.optString("driver_phase") == "waiting") value.put("driver_phase", "")
        if ((role || details) && value.optString("status") == "onboard" && !value.has("onboard_at"))
            value.put("onboard_at", "2031-02-03T09:00:00.123456Z")
        return value.toString()
    }

}
