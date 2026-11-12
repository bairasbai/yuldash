package com.yuldash.app.data

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Actual ApiClient HTTP -> trace -> shared gate -> actual preference adapter, using synthetic unit data. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
internal class SnapshotTransportWiringTest : SnapshotWiringFixture() {
    @Test fun coldBytesAndCachedDtoCannotGrantLiveOrPrivateUntilOwnActualDetails200() = runBlocking {
        warm()
        val candidate = requireNotNull(OfflineTripSnapshots.privatePass(11))
        reopenAuthority()
        assertNull(TripPassStore.load(context, 11)); assertNull(OfflineTripSnapshots.load(owner(), 11))
        assertFalse(OfflineTripSnapshots.matchesPass(candidate)); assertFalse(OfflineTripSnapshots.allowsLive(11))
        val value = ApiClient.getBookingDetails(11).getOrThrow()
        assertTrue(OfflineTripSnapshots.privateDetails(value)); assertTrue(OfflineTripSnapshots.allowsLive(11))
        assertEquals("", OfflineTripSnapshots.privatePass(11)!!.boardingCode)
        assertEquals("4821", ApiClient.getBoardingCode(11).getOrThrow())
        assertTrue(requests.filter { it.path?.startsWith("/bookings/") == true }.all { it.getHeader("Authorization") == "Bearer ${jwt(7)}" })
    }
    @Test fun ownBookingListClosesOnlyExplicitTerminalCopiesAcrossProcessReopen() = runBlocking {
        warm()
        for (id in 12..14) ApiClient.getBookingDetails(id).getOrThrow()
        reopenAuthority()
        assertEquals(setOf(11, 12, 13, 14), OfflineTripSnapshots.historicalSummaries().map { it.bookingId }.toSet())
        handler = { request ->
            if (request.path == "/bookings/mine") MockResponse().setBody(
                """{"items":[{"id":11,"status":"cancelled"},{"id":12,"status":"confirmed"},{"id":13,"status":"done"},{"id":99,"status":"done"}]}"""
            ) else defaultResponse(request)
        }

        assertEquals(listOf("cancelled", "confirmed", "done", "done"),
            ApiClient.getMyBookingsDetailed().getOrThrow().map { it.status })
        reopenAuthority()
        assertEquals(setOf(12, 14), OfflineTripSnapshots.historicalSummaries().map { it.bookingId }.toSet())
        assertFalse(OfflineTripSnapshots.allowsLive(11))
        assertFalse(OfflineTripSnapshots.allowsLive(12))
        val disk = SnapshotPreferencesDisk(meta, secure)
        assertNull(disk.barrier(owner().barrier("booking", 99)))
        val saved = disk.read(owner().namespace)
        ApiClient.getMyBookingsDetailed().getOrThrow()
        assertEquals(saved, disk.read(owner().namespace))
    }

    @Test fun ownTerminalListPersistsBarrierWhileEncryptedPayloadIsUnavailable() = runBlocking {
        warm()
        val previous = owner()
        OfflineTripSnapshots.installForTest(SnapshotPreferencesDisk(meta, null)) { clock }
        OfflineTripSnapshots.activate(previous.userId, previous.scope, previous.generation)
        handler = { request ->
            if (request.path == "/bookings/mine")
                MockResponse().setBody("""{"items":[{"id":11,"status":"cancelled"}]}""")
            else defaultResponse(request)
        }

        assertEquals("cancelled", ApiClient.getMyBookingsDetailed().getOrThrow().single().status)
        assertEquals("closed", SnapshotPreferencesDisk(meta, null).barrier(previous.barrier("booking", 11)))
        reopenAuthority()
        assertTrue(OfflineTripSnapshots.historicalSummaries().isEmpty())
    }

