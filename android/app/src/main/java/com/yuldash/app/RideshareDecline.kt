package com.yuldash.app

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * «Отклонить» бронь, которую водитель ещё не подтверждал (приёмка 18.10, п.6).
 *
 * Раньше одно касание сразу снимало бронь: промахнулся пальцем мимо «Подтвердить» — и человек
 * уже получил отказ, а вернуть его нечем. Теперь сначала вопрос «Отклонить бронь?» с именем
 * пассажира; [onDecline] зовётся только после «Да, отклонить». Сервер сам узнаёт отказ (бронь
 * ждала подтверждения, снимает водитель) и пишет пассажиру «Водитель отклонил бронь».
 */
@Composable
internal fun DeclineBookingButton(
    passengerName: String,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var asking by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(
        onClick = { asking = true },
        modifier = modifier.heightIn(min = 48.dp),
        shape = CanonFieldShape,
    ) { Text(appText("Отклонить", "Кире ҡағыу"), color = CanonRed, fontWeight = FontWeight.Bold) }
    if (!asking) return
    val who = passengerName.ifBlank { appText("Пассажир", "Пассажир") }
    AlertDialog(
        onDismissRequest = { asking = false },
        containerColor = CanonSurface,
        shape = CanonCardShape,
        title = {
            Text(appText("Отклонить бронь?", "Бронде кире ҡағырғамы?"), color = CanonText, fontWeight = FontWeight.Bold)
        },
        text = {
            Text(
                appText(
                    "$who получит уведомление, что ты отклонил бронь. Место снова станет свободным.",
                    "$who бронде кире ҡаҡҡаныңды белер. Урын яңынан буш булыр.",
                ),
                color = CanonMuted, style = CanonCaption,
            )
        },
        confirmButton = {
            TextButton(onClick = { asking = false; onDecline() }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(appText("Да, отклонить", "Эйе, кире ҡағырға"), color = CanonRed, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = { asking = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(appText("Не отклонять", "Кире ҡаҡмаҫҡа"), color = CanonGreen2, fontWeight = FontWeight.Bold)
            }
        },
    )
}
