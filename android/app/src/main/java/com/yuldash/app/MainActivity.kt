package com.yuldash.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        // Плавный уход системного сплэша: значок/фон мягко растворяются в интро (без резкого стыка).
        // withEndAction всегда снимает сплэш по завершении — экран не «залипнет».
        splashScreen.setOnExitAnimationListener { vp ->
            vp.view.animate().alpha(0f).setDuration(300L).withEndAction { vp.remove() }.start()
        }
        // Восстановить выбор темы день/ночь (если пользователь переключал тумблером в шапке).
        val prefs = getSharedPreferences("yuldash_theme", MODE_PRIVATE)
        if (prefs.contains("dark_override")) ThemePrefs.darkOverride = prefs.getBoolean("dark_override", false)
        setContent {
            YuldashTheme(darkTheme = appIsDark()) {
                YuldashApp()
            }
        }
    }
}

internal enum class Screen {
    Splash,
    Intro,        // брендовое интро при ПЕРВОМ запуске (морф «Попутчик»→«Юлдаш» + слоган RU→BA) → онбординг
    Onboarding,
    Login,
    Home,
    CreateRide,
    Support,
    Boost,
    Booking,
    ActiveTrip,
    Sos,
    CreateRequest,
    VerifyDriver,
    Notifications,
    Safety,
    Settings,
    Privacy,
    Rules,
    PaymentInfo,
    Blocklist,
    Report,
    Filters,
    AdminCabinet,
    AdminRequest,
    AdminResponses,
    AdminDrivers,
    AdminReports,
    RequestsFeed,
    RequestResponses,
    Help,
    PassengerCabinet,
    DriverCabinet,
    AdsCabinet,
    SimpleMode,
    VoiceRequest,
    FamilyOrder,
    TrustedContacts,
    RepeatTrip,
    CallbackHelp,
    AppReview,
    AdminReviews,
    AdminAds
}

internal enum class HomeTab {
    Map,
    Rides,
    Request,
    Chat,
    Profile
}

internal data class LocalizedText(
    val ru: String,
    val ba: String
)

internal enum class RideRole {
    Passenger,
    Driver
}

internal enum class OnboardingHero {
    Route,
    Security,
    Steps,
    Start
}

internal enum class AdPlacement {
    Nearby,
    Route,
    RidesList,
    TripDetails,
    Profile,
    Help
}

internal enum class AdStatus {
    Draft,
    Moderation,
    Active,
    Paused,
    Finished
}

internal data class OnboardingSlide(
    val eyebrowRu: String,
    val eyebrowBa: String,
    val titleRu: String,
    val titleBa: String,
    val bodyRu: String,
    val bodyBa: String,
    val hero: OnboardingHero,
    val items: List<OnboardingItem>,
    val noteRu: String? = null,
    val noteBa: String? = null
)

internal data class OnboardingItem(
    val icon: ImageVector,
    val titleRu: String,
    val titleBa: String,
    val bodyRu: String,
    val bodyBa: String
)

// Дизайн-токены (Canon*, формы, ThemePrefs, appIsDark) вынесены в CanonTokens.kt (Фаза 0).
// СБП-перевод по номеру телефона (донат/boost) — P2P, без мерчант-аккаунта. Позже вынести в конфиг/бэкенд.
private const val SBP_PHONE_DISPLAY = "+7 (999) 134-82-75"
private const val SBP_PHONE_DIGITS = "+79991348275"
private const val SBP_NAME = "Байрас"
private const val SBP_BANK = "Сбербанк"

@Composable
internal fun LocalizedText.text(): String = appText(ru, ba)

@Composable
internal fun seatsText(count: Int): String = appText("$count места", "$count урын")

@Composable
internal fun Ride.timeText(): String = appText(time, timeBa ?: time)

@Composable
internal fun Ride.carText(): String = appText(car, carBa ?: car)

@Composable
private fun PopularRoute.minutesText(): String = appText(minutes, minutesBa ?: minutes)

@Composable
private fun PopularRoute.labelText(): String = appText(label, labelBa ?: label)

