package com.yuldash.app

// Вторичные экраны: Уведомления, Безопасность, Настройки, Помощь. Вынесено из MainActivity (Фаза 1).
// Импорты скопированы целиком — лишние = варнинги.

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
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.LocalTaxi
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.AddRoad
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Loyalty
import androidx.compose.material.icons.filled.Storefront
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
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.DeliveryDining
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.FormatSize
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
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.MoneyOff
import androidx.compose.material.icons.filled.MoodBad
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.ChevronRight
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.yuldash.app.data.NotifFeed
import com.yuldash.app.data.AdDto
import com.yuldash.app.ui.theme.YuldashTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NotificationsScreen(
    onBack: () -> Unit,
    onSelectTab: (HomeTab) -> Unit,
    onOpenBooking: (Int) -> Unit = {},
    onOpenResponses: (Int) -> Unit = {},
    onRouteWatches: () -> Unit = {},
    onOpenSupport: (Int) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf("all") }
    val allLabel = appText("Все", "Бөтәһе")
    val tripsLabel = appText("Поездки", "Сәфәрҙәр")
    val chatLabel = appText("Сообщения", "Хәбәрҙәр")
    // Таба «Система» убрана: сервер таких уведомлений не шлёт (все события — booking/ride/message),
    // поэтому она всегда была пустой. Оставили только реально наполняемые вкладки.
    val selectedLabel = when (selected) {
        "trips" -> tripsLabel
        "chat" -> chatLabel
        else -> allLabel
    }

    var feed by remember { mutableStateOf(NotifFeed(0, emptyList())) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(reload) {
        loading = true
        ApiClient.getNotifications()
            .onSuccess { feed = it; error = false }
            // 401 / нет сессии — не сетевая ошибка: событий просто нет, показываем дружелюбное «пусто».
            .onFailure { e -> error = (e as? ApiException)?.status != 401 }
        loading = false
    }

    // Локальная пометка прочитанным (мгновенно в UI) + запрос на сервер. Не блокирует навигацию.
    fun markRead(id: Int) {
        if (feed.items.none { it.id == id && !it.read }) return
        feed = feed.copy(
            unread = (feed.unread - 1).coerceAtLeast(0),
            items = feed.items.map { if (it.id == id) it.copy(read = true) else it },
        )
        scope.launch { ApiClient.markNotificationsRead(id) }
    }
    fun markAll() {
        if (feed.unread == 0) return
        feed = feed.copy(unread = 0, items = feed.items.map { it.copy(read = true) })
        scope.launch { ApiClient.markNotificationsRead(null) }
    }
    fun openDeepLink(n: NotifDto) {
        markRead(n.id)
        val ref = n.refId ?: return
        when (n.refKind) {
            "booking" -> onOpenBooking(ref)
            "request" -> onOpenResponses(ref)
            "support" -> onOpenSupport(ref)
        }
    }

    val visible = feed.items.filter { n ->
        when (selected) {
            "trips" -> n.type == "booking" || n.type == "ride"
            "chat" -> n.type == "message"
            else -> true
        }
    }

    Scaffold(
        containerColor = CanonBg,
        bottomBar = { YuldashBottomBar(selectedTab = HomeTab.Chat, onSelect = onSelectTab) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 18.dp)
        ) {
            item { Spacer(Modifier.height(10.dp)) }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(appText("Уведомления", "Хәбәрҙәр"), color = CanonGreen, fontSize = 34.sp, lineHeight = 36.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                    // «Прочитать всё» — только когда есть непрочитанные (тач-цель 48dp через padding).
                    AnimatedVisibility(visible = feed.unread > 0) {
                        Text(
                            appText("Прочитать всё", "Барыһын да уҡыу"),
                            color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                            modifier = Modifier.bounceClick { markAll() }.padding(horizontal = 8.dp, vertical = 12.dp)
                        )
                    }
                }
            }
            item {
                // F13: вход в «Мои подписки» на маршрут — карауль поездку.
                AppCard(onClick = onRouteWatches) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = CanonMint, shape = CircleShape) {
                            Icon(Icons.Default.NotificationsActive, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(12.dp).size(22.dp))
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(appText("Мои подписки на маршрут", "Маршрут яҙылыуҙарым"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 17.sp)
                            Text(appText("Караулим поездку и сообщим первыми", "Сәфәрҙе күҙәтеп, беренсе булып хәбәр итәбеҙ"), color = CanonMuted, fontSize = 13.sp)
                        }
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = CanonMuted)
                    }
                }
            }
            item {
                SegmentedTabs(
                    listOf(allLabel, tripsLabel, chatLabel),
                    selectedLabel,
                    onSelect = {
                        selected = when (it) {
                            tripsLabel -> "trips"
                            chatLabel -> "chat"
                            else -> "all"
                        }
                    }
                )
            }
            when {
                loading && feed.items.isEmpty() -> {
                    items(4) { SkeletonCard(lines = 2, modifier = Modifier.padding(vertical = 2.dp)) }
                }
                error && feed.items.isEmpty() -> {
                    item { AppErrorState(onRetry = { reload++ }) }
                }
                visible.isEmpty() -> {
                    item {
                        AppEmptyState(
                            title = appText("Уведомлений пока нет", "Хәбәрҙәр әлегә юҡ"),
                            text = appText("Новые события по броням, поездкам и сообщениям появятся здесь.", "Броньдар, сәфәрҙәр һәм хәбәрҙәр буйынса яңы ваҡиғалар бында күренә."),
                            icon = Icons.Default.Notifications,
                        )
                    }
                }
                else -> {
                    itemsIndexed(visible, key = { _, n -> n.id }) { i, n ->
                        Box(Modifier.appearIn(i)) {
                            NotificationRow(notif = n, onClick = { openDeepLink(n) })
                        }
                    }
                }
            }
        }
    }
}

/** Иконка типа уведомления (в едином стиле, без новых сущностей). */
private fun notifIcon(type: String): androidx.compose.ui.graphics.vector.ImageVector = when (type) {
    "booking" -> Icons.Default.EventSeat
    "ride" -> Icons.Default.DirectionsCar
    "message" -> Icons.Default.ChatBubble
    else -> Icons.Default.Notifications
}

/** Относительное время события (двуязычно). created_at — наивный UTC ISO с бэкенда. */
@Composable
private fun notifTimeAgo(iso: String): String {
    val ms = remember(iso) {
        runCatching { java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli() }
            .getOrElse { runCatching { java.time.LocalDateTime.parse(iso).toInstant(java.time.ZoneOffset.UTC).toEpochMilli() }.getOrNull() }
    } ?: return ""
    val mins = ((System.currentTimeMillis() - ms) / 60000L).coerceAtLeast(0)
    return when {
        mins < 1 -> appText("только что", "хәҙер генә")
        mins < 60 -> appText("$mins мин", "$mins мин")
        mins < 1440 -> appText("${mins / 60} ч", "${mins / 60} сәғ")
        else -> appText("${mins / 1440} дн", "${mins / 1440} көн")
    }
}

