package com.yuldash.app

// Экраны брони (BookingScreen) и активной поездки (ActiveTripScreen: чат/статус/SOS).
// Вынесено из MainActivity (Фаза 2). Импорты целиком — лишние = варнинги.

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
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Edit
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.text.font.FontStyle
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
import androidx.compose.material.icons.filled.Phone
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
import androidx.lifecycle.Lifecycle
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
import androidx.compose.foundation.layout.sizeIn
import coil.compose.AsyncImage
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
import com.yuldash.app.data.ChatSocket
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
internal fun BookingScreen(
    ride: Ride,
    bookingId: Int? = null,
    ads: List<PartnerAd>,
    adStats: Map<String, AdStats>,
    onBack: () -> Unit,
    onSelectTab: (HomeTab) -> Unit,
    onMessage: () -> Unit,
    onAdImpression: (PartnerAd) -> Unit,
    onAdClick: (PartnerAd) -> Unit,
    canOpenActiveTrip: Boolean = true,
    onConfirmRide: () -> Unit
) {
    val routeAd = ads.forPlacement(AdPlacement.TripDetails).firstOrNull { it.matchesRoute(ride.from, ride.to) }
    val context = LocalContext.current
    var details by remember(bookingId) { mutableStateOf<com.yuldash.app.data.BookingDetailsDto?>(null) }
    var detailsLoading by remember(bookingId) { mutableStateOf(bookingId != null) }
    var detailsError by remember(bookingId) { mutableStateOf(false) }
    var detailsReload by remember(bookingId) { mutableIntStateOf(0) }
    LaunchedEffect(bookingId, detailsReload) {
        val bid = bookingId ?: return@LaunchedEffect
        detailsLoading = true
        ApiClient.getBookingDetails(bid)
            .onSuccess { loaded -> details = loaded; detailsError = false }
            .onFailure { detailsError = true }
        detailsLoading = false
    }
    val displayRide = details?.let {
        ride.copy(
            id = it.rideId.toString(),
            from = it.fromCity.ifBlank { ride.from },
            to = it.toCity.ifBlank { ride.to },
            time = formatDepart(it.departAt.ifBlank { ride.time }),
            driver = it.driverName.ifBlank { ride.driver },
            car = it.driverCar.ifBlank { ride.car },
            price = if (it.price > 0) it.price else ride.price,
            seats = it.seats,
            verified = it.driverVerified || ride.verified,
            pickup = it.pickup,
            pickupLat = it.pickupLat,
            pickupLng = it.pickupLng,
        )
    } ?: ride
    val contactUnlocked = details?.contactUnlocked == true
    val driverPhone = details?.driverPhone.orEmpty()
    val exactPickup = if (contactUnlocked) displayRide.pickup else ""
    val pickupLat = if (contactUnlocked) displayRide.pickupLat else null
    val pickupLng = if (contactUnlocked) displayRide.pickupLng else null
    val routeFromPoint = details?.let { d -> d.fromLat?.let { lat -> d.fromLng?.let { lng -> Point(lat, lng) } } }
    val routeToPoint = details?.let { d -> d.toLat?.let { lat -> d.toLng?.let { lng -> Point(lat, lng) } } }
    Scaffold(
        containerColor = CanonBg,
        bottomBar = { YuldashBottomBar(selectedTab = HomeTab.Rides, onSelect = onSelectTab) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 116.dp)
        ) {
            item {
                Spacer(Modifier.height(10.dp))
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBackIosNew, contentDescription = appText("Назад", "Кире"), tint = CanonText)
                    }
                    Text(appText("Детали поездки", "Сәфәр тураһында"), modifier = Modifier.weight(1f), color = CanonGreen, fontSize = 26.sp, lineHeight = 28.sp, fontWeight = FontWeight.Black)
                    val shareTitle = appText("Позвать соседа", "Күршене саҡырырға")
                    val shareText = appText(
                        "Еду ${displayRide.from} → ${displayRide.to}, ${displayRide.timeText()}. ${displayRide.price} ₽. Поехали вместе в Юлдаше 👇\nhttps://yulbash.ru",
                        "${displayRide.from} → ${displayRide.to}, ${displayRide.timeText()}. ${displayRide.price} ₽. Әйҙә бергә — Юлдашта 👇\nhttps://yulbash.ru"
                    )
                    IconButton(onClick = { shareRide(context, shareText, shareTitle) }) {
                        Icon(Icons.Default.Share, contentDescription = shareTitle, tint = CanonGreen2)
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = CanonSurface), shape = CanonItemShape, elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        RouteMiniIcon()
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text("${displayRide.from}  →  ${displayRide.to}", color = CanonText, fontSize = 19.sp, lineHeight = 21.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                DetailMeta(Icons.Default.CalendarMonth, displayRide.timeText(), modifier = Modifier.weight(1.45f))
                                DetailMeta(Icons.Default.Person, seatsText(displayRide.seats), modifier = Modifier.weight(0.8f))
                            }
                        }
                        Surface(color = CanonMint, shape = RoundedCornerShape(12.dp)) {
                            Text("${displayRide.price} ₽", modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp), color = CanonGreen2, fontWeight = FontWeight.Black, fontSize = 14.sp)
                        }
                    }
                }
            }
            if (detailsLoading) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = CanonSurface), shape = CanonItemShape) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), color = CanonGreen2, strokeWidth = 2.dp)
                            Text(appText("Обновляем подтверждение поездки", "Сәфәр раҫланыуын яңыртабыҙ"), color = CanonMuted, fontSize = 14.sp)
                        }
                    }
                }
            }
            if (detailsError) {
                item {
                    EmptyStateCard(
                        title = appText("Не удалось обновить детали", "Ентекле мәғлүмәтте яңыртып булманы"),
                        text = appText("Телефон и точка встречи останутся закрытыми, пока сервер не подтвердит доступ.", "Сервер рөхсәтте раҫлағансы, телефон һәм осрашыу урыны ябыҡ ҡала."),
                        icon = Icons.Default.Refresh,
                        action = appText("Повторить", "Ҡабатлау"),
                        onAction = { detailsReload++ }
                    )
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = CanonSurface), shape = CanonCardShape, elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(color = CanonMint, shape = CircleShape) {
                                Text(displayRide.driver.firstOrNull()?.uppercase() ?: "?", modifier = Modifier.padding(22.dp), color = CanonGreen2, fontWeight = FontWeight.Black, fontSize = 22.sp)
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(displayRide.driver, color = CanonText, fontWeight = FontWeight.Black, fontSize = 21.sp)
                                    if (displayRide.verified) {
                                        Spacer(Modifier.width(6.dp))
                                        Icon(Icons.Default.Verified, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(20.dp))
                                    }
                                }
                                Text(appText("Опытный водитель", "Тәжрибәле водитель"), color = CanonMuted, fontSize = 14.sp)
                                DetailMeta(Icons.Default.DirectionsCar, displayRide.carText())
                            }
                            Surface(
                                color = CanonMint,
                                shape = CircleShape,
                                modifier = if (contactUnlocked && driverPhone.isNotBlank()) Modifier.bounceClick {
                                    runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$driverPhone"))) }
                                } else Modifier
                            ) {
                                Icon(if (contactUnlocked && driverPhone.isNotBlank()) Icons.Default.Phone else Icons.Default.PhoneLocked, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(16.dp))
                            }
                        }
                        TripInfoRow(
                            Icons.Default.LocationOn,
                            appText("Место встречи", "Осрашыу урыны"),
                            when {
                                contactUnlocked && exactPickup.isNotBlank() -> exactPickup
                                contactUnlocked -> appText("Уточните точку в чате", "Нөктәне чатта асыҡлағыҙ")
                                bookingId != null -> appText("Откроется после подтверждения водителем", "Водитель раҫлағас асыла")
                                else -> appText("Откроется после подтверждения поездки", "Сәфәр раҫланғас асыла")
                            }
                        )
                        if (pickupLat != null && pickupLng != null) {
                            val la = pickupLat
                            val ln = pickupLng
                            val meet = appText("Место встречи", "Осрашыу урыны")
                            OutlinedButton(
                                onClick = {
                                    val uri = android.net.Uri.parse("geo:$la,$ln?q=$la,$ln(" + android.net.Uri.encode(meet) + ")")
                                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                                },
                                modifier = Modifier.fillMaxWidth().height(46.dp),
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, CanonGreen2)
                            ) {
                                Icon(Icons.Default.Map, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(appText("Открыть точку на карте", "Нөктәне картала асырға"), color = CanonGreen2)
                            }
                        }
                        if (routeFromPoint != null && routeToPoint != null) {
                            BookingRouteMapPreview(
                                modifier = Modifier.height(170.dp),
                                from = displayRide.from,
                                to = displayRide.to,
                                fromPoint = routeFromPoint,
                                toPoint = routeToPoint,
                                contactUnlocked = contactUnlocked
                            )
                        } else {
                            RouteMapUnavailableCard(Modifier.height(170.dp))
                        }
                        routeAd?.let { ad ->
                            PartnerAdCard(
                                ad = ad,
                                stats = adStats[ad.id] ?: AdStats(),
                                compact = true,
                                label = appText("По маршруту", "Маршрут буйынса"),
                                onImpression = onAdImpression,
                                onClick = onAdClick
                            )
                        }
                        if (contactUnlocked && driverPhone.isNotBlank()) {
                            InfoCard(
                                title = appText("Телефон водителя открыт", "Водителдең телефоны асылды"),
                                text = driverPhone,
                                icon = Icons.Default.Phone
                            )
                        } else {
                            InfoCard(
                                title = if (bookingId != null)
                                    appText("Телефон откроется после подтверждения водителем", "Телефон водитель раҫлағас асыла")
                                else
                                    appText("Телефон откроется после подтверждения поездки", "Телефон сәфәр раҫланғандан һуң асыла"),
                                text = appText("Так мы защищаем номер и точную геолокацию до взаимного согласия.", "Шулай итеп номерҙы һәм теүәл геолокацияны ике яҡ ризалығына тиклем һаҡлайбыҙ."),
                                icon = Icons.Default.Lock
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(
                                onClick = onMessage,
                                modifier = Modifier.weight(1f).height(54.dp),
                                shape = RoundedCornerShape(18.dp),
                                border = BorderStroke(1.dp, CanonGreen2)
                            ) {
                                Icon(Icons.Default.ChatBubble, contentDescription = null, tint = CanonGreen2)
                                Spacer(Modifier.width(8.dp))
                                Text(appText("Написать", "Яҙырға"), color = CanonGreen2, fontWeight = FontWeight.Bold)
                            }
                            Button(
                                onClick = onConfirmRide,
                                enabled = bookingId == null || canOpenActiveTrip,
                                modifier = Modifier.weight(1.15f).height(54.dp),
                                shape = RoundedCornerShape(18.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = CanonGreen2,
                                    disabledContainerColor = CanonMint,
                                    disabledContentColor = CanonGreen2.copy(alpha = 0.68f)
                                )
                            ) {
                                Icon(if (bookingId != null && !canOpenActiveTrip) Icons.Default.Schedule else Icons.Default.Route, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    when {
                                        bookingId == null -> appText("Поехать", "Барырға")
                                        canOpenActiveTrip -> appText("Открыть", "Асырға")
                                        else -> appText("Ждём водителя", "Водителде көтәбеҙ")
                                    },
                                    fontWeight = FontWeight.Black,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
            item {
                InfoCard(
                    title = appText("Мы заботимся о вашей безопасности", "Беҙ һеҙҙең хәүефһеҙлек тураһында ҡайғыртабыҙ"),
                    text = appText("Все поездки защищены и отслеживаются службой поддержки Юлдаш.", "Бөтә сәфәрҙәр Юлдаш ярҙам хеҙмәте тарафынан күҙәтелә."),
                    icon = Icons.Default.Shield
                )
            }
        }
    }
}

@Composable
private fun BookingRouteMapPreview(
    modifier: Modifier = Modifier,
    from: String,
    to: String,
    fromPoint: Point,
    toPoint: Point,
    contactUnlocked: Boolean
) {
    val context = LocalContext.current
    val mapView = remember(fromPoint, toPoint) {
        runCatching { MapKitFactory.initialize(context) }
        MapView(context).also { view ->
            view.setOnTouchListener { v, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                }
                false
            }
        }
    }
    DisposableEffect(mapView) {
        MapKitFactory.getInstance().onStart()
        mapView.onStart()
        onDispose {
            mapView.onStop()
            MapKitFactory.getInstance().onStop()
        }
    }
    LaunchedEffect(mapView, fromPoint, toPoint) {
        val map = mapView.mapWindow.map
        fitBookingRouteCamera(map, fromPoint, toPoint)?.let { camera ->
            map.move(camera, Animation(Animation.Type.SMOOTH, 0.25f), null)
        }
        map.mapObjects.addPolyline(Polyline(listOf(fromPoint, toPoint))).apply {
            setStrokeColor(0xCC0B6B3A.toInt())
            strokeWidth = 5f
        }
        map.mapObjects.addPlacemark().apply { geometry = fromPoint }
        map.mapObjects.addPlacemark().apply { geometry = toPoint }
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .border(1.dp, CanonBorder, RoundedCornerShape(24.dp))
    ) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        BookingMapLabel(from, Modifier.align(Alignment.TopStart).padding(14.dp))
        BookingMapLabel(to, Modifier.align(Alignment.CenterEnd).padding(14.dp))
        if (!contactUnlocked) {
            Card(
                modifier = Modifier.align(Alignment.BottomCenter).padding(14.dp),
                colors = CardDefaults.cardColors(containerColor = CanonSurface),
                shape = RoundedCornerShape(18.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = CanonText)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        appText(
                            "Точная точка встречи откроется после подтверждения",
                            "Теүәл осрашыу урыны раҫланғандан һуң асыла"
                        ),
                        color = CanonText,
                        fontSize = 14.sp,
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }
}

@Composable
internal fun BookingMapLabel(text: String, modifier: Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = CanonSurface,
        shadowElevation = 3.dp
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            color = CanonText,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun fitBookingRouteCamera(map: com.yandex.mapkit.map.Map, from: Point, to: Point): CameraPosition? = runCatching {
    val bbox = com.yandex.mapkit.geometry.BoundingBox(
        Point(minOf(from.latitude, to.latitude), minOf(from.longitude, to.longitude)),
        Point(maxOf(from.latitude, to.latitude), maxOf(from.longitude, to.longitude))
    )
    val fit = map.cameraPosition(com.yandex.mapkit.geometry.Geometry.fromBoundingBox(bbox))
    CameraPosition(fit.target, (fit.zoom - 0.55f).coerceIn(3f, 16f), 0f, 0f)
}.getOrNull()

@Composable
internal fun RouteMapUnavailableCard(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = CanonCardShape,
        border = BorderStroke(1.dp, CanonBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(color = CanonMint, shape = CircleShape) {
                Icon(Icons.Default.Route, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(12.dp).size(24.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    appText("Карта маршрута загружается", "Маршрут картаһы йөкләнә"),
                    color = CanonText,
                    fontWeight = FontWeight.Black,
                    fontSize = 16.sp
                )
                Text(
                    appText(
                        "Покажем реальный маршрут, когда сервер вернёт координаты.",
                        "Сервер координаталарҙы биргәс, ысын маршрутты күрһәтербеҙ."
                    ),
                    color = CanonMuted,
                    fontSize = 13.sp,
                    lineHeight = 17.sp
                )
            }
        }
    }
}

@Composable
internal fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = CanonSurface), shape = CanonItemShape, elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(Modifier.padding(vertical = 8.dp), content = content)
    }
}