@Composable
internal fun TrustedContact.relationText(): String = appText(relation, relationBa ?: relation)

@Composable
internal fun FrequentTrip.titleText(): String = appText(title, titleBa)

@Composable
internal fun FrequentTrip.timeHintText(): String = appText(timeHint, timeHintBa)

/** ISO-дата сервера "2026-06-22T22:24:07" → "22.06, 22:24" для карточки поездки. */
internal fun formatDepart(iso: String): String = try {
    val d = iso.substringBefore('T')
    val t = iso.substringAfter('T')
    "${d.substring(8, 10)}.${d.substring(5, 7)}, ${t.substring(0, 5)}"
} catch (e: Exception) {
    iso
}

/** Поездка с сервера → UI-модель (для карточек/брони). Один шов RideDto→Ride. */
internal fun com.yuldash.app.data.RideDto.toUiRide(): Ride = Ride(
    id = id.toString(),
    from = fromCity,
    to = toCity,
    time = formatDepart(departAt),
    driver = driverName.ifBlank { "Водитель" },
    driverAvatar = driverAvatar,
    driverOnline = driverOnline,
    car = driverCar,
    price = price,
    seats = seatsLeft,
    rating = driverRating,
    verified = driverVerified,
    boosted = false,
    petsAllowed = petsAllowed,
    childSeat = childSeat,
    womenOnly = womenOnly,
    smoking = smoking,
    baggage = baggage,
    airConditioner = airConditioner,
    pickup = pickup,
    pickupLat = pickupLat,
    pickupLng = pickupLng,
)

/** Километры коротко: «2.3 км» вблизи, «243 км» вдали. */
internal fun fmtKm(d: Double): String =
    if (d < 10) String.format(java.util.Locale.US, "%.1f", d) else Math.round(d).toString()

/** Категория из UI → (enum бэкенда, признак «с детьми»). */
private fun categoryToApi(ui: String): Pair<String, Boolean> = when (ui) {
    "Срочно" -> "urgent" to false
    "Посылка" -> "parcel" to false
    "С детьми" -> "regular" to true
    else -> "regular" to false
}

// Тип поездки (попутки между своими): кого/что везём. Иконка + RU/BA подпись.
internal fun rideTypeMeta(key: String): Triple<androidx.compose.ui.graphics.vector.ImageVector, String, String> = when (key) {
    "parcel" -> Triple(Icons.Default.Inventory2, "Посылка", "Посылка")
    "cargo" -> Triple(Icons.Default.LocalShipping, "Груз", "Йөк")
    "urgent" -> Triple(Icons.Default.Bolt, "Срочно", "Ашығыс")
    else -> Triple(Icons.Default.DirectionsCar, "Пассажиры", "Пассажирҙар")
}

// Заявка из строки-маршрута «Откуда → Куда» (голосовая / за близкого) → реальная серверная заявка.
internal fun fireRequestFromRoute(route: String, comment: String = "", voiceUrl: String? = null, transcript: String? = null) {
    val parts = route.split("→", "->", "-").map { it.trim() }.filter { it.isNotEmpty() }
    val from = parts.getOrElse(0) { route.trim() }
    val to = parts.getOrElse(1) { "" }
    // assisted=true: вызывается из «помощь»-режимов (голос/повтор/простой) → админ получит уведомление.
    if (from.isNotBlank()) ApiClient.fireCreateRequest(from, to, 1, "regular", false, comment, 0, voiceUrl, transcript, assisted = true)
}

