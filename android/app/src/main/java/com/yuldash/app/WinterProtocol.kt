package com.yuldash.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/**
 * ❄️ Зимний протокол — общий на все три сценария.
 *
 * Что видит человек. Через какое-то время после начала пути приложение спрашивает:
 * «Ты доехал(а)?». Нажал «Доехал» — всё, тишина. Не ответил полчаса — тем, кого он сам
 * заранее выбрал, уходит SMS «позвони, проверь, всё ли хорошо».
 *
 * Зачем. Зимой трасса Сибай–Уфа — четыре часа. Если человек не доехал, узнать об этом должен
 * кто-то, кроме него самого. Один вопрос и один звонок близкого — этого достаточно, и это
 * не слежка: маршрут и координаты близким не уходят, уходит только «не отметился, позвони».
 *
 * Почему один файл на три экрана. Раньше протокол жил только в попутке, и когда появились
 * такси и доставка, их просто не подключили — человек в такси ехал те же четыре часа без
 * всякой страховки (аудит 2026-08-06). Три копии разошлись бы снова, поэтому диалог и таймер
 * здесь одни, а экраны передают только «чем закончить» — свои вызовы сервера.
 */

/** Запасной срок, если посчитать дорогу нечем: спрашиваем через два часа после старта. */
const val WINTER_FALLBACK_MS = 2 * 60 * 60 * 1000L

/**
 * Таймер вопроса. Ждёт, пока с начала пути пройдёт [armAfterMs], и один раз показывает диалог.
 *
 * [startMs] — когда путь начался (null = ещё не начался, ждём).
 * [active] — путь ещё идёт (закрытую поездку не тревожим).
 * [onArm] — дёрнуть сервер: он сам решит, рано ли ещё (`too_early`) и кому слать пуш.
 */
@Composable
fun WinterArrivalWatcher(
    key: Any?,
    startMs: () -> Long?,
    active: () -> Boolean,
    asked: MutableState<Boolean>,
    show: MutableState<Boolean>,
    armAfterMs: Long = WINTER_FALLBACK_MS,
    onArm: suspend () -> Unit,
) {
    val lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(key, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (!asked.value) {
                val started = startMs()
                if (active() && started != null &&
                    System.currentTimeMillis() >= started + armAfterMs
                ) {
                    asked.value = true
                    show.value = true
                    onArm()
                    break
                }
                delay(60_000)
            }
        }
    }
}

/**
 * Сам вопрос. Две кнопки и ни одной лишней: «Доехал» гасит тревогу, «Ещё в пути» просто
 * закрывает окно — человек за рулём или в дороге не должен разбираться в вариантах.
 */
@Composable
fun WinterArrivalDialog(
    show: MutableState<Boolean>,
    onArrived: () -> Unit,
) {
    if (!show.value) return
    AlertDialog(
        onDismissRequest = { show.value = false },
        containerColor = CanonSurface,
        icon = { Icon(Icons.Default.AcUnit, contentDescription = null, tint = CanonGreen2) },
        title = {
            Text(
                appText("Ты доехал(а)?", "Барып еттеңме?"),
                color = CanonText, fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Text(
                appText(
                    "Отметь, что всё хорошо — и близкие не будут волноваться.",
                    "Бөтәһе лә яҡшы тип билдәлә — яҡындарың борсолмаҫ.",
                ),
                color = CanonMuted, fontSize = CanonBody.fontSize, lineHeight = CanonBody.lineHeight,
            )
        },
        confirmButton = {
            TextButton(onClick = { show.value = false; onArrived() }) {
                Text(
                    appText("Доехал ✓", "Барып еттем ✓"),
                    color = CanonGreen2, fontWeight = FontWeight.Bold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = { show.value = false }) {
                Text(appText("Ещё в пути", "Юлдамын"), color = CanonMuted)
            }
        },
    )
}
