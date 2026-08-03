package com.yuldash.app

// ============================ Админ: заявки таксистов + города такси ============================
// По паттерну AdminDriversScreen/AdminPaymentRequestsScreen (SecondaryScreens.kt): умная обёртка
// держит стейт и сеть, LazyColumn рисует все состояния (загрузка / ошибка+Повторить / пусто / список).
// Заявки: имя, телефон, ИНН, № разрешения, возраст/стаж, фото документов (Coil+Bearer),
// Одобрить / Отклонить (с комментарием). Ниже — секция «Города такси»: тумблер enabled, добавить/удалить.

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.LocalTaxi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.TaxiApplicationDto
import com.yuldash.app.data.TaxiCityDto
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
internal fun AdminTaxiScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val token = remember { ApiClient.currentToken() ?: "" }

    var filter by remember { mutableStateOf("pending") }
    var apps by remember { mutableStateOf<List<TaxiApplicationDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    var cities by remember { mutableStateOf<List<TaxiCityDto>>(emptyList()) }
    var citiesError by remember { mutableStateOf(false) }
    var newCity by remember { mutableStateOf("") }
    var cityBusy by remember { mutableStateOf(false) }

    // Отклонение с комментарием: id раскрытой карточки + текст.
    var rejectingId by remember { mutableStateOf<Int?>(null) }
    var rejectComment by remember { mutableStateOf("") }

    // Журнал предрейсовых подтверждений (580-ФЗ). Сервер вёл его всё это время, но показать
    // было негде: при разборе ДТП или проверке единственным способом достать запись был curl.
    // dayShift: 0 = сегодня, 1 = вчера и так далее — админ листает назад стрелкой.
    var dayShift by remember { mutableStateOf(0) }
    var pretrip by remember { mutableStateOf<com.yuldash.app.data.PretripJournalDto?>(null) }
    var pretripLoading by remember { mutableStateOf(true) }
    var pretripError by remember { mutableStateOf(false) }

    val approvedMsg = appText("Таксист одобрен", "Таксист раҫланды")
    val rejectedMsg = appText("Заявка отклонена", "Заявка кире ҡағылды")
    val actionErrMsg = appText("Не получилось. Проверь сеть и повтори.", "Булманы. Селтәрҙе тикшереп ҡабатла.")
    val loadErr = appText("Не удалось загрузить. Проверь интернет.", "Йөкләп булманы. Интернетты тикшер.")

    fun reloadApps() {
        loading = true; error = null
        scope.launch {
            ApiClient.adminTaxiApplications(filter).onSuccess { apps = it }.onFailure { error = loadErr }
            loading = false
        }
    }
    fun reloadCities() {
        scope.launch {
            citiesError = false
            ApiClient.adminTaxiCities().onSuccess { cities = it }.onFailure { citiesError = true }
        }
    }
    fun reloadPretrip() {
        pretripLoading = true; pretripError = false
        scope.launch {
            ApiClient.adminPretripJournal(pretripDayParam(dayShift))
                .onSuccess { pretrip = it }
                .onFailure { pretripError = true }
            pretripLoading = false
        }
    }
    LaunchedEffect(filter) { reloadApps() }
    LaunchedEffect(Unit) { reloadCities() }
    LaunchedEffect(dayShift) { reloadPretrip() }

    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Таксисты", "Таксистар"), onBack) }) { padding ->
        LazyColumn(
            Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            item {
                Text(
                    appText(
                        "Заявки «Стать таксистом» (580-ФЗ): сверь ИНН, разрешение и ОСАГО, потом одобри или отклони с комментарием.",
                        "«Таксист булыу» заявкалары (580-ФЗ): ИНН, рөхсәт һәм ОСАГО-ны тикшер, аҙаҡ раҫла йәки комментарий менән кире ҡаҡ.",
                    ),
                    color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp,
                )
            }
            // Фильтр по статусу.
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TaxiFilterChip(appText("На проверке", "Тикшереүҙә"), filter == "pending") { filter = "pending" }
                    TaxiFilterChip(appText("Одобрены", "Раҫланған"), filter == "approved") { filter = "approved" }
                    TaxiFilterChip(appText("Отклонены", "Кире ҡағылған"), filter == "rejected") { filter = "rejected" }
                    TaxiFilterChip(appText("Все", "Барыһы"), filter == "all") { filter = "all" }
                }
            }
            if (loading) {
                item { SkeletonCard(lines = 4) }
                item { SkeletonCard(lines = 4) }
            } else if (error != null) {
                item { ListedError(error ?: "") { reloadApps() } }
            } else if (apps.isEmpty()) {
                item {
                    ListedEmpty(
                        appText("Заявок нет", "Заявка юҡ"),
                        appText("Здесь появятся водители, которые хотят возить такси.", "Бында такси йөрөтөргә теләгән водителдәр күренер"),
                    )
                }
            } else {
                items(apps.size, key = { "app-" + apps[it].id }) { i ->
                    val a = apps[i]
                    Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(a.name.ifBlank { appText("Без имени", "Исемһеҙ") }, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                                TaxiStatusBadge(a.status)
                            }
                            if (a.phone.isNotBlank()) Text(a.phone, color = CanonMuted, fontSize = 14.sp)
                            // Доверие «между своими»: кто пригласил этого водителя (если по реф-коду).
                            a.invitedBy?.let { Text(appText("Пригласил: ", "Саҡырҙы: ") + it, color = CanonGreen2, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                            // ИНН + разрешение + возраст/стаж.
                            val age = taxiAgeYears(a.birthDate)
                            val currentYear = remember { java.time.LocalDate.now().year }
                            val expYears = if (a.licenseSinceYear in 1900..currentYear) currentYear - a.licenseSinceYear else null
                            Text(appText("ИНН: ", "ИНН: ") + a.inn.ifBlank { "—" }, color = CanonText, fontSize = 14.sp)
                            Text(appText("Разрешение: ", "Рөхсәт: ") + a.permitNumber.ifBlank { "—" }, color = CanonText, fontSize = 14.sp)
                            val meta = buildList {
                                if (age != null) add(appText("$age лет", "$age йәш"))
                                if (expYears != null) add(appText("стаж $expYears г.", "стаж $expYears йыл"))
                            }.joinToString("  ·  ")
                            if (meta.isNotBlank()) Text(meta, color = CanonMuted, fontSize = 14.sp)
                            if (a.permitPhotoUrl.isNotBlank()) {
                                Text(appText("Разрешение на такси", "Такси рөхсәте"), color = CanonMuted, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                TaxiDocImage(a.permitPhotoUrl, token)
                            }
                            if (a.osagoUrl.isNotBlank()) {
                                Text(appText("Полис ОСАГО", "ОСАГО полисы"), color = CanonMuted, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                TaxiDocImage(a.osagoUrl, token)
                            }
                            if (a.selfieUrl.isNotBlank()) {
                                Text(appText("Селфи с правами (сверь лицо)", "Права менән селфи (йөҙҙө сағыштыр)"), color = CanonMuted, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                TaxiDocImage(a.selfieUrl, token)
                            }
                            if (a.criminalRecordUrl.isNotBlank()) {
                                Text(appText("Справка о несудимости", "Судимлек юҡлығы белешмәһе"), color = CanonMuted, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                TaxiDocImage(a.criminalRecordUrl, token)
                            }
                            if (a.status == "rejected" && a.comment.isNotBlank()) {
                                Text(appText("Комментарий: ", "Комментарий: ") + a.comment, color = CanonRed, fontSize = 14.sp, lineHeight = 20.sp)
                            }
                            if (a.status == "pending") {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = {
                                            val id = a.id
                                            scope.launch {
                                                ApiClient.adminApproveTaxiApplication(id)
                                                    .onSuccess { Toast.makeText(ctx, approvedMsg, Toast.LENGTH_SHORT).show(); reloadApps() }
                                                    .onFailure { Toast.makeText(ctx, actionErrMsg, Toast.LENGTH_SHORT).show() }
                                            }
                                        },
                                        modifier = Modifier.weight(1f).height(48.dp),
                                        shape = RoundedCornerShape(14.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2),
                                    ) { Text(appText("Одобрить", "Раҫлау"), fontWeight = FontWeight.Bold) }
                                    OutlinedButton(
                                        onClick = {
                                            if (rejectingId == a.id) { rejectingId = null } else { rejectingId = a.id; rejectComment = "" }
                                        },
                                        modifier = Modifier.weight(1f).height(48.dp),
                                        shape = RoundedCornerShape(14.dp),
                                    ) { Text(appText("Отклонить", "Кире ҡағыу"), color = CanonRed, fontWeight = FontWeight.Bold) }
                                }
                                // Отклонение — с полем комментария (водитель увидит его в заявке).
                                AnimatedVisibility(visible = rejectingId == a.id) {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedTextField(
                                            value = rejectComment,
                                            onValueChange = { rejectComment = it.take(300) },
                                            label = { Text(appText("Почему отклоняешь (увидит водитель)", "Ниңә кире ҡағаһың (водитель күрер)")) },
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(14.dp),
                                        )
                                        Button(
                                            onClick = {
                                                val id = a.id
                                                val comment = rejectComment.trim()
                                                scope.launch {
                                                    ApiClient.adminRejectTaxiApplication(id, comment)
                                                        .onSuccess { Toast.makeText(ctx, rejectedMsg, Toast.LENGTH_SHORT).show(); rejectingId = null; reloadApps() }
                                                        .onFailure { Toast.makeText(ctx, actionErrMsg, Toast.LENGTH_SHORT).show() }
                                                }
                                            },
                                            enabled = rejectComment.isNotBlank(),
                                            modifier = Modifier.fillMaxWidth().height(48.dp),
                                            shape = RoundedCornerShape(14.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = CanonRed),
                                        ) { Text(appText("Отклонить с комментарием", "Комментарий менән кире ҡағыу"), fontWeight = FontWeight.Bold) }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ------------------------------ Города такси ------------------------------
            item { Spacer(Modifier.height(4.dp)) }
            item { SectionHeader(appText("Города такси", "Такси ҡалалары"), appText("Где пассажирам доступен быстрый заказ", "Пассажирҙарға тиҙ заказ ҡайҙа асыҡ")) }
            if (citiesError) {
                item { ListedError(loadErr) { reloadCities() } }
            } else if (cities.isEmpty()) {
                item {
                    Text(
                        appText("Городов пока нет — добавь первый ниже. Пока список пуст, такси решает глобальный флаг на сервере.", "Ҡалалар әлегә юҡ — тәүгеһен түбәндә өҫтә. Исемлек буш саҡта таксины серверҙағы дөйөм флаг хәл итә."),
                        color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp,
                    )
                }
            } else {
                items(cities.size, key = { "city-" + cities[it].id }) { i ->
                    val c = cities[i]
                    Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.LocalTaxi, contentDescription = null, tint = if (c.enabled) CanonGreen2 else CanonMuted, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(c.city, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Text(
                                    if (c.enabled) appText("Такси включено", "Такси ҡабыҙылған") else appText("Выключено", "Һүндерелгән"),
                                    color = if (c.enabled) CanonGreen2 else CanonMuted, fontSize = 12.sp,
                                )
                            }
                            Switch(
                                checked = c.enabled,
                                onCheckedChange = { v ->
                                    val name = c.city
                                    scope.launch {
                                        ApiClient.adminAddTaxiCity(name, v)
                                            .onSuccess { reloadCities() }
                                            .onFailure { Toast.makeText(ctx, actionErrMsg, Toast.LENGTH_SHORT).show() }
                                    }
                                },
                                colors = SwitchDefaults.colors(checkedTrackColor = CanonGreen2),
                            )
                            Spacer(Modifier.width(4.dp))
                            IconButton(onClick = {
                                val id = c.id
                                scope.launch {
                                    ApiClient.adminDeleteTaxiCity(id)
                                        .onSuccess { reloadCities() }
                                        .onFailure { Toast.makeText(ctx, actionErrMsg, Toast.LENGTH_SHORT).show() }
                                }
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = appText("Удалить город", "Ҡаланы юйыу"), tint = CanonRed)
                            }
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newCity,
                        onValueChange = { newCity = it.take(40) },
                        label = { Text(appText("Город", "Ҡала")) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                    )
                    Button(
                        onClick = {
                            val name = newCity.trim()
                            if (name.isBlank() || cityBusy) return@Button
                            cityBusy = true
                            scope.launch {
                                ApiClient.adminAddTaxiCity(name, true)
                                    .onSuccess { newCity = ""; reloadCities() }
                                    .onFailure { Toast.makeText(ctx, actionErrMsg, Toast.LENGTH_SHORT).show() }
                                cityBusy = false
                            }
                        },
                        enabled = newCity.isNotBlank() && !cityBusy,
                        modifier = Modifier.height(56.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = appText("Добавить город", "Ҡала өҫтәү"))
                        Spacer(Modifier.width(4.dp))
                        Text(appText("Добавить", "Өҫтәү"), fontWeight = FontWeight.Bold)
                    }
                }
            }

            // ------------------ Журнал предрейсовых подтверждений (580-ФЗ) ------------------
            item { Spacer(Modifier.height(4.dp)) }
            item {
                SectionHeader(
                    appText("Готовность к работе", "Эшкә әҙерлек"),
                    appText(
                        "Кто отметился перед сменой. Это след для разбора ДТП и проверки.",
                        "Смена алдынан кем билдәләнгән. Был — юл ваҡиғаһын тикшереү өсөн эҙ.",
                    ),
                )
            }
            // Переключатель дня: назад по дням, вперёд — не дальше сегодня.
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { dayShift += 1 }) {
                        Icon(
                            Icons.Default.ChevronLeft,
                            contentDescription = appText("День раньше", "Иртәрәк көн"),
                            tint = CanonText,
                        )
                    }
                    Text(
                        pretripDayLabel(dayShift, pretrip?.day),
                        color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                        modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
                    )
                    IconButton(onClick = { if (dayShift > 0) dayShift -= 1 }, enabled = dayShift > 0) {
                        Icon(
                            Icons.Default.ChevronRight,
                            contentDescription = appText("День позже", "Һуңғараҡ көн"),
                            tint = if (dayShift > 0) CanonText else CanonMuted,
                        )
                    }
                }
            }
            when {
                pretripLoading && pretrip == null -> item { SkeletonCard(lines = 2) }
                pretripError && pretrip == null -> item { ListedError(loadErr) { reloadPretrip() } }
                pretrip?.items.isNullOrEmpty() -> item {
                    Text(
                        appText(
                            "В этот день никто не отмечался.",
                            "Был көндә бер кем дә билдәләнмәгән.",
                        ),
                        color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp,
                    )
                }
                else -> items(
                    pretrip!!.items.size,
                    key = { "pretrip-" + pretrip!!.items[it].driverId + "-" + pretrip!!.day },
                ) { i ->
                    val e = pretrip!!.items[i]
                    Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.HealthAndSafety, contentDescription = null,
                                tint = CanonGreen2, modifier = Modifier.size(22.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(e.name, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Text(
                                    appText("Отметился в ", "Билдәләнгән: ") + shortTimeOf(e.confirmedAt),
                                    color = CanonMuted, fontSize = 12.sp,
                                )
                                if (e.note.isNotBlank()) {
                                    Text(e.note, color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Дата для запроса журнала: 0 = сегодня (пусто — день выбирает сервер), иначе ГГГГ-ММ-ДД. */
private fun pretripDayParam(shift: Int): String? {
    if (shift <= 0) return null
    val cal = java.util.Calendar.getInstance()
    cal.add(java.util.Calendar.DAY_OF_YEAR, -shift)
    return java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
}

/** Подпись дня: «Сегодня» / «Вчера» / дата с сервера — чтобы админ не считал дни в уме. */
@Composable
private fun pretripDayLabel(shift: Int, serverDay: String?): String = when (shift) {
    0 -> appText("Сегодня", "Бөгөн")
    1 -> appText("Вчера", "Кисә")
    else -> serverDay ?: pretripDayParam(shift).orEmpty()
}

/** «14:35» из ISO-времени. Не разобралось — отдаём как есть, лишь бы не пусто. */
private fun shortTimeOf(iso: String): String {
    val t = iso.substringAfter('T', "")
    return if (t.length >= 5) t.take(5) else iso
}

/** Чип фильтра статуса заявок (тач-цель ≥48dp). */
@Composable
private fun TaxiFilterChip(label: String, active: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = if (active) CanonMint else CanonSurface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, if (active) CanonGreen2 else CanonBorder),
        modifier = Modifier.height(48.dp),
    ) {
        Box(Modifier.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            Text(label, color = if (active) CanonGreen2 else CanonMuted, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}

/** Бейдж статуса заявки таксиста. */
@Composable
private fun TaxiStatusBadge(status: String) {
    val (label, fg, bg) = when (status) {
        "approved" -> Triple(appText("Одобрен", "Раҫланған"), CanonGreen2, CanonMint)
        "rejected" -> Triple(appText("Отклонён", "Кире ҡағылған"), CanonRed, CanonDangerBg)
        else -> Triple(appText("На проверке", "Тикшереүҙә"), CanonWarn, CanonWarnBg)
    }
    Surface(color = bg, shape = RoundedCornerShape(8.dp)) {
        Text(label, color = fg, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

/** Фото документа с Bearer-токеном (как DocImage в модерации водителей — тот приватный, поэтому свой). */
@Composable
private fun TaxiDocImage(url: String, token: String) {
    val ctx = LocalContext.current
    coil.compose.AsyncImage(
        model = coil.request.ImageRequest.Builder(ctx).data(url).addHeader("Authorization", "Bearer $token").crossfade(true).build(),
        contentDescription = null,
        modifier = Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(14.dp)),
        contentScale = ContentScale.Crop,
    )
}
