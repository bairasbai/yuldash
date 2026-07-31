package com.yuldash.app

import androidx.compose.ui.res.painterResource
import android.graphics.PointF
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.LocalTaxi
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sos
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.yandex.mapkit.MapKitFactory
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.map.CameraPosition
import com.yandex.mapkit.map.IconStyle
import com.yandex.mapkit.mapview.MapView
import com.yandex.runtime.image.ImageProvider
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

// ───────────────────────── Типографика экрана: РОВНО четыре размера ─────────────────────────
// До этого раунда в файле их было двенадцать (11·12·13·14·15·16·18·19·22·26·30·34) — от этого
// экран читался как таблица, а не как ответ на вопрос «что сейчас происходит». Теперь у каждого
// размера одна роль, и доминанта на экране всегда одна: цена или статус.
private val TxHero = 28.sp        // ДОМИНАНТА: цена и статус. Ровно одна такая надпись на экран.
private val TxTitle = 20.sp       // заголовок экрана или карточки
private val TxBody = 15.sp        // тело: адреса, имена, значения, надписи кнопок
private val TxCaption = 13.sp     // подпись: мета, пояснения, лейблы полей
// Межстрочные — кратны 4dp, чтобы вертикальный ритм не плыл на длинном башкирском.
private val LhHero = 32.sp
private val LhTitle = 26.sp
private val LhBody = 20.sp
private val LhCaption = 18.sp

// Единая форма интерактивных элементов внутри карточек (поля, кнопки-строки, чипы).
// Отдельная от CanonCardShape(28)/CanonItemShape(22) — это «мелкая» ступень той же лестницы.
private val InstantControlShape = RoundedCornerShape(16.dp)

/**
 * Тон финальной карточки. До этого раунда крестик отмены был ЗЕЛЁНЫМ: цвет говорил «всё
 * хорошо», а надпись под ним — «заказ отменён». Человек читает цвет раньше текста, поэтому
 * они обязаны говорить одно и то же.
 */
private enum class InstantTone { Good, Bad }

/**
 * Полоска фаз заказа: «Ищем → Едет → На месте → В пути». Человек на морозе не читает —
 * он смотрит. Полоска отвечает «где я сейчас» за долю секунды и заполняется с анимацией,
 * поэтому смена фазы видна как движение вперёд, а не как подмена надписи.
 *
 * @param step 0 = ищем, 1 = водитель едет, 2 = машина на месте, 3 = в пути.
 */
@Composable
private fun InstantTripPhaseBar(step: Int, modifier: Modifier = Modifier) {
    val labels = listOf(
        appText("Ищем", "Эҙләйбеҙ"),
        appText("Едет", "Килә"),
        appText("На месте", "Урынында"),
        appText("В пути", "Юлда"),
    )
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { i, label ->
            val passed = i <= step
            val segment by animateColorAsState(
                if (passed) CanonGreen2 else CanonBorder, tween(340), label = "phaseSeg$i",
            )
            val ink by animateColorAsState(
                if (i == step) CanonText else CanonMuted, tween(340), label = "phaseInk$i",
            )
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(segment))
                Spacer(Modifier.height(6.dp))
                Text(
                    label, color = ink, fontSize = TxCaption, lineHeight = LhCaption,
                    fontWeight = if (i == step) FontWeight.Black else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                )
            }
        }
    }
}

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
 *  car/carBearing (B7a-3) — live-позиция машины (нав-стрелка, как у попутки): один placemark,
 *  двигаем geometry без пересоздания — плавно и без мигания.
 *  Локаль MapKit задаётся при первой карте приложения; тут только initialize (повторный setLocale бы упал). */
@Composable
internal fun InstantRouteMap(
    from: Point?,
    to: Point?,
    modifier: Modifier = Modifier,
    car: Point? = null,
    carBearing: Double? = null,
    nearbyDrivers: List<com.yuldash.app.data.NearbyDriverDto> = emptyList(),
) {
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
    // Маркер машины (live-трек, B7a-3): нав-стрелка (как стрелка попутчика), поворот по bearing.
    // Placemark один на жизнь карты — обновляем geometry/direction, не пересоздаём (без мигания).
    val carPm = remember { mutableStateOf<com.yandex.mapkit.map.PlacemarkMapObject?>(null) }
    LaunchedEffect(car, carBearing) {
        val map = mapView.mapWindow.map
        val point = car
        if (point == null) {
            carPm.value?.let { runCatching { it.isVisible = false } }
            return@LaunchedEffect
        }
        val pm = carPm.value ?: runCatching {
            map.mapObjects.addPlacemark(point).apply {
                setIcon(ImageProvider.fromBitmap(peerArrowBitmap()))
                setIconStyle(
                    com.yandex.mapkit.map.IconStyle()
                        .setAnchor(android.graphics.PointF(0.5f, 0.5f))
                        .setRotationType(com.yandex.mapkit.map.RotationType.ROTATE)
                )
            }
        }.getOrNull()?.also { carPm.value = it } ?: return@LaunchedEffect
        runCatching {
            pm.isVisible = true
            pm.geometry = point
            carBearing?.let { pm.direction = it.toFloat() }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            carPm.value?.let { runCatching { mapView.mapWindow.map.mapObjects.remove(it) } }
            carPm.value = null
        }
    }
    // «Честные машины рядом»: показываем ТОЛЬКО до выбора адреса (to == null), чтобы не мешать
    // маршруту. Каждая машинка — реальная точка из presence + ≈ETA (без цены и без личности).
    DisposableEffect(nearbyDrivers, to) {
        val map = mapView.mapWindow.map
        val carObjs = mutableListOf<com.yandex.mapkit.map.MapObject>()
        if (to == null) {
            nearbyDrivers.forEach { d ->
                runCatching {
                    carObjs += map.mapObjects.addPlacemark(Point(d.lat, d.lng)).apply {
                        setIcon(ImageProvider.fromBitmap(carEtaBitmap("≈${d.etaMin} мин")))
                        setIconStyle(IconStyle().setAnchor(PointF(0.5f, 1f)))
                    }
                }
            }
        }
        onDispose { carObjs.forEach { runCatching { map.mapObjects.remove(it) } } }
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
    onOpenScheduled: () -> Unit = {},    // «На время»: предзаказ создан → «Мои предзаказы»
) {
    val scope = rememberCoroutineScope()
    val loggedIn = ApiClient.isLoggedIn()
    var order by remember { mutableStateOf<InstantOrderDto?>(null) }
    // Предзаказ «на время» создан → карточка подтверждения (не активный заказ, живёт в «Моих предзаказах»).
    var scheduledConfirm by remember { mutableStateOf<InstantOrderDto?>(null) }
    var checking by remember { mutableStateOf(loggedIn) }   // первичная загрузка: есть ли активный заказ
    // Гейт такси (волна 2): доступно ли такси в моей точке (глобальный флаг + города на сервере).
    // Сеть упала → фолбэк «доступно» (обычный пикер): сервер всё равно гейтит оценку и заказ.
    var availability by remember { mutableStateOf<com.yuldash.app.data.TaxiAvailabilityDto?>(null) }
    // Восстановление входа упало по сети → показываем retry вместо тихого падения в пикер (могли потерять живой заказ).
    var restoreError by remember { mutableStateOf(false) }
    // Связь с сервером при поллинге активного заказа потеряна → мягкий баннер «пробуем ещё», не молчим.
    var pollOffline by remember { mutableStateOf(false) }
    var restoreTick by remember { mutableIntStateOf(0) }   // Int-состояние без автобокса

    // Восстановление активного заказа при входе на экран + проверка доступности такси в точке.
    LaunchedEffect(restoreTick) {
        if (!loggedIn) { checking = false; return@LaunchedEffect }
        checking = true
        restoreError = false
        val lat = LocationPrefs.lastLat ?: InstantDefaultPoint.latitude
        val lng = LocationPrefs.lastLng ?: InstantDefaultPoint.longitude
        // Доступность: сеть упала → фолбэк «доступно» (сервер всё равно гейтит). А вот список заказов
        // важен: если он не загрузился, НЕ роняем в пикер молча — вдруг есть живой заказ.
        ApiClient.getTaxiAvailability(lat, lng).onSuccess { availability = it }
        ApiClient.getMyInstantOrders(limit = 5)
            // Предзаказы (scheduled) сюда не тянем — они живут в «Моих предзаказах», а не как активный заказ.
            // isWaitingQueue: заказ формально expired, но человек нажал «Подожду машину» —
            // воркер ещё ищет, и такой заказ надо восстановить как живой.
            .onSuccess { list -> order = list.firstOrNull { (!it.isTerminal || it.isWaitingQueue) && !it.isScheduled } }
            .onFailure { restoreError = true }
        checking = false
    }

    // Поллинг статуса активного заказа (пока заказ есть и не терминальный или стоит в очереди ожидания).
    val activeId = order?.takeIf { !it.isTerminal || it.isWaitingQueue }?.id
    LaunchedEffect(activeId) {
        val id = activeId ?: return@LaunchedEffect
        while (isActive) {
            delay(3_000)
            ApiClient.getInstantOrder(id)
                .onSuccess { order = it; pollOffline = false }
                .onFailure { pollOffline = true }   // связь потеряна — не глотаем, показываем баннер
            val o = order
            if (o != null && o.isTerminal && !o.isWaitingQueue) break
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
            val av = availability
            // Фаза заказа как ОДНО значение: и порядок веток тот же, что был, и есть чем кормить
            // AnimatedContent. Раньше `when` менял полэкрана мгновенно — человек, который стоит
            // на морозе, видел рывок вместо перехода «ищем → нашли».
            val phase = when {
                !loggedIn -> "login"
                checking -> "checking"
                scheduledConfirm != null -> "scheduled"
                current == null && restoreError -> "restoreError"
                current == null && av?.enabled == false -> "comingSoon"
                current == null -> "picker"
                current.isSearching -> "searching"
                current.isActive -> "enroute"
                current.status == "expired" -> "expired"
                current.status == "cancelled" -> "cancelled"
                else -> "done"
            }
            AnimatedContent(
                targetState = phase,
                // Спокойный кросс-фейд с еле заметным подъёмом: смена фазы читается как движение,
                // а не как подмена экрана. Уход быстрее прихода — так переход кажется отзывчивее.
                transitionSpec = {
                    (fadeIn(tween(280)) + slideInVertically(tween(320)) { it / 20 })
                        .togetherWith(fadeOut(tween(160)))
                },
                label = "instantPhase",
            ) { p ->
                // Во время перехода недолго живут обе фазы, а данные уже новые — поэтому везде
                // безопасный `?.let`, без `!!`: уходящая карточка просто не рисуется.
                when (p) {
                    "login" -> InstantLoginNeeded(onLoginRequired)
                    "checking" -> InstantCheckingSkeleton()
                    // Предзаказ «на время» создан → спокойное подтверждение + путь в «Мои предзаказы».
                    "scheduled" -> scheduledConfirm?.let { sc ->
                        InstantScheduledCreatedCard(
                            order = sc,
                            onOpenScheduled = onOpenScheduled,
                            onNewOrder = { scheduledConfirm = null },
                            onBack = onBack,
                        )
                    }
                    // Вход не загрузился по сети → не роняем в пикер молча (мог быть живой заказ), даём «Повторить».
                    "restoreError" -> InstantRetryCard(
                        onRetry = { restoreTick++ },
                        onBack = onBack,
                    )
                    // Гейт (a): такси выключено глобально или в этом городе → тёплая заглушка «Скоро».
                    // Активный заказ (если вдруг успел создаться до выключения) показываем как обычно.
                    "comingSoon" -> av?.let {
                        TaxiComingSoonCard(
                            availability = it,
                            onBackToPooling = onBack,
                            onTaxiOnboarding = onTaxiOnboarding,
                        )
                    }
                    "picker" -> InstantDestinationPicker(
                        onOrderCreated = { order = it },
                        onScheduled = { scheduledConfirm = it },
                    )
                    "searching" -> current?.let { o ->
                        InstantSearchingCard(
                            order = o,
                            onCancel = { scope.launch { ApiClient.instantCancel(o.id).onSuccess { order = it } } },
                        )
                    }
                    "enroute" -> current?.let { o ->
                        InstantDriverEnRouteCard(
                            order = o,
                            onCancel = { scope.launch { ApiClient.instantCancel(o.id).onSuccess { order = it } } },
                        )
                    }
                    "expired" -> current?.let { o ->
                        InstantNoDriversCard(
                            order = o,
                            onRetry = { order = null },
                            onDone = onBack,
                            onOrderUpdated = { order = it },
                        )
                    }
                    "cancelled" -> current?.let { o ->
                        InstantFinalCard(
                            icon = Icons.Default.Close,
                            title = if (o.noShow) appText("Поездка не состоялась", "Сәфәр булманы")
                            else appText("Заказ отменён", "Заказ ҡабул ителмәне"),
                            // Честные тексты про штраф/страйки (Модель А: фиксируем, деньги не списываем).
                            subtitle = when {
                                o.noShow -> appText(
                                    "Водитель ждал ${o.waitFreeMin}+ минут, но не дождался. Подача — ${o.cancelFeeKop / 100} ₽, переведи водителю. Частые такие отмены ставят такси на паузу.",
                                    "Водитель ${o.waitFreeMin}+ минут көттө, тик көтөп еткермәне. Килеү хаҡы — ${o.cancelFeeKop / 100} ₽, водителгә күсер. Йыш улай булһа — такси паузаға китә.")
                                o.cancelFeeKop > 0 && o.cancelBy == "passenger" -> appText(
                                    "Отмена была платной: ${o.cancelFeeKop / 100} ₽ (подача) — переведи водителю. Частые платные отмены ставят такси на паузу.",
                                    "Кире алыу түләүле булды: ${o.cancelFeeKop / 100} ₽ (килеү хаҡы) — водителгә күсер. Йыш түләүле кире алыуҙар таксиҙы паузаға ҡуя.")
                                o.cancelBy == "driver" -> appText("Водитель отменил. Попробуй заказать снова.", "Водитель баш тартты. Ҡабат заказ ит.")
                                else -> appText("Ты отменил заказ — бесплатно.", "Һин заказды кире алдың — бушлай.")
                            },
                            action = appText("Новый заказ", "Яңы заказ"),
                            onAction = { order = null },
                            onSecondary = onBack,
                            tone = InstantTone.Bad,   // отмена — красный кружок, не зелёный
                            // B8-8: телефон/чат уже открывались → мягко напоминаем завершать поездку в приложении.
                            extra = if (o.contactThenCancel) ({ ContactCancelSoftBanner() }) else null,
                        )
                    }
                    else -> current?.let { o ->   // done
                        InstantFinalCard(
                            icon = Icons.Default.CheckCircle,
                            title = appText("Поездка завершена", "Сәфәр тамамланды"),
                            // Цена — доминанта итога, маршрут под ней подписью: человек проверяет
                            // «сколько», а не перечитывает адреса.
                            hero = "${o.priceFinal ?: o.priceEstimate} ₽",
                            subtitle = appText(
                                "${o.fromText.ifBlank { "Точка А" }} → ${o.toText.ifBlank { "Точка Б" }}",
                                "${o.fromText.ifBlank { "А нөктәһе" }} → ${o.toText.ifBlank { "Б нөктәһе" }}"),
                            action = appText("Новый заказ", "Яңы заказ"),
                            onAction = { order = null },
                            onSecondary = onBack,
                            extra = {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    // Онлайн-оплата завершённого такси-заказа (карта/СБП через ЮKassa, за флагом).
                                    // 503 (провайдер выключен) → карточка тихо исчезает на сессию (OnlinePayGate).
                                    PayOnlineCard(
                                        amountRub = (o.priceFinal ?: o.priceEstimate).takeIf { it > 0 },
                                        pay = { m -> ApiClient.payInstantOrder(o.id, m) },
                                    )
                                    InstantRateAndReport(o, isDriver = false)   // §9: оценить/пожаловаться
                                    // Чек за поездку: справка на работу, «рәхмәт» водителю и «забыл вещь».
                                    TaxiReceiptLink(o.id)
                                    TaxiDisputeLink(o, isDriver = false)
                                }
                            },
                        )
                    }
                }
            }
            // Связь потеряна во время живого заказа: мягкий баннер сверху, поллинг сам возобновится.
            androidx.compose.animation.AnimatedVisibility(
                visible = pollOffline && current?.isTerminal == false,
                enter = fadeIn(), exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter),
            ) { InstantOfflineBanner() }
        }
    }
}

