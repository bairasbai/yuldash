package com.yuldash.app

/**
 * География (волна 2, батч B2): UI-кирпичи справочника населённых пунктов.
 *  • [settlementTitle] — имя НП по текущему языку (BA, если есть, иначе RU);
 *  • [DriverZoneChip] — строка текущей зоны работы у тумблера «Я на линии»;
 *  • [DriverZoneSheet] — шторка «Где работаю»: база (🏙 мой город/село или 🗺 мой район)
 *    плюс тумблеры 🛣 «выезд загород» (опц. направление) и 🌍 «соседние регионы»;
 *  • [SettlementPickField] — поле с автоподсказками из справочника (для шторки).
 * Чипы популярных маршрутов — PopularRouteChips в CreateRideScreen.kt; подсказки «откуда/куда» —
 * AddressSuggestField в AccessibilityScreens.kt (справочник + геокодер).
 * Всё в Canon-стиле, два языка, тач-цели ≥48dp. Ошибки сети — мягкие (подсказки просто пустые).
 */

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material3.TextButton
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LocationCity
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.DistrictDto
import com.yuldash.app.data.InstantZoneDto
import com.yuldash.app.data.SettlementDto
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Имя НП по текущему языку (Compose-обёртка над [settlementTitleFor] из AccessibilityScreens). */
@Composable
internal fun settlementTitle(s: SettlementDto): String = settlementTitleFor(LocalAppLanguage.current, s)

/**
 * Атрибуция OpenStreetMap под списком подсказок. Координаты и названия деревень взяты из OSM,
 * лицензия ODbL требует указывать источник там, где эти данные видно.
 */
