package com.yuldash.app

// ============================ «Режим курьера» (работа) — C1 ============================
// Если не одобрен → CTA «Стать курьером». Если одобрен → тумблер «На линии» + выбор зоны
// (🏙 город / 🛣 межгород / 🌍 регион) → доступные заказы (без телефона, «Взять»→accept) +
// «Везу» (телефон виден, «В пути»/«Доставлено» по коду). Кабинет: доставлено N · наш сбор.
// Бэкенд: GET /courier/application → /courier/me только для approved,
//         POST /courier/online|offline, GET /courier/available,
//         приём/доставка — существующие /parcels/{id}/accept, /parcels/{id}/status, /parcels/carrying.

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeliveryDining
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.LocationCity
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Percent
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.CourierApplicationDto
import com.yuldash.app.data.CourierMeDto
import com.yuldash.app.data.ParcelDto
import com.yuldash.app.data.PayCommissionDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val COURIER_REFRESH_INTERVAL_MS = 25_000L

// Две ленты свободных заказов на одном экране (раньше жили на разных и на разных эндпоинтах).
private const val COURIER_FEED_PRO = 0        // /courier/available — courier | buy_bring, только на линии
private const val COURIER_FEED_POPUTKA = 1    // /parcels/available — «по пути», линия не нужна

/** Как часто курьер шлёт свою позицию отправителям (мс). */
private const val COURIER_LOC_INTERVAL_MS = 5_000L

@Composable
internal fun CourierScreen(
    onBack: () -> Unit,
    onBecomeCourier: () -> Unit,
    // «Мой заработок» курьера: раньше он видел только «должен Юлдашу столько-то».
    onEarnings: () -> Unit = {},
) {
    var application by remember { mutableStateOf<CourierApplicationDto?>(null) }
    var applicationChecked by remember { mutableStateOf(false) }
    var applicationRefreshFailed by remember { mutableStateOf(false) }
    var me by remember { mutableStateOf<CourierMeDto?>(null) }
    var loading by remember { mutableStateOf(true) }
    var meError by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(reloadKey) {
        loading = true
        meError = false
        applicationRefreshFailed = false
        ApiClient.getCourierApplication()
            .onSuccess { latestApplication ->
                application = latestApplication
                applicationChecked = true
                if (latestApplication?.status == "approved") {
                    ApiClient.getCourierMe()
                        .onSuccess { me = it }
                        .onFailure { meError = true }
                } else {
                    // Для нового/pending/rejected /courier/me намеренно не вызываем:
                    // этот endpoint требует уже созданный профиль и раньше превращал онбординг в 404.
                    me = null
                }
            }
            .onFailure {
                applicationChecked = true
                applicationRefreshFailed = true
            }
        loading = false
    }

    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Режим курьера", "Курьер режимы"), onBack) }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val m = me
            when {
                loading && !applicationChecked -> Column(
                    Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    SkeletonCard(lines = 2)
                    SkeletonCard(lines = 3)
                    SkeletonCard(lines = 3)
                }
                application?.status == "approved" && loading && m == null -> Column(
                    Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    SkeletonCard(lines = 2)
                    SkeletonCard(lines = 3)
                    SkeletonCard(lines = 3)
                }
                application?.status == "approved" && meError && m == null -> Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.Center) {
                    AppErrorState(onRetry = { reloadKey++ })
                }
                application?.status != "approved" -> CourierNotApprovedView(
                    application = application,
                    refreshFailed = applicationRefreshFailed,
                    onRetry = { reloadKey++ },
                    onBecomeCourier = onBecomeCourier,
                )
                m == null -> Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.Center) {
                    AppErrorState(onRetry = { reloadKey++ })
                }
                else -> CourierWorkContent(m, onReloadMe = { reloadKey++ }, onEarnings = onEarnings, reloadingMe = loading)
            }
        }
    }
}

// ─────────────────────────── Не одобрен → CTA ───────────────────────────
@Composable
private fun CourierNotApprovedView(
    application: CourierApplicationDto?,
    refreshFailed: Boolean,
    onRetry: () -> Unit,
    onBecomeCourier: () -> Unit,
) {
    val status = application?.status
    val (emoji, title, body) = when (status) {
        "pending" -> Triple(
            "⏳",
            appText("Заявка на проверке", "Заявка тикшереүҙә"),
            appText("Как только мы одобрим твою заявку — здесь появятся заказы. Обычно это меньше дня.", "Заявкаңды раҫлау менән — бында заказдар күренер. Ғәҙәттә был бер көндән әҙерәк."),
        )
        "rejected" -> Triple(
            "✋",
            appText("Заявка отклонена", "Заявка кире ҡағылды"),
            appText("Загляни в заявку, исправь замечания и подай снова.", "Заявкаға кер, иҫкәрмәләрҙе төҙәт тә ҡабат бир."),
        )
        else -> Triple(
            "🛵",
            appText("Стань курьером Юлдаша", "Юлдаш курьеры бул"),
            appText("Развози посылки своим и зарабатывай. Комиссия по ступени — от 0% до 8%, всегда видна заранее.", "Үҙебеҙҙекеләргә бандеролдәр илт тә аҡса эшлә. Баҫҡыс буйынса комиссия 0%-тан 8%-ҡа тиклем, алдан уҡ күренә."),
        )
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        contentPadding = PaddingValues(vertical = 24.dp),
    ) {
        if (refreshFailed) {
            item {
                CourierRefreshStrip(
                    text = appText(
                        "Не удалось обновить статус заявки. Можно продолжить или повторить.",
                        "Заявка статусын яңыртып булманы. Дауам итергә йәки ҡабатларға мөмкин.",
                    ),
                    onRetry = onRetry,
                )
                Spacer(Modifier.height(20.dp))
            }
        }
        item {
            Surface(shape = CircleShape, color = CanonMint, border = BorderStroke(1.dp, CanonGreen2.copy(alpha = 0.3f))) {
                Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
                    // Приглашение стать курьером — брендовая иконка (в тон онлайн-герою); статусы (⏳/✋) остаются эмодзи.
                    if (emoji == "🛵") Icon(painterResource(R.drawable.yu_mode_courier), contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(48.dp))
                    else Text(emoji, fontSize = DeliveryDisplay, lineHeight = DeliveryDisplayLine)
                }
            }
            Spacer(Modifier.height(20.dp))
        }
        item {
            Text(title, color = CanonText, fontSize = DeliveryTitle, lineHeight = DeliveryTitleLine, fontWeight = FontWeight.Black, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(body, color = CanonMuted, fontSize = DeliveryBody, lineHeight = DeliveryBodyLine, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(20.dp))
        }
        item {
            AppButton(
                text = if (status == "rejected") appText("Открыть заявку", "Заявканы асыу") else if (status == "pending") appText("Открыть заявку", "Заявканы асыу") else appText("Стать курьером", "Курьер булыу"),
                onClick = onBecomeCourier,
                style = AppButtonStyle.Accent,
                icon = Icons.Default.DeliveryDining,
            )
        }
    }
}

@Composable
private fun CourierRefreshStrip(text: String, onRetry: () -> Unit) {
    Surface(color = CanonWarnBg, shape = CanonItemShape, border = BorderStroke(1.dp, CanonWarn)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text,
                color = CanonWarn,
                fontSize = DeliveryCaption,
                lineHeight = DeliveryCaptionLine,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onRetry,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(
                    appText("Повторить", "Ҡабатлау"),
                    color = CanonWarn,
                    fontWeight = FontWeight.Black,
                    fontSize = DeliveryBody,
                    lineHeight = DeliveryBodyLine,
                )
            }
        }
    }
}

