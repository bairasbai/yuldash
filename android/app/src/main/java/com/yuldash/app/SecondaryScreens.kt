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
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Refresh
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
    var notifsLoading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { ApiClient.getNotifications().onSuccess { serverNotifs = it }; notifsLoading = false }
    // Только реальные события с сервера. Пусто → честная заглушка (без демо-обмана «Рамиль едет»).
    val notifications = serverNotifs.map { Triple(Icons.Default.ChatBubble, it.title, it.text) }
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
            val visibleNotifications = if (cleared) emptyList() else notifications.filter { (icon, _, _) ->
                selected == "all" ||
                    (selected == "rides" && icon != Icons.Default.ChatBubble && icon != Icons.Default.Shield) ||
                    (selected == "chat" && icon == Icons.Default.ChatBubble) ||
                    (selected == "system" && icon == Icons.Default.Shield)
            }
            if (notifsLoading && !cleared) {
                item { Text(appText("Загрузка…", "Йөкләнә…"), color = CanonMuted) }
            } else if (visibleNotifications.isEmpty()) {
                item {
                    InfoCard(
                        title = appText("Уведомлений пока нет", "Хәбәрҙәр әлегә юҡ"),
                        text = appText("Новые события по поездкам, чату и профилю появятся здесь.", "Сәфәр, чат һәм профиль буйынса яңы ваҡиғалар бында күренә."),
                        icon = Icons.Default.Notifications
                    )
                }
            } else {
                items(visibleNotifications, key = { it.second }) { (icon, title, subtitle) ->
                    NotificationRow(icon = icon, title = title, subtitle = subtitle, time = "", unread = true)
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
    val ctx = LocalContext.current
    var hidePhone by remember { mutableStateOf(AppPrefs.hidePhone(ctx)) }
    var verifiedOnly by remember { mutableStateOf(AppPrefs.verifiedOnly(ctx)) }
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
                Surface(color = CanonDangerBg, shape = CanonItemShape, border = BorderStroke(1.dp, CanonDangerBorder)) {
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
                    SettingSwitchRow(Icons.Default.PhoneLocked, appText("Скрывать телефон до подтверждения", "Телефонды раҫлағанға тиклем йәшереү"), appText("Ваш номер будет скрыт до подтверждения поездки.", "Номерегеҙ сәфәр раҫланғанға тиклем йәшерелә."), hidePhone) { hidePhone = it; AppPrefs.setHidePhone(ctx, it) }
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
    var showLogoutDialog by remember { mutableStateOf(false) }
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
                }
            }
            item {
                SettingsGroup {
                    SettingsNavRow(Icons.Default.Shield, appText("Приватность", "Махсуслыҡ"), appText("Управление безопасностью и данными", "Хәүефһеҙлек һәм мәғлүмәт"), onClick = onPrivacy)
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
                    SettingsNavRow(Icons.Default.Info, appText("О приложении", "Ҡушымта тураһында"), "Версия ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
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
    fun verifiedOnly(ctx: Context) = sp(ctx).getBoolean("verified_only", false)
    fun hidePhone(ctx: Context) = sp(ctx).getBoolean("hide_phone", true)
    fun setNotifications(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("notifications", v).apply()
    fun setSounds(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("sounds", v).apply()
    fun setVerifiedOnly(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("verified_only", v).apply()
    fun setHidePhone(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("hide_phone", v).apply()
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

/** Админ: модерация водителей — права/фото авто, одобрить/отклонить. */
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
    val loadErr = appText("Не удалось загрузить. Проверь интернет.", "Йөкләп булманы. Интернетты тикшер.")
    // error отделяет «сеть упала» от «список пуст» — иначе админ решит, что заявок на проверку нет.
    fun reload() { loading = true; error = null; scope.launch { ApiClient.getPendingDrivers().onSuccess { list = it }.onFailure { error = loadErr }; loading = false } }
    LaunchedEffect(Unit) { reload() }
    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Модерация водителей", "Водителдәрҙе модерациялау"), onBack) }) { padding ->
        LazyColumn(Modifier.padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
            item { Text(appText("Проверь права и фото авто. Одобри или отклони.", "Права һәм авто фотоһын тикшер. Раҫла йәки кире ҡаҡ."), color = CanonMuted, fontSize = 14.sp, lineHeight = 19.sp) }
            if (loading) {
                item { Text(appText("Загрузка…", "Йөкләнә…"), color = CanonMuted) }
            } else if (error != null) {
                item { ListedError(error!!) { reload() } }
            } else if (list.isEmpty()) {
                item { ListedEmpty(appText("Нет заявок на проверку", "Тикшереүгә заявка юҡ"), appText("Здесь появятся водители, отправившие документы.", "Бында документ ебәргән водителдәр күренер")) }
            } else {
                items(list.size) { i ->
                    val d = list[i]
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
                                Button(onClick = { val id = d.userId; scope.launch { ApiClient.moderateDriver(id, true).onSuccess { Toast.makeText(ctx, approvedMsg, Toast.LENGTH_SHORT).show(); reload() } } }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)) { Text(appText("Одобрить", "Раҫлау"), fontWeight = FontWeight.Bold) }
                                OutlinedButton(onClick = { val id = d.userId; scope.launch { ApiClient.moderateDriver(id, false).onSuccess { Toast.makeText(ctx, rejectedMsg, Toast.LENGTH_SHORT).show(); reload() } } }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Text(appText("Отклонить", "Кире ҡағыу"), color = CanonRed, fontWeight = FontWeight.Bold) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Бейдж авто-проверки прав (OCR) в карточке модерации: вердикт + распознанные данные. */
@Composable
private fun AutoCheckRow(result: String, dataJson: String) {
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

/** Админ: жалобы пользователей — кто на кого, причина, дата. */
@Composable
internal fun AdminReportsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<com.yuldash.app.data.AdminReportDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val loadErr = appText("Не удалось загрузить. Проверь интернет.", "Йөкләп булманы. Интернетты тикшер.")
    // error отделяет «сеть упала» от «жалоб нет» — иначе сбой выглядит как «всё хорошо».
    fun reload() { loading = true; error = null; scope.launch { ApiClient.getAdminReports().onSuccess { list = it }.onFailure { error = loadErr }; loading = false } }
    LaunchedEffect(Unit) { reload() }
    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Жалобы", "Ялыуҙар"), onBack) }) { padding ->
        LazyColumn(Modifier.padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
            item { Text(appText("Жалобы пользователей. Разберись — позвони, предупреди или отклони водителя в модерации.", "Ҡулланыусы ялыуҙары. Тикшер — шылтырат, иҫкәрт йәки модерацияла кире ҡаҡ."), color = CanonMuted, fontSize = 14.sp, lineHeight = 19.sp) }
            if (loading) {
                item { Text(appText("Загрузка…", "Йөкләнә…"), color = CanonMuted) }
            } else if (error != null) {
                item { ListedError(error!!) { reload() } }
            } else if (list.isEmpty()) {
                item { ListedEmpty(appText("Жалоб нет", "Ялыу юҡ"), appText("Хороший знак — пользователи довольны.", "Яҡшы билдә — ҡулланыусылар риза.")) }
            } else {
                items(list.size) { i ->
                    val r = list[i]
                    Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${r.reporterName}  →  ${r.targetName}", color = CanonText, fontWeight = FontWeight.Black, fontSize = 15.sp)
                            if (r.targetPhone.isNotBlank()) Text(r.targetPhone, color = CanonMuted, fontSize = 13.sp)
                            Text(r.reason.ifBlank { appText("без причины", "сәбәпһеҙ") }, color = CanonText, fontSize = 14.sp, lineHeight = 19.sp)
                            if (r.createdAt.length >= 10) Text(r.createdAt.take(10), color = CanonMuted, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

/** Кабинет админа — единый центр: заявки помощи, отклики, реклама. Виден только админу. */
@Composable
internal fun AdminCabinetScreen(onBack: () -> Unit, onAdminRequest: () -> Unit, onAdminResponses: () -> Unit, onAds: () -> Unit, onDrivers: () -> Unit = {}, onReports: () -> Unit = {}, onPaymentRequests: () -> Unit = {}) {
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
                    SettingsNavRow(Icons.Default.Report, appText("Жалобы", "Ялыуҙар"), appText("Разобрать жалобы пользователей", "Ҡулланыусы ялыуҙарын тикшереү"), onClick = onReports)
                }
            }
            item {
                SettingsGroup {
                    SettingsNavRow(Icons.Default.Payments, appText("Заявки на оплату", "Түләү заявкалары"), appText("Подтвердить оплату буста и донаты", "Буст түләүен раҫлау һәм донаттар"), onClick = onPaymentRequests)
                    SettingsNavRow(Icons.Default.CreditCard, appText("Реклама", "Реклама"), appText("Объявления, erid, показы и клики", "Иғландар, erid, күрһәтеү һәм баҫыу"), onClick = onAds)
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
    var summary by remember { mutableStateOf<com.yuldash.app.data.PaymentsSummaryDto?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val confirmedMsg = appText("Оплата подтверждена", "Түләү раҫланды")
    val rejectedMsg = appText("Отклонено", "Кире ҡағылды")
    val loadErr = appText("Не удалось загрузить. Проверь интернет.", "Йөкләп булманы. Интернетты тикшер.")
    fun reload() {
        loading = true; error = null
        scope.launch {
            ApiClient.getPaymentsSummary().onSuccess { summary = it }
            ApiClient.getPendingPayments().onSuccess { list = it }.onFailure { error = loadErr }
            loading = false
        }
    }
    LaunchedEffect(Unit) { reload() }
    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Заявки на оплату", "Түләү заявкалары"), onBack) }) { padding ->
        LazyColumn(Modifier.padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
            item { Text(appText("Сверь свою карту по сумме и имени, потом подтверди — буст запустится. Донаты просто засчитываются.", "Картаңды сумма һәм исем буйынса тикшер, аҙаҡ раҫла — буст эшләй. Донаттар иҫәпләнә."), color = CanonMuted, fontSize = 14.sp, lineHeight = 19.sp) }
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
            if (loading) {
                item { Text(appText("Загрузка…", "Йөкләнә…"), color = CanonMuted) }
            } else if (error != null) {
                item { ListedError(error!!) { reload() } }
            } else if (list.isEmpty()) {
                item { ListedEmpty(appText("Нет заявок на оплату", "Түләү заявкалары юҡ"), appText("Здесь появятся оплаты буста и донаты на подтверждение.", "Бында буст түләүҙәре һәм донаттар раҫлауға күренер")) }
            } else {
                items(list.size) { i ->
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
                                Button(onClick = { val id = p.paymentId; scope.launch { ApiClient.confirmPayment(id).onSuccess { Toast.makeText(ctx, confirmedMsg, Toast.LENGTH_SHORT).show(); reload() } } }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)) { Text(appText("Подтвердить", "Раҫлау"), fontWeight = FontWeight.Bold) }
                                OutlinedButton(onClick = { val id = p.paymentId; scope.launch { ApiClient.rejectPayment(id).onSuccess { Toast.makeText(ctx, rejectedMsg, Toast.LENGTH_SHORT).show(); reload() } } }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Text(appText("Отклонить", "Кире ҡағыу"), color = CanonRed, fontWeight = FontWeight.Bold) }
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
            items(resps.size) { i ->
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
                                    scope.launch { ApiClient.acceptResponse(id).onSuccess { Toast.makeText(ctx, acceptedMsg, Toast.LENGTH_LONG).show(); load() } }
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
        LazyColumn(
            Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item { Text(appText("Заблокированные не видят ваши поездки и не могут писать.", "Блоктағылар сәфәрегеҙҙе күрмәй һәм яҙа алмай."), color = CanonMuted, fontSize = 14.sp, lineHeight = 19.sp) }
            if (loading) {
                item { Text(appText("Загрузка…", "Йөкләнә…"), color = CanonMuted) }
            } else if (error != null) {
                item { ListedError(error!!) { reload() } }
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
    var error by remember { mutableStateOf<String?>(null) }
    var target by remember { mutableStateOf<com.yuldash.app.data.ReportableUserDto?>(null) }
    var reason by remember { mutableStateOf("") }
    val sentMsg = appText("Жалоба отправлена. Спасибо.", "Ялыу ебәрелде. Рәхмәт.")
    val loadErr = appText("Не удалось загрузить. Проверь интернет.", "Йөкләп булманы. Интернетты тикшер.")
    val errMsg = appText("Не удалось отправить. Проверь сеть.", "Ебәреп булманы. Селтәрҙе тикшерегеҙ.")
    fun reload() { loading = true; error = null; scope.launch { ApiClient.getReportableUsers().onSuccess { partners = it }.onFailure { error = loadErr }; loading = false } }
    LaunchedEffect(Unit) { reload() }
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
                    scope.launch {
                        ApiClient.reportUser(id, r)
                            .onSuccess { Toast.makeText(ctx, sentMsg, Toast.LENGTH_SHORT).show() }
                            .onFailure { Toast.makeText(ctx, errMsg, Toast.LENGTH_SHORT).show() }
                    }
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
            } else if (error != null) {
                item { ListedError(error!!) { reload() } }
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
    val ctx = LocalContext.current
    val supportSent = appText("Заявка отправлена — мы свяжемся с вами.", "Заявка ебәрелде — һеҙҙең менән бәйләнешербеҙ.")
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
            item { Text(appText("Связаться с поддержкой", "Ярдам менән бәйләнеү"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 17.sp) }
            item {
                SettingsGroup {
                    SettingsNavRow(Icons.Default.ChatBubble, appText("Связаться с поддержкой", "Ярҙамға яҙыу"), appText("Оставьте заявку — мы перезвоним", "Заявка ҡалдырығыҙ — шылтыратырбыҙ"), onClick = {
                        ApiClient.fireRequestCallback("Поддержка из раздела «Помощь»")
                        Toast.makeText(ctx, supportSent, Toast.LENGTH_SHORT).show()
                    })
                    SettingsNavRow(Icons.Default.HeadsetMic, appText("Написать в Telegram", "Telegram-ға яҙыу"), appText("Открыть чат поддержки Юлдаш", "Юлдаш ярҙам чатын асыу"), onClick = {
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

