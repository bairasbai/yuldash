package com.yuldash.app

import android.content.Context
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.MemoryDiskPreferences
import com.yuldash.app.data.OfflineTripSnapshots
import com.yuldash.app.data.SnapshotWiringCleanupGuard
import com.yuldash.app.data.TripPass
import com.yuldash.app.data.TripPassAuthorityFixture
import com.yuldash.app.data.TripPassStore
import com.yuldash.app.data.TripStateDto
import com.yuldash.app.data.finishOfflineWriteFixture02
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*

/** Same positive HTTP preparation as the original fixture; never saves or grants directly. */
internal fun prepareCancelledPass09(
    plain: MemoryDiskPreferences,
    secure: MemoryDiskPreferences,
    preparationRequests: List<String>,
    setDetails: (String?) -> Unit,
): TripPass {
    val draft = TripPass.fromJson(JSONObject().put("booking_id", 42).put("driver_name", "Ринат")
        .put("driver_car", "серая Лада Веста").put("driver_plate", "М555ОР102")
        .put("driver_phone", "+79990001122").put("boarding_code", "4821"))
    val before = preparationRequests.size
    assertFalse(plain.restarted().contains("pass_42")); assertFalse(secure.restarted().contains("pass_42"))
    setDetails(draft.toJson().put("ride_id", 7).put("role", "passenger").put("status", "confirmed")
        .put("contact_unlocked", true).put("pay_method", "negotiate").toString())
    try {
        val actual = runBlocking {
            assertTrue(OfflineTripSnapshots.privateDetails(ApiClient.getBookingDetails(42).getOrThrow()))
            assertEquals("4821", ApiClient.getBoardingCode(42).getOrThrow())
            requireNotNull(OfflineTripSnapshots.privatePass(42))
        }
        assertEquals(listOf("GET /bookings/42/details", "GET /bookings/42/boarding-code"), preparationRequests.drop(before))
        assertEquals(11, OfflineTripSnapshots.currentOwner()?.userId); assertTrue(OfflineTripSnapshots.matchesPass(actual))
        assertFalse(plain.restarted().contains("pass_42")); assertFalse(secure.restarted().contains("pass_42"))
        return actual
    } finally { setDetails(null) }
}

internal fun cancelledEntryRole09() =
    """{"role":"passenger","status":"confirmed","driver_phase":"","peer_id":9}"""

/** Before original flags/mount only: same-user new login, durable candidate, real ROLE live only. */
internal fun prepareCancelledDetailsFirstEntry09(
    context: Context,
    plain: MemoryDiskPreferences,
    secure: MemoryDiskPreferences,
    preparationRequests: List<String>,
    guard: SnapshotWiringCleanupGuard,
    prepare: () -> TripPass,
    save: (TripPass) -> Unit,
    setRolePreparation: (Boolean) -> Unit,
) {
    guard.requireHealthy()
    assertCancelledUnitAuth09()
    val pass = prepare()
    save(pass)
    val candidate = requireNotNull(secure.restarted().getString("pass_42", null))
    assertTrue(candidate.isNotBlank())
    assertCancelledCandidate09(plain, secure, candidate)
    finishOfflineWriteFixture02(guard) {}
    val previous = requireNotNull(OfflineTripSnapshots.currentOwner())
    val previousGeneration = ApiClient.queueSessionGeneration()
    assertTrue(previous.valid && previous.userId == 11)
    assertTrue(previous.generation == previousGeneration)
    assertTrue(previous.origin == ApiClient.apiBase())
    assertTrue(ApiClient.isCurrentSession(previousGeneration))
    assertTrue(OfflineTripSnapshots.cacheEnabled(previous))
    ApiClient.saveToken(TripPassAuthorityFixture.jwt(11))
    assertCancelledUnitAuth09()
    val current = requireNotNull(OfflineTripSnapshots.currentOwner())
    val generation = ApiClient.queueSessionGeneration()
    assertTrue(generation == previousGeneration + 1)
    assertTrue(current.valid && current.generation == generation)
    assertTrue(current.userId == previous.userId && current.origin == previous.origin)
    assertTrue(current.scope != previous.scope)
    assertFalse(OfflineTripSnapshots.current(previous))
    assertFalse(ApiClient.isCurrentSession(previousGeneration))
    assertTrue(ApiClient.isCurrentSession(generation))
    assertTrue(OfflineTripSnapshots.cacheEnabled(current))
    assertCancelledCandidate09(plain, secure, candidate)
    assertCancelledPrivateAbsent09(context)
    assertFalse(OfflineTripSnapshots.allowsLive(42))
    assertNull(OfflineTripSnapshots.terminal(42))
    val before = preparationRequests.size
    setRolePreparation(true)
    try {
        val live: TripStateDto = runBlocking { ApiClient.getTripState(42).getOrThrow() }
        assertTrue(live == TripStateDto("passenger", "confirmed", "", peerId = 9))
    } finally {
        setRolePreparation(false)
    }
    assertEquals(listOf("GET /bookings/42/role"), preparationRequests.drop(before))
    assertTrue(OfflineTripSnapshots.current(current))
    assertTrue(ApiClient.isCurrentSession(generation))
    assertTrue(OfflineTripSnapshots.allowsLive(42))
    assertNull(OfflineTripSnapshots.terminal(42))
    assertCancelledPrivateAbsent09(context)
    assertCancelledCandidate09(plain, secure, candidate)
}

/** Fixed null-auth unit seam provenance only; reads no value into output and never mutates it. */
private fun assertCancelledUnitAuth09() {
    listOf("prefs", "plainAuthPrefs", "secureAuthPrefs", "authStoreSource", "appCtx", "refreshToken").forEach { name ->
        val field = ApiClient::class.java.getDeclaredField(name).apply { isAccessible = true }
        assertTrue(field.get(ApiClient) == null)
    }
    assertTrue(ApiClient.currentToken() == TripPassAuthorityFixture.jwt(11))
}

private fun assertCancelledPrivateAbsent09(context: Context) {
    assertNull(OfflineTripSnapshots.projection(42))
    assertNull(OfflineTripSnapshots.privatePass(42))
    assertNull(OfflineTripSnapshots.currentBoardingCode(42))
    assertNull(TripPassStore.load(context, 42))
}

private fun assertCancelledCandidate09(plain: MemoryDiskPreferences, secure: MemoryDiskPreferences, candidate: String) {
    assertFalse(plain.restarted().contains("pass_42"))
    assertTrue(candidate == secure.restarted().getString("pass_42", null))
}
