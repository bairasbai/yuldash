package com.yuldash.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AssignmentReturn
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
 *
 *  ── Как это выглядит (правки 2026-07-30) ───────────────────────────────────
 *  Выходы стоят в теле диалога полноразмерными кнопками, а не в углу «да / нет». Причины две:
 *   1. Раньше у диалога НЕ БЫЛО кнопки «Отмена» — оба угла были действиями, и человек,
 *      открывший его случайно, мог только промахнуться мимо окна. Теперь отмена явная.
 *   2. «Не смогу везти» больше не красная. Это не провал и не наказание, а нормальный
 *      зимний исход — красный тут пугал и противоречил тону файла («без обвинений»).
 *  Рекомендуемый путь («Везу обратно» / «Вернул отправителю») — сплошная зелёная кнопка,
 *  запасной — контурная. Каждая честно показывает, что запрос ушёл: крутится только та,
 *  которую нажали.
 */

// Типографика диалога: два размера, две роли. Body — то, что человек читает;
// Meta — пояснение мелким шрифтом. Заголовок держит сам AlertDialog.
private val TroubleBody = 14.sp
private val TroubleBodyLine = 20.sp
private val TroubleMeta = 12.sp
private val TroubleMetaLine = 16.sp

// Ритм: всё кратно 4dp.
private val TroubleGap = 12.dp
private val TroubleGapTight = 8.dp
private val TroublePad = 16.dp
private val TroubleTouch = 48.dp     // минимальная тач-цель
private val TroubleIcon = 16.dp

/** Мягкая кнопка на карточке доставки: отказ / возврат. Тон спокойный — это не «провал». */
@Composable
internal fun CourierTroubleButton(returning: Boolean, onClick: () -> Unit) {
    // Посылка перешла в возврат прямо на экране — подпись и цвет переезжают плавно,
    // без «подмены» кнопки под пальцем.
    val tint by animateColorAsState(if (returning) CanonWarn else CanonMuted, tween(260), label = "trouble-tint")
    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = TroubleTouch)) {
        AnimatedContent(
            targetState = returning,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(140)) },
            label = "trouble-label",
        ) { isReturning ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (isReturning) Icons.Default.AssignmentReturn else Icons.Default.ErrorOutline,
                    // Рядом стоит та же надпись — второй раз её озвучивать не нужно.
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(TroubleIcon),
                )
                Spacer(Modifier.width(TroubleGapTight))
                Text(
                    if (isReturning) appText("Вернул отправителю", "Ебәреүсегә ҡайтарҙым")
                    else appText("Что-то пошло не так", "Нимәлер дөрөҫ бармай"),
                    color = tint,
                    fontWeight = FontWeight.Bold,
                    fontSize = TroubleMeta,
                    lineHeight = TroubleMetaLine,
                )
            }
        }
    }
}

