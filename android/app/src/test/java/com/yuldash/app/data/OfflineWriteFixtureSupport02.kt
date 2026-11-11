package com.yuldash.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** Test-only helpers for the four offline-write fixtures; no process-global safety claim. */
internal fun awaitOfflineWriteSnapshotReset02() = runBlocking {
    withTimeout(5_000) {
        while (!OfflineTripSnapshots.ensureResetCommitted()) delay(10)
    }
}

/** Pure legacy view: it does not open, recover, reset, grant authority or expire a projection. */
internal fun offlineWriteLegacyPass02(bookingId: Int): TripPass? = synchronized(TripPassStore) {
    val field = TripPassStore::class.java.getDeclaredField("migration").apply { isAccessible = true }
    val selection = field.get(TripPassStore) as? OfflineMigration.Selection
    selection?.read("pass_$bookingId")?.let { TripPass.fromJson(JSONObject(it)) }
}

/** On any drain failure leave the current origin/server intact and poison this loaded fixture. */
internal fun finishOfflineWriteFixture02(guard: SnapshotWiringCleanupGuard, cleanup: () -> Unit) {
    guard.requireHealthy()
    val drained = runCatching {
        val parents = listOf(ApiClient to "bg", OfflineTripSnapshots to "jobs").map { (owner, name) ->
            val scope = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner) as CoroutineScope
            requireNotNull(scope.coroutineContext[Job])
        }
        runBlocking {
            withTimeout(5_000) {
                while (true) {
                    val children = parents.flatMap { it.children.toList() }
                    if (children.isEmpty()) break
                    children.forEach { it.join() }
                }
            }
        }
    }.exceptionOrNull()
    if (drained != null) poisonOfflinePassportFixture03(guard, drained)
    guard.runCleanup(true, cleanup)
}

/** Narrow participant transport for the four passport-precondition fixtures; grants only via HTTP. */
internal class OfflinePassportFixture03(private val context: android.content.Context) {
    private var preparedClock = false
    private var adjustedBoot = false
    private var originalBoot: String? = null
    private var authBacking = emptyList<Pair<String, Any?>>()
    private var observedSnapshotDisk: Any? = null
    private val values = java.util.concurrent.ConcurrentHashMap<Pair<Int, Int>, TripPass>()
    private val reads = java.util.concurrent.ConcurrentLinkedQueue<String>()

    fun prepareClock() {
        if (preparedClock) return
        val resolver = context.contentResolver
        originalBoot = android.provider.Settings.Global.getString(resolver, android.provider.Settings.Global.BOOT_COUNT)
        if (originalBoot == null) {
            check(android.provider.Settings.Global.putInt(resolver, android.provider.Settings.Global.BOOT_COUNT, 41))
            adjustedBoot = true
        }
        check(android.provider.Settings.Global.getInt(resolver, android.provider.Settings.Global.BOOT_COUNT) >= 0)
        preparedClock = true
    }

    fun captureAuthBacking() {
        authBacking = listOf("prefs", "plainAuthPrefs", "secureAuthPrefs", "appCtx", "authStoreSource").map { name ->
            name to apiField(name).also { if (name != "secureAuthPrefs") requireNotNull(it) }
        }
        assertOriginalBacking()
    }

    private fun apiField(name: String): Any? = ApiClient::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(ApiClient)

    fun assertOriginalBacking() {
        check(authBacking.isNotEmpty())
        authBacking.forEach { (name, value) -> org.junit.Assert.assertSame(name, value, apiField(name)) }
        val prefs = apiField("prefs") as android.content.SharedPreferences
        org.junit.Assert.assertEquals(ApiClient.currentToken(), prefs.getString("token", null))
        org.junit.Assert.assertSame(context.applicationContext, apiField("appCtx"))
        org.junit.Assert.assertNull(OfflineTripSnapshots::class.java.getDeclaredField("suppliedClock")
            .apply { isAccessible = true }.get(OfflineTripSnapshots))
        org.junit.Assert.assertSame(context.applicationContext, OfflineTripSnapshots::class.java.getDeclaredField("app")
            .apply { isAccessible = true }.get(OfflineTripSnapshots))
        val repository = OfflineTripSnapshots::class.java.getDeclaredField("repository")
            .apply { isAccessible = true }.get(OfflineTripSnapshots)
        if (repository != null) {
            val disk = repository.javaClass.getDeclaredField("disk").apply { isAccessible = true }.get(repository)
            org.junit.Assert.assertTrue("Only the default production disk may be selected", disk is SnapshotPreferencesDisk)
            if (observedSnapshotDisk == null) observedSnapshotDisk = disk
            org.junit.Assert.assertSame(observedSnapshotDisk, disk)
        }
    }