@Composable
internal fun OsmCreditRow() {
    Text(
        appText("Деревни — данные © OpenStreetMap", "Ауылдар — мәғлүмәт © OpenStreetMap"),
        color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/**
 * Подпись зоны для чипа в кабинете: база плюс, если включено, выезд.
 * «🏙 Сибай», «🗺 Абзелиловский р-н + загород», «🏙 Сибай → Уфа», «🌍 …и соседние регионы».
 */
@Composable
internal fun zoneLabel(zone: InstantZoneDto?): String {
    val z = zone?.workZone ?: return appText("Зона: весь ближний круг", "Зона: яҡын тирә-яҡ")
    val base = when (z) {
        "district" -> zone.workDistrict?.takeIf { it.isNotBlank() }
            ?: appText("Мой район", "Минең районым")
        else -> zone.workCity?.takeIf { it.isNotBlank() }
            ?: appText("Мой город", "Минең ҡалам")
    }
    val direction = zone.workDirection?.let { " → " + settlementTitle(it) }
    val extra = when {
        direction != null -> direction
        zone.workRegions -> appText(" + соседние регионы", " + күрше төбәктәр")
        zone.workIntercity -> appText(" + загород", " + ҡала тышы")
        else -> ""
    }
    return base + extra
}

private fun zoneEmoji(zone: InstantZoneDto?): String = when {
    zone?.workZone == null -> "📍"
    zone.workRegions -> "🌍"
    zone.workIntercity -> "🛣"
    zone.workZone == "district" -> "🗺"
    else -> "🏙"
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
    var suggestFailed by remember { mutableStateOf(false) }
    var suggestRetry by remember { mutableIntStateOf(0) }
    val shown = picked?.let { settlementTitle(it) } ?: query
    LaunchedEffect(query, picked, suggestRetry) {
        if (picked != null || query.isBlank()) { hits = emptyList(); suggestFailed = false; return@LaunchedEffect }
        delay(250)   // дебаунс, чтобы не дёргать сервер на каждую букву
        ApiClient.searchSettlements(query, 6)
            .onSuccess { hits = it; suggestFailed = false }
            .onFailure { hits = emptyList(); suggestFailed = true }
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
        GeoSuggestError(visible = suggestFailed) { suggestRetry++ }
        AnimatedVisibility(hits.isNotEmpty(), enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Surface(
                color = CanonSurface, shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, CanonBorder),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            ) {
                Column {
                    hits.forEach { s ->
                        val title = settlementTitle(s)
                        val hint = settlementHintFor(s)
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { onPick(s); query = "" }
                                .heightIn(min = 48.dp)
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.LocationCity, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(title, color = CanonText, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                // Район под названием: тёзок среди деревень много, регион один — этого мало.
                                if (hint.isNotBlank()) {
                                    Text(hint, color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                    if (needsOsmCredit(hits)) OsmCreditRow()
                }
            }
        }
    }
}

/**
 * Шторка «Где работаю»: база (🏙 мой город/село или 🗺 мой район) + два согласия —
 * 🛣 выезд загород (с опциональным направлением) и 🌍 соседние регионы.
 *
 * Как «Мой район» у Яндекс Про, только бесплатно: в базовом режиме обе точки заказа внутри
 * зоны, а выезд за неё — по тумблеру. Сохранение через POST /instant/zone (гейтит сервер).
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
    var zone by remember { mutableStateOf(if (current?.workZone == "district") "district" else "city") }
    var cityText by remember { mutableStateOf(current?.workCity ?: "") }
    var districtText by remember { mutableStateOf(current?.workDistrict ?: "") }
    var intercity by remember { mutableStateOf(current?.workIntercity ?: false) }
    var regions by remember { mutableStateOf(current?.workRegions ?: false) }
    var direction by remember { mutableStateOf(current?.workDirection) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val netErr = appText("Не получилось сохранить. Проверь сеть и повтори.", "Һаҡлап булманы. Селтәрҙе тикшереп ҡабатла.")
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = CanonBg) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(appText("Где работаю", "Ҡайҙа эшләйем"), color = CanonText, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(
                appText("Заказы придут только по выбранной зоне. Поменять можно в любой момент.",
                    "Заказдар һайланған зона буйынса ғына килә. Теләһә ҡасан үҙгәртеп була."),
                color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp
            )
            ZoneOptionCard(
                emoji = "🏙", selected = zone == "city",
                title = appText("Мой город или село", "Минең ҡалам йәки ауылым"),
                text = appText("Заказы внутри одного населённого пункта", "Бер иленең эсендәге заказдар"),
                onClick = { zone = "city" },
            ) {
                CitySuggestInput(cityText, onChange = { cityText = it })
            }
            ZoneOptionCard(
                emoji = "🗺", selected = zone == "district",
                title = appText("Мой район", "Минең районым"),
                text = appText("Весь район: райцентр и все сёла", "Бөтөн район: район үҙәге һәм бөтә ауылдар"),
                onClick = { zone = "district" },
            ) {
                DistrictPickInput(districtText, onChange = { districtText = it })
            }
            ZoneToggleCard(
                emoji = "🛣", checked = intercity,
                title = appText("Выезд загород", "Ҡала тышына сығыу"),
                text = appText("Дальние заказы за пределы моей зоны", "Зонамдан ситкә алыҫ заказдар"),
                onCheck = { intercity = it; if (!it) { regions = false; direction = null } },
            ) {
                SettlementPickField(
                    label = appText("Только в сторону (не обязательно)", "Тик был яҡҡа (мотлаҡ түгел)"),
                    picked = direction,
                    onPick = { direction = it },
                )
            }
            ZoneToggleCard(
                emoji = "🌍", checked = regions, enabled = intercity,
                title = appText("Соседние регионы", "Күрше төбәктәр"),
                text = appText("Магнитогорск, Оренбург, Казань и другие", "Магнитогорск, Ырымбур, Ҡазан һәм башҡалар"),
                onCheck = { regions = it },
            )
            AnimatedVisibility(error != null) {
                Text(error ?: "", color = CanonRed, fontSize = 14.sp)
            }
            // Пустая база = «беру всё подряд», а человек думает, что сузил зону. Поэтому
            // подсказываем и не даём сохранить, пока НП или район не выбран.
            val baseFilled = if (zone == "district") districtText.isNotBlank() else cityText.isNotBlank()
            AnimatedVisibility(!baseFilled) {
                Text(
                    if (zone == "district") appText("Выбери район — иначе заказы будут приходить отовсюду.",
                        "Районды һайла — юғиһә заказдар бөтә ерҙән киләсәк.")
                    else appText("Выбери город или село — иначе заказы будут приходить отовсюду.",
                        "Ҡаланы йәки ауылды һайла — юғиһә заказдар бөтә ерҙән киләсәк."),
                    color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp,
                )
            }
            Button(
                onClick = {
                    if (saving) return@Button
                    saving = true; error = null
                    scope.launch {
                        ApiClient.setInstantZone(
                            workZone = zone,
                            workCity = cityText.trim().takeIf { zone == "city" && it.isNotBlank() },
                            workDistrict = districtText.trim().takeIf { zone == "district" && it.isNotBlank() },
                            workIntercity = intercity,
                            workRegions = regions,
                            workDirectionId = direction?.id?.takeIf { intercity },
                        ).onSuccess { saving = false; onSaved(it) }
                            .onFailure { e ->
                                saving = false
                                error = (e as? com.yuldash.app.data.ApiException)?.message ?: netErr
                            }
                    }
                },
                enabled = !saving && baseFilled,
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
    var cityFailed by remember { mutableStateOf(false) }
    var cityRetry by remember { mutableIntStateOf(0) }
    LaunchedEffect(value, cityRetry) {
        if (picked) { picked = false; return@LaunchedEffect }
        if (value.isBlank()) { hits = emptyList(); cityFailed = false; return@LaunchedEffect }
        delay(250)
        // Зона таксиста — это ГОРОД: заказ привязывается к ближайшему городу/райцентру,
        // деревня зоной быть не может (иначе водитель не увидит ни одного заказа).
        ApiClient.searchSettlements(value, 8)
            .onSuccess { list -> hits = list.filter { it.kind != "village" }.take(5); cityFailed = false }
            .onFailure { hits = emptyList(); cityFailed = true }
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
        GeoSuggestError(visible = cityFailed) { cityRetry++ }
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

/**
 * Карточка-тумблер зоны («Выезд загород», «Соседние регионы») — согласие поверх базы,
 * а не выбор вместо неё. Выключенная (enabled=false) гасится: «регионы» без «загорода»
 * бессмысленны, и человек должен это видеть, а не гадать, почему ничего не меняется.
 */
@Composable
private fun ZoneToggleCard(
    emoji: String,
    checked: Boolean,
    title: String,
    text: String,
    onCheck: (Boolean) -> Unit,
    enabled: Boolean = true,
    extra: (@Composable () -> Unit)? = null,
) {
    val on = checked && enabled
    Surface(
        color = if (on) CanonMint else CanonSurface,
        shape = CanonItemShape,
        border = BorderStroke(if (on) 1.5.dp else 1.dp, if (on) CanonGreen2 else CanonBorder),
        modifier = Modifier.fillMaxWidth()
            .clickable(enabled = enabled) { onCheck(!checked) }
            .alpha(if (enabled) 1f else 0.5f)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).background(if (on) CanonSurface else CanonMint, CircleShape), contentAlignment = Alignment.Center) {
                    Text(emoji, fontSize = 19.sp)
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f).heightIn(min = 48.dp), verticalArrangement = Arrangement.Center) {
                    Text(title, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(text, color = CanonMuted, fontSize = 12.sp, lineHeight = 23.sp)
                }
                Switch(checked = on, onCheckedChange = onCheck, enabled = enabled)
            }
            if (extra != null) {
                AnimatedVisibility(on, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    extra()
                }
            }
        }
    }
}

/**
 * Поле «мой район» с подсказками из справочника (/settlements/districts).
 * Рядом с названием — сколько в районе населённых пунктов: человеку сразу видно,
 * что он выбрал тот самый район, а не тёзку из соседнего региона.
 */
@Composable
internal fun DistrictPickInput(value: String, onChange: (String) -> Unit, enabled: Boolean = true) {
    var picked by remember { mutableStateOf(true) }   // предзаполненный район не подсказываем
    var hits by remember { mutableStateOf<List<DistrictDto>>(emptyList()) }
    var districtFailed by remember { mutableStateOf(false) }
    var districtRetry by remember { mutableIntStateOf(0) }
    LaunchedEffect(value, districtRetry) {
        if (picked) { picked = false; return@LaunchedEffect }
        if (value.isBlank()) { hits = emptyList(); districtFailed = false; return@LaunchedEffect }
        delay(250)
        ApiClient.searchDistricts(value, 6)
            .onSuccess { hits = it; districtFailed = false }
            .onFailure { hits = emptyList(); districtFailed = true }
    }
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            enabled = enabled,
            label = { Text(appText("Район работы", "Эш районы")) },
            placeholder = { Text(appText("Например, Абзелиловский", "Мәҫәлән, Әбйәлил")) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
        )
        GeoSuggestError(visible = districtFailed) { districtRetry++ }
        AnimatedVisibility(hits.isNotEmpty()) {
            Column(Modifier.padding(top = 4.dp)) {
                hits.forEach { d ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { picked = true; onChange(d.district); hits = emptyList() }
                            .heightIn(min = 48.dp)
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Map, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(d.district, color = CanonText, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                appText("${d.settlements} населённых пунктов · ${d.region}",
                                    "${d.settlements} илен · ${d.region}"),
                                color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Радио-карточка базы зоны: эмодзи + заголовок + описание; выбранная — мятная с зелёной рамкой.
 *  [extra] — дополнительный контент (поле города/района), плавно раскрывается у выбранной. */
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



/**
 * Подсказки не загрузились (нет связи). Показываем это ЯВНО.
 *
 * Раньше сбой сети выглядел как «такого населённого пункта нет»: список подсказок молча
 * оставался пустым. Человек вводил своё село, ничего не находил и делал вывод про
 * приложение, а не про связь — а в деревне слабый сигнал это норма, а не исключение.
 */
@Composable
private fun GeoSuggestError(visible: Boolean, onRetry: () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                appText("Подсказки не загрузились — проверь связь",
                        "Ишараттар йөкләнмәне — бәйләнеште тикшер"),
                color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry) {
                Text(appText("Повторить", "Ҡабатларға"), color = CanonGreen2, fontSize = 14.sp)
            }
        }
    }
}