// Нативный календарь + часы → строка «ДД.ММ.ГГГГ, ЧЧ:ММ» в поле даты заявки/поездки.
// localeTag — язык диалога (ru/ba), чтоб названия месяцев/кнопки были не на английском.
internal fun openDateTimePicker(context: android.content.Context, localeTag: String, onPicked: (String) -> Unit) {
    // ContextThemeWrapper сохраняет привязку к Activity (window для диалога) + override локали.
    val cfg = android.content.res.Configuration(context.resources.configuration).apply { setLocale(java.util.Locale(localeTag)) }
    val ctx = android.view.ContextThemeWrapper(context, 0).apply { applyOverrideConfiguration(cfg) }
    val cal = java.util.Calendar.getInstance()
    android.app.DatePickerDialog(
        ctx, com.yuldash.app.R.style.Theme_Yuldash_DatePicker,
        { _, y, m, d ->
            android.app.TimePickerDialog(
                ctx, com.yuldash.app.R.style.Theme_Yuldash_DatePicker,
                { _, h, min -> onPicked(String.format(java.util.Locale.getDefault(), "%02d.%02d.%d, %02d:%02d", d, m + 1, y, h, min)) },
                cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE), true
            ).show()
        },
        cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH), cal.get(java.util.Calendar.DAY_OF_MONTH)
    ).apply { datePicker.minDate = cal.timeInMillis }.show()
}

internal fun apiCategoryToUiFor(language: AppLanguage, category: String, withKids: Boolean): String = when {
    category == "urgent" -> appTextFor(language, "Срочно", "Ашығыс")
    category == "cargo" -> appTextFor(language, "Груз", "Йөк")
    category == "parcel" -> appTextFor(language, "Посылка", "Посылка")
    withKids -> appTextFor(language, "С детьми", "Балалар менән")
    else -> appTextFor(language, "Обычная", "Ғәҙәти")
}

// Доменные UI-модели (Ride, PopularRoute, TrustedContact, FrequentTrip, LocalRequest,
// LocalVoiceMessage) вынесены в Domain.kt (Фаза 0).

// Запись голоса с микрофона: MediaRecorder → m4a в кэше приложения.
internal class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var path: String? = null
    fun start(): Boolean = try {
        val f = File(context.cacheDir, "voice_${SystemClock.elapsedRealtime()}.m4a")
        path = f.absolutePath
        recorder = (if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setOutputFile(path)
            prepare()
            start()
        }
        true
    } catch (e: Exception) {
        recorder?.release(); recorder = null; false
    }
    fun stop(): String? = try {
        recorder?.stop(); recorder?.release(); recorder = null; path
    } catch (e: Exception) {
        recorder?.release(); recorder = null; null
    }
}

// Русский плюрал: 1 поездка / 2 поездки / 5 поездок.
private fun plRu(n: Int, one: String, few: String, many: String): String {
    val m10 = n % 10; val m100 = n % 100
    return when {
        m100 in 11..14 -> many
        m10 == 1 -> one
        m10 in 2..4 -> few
        else -> many
    }
}
private fun ridesRu(n: Int) = plRu(n, "поездка", "поездки", "поездок")
private fun driversRu(n: Int) = plRu(n, "водитель", "водителя", "водителей")

