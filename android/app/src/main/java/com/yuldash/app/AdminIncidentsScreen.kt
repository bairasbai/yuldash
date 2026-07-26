package com.yuldash.app

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
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
 */

private val adminIncidentTabs = listOf("under_review", "awaiting_response", "appealed", "resolved")

@Composable
private fun adminTabLabel(key: String): String = when (key) {
    "under_review" -> appText("На разборе", "Ҡарала")
    "awaiting_response" -> appText("Ждут ответа", "Яуап көтә")
    "appealed" -> appText("Апелляции", "Ялыуҙар")
    else -> appText("Решённые", "Хәл ителгән")
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
            contentPadding = PaddingValues(top = 8.dp, bottom = 28.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        adminIncidentTabs.take(2).forEach { k ->
                            NearbyFilterChip(Icons.Default.Shield, adminTabLabel(k), tab == k,
                                modifier = Modifier.heightIn(min = 48.dp)) { tab = k }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        adminIncidentTabs.drop(2).forEach { k ->
                            NearbyFilterChip(Icons.Default.Report, adminTabLabel(k), tab == k,
                                modifier = Modifier.heightIn(min = 48.dp)) { tab = k }
                        }
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
                else -> items(list, key = { it.id }) { inc ->
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

    resolveTarget?.let { target ->
        ResolveIncidentDialog(
            inc = target,
            onDismiss = { resolveTarget = null },
            onResolved = { resolveTarget = null; reload++ },
        )
    }
}

@Composable
private fun AdminIncidentCard(
    inc: IncidentDto,
    onCallReporter: () -> Unit,
    onCallRespondent: () -> Unit,
    onResolve: () -> Unit,
) {
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = if (inc.severe) CanonDangerBg else CanonWarnBg, shape = CircleShape) {
                    Icon(
                        if (inc.severe) Icons.Default.Report else Icons.Default.Shield,
                        contentDescription = null,
                        tint = if (inc.severe) CanonRed else CanonWarn,
                        modifier = Modifier.padding(10.dp).size(20.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "#${inc.id} · " + incidentTypeLabel(inc.type),
                        color = CanonText, fontWeight = FontWeight.Black, fontSize = 16.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        (inc.route ?: appText("Без маршрута", "Маршрутһыҙ")) + " · " + formatDepart(inc.createdAt),
                        color = CanonMuted, fontSize = 12.sp,
                    )
                }
                if (inc.severe) {
                    Surface(color = CanonDangerBg, shape = RoundedCornerShape(999.dp)) {
                        Text(appText("Срочно", "Ашығыс"), color = CanonRed, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                    }
                }
            }

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
                text = inc.respondentStatement.ifBlank {
                    appText("Ещё не объяснился", "Әле аңлатма бирмәгән")
                },
                photos = inc.respondentEvidenceUrls.size,
                onCall = onCallRespondent,
            )

            if (inc.appealText.isNotBlank()) {
                Surface(color = CanonDangerBg, shape = CanonItemShape) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(appText("Апелляция", "Ялыу"), color = CanonRed, fontWeight = FontWeight.Black, fontSize = 13.sp)
                        Text(inc.appealText, color = CanonText, fontSize = 13.sp, lineHeight = 18.sp)
                    }
                }
            }
            if (inc.resolution.isNotBlank()) {
                Text(
                    appText("Решение: ", "Ҡарар: ") + inc.resolution +
                        (if (inc.resolutionNote.isNotBlank()) " — ${inc.resolutionNote}" else ""),
                    color = CanonMutedStrong, fontSize = 13.sp, lineHeight = 18.sp,
                )
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

@Composable
private fun AdminSideBlock(who: String, phone: String, text: String, photos: Int, onCall: () -> Unit) {
    Surface(color = CanonBg, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(who, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                if (phone.isNotBlank()) {
                    Surface(onClick = onCall, color = CanonMint, shape = CircleShape) {
                        Icon(Icons.Default.Call, contentDescription = appText("Позвонить", "Шылтыратыу"),
                            tint = CanonGreen2, modifier = Modifier.padding(10.dp).size(18.dp))
                    }
                }
            }
            Text(text, color = CanonMutedStrong, fontSize = 13.sp, lineHeight = 18.sp)
            if (photos > 0) {
                Text(appText("Фото: $photos", "Фото: $photos"), color = CanonGreen2, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = CanonSurface,
        title = { Text(appText("Решение по спору #${inc.id}", "#${inc.id} бәхәс буйынса ҡарар"), color = CanonText, fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(appText("Что решаем", "Нимә хәл итәбеҙ"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    resolutionOptions.forEach { r ->
                        ChoiceRow(appText(r.ru, r.ba), resolution == r.key) { resolution = r.key }
                    }
                }
                Text(appText("Кто виноват", "Кем ғәйепле"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    faultOptions.forEach { f ->
                        ChoiceRow(appText(f.ru, f.ba), fault == f.key) { fault = f.key }
                    }
                }
                if (resolution == "suspend") {
                    OutlinedTextField(
                        value = suspendDays,
                        onValueChange = { v -> suspendDays = v.filter { it.isDigit() }.take(3) },
                        label = { Text(appText("Пауза, дней", "Пауза, көн")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    )
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(2000) },
                    label = { Text(appText("Объяснение для обеих сторон", "Ике яҡҡа ла аңлатма")) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                )
                Text(
                    appText(
                        "Этот текст увидят оба участника — напиши так, чтобы решение было понятно и тому, кто с ним не согласен.",
                        "Был текстты ике ҡатнашыусы ла күрә — килешмәгән кешегә лә аңлайышлы итеп яҙ.",
                    ),
                    color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp,
                )
                if (conflict) {
                    Text(
                        appText(
                            "Вина на заявителе — наказание легло бы на обвинённого. Заведи встречный спор, где заявитель будет второй стороной.",
                            "Ғәйеп ялыусыла — яза ғәйепләнеүсегә төшөр ине. Ялыусы икенсе яҡ булған ҡаршы бәхәс ас.",
                        ),
                        color = CanonRed, fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Bold,
                    )
                }
                err?.let { Text(it, color = CanonRed, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && resolution.isNotBlank() && note.isNotBlank() && !conflict,
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
            ) { Text(appText("Сохранить решение", "Ҡарарҙы һаҡлау"), color = CanonGreen2, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text(appText("Отмена", "Кире алыу"), color = CanonMuted) }
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
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (selected) Icons.Default.CheckCircle else Icons.Default.Shield,
                contentDescription = null,
                tint = if (selected) CanonGreen2 else CanonMuted,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(label, color = CanonText, fontSize = 14.sp)
        }
    }
}
