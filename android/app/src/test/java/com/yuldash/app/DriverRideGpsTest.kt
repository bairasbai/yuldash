package com.yuldash.app

import com.yuldash.app.data.DriverBookingDto
import org.junit.Assert.*
import org.junit.Test

class DriverRideGpsTest {
    private fun row(id: Int, status: String = "onboard", ride: Int = 8, stop: String? = null, wait: Int = 0) =
        DriverBookingDto(id, "", null, "", status, rideId = ride, pickupStop = stop, noShowWaitSec = wait)
    @Test fun choosesSameRideBoardedThenStopThenUnexpiredStart() {
        val rows = listOf(row(1, ride = 9), row(42), row(43, "confirmed", stop = "Бурибай"), row(44, "confirmed", wait = 120))
        assertEquals(42, pickRideCarrier(rows, 8, emptySet()).carrier)
        assertEquals(43, pickRideCarrier(rows, 8, setOf(42)).carrier)
        val final = pickRideCarrier(rows, 8, setOf(42, 43))
        assertEquals(44, final.carrier); assertEquals(120, final.carrierTtlSec)
        assertNull(pickRideCarrier(rows, 8, setOf(42, 43, 44)).carrier)
    }
    @Test fun expiredStartUnknownRideAndTerminalNeverCarry() {
        assertNull(pickRideCarrier(listOf(row(42, "confirmed", wait = 60), row(43, "done")), 8, emptySet()).carrier)
        assertNull(pickRideCarrier(listOf(row(42)), 0, emptySet()).carrier)
    }
}
