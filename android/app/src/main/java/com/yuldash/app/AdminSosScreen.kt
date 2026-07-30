package com.yuldash.app

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.filled.Sos
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
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
 * Дизайн (этот экран открывают в 3 часа ночи, спросонья):
 *  • Открытый сигнал КРИЧИТ: красная полоса во всю ширину карточки + пульсирующая шапка списка.
 *    Разобранный — мятный и тихий. Тревожное не выглядит как обычное.
 *  • Главное действие ровно одно и самое крупное — «Позвонить» (60dp, красная заливка).
 *    Голос человеку в беде нужнее переписки. «Принял» — тихая текстовая кнопка.
 *  • Типографика: 20 (имя) / 16 (текст и кнопки) / 13 (мета) / 11 (статус) — четыре размера.
 *  • Сетка: 4dp. Поля карточки 16, шаг блоков 12, микро-шаг 4.
 *
 * Состояния: загрузка (скелетон) / ошибка + «Повторить» / пусто («тихо — это хорошая новость»).
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

    // Шапка списка говорит главное ещё до чтения карточек: «есть открытые» / «это архив».
    // Пока грузим — молчим, чтобы не мигнуть ложным «всё тихо».
    val bannerKind = when {
        loading && list.isEmpty() -> ""
        tab == "open" && list.isNotEmpty() -> "alarm"
        tab != "open" -> "history"
        else -> ""
    }

    Scaffold(
        containerColor = CanonBg,
        topBar = { ScreenTopBar(appText("Сигналы SOS", "SOS сигналдары"), onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp),
        ) {
            item(key = "sos-tabs") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NearbyFilterChip(
                        Icons.Default.Sos, appText("Открытые", "Асыҡ"), tab == "open",
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { tab = "open" }
                    NearbyFilterChip(
                        Icons.Default.History, appText("Разобранные", "Ҡаралған"), tab == "handled",
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { tab = "handled" }
                }
            }

            if (bannerKind.isNotEmpty()) {
                item(key = "sos-banner") {
                    AnimatedContent(targetState = bannerKind, label = "sosBanner") { kind ->
                        if (kind == "alarm") SosAlarmBanner(count = list.size) else SosHistoryHint()
                    }
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
                            appText("Тишина — это хорошая новость. Все дома.", "Тынлыҡ — ул яҡшы хәбәр. Бөтәһе лә өйҙә.")
                        else
                            appText("Здесь будут сигналы, которые ты уже принял.", "Бында һин ҡабул иткән сигналдар буласаҡ."),
                        icon = Icons.Default.CheckCircle,
                    )
                }
                else -> itemsIndexed(list, key = { _, e -> e.id }) { i, e ->
                    // animateItem: принятый сигнал не исчезает рывком — он тает, остальные подъезжают.
                    // appearIn: каскад появления при первой загрузке.
                    Box(Modifier.animateItem().appearIn(i.coerceAtMost(6))) {
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
}

/** Красная шапка списка: сколько сигналов ждут ответа. Иконка медленно «дышит» — это тревога, а не декор. */
@Composable
private fun SosAlarmBanner(count: Int) {
    val pulse = rememberInfiniteTransition(label = "sosPulse")
    val alpha by pulse.animateFloat(
        initialValue = 0.4f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "sosPulseAlpha",
    )
    Surface(color = CanonDangerBg, shape = CanonItemShape) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Sos,
                contentDescription = appText("Открытые сигналы SOS", "Асыҡ SOS сигналдары"),
                tint = CanonRed.copy(alpha = alpha),
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    appText("Ждут помощи: $count", "Ярҙам көтә: $count"),
                    color = CanonRed, fontSize = 16.sp, fontWeight = FontWeight.Black, lineHeight = 20.sp,
                )
                Text(
                    appText("Сначала позвони — разбираться будешь потом.", "Башта шылтырат — аҙаҡ асыҡларһың."),
                    color = CanonMutedStrong, fontSize = 13.sp, lineHeight = 18.sp,
                )
            }
        }
    }
}

