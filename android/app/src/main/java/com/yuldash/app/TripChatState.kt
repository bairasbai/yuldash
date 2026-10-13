package com.yuldash.app

// Состояние чата попутки: лента, отправка WS→REST, очередь без сети, правка/удаление, голос, фото.
//
// Зачем отдельный класс. Раньше всё это жило внутри экрана «Моя поездка» вперемешку с посадкой,
// оплатой и шерингом, а сама переписка была строкой посреди длинного списка: поле ввода висело
// под последним сообщением где-то в середине экрана. Теперь чат — полноэкранный вид, а на
// экране поездки — карточка «Чат по поездке». Оба вида читают ОДИН экземпляр этого класса:
// один сокет, одна история, один черновик — ничего не дублируется и не теряется при переходе.
//
// Логика перенесена из BookingActiveTripScreen.kt 1:1, без переписывания: каждое обращение к
// сети по-прежнему сверяется с `isActive` до и после запроса (тот же вход, поездка не закрыта,
// экран жив) — поздний ответ чужому аккаунту или закрытой поездке ничего не меняет.

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.ApiException
import com.yuldash.app.data.ChatSocket
import com.yuldash.app.data.MessageDto
import com.yuldash.app.data.Outbox
import com.yuldash.app.data.OutboxAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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
)

@Composable
internal fun rememberTripChatTexts(queued: String, queueSaveFail: String): State<TripChatTexts> = rememberUpdatedState(
    TripChatTexts(
        sendFail = appText("Сообщение не отправлено", "Хәбәр ебәрелмәне"),
        tooFast = appText("Слишком быстро. Подожди минуту и продолжи.", "Артыҡ тиҙ. Бер минут көт тә дауам ит."),
        queued = queued,
        queueSaveFail = queueSaveFail,
        chatSendFail = appText("Не отправилось. Повтори.", "Ебәрелмәне. Ҡабатла."),
        chatActionFail = appText("Не получилось. Повтори.", "Булманы. Ҡабатла."),
        voiceSaved = appText("Голос записан", "Тауыш яҙылды"),
    )
)

