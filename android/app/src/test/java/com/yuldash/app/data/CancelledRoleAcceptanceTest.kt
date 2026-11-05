package com.yuldash.app.data

import android.app.Application
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual HTTP acceptance. UI assertions stay in the unchanged CancelledInTransitTest. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
internal class CancelledRoleAcceptanceTest : SnapshotWiringFixture() {
    private suspend fun readRole(
        status: String = "cancelled", code: Int = 200, inTransit: Boolean = status == "cancelled",
    ): SnapshotReadResult<TripStateDto> {
        handler = { request ->
            if (request.path == "/bookings/11/role") {
                val body = roleJson(status = status).put("peer_id", 9).put("problem_report_sec", 3600)
                    .put("cancelled_in_transit", inTransit)
                MockResponse().setResponseCode(code).setBody(body.toString())
            } else defaultResponse(request)
        }
        return ApiClient.getTripStateForNavigation(11)
    }

    private fun assertPrivateClosed() {
        assertFalse(OfflineTripSnapshots.allowsLive(11))
        assertNull(OfflineTripSnapshots.privatePass(11))
        assertNull(OfflineTripSnapshots.currentBoardingCode(11))
        assertNull(TripPassStore.load(context, 11))
    }

    private suspend fun sameTerminalRead(): SnapshotReadResult<TripStateDto> {
        val first = readRole()
        val firstValue = first.result.getOrThrow()
        val firstAccepted = requireNotNull(first.acceptedRole)
        assertEquals("cancelled", requireNotNull(OfflineTripSnapshots.terminal(11)).status)
        assertFalse("First close changed the request epoch", firstAccepted.matches(11, owner(), firstValue))
        val read = readRole(inTransit = false)
        val value = read.result.getOrThrow()
        assertFalse(value.cancelledInTransit)
        assertTrue(requireNotNull(read.acceptedRole).matches(11, owner(), value))
        assertPrivateClosed()
        return read
    }

    @Test fun ownAuthorizedSameTerminal200HasExactSameEpochAcceptance() = runBlocking {
        val read = sameTerminalRead()
        val value = read.result.getOrThrow()
        assertEquals(9, value.peerId)
        assertEquals(3600, value.problemReportSec)
        assertFalse(read.fallbackAllowed)
        assertEquals(2, requests.size)
        assertTrue(requests.all { it.method == "GET" && it.path == "/bookings/11/role" })
        assertTrue(requests.all { it.getHeader("Authorization") == "Bearer " + jwt(7) })
    }

    @Test fun valid201WithOwnEqualTerminalIsTransportSuccessWithoutAcceptance() = runBlocking {
        val previous = sameTerminalRead()
        val terminal = requireNotNull(OfflineTripSnapshots.terminal(11))
        val read = readRole(code = 201, inTransit = false)
        val value = read.result.getOrThrow()
        assertEquals(previous.result.getOrThrow(), value)
        assertNotSame(previous.result.getOrThrow(), value)
        assertNull(read.acceptedRole)
        assertFalse(read.fallbackAllowed)
        assertFalse(requireNotNull(previous.acceptedRole).matches(11, owner(), value))
        assertSame(terminal, OfflineTripSnapshots.terminal(11))
        assertEquals(3, requests.size)
        assertPrivateClosed()
    }

    @Test fun equalNewOwnersTerminalCannotAuthorizePreviousOwnersAcceptedResponse() = runBlocking {
        val old = sameTerminalRead()
        val oldOwner = owner()
        val oldTerminal = requireNotNull(OfflineTripSnapshots.terminal(11))
        ApiClient.saveToken(jwt(8))
        val fresh = sameTerminalRead()
        val freshOwner = owner()
        val freshTerminal = requireNotNull(OfflineTripSnapshots.terminal(11))
        assertEquals(8, freshOwner.userId)
        assertNotEquals(oldOwner, freshOwner)
        assertNotEquals(oldOwner.generation, freshOwner.generation)
        assertEquals(oldTerminal.role, freshTerminal.role)
        assertEquals(oldTerminal.status, freshTerminal.status)
        assertEquals(old.result.getOrThrow(), fresh.result.getOrThrow())
        val accepted = requireNotNull(old.acceptedRole)
        assertFalse(accepted.matches(11, freshOwner, old.result.getOrThrow()))
        assertFalse(accepted.matches(11, oldOwner, old.result.getOrThrow()))
        assertTrue(requireNotNull(fresh.acceptedRole).matches(11, freshOwner, fresh.result.getOrThrow()))
        assertEquals(4, requests.size)
        assertPrivateClosed()
    }

