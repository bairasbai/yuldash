package com.yuldash.app

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.ApiException
import com.yuldash.app.data.IncidentDto
import kotlinx.coroutines.launch

/*
 * ════════════════════════════════════════════════════════════════════════════
 *  Админ: разбор споров «Справедливости»
 * ════════════════════════════════════════════════════════════════════════════
 *  Здесь спор закрывает ЧЕЛОВЕК — это принцип, а не недоделка: автоматика не отличит
 *  «нахамил» от «был резок, потому что испугался». Экран показывает обе версии рядом,
 *  телефоны сторон (чтобы можно было просто позвонить и разобраться по-соседски) и
 *  требует написать объяснение — его увидят ОБА участника одинаковым текстом.
 *
 *  Правило сервера, которое здесь отражено кнопками: наказание всегда ложится на обвинённого.
 *  «Виноват заявитель» + страйк сервер отвергает (иначе накажем невиновного) — для лживого
 *  заявителя заводится ВСТРЕЧНЫЙ спор, где он вторая сторона.
 *
 *  Дизайн: тяжёлый спор и апелляция — красная полоса, обычный — жёлтая (ждёт разбора),
 *  решённый — мятная. «Пока без объяснения» — жёлтый значок ожидания, а не пустая строка.
 *  Типографика: 20 / 16 / 13 / 11. Сетка 4dp: поля 16, шаг 12, микро-шаг 4.
 */

private val adminIncidentTabs = listOf("under_review", "awaiting_response", "appealed", "resolved")

@Composable
private fun adminTabLabel(key: String): String = when (key) {
    "under_review" -> appText("На разборе", "Ҡарала")
    "awaiting_response" -> appText("Ждут ответа", "Яуап көтә")
    "appealed" -> appText("Апелляции", "Ялыуҙар")
    else -> appText("Решённые", "Хәл ителгән")
}

/** Иконка вкладки по смыслу: щит — разбор, часы — ожидание, сигнал — апелляция, галочка — архив. */
private fun adminTabIcon(key: String): ImageVector = when (key) {
    "under_review" -> Icons.Default.Shield
    "awaiting_response" -> Icons.Default.Schedule
    "appealed" -> Icons.Default.Report
    else -> Icons.Default.CheckCircle
}

/** Одна строка про то, что вообще лежит в этой вкладке — чтобы не гадать спросонья. */
@Composable
private fun adminTabHint(key: String): String = when (key) {
    "under_review" -> appText("Обе версии есть — решение за тобой.", "Ике версия ла бар — ҡарар һиндә.")
    "awaiting_response" -> appText("Ждём объяснения второй стороны.", "Икенсе яҡтың аңлатмаһын көтәбеҙ.")
    "appealed" -> appText("Человек не согласен с решением — перечитай.", "Кеше ҡарар менән килешмәй — ҡабат уҡы.")
    else -> appText("Архив: решения, которые уже приняты.", "Архив: ҡабул ителгән ҡарарҙар.")
}

@Composable
internal fun AdminIncidentsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var tab by remember { mutableStateOf("under_review") }
    var list by remember { mutableStateOf<List<IncidentDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var resolveTarget by remember { mutableStateOf<IncidentDto?>(null) }

    LaunchedEffect(tab, reload) {
        loading = true; error = false
        ApiClient.adminIncidents(tab)
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
        topBar = { ScreenTopBar(appText("Разбор споров", "Бәхәстәрҙе ҡарау"), onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp),
        ) {
            // Четыре состояния спора — одной строкой, а не сеткой 2×2: взгляд идёт слева направо.
            item(key = "inc-tabs") {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    adminIncidentTabs.forEach { k ->
                        NearbyFilterChip(
                            adminTabIcon(k), adminTabLabel(k), tab == k,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { tab = k }
                    }
                }
            }
            item(key = "inc-hint") {
                AnimatedContent(targetState = tab, label = "incTabHint") { k ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(adminTabIcon(k), contentDescription = null, tint = CanonMuted, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(adminTabHint(k), color = CanonMuted, fontSize = 13.sp, lineHeight = 18.sp)
                    }
                }
            }
            when {
                loading && list.isEmpty() ->
                    item { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { repeat(3) { SkeletonCard(lines = 4) } } }
                error && list.isEmpty() -> item { AppErrorState(onRetry = { reload++ }) }
                list.isEmpty() -> item {
                    AppEmptyState(
                        title = appText("Здесь пусто", "Бында буш"),
                        text = appText("Споров в этом состоянии сейчас нет.", "Был хәлдәге бәхәстәр әлегә юҡ."),
                        icon = Icons.Default.Handshake,
                    )
                }
                else -> itemsIndexed(list, key = { _, inc -> inc.id }) { i, inc ->
                    // Решённый спор уходит из вкладки не рывком, а плавно — соседние карточки подъезжают.
                    Box(Modifier.animateItem().appearIn(i.coerceAtMost(6))) {
                        AdminIncidentCard(
                            inc = inc,
                            onCallReporter = { dial(inc.reporterPhone) },
                            onCallRespondent = { dial(inc.respondentPhone) },
                            onResolve = { resolveTarget = inc },
                        )
                    }
                }
            }
        }
    }

    resolveTarget?.let { target ->
        ResolveIncidentDialog(
            inc = target,
            onDismiss = { resolveTarget = null },
            onResolved = { resolveTarget = null; reload++ },
        )
    }
}

