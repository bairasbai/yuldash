package com.yuldash.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.ApiException
import com.yuldash.app.data.GeocoderClient
import com.yuldash.app.data.RecentPlaceDto
import com.yuldash.app.data.SavedPlaceDto
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * ════════════════════════════════════════════════════════════════════════════
 *  «Мои адреса» — сохранённые Дом/Работа/свои + переиспользуемые блоки для пикера
 * ════════════════════════════════════════════════════════════════════════════
 *  • SavedPlacesScreen — управление списком (просмотр/добавить/удалить), вход из кабинета пассажира.
 *  • QuickPlacesBlock — быстрые строки (Дом/Работа + Недавние) над полем ввода адреса заказа.
 *  • SaveAsPlaceChips — «Сохранить как Дом/Работу» для уже выбранного адреса.
 *  Все три работают с одними DTO (SavedPlaceDto/RecentPlaceDto) и хелперами ниже.
 */

// ─────────────────── Общие хелперы вида ───────────────────

internal fun placeKindIcon(kind: String): ImageVector = when (kind) {
    "home" -> Icons.Default.Home
    "work" -> Icons.Default.Work
    else -> Icons.Default.Place
}

/** Двуязычная подпись типа адреса. Для custom — берём label записи (фолбэк «Адрес»). */
@Composable
internal fun placeKindLabel(kind: String, label: String): String = when (kind) {
    "home" -> appText("Дом", "Өй")
    "work" -> appText("Работа", "Эш")
    else -> label.ifBlank { appText("Адрес", "Адрес") }
}

// ─────────────────── Быстрый выбор в пикере (Дом/Работа/Недавние) ───────────────────

/**
 * Блок над полем ввода адреса: Дом/Работа (если заданы) + Недавние.
 * Тап по строке подставляет адрес и координаты сразу, без повторного геокодинга.
 * Если ничего нет — дружелюбная подсказка (не пустой блок).
 */
@Composable
internal fun QuickPlacesBlock(
    saved: List<SavedPlaceDto>,
    recent: List<RecentPlaceDto>,
    onPick: (address: String, lat: Double, lng: Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val home = saved.firstOrNull { it.kind == "home" }
    val work = saved.firstOrNull { it.kind == "work" }
    if (home == null && work == null && recent.isEmpty()) {
        Surface(
            shape = CanonItemShape,
            color = CanonMint,
            modifier = modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("🏠", fontSize = 19.sp)
                Spacer(Modifier.width(12.dp))
                Text(
                    appText("Добавь дом и работу — заказывать станет быстрее.", "Өй һәм эш адресын өҫтә — заказ биреүе тиҙерәк булыр."),
                    color = CanonGreen, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium,
                )
            }
        }
        return
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        home?.let { QuickPlaceRow(placeKindIcon("home"), appText("Дом", "Өй"), it.address) { onPick(it.address, it.lat, it.lng) } }
        work?.let { QuickPlaceRow(placeKindIcon("work"), appText("Работа", "Эш"), it.address) { onPick(it.address, it.lat, it.lng) } }
        if (recent.isNotEmpty()) {
            Text(
                appText("Недавние", "Һуңғылар"),
                color = CanonMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp),
            )
            recent.take(5).forEach { r ->
                QuickPlaceRow(Icons.Default.History, r.address, null) { onPick(r.address, r.lat, r.lng) }
            }
        }
    }
}

