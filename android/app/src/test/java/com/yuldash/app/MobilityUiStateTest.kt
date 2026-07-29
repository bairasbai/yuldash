package com.yuldash.app

import org.junit.Assert.assertEquals
import org.junit.Test

class MobilityUiStateTest {

    @Test
    fun taxiProgressCoversEveryTripPhase() {
        assertEquals(0, taxiProgressIndex("created"))
        assertEquals(0, taxiProgressIndex("searching"))
        assertEquals(0, taxiProgressIndex("offered"))
        assertEquals(0, taxiProgressIndex("accepted"))
        assertEquals(1, taxiProgressIndex("arriving"))
        assertEquals(2, taxiProgressIndex("onboard"))
        assertEquals(3, taxiProgressIndex("done"))
    }

    @Test
    fun taxiProgressFallsBackSafelyForTerminalAndUnknownStates() {
        assertEquals(0, taxiProgressIndex("cancelled"))
        assertEquals(0, taxiProgressIndex("expired"))
        assertEquals(0, taxiProgressIndex("unexpected"))
    }

    @Test
    fun courierProgressCoversDeliveryAndReturnPaths() {
        assertEquals(0, courierProgressIndex("accepted"))
        assertEquals(1, courierProgressIndex("in_transit"))
        assertEquals(2, courierProgressIndex("delivered"))
        assertEquals(1, courierProgressIndex("returning"))
        assertEquals(2, courierProgressIndex("returned"))
    }

    @Test
    fun courierProgressFallsBackSafelyForNonActiveStates() {
        assertEquals(0, courierProgressIndex("created"))
        assertEquals(0, courierProgressIndex("cancelled"))
        assertEquals(0, courierProgressIndex("unexpected"))
    }


    @Test
    fun activatedScheduledOrderRemainsReachableAfterBackgroundWorkerRuns() {
        assertEquals(true, shouldShowActivatedScheduled("searching", "2026-07-30T06:00:00Z", null))
        assertEquals(true, shouldShowActivatedScheduled("offered", "2026-07-30T06:00:00Z", null))
        assertEquals(true, shouldShowActivatedScheduled("accepted", "2026-07-30T06:00:00Z", null))
        assertEquals(true, shouldShowActivatedScheduled("arriving", "2026-07-30T06:00:00Z", null))
        assertEquals(true, shouldShowActivatedScheduled("onboard", "2026-07-30T06:00:00Z", null))
    }

    @Test
    fun scheduledAndFinishedOrdersAreNotShownAsActive() {
        assertEquals(false, shouldShowActivatedScheduled("scheduled", "2026-07-30T06:00:00Z", null))
        assertEquals(false, shouldShowActivatedScheduled("done", "2026-07-30T06:00:00Z", null))
        assertEquals(false, shouldShowActivatedScheduled("cancelled", "2026-07-30T06:00:00Z", null))
        assertEquals(false, shouldShowActivatedScheduled("searching", null, null))
    }

    @Test
    fun waitingQueueFromScheduledOrderStaysReachable() {
        assertEquals(
            true,
            shouldShowActivatedScheduled(
                status = "expired",
                scheduledAt = "2026-07-30T06:00:00Z",
                waitUntil = "2026-07-30T06:20:00Z",
            ),
        )
    }
}
