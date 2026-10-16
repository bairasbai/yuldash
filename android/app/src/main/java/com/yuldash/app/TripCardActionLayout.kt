package com.yuldash.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** Receipt/history actions keep full labels, including large system text. Other cards retain their row. */
@Composable
internal fun TripCardActionLayout(stacked: Boolean, content: @Composable (Modifier, Modifier) -> Unit) {
    if (stacked) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
            content(Modifier.fillMaxWidth(), Modifier.fillMaxWidth())
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(CanonSpace.sm), verticalAlignment = Alignment.CenterVertically) {
            content(Modifier.weight(1f), Modifier.weight(1.1f))
        }
    }
}