@Composable
internal fun NotificationRow(notif: NotifDto, onClick: () -> Unit) {
    val isBa = LocalAppLanguage.current == AppLanguage.Ba
    val title = (if (isBa) notif.titleBa else notif.titleRu).ifBlank { notif.titleRu }
    val subtitle = (if (isBa) notif.bodyBa else notif.bodyRu).ifBlank { notif.bodyRu }
    val time = notifTimeAgo(notif.createdAt)
    // Непрочитанное — чуть плотнее (мятная подложка), прочитанное — спокойный фон.
    val bg = if (notif.read) CanonSurface else CanonMint
    Card(
        modifier = Modifier.fillMaxWidth().bounceClick(onClick),
        colors = CardDefaults.cardColors(containerColor = bg),
        shape = CanonItemShape,
        elevation = CardDefaults.cardElevation(defaultElevation = if (notif.read) 1.dp else 2.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = if (notif.read) CanonMint else CanonSurface, shape = CircleShape) {
                Icon(notifIcon(notif.type), contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(14.dp).size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title, color = CanonText, fontWeight = FontWeight.Black, fontSize = 17.sp, lineHeight = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotBlank()) {
                    Text(subtitle, color = CanonMuted, fontSize = 14.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (time.isNotBlank()) Text(time, color = CanonMuted, fontSize = 13.sp, maxLines = 1)
                if (!notif.read) Box(Modifier.size(9.dp).background(CanonGreen2, CircleShape))
            }
        }
    }
}

/**
 * F13 «Мои подписки» — подписка на маршрут «карауль поездку» (retention-двигатель).
 * Форма подписки (откуда/куда + туда-обратно) + список активных подписок с удалением.
 * Все состояния: загрузка / ошибка+повтор / пусто / список. Двуязычно.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RouteWatchesScreen(
    onBack: () -> Unit,
    prefillFrom: String = "",
    prefillTo: String = "",
) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var watches by remember { mutableStateOf<List<com.yuldash.app.data.RouteWatchDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var from by rememberSaveable { mutableStateOf(prefillFrom) }
    var to by rememberSaveable { mutableStateOf(prefillTo) }
    var bothWays by rememberSaveable { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }

    val savedMsg = appText("Готово! Сообщим, как появится поездка", "Әҙер! Сәфәр сыҡҡас, хәбәр итәбеҙ")
    val failMsg = appText("Не получилось. Проверь сеть и повтори", "Булманы. Сетте тикшереп ҡабатла")
    val removedMsg = appText("Подписка удалена", "Яҙылыу юйылды")

    fun reload() {
        loading = true; error = false
        scope.launch {
            ApiClient.getRouteWatches()
                .onSuccess { watches = it; error = false }
                .onFailure { error = true }
            loading = false
        }
    }
    LaunchedEffect(Unit) { reload() }

    Scaffold(
        containerColor = CanonBg,
        topBar = { ScreenTopBar(appText("Мои подписки", "Яҙылыуҙарым"), onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item {
                Text(
                    appText(
                        "Подпишись на маршрут — пришлём уведомление, как только водитель опубликует подходящую поездку.",
                        "Маршрутҡа яҙыл — йөрөтөүсе тап килгән сәфәр баҫтырһа, шунда уҡ хәбәр итәбеҙ."
                    ),
                    color = CanonMuted, fontSize = 14.sp, lineHeight = 19.sp
                )
            }
            // --- Форма подписки ---
            item {
                AppCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            from, { from = it }, label = { Text(appText("Откуда", "Ҡайҙан")) },
                            leadingIcon = { Icon(Icons.Default.Route, contentDescription = null, tint = CanonGreen2) },
                            modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(14.dp)
                        )
                        OutlinedTextField(
                            to, { to = it }, label = { Text(appText("Куда", "Ҡайҙа")) },
                            leadingIcon = { Icon(Icons.Default.NotificationsActive, contentDescription = null, tint = CanonGreen2) },
                            modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(14.dp)
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.SwapHoriz, contentDescription = null, tint = CanonMuted, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(appText("И в обратную сторону", "Кире яҡҡа ла"), color = CanonText, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            Switch(checked = bothWays, onCheckedChange = { bothWays = it })
                        }
                        val needBothMsg = appText("Укажи откуда и куда", "Ҡайҙан һәм ҡайҙа икәнен яҙ")
                        AppButton(
                            text = appText("Следить за маршрутом", "Маршрутты күҙәтеү"),
                            onClick = {
                                val f = from.trim(); val t = to.trim()
                                if (f.isBlank() || t.isBlank()) {
                                    Toast.makeText(ctx, needBothMsg, Toast.LENGTH_SHORT).show()
                                    return@AppButton
                                }
                                submitting = true
                                scope.launch {
                                    ApiClient.createRouteWatch(f, t, if (bothWays) "both" else "forward")
                                        .onSuccess {
                                            Toast.makeText(ctx, savedMsg, Toast.LENGTH_SHORT).show()
                                            from = ""; to = ""; bothWays = false
                                            reload()
                                        }
                                        .onFailure {
                                            val m = (it as? com.yuldash.app.data.ApiException)?.message ?: failMsg
                                            Toast.makeText(ctx, m, Toast.LENGTH_LONG).show()
                                        }
                                    submitting = false
                                }
                            },
                            icon = Icons.Default.NotificationsActive,
                            loading = submitting,
                        )
                    }
                }
            }
            // --- Список активных подписок ---
            item {
                Text(appText("Активные подписки", "Әүҙем яҙылыуҙар"), color = CanonGreen, fontWeight = FontWeight.Black, fontSize = 18.sp, modifier = Modifier.padding(top = 6.dp))
            }
            when {
                loading && watches.isEmpty() -> item { AppLoading(appText("Загрузка…", "Йөкләнә…")) }
                error && watches.isEmpty() -> item { AppErrorState(onRetry = { reload() }) }
                watches.isEmpty() -> item {
                    AppEmptyState(
                        title = appText("Пока нет подписок", "Әлегә яҙылыуҙар юҡ"),
                        text = appText("Подпишись на нужный маршрут выше — не пропустишь новую поездку.", "Кәрәкле маршрутҡа яҙыл — яңы сәфәрҙе үткәрмәҫһең."),
                        icon = Icons.Default.NotificationsActive,
                    )
                }
                else -> items(watches, key = { it.id }) { w ->
                    RouteWatchRow(
                        watch = w,
                        onDelete = {
                            scope.launch {
                                ApiClient.deleteRouteWatch(w.id)
                                    .onSuccess { watches = watches.filterNot { it.id == w.id }; Toast.makeText(ctx, removedMsg, Toast.LENGTH_SHORT).show() }
                                    .onFailure { Toast.makeText(ctx, failMsg, Toast.LENGTH_SHORT).show() }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun RouteWatchRow(watch: com.yuldash.app.data.RouteWatchDto, onDelete: () -> Unit) {
    AppCard {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = CanonMint, shape = CircleShape) {
                Icon(Icons.Default.Route, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(12.dp).size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    if (watch.direction == "both") "${watch.fromCity}  ⇄  ${watch.toCity}" else "${watch.fromCity}  →  ${watch.toCity}",
                    color = CanonText, fontWeight = FontWeight.Black, fontSize = 17.sp
                )
                Text(
                    if (watch.direction == "both") appText("Туда и обратно", "Бара һәм ҡайта")
                    else appText("Караулим поездку", "Сәфәрҙе күҙәтәбеҙ"),
                    color = CanonMuted, fontSize = 13.sp
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = appText("Удалить", "Юйыу"), tint = CanonRed)
            }
        }
    }
}

@Composable
internal fun SafetyScreen(
    onBack: () -> Unit,
    onSelectTab: (HomeTab) -> Unit,
    onSos: () -> Unit,
    onShareTrip: () -> Unit = {},
    onRules: () -> Unit = {},
    onBlocklist: () -> Unit = {},
    onReport: () -> Unit = {},
) {
    val ctx = LocalContext.current
    var verifiedOnly by remember { mutableStateOf(AppPrefs.verifiedOnly(ctx)) }
    Scaffold(
        containerColor = CanonBg,
        topBar = { ScreenTopBar(appText("Безопасность", "Хәүефһеҙлек"), onBack) },
        bottomBar = { YuldashBottomBar(selectedTab = HomeTab.Profile, onSelect = onSelectTab) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 18.dp)
        ) {
            item { Spacer(Modifier.height(10.dp)) }
            item {
                Text(
                    appText("Ваши данные и поездки под защитой", "Һеҙҙең мәғлүмәт һәм сәфәрҙәр һаҡланған"),
                    color = CanonMuted,
                    fontSize = 15.sp
                )
            }
            item {
                Surface(color = CanonDangerBg, shape = CanonItemShape, border = BorderStroke(1.dp, CanonDangerBorder)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = CanonDangerBg, shape = RoundedCornerShape(18.dp)) {
                            Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                // Sos-иконка сама рисует «SOS» → с Text("SOS") был дубль. Щит (как кнопка SOS).
                                Icon(Icons.Default.Shield, contentDescription = appText("Экстренный вызов", "Ашығыс саҡырыу"), tint = CanonRed, modifier = Modifier.size(34.dp))
                                Text("SOS", color = CanonRed, fontWeight = FontWeight.Black)
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(appText("Нужна помощь?", "Ярҙәм кәрәкме?"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 18.sp)
                            Text(appText("Свяжитесь с экстренными службами и поддержкой Юлдаш.", "Ашығыс хеҙмәттәр һәм Юлдаш ярҙамы менән бәйләнегеҙ."), color = CanonMuted, lineHeight = 19.sp)
                        }
                        Button(onClick = onSos, colors = ButtonDefaults.buttonColors(containerColor = CanonRed), shape = RoundedCornerShape(16.dp)) {
                            Text("SOS", fontWeight = FontWeight.Black)
                        }
                    }
                }
            }
            item {
                SettingsGroup {
                    // Честно: телефон прячет сервер (отдаёт номер только после подтверждения поездки).
                    // Раньше тут был тумблер, который писал в prefs, но ни на что не влиял — убрали ложное обещание.
                    SettingsNavRow(Icons.Default.PhoneLocked, appText("Телефон скрыт до подтверждения", "Телефон раҫланғанға тиклем йәшерелгән"), appText("Твой номер откроется попутчику только после подтверждения поездки — так устроен Юлдаш.", "Номерың юлдашҡа тик сәфәр раҫланғас ҡына асыла — Юлдаш шулай эшләй."))
                    SettingSwitchRow(Icons.Default.Verified, appText("Только проверенные участники", "Тик раҫланған ҡатнашыусылар"), appText("Показывать и принимать поездки только от проверенных пользователей.", "Тик раҫланған ҡулланыусылар менән эшләү."), verifiedOnly) { verifiedOnly = it; AppPrefs.setVerifiedOnly(ctx, it) }
                    SettingsNavRow(Icons.Default.Person, appText("Поделиться поездкой с близким", "Сәфәрҙе яҡын кешегә ебәреү"), appText("Отправьте данные о поездке близкому человеку.", "Сәфәр мәғлүмәтен яҡын кешегә ебәрегеҙ."), onClick = onShareTrip)
                    SettingsNavRow(Icons.Default.Block, appText("Чёрный список", "Ҡара исемлек"), appText("Пользователи, с которыми вы не хотите ездить.", "Сәфәр итмәҫкә теләгән ҡулланыусылар."), onClick = onBlocklist)
                    SettingsNavRow(Icons.Default.Report, appText("Пожаловаться на пользователя", "Ҡулланыусыға ялыу"), appText("Сообщите о нарушении правил или безопасности.", "Ҡағиҙә йәки хәүефһеҙлек боҙолоуын хәбәр итегеҙ."), onClick = onReport)
                    SettingsNavRow(Icons.Default.Description, appText("Правила поездок", "Сәфәр ҡағиҙәләре"), appText("Ознакомьтесь с правилами сервиса Юлдаш.", "Юлдаш ҡағиҙәләре менән танышығыҙ."), onClick = onRules)
                }
            }
            item {
                InfoCard(appText("Мы заботимся о вашей безопасности", "Беҙ хәүефһеҙлек тураһында ҡайғыртабыҙ"), appText("Проверяем участников, скрываем телефон и даём быстрый SOS.", "Ҡатнашыусыларҙы тикшерәбеҙ, телефонды йәшерәбеҙ һәм тиҙ SOS бирәбеҙ."), Icons.Default.Shield)
            }
        }
    }
}

@Composable
internal fun SettingsScreen(
    onBack: () -> Unit,
    onSelectTab: (HomeTab) -> Unit,
    onToggleLanguage: () -> Unit,
    onPrivacy: () -> Unit = {},
    onConsents: () -> Unit = {},
    onPayments: () -> Unit = {},
    onFilters: () -> Unit = {},
    isAdmin: Boolean = false,
    onAdminCabinet: () -> Unit = {},
    onLogout: () -> Unit = {},
) {
    val ctx = LocalContext.current
    var notifications by remember { mutableStateOf(AppPrefs.notifications(ctx)) }
    var sounds by remember { mutableStateOf(AppPrefs.sounds(ctx)) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showFontDialog by remember { mutableStateOf(false) }
    var showLogoutDialog by remember { mutableStateOf(false) }
    val isBashkir = LocalAppLanguage.current == AppLanguage.Ba
    // Текущая тема приложения (она же тема карты): системная / светлая / тёмная.
    val themeLabel = when (ThemePrefs.darkOverride) {
        true -> appText("Тёмная", "Ҡараңғы")
        false -> appText("Светлая", "Яҡты")
        null -> appText("Как в системе", "Системалағыса")
    }
    // Крупный шрифт: текущий выбранный размер текста (единая точка правды FontScalePrefs).
    val fontLabel = fontScaleLabel(FontScalePrefs.option)
    if (showThemeDialog) {
        ThemePickerDialog(current = ThemePrefs.darkOverride, onPick = { ThemePrefs.darkOverride = it; showThemeDialog = false }, onDismiss = { showThemeDialog = false })
    }
    if (showFontDialog) {
        FontScalePickerDialog(
            current = FontScalePrefs.option,
            onPick = { FontScalePrefs.set(ctx, it); showFontDialog = false },
            onDismiss = { showFontDialog = false },
        )
    }
    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            containerColor = CanonSurface,
            title = { Text(appText("Выйти из аккаунта?", "Иҫәптән сығаһығыҙмы?"), color = CanonText, fontWeight = FontWeight.Black) },
            text = { Text(appText("Нужно будет снова войти через Telegram.", "Telegram аша яңынан инергә кәрәк буласаҡ."), color = CanonMuted) },
            confirmButton = { TextButton(onClick = { showLogoutDialog = false; onLogout() }) { Text(appText("Выйти", "Сығыу"), color = CanonRed, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { showLogoutDialog = false }) { Text(appText("Отмена", "Баш тартыу"), color = CanonMuted) } },
        )
    }
    Scaffold(
        containerColor = CanonBg,
        bottomBar = { YuldashBottomBar(selectedTab = HomeTab.Profile, onSelect = onSelectTab) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 18.dp)
        ) {
            item { Spacer(Modifier.height(10.dp)) }
            item {
                Text(appText("Настройки", "Көйләүҙәр"), color = CanonGreen, fontSize = 34.sp, lineHeight = 36.sp, fontWeight = FontWeight.Black)
                Text(appText("Настройте приложение под себя", "Ҡушымтаны үҙегеҙгә көйләгеҙ"), color = CanonMuted, fontSize = 15.sp)
            }
            item { CompactProfileBanner() }
            item {
                SettingsGroup {
                    SettingSwitchRow(Icons.Default.Notifications, appText("Уведомления", "Хәбәрҙәр"), appText("Получать важные обновления и напоминания", "Мөһим иҫкәртеүҙәр алыу"), notifications) { notifications = it; AppPrefs.setNotifications(ctx, it) }
                    SettingsNavRow(Icons.Default.Language, appText("Язык", "Тел"), if (isBashkir) "Башҡортса" else "Русский", onClick = onToggleLanguage)
                    SettingsNavRow(Icons.Default.Map, appText("Тема", "Тема"), themeLabel, onClick = { showThemeDialog = true })
                    SettingsNavRow(Icons.Default.FormatSize, appText("Размер текста", "Текст ҙурлығы"), fontLabel, onClick = { showFontDialog = true })
                }
            }
            item {
                SettingsGroup {
                    SettingsNavRow(Icons.Default.Shield, appText("Приватность", "Махсуслыҡ"), appText("Управление безопасностью и данными", "Хәүефһеҙлек һәм мәғлүмәт"), onClick = onPrivacy)
                    SettingsNavRow(Icons.Default.Description, appText("Согласия и данные", "Ризалыҡтар һәм мәғлүмәт"), appText("Оферта, политика, геолокация — 152-ФЗ", "Оферта, сәйәсәт, геолокация — 152-ФЗ"), onClick = onConsents)
                    SettingSwitchRow(Icons.Default.VolumeUp, appText("Звуки", "Тауыштар"), appText("Звуковые уведомления и эффекты", "Тауышлы хәбәрҙәр"), sounds) { sounds = it; AppPrefs.setSounds(ctx, it) }
                }
            }
            item {
                SettingsGroup {
                    SettingsNavRow(Icons.Default.Tune, appText("Фильтры по умолчанию", "Ғәҙәти фильтрҙар"), appText("Условия поиска поездок", "Сәфәр эҙләү шарттары"), onClick = onFilters)
                    SettingsNavRow(Icons.Default.CreditCard, appText("Оплата поездок", "Сәфәр түләүе"), appText("Как оплачивать поездки в Юлдаш", "Юлдашта сәфәр өсөн нисек түләргә"), onClick = onPayments)
                }
            }
            if (isAdmin) {
                item {
                    SettingsGroup {
                        SettingsNavRow(Icons.Default.AdminPanelSettings, appText("Кабинет админа", "Админ кабинеты"), appText("Заявки, отклики, реклама — единый центр", "Заявкалар, яуаптар, реклама — берҙәм үҙәк"), onClick = onAdminCabinet)
                    }
                }
            }
            item {
                SettingsGroup {
                    SettingsNavRow(Icons.Default.Info, appText("О приложении", "Ҡушымта тураһында"), appText("Версия ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", "Нөсхә ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"))
                }
            }
            item {
                SettingsGroup {
                    SettingsNavRow(Icons.Default.ExitToApp, appText("Выйти из аккаунта", "Иҫәптән сығыу"), appText("Завершить сеанс на этом устройстве", "Был ҡоролмала сеансты тамамлау"), onClick = { showLogoutDialog = true })
                }
            }
            item {
                Text("Юлдаш © 2026", modifier = Modifier.fillMaxWidth(), color = CanonMuted, fontSize = 13.sp)
            }
        }
    }
}

/** Выбор темы оформления (она же тема карты): системная / светлая / тёмная. */
@Composable
internal fun ThemePickerDialog(current: Boolean?, onPick: (Boolean?) -> Unit, onDismiss: () -> Unit) {
    val options = listOf<Pair<Boolean?, String>>(
        null to appText("Как в системе", "Системалағыса"),
        false to appText("Светлая", "Яҡты"),
        true to appText("Тёмная", "Ҡараңғы"),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CanonSurface,
        confirmButton = { TextButton(onClick = onDismiss) { Text(appText("Готово", "Әҙер"), color = CanonGreen2, fontWeight = FontWeight.Bold) } },
        title = { Text(appText("Тема оформления", "Биҙәлеш темаһы"), color = CanonText, fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                options.forEach { (value, label) ->
                    Row(
                        Modifier.fillMaxWidth().bounceClick { onPick(value) }.padding(vertical = 12.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (value == current) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (value == current) CanonGreen2 else CanonMuted
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(label, color = CanonText, fontSize = 16.sp)
                    }
                }
            }
        },
    )
}

/** Подпись текущего размера текста (для строки настроек и простого режима). */
@Composable
internal fun fontScaleLabel(option: FontScaleOption): String = when (option) {
    FontScaleOption.Normal -> appText("Обычный", "Ғәҙәти")
    FontScaleOption.Large -> appText("Крупный", "Эре")
    FontScaleOption.ExtraLarge -> appText("Очень крупный", "Бик эре")
}

/**
 * Выбор размера текста (крупный шрифт для пожилых и слабовидящих).
 * Превью справа показывает относительный размер выбранного множителя.
 */
@Composable
internal fun FontScalePickerDialog(current: FontScaleOption, onPick: (FontScaleOption) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CanonSurface,
        confirmButton = { TextButton(onClick = onDismiss) { Text(appText("Готово", "Әҙер"), color = CanonGreen2, fontWeight = FontWeight.Bold) } },
        title = { Text(appText("Размер текста", "Текст ҙурлығы"), color = CanonText, fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    appText("Увеличь текст во всём приложении — так удобнее читать.", "Бөтә ҡушымтала текстты ҙурайт — уҡырға уңайлыраҡ."),
                    color = CanonMuted, fontSize = 14.sp, lineHeight = 18.sp,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
                FontScaleOption.values().forEach { option ->
                    val selected = option == current
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .bounceClick { onPick(option) }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (selected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (selected) CanonGreen2 else CanonMuted
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(fontScaleLabel(option), color = CanonText, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        // Превью относительного размера: одна и та же «Аа» в масштабе множителя.
                        Text(
                            "Аа",
                            color = if (selected) CanonGreen2 else CanonMuted,
                            fontWeight = FontWeight.Bold,
                            fontSize = (15f * option.multiplier).sp,
                        )
                    }
                }
            }
        },
    )
}

/** Правила поездок «между своими» — статический экран. */
@Composable
internal fun RulesScreen(onBack: () -> Unit) {
    val rules = listOf(
        appText("Уважайте друг друга", "Бер-берегеҙҙе хөрмәт итегеҙ") to appText("Юлдаш — поездки между своими. Будьте вежливы и пунктуальны.", "Юлдаш — үҙ кешеләр араһында сәфәр. Әҙәпле һәм ваҡытлы булығыҙ."),
        appText("Договаривайтесь заранее", "Алдан килешегеҙ") to appText("Согласуйте место и время встречи в чате до выезда.", "Сығышҡа тиклем осрашыу урынын һәм ваҡытын чатта килешегеҙ."),
        appText("Безопасность прежде всего", "Хәүефһеҙлек беренсе урында") to appText("Пристёгивайтесь, не отвлекайте водителя, при опасности — кнопка SOS.", "Бәйләнегеҙ, водителде борсомағыҙ, хәүеф булһа — SOS төймәһе."),
        appText("Честная оплата", "Намыҫлы түләү") to appText("Оплачивайте поездку как договорились, переводом по СБП.", "Сәфәр өсөн килешеүсә, СБП аша түләгеҙ."),
        appText("Оставляйте отзыв", "Баһа ҡалдырығыҙ") to appText("После поездки оцените попутчика — так доверие растёт у всех.", "Сәфәрҙән һуң юлдашты баһалағыҙ — шулай ышаныс үҫә."),
    )
    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Правила поездок", "Сәфәр ҡағиҙәләре"), onBack) }) { padding ->
        LazyColumn(
            Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            items(rules.size) { i ->
                val (t, d) = rules[i]
                Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                        Surface(color = CanonMint, shape = RoundedCornerShape(12.dp)) {
                            Text("${i + 1}", Modifier.padding(horizontal = 13.dp, vertical = 8.dp), color = CanonGreen2, fontWeight = FontWeight.Black)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(t, color = CanonText, fontWeight = FontWeight.Black, fontSize = 16.sp)
                            Text(d, color = CanonMuted, fontSize = 14.sp, lineHeight = 19.sp)
                        }
                    }
                }
            }
        }
    }
}

