package com.yuldash.app

// Экран админа «Обращения в поддержку».
//
// Зачем он есть. Обращения в поддержку приложение умело только создавать и читать СВОИ:
// человек писал вопрос, тикет уходил на сервер — и там оставался. Разобрать его из
// приложения было нельзя, то есть человек писал в пустоту. Веб-версия разбор получила,
// приложение — нет (сверка с вебом, 2026-08-30).
//
// Что здесь есть: очередь (открытые сверху), тред целиком, ответ и закрытие. Ответ на
// закрытое обращение переоткрывает его — так решил сервер, и это правильно: если человек
// написал снова, разговор продолжается, а не начинается заново.
//
// Приватность. Телефон человека в очереди НЕ показываем: для ответа он не нужен, а
// список обращений — не справочник номеров. Нужен звонок — админ найдёт человека
// в его карточке.

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuldash.app.data.AdminSupportTicketDto
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.SupportTicketDto
import kotlinx.coroutines.launch

/**
 * Очередь обращений и разбор одного. Открытый тред живёт в этом же экране: обращений
 * немного, и отдельный экран ради одного треда — лишний переход в чужой боли.
 */
@Composable
internal fun AdminSupportScreen(onBack: () -> Unit) {
    var items by remember { mutableStateOf<List<AdminSupportTicketDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var onlyOpen by remember { mutableStateOf(true) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var thread by remember { mutableStateOf<SupportTicketDto?>(null) }
    var busy by remember { mutableStateOf(false) }
    var closeError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    // Тот же текст, что и у отказа списка (:183) — открыть тред не удалось по той же причине.
    val loadFailedMsg = appText("Не удалось загрузить. Проверь сеть.", "Йөкләп булманы. Селтәрҙе тикшер.")

    LaunchedEffect(onlyOpen, reloadKey) {
        loading = true; error = false
        ApiClient.adminSupportTickets(if (onlyOpen) "open" else "all")
            .onSuccess { items = it; loading = false }
            .onFailure { error = true; loading = false }
    }

    Scaffold(
        containerColor = CanonBg,
        topBar = {
            ScreenTopBar(
                if (thread == null) appText("Обращения", "Мөрәжәғәттәр")
                else appText("Обращение", "Мөрәжәғәт"),
                onBack = { if (thread != null) thread = null else onBack() },
            )
        },
    ) { padding ->
        val open = thread
        if (open == null) {
            AdminSupportList(
                loading = loading,
                error = error,
                items = items,
                onlyOpen = onlyOpen,
                onOnlyOpen = { onlyOpen = it },
                onRetry = { reloadKey += 1 },
                onOpen = { id ->
                    scope.launch {
                        ApiClient.adminSupportThread(id)
                            .onSuccess { thread = it; closeError = false }
                            .onFailure { Toast.makeText(ctx, loadFailedMsg, Toast.LENGTH_LONG).show() }
                    }
                },
                modifier = Modifier.padding(padding),
            )
        } else {
            AdminSupportThread(
                ticket = open,
                busy = busy,
                closeError = closeError,
                onReply = { text, onResult ->
                    busy = true
                    scope.launch {
                        ApiClient.adminSupportReply(open.id, text)
                            .onSuccess { thread = it; reloadKey += 1; closeError = false; onResult(true) }
                            .onFailure { onResult(false) }
                        busy = false
                    }
                },
                onClose = {
                    busy = true
                    closeError = false
                    scope.launch {
                        ApiClient.adminSupportClose(open.id)
                            .onSuccess { thread = it; reloadKey += 1 }
                            .onFailure { closeError = true }
                        busy = false
                    }
                },
                modifier = Modifier.padding(padding),
            )
        }
    }
}

/** Очередь: открытые сверху, внутри — по свежести последней активности. */
@Composable
internal fun AdminSupportList(
    loading: Boolean,
    error: Boolean,
    items: List<AdminSupportTicketDto>,
    onlyOpen: Boolean,
    onOnlyOpen: (Boolean) -> Unit,
    onRetry: () -> Unit,
    onOpen: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
    ) {
        item {
            Text(
                appText(
                    "Человек написал и ждёт ответа. Ответ переоткрывает закрытое обращение — разговор продолжается, а не начинается заново.",
                    "Кеше яҙған һәм яуап көтә. Яуап ябылған мөрәжәғәтте ҡабат аса — һөйләшеү дауам итә, яңынан башланмай.",
                ),
                color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp,
            )
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf(true, false)) { flag ->
                    NearbyFilterChip(
                        Icons.Default.SupportAgent,
                        if (flag) appText("Открытые", "Асыҡтар") else appText("Все", "Барыһы"),
                        onlyOpen == flag,
                    ) { onOnlyOpen(flag) }
                }
            }
        }
        if (loading) {
            item { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { repeat(3) { SkeletonCard(lines = 2) } } }
        } else if (error) {
            item {
                ListedError(
                    appText("Не удалось загрузить. Проверь сеть.", "Йөкләп булманы. Селтәрҙе тикшер."),
                    onRetry = onRetry,
                )
            }
        } else if (items.isEmpty()) {
            item {
                ListedEmpty(
                    appText("Обращений нет", "Мөрәжәғәт юҡ"),
                    appText(
                        "Никто не ждёт ответа. Загляни сюда позже — люди пишут не каждый день.",
                        "Бер кем яуап көтмәй. Һуңыраҡ кил — кешеләр һәр көн яҙмай.",
                    ),
                )
            }
        } else {
            items(items, key = { it.id }) { t -> AdminSupportRow(t) { onOpen(t.id) } }
        }
    }
}

