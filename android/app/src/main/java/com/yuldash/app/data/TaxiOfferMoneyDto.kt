package com.yuldash.app.data

import org.json.JSONObject

/** Сохраняет разницу между отсутствующим числом и явно присланным нулём. */
data class TaxiOfferMoneyDto(
    val grossKop: Int?,
    val netKop: Int?,
    val feeKop: Int?,
    val feePercent: Double?,
)

internal fun JSONObject.toTaxiOfferMoneyDto(): TaxiOfferMoneyDto {
    fun amount(key: String): Int? {
        val value = (opt(key) as? Number)?.toDouble() ?: return null
        return if (value in 0.0..Int.MAX_VALUE.toDouble() && value % 1.0 == 0.0) value.toInt() else null
    }
    return TaxiOfferMoneyDto(
        grossKop = amount("driver_gross_kop"),
        netKop = amount("driver_net_kop"),
        feeKop = amount("driver_fee_kop"),
        feePercent = (opt("driver_fee_percent") as? Number)?.toDouble(),
    )
}