/** Как оплачивать поездки — статический экран (сейчас СБП напрямую, ЮКасса позже). */
@Composable
internal fun PaymentInfoScreen(onBack: () -> Unit, onOpenPricing: () -> Unit = {}) {
    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Оплата поездок", "Сәфәр түләүе"), onBack) }) { padding ->
        LazyColumn(
            Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item {
                Surface(color = CanonMint, shape = CanonItemShape) {
                    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CreditCard, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(appText("Оплата напрямую водителю", "Тура водителгә түләү"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 17.sp)
                            Text(appText("Сейчас оплата — переводом по СБП на карту водителя, как договоритесь в чате.", "Хәҙер түләү — СБП аша водитель картаһына, чатта килешеүсә."), color = CanonMuted, fontSize = 14.sp, lineHeight = 19.sp)
                        }
                    }
                }
            }
            item {
                Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(appText("Как это работает", "Был нисек эшләй"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 16.sp)
                        PaymentStepRow("1", appText("Договоритесь о цене в чате", "Хаҡты чатта килешегеҙ"))
                        PaymentStepRow("2", appText("После поездки переведите по СБП", "Сәфәрҙән һуң СБП аша күсерегеҙ"))
                        PaymentStepRow("3", appText("Оставьте отзыв друг о друге", "Бер-берегеҙ тураһында баһа ҡалдырығыҙ"))
                    }
                }
            }
            item {
                // Честно о риске (не только о плюсе «без комиссии»): деньги мимо приложения.
                Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
                    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(appText("Важно знать", "Белеп ҡуйығыҙ"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 15.sp)
                            Text(
                                appText(
                                    "Деньги идут напрямую между вами — Юлдаш их не держит и не может вернуть. Это доверие «между своими»: плати после поездки, смотри рейтинг и отзывы, а при споре напиши в поддержку — разберёмся по-человечески.",
                                    "Аҡса тура үҙ-ара күсә — Юлдаш уны тотмай һәм кире ҡайтара алмай. Был «үҙ-ара» ышаныс: сәфәрҙән һуң түлә, баһа менән фекерҙәргә ҡара, бәхәс сыҡһа ярҙамға яҙ — кешеләрсә асыҡларбыҙ.",
                                ),
                                color = CanonMuted, fontSize = 13.sp, lineHeight = 18.sp,
                            )
                        }
                    }
                }
            }
            item {
                // Вход в подробную страницу «Честно о цене» (формула, комиссия, куда идёт).
                Surface(
                    color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder),
                    modifier = Modifier.fillMaxWidth().bounceClick(onClick = onOpenPricing)
                ) {
                    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(26.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(appText("Честно о цене", "Хаҡ тураһында асыҡтан-асыҡ"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 16.sp)
                            Text(appText("Как считается цена и куда идёт комиссия", "Хаҡ нисек иҫәпләнә һәм комиссия ҡайҙа китә"), color = CanonMuted, fontSize = 13.sp, lineHeight = 17.sp)
                        }
                        Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = CanonMuted)
                    }
                }
            }
            item {
                Text(appText("Скоро: оплата картой прямо в приложении.", "Тиҙҙән: ҡушымтала карта менән түләү."), color = CanonMuted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp))
            }
        }
    }
}

/**
 * «Честно о цене» — по-соседски и с гордостью объясняем, за что платят и куда идёт комиссия.
 * Никаких выдуманных цифр: попутка бесплатна (только бензин напрямую), у такси честный потолок
 * суржа ×1.5 (не ×3), комиссия водителя 3–8% по стажу — и открыто, на что она уходит.
 */
@Composable
internal fun PricingInfoScreen(onBack: () -> Unit) {
    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Честно о цене", "Хаҡ тураһында"), onBack) }) { padding ->
        LazyColumn(
            Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item {
                Surface(color = CanonMint, shape = CanonItemShape) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(appText("Мы не прячем, на что живём", "Нимә менән йәшәгәнде йәшермәйбеҙ"), color = CanonGreen2, fontWeight = FontWeight.Black, fontSize = 18.sp, lineHeight = 22.sp)
                        Text(appText("Юлдаш — между своими. Здесь ты всегда видишь, из чего цена и куда уходит каждая копейка.", "Юлдаш — үҙ кешеләр араһында. Бында хаҡтың нимәнән торғанын һәм һәр тин ҡайҙа киткәнен күрәһең."), color = CanonText, fontSize = 14.sp, lineHeight = 19.sp)
                    }
                }
            }
            // (а) Попутка
            item {
                PricingBlock(
                    icon = Icons.Default.VolunteerActivism,
                    title = appText("Попутка — бесплатна", "Юлдаш — бушлай"),
                    body = appText(
                        "За саму поездку между своими Юлдаш не берёт ничего. Ты просто скидываешься водителю на бензин напрямую — по-соседски. Сумму видно заранее и можно честно поделить на всех.",
                        "Үҙ кешеләр араһындағы сәфәр өсөн Юлдаш бер нәмә лә алмай. Һин бензинға тура водителгә өҫтәйһең — күршеләрсә. Сумма алдан күренә, бөтәһенә лә намыҫлы бүленә.",
                    ),
                )
            }
            // (б) Такси — формула тарифа + честный сурж
            item {
                PricingBlock(
                    icon = Icons.Default.LocalTaxi,
                    accent = CanonTaxi,
                    title = appText("Такси — понятный тариф", "Такси — асыҡ тариф"),
                    body = appText(
                        "Цену показываем ДО заказа, без сюрпризов. Она складывается из подачи + за километры + за минуты в пути, и есть минимальная стоимость короткой поездки. Никакого счётчика, который «набегает» незаметно.",
                        "Хаҡты заказға тиклем күрһәтәбеҙ, сюрприздарһыҙ. Ул килеү + километрҙар + юлдағы минуттар өсөн, һәм ҡыҫҡа сәфәрҙең минималь хаҡы бар. Һиҙҙермәй «үҫкән» счётчик юҡ.",
                    ),
                )
            }
            item {
                Surface(shape = CanonItemShape, color = CanonTaxiBg, border = BorderStroke(1.dp, CanonTaxi)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                        Text("⚡", fontSize = 22.sp)
                        Spacer(Modifier.width(12.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(appText("Честный сурж: максимум ×1.5", "Намыҫлы сурж: күп тигәндә ×1.5"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 15.sp)
                            Text(
                                appText(
                                    "Когда машин мало, цена может подрасти — но у нас потолок ×1.5, а не ×3, как у больших сервисов. И мы честно пишем, почему дороже, ещё до того, как ты вызовешь.",
                                    "Машина аҙ булғанда хаҡ бер аҙ үҫә ала — тик бездә түшәм ×1.5, ҙур сервистарҙағыса ×3 түгел. Һәм ниңә ҡиммәтерәк икәнен саҡырғанға тиклем үк яҙабыҙ.",
                                ),
                                color = CanonMuted, fontSize = 13.sp, lineHeight = 18.sp,
                            )
                        }
                    }
                }
            }
            // (в) Комиссия водителя + куда идёт
            item {
                PricingBlock(
                    icon = Icons.Default.Verified,
                    title = appText("Комиссия водителя — 3–8%", "Водитель комиссияһы — 3–8%"),
                    body = appText(
                        "С поездок такси Юлдаш берёт небольшую комиссию с водителя — от 3% до 8% в зависимости от стажа: чем дольше и надёжнее возишь, тем меньше платишь. У попутки комиссии нет вовсе.",
                        "Такси сәфәрҙәренән Юлдаш водителдән бәләкәй комиссия ала — стажға ҡарап 3%-тан 8%-ҡа тиклем: оҙағыраҡ һәм ышаныслыраҡ йөрөтһәң, шунса аҙ түләйһең. Юлдашта комиссия бөтөнләй юҡ.",
                    ),
                )
            }
            item {
                Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(appText("Куда идёт комиссия", "Комиссия ҡайҙа китә"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 16.sp)
                        PricingWhereRow(Icons.Default.Info, appText("Серверы и связь", "Серверҙар һәм бәйләнеш"), appText("чтобы карта, чат и заказы работали без сбоев", "карта, чат һәм заказдар өҙлөкһөҙ эшләһен"))
                        PricingWhereRow(Icons.Default.Map, appText("Карты и маршруты", "Карталар һәм маршруттар"), appText("оплата картографии, по которой строятся поездки", "сәфәрҙәр төҙөлгән картография түләүе"))
                        PricingWhereRow(Icons.Default.TrendingUp, appText("Развитие приложения", "Ҡушымтаны үҫтереү"), appText("новые функции и поддержка — чтобы Юлдаш рос", "яңы мөмкинлектәр һәм ярҙам — Юлдаш үҫһен өсөн"))
                        Text(appText("Мы не прячем, на что живём — сервис должен окупаться честно, без скрытых наценок.", "Нимә менән йәшәгәнде йәшермәйбеҙ — сервис йәшерен өҫтәмәләрһеҙ, намыҫлы аҡланырға тейеш."), color = CanonMuted, fontSize = 13.sp, lineHeight = 18.sp)
                    }
                }
            }
            // (г) Оплата
            item {
                PricingBlock(
                    icon = Icons.Default.CreditCard,
                    title = appText("Оплата — пока напрямую по СБП", "Түләү — әлегә СБП аша тура"),
                    body = appText(
                        "Сейчас деньги идут напрямую водителю переводом по СБП — «на доверии», как между своими. Юлдаш их не держит. Скоро добавим оплату картой прямо в приложении.",
                        "Хәҙер аҡса водителгә СБП аша тура күсә — «ышаныс менән», үҙ-ара кеүек. Юлдаш уны тотмай. Тиҙҙән ҡушымтала карта менән түләү өҫтәйбеҙ.",
                    ),
                )
            }
            item {
                Text(appText("Цифры могут меняться — но правило одно: ты всегда видишь, за что платишь.", "Һандар үҙгәрергә мөмкин — тик ҡағиҙә бер: нимә өсөн түләгәнеңде һәр ваҡыт күрәһең."), color = CanonMuted, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(horizontal = 4.dp))
            }
        }
    }
}