@Composable
internal fun SettingsNavRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null
) {
    val modifier = if (onClick != null) Modifier.bounceClick(onClick) else Modifier
    Row(modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(color = CanonMint, shape = RoundedCornerShape(14.dp)) {
            Icon(icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(11.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = CanonText, fontWeight = FontWeight.Black, fontSize = 16.sp)
            Text(subtitle, color = CanonMuted, fontSize = 13.sp, lineHeight = 17.sp)
        }
        if (onClick != null) {
            Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = CanonMuted)
        }
    }
}

@Composable
internal fun SettingSwitchRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(color = CanonMint, shape = RoundedCornerShape(14.dp)) {
            Icon(icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(11.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = CanonText, fontWeight = FontWeight.Black, fontSize = 16.sp)
            Text(subtitle, color = CanonMuted, fontSize = 13.sp, lineHeight = 17.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
internal fun CompactProfileBanner() {
    Card(colors = CardDefaults.cardColors(containerColor = Color.Transparent), shape = CanonItemShape, elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Row(
            modifier = Modifier
                .background(Brush.linearGradient(listOf(CanonGreenInk, CanonGreenInkDark)), CanonItemShape)  // фикс тёмной темы: белый текст на фиксированном ink-зелёном
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(62.dp).background(Color.White.copy(alpha = 0.18f), CircleShape), contentAlignment = Alignment.Center) {
                Text((ApiClient.cachedName() ?: "Я").take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Black, fontSize = 24.sp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(ApiClient.cachedName() ?: "Я", color = Color.White, fontWeight = FontWeight.Black, fontSize = 20.sp)
                Text(appText("Пассажир · Баймаҡ", "Пассажир · Баймаҡ"), color = Color.White.copy(alpha = 0.78f), fontSize = 13.sp)
                Text(appText("Телефон скрыт до подтверждения", "Телефон раҫланғанға тиклем йәшерен"), color = Color.White.copy(alpha = 0.78f), fontSize = 13.sp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ActiveTripScreen(
    ride: Ride?,
    contacts: List<TrustedContact>,
    bookingId: Int?,
    onBack: () -> Unit,
    onTripEnd: () -> Unit,
    onSos: () -> Unit
) {
    val context = LocalContext.current
    var messages by remember(bookingId) { mutableStateOf<List<MessageDto>>(emptyList()) }
    val voiceScope = rememberCoroutineScope()
    val statusErrMsg = appText("Не удалось сохранить статус. Проверь сеть.", "Хәлде һаҡлап булманы. Селтәрҙе тикшерегеҙ.")
    val shareErrMsg = appText("Не удалось отправить. Проверь сеть.", "Ебәреп булманы. Селтәрҙе тикшерегеҙ.")
    val driverNotifiedMsg = appText("Пассажир уведомлён", "Пассажир хәбәрҙар ителде")
    val chatSendFailMsg = appText("Не отправилось. Повтори.", "Ебәрелмәне. Ҡабатла.")
    val chatActionFailMsg = appText("Не получилось. Повтори.", "Булманы. Ҡабатла.")
    // Роль в этой брони: водитель видит «Я выехал/Подъезжаю» (push пассажиру), пассажир — «сел/доехал/завершить».
    var role by remember { mutableStateOf("") }
    var driverPhase by remember(bookingId) { mutableStateOf("") }   // ""/departed/arriving — для live-баннера пассажиру
    var bookingStatus by remember(bookingId) { mutableStateOf("") }
    // Опрос состояния поездки раз в ~12с: роль + подфаза водителя. Так пассажир видит «водитель выехал/
    // подъезжает» LIVE (раньше это приходило только пушем — его легко пропустить, а UI не обновлялся).
    // На паузе в фоне (repeatOnLifecycle RESUMED) — не дёргаем сервер и батарею, когда приложение свёрнуто.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(bookingId, lifecycleOwner) {
        val id = bookingId ?: return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                ApiClient.getTripState(id).onSuccess { st -> role = st.role; driverPhase = st.driverPhase; bookingStatus = st.status }
                kotlinx.coroutines.delay(12_000)
            }
        }
    }
    var draft by remember { mutableStateOf("") }
    var editingId by remember { mutableStateOf<Int?>(null) }   // id редактируемого сообщения (null — обычная отправка)
    var status by remember { mutableStateOf<String?>(null) }
    var showShare by remember { mutableStateOf(false) }
    val shareSheet = rememberModalBottomSheetState()
    val tripSharedPrefix = appText("Поездка отправлена", "Сәфәр ебәрелде")

    val myId = remember { ApiClient.myUserId() ?: -1 }
    var wsConnected by remember { mutableStateOf(false) }
    // Оптимистичные (ещё не подтверждённые сервером) сообщения получают уникальный
    // отрицательный id (-2, -3, …). failedIds — те, что не доставились (показываем «Повторить»).
    var failedIds by remember(bookingId) { mutableStateOf(setOf<Int>()) }
    var tempSeq by remember(bookingId) { mutableStateOf(-2) }
    var boardingCode by remember(bookingId) { mutableStateOf("") }
    val sendFailMsg = appText("Сообщение не отправлено", "Хәбәр ебәрелмәне")
    // Состояние первой загрузки истории чата: спиннер, ошибка (с «Повторить»), пусто.
    var historyLoading by remember(bookingId) { mutableStateOf(bookingId != null) }
    var historyError by remember(bookingId) { mutableStateOf(false) }
    var historyTick by remember(bookingId) { mutableStateOf(0) }   // bump → перезагрузить историю (кнопка «Повторить»)

    // История — по REST (один раз, + повтор по кнопке). + код посадки брони.
    LaunchedEffect(bookingId, historyTick) {
        val id = bookingId ?: run { historyLoading = false; return@LaunchedEffect }
        historyLoading = true
        historyError = false
        ApiClient.getMessages(id)
            .onSuccess { messages = it }
            .onFailure { historyError = true }
        historyLoading = false
        ApiClient.getBoardingCode(id).onSuccess { boardingCode = it }
    }

    // Realtime — по WebSocket: входящие добавляем живьём; эхо своего сообщения заменяет оптимистичное.
    val chatSocket = remember(bookingId) {
        bookingId?.let { id ->
            ChatSocket(
                bookingId = id,
                onMessage = { inc ->
                    // WS-колбэк приходит с фонового потока OkHttp → правку Compose-state делаем на main
                    // (read-modify-write `messages` иначе может потерять обновление при гонке потоков).
                    voiceScope.launch {
                        // оптимистичное = отрицательный id, не помеченное как «не доставлено», моё, тот же текст
                        val optIdx = messages.indexOfFirst { it.id < 0 && it.id !in failedIds && it.senderId == myId && it.text == inc.text }
                        messages = when {
                            optIdx >= 0 -> messages.toMutableList().also { it[optIdx] = MessageDto(inc.id, inc.text, inc.senderId) }
                            inc.id > 0 && messages.any { it.id == inc.id } -> messages   // дубль по id — пропустить
                            else -> messages + MessageDto(inc.id, inc.text, inc.senderId)
                        }
                    }
                },
                onConnected = { wsConnected = it },
            )
        }
    }
    DisposableEffect(bookingId) {
        chatSocket?.connect()
        onDispose { chatSocket?.close() }
    }
    // После авто-реконнекта WS (был обрыв → связь вернулась) дотягиваем пропущенные сообщения по REST:
    // живой приём мог простоять, пока сокет был мёртв. Первый коннект не трогаем — историю уже грузит эффект выше.
    var wasEverConnected by remember(bookingId) { mutableStateOf(false) }
    LaunchedEffect(wsConnected) {
        if (wsConnected) {
            if (wasEverConnected) {
                bookingId?.let { id -> ApiClient.getMessages(id).onSuccess { messages = it } }
            }
            wasEverConnected = true
        }
    }

    // Доставка одного сообщения. Сперва WS (если жив), иначе REST. Ошибку НЕ глотаем:
    // при сбое сети помечаем сообщение «не доставлено» (кнопка повтора), чтобы оно не пропало молча.
    fun deliver(tempId: Int, text: String) {
        val bid = bookingId ?: return
        val ws = chatSocket
        val sentViaWs = wsConnected && ws != null && ws.send(text)   // send()=false → сокет мёртв → уходим в REST
        if (sentViaWs) return   // эхо WS заменит оптимистичное сообщение настоящим
        voiceScope.launch {
            ApiClient.sendMessage(bid, text)
                .onSuccess { ApiClient.getMessages(bid).onSuccess { messages = it } }   // забираем авторитетную историю
                .onFailure {
                    failedIds = failedIds + tempId
                    Toast.makeText(context, sendFailMsg, Toast.LENGTH_SHORT).show()
                }
        }
    }

    fun sendText(text: String) {
        val t = text.trim()
        if (t.isEmpty() || bookingId == null) return
        val tempId = tempSeq
        tempSeq -= 1
        messages = messages + MessageDto(tempId, t, myId)   // показываем сразу (оптимистично)
        deliver(tempId, t)
    }

    fun retry(tempId: Int, text: String) {
        failedIds = failedIds - tempId
        deliver(tempId, text)
    }
    val visibleMessages = messages.filter { it.deleted || it.voiceUrl != null || it.text.isNotBlank() }

    Scaffold(
        containerColor = CanonBg,
        topBar = { ScreenTopBar(appText("Моя поездка", "Минең сәфәр"), onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp).imePadding(),  // поднимаем контент над клавиатурой (композер чата не перекрывается)
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 28.dp)
        ) {
            item {
                TripRouteHeaderCard(
                    from = ride?.from,
                    to = ride?.to,
                    driver = ride?.driver,
                    time = ride?.time,
                    modifier = Modifier.appearIn(0),
                )
            }
            // Live-баннер пассажиру: водитель выехал/подъезжает (опрос статуса раз в ~12с, не только пуш).
            if (role == "passenger" && (driverPhase == "departed" || driverPhase == "arriving")) {
                item {
                    DriverApproachingBanner(
                        arriving = driverPhase == "arriving",
                        modifier = Modifier.appearIn(1),
                    )
                }
            }
            if (boardingCode.isNotBlank() && bookingStatusAllowsBoarding(bookingStatus)) {
                item {
                    BoardingCodeCard(code = boardingCode, modifier = Modifier.appearIn(1))
                }
            }
            val canChangeTripStatus = bookingId == null || (role.isNotBlank() && bookingStatusAllowsBoarding(bookingStatus))
            if (canChangeTripStatus) {
                item { Text(if (role == "driver") appText("Сообщить пассажиру", "Пассажирға хәбәр итеү") else appText("Статус поездки", "Сәфәр хәле"), fontWeight = FontWeight.Bold, modifier = Modifier.appearIn(2)) }
                item {
                    TripStatusButtons(
                        role = role,
                        selectedStatus = status,
                        modifier = Modifier.appearIn(2),
                        onStatus = { st ->
                            val bid = bookingId
                            if (role == "driver") {
                                // Водитель: «выехал/подъезжаю» → push пассажиру; «Завершить» → закрывает бронь на сервере.
                                if (bid == null) { if (st == "done") onTripEnd() }   // демо/нет брони → просто закрываем экран
                                else voiceScope.launch {
                                    ApiClient.driverStatus(bid, st)
                                        .onSuccess {
                                            if (st == "done") onTripEnd()   // уходим с экрана только при реальном закрытии брони
                                            else {
                                                Toast.makeText(context, driverNotifiedMsg, Toast.LENGTH_SHORT).show()
                                                ApiClient.getTripState(bid).onSuccess { s -> role = s.role; driverPhase = s.driverPhase; bookingStatus = s.status }   // сразу синхроним UI, не ждём 12с поллинга
                                            }
                                        }
                                        .onFailure { Toast.makeText(context, statusErrMsg, Toast.LENGTH_SHORT).show() }
                                }
                            } else {
                                status = st
                                if (bid == null) { if (st == "done") onTripEnd() }   // демо/нет брони → просто закрываем
                                else voiceScope.launch {
                                    ApiClient.setTripStatus(bid, st)
                                        // «Завершить» уходит с экрана только при реальном закрытии брони на сервере.
                                        .onSuccess {
                                            if (st == "done") onTripEnd()
                                            else ApiClient.getTripState(bid).onSuccess { s -> role = s.role; driverPhase = s.driverPhase; bookingStatus = s.status }   // сразу синхроним статус/код посадки
                                        }
                                        .onFailure { Toast.makeText(context, statusErrMsg, Toast.LENGTH_SHORT).show() }
                                }
                            }
                        },
                    )
                }
            }
            if (bookingStatus == "done") item {
                var myStars by remember { mutableStateOf(0) }
                var rating by remember { mutableStateOf(false) }   // запрос в полёте — блок повторных тапов, откат при сбое
                val thanksMsg = appText("Спасибо за оценку", "Баһа өсөн рәхмәт")
                val rateFailMsg = appText("Не получилось оценить", "Баһалап булманы")
                Card(modifier = Modifier.appearIn(2), colors = CardDefaults.cardColors(containerColor = CanonSurface), shape = CanonItemShape, elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(appText("Оцените водителя", "Водителде баһалағыҙ"), fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            (1..5).forEach { n ->
                                Icon(
                                    Icons.Default.Star,
                                    contentDescription = "$n",
                                    tint = if (n <= myStars) CanonStar else CanonBorder,
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clickable(enabled = !rating) {
                                            val prev = myStars
                                            myStars = n
                                            val id = bookingId
                                            if (id != null) {
                                                rating = true
                                                voiceScope.launch {
                                                    ApiClient.rateBooking(id, n)
                                                        .onSuccess { rating = false; Toast.makeText(context, thanksMsg, Toast.LENGTH_SHORT).show() }
                                                        .onFailure { rating = false; myStars = prev; Toast.makeText(context, rateFailMsg, Toast.LENGTH_SHORT).show() }   // откат: не показываем «оценено», если не сохранилось
                                                }
                                            }
                                        }
                                )
                            }
                        }
                    }
                }
            }
            if (bookingId == null || bookingStatusAllowsBoarding(bookingStatus)) item {
                ShareTripRow(onClick = { showShare = true }, modifier = Modifier.appearIn(3))
            }
            if (bookingId == null || bookingStatus == "confirmed") item {
                var showCancel by remember { mutableStateOf(false) }
                val cancelOkMsg = appText("Поездка отменена", "Сәфәр кире алынды")
                val cancelFailMsg = appText("Не удалось отменить", "Кире алып булманы")
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = { showCancel = true },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, CanonRed)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null, tint = CanonRed)
                        Spacer(Modifier.width(8.dp))
                        Text(appText("Отменить поездку", "Сәфәрҙе кире алыу"), color = CanonRed, fontWeight = FontWeight.Bold)
                    }
                    Text(appText("Отмена бесплатна до начала поездки — место вернётся в поездку.", "Сәфәр башланғанға тиклем кире алыу бушлай — урын кире ҡайта."), color = CanonMuted, fontSize = 12.sp, lineHeight = 16.sp)
                }
                if (showCancel) {
                    AlertDialog(
                        onDismissRequest = { showCancel = false },
                        title = { Text(appText("Отменить поездку?", "Сәфәрҙе кире аларғамы?")) },
                        text = { Text(appText("Бронь будет отменена, место освободится для других.", "Брон кире алына, урын башҡаларға бушай.")) },
                        confirmButton = {
                            TextButton(onClick = {
                                showCancel = false
                                bookingId?.let { id ->
                                    voiceScope.launch {
                                        ApiClient.cancelBooking(id)
                                            .onSuccess { Toast.makeText(context, cancelOkMsg, Toast.LENGTH_SHORT).show(); onTripEnd() }
                                            .onFailure { Toast.makeText(context, cancelFailMsg, Toast.LENGTH_SHORT).show() }
                                    }
                                } ?: onTripEnd()
                            }) { Text(appText("Да, отменить", "Эйе, кире алырға"), color = CanonRed, fontWeight = FontWeight.Bold) }
                        },
                        dismissButton = { TextButton(onClick = { showCancel = false }) { Text(appText("Назад", "Кире")) } }
                    )
                }
            }
            item { Text(appText("Чат по поездке", "Сәфәр буйынса чат"), fontWeight = FontWeight.Bold, modifier = Modifier.appearIn(4)) }
            // WS лежит → сообщения уходят по REST. Спокойно сообщаем, что связь восстанавливается (не ошибка).
            item {
                AnimatedVisibility(visible = bookingId != null && !wsConnected) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp)
                            .background(CanonMint, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = CanonGreen2)
                        Spacer(Modifier.width(10.dp))
                        Text(appText("Соединение восстанавливается…", "Бәйләнеш тергеҙелә…"), color = CanonGreen2, fontSize = 13.sp)
                    }
                }
            }
            // Состояния первой загрузки истории: спиннер / ошибка с «Повторить» / пусто.
            // Оптимистично отправленное сообщение уже наполняет messages → состояния гаснут.
            if (visibleMessages.isEmpty()) {
                when {
                    historyLoading -> item {
                        Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp, color = CanonGreen2)
                        }
                    }
                    historyError -> item {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                appText("Не удалось загрузить чат", "Чатты йөкләп булманы"),
                                color = CanonMuted, fontSize = 14.sp, textAlign = TextAlign.Center
                            )
                            OutlinedButton(
                                onClick = { historyTick++ },
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, CanonGreen2)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(appText("Повторить", "Ҡабатларға"), color = CanonGreen2, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    bookingId != null -> item {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
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
                val saved = m.id > 0   // оптимистичные (id<0) ещё не на сервере — без меню
                MessageBubble(
                    text = m.text,
                    voiceUrl = m.voiceUrl,
                    mine = m.senderId == myId,
                    failed = m.id in failedIds,
                    deleted = m.deleted,
                    edited = m.edited,
                    canEdit = saved && m.senderId == myId && m.voiceUrl == null && !m.deleted,
                    canDeleteAll = saved && m.senderId == myId && !m.deleted,
                    canDeleteMine = saved && !m.deleted,
                    onRetry = { retry(m.id, m.text) },
                    onEdit = { editingId = m.id; draft = m.text },
                    onDelete = { scope ->
                        if (bookingId != null) voiceScope.launch {
                            ApiClient.deleteMessage(bookingId, m.id, scope)
                                .onSuccess { ApiClient.getMessages(bookingId).onSuccess { messages = it } }
                                .onFailure { Toast.makeText(context, chatActionFailMsg, Toast.LENGTH_SHORT).show() }
                        }
                    },
                )
            }
            item {
                val voiceSoon = appText("Голос записан", "Тауыш яҙылды")
                if (editingId != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                            .background(CanonWarnBg, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, tint = CanonWarn, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(appText("Редактирование сообщения", "Хәбәрҙе үҙгәртеү"), color = CanonWarn, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text(
                            appText("Отмена", "Кире алыу"), color = CanonGreen2, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.bounceClick { editingId = null; draft = "" }
                        )
                    }
                }
                ChatComposer(
                    draft = draft,
                    onDraftChange = { draft = it },
                    onSend = {
                        val t = draft.trim()
                        if (t.isNotEmpty() && bookingId != null) {
                            val eid = editingId
                            if (eid != null) {
                                voiceScope.launch {
                                    ApiClient.editMessage(bookingId, eid, t)
                                        .onSuccess { ApiClient.getMessages(bookingId).onSuccess { messages = it } }
                                        .onFailure { Toast.makeText(context, chatActionFailMsg, Toast.LENGTH_SHORT).show() }
                                }
                                editingId = null
                            } else {
                                sendText(t)   // единый надёжный путь: WS→REST, ошибка не теряется
                            }
                            draft = ""
                        }
                    },
                    onVoiceRecorded = { path, _ ->
                        Toast.makeText(context, voiceSoon, Toast.LENGTH_SHORT).show()
                        if (bookingId != null) voiceScope.launch {
                            val bytes = runCatching { File(path).readBytes() }.getOrNull()
                            if (bytes != null) ApiClient.uploadVoice(bytes)
                                .onSuccess { url ->
                                    ApiClient.sendVoiceMessage(bookingId, url)   // результат больше НЕ выброшен: сбой = «не отправилось», не молчим
                                        .onSuccess { ApiClient.getMessages(bookingId).onSuccess { messages = it } }
                                        .onFailure { Toast.makeText(context, chatSendFailMsg, Toast.LENGTH_SHORT).show() }
                                }
                                .onFailure { Toast.makeText(context, chatSendFailMsg, Toast.LENGTH_SHORT).show() }
                        }
                    },
                    onPhotoPicked = { bytes ->
                        if (bookingId != null) voiceScope.launch {
                            ApiClient.uploadChatPhoto(bytes)
                                .onSuccess { url ->
                                    ApiClient.sendPhotoMessage(bookingId, url)   // результат больше НЕ выброшен
                                        .onSuccess { ApiClient.getMessages(bookingId).onSuccess { messages = it } }
                                        .onFailure { Toast.makeText(context, chatSendFailMsg, Toast.LENGTH_SHORT).show() }
                                }
                                .onFailure { Toast.makeText(context, chatSendFailMsg, Toast.LENGTH_SHORT).show() }
                        }
                    }
                )
            }
            item {
                Button(
                    onClick = onSos,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CanonRed)
                ) {
                    // Icons.Default.Sos сам рисует буквы «SOS» → с Text("SOS") выходило «SOS SOS». Щит (как на карте).
                    Icon(Icons.Default.Shield, contentDescription = appText("Экстренный вызов", "Ашығыс саҡырыу"))
                    Spacer(Modifier.width(8.dp))
                    Text("SOS", fontWeight = FontWeight.Black)
                }
            }
        }
    }

    if (showShare) {
        ModalBottomSheet(onDismissRequest = { showShare = false }, sheetState = shareSheet, containerColor = CanonSurface) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                Text(appText("Кому отправить поездку", "Сәфәрҙе кемгә ебәрергә"), fontSize = 18.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(vertical = 8.dp))
                if (contacts.isEmpty()) {
                    Text(appText("Сначала добавьте доверенный контакт в профиле", "Башта профилдә ышаныслы контакт өҫтәгеҙ"), color = CanonMuted)
                }
                contacts.forEach { c ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            val bid = bookingId
                            showShare = false
                            if (bid != null) voiceScope.launch {
                                ApiClient.shareTrip(bid, c.id)
                                    .onSuccess { Toast.makeText(context, "$tripSharedPrefix: ${c.name}", Toast.LENGTH_SHORT).show() }
                                    .onFailure { Toast.makeText(context, shareErrMsg, Toast.LENGTH_SHORT).show() }
                            }
                        }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(44.dp).background(CanonMint, CircleShape), contentAlignment = Alignment.Center) {
                            Text(c.name.take(1), fontWeight = FontWeight.Black)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.name, fontWeight = FontWeight.Bold)
                            Text(c.relation, color = CanonMuted, fontSize = 13.sp)
                        }
                        Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = CanonMuted)
                    }
                }
            }
        }
    }
}