// 6 карточек по кругу. Периоды день/неделя/месяц/год + факт → лента «живёт».
// Числа РЕАЛЬНЫЕ с сервера (feed); офлайн (feed=null) — демо-значения, чтоб лента не выглядела пустой.
internal fun mapFeedFrom(popular: List<PopularRoute>, feed: FeedDto? = null): List<MapFeedCard> {
    val routes = popular.ifEmpty { demoPopularRoutes }
    val r0 = routes.getOrNull(0) ?: demoPopularRoutes[0]
    val r1 = routes.getOrNull(1) ?: r0
    val today = feed?.today ?: 142
    val month = feed?.month ?: 4700
    val year = feed?.year ?: 38500
    val drivers = feed?.drivers ?: 1200
    val topFrom = feed?.topFrom?.takeIf { it.isNotBlank() } ?: r1.from
    val topTo = feed?.topTo?.takeIf { it.isNotBlank() } ?: r1.to
    val topCount = feed?.topCount?.takeIf { it > 0 } ?: 320
    val topRoute = routes.firstOrNull { it.from == topFrom && it.to == topTo } ?: r1
    return listOf(
        MapFeedCard(FeedKind.Route, "Популярно", "Популяр",
            "${r0.from} → ${r0.to}", "${r0.from} → ${r0.to}",
            "${r0.nearbyCount} рядом · ${r0.distance}", "${r0.nearbyCount} яҡында · ${r0.distance}",
            r0.minutes, r0.minutesBa ?: r0.minutes, route = r0),
        MapFeedCard(FeedKind.Live, "Сегодня", "Бөгөн",
            "$today ${ridesRu(today)} за день", "Көнөнә $today сәфәр",
            "Земляки уже в пути", "Яҡташтар юлда",
            "за 24 ч", "24 сәғәт"),
        MapFeedCard(FeedKind.Top, "Хит недели", "Аҙна хиты",
            "$topFrom → $topTo", "$topFrom → $topTo",
            "Самый частый маршрут недели", "Аҙнаның иң йыш маршруты",
            "$topCount ${plRu(topCount, "раз", "раза", "раз")}", "$topCount тапҡыр", route = topRoute),
        MapFeedCard(FeedKind.Fact, "Факт", "Факт",
            "Каждая 3-я — домой на выходные", "Һәр 3-сө сәфәр — өйгә",
            "Земляки едут к родным", "Яҡташтар тыуғандарға бара",
            "78%", "78%"),
        MapFeedCard(FeedKind.Community, "За месяц", "Айға",
            "$month ${ridesRu(month)}", "Айына $month сәфәр",
            "$drivers ${driversRu(drivers)} за рулём", "Юлда $drivers водитель",
            "месяц", "ай"),
        MapFeedCard(FeedKind.Community, "За год", "Йылға",
            "$year ${ridesRu(year)}", "Йылына $year сәфәр",
            "Спасибо, что вы вместе ❤️", "Бергә булғанға рәхмәт ❤️",
            "год", "йыл")
    )
}

@Composable
internal fun Modifier.bounceClick(onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, tween(120), label = "bounce")
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = interaction, indication = null, onClick = onClick)
}

/** Карточка мягко всплывает снизу при появлении. index = задержка для каскада. */
@Composable
internal fun Modifier.appearIn(index: Int = 0): Modifier {
    var shown by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(380, delayMillis = index * 55), label = "appearAlpha")
    val ty by animateFloatAsState(if (shown) 0f else 40f, tween(380, delayMillis = index * 55), label = "appearY")
    LaunchedEffect(Unit) { shown = true }
    return this.graphicsLayer { this.alpha = alpha; translationY = ty }
}



// Перевод по СБП на номер телефона (без мерчанта). Копировать номер + инструкция + «я перевёл».
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SbpTransferSheet(amountRub: Int, onPaid: () -> Unit, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val copied = appText("Номер скопирован", "Номер күсерелде")
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = CanonSurface) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(appText("Перевод по СБП", "СБП аша күсереү"), fontSize = 22.sp, fontWeight = FontWeight.Black, color = CanonText)
            Text("$amountRub ₽", fontSize = 42.sp, fontWeight = FontWeight.Black, color = CanonGreen2)
            Surface(color = CanonMint, shape = CanonItemShape) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(appText("Получатель · СБП", "Алыусы · СБП"), color = CanonMuted, fontSize = 13.sp)
                    Text(SBP_PHONE_DISPLAY, color = CanonText, fontSize = 22.sp, fontWeight = FontWeight.Black)
                    Text("$SBP_NAME · $SBP_BANK", color = CanonMuted, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
            Text(
                appText(
                    "Откройте банк → Переводы → По номеру телефона (СБП) → банк получателя $SBP_BANK → вставьте номер и сумму $amountRub ₽.",
                    "Банк ҡушымтаһын асығыҙ → Күсереүҙәр → Телефон номеры буйынса (СБП) → алыусы банкы $SBP_BANK → номерҙы һәм $amountRub ₽ сумманы ҡуйығыҙ."
                ),
                color = CanonMuted, fontSize = 14.sp, lineHeight = 19.sp
            )
            Button(
                onClick = {
                    clipboard.setText(AnnotatedString(SBP_PHONE_DIGITS))
                    Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)
            ) {
                Text(appText("Скопировать номер", "Номерҙы күсереү"), fontWeight = FontWeight.Black)
            }
            OutlinedButton(onClick = onPaid, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(16.dp)) {
                Text(appText("Я перевёл", "Күсерҙем"))
            }
        }
    }
}