@Composable
private fun QuickPlaceRow(icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit) {
    Surface(
        shape = CanonItemShape,
        color = CanonSurface,
        border = BorderStroke(1.dp, CanonBorder),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = CanonMint, shape = CircleShape) {
                Icon(icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(8.dp).size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = CanonText, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                if (subtitle != null) Text(subtitle, color = CanonMuted, fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}

/**
 * «Сохранить как Дом/Работу» для выбранного адреса. Показывается, когда адрес выбран.
 * Подсвечивает уже сохранённый вариант (адрес совпадает) — чтобы не сохранять дубль.
 */
@Composable
internal fun SaveAsPlaceChips(
    address: String,
    lat: Double,
    lng: Double,
    savedHome: SavedPlaceDto?,
    savedWork: SavedPlaceDto?,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busyKind by remember { mutableStateOf<String?>(null) }
    val doneMsg = appText("Адрес сохранён", "Адрес һаҡланды")
    val failMsg = appText("Не удалось сохранить. Повтори.", "Һаҡлап булманы. Ҡабатла.")

    fun save(kind: String, label: String) {
        if (busyKind != null) return
        busyKind = kind
        scope.launch {
            ApiClient.saveSavedPlace(kind, label, address, lat, lng)
                .onSuccess { busyKind = null; Toast.makeText(ctx, doneMsg, Toast.LENGTH_SHORT).show(); onSaved() }
                .onFailure { busyKind = null; Toast.makeText(ctx, (it as? ApiException)?.message ?: failMsg, Toast.LENGTH_SHORT).show() }
        }
    }

    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(appText("Сохранить как", "Былай һаҡлау"), color = CanonMuted, fontSize = 14.sp)
        Spacer(Modifier.width(8.dp))
        SaveAsChip(
            icon = placeKindIcon("home"),
            label = appText("Дом", "Өй"),
            active = savedHome?.address == address,
            loading = busyKind == "home",
            onClick = { save("home", "Дом") },
        )
        Spacer(Modifier.width(8.dp))
        SaveAsChip(
            icon = placeKindIcon("work"),
            label = appText("Работа", "Эш"),
            active = savedWork?.address == address,
            loading = busyKind == "work",
            onClick = { save("work", "Работа") },
        )
    }
}

@Composable
private fun SaveAsChip(icon: ImageVector, label: String, active: Boolean, loading: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (active) CanonGreen2 else CanonMint,
        modifier = Modifier.heightIn(min = 48.dp).clickable(enabled = !loading && !active, onClick = onClick),   // P3: тач-цель ≥48dp (§4.5)
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp, color = if (active) CanonSurface else CanonGreen2)
            } else {
                Icon(icon, contentDescription = null, tint = if (active) CanonSurface else CanonGreen2, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(4.dp))
            Text(
                if (active) appText("Сохранено", "Һаҡланды") else label,
                color = if (active) CanonSurface else CanonGreen2, fontSize = 14.sp, fontWeight = FontWeight.Bold,
            )
        }
    }
}

// ─────────────────── Экран управления «Мои адреса» ───────────────────

