package com.yuldash.app

// ============================ «Режим курьера» (работа) — C1 ============================
// Если не одобрен → CTA «Стать курьером». Если одобрен → тумблер «На линии» + выбор зоны
// (🏙 город / 🛣 межгород / 🌍 регион) → доступные заказы (без телефона, «Взять»→accept) +
// «Везу» (телефон виден, «В пути»/«Доставлено» по коду). Кабинет: доставлено N · наш сбор.
// Бэкенд: GET /courier/application → /courier/me только для approved,
//         POST /courier/online|offline, GET /courier/available,
//         приём/доставка — существующие /parcels/{id}/accept, /parcels/{id}/status, /parcels/carrying.

import android.content.Context
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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeliveryDining
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.LocationCity
import androidx.compose.material.icons.filled.Map
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

/**
 * Открыть внешний навигатор к точке: Яндекс Навигатор → Яндекс Карты → любое geo:-приложение.
 *
 * Координаты у заказа были всегда, а маршрута не было: курьер читал «синие ворота у мечети»
 * и искал их объездом по селу. Функция локальная, а не общая с такси, сознательно — экран такси
 * сейчас правит другой человек, и лезть туда за одной строкой значило бы затереть чужую работу.
 *
 * Ничего из трёх не установлено → тихо ничего не делаем: кнопка навигации не должна ронять экран.
 */
private fun openCourierNavigator(ctx: Context, lat: Double, lng: Double) {
    val uris = listOf(
        "yandexnavi://build_route_on_map?lat_to=$lat&lon_to=$lng",
        "yandexmaps://maps.yandex.ru/?rtext=~$lat,$lng&rtt=auto",
        "geo:$lat,$lng?q=$lat,$lng",
    )
    for (u in uris) {
        val ok = runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) }.isSuccess
        if (ok) return
    }
}