/** Тихая подпись архива: тут уже ничего не горит. */
@Composable
private fun SosHistoryHint() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.History, contentDescription = null, tint = CanonMuted, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            appText(
                "Видно, кто принял сигнал и что сделал.",
                "Сигналды кем ҡабул иткәнен һәм нимә эшләгәнен күреп була.",
            ),
            color = CanonMuted, fontSize = 13.sp, lineHeight = 18.sp,
        )
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
    val accent = if (open) CanonRed else CanonGreen2
    val band = if (open) CanonDangerBg else CanonMint

    AppCard {
        Column {
            // ── Полоса состояния во всю ширину: цвет карточки читается раньше, чем текст.
            Row(
                modifier = Modifier.fillMaxWidth().background(band).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(catIcon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(
                    catLabel, color = accent, fontSize = 16.sp, fontWeight = FontWeight.Black, lineHeight = 20.sp,
                    modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    if (open) appText("Открыт", "Асыҡ") else appText("Разобран", "Ҡаралған"),
                    color = accent, fontSize = 11.sp, fontWeight = FontWeight.Black,
                )
            }

            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // ── Кто и когда. Имя — самое крупное на карточке: за сигналом стоит человек.
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        e.userName.ifBlank { appText("Без имени", "Исемһеҙ") },
                        color = CanonText, fontWeight = FontWeight.Black, fontSize = 20.sp, lineHeight = 24.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Text(formatDepart(e.createdAt), color = CanonMuted, fontSize = 13.sp)
                }

                if (e.route.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.DirectionsCar,
                            contentDescription = appText("Маршрут поездки", "Сәфәр маршруты"),
                            tint = CanonMuted, modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            e.route, color = CanonMutedStrong, fontSize = 13.sp, lineHeight = 18.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                // ── Что человек написал сам. Красной заливки тут нет намеренно: полоса сверху
                // уже кричит, а слова человека должны читаться, а не тонуть в красном. Красная
                // волосяная рамка держит связь с тревогой.
                if (e.note.isNotBlank()) {
                    Surface(
                        color = CanonBg,
                        shape = CanonItemShape,
                        border = BorderStroke(1.dp, if (open) CanonDangerBorder else CanonBorder),
                    ) {
                        Text(
                            e.note, color = CanonText, fontSize = 16.sp, lineHeight = 22.sp,
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                        )
                    }
                }

                // ── След разбора. Показываем и когда заметки нет: важен сам факт «принято» и когда.
                if (!open) {
                    val stamp = e.handledAt?.let { formatDepart(it) }
                    if (e.handledNote.isNotBlank() || stamp != null) {
                        Surface(color = CanonMint, shape = CanonItemShape) {
                            Column(
                                Modifier.fillMaxWidth().padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    (if (e.handledNote.isNotBlank()) appText("Что сделали", "Нимә эшләнде")
                                    else appText("Принято", "Ҡабул ителгән")) + (stamp?.let { " · $it" } ?: ""),
                                    color = CanonGreen2, fontSize = 11.sp, fontWeight = FontWeight.Black,
                                )
                                if (e.handledNote.isNotBlank()) {
                                    Text(e.handledNote, color = CanonText, fontSize = 16.sp, lineHeight = 22.sp)
                                }
                            }
                        }
                    }
                }

                // ── Главное действие. Человеку в беде нужен голос, а не переписка.
                if (e.userPhone.isNotBlank()) {
                    AppButton(
                        text = appText("Позвонить", "Шылтыратыу") + " " + e.userPhone,
                        onClick = onCall,
                        style = if (open) AppButtonStyle.Danger else AppButtonStyle.Secondary,
                        icon = Icons.Default.Call,
                        height = if (open) 60.dp else 54.dp,
                    )
                } else {
                    Text(
                        appText(
                            "Телефон не передан — свяжись через чат поездки.",
                            "Телефон бирелмәгән — сәфәр чаты аша бәйләнеш ҡор.",
                        ),
                        color = CanonMuted, fontSize = 13.sp, lineHeight = 18.sp,
                    )
                }

                // ── Второстепенное: тихо, но под пальцем (48dp).
                if (open) {
                    AnimatedContent(targetState = noteOpen, label = "sosHandle") { editing ->
                        if (editing) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedTextField(
                                    value = note,
                                    onValueChange = onNoteChange,
                                    label = {
                                        Text(
                                            appText("Что сделали (для истории)", "Нимә эшләнде (тарих өсөн)"),
                                            fontSize = 13.sp,
                                        )
                                    },
                                    minLines = 2,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = CanonItemShape,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
                        } else {
                            TextButton(
                                onClick = onStartHandle,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            ) {
                                Icon(
                                    Icons.Default.Done, contentDescription = null,
                                    tint = CanonMutedStrong, modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    appText("Отметить, что принял", "Ҡабул иттем тип билдәләү"),
                                    color = CanonMutedStrong, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Иконка и подпись категории сигнала (значения сервера: medical | breakdown | other). */
@Composable
private fun sosCategoryBadge(category: String): Pair<ImageVector, String> = when (category) {
    SOS_CATEGORY_MEDICAL -> Icons.Default.LocalHospital to appText("Плохо человеку", "Кешегә насар")
    SOS_CATEGORY_BREAKDOWN -> Icons.Default.DirectionsCar to appText("Машина сломалась", "Машина ватылған")
    else -> Icons.Default.QuestionMark to appText("Другое", "Башҡа")
}
