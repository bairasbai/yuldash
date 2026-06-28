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
internal fun NotificationsScreen(onBack: () -> Unit, onSelectTab: (HomeTab) -> Unit) {
    var selected by remember { mutableStateOf("all") }
    var cleared by remember { mutableStateOf(false) }
    val allLabel = appText("Все", "Бөтәһе")
    val ridesLabel = appText("Поездки", "Сәфәрҙәр")
    val chatLabel = appText("Чат", "Чат")
    val systemLabel = appText("Система", "Система")
    val selectedLabel = when (selected) {
        "rides" -> ridesLabel
        "chat" -> chatLabel
        "system" -> systemLabel
        else -> allLabel
    }
    var serverNotifs by remember { mutableStateOf<List<NotifDto>>(emptyList()) }
    LaunchedEffect(Unit) { ApiClient.getNotifications().onSuccess { serverNotifs = it } }
    val demoNotifs = listOf(
        Triple(Icons.Default.DirectionsCar, appText("Водитель откликнулся на заявку", "Водитель заявкаға яуап бирҙе"), appText("Рамиль едет к вам", "Рамиль һеҙгә килә")),
        Triple(Icons.Default.CheckCircle, appText("Поездка подтверждена", "Сәфәр раҫланды"), appText("Баймаҡ → Сибай, сегодня в 17:30", "Баймаҡ → Сибай, бөгөн 17:30")),
        Triple(Icons.Default.ChatBubble, appText("Новое сообщение в чате", "Чатта яңы хәбәр"), appText("Рамиль: «Буду у вокзала в 17:20»", "Рамиль: «17:20-лә вокзалда булам»")),
        Triple(Icons.Default.Shield, appText("Профиль успешно проверен", "Профиль уңышлы тикшерелде"), appText("Ваш профиль подтверждён", "Профилегеҙ раҫланды")),
        Triple(Icons.Default.Schedule, appText("Поездка начнётся через 30 минут", "Сәфәр 30 минуттан башлана"), appText("Баймаҡ → Сибай, сегодня в 17:30", "Баймаҡ → Сибай, бөгөн 17:30"))
    )
    // Реальные события с сервера; демо — пока их нет (новый юзер).
    val notifications = if (serverNotifs.isNotEmpty()) serverNotifs.map { Triple(Icons.Default.ChatBubble, it.title, it.text) } else demoNotifs
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(appText("Уведомления", "Хәбәрҙәр"), modifier = Modifier.weight(1f), color = CanonGreen, fontSize = 34.sp, lineHeight = 36.sp, fontWeight = FontWeight.Black)
                    TextButton(
                        enabled = !cleared,
                        onClick = { cleared = true }
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, tint = CanonGreen2)
                        Spacer(Modifier.width(6.dp))
                        Text(appText("Очистить всё", "Барыһын таҙартыу"), color = CanonGreen2, fontWeight = FontWeight.Bold)
                    }
                }
            }
            item {
                SegmentedTabs(
                    listOf(allLabel, ridesLabel, chatLabel, systemLabel),
                    selectedLabel,
                    onSelect = {
                        selected = when (it) {
                            ridesLabel -> "rides"
                            chatLabel -> "chat"
                            systemLabel -> "system"
                            else -> "all"
                        }
                    }
                )
            }
            if (cleared) {
                item {
                    InfoCard(
                        title = appText("Уведомлений нет", "Хәбәрҙәр юҡ"),
                        text = appText("Новые события по поездкам, чату и профилю появятся здесь.", "Сәфәр, чат һәм профиль буйынса яңы ваҡиғалар бында күренә."),
                        icon = Icons.Default.Notifications
                    )
                }
            } else {
                val visibleNotifications = notifications.filter { (icon, _, _) ->
                    selected == "all" ||
                        (selected == "rides" && icon != Icons.Default.ChatBubble && icon != Icons.Default.Shield) ||
                        (selected == "chat" && icon == Icons.Default.ChatBubble) ||
                        (selected == "system" && icon == Icons.Default.Shield)
                }
                items(visibleNotifications, key = { it.second }) { (icon, title, subtitle) ->
                    NotificationRow(
                        icon = icon,
                        title = title,
                        subtitle = subtitle,
                        time = when (icon) {
                            Icons.Default.DirectionsCar -> "09:30"
                            Icons.Default.CheckCircle -> "09:28"
                            Icons.Default.ChatBubble -> "09:15"
                            Icons.Default.Shield -> appText("Вчера, 16:45", "Кисә, 16:45")
                            else -> appText("Вчера, 16:20", "Кисә, 16:20")
                        },
                        unread = icon != Icons.Default.Shield && icon != Icons.Default.Schedule
                    )
                }
            }
        }
    }
}