/**
 * Что за груз — вес, тип и «хрупкое» — в карточке заказа.
 *
 * Главное здесь — что строка стоит и в ОТКРЫТОЙ ленте, до принятия: «беру / не беру» курьер
 * решает именно по ней. Размер отвечает только на «влезет ли», а коробка 40×40 бывает и подушкой
 * на 3 кг, и картошкой на 40 кг — по размеру этого не понять.
 *
 * Пустые значения не рисуем вовсе: строка «Вес: —» хуже, чем её отсутствие. Если отправитель
 * ничего не указал, блока просто нет.
 *
 * FlowRow: длинный башкирский («Аҙыҡ-түлек») и крупный системный шрифт не должны обрезать теги —
 * они переносятся на новую строку.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CourierCargoRow(p: ParcelDto) {
    val hasWeight = p.weightKg > 0.0
    val hasType = p.cargoType.isNotBlank()
    if (!hasWeight && !hasType && !p.fragile) return
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (hasWeight) {
            CourierCargoTag("⚖️  " + parcelWeightLabel(p.weightKg), CanonMint, CanonGreen2)
        }
        if (hasType) {
            CourierCargoTag(cargoTypeEmoji(p.cargoType) + "  " + cargoTypeLabel(p.cargoType), CanonMint, CanonGreen2)
        }
        // «Хрупкое» — предупреждение, а не ещё один нейтральный тег: от него зависит, как курьер
        // положит коробку в багажник. Поэтому тревожная подложка и рамка, а не общая мятная.
        if (p.fragile) {
            CourierCargoTag(
                "⚠️  " + appText("Хрупкое", "Ватыла торған"),
                CanonWarnBg, CanonWarn, bordered = true,
            )
        }
    }
}

/** Один тег груза. [bordered] — для «хрупкого»: обводка делает его заметным среди спокойных. */
@Composable
private fun CourierCargoTag(text: String, bg: Color, ink: Color, bordered: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = bg,
        border = if (bordered) BorderStroke(1.dp, ink) else null,
    ) {
        Text(
            text, color = ink, fontWeight = FontWeight.Bold,
            fontSize = DeliveryCaption, lineHeight = DeliveryCaptionLine,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
internal fun CourierScreen(
    onBack: () -> Unit,
    onBecomeCourier: () -> Unit,
    // «Мой заработок» курьера: раньше он видел только «должен Юлдашу столько-то».
    onEarnings: () -> Unit = {},
    // Фотоконтроль машины (580-ФЗ): две стороны кузова и багажник раз в две недели.
    onCarPhoto: () -> Unit = {},
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
                application?.status == "approved" && meError && m == null -> Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Center) {
                    AppErrorState(onRetry = { reloadKey++ })
                }
                // Без одобренной заявки человек ВСЁ РАВНО видит заказы «по пути» (найдено при
                // живой проверке на эмуляторе 2026-08-03). Раньше весь экран был заперт за
                // заявкой профи-курьера — с селфи, госномером и ручной модерацией. Но «по пути»
                // везёт сосед, который и так едет в Сибай: требовать от него анкету перевозчика
                // значит убить главный сценарий доставки «между своими». Сервер этого и не
                // требовал — `/parcels/available` открыт любому вошедшему; запрет жил только
                // в приложении. Профи-лента («Курьерские») по-прежнему за заявкой.
                application?.status != "approved" -> Column(Modifier.fillMaxSize()) {
                    CourierNotApprovedView(
                        application = application,
                        refreshFailed = applicationRefreshFailed,
                        onRetry = { reloadKey++ },
                        onBecomeCourier = onBecomeCourier,
                        compact = true,
                    )
                    CourierAvailableTab(
                        online = false,
                        lineBusy = false,
                        zone = "region",
                        workCity = "",
                        onGoOnline = {},
                        poputkaOnly = true,
                    )
                }
                m == null -> Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Center) {
                    AppErrorState(onRetry = { reloadKey++ })
                }
                else -> CourierWorkContent(m, onReloadMe = { reloadKey++ }, onEarnings = onEarnings,
                                           onCarPhoto = onCarPhoto, reloadingMe = loading)
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
    // compact=true — приглашение стоит НАД лентой «по пути», а не вместо неё. Тогда оно
    // обязано быть узкой карточкой: два прокручиваемых списка в одной колонке не уживаются.
    compact: Boolean = false,
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
            appText("Развози посылки своим и зарабатывай. Текущая ставка комиссии — в кабинете курьера.", "Үҙебеҙҙекеләргә бандеролдәр илт тә аҡса эшлә. Хәҙерге комиссия ставкаһы — курьер кабинетында."),
        )
    }
    if (compact) {
        Surface(
            color = CanonMint,
            shape = CanonCardShape,
            border = BorderStroke(1.dp, CanonGreen2.copy(alpha = 0.3f)),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(
                Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (emoji == "🛵") {
                    Icon(painterResource(R.drawable.yu_mode_courier), contentDescription = null,
                        tint = CanonGreen2, modifier = Modifier.size(28.dp))
                } else {
                    Text(emoji, fontSize = DeliveryTitle, lineHeight = DeliveryTitleLine)
                }
                Column(Modifier.weight(1f)) {
                    Text(title, color = CanonText, fontSize = DeliveryBody, lineHeight = DeliveryBodyLine,
                        fontWeight = FontWeight.Bold)
                    Text(
                        appText(
                            "Заказы «по пути» бери прямо сейчас — заявка нужна только для курьерских.",
                            "«Юл ыңғайы» заказдарын хәҙер үк ал — заявка тик курьер заказдары өсөн кәрәк.",
                        ),
                        color = CanonMuted, fontSize = DeliveryCaption, lineHeight = DeliveryCaptionLine,
                    )
                }
                TextButton(onClick = onBecomeCourier) {
                    Text(
                        if (status == null) appText("Стать", "Булыу") else appText("Заявка", "Ғариза"),
                        color = CanonGreen2, fontSize = DeliveryCaption, fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
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
                Spacer(Modifier.height(16.dp))
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
            Spacer(Modifier.height(16.dp))
        }
        item {
            Text(title, color = CanonText, fontSize = DeliveryTitle, lineHeight = DeliveryTitleLine, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(body, color = CanonMuted, fontSize = DeliveryBody, lineHeight = DeliveryBodyLine, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(16.dp))
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
    // Вид и поведение — общие ([AppStaleStrip]); у курьера только свой текст про доставки.
    AppStaleStrip(onRetry = onRetry, text = text)
}

// ─────────────────────────── Одобрен → работа ───────────────────────────
@Composable
private fun CourierWorkContent(
    me: CourierMeDto,
    onReloadMe: () -> Unit,
    onEarnings: () -> Unit = {},
    onCarPhoto: () -> Unit = {},
    reloadingMe: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    var online by remember { mutableStateOf(me.profile?.online ?: false) }
    var confirmedZone by remember { mutableStateOf(me.profile?.zone?.takeIf { it.isNotBlank() } ?: "city") }
    var zone by rememberSaveable { mutableStateOf(confirmedZone) }
    var workCity by rememberSaveable { mutableStateOf(me.profile?.workCity ?: "") }
    var workCityDraft by rememberSaveable { mutableStateOf(workCity) }
    // Зона по-новому: база (город/село ИЛИ район) + два согласия — выезд загород и соседние
    // регионы. Черновик и подтверждённое значение живут раздельно, как у города: пока сервер
    // не принял настройку, курьер не должен видеть её как действующую.
    var workDistrict by rememberSaveable { mutableStateOf(me.profile?.workDistrict ?: "") }
    var workDistrictDraft by rememberSaveable { mutableStateOf(workDistrict) }
    var intercity by rememberSaveable { mutableStateOf(me.profile?.workIntercity ?: false) }
    var regions by rememberSaveable { mutableStateOf(me.profile?.workRegions ?: false) }
    var syncedProfileUpdatedAt by rememberSaveable { mutableStateOf(me.profile?.updatedAt.orEmpty()) }
    var toggling by remember { mutableStateOf(false) }
    var configSaving by remember { mutableStateOf(false) }
    var sub by rememberSaveable { mutableIntStateOf(0) }   // 0 = доступные, 1 = везу, 2 = кабинет
    // Экран заработка попросил показать заказы — выполняем и гасим сигнал.
    LaunchedEffect(NavSignals.openCourierOrders.value) {
        if (NavSignals.openCourierOrders.value) { sub = 0; NavSignals.openCourierOrders.value = false }
    }
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

    // ❄️ Зимний протокол у курьера. Он едет по той же зимней трассе, что попутка и такси,
    // но едет ОДИН: рядом нет пассажира, который заметит, что что-то не так. Раньше протокола
    // здесь не было вовсе (аудит 2026-08-06).
    //
    // Спрашиваем по самой ранней из везомых посылок: если курьер молчит, неважно, какая
    // из них — важно, что молчит он сам.
    val winterAsked = rememberSaveable { mutableStateOf(false) }
    val winterShow = rememberSaveable { mutableStateOf(false) }
    val winterParcel = carrying.minByOrNull { it.acceptedAt ?: it.createdAt }
    WinterArrivalWatcher(
        key = winterParcel?.id,
        startMs = { winterParcel?.acceptedAt?.let(::parseIsoUtcMillis) },
        active = { winterParcel != null },
        asked = winterAsked,
        show = winterShow,
        onArm = { winterParcel?.id?.let { ApiClient.winterCheckParcel(it) } },
    )
    WinterArrivalDialog(winterShow) {
        val pid = winterParcel?.id
        if (pid != null) scope.launch { ApiClient.winterCheckParcelOk(pid) }
    }

    val toggleErr = appText("Не получилось изменить статус. Проверь сеть.", "Статусты үҙгәртеп булманы. Селтәрҙе тикшер.")
    val needCityMsg = appText("Укажи город работы", "Эш ҡалаһын күрһәт")
    val needDistrictMsg = appText("Укажи район работы", "Эш районын күрһәт")

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
        workDistrict = profile.workDistrict.orEmpty()
        workDistrictDraft = profile.workDistrict.orEmpty()
        intercity = profile.workIntercity
        regions = profile.workRegions
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
            val res = if (target) ApiClient.courierOnline(
                zone, candidateCity.takeIf { it.isNotBlank() }, null,
                workDistrict = workDistrictDraft.trim().takeIf { zone == "district" && it.isNotBlank() },
                workIntercity = intercity, workRegions = regions,
            ) else ApiClient.courierOffline()
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
            ApiClient.courierOnline(
                newZone, candidateCity.takeIf { it.isNotBlank() }, null,
                workDistrict = workDistrictDraft.trim().takeIf { newZone == "district" && it.isNotBlank() },
                workIntercity = intercity, workRegions = regions,
            ).onSuccess {
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

    /** Тумблеры «загород»/«регионы» на линии — отправляем той же ручкой, что и зону. */
    fun changeZoneFlags() {
        if (!online || lineBusy) return
        configSaving = true
        scope.launch {
            ApiClient.courierOnline(
                zone, workCityDraft.trim().takeIf { zone == "city" && it.isNotBlank() }, null,
                workDistrict = workDistrictDraft.trim().takeIf { zone == "district" && it.isNotBlank() },
                workIntercity = intercity, workRegions = regions,
            ).onFailure {
                // Сервер не принял — возвращаем тумблеры к серверной правде, а не к желаемой.
                intercity = me.profile?.workIntercity ?: false
                regions = me.profile?.workRegions ?: false
                Toast.makeText(ctx, (it as? com.yuldash.app.data.ApiException)?.message ?: toggleErr, Toast.LENGTH_SHORT).show()
            }
            configSaving = false
        }
    }

    fun saveWorkDistrict() {
        if (!online || lineBusy) return
        val candidate = workDistrictDraft.trim()
        if (candidate.isBlank()) {
            Toast.makeText(ctx, needDistrictMsg, Toast.LENGTH_SHORT).show(); return
        }
        if (candidate == workDistrict) return
        configSaving = true
        scope.launch {
            ApiClient.courierOnline(
                zone, null, null,
                workDistrict = candidate, workIntercity = intercity, workRegions = regions,
            ).onSuccess {
                confirmedZone = zone
                workDistrict = candidate
                workDistrictDraft = candidate
            }.onFailure {
                workDistrictDraft = workDistrict
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
            ApiClient.courierOnline(
                zone, candidateCity, null,
                workDistrict = workDistrictDraft.trim().takeIf { zone == "district" && it.isNotBlank() },
                workIntercity = intercity, workRegions = regions,
            ).onSuccess {
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
            // База: где я вожу вообще. Один НП или весь район — как «Мой район» у Яндекс Про.
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CourierZoneChip(Icons.Default.LocationCity, appText("Мой город", "Минең ҡалам"), zone == "city", !lineBusy) { changeZone("city") }
                CourierZoneChip(Icons.Default.Map, appText("Мой район", "Минең районым"), zone == "district", !lineBusy) { changeZone("district") }
            }
            // Согласия поверх базы: выезд за неё и в соседние регионы. Выключил «загород» —
            // «регионы» гаснут сами: без выезда они ничего не значат.
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CourierZoneChip(Icons.Default.AltRoute, appText("Выезд загород", "Ҡала тышына"), intercity, !lineBusy) {
                    intercity = !intercity
                    if (!intercity) regions = false
                    if (online) changeZoneFlags()
                }
                CourierZoneChip(Icons.Default.Public, appText("Соседние регионы", "Күрше төбәктәр"), regions, !lineBusy && intercity) {
                    regions = !regions
                    if (online) changeZoneFlags()
                }
            }
            if (zone == "district") {
                // Подсказки из справочника: район с опечаткой сервер не примет, а человек
                // будет сидеть без заказов и гадать, что не так.
                DistrictPickInput(
                    value = workDistrictDraft,
                    onChange = { workDistrictDraft = it.take(40) },
                    enabled = !lineBusy,
                )
                if (workDistrictDraft.trim() != workDistrict) {
                    DeliveryHint(
                        if (online) {
                            appText("Подтверди новый район — до этого заказы остаются по прежнему.",
                                "Яңы районды раҫла — уға тиклем заказдар элеккесә ҡала.")
                        } else {
                            appText("Район применится после успешного включения линии.",
                                "Район линия уңышлы ҡабыҙылғас ҡулланыласаҡ.")
                        },
                    )
                    if (online) {
                        AppButton(
                            text = appText("Сохранить район", "Районды һаҡлау"),
                            onClick = { saveWorkDistrict() },
                            style = AppButtonStyle.Secondary,
                            enabled = !lineBusy && workDistrictDraft.isNotBlank(),
                            loading = configSaving,
                        )
                    }
                }
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
            shape = RoundedCornerShape(14.dp),
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
            transitionSpec = { fadeIn(tween(CanonMotion.QUICK)) togetherWith fadeOut(tween(CanonMotion.QUICK)) },
            label = "courier-sub",
        ) { s ->
            when (s) {
                0 -> CourierAvailableTab(
                    online = online,
                    lineBusy = lineBusy,
                    // Зона в ключе списка — чтобы после смены района или тумблеров лента
                    // перезапросилась: иначе курьер видел бы старую подборку заказов.
                    zone = "$confirmedZone|${if (workDistrict.isNotBlank()) workDistrict else "-"}|$intercity|$regions",
                    workCity = if (confirmedZone == "city") workCity else "",
                    onGoOnline = { setOnline(true) },
                )
                1 -> CourierCarryingTab(online = online, list = carrying, onList = { carrying = it }, onGoOrders = { sub = 0 })
                else -> CourierCabinetTab(me, onReloadMe, onEarnings, onCarPhoto, reloadingMe)
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
    val bg by animateColorAsState(if (active) CanonGreen2 else CanonSurface, tween(CanonMotion.QUICK), label = "zone")
    // Рамка и надпись тоже переезжают: раньше фон плыл, а текст с обводкой щёлкали кадром.
    val line by animateColorAsState(if (active) CanonGreen2 else CanonBorder, tween(CanonMotion.QUICK), label = "zone-line")
    val ink by animateColorAsState(if (active) Color.White else CanonMutedStrong, tween(CanonMotion.QUICK), label = "zone-ink")
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
    val bg by animateColorAsState(if (selected) CanonMint else CanonSurface, tween(CanonMotion.QUICK), label = "pick")
    val line by animateColorAsState(if (selected) CanonGreen2 else CanonBorder, tween(CanonMotion.QUICK), label = "pick-line")
    val ink by animateColorAsState(if (selected) CanonGreen2 else CanonMutedStrong, tween(CanonMotion.QUICK), label = "pick-ink")
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
        Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
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
    // Человек БЕЗ заявки профи-курьера. Ему доступна только лента «по пути» — и это не
    // ограничение, а её смысл: везёт сосед, который и так едет, а не нанятый курьер.
    poputkaOnly: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var feed by rememberSaveable { mutableIntStateOf(if (poputkaOnly) COURIER_FEED_POPUTKA else COURIER_FEED_PRO) }
    var cityFilter by rememberSaveable { mutableStateOf("") }
    // Куда. Сервер этот фильтр принимал всегда, клиент его просто не слал — и курьер, который
    // едет в Сибай, листал заказы во все стороны подряд. Работает в обеих лентах: «по пути»
    // без него теряет весь смысл, но и профи-курьеру важно набрать заказы в одну сторону.
    var toCityFilter by rememberSaveable { mutableStateOf("") }
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
    val queryKey = "$feed|$zone|${workCity.trim()}|${cityFilter.trim()}|${toCityFilter.trim()}"
    val hasLoadedCurrentQuery = loadedQuery == queryKey
    val visibleList = if (hasLoadedCurrentQuery) list else emptyList()
    // Города берём из того, что реально пришло: выбранный оставляем, даже если он опустел,
    // иначе фильтр исчезал вместе с последним заказом и сбросить его было нечем.
    val cities = remember(visibleList, cityFilter) {
        (visibleList.map { it.fromCity }.filter { it.isNotBlank() } +
            listOfNotNull(cityFilter.takeIf { it.isNotBlank() })).distinct()
    }
    val toCities = remember(visibleList, toCityFilter) {
        (visibleList.map { it.toCity }.filter { it.isNotBlank() } +
            listOfNotNull(toCityFilter.takeIf { it.isNotBlank() })).distinct()
    }
    val anyFilter = cityFilter.isNotBlank() || toCityFilter.isNotBlank()

    // Один последовательный цикл на текущую ленту+фильтр. Смена ключа отменяет старый запрос,
    // поэтому таймер, ручной retry и подтверждённая смена зоны не создают дублей.
    // В фоне цикл стоит (repeatOnLifecycle RESUMED) — не жжём батарею и трафик; возврат на экран
    // сразу тянет свежее.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(feed, online, zone, workCity, cityFilter, toCityFilter, refreshKey, lifecycleOwner) {
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
                    ApiClient.getAvailableParcels(
                        fromCity = cityFilter.takeIf { it.isNotBlank() },
                        toCity = toCityFilter.takeIf { it.isNotBlank() },
                    )
                } else {
                    ApiClient.getCourierAvailable(
                        fromCity = workCity.takeIf { it.isNotBlank() },
                        toCity = toCityFilter.takeIf { it.isNotBlank() },
                    )
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
            if (!poputkaOnly) {
                item {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CourierPickChip(appText("Курьерские", "Курьер заказдары"), !poputka) { feed = COURIER_FEED_PRO }
                        CourierPickChip(appText("По пути", "Юл ыңғайы"), poputka) { feed = COURIER_FEED_POPUTKA }
                    }
                }
            }
            // Два ряда фильтров — «откуда» и «куда». Подписи обязательны: без них два одинаковых
            // ряда чипов подряд читались бы как один сбойный список.
            if (poputka && cities.isNotEmpty()) {
                item {
                    CourierFilterRow(appText("Откуда", "Ҡайҙан")) {
                        CourierPickChip(appText("Все города", "Бөтә ҡалалар"), cityFilter == "") { cityFilter = "" }
                        cities.forEach { city ->
                            CourierPickChip(city, cityFilter == city) { cityFilter = if (cityFilter == city) "" else city }
                        }
                    }
                }
            }
            // «Куда» — главный фильтр курьера: он отбирает заказы себе ПО ПУТИ, а не по тому,
            // из какого села их шлют. Работает в обеих лентах.
            if (toCities.isNotEmpty()) {
                item {
                    CourierFilterRow(appText("Куда", "Ҡайҙа")) {
                        CourierPickChip(appText("Любое направление", "Теләһә ҡайҙа"), toCityFilter == "") { toCityFilter = "" }
                        toCities.forEach { city ->
                            CourierPickChip(city, toCityFilter == city) { toCityFilter = if (toCityFilter == city) "" else city }
                        }
                    }
                }
            }
            // Текст зависит от того, есть ли что показывать. Раньше строка была одна, и экран
            // противоречил сам себе: сверху «показываем последний список», а сразу под ним
            // «Свободных заказов нет». Поймал запуском на эмуляторе с оборванной связью.
            // Обещать список можно, только когда он есть; при пустом — просто честно про сбой,
            // иначе «заказов нет» читается как правда, хотя это всего лишь несостоявшийся запрос.
            if (feedReady && error != null && hasLoadedCurrentQuery) {
                item {
                    CourierRefreshStrip(
                        text = if (visibleList.isNotEmpty()) appText(
                            "Не удалось обновить заказы — показываем последний список.",
                            "Заказдарҙы яңыртып булманы — һуңғы исемлекте күрһәтәбеҙ.",
                        ) else appText(
                            "Не удалось обновить заказы — список может быть неполным.",
                            "Заказдарҙы яңыртып булманы — исемлек тулы булмаҫҡа мөмкин.",
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
                    // С включённым фильтром «пусто» значит другое: заказы могут быть, просто не
                    // в эту сторону. Без этой подсказки экран выглядит сломанным.
                    AppEmptyState(
                        title = if (anyFilter) appText("По этому направлению пусто", "Был йүнәлештә буш")
                        else appText("Свободных заказов нет", "Буш заказдар юҡ"),
                        text = if (anyFilter) appText(
                            "Убери фильтр направления — возможно, заказы есть в другую сторону.",
                            "Йүнәлеш фильтрын алып ташла — бәлки, заказдар икенсе яҡҡалыр.",
                        ) else appText(
                            "Загляни позже — соседи скоро что-нибудь отправят.",
                            "Һуңыраҡ кер — күршеләр тиҙҙән берәй нәмә ебәрер.",
                        ),
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

/** Один ряд фильтра: подпись + прокручиваемые чипы. Подпись нужна, потому что рядов теперь два
 *  («Откуда» и «Куда»), и без неё курьер не понимает, что именно он сейчас сужает. */
@Composable
private fun CourierFilterRow(label: String, chips: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            label, color = CanonMutedStrong, fontWeight = FontWeight.Bold,
            fontSize = DeliveryCaption, lineHeight = DeliveryCaptionLine,
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            chips()
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
        // Срок стоит первым в карточке свободного заказа: по нему курьер и решает, браться ли.
        // «Нужно завтра» и «нужно к пятнице» — это разные заказы, даже если маршрут один.
        ParcelDeadlineNote(
            deliverBy = p.deliverBy,
            overdue = p.overdue,
            status = p.status,
            forCourier = true,
        )
        // Что везти: вес, тип, «хрупкое». Стоит ДО кнопки «Взять заказ» намеренно — это и есть
        // ответ на «унесу ли один» и «возьмусь ли». Отправитель не указал — строки просто нет.
        CourierCargoRow(p)
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
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
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
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
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
    onGoOrders: () -> Unit = {},
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
    // Посылка, которую курьер забирает прямо сейчас: диалог «взял целой» перед выездом.
    var transitTarget by remember { mutableStateOf<ParcelDto?>(null) }
    var goodsTarget by remember { mutableStateOf<ParcelDto?>(null) }
    var disputeTarget by remember { mutableStateOf<ParcelDto?>(null) }
    var troubleTarget by remember { mutableStateOf<ParcelDto?>(null) }   // отказ / возврат (аудит 2026-07-26)
    // «Приехал — а дома никого»: попытка фиксируется, посылка ОСТАЁТСЯ у курьера.
    var attemptTarget by remember { mutableStateOf<ParcelDto?>(null) }
    var rateTarget by remember { mutableStateOf<ParcelDto?>(null) }
    // Чек открывается по номеру доставки: сам чек приходит с сервера, локальную копию не держим.
    var receiptId by remember { mutableStateOf<Int?>(null) }
    var ratedIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    val loadErr = appText("Не удалось загрузить. Проверь интернет.", "Йөкләп булманы. Интернетты тикшер.")
    val actionErr = appText("Не получилось. Проверь сеть и повтори.", "Булманы. Селтәрҙе тикшереп ҡабатла.")
    val transitMsg = appText("Статус обновлён: в пути", "Статус яңырҙы: юлда")
    // «Я на месте»: с этой минуты идёт платное ожидание. Сколько минут ждём бесплатно —
    // говорит сервер, чтобы цифра в подсказке не разошлась с той, по которой считают деньги.
    var waitFreeMin by remember { mutableIntStateOf(0) }
    val arrivedSenderMsg = appText("Отметил: ты у отправителя", "Билдәләнде: ебәреүсе янында")
    val arrivedReceiverMsg = appText("Отметил: ты у получателя", "Билдәләнде: алыусы янында")
    val deliveredMsg = appText("Заказ вручён. Спасибо!", "Заказ тапшырылды. Рәхмәт!")
    val goodsSavedMsg = appText("Стоимость покупки сохранена", "Һатып алыу хаҡы һаҡланды")
    val attemptMsg = appText(
        "Попытка отмечена, отправителю сообщили. Посылка остаётся у тебя.",
        "Маташыу билдәләнде, ебәреүсегә хәбәр ителде. Бандероль һиндә ҡала.",
    )

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
                    // Текст указывал дорогу, но идти по ней человек должен был сам.
                    // Кнопка ведёт ровно туда, куда указывает текст — на вкладку «Заказы».
                    AppEmptyState(
                        title = appText("Ты пока ничего не везёшь", "Әлегә бер нәмә лә илтмәйһең"),
                        text = appText("Возьми заказ во вкладке «Заказы» — он появится здесь.", "«Заказдар» бүлегендә заказ ал — ул бында күренер."),
                        icon = Icons.Default.LocalShipping,
                        actionLabel = appText("Смотреть заказы", "Заказдарҙы ҡарау"),
                        onAction = onGoOrders,
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
                        // Гололёд и метель на маршруте — третья функция того же ряда, что кнопки
                        // ниже, и её курьеру тоже не дали. Погоду видит таксист, видит водитель
                        // попутки, видит тот, кто ПУБЛИКУЕТ рейс, — а человек, который прямо
                        // сейчас везёт посылку по той же трассе Баймак–Сибай, не видел ничего.
                        // Довод тот же, что записан ниже про «Застрял» и SOS: курьер едет ОДИН,
                        // рядом нет пассажира, который скажет «смотри, лёд». Предупреждение
                        // ничего не запрещает — решает человек, но знать он должен заранее.
                        item(key = "ccar-weather") {
                            val route = activeParcels.firstOrNull { it.status == "in_transit" } ?: activeParcels.first()
                            WeatherWarningCard(
                                rememberRouteWeather(fromCity = route.fromCity, toCity = route.toCity),
                            )
                        }
                        // «Застрял на трассе» — у попутки и такси кнопка была, у курьера нет,
                        // хотя он едет по той же зимней трассе и ОДИН: рядом нет пассажира,
                        // который заметит беду. Сервер сигнал принимал, нажать было негде
                        // (аудит 2026-08-06). Привязываем к той доставке, что уже в пути.
                        item(key = "ccar-roadside") {
                            val stuckParcel = activeParcels.firstOrNull { it.status == "in_transit" }
                                ?: activeParcels.first()
                            RoadsideHelpAction(key = stuckParcel.id) { lat, lng ->
                                ApiClient.parcelRoadsideHelp(stuckParcel.id, lat, lng)
                            }
                        }
                        // И красная кнопка рядом. Тот же довод, что и у мягкой, только сильнее:
                        // курьер на трассе ОДИН. Мягкую кнопку ему дали, а SOS остался только
                        // на вкладке «Карта» — в беде человек не ходит по вкладкам (аудит 2026-08-06).
                        // Дежурному уходит подпись с маршрутом: у сигнала нет поля под доставку,
                        // а знать, что человек был в рейсе, ему нужно.
                        item(key = "ccar-sos") {
                            val p = activeParcels.firstOrNull { it.status == "in_transit" } ?: activeParcels.first()
                            CourierSosButton(route = "Доставка #${p.id} ${p.fromCity} → ${p.toCity}")
                        }
                    }
                    items(list.size, key = { "ccar-" + list[it].id }) { i ->
                        val parcel = list[i]
                        Box(Modifier.appearIn(i.coerceAtMost(6))) {
                            CourierCarryingCard(
                                p = parcel,
                                busy = busyId == parcel.id,
                                // Не отправляем статус сразу: сначала предлагаем снять посылку.
                                // Это вторая граница ответственности — «взял целой». Снимок
                                // необязателен, отказаться можно одной кнопкой.
                                onTransit = { if (busyId == 0) transitTarget = parcel },
                                onArrived = {
                                    if (busyId == 0) {
                                        busyId = parcel.id
                                        scope.launch {
                                            ApiClient.parcelArrived(parcel.id)
                                                .onSuccess { r ->
                                                    waitFreeMin = r.waitFreeMin
                                                    Toast.makeText(
                                                        ctx,
                                                        if (r.where == "sender") arrivedSenderMsg
                                                        else arrivedReceiverMsg,
                                                        Toast.LENGTH_SHORT,
                                                    ).show()
                                                    reload()
                                                }
                                                .onFailure {
                                                    Toast.makeText(
                                                        ctx,
                                                        (it as? com.yuldash.app.data.ApiException)?.message ?: actionErr,
                                                        Toast.LENGTH_SHORT,
                                                    ).show()
                                                }
                                            busyId = 0
                                        }
                                    }
                                },
                                waitFreeMin = waitFreeMin,
                                onDeliver = { deliverTarget = parcel },
                                onSetGoods = { goodsTarget = parcel },
                                onDispute = { disputeTarget = parcel },
                                onTrouble = { troubleTarget = parcel },
                                onAttemptFailed = { if (busyId == 0) attemptTarget = parcel },
                                rated = ratedIds.contains(parcel.id),
                                onRate = { rateTarget = parcel },
                                onReceipt = { receiptId = parcel.id },
                            )
                        }
                    }
                }
            }
        }
    }

    ParcelReceiptDialog(receiptId) { receiptId = null }

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
            title = { Text(appText("Стоимость покупки", "Һатып алыу хаҡы"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = DeliveryTitle, lineHeight = DeliveryTitleLine) },
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

    // «Не застал получателя». Раньше у этой ситуации был ОДИН исход — возврат: везти коробку
    // за 60 км обратно, хотя человек вернётся с работы через два часа. Теперь попытка просто
    // фиксируется: заказ живой, посылка у курьера, отправителю уходит сообщение, и завтра
    // можно попробовать ещё раз. Возврат остался отдельным решением и отдельной кнопкой.
    attemptTarget?.let { target ->
        var reason by remember(target.id) { mutableStateOf("") }
        var submitting by remember(target.id) { mutableStateOf(false) }
        var attemptError by remember(target.id) { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { if (!submitting) attemptTarget = null },
            containerColor = CanonSurface,
            shape = CanonCardShape,
            title = {
                Text(
                    appText("Не застал получателя?", "Алыусыны тапманыңмы?"),
                    color = CanonText, fontWeight = FontWeight.Bold,
                    fontSize = DeliveryTitle, lineHeight = DeliveryTitleLine,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DeliveryHint(
                        appText(
                            "Заказ не закроется, посылка останется у тебя. Отправителю уйдёт сообщение — он свяжется с получателем, и вы попробуете ещё раз.",
                            "Заказ ябылмай, бандероль һиндә ҡала. Ебәреүсегә хәбәр китә — ул алыусы менән һөйләшер, һин тағы бер тапҡыр ҡабатларһың.",
                        ),
                    )
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it.take(120); attemptError = null },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(appText("Что случилось (необязательно)", "Ни булды (мотлаҡ түгел)")) },
                        placeholder = {
                            Text(
                                appText("Никто не открыл, телефон не отвечает", "Берәү ҙә асманы, телефон яуап бирмәй"),
                                color = CanonMuted,
                            )
                        },
                        shape = RoundedCornerShape(14.dp),
                        minLines = 2,
                        isError = attemptError != null,
                    )
                    DeliveryHint(
                        appText(
                            "Пара слов помогает отправителю: он сразу поймёт, звонить получателю или переносить день.",
                            "Ике һүҙ ебәреүсегә ярҙам итә: ул алыусыға шылтыратырғамы, әллә көндө күсерергәме — шунда уҡ аңлар.",
                        ),
                    )
                    DialogErrorLine(attemptError)
                }
            },
            confirmButton = {
                TextButton(
                    modifier = Modifier.heightIn(min = 48.dp),
                    enabled = !submitting,
                    onClick = {
                        submitting = true; attemptError = null
                        scope.launch {
                            ApiClient.parcelAttemptFailed(target.id, reason.trim())
                                .onSuccess {
                                    Toast.makeText(ctx, attemptMsg, Toast.LENGTH_LONG).show()
                                    attemptTarget = null; reload()
                                }
                                .onFailure { attemptError = (it as? com.yuldash.app.data.ApiException)?.message ?: actionErr }
                            submitting = false
                        }
                    },
                ) {
                    Text(
                        appText("Отметить попытку", "Маташыуҙы билдәләү"),
                        color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = DeliveryBody,
                    )
                }
            },
            dismissButton = {
                TextButton(
                    modifier = Modifier.heightIn(min = 48.dp),
                    enabled = !submitting,
                    onClick = { attemptTarget = null },
                ) { Text(appText("Отмена", "Баш тартыу"), color = CanonMuted, fontSize = DeliveryBody) }
            },
        )
    }

    // «Забрал и повёз» — вторая граница ответственности. Снимок вручения был давно, снимка
    // забора не было вовсе: если получатель скажет «пришло битое», курьеру нечем показать,
    // какой посылка была на старте. Фото необязательно — уехать можно и без него.
    transitTarget?.let { target ->
        var submitting by remember(target.id) { mutableStateOf(false) }
        var pickupPhoto by remember(target.id) { mutableStateOf<String?>(null) }
        var photoBusy by remember(target.id) { mutableStateOf(false) }
        var photoError by remember(target.id) { mutableStateOf<String?>(null) }
        val photoFail = appText("Фото не загрузилось, попробуй ещё раз", "Фото йөкләнмәне, тағы ҡабатла")
        val pickPickupPhoto = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            photoBusy = true
            scope.launch {
                val bytes = withContext(Dispatchers.IO) { decodeToJpeg(ctx, uri) }
                if (bytes == null) { photoBusy = false; photoError = photoFail; return@launch }
                ApiClient.uploadEvidence(bytes)
                    .onSuccess { url -> if (url.isNotBlank()) { pickupPhoto = url; photoError = null } }
                    .onFailure { photoError = photoFail }
                photoBusy = false
            }
        }
        AlertDialog(
            onDismissRequest = { if (!submitting) transitTarget = null },
            containerColor = CanonSurface,
            title = {
                Text(
                    appText("Забрал посылку?", "Бандерольде алдыңмы?"),
                    color = CanonText, fontWeight = FontWeight.Bold,
                    fontSize = DeliveryTitle, lineHeight = DeliveryTitleLine,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DeliveryHint(
                        appText(
                            "Сними посылку перед выездом. Если в дороге что-то случится, будет видно, какой ты её взял.",
                            "Юлға сыҡҡанға тиклем бандерольде фотоға төшөр. Юлда берәй хәл булһа, уны ниндәй итеп алғаның күренер.",
                        )
                    )
                    AppButton(
                        text = when {
                            pickupPhoto != null -> appText("Фото приложено ✓", "Фото тағылды ✓")
                            photoBusy -> appText("Загружаем фото…", "Фото йөкләнә…")
                            else -> appText("Сфотографировать посылку", "Бандерольде фотоға төшөрөү")
                        },
                        onClick = { if (!photoBusy && pickupPhoto == null) pickPickupPhoto.launch("image/*") },
                        style = AppButtonStyle.Secondary,
                        loading = photoBusy,
                        enabled = !photoBusy && pickupPhoto == null,
                    )
                    DialogErrorLine(photoError)
                }
            },
            confirmButton = {
                TextButton(
                    modifier = Modifier.heightIn(min = 48.dp),
                    enabled = !submitting && !photoBusy,
                    onClick = {
                        submitting = true
                        busyId = target.id
                        scope.launch {
                            ApiClient.setParcelStatus(target.id, "in_transit", pickupPhotoUrl = pickupPhoto)
                                .onSuccess {
                                    Toast.makeText(ctx, transitMsg, Toast.LENGTH_SHORT).show()
                                    transitTarget = null
                                    reload()
                                }
                                .onFailure {
                                    Toast.makeText(
                                        ctx,
                                        (it as? com.yuldash.app.data.ApiException)?.message ?: actionErr,
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            busyId = 0
                            submitting = false
                        }
                    },
                ) {
                    Text(
                        appText("Забрал, еду", "Алдым, китәм"),
                        color = CanonGreen2, fontWeight = FontWeight.Bold,
                    )
                }
            },
            dismissButton = {
                TextButton(
                    modifier = Modifier.heightIn(min = 48.dp),
                    enabled = !submitting,
                    onClick = { transitTarget = null },
                ) { Text(appText("Отмена", "Кире алыу"), color = CanonMuted) }
            },
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
            title = { Text(appText("Код вручения", "Тапшырыу коды"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = DeliveryTitle, lineHeight = DeliveryTitleLine) },
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
    onArrived: () -> Unit,
    /** Сколько минут ждём бесплатно. 0 = сервер ещё не сказал — тогда подсказку не пишем. */
    waitFreeMin: Int,
    onDeliver: () -> Unit,
    onSetGoods: () -> Unit,
    onDispute: () -> Unit,
    onTrouble: () -> Unit,
    onAttemptFailed: () -> Unit,
    rated: Boolean,
    onRate: () -> Unit,
    onReceipt: () -> Unit,
) {
    val ctx = LocalContext.current
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
            // Сколько раз уже приезжали впустую. Это не «штрафной балл» курьеру: то же число
            // видит отправитель, и из него обе стороны решают — ждать ещё или всё-таки везти
            // обратно. Ноль попыток не показываем: строка «Попыток: 0» ничего не сообщает.
            if (p.deliveryAttempts > 0) {
                Surface(shape = CanonItemShape, color = CanonWarnBg, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        appText("Попыток вручения: ", "Тапшырыу маташыуы: ") + p.deliveryAttempts +
                            appText(
                                ". Отправителю сообщили — посылка остаётся у тебя.",
                                ". Ебәреүсегә хәбәр ителде — бандероль һиндә ҡала.",
                            ),
                        color = CanonWarn, fontWeight = FontWeight.Bold,
                        fontSize = DeliveryCaption, lineHeight = DeliveryCaptionLine,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            // Срок — выше адресов: сначала «к какому дню», потом «куда рулить». Если он вышел,
            // блок краснеет сам, и курьер видит это раньше, чем ему напишет отправитель.
            ParcelDeadlineNote(
                deliverBy = p.deliverBy,
                overdue = p.overdue,
                status = p.status,
                forCourier = true,
            )
            // Что везём — сразу под сроком: курьер уже взялся, но именно отсюда он понимает,
            // одному нести или звать помощь и можно ли ставить коробку на бок.
            CourierCargoRow(p)
            // Главное на карточке взятого заказа: по этим ориентирам курьер и едет. Стоит выше
            // контактов — сначала «куда рулить», потом «кому звонить». Пусто → блок не рисуется.
            ParcelAddressBlock(fromAddress = p.fromAddress, toAddress = p.toAddress, prominent = true)
            // Маршрут в навигаторе. Ведёт туда, где машина нужна ПРЯМО СЕЙЧАС: пока посылка не
            // забрана — к отправителю, в пути — к получателю, на возврате — снова к отправителю.
            // Без этого координаты у заказа были, а курьер искал «синие ворота» объездом по селу.
            val navActive = p.status == "accepted" || p.status == "in_transit" || p.status == "returning"
            val navToPickup = p.status == "accepted" || p.status == "returning"
            val navPoint = when {
                !navActive -> null
                navToPickup -> p.fromLat?.let { la -> p.fromLng?.let { lo -> la to lo } }
                else -> p.toLat?.let { la -> p.toLng?.let { lo -> la to lo } }
            }
            if (navPoint != null) {
                val navLat = navPoint.first
                val navLng = navPoint.second
                AppButton(
                    text = appText("Маршрут", "Юл"),
                    onClick = { openCourierNavigator(ctx, navLat, navLng) },
                    style = AppButtonStyle.Secondary,
                    icon = Icons.Default.AltRoute,
                    height = 48.dp,
                )
                DeliveryHint(
                    if (navToPickup) appText(
                        "Откроет навигатор к точке забора.",
                        "Алып китеү нөктәһенә навигаторҙы аса.",
                    ) else appText(
                        "Откроет навигатор к точке вручения.",
                        "Тапшырыу нөктәһенә навигаторҙы аса.",
                    ),
                )
            }
            // Что курьер снял сам: подтверждение его же добросовестности, если начнётся спор.
            ParcelPhotoStrip(pickupUrl = p.pickupPhotoUrl, deliveryUrl = p.deliveryPhotoUrl)
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
                // «Я на месте» — та же кнопка, что у таксиста, и работает на обоих концах:
                // у отправителя, когда забираешь, и у получателя, когда привёз. С этой минуты
                // идёт платное ожидание — раньше курьер стоял у двери сорок минут бесплатно.
                AppButton(
                    text = appText("Я на месте", "Мин урында"),
                    onClick = onArrived,
                    style = AppButtonStyle.Secondary,
                    icon = Icons.Default.LocationCity,
                    enabled = !busy,
                    height = 48.dp,
                )
                if (waitFreeMin > 0) {
                    DeliveryHint(appText(
                        "Отмечено. Первые $waitFreeMin мин ждём бесплатно, дальше ожидание оплачивается.",
                        "Билдәләнде. Тәүге $waitFreeMin мин түләүһеҙ көтәбеҙ, артабан көтөү түләүле.",
                    ))
                }
                // Возврат — тоже поездка. Курьер, который привёз коробку назад и снова стоит
                // под дверью, должен видеть: время идёт ему, а не в никуда, и выход есть.
                if (p.status == "returning") {
                    DeliveryHint(appText(
                        "Везёшь обратно. Отметь «Я на месте» у отправителя — ожидание оплачивается и здесь. " +
                            "Если и его нет дома, открой спор: коробку решит человек, а не приложение.",
                        "Кире алып бараһың. Ебәреүсе янында «Мин урында» тип билдәлә — көтөү бында ла түләнә. " +
                            "Ул да өйҙә булмаһа, бәхәс ас: ҡумтаны кеше хәл итер, ҡушымта түгел.",
                    ))
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
            // «Не застал получателя» и «возврат» — РАЗНЫЕ решения, поэтому и выглядят по-разному.
            // Первое — обычный рабочий исход (приехал, никого нет, приеду завтра): полноценная
            // кнопка, доступна пока посылка в пути. Второе — тяжёлое, с дорогой обратно за 60 км:
            // осталось тихой строкой ниже, ведущей в диалог с последствиями.
            if (p.status == "in_transit") {
                AppButton(
                    text = appText("Не застал получателя", "Алыусыны тапманым"),
                    onClick = onAttemptFailed,
                    style = AppButtonStyle.Secondary,
                    enabled = !busy,
                    height = 48.dp,
                )
            }
            if (canCourierResolveParcelTrouble(p.status)) {
                CourierTroubleButton(returning = p.status == "returning", onClick = onTrouble)
            }
            if (delivered) {
                if (rated) ParcelRatedRow() else ParcelRateButton(onClick = onRate)
                // Чек за доставку: у попутки и такси он был, у доставки не было — хотя деньги
                // тут настоящие, а спор «я отдал / он не отдал» решается только документом.
                TextButton(onClick = onReceipt, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        appText("Квитанция за доставку", "Илтеү өсөн квитанция"),
                        color = CanonGreen2, fontWeight = FontWeight.Bold,
                        fontSize = DeliveryCaption,
                    )
                }
            }
            if (canOpenParcelDispute(p.status)) {
                ParcelDisputeButton(onClick = onDispute)
            }
        }
    }
}

@Composable
private fun CourierContactDetails(label: String, name: String, phone: String) {
    val ctx = LocalContext.current
    // Подпись и имя озвучиваются одним куском, а телефон стал КНОПКОЙ и должен получить фокус
    // отдельно: если оставить общий mergeDescendants на всю колонку, TalkBack проглотит нажатие
    // и позвонить голосом станет нельзя.
    val spokenContact = listOf(label, name).filter { it.isNotBlank() }.joinToString(". ")
    val spokenCall = listOf(appText("Позвонить", "Шылтыратыу"), label, phone)
        .filter { it.isNotBlank() }.joinToString(". ")
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(
            Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = spokenContact },
            verticalArrangement = Arrangement.spacedBy(4.dp),
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
        }
        // Номер — не текст, а кнопка набора. Курьер стоит у чужих ворот в мороз и до этого
        // переписывал одиннадцать цифр в звонилку по одной. ACTION_DIAL открывает набор с уже
        // введённым номером: разрешения на звонок мы не просим, последнее касание — за человеком.
        if (phone.isNotBlank()) {
            Surface(
                onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))) } },
                color = CanonSurface,
                shape = CanonItemShape,
                border = BorderStroke(1.dp, CanonHairlineGreen),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .semantics(mergeDescendants = true) {
                        role = Role.Button
                        contentDescription = spokenCall
                    },
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Phone, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        phone,
                        color = CanonGreen2,
                        fontWeight = FontWeight.Bold,
                        fontSize = DeliveryBody,
                        lineHeight = DeliveryBodyLine,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        appText("Позвонить", "Шылтыратыу"),
                        color = CanonGreen2,
                        fontWeight = FontWeight.Bold,
                        fontSize = DeliveryCaption,
                        lineHeight = DeliveryCaptionLine,
                        maxLines = 1,
                    )
                }
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
    onCarPhoto: () -> Unit = {},
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
            // Фотоконтроль машины (580-ФЗ). Состояние показываем на самом экране: здесь
            // дверь, а не сводка — иначе кабинет превращается в приборную панель.
            item {
                AppButton(
                    text = appText("Фотоконтроль машины", "Машина фотоконтроле"),
                    onClick = onCarPhoto,
                    style = AppButtonStyle.Secondary,
                    icon = Icons.Default.PhotoCamera,
                )
            }
            // ⭐ Мой приоритет: кому заказ падает первым и за что. Считается по ДОСТАВКАМ,
            // отдельно от такси — работа разная, и заслуги одной роли не переносятся в другую.
            item { PrioritySection(courier = true) }
            // Пауза по качеству (если задана) — тёплая плашка, не ругательно.
            me.pausedUntil?.takeIf { it.isNotBlank() }?.let { until ->
                item {
                    Surface(color = CanonWarnBg, shape = CanonCardShape, border = BorderStroke(1.dp, CanonWarn)) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.PauseCircle, contentDescription = null, tint = CanonWarn, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(16.dp))
                            val until10 = until.take(10)
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(appText("Пауза по качеству", "Сифат буйынса пауза"), color = CanonWarn, fontWeight = FontWeight.Bold, fontSize = DeliveryBody, lineHeight = DeliveryBodyLine)
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
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = CanonMint, shape = RoundedCornerShape(14.dp)) {
                            Icon(Icons.Default.Star, contentDescription = null, tint = CanonStar, modifier = Modifier.padding(12.dp).size(24.dp))
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(appText("Твой рейтинг", "Һинең рейтинг"), color = CanonMuted, fontWeight = FontWeight.Bold, fontSize = DeliveryCaption, lineHeight = DeliveryCaptionLine)
                            if (avg != null && me.rating.count > 0) {
                                Text(deliveryDecimal(avg) + " ★", color = CanonText, fontWeight = FontWeight.Bold, fontSize = DeliveryDisplay, lineHeight = DeliveryDisplayLine)
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
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = CanonSurface, shape = RoundedCornerShape(14.dp)) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(12.dp).size(24.dp))
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(appText("Доставлено заказов", "Тапшырылған заказдар"), color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = DeliveryCaption, lineHeight = DeliveryCaptionLine)
                            Text("${st.deliveredCount}", color = CanonText, fontWeight = FontWeight.Bold, fontSize = DeliveryDisplay, lineHeight = DeliveryDisplayLine)
                        }
                    }
                }
            }
            // Текущая ступень комиссии — курьер видит, сколько платит и почему.
            if (st.feeTier.isNotBlank()) {
                item {
                    val tierLine = when (st.feeTier) {
                        "promo" -> appText("Промо-ставка", "Акция ставкаһы")
                        "tier1" -> appText("Стартовая ступень", "Башланғыс баҫҡыс")
                        "tier2" -> appText("Следующая ступень", "Киләһе баҫҡыс")
                        "tier3" -> appText("Обычная ставка", "Ғәҙәти ставка")
                        else -> appText("Комиссия по твоей ступени", "Баҫҡысың буйынса комиссия")
                    }
                    val promo = st.feeTier == "promo"
                    Surface(
                        color = if (promo) CanonMint else CanonSurface,
                        shape = CanonCardShape,
                        border = BorderStroke(1.dp, if (promo) CanonGreen2 else CanonBorder),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Surface(color = if (promo) CanonSurface else CanonMint, shape = RoundedCornerShape(14.dp)) {
                                    Icon(Icons.Default.Percent, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(12.dp).size(24.dp))
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(appText("Сейчас ты платишь $currentFeePercent% комиссии", "Хәҙер һин $currentFeePercent% комиссия түләйһең"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = DeliveryBody, lineHeight = DeliveryBodyLine)
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
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Payments, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(24.dp))
                            Text(appText("Наша комиссия за доставки", "Илтеүҙәр өсөн беҙҙең комиссия"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = DeliveryBody, lineHeight = DeliveryBodyLine)
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
                                    appText("К оплате сейчас", "Хәҙер түләргә"), color = CanonText, fontWeight = FontWeight.Bold,
                                    fontSize = DeliveryBody, lineHeight = DeliveryBodyLine, modifier = Modifier.weight(1f),
                                )
                                Text(kopToRub(owed), color = if (owed > 0) CanonGreen2 else CanonMuted, fontWeight = FontWeight.Bold, fontSize = DeliveryDisplay, lineHeight = DeliveryDisplayLine)
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
            amountKop = pr.amountKop,
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