/** Тон карточки: цвет полосы, акцент и иконка. Тревожное (тяжёлое, апелляция) — красное,
 *  ждущее разбора — жёлтое, закрытое — мятное. */
private data class IncidentTone(val bg: Color, val fg: Color, val icon: ImageVector, val word: String)

@Composable
private fun incidentTone(inc: IncidentDto): IncidentTone = when {
    inc.status == "resolved" || inc.status == "closed" ->
        IncidentTone(CanonMint, CanonGreen2, Icons.Default.CheckCircle, "")
    inc.status == "appealed" || inc.appealText.isNotBlank() ->
        IncidentTone(CanonDangerBg, CanonRed, Icons.Default.Report, appText("Апелляция", "Ялыу"))
    inc.severe ->
        IncidentTone(CanonDangerBg, CanonRed, Icons.Default.Report, appText("Срочно", "Ашығыс"))
    else ->
        IncidentTone(CanonWarnBg, CanonWarn, Icons.Default.Shield, "")
}

@Composable
private fun AdminIncidentCard(
    inc: IncidentDto,
    onCallReporter: () -> Unit,
    onCallRespondent: () -> Unit,
    onResolve: () -> Unit,
) {
    val tone = incidentTone(inc)
    AppCard {
        Column {
            // ── Полоса состояния: суть спора и его вес видно раньше, чем прочитал текст.
            Row(
                modifier = Modifier.fillMaxWidth().background(tone.bg).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(tone.icon, contentDescription = null, tint = tone.fg, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(
                    incidentTypeLabel(inc.type),
                    color = tone.fg, fontSize = 16.sp, fontWeight = FontWeight.Black, lineHeight = 20.sp,
                    modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                if (tone.word.isNotEmpty()) {
                    Spacer(Modifier.width(12.dp))
                    Text(tone.word, color = tone.fg, fontSize = 11.sp, fontWeight = FontWeight.Black)
                }
            }

            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "#${inc.id} · " + (inc.route ?: appText("Без маршрута", "Маршрутһыҙ")) +
                        " · " + formatDepart(inc.createdAt),
                    color = CanonMuted, fontSize = 13.sp, lineHeight = 18.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )

                // ── Две версии рядом. Пустое объяснение — это ожидание, а не «ничего не было».
                AdminSideBlock(
                    who = appText("Заявитель: ", "Ялыусы: ") + inc.reporterName,
                    phone = inc.reporterPhone,
                    text = inc.description,
                    photos = inc.evidenceUrls.size,
                    onCall = onCallReporter,
                )
                AdminSideBlock(
                    who = appText("Вторая сторона: ", "Икенсе яҡ: ") + inc.respondentName,
                    phone = inc.respondentPhone,
                    text = inc.respondentStatement,
                    photos = inc.respondentEvidenceUrls.size,
                    onCall = onCallRespondent,
                )

                if (inc.appealText.isNotBlank()) {
                    Surface(color = CanonDangerBg, shape = CanonItemShape, border = BorderStroke(1.dp, CanonDangerBorder)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(appText("Апелляция", "Ялыу"), color = CanonRed, fontWeight = FontWeight.Black, fontSize = 11.sp)
                            Text(inc.appealText, color = CanonText, fontSize = 16.sp, lineHeight = 22.sp)
                        }
                    }
                }

                if (inc.resolution.isNotBlank()) {
                    Surface(color = CanonMint, shape = CanonItemShape) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.CheckCircle, contentDescription = null,
                                    tint = CanonGreen2, modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    incidentResolutionLabel(inc.resolution),
                                    color = CanonGreen2, fontSize = 11.sp, fontWeight = FontWeight.Black,
                                )
                            }
                            if (inc.resolutionNote.isNotBlank()) {
                                Text(inc.resolutionNote, color = CanonText, fontSize = 16.sp, lineHeight = 22.sp)
                            }
                        }
                    }
                }

                if (inc.status != "closed") {
                    AppButton(
                        text = if (inc.status == "resolved") appText("Пересмотреть решение", "Ҡарарҙы ҡабат ҡарау")
                        else appText("Принять решение", "Ҡарар ҡабул итеү"),
                        onClick = onResolve,
                        style = if (inc.status == "resolved") AppButtonStyle.Secondary else AppButtonStyle.Primary,
                        icon = Icons.Default.CheckCircle,
                    )
                }
            }
        }
    }
}

