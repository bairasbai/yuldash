package com.yuldash.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Сколько водитель ждёт на месте. Часы, а не минуты: человек мыслит «на часок» / «до вечера». */
private val ROUND_TRIP_WAIT_HOURS = listOf(1, 2, 3, 4)

@Composable
internal fun ColumnScope.RoundTripWaitSelector(
    checked: Boolean,
    maxHours: Int,
    waitMin: Int,
    onWaitMin: (Int) -> Unit,
) {
    // Выбор времени показываем только включённым: пока переключатель выключен, эти
    // кнопки — лишний шум на и без того плотном экране заказа.
    AnimatedVisibility(
        visible = checked,
        enter = expandVertically(tween(CanonMotion.NORMAL)) + fadeIn(tween(CanonMotion.NORMAL)),
        exit = shrinkVertically(tween(CanonMotion.QUICK)) + fadeOut(tween(CanonMotion.QUICK)),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
            Text(
                appText("Сколько ждать на месте", "Урында күпме көтөргә"),
                color = CanonMuted, style = CanonCaption,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ROUND_TRIP_WAIT_HOURS.filter { it <= maxHours }.forEach { h ->
                    RoundTripWaitChip(
                        hours = h,
                        selected = waitMin == h * 60,
                        onClick = { onWaitMin(h * 60) },
                        modifier = Modifier.weight(1f).testTag("taxiRoundTripWait${h * 60}"),
                    )
                }
            }
            // Ожидание отдельно не оплачивается — оно уже оплачено тем, что второй конец
            // достался водителю без поиска пассажира. Но человек должен понимать рамку:
            // это не «жди сколько хочешь», у водителя есть смена.
            Spacer(Modifier.height(8.dp))
            Text(
                appText(
                    "Ждать дольше водитель не сможет — у него смена. Задержишься — поездку придётся заказать заново.",
                    "Йөрөтөүсе оҙағыраҡ көтә алмай — уның сменаһы бар. Һуңлаһаң, сәфәрҙе яңынан заказ итергә тура килә.",
                ),
                color = CanonMuted, style = CanonMicro,
            )
        }
    }
}

@Composable
private fun RoundTripWaitChip(
    hours: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // CanonGreen2, а не CanonGreen: второй в тёмной теме превращается в светлую мяту, и белая
    // надпись на нём становится нечитаемой. CanonGreen2 затемнён специально под белый текст.
    val bg by animateColorAsState(if (selected) CanonGreen2 else CanonMint, tween(CanonMotion.QUICK), label = "rtChipBg")
    val fg by animateColorAsState(if (selected) CanonOnAccent else CanonText, tween(CanonMotion.QUICK), label = "rtChipFg")
    Surface(
        color = bg,
        shape = CanonTinyShape,
        modifier = modifier.heightIn(min = 48.dp).semantics { this.selected = selected }.clickable(onClick = onClick),   // тач-цель ≥48dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                appText("$hours ч", "$hours сәғ"),
                color = fg,
                style = if (selected) CanonBodyStrong else CanonBody,
            )
        }
    }
}