@Composable
internal fun SavedPlacesScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var saved by remember { mutableStateOf<List<SavedPlaceDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }

    var query by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<com.yuldash.app.data.GeoHit>>(emptyList()) }
    var pendingHit by remember { mutableStateOf<com.yuldash.app.data.GeoHit?>(null) }   // выбранный адрес → диалог «как сохранить»

    val deleteFailMsg = appText("Не удалось удалить. Повтори.", "Юйып булманы. Ҡабатла.")
    val saveFailMsg = appText("Не удалось сохранить", "Һаҡлап булманы")

    LaunchedEffect(reload) {
        loading = true; error = false
        ApiClient.getSavedPlaces()
            .onSuccess { saved = it; error = false }
            .onFailure { error = true }
        loading = false
    }

    // Поиск адреса для добавления (дебаунс 350мс).
    LaunchedEffect(query) {
        if (query.trim().length < 2) { suggestions = emptyList(); return@LaunchedEffect }
        delay(350)
        suggestions = GeocoderClient.suggest(query).take(6)
    }

    Scaffold(
        containerColor = CanonBg,
        topBar = { ScreenTopBar(appText("Мои адреса", "Минең адрестар"), onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
        ) {
            item {
                Text(
                    appText("Дом, работа и любимые места — чтобы заказывать в один тап.", "Өй, эш һәм яратҡан урындар — бер тап менән заказ биреү өсөн."),
                    color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp,
                )
            }

            // Поле поиска нового адреса + подсказки
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text(appText("Добавить адрес", "Адрес өҫтәү")) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = CanonMuted) },
                        singleLine = true,
                        shape = CanonItemShape,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    suggestions.forEach { hit ->
                        Surface(
                            shape = CanonItemShape,
                            color = CanonSurface,
                            border = BorderStroke(1.dp, CanonBorder),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                                .clickable { pendingHit = hit; query = ""; suggestions = emptyList() },
                        ) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.LocationOn, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(hit.title, color = CanonText, fontSize = 14.sp, maxLines = 2)
                            }
                        }
                    }
                }
            }

            when {
                loading && saved.isEmpty() -> item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SkeletonCard(lines = 2); SkeletonCard(lines = 2)
                    }
                }
                error && saved.isEmpty() -> item { AppErrorState(onRetry = { reload++ }) }
                saved.isEmpty() -> item {
                    EmptyStateCard(
                        title = appText("Пока нет сохранённых адресов", "Һаҡланған адрестар юҡ әле"),
                        text = appText("Найди адрес выше и сохрани как Дом или Работу.", "Юғарыла адресты табып, Өй йәки Эш итеп һаҡла."),
                        icon = Icons.Default.Place,
                    )
                }
                else -> items(saved, key = { it.id }) { place ->
                    SavedPlaceRow(
                        place = place,
                        onDelete = {
                            val prev = saved
                            saved = saved.filterNot { it.id == place.id }   // оптимистично убираем
                            scope.launch {
                                ApiClient.deleteSavedPlace(place.id)
                                    .onFailure { saved = prev; Toast.makeText(ctx, deleteFailMsg, Toast.LENGTH_SHORT).show() }
                            }
                        },
                    )
                }
            }
        }
    }

    // Диалог «Как сохранить адрес» (Дом / Работа / Своё+название)
    pendingHit?.let { hit ->
        SavePlaceKindDialog(
            address = hit.title,
            onDismiss = { pendingHit = null },
            onSave = { kind, label ->
                pendingHit = null
                scope.launch {
                    ApiClient.saveSavedPlace(kind, label, hit.title, hit.lat, hit.lon)
                        .onSuccess { reload++ }
                        .onFailure {
                            val msg = (it as? ApiException)?.message ?: saveFailMsg
                            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                        }
                }
            },
        )
    }
}

@Composable
private fun SavedPlaceRow(place: SavedPlaceDto, onDelete: () -> Unit) {
    Surface(
        shape = CanonItemShape,
        color = CanonSurface,
        border = BorderStroke(1.dp, CanonBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = CanonMint, shape = CircleShape) {
                Icon(placeKindIcon(place.kind), contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(8.dp).size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(placeKindLabel(place.kind, place.label), color = CanonText, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(place.address, color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 2)
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.DeleteOutline, contentDescription = appText("Удалить", "Юйыу"), tint = CanonMuted)
            }
        }
    }
}

@Composable
private fun SavePlaceKindDialog(
    address: String,
    onDismiss: () -> Unit,
    onSave: (kind: String, label: String) -> Unit,
) {
    var customLabel by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(appText("Отмена", "Баш тартыу"), color = CanonMuted) }
        },
        title = { Text(appText("Сохранить адрес", "Адресты һаҡлау"), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(address, color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp)
                DialogKindRow(placeKindIcon("home"), appText("Дом", "Өй")) { onSave("home", "Дом") }
                DialogKindRow(placeKindIcon("work"), appText("Работа", "Эш")) { onSave("work", "Работа") }
                OutlinedTextField(
                    value = customLabel,
                    onValueChange = { if (it.length <= 40) customLabel = it },
                    label = { Text(appText("Своё название", "Үҙ исеме")) },
                    singleLine = true,
                    shape = CanonItemShape,
                    modifier = Modifier.fillMaxWidth(),
                )
                DialogKindRow(
                    Icons.Default.Add,
                    if (customLabel.isBlank()) appText("Сохранить как своё", "Үҙ адресы итеп һаҡлау") else customLabel,
                ) { onSave("custom", customLabel.trim().ifBlank { address.take(40) }) }
            }
        },
        containerColor = CanonSurface,
        shape = CanonCardShape,
    )
}

@Composable
private fun DialogKindRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Surface(
        shape = CanonItemShape,
        color = CanonMint,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(label, color = CanonGreen, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}
