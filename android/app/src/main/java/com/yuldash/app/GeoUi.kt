package com.yuldash.app

/**
 * География (волна 2, батч B2): UI-кирпичи справочника населённых пунктов.
 *  • [settlementTitle] — имя НП по текущему языку (BA, если есть, иначе RU);
 *  • [DriverZoneChip] — строка текущей зоны работы у тумблера «Я на линии»;
 *  • [DriverZoneSheet] — шторка выбора зоны: 🏙 мой город / 🛣 межгород / 🌍 соседний регион;
 *  • [SettlementPickField] — поле с автоподсказками из справочника (для шторки).
 * Чипы популярных маршрутов — PopularRouteChips в CreateRideScreen.kt; подсказки «откуда/куда» —
 * AddressSuggestField в AccessibilityScreens.kt (справочник + геокодер).
 * Всё в Canon-стиле, два языка, тач-цели ≥48dp. Ошибки сети — мягкие (подсказки просто пустые).
 */

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LocationCity
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.InstantZoneDto
import com.yuldash.app.data.SettlementDto
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Имя НП по текущему языку (Compose-обёртка над [settlementTitleFor] из AccessibilityScreens). */
@Composable
internal fun settlementTitle(s: SettlementDto): String = settlementTitleFor(LocalAppLanguage.current, s)

