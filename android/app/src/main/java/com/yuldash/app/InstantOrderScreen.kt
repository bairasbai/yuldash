package com.yuldash.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.yandex.mapkit.MapKitFactory
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.map.CameraPosition
import com.yandex.mapkit.mapview.MapView
import com.yandex.mapkit.runtime.image.ImageProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.ApiException
import com.yuldash.app.data.GeocoderClient
import com.yuldash.app.data.InstantEstimateDto
import com.yuldash.app.data.InstantOrderDto
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// ============================ «Быстрый заказ» (такси-режим, Фаза 2) ============================
// Отдельный поток от плановых поездок. Пассажир: «Куда едем?» → цена → «Ищем машину» → «Водитель едет».
// Водитель: presence-heartbeat + входящий оффер (таймер) → поездка (приехал/посадил/завершил).
// Приватность: телефон стороны сервер отдаёт только после accept; координаты не логируем.

// Города-опоры РБ для дефолтной камеры, если позиция ещё не определилась.
private val InstantDefaultPoint = Point(52.5980, 58.4419) // Баймак

// ------------------------------ Время сервера (наивный UTC ISO) ------------------------------
/** Сервер шлёт наивные UTC-даты ISO («2026-07-10T12:34:56.123456») — переводим в epoch ms. */
private fun isoUtcToEpochMs(iso: String): Long? = runCatching {
    java.time.LocalDateTime.parse(iso.removeSuffix("Z"))
        .toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
}.getOrNull()

/** Тикающее «сейчас» (раз в секунду) для живых таймеров ожидания. */
@Composable
private fun rememberNowMs(): State<Long> {
    val state = remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            state.value = System.currentTimeMillis()
            delay(1_000)
        }
    }
    return state
}

// ------------------------------ Гео: моя позиция ------------------------------
/** Моя позиция через LocationManager (как на «Карте»). active=false → не подписываемся (экономим батарею). */
@Composable
internal fun rememberMyPoint(active: Boolean = true): State<Point?> {
    val context = LocalContext.current
    val state = remember { mutableStateOf<Point?>(LocationPrefs.lastLat?.let { la -> LocationPrefs.lastLng?.let { lo -> Point(la, lo) } }) }
    DisposableEffect(active) {
        if (!active) return@DisposableEffect onDispose { }
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!granted) return@DisposableEffect onDispose { }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        val listener = object : android.location.LocationListener {
            override fun onLocationChanged(loc: android.location.Location) {
                state.value = Point(loc.latitude, loc.longitude)
                LocationPrefs.lastLat = loc.latitude; LocationPrefs.lastLng = loc.longitude
            }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
        }
        try {
            lm.requestLocationUpdates(android.location.LocationManager.GPS_PROVIDER, 3000L, 10f, listener)
            lm.requestLocationUpdates(android.location.LocationManager.NETWORK_PROVIDER, 3000L, 10f, listener)
            (lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER))?.let { state.value = Point(it.latitude, it.longitude) }
        } catch (e: SecurityException) {
        } catch (e: IllegalArgumentException) {
        }
        onDispose { runCatching { lm.removeUpdates(listener) } }
    }
    return state
}

// ------------------------------ Компактная карта маршрута A→B ------------------------------
/** Реальная Яндекс-карта: точка подачи (A) + назначения (B) + дорожный маршрут между ними.
 *  Локаль MapKit задаётся при первой карте приложения; тут только initialize (повторный setLocale бы упал). */
@Composable
internal fun InstantRouteMap(from: Point?, to: Point?, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val mapView = remember {
        runCatching { MapKitFactory.initialize(ctx) }
        MapView(ctx).also { v ->
            val center = from ?: to ?: InstantDefaultPoint
            v.mapWindow.map.move(CameraPosition(center, 12f, 0f, 0f))
        }
    }
    DisposableEffect(Unit) {
        MapKitFactory.getInstance().onStart(); mapView.onStart()
        onDispose { mapView.onStop(); MapKitFactory.getInstance().onStop() }
    }
    // Перерисовываем маршрут при смене точек. Все объекты снимаем при следующей смене/уходе.
    DisposableEffect(from, to) {
        val map = mapView.mapWindow.map
        val added = mutableListOf<com.yandex.mapkit.map.MapObject>()
        var session: com.yandex.mapkit.directions.driving.DrivingSession? = null
        if (from != null) {
            added += map.mapObjects.addPlacemark(from).apply { setIcon(ImageProvider.fromBitmap(userPuckBitmap())) }
        }
        if (to != null) {
            added += map.mapObjects.addPlacemark(to).apply { setIcon(ImageProvider.fromBitmap(destFlagBitmap())) }
        }
        if (from != null && to != null) {
            val straight = map.mapObjects.addPolyline(com.yandex.mapkit.geometry.Polyline(listOf(from, to))).apply {
                setStrokeColor(0xCC0B6B3A.toInt()); strokeWidth = 4f
            }
            added += straight
            session = runCatching {
                val router = com.yandex.mapkit.directions.DirectionsFactory.getInstance()
                    .createDrivingRouter(com.yandex.mapkit.directions.driving.DrivingRouterType.COMBINED)
                val reqPoints = listOf(
                    com.yandex.mapkit.RequestPoint(from, com.yandex.mapkit.RequestPointType.WAYPOINT, null, null, null),
                    com.yandex.mapkit.RequestPoint(to, com.yandex.mapkit.RequestPointType.WAYPOINT, null, null, null),
                )
                router.requestRoutes(
                    reqPoints,
                    com.yandex.mapkit.directions.driving.DrivingOptions(),
                    com.yandex.mapkit.directions.driving.VehicleOptions(),
                    object : com.yandex.mapkit.directions.driving.DrivingSession.DrivingRouteListener {
                        override fun onDrivingRoutes(routes: MutableList<com.yandex.mapkit.directions.driving.DrivingRoute>) {
                            val r = routes.firstOrNull() ?: return
                            runCatching {
                                map.mapObjects.remove(straight)
                                added.remove(straight)
                                added += map.mapObjects.addPolyline(r.geometry).apply { setStrokeColor(0xCC0B6B3A.toInt()); strokeWidth = 5f }
                            }
                        }
                        override fun onDrivingRoutesError(error: com.yandex.runtime.Error) {}
                    }
                )
            }.getOrNull()
            runCatching {
                val bbox = com.yandex.mapkit.geometry.BoundingBox(
                    Point(minOf(from.latitude, to.latitude), minOf(from.longitude, to.longitude)),
                    Point(maxOf(from.latitude, to.latitude), maxOf(from.longitude, to.longitude)),
                )
                val fit = map.cameraPosition(com.yandex.mapkit.geometry.Geometry.fromBoundingBox(bbox))
                map.move(CameraPosition(fit.target, (fit.zoom - 0.6f).coerceIn(3f, 15f), 0f, 0f))
            }
        } else {
            (from ?: to)?.let { map.move(CameraPosition(it, 14f, 0f, 0f)) }
        }
        onDispose {
            runCatching { session?.cancel() }
            added.forEach { runCatching { map.mapObjects.remove(it) } }
        }
    }
    AndroidView(factory = { mapView }, modifier = modifier)
}

// ==================================== ПАССАЖИР ====================================
/**
 * «Быстрый заказ» пассажира. Один экран с внутренней машиной состояний по статусу заказа:
 * нет заказа → «Куда едем?» (A=моя позиция, B=поиск/карта, оценка цены) → создать →
 * searching «Ищем машину» → accepted/arriving/onboard «Водитель едет» → done/expired/cancelled.
 * При возврате на экран активный заказ восстанавливается (getMyInstantOrders).
 */
