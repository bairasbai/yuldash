package com.yuldash.app

// Вкладки Поездки + Заявки + Чат и их карточки. Вынесено из MainActivity (Фаза 2).
// Импорты целиком — лишние = варнинги.

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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.NotificationsActive
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
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
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
internal fun RidesScreen(
    rides: List<Ride>,
    ads: List<PartnerAd>,
    adStats: Map<String, AdStats>,
    presetTo: String,
    presetToday: Boolean,
    onBookRide: (Ride) -> Unit,
    onOpenBookingDetails: (Ride, String) -> Unit,
    onOpenActiveTrip: (Ride, String) -> Unit,
    onMessage: () -> Unit,
    onShareRide: (Ride) -> Unit,
    onBoost: () -> Unit,
    onCreateRequest: () -> Unit,
    onAdImpression: (PartnerAd) -> Unit,
    onAdClick: (PartnerAd) -> Unit
) {
    var selectedStatus by remember { mutableStateOf("active") }
    val activeLabel = appText("Активные", "Актив")
    val historyLabel = appText("История", "Тарих")
    val allLabel = appText("Все", "Бөтәһе")
    val selectedStatusLabel = when (selectedStatus) {
        "history" -> historyLabel
        "all" -> allLabel
        else -> activeLabel
    }
    // Подбор рекламы — в remember(ads): фильтры не пересчитываются на каждой рекомпозиции списка.
    val routeAd = remember(ads) {
        ads.forPlacement(AdPlacement.Route).filter { it.matchesRoute("Баймаҡ", "Сибай") }.firstOrNull { it.id == "ad-cafe-route" }
            ?: ads.forPlacement(AdPlacement.Route).firstOrNull { it.matchesRoute("Баймаҡ", "Сибай") }
    }
    val sponsoredAd = remember(ads) { ads.forPlacement(AdPlacement.RidesList).firstOrNull { it.id == "ad-service-rides" } }
    val inlineAd = routeAd ?: sponsoredAd
    LaunchedEffect(presetTo, presetToday) {
        if (presetTo.isNotBlank() || presetToday) selectedStatus = "active"
    }
    // Реальные брони пользователя (раньше тут были захардкоженные «Рамиль/12 мая»).
    var bookings by remember { mutableStateOf<List<com.yuldash.app.data.BookingMineDto>>(emptyList()) }
    var bookingsLoading by remember { mutableStateOf(true) }
    var bookingsError by remember { mutableStateOf(false) }
    var bookingsReload by remember { mutableStateOf(0) }
    LaunchedEffect(bookingsReload) {
        bookingsLoading = true
        ApiClient.getMyBookingsDetailed()
            .onSuccess { bookings = it; bookingsError = false }
            // 401 (не вошёл) — не ошибка сети: просто пусто. Реальный сбой → «Повторить».
            .onFailure { e -> bookingsError = (e as? ApiException)?.status != 401 }
        bookingsLoading = false
    }
    val activeStatuses = listOf("pending", "confirmed", "onboard")
    val historyStatuses = listOf("done", "cancelled")
    // contentWindowInsets = 0: вкладки живут ВНУТРИ общего Scaffold в YuldashApp, он уже отдал
    // отступ под статус-бар. Свой Scaffold добавлял его второй раз — заголовок «Мои поездки»
    // висел на 70dp от верха, а соседние «Мои заявки» на 33dp. Разнобой между вкладками одного
    // приложения глаз ловит сразу, даже не понимая, что именно не так. Так уже сделано на главной.
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Spacer(Modifier.height(4.dp)) }
            item {
                Text(appText("Мои поездки", "Минең сәфәрҙәр"), color = CanonGreen, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold)
            }
            item {
                SegmentedTabs(
                    tabs = listOf(activeLabel, historyLabel, allLabel),
                    selected = selectedStatusLabel,
                    onSelect = {
                        selectedStatus = when (it) {
                            historyLabel -> "history"
                            allLabel -> "all"
                            else -> "active"
                        }
                    }
                )
            }
            val visibleBookings = bookings.filter {
                when (selectedStatus) {
                    "history" -> it.status in historyStatuses
                    "all" -> true
                    else -> it.status in activeStatuses
                }
            }
            when {
                bookingsLoading -> item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { repeat(3) { SkeletonCard(lines = 3) } }
                }
                bookingsError -> item {
                    EmptyStateCard(
                        title = appText("Не удалось загрузить поездки", "Сәфәрҙәрҙе йөкләп булманы"),
                        text = appText("Проверь интернет и повтори", "Интернетты тикшереп ҡабатла"),
                        icon = Icons.Default.Refresh,
                        action = appText("Повторить", "Ҡабатлау"),
                        onAction = { bookingsReload++ }
                    )
                }
                visibleBookings.isEmpty() -> item {
                    EmptyStateCard(
                        title = appText("Поездок пока нет", "Әлегә сәфәрҙәр юҡ"),
                        text = appText("Создайте заявку или опубликуйте маршрут водителя.", "Заявка булдырығыҙ йәки водитель маршрутын баҫтырығыҙ."),
                        icon = Icons.Default.Route,
                        action = appText("Создать заявку", "Заявка булдырыу"),
                        onAction = onCreateRequest
                    )
                }
                else -> {
                    itemsIndexed(visibleBookings, key = { _, b -> b.id }) { i, b ->
                        // Сводку с сервера дополняем feed-поездкой по ride_id (если сервер ещё без джойна).
                        val feed = rides.firstOrNull { it.id == b.rideId.toString() }
                        val displayRide = Ride(
                            id = b.id.toString(),   // id = booking_id → onOpenActiveTrip получит верный booking
                            from = b.fromCity.ifBlank { feed?.from ?: appText("Поездка", "Сәфәр") },
                            to = b.toCity.ifBlank { feed?.to ?: "№${b.rideId}" },
                            time = b.departAt.takeIf { it.isNotBlank() }?.let(::formatDepart) ?: (feed?.time ?: ""),
                            timeBa = b.departAt.takeIf { it.isNotBlank() }?.let(::formatDepart) ?: (feed?.timeBa ?: feed?.time ?: ""),
                            driver = b.driverName.ifBlank { feed?.driver ?: "" },
                            car = feed?.car ?: "",
                            carBa = feed?.carBa ?: feed?.car ?: "",
                            price = if (b.price > 0) b.price else (feed?.price ?: 0),
                            seats = b.seats,
                            rating = feed?.rating ?: 0.0,
                            verified = b.driverVerified || (feed?.verified ?: false),
                            boosted = false
                        )
                        val isHist = b.status in historyStatuses
                        val opensActiveTrip = bookingStatusAllowsActiveTrip(b.status)
                        val (statusLabel, statusColor, statusIcon) = when (b.status) {
                            "confirmed" -> Triple(appText("Подтверждена", "Раҫланды"), CanonMint, Icons.Default.DirectionsCar)
                            "onboard" -> Triple(appText("В пути", "Юлда"), CanonMint, Icons.Default.DirectionsCar)
                            "done" -> Triple(appText("Завершена", "Тамамланды"), CanonMint, Icons.Default.CheckCircle)
                            "cancelled" -> Triple(appText("Отменена", "Кире ҡағылды"), CanonDangerBg, Icons.Default.Close)
                            else -> Triple(appText("Ожидает", "Көтә"), CanonWarnBg, Icons.Default.Schedule)
                        }
                        Box(Modifier.appearIn(i)) {
                            MyTripCard(
                                ride = displayRide,
                                status = statusLabel,
                                statusColor = statusColor,
                                icon = statusIcon,
                                primaryAction = when {
                                    isHist -> appText("Повторить маршрут", "Маршрутты ҡабатлау")
                                    opensActiveTrip -> appText("Открыть поездку", "Сәфәрҙе асыу")
                                    else -> appText("Подробнее", "Ентекле")
                                },
                                secondaryAction = if (isHist) "" else if (opensActiveTrip) appText("Чат", "Чат") else appText("Написать", "Яҙыу"),
                                onPrimary = {
                                    when {
                                        isHist -> onCreateRequest()
                                        opensActiveTrip -> onOpenActiveTrip(displayRide, b.status)
                                        else -> onOpenBookingDetails(displayRide, b.status)
                                    }
                                },
                                onSecondary = { if (opensActiveTrip) onOpenActiveTrip(displayRide, b.status) else onMessage() }
                            )
                        }
                    }
                    inlineAd?.let { ad ->
                        item {
                            InlinePartnerAdCard(
                                ad = ad,
                                label = if (ad.id == routeAd?.id) appText("Партнёр по маршруту", "Маршрут партнёры") else appText("Совет партнёра", "Партнёр кәңәше"),
                                onImpression = onAdImpression,
                                onClick = onAdClick
                            )
                        }
                    }
                }
            }
            item {
                InfoCard(
                    title = appText("Поездки защищены системой Юлдаш", "Сәфәрҙәр Юлдаш системаһы менән һаҡлана"),
                    text = appText("Мы заботимся о вашей безопасности", "Беҙ һеҙҙең хәүефһеҙлек тураһында ҡайғыртабыҙ"),
                    icon = Icons.Default.Shield
                )
            }
            item { Spacer(Modifier.height(150.dp)) }
        }
    }
}

