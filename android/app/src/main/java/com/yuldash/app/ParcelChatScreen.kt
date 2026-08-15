package com.yuldash.app

// Чат доставки: отправитель ↔ курьер. Живой WebSocket (/ws/parcel/{id}/chat) + REST-история и фолбэк.
// Зеркало чата такси-заказа (InstantChatScreen) — та же механика: оптимистичная отправка,
// авто-реконнект, честные состояния загрузки и ошибки, read-only после закрытия доставки.
//
// Зачем экран вообще: до него по посылке можно было только позвонить. А половина вопросов
// доставки в селе — это одна фраза: «оставь у соседей», «я на работе до шести», «звони, домофон
// не работает». Звонок для этого слишком тяжёлый, и он не оставляет следа, если потом спор.

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.ChatSocket
import com.yuldash.app.data.MessageDto
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Статусы, после которых сервер новых сообщений не принимает: чат становится «только чтение». */
private val PARCEL_CHAT_CLOSED_STATUSES = setOf("delivered", "canceled", "cancelled", "returned")

/**
 * Чат по конкретной посылке.
 *
 * @param parcelId      посылка, о которой говорим
 * @param peerIsCourier true = я отправитель и пишу курьеру; false = я курьер и пишу отправителю.
 *                      Роль знает вызывающая карточка — лишний запрос к серверу тут не нужен.
 * @param parcelStatus  статус посылки на момент открытия: по нему решаем, можно ли ещё писать.
 */
