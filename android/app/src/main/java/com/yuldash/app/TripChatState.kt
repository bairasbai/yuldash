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
import kotlinx.coroutines.delay
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
    private val delivering = mutableMapOf<Int, Any>()
    private val acknowledged = mutableSetOf<Int>()
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

    /**
     * Лента с сервера + свои ещё не подтверждённые сообщения («В очереди», «Не доставлено»). Раньше
     * лента заменяла список целиком, и неотправленное пропадало с экрана (приёмка 18.10.2026).
     */
    private fun applyServer(server: List<MessageDto>) {
        messages = server + messages.filter { it.id < 0 && it.id !in acknowledged }
    }

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
                    // A repeated frame must never consume another same-text pending message.
                    val pending = restAttempts.entries.firstOrNull {
                        inc.senderId == myId && inc.requestId.isNotBlank() &&
                            Outbox.messageRequestKey(it.value) == inc.requestId
                    }
                    if (pending != null) {
                        acknowledged += pending.key
                        failedIds = failedIds - pending.key
                        queuedIds = queuedIds - pending.key
                    }
                    val optIdx = pending?.let { entry -> messages.indexOfFirst { it.id == entry.key } } ?: -1
                    val dto = MessageDto(inc.id, inc.text, inc.senderId, voiceUrl = inc.voiceUrl, deleted = inc.deleted,
                        edited = inc.edited, flag = inc.flag, fromAdmin = inc.fromAdmin)
                    messages = when {
                        inc.id > 0 && messages.any { it.id == inc.id } -> messages.filterNot { it.id == pending?.key }
                        optIdx >= 0 -> messages.toMutableList().also { it[optIdx] = dto }
                        else -> messages + dto
                    }
                    if (pending != null) withContext(Dispatchers.IO) {
                        Outbox.acknowledge(context, pending.value, tripSession)
                    }
                }
            },
            onConnected = { connected ->
                scope.launch { if (isActive()) wsConnected = connected }
            },
            // Сервер не принял сообщение (слишком быстрый поток). Помечаем его
            // «Не доставлено · Повторить» — тем же способом, что и отказ по обычному
            // запросу. Молча оставить нельзя: оно висело бы как отправленное.
            onRejectedDetailed = { tempId, key, status, _ ->
                scope.launch {
                    if (!isActive()) return@launch
                    val action = restAttempts[tempId] ?: return@launch
                    if (key != Outbox.messageRequestKey(action)) return@launch
                    val permanent = status in PERMANENT_REJECTIONS
                    if (permanent) {
                        // Invalidate the old timeout before permitting a manual same-key retry.
                        delivering.remove(tempId)
                        withContext(Dispatchers.IO) { Outbox.acknowledge(context, action, tripSession) }
                        if (!isActive()) return@launch
                        queuedIds = queuedIds - tempId
                        failedIds = failedIds + tempId
                    } else queuedIds = queuedIds + tempId
                    toast(if (status == 429) texts.value.tooFast else texts.value.sendFail, long = false)
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
            .onSuccess { applyServer(it); markBaseline(it) }
            .onFailure { historyError = true }
        historyLoading = false
        return true
    }

    /**
     * Сокет подключился — сервер снова на связи. Сперва отправляем то, что ждало в очереди: раньше оно
     * уходило лишь при следующем запуске, если интернет у телефона не пропадал, а лежал сервер
     * (приёмка 18.10.2026). После обрыва дотягиваем пропущенные сообщения по REST: живой приём мог
     * простоять, пока сокет был мёртв. Первый коннект ленту не трогает — историю уже грузит экран.
     */
    suspend fun refreshAfterReconnect() {
        if (!isActive() || !wsConnected) return
        val reconnected = wasEverConnected
        wasEverConnected = true
        if (flushQueued() || !reconnected || !isActive()) return
        bookingId?.let { id -> ApiClient.getMessages(id).onSuccess { if (isActive()) applyServer(it) } }
    }

    /**
     * Отправить очередь (сообщения и статусы, по порядку): сокет переподключился, опрос поездки снова
     * дошёл до сервера, сеть вернулась, экран открыли. Ключ запроса тот же, что у первой попытки, —
     * сервер не задвоит, даже если сообщение уже ушло другим путём. Ушедшее снимаем с «В очереди»,
     * отказанное помечаем «Не доставлено», ленту перечитываем — неотправленное в ней остаётся.
     * true — очередь изменилась.
     */
    suspend fun flushQueued(): Boolean {
        if (!isActive()) return false
        val refused = ConcurrentHashMap.newKeySet<Long>()
        val changed = Outbox.flush(context, expectedGeneration = tripSession, shouldContinue = isActive) { refused += it.id }
        if (!isActive()) return changed
        val waiting = queuedIds.mapNotNull { id -> restAttempts[id]?.let { id to it } }
        val left = withContext(Dispatchers.IO) { waiting.filterNot { (_, action) -> Outbox.contains(context, action) } }
        if (!isActive()) return changed
        val (rejected, sent) = left.partition { (_, action) ->
            action.id in refused || Outbox.wasRefused(action, tripSession)
        }
        acknowledged += sent.map { it.first }
        queuedIds = queuedIds - left.map { it.first }.toSet()
        failedIds = (failedIds - sent.map { it.first }.toSet()) + rejected.map { it.first }
        val queueChanged = changed || left.isNotEmpty()
        if (queueChanged) bookingId?.let { id -> ApiClient.getMessages(id).onSuccess { if (isActive()) applyServer(it) } }
        return queueChanged
    }

    fun retryHistory() { historyTick++ }

    // ── Отправка ────────────────────────────────────────────────────────────────────────────
    // Доставка одного сообщения. Сперва WS (если жив), иначе REST. Ошибку НЕ глотаем:
    // - сетевой сбой (нет связи на трассе) → кладём в очередь (Outbox), помечаем «в очереди» —
    //   отправится само при появлении сети (F11), ничего не теряется;
    // - ошибка сервера → «Не доставлено · Повторить» (ручной повтор, как прежде).
    private fun deliver(tempId: Int, text: String) {
        val bid = bookingId ?: return
        if (!isActive() || tempId in acknowledged || tempId in delivering) return
        val attempt = Any()
        delivering[tempId] = attempt
        val action = restAttempts.getOrPut(tempId) { Outbox.newMessage(bid, text) }
        scope.launch {
            try {
                // Commit before network: process death must not forget an accepted WS send.
                val saved = withContext(Dispatchers.IO) { Outbox.enqueue(context, action, tripSession) }
                if (!isActive()) return@launch
                if (!saved) {
                    failedIds = failedIds + tempId
                    toast(texts.value.queueSaveFail, long = false)
                    return@launch
                }
                val sentViaWs = wsConnected && socket?.send(action.payload, tempId, Outbox.messageRequestKey(action)) == true
                if (sentViaWs) delay(WS_ACK_TIMEOUT_MS)
                if (!isActive() || tempId in acknowledged || delivering[tempId] !== attempt) return@launch
                // Same key for uncertain WS delivery, HTTP retry and startup queue flush.
                ApiClient.sendQueuedAction(action, tripSession)
                    .onSuccess {
                        acknowledged += tempId
                        withContext(Dispatchers.IO) { Outbox.acknowledge(context, action, tripSession) }
                        if (isActive()) {
                            failedIds = failedIds - tempId
                            queuedIds = queuedIds - tempId
                            ApiClient.getMessages(bid).onSuccess { if (isActive()) applyServer(it) }
                        }
                    }
                    .onFailure { e ->
                        if (!isSessionCurrent() || tempId in acknowledged) return@onFailure
                        val permanent = e is ApiException && e.status in PERMANENT_REJECTIONS
                        if (permanent) withContext(Dispatchers.IO) { Outbox.acknowledge(context, action, tripSession) }
                        if (!isActive()) return@onFailure
                        if (permanent) failedIds = failedIds + tempId else queuedIds = queuedIds + tempId
                        toast(if (permanent) serverSaid(e, texts.value.sendFail) else texts.value.queued, long = permanent)
                    }
            } finally { if (delivering[tempId] === attempt) delivering.remove(tempId) }
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
                result.onSuccess { ApiClient.getMessages(bid).onSuccess { if (isActive()) applyServer(it) } }
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
            result.onSuccess { ApiClient.getMessages(bid).onSuccess { if (isActive()) applyServer(it) } }
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
                result.onSuccess { ApiClient.getMessages(bid).onSuccess { if (isActive()) applyServer(it) } }
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
                result.onSuccess { ApiClient.getMessages(bid).onSuccess { if (isActive()) applyServer(it) } }
                    .onFailure { if (isActive()) toast(serverSaid(it, texts.value.chatSendFail), long = true) }
            }.onFailure { if (isActive()) toast(serverSaid(it, texts.value.chatSendFail), long = true) }
        }
    }

    private fun toast(text: String, long: Boolean) {
        Toast.makeText(context, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    internal companion object {
        private val PERMANENT_REJECTIONS = setOf(400, 403, 404, 405, 409, 410, 413, 415, 422)
        internal const val WS_ACK_TIMEOUT_MS = 5_000L
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
