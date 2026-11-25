package com.yuldash.app

import com.yuldash.app.data.TaxiOfferMoneyDto
import kotlin.math.roundToInt

internal data class TaxiOfferMoneyCopy(val income: String, val breakdown: String?, val incomeKnown: Boolean)

private val offerGroupDigits = Regex("(\\d)(?=(\\d{3})+(?:,| ))")

private fun offerKopLabel(kop: Int): String =
    formatTaxiKop(kop).replace(offerGroupDigits, "$1\u00A0")

internal fun taxiOfferMoneyCopy(money: TaxiOfferMoneyDto, language: AppLanguage): TaxiOfferMoneyCopy {
    val gross = money.grossKop?.takeIf { it > 0 }
        ?: return TaxiOfferMoneyCopy(appTextFor(language, "Уточняется", "Асыҡлана"), null, false)
    val net = money.netKop?.takeIf { it in 0..gross }
    val income = net?.let(::offerKopLabel)
        ?: appTextFor(language, "Уточняется", "Асыҡлана")
    val fee = money.feeKop?.takeIf { it in 0..gross }
        ?: return TaxiOfferMoneyCopy(income, null, net != null)
    val rate = money.feePercent?.takeIf { it in 0.0..100.0 }?.let {
        val percent = ((it * 100).roundToInt() / 100.0).toString().removeSuffix(".0").replace('.', ',')
        " (${percent}%)"
    }.orEmpty()
    return TaxiOfferMoneyCopy(
        income,
        appTextFor(
            language,
            "Пассажир платит ${offerKopLabel(gross)} · комиссия ${offerKopLabel(fee)}$rate",
            "Пассажир ${offerKopLabel(gross)} түләй · комиссия ${offerKopLabel(fee)}$rate",
        ),
        net != null,
    )
}
