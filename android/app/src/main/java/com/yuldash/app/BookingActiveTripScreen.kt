package com.yuldash.app

// Экраны брони (BookingScreen) и активной поездки (ActiveTripScreen: чат/статус/SOS).
// Вынесено из MainActivity (Фаза 2). Импорты целиком — лишние = варнинги.

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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.EscalatorWarning
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
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Handshake
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
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.VolunteerActivism
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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
import com.yuldash.app.data.TripPass
import com.yuldash.app.data.TripPassStore
import com.yuldash.app.data.Outbox
import com.yuldash.app.ui.theme.YuldashTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * F11 — сохранить офлайн-паспорт брони из подтверждённых деталей. Дотягивает код посадки,
 * чтобы паспорт был полным ещё до входа в активную поездку. Телефон водителя (ПДн) уходит
 * в secure-хранилище TripPassStore и НЕ логируется.
 */
private suspend fun saveTripPass(context: android.content.Context, d: com.yuldash.app.data.BookingDetailsDto) {
    val code = ApiClient.getBoardingCode(d.bookingId).getOrNull().orEmpty()
    TripPassStore.save(
        context,
        TripPass(
            bookingId = d.bookingId,
            fromCity = d.fromCity,
            toCity = d.toCity,
            departAt = d.departAt,
            driverName = d.driverName,
            driverCar = listOf(d.driverCarColor, d.driverCar).filter { it.isNotBlank() }.joinToString(" "),
            driverPlate = d.driverPlate,
            driverPhone = d.driverPhone,
            boardingCode = code,
            pickup = d.pickup,
            pickupLat = d.pickupLat,
            pickupLng = d.pickupLng,
            price = d.price,
            seats = d.seats,
            paymentNote = "",   // явной договорённости от бэка нет — оплату показываем из price (двуязычно на экране)
            savedAt = System.currentTimeMillis(),
        ),
    )
}

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
    onConfirmRide: (payMethod: String, payAmount: Int?, minor: Boolean, guardianName: String, guardianPhone: String) -> Unit
) {
    val routeAd = ads.forPlacement(AdPlacement.TripDetails).firstOrNull { it.matchesRoute(ride.from, ride.to) }
    val context = LocalContext.current
    var details by remember(bookingId) { mutableStateOf<com.yuldash.app.data.BookingDetailsDto?>(null) }
    // Договорённость об оплате (ЗАПИСЬ, не платёж): что выбрал пассажир до брони.
    // Способ по умолчанию — «договоримся»; сумма по умолчанию — из цены поездки.
    var payMethod by remember(bookingId) { mutableStateOf("negotiate") }
    var payAmountText by remember(bookingId) { mutableStateOf(if (ride.price > 0) ride.price.toString() else "") }
    // Едет подросток: отметка + взрослый на связи (сервер без него бронь не создаст).
    var minorPassenger by remember { mutableStateOf(false) }
    var guardianName by remember { mutableStateOf("") }
    var guardianPhone by remember { mutableStateOf("") }
    var detailsLoading by remember(bookingId) { mutableStateOf(bookingId != null) }
    var detailsError by remember(bookingId) { mutableStateOf(false) }
    var detailsReload by remember(bookingId) { mutableIntStateOf(0) }
    LaunchedEffect(bookingId, detailsReload) {
        val bid = bookingId ?: return@LaunchedEffect
        detailsLoading = true
        ApiClient.getBookingDetails(bid)
            .onSuccess { loaded ->
                details = loaded; detailsError = false
                // F11: как только бронь подтверждена (телефон/встреча открыты) — сохраняем офлайн-паспорт.
                // Так экран активной поездки поднимет данные без сети на трассе без связи.
                if (loaded.contactUnlocked) saveTripPass(context, loaded)
            }
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 116.dp)
        ) {
            item {
                Spacer(Modifier.height(8.dp))
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBackIosNew, contentDescription = appText("Назад", "Кире"), tint = CanonText)
                    }
                    Text(appText("Детали поездки", "Сәфәр тураһында"), modifier = Modifier.weight(1f), color = CanonGreen, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold)
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
                Card(colors = CardDefaults.cardColors(containerColor = CanonSurface), shape = CanonItemShape, elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.card)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        RouteMiniIcon()
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${displayRide.from}  →  ${displayRide.to}", color = CanonText, fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                DetailMeta(Icons.Default.CalendarMonth, displayRide.timeText(), modifier = Modifier.weight(1.45f))
                                DetailMeta(Icons.Default.Person, seatsText(displayRide.seats), modifier = Modifier.weight(0.8f))
                            }
                        }
                        Surface(color = CanonMint, shape = RoundedCornerShape(14.dp)) {
                            Text("${displayRide.price} ₽", modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp), color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                }
            }
            if (detailsLoading) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = CanonSurface), shape = CanonItemShape) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
                Card(colors = CardDefaults.cardColors(containerColor = CanonSurface), shape = CanonCardShape, elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.card)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        // Двуязычный дефолт имени водителя (toUiRide больше не кладёт русский литерал). BA-draft: «Йөрөтөүсе».
                        val driverFallback = appText("Водитель", "Йөрөтөүсе")
                        val driverName = displayRide.driver.ifBlank { driverFallback }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(color = CanonMint, shape = CircleShape) {
                                Text(driverName.firstOrNull()?.uppercase() ?: "?", modifier = Modifier.padding(24.dp), color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 24.sp)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(driverName, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                                    if (displayRide.verified) {
                                        Spacer(Modifier.width(4.dp))
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
                                Icon(
                                    if (contactUnlocked && driverPhone.isNotBlank()) Icons.Default.Phone else Icons.Default.PhoneLocked,
                                    contentDescription = if (contactUnlocked && driverPhone.isNotBlank())
                                        appText("Позвонить", "Шылтыратыу")          // BA-draft
                                    else appText("Телефон пока скрыт", "Телефон әлегә йәшерелгән"),  // BA-draft
                                    tint = CanonGreen2,
                                    modifier = Modifier.padding(16.dp)
                                )
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
                        PayAgreementBlock(
                            editable = bookingId == null,
                            method = if (bookingId == null) payMethod else (details?.payMethod ?: "negotiate"),
                            amountText = payAmountText,
                            summaryAmount = details?.payAmount,
                            onMethod = { payMethod = it },
                            onAmount = { payAmountText = it }
                        )
                        // Едет подросток: до брони — форма со взрослым, после — просто пометка.
                        // Водителю она приходит вместе с бронью, чтобы он решал заранее.
                        if (bookingId == null) {
                            if (!ride.noMinors) {
                                MinorPassengerBlock(
                                    checked = minorPassenger,
                                    guardianName = guardianName,
                                    guardianPhone = guardianPhone,
                                    onChecked = { minorPassenger = it },
                                    onName = { guardianName = it },
                                    onPhone = { guardianPhone = it },
                                )
                            } else {
                                InfoCard(
                                    title = appText("Водитель берёт только 18+", "Водитель тик 18+ ала"),
                                    text = appText("Этот водитель не везёт пассажиров младше 18 без взрослого. Поищи другую поездку — их много.",
                                        "Был водитель 18-ҙән кесе юлсыларҙы оло кешеһеҙ йөрөтмәй. Башҡа сәфәр эҙлә — улар күп."),
                                    icon = Icons.Default.EscalatorWarning,
                                )
                            }
                        } else if (details?.minorPassenger == true) {
                            InfoCard(
                                title = appText("Едет пассажир младше 18", "18-ҙән кесе юлсы бара"),
                                text = listOf(details?.minorGuardianName.orEmpty(), details?.minorGuardianPhone.orEmpty())
                                    .filter { it.isNotBlank() }.joinToString(" · ")
                                    .ifBlank { appText("Взрослый на связи указан", "Оло кеше күрһәтелгән") },
                                icon = Icons.Default.EscalatorWarning,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(
                                onClick = onMessage,
                                modifier = Modifier.weight(1f).height(54.dp),
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, CanonGreen2)
                            ) {
                                Icon(Icons.Default.ChatBubble, contentDescription = null, tint = CanonGreen2)
                                Spacer(Modifier.width(8.dp))
                                Text(appText("Написать", "Яҙырға"), color = CanonGreen2, fontWeight = FontWeight.Bold)
                            }
                            Button(
                                onClick = { onConfirmRide(payMethod, payAmountText.trim().toIntOrNull(), minorPassenger, guardianName.trim(), guardianPhone.trim()) },
                                enabled = bookingId == null || canOpenActiveTrip,
                                modifier = Modifier.weight(1.15f).height(54.dp),
                                shape = RoundedCornerShape(14.dp),
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
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
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
                    title = appText("Мы бережём твою безопасность", "Беҙ һинең хәүефһеҙлегеңде һаҡлайбыҙ"),
                    text = appText("Все поездки защищены и отслеживаются службой поддержки Юлдаш.", "Бөтә сәфәрҙәр Юлдаш ярҙам хеҙмәте тарафынан күҙәтелә."),
                    icon = Icons.Default.Shield
                )
            }
        }
    }
}

/** Способы оплаты-договорённости: ключ на бэке + иконка + двуязычная подпись.
 * Это ЗАПИСЬ «как договорились платить», НЕ платёж и не движение денег. */
internal val payMethodKeys = listOf("cash", "sbp", "negotiate")