@Composable
internal fun SegmentedTabs(
    tabs: List<String>,
    selected: String,
    onSelect: (String) -> Unit
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(tabs, key = { it }) { tab ->
            Surface(
                modifier = Modifier.bounceClick { onSelect(tab) },
                color = if (selected == tab) CanonGreen2 else CanonSurface,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, if (selected == tab) Color.Transparent else CanonBorder),
                shadowElevation = if (selected == tab) 2.dp else 1.dp
            ) {
                Text(
                    tab,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = if (selected == tab) Color.White else CanonMuted,
                    fontWeight = if (selected == tab) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 14.sp,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
internal fun MyTripCard(
    ride: Ride,
    status: String,
    statusColor: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    primaryAction: String,
    secondaryAction: String,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = CanonCardShape,
        elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.card)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Surface(color = CanonMint, shape = RoundedCornerShape(14.dp)) {
                    Icon(icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(12.dp).size(24.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            "${ride.from} → ${ride.to}",
                            modifier = Modifier.weight(1f),
                            color = CanonText,
                            fontSize = 19.sp,
                            lineHeight = 25.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        // Убрана декоративная иконка «⋮» (MoreVert) — выглядела кликабельной, но меню не открывала.
                    }
                    Surface(color = statusColor, shape = RoundedCornerShape(999.dp)) {
                        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Verified, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(status, color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
                        }
                    }
                    DetailMeta(Icons.Default.Schedule, ride.timeText())
                DetailMeta(Icons.Default.Person, "${seatsText(ride.seats)} · ${ride.price} ₽")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onPrimary,
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)
                ) {
                    Text(primaryAction, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (secondaryAction.isNotBlank()) {
                    FilledTonalButton(
                        onClick = onSecondary,
                        modifier = Modifier.weight(1.1f).height(44.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = CanonMint, contentColor = CanonGreen2)
                    ) {
                        Text(secondaryAction, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

// Карточка «ближайшей поездки» — строго 1-в-1 (фикс. размер), сортировка по времени выезда.
// Первая (самая ранняя) помечается «ближайшая». Показывает дистанцию до точки выезда (если есть гео).
@Composable
internal fun NearbyMoreCard(loading: Boolean, onMore: () -> Unit) {
    // «Показать ещё» — карточка в конце ленты «Ближайших» (серверная пагинация limit/offset).
    Card(
        modifier = Modifier
            .width(132.dp)
            .height(152.dp)
            .clickable(enabled = !loading, onClick = onMore),
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = CanonItemShape,
        elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.card)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                if (loading) appText("Загрузка…", "Йөкләнә…") else appText("Показать\nещё", "Тағы\nкүрһәтеү"),
                color = CanonGreen, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 23.sp
            )
        }
    }
}

@Composable
internal fun NearbyRideCard(dto: com.yuldash.app.data.RideDto, soonest: Boolean, onOpen: () -> Unit) {
    Card(
        modifier = Modifier
            .width(286.dp)
            .height(152.dp)
            .clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = CanonItemShape,
        elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.card)
    ) {
        Column(
            // Зазор xs, а не sm: карточка ФИКСИРОВАННОЙ высоты 152dp, а родитель режет всё, что
            // не влезло, причём срезает ВЕРХ — пропадают маршрут и время, остаются цена с кнопкой.
            // Пять строк + четыре зазора по 8dp + поля 24dp давали ~154dp, то есть перебор.
            // Поймано в тёмной теме на эмуляторе; тесты и компилятор молчат — обрезанный текст
            // в дереве элементов присутствует, он просто не на экране.
            modifier = Modifier.fillMaxSize().padding(horizontal = CanonSpace.lg, vertical = CanonSpace.md),
            verticalArrangement = Arrangement.spacedBy(CanonSpace.xs)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${dto.fromCity} → ${dto.toCity}",
                    modifier = Modifier.weight(1f),
                    style = CanonBodyStrong,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (dto.category.isNotBlank() && dto.category != "regular") {
                    val (ic, ru, ba) = rideTypeMeta(dto.category)
                    Surface(color = CanonMint, shape = RoundedCornerShape(999.dp)) {
                        Row(Modifier.padding(horizontal = CanonSpace.sm, vertical = CanonSpace.xs), verticalAlignment = Alignment.CenterVertically) {
                            Icon(ic, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(appText(ru, ba), color = CanonGreen2, style = CanonMicro)
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                }
                if (dto.driverVerified) {
                    Icon(Icons.Default.Verified, contentDescription = appText("Проверен", "Тикшерелгән"), tint = CanonGreen2, modifier = Modifier.size(18.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Schedule, contentDescription = null, tint = CanonMuted, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text(formatDepart(dto.departAt), color = CanonMuted, style = CanonCaption)
                if (soonest) {
                    Spacer(Modifier.width(8.dp))
                    Surface(color = CanonMint, shape = RoundedCornerShape(999.dp)) {
                        Text(
                            appText("ближайшая", "иң яҡыны"),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            color = CanonGreen2, style = CanonMicro
                        )
                    }
                }
            }
            val openDriver = LocalOpenDriverProfile.current
            Row(
                verticalAlignment = Alignment.CenterVertically,
                // onClickLabel → TalkBack озвучит действие; BA-draft
                modifier = if (dto.driverId > 0) Modifier.clickable(onClickLabel = appText("Открыть профиль", "Профильде асыу")) { openDriver(dto.driverId) } else Modifier
            ) {
                SmallAvatar(dto.driverAvatar, dto.driverName, 30)
                Spacer(Modifier.width(8.dp))
                Text(dto.driverName.ifBlank { appText("Водитель", "Йөрөтөүсе") }, style = CanonCaption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (dto.driverOnline) { Spacer(Modifier.width(4.dp)); OnlineBadge() }
                if (dto.driverIsWoman) { Spacer(Modifier.width(4.dp)); WomanDriverBadge() }
                Spacer(Modifier.weight(1f))
                Icon(Icons.Default.Star, contentDescription = null, tint = CanonStar, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text(dto.driverRating.toString(), style = CanonCaption)
            }
            // F8: стаж/поездки водителя — в гибкой зоне (weight), высоту карточки не увеличивает.
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                CompactTrustLine(trips = dto.driverTrips, since = dto.driverSince)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                dto.distanceKm?.let { km ->
                    Icon(Icons.Default.NearMe, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (km < 1.0) appText("рядом", "янда") else appText("${fmtKm(km)} км", "${fmtKm(km)} км"),
                        style = CanonCaption, color = CanonText
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text("${dto.price} ₽", color = CanonGreen2, style = CanonHeading)
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = onOpen,
                    modifier = Modifier.height(36.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2),
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    Text(appText("Поехать", "Барырға"), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
internal fun NearbySkeletonCard() {
    Card(
        modifier = Modifier.width(286.dp).height(152.dp),
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = CanonItemShape,
        elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.card)
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SkeletonBox(widthFraction = 0.7f, height = 16.dp)
            SkeletonBox(widthFraction = 0.4f, height = 13.dp)
            SkeletonBox(widthFraction = 0.55f, height = 13.dp)
            Spacer(Modifier.weight(1f))
            SkeletonBox(widthFraction = 0.5f, height = 34.dp, shape = RoundedCornerShape(14.dp))
        }
    }
}

@Composable
internal fun NearbyEmptyCard(hasRoute: Boolean, onRetry: () -> Unit, error: Boolean = false, onWatchRoute: (() -> Unit)? = null) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = CanonItemShape,
        elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.card)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                if (error) Icons.Default.Refresh else Icons.Default.DirectionsCar,
                contentDescription = null, tint = CanonMuted, modifier = Modifier.size(34.dp)
            )
            Text(
                when {
                    error -> appText("Не удалось загрузить", "Йөкләп булманы")
                    hasRoute -> appText("На этом маршруте пока нет машин", "Был маршрутта әлегә машина юҡ")
                    else -> appText("Поездок рядом пока нет", "Яҡында сәфәрҙәр әлегә юҡ")
                },
                fontWeight = FontWeight.Bold, fontSize = 16.sp
            )
            Text(
                if (error) appText("Проверь интернет и повтори", "Интернетты тикшереп ҡабатла")
                else appText("Появятся — покажем здесь", "Барлыҡҡа килһә — бында күрһәтәбеҙ"),
                color = CanonMuted, fontSize = 14.sp
            )
            TextButton(onClick = onRetry) {
                Text(if (error) appText("Повторить", "Ҡабатлау") else appText("Обновить", "Яңыртыу"), color = CanonGreen2, fontWeight = FontWeight.Bold)
            }
            // F13 «карауль поездку»: подпишись на маршрут — уведомим, как только появится машина.
            if (onWatchRoute != null) {
                Spacer(Modifier.height(4.dp))
                AppButton(
                    text = appText("Следить за маршрутом", "Маршрутты күҙәтеү"),
                    onClick = onWatchRoute,
                    style = AppButtonStyle.Secondary,
                    icon = Icons.Default.NotificationsActive,
                    fillWidth = false,
                    height = 46.dp,
                )
            }
            // F19 «Позови водителя»: на реальной пустой выдаче (не ошибка сети) — мягкий призыв
            // пригласить знакомого водителя. Спрос сам растит предложение (виральность «между своими»).
            if (!error) {
                Spacer(Modifier.height(4.dp))
                InviteDriverCallout()
            }
        }
    }
}

/**
 * F19 «Позови водителя» — блок на пустой выдаче поиска.
 * Шеринг персональной реферальной ссылки + обещание бонуса: приглашённый станет водителем
 * и сделает первый рейс → пригласившему бесплатный Boost (поднятие поездки).
 * Реф-код тянем из существующего /referral/me (кэш). Не вошёл → делимся без кода (общая ссылка).
 */
@Composable
internal fun InviteDriverCallout() {
    val ctx = LocalContext.current
    val lang = LocalAppLanguage.current   // читаем в @Composable-теле; в onClick — appTextFor (не @Composable)
    var refCode by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { ApiClient.getReferral().onSuccess { refCode = it.code } }

    val shareTitle = appText("Пригласить водителя", "Водитель саҡырыу")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(CanonHairlineGreen)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(38.dp).clip(CircleShape).background(CanonGreen2),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.DirectionsCar, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    appText("Никто не едет? Позови водителя", "Бер кем дә бармаймы? Водитель саҡыр"),
                    color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp
                )
                Text(
                    appText(
                        "Пригласи знакомого. Сделает первый рейс — тебе бесплатный Boost.",
                        "Танышыңды саҡыр. Тәүге сәфәрен яһаһа — һиңә бушлай Boost."
                    ),
                    color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp
                )
            }
        }
        Button(
            onClick = {
                val code = refCode
                val shareTxt = if (code != null) appTextFor(lang,
                    "Юлдаш — попутки между своими по Башкортостану. Становись водителем по моему приглашению: сделаешь первый рейс — обоим бонус. Мой код: $code. Скачать: https://yulbash.ru",
                    "Юлдаш — Башҡортостан буйлап үҙ-ара юлдаштар. Минең саҡырыу буйынса водитель бул: тәүге сәфәреңде яһаһаң — икәүгә лә бонус. Минең код: $code. Йөкләү: https://yulbash.ru"
                ) else appTextFor(lang,
                    "Юлдаш — попутки между своими по Башкортостану. Становись водителем — вози соседей и зарабатывай. Скачать: https://yulbash.ru",
                    "Юлдаш — Башҡортостан буйлап үҙ-ара юлдаштар. Водитель бул — күршеләреңде йөрөт, аҡса эшлә. Йөкләү: https://yulbash.ru"
                )
                runCatching {
                    ctx.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareTxt),
                            shareTitle
                        )
                    )
                }
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)
        ) {
            Icon(Icons.Default.Share, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(shareTitle, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}

@Composable
internal fun RideCard(
    ride: Ride,
    compact: Boolean,
    fullWidth: Boolean = false,
    onBook: () -> Unit,
    onShare: () -> Unit,
    onBoost: () -> Unit
) {
    val hasSeats = ride.seats > 0
    val noSeatsText = appText("Мест нет", "Урын юҡ")
    if (compact && fullWidth) {
        FullRideCard(
            ride = ride,
            onBook = onBook,
            onShare = onShare,
            onBoost = onBoost
        )
        return
    }

    Card(
        modifier = (if (compact && !fullWidth) Modifier.width(320.dp) else Modifier.fillMaxWidth())
            .clickable(enabled = compact, onClick = onBook),
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = CanonItemShape,
        elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.card)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${ride.from} → ${ride.to}",
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.Bold,
                    fontSize = if (compact) 16.sp else 19.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (ride.verified) {
                    VerifiedBadge()
                } else if (ride.boosted) {
                    BoostBadge()
                }
            }
            // Посылка: кому отдать + габарит/вес (показываем только если заполнено).
            if (ride.receiverName.isNotBlank() || ride.parcelSize.isNotBlank()) {
                Text(
                    "📦 " + listOfNotNull(
                        ride.receiverName.takeIf { it.isNotBlank() }?.let { appText("кому: $it", "кемгә: $it") },
                        ride.parcelSize.takeIf { it.isNotBlank() }
                    ).joinToString(" · "),
                    color = CanonGreen2, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(4.dp))
                Text(ride.timeText(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val openDriver = LocalOpenDriverProfile.current
            // Двуязычный дефолт имени (toUiRide больше не кладёт русский литерал). BA-draft: «Йөрөтөүсе».
            val driverFallback = appText("Водитель", "Йөрөтөүсе")
            val driverName = ride.driver.ifBlank { driverFallback }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                // onClickLabel → TalkBack озвучит действие; BA-draft
                modifier = if (ride.driverId > 0) Modifier.clickable(onClickLabel = appText("Открыть профиль", "Профильде асыу")) { openDriver(ride.driverId) } else Modifier
            ) {
                SmallAvatar(ride.driverAvatar, driverName, 44)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(driverName, fontWeight = FontWeight.Bold)
                        if (ride.verified) {
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Default.Verified, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(16.dp))
                        }
                        if (ride.driverOnline) {
                            Spacer(Modifier.width(8.dp))
                            OnlineBadge()
                        }
                        if (ride.driverIsWoman) {
                            Spacer(Modifier.width(8.dp))
                            WomanDriverBadge()
                        }
                    }
                    Text(ride.carText(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Default.Star, contentDescription = null, tint = CanonStar, modifier = Modifier.size(18.dp))
                Text(ride.rating.toString())
            }
            // F8: бейджи доверия водителя (проверен · N поездок · с нами с …). Скрыт «Проверен» в чипе,
            // т.к. галочка уже есть рядом с именем выше — тут показываем «стаж» и «поездки».
            DriverTrustBadges(verified = false, trips = ride.driverTrips, since = ride.driverSince)
            if (compact) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(4.dp))
                    Text("${seatsText(ride.seats)} · ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${ride.price} ₽", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onBook,
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(appText("Подробнее", "Ентекле"))
                    }
                    Button(
                        onClick = onBook,
                        enabled = hasSeats,
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)
                    ) {
                        Text(if (hasSeats) appText("Поехать", "Барырға") else noSeatsText, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Metric(Icons.Default.Payments, "${ride.price} ₽", Modifier.weight(1f))
            Metric(Icons.Default.EventSeat, seatsText(ride.seats), Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onBook,
                        enabled = hasSeats,
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(if (hasSeats) appText("Забронировать", "Бронләү") else noSeatsText)
                    }
                    IconButton(onClick = onShare) {
                        Icon(Icons.Default.IosShare, contentDescription = appText("Поделиться", "Бүлешеү"))
                    }
                    IconButton(onClick = onBoost) {
                            Icon(Icons.Default.TrendingUp, contentDescription = appText("Поднять объявление", "Иғланды өҫкә күтәреү"))
                    }
                }
            }
        }
    }
}

@Composable
private fun VerifiedBadge() {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(999.dp)
    ) {
        Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Verified, contentDescription = null, modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(4.dp))
            Text(appText("Проверен", "Тикшерелгән"), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
    }
}

// ---- F8 «Стаж своего»: компактные бейджи доверия водителя ----
// Показываем только правдивые факты: «Проверен» / «N поездок» (завершённых) / «С нами с <мес год>».
// FlowRow → длинный башкирский переносится на новую строку, вёрстка карточки не ломается.
// «Земляк» и «Отвечает быстро» пока НЕ отдаём: у пользователя нет города, а времени подтверждения
// брони не храним — честно пропускаем, чтобы не рисовать выдуманный бейдж.
private val f8MonthsRu = listOf(
    "января", "февраля", "марта", "апреля", "мая", "июня",
    "июля", "августа", "сентября", "октября", "ноября", "декабря",
)
private val f8MonthsBa = listOf(
    "ғинуар", "февраль", "март", "апрель", "май", "июнь",
    "июль", "август", "сентябрь", "октябрь", "ноябрь", "декабрь",
)

/** RU-плюрал: 1 поездка · 2 поездки · 5 поездок. */
private fun tripsWordRu(n: Int): String {
    val m100 = n % 100
    val m10 = n % 10
    return when {
        m100 in 11..14 -> "поездок"
        m10 == 1 -> "поездка"
        m10 in 2..4 -> "поездки"
        else -> "поездок"
    }
}

/** "YYYY-MM" → (индекс месяца 0..11, год); null — если формат неожиданный (бейдж не покажем). */
private fun parseDriverSince(since: String): Pair<Int, Int>? {
    val parts = since.split("-")
    if (parts.size < 2) return null
    val year = parts[0].toIntOrNull() ?: return null
    val mon = parts[1].toIntOrNull() ?: return null
    if (mon !in 1..12) return null
    return (mon - 1) to year
}

@Composable
private fun TrustChip(icon: ImageVector, text: String, accent: Boolean) {
    val bg = if (accent) MaterialTheme.colorScheme.primaryContainer else CanonMint
    val fg = if (accent) MaterialTheme.colorScheme.primary else CanonGreen2
    Surface(color = bg, shape = RoundedCornerShape(999.dp)) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(4.dp))
            Text(text, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DriverTrustBadges(verified: Boolean, trips: Int, since: String, modifier: Modifier = Modifier) {
    val sinceParts = parseDriverSince(since)
    if (!verified && trips <= 0 && sinceParts == null) return   // нечего показывать — не занимаем место
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (verified) {
            TrustChip(Icons.Default.Verified, appText("Проверен", "Тикшерелгән"), accent = true)
        }
        if (trips > 0) {
            TrustChip(Icons.Default.Route, appText("$trips ${tripsWordRu(trips)}", "$trips сәфәр"), accent = false)
        }
        sinceParts?.let { (mi, yr) ->
            TrustChip(
                Icons.Default.CalendarMonth,
                appText("с ${f8MonthsRu[mi]} $yr", "${f8MonthsBa[mi]} $yr-нан бирле"),
                accent = false,
            )
        }
    }
}

/** Однострочный компактный «стаж» для карточек фикс-высоты (Ближайшие): «N поездок · с <мес год>».
 *  maxLines=1 + ellipsis → длинный башкирский не ломает вёрстку. Пусто → строку не рисуем. */
@Composable
internal fun CompactTrustLine(trips: Int, since: String, modifier: Modifier = Modifier) {
    val sinceParts = parseDriverSince(since)
    val parts = buildList {
        if (trips > 0) add(appText("$trips ${tripsWordRu(trips)}", "$trips сәфәр"))
        sinceParts?.let { (mi, yr) -> add(appText("с ${f8MonthsRu[mi]} $yr", "${f8MonthsBa[mi]} $yr-нан")) }
    }
    if (parts.isEmpty()) return
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Badge, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(4.dp))
        Text(parts.joinToString(" · "), color = CanonMuted, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun BoostBadge() {
    Surface(
        color = CanonYellow,
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, CanonGold.copy(alpha = 0.25f))
    ) {
        Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.TrendingUp, contentDescription = null, modifier = Modifier.size(15.dp), tint = CanonGreen2)
            Spacer(Modifier.width(4.dp))
            Text(appText("Вверху", "Өҫтә"), fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
    }
}

// Чип-предпочтение поездки (иконка + подпись), стиль Canon.
@Composable
private fun PrefChip(icon: ImageVector, label: String) {
    Surface(color = CanonMint, shape = RoundedCornerShape(999.dp)) {
        Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
        }
    }
}

/** То же, но иконка из брендового пака (vector-drawable) — красится под тему через tint. */
@Composable
private fun PrefChip(iconRes: Int, label: String) {
    Surface(color = CanonMint, shape = RoundedCornerShape(999.dp)) {
        Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(iconRes), contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
        }
    }
}

