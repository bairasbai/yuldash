package com.yuldash.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.runtime.key as protocolKey
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.yuldash.app.data.ApiClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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

/** Local function references can compare equal while capturing different targets. */
@Composable
private fun <T> winterLatest(value: T): State<T> {
    val state = remember { mutableStateOf(value, referentialEqualityPolicy()) }
    state.value = value
    return state
}

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
    ownerGeneration: Long? = null,
    isCurrentTarget: () -> Boolean = { true },
    onArm: suspend (Long) -> Result<String>,
) {
    val generation = remember { ownerGeneration ?: ApiClient.queueSessionGeneration() }
    val session by ApiClient.sessionChanges.collectAsState()
    val currentStart by winterLatest(startMs)
    val currentActive by winterLatest(active)
    val currentTarget by winterLatest(isCurrentTarget)
    val currentArm by winterLatest(onArm)
    val currentDelay by rememberUpdatedState(armAfterMs)
    val currentAsked by rememberUpdatedState(asked)
    val currentShow by rememberUpdatedState(show)
    val scope = rememberCoroutineScope()
    fun current() = scope.isActive && ApiClient.isCurrentSession(generation) && currentTarget() && currentActive()
    if (key == null || (key is Int && key <= 0) || session != generation ||
        (ownerGeneration != null && ownerGeneration != generation)) return
    val lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(key, lifecycleOwner, generation) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (!currentAsked.value) {
                val started = currentStart()
                if (current() && started != null && System.currentTimeMillis() - started >= currentDelay) {
                    val attemptAsked = currentAsked
                    val attemptShow = currentShow
                    val result = currentArm(generation)
                    // A paused/disposed/retired caller cannot publish a late result.
                    ApiClient.runIfCurrentSession(generation) {
                        if (isActive && current() && currentAsked === attemptAsked && currentShow === attemptShow) {
                            when (result.getOrNull()) {
                                "check_sent", "waiting", "no_share", "escalated" -> {
                                    attemptAsked.value = true
                                    attemptShow.value = true
                                }
                                "closed", "ok" -> { attemptAsked.value = true; attemptShow.value = false }
                                // Failed, too early or unknown: retry on the next tick/resume.
                            }
                        }
                    }
                    if (currentAsked.value) break
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
    target: Any?,
    ownerGeneration: Long? = null,
    isCurrentTarget: () -> Boolean = { true },
    onArrived: suspend (Long) -> Result<Unit>,
) {
    val generation = remember { ownerGeneration ?: ApiClient.queueSessionGeneration() }
    val session by ApiClient.sessionChanges.collectAsState()
    if (!show.value || target == null || (target is Int && target <= 0) ||
        session != generation || !ApiClient.isCurrentSession(generation) ||
        (ownerGeneration != null && ownerGeneration != generation) || !isCurrentTarget()) return
    protocolKey(target, generation, show) {
        WinterArrivalDialogContent(show, generation, isCurrentTarget, onArrived)
    }
}

@Composable
private fun WinterArrivalDialogContent(
    show: MutableState<Boolean>, generation: Long, isCurrentTarget: () -> Boolean,
    onArrived: suspend (Long) -> Result<Unit>,
) {
    val scope = rememberCoroutineScope()
    val currentTarget by winterLatest(isCurrentTarget)
    val currentArrived by winterLatest(onArrived)
    var closed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val failMessage = appText("Не получилось. Повтори.", "Булманы. Ҡабатла.")
    fun current() = scope.isActive && !closed && show.value && currentTarget()
    fun commit(action: () -> Unit) { ApiClient.runIfCurrentSession(generation) { if (current()) action() } }
    fun dismiss() = commit { if (!busy) { closed = true; show.value = false } }
    fun arrive() = commit {
        if (!busy) {
            busy = true
            error = null
            scope.launch {
                try {
                if (!current() || !ApiClient.isCurrentSession(generation)) return@launch
                val result = currentArrived(generation)
                commit {
                    result.onSuccess { closed = true; show.value = false }
                        .onFailure { error = serverSaid(it, failMessage) }
                }
                } finally {
                    ApiClient.runIfCurrentSession(generation) { if (scope.isActive) busy = false }
                }
            }
        }
    }
    AlertDialog(
        onDismissRequest = ::dismiss,
        containerColor = CanonSurface,
        icon = { Icon(Icons.Default.AcUnit, contentDescription = null, tint = CanonGreen2) },
        title = {
            Text(
                appText("Ты доехал(а)?", "Барып еттеңме?"),
                color = CanonText, fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column {
                Text(
                    appText(
                        "Отметь, что всё хорошо — и близкие не будут волноваться.",
                        "Бөтәһе лә яҡшы тип билдәлә — яҡындарың борсолмаҫ.",
                    ),
                    color = CanonMuted, fontSize = CanonBody.fontSize, lineHeight = CanonBody.lineHeight,
                )
                error?.let { Text(it, color = CanonRed, fontSize = CanonBody.fontSize) }
            }
        },
        confirmButton = {
            TextButton(onClick = ::arrive, enabled = !busy) {
                Text(
                    appText("Доехал ✓", "Барып еттем ✓"),
                    color = CanonGreen2, fontWeight = FontWeight.Bold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = ::dismiss, enabled = !busy) {
                Text(appText("Ещё в пути", "Юлдамын"), color = CanonMuted)
            }
        },
    )
}