@Composable
private fun PricingBlock(icon: ImageVector, title: String, body: String, accent: Color? = null) {
    Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = null, tint = accent ?: CanonGreen2, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, color = CanonText, fontWeight = FontWeight.Black, fontSize = 16.sp)
                Text(body, color = CanonMuted, fontSize = 14.sp, lineHeight = 19.sp)
            }
        }
    }
}

@Composable
private fun PricingWhereRow(icon: ImageVector, title: String, sub: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(sub, color = CanonMuted, fontSize = 13.sp, lineHeight = 17.sp)
        }
    }
}

@Composable
internal fun PaymentStepRow(n: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(color = CanonMint, shape = RoundedCornerShape(10.dp)) {
            Text(n, Modifier.padding(horizontal = 11.dp, vertical = 6.dp), color = CanonGreen2, fontWeight = FontWeight.Black, fontSize = 14.sp)
        }
        Spacer(Modifier.width(12.dp))
        Text(text, color = CanonText, fontSize = 15.sp)
    }
}

/** Фильтры по умолчанию для «Ближайших поездок» — хранятся в SharedPreferences. */
internal object FilterPrefs {
    private const val PREF = "yuldash_filters"
    private const val KEY = "default_filters"
    fun load(ctx: Context): Set<String> =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getStringSet(KEY, emptySet())?.toSet() ?: emptySet()
    fun save(ctx: Context, set: Set<String>) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putStringSet(KEY, set).apply()
    }
}

/**
 * Настройки приложения — реальные тумблеры (Уведомления/Звуки/Безопасность), хранятся на диске.
 * Уведомления/Звуки читает [com.yuldash.app.data.FcmService] перед показом пуша (клиентское заглушение).
 * verifiedOnly применяется как фильтр выдачи «Ближайших» на карте.
 */