// ─────────────────────────── Одобрен → работа ───────────────────────────
@Composable
private fun CourierWorkContent(
    me: CourierMeDto,
    onReloadMe: () -> Unit,
    onEarnings: () -> Unit = {},
    reloadingMe: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    var online by remember { mutableStateOf(me.profile?.online ?: false) }
    var confirmedZone by remember { mutableStateOf(me.profile?.zone?.takeIf { it.isNotBlank() } ?: "city") }
    var zone by rememberSaveable { mutableStateOf(confirmedZone) }
    var workCity by rememberSaveable { mutableStateOf(me.profile?.workCity ?: "") }
    var workCityDraft by rememberSaveable { mutableStateOf(workCity) }
    var syncedProfileUpdatedAt by rememberSaveable { mutableStateOf(me.profile?.updatedAt.orEmpty()) }
    var toggling by remember { mutableStateOf(false) }
    var configSaving by remember { mutableStateOf(false) }
    var sub by rememberSaveable { mutableIntStateOf(0) }   // 0 = доступные, 1 = везу, 2 = кабинет
    val lineBusy = toggling || configSaving
    // Что курьер везёт прямо сейчас — состояние поднято сюда из вкладки «Везу» специально:
    // от него зависит живая отправка GPS отправителям, а она обязана пережить и прокрутку
    // списка, и переход на другую под-вкладку.
    var carrying by remember { mutableStateOf<List<ParcelDto>>(emptyList()) }
    // Первое чтение «что везу» — сразу на входе в режим, не дожидаясь, пока курьер откроет
    // вкладку «Везу»: от этого списка зависит живая точка, которую видит отправитель.
    LaunchedEffect(Unit) {
        if (carrying.isEmpty()) {
            ApiClient.getCarryingParcels()
                .onSuccess { carrying = it.sortedByDescending { p -> p.acceptedAt ?: p.createdAt } }
        }
    }

    val toggleErr = appText("Не получилось изменить статус. Проверь сеть.", "Статусты үҙгәртеп булманы. Селтәрҙе тикшер.")
    val needCityMsg = appText("Укажи город работы", "Эш ҡалаһын күрһәт")

    // Если /courier/me обновился извне, локальный экран возвращается к серверной правде.
    LaunchedEffect(me.profile?.updatedAt) {
        val profile = me.profile ?: return@LaunchedEffect
        if (profile.updatedAt == syncedProfileUpdatedAt) return@LaunchedEffect
        val serverZone = profile.zone.takeIf { it.isNotBlank() } ?: "city"
        val serverCity = profile.workCity.orEmpty()
        online = profile.online
        confirmedZone = serverZone
        zone = serverZone
        workCity = serverCity
        workCityDraft = serverCity
        syncedProfileUpdatedAt = profile.updatedAt
    }

    fun setOnline(target: Boolean) {
        if (lineBusy) return
        val candidateCity = workCityDraft.trim()
        if (target && zone == "city" && candidateCity.isBlank()) {
            Toast.makeText(ctx, needCityMsg, Toast.LENGTH_SHORT).show(); return
        }
        toggling = true
        scope.launch {
            val res = if (target) ApiClient.courierOnline(zone, candidateCity.takeIf { it.isNotBlank() }, null)
            else ApiClient.courierOffline()
            res.onSuccess {
                online = target
                if (target) {
                    confirmedZone = zone
                    workCity = candidateCity
                    workCityDraft = candidateCity
                }
            }.onFailure {
                // courierOnline не подтвердил черновик — не выдаём его за активную настройку.
                if (target) {
                    zone = confirmedZone
                    workCityDraft = workCity
                }
                Toast.makeText(ctx, (it as? com.yuldash.app.data.ApiException)?.message ?: toggleErr, Toast.LENGTH_SHORT).show()
            }
            toggling = false
        }
    }

    fun changeZone(newZone: String) {
        if (newZone == zone || lineBusy) return
        val candidateCity = workCityDraft.trim()
        if (newZone == "city" && candidateCity.isBlank() && online) {
            Toast.makeText(ctx, needCityMsg, Toast.LENGTH_SHORT).show(); return
        }
        if (!online) {
            // Оффлайн это явно черновик «на следующую линию»; сервер подтвердит его при включении.
            zone = newZone
            return
        }
        configSaving = true
        scope.launch {
            ApiClient.courierOnline(newZone, candidateCity.takeIf { it.isNotBlank() }, null)
                .onSuccess {
                    confirmedZone = newZone
                    zone = newZone
                    workCity = candidateCity
                    workCityDraft = candidateCity
                }
                .onFailure {
                    zone = confirmedZone
                    workCityDraft = workCity
                    Toast.makeText(ctx, (it as? com.yuldash.app.data.ApiException)?.message ?: toggleErr, Toast.LENGTH_SHORT).show()
                }
            configSaving = false
        }
    }

    fun saveWorkCity() {
        if (!online || lineBusy) return
        val candidateCity = workCityDraft.trim()
        if (candidateCity.isBlank()) {
            Toast.makeText(ctx, needCityMsg, Toast.LENGTH_SHORT).show(); return
        }
        if (candidateCity == workCity) return
        configSaving = true
        scope.launch {
            // И зона, и город уходят одним запросом: сервер никогда не видит половину настройки.
            ApiClient.courierOnline(zone, candidateCity, null)
                .onSuccess {
                    confirmedZone = zone
                    workCity = candidateCity
                    workCityDraft = candidateCity
                }
                .onFailure {
                    workCityDraft = workCity
                    Toast.makeText(ctx, (it as? com.yuldash.app.data.ApiException)?.message ?: toggleErr, Toast.LENGTH_SHORT).show()
                }
            configSaving = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        // Главный рабочий статус — один взгляд: онлайн/оффлайн, зона и город.
        CourierLineHero(
            online = online,
            toggling = lineBusy,
            onToggle = { setOnline(it) },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CourierZoneChip(Icons.Default.LocationCity, appText("Город", "Ҡала"), zone == "city", !lineBusy) { changeZone("city") }
                CourierZoneChip(Icons.Default.AltRoute, appText("Межгород", "Ҡалалар араһы"), zone == "intercity", !lineBusy) { changeZone("intercity") }
                CourierZoneChip(Icons.Default.Public, appText("Регион", "Төбәк"), zone == "region", !lineBusy) { changeZone("region") }
            }
            if (zone == "city") {
                OutlinedTextField(
                    value = workCityDraft,
                    onValueChange = { workCityDraft = it.take(40) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !lineBusy,
                    label = { Text(appText("Город работы", "Эш ҡалаһы")) },
                    placeholder = { Text(appText("Например: Баймак", "Мәҫәлән: Баймаҡ"), color = CanonMuted) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                )
                if (workCityDraft.trim() != workCity) {
                    DeliveryHint(
                        if (online) {
                            appText("Подтверди новый город — до этого заказы остаются по прежнему.", "Яңы ҡаланы раҫла — уға тиклем заказдар элекке ҡала буйынса ҡала.")
                        } else {
                            appText("Город применится после успешного включения линии.", "Ҡала линия уңышлы ҡабыҙылғас ҡулланыласаҡ.")
                        },
                    )
                    if (online) {
                        AppButton(
                            text = appText("Сохранить город", "Ҡаланы һаҡлау"),
                            onClick = { saveWorkCity() },
                            style = AppButtonStyle.Secondary,
                            enabled = !lineBusy && workCityDraft.isNotBlank(),
                            loading = configSaving,
                        )
                    }
                }
            }
            if (!online && zone != confirmedZone) {
                DeliveryHint(appText("Настройки подтвердятся при включении линии.", "Көйләүҙәр линия ҡабыҙылғанда раҫланасаҡ."))
            }
        }
        // Под-вкладки.
        Surface(
            color = CanonSurface,
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, CanonBorder),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                CourierSubTab(appText("Заказы", "Заказдар"), sub == 0, Modifier.weight(1f)) { sub = 0 }
                CourierSubTab(appText("Везу", "Илтәм"), sub == 1, Modifier.weight(1f)) { sub = 1 }
                CourierSubTab(appText("Кабинет", "Кабинет"), sub == 2, Modifier.weight(1f)) { sub = 2 }
            }
        }
        Spacer(Modifier.height(12.dp))
        AnimatedContent(
            targetState = sub,
            modifier = Modifier.fillMaxWidth().weight(1f),
            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(140)) },
            label = "courier-sub",
        ) { s ->
            when (s) {
                0 -> CourierAvailableTab(
                    online = online,
                    lineBusy = lineBusy,
                    zone = confirmedZone,
                    workCity = if (confirmedZone == "city") workCity else "",
                    onGoOnline = { setOnline(true) },
                )
                1 -> CourierCarryingTab(online = online, list = carrying, onList = { carrying = it })
                else -> CourierCabinetTab(me, onReloadMe, onEarnings, reloadingMe)
            }
        }
        // Живая позиция отправителям. Ничего не рисует — держит каналы открытыми, пока курьер
        // в режиме курьера, независимо от прокрутки и выбранной под-вкладки.
        CourierLiveLocationLink(carrying)
    }
}

/**
 * Курьер шлёт свой GPS отправителям всех активных доставок.
 *
 * Раньше это жило внутри карточки карты, а карта лежала item-ом внутри LazyColumn: курьер
 * прокручивал список, переключал под-вкладку или сворачивал приложение — карта уничтожалась,
 * сокет закрывался, и у отправителя точка ЗАМИРАЛА (аудит 2026-08-03). Теперь отправка висит
 * на уровне всего режима курьера и не зависит от того, что сейчас на экране. Бонусом: шлём
 * во ВСЕ активные доставки, а не только в ту, чья карта открыта, — раньше остальные отправители
 * не видели курьера вообще.
 *
 * Чего этот слой всё ещё не умеет: пережить сворачивание приложения и погасший экран — Compose
 * усыпляют вместе с процессом. Для этого нужен foreground-сервис, как TripLocationService
 * у поездок (отдельная задача, файл вне этой зоны).
 *
 * Приватность как была: канал открыт только пока посылка в работе (accepted / in_transit),
 * координаты сервер не хранит, чужим канал закрыт.
 */