// ── Чистые карточки поездки/статуса (без карты/сокета/чата) ─────────────────────────────
// Вынесены из ActiveTripScreen: берут примитивы/лямбды, рисуют только Text/Icon/Row/Column/
// Surface/Card. Двуязычие считается внутри через appText (по LocalAppLanguage). Анимацию появления
// (`appearIn`) экран навешивает снаружи через modifier — тела остаются без анимаций/эффектов, что
// делает их покрываемыми на JVM (Robolectric). Поведение 1:1 с прежним инлайном.

/** Шапка активной поездки: «откуда → куда», водитель, время. Пустые значения → «—». */
@Composable
internal fun TripRouteHeaderCard(
    from: String?,
    to: String?,
    driver: String?,
    time: String?,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = CanonSurface), shape = CanonCardShape, elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${from ?: "—"}  →  ${to ?: "—"}", fontSize = 22.sp, fontWeight = FontWeight.Black)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.DirectionsCar, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(driver ?: appText("Водитель", "Водитель"), color = CanonMuted)
                time?.let { Spacer(Modifier.width(10.dp)); Text(it, color = CanonMuted) }
            }
        }
    }
}

/** Live-баннер пассажиру: водитель выехал / подъезжает. `arriving` → акцентная (зелёная) плашка. */
@Composable
internal fun DriverApproachingBanner(
    arriving: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = if (arriving) CanonGreen2 else CanonMint,
        shape = CanonCardShape
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.DirectionsCar, contentDescription = null, tint = if (arriving) Color.White else CanonGreen2, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                if (arriving) appText("Водитель подъезжает", "Водитель яҡынлаша") else appText("Водитель выехал к вам", "Водитель сыҡты"),
                color = if (arriving) Color.White else CanonText, fontWeight = FontWeight.Black, fontSize = 16.sp
            )
        }
    }
}