/** Строка очереди: кто, о чём и чьё последнее слово. Ждёт ответа — видно сразу. */
@Composable
private fun AdminSupportRow(t: AdminSupportTicketDto, onClick: () -> Unit) {
    // «Последним написал человек» и есть признак «ждёт нас». Статус open тут мало
    // говорит: открытым остаётся и тикет, где мы уже ответили.
    val waitsUs = t.status == "open" && t.lastSender == "user"
    Surface(
        onClick = onClick,
        color = CanonSurface,
        shape = CanonItemShape,
        border = BorderStroke(if (waitsUs) 2.dp else 1.dp, if (waitsUs) CanonGreen2 else CanonBorder),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    t.userName.ifBlank { appText("Без имени", "Исемһеҙ") },
                    color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                )
                Spacer(Modifier.width(8.dp))
                if (waitsUs) {
                    Text(
                        appText("ждёт ответа", "яуап көтә"),
                        color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                    )
                } else if (t.status == "closed") {
                    Text(appText("закрыто", "ябылған"), color = CanonMuted, fontSize = 12.sp)
                }
            }
            if (t.subject.isNotBlank()) {
                Text(t.subject, color = CanonText, fontSize = 14.sp, lineHeight = 20.sp)
            }
            Text(t.lastMessage, color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp)
            if (t.updatedAt.isNotBlank()) {
                Text(formatDepart(t.updatedAt), color = CanonMuted, fontSize = 12.sp)
            }
        }
    }
}

/** Тред: сообщения по порядку, поле ответа и «закрыть». */
@Composable
internal fun AdminSupportThread(
    ticket: SupportTicketDto,
    busy: Boolean,
    closeError: Boolean,
    onReply: (String, onResult: (Boolean) -> Unit) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember(ticket.id) { mutableStateOf("") }
    var replyError by remember(ticket.id) { mutableStateOf(false) }

    LazyColumn(
        modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
    ) {
        item {
            Text(
                ticket.subject.ifBlank { appText("Без темы", "Темаһыҙ") },
                color = CanonText, fontWeight = FontWeight.Bold, fontSize = 19.sp, lineHeight = 25.sp,
            )
        }
        items(ticket.messages, key = { it.id }) { m ->
            val mine = m.sender == "admin"
            Surface(
                color = if (mine) CanonPoolingBg else CanonSurface,
                shape = CanonItemShape,
                border = BorderStroke(1.dp, if (mine) CanonHairlineGreen else CanonBorder),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        if (mine) appText("Поддержка", "Ярҙам хеҙмәте") else appText("Человек", "Кеше"),
                        color = if (mine) CanonPooling else CanonMuted,
                        fontWeight = FontWeight.Bold, fontSize = 12.sp,
                    )
                    Text(m.body, color = CanonText, fontSize = 16.sp, lineHeight = 23.sp)
                    if (m.createdAt.isNotBlank()) {
                        Text(formatDepart(m.createdAt), color = CanonMuted, fontSize = 12.sp)
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(4000) },
                placeholder = {
                    Text(
                        appText("Ответ человеку", "Кешегә яуап"),
                        color = CanonMuted, fontSize = 14.sp,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // Ответ не ушёл: текст остаётся в поле (не стираем зря набранное), и рядом видно почему.
        if (replyError) {
            item {
                Text(
                    appText(
                        "Ответ не отправлен. Текст сохранён — проверь сеть и попробуй ещё раз.",
                        "Яуап ебәрелмәне. Текст һаҡланды — селтәрҙе тикшереп ҡабатла.",
                    ),
                    color = CanonRed, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold,
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val toSend = text.trim()
                        if (toSend.isNotBlank()) {
                            replyError = false
                            onReply(toSend) { ok -> if (ok) text = "" else replyError = true }
                        }
                    },
                    enabled = !busy && text.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(appText("Ответить", "Яуап биреү"), fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = onClose,
                    enabled = !busy && ticket.status != "closed",
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(appText("Закрыть", "Ябыу"))
                }
            }
        }
        if (closeError) {
            item {
                Text(
                    appText(
                        "Не получилось закрыть обращение. Проверь сеть и попробуй ещё раз.",
                        "Мөрәжәғәтте ябып булманы. Селтәрҙе тикшереп ҡабатла.",
                    ),
                    color = CanonRed, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold,
                )
            }
        }
        item {
            Text(
                appText(
                    "Закрытие не запрещает человеку написать снова: его ответ откроет обращение обратно.",
                    "Ябыу кешегә ҡабат яҙырға ҡамасауламай: уның яуабы мөрәжәғәтте кире аса.",
                ),
                color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp,
            )
        }
    }
}
