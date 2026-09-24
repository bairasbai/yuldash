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
    private var holdPath = "/bookings/42/role"
    @Volatile private var serverStatus = "confirmed"
    private var finished = 0
    private lateinit var server: MockWebServer

    @Before fun setup() {
        ApiClient.resetForTest()
        ApiClient.init(context)
        ApiClient.logout()
        ApiClient.saveToken("local-account-A")
        TripPassStore.initStores(MemoryDiskPreferences(), MemoryDiskPreferences())
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
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
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 45000
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
        compose.runOnIdle {
            ApiClient.logout()
            ApiClient.saveToken("local-account-B")
        }
        serverStatus = "done"
        release.countDown()
        assertTrue(returned.await(5, TimeUnit.SECONDS))
        pumpPollPeriods()
        compose.onAllNodesWithText("Поездка завершена").assertCountEquals(0)
        assertEquals(0, finished)
        val leaked = requests.filter { it.first == holdPath && it.second == "Bearer local-account-B" }
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
        assertTrue(requests.contains(holdPath to "Bearer local-account-A"))
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
}
