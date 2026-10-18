package com.yuldash.app

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.ApiException
import com.yuldash.app.data.BoardingApi
import com.yuldash.app.data.NoShowTerms
import com.yuldash.app.data.noShowTermsAfterRefusal
import kotlinx.coroutines.delay
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
// До этого момента кнопка неактивна, под ней — спокойная подпись [NoShowWaitNote]; нажатие —
// подтверждение; отказ сервера `no_show_too_early` — его словами в тосте, а срок из отказа — в карточку.
//
// Пассажиры на остановках (решение Александра 18.10.2026): срок считается от позднего из «выезд» и
// первого «Подъезжаю» по этой брони, а на поездке с остановками без «Подъезжаю» неявки нет вовсе —
// человека на остановке сажают позже выезда, и 15 минут от начальной точки его не защищали. Тогда
// на месте «Не пришёл» — «Подъезжаю» ([ArrivingButton]), под ним — [NoShowNeedsArrivingNote].

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
 * С какого момента (мс по часам телефона) открыть «Не пришёл» по сроку от сервера: поздний из
 * `no_show_at` и «секунды из ответа» ([NoShowTerms.waitUntilMs]) — если часы телефона спешат, ждём
 * по секундам сервера. null — срока нет (решает сервер).
 */
internal fun NoShowTerms.opensAtMs(): Long? = listOfNotNull(noShowOpensAtMs(noShowAt), waitUntilMs).maxOrNull()

/**
 * Через сколько миллисекунд снова посмотреть на часы; null — больше не нужно: срок наступил или его
 * нет. Не реже раза в секунду: подпись сменится кнопкой в нужную секунду, даже если часы перевели.
 */
internal fun noShowTickMs(opensAtMs: Long?, nowMs: Long): Long? =
    opensAtMs?.takeIf { nowMs < it }?.let { (it - nowMs).coerceAtMost(1_000L) }

/**
 * Живое «срок уже наступил?» для карточки. Часы тикают, ТОЛЬКО пока срок впереди (приёмка PR182:
 * раньше каждая карточка пересчитывала время раз в секунду, даже когда кнопка давно открыта или
 * срока нет вовсе), и только на видимом экране: в фоне ждать нечего, при возврате время берётся
 * заново. Карточка перерисовывается один раз — в момент перехода.
 */
@Composable
internal fun rememberNoShowOpen(opensAtMs: Long?): State<Boolean> {
    val open = remember(opensAtMs) { mutableStateOf(noShowTickMs(opensAtMs, System.currentTimeMillis()) == null) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(opensAtMs, owner) {
        if (open.value) return@LaunchedEffect   // срок наступил или его нет — часы не заводим
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var wait = noShowTickMs(opensAtMs, System.currentTimeMillis())
            while (wait != null) {
                delay(wait)
                wait = noShowTickMs(opensAtMs, System.currentTimeMillis())
            }
            open.value = true
        }
    }
    return open
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
 * Поездка с остановками, а «Подъезжаю» по этой брони ещё не было: неявки нет, пока пассажир не
 * получил «Водитель подъезжает» (решение 18.10.2026). Спокойная подпись под «Подъезжаю».
 */
@Composable
internal fun NoShowNeedsArrivingNote(modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.DirectionsCar, contentDescription = null, tint = CanonMuted, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(CanonSpace.sm))
        Text(
            appText(
                "Сначала нажми «Подъезжаю» — пассажир получит уведомление",
                "Башта «Яҡынлашам»ға баҫ — пассажир хәбәр алыр",
            ),
            color = CanonMuted, style = CanonCaption,
        )
    }
}

/**
 * «Подъезжаю» на карточке «Едут с тобой» — на поездке с остановками это шаг перед «Не пришёл».
 * Раньше «Подъезжаю» была только на экране поездки («Открыть поездку»), и водитель у остановки
 * читал бы «Сначала нажми «Подъезжаю»», не видя кнопки. Тот же запрос, что на экране поездки:
 * пассажиру уходит «Водитель подъезжает», ответ несёт новый срок — его и показывает карточка.
 */