/** Плашка кода посадки: пассажир называет код водителю для сверки машины. */
@Composable
internal fun BoardingCodeCard(
    code: String,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier, color = CanonMint, shape = CanonCardShape) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Pin, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(appText("Код посадки", "Ултырыу коды"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 15.sp)
                Text(appText("Назовите водителю — он сверит. Это та самая машина.", "Водителгә әйтегеҙ — ул тикшерер. Тап шул машина."), color = CanonMuted, fontSize = 13.sp, lineHeight = 17.sp)
            }
            Spacer(Modifier.width(10.dp))
            Text(code, color = CanonGreen2, fontWeight = FontWeight.Black, fontSize = 30.sp, letterSpacing = 4.sp)
        }
    }
}

/**
 * Кнопки статуса поездки. Водитель: «Я выехал / Подъезжаю / Завершить»; пассажир: «Я сел /
 * Доехал / Завершить». Клик отдаёт код статуса в [onStatus] — вся сеть/навигация снаружи.
 * У пассажира выбранный статус подсвечен ([selectedStatus]); у водителя подсветки нет.
 */
@Composable
internal fun TripStatusButtons(
    role: String,
    selectedStatus: String?,
    onStatus: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val statusButtons = if (role == "driver")
            // + «Завершить»: водитель тоже закрывает поездку. Раньше закрыть бронь мог ТОЛЬКО
            // пассажир → если он забывал, бронь висела активной, а места поездки не освобождались.
            listOf("departed" to appText("Я выехал", "Сыҡтым"), "arriving" to appText("Подъезжаю", "Яҡынлашам"), "done" to appText("Завершить", "Тамам"))
        else
            listOf("sat" to appText("Я сел", "Ултырҙым"), "arrived" to appText("Доехал", "Барып еттем"), "done" to appText("Завершить", "Тамам"))
        statusButtons.forEach { (st, label) ->
            FilledTonalButton(
                onClick = { onStatus(st) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = if (role != "driver" && selectedStatus == st) CanonMint else CanonSurface,
                    contentColor = CanonText
                )
            ) { Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** Строка «Поделиться поездкой с близким» — открывает шит выбора контакта. */
@Composable
internal fun ShareTripRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(colors = CardDefaults.cardColors(containerColor = CanonSurface), shape = CanonItemShape, elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Row(
            modifier.fillMaxWidth().clickable { onClick() }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(color = CanonMint, shape = CircleShape) {
                Icon(Icons.Default.Person, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(10.dp).size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(appText("Поделиться поездкой с близким", "Сәфәрҙе яҡының менән уртаҡлашыу"), fontWeight = FontWeight.Bold)
                Text(appText("Близкий будет видеть статус поездки", "Яҡының сәфәр хәлен күреп торор"), color = CanonMuted, fontSize = 13.sp)
            }
            Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = CanonMuted)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MessageBubble(
    text: String,
    voiceUrl: String?,
    mine: Boolean,
    failed: Boolean = false,
    deleted: Boolean = false,
    edited: Boolean = false,
    canEdit: Boolean = false,
    canDeleteAll: Boolean = false,
    canDeleteMine: Boolean = false,
    onRetry: () -> Unit = {},
    onEdit: () -> Unit = {},
    onDelete: (String) -> Unit = {},
) {
    var playing by remember { mutableStateOf(false) }
    val player = remember { mutableStateOf<MediaPlayer?>(null) }
    DisposableEffect(voiceUrl) { onDispose { runCatching { player.value?.release() }; player.value = null } }
    var menu by remember { mutableStateOf(false) }
    val showMenu = !deleted && (canEdit || canDeleteAll || canDeleteMine)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Box {
        Surface(
            color = if (deleted) CanonSurface else if (mine) CanonGreen2 else CanonSurface,
            shape = RoundedCornerShape(18.dp),
            shadowElevation = 1.dp,
            border = if (deleted) BorderStroke(1.dp, CanonBorder) else null,
            modifier = if (showMenu) Modifier.combinedClickable(onClick = {}, onLongClick = { menu = true }) else Modifier
        ) {
            if (deleted) {
                Text(
                    appText("Сообщение удалено", "Хәбәр юйылды"),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    color = CanonMuted, fontSize = 14.sp, fontStyle = FontStyle.Italic
                )
            } else if (voiceUrl != null) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = {
                            if (playing) {
                                runCatching { player.value?.stop(); player.value?.release() }; player.value = null; playing = false
                            } else runCatching {
                                player.value = MediaPlayer().apply {
                                    setDataSource(voiceUrl)
                                    setOnPreparedListener { it.start() }
                                    setOnCompletionListener { playing = false; runCatching { release() }; player.value = null }
                                    prepareAsync()
                                }
                                playing = true
                            }
                        },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(if (playing) Icons.Default.Close else Icons.Default.PlayArrow, contentDescription = appText("Воспроизвести", "Уйнатыу"), tint = if (mine) Color.White else CanonGreen2)
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(appText("Голосовое", "Тауыш"), modifier = Modifier.padding(end = 8.dp), color = if (mine) Color.White else CanonText, fontSize = 14.sp)
                }
            } else if (text.startsWith(ApiClient.IMG_PREFIX)) {
                AsyncImage(
                    model = text.removePrefix(ApiClient.IMG_PREFIX),
                    contentDescription = appText("Фото", "Фото"),
                    modifier = Modifier
                        .padding(4.dp)
                        .sizeIn(maxWidth = 240.dp, maxHeight = 320.dp)
                        .clip(RoundedCornerShape(14.dp)),
                    contentScale = ContentScale.Fit
                )
            } else {
                Text(
                    text,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    color = if (mine) Color.White else CanonText,
                    fontSize = 15.sp
                )
            }
        }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (canEdit) DropdownMenuItem(
                    text = { Text(appText("Редактировать", "Үҙгәртеү")) },
                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, tint = CanonGreen2) },
                    onClick = { menu = false; onEdit() }
                )
                if (canDeleteMine) DropdownMenuItem(
                    text = { Text(appText("Удалить у себя", "Үҙемдә юйыу")) },
                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = CanonMuted) },
                    onClick = { menu = false; onDelete("me") }
                )
                if (canDeleteAll) DropdownMenuItem(
                    text = { Text(appText("Удалить у всех", "Барыһында юйыу"), color = CanonRed) },
                    leadingIcon = { Icon(Icons.Default.DeleteForever, contentDescription = null, tint = CanonRed) },
                    onClick = { menu = false; onDelete("all") }
                )
            }
        }
    }
        if (edited && !deleted) {
            Text(
                appText("изменено", "үҙгәртелде"),
                color = CanonMuted, fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp, end = 4.dp)
            )
        }
        if (failed) {
            Text(
                appText("Не доставлено · Повторить", "Ебәрелмәне · Ҡабатларға"),
                color = CanonRed,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 2.dp, end = 4.dp).bounceClick { onRetry() }
            )
        }
    }
}


