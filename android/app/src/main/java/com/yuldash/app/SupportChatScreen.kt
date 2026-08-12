package com.yuldash.app

// Поддержка Юлдаш (внутренний чат вместо ссылки «Написать в Telegram»).
// Два экрана:
//   • SupportTicketsScreen — список моих обращений + форма «Новое обращение».
//   • SupportTicketScreen  — тред одного обращения (пузыри user/admin, ответ, закрыть/переоткрыть).
// Проще чата брони: REST-поллинг (без WebSocket). Лента пузырей переиспользует ChatContent
// (RidesRequestsChatScreens) — та же механика и вид: свой пузырь справа-зелёный, ответ поддержки
// слева со значком «Юлдаш ✓» (по флагу from_admin). Все состояния: загрузка/пусто/ошибка+повтор.

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HeadsetMic
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.ApiException
import com.yuldash.app.data.MessageDto
import com.yuldash.app.data.SupportListDto
import com.yuldash.app.data.SupportTicketRowDto
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// ============================ Список моих обращений ============================

@Composable
internal fun SupportTicketsScreen(onBack: () -> Unit, onOpenTicket: (Int) -> Unit) {
    var feed by remember { mutableStateOf(SupportListDto(0, emptyList())) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    var composing by remember { mutableStateOf(false) }   // показать форму нового обращения

    LaunchedEffect(reload) {
        loading = true
        ApiClient.getSupportTickets()
            .onSuccess { feed = it; error = false }
            // 401 / нет сессии — не ошибка сети: обращений просто нет, покажем дружелюбное «пусто».
            .onFailure { e -> error = (e as? ApiException)?.status != 401 }
        loading = false
    }

    Scaffold(
        containerColor = CanonBg,
        topBar = { ScreenTopBar(appText("Поддержка Юлдаш", "Юлдаш ярҙамы"), onBack) },
    ) { padding ->
        AnimatedContent(
            targetState = composing,
            transitionSpec = { fadeIn(tween(CanonMotion.QUICK)) togetherWith fadeOut(tween(CanonMotion.QUICK)) },
            modifier = Modifier.padding(padding).fillMaxSize(),
            label = "support-list-compose",
        ) { isComposing ->
            if (isComposing) {
                NewTicketForm(
                    onCancel = { composing = false },
                    onCreated = { id -> composing = false; onOpenTicket(id) },
                )
            } else {
                SupportTicketsList(
                    feed = feed,
                    loading = loading,
                    error = error,
                    onRetry = { reload++ },
                    onOpen = onOpenTicket,
                    onNew = { composing = true },
                )
            }
        }
    }
}

@Composable
private fun SupportTicketsList(
    feed: SupportListDto,
    loading: Boolean,
    error: Boolean,
    onRetry: () -> Unit,
    onOpen: (Int) -> Unit,
    onNew: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
    ) {
        item {
            Text(appText("Мы рядом", "Беҙ янда"), color = CanonGreen, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold)
            Text(appText("Напиши нам — ответим и поможем. Обычно отвечаем в течение дня.",
                "Беҙгә яҙ — яуап бирербеҙ һәм ярҙам итербеҙ. Ғәҙәттә көн эсендә яуаплайбыҙ."),
                color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 4.dp))
        }
        item {
            // Заметная кнопка «Новое обращение».
            Card(
                onClick = onNew,
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CanonGreen2),
                shape = CanonCardShape,
                elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.raised),
            ) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = CircleShape, color = CanonBg.copy(alpha = 0.22f)) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = CanonBg, modifier = Modifier.padding(12.dp).size(24.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(appText("Новое обращение", "Яңы мөрәжәғәт"), color = CanonBg, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Text(appText("Опиши вопрос — мы разберёмся", "Һорауыңды яҙ — беҙ асыҡлайбыҙ"),
                            color = CanonBg.copy(alpha = 0.9f), fontSize = 14.sp, lineHeight = 20.sp)
                    }
                }
            }
        }

        when {
            loading -> item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    repeat(3) { SkeletonCard(lines = 2) }
                }
            }
            error -> item {
                EmptyStateCard(
                    title = appText("Не удалось загрузить обращения", "Мөрәжәғәттәрҙе йөкләп булманы"),
                    text = appText("Проверь интернет и повтори", "Интернетты тикшереп ҡабатла"),
                    icon = Icons.Default.Refresh,
                    action = appText("Повторить", "Ҡабатлау"),
                    onAction = onRetry,
                )
            }
            feed.items.isEmpty() -> item {
                EmptyStateCard(
                    title = appText("Пока обращений нет", "Әлегә мөрәжәғәт юҡ"),
                    text = appText("Напиши — поможем. Мы на связи каждый день.",
                        "Яҙ — ярҙам итербеҙ. Беҙ көн һайын бәйләнештә."),
                    icon = Icons.Default.HeadsetMic,
                    action = appText("Новое обращение", "Яңы мөрәжәғәт"),
                    onAction = onNew,
                )
            }
            else -> items(feed.items, key = { it.id }) { row ->
                SupportTicketRow(row = row, onClick = { onOpen(row.id) })
            }
        }
    }
}

