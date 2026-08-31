package com.yuldash.app

// Две вещи, которые работали на сервере и жили в веб-версии, а в приложении их не было
// (сверка с вебом, 2026-08-30).
//
// 1. Денежные чаевые. Водитель указывает свой номер СБП — и пассажир после ЗАВЕРШЁННОЙ
//    поездки видит, куда перевести «спасибо». Платформа денег не касается: перевод идёт
//    напрямую, комиссии тут нет вообще. Номер личный, поэтому он opt-in и показывается
//    только тому, кто реально ехал.
//
// 2. Советы по поездке. Поездка висит без броней — и водитель не знает почему. Сервер
//    знает: нет фото профиля, не пройдена проверка, цена выше средней по маршруту, нет
//    описания. Это подсказка, а не упрёк: всё хорошо — блока просто нет.

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.RideTipsDto
import kotlinx.coroutines.launch

/**
 * «Спасибо деньгами» — номер СБП водителя.
 *
 * Номер сохраняем только по нажатию: живое сохранение на каждый символ отправило бы
 * на сервер половину номера. Пустое поле — выключить: отдельного тумблера не нужно,
 * «нет номера» и есть «не принимаю».
 */
@Composable
internal fun DriverTipsCard(initialSbp: String = "") {
    var sbp by remember { mutableStateOf(initialSbp) }
    var saved by remember { mutableStateOf(initialSbp.isNotBlank()) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Surface(
        color = CanonSurface,
        shape = CanonItemShape,
        border = BorderStroke(1.dp, CanonBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Payments, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    appText("Спасибо деньгами", "Аҡса менән рәхмәт"),
                    color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                )
            }
            Text(
                appText(
                    "Пассажир увидит твой номер СБП только после завершённой поездки. Деньги идут напрямую тебе — платформа их не касается и комиссию не берёт.",
                    "Юлаусы һинең СБП номерыңды тик тамамланған сәфәрҙән һуң күрә. Аҡса тура һиңә бара — платформа уға ҡағылмай, комиссия алмай.",
                ),
                color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp,
            )
            OutlinedTextField(
                value = sbp,
                onValueChange = { sbp = it.take(20); saved = false; failed = false },
                placeholder = {
                    Text(appText("+7 999 000-00-00", "+7 999 000-00-00"), color = CanonMuted, fontSize = 14.sp)
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    busy = true
                    failed = false
                    scope.launch {
                        ApiClient.setTipsSbp(sbp)
                            .onSuccess { saved = true }
                            .onFailure { failed = true }
                        busy = false
                    }
                },
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(
                    if (sbp.isBlank()) appText("Не принимать чаевые", "Аҡса рәхмәтен алмаҫҡа")
                    else appText("Сохранить номер", "Номерҙы һаҡлау"),
                    fontWeight = FontWeight.Bold,
                )
            }
            AnimatedVisibility(visible = saved) {
                Text(
                    if (sbp.isBlank()) appText("Чаевые выключены", "Аҡса рәхмәте һүндерелгән")
                    else appText("Готово. Пассажиры увидят номер после поездки", "Әҙер. Юлаусылар номерҙы сәфәрҙән һуң күрә"),
                    color = CanonGreen2, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                )
            }
            AnimatedVisibility(visible = failed) {
                Text(
                    appText("Не сохранилось. Проверь номер и сеть.", "Һаҡланманы. Номерҙы һәм селтәрҙе тикшер."),
                    color = CanonRed, fontSize = 14.sp,
                )
            }
        }
    }
}

/**
 * Почему поездка стоит без броней. Молчит, когда всё хорошо: пустая плашка «всё отлично»
 * каждый день перестаёт читаться, а в нужный день её пролистают вместе с остальным.
 */
@Composable
internal fun RideTipsCard(rideId: Int) {
    var data by remember(rideId) { mutableStateOf<RideTipsDto?>(null) }

    LaunchedEffect(rideId) {
        ApiClient.getRideTips(rideId).onSuccess { data = it }
    }

    val d = data ?: return
    if (d.allGood || d.tips.isEmpty()) return

    Surface(
        color = CanonWarnBg,
        shape = CanonItemShape,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Lightbulb, contentDescription = null, tint = CanonWarn, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    appText("Как получить больше заявок", "Күберәк заявка нисек алырға"),
                    color = CanonWarn, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                )
            }
            d.tips.forEach { tip ->
                Text("• " + appText(tip.ru, tip.ba), color = CanonMutedStrong, fontSize = 14.sp, lineHeight = 20.sp)
            }
            // Средняя цена по маршруту — не приговор, а ориентир: иногда дороже и правильно
            // (новая машина, ночь). Показываем только когда есть на чём считать.
            if (d.routeAvgPrice != null && d.routeSample > 0) {
                Text(
                    appText(
                        "Обычно по этому маршруту просят около ${d.routeAvgPrice} ₽ (поездок: ${d.routeSample})",
                        "Был юлда ғәҙәттә ${d.routeAvgPrice} һум һорайҙар (сәфәр: ${d.routeSample})",
                    ),
                    color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp,
                )
            }
        }
    }
}
