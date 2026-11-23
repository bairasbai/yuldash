package com.yuldash.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Дружелюбная заглушка «нет диалогов» — полноценная, сбалансированная (не «половина пустая»).
@Composable
internal fun ChatEmptyState() {
    Card(
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = CanonCardShape,
        elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.card),
        border = BorderStroke(1.dp, CanonBorder)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(color = CanonMint, shape = CircleShape) {
                Icon(
                    Icons.Default.ChatBubbleOutline,
                    contentDescription = null,
                    tint = CanonGreen2,
                    modifier = Modifier.padding(24.dp).size(38.dp)
                )
            }
            Text(
                appText("Здесь будут твои чаты", "Бында чаттарың булыр"),
                color = CanonText, fontWeight = FontWeight.Bold, fontSize = 19.sp, textAlign = TextAlign.Center
            )
            Text(
                appText(
                    // Русский был на «вы», башкирский рядом — на «ты» («бронла»). Тон один.
                    "Найди поездку и забронируй место — после брони откроется чат с водителем или пассажиром.",
                    "Сәфәр табып, урын бронла — бронынан һуң йөрөтөүсе йәки пассажир менән чат асыла."
                ),
                color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center
            )
            Surface(color = CanonMint, shape = RoundedCornerShape(14.dp)) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("💬", fontSize = 19.sp)
                    Text(
                        appText("Например: «Я на остановке у рынка»", "Мәҫәлән: «Мин баҙар туҡталышында»"),
                        color = CanonGreen, fontSize = 14.sp, fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}