@Composable
internal fun InstantOrderScreen(
    onBack: () -> Unit,
    onLoginRequired: () -> Unit,
    embedded: Boolean = false,
    onTaxiOnboarding: () -> Unit = {},   // §11: из заглушки «Скоро» водитель может уйти в онбординг таксиста
) {
    val scope = rememberCoroutineScope()
    val loggedIn = ApiClient.isLoggedIn()
    var order by remember { mutableStateOf<InstantOrderDto?>(null) }
    var checking by remember { mutableStateOf(loggedIn) }   // первичная загрузка: есть ли активный заказ
    // Гейт такси (волна 2): доступно ли такси в моей точке (глобальный флаг + города на сервере).
    // Сеть упала → фолбэк «доступно» (обычный пикер): сервер всё равно гейтит оценку и заказ.
    var availability by remember { mutableStateOf<com.yuldash.app.data.TaxiAvailabilityDto?>(null) }

    // Восстановление активного заказа при входе на экран + проверка доступности такси в точке.
    LaunchedEffect(Unit) {
        if (!loggedIn) { checking = false; return@LaunchedEffect }
        val lat = LocationPrefs.lastLat ?: InstantDefaultPoint.latitude
        val lng = LocationPrefs.lastLng ?: InstantDefaultPoint.longitude
        ApiClient.getTaxiAvailability(lat, lng).onSuccess { availability = it }
        ApiClient.getMyInstantOrders(limit = 5)
            .onSuccess { list -> order = list.firstOrNull { !it.isTerminal } }
        checking = false
    }

    // Поллинг статуса активного заказа (пока заказ есть и не терминальный).
    val activeId = order?.takeIf { !it.isTerminal }?.id
    LaunchedEffect(activeId) {
        val id = activeId ?: return@LaunchedEffect
        while (isActive) {
            delay(3_000)
            ApiClient.getInstantOrder(id).onSuccess { order = it }
            if (order?.isTerminal == true) break
        }
    }

    // Встроенный режим (внутри переключателя Такси↔Попутка на главной): свою шапку не рисуем —
    // контекст задаёт сам переключатель. Самостоятельный экран (из кабинета) — с шапкой и «Назад».
    Scaffold(
        containerColor = CanonBg,
        topBar = { if (!embedded) ScreenTopBar(appText("Быстрый заказ", "Тиҙ заказ"), onBack) }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val current = order
            when {
                !loggedIn -> InstantLoginNeeded(onLoginRequired)
                checking -> InstantCenterLoader(appText("Проверяем заказ…", "Заказды тикшерәбеҙ…"))
                // Гейт (a): такси выключено глобально или в этом городе → тёплая заглушка «Скоро».
                // Активный заказ (если вдруг успел создаться до выключения) показываем как обычно.
                current == null && availability?.enabled == false -> TaxiComingSoonCard(
                    availability = availability!!,
                    onBackToPooling = onBack,
                    onTaxiOnboarding = onTaxiOnboarding,
                )
                current == null -> InstantDestinationPicker(
                    onOrderCreated = { order = it },
                )
                current.isSearching -> InstantSearchingCard(
                    order = current,
                    onCancel = { scope.launch { ApiClient.instantCancel(current.id).onSuccess { order = it } } },
                )
                current.isActive -> InstantDriverEnRouteCard(
                    order = current,
                    onCancel = { scope.launch { ApiClient.instantCancel(current.id).onSuccess { order = it } } },
                )
                current.status == "expired" -> InstantNoDriversCard(
                    onRetry = { order = null },
                    onDone = onBack,
                )
                current.status == "cancelled" -> InstantFinalCard(
                    icon = Icons.Default.Close,
                    title = if (current.noShow) appText("Поездка не состоялась", "Сәфәр булманы")
                    else appText("Заказ отменён", "Заказ ҡабул ителмәне"),
                    // Честные тексты про штраф/страйки (Модель А: фиксируем, деньги не списываем).
                    subtitle = when {
                        current.noShow -> appText(
                            "Водитель ждал ${current.waitFreeMin}+ минут, но не дождался. Подача — ${current.cancelFeeKop / 100} ₽, переведи водителю. Частые такие отмены ставят такси на паузу.",
                            "Водитель ${current.waitFreeMin}+ минут көттө, тик көтөп еткермәне. Килеү хаҡы — ${current.cancelFeeKop / 100} ₽, водителгә күсер. Йыш улай булһа — такси паузаға китә.")
                        current.cancelFeeKop > 0 && current.cancelBy == "passenger" -> appText(
                            "Отмена была платной: ${current.cancelFeeKop / 100} ₽ (подача) — переведи водителю. Частые платные отмены ставят такси на паузу.",
                            "Кире алыу түләүле булды: ${current.cancelFeeKop / 100} ₽ (килеү хаҡы) — водителгә күсер. Йыш түләүле кире алыуҙар таксиҙы паузаға ҡуя.")
                        current.cancelBy == "driver" -> appText("Водитель отменил. Попробуй заказать снова.", "Водитель баш тартты. Ҡабат заказ ит.")
                        else -> appText("Ты отменил заказ — бесплатно.", "Һин заказды кире алдың — бушлай.")
                    },
                    action = appText("Новый заказ", "Яңы заказ"),
                    onAction = { order = null },
                    onSecondary = onBack,
                )
                else -> InstantFinalCard(   // done
                    icon = Icons.Default.CheckCircle,
                    title = appText("Поездка завершена", "Сәфәр тамамланды"),
                    subtitle = appText("${current.fromText.ifBlank { "Точка А" }} → ${current.toText.ifBlank { "Точка Б" }} · ${current.priceFinal ?: current.priceEstimate} ₽",
                        "${current.fromText.ifBlank { "А нөктәһе" }} → ${current.toText.ifBlank { "Б нөктәһе" }} · ${current.priceFinal ?: current.priceEstimate} ₽"),
                    action = appText("Новый заказ", "Яңы заказ"),
                    onAction = { order = null },
                    onSecondary = onBack,
                    extra = { InstantRateAndReport(current, isDriver = false) },   // §9: оценить/пожаловаться
                )
            }
        }
    }
}