@Composable
private fun NotificationRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, time: String, unread: Boolean) {
    Card(colors = CardDefaults.cardColors(containerColor = CanonSurface), shape = CanonItemShape, elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = CanonMint, shape = CircleShape) {
                Icon(icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(16.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title, color = CanonText, fontWeight = FontWeight.Black, fontSize = 18.sp, lineHeight = 21.sp)
                Text(subtitle, color = CanonMuted, fontSize = 14.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text(time, color = CanonMuted, fontSize = 13.sp)
                if (unread) Box(Modifier.size(8.dp).background(CanonGreen2, CircleShape))
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
    var hidePhone by remember { mutableStateOf(true) }
    var verifiedOnly by remember { mutableStateOf(true) }
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
                Text(appText("Безопасность", "Хәүефһеҙлек"), color = CanonGreen, fontSize = 34.sp, lineHeight = 36.sp, fontWeight = FontWeight.Black)
                Text(appText("Ваши данные и поездки под защитой", "Һеҙҙең мәғлүмәт һәм сәфәрҙәр һаҡланған"), color = CanonMuted, fontSize = 15.sp)
            }
            item {
                Surface(color = CanonDangerBg, shape = CanonItemShape, border = BorderStroke(1.dp, Color(0x33D93025))) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = CanonDangerBg, shape = RoundedCornerShape(18.dp)) {
                            Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.Sos, contentDescription = null, tint = CanonRed, modifier = Modifier.size(34.dp))
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
                    SettingSwitchRow(Icons.Default.PhoneLocked, appText("Скрывать телефон до подтверждения", "Телефонды раҫлағанға тиклем йәшереү"), appText("Ваш номер будет скрыт до подтверждения поездки.", "Номерегеҙ сәфәр раҫланғанға тиклем йәшерелә."), hidePhone) { hidePhone = it }
                    SettingSwitchRow(Icons.Default.Verified, appText("Только проверенные участники", "Тик раҫланған ҡатнашыусылар"), appText("Показывать и принимать поездки только от проверенных пользователей.", "Тик раҫланған ҡулланыусылар менән эшләү."), verifiedOnly) { verifiedOnly = it }
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
    onPayments: () -> Unit = {},
    onFilters: () -> Unit = {},
    isAdmin: Boolean = false,
    onAdminRequest: () -> Unit = {},
) {
    var notifications by remember { mutableStateOf(true) }
    var sounds by remember { mutableStateOf(true) }
    var showThemeDialog by remember { mutableStateOf(false) }
    val isBashkir = LocalAppLanguage.current == AppLanguage.Ba
    // Текущая тема приложения (она же тема карты): системная / светлая / тёмная.
    val themeLabel = when (ThemePrefs.darkOverride) {
        true -> appText("Тёмная", "Ҡараңғы")
        false -> appText("Светлая", "Яҡты")
        null -> appText("Как в системе", "Системалағыса")
    }
    if (showThemeDialog) {
        ThemePickerDialog(current = ThemePrefs.darkOverride, onPick = { ThemePrefs.darkOverride = it; showThemeDialog = false }, onDismiss = { showThemeDialog = false })
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
                    SettingSwitchRow(Icons.Default.Notifications, appText("Уведомления", "Хәбәрҙәр"), appText("Получать важные обновления и напоминания", "Мөһим иҫкәртеүҙәр алыу"), notifications) { notifications = it }
                    SettingsNavRow(Icons.Default.Language, appText("Язык", "Тел"), if (isBashkir) "Башҡортса" else "Русский", onClick = onToggleLanguage)
                    SettingsNavRow(Icons.Default.Map, appText("Тема", "Тема"), themeLabel, onClick = { showThemeDialog = true })
                }
            }
            item {
                SettingsGroup {
                    SettingsNavRow(Icons.Default.Shield, appText("Приватность", "Махсуслыҡ"), appText("Управление безопасностью и данными", "Хәүефһеҙлек һәм мәғлүмәт"), onClick = onPrivacy)
                    SettingSwitchRow(Icons.Default.VolumeUp, appText("Звуки", "Тауыштар"), appText("Звуковые уведомления и эффекты", "Тауышлы хәбәрҙәр"), sounds) { sounds = it }
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
                        SettingsNavRow(Icons.Default.HeadsetMic, appText("Заявка за пользователя", "Ҡулланыусы өсөн заявка"), appText("Создать заявку после звонка «перезвоните мне»", "«Шылтыратығыҙ» һуңында заявка булдырыу"), onClick = onAdminRequest)
                    }
                }
            }
            item {
                SettingsGroup {
                    SettingsNavRow(Icons.Default.Info, appText("О приложении", "Ҡушымта тураһында"), "Версия 1.0.0 (100)")
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
internal fun PaymentInfoScreen(onBack: () -> Unit) {
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
                Text(appText("Скоро: оплата картой прямо в приложении.", "Тиҙҙән: ҡушымтала карта менән түләү."), color = CanonMuted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp))
            }
        }
    }
}

