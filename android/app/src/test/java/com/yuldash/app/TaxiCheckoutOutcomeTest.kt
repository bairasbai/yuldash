package com.yuldash.app

import com.yuldash.app.data.PayTripResultDto
import org.junit.Assert.assertEquals
import org.junit.Test

class TaxiCheckoutOutcomeTest {
    private fun result(status: String, link: String? = null) =
        taxiCheckoutOutcome(PayTripResultDto(status, "yookassa", 91, link))

    @Test fun paidRequiresExplicitSuccess() {
        for (status in listOf("paid", "already_paid", "succeeded")) {
            assertEquals(TaxiCheckoutOutcome.Paid, result(status))
        }
        assertEquals(TaxiCheckoutOutcome.Pending, result("unknown", "https://example.invalid"))
    }

    @Test fun pendingWithoutLinkAndCanceledAreDifferent() {
        assertEquals(TaxiCheckoutOutcome.Pending, result("pending"))
        assertEquals(TaxiCheckoutOutcome.OpenBank, result("pending", "https://example.invalid"))
        assertEquals(TaxiCheckoutOutcome.Canceled, result("canceled", "https://example.invalid"))
    }

    @Test fun onlyExplicitDisabledAllowsLeavingBankFlow() {
        assertEquals(TaxiCheckoutOutcome.Disabled, taxiCheckoutFailure(false, 503, "taxi_online_pay_off"))
        assertEquals(TaxiCheckoutOutcome.Pending, taxiCheckoutFailure(false, 503, ""))
        assertEquals(TaxiCheckoutOutcome.Pending, taxiCheckoutFailure(false, null, null))
        assertEquals(TaxiCheckoutOutcome.Pending, taxiCheckoutFailure(false, 409, "taxi_checkout_unresolved"))
        assertEquals(TaxiCheckoutOutcome.Pending, taxiCheckoutFailure(true, 503, "taxi_online_pay_off"))
        assertEquals(TaxiCheckoutOutcome.Error, taxiCheckoutFailure(false, 400, ""))
    }
}