/** Версия одной стороны: кто, кнопка звонка (48dp — по-соседски проще позвонить), текст, фото. */
@Composable
private fun AdminSideBlock(who: String, phone: String, text: String, photos: Int, onCall: () -> Unit) {
    Surface(color = CanonBg, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    who, color = CanonText, fontWeight = FontWeight.Black, fontSize = 16.sp, lineHeight = 20.sp,
                    modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                if (phone.isNotBlank()) {
                    Spacer(Modifier.width(12.dp))
                    Surface(
                        onClick = onCall,
                        color = CanonMint,
                        shape = CircleShape,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Call,
                                contentDescription = appText("Позвонить — ", "Шылтыратыу — ") + who,
                                tint = CanonGreen2, modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
            if (text.isBlank()) {
                // Ожидание — жёлтым. Пустая строка молчит, а это важная часть картины.
                Surface(color = CanonWarnBg, shape = CircleShape) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Schedule, contentDescription = null, tint = CanonWarn, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            appText("Пока без объяснения", "Әлегә аңлатмаһыҙ"),
                            color = CanonWarn, fontSize = 13.sp, fontWeight = FontWeight.Bold, lineHeight = 18.sp,
                        )
                    }
                }
            } else {
                Text(text, color = CanonText, fontSize = 16.sp, lineHeight = 22.sp)
            }
            if (photos > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Image, contentDescription = null, tint = CanonMuted, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        appText("Фото: $photos", "Фотолар: $photos"),
                        color = CanonMuted, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

// ─────────────────────────── Решение по спору ───────────────────────────

private data class ResolutionUi(val key: String, val ru: String, val ba: String, val strike: Boolean)

private val resolutionOptions = listOf(
    ResolutionUi("dismissed", "Не подтвердилось", "Раҫланманы", false),
    ResolutionUi("mutual_resolved", "Договорились", "Килештеләр", false),
    ResolutionUi("warning", "Предупреждение", "Иҫкәртеү", false),
    ResolutionUi("strike", "Страйк", "Страйк", true),
    ResolutionUi("suspend", "Пауза аккаунта", "Аккаунт паузаһы", true),
)

private data class FaultUi(val key: String, val ru: String, val ba: String)

private val faultOptions = listOf(
    FaultUi("respondent", "Вторая сторона", "Икенсе яҡ"),
    FaultUi("reporter", "Заявитель", "Ялыусы"),
    FaultUi("both", "Оба", "Икеһе лә"),
    FaultUi("none", "Никто", "Бер кем дә"),
    FaultUi("unclear", "Не ясно", "Асыҡ түгел"),
)

/** Человеческое название вынесенного решения. Только для показа: в карточке раньше светился
 *  сырой ключ сервера («strike»), а админ читает не по-английски. */
@Composable
private fun incidentResolutionLabel(key: String): String = when (key) {
    "dismissed" -> appText("Не подтвердилось", "Раҫланманы")
    "mutual_resolved" -> appText("Договорились", "Килештеләр")
    "warning" -> appText("Предупреждение", "Иҫкәртеү")
    "strike" -> appText("Страйк", "Страйк")
    "suspend" -> appText("Пауза аккаунта", "Аккаунт паузаһы")
    "ban" -> appText("Блокировка", "Блоклау")
    else -> appText("Решение принято", "Ҡарар ҡабул ителде")
}

@Composable
private fun ResolveIncidentDialog(inc: IncidentDto, onDismiss: () -> Unit, onResolved: () -> Unit) {
    val scope = rememberCoroutineScope()
    var resolution by remember { mutableStateOf("") }
    var fault by remember { mutableStateOf("respondent") }
    var note by remember { mutableStateOf("") }
    var suspendDays by remember { mutableStateOf("3") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }

    val errFallback = appText("Не получилось сохранить решение. Проверь сеть.", "Ҡарарҙы һаҡлап булманы. Селтәрҙе тикшер.")
    val chosen = resolutionOptions.firstOrNull { it.key == resolution }
    // Сервер отвергнет «виноват заявитель» вместе с наказанием — предупреждаем ДО отправки,
    // чтобы админ не ловил 422 вслепую.
    val conflict = fault == "reporter" && chosen != null &&
        chosen.key in listOf("warning", "strike", "suspend")
    val canSave = !busy && resolution.isNotBlank() && note.isNotBlank() && !conflict

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = CanonSurface,
        title = {
            Text(
                appText("Решение по спору #${inc.id}", "#${inc.id} бәхәс буйынса ҡарар"),
                color = CanonText, fontWeight = FontWeight.Black, fontSize = 20.sp, lineHeight = 24.sp,
            )
        },
        text = {
            // Вариантов много — без прокрутки на маленьком экране кнопка «Сохранить» уезжает за край.
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(appText("Что решаем", "Нимә хәл итәбеҙ"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 16.sp)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    resolutionOptions.forEach { r ->
                        ChoiceRow(appText(r.ru, r.ba), resolution == r.key) { resolution = r.key }
                    }
                }
                // Наказание — не рядовой выбор: говорим вслух, что оно останется в истории человека.
                AnimatedVisibility(visible = chosen?.strike == true) {
                    Surface(color = CanonWarnBg, shape = CanonItemShape) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = CanonWarn, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                appText(
                                    "Это наказание: останется в истории и повлияет на доступ к заказам.",
                                    "Был яза: тарихта ҡала һәм заказдарға инеүгә тәьҫир итә.",
                                ),
                                color = CanonWarn, fontSize = 13.sp, lineHeight = 18.sp,
                            )
                        }
                    }
                }

                Text(appText("Кто виноват", "Кем ғәйепле"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 16.sp)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    faultOptions.forEach { f ->
                        ChoiceRow(appText(f.ru, f.ba), fault == f.key) { fault = f.key }
                    }
                }

                AnimatedVisibility(visible = resolution == "suspend") {
                    OutlinedTextField(
                        value = suspendDays,
                        onValueChange = { v -> suspendDays = v.filter { it.isDigit() }.take(3) },
                        label = { Text(appText("Пауза, дней", "Пауза, көн"), fontSize = 13.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = CanonItemShape,
                    )
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(2000) },
                    label = { Text(appText("Объяснение для обеих сторон", "Ике яҡҡа ла аңлатма"), fontSize = 13.sp) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                    shape = CanonItemShape,
                )
                Text(
                    appText(
                        "Этот текст увидят оба участника — напиши так, чтобы решение было понятно и тому, кто с ним не согласен.",
                        "Был текстты ике ҡатнашыусы ла күрә — килешмәгән кешегә лә аңлайышлы итеп яҙ.",
                    ),
                    color = CanonMuted, fontSize = 13.sp, lineHeight = 18.sp,
                )
                AnimatedVisibility(visible = conflict) {
                    Surface(color = CanonDangerBg, shape = CanonItemShape, border = BorderStroke(1.dp, CanonDangerBorder)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = CanonRed, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                appText(
                                    "Вина на заявителе — наказание легло бы на обвинённого. Заведи встречный спор, где заявитель будет второй стороной.",
                                    "Ғәйеп ялыусыла — яза ғәйепләнеүсегә төшөр ине. Ялыусы икенсе яҡ булған ҡаршы бәхәс ас.",
                                ),
                                color = CanonRed, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
                err?.let {
                    Text(it, color = CanonRed, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                modifier = Modifier.heightIn(min = 48.dp),
                onClick = {
                    busy = true; err = null
                    scope.launch {
                        ApiClient.adminResolveIncident(
                            inc.id,
                            resolution = resolution,
                            fault = fault,
                            note = note,
                            strike = chosen?.strike == true,
                            suspendDays = if (resolution == "suspend") suspendDays.toIntOrNull() ?: 3 else null,
                        )
                            .onSuccess { onResolved() }
                            .onFailure { err = (it as? ApiException)?.message ?: errFallback }
                        busy = false
                    }
                },
            ) {
                // Пока сохраняем — крутится спиннер, а не «мёртвая» кнопка.
                AnimatedContent(targetState = busy, label = "resolveBusy") { saving ->
                    if (saving) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = CanonGreen2, strokeWidth = 2.dp)
                    } else {
                        Text(
                            appText("Сохранить решение", "Ҡарарҙы һаҡлау"),
                            color = if (canSave) CanonGreen2 else CanonMuted,
                            fontSize = 16.sp, fontWeight = FontWeight.Black,
                        )
                    }
                }
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(appText("Отмена", "Кире алыу"), color = CanonMuted, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        },
    )
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = if (selected) CanonMint else CanonBg,
        shape = CanonItemShape,
        border = BorderStroke(1.dp, if (selected) CanonGreen2 else CanonBorder),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (selected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (selected) CanonGreen2 else CanonMuted,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                label, color = CanonText, fontSize = 16.sp, lineHeight = 20.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}
