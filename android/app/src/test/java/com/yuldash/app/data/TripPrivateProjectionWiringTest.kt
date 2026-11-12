package com.yuldash.app.data

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import okhttp3.mockwebserver.MockResponse
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
internal class TripPrivateProjectionWiringTest : SnapshotWiringFixture() {
    @Test fun freshRoleAndOldPrivatePassWithFailedDetailsNeverUnlockCodePhoneOrPoints() = runBlocking {
        warm(); val old = requireNotNull(OfflineTripSnapshots.privatePass(11)); reopenAuthority()
        handler = { request -> if (request.path?.endsWith("/details") == true) MockResponse().setResponseCode(503).setBody("{}") else defaultResponse(request) }
        assertTrue(ApiClient.getTripState(11).isSuccess)
        assertTrue(OfflineTripSnapshots.allowsLive(11)); assertTrue(ApiClient.getBookingDetails(11).isFailure)
        assertFalse(OfflineTripSnapshots.matchesPass(old)); assertNull(TripPassStore.load(context, 11))
        assertNull(OfflineTripSnapshots.privatePass(11))
        val before = requests.count { it.path?.endsWith("/boarding-code") == true }
        assertTrue(ApiClient.getBoardingCode(11).isFailure)
        assertEquals(before, requests.count { it.path?.endsWith("/boarding-code") == true })
    }
    @Test fun fullFingerprintChangesForSPointsConsentCodeAndRublesWithoutConvertingSavedMoney() = runBlocking {
        warm()
        val old = requireNotNull(OfflineTripSnapshots.projection(11))
        handler = { request -> if (request.path?.endsWith("/details") == true) MockResponse().setBody(detailsJson(marker = "latest-pin")
            .put("meet_lat", 56.1).put("alight_note", "latest-alight").put("meeting_version", 3).toString()) else defaultResponse(request) }
        val current = ApiClient.getBookingDetails(11).getOrThrow()
        val projection = requireNotNull(OfflineTripSnapshots.projection(11))
        assertNotEquals(old.fingerprint, projection.fingerprint); assertFalse(OfflineTripSnapshots.matchesPass(old.pass))
        assertEquals("", projection.pass.boardingCode); assertEquals("latest-pin", projection.pass.meetNote)
        assertTrue(OfflineTripSnapshots.privateDetails(current))
        assertEquals("4821", ApiClient.getBoardingCode(11).getOrThrow())
        val pass = requireNotNull(OfflineTripSnapshots.privatePass(11))
        assertEquals(500, pass.price); assertEquals(200, pass.payAmount); assertNull(pass.pickupStop); assertEquals("", pass.dropoffStop)
        assertTrue(TripPassStore.save(context, pass))
        val stored = JSONObject(TripPassStore.load(context, 11)!!.toJson().toString())
        assertEquals(500, stored.getInt("price")); assertEquals(200, stored.getInt("pay_amount")); assertEquals("latest-pin", stored.getString("meet_note"))
        assertFalse(TripPassStore.save(context, pass.copy(meetNote = "unproven")))
        assertFalse(TripPassStore.updateBoardingCode(context, 11, "unproven-code"))
    }
    @Test fun failedPassWriteKeepsLatestVerifiedMemoryAndCannotSubstituteOldDiskBlob() = runBlocking {
        warm()
        val old = requireNotNull(OfflineTripSnapshots.privatePass(11))
        val passPlain = MemoryDiskPreferences(); val passSecure = MemoryDiskPreferences()
        TripPassStore.initStores(passPlain, passSecure)
        assertTrue(TripPassStore.save(context, old))
        handler = { request -> if (request.path?.endsWith("/details") == true) MockResponse().setBody(detailsJson(marker = "fresh-after-failure").toString()) else defaultResponse(request) }
        ApiClient.getBookingDetails(11).getOrThrow(); ApiClient.getBoardingCode(11).getOrThrow()
        val fresh = requireNotNull(OfflineTripSnapshots.privatePass(11))
        passSecure.failWriteOf = "pass_11"
        assertFalse(TripPassStore.save(context, fresh))
        assertEquals("fresh-after-failure", TripPassStore.load(context, 11)!!.meetNote)
        assertEquals(old.meetNote, JSONObject(passSecure.restarted().getString("pass_11", null)!!).getString("meet_note"))
        reopenAuthority(); assertNull(TripPassStore.load(context, 11))
    }
    @Test fun acceptedTerminalClosesAllLivePrivateAndLateSaveButKeepsOwnReceiptHistory() = runBlocking {
        warm()
        val pass = requireNotNull(OfflineTripSnapshots.privatePass(11))
        assertTrue(cancelPassengerOnStand().isSuccess)
        assertFalse(OfflineTripSnapshots.allowsLive(11)); assertNull(TripPassStore.load(context, 11))
        assertFalse(TripPassStore.save(context, pass)); assertNull(OfflineTripSnapshots.load(owner(), 11))
        val receipt = ApiClient.getTripReceipt(11).getOrThrow()
        assertTrue(OfflineTripSnapshots.allowsHistory(receipt))
        TripPassStore.requestRemoval(context, 11)
        assertTrue(OfflineTripSnapshots.allowsHistory(receipt)); assertFalse(OfflineTripSnapshots.allowsLive(11))
    }
    @Test fun expiryThenWallRollbackCannotRevivePrivateWithoutAnotherActualDetailsRead() = runBlocking {
        warm(); val old = requireNotNull(OfflineTripSnapshots.privatePass(11))
        clock = SnapshotClock(3, OfflineTripSnapshotPolicy.MAX_AGE_MS + 1000, 2000, 2000)
        assertNull(TripPassStore.load(context, 11)); assertFalse(OfflineTripSnapshots.matchesPass(old))
        clock = SnapshotClock(3, 2000, 3000, 3000)
        assertNull(TripPassStore.load(context, 11)); assertFalse(OfflineTripSnapshots.allowsLive(11))
        assertTrue(OfflineTripSnapshots.privateDetails(ApiClient.getBookingDetails(11).getOrThrow()))
    }
    @Test fun orderedColdLogoutDeletesSecureBeforeMetaAndNeverOpensAdapterOrKeys() {
        val opened = mutableListOf<String>(); val removed = mutableListOf<String>()
        val metaOnly = MemoryDiskPreferences().apply { edit().putString(SNAPSHOT_BARRIERS_KEY, "{\"old\":\"closed\"}").commit() }
        val context = object : ContextWrapper(this.context) {
            override fun getSharedPreferences(name: String, mode: Int) = metaOnly.also { opened += name; assertEquals(SNAPSHOT_META_PREFS, name) }
        }
        val cleaner = SnapshotLogoutCleanup(context, { name -> removed += name; true }, { true })
        assertEquals(SnapshotPersistence.COMMITTED, cleaner.run())
        assertEquals(listOf(SNAPSHOT_SECURE_PREFS, SNAPSHOT_META_PREFS), removed)
        assertTrue(opened.all { it == SNAPSHOT_META_PREFS })
    }
    @Test fun failedSecureDeletionPreservesMetaAndConfirmedMarkerForExplicitRetry() {
        val removed = mutableListOf<String>()
        val metaOnly = MemoryDiskPreferences().apply { edit().putString(SNAPSHOT_BARRIERS_KEY, "{\"old\":\"closed\"}").commit() }
        val context = object : ContextWrapper(this.context) {
            override fun getSharedPreferences(name: String, mode: Int) = metaOnly.also { assertEquals(SNAPSHOT_META_PREFS, name) }
        }
        val fail = SnapshotLogoutCleanup(context, { name -> removed += name; false }, { false })
        assertEquals(SnapshotPersistence.DURABLY_BLOCKED, fail.run())
        assertEquals(listOf(SNAPSHOT_SECURE_PREFS), removed)
        assertEquals("{\"old\":\"closed\"}", metaOnly.restarted().getString(SNAPSHOT_BARRIERS_KEY, null))
        assertTrue(metaOnly.restarted().getBoolean(OfflineStoreReset.PENDING, false)); assertEquals(true, fail.pending())
    }
    @Test fun actualColdApiLogoutUsesExactOrderedPairWithoutOpeningSnapshotAdapter() = runBlocking {
        val removed = java.util.concurrent.CopyOnWriteArrayList<String>()
        val opened = java.util.concurrent.CopyOnWriteArrayList<String>()
        val metaOnly = MemoryDiskPreferences().apply { edit().putString(SNAPSHOT_BARRIERS_KEY, "{\"old\":\"closed\"}").commit() }
        val app = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = metaOnly.also { opened += name; assertEquals(SNAPSHOT_META_PREFS, name) }
            override fun deleteSharedPreferences(name: String): Boolean {
                assertTrue(name in setOf(SNAPSHOT_SECURE_PREFS, SNAPSHOT_META_PREFS)); removed += name
                return super.deleteSharedPreferences(name)
            }
        }
        OfflineTripSnapshots.resetForTest(); OfflineTripSnapshots.init(app)
        ApiClient.logout()
        withTimeout(5000) { while (OfflineTripSnapshots.persistence.value != SnapshotPersistence.COMMITTED) delay(10) }
        assertEquals(listOf(SNAPSHOT_SECURE_PREFS, SNAPSHOT_META_PREFS), removed.toList())
        assertTrue(opened.all { it == SNAPSHOT_META_PREFS }); assertNull(OfflineTripSnapshots.currentOwner())
    }
    @Test fun normalProcessOnlyNormalReentryUsesNewScopeOnlyAtBoundaryAndOldProofsStayRevoked() = runBlocking {
        val calls = AtomicInteger()
        val actual = SnapshotPreferencesDisk(meta, secure)
        val counted = object : SnapshotDisk {
            override fun read(namespace: String): String? { calls.incrementAndGet(); return actual.read(namespace) }
            override fun write(namespace: String, value: String): Boolean { calls.incrementAndGet(); return actual.write(namespace, value) }
            override fun barrier(key: String): String? { calls.incrementAndGet(); return actual.barrier(key) }
            override fun setBarrier(key: String, value: String?): Boolean { calls.incrementAndGet(); return actual.setBarrier(key, value) }
            override fun retire(namespace: String): Boolean { calls.incrementAndGet(); return actual.retire(namespace) }
            override fun purgeExcept(namespace: String?): Boolean { calls.incrementAndGet(); return actual.purgeExcept(namespace) }
        }
        OfflineTripSnapshots.installForTest(counted) { clock }
        OfflineTripSnapshotHooks.refreshed(7, ApiClient.queueSessionGeneration()); warm()
        val beforeBad = calls.get()
        val rejected = AuthStorePolicy.Selection(MemoryDiskPreferences(), "plain", true)
        OfflineTripSnapshotHooks.selected(rejected)
        OfflineTripSnapshotHooks.restore(rejected.prefs, true, 7, ApiClient.queueSessionGeneration())
        val first = owner(); assertFalse(OfflineTripSnapshots.cacheEnabled(first)); assertFalse(OfflineTripSnapshots.allowsLive(11))
        val old = ApiClient.getBookingDetails(11).getOrThrow()
        val oldProof = requireNotNull(OfflineTripSnapshots.projection(11)).proof
        val history = ApiClient.getTripReceipt(19).getOrThrow()
        assertEquals(beforeBad, calls.get()); assertTrue(OfflineTripSnapshots.privateDetails(old))
        val repaired = MemoryDiskPreferences().apply { edit().putString(SNAPSHOT_AUTH_SCOPE, "00000000-0000-4000-8000-000000000077").commit() }
        OfflineTripSnapshotHooks.selected(AuthStorePolicy.Selection(repaired, "plain"))
        OfflineTripSnapshotHooks.restore(repaired, true, 7, ApiClient.queueSessionGeneration())
        assertTrue(OfflineTripSnapshots.cacheEnabled(owner())); assertFalse(OfflineTripSnapshots.allowsLive(11)); assertFalse(OfflineTripSnapshots.allowsHistory(history))
        ApiClient.getBookingDetails(11).getOrThrow()
        val beforeReentry = calls.get()
        OfflineTripSnapshotHooks.selected(rejected); OfflineTripSnapshotHooks.restore(rejected.prefs, true, 7, ApiClient.queueSessionGeneration())
        val second = owner(); assertNotEquals(first.scope, second.scope); assertFalse(OfflineTripSnapshots.allowsLive(11))
        assertFalse(OfflineTripSnapshots.acceptDetails(oldProof, old))
        val fresh = ApiClient.getBookingDetails(11).getOrThrow(); assertTrue(OfflineTripSnapshots.privateDetails(fresh))
        OfflineTripSnapshotHooks.refreshed(7, ApiClient.queueSessionGeneration())
        assertEquals(second, owner()); assertEquals(beforeReentry, calls.get())
    }
}