internal object AppPrefs {
    private const val PREF = "yuldash_settings"
    private fun sp(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    fun notifications(ctx: Context) = sp(ctx).getBoolean("notifications", true)
    fun sounds(ctx: Context) = sp(ctx).getBoolean("sounds", true)
    /** Язык интерфейса для мира БЕЗ Compose (FCM/фоновые сервисы) — YuldashApp пишет при смене языка. */
    fun language(ctx: Context): AppLanguage =
        runCatching { AppLanguage.valueOf(sp(ctx).getString("app_language", "") ?: "") }.getOrDefault(AppLanguage.Ru)
    fun setLanguage(ctx: Context, v: AppLanguage) = sp(ctx).edit().putString("app_language", v.name).apply()
    fun verifiedOnly(ctx: Context) = sp(ctx).getBoolean("verified_only", false)
    fun setNotifications(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("notifications", v).apply()
    fun setSounds(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("sounds", v).apply()
    fun setVerifiedOnly(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("verified_only", v).apply()
}

/** Экран «Фильтры по умолчанию»: тумблеры условий, сохраняются и применяются к «Ближайшим». */
@Composable
internal fun FiltersScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var sel by remember { mutableStateOf(FilterPrefs.load(ctx)) }
    fun toggle(k: String) {
        sel = if (k in sel) sel - k else sel + k
        FilterPrefs.save(ctx, sel)
    }
    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Фильтры по умолчанию", "Ғәҙәти фильтрҙар"), onBack) }) { padding ->
        LazyColumn(
            Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item { Text(appText("Эти условия применятся к «Ближайшим поездкам» автоматически при открытии карты.", "Был шарттар карта асылғанда «Яҡын сәфәрҙәргә» автомат ҡулланыла."), color = CanonMuted, fontSize = 14.sp, lineHeight = 19.sp) }
            item {
                SettingsGroup {
                    SettingSwitchRow(Icons.Default.Woman, appText("Только женщины", "Тик ҡатын-ҡыҙ"), appText("Показывать только поездки для женщин", "Тик ҡатын-ҡыҙ өсөн сәфәрҙәр"), "women" in sel) { toggle("women") }
                    SettingSwitchRow(Icons.Default.ChildCare, appText("Детское кресло", "Балалар ултырғысы"), appText("Есть детское кресло или бустер", "Балалар ултырғысы бар"), "child" in sel) { toggle("child") }
                    SettingSwitchRow(Icons.Default.Pets, appText("С животным", "Хайуан менән"), appText("Можно ехать с питомцем", "Хайуан менән барырға мөмкин"), "pets" in sel) { toggle("pets") }
                    SettingSwitchRow(Icons.Default.Luggage, appText("Багаж", "Багаж"), appText("Есть место под багаж", "Багаж өсөн урын бар"), "baggage" in sel) { toggle("baggage") }
                    SettingSwitchRow(Icons.Default.AcUnit, appText("Кондиционер", "Кондиционер"), appText("Есть кондиционер в салоне", "Салонда кондиционер бар"), "ac" in sel) { toggle("ac") }
                    SettingSwitchRow(Icons.Default.Block, appText("Некурящий", "Тартмаусы"), appText("Показывать поездки без курения", "Тартыуһыҙ сәфәрҙәр генә"), "nosmoke" in sel) { toggle("nosmoke") }
                }
            }
        }
    }
}

@Composable
internal fun PersonRow(name: String, actionLabel: String, danger: Boolean, onAction: () -> Unit) {
    Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = CanonMint, shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Default.Person, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(10.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text(name, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = onAction) { Text(actionLabel, color = if (danger) CanonRed else CanonGreen2, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun DocImage(url: String, token: String) {
    val ctx = LocalContext.current
    if (url.isBlank()) {
        Text(appText("нет файла", "файл юҡ"), color = CanonMuted, fontSize = 12.sp)
        return
    }
    coil.compose.AsyncImage(
        model = coil.request.ImageRequest.Builder(ctx).data(url).addHeader("Authorization", "Bearer $token").crossfade(true).build(),
        contentDescription = null,
        modifier = Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(12.dp)),
        contentScale = ContentScale.Crop,
    )
}

/**
 * Админ: модерация водителей — умная обёртка. Держит стейт, грузит список, ходит в ApiClient/Toast.
 * Весь рендер вынесен в чистый [AdminDriversContent] → его покрывают Robolectric-тесты на JVM.
 */
@Composable
internal fun AdminDriversScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var list by remember { mutableStateOf<List<com.yuldash.app.data.PendingDriverDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val token = remember { ApiClient.currentToken() ?: "" }
    val approvedMsg = appText("Водитель одобрен", "Водитель раҫланды")
    val rejectedMsg = appText("Отклонено", "Кире ҡағылды")
    val actionErrMsg = appText("Не получилось. Проверь сеть и повтори.", "Булманы. Сетте тикшереп ҡабатла.")
    val loadErr = appText("Не удалось загрузить. Проверь интернет.", "Йөкләп булманы. Интернетты тикшер.")
    // error отделяет «сеть упала» от «список пуст» — иначе админ решит, что заявок на проверку нет.
    fun reload() { loading = true; error = null; scope.launch { ApiClient.getPendingDrivers().onSuccess { list = it }.onFailure { error = loadErr }; loading = false } }
    LaunchedEffect(Unit) { reload() }
    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Модерация водителей", "Водителдәрҙе модерациялау"), onBack) }) { padding ->
        AdminDriversContent(
            loading = loading,
            error = error,
            drivers = list,
            token = token,
            onRetry = { reload() },
            onApprove = { d -> val id = d.userId; scope.launch { ApiClient.moderateDriver(id, true).onSuccess { Toast.makeText(ctx, approvedMsg, Toast.LENGTH_SHORT).show(); reload() }.onFailure { Toast.makeText(ctx, actionErrMsg, Toast.LENGTH_SHORT).show() } } },
            onReject = { d -> val id = d.userId; scope.launch { ApiClient.moderateDriver(id, false).onSuccess { Toast.makeText(ctx, rejectedMsg, Toast.LENGTH_SHORT).show(); reload() }.onFailure { Toast.makeText(ctx, actionErrMsg, Toast.LENGTH_SHORT).show() } } },
            modifier = Modifier.padding(padding),
        )
    }
}

/**
 * Чистый рендер экрана модерации водителей: все состояния (загрузка / ошибка+повтор / пусто / список).
 * Данные и колбэки приходят параметрами → без сети/стейта/эффектов → тестируется на JVM (Robolectric).
 */
@Composable
internal fun AdminDriversContent(
    loading: Boolean,
    error: String?,
    drivers: List<com.yuldash.app.data.PendingDriverDto>,
    token: String,
    onRetry: () -> Unit,
    onApprove: (com.yuldash.app.data.PendingDriverDto) -> Unit,
    onReject: (com.yuldash.app.data.PendingDriverDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
        item { Text(appText("Проверь права и фото авто. Одобри или отклони.", "Права һәм авто фотоһын тикшер. Раҫла йәки кире ҡаҡ."), color = CanonMuted, fontSize = 14.sp, lineHeight = 19.sp) }
        if (loading) {
            item { Text(appText("Загрузка…", "Йөкләнә…"), color = CanonMuted) }
        } else if (error != null) {
            item { ListedError(error) { onRetry() } }
        } else if (drivers.isEmpty()) {
            item { ListedEmpty(appText("Нет заявок на проверку", "Тикшереүгә заявка юҡ"), appText("Здесь появятся водители, отправившие документы.", "Бында документ ебәргән водителдәр күренер")) }
        } else {
            items(drivers.size, key = { drivers[it].userId }) { i ->
                val d = drivers[i]
                Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(d.name, color = CanonText, fontWeight = FontWeight.Black, fontSize = 17.sp)
                        Text((d.car.ifBlank { "—" }) + " · " + d.phone, color = CanonMuted, fontSize = 13.sp)
                        AutoCheckRow(d.autocheckResult, d.autocheckData)
                        Text(appText("Водительское удостоверение", "Водитель таныҡлығы"), color = CanonMuted, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        DocImage(d.licenseUrl, token)
                        Text(appText("Фото автомобиля", "Автомобиль фотоһы"), color = CanonMuted, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        DocImage(d.carPhotoUrl, token)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { onApprove(d) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)) { Text(appText("Одобрить", "Раҫлау"), fontWeight = FontWeight.Bold) }
                            OutlinedButton(onClick = { onReject(d) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Text(appText("Отклонить", "Кире ҡағыу"), color = CanonRed, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }
        }
    }
}

/** Бейдж авто-проверки прав (OCR) в карточке модерации: вердикт + распознанные данные. */
@Composable
internal fun AutoCheckRow(result: String, dataJson: String) {
    if (result.isBlank()) return
    val parsed = remember(dataJson) {
        try { org.json.JSONObject(dataJson) } catch (e: Exception) { org.json.JSONObject() }
    }
    val num = parsed.optString("license_number")
    val expiry = parsed.optString("expiry")
    val (label, color) = when (result) {
        "pass" -> appText("🤖 Авто: похоже на действительные права", "🤖 Авто: ысын права кеүек") to CanonGreen2
        "reject" -> appText("🤖 Авто: фото не распознано", "🤖 Авто: фото танылманы") to CanonRed
        "error" -> appText("🤖 Авто: проверка недоступна", "🤖 Авто: тикшереп булманы") to CanonMuted
        else -> appText("🤖 Авто: нужна ручная проверка", "🤖 Авто: ҡул менән тикшерергә") to CanonMuted
    }
    val numLabel = appText("№ прав ", "права № ")
    val expLabel = appText("срок до ", "ваҡыты ")
    Surface(color = color.copy(alpha = 0.10f), shape = RoundedCornerShape(10.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, color = color, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            if (num.isNotBlank() || expiry.isNotBlank()) {
                val recog = buildString {
                    if (num.isNotBlank()) append(numLabel).append(num)
                    if (num.isNotBlank() && expiry.isNotBlank()) append("  ·  ")
                    if (expiry.isNotBlank()) append(expLabel).append(expiry)
                }
                Text(recog, color = CanonMuted, fontSize = 12.sp)
            }
        }
    }
}

/**
 * Админ: жалобы пользователей — умная обёртка. Держит стейт, грузит список, ходит в ApiClient.
 * Весь рендер вынесен в чистый [AdminReportsContent] → его покрывают Robolectric-тесты на JVM.
 */
@Composable
internal fun AdminReportsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var list by remember { mutableStateOf<List<com.yuldash.app.data.AdminReportDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val loadErr = appText("Не удалось загрузить. Проверь интернет.", "Йөкләп булманы. Интернетты тикшер.")
    val doneMsg = appText("Готово", "Әҙер")
    val actionErr = appText("Не получилось. Проверь сеть и повтори.", "Булманы. Селтәрҙе тикшереп ҡабатла.")
    // error отделяет «сеть упала» от «жалоб нет» — иначе сбой выглядит как «всё хорошо».
    fun reload() { loading = true; error = null; scope.launch { ApiClient.getAdminReports().onSuccess { list = it }.onFailure { error = loadErr }; loading = false } }
    fun act(block: suspend () -> Result<Unit>) {
        scope.launch {
            block().onSuccess { Toast.makeText(ctx, doneMsg, Toast.LENGTH_SHORT).show(); reload() }
                .onFailure { Toast.makeText(ctx, actionErr, Toast.LENGTH_SHORT).show() }
        }
    }
    LaunchedEffect(Unit) { reload() }
    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Жалобы", "Ялыуҙар"), onBack) }) { padding ->
        AdminReportsContent(
            loading = loading,
            error = error,
            reports = list,
            onRetry = { reload() },
            modifier = Modifier.padding(padding),
            onResolve = { r, keepPause -> act { ApiClient.adminResolveReport(r.id, resolution = "", keepPause = keepPause) } },
            onReject = { r -> act { ApiClient.adminRejectReport(r.id) } },
            onPause = { userId -> act { ApiClient.adminQualityPause(userId, hours = 72) } },
            onUnpause = { userId -> act { ApiClient.adminQualityUnpause(userId) } },
        )
    }
}

/** Двуязычное название категории жалобы по id (для админки и карточек). */
@Composable
internal fun reportCategoryLabel(id: String): String {
    val c = reportCategoriesAll().firstOrNull { it.id == id }
    return if (c != null) appText(c.ru, c.ba) else appText("Другое", "Башҡа")
}

/** Тяжёлые категории (⛔ §9): мгновенная пауза такси до разбора. */
internal val severeReportCategories = setOf("safety_threat", "kicked_out", "dangerous_driving")

/**
 * Чистый рендер экрана жалоб: все состояния (загрузка / ошибка+повтор / пусто / список).
 * Данные и колбэки приходят параметрами → без сети/стейта/эффектов → тестируется на JVM (Robolectric).
 */
@Composable
internal fun AdminReportsContent(
    loading: Boolean,
    error: String?,
    reports: List<com.yuldash.app.data.AdminReportDto>,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    // §9 Качество: действия разбора (дефолты — совместимость со старыми вызовами/тестами).
    onResolve: (com.yuldash.app.data.AdminReportDto, Boolean) -> Unit = { _, _ -> },
    onReject: (com.yuldash.app.data.AdminReportDto) -> Unit = {},
    onPause: (Int) -> Unit = {},
    onUnpause: (Int) -> Unit = {},
) {
    LazyColumn(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
        item { Text(appText("Жалобы пользователей. Подтверди или отклони — лестница наказаний дальше считается сама. Автора видишь только ты.", "Ҡулланыусы ялыуҙары. Раҫла йәки кире ҡаҡ — язалар баҫҡысы артабан үҙе иҫәпләнә. Авторҙы тик һин күрәһең."), color = CanonMuted, fontSize = 14.sp, lineHeight = 19.sp) }
        if (loading) {
            item { Text(appText("Загрузка…", "Йөкләнә…"), color = CanonMuted) }
        } else if (error != null) {
            item { ListedError(error) { onRetry() } }
        } else if (reports.isEmpty()) {
            item { ListedEmpty(appText("Жалоб нет", "Ялыу юҡ"), appText("Хороший знак — пользователи довольны.", "Яҡшы билдә — ҡулланыусылар риза.")) }
        } else {
            items(reports.size, key = { reports[it].id }) { i ->
                val r = reports[i]
                val severe = r.category in severeReportCategories
                val open = r.status == "new" || r.status == "reviewing"
                Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, if (severe && open) CanonRed.copy(alpha = 0.45f) else CanonBorder)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            // Категория (⛔ тяжёлая — красным) + статус разбора.
                            Surface(shape = RoundedCornerShape(8.dp), color = (if (severe) CanonRed else CanonGreen2).copy(alpha = 0.12f)) {
                                Text(reportCategoryLabel(r.category), color = if (severe) CanonRed else CanonGreen2, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                            }
                            val (stLabel, stColor) = when (r.status) {
                                "resolved" -> appText("Подтверждена", "Раҫланған") to CanonGreen2
                                "rejected" -> appText("Отклонена", "Кире ҡағылған") to CanonMuted
                                "reviewing" -> appText("В разборе", "Тикшереүҙә") to CanonWarn
                                else -> appText("Новая", "Яңы") to CanonWarn
                            }
                            Text(stLabel, color = stColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            if (r.createdAt.length >= 10) Text(r.createdAt.take(10), color = CanonMuted, fontSize = 12.sp)
                        }
                        Text("${r.reporterName}  →  ${r.targetName}", color = CanonText, fontWeight = FontWeight.Black, fontSize = 15.sp)
                        if (r.targetPhone.isNotBlank()) Text(r.targetPhone, color = CanonMuted, fontSize = 13.sp)
                        Text(r.reason.ifBlank { appText("без деталей", "ентекһеҙ") }, color = CanonText, fontSize = 14.sp, lineHeight = 19.sp)
                        if (r.resolution.isNotBlank()) Text(appText("Решение: ", "Ҡарар: ") + r.resolution, color = CanonMuted, fontSize = 13.sp)
                        if (open) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { onResolve(r, false) }, modifier = Modifier.weight(1f).height(44.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)) {
                                    Text(appText("Подтвердить", "Раҫлау"), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                                OutlinedButton(onClick = { onReject(r) }, modifier = Modifier.weight(1f).height(44.dp), shape = RoundedCornerShape(12.dp)) {
                                    Text(appText("Отклонить", "Кире ҡағыу"), color = CanonRed, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                            }
                            if (severe) {
                                // ⛔ Тяжёлая: пауза стоит «до разбора» — можно подтвердить, ОСТАВИВ паузу.
                                TextButton(onClick = { onResolve(r, true) }, modifier = Modifier.fillMaxWidth()) {
                                    Text(appText("Подтвердить и оставить паузу такси", "Раҫлап такси паузаһын ҡалдырыу"), color = CanonWarn, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        if (r.targetUserId > 0) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { onPause(r.targetUserId) }, modifier = Modifier.weight(1f)) {
                                    Text(appText("⏸ Пауза такси 72ч", "⏸ Такси паузаһы 72сәғ"), color = CanonWarn, fontSize = 12.sp)
                                }
                                TextButton(onClick = { onUnpause(r.targetUserId) }, modifier = Modifier.weight(1f)) {
                                    Text(appText("▶ Снять паузу", "▶ Паузаны алыу"), color = CanonGreen2, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Кабинет админа — единый центр: заявки помощи, отклики, реклама. Виден только админу. */
@Composable
internal fun AdminCabinetScreen(onBack: () -> Unit, onAdminRequest: () -> Unit, onAdminResponses: () -> Unit, onAds: () -> Unit, onDrivers: () -> Unit = {}, onReports: () -> Unit = {}, onPaymentRequests: () -> Unit = {}, onTaxi: () -> Unit = {}, onWaitlist: () -> Unit = {}, onTaxiPulse: () -> Unit = {}, onPartners: () -> Unit = {}, onPromoAdmin: () -> Unit = {}, onParcelsAdmin: () -> Unit = {}, onCourierAdmin: () -> Unit = {}, onIncomeCalc: () -> Unit = {}) {
    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Кабинет админа", "Админ кабинеты"), onBack) }) { padding ->
        LazyColumn(Modifier.padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
            item { Text(appText("Единый центр управления Юлдашем. Виден только администратору.", "Юлдашты идара итеү үҙәге. Тик админға күренә."), color = CanonMuted, fontSize = 14.sp, lineHeight = 19.sp) }
            item {
                SettingsGroup {
                    SettingsNavRow(Icons.Default.HeadsetMic, appText("Заявка за пользователя", "Ҡулланыусы өсөн заявка"), appText("Создать заявку после звонка «перезвоните мне»", "«Шылтыратығыҙ» һуңында заявка булдырыу"), onClick = onAdminRequest)
                    SettingsNavRow(Icons.Default.ListAlt, appText("Отклики по заявке", "Заявка буйынса яуаптар"), appText("Принять отклик за пользователя без интернета", "Интернетһыҙ ҡулланыусы өсөн яуап ҡабул итеү"), onClick = onAdminResponses)
                }
            }
            item {
                SettingsGroup {
                    SettingsNavRow(Icons.Default.Verified, appText("Модерация водителей", "Водителдәрҙе модерациялау"), appText("Проверить права и фото, одобрить", "Права һәм фотоны тикшереп раҫлау"), onClick = onDrivers)
                    SettingsNavRow(Icons.Default.LocalTaxi, appText("Таксисты", "Таксистар"), appText("Заявки 580-ФЗ и города, где включено такси", "580-ФЗ заявкалары һәм такси ҡабыҙылған ҡалалар"), onClick = onTaxi)
                    SettingsNavRow(Icons.Default.MonitorHeart, appText("Пульс такси", "Такси пульсы"), appText("На линии, активные заказы, счётчики дня по городам", "Линияла, актив заказдар, көн һандары ҡалалар буйынса"), onClick = onTaxiPulse)
                    SettingsNavRow(Icons.Default.Campaign, appText("Лист ожидания", "Көтөү исемлеге"), appText("Ранний доступ: кто ждёт запуска, волны приглашений", "Иртә инеү: кем көтә, саҡырыу тулҡындары"), onClick = onWaitlist)
                    SettingsNavRow(Icons.Default.Report, appText("Жалобы", "Ялыуҙар"), appText("Разобрать жалобы пользователей", "Ҡулланыусы ялыуҙарын тикшереү"), onClick = onReports)
                    SettingsNavRow(Icons.Default.Storefront, appText("Бизнесы-партнёры", "Партнёр-бизнестар"), appText("Модерация: одобрить купонных партнёров", "Модерация: купон партнёрҙарын раҫлау"), onClick = onPartners)
                    SettingsNavRow(Icons.Default.Loyalty, appText("Промокоды и кампании", "Промокодтар һәм акциялар"), appText("Коды для блогеров и акций, статистика", "Блогерҙар һәм акциялар өсөн кодтар, статистика"), onClick = onPromoAdmin)
                    SettingsNavRow(Icons.Default.LocalShipping, appText("Посылки", "Бандеролдәр"), appText("Доставки и собранный сбор", "Илтеүҙәр һәм йыйылған сбор"), onClick = onParcelsAdmin)
                    SettingsNavRow(Icons.Default.DeliveryDining, appText("Курьеры", "Курьерҙар"), appText("Заявки курьеров: одобрить или отклонить", "Курьер заявкалары: раҫлау йәки кире ҡағыу"), onClick = onCourierAdmin)
                }
            }
            item {
                SettingsGroup {
                    SettingsNavRow(Icons.Default.Payments, appText("Заявки на оплату", "Түләү заявкалары"), appText("Подтвердить оплату буста и донаты", "Буст түләүен раҫлау һәм донаттар"), onClick = onPaymentRequests)
                    SettingsNavRow(Icons.Default.CreditCard, appText("Реклама", "Реклама"), appText("Объявления, erid, показы и клики", "Иғландар, erid, күрһәтеү һәм баҫыу"), onClick = onAds)
                    SettingsNavRow(Icons.Default.TrendingUp, appText("Калькулятор дохода", "Килем калькуляторы"), appText("Прикинь месячную выручку и «чистыми» по маршруту", "Маршрут буйынса айлыҡ килемде һәм таҙаһын самала"), onClick = onIncomeCalc)
                }
            }
        }
    }
}

/** Админ: заявки на оплату (буст/донат) на подтверждение + счётчик подтверждённых донатов. */
@Composable
internal fun AdminPaymentRequestsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var list by remember { mutableStateOf<List<com.yuldash.app.data.PendingPaymentDto>>(emptyList()) }
    var debts by remember { mutableStateOf<List<com.yuldash.app.data.AdminDebtDto>>(emptyList()) }
    var summary by remember { mutableStateOf<com.yuldash.app.data.PaymentsSummaryDto?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val confirmedMsg = appText("Оплата подтверждена", "Түләү раҫланды")
    val rejectedMsg = appText("Отклонено", "Кире ҡағылды")
    val loadErr = appText("Не удалось загрузить. Проверь интернет.", "Йөкләп булманы. Интернетты тикшер.")
    val actionErrMsg = appText("Не получилось. Проверь сеть и повтори.", "Булманы. Сетте тикшереп ҡабатла.")
    fun reload() {
        loading = true; error = null
        scope.launch {
            ApiClient.getPaymentsSummary().onSuccess { summary = it }
            ApiClient.getAdminDebts().onSuccess { debts = it }
            ApiClient.getPendingPayments().onSuccess { list = it }.onFailure { error = loadErr }
            loading = false
        }
    }
    LaunchedEffect(Unit) { reload() }
    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Заявки на оплату", "Түләү заявкалары"), onBack) }) { padding ->
        LazyColumn(Modifier.padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
            item { Text(appText("Сверь свою карту по сумме и имени, потом подтверди — буст запустится. Донаты просто засчитываются.", "Картаңды сумма һәм исем буйынса тикшер, аҙаҡ раҫла — буст эшләй. Донаттар иҫәпләнә."), color = CanonMuted, fontSize = 14.sp, lineHeight = 19.sp) }
            // Оплата от партнёров: QR раскрывается по нажатию (не висит всегда над списком заявок).
            item {
                var showPartnerPay by remember { mutableStateOf(false) }
                Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            Modifier.fillMaxWidth().clickable { showPartnerPay = !showPartnerPay },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(appText("Оплата от партнёра", "Партнёр түләүе"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 16.sp)
                                Text(appText("QR/номер для оплаты рекламы — показать партнёру", "Реклама түләүе өсөн QR/номер — партнёрға күрһәт"), color = CanonMuted, fontSize = 13.sp, lineHeight = 18.sp)
                            }
                            Icon(
                                if (showPartnerPay) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                                contentDescription = if (showPartnerPay) appText("Свернуть", "Йый") else appText("Показать", "Күрһәт"),
                                tint = CanonMuted
                            )
                        }
                        AnimatedVisibility(showPartnerPay) {
                            SberPayBlock(SBP_PHONE_DIGITS)
                        }
                    }
                }
            }
            summary?.let { s ->
                item {
                    Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(appText("Донаты подтверждённые", "Раҫланған донаттар"), color = CanonMuted, fontSize = 13.sp)
                            Text("${s.donateCount} " + appText("чел.", "кеше") + " · ${s.donateSum} ₽", color = CanonGreen2, fontWeight = FontWeight.Black, fontSize = 18.sp)
                        }
                    }
                }
            }
            // Долги водителей по комиссии за такси (Модель А «на доверии») — на подтверждение.
            if (debts.isNotEmpty()) {
                item {
                    Text(appText("Долги за такси", "Такси бурыстары"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 16.sp)
                }
                item {
                    Text(appText("Водитель перевёл комиссию по СБП и нажал «Я оплатил». Сверь по имени и сумме — подтверди, и такси у него разблокируется.", "Водитель комиссияны СБП аша күсереп «Мин түләнем» баҫҡан. Исем һәм сумма буйынса тикшер — раҫла, такси блокан асыла."), color = CanonMuted, fontSize = 13.sp, lineHeight = 18.sp)
                }
                items(debts.size, key = { debts[it].debtId }) { i ->
                    val g = debts[i]
                    val noName = appText("Без имени", "Исемһеҙ")
                    Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(appText("Долг за такси", "Такси бурысы"), color = CanonWarn, fontWeight = FontWeight.Black, fontSize = 14.sp)
                                Text("${g.amount} ₽", color = CanonText, fontWeight = FontWeight.Black, fontSize = 18.sp)
                            }
                            Text((g.driverName.ifBlank { noName }) + (if (g.driverPhone.isNotBlank()) " · ${g.driverPhone}" else ""), color = CanonMuted, fontSize = 13.sp)
                            if (g.weeks.isNotEmpty()) Text(appText("Недели: ", "Аҙналар: ") + g.weeks.joinToString(", "), color = CanonMuted, fontSize = 12.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(onClick = { val id = g.debtId; scope.launch { ApiClient.confirmDebt(id).onSuccess { Toast.makeText(ctx, confirmedMsg, Toast.LENGTH_SHORT).show(); reload() }.onFailure { Toast.makeText(ctx, actionErrMsg, Toast.LENGTH_SHORT).show() } } }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)) { Text(appText("Подтвердить", "Раҫлау"), fontWeight = FontWeight.Bold) }
                                OutlinedButton(onClick = { val id = g.debtId; scope.launch { ApiClient.rejectDebt(id).onSuccess { Toast.makeText(ctx, rejectedMsg, Toast.LENGTH_SHORT).show(); reload() }.onFailure { Toast.makeText(ctx, actionErrMsg, Toast.LENGTH_SHORT).show() } } }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Text(appText("Отклонить", "Кире ҡағыу"), color = CanonRed, fontWeight = FontWeight.Bold) }
                            }
                        }
                    }
                }
            }
            if (loading) {
                item { Text(appText("Загрузка…", "Йөкләнә…"), color = CanonMuted) }
            } else if (error != null) {
                item { ListedError(error ?: "") { reload() } }
            } else if (list.isEmpty() && debts.isEmpty()) {
                item { ListedEmpty(appText("Нет заявок на оплату", "Түләү заявкалары юҡ"), appText("Здесь появятся оплаты буста, донаты и долги за такси на подтверждение.", "Бында буст түләүҙәре, донаттар һәм такси бурыстары раҫлауға күренер")) }
            } else if (list.isNotEmpty()) {
                items(list.size, key = { list[it].paymentId }) { i ->
                    val p = list[i]
                    val label = when (p.purpose) {
                        "donate" -> appText("Донат", "Донат")
                        "boost" -> appText("Буст поездки", "Сәфәр бусты")
                        "ad" -> appText("Реклама", "Реклама")
                        else -> p.purpose
                    }
                    val noName = appText("Без имени", "Исемһеҙ")
                    Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(label, color = CanonGreen2, fontWeight = FontWeight.Black, fontSize = 14.sp)
                                Text("${p.amount} ₽", color = CanonText, fontWeight = FontWeight.Black, fontSize = 18.sp)
                            }
                            Text((p.payerName.ifBlank { noName }) + (if (p.payerPhone.isNotBlank()) " · ${p.payerPhone}" else ""), color = CanonMuted, fontSize = 13.sp)
                            if (p.note.isNotBlank()) Text(p.note, color = CanonText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            if (p.createdAt.length >= 10) Text(p.createdAt.take(10), color = CanonMuted, fontSize = 12.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(onClick = { val id = p.paymentId; scope.launch { ApiClient.confirmPayment(id).onSuccess { Toast.makeText(ctx, confirmedMsg, Toast.LENGTH_SHORT).show(); reload() }.onFailure { Toast.makeText(ctx, actionErrMsg, Toast.LENGTH_SHORT).show() } } }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)) { Text(appText("Подтвердить", "Раҫлау"), fontWeight = FontWeight.Bold) }
                                OutlinedButton(onClick = { val id = p.paymentId; scope.launch { ApiClient.rejectPayment(id).onSuccess { Toast.makeText(ctx, rejectedMsg, Toast.LENGTH_SHORT).show(); reload() }.onFailure { Toast.makeText(ctx, actionErrMsg, Toast.LENGTH_SHORT).show() } } }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Text(appText("Отклонить", "Кире ҡағыу"), color = CanonRed, fontWeight = FontWeight.Bold) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Админ: создать заявку ЗА пользователя по телефону (после звонка «перезвоните мне»). */
@Composable
internal fun AdminRequestScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var phone by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var from by remember { mutableStateOf("") }
    var to by remember { mutableStateOf("") }
    var seats by remember { mutableStateOf("1") }
    var comment by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val okMsg = appText("Заявка создана для пользователя", "Ҡулланыусы өсөн заявка булдырылды")
    val errMsg = appText("Не удалось. Проверь данные.", "Булманы. Мәғлүмәтте тикшер.")
    val needMsg = appText("Заполни телефон, откуда и куда", "Телефон, ҡайҙан, ҡайҙа тултыр")
    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Заявка за пользователя", "Ҡулланыусы өсөн заявка"), onBack) }) { padding ->
        LazyColumn(
            Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item { Text(appText("После звонка «перезвоните мне» заполни заявку за человека — водители увидят её как обычную.", "«Шылтыратығыҙ» һуңында кеше өсөн заявка тултыр — водителдәр уны ғәҙәти күрер."), color = CanonMuted, fontSize = 14.sp, lineHeight = 19.sp) }
            item { OutlinedTextField(phone, { phone = it }, label = { Text(appText("Телефон пользователя", "Ҡулланыусы телефоны")) }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(14.dp)) }
            item { OutlinedTextField(name, { name = it }, label = { Text(appText("Имя (необязательно)", "Исем (мотлаҡ түгел)")) }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(14.dp)) }
            item { OutlinedTextField(from, { from = it }, label = { Text(appText("Откуда", "Ҡайҙан")) }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(14.dp)) }
            item { OutlinedTextField(to, { to = it }, label = { Text(appText("Куда", "Ҡайҙа")) }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(14.dp)) }
            item { OutlinedTextField(seats, { seats = it.filter { c -> c.isDigit() }.take(1) }, label = { Text(appText("Мест", "Урын")) }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(14.dp)) }
            item { OutlinedTextField(comment, { comment = it }, label = { Text(appText("Комментарий", "Аңлатма")) }, modifier = Modifier.fillMaxWidth(), minLines = 2, shape = RoundedCornerShape(14.dp)) }
            item {
                Button(
                    onClick = {
                        if (sending) return@Button
                        if (phone.isBlank() || from.isBlank() || to.isBlank()) {
                            Toast.makeText(ctx, needMsg, Toast.LENGTH_SHORT).show(); return@Button
                        }
                        sending = true
                        val s = seats.toIntOrNull()?.coerceAtLeast(1) ?: 1
                        scope.launch {
                            ApiClient.adminRequestForPhone(phone.trim(), name.trim(), from.trim(), to.trim(), s, comment.trim())
                                .onSuccess { Toast.makeText(ctx, okMsg, Toast.LENGTH_SHORT).show(); onBack() }
                                .onFailure { Toast.makeText(ctx, errMsg, Toast.LENGTH_SHORT).show(); sending = false }
                        }
                    },
                    enabled = !sending,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)
                ) { Text(appText("Создать заявку", "Заявка булдырыу"), fontWeight = FontWeight.Black) }
            }
        }
    }
}

/** Админ: отклики по заявке (из Telegram-уведомления) → принять ЗА пользователя (без интернета). */
@Composable
internal fun AdminResponsesScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var reqId by remember { mutableStateOf("") }
    var resps by remember { mutableStateOf<List<com.yuldash.app.data.ResponseDto>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    val acceptedMsg = appText("Поездка создана. Перезвоните пассажиру и водителю.", "Сәфәр булдырылды. Пассажирға һәм водителгә шылтыратығыҙ.")
    val noResp = appText("Откликов нет или заявка не найдена", "Яуап юҡ йәки заявка табылманы")
    val acceptErr = appText("Не получилось принять отклик. Проверь сеть и повтори.", "Яуапты алып булманы. Сетте тикшереп ҡабатла.")
    fun load() {
        val id = reqId.toIntOrNull() ?: return
        loading = true
        scope.launch {
            ApiClient.getRequestResponses(id)
                .onSuccess { resps = it; loading = false; if (it.isEmpty()) Toast.makeText(ctx, noResp, Toast.LENGTH_SHORT).show() }
                .onFailure { loading = false; Toast.makeText(ctx, noResp, Toast.LENGTH_SHORT).show() }
        }
    }
    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Отклики по заявке", "Заявка буйынса яуаптар"), onBack) }) { padding ->
        LazyColumn(Modifier.padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
            item { Text(appText("Из Telegram-уведомления возьми № заявки. Открой отклики и прими за пользователя после звонка.", "Telegram хәбәренән заявка № ал. Шылтыратҡас яуаптарҙы ас, ҡулланыусы өсөн ҡабул ит."), color = CanonMuted, fontSize = 14.sp, lineHeight = 19.sp) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(reqId, { reqId = it.filter { c -> c.isDigit() }.take(8) }, label = { Text(appText("№ заявки", "Заявка №")) }, modifier = Modifier.weight(1f), singleLine = true, shape = RoundedCornerShape(14.dp))
                    Spacer(Modifier.width(10.dp))
                    Button(onClick = { load() }, shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)) { Text(appText("Открыть", "Асыу"), fontWeight = FontWeight.Bold) }
                }
            }
            if (loading) item { Text(appText("Загрузка…", "Йөкләнә…"), color = CanonMuted) }
            items(resps.size, key = { resps[it].id }) { i ->
                val r = resps[i]
                Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SmallAvatar(r.driverAvatar, r.driverName, 42)
                            Spacer(Modifier.width(10.dp))
                            Text(r.driverName, color = CanonText, fontWeight = FontWeight.Black, fontSize = 16.sp)
                            r.driverRating?.let { Spacer(Modifier.width(6.dp)); Text("★ $it", color = CanonMuted, fontSize = 13.sp) }
                            Spacer(Modifier.weight(1f))
                            if (r.price > 0) Text("${r.price} ₽", color = CanonGreen2, fontWeight = FontWeight.Black, fontSize = 18.sp)
                        }
                        if (r.comment.isNotBlank()) Text(r.comment, color = CanonMuted, fontSize = 14.sp)
                        if (r.status == "accepted") {
                            Text(appText("Принято", "Ҡабул ителде"), color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        } else {
                            Button(
                                onClick = {
                                    val id = r.id
                                    scope.launch { ApiClient.acceptResponse(id).onSuccess { Toast.makeText(ctx, acceptedMsg, Toast.LENGTH_LONG).show(); load() }.onFailure { Toast.makeText(ctx, acceptErr, Toast.LENGTH_SHORT).show() } }
                                },
                                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)
                            ) { Text(appText("Принять за пользователя", "Ҡулланыусы өсөн ҡабул итеү"), fontWeight = FontWeight.Black) }
                        }
                    }
                }
            }
        }
    }
}

/** Чёрный список: кого заблокировал (разблокировать) + попутчики, кого можно заблокировать. */
@Composable
internal fun BlocklistScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var blocks by remember { mutableStateOf<List<com.yuldash.app.data.BlockDto>>(emptyList()) }
    var partners by remember { mutableStateOf<List<com.yuldash.app.data.ReportableUserDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val blockedMsg = appText("Добавлен в чёрный список", "Ҡара исемлеккә өҫтәлде")
    val loadErr = appText("Не удалось загрузить. Проверь интернет.", "Йөкләп булманы. Интернетты тикшер.")
    val actionErr = appText("Не получилось. Проверь сеть и повтори.", "Булманы. Сетте тикшереп ҡабатла.")
    fun reload() {
        loading = true; error = null
        scope.launch {
            // Ошибку ловим по основному списку (getBlocks) — иначе сбой сети выглядит как «список пуст».
            ApiClient.getBlocks().onSuccess { blocks = it }.onFailure { error = loadErr }
            ApiClient.getReportableUsers().onSuccess { partners = it }
            loading = false
        }
    }
    LaunchedEffect(Unit) { reload() }
    val blockedIds = blocks.map { it.blockedUserId }.toSet()
    val addable = partners.filter { it.id !in blockedIds }
    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Чёрный список", "Ҡара исемлек"), onBack) }) { padding ->
        BlocklistContent(
            loading = loading,
            error = error,
            blocks = blocks,
            addable = addable,
            onRetry = { reload() },
            onUnblock = { id -> scope.launch { ApiClient.unblockUser(id).onSuccess { reload() }.onFailure { Toast.makeText(ctx, actionErr, Toast.LENGTH_SHORT).show() } } },
            onBlock = { id -> scope.launch { ApiClient.blockUser(id).onSuccess { reload(); Toast.makeText(ctx, blockedMsg, Toast.LENGTH_SHORT).show() }.onFailure { Toast.makeText(ctx, actionErr, Toast.LENGTH_SHORT).show() } } },
            modifier = Modifier.padding(padding),
        )
    }
}

/**
 * Чистый рендер чёрного списка: все состояния (загрузка / ошибка+повтор / пусто / список + секция
 * «Ваши попутчики»). `blocks`/`addable` уже вычислены выше, колбэки принимают id → без сети/стейта →
 * тестируется на JVM (Robolectric).
 */
@Composable
internal fun BlocklistContent(
    loading: Boolean,
    error: String?,
    blocks: List<com.yuldash.app.data.BlockDto>,
    addable: List<com.yuldash.app.data.ReportableUserDto>,
    onRetry: () -> Unit,
    onUnblock: (Int) -> Unit,
    onBlock: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        item { Text(appText("Заблокированные не видят ваши поездки и не могут писать.", "Блоктағылар сәфәрегеҙҙе күрмәй һәм яҙа алмай."), color = CanonMuted, fontSize = 14.sp, lineHeight = 19.sp) }
        if (loading) {
            item { Text(appText("Загрузка…", "Йөкләнә…"), color = CanonMuted) }
        } else if (error != null) {
            item { ListedError(error) { onRetry() } }
        } else {
            if (blocks.isEmpty()) {
                item {
                    Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
                        Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Default.Block, contentDescription = null, tint = CanonMuted, modifier = Modifier.size(34.dp))
                            Text(appText("Чёрный список пуст", "Ҡара исемлек буш"), color = CanonText, fontWeight = FontWeight.Black)
                        }
                    }
                }
            } else {
                items(blocks.size, key = { blocks[it].blockedUserId }) { i ->
                    val b = blocks[i]
                    PersonRow(b.name, appText("Разблокировать", "Блокты алыу"), danger = false) { onUnblock(b.blockedUserId) }
                }
            }
            if (addable.isNotEmpty()) {
                item { Text(appText("Ваши попутчики", "Юлдаштарығыҙ"), color = CanonGreen, fontWeight = FontWeight.Black, fontSize = 18.sp, modifier = Modifier.padding(top = 8.dp)) }
                items(addable.size, key = { addable[it].id }) { i ->
                    val p = addable[i]
                    PersonRow(p.name, appText("Заблокировать", "Блоклау"), danger = true) { onBlock(p.id) }
                }
            }
        }
    }
}

