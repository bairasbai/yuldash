package com.yuldash.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Спокойный баннер морозной ночи: напомнить взять тепло и проверить заряд. Тон — заботливый, не тревожный. */
@Composable
internal fun FrostyNightBanner(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = CanonMint),
        shape = CanonItemShape,
        elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.flat),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.AcUnit, contentDescription = appText("Морозная ночь", "Һыуыҡ төн"), tint = CanonGreen2, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(appText("Морозная ночь", "Һыуыҡ төн"), fontWeight = FontWeight.Bold, color = CanonGreen2)
                Text(
                    appText(
                        "Возьми тёплые вещи и проверь заряд телефона перед дорогой. Поделись поездкой со своими.",
                        "Йылы кейем ал һәм юлға сыҡҡанға тиклем телефон зарядын тикшер. Сәфәреңде үҙеңдекеләр менән уртаҡлаш.",
                    ),
                    color = CanonGreen2, fontSize = 14.sp, lineHeight = 20.sp,
                )
            }
        }
    }
}
