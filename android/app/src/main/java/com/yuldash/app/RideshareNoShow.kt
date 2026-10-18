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
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
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
//
// «Не пришёл» (решение Александра 18.10.2026, образец BlaBlaCar): не раньше чем через 15 минут после
// времени выезда и только после вопроса «Точно?». Раньше кнопка работала в любой момент — хоть за
// неделю до поездки — одним касанием вплотную к «Сел», и вернуть было нельзя: промахнулся, и бронь
// человека, который стоит у дороги, снята, а место ушло в продажу. Срок знает ТОЛЬКО сервер
// (`bookings.NO_SHOW_WAIT`): клиент получает момент `no_show_at` и 15 минут у себя не держит.
// До этого момента вместо кнопки — спокойная подпись [NoShowWaitNote]; нажатие — подтверждение;
// отказ сервера `no_show_too_early` — его словами в тосте.

/**
 * Можно ли водителю отметить неявку. Только по подтверждённой брони: «в пути» (onboard) значит,
 * что посадка уже отмечена, и одним тапом это не перечеркнуть — сервер ответит 409
 * (решение 14.10.2026, docs/decisions.md). Промахнулся — «Отменить» в первые минуты, позже разбор.
 */
internal fun noShowAllowed(bookingStatus: String): Boolean = bookingStatus == "confirmed"

/**
 * С какого момента (мс) водителю доступна «Не пришёл». null — срока нет: старый сервер без
 * `no_show_at`, у поездки не задан выезд или строка не разобралась. Тогда кнопку не прячем:
 * последнее слово за сервером (409 `no_show_too_early`).
 */
internal fun noShowOpensAtMs(noShowAt: String?): Long? =
    noShowAt?.takeIf { it.isNotBlank() }?.let { parseIsoUtcMillis(it) }

/** Можно ли уже нажимать «Не пришёл»: срок наступил или его не знаем (решает сервер). */
internal fun noShowOpen(noShowAt: String?, nowMs: Long): Boolean =
    noShowOpensAtMs(noShowAt)?.let { nowMs >= it } ?: true

/**
 * Живое «срок уже наступил?» для карточки: пересчёт раз в секунду ([rememberNowMs] — в фоне не тикает,
 * при возврате берёт время заново). Карточка перерисовывается только в момент перехода, а не каждую секунду.
 */
@Composable
internal fun rememberNoShowOpen(noShowAt: String?): State<Boolean> {
    val opensAt = remember(noShowAt) { noShowOpensAtMs(noShowAt) }
    val now = rememberNowMs()
    return remember(opensAt) { derivedStateOf { opensAt == null || now.value >= opensAt } }
}

/** Пока срок не наступил: на месте «Не пришёл» — спокойная подпись, когда это станет можно. */
@Composable
internal fun NoShowWaitNote(noShowAt: String?, modifier: Modifier = Modifier) {
    val from = noShowAt?.let { formatDepart(it) }.orEmpty()
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Schedule, contentDescription = null, tint = CanonMuted, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(CanonSpace.sm))
        Text(
            appText(
                "Не пришёл — можно отметить с $from",
                "Килмәне — $from үткәс кенә билдәләп була",
            ),
            color = CanonMuted, style = CanonCaption,
        )
    }
}

/**
 * «Не пришёл» — водитель отмечает неявку пассажира (no-show): /bookings/{id}/no-show, места
 * возвращаются на сервере. Нажатие само ничего не отправляет: сначала вопрос «Точно не пришёл?»
 * ([onSent] и сеть — только после «Да, не пришёл»; «Подождать» закрывает окно, не трогая бронь).
 * Что показать после ответа, решает карточка ([DriverBoardingControls]):
 * [onSent] — неявка отмечена, [onBoarded] — сервер ответил, что посадка уже отмечена (второй
 * телефон водителя, очередь без сети): аргумент — сколько секунд ещё можно отменить отметку
 * (`boarding_undo_sec` из отказа), чтобы карточка показала «Отменить» с верным отсчётом, а не
 * сразу разбор (приёмка PR174). Отказ `no_show_too_early` (на телефоне время ушло вперёд или выезд
 * перенесли) — словами сервера в тосте, как любой другой отказ.
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
    var asking by remember(bookingId) { mutableStateOf(false) }
    val failMsg = appText("Не получилось отметить. Проверь сеть.", "Билдәләп булманы. Селтәрҙе тикшер.")
    OutlinedButton(
        onClick = { asking = true },
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
    if (asking) {
        AlertDialog(
            onDismissRequest = { if (!sending) asking = false },
            containerColor = CanonSurface,
            shape = CanonCardShape,
            title = {
                Text(
                    appText("Точно не пришёл?", "Ысынлап та килмәнеме?"),
                    color = CanonText, fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(
                    appText(
                        "Бронь снимется, место вернётся в продажу. Если пассажир уже идёт — лучше подожди.",
                        "Бронь алына, урын кире һатыуға ҡайта. Пассажир килеп барһа — әҙ генә көт.",
                    ),
                    color = CanonMuted, style = CanonCaption,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !sending,
                    onClick = {
                        if (sending) return@TextButton
                        sending = true
                        scope.launch {
                            ApiClient.markNoShow(bookingId)
                                .onSuccess { asking = false; onSent() }
                                .onFailure { e ->
                                    asking = false
                                    if (e is ApiException && e.detailCode == "passenger_onboard") {
                                        onBoarded(e.detail?.optInt("boarding_undo_sec")?.coerceAtLeast(0) ?: 0)
                                    }
                                    Toast.makeText(ctx, serverSaid(e, failMsg), Toast.LENGTH_LONG).show()
                                }
                            sending = false
                        }
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(appText("Да, не пришёл", "Эйе, килмәне"), color = CanonRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !sending,
                    onClick = { asking = false },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(appText("Подождать", "Көтөргә"), color = CanonGreen2, fontWeight = FontWeight.Bold)
                }
            },
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
