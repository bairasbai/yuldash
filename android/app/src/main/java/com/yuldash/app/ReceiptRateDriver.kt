package com.yuldash.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * «Оценить водителя» в квитанции (приёмка 18.10, п.7).
 *
 * Пассажир пропустил экран «Поездка завершена» — и позже оценить было негде: в истории только
 * «Повторить маршрут» и «Квитанция», а квитанция показывала оценку, лишь когда она уже стоит.
 * Теперь здесь пять звёзд; касание сразу отправляет оценку (как в квитанции такси). Блок виден,
 * пока сервер разрешает оценку (`can_rate`: 60 дней после выезда) и своей оценки ещё нет.
 */
@Composable
internal fun ReceiptRateDriver(busy: Boolean, onRate: (Int) -> Unit) {
    AppCard {
        Column(
            Modifier.fillMaxWidth().padding(CanonSpace.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(CanonSpace.sm),
        ) {
            Text(appText("Оценить водителя", "Йөрөтөүсене баһалау"),
                color = CanonText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(
                appText("Не успел оценить после поездки? Пара звёзд поможет другим выбрать.",
                    "Сәфәрҙән һуң баһаларға өлгөрмәнеңме? Бер нисә йондоҙ башҡаларға һайларға ярҙам итер."),
                color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(CanonSpace.xs)) {
                for (v in 1..5) {
                    val label = appText("$v из 5", "5-тән $v")
                    IconButton(
                        onClick = { onRate(v) },
                        enabled = !busy,
                        modifier = Modifier.size(48.dp).semantics { contentDescription = label },
                    ) {
                        Icon(Icons.Default.StarBorder, contentDescription = null, tint = CanonStar, modifier = Modifier.size(32.dp))
                    }
                }
            }
        }
    }
}