/** Пожаловаться на попутчика (с кем была поездка) → POST /reports (категория §9 + детали). */
@Composable
internal fun ReportScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var partners by remember { mutableStateOf<List<com.yuldash.app.data.ReportableUserDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var target by remember { mutableStateOf<com.yuldash.app.data.ReportableUserDto?>(null) }
    val sentMsg = appText("Жалоба отправлена. Спасибо, разберёмся.", "Ялыу ебәрелде. Рәхмәт, тикшерербеҙ.")
    val loadErr = appText("Не удалось загрузить. Проверь интернет.", "Йөкләп булманы. Интернетты тикшер.")
    val errMsg = appText("Не удалось отправить. Проверь сеть.", "Ебәреп булманы. Селтәрҙе тикшерегеҙ.")
    fun reload() { loading = true; error = null; scope.launch { ApiClient.getReportableUsers().onSuccess { partners = it }.onFailure { error = loadErr }; loading = false } }
    LaunchedEffect(Unit) { reload() }
    target?.let { t ->
        ReportCategoryDialog(
            title = appText("Жалоба на", "Ялыу:") + " ${t.name}",
            categories = reportCategoriesAll(),
            onDismiss = { target = null },
            onSend = { category, details ->
                val id = t.id
                scope.launch {
                    ApiClient.reportUser(targetUserId = id, reason = details, category = category)
                        .onSuccess { Toast.makeText(ctx, sentMsg, Toast.LENGTH_SHORT).show() }
                        .onFailure { Toast.makeText(ctx, errMsg, Toast.LENGTH_SHORT).show() }
                }
                target = null
            },
        )
    }
    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Пожаловаться", "Ялыу"), onBack) }) { padding ->
        ReportListContent(
            loading = loading,
            error = error,
            partners = partners,
            onRetry = { reload() },
            onSelect = { p -> target = p },
            modifier = Modifier.padding(padding),
        )
    }
}