    @Test fun equalDtoOtherBookingAndMissingOwnerCannotReuseExactAcceptance() = runBlocking {
        val read = sameTerminalRead()
        val value = read.result.getOrThrow()
        val accepted = requireNotNull(read.acceptedRole)
        val equalCopy = value.copy()
        assertEquals(value, equalCopy)
        assertNotSame(value, equalCopy)
        assertFalse(accepted.matches(11, owner(), equalCopy))
        assertFalse(accepted.matches(12, owner(), value))
        assertFalse(accepted.matches(11, null, value))
        assertTrue(accepted.matches(11, owner(), value))
    }

    @Test fun laterInvalidationRejectsAcceptedResponseEvenWhileOwnTerminalRemains() = runBlocking {
        val read = sameTerminalRead()
        val terminal = requireNotNull(OfflineTripSnapshots.terminal(11))
        OfflineTripSnapshots.gate.invalidate(requireNotNull(OfflineTripSnapshots.ticket()), 11)
        assertSame(terminal, OfflineTripSnapshots.terminal(11))
        assertFalse(requireNotNull(read.acceptedRole).matches(11, owner(), read.result.getOrThrow()))
        assertPrivateClosed()
    }

    @Test fun actual403RevokesOldAcceptanceInAlreadyClosedContext() = runBlocking {
        val read = sameTerminalRead()
        val currentOwner = owner()
        val denied = readRole(code = 403)
        assertTrue(denied.result.isFailure)
        assertNull(denied.acceptedRole)
        assertEquals(currentOwner, owner())
        assertNull(OfflineTripSnapshots.terminal(11))
        assertFalse(requireNotNull(read.acceptedRole).matches(11, currentOwner, read.result.getOrThrow()))
        assertPrivateClosed()
    }

    @Test fun revokedTerminalRejectsAcceptanceWithoutChangingTheGateEpoch() = runBlocking {
        val read = sameTerminalRead()
        val currentOwner = owner()
        val before = requireNotNull(OfflineTripSnapshots.gate.beginRead(11, SnapshotAuthorityKind.ROLE)).epoch
        OfflineTripSnapshots.revoked(currentOwner, 11)
        assertEquals(before, requireNotNull(OfflineTripSnapshots.gate.beginRead(11, SnapshotAuthorityKind.ROLE)).epoch)
        assertNull(OfflineTripSnapshots.terminal(11))
        assertFalse(requireNotNull(read.acceptedRole).matches(11, currentOwner, read.result.getOrThrow()))
        assertPrivateClosed()
    }

    @Test fun acceptedNonterminalRoleDoesNotAuthorizeCancelledReportOnlyUse() = runBlocking {
        val read = readRole(status = "confirmed")
        val value = read.result.getOrThrow()
        assertNotNull(read.acceptedRole)
        assertEquals("confirmed", value.status)
        assertFalse(requireNotNull(read.acceptedRole).matches(11, owner(), value))
        assertNull(OfflineTripSnapshots.terminal(11))
    }

    @Test fun acceptedSameEpochDoneDoesNotAuthorizeCancelledReportOnlyUse() = runBlocking {
        readRole(status = "done").result.getOrThrow()
        val read = readRole(status = "done")
        val value = read.result.getOrThrow()
        assertNotNull(read.acceptedRole)
        assertEquals("done", requireNotNull(OfflineTripSnapshots.terminal(11)).status)
        assertFalse(requireNotNull(read.acceptedRole).matches(11, owner(), value))
        assertPrivateClosed()
    }

    @Test fun logoutRejectsAcceptanceWithNoCurrentOwner() = runBlocking {
        val read = sameTerminalRead()
        val previousOwner = owner()
        ApiClient.logout()
        assertNull(OfflineTripSnapshots.currentOwner())
        assertFalse(requireNotNull(read.acceptedRole).matches(11, previousOwner, read.result.getOrThrow()))
        assertFalse(requireNotNull(read.acceptedRole).matches(11, null, read.result.getOrThrow()))
    }
}