@Composable
private fun PaymentStepRow(n: String, text: String) {
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
                }
            }
        }
    }
}

@Composable
private fun PersonRow(name: String, actionLabel: String, danger: Boolean, onAction: () -> Unit) {
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

/** Чёрный список: кого заблокировал (разблокировать) + попутчики, кого можно заблокировать. */
@Composable
internal fun BlocklistScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var blocks by remember { mutableStateOf<List<com.yuldash.app.data.BlockDto>>(emptyList()) }
    var partners by remember { mutableStateOf<List<com.yuldash.app.data.ReportableUserDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    val blockedMsg = appText("Добавлен в чёрный список", "Ҡара исемлеккә өҫтәлде")
    fun reload() {
        scope.launch {
            ApiClient.getBlocks().onSuccess { blocks = it }
            ApiClient.getReportableUsers().onSuccess { partners = it }
            loading = false
        }
    }
    LaunchedEffect(Unit) { reload() }
    val blockedIds = blocks.map { it.blockedUserId }.toSet()
    val addable = partners.filter { it.id !in blockedIds }
    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Чёрный список", "Ҡара исемлек"), onBack) }) { padding ->
        LazyColumn(
            Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item { Text(appText("Заблокированные не видят ваши поездки и не могут писать.", "Блоктағылар сәфәрегеҙҙе күрмәй һәм яҙа алмай."), color = CanonMuted, fontSize = 14.sp, lineHeight = 19.sp) }
            if (loading) {
                item { Text(appText("Загрузка…", "Йөкләнә…"), color = CanonMuted) }
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
                    items(blocks.size) { i ->
                        val b = blocks[i]
                        PersonRow(b.name, appText("Разблокировать", "Блокты алыу"), danger = false) {
                            scope.launch { ApiClient.unblockUser(b.blockedUserId).onSuccess { reload() } }
                        }
                    }
                }
                if (addable.isNotEmpty()) {
                    item { Text(appText("Ваши попутчики", "Юлдаштарығыҙ"), color = CanonGreen, fontWeight = FontWeight.Black, fontSize = 18.sp, modifier = Modifier.padding(top = 8.dp)) }
                    items(addable.size) { i ->
                        val p = addable[i]
                        PersonRow(p.name, appText("Заблокировать", "Блоклау"), danger = true) {
                            scope.launch { ApiClient.blockUser(p.id).onSuccess { reload(); Toast.makeText(ctx, blockedMsg, Toast.LENGTH_SHORT).show() } }
                        }
                    }
                }
            }
        }
    }
}