// ------------------------------ «Куда едем?» (пикер + оценка) ------------------------------
@Composable
private fun InstantDestinationPicker(onOrderCreated: (InstantOrderDto) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val myPoint by rememberMyPoint(active = true)

    var fromPoint by remember { mutableStateOf<Point?>(null) }
    var fromText by remember { mutableStateOf("") }
    // A по умолчанию = моя позиция (пока пользователь не выбрал вручную).
    var fromManual by remember { mutableStateOf(false) }
    LaunchedEffect(myPoint) { if (!fromManual && myPoint != null) fromPoint = myPoint }
    val effFrom = fromPoint ?: myPoint

    var toPoint by remember { mutableStateOf<Point?>(null) }
    var toText by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<com.yuldash.app.data.GeoHit>>(emptyList()) }

    var estimate by remember { mutableStateOf<InstantEstimateDto?>(null) }
    var estimating by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var category by remember { mutableStateOf("standard") }   // §6: standard = Эконом, comfort = Комфорт
    var pickOnMap by remember { mutableStateOf(false) }   // оверлей выбора точки Б на карте
    var pickFromOnMap by remember { mutableStateOf(false) }

    // Строки для колбэков (вне composition appText звать нельзя) — считаем заранее.
    val estimateFailMsg = appText("Не удалось оценить цену", "Хаҡты баһалап булманы")
    val createFailMsg = appText("Не удалось создать заказ. Повтори.", "Заказ булманы. Ҡабатла.")
    val myPosText = appText("Моя позиция", "Минең урын")
    val mapPointText = appText("Точка на карте", "Картала нөктә")

    val locationPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) LocationPrefs.sharingEnabled = true
    }
    val hasLocPerm = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    // Поиск адреса Б (дебаунс 350мс).
    LaunchedEffect(query) {
        if (query.trim().length < 2) { suggestions = emptyList(); return@LaunchedEffect }
        delay(350)
        suggestions = GeocoderClient.suggest(query).take(6)
    }

    // Оценка цены, когда есть обе точки (и при смене класса — тариф другой).
    LaunchedEffect(effFrom, toPoint, category) {
        val f = effFrom; val t = toPoint
        if (f == null || t == null) { estimate = null; return@LaunchedEffect }
        delay(350)   // дебаунс: позиция уточняется GPS-фиксами — не дёргаем /estimate на каждый
        estimating = true; errorText = null
        ApiClient.instantEstimate(f.latitude, f.longitude, t.latitude, t.longitude, fromText, toText, category)
            .onSuccess { estimate = it }
            .onFailure { errorText = (it as? ApiException)?.message ?: estimateFailMsg }
        estimating = false
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(appText("Куда едем?", "Ҡайҙа барабыҙ?"), color = CanonText, fontSize = 26.sp, lineHeight = 30.sp, fontWeight = FontWeight.Black)
        Text(appText("Машина приедет за тобой. Цену считаем заранее — без сюрпризов.", "Машина һине алырға килә. Хаҡты алдан иҫәпләйбеҙ — сюрприздарһыҙ."),
            color = CanonMuted, fontSize = 14.sp, lineHeight = 19.sp)

        // Точка А
        Card(colors = CardDefaults.cardColors(containerColor = CanonSurface), shape = CanonItemShape) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.MyLocation, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(appText("Откуда", "Ҡайҙан"), color = CanonMuted, fontSize = 12.sp)
                    val label = when {
                        fromManual && fromText.isNotBlank() -> fromText
                        effFrom != null -> appText("Моя позиция", "Минең урын")
                        hasLocPerm -> appText("Определяем…", "Билдәләйбеҙ…")
                        else -> appText("Включи геолокацию", "Геолокацияны ҡабыҙ")
                    }
                    Text(label, color = CanonText, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
                if (!hasLocPerm && !fromManual) {
                    TextButton(onClick = { locationPermLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }) {
                        Text(appText("Включить", "Ҡабыҙ"), color = CanonGreen2, fontWeight = FontWeight.Bold)
                    }
                } else {
                    TextButton(onClick = { pickFromOnMap = true }) { Text(appText("На карте", "Картала"), color = CanonGreen2) }
                }
            }
        }

        // Точка Б: поиск + «на карте»
        Card(colors = CardDefaults.cardColors(containerColor = CanonSurface), shape = CanonItemShape) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = if (toPoint != null && query.isBlank()) toText else query,
                    onValueChange = { query = it; if (it.isBlank()) { toPoint = null; estimate = null } },
                    label = { Text(appText("Куда", "Ҡайҙа")) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = CanonMuted) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                suggestions.forEach { hit ->
                    Row(
                        Modifier.fillMaxWidth().height(44.dp)
                            .clickable {
                                toPoint = Point(hit.lat, hit.lon); toText = hit.title; query = ""; suggestions = emptyList()
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.LocationOn, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(hit.title, color = CanonText, fontSize = 14.sp, maxLines = 1)
                    }
                }
                OutlinedButton(
                    onClick = { pickOnMap = true },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(appText("Выбрать точку на карте", "Картала нөктә һайлау"))
                }
            }
        }

        // Класс машины (§6): Эконом / Комфорт — обе цены сразу, выбранная уходит в заказ.
        if (toPoint != null) {
            val opts = estimate?.options.orEmpty().associate { it.category to it.price }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                InstantClassCard(
                    title = appText("Эконом", "Эконом"),
                    subtitle = appText("обычная машина", "ғәҙәти машина"),
                    price = opts["standard"],
                    selected = category == "standard",
                    onClick = { category = "standard" },
                    modifier = Modifier.weight(1f),
                )
                InstantClassCard(
                    title = appText("Комфорт", "Комфорт"),
                    subtitle = appText("новее и просторнее", "яңыраҡ һәм иркенерәк"),
                    price = opts["comfort"],
                    selected = category == "comfort",
                    onClick = { category = "comfort" },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // Сурж — честно и ДО заказа: почему дороже и на сколько (потолок ×1.5 на сервере).
        AnimatedVisibility(
            visible = (estimate?.surgeK ?: 1.0) > 1.0,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            val est = estimate
            val pct = (((est?.surgeK ?: 1.0) - 1.0) * 100).toInt()
            Surface(shape = CanonItemShape, color = CanonTaxiBg, border = BorderStroke(1.dp, CanonTaxi)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("⚡", fontSize = 22.sp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            // Серверный текст (двуязычный) — источник правды; локальный — фолбэк.
                            appText(
                                est?.surgeNoteRu?.ifBlank { null } ?: "Сейчас заказов больше обычного — цена выше на $pct%. Вызвать или подождать?",
                                est?.surgeNoteBa?.ifBlank { null } ?: "Хәҙер заказдар күберәк — хаҡ $pct%-ҡа юғарыраҡ. Саҡырырғамы, әллә көтөргәме?",
                            ),
                            color = CanonText, fontSize = 13.sp, lineHeight = 18.sp,
                        )
                    }
                }
            }
        }

        // Оценка цены
        if (toPoint != null) {
            Card(colors = CardDefaults.cardColors(containerColor = CanonSurface), shape = CanonCardShape) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    when {
                        estimating -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = CanonGreen2)
                            Spacer(Modifier.width(10.dp))
                            Text(appText("Считаем цену…", "Хаҡты иҫәпләйбеҙ…"), color = CanonMuted, fontSize = 14.sp)
                        }
                        errorText != null -> Text(errorText!!, color = CanonRed, fontSize = 14.sp)
                        estimate != null -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${estimate!!.price} ₽", color = CanonText, fontSize = 30.sp, fontWeight = FontWeight.Black)
                                Spacer(Modifier.width(10.dp))
                                Text(appText("примерно", "яҡынса"), color = CanonMuted, fontSize = 13.sp)
                            }
                            val meta = buildList {
                                if (estimate!!.distanceKm > 0) add(appText("≈ ${estimate!!.distanceKm.toInt()} км", "≈ ${estimate!!.distanceKm.toInt()} км"))
                                if (estimate!!.etaMin > 0) add(appText("≈ ${estimate!!.etaMin.toInt()} мин в пути", "≈ ${estimate!!.etaMin.toInt()} мин юлда"))
                            }.joinToString("  ·  ")
                            if (meta.isNotBlank()) Text(meta, color = CanonMuted, fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(2.dp))
        Button(
            onClick = {
                val f = effFrom ?: return@Button
                val t = toPoint ?: return@Button
                creating = true; errorText = null
                scope.launch {
                    ApiClient.createInstantOrder(f.latitude, f.longitude, t.latitude, t.longitude,
                        fromText.ifBlank { myPosText }, toText.ifBlank { mapPointText }, category)
                        .onSuccess { onOrderCreated(it) }
                        .onFailure { errorText = (it as? ApiException)?.message ?: createFailMsg }
                    creating = false
                }
            },
            enabled = effFrom != null && toPoint != null && estimate != null && !creating,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = CanonTaxi, contentColor = CanonTaxiInk),   // жёлтый — режим такси
        ) {
            if (creating) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = CanonTaxiInk)
            } else {
                Icon(Icons.Default.DirectionsCar, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    // Глагол-действие: «Вызвать машину» понятнее, чем «Заказать» (эталон Яндекс/inDrive).
                    if (estimate != null) appText("Вызвать за ${estimate!!.price} ₽", "${estimate!!.price} ₽-ға саҡырыу")
                    else appText("Вызвать машину", "Машина саҡырыу"),
                    fontSize = 16.sp, fontWeight = FontWeight.Bold,
                )
            }
        }
        Text(appText("Оплата водителю напрямую. Телефон водителя откроется после того, как он примет заказ.",
            "Түләү водителгә тура. Водитель заказды алғас, уның телефоны асыла."),
            color = CanonMuted, fontSize = 12.sp, lineHeight = 16.sp)
    }

    if (pickOnMap) {
        PickupPickerOverlay(
            initial = toPoint ?: effFrom,
            onConfirm = { lat, lng -> toPoint = Point(lat, lng); toText = mapPointText; query = ""; suggestions = emptyList(); pickOnMap = false },
            onDismiss = { pickOnMap = false },
        )
    }
    if (pickFromOnMap) {
        PickupPickerOverlay(
            initial = effFrom,
            onConfirm = { lat, lng -> fromPoint = Point(lat, lng); fromText = mapPointText; fromManual = true; pickFromOnMap = false },
            onDismiss = { pickFromOnMap = false },
        )
    }
}

