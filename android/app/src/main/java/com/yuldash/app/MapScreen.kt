package com.yuldash.app

// Вкладка «Карта»: MapScreen + Яндекс MapKit (YandexMapCard, MapHero, маркеры-ценники,
// геолокация, контролы). Вынесено из MainActivity (Фаза 3). Импорты целиком — лишние = варнинги.

import com.yuldash.app.R
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.zIndex
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.Woman
import androidx.compose.material.icons.filled.SmokingRooms
import androidx.compose.material.icons.filled.Luggage
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.filled.AddBox
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.AddRoad
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.EventSeat
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.HeadsetMic
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.NearMeDisabled
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneLocked
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Pin
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Sos
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.VolunteerActivism
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import android.Manifest
import android.app.Activity
import android.speech.RecognizerIntent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import java.io.File
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.content.Context
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.alpha
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Typeface
import android.view.MotionEvent
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.viewinterop.AndroidView
import com.yandex.mapkit.MapKitFactory
import com.yandex.mapkit.geometry.Circle
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.geometry.Polyline
import com.yandex.mapkit.map.CameraPosition
import com.yandex.mapkit.Animation
import com.yandex.mapkit.map.CameraListener
import com.yandex.mapkit.map.CameraUpdateReason
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import com.yandex.mapkit.map.IconStyle
import com.yandex.mapkit.map.MapObjectTapListener
import com.yandex.mapkit.mapview.MapView
import com.yandex.mapkit.logo.Alignment as LogoAlignment
import com.yandex.mapkit.logo.HorizontalAlignment as LogoHorizontal
import com.yandex.mapkit.logo.VerticalAlignment as LogoVertical
import com.yandex.runtime.image.ImageProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.ApiException
import com.yuldash.app.data.MessageDto
import com.yuldash.app.data.GeocoderClient
import com.yuldash.app.data.GeoHit
import com.yuldash.app.data.ConversationDto
import com.yuldash.app.data.PopularRouteDto
import com.yuldash.app.data.FeedDto
import com.yuldash.app.data.RequestDto
import com.yuldash.app.data.NotifDto
import com.yuldash.app.data.AdDto
import com.yuldash.app.ui.theme.YuldashTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Цвет линии маршрута на карте MapKit (ARGB): фирменный зелёный Юлдаша с прозрачностью.
private const val ROUTE_STROKE_ARGB: Long = 0xCC0B6B3A

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MapScreen(
    rides: List<Ride>,
    activeTrip: Ride?,
    ads: List<PartnerAd>,
    adStats: Map<String, AdStats>,
    onBookRide: (Ride) -> Unit,
    onShareRide: (Ride) -> Unit,
    onAdImpression: (PartnerAd) -> Unit,
    onAdClick: (PartnerAd) -> Unit,
    onSos: () -> Unit,
    onOpenPopular: (PopularRoute) -> Unit,
    onDriver: () -> Unit,
    onBoost: () -> Unit,
    onClinicRides: () -> Unit,   // F22: раздел «Поездки к клинике»
    onRouteWatch: (String?, String?) -> Unit = { _, _ -> },   // F13 «карауль поездку»: открыть «Мои подписки»
    onSeasonalPublish: (String) -> Unit = {}   // F15: баннер «на праздник» → форма создания поездки (аргумент — дата-шаблон)
) {
    val nearbyAd = ads.forPlacement(AdPlacement.Nearby).firstOrNull { it.city == "Баймаҡ" }
    var selectedRide by remember { mutableStateOf<Ride?>(null) }
    var lastPreview by remember { mutableStateOf<Ride?>(null) }   // держим поездку во время анимации скрытия карточки
    LaunchedEffect(selectedRide) { if (selectedRide != null) lastPreview = selectedRide }
    var adRoute by remember { mutableStateOf<PartnerAd?>(null) }   // «Маршрут» из рекламы → рисуем на нашей карте
    // Ближайшие поездки: маршрут клиента (активная поездка → её маршрут) + сортировка по времени выезда + гео-дистанция.
    var nearby by remember { mutableStateOf<List<com.yuldash.app.data.RideDto>>(emptyList()) }
    var nearbyRequests by remember { mutableStateOf<List<com.yuldash.app.data.RequestNearDto>>(emptyList()) }  // заявки рядом → маркеры на карте
    var nearbyLoading by remember { mutableStateOf(true) }
    var nearbyError by remember { mutableStateOf(false) }   // отличаем «нет сети» от «нет поездок»
    var nearbyReload by remember { mutableStateOf(0) }
    var nearbyTotal by remember { mutableStateOf(0) }       // всего на маршруте (для «Показать ещё»)
    var nearbyLimit by remember { mutableStateOf(NEARBY_PAGE) }  // сколько показываем сейчас
    // F15: сезонное событие для баннера «на праздник». Один запрос; сервер сам считает даты по годам.
    var seasonalEvent by remember { mutableStateOf<com.yuldash.app.data.SeasonalEventDto?>(null) }
    var seasonalDismissed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        ApiClient.getSeasonalEvents().onSuccess { list ->
            // самое релевантное: идущее сейчас, иначе — ближайшее (сервер уже отсортировал по дате начала)
            seasonalEvent = list.firstOrNull { it.active } ?: list.firstOrNull()
        }
    }
    val filterCtx = LocalContext.current
    var prefFilter by remember { mutableStateOf(FilterPrefs.load(filterCtx)) }  // фильтр «Ближайших»: старт из настроек «Фильтры по умолчанию»
    val verifiedOnly = remember { AppPrefs.verifiedOnly(filterCtx) }  // «Только проверенные» из раздела Безопасность
    // F4: «когда едем» — null = все дни, иначе YYYY-MM-DD. Поездки в РБ планируют за 1-3 дня;
    // чипы Сегодня/Завтра режут ленту до нужного дня (фильтрует сервер по depart_at).
    var dateFilter by remember { mutableStateOf<String?>(null) }
    val focusFrom = activeTrip?.from
    val focusTo = activeTrip?.to
    val userLat = LocationPrefs.lastLat   // читаем в локальные val → подписка на изменение позиции
    val userLng = LocationPrefs.lastLng
    // Огрублённый ключ позиции (~1.1 км, 2 знака): перезапрашиваем «рядом» только при заметном
    // смещении, а не на КАЖДЫЙ GPS-фикс (было — REST на каждый фикс, жёг трафик и квоту). Сам запрос
    // уходит с ТОЧНОЙ позицией — грубим только частоту, не точность.
    val userLatKey = userLat?.let { kotlin.math.round(it * 100) }
    val userLngKey = userLng?.let { kotlin.math.round(it * 100) }
    // Сбрасываем страницу при смене маршрута/позиции/дня (новый контекст → снова с начала).
    LaunchedEffect(focusFrom, focusTo, userLatKey, userLngKey, dateFilter) { nearbyLimit = NEARBY_PAGE }
    LaunchedEffect(focusFrom, focusTo, userLatKey, userLngKey, nearbyReload, nearbyLimit, dateFilter) {
        if (nearby.isEmpty()) nearbyLoading = true   // спиннер только когда показывать нечего; авто-обновление с данными — молча, без мигания
        // Радиус применяем только когда знаем позицию (иначе показываем все по маршруту/времени).
        val radius = if (userLat != null && userLng != null) NEARBY_RADIUS_KM else null
        ApiClient.getNearbyRidesPaged(focusFrom, focusTo, userLat, userLng, radius, nearbyLimit, date = dateFilter)
            .onSuccess { nearby = it.items; nearbyTotal = it.total; nearbyError = false }
            .onFailure { nearbyError = true }
        nearbyLoading = false
    }
    // Заявки пассажиров рядом → маркеры на карте (кто ищет попутку). Радиус — когда знаем позицию.
    LaunchedEffect(userLatKey, userLngKey, nearbyReload) {
        val radius = if (userLat != null && userLng != null) NEARBY_RADIUS_KM else null
        ApiClient.getNearbyRequests(userLat, userLng, radius)
            .onSuccess { nearbyRequests = it }
    }
    // Авто-обновление пинов: пока «Карта» открыта и на переднем плане — раз в ~25с тянем свежее (новые
    // поездки/заявки появляются сами, исполненные/уехавшие исчезают). bump nearbyReload → перетягивает ОБА
    // эффекта (поездки + заявки). В фоне — пауза (repeatOnLifecycle RESUMED), не жжём батарею/трафик.
    val nearbyLifecycle = LocalLifecycleOwner.current
    var skipFirstAuto by remember { mutableStateOf(true) }
    LaunchedEffect(nearbyLifecycle) {
        nearbyLifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (skipFirstAuto) skipFirstAuto = false   // первый показ грузят эффекты выше — не дублируем
            else nearbyReload++                         // вернулись из фона на карту → сразу свежие
            while (true) {
                kotlinx.coroutines.delay(25_000)
                nearbyReload++
            }
        }
    }
    // Live-сигнал через WS /ws/map: при изменении (новая поездка/заявка/бронь/отмена) сервер шлёт «refresh» →
    // перетягиваем СРАЗУ, не дожидаясь 25-сек опроса. Дебаунс ~1.5с схлопывает пачку пингов. Закрывается с экраном.
    val mapPingScope = rememberCoroutineScope()
    DisposableEffect(Unit) {
        var lastPing = 0L
        val sock = com.yuldash.app.data.MapFeedSocket(onRefresh = {
            val now = System.currentTimeMillis()
            if (now - lastPing > 1500) { lastPing = now; mapPingScope.launch { nearbyReload++ } }
        }).also { it.connect() }
        onDispose { sock.close() }
    }
    // Клиентская фильтрация «Ближайших» по выбранным условиям (поля уже пришли в RideDto).
    // remember: пересчитываем только при смене списка/фильтра, а не на каждой рекомпозиции экрана.
    val shownNearby = remember(nearby, prefFilter, verifiedOnly) {
        if (prefFilter.isEmpty() && !verifiedOnly) nearby else nearby.filter { d ->
            (!verifiedOnly || d.driverVerified) &&
                ("women" !in prefFilter || d.womenOnly || d.driverIsWoman) &&   // F9: женщины за рулём тоже подходят
                ("child" !in prefFilter || d.childSeat) &&
                ("pets" !in prefFilter || d.petsAllowed) &&
                ("baggage" !in prefFilter || d.baggage) &&
                ("ac" !in prefFilter || d.airConditioner) &&
                ("nosmoke" !in prefFilter || !d.smoking) &&   // некурящий = поездки, где курить нельзя
                // «Тихая поездка» водитель мог отметить с самого начала, а найти её пассажир не мог —
                // условие было только на карточке. Главная жалоба попутчиков (BlaBlaCar, 1449 голосов)
                // не про багаж, а про три часа разговора, от которого некуда деться.
                ("quiet" !in prefFilter || d.quiet)
        }
    }
    // Пины-ценники на карте = те же «Ближайшие» (реальные поездки), макс 20 чтобы не захламлять.
    val mapPins = remember(shownNearby) { shownNearby.take(20).map { it.toUiRide() } }
    // Box-обёртка: Scaffold + не-модальная карточка выбранной поездки поверх (чтобы карта с маршрутом была видна).
    Box(Modifier.fillMaxSize()) {
    // Карта вложена в HomeScreen-Scaffold (он уже даёт отступ под меню и статус-бар).
    // Свой Scaffold НЕ должен добавлять системные инсеты второй раз → contentWindowInsets = 0.
    Scaffold(containerColor = CanonBg, contentWindowInsets = WindowInsets(0, 0, 0, 0)) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            // Закреплённый верх: шапка + карта (НЕ в прокрутке → вертикальный пан двигает карту, а не страницу).
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Spacer(Modifier.height(4.dp))
                Box(Modifier.appearIn(0)) { HomeHeader(onSos = onSos) }
                Spacer(Modifier.height(12.dp))
                Box(Modifier.appearIn(1)) {
                    MapHero(
                        activeTrip = activeTrip,
                        rides = mapPins,
                        onRideTap = { selectedRide = it },
                        onFind = onOpenPopular,
                        onDriver = onDriver,
                        adRoute = adRoute,
                        onClearRoute = { adRoute = null },
                        previewRide = selectedRide,
                        requests = nearbyRequests
                    )
                }
                // Закреплённый зазор кнопки → «Ближайшие поездки»: держится и на скролле
                // (contentPadding ниже «съедается» прокруткой, поэтому воздух ставим тут, в пине).
                Spacer(Modifier.height(12.dp))
            }
            // Прокручиваемый низ: простой режим, ближайшие поездки, реклама.
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(top = 12.dp, bottom = 12.dp)   // низ потеснее (просьба: внизу было много места)
            ) {
                // F15: баннер «на праздник» — только когда близко событие (нет события → пункта нет, без пустой дырки).
                seasonalEvent?.takeIf { !seasonalDismissed }?.let { sev ->
                    item(key = "seasonal") {
                        SeasonalBanner(
                            event = sev,
                            onPublish = { onSeasonalPublish(seasonalRidePrefillDate(sev)) },
                            onDismiss = { seasonalDismissed = true },
                        )
                    }
                }
                // key обязателен: без него LazyColumn переиспользует ячейку по индексу, и состояние
                // анимации появления (appearIn стартует с alpha=0) достаётся чужому содержимому —
                // заголовок остаётся невидимым НАВСЕГДА. Ловится только глазами: в дерево элементов
                // прозрачный узел тоже не попадает, поэтому и тесты, и uiautomator молчат.
                item(key = "nearby_header") {
                    Box(Modifier.appearIn(2)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),   // маленькая пауза заголовок → карточка
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    appText("Ближайшие поездки", "Яҡындағы сәфәрҙәр"),
                                    color = CanonText, style = CanonHeading,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                if (focusFrom != null && focusTo != null) {
                                    Text("$focusFrom → $focusTo", color = CanonGreen2, style = CanonMicro, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            // Счётчик — пилюля с мягкой сменой цифры (фильтры/обновление ленты меняют её на лету).
                            AnimatedVisibility(
                                visible = nearby.isNotEmpty(),
                                enter = fadeIn(tween(CanonMotion.QUICK)),
                                exit = fadeOut(tween(CanonMotion.QUICK))
                            ) {
                                Surface(color = CanonMint, shape = RoundedCornerShape(999.dp)) {
                                    AnimatedContent(
                                        targetState = shownNearby.size,
                                        transitionSpec = { fadeIn(tween(CanonMotion.QUICK)) togetherWith fadeOut(tween(CanonMotion.QUICK)) },
                                        label = "nearbyCount"
                                    ) { count ->
                                        Text(
                                            appText("$count рядом", "$count яҡында"),
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                            color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                item(key = "nearby_filters") {
                    // УСЛОВИЕ ПОКАЗА (2026-08-03, найдено независимой проверкой). Раньше здесь стояло
                    // просто `nearby.isNotEmpty()` — и это был тупик: выбрал «Сегодня», на сегодня
                    // поездок нет → выдача пуста → строка фильтров исчезает ВМЕСТЕ с кнопкой «Все дни»,
                    // и снять фильтр нечем (в пустой заглушке только «Обновить», а он повторяет тот же
                    // запрос). То же с «Детским креслом» и любым другим условием.
                    // Поэтому: фильтры видно, пока есть ЧТО фильтровать ИЛИ пока хоть один фильтр
                    // включён. Второе слагаемое — и есть путь назад. Когда фильтров нет и поездок нет,
                    // ряд прячем: пустой экран не должен начинаться с восьми неработающих кнопок.
                    if (nearby.isNotEmpty() || dateFilter != null || prefFilter.isNotEmpty()) {
                      // Тач-цель ≥48dp (§4.5) + зазор справа у каждого чипа. Зазор ИМЕННО у чипа, а не
                      // spacedBy у Row: скрытый «Сбросить» тогда не оставляет пустой отступ слева.
                      val chipTouch = Modifier.heightIn(min = 48.dp).padding(end = 8.dp)
                      Column {
                        // ПОЯСНЕНИЕ к строке ниже (2026-08-03, «где ближайшая поездка?»): раньше
                        // фильтры шли ДВУМЯ рядами — «когда едем» и «условия поездки». Вместе
                        // ~110dp, и они выталкивали за нижний край сами карточки поездок: человек
                        // видел заголовок «Ближайшие поездки», фильтры к ним — и ни одной поездки.
                        // Ряды сведены в один: он и так прокручивается вбок, ничего не потерялось,
                        // а экран стал ниже на целый ряд. Группы разделены тонкой чертой, чтобы
                        // «Сегодня» и «Детское кресло» не читались как один список.
                        Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                            // F4: «Когда едем» — видим ВСЕГДА (даже при пустой выдаче: выбрал
                            // «Сегодня», пусто → должен смочь вернуться на «Все дни»).
                            val today = java.time.LocalDate.now()
                            NearbyFilterChip(Icons.Default.CalendarMonth, appText("Все дни", "Бөтә көндәр"), dateFilter == null, modifier = chipTouch) { dateFilter = null }
                            NearbyFilterChip(Icons.Default.Schedule, appText("Сегодня", "Бөгөн"), dateFilter == today.toString(), modifier = chipTouch) {
                                dateFilter = if (dateFilter == today.toString()) null else today.toString()
                            }
                            NearbyFilterChip(Icons.Default.Schedule, appText("Завтра", "Иртәгә"), dateFilter == today.plusDays(1).toString(), modifier = chipTouch) {
                                dateFilter = if (dateFilter == today.plusDays(1).toString()) null else today.plusDays(1).toString()
                            }
                            // Черта между «когда» и «на чём»: две разные мысли в одном ряду.
                            Box(
                                Modifier.align(Alignment.CenterVertically)
                                    .padding(end = 8.dp).width(1.dp).height(22.dp)
                                    .background(CanonBorder)
                            )
                            // «Сбросить · N» — выезжает слева, как только включён хоть один фильтр,
                            // и уезжает обратно, когда фильтров нет (не занимает место зря).
                            AnimatedVisibility(
                                visible = prefFilter.isNotEmpty(),
                                enter = fadeIn(tween(CanonMotion.QUICK)) + slideInHorizontally(tween(CanonMotion.NORMAL)) { -it },
                                exit = fadeOut(tween(CanonMotion.QUICK)) + slideOutHorizontally(tween(CanonMotion.QUICK)) { -it }
                            ) {
                                NearbyFilterChip(
                                    Icons.Default.Close,
                                    appText("Сбросить · ${prefFilter.size}", "Бушатырға · ${prefFilter.size}"),
                                    false,
                                    modifier = chipTouch
                                ) { prefFilter = emptySet() }
                            }
                            NearbyFilterChip(Icons.Default.Woman, appText("Только женщины", "Тик ҡатын-ҡыҙ"), "women" in prefFilter, modifier = chipTouch) { prefFilter = if ("women" in prefFilter) prefFilter - "women" else prefFilter + "women" }
                            NearbyFilterChip(Icons.Default.ChildCare, appText("Детское кресло", "Балалар ултырғысы"), "child" in prefFilter, modifier = chipTouch) { prefFilter = if ("child" in prefFilter) prefFilter - "child" else prefFilter + "child" }
                            NearbyFilterChip(Icons.Default.Pets, appText("С животным", "Хайуан менән"), "pets" in prefFilter, modifier = chipTouch) { prefFilter = if ("pets" in prefFilter) prefFilter - "pets" else prefFilter + "pets" }
                            NearbyFilterChip(Icons.Default.Luggage, appText("Багаж", "Багаж"), "baggage" in prefFilter, modifier = chipTouch) { prefFilter = if ("baggage" in prefFilter) prefFilter - "baggage" else prefFilter + "baggage" }
                            NearbyFilterChip(Icons.Default.AcUnit, appText("Кондиционер", "Кондиционер"), "ac" in prefFilter, modifier = chipTouch) { prefFilter = if ("ac" in prefFilter) prefFilter - "ac" else prefFilter + "ac" }
                            NearbyFilterChip(Icons.Default.Block, appText("Некурящий", "Тартмаусы"), "nosmoke" in prefFilter, modifier = chipTouch) { prefFilter = if ("nosmoke" in prefFilter) prefFilter - "nosmoke" else prefFilter + "nosmoke" }
                            NearbyFilterChip(Icons.Default.VolumeOff, appText("Тихая поездка", "Тыныс сәфәр"), "quiet" in prefFilter, modifier = chipTouch) { prefFilter = if ("quiet" in prefFilter) prefFilter - "quiet" else prefFilter + "quiet" }
                        }
                        // F9: поясняем, что фильтр «Только женщины» включает и женщин за рулём.
                        AnimatedVisibility(
                            visible = "women" in prefFilter,
                            enter = fadeIn(tween(CanonMotion.QUICK)),
                            exit = fadeOut(tween(CanonMotion.QUICK))
                        ) {
                            Row(
                                modifier = Modifier.padding(top = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Woman, contentDescription = null, tint = CanonWoman, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    appText("Женщины за рулём и поездки «только для женщин».", "Рулдә ҡатын-ҡыҙҙар һәм «тик ҡатын-ҡыҙ өсөн» сәфәрҙәр."),
                                    color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp
                                )
                            }
                        }
                      }
                    }
                }
                item {
                    // Состояния ленты: загрузка → скелетоны, пусто/нет сети → карточка-заглушка,
                    // фильтры всё срезали → подсказка, иначе — карусель. Переход между состояниями
                    // мягкий (скелетоны не «хлопают» в карточки, а растворяются друг в друга).
                    val nearbyState = when {
                        nearbyLoading && nearby.isEmpty() -> "loading"
                        nearby.isEmpty() -> if (nearbyError) "error" else "empty"
                        shownNearby.isEmpty() -> "filtered"
                        else -> "list"
                    }
                    Box(Modifier.appearIn(3)) {
                        AnimatedContent(
                            targetState = nearbyState,
                            transitionSpec = { fadeIn(tween(CanonMotion.NORMAL)) togetherWith fadeOut(tween(CanonMotion.QUICK)) },
                            label = "nearbyState"
                        ) { state ->
                        when (state) {
                            "loading" -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                NearbySkeletonCard(); NearbySkeletonCard()
                            }
                            "empty", "error" -> NearbyEmptyCard(
                                hasRoute = focusFrom != null,
                                onRetry = { nearbyReload++ },
                                error = nearbyError,
                                onWatchRoute = if (!nearbyError) ({ onRouteWatch(focusFrom, focusTo) }) else null
                            )
                            "filtered" -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(
                                    appText("Нет поездок с такими условиями. Сними часть фильтров.", "Был шарттар менән сәфәр юҡ. Фильтрҙың бер өлөшөн ал."),
                                    color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp
                                )
                                if (prefFilter.isNotEmpty()) {
                                    Surface(
                                        onClick = { prefFilter = emptySet() },
                                        shape = RoundedCornerShape(14.dp),
                                        color = CanonSurface,
                                        border = BorderStroke(1.dp, CanonBorder)
                                    ) {
                                        Row(
                                            modifier = Modifier.heightIn(min = 48.dp).padding(horizontal = 16.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Default.Close, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(20.dp))
                                            Spacer(Modifier.width(8.dp))
                                            // BA-draft
                                            Text(appText("Сбросить фильтры", "Фильтрҙы бушат"), color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        }
                                    }
                                }
                            }
                            else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                itemsIndexed(shownNearby, key = { i, dto -> "${dto.id}#$i" }) { i, dto ->
                                    NearbyRideCard(dto = dto, soonest = i == 0, onOpen = { onBookRide(dto.toUiRide()) })
                                }
                                // «Показать ещё» — когда сервер сообщил, что есть ещё (и фильтр не активен).
                                if (prefFilter.isEmpty() && nearby.size < nearbyTotal) {
                                    item(key = "nearby_more") {
                                        NearbyMoreCard(loading = nearbyLoading) { nearbyLimit += NEARBY_PAGE }
                                    }
                                }
                            }
                        }
                        }
                    }
                }
                item {
                    Box(Modifier.appearIn(4)) {
                        ClinicRidesEntryCard(onClick = onClinicRides)
                    }
                }
                nearbyAd?.let { ad ->
                    item {
                        Box(Modifier.appearIn(5)) {
                            PartnerAdCard(
                                ad = ad,
                                stats = adStats[ad.id] ?: AdStats(),
                                label = appText("Партнёр рядом", "Яҡындағы партнёр"),
                                onImpression = onAdImpression,
                                onClick = onAdClick,
                                onRoute = { selected -> adRoute = selected }   // «Маршрут» → на нашей карте, не во внешних
                            )
                        }
                    }
                }
            }
        }
    }
    // Тап по маркеру/карточке поездки → карта показывает её маршрут (выше), детали — карточкой снизу.
    // Не модалка (нет затемнения карты): тап мимо карточки закрывает; карта с линией маршрута видна.
    if (selectedRide != null) {
        Box(
            Modifier.fillMaxSize().clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { selectedRide = null }
        )
    }
    AnimatedVisibility(
        visible = selectedRide != null,
        // Лист выезжает снизу мягко и чуть медленнее, чем уходит — так он читается «дорого», а не резко.
        enter = slideInVertically(tween(CanonMotion.SLOW)) { it } + fadeIn(tween(CanonMotion.NORMAL)),
        exit = slideOutVertically(tween(CanonMotion.QUICK)) { it } + fadeOut(tween(CanonMotion.QUICK)),
        modifier = Modifier.align(Alignment.BottomCenter)
    ) {
        lastPreview?.let { ride ->
            Surface(
                color = CanonSurface,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),   // радиус как у карточек Canon
                shadowElevation = CanonDepth.sheet,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 16.dp)) {
                    Box(
                        Modifier.align(Alignment.CenterHorizontally).width(40.dp).height(4.dp)
                            .clip(RoundedCornerShape(8.dp)).background(CanonBorder)
                    )
                    Spacer(Modifier.height(12.dp))
                    RideCard(
                        ride = ride,
                        compact = true,
                        fullWidth = true,
                        onBook = { selectedRide = null; onBookRide(ride) },
                        onShare = { onShareRide(ride) },
                        onBoost = { selectedRide = null; onBoost() }
                    )
                }
            }
        }
    }
    }
}

