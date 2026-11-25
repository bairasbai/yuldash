package com.yuldash.app

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

@Composable
internal fun DriverCurrentTaxiLookupError(onRetry: () -> Unit) {
    Text(appText("Не удалось проверить активную поездку", "Әүҙем сәфәрҙе тикшереп булманы"), color = CanonMuted)
    TextButton(onClick = onRetry) { Text(appText("Повторить", "Ҡабатлау")) }
}