@Composable
internal fun payMethodLabel(method: String): String = when (method) {
    "cash" -> appText("Наличными", "Аҡса менән")
    "sbp" -> appText("Перевод по СБП", "СБП аша күсереү")
    else -> appText("Договоримся", "Килешербеҙ")
}

internal fun payMethodIcon(method: String) = when (method) {
    "cash" -> Icons.Default.Payments
    "sbp" -> Icons.Default.CreditCard
    else -> Icons.Default.VolunteerActivism
}

/**
 * Блок «Как договорились платить» в деталях брони.
 * editable=true (до брони) — пассажир выбирает способ (чипы) и сумму.
 * editable=false (бронь есть) — только показ договорённости обеим сторонам.
 * ВАЖНО: это запись договорённости, а НЕ оплата — деньги через приложение не идут.
 */
@Composable
internal fun PayAgreementBlock(
    editable: Boolean,
    method: String,
    amountText: String,
    summaryAmount: Int?,
    onMethod: (String) -> Unit,
    onAmount: (String) -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, CanonBorder, CanonItemShape)
            .background(CanonBg, CanonItemShape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Handshake, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(appText("Как договорились платить", "Түләү тураһында нисек килешкәнбеҙ"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
        Text(
            appText(
                "Это просто запись договорённости — деньги через приложение не проходят.",
                "Был — тик килешеү яҙмаһы, аҡса ҡулланма аша үтмәй."
            ),
            color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp
        )
        if (editable) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                payMethodKeys.forEach { key ->
                    // Способ оплаты — решение про деньги, тач-цель ≥ 48dp (§4.5), не мелкий фильтр.
                    NearbyFilterChip(
                        payMethodIcon(key), payMethodLabel(key), method == key,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { onMethod(key) }
                }
            }
            OutlinedTextField(
                value = amountText,
                onValueChange = { new -> onAmount(new.filter { it.isDigit() }.take(6)) },
                label = { Text(appText("Сумма, ₽ (необязательно)", "Сумма, ₽ (мотлаҡ түгел)")) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(color = CanonMint, shape = RoundedCornerShape(999.dp)) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(payMethodIcon(method), contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(payMethodLabel(method), color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
                if (summaryAmount != null && summaryAmount > 0) {
                    Text("$summaryAmount ₽", color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
internal fun BookingRouteMapPreview(   // internal: живой MapKit-рендер маршрута покрывается инструментальным тестом на ≤API-36 (JVM не может)
    modifier: Modifier = Modifier,
    from: String,
    to: String,
    fromPoint: Point,
    toPoint: Point,
    contactUnlocked: Boolean
) {
    val context = LocalContext.current
    val mapView = remember(fromPoint, toPoint) {
        ensureMapKit(context)   // русская локаль ставится тут же: голый initialize оставлял логотип «Yandex Maps» по-английски
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
            .clip(RoundedCornerShape(22.dp))
            .border(1.dp, CanonBorder, RoundedCornerShape(22.dp))
    ) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        BookingMapLabel(from, Modifier.align(Alignment.TopStart).padding(12.dp))
        BookingMapLabel(to, Modifier.align(Alignment.CenterEnd).padding(12.dp))
        if (!contactUnlocked) {
            Card(
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                colors = CardDefaults.cardColors(containerColor = CanonSurface),
                shape = RoundedCornerShape(14.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.card)
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
                        lineHeight = 20.sp
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
        shadowElevation = CanonDepth.raised
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
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
        elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.card)
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(color = CanonMint, shape = CircleShape) {
                Icon(painterResource(R.drawable.yu_route), contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(12.dp).size(24.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    appText("Карта маршрута загружается", "Маршрут картаһы йөкләнә"),
                    color = CanonText,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Text(
                    appText(
                        "Покажем реальный маршрут, когда сервер вернёт координаты.",
                        "Сервер координаталарҙы биргәс, ысын маршрутты күрһәтербеҙ."
                    ),
                    color = CanonMuted,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
            }
        }
    }
}

@Composable
internal fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = CanonSurface), shape = CanonItemShape, elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.card)) {
        Column(Modifier.padding(vertical = 8.dp), content = content)
    }
}

@Composable
internal fun SettingsNavRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
    badge: Int = 0,   // >0 → зелёный бейдж непрочитанного (напр. новые ответы поддержки)
) {
    val modifier = if (onClick != null) Modifier.bounceClick(onClick) else Modifier
    Row(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(color = CanonMint, shape = RoundedCornerShape(14.dp)) {
            Icon(icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(12.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(subtitle, color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp)
        }
        if (badge > 0) {
            Surface(color = CanonGreen2, shape = RoundedCornerShape(999.dp)) {
                Text(
                    if (badge > 99) "99+" else badge.toString(),
                    color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
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
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(color = CanonMint, shape = RoundedCornerShape(14.dp)) {
            Icon(icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(12.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(subtitle, color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
internal fun CompactProfileBanner() {
    Card(colors = CardDefaults.cardColors(containerColor = Color.Transparent), shape = CanonItemShape, elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.card)) {
        Row(
            modifier = Modifier
                .background(Brush.linearGradient(listOf(CanonGreenInk, CanonGreenInkDark)), CanonItemShape)  // фикс тёмной темы: белый текст на фиксированном ink-зелёном
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(62.dp).background(Color.White.copy(alpha = 0.18f), CircleShape), contentAlignment = Alignment.Center) {
                Text((ApiClient.cachedName() ?: appText("Я", "Мин")).take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 24.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(ApiClient.cachedName() ?: appText("Я", "Мин"), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                // Настоящая роль из кеша /me (города в профиле нет — не выдумываем «Баймаҡ»).
                Text(roleLabel(ApiClient.cachedRole() ?: ""), color = Color.White.copy(alpha = 0.78f), fontSize = 14.sp)
                Text(appText("Телефон скрыт до подтверждения", "Телефон раҫланғанға тиклем йәшерен"), color = Color.White.copy(alpha = 0.78f), fontSize = 14.sp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ActiveTripScreen(
    ride: Ride?,
    contacts: List<TrustedContact>,
    bookingId: Int?,
    onBack: () -> Unit,
    onTripEnd: () -> Unit,
    onSos: () -> Unit,
    onSupport: () -> Unit = {},
    onOpenReceipt: (Int) -> Unit = {}
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
    // Ключуем по bookingId (как driverPhase/bookingStatus ниже): иначе при открытии ДРУГОЙ брони до первого
    // опроса видны кнопки чужой роли (водительские «Я выехал» у пассажира).
    var role by remember(bookingId) { mutableStateOf("") }
    var driverPhase by remember(bookingId) { mutableStateOf("") }   // ""/departed/arriving — для live-баннера пассажиру
    // Сервер сверил «подъезжаю» с GPS водителя → пассажир видит «подтверждено по GPS» и знает,
    // что машина правда рядом, а не «уже почти» на словах (разбор конкурентов 2026-08-07).
    var arrivalVerified by remember(bookingId) { mutableStateOf(false) }
    // Попутчики вышли, остался один на один с водителем (см. AlonePassengerHint).
    var aloneWithDriver by remember(bookingId) { mutableStateOf(false) }
    var bookingStatus by remember(bookingId) { mutableStateOf("") }
    // F11: офлайн-паспорт брони. Читаем СРАЗУ из локального (secure) хранилища — данные видны без сети.
    var tripPass by remember(bookingId) { mutableStateOf(bookingId?.let { TripPassStore.load(context, it) }) }
    // offline = последний опрос состояния упал по СЕТИ (не по ответу сервера). Тогда показываем паспорт+плашку.
    var offline by remember(bookingId) { mutableStateOf(false) }
    // Опрос состояния поездки раз в ~12с: роль + подфаза водителя. Так пассажир видит «водитель выехал/
    // подъезжает» LIVE (раньше это приходило только пушем — его легко пропустить, а UI не обновлялся).
    // На паузе в фоне (repeatOnLifecycle RESUMED) — не дёргаем сервер и батарею, когда приложение свёрнуто.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(bookingId, lifecycleOwner) {
        val id = bookingId ?: return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                ApiClient.getTripState(id)
                    .onSuccess { st -> role = st.role; driverPhase = st.driverPhase; arrivalVerified = st.arrivalVerified; aloneWithDriver = st.aloneWithDriver; bookingStatus = st.status; offline = false }
                    // Сетевой сбой (не ApiException) → уходим в офлайн-режим: поднимаем сохранённый паспорт.
                    .onFailure { e -> if (e !is ApiException) offline = true }
                kotlinx.coroutines.delay(12_000)
            }
        }
    }
    // F12 «Зимний протокол»: мягкая проверка «доехал?». Показываем ОДИН раз за поездку
    // (rememberSaveable переживает поворот и kill процесса). departIso — старт поездки (raw ISO).
    // Момент проверки считаем по РАСЧЁТНОЙ ETA маршрута (расстояние/скорость + запас), а НЕ по
    // фиксированному часу — иначе на длинном межгороде спросили бы «доехал?» в середине пути и
    // могли зря потревожить близкого через шаринг. Нет координат маршрута → щедрый фолбэк.
    // Диалог и таймер — общие с такси и доставкой (WinterProtocol.kt): три копии уже однажды
    // разошлись, и попутка осталась единственным сценарием с зимним протоколом.
    val showArrivalCheck = rememberSaveable(bookingId) { mutableStateOf(false) }
    val arrivalAsked = rememberSaveable(bookingId) { mutableStateOf(false) }
    var departIso by remember(bookingId) { mutableStateOf("") }
    var armAfterMs by remember(bookingId) { mutableStateOf(ARRIVAL_CHECK_FALLBACK_MS) }
    // F12: пробудить проверку «доехал?» один раз, когда прошёл буфер после выезда, а поездка
    // ещё активна (не done/cancelled). Буфер — эвристика (ETA в этом экране нет): сервер сам
    // не пошлёт пуш до depart_at и не эскалирует раньше 30 мин + активного шаринга.
    WinterArrivalWatcher(
        key = bookingId,
        startMs = {
            val iso = departIso.ifBlank { tripPass?.departAt ?: "" }
            iso.takeIf { it.isNotBlank() }?.let(::parseIsoUtcMillis)
        },
        // Водителя не спрашиваем: протокол сторожит того, кого везут.
        active = { bookingStatus != "done" && bookingStatus != "cancelled" && role != "driver" },
        asked = arrivalAsked,
        show = showArrivalCheck,
        armAfterMs = armAfterMs,
        onArm = { bookingId?.let { ApiClient.winterCheck(it) } },   // сервер решит: too_early / check_sent
    )
    // Ключуем по bookingId: черновик/режим редактирования/выбранный статус не должны утекать в другую бронь.
    var draft by remember(bookingId) { mutableStateOf("") }
    var editingId by remember(bookingId) { mutableStateOf<Int?>(null) }   // id редактируемого сообщения (null — обычная отправка)
    var status by remember(bookingId) { mutableStateOf<String?>(null) }
    var showShare by remember { mutableStateOf(false) }
    val shareSheet = rememberModalBottomSheetState()
    val tripSharedPrefix = appText("Поездка отправлена", "Сәфәр ебәрелде")
    val shareRevokedMsg = appText("Ссылка отозвана", "Һылтанма кире алынды")

    val myId = remember { ApiClient.myUserId() ?: -1 }
    val frostyNight = remember { isFrostyWinterNight() }   // F12: морозная ночь — считаем один раз (LazyListScope не @Composable)
    var wsConnected by remember { mutableStateOf(false) }
    // Оптимистичные (ещё не подтверждённые сервером) сообщения получают уникальный
    // отрицательный id (-2, -3, …). failedIds — те, что не доставились (показываем «Повторить»).
    var failedIds by remember(bookingId) { mutableStateOf(setOf<Int>()) }
    // F11: сообщения, поставленные в очередь при отсутствии сети (уйдут авто-ретраем).
    var queuedIds by remember(bookingId) { mutableStateOf(setOf<Int>()) }
    var tempSeq by remember(bookingId) { mutableStateOf(-2) }
    var boardingCode by remember(bookingId) { mutableStateOf("") }
    // Договорённость об оплате (ЗАПИСЬ, не платёж) — показываем обеим сторонам в активной поездке.
    var payMethod by remember(bookingId) { mutableStateOf("negotiate") }
    var payAmount by remember(bookingId) { mutableStateOf<Int?>(null) }
    val sendFailMsg = appText("Сообщение не отправлено", "Хәбәр ебәрелмәне")
    val tooFastMsg = appText("Слишком быстро. Подожди минуту и продолжи.",
                             "Артыҡ тиҙ. Бер минут көт тә дауам ит.")
    val queuedMsg = appText("Нет сети — отправим позже", "Селтәр юҡ — һуңыраҡ ебәрербеҙ")
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
        ApiClient.getBoardingCode(id).onSuccess { code ->
            boardingCode = code
            // F11: дополним офлайн-паспорт кодом посадки (его пассажир называет водителю без сети).
            TripPassStore.updateBoardingCode(context, id, code)
            tripPass = TripPassStore.load(context, id)
        }
        ApiClient.getBookingDetails(id).onSuccess { d -> payMethod = d.payMethod; payAmount = d.payAmount; if (d.departAt.isNotBlank()) departIso = d.departAt; armAfterMs = arrivalCheckAfterMs(d.fromLat, d.fromLng, d.toLat, d.toLng) }
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
                        val dto = MessageDto(inc.id, inc.text, inc.senderId, flag = inc.flag, fromAdmin = inc.fromAdmin)
                        messages = when {
                            optIdx >= 0 -> messages.toMutableList().also { it[optIdx] = dto }
                            inc.id > 0 && messages.any { it.id == inc.id } -> messages   // дубль по id — пропустить
                            else -> messages + dto
                        }
                    }
                },
                onConnected = { wsConnected = it },
                // Сервер не принял сообщение (слишком быстрый поток). Помечаем его
                // «Не доставлено · Повторить» — тем же способом, что и отказ по обычному
                // запросу. Молча оставить нельзя: оно висело бы как отправленное.
                onRejected = { tempId, _ ->
                    voiceScope.launch {
                        failedIds = failedIds + tempId
                        Toast.makeText(context, tooFastMsg, Toast.LENGTH_SHORT).show()
                    }
                },
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

    // F11: авто-ретрай очереди исходящих при появлении сети. Слушаем ConnectivityManager: сеть вернулась →
    // разгружаем очередь (сообщения/статусы), затем подтягиваем авторитетную историю и состояние.
    fun flushOutbox() {
        val id = bookingId ?: return
        voiceScope.launch {
            val changed = Outbox.flush(context)
            if (changed) {
                queuedIds = emptySet()
                ApiClient.getMessages(id).onSuccess { messages = it }
                ApiClient.getTripState(id).onSuccess { st -> role = st.role; driverPhase = st.driverPhase; arrivalVerified = st.arrivalVerified; aloneWithDriver = st.aloneWithDriver; bookingStatus = st.status; offline = false }
            }
        }
    }
    // Пробуем разгрузить очередь при входе на экран (мог накопить в прошлой сессии без сети).
    LaunchedEffect(bookingId) { if (bookingId != null) flushOutbox() }
    DisposableEffect(bookingId) {
        val id = bookingId
        if (id == null) { onDispose { } }
        else {
            val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            val cb = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) { flushOutbox() }
            }
            runCatching { cm?.registerDefaultNetworkCallback(cb) }
            onDispose { runCatching { cm?.unregisterNetworkCallback(cb) } }
        }
    }

    // Доставка одного сообщения. Сперва WS (если жив), иначе REST. Ошибку НЕ глотаем:
    // - сетевой сбой (нет связи на трассе) → кладём в очередь (Outbox), помечаем «в очереди» —
    //   отправится само при появлении сети (F11), ничего не теряется;
    // - ошибка сервера → «Не доставлено · Повторить» (ручной повтор, как прежде).
    fun deliver(tempId: Int, text: String) {
        val bid = bookingId ?: return
        val ws = chatSocket
        // tempId уходит на сервер: если он откажется принять сообщение, вернёт этот же номер,
        // и мы пометим «Не доставлено» именно это сообщение (см. onRejected).
        val sentViaWs = wsConnected && ws != null && ws.send(text, tempId)   // false → сокет мёртв → REST
        if (sentViaWs) return   // эхо WS заменит оптимистичное сообщение настоящим
        voiceScope.launch {
            ApiClient.sendMessage(bid, text)
                .onSuccess { ApiClient.getMessages(bid).onSuccess { messages = it } }   // забираем авторитетную историю
                .onFailure { e ->
                    if (e is ApiException) {
                        failedIds = failedIds + tempId
                        Toast.makeText(context, sendFailMsg, Toast.LENGTH_SHORT).show()
                    } else {
                        // Нет сети → в очередь на авто-ретрай. Сообщение остаётся на экране с меткой «в очереди».
                        Outbox.enqueue(context, Outbox.newMessage(bid, text))
                        queuedIds = queuedIds + tempId
                        Toast.makeText(context, queuedMsg, Toast.LENGTH_SHORT).show()
                    }
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
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                TripRouteHeaderCard(
                    // Без сети ride может быть null (холодный старт по bookingId) — берём из офлайн-паспорта.
                    from = ride?.from ?: tripPass?.fromCity,
                    to = ride?.to ?: tripPass?.toCity,
                    driver = ride?.driver ?: tripPass?.driverName,
                    time = ride?.time ?: tripPass?.departAt?.let { formatDepart(it) },
                    modifier = Modifier.appearIn(0),
                )
            }
            // F11: офлайн-режим — сервер недоступен, но паспорт поездки сохранён локально.
            if (offline && tripPass != null) {
                item { OfflineTripBanner(modifier = Modifier.appearIn(0)) }
                item { TripPassCard(pass = tripPass!!, modifier = Modifier.appearIn(1)) }
            }
            // F12 «Зимний протокол»: спокойное напоминание в морозную ночь (ноя–мар + ночь).
            if (frostyNight) {
                item { FrostyNightBanner(modifier = Modifier.appearIn(1)) }
            }
            // Live-баннер пассажиру: водитель выехал/подъезжает (опрос статуса раз в ~12с, не только пуш).
            if (role == "passenger" && (driverPhase == "departed" || driverPhase == "arriving")) {
                item {
                    DriverApproachingBanner(
                        arriving = driverPhase == "arriving",
                        modifier = Modifier.appearIn(1),
                        verified = arrivalVerified,
                    )
                }
            }
            // Подсказка только пассажиру и только когда салон реально опустел. Водителю её нет.
            if (role == "passenger" && aloneWithDriver) {
                item {
                    AlonePassengerHint(onShare = { showShare = true }, modifier = Modifier.appearIn(1))
                }
            }
            if (boardingCode.isNotBlank() && bookingStatusAllowsBoarding(bookingStatus)) {
                item {
                    // Машину берём из офлайн-паспорта: он пишется при подтверждении брони и
                    // доступен без сети — а сверяют машину как раз у дороги, где связи может
                    // не быть. Госномера в публичной карточке поездки нет и быть не должно.
                    BoardingCodeCard(
                        code = boardingCode,
                        modifier = Modifier.appearIn(1),
                        car = tripPass?.driverCar.orEmpty().ifBlank { ride?.car.orEmpty() },
                        plate = tripPass?.driverPlate.orEmpty(),
                    )
                }
            }
            if (bookingId != null) {
                item {
                    Box(Modifier.appearIn(1)) {
                        PayAgreementBlock(
                            editable = false,
                            method = payMethod,
                            amountText = "",
                            summaryAmount = payAmount,
                            onMethod = {},
                            onAmount = {}
                        )
                    }
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
                                            if (st == "done") { TripPassStore.remove(context, bid); onTripEnd() }   // уходим с экрана только при реальном закрытии брони + чистим ПДн из паспорта
                                            else {
                                                Toast.makeText(context, driverNotifiedMsg, Toast.LENGTH_SHORT).show()
                                                ApiClient.getTripState(bid).onSuccess { s -> role = s.role; driverPhase = s.driverPhase; arrivalVerified = s.arrivalVerified; aloneWithDriver = s.aloneWithDriver; bookingStatus = s.status }   // сразу синхроним UI, не ждём 12с поллинга
                                            }
                                        }
                                        .onFailure { e ->
                                            if (e !is ApiException) {   // нет сети → статус в очередь на авто-ретрай (F11)
                                                Outbox.enqueue(context, Outbox.newDriverStatus(bid, st))
                                                Toast.makeText(context, queuedMsg, Toast.LENGTH_SHORT).show()
                                            } else Toast.makeText(context, statusErrMsg, Toast.LENGTH_SHORT).show()
                                        }
                                }
                            } else {
                                status = st
                                if (bid == null) { if (st == "done") onTripEnd() }   // демо/нет брони → просто закрываем
                                else voiceScope.launch {
                                    ApiClient.setTripStatus(bid, st)
                                        // «Завершить» уходит с экрана только при реальном закрытии брони на сервере.
                                        .onSuccess {
                                            if (st == "done") { TripPassStore.remove(context, bid); onTripEnd() }
                                            else ApiClient.getTripState(bid).onSuccess { s -> role = s.role; driverPhase = s.driverPhase; arrivalVerified = s.arrivalVerified; aloneWithDriver = s.aloneWithDriver; bookingStatus = s.status }   // сразу синхроним статус/код посадки
                                        }
                                        .onFailure { e ->
                                            if (e !is ApiException) {   // нет сети → статус «сел/доехал» в очередь на авто-ретрай (F11)
                                                Outbox.enqueue(context, Outbox.newTripStatus(bid, st))
                                                Toast.makeText(context, queuedMsg, Toast.LENGTH_SHORT).show()
                                            } else Toast.makeText(context, statusErrMsg, Toast.LENGTH_SHORT).show()
                                        }
                                }
                            }
                        },
                    )
                }
            }
            if (bookingStatus == "done") item {
                var myStars by remember { mutableStateOf(0) }
                var reviewText by remember { mutableStateOf("") }
                var rating by remember { mutableStateOf(false) }   // запрос в полёте — блок повторных тапов, откат при сбое
                var reviewSent by remember { mutableStateOf(false) }
                var pickedTags by remember { mutableStateOf(setOf<String>()) }
                val isDriver = role == "driver"
                val thanksMsg = appText("Спасибо за оценку", "Баһа өсөн рәхмәт")
                val reviewSentMsg = appText("Спасибо! Отзыв на проверке", "Рәхмәт! Фекер тикшереүҙә")
                val rateFailMsg = appText("Не получилось оценить", "Баһалап булманы")
                // Кого оцениваем: пассажир → водителя, водитель → пассажира.
                val rateTitle = if (isDriver) appText("Оцените попутчика", "Юлдашты баһалағыҙ")
                                else appText("Оцените водителя", "Водителде баһалағыҙ")
                Card(modifier = Modifier.appearIn(2), colors = CardDefaults.cardColors(containerColor = CanonSurface), shape = CanonItemShape, elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.card)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(rateTitle, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            (1..5).forEach { n ->
                                val starCd = starsText(n)
                                // Тач-цель ≥48dp (иконка визуально 38dp внутри), клик на всей зоне.
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clickable(enabled = !rating) {
                                            val prev = myStars
                                            myStars = n
                                            val id = bookingId
                                            if (id != null) {
                                                rating = true
                                                voiceScope.launch {
                                                    // Звёзды уходят сразу; текст и метки (если уже выбраны) прикрепляем тем же запросом.
                                                    ApiClient.rateBooking(id, n, reviewText, pickedTags.toList())
                                                        .onSuccess {
                                                            rating = false
                                                            if (!reviewSent) Toast.makeText(context, thanksMsg, Toast.LENGTH_SHORT).show()
                                                            // Честный рост: просим оценку в Play только у довольного пассажира (5★).
                                                            // Play сам решит, показывать ли; не чаще раза в 30 дней; без Play — no-op.
                                                            if (n == 5 && !isDriver) maybeRequestStoreReview(context)
                                                        }
                                                        .onFailure { rating = false; myStars = prev; Toast.makeText(context, rateFailMsg, Toast.LENGTH_SHORT).show() }   // откат: не показываем «оценено», если не сохранилось
                                                }
                                            }
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Default.Star,
                                        contentDescription = starCd,
                                        tint = if (n <= myStars) CanonStar else CanonMuted,
                                        modifier = Modifier.size(48.dp),
                                    )
                                }
                            }
                        }
                        // Быстрые метки — появляются сразу после звёзд. Тапнул пару штук и свободен:
                        // писать отзыв согласны единицы, а метку ставят почти все.
                        //
                        // Показываем те, что подходят выставленной оценке: за 4–5★ хвалебные,
                        // за 1–3★ те, что объясняют низкую. Предлагать «Грубый» человеку, который
                        // только что поставил пятёрку, — навязывать ссору на пустом месте.
                        //
                        // Набор зависит и от того, КОГО оцениваем: «Чисто в машине» пассажиру
                        // не адресуешь, машина не его.
                        if (myStars > 0) {
                            val tagOptions: List<Pair<String, String>> = if (myStars >= 4) {
                                if (isDriver) listOf(
                                    "polite" to appText("Вежливый", "Итәғәтле"),
                                    "ontime" to appText("Вовремя вышел", "Ваҡытында сыҡты"),
                                    "helpful" to appText("Помог в дороге", "Юлда ярҙам итте"),
                                ) else listOf(
                                    "polite" to appText("Вежливый", "Итәғәтле"),
                                    "ontime" to appText("Приехал вовремя", "Ваҡытында килде"),
                                    "clean" to appText("Чисто в машине", "Машинала таҙа"),
                                    "safe" to appText("Везёт аккуратно", "Һаҡ йөрөтә"),
                                    "comfortable" to appText("Ехать удобно", "Барыуы уңайлы"),
                                )
                            } else {
                                if (isDriver) listOf(
                                    "late" to appText("Опоздал", "Һуңланы"),
                                    "rude" to appText("Грубый", "Тупаҫ"),
                                ) else listOf(
                                    "late" to appText("Опоздал", "Һуңланы"),
                                    "rude" to appText("Грубый", "Тупаҫ"),
                                    "unsafe" to appText("Опасная езда", "Хәүефле йөрөтөү"),
                                    "dirty" to appText("Грязно в машине", "Машинала бысраҡ"),
                                    "detour" to appText("Вёз кругами", "Урап йөрөттө"),
                                )
                            }
                            // FlowRow, а не прокрутка вбок: башкирская подпись длиннее русской,
                            // и при крупном системном шрифте метки обязаны переноситься,
                            // а не уезжать за край, где их никто не найдёт.
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                tagOptions.forEach { (code, label) ->
                                    val on = code in pickedTags
                                    val chipBg by animateColorAsState(if (on) CanonMint else CanonSurface, tween(CanonMotion.QUICK), label = "tag-bg")
                                    val chipLine by animateColorAsState(if (on) CanonGreen2 else CanonBorder, tween(CanonMotion.QUICK), label = "tag-line")
                                    val chipInk by animateColorAsState(if (on) CanonGreen2 else CanonMutedStrong, tween(CanonMotion.QUICK), label = "tag-ink")
                                    Surface(
                                        onClick = {
                                            val next = if (on) pickedTags - code else pickedTags + code
                                            pickedTags = next
                                            // Уходит сразу, как и звёзды: отдельной кнопки «сохранить метки»
                                            // нет, иначе человек выберет и уйдёт, не нажав.
                                            val id = bookingId
                                            if (id != null) voiceScope.launch {
                                                ApiClient.rateBooking(id, myStars, reviewText, next.toList())
                                            }
                                        },
                                        enabled = !rating,
                                        color = chipBg,
                                        shape = CanonFieldShape,
                                        border = BorderStroke(1.dp, chipLine),
                                        modifier = Modifier
                                            .heightIn(min = 48.dp)          // тач-цель ≥48dp
                                            // this. обязательно: у самого экрана есть параметр `role`
                                            // (строка «driver»/«passenger»), и без уточнения Kotlin
                                            // подставляет его вместо семантики доступности.
                                            .semantics(mergeDescendants = true) {
                                                this.role = Role.Checkbox
                                                this.selected = on
                                            },
                                    ) {
                                        Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                                            Text(label, color = chipInk, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        }
                                    }
                                }
                            }
                        }
                        // Текстовый отзыв — появляется после выбора звёзд. Идёт на модерацию.
                        if (myStars > 0 && !reviewSent) {
                            OutlinedTextField(
                                value = reviewText,
                                onValueChange = { if (it.length <= 500) reviewText = it },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text(appText("Пара слов о поездке (необязательно)", "Сәфәр тураһында бер-ике һүҙ (мотлаҡ түгел)")) },
                                minLines = 2,
                                maxLines = 4,
                                shape = CanonItemShape,
                            )
                            Text(
                                appText("Отзыв появится после проверки", "Фекер тикшереүҙән һуң күренер"),
                                color = CanonMuted, fontSize = 12.sp,
                            )
                            Button(
                                onClick = {
                                    val id = bookingId
                                    if (id != null && reviewText.isNotBlank()) {
                                        rating = true
                                        voiceScope.launch {
                                            ApiClient.rateBooking(id, myStars, reviewText, pickedTags.toList())
                                                .onSuccess { rating = false; reviewSent = true; Toast.makeText(context, reviewSentMsg, Toast.LENGTH_SHORT).show() }
                                                .onFailure { rating = false; Toast.makeText(context, rateFailMsg, Toast.LENGTH_SHORT).show() }
                                        }
                                    }
                                },
                                enabled = !rating && reviewText.isNotBlank(),
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2),
                            ) {
                                Text(appText("Оставить отзыв", "Фекер ҡалдырыу"), fontWeight = FontWeight.Bold)
                            }
                        }
                        if (reviewSent) {
                            Text(appText("✓ Отзыв отправлен на проверку", "✓ Фекер тикшереүгә ебәрелде"), color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                }
            }
            // Онлайн-оплата завершённой поездки (карта/СБП через ЮKassa, за флагом провайдера).
            // Только пассажиру — платит владелец брони. Сумма для показа — из договорённости/цены,
            // списывает сервер по цене брони. 503 (провайдер выключен) → карточка тихо исчезает
            // на всю сессию (OnlinePayGate) — договорённость «на доверии» остаётся как раньше.
            if (bookingStatus == "done" && bookingId != null && role == "passenger") item {
                PayOnlineCard(
                    amountRub = payAmount ?: ride?.price?.takeIf { it > 0 },
                    pay = { m -> ApiClient.payBooking(bookingId, m) },
                    modifier = Modifier.appearIn(2),
                )
            }
            // Квитанция завершённой поездки: маршрут, дата, сумма, способ оплаты, водитель.
            if (bookingStatus == "done" && bookingId != null) item {
                AppButton(
                    text = appText("Квитанция поездки", "Сәфәр квитанцияһы"),
                    onClick = { onOpenReceipt(bookingId) },
                    style = AppButtonStyle.Secondary,
                    icon = Icons.Default.ReceiptLong,
                    modifier = Modifier.appearIn(2),
                )
            }
            // Мягкое, ненавязчивое предложение поддержать платформу после завершённой поездки.
            // Легко закрыть (крестик / «Не сейчас») — поддержка строго по желанию.
            if (bookingStatus == "done") item {
                var supportDismissed by rememberSaveable(bookingId) { mutableStateOf(false) }
                AnimatedVisibility(visible = !supportDismissed) {
                    Card(
                        modifier = Modifier.appearIn(3),
                        colors = CardDefaults.cardColors(containerColor = CanonGreen.copy(alpha = 0.10f)),
                        shape = CanonItemShape,
                        elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.flat)
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.VolunteerActivism, contentDescription = null, tint = CanonGreen)
                                Text(appText("Юлдаш делают для своих 🌱", "Юлдашты үҙебеҙ өсөн эшләйбеҙ 🌱"), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                IconButton(onClick = { supportDismissed = true }) {
                                    Icon(Icons.Default.Close, contentDescription = appText("Закрыть", "Ябыу"), tint = CanonMuted)
                                }
                            }
                            Text(
                                appText("Если нравится — поддержи, это по желанию.", "Оҡшаһа — ярҙам ит, был ирекле."),
                                color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(
                                    onClick = onSupport,
                                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = CanonGreen.copy(alpha = 0.18f))
                                ) {
                                    Text(appText("Поддержать", "Ярҙам итеү"), color = CanonGreen, fontWeight = FontWeight.Bold)
                                }
                                TextButton(onClick = { supportDismissed = true }) {
                                    Text(appText("Не сейчас", "Хәҙер түгел"), color = CanonMuted)
                                }
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
                var cancelReason by remember { mutableStateOf("") }   // код причины отмены (по желанию пассажира)
                val cancelOkMsg = appText("Поездка отменена", "Сәфәр кире алынды")
                // B8-8: отмена после открытия телефона/чата — мягкое напоминание (не обвиняем).
                val contactCancelMsg = appText(
                    "Договорились ехать? Заверши поездку в приложении — так работает защита и SOS 💚",
                    "Барырға һөйләштегеҙме? Сәфәрҙе ҡушымтала тамамла — шулай яҡлау һәм SOS эшләй 💚",
                )
                val cancelFailMsg = appText("Не удалось отменить", "Кире алып булманы")
                // Экран отстал: поездку уже завершили, пока диалог был открыт.
                val cancelTooLateMsg = appText(
                    "Поездка уже завершена — отменить её нельзя.",
                    "Сәфәр тамамланған инде — уны кире алып булмай.",
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(
                        onClick = { showCancel = true },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, CanonRed)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null, tint = CanonRed)
                        Spacer(Modifier.width(8.dp))
                        Text(appText("Отменить поездку", "Сәфәрҙе кире алыу"), color = CanonRed, fontWeight = FontWeight.Bold)
                    }
                    Text(appText("Отмена бесплатна до начала поездки — место вернётся в поездку.", "Сәфәр башланғанға тиклем кире алыу бушлай — урын кире ҡайта."), color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp)
                }
                if (showCancel) {
                    AlertDialog(
                        onDismissRequest = { showCancel = false },
                        title = { Text(appText("Отменить поездку?", "Сәфәрҙе кире аларғамы?")) },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(appText("Бронь будет отменена, место освободится для других.", "Брон кире алына, урын башҡаларға бушай."))
                                Text(appText("Причина (по желанию):", "Сәбәбе (теләгәнсә):"), color = CanonMuted, fontSize = 12.sp)
                                listOf(
                                    "plans_changed" to appText("Планы поменялись", "Пландар үҙгәрҙе"),
                                    "found_other" to appText("Нашёл другой вариант", "Башҡа юл таптым"),
                                    "driver_no_response" to appText("Водитель не отвечает", "Водитель яуап бирмәй"),
                                ).forEach { (code, label) ->
                                    val on = cancelReason == code
                                    Text(
                                        label,
                                        color = if (on) CanonGreen2 else CanonText,
                                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 14.sp,
                                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                            .clickable { cancelReason = if (on) "" else code }
                                            .background(if (on) CanonMint else Color.Transparent)
                                            .padding(horizontal = 10.dp, vertical = 8.dp)
                                    )
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                showCancel = false
                                bookingId?.let { id ->
                                    voiceScope.launch {
                                        ApiClient.cancelBooking(id, cancelReason)
                                            .onSuccess { res ->
                                                if (!res.cancelled) {
                                                    // Сервер отменять отказался — поездка уже завершена (или отменена
                                                    // раньше). Экран у пассажира просто отстал: водитель нажал
                                                    // «доехали», пока диалог был открыт. Врать «отменено» нельзя,
                                                    // и паспорт поездки стирать тоже — поездка-то состоялась.
                                                    Toast.makeText(context, cancelTooLateMsg, Toast.LENGTH_LONG).show()
                                                    bookingStatus = res.status.ifBlank { bookingStatus }
                                                    return@onSuccess
                                                }
                                                // F11: локальный паспорт поездки больше не нужен — бронь отменена.
                                                TripPassStore.remove(context, id)
                                                // B8-8: телефон/чат уже открывались → мягко напоминаем про защиту в приложении.
                                                val msg = if (res.contactThenCancel) contactCancelMsg else cancelOkMsg
                                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                                onTripEnd()
                                            }
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
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                            .background(CanonMint, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = CanonGreen2)
                        Spacer(Modifier.width(8.dp))
                        Text(appText("Соединение восстанавливается…", "Бәйләнеш тергеҙелә…"), color = CanonGreen2, fontSize = 14.sp)
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
                            Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
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
            // B8-6: дисклеймер безопасности при первом открытии чата (закрывается «Понятно»).
            item { ChatSafetyDisclaimer() }
            items(visibleMessages, key = { it.id }) { m ->
                val saved = m.id > 0   // оптимистичные (id<0) ещё не на сервере — без меню
                Box(Modifier.fillMaxWidth().animateItem()) {   // плавное появление/перестановка пузыря в списке
                MessageBubble(
                    text = m.text,
                    voiceUrl = m.voiceUrl,
                    mine = m.senderId == myId,
                    failed = m.id in failedIds,
                    queued = m.id in queuedIds,
                    deleted = m.deleted,
                    edited = m.edited,
                    flag = m.flag,
                    fromAdmin = m.fromAdmin,
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
            }
            item {
                val voiceSoon = appText("Голос записан", "Тауыш яҙылды")
                if (editingId != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                            .background(CanonWarnBg, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, tint = CanonWarn, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(appText("Редактирование сообщения", "Хәбәрҙе үҙгәртеү"), color = CanonWarn, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Text(
                            appText("Отмена", "Кире алыу"), color = CanonGreen2, fontSize = 14.sp, fontWeight = FontWeight.Bold,
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
                    },
                    onQuickSend = { phrase -> sendText(phrase) }   // готовая фраза — тот же надёжный путь (WS→REST)
                )
            }
            // F12 «Застрял на трассе» — уровень мягче паники SOS: зовём своих на помощь в дороге.
            // Только в реальной активной поездке (есть бронь и посадка подтверждена).
            if (bookingId != null && bookingStatusAllowsBoarding(bookingStatus)) {
                item {
                    // Блок общий с такси и доставкой (RoadsideHelp.kt): копии разошлись текстами.
                    RoadsideHelpAction(key = bookingId, modifier = Modifier.appearIn(5)) { lat, lng ->
                        ApiClient.roadsideHelp(bookingId, lat, lng, "")
                    }
                }
            }
            item {
                Button(
                    onClick = onSos,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CanonRed)
                ) {
                    // Icons.Default.Sos сам рисует буквы «SOS» → с Text("SOS") выходило «SOS SOS». Щит (как на карте).
                    Icon(Icons.Default.Shield, contentDescription = appText("Экстренный вызов", "Ашығыс саҡырыу"))
                    Spacer(Modifier.width(8.dp))
                    Text("SOS", fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    WinterArrivalDialog(showArrivalCheck) {
        val bid = bookingId
        if (bid != null) voiceScope.launch { ApiClient.winterCheckOk(bid) }
    }

    if (showShare) {
        ModalBottomSheet(onDismissRequest = { showShare = false }, sheetState = shareSheet, containerColor = CanonSurface) {
            // Ссылка live-поездки (B7c): после выбора близкого показываем её тут же —
            // скопировать или отправить самому через системный share-sheet.
            var liveLink by remember { mutableStateOf<String?>(null) }
            // Приватность: кому сейчас открыта поездка + возможность отозвать.
            var activeShares by remember { mutableStateOf<List<Pair<com.yuldash.app.data.TripShareDto, String>>>(emptyList()) }
            // «Поделиться ещё» гасит вид ссылки, но список активных ссылок оставляем видимым.
            var showContacts by remember { mutableStateOf(true) }
            // Спрашиваем сервер, кому уже открыто. Раньше список жил только в памяти экрана:
            // человек делился, сворачивал приложение — и отзывать было нечего, хотя ссылка на
            // его живое местоположение работала до конца поездки (аудит 2026-08-06). Старый
            // сервер без этой ручки просто вернёт ошибку — поведение как раньше, ничего не ломаем.
            LaunchedEffect(bookingId) {
                val bid = bookingId ?: return@LaunchedEffect
                ApiClient.getBookingShares(bid).onSuccess { srv ->
                    if (srv.isEmpty()) return@onSuccess
                    activeShares = srv.map { s ->
                        s to (contacts.firstOrNull { it.id == s.contactId }?.name ?: "")
                    }
                    liveLink = srv.firstNotNullOfOrNull { it.link?.takeIf(String::isNotBlank) }
                    showContacts = false   // есть кому открыто → сразу показываем список, а не выбор контакта
                }
            }
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                val link = liveLink
                if (activeShares.isNotEmpty() && !showContacts) {
                    Text(appText("Ссылка для близкого", "Яҡын кеше өсөн һылтанма"), fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
                    if (!link.isNullOrBlank()) LiveLinkCard(link)
                    ActiveSharesList(activeShares) { share ->
                        val bid = bookingId
                        if (bid != null) voiceScope.launch {
                            ApiClient.revokeBookingShare(bid, share.id)
                                .onSuccess {
                                    activeShares = activeShares.filterNot { it.first.id == share.id }
                                    if (activeShares.none { !it.first.link.isNullOrBlank() }) liveLink = null
                                    if (activeShares.isEmpty()) showContacts = true
                                    Toast.makeText(context, shareRevokedMsg, Toast.LENGTH_SHORT).show()
                                }
                                .onFailure { Toast.makeText(context, shareErrMsg, Toast.LENGTH_SHORT).show() }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { showContacts = true }) {
                            Text(appText("Поделиться ещё", "Йәнә бүлешеү"), color = CanonGreen2, fontWeight = FontWeight.Bold)
                        }
                        TextButton(onClick = { showShare = false }) {
                            Text(appText("Готово", "Әҙер"), color = CanonGreen2, fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                Text(appText("Кому отправить поездку", "Сәфәрҙе кемгә ебәрергә"), fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
                if (contacts.isEmpty()) {
                    Text(appText("Сначала добавьте доверенный контакт в профиле", "Башта профилдә ышаныслы контакт өҫтәгеҙ"), color = CanonMuted)
                }
                contacts.forEach { c ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            val bid = bookingId
                            if (bid != null) voiceScope.launch {
                                ApiClient.shareTrip(bid, c.id)
                                    .onSuccess { share ->
                                        Toast.makeText(context, "$tripSharedPrefix: ${c.name}", Toast.LENGTH_SHORT).show()
                                        if (share != null) {
                                            activeShares = activeShares.filterNot { it.first.id == share.id } + (share to c.name)
                                            if (!share.link.isNullOrBlank()) liveLink = share.link
                                            showContacts = false
                                        } else showShare = false
                                    }
                                    .onFailure {
                                        showShare = false
                                        Toast.makeText(context, shareErrMsg, Toast.LENGTH_SHORT).show()
                                    }
                            } else showShare = false
                        }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(44.dp).background(CanonMint, CircleShape), contentAlignment = Alignment.Center) {
                            Text(c.name.take(1), fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.name, fontWeight = FontWeight.Bold)
                            Text(c.relation, color = CanonMuted, fontSize = 14.sp)
                        }
                        Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = CanonMuted)
                    }
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

/** F12: параметры мягкой проверки «доехал?». Момент проверки = расчётная ETA маршрута
 *  (расстояние по прямой / средняя скорость) + запас. Скорость консервативная (со стопами и
 *  трафиком), запас щедрый — чтобы НЕ спросить «доехал?» посреди длинной межгородской поездки
 *  и не потревожить близкого зря. Нет координат маршрута → фолбэк (3 ч). */
private const val ARRIVAL_CHECK_FALLBACK_MS = 3L * 60 * 60_000L   // нет ETA → щедрый фолбэк
private const val ARRIVAL_CHECK_GRACE_MS = 45L * 60_000L          // запас после расчётного прибытия
private const val ARRIVAL_ASSUMED_KMH = 45.0                      // консервативная средняя скорость

/** Через сколько после выезда будить проверку «доехал?»: расчётное время в пути (haversine/скорость)
 *  + запас. Нет полных координат → фолбэк. Никаких сетевых вызовов — считаем локально. */
internal fun arrivalCheckAfterMs(fromLat: Double?, fromLng: Double?, toLat: Double?, toLng: Double?): Long {
    if (fromLat == null || fromLng == null || toLat == null || toLng == null) return ARRIVAL_CHECK_FALLBACK_MS
    val r = 6371.0
    val dLat = Math.toRadians(toLat - fromLat)
    val dLon = Math.toRadians(toLng - fromLng)
    val h = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
        Math.cos(Math.toRadians(fromLat)) * Math.cos(Math.toRadians(toLat)) * Math.sin(dLon / 2) * Math.sin(dLon / 2)
    val km = 2 * r * Math.asin(Math.min(1.0, Math.sqrt(h)))
    if (km <= 0.0) return ARRIVAL_CHECK_FALLBACK_MS
    val travelMs = (km / ARRIVAL_ASSUMED_KMH * 3_600_000.0).toLong()
    return travelMs + ARRIVAL_CHECK_GRACE_MS
}

/** Момент времени → ISO С ЧАСОВЫМ ПОЯСОМ («2026-08-05T10:00:00+05:00») для отправки на сервер.
 *
 *  Единственный правильный способ назвать серверу время: без пояса он вынужден догадываться,
 *  а догадка была неверной — уфимские часы принимались за UTC, и поездка «уезжала» на 5 часов
 *  (разбор №2, 2026-08-03). Такси-предзаказ так делал с самого начала, попутка и заявка — нет;
 *  теперь во всём приложении одно правило. */
internal fun isoWithOffset(ms: Long): String =
    java.time.OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), java.time.ZoneId.systemDefault())
        .withNano(0).toString()

/** ISO выезда (UTC-наивный с сервера) → epoch millis. Терпимо к 'Z'/смещению/долям секунды. */
internal fun parseIsoUtcMillis(iso: String): Long? = try {
    // Если пояс указан явно — верим ему, а не соглашению. Раньше «+05:00» просто отрезался,
    // и время сдвигалось на этот же пояс. Сервер такие строки не шлёт, но клиент их теперь
    // ФОРМИРУЕТ (isoWithOffset), и однажды они вернутся эхом — пусть читается правильно.
    runCatching { java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrElse {
        val s = iso.substringBefore('.').substringBefore('+').removeSuffix("Z").take(19)
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        fmt.parse(s)?.time
    }
} catch (e: Exception) {
    null
}

/** F12 «Зимний протокол»: сейчас морозная ночь? Зима по МЕСЯЦУ (ноя–мар) + ночное время
 *  (20:00–07:00) по календарю устройства. Без внешних API/погоды в v1 — простое и честное правило. */
internal fun isFrostyWinterNight(now: java.util.Calendar = java.util.Calendar.getInstance()): Boolean {
    val month = now.get(java.util.Calendar.MONTH)   // 0=янв … 11=дек
    val hour = now.get(java.util.Calendar.HOUR_OF_DAY)
    val winter = month == java.util.Calendar.NOVEMBER || month == java.util.Calendar.DECEMBER ||
        month == java.util.Calendar.JANUARY || month == java.util.Calendar.FEBRUARY || month == java.util.Calendar.MARCH
    val night = hour >= 20 || hour < 7
    return winter && night
}

/** Спокойный баннер морозной ночи: напомнить взять тепло и проверить заряд. Тон — заботливый, не тревожный. */
@Composable
internal fun FrostyNightBanner(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = CanonMint),
        shape = CanonItemShape,
        elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.flat),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.AcUnit, contentDescription = appText("Морозная ночь", "Һыуыҡ төн"), tint = CanonGreen2, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(appText("Морозная ночь", "Һыуыҡ төн"), fontWeight = FontWeight.Bold, color = CanonGreen2)
                Text(
                    appText(
                        "Возьми тёплые вещи и проверь заряд телефона перед дорогой. Поделись поездкой со своими.",
                        "Йылы кейем ал һәм юлға сыҡҡанға тиклем телефон зарядын тикшер. Сәфәреңде үҙеңдекеләр менән уртаҡлаш.",
                    ),
                    color = CanonGreen2, fontSize = 14.sp, lineHeight = 20.sp,
                )
            }
        }
    }
}

/** F12 «Застрял на трассе»: заметная, но спокойная (амбер), отдельный уровень от красной паники SOS.
 *  Состояния: обычная / отправка / отправлено. Двуязычие внутри. */
@Composable
internal fun RoadsideHelpButton(sending: Boolean, sent: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        enabled = !sending && !sent,
        modifier = modifier.fillMaxWidth().height(50.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = CanonWarnBg, contentColor = CanonWarn),
        border = BorderStroke(1.dp, CanonWarn),
    ) {
        if (sending) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = CanonWarn)
            Spacer(Modifier.width(8.dp))
            Text(appText("Отправляем…", "Ебәрелә…"), color = CanonWarn, fontWeight = FontWeight.Bold)
        } else {
            Icon(if (sent) Icons.Default.CheckCircle else Icons.Default.Warning, contentDescription = null, tint = CanonWarn, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                if (sent) appText("Помощь позвана", "Ярҙам саҡырылды") else appText("Застрял на трассе", "Юлда ҡалдым"),
                color = CanonWarn, fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** Шапка активной поездки: «откуда → куда», водитель, время. Пустые значения → «—». */
@Composable
internal fun TripRouteHeaderCard(
    from: String?,
    to: String?,
    driver: String?,
    time: String?,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = CanonSurface), shape = CanonCardShape, elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.card)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${from ?: "—"}  →  ${to ?: "—"}", fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.DirectionsCar, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(driver ?: appText("Водитель", "Йөрөтөүсе"), color = CanonMuted)
                time?.let { Spacer(Modifier.width(8.dp)); Text(it, color = CanonMuted) }
            }
        }
    }
}

/** Live-баннер пассажиру: водитель выехал / подъезжает. `arriving` → акцентная (зелёная) плашка.
 *
 * `verified` — сервер сверил «подъезжаю» с живым GPS водителя и убедился, что машина рядом.
 * Строка подтверждения — наш ответ на массовую жалобу к inDrive («жмут „я приехал“, стоя
 * за километры»): пассажир выходит из дома, только когда за словами водителя есть проверка.
 */
@Composable
internal fun DriverApproachingBanner(
    arriving: Boolean,
    modifier: Modifier = Modifier,
    verified: Boolean = false,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = if (arriving) CanonGreen2 else CanonMint,
        shape = CanonCardShape
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.DirectionsCar, contentDescription = null, tint = if (arriving) Color.White else CanonGreen2, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (arriving) appText("Водитель подъезжает", "Водитель яҡынлаша") else appText("Водитель выехал к тебе", "Водитель сыҡты"),
                    color = if (arriving) Color.White else CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp
                )
                // Показываем ТОЛЬКО когда проверка реально прошла. Нет подтверждения — молчим,
                // а не рисуем галочку авансом: ложное «проверено» хуже отсутствующего.
                AnimatedVisibility(visible = arriving && verified) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.CheckCircle, contentDescription = null,
                            tint = Color.White, modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            appText("Подтверждено по GPS — машина рядом", "GPS раҫланы — машина янда"),
                            color = Color.White, fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

/** Плашка кода посадки: пассажир называет код водителю для сверки машины. */
@Composable
internal fun BoardingCodeCard(
    code: String,
    modifier: Modifier = Modifier,
    car: String = "",      // «белая Lada Vesta» — как выглядит машина
    plate: String = "",    // госномер: по нему и сверяют
) {
    Surface(modifier = modifier, color = CanonMint, shape = CanonCardShape) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Pin, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(appText("Код посадки", "Ултырыу коды"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(appText("Назовите водителю — он сверит. Это та самая машина.", "Водителгә әйтегеҙ — ул тикшерер. Тап шул машина."), color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp)
                }
                Spacer(Modifier.width(8.dp))
                Text(code, color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 34.sp, letterSpacing = 4.sp)
            }
            // Обещание «это та самая машина» до сих пор нечем было проверить: пассажир видел
            // марку, но не номер. Разбор конкурентов 2026-08-07 — у BlaBlaCar приезжала другая
            // машина с другим человеком за рулём. Показываем ровно то, что сверяют глазами.
            if (car.isNotBlank() || plate.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.DirectionsCar,
                        contentDescription = appText("Машина водителя", "Водитель машинаһы"),
                        tint = CanonGreen2, modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            appText("Сверьте машину перед посадкой", "Ултырыр алдынан машинаны тикшерегеҙ"),
                            color = CanonMuted, fontSize = 12.sp,
                        )
                        if (car.isNotBlank()) {
                            Text(car, color = CanonText, fontSize = 14.sp)
                        }
                    }
                    if (plate.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Surface(color = CanonSurface, shape = CanonTinyShape) {
                            Text(
                                plate,
                                color = CanonText, fontWeight = FontWeight.Bold,
                                fontSize = 19.sp, letterSpacing = 1.sp,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * F11 — плашка офлайн-режима. Сервер недоступен (трасса без связи), но паспорт поездки
 * сохранён локально: спокойно сообщаем об этом, без тревоги, тёплым тоном.
 */
@Composable
internal fun OfflineTripBanner(modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxWidth(), color = CanonWarnBg, shape = CanonCardShape) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CloudOff, contentDescription = appText("Нет сети", "Селтәр юҡ"), tint = CanonWarn, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(appText("Офлайн — данные сохранены", "Офлайн — мәғлүмәт һаҡланған"), color = CanonWarn, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    appText("Показываем сохранённую поездку. Сообщения и статусы отправим, как появится сеть.",
                        "Һаҡланған сәфәрҙе күрһәтәбеҙ. Хәбәр һәм хәлдәрҙе селтәр булғас ебәрербеҙ."),
                    color = CanonWarn, fontSize = 14.sp, lineHeight = 20.sp
                )
            }
        }
    }
}

/**
 * F11 — карточка «Паспорт поездки»: всё главное для встречи с водителем без сети —
 * маршрут, время, водитель+машина, телефон (кнопка «Позвонить»), код посадки, точка сбора, оплата.
 * Телефон — ПДн, показываем участнику брони; НЕ логируем.
 */
@Composable
internal fun TripPassCard(pass: com.yuldash.app.data.TripPass, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = CanonItemShape,
        elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.card)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Lock, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(appText("Паспорт поездки", "Сәфәр паспорты"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            TripPassRow(Icons.Default.Route, appText("Маршрут", "Юл"), "${pass.fromCity} → ${pass.toCity}")
            if (pass.departAt.isNotBlank()) TripPassRow(Icons.Default.Schedule, appText("Время", "Ваҡыт"), formatDepart(pass.departAt))
            val driverLine = listOf(pass.driverName, pass.driverCar).filter { it.isNotBlank() }.joinToString(" · ")
            if (driverLine.isNotBlank()) TripPassRow(Icons.Default.Person, appText("Водитель", "Йөрөтөүсе"), driverLine)
            // Номер отдельной строкой, а не в хвосте описания машины: его сверяют глазами
            // в темноте у обочины, и он должен читаться сразу.
            if (pass.driverPlate.isNotBlank()) {
                TripPassRow(Icons.Default.DirectionsCar, appText("Госномер", "Дәүләт номеры"), pass.driverPlate)
            }
            if (pass.driverPhone.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TripPassRow(Icons.Default.Phone, appText("Телефон", "Телефон"), pass.driverPhone, modifier = Modifier.weight(1f))
                    FilledTonalButton(
                        onClick = {
                            runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${pass.driverPhone}"))) }
                        },
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = CanonMint, contentColor = CanonGreen2)
                    ) {
                        Icon(Icons.Default.Phone, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(appText("Позвонить", "Шылтыратыу"), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
            }
            if (pass.pickup.isNotBlank()) TripPassRow(Icons.Default.LocationOn, appText("Точка сбора", "Йыйылыу урыны"), pass.pickup)
            if (pass.boardingCode.isNotBlank()) TripPassRow(Icons.Default.Pin, appText("Код посадки", "Ултырыу коды"), pass.boardingCode)
            if (pass.price > 0) TripPassRow(
                Icons.Default.Payments, appText("Оплата", "Түләү"),
                appText("${pass.price} ₽ · перевод по СБП", "${pass.price} ₽ · СБП аша күсереү")
            )
        }
    }
}

@Composable
private fun TripPassRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, color = CanonMuted, fontSize = 12.sp)
            Text(value, color = CanonText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 23.sp)
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
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = if (role != "driver" && selectedStatus == st) CanonMint else CanonSurface,
                    contentColor = CanonText
                )
            ) { Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/**
 * «Едет пассажир младше 18» — отметка и взрослый, который за него отвечает.
 *
 * Возраст у нас не спрашивался нигде: подросток регистрировался и садился к незнакомому
 * человеку, водитель об этом не знал, а отвечать в случае чего пришлось бы ему. Запрещать
 * нельзя — сайт прямо обещает «школьник доберётся», и в районе это реальная нужда: до школы,
 * в райцентр, к врачу. Поэтому не запрет, а взрослый на связи + честная видимость.
 *
 * Имя и телефон взрослого обязательны (сервер без них бронь не создаст): это и есть запись
 * согласия, и водителю есть кому позвонить, если что-то пойдёт не так.
 */
@Composable
internal fun MinorPassengerBlock(
    checked: Boolean,
    guardianName: String,
    guardianPhone: String,
    onChecked: (Boolean) -> Unit,
    onName: (String) -> Unit,
    onPhone: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxWidth(), color = CanonSurface, shape = CanonCardShape, border = BorderStroke(1.dp, CanonBorder)) {
        Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SettingSwitchRow(
                Icons.Default.EscalatorWarning,
                appText("Едет пассажир младше 18", "18-ҙән кесе юлсы бара"),
                appText("Водитель увидит это до подтверждения", "Водитель быны раҫлауға тиклем күрер"),
                checked,
            ) { onChecked(it) }
            AnimatedVisibility(visible = checked) {
                Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        appText("Кто из взрослых отвечает за поездку. Водителю будет кому позвонить.",
                            "Сәфәр өсөн ҡайһы оло кеше яуаплы. Водителгә шылтыратырға кем булыр."),
                        color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp,
                    )
                    OutlinedTextField(
                        guardianName, onName,
                        label = { Text(appText("Имя взрослого", "Оло кешенең исеме")) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                    OutlinedTextField(
                        guardianPhone, onPhone,
                        label = { Text(appText("Телефон взрослого", "Оло кешенең телефоны")) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    )
                }
            }
        }
    }
}

/**
 * Тихая подсказка, когда салон опустел: попутчики вышли, человек остался один на один
 * с водителем.
 *
 * Откуда взялось: история на 849 голосов (r/india) — приставания начались ровно после высадки
 * второй пассажирки. Женщина доплатила сверху и промолчала, лишь бы доехать без конфликта.
 * SOS в такой момент не жмут: он ощущается как «поднять шум из-за слов».
 *
 * Поэтому здесь НЕТ тревоги: спокойный факт и одно действие. Ни пуша, ни звука, ни красного
 * цвета — иначе подсказка сама становится источником страха. Водителю она не видна и
 * обвинением не является: поделиться поездкой — обычная вещь, а не сигнал недоверия.
 */
@Composable
internal fun AlonePassengerHint(
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxWidth(), color = CanonMint, shape = CanonCardShape) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Person, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    appText("Попутчики вышли — дальше едешь одна(один)", "Юлдаштар төштө — артабан яңғыҙ бараһың"),
                    color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                )
                Text(
                    appText("Можно отправить близкому ссылку — он будет видеть, где ты едешь.",
                        "Яҡыныңа һылтанма ебәрергә була — ул ҡайҙа барғаныңды күреп торор."),
                    color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp,
                )
            }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = onShare, shape = RoundedCornerShape(14.dp)) {
                Text(appText("Отправить", "Ебәрергә"), fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Строка «Поделиться поездкой с близким» — открывает шит выбора контакта. */
@Composable
internal fun ShareTripRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(colors = CardDefaults.cardColors(containerColor = CanonSurface), shape = CanonItemShape, elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.card)) {
        Row(
            modifier.fillMaxWidth().clickable { onClick() }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(color = CanonMint, shape = CircleShape) {
                Icon(Icons.Default.Person, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(8.dp).size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(appText("Поделиться поездкой с близким", "Сәфәрҙе яҡының менән уртаҡлашыу"), fontWeight = FontWeight.Bold)
                Text(appText("Близкий будет видеть статус поездки", "Яҡының сәфәр хәлен күреп торор"), color = CanonMuted, fontSize = 14.sp)
            }
            Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = CanonMuted)
        }
    }
}

/** Список активных live-ссылок поездки с кнопкой «Отозвать» (приватность B7c). */
@Composable
private fun ActiveSharesList(
    shares: List<Pair<com.yuldash.app.data.TripShareDto, String>>,
    onRevoke: (com.yuldash.app.data.TripShareDto) -> Unit,
) {
    if (shares.isEmpty()) return
    Spacer(Modifier.height(12.dp))
    Text(
        appText("Активные ссылки", "Әүҙем һылтанмалар"),
        color = CanonMuted, fontSize = 14.sp, fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(4.dp))
    shares.forEach { (share, name) ->
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Person, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(name, color = CanonText, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(
                onClick = { onRevoke(share) },
                modifier = Modifier.heightIn(min = 44.dp),
            ) {
                Icon(Icons.Default.Close, contentDescription = appText("Отозвать ссылку", "Һылтанманы кире алыу"), tint = CanonRed, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(appText("Отозвать", "Кире алыу"), color = CanonRed, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
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
    queued: Boolean = false,
    deleted: Boolean = false,
    edited: Boolean = false,
    flag: String = "",           // метка сервера: warn (фишинг) / contact (телефон) / abuse (грубость)
    fromAdmin: Boolean = false,  // B8-9: бейдж «Юлдаш ✓» (только серверный флаг)
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
    if (fromAdmin && !deleted) YuldashOfficialBadge(Modifier.padding(bottom = 4.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Box {
        Surface(
            color = if (deleted) CanonSurface else if (mine) CanonGreen2 else CanonSurface,
            shape = RoundedCornerShape(14.dp),
            shadowElevation = CanonDepth.card,
            border = if (deleted) BorderStroke(1.dp, CanonBorder) else null,
            modifier = if (showMenu) Modifier.combinedClickable(onClick = {}, onLongClick = { menu = true }) else Modifier
        ) {
            if (deleted) {
                Text(
                    appText("Сообщение удалено", "Хәбәр юйылды"),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    color = CanonMuted, fontSize = 14.sp, fontStyle = FontStyle.Italic
                )
            } else if (voiceUrl != null) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
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
                        modifier = Modifier.size(48.dp)
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
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    color = if (mine) Color.White else CanonText,
                    fontSize = 16.sp
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
        if (flag.isNotEmpty() && !deleted) {
            // Кому показывать — решает сама плашка: фишинг и грубость видит получатель,
            // предупреждение про телефон — отправитель (рискует именно он).
            ChatFlagPlate(flag, mine, Modifier.padding(top = 4.dp))
        }
        if (edited && !deleted) {
            Text(
                appText("изменено", "үҙгәртелде"),
                color = CanonMuted, fontSize = 14.sp,
                modifier = Modifier.padding(top = 4.dp, end = 4.dp)
            )
        }
        if (failed) {
            Text(
                appText("Не доставлено · Повторить", "Ебәрелмәне · Ҡабатларға"),
                color = CanonRed,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp, end = 4.dp).bounceClick { onRetry() }
            )
        } else if (queued) {
            // F11: сообщение в очереди — уйдёт само, когда вернётся сеть.
            Row(
                modifier = Modifier.padding(top = 4.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(Icons.Default.Schedule, contentDescription = null, tint = CanonMuted, modifier = Modifier.size(13.dp))
                Text(
                    appText("В очереди · отправим при сети", "Сиратта · селтәр булғас ебәрербеҙ"),
                    color = CanonMuted, fontSize = 12.sp
                )
            }
        }
    }
}


