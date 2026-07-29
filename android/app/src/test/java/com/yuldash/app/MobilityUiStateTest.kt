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

    @Test
    fun parcelTerminalStatesStopSenderActions() {
        assertEquals(true, isParcelTerminal("delivered"))
        assertEquals(true, isParcelTerminal("returned"))
        assertEquals(true, isParcelTerminal("canceled"))
        assertEquals(true, isParcelTerminal("cancelled"))
        assertEquals(false, isParcelTerminal("returning"))
        assertEquals(false, isParcelTerminal("in_transit"))
    }

    @Test
    fun senderCancellationMatchesBackendRules() {
        assertEquals(true, canSenderCancelParcel("created", "courier", 0))
        assertEquals(true, canSenderCancelParcel("accepted", "courier", 0))
        assertEquals(true, canSenderCancelParcel("in_transit", "courier", 0))
        assertEquals(false, canSenderCancelParcel("returning", "courier", 0))
        assertEquals(false, canSenderCancelParcel("returned", "courier", 0))
        assertEquals(false, canSenderCancelParcel("in_transit", "buy_bring", 1))
    }

    @Test
    fun courierDeliveryAndReturnActionsNeverOverlap() {
        assertEquals(true, canCourierDeliverParcel("accepted"))
        assertEquals(true, canCourierDeliverParcel("in_transit"))
        assertEquals(false, canCourierDeliverParcel("returning"))
        assertEquals(false, canCourierDeliverParcel("returned"))

        assertEquals(true, canCourierResolveParcelTrouble("accepted"))
        assertEquals(true, canCourierResolveParcelTrouble("in_transit"))
        assertEquals(true, canCourierResolveParcelTrouble("returning"))
        assertEquals(false, canCourierResolveParcelTrouble("returned"))
        assertEquals(false, canCourierResolveParcelTrouble("delivered"))
    }

    @Test
    fun courierIncomeSubtractsCommissionWithoutGoingNegative() {
        assertEquals(50_000, courierNetKop(priceKop = 54_000, commissionKop = 4_000))
        assertEquals(54_000, courierNetKop(priceKop = 54_000, commissionKop = 0))
        assertEquals(0, courierNetKop(priceKop = 3_000, commissionKop = 4_000))
    }

    @Test
    fun taxiMoneyKeepsKopecksAndNeverDisplaysNegativeIncome() {
        assertEquals("0 ₽", formatTaxiKop(0))
        assertEquals("123 ₽", formatTaxiKop(12_300))
        assertEquals("123,45 ₽", formatTaxiKop(12_345))
        assertEquals("0 ₽", formatTaxiKop(-1))
    }

    @Test
    fun parcelDisputeRemainsAvailableAfterReturn() {
        assertEquals(false, canOpenParcelDispute("created"))
        assertEquals(true, canOpenParcelDispute("accepted"))
        assertEquals(true, canOpenParcelDispute("in_transit"))
        assertEquals(true, canOpenParcelDispute("returning"))
        assertEquals(true, canOpenParcelDispute("returned"))
        assertEquals(true, canOpenParcelDispute("delivered"))
    }

}