// ------------------------------ Карточка класса (Эконом/Комфорт) ------------------------------
@Composable
private fun InstantClassCard(
    title: String,
    subtitle: String,
    price: Int?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val border by animateColorAsState(if (selected) CanonGreen2 else CanonSurface, tween(200), label = "clsBorder")
    val bg by animateColorAsState(if (selected) CanonGreen2.copy(alpha = 0.08f) else CanonSurface, tween(200), label = "clsBg")
    Surface(
        onClick = onClick,
        shape = CanonItemShape,
        color = bg,
        border = BorderStroke(if (selected) 2.dp else 1.dp, border),
        modifier = modifier.height(76.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = CanonText, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (price != null) {
                    Text("$price ₽", color = if (selected) CanonGreen2 else CanonText, fontSize = 15.sp, fontWeight = FontWeight.Black)
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = CanonMuted, fontSize = 12.sp, maxLines = 1)
        }
    }
}

// ------------------------------ Таймер ожидания (обе стороны) ------------------------------
/** «Машина на месте»: живой таймер — бесплатное окно тикает вниз, дальше честно копится
 *  платное ожидание (+N ₽/мин, целыми минутами — как считает сервер). */
@Composable
private fun InstantWaitingRow(order: InstantOrderDto) {
    val startMs = order.waitingStartedAt?.let { isoUtcToEpochMs(it) } ?: return
    val now by rememberNowMs()
    val elapsedSec = ((now - startMs) / 1000).coerceAtLeast(0)
    val freeSec = order.waitFreeMin * 60L
    val isFree = elapsedSec < freeSec
    val accent by animateColorAsState(if (isFree) CanonGreen2 else CanonRed, tween(300), label = "waitAccent")
    Surface(shape = CanonItemShape, color = accent.copy(alpha = 0.08f), border = BorderStroke(1.dp, accent.copy(alpha = 0.4f))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.AccessTime, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            if (isFree) {
                val left = freeSec - elapsedSec
                Text(
                    appText("Бесплатное ожидание %d:%02d".format(left / 60, left % 60),
                        "Бушлай көтөү %d:%02d".format(left / 60, left % 60)),
                    color = CanonText, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                )
            } else {
                val paidRub = ((elapsedSec / 60 - order.waitFreeMin).coerceAtLeast(0)) * order.waitFeeRubPerMin
                Text(
                    appText("Платное ожидание · +${order.waitFeeRubPerMin} ₽/мин" + (if (paidRub > 0) " (уже +$paidRub ₽)" else ""),
                        "Түләүле көтөү · +${order.waitFeeRubPerMin} ₽/мин" + (if (paidRub > 0) " (инде +$paidRub ₽)" else "")),
                    color = CanonText, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

// ------------------------------ «Ищем машину» ------------------------------
@Composable
private fun InstantSearchingCard(order: InstantOrderDto, onCancel: () -> Unit) {
    val infinite = rememberInfiniteTransition(label = "search")
    val pulse by infinite.animateFloat(
        initialValue = 0.4f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "pulse",
    )
    Column(
        Modifier.fillMaxSize().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(120.dp)) {
            Box(Modifier.size((60 + pulse * 56).dp).background(CanonGreen2.copy(alpha = 0.12f * pulse), CircleShape))
            Surface(shape = CircleShape, color = CanonGreen2) {
                Icon(Icons.Default.DirectionsCar, contentDescription = null, tint = CanonBg, modifier = Modifier.padding(20.dp).size(34.dp))
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(appText("Ищем машину рядом…", "Яҡында машина эҙләйбеҙ…"), color = CanonText, fontSize = 22.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text("${order.fromText.ifBlank { appText("Точка А", "А нөктәһе") }} → ${order.toText.ifBlank { appText("Точка Б", "Б нөктәһе") }}",
            color = CanonMuted, fontSize = 14.sp, textAlign = TextAlign.Center, maxLines = 2)
        Spacer(Modifier.height(4.dp))
        Text(appText("≈ ${order.priceEstimate} ₽ · подбираем ближайшего водителя", "≈ ${order.priceEstimate} ₽ · яҡын водителде табабыҙ"),
            color = CanonMuted, fontSize = 13.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(32.dp))
        OutlinedButton(onClick = onCancel, modifier = Modifier.height(48.dp), shape = RoundedCornerShape(14.dp)) {
            Text(appText("Отменить заказ", "Заказды кире алыу"), color = CanonRed)
        }
    }
}

// ------------------------------ «Водитель едет» ------------------------------
@Composable
private fun InstantDriverEnRouteCard(order: InstantOrderDto, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    // Семантика фаз (§5, структура как Яндекс): accepted = водитель едет к тебе,
    // arriving = «Я на месте» (машина ждёт — тикает ожидание), onboard = в пути.
    val phaseTitle = when (order.status) {
        "accepted" -> appText("Водитель едет к тебе", "Водитель һиңә килә")
        "arriving" -> appText("Машина на месте", "Машина урынында")
        "onboard" -> appText("В пути", "Юлда")
        else -> appText("Водитель едет", "Водитель килә")
    }
    var confirmPaidCancel by remember { mutableStateOf(false) }
    val cancelFeeRub = order.cancelFeeNowKop / 100
    Column(Modifier.fillMaxSize()) {
        InstantRouteMap(
            from = Point(order.fromLat, order.fromLng),
            to = Point(order.toLat, order.toLng),
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        Card(
            colors = CardDefaults.cardColors(containerColor = CanonSurface),
            shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(18.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Navigation, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(phaseTitle, color = CanonText, fontSize = 18.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                    if (order.etaMin > 0 && order.status != "onboard") {
                        Text(appText("≈ ${order.etaMin.toInt()} мин", "≈ ${order.etaMin.toInt()} мин"), color = CanonGreen2, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
                // Карточка водителя
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = CircleShape, color = CanonBg, modifier = Modifier.size(46.dp)) {
                        Icon(Icons.Default.Person, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(11.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(order.driverName.ifBlank { appText("Водитель", "Водитель") }, color = CanonText, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            if (order.driverVerified) {
                                Spacer(Modifier.width(5.dp))
                                Icon(Icons.Default.Verified, contentDescription = appText("Проверен", "Тикшерелгән"), tint = CanonGreen2, modifier = Modifier.size(16.dp))
                            }
                        }
                        val sub = buildList {
                            if (order.driverRating > 0) add("★ ${String.format(java.util.Locale.US, "%.1f", order.driverRating)}")
                            if (order.driverCar.isNotBlank()) add(order.driverCar)
                        }.joinToString("  ·  ")
                        if (sub.isNotBlank()) Text(sub, color = CanonMuted, fontSize = 13.sp, maxLines = 1)
                    }
                    // Телефон — ТОЛЬКО после accept (сервер отдаёт его непустым).
                    if (order.driverPhone.isNotBlank()) {
                        Surface(
                            onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${order.driverPhone}"))) } },
                            shape = CircleShape, color = CanonGreen2,
                        ) {
                            Icon(Icons.Default.Phone, contentDescription = appText("Позвонить водителю", "Водителгә шылтыратыу"), tint = CanonBg, modifier = Modifier.padding(12.dp).size(20.dp))
                        }
                    }
                }
                // «Я на месте» → живой таймер ожидания (бесплатное окно → платно).
                if (order.status == "arriving") {
                    InstantWaitingRow(order)
                }
                if (order.status != "onboard") {
                    OutlinedButton(
                        // Поздняя отмена платная (подача) — честно предупреждаем ДО тапа.
                        onClick = { if (order.cancelFeeNowKop > 0) confirmPaidCancel = true else onCancel() },
                        modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(14.dp),
                    ) {
                        Text(
                            if (order.cancelFeeNowKop > 0)
                                appText("Отменить · $cancelFeeRub ₽", "Кире алыу · $cancelFeeRub ₽")
                            else appText("Отменить заказ", "Заказды кире алыу"),
                            color = CanonRed,
                        )
                    }
                }
            }
        }
    }
    if (confirmPaidCancel) {
        AlertDialog(
            onDismissRequest = { confirmPaidCancel = false },
            containerColor = CanonSurface,
            title = { Text(appText("Отмена сейчас платная", "Хәҙер кире алыу түләүле"), color = CanonText, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    appText(
                        "Водитель уже приехал и ждёт. Отмена — $cancelFeeRub ₽ (подача), переведи водителю напрямую. Частые платные отмены ставят такси на паузу.",
                        "Водитель килде инде һәм көтә. Кире алыу — $cancelFeeRub ₽ (килеү хаҡы), водителгә туранан күсер. Йыш түләүле кире алыуҙар таксиҙы паузаға ҡуя.",
                    ),
                    color = CanonMuted, fontSize = 14.sp, lineHeight = 19.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmPaidCancel = false; onCancel() }) {
                    Text(appText("Всё равно отменить", "Барыбер кире алыу"), color = CanonRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmPaidCancel = false }) {
                    Text(appText("Я выхожу", "Мин сығам"), color = CanonGreen2, fontWeight = FontWeight.Bold)
                }
            },
        )
    }
}

// ------------------------------ «Рядом никого» (expired) ------------------------------
@Composable
private fun InstantNoDriversCard(onRetry: () -> Unit, onDone: () -> Unit) {
    // Мост в «заявку-сторож» (route-watch) появится, когда эта ветка вольётся; пока — честный повтор/позже.
    InstantFinalCard(
        icon = Icons.Default.AccessTime,
        title = appText("Рядом пока никого", "Яҡында әлегә бер кем дә юҡ"),
        subtitle = appText("Свободных водителей рядом не нашли. Попробуй ещё раз через минуту.",
            "Яҡында буш водитель табылманы. Бер минуттан ҡабат ит."),
        action = appText("Попробовать ещё раз", "Ҡабат итеп ҡарау"),
        onAction = onRetry,
        onSecondary = onDone,
    )
}

// ------------------------------ Общая финальная карточка ------------------------------
@Composable
private fun InstantFinalCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    action: String,
    onAction: () -> Unit,
    onSecondary: () -> Unit,
    extra: (@Composable () -> Unit)? = null,   // §9: блок оценки/жалобы после done
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(shape = CircleShape, color = CanonSurface) {
            Icon(icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(22.dp).size(40.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text(title, color = CanonText, fontSize = 22.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(subtitle, color = CanonMuted, fontSize = 14.sp, lineHeight = 19.sp, textAlign = TextAlign.Center)
        if (extra != null) {
            Spacer(Modifier.height(18.dp))
            extra()
        }
        Spacer(Modifier.height(28.dp))
        Button(onClick = onAction, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)) {
            Text(action, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onSecondary) { Text(appText("Закрыть", "Ябыу"), color = CanonMuted) }
    }
}

/**
 * §9 Качество: взаимная оценка завершённого заказа (звёзды, анонимно) + «Пожаловаться»
 * (категории из перечня, анонимно). Обе стороны: пассажир оценивает водителя, водитель —
 * пассажира. Плавное появление, тач-цели 40dp+, честная строка «оценка анонимна».
 */
@Composable
private fun InstantRateAndReport(order: InstantOrderDto, isDriver: Boolean) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var stars by remember(order.id) { mutableIntStateOf(0) }
    var rated by remember(order.id) { mutableStateOf(false) }
    var showReport by remember(order.id) { mutableStateOf(false) }
    val thanksMsg = appText("Спасибо за оценку!", "Баһа өсөн рәхмәт!")
    val rateFail = appText("Не получилось оценить. Проверь сеть.", "Баһалап булманы. Селтәрҙе тикшер.")
    val sentMsg = appText("Жалоба отправлена. Спасибо, разберёмся.", "Ялыу ебәрелде. Рәхмәт, тикшерербеҙ.")
    val sendFail = appText("Не удалось отправить. Проверь сеть.", "Ебәреп булманы. Селтәрҙе тикшер.")
    Card(colors = CardDefaults.cardColors(containerColor = CanonSurface), shape = CanonItemShape, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (isDriver) appText("Как прошла поездка с пассажиром?", "Пассажир менән сәфәр нисек үтте?")
                else appText("Как прошла поездка?", "Сәфәр нисек үтте?"),
                color = CanonText, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (1..5).forEach { n ->
                    val filled = n <= stars
                    val scale by animateFloatAsState(if (filled) 1f else 0.86f, label = "star$n")
                    Icon(
                        Icons.Default.Star,
                        contentDescription = appText("$n звёзд", "$n йондоҙ"),
                        tint = if (filled) CanonStar else CanonBorder,
                        modifier = Modifier.size(40.dp).graphicsLayer { scaleX = scale; scaleY = scale }
                            .clickable {
                                stars = n
                                scope.launch {
                                    ApiClient.rateInstantOrder(order.id, n)
                                        .onSuccess { if (!rated) { rated = true; Toast.makeText(ctx, thanksMsg, Toast.LENGTH_SHORT).show() } }
                                        .onFailure { Toast.makeText(ctx, rateFail, Toast.LENGTH_SHORT).show() }
                                }
                            },
                    )
                }
            }
            Text(
                appText("Оценка анонимна — видно только средний рейтинг.", "Баһа аноним — тик уртаса рейтинг күренә."),
                color = CanonMuted, fontSize = 12.sp, textAlign = TextAlign.Center,
            )
            TextButton(onClick = { showReport = true }) {
                Text(
                    if (isDriver) appText("Пожаловаться на пассажира", "Пассажирға ялыу")
                    else appText("Пожаловаться на водителя", "Водителгә ялыу"),
                    color = CanonMuted, fontSize = 13.sp,
                )
            }
        }
    }
    if (showReport) {
        ReportCategoryDialog(
            title = if (isDriver) appText("Жалоба на пассажира", "Пассажирға ялыу")
            else appText("Жалоба на водителя", "Водителгә ялыу"),
            categories = if (isDriver) reportCategoriesPassenger() else reportCategoriesDriver(),
            onDismiss = { showReport = false },
            onSend = { category, details ->
                showReport = false
                scope.launch {
                    ApiClient.reportUser(reason = details, category = category, orderId = order.id)
                        .onSuccess { Toast.makeText(ctx, sentMsg, Toast.LENGTH_SHORT).show() }
                        .onFailure { Toast.makeText(ctx, sendFail, Toast.LENGTH_SHORT).show() }
                }
            },
        )
    }
}

@Composable
private fun InstantCenterLoader(text: String) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator(color = CanonGreen2, strokeWidth = 3.dp)
        Spacer(Modifier.height(14.dp))
        Text(text, color = CanonMuted, fontSize = 14.sp)
    }
}

// ------------------------------ «Такси скоро» (гейт по флагу/городу) ------------------------------
private val WaitlistPhoneRegex = Regex("^\\+?\\d{10,15}$")   // как на сервере (family.py/waitlist.py)

/** Такси в этой точке пока выключено (глобальный запуск или город ещё не подключён).
 *  Тёплая заглушка вместо пикера + ранний доступ (§11): «оставь номер — сообщим, когда включим»
 *  и CTA для водителей «стань первым таксистом города». Успех — «Ты в списке! 🎉». */
@Composable
private fun TaxiComingSoonCard(
    availability: com.yuldash.app.data.TaxiAvailabilityDto,
    onBackToPooling: () -> Unit,
    onTaxiOnboarding: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val title = if (availability.reason == "global_off")
        appText("Такси Юлдаш совсем скоро 🚕", "Юлдаш таксиы бик тиҙҙән 🚕")
    else appText("Такси скоро в твоём городе 🚕", "Тиҙҙән таксиы һинең ҡалаңда ла 🚕")
    val serverMsg = appText(availability.messageRu, availability.messageBa)
    val body = (if (serverMsg.isNotBlank()) "$serverMsg\n\n" else "") + appText(
        "Мы подключаем города по очереди, чтобы машины точно были рядом. А попутка уже работает по всей республике.",
        "Ҡалаларҙы сиратлап тоташтырабыҙ — машиналар яҡында булһын өсөн. Ә юлдаш инде бөтә республикала эшләй.",
    )

    // Форма листа ожидания: телефон (предзаполнен у залогиненного), город (из availability), роль.
    var phone by remember { mutableStateOf("") }
    var city by remember { mutableStateOf(availability.city) }
    var role by remember { mutableStateOf("passenger") }
    var sending by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val sendErr = appText("Не получилось отправить. Проверь сеть и повтори.", "Ебәреп булманы. Селтәрҙе тикшереп ҡабатла.")
    val badPhoneErr = appText("Проверь номер: 10–15 цифр, можно с +", "Номерҙы тикшер: 10–15 һан, + менән дә мөмкин")

    // Предзаполняем телефон из профиля (Telegram-плейсхолдер tg<id> не подставляем).
    LaunchedEffect(Unit) {
        if (ApiClient.isLoggedIn()) {
            ApiClient.me().onSuccess { me ->
                val p = me.optString("phone")
                if (phone.isBlank() && WaitlistPhoneRegex.matches(p.replace(" ", "").replace("-", ""))) phone = p
            }
        }
    }

    fun submit() {
        val normalized = phone.replace(Regex("[\\s\\-()]"), "")
        if (!WaitlistPhoneRegex.matches(normalized)) { error = badPhoneErr; return }
        sending = true; error = null
        scope.launch {
            ApiClient.joinWaitlist(normalized, city.trim(), role)
                .onSuccess { sent = true }
                .onFailure { error = sendErr }
            sending = false
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))
        Surface(shape = CircleShape, color = CanonTaxiBg) {
            Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) { Text("🚕", fontSize = 44.sp) }
        }
        Spacer(Modifier.height(20.dp))
        Text(title, color = CanonText, fontSize = 22.sp, lineHeight = 27.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))

        // ---------- Ранний доступ: форма или «Ты в списке!» ----------
        AnimatedVisibility(visible = sent, enter = fadeIn(tween(300)) + expandVertically(tween(300))) {
            Surface(color = CanonMint, shape = CanonCardShape, border = BorderStroke(1.dp, CanonGreen2), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(appText("Ты в списке! 🎉", "Һин исемлектә! 🎉"), color = CanonGreen2, fontSize = 19.sp, fontWeight = FontWeight.Black)
                    Text(
                        if (role == "driver")
                            appText("Позовём одним из первых — 0% комиссии первые 3 месяца.", "Беренселәрҙән булып саҡырырбыҙ — тәүге 3 айҙа 0% комиссия.")
                        else appText("Сообщим, как только такси заработает в твоём городе.", "Такси һинең ҡалаңда эшләй башлағас та хәбәр итербеҙ."),
                        color = CanonText, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center,
                    )
                }
            }
        }
        AnimatedVisibility(visible = !sent, exit = fadeOut(tween(200)) + shrinkVertically(tween(250))) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Surface(color = CanonSurface, shape = CanonCardShape, border = BorderStroke(1.dp, CanonBorder), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            appText("Оставь номер — сообщим, когда включим", "Номерыңды ҡалдыр — ҡабыҙғас та хәбәр итербеҙ"),
                            color = CanonText, fontSize = 16.sp, fontWeight = FontWeight.Black, lineHeight = 21.sp,
                        )
                        // Роль: пассажир / водитель (тач-цель ≥48dp).
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            WaitlistRoleChip(appText("Я пассажир", "Мин пассажир"), role == "passenger", Modifier.weight(1f)) { role = "passenger" }
                            WaitlistRoleChip(appText("Я водитель", "Мин водитель"), role == "driver", Modifier.weight(1f)) { role = "driver" }
                        }
                        OutlinedTextField(
                            value = phone,
                            onValueChange = { phone = it.take(20); error = null },
                            label = { Text(appText("Телефон", "Телефон")) },
                            placeholder = { Text("+7 9xx xxx-xx-xx") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                        )
                        OutlinedTextField(
                            value = city,
                            onValueChange = { city = it.take(40) },
                            label = { Text(appText("Город", "Ҡала")) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                        )
                        error?.let { Text(it, color = CanonRed, fontSize = 13.sp, lineHeight = 18.sp) }
                        Button(
                            onClick = { submit() },
                            enabled = phone.isNotBlank() && !sending,
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2),
                        ) {
                            if (sending) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = CanonSurface)
                            else Text(appText("Записаться", "Яҙылыу"), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                // ---------- CTA для водителей: застолби город ----------
                Surface(color = CanonTaxiBg, shape = CanonCardShape, border = BorderStroke(1.dp, CanonTaxi), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            appText("Стань первым таксистом города 🚖", "Ҡаланың беренсе таксисы бул 🚖"),
                            color = CanonTaxiInk, fontSize = 16.sp, fontWeight = FontWeight.Black, lineHeight = 21.sp,
                        )
                        Text(
                            appText("Первым водителям — 0% комиссии первые 3 месяца. Оставь номер как водитель, и город твой.",
                                "Тәүге водителдәргә — тәүге 3 айҙа 0% комиссия. Номерыңды водитель итеп ҡалдыр — ҡала һинеке."),
                            color = CanonTaxiInk, fontSize = 13.sp, lineHeight = 19.sp,
                        )
                        if (role != "driver") {
                            OutlinedButton(
                                onClick = { role = "driver" },
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, CanonTaxiInk),
                            ) { Text(appText("Хочу возить", "Йөрөтөргә теләйем"), color = CanonTaxiInk, fontWeight = FontWeight.Bold) }
                        }
                        // Такси уже включено (глобально), просто не в этом городе → проверку 580-ФЗ можно пройти заранее.
                        if (availability.reason == "city_off") {
                            TextButton(onClick = onTaxiOnboarding, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                                Text(
                                    appText("Пройти проверку таксиста заранее →", "Таксист тикшереүен алдан үтергә →"),
                                    color = CanonTaxiInk, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onBackToPooling,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2),
        ) {
            Icon(Icons.Default.DirectionsCar, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(appText("Поехали попуткой", "Юлдаш менән киттек"), fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** Чип выбора роли в форме листа ожидания (тач-цель ≥48dp). */
@Composable
private fun WaitlistRoleChip(label: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = if (active) CanonMint else CanonBg,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, if (active) CanonGreen2 else CanonBorder),
        modifier = modifier.height(48.dp),
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(label, color = if (active) CanonGreen2 else CanonMuted, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}

@Composable
private fun InstantLoginNeeded(onLoginRequired: () -> Unit) {
    InstantFinalCard(
        icon = Icons.Default.DirectionsCar,
        title = appText("Войди, чтобы заказать машину", "Машина заказлар өсөн ин"),
        subtitle = appText("Быстрый заказ доступен после входа — так водитель видит, кому ехать.",
            "Тиҙ заказ ингәндән һуң эшләй — водитель кемгә барырын күрә."),
        action = appText("Войти", "Инеү"),
        onAction = onLoginRequired,
        onSecondary = onLoginRequired,
    )
}

// ==================================== ВОДИТЕЛЬ ====================================
/**
 * Контроллер водителя «на линии»: пока online — шлём presence-heartbeat (координаты, ~раз в 12с) и
 * опрашиваем входящий оффер (~раз в 4с). Пришёл оффер → полноэкранная карточка с таймером.
 * «Взять» → accept → onOpenTrip(orderId); «Пропустить» → decline. Встраивается в кабинет водителя.
 */
@Composable
internal fun InstantDriverOnlineController(online: Boolean, onOpenTrip: (Int) -> Unit) {
    val scope = rememberCoroutineScope()
    val myPoint by rememberMyPoint(active = online)
    var offer by remember { mutableStateOf<InstantOrderDto?>(null) }

    // Presence-heartbeat (координаты не логируем).
    LaunchedEffect(online) {
        if (!online) return@LaunchedEffect
        while (isActive) {
            myPoint?.let { ApiClient.fireInstantPresence(it.latitude, it.longitude) }
            delay(12_000)
        }
    }
    // Поллинг входящего оффера (пока нет активного на экране).
    LaunchedEffect(online) {
        if (!online) { offer = null; return@LaunchedEffect }
        while (isActive) {
            if (offer == null) {
                ApiClient.getDriverOffer().onSuccess { o -> if (o != null && o.status == "offered") offer = o }
            }
            delay(4_000)
        }
    }

    val current = offer
    if (online && current != null) {
        InstantOfferOverlay(
            order = current,
            onAccept = {
                scope.launch {
                    ApiClient.instantAccept(current.id)
                        .onSuccess { offer = null; onOpenTrip(it.id) }
                        .onFailure { offer = null }   // 409 (гонка/протух) → просто закрываем, ждём следующий
                }
            },
            onDecline = {
                scope.launch { ApiClient.instantDecline(current.id) }
                offer = null
            },
        )
    }
}

// ------------------------------ Полноэкранный входящий оффер ------------------------------
@Composable
private fun InstantOfferOverlay(order: InstantOrderDto, onAccept: () -> Unit, onDecline: () -> Unit) {
    val ttl = 20
    var secondsLeft by remember(order.id) { mutableIntStateOf(ttl) }
    LaunchedEffect(order.id) {
        secondsLeft = ttl
        while (secondsLeft > 0) { delay(1_000); secondsLeft-- }
        onDecline()   // таймаут — как «Пропустить»
    }
    Box(Modifier.fillMaxSize().background(CanonBg).statusBarsPadding()) {
        Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = CanonGreen2) {
                    Icon(Icons.Default.DirectionsCar, contentDescription = null, tint = CanonBg, modifier = Modifier.padding(12.dp).size(24.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(appText("Новый заказ!", "Яңы заказ!"), color = CanonText, fontSize = 22.sp, fontWeight = FontWeight.Black)
                    Text(appText("Ответь за $secondsLeft с", "$secondsLeft секундта яуап бир"), color = CanonMuted, fontSize = 13.sp)
                }
                Surface(shape = CircleShape, color = CanonSurface) {
                    Text("$secondsLeft", color = CanonGreen2, fontSize = 22.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                }
            }
            LinearProgressIndicator(
                progress = { secondsLeft / ttl.toFloat() },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = CanonGreen2, trackColor = CanonSurface,
            )
            Card(colors = CardDefaults.cardColors(containerColor = CanonSurface), shape = CanonCardShape, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${order.priceEstimate} ₽", color = CanonText, fontSize = 34.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                        if (order.category == "comfort") {
                            Surface(shape = RoundedCornerShape(10.dp), color = CanonGreen2.copy(alpha = 0.12f)) {
                                Text(appText("Комфорт", "Комфорт"), color = CanonGreen2, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
                            }
                        }
                    }
                    InstantPointRow(Icons.Default.MyLocation, appText("Подача", "Килеп алыу"), order.fromText.ifBlank { appText("Точка А", "А нөктәһе") })
                    InstantPointRow(Icons.Default.LocationOn, appText("Назначение", "Барыр урын"), order.toText.ifBlank { appText("Точка Б", "Б нөктәһе") })
                    val meta = buildList {
                        if (order.distanceKm > 0) add(appText("≈ ${order.distanceKm.toInt()} км поездка", "≈ ${order.distanceKm.toInt()} км сәфәр"))
                        if (order.etaMin > 0) add(appText("≈ ${order.etaMin.toInt()} мин", "≈ ${order.etaMin.toInt()} мин"))
                    }.joinToString("  ·  ")
                    if (meta.isNotBlank()) Text(meta, color = CanonMuted, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = onAccept, modifier = Modifier.fillMaxWidth().height(56.dp).navigationBarsPadding(), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)) {
                Icon(Icons.Default.CheckCircle, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(appText("Взять заказ", "Заказды алыу"), fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
            OutlinedButton(onClick = onDecline, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(14.dp)) {
                Text(appText("Пропустить", "Үткәреп ебәреү"), color = CanonMuted)
            }
        }
    }
}

@Composable
private fun InstantPointRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(label, color = CanonMuted, fontSize = 11.sp)
            Text(value, color = CanonText, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

// ------------------------------ Экран поездки водителя ------------------------------
/** После accept: навигация к пассажиру + кнопки «Я на месте» → «Пассажир сел» → «Завершить».
 *  На месте — живой таймер ожидания; по таймингу (5 бесплатных + 3 сверх) появляется
 *  «Пассажир не вышел» (no-show: заказ закрывается, штраф-подача фиксируется пассажиру). */
@Composable
internal fun InstantDriverTripScreen(orderId: Int, onBack: () -> Unit, onFinished: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var order by remember { mutableStateOf<InstantOrderDto?>(null) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var confirmNoShow by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(orderId) {
        ApiClient.getInstantOrder(orderId).onSuccess { order = it }
        loading = false
        while (isActive) {
            delay(5_000)
            ApiClient.getInstantOrder(orderId).onSuccess { order = it }
            if (order?.status == "done" || order?.status == "cancelled" || order?.status == "expired") break
        }
    }

    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Поездка", "Сәфәр"), onBack) }) { padding ->
        val current = order
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                loading && current == null -> InstantCenterLoader(appText("Загружаем заказ…", "Заказды йөкләйбеҙ…"))
                current == null -> InstantFinalCard(
                    icon = Icons.Default.Close,
                    title = appText("Заказ не найден", "Заказ табылманы"),
                    subtitle = appText("Возможно, он уже завершён или отменён.", "Бәлки, ул тамамланған йәки кире алынған."),
                    action = appText("К заказам", "Заказдарға"), onAction = onBack, onSecondary = onBack,
                )
                current.status == "done" -> InstantFinalCard(
                    icon = Icons.Default.CheckCircle,
                    title = appText("Поездка завершена", "Сәфәр тамамланды"),
                    subtitle = appText("Получено ${current.priceFinal ?: current.priceEstimate} ₽. Спасибо!", "${current.priceFinal ?: current.priceEstimate} ₽ алынды. Рәхмәт!"),
                    action = appText("Готово", "Әҙер"), onAction = onFinished, onSecondary = onFinished,
                    extra = { InstantRateAndReport(current, isDriver = true) },   // §9: оценить/пожаловаться
                )
                current.status == "cancelled" -> InstantFinalCard(
                    icon = Icons.Default.Close,
                    title = if (current.noShow) appText("Пассажир не вышел", "Пассажир сыҡманы")
                    else appText("Заказ отменён", "Заказ кире алынды"),
                    subtitle = when {
                        current.noShow -> appText(
                            "Заказ закрыт. Пассажиру зафиксирована плата за подачу — ${current.cancelFeeKop / 100} ₽.",
                            "Заказ ябылды. Пассажирға килеү хаҡы яҙылды — ${current.cancelFeeKop / 100} ₽.")
                        current.cancelBy == "passenger" && current.cancelFeeKop > 0 -> appText(
                            "Пассажир отменил поздно — ему зафиксирована подача ${current.cancelFeeKop / 100} ₽.",
                            "Пассажир һуң кире алды — уға килеү хаҡы яҙылды: ${current.cancelFeeKop / 100} ₽.")
                        current.cancelBy == "passenger" -> appText("Пассажир отменил заказ.", "Пассажир заказды кире алды.")
                        else -> appText("Заказ отменён.", "Заказ кире алынды.")
                    },
                    action = appText("К заказам", "Заказдарға"), onAction = onFinished, onSecondary = onFinished,
                )
                else -> Column(Modifier.fillMaxSize()) {
                    InstantRouteMap(
                        from = Point(current.fromLat, current.fromLng),
                        to = Point(current.toLat, current.toLng),
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                    Card(
                        colors = CardDefaults.cardColors(containerColor = CanonSurface),
                        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(18.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            // Пассажир + телефон (после accept)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Person, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(current.passengerName.ifBlank { appText("Пассажир", "Пассажир") }, color = CanonText, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                    Text("${current.fromText.ifBlank { appText("Точка А", "А нөктәһе") }} → ${current.toText.ifBlank { appText("Точка Б", "Б нөктәһе") }}", color = CanonMuted, fontSize = 13.sp, maxLines = 1)
                                }
                                if (current.passengerPhone.isNotBlank()) {
                                    Surface(onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${current.passengerPhone}"))) } }, shape = CircleShape, color = CanonGreen2) {
                                        Icon(Icons.Default.Phone, contentDescription = appText("Позвонить пассажиру", "Пассажирға шылтыратыу"), tint = CanonBg, modifier = Modifier.padding(12.dp).size(20.dp))
                                    }
                                }
                            }
                            val waitRub = current.waitingFeeKop / 100
                            Text(
                                appText(
                                    "Оплата: ${current.priceEstimate} ₽" + (if (waitRub > 0) " + $waitRub ₽ ожидание" else "") + " наличными/переводом",
                                    "Түләү: ${current.priceEstimate} ₽" + (if (waitRub > 0) " + $waitRub ₽ көтөү" else "") + " аҡсалата/күсереп",
                                ),
                                color = CanonMuted, fontSize = 13.sp,
                            )
                            // «Я на месте» → таймер ожидания (бесплатное окно и платные минуты — как у пассажира).
                            if (current.status == "arriving") {
                                InstantWaitingRow(current)
                            }
                            // Главная кнопка по фазе (§5: accepted = еду, arriving = на месте/жду).
                            val (label, next) = when (current.status) {
                                "accepted" -> appText("Я на месте", "Мин урында") to "arriving"
                                "arriving" -> appText("Пассажир сел", "Пассажир ултырҙы") to "onboard"
                                "onboard" -> appText("Завершить поездку", "Сәфәрҙе тамамлау") to "done"
                                else -> appText("Обновить", "Яңыртыу") to ""
                            }
                            Button(
                                onClick = {
                                    if (next.isBlank() || busy) return@Button
                                    busy = true
                                    scope.launch {
                                        val res = when (next) {
                                            "arriving" -> ApiClient.instantArrived(current.id)
                                            "onboard" -> ApiClient.instantOnboard(current.id)
                                            else -> ApiClient.instantDone(current.id)
                                        }
                                        res.onSuccess { order = it; actionError = null }
                                        res.onFailure { actionError = (it as? ApiException)?.message }
                                        busy = false
                                    }
                                },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth().height(54.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2),
                            ) {
                                if (busy) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = CanonBg)
                                else Text(label, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            }
                            // «Пассажир не вышел» — появляется по честному таймингу сервера
                            // (5 бесплатных минут + 3 сверх после «Я на месте»).
                            val nowMs by rememberNowMs()
                            val noShowReady = current.status == "arriving" &&
                                (current.noShowAt?.let { isoUtcToEpochMs(it) }?.let { nowMs >= it } == true)
                            AnimatedVisibility(visible = noShowReady, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                                OutlinedButton(
                                    onClick = { confirmNoShow = true },
                                    modifier = Modifier.fillMaxWidth().height(48.dp),
                                    shape = RoundedCornerShape(14.dp),
                                ) {
                                    Text(appText("Пассажир не вышел", "Пассажир сыҡманы"), color = CanonRed, fontWeight = FontWeight.Bold)
                                }
                            }
                            if (actionError != null) {
                                Text(actionError!!, color = CanonRed, fontSize = 13.sp)
                            }
                            TextButton(
                                onClick = { scope.launch { ApiClient.instantCancel(current.id).onSuccess { order = it } } },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(appText("Отменить заказ", "Заказды кире алыу"), color = CanonRed) }
                        }
                    }
                }
            }
        }
    }
    val activeOrder = order
    if (confirmNoShow && activeOrder != null) {
        AlertDialog(
            onDismissRequest = { confirmNoShow = false },
            containerColor = CanonSurface,
            title = { Text(appText("Пассажир не вышел?", "Пассажир сыҡманымы?"), color = CanonText, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    appText(
                        "Заказ закроется, пассажиру зафиксируется плата за подачу. Позвони ему перед этим — вдруг уже бежит.",
                        "Заказ ябыла, пассажирға килеү хаҡы яҙыла. Тәүҙә шылтыратып ҡара — бәлки, йүгереп килә лә.",
                    ),
                    color = CanonMuted, fontSize = 14.sp, lineHeight = 19.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmNoShow = false
                    scope.launch {
                        ApiClient.instantCancel(activeOrder.id, reason = "no_show")
                            .onSuccess { order = it; actionError = null }
                            .onFailure { actionError = (it as? ApiException)?.message }
                    }
                }) { Text(appText("Да, не вышел", "Эйе, сыҡманы"), color = CanonRed, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { confirmNoShow = false }) {
                    Text(appText("Ещё подожду", "Тағы көтәм"), color = CanonGreen2, fontWeight = FontWeight.Bold)
                }
            },
        )
    }
}
