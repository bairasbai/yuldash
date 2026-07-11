package com.yuldash.app

// Чат такси-заказа (B7b-1): живой WebSocket (/ws/instant/{id}/chat) + REST-история/фолбэк.
// Переиспользует чистую ленту ChatContent (RidesRequestsChatScreens) — та же механика, что
// в чате брони: оптимистичная отправка, авто-реконнект, честные состояния загрузки/ошибки.
// После done/отмены заказа сервер держит чат read-only — показываем спокойный баннер.

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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
internal fun InstantChatScreen(orderId: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val myId = remember { ApiClient.myUserId() ?: -1 }

    var messages by remember(orderId) { mutableStateOf<List<MessageDto>>(emptyList()) }
    var input by remember(orderId) { mutableStateOf("") }
    var sending by remember(orderId) { mutableStateOf(false) }
    var historyLoading by remember(orderId) { mutableStateOf(true) }
    var historyError by remember(orderId) { mutableStateOf(false) }
    var historyTick by remember(orderId) { mutableStateOf(0) }
    var wsConnected by remember(orderId) { mutableStateOf(false) }
    var tempSeq by remember(orderId) { mutableStateOf(-2) }
    // Роль/статус заказа: заголовок («с водителем»/«с пассажиром») + read-only после завершения.
    var role by remember(orderId) { mutableStateOf("") }
    var orderStatus by remember(orderId) { mutableStateOf("") }
    val readOnly = orderStatus == "done" || orderStatus == "cancelled" || orderStatus == "expired"

    val sendFailMsg = appText("Сообщение не отправлено. Повтори.", "Хәбәр ебәрелмәне. Ҡабатла.")

    // История + статус заказа (REST). Повтор — по кнопке «Повторить» (historyTick).
    LaunchedEffect(orderId, historyTick) {
        historyLoading = true
        historyError = false
        ApiClient.getInstantOrder(orderId).onSuccess { role = it.role; orderStatus = it.status }
        ApiClient.getOrderMessages(orderId)
            .onSuccess { messages = it }
            .onFailure { historyError = true }
        historyLoading = false
    }
    // Заказ мог завершиться, пока чат открыт → мягкий опрос статуса раз в ~15с (баннер read-only).
    LaunchedEffect(orderId) {
        while (isActive) {
            delay(15_000)
            ApiClient.getInstantOrder(orderId).onSuccess { role = it.role; orderStatus = it.status }
            if (orderStatus == "done" || orderStatus == "cancelled") break
        }
    }

    // Realtime: WS чата заказа. Эхо своего сообщения заменяет оптимистичное (как в чате брони).
    val chatSocket = remember(orderId) {
        ChatSocket.forOrder(
            orderId,
            onMessage = { inc ->
                scope.launch {
                    val optIdx = messages.indexOfFirst { it.id < 0 && it.senderId == myId && it.text == inc.text }
                    messages = when {
                        optIdx >= 0 -> messages.toMutableList().also { it[optIdx] = MessageDto(inc.id, inc.text, inc.senderId) }
                        inc.id > 0 && messages.any { it.id == inc.id } -> messages
                        else -> messages + MessageDto(inc.id, inc.text, inc.senderId)
                    }
                }
            },
            onConnected = { wsConnected = it },
        )
    }
    DisposableEffect(orderId) {
        chatSocket.connect()
        onDispose { chatSocket.close() }
    }
    // После реконнекта дотягиваем пропущенное по REST (живой приём стоял, пока сокет был мёртв).
    var wasEverConnected by remember(orderId) { mutableStateOf(false) }
    LaunchedEffect(wsConnected) {
        if (wsConnected) {
            if (wasEverConnected) ApiClient.getOrderMessages(orderId).onSuccess { messages = it }
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
            ApiClient.sendOrderMessage(orderId, text)
                .onSuccess { ApiClient.getOrderMessages(orderId).onSuccess { messages = it } }
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
                if (role == "driver") appText("Чат с пассажиром", "Пассажир менән чат")
                else appText("Чат с водителем", "Водитель менән чат"),
                onBack,
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            // WS восстанавливается → сообщения уходят по REST, честно показываем (не ошибка).
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
            // Поездка завершена → чат только для чтения (сервер новые сообщения не примет).
            AnimatedVisibility(visible = readOnly) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                        .background(CanonSurface, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = CanonMuted, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        appText("Поездка завершена — чат доступен только для чтения", "Сәфәр тамамланды — чат уҡыу өсөн генә"),
                        color = CanonMuted, fontSize = 13.sp, lineHeight = 17.sp,
                    )
                }
            }
            when {
                historyError && messages.isEmpty() -> Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
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