/** Пожаловаться на попутчика (с кем была поездка) → POST /reports. */
@Composable
internal fun ReportScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var partners by remember { mutableStateOf<List<com.yuldash.app.data.ReportableUserDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var target by remember { mutableStateOf<com.yuldash.app.data.ReportableUserDto?>(null) }
    var reason by remember { mutableStateOf("") }
    val sentMsg = appText("Жалоба отправлена. Спасибо.", "Ялыу ебәрелде. Рәхмәт.")
    LaunchedEffect(Unit) { ApiClient.getReportableUsers().onSuccess { partners = it }; loading = false }
    target?.let { t ->
        AlertDialog(
            onDismissRequest = { target = null },
            containerColor = CanonSurface,
            title = { Text(appText("Жалоба на", "Ялыу:") + " ${t.name}", color = CanonText, fontWeight = FontWeight.Black) },
            text = {
                OutlinedTextField(
                    value = reason, onValueChange = { reason = it },
                    placeholder = { Text(appText("Что случилось?", "Ни булды?")) },
                    modifier = Modifier.fillMaxWidth(), minLines = 2
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val r = reason.trim(); val id = t.id
                    scope.launch { ApiClient.reportUser(id, r); Toast.makeText(ctx, sentMsg, Toast.LENGTH_SHORT).show() }
                    target = null; reason = ""
                }) { Text(appText("Отправить", "Ебәреү"), color = CanonRed, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { target = null }) { Text(appText("Отмена", "Баш тартыу"), color = CanonMuted) } },
        )
    }
    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Пожаловаться", "Ялыу"), onBack) }) { padding ->
        LazyColumn(
            Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item { Text(appText("Выберите, на кого пожаловаться. Видят только модераторы Юлдаша.", "Кемгә ялыу икәнен һайлағыҙ. Тик Юлдаш модераторҙары күрә."), color = CanonMuted, fontSize = 14.sp, lineHeight = 19.sp) }
            if (loading) {
                item { Text(appText("Загрузка…", "Йөкләнә…"), color = CanonMuted) }
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
                items(partners.size) { i ->
                    val p = partners[i]
                    PersonRow(p.name, appText("Пожаловаться", "Ялыу"), danger = true) { target = p; reason = "" }
                }
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
    onAdClick: (PartnerAd) -> Unit
) {
    val usefulAd = ads.forPlacement(AdPlacement.Help).firstOrNull { it.category == "В больницу" }
        ?: ads.forPlacement(AdPlacement.Help).firstOrNull { it.city == "Баймаҡ" }
    var helpQuery by remember { mutableStateOf("") }
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
            if (helpQuery.isNotBlank()) {
                item {
                    InfoCard(
                        appText("Найдено по запросу: $helpQuery", "$helpQuery буйынса табылды"),
                        appText("Откройте подходящий вопрос ниже или напишите в поддержку.", "Түбәндәге һорауҙы асығыҙ йәки ярҙамға яҙығыҙ."),
                        Icons.Default.Search
                    )
                }
            }
            item { Text(appText("Популярные вопросы", "Популяр һорауҙар"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 17.sp) }
            item { HelpRow(Icons.Default.Search, appText("Как найти поездку?", "Сәфәрҙе нисек табырға?"), appText("Поиск и фильтры, выбор водителя", "Эҙләү, фильтрҙар, водитель һайлау")) }
            item { HelpRow(Icons.Default.AddRoad, appText("Как создать заявку?", "Заявканы нисек булдырырға?"), appText("Пошаговая инструкция", "Аҙымлап аңлатма")) }
            item { HelpRow(Icons.Default.Shield, appText("Как проходит проверка водителя?", "Водитель нисек тикшерелә?"), appText("Безопасность и подтверждение", "Хәүефһеҙлек һәм раҫлау")) }
            item { HelpRow(Icons.Default.Notifications, appText("Что делать в экстренной ситуации?", "Ашығыс хәлдә нимә эшләргә?"), appText("SOS, отмена поездки, поддержка", "SOS, сәфәрҙе туҡтатыу, ярҙам")) }
            item { Text(appText("Связаться с поддержкой", "Ярдам менән бәйләнеү"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 17.sp) }
            item {
                SettingsGroup {
                    SettingsNavRow(Icons.Default.ChatBubble, appText("Связаться с поддержкой", "Ярдамға яҙыу"), appText("Мы поможем решить ваш вопрос", "Һорауығыҙҙы хәл итергә ярҙам итәбеҙ"))
                    SettingsNavRow(Icons.Default.HeadsetMic, appText("Написать в чат поддержки", "Ярдам чатына яҙыу"), appText("Онлайн-ответ в чате приложения", "Ҡушымта чатында онлайн яуап"))
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

@Composable
private fun HelpRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String) {
    Card(colors = CardDefaults.cardColors(containerColor = CanonSurface), shape = CanonItemShape, elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = CanonMint, shape = RoundedCornerShape(16.dp)) {
                Icon(icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(13.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = CanonText, fontWeight = FontWeight.Black, fontSize = 17.sp)
                Text(subtitle, color = CanonMuted, fontSize = 14.sp)
            }
            Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = CanonMuted)
        }
    }
}

