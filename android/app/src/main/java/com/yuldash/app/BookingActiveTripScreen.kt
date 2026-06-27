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
    ads: List<PartnerAd>,
    adStats: Map<String, AdStats>,
    onBack: () -> Unit,
    onSelectTab: (HomeTab) -> Unit,
    onMessage: () -> Unit,
    onAdImpression: (PartnerAd) -> Unit,
    onAdClick: (PartnerAd) -> Unit,
    onConfirmRide: () -> Unit
) {
    val routeAd = ads.forPlacement(AdPlacement.TripDetails).firstOrNull { it.matchesRoute(ride.from, ride.to) }
    val context = LocalContext.current
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
                        "Еду ${ride.from} → ${ride.to}, ${ride.timeText()}. ${ride.price} ₽. Поехали вместе в Юлдаше 👇\nhttps://yulbash.ru",
                        "${ride.from} → ${ride.to}, ${ride.timeText()}. ${ride.price} ₽. Әйҙә бергә — Юлдашта 👇\nhttps://yulbash.ru"
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
                            Text("${ride.from}  →  ${ride.to}", color = CanonText, fontSize = 19.sp, lineHeight = 21.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                DetailMeta(Icons.Default.CalendarMonth, ride.timeText(), modifier = Modifier.weight(1.45f))
                    DetailMeta(Icons.Default.Person, seatsText(ride.seats), modifier = Modifier.weight(0.8f))
                            }
                        }
                        Surface(color = CanonMint, shape = RoundedCornerShape(12.dp)) {
                            Text("${ride.price} ₽", modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp), color = CanonGreen2, fontWeight = FontWeight.Black, fontSize = 14.sp)
                        }
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = CanonSurface), shape = CanonCardShape, elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(color = CanonMint, shape = CircleShape) {
                                Text(ride.driver.firstOrNull()?.uppercase() ?: "?", modifier = Modifier.padding(22.dp), color = CanonGreen2, fontWeight = FontWeight.Black, fontSize = 22.sp)
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(ride.driver, color = CanonText, fontWeight = FontWeight.Black, fontSize = 21.sp)
                                    if (ride.verified) {
                                        Spacer(Modifier.width(6.dp))
                                        Icon(Icons.Default.Verified, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(20.dp))
                                    }
                                }
                                Text(appText("Опытный водитель", "Тәжрибәле водитель"), color = CanonMuted, fontSize = 14.sp)
                                DetailMeta(Icons.Default.DirectionsCar, ride.carText())
                            }
                            Surface(color = CanonMint, shape = CircleShape) {
                                Icon(Icons.Default.PhoneLocked, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(16.dp))
                            }
                        }
                        TripInfoRow(Icons.Default.LocationOn, appText("Место встречи", "Осрашыу урыны"), ride.pickup.ifBlank { appText("Уточнить у водителя", "Водителдән асыҡларға") })
                        ride.pickupLat?.let { la ->
                            val ln = ride.pickupLng ?: 0.0
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
                        MapPreview(Modifier.height(170.dp), from = ride.from, to = ride.to, distance = cityDistanceText(ride.from, ride.to))
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
                        InfoCard(
                            title = appText("Телефон откроется после подтверждения поездки", "Телефон сәфәр раҫланғандан һуң асыла"),
                            text = "",
                            icon = Icons.Default.Lock
                        )
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
                                modifier = Modifier.weight(1.15f).height(54.dp),
                                shape = RoundedCornerShape(18.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)
                            ) {
                                Icon(Icons.Default.Route, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(appText("Поехать", "Барырға"), fontWeight = FontWeight.Black)
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
                .background(Brush.linearGradient(listOf(CanonGreen, Color(0xFF0E6C3F))), CanonItemShape)
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
            Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = Color.White)
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
    var messages by remember { mutableStateOf<List<MessageDto>>(emptyList()) }
    val voiceScope = rememberCoroutineScope()
    var draft by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var showShare by remember { mutableStateOf(false) }
    val shareSheet = rememberModalBottomSheetState()
    val tripSharedPrefix = appText("Поездка отправлена", "Сәфәр ебәрелде")

    val myId = remember { ApiClient.myUserId() ?: -1 }
    var wsConnected by remember { mutableStateOf(false) }

    // История — по REST (один раз).
    LaunchedEffect(bookingId) {
        bookingId?.let { id -> ApiClient.getMessages(id).onSuccess { messages = it } }
    }

    // Realtime — по WebSocket: входящие добавляем живьём; эхо своего сообщения заменяет оптимистичное.
    val chatSocket = remember(bookingId) {
        bookingId?.let { id ->
            ChatSocket(
                bookingId = id,
                onMessage = { inc ->
                    val optIdx = messages.indexOfFirst { it.id == 0 && it.senderId == myId && it.text == inc.text }
                    messages = when {
                        optIdx >= 0 -> messages.toMutableList().also { it[optIdx] = MessageDto(inc.id, inc.text, inc.senderId) }
                        inc.id > 0 && messages.any { it.id == inc.id } -> messages   // дубль по id — пропустить
                        else -> messages + MessageDto(inc.id, inc.text, inc.senderId)
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

    Scaffold(
        containerColor = CanonBg,
        topBar = { ScreenTopBar(appText("Моя поездка", "Минең сәфәр"), onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 28.dp)
        ) {
            item {
                Card(modifier = Modifier.appearIn(0), colors = CardDefaults.cardColors(containerColor = CanonSurface), shape = CanonCardShape, elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${ride?.from ?: "—"}  →  ${ride?.to ?: "—"}", fontSize = 22.sp, fontWeight = FontWeight.Black)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.DirectionsCar, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(ride?.driver ?: appText("Водитель", "Водитель"), color = CanonMuted)
                            ride?.time?.let { Spacer(Modifier.width(10.dp)); Text(it, color = CanonMuted) }
                        }
                    }
                }
            }
            item { Text(appText("Статус поездки", "Сәфәр хәле"), fontWeight = FontWeight.Bold, modifier = Modifier.appearIn(1)) }
            item {
                Row(modifier = Modifier.appearIn(2), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "sat" to appText("Я сел", "Ултырҙым"),
                        "arrived" to appText("Доехал", "Барып еттем"),
                        "done" to appText("Завершить", "Тамам")
                    ).forEach { (st, label) ->
                        FilledTonalButton(
                            onClick = { status = st; bookingId?.let { ApiClient.fireSetTripStatus(it, st) }; if (st == "done") onTripEnd() },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = if (status == st) CanonMint else CanonSurface,
                                contentColor = CanonText
                            )
                        ) { Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
            item {
                var myStars by remember { mutableStateOf(0) }
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
                                    tint = if (n <= myStars) Color(0xFFE7A921) else CanonBorder,
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clickable {
                                            myStars = n
                                            bookingId?.let { id ->
                                                voiceScope.launch {
                                                    ApiClient.rateBooking(id, n)
                                                        .onSuccess { Toast.makeText(context, thanksMsg, Toast.LENGTH_SHORT).show() }
                                                        .onFailure { Toast.makeText(context, rateFailMsg, Toast.LENGTH_SHORT).show() }
                                                }
                                            }
                                        }
                                )
                            }
                        }
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = CanonSurface), shape = CanonItemShape, elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
                    Row(
                        Modifier.appearIn(3).fillMaxWidth().clickable { showShare = true }.padding(16.dp),
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
            item {
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
            item {
                val voiceSoon = appText("Голос записан", "Тауыш яҙылды")
                ChatComposer(
                    draft = draft,
                    onDraftChange = { draft = it },
                    onSend = {
                        val t = draft.trim()
                        if (t.isNotEmpty() && bookingId != null) {
                            if (wsConnected && chatSocket != null) {
                                messages = messages + MessageDto(0, t, myId)   // оптимистично; эхо WS заменит
                                chatSocket.send(t)
                            } else {
                                ApiClient.fireSendMessage(bookingId, t)         // фоллбэк по REST
                                messages = messages + MessageDto(0, t, -1)
                            }
                            draft = ""
                        }
                    },
                    onVoiceRecorded = { path, _ ->
                        Toast.makeText(context, voiceSoon, Toast.LENGTH_SHORT).show()
                        if (bookingId != null) voiceScope.launch {
                            val bytes = runCatching { File(path).readBytes() }.getOrNull()
                            if (bytes != null) ApiClient.uploadVoice(bytes).onSuccess { url ->
                                ApiClient.sendVoiceMessage(bookingId, url)
                                ApiClient.getMessages(bookingId).onSuccess { messages = it }
                            }
                        }
                    }
                )
            }
            items(messages) { m -> MessageBubble(m.text, m.voiceUrl, mine = m.senderId == myId || m.senderId == -1) }
            item {
                Button(
                    onClick = onSos,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CanonRed)
                ) {
                    Icon(Icons.Default.Sos, contentDescription = null)
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
                            bookingId?.let { ApiClient.fireShareTrip(it, c.id) }
                            showShare = false
                            Toast.makeText(context, "$tripSharedPrefix: ${c.name}", Toast.LENGTH_SHORT).show()
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

@Composable
private fun MessageBubble(text: String, voiceUrl: String?, mine: Boolean) {
    var playing by remember { mutableStateOf(false) }
    val player = remember { mutableStateOf<MediaPlayer?>(null) }
    DisposableEffect(voiceUrl) { onDispose { runCatching { player.value?.release() }; player.value = null } }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Surface(
            color = if (mine) CanonGreen2 else CanonSurface,
            shape = RoundedCornerShape(18.dp),
            shadowElevation = 1.dp
        ) {
            if (voiceUrl != null) {
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
            } else {
                Text(
                    text,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    color = if (mine) Color.White else CanonText,
                    fontSize = 15.sp
                )
            }
        }
    }
}


