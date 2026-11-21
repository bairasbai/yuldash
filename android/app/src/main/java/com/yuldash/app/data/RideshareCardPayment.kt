package com.yuldash.app.data

import org.json.JSONObject

internal enum class RideshareBankStatus(val wire: String) {
    Unavailable("unavailable"),
    NotStarted("not_started"),
    Queued("queued"),
    WaitingBank("waiting_bank"),
    Reconciling("reconciling"),
    Succeeded("succeeded"),
    Canceled("canceled");

    companion object {
        fun parse(value: String): RideshareBankStatus = entries.first { it.wire == value }
    }
}

internal data class RideshareCardPayment(
    val payment: RideshareBankStatus,
    val amountKop: Int?,
    val paidKop: Int,
    val refund: RideshareBankStatus,
    val refundedKop: Int,
    val refundCoverage: String,
    val payout: RideshareBankStatus?,
    val paidOutKop: Int?,
    val payoutRemainingKop: Int?,
) {
    val hasOperation get() = payment != RideshareBankStatus.Unavailable && payment != RideshareBankStatus.NotStarted
    val replacesDirectPayment get() = hasOperation && payment != RideshareBankStatus.Canceled
}

internal const val RIDESHARE_CARD_RECEIPT_METHOD = "rideshare_card"

internal fun parseRideshareCardPayment(value: JSONObject): RideshareCardPayment {
    require(value.getString("currency") == "RUB")
    fun amount(key: String): Int {
        val number = value.get(key)
        require(number is Int || number is Long)
        val kop = (number as Number).toLong()
        require(kop in 0..Int.MAX_VALUE.toLong())
        return kop.toInt()
    }
    fun optionalAmount(key: String): Int? = if (value.isNull(key)) null else amount(key)
    fun status(key: String) = RideshareBankStatus.parse(value.getString(key))
    val coverage = value.getString("refund_coverage")
    require(coverage == "none" || coverage == "partial" || coverage == "full")
    return RideshareCardPayment(
        payment = status("payment_status"),
        amountKop = optionalAmount("amount_kop"),
        paidKop = amount("paid_kop"),
        refund = status("refund_status"),
        refundedKop = amount("refunded_kop"),
        refundCoverage = coverage,
        payout = if (value.isNull("payout_status")) null else status("payout_status"),
        paidOutKop = optionalAmount("paid_out_kop"),
        payoutRemainingKop = optionalAmount("payout_remaining_kop"),
    )
}
