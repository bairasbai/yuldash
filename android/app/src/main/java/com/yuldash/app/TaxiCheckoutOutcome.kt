package com.yuldash.app

import com.yuldash.app.data.PayTripResultDto

internal enum class TaxiCheckoutOutcome { Paid, Canceled, OpenBank, Pending, Disabled, Error }

internal fun taxiCheckoutOutcome(result: PayTripResultDto): TaxiCheckoutOutcome = when {
    result.isPaid -> TaxiCheckoutOutcome.Paid
    result.status == "canceled" -> TaxiCheckoutOutcome.Canceled
    result.status == "pending" && !result.confirmationUrl.isNullOrBlank() -> TaxiCheckoutOutcome.OpenBank
    else -> TaxiCheckoutOutcome.Pending
}

internal fun taxiCheckoutFailure(check: Boolean, status: Int?, code: String?): TaxiCheckoutOutcome = when {
    !check && status == 503 && code == "taxi_online_pay_off" -> TaxiCheckoutOutcome.Disabled
    check || status == null || status <= 0 || status >= 500 ||
        code == "taxi_checkout_unresolved" -> TaxiCheckoutOutcome.Pending
    else -> TaxiCheckoutOutcome.Error
}