// ------------------------------ Категории жалоб (§9 Качество) ------------------------------
/** Категория жалобы для UI: id — как на сервере (закрытый перечень), названия двуязычные. */
internal data class ReportCategoryUi(val id: String, val icon: ImageVector, val ru: String, val ba: String)

/** Жалобы НА ВОДИТЕЛЯ (пассажир жалуется). */
internal fun reportCategoriesDriver(): List<ReportCategoryUi> = listOf(
    ReportCategoryUi("rude", Icons.Default.MoodBad, "Нахамил", "Тупаҫланды"),
    ReportCategoryUi("kicked_out", Icons.Default.PersonOff, "Высадил в пути", "Юлда төшөрөп ҡалдырҙы"),
    ReportCategoryUi("dangerous_driving", Icons.Default.Speed, "Опасное вождение", "Хәүефле йөрөтөү"),
    ReportCategoryUi("price_fraud", Icons.Default.Payments, "Обман с ценой", "Хаҡ менән алдау"),
    ReportCategoryUi("dirty_car", Icons.Default.CleaningServices, "Грязная машина", "Бысраҡ машина"),
    ReportCategoryUi("late", Icons.Default.Schedule, "Опоздал", "Һуңланы"),
    ReportCategoryUi("safety_threat", Icons.Default.Warning, "Угроза безопасности", "Хәүефһеҙлеккә янау"),
    ReportCategoryUi("other", Icons.Default.QuestionMark, "Другое", "Башҡа"),
)

/** Жалобы НА ПАССАЖИРА (водитель жалуется). */
internal fun reportCategoriesPassenger(): List<ReportCategoryUi> = listOf(
    ReportCategoryUi("rude", Icons.Default.MoodBad, "Нахамил", "Тупаҫланды"),
    ReportCategoryUi("no_show", Icons.Default.EventBusy, "Не пришёл к машине", "Машинаға килмәне"),
    ReportCategoryUi("damage", Icons.Default.Build, "Испортил машину", "Машинаны боҙҙо"),
    ReportCategoryUi("unpaid", Icons.Default.MoneyOff, "Не заплатил", "Түләмәне"),
    ReportCategoryUi("safety_threat", Icons.Default.Warning, "Небезопасное поведение", "Хәүефле үҙ-үҙен тотоу"),
    ReportCategoryUi("other", Icons.Default.QuestionMark, "Другое", "Башҡа"),
)

/** Полный перечень (когда роль цели неизвестна — общий экран «Пожаловаться»). */
internal fun reportCategoriesAll(): List<ReportCategoryUi> {
    val driver = reportCategoriesDriver()
    val ids = driver.map { it.id }.toSet()
    // «Другое» — всегда последним.
    return driver.dropLast(1) + reportCategoriesPassenger().filter { it.id !in ids } + driver.last()
}

/**
 * Диалог жалобы: категории из перечня §9 (иконка + двуязычное название, тач-цель 48dp),
 * поле деталей («Другое» — обязательно опиши) и честная строка «жалоба анонимна».
 * Тёплый тон: жалоба — не донос, а способ сделать сервис безопаснее.
 */
