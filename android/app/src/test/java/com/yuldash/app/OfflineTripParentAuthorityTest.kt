package com.yuldash.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
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
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Production parents + synthetic HTTP only. These tests must be run before any product fix.
 * A is booking 11/ride 33; already-mounted B is booking 22/ride 44, with the same owner.
 * There is no new DETAILS/HISTORY read to repair B after A loses its route.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w1000dp-h1800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class OfflineTripParentAuthorityTest : SnapshotWiringFixture() {
    @get:Rule val compose = createComposeRule()
    private enum class Chain { BOOKING, COMPLETED }
    private enum class Loss { SCREEN, BOOKING, RIDE, REVISION, PENDING_BOOKING, PENDING_SCREEN, PENDING_RIDE }
    private class Window(val id: Int, screen: Screen) : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
        val mounted = mutableStateOf(true)
        val disposed = AtomicInteger()
        val receiptBookingId = mutableStateOf(0)
        val receiptOpens = AtomicInteger()
        val saved = SavedStateHandle()
        val vm = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = YuldashViewModel(saved) as T
        })[YuldashViewModel::class.java].apply {
            activeBookingId.value = id
            selectedRide.value = bookingLinkStubRide(id).copy(id = if (id == 11) "33" else "44")
            this.screen.value = screen
        }
    }
    private lateinit var chain: Chain
    private lateinit var a: Window
    private lateinit var b: Window
    private val releaseRoles = CountDownLatch(1)
    private val actionHeld = CountDownLatch(1)
    private val releaseAction = CountDownLatch(1)
    private val actionReturned = CountDownLatch(1)
    private val releaseAReads = CountDownLatch(1)
    private val heldAReads = AtomicInteger()
    @Volatile private var holdAReads = false
    private val releasePublicRide = CountDownLatch(1)
    private val heldPublicRide = AtomicInteger()
    @Volatile private var holdPublicRide = false
    @Volatile private var heldPath: String? = null
    @Volatile private var successFor: Int? = null
    @Volatile private var savedMeetingId: Int? = null
    private var otherDetails: BookingDetailsDto? = null
    private var otherHistory: TripReceiptDto? = null
    private var otherReads = emptyList<String>()
    private val warmMounted = mutableStateOf(false)

    @Before fun setupParentUi() {
        ApiClient.testTimeoutMs = 45000
        context.getSharedPreferences("yuldash_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("onboarding_completed", true).commit()
        DeepLink.pendingCompletedBookingId.value = null
        DeepLink.pendingBookingChatId.value = null
        NavSignals.openDriverCabinet.value = false
        NavSignals.openInstantOrder.value = false
        NavSignals.openInstantChat.value = 0
    }
    @After fun unmountParentUi() {
        holdPublicRide = false
        releasePublicRide.countDown()
        holdAReads = false
        releaseAReads.countDown()
        releaseAction.countDown()
        releaseRoles.countDown()
        if (warmMounted.value) {
            compose.runOnIdle { warmMounted.value = false }
            compose.waitForIdle()
        }
        if (::a.isInitialized) {
            compose.runOnIdle { a.mounted.value = false; b.mounted.value = false }
            compose.waitForIdle()
            a.viewModelStore.clear()
            b.viewModelStore.clear()
        }
        DeepLink.pendingCompletedBookingId.value = null
        DeepLink.pendingBookingChatId.value = null
    }
    private fun failure(id: Int, late: Boolean = false): MockResponse = MockResponse().setResponseCode(409)
        .setBody(JSONObject().put("detail", JSONObject().put("code", "qa_parent_retry")
            .put("ru", if (late) "qa-parent-late-$id" else "qa-parent-retry-$id")
            .put("ba", if (late) "qa-parent-late-$id" else "qa-parent-retry-$id")).toString())
    private fun response(request: RecordedRequest): MockResponse {
        val path = request.requestUrl!!.encodedPath
        val id = Regex("/(?:bookings|trips)/([0-9]+)/").find(path)?.groupValues?.get(1)?.toInt()
        if (holdPublicRide && request.method == "GET" && path == "/rides/34") {
            heldPublicRide.incrementAndGet()
            check(releasePublicRide.await(40, TimeUnit.SECONDS))
        }
        // Only reads dispatched after positive preparation are delayed. The already-started
        // late POST and every request from the independent B window keep their own controls.
        if (holdAReads && request.method == "GET" && id == 11 &&
            path.substringAfterLast('/') in setOf("details", "role", "receipt")) {
            heldAReads.incrementAndGet()
            check(releaseAReads.await(40, TimeUnit.SECONDS))
        }
        if (request.method == "POST" && id != null &&
            path.substringAfterLast('/') in setOf("meeting-point", "rate", "thanks", "lost-item")) {
            val late = path == heldPath
            if (late) {
                actionHeld.countDown()
                check(releaseAction.await(40, TimeUnit.SECONDS))
                actionReturned.countDown()
            }
            if (!late && id == successFor) {
                if (path.endsWith("/meeting-point")) savedMeetingId = id
                return MockResponse().setBody("{}")
            }
            return failure(id, late)
        }
        if (id != null && path.endsWith("/details")) {
            val status = if (chain == Chain.BOOKING) "confirmed" else "done"
            return MockResponse().setBody(detailsJson(id, status = status, marker = if (savedMeetingId == id) "parent-saved-$id" else "parent-point-$id").apply {
                put("ride_id", if (id == 22) 44 else 33)
                if (savedMeetingId == id) put("meeting_version", 3)
                put("driver_phone", "+700000000$id")
                remove("onboard_at")
            }.toString())
        }
        if (id != null && path.endsWith("/role")) {
            // The terminal seed came from actual DETAILS before mount. Hold competing terminal
            // ROLE reads, as ActiveTripTerminalAuthorityTest does, so HISTORY remains identical.
            if (chain == Chain.COMPLETED) check(releaseRoles.await(40, TimeUnit.SECONDS))
            return MockResponse().setBody(roleJson(status = if (chain == Chain.BOOKING) "confirmed" else "done")
                .apply { remove("onboard_at") }.toString())
        }
        if (id != null && path.endsWith("/receipt")) return MockResponse().setBody(receiptJson(id).apply {
            put("ride_id", if (id == 22) 44 else 33)
            put("counterparty_name", "parent-history-$id")
        }.toString())
        if (path.endsWith("/tip")) return MockResponse().setBody("{\"already_thanked\":false}")
        if (path.endsWith("/messages") || path == "/pickup-points" || path == "/ads")
            return MockResponse().setBody("{\"items\":[]}")
        return defaultResponse(request)
    }
    private fun settle() {
        compose.mainClock.advanceTimeBy(50)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        Snapshot.sendApplyNotifications()
    }
    private fun awaitUi(check: () -> Boolean) = compose.waitUntil(15000) { settle(); check() }
    private fun drainActions() {
        repeat(50) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeBy(50)
            Thread.sleep(10)
        }
        compose.waitForIdle()
    }
    private fun inSameFrameGap(action: () -> Unit) {
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try { compose.runOnUiThread(action) }
        finally { compose.mainClock.autoAdvance = true }
    }
    private fun inside(w: Window) = hasAnyAncestor(hasTestTag("parent-window-${w.id}"))
    private fun text(w: Window, value: String) = hasText(value) and inside(w)
    private fun tag(w: Window, value: String) = hasTestTag(value) and inside(w)
    private fun node(w: Window, value: String) = compose.onNode(text(w, value))
    private fun scroll(w: Window, target: SemanticsMatcher) {
        compose.onNode(hasScrollAction() and inside(w)).performScrollToNode(target)
    }
    private fun retainedSemanticsClick(matcher: SemanticsMatcher): () -> Boolean {
        awaitUi { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(matcher).assertIsEnabled()
        return requireNotNull(compose.onNode(matcher).fetchSemanticsNode().config[SemanticsActions.OnClick].action)
    }
    private fun posts(w: Window) = requests.filter { it.method == "POST" &&
        it.requestUrl!!.encodedPath.startsWith("/bookings/${w.id}/") &&
        it.requestUrl!!.encodedPath.substringAfterLast('/') in setOf("meeting-point", "rate", "thanks", "lost-item") }
    private fun reads(w: Window) = requests.filter { it.method == "GET" &&
        it.requestUrl!!.encodedPath in setOf("/bookings/${w.id}/details", "/bookings/${w.id}/role", "/trips/${w.id}/receipt") }
        .map { it.requestUrl!!.encodedPath }
    @Composable private fun WindowContent(w: Window) {
        if (w.mounted.value) {
            DisposableEffect(w) { onDispose { w.disposed.incrementAndGet() } }
            CompositionLocalProvider(LocalViewModelStoreOwner provides w, LocalAppLanguage provides AppLanguage.Ru,
                LocalPoolingNativeMapEnabled provides false) {
                YuldashTheme {
                    if (chain == Chain.BOOKING) YuldashApp()
                    else {
                        val session = ApiClient.sessionChanges.collectAsState(initial = ApiClient.queueSessionGeneration()).value
                        GuardedActiveTripDestination(w.vm, session, emptyList(), null, false,
                            onBack = {}, onTripEnd = {}, onSos = {}, onSupport = {}, onTrustedContacts = {},
                            onTripStopped = {}, onOpenReceipt = { id ->
                                w.receiptBookingId.value = id
                                w.receiptOpens.incrementAndGet()
                                w.vm.screen.value = Screen.TripReceipt
                            }, currentReceiptBookingId = { w.receiptBookingId.value })
                    }
                }
            }
        }
    }
    private fun open(
        which: Chain, initialRideA: Int? = null, initialStubA: Boolean = false,
    ) = runBlocking {
        chain = which
        handler = ::response
        val screen = if (which == Chain.BOOKING) Screen.Booking else Screen.ActiveTrip
        a = Window(11, screen)
        if (initialRideA != null) a.vm.selectedRide.value = requireNotNull(a.vm.selectedRide.value).copy(id = initialRideA.toString())
        if (initialStubA) a.vm.selectedRide.value = bookingLinkStubRide(11)
        b = Window(22, screen)
        if (which == Chain.COMPLETED) {
            assertEquals("done", ApiClient.getBookingDetails(11).getOrThrow().status)
            assertEquals("done", ApiClient.getBookingDetails(22).getOrThrow().status)
        }
        compose.mainClock.autoAdvance = true
        compose.setContent {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).testTag("parent-window-11")) { WindowContent(a) }
                Box(Modifier.weight(1f).testTag("parent-window-22")) { WindowContent(b) }
            }
        }
        awaitUi {
            if (which == Chain.BOOKING) listOf(11, 22).all { OfflineTripSnapshots.projection(it)?.details?.meetingVersion == 2 }
            else actualHistory(11)?.let(OfflineTripSnapshots::allowsHistory) == true &&
                actualHistory(22)?.let(OfflineTripSnapshots::allowsHistory) == true &&
                (initialRideA != null ||
                    compose.onAllNodes(tag(a, "rideshareCompletedHero")).fetchSemanticsNodes().isNotEmpty()) &&
                compose.onAllNodes(tag(b, "rideshareCompletedHero")).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(7, owner().userId)
    }
    /** Observation of the exact object delivered by HTTP, copied from the existing terminal
     * wiring test. No replacement DTO, proof, permit or authority-map mutation is performed. */
    @Suppress("UNCHECKED_CAST")
    private fun actualHistory(id: Int): TripReceiptDto? = synchronized(OfflineTripSnapshots) {
        val field = OfflineTripSnapshots::class.java.getDeclaredField("histories").apply { isAccessible = true }
        (field.get(OfflineTripSnapshots) as Map<Int, Pair<TripReceiptDto, SnapshotOwner>>)[id]?.first
    }
    private fun prepareBooking(w: Window): List<() -> Boolean> {
        scroll(w, hasText("+700000000${w.id}"))
        node(w, "+700000000${w.id}").assertExists()
        scroll(w, hasText("Маршрут к месту посадки"))
        val map = retainedSemanticsClick(text(w, "Маршрут к месту посадки"))
        val application = Shadows.shadowOf(context as Application)
        compose.runOnIdle { map() }
        assertEquals(Intent.ACTION_CHOOSER, requireNotNull(application.nextStartedActivity).action)
        scroll(w, hasText("Изменить место посадки"))
        node(w, "Изменить место посадки").performClick()
        awaitUi { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction()).performTextReplacement("parent-draft-${w.id}")
        compose.onNodeWithText("Готово").performClick()
        awaitUi { posts(w).size == 1 && compose.onAllNodesWithText("qa-parent-retry-${w.id}").fetchSemanticsNodes().isNotEmpty() }
        val request = posts(w).single()
        assertEquals("Bearer ${jwt(7)}", request.getHeader("Authorization"))
        val body = JSONObject(request.body.clone().readUtf8())
        assertEquals(2, body.getInt("base_version"))
        assertEquals("pickup", body.getString("kind"))
        assertEquals("parent-draft-${w.id}", body.getJSONObject("point").getString("note"))
        return listOf(map, retainedSemanticsClick(hasText("Готово")))
    }
    private fun prepareCompleted(w: Window): List<() -> Boolean> {
        scroll(w, hasTestTag("rideshareStar4"))
        compose.onNode(tag(w, "rideshareStar4")).performClick()
        compose.onNode(tag(w, "rideshareRatingSubmit")).performClick()
        awaitUi { posts(w).count { it.path == "/bookings/${w.id}/rate" } == 1 &&
            compose.onAllNodes(text(w, "qa-parent-retry-${w.id}")).fetchSemanticsNodes().isNotEmpty() }
        scroll(w, hasText("Сказать «Рәхмәт»"))
        node(w, "Сказать «Рәхмәт»").performClick()
        awaitUi { posts(w).count { it.path == "/bookings/${w.id}/thanks" } == 1 &&
            compose.onAllNodes(tag(w, "rideshareThanksRetry")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(tag(w, "rideshareThanksRetry")).performClick()
        awaitUi { compose.onAllNodes(tag(w, "rideshareThanksError")).fetchSemanticsNodes().isEmpty() &&
            compose.onAllNodes(tag(w, "rideshareThanksLoading")).fetchSemanticsNodes().isEmpty() }
        scroll(w, hasTestTag("rideshareLostItem"))
        compose.onNode(tag(w, "rideshareLostItem")).performClick()
        awaitUi { posts(w).count { it.path == "/bookings/${w.id}/lost-item" } == 1 &&
            compose.onAllNodes(text(w, "qa-parent-retry-${w.id}")).fetchSemanticsNodes().isNotEmpty() }
        val lost = retainedSemanticsClick(tag(w, "rideshareLostItem"))
        scroll(w, hasText("Сказать «Рәхмәт»"))
        val thanks = retainedSemanticsClick(text(w, "Сказать «Рәхмәт»"))
        val rate = retainedSemanticsClick(tag(w, "rideshareRatingSubmit"))
        assertEquals(3, posts(w).size)
        posts(w).forEach { assertEquals("Bearer ${jwt(7)}", it.getHeader("Authorization")) }
        assertEquals(4, JSONObject(posts(w).single { it.path!!.endsWith("/rate") }.body.clone().readUtf8()).getInt("stars"))
        assertTrue(OfflineTripSnapshots.allowsHistory(requireNotNull(actualHistory(w.id))))
        return listOf(rate, thanks, lost)
    }
    private fun prepareTarget() = if (chain == Chain.BOOKING) prepareBooking(a) else prepareCompleted(a)
    private fun rememberOtherWindow() {
        otherDetails = OfflineTripSnapshots.projection(22)?.details
        otherHistory = actualHistory(22)
        otherReads = reads(b)
        assertOtherWindow()
    }
    private fun assertOtherWindow() {
        assertEquals(0, b.disposed.get())
        assertEquals(22, b.vm.activeBookingId.value)
        assertEquals("44", b.vm.selectedRide.value?.id)
        assertNull(b.vm.pendingBookingNavigation.value)
        assertNull(b.vm.pendingScreenNavigation.value)
        assertNull(b.vm.pendingPublicRideNavigation.value)
        assertEquals(otherReads, reads(b))
        if (chain == Chain.BOOKING) {
            assertEquals(Screen.Booking, b.vm.screen.value)
            assertSame(otherDetails, OfflineTripSnapshots.projection(22)?.details)
            assertTrue(OfflineTripSnapshots.freshDetails(requireNotNull(otherDetails)))
            assertTrue(OfflineTripSnapshots.allowsLive(22))
        } else {
            assertEquals(Screen.ActiveTrip, b.vm.screen.value)
            assertSame(otherHistory, actualHistory(22))
            assertTrue(OfflineTripSnapshots.allowsHistory(requireNotNull(otherHistory)))
        }
    }
    private fun positiveOtherWindow() {
        assertOtherWindow()
        if (chain == Chain.BOOKING) prepareBooking(b) else {
            scroll(b, hasTestTag("rideshareStar4"))
            compose.onNode(tag(b, "rideshareStar4")).performClick()
            compose.onNode(tag(b, "rideshareRatingSubmit")).performClick()
            awaitUi { posts(b).size == 1 && compose.onAllNodes(text(b, "qa-parent-retry-22")).fetchSemanticsNodes().isNotEmpty() }
            assertEquals("/bookings/22/rate", posts(b).single().path)
            assertEquals("Bearer ${jwt(7)}", posts(b).single().getHeader("Authorization"))
        }
        assertOtherWindow()
    }
    private data class ParentReadProbe(
        val counts: List<Int>, val history: TripReceiptDto?, val details: BookingDetailsDto?,
        val owner: SnapshotOwner?, val session: Long, val ride: Ride?, val booking: Int?,
        val historyCurrent: Boolean, val detailsFresh: Boolean,
        val pendingPublic: Boolean, val publicRideGets: Int,
    ) {
        fun historyRideMatchesParent() = history?.let { ride?.id == it.rideId.toString() } == true
        fun detailsRideMatchesParent() = details?.let { ride?.id == it.rideId.toString() } == true
        fun isExactBookingStub() = booking?.let { ride == bookingLinkStubRide(it) } == true
    }
    private fun parentReadProbe(): ParentReadProbe {
        val history = actualHistory(11)
        val details = OfflineTripSnapshots.projection(11)?.details
        val paths = reads(a)
        return ParentReadProbe(listOf(paths.count { it.endsWith("/role") }, paths.count { it.endsWith("/details") },
            paths.count { it.endsWith("/receipt") }), history, details, OfflineTripSnapshots.currentOwner(),
            ApiClient.queueSessionGeneration(), a.vm.selectedRide.value, a.vm.activeBookingId.value,
            history?.let(OfflineTripSnapshots::allowsHistory) == true,
            details?.let(OfflineTripSnapshots::freshDetails) == true,
            a.vm.pendingPublicRideNavigation.value != null,
            requests.count { it.method == "GET" && it.requestUrl!!.encodedPath == "/rides/34" })
    }
    private fun printReadBinding(stage: String, before: ParentReadProbe) {
        val after = parentReadProbe()
        // Counts and booleans only: never stringify a DTO, owner, route, bearer or private field.
        println("PARENT_READ_BINDING stage=$stage chain=$chain readOrder=ROLE,DETAILS,HISTORY " +
            "aReadsBefore=${before.counts} aReadsAfter=${after.counts} " +
            "aReadHoldArmed=$holdAReads aReadResponsesHeld=${heldAReads.get()} " +
            "historyBeforePresent=${before.history != null} historyAfterPresent=${after.history != null} " +
            "historySameRef=${before.history != null && before.history === after.history} " +
            "historyCurrentBefore=${before.historyCurrent} historyCurrentAfter=${after.historyCurrent} " +
            "detailsApplicable=${chain == Chain.BOOKING} detailsBeforePresent=${before.details != null} " +
            "detailsAfterPresent=${after.details != null} " +
            "detailsSameRef=${before.details != null && before.details === after.details} " +
            "detailsFreshBefore=${before.detailsFresh} detailsFreshAfter=${after.detailsFresh} " +
            "ownerSame=${before.owner == after.owner} sessionSame=${before.session == after.session} " +
            "ownerUserSame=${before.owner?.userId == after.owner?.userId} " +
            "pendingPublicBefore=${before.pendingPublic} pendingPublicAfter=${after.pendingPublic} " +
            "publicRideGetsBefore=${before.publicRideGets} publicRideGetsAfter=${after.publicRideGets} " +
            "publicRideHoldArmed=$holdPublicRide publicRideResponsesHeld=${heldPublicRide.get()} " +
            "historyRideMatchBefore=${before.historyRideMatchesParent()} historyRideMatchAfter=${after.historyRideMatchesParent()} " +
            "detailsRideMatchBefore=${before.detailsRideMatchesParent()} detailsRideMatchAfter=${after.detailsRideMatchesParent()} " +
            "exactBookingStubBefore=${before.isExactBookingStub()} exactBookingStubAfter=${after.isExactBookingStub()}")
    }
    private fun parentState() = a.vm.let { listOf(it.screen.value, it.activeBookingId.value,
        it.selectedRide.value, it.privateNavigationRevision, it.pendingBookingNavigation.value,
        it.pendingScreenNavigation.value, it.pendingPublicRideNavigation.value) }
    private fun loseOnly(loss: Loss) {
        val before = parentState()
        when (loss) {
            Loss.SCREEN -> a.vm.screen.value = Screen.Settings
            Loss.BOOKING -> a.vm.activeBookingId.value = 12
            Loss.RIDE -> a.vm.selectedRide.value = requireNotNull(a.vm.selectedRide.value).copy(id = "34")
            Loss.REVISION -> a.vm.navigateLocally { }
            Loss.PENDING_BOOKING -> a.vm.pendingBookingNavigation.value =
                YuldashViewModel.PendingBookingNavigation(12, 7, a.vm.privateNavigationRevision, false)
            Loss.PENDING_SCREEN -> a.vm.pendingScreenNavigation.value =
                YuldashViewModel.PendingScreenNavigation(Screen.SupportTickets, 0, 7, a.vm.privateNavigationRevision)
            Loss.PENDING_RIDE -> a.vm.pendingPublicRideNavigation.value =
                YuldashViewModel.PendingPublicRideNavigation(34, a.vm.privateNavigationRevision)
        }
        val after = parentState()
        assertEquals("The negative case must change exactly one parent input", listOf(loss.ordinal),
            before.indices.filter { before[it] != after[it] })
    }
    @Suppress("DEPRECATION")
    private fun noOldPrivate() {
        // tripAuthorityDraw suppresses pixels and sets invisibleToUser rather than changing
        // layout bounds. Check the actual accessibility tree, including any retained subtree.
        val markers = if (chain == Chain.BOOKING) listOf("parent-point-11", "+70000000011") else listOf("parent-history-11")
        markers.forEach { marker ->
            compose.onAllNodes(hasText(marker, substring = true) and inside(a), useUnmergedTree = true)
                .fetchSemanticsNodes().forEach { node ->
                    assertTrue("Outgoing private field is still accessible: $marker",
                        generateSequence(node) { it.parent }.any { it.config.contains(SemanticsProperties.InvisibleToUser) })
                }
        }
    }
    private fun matrix(which: Chain, loss: Loss) {
        open(which)
        val actions = prepareTarget()
        rememberOtherWindow()
        val readProbe = parentReadProbe()
        val initialPosts = posts(a).size
        val originalOwner = owner()
        val originalSession = ApiClient.queueSessionGeneration()
        val started = mutableListOf<String?>()
        val app = Shadows.shadowOf(context as Application)
        holdPublicRide = loss == Loss.PENDING_RIDE
        holdAReads = true
        try {
            inSameFrameGap {
                loseOnly(loss)
                if (loss == Loss.PENDING_RIDE) println("PARENT_PUBLIC_PENDING phase=after_change chain=$chain " +
                    "pendingPublic=${a.vm.pendingPublicRideNavigation.value != null}")
                assertEquals(0, a.disposed.get())
                actions.forEach { it() }
                while (true) { val intent = app.nextStartedActivity ?: break; started += intent.action }
            }
            drainActions()
            printReadBinding("matrix_$loss", readProbe)
            assertEquals(originalOwner, owner())
            assertEquals(originalSession, ApiClient.queueSessionGeneration())
            assertEquals("Retained actions must not POST before their parent is disposed: $which/$loss", initialPosts, posts(a).size)
            assertEquals("Retained map must not start an outgoing Intent", emptyList<String?>(), started)
            if (loss == Loss.PENDING_RIDE) assertTrue("Pending public ride must remain unresolved during the privacy check",
                a.vm.pendingPublicRideNavigation.value != null)
            noOldPrivate()
            positiveOtherWindow()
        } finally {
            holdPublicRide = false
            releasePublicRide.countDown()
            holdAReads = false
            releaseAReads.countDown()
        }
    }
    @Test fun bookingScreenOnly() = matrix(Chain.BOOKING, Loss.SCREEN)
    @Test fun bookingBookingOnly() = matrix(Chain.BOOKING, Loss.BOOKING)
    @Test fun bookingRideOnly() = matrix(Chain.BOOKING, Loss.RIDE)
    @Test fun bookingRevisionOnly() = matrix(Chain.BOOKING, Loss.REVISION)
    @Test fun bookingPendingBookingOnly() = matrix(Chain.BOOKING, Loss.PENDING_BOOKING)
    @Test fun bookingPendingScreenOnly() = matrix(Chain.BOOKING, Loss.PENDING_SCREEN)
    @Test fun bookingPendingPublicRideOnly() = matrix(Chain.BOOKING, Loss.PENDING_RIDE)
    @Test fun completedScreenOnly() = matrix(Chain.COMPLETED, Loss.SCREEN)
    @Test fun completedBookingOnly() = matrix(Chain.COMPLETED, Loss.BOOKING)
    @Test fun completedRideOnly() = matrix(Chain.COMPLETED, Loss.RIDE)
    @Test fun completedRevisionOnly() = matrix(Chain.COMPLETED, Loss.REVISION)
    @Test fun completedPendingBookingOnly() = matrix(Chain.COMPLETED, Loss.PENDING_BOOKING)
    @Test fun completedPendingScreenOnly() = matrix(Chain.COMPLETED, Loss.PENDING_SCREEN)
    @Test fun completedPendingPublicRideOnly() = matrix(Chain.COMPLETED, Loss.PENDING_RIDE)

    @Test fun currentBookingAcceptsSuccessfulHttpAndFreshMeetingVersion() {
        open(Chain.BOOKING)
        val submit = prepareBooking(a).last()
        val initialReads = reads(a).size
        successFor = 11
        compose.runOnIdle { submit() }
        awaitUi { posts(a).size == 2 && reads(a).size > initialReads &&
            OfflineTripSnapshots.projection(11)?.details?.meetingVersion == 3 }
        assertEquals("parent-saved-11", OfflineTripSnapshots.projection(11)?.details?.meetNote)
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        assertTrue(OfflineTripSnapshots.allowsLive(11))
    }
    @Test fun currentCompletedAcceptsSuccessfulRatingThanksAndLostHttp() {
        open(Chain.COMPLETED)
        val actions = prepareCompleted(a)
        val history = requireNotNull(actualHistory(11))
        successFor = 11
        compose.runOnIdle { actions.forEach { it() } }
        awaitUi { posts(a).size == 6 &&
            compose.onAllNodes(text(a, "Готово")).fetchSemanticsNodes().isNotEmpty() }
        scroll(a, hasText("Рәхмәт передано"))
        node(a, "Рәхмәт передано").assertExists()
        scroll(a, hasText("Чат поездки снова открыт"))
        node(a, "Чат поездки снова открыт").assertExists()
        assertSame(history, actualHistory(11))
        assertTrue(OfflineTripSnapshots.allowsHistory(history))
    }
    @Test fun exactBookingLinkStubWithFreshHistoryAllowsCompletedActions() {
        open(Chain.COMPLETED, initialStubA = true)
        val history = requireNotNull(actualHistory(11))
        assertEquals(bookingLinkStubRide(11), a.vm.selectedRide.value)
        assertEquals("11", a.vm.selectedRide.value?.id)
        assertEquals(11, history.bookingId)
        assertEquals(33, history.rideId)
        assertTrue(OfflineTripSnapshots.allowsHistory(history))
        assertTrue(requests.any { it.method == "GET" && it.requestUrl!!.encodedPath == "/trips/11/receipt" })
        compose.onAllNodes(hasText("parent-history-11", substring = true) and inside(a))[0].assertIsDisplayed()
        val actions = prepareCompleted(a)
        rememberOtherWindow()
        successFor = 11
        compose.runOnIdle { actions.forEach { it() } }
        awaitUi { posts(a).size == 6 &&
            compose.onAllNodes(text(a, "Готово")).fetchSemanticsNodes().isNotEmpty() }
        scroll(a, hasText("Рәхмәт передано"))
        node(a, "Рәхмәт передано").assertExists()
        scroll(a, hasText("Чат поездки снова открыт"))
        node(a, "Чат поездки снова открыт").assertExists()
        assertSame(history, actualHistory(11))
        assertTrue(OfflineTripSnapshots.allowsHistory(history))
        positiveOtherWindow()
        assertSame(history, actualHistory(11))
        assertTrue(OfflineTripSnapshots.allowsHistory(history))
    }
    @Test fun completedInitialWrongRideCannotExposeHistoryOrAct() {
        open(Chain.COMPLETED, initialRideA = 34)
        val history = requireNotNull(actualHistory(11))
        assertTrue(OfflineTripSnapshots.allowsHistory(history))
        assertEquals(11, history.bookingId)
        assertEquals(33, history.rideId)
        assertEquals("34", a.vm.selectedRide.value?.id)
        // The production link stub uses booking id, not ride id. This blank copy has another
        // id and is NOT that exact stub; a future guard must not accept every blank summary.
        assertNotEquals(bookingLinkStubRide(11), a.vm.selectedRide.value)
        assertTrue(requests.any { it.method == "GET" && it.requestUrl!!.encodedPath == "/trips/11/receipt" })
        assertEquals(0, posts(a).size)
        rememberOtherWindow()
        printReadBinding("initial_wrong_ride", parentReadProbe())
        noOldPrivate()
        listOf(tag(a, "rideshareStar4"), tag(a, "rideshareRatingSubmit"),
            tag(a, "rideshareLostItem"), text(a, "Сказать «Рәхмәт»")).forEach { matcher ->
            val nodes = compose.onAllNodes(matcher)
            nodes.fetchSemanticsNodes().indices.forEach { nodes[it].assertIsNotEnabled() }
        }
        assertEquals(0, posts(a).size)
        positiveOtherWindow()
        assertEquals(0, posts(a).size)
        assertSame(history, actualHistory(11))
        assertTrue(OfflineTripSnapshots.allowsHistory(history))
    }
    private fun lateResponse(which: Chain) {
        open(which)
        val actions = prepareTarget()
        rememberOtherWindow()
        val readProbe = parentReadProbe()
        holdAReads = true
        try {
            heldPath = "/bookings/11/" + if (which == Chain.BOOKING) "meeting-point" else "rate"
            val baseline = posts(a).size
            compose.runOnIdle { if (which == Chain.BOOKING) actions.last()() else actions.first()() }
            awaitUi { actionHeld.count == 0L }
            assertEquals(baseline + 1, posts(a).size)
            val beforeDisposal = a.disposed.get()
            inSameFrameGap {
                // Revision-only keeps the same screen/booking/ride and active coroutine scope.
                // A transport result must not appear safe merely because navigation disposed it.
                loseOnly(Loss.REVISION)
                releaseAction.countDown()
            }
            awaitUi { actionReturned.count == 0L }
            drainActions()
            printReadBinding("late_revision", readProbe)
            assertEquals(beforeDisposal, a.disposed.get())
            compose.onAllNodesWithText("qa-parent-late-11", substring = true).assertCountEquals(0)
            assertEquals("One already-started request may finish, but it must never be retried", baseline + 1, posts(a).size)
            noOldPrivate()
            positiveOtherWindow()
        } finally {
            releaseAction.countDown()
            holdAReads = false
            releaseAReads.countDown()
        }
    }
    @Test fun bookingLateResponseAfterRevisionCannotPublish() = lateResponse(Chain.BOOKING)
    @Test fun completedLateResponseAfterRevisionCannotPublish() = lateResponse(Chain.COMPLETED)

    private fun lifetime(which: Chain, replacement: String?) {
        open(which)
        val actions = prepareTarget()
        val count = posts(a).size
        val app = Shadows.shadowOf(context as Application)
        val readProbe = parentReadProbe()
        holdAReads = true
        try {
            if (replacement == null) {
                rememberOtherWindow()
                compose.runOnIdle { a.mounted.value = false }
                compose.waitForIdle()
                assertEquals(1, a.disposed.get())
            }
            val started = mutableListOf<String?>()
            inSameFrameGap {
                if (replacement != null) ApiClient.saveToken(replacement)
                actions.forEach { it() }
                while (true) { val intent = app.nextStartedActivity ?: break; started += intent.action }
            }
            drainActions()
            printReadBinding("lifetime", readProbe)
            assertEquals(count, posts(a).size)
            assertEquals(emptyList<String?>(), started)
            noOldPrivate()
            if (replacement == null) positiveOtherWindow()
        } finally {
            holdAReads = false
            releaseAReads.countDown()
        }
    }
    @Test fun bookingDisposedCallbackCannotAct() = lifetime(Chain.BOOKING, null)
    @Test fun completedDisposedCallbackCannotAct() = lifetime(Chain.COMPLETED, null)
    @Test fun bookingOtherOwnerCannotAct() = lifetime(Chain.BOOKING, jwt(8))
    @Test fun completedOtherOwnerCannotAct() = lifetime(Chain.COMPLETED, jwt(8))
    @Test fun bookingReplacementSessionCannotAct() = lifetime(Chain.BOOKING, jwt(7, "replacement"))
    @Test fun completedReplacementSessionCannotAct() = lifetime(Chain.COMPLETED, jwt(7, "replacement"))

    private fun receiptAction(): () -> Boolean {
        // New receipt cases have no held ROLE response: all authority comes from actual HTTP.
        releaseRoles.countDown()
        open(Chain.COMPLETED)
        scroll(a, hasText("Квитанция"))
        val history = requireNotNull(actualHistory(11))
        assertTrue(OfflineTripSnapshots.allowsHistory(history))
        assertTrue(requests.any { it.method == "GET" && it.requestUrl!!.encodedPath == "/trips/11/receipt" })
        return retainedSemanticsClick(text(a, "Квитанция"))
    }
    private fun currentReceiptCallback(target: Screen, existingId: Int) {
        val action = receiptAction()
        inSameFrameGap {
            a.vm.screen.value = target
            a.receiptBookingId.value = existingId
            action()
            assertEquals(1, a.receiptOpens.get())
            assertEquals(11, a.receiptBookingId.value)
            assertEquals(Screen.TripReceipt, a.vm.screen.value)
            assertEquals(11, a.vm.activeBookingId.value)
        }
        assertEquals(0, posts(a).size)
    }
    @Test fun receiptCurrentActiveParentOpensExactReceipt() = currentReceiptCallback(Screen.ActiveTrip, 0)
    @Test fun receiptCurrentMissingRootIdCanBeRepairedBeforeFallback() = currentReceiptCallback(Screen.TripReceipt, 0)
    @Test fun receiptCurrentSameRootIdCanBeReusedBeforeFallback() = currentReceiptCallback(Screen.TripReceipt, 11)

    private fun deniedReceiptCallback(loss: String) {
        val action = receiptAction()
        val originalHistory = requireNotNull(actualHistory(11))
        if (loss == "oldHistory") {
            val replacement = runBlocking { ApiClient.getTripReceipt(11).getOrThrow() }
            assertNotSame(originalHistory, replacement)
            assertSame(replacement, actualHistory(11))
            assertTrue(OfflineTripSnapshots.allowsHistory(replacement))
            assertFalse(OfflineTripSnapshots.allowsHistory(originalHistory))
        }
        if (loss == "disposed") {
            compose.runOnIdle { a.mounted.value = false }
            compose.waitForIdle()
            assertEquals(1, a.disposed.get())
        }
        inSameFrameGap {
            when (loss) {
                "settings" -> a.vm.screen.value = Screen.Settings
                "missingBooking" -> a.vm.activeBookingId.value = null
                "booking" -> loseOnly(Loss.BOOKING)
                "ride" -> loseOnly(Loss.RIDE)
                "revision" -> loseOnly(Loss.REVISION)
                "pendingBooking" -> loseOnly(Loss.PENDING_BOOKING)
                "pendingScreen" -> loseOnly(Loss.PENDING_SCREEN)
                "pendingRide" -> loseOnly(Loss.PENDING_RIDE)
                "owner" -> ApiClient.saveToken(jwt(8))
                "session" -> ApiClient.saveToken(jwt(7, "receipt-replacement"))
                "foreignRootId" -> {
                    a.vm.screen.value = Screen.TripReceipt
                    a.receiptBookingId.value = 22
                }
                "oldHistory", "disposed" -> Unit
                else -> error(loss)
            }
            val before = parentState()
            val receiptIdBefore = a.receiptBookingId.value
            action()
            assertEquals("Denied receipt callback changed navigation: $loss", before, parentState())
            assertEquals("Denied receipt callback changed receipt ID: $loss", receiptIdBefore, a.receiptBookingId.value)
            assertEquals("Denied receipt callback reached the navigation sink: $loss", 0, a.receiptOpens.get())
        }
        assertEquals(0, posts(a).size)
    }
    @Test fun receiptSettingsCannotNavigate() = deniedReceiptCallback("settings")
    @Test fun receiptMissingActiveBookingCannotNavigate() = deniedReceiptCallback("missingBooking")
    @Test fun receiptOtherActiveBookingCannotNavigate() = deniedReceiptCallback("booking")
    @Test fun receiptOtherRideCannotNavigate() = deniedReceiptCallback("ride")
    @Test fun receiptOtherRevisionCannotNavigate() = deniedReceiptCallback("revision")
    @Test fun receiptPendingBookingCannotNavigate() = deniedReceiptCallback("pendingBooking")
    @Test fun receiptPendingScreenCannotNavigate() = deniedReceiptCallback("pendingScreen")
    @Test fun receiptPendingRideCannotNavigate() = deniedReceiptCallback("pendingRide")
    @Test fun receiptOtherOwnerCannotNavigate() = deniedReceiptCallback("owner")
    @Test fun receiptReplacementSessionCannotNavigate() = deniedReceiptCallback("session")
    @Test fun receiptDisposedCallbackCannotNavigate() = deniedReceiptCallback("disposed")
    @Test fun receiptOldHistoryInstanceCannotNavigate() = deniedReceiptCallback("oldHistory")
    @Test fun receiptForeignRootIdCannotNavigate() = deniedReceiptCallback("foreignRootId")

    /** Renderer guards use the existing actual-HTTP fixture; storage durability is tested separately. */
    private fun warmPaymentProof(method: String = "cash", amount: Int? = 0): Pair<TripPass, OfflineTripSnapshotMode> = runBlocking {
        handler = { request -> when (request.requestUrl!!.encodedPath) {
            "/bookings/11/details" -> MockResponse().setBody(detailsJson(11, status = "confirmed").apply {
                remove("onboard_at")
                put("pay_method", method)
                put("pay_amount", amount ?: JSONObject.NULL)
            }.toString())
            "/bookings/11/role" -> MockResponse().setBody(roleJson(status = "confirmed").apply { remove("onboard_at") }.toString())
            else -> defaultResponse(request)
        } }
        warm()
        val pass = requireNotNull(OfflineTripSnapshots.privatePass(11))
        assertTrue(OfflineTripSnapshots.matchesPass(pass))
        assertEquals(method, pass.payMethod)
        assertEquals(amount, pass.payAmount)
        pass to OfflineTripSnapshotMode(requireNotNull(OfflineTripSnapshots.load(owner(), 11)), 1)
    }
    private fun mountWarmPayment(mode: OfflineTripSnapshotMode, parent: () -> Boolean = { true }, expectedText: String = "0 ₽ · Наличными") {
        warmMounted.value = true
        compose.setContent {
            YuldashTheme {
                CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                    if (warmMounted.value) OfflineActiveTripSnapshot(mode, parent, onRetry = {}, onBack = {})
                }
            }
        }
        awaitUi { compose.onAllNodesWithText(expectedText).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun warmScroll(value: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(value))
        compose.onNodeWithText(value).assertIsDisplayed()
    }
    private fun noWarmContacts(pass: TripPass) {
        listOf(pass.driverPhone, pass.driverName, pass.driverCar, pass.driverPlate, pass.boardingCode,
            pass.meetNote, pass.meetLandmarkRu, pass.meetLandmarkBa,
            "Паспорт поездки", "Позвонить", "Открыть точку на карте", "Связь и встреча", "Код посадки")
            .filter(String::isNotBlank).distinct().forEach { marker ->
                compose.onAllNodes(hasText(marker, substring = true), useUnmergedTree = true).assertCountEquals(0)
            }
    }
    @Suppress("DEPRECATION")
    private fun noWarmPayment() {
        listOf("0 ₽", "Наличными", "Как договорились платить").forEach { marker ->
            compose.onAllNodes(hasText(marker, substring = true), useUnmergedTree = true).fetchSemanticsNodes().forEach { node ->
                assertTrue("Warm payment remained accessible: $marker",
                    generateSequence(node) { it.parent }.any { it.config.contains(SemanticsProperties.InvisibleToUser) })
            }
        }
    }
    @Test fun warmPaymentZeroShowsReadonlyAgreementWithoutContactsOrActions() {
        val (pass, mode) = warmPaymentProof()
        mountWarmPayment(mode)
        warmScroll("0 ₽ · Наличными")
        warmScroll("Как договорились платить")
        compose.onNodeWithText("Наличными").assertIsDisplayed()
        compose.onNodeWithText("0 ₽").assertIsDisplayed()
        noWarmContacts(pass)
        assertFalse(requests.any { it.method == "POST" })
    }
    @Test fun warmPaymentDisappearsWhenParentLeaves() {
        val (pass, mode) = warmPaymentProof()
        val parent = mutableStateOf(true)
        mountWarmPayment(mode, { parent.value })
        warmScroll("0 ₽ · Наличными")
        warmScroll("Как договорились платить")
        compose.runOnIdle {
            parent.value = false
            assertTrue("This case removes only the parent, not the private permission", OfflineTripSnapshots.matchesPass(pass))
        }
        settle()
        noWarmPayment()
        noWarmContacts(pass)
        assertFalse(requests.any { it.method == "POST" })
    }
    private fun denyWarmPayment(kind: String) {
        val (pass, mode) = warmPaymentProof()
        mountWarmPayment(mode)
        warmScroll("0 ₽ · Наличными")
        warmScroll("Как договорились платить")
        noWarmContacts(pass)
        when (kind) {
            "owner" -> compose.runOnIdle { ApiClient.saveToken(jwt(8)) }
            "terminal" -> runBlocking {
                handler = { request -> if (request.path == "/bookings/11/role")
                    MockResponse().setBody(roleJson(status = "done").toString()) else defaultResponse(request) }
                assertEquals("done", ApiClient.getTripStateForNavigation(11).result.getOrThrow().status)
            }
            "expiry" -> {
                val expired = 1000 + OfflineTripSnapshotPolicy.MAX_AGE_MS
                clock = SnapshotClock(3, expired, expired, expired)
            }
            else -> error("Unknown warm payment denial")
        }
        assertFalse(OfflineTripSnapshots.matchesPass(pass))
        settle()
        noWarmPayment()
        noWarmContacts(pass)
    }
    @Test fun warmPaymentDisappearsAfterOwnerChange() = denyWarmPayment("owner")
    @Test fun warmPaymentDisappearsAfterTerminal() = denyWarmPayment("terminal")
    @Test fun warmPaymentDisappearsAfterExpiry() = denyWarmPayment("expiry")
    @Test fun coldSnapshotBytesDoNotMountWarmPayment() {
        val (pass, mode) = warmPaymentProof()
        val namespace = owner().namespace
        reopenAuthority()
        assertNotNull("The test must retain persisted snapshot candidate bytes", secure.getString(namespace, null))
        assertNull(OfflineTripSnapshots.privatePass(11))
        assertFalse(OfflineTripSnapshots.matchesPass(pass))
        mountWarmPayment(mode, expectedText = "Сохранённые данные устарели или недоступны")
        noWarmPayment()
        noWarmContacts(pass)
    }
    @Test fun unknownWarmPaymentMethodIsNotInventedAsNegotiate() {
        val (pass, mode) = warmPaymentProof(method = "future_payment")
        mountWarmPayment(mode, expectedText = "Уфа → Сибай")
        noWarmPayment()
        noWarmContacts(pass)
        compose.onAllNodesWithText("Договоримся").assertCountEquals(0)
    }
}