    @Test fun heldOldBookingListCannotCloseTheNewOwnersCopyOfTheSameId() = runBlocking {
        warm()
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        handler = { request ->
            if (request.path == "/bookings/mine" && request.getHeader("Authorization") == "Bearer " + jwt(7)) {
                held.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                MockResponse().setBody("""{"items":[{"id":11,"status":"cancelled"}]}""")
            } else defaultResponse(request)
        }
        ApiClient.testTimeoutMs = 8000
        val oldList = async(Dispatchers.IO) { ApiClient.getMyBookingsDetailed() }
        try {
            assertTrue(held.await(10, TimeUnit.SECONDS))
            ApiClient.saveToken(jwt(8))
            val fresh = ApiClient.getBookingDetails(11).getOrThrow()
            assertTrue(OfflineTripSnapshots.privateDetails(fresh))
            release.countDown()
            assertTrue(oldList.await().isFailure)
            assertTrue(OfflineTripSnapshots.privateDetails(fresh))
            reopenAuthority()
            assertEquals(8, owner().userId)
            assertEquals(11, OfflineTripSnapshots.historicalSummaries().single().bookingId)
        } finally {
            release.countDown()
        }
    }


    @Test fun observed401WithBrokenBodyRevokesBeforeRefreshAndNeverFallsBack() = runBlocking {
        warm()
        val previous = owner()
        handler = { MockResponse().setResponseCode(401).setBody("x".repeat(8000)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY) }
        val read = ApiClient.getTripStateForNavigation(11)
        assertTrue(read.result.isFailure); assertFalse(read.fallbackAllowed)
        assertFalse(OfflineTripSnapshots.allowsLive(11)); assertNull(TripPassStore.load(context, 11))
        assertNull(OfflineTripSnapshots.load(previous, 11))
        assertNull(OfflineTripSnapshots.currentOwner())
    }
    @Test fun logoutRevokesMemoryBeforeTheFirstAuthPreferenceCommitCompletes(): Unit = runBlocking {
        warm()
        val previous = owner()
        val details = requireNotNull(OfflineTripSnapshots.projection(11)).details
        val generation = ApiClient.queueSessionGeneration()
        assertTrue(OfflineTripSnapshots.privateDetails(details))
        val auth = MemoryDiskPreferences()
        assertTrue(auth.edit().putString("token", jwt(7)).commit())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blockedAuth = object : SharedPreferences by auth {
            override fun edit(): SharedPreferences.Editor {
                val editor = auth.edit()
                var logoutMarker = false
                return object : SharedPreferences.Editor by editor {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                        if (key == AuthStorePolicy.STATE_KEY && value == "logout") logoutMarker = true
                        editor.putString(key, value)
                        return this
                    }
                    override fun commit(): Boolean {
                        if (logoutMarker) {
                            entered.countDown()
                            check(release.await(10, TimeUnit.SECONDS))
                        }
                        return editor.commit()
                    }
                }
            }
        }
        field("plainAuthPrefs", blockedAuth)
        field("prefs", blockedAuth)
        val logout = async(Dispatchers.IO) { ApiClient.logout() }
        try {
            assertTrue("The real auth logout marker must reach the controlled commit", entered.await(10, TimeUnit.SECONDS))
            // These reads do not acquire ApiClient.sessionLock: a blocked disk commit cannot hide
            // the security window by also blocking the observer behind that same monitor.
            val ownerDuringCommit = OfflineTripSnapshots.currentOwner()
            val liveDuringCommit = OfflineTripSnapshots.allowsLive(11)
            val privateDuringCommit = OfflineTripSnapshots.privateDetails(details)
            val passDuringCommit = TripPassStore.load(context, 11)
            val snapshotDuringCommit = OfflineTripSnapshots.load(previous, 11)
            val sessionDuringCommit = ApiClient.isCurrentSession(generation)
            assertNull("Logout must revoke the owner before waiting for disk", ownerDuringCommit)
            assertFalse("Old LIVE permission remained usable during auth commit", liveDuringCommit)
            assertFalse("Old DETAILS permission remained usable during auth commit", privateDuringCommit)
            assertNull(passDuringCommit)
            assertNull(snapshotDuringCommit)
            assertFalse(sessionDuringCommit)
        } finally {
            release.countDown()
            logout.await()
        }
        assertEquals("logout", auth.restarted().getString(AuthStorePolicy.STATE_KEY, null))
        assertFalse(auth.restarted().contains("token"))
        assertNull(OfflineTripSnapshots.currentOwner())
    }
    @Test fun successfulRefreshUsesNewReadTicketAndPreservesOnline200WithoutCacheFallback() = runBlocking {
        warm()
        installRefresh()
        val scope = owner().scope
        val roles = AtomicInteger()
        handler = { request -> when {
            request.path == "/auth/refresh" -> MockResponse().setBody(JSONObject().put("access_token", jwt(7, "new")).put("refresh_token", "qa-next-refresh").toString())
            request.path == "/bookings/11/role" && roles.incrementAndGet() == 1 -> MockResponse().setResponseCode(401).setBody("{}")
            else -> defaultResponse(request)
        } }
        val read = ApiClient.getTripStateForNavigation(11)
        assertTrue(read.result.isSuccess); assertFalse(read.fallbackAllowed)
        assertEquals(scope, owner().scope); assertTrue(OfflineTripSnapshots.allowsLive(11))
        assertNull(OfflineTripSnapshots.privatePass(11)) // ROLE did not restore old DETAILS/code.
        assertEquals(2, roles.get()); assertEquals(1, requests.count { it.path == "/auth/refresh" })
        assertTrue(OfflineTripSnapshots.privateDetails(ApiClient.getBookingDetails(11).getOrThrow()))
    }
    @Test fun actual400Bad200And503HaveDifferentFallbackRules() = runBlocking {
        for (kind in listOf("400", "bad200", "503")) {
            warm()
            handler = { if (kind == "bad200") MockResponse().setBody("{\"role\":\"owner\",\"status\":\"onboard\"}")
                else MockResponse().setResponseCode(kind.toInt()).setBody("{}") }
            val read = ApiClient.getTripStateForNavigation(11)
            assertTrue(read.result.isFailure)
            assertEquals(kind == "503", read.fallbackAllowed)
            if (kind != "503") { assertFalse(OfflineTripSnapshots.allowsLive(11)); assertNull(OfflineTripSnapshots.load(owner(), 11)) }
            handler = ::defaultResponse
        }
    }
    @Test fun lateOldOwner403CannotRevokeNewOwnersDetails() = runBlocking {
        warm()
        val held = CountDownLatch(1); val release = CountDownLatch(1)
        handler = { request ->
            if (request.path == "/bookings/11/role" && request.getHeader("Authorization") == "Bearer ${jwt(7)}") {
                held.countDown(); check(release.await(10, TimeUnit.SECONDS)); MockResponse().setResponseCode(403).setBody("{}")
            } else defaultResponse(request)
        }
        val old = async(Dispatchers.IO) { ApiClient.getTripState(11) }
        try {
            assertTrue(held.await(10, TimeUnit.SECONDS))
            ApiClient.saveToken(jwt(8))
            val fresh = ApiClient.getBookingDetails(11).getOrThrow()
            assertTrue(OfflineTripSnapshots.privateDetails(fresh))
            release.countDown(); assertTrue(old.await().isFailure)
            assertEquals(8, owner().userId); assertTrue(OfflineTripSnapshots.privateDetails(fresh))
        } finally { release.countDown() }
    }
    @Test fun processOnlyScopeKeepsFreshOnlineProjectionButPerformsNoSnapshotOrPassIo() = runBlocking {
        val calls = AtomicInteger()
        OfflineTripSnapshots.installForTest(object : SnapshotDisk {
            private fun forbidden(): Nothing { calls.incrementAndGet(); error("process-only disk IO") }
            override fun read(namespace: String): String? = forbidden()
            override fun write(namespace: String, value: String): Boolean = forbidden()
            override fun barrier(key: String): String? = forbidden()
            override fun setBarrier(key: String, value: String?): Boolean = forbidden()
            override fun retire(namespace: String): Boolean = forbidden()
            override fun purgeExcept(namespace: String?): Boolean = forbidden()
        }) { clock }
        OfflineTripSnapshotHooks.selected(AuthStorePolicy.Selection(MemoryDiskPreferences(), "plain", true))
        OfflineTripSnapshotHooks.restore(MemoryDiskPreferences(), true, 7, ApiClient.queueSessionGeneration())
        val first = owner()
        val value = ApiClient.getBookingDetails(11).getOrThrow()
        assertEquals(first, owner()); assertFalse(OfflineTripSnapshots.cacheEnabled(first))
        assertTrue(OfflineTripSnapshots.privateDetails(value)); assertTrue(OfflineTripSnapshots.allowsLive(11))
        assertEquals("4821", ApiClient.getBoardingCode(11).getOrThrow())
        assertNull(OfflineTripSnapshots.load(first, 11)); assertNull(TripPassStore.load(context, 11))
        assertFalse(TripPassStore.save(context, OfflineTripSnapshots.privatePass(11)!!))
        assertFalse(TripPassStore.updateBoardingCode(context, 11, "4821")); assertEquals(0, calls.get())
    }
    @Test fun heldOwnCancelThenFreshOldDetailsStillClosesOnActualSuccessfulTerminalPost() = runBlocking {
        warm()
        val held = CountDownLatch(1); val release = CountDownLatch(1)
        handler = { request -> if (request.path == "/bookings/11/commands/v1/cancel") {
            held.countDown(); check(release.await(10, TimeUnit.SECONDS)); cancelledBookingResponse()
        } else defaultResponse(request) }
        ApiClient.testTimeoutMs = 8000
        val cancel = async(Dispatchers.IO) { cancelPassengerOnStand() }
        try {
            assertTrue(held.await(10, TimeUnit.SECONDS))
            val oldState = ApiClient.getBookingDetails(11).getOrThrow()
            assertTrue(OfflineTripSnapshots.privateDetails(oldState)); assertTrue(OfflineTripSnapshots.allowsLive(11))
            release.countDown(); assertEquals("cancelled", cancel.await().getOrThrow().bookingStatus)
            assertFalse(OfflineTripSnapshots.allowsLive(11)); assertNull(TripPassStore.load(context, 11))
            assertNull(OfflineTripSnapshots.load(owner(), 11)); assertFalse(OfflineTripSnapshots.privateDetails(oldState))
            reopenAuthority(); assertNull(OfflineTripSnapshots.load(owner(), 11))
        } finally { release.countDown() }
    }
    @Test fun staleBoardingAckAfterNewerBoardingMutationAndDetailsIsNoOp() = runBlocking {
        val newer = "2026-11-02T00:00:00.123457Z"
        val held = CountDownLatch(1); val release = CountDownLatch(1); val posts = AtomicInteger()
        val actualEpoch = java.util.concurrent.atomic.AtomicReference(epoch)
        handler = { request -> when {
            request.path == "/bookings/11/details" -> MockResponse().setBody(detailsJson(role = "driver").put("onboard_at", actualEpoch.get()).toString())
            request.path == "/bookings/11/driver-status" && posts.incrementAndGet() == 1 -> {
                held.countDown(); check(release.await(10, TimeUnit.SECONDS))
                MockResponse().setBody(JSONObject().put("status", "onboard").put("onboard_at", epoch).toString())
            }
            request.path == "/bookings/11/driver-status" -> {
                actualEpoch.set(newer)
                MockResponse().setBody(JSONObject().put("status", "onboard").put("onboard_at", newer).toString())
            }
            else -> defaultResponse(request)
        } }
        ApiClient.testTimeoutMs = 8000
        ApiClient.getBookingDetails(11).getOrThrow()
        val first = async(Dispatchers.IO) { BoardingApi.markBoarded(11) }
        try {
            assertTrue(held.await(10, TimeUnit.SECONDS))
            assertTrue(BoardingApi.markBoarded(11).isSuccess)
            val fresh = ApiClient.getBookingDetails(11).getOrThrow()
            assertTrue(OfflineTripSnapshots.privateDetails(fresh))
            val before = OfflineTripSnapshots.load(owner(), 11)
            assertEquals(snapshotEpoch(newer), snapshotEpoch(before!!.state.maxOnboard))
            release.countDown(); assertTrue(first.await().isSuccess)
            assertTrue(OfflineTripSnapshots.privateDetails(fresh)); assertTrue(OfflineTripSnapshots.allowsLive(11))
            assertEquals(before, OfflineTripSnapshots.load(owner(), 11))
            assertEquals(2, posts.get())
        } finally { release.countDown() }
    }
    @Test fun malformedAcceptedNonterminalAckIsMemoryOnlyNotDurableAndNeedsFreshRead() = runBlocking {
        handler = { request -> if (request.path?.endsWith("/details") == true) MockResponse().setBody(detailsJson(role = "driver").toString()) else defaultResponse(request) }
        ApiClient.getBookingDetails(11).getOrThrow()
        val namespace = owner().namespace
        val raw = secure.restarted().getString(namespace, null)
        val barriers = meta.restarted().getString(SNAPSHOT_BARRIERS_KEY, null)
        handler = { MockResponse().setBody("{}") }
        assertTrue(BoardingApi.markBoarded(11).isSuccess)
        assertEquals(SnapshotPersistence.NOT_DURABLE, OfflineTripSnapshots.persistence.value)
        assertFalse(OfflineTripSnapshots.allowsLive(11)); assertNull(OfflineTripSnapshots.privatePass(11))
        assertEquals(raw, secure.restarted().getString(namespace, null)); assertEquals(barriers, meta.restarted().getString(SNAPSHOT_BARRIERS_KEY, null))
        reopenAuthority(); assertNull(OfflineTripSnapshots.load(owner(), 11))
        handler = { request -> if (request.path?.endsWith("/details") == true) MockResponse().setBody(detailsJson(role = "driver").toString()) else defaultResponse(request) }
        assertTrue(OfflineTripSnapshots.privateDetails(ApiClient.getBookingDetails(11).getOrThrow()))
    }
    @Test fun acceptedArrivingUpdatesWarmSnapshotAndOldRoleResponseCannotRollItBack() = runBlocking {
        val held = CountDownLatch(1); val release = CountDownLatch(1)
        handler = { request -> when (request.path) {
            "/bookings/11/details" -> MockResponse().setBody(detailsJson(role = "driver").toString())
            "/bookings/11/role" -> { held.countDown(); check(release.await(10, TimeUnit.SECONDS)); MockResponse().setBody(roleJson(role = "driver").put("driver_phase", "").toString()) }
            "/bookings/11/driver-status" -> MockResponse().setBody("{\"status\":\"onboard\",\"arriving_wait_sec\":0}")
            else -> defaultResponse(request)
        } }
        ApiClient.testTimeoutMs = 8000
        ApiClient.getBookingDetails(11).getOrThrow()
        val read = async(Dispatchers.IO) { ApiClient.getTripState(11) }
        try {
            assertTrue(held.await(10, TimeUnit.SECONDS)); assertTrue(BoardingApi.markArriving(11).isSuccess)
            assertEquals("arriving", OfflineTripSnapshots.load(owner(), 11)!!.state.phase)
            release.countDown(); assertTrue(read.await().isFailure)
            assertEquals("arriving", OfflineTripSnapshots.load(owner(), 11)!!.state.phase)
        } finally { release.countDown() }
    }
}