// Лента чипов с условиями поездки (показывается только если есть хоть одно).
@Composable
private fun RidePrefChips(ride: Ride, modifier: Modifier = Modifier) {
    if (!(ride.petsAllowed || ride.childSeat || ride.womenOnly || ride.smoking || ride.baggage || ride.airConditioner || ride.quiet)) return
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (ride.womenOnly) PrefChip(R.drawable.yu_women_only, appText("Только женщины", "Тик ҡатын-ҡыҙ"))
        if (ride.childSeat) PrefChip(R.drawable.yu_child_seat, appText("Детское кресло", "Балалар ултырғысы"))
        if (ride.petsAllowed) PrefChip(R.drawable.yu_pet, appText("С животным", "Хайуан менән"))
        if (ride.baggage) PrefChip(R.drawable.yu_luggage, appText("Багаж", "Багаж"))
        if (ride.airConditioner) PrefChip(R.drawable.yu_ac, appText("Кондиционер", "Кондиционер"))
        if (ride.quiet) PrefChip(R.drawable.yu_quiet, appText("Тихая поездка", "Тыныс сәфәр"))
        if (ride.smoking) PrefChip(Icons.Default.SmokingRooms, appText("Можно курить", "Тартырға ярай"))
    }
}

// Строка-тумблер условия поездки (для экрана «Создать поездку»).
@Composable
internal fun PrefToggleRow(icon: ImageVector, label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(color = CanonMint, shape = RoundedCornerShape(14.dp)) {
            Icon(icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(8.dp).size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(label, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** То же, но иконка из брендового пака (vector-drawable). */
@Composable
internal fun PrefToggleRow(iconRes: Int, label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(color = CanonMint, shape = RoundedCornerShape(14.dp)) {
            Icon(painterResource(iconRes), contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(8.dp).size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(label, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

// Чип-фильтр «Ближайших» — переключаемый (зелёный = активен).
@Composable
internal fun NearbyFilterChip(
    icon: ImageVector,
    label: String,
    active: Boolean,
    // Внешний модификатор: там, где чип — не фильтр в ленте, а ОСНОВНОЙ выбор (способ оплаты,
    // категория SOS), вызывающий поднимает высоту до 48dp — минимальная тач-цель (§4.5).
    // Surface пробрасывает min-constraints внутрь, поэтому содержимое остаётся по центру.
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
) {
    Surface(
        modifier = modifier.bounceClick(onToggle),
        color = if (active) CanonGreen2 else CanonSurface,
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, if (active) Color.Transparent else CanonBorder)
    ) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = if (active) Color.White else CanonGreen2, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, color = if (active) Color.White else CanonText, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
        }
    }
}

@Composable
private fun Metric(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = CanonSurface,
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
internal fun MyRequestsScreen(
    requests: List<LocalRequest>,
    onCreateNew: () -> Unit,
    onViewResponses: (Int) -> Unit,
    onCancel: (Int) -> Unit,
    loading: Boolean = false,
    // Ошибка загрузки — ОТДЕЛЬНОЕ состояние, а не «пусто». Без неё при обрыве связи экран
    // уверенно писал «Заявок пока нет» человеку, у которого заявка есть (аудит 2026-08-04).
    error: Boolean = false,
    onRetry: () -> Unit = {},
    onEditRequest: (Int, String, String, Int, String) -> Unit = { _, _, _, _, _ -> },   // F3: id, from, to, maxPrice, comment
    onOpenRide: (com.yuldash.app.data.RideDto) -> Unit = {},                              // авто-подбор: открыть подходящую поездку
) {
    // Один честный список заявок. Прежние вкладки «Отклики»/«Черновики» были вечными
    // заглушками (статичный текст + фейковый черновик «Баймак→Уфа 450₽») → убраны.
    // Отклики открываются с карточки заявки кнопкой «Посмотреть отклики».
    var cancelTarget by remember { mutableStateOf<LocalRequest?>(null) }
    var editTarget by remember { mutableStateOf<LocalRequest?>(null) }
    editTarget?.let { et ->
        EditRequestDialog(
            request = et,
            onDismiss = { editTarget = null },
            onSave = { from, to, price, comment ->
                onEditRequest(et.serverId, from, to, price, comment)
                editTarget = null
            },
        )
    }
    cancelTarget?.let { ct ->
        AlertDialog(
            onDismissRequest = { cancelTarget = null },
            containerColor = CanonSurface,
            title = { Text(appText("Отменить заявку?", "Заявканы кире алабыҙмы?"), color = CanonText, fontWeight = FontWeight.Bold) },
            text = { Text(appText("Водители больше не увидят её. Это действие нельзя отменить.", "Водителдәр уны күрмәҫ. Быны кире ҡайтарып булмай."), color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp) },
            confirmButton = {
                TextButton(onClick = { onCancel(ct.serverId); cancelTarget = null }) {
                    Text(appText("Отменить заявку", "Кире алыу"), color = CanonRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { cancelTarget = null }) { Text(appText("Оставить", "Ҡалдырыу"), color = CanonMuted) } },
        )
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 118.dp)
    ) {
        item { Spacer(Modifier.height(4.dp)) }
        item {
            Text(appText("Мои заявки", "Минең заявкалар"), color = CanonGreen, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold)
        }
        if (requests.isEmpty() && loading) {
            // Пока грузим — скелетон, а не ложное «Заявок пока нет» (мелькало на первой загрузке).
            item { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { repeat(3) { SkeletonCard(lines = 3) } } }
        } else if (requests.isEmpty() && error) {
            // Не смогли загрузить ≠ заявок нет. Разница принципиальная: во втором случае человек
            // решит, что его заявка не создалась, и создаст вторую.
            item {
                Box(
                    Modifier.appearIn(0).fillParentMaxHeight(0.72f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    AppErrorState(
                        onRetry = onRetry,
                        title = appText("Не удалось загрузить заявки", "Заявкаларҙы йөкләп булманы"),
                    )
                }
            }
        } else if (requests.isEmpty()) {
            item {
                // Пусто — это тоже состояние экрана, а не «ничего нет». Плашка и кнопка идут
                // ОДНОЙ связкой по центру свободного места: прижатая к статус-бару плашка
                // и 1300px пустоты под ней читались как незагрузившийся экран, а разнесённые
                // по экрану плашка и кнопка — как два несвязанных острова.
                Box(
                    Modifier.appearIn(0).fillParentMaxHeight(0.72f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        InfoCard(
                            title = appText("Заявок пока нет", "Әлегә заявкалар юҡ"),
                            text = appText("Создайте заявку — водители увидят её и откликнутся.", "Заявка булдырығыҙ — водителдәр уны күреп яуап бирер."),
                            icon = Icons.Default.AddBox
                        )
                        CreateRequestButton(onCreateNew)
                    }
                }
            }
        } else {
            itemsIndexed(requests, key = { i, r -> (if (r.serverId != 0) "id-${r.serverId}" else r.route + r.time + r.title) + "#$i" }) { i, req ->
                Box(Modifier.appearIn(0)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        RequestSummaryCard(
                            icon = if (req.title.contains("больниц", ignoreCase = true)) Icons.Default.LocalHospital else Icons.Default.DirectionsCar,
                            from = req.route.substringBefore(" → "),
                            to = req.route.substringAfter(" → "),
                            date = req.time,
                            reason = req.title,
                            price = if (req.price > 0) appText("${req.price} ₽ предлагаю", "${req.price} ₽ тәҡдим итәм") else appText("цена договорная", "хаҡ килешеү буйынса"),
                            badge = req.status,
                            action = appText("Посмотреть отклики", "Яуаптарҙы ҡарау"),
                            onAction = { onViewResponses(req.serverId) },
                            onCancel = if (req.serverId != 0) ({ cancelTarget = req }) else null,
                            onEdit = if (req.serverId != 0) ({ editTarget = req }) else null
                        )
                        // Авто-подбор попуток под эту заявку (только для заявок с серверным id).
                        if (req.serverId != 0) MatchingRidesSection(requestId = req.serverId, onOpenRide = onOpenRide)
                    }
                }
            }
        }
        if (requests.isNotEmpty()) item { CreateRequestButton(onCreateNew) }
    }
}

/** Одна кнопка на два места: в связке пустого состояния и под списком заявок. */
@Composable
private fun CreateRequestButton(onCreateNew: () -> Unit) {
    Button(
        onClick = onCreateNew,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = CanonFieldShape,
        colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)
    ) {
        Icon(Icons.Default.AddBox, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(appText("Создать новую", "Яңыһын булдырыу"), fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

@Composable
internal fun RequestSummaryCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    from: String,
    to: String,
    date: String,
    reason: String,
    price: String,
    badge: String,
    action: String,
    onAction: () -> Unit,
    onCancel: (() -> Unit)? = null,   // не null → показываем «Отменить заявку» (только для активных)
    onEdit: (() -> Unit)? = null      // не null → показываем «Редактировать» (только для активных)
) {
    Card(colors = CardDefaults.cardColors(containerColor = CanonSurface), shape = CanonCardShape, elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.card)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Surface(color = CanonMint, shape = RoundedCornerShape(14.dp)) {
                    Icon(icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(12.dp).size(24.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            "$from  →  $to",
                            modifier = Modifier.weight(1f),
                            color = CanonText,
                            fontSize = 19.sp,
                            lineHeight = 25.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.width(8.dp))
                        Surface(color = CanonMint, shape = RoundedCornerShape(999.dp)) {
                            Text(badge, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
                        }
                    }
                    DetailMeta(Icons.Default.CalendarMonth, date)
                    DetailMeta(Icons.Default.AddBox, reason)
                    DetailMeta(Icons.Default.Payments, price)   // price уже готовая строка («450 ₽ предлагаю»); не оборачиваем повторно
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(CanonBorder))
            OutlinedButton(
                onClick = onAction,
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, CanonGreen2)
            ) {
                Icon(Icons.Default.ChatBubble, contentDescription = null, tint = CanonGreen2)
                Spacer(Modifier.width(8.dp))
                Text(action, color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.weight(1f))
                Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = CanonGreen2)
            }
            if (onEdit != null || onCancel != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    onEdit?.let { doEdit ->
                        TextButton(onClick = doEdit, modifier = Modifier.weight(1f).height(44.dp)) {
                            Icon(Icons.Default.Edit, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(appText("Редактировать", "Үҙгәртеү"), color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                    onCancel?.let { doCancel ->
                        TextButton(onClick = doCancel, modifier = Modifier.weight(1f).height(44.dp)) {
                            Icon(Icons.Default.Close, contentDescription = null, tint = CanonRed, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(appText("Отменить заявку", "Заявканы кире алыу"), color = CanonRed, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }
}

/** F3: диалог правки заявки — прегружаем текущие значения (маршрут/цена/комментарий),
 *  шлём только то, что задано. Время и число мест правим отдельно (тут — частые правки). */
@Composable
private fun EditRequestDialog(
    request: LocalRequest,
    onDismiss: () -> Unit,
    onSave: (from: String, to: String, maxPrice: Int, comment: String) -> Unit,
) {
    var from by remember { mutableStateOf(request.route.substringBefore(" → ").trim()) }
    var to by remember { mutableStateOf(request.route.substringAfter(" → ").trim()) }
    var price by remember { mutableStateOf(if (request.price > 0) request.price.toString() else "") }
    var comment by remember { mutableStateOf(request.trustedContact ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CanonSurface,
        title = { Text(appText("Редактировать заявку", "Заявканы үҙгәртеү"), color = CanonText, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = from, onValueChange = { from = it }, singleLine = true,
                    label = { Text(appText("Откуда", "Ҡайҙан")) }, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = to, onValueChange = { to = it }, singleLine = true,
                    label = { Text(appText("Куда", "Ҡайҙа")) }, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = price, onValueChange = { s -> price = s.filter { it.isDigit() }.take(6) }, singleLine = true,
                    label = { Text(appText("Цена, ₽ (необязательно)", "Хаҡ, ₽ (мотлаҡ түгел)")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = comment, onValueChange = { comment = it.take(300) },
                    label = { Text(appText("Комментарий", "Аңлатма")) }, modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(from.trim(), to.trim(), price.toIntOrNull() ?: 0, comment.trim()) },
                enabled = from.isNotBlank() && to.isNotBlank(),
            ) { Text(appText("Сохранить", "Һаҡлау"), color = CanonGreen2, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(appText("Отмена", "Кире алыу"), color = CanonMuted) } },
    )
}

/** Авто-подбор попуток под заявку пассажира: показывает подходящие поездки (matchRides).
 *  Все состояния: загрузка / пусто («как появятся — покажем») / ошибка+повтор / список карточек. */
@Composable
private fun MatchingRidesSection(requestId: Int, onOpenRide: (com.yuldash.app.data.RideDto) -> Unit) {
    var rides by remember(requestId) { mutableStateOf<List<com.yuldash.app.data.RideDto>?>(null) }
    var error by remember(requestId) { mutableStateOf(false) }
    var reload by remember(requestId) { mutableStateOf(0) }
    LaunchedEffect(requestId, reload) {
        error = false
        rides = null
        ApiClient.matchRides(requestId)
            .onSuccess { rides = it }
            .onFailure { error = true }
    }
    val list = rides
    Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            appText("Подходящие поездки", "Тап килгән сәфәрҙәр"),
            color = CanonGreen2, fontSize = 16.sp, fontWeight = FontWeight.Bold,
        )
        when {
            error -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(appText("Не удалось загрузить.", "Йөкләп булманы."), color = CanonMuted, fontSize = 14.sp)
                Spacer(Modifier.width(8.dp))
                Text(appText("Повторить", "Ҡабатларға"),
                    color = CanonGreen2, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.bounceClick { reload++ })
            }
            list == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = CanonGreen2)
                Spacer(Modifier.width(8.dp))
                Text(appText("Ищем совпадения…", "Тап килгәндәрҙе эҙләйбеҙ…"), color = CanonMuted, fontSize = 14.sp)
            }
            list.isEmpty() -> Text(
                appText("Пока нет совпадений — как появятся, покажем.",
                    "Әлегә тап килгәне юҡ — булыу менән күрһәтербеҙ."),
                color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp,
            )
            else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 8.dp)) {
                itemsIndexed(list, key = { _, r -> r.id }) { i, dto ->
                    NearbyRideCard(dto = dto, soonest = i == 0, onOpen = { onOpenRide(dto) })
                }
            }
        }
    }
}

@Composable
private fun FullRideCard(
    ride: Ride,
    onBook: () -> Unit,
    onShare: () -> Unit,
    onBoost: () -> Unit
) {
    val isHospital = ride.car.contains("больниц", ignoreCase = true)
    val hasSeats = ride.seats > 0
    val noSeatsText = appText("Мест нет", "Урын юҡ")
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .bounceClick(onBook),
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = CanonItemShape,
        elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.card)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(color = CanonMint, shape = RoundedCornerShape(14.dp)) {
                    Icon(
                        if (isHospital) Icons.Default.LocalHospital else Icons.Default.DirectionsCar,
                        contentDescription = null,
                        modifier = Modifier.padding(12.dp).size(28.dp),
                        tint = CanonGreen2
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isHospital) {
                            Surface(color = CanonMint, shape = RoundedCornerShape(999.dp)) {
                                Text(
                                    appText("В больницу", "Больницаға"),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    color = CanonGreen2,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                            Spacer(Modifier.width(4.dp))
                        }
                        if (ride.boosted) {
                            BoostBadge()
                            Spacer(Modifier.width(4.dp))
                        }
                        if (ride.verified) {
                            VerifiedBadge()
                        }
                    }
                    Text(
                        "${ride.from} → ${ride.to}",
                        color = CanonText,
                        fontWeight = FontWeight.Bold,
                        fontSize = 19.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    // Остановки по пути (несколько точек) — если водитель их указал.
                    if (ride.waypoints.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(painterResource(R.drawable.yu_multi_stop), contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                appText("через ", "аша ") + ride.waypoints.joinToString(" · "),
                                color = CanonGreen2, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Schedule, contentDescription = null, tint = CanonMuted, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(ride.timeText(), color = CanonMuted, fontSize = 14.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Person, contentDescription = null, tint = CanonMuted, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(seatsText(ride.seats), color = CanonMuted, fontSize = 14.sp)
                        Text(" · ", color = CanonMuted, fontSize = 14.sp)
                        Text("${ride.price} ₽", color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
            }
            RidePrefChips(ride)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onBook,
                    modifier = Modifier.weight(1f).height(42.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, CanonBorder)
                ) {
                    Text(appText("Подробнее", "Ентекле"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
                }
                Button(
                    onClick = onBook,
                    enabled = hasSeats,
                    modifier = Modifier.weight(1f).height(42.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)
                ) {
                    Text(if (hasSeats) appText("Поехать", "Барырға") else noSeatsText, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
                }
                IconButton(onClick = onShare) {
                    Icon(Icons.Default.IosShare, contentDescription = appText("Поделиться", "Бүлешеү"), tint = CanonText)
                }
                IconButton(onClick = onBoost) {
                    Icon(Icons.Default.TrendingUp, contentDescription = appText("Поднять объявление", "Иғланды өҫкә күтәреү"), tint = CanonText)
                }
            }
        }
    }
}

@Composable
internal fun ChatScreen(
    voiceMessages: List<LocalVoiceMessage>,
    onAddVoiceMessage: (LocalVoiceMessage) -> Unit,
    onNotifications: () -> Unit,
    onOpenChat: (Int, String, String) -> Unit,
    onOpenResponses: (Int) -> Unit = {}
) {
    var selected by remember { mutableStateOf("active") }
    var conversations by remember { mutableStateOf<List<ConversationDto>>(emptyList()) }
    var convLoading by remember { mutableStateOf(true) }
    var convError by remember { mutableStateOf(false) }
    var convReload by remember { mutableStateOf(0) }
    var myRequests by remember { mutableStateOf<List<RequestDto>>(emptyList()) }
    var reqError by remember { mutableStateOf(false) }   // заявки: отличаем «нет заявок» от «сеть упала»
    var reqLoading by remember { mutableStateOf(true) }   // пока грузим заявки — скелетон, а не ложное «Заявок пока нет»
    var notifUnread by remember { mutableStateOf(0) }   // бейдж непрочитанных на кнопке «Система»
    val chatTabs = listOf(
        "active" to LocalizedText("Активные", "Актив"),
        "requests" to LocalizedText("Заявки", "Заявкалар"),
        "system" to LocalizedText("Система", "Система")
    )
    LaunchedEffect(convReload) {
        convLoading = true
        reqLoading = true
        ApiClient.getConversations()
            .onSuccess { conversations = it; convError = false }
            // 401 / нет сессии — это НЕ сетевая ошибка: диалогов просто нет, показываем дружелюбное «пусто».
            // Реальная ошибка (нет сети, 5xx) → convError=true → «Повторить».
            .onFailure { e -> convError = (e as? ApiException)?.status != 401 }
        convLoading = false
        ApiClient.getMyRequests()
            .onSuccess { myRequests = it; reqError = false }
            // 401 → не вошёл (обычное «пусто»); иначе сеть упала → показываем ошибку + «Повторить».
            .onFailure { e -> reqError = (e as? ApiException)?.status != 401 }
        reqLoading = false
        ApiClient.getNotifications().onSuccess { notifUnread = it.unread }
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Spacer(Modifier.height(8.dp)) }
        item {
            Text(appText("Чат", "Чат"), color = CanonGreen, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold)
            Text(
                appText("Общайтесь по активным поездкам и заявкам", "Актив сәфәрҙәр һәм заявкалар буйынса аралашығыҙ"),
                color = CanonMuted,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                chatTabs.zip(listOf(Icons.Default.ChatBubble, Icons.Default.ListAlt, Icons.Default.Settings)).forEach { (tab, icon) ->
                    val (key, label) = tab
                    val labelText = label.text()
                    Box(Modifier.weight(1f)) {
                        FilledTonalButton(
                            onClick = {
                                if (key == "system") onNotifications() else selected = key
                            },
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = if (selected == key) CanonMint else CanonSurface,
                                contentColor = CanonText
                            )
                        ) {
                            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = if (selected == key) CanonGreen2 else CanonMuted)
                            Spacer(Modifier.width(4.dp))
                            Text(labelText, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        // Бейдж непрочитанных уведомлений на кнопке «Система».
                        if (key == "system" && notifUnread > 0) {
                            Box(
                                Modifier.align(Alignment.TopEnd).padding(4.dp)
                                    .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
                                    .background(CanonRed, CircleShape)
                                    .padding(horizontal = 4.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    if (notifUnread > 99) "99+" else notifUnread.toString(),
                                    color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
        if (selected == "requests") {
            // Вкладка «Заявки» — реальные заявки пользователя (ждут отклика водителя).
            if (myRequests.isEmpty() && reqLoading) {
                // Пока грузим — скелетон, чтобы не мелькало ложное «Заявок пока нет».
                item {
                    Box(Modifier.appearIn(0)) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { repeat(3) { SkeletonCard(lines = 2) } }
                    }
                }
            } else if (myRequests.isEmpty() && reqError) {
                // Сеть упала — не выдаём это за «нет заявок», даём «Повторить».
                item {
                    Box(Modifier.appearIn(0)) {
                        EmptyStateCard(
                            title = appText("Не удалось загрузить заявки", "Заявкаларҙы йөкләп булманы"),
                            text = appText("Проверь интернет и повтори", "Интернетты тикшереп ҡабатла"),
                            icon = Icons.Default.Refresh,
                            action = appText("Повторить", "Ҡабатлау"),
                            onAction = { convReload++ },
                        )
                    }
                }
            } else if (myRequests.isEmpty()) {
                item {
                    Box(Modifier.appearIn(0)) {
                        InfoCard(
                            title = appText("Заявок пока нет", "Әлегә заявкалар юҡ"),
                            text = appText("Создай заявку на вкладке «Заявка» — водители откликнутся", "«Заявка» бүлегендә заявка яһа — водителдәр яуап бирер"),
                            icon = Icons.Default.ListAlt
                        )
                    }
                }
            } else {
                itemsIndexed(myRequests, key = { _, r -> r.id }) { i, r ->
                    // Закрытая заявка (время вышло / отменена) выглядела в точности как живая,
                    // и человек читал «смотреть отклики водителей» по заявке, откликов на
                    // которую уже не будет никогда. Подписываем честно.
                    val closed = r.status != "active"
                    val statusLine = when (r.status) {
                        "active" -> appText("Смотреть отклики водителей", "Водитель яуаптарын ҡарау")
                        "matched" -> appText("Водитель найден", "Водитель табылды")
                        "cancelled" -> appText("Заявка отменена", "Ғариза кире алынған")
                        else -> appText("Время вышло — откликов не будет",
                                        "Ваҡыт үтте — яуап булмаясаҡ")
                    }
                    Box(Modifier.appearIn(i)) {
                        ChatCard(
                            initial = r.fromCity.firstOrNull()?.uppercase() ?: "?",
                            name = "${r.fromCity} → ${r.toCity}",
                            subtitle = appText("Заявка", "Ғариза") + " · " + seatsText(r.seats) +
                                (if (closed) " · " + appText("закрыта", "ябыҡ") else ""),
                            message = statusLine,
                            time = "",
                            unread = 0,
                            verified = false,
                            onClick = { onOpenResponses(r.id) }
                        )
                    }
                }
            }
        } else {
            // Вкладка «Активные» — список диалогов по поездкам (сам чат открывается в поездке).
            if (conversations.isNotEmpty()) {
                itemsIndexed(conversations, key = { _, c -> c.bookingId }) { i, c ->
                    Box(Modifier.appearIn(i)) {
                        ChatCard(
                            initial = c.peerName.take(1).uppercase(),
                            name = c.peerName,
                            subtitle = c.route,
                            message = c.lastMessage,
                            time = c.departAt?.takeIf { it.isNotBlank() }?.let(::formatDepart) ?: "",   // время выезда — различать треды
                            unread = 0,
                            verified = c.peerVerified,   // реальный статус проверки собеседника (не фейк «проверен» у всех)
                            onClick = { onOpenChat(c.bookingId, c.peerName, c.route) },
                            avatarUrl = c.peerAvatar
                        )
                    }
                }
            } else {
                // Честные состояния вместо фейковых демо-диалогов: загрузка / ошибка / пусто.
                item {
                    Box(Modifier.appearIn(0)) {
                        when {
                            convLoading -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                repeat(4) { SkeletonCard(lines = 2) }
                            }
                            convError -> EmptyStateCard(
                                title = appText("Не удалось загрузить диалоги", "Диалогтарҙы йөкләп булманы"),
                                text = appText("Проверь интернет и повтори", "Интернетты тикшереп ҡабатла"),
                                icon = Icons.Default.Refresh,
                                action = appText("Повторить", "Ҡабатлау"),
                                onAction = { convReload++ },
                            )
                            else -> ChatEmptyState()
                        }
                    }
                }
            }
        }
        item {
            InfoCard(
                title = appText("Телефон открывается только после подтверждения поездки", "Телефон сәфәр раҫланғандан һуң ғына асыла"),
                text = appText("Мы заботимся о вашей безопасности", "Һеҙҙең хәүефһеҙлек тураһында ҡайғыртабыҙ"),
                icon = Icons.Default.Shield
            )
        }
        item { Spacer(Modifier.height(92.dp)) }
    }
}

/**
 * Чистый рендер ЛЕНТЫ СООБЩЕНИЙ чата по брони + поле ввода. Всё состояние — параметрами,
 * а сокет/отправка/голос/фото — колбэками. Умная обёртка (WebSocket `ChatSocket`, история по REST,
 * оптимистичная отправка, MediaPlayer) остаётся в `BookingActiveTripScreen` — сюда не тянем.
 * Благодаря чистоте (без сети/эффектов/state) лента тестируется на JVM (Robolectric).
 *
 * Поведение ленты 1:1 с боевым экраном:
 *  - loading (первая загрузка истории, сообщений ещё нет) → спиннер;
 *  - пусто → дружелюбная заглушка «Пока нет сообщений. Напиши первым»;
 *  - список → пузыри своих (справа, зелёный) и чужих (слева, светлый);
 *  - поле ввода + кнопка «Отправить» с гардом: пустой ввод или идёт отправка → кнопка выключена
 *    (нельзя слать пустое и нельзя дважды нажать во время отправки).
 * Голос/фото и WebSocket — в умной обёртке; сюда прокидываются опционально (`onEmoji` — вставка эмодзи).
 */
@Composable
internal fun ChatContent(
    messages: List<MessageDto>,
    input: String,
    sending: Boolean,
    loading: Boolean,
    myId: Int,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onEmoji: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // Как на боевом экране: удалённые/голос/непустой текст видимы, «пустые» технические — нет.
    val visibleMessages = messages.filter { it.deleted || it.voiceUrl != null || it.text.isNotBlank() }
    val canSend = input.isNotBlank() && !sending   // гард: пустое не шлём, во время отправки — тоже
    Column(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { Spacer(Modifier.height(8.dp)) }
            // B8-6: дисклеймер безопасности при первом открытии чата (закрывается «Понятно»).
            item { ChatSafetyDisclaimer() }
            if (visibleMessages.isEmpty()) {
                when {
                    loading -> item {
                        Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp, color = CanonGreen2)
                        }
                    }
                    else -> item {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Default.ChatBubbleOutline, contentDescription = null, tint = CanonMuted, modifier = Modifier.size(30.dp))
                            Text(
                                appText("Пока нет сообщений. Напиши первым", "Әлегә хәбәрҙәр юҡ. Беренсе булып яҙ"),
                                color = CanonMuted, fontSize = 14.sp, textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
            items(visibleMessages, key = { it.id }) { m ->
                Box(Modifier.fillMaxWidth().animateItem()) {   // плавное появление/перестановка пузыря в списке
                    ChatFeedBubble(
                        text = m.text, voiceUrl = m.voiceUrl, deleted = m.deleted, mine = m.senderId == myId,
                        flag = m.flag, fromAdmin = m.fromAdmin,
                    )
                }
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            colors = CardDefaults.cardColors(containerColor = CanonSurface),
            shape = RoundedCornerShape(28.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.card)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (onEmoji != null) {
                    IconButton(onClick = onEmoji, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.EmojiEmotions, contentDescription = appText("Эмодзи", "Эмодзи"), tint = CanonMuted, modifier = Modifier.size(22.dp))
                    }
                }
                Row(
                    modifier = Modifier.weight(1f).background(CanonMint, RoundedCornerShape(22.dp)).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
                        if (input.isBlank()) Text(appText("Сообщение", "Хәбәр"), color = CanonMuted, fontSize = 16.sp)
                        BasicTextField(
                            value = input,
                            onValueChange = { if (!sending) onInputChange(it) },   // во время отправки поле «заморожено»
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = TextStyle(color = CanonText, fontSize = 16.sp),
                            cursorBrush = SolidColor(CanonGreen2),
                            maxLines = 4
                        )
                    }
                }
                IconButton(
                    onClick = onSend,
                    enabled = canSend,   // пустой ввод / идёт отправка → нельзя (гард двойного тапа)
                    modifier = Modifier.size(48.dp).background(if (canSend) CanonGreen2 else CanonBorder, CircleShape)
                ) {
                    // disabled: фон = CanonBorder (светлый) → белая иконка исчезала. Гасим иконку в CanonMuted.
                    Icon(Icons.Default.Send, contentDescription = appText("Отправить", "Ебәреү"), tint = if (canSend) Color.White else CanonMuted)
                }
            }
        }
    }
}

/**
 * Один пузырь ленты (только рендер): удалённое / голос / фото / текст, свой справа-зелёный,
 * чужой слева-светлый. Логика меню/повтора/плеера остаётся в умном `MessageBubble` боевого экрана.
 * B8-6: warn у чужого сообщения → плашка «не сообщай коды из SMS»; B8-9: fromAdmin → бейдж «Юлдаш ✓».
 */
@Composable
private fun ChatFeedBubble(
    text: String, voiceUrl: String?, deleted: Boolean, mine: Boolean,
    flag: String = "", fromAdmin: Boolean = false,   // метка сервера: warn / contact / abuse
) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        if (fromAdmin && !deleted) YuldashOfficialBadge(Modifier.padding(bottom = 4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Surface(
            color = if (deleted) CanonSurface else if (mine) CanonGreen2 else CanonSurface,
            shape = RoundedCornerShape(14.dp),
            shadowElevation = CanonDepth.card,
            border = if (deleted) BorderStroke(1.dp, CanonBorder) else null
        ) {
            when {
                deleted -> Text(
                    appText("Сообщение удалено", "Хәбәр юйылды"),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    color = CanonMuted, fontSize = 14.sp
                )
                voiceUrl != null -> Text(
                    appText("Голосовое", "Тауыш"),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    color = if (mine) Color.White else CanonText, fontSize = 14.sp
                )
                else -> Text(
                    text,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    color = if (mine) Color.White else CanonText, fontSize = 16.sp
                )
            }
        }
        }
        if (flag.isNotEmpty() && !deleted) ChatFlagPlate(flag, mine, Modifier.padding(top = 4.dp))
    }
}


/** Плашка под сообщением по метке сервера. Три вида, и КОМУ показывать — тоже три разных ответа.
 *
 *  warn    — фишинг. Показываем ПОЛУЧАТЕЛЮ: предупреждаем того, у кого пытаются увести деньги.
 *  contact — телефон или увод в мессенджер. Показываем ОТПРАВИТЕЛЮ: это он рискует, объясняем чем.
 *            Собеседнику показывать нечего — он ничего не сделал.
 *  abuse   — грубость. Показываем ПОЛУЧАТЕЛЮ: он уже прочитал, помогаем понять, что делать.
 *            Отправителю не показываем: нотация в ответ на эмоцию только злит.
 *
 *  Сообщение при этом доставлено и видно — сервер ничего не блокирует, плашка только объясняет.
 */
@Composable
internal fun ChatFlagPlate(flag: String, mine: Boolean, modifier: Modifier = Modifier) {
    val forMe = when (flag) {
        "warn", "abuse" -> !mine     // читает получатель
        "contact" -> mine            // читает отправитель
        else -> false
    }
    if (!forMe) return
    val text = when (flag) {
        "warn" -> appText(
            "Никому не сообщай коды из SMS. Юлдаш никогда их не просит",
            "СМС-тағы кодтарҙы бер кемгә лә әйтмә. Юлдаш уларҙы бер ҡасан да һорамай",
        )
        "contact" -> appText(
            "Договариваться мимо приложения небезопасно: поездка не будет застрахована, " +
                "и решить спор будет нельзя",
            "Ҡулланманан тыш килешеү хәүефһеҙ түгел: сәфәр иминләштерелмәй, " +
                "бәхәсте хәл итеп булмай",
        )
        else -> appText(
            "Сообщение содержит грубые слова",
            "Хәбәрҙә ҡытыр һүҙҙәр бар",
        )
    }
    Row(
        modifier = modifier
            .background(CanonWarnBg, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "⚠️ $text",
            color = CanonWarn, fontSize = 12.sp, lineHeight = 17.sp,
        )
    }
}


/** B8-9: бейдж официальности «Юлдаш ✓» — только по серверному флагу from_admin
 *  (мошенник не может прикинуться поддержкой: флаг ставит сервер по роли отправителя). */
@Composable
internal fun YuldashOfficialBadge(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(CanonMint, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Юлдаш ✓", color = CanonGreen2, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}


/** B8-6: тонкий баннер-дисклеймер безопасности при первом открытии чата. «Понятно» —
 *  больше не показываем (метка в prefs). Появляется/уходит мягко (AnimatedVisibility). */
@Composable
internal fun ChatSafetyDisclaimer(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("yuldash", android.content.Context.MODE_PRIVATE) }
    var visible by remember { mutableStateOf(!prefs.getBoolean("chat_safety_seen", false)) }
    AnimatedVisibility(visible = visible) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .background(CanonWarnBg, RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "⚠️ " + appText(
                    "Никому не сообщай коды из SMS и не переводи деньги «на другой номер». Юлдаш никогда их не просит",
                    "СМС-тағы кодтарҙы бер кемгә лә әйтмә һәм «башҡа номерға» аҡса күсермә. Юлдаш уларҙы бер ҡасан да һорамай",
                ),
                color = CanonWarn, fontSize = 12.sp, lineHeight = 17.sp,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                appText("Понятно", "Аңлашылды"),
                color = CanonGreen2, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .minimumInteractiveComponentSize()   // тач-цель ≥48dp
                    .bounceClick {
                        prefs.edit().putBoolean("chat_safety_seen", true).apply()
                        visible = false
                    }
                    .padding(4.dp),
            )
        }
    }
}

/** Лента заявок пассажиров — водитель откликается (цена/коммент). */
@Composable
internal fun RequestsFeedScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var feed by remember { mutableStateOf<List<com.yuldash.app.data.RequestFeedDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf<com.yuldash.app.data.RequestFeedDto?>(null) }
    var price by remember { mutableStateOf("") }
    var comment by remember { mutableStateOf("") }
    var responding by remember { mutableStateOf(false) }   // защита от двойного тапа + чтобы показать ошибку до закрытия диалога
    var withdrawTarget by remember { mutableStateOf<com.yuldash.app.data.RequestFeedDto?>(null) }   // заявка, чей отклик отзываем (подтверждение)
    var withdrawing by remember { mutableStateOf(false) }
    val sentMsg = appText("Отклик отправлен", "Яуап ебәрелде")
    val respondErr = appText("Не удалось отправить отклик. Проверь сеть и повтори.", "Яуап ебәреп булманы. Сетте тикшереп ҡабатла.")
    val withdrawnMsg = appText("Отклик отозван", "Яуап кире алынды")
    val withdrawErr = appText("Не удалось отозвать. Проверь сеть и повтори.", "Кире алып булманы. Сетте тикшереп ҡабатла.")
    val withdrawTakenErr = appText("Пассажир уже принял отклик — отозвать нельзя.", "Пассажир яуапты ҡабул иткән — кире алып булмай.")
    // Сбой сети больше не маскируется под «заявок нет» — показываем ошибку с «Повторить».
    fun reload() { loading = true; error = false; scope.launch { ApiClient.getRequestsFeed().onSuccess { feed = it }.onFailure { error = true }; loading = false } }
    LaunchedEffect(Unit) { reload() }
    withdrawTarget?.let { t ->
        AlertDialog(
            onDismissRequest = { if (!withdrawing) withdrawTarget = null },
            containerColor = CanonSurface,
            title = { Text(appText("Отозвать отклик?", "Яуапты кире аларғамы?"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = { Text(appText("Пассажир больше не увидит ваш отклик на «${t.from} → ${t.to}».", "Пассажир «${t.from} → ${t.to}» яуабығыҙҙы башҡа күрмәйәсәк."), color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp) },
            confirmButton = {
                TextButton(enabled = !withdrawing, onClick = {
                    val respId = t.myResponseId ?: return@TextButton
                    withdrawing = true
                    scope.launch {
                        ApiClient.deleteResponse(respId)
                            .onSuccess { withdrawing = false; withdrawTarget = null; Toast.makeText(ctx, withdrawnMsg, Toast.LENGTH_SHORT).show(); reload() }
                            .onFailure { e ->
                                withdrawing = false
                                val taken = (e as? com.yuldash.app.data.ApiException)?.status == 409
                                Toast.makeText(ctx, if (taken) withdrawTakenErr else withdrawErr, Toast.LENGTH_LONG).show()
                                if (taken) { withdrawTarget = null; reload() }   // уже принят → обновим ленту, кнопки отзыва там уже не будет
                            }
                    }
                }) { Text(if (withdrawing) appText("Отзываем…", "Кире алабыҙ…") else appText("Отозвать", "Кире алыу"), color = CanonRed, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(enabled = !withdrawing, onClick = { withdrawTarget = null }) { Text(appText("Оставить", "Ҡалдырыу"), color = CanonMuted) } },
        )
    }
    target?.let { t ->
        AlertDialog(
            onDismissRequest = { target = null },
            containerColor = CanonSurface,
            title = { Text("${t.from} → ${t.to}", color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(price, { price = it.filter { c -> c.isDigit() }.take(6) }, label = { Text(appText("Цена, ₽", "Хаҡ, ₽")) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(comment, { comment = it }, label = { Text(appText("Когда едете / детали", "Ҡасан / детальдәр")) }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                }
            },
            confirmButton = {
                TextButton(enabled = !responding, onClick = {
                    val rid = t.id; val p = price.toIntOrNull() ?: 0; val c = comment.trim()
                    responding = true
                    // Не гасим диалог до ответа сервера — при сбое покажем ошибку, отклик не потеряется молча.
                    scope.launch {
                        ApiClient.respondToRequest(rid, p, c)
                            .onSuccess { responding = false; target = null; price = ""; comment = ""; Toast.makeText(ctx, sentMsg, Toast.LENGTH_SHORT).show(); reload() }
                            .onFailure { responding = false; Toast.makeText(ctx, respondErr, Toast.LENGTH_LONG).show() }
                    }
                }) { Text(if (responding) appText("Отправляем…", "Ебәрәбеҙ…") else appText("Отправить", "Ебәреү"), color = CanonGreen2, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(enabled = !responding, onClick = { target = null }) { Text(appText("Отмена", "Баш тартыу"), color = CanonMuted) } },
        )
    }
    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Заявки пассажиров", "Пассажир заявкалары"), onBack) }) { padding ->
        RequestsFeedContent(
            loading = loading,
            error = error,
            feed = feed,
            onRetry = { reload() },
            onRespond = { r -> target = r; price = ""; comment = "" },
            onWithdraw = { r -> withdrawTarget = r },
            modifier = Modifier.padding(padding),
        )
    }
}

/**
 * Чистый рендер ленты заявок пассажиров: все состояния (загрузка / ошибка+повтор / пусто / список).
 * Данные и колбэки приходят параметрами → без сети/стейта/эффектов → тестируется на JVM (Robolectric).
 */
@Composable
internal fun RequestsFeedContent(
    loading: Boolean,
    error: Boolean,
    feed: List<com.yuldash.app.data.RequestFeedDto>,
    onRetry: () -> Unit,
    onRespond: (com.yuldash.app.data.RequestFeedDto) -> Unit,
    onWithdraw: (com.yuldash.app.data.RequestFeedDto) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
        item { Text(appText("Пассажиры ищут поездку. Откликнись — предложи цену и время.", "Пассажирҙар сәфәр эҙләй. Яуап бир — хаҡ һәм ваҡыт тәҡдим ит."), color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp) }
        if (loading) {
            item { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { repeat(3) { SkeletonCard(lines = 3) } } }
        } else if (error) {
            item { ListedError(appText("Не удалось загрузить заявки. Проверь сеть.", "Заявкаларҙы йөкләп булманы. Сетте тикшер."), onRetry = onRetry) }
        } else if (feed.isEmpty()) {
            item { ListedEmpty(appText("Заявок пока нет", "Әлегә заявкалар юҡ"), appText("Здесь появятся заявки пассажиров.", "Бында пассажир заявкалары күренер")) }
        } else {
            items(feed, key = { it.id }) { r ->
                Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${r.from} → ${r.to}", color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SmallAvatar(r.passengerAvatar, r.passengerName, 34)
                            Spacer(Modifier.width(8.dp))
                            Text("${r.passengerName} · " + seatsText(r.seats), color = CanonMuted, fontSize = 14.sp)
                        }
                        if (r.comment.isNotBlank()) Text(r.comment, color = CanonMuted, fontSize = 14.sp)
                        if (r.prefs.isNotEmpty()) {
                            // Условия пассажира → водитель видит, подходит ли поездка.
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                items(r.prefs, key = { it }) { key ->
                                    when (key) {
                                        "women" -> PrefChip(R.drawable.yu_women_only, appText("Только женщины", "Тик ҡатын-ҡыҙ"))
                                        "child" -> PrefChip(R.drawable.yu_child_seat, appText("Детское кресло", "Балалар ултырғысы"))
                                        "pets" -> PrefChip(R.drawable.yu_pet, appText("С животным", "Хайуан менән"))
                                        "wheelchair" -> PrefChip(R.drawable.yu_accessible, appText("Коляска", "Коляска"))
                                        "baggage" -> PrefChip(R.drawable.yu_luggage, appText("Багаж", "Багаж"))
                                        "nosmoke" -> PrefChip(R.drawable.yu_smoke_free, appText("Не курить", "Тартмаҫҡа"))
                                        "ac" -> PrefChip(R.drawable.yu_ac, appText("Кондиционер", "Кондиционер"))
                                    }
                                }
                            }
                        }
                        if (r.responded) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(appText("Вы откликнулись", "Яуап бирҙегеҙ"), color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Spacer(Modifier.weight(1f))
                            // Отозвать можно, пока пассажир не принял (после accept заявка уходит из ленты; сервер всё равно вернёт 409).
                            if (r.myResponseId != null) TextButton(
                                onClick = { onWithdraw(r) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            ) { Text(appText("Отозвать отклик", "Яуапты кире алыу"), color = CanonRed, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
                        }
                        else Button(onClick = { onRespond(r) }, modifier = Modifier.align(Alignment.End), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)) { Text(appText("Предложить поездку", "Сәфәр тәҡдим итеү"), fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    }
}

/** Отклики водителей на МОЮ заявку — пассажир выбирает → поездка+чат.
 *  Умная обёртка: держит стейт, грузит отклики, ходит в ApiClient.
 *  Рендер вынесен в чистый [ResponsesContent] → его покрывают Robolectric-тесты. */
@Composable
internal fun ResponsesScreen(requestId: Int, onBack: () -> Unit, onAccepted: (Int) -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var resps by remember { mutableStateOf<List<com.yuldash.app.data.ResponseDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var accepting by remember { mutableStateOf(false) }
    var reloadTick by remember { mutableStateOf(0) }
    var counterFor by remember { mutableStateOf<com.yuldash.app.data.ResponseDto?>(null) }
    val failMsg = appText("Не получилось принять", "Ҡабул итеп булманы")
    // Сбой загрузки откликов больше не выглядит как «откликов нет» — ошибка + «Повторить».
    LaunchedEffect(requestId, reloadTick) { loading = true; error = false; ApiClient.getRequestResponses(requestId).onSuccess { resps = it }.onFailure { error = true }; loading = false }
    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Отклики водителей", "Водитель яуаптары"), onBack) }) { padding ->
        ResponsesContent(
            loading = loading,
            error = error,
            responses = resps,
            accepting = accepting,
            onRetry = { reloadTick++ },
            onAccept = { r ->
                if (accepting) return@ResponsesContent
                accepting = true; val id = r.id
                scope.launch {
                    ApiClient.acceptResponse(id)
                        .onSuccess { bid -> onAccepted(bid) }
                        .onFailure { Toast.makeText(ctx, failMsg, Toast.LENGTH_SHORT).show(); accepting = false }
                }
            },
            // Торг: своя цена и «не договорились». Ошибку сервера показываем как есть — она
            // двуязычная и объясняет причину («сейчас ход другой стороны», «торг окончен»).
            onCounter = { r -> counterFor = r },
            onDecline = { r ->
                if (accepting) return@ResponsesContent
                accepting = true; val id = r.id
                scope.launch {
                    ApiClient.declineResponse(id)
                        .onFailure { Toast.makeText(ctx, (it as? com.yuldash.app.data.ApiException)?.message ?: failMsg, Toast.LENGTH_SHORT).show() }
                    accepting = false; reloadTick++
                }
            },
            modifier = Modifier.padding(padding),
        )
    }
    counterFor?.let { target ->
        CounterPriceDialog(
            current = target.onTable,
            roundsLeft = (BARGAIN_MAX_TOTAL_UI - target.bargainRounds).coerceAtLeast(1),
            busy = accepting,
            onDismiss = { counterFor = null },
            onSend = { price ->
                accepting = true; val id = target.id
                scope.launch {
                    ApiClient.counterOffer(id, price)
                        .onFailure { Toast.makeText(ctx, (it as? com.yuldash.app.data.ApiException)?.message ?: failMsg, Toast.LENGTH_LONG).show() }
                    accepting = false; counterFor = null; reloadTick++
                }
            },
        )
    }
}

/** Сколько встречных всего допускает сервер (BARGAIN_MAX_ROUNDS × 2) — для подписи «осталось ходов». */
internal const val BARGAIN_MAX_TOTAL_UI = 6

/**
 * Чистый рендер списка откликов водителей: все состояния (загрузка / ошибка+повтор / пусто / список).
 * Данные и колбэки приходят параметрами → без сети/стейта/эффектов → тестируется на JVM (Robolectric).
 */
@Composable
internal fun ResponsesContent(
    loading: Boolean,
    error: Boolean,
    responses: List<com.yuldash.app.data.ResponseDto>,
    accepting: Boolean,
    onRetry: () -> Unit,
    onAccept: (com.yuldash.app.data.ResponseDto) -> Unit,
    // Торг о цене: параметры со значениями по умолчанию — старые вызовы (и тесты) не ломаются.
    onCounter: (com.yuldash.app.data.ResponseDto) -> Unit = {},
    onDecline: (com.yuldash.app.data.ResponseDto) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
        item { Text(appText("Выберите водителя — поездка начнётся, откроется чат. Цена не подходит — предложи свою.", "Водитель һайла — сәфәр башлана, чат асыла. Хаҡ ярамаһа — үҙеңдекен тәҡдим ит."), color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp) }
        if (loading) {
            item { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { repeat(3) { SkeletonCard(lines = 3) } } }
        } else if (error) {
            item { ListedError(appText("Не удалось загрузить отклики. Проверь сеть.", "Яуаптарҙы йөкләп булманы. Сетте тикшер."), onRetry = onRetry) }
        } else if (responses.isEmpty()) {
            item { ListedEmpty(appText("Откликов пока нет", "Әлегә яуап юҡ"), appText("Водители ещё не откликнулись. Загляни позже.", "Водителдәр яуап бирмәгән. Һуңыраҡ кер.")) }
        } else {
            items(responses, key = { it.id }) { r ->
                Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SmallAvatar(r.driverAvatar, r.driverName, 42)
                            Spacer(Modifier.width(8.dp))
                            Text(r.driverName, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            r.driverRating?.let { Spacer(Modifier.width(4.dp)); Icon(Icons.Default.Star, contentDescription = null, tint = CanonStar, modifier = Modifier.size(14.dp)); Text(" $it", color = CanonMuted, fontSize = 14.sp) }
                            Spacer(Modifier.weight(1f))
                            // Цена НА СТОЛЕ (после торга), а не первое предложение водителя:
                            // поездка создастся именно по ней.
                            if (r.onTable > 0) Text("${r.onTable} ₽", color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                        }
                        if (r.comment.isNotBlank()) Text(r.comment, color = CanonMuted, fontSize = 14.sp)
                        BargainSummary(r)
                        // Старый сервер не шлёт флаги торга → ведём себя как раньше: пока торга
                        // не было, пассажир принимает цену водителя.
                        val canTake = r.canAccept || (r.bargainRounds == 0 && r.lastOfferBy == "driver")
                        if (canTake) {
                            Button(
                                onClick = { onAccept(r) },
                                enabled = !accepting,
                                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)
                            ) { Text(appText("Поехать с этим водителем", "Был водитель менән барырға"), fontWeight = FontWeight.Bold) }
                        }
                        if (r.canCounter) {
                            TextButton(onClick = { onCounter(r) }, enabled = !accepting, modifier = Modifier.fillMaxWidth()) {
                                Text(appText("Предложить свою цену", "Үҙ хаҡыңды тәҡдим итеү"), color = CanonGreen2, fontWeight = FontWeight.Bold)
                            }
                        }
                        if (r.status == "offered" && (canTake || r.canCounter)) {
                            TextButton(onClick = { onDecline(r) }, enabled = !accepting, modifier = Modifier.fillMaxWidth()) {
                                Text(appText("Не договорились", "Килешмәнек"), color = CanonMuted, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Бейдж «на линии» — зелёная точка + текст. Водитель доступен сейчас. */
@Composable
internal fun OnlineBadge() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(CanonGreen2, CircleShape))
        Spacer(Modifier.width(4.dp))
        Text(appText("на линии", "эштә"), color = CanonGreen2, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

/** Бейдж «Водитель-женщина» — деликатный сигнал для пассажирок (F9, строго opt-in).
 *  Показываем ТОЛЬКО когда сама водитель указала пол «женщина». Мужской пол не выпячиваем. */
@Composable
internal fun WomanDriverBadge() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(CanonWomanBg, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Icon(Icons.Default.Woman, contentDescription = null, tint = CanonWoman, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(4.dp))
        Text(appText("За рулём женщина", "Рулдә ҡатын-ҡыҙ"), color = CanonWoman, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

/** Кружок-аватар: фото (Coil) или буква имени. Для карточек выбора попутчика. */
@Composable
internal fun SmallAvatar(url: String, initial: String, size: Int = 44) {
    Box(Modifier.size(size.dp).background(CanonMint, CircleShape), contentAlignment = Alignment.Center) {
        if (url.isBlank()) {
            Text(initial.take(1).uppercase().ifBlank { "?" }, color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = (size / 2.6f).sp)
        } else {
            coil.compose.AsyncImage(model = url, contentDescription = null, modifier = Modifier.size(size.dp).clip(CircleShape), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        }
    }
}

@Composable
internal fun ListedEmpty(title: String, subtitle: String) {
    Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(Icons.Default.ListAlt, contentDescription = null, tint = CanonMuted, modifier = Modifier.size(34.dp))
            Text(title, color = CanonText, fontWeight = FontWeight.Bold)
            Text(subtitle, color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp)
        }
    }
}

/** Состояние ошибки загрузки списка: понятный текст + «Повторить». Чтобы сетевой сбой
 *  НЕ выглядел как «пусто» (важно для админ-лент — иначе можно решить, что водителей/жалоб нет). */
@Composable
internal fun ListedError(message: String, onRetry: () -> Unit) {
    Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(message, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 20.sp)
            Button(onClick = onRetry, shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)) {
                Text(appText("Повторить", "Ҡабатларға"), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
internal fun ChatCard(initial: String, name: String, subtitle: String, message: String, time: String, unread: Int, verified: Boolean, support: Boolean = false, onClick: (() -> Unit)? = null, avatarUrl: String = "") {
    Card(
        modifier = if (onClick != null) Modifier.bounceClick(onClick) else Modifier,
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = RoundedCornerShape(22.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.card)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .background(if (support) CanonGreen2 else MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (avatarUrl.isBlank()) {
                    Text(initial, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = if (support) Color.White else MaterialTheme.colorScheme.primary)
                } else {
                    coil.compose.AsyncImage(
                        model = avatarUrl,
                        contentDescription = null,
                        modifier = Modifier.size(58.dp).clip(CircleShape),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold)
                    if (verified) {
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Default.Verified, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    }
                }
                Text(subtitle, fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.Bold)
                Text(message, color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(time, color = CanonMuted, fontSize = 14.sp)
                if (unread > 0) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(unread.toString(), color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
internal fun ChatComposer(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onVoiceRecorded: (String, Int) -> Unit,
    onPhotoPicked: (ByteArray) -> Unit = {},
    onQuickSend: (String) -> Unit = {},   // тап по готовой фразе → отправить сразу (тот же путь, что и обычное сообщение)
) {
    val context = LocalContext.current
    val recorder = remember { VoiceRecorder(context) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            if (bytes != null) onPhotoPicked(bytes)
        }
    }
    var recording by remember { mutableStateOf(false) }
    var startMs by remember { mutableStateOf(0L) }
    fun begin() { if (recorder.start()) { recording = true; startMs = SystemClock.elapsedRealtime() } }
    fun finish() {
        val p = recorder.stop()
        val dur = ((SystemClock.elapsedRealtime() - startMs) / 1000).toInt().coerceAtLeast(1)
        recording = false
        if (p != null) onVoiceRecorded(p, dur)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) begin() }
    fun requestVoice() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) begin()
        else permLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
    var showEmoji by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    // Быстрые ответы: частые фразы в дороге — тап отправляет сразу (без набора).
    AnimatedVisibility(visible = !recording && !showEmoji) {
        val quickReplies = listOf(
            appText("Выезжаю", "Сығам"),
            appText("Жду у подъезда", "Подъезд янында көтәм"),
            appText("Опаздываю на 5 минут", "5 минутҡа һуңлайым"),
            appText("Я на месте", "Урынымда"),
            appText("Спасибо!", "Рәхмәт!"),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 4.dp),
        ) {
            items(quickReplies, key = { it }) { phrase -> QuickReplyChip(phrase) { onQuickSend(phrase) } }
        }
    }
    AnimatedVisibility(visible = showEmoji && !recording) {
        EmojiPicker(onPick = { e -> onDraftChange(draft + e) })
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = RoundedCornerShape(28.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.card)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (recording) {
                Spacer(Modifier.width(4.dp))
                Box(Modifier.size(12.dp).background(CanonRed, CircleShape))
                Text(
                    appText("Идёт запись… отправить →", "Яҙыла… ебәреү →"),
                    modifier = Modifier.weight(1f).padding(start = 12.dp),
                    color = CanonText, fontSize = 14.sp
                )
            } else {
                // «+» — прикрепить фото (голос — отдельной кнопкой-микрофоном справа)
                IconButton(onClick = { photoPicker.launch("image/*") }, modifier = Modifier.size(48.dp)) {   // тач-цель ≥48dp
                    Icon(Icons.Default.Add, contentDescription = appText("Прикрепить фото", "Фото беркетеү"), tint = CanonGreen2)
                }
                // Пилюля: эмодзи + поле ввода
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .background(CanonMint, RoundedCornerShape(22.dp))
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            showEmoji = !showEmoji
                            if (showEmoji) focusManager.clearFocus()   // прячем системную клавиатуру → видна наша панель
                        },
                        modifier = Modifier.size(48.dp)   // тач-цель ≥48dp (было 36)
                    ) {
                        Icon(Icons.Default.EmojiEmotions, contentDescription = appText("Эмодзи", "Эмодзи"), tint = if (showEmoji) CanonGreen2 else CanonMuted, modifier = Modifier.size(22.dp))
                    }
                    Box(modifier = Modifier.weight(1f).padding(horizontal = 4.dp, vertical = 12.dp)) {
                        if (draft.isBlank()) Text(appText("Сообщение", "Хәбәр"), color = CanonMuted, fontSize = 16.sp)
                        BasicTextField(
                            value = draft,
                            onValueChange = onDraftChange,
                            modifier = Modifier.fillMaxWidth().focusRequester(focus)
                                .onFocusChanged { if (it.isFocused) showEmoji = false },
                            textStyle = TextStyle(color = CanonText, fontSize = 16.sp),
                            cursorBrush = SolidColor(CanonGreen2),
                            maxLines = 4
                        )
                    }
                }
            }
            // Кнопка справа: микрофон (пусто) / отправить (есть текст или идёт запись)
            IconButton(
                onClick = {
                    when {
                        recording -> finish()
                        draft.isNotBlank() -> onSend()
                        else -> requestVoice()
                    }
                },
                modifier = Modifier
                    .size(48.dp)
                    .background(if (recording) CanonRed else CanonGreen2, CircleShape)
            ) {
                Icon(
                    if (recording || draft.isNotBlank()) Icons.Default.Send else Icons.Default.Mic,
                    contentDescription = if (recording) appText("Отправить запись", "Яҙманы ебәреү") else if (draft.isBlank()) appText("Записать голос", "Тауыш яҙҙырыу") else appText("Отправить", "Ебәреү"),
                    tint = Color.White
                )
            }
        }
    }
    }
}

// Чип «быстрого ответа»: готовая фраза, тач-цель ≥48dp, тап отправляет сразу.
@Composable
private fun QuickReplyChip(text: String, onClick: () -> Unit) {
    Surface(
        color = CanonMint,
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, CanonHairlineGreen),
        modifier = Modifier.heightIn(min = 48.dp).bounceClick(onClick = onClick),
    ) {
        Box(Modifier.fillMaxHeight().padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Text(text, color = CanonGreen2, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

// Дружелюбная заглушка «нет диалогов» — полноценная, сбалансированная (не «половина пустая»).
@Composable
internal fun ChatEmptyState() {
    Card(
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = CanonCardShape,
        elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.card),
        border = BorderStroke(1.dp, CanonBorder)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(color = CanonMint, shape = CircleShape) {
                Icon(
                    Icons.Default.ChatBubbleOutline,
                    contentDescription = null,
                    tint = CanonGreen2,
                    modifier = Modifier.padding(24.dp).size(38.dp)
                )
            }
            Text(
                appText("Здесь будут ваши чаты", "Бында чаттарығыҙ булыр"),
                color = CanonText, fontWeight = FontWeight.Bold, fontSize = 19.sp, textAlign = TextAlign.Center
            )
            Text(
                appText(
                    "Найдите поездку и забронируйте место — после брони откроется чат с водителем или пассажиром.",
                    "Сәфәр табып, урын бронла — бронынан һуң водитель йәки пассажир менән чат асыла."
                ),
                color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center
            )
            Surface(color = CanonMint, shape = RoundedCornerShape(14.dp)) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("💬", fontSize = 19.sp)
                    Text(
                        appText("Например: «Я на остановке у рынка»", "Мәҫәлән: «Мин баҙар туҡталышында»"),
                        color = CanonGreen, fontSize = 14.sp, fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

// Эмодзи под Юлдаш: транспорт/поездка/навигация/деньги/жесты — впереди,
// дальше базовые эмоции и сердца (для живого общения водитель↔пассажир).
private val CHAT_EMOJIS = listOf(
    "🚗","🚕","🚙","🚐","🏎️","🛻","🛣️","🧭",   // транспорт
    "📍","🗺️","🕐","⏰","⏳","🅿️","🚦","🚸",   // навигация/время/дорога
    "🧳","🎒","💺","⛽","💰","💵","💳","🤝",   // вещи/деньги/договор
    "👋","🙏","👍","👎","👏","💪","🤙","✌️",   // жесты
    "😀","😁","😂","🤣","😊","😍","😎","🤗",   // эмоции
    "🙂","😉","🤔","😅","😴","😭","😡","🥳",
    "❤️","🧡","💛","💚","💙","💜","🤍","💯",   // сердца/реакции
    "🔥","✨","⭐","✅","❌","⚠️","☀️","🌧️"    // акценты/погода
)

@Composable
internal fun EmojiPicker(onPick: (String) -> Unit) {
    Surface(
        color = CanonSurface,
        shape = RoundedCornerShape(22.dp),
        shadowElevation = CanonDepth.card,
        border = BorderStroke(1.dp, CanonBorder)
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 220.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            CHAT_EMOJIS.chunked(8).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    row.forEach { e ->
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .clickable { onPick(e) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(e, fontSize = 23.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun VoiceMessageCard(message: LocalVoiceMessage) {
    var playing by remember { mutableStateOf(false) }
    val player = remember { mutableStateOf<MediaPlayer?>(null) }
    DisposableEffect(message.audioPath) { onDispose { runCatching { player.value?.release() }; player.value = null } }
    Card(colors = CardDefaults.cardColors(containerColor = CanonSurface), shape = CanonItemShape, elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.card)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = CanonGreen2, shape = CircleShape) {
                IconButton(
                    onClick = {
                        val path = message.audioPath ?: return@IconButton
                        if (playing) {
                            runCatching { player.value?.stop(); player.value?.release() }
                            player.value = null; playing = false
                        } else {
                            runCatching {
                                player.value = MediaPlayer().apply {
                                    setDataSource(path)
                                    setOnCompletionListener { playing = false; runCatching { release() }; player.value = null }
                                    prepare(); start()
                                }
                                playing = true
                            }
                        }
                    },
                    modifier = Modifier.padding(4.dp)
                ) {
                    Icon(
                        if (playing) Icons.Default.Close else if (message.audioPath != null) Icons.Default.PlayArrow else Icons.Default.VolumeUp,
                        contentDescription = appText("Воспроизвести", "Уйнатыу"),
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(appText("Голосовое от ${message.author}", "Тауыш хәбәр: ${message.author}"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                if (message.audioPath != null) {
                    Text(appText("${message.durationSec} сек · нажмите ▶", "${message.durationSec} сек · ▶ баҫығыҙ"), color = CanonMuted, fontSize = 14.sp)
                } else {
                    Text(message.transcript, color = CanonText, fontSize = 14.sp, lineHeight = 20.sp)
                    Text(appText("Расшифровка для водителя", "Водитель өсөн текст"), color = CanonMuted, fontSize = 12.sp)
                }
            }
            Text(message.time, color = CanonMuted, fontSize = 12.sp)
        }
    }
}