@Composable
internal fun ArrivingButton(bookingId: Int, onArrived: (NoShowTerms) -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var sending by remember(bookingId) { mutableStateOf(false) }
    val failMsg = appText("Не удалось сохранить статус. Проверь сеть.", "Хәлде һаҡлап булманы. Селтәрҙе тикшер.")
    val sentMsg = appText("Пассажир уведомлён", "Пассажир хәбәрҙар ителде")
    AppButton(
        text = appText("Подъезжаю", "Яҡынлашам"),
        onClick = {
            if (!sending) {
                sending = true
                scope.launch {
                    BoardingApi.markArriving(bookingId)
                        .onSuccess { terms ->
                            Toast.makeText(ctx, sentMsg, Toast.LENGTH_SHORT).show()
                            onArrived(terms)
                        }
                        .onFailure { e -> Toast.makeText(ctx, serverSaid(e, failMsg), Toast.LENGTH_LONG).show() }
                    sending = false
                }
            }
        },
        modifier = modifier,
        style = AppButtonStyle.Secondary,
        icon = Icons.Default.DirectionsCar,
        loading = sending,
        height = 48.dp,
    )
}

/**
 * «Не пришёл» — водитель отмечает неявку пассажира (no-show): /bookings/{id}/no-show, места
 * возвращаются на сервере. Нажатие само ничего не отправляет: сначала вопрос «Точно не пришёл?»
 * ([onSent] и сеть — только после «Да, не пришёл»; «Подождать» закрывает окно, не трогая бронь).
 * Что показать после ответа, решает карточка ([DriverBoardingControls]):
 * [onSent] — неявка отмечена, [onBoarded] — сервер ответил, что посадка уже отмечена (второй
 * телефон водителя, очередь без сети): аргумент — сколько секунд ещё можно отменить отметку
 * (`boarding_undo_sec` из отказа), чтобы карточка показала «Отменить» с верным отсчётом, а не
 * сразу разбор (приёмка PR174). Отказы `no_show_too_early` (часы телефона спешат, строка списка
 * устарела, «Подъезжаю» нажат с другого телефона) и `no_show_needs_arriving` — словами сервера в
 * тосте, а новый срок из отказа уходит в [onTerms]: карточка снова показывает подпись, а не кнопку.
 * [locked] — срок ещё не наступил: кнопка на своём месте, но неактивна и не красная, а в момент
 * срока плавно оживает (место под ней занято заранее — «Сел» рядом не сдвигается, приёмка PR182).
 */
@Composable
internal fun NoShowButton(
    bookingId: Int,
    onSent: () -> Unit,
    onBoarded: (undoSec: Int) -> Unit,
    modifier: Modifier = Modifier,
    locked: Boolean = false,
    onTerms: (NoShowTerms) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var sending by remember(bookingId) { mutableStateOf(false) }
    var asking by remember(bookingId) { mutableStateOf(false) }
    val failMsg = appText("Не получилось отметить. Проверь сеть.", "Билдәләп булманы. Селтәрҙе тикшер.")
    val tint by animateColorAsState(if (locked) CanonMuted else CanonRed, tween(CanonMotion.NORMAL), label = "noShowTint")
    val edge by animateColorAsState(if (locked) CanonBorder else CanonRed, tween(CanonMotion.NORMAL), label = "noShowEdge")
    OutlinedButton(
        onClick = { asking = true },
        enabled = !sending && !locked,
        shape = CanonFieldShape,
        border = BorderStroke(1.dp, edge),
        modifier = modifier.heightIn(min = 48.dp),
    ) {
        Text(
            appText("Не пришёл", "Килмәне"),
            color = tint, style = CanonButton, textAlign = TextAlign.Center,
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
                                    if (e is ApiException) noShowTermsAfterRefusal(e.detailCode, e.detail)?.let(onTerms)
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