/** Reused test fixture; no production test flag grants authority. Every permit comes through actual HTTP. */
/** Guard belongs to this fixture's loaded class, not a verified cross-sandbox/global firewall. */
internal class SnapshotWiringCleanupGuard {
    private var poisoned = false
    @Synchronized fun requireHealthy() {
        if (poisoned) throw AssertionError(POISON_MARKER)
    }
    @Synchronized fun runSetup(action: () -> Unit) {
        requireHealthy()
        action()
    }
    @Synchronized fun runCleanup(permitted: Boolean, action: () -> Unit) {
        requireHealthy()
        if (!permitted) {
            poisoned = true
            throw AssertionError(POISON_MARKER)
        }
        action()
    }
    companion object {
        const val POISON_MARKER = "SNAPSHOT_WIRING_CLEANUP_POISON_ABORT"
    }
}

internal open class SnapshotWiringFixture {
    companion object { private val cleanupGuard = SnapshotWiringCleanupGuard() }
    protected open fun permitWiringCleanup(): Boolean = true
    protected fun requireHealthyWiringFixture() = cleanupGuard.requireHealthy()
    protected val context = ApplicationProvider.getApplicationContext<Context>()
    protected lateinit var server: MockWebServer
    protected val serverOrigin: String get() = "http://127.0.0.1:${server.port}"
    protected var meta = MemoryDiskPreferences()
    protected var secure = MemoryDiskPreferences()
    protected val metaFault = SnapshotWiringCommitFault()
    protected val secureFault = SnapshotWiringCommitFault()
    @Volatile protected var clock = SnapshotClock(3, 1000, 1000, 1000)
    protected val requests = CopyOnWriteArrayList<RecordedRequest>()
    @Volatile protected var handler: (RecordedRequest) -> MockResponse = ::defaultResponse
    protected val epoch = "2026-11-02T00:00:00.123456Z"
    protected fun jwt(id: Int, suffix: String = "qa") = "e30." + Base64.getUrlEncoder().withoutPadding()
        .encodeToString("""{"sub":"$id"}""".toByteArray()) + ".$suffix"
    protected fun field(name: String, value: Any?) = ApiClient::class.java.getDeclaredField(name).apply { isAccessible = true }.set(ApiClient, value)
    @Before fun setupWiring() = cleanupGuard.runSetup {
        ApiClient.resetForTest()
        listOf("prefs", "plainAuthPrefs", "secureAuthPrefs", "appCtx", "authStoreSource", "token", "refreshToken").forEach { field(it, null) }
        meta = MemoryDiskPreferences(); secure = MemoryDiskPreferences()
        handler = ::defaultResponse
        server = MockWebServer().apply { dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse { requests += request; return handler(request) }
        }; start(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0) }
        ApiClient.testBaseUrl = serverOrigin
        ApiClient.testTimeoutMs = 500
        OfflineTripSnapshots.installForTest(SnapshotPreferencesDisk(metaFault.wrap(meta), secureFault.wrap(secure))) { clock }
        ApiClient.saveToken(jwt(7))
        BookingCommandApi.init(MemoryDiskPreferences())
        TripPassStore.initStores(MemoryDiskPreferences(), MemoryDiskPreferences())
    }
    @After fun cleanupWiring() = cleanupGuard.runCleanup(permitWiringCleanup()) {
        ApiClient.resetForTest(); ApiClient.testTimeoutMs = null
        listOf("prefs", "plainAuthPrefs", "secureAuthPrefs", "appCtx", "authStoreSource", "token", "refreshToken").forEach { field(it, null) }
        TripPassStore.initStores(MemoryDiskPreferences(), null)
        server.shutdown()
    }
    protected fun owner() = requireNotNull(OfflineTripSnapshots.currentOwner())
    protected fun installRefresh() {
        val prefs = MemoryDiskPreferences()
        prefs.edit().putString("token", jwt(7)).putString("refresh_token", "qa-refresh").commit()
        field("prefs", prefs); field("refreshToken", "qa-refresh")
    }
    protected fun detailsJson(id: Int = 11, role: String = "passenger", status: String = "onboard", marker: String = "current-pin"): JSONObject = JSONObject()
        .put("booking_id", id).put("ride_id", 33).put("role", role).put("status", status).put("contact_unlocked", status in setOf("confirmed", "onboard"))
        .put("from_city", "Уфа").put("to_city", "Сибай").put("depart_at", "2026-11-02T10:00:00Z")
        .put("seats", 1).put("price", 500).put("pay_amount", 200).put("pay_method", "cash")
        .put("driver_name", "QA Driver").put("driver_phone", "+70000000000").put("driver_car", "QA car").put("driver_plate", "QA-plate")
        .put("pickup", "QA public stop").put("pickup_stop", JSONObject.NULL).put("dropoff_stop", "").put("ride_has_stops", true)
        .put("meet_lat", 55.1).put("meet_lng", 55.2).put("meet_note", marker).put("meet_landmark_ru", "QA RU").put("meet_landmark_ba", "QA BA")
        .put("alight_lat", 55.3).put("alight_lng", 55.4).put("alight_note", "$marker-alight").put("meeting_version", 2)
        .put("onboard_at", epoch)
    protected fun roleJson(role: String = "passenger", status: String = "onboard") = JSONObject()
        .put("role", role).put("status", status).put("driver_phase", "arriving").put("onboard_at", epoch)
    protected fun receiptJson(id: Int = 11, marker: String = "receipt-B") = JSONObject().put("booking_id", id).put("ride_id", 33)
        .put("role", "passenger").put("from_city", "Уфа").put("to_city", "Сибай").put("depart_at", "2026-11-02T10:00:00Z")
        .put("seats", 1).put("amount", 200).put("pay_method", "cash").put("paid", false).put("driver_name", marker)
    protected fun defaultResponse(request: RecordedRequest): MockResponse = when {
        request.path == "/me" -> MockResponse().setBody(JSONObject()
            .put("id", ApiClient.myUserId()).put("created_at", "2026-01-01T00:00:00Z").toString())
        request.path == "/bookings/11/cancel-preview" -> MockResponse().setBody(JSONObject()
            .put("booking_status", "confirmed").put("consequence", "early").put("terms_key", "a".repeat(64)).toString())
        request.path == "/bookings/11/commands/v1/cancel" -> cancelledBookingResponse()
        request.path?.endsWith("/details") == true -> MockResponse().setBody(detailsJson(request.path!!.split('/')[2].toInt()).toString())
        request.path?.endsWith("/role") == true -> MockResponse().setBody(roleJson().toString())
        request.path?.endsWith("/boarding-code") == true -> MockResponse().setBody("{\"code\":\"4821\"}")
        request.path?.endsWith("/receipt") == true -> MockResponse().setBody(receiptJson(request.path!!.split('/')[2].toInt()).toString())
        request.path?.endsWith("/complete") == true || request.path?.endsWith("/driver-status") == true -> MockResponse().setBody("{\"status\":\"done\"}")
        request.path?.endsWith("/trip-status") == true -> MockResponse().setBody("[]")
        else -> MockResponse().setResponseCode(404).setBody("{}")
    }
    protected fun cancelledBookingResponse() =
        MockResponse().setBody("""{"id":11,"ride_id":33,"passenger_id":7,"status":"cancelled"}""")
    protected suspend fun cancelPassengerOnStand(): Result<BookingDecisionOutcome> {
        val generation = ApiClient.queueSessionGeneration()
        val terms = BookingDecisionApi.preparePassengerCancellation(generation, 11, "confirmed").getOrThrow()
        return BookingDecisionApi.cancelPassenger(generation, terms, "")
    }
    protected suspend fun warm() {
        val value = ApiClient.getBookingDetails(11).getOrThrow()
        assertTrue(OfflineTripSnapshots.privateDetails(value))
        assertEquals("4821", ApiClient.getBoardingCode(11).getOrThrow())
        assertTrue(TripPassStore.save(context, requireNotNull(OfflineTripSnapshots.privatePass(11))))
        assertNotNull(OfflineTripSnapshots.load(owner(), 11))
    }
    protected fun reopenAuthority() {
        val previous = owner()
        meta = meta.restarted(); secure = secure.restarted()
        OfflineTripSnapshots.installForTest(SnapshotPreferencesDisk(metaFault.wrap(meta), secureFault.wrap(secure))) { clock }
        OfflineTripSnapshots.activate(previous.userId, previous.scope, previous.generation)
    }
    protected fun armFault(kind: String) {
        val namespace = owner().namespace
        metaFault.reject = { changes -> changes[SNAPSHOT_BARRIERS_KEY]?.let { value ->
            if (kind == "clear") JSONObject(value).length() == 0
            else kind in setOf("barrier", "both") && value.contains("closed")
        } == true }
        secureFault.reject = { changes -> kind in setOf("payload", "both") && changes[namespace]?.let {
            JSONObject(it).getJSONArray("closed_bookings").length() > 0
        } == true }
        metaFault.armed = true; secureFault.armed = true
    }
}

internal class SnapshotWiringCommitFault {
    @Volatile var armed = false
    var rejected = 0
    @Volatile var reject: (Map<String, String?>) -> Boolean = { false }
    fun wrap(base: SharedPreferences): SharedPreferences = object : SharedPreferences by base {
        override fun edit(): SharedPreferences.Editor {
            val editor = base.edit(); val changes = linkedMapOf<String, String?>()
            return object : SharedPreferences.Editor by editor {
                override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                    changes[requireNotNull(key)] = value; editor.putString(key, value); return this
                }
                override fun commit(): Boolean {
                    if (armed && reject(changes)) { rejected++; return false }
                    return editor.commit()
                }
            }
        }
    }
}
