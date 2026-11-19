package com.yuldash.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** F12 «Зимний протокол»: сейчас морозная ночь? Зима по МЕСЯЦУ (ноя–мар) + ночное время
 *  (20:00–07:00) по календарю устройства. Без внешних API/погоды в v1 — простое и честное правило. */
internal fun isFrostyWinterNight(now: java.util.Calendar = java.util.Calendar.getInstance()): Boolean {
    val month = now.get(java.util.Calendar.MONTH)   // 0=янв … 11=дек
    val hour = now.get(java.util.Calendar.HOUR_OF_DAY)
    val winter = month == java.util.Calendar.NOVEMBER || month == java.util.Calendar.DECEMBER ||
        month == java.util.Calendar.JANUARY || month == java.util.Calendar.FEBRUARY || month == java.util.Calendar.MARCH
    val night = hour >= 20 || hour < 7
    return winter && night
}

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

/** F12 «Застрял на трассе»: заметная, но спокойная (амбер), отдельный уровень от красной паники SOS.
 *  Состояния: обычная / отправка / отправлено. Двуязычие внутри. */
@Composable
internal fun RoadsideHelpButton(sending: Boolean, sent: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        enabled = !sending && !sent,
        modifier = modifier.fillMaxWidth().heightIn(min = 50.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = CanonWarnBg, contentColor = CanonWarn),
        border = BorderStroke(1.dp, CanonWarn),
    ) {
        if (sending) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = CanonWarn)
            Spacer(Modifier.width(8.dp))
            Text(appText("Отправляем…", "Ебәрелә…"), color = CanonWarn, fontWeight = FontWeight.Bold)
        } else {
            Icon(if (sent) Icons.Default.CheckCircle else Icons.Default.Warning, contentDescription = null, tint = CanonWarn, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                if (sent) appText("Помощь позвана", "Ярҙам саҡырылды") else appText("Застрял на трассе", "Юлда ҡалдым"),
                color = CanonWarn, fontWeight = FontWeight.Bold,
            )
        }
    }
}
