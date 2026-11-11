package com.yuldash.app

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
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
import com.yuldash.app.data.MemoryDiskPreferences
import com.yuldash.app.data.TripPassStore
import com.yuldash.app.data.TripPassAuthorityFixture
import com.yuldash.app.data.TripPass
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
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
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/** Real screen and loopback transport: an unknown booking cannot become "confirmed" on failure. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ActiveTripOfflineHonestyTest {
    companion object { private val cleanupGuard = SnapshotWiringCleanupGuard() }
    private val requestJobs = RequestJobs()
    private var setupEntered = false
    private var serverStarted = false
    private var sessionInstalled = false
    private var contentSet = false
    private val ownTrace: (String) -> Unit = {
        if (it.startsWith("вошли в вызов ")) requestJobs.record()
    }
    @get:Rule val compose = createComposeRule(effectContext = requestJobs)
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val plain = MemoryDiskPreferences()
    private val secure = MemoryDiskPreferences()
    private val mounted = mutableStateOf(true)
    private val requests = CopyOnWriteArrayList<String>()
    private val authLogout = CopyOnWriteArrayList<Boolean>()
    private val cleanupAuthLogout = CopyOnWriteArrayList<Boolean>()
    private var explicitLogout = false
    @Volatile private var cleaningUp = false
    @Volatile private var detailsAvailable = false
    @Volatile private var receiptAvailable = false
    private var openedReceipt = 0
    private var backCalls = 0
    private lateinit var server: MockWebServer

    @Before fun setup() {
        cleanupGuard.requireHealthy()
        check(ApiClient.testTrace == null)
        setupEntered = true
        TripPassStore.initStores(plain, secure)
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    if (request.method == "POST" && path == "/auth/logout") {
                        val expectedOwner = request.getHeader("Authorization") ==
                            "Bearer " + TripPassAuthorityFixture.jwt(7)
                        (if (cleaningUp) cleanupAuthLogout else authLogout).add(expectedOwner)
                        return MockResponse().setHeader("Content-Type", "application/json").setBody("{}")
                    }
                    requests += "${request.method} $path"
                    if (request.method != "GET") return MockResponse().setResponseCode(405)
                    val body = when (path) {
                        // The independent role poll remains unavailable while details are retried.
                        "/bookings/42/role" -> return MockResponse().setResponseCode(503).setBody("{}")
                        "/bookings/42/details" -> {
                            if (!detailsAvailable) return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                            """{"booking_id":42,"ride_id":7,"role":"driver","status":"done","from_city":"Город А","to_city":"Город Б"}"""
                        }
                        "/bookings/42/boarding-code" -> """{"code":"5678"}"""
                        "/bookings/42/messages" -> """{"items":[]}"""
                        "/trips/42/receipt" -> {
                            if (!receiptAvailable) return MockResponse().setResponseCode(503).setBody("{}")
                            """{"booking_id":42,"ride_id":7,"role":"driver","from_city":"Город А","to_city":"Город Б","counterparty_name":"Тестовый пассажир","amount":400,"paid":true,"my_stars":3}"""
                        }
                        else -> return MockResponse().setResponseCode(404).setBody("{}")
                    }
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
                }
            }
            start(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0)
        }
        serverStarted = true
        TripPassAuthorityFixture.installSession(
            server.url("/").newBuilder().host("127.0.0.1").build().toString().trimEnd('/'), 7)
        sessionInstalled = true
        ApiClient.testTimeoutMs = 2000
        ApiClient.testTrace = ownTrace
    }

    @After fun cleanup() {
        try {
            cleanupGuard.requireHealthy()
            if (!setupEntered) return
            if (contentSet) {
                compose.runOnIdle { mounted.value = false }
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                compose.waitForIdle()
            }
            requestJobs.awaitCompleted(if (serverStarted) server.port else null) { compose.mainClock.advanceTimeByFrame() }
            cleaningUp = true
            val expectedCleanupLogout = sessionInstalled && ApiClient.isLoggedIn()
            if (sessionInstalled) ApiClient.logout()
            finishOfflineWriteFixture02(cleanupGuard) {
                assertEquals(if (expectedCleanupLogout) listOf(true) else emptyList<Boolean>(),
                    cleanupAuthLogout.toList())
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

    private fun mount(language: AppLanguage, hint: ActiveTripTerminalHint? = null) {
        contentSet = true
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(LocalAppLanguage provides language) {
                ActiveTripScreen(null, emptyList(), 42, onBack = { backCalls++ }, onTripEnd = {}, onSos = {},
                    onOpenReceipt = { openedReceipt = it }, terminalHint = hint)
            }
        }
    }

    private fun awaitText(text: String) = compose.waitUntil(15000) {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun assertNoInventedActiveTrip() {
        listOf("Поездка подтверждена", "Сәфәр раҫланды", "Подтверждено", "Раҫланды",
            "Код посадки", "Ултырыу коды", "5678", "Позвонить", "Шылтыратырға",
            "Водитель отметит посадку", "Я выехал", "Подъезжаю").forEach {
            compose.onAllNodesWithText(it).assertCountEquals(0)
        }
        assertTrue("Reading a failed booking must not change it", requests.all { it.startsWith("GET ") })
        assertEquals(if (explicitLogout) listOf(true) else emptyList<Boolean>(), authLogout.toList())
    }

    private fun unknownFailure(language: AppLanguage) {
        mount(language)
        val failed = if (language == AppLanguage.Ru) "Не удалось загрузить данные поездки" else "Сәфәр мәғлүмәттәрен йөкләп булманы"
        awaitText(failed)
        compose.onNodeWithText(failed).assertIsDisplayed()
        compose.onNodeWithText(if (language == AppLanguage.Ru) "Повторить загрузку данных" else "Мәғлүмәттәрҙе яңынан йөкләргә").assertIsEnabled()
        assertNoInventedActiveTrip()
        assertNull(TripPassStore.load(context, 42))
    }

    @Test fun unknownNetworkFailureIsHonestRussian() = unknownFailure(AppLanguage.Ru)
    @Test fun unknownNetworkFailureIsHonestBashkir() = unknownFailure(AppLanguage.Ba)

    private fun recoverUnknown(language: AppLanguage) {
        unknownFailure(language)
        detailsAvailable = true
        receiptAvailable = true
        compose.onNodeWithText(if (language == AppLanguage.Ru) "Повторить загрузку данных" else "Мәғлүмәттәрҙе яңынан йөкләргә").performClick()
        awaitText(if (language == AppLanguage.Ru) "Поездка завершена" else "Сәфәр тамамланды")
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            requests.any { it == "GET /trips/42/receipt" }
        }
        assertNoInventedActiveTrip()
        assertTrue(requests.any { it == "GET /trips/42/receipt" })
        assertNull(TripPassStore.load(context, 42))
    }

    @Test fun detailsRetryRecoversCompletionWithoutSuccessfulRolePollRussian() = recoverUnknown(AppLanguage.Ru)
    @Test fun detailsRetryRecoversCompletionWithoutSuccessfulRolePollBashkir() = recoverUnknown(AppLanguage.Ba)

    private fun knownCompletion(language: AppLanguage) {
        mount(language, knownTerminalTripHint(42, "done", "driver"))
        val completed = if (language == AppLanguage.Ru) "Поездка завершена" else "Сәфәр тамамланды"
        val receipt = if (language == AppLanguage.Ru) "Квитанция" else "Сәфәр квитанцияһы"
        awaitText(completed)
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            runCatching {
                compose.onNodeWithTag("rideshareCompletedList").performScrollToNode(hasTestTag("rideshareReceiptRetry"))
                compose.onNodeWithTag("rideshareReceiptRetry").assertIsDisplayed()
            }.isSuccess
        }
        assertNoInventedActiveTrip()
        assertFalse("Known completion must not load active trip details", requests.any { it == "GET /bookings/42/details" })
        receiptAvailable = true
        compose.onNodeWithTag("rideshareReceiptRetry").performClick()
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            requests.count { it == "GET /trips/42/receipt" } >= 2 &&
                runCatching {
                    compose.onNodeWithTag("rideshareCompletedList").performScrollToNode(hasText(receipt))
                    compose.onNodeWithText(receipt).assertIsDisplayed()
                }.isSuccess
        }
        compose.onNodeWithText(receipt).performClick()
        assertEquals(42, openedReceipt)
        assertNoInventedActiveTrip()
    }

    @Test fun knownCompletionStaysClosedAndReceiptRetryWorksRussian() = knownCompletion(AppLanguage.Ru)
    @Test fun knownCompletionStaysClosedAndReceiptRetryWorksBashkir() = knownCompletion(AppLanguage.Ba)

    @Test fun terminalHintCannotApplyToAnotherBooking() {
        mount(AppLanguage.Ru, knownTerminalTripHint(99, "done", "driver"))
        awaitText("Не удалось загрузить данные поездки")
        compose.onAllNodesWithText("Поездка завершена").assertCountEquals(0)
        assertNoInventedActiveTrip()
    }

    @Test fun logoutRemovesKnownCompletedScreenAndDoesNotContinueIt() {
        mount(AppLanguage.Ru, knownTerminalTripHint(42, "done", "driver"))
        awaitText("Поездка завершена")
        assertTrue(authLogout.isEmpty())
        compose.runOnIdle {
            explicitLogout = true
            ApiClient.logout()
        }
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            backCalls > 0 && authLogout.size == 1 &&
                compose.onAllNodesWithText("Поездка завершена").fetchSemanticsNodes().isEmpty()
        }
        assertNoInventedActiveTrip()
    }

    @Test fun knownCompletionRemovesOnlyItsOwnOfflinePassport() {
        // NOT_SAME_ASSERT: prove physical legacy-byte deletion without granting private access.
        val pass = TripPass.fromJson(JSONObject().put("booking_id", 42).put("boarding_code", "1234"))
        val ownBytes = pass.toJson().toString()
        val otherBytes = pass.copy(bookingId = 99, boardingCode = "unrelated").toJson().toString()
        assertTrue(secure.edit().putString("pass_42", ownBytes).putString("pass_99", otherBytes).commit())
        assertEquals(ownBytes, secure.restarted().getString("pass_42", null))
        assertEquals(otherBytes, secure.restarted().getString("pass_99", null))
        assertFalse(plain.restarted().contains("pass_42"))
        assertFalse(plain.restarted().contains("pass_99"))
        mount(AppLanguage.Ru, knownTerminalTripHint(42, "done", "driver"))
        awaitText("Поездка завершена")
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            !plain.restarted().contains("pass_42") && !secure.restarted().contains("pass_42")
        }
        assertEquals(otherBytes, secure.restarted().getString("pass_99", null))
        assertFalse(plain.restarted().contains("pass_99"))
        assertNoInventedActiveTrip()
    }

    @Test fun knownCancellationDoesNotShowBoardingOrConfirmedTripRussian() = knownCancellation(AppLanguage.Ru)
    @Test fun knownCancellationDoesNotShowBoardingOrConfirmedTripBashkir() = knownCancellation(AppLanguage.Ba)

    private fun knownCancellation(language: AppLanguage) {
        mount(language, knownTerminalTripHint(42, "cancelled", "passenger"))
        awaitText(if (language == AppLanguage.Ru) "Поездка отменена" else "Сәфәр кире алынды")
        assertNoInventedActiveTrip()
        assertNull(TripPassStore.load(context, 42))
    }

    @Test fun hintRejectsMissingBookingUnknownRoleAndNonTerminalStatus() {
        assertNull(knownTerminalTripHint(null, "done", "driver"))
        assertNull(knownTerminalTripHint(0, "done", "driver"))
        assertNull(knownTerminalTripHint(42, "confirmed", "driver"))
        assertNull(knownTerminalTripHint(42, "", "driver"))
        assertNull(knownTerminalTripHint(42, "done", ""))
        assertEquals(42, knownTerminalTripHint(42, "done", "passenger")?.bookingId)
    }

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
