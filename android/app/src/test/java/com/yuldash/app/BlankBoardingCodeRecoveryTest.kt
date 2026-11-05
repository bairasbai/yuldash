package com.yuldash.app

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.yuldash.app.data.*
import com.yuldash.app.ui.theme.YuldashTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Actual production parents and HTTP. No authority/session reinstall after positive preparation.
 * Old durable bytes are evidence, never permission to show a previous phone, code or meeting point.
 * Composition keeps the existing fixture's unsafe teardown behind our explicit background join.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class BlankBoardingCodeRecoveryTest {
    @get:Rule val compose = createComposeRule()

    private class Wire : SnapshotWiringFixture() {
        private val scheduledDeparture = java.time.Instant.now().plusSeconds(TimeUnit.DAYS.toSeconds(1)).toString()
        val appContext get() = context
        val records get() = requests
        val origin get() = server.url("/").toString().trimEnd('/')
        fun use(handler: (RecordedRequest) -> MockResponse) { this.handler = handler }
        fun currentDetails() = detailsJson(id = 11, status = "confirmed", marker = PRIVATE_POINT).apply {
            put("ride_id", 33)
            put("depart_at", scheduledDeparture)
            put("driver_name", SERVER_DRIVER)
            put("driver_phone", PRIVATE_PHONE)
            remove("onboard_at")
        }
        fun currentRole() = roleJson(status = "confirmed").apply { remove("onboard_at") }
        fun fallback(request: RecordedRequest) = defaultResponse(request)
        fun syntheticBearer() = "Bearer " + jwt(7)
    }

    private class Host(screen: Screen) : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
        val vm = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                YuldashViewModel(SavedStateHandle()) as T
        })[YuldashViewModel::class.java].apply {
            activeBookingId.value = 11
            selectedRide.value = bookingLinkStubRide(11).copy(
                id = "33", from = "Город А", to = "Город Б", driver = PUBLIC_DRIVER, car = "Lada Vesta")
            language.value = AppLanguage.Ru
            this.screen.value = screen
        }
    }

    private lateinit var wire: Wire
    private var fixtureReady = false
    private var host: Host? = null
    private val mounted = mutableStateOf(false)
    private val passPlain = MemoryDiskPreferences()
    private val passSecure = MemoryDiskPreferences()
    private val languageWrites = CopyOnWriteArrayList<String>()
    private val unexpectedWrites = AtomicInteger()
    private val badSyntheticAuthorization = AtomicInteger()
    private val recoveryDetailsEntered = CountDownLatch(1)
    private val releaseRecoveryDetails = CountDownLatch(1)
    @Volatile private var holdRecoveryDetails = false
    @Volatile private var codeReply = OLD_CODE
    private var oldDurableBytes: String? = null
    private var codeReadsAfterPreparation = 0
    @Volatile private var recoveryCodeDetails: BookingDetailsDto? = null
    @Volatile private var recoveryCodeOwner: SnapshotOwner? = null
    @Volatile private var recoveryCodeDetailsFresh = false
    @Volatile private var blankCodeDetails: BookingDetailsDto? = null
    @Volatile private var blankCodeDetailsFresh = false
    private data class RetryContext(
        val from: Int, val owner: SnapshotOwner?, val session: Long,
        val bookingId: Int?, val ride: Ride?, val revision: Long?,
    )

    companion object {
        private const val OLD_CODE = "4821"
        private const val CURRENT_CODE = "5932"
        private const val PRIVATE_PHONE = "+70000000111"
        private const val PRIVATE_POINT = "blank-code-private-meeting"
        private const val PUBLIC_DRIVER = "Публичный водитель"
        private const val SERVER_DRIVER = "Серверный водитель"
        private const val DETAILS = "/bookings/11/details"
        private const val ROLE = "/bookings/11/role"
        private const val CODE = "/bookings/11/boarding-code"
        private const val SAVE_WARNING = "Не удалось сохранить поездку без интернета"
        private const val SAVE_RETRY = "Сохранить ещё раз"
        private const val DATA_RETRY = "Повторить загрузку данных"
        @Volatile private var cleanupBlocked = false

        private fun backgroundRoot(): Job {
            val scope = ApiClient::class.java.getDeclaredField("bg").apply { isAccessible = true }
                .get(ApiClient) as CoroutineScope
            return requireNotNull(scope.coroutineContext[Job])
        }

        /** Observe and join actual Jobs, without cancel-only cleanup or clearing a live origin. */
        private fun awaitBackgroundCompletion() = runBlocking {
            withTimeout(12_000) {
                while (true) {
                    val children = backgroundRoot().children.toList()
                    if (children.isEmpty()) break
                    children.joinAll()
                    yield()
                }
            }
        }
    }

    @Before fun setupRecovery() {
        check(!cleanupBlocked) { "Previous cleanup retained its local origin; do not reset this test process" }
        try {
            awaitBackgroundCompletion()
        } catch (failure: Throwable) {
            cleanupBlocked = true
            throw AssertionError("Inherited API background Jobs did not finish; original origin retained", failure)
        }
        wire = Wire()
        wire.setupWiring()
        fixtureReady = true
        check(ApiClient.testBaseUrl == wire.origin)
        ApiClient.testTimeoutMs = 12_000
        TripPassStore.initStores(passPlain, passSecure)
        Outbox.initStores(MemoryDiskPreferences(), null)
        wire.appContext.getSharedPreferences("yuldash_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("onboarding_completed", true).commit()
        clearSignals()
        wire.use(::respond)
    }

    @After fun cleanupRecovery() {
        holdRecoveryDetails = false
        releaseRecoveryDetails.countDown()
        if (!fixtureReady) return
        // Any failure below deliberately retains the local API override and its owned server.
        try {
            host?.let { current ->
                compose.runOnIdle { mounted.value = false }
                compose.waitForIdle()
                current.viewModelStore.clear()
            }
            awaitBackgroundCompletion()
            check(backgroundRoot().children.none()) { "A background Job appeared after joining" }
        } catch (failure: Throwable) {
            cleanupBlocked = true
            throw AssertionError("Cleanup incomplete; local origin/server retained before any reset", failure)
        }
        clearSignals()
        Outbox.initStores(MemoryDiskPreferences(), null)
        wire.cleanupWiring()
        fixtureReady = false
    }

    private fun clearSignals() {
        DeepLink.pendingBookingChatId.value = null
        DeepLink.pendingCompletedBookingId.value = null
        DeepLink.pendingParcels.value = false
        DeepLink.pendingSupport.value = false
        DeepLink.pendingFairness.value = false
        DeepLink.pendingRequestResponsesId.value = null
        DeepLink.pendingRequestsFeed.value = false
        DeepLink.pendingApplicationScreen.value = null
        NavSignals.openInstantChat.value = 0
        NavSignals.openInstantOrder.value = false
        NavSignals.openDriverCabinet.value = false
        NavSignals.openAdsCabinet.value = false
        NavSignals.openPartnerCabinet.value = false
    }

    private fun json(body: JSONObject, status: Int = 200) = MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "application/json").setBody(body.toString())

    private fun path(request: RecordedRequest) = request.requestUrl!!.encodedPath
    private fun respond(request: RecordedRequest): MockResponse {
        val path = path(request)
        if (path.startsWith("/bookings/11/") || path == "/me/update") {
            if (request.getHeader("Authorization") != wire.syntheticBearer()) {
                badSyntheticAuthorization.incrementAndGet()
                return json(JSONObject(), 403)
            }
        }
        if (request.method != "GET") {
            // The real app always synchronizes language. Keep this separate from booking writes.
            if (request.method == "POST" && path == "/me/update") {
                val body = JSONObject(request.body.clone().readUtf8())
                check(body.keys().asSequence().toSet() == setOf("language"))
                val language = body.getString("language")
                check(language in setOf("ru", "ba"))
                languageWrites += language
                return json(JSONObject())
            }
            unexpectedWrites.incrementAndGet()
            return json(JSONObject(), 409)
        }
        return when (path) {
            ROLE -> json(wire.currentRole())
            DETAILS -> {
                if (holdRecoveryDetails) {
                    recoveryDetailsEntered.countDown()
                    check(releaseRecoveryDetails.await(15, TimeUnit.SECONDS))
                }
                json(wire.currentDetails())
            }
            CODE -> {
                if (codeReply.isBlank()) {
                    val actual = OfflineTripSnapshots.projection(11)?.details
                    blankCodeDetails = actual
                    blankCodeDetailsFresh = actual?.let(OfflineTripSnapshots::freshDetails) == true
                }
                if (holdRecoveryDetails && codeReply == CURRENT_CODE) {
                    val actual = OfflineTripSnapshots.projection(11)?.details
                    recoveryCodeDetails = actual
                    recoveryCodeOwner = OfflineTripSnapshots.currentOwner()
                    recoveryCodeDetailsFresh = actual?.let(OfflineTripSnapshots::freshDetails) == true
                }
                json(JSONObject().put("code", codeReply))
            }
            "/me" -> json(JSONObject().put("id", 7).put("role", "user"))
            "/bookings/11/messages", "/pickup-points", "/ads" ->
                json(JSONObject().put("items", JSONArray()))
            else -> wire.fallback(request)
        }
    }

    private fun settle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(50)
    }
    private fun exists(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    private fun awaitUi(reason: String, check: () -> Boolean) {
        try {
            compose.waitUntil(15_000) { settle(); check() }
        } catch (failure: ComposeTimeoutException) {
            throw AssertionError(reason + "; codeReads=" + codeReads() +
                ", live=" + OfflineTripSnapshots.allowsLive(11) +
                ", detailsPresent=" + (OfflineTripSnapshots.projection(11) != null), failure)
        }
    }
    private fun scrollTo(text: String) =
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))
    private fun codeReads() = wire.records.count { it.method == "GET" && path(it) == CODE }
    private fun rawDurable() = passSecure.restarted().getString("pass_11", null)
    private fun assertOldDurableUnchanged() {
        assertTrue("Original durable bytes must survive a refused code read",
            oldDurableBytes != null && oldDurableBytes == rawDurable())
        assertTrue("Independent restarted reader must retain the original code",
            TripPassAuthorityFixture.durable(passSecure, 11)?.boardingCode == OLD_CODE)
    }
    private fun assertNoPrivate() {
        assertTrue("A private pass remained authorized", OfflineTripSnapshots.privatePass(11) == null)
        assertTrue("Private participant details remained authorized", currentTripPrivateDetails(11) == null)
        listOf(PRIVATE_PHONE, PRIVATE_POINT, OLD_CODE, CURRENT_CODE).forEach { value ->
            assertTrue("A private fixture marker remained in semantics",
                compose.onAllNodesWithText(value, substring = true).fetchSemanticsNodes().isEmpty())
        }
    }
    private fun assertAllowedWrites(allowLanguage: Boolean) {
        assertEquals("Synthetic authorization failed", 0, badSyntheticAuthorization.get())
        val unexpectedPaths = wire.records.filter { it.method != "GET" && path(it) != "/me/update" }
            .map { it.method + " " + path(it) }
        assertEquals("A non-language write escaped the readonly journey: " + unexpectedPaths, 0, unexpectedWrites.get())
        val writes = wire.records.filter { it.method != "GET" }
        assertTrue("Only the real root's validated language sync may write",
            writes.all { allowLanguage && it.method == "POST" && path(it) == "/me/update" })
        assertEquals("Every allowed write must have its language-only ledger entry",
            writes.size, languageWrites.size)
    }

    private suspend fun prepareDurablePass() {
        assertEquals("passenger", ApiClient.getTripState(11).getOrThrow().role)
        val details = ApiClient.getBookingDetails(11).getOrThrow()
        assertTrue("Preparation needs actual fresh participant DETAILS", OfflineTripSnapshots.privateDetails(details))
        assertTrue("Preparation needs an actual code response", ApiClient.getBoardingCode(11).getOrThrow() == OLD_CODE)
        assertTrue(TripPassStore.save(wire.appContext, requireNotNull(OfflineTripSnapshots.privatePass(11))))
        oldDurableBytes = rawDurable()
        assertOldDurableUnchanged()
        assertAllowedWrites(false)
        codeReadsAfterPreparation = codeReads()
        // From here onward no install/reset/revoke/coldAuthority helper may alter access.
    }

    private fun mountBooking() {
        val current = Host(Screen.Booking).also { host = it }
        compose.mainClock.autoAdvance = true
        mounted.value = true
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(
                LocalViewModelStoreOwner provides current, LocalPoolingNativeMapEnabled provides false
            ) { YuldashTheme { YuldashApp() } }
        }
    }
    private fun mountActiveParent() {
        val current = Host(Screen.ActiveTrip).also { host = it }
        compose.mainClock.autoAdvance = true
        mounted.value = true
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(
                LocalAppLanguage provides AppLanguage.Ru, LocalPoolingNativeMapEnabled provides false
            ) {
                val session = ApiClient.sessionChanges.collectAsState(
                    initial = ApiClient.queueSessionGeneration()).value
                YuldashTheme {
                    GuardedActiveTripDestination(current.vm, session, emptyList(), null, false,
                        onBack = {}, onTripEnd = {}, onSos = {}, onSupport = {},
                        onTrustedContacts = {}, onTripStopped = {}, onOpenReceipt = {},
                        currentReceiptBookingId = { 0 })
                }
            }
        }
    }
    private fun awaitBlankWithdrawal() {
        awaitUi("Actual blank CODE200 must withdraw private authority") {
            codeReads() > codeReadsAfterPreparation && !OfflineTripSnapshots.allowsLive(11)
        }
        settle()
        assertNoPrivate()
        assertOldDurableUnchanged()
    }

    /** Read-only evidence. Never format the retained DTO, owner, phone, code or request headers. */
    private fun diagnoseBookingPublication(phase: String, expected: RetryContext) {
        runCatching {
            val captured = recoveryCodeDetails
            val actual = OfflineTripSnapshots.projection(11)?.details
            val beforeBlank = blankCodeDetails
            // These unobserved local probes never assign to application or authority state.
            val probes = if (beforeBlank != null && actual != null) {
                val structural = mutableStateOf(beforeBlank)
                structural.value = actual
                val referential = mutableStateOf(beforeBlank, referentialEqualityPolicy())
                referential.value = actual
                (structural.value === beforeBlank) to (referential.value === actual)
            } else false to false
            val currentOwner = OfflineTripSnapshots.currentOwner()
            val vm = host?.vm
            val flags = linkedMapOf(
                "blankDetailsAtCode" to (beforeBlank != null),
                "blankDetailsFreshAtCode" to blankCodeDetailsFresh,
                "blankEqualsActual" to (beforeBlank != null && actual != null && beforeBlank == actual),
                "blankSameRefAsActual" to (beforeBlank != null && actual === beforeBlank),
                "structuralProbeRetainsBlankRef" to probes.first,
                "referentialProbeKeepsActualRef" to probes.second,
                "detailsAtCode" to (captured != null),
                "detailsFreshAtCode" to recoveryCodeDetailsFresh,
                "capturedIsCurrentRef" to (captured != null && actual === captured),
                "capturedFreshNow" to (captured?.let(OfflineTripSnapshots::freshDetails) == true),
                "actualFreshNow" to (actual?.let(OfflineTripSnapshots::freshDetails) == true),
                "actualPrivateNow" to OfflineTripSnapshots.privateDetails(actual),
                "capturedServerName" to (captured?.driverName == SERVER_DRIVER),
                "actualServerName" to (actual?.driverName == SERVER_DRIVER),
                "capturedMatchesBookingParent" to
                    (currentBookingParentDetails(captured, vm?.activeBookingId?.value, vm?.selectedRide?.value) != null),
                "actualMatchesBookingParent" to
                    (currentBookingParentDetails(actual, vm?.activeBookingId?.value, vm?.selectedRide?.value) != null),
                "ownerUnchanged" to (currentOwner != null && currentOwner == expected.owner),
                "ownerSameAsAtCode" to (currentOwner != null && currentOwner == recoveryCodeOwner),
                "sessionCurrent" to ApiClient.isCurrentSession(expected.session),
                "vmContextUnchanged" to (vm != null && vm.screen.value == Screen.Booking &&
                    vm.activeBookingId.value == expected.bookingId && vm.selectedRide.value == expected.ride &&
                    vm.privateNavigationRevision == expected.revision),
                "passCurrent" to (OfflineTripSnapshots.privatePass(11)?.boardingCode == CURRENT_CODE),
                "durableCurrent" to (TripPassAuthorityFixture.durable(passSecure, 11)?.boardingCode == CURRENT_CODE),
                "publicLabelPresent" to exists(PUBLIC_DRIVER),
                "serverLabelPresent" to exists(SERVER_DRIVER),
                "warningPresent" to exists(SAVE_WARNING),
            )
            val reads = wire.records.drop(expected.from).filter { it.method == "GET" }.map(::path)
            val counts = linkedMapOf("role" to reads.count { it == ROLE },
                "details" to reads.count { it == DETAILS }, "code" to reads.count { it == CODE })
            println("BLANK_CODE_PUBLICATION phase=" + phase + " reads=" + counts + " flags=" + flags)
        }.onFailure { failure ->
            println("BLANK_CODE_PUBLICATION phase=" + phase + " diagnosticUnavailable=" + failure.javaClass.simpleName)
        }
    }

    /** The held DETAILS proves that a button cannot reuse the previous private projection. */
    private fun retryThroughFreshDetails(button: String, allowLanguage: Boolean, booking: Boolean) {
        val from = wire.records.size
        val beforeCode = codeReads()
        val expected = RetryContext(from, OfflineTripSnapshots.currentOwner(), ApiClient.queueSessionGeneration(),
            host?.vm?.activeBookingId?.value, host?.vm?.selectedRide?.value, host?.vm?.privateNavigationRevision)
        holdRecoveryDetails = true
        codeReply = CURRENT_CODE
        try {
            scrollTo(button)
            compose.onNodeWithText(button).assertIsEnabled().performClick()
            awaitUi("Explicit retry must request fresh DETAILS") { recoveryDetailsEntered.count == 0L }
            assertEquals("No code GET before the held fresh DETAILS response", beforeCode, codeReads())
            assertNoPrivate()
            assertOldDurableUnchanged()
            assertAllowedWrites(allowLanguage)
            releaseRecoveryDetails.countDown()
            awaitUi("Fresh details and code must recover the current private projection") {
                codeReads() > beforeCode &&
                    OfflineTripSnapshots.privatePass(11)?.boardingCode == CURRENT_CODE &&
                    TripPassAuthorityFixture.durable(passSecure, 11)?.boardingCode == CURRENT_CODE
            }
            val reads = wire.records.drop(from).filter { it.method == "GET" }.map(::path)
            assertTrue("Recovery needs a new actual DETAILS before CODE",
                reads.indexOf(DETAILS) >= 0 && reads.indexOf(CODE) > reads.indexOf(DETAILS))
            if (booking) {
                awaitUi("Successful save must remove the warning") { !exists(SAVE_WARNING) }
                diagnoseBookingPublication("before-original-server-scroll", expected)
                try {
                    scrollTo(SERVER_DRIVER)
                    compose.onNodeWithText(SERVER_DRIVER).assertIsDisplayed()
                } finally {
                    diagnoseBookingPublication("after-original-server-scroll", expected)
                }
            } else {
                scrollTo("Код посадки")
                assertTrue("The current HTTP code must be visible", exists(CURRENT_CODE))
            }
            assertAllowedWrites(allowLanguage)
        } finally {
            holdRecoveryDetails = false
            releaseRecoveryDetails.countDown()
        }
    }

    @Test fun blankBookingCodeKeepsDurableBytesAndRetriesThroughFreshDetails(): Unit = runBlocking {
        prepareDurablePass()
        codeReply = ""
        mountBooking()
        awaitBlankWithdrawal()
        awaitUi("Booking must leave its confirmation loader") { !exists("Обновляем подтверждение поездки") }
        scrollTo(PUBLIC_DRIVER)
        compose.onNodeWithText(PUBLIC_DRIVER).assertIsDisplayed()
        scrollTo(SAVE_WARNING)
        compose.onNodeWithText(SAVE_WARNING).assertIsDisplayed()
        assertAllowedWrites(true)
        retryThroughFreshDetails(SAVE_RETRY, allowLanguage = true, booking = true)
    }

    @Test fun blankActiveCodeLeavesLoadingAndOffersAWorkingFreshDetailsRetry(): Unit = runBlocking {
        prepareDurablePass()
        codeReply = ""
        mountActiveParent()
        awaitBlankWithdrawal()
        assertAllowedWrites(false)
        awaitUi("Unverified parent must end loading and offer an error with retry") {
            !exists("Обновляем данные поездки") && !exists("Код загружается") &&
                exists("Не удалось загрузить данные поездки") && exists(DATA_RETRY)
        }
        retryThroughFreshDetails(DATA_RETRY, allowLanguage = false, booking = false)
    }

    @Test fun nonemptyHttpControlWorksThroughRealBookingAndActiveParents(): Unit = runBlocking {
        prepareDurablePass()
        codeReply = CURRENT_CODE
        mountBooking()
        awaitUi("Normal Booking HTTP must replace the old durable code") {
            !exists("Обновляем подтверждение поездки") &&
                TripPassAuthorityFixture.durable(passSecure, 11)?.boardingCode == CURRENT_CODE
        }
        scrollTo(SERVER_DRIVER)
        compose.onNodeWithText(SERVER_DRIVER).assertIsDisplayed()
        scrollTo("Телефон водителя открыт")
        assertTrue("Fresh participant phone must be visible", exists(PRIVATE_PHONE))
        assertFalse(exists(SAVE_WARNING))
        val beforeCode = codeReads()
        val beforeRole = wire.records.count { it.method == "GET" && path(it) == ROLE }
        compose.onNodeWithText("Открыть поездку").assertIsEnabled().performClick()
        awaitUi("Actual app navigation must obtain current active-parent HTTP authority") {
            host?.vm?.screen?.value == Screen.ActiveTrip &&
                wire.records.count { it.method == "GET" && path(it) == ROLE } > beforeRole &&
                codeReads() > beforeCode &&
                OfflineTripSnapshots.privatePass(11)?.boardingCode == CURRENT_CODE
        }
        scrollTo("Код посадки")
        assertTrue("Current HTTP code must appear in the real active card", exists(CURRENT_CODE))
        assertFalse(exists("Не удалось загрузить код"))
        assertAllowedWrites(true)
    }
}
