package com.yuldash.app

// Вкладка «Карта»: MapScreen + Яндекс MapKit (YandexMapCard, MapHero, маркеры-ценники,
// геолокация, контролы). Вынесено из MainActivity (Фаза 3). Импорты целиком — лишние = варнинги.

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
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.VolunteerActivism
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.material.icons.filled.NearMe
import com.yandex.mapkit.map.IconStyle
import com.yandex.mapkit.map.MapObjectTapListener
import com.yandex.mapkit.mapview.MapView
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
    onSimpleMode: () -> Unit,
    onOpenPopular: (PopularRoute) -> Unit,
    onDriver: () -> Unit,
    onBoost: () -> Unit
) {
    val nearbyAd = ads.forPlacement(AdPlacement.Nearby).firstOrNull { it.city == "Баймаҡ" }
    var selectedRide by remember { mutableStateOf<Ride?>(null) }
    val sheetState = rememberModalBottomSheetState()
    // Ближайшие поездки: маршрут клиента (активная поездка → её маршрут) + сортировка по времени выезда + гео-дистанция.
    var nearby by remember { mutableStateOf<List<com.yuldash.app.data.RideDto>>(emptyList()) }
    var nearbyLoading by remember { mutableStateOf(true) }
    var nearbyError by remember { mutableStateOf(false) }   // отличаем «нет сети» от «нет поездок»
    var nearbyReload by remember { mutableStateOf(0) }
    var nearbyTotal by remember { mutableStateOf(0) }       // всего на маршруте (для «Показать ещё»)
    var nearbyLimit by remember { mutableStateOf(NEARBY_PAGE) }  // сколько показываем сейчас
    var prefFilter by remember { mutableStateOf(setOf<String>()) }  // фильтр «Ближайших» по условиям поездки
    val focusFrom = activeTrip?.from
    val focusTo = activeTrip?.to
    val userLat = LocationPrefs.lastLat   // читаем в локальные val → подписка на изменение позиции
    val userLng = LocationPrefs.lastLng
    // Сбрасываем страницу при смене маршрута/позиции (новый контекст → снова с начала).
    LaunchedEffect(focusFrom, focusTo, userLat, userLng) { nearbyLimit = NEARBY_PAGE }
    LaunchedEffect(focusFrom, focusTo, userLat, userLng, nearbyReload, nearbyLimit) {
        nearbyLoading = true
        // Радиус применяем только когда знаем позицию (иначе показываем все по маршруту/времени).
        val radius = if (userLat != null && userLng != null) NEARBY_RADIUS_KM else null
        ApiClient.getNearbyRidesPaged(focusFrom, focusTo, userLat, userLng, radius, nearbyLimit)
            .onSuccess { nearby = it.items; nearbyTotal = it.total; nearbyError = false }
            .onFailure { nearbyError = true }
        nearbyLoading = false
    }
    // Клиентская фильтрация «Ближайших» по выбранным условиям (поля уже пришли в RideDto).
    // remember: пересчитываем только при смене списка/фильтра, а не на каждой рекомпозиции экрана.
    val shownNearby = remember(nearby, prefFilter) {
        if (prefFilter.isEmpty()) nearby else nearby.filter { d ->
            ("women" !in prefFilter || d.womenOnly) &&
                ("child" !in prefFilter || d.childSeat) &&
                ("pets" !in prefFilter || d.petsAllowed) &&
                ("baggage" !in prefFilter || d.baggage)
        }
    }
    Scaffold(containerColor = CanonBg) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            // Закреплённый верх: шапка + карта (НЕ в прокрутке → вертикальный пан двигает карту, а не страницу).
            Column(modifier = Modifier.padding(horizontal = 14.dp)) {
                Spacer(Modifier.height(2.dp))
                Box(Modifier.appearIn(0)) { HomeHeader(onSos = onSos) }
                Spacer(Modifier.height(11.dp))
                Box(Modifier.appearIn(1)) {
                    MapHero(
                        activeTrip = activeTrip,
                        onRideTap = { selectedRide = it },
                        onFind = onOpenPopular,
                        onDriver = onDriver
                    )
                }
            }
            // Прокручиваемый низ: простой режим, ближайшие поездки, реклама.
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp),
                contentPadding = PaddingValues(top = 11.dp, bottom = 8.dp)
            ) {
                item {
                    Box(Modifier.appearIn(3)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(appText("Ближайшие поездки", "Яҡындағы сәфәрҙәр"), fontSize = 16.sp, fontWeight = FontWeight.Black)
                                if (focusFrom != null && focusTo != null) {
                                    Text("$focusFrom → $focusTo", color = CanonGreen2, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            if (nearby.isNotEmpty()) {
                                Text(appText("${shownNearby.size} рядом", "${shownNearby.size} яҡында"), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }
                }
                item {
                    if (nearby.isNotEmpty()) {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            NearbyFilterChip(Icons.Default.Woman, appText("Только женщины", "Тик ҡатын-ҡыҙ"), "women" in prefFilter) { prefFilter = if ("women" in prefFilter) prefFilter - "women" else prefFilter + "women" }
                            NearbyFilterChip(Icons.Default.ChildCare, appText("Детское кресло", "Балалар ултырғысы"), "child" in prefFilter) { prefFilter = if ("child" in prefFilter) prefFilter - "child" else prefFilter + "child" }
                            NearbyFilterChip(Icons.Default.Pets, appText("С животным", "Хайуан менән"), "pets" in prefFilter) { prefFilter = if ("pets" in prefFilter) prefFilter - "pets" else prefFilter + "pets" }
                            NearbyFilterChip(Icons.Default.Luggage, appText("Багаж", "Багаж"), "baggage" in prefFilter) { prefFilter = if ("baggage" in prefFilter) prefFilter - "baggage" else prefFilter + "baggage" }
                        }
                    }
                }
                item {
                    Box(Modifier.appearIn(4)) {
                        when {
                            nearbyLoading && nearby.isEmpty() -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                NearbySkeletonCard(); NearbySkeletonCard()
                            }
                            nearby.isEmpty() -> NearbyEmptyCard(hasRoute = focusFrom != null, onRetry = { nearbyReload++ }, error = nearbyError)
                            shownNearby.isEmpty() -> Text(
                                appText("Нет поездок с такими условиями. Снимите часть фильтров.", "Был шарттар менән сәфәр юҡ. Фильтрҙың бер өлөшөн алығыҙ."),
                                color = CanonMuted, fontSize = 14.sp, lineHeight = 19.sp
                            )
                            else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                itemsIndexed(shownNearby, key = { _, dto -> dto.id }) { i, dto ->
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
                nearbyAd?.let { ad ->
                    item {
                        Box(Modifier.appearIn(5)) {
                            PartnerAdCard(
                                ad = ad,
                                stats = adStats[ad.id] ?: AdStats(),
                                label = appText("Партнёр рядом", "Яҡындағы партнёр"),
                                onImpression = onAdImpression,
                                onClick = onAdClick
                            )
                        }
                    }
                }
            }
        }
    }
    // Тап по маркеру поездки → карточка снизу с деталями и действиями.
    selectedRide?.let { ride ->
        ModalBottomSheet(
            onDismissRequest = { selectedRide = null },
            sheetState = sheetState,
            containerColor = CanonSurface
        ) {
            Column(Modifier.padding(horizontal = 14.dp).padding(bottom = 24.dp)) {
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

@Composable
private fun MapHero(
    activeTrip: Ride?,
    onRideTap: (Ride) -> Unit,
    onFind: (PopularRoute) -> Unit,
    onDriver: () -> Unit
) {
    // Популярные маршруты — порядок с сервера (из реальных поездок); демо для богатого вида.
    var popular by remember { mutableStateOf(demoPopularRoutes) }
    LaunchedEffect(Unit) {
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
    // Живые цифры ленты (поездок за день/неделю/месяц/год + топ-маршрут) — с сервера.
    var liveFeed by remember { mutableStateOf<FeedDto?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            ApiClient.getFeed().onSuccess { liveFeed = it }
            delay(60_000)
        }
    }
    var cardCollapsed by remember { mutableStateOf(false) }
    var activeRoute by remember { mutableStateOf<PopularRoute?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(350.dp)
        ) {
            if (BuildConfig.YANDEX_MAPKIT_KEY.isNotBlank()) {
                YandexMapCard(
                    modifier = Modifier.matchParentSize(),
                    activeTrip = activeTrip,
                    onRideTap = onRideTap,
                    showPrivacyNotice = false
                )
            } else {
                MapPreview(Modifier.matchParentSize())
            }
            // Подсказка-маршрут плавает в нижней части карты: свайп вправо → язычок, тап → назад.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(10.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    AnimatedVisibility(
                        visible = !cardCollapsed,
                        enter = slideInHorizontally { it } + fadeIn(),
                        exit = slideOutHorizontally { it } + fadeOut()
                    ) {
                        QuickSearchCard(
                            feed = remember(popular, liveFeed) { mapFeedFrom(popular, liveFeed) },
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
                            shape = RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp),
                            color = CanonSurface,
                            shadowElevation = 4.dp
                        ) {
                            Icon(
                                Icons.Default.ArrowBackIosNew,
                                contentDescription = appText("Показать популярный маршрут", "Популяр маршрутты күрһәтеү"),
                                tint = CanonGreen2,
                                modifier = Modifier.padding(vertical = 14.dp, horizontal = 12.dp).size(16.dp)
                            )
                        }
                    }
                }
            }
        }
        // Кнопки — отдельный блок ПОД картой (не плавают на ней).
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { onFind(activeRoute ?: popular.firstOrNull() ?: demoPopularRoutes.first()) },
                modifier = Modifier.weight(1.25f).height(52.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2),
                contentPadding = PaddingValues(horizontal = 10.dp)
            ) {
                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(6.dp))
                Text(appText("Найти поездку", "Сәфәр табыу"), fontWeight = FontWeight.Black, fontSize = 14.sp, maxLines = 1)
            }
            Button(
                onClick = onDriver,
                modifier = Modifier.weight(0.95f).height(52.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CanonGold, contentColor = CanonGoldInk),
                contentPadding = PaddingValues(horizontal = 10.dp)
            ) {
                Icon(Icons.Default.DirectionsCar, contentDescription = null, tint = CanonGoldInk, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(6.dp))
                Text(appText("Я водитель", "Мин водитель"), fontWeight = FontWeight.Black, fontSize = 13.sp, maxLines = 1)
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
        Column(Modifier.weight(1f)) {
            Text(timeGreeting(ApiClient.cachedName() ?: "друг"), color = CanonMuted, fontSize = 14.sp)
            Text(
                appText("Куда поедем?", "Ҡайҙа барабыҙ?"),
                color = CanonGreen,
                fontSize = 28.sp,
                lineHeight = 29.sp,
                fontWeight = FontWeight.Black
            )
        }
        // Тумблер день/ночь: иконка солнца в тёмной теме (тап → светлая), луны в светлой (тап → тёмная).
        val themeCtx = LocalContext.current
        val isDarkNow = appIsDark()
        Surface(
            modifier = Modifier
                .bounceClick {
                    val newDark = !isDarkNow
                    ThemePrefs.darkOverride = newDark
                    themeCtx.getSharedPreferences("yuldash_theme", Context.MODE_PRIVATE)
                        .edit().putBoolean("dark_override", newDark).apply()
                }
                .size(44.dp),
            shape = RoundedCornerShape(16.dp),
            color = CanonSurface,
            border = BorderStroke(1.dp, CanonBorder)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    if (isDarkNow) Icons.Default.LightMode else Icons.Default.DarkMode,
                    contentDescription = appText(
                        if (isDarkNow) "Светлая тема" else "Тёмная тема",
                        if (isDarkNow) "Яҡты тема" else "Ҡараңғы тема"
                    ),
                    tint = CanonGreen2,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Surface(
            modifier = Modifier.bounceClick(onSos),
            shape = RoundedCornerShape(16.dp),
            color = CanonDangerBg,
            border = BorderStroke(1.dp, Color(0xFFFFC8C0))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Shield, contentDescription = "SOS", tint = CanonRed, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text("SOS", color = CanonRed, fontWeight = FontWeight.Black, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun QuickSearchCard(
    feed: List<MapFeedCard>,
    onCollapse: () -> Unit,
    onRouteChange: (PopularRoute) -> Unit,
    compact: Boolean = false
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
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(if (compact) 12.dp else 14.dp)) {
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
                }
                // Единый макет: бейдж+пилюля (верх) · заголовок фикс.высоты · подпись+точки (низ).
                // Фикс. высота заголовка → все карточки ровно одного размера, карусель не «прыгает».
                Column(verticalArrangement = Arrangement.spacedBy(if (compact) 7.dp else 9.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = CanonMint, shape = RoundedCornerShape(999.dp)) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    appText(card.badge, card.badgeBa),
                                    color = CanonGreen2, fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        Surface(color = CanonMint, shape = RoundedCornerShape(14.dp)) {
                            Text(
                                appText(card.pill, card.pillBa),
                                modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                                color = CanonGreen, fontWeight = FontWeight.Black,
                                fontSize = 13.sp, maxLines = 1
                            )
                        }
                    }
                    Box(
                        modifier = Modifier.height(if (compact) 46.dp else 54.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            appText(card.title, card.titleBa),
                            color = CanonGreen, fontWeight = FontWeight.Black,
                            fontSize = if (compact) 19.sp else 23.sp,
                            lineHeight = if (compact) 22.sp else 26.sp,
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
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            val active = pagerState.currentPage % count
                            safeFeed.forEachIndexed { index, _ ->
                                Box(
                                    modifier = Modifier
                                        .size(if (index == active) 7.dp else 6.dp)
                                        .background(if (index == active) CanonGreen2 else CanonBorder, CircleShape)
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
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = BorderStroke(1.dp, Color(0x1A0B6B3A))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(color = CanonMint, shape = CircleShape) {
                Icon(
                    Icons.Default.VolumeUp,
                    contentDescription = null,
                    tint = CanonGreen2,
                    modifier = Modifier.padding(8.dp).size(20.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    appText("Простой режим", "Ябай режим"),
                    color = CanonText,
                    fontWeight = FontWeight.Black,
                    fontSize = 15.sp,
                    lineHeight = 18.sp
                )
                Text(
                    appText("Крупные кнопки и голос", "Ҙур төймәләр һәм тауыш"),
                    color = CanonMuted,
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Switch(
                checked = false,
                onCheckedChange = { onSimpleMode() }
            )
        }
    }
}

// Координаты для карты (Башкортостан). Старт — Баймаҡ, финиш — Сибай.
private val BaymakPoint = Point(52.5911, 58.3222)
private val SibayPoint = Point(52.7236, 58.6651)
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

// Город → точка: частые города Башкортостана мгновенно; прочие догружает геокодер (см. YandexMapCard).
private fun cityPoint(city: String): Point? = when (city.trim().lowercase()) {
    "баймаҡ", "баймак" -> BaymakPoint
    "сибай" -> SibayPoint
    "темясово" -> Point(52.9686, 58.3206)
    "уфа" -> Point(54.7388, 55.9721)
    "учалы" -> Point(54.3050, 59.4040)
    "магнитогорск" -> Point(53.4072, 58.9794)
    "стерлитамак" -> Point(53.6303, 55.9311)
    "салават" -> Point(53.3617, 55.9244)
    "нефтекамск" -> Point(56.0911, 54.2486)
    "октябрьский" -> Point(54.4817, 53.4708)
    "белорецк" -> Point(53.9694, 58.4097)
    "ишимбай" -> Point(53.4528, 56.0386)
    "туймазы" -> Point(54.6014, 53.6947)
    "кумертау" -> Point(52.7639, 55.7964)
    else -> null
}

// Маркер-«ценник» (стиль Яндекс/Airbnb): белая пилюля с ценой, цветная рамка, остриё вниз.
// Boosted-поездка — золотой акцент, обычная — фирменный зелёный.
// Метка «моя геопозиция»: круглая точка (тень + белое кольцо + зелёный центр).
// Метка геопозиции постоянна → рисуем один раз и переиспользуем (GPS шлёт апдейты ~раз в 2с,
// без кеша это была новая Bitmap+Canvas+3 Paint на каждый апдейт → лишний GC и аллокации).
private var userPuckCache: Bitmap? = null
private fun userPuckBitmap(): Bitmap {
    userPuckCache?.let { return it }
    val size = 64
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val cx = size / 2f
    val cy = size / 2f
    c.drawCircle(cx, cy, 19f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.parseColor("#22000000") })
    c.drawCircle(cx, cy, 16f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE })
    c.drawCircle(cx, cy, 11f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.parseColor("#0B6B3A") })
    return bmp.also { userPuckCache = it }
}

// Флажок пункта назначения (точка Б) — зелёный вымпел на флагштоке. Якорь у основания.
// Тоже постоянный → кешируем.
private var destFlagCache: Bitmap? = null
private fun destFlagBitmap(): Bitmap {
    destFlagCache?.let { return it }
    val w = 50
    val h = 62
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val green = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.parseColor("#0B6B3A") }
    val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
    val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22000000 }
    c.drawOval(android.graphics.RectF(4f, h - 12f, 22f, h - 2f), shadow)   // тень у земли
    c.drawRect(11f, 8f, 14.5f, h - 6f, green)                              // флагшток
    // полотнище: белая кайма + зелёный вымпел
    c.drawPath(android.graphics.Path().apply { moveTo(14.5f, 6f); lineTo(46f, 16f); lineTo(14.5f, 28f); close() }, white)
    c.drawPath(android.graphics.Path().apply { moveTo(16f, 9.5f); lineTo(41f, 16f); lineTo(16f, 24.5f); close() }, green)
    c.drawCircle(12.7f, h - 6f, 5f, white)                                 // точка у основания
    c.drawCircle(12.7f, h - 6f, 3f, green)
    return bmp.also { destFlagCache = it }
}

// Ценник-маркер зависит только от (цена, boosted) → кешируем по ключу,
// чтобы при перерисовке/смене поездки не лепить заново Bitmap+Paint каждый раз.
private val ridePinCache = HashMap<String, Bitmap>()
private fun ridePinBitmap(price: String, boosted: Boolean): Bitmap {
    val cacheKey = "$price|$boosted"
    ridePinCache[cacheKey]?.let { return it }
    val accent = android.graphics.Color.parseColor(if (boosted) "#C98A00" else "#0B6B3A")
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent
        textSize = 34f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val padX = 22f
    val padY = 13f
    val pointer = 16f
    val pad = 6f // запас под тень
    val pillH = textPaint.textSize + padY * 2
    val pillW = textPaint.measureText(price) + padX * 2
    val bmp = Bitmap.createBitmap(
        (pillW + pad * 2).toInt(),
        (pillH + pointer + pad * 2).toInt(),
        Bitmap.Config.ARGB_8888
    )
    val c = Canvas(bmp)
    val left = pad; val right = pad + pillW
    val pillTop = pad + pointer; val pillBottom = pillTop + pillH
    val radius = pillH / 2
    val cx = (left + right) / 2
    val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
    val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f; color = accent
    }
    val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22000000 }
    // остриё СВЕРХУ (смотрит на город), пилюля под ним → цена ниже названия города
    val tip = android.graphics.Path().apply {
        moveTo(cx - pointer / 2, pillTop + 2f)
        lineTo(cx + pointer / 2, pillTop + 2f)
        lineTo(cx, pad)
        close()
    }
    c.drawRoundRect(left, pillTop + 3f, right, pillBottom + 3f, radius, radius, shadow)
    c.drawPath(tip, white)
    c.drawPath(tip, border)
    c.drawRoundRect(left, pillTop, right, pillBottom, radius, radius, white)
    c.drawRoundRect(left, pillTop, right, pillBottom, radius, radius, border)
    val fm = textPaint.fontMetrics
    val ty = pillTop + pillH / 2 - (fm.ascent + fm.descent) / 2
    c.drawText(price, left + padX, ty, textPaint)
    return bmp.also { ridePinCache[cacheKey] = it }
}

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
private fun ensureMapKit(context: Context) {
    if (mapKitReady) return
    mapKitReady = true
    runCatching { MapKitFactory.setLocale("ru_RU") }   // если уже инициализирован — пропускаем, не крашим
    MapKitFactory.initialize(context)
}

@Composable
private fun YandexMapCard(
    modifier: Modifier = Modifier,
    activeTrip: Ride? = null,
    onRideTap: (Ride) -> Unit = {},
    showPrivacyNotice: Boolean = true
) {
    val context = LocalContext.current
    // Геолокация управляется из Профиль → Конфиденциальность (общий LocationPrefs); FAB «к себе» тоже включает.
    var lastUserPoint by remember { mutableStateOf<Point?>(null) }
    val locationPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) LocationPrefs.sharingEnabled = true
    }
    val nightMap = appIsDark()   // тёмная тема → ночной стиль карты
    // Свежие ссылки на активную поездку/тап, чтобы tap-listener не «застревал» на старых данных.
    val currentTrip by rememberUpdatedState(activeTrip)
    val currentOnTap by rememberUpdatedState(onRideTap)
    val tapListener = remember {
        MapObjectTapListener { obj, _ ->
            val trip = currentTrip?.takeIf { it.id == obj.userData as? String }
            if (trip != null) currentOnTap(trip)
            trip != null
        }
    }
    val mapView = remember {
        ensureMapKit(context)   // setLocale+initialize ОДИН раз на процесс (повторный setLocale крашит)
        MapView(context).also { view ->
            val map = view.mapWindow.map
            map.isNightModeEnabled = nightMap
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
            if (reason == CameraUpdateReason.GESTURES && finished &&
                LocationPrefs.sharingEnabled && kotlin.math.abs(pos.zoom - prevZoom) > 0.05f) {
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
                val hit = GeocoderClient.suggest(k).firstOrNull() ?: return null
                return Point(hit.lat, hit.lon).also { cityCache[k] = it }
            }
            val fromPt = resolve(trip.from) ?: return@launch
            val toPt = resolve(trip.to)
            if (toPt != null) {
                val straightLine = map.mapObjects.addPolyline(Polyline(listOf(fromPt, toPt))).apply {
                    setStrokeColor(0xCC0B6B3A.toInt())
                    strokeWidth = 4f
                }
                added += straightLine
                // Маршрут ПО ДОРОГАМ (full SDK + DrivingRouter). Ошибка/нет квоты роутинга → остаётся прямая линия (фоллбэк, без поломки карты).
                roadSession = runCatching {
                    val router = com.yandex.mapkit.directions.DirectionsFactory.getInstance()
                        .createDrivingRouter(com.yandex.mapkit.directions.driving.DrivingRouterType.COMBINED)
                    val reqPoints = listOf(
                        com.yandex.mapkit.RequestPoint(fromPt, com.yandex.mapkit.RequestPointType.WAYPOINT, null, null, null),
                        com.yandex.mapkit.RequestPoint(toPt, com.yandex.mapkit.RequestPointType.WAYPOINT, null, null, null),
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
                                        setStrokeColor(0xCC0B6B3A.toInt()); strokeWidth = 5f
                                    }
                                }
                            }
                            override fun onDrivingRoutesError(error: com.yandex.runtime.Error) { /* фоллбэк: прямая остаётся */ }
                        }
                    )
                }.getOrNull()
                added += map.mapObjects.addPlacemark().apply {
                    geometry = toPt
                    setIcon(ImageProvider.fromBitmap(destFlagBitmap()))
                    setIconStyle(IconStyle().setAnchor(PointF(0.24f, 0.9f)))
                }
                map.move(
                    CameraPosition(Point((fromPt.latitude + toPt.latitude) / 2, (fromPt.longitude + toPt.longitude) / 2), 9.5f, 0f, 0f),
                    Animation(Animation.Type.SMOOTH, 0.5f), null
                )
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
        }
    }
    // Жизненный цикл карты привязан к появлению/скрытию экрана «Карта».
    DisposableEffect(Unit) {
        MapKitFactory.getInstance().onStart()
        mapView.onStart()
        onDispose {
            mapView.onStop()
            MapKitFactory.getInstance().onStop()
        }
    }
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .border(1.dp, Color(0x1A000000), RoundedCornerShape(24.dp))
    ) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        // Кнопки масштаба (как в Яндекс.Картах): правый верх, под чипом расстояния.
        MapZoomControls(
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 14.dp, end = 14.dp),
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
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 100.dp, end = 14.dp).size(38.dp).zIndex(6f),
            shape = RoundedCornerShape(13.dp),
            color = Color.White,
            shadowElevation = 4.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.NearMe,
                    contentDescription = appText("Где я", "Мин ҡайҙа"),
                    tint = if (LocationPrefs.sharingEnabled) CanonGreen2 else CanonMuted,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        if (showPrivacyNotice) {
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(14.dp),
                colors = CardDefaults.cardColors(containerColor = CanonSurface),
                shape = RoundedCornerShape(18.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(appText("Геолокация откроется после подтверждения поездки", "Геолокация сәфәр раҫланғас асыла"), fontSize = 15.sp)
                }
            }
        }
    }
}

