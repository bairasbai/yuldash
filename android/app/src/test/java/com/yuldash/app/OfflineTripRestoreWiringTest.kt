package com.yuldash.app

import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.SavedStateHandle
import com.yuldash.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Actual production navigation/draw boundary under Robolectric; not a real-device UI acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class OfflineTripRestoreWiringTest : SnapshotWiringFixture() {
    @get:Rule val compose = createComposeRule()
    private val mounted = mutableStateOf(true)
    private fun settle() {
        compose.mainClock.advanceTimeBy(50)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        Snapshot.sendApplyNotifications()
    }
    private fun vm(): YuldashViewModel = YuldashViewModel(SavedStateHandle()).apply {
        activeBookingId.value = 11; selectedRide.value = bookingLinkStubRide(11); screen.value = Screen.ActiveTrip
    }
    private fun mount(vm: YuldashViewModel) {
        compose.setContent { CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
            val session = ApiClient.sessionChanges.collectAsState(initial = ApiClient.queueSessionGeneration()).value
            if (mounted.value) GuardedActiveTripDestination(vm, session, emptyList(), null, false,
                onBack = {}, onTripEnd = {}, onSos = {}, onSupport = {}, onTrustedContacts = {}, onTripStopped = {},
                onOpenReceipt = {}, currentReceiptBookingId = { 0 })
        } }
    }
    private fun noPrivate() {
        compose.onAllNodesWithText("4821", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("+70000000000", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("current-pin", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("QA-plate", substring = true).assertCountEquals(0)
    }
    private fun coldFault(kind: String) = runBlocking {
        warm(); val namespace = owner().namespace; armFault(kind)
        if (kind == "clear") {
            handler = { request -> if (request.path == "/bookings/11/boarded/undo/respond") MockResponse().setBody(JSONObject()
                .put("status", "confirmed").put("boarding_undo_confirmed_id", "00000000-0000-4000-8000-000000000011")
                .put("boarding_undo_onboard_at", epoch).toString()) else defaultResponse(request) }
            assertTrue(BoardingApi.respondUndo(11, "00000000-0000-4000-8000-000000000011", true, ApiClient.queueSessionGeneration()).isSuccess)
        } else assertTrue(ApiClient.cancelBooking(11).isSuccess)
        if (kind == "clear") assertTrue(OfflineTripSnapshots.allowsLive(11)) else assertFalse(OfflineTripSnapshots.allowsLive(11))
        assertNull(TripPassStore.load(context, 11)); assertNull(OfflineTripSnapshots.load(owner(), 11))
        assertEquals(when (kind) {
            "both" -> SnapshotPersistence.NOT_DURABLE
            "barrier" -> SnapshotPersistence.COMMITTED
            else -> SnapshotPersistence.DURABLY_BLOCKED
        }, OfflineTripSnapshots.persistence.value)
        assertTrue(metaFault.rejected + secureFault.rejected > 0)
        if (kind == "both") assertTrue(JSONObject(secure.restarted().getString(namespace, null)!!).getJSONObject("records").has("11"))
        reopenAuthority()
        handler = { MockResponse().setResponseCode(503).setBody("{}") }
        val vm = vm(); mount(vm)
        compose.waitUntil(15000) { settle(); requests.any { it.path == "/bookings/11/role" } &&
            compose.onAllNodesWithText("Не удалось загрузить данные поездки").fetchSemanticsNodes().isNotEmpty() }
        noPrivate(); assertNull(vm.activeTrip.value); assertFalse(OfflineTripSnapshots.allowsLive(11))
    }
    @Test fun payloadFailureThroughRealClosureAndColdNavigationNeverDrawsOldTrip() = coldFault("payload")
    @Test fun barrierFailureThroughRealClosureAndColdNavigationNeverDrawsOldTrip() = coldFault("barrier")
    @Test fun totalFailureLeavesActualOldBytesButColdNavigationNeverDrawsThem() = coldFault("both")
    @Test fun clearProtectionFailureAfterAcceptedConsentCannotBecomeColdActiveUi() = coldFault("clear")

    @Test fun freshRoleWithOldPassAndDetails503DoesNotDrawOldPrivateFields() = runBlocking {
        warm(); reopenAuthority()
        handler = { request -> if (request.path?.endsWith("/details") == true) MockResponse().setResponseCode(503).setBody("{}") else defaultResponse(request) }
        val vm = vm(); mount(vm)
        compose.waitUntil(15000) { settle(); OfflineTripSnapshots.allowsLive(11) && requests.count { it.path?.endsWith("/details") == true } >= 2 }
        noPrivate(); assertNull(TripPassStore.load(context, 11))
    }
    @Test fun ownFreshDetailsAfterColdAuthorityDrawOnlyLatestSPointAndCurrentCode() = runBlocking {
        warm(); reopenAuthority()
        handler = { request -> when {
            request.path?.endsWith("/details") == true -> MockResponse().setBody(detailsJson(status = "confirmed", marker = "latest-visible-point")
                .put("boarding_undo_confirmed_id", "00000000-0000-4000-8000-000000000011").put("boarding_undo_onboard_at", epoch).toString())
            request.path?.endsWith("/role") == true -> MockResponse().setBody(roleJson(status = "confirmed")
                .put("boarding_undo_confirmed_id", "00000000-0000-4000-8000-000000000011").put("boarding_undo_onboard_at", epoch).toString())
            else -> defaultResponse(request)
        } }
        val vm = vm(); mount(vm)
        compose.waitUntil(15000) { settle(); compose.onAllNodesWithText("4821", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(OfflineTripSnapshots.allowsLive(11)); assertNotNull(vm.activeTrip.value)
        compose.onAllNodesWithText("current-pin", substring = true).assertCountEquals(0)
        val projection = requireNotNull(OfflineTripSnapshots.projection(11))
        assertEquals("latest-visible-point", projection.details.meetNote); assertEquals("4821", projection.pass.boardingCode)
        assertEquals(500, projection.pass.price); assertEquals(200, projection.pass.payAmount)
    }
    @Test fun warmRoleNetworkFailureUnmountsLiveUiAndShowsOnlyReadonlySnapshot(): Unit = runBlocking {
        warm()
        val vm = vm(); mount(vm)
        compose.waitUntil(15000) { settle(); vm.activeTrip.value != null && OfflineTripSnapshots.privatePass(11) != null }
        handler = { MockResponse().setResponseCode(503).setBody("{}") }
        assertTrue(ApiClient.getTripStateForNavigation(11).result.isFailure)
        compose.waitUntil(15000) { settle(); vm.offlineTripSnapshot.value != null &&
            compose.onAllNodesWithText("Сохранённая поездка").fetchSemanticsNodes().isNotEmpty() }
        assertNull(vm.activeTrip.value)
        noPrivate()
        compose.onAllNodesWithText("Связь и встреча").assertCountEquals(0)
    }

    private fun confirmedResponse(request: RecordedRequest): MockResponse = when {
        request.path == "/bookings/11/details" -> MockResponse().setBody(detailsJson(status = "confirmed").apply { remove("onboard_at") }.toString())
        request.path == "/bookings/11/role" -> MockResponse().setBody(roleJson(status = "confirmed").apply { remove("onboard_at") }.toString())
        request.path == "/bookings/11/messages" || request.path?.startsWith("/pickup-points?") == true -> MockResponse().setBody("{\"items\":[]}")
        else -> defaultResponse(request)
    }
    private fun awaitUi(check: () -> Boolean) = compose.waitUntil(15000) { settle(); check() }
    private fun captureText(text: String, scroll: Boolean = false): () -> Boolean {
        if (scroll) compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))
        awaitUi { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        return requireNotNull(compose.onNodeWithText(text).fetchSemanticsNode().config[SemanticsActions.OnClick].action)
    }
    private fun inSameFrameGap(action: () -> Unit) {
        // Finish positive preparation with a running clock. No semantics lookup, scrolling or
        // idling operation is allowed while frame advancement is paused below.
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnUiThread(action)
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }
    private fun drainActions() {
        // The adversarial UI turn has finished. Keep the clock running while the real callbacks
        // and controlled HTTP responses settle; all semantics/idling use this mode too.
        repeat(30) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeBy(50)
            Thread.sleep(10)
        }
        compose.waitForIdle()
    }
    private fun cancelPosts() = requests.filter { it.method == "POST" && it.path == "/bookings/11/cancel" }
    private fun meetingPosts() = requests.filter { it.method == "POST" && it.path == "/bookings/11/meeting-point" }

    @Test fun retainedCancelDialogCannotPostForAnotherOwnerOrAnOutgoingRoute(): Unit = runBlocking {
        compose.mainClock.autoAdvance = true
        handler = { request -> if (request.path == "/bookings/11/cancel")
            MockResponse().setBody("{\"status\":\"confirmed\"}") else confirmedResponse(request) }
        warm()
        val vm = vm(); mount(vm)
        awaitUi { compose.onAllNodesWithText("4821", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Отменить поездку"))
        compose.onNodeWithText("Отменить поездку").performClick()
        compose.onNodeWithText("Планы поменялись").performClick()
        compose.onNodeWithText("Да, отменить").performClick()
        awaitUi { cancelPosts().size == 1 && compose.onAllNodesWithText("Отменить поездку?").fetchSemanticsNodes().isEmpty() }
        assertEquals("plans_changed", JSONObject(cancelPosts().single().body.clone().readUtf8()).getString("reason"))
        assertEquals("Bearer ${jwt(7)}", cancelPosts().single().getHeader("Authorization"))

        compose.onNodeWithText("Отменить поездку").performClick()
        val oldOwnerConfirm = captureText("Да, отменить")
        inSameFrameGap { ApiClient.saveToken(jwt(8)); oldOwnerConfirm() }
        drainActions()
        assertEquals("A retained dialog must not POST with the replacement owner's bearer", 1, cancelPosts().size)
        assertEquals(8, owner().userId)

        awaitUi { OfflineTripSnapshots.privatePass(11)?.boardingCode == "4821" }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Отменить поездку"))
        compose.onNodeWithText("Отменить поездку").performClick()
        val outgoingConfirm = captureText("Да, отменить")
        inSameFrameGap { vm.navigateLocally { vm.screen.value = Screen.Settings }; outgoingConfirm() }
        drainActions()
        assertEquals("Changing route before the retained click must send zero additional POSTs", 1, cancelPosts().size)

        compose.runOnIdle { vm.navigateLocally { vm.screen.value = Screen.ActiveTrip } }
        awaitUi { compose.onAllNodesWithText("4821", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Отменить поездку"))
        compose.onNodeWithText("Отменить поездку").performClick()
        val queuedConfirm = captureText("Да, отменить")
        inSameFrameGap {
            // The real callback queues its coroutine on Main while Main is held here. Retire the
            // route after the click, but before that coroutine can reach the HTTP transport.
            runBlocking(Dispatchers.Default) { queuedConfirm() }
            vm.navigateLocally { vm.screen.value = Screen.Settings }
        }
        drainActions()
        assertEquals("A queued cancellation must recheck its captured route before HTTP", 1, cancelPosts().size)
        assertTrue("Leaving one window does not revoke another window's process authority", OfflineTripSnapshots.allowsLive(11))
    }

    @Test fun outgoingActiveTripRejectsRetainedCallMapAndMeetingSubmitBeforeDisposal(): Unit = runBlocking {
        compose.mainClock.autoAdvance = true
        handler = { request -> if (request.path == "/bookings/11/meeting-point" && request.method == "POST")
            MockResponse().setResponseCode(409).setBody("{\"detail\":{\"code\":\"qa_retry\",\"ru\":\"QA retry\",\"ba\":\"QA retry\"}}")
            else confirmedResponse(request) }
        warm()
        val vm = vm(); mount(vm)
        awaitUi { compose.onAllNodesWithContentDescription("Позвонить").fetchSemanticsNodes().isNotEmpty() }
        val call = requireNotNull(compose.onNodeWithContentDescription("Позвонить").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        val application = Shadows.shadowOf(context as Application)
        compose.runOnIdle { call() }
        assertEquals(Intent.ACTION_DIAL, requireNotNull(application.nextStartedActivity).action)
        val map = captureText("Маршрут к месту посадки", scroll = true)
        compose.runOnIdle { map() }
        assertEquals(Intent.ACTION_CHOOSER, requireNotNull(application.nextStartedActivity).action)
        val edit = captureText("Изменить место посадки", scroll = true)
        compose.runOnIdle { edit() }
        awaitUi { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction()).performTextReplacement("outgoing-draft")
        compose.onNodeWithText("Готово").performClick()
        awaitUi { meetingPosts().size == 1 && compose.onAllNodesWithText("QA retry").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(2, JSONObject(meetingPosts().single().body.clone().readUtf8()).getInt("base_version"))
        val submit = captureText("Готово")
        val prior = owner()
        val started = mutableListOf<String?>()
        inSameFrameGap {
            vm.navigateLocally { vm.screen.value = Screen.Settings }
            call(); map(); submit()
            // Observe synchronous side effects in the same turn, before recomposition/disposal
            // could accidentally make the old implementation appear safe.
            while (true) {
                val intent = application.nextStartedActivity ?: break
                started += intent.action
            }
        }
        drainActions()
        assertEquals(prior, owner())
        assertTrue(OfflineTripSnapshots.allowsLive(11))
        assertEquals("Retained call/map callbacks must use the parent's current route predicate", emptyList<String?>(), started)
        assertEquals("Retained meeting submission must not make an outgoing-screen POST", 1, meetingPosts().size)
    }

    @Test fun staleMeetingPointConflictFetchesNewDetailsAndRetriesOnlyWithTheirVersion(): Unit = runBlocking {
        compose.mainClock.autoAdvance = true
        val conflict = AtomicBoolean(false)
        val posts = AtomicInteger()
        val freshGet = CountDownLatch(1)
        handler = { request -> when {
            request.method == "POST" && request.path == "/bookings/11/meeting-point" -> {
                if (posts.incrementAndGet() == 1) {
                    conflict.set(true)
                    MockResponse().setResponseCode(409).setBody("{\"detail\":{\"code\":\"meeting_point_stale\",\"ru\":\"QA stale version\",\"ba\":\"QA stale version\"}}")
                } else MockResponse().setBody("{}")
            }
            request.path == "/bookings/11/details" && conflict.get() -> {
                freshGet.countDown()
                MockResponse().setBody(detailsJson(status = "confirmed", marker = "server-version-B").apply {
                    remove("onboard_at"); put("meeting_version", 3)
                }.toString())
            }
            else -> confirmedResponse(request)
        } }
        warm()
        val vm = vm(); mount(vm)
        awaitUi { compose.onAllNodesWithText("4821", substring = true).fetchSemanticsNodes().isNotEmpty() }
        val edit = captureText("Изменить место посадки", scroll = true)
        compose.runOnIdle { edit() }
        awaitUi { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction()).performTextReplacement("keep-my-conflict-draft")
        compose.onNodeWithText("Готово").performClick()
        awaitUi { meetingPosts().size == 1 && compose.onAllNodesWithText("QA stale version").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(2, JSONObject(meetingPosts().single().body.clone().readUtf8()).getInt("base_version"))
        drainActions()
        assertTrue("meeting_point_stale must perform a fresh GET instead of reusing initialProjection", freshGet.await(2, TimeUnit.SECONDS))
        awaitUi { OfflineTripSnapshots.projection(11)?.details?.meetingVersion == 3 }
        assertEquals("server-version-B", OfflineTripSnapshots.projection(11)?.details?.meetNote)
        compose.onNode(hasSetTextAction()).assertTextContains("keep-my-conflict-draft")
        assertEquals("A conflict refresh must not automatically repeat the POST", 1, meetingPosts().size)
        compose.onNodeWithText("Готово").performClick()
        awaitUi { meetingPosts().size == 2 }
        val retry = JSONObject(meetingPosts()[1].body.clone().readUtf8())
        assertEquals(3, retry.getInt("base_version"))
        assertEquals("keep-my-conflict-draft", retry.getJSONObject("point").getString("note"))
        assertEquals("Bearer ${jwt(7)}", meetingPosts()[1].getHeader("Authorization"))
    }
    private fun retainedMapAction(d: BookingDetailsDto): () -> Boolean {
        compose.setContent { if (mounted.value) OwnMeetingPoints(d) }
        compose.waitUntil(15000) { settle(); compose.onAllNodesWithText("Маршрут к месту посадки").fetchSemanticsNodes().isNotEmpty() }
        val action = compose.onNodeWithText("Маршрут к месту посадки").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val application = Shadows.shadowOf(context as Application)
        compose.runOnIdle { action() }; assertNotNull(application.nextStartedActivity)
        return action
    }
    @Test fun retainedMapActionDeniesUnmountWhileAnotherWindowCanKeepProcessAuthority() = runBlocking {
        val d = ApiClient.getBookingDetails(11).getOrThrow()
        val action = retainedMapAction(d)
        val application = Shadows.shadowOf(context as Application)
        compose.runOnIdle { mounted.value = false }; compose.waitForIdle()
        compose.runOnIdle { action() }; assertNull(application.nextStartedActivity)
        assertTrue(OfflineTripSnapshots.privateDetails(d))
    }
    @Test fun retainedMapActionDeniesOwnerChangeWhileItsWindowRemainsMounted() = runBlocking {
        val d = ApiClient.getBookingDetails(11).getOrThrow()
        val action = retainedMapAction(d)
        val application = Shadows.shadowOf(context as Application)
        ApiClient.saveToken(jwt(8)); compose.runOnIdle { action() }; assertNull(application.nextStartedActivity)
        compose.waitUntil(15000) { settle(); compose.onAllNodesWithText("Маршрут к месту посадки").fetchSemanticsNodes().isEmpty() }
        assertFalse(OfflineTripSnapshots.privateDetails(d))
    }
    @Test fun driverCabinetAndSavedStateRemainCandidatesWithoutAnyNetworkAuthority() {
        val vm = vm(); vm.openDriverTrip(11)
        assertNull(vm.activeTrip.value); assertFalse(OfflineTripSnapshots.allowsLive(11))
        vm.applyBookingLink(11, "driver", "onboard", false)
        assertNull(vm.activeTrip.value)
        val restored = YuldashViewModel(SavedStateHandle(mapOf("yuldash_screen" to "ActiveTrip", "yuldash_active_bid" to 11)))
        assertEquals(11, restored.activeBookingId.value); assertNull(restored.activeTrip.value)
        assertNull(restored.offlineTripSnapshot.value)
    }
}
