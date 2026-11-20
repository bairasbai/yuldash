package com.yuldash.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.VolunteerActivism
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.yuldash.app.data.directDriverPayMethods

internal val payMethodKeys = directDriverPayMethods + "negotiate"

internal fun passengerPayChoices(driverMethods: List<String>): List<String> =
    directDriverPayMethods.filter { it in driverMethods }.ifEmpty { directDriverPayMethods } + "negotiate"

@Composable
internal fun payMethodLabel(method: String): String = when (method) {
    "cash" -> appText("Наличными", "Наличный менән")
    "sbp" -> appText("Перевод по СБП", "СБП аша күсереү")
    else -> appText("Договоримся", "Килешербеҙ")
}

internal fun payMethodIcon(method: String) = when (method) {
    "cash" -> Icons.Default.Payments
    "sbp" -> Icons.Default.CreditCard
    else -> Icons.Default.VolunteerActivism
}

private fun ridePayMethodIcon(method: String) =
    if (method == "sbp") Icons.Default.AccountBalance else payMethodIcon(method)

@Composable
internal fun PayAgreementBlock(
    editable: Boolean,
    method: String,
    amountText: String,
    summaryAmount: Int?,
    onMethod: (String) -> Unit,
    onAmount: (String) -> Unit,
    options: List<String> = payMethodKeys,
    enabled: Boolean = true,
    showAmount: Boolean = true,
) {
    AppCard {
        Column(Modifier.padding(CanonSpace.md), verticalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
                Icon(Icons.Default.Handshake, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(CanonSpace.lg))
                Text(appText("Как договорились платить", "Түләү тураһында нисек килешкәнбеҙ"), color = CanonText, style = CanonBodyStrong)
            }
            Text(appText("Оплата напрямую водителю.", "Түләү туранан-тура йөрөтөүсеһенә."), color = CanonMuted, style = CanonCaption)
            if (editable) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(CanonSpace.sm),
                ) {
                    options.forEach { key ->
                        FilterChip(
                            selected = method == key, enabled = enabled, onClick = { onMethod(key) },
                            modifier = Modifier.minimumInteractiveComponentSize(),
                            label = { Text(payMethodLabel(key), style = CanonCaption) },
                            leadingIcon = { Icon(ridePayMethodIcon(key), contentDescription = null, modifier = Modifier.size(CanonSpace.lg)) },
                            colors = FilterChipDefaults.filterChipColors(
                                labelColor = CanonText, selectedLabelColor = CanonGreen2,
                                selectedContainerColor = CanonMint, selectedLeadingIconColor = CanonGreen2,
                            ),
                        )
                    }
                }
                if (showAmount) OutlinedTextField(
                    value = amountText,
                    onValueChange = { onAmount(it.filter(Char::isDigit).take(6)) },
                    label = { Text(appText("Сумма, ₽ (необязательно)", "Сумма, ₽ (мотлаҡ түгел)")) },
                    enabled = enabled,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
                    Icon(ridePayMethodIcon(method), contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(CanonSpace.lg))
                    Text(payMethodLabel(method), color = CanonGreen2, style = CanonBodyStrong)
                    if (summaryAmount != null) {
                        Text(kopToRub(rideshareRubToKop(summaryAmount)), color = CanonText, style = CanonBodyStrong)
                    }
                }
            }
        }
    }
}