@Composable
private fun MapMarkerHitTargets(rides: List<Ride>, onRideTap: (Ride) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().zIndex(5f)) {
        val byCity = remember(rides) { rides.groupBy { it.from } }
        byCity["Баймаҡ"]?.firstOrNull()?.let { ride ->
            Box(
                Modifier
                    .offset(x = maxWidth * 0.34f, y = maxHeight * 0.18f)
                    .size(width = 96.dp, height = 64.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onRideTap(ride) }
                    )
            )
        }
        byCity["Сибай"]?.firstOrNull()?.let { ride ->
            Box(
                Modifier
                    .offset(x = maxWidth * 0.62f, y = maxHeight * 0.39f)
                    .size(width = 112.dp, height = 72.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onRideTap(ride) }
                    )
            )
        }
    }
}

// Дистанция между городами по координатам (для превью маршрута). null — если город неизвестен.
internal fun cityDistanceText(from: String, to: String): String? {
    val a = cityPoint(from) ?: return null
    val b = cityPoint(to) ?: return null
    val sLat = Math.sin(Math.toRadians(b.latitude - a.latitude) / 2)
    val sLon = Math.sin(Math.toRadians(b.longitude - a.longitude) / 2)
    val h = sLat * sLat + Math.cos(Math.toRadians(a.latitude)) * Math.cos(Math.toRadians(b.latitude)) * sLon * sLon
    return "${Math.round(2 * 6371.0 * Math.asin(Math.sqrt(h)))} км"
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
        runCatching { MapKitFactory.initialize(ctx) }   // локаль уже задана при первой карте; повторный setLocale кинул бы исключение
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
            Surface(onClick = onDismiss, shape = CircleShape, color = CanonSurface, shadowElevation = 3.dp) {
                Icon(Icons.Default.ArrowBackIosNew, contentDescription = appText("Назад", "Кире"), tint = CanonText, modifier = Modifier.padding(12.dp).size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Surface(shape = RoundedCornerShape(14.dp), color = CanonSurface, shadowElevation = 3.dp) {
                Text(appText("Двигай карту — пин на месте встречи", "Картаны күсер — пин осрашыу урынында"), Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = CanonText, fontSize = 13.sp)
            }
        }
        Button(
            onClick = {
                val t = mapView.mapWindow.map.cameraPosition.target
                onConfirm(t.latitude, t.longitude)
            },
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(16.dp).height(54.dp),
            shape = RoundedCornerShape(16.dp),
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
                Brush.linearGradient(listOf(Color(0xFFDDEEDF), Color(0xFFEEF3E5), Color(0xFFFFE7AC))),
                RoundedCornerShape(24.dp)
            )
            .border(1.dp, Color(0x1A000000), RoundedCornerShape(24.dp))
            .padding(0.dp)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Color.White.copy(alpha = 0.55f), radius = 170f, center = Offset(size.width * 0.05f, size.height * 0.12f))
            drawCircle(Color(0xFF0B6B3A).copy(alpha = 0.08f), radius = 210f, center = Offset(size.width * 0.95f, size.height * 0.88f))
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
            drawPath(sideRoad, Color.White.copy(alpha = 0.75f), style = Stroke(width = 11f, cap = StrokeCap.Round))
            drawPath(sideRoad, Color(0xFF8DB39A).copy(alpha = 0.45f), style = Stroke(width = 3f, cap = StrokeCap.Round))
            drawPath(route, Color.White, style = Stroke(width = 22f, cap = StrokeCap.Round))
            drawPath(route, Color(0xFF0B6B3A), style = Stroke(width = 7f, cap = StrokeCap.Round))
            drawCircle(Color(0xFF0B6B3A), radius = 15f, center = Offset(size.width * 0.16f, size.height * 0.28f))
            drawCircle(Color(0xFFE2A11B), radius = 15f, center = Offset(size.width * 0.84f, size.height * 0.68f))
        }
        MapLabel(from, Modifier.align(Alignment.TopStart).padding(20.dp))
        MapLabel(to, Modifier.align(Alignment.CenterEnd).padding(20.dp))
        distance?.let { dist ->
            Surface(
                modifier = Modifier.align(Alignment.TopEnd).padding(18.dp),
                color = Color.White.copy(alpha = 0.92f),
                shape = RoundedCornerShape(999.dp)
            ) {
                Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Route, contentDescription = null, modifier = Modifier.size(16.dp), tint = CanonGreen2)
                    Spacer(Modifier.width(5.dp))
                    Text(dist, fontWeight = FontWeight.Bold)
                }
            }
        }
        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(14.dp),
            colors = CardDefaults.cardColors(containerColor = CanonSurface),
            shape = RoundedCornerShape(18.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Lock, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(appText("Геолокация откроется после подтверждения поездки", "Геолокация сәфәр раҫланғас асыла"), fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun MapLabel(text: String, modifier: Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = Color.White,
        shadowElevation = 3.dp
    ) {
        Text(text, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontWeight = FontWeight.Bold)
    }
}

// Кнопки масштаба карты ＋/− (стек справа, как в Яндекс.Картах).
@Composable
private fun MapZoomControls(modifier: Modifier = Modifier, onZoomIn: () -> Unit, onZoomOut: () -> Unit) {
    Surface(modifier = modifier, color = Color.White.copy(alpha = 0.95f), shape = RoundedCornerShape(13.dp), shadowElevation = 3.dp) {
        Column {
            IconButton(onClick = onZoomIn, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Default.Add, contentDescription = appText("Приблизить", "Яҡынайтыу"), tint = CanonGreen2, modifier = Modifier.size(18.dp))
            }
            Box(Modifier.width(20.dp).height(1.dp).background(Color(0x14000000)).align(Alignment.CenterHorizontally))
            IconButton(onClick = onZoomOut, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Default.Remove, contentDescription = appText("Отдалить", "Йыраҡлаштырыу"), tint = CanonGreen2, modifier = Modifier.size(18.dp))
            }
        }
    }
}