@Composable
private fun CourierLiveLocationLink(parcels: List<ParcelDto>) {
    val ctx = LocalContext.current
    val activeIds = remember(parcels) {
        parcels.filter { it.status == "accepted" || it.status == "in_transit" }.map { it.id }
    }
    // Отправку ведёт foreground-сервис, а не composable: экран может уйти с глаз (курьер свернул
    // приложение, погасил экран, переключил вкладку) — доставка от этого не заканчивается, и точка
    // у отправителя не должна замирать. Тот же приём, что у поездок (TripLocationService).
    // Нет точной геолокации — сервис не поднимаем: он бы показал нотификацию «отправитель видит,
    // где посылка», ничего при этом не отправляя.
    val lang = AppPrefs.language(ctx)
    DisposableEffect(activeIds, lang) {
        val allowed = ContextCompat.checkSelfPermission(
            ctx, android.Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (activeIds.isNotEmpty() && allowed) {
            CourierLocationService.start(ctx, activeIds, lang)
        } else {
            CourierLocationService.stop(ctx)
        }
        // Уходим из режима курьера — трансляцию гасим (приватность: канал живёт только под доставку).
        onDispose { CourierLocationService.stop(ctx) }
    }
}

@Composable
private fun CourierZoneChip(icon: ImageVector, label: String, active: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (active) CanonGreen2 else CanonSurface, tween(200), label = "zone")
    // Рамка и надпись тоже переезжают: раньше фон плыл, а текст с обводкой щёлкали кадром.
    val line by animateColorAsState(if (active) CanonGreen2 else CanonBorder, tween(200), label = "zone-line")
    val ink by animateColorAsState(if (active) Color.White else CanonMutedStrong, tween(200), label = "zone-ink")
    Surface(
        onClick = onClick, enabled = enabled, color = bg, shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, line),
        modifier = Modifier
            .height(48.dp)
            .semantics(mergeDescendants = true) {
                role = Role.RadioButton
                selected = active
            },
    ) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
            Text(
                label, color = ink, fontWeight = FontWeight.Bold,
                fontSize = DeliveryBody, lineHeight = DeliveryBodyLine,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Чип выбора «одно из нескольких»: какую ленту заказов смотреть, из какого города возить,
 * чью доставку показывать на карте. Один вид на все три ряда — курьер не гадает, что тут кнопка,
 * а что фильтр. Цвета переезжают, а не подменяются кадром.
 */
@Composable
private fun CourierPickChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) CanonMint else CanonSurface, tween(200), label = "pick")
    val line by animateColorAsState(if (selected) CanonGreen2 else CanonBorder, tween(200), label = "pick-line")
    val ink by animateColorAsState(if (selected) CanonGreen2 else CanonMutedStrong, tween(200), label = "pick-ink")
    Surface(
        onClick = onClick, color = bg, shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, line),
        modifier = Modifier
            .height(48.dp)
            .semantics(mergeDescendants = true) {
                role = Role.RadioButton
                this.selected = selected
            },
    ) {
        Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
            Text(
                label, color = ink, fontWeight = FontWeight.Bold,
                fontSize = DeliveryBody, lineHeight = DeliveryBodyLine,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CourierSubTab(label: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    MobilitySegmentTab(
        label = label,
        active = active,
        onClick = onClick,
        modifier = modifier,
    )
}

// ─────────────────────────── Доступные заказы ───────────────────────────
/**
 * Две ленты свободных заказов в одном месте.
 *
 * Раньше их было две в РАЗНЫХ экранах и с разными правилами: «Возить» в «Посылках» показывала
 * только заказы «по пути» (`/parcels/available`) и была открыта вообще всем, а этот экран —
 * только профессиональные (`/courier/available`), с одобрением, зоной и паузой. Человек не мог
 * знать, что половина заказов лежит на другом экране. Теперь обе ленты здесь, переключателем
 * (аудит 2026-08-03).
 *
 * Разница по смыслу сохранена: профи-лента живёт только на включённой линии (случайно заказ
 * не возьмёшь), «по пути» — обычная подработка попутчика, линия для неё не нужна, зато есть
 * фильтр по городу отправления.
 */
@Composable
private fun CourierAvailableTab(
    online: Boolean,
    lineBusy: Boolean,
    zone: String,
    workCity: String,
    onGoOnline: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var feed by rememberSaveable { mutableIntStateOf(COURIER_FEED_PRO) }
    var cityFilter by rememberSaveable { mutableStateOf("") }
    var list by remember { mutableStateOf<List<ParcelDto>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loadedQuery by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    var busyId by remember { mutableIntStateOf(0) }
    val loadErr = appText("Не удалось загрузить. Проверь интернет.", "Йөкләп булманы. Интернетты тикшер.")
    val actionErr = appText("Не получилось взять. Проверь сеть.", "Алып булманы. Селтәрҙе тикшер.")
    val tookMsg = appText("Заказ у тебя. Он во вкладке «Везу».", "Заказ һиндә. Ул «Илтәм» бүлегендә.")

    val poputka = feed == COURIER_FEED_POPUTKA
    // Линия нужна только профи-ленте: «по пути» человек берёт заодно со своей дорогой.
    val feedReady = poputka || online
    val queryKey = "$feed|$zone|${workCity.trim()}|${cityFilter.trim()}"
    val hasLoadedCurrentQuery = loadedQuery == queryKey
    val visibleList = if (hasLoadedCurrentQuery) list else emptyList()
    // Города берём из того, что реально пришло: выбранный оставляем, даже если он опустел,
    // иначе фильтр исчезал вместе с последним заказом и сбросить его было нечем.
    val cities = remember(visibleList, cityFilter) {
        (visibleList.map { it.fromCity }.filter { it.isNotBlank() } +
            listOfNotNull(cityFilter.takeIf { it.isNotBlank() })).distinct()
    }

    // Один последовательный цикл на текущую ленту+фильтр. Смена ключа отменяет старый запрос,
    // поэтому таймер, ручной retry и подтверждённая смена зоны не создают дублей.
    // В фоне цикл стоит (repeatOnLifecycle RESUMED) — не жжём батарею и трафик; возврат на экран
    // сразу тянет свежее.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(feed, online, zone, workCity, cityFilter, refreshKey, lifecycleOwner) {
        if (!feedReady) {
            // Доступные заказы быстро устаревают: после новой сессии линии сначала подтверждаем их сервером.
            loadedQuery = null
            error = null
            refreshing = false
            return@LaunchedEffect
        }
        error = null
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val res = if (poputka) {
                    ApiClient.getAvailableParcels(fromCity = cityFilter.takeIf { it.isNotBlank() })
                } else {
                    ApiClient.getCourierAvailable(fromCity = workCity.takeIf { it.isNotBlank() })
                }
                res.onSuccess { fresh ->
                    list = fresh
                    loadedQuery = queryKey
                    error = null
                }.onFailure { e ->
                    error = (e as? com.yuldash.app.data.ApiException)?.message ?: loadErr
                }
                refreshing = false
                delay(COURIER_REFRESH_INTERVAL_MS)
            }
        }
    }

    AppPullRefresh(
        refreshing = refreshing,
        onRefresh = { if (!refreshing) { refreshing = true; refreshKey++ } },
    ) {
        LazyColumn(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            item {
                MobilityScreenIntro(
                    mode = MobilityMode.Courier,
                    title = appText("Заказы рядом", "Яҡындағы заказдар"),
                    subtitle = if (poputka) appText(
                        "Едешь в другой город? Захвати посылку по пути — сумму от отправителя увидишь до принятия.",
                        "Икенсе ҡалаға бараһыңмы? Юл ыңғайы бандероль ал — ебәреүсе тәҡдим иткән сумманы алдан күрерһең.",
                    ) else appText(
                        "Маршрут и твой доход видны до принятия. Контакты откроются после.",
                        "Маршрут һәм килем заказды алғансы күренә. Контакттар һуңынан асыла.",
                    ),
                )
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CourierPickChip(appText("Курьерские", "Курьер заказдары"), !poputka) { feed = COURIER_FEED_PRO }
                    CourierPickChip(appText("По пути", "Юл ыңғайы"), poputka) { feed = COURIER_FEED_POPUTKA }
                }
            }
            if (poputka && cities.isNotEmpty()) {
                item {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CourierPickChip(appText("Все города", "Бөтә ҡалалар"), cityFilter == "") { cityFilter = "" }
                        cities.forEach { city ->
                            CourierPickChip(city, cityFilter == city) { cityFilter = if (cityFilter == city) "" else city }
                        }
                    }
                }
            }
            if (feedReady && error != null && hasLoadedCurrentQuery) {
                item {
                    CourierRefreshStrip(
                        text = appText(
                            "Не удалось обновить заказы — показываем последний список.",
                            "Заказдарҙы яңыртып булманы — һуңғы исемлекте күрһәтәбеҙ.",
                        ),
                        onRetry = { refreshKey++ },
                    )
                }
            }
            when {
                !feedReady -> {
                    item {
                        AppEmptyState(
                            title = appText("Сначала включи линию", "Тәүҙә линияны ҡабыҙ"),
                            text = appText(
                                "Курьерские заказы появятся только на линии — так никто не возьмёт заказ случайно. Заказы «по пути» можно смотреть и без линии.",
                                "Курьер заказдары тик линияла күренә — шулай заказды осраҡлы алып булмай. «Юл ыңғайы» заказдарын линияһыҙ ҙа ҡарарға була.",
                            ),
                            icon = Icons.Default.LocalShipping,
                        )
                    }
                    item {
                        AppButton(
                            text = appText("Включить линию", "Линияны ҡабыҙыу"),
                            onClick = onGoOnline,
                            style = AppButtonStyle.Primary,
                            icon = Icons.Default.LocalShipping,
                            enabled = !lineBusy,
                            loading = lineBusy,
                        )
                    }
                }
                !hasLoadedCurrentQuery && error == null -> {
                    item { SkeletonCard(lines = 3) }
                    item { SkeletonCard(lines = 3) }
                }
                error != null && !hasLoadedCurrentQuery -> item { ListedError(error ?: "") { refreshKey++ } }
                visibleList.isEmpty() -> item {
                    AppEmptyState(
                        title = appText("Свободных заказов нет", "Буш заказдар юҡ"),
                        text = appText("Загляни позже — соседи скоро что-нибудь отправят.", "Һуңыраҡ кер — күршеләр тиҙҙән берәй нәмә ебәрер."),
                        icon = Icons.Default.LocalShipping,
                    )
                }
                else -> items(visibleList.size, key = { "cav-" + visibleList[it].id }) { i ->
                    val parcel = visibleList[i]
                    Box(Modifier.appearIn(i.coerceAtMost(6))) {
                        CourierAvailableCard(
                            p = parcel,
                            busy = busyId == parcel.id,
                            canTake = !lineBusy,
                            onTake = {
                                if (lineBusy || busyId != 0) return@CourierAvailableCard
                                busyId = parcel.id
                                scope.launch {
                                    ApiClient.acceptParcel(parcel.id)
                                        .onSuccess { Toast.makeText(ctx, tookMsg, Toast.LENGTH_SHORT).show(); refreshKey++ }
                                        .onFailure { Toast.makeText(ctx, (it as? com.yuldash.app.data.ApiException)?.message ?: actionErr, Toast.LENGTH_SHORT).show() }
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

@Composable
private fun CourierAvailableCard(p: ParcelDto, busy: Boolean, canTake: Boolean, onTake: () -> Unit) {
    val deliveryLabel = when (p.deliveryType) {
        "buy_bring" -> appText("Купи и привези", "Һатып ал да килтер")
        "courier" -> appText("Курьер", "Курьер")
        else -> appText("По пути", "Юл ыңғайы")
    }
    val estimatedNetKop = courierNetKop(p.priceKop, p.commissionKop)
    val paid = p.priceKop > 0
    val priceLabel = if (paid) "≈ " + kopToRub(estimatedNetKop)
    else appText("По-соседски", "Күрше хаҡы")
    // Подпись честная: над «По-соседски» нельзя писать «Твой доход» — дохода там нет.
    val priceCaption = if (paid) appText("Твой доход", "Һинең килем")
    else appText("Без оплаты", "Түләүһеҙ")
    CourierOfferCard(
        from = p.fromCity,
        to = p.toCity,
        sizeLabel = parcelSizeLabel(p.size),
        deliveryLabel = deliveryLabel,
        priceCaption = priceCaption,
        priceLabel = priceLabel,
        description = p.description,
    ) {
        // Приватность (§8): в открытой ленте сервер адресов НЕ отдаёт — до «Взять заказ» блок
        // молчит сам собой. Как только заказ станет своим, ориентиры появятся здесь же, без
        // отдельной ветки кода: рисуем ровно то, что прислал сервер.
        ParcelAddressBlock(fromAddress = p.fromAddress, toAddress = p.toAddress, prominent = true)
        if (p.priceKop > 0) {
            Surface(shape = RoundedCornerShape(14.dp), color = CanonMint) {
                Text(
                    appText("Цена доставки: ", "Илтеү хаҡы: ") + kopToRub(p.priceKop) +
                        appText(" · комиссия ориентировочно ", " · яҡынса комиссия ") +
                        kopToRub(p.commissionKop),
                    color = CanonGreen2,
                    fontWeight = FontWeight.Bold,
                    fontSize = DeliveryCaption,
                    lineHeight = DeliveryCaptionLine,
                    modifier = Modifier.fillMaxWidth().padding(11.dp),
                )
            }
        }
        if (p.deliveryType == "buy_bring" && p.codAmountKop > 0) {
            Surface(shape = RoundedCornerShape(14.dp), color = CanonWarnBg) {
                Text(
                    appText("На покупку: ", "Һатып алыуға: ") + kopToRub(p.codAmountKop),
                    color = CanonWarn,
                    fontWeight = FontWeight.Bold,
                    fontSize = DeliveryCaption,
                    lineHeight = DeliveryCaptionLine,
                    modifier = Modifier.fillMaxWidth().padding(11.dp),
                )
            }
        }
        AppButton(
            text = appText("Взять заказ", "Заказ алыу"),
            onClick = onTake,
            style = AppButtonStyle.Primary,
            icon = Icons.Default.LocalShipping,
            enabled = canTake && !busy,
            loading = busy,
        )
    }
}

// ─────────────────────────── Везу ───────────────────────────
/**
 * Что курьер везёт сейчас (+ короткая история).
 *
 * Список приходит сверху и туда же возвращается ([onList]): его вторым потребителем стала живая
 * отправка GPS, которая живёт выше уровнем и не должна умирать вместе с этой вкладкой.
 */
@Composable
private fun CourierCarryingTab(
    online: Boolean,
    list: List<ParcelDto>,
    onList: (List<ParcelDto>) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var loading by remember { mutableStateOf(list.isEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    var busyId by remember { mutableIntStateOf(0) }
    var deliverTarget by remember { mutableStateOf<ParcelDto?>(null) }
    var goodsTarget by remember { mutableStateOf<ParcelDto?>(null) }
    var disputeTarget by remember { mutableStateOf<ParcelDto?>(null) }
    var troubleTarget by remember { mutableStateOf<ParcelDto?>(null) }   // отказ / возврат (аудит 2026-07-26)
    var rateTarget by remember { mutableStateOf<ParcelDto?>(null) }
    var ratedIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    val loadErr = appText("Не удалось загрузить. Проверь интернет.", "Йөкләп булманы. Интернетты тикшер.")
    val actionErr = appText("Не получилось. Проверь сеть и повтори.", "Булманы. Селтәрҙе тикшереп ҡабатла.")
    val transitMsg = appText("Статус обновлён: в пути", "Статус яңырҙы: юлда")
    val deliveredMsg = appText("Заказ вручён. Спасибо!", "Заказ тапшырылды. Рәхмәт!")
    val goodsSavedMsg = appText("Стоимость покупки сохранена", "Һатып алыу хаҡы һаҡланды")

    fun reload() { refreshKey++ }

    // Историю/активную доставку грузим и оффлайн один раз; на линии держим один
    // последовательный polling-цикл без перекрывающихся getCarryingParcels.
    // В фоне цикл на паузе (repeatOnLifecycle RESUMED); вернулся на экран — сразу свежее.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(online, refreshKey, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                loading = true
                ApiClient.getCarryingParcels()
                    .onSuccess {
                        onList(it.sortedByDescending { p -> p.acceptedAt ?: p.createdAt })
                        error = null
                    }
                    .onFailure { error = (it as? com.yuldash.app.data.ApiException)?.message ?: loadErr }
                loading = false
                refreshing = false
                if (!online) return@repeatOnLifecycle
                delay(COURIER_REFRESH_INTERVAL_MS)
            }
        }
    }

    AppPullRefresh(
        refreshing = refreshing,
        onRefresh = { if (!refreshing) { refreshing = true; reload() } },
    ) {
        LazyColumn(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            if (error != null && list.isNotEmpty()) {
                item {
                    CourierRefreshStrip(
                        text = appText(
                            "Не удалось обновить доставки — показываем последние данные.",
                            "Илтеүҙәрҙе яңыртып булманы — һуңғы мәғлүмәтте күрһәтәбеҙ.",
                        ),
                        onRetry = { reload() },
                    )
                }
            }
            when {
                loading && list.isEmpty() -> {
                    item { SkeletonCard(lines = 3) }
                    item { SkeletonCard(lines = 3) }
                }
                error != null && list.isEmpty() -> item { ListedError(error ?: "") { reload() } }
                list.isEmpty() -> item {
                    AppEmptyState(
                        title = appText("Ты пока ничего не везёшь", "Әлегә бер нәмә лә илтмәйһең"),
                        text = appText("Возьми заказ во вкладке «Заказы» — он появится здесь.", "«Заказдар» бүлегендә заказ ал — ул бында күренер."),
                        icon = Icons.Default.LocalShipping,
                    )
                }
                else -> {
                    // Онлайн-трекинг: карта активной доставки (курьер шлёт свой GPS отправителю).
                    // Карта одна — MapKit тяжёлый, три карты в одном списке подвесят прокрутку.
                    // Но раньше она молча показывала ПЕРВУЮ посылку: курьер вёз три, вручал вторую,
                    // а на карте был чужой маршрут. Теперь при нескольких доставках он выбирает, чью
                    // карту смотреть, а по умолчанию открыта та, что уже в пути.
                    val activeParcels = list.filter { it.status == "accepted" || it.status == "in_transit" }
                    if (activeParcels.isNotEmpty()) {
                        item(key = "ccar-track") {
                            val defaultId = (activeParcels.firstOrNull { it.status == "in_transit" }
                                ?: activeParcels.first()).id
                            var trackedId by remember(activeParcels.map { it.id }) { mutableIntStateOf(defaultId) }
                            val tracked = activeParcels.firstOrNull { it.id == trackedId } ?: activeParcels.first()
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    if (activeParcels.size > 1) appText(
                                        "Ты в пути — отправители видят тебя на карте. Выбери доставку:",
                                        "Һин юлда — ебәреүселәр һине картала күрә. Илтеүҙе һайла:",
                                    ) else appText(
                                        "Ты в пути — отправитель видит тебя на карте",
                                        "Һин юлда — ебәреүсе һине картала күрә",
                                    ),
                                    color = CanonMuted, fontSize = DeliveryCaption, lineHeight = DeliveryCaptionLine,
                                )
                                if (activeParcels.size > 1) {
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        items(activeParcels, key = { "track-chip-${it.id}" }) { p ->
                                            CourierPickChip(
                                                label = p.toCity.ifBlank { appText("Доставка", "Илтеү") } + " · №${p.id}",
                                                selected = p.id == tracked.id,
                                                onClick = { trackedId = p.id },
                                            )
                                        }
                                    }
                                }
                                ParcelTrackMap(tracked, asCourier = true)
                            }
                        }
                    }
                    items(list.size, key = { "ccar-" + list[it].id }) { i ->
                        val parcel = list[i]
                        Box(Modifier.appearIn(i.coerceAtMost(6))) {
                            CourierCarryingCard(
                                p = parcel,
                                busy = busyId == parcel.id,
                                onTransit = {
                                    if (busyId != 0) return@CourierCarryingCard
                                    busyId = parcel.id
                                    scope.launch {
                                        ApiClient.setParcelStatus(parcel.id, "in_transit")
                                            .onSuccess { Toast.makeText(ctx, transitMsg, Toast.LENGTH_SHORT).show(); reload() }
                                            .onFailure { Toast.makeText(ctx, (it as? com.yuldash.app.data.ApiException)?.message ?: actionErr, Toast.LENGTH_SHORT).show() }
                                        busyId = 0
                                    }
                                },
                                onDeliver = { deliverTarget = parcel },
                                onSetGoods = { goodsTarget = parcel },
                                onDispute = { disputeTarget = parcel },
                                onTrouble = { troubleTarget = parcel },
                                rated = ratedIds.contains(parcel.id),
                                onRate = { rateTarget = parcel },
                            )
                        }
                    }
                }
            }
        }
    }

    // C3: курьер оценивает отправителя после вручения.
    rateTarget?.let { target ->
        ParcelRateDialog(
            parcel = target,
            raterIsCourier = true,
            onDismiss = { rateTarget = null },
            onRated = { ratedIds = ratedIds + target.id; rateTarget = null },
        )
    }

    // C2: курьер вводит фактическую стоимость купленного товара (buy_bring).
    goodsTarget?.let { target ->
        var rub by remember(target.id) { mutableStateOf(((target.settlement?.goodsActualKop ?: 0) / 100).takeIf { it > 0 }?.toString() ?: "") }
        var goodsError by remember(target.id) { mutableStateOf<String?>(null) }
        var saving by remember(target.id) { mutableStateOf(false) }
        val rubInt = rub.filter(Char::isDigit).toIntOrNull()
        val goodsOk = rubInt != null && rubInt in 1..5000
        AlertDialog(
            onDismissRequest = { if (!saving) goodsTarget = null },
            containerColor = CanonSurface,
            title = { Text(appText("Стоимость покупки", "Һатып алыу хаҡы"), color = CanonText, fontWeight = FontWeight.Black, fontSize = DeliveryTitle, lineHeight = DeliveryTitleLine) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DeliveryHint(appText("Сколько ты потратил на товар? Получатель вернёт эту сумму плюс доставку.", "Тауарға күпме тотондоң? Алыусы был сумманы һәм илтеүҙе кире ҡайтарыр."))
                    OutlinedTextField(
                        value = rub,
                        onValueChange = { rub = it.filter(Char::isDigit).take(5); goodsError = null },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(appText("Сумма покупки, ₽", "Һатып алыу суммаһы, ₽")) },
                        placeholder = { Text("0", color = CanonMuted) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(14.dp),
                        singleLine = true,
                        isError = goodsError != null,
                    )
                    DeliveryHint(appText("Лимит покупки — 5000 ₽.", "Һатып алыу лимиты — 5000 ₽."))
                    DialogErrorLine(goodsError)
                }
            },
            confirmButton = {
                TextButton(
                    modifier = Modifier.heightIn(min = 48.dp),
                    enabled = !saving && goodsOk,
                    onClick = {
                        val kop = (rubInt ?: 0) * 100
                        saving = true; goodsError = null
                        scope.launch {
                            ApiClient.setGoodsCost(target.id, kop)
                                .onSuccess {
                                    Toast.makeText(ctx, goodsSavedMsg, Toast.LENGTH_SHORT).show()
                                    goodsTarget = null; reload()
                                }
                                .onFailure { goodsError = (it as? com.yuldash.app.data.ApiException)?.message ?: actionErr }
                            saving = false
                        }
                    },
                ) { Text(appText("Сохранить", "Һаҡлау"), color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = DeliveryBody) }
            },
            dismissButton = { TextButton(modifier = Modifier.heightIn(min = 48.dp), enabled = !saving, onClick = { goodsTarget = null }) { Text(appText("Отмена", "Баш тартыу"), color = CanonMuted, fontSize = DeliveryBody) } },
        )
    }

    // C2: спор по заказу (общий диалог из ParcelsScreen).
    disputeTarget?.let { target ->
        ParcelDisputeDialog(
            parcel = target,
            onDismiss = { disputeTarget = null },
            onOpened = { disputeTarget = null; reload() },
        )
    }

    // «Что-то пошло не так»: отказ от заказа и возврат посылки отправителю.
    troubleTarget?.let { target ->
        CourierTroubleDialog(
            parcel = target,
            onDismiss = { troubleTarget = null },
            onDone = { troubleTarget = null; reload() },
        )
    }

    deliverTarget?.let { target ->
        var code by remember(target.id) { mutableStateOf("") }
        var codeError by remember(target.id) { mutableStateOf<String?>(null) }
        var submitting by remember(target.id) { mutableStateOf(false) }
        // Фото «отдал целой» — граница ответственности. Поле сервер принимал давно, клиент его
        // не слал, и в споре о повреждении у курьера не было НИЧЕГО, кроме своего слова.
        var deliveryPhoto by remember(target.id) { mutableStateOf<String?>(null) }
        var photoBusy by remember(target.id) { mutableStateOf(false) }
        val photoFail = appText("Фото не загрузилось, попробуй ещё раз", "Фото йөкләнмәне, тағы ҡабатла")
        val pickDeliveryPhoto = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            photoBusy = true
            scope.launch {
                val bytes = withContext(Dispatchers.IO) { decodeToJpeg(ctx, uri) }
                if (bytes == null) { photoBusy = false; codeError = photoFail; return@launch }
                ApiClient.uploadEvidence(bytes)
                    .onSuccess { url -> if (url.isNotBlank()) deliveryPhoto = url }
                    .onFailure { codeError = photoFail }
                photoBusy = false
            }
        }
        AlertDialog(
            onDismissRequest = { if (!submitting) deliverTarget = null },
            containerColor = CanonSurface,
            title = { Text(appText("Код вручения", "Тапшырыу коды"), color = CanonText, fontWeight = FontWeight.Black, fontSize = DeliveryTitle, lineHeight = DeliveryTitleLine) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DeliveryHint(appText("Спроси код у получателя и введи его. Так подтвердим, что заказ попал по адресу.", "Кодты алыусынан һора һәм индер. Шулай заказ дөрөҫ ергә барғанын раҫлайбыҙ."))
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it; codeError = null },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(appText("Код от получателя", "Алыусы коды")) },
                        shape = RoundedCornerShape(14.dp),
                        singleLine = true,
                        isError = codeError != null,
                    )
                    DialogErrorLine(codeError)
                    // Фото «отдал целой» — необязательно, но это единственное, что отличает
                    // слово от доказательства, если получатель потом скажет «пришло битое».
                    AppButton(
                        text = when {
                            deliveryPhoto != null -> appText("Фото приложено ✓", "Фото тағылды ✓")
                            photoBusy -> appText("Загружаем фото…", "Фото йөкләнә…")
                            else -> appText("Сфотографировать при вручении", "Тапшырғанда фотоға төшөрөү")
                        },
                        onClick = { if (!photoBusy && deliveryPhoto == null) pickDeliveryPhoto.launch("image/*") },
                        style = AppButtonStyle.Secondary,
                        loading = photoBusy,
                        enabled = !photoBusy && deliveryPhoto == null,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    modifier = Modifier.heightIn(min = 48.dp),
                    enabled = !submitting && code.isNotBlank(),
                    onClick = {
                        submitting = true; codeError = null
                        scope.launch {
                            ApiClient.setParcelStatus(target.id, "delivered", code.trim(), deliveryPhoto)
                                .onSuccess {
                                    Toast.makeText(ctx, deliveredMsg, Toast.LENGTH_SHORT).show()
                                    deliverTarget = null; reload()
                                }
                                .onFailure { codeError = (it as? com.yuldash.app.data.ApiException)?.message ?: actionErr }
                            submitting = false
                        }
                    },
                ) { Text(appText("Подтвердить вручение", "Тапшырыуҙы раҫлау"), color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = DeliveryBody) }
            },
            dismissButton = { TextButton(modifier = Modifier.heightIn(min = 48.dp), enabled = !submitting, onClick = { deliverTarget = null }) { Text(appText("Отмена", "Баш тартыу"), color = CanonMuted, fontSize = DeliveryBody) } },
        )
    }
}

