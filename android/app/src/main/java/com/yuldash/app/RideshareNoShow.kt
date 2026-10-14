package com.yuldash.app

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.ApiException
import kotlinx.coroutines.launch

// Неявка пассажира попутки в кабинете водителя («Едут с тобой»). Вынесено из InstantOrderScreen.kt
// (храповик размера файлов, 14.10.2026): это домен брони попутки, а не такси-заказа.

/**
 * Можно ли водителю отметить неявку. Только по подтверждённой брони: «в пути» (onboard) значит,
 * что пассажир сам нажал «Я сел», и одним тапом это не перечеркнуть — сервер ответит 409
 * (решение 14.10.2026, docs/decisions.md). Спорную посадку разбирает админ.
 */
internal fun noShowAllowed(bookingStatus: String): Boolean = bookingStatus == "confirmed"

// Водитель отмечает неявку пассажира (no-show). Само-содержащая кнопка (как UnpaidReportButton):
// дёргает /bookings/{id}/no-show, при успехе показывает подтверждение на месте. Места возвращаются на сервере.
// Бронь «в пути» — вместо красной кнопки спокойное объяснение и «Сообщить о проблеме» (разбор).
@Composable
internal fun NoShowButton(
    bookingId: Int,
    // Без значения по умолчанию: новое место вызова обязано передать статус брони с сервера,
    // иначе «Пассажир не явился» снова появится у брони «в пути» (ревью Opus волны 1).
    bookingStatus: String,
    modifier: Modifier = Modifier,
    passengerId: Int = 0,
    passengerName: String = "",
) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var sent by remember(bookingId) { mutableStateOf(false) }
    var sending by remember(bookingId) { mutableStateOf(false) }
    // Пассажир нажал «Я сел» уже после загрузки списка: сервер отказал с причиной — сразу
    // показываем путь разбора, а не ту же кнопку, которая снова получит отказ.
    var boardedByServer by remember(bookingId) { mutableStateOf(false) }
    val failMsg = appText("Не получилось отметить. Проверь сеть.", "Билдәләп булманы. Селтәрҙе тикшер.")
    if (!sent && (bookingStatus == "onboard" || boardedByServer)) {
        BoardedDisputeBlock(bookingId, passengerId, passengerName, modifier)
        return
    }
    if (!sent && !noShowAllowed(bookingStatus)) return   // ждёт ответа / закрыта — неявке не место
    if (sent) {
        Row(
            modifier = modifier.fillMaxWidth().background(CanonMint, RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                appText("Отмечена неявка. Место вернулось в поездку.",
                        "Килмәгәнлек билдәләнде. Урын сәфәргә ҡайтты."),
                color = CanonGreen2, fontSize = 14.sp, lineHeight = 20.sp,
            )
        }
    } else {
        OutlinedButton(
            onClick = {
                if (sending) return@OutlinedButton
                sending = true
                scope.launch {
                    ApiClient.markNoShow(bookingId)
                        .onSuccess { sent = true }
                        .onFailure { e ->
                            if (e is ApiException && e.detailCode == "passenger_onboard") boardedByServer = true
                            Toast.makeText(ctx, serverSaid(e, failMsg), Toast.LENGTH_LONG).show()
                        }
                    sending = false
                }
            },
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, CanonRed),
            modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(appText("Пассажир не явился", "Пассажир килмәне"), color = CanonRed, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * Бронь «в пути»: пассажир сам отметил «Я сел». Неявку не ставим — слово против слова решает
 * двусторонний разбор: пассажира зовут объясниться, решение объясняют обоим. Тип спора уже
 * выбран («Пассажир не вышел»), водителю остаётся рассказать, как было.
 */
@Composable
private fun BoardedDisputeBlock(bookingId: Int, passengerId: Int, passengerName: String, modifier: Modifier) {
    var open by remember(bookingId) { mutableStateOf(false) }
    var filed by remember(bookingId) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
        Text(
            appText(
                "Пассажир отметил «Я сел». Если он не садился — сообщи о проблеме, разберёмся.",
                "Юлсы «Ултырҙым» тип билдәләне. Ултырмаһа — проблема тураһында хәбәр ит, тикшерербеҙ.",
            ),
            color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp,
        )
        AnimatedContent(targetState = filed, label = "boardedDispute") { done ->
            if (done) {
                Row(
                    modifier = Modifier.fillMaxWidth().background(CanonMint, CanonItemShape)
                        .padding(horizontal = CanonSpace.md, vertical = CanonSpace.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(CanonSpace.sm))
                    Text(
                        appText(
                            "Разбор открыт. Позовём пассажира объясниться, а решение объясним обеим сторонам.",
                            "Ҡарау асылды. Юлсыны аңлатырға саҡырабыҙ, ә ҡарарҙы ике яҡҡа ла аңлатабыҙ.",
                        ),
                        color = CanonGreen2, fontSize = 14.sp, lineHeight = 20.sp,
                    )
                }
            } else if (passengerId > 0) {
                OutlinedButton(
                    onClick = { open = true },
                    shape = CanonItemShape,
                    border = BorderStroke(1.dp, CanonBorder),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(appText("Сообщить о проблеме", "Проблема тураһында хәбәр итергә"), color = CanonText, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
    if (open) {
        FileIncidentDialog(
            respondentId = passengerId,
            respondentName = passengerName,
            bookingId = bookingId,
            onDismiss = { open = false },
            onFiled = { open = false; filed = true },
            initialType = "passenger_no_show",
        )
    }
}