@Composable
internal fun ParcelChatScreen(
    parcelId: Int,
    peerIsCourier: Boolean,
    parcelStatus: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val myId = remember { ApiClient.myUserId() ?: -1 }

    var messages by remember(parcelId) { mutableStateOf<List<MessageDto>>(emptyList()) }
    var input by remember(parcelId) { mutableStateOf("") }
    var sending by remember(parcelId) { mutableStateOf(false) }
    var historyLoading by remember(parcelId) { mutableStateOf(true) }
    var historyError by remember(parcelId) { mutableStateOf(false) }
    var historyTick by remember(parcelId) { mutableStateOf(0) }
    var wsConnected by remember(parcelId) { mutableStateOf(false) }
    var tempSeq by remember(parcelId) { mutableStateOf(-2) }

    // Статус приходит параметром — тем, что был в момент ОТКРЫТИЯ чата. Дальше он живёт своей
    // жизнью: курьер вручил посылку, отправитель отменил — а экран об этом не знал и продолжал
    // пускать в поле ввода. Человек писал, сообщение не уходило, и он видел только «не
    // отправлено», не понимая причины. Мягко переспрашиваем свой список раз в ~20 секунд
    // (в фоне цикл стоит — правило про невидимый экран, lessons.md).
    var liveStatus by remember(parcelId) { mutableStateOf(parcelStatus) }
    val statusOwner = LocalLifecycleOwner.current
    LaunchedEffect(parcelId, peerIsCourier, statusOwner) {
        statusOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                delay(20_000)
                // Отдельной ручки «одна посылка» в API нет, и заводить её ради статуса не стоит:
                // берём свой же список (сервер и так проверяет, что посылка твоя).
                val list = if (peerIsCourier) ApiClient.getMyParcels() else ApiClient.getCarryingParcels()
                list.onSuccess { items ->
                    items.firstOrNull { it.id == parcelId }?.let { liveStatus = it.status }
                }
                if (liveStatus in PARCEL_CHAT_CLOSED_STATUSES) break   // дальше меняться нечему
            }
        }
    }

    // Доставка закрыта → сервер новых сообщений не примет. Показываем спокойный баннер и
    // оставляем историю: по ней потом разбирают спор («я же написал, что меня не будет дома»).
    val readOnly = liveStatus in PARCEL_CHAT_CLOSED_STATUSES

    val sendFailMsg = appText("Сообщение не отправлено. Повтори.", "Хәбәр ебәрелмәне. Ҡабатла.")
    val tooFastMsg = appText("Слишком быстро. Подожди минуту и продолжи.",
                             "Артыҡ тиҙ. Бер минут көт тә дауам ит.")

    // История (REST). Повтор — по кнопке «Повторить».
    LaunchedEffect(parcelId, historyTick) {
        historyLoading = true
        historyError = false
        ApiClient.getParcelMessages(parcelId)
            .onSuccess { messages = it }
            .onFailure { historyError = true }
        historyLoading = false
    }

    // Realtime: эхо своего сообщения заменяет оптимистичное (как в чате брони и такси).
    val chatSocket = remember(parcelId) {
        ChatSocket.forParcel(
            parcelId,
            onMessage = { inc ->
                scope.launch {
                    val optIdx = messages.indexOfFirst { it.id < 0 && it.senderId == myId && it.text == inc.text }
                    val dto = MessageDto(inc.id, inc.text, inc.senderId, flag = inc.flag, fromAdmin = inc.fromAdmin)
                    messages = when {
                        optIdx >= 0 -> messages.toMutableList().also { it[optIdx] = dto }
                        inc.id > 0 && messages.any { it.id == inc.id } -> messages
                        else -> messages + dto
                    }
                }
            },
            onConnected = { wsConnected = it },
            // Сервер не принял сообщение (слишком быстрый поток). Убираем его с экрана и
            // возвращаем текст в поле ввода — иначе оно висело бы как отправленное.
            onRejected = { tempId, _ ->
                scope.launch {
                    val lost = messages.firstOrNull { it.id == tempId }
                    messages = messages.filter { it.id != tempId }
                    if (lost != null && input.isBlank()) input = lost.text
                    Toast.makeText(context, tooFastMsg, Toast.LENGTH_SHORT).show()
                }
            },
        )
    }
    DisposableEffect(parcelId) {
        chatSocket.connect()
        onDispose { chatSocket.close() }
    }
    // После реконнекта дотягиваем пропущенное по REST (живой приём стоял, пока сокет был мёртв).
    var wasEverConnected by remember(parcelId) { mutableStateOf(false) }
    LaunchedEffect(wsConnected) {
        if (wsConnected) {
            if (wasEverConnected) ApiClient.getParcelMessages(parcelId).onSuccess { messages = it }
            wasEverConnected = true
        }
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty() || sending || readOnly) return
        val tempId = tempSeq
        tempSeq -= 1
        messages = messages + MessageDto(tempId, text, myId)   // оптимистично — сразу в ленту
        input = ""
        val viaWs = wsConnected && chatSocket.send(text, tempId)
        if (viaWs) return   // эхо WS заменит оптимистичное настоящим
        sending = true
        scope.launch {
            ApiClient.sendParcelMessage(parcelId, text)
                .onSuccess { ApiClient.getParcelMessages(parcelId).onSuccess { messages = it } }
                .onFailure {
                    messages = messages.filter { it.id != tempId }   // честно: не ушло — не показываем
                    input = text
                    Toast.makeText(context, serverSaid(it, sendFailMsg), Toast.LENGTH_LONG).show()
                }
            sending = false
        }
    }

    Scaffold(
        containerColor = CanonBg,
        topBar = {
            ScreenTopBar(
                if (peerIsCourier) appText("Чат с курьером", "Курьер менән чат")
                else appText("Чат с отправителем", "Ебәреүсе менән чат"),
                onBack,
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            // WS восстанавливается → сообщения уходят по REST, честно показываем (это не ошибка).
            AnimatedVisibility(visible = !wsConnected && !readOnly && !historyLoading) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                        .background(CanonMint, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = CanonGreen2)
                    Spacer(Modifier.width(8.dp))
                    Text(appText("Соединение восстанавливается…", "Бәйләнеш тергеҙелә…"), color = CanonGreen2, fontSize = 14.sp)
                }
            }
            AnimatedVisibility(visible = readOnly) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                        .background(CanonSurface, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = CanonMuted, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        appText(
                            "Доставка закрыта — переписка осталась только для чтения",
                            "Илтеү ябылды — яҙышыу уҡыу өсөн генә ҡалды",
                        ),
                        color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp,
                    )
                }
            }
            when {
                historyError && messages.isEmpty() -> Box(
                    Modifier.fillMaxSize().padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyStateCard(
                        title = appText("Не удалось загрузить чат", "Чатты йөкләп булманы"),
                        text = appText("Проверь интернет и повтори", "Интернетты тикшереп ҡабатла"),
                        icon = Icons.Default.Refresh,
                        action = appText("Повторить", "Ҡабатлау"),
                        onAction = { historyTick++ },
                    )
                }
                else -> ChatContent(
                    messages = messages,
                    input = input,
                    sending = sending || readOnly,   // read-only: поле «заморожено», отправка выключена
                    loading = historyLoading,
                    myId = myId,
                    onInputChange = { input = it },
                    onSend = { send() },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