/**
 * Онлайн-трекинг доставки на карте (курьер ↔ отправитель) — тот же движок, что у такси.
 * asCourier=true: курьер видит СЕБЯ на маршруте (позицию берём с этого же телефона).
 * asCourier=false: отправитель ВИДИТ движущегося курьера (принимает позицию по WS).
 *
 * Отправку позиции курьера этот компонент больше НЕ держит: она переехала в
 * [CourierLiveLocationLink] уровнем выше. Карта лежит item-ом внутри LazyColumn, и вместе с
 * прокруткой умирал весь поток — у отправителя точка замирала (аудит 2026-08-03). Карта теперь
 * отвечает только за картинку, поток живёт своей жизнью.
 *
 * Приватность как у такси-трека: поток живёт лишь пока посылка в работе (accepted/in_transit),
 * координаты сервер не хранит, канал закрыт для чужих.
 */
@Composable
internal fun ParcelTrackMap(parcel: ParcelDto, asCourier: Boolean, modifier: Modifier = Modifier) {
    val from = parcel.fromLat?.let { la -> parcel.fromLng?.let { lo -> com.yandex.mapkit.geometry.Point(la, lo) } }
    val to = parcel.toLat?.let { la -> parcel.toLng?.let { lo -> com.yandex.mapkit.geometry.Point(la, lo) } }
    if (from == null && to == null) return   // без координат карту не рисуем
    val active = parcel.status == "accepted" || parcel.status == "in_transit"

    var peerPoint by remember(parcel.id) { mutableStateOf<com.yandex.mapkit.geometry.Point?>(null) }
    var peerBearing by remember(parcel.id) { mutableStateOf<Double?>(null) }

    // Отправитель: слушаем канал доставки, пока карта на экране, — это ровно та ситуация,
    // когда поток и нужен. Курьеру приёмник ни к чему: он и так знает, где он.
    if (!asCourier) {
        val sock = remember(parcel.id) {
            com.yuldash.app.data.InstantLocationSocket.forParcel(parcel.id, onPeer = { peer ->
                if (peer.role == "courier") {
                    peerPoint = com.yandex.mapkit.geometry.Point(peer.lat, peer.lng)
                    peerBearing = peer.bearing
                }
            })
        }
        DisposableEffect(parcel.id, active) {
            if (!active) return@DisposableEffect onDispose { }
            sock.connect()
            onDispose { sock.close() }
        }
    }

    // Курьер видит себя: позиция с этого телефона, отправляет её CourierLiveLocationLink.
    val myPoint by rememberMyPoint(active = asCourier && active)
    // Куда он повёрнут — считаем по смещению между точками, иначе стрелка на карте застыла бы носом на север.
    var myBearing by remember(parcel.id) { mutableStateOf<Double?>(null) }
    var prevMy by remember(parcel.id) { mutableStateOf<com.yandex.mapkit.geometry.Point?>(null) }
    LaunchedEffect(myPoint, asCourier) {
        if (!asCourier) return@LaunchedEffect
        val p = myPoint ?: return@LaunchedEffect
        prevMy?.let { q ->
            val dLat = p.latitude - q.latitude
            val dLng = p.longitude - q.longitude
            if (kotlin.math.abs(dLat) + kotlin.math.abs(dLng) >= 0.00005) {
                myBearing = (Math.toDegrees(kotlin.math.atan2(dLng * kotlin.math.cos(Math.toRadians(p.latitude)), dLat)) + 360) % 360
            }
        }
        prevMy = p
    }

    InstantRouteMap(
        from = from, to = to,
        car = if (asCourier) myPoint else peerPoint,
        carBearing = if (asCourier) myBearing else peerBearing,
        modifier = modifier.fillMaxWidth().height(192.dp).clip(CanonItemShape),
    )
}

