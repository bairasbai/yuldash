package com.yuldash.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@Composable
internal fun BookingSeatCount(available: Int, selected: Int, price: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    AppCard {
        Column(Modifier.fillMaxWidth().padding(CanonSpace.lg), verticalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
            Text(appText("Количество мест", "Урындар һаны"), style = CanonBodyStrong, color = CanonText)
            if (available < 1) {
                Text(
                    appText("Мест больше нет. Посмотри другие поездки.", "Буш урындар ҡалманы. Башҡа сәфәрҙәрҙе ҡара."),
                    style = CanonCaption, color = CanonMuted,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CanonSpace.md)) {
                    IconButton(enabled = enabled && selected > 1, onClick = { onChange((selected - 1).coerceIn(1, available)) }) {
                        Icon(Icons.Default.Remove, appText("Меньше мест", "Урындарҙы кәметеү"), tint = CanonGreen2)
                    }
                    AnimatedContent(targetState = selected, label = "bookingSeats") { count ->
                        Text(count.toString(), style = CanonHeading, color = CanonText)
                    }
                    IconButton(enabled = enabled && selected < available, onClick = { onChange((selected + 1).coerceIn(1, available)) }) {
                        Icon(Icons.Default.Add, appText("Больше мест", "Урындарҙы арттырыу"), tint = CanonGreen2)
                    }
                }
                Text(
                    appText("Итого: ", "Бөтәһе: ") + kopToRub(price * selected * 100),
                    style = CanonBodyStrong, color = CanonText,
                )
            }
        }
    }
}
