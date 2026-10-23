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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.ApiException
import com.yuldash.app.data.ChatSocket
import com.yuldash.app.data.MessageDto
import com.yuldash.app.data.Outbox
import com.yuldash.app.data.OutboxAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID

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
    private val historyMutex = Mutex()
    private var historyVersion = 0L
    private var historyPending = false
    private var historyRefreshJob: Job? = null
    // After an invalidation, an unknown row needs this owner's authoritative history.
    // A delayed message/receipt alone cannot reveal a row hidden by another open login.
    private var authoritativeHistory = false
    private val continuityEpoch = AtomicLong()
    private val hiddenMessageIds = mutableSetOf<Int>()
    // Черновик и режим правки живут здесь, а не в виде: ушёл в поездку и вернулся — текст на месте.
    var draft: String by mutableStateOf("")
        private set
    var editingId: Int? by mutableStateOf(null)   // id редактируемого сообщения (null — обычная отправка)
        private set
    var actionBusy: Boolean by mutableStateOf(false)
        private set
    var actionError: String by mutableStateOf("")
        private set
    private var failedDelete: Pair<Int, String>? = null
    private var editVersion = 0L
    private var failedEdit: Pair<Int, Long>? = null
    private data class VoiceAttempt(val url: String, val key: String, val epoch: Long, var attempted: Boolean = false)
    // Only an already-uploaded recording in this screen, never a durable media queue.
    private var failedVoice: VoiceAttempt? = null
    private val activeVoicePaths = ConcurrentHashMap.newKeySet<String>()
    val canRetryAction: Boolean
        get() = !actionBusy && (failedDelete != null || failedVoice != null ||
            failedEdit?.let { it.first == editingId && it.second == editVersion } == true)

    /** Удалённые, голос и непустой текст видимы; «пустые» технические — нет. */
    val visibleMessages: List<MessageDto>
        get() = messages.filter { it.deleted || it.voiceUrl != null || it.text.isNotBlank() }

    /**
     * Лента с сервера + свои ещё не подтверждённые сообщения («В очереди», «Не доставлено»). Раньше
     * лента заменяла список целиком, и неотправленное пропадало с экрана (приёмка 18.10.2026).
     */
    private fun applyServer(server: List<MessageDto>) {
        messages = server.filterNot { it.id in hiddenMessageIds } +
            messages.filter { it.id < 0 && it.id !in acknowledged }
    }

    private fun snapshotChanged() { historyVersion++ }

    private fun requestHistoryRefresh() {
        if (!isActive() || bookingId == null) return
        historyPending = true
        if (historyRefreshJob?.isActive != true) historyRefreshJob = scope.launch { drainHistory(bookingId) }
    }

    private fun historyChanged() {
        if (!isActive()) return
        authoritativeHistory = true
        snapshotChanged()
        requestHistoryRefresh()
    }

    // The socket callback advances this before any replacement/late HTTP result can be admitted.
    fun continuityLost() {
        if (!isActive()) return
        continuityEpoch.incrementAndGet()
        scope.launch { if (isActive()) { snapshotChanged(); requestHistoryRefresh() } }
    }

    fun refreshAfterReturn() { continuityLost() }

    /** All readers share one pump; a signal/change during IO invalidates that snapshot. */
    private suspend fun drainHistory(id: Int): Boolean = historyMutex.withLock {
        if (!isActive()) return@withLock false
        try {
            while (historyPending && isActive()) {
                historyPending = false
                historyLoading = true
                historyError = false
                val version = historyVersion
                val epoch = continuityEpoch.get()
                // Never compare with rows that arrive after this request's server snapshot.
                val previousIds = messages.filter { it.id > 0 }.map { it.id }.toSet()
                val result = ApiClient.getMessages(id)
                if (!isActive()) return@withLock false
                if (version != historyVersion || epoch != continuityEpoch.get()) {
                    historyPending = true
                    continue
                }
                result.onSuccess { server ->
                    hiddenMessageIds += previousIds - server.map { it.id }.toSet()
                    applyServer(server)
                    markBaseline(server)
                }.onFailure { historyError = true }
            }
            isActive()
        } finally { if (isActive()) historyLoading = false }
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
            onHistoryChanged = { scope.launch { historyChanged() } },
            onContinuityLost = { continuityLost() },
            onMessage = { inc ->
                // WS-колбэк приходит с фонового потока OkHttp → правку Compose-state делаем на main
                // (read-modify-write `messages` иначе может потерять обновление при гонке потоков).
                scope.launch {
                    if (!isActive() || inc.id <= 0) return@launch
                    // A repeated frame must never consume another same-text pending message.
                    val pending = restAttempts.entries.firstOrNull {
                        inc.senderId == myId && inc.requestId.isNotBlank() &&
                            Outbox.messageRequestKey(it.value) == inc.requestId
                    }
                    if (pending != null) {
                        if (acknowledged.add(pending.key)) snapshotChanged()
                        failedIds = failedIds - pending.key
                        queuedIds = queuedIds - pending.key
                    }
                    val optIdx = pending?.let { entry -> messages.indexOfFirst { it.id == entry.key } } ?: -1
                    val dto = MessageDto(inc.id, inc.text, inc.senderId, voiceUrl = inc.voiceUrl, deleted = inc.deleted,
                        edited = inc.edited, flag = inc.flag, fromAdmin = inc.fromAdmin)
                    if (inc.hiddenForMe) {
                        hiddenMessageIds += inc.id
                        snapshotChanged()
                    }
                    messages = when {
                        inc.id in hiddenMessageIds -> messages.filterNot { it.id == pending?.key || it.id == inc.id }
                        inc.id > 0 && messages.any { it.id == inc.id } -> messages.filterNot { it.id == pending?.key }
                        authoritativeHistory || continuityEpoch.get() > 0 -> {
                            snapshotChanged(); requestHistoryRefresh(); messages.filterNot { it.id == pending?.key }
                        }
                        optIdx >= 0 -> messages.toMutableList().also { snapshotChanged(); it[optIdx] = dto }
                        else -> { snapshotChanged(); messages + dto }
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

    init {
        val stop = ApiClient.watchBookingReceipts { bid, generation, receipt ->
            if (bid == bookingId && generation == tripSession) scope.launch {
                if (!isActive() || !receipt.hiddenForMe || receipt.senderId != myId) return@launch
                hiddenMessageIds += receipt.id
                val pending = restAttempts.filterValues { Outbox.messageRequestKey(it) == receipt.requestId }.keys
                acknowledged += pending
                failedIds = failedIds - pending; queuedIds = queuedIds - pending
                snapshotChanged()
                messages = messages.filterNot { it.id == receipt.id || it.id in pending }
            }
        }
        scope.coroutineContext[Job]?.invokeOnCompletion { stop() }
    }

    fun canWrite(): Boolean = isActive()

    /**
     * Первая загрузка истории (и «Повторить»). Звено цепочки экрана поездки: история → код
     * посадки → детали брони. false — экран уже не тот, цепочку дальше не ведём.
     */
    suspend fun loadHistory(id: Int): Boolean {
        if (!isActive()) return false
        historyPending = true
        return drainHistory(id)
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
        bookingId?.let { loadHistory(it) }
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
        messages = messages.filterNot { row -> sent.any { it.first == row.id } }
        queuedIds = queuedIds - left.map { it.first }.toSet()
        failedIds = (failedIds - sent.map { it.first }.toSet()) + rejected.map { it.first }
        val queueChanged = changed || left.isNotEmpty()
        if (queueChanged) { snapshotChanged(); bookingId?.let { loadHistory(it) } }
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
                        if (isActive()) snapshotChanged()
                        acknowledged += tempId
                        withContext(Dispatchers.IO) { Outbox.acknowledge(context, action, tripSession) }
                        if (isActive()) {
                            messages = messages.filterNot { it.id == tempId }
                            failedIds = failedIds - tempId
                            queuedIds = queuedIds - tempId
                            loadHistory(bid)
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
        if (isActive() && !actionBusy) {
            editVersion++
            editingId = message.id; draft = message.text
            actionError = ""; failedDelete = null; failedEdit = null; failedVoice = null
        }
    }

    fun cancelEdit() {
        editVersion++; failedEdit = null
        editingId = null; draft = ""
        if (failedDelete == null && failedVoice == null) actionError = ""
    }

    fun retryAction() {
        if (!isActive() || actionBusy) return
        val deletion = failedDelete
        if (deletion != null) delete(deletion.first, deletion.second)
        else if (failedEdit?.let { it.first == editingId && it.second == editVersion } == true) submitDraft()
        else failedVoice?.let { attempt ->
            actionBusy = true; actionError = ""; failedVoice = null
            scope.launch {
                try { sendVoiceAttempt(attempt) }
                finally { if (isActive()) actionBusy = false }
            }
        }
    }

    private fun mutationFailure(error: Throwable, fallback: String): String =
        if (error is ApiException && error.status in 400..499) serverSaid(error, fallback) else fallback

    /** «Отправить» в строке ввода: правка выбранного сообщения или новое сообщение. */
    fun submitDraft() {
        val t = draft.trim()
        val bid = bookingId
        if (t.isEmpty() || bid == null || !isActive()) return
        val eid = editingId
        if (eid != null) {
            if (actionBusy) return
            val version = editVersion
            actionBusy = true; actionError = ""; failedDelete = null; failedEdit = null; failedVoice = null
            scope.launch {
                try {
                    if (!isActive()) return@launch
                    val result = ApiClient.editMessage(bid, eid, t)
                    if (!isActive()) return@launch
                    result.onSuccess {
                        snapshotChanged()
                        // Confirmation is enough to show our change even if the subsequent read fails.
                        messages = messages.map { if (it.id == eid) it.copy(text = t, edited = true) else it }
                        // A person can type a newer draft while this request is on the road.
                        if (editingId == eid && editVersion == version && draft.trim() == t) { editingId = null; draft = "" }
                        loadHistory(bid)
                    }.onFailure {
                        if (editingId == eid && editVersion == version) {
                            failedEdit = eid to version
                            actionError = mutationFailure(it, texts.value.chatActionFail)
                            toast(actionError, long = true)
                        }
                    }
                } finally { if (isActive()) actionBusy = false }
            }
        } else {
            sendText(t)
            draft = ""
        }
    }

    /** who: "me" — убрать у себя, "all" — у обоих. */
    fun delete(messageId: Int, who: String) {
        val bid = bookingId ?: return
        if (!isActive() || actionBusy || who !in setOf("me", "all")) return
        if (messages.none { it.id == messageId && !it.deleted }) {
            // A fresh authoritative history may already have reached the intended absence.
            if (failedDelete == (messageId to who)) { failedDelete = null; actionError = "" }
            return
        }
        actionBusy = true; actionError = ""; failedDelete = null; failedEdit = null; failedVoice = null
        scope.launch {
            try {
                if (!isActive()) return@launch
                val result = ApiClient.deleteMessage(bid, messageId, who)
                if (!isActive()) return@launch
                result.onSuccess {
                    snapshotChanged()
                    if (who == "me") { authoritativeHistory = true; hiddenMessageIds += messageId }
                    messages = if (who == "me") messages.filterNot { it.id == messageId }
                    else messages.map { if (it.id == messageId) it.copy(text = "", voiceUrl = null, deleted = true) else it }
                    loadHistory(bid)
                }.onFailure {
                    failedDelete = messageId to who
                    actionError = mutationFailure(it, texts.value.chatActionFail)
                    toast(actionError, long = true)
                }
            } finally { if (isActive()) actionBusy = false }
        }
    }

    /** Готовая запись от строки ввода. Файл наш: удаляем его при любом исходе, даже если запуск отменён. */
    fun sendVoice(path: String) {
        val bid = bookingId
        // A retained completion may hand us the same owned file twice. The original job
        // keeps ownership until completion; the duplicate must not delete it underneath IO.
        if (path in activeVoicePaths) return
        if (bid == null || !isActive() || actionBusy) {
            ApiClient.dropVoiceFile(path)
            return
        }
        activeVoicePaths += path
        actionBusy = true; actionError = ""; failedDelete = null; failedEdit = null; failedVoice = null
        toast(texts.value.voiceSaved, long = false)
        scope.launch {
            try {
                if (!isActive()) return@launch
                val bytes = withContext(Dispatchers.IO) { runCatching { File(path).readBytes() }.getOrNull() }
                if (!isActive()) return@launch
                if (bytes == null) {
                    actionError = texts.value.voiceUploadFail
                    toast(actionError, long = true)
                    return@launch
                }
                val upload = ApiClient.uploadVoice(bytes)
                if (!isActive()) return@launch
                upload.onSuccess { url -> sendVoiceAttempt(VoiceAttempt(url, UUID.randomUUID().toString(), continuityEpoch.get())) }
                    .onFailure {
                        actionError = mutationFailure(it, texts.value.voiceUploadFail)
                        toast(actionError, long = true)
                    }
            } finally { if (isActive()) actionBusy = false }
        // Completion also runs for a launch cancelled before its body starts.
        }.invokeOnCompletion { ApiClient.dropVoiceFile(path); activeVoicePaths -= path }
    }

    private suspend fun sendVoiceAttempt(attempt: VoiceAttempt) {
        val bid = bookingId ?: return
        if (!isActive()) return
        val firstAttempt = !attempt.attempted
        attempt.attempted = true
        val result = ApiClient.sendVoiceMessage(bid, attempt.url, attempt.key)
        if (!isActive()) return
        result.mapCatching { message ->
            check(message.id > 0 && message.senderId == myId &&
                (if (message.hiddenForMe) message.requestId == attempt.key else message.voiceUrl == attempt.url && !message.deleted)) {
                "Unconfirmed voice receipt"
            }
            message
        }.onSuccess { message ->
            snapshotChanged()
            if (message.hiddenForMe) { hiddenMessageIds += message.id; messages = messages.filterNot { it.id == message.id } }
            val stableNewVoice = firstAttempt && attempt.epoch == continuityEpoch.get() && !authoritativeHistory
            if (!message.hiddenForMe && message.id !in hiddenMessageIds && (stableNewVoice || messages.any { it.id == message.id })) {
                if (messages.none { it.id == message.id }) messages = messages + message
            }
            loadHistory(bid)
        }
            .onFailure { error ->
                // A known rejection is not an uncertain delivery. Keep a same-key retry only
                // when the sent message may already have been committed (including proxy 408).
                failedVoice = attempt.takeIf { error !is ApiException || error.status !in 400..499 || error.status == 408 }
                actionError = mutationFailure(error, texts.value.chatActionFail)
                toast(actionError, long = true)
            }
    }

    fun sendPhoto(bytes: ByteArray) {
        if (bookingId == null || !isActive() || actionBusy) return
        actionBusy = true; actionError = ""; failedDelete = null; failedEdit = null; failedVoice = null
        scope.launch {
            try {
                if (!isActive()) return@launch
                val upload = ApiClient.uploadChatPhoto(bytes)
                if (!isActive()) return@launch
                upload.onSuccess { url ->
                    // The upload is confirmed. Persist the resulting [img] message before sending,
                    // using the existing text queue and request key, including lost acknowledgements.
                    sendText(ApiClient.IMG_PREFIX + url)
                }.onFailure {
                    actionError = mutationFailure(it, texts.value.photoUploadFail)
                    toast(actionError, long = true)
                }
            } finally { if (isActive()) actionBusy = false }
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