@Composable
private fun SupportTicketRow(row: SupportTicketRowDto, onClick: () -> Unit) {
    val closed = row.status == "closed"
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = CanonItemShape,
        elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.card),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (row.unread) {
                        Box(Modifier.size(9.dp).background(CanonGreen2, CircleShape))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        row.subject.ifBlank { appText("Обращение", "Мөрәжәғәт") },
                        color = CanonText,
                        fontWeight = if (row.unread) FontWeight.Bold else FontWeight.Bold,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                val preview = buildString {
                    if (row.lastSender == "admin") append(appText("Поддержка: ", "Ярҙам: "))
                    append(row.lastMessage.ifBlank { appText("Нет сообщений", "Хәбәрҙәр юҡ") })
                }
                Text(preview, color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SupportStatusChip(closed)
                    if (row.updatedAt.isNotBlank()) {
                        Text(formatDepart(row.updatedAt), color = CanonMuted, fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = CanonMuted)
        }
    }
}

@Composable
private fun SupportStatusChip(closed: Boolean) {
    val bg = if (closed) CanonSurface else CanonMint
    val fg = if (closed) CanonMuted else CanonGreen2
    Surface(color = bg, shape = RoundedCornerShape(999.dp), border = if (closed) BorderStroke(1.dp, CanonBorder) else null) {
        Text(
            if (closed) appText("Закрыто", "Ябыҡ") else appText("Открыто", "Асыҡ"),
            color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

// ============================ Форма нового обращения ============================

@Composable
private fun NewTicketForm(onCancel: () -> Unit, onCreated: (Int) -> Unit) {
    val scope = rememberCoroutineScope()
    var subject by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val failMsg = appText("Не удалось отправить. Проверь сеть и повтори.", "Ебәреп булманы. Сетте тикшереп ҡабатла.")

    Column(
        Modifier.fillMaxSize().padding(16.dp).imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(appText("Новое обращение", "Яңы мөрәжәғәт"), color = CanonText, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(appText("Расскажи, что случилось — чем подробнее, тем быстрее поможем.",
            "Нимә булғанын яҙ — ни тиклем ентеклерәк, шул тиклем тиҙерәк ярҙам итәбеҙ."),
            color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp)

        OutlinedTextField(
            value = subject,
            onValueChange = { subject = it },
            label = { Text(appText("Тема (необязательно)", "Тема (мотлаҡ түгел)")) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
        )
        OutlinedTextField(
            value = body,
            onValueChange = { body = it; if (errorText != null) errorText = null },
            label = { Text(appText("Сообщение", "Хәбәр")) },
            placeholder = { Text(appText("Опиши вопрос…", "Һорауыңды яҙ…")) },
            modifier = Modifier.fillMaxWidth().height(160.dp),
            shape = RoundedCornerShape(14.dp),
        )
        if (errorText != null) Text(errorText!!, color = CanonRed, fontSize = 14.sp)

        Spacer(Modifier.height(4.dp))
        AppButton(
            text = appText("Отправить", "Ебәреү"),
            onClick = {
                if (body.isBlank() || sending) return@AppButton
                sending = true; errorText = null
                scope.launch {
                    ApiClient.createSupportTicket(subject.trim().ifBlank { null }, body.trim())
                        .onSuccess { onCreated(it.id) }
                        .onFailure { errorText = (it as? ApiException)?.message ?: failMsg }
                    sending = false
                }
            },
            enabled = body.isNotBlank() && !sending,
            loading = sending,
            style = AppButtonStyle.Primary,
        )
        AppButton(text = appText("Отмена", "Баш тартыу"), onClick = onCancel, style = AppButtonStyle.Secondary)
    }
}

// ============================ Тред одного обращения ============================

private const val SUPPORT_ADMIN_ID = -777   // фиктивный id поддержки (гарантированно ≠ myId) → пузырь слева

@Composable
internal fun SupportTicketScreen(ticketId: Int, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val myId = remember { ApiClient.myUserId() ?: -1 }

    var messages by remember(ticketId) { mutableStateOf<List<MessageDto>>(emptyList()) }
    var subject by remember(ticketId) { mutableStateOf("") }
    var status by remember(ticketId) { mutableStateOf("open") }
    var input by remember(ticketId) { mutableStateOf("") }
    var sending by remember(ticketId) { mutableStateOf(false) }
    var loading by remember(ticketId) { mutableStateOf(true) }
    var error by remember(ticketId) { mutableStateOf(false) }
    var reload by remember(ticketId) { mutableStateOf(0) }
    var tempSeq by remember(ticketId) { mutableStateOf(-2) }
    var toastMsg by remember(ticketId) { mutableStateOf<String?>(null) }

    val closed = status == "closed"

    // Поддержка user/admin → лента ChatContent: свой пузырь справа, ответ поддержки слева со значком «Юлдаш ✓».
    fun applyThread(list: List<com.yuldash.app.data.SupportMessageDto>, st: String, subj: String) {
        status = st; subject = subj
        messages = list.map { m ->
            if (m.sender == "admin") MessageDto(m.id, m.body, SUPPORT_ADMIN_ID, fromAdmin = true)
            else MessageDto(m.id, m.body, myId)
        }
    }

    LaunchedEffect(ticketId, reload) {
        loading = true; error = false
        ApiClient.getSupportTicket(ticketId)
            .onSuccess { applyThread(it.messages, it.status, it.subject) }
            .onFailure { error = true }
        loading = false
    }
    // Мягкий поллинг ответов поддержки, пока экран открыт (без WS).
    // В фоне цикл стоит (repeatOnLifecycle RESUMED): свёрнутая переписка спрашивала сервер
    // каждые восемь секунд, хотя новый ответ всё равно приходит пушем.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(ticketId, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                delay(8_000)
                ApiClient.getSupportTicket(ticketId).onSuccess { applyThread(it.messages, it.status, it.subject) }
            }
        }
    }

    val sendFailMsg = appText("Сообщение не отправлено. Повтори.", "Хәбәр ебәрелмәне. Ҡабатла.")

    fun send() {
        val text = input.trim()
        if (text.isEmpty() || sending) return
        val tempId = tempSeq; tempSeq -= 1
        messages = messages + MessageDto(tempId, text, myId)   // оптимистично
        input = ""
        sending = true
        scope.launch {
            ApiClient.postSupportMessage(ticketId, text)
                .onSuccess {
                    // Дотягиваем актуальный тред (сервер мог переоткрыть закрытый тикет).
                    ApiClient.getSupportTicket(ticketId).onSuccess { applyThread(it.messages, it.status, it.subject) }
                }
                .onFailure {
                    messages = messages.filter { it.id != tempId }   // не ушло — не показываем
                    input = text
                    toastMsg = sendFailMsg
                }
            sending = false
        }
    }

    Scaffold(
        containerColor = CanonBg,
        topBar = {
            ScreenTopBar(
                subject.ifBlank { appText("Поддержка Юлдаш", "Юлдаш ярҙамы") },
                onBack,
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            // Закрытое обращение → мягкий баннер; писать всё ещё можно (сервер переоткроет).
            AnimatedVisibility(visible = closed && !loading) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                        .background(CanonSurface, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(appText("Обращение закрыто. Напиши — и оно снова откроется.",
                        "Мөрәжәғәт ябылды. Яҙ — ул ҡабат асыла."),
                        color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp)
                }
            }
            when {
                error && messages.isEmpty() -> Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                    EmptyStateCard(
                        title = appText("Не удалось загрузить обращение", "Мөрәжәғәтте йөкләп булманы"),
                        text = appText("Проверь интернет и повтори", "Интернетты тикшереп ҡабатла"),
                        icon = Icons.Default.Refresh,
                        action = appText("Повторить", "Ҡабатлау"),
                        onAction = { reload++ },
                    )
                }
                else -> {
                    ChatContent(
                        messages = messages,
                        input = input,
                        sending = sending,
                        loading = loading,
                        myId = myId,
                        onInputChange = { input = it },
                        onSend = { send() },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                    // «Закрыть обращение» — только у открытого, только если уже есть переписка.
                    AnimatedVisibility(visible = !closed && !loading && messages.isNotEmpty()) {
                        TextButton(
                            onClick = { scope.launch { ApiClient.closeSupportTicket(ticketId).onSuccess { status = "closed" } } },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        ) {
                            Text(appText("Закрыть обращение", "Мөрәжәғәтте ябыу"), color = CanonMuted, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }

    // Тост об ошибке отправки: показываем и сбрасываем.
    val toast = toastMsg
    if (toast != null) {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        LaunchedEffect(toast) {
            android.widget.Toast.makeText(ctx, toast, android.widget.Toast.LENGTH_SHORT).show()
            toastMsg = null
        }
    }
}