    fun response(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse? {
        val match = Regex("^/bookings/([1-9][0-9]*)/(details|boarding-code)$")
            .matchEntire(request.path.orEmpty()) ?: return null
        if (request.method != "GET") return okhttp3.mockwebserver.MockResponse().setResponseCode(405)
        val id = match.groupValues[1].toInt()
        val key = values.keys.firstOrNull { (user, booking) ->
            booking == id && request.getHeader("Authorization") == "Bearer ${TripPassAuthorityFixture.jwt(user)}"
        } ?: return okhttp3.mockwebserver.MockResponse().setResponseCode(403).setBody("{}")
        val pass = requireNotNull(values[key])
        reads.add("GET ${request.path}")
        val body = if (match.groupValues[2] == "details") pass.toJson()
            .put("ride_id", id + 1000).put("role", "passenger").put("status", "confirmed")
            .put("contact_unlocked", true).put("pay_method", pass.payMethod ?: "negotiate")
        else JSONObject().put("code", pass.boardingCode)
        return okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "application/json").setBody(body.toString())
    }

    fun prepare(pass: TripPass, user: Int): TripPass = runBlocking {
        assertOriginalBacking()
        org.junit.Assert.assertEquals(TripPassAuthorityFixture.jwt(user), ApiClient.currentToken())
        check(pass.boardingCode.isNotBlank())
        values[user to pass.bookingId] = pass
        val before = reads.size
        val details = ApiClient.getBookingDetails(pass.bookingId).getOrThrow()
        org.junit.Assert.assertTrue(OfflineTripSnapshots.privateDetails(details))
        org.junit.Assert.assertEquals(pass.boardingCode, ApiClient.getBoardingCode(pass.bookingId).getOrThrow())
        org.junit.Assert.assertEquals(listOf("GET /bookings/${pass.bookingId}/details",
            "GET /bookings/${pass.bookingId}/boarding-code"), reads.toList().drop(before))
        requireNotNull(OfflineTripSnapshots.privatePass(pass.bookingId)).also {
            org.junit.Assert.assertTrue(OfflineTripSnapshots.matchesPass(it))
            org.junit.Assert.assertEquals(user, OfflineTripSnapshots.currentOwner()?.userId)
            assertOriginalBacking()
        }
    }

    /** Invoke only after bounded jobs drain and owned-server shutdown. */
    fun restoreClock() {
        if (!adjustedBoot) return
        check(android.provider.Settings.Global.putString(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, originalBoot))
        org.junit.Assert.assertEquals(originalBoot, android.provider.Settings.Global.getString(
            context.contentResolver, android.provider.Settings.Global.BOOT_COUNT))
    }
}

/** Raw selected preference value only: no load/open/recovery/authority grant. Not a cold-process read. */
internal fun offlinePassportRaw03(bookingId: Int): String? = synchronized(TripPassStore) {
    val prefs = TripPassStore::class.java.getDeclaredField("prefs").apply { isAccessible = true }
        .get(TripPassStore) as? android.content.SharedPreferences
    prefs?.getString("pass_" + bookingId, null)
}

/** A cleanup exception permanently blocks this loaded class and emits the root runner's abort marker. */
internal fun poisonOfflinePassportFixture03(guard: SnapshotWiringCleanupGuard, failure: Throwable): Nothing {
    if (failure is AssertionError && failure.message == SnapshotWiringCleanupGuard.POISON_MARKER) throw failure
    try {
        guard.runCleanup(false) {}
    } catch (blocked: AssertionError) {
        blocked.addSuppressed(failure)
        throw blocked
    }
    throw failure
}