@Composable
private fun InstantOfflineBanner() {
    Surface(
        color = CanonWarnBg,
        contentColor = CanonText,
        shape = CanonItemShape,
        shadowElevation = 4.dp,
        modifier = Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(strokeWidth = 2.dp, color = CanonWarn, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                appText("Связь потеряна — пробуем ещё…", "Бәйләнеш өҙөлдө — тағы тырышабыҙ…"),
                color = CanonWarn, fontSize = TxCaption, lineHeight = LhCaption, fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * Первая загрузка экрана: проверяем, нет ли живого заказа. Голый спиннер по центру не говорил
 * ничего — теперь показываем форму будущего контента (скелетон карты и карточек маршрута), и
 * появление реального экрана не выглядит скачком.
 */
@Composable
private fun InstantCheckingSkeleton() {
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SkeletonBox(widthFraction = 0.56f, height = 28.dp, shape = InstantControlShape)
        SkeletonBox(widthFraction = 0.84f, height = 16.dp, shape = InstantControlShape)
        Spacer(Modifier.height(4.dp))
        SkeletonBox(height = 192.dp, shape = CanonItemShape)
        SkeletonBox(height = 76.dp, shape = CanonItemShape)
        SkeletonBox(height = 120.dp, shape = CanonItemShape)
        Spacer(Modifier.height(4.dp))
        Text(
            appText("Проверяем, нет ли активного заказа…", "Әүҙем заказ бармы — тикшерәбеҙ…"),
            color = CanonMuted, fontSize = TxCaption, lineHeight = LhCaption,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
    }
}

// Вход не загрузился по сети: не роняем в пикер — предлагаем повторить (мог быть живой заказ).
@Composable
private fun InstantRetryCard(onRetry: () -> Unit, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(shape = CircleShape, color = CanonWarnBg, modifier = Modifier.appearIn(0)) {
            Icon(Icons.Default.CloudOff, contentDescription = null, tint = CanonWarn, modifier = Modifier.padding(20.dp).size(40.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text(
            appText("Не удалось проверить заказ", "Заказды тикшереп булманы"),
            color = CanonText, fontWeight = FontWeight.Black, fontSize = TxTitle, lineHeight = LhTitle,
            textAlign = TextAlign.Center, modifier = Modifier.appearIn(1),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            appText("Проверь интернет и попробуй ещё раз.", "Интернетты тикшереп, ҡабат ҡара."),
            color = CanonMuted, fontSize = TxBody, lineHeight = LhBody,
            textAlign = TextAlign.Center, modifier = Modifier.appearIn(2),
        )
        Spacer(Modifier.height(24.dp))
        Column(Modifier.appearIn(3), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AppButton(text = appText("Повторить", "Ҡабатларға"), onClick = onRetry, style = AppButtonStyle.Primary)
            AppButton(text = appText("Назад", "Артҡа"), onClick = onBack, style = AppButtonStyle.Secondary)
        }
    }
}

// ------------------------------ «Куда едем?» (пикер + оценка) ------------------------------

/** Русское склонение: «1 машина», «2 машины», «5 машин». Раньше всегда было «N машин рядом»,
 *  и при одной свободной машине бейдж читался как опечатка. В башкирском счётное слово
 *  остаётся в единственном числе — там менять нечего. */
private fun carsWordRu(n: Int): String {
    val h = n % 100
    val t = n % 10
    return when {
        h in 11..14 -> "машин"
        t == 1 -> "машина"
        t in 2..4 -> "машины"
        else -> "машин"
    }
}

/**
 * Бейдж поверх карты: сколько РЕАЛЬНЫХ машин на линии рядом. Вынесен в отдельную функцию не
 * ради красоты — `AnimatedVisibility` внутри `Box`, когда выше по коду есть `Column`, не
 * компилируется (компилятор берёт ColumnScope-версию, а получателя нет).
 *
 * @param loaded пришёл ли успешный ответ. Пока нет — молчим: «рядом никого» без ответа сервера
 *               было бы выдумкой.
 */
@Composable
private fun InstantNearbyBadge(count: Int, loaded: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = loaded,
        enter = fadeIn(tween(280)) + expandVertically(),
        exit = fadeOut(tween(160)) + shrinkVertically(),
        modifier = modifier,
    ) {
        Surface(color = CanonSurface, shape = CircleShape, shadowElevation = 2.dp) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 6.dp).heightIn(min = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.LocalTaxi,
                    contentDescription = appText("Машины рядом", "Яҡындағы машиналар"),
                    tint = if (count > 0) CanonTaxi else CanonMuted, modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                AnimatedContent(targetState = count, label = "nearbyCount") { n ->
                    Text(
                        if (n > 0) appText("$n ${carsWordRu(n)} рядом", "$n машина яҡында")
                        else appText("Рядом машин нет — поищем дальше", "Яҡында машина юҡ — арыраҡ ҡарайбыҙ"),
                        color = if (n > 0) CanonText else CanonMuted,
                        fontSize = TxCaption, lineHeight = LhCaption, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Подсказки адреса — со ВСЕМИ состояниями, а не только с успехом. Раньше здесь был голый
 * `forEach`: пока геокодер думал, список был пуст, и «ещё ищем» ничем не отличалось от
 * «ничего не нашли» — человек в обоих случаях видел пустоту и не понимал, ждать ему или
 * переписывать адрес.
 */
@Composable
private fun InstantAddressResults(
    query: String,
    searching: Boolean,
    hits: List<com.yuldash.app.data.GeoHit>,
    onPick: (com.yuldash.app.data.GeoHit) -> Unit,
) {
    // Короче двух букв не ищем вообще (дебаунс в пикере) — значит и «не нашли» показывать не за что.
    val asked = query.trim().length >= 2
    val state = when {
        searching -> "loading"
        asked && hits.isEmpty() -> "empty"
        hits.isEmpty() -> "idle"
        else -> "hits"
    }
    AnimatedContent(
        targetState = state,
        transitionSpec = { fadeIn(tween(220)).togetherWith(fadeOut(tween(120))) },
        label = "addrResults",
    ) { s ->
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (s) {
                // Форма будущего списка вместо пустоты: видно, что поиск идёт.
                "loading" -> repeat(3) { i ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        SkeletonBox(widthFraction = if (i == 2) 0.52f else 0.86f, height = 14.dp, shape = CircleShape)
                    }
                }
                "empty" -> Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, tint = CanonMuted, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        appText("Такого адреса не нашли. Попробуй короче — «Ленина 12» — или выбери точку на карте.",
                            "Бындай адрес табылманы. Ҡыҫҡараҡ яҙып ҡара — «Ленина 12» — йәки картанан нөктә һайла."),
                        color = CanonMuted, fontSize = TxCaption, lineHeight = LhCaption,
                    )
                }
                "hits" -> hits.forEach { hit ->
                    Row(
                        Modifier.fillMaxWidth()
                            // heightIn до clickable: тач-цель = 48dp, а длинный адрес в две строки
                            // растягивает строку, а не обрезается.
                            .heightIn(min = 48.dp)
                            .clip(InstantControlShape)
                            .clickable { onPick(hit) }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.LocationOn, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            hit.title, color = CanonText, fontSize = TxBody, lineHeight = LhBody,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InstantDestinationPicker(
    onOrderCreated: (InstantOrderDto) -> Unit,
    onScheduled: (InstantOrderDto) -> Unit = {},
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val myPoint by rememberMyPoint(active = true)

    // Подача: сейчас (null) или «на время» (epoch ms выбранного времени). ≤7 суток, не в прошлом.
    var scheduledAtMs by remember { mutableStateOf<Long?>(null) }

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
    // Идёт ли сейчас поиск адреса. Без этого флага «ничего не нашли» мигало бы во время
    // дебаунса на каждой букве — а «пусто» обязано отличаться от «ещё ищем».
    var searchingAddr by remember { mutableStateOf(false) }

    var estimate by remember { mutableStateOf<InstantEstimateDto?>(null) }
    var estimating by remember { mutableStateOf(false) }
    // Ручной повтор оценки цены после сетевой ошибки: раньше ошибка была тупиком —
    // красная строка без единой кнопки, и заказать было нельзя вообще.
    var estimateTick by remember { mutableIntStateOf(0) }
    var creating by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var category by remember { mutableStateOf("standard") }   // §6: standard = Эконом, comfort = Комфорт
    var pickOnMap by remember { mutableStateOf(false) }   // оверлей выбора точки Б на карте
    var pickFromOnMap by remember { mutableStateOf(false) }

    // Детали заказа (аудит 2026-07-26). Свёрнуты по умолчанию: 9 заказов из 10 — обычные,
    // лишние поля на главном пути только мешают.
    var detailsOpen by remember { mutableStateOf(false) }
    var comment by remember { mutableStateOf("") }     // «за магазином, синие ворота»
    var entrance by remember { mutableStateOf("") }    // подъезд / квартира / этаж
    var forOther by remember { mutableStateOf(false) } // заказ ДЛЯ ДРУГОГО человека
    var forName by remember { mutableStateOf("") }
    var forPhone by remember { mutableStateOf("") }

    // Сохранённые (Дом/Работа) + недавние: быстрый выбор адреса Б без повторного геокодинга.
    var savedPlaces by remember { mutableStateOf<List<com.yuldash.app.data.SavedPlaceDto>>(emptyList()) }
    var recentPlaces by remember { mutableStateOf<List<com.yuldash.app.data.RecentPlaceDto>>(emptyList()) }
    var placesReload by remember { mutableIntStateOf(0) }
    LaunchedEffect(placesReload) {
        if (ApiClient.isLoggedIn()) {
            ApiClient.getSavedPlaces().onSuccess { savedPlaces = it }
            ApiClient.getRecentPlaces().onSuccess { recentPlaces = it }
        }
    }
    // Быстрый выбор точки Б: подставляем адрес и координаты сразу (без сети).
    fun pickDestination(address: String, lat: Double, lng: Double) {
        toPoint = Point(lat, lng); toText = address; query = ""; suggestions = emptyList()
    }

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
        if (query.trim().length < 2) { suggestions = emptyList(); searchingAddr = false; return@LaunchedEffect }
        searchingAddr = true
        delay(350)
        suggestions = GeocoderClient.suggest(query).take(6)
        searchingAddr = false
    }

    // Оценка цены, когда есть обе точки (и при смене класса — тариф другой).
    LaunchedEffect(effFrom, toPoint, category, estimateTick) {
        val f = effFrom; val t = toPoint
        if (f == null || t == null) { estimate = null; return@LaunchedEffect }
        delay(350)   // дебаунс: позиция уточняется GPS-фиксами — не дёргаем /estimate на каждый
        estimating = true; errorText = null
        ApiClient.instantEstimate(f.latitude, f.longitude, t.latitude, t.longitude, fromText, toText, category)
            .onSuccess { estimate = it }
            .onFailure {
                estimate = null   // сбрасываем устаревшую цену → кнопка «Вызвать» гаснет, не заказываем по старой оценке
                errorText = (it as? ApiException)?.message ?: estimateFailMsg
            }
        estimating = false
    }

    // «Честные машины рядом»: пока адрес Б не выбран — реальные машины на линии рядом (presence).
    // Обновляем раз в 15с. Выбрал адрес → прячем (на карте появится маршрут). Нет — просто пусто.
    var nearbyDrivers by remember { mutableStateOf<List<com.yuldash.app.data.NearbyDriverDto>>(emptyList()) }
    // Пришёл ли хоть один УСПЕШНЫЙ ответ. Без этого «машин рядом нет» и «ещё не спросили»
    // выглядят одинаково — пустым местом, и мы молча врём человеку, что рядом пусто.
    var nearbyLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(effFrom, toPoint) {
        if (toPoint != null) { nearbyDrivers = emptyList(); return@LaunchedEffect }
        val f = effFrom ?: return@LaunchedEffect
        while (true) {
            ApiClient.getNearbyDrivers(f.latitude, f.longitude).onSuccess { nearbyDrivers = it; nearbyLoaded = true }
            delay(15_000)
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            appText("Куда едем?", "Ҡайҙа барабыҙ?"),
            color = CanonText, fontSize = TxTitle, lineHeight = LhTitle, fontWeight = FontWeight.Black,
        )
        Text(
            appText("Машина приедет за тобой. Цену считаем заранее — без сюрпризов.",
                "Машина һине алырға килә. Хаҡты алдан иҫәпләйбеҙ — сюрприздарһыҙ."),
            color = CanonMuted, fontSize = TxCaption, lineHeight = LhCaption,
        )

        // Карта с РЕАЛЬНЫМИ машинами рядом (честно, без выдуманной цены): видно, что помощь близко.
        // Показываем, только когда знаем позицию. Машинки — из presence, ≈ETA до подачи.
        if (effFrom != null) {
            Card(shape = CanonItemShape, colors = CardDefaults.cardColors(containerColor = CanonSurface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
                Box {
                    InstantRouteMap(
                        from = effFrom, to = null, nearbyDrivers = nearbyDrivers,
                        modifier = Modifier.fillMaxWidth().height(190.dp),
                    )
                    InstantNearbyBadge(
                        count = nearbyDrivers.size,
                        loaded = nearbyLoaded,
                        modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
                    )
                }
            }
        }

        // Точка А
        Card(colors = CardDefaults.cardColors(containerColor = CanonSurface), shape = CanonItemShape) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.MyLocation, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        appText("Откуда", "Ҡайҙан"),
                        color = CanonMuted, fontSize = TxCaption, lineHeight = LhCaption,
                    )
                    val label = when {
                        fromManual && fromText.isNotBlank() -> fromText
                        effFrom != null -> appText("Моя позиция", "Минең урын")
                        hasLocPerm -> appText("Определяем…", "Билдәләйбеҙ…")
                        else -> appText("Включи геолокацию", "Геолокацияны ҡабыҙ")
                    }
                    // «Определяем…» → «Моя позиция» приходило рывком, будто экран моргнул.
                    AnimatedContent(targetState = label, label = "fromLabel") { text ->
                        Text(
                            text, color = CanonText, fontSize = TxBody, lineHeight = LhBody,
                            fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                // Тач-цель: у TextButton своя высота 40dp — ниже нормы 48dp, а мимо этой кнопки
                // человек промахивается в перчатках, стоя на остановке.
                if (!hasLocPerm && !fromManual) {
                    TextButton(
                        onClick = { locationPermLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(
                            appText("Включить", "Ҡабыҙ"), color = CanonGreen2,
                            fontSize = TxBody, lineHeight = LhBody, fontWeight = FontWeight.Bold,
                        )
                    }
                } else {
                    TextButton(onClick = { pickFromOnMap = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(
                            appText("На карте", "Картала"), color = CanonGreen2,
                            fontSize = TxBody, lineHeight = LhBody,
                        )
                    }
                }
            }
        }

        // Быстрый выбор: Дом/Работа + недавние (до ввода адреса). Тап подставляет адрес и координаты сразу.
        if (toPoint == null) {
            QuickPlacesBlock(
                saved = savedPlaces,
                recent = recentPlaces,
                onPick = { address, lat, lng -> pickDestination(address, lat, lng) },
            )
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
                InstantAddressResults(
                    query = query,
                    searching = searchingAddr,
                    hits = suggestions,
                    onPick = { hit ->
                        toPoint = Point(hit.lat, hit.lon); toText = hit.title; query = ""; suggestions = emptyList()
                    },
                )
                OutlinedButton(
                    onClick = { pickOnMap = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = InstantControlShape,
                ) {
                    Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        appText("Выбрать точку на карте", "Картала нөктә һайлау"),
                        fontSize = TxBody, lineHeight = LhBody,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    )
                }
            }
        }

        // «Сохранить как Дом/Работу» для выбранного адреса — потом заказывать в один тап.
        if (toPoint != null && toText.isNotBlank()) {
            SaveAsPlaceChips(
                address = toText,
                lat = toPoint!!.latitude,
                lng = toPoint!!.longitude,
                savedHome = savedPlaces.firstOrNull { it.kind == "home" },
                savedWork = savedPlaces.firstOrNull { it.kind == "work" },
                onSaved = { placesReload++ },
            )
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
                    Text("⚡", fontSize = TxTitle, lineHeight = LhTitle)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            // Серверный текст (двуязычный) — источник правды; локальный — фолбэк.
                            appText(
                                est?.surgeNoteRu?.ifBlank { null } ?: "Сейчас заказов больше обычного — цена выше на $pct%. Вызвать или подождать?",
                                est?.surgeNoteBa?.ifBlank { null } ?: "Хәҙер заказдар күберәк — хаҡ $pct%-ҡа юғарыраҡ. Саҡырырғамы, әллә көтөргәме?",
                            ),
                            color = CanonText, fontSize = TxCaption, lineHeight = LhCaption,
                        )
                    }
                }
            }
        }

        // Оценка цены
        if (toPoint != null) {
            Card(colors = CardDefaults.cardColors(containerColor = CanonSurface), shape = CanonCardShape) {
                // Три состояния цены сменяются кросс-фейдом и в ОДНОЙ высоте: раньше карточка
                // на каждом пересчёте схлопывалась и раздувалась, и кнопка «Вызвать» прыгала
                // под пальцем. Скелетон вместо спиннера держит форму будущего числа.
                val estPhase = when {
                    estimating -> "calc"
                    errorText != null -> "err"
                    estimate != null -> "ok"
                    else -> "none"
                }
                AnimatedContent(
                    targetState = estPhase,
                    transitionSpec = { fadeIn(tween(240)).togetherWith(fadeOut(tween(140))) },
                    label = "estimatePhase",
                ) { phase ->
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        when (phase) {
                            "calc" -> {
                                SkeletonBox(widthFraction = 0.42f, height = 30.dp, shape = InstantControlShape)
                                SkeletonBox(widthFraction = 0.72f, height = 14.dp, shape = CircleShape)
                                Text(
                                    appText("Считаем цену…", "Хаҡты иҫәпләйбеҙ…"),
                                    color = CanonMuted, fontSize = TxCaption, lineHeight = LhCaption,
                                )
                            }
                            // Ошибка больше не тупик: раньше это была красная строка без единой
                            // кнопки — заказать нельзя, повторить нечем, выход только «назад».
                            "err" -> {
                                Text(
                                    errorText ?: appText("Не удалось оценить цену", "Хаҡты баһалап булманы"),
                                    color = CanonRed, fontSize = TxBody, lineHeight = LhBody,
                                )
                                Text(
                                    appText("Проверь интернет — без цены заказывать нельзя.",
                                        "Интернетты тикшер — хаҡһыҙ заказ итеп булмай."),
                                    color = CanonMuted, fontSize = TxCaption, lineHeight = LhCaption,
                                )
                                Spacer(Modifier.height(2.dp))
                                AppButton(
                                    text = appText("Повторить", "Ҡабатларға"),
                                    onClick = { estimateTick++ },
                                    style = AppButtonStyle.Secondary,
                                    icon = Icons.Default.Search,
                                    height = 48.dp,
                                )
                            }
                            "ok" -> estimate?.let { est ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    // Доминанта экрана: сюда человек и смотрит.
                                    AnimatedContent(targetState = est.price, label = "estPrice") { p ->
                                        Text(
                                            "$p ₽", color = CanonText, fontSize = TxHero, lineHeight = LhHero,
                                            fontWeight = FontWeight.Black,
                                        )
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        appText("примерно", "яҡынса"),
                                        color = CanonMuted, fontSize = TxCaption, lineHeight = LhCaption,
                                    )
                                }
                                val meta = buildList {
                                    if (est.distanceKm > 0) add(appText("≈ ${est.distanceKm.toInt()} км", "≈ ${est.distanceKm.toInt()} км"))
                                    if (est.etaMin > 0) add(appText("≈ ${est.etaMin.toInt()} мин в пути", "≈ ${est.etaMin.toInt()} мин юлда"))
                                    // Время ПОДАЧИ — то, что человек на самом деле хочет знать перед
                                    // заказом. Раньше его не показывали вообще: была только длительность
                                    // поездки, и «когда приедет?» оставалось без ответа.
                                    est.pickupEtaMin?.let { add(appText("машина через ≈$it мин", "машина ≈$it минуттан")) }
                                }.joinToString("  ·  ")
                                if (meta.isNotBlank()) Text(
                                    meta, color = CanonMuted, fontSize = TxCaption, lineHeight = LhCaption,
                                )
                            }
                        }
                    }
                }
            }
        }

        // Детали: как найти пассажира и «заказ для другого». Появляются вместе с маршрутом.
        if (toPoint != null) {
            InstantOrderDetails(
                open = detailsOpen,
                onToggle = { detailsOpen = !detailsOpen },
                comment = comment, onComment = { comment = it.take(300) },
                entrance = entrance, onEntrance = { entrance = it.take(60) },
                forOther = forOther,
                onForOther = {
                    forOther = it
                    if (!it) { forName = ""; forPhone = "" }   // выключили — не шлём чужие ПДн
                },
                forName = forName, onForName = { forName = it.take(120) },
                forPhone = forPhone, onForPhone = { forPhone = it.take(32) },
            )
        }

        // Когда подать машину: «Сейчас» или «На время» (предзаказ). Появляется, когда есть маршрут.
        if (toPoint != null) {
            InstantTimingPicker(
                scheduledAtMs = scheduledAtMs,
                onNow = { scheduledAtMs = null },
                onPickTime = { picked -> scheduledAtMs = picked },
            )
        }

        val scheduled = scheduledAtMs != null
        Spacer(Modifier.height(2.dp))
        Button(
            onClick = {
                val f = effFrom ?: return@Button
                val t = toPoint ?: return@Button
                creating = true; errorText = null
                val fText = fromText.ifBlank { myPosText }
                val tText = toText.ifBlank { mapPointText }
                scope.launch {
                    if (scheduled) {
                        val iso = isoFromMillis(scheduledAtMs!!)
                        ApiClient.scheduleInstantOrder(f.latitude, f.longitude, t.latitude, t.longitude, iso, fText, tText, category)
                            .onSuccess {
                                ApiClient.fireAddRecentPlace(tText, t.latitude, t.longitude)
                                onScheduled(it)
                            }
                            .onFailure { errorText = (it as? ApiException)?.message ?: createFailMsg }
                    } else {
                        ApiClient.createInstantOrder(
                            f.latitude, f.longitude, t.latitude, t.longitude, fText, tText, category,
                            comment = comment, entrance = entrance,
                            forName = if (forOther) forName else "",
                            forPhone = if (forOther) forPhone else "",
                        )
                            .onSuccess {
                                // Наполняем «Недавние» точкой Б (best-effort, на долгоживущем scope — не блокирует заказ).
                                ApiClient.fireAddRecentPlace(tText, t.latitude, t.longitude)
                                onOrderCreated(it)
                            }
                            .onFailure { errorText = (it as? ApiException)?.message ?: createFailMsg }
                    }
                    creating = false
                }
            },
            enabled = effFrom != null && toPoint != null && estimate != null && !creating,
            // heightIn: на крупном системном шрифте фиксированные 54dp срезали надпись с ценой.
            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
            shape = InstantControlShape,
            colors = ButtonDefaults.buttonColors(containerColor = CanonTaxi, contentColor = CanonTaxiInk),   // жёлтый — режим такси
        ) {
            if (creating) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = CanonTaxiInk)
            } else {
                Icon(if (scheduled) Icons.Default.AccessTime else Icons.Default.DirectionsCar, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                val label = when {
                    // Предзаказ «на время»: показываем время подачи.
                    scheduled -> appText("Заказать на ${clockHm(scheduledAtMs!!)}", "${clockHm(scheduledAtMs!!)}-ға заказ итеү")
                    // Глагол-действие: «Вызвать машину» понятнее, чем «Заказать» (эталон Яндекс/inDrive).
                    estimate != null -> appText("Вызвать за ${estimate!!.price} ₽", "${estimate!!.price} ₽-ға саҡырыу")
                    else -> appText("Вызвать машину", "Машина саҡырыу")
                }
                // Цена в кнопке меняется вместе с классом машины — без анимации это выглядело
                // как подмена суммы в последний момент перед нажатием.
                AnimatedContent(targetState = label, label = "orderCta") { text ->
                    Text(
                        text, fontSize = TxBody, lineHeight = LhBody, fontWeight = FontWeight.Black,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    )
                }
            }
        }
        Text(
            if (scheduled) appText("Предзаказ ждёт своего времени. Открой Юлдаш ко времени подачи, чтобы начать поиск. Цену уточним при подаче.",
                "Алдан заказ үҙ ваҡытын көтә. Эҙләүҙе башлар өсөн Юлдашты килеү ваҡытына ас. Хаҡты килгәндә асыҡлайбыҙ.")
            else appText("Оплата водителю напрямую. Телефон водителя откроется после того, как он примет заказ.",
                "Түләү водителгә тура. Водитель заказды алғас, уның телефоны асыла."),
            color = CanonMuted, fontSize = TxCaption, lineHeight = LhCaption)
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

// ------------------------------ Детали заказа: «как найти» и «для другого» ------------------------------
/**
 * Свёрнутый блок с двумя вещами, которых не хватало (аудит 2026-07-26):
 *
 *  1. «Как меня найти» — комментарий и подъезд. В селе «Ленина 12» — пять домов без табличек,
 *     а чат открывается только ПОСЛЕ принятия заказа: водитель наматывал круги вслепую.
 *  2. «Заказ для другого» — сын из Уфы вызывает такси маме в Баймаке. Без этих полей водитель
 *     звонил заказчику в другой город, а мама стояла у ворот и не знала, приехала ли машина.
 *
 * Свёрнуто по умолчанию: обычному заказу лишние поля на главном пути только мешают.
 */
@Composable
private fun InstantOrderDetails(
    open: Boolean,
    onToggle: () -> Unit,
    comment: String, onComment: (String) -> Unit,
    entrance: String, onEntrance: (String) -> Unit,
    forOther: Boolean, onForOther: (Boolean) -> Unit,
    forName: String, onForName: (String) -> Unit,
    forPhone: String, onForPhone: (String) -> Unit,
) {
    // Свёрнуто, но что-то заполнено → показываем короткую сводку, чтобы человек не потерял ввод.
    val filled = comment.isNotBlank() || entrance.isNotBlank() || forOther
    Card(colors = CardDefaults.cardColors(containerColor = CanonSurface), shape = CanonItemShape) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(
                onClick = onToggle,
                color = CanonSurface,
                shape = CanonItemShape,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LocationOn, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(appText("Как меня найти", "Мине нисек табырға"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        if (!open && filled) {
                            Text(
                                listOf(entrance, comment).filter { it.isNotBlank() }.joinToString(" · ")
                                    .ifBlank { appText("Заказ для другого", "Икенсе кеше өсөн") },
                                color = CanonMuted, fontSize = 12.sp, maxLines = 1,
                            )
                        } else if (!open) {
                            Text(
                                appText("Подъезд, ориентир, заказ для другого", "Подъезд, ориентир, икенсе кеше өсөн"),
                                color = CanonMuted, fontSize = 12.sp, maxLines = 1,
                            )
                        }
                    }
                    Text(
                        if (open) appText("Скрыть", "Йәшереү") else appText("Указать", "Күрһәтеү"),
                        color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                    )
                }
            }

            AnimatedVisibility(
                visible = open,
                enter = expandVertically(tween(220)) + fadeIn(tween(220)),
                exit = shrinkVertically(tween(180)) + fadeOut(tween(180)),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = entrance,
                        onValueChange = onEntrance,
                        label = { Text(appText("Подъезд, квартира, этаж", "Подъезд, фатир, ҡат")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    )
                    OutlinedTextField(
                        value = comment,
                        onValueChange = onComment,
                        label = { Text(appText("Комментарий водителю", "Водителгә аңлатма")) },
                        placeholder = { Text(appText("«За магазином, синие ворота»", "«Кибет артында, зәңгәр ҡапҡа»")) },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    )

                    // Заказ для другого — отдельный переключатель, чтобы чужой телефон не улетал случайно.
                    Surface(
                        onClick = { onForOther(!forOther) },
                        color = if (forOther) CanonMint else CanonBg,
                        shape = CanonItemShape,
                        border = BorderStroke(1.dp, if (forOther) CanonGreen2 else CanonBorder),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (forOther) Icons.Default.CheckCircle else Icons.Default.Person,
                                contentDescription = null,
                                tint = if (forOther) CanonGreen2 else CanonMuted,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(appText("Заказ для другого человека", "Икенсе кеше өсөн заказ"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text(
                                    appText("Водитель будет звонить ему, а не тебе", "Водитель һиңә түгел, уға шылтырата"),
                                    color = CanonMuted, fontSize = 12.sp, lineHeight = 16.sp,
                                )
                            }
                        }
                    }
                    AnimatedVisibility(
                        visible = forOther,
                        enter = expandVertically(tween(200)) + fadeIn(tween(200)),
                        exit = shrinkVertically(tween(160)) + fadeOut(tween(160)),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = forName,
                                onValueChange = onForName,
                                label = { Text(appText("Кого везём (имя)", "Кемде илтәбеҙ (исем)")) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                            )
                            OutlinedTextField(
                                value = forPhone,
                                onValueChange = onForPhone,
                                label = { Text(appText("Его телефон", "Уның телефоны")) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                            )
                            Text(
                                appText(
                                    "Телефон увидит только водитель и только после того, как примет заказ.",
                                    "Телефонды тик водитель, тик заказды алғандан һуң күрә.",
                                ),
                                color = CanonMuted, fontSize = 12.sp, lineHeight = 16.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------ Когда подать: «Сейчас» / «На время» ------------------------------
@Composable
private fun InstantTimingPicker(
    scheduledAtMs: Long?,
    onNow: () -> Unit,
    onPickTime: (Long) -> Unit,
) {
    val ctx = LocalContext.current
    val scheduled = scheduledAtMs != null
    Card(colors = CardDefaults.cardColors(containerColor = CanonSurface), shape = CanonItemShape) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(appText("Когда подать машину?", "Машина ҡасан килһен?"), color = CanonText, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TimingChoiceChip(
                    title = appText("Сейчас", "Хәҙер"),
                    selected = !scheduled,
                    onClick = onNow,
                    modifier = Modifier.weight(1f),
                )
                TimingChoiceChip(
                    title = appText("На время", "Ваҡытҡа"),
                    selected = scheduled,
                    onClick = { showDateTimePicker(ctx, scheduledAtMs, onPickTime) },
                    modifier = Modifier.weight(1f),
                )
            }
            AnimatedVisibility(visible = scheduled, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AccessTime, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(scheduledAtMs?.let { fullWhen(it) } ?: "", color = CanonText, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { showDateTimePicker(ctx, scheduledAtMs, onPickTime) }) {
                        Text(appText("Изменить", "Үҙгәртеү"), color = CanonGreen2, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun TimingChoiceChip(title: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        color = if (selected) CanonMint else CanonBg,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.5.dp, if (selected) CanonGreen2 else CanonBorder),
        modifier = modifier.heightIn(min = 48.dp),
    ) {
        Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
            Text(title, color = if (selected) CanonGreen2 else CanonMuted, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
    }
}

// Предзаказ создан → спокойное подтверждение + путь в «Мои предзаказы».
@Composable
private fun InstantScheduledCreatedCard(
    order: InstantOrderDto,
    onOpenScheduled: () -> Unit,
    onNewOrder: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(color = CanonMint, shape = CircleShape) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(18.dp).size(38.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(
            order.scheduledAt?.let { appText("Предзаказ на ${formatDepart(it)} создан", "${formatDepart(it)}-ға алдан заказ булдырылды") }
                ?: appText("Предзаказ создан", "Алдан заказ булдырылды"),
            fontWeight = FontWeight.Black, fontSize = 20.sp, textAlign = TextAlign.Center, color = CanonText,
        )
        Spacer(Modifier.height(8.dp))
        Text("${order.fromText.ifBlank { appText("Точка А", "А нөктәһе") }} → ${order.toText.ifBlank { appText("Точка Б", "Б нөктәһе") }}",
            color = CanonMuted, fontSize = 14.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(appText("Открой Юлдаш ко времени подачи, чтобы начать поиск машины. Мы напомним.",
            "Машина эҙләй башлар өсөн Юлдашты килеү ваҡытына ас. Беҙ иҫкә төшөрөрбөҙ."),
            color = CanonMuted, fontSize = 13.sp, lineHeight = 18.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(22.dp))
        AppButton(text = appText("Мои предзаказы", "Минең алдан заказдар"), onClick = onOpenScheduled, style = AppButtonStyle.Primary)
        Spacer(Modifier.height(8.dp))
        AppButton(text = appText("Новый заказ", "Яңы заказ"), onClick = onNewOrder, style = AppButtonStyle.Secondary)
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onBack) { Text(appText("Готово", "Әҙер"), color = CanonMuted) }
    }
}

// Диалог выбора даты и времени подачи: не в прошлом, ≤ 7 суток.
private fun showDateTimePicker(ctx: Context, initialMs: Long?, onPicked: (Long) -> Unit) {
    val now = java.util.Calendar.getInstance()
    val start = java.util.Calendar.getInstance().apply {
        timeInMillis = initialMs ?: (now.timeInMillis + 30 * 60_000L)   // по умолчанию через ~30 мин
    }
    val maxMs = now.timeInMillis + 7L * 24 * 60 * 60 * 1000   // потолок 7 суток
    android.app.DatePickerDialog(
        ctx,
        { _, year, month, day ->
            android.app.TimePickerDialog(
                ctx,
                { _, hour, minute ->
                    val picked = java.util.Calendar.getInstance().apply {
                        set(java.util.Calendar.YEAR, year); set(java.util.Calendar.MONTH, month)
                        set(java.util.Calendar.DAY_OF_MONTH, day)
                        set(java.util.Calendar.HOUR_OF_DAY, hour); set(java.util.Calendar.MINUTE, minute)
                        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
                    }
                    // Не в прошлом (мин. через 5 мин) и не дальше 7 суток.
                    val floor = System.currentTimeMillis() + 5 * 60_000L
                    val ms = picked.timeInMillis.coerceIn(floor, maxMs)
                    onPicked(ms)
                },
                start.get(java.util.Calendar.HOUR_OF_DAY), start.get(java.util.Calendar.MINUTE), true,
            ).show()
        },
        start.get(java.util.Calendar.YEAR), start.get(java.util.Calendar.MONTH), start.get(java.util.Calendar.DAY_OF_MONTH),
    ).apply {
        datePicker.minDate = now.timeInMillis
        datePicker.maxDate = maxMs
    }.show()
}

// epoch ms → ISO с локальным смещением (однозначно для сервера).
private fun isoFromMillis(ms: Long): String =
    java.time.OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), java.time.ZoneId.systemDefault()).toString()

// epoch ms → «ЧЧ:ММ» (для кнопки).
private fun clockHm(ms: Long): String {
    val c = java.util.Calendar.getInstance().apply { timeInMillis = ms }
    return String.format(java.util.Locale.US, "%02d:%02d", c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
}

// epoch ms → «ДД.ММ, ЧЧ:ММ» (для строки выбранного времени).
private fun fullWhen(ms: Long): String {
    val c = java.util.Calendar.getInstance().apply { timeInMillis = ms }
    return String.format(
        java.util.Locale.US, "%02d.%02d, %02d:%02d",
        c.get(java.util.Calendar.DAY_OF_MONTH), c.get(java.util.Calendar.MONTH) + 1,
        c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE),
    )
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
    // Невыбранная карточка раньше имела рамку цветом CanonSurface — то есть НЕВИДИМУЮ:
    // на белом фоне вторая цена буквально висела в воздухе. Теперь это обычный бордер.
    val border by animateColorAsState(if (selected) CanonGreen2 else CanonBorder, tween(200), label = "clsBorder")
    val bg by animateColorAsState(if (selected) CanonGreen2.copy(alpha = 0.08f) else CanonSurface, tween(200), label = "clsBg")
    // Еле заметный «подъём» выбранной карточки: выбор чувствуется пальцем, а не только глазом.
    val scale by animateFloatAsState(if (selected) 1f else 0.98f, tween(200), label = "clsScale")
    Surface(
        onClick = onClick,
        shape = CanonItemShape,
        color = bg,
        border = BorderStroke(if (selected) 2.dp else 1.dp, border),
        // heightIn: при системном крупном шрифте фиксированные 76dp срезали подпись класса.
        modifier = modifier.heightIn(min = 76.dp).graphicsLayer { scaleX = scale; scaleY = scale },
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title, color = CanonText, fontSize = TxBody, lineHeight = LhBody,
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                if (price != null) {
                    AnimatedContent(targetState = price, label = "clsPrice") { p ->
                        Text(
                            "$p ₽", color = if (selected) CanonGreen2 else CanonText,
                            fontSize = TxBody, lineHeight = LhBody, fontWeight = FontWeight.Black,
                        )
                    }
                } else {
                    // Цена ещё считается: держим её место, иначе карточка прыгает в момент ответа.
                    Box(Modifier.width(46.dp)) { SkeletonBox(height = 14.dp, shape = CircleShape) }
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle, color = CanonMuted, fontSize = TxCaption, lineHeight = LhCaption,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
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
    // Доля бесплатного окна, что ещё осталась. Тикает раз в секунду — тянем плавно, чтобы
    // полоска ползла, а не дёргалась. Стартовое значение = реальный остаток (это обратный
    // отсчёт, а не «рост»: анимировать его с нуля было бы враньём).
    val freeLeftFraction = if (freeSec > 0) ((freeSec - elapsedSec).toFloat() / freeSec).coerceIn(0f, 1f) else 0f
    val freeProgress by animateFloatAsState(freeLeftFraction, tween(1000, easing = LinearEasing), label = "waitFree")
    Surface(shape = CanonItemShape, color = accent.copy(alpha = 0.08f), border = BorderStroke(1.dp, accent.copy(alpha = 0.4f))) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth().heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AccessTime, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                if (isFree) {
                    val left = freeSec - elapsedSec
                    Text(
                        appText("Бесплатное ожидание %d:%02d".format(left / 60, left % 60),
                            "Бушлай көтөү %d:%02d".format(left / 60, left % 60)),
                        color = CanonText, fontSize = TxBody, lineHeight = LhBody, fontWeight = FontWeight.Bold,
                    )
                } else {
                    val paidRub = ((elapsedSec / 60 - order.waitFreeMin).coerceAtLeast(0)) * order.waitFeeRubPerMin
                    Text(
                        appText("Платное ожидание · +${order.waitFeeRubPerMin} ₽/мин" + (if (paidRub > 0) " (уже +$paidRub ₽)" else ""),
                            "Түләүле көтөү · +${order.waitFeeRubPerMin} ₽/мин" + (if (paidRub > 0) " (инде +$paidRub ₽)" else "")),
                        color = CanonText, fontSize = TxBody, lineHeight = LhBody, fontWeight = FontWeight.Bold,
                    )
                }
            }
            // Пока окно бесплатное — видно, СКОЛЬКО его осталось, без чтения цифр.
            if (isFree) {
                LinearProgressIndicator(
                    progress = { freeProgress },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                    color = accent, trackColor = CanonBorder,
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
    // Сколько человек уже смотрит на этот экран. Считаем ЛОКАЛЬНО: времени создания заказа
    // сервер не отдаёт, а «ищем уже 3 минуты» после возврата на экран было бы враньём. Поэтому
    // время меняет только ФОРМУЛИРОВКУ ожидания — без точных цифр, которые нечем подтвердить.
    val startMs = remember(order.id) { System.currentTimeMillis() }
    val now by rememberNowMs()
    val watchedSec = ((now - startMs) / 1000).coerceAtLeast(0)
    val waitStage = when {
        watchedSec < 25L -> 0
        watchedSec < 70L -> 1
        else -> 2
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(120.dp)) {
            Box(Modifier.size((60 + pulse * 56).dp).background(CanonGreen2.copy(alpha = 0.12f * pulse), CircleShape))
            Surface(shape = CircleShape, color = CanonGreen2) {
                Icon(
                    painterResource(R.drawable.yu_map_car),
                    contentDescription = appText("Ищем машину", "Машина эҙләйбеҙ"),
                    tint = CanonBg, modifier = Modifier.padding(20.dp).size(34.dp),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            appText("Ищем машину рядом…", "Яҡында машина эҙләйбеҙ…"),
            color = CanonText, fontSize = TxTitle, lineHeight = LhTitle,
            fontWeight = FontWeight.Black, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "${order.fromText.ifBlank { appText("Точка А", "А нөктәһе") }} → ${order.toText.ifBlank { appText("Точка Б", "Б нөктәһе") }}",
            color = CanonMuted, fontSize = TxBody, lineHeight = LhBody, textAlign = TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            appText("≈ ${order.priceEstimate} ₽ · подбираем ближайшего водителя", "≈ ${order.priceEstimate} ₽ · яҡын водителде табабыҙ"),
            color = CanonMuted, fontSize = TxCaption, lineHeight = LhCaption, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        // Ответ на «сколько ещё ждать». Пустого обещания не даём — по мере ожидания текст
        // честно меняется, и человек видит, что приложение про него не забыло.
        AnimatedContent(
            targetState = waitStage,
            transitionSpec = { fadeIn(tween(260)).togetherWith(fadeOut(tween(160))) },
            label = "searchStage",
        ) { stage ->
            Text(
                when (stage) {
                    0 -> appText("Обычно машина находится за 1–3 минуты",
                        "Ғәҙәттә машина 1–3 минутта табыла")
                    1 -> appText("Ещё ищем — свободных машин рядом сейчас мало",
                        "Әле лә эҙләйбеҙ — яҡында буш машина аҙ")
                    else -> appText("Ищем дольше обычного. Можно подождать — как найдём, сразу сообщим",
                        "Ғәҙәттәгенән оҙағыраҡ эҙләйбеҙ. Көтөргә була — тапҡас, шунда уҡ хәбәр итәбеҙ")
                },
                color = CanonMuted, fontSize = TxCaption, lineHeight = LhCaption, textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(24.dp))
        InstantTripPhaseBar(step = 0, modifier = Modifier.padding(horizontal = 8.dp))
        Spacer(Modifier.height(24.dp))
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.heightIn(min = 48.dp),
            shape = InstantControlShape,
        ) {
            Text(
                appText("Отменить заказ", "Заказды кире алыу"),
                color = CanonRed, fontSize = TxBody, lineHeight = LhBody,
            )
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
    // Шаг для полоски фаз: тот же смысл, что и заголовок, но читается без чтения.
    val phaseStep = when (order.status) {
        "arriving" -> 2
        "onboard" -> 3
        else -> 1
    }
    var confirmPaidCancel by remember { mutableStateOf(false) }
    var showShare by remember(order.id) { mutableStateOf(false) }   // «Поделиться поездкой» (B7b-2)
    val cancelFeeRub = order.cancelFeeNowKop / 100
    // Live-трек машины (B7a-3): пока заказ активен — держим WS такси-заказа и двигаем маркер.
    // Колбэк приходит с потока OkHttp — snapshot-state потокобезопасен. Ушли с экрана → close.
    var carPoint by remember(order.id) { mutableStateOf<Point?>(null) }
    var carBearing by remember(order.id) { mutableStateOf<Double?>(null) }
    DisposableEffect(order.id) {
        val socket = com.yuldash.app.data.InstantLocationSocket(order.id, onPeer = { peer ->
            if (peer.role == "driver") {
                carPoint = Point(peer.lat, peer.lng)
                carBearing = peer.bearing
            }
        }).also { it.connect() }
        onDispose { socket.close() }
    }
    Column(Modifier.fillMaxSize()) {
        InstantRouteMap(
            from = Point(order.fromLat, order.fromLng),
            to = Point(order.toLat, order.toLng),
            modifier = Modifier.fillMaxWidth().weight(1f),
            car = carPoint,
            carBearing = carBearing,
        )
        Card(
            colors = CardDefaults.cardColors(containerColor = CanonSurface),
            shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(18.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // «Где я сейчас» одним взглядом — до того, как человек начнёт читать надписи.
                InstantTripPhaseBar(step = phaseStep)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Navigation, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    // Смена фазы — главное событие этого экрана. Мгновенная подмена надписи
                    // («едет» → «на месте») читалась как сбой; теперь это движение вверх.
                    AnimatedContent(
                        targetState = phaseTitle,
                        transitionSpec = {
                            (fadeIn(tween(280)) + slideInVertically(tween(320)) { it / 3 })
                                .togetherWith(fadeOut(tween(150)))
                        },
                        modifier = Modifier.weight(1f),
                        label = "enroutePhaseTitle",
                    ) { title ->
                        Text(
                            title, color = CanonText, fontSize = TxTitle, lineHeight = LhTitle,
                            fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (order.etaMin > 0 && order.status != "onboard") {
                        Spacer(Modifier.width(8.dp))
                        // «Сколько ещё ждать» — второе по важности число после статуса.
                        // Меняется плавно: скачущие минуты выглядят как ошибка связи.
                        AnimatedContent(targetState = order.etaMin.toInt(), label = "enrouteEta") { m ->
                            Text(
                                appText("≈ $m мин", "≈ $m мин"), color = CanonGreen2,
                                fontSize = TxBody, lineHeight = LhBody, fontWeight = FontWeight.Black,
                            )
                        }
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
                            Text(
                                order.driverName.ifBlank { appText("Водитель", "Водитель") },
                                color = CanonText, fontSize = TxBody, lineHeight = LhBody,
                                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (order.driverVerified) {
                                Spacer(Modifier.width(5.dp))
                                Icon(Icons.Default.Verified, contentDescription = appText("Проверен", "Тикшерелгән"), tint = CanonGreen2, modifier = Modifier.size(16.dp))
                            }
                        }
                        val sub = buildList {
                            if (order.driverRating > 0) add("★ ${String.format(java.util.Locale.US, "%.1f", order.driverRating)}")
                            if (order.driverCar.isNotBlank()) add(order.driverCar)
                        }.joinToString("  ·  ")
                        if (sub.isNotBlank()) Text(
                            sub, color = CanonMuted, fontSize = TxCaption, lineHeight = LhCaption,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        // ГОСНОМЕР — по нему во дворе узнают машину. «Белая Гранта» не помогает,
                        // когда во дворе три белых Гранты; номер отдаётся только после accept.
                        if (order.driverPlate.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Surface(color = CanonTaxiBg, shape = RoundedCornerShape(8.dp)) {
                                Text(
                                    order.driverPlate,
                                    color = CanonTaxiText, fontSize = TxBody, lineHeight = LhBody,
                                    fontWeight = FontWeight.Black,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                    // «Написать» (B7b-1): чат заказа — не звоня, уточнить подъезд/этаж/ориентир.
                    Surface(
                        onClick = { NavSignals.openInstantChat.value = order.id },
                        shape = CircleShape, color = CanonMint,
                    ) {
                        Icon(Icons.Default.ChatBubble, contentDescription = appText("Написать водителю", "Водителгә яҙырға"), tint = CanonGreen2, modifier = Modifier.padding(14.dp).size(20.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    // Телефон — ТОЛЬКО после accept (сервер отдаёт его непустым).
                    if (order.driverPhone.isNotBlank()) {
                        Surface(
                            onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${order.driverPhone}"))) } },
                            shape = CircleShape, color = CanonGreen2,
                        ) {
                            Icon(Icons.Default.Phone, contentDescription = appText("Позвонить водителю", "Водителгә шылтыратыу"), tint = CanonBg, modifier = Modifier.padding(14.dp).size(20.dp))
                        }
                    }
                }
                // «Я на месте» → живой таймер ожидания (бесплатное окно → платно).
                if (order.status == "arriving") {
                    InstantWaitingRow(order)
                }
                // Безопасность (B7b-2): SOS + «Поделиться поездкой с близким» — всю активную поездку.
                InstantSafetyRow(orderId = order.id, onShare = { showShare = true })
                if (order.status != "onboard") {
                    OutlinedButton(
                        // Поздняя отмена платная (подача) — честно предупреждаем ДО тапа.
                        onClick = { if (order.cancelFeeNowKop > 0) confirmPaidCancel = true else onCancel() },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = InstantControlShape,
                    ) {
                        Text(
                            if (order.cancelFeeNowKop > 0)
                                appText("Отменить · $cancelFeeRub ₽", "Кире алыу · $cancelFeeRub ₽")
                            else appText("Отменить заказ", "Заказды кире алыу"),
                            color = CanonRed, fontSize = TxBody, lineHeight = LhBody,
                        )
                    }
                }
            }
        }
    }
    if (showShare) {
        InstantShareDialog(orderId = order.id, onDismiss = { showShare = false })
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

// ------------------------------ Безопасность в поездке (B7b-2) ------------------------------
/** SOS + «Поделиться поездкой»: доверие = продукт (§8). Обе кнопки ≥48dp, спокойные цвета. */
@Composable
private fun InstantSafetyRow(orderId: Int, onShare: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(
            onClick = { NavSignals.openSosForOrder.value = orderId },
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            shape = InstantControlShape,
            border = BorderStroke(1.dp, CanonRed.copy(alpha = 0.5f)),
        ) {
            Icon(
                Icons.Default.Sos,
                contentDescription = appText("Экстренная помощь", "Ашығыс ярҙам"),
                tint = CanonRed, modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                appText("SOS", "SOS"), color = CanonRed,
                fontSize = TxBody, lineHeight = LhBody, fontWeight = FontWeight.Black,
            )
        }
        if (onShare != null) {
            OutlinedButton(
                onClick = onShare,
                modifier = Modifier.weight(1.6f).heightIn(min = 48.dp),
                shape = InstantControlShape,
            ) {
                Icon(
                    Icons.Default.IosShare,
                    contentDescription = appText("Поделиться поездкой с близким", "Сәфәр менән яҡыныңа бүлешеү"),
                    tint = CanonGreen2, modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    appText("Поделиться поездкой", "Сәфәр менән бүлешеү"), color = CanonGreen2,
                    fontSize = TxBody, lineHeight = LhBody, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    // Зимний протокол для такси: мягче SOS, но реальный. Раньше работал только для попуток,
    // хотя четыре часа трассы Сибай–Уфа зимой — это как раз такси (аудит 2026-07-26).
    InstantRoadsideButton(orderId)
}

/**
 * «Застряли на трассе» — координаты уходят доверенным контактам и в ленту админа.
 * Не паника, а честная просьба о помощи: между SOS и «всё нормально» была пустота.
 */
@Composable
private fun InstantRoadsideButton(orderId: Int) {
    val scope = rememberCoroutineScope()
    var confirm by remember(orderId) { mutableStateOf(false) }
    var busy by remember(orderId) { mutableStateOf(false) }
    var sent by remember(orderId) { mutableStateOf(false) }

    if (sent) {
        Surface(color = CanonWarnBg, shape = CanonItemShape) {
            Text(
                appText(
                    "Помощь вызвана: близкие и поддержка получили твои координаты.",
                    "Ярҙам саҡырылды: яҡындар һәм ярҙам хеҙмәте координаталарыңды алды.",
                ),
                color = CanonWarn, fontSize = 13.sp, lineHeight = 18.sp,
                modifier = Modifier.fillMaxWidth().padding(14.dp),
            )
        }
        return
    }
    TextButton(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth()) {
        Text(appText("Застряли на трассе — нужна помощь", "Юлда ҡалдыҡ — ярҙам кәрәк"),
            color = CanonWarn, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { if (!busy) confirm = false },
            containerColor = CanonSurface,
            title = { Text(appText("Позвать помощь?", "Ярҙам саҡырырғамы?"), color = CanonText, fontWeight = FontWeight.Black) },
            text = {
                Text(
                    appText(
                        "Твоим доверенным контактам уйдёт SMS с координатами, а поддержка Юлдаша увидит сигнал. Если угрожает опасность — звони 112.",
                        "Ышаныслы контакттарыңа координаталар менән SMS китә, Юлдаш ярҙамы сигналды күрә. Хәүеф янаһа — 112-гә шылтырат.",
                    ),
                    color = CanonMuted, fontSize = 13.sp, lineHeight = 18.sp,
                )
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        ApiClient.instantRoadsideHelp(orderId, LocationPrefs.lastLat, LocationPrefs.lastLng)
                            .onSuccess { sent = true; confirm = false }
                        busy = false
                    }
                }) { Text(appText("Позвать помощь", "Ярҙам саҡырыу"), color = CanonWarn, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { confirm = false }) {
                    Text(appText("Отмена", "Кире алыу"), color = CanonMuted)
                }
            },
        )
    }
}

/** Выбор близкого для шаринга такси-заказа: близкий получит SMS со ссылкой live-поездки (B7c),
 *  а пассажиру тут же показываем ссылку — скопировать или отправить самому (share-sheet).
 *  Состояния честные: загрузка / пусто (подсказка добавить контакт) / список / ошибка / ссылка. */
@Composable
private fun InstantShareDialog(orderId: Int, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var contacts by remember { mutableStateOf<List<com.yuldash.app.data.ContactDto>?>(null) }
    var loadError by remember { mutableStateOf(false) }
    var liveLink by remember { mutableStateOf<String?>(null) }   // ссылка после share (B7c)
    // Приватность: активные ссылки этого заказа (сервер отдаёт GET shares) + отозвать.
    var activeShares by remember { mutableStateOf<List<com.yuldash.app.data.TripShareDto>>(emptyList()) }
    val sharedMsg = appText("Близкий получит SMS о поездке", "Яҡын кеше сәфәр тураһында SMS алыр")
    val shareFailMsg = appText("Не получилось. Повтори.", "Булманы. Ҡабатла.")
    val revokedMsg = appText("Ссылка отозвана", "Һылтанма кире алынды")
    LaunchedEffect(Unit) {
        ApiClient.getContacts()
            .onSuccess { contacts = it }
            .onFailure { loadError = true; contacts = emptyList() }
        ApiClient.getInstantShares(orderId).onSuccess { activeShares = it }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CanonSurface,
        title = {
            Text(
                if (liveLink != null) appText("Ссылка для близкого", "Яҡын кеше өсөн һылтанма")
                else appText("Поделиться поездкой", "Сәфәр менән бүлешеү"),
                color = CanonText, fontWeight = FontWeight.Bold,
            )
        },
        text = {
            val list = contacts
            val link = liveLink
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    link != null -> LiveLinkCard(link)
                    list == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = CanonGreen2)
                        Spacer(Modifier.width(10.dp))
                        Text(appText("Загружаем близких…", "Яҡындарҙы йөкләйбеҙ…"), color = CanonMuted, fontSize = 14.sp)
                    }
                    loadError -> Text(appText("Не удалось загрузить контакты. Проверь сеть и попробуй ещё раз.",
                        "Контакттарҙы йөкләп булманы. Селтәрҙе тикшереп ҡабат ҡара."), color = CanonMuted, fontSize = 14.sp)
                    list.isEmpty() -> Text(appText("Добавь близкого в «Доверенные контакты» в профиле — и делись поездкой в одно касание.",
                        "Профилдә «Ышаныслы кешеләр»гә яҡыныңды өҫтә — сәфәр менән бер баҫыуҙа бүлеш."), color = CanonMuted, fontSize = 14.sp, lineHeight = 19.sp)
                    else -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        list.forEach { c ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        scope.launch {
                                            ApiClient.shareInstantTrip(orderId, c.id)
                                                .onSuccess { share ->
                                                    Toast.makeText(ctx, "$sharedMsg: ${c.name}", Toast.LENGTH_SHORT).show()
                                                    if (share != null) {
                                                        activeShares = activeShares.filterNot { it.id == share.id } + share
                                                        if (!share.link.isNullOrBlank()) liveLink = share.link
                                                    } else onDismiss()
                                                }
                                                .onFailure {
                                                    onDismiss()
                                                    Toast.makeText(ctx, shareFailMsg, Toast.LENGTH_SHORT).show()
                                                }
                                        }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Default.Person, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(c.name, color = CanonText, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                    if (c.relation.isNotBlank()) Text(c.relation, color = CanonMuted, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
                // Приватность (B7c): активные ссылки заказа + «Отозвать» (сгорит /t/{token}, SMS-статусы стоп).
                if (activeShares.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(appText("Активные ссылки", "Әүҙем һылтанмалар"), color = CanonMuted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    activeShares.forEach { share ->
                        val name = contacts?.firstOrNull { it.id == share.contactId }?.name
                            ?: appText("Близкий", "Яҡын кеше")
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Person, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(name, color = CanonText, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1)
                            TextButton(onClick = {
                                scope.launch {
                                    ApiClient.revokeInstantShare(orderId, share.id)
                                        .onSuccess {
                                            activeShares = activeShares.filterNot { it.id == share.id }
                                            Toast.makeText(ctx, revokedMsg, Toast.LENGTH_SHORT).show()
                                        }
                                        .onFailure { Toast.makeText(ctx, shareFailMsg, Toast.LENGTH_SHORT).show() }
                                }
                            }, modifier = Modifier.heightIn(min = 44.dp)) {
                                Text(appText("Отозвать", "Кире алыу"), color = CanonRed, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (liveLink != null) TextButton(onClick = { liveLink = null }) {
                Text(appText("Поделиться ещё", "Йәнә бүлешеү"), color = CanonGreen2, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(appText("Закрыть", "Ябыу"), color = CanonMuted) }
        },
    )
}

// ------------------------------ «Рядом никого» (expired) ------------------------------
/**
 * Свободных водителей нет. Раньше это был тупик: отказ приходил за две секунды и всё, повтора
 * поиска не существовало. В райцентре ночью на линии 2–3 водителя и оба заняты — это норма, а не
 * исключение: человек получал отказ и уходил к конкуренту (аудит 2026-07-26).
 *
 * Теперь главное действие — «Подожду машину»: заказ встаёт в очередь, фоновый воркер сам
 * перезапускает поиск и пришлёт пуш, как только машина найдётся. Ручной повтор остаётся рядом.
 */
@Composable
private fun InstantNoDriversCard(
    order: InstantOrderDto,
    onRetry: () -> Unit,
    onDone: () -> Unit,
    onOrderUpdated: (InstantOrderDto) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var busy by remember(order.id) { mutableStateOf(false) }
    // Сервер уже мог поставить заказ в очередь (вернулись на экран) — тогда сразу «ждём».
    var waitMinutes by remember(order.id) { mutableIntStateOf(if (order.waitUntil.isNullOrBlank()) 0 else -1) }
    var err by remember(order.id) { mutableStateOf<String?>(null) }
    val waiting = waitMinutes != 0
    val errFallback = appText("Не получилось. Проверь сеть и повтори.", "Булманы. Селтәрҙе тикшереп ҡабатла.")

    InstantFinalCard(
        // Два РАЗНЫХ состояния, и выглядеть они обязаны по-разному. «Рядом никого» — тупик,
        // из которого нужен выход (красное такси). «Ищем дальше» — работа идёт, человек может
        // убрать телефон в карман (зелёные часы + живая полоска ниже).
        icon = if (waiting) Icons.Default.AccessTime else Icons.Default.LocalTaxi,
        tone = if (waiting) InstantTone.Good else InstantTone.Bad,
        title = if (waiting) appText("Ищем машину дальше", "Машинаны эҙләүҙе дауам итәбеҙ")
        else appText("Рядом пока никого", "Яҡында әлегә бер кем дә юҡ"),
        subtitle = when {
            // waitMinutes = -1 → пришли на экран с уже поставленной очередью, точный срок не знаем.
            waiting && waitMinutes > 0 -> appText(
                "Будем искать ещё $waitMinutes минут. Как машина найдётся — сразу пришлём уведомление, приложение можно закрыть.",
                "Тағы $waitMinutes минут эҙләйбеҙ. Машина табылыу менән хәбәр итәбеҙ, ҡулланманы ябырға була.",
            )
            waiting -> appText(
                "Поиск продолжается. Как машина найдётся — сразу пришлём уведомление, приложение можно закрыть.",
                "Эҙләү дауам итә. Машина табылыу менән хәбәр итәбеҙ, ҡулланманы ябырға була.",
            )
            else -> appText(
                "Свободных водителей рядом не нашли. Можем подождать — как только кто-то освободится, пришлём уведомление.",
                "Яҡында буш водитель табылманы. Көтә алабыҙ — берәйһе бушаныу менән хәбәр итәбеҙ.",
            )
        },
        action = if (waiting) appText("Заказать заново", "Яңынан заказ итеү")
        else appText("Попробовать ещё раз", "Ҡабат итеп ҡарау"),
        onAction = onRetry,
        onSecondary = onDone,
        extra = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Очередь ожидания: живая полоска — единственное доказательство, что поиск идёт,
                // когда экран статичен, а телефон лежит в кармане.
                if (waiting) InstantQueuePulse()
                if (!waiting) {
                    AppButton(
                        text = appText("Подожду машину", "Машинаны көтәм"),
                        onClick = {
                            if (busy) return@AppButton
                            busy = true; err = null
                            scope.launch {
                                ApiClient.waitForDriver(order.id)
                                    .onSuccess { w ->
                                        waitMinutes = if (w.waitMinutes > 0) w.waitMinutes else -1
                                        w.order?.let(onOrderUpdated)
                                    }
                                    .onFailure { err = (it as? ApiException)?.message ?: errFallback }
                                busy = false
                            }
                        },
                        icon = Icons.Default.AccessTime,
                        loading = busy,
                    )
                }
                err?.let {
                    Text(
                        it, color = CanonRed, fontSize = TxCaption, lineHeight = LhCaption,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
    )
}

/**
 * «Мы всё ещё ищем» для очереди ожидания. Неопределённая полоска — честный сигнал: срок
 * неизвестен, но работа идёт. Показываем ТОЛЬКО в очереди: в состоянии «рядом никого» такая
 * же полоска врала бы, что поиск продолжается.
 */
@Composable
private fun InstantQueuePulse() {
    Surface(shape = CanonItemShape, color = CanonMint, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                appText("Поиск идёт прямо сейчас", "Эҙләү нәҡ хәҙер бара"),
                color = CanonGreen2, fontSize = TxCaption, lineHeight = LhCaption,
                fontWeight = FontWeight.Black,
            )
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                color = CanonGreen2, trackColor = CanonBorder,
            )
        }
    }
}

/**
 * Ссылка «Чек за поездку» в финальной карточке заказа. Открывает [TaxiReceiptScreen] через
 * [NavSignals] — карточка живёт глубоко в экране такси (в т.ч. встроенном в главную),
 * тянуть колбэк через все слои ради одной кнопки не стоит.
 */
/**
 * «Открыть разбор» по завершённому такси-заказу. Отличается от жалобы: жалоба анонимна и
 * односторонняя, а разбор двусторонний — вторую сторону позовут объясниться, и решение
 * объяснят обоим. До этого раунда спор по такси был технически невозможен: публичная ручка
 * принимала только бронь попутки (аудит 2026-07-26).
 */
@Composable
private fun TaxiDisputeLink(order: InstantOrderDto, isDriver: Boolean) {
    var open by remember(order.id) { mutableStateOf(false) }
    var filed by remember(order.id) { mutableStateOf(false) }
    // Кому предъявляем: пассажир — водителю, водитель — пассажиру. Нет второй стороны → нечего разбирать.
    val respondentId = if (isDriver) order.passengerId else order.driverId
    val respondentName = if (isDriver) order.passengerName.ifBlank { appText("пассажира", "юлаусыны") }
    else order.driverName.ifBlank { appText("водителя", "водителде") }
    if (respondentId == null || respondentId <= 0) return

    if (filed) {
        Surface(color = CanonMint, shape = CanonItemShape) {
            Text(
                appText(
                    "Разбор открыт. Мы позовём вторую сторону объясниться и напишем решение вам обоим.",
                    "Ҡарау асылды. Икенсе яҡты аңлатырға саҡырабыҙ һәм ҡарарҙы икегеҙгә лә яҙабыҙ.",
                ),
                color = CanonGreen2, fontSize = 13.sp, lineHeight = 18.sp,
                modifier = Modifier.fillMaxWidth().padding(14.dp),
            )
        }
        return
    }
    TextButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
        Text(appText("Открыть разбор", "Ҡарауҙы асыу"), color = CanonMuted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
    if (open) {
        FileIncidentDialog(
            respondentId = respondentId,
            respondentName = respondentName,
            orderId = order.id,
            onDismiss = { open = false },
            onFiled = { open = false; filed = true },
        )
    }
}

@Composable
private fun TaxiReceiptLink(orderId: Int) {
    OutlinedButton(
        onClick = { NavSignals.openTaxiReceipt.value = orderId },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = InstantControlShape,
    ) {
        Icon(
            Icons.Default.IosShare,
            contentDescription = appText("Открыть чек за поездку", "Сәфәр чеген асыу"),
            tint = CanonGreen2, modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            appText("Чек за поездку", "Сәфәр чегы"), color = CanonGreen2,
            fontSize = TxBody, lineHeight = LhBody, fontWeight = FontWeight.Bold,
            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        )
    }
}

// ------------------------------ Общая финальная карточка ------------------------------
@Composable
private fun InstantFinalCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    // Крупное число итога — цена завершённой поездки. Человек открывает этот экран, чтобы
    // проверить «сколько», а не перечитать адреса, поэтому сумма стоит выше маршрута и
    // доминирует над ним. null — карточка без суммы (отмена, «заказ не найден»).
    hero: String? = null,
    subtitle: String,
    action: String,
    onAction: () -> Unit,
    onSecondary: () -> Unit,
    // Цвет кружка с иконкой. Good — зелёный (успех, ожидание), Bad — красный (отмена, «не найден»).
    tone: InstantTone = InstantTone.Good,
    extra: (@Composable () -> Unit)? = null,   // §9: блок оценки/жалобы после done
) {
    val accent = if (tone == InstantTone.Bad) CanonRed else CanonGreen2
    val accentBg = if (tone == InstantTone.Bad) CanonDangerBg else CanonMint
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(shape = CircleShape, color = accentBg, modifier = Modifier.appearIn(0)) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.padding(22.dp).size(40.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text(
            title, color = CanonText, fontSize = TxTitle, lineHeight = LhTitle,
            fontWeight = FontWeight.Black, textAlign = TextAlign.Center,
            modifier = Modifier.appearIn(1),
        )
        if (hero != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                hero, color = accent, fontSize = TxHero, lineHeight = LhHero,
                fontWeight = FontWeight.Black, textAlign = TextAlign.Center,
                modifier = Modifier.appearIn(2),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            subtitle, color = CanonMuted, fontSize = TxBody, lineHeight = LhBody,
            textAlign = TextAlign.Center, modifier = Modifier.appearIn(3),
        )
        if (extra != null) {
            Spacer(Modifier.height(18.dp))
            Column(Modifier.fillMaxWidth().appearIn(4)) { extra() }
        }
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onAction,
            // heightIn, а не height: при системном крупном шрифте фиксированная высота срезает надпись.
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).appearIn(5),
            shape = InstantControlShape,
            colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2),
        ) {
            Text(action, fontSize = TxBody, lineHeight = LhBody, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onSecondary, modifier = Modifier.heightIn(min = 48.dp).appearIn(6)) {
            Text(appText("Закрыть", "Ябыу"), color = CanonMuted, fontSize = TxBody, lineHeight = LhBody)
        }
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
                    val starCd = starsText(n)
                    // Тач-цель ≥48dp (иконка визуально 40dp внутри), анимация масштаба сохранена.
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clickable {
                                stars = n
                                scope.launch {
                                    ApiClient.rateInstantOrder(order.id, n)
                                        .onSuccess { if (!rated) { rated = true; Toast.makeText(ctx, thanksMsg, Toast.LENGTH_SHORT).show() } }
                                        .onFailure { Toast.makeText(ctx, rateFail, Toast.LENGTH_SHORT).show() }
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Star,
                            contentDescription = starCd,
                            tint = if (filled) CanonStar else CanonMuted,
                            modifier = Modifier.size(40.dp).graphicsLayer { scaleX = scale; scaleY = scale },
                        )
                    }
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

/**
 * B8-8: мягкий баннер пассажиру после отмены, когда телефон/чат уже открывались
 * («увод мимо приложения»). Не обвиняем — по-добрососедски напоминаем про защиту и SOS.
 */
@Composable
internal fun ContactCancelSoftBanner(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().background(CanonMint, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            appText(
                "Договорились ехать? Заверши поездку в приложении — так работает защита и SOS 💚",
                "Барырға һөйләштегеҙме? Сәфәрҙе ҡушымтала тамамла — шулай яҡлау һәм SOS эшләй 💚",
            ),
            color = CanonGreen2, fontSize = 13.sp, lineHeight = 17.sp,
        )
    }
}


/**
 * B8-7: «Пассажир не заплатил» — одним тапом на экране завершённой поездки (такси и попутка).
 * Создаёт жалобу категории unpaid (сервер: только водитель, только done, дедуп — одна на
 * поездку) → пассажиру страйк по механике §5/§9 + пометка на заказе. Повторный тап безопасен.
 */
@Composable
internal fun UnpaidReportButton(orderId: Int? = null, bookingId: Int? = null, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var sent by remember(orderId, bookingId) { mutableStateOf(false) }
    var sending by remember(orderId, bookingId) { mutableStateOf(false) }
    val failMsg = appText("Не получилось отметить. Проверь сеть.", "Билдәләп булманы. Селтәрҙе тикшер.")
    if (sent) {
        Row(
            modifier = modifier.fillMaxWidth().background(CanonMint, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                appText("Отмечено: пассажир не заплатил. Мы разберёмся.",
                        "Билдәләнде: пассажир түләмәгән. Беҙ тикшерербеҙ."),
                color = CanonGreen2, fontSize = 13.sp, lineHeight = 17.sp,
            )
        }
    } else {
        OutlinedButton(
            onClick = {
                if (sending) return@OutlinedButton
                sending = true
                scope.launch {
                    ApiClient.reportUser(category = "unpaid", orderId = orderId, bookingId = bookingId)
                        .onSuccess { sent = true }
                        .onFailure { Toast.makeText(ctx, failMsg, Toast.LENGTH_SHORT).show() }
                    sending = false
                }
            },
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, CanonRed),
            modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(appText("Пассажир не заплатил", "Пассажир түләмәне"), color = CanonRed, fontWeight = FontWeight.Bold)
        }
    }
}


// Водитель отмечает неявку пассажира (no-show). Само-содержащая кнопка (как UnpaidReportButton):
// дёргает /bookings/{id}/no-show, при успехе показывает подтверждение на месте. Места возвращаются на сервере.
@Composable
internal fun NoShowButton(bookingId: Int, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var sent by remember(bookingId) { mutableStateOf(false) }
    var sending by remember(bookingId) { mutableStateOf(false) }
    val failMsg = appText("Не получилось отметить. Проверь сеть.", "Билдәләп булманы. Селтәрҙе тикшер.")
    if (sent) {
        Row(
            modifier = modifier.fillMaxWidth().background(CanonMint, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                appText("Отмечена неявка. Место вернулось в поездку.",
                        "Килмәгәнлек билдәләнде. Урын сәфәргә ҡайтты."),
                color = CanonGreen2, fontSize = 13.sp, lineHeight = 17.sp,
            )
        }
    } else {
        OutlinedButton(
            onClick = {
                if (sending) return@OutlinedButton
                sending = true
                scope.launch {
                    ApiClient.markNoShow(bookingId)
                        .onSuccess { sent = true }
                        .onFailure { Toast.makeText(ctx, failMsg, Toast.LENGTH_SHORT).show() }
                    sending = false
                }
            },
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, CanonRed),
            modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(appText("Пассажир не явился", "Пассажир килмәне"), color = CanonRed, fontWeight = FontWeight.Bold)
        }
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
    val ctx = LocalContext.current
    val myPoint by rememberMyPoint(active = online)
    var offer by remember { mutableStateOf<InstantOrderDto?>(null) }
    var accepting by remember { mutableStateOf(false) }
    var presenceFails by remember { mutableIntStateOf(0) }   // подряд-неудачи heartbeat → «нет связи»
    val acceptTakenMsg = appText("Заказ уже взял другой водитель", "Заказды башҡа водитель алды")
    val acceptNetMsg = appText("Не удалось взять заказ. Проверь связь и попробуй снова.", "Заказды алып булманы. Бәйләнеште тикшереп ҡабатла.")

    // Presence-heartbeat (координаты не логируем). Следим за связью: если heartbeat не долетает,
    // водитель невидим серверу — честно показываем это чипом, а не делаем вид, что он «на линии».
    LaunchedEffect(online) {
        if (!online) { presenceFails = 0; return@LaunchedEffect }
        while (isActive) {
            myPoint?.let {
                ApiClient.instantPresence(it.latitude, it.longitude)
                    .onSuccess { presenceFails = 0 }
                    .onFailure { presenceFails = (presenceFails + 1).coerceAtMost(99) }
            }
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
            accepting = accepting,
            onAccept = {
                if (accepting) return@InstantOfferOverlay
                accepting = true
                scope.launch {
                    ApiClient.instantAccept(current.id)
                        .onSuccess { offer = null; onOpenTrip(it.id) }
                        .onFailure { e ->
                            // 409 (гонку проиграли/оффер протух) — заказ ушёл, закрываем и ждём следующий.
                            // Сеть/5xx — не молчим: говорим, что не взяли, оффер оставляем на повтор.
                            val st = (e as? ApiException)?.status
                            if (st == 409 || st == 410) {
                                Toast.makeText(ctx, acceptTakenMsg, Toast.LENGTH_SHORT).show(); offer = null
                            } else {
                                Toast.makeText(ctx, acceptNetMsg, Toast.LENGTH_SHORT).show()
                            }
                        }
                    accepting = false
                }
            },
            onDecline = {
                if (accepting) return@InstantOfferOverlay
                scope.launch { ApiClient.instantDecline(current.id) }
                offer = null
            },
        )
    }

    // Связь потеряна, пока «на линии» и нет оффера на экране: мягкий чип «нет связи».
    // Не молчим — иначе водитель ждёт заказы, а сервер его не видит. Восстановится сам.
    AnimatedVisibility(
        visible = online && current == null && presenceFails >= 2,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            Surface(shape = CircleShape, color = CanonGold.copy(alpha = 0.16f)) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Default.CloudOff, contentDescription = null, tint = CanonGold, modifier = Modifier.size(16.dp))
                    Text(
                        appText("Нет связи — переподключаемся…", "Бәйләнеш юҡ — ҡабат тоташабыҙ…"),
                        color = CanonText, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

// ------------------------------ Полноэкранный входящий оффер ------------------------------
@Composable
private fun InstantOfferOverlay(order: InstantOrderDto, accepting: Boolean = false, onAccept: () -> Unit, onDecline: () -> Unit) {
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
                    // Плавная смена цифры таймера (в такт анимированной полоске), без рывка.
                    AnimatedContent(targetState = secondsLeft, label = "offerTimer") { s ->
                        Text("$s", color = CanonGreen2, fontSize = 22.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                    }
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
                            Surface(shape = RoundedCornerShape(10.dp), color = CanonMint) {
                                Text(appText("Комфорт", "Комфорт"), color = CanonGreen2, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
                            }
                        }
                    }
                    InstantPointRow(Icons.Default.MyLocation, appText("Подача", "Килеп алыу"), order.fromText.ifBlank { appText("Точка А", "А нөктәһе") })
                    InstantPointRow(Icons.Default.LocationOn, appText("Назначение", "Барыр урын"), order.toText.ifBlank { appText("Точка Б", "Б нөктәһе") })
                    // Пассажир (B7a-4): рейтинг + опыт — водитель решает по данным; новичок — честно.
                    // Агрегат анонимен; имя/телефон откроются только после «Взять заказ».
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Person, contentDescription = null, tint = CanonMuted, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        val rating = order.passengerRating
                        Text(
                            if (rating != null) {
                                val stars = String.format(java.util.Locale.US, "%.1f", rating)
                                appText("Пассажир: ★ $stars · ${order.passengerTrips} поездок",
                                    "Пассажир: ★ $stars · ${order.passengerTrips} сәфәр")
                            } else appText("Пассажир: новичок 🌱", "Пассажир: яңы юлсы 🌱"),
                            color = CanonText, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        )
                    }
                    val meta = buildList {
                        if (order.distanceKm > 0) add(appText("≈ ${order.distanceKm.toInt()} км поездка", "≈ ${order.distanceKm.toInt()} км сәфәр"))
                        if (order.etaMin > 0) add(appText("≈ ${order.etaMin.toInt()} мин", "≈ ${order.etaMin.toInt()} мин"))
                    }.joinToString("  ·  ")
                    if (meta.isNotBlank()) Text(meta, color = CanonMuted, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.weight(1f))
            AppButton(
                text = appText("Взять заказ", "Заказды алыу"),
                onClick = onAccept,
                modifier = Modifier.navigationBarsPadding(),
                style = AppButtonStyle.Primary,
                icon = Icons.Default.CheckCircle,
                loading = accepting,
                enabled = !accepting,
                height = 56.dp,
            )
            AppButton(
                text = appText("Пропустить", "Үткәреп ебәреү"),
                onClick = onDecline,
                style = AppButtonStyle.Secondary,
                enabled = !accepting,
                height = 48.dp,
            )
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
    // Первая загрузка упала по СЕТИ (не 404) → показываем «Повторить», а не «Заказ не найден».
    var loadError by remember { mutableStateOf(false) }
    var reloadTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(orderId, reloadTick) {
        loading = true; loadError = false
        ApiClient.getInstantOrder(orderId)
            .onSuccess { order = it }
            .onFailure { e -> loadError = (e as? ApiException)?.status?.let { it >= 500 } ?: true }
        loading = false
        while (isActive) {
            delay(5_000)
            ApiClient.getInstantOrder(orderId).onSuccess { order = it }
            if (order?.status == "done" || order?.status == "cancelled" || order?.status == "expired") break
        }
    }

    // Live-трек (B7a-3): пока заказ активен — шлём свою позицию пассажиру (WS, не чаще ~5с),
    // он видит движущуюся машину. Заказ кончился / ушли с экрана → сокет закрывается.
    val isOrderActive = order?.isActive == true
    val trackSocket = remember { mutableStateOf<com.yuldash.app.data.InstantLocationSocket?>(null) }
    DisposableEffect(orderId, isOrderActive) {
        if (!isOrderActive) return@DisposableEffect onDispose { }
        val s = com.yuldash.app.data.InstantLocationSocket(orderId, onPeer = { }).also { it.connect() }
        trackSocket.value = s
        onDispose { s.close(); trackSocket.value = null }
    }
    val myLivePoint by rememberMyPoint(active = isOrderActive)
    var lastLocSentMs by remember { mutableStateOf(0L) }
    var prevSentPoint by remember { mutableStateOf<Point?>(null) }
    LaunchedEffect(myLivePoint, isOrderActive) {
        val p = myLivePoint ?: return@LaunchedEffect
        if (!isOrderActive) return@LaunchedEffect
        val now = System.currentTimeMillis()
        if (now - lastLocSentMs < 5_000) return@LaunchedEffect
        lastLocSentMs = now
        // Курс из двух последних фиксов (нос стрелки по движению); стоим на месте → без поворота.
        val bearing = prevSentPoint?.let { q ->
            val dLat = p.latitude - q.latitude
            val dLng = p.longitude - q.longitude
            if (kotlin.math.abs(dLat) + kotlin.math.abs(dLng) < 0.00005) null
            else (Math.toDegrees(kotlin.math.atan2(dLng * kotlin.math.cos(Math.toRadians(p.latitude)), dLat)) + 360) % 360
        }
        prevSentPoint = p
        trackSocket.value?.sendLoc(p.latitude, p.longitude, bearing)
    }

    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Поездка", "Сәфәр"), onBack) }) { padding ->
        val current = order
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                loading && current == null -> InstantCenterLoader(appText("Загружаем заказ…", "Заказды йөкләйбеҙ…"))
                // Сеть упала на загрузке — не выдаём за «не найден», даём «Повторить».
                current == null && loadError -> InstantRetryCard(onRetry = { reloadTick++ }, onBack = onBack)
                current == null -> InstantFinalCard(
                    icon = Icons.Default.Close,
                    title = appText("Заказ не найден", "Заказ табылманы"),
                    subtitle = appText("Возможно, он уже завершён или отменён.", "Бәлки, ул тамамланған йәки кире алынған."),
                    action = appText("К заказам", "Заказдарға"), onAction = onBack, onSecondary = onBack,
                    tone = InstantTone.Bad,
                )
                current.status == "done" -> InstantFinalCard(
                    icon = Icons.Default.CheckCircle,
                    title = appText("Поездка завершена", "Сәфәр тамамланды"),
                    subtitle = appText("Получено ${current.priceFinal ?: current.priceEstimate} ₽. Спасибо!", "${current.priceFinal ?: current.priceEstimate} ₽ алынды. Рәхмәт!"),
                    action = appText("Готово", "Әҙер"), onAction = onFinished, onSecondary = onFinished,
                    extra = {
                        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                            InstantReceiptReminder()   // B7b-4: чек самозанятого — мягко, не назидательно
                            InstantRateAndReport(current, isDriver = true)   // §9: оценить/пожаловаться
                            UnpaidReportButton(orderId = current.id)   // B8-7: «пассажир не заплатил» одним тапом
                            // Чек поездки: там же водитель отмечает «наличные получил» и «нашёл вещь».
                            TaxiReceiptLink(current.id)
                            TaxiDisputeLink(current, isDriver = true)
                        }
                    },
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
                    tone = InstantTone.Bad,
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
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(current.passengerName.ifBlank { appText("Пассажир", "Пассажир") }, color = CanonText, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                        // Заказ ДЛЯ ДРУГОГО: везём не заказчика. Имя и телефон выше — уже
                                        // того, кого забираем, но водитель должен это ПОНИМАТЬ заранее.
                                        if (current.forOther) {
                                            Spacer(Modifier.width(6.dp))
                                            Surface(color = CanonTaxiBg, shape = RoundedCornerShape(8.dp)) {
                                                Text(
                                                    appText("заказ для другого", "икенсе кеше өсөн"),
                                                    color = CanonTaxiInk, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                                )
                                            }
                                        }
                                    }
                                    Text("${current.fromText.ifBlank { appText("Точка А", "А нөктәһе") }} → ${current.toText.ifBlank { appText("Точка Б", "Б нөктәһе") }}", color = CanonMuted, fontSize = 13.sp, maxLines = 1)
                                }
                                // «Написать» (B7b-1): чат заказа — водителю удобнее коротким текстом на месте.
                                Surface(onClick = { NavSignals.openInstantChat.value = current.id }, shape = CircleShape, color = CanonMint) {
                                    Icon(Icons.Default.ChatBubble, contentDescription = appText("Написать пассажиру", "Пассажирға яҙырға"), tint = CanonGreen2, modifier = Modifier.padding(14.dp).size(20.dp))
                                }
                                Spacer(Modifier.width(8.dp))
                                if (current.passengerPhone.isNotBlank()) {
                                    Surface(onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${current.passengerPhone}"))) } }, shape = CircleShape, color = CanonGreen2) {
                                        Icon(Icons.Default.Phone, contentDescription = appText("Позвонить пассажиру", "Пассажирға шылтыратыу"), tint = CanonBg, modifier = Modifier.padding(14.dp).size(20.dp))
                                    }
                                }
                            }
                            // «Как меня найти» — комментарий и подъезд от пассажира. В селе адрес
                            // «Ленина 12» — это пять домов без табличек, а чат открывается только
                            // ПОСЛЕ принятия заказа: без этой подсказки водитель наматывал круги.
                            if (current.comment.isNotBlank() || current.entrance.isNotBlank()) {
                                Surface(color = CanonMint, shape = CanonItemShape) {
                                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.LocationOn, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text(appText("Как найти", "Нисек табырға"), color = CanonGreen2, fontWeight = FontWeight.Black, fontSize = 13.sp)
                                        }
                                        if (current.entrance.isNotBlank()) {
                                            Text(current.entrance, color = CanonText, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        }
                                        if (current.comment.isNotBlank()) {
                                            Text(current.comment, color = CanonText, fontSize = 14.sp, lineHeight = 19.sp)
                                        }
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
                            // «Навигатор» (B7a-3): до посадки ведём к подаче (А), после — к назначению (Б).
                            // Яндекс Навигатор → Яндекс Карты → любое geo:-приложение.
                            OutlinedButton(
                                onClick = {
                                    val toDest = current.status == "onboard"
                                    openNavigator(
                                        ctx,
                                        if (toDest) current.toLat else current.fromLat,
                                        if (toDest) current.toLng else current.fromLng,
                                    )
                                },
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                shape = RoundedCornerShape(14.dp),
                            ) {
                                Icon(Icons.Default.Navigation, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (current.status == "onboard") appText("Навигатор · к точке Б", "Навигатор · Б нөктәһенә")
                                    else appText("Навигатор · к пассажиру", "Навигатор · пассажирға"),
                                    color = CanonGreen2, fontWeight = FontWeight.Bold,
                                )
                            }
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
                            // SOS (B7b-2): безопасность водителя — тоже продукт (обе стороны заказа).
                            InstantSafetyRow(orderId = current.id)
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

/** B7b-4: мягкое напоминание самозанятому о чеке после завершённой поездки (не интеграция —
 *  просто тёплая подсказка; пуш-напоминание с дедупом 1/сутки шлёт сервер). */
@Composable
private fun InstantReceiptReminder() {
    Surface(shape = CanonItemShape, color = CanonMint, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("🧾", fontSize = 22.sp)
            Spacer(Modifier.width(10.dp))
            Text(
                appText("Не забудь чек в «Мой налог» — пара касаний, и обязанность самозанятого выполнена 💚",
                    "«Мой налог»да чек бирергә онотма — бер-ике баҫыу, һәм үҙмәшғүл бурысы үтәлде 💚"),
                color = CanonText, fontSize = 13.sp, lineHeight = 18.sp,
            )
        }
    }
}

/** Открыть внешний навигатор к точке (B7a-3): Яндекс Навигатор → Яндекс Карты → любое geo:-приложение.
 *  Ничего не установлено → тихо ничего (кнопка не роняет экран). */
private fun openNavigator(ctx: Context, lat: Double, lng: Double) {
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
