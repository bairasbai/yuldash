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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.yuldash.app.data.TripPassAuthorityFixture

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ActiveTripPollingSessionTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mounted = mutableStateOf(false)
    private val held = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val returned = CountDownLatch(1)
    private val requests = CopyOnWriteArrayList<Pair<String?, String?>>()
    @Volatile private var preparingRole = false
    private val preparationRequests = CopyOnWriteArrayList<String>()
    private val tokenA = TripPassAuthorityFixture.jwt(7)
    private val tokenB = TripPassAuthorityFixture.jwt(8)
    private var holdPath = "/bookings/42/role"
    @Volatile private var serverStatus = "confirmed"
    private var finished = 0
    private lateinit var server: MockWebServer

    @Before fun setup() {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    preparationRoleResponse(request)?.let { return it }
                    requests.add(request.path to request.getHeader("Authorization"))
                    if (request.path == holdPath) {
                        held.countDown()
                        if (!release.await(30, TimeUnit.SECONDS)) return MockResponse().setResponseCode(503)
                        returned.countDown()
                    }
                    val body = when (request.path) {
                        "/bookings/42/messages" -> """{"items":[]}"""
                        "/bookings/42/role" -> """{"role":"passenger","status":"$serverStatus","driver_phase":"waiting"}"""
                        "/bookings/42/boarding-code" -> """{"code":"5678"}"""
                        "/bookings/42/details" -> """{"booking_id":42,"role":"passenger","status":"confirmed"}"""
                        else -> return MockResponse().setResponseCode(404)
                    }
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(canonicalTripBody(request.path, body))
                }
            }
            start()
        }
        ApiClient.resetForTest()
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 45000
        ApiClient.init(context)
        ApiClient.logout()
        awaitSnapshotCleanup()
        ApiClient.saveToken(tokenA)
        TripPassStore.initStores(MemoryDiskPreferences(), MemoryDiskPreferences())
    }

    @After fun cleanup() {
        release.countDown()
        compose.runOnIdle { mounted.value = false }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        ApiClient.logout()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        TripPassStore.initStores(MemoryDiskPreferences(), null)
        server.shutdown()
    }

    @Test fun latePollAfterAccountSwitchMustNotChangeUiOrPollAsNewAccount() {
        mount()
        awaitHeld()
        // Let the independent initial history chain finish before switching accounts.
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            requests.any { it.first == "/bookings/42/details" }
        }
        compose.runOnIdle { ApiClient.logout() }
        awaitSnapshotCleanup()
        compose.runOnIdle { ApiClient.saveToken(tokenB) }
        serverStatus = "done"
        release.countDown()
        assertTrue(returned.await(5, TimeUnit.SECONDS))
        pumpPollPeriods()
        compose.onAllNodesWithText("Поездка завершена").assertCountEquals(0)
        assertEquals(0, finished)
        val leaked = requests.filter { it.first == holdPath && it.second == "Bearer $tokenB" }
        assertTrue("Old polling loop requested status as B: $leaked", leaked.isEmpty())
    }

    @Test fun normalPollingKeepsDoneTerminal() = checkTerminal("done")
    @Test fun normalPollingKeepsCancelledTerminal() = checkTerminal("cancelled")

    private fun checkTerminal(terminal: String) {
        mount()
        awaitHeld()
        release.countDown()
        assertTrue(returned.await(5, TimeUnit.SECONDS))
        // First confirmed response is processed before the next server state is selected.
        pump(1000)
        serverStatus = terminal
        pumpPollPeriods()
        if (terminal == "done") compose.onNodeWithText("Поездка завершена").assertIsDisplayed()
        else assertEquals(1, finished)
        val terminalCount = requests.count { it.first == holdPath }
        assertTrue("Normal polling never repeated", terminalCount >= 2)
        serverStatus = "confirmed"
        pumpPollPeriods()
        assertTrue("No subsequent poll witnessed", requests.count { it.first == holdPath } > terminalCount)
        if (terminal == "done") compose.onNodeWithText("Поездка завершена").assertIsDisplayed()
        else assertEquals("Late active status repeated terminal callback", 1, finished)
    }

    private fun mount() {
        prepareRole()
        mounted.value = true
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                ActiveTripScreen(ride = null, contacts = emptyList(), bookingId = 42,
                    onBack = {}, onTripEnd = { finished++ }, onSos = {})
            }
        }
    }

    private fun awaitHeld() {
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            held.count == 0L
        }
        assertTrue(requests.contains(holdPath to "Bearer $tokenA"))
    }

    // Advance Android's delayed main-loop tasks and allow IO dispatch to complete.
    // Advance Compose virtual time across the production 12-second poll period.
    private fun pumpPollPeriods() = pump(14000)
    private fun pump(millis: Long) {
        repeat((millis / 100).toInt()) {
            compose.mainClock.advanceTimeBy(100)
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
            Thread.sleep(10)
        }
        compose.waitForIdle()
    }
    private fun awaitSnapshotCleanup() = kotlinx.coroutines.runBlocking {
        kotlinx.coroutines.withTimeout(5000) {
            while (!com.yuldash.app.data.OfflineTripSnapshots.ensureResetCommitted()) kotlinx.coroutines.delay(10)
        }
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

    private fun preparationRoleResponse(request: RecordedRequest): MockResponse? {
        if (!preparingRole || request.path != "/bookings/42/role") return null
        preparationRequests += "${request.method} ${request.path}"
        return MockResponse().setBody(canonicalTripBody(request.path, """{"role":"passenger","status":"$serverStatus","driver_phase":"waiting"}"""))
    }
    private fun prepareRole() = kotlinx.coroutines.runBlocking {
        val start = preparationRequests.size
        preparingRole = true
        try { ApiClient.getTripState(42).getOrThrow() } finally { preparingRole = false }
        assertEquals(listOf("GET /bookings/42/role"), preparationRequests.drop(start))
        assertTrue(com.yuldash.app.data.OfflineTripSnapshots.allowsLive(42))
        assertNull(com.yuldash.app.data.OfflineTripSnapshots.privatePass(42))
    }

}
