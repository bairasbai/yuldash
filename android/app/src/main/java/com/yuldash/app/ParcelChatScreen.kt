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
import kotlinx.coroutines.launch

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

    // Доставка закрыта → сервер новых сообщений не примет. Показываем спокойный баннер и
    // оставляем историю: по ней потом разбирают спор («я же написал, что меня не будет дома»).
    val readOnly = parcelStatus == "delivered" || parcelStatus == "canceled" ||
        parcelStatus == "cancelled" || parcelStatus == "returned"

    val sendFailMsg = appText("Сообщение не отправлено. Повтори.", "Хәбәр ебәрелмәне. Ҡабатла.")

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
        val viaWs = wsConnected && chatSocket.send(text)
        if (viaWs) return   // эхо WS заменит оптимистичное настоящим
        sending = true
        scope.launch {
            ApiClient.sendParcelMessage(parcelId, text)
                .onSuccess { ApiClient.getParcelMessages(parcelId).onSuccess { messages = it } }
                .onFailure {
                    messages = messages.filter { it.id != tempId }   // честно: не ушло — не показываем
                    input = text
                    Toast.makeText(context, sendFailMsg, Toast.LENGTH_SHORT).show()
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
                        .background(CanonMint, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = CanonGreen2)
                    Spacer(Modifier.width(10.dp))
                    Text(appText("Соединение восстанавливается…", "Бәйләнеш тергеҙелә…"), color = CanonGreen2, fontSize = 13.sp)
                }
            }
            AnimatedVisibility(visible = readOnly) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                        .background(CanonSurface, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = CanonMuted, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        appText(
                            "Доставка закрыта — переписка осталась только для чтения",
                            "Илтеү ябылды — яҙышыу уҡыу өсөн генә ҡалды",
                        ),
                        color = CanonMuted, fontSize = 13.sp, lineHeight = 17.sp,
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
