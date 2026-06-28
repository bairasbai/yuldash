package com.yuldash.app

// Вкладки Поездки + Заявки + Чат и их карточки. Вынесено из MainActivity (Фаза 2).
// Импорты целиком — лишние = варнинги.

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
import androidx.compose.foundation.verticalScroll
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
    onOpenActiveTrip: (Ride) -> Unit,
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
    val routeAd = ads.forPlacement(AdPlacement.Route).filter { it.matchesRoute("Баймаҡ", "Сибай") }.firstOrNull { it.id == "ad-cafe-route" }
        ?: ads.forPlacement(AdPlacement.Route).firstOrNull { it.matchesRoute("Баймаҡ", "Сибай") }
    val sponsoredAd = ads.forPlacement(AdPlacement.RidesList).firstOrNull { it.id == "ad-service-rides" }
    val inlineAd = routeAd ?: sponsoredAd
    LaunchedEffect(presetTo, presetToday) {
        if (presetTo.isNotBlank() || presetToday) selectedStatus = "active"
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Spacer(Modifier.height(2.dp)) }
            item {
                Text(appText("Мои поездки", "Минең сәфәрҙәр"), color = CanonGreen, fontSize = 28.sp, lineHeight = 30.sp, fontWeight = FontWeight.Black)
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
            if (rides.isEmpty()) {
                item {
                    EmptyStateCard(
                        title = appText("Поездок пока нет", "Әлегә сәфәрҙәр юҡ"),
                        text = appText("Создайте заявку или опубликуйте маршрут водителя.", "Заявка булдырығыҙ йәки водитель маршрутын баҫтырығыҙ."),
                        icon = Icons.Default.Route,
                        action = appText("Создать заявку", "Заявка булдырыу"),
                        onAction = onCreateRequest
                    )
                }
            } else {
                item {
                    Box(Modifier.appearIn(0)) {
                    MyTripCard(
                        ride = rides.first(),
                        status = appText("Подтверждена", "Раҫланды"),
                        statusColor = CanonMint,
                        icon = Icons.Default.DirectionsCar,
                        primaryAction = appText("Подробнее", "Ентекле"),
                        secondaryAction = appText("Связаться", "Бәйләнеү"),
                        onPrimary = { onOpenActiveTrip(rides.first()) },
                        onSecondary = onMessage
                    )
                    }
                }
                item {
                    Box(Modifier.appearIn(1)) {
                    MyTripCard(
                        ride = rides.getOrElse(2) { rides.first() },
                        status = appText("Ожидает", "Көтә"),
                        statusColor = CanonWarnBg,
                        icon = Icons.Default.Schedule,
                        primaryAction = appText("Подробнее", "Ентекле"),
                        secondaryAction = appText("Написать", "Яҙыу"),
                        onPrimary = { onBookRide(rides.getOrElse(2) { rides.first() }) },
                        onSecondary = onMessage
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
                item {
                    Box(Modifier.appearIn(2)) {
                    MyTripCard(
                        ride = Ride(
                            id = "done",
                            from = "Баймаҡ",
                            to = "Сибай",
                            time = "12 мая, 17:40",
                            timeBa = "12 май, 17:40",
                            driver = "Рамиль",
                            car = "Lada Vesta",
                            carBa = "Lada Vesta",
                            price = 300,
                            seats = 2,
                            rating = 5.0,
                            verified = true,
                            boosted = false
                        ),
                        status = appText("Завершена", "Тамамланды"),
                        statusColor = Color(0xFFEDEDED),
                        icon = Icons.Default.CheckCircle,
                        primaryAction = appText("Повторить маршрут", "Маршрутты ҡабатлау"),
                        secondaryAction = appText("Написать", "Яҙыу"),
                        onPrimary = { onCreateRequest() },
                        onSecondary = onMessage
                    )
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
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, if (selected == tab) Color.Transparent else CanonBorder),
                shadowElevation = if (selected == tab) 2.dp else 1.dp
            ) {
                Text(
                    tab,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    color = if (selected == tab) Color.White else CanonMuted,
                    fontWeight = if (selected == tab) FontWeight.Black else FontWeight.Medium,
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
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Surface(color = CanonMint, shape = RoundedCornerShape(16.dp)) {
                    Icon(icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(12.dp).size(24.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            "${ride.from} → ${ride.to}",
                            modifier = Modifier.weight(1f),
                            color = CanonText,
                            fontSize = 18.sp,
                            lineHeight = 20.sp,
                            fontWeight = FontWeight.Black,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Icon(Icons.Default.MoreVert, contentDescription = null, tint = CanonMuted, modifier = Modifier.size(22.dp))
                    }
                    Surface(color = statusColor, shape = RoundedCornerShape(999.dp)) {
                        Row(Modifier.padding(horizontal = 9.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Verified, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(5.dp))
                            Text(status, color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
                        }
                    }
                    DetailMeta(Icons.Default.Schedule, ride.timeText())
                DetailMeta(Icons.Default.Person, "${seatsText(ride.seats)} · ${ride.price} ₽")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onPrimary,
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)
                ) {
                    Text(primaryAction, fontWeight = FontWeight.Black, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (secondaryAction.isNotBlank()) {
                    FilledTonalButton(
                        onClick = onSecondary,
                        modifier = Modifier.weight(1.1f).height(44.dp),
                        shape = RoundedCornerShape(16.dp),
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
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                if (loading) appText("Загрузка…", "Йөкләнә…") else appText("Показать\nещё", "Тағы\nкүрһәтеү"),
                color = CanonGreen, fontWeight = FontWeight.Black, fontSize = 15.sp, lineHeight = 19.sp
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
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${dto.fromCity} → ${dto.toCity}",
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.Black, fontSize = 16.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (dto.category.isNotBlank() && dto.category != "regular") {
                    val (ic, ru, ba) = rideTypeMeta(dto.category)
                    Surface(color = CanonMint, shape = RoundedCornerShape(999.dp)) {
                        Row(Modifier.padding(horizontal = 7.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(ic, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(3.dp))
                            Text(appText(ru, ba), color = CanonGreen2, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                }
                if (dto.driverVerified) {
                    Icon(Icons.Default.Verified, contentDescription = appText("Проверен", "Тикшерелгән"), tint = CanonGreen2, modifier = Modifier.size(18.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Schedule, contentDescription = null, tint = CanonMuted, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text(formatDepart(dto.departAt), color = CanonMuted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                if (soonest) {
                    Spacer(Modifier.width(8.dp))
                    Surface(color = CanonMint, shape = RoundedCornerShape(999.dp)) {
                        Text(
                            appText("ближайшая", "иң яҡыны"),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            color = CanonGreen2, fontSize = 11.sp, fontWeight = FontWeight.Black
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(30.dp).background(CanonMint, CircleShape), contentAlignment = Alignment.Center) {
                    Text(dto.driverName.take(1).uppercase(), fontWeight = FontWeight.Black, fontSize = 13.sp, color = CanonGreen2)
                }
                Spacer(Modifier.width(8.dp))
                Text(dto.driverName.ifBlank { appText("Водитель", "Водитель") }, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Default.Star, contentDescription = null, tint = Color(0xFFE7A921), modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(3.dp))
                Text(dto.driverRating.toString(), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                dto.distanceKm?.let { km ->
                    Icon(Icons.Default.NearMe, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(3.dp))
                    Text(
                        if (km < 1.0) appText("рядом", "янда") else appText("${fmtKm(km)} км", "${fmtKm(km)} км"),
                        fontSize = 13.sp, fontWeight = FontWeight.Bold, color = CanonText
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Text("${dto.price} ₽", color = CanonGreen2, fontWeight = FontWeight.Black, fontSize = 15.sp)
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = onOpen,
                    modifier = Modifier.height(36.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2),
                    contentPadding = PaddingValues(horizontal = 14.dp)
                ) {
                    Text(appText("Поехать", "Барырға"), fontWeight = FontWeight.Black, fontSize = 13.sp)
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
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SkeletonBox(widthFraction = 0.7f, height = 16.dp)
            SkeletonBox(widthFraction = 0.4f, height = 13.dp)
            SkeletonBox(widthFraction = 0.55f, height = 13.dp)
            Spacer(Modifier.weight(1f))
            SkeletonBox(widthFraction = 0.5f, height = 34.dp, shape = RoundedCornerShape(12.dp))
        }
    }
}

@Composable
internal fun NearbyEmptyCard(hasRoute: Boolean, onRetry: () -> Unit, error: Boolean = false) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = CanonItemShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
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
                fontWeight = FontWeight.Bold, fontSize = 15.sp
            )
            Text(
                if (error) appText("Проверь интернет и повтори", "Интернетты тикшереп ҡабатла")
                else appText("Появятся — покажем здесь", "Барлыҡҡа килһә — бында күрһәтәбеҙ"),
                color = CanonMuted, fontSize = 13.sp
            )
            TextButton(onClick = onRetry) {
                Text(if (error) appText("Повторить", "Ҡабатлау") else appText("Обновить", "Яңыртыу"), color = CanonGreen2, fontWeight = FontWeight.Black)
            }
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
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${ride.from} → ${ride.to}",
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.Black,
                    fontSize = if (compact) 17.sp else 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (ride.verified) {
                    VerifiedBadge()
                } else if (ride.boosted) {
                    BoostBadge()
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(5.dp))
                Text(ride.timeText(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                    .background(CanonMint, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(ride.driver.first().toString(), fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(ride.driver, fontWeight = FontWeight.Bold)
                        if (ride.verified) {
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Default.Verified, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(16.dp))
                        }
                    }
                    Text(ride.carText(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Default.Star, contentDescription = null, tint = Color(0xFFE7A921), modifier = Modifier.size(18.dp))
                Text(ride.rating.toString())
            }
            if (compact) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Text("${seatsText(ride.seats)} · ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${ride.price} ₽", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = onBook,
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(appText("Подробнее", "Ентекле"))
                    }
                    Button(
                        onClick = onBook,
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0B6B3A))
                    ) {
                        Text(appText("Поехать", "Барырға"), fontWeight = FontWeight.Black)
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Metric(Icons.Default.Payments, "${ride.price} ₽", Modifier.weight(1f))
            Metric(Icons.Default.EventSeat, seatsText(ride.seats), Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onBook,
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(appText("Забронировать", "Бронләү"))
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
        Row(modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Verified, contentDescription = null, modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(4.dp))
            Text(appText("Проверен", "Тикшерелгән"), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
    }
}

@Composable
private fun BoostBadge() {
    Surface(
        color = CanonYellow,
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, Color(0xFFE2A11B).copy(alpha = 0.25f))
    ) {
        Row(modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
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
        Row(modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(5.dp))
            Text(label, color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1)
        }
    }
}

// Лента чипов с условиями поездки (показывается только если есть хоть одно).
@Composable
private fun RidePrefChips(ride: Ride, modifier: Modifier = Modifier) {
    if (!(ride.petsAllowed || ride.childSeat || ride.womenOnly || ride.smoking || ride.baggage || ride.airConditioner)) return
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (ride.womenOnly) PrefChip(Icons.Default.Woman, appText("Только женщины", "Тик ҡатын-ҡыҙ"))
        if (ride.childSeat) PrefChip(Icons.Default.ChildCare, appText("Детское кресло", "Балалар ултырғысы"))
        if (ride.petsAllowed) PrefChip(Icons.Default.Pets, appText("С животным", "Хайуан менән"))
        if (ride.baggage) PrefChip(Icons.Default.Luggage, appText("Багаж", "Багаж"))
        if (ride.airConditioner) PrefChip(Icons.Default.AcUnit, appText("Кондиционер", "Кондиционер"))
        if (ride.smoking) PrefChip(Icons.Default.SmokingRooms, appText("Можно курить", "Тартырға ярай"))
    }
}

// Строка-тумблер условия поездки (для экрана «Создать поездку»).
@Composable
internal fun PrefToggleRow(icon: ImageVector, label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(color = CanonMint, shape = RoundedCornerShape(12.dp)) {
            Icon(icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(9.dp).size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(label, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

// Чип-фильтр «Ближайших» — переключаемый (зелёный = активен).
@Composable
internal fun NearbyFilterChip(icon: ImageVector, label: String, active: Boolean, onToggle: () -> Unit) {
    Surface(
        modifier = Modifier.bounceClick(onToggle),
        color = if (active) CanonGreen2 else CanonSurface,
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, if (active) Color.Transparent else CanonBorder)
    ) {
        Row(modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = if (active) Color.White else CanonGreen2, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(5.dp))
            Text(label, color = if (active) Color.White else CanonText, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
        }
    }
}

@Composable
private fun Metric(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = CanonSurface,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
internal fun MyRequestsScreen(requests: List<LocalRequest>, onCreateNew: () -> Unit, onViewResponses: () -> Unit) {
    val activeLabel = appText("Мои заявки", "Минең заявкалар")
    val responsesLabel = appText("Отклики", "Яуаптар")
    val draftsLabel = appText("Черновики", "Черновиктар")
    var selectedTab by remember { mutableStateOf("active") }
    val selectedLabel = when (selectedTab) {
        "drafts" -> draftsLabel
        "responses" -> responsesLabel
        else -> activeLabel
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 118.dp)
    ) {
        item { Spacer(Modifier.height(2.dp)) }
        item {
            Text(appText("Мои заявки", "Минең заявкалар"), color = CanonGreen, fontSize = 28.sp, lineHeight = 30.sp, fontWeight = FontWeight.Black)
        }
        item {
            SegmentedTabs(
                tabs = listOf(activeLabel, responsesLabel, draftsLabel),
                selected = selectedLabel,
                onSelect = {
                    selectedTab = when (it) {
                        responsesLabel -> "responses"
                        draftsLabel -> "drafts"
                        else -> "active"
                    }
                }
            )
        }
        if (selectedTab == "active") {
            if (requests.isEmpty()) {
                item {
                    Box(Modifier.appearIn(0)) {
                        InfoCard(
                            title = appText("Заявок пока нет", "Әлегә заявкалар юҡ"),
                            text = appText("Создайте заявку — водители увидят её и откликнутся.", "Заявка булдырығыҙ — водителдәр уны күреп яуап бирер."),
                            icon = Icons.Default.AddBox
                        )
                    }
                }
            } else {
                items(requests, key = { it.route + it.time + it.title }) { req ->
                    Box(Modifier.appearIn(0)) {
                        RequestSummaryCard(
                            icon = if (req.title.contains("больниц", ignoreCase = true)) Icons.Default.LocalHospital else Icons.Default.DirectionsCar,
                            from = req.route.substringBefore(" → "),
                            to = req.route.substringAfter(" → "),
                            date = req.time,
                            reason = req.title,
                            price = if (req.price > 0) appText("${req.price} ₽ предлагаю", "${req.price} ₽ тәҡдим итәм") else appText("цена договорная", "хаҡ килешеү буйынса"),
                            badge = req.status,
                            action = appText("Посмотреть отклики", "Яуаптарҙы ҡарау"),
                            onAction = onViewResponses
                        )
                    }
                }
            }
        } else if (selectedTab == "responses") {
            item {
                InfoCard(
                    title = appText("Отклики появятся здесь", "Яуаптар бында күренер"),
                    text = appText("Когда водитель ответит на вашу заявку, карточка появится в этом списке.", "Водитель заявкаға яуап бирһә, карточка ошо исемлектә күренер."),
                    icon = Icons.Default.ChatBubble
                )
            }
        } else if (selectedTab == "drafts") {
            item {
                Box(Modifier.appearIn(0)) { DraftRequestCard() }
            }
        }
        item {
            Button(
                onClick = onCreateNew,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)
            ) {
                Icon(Icons.Default.AddBox, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(appText("Создать новую", "Яңыһын булдырыу"), fontWeight = FontWeight.Black, fontSize = 17.sp)
            }
        }
    }
}

@Composable
private fun RequestSummaryCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    from: String,
    to: String,
    date: String,
    reason: String,
    price: String,
    badge: String,
    action: String,
    onAction: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = CanonSurface), shape = CanonCardShape, elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Surface(color = CanonMint, shape = RoundedCornerShape(16.dp)) {
                    Icon(icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(12.dp).size(24.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            "$from  →  $to",
                            modifier = Modifier.weight(1f),
                            color = CanonText,
                            fontSize = 18.sp,
                            lineHeight = 20.sp,
                            fontWeight = FontWeight.Black,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.width(8.dp))
                        Surface(color = CanonMint, shape = RoundedCornerShape(999.dp)) {
                            Text(badge, modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp), color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
                        }
                    }
                    DetailMeta(Icons.Default.CalendarMonth, date)
                    DetailMeta(Icons.Default.AddBox, reason)
                    DetailMeta(Icons.Default.Payments, appText("$price ₽ предлагаю", "$price ₽ тәҡдим итәм"))
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(CanonBorder))
            OutlinedButton(
                onClick = onAction,
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, CanonGreen2)
            ) {
                Icon(Icons.Default.ChatBubble, contentDescription = null, tint = CanonGreen2)
                Spacer(Modifier.width(8.dp))
                Text(action, color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.weight(1f))
                Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = CanonGreen2)
            }
        }
    }
}

@Composable
private fun DraftRequestCard() {
    Surface(
        color = Color.White.copy(alpha = 0.84f),
        shape = CanonItemShape,
        border = BorderStroke(1.dp, Color(0x2235A363))
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = CanonMint, shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Default.DirectionsCar, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(11.dp).size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(appText("Баймак → Уфа", "Баймаҡ → Өфө"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(appText("18 мая, в 10:00", "18 май, 10:00"), color = CanonMuted, fontSize = 13.sp)
                Text(appText("450 ₽ предлагаю", "450 ₽ тәҡдим итәм"), color = CanonMuted, fontSize = 13.sp)
            }
            Surface(color = CanonWarnBg, shape = RoundedCornerShape(999.dp)) {
                Text(appText("Черновик", "Черновик"), modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = CanonWarn, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = CanonMuted)
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
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .bounceClick(onBook),
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = CanonItemShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(color = CanonMint, shape = RoundedCornerShape(18.dp)) {
                    Icon(
                        if (isHospital) Icons.Default.LocalHospital else Icons.Default.DirectionsCar,
                        contentDescription = null,
                        modifier = Modifier.padding(14.dp).size(28.dp),
                        tint = CanonGreen2
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isHospital) {
                            Surface(color = CanonMint, shape = RoundedCornerShape(999.dp)) {
                                Text(
                                    appText("В больницу", "Больницаға"),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    color = CanonGreen2,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                        }
                        if (ride.boosted) {
                            BoostBadge()
                            Spacer(Modifier.width(6.dp))
                        }
                        if (ride.verified) {
                            VerifiedBadge()
                        }
                    }
                    Text(
                        "${ride.from} → ${ride.to}",
                        color = CanonText,
                        fontWeight = FontWeight.Black,
                        fontSize = 20.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Schedule, contentDescription = null, tint = CanonMuted, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(ride.timeText(), color = CanonMuted, fontSize = 14.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Person, contentDescription = null, tint = CanonMuted, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(seatsText(ride.seats), color = CanonMuted, fontSize = 14.sp)
                        Text(" · ", color = CanonMuted, fontSize = 14.sp)
                        Text("${ride.price} ₽", color = CanonGreen2, fontWeight = FontWeight.Black, fontSize = 14.sp)
                    }
                }
            }
            RidePrefChips(ride)
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onBook,
                    modifier = Modifier.weight(1f).height(42.dp),
                    shape = RoundedCornerShape(15.dp),
                    border = BorderStroke(1.dp, CanonBorder)
                ) {
                    Text(appText("Подробнее", "Ентекле"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
                }
                Button(
                    onClick = onBook,
                    modifier = Modifier.weight(1f).height(42.dp),
                    shape = RoundedCornerShape(15.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)
                ) {
                    Text(appText("Поехать", "Барырға"), fontWeight = FontWeight.Black, fontSize = 13.sp, maxLines = 1)
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
    var voiceSent by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var latestBookingId by remember { mutableStateOf<Int?>(null) }
    var conversations by remember { mutableStateOf<List<ConversationDto>>(emptyList()) }
    var convLoading by remember { mutableStateOf(true) }
    var convError by remember { mutableStateOf(false) }
    var convReload by remember { mutableStateOf(0) }
    var myRequests by remember { mutableStateOf<List<RequestDto>>(emptyList()) }
    val chatTabs = listOf(
        "active" to LocalizedText("Активные", "Актив"),
        "requests" to LocalizedText("Заявки", "Заявкалар"),
        "system" to LocalizedText("Система", "Система")
    )
    val nowText = appText("сейчас", "хәҙер")
    val chatScope = rememberCoroutineScope()
    LaunchedEffect(convReload) {
        ApiClient.getMyBookings().onSuccess { latestBookingId = it.maxOrNull() }
        convLoading = true
        ApiClient.getConversations()
            .onSuccess { conversations = it; convError = false }
            // 401 / нет сессии — это НЕ сетевая ошибка: диалогов просто нет, показываем дружелюбное «пусто».
            // Реальная ошибка (нет сети, 5xx) → convError=true → «Повторить».
            .onFailure { e -> convError = (e as? ApiException)?.status != 401 }
        convLoading = false
        ApiClient.getMyRequests().onSuccess { myRequests = it }
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { Spacer(Modifier.height(10.dp)) }
        item {
            Text(appText("Чат", "Чат"), color = CanonGreen, fontSize = 29.sp, lineHeight = 31.sp, fontWeight = FontWeight.Black)
            Text(
                appText("Общайтесь по активным поездкам и заявкам", "Актив сәфәрҙәр һәм заявкалар буйынса аралашығыҙ"),
                color = CanonMuted,
                fontSize = 14.sp,
                lineHeight = 19.sp
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
                    FilledTonalButton(
                        onClick = {
                            if (key == "system") onNotifications() else selected = key
                        },
                        modifier = Modifier.weight(1f).height(50.dp),
                        shape = RoundedCornerShape(18.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = if (selected == key) CanonMint else CanonSurface,
                            contentColor = CanonText
                        )
                    ) {
                        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = if (selected == key) CanonGreen2 else CanonMuted)
                        Spacer(Modifier.width(5.dp))
                        Text(labelText, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (selected == "active") {
            // Композер — только на «Активные» и шлёт по активной поездке (последняя бронь), с явной подписью.
            latestBookingId?.let {
                item { Text(appText("Сообщение по активной поездке", "Актив сәфәр буйынса хәбәр"), color = CanonMuted, fontSize = 12.sp) }
            }
            item {
                ChatComposer(
                    draft = draft,
                    onDraftChange = { draft = it },
                    onSend = {
                        val text = draft.trim()
                        if (text.isNotEmpty()) {
                            latestBookingId?.let { ApiClient.fireSendMessage(it, text) }
                            onAddVoiceMessage(LocalVoiceMessage(ApiClient.cachedName() ?: "Я", text, nowText))
                            draft = ""
                        }
                    },
                    onVoiceRecorded = { path, dur ->
                        onAddVoiceMessage(LocalVoiceMessage(ApiClient.cachedName() ?: "Я", "", nowText, audioPath = path, durationSec = dur))
                        voiceSent = true
                        // Реально шлём голос на сервер по активной брони (раньше оставался только локально).
                        latestBookingId?.let { bid ->
                            chatScope.launch {
                                val bytes = runCatching { java.io.File(path).readBytes() }.getOrNull()
                                if (bytes != null) ApiClient.uploadVoice(bytes).onSuccess { url -> ApiClient.sendVoiceMessage(bid, url) }
                            }
                        }
                    },
                    onPhotoPicked = { bytes ->
                        latestBookingId?.let { bid ->
                            chatScope.launch {
                                ApiClient.uploadChatPhoto(bytes).onSuccess { url -> ApiClient.sendPhotoMessage(bid, url) }
                            }
                        }
                    }
                )
            }
        }
        if (selected == "requests") {
            // Вкладка «Заявки» — реальные заявки пользователя (ждут отклика водителя).
            if (myRequests.isEmpty()) {
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
                    Box(Modifier.appearIn(i)) {
                        ChatCard(
                            initial = r.fromCity.firstOrNull()?.uppercase() ?: "З",
                            name = "${r.fromCity} → ${r.toCity}",
                            subtitle = appText("Заявка · ${r.seats} мест", "Заявка · ${r.seats} урын"),
                            message = appText("Смотреть отклики водителей", "Водитель яуаптарын ҡарау"),
                            time = "",
                            unread = 0,
                            verified = false,
                            onClick = { onOpenResponses(r.id) }
                        )
                    }
                }
            }
        } else {
            // Вкладка «Активные» — чаты по поездкам + записанные голосовые.
            items(voiceMessages, key = { it.audioPath ?: (it.author + it.time + it.transcript) }) { message ->
                VoiceMessageCard(message)
            }
            if (conversations.isNotEmpty()) {
                itemsIndexed(conversations, key = { _, c -> c.bookingId }) { i, c ->
                    Box(Modifier.appearIn(i)) {
                        ChatCard(
                            initial = c.peerName.take(1).uppercase(),
                            name = c.peerName,
                            subtitle = c.route,
                            message = c.lastMessage,
                            time = "",
                            unread = 0,
                            verified = true,
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

/** Лента заявок пассажиров — водитель откликается (цена/коммент). */
@Composable
internal fun RequestsFeedScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var feed by remember { mutableStateOf<List<com.yuldash.app.data.RequestFeedDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var target by remember { mutableStateOf<com.yuldash.app.data.RequestFeedDto?>(null) }
    var price by remember { mutableStateOf("") }
    var comment by remember { mutableStateOf("") }
    val sentMsg = appText("Отклик отправлен", "Яуап ебәрелде")
    fun reload() { scope.launch { ApiClient.getRequestsFeed().onSuccess { feed = it }; loading = false } }
    LaunchedEffect(Unit) { reload() }
    target?.let { t ->
        AlertDialog(
            onDismissRequest = { target = null },
            containerColor = CanonSurface,
            title = { Text("${t.from} → ${t.to}", color = CanonText, fontWeight = FontWeight.Black, fontSize = 17.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(price, { price = it.filter { c -> c.isDigit() }.take(6) }, label = { Text(appText("Цена, ₽", "Хаҡ, ₽")) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(comment, { comment = it }, label = { Text(appText("Когда едете / детали", "Ҡасан / детальдәр")) }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val rid = t.id; val p = price.toIntOrNull() ?: 0; val c = comment.trim()
                    scope.launch { ApiClient.respondToRequest(rid, p, c).onSuccess { Toast.makeText(ctx, sentMsg, Toast.LENGTH_SHORT).show(); reload() } }
                    target = null; price = ""; comment = ""
                }) { Text(appText("Отправить", "Ебәреү"), color = CanonGreen2, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { target = null }) { Text(appText("Отмена", "Баш тартыу"), color = CanonMuted) } },
        )
    }
    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Заявки пассажиров", "Пассажир заявкалары"), onBack) }) { padding ->
        LazyColumn(Modifier.padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
            item { Text(appText("Пассажиры ищут поездку. Откликнись — предложи цену и время.", "Пассажирҙар сәфәр эҙләй. Яуап бир — хаҡ һәм ваҡыт тәҡдим ит."), color = CanonMuted, fontSize = 14.sp, lineHeight = 19.sp) }
            if (loading) {
                item { Text(appText("Загрузка…", "Йөкләнә…"), color = CanonMuted) }
            } else if (feed.isEmpty()) {
                item { ListedEmpty(appText("Заявок пока нет", "Әлегә заявкалар юҡ"), appText("Здесь появятся заявки пассажиров.", "Бында пассажир заявкалары күренер")) }
            } else {
                items(feed.size) { i ->
                    val r = feed[i]
                    Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${r.from} → ${r.to}", color = CanonText, fontWeight = FontWeight.Black, fontSize = 17.sp)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SmallAvatar(r.passengerAvatar, r.passengerName, 34)
                                Spacer(Modifier.width(8.dp))
                                Text("${r.passengerName} · " + appText("${r.seats} мест", "${r.seats} урын"), color = CanonMuted, fontSize = 13.sp)
                            }
                            if (r.comment.isNotBlank()) Text(r.comment, color = CanonMuted, fontSize = 14.sp)
                            if (r.responded) Text(appText("Вы откликнулись", "Яуап бирҙегеҙ"), color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            else Button(onClick = { target = r; price = ""; comment = "" }, modifier = Modifier.align(Alignment.End), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)) { Text(appText("Предложить поездку", "Сәфәр тәҡдим итеү"), fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }
        }
    }
}

/** Отклики водителей на МОЮ заявку — пассажир выбирает → поездка+чат. */
@Composable
internal fun ResponsesScreen(requestId: Int, onBack: () -> Unit, onAccepted: (Int) -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var resps by remember { mutableStateOf<List<com.yuldash.app.data.ResponseDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var accepting by remember { mutableStateOf(false) }
    val failMsg = appText("Не получилось принять", "Ҡабул итеп булманы")
    LaunchedEffect(requestId) { ApiClient.getRequestResponses(requestId).onSuccess { resps = it }; loading = false }
    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Отклики водителей", "Водитель яуаптары"), onBack) }) { padding ->
        LazyColumn(Modifier.padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
            item { Text(appText("Выберите водителя — поездка начнётся, откроется чат.", "Водитель һайла — сәфәр башлана, чат асыла."), color = CanonMuted, fontSize = 14.sp, lineHeight = 19.sp) }
            if (loading) {
                item { Text(appText("Загрузка…", "Йөкләнә…"), color = CanonMuted) }
            } else if (resps.isEmpty()) {
                item { ListedEmpty(appText("Откликов пока нет", "Әлегә яуап юҡ"), appText("Водители ещё не откликнулись. Загляни позже.", "Водителдәр яуап бирмәгән. Һуңыраҡ кер.")) }
            } else {
                items(resps.size) { i ->
                    val r = resps[i]
                    Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SmallAvatar(r.driverAvatar, r.driverName, 42)
                                Spacer(Modifier.width(10.dp))
                                Text(r.driverName, color = CanonText, fontWeight = FontWeight.Black, fontSize = 16.sp)
                                r.driverRating?.let { Spacer(Modifier.width(6.dp)); Icon(Icons.Default.Star, contentDescription = null, tint = Color(0xFFE7A921), modifier = Modifier.size(14.dp)); Text(" $it", color = CanonMuted, fontSize = 13.sp) }
                                Spacer(Modifier.weight(1f))
                                if (r.price > 0) Text("${r.price} ₽", color = CanonGreen2, fontWeight = FontWeight.Black, fontSize = 18.sp)
                            }
                            if (r.comment.isNotBlank()) Text(r.comment, color = CanonMuted, fontSize = 14.sp)
                            Button(
                                onClick = {
                                    if (accepting) return@Button
                                    accepting = true; val id = r.id
                                    scope.launch {
                                        ApiClient.acceptResponse(id)
                                            .onSuccess { bid -> onAccepted(bid) }
                                            .onFailure { Toast.makeText(ctx, failMsg, Toast.LENGTH_SHORT).show(); accepting = false }
                                    }
                                },
                                enabled = !accepting,
                                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)
                            ) { Text(appText("Поехать с этим водителем", "Был водитель менән барырға"), fontWeight = FontWeight.Black) }
                        }
                    }
                }
            }
        }
    }
}

/** Кружок-аватар: фото (Coil) или буква имени. Для карточек выбора попутчика. */
@Composable
internal fun SmallAvatar(url: String, initial: String, size: Int = 44) {
    Box(Modifier.size(size.dp).background(CanonMint, CircleShape), contentAlignment = Alignment.Center) {
        if (url.isBlank()) {
            Text(initial.take(1).uppercase().ifBlank { "?" }, color = CanonGreen2, fontWeight = FontWeight.Black, fontSize = (size / 2.6f).sp)
        } else {
            coil.compose.AsyncImage(model = url, contentDescription = null, modifier = Modifier.size(size.dp).clip(CircleShape), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        }
    }
}

@Composable
internal fun ListedEmpty(title: String, subtitle: String) {
    Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Default.ListAlt, contentDescription = null, tint = CanonMuted, modifier = Modifier.size(34.dp))
            Text(title, color = CanonText, fontWeight = FontWeight.Black)
            Text(subtitle, color = CanonMuted, fontSize = 13.sp, lineHeight = 17.sp)
        }
    }
}

@Composable
private fun ChatCard(initial: String, name: String, subtitle: String, message: String, time: String, unread: Int, verified: Boolean, support: Boolean = false, onClick: (() -> Unit)? = null, avatarUrl: String = "") {
    Card(
        modifier = if (onClick != null) Modifier.bounceClick(onClick) else Modifier,
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .background(if (support) Color(0xFF0B6B3A) else MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (avatarUrl.isBlank()) {
                    Text(initial, fontSize = 20.sp, fontWeight = FontWeight.Black, color = if (support) Color.White else MaterialTheme.colorScheme.primary)
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
                    Text(name, fontSize = 18.sp, lineHeight = 20.sp, fontWeight = FontWeight.Black)
                    if (verified) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Default.Verified, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    }
                }
                Text(subtitle, fontSize = 15.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold)
                Text(message, color = CanonMuted, fontSize = 14.sp, lineHeight = 18.sp)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(time, color = CanonMuted, fontSize = 13.sp)
                if (unread > 0) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(unread.toString(), color = Color.White, fontWeight = FontWeight.Black)
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
    onPhotoPicked: (ByteArray) -> Unit = {}
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
    AnimatedVisibility(visible = showEmoji && !recording) {
        EmojiPicker(onPick = { e -> onDraftChange(draft + e) })
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = RoundedCornerShape(26.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
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
                IconButton(onClick = { photoPicker.launch("image/*") }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.Add, contentDescription = appText("Прикрепить фото", "Фото беркетеү"), tint = CanonGreen2)
                }
                // Пилюля: эмодзи + поле ввода
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .background(CanonMint, RoundedCornerShape(22.dp))
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            showEmoji = !showEmoji
                            if (showEmoji) focusManager.clearFocus()   // прячем системную клавиатуру → видна наша панель
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.EmojiEmotions, contentDescription = appText("Эмодзи", "Эмодзи"), tint = if (showEmoji) CanonGreen2 else CanonMuted, modifier = Modifier.size(22.dp))
                    }
                    Box(modifier = Modifier.weight(1f).padding(horizontal = 4.dp, vertical = 12.dp)) {
                        if (draft.isBlank()) Text(appText("Сообщение", "Хәбәр"), color = CanonMuted, fontSize = 15.sp)
                        BasicTextField(
                            value = draft,
                            onValueChange = onDraftChange,
                            modifier = Modifier.fillMaxWidth().focusRequester(focus)
                                .onFocusChanged { if (it.isFocused) showEmoji = false },
                            textStyle = TextStyle(color = CanonText, fontSize = 15.sp),
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

// Дружелюбная заглушка «нет диалогов» — полноценная, сбалансированная (не «половина пустая»).
@Composable
private fun ChatEmptyState() {
    Card(
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = CanonCardShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = BorderStroke(1.dp, CanonBorder)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(color = CanonMint, shape = CircleShape) {
                Icon(
                    Icons.Default.ChatBubbleOutline,
                    contentDescription = null,
                    tint = CanonGreen2,
                    modifier = Modifier.padding(22.dp).size(38.dp)
                )
            }
            Text(
                appText("Здесь будут ваши чаты", "Бында чаттарығыҙ булыр"),
                color = CanonText, fontWeight = FontWeight.Black, fontSize = 19.sp, textAlign = TextAlign.Center
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
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("💬", fontSize = 18.sp)
                    Text(
                        appText("Например: «Я на остановке у рынка»", "Мәҫәлән: «Мин баҙар туҡталышында»"),
                        color = CanonGreen, fontSize = 13.sp, fontWeight = FontWeight.Medium
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
private fun EmojiPicker(onPick: (String) -> Unit) {
    Surface(
        color = CanonSurface,
        shape = RoundedCornerShape(20.dp),
        shadowElevation = 1.dp,
        border = BorderStroke(1.dp, CanonBorder)
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 220.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
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
    Card(colors = CardDefaults.cardColors(containerColor = CanonSurface), shape = CanonItemShape, elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
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
                Text(appText("Голосовое от ${message.author}", "Тауыш хәбәр: ${message.author}"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 15.sp)
                if (message.audioPath != null) {
                    Text(appText("${message.durationSec} сек · нажмите ▶", "${message.durationSec} сек · ▶ баҫығыҙ"), color = CanonMuted, fontSize = 13.sp)
                } else {
                    Text(message.transcript, color = CanonText, fontSize = 14.sp, lineHeight = 18.sp)
                    Text(appText("Расшифровка для водителя", "Водитель өсөн текст"), color = CanonMuted, fontSize = 12.sp)
                }
            }
            Text(message.time, color = CanonMuted, fontSize = 12.sp)
        }
    }
}
