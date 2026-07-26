package com.yuldash.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AssignmentReturn
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.ApiException
import com.yuldash.app.data.ParcelDto
import kotlinx.coroutines.launch

/*
 * ════════════════════════════════════════════════════════════════════════════
 *  Курьер: «что-то пошло не так» — отказ и возврат
 * ════════════════════════════════════════════════════════════════════════════
 *  Раньше выйти из заказа было НЕЛЬЗЯ вообще: снятия курьера не существовало нигде в коде,
 *  а в статусах были только «доставлена» и «отменена». Замело дорогу на Баймак, курьер
 *  заболел, получателя нет дома — посылка у курьера дома, заказ мёртвый, отправитель
 *  ничего не понимает (аудит 2026-07-26). Для зимнего Башкортостана это норма, не край.
 *
 *  Три честных выхода:
 *   • «Не смогу везти»     → POST /parcels/{id}/release       (посылка возвращается в общий список)
 *   • «Везу обратно»       → POST /parcels/{id}/return-start  (получателя нет / отказался)
 *   • «Вернул отправителю» → POST /parcels/{id}/return-done   (заказ закрыт, комиссию не берём)
 *
 *  Тон: без обвинений. Причина не обязательна, но уходит отправителю — просим по-доброму.
 */

/** Мягкая кнопка на карточке доставки: отказ / возврат. Тон спокойный — это не «провал». */
@Composable
internal fun CourierTroubleButton(returning: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(
            if (returning) Icons.Default.AssignmentReturn else Icons.Default.ErrorOutline,
            contentDescription = null,
            tint = if (returning) CanonWarn else CanonMuted,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            if (returning) appText("Вернул отправителю", "Ебәреүсегә ҡайтарҙым")
            else appText("Что-то пошло не так", "Нимәлер дөрөҫ бармай"),
            color = if (returning) CanonWarn else CanonMuted,
            fontWeight = FontWeight.Bold, fontSize = 13.sp,
        )
    }
}

@Composable
internal fun CourierTroubleDialog(
    parcel: ParcelDto,
    onDismiss: () -> Unit,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var reason by remember(parcel.id) { mutableStateOf("") }
    var busy by remember(parcel.id) { mutableStateOf(false) }
    var err by remember(parcel.id) { mutableStateOf<String?>(null) }

    val fallbackErr = appText("Не получилось. Проверь сеть и повтори.", "Булманы. Селтәрҙе тикшереп ҡабатла.")
    // Посылку уже везут обратно → остался один шаг: отдать её отправителю и закрыть заказ.
    val returning = parcel.status == "returning"

    // Result<out T> ковариантен, поэтому Result<Unit> и Result<ParcelDto> оба подходят под Result<Any?>.
    fun submit(action: suspend () -> Result<Any?>) {
        if (busy) return
        busy = true; err = null
        scope.launch {
            action()
                .onSuccess { busy = false; onDone() }
                .onFailure { busy = false; err = (it as? ApiException)?.message ?: fallbackErr }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = CanonSurface,
        title = {
            Text(
                if (returning) appText("Возврат посылки", "Бандерольде кире ҡайтарыу")
                else appText("Что-то пошло не так?", "Нимәлер дөрөҫ бармаймы?"),
                color = CanonText, fontWeight = FontWeight.Black,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (returning)
                        appText(
                            "Когда отдашь посылку обратно отправителю — отметь здесь. Комиссию за возврат мы не берём.",
                            "Бандерольде ебәреүсегә кире биргәс — бында билдәлә. Кире ҡайтарыу өсөн комиссия алмайбыҙ.",
                        )
                    else
                        appText(
                            "Бывает: заболел, замело дорогу, получателя нет дома. Скажи как есть — отправитель поймёт.",
                            "Булырға мөмкин: ауырыным, юлды ҡар баҫты, алыусы өйҙә юҡ. Нисек бар — шулай әйт, ебәреүсе аңлар.",
                        ),
                    color = CanonMuted, fontSize = 13.sp, lineHeight = 18.sp,
                )
                if (!returning) {
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it.take(200) },
                        label = { Text(appText("Причина (необязательно)", "Сәбәбе (мотлаҡ түгел)")) },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    )
                    Text(
                        appText(
                            "«Не смогу везти» — посылка вернётся в общий список, её возьмёт другой курьер. " +
                                "«Везу обратно» — ты сам довезёшь её отправителю.",
                            "«Илтә алмайым» — бандероль дөйөм исемлеккә ҡайта, уны башҡа курьер ала. " +
                                "«Кире алып барам» — уны ебәреүсегә үҙең илтәһең.",
                        ),
                        color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp,
                    )
                }
                err?.let { Text(it, color = CanonRed, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
            }
        },
        confirmButton = {
            if (returning) {
                TextButton(enabled = !busy, onClick = { submit { ApiClient.parcelReturnDone(parcel.id) } }) {
                    Text(appText("Вернул отправителю", "Ебәреүсегә ҡайтарҙым"), color = CanonGreen2, fontWeight = FontWeight.Bold)
                }
            } else {
                TextButton(enabled = !busy, onClick = { submit { ApiClient.parcelReturnStart(parcel.id, reason) } }) {
                    Text(appText("Везу обратно", "Кире алып барам"), color = CanonWarn, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            if (returning) {
                TextButton(enabled = !busy, onClick = onDismiss) {
                    Text(appText("Закрыть", "Ябыу"), color = CanonMuted)
                }
            } else {
                TextButton(enabled = !busy, onClick = { submit { ApiClient.parcelRelease(parcel.id, reason) } }) {
                    Text(appText("Не смогу везти", "Илтә алмайым"), color = CanonRed, fontWeight = FontWeight.Bold)
                }
            }
        },
    )
}