/** Плашка-сообщение внутри диалога (ошибка). Единый вид, цвета — только токены Canon*. */
@Composable
private fun TroubleNotice(text: String, bg: Color, fg: Color) {
    Surface(color = bg, shape = CanonItemShape, modifier = Modifier.fillMaxWidth()) {
        Text(
            text,
            color = fg,
            fontSize = TroubleBody,
            lineHeight = TroubleBodyLine,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(TroublePad),
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
    // Какая именно кнопка сейчас ждёт сервер — чтобы спиннер крутился на нажатой, а не на обеих.
    var pending by remember(parcel.id) { mutableStateOf("") }

    val fallbackErr = appText("Не получилось. Проверь сеть и повтори.", "Булманы. Селтәрҙе тикшереп ҡабатла.")
    // Посылку уже везут обратно → остался один шаг: отдать её отправителю и закрыть заказ.
    val returning = parcel.status == "returning"

    // Result<out T> ковариантен, поэтому Result<Unit> и Result<ParcelDto> оба подходят под Result<Any?>.
    fun submit(tag: String, action: suspend () -> Result<Any?>) {
        if (busy) return
        busy = true; pending = tag; err = null
        scope.launch {
            action()
                .onSuccess { busy = false; pending = ""; onDone() }
                .onFailure { busy = false; pending = ""; err = (it as? ApiException)?.message ?: fallbackErr }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = CanonSurface,
        shape = CanonCardShape,
        title = {
            Text(
                if (returning) appText("Возврат посылки", "Бандерольде кире ҡайтарыу")
                else appText("Что-то пошло не так?", "Нимәлер дөрөҫ бармаймы?"),
                color = CanonText, fontWeight = FontWeight.Bold,
            )
        },
        text = {
            // Скролл обязателен: с полем причины, пояснением и двумя кнопками содержимое
            // не влезает в диалог при крупном системном шрифте, а Material сам не прокручивает.
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(TroubleGap),
            ) {
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
                    color = CanonMuted, fontSize = TroubleBody, lineHeight = TroubleBodyLine,
                )
                if (!returning) {
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it.take(200) },
                        label = {
                            Text(appText("Причина (необязательно)", "Сәбәбе (мотлаҡ түгел)"), fontSize = TroubleBody)
                        },
                        minLines = 2,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CanonGreen2,
                            cursorColor = CanonGreen2,
                            focusedLabelColor = CanonGreen2,
                        ),
                    )
                    Text(
                        appText(
                            "«Не смогу везти» — посылка вернётся в общий список, её возьмёт другой курьер. " +
                                "«Везу обратно» — ты сам довезёшь её отправителю.",
                            "«Илтә алмайым» — бандероль дөйөм исемлеккә ҡайта, уны башҡа курьер ала. " +
                                "«Кире алып барам» — уны ебәреүсегә үҙең илтәһең.",
                        ),
                        color = CanonMuted, fontSize = TroubleMeta, lineHeight = TroubleMetaLine,
                    )
                }

                // Ошибка появляется плавно и стоит прямо над кнопками — там, где человек смотрит.
                AnimatedVisibility(
                    visible = err != null,
                    enter = fadeIn(tween(220)),
                    exit = fadeOut(tween(140)),
                ) {
                    TroubleNotice(err.orEmpty(), CanonDangerBg, CanonRed)
                }

                if (returning) {
                    AppButton(
                        text = appText("Вернул отправителю", "Ебәреүсегә ҡайтарҙым"),
                        onClick = { submit("return-done") { ApiClient.parcelReturnDone(parcel.id) } },
                        icon = Icons.Default.AssignmentReturn,
                        loading = pending == "return-done",
                        enabled = !busy,
                    )
                } else {
                    // Рекомендуемый путь — сплошная кнопка; запасной — контурная.
                    AppButton(
                        text = appText("Везу обратно", "Кире алып барам"),
                        onClick = { submit("return-start") { ApiClient.parcelReturnStart(parcel.id, reason) } },
                        icon = Icons.Default.AssignmentReturn,
                        loading = pending == "return-start",
                        enabled = !busy,
                    )
                    AppButton(
                        text = appText("Не смогу везти", "Илтә алмайым"),
                        onClick = { submit("release") { ApiClient.parcelRelease(parcel.id, reason) } },
                        style = AppButtonStyle.Secondary,
                        icon = Icons.Default.ErrorOutline,
                        loading = pending == "release",
                        enabled = !busy,
                    )
                }
            }
        },
        confirmButton = {
            // Единственная кнопка в углу — выход без действия. Раньше её не было вообще:
            // оба угла были действиями, и «просто закрыть» было нечем.
            TextButton(enabled = !busy, onClick = onDismiss, modifier = Modifier.heightIn(min = TroubleTouch)) {
                Text(
                    if (returning) appText("Закрыть", "Ябыу") else appText("Отмена", "Кире алыу"),
                    color = CanonMuted, fontSize = TroubleBody,
                )
            }
        },
    )
}
