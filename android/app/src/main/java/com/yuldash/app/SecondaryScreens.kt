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
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.LocalOffer
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
import androidx.compose.material.icons.filled.Sos
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
import androidx.compose.material.icons.filled.VolumeOff
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
    onOpenParcels: () -> Unit = {},
    onOpenInstantOrder: () -> Unit = {},
    onOpenRide: (Int) -> Unit = {},
    onOpenIncident: (Int) -> Unit = {},
    onOpenDriverCabinet: () -> Unit = {},
    onOpenTaxiApply: () -> Unit = {},
    onOpenCourierApply: () -> Unit = {},
    onOpenPartnerCabinet: () -> Unit = {},
    onOpenAdsCabinet: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf("all") }
    val allLabel = appText("Все", "Бөтәһе")
    val tripsLabel = appText("Поездки", "Сәфәрҙәр")
    val chatLabel = appText("Сообщения", "Хәбәрҙәр")
    val deliveryLabel = appText("Доставка", "Илтеү")
    // Здесь стояло: «сервер таких уведомлений не шлёт (все события — booking/ride/message)».
    // Это перестало быть правдой: сервер шлёт ОДИННАДЦАТЬ видов, и самый частый из них —
    // посылки (15 мест против 11 у поездок). Комментарий устарел, а по нему жил фильтр:
    // «Поездки» показывали только попутку и не показывали такси, а доставку не собирал никто.
    // Человек фильтровал «Поездки», не находил своё такси и решал, что уведомление не пришло.
    val selectedLabel = when (selected) {
        "trips" -> tripsLabel
        "chat" -> chatLabel
        "delivery" -> deliveryLabel
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
    // Тап по карточке ведёт туда, где событие видно целиком.
    // Аудит 2026-08-06: раньше открывались только бронь, заявка и обращение, а доставка и такси —
    // самая большая группа событий — молчали. Карточка при этом пружинила под пальцем, то есть
    // обещала переход. Человек читал «Курьер забрал посылку», жал и оставался на том же месте.
    fun openDeepLink(n: NotifDto) {
        markRead(n.id)
        val ref = n.refId ?: return
        when (n.refKind) {
            "booking" -> onOpenBooking(ref)
            "request" -> onOpenResponses(ref)
            "support" -> onOpenSupport(ref)
            "parcel" -> onOpenParcels()          // «Посылки»: там карточка с ходом доставки
            "instant" -> onOpenInstantOrder()    // экран такси-заказа (сам подхватывает активный)
            "ride" -> onOpenRide(ref)            // моя поездка (событие по опубликованному рейсу)
            // «Открыт разбор» / «Решение по спору» — самое тяжёлое, что бывает с аккаунтом:
            // человеку надо видеть, за что именно и на какой срок (аудит 2026-08-08, волна 19).
            "incident" -> onOpenIncident(ref)
            // Деньги и допуск к работе (аудит 2026-08-08, волна 20): долг, списание комиссии,
            // пауза такси — всё это видно в кабинете водителя; статус заявки — на её экране.
            "debt" -> onOpenDriverCabinet()
            "taxi_apply" -> onOpenTaxiApply()
            "courier_apply" -> onOpenCourierApply()
            // Деньги бизнеса (аудит 2026-08-12, волна 24): решение по бизнесу и по купону
            // ведёт в «Мой бизнес», решение по рекламе — в кабинет объявлений. Человек
            // заплатил и ждёт ответа: сказать «одобрено» и никуда не привести — половина дела.
            "partner" -> onOpenPartnerCabinet()
            "ad" -> onOpenAdsCabinet()
        }
    }

    val visible = feed.items.filter { n ->
        when (selected) {
            // «Поездки» — это ВСЁ, чем человек куда-то ехал: попутка (booking/ride) и такси.
            "trips" -> n.type == "booking" || n.type == "ride" || n.type == "taxi"
            "chat" -> n.type == "message"
            "delivery" -> n.type == "parcel"
            else -> true
        }
    }

    Scaffold(
        containerColor = CanonBg,
        bottomBar = { YuldashBottomBar(selectedTab = HomeTab.Chat, onSelect = onSelectTab) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            item { Spacer(Modifier.height(8.dp)) }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(appText("Уведомления", "Хәбәрҙәр"), color = CanonGreen, fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
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
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(appText("Мои подписки на маршрут", "Маршрут яҙылыуҙарым"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text(appText("Караулим поездку и сообщим первыми", "Сәфәрҙе күҙәтеп, беренсе булып хәбәр итәбеҙ"), color = CanonMuted, fontSize = 14.sp)
                        }
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = CanonMuted)
                    }
                }
            }
            item {
                SegmentedTabs(
                    listOf(allLabel, tripsLabel, deliveryLabel, chatLabel),
                    selectedLabel,
                    onSelect = {
                        selected = when (it) {
                            tripsLabel -> "trips"
                            deliveryLabel -> "delivery"
                            chatLabel -> "chat"
                            else -> "all"
                        }
                    }
                )
            }
            when {
                loading && feed.items.isEmpty() -> {
                    items(4) { SkeletonCard(lines = 2, modifier = Modifier.padding(vertical = 4.dp)) }
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
    // Своя иконка была у трёх видов из одиннадцати — остальные восемь показывались одинаковым
    // колокольчиком, в том числе САМЫЙ частый (посылки) и самый важный (безопасность).
    // В списке из двадцати строк иконка — это то, чем человек ищет глазами.
    "parcel" -> Icons.Default.LocalShipping
    "taxi" -> Icons.Default.LocalTaxi
    "safety" -> Icons.Default.Shield
    "money" -> Icons.Default.Payments
    "docs" -> Icons.Default.Description
    "route_watch", "request_watch" -> Icons.Default.Search
    // Виды, приехавшие с ветки аудита (реклама, купоны, быстрый заказ, приглашения друзей):
    // сервер их шлёт, значит в ленте они обязаны отличаться глазами, а не только текстом.
    "ads" -> Icons.Default.Campaign
    "coupon" -> Icons.Default.LocalOffer
    "instant" -> Icons.Default.Bolt
    "referral" -> Icons.Default.CardGiftcard
    // Водительские дела (волна 169: бейдж снят после смены машины) — это про допуск
    // к работе, а не про конкретную поездку: человек должен находить такое в ленте глазами.
    "driver" -> Icons.Default.Badge
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
                Icon(notifIcon(notif.type), contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(12.dp).size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 23.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotBlank()) {
                    Text(subtitle, color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (time.isNotBlank()) Text(time, color = CanonMuted, fontSize = 14.sp, maxLines = 1)
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
                    color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp
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
                            Text(appText("И в обратную сторону", "Кире яҡҡа ла"), color = CanonText, fontSize = 16.sp, modifier = Modifier.weight(1f))
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
                                            Toast.makeText(ctx, serverSaid(it, m), Toast.LENGTH_LONG).show()
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
                Text(appText("Активные подписки", "Әүҙем яҙылыуҙар"), color = CanonGreen, fontWeight = FontWeight.Bold, fontSize = 19.sp, modifier = Modifier.padding(top = 4.dp))
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
                                    .onFailure { Toast.makeText(ctx, serverSaid(it, failMsg), Toast.LENGTH_LONG).show() }
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
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (watch.direction == "both") "${watch.fromCity}  ⇄  ${watch.toCity}" else "${watch.fromCity}  →  ${watch.toCity}",
                    color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp
                )
                Text(
                    if (watch.direction == "both") appText("Туда и обратно", "Бара һәм ҡайта")
                    else appText("Караулим поездку", "Сәфәрҙе күҙәтәбеҙ"),
                    color = CanonMuted, fontSize = 14.sp
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            item { Spacer(Modifier.height(8.dp)) }
            item {
                Text(
                    appText("Твои данные и поездки под защитой", "Һинең мәғлүмәт һәм сәфәрҙәр һаҡланған"),
                    color = CanonMuted,
                    fontSize = 16.sp
                )
            }
            item {
                Surface(color = CanonDangerBg, shape = CanonItemShape, border = BorderStroke(1.dp, CanonDangerBorder)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = CanonDangerBg, shape = RoundedCornerShape(14.dp)) {
                            Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                // Sos-иконка сама рисует «SOS» → с Text("SOS") был дубль. Щит (как кнопка SOS).
                                Icon(Icons.Default.Shield, contentDescription = appText("Экстренный вызов", "Ашығыс саҡырыу"), tint = CanonRed, modifier = Modifier.size(34.dp))
                                Text("SOS", color = CanonRed, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(appText("Нужна помощь?", "Ярҙәм кәрәкме?"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                            Text(appText("Свяжись с экстренными службами и поддержкой Юлдаш.", "Ашығыс хеҙмәттәр һәм Юлдаш ярҙамы менән бәйлән."), color = CanonMuted, lineHeight = 25.sp)
                        }
                        Button(onClick = onSos, colors = ButtonDefaults.buttonColors(containerColor = CanonRed), shape = RoundedCornerShape(14.dp)) {
                            Text("SOS", fontWeight = FontWeight.Bold)
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
                    SettingsNavRow(Icons.Default.Person, appText("Поделиться поездкой с близким", "Сәфәрҙе яҡын кешегә ебәреү"), appText("Отправь данные о поездке близкому человеку.", "Сәфәр мәғлүмәтен яҡын кешегә ебәр."), onClick = onShareTrip)
                    SettingsNavRow(Icons.Default.Block, appText("Чёрный список", "Ҡара исемлек"), appText("Те, с кем ты не хочешь ездить.", "Сәфәр итмәҫкә теләгән ҡулланыусылар."), onClick = onBlocklist)
                    SettingsNavRow(Icons.Default.Report, appText("Пожаловаться на пользователя", "Ҡулланыусыға ялыу"), appText("Сообщи о нарушении правил или безопасности.", "Ҡағиҙә йәки хәүефһеҙлек боҙолоуын хәбәр ит."), onClick = onReport)
                    SettingsNavRow(Icons.Default.Description, appText("Правила поездок", "Сәфәр ҡағиҙәләре"), appText("Почитай правила сервиса Юлдаш.", "Юлдаш ҡағиҙәләре менән таныш."), onClick = onRules)
                }
            }
            item {
                InfoCard(appText("Безопасность в Юлдаше", "Юлдашта хәүефһеҙлек"), appText("Проверяем участников, скрываем телефон и даём быстрый SOS.", "Ҡатнашыусыларҙы тикшерәбеҙ, телефонды йәшерәбеҙ һәм тиҙ SOS бирәбеҙ."), Icons.Default.Shield)
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
            title = { Text(appText("Выйти из аккаунта?", "Иҫәптән сығаһыңмы?"), color = CanonText, fontWeight = FontWeight.Bold) },
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            item { Spacer(Modifier.height(8.dp)) }
            item {
                Text(appText("Настройки", "Көйләүҙәр"), color = CanonGreen, fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold)
                Text(appText("Настрой приложение под себя", "Ҡушымтаны үҙегеҙгә көйләгеҙ"), color = CanonMuted, fontSize = 16.sp)
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
                Text("Юлдаш © 2026", modifier = Modifier.fillMaxWidth(), color = CanonMuted, fontSize = 14.sp)
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
        title = { Text(appText("Тема оформления", "Биҙәлеш темаһы"), color = CanonText, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
        title = { Text(appText("Размер текста", "Текст ҙурлығы"), color = CanonText, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    appText("Увеличь текст во всём приложении — так удобнее читать.", "Бөтә ҡушымтала текстты ҙурайт — уҡырға уңайлыраҡ."),
                    color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp,
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
        appText("Уважай попутчика", "Юлдашыңды хөрмәт ит") to appText("Юлдаш — поездки между своими. Веди себя по-соседски и приезжай вовремя.", "Юлдаш — үҙ кешеләр араһында сәфәр. Әҙәпле һәм ваҡытлы бул."),
        appText("Договаривайтесь заранее", "Алдан килешегеҙ") to appText("Согласуй место и время встречи в чате до выезда.", "Сығышҡа тиклем осрашыу урынын һәм ваҡытын чатта килешегеҙ."),
        appText("Безопасность прежде всего", "Хәүефһеҙлек беренсе урында") to appText("Пристегнись, не отвлекай водителя, при опасности — кнопка SOS.", "Бәйлән, йөрөтөүсене борсома, хәүеф булһа — SOS төймәһе."),
        // Водитель узнаёт об этом ЗАРАНЕЕ, а не постфактум: правило, о котором не предупредили,
        // ощущается как слежка. Отдаём только то, что и так видно на улице (решение 2026-08-06).
        appText("Если кто-то нажмёт SOS", "Кемдер SOS баҫһа") to appText(
            "Близкие получат место и описание машины — цвет, модель, госномер. Имя и телефон водителя видит только дежурный Юлдаша. Это работает в обе стороны: за тебя тоже вступятся.",
            "Яҡындары урынды һәм машина һүрәтләмәһен ала — төҫө, моделе, дәүләт номеры. Йөрөтөүсенең исеме менән телефонын тик Юлдаш дежуры күрә. Был ике яҡҡа ла эшләй: һинең өсөн дә торорҙар.",
        ),
        appText("Честная оплата", "Намыҫлы түләү") to appText("Оплати поездку как договорились, переводом по СБП.", "Сәфәр өсөн килешеүсә, СБП аша түләгеҙ."),
        appText("Оставляй отзыв", "Баһа ҡалдыр") to appText("После поездки оцени попутчика — так доверие растёт у всех.", "Сәфәрҙән һуң юлдашты баһала — шулай ышаныс үҫә."),
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
                        Surface(color = CanonMint, shape = RoundedCornerShape(14.dp)) {
                            Text("${i + 1}", Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = CanonGreen2, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(t, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text(d, color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp)
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item {
                Surface(color = CanonMint, shape = CanonItemShape) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CreditCard, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(appText("Оплата напрямую водителю", "Тура йөрөтөүсегә түләү"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text(appText("Сейчас оплата — переводом по СБП на карту водителя, как договоритесь в чате.", "Хәҙер түләү — СБП аша йөрөтөүсе картаһына, чатта килешеүсә."), color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp)
                        }
                    }
                }
            }
            item {
                Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(appText("Как это работает", "Был нисек эшләй"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        PaymentStepRow("1", appText("Договоритесь о цене в чате", "Хаҡты чатта килешегеҙ"))
                        PaymentStepRow("2", appText("После поездки переведи по СБП", "Сәфәрҙән һуң СБП аша күсер"))
                        PaymentStepRow("3", appText("Оставь отзыв о попутчике", "Бер-берегеҙ тураһында баһа ҡалдырығыҙ"))
                    }
                }
            }
            item {
                // Честно о риске (не только о плюсе «без комиссии»): деньги мимо приложения.
                Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(appText("Важно знать", "Белеп ҡуй"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text(
                                appText(
                                    "Деньги идут напрямую между вами — Юлдаш их не держит и не может вернуть. Это доверие «между своими»: плати после поездки, смотри рейтинг и отзывы, а при споре напиши в поддержку — разберёмся по-человечески.",
                                    "Аҡса тура үҙ-ара күсә — Юлдаш уны тотмай һәм кире ҡайтара алмай. Был «үҙ-ара» ышаныс: сәфәрҙән һуң түлә, баһа менән фекерҙәргә ҡара, бәхәс сыҡһа ярҙамға яҙ — кешеләрсә асыҡларбыҙ.",
                                ),
                                color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp,
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
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(26.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(appText("Честно о цене", "Хаҡ тураһында асыҡтан-асыҡ"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text(appText("Как считается цена и куда идёт комиссия", "Хаҡ нисек иҫәпләнә һәм комиссия ҡайҙа китә"), color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp)
                        }
                        Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = CanonMuted)
                    }
                }
            }
            item {
                Text(appText("Скоро: оплата картой прямо в приложении.", "Тиҙҙән: ҡушымтала карта менән түләү."), color = CanonMuted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 4.dp))
            }
        }
    }
}

/**
 * «Честно о цене» — по-соседски и с гордостью объясняем, за что платят и куда идёт комиссия.
 * Никаких выдуманных цифр: попутка бесплатна (только бензин напрямую), у такси честный потолок
 * суржа ×1.5 (не ×3), комиссия водителя 3–15% по стажу — и открыто, на что она уходит.
 */
@Composable
internal fun PricingInfoScreen(onBack: () -> Unit) {
    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Честно о цене", "Хаҡ тураһында"), onBack) }) { padding ->
        LazyColumn(
            Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item {
                Surface(color = CanonMint, shape = CanonItemShape) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(appText("Мы не прячем, на что живём", "Нимә менән йәшәгәнде йәшермәйбеҙ"), color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 19.sp, lineHeight = 25.sp)
                        Text(appText("Юлдаш — между своими. Здесь ты всегда видишь, из чего цена и куда уходит каждая копейка.", "Юлдаш — үҙ кешеләр араһында. Бында хаҡтың нимәнән торғанын һәм һәр тин ҡайҙа киткәнен күрәһең."), color = CanonText, fontSize = 14.sp, lineHeight = 20.sp)
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
                        "Үҙ кешеләр араһындағы сәфәр өсөн Юлдаш бер нәмә лә алмай. Һин бензинға тура йөрөтөүсегә өҫтәйһең — күршеләрсә. Сумма алдан күренә, бөтәһенә лә намыҫлы бүленә.",
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
                        Text("⚡", fontSize = 24.sp)
                        Spacer(Modifier.width(12.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(appText("Честный сурж: максимум ×1.5", "Намыҫлы сурж: күп тигәндә ×1.5"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text(
                                appText(
                                    "Когда машин мало, цена может подрасти — но у нас потолок ×1.5, а не ×3, как у больших сервисов. И мы честно пишем, почему дороже, ещё до того, как ты вызовешь.",
                                    "Машина аҙ булғанда хаҡ бер аҙ үҫә ала — тик бездә түшәм ×1.5, ҙур сервистарҙағыса ×3 түгел. Һәм ниңә ҡиммәтерәк икәнен саҡырғанға тиклем үк яҙабыҙ.",
                                ),
                                color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp,
                            )
                        }
                    }
                }
            }
            // (в) Комиссия водителя + куда идёт
            item {
                PricingBlock(
                    icon = Icons.Default.Verified,
                    title = appText("Комиссия водителя — 3–15%", "Йөрөтөүсе комиссияһы — 3–15%"),
                    body = appText(
                        "С поездок такси Юлдаш берёт комиссию с водителя: первые 30 поездок 3%, следующие 70 — 8%, дальше 15% — так новичок пробует почти без риска, пока не раскатался. Это меньше, чем берут большие агрегаторы. У попутки комиссии нет вовсе.",
                        "Такси сәфәрҙәренән Юлдаш йөрөтөүсенән комиссия ала: тәүге ай 3%, икенсеһе 8%, артабан 15% — яңы килгән кеше шулай тәүәкәлләмәйенсә һынап ҡарай. Был ҙур агрегаторҙар алғандан аҙыраҡ. Юлдашта комиссия бөтөнләй юҡ.",
                    ),
                )
            }
            item {
                Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(appText("Куда идёт комиссия", "Комиссия ҡайҙа китә"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        PricingWhereRow(Icons.Default.Info, appText("Серверы и связь", "Серверҙар һәм бәйләнеш"), appText("чтобы карта, чат и заказы работали без сбоев", "карта, чат һәм заказдар өҙлөкһөҙ эшләһен"))
                        PricingWhereRow(Icons.Default.Map, appText("Карты и маршруты", "Карталар һәм маршруттар"), appText("оплата картографии, по которой строятся поездки", "сәфәрҙәр төҙөлгән картография түләүе"))
                        PricingWhereRow(Icons.Default.TrendingUp, appText("Развитие приложения", "Ҡушымтаны үҫтереү"), appText("новые функции и поддержка — чтобы Юлдаш рос", "яңы мөмкинлектәр һәм ярҙам — Юлдаш үҫһен өсөн"))
                        Text(appText("Мы не прячем, на что живём — сервис должен окупаться честно, без скрытых наценок.", "Нимә менән йәшәгәнде йәшермәйбеҙ — сервис йәшерен өҫтәмәләрһеҙ, намыҫлы аҡланырға тейеш."), color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp)
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
                        "Хәҙер аҡса йөрөтөүсегә СБП аша тура күсә — «ышаныс менән», үҙ-ара кеүек. Юлдаш уны тотмай. Тиҙҙән ҡушымтала карта менән түләү өҫтәйбеҙ.",
                    ),
                )
            }
            item {
                Text(appText("Цифры могут меняться — но правило одно: ты всегда видишь, за что платишь.", "Һандар үҙгәрергә мөмкин — тик ҡағиҙә бер: нимә өсөн түләгәнеңде һәр ваҡыт күрәһең."), color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(horizontal = 4.dp))
            }
        }
    }
}

@Composable
private fun PricingBlock(icon: ImageVector, title: String, body: String, accent: Color? = null) {
    Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = null, tint = accent ?: CanonGreen2, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(body, color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp)
            }
        }
    }
}

@Composable
private fun PricingWhereRow(icon: ImageVector, title: String, sub: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(sub, color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp)
        }
    }
}

@Composable
internal fun PaymentStepRow(n: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(color = CanonMint, shape = RoundedCornerShape(8.dp)) {
            Text(n, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        Spacer(Modifier.width(12.dp))
        Text(text, color = CanonText, fontSize = 16.sp)
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item { Text(appText("Эти условия применятся к «Ближайшим поездкам» автоматически при открытии карты.", "Был шарттар карта асылғанда «Яҡын сәфәрҙәргә» автомат ҡулланыла."), color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp) }
            item {
                SettingsGroup {
                    SettingSwitchRow(Icons.Default.Woman, appText("Только женщины", "Тик ҡатын-ҡыҙ"), appText("Показывать только поездки для женщин", "Тик ҡатын-ҡыҙ өсөн сәфәрҙәр"), "women" in sel) { toggle("women") }
                    SettingSwitchRow(Icons.Default.ChildCare, appText("Детское кресло", "Балалар ултырғысы"), appText("Есть детское кресло или бустер", "Балалар ултырғысы бар"), "child" in sel) { toggle("child") }
                    SettingSwitchRow(Icons.Default.Pets, appText("С животным", "Хайуан менән"), appText("Можно ехать с питомцем", "Хайуан менән барырға мөмкин"), "pets" in sel) { toggle("pets") }
                    SettingSwitchRow(Icons.Default.Luggage, appText("Багаж", "Багаж"), appText("Есть место под багаж", "Багаж өсөн урын бар"), "baggage" in sel) { toggle("baggage") }
                    SettingSwitchRow(Icons.Default.AcUnit, appText("Кондиционер", "Кондиционер"), appText("Есть кондиционер в салоне", "Салонда кондиционер бар"), "ac" in sel) { toggle("ac") }
                    SettingSwitchRow(Icons.Default.Block, appText("Некурящий", "Тартмаусы"), appText("Показывать поездки без курения", "Тартыуһыҙ сәфәрҙәр генә"), "nosmoke" in sel) { toggle("nosmoke") }
                    SettingSwitchRow(Icons.Default.VolumeOff, appText("Тихая поездка", "Тыныс сәфәр"), appText("Едем без лишних разговоров и громкой музыки", "Артыҡ һөйләшеүһеҙ, ҡысҡырып музыкаһыҙ"), "quiet" in sel) { toggle("quiet") }
                }
            }
        }
    }
}

@Composable
internal fun PersonRow(name: String, actionLabel: String, danger: Boolean, onAction: () -> Unit) {
    Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = CanonMint, shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Default.Person, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(8.dp))
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
        model = authedImageRequest(ctx, url, token),
        contentDescription = null,
        modifier = Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(14.dp)),
        contentScale = ContentScale.Crop,
    )
}

/**
 * След экрана для разбора мигающих админ-тестов. Пишет в тот же приёмник, что и сетевой клиент
 * (`ApiClient.testTrace`), чтобы события экрана и события сети легли в ОДНУ ленту по времени —
 * иначе их не сопоставить.
 *
 * Зачем понадобился. Измерение 2026-08-08 доказало: клиент отдаёт результат за 15–38 мс
 * («ВЫШЛИ из вызова успешно»), а экран все 20 секунд показывает «Загрузка…». Виновник — участок
 * между возвратом результата и записью стейта, и там до сих пор не было ни одного замера.
 * Имя потока в каждой строке отвечает на главный вопрос: продолжение возобновилось на потоке
 * теста или осталось на фоновом.
 *
 * В проде `testTrace` = null: лямбда не вызывается, строка не собирается, цена — одна проверка
 * на null. Убрать можно ровно тогда, когда причина мигания названа и закрыта.
 */
internal inline fun screenTrace(what: () -> String) {
    ApiClient.testTrace?.invoke(what())
}

/**
 * Админ: модерация водителей — умная обёртка. Держит стейт, грузит список, ходит в ApiClient/Toast.
 * Весь рендер вынесен в чистый [AdminDriversContent] → его покрывают Robolectric-тесты на JVM.
 */
@Composable
internal fun AdminDriversScreen(onBack: () -> Unit) {
    // Права и фото машины водителя — чужие документы (волна 30).
    SecureWindow()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var list by remember { mutableStateOf<List<com.yuldash.app.data.PendingDriverDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val token = remember { ApiClient.currentToken() ?: "" }
    val approvedMsg = appText("Водитель одобрен", "Йөрөтөүсе раҫланды")
    val rejectedMsg = appText("Отклонено", "Кире ҡағылды")
    val actionErrMsg = appText("Не получилось. Проверь сеть и повтори.", "Булманы. Сетте тикшереп ҡабатла.")
    val loadErr = appText("Не удалось загрузить. Проверь интернет.", "Йөкләп булманы. Интернетты тикшер.")
    // error отделяет «сеть упала» от «список пуст» — иначе админ решит, что заявок на проверку нет.
    fun reload() {
        loading = true; error = null
        screenTrace { "экран Водители: reload() позвали (поток ${Thread.currentThread().name})" }
        scope.launch {
            screenTrace { "экран Водители: корутина стартовала (поток ${Thread.currentThread().name})" }
            val r = ApiClient.getPendingDrivers()
            screenTrace { "экран Водители: результат вернулся, успех=${r.isSuccess} (поток ${Thread.currentThread().name})" }
            r.onSuccess { list = it }.onFailure { error = loadErr }
            loading = false
            screenTrace { "экран Водители: стейт записан, loading=false (поток ${Thread.currentThread().name})" }
        }
    }
    LaunchedEffect(Unit) { reload() }
    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Модерация водителей", "Йөрөтөүселәрҙе модерациялау"), onBack) }) { padding ->
        AdminDriversContent(
            loading = loading,
            error = error,
            drivers = list,
            token = token,
            onRetry = { reload() },
            onApprove = { d, genderOk -> val id = d.userId; scope.launch { ApiClient.moderateDriver(id, true, genderOk).onSuccess { Toast.makeText(ctx, approvedMsg, Toast.LENGTH_SHORT).show(); reload() }.onFailure { Toast.makeText(ctx, serverSaid(it, actionErrMsg), Toast.LENGTH_LONG).show() } } },
            onReject = { d -> val id = d.userId; scope.launch { ApiClient.moderateDriver(id, false).onSuccess { Toast.makeText(ctx, rejectedMsg, Toast.LENGTH_SHORT).show(); reload() }.onFailure { Toast.makeText(ctx, serverSaid(it, actionErrMsg), Toast.LENGTH_LONG).show() } } },
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
    // Второй параметр — подтверждение пола: true/false, если водитель его заявил, иначе null
    // («не трогать»). Так одобрение документов и подтверждение пола едут одним запросом.
    onApprove: (com.yuldash.app.data.PendingDriverDto, Boolean?) -> Unit,
    onReject: (com.yuldash.app.data.PendingDriverDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
        item { Text(appText("Проверь права и фото авто. Одобри или отклони.", "Права һәм авто фотоһын тикшер. Раҫла йәки кире ҡаҡ."), color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp) }
        if (loading) {
            item { Text(appText("Загрузка…", "Йөкләнә…"), color = CanonMuted) }
        } else if (error != null) {
            item { ListedError(error) { onRetry() } }
        } else if (drivers.isEmpty()) {
            item { ListedEmpty(appText("Нет заявок на проверку", "Тикшереүгә заявка юҡ"), appText("Здесь появятся водители, отправившие документы.", "Бында документ ебәргән йөрөтөүселәр күренер")) }
        } else {
            items(drivers.size, key = { drivers[it].userId }) { i ->
                val d = drivers[i]
                Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(d.name, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text((d.car.ifBlank { "—" }) + " · " + d.phone, color = CanonMuted, fontSize = 14.sp)
                        AutoCheckRow(d.autocheckResult, d.autocheckData)
                        Text(appText("Водительское удостоверение", "Йөрөтөүсе таныҡлығы"), color = CanonMuted, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        DocImage(d.licenseUrl, token)
                        Text(appText("Фото автомобиля", "Автомобиль фотоһы"), color = CanonMuted, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        DocImage(d.carPhotoUrl, token)
                        // Пол подтверждает модератор по фото прав, которые уже перед глазами.
                        // Пока не подтверждён — бейдж «женщина за рулём» не показывается и женские
                        // заказы такси такому водителю не приходят. Самодекларации недостаточно:
                        // иначе фильтр, который женщина включает ради безопасности, ничего не значит.
                        var confirmGender by remember(d.userId) { mutableStateOf(d.genderVerified) }
                        if (d.genderClaimed.isNotBlank()) {
                            SettingSwitchRow(
                                Icons.Default.Woman,
                                if (d.genderClaimed == "female") appText("Это женщина — подтверждаю", "Был ҡатын-ҡыҙ — раҫлайым")
                                else appText("Это мужчина — подтверждаю", "Был ир-ат — раҫлайым"),
                                appText("Сверь с фото прав. Без подтверждения бейдж и женские заказы не работают.",
                                    "Права фотоһы менән сағыштыр. Раҫлауһыҙ билдә лә, ҡатын-ҡыҙ заказы ла эшләмәй."),
                                confirmGender,
                            ) { confirmGender = it }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onApprove(d, if (d.genderClaimed.isNotBlank()) confirmGender else null) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)) { Text(appText("Одобрить", "Раҫлау"), fontWeight = FontWeight.Bold) }
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
    Surface(color = color.copy(alpha = 0.10f), shape = RoundedCornerShape(8.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, color = color, fontWeight = FontWeight.Bold, fontSize = 14.sp)
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
    fun reload() {
        loading = true; error = null
        screenTrace { "экран Жалобы: reload() позвали (поток ${Thread.currentThread().name})" }
        scope.launch {
            screenTrace { "экран Жалобы: корутина стартовала (поток ${Thread.currentThread().name})" }
            val r = ApiClient.getAdminReports()
            screenTrace { "экран Жалобы: результат вернулся, успех=${r.isSuccess} (поток ${Thread.currentThread().name})" }
            r.onSuccess { list = it }.onFailure { error = loadErr }
            loading = false
            screenTrace { "экран Жалобы: стейт записан, loading=false (поток ${Thread.currentThread().name})" }
        }
    }
    fun act(block: suspend () -> Result<Unit>) {
        scope.launch {
            block().onSuccess { Toast.makeText(ctx, doneMsg, Toast.LENGTH_SHORT).show(); reload() }
                .onFailure { Toast.makeText(ctx, serverSaid(it, actionErr), Toast.LENGTH_LONG).show() }
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
        item { Text(appText("Жалобы пользователей. Подтверди или отклони — лестница наказаний дальше считается сама. Автора видишь только ты.", "Ҡулланыусы ялыуҙары. Раҫла йәки кире ҡаҡ — язалар баҫҡысы артабан үҙе иҫәпләнә. Авторҙы тик һин күрәһең."), color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp) }
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
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            // Категория (⛔ тяжёлая — красным) + статус разбора.
                            Surface(shape = RoundedCornerShape(8.dp), color = (if (severe) CanonRed else CanonGreen2).copy(alpha = 0.12f)) {
                                Text(reportCategoryLabel(r.category), color = if (severe) CanonRed else CanonGreen2, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
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
                        Text("${r.reporterName}  →  ${r.targetName}", color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        if (r.targetPhone.isNotBlank()) Text(r.targetPhone, color = CanonMuted, fontSize = 14.sp)
                        Text(r.reason.ifBlank { appText("без деталей", "ентекһеҙ") }, color = CanonText, fontSize = 14.sp, lineHeight = 20.sp)
                        if (r.resolution.isNotBlank()) Text(appText("Решение: ", "Ҡарар: ") + r.resolution, color = CanonMuted, fontSize = 14.sp)
                        if (open) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { onResolve(r, false) }, modifier = Modifier.weight(1f).heightIn(min = 44.dp), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)) {
                                    Text(appText("Подтвердить", "Раҫлау"), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                }
                                OutlinedButton(onClick = { onReject(r) }, modifier = Modifier.weight(1f).heightIn(min = 44.dp), shape = RoundedCornerShape(14.dp)) {
                                    Text(appText("Отклонить", "Кире ҡағыу"), color = CanonRed, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                }
                            }
                            if (severe) {
                                // ⛔ Тяжёлая: пауза стоит «до разбора» — можно подтвердить, ОСТАВИВ паузу.
                                TextButton(onClick = { onResolve(r, true) }, modifier = Modifier.fillMaxWidth()) {
                                    Text(appText("Подтвердить и оставить паузу такси", "Раҫлап такси паузаһын ҡалдырыу"), color = CanonWarn, fontSize = 14.sp, fontWeight = FontWeight.Bold)
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
// Обе ветки добавляли сюда свой раздел админки: `onTextFlags` — «Помеченные тексты»
// (волна модерации), `onModeration` — очередь модерации витрин (аудит безопасности).
// При слиянии 2026-08-12 нужны оба, потерять любой = потерять целый экран кабинета.
internal fun AdminCabinetScreen(onBack: () -> Unit, onAdminRequest: () -> Unit, onAdminResponses: () -> Unit, onAds: () -> Unit, onDrivers: () -> Unit = {}, onReports: () -> Unit = {}, onPaymentRequests: () -> Unit = {}, onTaxi: () -> Unit = {}, onWaitlist: () -> Unit = {}, onTaxiPulse: () -> Unit = {}, onPartners: () -> Unit = {}, onModeration: () -> Unit = {}, onPromoAdmin: () -> Unit = {}, onParcelsAdmin: () -> Unit = {}, onCourierAdmin: () -> Unit = {}, onIncomeCalc: () -> Unit = {}, onSosFeed: () -> Unit = {}, onIncidents: () -> Unit = {}, onRatings: () -> Unit = {}, onTextFlags: () -> Unit = {}) {
    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Кабинет админа", "Админ кабинеты"), onBack) }) { padding ->
        LazyColumn(Modifier.padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
            item { Text(appText("Единый центр управления Юлдашем. Виден только администратору.", "Юлдашты идара итеү үҙәге. Тик админға күренә."), color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp) }
            item {
                SettingsGroup {
                    SettingsNavRow(Icons.Default.HeadsetMic, appText("Заявка за пользователя", "Ҡулланыусы өсөн заявка"), appText("Создать заявку после «Попросить звонок»", "«Шылтыратыу һорау»ҙан һуң заявка булдырыу"), onClick = onAdminRequest)
                    SettingsNavRow(Icons.Default.ListAlt, appText("Отклики по заявке", "Заявка буйынса яуаптар"), appText("Принять отклик за пользователя без интернета", "Интернетһыҙ ҡулланыусы өсөн яуап ҡабул итеү"), onClick = onAdminResponses)
                }
            }
            item {
                SettingsGroup {
                    SettingsNavRow(Icons.Default.Verified, appText("Модерация водителей", "Йөрөтөүселәрҙе модерациялау"), appText("Проверить права и фото, одобрить", "Права һәм фотоны тикшереп раҫлау"), onClick = onDrivers)
                    SettingsNavRow(Icons.Default.LocalTaxi, appText("Таксисты", "Таксистар"), appText("Заявки 580-ФЗ и города, где включено такси", "580-ФЗ заявкалары һәм такси ҡабыҙылған ҡалалар"), onClick = onTaxi)
                    SettingsNavRow(Icons.Default.MonitorHeart, appText("Пульс такси", "Такси пульсы"), appText("На линии, активные заказы, счётчики дня по городам", "Линияла, актив заказдар, көн һандары ҡалалар буйынса"), onClick = onTaxiPulse)
                    SettingsNavRow(Icons.Default.Campaign, appText("Лист ожидания", "Көтөү исемлеге"), appText("Ранний доступ: кто ждёт запуска, волны приглашений", "Иртә инеү: кем көтә, саҡырыу тулҡындары"), onClick = onWaitlist)
                    SettingsNavRow(Icons.Default.Report, appText("Жалобы", "Ялыуҙар"), appText("Разобрать жалобы пользователей", "Ҡулланыусы ялыуҙарын тикшереү"), onClick = onReports)
                    // Пометки ставились всегда, но лежали в счётчике: было видно ЧИСЛО за день
                    // и нельзя посмотреть, кто и за что. Теперь список.
                    SettingsNavRow(Icons.Default.Block, appText("Помеченные тексты", "Билдәләнгән текстар"), appText("Телефоны, мат и фишинг в открытых полях", "Асыҡ ҡырҙарҙа телефон, тупаҫлыҡ, фишинг"), onClick = onTextFlags)
                    // SOS-лента: раньше сигнал уходил ОДНИМ сообщением в Telegram, и если его
                    // не прочитали ночью — следа о происшествии не оставалось нигде.
                    // «Справедливость»: двусторонний разбор — сервер умел давно, экрана не было.
                    // Без этого экрана текстовые отзывы не публиковались НИКОГДА — люди писали в пустоту.
                    SettingsNavRow(Icons.Default.Star, appText("Отзывы на модерации", "Модерациялағы фекерҙәр"), appText("Одобрить текст к показу в профиле", "Текстты профилдә күрһәтергә раҫлау"), onClick = onRatings)
                    SettingsNavRow(Icons.Default.Shield, appText("Разбор споров", "Бәхәстәрҙе ҡарау"), appText("Обе версии рядом, телефоны сторон, решение с объяснением", "Ике версия ҡатар, телефондар, аңлатмалы ҡарар"), onClick = onIncidents)
                    SettingsNavRow(Icons.Default.Sos, appText("Сигналы SOS", "SOS сигналдары"), appText("Кто позвал на помощь: позвонить и отметить «принял»", "Кем ярҙам һораған: шылтыратып «ҡабул иттем» тип билдәләү"), onClick = onSosFeed)
                    SettingsNavRow(Icons.Default.Storefront, appText("Модерация витрины", "Витрина модерацияһы"), appText("Что я ещё не смотрел: бизнесы и купоны", "Ҡарамағаным: бизнестар һәм купондар"), onClick = onModeration)
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
    // Какие строки прямо сейчас в работе. Нужно против двойного тапа: подтверждение долга и
    // подтверждение платежа — операции с деньгами, и второе нажатие, пока идёт первый запрос,
    // отправляет второй такой же. Ключ — вид действия и номер строки, чтобы блокировалась
    // только нажатая карточка, а не весь список (аудит 2026-08-07).
    val busy = remember { mutableStateListOf<String>() }
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
        LazyColumn(Modifier.padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
            item { Text(appText("Сверь свою карту по сумме и имени, потом подтверди — буст запустится. Донаты просто засчитываются.", "Картаңды сумма һәм исем буйынса тикшер, аҙаҡ раҫла — буст эшләй. Донаттар иҫәпләнә."), color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp) }
            // Оплата от партнёров: QR раскрывается по нажатию (не висит всегда над списком заявок).
            item {
                var showPartnerPay by remember { mutableStateOf(false) }
                Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            Modifier.fillMaxWidth().clickable { showPartnerPay = !showPartnerPay },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(appText("Оплата от партнёра", "Партнёр түләүе"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Text(appText("QR/номер для оплаты рекламы — показать партнёру", "Реклама түләүе өсөн QR/номер — партнёрға күрһәт"), color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp)
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
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(appText("Донаты подтверждённые", "Раҫланған донаттар"), color = CanonMuted, fontSize = 14.sp)
                            Text("${s.donateCount} " + appText("чел.", "кеше") + " · ${s.donateSum} ₽", color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                        }
                    }
                }
            }
            // Долги водителей по комиссии за такси (Модель А «на доверии») — на подтверждение.
            if (debts.isNotEmpty()) {
                item {
                    Text(appText("Долги за такси", "Такси бурыстары"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                item {
                    Text(appText("Водитель перевёл комиссию по СБП и нажал «Я оплатил». Сверь по имени и сумме — подтверди, и такси у него разблокируется.", "Йөрөтөүсе комиссияны СБП аша күсереп «Мин түләнем» баҫҡан. Исем һәм сумма буйынса тикшер — раҫла, такси блокан асыла."), color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp)
                }
                items(debts.size, key = { debts[it].debtId }) { i ->
                    val g = debts[i]
                    val noName = appText("Без имени", "Исемһеҙ")
                    Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(appText("Долг за такси", "Такси бурысы"), color = CanonWarn, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                // С копейками: админ сверяет эту сумму с переводом от водителя,
                                // а у того в приложении стоит точная — расходиться они не должны.
                                Text(kopToRub(g.amountKop), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                            }
                            Text((g.driverName.ifBlank { noName }) + (if (g.driverPhone.isNotBlank()) " · ${g.driverPhone}" else ""), color = CanonMuted, fontSize = 14.sp)
                            if (g.weeks.isNotEmpty()) Text(appText("Недели: ", "Аҙналар: ") + g.weeks.joinToString(", "), color = CanonMuted, fontSize = 12.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { val id = g.debtId; val k = "debt-ok-$id"; if (busy.add(k)) scope.launch { ApiClient.confirmDebt(id).onSuccess { Toast.makeText(ctx, confirmedMsg, Toast.LENGTH_SHORT).show(); reload() }.onFailure { Toast.makeText(ctx, serverSaid(it, actionErrMsg), Toast.LENGTH_LONG).show() }; busy.remove(k) } }, enabled = "debt-ok-${g.debtId}" !in busy, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)) { Text(appText("Подтвердить", "Раҫлау"), fontWeight = FontWeight.Bold) }
                                OutlinedButton(onClick = { val id = g.debtId; val k = "debt-no-$id"; if (busy.add(k)) scope.launch { ApiClient.rejectDebt(id).onSuccess { Toast.makeText(ctx, rejectedMsg, Toast.LENGTH_SHORT).show(); reload() }.onFailure { Toast.makeText(ctx, serverSaid(it, actionErrMsg), Toast.LENGTH_LONG).show() }; busy.remove(k) } }, enabled = "debt-no-${g.debtId}" !in busy, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Text(appText("Отклонить", "Кире ҡағыу"), color = CanonRed, fontWeight = FontWeight.Bold) }
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
                                Text(label, color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text("${p.amount} ₽", color = CanonText, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                            }
                            Text((p.payerName.ifBlank { noName }) + (if (p.payerPhone.isNotBlank()) " · ${p.payerPhone}" else ""), color = CanonMuted, fontSize = 14.sp)
                            if (p.note.isNotBlank()) Text(p.note, color = CanonText, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            if (p.createdAt.length >= 10) Text(p.createdAt.take(10), color = CanonMuted, fontSize = 12.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { val id = p.paymentId; val k = "pay-ok-$id"; if (busy.add(k)) scope.launch { ApiClient.confirmPayment(id).onSuccess { Toast.makeText(ctx, confirmedMsg, Toast.LENGTH_SHORT).show(); reload() }.onFailure { Toast.makeText(ctx, serverSaid(it, actionErrMsg), Toast.LENGTH_LONG).show() }; busy.remove(k) } }, enabled = "pay-ok-${p.paymentId}" !in busy, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)) { Text(appText("Подтвердить", "Раҫлау"), fontWeight = FontWeight.Bold) }
                                OutlinedButton(onClick = { val id = p.paymentId; val k = "pay-no-$id"; if (busy.add(k)) scope.launch { ApiClient.rejectPayment(id).onSuccess { Toast.makeText(ctx, rejectedMsg, Toast.LENGTH_SHORT).show(); reload() }.onFailure { Toast.makeText(ctx, serverSaid(it, actionErrMsg), Toast.LENGTH_LONG).show() }; busy.remove(k) } }, enabled = "pay-no-${p.paymentId}" !in busy, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Text(appText("Отклонить", "Кире ҡағыу"), color = CanonRed, fontWeight = FontWeight.Bold) }
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
            item { Text(appText("После «Попросить звонок» заполни заявку за человека — водители увидят её как обычную.", "«Шылтыратыу һорау»ҙан һуң кеше өсөн заявка тултыр — йөрөтөүселәр уны ғәҙәти күрер."), color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp) }
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
                                .onFailure { Toast.makeText(ctx, serverSaid(it, errMsg), Toast.LENGTH_LONG).show(); sending = false }
                        }
                    },
                    enabled = !sending,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)
                ) { Text(appText("Создать заявку", "Заявка булдырыу"), fontWeight = FontWeight.Bold) }
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
    // Отклики, приём которых прямо сейчас в работе — против двойного тапа (см. кнопку ниже).
    val accepting = remember { mutableStateListOf<Int>() }
    val acceptedMsg = appText("Поездка создана. Перезвони пассажиру и водителю.", "Сәфәр булдырылды. Пассажирға һәм йөрөтөүсегә шылтырат.")
    val noResp = appText("Откликов нет или заявка не найдена", "Яуап юҡ йәки заявка табылманы")
    val acceptErr = appText("Не получилось принять отклик. Проверь сеть и повтори.", "Яуапты алып булманы. Сетте тикшереп ҡабатла.")
    fun load() {
        val id = reqId.toIntOrNull() ?: return
        loading = true
        scope.launch {
            ApiClient.getRequestResponses(id)
                .onSuccess { resps = it; loading = false; if (it.isEmpty()) Toast.makeText(ctx, noResp, Toast.LENGTH_SHORT).show() }
                .onFailure { loading = false; Toast.makeText(ctx, serverSaid(it, noResp), Toast.LENGTH_LONG).show() }
        }
    }
    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Отклики по заявке", "Заявка буйынса яуаптар"), onBack) }) { padding ->
        LazyColumn(Modifier.padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
            item { Text(appText("Из Telegram-уведомления возьми № заявки. Открой отклики и прими за пользователя после звонка.", "Telegram хәбәренән заявка № ал. Шылтыратҡас яуаптарҙы ас, ҡулланыусы өсөн ҡабул ит."), color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(reqId, { reqId = it.filter { c -> c.isDigit() }.take(8) }, label = { Text(appText("№ заявки", "Заявка №")) }, modifier = Modifier.weight(1f), singleLine = true, shape = RoundedCornerShape(14.dp))
                    Spacer(Modifier.width(8.dp))
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
                            Spacer(Modifier.width(8.dp))
                            Text(r.driverName, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            r.driverRating?.let { Spacer(Modifier.width(4.dp)); Text("★ $it", color = CanonMuted, fontSize = 14.sp) }
                            Spacer(Modifier.weight(1f))
                            if (r.price > 0) Text("${r.price} ₽", color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                        }
                        if (r.comment.isNotBlank()) Text(r.comment, color = CanonMuted, fontSize = 14.sp)
                        if (r.status == "accepted") {
                            Text(appText("Принято", "Ҡабул ителде"), color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        } else {
                            Button(
                                onClick = {
                                    val id = r.id
                                    // Двойной тап создавал ДВЕ брони на одного пассажира: приём отклика
                                    // — операция с местами в машине, а кнопка на время запроса не гасла
                                    // (аудит 2026-08-07). Гасим только нажатую карточку, не весь список.
                                    if (accepting.add(id)) scope.launch {
                                        ApiClient.acceptResponse(id)
                                            .onSuccess { Toast.makeText(ctx, acceptedMsg, Toast.LENGTH_LONG).show(); load() }
                                            .onFailure { Toast.makeText(ctx, serverSaid(it, acceptErr), Toast.LENGTH_LONG).show() }
                                        accepting.remove(id)
                                    }
                                },
                                enabled = r.id !in accepting,
                                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)
                            ) { Text(appText("Принять за пользователя", "Ҡулланыусы өсөн ҡабул итеү"), fontWeight = FontWeight.Bold) }
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
            onUnblock = { id -> scope.launch { ApiClient.unblockUser(id).onSuccess { reload() }.onFailure { Toast.makeText(ctx, serverSaid(it, actionErr), Toast.LENGTH_LONG).show() } } },
            onBlock = { id -> scope.launch { ApiClient.blockUser(id).onSuccess { reload(); Toast.makeText(ctx, blockedMsg, Toast.LENGTH_SHORT).show() }.onFailure { Toast.makeText(ctx, serverSaid(it, actionErr), Toast.LENGTH_LONG).show() } } },
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
        item { Text(appText("Заблокированные не видят твои поездки и не могут писать.", "Блоктағылар сәфәреңде күрмәй һәм яҙа алмай."), color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp) }
        if (loading) {
            item { Text(appText("Загрузка…", "Йөкләнә…"), color = CanonMuted) }
        } else if (error != null) {
            item { ListedError(error) { onRetry() } }
        } else {
            if (blocks.isEmpty()) {
                item {
                    Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
                        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Default.Block, contentDescription = null, tint = CanonMuted, modifier = Modifier.size(34.dp))
                            Text(appText("Чёрный список пуст", "Ҡара исемлек буш"), color = CanonText, fontWeight = FontWeight.Bold)
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
                item { Text(appText("Твои попутчики", "Юлдаштарың"), color = CanonGreen, fontWeight = FontWeight.Bold, fontSize = 19.sp, modifier = Modifier.padding(top = 8.dp)) }
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
    val errMsg = appText("Не удалось отправить. Проверь сеть.", "Ебәреп булманы. Селтәрҙе тикшер.")
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
                        .onFailure { Toast.makeText(ctx, serverSaid(it, errMsg), Toast.LENGTH_LONG).show() }
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
        title = { Text(title, color = CanonText, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    appText("Что случилось? Выбери категорию:", "Ни булды? Категорияны һайла:"),
                    color = CanonMuted, fontSize = 14.sp,
                )
                Column(
                    Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    categories.forEach { c ->
                        val active = selected == c.id
                        val tint by animateColorAsState(if (active) CanonGreen2 else CanonMuted, label = "repCat")
                        Surface(
                            onClick = { selected = c.id },
                            shape = RoundedCornerShape(14.dp),
                            color = if (active) CanonGreen2.copy(alpha = 0.10f) else Color.Transparent,
                        ) {
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(c.icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    appText(c.ru, c.ba), color = CanonText, fontSize = 16.sp,
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
                        // Сервер принимает 1000 знаков (ReportIn.reason). Приложение не
                        // ограничивало вовсе: человек описывал происшествие подробно, жал
                        // «отправить» и получал «проверь введённые данные» — без единого
                        // намёка, ЧТО не так. Момент для этого худший из возможных.
                        value = details, onValueChange = { details = it.take(REPORT_DETAILS_MAX) },
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
                    color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp,
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
        item { Text(appText("Выбери, на кого пожаловаться. Видят только модераторы Юлдаша.", "Кемгә ялыу икәнен һайлағыҙ. Тик Юлдаш модераторҙары күрә."), color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp) }
        if (loading) {
            item { Text(appText("Загрузка…", "Йөкләнә…"), color = CanonMuted) }
        } else if (error != null) {
            item { ListedError(error) { onRetry() } }
        } else if (partners.isEmpty()) {
            item {
                Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Default.Report, contentDescription = null, tint = CanonMuted, modifier = Modifier.size(34.dp))
                        Text(appText("Пока не на кого жаловаться", "Әлегә ялыу итергә кеше юҡ"), color = CanonText, fontWeight = FontWeight.Bold)
                        Text(appText("Здесь появятся попутчики после поездок.", "Бында сәфәрҙән һуң юлдаштар күренер."), color = CanonMuted, fontSize = 14.sp)
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
    val supportSent = appText("Заявка отправлена — мы свяжемся с тобой.", "Заявка ебәрелде — һинең менән бәйләнешербеҙ.")
    val supportErr = appText("Не получилось отправить. Проверь сеть и повтори.", "Ебәреп булманы. Сетте тикшереп ҡабатла.")
    var helpQuery by remember { mutableStateOf("") }
    // FAQ строим в composable-контексте (appText), не внутри LazyColumn-лямбды.
    val faq = listOf(
        // Первым, а не последним: между тремя сервисами человек выбирает раньше, чем ищет
        // конкретную поездку. Раньше это объяснял знак вопроса на главном экране — он
        // остался только для новичков, а постоянный дом ответа теперь здесь.
        Triple(Icons.Default.SwapHoriz, appText("Чем отличаются попутка, такси и курьер?", "Юлдаш, такси һәм курьер нимә менән айырыла?"), appText(
            "Попутка — дешевле: подсаживаешься к тому, кто и так едет туда. Такси — быстро: машина едет прямо за тобой, чуть дороже. Курьер — едешь не ты, а посылка: отвезёт тот, кто и так в пути. Переключить можно вверху главного экрана.",
            "Юлдаш — арзаныраҡ: барыбер шунда барған кешегә ултыраһың. Такси — тиҙ: машина тап һинең артыңдан килә, бер аҙ ҡиммәтерәк. Курьер — һин түгел, бандеролең бара: юлда булған кеше илтә. Баш экрандың өҫтөндә алмаштырып була.")),
        Triple(Icons.Default.Search, appText("Как найти поездку?", "Сәфәрҙе нисек табырға?"), appText(
            "Открой вкладку «Карта» или «Поездки». В «Ближайших поездках» включи нужные фильтры (только женщины, кресло, животные) и нажми «Поехать» — водитель получит твою бронь и код посадки.",
            "«Карта» йәки «Сәфәрҙәр» бүлеген ас. «Яҡын сәфәрҙәр»ҙә кәрәкле фильтрҙарҙы тоҡандыр һәм «Барам» тип баҫ — йөрөтөүсе брондауҙы һәм ултырыу кодын ала.")),
        Triple(Icons.Default.AddRoad, appText("Как создать заявку?", "Заявканы нисек булдырырға?"), appText(
            "Вкладка «Заявка» → укажи маршрут, дату и число мест → отправь. Водители увидят заявку и откликнутся; подходящего выберешь во вкладке «Чат» → «Заявки».",
            "«Заявка» бүлеге → юлды, көндө һәм урын һанын күрһәт → ебәр. Йөрөтөүселәр заявканы күреп яуап бирер; «Чат» → «Заявкалар»ҙа кәрәклеһен һайларһың.")),
        Triple(Icons.Default.Shield, appText("Как проходит проверка водителя?", "Водитель нисек тикшерелә?"), appText(
            "Водитель загружает фото прав и авто в разделе «Стать водителем». Модератор Юлдаша проверяет вручную и ставит значок «Проверен». Документы видны только модератору.",
            "Йөрөтөүсе «Йөрөтөүсе булыу» бүлегендә права һәм машина фотоһын тейәй. Юлдаш модераторы ҡулдан тикшереп «Тикшерелгән» билдәһен ҡуя. Документтар тик модераторға күренә.")),
        Triple(Icons.Default.Notifications, appText("Что делать в экстренной ситуации?", "Ашығыс хәлдә нимә эшләргә?"), appText(
            // Русский был на «вы», башкирский рядом — на «ты» («баҫ»). В ответе про SOS тон
            // особенно важен: это читают, когда страшно.
            "Нажми красную кнопку SOS («Безопасность» или активная поездка). Откроется звонок в службы 112/102/101/103, а доверенным контактам уйдёт SMS с твоими координатами.",
            "Ҡыҙыл SOS төймәһенә баҫ («Хәүефһеҙлек» йәки сәфәр барышында). 112/102/101/103-кә шылтыратыу асыла, ышаныслы кешеләргә координаталар менән SMS китә.")),
    )
    val faqFiltered = if (helpQuery.isBlank()) faq else faq.filter { it.second.contains(helpQuery.trim(), ignoreCase = true) }
    Scaffold(
        containerColor = CanonBg,
        bottomBar = { YuldashBottomBar(selectedTab = HomeTab.Profile, onSelect = onSelectTab) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            item { Spacer(Modifier.height(8.dp)) }
            item {
                Text(appText("Помощь", "Ярдам"), color = CanonGreen, fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold)
                Text(appText("Ответы на частые вопросы и поддержка", "Йыш һорауҙарға яуаптар һәм ярҙам"), color = CanonMuted, fontSize = 16.sp)
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
            item { Text(appText("Популярные вопросы", "Популяр һорауҙар"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
            if (faqFiltered.isEmpty()) {
                item { InfoCard(appText("Ничего не найдено", "Бер ни ҙә табылманы"), appText("Попробуй другой запрос или напиши в поддержку ниже.", "Башҡа һорау яҙ йәки түбәндә ярҙамға мөрәжәғәт ит."), Icons.Default.Search) }
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
                    elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.flat),
                ) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.HeadsetMic, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(appText("Не нашёл ответ?", "Яуап тапманыңмы?"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text(appText("Напиши в поддержку — поможем", "Ярҙамға яҙ — ярҙам итербеҙ"), color = CanonGreen2, fontSize = 14.sp)
                        }
                        Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = CanonGreen2)
                    }
                }
            }
            item { Text(appText("Связаться с поддержкой", "Ярдам менән бәйләнеү"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
            item {
                SettingsGroup {
                    // Основной способ — внутренний чат поддержки Юлдаш (переписка сохраняется, ответы приходят сюда же).
                    SettingsNavRow(Icons.Default.HeadsetMic, appText("Поддержка Юлдаш", "Юлдаш ярҙамы"), appText("Написать нам в приложении — ответим здесь", "Ҡушымтала беҙгә яҙ — ошонда яуап бирербеҙ"), onClick = onSupportChat, badge = supportUnread)
                    SettingsNavRow(Icons.Default.ChatBubble, appText("Попросить звонок", "Шылтыратыу һорау"), appText("Оставь заявку — мы перезвоним", "Заявка ҡалдыр — шылтыратырбыҙ"), onClick = {
                        // Ждём результат: тост «отправлено» — только при успехе, иначе честная ошибка
                        // (раньше fire-and-forget + тост ДО результата → при офлайне заявка терялась молча).
                        scope.launch {
                            ApiClient.requestCallback("Поддержка из раздела «Помощь»")
                                .onSuccess { Toast.makeText(ctx, supportSent, Toast.LENGTH_SHORT).show() }
                                .onFailure { Toast.makeText(ctx, serverSaid(it, supportErr), Toast.LENGTH_LONG).show() }
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
    Card(colors = CardDefaults.cardColors(containerColor = CanonSurface), shape = CanonItemShape, elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.card)) {
        Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = CanonMint, shape = RoundedCornerShape(14.dp)) {
                    Icon(icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(12.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text(title, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Icon(if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight, contentDescription = null, tint = CanonMuted)
            }
            AnimatedVisibility(visible = expanded) {
                Text(answer, color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 12.dp, start = 4.dp))
            }
        }
    }
}

