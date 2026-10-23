package com.yuldash.app

// Обвязка состояния чата попутки (TripChatState.kt): подсказки на двух языках, метка
// «прочитано» и живое соединение у экрана-владельца. Вынесено, чтобы файл состояния не рос.

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yuldash.app.data.MessageDto
import java.util.concurrent.ConcurrentHashMap

/** Подсказки чата. Считаются в композиции (два языка), читаются в момент показа. */
internal class TripChatTexts(
    val sendFail: String,
    val tooFast: String,
    val queued: String,
    val queueSaveFail: String,
    val chatSendFail: String,
    val chatActionFail: String,
    val voiceSaved: String,
    val photoUploadFail: String = chatSendFail,
    val voiceUploadFail: String = chatSendFail,
)

@Composable
internal fun rememberTripChatTexts(queued: String, queueSaveFail: String): State<TripChatTexts> = rememberUpdatedState(
    TripChatTexts(
        sendFail = appText("Сообщение не отправлено", "Хәбәр ебәрелмәне"),
        tooFast = appText("Слишком быстро. Подожди минуту и продолжи.", "Артыҡ тиҙ. Бер минут көт тә дауам ит."),
        queued = queued,
        queueSaveFail = queueSaveFail,
        chatSendFail = appText("Не отправилось. Повтори.", "Ебәрелмәне. Ҡабатла."),
        chatActionFail = appText("Не удалось подтвердить действие. Проверь связь и повтори.", "Ғәмәлде раҫлап булманы. Бәйләнеште тикшереп ҡабатла."),
        voiceSaved = appText("Голос записан", "Тауыш яҙылды"),
        photoUploadFail = appText("Не удалось подтвердить загрузку фото. Проверь связь и выбери фото ещё раз.",
            "Фотоның тейәлеүен раҫлап булманы. Бәйләнеште тикшереп фотоны тағы һайла."),
        voiceUploadFail = appText("Не удалось подтвердить загрузку голосового. Проверь связь и запиши ещё раз.",
            "Тауыш яҙмаһының тейәлеүен раҫлап булманы. Бәйләнеште тикшереп тағы яҙ."),
    )
)

/**
 * Непрочитанные. Сервер «прочитано» для чата брони не хранит, поэтому считаем честно и локально:
 * сколько сообщений собеседника пришло после того, как человек последний раз смотрел в ленту.
 * Метка живёт в памяти процесса и привязана ко входу ([key] — вход и бронь): другой аккаунт её
 * не увидит. Нет метки (первый показ) — уже загруженная история считается прочитанной: лучше не
 * показать бейдж, чем показать «12 новых» на переписку, которую человек давно прочёл.
 */
@Stable
internal class TripChatSeenMark(private val key: String) {
    var id: Int by mutableIntStateOf(marks[key] ?: NO_MARK)
        private set

    fun unread(visible: List<MessageDto>, myId: Int): Int =
        if (id == NO_MARK) 0 else visible.count { it.id > id && it.senderId != myId && !it.deleted }

    fun advance(top: Int) {
        if (top > id) { id = top; marks[key] = top }
    }

    /** Первая загруженная история — точка отсчёта, если метки ещё не было. */
    fun baseline(history: List<MessageDto>) {
        if (id != NO_MARK) return
        id = history.maxOfOrNull { it.id } ?: 0
        marks[key] = id
    }

    private companion object {
        const val NO_MARK = Int.MIN_VALUE
        val marks = ConcurrentHashMap<String, Int>()
    }
}

/**
 * Живое соединение чата: сокет открыт, пока поездка не закрыта (done/cancelled — закрываем);
 * после обрыва и возврата связи дотягиваем пропущенное по REST. Вызывается у владельца
 * состояния, а не у вида — переход «поездка ↔ чат» сокет не рвёт.
 */
@Composable
internal fun TripChatConnection(chat: TripChatState, terminal: String?) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(chat, lifecycle) {
        var stopped = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { stopped = true; chat.continuityLost() }
            else if (event == Lifecycle.Event.ON_START && stopped) { stopped = false; chat.refreshAfterReturn() }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    DisposableEffect(chat.bookingId, terminal) {
        if (terminal == null) chat.socket?.connect()
        else { chat.wsConnected = false; chat.socket?.close() }
        onDispose { chat.socket?.close() }
    }
    LaunchedEffect(chat.wsConnected) { chat.refreshAfterReconnect() }
}