@Stable
internal class TripChatState(
    private val context: Context,
    val bookingId: Int?,
    private val tripSession: Long,
    /** Корутины экрана-владельца: ушёл экран — ушли и они (поздний ответ применять некому). */
    internal val scope: CoroutineScope,
    /** Тот же вход, поездка не завершена и не отменена, экран жив. */
    private val isActive: () -> Boolean,
    /** Только «тот же вход» — для сбоев, которые нужно сохранить в очередь и после закрытия поездки. */
    private val isSessionCurrent: () -> Boolean,
    private val texts: State<TripChatTexts>,
) {
    val myId: Int = ApiClient.myUserId() ?: -1

    internal val messagesState: MutableState<List<MessageDto>> = mutableStateOf(emptyList())
    var messages: List<MessageDto> by messagesState
    // Оптимистичные (ещё не подтверждённые сервером) сообщения получают уникальный
    // отрицательный id (-2, -3, …). failedIds — те, что не доставились (показываем «Повторить»).
    internal val failedIdsState: MutableState<Set<Int>> = mutableStateOf(emptySet())
    var failedIds: Set<Int> by failedIdsState
    // F11: сообщения, поставленные в очередь при отсутствии сети (уйдут авто-ретраем).
    var queuedIds: Set<Int> by mutableStateOf(emptySet())
    private var tempSeq = -2
    private val restAttempts = mutableMapOf<Int, OutboxAction>()
    internal val wsConnectedState: MutableState<Boolean> = mutableStateOf(false)
    var wsConnected: Boolean by wsConnectedState
    private var wasEverConnected = false
    // Состояние первой загрузки истории: спиннер, ошибка (с «Повторить»), пусто.
    var historyLoading: Boolean by mutableStateOf(bookingId != null)
    var historyError: Boolean by mutableStateOf(false)
    /** bump → перезагрузить историю (кнопка «Повторить»). */
    var historyTick: Int by mutableIntStateOf(0)
    // Черновик и режим правки живут здесь, а не в виде: ушёл в поездку и вернулся — текст на месте.
    var draft: String by mutableStateOf("")
        private set
    var editingId: Int? by mutableStateOf(null)   // id редактируемого сообщения (null — обычная отправка)
        private set

    /** Удалённые, голос и непустой текст видимы; «пустые» технические — нет. */
    val visibleMessages: List<MessageDto>
        get() = messages.filter { it.deleted || it.voiceUrl != null || it.text.isNotBlank() }

    // ── Непрочитанные ───────────────────────────────────────────────────────────────────────
    // Сервер «прочитано» для чата брони не хранит, поэтому считаем честно и локально: сколько
    // сообщений собеседника пришло после того, как человек последний раз смотрел в ленту.
    // Метка живёт в памяти процесса и привязана ко входу: другой аккаунт её не увидит.
    // Нет метки (первый показ) — уже загруженная история считается прочитанной: лучше не
    // показать бейдж, чем показать «12 новых» на переписку, которую человек давно прочёл.
    private val seenKey = "$tripSession:${bookingId ?: 0}"
    var seenId: Int by mutableIntStateOf(seenMarks[seenKey] ?: NO_MARK)
        private set

    val unread: Int
        get() = if (seenId == NO_MARK) 0
        else visibleMessages.count { it.id > seenId && it.senderId != myId && !it.deleted }

    /** Лента на экране и внизу — всё, что в ней есть, прочитано. До истории метку не ставим. */
    fun markSeen() {
        if (historyLoading) return
        val top = messages.maxOfOrNull { it.id } ?: 0
        if (top > seenId) {
            seenId = top
            seenMarks[seenKey] = top
        }
    }

    private fun markBaseline(history: List<MessageDto>) {
        if (seenId != NO_MARK) return
        seenId = history.maxOfOrNull { it.id } ?: 0
        seenMarks[seenKey] = seenId
    }

    // ── Живой канал ─────────────────────────────────────────────────────────────────────────
    // Realtime — по WebSocket: входящие добавляем живьём; эхо своего сообщения заменяет оптимистичное.
    val socket: ChatSocket? = bookingId?.let { id ->
        ChatSocket(
            bookingId = id,
            expectedGeneration = tripSession,
            onMessage = { inc ->
                // WS-колбэк приходит с фонового потока OkHttp → правку Compose-state делаем на main
                // (read-modify-write `messages` иначе может потерять обновление при гонке потоков).
                scope.launch {
                    if (!isActive()) return@launch
                    // оптимистичное = отрицательный id, не помеченное как «не доставлено», моё, тот же текст
                    val optIdx = messages.indexOfFirst { it.id < 0 && it.id !in failedIds && it.senderId == myId && it.text == inc.text }
                    val dto = MessageDto(inc.id, inc.text, inc.senderId, flag = inc.flag, fromAdmin = inc.fromAdmin)
                    messages = when {
                        optIdx >= 0 -> messages.toMutableList().also { it[optIdx] = dto }
                        inc.id > 0 && messages.any { it.id == inc.id } -> messages   // дубль по id — пропустить
                        else -> messages + dto
                    }
                }
            },
            onConnected = { connected ->
                scope.launch { if (isActive()) wsConnected = connected }
            },
            // Сервер не принял сообщение (слишком быстрый поток). Помечаем его
            // «Не доставлено · Повторить» — тем же способом, что и отказ по обычному
            // запросу. Молча оставить нельзя: оно висело бы как отправленное.
            onRejected = { tempId, _ ->
                scope.launch {
                    if (!isActive()) return@launch
                    failedIds = failedIds + tempId
                    toast(texts.value.tooFast, long = false)
                }
            },
        )
    }

    fun canWrite(): Boolean = isActive()

    /**
     * Первая загрузка истории (и «Повторить»). Звено цепочки экрана поездки: история → код
     * посадки → детали брони. false — экран уже не тот, цепочку дальше не ведём.
     */
    suspend fun loadHistory(id: Int): Boolean {
        historyLoading = true
        historyError = false
        val historyResult = ApiClient.getMessages(id)
        // Каждый запрос защищён отдельно, но следующий иначе захватит уже новый аккаунт.
        if (!isActive()) return false
        historyResult
            .onSuccess { messages = it; markBaseline(it) }
            .onFailure { historyError = true }
        historyLoading = false
        return true
    }

    /**
     * После авто-реконнекта WS (был обрыв → связь вернулась) дотягиваем пропущенные сообщения по REST:
     * живой приём мог простоять, пока сокет был мёртв. Первый коннект не трогаем — историю уже грузит экран.
     */
    suspend fun refreshAfterReconnect() {
        if (!isActive()) return
        if (wsConnected) {
            if (wasEverConnected) {
                bookingId?.let { id -> ApiClient.getMessages(id).onSuccess { if (isActive()) messages = it } }
            }
            wasEverConnected = true
        }
    }

    fun retryHistory() { historyTick++ }

    // ── Отправка ────────────────────────────────────────────────────────────────────────────
    // Доставка одного сообщения. Сперва WS (если жив), иначе REST. Ошибку НЕ глотаем:
    // - сетевой сбой (нет связи на трассе) → кладём в очередь (Outbox), помечаем «в очереди» —
    //   отправится само при появлении сети (F11), ничего не теряется;
    // - ошибка сервера → «Не доставлено · Повторить» (ручной повтор, как прежде).
    private fun deliver(tempId: Int, text: String) {
        val bid = bookingId ?: return
        if (!isActive()) return
        val queueSession = tripSession
        val ws = socket
        // tempId уходит на сервер: если он откажется принять сообщение, вернёт этот же номер,
        // и мы пометим «Не доставлено» именно это сообщение (см. onRejected).
        val sentViaWs = tempId !in restAttempts && wsConnected && ws != null && ws.send(text, tempId)
        if (sentViaWs) return   // эхо WS заменит оптимистичное сообщение настоящим
        val action = restAttempts.getOrPut(tempId) { Outbox.newMessage(bid, text) }
        scope.launch {
            if (!isActive()) return@launch
            ApiClient.sendMessage(bid, action.payload, Outbox.messageRequestKey(action))
                .onSuccess { if (isActive()) ApiClient.getMessages(bid).onSuccess { if (isActive()) messages = it } }
                .onFailure { e ->
                    if (!isSessionCurrent()) return@onFailure
                    if (e is ApiException) {
                        if (!isActive()) return@onFailure
                        failedIds = failedIds + tempId
                        // Сервер знает причину: чат закрылся после поездки, собеседник в блокировке.
                        // «Не отправилось» об этом молчало, и человек писал в пустоту.
                        toast(serverSaid(e, texts.value.sendFail), long = true)
                    } else {
                        // Нет сети → в очередь на авто-ретрай. Сообщение остаётся на экране с меткой «в очереди».
                        val saved = withContext(Dispatchers.IO) { Outbox.enqueue(context, action, queueSession) }
                        if (!isActive()) return@onFailure
                        if (saved) queuedIds = queuedIds + tempId else failedIds = failedIds + tempId
                        toast(if (saved) texts.value.queued else texts.value.queueSaveFail, long = false)
                    }
                }
        }
    }

    /** Текст и готовая фраза — один надёжный путь: WS→REST, ошибка не теряется. */
    fun sendText(text: String) {
        val t = text.trim()
        if (t.isEmpty() || bookingId == null || !isActive()) return
        val tempId = tempSeq
        tempSeq -= 1
        messages = messages + MessageDto(tempId, t, myId)   // показываем сразу (оптимистично)
        deliver(tempId, t)
    }

    fun retry(tempId: Int, text: String) {
        if (!isActive()) return
        failedIds = failedIds - tempId
        deliver(tempId, text)
    }

    fun updateDraft(value: String) { if (isActive()) draft = value }

    fun startEdit(message: MessageDto) {
        if (isActive()) { editingId = message.id; draft = message.text }
    }

    fun cancelEdit() { editingId = null; draft = "" }

    /** «Отправить» в строке ввода: правка выбранного сообщения или новое сообщение. */
    fun submitDraft() {
        val t = draft.trim()
        val bid = bookingId
        if (t.isEmpty() || bid == null || !isActive()) return
        val eid = editingId
        if (eid != null) {
            scope.launch {
                if (!isActive()) return@launch
                val result = ApiClient.editMessage(bid, eid, t)
                if (!isActive()) return@launch
                result.onSuccess { ApiClient.getMessages(bid).onSuccess { if (isActive()) messages = it } }
                    .onFailure { if (isActive()) toast(serverSaid(it, texts.value.chatActionFail), long = true) }
            }
            editingId = null
        } else {
            sendText(t)
        }
        draft = ""
    }

    /** who: "me" — убрать у себя, "all" — у обоих. */
    fun delete(messageId: Int, who: String) {
        val bid = bookingId ?: return
        if (!isActive()) return
        scope.launch {
            if (!isActive()) return@launch
            val result = ApiClient.deleteMessage(bid, messageId, who)
            if (!isActive()) return@launch
            result.onSuccess { ApiClient.getMessages(bid).onSuccess { if (isActive()) messages = it } }
                .onFailure { if (isActive()) toast(serverSaid(it, texts.value.chatActionFail), long = true) }
        }
    }

    /** Готовая запись от строки ввода. Файл наш: удаляем его при любом исходе, даже если запуск отменён. */
    fun sendVoice(path: String) {
        val bid = bookingId
        if (bid == null || !isActive()) {
            ApiClient.dropVoiceFile(path)
            return
        }
        toast(texts.value.voiceSaved, long = false)
        scope.launch {
            if (!isActive()) return@launch
            val bytes = withContext(Dispatchers.IO) { runCatching { File(path).readBytes() }.getOrNull() }
                ?: return@launch
            if (!isActive()) return@launch
            val upload = ApiClient.uploadVoice(bytes)
            if (!isActive()) return@launch
            upload.onSuccess { url ->
                val result = ApiClient.sendVoiceMessage(bid, url)
                if (!isActive()) return@launch
                result.onSuccess { ApiClient.getMessages(bid).onSuccess { if (isActive()) messages = it } }
                    .onFailure { if (isActive()) toast(serverSaid(it, texts.value.chatSendFail), long = true) }
            }.onFailure { if (isActive()) toast(serverSaid(it, texts.value.chatSendFail), long = true) }
        // Completion also runs for a launch cancelled before its body starts.
        }.invokeOnCompletion { ApiClient.dropVoiceFile(path) }
    }

    fun sendPhoto(bytes: ByteArray) {
        val bid = bookingId ?: return
        if (!isActive()) return
        scope.launch {
            if (!isActive()) return@launch
            val upload = ApiClient.uploadChatPhoto(bytes)
            if (!isActive()) return@launch
            upload.onSuccess { url ->
                val result = ApiClient.sendPhotoMessage(bid, url)
                if (!isActive()) return@launch
                result.onSuccess { ApiClient.getMessages(bid).onSuccess { if (isActive()) messages = it } }
                    .onFailure { if (isActive()) toast(serverSaid(it, texts.value.chatSendFail), long = true) }
            }.onFailure { if (isActive()) toast(serverSaid(it, texts.value.chatSendFail), long = true) }
        }
    }

    private fun toast(text: String, long: Boolean) {
        Toast.makeText(context, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    internal companion object {
        const val NO_MARK = Int.MIN_VALUE
        private val seenMarks = ConcurrentHashMap<String, Int>()
    }
}

/**
 * Живое соединение чата: сокет открыт, пока поездка не закрыта (done/cancelled — закрываем);
 * после обрыва и возврата связи дотягиваем пропущенное по REST. Вызывается у владельца
 * состояния, а не у вида — переход «поездка ↔ чат» сокет не рвёт.
 */
@Composable
internal fun TripChatConnection(chat: TripChatState, terminal: String?) {
    DisposableEffect(chat.bookingId, terminal) {
        if (terminal == null) chat.socket?.connect()
        else { chat.wsConnected = false; chat.socket?.close() }
        onDispose { chat.socket?.close() }
    }
    LaunchedEffect(chat.wsConnected) { chat.refreshAfterReconnect() }
}