@Composable
internal fun ReportCategoryDialog(
    title: String,
    categories: List<ReportCategoryUi>,
    onDismiss: () -> Unit,
    onSend: (category: String, details: String) -> Unit,
) {
    var selected by remember { mutableStateOf<String?>(null) }
    var details by remember { mutableStateOf("") }
    val canSend = selected != null && (selected != "other" || details.isNotBlank())
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CanonSurface,
        title = { Text(title, color = CanonText, fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    appText("Что случилось? Выбери категорию:", "Ни булды? Категорияны һайла:"),
                    color = CanonMuted, fontSize = 13.sp,
                )
                Column(
                    Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    categories.forEach { c ->
                        val active = selected == c.id
                        val tint by animateColorAsState(if (active) CanonGreen2 else CanonMuted, label = "repCat")
                        Surface(
                            onClick = { selected = c.id },
                            shape = RoundedCornerShape(12.dp),
                            color = if (active) CanonGreen2.copy(alpha = 0.10f) else Color.Transparent,
                        ) {
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(c.icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    appText(c.ru, c.ba), color = CanonText, fontSize = 15.sp,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.weight(1f),
                                )
                                if (active) Icon(Icons.Default.CheckCircle, contentDescription = appText("Выбрано", "Һайланған"), tint = CanonGreen2, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
                AnimatedVisibility(selected != null) {
                    OutlinedTextField(
                        value = details, onValueChange = { details = it },
                        placeholder = {
                            Text(
                                if (selected == "other") appText("Опиши, что случилось", "Ни булғанын яҙ")
                                else appText("Детали (необязательно)", "Ентекләп (мотлаҡ түгел)")
                            )
                        },
                        modifier = Modifier.fillMaxWidth(), minLines = 2,
                    )
                }
                Text(
                    appText("Жалоба анонимна: человек не узнает, что она от тебя. Разбирает живой человек.",
                        "Ялыу аноним: кеше уның һинән икәнен белмәйәсәк. Тере кеше тикшерә."),
                    color = CanonMuted, fontSize = 12.sp, lineHeight = 16.sp,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSend,
                onClick = { onSend(selected ?: "other", details.trim()) },
            ) { Text(appText("Отправить", "Ебәреү"), color = if (canSend) CanonRed else CanonMuted, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(appText("Отмена", "Баш тартыу"), color = CanonMuted) } },
    )
}

/**
 * Чистый рендер списка «на кого пожаловаться»: все состояния (загрузка / ошибка+повтор / пусто /
 * список). Диалог жалобы держит умная обёртка (свой стейт target/reason). Клик по строке →
 * onSelect(user). Без сети/стейта → тестируется на JVM (Robolectric).
 */
@Composable
internal fun ReportListContent(
    loading: Boolean,
    error: String?,
    partners: List<com.yuldash.app.data.ReportableUserDto>,
    onRetry: () -> Unit,
    onSelect: (com.yuldash.app.data.ReportableUserDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        item { Text(appText("Выберите, на кого пожаловаться. Видят только модераторы Юлдаша.", "Кемгә ялыу икәнен һайлағыҙ. Тик Юлдаш модераторҙары күрә."), color = CanonMuted, fontSize = 14.sp, lineHeight = 19.sp) }
        if (loading) {
            item { Text(appText("Загрузка…", "Йөкләнә…"), color = CanonMuted) }
        } else if (error != null) {
            item { ListedError(error) { onRetry() } }
        } else if (partners.isEmpty()) {
            item {
                Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
                    Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.Report, contentDescription = null, tint = CanonMuted, modifier = Modifier.size(34.dp))
                        Text(appText("Пока не на кого жаловаться", "Әлегә ялыу итергә кеше юҡ"), color = CanonText, fontWeight = FontWeight.Black)
                        Text(appText("Здесь появятся попутчики после поездок.", "Бында сәфәрҙән һуң юлдаштар күренер."), color = CanonMuted, fontSize = 13.sp)
                    }
                }
            }
        } else {
            items(partners.size, key = { partners[it].id }) { i ->
                val p = partners[i]
                PersonRow(p.name, appText("Пожаловаться", "Ялыу"), danger = true) { onSelect(p) }
            }
        }
    }
}

@Composable
internal fun HelpScreen(
    ads: List<PartnerAd>,
    adStats: Map<String, AdStats>,
    onBack: () -> Unit,
    onSelectTab: (HomeTab) -> Unit,
    onAdImpression: (PartnerAd) -> Unit,
    onAdClick: (PartnerAd) -> Unit,
    onSupportChat: () -> Unit = {},   // внутренний чат поддержки Юлдаш (замена ссылки в Telegram)
) {
    // Бейдж непрочитанного на входе «Поддержка Юлдаш» (best-effort: нет сессии/сети → просто 0).
    var supportUnread by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        if (ApiClient.isLoggedIn()) ApiClient.getSupportTickets().onSuccess { supportUnread = it.unread }
    }
    val usefulAd = ads.forPlacement(AdPlacement.Help).firstOrNull { it.category == "В больницу" }
        ?: ads.forPlacement(AdPlacement.Help).firstOrNull { it.city == "Баймаҡ" }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val supportSent = appText("Заявка отправлена — мы свяжемся с вами.", "Заявка ебәрелде — һеҙҙең менән бәйләнешербеҙ.")
    val supportErr = appText("Не получилось отправить. Проверь сеть и повтори.", "Ебәреп булманы. Сетте тикшереп ҡабатла.")
    var helpQuery by remember { mutableStateOf("") }
    // FAQ строим в composable-контексте (appText), не внутри LazyColumn-лямбды.
    val faq = listOf(
        Triple(Icons.Default.Search, appText("Как найти поездку?", "Сәфәрҙе нисек табырға?"), appText(
            "Откройте вкладку «Карта» или «Поездки». В «Ближайших поездках» включите нужные фильтры (только женщины, кресло, животные) и нажмите «Поехать» — водитель получит вашу бронь и код посадки.",
            "«Карта» йәки «Сәфәрҙәр» бүлеген асығыҙ. «Яҡын сәфәрҙәр»ҙә кәрәкле фильтрҙарҙы тоҡандырығыҙ һәм «Барам» тип баҫығыҙ — водитель брондауҙы һәм ултырыу кодын ала.")),
        Triple(Icons.Default.AddRoad, appText("Как создать заявку?", "Заявканы нисек булдырырға?"), appText(
            "Вкладка «Заявка» → укажите маршрут, дату и число мест → отправьте. Водители увидят заявку и откликнутся; вы выберете подходящего во вкладке «Чат» → «Заявки».",
            "«Заявка» бүлеге → юлды, көндө һәм урын һанын күрһәтегеҙ → ебәрегеҙ. Водителдәр заявканы күреп яуап бирер; «Чат» → «Заявкалар»ҙа кәрәклеһен һайларһығыҙ.")),
        Triple(Icons.Default.Shield, appText("Как проходит проверка водителя?", "Водитель нисек тикшерелә?"), appText(
            "Водитель загружает фото прав и авто в разделе «Стать водителем». Модератор Юлдаша проверяет вручную и ставит значок «Проверен». Документы видны только модератору.",
            "Водитель «Водитель булыу» бүлегендә права һәм машина фотоһын тейәй. Юлдаш модераторы ҡулдан тикшереп «Тикшерелгән» билдәһен ҡуя. Документтар тик модераторға күренә.")),
        Triple(Icons.Default.Notifications, appText("Что делать в экстренной ситуации?", "Ашығыс хәлдә нимә эшләргә?"), appText(
            "Нажмите красную кнопку SOS («Безопасность» или активная поездка). Откроется звонок в службы 112/102/101/103, а доверенным контактам уйдёт SMS с вашими координатами.",
            "Ҡыҙыл SOS төймәһенә баҫығыҙ («Хәүефһеҙлек» йәки сәфәр барышында). 112/102/101/103-кә шылтыратыу асыла, ышаныслы кешеләргә координаталар менән SMS китә.")),
    )
    val faqFiltered = if (helpQuery.isBlank()) faq else faq.filter { it.second.contains(helpQuery.trim(), ignoreCase = true) }
    Scaffold(
        containerColor = CanonBg,
        bottomBar = { YuldashBottomBar(selectedTab = HomeTab.Profile, onSelect = onSelectTab) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 18.dp)
        ) {
            item { Spacer(Modifier.height(10.dp)) }
            item {
                Text(appText("Помощь", "Ярдам"), color = CanonGreen, fontSize = 34.sp, lineHeight = 36.sp, fontWeight = FontWeight.Black)
                Text(appText("Ответы на частые вопросы и поддержка", "Йыш һорауҙарға яуаптар һәм ярҙам"), color = CanonMuted, fontSize = 15.sp)
            }
            item {
                OutlinedTextField(
                    value = helpQuery,
                    onValueChange = { helpQuery = it },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = CanonGreen2) },
                    placeholder = { Text(appText("Поиск по вопросам", "Һорауҙар буйынса эҙләү")) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(999.dp)
                )
            }
            item { Text(appText("Популярные вопросы", "Популяр һорауҙар"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 17.sp) }
            if (faqFiltered.isEmpty()) {
                item { InfoCard(appText("Ничего не найдено", "Бер ни ҙә табылманы"), appText("Попробуйте другой запрос или напишите в поддержку ниже.", "Башҡа һорау яҙығыҙ йәки түбәндә ярҙамға мөрәжәғәт итегеҙ."), Icons.Default.Search) }
            } else {
                items(faqFiltered, key = { it.second }) { f -> ExpandableHelpRow(f.first, f.second, f.third) }
            }
            // Прямо под FAQ — тёплое приглашение написать в поддержку, если ответа не нашлось.
            item {
                Card(
                    onClick = onSupportChat,
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = CanonMint),
                    shape = CanonItemShape,
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.HeadsetMic, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(appText("Не нашёл ответ?", "Яуап тапманыңмы?"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 16.sp)
                            Text(appText("Напиши в поддержку — поможем", "Ярҙамға яҙ — ярҙам итербеҙ"), color = CanonGreen2, fontSize = 13.sp)
                        }
                        Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = CanonGreen2)
                    }
                }
            }
            item { Text(appText("Связаться с поддержкой", "Ярдам менән бәйләнеү"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 17.sp) }
            item {
                SettingsGroup {
                    // Основной способ — внутренний чат поддержки Юлдаш (переписка сохраняется, ответы приходят сюда же).
                    SettingsNavRow(Icons.Default.HeadsetMic, appText("Поддержка Юлдаш", "Юлдаш ярҙамы"), appText("Написать нам в приложении — ответим здесь", "Ҡушымтала беҙгә яҙ — ошонда яуап бирербеҙ"), onClick = onSupportChat, badge = supportUnread)
                    SettingsNavRow(Icons.Default.ChatBubble, appText("Попросить звонок", "Шылтыратыу һорау"), appText("Оставьте заявку — мы перезвоним", "Заявка ҡалдырығыҙ — шылтыратырбыҙ"), onClick = {
                        // Ждём результат: тост «отправлено» — только при успехе, иначе честная ошибка
                        // (раньше fire-and-forget + тост ДО результата → при офлайне заявка терялась молча).
                        scope.launch {
                            ApiClient.requestCallback("Поддержка из раздела «Помощь»")
                                .onSuccess { Toast.makeText(ctx, supportSent, Toast.LENGTH_SHORT).show() }
                                .onFailure { Toast.makeText(ctx, supportErr, Toast.LENGTH_SHORT).show() }
                        }
                    })
                    // Доп. вариант — Telegram (кому привычнее). Основной путь — внутренний чат выше.
                    SettingsNavRow(Icons.Default.Send, appText("Написать в Telegram", "Telegram-ға яҙыу"), appText("Дополнительно — чат в Telegram", "Өҫтәмә — Telegram чаты"), onClick = {
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/bairas_ntv")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    })
                }
            }
            item {
                InfoCard(appText("Мы отвечаем ежедневно с 9:00 до 21:00", "Беҙ көн һайын 9:00-21:00 яуап бирәбеҙ"), "", Icons.Default.Info)
            }
            usefulAd?.let { ad ->
                item {
                    PartnerAdCard(
                        ad = ad,
                        stats = adStats[ad.id] ?: AdStats(),
                        compact = true,
                        label = appText("Полезный партнёр", "Файҙалы партнёр"),
                        onImpression = onAdImpression,
                        onClick = onAdClick
                    )
                }
            }
        }
    }
}

/** Вопрос FAQ с раскрытием ответа по тапу (поиск выше реально фильтрует список). */
@Composable
private fun ExpandableHelpRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, answer: String) {
    var expanded by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = CanonSurface), shape = CanonItemShape, elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = CanonMint, shape = RoundedCornerShape(16.dp)) {
                    Icon(icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(13.dp))
                }
                Spacer(Modifier.width(14.dp))
                Text(title, color = CanonText, fontWeight = FontWeight.Black, fontSize = 17.sp, modifier = Modifier.weight(1f))
                Icon(if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight, contentDescription = null, tint = CanonMuted)
            }
            AnimatedVisibility(visible = expanded) {
                Text(answer, color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 12.dp, start = 4.dp))
            }
        }
    }
}