@Composable
private fun CourierCarryingCard(
    p: ParcelDto,
    busy: Boolean,
    onTransit: () -> Unit,
    onDeliver: () -> Unit,
    onSetGoods: () -> Unit,
    onDispute: () -> Unit,
    onTrouble: () -> Unit,
    rated: Boolean,
    onRate: () -> Unit,
) {
    val delivered = p.status == "delivered"
    val canDeliver = canCourierDeliverParcel(p.status)
    val buyBring = p.deliveryType == "buy_bring"
    val needGoods = buyBring && (p.settlement?.goodsActualKop ?: 0) == 0
    val showSenderContact = (
        p.status == "accepted" || p.status == "in_transit" ||
            p.status == "returning" || p.status == "returned"
        ) && (p.senderName.isNotBlank() || p.senderPhone.isNotBlank())
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    CourierRouteRow(p.fromCity, p.toCity)
                    Text(
                        parcelSizeLabel(p.size) + (if (p.description.isNotBlank()) "  ·  ${p.description}" else ""),
                        color = CanonMuted, fontSize = DeliveryCaption, lineHeight = DeliveryCaptionLine,
                    )
                }
                ParcelStatusChip(p.status)
            }
            CourierDeliveryProgress(status = p.status)
            ParcelReturnNotice(status = p.status, reason = p.returnReason, forCourier = true)
            // Главное на карточке взятого заказа: по этим ориентирам курьер и едет. Стоит выше
            // контактов — сначала «куда рулить», потом «кому звонить». Пусто → блок не рисуется.
            ParcelAddressBlock(fromAddress = p.fromAddress, toAddress = p.toAddress, prominent = true)
            Surface(color = CanonMint, shape = CanonItemShape) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CourierContactDetails(
                        label = appText("Получатель", "Алыусы"),
                        name = p.receiverName,
                        phone = p.receiverPhone,
                    )
                    if (showSenderContact) {
                        Surface(color = CanonHairlineGreen, modifier = Modifier.fillMaxWidth().height(1.dp)) { }
                        CourierContactDetails(
                            label = if (p.status == "returning" || p.status == "returned") {
                                appText("Отправитель · точка возврата", "Ебәреүсе · ҡайтарыу урыны")
                            } else {
                                appText("Отправитель · точка забора", "Ебәреүсе · алып китеү урыны")
                            },
                            name = p.senderName,
                            phone = p.senderPhone,
                        )
                    }
                    // Половина вопросов доставки — одна фраза: «оставь у соседей», «я до шести
                    // на работе». Звонок ради этого тяжёлый, и следа не остаётся, если потом спор.
                    if (showSenderContact) {
                        AppButton(
                            text = appText("Написать отправителю", "Ебәреүсегә яҙырға"),
                            onClick = {
                                DeepLink.pendingParcelChat.value =
                                    ParcelChatTarget(p.id, peerIsCourier = false, status = p.status)
                            },
                            style = AppButtonStyle.Secondary,
                            icon = Icons.Default.ChatBubble,
                            height = 48.dp,
                        )
                    }
                }
            }
            if (buyBring) {
                val st = p.settlement
                if (st != null) ParcelSettlementBlock(st, forCourier = true)
                else if (p.codAmountKop > 0) Text(
                    appText("Выкуп товара: ", "Тауар выкупы: ") + kopToRub(p.codAmountKop),
                    color = CanonWarn, fontWeight = FontWeight.Bold,
                    fontSize = DeliveryCaption, lineHeight = DeliveryCaptionLine,
                )
            }
            when {
                p.status == "returning" || p.status == "returned" -> DeliveryHint(
                    text = appText(
                        "При возврате комиссию Юлдаша не берём. Расчёт по расходам — напрямую с отправителем.",
                        "Ҡайтарғанда Юлдаш комиссия алмай. Сығымдар буйынса ебәреүсе менән туранан-тура иҫәпләш.",
                    ),
                    tone = CanonWarn,
                )
                p.cancelFeeKop > 0 -> DeliveryHint(
                    text = appText("Компенсация от отправителя: ", "Ебәреүсенән компенсация: ") +
                        kopToRub(p.cancelFeeKop),
                    tone = CanonWarn,
                )
                else -> {
                    // Комиссия до вручения — оценка; финал считается после вручения.
                    val feeEst = if (!delivered) appText(" ≈ ориентировочно", " ≈ самаға") else ""
                    val myIncome = courierNetKop(p.priceKop, p.commissionKop)
                    DeliveryHint(
                        text = if (p.priceKop > 0) {
                            appText("Твой доход: ", "Һинең килем: ") + kopToRub(myIncome) +
                                appText(
                                    " (наш сбор ${kopToRub(p.commissionKop)}$feeEst)",
                                    " (беҙҙең сбор ${kopToRub(p.commissionKop)}$feeEst)",
                                )
                        } else appText("По-соседски, без оплаты", "Күрше хаҡы, түләүһеҙ"),
                    )
                }
            }
            if (canDeliver) {
                if (needGoods) {
                    AppButton(
                        text = appText("Указать стоимость покупки", "Һатып алыу хаҡын күрһәтеү"),
                        onClick = onSetGoods,
                        style = AppButtonStyle.Accent,
                        icon = Icons.Default.Payments,
                        enabled = !busy,
                    )
                    DeliveryHint(appText("Сначала укажи стоимость покупки — потом сможешь вручить заказ.", "Тәүҙә һатып алыу хаҡын күрһәт — шунан заказды тапшыра алырһың."))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (p.status == "accepted") {
                        AppButton(
                            text = appText("В пути", "Юлда"),
                            onClick = onTransit,
                            style = AppButtonStyle.Secondary,
                            fillWidth = false,
                            modifier = Modifier.weight(1f),
                            enabled = !busy,
                            loading = busy,
                        )
                    }
                    AppButton(
                        text = appText("Доставлено", "Тапшырылды"),
                        onClick = onDeliver,
                        style = AppButtonStyle.Primary,
                        icon = Icons.Default.CheckCircle,
                        fillWidth = false,
                        modifier = Modifier.weight(1f),
                        enabled = !busy && !needGoods,
                    )
                }
            }
            if (canCourierResolveParcelTrouble(p.status)) {
                CourierTroubleButton(returning = p.status == "returning", onClick = onTrouble)
            }
            if (delivered) {
                if (rated) ParcelRatedRow() else ParcelRateButton(onClick = onRate)
            }
            if (canOpenParcelDispute(p.status)) {
                ParcelDisputeButton(onClick = onDispute)
            }
        }
    }
}

