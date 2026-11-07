package com.yuldash.app.data

import android.app.Application
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
internal class ColdTripPassportWiringTest : SnapshotWiringFixture() {
    private var passPlain = MemoryDiskPreferences()
    private var passSecure = MemoryDiskPreferences()

    @Before fun installColdPassStores() {
        TripPassStore.initStores(passPlain, passSecure)
    }

    @After fun drainColdBackgroundJobs() = runBlocking {
        val roots = listOf(ApiClient to "bg", OfflineTripSnapshots to "jobs").map { (owner, name) ->
            val scope = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner) as kotlinx.coroutines.CoroutineScope
            requireNotNull(scope.coroutineContext[kotlinx.coroutines.Job])
        }
        withTimeout(5000) {
            while (true) {
                val children = roots.flatMap { it.children.toList() }
                if (children.isEmpty()) break
                children.forEach { it.join() }
            }
        }
    }

    private suspend fun saveCold(): TripPass {
        OfflineTripSnapshots.init(context)
        val details = ApiClient.getBookingDetails(11).getOrThrow()
        assertTrue(OfflineTripSnapshots.privateDetails(details))
        assertEquals("4821", ApiClient.getBoardingCode(11).getOrThrow())
        val pass = requireNotNull(OfflineTripSnapshots.privatePass(11))
        assertEquals(SnapshotPersistence.COMMITTED, OfflineTripSnapshots.saveColdPassport(context, pass,
            expectedGeneration = ApiClient.queueSessionGeneration()))
        return pass
    }

    private fun reopenCold(secureAvailable: Boolean = true) {
        val previous = owner()
        reopenAuthority()
        passPlain = passPlain.restarted()
        passSecure = passSecure.restarted()
        TripPassStore.initStores(passPlain, if (secureAvailable) passSecure else null)
        OfflineTripSnapshots.init(context)
        assertEquals(previous, owner())
    }

    @Test fun newGateAndProcessRestoresOfflineWithoutLivePrivateOrNetwork() = runBlocking {
        val saved = saveCold()
        reopenCold()
        server.shutdown()
        val calls = requests.size
        val restored = requireNotNull(OfflineTripSnapshots.loadColdPassport(owner(), 11))
        assertEquals(saved.toJson().toString(), restored.pass.toJson().toString())
        assertTrue(OfflineTripSnapshots.canDrawCold(restored))
        assertFalse(OfflineTripSnapshots.allowsLive(11))
        assertNull(OfflineTripSnapshots.privatePass(11))
        assertNull(TripPassStore.load(context, 11))
        assertNull(OfflineTripSnapshots.load(owner(), 11))
        assertEquals("Cold restore must not issue GET, CODE, status or POST requests", calls, requests.size)
        assertTrue(requests.all { it.method == "GET" })
    }

    @Test fun unchangedRoleKeepsColdGrantAfterReopen() = runBlocking {
        handler = { request ->
            if (request.path?.endsWith("/role") == true) {
                MockResponse().setBody(roleJson().put("driver_phase", "").toString())
            } else defaultResponse(request)
        }
        saveCold()
        assertTrue(ApiClient.getTripStateForNavigation(11).result.isSuccess)
        val raw = secure.restarted().getString(owner().namespace, null)
        assertTrue(JSONObject(requireNotNull(raw)).getJSONObject("records").getJSONObject("11").has("cold_grant"))
        reopenCold()
        assertNotNull(OfflineTripSnapshots.loadColdPassport(owner(), 11))
    }

    @Test fun cancellationAfterAcceptedDetailsLeavesDurableDenial() = runBlocking {
        saveCold()
        val previous = requireNotNull(OfflineTripSnapshots.projection(11)).proof.request.ticket.sequence
        val field = OfflineTripSnapshots::class.java.getDeclaredField("diskAccess").apply { isAccessible = true }
        val mutex = field.get(OfflineTripSnapshots) as kotlinx.coroutines.sync.Mutex
        mutex.lock()
        var accepted = previous
        val request = async { ApiClient.getBookingDetails(11) }
        try {
            withTimeout(5000) {
                while (requireNotNull(OfflineTripSnapshots.projection(11)).proof.request.ticket.sequence == previous) {
                    delay(5)
                }
            }
            accepted = requireNotNull(OfflineTripSnapshots.projection(11)).proof.request.ticket.sequence
            request.cancel()
        } finally {
            mutex.unlock()
        }
        runCatching { request.await() }
        assertTrue(accepted > previous)
        val raw = secure.restarted().getString(owner().namespace, null)
        val record = JSONObject(requireNotNull(raw)).getJSONObject("records").getJSONObject("11")
        assertFalse(record.has("cold_grant"))
        assertNotNull(SnapshotPreferencesDisk(meta, secure).barrier(owner().barrier("cold-booking", 11)))
        reopenCold()
        assertNull(OfflineTripSnapshots.loadColdPassport(owner(), 11))
    }

    private fun diskAccessMutex(): kotlinx.coroutines.sync.Mutex {
        val field = OfflineTripSnapshots::class.java.getDeclaredField("diskAccess").apply { isAccessible = true }
        return field.get(OfflineTripSnapshots) as kotlinx.coroutines.sync.Mutex
    }

    private suspend fun expectRevocationBlocksNewRestore(
        originalOwner: SnapshotOwner, operation: kotlinx.coroutines.Deferred<*>, mutex: kotlinx.coroutines.sync.Mutex, reopen: Boolean,
    ) {
        try {
            withTimeout(5000) {
                while (OfflineTripSnapshots.gate.beginColdRestore(originalOwner, 11) != null) delay(5)
            }
            assertNull(OfflineTripSnapshots.gate.beginColdRestore(originalOwner, 11))
        } finally {
            mutex.unlock()
        }
        runCatching { operation.await() }
        val raw = SnapshotPreferencesDisk(meta, secure).read(originalOwner.namespace)
        val record = raw?.let { JSONObject(it).getJSONObject("records").optJSONObject("11") }
        assertTrue(record == null || !record.has("cold_grant"))
        if (reopen) {
            reopenCold()
            assertNull(OfflineTripSnapshots.loadColdPassport(originalOwner, 11))
        } else {
            assertNull(OfflineTripSnapshots.gate.beginColdRestore(originalOwner, 11))
        }
    }

    @Test fun pendingDetailsBlockNewRestoreBeforeDurableWrite() = runBlocking {
        saveCold()
        handler = { request ->
            if (request.path?.endsWith("/details") == true) {
                MockResponse().setBody(detailsJson(status = "pending").toString())
            } else defaultResponse(request)
        }
        val originalOwner = owner()
        val mutex = diskAccessMutex().apply { lock() }
        val operation = async { ApiClient.getBookingDetails(11) }
        expectRevocationBlocksNewRestore(originalOwner, operation, mutex, reopen = true)
    }

    @Test fun unauthorizedBlockNewRestoreBeforeDurableWrite() = runBlocking {
        saveCold()
        handler = { MockResponse().setResponseCode(401).setBody("{}") }
        val originalOwner = owner()
        val mutex = diskAccessMutex().apply { lock() }
        val operation = async { ApiClient.getBookingDetails(11) }
        expectRevocationBlocksNewRestore(originalOwner, operation, mutex, reopen = false)
    }

    @Test fun privateMutationBlockNewRestoreBeforeDurableWrite() = runBlocking {
        handler = { request ->
            if (request.path?.endsWith("/confirm") == true) MockResponse().setBody("{}") else defaultResponse(request)
        }
        saveCold()
        val originalOwner = owner()
        val mutex = diskAccessMutex().apply { lock() }
        val operation = async { ApiClient.confirmBooking(11) }
        expectRevocationBlocksNewRestore(originalOwner, operation, mutex, reopen = true)
    }

    @Test fun privateMutationBetweenObserveAndGrantLastCannotClearDenial() = runBlocking {
        OfflineTripSnapshots.init(context)
        val details = ApiClient.getBookingDetails(11).getOrThrow()
        assertTrue(OfflineTripSnapshots.privateDetails(details))
        assertEquals("4821", ApiClient.getBoardingCode(11).getOrThrow())
        val projection = requireNotNull(OfflineTripSnapshots.projection(11))
        val permit = requireNotNull(projection.permit)
        val state = requireNotNull(permit.input)
        val pass = requireNotNull(OfflineTripSnapshots.privatePass(11))
        val receipt = requireNotNull(TripPassStore.commitSecure(context, pass, projection.fingerprint,
            retryMigration = false, expectedGeneration = ApiClient.queueSessionGeneration()))
        val repository = OfflineTripSnapshots::class.java.getDeclaredField("repository").apply { isAccessible = true }
            .get(OfflineTripSnapshots) as OfflineTripSnapshotRepository
        val route = SnapshotRoute(details.fromCity, details.toCity, details.departAt,
            details.pickupStop, details.dropoffStop, details.rideHasStops)
        assertEquals(SnapshotPersistence.COMMITTED, repository.beginColdSave(permit, state, details.rideId, route,
            "blocked:" + snapshotHash("pending-grant-race")))
        assertEquals(SnapshotPersistence.COMMITTED, repository.observe(permit.ticket, 11,
            state, details.rideId, route, permit = permit, coldSave = true))
        handler = { request ->
            if (request.path?.endsWith("/confirm") == true) MockResponse().setBody("{}") else defaultResponse(request)
        }
        val originalOwner = owner()
        val mutex = diskAccessMutex().apply { lock() }
        val operation = async { ApiClient.confirmBooking(11) }
        try {
            withTimeout(5000) {
                while (OfflineTripSnapshots.projection(11) != null) delay(5)
            }
            assertEquals(SnapshotPersistence.STALE, repository.commitColdGrant(permit,
                projection.proof.request.ticket, receipt, state, details.rideId, route))
            assertNull(OfflineTripSnapshots.gate.beginColdRestore(originalOwner, 11))
        } finally {
            mutex.unlock()
        }
        assertTrue(operation.await().isSuccess)
        assertNotNull(SnapshotPreferencesDisk(meta, secure).barrier(originalOwner.barrier("cold-booking", 11)))
        reopenCold()
        assertNull(OfflineTripSnapshots.loadColdPassport(originalOwner, 11))
    }

    @Test fun networkFailureKeepsColdGrantForFallbackWithoutLiveAccess() = runBlocking {
        saveCold()
        reopenCold()
        server.shutdown()
        val read = ApiClient.getTripStateForNavigation(11)
        assertTrue(read.result.isFailure)
        assertTrue(read.fallbackAllowed)
        val restored = requireNotNull(OfflineTripSnapshots.loadColdPassport(owner(), 11))
        assertTrue(OfflineTripSnapshots.canDrawCold(restored))
        assertFalse(OfflineTripSnapshots.allowsLive(11))
        assertNull(OfflineTripSnapshots.privatePass(11))
    }

    @Test fun cancelledContinuationAndDrawExpiryLeaveDurableDeny() = runBlocking {
        OfflineTripSnapshots.init(context)
        ApiClient.getBookingDetails(11).getOrThrow()
        ApiClient.getBoardingCode(11).getOrThrow()
        val cancelled = requireNotNull(OfflineTripSnapshots.privatePass(11))
        var checks = 0
        assertEquals(SnapshotPersistence.STALE, OfflineTripSnapshots.saveColdPassport(context, cancelled,
            shouldContinue = { ++checks == 1 }))
        reopenCold()
        assertNull(OfflineTripSnapshots.loadColdPassport(owner(), 11))

        saveCold()
        reopenCold()
        val restored = requireNotNull(OfflineTripSnapshots.loadColdPassport(owner(), 11))
        clock = SnapshotClock(3, 1000 + OfflineTripSnapshotPolicy.MAX_AGE_MS,
            1000 + OfflineTripSnapshotPolicy.MAX_AGE_MS, 1000 + OfflineTripSnapshotPolicy.MAX_AGE_MS)
        assertFalse(OfflineTripSnapshots.canDrawCold(restored))
        kotlinx.coroutines.withTimeout(5000) {
            while (SnapshotPreferencesDisk(meta, secure).barrier(owner().barrier("cold-booking", 11)) == null) {
                kotlinx.coroutines.delay(10)
            }
        }
        clock = SnapshotClock(3, 1000, 1000, 1000)
        assertFalse(OfflineTripSnapshots.canDrawCold(restored))
        reopenCold()
        assertNull(OfflineTripSnapshots.loadColdPassport(owner(), 11))
    }

    @Test fun warmBytesWithoutGrantRemainProcessOnly() = runBlocking {
        warm()
        reopenCold()
        assertNotNull(TripPassAuthorityFixture.durable(passSecure, 11))
        assertNull(OfflineTripSnapshots.loadColdPassport(owner(), 11))
    }

    @Test fun missingSecureStoreCannotUsePlaintextFallback() = runBlocking {
        OfflineTripSnapshots.init(context)
        ApiClient.getBookingDetails(11).getOrThrow()
        ApiClient.getBoardingCode(11).getOrThrow()
        val pass = requireNotNull(OfflineTripSnapshots.privatePass(11))
        passPlain.edit().putString("pass_11", pass.toJson().toString()).commit()
        TripPassStore.initStores(passPlain, null)
        assertEquals(SnapshotPersistence.DURABLY_BLOCKED, OfflineTripSnapshots.saveColdPassport(context, pass))
        reopenCold(secureAvailable = false)
        assertTrue(passPlain.contains("pass_11"))
        assertNull(OfflineTripSnapshots.loadColdPassport(owner(), 11))
    }

    @Test fun failedSecureCommitNeverWritesGrantOrClaimsCommitted() = runBlocking {
        OfflineTripSnapshots.init(context)
        ApiClient.getBookingDetails(11).getOrThrow()
        ApiClient.getBoardingCode(11).getOrThrow()
        val pass = requireNotNull(OfflineTripSnapshots.privatePass(11))
        passSecure.failWriteOf = "pass_11"
        try {
            assertEquals(SnapshotPersistence.DURABLY_BLOCKED, OfflineTripSnapshots.saveColdPassport(context, pass))
            assertNull(TripPassAuthorityFixture.durable(passSecure, 11))
            val raw = secure.restarted().getString(owner().namespace, null)
            assertTrue(raw == null || !raw.contains("cold_grant"))
        } finally {
            passSecure.failWriteOf = null
        }
        reopenCold()
        assertNull(OfflineTripSnapshots.loadColdPassport(owner(), 11))
    }

    @Test fun newDetailsBlankPrivatePostAndRemovalRevokeDurably() = runBlocking {
        saveCold()
        ApiClient.getBookingDetails(11).getOrThrow()
        reopenCold()
        assertNull(OfflineTripSnapshots.loadColdPassport(owner(), 11))

        saveCold()
        handler = { request ->
            if (request.path?.endsWith("/boarding-code") == true) {
                MockResponse().setBody("{\"code\":\"\"}")
            } else defaultResponse(request)
        }
        assertTrue(ApiClient.getBoardingCode(11).isFailure)
        assertEquals(SnapshotPersistence.COMMITTED, OfflineTripSnapshots.persistence.value)
        reopenCold()
        assertNull(OfflineTripSnapshots.loadColdPassport(owner(), 11))

        handler = ::defaultResponse
        saveCold()
        handler = { request ->
            if (request.path?.endsWith("/confirm") == true) MockResponse().setBody("{}")
            else defaultResponse(request)
        }
        assertTrue(ApiClient.confirmBooking(11).isSuccess)
        reopenCold()
        assertNull(OfflineTripSnapshots.loadColdPassport(owner(), 11))

        handler = ::defaultResponse
        saveCold()
        assertEquals(TripPassStore.RemovalResult.CLEARED,
            TripPassStore.requestRemoval(context, 11, expectedGeneration = ApiClient.queueSessionGeneration()))
        reopenCold()
        assertNull(OfflineTripSnapshots.loadColdPassport(owner(), 11))
        assertNull(TripPassAuthorityFixture.durable(passSecure, 11))
    }

    @Test fun driverCodeNeverCreatesColdGrant() = runBlocking {
        handler = { request ->
            if (request.path?.endsWith("/details") == true) {
                MockResponse().setBody(detailsJson(role = "driver").toString())
            } else defaultResponse(request)
        }
        OfflineTripSnapshots.init(context)
        val details = ApiClient.getBookingDetails(11).getOrThrow()
        val code = ApiClient.getBoardingCode(11).getOrThrow()
        val forged = tripPassFromDetails(details).copy(boardingCode = code)
        assertEquals(SnapshotPersistence.STALE, OfflineTripSnapshots.saveColdPassport(context, forged))
        assertNull(TripPassAuthorityFixture.durable(passSecure, 11))
    }

    @Test fun corruptSecureCandidateCannotRestoreGrant() = runBlocking {
        val saved = saveCold()
        passSecure.edit().putString("pass_11", saved.toJson().put("unexpected", true).toString()).commit()
        reopenCold()
        assertNull(OfflineTripSnapshots.loadColdPassport(owner(), 11))
    }

    @Test fun failedColdRevokeCannotClaimFullRemoval() = runBlocking {
        val saved = saveCold()
        metaFault.reject = { changes -> changes[SNAPSHOT_BARRIERS_KEY]?.contains("blocked") == true }
        secureFault.reject = { changes -> changes.containsKey(owner().namespace) }
        metaFault.armed = true; secureFault.armed = true
        assertEquals(TripPassStore.RemovalResult.NOT_SAVED,
            TripPassStore.requestRemoval(context, 11, expectedGeneration = ApiClient.queueSessionGeneration()))
        assertNotNull(TripPassAuthorityFixture.durable(passSecure, 11))
        metaFault.armed = false; secureFault.armed = false
        reopenCold()
        assertNotNull(OfflineTripSnapshots.loadColdPassport(owner(), 11))
        assertEquals(TripPassStore.RemovalResult.CLEARED,
            TripPassStore.requestRemoval(context, 11, expectedGeneration = ApiClient.queueSessionGeneration()))
    }
}
