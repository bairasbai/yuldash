package com.yuldash.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.yuldash.app.data.Outbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Как часто приложение на экране пробует отправить очередь, пока в ней что-то есть. */
internal const val OUTBOX_RETRY_MS = 30_000L

/**
 * Очередь исходящих («Жду у подъезда», «сел», «доехал») не ждёт, пока человек вернётся в поездку.
 * Раньше она уходила при запуске приложения и на экране поездки: написал без связи с сервером, ушёл
 * на карту, сервер вернулся — водитель не получал сообщения, пока человек снова не открыл поездку
 * (ревью 23.10.2026). Теперь, пока приложение на экране и очередь не пуста, пробуем раз в 30 с:
 * тот же `Outbox.flush` (по порядку, тот же ключ запроса — сервер не задвоит), а в фоне — ничего.
 * [sessionKey] — смена входа начинает цикл заново: очередь чужого входа уже стёрта при выходе.
 */
@Composable
internal fun OutboxAutoFlush(sessionKey: Any?) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner, sessionKey) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                // Чтение и запись очереди на диск — не на главном потоке.
                withContext(Dispatchers.IO) { if (Outbox.hasPending(context)) Outbox.flush(context) }
                delay(OUTBOX_RETRY_MS)
            }
        }
    }
}