@Composable
private fun CourierContactDetails(label: String, name: String, phone: String) {
    val spokenContact = listOf(label, name, phone).filter { it.isNotBlank() }.joinToString(". ")
    Column(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = spokenContact },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            label,
            color = CanonMutedStrong,
            fontWeight = FontWeight.Bold,
            fontSize = DeliveryCaption,
            lineHeight = DeliveryCaptionLine,
        )
        if (name.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Person, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    name,
                    color = CanonText,
                    fontWeight = FontWeight.Bold,
                    fontSize = DeliveryBody,
                    lineHeight = DeliveryBodyLine,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (phone.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Phone, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    phone,
                    color = CanonGreen2,
                    fontWeight = FontWeight.Black,
                    fontSize = DeliveryBody,
                    lineHeight = DeliveryBodyLine,
                )
            }
        }
    }
}

// ─────────────────────────── Кабинет курьера ───────────────────────────
@Composable
private fun CourierCabinetTab(
    me: CourierMeDto,
    onReloadMe: () -> Unit,
    onEarnings: () -> Unit = {},
    reloading: Boolean = false,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var paying by remember { mutableStateOf(false) }
    var payResult by remember { mutableStateOf<PayCommissionDto?>(null) }
    val payErr = appText("Не получилось оформить оплату. Проверь сеть.", "Түләүҙе рәсмиләштереп булманы. Селтәрҙе тикшер.")
    val commissionPaidMsg = appText("Комиссия оплачена. Спасибо!", "Комиссия түләнде. Рәхмәт!")

    val st = me.statement
    val owed = st.commissionOwedKop
    val currentFeePercent = courierFeePercentText(st.currentFeePercent)
    // Ставку показываем ТОЛЬКО когда сервер её прислал (feeTier непустой). Иначе поле по умолчанию
    // равно 0.0, и курьеру объявлялся «сбор Юлдаша (0%)» — обещание, которого мы не давали.
    val feeShare = if (st.feeTier.isNotBlank()) " ($currentFeePercent%)" else ""

    // Кабинет тоже тянется вниз: «Обновить» кнопкой в самом низу списка — не то место,
    // куда человек тянется рефлекторно.
    AppPullRefresh(refreshing = reloading, onRefresh = onReloadMe) {
        LazyColumn(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            // «Мой заработок» — первым делом: курьер должен видеть, СКОЛЬКО он получил, а не только
            // сколько должен. До этого раунда экрана не было вовсе (аудит 2026-07-26).
            item {
                AppButton(
                    text = appText("Мой заработок", "Минең табыш"),
                    onClick = onEarnings,
                    style = AppButtonStyle.Secondary,
                    icon = Icons.Default.Payments,
                )
            }
            // Пауза по качеству (если задана) — тёплая плашка, не ругательно.
            me.pausedUntil?.takeIf { it.isNotBlank() }?.let { until ->
                item {
                    Surface(color = CanonWarnBg, shape = CanonCardShape, border = BorderStroke(1.dp, CanonWarn)) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.PauseCircle, contentDescription = null, tint = CanonWarn, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(16.dp))
                            val until10 = until.take(10)
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(appText("Пауза по качеству", "Сифат буйынса пауза"), color = CanonWarn, fontWeight = FontWeight.Black, fontSize = DeliveryBody, lineHeight = DeliveryBodyLine)
                                Text(
                                    appText("Пауза до $until10. Подтяни рейтинг — и снова в строю. Мы рядом, поможем.", "$until10 тиклем пауза. Рейтингты күтәр — һәм ҡабат сафта. Беҙ янда, ярҙам итербеҙ."),
                                    color = CanonWarn, fontSize = DeliveryCaption, lineHeight = DeliveryCaptionLine,
                                )
                            }
                        }
                    }
                }
            }
            // Рейтинг курьера.
            item {
                val avg = me.rating.avg
                Surface(color = CanonSurface, shape = CanonCardShape, border = BorderStroke(1.dp, CanonBorder)) {
                    Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = CanonMint, shape = RoundedCornerShape(16.dp)) {
                            Icon(Icons.Default.Star, contentDescription = null, tint = CanonStar, modifier = Modifier.padding(12.dp).size(24.dp))
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(appText("Твой рейтинг", "Һинең рейтинг"), color = CanonMuted, fontWeight = FontWeight.Bold, fontSize = DeliveryCaption, lineHeight = DeliveryCaptionLine)
                            if (avg != null && me.rating.count > 0) {
                                Text(deliveryDecimal(avg) + " ★", color = CanonText, fontWeight = FontWeight.Black, fontSize = DeliveryDisplay, lineHeight = DeliveryDisplayLine)
                                Text(appText("оценок: ${me.rating.count}", "баһа: ${me.rating.count}"), color = CanonMuted, fontSize = DeliveryCaption, lineHeight = DeliveryCaptionLine)
                            } else {
                                Text(appText("Пока нет оценок", "Әлегә оценка юҡ"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = DeliveryBody, lineHeight = DeliveryBodyLine)
                                Text(appText("Первые доставки — и рейтинг появится.", "Тәүге илтеүҙәр — һәм рейтинг күренер."), color = CanonMuted, fontSize = DeliveryCaption, lineHeight = DeliveryCaptionLine)
                            }
                        }
                    }
                }
            }
            // Доставлено заказов.
            item {
                Surface(color = CanonMint, shape = CanonCardShape, border = BorderStroke(1.dp, CanonGreen2)) {
                    Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = CanonSurface, shape = RoundedCornerShape(16.dp)) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(12.dp).size(24.dp))
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(appText("Доставлено заказов", "Тапшырылған заказдар"), color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = DeliveryCaption, lineHeight = DeliveryCaptionLine)
                            Text("${st.deliveredCount}", color = CanonText, fontWeight = FontWeight.Black, fontSize = DeliveryDisplay, lineHeight = DeliveryDisplayLine)
                        }
                    }
                }
            }
            // Текущая ступень комиссии — курьер видит, сколько платит и почему.
            if (st.feeTier.isNotBlank()) {
                item {
                    val tierLine = when (st.feeTier) {
                        "promo" -> appText("🎁 Промо для первых: 0% — пользуйся!", "🎁 Тәүгеләр өсөн промо: 0% — файҙалан!")
                        "tier1" -> appText("Новичок: 3% — самая низкая ставка", "Яңы башлаусы: 3% — иң түбән ставка")
                        "tier2" -> appText("Опытный курьер: 5%", "Тәжрибәле курьер: 5%")
                        "tier3" -> appText("8% — обычная ставка", "8% — ғәҙәти ставка")
                        else -> appText("Комиссия по твоей ступени", "Баҫҡысың буйынса комиссия")
                    }
                    val promo = st.feeTier == "promo"
                    Surface(
                        color = if (promo) CanonMint else CanonSurface,
                        shape = CanonCardShape,
                        border = BorderStroke(1.dp, if (promo) CanonGreen2 else CanonBorder),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Surface(color = if (promo) CanonSurface else CanonMint, shape = RoundedCornerShape(14.dp)) {
                                    Icon(Icons.Default.Percent, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(12.dp).size(24.dp))
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(appText("Сейчас ты платишь $currentFeePercent% комиссии", "Хәҙер һин $currentFeePercent% комиссия түләйһең"), color = CanonText, fontWeight = FontWeight.Black, fontSize = DeliveryBody, lineHeight = DeliveryBodyLine)
                                    Text(tierLine, color = if (promo) CanonGreen2 else CanonMuted, fontWeight = FontWeight.Bold, fontSize = DeliveryCaption, lineHeight = DeliveryCaptionLine)
                                }
                            }
                            if (st.commissionMinKop > 0) {
                                DeliveryHint(
                                    appText(
                                        "Комиссия минимум ${kopToRub(st.commissionMinKop)} за доставку. Всё прозрачно — видно, сколько и за что.",
                                        "Комиссия иң кәме ${kopToRub(st.commissionMinKop)} бер илтеү өсөн. Барыһы ла асыҡ — күпме һәм ни өсөн икәне күренә.",
                                    ),
                                )
                            }
                        }
                    }
                }
            }
            // Комиссия: заработали · к оплате (крупно) · оплачено.
            item {
                Surface(color = CanonSurface, shape = CanonCardShape, border = BorderStroke(1.dp, if (owed > 0) CanonGreen2 else CanonBorder)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Payments, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(24.dp))
                            Text(appText("Наша комиссия за доставки", "Илтеүҙәр өсөн беҙҙең комиссия"), color = CanonText, fontWeight = FontWeight.Black, fontSize = DeliveryBody, lineHeight = DeliveryBodyLine)
                        }
                        DeliveryHint(
                            appText("Это сбор Юлдаша$feeShare за то, что мы свели тебя с заказами. Твой доход остаётся у тебя — сюда попадает только наша часть.", "Был — заказдар менән таныштырғаныбыҙ өсөн Юлдаш сборы$feeShare. Килемең үҙеңдә ҡала — бында тик беҙҙең өлөш."),
                        )
                        StatementRow(appText("Всего заработали мы", "Барлығы беҙ эшләнек"), kopToRub(st.commissionEarnedKop), CanonMuted)
                        StatementRow(appText("Уже оплачено", "Түләнгән"), kopToRub(st.commissionPaidKop), CanonGreen2)
                        // К оплате сейчас — крупно.
                        Surface(color = if (owed > 0) CanonMint else CanonBg, shape = CanonItemShape) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    appText("К оплате сейчас", "Хәҙер түләргә"), color = CanonText, fontWeight = FontWeight.Black,
                                    fontSize = DeliveryBody, lineHeight = DeliveryBodyLine, modifier = Modifier.weight(1f),
                                )
                                Text(kopToRub(owed), color = if (owed > 0) CanonGreen2 else CanonMuted, fontWeight = FontWeight.Black, fontSize = DeliveryDisplay, lineHeight = DeliveryDisplayLine)
                            }
                        }
                        if (owed > 0) {
                            AppButton(
                                text = appText("Оплатить комиссию", "Комиссияны түләү"),
                                onClick = {
                                    if (paying) return@AppButton
                                    paying = true
                                    scope.launch {
                                        ApiClient.payCommission()
                                            .onSuccess onPaid@{ res ->
                                                when {
                                                    // ЮKassa (по флажку): в браузер оплаты, статус — поллингом.
                                                    res.method == "yookassa" && res.status == "pending" && !res.confirmationUrl.isNullOrBlank() -> {
                                                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(res.confirmationUrl))) }
                                                        paying = false
                                                        repeat(6) {
                                                            kotlinx.coroutines.delay(4000)
                                                            if (ApiClient.getPaymentStatus(res.paymentId).getOrNull()?.status == "succeeded") {
                                                                Toast.makeText(ctx, commissionPaidMsg, Toast.LENGTH_LONG).show(); onReloadMe(); return@onPaid
                                                            }
                                                        }
                                                        onReloadMe()
                                                        return@onPaid
                                                    }
                                                    res.status == "succeeded" -> { Toast.makeText(ctx, commissionPaidMsg, Toast.LENGTH_LONG).show(); onReloadMe() }
                                                    else -> payResult = res   // СБП «на доверии» → показать реквизиты
                                                }
                                            }
                                            .onFailure { Toast.makeText(ctx, (it as? com.yuldash.app.data.ApiException)?.message ?: payErr, Toast.LENGTH_LONG).show() }
                                        paying = false
                                    }
                                },
                                style = AppButtonStyle.Accent,
                                icon = Icons.Default.Payments,
                                loading = paying,
                            )
                            DeliveryHint(
                                appText("Переведи сумму по СБП на реквизиты Юлдаша — админ подтвердит оплату вручную.", "Сумманы СБП аша Юлдаш реквизиттарына күсер — админ түләүҙе ҡулдан раҫлар."),
                            )
                        } else {
                            Text(
                                appText("Долгов нет — спасибо, что возишь по-честному.", "Бурыс юҡ — намыҫлы илткәнең өсөн рәхмәт."),
                                color = CanonGreen2, fontSize = DeliveryCaption, lineHeight = DeliveryCaptionLine, fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
            me.profile?.let { pr ->
                item {
                    Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                appText("Транспорт: ", "Транспорт: ") + courierTransportLabel(me.application?.transport ?: ""),
                                color = CanonText, fontSize = DeliveryBody, lineHeight = DeliveryBodyLine,
                            )
                            Text(
                                appText("Статус: ", "Статус: ") + if (pr.online) appText("на линии", "линияла") else appText("не на линии", "линияла түгел"),
                                color = if (pr.online) CanonGreen2 else CanonMuted, fontSize = DeliveryBody, lineHeight = DeliveryBodyLine, fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
            item {
                AppButton(appText("Обновить", "Яңыртыу"), onReloadMe, style = AppButtonStyle.Secondary)
            }
        }
    }

    // Реквизиты СБП после оформления оплаты (переиспользуем общий лист донат/буста).
    payResult?.let { pr ->
        SbpTransferSheet(
            amountRub = pr.amountKop / 100,
            onPaid = { payResult = null; onReloadMe() },
            onDismiss = { payResult = null },
            payeePhone = pr.payeePhone,
            payeeBank = pr.payeeBank,
            payeeName = pr.payeeName,
        )
    }
}

@Composable
private fun StatementRow(label: String, value: String, valueColor: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = CanonMuted, fontSize = DeliveryCaption, lineHeight = DeliveryCaptionLine, modifier = Modifier.weight(1f))
        Text(value, color = valueColor, fontWeight = FontWeight.Bold, fontSize = DeliveryCaption, lineHeight = DeliveryCaptionLine)
    }
}