@Composable
private fun MapHero(
    activeTrip: Ride?,
    rides: List<Ride> = emptyList(),
    onRideTap: (Ride) -> Unit,
    onFind: (PopularRoute) -> Unit,
    onDriver: () -> Unit,
    adRoute: PartnerAd? = null,        // активный «Маршрут до партнёра» (из рекламы) → показываем на карте
    onClearRoute: () -> Unit = {},
    previewRide: Ride? = null,         // выбранная поездка → её маршрут на карте
    requests: List<com.yuldash.app.data.RequestNearDto> = emptyList()  // заявки рядом → маркеры на карте
) {
    // Популярные маршруты — порядок с сервера (из реальных поездок); демо для богатого вида.
    // Поллинг ставится на паузу, когда приложение уходит в фон (repeatOnLifecycle RESUMED):
    // не дёргаем сервер, пока экран не виден — экономия трафика/батареи на масштабе.
    val lifecycleOwner = LocalLifecycleOwner.current
    var popular by remember { mutableStateOf(demoPopularRoutes) }
    LaunchedEffect(Unit) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                ApiClient.getPopularRoutes().onSuccess { srv ->
                    if (srv.isNotEmpty()) popular = srv.map { s ->
                        demoPopularRoutes.firstOrNull { it.from == s.from && it.to == s.to }
                            ?: PopularRoute(from = s.from, to = s.to, minutes = "—", minutesBa = "—", distance = "", nearbyCount = s.count, label = "Поездки", labelBa = "Сәфәрҙәр")
                    }
                }
                delay(45_000)   // обновляем карусель под актуальные поездки
            }
        }
    }
    // Живые цифры ленты (поездок за день/неделю/месяц/год + топ-маршрут) — с сервера.
    var liveFeed by remember { mutableStateOf<FeedDto?>(null) }
    LaunchedEffect(Unit) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                ApiClient.getFeed().onSuccess { liveFeed = it }
                delay(60_000)
            }
        }
    }
    var cardCollapsed by remember { mutableStateOf(false) }
    var activeRoute by remember { mutableStateOf<PopularRoute?>(null) }
    val adRoutePoint = remember(adRoute) { adRoute?.let { parseMapPoint(it.mapPoint) } }
    var nativeMapVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (BuildConfig.YANDEX_MAPKIT_KEY.isNotBlank()) {
            androidx.compose.runtime.withFrameNanos { }
            delay(700)
            nativeMapVisible = true
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // 280dp вместо прежних 352dp (замечание Александра 2026-08-03: «где ближайшая
                // поездка?»). Карта тут ПРЕВЬЮ, а не рабочий инструмент: точку на ней не выбрать,
                // маршрут строится в отдельном потоке. При этом она съедала почти половину экрана
                // и выталкивала за нижний край «Ближайшие поездки» — то есть ровно тот список,
                // ради которого приложение и открывают.
                //
                // Почему 280, а не 240, как было в первой попытке: на 240dp кнопки масштаба
                // (правый верхний угол, две по 48dp) и плавающая карточка-подсказка (низ, ~125dp)
                // сходились в одной полосе, и карточка наезжала на «−». Сузить карточку было бы
                // костылём: она полноширинная по смыслу, а тесно ей стало из-за высоты. 280dp
                // дают между ними ~30dp воздуха — и список поездок всё равно поднимается
                // на экран (первая карточка выглядывает снизу и сама говорит «листай дальше»).
                .height(280.dp)   // сетка 4dp
        ) {
            if (BuildConfig.YANDEX_MAPKIT_KEY.isNotBlank() && nativeMapVisible) {
                YandexMapCard(
                    modifier = Modifier.matchParentSize(),
                    activeTrip = activeTrip,
                    rides = rides,
                    onRideTap = onRideTap,
                    adRoutePoint = adRoutePoint,
                    previewRide = previewRide,
                    requests = requests,
                    showPrivacyNotice = false
                )
            } else {
                MapPreview(Modifier.matchParentSize())
            }
            // Плашка активного маршрута до партнёра + крестик «сбросить» (как в навигаторах).
            // Появляется/уходит мягко — карта не «моргает» плашкой при выборе маршрута из рекламы.
            MapAdRouteBanner(
                adRoute = adRoute,
                onClearRoute = onClearRoute,
                // top = 44dp: сверху слева теперь стоит логотип «Яндекс Карты» (его туда увели,
                // чтобы карточка снизу его не закрывала) — плашка маршрута идёт под ним.
                modifier = Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 48.dp, end = 12.dp),
            )
            // Подсказка-маршрут плавает в нижней части карты: свайп вправо → язычок, тап → назад.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    AnimatedVisibility(
                        visible = !cardCollapsed,
                        enter = slideInHorizontally { it } + fadeIn(),
                        exit = slideOutHorizontally { it } + fadeOut()
                    ) {
                        QuickSearchCard(
                            feed = remember(popular, liveFeed) { mapFeedFrom(popular, liveFeed) },
                            // Лента ещё не пришла с сервера → цифры это демо/офлайн-фоллбэк (mapFeedFrom),
                            // показываем честную подпись «≈ примерно», чтобы не выдавать их за живые.
                            feedLive = liveFeed != null,
                            onCollapse = { cardCollapsed = true },
                            onRouteChange = { activeRoute = it },
                            compact = true
                        )
                    }
                    AnimatedVisibility(
                        visible = cardCollapsed,
                        modifier = Modifier.align(Alignment.End),
                        enter = slideInHorizontally { it } + fadeIn(),
                        exit = slideOutHorizontally { it } + fadeOut()
                    ) {
                        Surface(
                            onClick = { cardCollapsed = false },
                            shape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp),
                            color = CanonSurface,
                            shadowElevation = CanonDepth.raised,
                            modifier = Modifier.size(width = 48.dp, height = 56.dp)   // тач-цель ≥48dp (§4.5): было 40dp по ширине
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.ArrowBackIosNew,
                                    contentDescription = appText("Показать популярный маршрут", "Популяр маршрутты күрһәтеү"),
                                    tint = CanonGreen2,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
        // Кнопки — отдельный блок ПОД картой (не плавают на ней). Один размер текста, одна высота,
        // один радиус: пара читается как одна пилюля-действие, зелёная — главная, золотая — вторая.
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // `heightIn(min = ...)` вместо жёсткой высоты и две строки вместо одной: при системном
            // шрифте 1.5× подпись «Мин водитель» обрезалась до «Мин во…», и кнопка переставала
            // называть своё действие. Теперь она подрастает под текст, а обычный шрифт вёрстку
            // не меняет — там обе подписи и так в одну строку. Высоты у пары остаются равными:
            // Row тянет обе кнопки по самой высокой.
            Button(
                onClick = { onFind(activeRoute ?: popular.firstOrNull() ?: demoPopularRoutes.first()) },
                modifier = Modifier.weight(1.25f).heightIn(min = 56.dp),
                shape = RoundedCornerShape(22.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(appText("Найти попутку", "Юлдаш табыу"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Button(
                onClick = onDriver,
                modifier = Modifier.weight(0.95f).heightIn(min = 56.dp),
                shape = RoundedCornerShape(22.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CanonGold, contentColor = CanonGoldInk),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Icon(Icons.Default.DirectionsCar, contentDescription = null, tint = CanonGoldInk, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(appText("Я водитель", "Мин водитель"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// Приветствие по времени суток (утро/день/вечер/ночь).
@Composable
private fun timeGreeting(name: String): String {
    val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    return when (hour) {
        in 5..10 -> appText("Доброе утро, $name", "Хәйерле иртә, $name")
        in 11..16 -> appText("Добрый день, $name", "Хәйерле көн, $name")
        in 17..22 -> appText("Добрый вечер, $name", "Хәйерле кис, $name")
        else -> appText("Доброй ночи, $name", "Тыныс төн, $name")
    }
}

@Composable
private fun HomeHeader(onSos: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                timeGreeting(ApiClient.cachedName() ?: appText("друг", "дуҫ")),
                color = CanonMuted, style = CanonCaption,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                appText("Куда поедем?", "Ҡайҙа барабыҙ?"),
                color = CanonGreen,
                style = CanonTitle,
                // Две строки, а не одна: при системном шрифте 1.5× башкирское «Ҡайҙа барабыҙ?»
                // обрывалось на «Ҡайҙа бараб…» — главный вопрос экрана человек не дочитывал.
                // При обычном шрифте обе фразы и так помещаются в строку, вёрстка не меняется.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        // Тумблер день/ночь: солнце в тёмной теме (тап → светлая), луна в светлой (тап → тёмная).
        //
        // Раньше это был квадрат 48dp с рамкой — ровно той же формы и с той же обводкой, что у
        // кнопки SOS рядом. Только SOS залита красным и подписана, а тут тонкая иконка болталась
        // в пустой белой коробке: читалось как сломанный близнец SOS (замечено Александром,
        // 2026-08-03). И по смыслу они не ровня — SOS про жизнь, тема про удобство глазам.
        //
        // Стало: круг вместо скруглённого квадрата (другая форма сразу снимает конкуренцию),
        // мягкая заливка без обводки, иконка крупнее — она больше не плавает в пустоте.
        // Смена солнце↔луна с поворотом: понятно, что кнопка сработала, без единой надписи.
        val themeCtx = LocalContext.current
        val isDarkNow = appIsDark()
        Surface(
            modifier = Modifier
                // clip ДО нажатия: Surface дописывает свой .clip(shape) ПОСЛЕ нашего модификатора,
                // поэтому волна от пальца до той обрезки не доходит и вылезает углами за круг.
                // На прежней квадратной плашке этого не было видно, на круге — видно сразу.
                .clip(CircleShape)
                .bounceClick {
                    val newDark = !isDarkNow
                    ThemePrefs.darkOverride = newDark
                    themeCtx.getSharedPreferences("yuldash_theme", Context.MODE_PRIVATE)
                        .edit().putBoolean("dark_override", newDark).apply()
                }
                .size(48.dp),   // тач-цель 48dp (a11y §4.5)
            shape = CircleShape,
            color = CanonMint,
        ) {
            Box(contentAlignment = Alignment.Center) {
                AnimatedContent(
                    targetState = isDarkNow,
                    transitionSpec = {
                        (fadeIn(tween(CanonMotion.QUICK)) + scaleIn(tween(CanonMotion.QUICK), initialScale = 0.6f))
                            .togetherWith(fadeOut(tween(CanonMotion.QUICK)) + scaleOut(tween(CanonMotion.QUICK), targetScale = 0.6f))
                    },
                    label = "theme_icon",
                ) { dark ->
                    Icon(
                        painterResource(if (dark) R.drawable.yu_sun else R.drawable.yu_moon),
                        contentDescription = appText(
                            if (dark) "Светлая тема" else "Тёмная тема",
                            if (dark) "Яҡты тема" else "Ҡараңғы тема"
                        ),
                        tint = CanonGreen2,
                        modifier = Modifier.size(23.dp)
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Surface(
            modifier = Modifier.bounceClick(onSos),
            shape = RoundedCornerShape(14.dp),
            color = CanonDangerBg,
            border = BorderStroke(1.dp, CanonDangerBorder)
        ) {
            Row(
                modifier = Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Shield,
                    contentDescription = appText("SOS — экстренная помощь", "SOS — ашығыс ярҙам"),
                    tint = CanonRed,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("SOS", color = CanonRed, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun QuickSearchCard(
    feed: List<MapFeedCard>,
    onCollapse: () -> Unit,
    onRouteChange: (PopularRoute) -> Unit,
    compact: Boolean = false,
    feedLive: Boolean = false   // лента подтверждена сервером? false → цифры приблизительные (демо/офлайн)
) {
    val safeFeed = feed.ifEmpty { mapFeedFrom(demoPopularRoutes) }
    val count = safeFeed.size
    // Бесконечная карусель: виртуальный счётчик страниц, контент по модулю → всегда вперёд, без отката.
    val pagerState = rememberPagerState(
        initialPage = if (count > 1) count * 1000 else 0,
        pageCount = { if (count > 1) Int.MAX_VALUE else count }
    )

    LaunchedEffect(count) {
        if (count <= 1) return@LaunchedEffect
        while (true) {
            delay(4_500)
            if (!pagerState.isScrollInProgress) {
                pagerState.animateScrollToPage(pagerState.currentPage + 1)
            }
        }
    }
    // Выбор для «Найти поездку» обновляем только на карточках-маршрутах (на цифрах/фактах — держим прошлый).
    LaunchedEffect(pagerState.currentPage, count) {
        safeFeed[pagerState.currentPage % count].route?.let(onRouteChange)
    }
    var dragAccum by remember { mutableStateOf(0f) }

    Card(
        modifier = Modifier.fillMaxWidth().pointerInput(Unit) {
            // Свайп вправо по карточке → свернуть в язычок (карусель листается сама).
            detectHorizontalDragGestures(
                onDragEnd = { if (dragAccum > 110f) onCollapse(); dragAccum = 0f },
                onDragCancel = { dragAccum = 0f }
            ) { _, dragAmount -> if (dragAmount > 0f) dragAccum += dragAmount }
        },
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = CanonCardShape,
        elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.card)
    ) {
        Column(modifier = Modifier.padding(if (compact) 12.dp else 16.dp)) {
            HorizontalPager(
                state = pagerState,
                pageSpacing = 12.dp,
                userScrollEnabled = false
            ) { page ->
                val card = safeFeed[page % count]
                val icon = when (card.kind) {
                    FeedKind.Route -> Icons.Default.Star
                    FeedKind.Live -> Icons.Default.Bolt
                    FeedKind.Top -> Icons.Default.EmojiEvents
                    FeedKind.Fact -> Icons.Default.Lightbulb
                    FeedKind.Community -> Icons.Default.Favorite
                    FeedKind.Donate -> Icons.Default.VolunteerActivism
                }
                // Единый макет: бейдж+пилюля (верх) · заголовок фикс.высоты · подпись+точки (низ).
                // Фикс. высота заголовка → все карточки ровно одного размера, карусель не «прыгает».
                Column(verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = CanonMint, shape = RoundedCornerShape(999.dp)) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    appText(card.badge, card.badgeBa),
                                    color = CanonGreen2, fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        // Честный сигнал: пока лента не пришла с сервера — цифры демо/офлайн, помечаем «≈ примерно».
                        if (!feedLive) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                appText("≈ примерно", "≈ яҡынса"),
                                color = CanonMuted, fontWeight = FontWeight.Medium,
                                fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        Surface(color = CanonMint, shape = RoundedCornerShape(14.dp)) {
                            Text(
                                appText(card.pill, card.pillBa),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                color = CanonGreen, fontWeight = FontWeight.Bold,
                                fontSize = 14.sp, maxLines = 1
                            )
                        }
                    }
                    Box(
                        // Высота = две строки заголовка ровно: 2 x 25 и 2 x 30 по шкале.
                        // Магическое число тут уже один раз обрезало текст — держим связь явной.
                        modifier = Modifier.height(if (compact) 50.dp else 60.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            appText(card.title, card.titleBa),
                            color = CanonGreen, fontWeight = FontWeight.Bold,
                            fontSize = if (compact) 19.sp else 24.sp,
                            lineHeight = if (compact) 25.sp else 30.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            appText(card.sub, card.subBa),
                            color = CanonMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        // Индикатор карусели: активная точка вытягивается в пилюлю и подкрашивается —
                        // переход плавный, поэтому смена карточки читается, а не «мигает».
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            val active = pagerState.currentPage % count
                            safeFeed.forEachIndexed { index, _ ->
                                val dotWidth by animateDpAsState(
                                    targetValue = if (index == active) 16.dp else 6.dp,
                                    animationSpec = tween(CanonMotion.NORMAL),
                                    label = "feedDotWidth"
                                )
                                val dotColor by animateColorAsState(
                                    targetValue = if (index == active) CanonGreen2 else CanonBorder,
                                    animationSpec = tween(CanonMotion.NORMAL),
                                    label = "feedDotColor"
                                )
                                Box(
                                    modifier = Modifier
                                        .width(dotWidth)
                                        .height(6.dp)
                                        .background(dotColor, CircleShape)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SeniorAccessCard(onSimpleMode: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .bounceClick(onSimpleMode),
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = CanonItemShape,
        elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.card),
        border = BorderStroke(1.dp, CanonHairlineGreen)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Строка стоит в одном списке с остальными настройками, поэтому и выглядит
            // так же (SettingsNavRow): значок 12dp, подпись 14sp, серая стрелка. Раньше
            // подпись была 12sp, а стрелка зелёной — набрана мельче соседей, но с более
            // ярким акцентом: два противоречивых сигнала в одной строке.
            Surface(color = CanonMint, shape = CircleShape) {
                Icon(
                    Icons.Default.VolumeUp,
                    contentDescription = null,
                    tint = CanonGreen2,
                    modifier = Modifier.padding(12.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    appText("Простой режим", "Ябай режим"),
                    color = CanonText,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    lineHeight = 23.sp
                )
                Text(
                    appText("Крупные кнопки и голос", "Ҙур төймәләр һәм тауыш"),
                    color = CanonMuted,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                Icons.Default.KeyboardArrowRight,
                contentDescription = appText("Открыть простой режим", "Ябай режимды асыу"),
                tint = CanonMuted,
            )
        }
    }
}

// Координаты городов (BaymakPoint/SibayPoint) и гео-хелперы вынесены в MapGeo.kt (Спринт 3).
// Согласие на показ геолокации — общий флаг (Профиль → Конфиденциальность ↔ карта).
// Радиус «рядом»: поездки, чья точка выезда дальше — отсекаем (только когда знаем позицию клиента).
private const val NEARBY_RADIUS_KM = 50.0
private const val NEARBY_PAGE = 5            // «Ближайших» на страницу; «Показать ещё» добавляет столько же

internal object LocationPrefs {
    var sharingEnabled by mutableStateOf(false)
    // Последняя позиция клиента (с карты) — для «сколько в N км от тебя» в «Ближайших поездках».
    var lastLat by mutableStateOf<Double?>(null)
    var lastLng by mutableStateOf<Double?>(null)
}

private val MapMidPoint = Point(52.55, 58.49) // южнее центра маршрута → точки рисуются в верхней части, не под плашкой


/**
 * Настоящая Яндекс-карта на вкладке «Карта». Инициализируется ЛЕНИВО (только когда
 * экран открыт) — экономим бесплатный тариф MapKit. Поверх карты — наши брендовые
 * накладки: метки городов, пилюля «43 км» и плашка-замок про приватность геолокации.
 *
 * Приватность: точную точку не показываем — рисуем приблизительные зоны (круги ~600 м)
 * у старта (зелёный) и финиша (золотой), как обещает плашка.
 */
// MapKit init: setLocale ДО initialize и ТОЛЬКО один раз на процесс.
// Повторный setLocale после initialize → AssertionError (краш при возврате на карту с подэкрана).
private var mapKitReady = false
internal fun ensureMapKit(context: Context) {
    if (mapKitReady) return
    mapKitReady = true
    runCatching { MapKitFactory.setLocale("ru_RU") }   // если уже инициализирован — пропускаем, не крашим
    MapKitFactory.initialize(context)
}

// Маршрут по дорогам from→to на карте. Общий рисователь для активной поездки И превью выбранной.
// Сразу кладёт прямую линию (мгновенный фидбэк), затем DrivingRouter заменяет её реальной дорожной геометрией;
// + флажок назначения. Все объекты — в `added` (вызывающий снимет при dispose). Возвращает сессию роутинга (отменить).
// Ошибка/нет квоты роутинга → остаётся прямая линия (фоллбэк, карта не ломается).
private fun drawRoadRoute(
    map: com.yandex.mapkit.map.Map,
    from: Point,
    to: Point,
    added: MutableList<com.yandex.mapkit.map.MapObject>,
    onEta: (String) -> Unit = {},   // время в пути из метаданных маршрута (если пришло)
    onRoutePoints: (List<Point>, Double) -> Unit = { _, _ -> }   // геометрия маршрута + полное время (сек) — для живого ETA
): com.yandex.mapkit.directions.driving.DrivingSession? {
    val straightLine = map.mapObjects.addPolyline(Polyline(listOf(from, to))).apply {
        setStrokeColor(ROUTE_STROKE_ARGB.toInt()); strokeWidth = 4f
    }
    added += straightLine
    val session = runCatching {
        val router = com.yandex.mapkit.directions.DirectionsFactory.getInstance()
            .createDrivingRouter(com.yandex.mapkit.directions.driving.DrivingRouterType.COMBINED)
        val reqPoints = listOf(
            com.yandex.mapkit.RequestPoint(from, com.yandex.mapkit.RequestPointType.WAYPOINT, null, null, null),
            com.yandex.mapkit.RequestPoint(to, com.yandex.mapkit.RequestPointType.WAYPOINT, null, null, null),
        )
        router.requestRoutes(
            reqPoints,
            com.yandex.mapkit.directions.driving.DrivingOptions().apply { routesCount = 3 },   // основной + до 2 объездных/альтернативных
            com.yandex.mapkit.directions.driving.VehicleOptions(),
            object : com.yandex.mapkit.directions.driving.DrivingSession.DrivingRouteListener {
                override fun onDrivingRoutes(routes: MutableList<com.yandex.mapkit.directions.driving.DrivingRoute>) {
                    val r = routes.firstOrNull() ?: return
                    runCatching {
                        map.mapObjects.remove(straightLine)
                        added.remove(straightLine)
                        // Объездные/альтернативные маршруты — бледно-серым, ПОД основным (как в навигаторах).
                        routes.drop(1).take(2).forEach { alt ->
                            runCatching { added += map.mapObjects.addPolyline(alt.geometry).apply { setStrokeColor(CANON_ROUTE_ALT_ARGB); strokeWidth = 4f } }
                        }
                        // Основной (оптимальный по Яндексу — он сам учитывает пробки и закрытия дорог) — зелёным, поверх.
                        added += map.mapObjects.addPolyline(r.geometry).apply {
                            setStrokeColor(ROUTE_STROKE_ARGB.toInt()); strokeWidth = 5f
                        }
                    }
                    runCatching { onEta(r.metadata.weight.time.text) }   // «45 мин» — время в пути
                    runCatching { onRoutePoints(r.geometry.points, r.metadata.weight.time.value) }   // точки+сек для живого «осталось»
                }
                override fun onDrivingRoutesError(error: com.yandex.runtime.Error) { /* фоллбэк: прямая остаётся */ }
            }
        )
    }.getOrNull()
    added += map.mapObjects.addPlacemark().apply {
        geometry = to
        setIcon(ImageProvider.fromBitmap(destFlagBitmap()))
        setIconStyle(IconStyle().setAnchor(PointF(0.24f, 0.9f)))
    }
    return session
}

// Камера, охватывающая весь маршрут from→to (как навигатор показывает поездку целиком), с небольшим запасом.
private fun fitRouteCamera(map: com.yandex.mapkit.map.Map, from: Point, to: Point): CameraPosition? = runCatching {
    val bbox = com.yandex.mapkit.geometry.BoundingBox(
        Point(minOf(from.latitude, to.latitude), minOf(from.longitude, to.longitude)),
        Point(maxOf(from.latitude, to.latitude), maxOf(from.longitude, to.longitude))
    )
    val fit = map.cameraPosition(com.yandex.mapkit.geometry.Geometry.fromBoundingBox(bbox))
    CameraPosition(fit.target, (fit.zoom - 0.5f).coerceIn(3f, 16f), 0f, 0f)   // -0.5 = запас по краям
}.getOrNull()

@Composable
private fun YandexMapCard(
    modifier: Modifier = Modifier,
    activeTrip: Ride? = null,
    rides: List<Ride> = emptyList(),
    onRideTap: (Ride) -> Unit = {},
    adRoutePoint: Point? = null,   // «Маршрут» из рекламы → строим дорогу к этой точке прямо на нашей карте
    previewRide: Ride? = null,     // выбранная поездка (тап по пину/карточке) → показать её маршрут на карте
    requests: List<com.yuldash.app.data.RequestNearDto> = emptyList(),  // заявки рядом → маркеры «ищет попутку»
    showPrivacyNotice: Boolean = true
) {
    val context = LocalContext.current
    // Геолокация управляется из Профиль → Конфиденциальность (общий LocationPrefs); FAB «к себе» тоже включает.
    var lastUserPoint by remember { mutableStateOf<Point?>(null) }
    var routeEta by remember { mutableStateOf<String?>(null) }   // время в пути из DrivingRoute → чип на карте
    var liveRemainSec by remember { mutableStateOf<Int?>(null) } // живой остаток «сколько ехать» по ходу движения (демо/трекинг)
    var activeRoutePts by remember { mutableStateOf<List<Point>>(emptyList()) } // геометрия маршрута активной поездки (для живого ETA от моей позиции)
    var activeRouteSec by remember { mutableStateOf(0.0) }                       // полное время этого маршрута, сек
    val locationPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) LocationPrefs.sharingEnabled = true
    }
    val nightMap = appIsDark()   // тёмная тема → ночной стиль карты
    // Свежие ссылки на активную поездку/тап, чтобы tap-listener не «застревал» на старых данных.
    val currentTrip by rememberUpdatedState(activeTrip)
    val currentPreview by rememberUpdatedState(previewRide)
    val currentRides by rememberUpdatedState(rides)
    val currentRequests by rememberUpdatedState(requests)
    val currentOnTap by rememberUpdatedState(onRideTap)
    // Тап по заявке пассажира → открыть карточку (имя, маршрут, коммент) + кнопка «Откликнуться».
    var selectedRequest by remember { mutableStateOf<com.yuldash.app.data.RequestNearDto?>(null) }
    val tapListener = remember {
        MapObjectTapListener { obj, _ ->
            val id = obj.userData as? String
            // Заявка пассажира: userData = "req-{id}" → кто ищет попутку (имя + маршрут, БЕЗ телефона).
            if (id != null && id.startsWith("req-")) {
                currentRequests.firstOrNull { "req-${it.id}" == id }?.let { req ->
                    selectedRequest = req
                    return@MapObjectTapListener true
                }
            }
            val ride = currentTrip?.takeIf { it.id == id } ?: currentRides.firstOrNull { it.id == id }
            if (ride != null) currentOnTap(ride)
            ride != null
        }
    }
    val mapView = remember {
        ensureMapKit(context)   // setLocale+initialize ОДИН раз на процесс (повторный setLocale крашит)
        MapView(context).also { view ->
            val map = view.mapWindow.map
            map.isNightModeEnabled = nightMap
            // Логотип «Яндекс Карты» — в ЛЕВЫЙ ВЕРХ. По умолчанию он внизу справа, а там у нас
            // плавает карточка-подсказка на всю ширину — она накрывала логотип наполовину.
            // Это не косметика: условия MapKit требуют, чтобы логотип был виден целиком,
            // иначе карту нельзя показывать в публичном релизе.
            map.logo.setAlignment(LogoAlignment(LogoHorizontal.LEFT, LogoVertical.TOP))
            map.move(CameraPosition(MapMidPoint, 9.0f, 0f, 0f))
            // Маршруты-линии + ценники поездок рисуются ниже (LaunchedEffect, по реальным заказам).
            // Карта внутри прокручиваемого списка: на касании просим родителя (LazyColumn)
            // не перехватывать жест — иначе тап по маркеру и панорамирование «съедает» скролл.
            view.setOnTouchListener { v, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                }
                false
            }
        }
    }
    // Тёмная тема → ночной стиль карты (обновляется при смене темы).
    LaunchedEffect(nightMap) { mapView.mapWindow.map.isNightModeEnabled = nightMap }
    // Жесты пальцами: щипок-зум держит мою точку по центру; панорама пальцем — выключает слежение.
    DisposableEffect(Unit) {
        val map = mapView.mapWindow.map
        var prevZoom = map.cameraPosition.zoom
        val cl = CameraListener { _, pos, reason, finished ->
            // Щипок-зум пальцами → держим мою точку по центру (как кнопки зума). Панораму не трогаем.
            // НО: когда на карте показан маршрут (preview/активная поездка) — не дёргаем камеру к себе,
            // иначе зум уводит её от маршрута и он «исчезает». Тогда даём свободно рассматривать маршрут.
            if (reason == CameraUpdateReason.GESTURES && finished &&
                LocationPrefs.sharingEnabled && currentTrip == null && currentPreview == null &&
                kotlin.math.abs(pos.zoom - prevZoom) > 0.05f) {
                lastUserPoint?.let { p -> map.move(CameraPosition(p, pos.zoom, 0f, 0f), Animation(Animation.Type.SMOOTH, 0.2f), null) }
            }
            prevZoom = pos.zoom
        }
        map.addCameraListener(cl)
        onDispose { map.removeCameraListener(cl) }
    }
    // «Моя геопозиция» — СВОЯ точка-плейсмарк через LocationManager (полный контроль, без дефолтной стрелки MapKit).
    DisposableEffect(LocationPrefs.sharingEnabled) {
        val map = mapView.mapWindow.map
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        var placemark: com.yandex.mapkit.map.PlacemarkMapObject? = null
        var firstFix = true
        val listener = object : android.location.LocationListener {
            override fun onLocationChanged(loc: android.location.Location) {
                val pt = Point(loc.latitude, loc.longitude)
                lastUserPoint = pt   // запоминаем — кнопка «к себе» центрирует на ней в любой момент
                LocationPrefs.lastLat = loc.latitude; LocationPrefs.lastLng = loc.longitude
                val pm = placemark
                if (pm == null) {
                    placemark = map.mapObjects.addPlacemark(pt).apply {
                        setIcon(ImageProvider.fromBitmap(userPuckBitmap()))
                    }
                } else {
                    pm.geometry = pt
                }
                if (firstFix) {
                    firstFix = false
                    map.move(CameraPosition(pt, 15f, 0f, 0f), Animation(Animation.Type.SMOOTH, 0.5f), null)
                }
            }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
        }
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (LocationPrefs.sharingEnabled && granted) {
            try {
                lm.requestLocationUpdates(android.location.LocationManager.GPS_PROVIDER, 2000L, 5f, listener)
                lm.requestLocationUpdates(android.location.LocationManager.NETWORK_PROVIDER, 2000L, 5f, listener)
                (lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                    ?: lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER))?.let { listener.onLocationChanged(it) }
            } catch (e: SecurityException) {
            } catch (e: IllegalArgumentException) {
            }
        }
        onDispose {
            lm.removeUpdates(listener)
            placemark?.let { map.mapObjects.remove(it) }
        }
    }
    // Маршрут на карте — ТОЛЬКО для активной (подтверждённой) поездки, как в Яндекс Такси:
    // линия from→to + ценник у отправления (под городом) + флажок назначения. Завершилась → всё исчезает.
    val cityCache = remember { mutableMapOf<String, Point>() }
    val tripScope = rememberCoroutineScope()
    DisposableEffect(activeTrip) {
        val map = mapView.mapWindow.map
        val added = mutableListOf<com.yandex.mapkit.map.MapObject>()
        var roadSession: com.yandex.mapkit.directions.driving.DrivingSession? = null
        val job = tripScope.launch {
            val trip = activeTrip ?: return@launch
            suspend fun resolve(city: String): Point? {
                val k = city.trim()
                cityPoint(k)?.let { return it }
                cityCache[k]?.let { return it }
                // Деревня («Кузяново (Ишимбайский р-н)») есть в нашем справочнике с координатами —
                // спрашиваем его раньше геокодера, иначе маршрут сельской поездки просто не рисуется.
                ApiClient.settlementByName(k)?.let { st ->
                    return Point(st.lat, st.lng).also { cityCache[k] = it }
                }
                val hit = GeocoderClient.suggest(k).firstOrNull() ?: return null
                return Point(hit.lat, hit.lon).also { cityCache[k] = it }
            }
            val fromPt = resolve(trip.from) ?: return@launch
            val toPt = resolve(trip.to)
            if (toPt != null) {
                roadSession = drawRoadRoute(map, fromPt, toPt, added, onEta = { routeEta = it },
                    onRoutePoints = { pts, sec -> activeRoutePts = pts; activeRouteSec = sec })   // + точки/время для живого «осталось»
                fitRouteCamera(map, fromPt, toPt)?.let { map.move(it, Animation(Animation.Type.SMOOTH, 0.5f), null) }  // показать весь маршрут
            }
            added += map.mapObjects.addPlacemark().apply {
                geometry = fromPt
                setIcon(ImageProvider.fromBitmap(ridePinBitmap("${trip.price} ₽", trip.boosted)))
                setIconStyle(IconStyle().setAnchor(PointF(0.5f, 0f)))
                userData = trip.id
                addTapListener(tapListener)
            }
        }
        onDispose {
            job.cancel()
            runCatching { roadSession?.cancel() }
            added.forEach { runCatching { map.mapObjects.remove(it) } }
            // Поездка кончилась/сменилась → убираем живой ETA и маршрут (демо-симуляция ведёт liveRemainSec сама).
            activeRoutePts = emptyList(); activeRouteSec = 0.0; liveRemainSec = null
        }
    }
    // Живой ETA реальной поездки: пока активна и есть мой GPS + дорожный маршрут — считаем, сколько ОСТАЛОСЬ ехать
    // (доля непройденного пути × полное время). Обновляется на каждый мой GPS-фикс. Пишем только при активной поездке,
    // чтобы не затирать демо-симуляцию (она пишет liveRemainSec, когда поездки нет).
    LaunchedEffect(activeTrip, lastUserPoint, activeRoutePts) {
        val pos = lastUserPoint
        if (activeTrip != null && pos != null && activeRoutePts.size >= 2 && activeRouteSec > 0) {
            liveRemainSec = remainingEtaSec(activeRoutePts, pos, activeRouteSec)
        }
    }
    // Превью маршрута ВЫБРАННОЙ поездки (тап по пину/карточке) — линия по дорогам + флажок, камера фитит весь путь.
    // Снимается, когда карточку закрыли (previewRide=null). Активную не дублируем — её рисует эффект выше.
    DisposableEffect(previewRide) {
        val map = mapView.mapWindow.map
        val added = mutableListOf<com.yandex.mapkit.map.MapObject>()
        var roadSession: com.yandex.mapkit.directions.driving.DrivingSession? = null
        val job = tripScope.launch {
            val ride = previewRide ?: return@launch
            if (ride.id == activeTrip?.id) return@launch
            suspend fun resolve(city: String): Point? {
                val k = city.trim()
                cityPoint(k)?.let { return it }
                cityCache[k]?.let { return it }
                // Деревня («Кузяново (Ишимбайский р-н)») есть в нашем справочнике с координатами —
                // спрашиваем его раньше геокодера, иначе маршрут сельской поездки просто не рисуется.
                ApiClient.settlementByName(k)?.let { st ->
                    return Point(st.lat, st.lng).also { cityCache[k] = it }
                }
                val hit = GeocoderClient.suggest(k).firstOrNull() ?: return null
                return Point(hit.lat, hit.lon).also { cityCache[k] = it }
            }
            val fromPt = resolve(ride.from) ?: return@launch
            val toPt = resolve(ride.to) ?: return@launch
            roadSession = drawRoadRoute(map, fromPt, toPt, added, onEta = { routeEta = it })
            fitRouteCamera(map, fromPt, toPt)?.let { map.move(it, Animation(Animation.Type.SMOOTH, 0.45f), null) }
        }
        onDispose {
            job.cancel()
            runCatching { roadSession?.cancel() }
            added.forEach { runCatching { map.mapObjects.remove(it) } }
        }
    }
    // Ценники поездок из ленты «Ближайших» на карте. Координаты — cityPoint (синхронно, известные города БашРТ);
    // неизвестный город пропускаем (без async-геокод-шторма). userData=id → тап открывает карточку поездки.
    DisposableEffect(rides) {
        val map = mapView.mapWindow.map
        val added = mutableListOf<com.yandex.mapkit.map.MapObject>()
        rides.forEach { ride ->
            if (activeTrip?.id == ride.id) return@forEach   // активную рисует отдельный эффект — не дублируем
            val pt = cityPoint(ride.from) ?: return@forEach
            runCatching {
                added += map.mapObjects.addPlacemark().apply {
                    geometry = pt
                    setIcon(ImageProvider.fromBitmap(ridePinBitmap("${ride.price} ₽", ride.boosted)))
                    setIconStyle(IconStyle().setAnchor(PointF(0.5f, 0f)))
                    userData = ride.id
                    addTapListener(tapListener)
                }
            }
        }
        onDispose { added.forEach { runCatching { map.mapObjects.remove(it) } } }
    }
    // Заявки пассажиров рядом («ищет попутку») — оранжевые маркеры. Координаты заявки (from_lat/lng) или город.
    // userData="req-{id}" → тап показывает кто ищет (имя+маршрут, без телефона).
    DisposableEffect(requests) {
        val map = mapView.mapWindow.map
        val added = mutableListOf<com.yandex.mapkit.map.MapObject>()
        requests.forEach { req ->
            val pt = if (req.fromLat != null && req.fromLng != null) Point(req.fromLat, req.fromLng)
                     else cityPoint(req.fromCity) ?: return@forEach
            runCatching {
                added += map.mapObjects.addPlacemark().apply {
                    geometry = pt
                    setIcon(ImageProvider.fromBitmap(requestPinBitmap()))
                    setIconStyle(IconStyle().setAnchor(PointF(0.5f, 0.5f)))
                    userData = "req-${req.id}"
                    addTapListener(tapListener)
                }
            }
        }
        onDispose { added.forEach { runCatching { map.mapObjects.remove(it) } } }
    }
    // Live-позиция попутчика (из foreground-сервиса через TripLocationBus) — ОДНА постоянная нав-стрелка,
    // которая ПЛАВНО «догоняет» новую позицию каждый кадр (как навигаторы), а не телепортируется на каждый
    // GPS-апдейт. Иначе при апдейте раз в ~7с (реальный GPS) или раз в ~0.4с (демо) были бы рывки.
    // Курс (bearing) тоже плавно доводится по кратчайшему углу. Стрелка скрыта, когда позиции нет.
    LaunchedEffect(Unit) {
        val map = mapView.mapWindow.map
        val pm = map.mapObjects.addPlacemark().apply {
            setIcon(ImageProvider.fromBitmap(peerArrowBitmap()))
            setIconStyle(IconStyle().setAnchor(PointF(0.5f, 0.5f)).setRotationType(com.yandex.mapkit.map.RotationType.ROTATE))
            isVisible = false
        }
        try {
            var curLat = 0.0; var curLng = 0.0; var curBrg = 0f
            var has = false                       // уже есть отрисованная позиция (чтобы первую ставить без «подлёта» из 0,0)
            var prevLat = Double.NaN; var prevLng = 0.0
            var lastChangeNanos = 0L; var intervalMs = 1000f   // как часто приходят новые точки (плотность подачи)
            while (true) {
                val now = androidx.compose.runtime.withFrameNanos { it }   // ждём кадр (60fps) + берём время кадра
                val p = com.yuldash.app.data.TripLocationBus.peer
                if (p == null) {                  // поездки нет → прячем стрелку, опрашиваем редко (не жжём кадры)
                    if (has) { pm.isVisible = false; has = false; prevLat = Double.NaN; lastChangeNanos = 0L }
                    kotlinx.coroutines.delay(200)
                    continue
                }
                // Новая цель? Замеряем интервал между обновлениями (это и есть «плотность подачи»).
                if (p.lat != prevLat || p.lng != prevLng) {
                    if (lastChangeNanos != 0L) intervalMs = ((now - lastChangeNanos) / 1_000_000.0).toFloat().coerceIn(8f, 8000f)
                    lastChangeNanos = now; prevLat = p.lat; prevLng = p.lng
                }
                if (!has) {                       // первая точка — ставим сразу
                    curLat = p.lat; curLng = p.lng; curBrg = (p.bearing ?: 0.0).toFloat()
                    pm.geometry = Point(curLat, curLng); pm.setDirection(curBrg); pm.isVisible = true
                    has = true
                    continue
                }
                // α от ПЛОТНОСТИ: точки часто (демо/быстрый GPS, <80мс) → тянемся жёстко = строго на дороге без отставания;
                // редко (реальный GPS ~7с) → мягко глайдим через паузу, чтобы не было рывка-телепорта.
                val dens = when {
                    intervalMs < 80f -> 0.5f
                    intervalMs > 1500f -> 0.10f
                    else -> 0.10f + (0.5f - 0.10f) * ((1500f - intervalMs) / (1500f - 80f))
                }
                // Масштаб: приближаешь → отзывчивее (на крупном плане глаз держит стрелку), отдаляешь → мягче.
                val zf = (map.cameraPosition.zoom / 13f).coerceIn(0.85f, 1.2f)
                val a = (dens * zf).coerceIn(0.08f, 0.6f)
                curLat += (p.lat - curLat) * a
                curLng += (p.lng - curLng) * a
                pm.geometry = Point(curLat, curLng)
                p.bearing?.let { tb ->            // курс — к целевому по кратчайшему углу (через 0/360 без «прокрутки»)
                    var d = ((tb.toFloat() - curBrg) % 360f + 540f) % 360f - 180f
                    curBrg = ((curBrg + d * a) % 360f + 360f) % 360f
                    pm.setDirection(curBrg)
                }
            }
        } finally {
            runCatching { map.mapObjects.remove(pm) }
        }
    }
    // Маршрут до партнёра из рекламы — прямо на нашей карте (как активная поездка, но к точке магазина).
    // Есть геолокация → дорога от меня к магазину; нет → просто центрируем карту на магазине с флажком.
    // Ключ ТОЛЬКО adRoutePoint (origin захватываем при запуске): раньше был и lastUserPoint → маршрут
    // пересчитывался DrivingRouter'ом на КАЖДЫЙ GPS-фикс, жёг квоту MapKit и линия мигала.
    DisposableEffect(adRoutePoint) {
        val map = mapView.mapWindow.map
        val added = mutableListOf<com.yandex.mapkit.map.MapObject>()
        var roadSession: com.yandex.mapkit.directions.driving.DrivingSession? = null
        val dest = adRoutePoint
        if (dest != null) {
            added += map.mapObjects.addPlacemark().apply {
                geometry = dest
                setIcon(ImageProvider.fromBitmap(destFlagBitmap()))
                setIconStyle(IconStyle().setAnchor(PointF(0.24f, 0.9f)))
            }
            val origin = lastUserPoint
            if (origin != null) {
                val straightLine = map.mapObjects.addPolyline(Polyline(listOf(origin, dest))).apply {
                    setStrokeColor(ROUTE_STROKE_ARGB.toInt()); strokeWidth = 4f
                }
                added += straightLine
                // Дорога по дорогам (DrivingRouter). Нет квоты/ошибка → остаётся прямая линия (фоллбэк).
                roadSession = runCatching {
                    val router = com.yandex.mapkit.directions.DirectionsFactory.getInstance()
                        .createDrivingRouter(com.yandex.mapkit.directions.driving.DrivingRouterType.COMBINED)
                    val reqPoints = listOf(
                        com.yandex.mapkit.RequestPoint(origin, com.yandex.mapkit.RequestPointType.WAYPOINT, null, null, null),
                        com.yandex.mapkit.RequestPoint(dest, com.yandex.mapkit.RequestPointType.WAYPOINT, null, null, null),
                    )
                    router.requestRoutes(
                        reqPoints,
                        com.yandex.mapkit.directions.driving.DrivingOptions(),
                        com.yandex.mapkit.directions.driving.VehicleOptions(),
                        object : com.yandex.mapkit.directions.driving.DrivingSession.DrivingRouteListener {
                            override fun onDrivingRoutes(routes: MutableList<com.yandex.mapkit.directions.driving.DrivingRoute>) {
                                val r = routes.firstOrNull() ?: return
                                runCatching {
                                    map.mapObjects.remove(straightLine)
                                    added.remove(straightLine)
                                    added += map.mapObjects.addPolyline(r.geometry).apply {
                                        setStrokeColor(ROUTE_STROKE_ARGB.toInt()); strokeWidth = 5f
                                    }
                                }
                            }
                            override fun onDrivingRoutesError(error: com.yandex.runtime.Error) { /* прямая остаётся */ }
                        }
                    )
                }.getOrNull()
                map.move(
                    CameraPosition(Point((origin.latitude + dest.latitude) / 2, (origin.longitude + dest.longitude) / 2), 9.5f, 0f, 0f),
                    Animation(Animation.Type.SMOOTH, 0.5f), null
                )
            } else {
                map.move(CameraPosition(dest, 13f, 0f, 0f), Animation(Animation.Type.SMOOTH, 0.5f), null)
            }
        }
        onDispose {
            runCatching { roadSession?.cancel() }
            added.forEach { runCatching { map.mapObjects.remove(it) } }
        }
    }
    // Жизненный цикл карты привязан к lifecycle экрана, а не к композиции: иначе при сворачивании
    // приложения (экран «Карта» ещё в композиции) mapView.onStop() не звался → MapKit продолжал
    // рендер/сеть в фоне (батарея + квота). Теперь onStart/onStop по ON_START/ON_STOP владельца.
    val mapLifecycle = LocalLifecycleOwner.current
    DisposableEffect(mapLifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> { MapKitFactory.getInstance().onStart(); mapView.onStart() }
                Lifecycle.Event.ON_STOP -> { mapView.onStop(); MapKitFactory.getInstance().onStop() }
                else -> {}
            }
        }
        mapLifecycle.lifecycle.addObserver(observer)
        onDispose {
            mapLifecycle.lifecycle.removeObserver(observer)
            mapView.onStop()
            MapKitFactory.getInstance().onStop()
        }
    }
    Box(
        modifier
            .fillMaxWidth()
            .clip(CanonCardShape)
            .border(1.dp, CanonBorder, CanonCardShape)
    ) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        // Мягкая вуаль по верхнему краю карты: чип времени и кнопки зума читаются на ЛЮБОЙ подложке
        // (лес, город, вода), а карта под ней остаётся видна. Цвет — фон темы, поэтому и днём, и ночью
        // вуаль «своя». Жесты не перехватывает (нет pointerInput) — карта тянется пальцем как раньше.
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(88.dp)
                .background(Brush.verticalGradient(listOf(CanonBg.copy(alpha = 0.55f), CanonBg.copy(alpha = 0f))))
        )
        // ETA-чип (слева сверху): при движении — «≈ … осталось» (живой остаток до конца), иначе общее «≈ … в пути».
        val liveSec = liveRemainSec
        val etaText: String? = when {
            liveSec != null -> {
                val h = liveSec / 3600; val m = (liveSec % 3600) / 60
                when {
                    h > 0 -> appText("≈ $h ч $m мин осталось", "≈ $h сәғ $m мин ҡалды")
                    m > 0 -> appText("≈ $m мин осталось", "≈ $m мин ҡалды")
                    else -> appText("почти на месте", "етеп килә")
                }
            }
            (activeTrip != null || previewRide != null) && routeEta != null -> appText("≈ $routeEta в пути", "≈ $routeEta юлда")
            else -> null
        }
        // Чип живёт своей жизнью: выезжает сверху, когда маршрут появился, и уходит вверх, когда снят.
        // Держим последний текст (lastEta), чтобы на выезде чип не «схлопывался» в пустоту, а сама
        // цифра минут менялась мягкой сменой — по ходу поездки она обновляется постоянно.
        var lastEta by remember { mutableStateOf("") }
        LaunchedEffect(etaText) { etaText?.let { lastEta = it } }
        AnimatedVisibility(
            visible = etaText != null,
            enter = fadeIn(tween(CanonMotion.NORMAL)) + slideInVertically(tween(CanonMotion.NORMAL)) { -it },
            exit = fadeOut(tween(CanonMotion.QUICK)) + slideOutVertically(tween(CanonMotion.QUICK)) { -it },
            modifier = Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 48.dp)
        ) {
            Surface(
                color = CanonSurface,
                shape = RoundedCornerShape(14.dp),
                shadowElevation = CanonDepth.raised,
                border = BorderStroke(1.dp, CanonHairlineGreen)
            ) {
                AnimatedContent(
                    targetState = lastEta,
                    transitionSpec = { fadeIn(tween(CanonMotion.QUICK)) togetherWith fadeOut(tween(CanonMotion.QUICK)) },
                    label = "etaChip"
                ) { text ->
                    Text(
                        text,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1
                    )
                }
            }
        }
        // ПРАВЫЙ БЛОК УПРАВЛЕНИЯ КАРТОЙ — «где я» и масштаб В ОДНУ СТРОКУ, не столбиком.
        //
        // Почему так (2026-08-03, найдено независимой проверкой). Столбиком не помещается
        // арифметически: зум-стек 97dp + зазор + «где я» 48dp = 169dp сверху, а плавающей
        // карточке снизу нужно 146dp. Вместе 315dp при высоте карты 280dp — карточка
        // накрывала «где я» на 34dp и перехватывала касания. Растить карту обратно нельзя:
        // ради этих 72dp её и ужимали, чтобы поездки поднялись над сгибом.
        // В строке высота блока = высота самого большого элемента, то есть 97dp вместо 169dp.
        // 16 + 97 + зазор + 146 = 267 < 280 — помещается с запасом 13dp.
        // Заодно честнее по смыслу: «где я» и масштаб — соседние по частоте действия,
        // держать их рядом привычнее, чем разносить по вертикали (так у 2ГИС).
        Row(
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 16.dp, end = 16.dp).zIndex(6f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top
        ) {
        // Кнопка «к себе» (как в Яндекс.Картах): центр на моей позиции; если выключено — включает.
        Surface(
            onClick = {
                when {
                    LocationPrefs.sharingEnabled -> lastUserPoint?.let { p ->
                        mapView.mapWindow.map.move(
                            CameraPosition(p, 15f, 0f, 0f),
                            Animation(Animation.Type.SMOOTH, 0.4f), null
                        )
                    }
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED -> LocationPrefs.sharingEnabled = true
                    else -> locationPermLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }
            },
            modifier = Modifier.size(48.dp),   // тач-цель ≥48dp
            shape = RoundedCornerShape(14.dp),
            color = CanonSurface,   // адаптивно: белая кнопка была нечитаема-инородна в тёмной теме
            shadowElevation = CanonDepth.raised
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    // Запрет геолокации → зачёркнутая стрелка; включил в Профиль→Конфиденциальность → обычная.
                    if (LocationPrefs.sharingEnabled) Icons.Default.NearMe else Icons.Default.NearMeDisabled,
                    contentDescription = if (LocationPrefs.sharingEnabled) appText("Где я", "Мин ҡайҙа") else appText("Геолокация выключена", "Геолокация һүнгән"),
                    tint = if (LocationPrefs.sharingEnabled) CanonGreen2 else CanonMutedStrong,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        // Кнопки масштаба (как в Яндекс.Картах): правый верх, под чипом расстояния.
        MapZoomControls(
            onZoomIn = {
                val cam = mapView.mapWindow.map.cameraPosition
                val t = if (LocationPrefs.sharingEnabled) (lastUserPoint ?: cam.target) else cam.target
                mapView.mapWindow.map.move(CameraPosition(t, (cam.zoom + 1f).coerceAtMost(18f), cam.azimuth, cam.tilt), Animation(Animation.Type.SMOOTH, 0.25f), null)
            },
            onZoomOut = {
                val cam = mapView.mapWindow.map.cameraPosition
                val t = if (LocationPrefs.sharingEnabled) (lastUserPoint ?: cam.target) else cam.target
                mapView.mapWindow.map.move(CameraPosition(t, (cam.zoom - 1f).coerceAtLeast(3f), cam.azimuth, cam.tilt), Animation(Animation.Type.SMOOTH, 0.25f), null)
            }
        )
        }   // конец правого блока управления картой
        if (showPrivacyNotice && selectedRequest == null) {
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = CanonSurface),
                shape = RoundedCornerShape(22.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.card)
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = CanonMint, shape = CircleShape) {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(8.dp).size(16.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        appText("Геолокация откроется после подтверждения поездки", "Геолокация сәфәр раҫланғас асыла"),
                        color = CanonText, fontSize = 14.sp, lineHeight = 20.sp
                    )
                }
            }
        }
        // Карточка заявки попутчика (тап по оранжевому маркеру) — кто ищет попутку + «Откликнуться».
        selectedRequest?.let { req ->
            RequestPreviewCard(
                req = req,
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                onClose = { selectedRequest = null }
            )
        }
        // DEBUG-ТОЛЬКО: симуляция движущегося попутчика — посмотреть, как едет нав-стрелка без 2-го
        // телефона. В релизе кнопки нет (BuildConfig.DEBUG=false). Тапни поездку → ▶ Симуляция: фейк-машина
        // едет по дорожному маршруту, стрелка крутится по направлению, камера следом.
        if (BuildConfig.DEBUG) {
            var simJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
            val simMap = mapView.mapWindow.map
            Surface(
                onClick = {
                    simJob?.let { it.cancel(); simJob = null; com.yuldash.app.data.TripLocationBus.peer = null; return@Surface }
                    val a = previewRide?.let { cityPoint(it.from) } ?: activeTrip?.let { cityPoint(it.from) } ?: cityPoint("Уфа") ?: Point(54.7388, 55.9721)
                    val b = previewRide?.let { cityPoint(it.to) } ?: activeTrip?.let { cityPoint(it.to) } ?: cityPoint("Сибай") ?: Point(52.6900, 58.6700)
                    simJob = tripScope.launch {
                        val (raw, totalSec) = roadRoutePoints(a, b)
                        val road = if (raw.size >= 2) raw else listOf(a, b)   // ПОЛНАЯ геометрия дороги — без прорежения
                        // Длины сегментов + полная длина → едем с ПОСТОЯННОЙ скоростью по длине (а не по вершинам).
                        val seg = DoubleArray(road.size - 1) { geoMeters(road[it], road[it + 1]) }
                        val total = seg.sum().coerceAtLeast(1.0)
                        val line = simMap.mapObjects.addPolyline(Polyline(road)).apply { setStrokeColor(ROUTE_STROKE_ARGB.toInt()); strokeWidth = 5f }
                        try {
                            simMap.move(CameraPosition(road.first(), 11.5f, 0f, 0f), Animation(Animation.Type.SMOOTH, 0.5f), null)
                            val durMs = 30000.0      // весь маршрут ~30с (спокойный круиз)
                            val stepMs = 40L         // подача ~25 точек/с, chaser догладит до 60fps
                            var elapsed = 0.0; var tick = 0
                            while (elapsed <= durMs) {
                                val frac = (elapsed / durMs).coerceIn(0.0, 1.0)
                                val targetDist = frac * total
                                // Найти сегмент по пройденной длине + точную точку НА дороге (интерполяция вдоль сегмента).
                                var acc = 0.0; var si = 0
                                while (si < seg.size - 1 && acc + seg[si] < targetDist) { acc += seg[si]; si++ }
                                val pA = road[si]; val pB = road[si + 1]
                                val t = if (seg[si] > 0.0) ((targetDist - acc) / seg[si]).coerceIn(0.0, 1.0) else 0.0
                                val lat = pA.latitude + (pB.latitude - pA.latitude) * t
                                val lng = pA.longitude + (pB.longitude - pA.longitude) * t
                                val brg = bearingBetween(pA, pB)   // нос — строго по касательной дороги (направление сегмента)
                                com.yuldash.app.data.TripLocationBus.peer = com.yuldash.app.data.LocationSocket.Peer("driver", lat, lng, brg, elapsed.toLong())
                                if (totalSec > 0) liveRemainSec = (totalSec * (1.0 - frac)).toInt()
                                if (tick % 4 == 0) simMap.move(CameraPosition(Point(lat, lng), simMap.cameraPosition.zoom, 0f, 0f), Animation(Animation.Type.SMOOTH, 0.32f), null)   // следование чаще+мягче
                                tick++
                                kotlinx.coroutines.delay(stepMs); elapsed += stepMs
                            }
                        } finally {
                            runCatching { simMap.mapObjects.remove(line) }
                            com.yuldash.app.data.TripLocationBus.peer = null
                            liveRemainSec = null
                            simJob = null
                        }
                    }
                },
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp).zIndex(8f),
                shape = RoundedCornerShape(14.dp),
                color = CanonGreen2,
                shadowElevation = CanonDepth.raised
            ) {
                Text(
                    if (simJob != null) appText("⏹ Стоп", "⏹ Туҡта") else appText("▶ Симуляция", "▶ Демо"),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp
                )
            }
        }
    }
}

// Карточка заявки попутчика (тап по маркеру «ищет попутку» на карте): имя, маршрут, места, комментарий.
// «Откликнуться» → диалог с ценой/комментом → respondToRequest. Телефон пассажира не показываем (приватность).
// internal (не private): базовый рендер карточки чист (текст+колбэки), покрыт Robolectric.
// Сеть (ApiClient) живёт ТОЛЬКО внутри диалога-отклика — тесты его не открывают.
@Composable
internal fun RequestPreviewCard(
    req: com.yuldash.app.data.RequestNearDto,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var respondOpen by remember { mutableStateOf(false) }
    var price by remember { mutableStateOf("") }
    var comment by remember { mutableStateOf("") }
    // Тексты тостов считаем заранее — appText @Composable, внутри scope.launch его звать нельзя.
    val sentMsg = appText("Отклик отправлен", "Яуап ебәрелде")
    val failMsg = appText("Не удалось отправить. Повтори.", "Ебәреп булманы. Ҡабатла.")

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = RoundedCornerShape(22.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.sheet)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Аватар-кружок вместо голой иконки: карточка сразу читается как «человек», а не как плашка.
                Surface(color = CanonMint, shape = CircleShape) {
                    Icon(Icons.Default.Person, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(8.dp).size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(req.passengerName, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(appText("ищет попутку", "юлдаш эҙләй"), color = CanonMuted, fontSize = 12.sp, maxLines = 1)
                }
            }
            Text("${req.fromCity}  →  ${req.toCity}", color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = buildList {
                if (req.seats > 0) add(seatsText(req.seats))
                req.distanceKm?.let { add(appText("≈ ${it.toInt()} км рядом", "≈ ${it.toInt()} км яҡын")) }
            }.joinToString("  ·  ")
            if (meta.isNotBlank()) Text(meta, color = CanonMuted, fontSize = 12.sp)
            if (req.comment.isNotBlank()) Text(req.comment, color = CanonMutedStrong, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { respondOpen = true },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2),
                    shape = RoundedCornerShape(14.dp)
                ) { Text(appText("Откликнуться", "Яуап бирергә"), fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1) }
                TextButton(onClick = onClose) { Text(appText("Закрыть", "Ябырға"), color = CanonMuted, fontSize = 16.sp) }
            }
        }
    }

    if (respondOpen) {
        AlertDialog(
            onDismissRequest = { respondOpen = false },
            title = { Text(appText("Отклик на заявку", "Заявкаға яуап")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${req.fromCity} → ${req.toCity}", color = CanonMuted, fontSize = 14.sp)
                    OutlinedTextField(
                        value = price,
                        onValueChange = { v -> price = v.filter { it.isDigit() }.take(7) },
                        label = { Text(appText("Цена, ₽", "Хаҡ, ₽")) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                    )
                    OutlinedTextField(
                        value = comment,
                        onValueChange = { comment = it.take(RIDE_COMMENT_MAX) },   // сервер принимает 2000
                        label = { Text(appText("Комментарий (необяз.)", "Аңлатма (мәжбүри түгел)")) },
                        minLines = 2
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val rid = req.id; val p = price.toIntOrNull() ?: 0; val c = comment.trim()
                    scope.launch {
                        ApiClient.respondToRequest(rid, p, c)
                            .onSuccess { Toast.makeText(ctx, sentMsg, Toast.LENGTH_SHORT).show() }
                            .onFailure { Toast.makeText(ctx, serverSaid(it, failMsg), Toast.LENGTH_LONG).show() }
                    }
                    respondOpen = false; onClose()
                }) { Text(appText("Отправить", "Ебәреү"), color = CanonGreen2, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { respondOpen = false }) { Text(appText("Отмена", "Баш тартыу"), color = CanonMuted) } }
        )
    }
}

// --- DEBUG-симуляция движущегося попутчика (демо нав-стрелки без 2-го телефона) ---
// Точки дорожного маршрута A→B + полное время в пути (сек). Для демо-движения и живого ETA.
// Ошибка/квота → прямая [from,to] и время 0 (тогда ETA не показываем).
private suspend fun roadRoutePoints(from: Point, to: Point): Pair<List<Point>, Double> =
    kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val session = runCatching {
            val router = com.yandex.mapkit.directions.DirectionsFactory.getInstance()
                .createDrivingRouter(com.yandex.mapkit.directions.driving.DrivingRouterType.COMBINED)
            val reqPoints = listOf(
                com.yandex.mapkit.RequestPoint(from, com.yandex.mapkit.RequestPointType.WAYPOINT, null, null, null),
                com.yandex.mapkit.RequestPoint(to, com.yandex.mapkit.RequestPointType.WAYPOINT, null, null, null),
            )
            router.requestRoutes(
                reqPoints, com.yandex.mapkit.directions.driving.DrivingOptions(), com.yandex.mapkit.directions.driving.VehicleOptions(),
                object : com.yandex.mapkit.directions.driving.DrivingSession.DrivingRouteListener {
                    override fun onDrivingRoutes(routes: MutableList<com.yandex.mapkit.directions.driving.DrivingRoute>) {
                        val r = routes.firstOrNull()
                        if (done.compareAndSet(false, true)) cont.resumeWith(Result.success(Pair(r?.geometry?.points ?: listOf(from, to), r?.metadata?.weight?.time?.value ?: 0.0)))
                    }
                    override fun onDrivingRoutesError(error: com.yandex.runtime.Error) {
                        if (done.compareAndSet(false, true)) cont.resumeWith(Result.success(Pair(listOf(from, to), 0.0)))
                    }
                }
            )
        }.getOrNull()
        cont.invokeOnCancellation { runCatching { session?.cancel() } }
    }

// Пикер точки сбора: полноэкранная карта + фикс-пин в центре. Двигаешь карту — пин на месте встречи.
@Composable
internal fun PickupPickerOverlay(
    initial: Point?,
    onConfirm: (Double, Double) -> Unit,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    val mapView = remember {
        ensureMapKit(ctx)   // русская локаль + initialize, ровно один раз на процесс
        MapView(ctx).also { v ->
            v.mapWindow.map.move(CameraPosition(initial ?: MapMidPoint, if (initial != null) 15f else 11f, 0f, 0f))
        }
    }
    DisposableEffect(Unit) {
        MapKitFactory.getInstance().onStart(); mapView.onStart()
        onDispose { mapView.onStop(); MapKitFactory.getInstance().onStop() }
    }
    Box(Modifier.fillMaxSize().background(CanonBg)) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        // фикс-пин в центре (кончик смотрит на центр карты)
        Icon(
            Icons.Default.LocationOn, contentDescription = null, tint = CanonRed,
            modifier = Modifier.align(Alignment.Center).size(48.dp).offset(y = (-24).dp)
        )
        Row(
            Modifier.align(Alignment.TopStart).fillMaxWidth().statusBarsPadding().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(modifier = Modifier.minimumInteractiveComponentSize(), onClick = onDismiss, shape = CircleShape, color = CanonSurface, shadowElevation = CanonDepth.raised) {
                Icon(Icons.Default.ArrowBackIosNew, contentDescription = appText("Назад", "Кире"), tint = CanonText, modifier = Modifier.padding(12.dp).size(18.dp))
            }
            Spacer(Modifier.width(8.dp))
            Surface(shape = RoundedCornerShape(14.dp), color = CanonSurface, shadowElevation = CanonDepth.raised) {
                Text(appText("Двигай карту — пин на месте встречи", "Картаны күсер — пин осрашыу урынында"), Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = CanonText, fontSize = 14.sp)
            }
        }
        Button(
            onClick = {
                val t = mapView.mapWindow.map.cameraPosition.target
                onConfirm(t.latitude, t.longitude)
            },
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(16.dp).heightIn(min = 54.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)
        ) {
            Icon(Icons.Default.LocationOn, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(appText("Готово — точка здесь", "Әҙер — нөктә бында"), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
internal fun MapPreview(modifier: Modifier = Modifier, from: String = "Баймаҡ", to: String = "Сибай", distance: String? = "43 км") {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                // Запасная «мок-карта» (без ключа MapKit) — цвета из Canon-токенов, адаптивны к тёмной теме.
                Brush.linearGradient(listOf(CanonMint, CanonBg, CanonGold.copy(alpha = 0.20f))),
                CanonCardShape
            )
            .border(1.dp, CanonBorder, CanonCardShape)
            .padding(0.dp)
    ) {
        // Canon-токены — @Composable-значения; читаем их ДО Canvas (DrawScope не композабл-контекст).
        val routeColor = CanonGreen2
        val destColor = CanonGold
        val casingColor = CanonSurface   // «обводка» дороги — фон-подложка, адаптивна к тёмной теме
        val decorColor = CanonBorder     // декоративные штрихи/пятна
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(decorColor, radius = 170f, center = Offset(size.width * 0.05f, size.height * 0.12f))
            drawCircle(routeColor.copy(alpha = 0.08f), radius = 210f, center = Offset(size.width * 0.95f, size.height * 0.88f))
            val route = Path().apply {
                moveTo(size.width * 0.16f, size.height * 0.28f)
                cubicTo(
                    size.width * 0.36f,
                    size.height * 0.18f,
                    size.width * 0.54f,
                    size.height * 0.62f,
                    size.width * 0.84f,
                    size.height * 0.68f
                )
            }
            val sideRoad = Path().apply {
                moveTo(size.width * 0.02f, size.height * 0.58f)
                cubicTo(size.width * 0.28f, size.height * 0.50f, size.width * 0.48f, size.height * 0.38f, size.width * 0.74f, size.height * 0.18f)
            }
            drawPath(sideRoad, casingColor.copy(alpha = 0.75f), style = Stroke(width = 11f, cap = StrokeCap.Round))
            drawPath(sideRoad, routeColor.copy(alpha = 0.45f), style = Stroke(width = 3f, cap = StrokeCap.Round))
            drawPath(route, casingColor, style = Stroke(width = 22f, cap = StrokeCap.Round))
            drawPath(route, routeColor, style = Stroke(width = 7f, cap = StrokeCap.Round))
            drawCircle(routeColor, radius = 15f, center = Offset(size.width * 0.16f, size.height * 0.28f))
            drawCircle(destColor, radius = 15f, center = Offset(size.width * 0.84f, size.height * 0.68f))
        }
        MapLabel(from, Modifier.align(Alignment.TopStart).padding(16.dp))
        MapLabel(to, Modifier.align(Alignment.CenterEnd).padding(16.dp))
        distance?.let { dist ->
            Surface(
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
                color = CanonSurface.copy(alpha = 0.92f),
                shape = RoundedCornerShape(999.dp)
            ) {
                Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.yu_route), contentDescription = null, modifier = Modifier.size(16.dp), tint = CanonGreen2)
                    Spacer(Modifier.width(8.dp))
                    Text(dist, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = CanonText)
                }
            }
        }
        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
            colors = CardDefaults.cardColors(containerColor = CanonSurface),
            shape = RoundedCornerShape(22.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.card)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(color = CanonMint, shape = CircleShape) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(8.dp).size(16.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    appText("Геолокация откроется после подтверждения поездки", "Геолокация сәфәр раҫланғас асыла"),
                    color = CanonText, fontSize = 14.sp, lineHeight = 20.sp
                )
            }
        }
    }
}

// Ярлык города на карте-заглушке — чистый (Surface+Text), internal → покрыт Robolectric.
@Composable
internal fun MapLabel(text: String, modifier: Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = CanonSurface,   // адаптивно: белый ярлык на тёмной карте заменён на surface темы
        shadowElevation = CanonDepth.raised
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            fontWeight = FontWeight.Bold, fontSize = 14.sp, color = CanonText
        )
    }
}

// Кнопки масштаба карты ＋/− (стек справа, как в Яндекс.Картах).
// Чистые: сам зум делает вызывающий через колбэки (карта тут не упоминается) → internal, покрыто Robolectric.
@Composable
internal fun MapZoomControls(modifier: Modifier = Modifier, onZoomIn: () -> Unit, onZoomOut: () -> Unit) {
    Surface(modifier = modifier, color = CanonSurface, shape = RoundedCornerShape(14.dp), shadowElevation = CanonDepth.raised) {   // адаптивно (тёмная тема): было хардкод-белое
        Column {
            IconButton(onClick = onZoomIn, modifier = Modifier.size(48.dp)) {   // тач-цель ≥48dp (a11y §4.5)
                Icon(Icons.Default.Add, contentDescription = appText("Приблизить", "Яҡынайтыу"), tint = CanonGreen2, modifier = Modifier.size(20.dp))
            }
            Box(Modifier.width(24.dp).height(1.dp).background(CanonBorder).align(Alignment.CenterHorizontally))
            IconButton(onClick = onZoomOut, modifier = Modifier.size(48.dp)) {   // тач-цель ≥48dp (a11y §4.5)
                Icon(Icons.Default.Remove, contentDescription = appText("Отдалить", "Йыраҡлаштырыу"), tint = CanonGreen2, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/**
 * Плашка активного маршрута до партнёра.
 *
 * Вынесена в отдельную функцию НЕ ради красоты: на месте вызова `AnimatedVisibility` стоит
 * внутри `Box`, но лексически выше есть `Column`, и компилятор выбирал `ColumnScope`-версию,
 * для которой получателя в этой точке нет — сборка падала с «cannot be called in this context
 * with an implicit receiver». Здесь ни `Column`, ни `Row` в области видимости нет, поэтому
 * резолвится обычная версия. Позиционирование приходит извне через [modifier].
 */
@Composable
private fun MapAdRouteBanner(
    adRoute: PartnerAd?,
    onClearRoute: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = adRoute != null,
        enter = fadeIn(tween(CanonMotion.QUICK)) + slideInVertically(tween(CanonMotion.NORMAL)) { -it },
        exit = fadeOut(tween(CanonMotion.QUICK)) + slideOutVertically(tween(CanonMotion.QUICK)) { -it },
        modifier = modifier,
    ) {
        Surface(
            color = CanonSurface,
            shape = RoundedCornerShape(14.dp),
            shadowElevation = CanonDepth.raised,
            border = BorderStroke(1.dp, CanonHairlineGreen),
        ) {
            Row(
                modifier = Modifier.heightIn(min = 48.dp).padding(start = 12.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Default.Directions, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(20.dp))
                Text(
                    appText("Маршрут · ${adRoute?.title ?: ""}", "Маршрут · ${adRoute?.titleBa ?: adRoute?.title ?: ""}"),
                    color = CanonText, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp),
                )
                Surface(
                    onClick = onClearRoute,
                    shape = CircleShape,
                    color = CanonMint,
                    modifier = Modifier.minimumInteractiveComponentSize().size(32.dp),
                ) {
                    Icon(Icons.Default.Close, contentDescription = appText("Сбросить маршрут", "Маршрутты бетереү"), tint = CanonGreen2, modifier = Modifier.padding(8.dp))
                }
            }
        }
    }
}
