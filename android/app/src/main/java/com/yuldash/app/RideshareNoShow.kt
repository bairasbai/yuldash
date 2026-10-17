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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.ApiException
import kotlinx.coroutines.launch

// Неявка пассажира попутки в кабинете водителя («Едут с тобой»). Вынесено из InstantOrderScreen.kt
// (храповик размера файлов, 14.10.2026): это домен брони попутки, а не такси-заказа.
// С 17.10.2026 посадку отмечает водитель: «Сел» рядом с «Не пришёл» и отмена отметки —
// в RideshareBoarding.kt (DriverBoardingControls), здесь — неявка и разбор.

/**
 * Можно ли водителю отметить неявку. Только по подтверждённой брони: «в пути» (onboard) значит,
 * что посадка уже отмечена, и одним тапом это не перечеркнуть — сервер ответит 409
 * (решение 14.10.2026, docs/decisions.md). Промахнулся — «Отменить» в первые минуты, позже разбор.
 */
internal fun noShowAllowed(bookingStatus: String): Boolean = bookingStatus == "confirmed"

/**
 * «Не пришёл» — водитель отмечает неявку пассажира (no-show): /bookings/{id}/no-show, места
 * возвращаются на сервере. Что показать после ответа, решает карточка ([DriverBoardingControls]):
 * [onSent] — неявка отмечена, [onBoarded] — сервер ответил, что посадка уже отмечена (второй
 * телефон водителя, очередь без сети): аргумент — сколько секунд ещё можно отменить отметку
 * (`boarding_undo_sec` из отказа), чтобы карточка показала «Отменить» с верным отсчётом, а не
 * сразу разбор (приёмка PR174).
 */
@Composable
internal fun NoShowButton(
    bookingId: Int,
    onSent: () -> Unit,
    onBoarded: (undoSec: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var sending by remember(bookingId) { mutableStateOf(false) }
    val failMsg = appText("Не получилось отметить. Проверь сеть.", "Билдәләп булманы. Селтәрҙе тикшер.")
    OutlinedButton(
        onClick = {
            if (sending) return@OutlinedButton
            sending = true
            scope.launch {
                ApiClient.markNoShow(bookingId)
                    .onSuccess { onSent() }
                    .onFailure { e ->
                        if (e is ApiException && e.detailCode == "passenger_onboard") {
                            onBoarded(e.detail?.optInt("boarding_undo_sec")?.coerceAtLeast(0) ?: 0)
                        }
                        Toast.makeText(ctx, serverSaid(e, failMsg), Toast.LENGTH_LONG).show()
                    }
                sending = false
            }
        },
        enabled = !sending,
        shape = CanonFieldShape,
        border = BorderStroke(1.dp, CanonRed),
        modifier = modifier.heightIn(min = 48.dp),
    ) {
        Text(
            appText("Не пришёл", "Килмәне"),
            color = CanonRed, style = CanonButton, textAlign = TextAlign.Center,
        )
    }
}

/** Неявка отмечена: спокойное подтверждение на месте кнопок. */
@Composable
internal fun NoShowSentNote(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().background(CanonMint, CanonItemShape)
            .padding(horizontal = CanonSpace.md, vertical = CanonSpace.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(CanonSpace.sm))
        Text(
            appText("Отмечена неявка. Место вернулось в поездку.",
                    "Килмәгәнлек билдәләнде. Урын сәфәргә ҡайтты."),
            color = CanonGreen2, style = CanonCaption,
        )
    }
}

/**
 * Бронь «в пути», а отменить отметку уже нельзя. Неявку не ставим — спор решает двусторонний
 * разбор: пассажира зовут объясниться, решение объясняют обоим. Тип спора уже выбран
 * («Пассажир не вышел»), водителю остаётся рассказать, как было.
 */
@Composable
internal fun BoardedDisputeBlock(bookingId: Int, passengerId: Int, passengerName: String, modifier: Modifier = Modifier) {
    var open by remember(bookingId) { mutableStateOf(false) }
    var filed by remember(bookingId) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
        Text(
            appText(
                "Если пассажир на самом деле не садился — сообщи о проблеме, разберёмся.",
                "Пассажир ысынлап ултырмаған булһа — проблема тураһында хәбәр ит, тикшерербеҙ.",
            ),
            color = CanonMuted, style = CanonCaption,
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
                        color = CanonGreen2, style = CanonCaption,
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
