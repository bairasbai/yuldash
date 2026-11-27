package com.yuldash.app

internal data class TaxiReceiptDriverMoney(
    val passengerKop: Int,
    val feeKop: Int,
    val promoCompKop: Int,
    val netKop: Int,
)

internal fun taxiReceiptDriverMoney(
    role: String, grossKop: Int, passengerKop: Int, discountKop: Int,
    feeKop: Int, netKop: Int, promoCompKop: Int?,
): TaxiReceiptDriverMoney? {
    if (role != "driver" || grossKop <= 0) return null
    if (discountKop > 0 && (promoCompKop == null || promoCompKop < 0 ||
            passengerKop < 0 || feeKop < 0 || netKop < 0 ||
            netKop.toLong() != (passengerKop.toLong() - feeKop + promoCompKop).coerceAtLeast(0L))) return null
    return TaxiReceiptDriverMoney(passengerKop, feeKop, promoCompKop ?: 0, netKop)
}
