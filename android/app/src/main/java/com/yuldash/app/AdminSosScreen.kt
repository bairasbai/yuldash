package com.yuldash.app

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.filled.Sos
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuldash.app.data.AdminSosDto
import com.yuldash.app.data.ApiClient
import kotlinx.coroutines.launch

/**
 * Админ: лента сигналов SOS (GET /admin/sos) + отметка «Принял» (POST /admin/sos/{id}/handle).
 *
 * Зачем: раньше ленты не существовало вообще. Сигнал уходил ОДНИМ сообщением в Telegram, поле
 * status не менял никто и никогда — если сообщение не прочитали ночью, следа о происшествии
 * не оставалось нигде (аудит 2026-07-26). Теперь: открытые сверху, телефон человека под рукой
 * (позвонить в один тап), «Принял» с заметкой — кто, когда и что сделал.
 *
 * Состояния: загрузка / ошибка + «Повторить» / пусто («тихо — это хорошо»).
 */
@Composable
internal fun AdminSosScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    var tab by remember { mutableStateOf("open") }          // open | handled
    var list by remember { mutableStateOf<List<AdminSosDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var openNoteFor by remember { mutableStateOf(0) }        // id сигнала, у которого раскрыта заметка
    var note by remember { mutableStateOf("") }
    var busyId by remember { mutableIntStateOf(0) }

    LaunchedEffect(tab, reload) {
        loading = true; error = false
        ApiClient.adminSosList(tab)
            .onSuccess { list = it }
            .onFailure { error = true }
        loading = false
    }

    fun dial(phone: String) {
        val clean = phone.filter { it.isDigit() || it == '+' }
        if (clean.isBlank()) return
        runCatching {
            ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$clean")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    Scaffold(
        containerColor = CanonBg,
        topBar = { ScreenTopBar(appText("Сигналы SOS", "SOS сигналдары"), onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 28.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NearbyFilterChip(
                        Icons.Default.Sos, appText("Открытые", "Асыҡ"), tab == "open",
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { tab = "open" }
                    NearbyFilterChip(
                        Icons.Default.Done, appText("Разобранные", "Ҡаралған"), tab == "handled",
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { tab = "handled" }
                }
            }

            when {
                loading && list.isEmpty() ->
                    item { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { repeat(3) { SkeletonCard(lines = 3) } } }
                error && list.isEmpty() -> item { AppErrorState(onRetry = { reload++ }) }
                list.isEmpty() -> item {
                    AppEmptyState(
                        title = if (tab == "open") appText("Открытых сигналов нет", "Асыҡ сигнал юҡ")
                        else appText("Разобранных пока нет", "Ҡаралғандары әлегә юҡ"),
                        text = if (tab == "open")
                            appText("Тишина — это хорошая новость.", "Тынлыҡ — ул яҡшы хәбәр.")
                        else
                            appText("Здесь будут сигналы, которые вы уже приняли.", "Бында һеҙ ҡабул иткән сигналдар буласаҡ."),
                        icon = Icons.Default.CheckCircle,
                    )
                }
                else -> items(list, key = { it.id }) { e ->
                    AdminSosCard(
                        e = e,
                        noteOpen = openNoteFor == e.id,
                        note = if (openNoteFor == e.id) note else "",
                        busy = busyId == e.id,
                        onNoteChange = { note = it },
                        onCall = { dial(e.userPhone) },
                        onStartHandle = { openNoteFor = e.id; note = "" },
                        onCancelHandle = { openNoteFor = 0; note = "" },
                        onConfirmHandle = {
                            busyId = e.id
                            val text = note
                            scope.launch {
                                ApiClient.adminSosHandle(e.id, text)
                                    .onSuccess { openNoteFor = 0; note = ""; reload++ }
                                busyId = 0
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AdminSosCard(
    e: AdminSosDto,
    noteOpen: Boolean,
    note: String,
    busy: Boolean,
    onNoteChange: (String) -> Unit,
    onCall: () -> Unit,
    onStartHandle: () -> Unit,
    onCancelHandle: () -> Unit,
    onConfirmHandle: () -> Unit,
) {
    val open = e.status == "open"
    val (catIcon, catLabel) = sosCategoryBadge(e.category)
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = if (open) CanonDangerBg else CanonMint, shape = CircleShape) {
                    Icon(
                        catIcon, contentDescription = null,
                        tint = if (open) CanonRed else CanonGreen2,
                        modifier = Modifier.padding(10.dp).size(20.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.userName.ifBlank { "—" }, color = CanonText, fontWeight = FontWeight.Black, fontSize = 17.sp)
                    Text(formatDepart(e.createdAt) + " · " + catLabel, color = CanonMuted, fontSize = 12.sp)
                }
                SosStatusTag(open)
            }

            if (e.route.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.DirectionsCar, contentDescription = null, tint = CanonMuted, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(e.route, color = CanonMutedStrong, fontSize = 13.sp)
                }
            }
            if (e.note.isNotBlank()) {
                Surface(color = CanonMint, shape = CanonItemShape) {
                    Text(e.note, color = CanonText, fontSize = 14.sp, lineHeight = 19.sp,
                        modifier = Modifier.fillMaxWidth().padding(14.dp))
                }
            }
            if (!open && e.handledNote.isNotBlank()) {
                Text(
                    appText("Что сделали: ", "Нимә эшләнде: ") + e.handledNote,
                    color = CanonMuted, fontSize = 13.sp, lineHeight = 18.sp,
                )
            }

            // Позвонить — главное действие: человеку в беде нужен голос, а не переписка.
            AppButton(
                text = appText("Позвонить ", "Шылтыратыу ") + e.userPhone,
                onClick = onCall,
                style = if (open) AppButtonStyle.Danger else AppButtonStyle.Secondary,
                icon = Icons.Default.Call,
            )

            if (open) {
                AnimatedVisibility(visible = noteOpen, enter = fadeIn(), exit = fadeOut()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = note,
                            onValueChange = onNoteChange,
                            label = { Text(appText("Что сделали (для истории)", "Нимә эшләнде (тарих өсөн)")) },
                            minLines = 2,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppButton(
                                text = appText("Отмена", "Кире алыу"),
                                onClick = onCancelHandle,
                                style = AppButtonStyle.Secondary,
                                fillWidth = false,
                                modifier = Modifier.weight(1f),
                            )
                            AppButton(
                                text = appText("Принял", "Ҡабул иттем"),
                                onClick = onConfirmHandle,
                                icon = Icons.Default.Done,
                                loading = busy,
                                fillWidth = false,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                if (!noteOpen) {
                    AppButton(
                        text = appText("Принять сигнал", "Сигналды ҡабул итеү"),
                        onClick = onStartHandle,
                        style = AppButtonStyle.Secondary,
                        icon = Icons.Default.Done,
                    )
                }
            }
        }
    }
}

@Composable
private fun SosStatusTag(open: Boolean) {
    Surface(
        color = if (open) CanonDangerBg else CanonMint,
        shape = RoundedCornerShape(999.dp),
    ) {
        Text(
            if (open) appText("Открыт", "Асыҡ") else appText("Разобран", "Ҡаралған"),
            color = if (open) CanonRed else CanonGreen2,
            fontSize = 11.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** Иконка и подпись категории сигнала (значения сервера: medical | breakdown | other). */
@Composable
private fun sosCategoryBadge(category: String): Pair<ImageVector, String> = when (category) {
    SOS_CATEGORY_MEDICAL -> Icons.Default.LocalHospital to appText("Плохо человеку", "Кешегә насар")
    SOS_CATEGORY_BREAKDOWN -> Icons.Default.DirectionsCar to appText("Машина сломалась", "Машина ватылған")
    else -> Icons.Default.QuestionMark to appText("Другое", "Башҡа")
}