// ─────────────────────────── Общие мелочи ───────────────────────────
/** Строка «маршрут»: Откуда → Куда. Города сжимаются, а не уезжают за экран:
 *  башкирские названия длиннее русских, и без weight+ellipsis «Ҡара-Йылға» ломал карточку. */
@Composable
private fun CourierRouteRow(from: String, to: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Default.Place, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(16.dp))
        Text(
            from.ifBlank { "—" }, color = CanonText, fontWeight = FontWeight.Bold,
            fontSize = DeliveryBody, lineHeight = DeliveryBodyLine,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
        )
        Text("→", color = CanonMuted, fontSize = DeliveryBody)
        Text(
            to.ifBlank { "—" }, color = CanonText, fontWeight = FontWeight.Bold,
            fontSize = DeliveryBody, lineHeight = DeliveryBodyLine,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
        )
    }
}

/** Ставка комиссии в проценты без лишних нулей: 0.0→«0», 3.0→«3», 7.5→«7,5».
 *  Это цифра про деньги, поэтому разделитель задаём явно (deliveryDecimal), а не отдаём локали
 *  телефона: старая замена точки на запятую под ru-локалью не срабатывала вовсе, а под en-локалью
 *  курьер видел «7.5» там, где остальной экран пишет «7,5» (аудит 2026-08-03). */
private fun courierFeePercentText(pct: Double): String =
    if (pct % 1.0 == 0.0) pct.toInt().toString() else deliveryDecimal(pct)

/** Ярлык типа доставки + срочности (для карточек курьера). */
@Composable
internal fun CourierDeliveryTag(deliveryType: String, urgency: String) {
    val (label, bg, fg) = when (deliveryType) {
        "buy_bring" -> Triple(appText("Купи и привези", "Ал да килтер"), CanonWarnBg, CanonWarn)
        "courier" -> Triple(appText("Курьер", "Курьер"), CanonMint, CanonGreen2)
        else -> Triple(appText("По пути", "Юл ыңғайы"), CanonMint, CanonGreen2)
    }
    Surface(color = bg, shape = RoundedCornerShape(8.dp)) {
        Text(
            label + if (urgency == "now") appText(" · срочно", " · ашығыс") else "",
            color = fg, fontWeight = FontWeight.Bold,
            fontSize = DeliveryCaption, lineHeight = DeliveryCaptionLine,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