/** Подпись зоны для чипа в кабинете: «🏙 Мой город · Сибай» / «🛣 Межгород → Уфа» / «🌍 Соседний регион». */
@Composable
internal fun zoneLabel(zone: InstantZoneDto?): String {
    val z = zone?.workZone ?: return appText("Зона: весь ближний круг", "Зона: яҡын тирә-яҡ")
    return when (z) {
        "city" -> appText("Мой город", "Минең ҡалам") +
            (zone.workCity?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
        "intercity" -> appText("Межгород", "Ҡалалар араһы") +
            (zone.workDirection?.let { " → " + settlementTitle(it) } ?: "")
        else -> appText("Соседний регион", "Күрше төбәк")
    }
}

private fun zoneEmoji(zone: InstantZoneDto?): String = when (zone?.workZone) {
    "city" -> "🏙"; "intercity" -> "🛣"; "region" -> "🌍"; else -> "📍"
}

/**
 * Чип текущей зоны работы — рисуется под тумблером «Я на линии» (только для одобренного таксиста).
 * Тап — открыть шторку выбора. Высота ≥48dp (тач-цель).
 */
@Composable
internal fun DriverZoneChip(zone: InstantZoneDto?, onClick: () -> Unit) {
    Surface(
        color = CanonMint,
        shape = CanonItemShape,
        border = BorderStroke(1.dp, CanonBorder),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(
            Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(zoneEmoji(zone), fontSize = 16.sp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    appText("Где вожу", "Ҡайҙа йөрөтәм"),
                    color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp
                )
                Text(
                    zoneLabel(zone),
                    color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Text(appText("Изменить", "Үҙгәртергә"), color = CanonGreen2, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * Поле выбора НП из справочника: свободный ввод + выпадающие подсказки (/settlements).
 * onPick(null) — юзер стёр/правит текст (выбор сброшен). Ошибка сети → подсказок просто нет.
 */
@Composable
internal fun SettlementPickField(
    label: String,
    picked: SettlementDto?,
    onPick: (SettlementDto?) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<SettlementDto>>(emptyList()) }
    val shown = picked?.let { settlementTitle(it) } ?: query
    LaunchedEffect(query, picked) {
        if (picked != null || query.isBlank()) { hits = emptyList(); return@LaunchedEffect }
        delay(250)   // дебаунс, чтобы не дёргать сервер на каждую букву
        ApiClient.searchSettlements(query, 6)
            .onSuccess { hits = it }
            .onFailure { hits = emptyList() }
    }
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = shown,
            onValueChange = { query = it; onPick(null) },
            label = { Text(label) },
            leadingIcon = { Icon(Icons.Default.LocationCity, contentDescription = null, tint = CanonGreen2) },
            trailingIcon = if (picked != null) {
                { Icon(Icons.Default.Check, contentDescription = appText("Выбрано", "Һайланған"), tint = CanonGreen2) }
            } else null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
        )
        AnimatedVisibility(hits.isNotEmpty(), enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Surface(
                color = CanonSurface, shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, CanonBorder),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            ) {
                Column {
                    hits.forEach { s ->
                        val title = settlementTitle(s)
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { onPick(s); query = "" }
                                .heightIn(min = 48.dp)
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.LocationCity, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(title, color = CanonText, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(s.region, color = CanonMuted, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Шторка «Где вожу»: 🏙 мой город (поле города) / 🛣 межгород (+опц. направление) / 🌍 соседний регион.
 * Сохранение через POST /instant/zone (только одобренный таксист — сервер сам гейтит).
 * Состояния: сохранение (спиннер в кнопке), ошибка (текст + кнопка активна для повтора).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DriverZoneSheet(
    current: InstantZoneDto?,
    onSaved: (InstantZoneDto) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var zone by remember { mutableStateOf(current?.workZone ?: "city") }
    var cityText by remember { mutableStateOf(current?.workCity ?: "") }
    var direction by remember { mutableStateOf(current?.workDirection) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val netErr = appText("Не получилось сохранить. Проверь сеть и повтори.", "Һаҡлап булманы. Селтәрҙе тикшереп ҡабатла.")
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = CanonBg) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(appText("Где вожу", "Ҡайҙа йөрөтәм"), color = CanonText, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(
                appText("Заказы придут только по выбранной зоне. Поменять можно в любой момент.",
                    "Заказдар һайланған зона буйынса ғына килә. Теләһә ҡасан үҙгәртеп була."),
                color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp
            )
            ZoneOptionCard(
                emoji = "🏙", selected = zone == "city",
                title = appText("Мой город", "Минең ҡалам"),
                text = appText("Вожу по своему городу и рядом", "Үҙ ҡалам һәм яҡын тирә буйынса йөрөтәм"),
                onClick = { zone = "city" },
            ) {
                CitySuggestInput(cityText, onChange = { cityText = it })
            }
            ZoneOptionCard(
                emoji = "🛣", selected = zone == "intercity",
                title = appText("Межгород", "Ҡалалар араһы"),
                text = appText("Дальние поездки. Можно закрепить направление", "Алыҫ сәфәрҙәр. Йүнәлеште беркетеп була"),
                onClick = { zone = "intercity" },
            ) {
                SettlementPickField(
                    label = appText("Направление (не обязательно)", "Йүнәлеш (мотлаҡ түгел)"),
                    picked = direction,
                    onPick = { direction = it },
                )
            }
            ZoneOptionCard(
                emoji = "🌍", selected = zone == "region",
                title = appText("Соседний регион", "Күрше төбәк"),
                text = appText("Беру заказы и в соседние регионы", "Күрше төбәктәргә лә заказдар алам"),
                onClick = { zone = "region" },
            )
            AnimatedVisibility(error != null) {
                Text(error ?: "", color = CanonRed, fontSize = 14.sp)
            }
            Button(
                onClick = {
                    if (saving) return@Button
                    saving = true; error = null
                    scope.launch {
                        ApiClient.setInstantZone(
                            workZone = zone,
                            workCity = cityText.trim().takeIf { zone == "city" && it.isNotBlank() },
                            workDirectionId = direction?.id?.takeIf { zone == "intercity" },
                        ).onSuccess { saving = false; onSaved(it) }
                            .onFailure { e ->
                                saving = false
                                error = (e as? com.yuldash.app.data.ApiException)?.message ?: netErr
                            }
                    }
                },
                enabled = !saving,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2, contentColor = Color.White),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
            ) {
                if (saving) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                else Text(appText("Сохранить", "Һаҡларға"), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    }
}

/** Поле «мой город» внутри шторки: свободный ввод + подсказки из справочника (текст остаётся текстом). */
@Composable
private fun CitySuggestInput(value: String, onChange: (String) -> Unit) {
    var picked by remember { mutableStateOf(true) }   // не подсказывать для предзаполненного города
    var hits by remember { mutableStateOf<List<SettlementDto>>(emptyList()) }
    LaunchedEffect(value) {
        if (picked) { picked = false; return@LaunchedEffect }
        if (value.isBlank()) { hits = emptyList(); return@LaunchedEffect }
        delay(250)
        ApiClient.searchSettlements(value, 5).onSuccess { hits = it }.onFailure { hits = emptyList() }
    }
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(appText("Город", "Ҡала")) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
        )
        AnimatedVisibility(hits.isNotEmpty()) {
            Column(Modifier.padding(top = 4.dp)) {
                hits.forEach { s ->
                    val title = settlementTitle(s)
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { picked = true; onChange(s.nameRu); hits = emptyList() }
                            .heightIn(min = 48.dp)
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.LocationCity, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(title, color = CanonText, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(s.region, color = CanonMuted, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

/** Радио-карточка зоны: эмодзи + заголовок + описание; выбранная — мятная с зелёной рамкой.
 *  [extra] — дополнительный контент (поле города/направления), плавно раскрывается у выбранной. */
@Composable
private fun ZoneOptionCard(
    emoji: String,
    selected: Boolean,
    title: String,
    text: String,
    onClick: () -> Unit,
    extra: (@Composable () -> Unit)? = null,
) {
    Surface(
        color = if (selected) CanonMint else CanonSurface,
        shape = CanonItemShape,
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) CanonGreen2 else CanonBorder),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).background(if (selected) CanonSurface else CanonMint, CircleShape), contentAlignment = Alignment.Center) {
                    Text(emoji, fontSize = 19.sp)
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f).heightIn(min = 48.dp), verticalArrangement = Arrangement.Center) {
                    Text(title, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(text, color = CanonMuted, fontSize = 12.sp, lineHeight = 23.sp)
                }
                if (selected) Icon(Icons.Default.Check, contentDescription = appText("Выбрано", "Һайланған"), tint = CanonGreen2)
            }
            if (extra != null) {
                AnimatedVisibility(selected, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    extra()
                }
            }
        }
    }
}

