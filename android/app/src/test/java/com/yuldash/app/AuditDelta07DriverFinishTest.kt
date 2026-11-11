package com.yuldash.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.TripPassAuthorityFixture
import com.yuldash.app.data.TripPassStore
import com.yuldash.app.data.MemoryDiskPreferences
import com.yuldash.app.data.ChatSocket
import com.yuldash.app.data.SnapshotWiringCleanupGuard
import com.yuldash.app.data.finishOfflineWriteFixture02
import com.yuldash.app.data.poisonOfflinePassportFixture03
import kotlinx.coroutines.Job
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import java.net.InetAddress
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
    companion object { private val cleanupGuard = SnapshotWiringCleanupGuard() }
    private val requestJobs = RequestJobs()
    private var setupEntered = false
    private var serverStarted = false
    private var sessionInstalled = false
    private var contentSet = false
    private val ownTrace: (String) -> Unit = {
        if (it.startsWith("вошли в вызов ")) requestJobs.record()
    }

    @get:Rule
    val composeRule = createComposeRule(effectContext = requestJobs)

    private lateinit var server: MockWebServer
    private val sentStatuses = Collections.synchronizedList(mutableListOf<String>())
    private val mounted = mutableStateOf(true)

    @Before
    fun setup() {
        cleanupGuard.requireHealthy()
        check(ApiClient.testTrace == null)
        setupEntered = true
        TripPassStore.initStores(MemoryDiskPreferences(), MemoryDiskPreferences())

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
                    request.method == "GET" && path == "/bookings/7707/details" -> json(
                        """{"booking_id":7707,"ride_id":7,"role":"driver","status":"confirmed","contact_unlocked":true}"""
                    )
                    request.method == "POST" && path == "/bookings/7707/driver-status" -> {
                        val body = request.body.readUtf8()
                        sentStatuses += body
                        when {
                            body.contains("\"status\":\"arriving\"") -> json(
                                """{"detail":"too far"}""",
                                code = 409,
                            )
                            body.contains("\"status\":\"done\"") -> json("""{"ok":true,"status":"done"}""")
                            else -> json("""{"detail":"unexpected status"}""", code = 422)
                        }
                    }
                    else -> MockResponse().setResponseCode(404).setBody("{}")
                }
            }
        }
        server.start(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0)
        serverStarted = true
        TripPassAuthorityFixture.installSession(
            server.url("/").newBuilder().host("127.0.0.1").build().toString().trimEnd('/'), 7)
        sessionInstalled = true
        ApiClient.testTimeoutMs = 1_000
        ApiClient.testTrace = ownTrace
    }

    @After
    fun teardown() {
        try {
            cleanupGuard.requireHealthy()
            if (!setupEntered) return
            if (contentSet) {
                composeRule.runOnIdle { mounted.value = false }
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                composeRule.waitForIdle()
            }
            requestJobs.awaitCompleted(if (serverStarted) server.port else null) { composeRule.mainClock.advanceTimeByFrame() }
            if (sessionInstalled) ApiClient.logout()
            finishOfflineWriteFixture02(cleanupGuard) {
                check(ApiClient.testTrace == null || ApiClient.testTrace === ownTrace)
                if (serverStarted) server.shutdown()
                ApiClient.resetForTest()
                ApiClient.testTimeoutMs = null
                TripPassStore.initStores(MemoryDiskPreferences(), null)
            }
        } catch (failure: Throwable) {
            poisonOfflinePassportFixture03(cleanupGuard, failure)
        } finally {
            if (ApiClient.testTrace === ownTrace) ApiClient.testTrace = null
        }
    }

    @Test
    fun departedDriverCanConfirmDoneWithoutSendingArriving() {
        val screenMounted = mounted
        contentSet = true
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
        contentSet = true
        composeRule.setContent {
            if (mounted.value) CompositionLocalProvider(
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

    /** Observes actual API callers; never creates a Job or replaces a dispatcher. */
    private class RequestJobs : ThreadContextElement<Job?>, AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<RequestJobs>
        private val current = ThreadLocal<Job?>()
        private val started = java.util.concurrent.CopyOnWriteArrayList<Job>()
        @Volatile private var missingParent = false

        override fun updateThreadContext(context: CoroutineContext): Job? =
            current.get().also { current.set(context[Job]) }

        override fun restoreThreadContext(context: CoroutineContext, oldState: Job?) {
            current.set(oldState)
        }

        fun record() {
            current.get()?.let { job ->
                started += job
                val caller = job.parent
                if (caller == null) missingParent = true else started += caller
            }
        }

        fun awaitCompleted(port: Int?, advanceFrame: () -> Unit) = runBlocking {
            withTimeout(5_000) {
                check(!missingParent) { "Owned caller completion unavailable" }
                val field = ChatSocket::class.java.getDeclaredField("client\$delegate")
                    .apply { isAccessible = true }
                val lazyClient = field.get(null) as Lazy<*>
                val client = lazyClient.takeIf { it.isInitialized() }?.value as? okhttp3.OkHttpClient
                while (started.any { !it.isCompleted } || client?.dispatcher?.let {
                        it.runningCalls() + it.queuedCalls()
                    }.orEmpty().any { it.request().url.host == "127.0.0.1" && it.request().url.port == port }) {
                    Snapshot.sendApplyNotifications()
                    advanceFrame()
                    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                    delay(10)
                }
            }
        }
    }
}
