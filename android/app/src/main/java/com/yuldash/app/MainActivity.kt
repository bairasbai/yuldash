package com.yuldash.app

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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

private enum class Screen {
    Splash,
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
    Help,
    PassengerCabinet,
    DriverCabinet,
    AdsCabinet,
    SimpleMode,
    VoiceRequest,
    FamilyOrder,
    TrustedContacts,
    RepeatTrip,
    CallbackHelp
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

private enum class RideRole {
    Passenger,
    Driver
}

private enum class OnboardingHero {
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

private data class OnboardingSlide(
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

private data class OnboardingItem(
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

// Стартовый сплэш: лого появляется с масштабом+прозрачностью, текст — следом. ~1.6с → следующий экран.
@Composable
private fun SplashScreen() {
    var start by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (start) 1f else 0.62f, animationSpec = tween(820), label = "logoScale")
    val logoAlpha by animateFloatAsState(targetValue = if (start) 1f else 0f, animationSpec = tween(620), label = "logoAlpha")
    val textAlpha by animateFloatAsState(targetValue = if (start) 1f else 0f, animationSpec = tween(640, delayMillis = 380), label = "textAlpha")
    LaunchedEffect(Unit) { start = true }
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0B6B3A), Color(0xFF073F25)))),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                modifier = Modifier.size(132.dp).scale(scale).alpha(logoAlpha),
                shape = CircleShape,
                color = Color.White,
                shadowElevation = 18.dp
            ) {
                Image(
                    painter = painterResource(R.drawable.yuldash_logo),
                    contentDescription = "Юлдаш",
                    modifier = Modifier.padding(22.dp)
                )
            }
            Spacer(Modifier.height(26.dp))
            Text("Юлдаш", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Black, modifier = Modifier.alpha(textAlpha))
            Spacer(Modifier.height(6.dp))
            Text(
                appText("Поездки между своими", "Үҙебеҙҙекеләр араһында юллашыу"),
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 15.sp,
                modifier = Modifier.alpha(textAlpha)
            )
        }
    }
}

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
private fun com.yuldash.app.data.RideDto.toUiRide(): Ride = Ride(
    id = id.toString(),
    from = fromCity,
    to = toCity,
    time = formatDepart(departAt),
    driver = driverName.ifBlank { "Водитель" },
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
    if (from.isNotBlank()) ApiClient.fireCreateRequest(from, to, 1, "regular", false, comment, 0, voiceUrl, transcript)
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

private fun apiCategoryToUiFor(language: AppLanguage, category: String, withKids: Boolean): String = when {
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

private val demoTrustedContacts = listOf(
    TrustedContact("Айгуль", "Дочь", "+7 927 111-22-33", true, relationBa = "Ҡыҙы"),
    TrustedContact("Рамиль", "Сосед", "+7 927 444-55-66", false, relationBa = "Күрше")
)

internal val demoFrequentTrips = listOf(
    FrequentTrip("В больницу", "Больницаға", "Баймаҡ", "Сибай", "завтра утром", "иртәгә иртән", "hospital"),
    FrequentTrip("К детям", "Балаларға", "Баймаҡ", "Уфа", "пятница, 08:00", "йома, 08:00", "intercity"),
    FrequentTrip("На рынок", "Баҙарға", "Баймаҡ", "Сибай", "сегодня после 15:00", "бөгөн 15:00-тан һуң", "regular")
)

internal data class PartnerAd(
    val id: String,
    val title: String,
    val titleBa: String? = null,
    val description: String,
    val descriptionBa: String? = null,
    val address: String,
    val addressBa: String? = null,
    val advertiserName: String,
    val erid: String,
    val city: String,
    val routeFrom: String? = null,
    val routeTo: String? = null,
    val category: String? = null,
    val categoryBa: String? = null,
    val startDate: String,
    val endDate: String,
    val status: AdStatus,
    val placements: Set<AdPlacement>,
    val packageName: String,
    val packageNameBa: String? = null,
    val budgetLabel: String,
    val budgetLabelBa: String? = null,
    val targetAction: String,
    val targetActionBa: String? = null,
    val contact: String,
    val mapPoint: String,
    val primaryButton: String,
    val primaryButtonBa: String? = null,
    val secondaryButton: String? = null,
    val secondaryButtonBa: String? = null,
    val icon: ImageVector
)

internal data class AdStats(
    val impressions: Int = 0,
    val clicks: Int = 0
) {
    val ctrPercent: Int
        get() = if (impressions == 0) 0 else ((clicks * 100) / impressions)
}

private val demoPartnerAds = listOf(
    PartnerAd(
        id = "ad-pharmacy-hospital",
        title = "Аптека «Здоровье»",
        titleBa = "«Здоровье» дарыуханаһы",
        description = "Скидка 10% для поездок в больницу",
        descriptionBa = "Больницаға сәфәрҙәр өсөн 10% ташлама",
        address = "Баймаҡ, ул. Ленина, 12",
        advertiserName = "ООО «Аптека Здоровье»",
        erid = "2VtzqxXXXX",
        city = "Баймаҡ",
        routeFrom = "Баймаҡ",
        routeTo = "Сибай",
        category = "В больницу",
        categoryBa = "Больницаға",
        startDate = "2026-06-22",
        endDate = "2026-07-22",
        status = AdStatus.Active,
        placements = setOf(AdPlacement.Nearby, AdPlacement.Profile, AdPlacement.Help, AdPlacement.TripDetails),
        packageName = "Город + категория",
        packageNameBa = "Ҡала + категория",
        budgetLabel = "3 000 ₽ / 30 дней",
        budgetLabelBa = "3 000 ₽ / 30 көн",
        targetAction = "Открыть карточку и построить маршрут",
        targetActionBa = "Карточканы асыу һәм маршрут төҙөү",
        contact = "+7 927 000-12-12",
        mapPoint = "53.9306, 58.3142",
        primaryButton = "Открыть",
        primaryButtonBa = "Асыу",
        secondaryButton = "Маршрут",
        secondaryButtonBa = "Маршрут",
        icon = Icons.Default.LocalHospital
    ),
    PartnerAd(
        id = "ad-cafe-route",
        title = "Кафе «Юлдаш»",
        titleBa = "«Юлдаш» кафеһы",
        description = "Горячий чай и еда по дороге Баймаҡ → Сибай",
        descriptionBa = "Баймаҡ → Сибай юлында эҫе сәй һәм аш",
        address = "5 минут от трассы",
        addressBa = "Трассанан 5 минут",
        advertiserName = "ИП Хусаинов",
        erid = "2VtzqyYYYY",
        city = "Сибай",
        routeFrom = "Баймаҡ",
        routeTo = "Сибай",
        startDate = "2026-06-22",
        endDate = "2026-07-06",
        status = AdStatus.Active,
        placements = setOf(AdPlacement.Route, AdPlacement.RidesList, AdPlacement.TripDetails),
        packageName = "Маршрут",
        packageNameBa = "Маршрут",
        budgetLabel = "2 500 ₽ / 14 дней",
        budgetLabelBa = "2 500 ₽ / 14 көн",
        targetAction = "Показать предложение по маршруту",
        targetActionBa = "Маршрут буйынса тәҡдим күрһәтеү",
        contact = "+7 927 000-23-23",
        mapPoint = "53.7442, 58.6638",
        primaryButton = "Посмотреть",
        primaryButtonBa = "Ҡарау",
        icon = Icons.Default.Star
    ),
    PartnerAd(
        id = "ad-service-rides",
        title = "СТО «АвтоМастер»",
        titleBa = "«АвтоМастер» СТО",
        description = "Проверка машины перед дальней поездкой",
        descriptionBa = "Оҙон сәфәр алдынан машинаны тикшереү",
        address = "Сибай",
        advertiserName = "ООО «АвтоМастер»",
        erid = "2VtzqzZZZZ",
        city = "Сибай",
        routeFrom = "Сибай",
        routeTo = "Баймаҡ",
        category = "Межгород",
        categoryBa = "Ҡалалар араһы",
        startDate = "2026-06-22",
        endDate = "2026-07-22",
        status = AdStatus.Active,
        placements = setOf(AdPlacement.RidesList, AdPlacement.Route),
        packageName = "Спонсор списка",
        packageNameBa = "Исемлек спонсоры",
        budgetLabel = "5 000 ₽ / 30 дней",
        budgetLabelBa = "5 000 ₽ / 30 көн",
        targetAction = "Позвонить или открыть точку на карте",
        targetActionBa = "Шылтыратыу йәки картала нөктәне асыу",
        contact = "+7 927 000-34-34",
        mapPoint = "52.7200, 58.6650",
        primaryButton = "Позвонить",
        primaryButtonBa = "Шылтыратыу",
        secondaryButton = "На карте",
        secondaryButtonBa = "Картала",
        icon = Icons.Default.Settings
    ),
    PartnerAd(
        id = "ad-hotel-moderation",
        title = "Гостиница «Ирендык»",
        titleBa = "«Ирендык» ҡунаҡханаһы",
        description = "Номер на ночь для тех, кто едет через Сибай",
        descriptionBa = "Сибай аша барыусылар өсөн төнгөлөк бүлмә",
        address = "Сибай, центр",
        addressBa = "Сибай, үҙәк",
        advertiserName = "ИП Каримова",
        erid = "ожидает присвоения",
        city = "Сибай",
        routeFrom = "Баймаҡ",
        routeTo = "Сибай",
        category = "Межгород",
        categoryBa = "Ҡалалар араһы",
        startDate = "2026-07-01",
        endDate = "2026-07-31",
        status = AdStatus.Moderation,
        placements = setOf(AdPlacement.Route, AdPlacement.Help),
        packageName = "Маршрут",
        packageNameBa = "Маршрут",
        budgetLabel = "4 000 ₽ / 30 дней",
        budgetLabelBa = "4 000 ₽ / 30 көн",
        targetAction = "Открыть карточку гостиницы",
        targetActionBa = "Ҡунаҡхана карточкаһын асыу",
        contact = "+7 927 000-45-45",
        mapPoint = "52.7182, 58.6657",
        primaryButton = "Посмотреть",
        primaryButtonBa = "Ҡарау",
        icon = Icons.Default.LocationOn
    )
)

private val demoRides = listOf(
    Ride(
        id = "1",
        from = "Баймаҡ",
        to = "Сибай",
        time = "Сегодня, 17:30",
        timeBa = "Бөгөн, 17:30",
        driver = "Ильдар",
        car = "Lada Vesta, белая",
        carBa = "Lada Vesta, аҡ",
        price = 350,
        seats = 2,
        rating = 4.8,
        verified = true,
        boosted = true,
        petsAllowed = true,
        airConditioner = true
    ),
    Ride(
        id = "2",
        from = "Темясово",
        to = "Уфа",
        time = "Завтра, 06:00",
        timeBa = "Иртәгә, 06:00",
        driver = "Айгуль",
        car = "Hyundai Solaris, серебро",
        carBa = "Hyundai Solaris, көмөш төҫ",
        price = 1400,
        seats = 2,
        rating = 4.9,
        verified = true,
        boosted = false,
        womenOnly = true,
        childSeat = true,
        baggage = true
    ),
    Ride(
        id = "3",
        from = "Сибай",
        to = "Баймак",
        time = "Пятница, 13:20",
        timeBa = "Йома, 13:20",
        driver = "Рустам",
        car = "Renault Logan, синий",
        carBa = "Renault Logan, күк",
        price = 300,
        seats = 1,
        rating = 4.6,
        verified = false,
        boosted = false,
        baggage = true
    )
)

private val demoPopularRoutes = listOf(
    PopularRoute(
        from = "Баймаҡ",
        to = "Сибай",
        minutes = "15 мин",
        minutesBa = "15 мин",
        distance = "43 км",
        nearbyCount = 3,
        label = "Популярный маршрут",
        labelBa = "Популяр маршрут"
    ),
    PopularRoute(
        from = "Сибай",
        to = "Баймаҡ",
        minutes = "18 мин",
        minutesBa = "18 мин",
        distance = "43 км",
        nearbyCount = 2,
        label = "Возвращаются домой",
        labelBa = "Өйгә ҡайталар"
    ),
    PopularRoute(
        from = "Темясово",
        to = "Уфа",
        minutes = "3 ч 40 мин",
        minutesBa = "3 сәғ 40 мин",
        distance = "310 км",
        nearbyCount = 1,
        label = "Межгород сегодня",
        labelBa = "Бөгөн ҡалалар араһы"
    )
)

// ── Лента-карусель на карте ──────────────────────────────────────────────
// MOCK: микс карточек — маршруты (тапаются на «Найти поездку») + живые цифры дня/недели/месяца + факты.
// Всё про поездки. Реальные числа подставит бэкенд позже (один шов — mapFeedFrom).
private enum class FeedKind { Route, Live, Top, Fact, Community }

private data class MapFeedCard(
    val kind: FeedKind,
    val badge: String, val badgeBa: String,
    val title: String, val titleBa: String,
    val sub: String, val subBa: String,
    val pill: String, val pillBa: String,
    val route: PopularRoute? = null    // задан → карточка-маршрут, обновляет выбор для «Найти поездку»
)

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
private fun mapFeedFrom(popular: List<PopularRoute>, feed: FeedDto? = null): List<MapFeedCard> {
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
private fun YuldashApp() {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("yuldash_prefs", android.content.Context.MODE_PRIVATE)
    }
    val rides = remember { mutableStateListOf<Ride>().apply { addAll(demoRides) } }
    val trustedContacts = remember { mutableStateListOf<TrustedContact>().apply { addAll(demoTrustedContacts) } }
    val localRequests = remember { mutableStateListOf<LocalRequest>() }
    val appScope = rememberCoroutineScope()
    var activeBookingId by remember { mutableStateOf<Int?>(null) }
    var activeTrip by remember { mutableStateOf<Ride?>(null) }   // подтверждённая поездка → маршрут на карте; исчезает при завершении
    val voiceMessages = remember { mutableStateListOf<LocalVoiceMessage>() }
    // Экран после сплэша вычисляем один раз; сплэш показывается первым ~1.6с.
    val splashTarget = remember {
        when {
            BuildConfig.DEBUG -> Screen.Home               // DEV-обход входа: только debug-сборка. Релиз — вход как обычно.
            !prefs.getBoolean("onboarding_completed", false) -> Screen.Onboarding
            ApiClient.isLoggedIn() -> Screen.Home          // уже вошёл → сразу домой
            else -> Screen.Login
        }
    }
    var screen by remember { mutableStateOf(Screen.Splash) }
    var language by remember { mutableStateOf(AppLanguage.Ru) }
    var selectedRide by remember { mutableStateOf<Ride?>(null) }
    var startHomeTab by remember { mutableStateOf(HomeTab.Map) }
    var callbackRequested by remember { mutableStateOf(false) }
    // Реклама — сервер-управляемая (/ads); демо-шаблон даёт оформление, демо-список — фоллбэк.
    var partnerAds by remember { mutableStateOf(demoPartnerAds) }
    LaunchedEffect(Unit) {
        ApiClient.getAds().onSuccess { srv ->
            val tmpl = demoPartnerAds.firstOrNull()
            if (srv.isNotEmpty() && tmpl != null) partnerAds = srv.map { a ->
                tmpl.copy(id = a.id, title = a.title, titleBa = a.title, description = a.text, descriptionBa = a.text, erid = a.erid, primaryButton = a.button, primaryButtonBa = a.button)
            }
        }
    }
    var adStats by remember {
        mutableStateOf(demoPartnerAds.associate { it.id to AdStats() })
    }

    fun finishOnboarding() {
        prefs.edit().putBoolean("onboarding_completed", true).apply()
        screen = Screen.Login
    }

    fun openHome(tab: HomeTab = HomeTab.Map) {
        startHomeTab = tab
        screen = Screen.Home
    }

    fun trackAdImpression(ad: PartnerAd) {
        val current = adStats[ad.id] ?: AdStats()
        adStats = adStats + (ad.id to current.copy(impressions = current.impressions + 1))
        ApiClient.fireAdEvent(ad.id, "impression")   // реальный показ на сервер
    }

    fun trackAdClick(ad: PartnerAd) {
        val current = adStats[ad.id] ?: AdStats()
        adStats = adStats + (ad.id to current.copy(clicks = current.clicks + 1))
        ApiClient.fireAdEvent(ad.id, "click")        // реальный клик на сервер
        val title = if (language == AppLanguage.Ba) ad.titleBa ?: ad.title else ad.title
        // Реальное действие по клику: телефон → звонилка; иначе координаты → карта; иначе подсказка.
        val phoneDigits = ad.contact.filter { it.isDigit() || it == '+' }
        val isPhone = phoneDigits.count { it.isDigit() } >= 10
        val mapPt = ad.mapPoint.replace(" ", "")
        try {
            when {
                isPhone -> context.startActivity(
                    Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:$phoneDigits"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                mapPt.isNotBlank() -> context.startActivity(
                    Intent(Intent.ACTION_VIEW, android.net.Uri.parse("geo:$mapPt?q=$mapPt"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                else -> Toast.makeText(context, title, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(context, title, Toast.LENGTH_SHORT).show()
        }
    }

    // Поездки — с сервера. Стартуем с демо (мгновенно), при ответе заменяем на серверные.
    // Сервер недоступен (ТСПУ/офлайн) → остаются демо, экран не пустеет.
    LaunchedEffect(Unit) {
        ApiClient.getRides().onSuccess { dtos ->
            if (dtos.isNotEmpty()) {
                rides.clear()
                rides.addAll(
                    dtos.map { d ->
                        Ride(
                            id = d.id.toString(),
                            from = d.fromCity,
                            to = d.toCity,
                            time = formatDepart(d.departAt),
                            driver = d.driverName,
                            car = d.driverCar,
                            price = d.price,
                            seats = d.seatsLeft,
                            rating = d.driverRating,
                            verified = d.driverVerified,
                            boosted = false,
                        )
                    }
                )
            }
        }
    }
    CompositionLocalProvider(LocalAppLanguage provides language) {
        // Мои заявки — с сервера (после входа). Точное время в Фазе 1 не храним.
        val reqWaitingStatus = appText("ждём отклики", "яуаптар көтәбеҙ")
        val reqByAgreement = appText("по договорённости", "килешеү буйынса")
        LaunchedEffect(Unit) {
            ApiClient.getMyRequests().onSuccess { reqs ->
                localRequests.clear()
                localRequests.addAll(
                    reqs.map { r ->
                        LocalRequest(
                            title = apiCategoryToUiFor(language, r.category, r.withKids),
                            route = "${r.fromCity} → ${r.toCity}",
                            time = reqByAgreement,
                            passenger = r.forRelativeName ?: "Байрас",
                            status = reqWaitingStatus,
                            price = r.maxPrice,
                            trustedContact = r.comment.ifBlank { null },
                        )
                    }
                )
            }
        }
        // Доверенные контакты — с сервера (после входа).
        LaunchedEffect(Unit) {
            ApiClient.getContacts().onSuccess { list ->
                if (list.isNotEmpty()) {
                    trustedContacts.clear()
                    trustedContacts.addAll(list.map { c -> TrustedContact(c.name, c.relation, c.phone, c.notifyByDefault, c.id) })
                }
            }
        }
        BackHandler(enabled = screen != Screen.Onboarding && screen != Screen.Login && screen != Screen.Home && screen != Screen.Splash) {
            screen = Screen.Home
        }
        AnimatedContent(
            targetState = screen,
            transitionSpec = {
                (fadeIn(animationSpec = tween(260)) +
                    slideInHorizontally(animationSpec = tween(300)) { it / 12 })
                    .togetherWith(
                        fadeOut(animationSpec = tween(200)) +
                            slideOutHorizontally(animationSpec = tween(300)) { -it / 12 }
                    )
            },
            label = "screen"
        ) { scr ->
        when (scr) {
            Screen.Splash -> {
                SplashScreen()
                LaunchedEffect(Unit) { delay(1300); screen = splashTarget }
            }
            Screen.Onboarding -> OnboardingScreen(onFinish = ::finishOnboarding)
            Screen.Login -> {
                val context = LocalContext.current
                LoginScreen(
                    currentLanguage = language,
                    onToggleLanguage = {
                        language = if (language == AppLanguage.Ru) AppLanguage.Ba else AppLanguage.Ru
                    },
                    onContinue = { openHome() },
                    onTelegramLogin = { openTelegramLogin(context) },
                    onVKLogin = { openVKLogin(context) },
                    onWhatsAppLogin = { openWhatsAppLogin(context) }
                )
            }
            Screen.Home -> HomeScreen(
                rides = rides,
                activeTrip = activeTrip,
                requests = localRequests,
                ads = partnerAds,
                adStats = adStats,
                voiceMessages = voiceMessages,
                initialTab = startHomeTab,
                onCreateRide = { screen = Screen.CreateRide },
                onCreateRequest = { screen = Screen.CreateRequest },
                onSupport = { screen = Screen.Support },
                onBoost = { screen = Screen.Boost },
                onPublishRide = { ride ->
                    rides.add(0, ride)
                    Toast.makeText(context, if (language == AppLanguage.Ba) "Заявка баҫтырылды" else "Заявка опубликована", Toast.LENGTH_SHORT).show()
                },
                onBookRide = { ride ->
                    selectedRide = ride
                    screen = Screen.Booking
                },
                onOpenActiveTrip = { ride ->
                    selectedRide = ride
                    activeTrip = ride
                    activeBookingId = ride.id.toIntOrNull()
                    screen = Screen.ActiveTrip
                },
                onShareRide = { ride ->
                    val rideTime = if (language == AppLanguage.Ba) ride.timeBa ?: ride.time else ride.time
                    val shareText = if (language == AppLanguage.Ba) {
                        "Юлдаш: ${ride.from} → ${ride.to}, $rideTime, йөрөтөүсе ${ride.driver}, ${ride.price} ₽, буш урын: ${ride.seats}."
                    } else {
                        "Юлдаш: ${ride.from} → ${ride.to}, $rideTime, водитель ${ride.driver}, ${ride.price} ₽, свободно ${ride.seats} места."
                    }
                    shareRide(
                        context = context,
                        text = shareText,
                        chooserTitle = if (language == AppLanguage.Ba) "Сәфәр менән бүлешеү" else "Поделиться поездкой"
                    )
                },
                onAdImpression = ::trackAdImpression,
                onAdClick = ::trackAdClick,
                onAddVoiceMessage = { message ->
                    voiceMessages.add(0, message)
                    Toast.makeText(context, if (language == AppLanguage.Ba) "Тауыш хәбәре ебәрелде" else "Голосовое отправлено", Toast.LENGTH_SHORT).show()
                },
                onSos = { screen = Screen.Sos },
                onVerifyDriver = { screen = Screen.VerifyDriver },
                onNotifications = { screen = Screen.Notifications },
                onOpenChat = { bid, peer, route ->
                    val parts = route.split("→").map { it.trim() }
                    selectedRide = Ride(id = bid.toString(), from = parts.getOrElse(0) { "" }, to = parts.getOrElse(1) { "" }, time = "", driver = peer, car = "", price = 0, seats = 1, rating = 0.0, verified = false, boosted = false)
                    activeBookingId = bid
                    screen = Screen.ActiveTrip
                },
                onSafety = { screen = Screen.Safety },
                onSettings = { screen = Screen.Settings },
                onPrivacy = { screen = Screen.Privacy },
                onHelp = { screen = Screen.Help },
                onPassengerCabinet = { screen = Screen.PassengerCabinet },
                onDriverCabinet = { screen = Screen.DriverCabinet },
                onSimpleMode = { screen = Screen.SimpleMode },
                onTrustedContacts = { screen = Screen.TrustedContacts },
                onCallbackHelp = { screen = Screen.CallbackHelp },
                onAdsCabinet = { screen = Screen.AdsCabinet },
                onToggleLanguage = {
                    language = if (language == AppLanguage.Ru) AppLanguage.Ba else AppLanguage.Ru
                }
            )
            Screen.CreateRide -> CreateRideScreen(
                onBack = { openHome(HomeTab.Request) },
                onPublish = { ride ->
                    rides.add(0, ride)
                    Toast.makeText(context, if (language == AppLanguage.Ba) "Сәфәр баҫтырылды" else "Поездка опубликована", Toast.LENGTH_SHORT).show()
                    openHome(HomeTab.Rides)
                }
            )
            Screen.CreateRequest -> CreatePassengerRequestScreen(
                onBack = { openHome(HomeTab.Request) },
                onCreateRequest = { request ->
                    localRequests.add(0, request)
                    Toast.makeText(context, if (language == AppLanguage.Ba) "Заявка булдырылды" else "Заявка создана", Toast.LENGTH_SHORT).show()
                    openHome(HomeTab.Request)
                }
            )
            Screen.Support -> SupportScreen(onBack = { openHome(HomeTab.Profile) })
            Screen.Boost -> BoostScreen(onBack = { openHome(HomeTab.Rides) })
            Screen.Booking -> BookingScreen(
                ride = selectedRide ?: rides.first(),
                ads = partnerAds,
                adStats = adStats,
                onBack = { openHome(HomeTab.Rides) },
                onSelectTab = { tab -> openHome(tab) },
                onMessage = { openHome(HomeTab.Chat) },
                onAdImpression = ::trackAdImpression,
                onAdClick = ::trackAdClick,
                onConfirmRide = {
                    val rid = selectedRide?.id?.toIntOrNull()
                    if (rid != null) {
                        appScope.launch {
                            ApiClient.book(rid, 1)
                                .onSuccess { bid -> activeBookingId = bid; activeTrip = selectedRide; screen = Screen.ActiveTrip }
                                .onFailure { Toast.makeText(context, if (language == AppLanguage.Ba) "Бронләп булманы. Ҡабатла." else "Не удалось забронировать. Повтори.", Toast.LENGTH_SHORT).show() }
                        }
                    }
                }
            )
            Screen.ActiveTrip -> ActiveTripScreen(
                ride = selectedRide,
                contacts = trustedContacts,
                bookingId = activeBookingId,
                onBack = { openHome(HomeTab.Rides) },
                onTripEnd = { activeTrip = null; openHome(HomeTab.Map) },
                onSos = { screen = Screen.Sos }
            )
            Screen.Sos -> SosScreen(onBack = { openHome(HomeTab.Map) })
            Screen.VerifyDriver -> VerifyDriverScreen(
                onBack = { openHome(HomeTab.Profile) },
                onSelectTab = { tab -> openHome(tab) }
            )
            Screen.Notifications -> NotificationsScreen(
                onBack = { openHome(HomeTab.Chat) },
                onSelectTab = { tab -> openHome(tab) }
            )
            Screen.Privacy -> PrivacyScreen(onBack = { openHome(HomeTab.Profile) })
            Screen.Safety -> SafetyScreen(
                onBack = { openHome(HomeTab.Profile) },
                onSelectTab = { tab -> openHome(tab) },
                onSos = { screen = Screen.Sos }
            )
            Screen.Settings -> SettingsScreen(onBack = { openHome(HomeTab.Profile) }, onSelectTab = { tab -> openHome(tab) }, onToggleLanguage = {
                language = if (language == AppLanguage.Ru) AppLanguage.Ba else AppLanguage.Ru
            })
            Screen.Help -> HelpScreen(
                ads = partnerAds,
                adStats = adStats,
                onBack = { openHome(HomeTab.Profile) },
                onSelectTab = { tab -> openHome(tab) },
                onAdImpression = ::trackAdImpression,
                onAdClick = ::trackAdClick
            )
            Screen.PassengerCabinet -> PassengerCabinetScreen(
                rides = rides,
                requests = localRequests,
                onBack = { openHome(HomeTab.Profile) },
                onFindRide = { openHome(HomeTab.Rides) },
                onCreateRequest = { screen = Screen.CreateRequest },
                onSafety = { screen = Screen.Safety }
            )
            Screen.DriverCabinet -> DriverCabinetScreen(
                rides = rides,
                onBack = { openHome(HomeTab.Profile) },
                onCreateRide = { screen = Screen.CreateRide },
                onVerifyDriver = { screen = Screen.VerifyDriver },
                onBoost = { screen = Screen.Boost }
            )
            Screen.AdsCabinet -> AdsCabinetScreen(
                ads = partnerAds,
                adStats = adStats,
                onBack = { openHome(HomeTab.Profile) }
            )
            Screen.SimpleMode -> SimpleModeScreen(
                latestRequests = localRequests,
                onBack = { openHome(HomeTab.Map) },
                onVoiceRequest = { screen = Screen.VoiceRequest },
                onFamilyOrder = { screen = Screen.FamilyOrder },
                onTrustedContacts = { screen = Screen.TrustedContacts },
                onRepeatTrip = { screen = Screen.RepeatTrip },
                onCallbackHelp = { screen = Screen.CallbackHelp },
                onSos = { screen = Screen.Sos },
                onChat = { openHome(HomeTab.Chat) }
            )
            Screen.VoiceRequest -> VoiceRequestScreen(
                contacts = trustedContacts,
                onBack = { screen = Screen.SimpleMode },
                onCreateRequest = { request ->
                    localRequests.add(0, request)
                    Toast.makeText(context, if (language == AppLanguage.Ba) "Заявка булдырылды" else "Заявка создана", Toast.LENGTH_SHORT).show()
                    screen = Screen.SimpleMode
                }
            )
            Screen.FamilyOrder -> FamilyOrderScreen(
                contacts = trustedContacts,
                onBack = { screen = Screen.SimpleMode },
                onCreateRequest = { request ->
                    localRequests.add(0, request)
                    Toast.makeText(context, if (language == AppLanguage.Ba) "Яҡын кеше өсөн сәфәр булдырылды" else "Поездка за близкого создана", Toast.LENGTH_SHORT).show()
                    screen = Screen.SimpleMode
                }
            )
            Screen.TrustedContacts -> TrustedContactsScreen(
                contacts = trustedContacts,
                onBack = { screen = Screen.SimpleMode },
                onAddContact = { contact ->
                    trustedContacts.add(contact)
                    ApiClient.fireAddContact(contact.name, contact.relation, contact.phone, contact.notifyByDefault)
                    Toast.makeText(context, if (language == AppLanguage.Ba) "Контакт өҫтәлде" else "Контакт добавлен", Toast.LENGTH_SHORT).show()
                }
            )
            Screen.RepeatTrip -> RepeatTripScreen(
                contacts = trustedContacts,
                onBack = { screen = Screen.SimpleMode },
                onRepeat = { request ->
                    localRequests.add(0, request)
                    Toast.makeText(context, if (language == AppLanguage.Ba) "Йыш сәфәр ҡабатланды" else "Частая поездка повторена", Toast.LENGTH_SHORT).show()
                    screen = Screen.SimpleMode
                }
            )
            Screen.CallbackHelp -> CallbackHelpScreen(
                requested = callbackRequested,
                onBack = { screen = Screen.SimpleMode },
                onRequest = {
                    callbackRequested = true
                    Toast.makeText(context, if (language == AppLanguage.Ba) "Шылтыратыу заявкаһы булдырылды" else "Заявка на звонок создана", Toast.LENGTH_SHORT).show()
                }
            )
        }
        }
    }
}

internal fun shareRide(context: android.content.Context, text: String, chooserTitle: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, chooserTitle))
}

@Composable
private fun OnboardingScreen(onFinish: () -> Unit) {
    val slides = remember { onboardingSlides() }
    val pagerState = rememberPagerState(pageCount = { slides.size })
    val scope = rememberCoroutineScope()
    var role by remember { mutableStateOf(RideRole.Passenger) }
    val isLastPage = pagerState.currentPage == slides.lastIndex

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = CanonBg
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f)
            ) { page ->
                val slide = slides[page]
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(bottom = 14.dp)
                ) {
                    item { Spacer(Modifier.height(8.dp)) }
                    item { OnboardingHeroCard(slide) { (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction } }
                    item {
                        Text(
                            text = appText(slide.titleRu, slide.titleBa),
                            color = CanonText,
                            fontSize = 34.sp,
                            lineHeight = 36.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                    item {
                        Text(
                            text = appText(slide.bodyRu, slide.bodyBa),
                            color = CanonMuted,
                            fontSize = 18.sp,
                            lineHeight = 25.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    if (page == slides.lastIndex) {
                        item {
                            OnboardingRoleChooser(
                                selected = role,
                                onSelect = { role = it }
                            )
                        }
                    } else {
                        items(slide.items) { item ->
                            OnboardingFeatureCard(item)
                        }
                    }
                    if (slide.noteRu != null && slide.noteBa != null) {
                        item { OnboardingSafetyNote(appText(slide.noteRu, slide.noteBa)) }
                    }
                }
            }
            Column(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (pagerState.currentPage > 0) {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                }
                            }
                        ) {
                            Text(appText("Назад", "Кире"), color = CanonGreen2, fontWeight = FontWeight.Black)
                        }
                    } else {
                        Spacer(Modifier.width(82.dp))
                    }
                    OnboardingDots(
                        count = slides.size,
                        selected = pagerState.currentPage,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onFinish) {
                        Text(appText("Пропустить", "Үткәреп ебәреү"), color = CanonGreen2, fontWeight = FontWeight.Black)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        if (isLastPage) {
                            onFinish()
                        } else {
                            scope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)
                ) {
                    Text(
                        text = if (isLastPage) appText("Войти по телефону", "Телефон аша инеү") else appText("Далее", "Артабан"),
                        fontWeight = FontWeight.Black,
                        fontSize = 18.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun OnboardingHeroCard(slide: OnboardingSlide, pageOffset: () -> Float = { 0f }) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp)
            .clip(CanonCardShape)
            .background(Brush.linearGradient(listOf(CanonGreen, Color(0xFF16884E), CanonYellow)))
            .padding(20.dp)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Color.White.copy(alpha = 0.12f), radius = 170f, center = Offset(size.width * 0.86f, size.height * 0.04f))
            drawCircle(Color.White.copy(alpha = 0.14f), radius = 92f, center = Offset(size.width * 0.82f, size.height * 0.78f))
            val road = Path().apply {
                moveTo(size.width * 0.04f, size.height * 0.74f)
                cubicTo(size.width * 0.32f, size.height * 0.44f, size.width * 0.58f, size.height * 0.85f, size.width * 0.96f, size.height * 0.54f)
            }
            drawPath(road, Color.White.copy(alpha = 0.25f), style = Stroke(width = 20f, cap = StrokeCap.Round))
            drawPath(road, Color.White.copy(alpha = 0.90f), style = Stroke(width = 7f, cap = StrokeCap.Round))
            drawCircle(Color.White, radius = 13f, center = Offset(size.width * 0.04f, size.height * 0.74f))
            drawCircle(Color.White, radius = 10f, center = Offset(size.width * 0.96f, size.height * 0.54f))
        }
        Row(
            modifier = Modifier.align(Alignment.TopStart),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(58.dp),
                shape = RoundedCornerShape(18.dp),
                color = Color.White,
                shadowElevation = 5.dp
            ) {
                Image(
                    painter = painterResource(R.drawable.yuldash_logo),
                    contentDescription = null,
                    modifier = Modifier.padding(5.dp),
                    contentScale = ContentScale.Fit
                )
            }
            Spacer(Modifier.width(12.dp))
            Text("Юлдаш", color = Color.White, fontSize = 42.sp, lineHeight = 44.sp, fontWeight = FontWeight.Black)
        }
        Text(
            text = appText(slide.eyebrowRu, slide.eyebrowBa),
            color = Color.White,
            fontSize = 20.sp,
            lineHeight = 25.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .align(Alignment.TopStart)
                .graphicsLayer { translationX = pageOffset() * 75f }
                .padding(top = 78.dp, end = 14.dp)
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .graphicsLayer { translationX = pageOffset() * 150f; translationY = pageOffset() * -28f }
                .size(96.dp)
                .background(Color.White.copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(onboardingHeroIcon(slide.hero), contentDescription = null, tint = Color.White, modifier = Modifier.size(58.dp))
        }
    }
}

private fun onboardingHeroIcon(hero: OnboardingHero): ImageVector {
    return when (hero) {
        OnboardingHero.Route -> Icons.Default.DirectionsCar
        OnboardingHero.Security -> Icons.Default.Shield
        OnboardingHero.Steps -> Icons.Default.Route
        OnboardingHero.Start -> Icons.Default.LocationOn
    }
}

@Composable
private fun OnboardingFeatureCard(item: OnboardingItem) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = CanonItemShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            OnboardingIconBubble(item.icon)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(appText(item.titleRu, item.titleBa), color = CanonText, fontWeight = FontWeight.Black, fontSize = 18.sp, lineHeight = 21.sp)
                Text(appText(item.bodyRu, item.bodyBa), color = CanonMuted, fontSize = 15.sp, lineHeight = 20.sp)
            }
        }
    }
}

@Composable
private fun OnboardingRoleChooser(selected: RideRole, onSelect: (RideRole) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OnboardingRoleCard(
            icon = Icons.Default.Person,
            title = appText("Я пассажир", "Мин пассажир"),
            body = appText("Ищу поездки, создаю заявки и общаюсь с водителями.", "Сәфәр эҙләйем, заявка булдырам һәм водителдәр менән һөйләшәм."),
            selected = selected == RideRole.Passenger,
            onClick = { onSelect(RideRole.Passenger) }
        )
        OnboardingRoleCard(
            icon = Icons.Default.DirectionsCar,
            title = appText("Я водитель", "Мин водитель"),
            body = appText("Публикую поездки, откликаюсь на заявки и прохожу проверку.", "Сәфәрҙәр ҡуям, заявкаларға яуап бирәм һәм тикшереү үтәм."),
            selected = selected == RideRole.Driver,
            onClick = { onSelect(RideRole.Driver) }
        )
        OnboardingTrustStrip()
    }
}

@Composable
private fun OnboardingRoleCard(
    icon: ImageVector,
    title: String,
    body: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.bounceClick(onClick),
        colors = CardDefaults.cardColors(containerColor = if (selected) CanonMint else CanonSurface),
        shape = CanonItemShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            OnboardingIconBubble(icon)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = CanonText, fontWeight = FontWeight.Black, fontSize = 18.sp)
                Text(body, color = CanonMuted, fontSize = 15.sp, lineHeight = 20.sp)
            }
            Icon(
                if (selected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                contentDescription = null,
                tint = CanonGreen2
            )
        }
    }
}

@Composable
private fun OnboardingTrustStrip() {
    Card(colors = CardDefaults.cardColors(containerColor = CanonSurface), shape = CanonItemShape, elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OnboardingMiniTrust(Icons.Default.PhoneLocked, appText("Скрытый\nномер", "Йәшерен\nномер"), Modifier.weight(1f))
            OnboardingMiniTrust(Icons.Default.Pin, appText("Код\nпосадки", "Ултырыу\nкоды"), Modifier.weight(1f))
            OnboardingMiniTrust(Icons.Default.Verified, appText("Проверка\nводителя", "Водителде\nтикшереү"), Modifier.weight(1f))
        }
    }
}

@Composable
private fun OnboardingMiniTrust(icon: ImageVector, label: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, tint = CanonGreen2)
        Spacer(Modifier.height(6.dp))
        Text(label, color = CanonText, textAlign = TextAlign.Center, fontWeight = FontWeight.Black, fontSize = 12.sp, lineHeight = 13.sp)
    }
}

@Composable
private fun OnboardingIconBubble(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(58.dp)
            .background(CanonMint, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(30.dp))
    }
}

@Composable
private fun OnboardingSafetyNote(text: String) {
    Surface(color = CanonMint, shape = CanonItemShape, border = BorderStroke(1.dp, Color(0x2235A363))) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Lock, contentDescription = null, tint = CanonGreen2)
            Spacer(Modifier.width(12.dp))
            Text(text, color = CanonGreen2, fontWeight = FontWeight.Black, fontSize = 15.sp, lineHeight = 19.sp)
        }
    }
}

@Composable
private fun OnboardingDots(count: Int, selected: Int, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { index ->
            val active = selected == index
            val w by animateDpAsState(if (active) 26.dp else 8.dp, tween(320), label = "dotW")
            val c by animateColorAsState(if (active) CanonGreen2 else CanonMuted.copy(alpha = 0.32f), tween(320), label = "dotC")
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(width = w, height = 8.dp)
                    .background(c, RoundedCornerShape(999.dp))
            )
        }
    }
}

private fun onboardingSlides() = listOf(
    OnboardingSlide(
        eyebrowRu = "Поездки между своими",
        eyebrowBa = "Үҙ кешеләрең менән сәфәрҙәр",
        titleRu = "Юлдаш помогает ехать спокойнее",
        titleBa = "Юлдаш тынысыраҡ барырға ярҙам итә",
        bodyRu = "Ищите поездку, создавайте заявку или публикуйте маршрут. Важные детали остаются внутри приложения.",
        bodyBa = "Сәфәр эҙләгеҙ, заявка булдырығыҙ йәки маршрут ҡуйығыҙ. Мөһим мәғлүмәт ҡушымта эсендә ҡала.",
        hero = OnboardingHero.Route,
        items = listOf(
            OnboardingItem(Icons.Default.PhoneLocked, "Скрытый номер", "Йәшерен номер", "Телефон не раскрывается до подтверждения поездки.", "Телефон сәфәр раҫланғанға тиклем асылмай."),
            OnboardingItem(Icons.Default.Pin, "Код посадки", "Ултырыу коды", "Встреча с водителем подтверждается уникальным кодом.", "Водитель менән осрашыу айырым код менән раҫлана."),
            OnboardingItem(Icons.Default.Verified, "Проверка водителя", "Водителде тикшереү", "Профиль водителя и машина проходят проверку.", "Водитель профиле һәм машина тикшереү үтә.")
        )
    ),
    OnboardingSlide(
        eyebrowRu = "Безопасность в каждой поездке",
        eyebrowBa = "Һәр сәфәрҙә хәүефһеҙлек",
        titleRu = "Защита включена с первого шага",
        titleBa = "Һаҡлау беренсе аҙымдан эшләй",
        bodyRu = "Подтверждённые участники, скрытые контакты и SOS помогают держать поездку под контролем.",
        bodyBa = "Раҫланған ҡатнашыусылар, йәшерен контакттар һәм SOS сәфәрҙе контролдә тоторға ярҙам итә.",
        hero = OnboardingHero.Security,
        items = listOf(
            OnboardingItem(Icons.Default.AdminPanelSettings, "Подтверждённые участники", "Раҫланған ҡатнашыусылар", "Меньше случайных контактов в заявках и откликах.", "Заявкаларҙа һәм яуаптарҙа осраҡлы бәйләнештәр кәмей."),
            OnboardingItem(Icons.Default.VisibilityOff, "Номер не виден сразу", "Номер шунда уҡ күренмәй", "Контакты открываются после подтверждения поездки.", "Контакттар сәфәр раҫланғандан һуң асыла."),
            OnboardingItem(Icons.Default.Sos, "SOS и поддержка", "SOS һәм ярҙам", "Экстренная помощь доступна прямо из приложения.", "Ашығыс ярҙам ҡушымта эсендә бар.")
        )
    ),
    OnboardingSlide(
        eyebrowRu = "Всё просто и понятно",
        eyebrowBa = "Барыһы ла ябай һәм аңлайышлы",
        titleRu = "Как это работает",
        titleBa = "Нисек эшләй",
        bodyRu = "Выберите маршрут, найдите подходящую поездку или создайте заявку, если варианта ещё нет.",
        bodyBa = "Маршрут һайлағыҙ, уңайлы сәфәр табығыҙ йәки вариант юҡ икән заявка булдырығыҙ.",
        hero = OnboardingHero.Steps,
        items = listOf(
            OnboardingItem(Icons.Default.Search, "Найдите поездку", "Сәфәр табығыҙ", "Выберите маршрут и посмотрите ближайшие варианты.", "Маршрут һайлап, яҡындағы варианттарҙы ҡарағыҙ."),
            OnboardingItem(Icons.Default.AddRoad, "Создайте заявку", "Заявка булдырығыҙ", "Укажите маршрут, время и условия поездки.", "Маршрутты, ваҡытты һәм шарттарҙы күрһәтегеҙ."),
            OnboardingItem(Icons.Default.ChatBubbleOutline, "Договоритесь в чате", "Чатта килешегеҙ", "После отклика можно обсудить детали и подтвердить поездку.", "Яуаптан һуң деталдәрҙе һөйләшеп, сәфәрҙе раҫларға була.")
        ),
        noteRu = "Телефон откроется только после подтверждения поездки",
        noteBa = "Телефон сәфәр раҫланғандан һуң ғына асыла"
    ),
    OnboardingSlide(
        eyebrowRu = "Готово к первой поездке",
        eyebrowBa = "Беренсе сәфәргә әҙер",
        titleRu = "Начнём?",
        titleBa = "Башлайбыҙмы?",
        bodyRu = "Выберите удобный сценарий и войдите по номеру телефона.",
        bodyBa = "Уңайлы сценарийҙы һайлап, телефон номеры аша инегеҙ.",
        hero = OnboardingHero.Start,
        items = emptyList()
    )
)


@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScreenTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.Black) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBackIosNew, contentDescription = appText("Назад", "Кире"))
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
            navigationIconContentColor = MaterialTheme.colorScheme.onBackground
        )
    )
}

@Composable
private fun HomeScreen(
    rides: List<Ride>,
    activeTrip: Ride?,
    requests: List<LocalRequest>,
    ads: List<PartnerAd>,
    adStats: Map<String, AdStats>,
    voiceMessages: List<LocalVoiceMessage>,
    initialTab: HomeTab,
    onCreateRide: () -> Unit,
    onCreateRequest: () -> Unit,
    onSupport: () -> Unit,
    onBoost: () -> Unit,
    onPublishRide: (Ride) -> Unit,
    onBookRide: (Ride) -> Unit,
    onOpenActiveTrip: (Ride) -> Unit,
    onShareRide: (Ride) -> Unit,
    onAdImpression: (PartnerAd) -> Unit,
    onAdClick: (PartnerAd) -> Unit,
    onAddVoiceMessage: (LocalVoiceMessage) -> Unit,
    onSos: () -> Unit,
    onVerifyDriver: () -> Unit,
    onNotifications: () -> Unit,
    onOpenChat: (Int, String, String) -> Unit,
    onSafety: () -> Unit,
    onSettings: () -> Unit,
    onPrivacy: () -> Unit,
    onHelp: () -> Unit,
    onPassengerCabinet: () -> Unit,
    onDriverCabinet: () -> Unit,
    onSimpleMode: () -> Unit,
    onTrustedContacts: () -> Unit,
    onCallbackHelp: () -> Unit,
    onAdsCabinet: () -> Unit,
    onToggleLanguage: () -> Unit
) {
    var selectedTab by remember(initialTab) { mutableStateOf(initialTab) }
    var ridesPresetTo by remember { mutableStateOf("") }
    var ridesPresetToday by remember { mutableStateOf(false) }

    fun openRides(to: String = "", today: Boolean = false) {
        ridesPresetTo = to
        ridesPresetToday = today
        selectedTab = HomeTab.Rides
    }

    BackHandler(enabled = selectedTab != HomeTab.Map) {
        selectedTab = HomeTab.Map
    }

    Scaffold(
        containerColor = CanonBg,
        bottomBar = {
            YuldashBottomBar(
                selectedTab = selectedTab,
                onSelect = { selectedTab = it }
            )
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    (fadeIn(animationSpec = tween(260)) +
                        slideInVertically(animationSpec = tween(260)) { it / 18 })
                        .togetherWith(fadeOut(animationSpec = tween(180)))
                },
                label = "homeTab"
            ) { tab ->
            when (tab) {
                HomeTab.Map -> MapScreen(
                    rides = rides,
                    activeTrip = activeTrip,
                    ads = ads,
                    adStats = adStats,
                    onBookRide = onBookRide,
                    onShareRide = onShareRide,
                    onAdImpression = onAdImpression,
                    onAdClick = onAdClick,
                    onSos = onSos,
                    onSimpleMode = onSimpleMode,
                    onOpenPopular = { route -> openRides(to = route.to, today = true) },
                    onDriver = onCreateRide,
                    onBoost = onBoost
                )
                HomeTab.Rides -> RidesScreen(
                    rides = rides,
                    ads = ads,
                    adStats = adStats,
                    presetTo = ridesPresetTo,
                    presetToday = ridesPresetToday,
                    onBookRide = onBookRide,
                    onOpenActiveTrip = onOpenActiveTrip,
                    onMessage = { selectedTab = HomeTab.Chat },
                    onShareRide = onShareRide,
                    onBoost = onBoost,
                    onCreateRequest = { selectedTab = HomeTab.Request },
                    onAdImpression = onAdImpression,
                    onAdClick = onAdClick
                )
                HomeTab.Request -> MyRequestsScreen(
                    requests = requests,
                    onCreateNew = onCreateRequest,
                    onViewResponses = { selectedTab = HomeTab.Chat }
                )
                HomeTab.Chat -> ChatScreen(
                    voiceMessages = voiceMessages,
                    onAddVoiceMessage = onAddVoiceMessage,
                    onNotifications = onNotifications,
                    onOpenChat = onOpenChat
                )
                HomeTab.Profile -> ProfileScreen(
                    ads = ads,
                    adStats = adStats,
                    onSupport = onSupport,
                    onVerifyDriver = onVerifyDriver,
                    onSafety = onSafety,
                    onSettings = onSettings,
                    onPrivacy = onPrivacy,
                    onHelp = onHelp,
                    onPassengerCabinet = onPassengerCabinet,
                    onDriverCabinet = onDriverCabinet,
                    onSimpleMode = onSimpleMode,
                    onTrustedContacts = onTrustedContacts,
                    onCallbackHelp = onCallbackHelp,
                    onAdsCabinet = onAdsCabinet,
                    onHospitalTrips = { openRides(to = "Больница") },
                    onToggleLanguage = onToggleLanguage,
                    onAdImpression = onAdImpression,
                    onAdClick = onAdClick
                )
            }
            }
        }
    }
}

@Composable
internal fun YuldashBottomBar(
    selectedTab: HomeTab,
    onSelect: (HomeTab) -> Unit
) {
    Surface(
        color = CanonSurface.copy(alpha = 0.98f),
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        shadowElevation = 12.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(78.dp)
                .padding(horizontal = 6.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            YuldashBottomItem(
                selected = selectedTab == HomeTab.Map,
                label = appText("Карта", "Карта"),
                icon = Icons.Default.Map,
                onClick = { onSelect(HomeTab.Map) }
            )
            YuldashBottomItem(
                selected = selectedTab == HomeTab.Rides,
                label = appText("Поездки", "Сәфәрҙәр"),
                icon = Icons.Default.ListAlt,
                onClick = { onSelect(HomeTab.Rides) }
            )
            YuldashBottomItem(
                selected = selectedTab == HomeTab.Request,
                label = appText("Заявка", "Заявка"),
                icon = Icons.Default.AddBox,
                onClick = { onSelect(HomeTab.Request) }
            )
            YuldashBottomItem(
                selected = selectedTab == HomeTab.Chat,
                label = appText("Чат", "Чат"),
                icon = Icons.Default.ChatBubble,
                onClick = { onSelect(HomeTab.Chat) }
            )
            YuldashBottomItem(
                selected = selectedTab == HomeTab.Profile,
                label = appText("Профиль", "Профиль"),
                icon = Icons.Default.Person,
                onClick = { onSelect(HomeTab.Profile) }
            )
        }
    }
}

@Composable
private fun RowScope.YuldashBottomItem(
    selected: Boolean,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    val pillColor by animateColorAsState(if (selected) CanonGold else Color.Transparent, tween(280), label = "navPill")
    val iconTint by animateColorAsState(if (selected) CanonText else CanonMuted, tween(280), label = "navTint")
    val labelColor by animateColorAsState(if (selected) Color(0xFFD29400) else CanonMuted, tween(280), label = "navLabel")
    val iconScale by animateFloatAsState(if (selected) 1.12f else 1f, tween(280), label = "navScale")
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .weight(1f)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Surface(
            color = pillColor,
            shape = RoundedCornerShape(18.dp)
        ) {
            Icon(
                icon,
                contentDescription = label,
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .size(21.dp)
                    .graphicsLayer { scaleX = iconScale; scaleY = iconScale },
                tint = iconTint
            )
        }
        Text(
            text = label,
            color = labelColor,
            fontSize = 9.sp,
            fontWeight = if (selected) FontWeight.Black else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapScreen(
    rides: List<Ride>,
    activeTrip: Ride?,
    ads: List<PartnerAd>,
    adStats: Map<String, AdStats>,
    onBookRide: (Ride) -> Unit,
    onShareRide: (Ride) -> Unit,
    onAdImpression: (PartnerAd) -> Unit,
    onAdClick: (PartnerAd) -> Unit,
    onSos: () -> Unit,
    onSimpleMode: () -> Unit,
    onOpenPopular: (PopularRoute) -> Unit,
    onDriver: () -> Unit,
    onBoost: () -> Unit
) {
    val nearbyAd = ads.forPlacement(AdPlacement.Nearby).firstOrNull { it.city == "Баймаҡ" }
    var selectedRide by remember { mutableStateOf<Ride?>(null) }
    val sheetState = rememberModalBottomSheetState()
    // Ближайшие поездки: маршрут клиента (активная поездка → её маршрут) + сортировка по времени выезда + гео-дистанция.
    var nearby by remember { mutableStateOf<List<com.yuldash.app.data.RideDto>>(emptyList()) }
    var nearbyLoading by remember { mutableStateOf(true) }
    var nearbyReload by remember { mutableStateOf(0) }
    var prefFilter by remember { mutableStateOf(setOf<String>()) }  // фильтр «Ближайших» по условиям поездки
    val focusFrom = activeTrip?.from
    val focusTo = activeTrip?.to
    val userLat = LocationPrefs.lastLat   // читаем в локальные val → подписка на изменение позиции
    val userLng = LocationPrefs.lastLng
    LaunchedEffect(focusFrom, focusTo, userLat, userLng, nearbyReload) {
        nearbyLoading = true
        // Радиус применяем только когда знаем позицию (иначе показываем все по маршруту/времени).
        val radius = if (userLat != null && userLng != null) NEARBY_RADIUS_KM else null
        ApiClient.getNearbyRides(focusFrom, focusTo, userLat, userLng, radius)
            .onSuccess { nearby = it }
        nearbyLoading = false
    }
    // Клиентская фильтрация «Ближайших» по выбранным условиям (поля уже пришли в RideDto).
    val shownNearby = if (prefFilter.isEmpty()) nearby else nearby.filter { d ->
        ("women" !in prefFilter || d.womenOnly) &&
            ("child" !in prefFilter || d.childSeat) &&
            ("pets" !in prefFilter || d.petsAllowed) &&
            ("baggage" !in prefFilter || d.baggage)
    }
    Scaffold(containerColor = CanonBg) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            // Закреплённый верх: шапка + карта (НЕ в прокрутке → вертикальный пан двигает карту, а не страницу).
            Column(modifier = Modifier.padding(horizontal = 14.dp)) {
                Spacer(Modifier.height(2.dp))
                Box(Modifier.appearIn(0)) { HomeHeader(onSos = onSos) }
                Spacer(Modifier.height(11.dp))
                Box(Modifier.appearIn(1)) {
                    MapHero(
                        activeTrip = activeTrip,
                        onRideTap = { selectedRide = it },
                        onFind = onOpenPopular,
                        onDriver = onDriver
                    )
                }
            }
            // Прокручиваемый низ: простой режим, ближайшие поездки, реклама.
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp),
                contentPadding = PaddingValues(top = 11.dp, bottom = 8.dp)
            ) {
                item {
                    Box(Modifier.appearIn(3)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(appText("Ближайшие поездки", "Яҡындағы сәфәрҙәр"), fontSize = 16.sp, fontWeight = FontWeight.Black)
                                if (focusFrom != null && focusTo != null) {
                                    Text("$focusFrom → $focusTo", color = CanonGreen2, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            if (nearby.isNotEmpty()) {
                                Text(appText("${shownNearby.size} рядом", "${shownNearby.size} яҡында"), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }
                }
                item {
                    if (nearby.isNotEmpty()) {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            NearbyFilterChip(Icons.Default.Woman, appText("Только женщины", "Тик ҡатын-ҡыҙ"), "women" in prefFilter) { prefFilter = if ("women" in prefFilter) prefFilter - "women" else prefFilter + "women" }
                            NearbyFilterChip(Icons.Default.ChildCare, appText("Детское кресло", "Балалар ултырғысы"), "child" in prefFilter) { prefFilter = if ("child" in prefFilter) prefFilter - "child" else prefFilter + "child" }
                            NearbyFilterChip(Icons.Default.Pets, appText("С животным", "Хайуан менән"), "pets" in prefFilter) { prefFilter = if ("pets" in prefFilter) prefFilter - "pets" else prefFilter + "pets" }
                            NearbyFilterChip(Icons.Default.Luggage, appText("Багаж", "Багаж"), "baggage" in prefFilter) { prefFilter = if ("baggage" in prefFilter) prefFilter - "baggage" else prefFilter + "baggage" }
                        }
                    }
                }
                item {
                    Box(Modifier.appearIn(4)) {
                        when {
                            nearbyLoading && nearby.isEmpty() -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                NearbySkeletonCard(); NearbySkeletonCard()
                            }
                            nearby.isEmpty() -> NearbyEmptyCard(hasRoute = focusFrom != null, onRetry = { nearbyReload++ })
                            shownNearby.isEmpty() -> Text(
                                appText("Нет поездок с такими условиями. Снимите часть фильтров.", "Был шарттар менән сәфәр юҡ. Фильтрҙың бер өлөшөн алығыҙ."),
                                color = CanonMuted, fontSize = 14.sp, lineHeight = 19.sp
                            )
                            else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                itemsIndexed(shownNearby) { i, dto ->
                                    NearbyRideCard(dto = dto, soonest = i == 0, onOpen = { onBookRide(dto.toUiRide()) })
                                }
                            }
                        }
                    }
                }
                nearbyAd?.let { ad ->
                    item {
                        Box(Modifier.appearIn(5)) {
                            PartnerAdCard(
                                ad = ad,
                                stats = adStats[ad.id] ?: AdStats(),
                                label = appText("Партнёр рядом", "Яҡындағы партнёр"),
                                onImpression = onAdImpression,
                                onClick = onAdClick
                            )
                        }
                    }
                }
            }
        }
    }
    // Тап по маркеру поездки → карточка снизу с деталями и действиями.
    selectedRide?.let { ride ->
        ModalBottomSheet(
            onDismissRequest = { selectedRide = null },
            sheetState = sheetState,
            containerColor = CanonSurface
        ) {
            Column(Modifier.padding(horizontal = 14.dp).padding(bottom = 24.dp)) {
                RideCard(
                    ride = ride,
                    compact = true,
                    fullWidth = true,
                    onBook = { selectedRide = null; onBookRide(ride) },
                    onShare = { onShareRide(ride) },
                    onBoost = { selectedRide = null; onBoost() }
                )
            }
        }
    }
}

@Composable
private fun MapHero(
    activeTrip: Ride?,
    onRideTap: (Ride) -> Unit,
    onFind: (PopularRoute) -> Unit,
    onDriver: () -> Unit
) {
    // Популярные маршруты — порядок с сервера (из реальных поездок); демо для богатого вида.
    var popular by remember { mutableStateOf(demoPopularRoutes) }
    LaunchedEffect(Unit) {
        while (true) {
            ApiClient.getPopularRoutes().onSuccess { srv ->
                if (srv.isNotEmpty()) popular = srv.map { s ->
                    demoPopularRoutes.firstOrNull { it.from == s.from && it.to == s.to }
                        ?: PopularRoute(from = s.from, to = s.to, minutes = "—", minutesBa = "—", distance = "", nearbyCount = s.count, label = "Поездки", labelBa = "Сәфәрҙәр")
                }
            }
            delay(45_000)   // обновляем карусель под актуальные поездки
        }
    }
    // Живые цифры ленты (поездок за день/неделю/месяц/год + топ-маршрут) — с сервера.
    var liveFeed by remember { mutableStateOf<FeedDto?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            ApiClient.getFeed().onSuccess { liveFeed = it }
            delay(60_000)
        }
    }
    var cardCollapsed by remember { mutableStateOf(false) }
    var activeRoute by remember { mutableStateOf<PopularRoute?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(350.dp)
        ) {
            if (BuildConfig.YANDEX_MAPKIT_KEY.isNotBlank()) {
                YandexMapCard(
                    modifier = Modifier.matchParentSize(),
                    activeTrip = activeTrip,
                    onRideTap = onRideTap,
                    showPrivacyNotice = false
                )
            } else {
                MapPreview(Modifier.matchParentSize())
            }
            // Подсказка-маршрут плавает в нижней части карты: свайп вправо → язычок, тап → назад.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(10.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    AnimatedVisibility(
                        visible = !cardCollapsed,
                        enter = slideInHorizontally { it } + fadeIn(),
                        exit = slideOutHorizontally { it } + fadeOut()
                    ) {
                        QuickSearchCard(
                            feed = remember(popular, liveFeed) { mapFeedFrom(popular, liveFeed) },
                            onCollapse = { cardCollapsed = true },
                            onRouteChange = { activeRoute = it },
                            compact = true
                        )
                    }
                    AnimatedVisibility(
                        visible = cardCollapsed,
                        modifier = Modifier.align(Alignment.End),
                        enter = slideInHorizontally { it } + fadeIn(),
                        exit = slideOutHorizontally { it } + fadeOut()
                    ) {
                        Surface(
                            onClick = { cardCollapsed = false },
                            shape = RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp),
                            color = CanonSurface,
                            shadowElevation = 4.dp
                        ) {
                            Icon(
                                Icons.Default.ArrowBackIosNew,
                                contentDescription = appText("Показать популярный маршрут", "Популяр маршрутты күрһәтеү"),
                                tint = CanonGreen2,
                                modifier = Modifier.padding(vertical = 14.dp, horizontal = 12.dp).size(16.dp)
                            )
                        }
                    }
                }
            }
        }
        // Кнопки — отдельный блок ПОД картой (не плавают на ней).
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { onFind(activeRoute ?: popular.firstOrNull() ?: demoPopularRoutes.first()) },
                modifier = Modifier.weight(1.25f).height(52.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2),
                contentPadding = PaddingValues(horizontal = 10.dp)
            ) {
                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(6.dp))
                Text(appText("Найти поездку", "Сәфәр табыу"), fontWeight = FontWeight.Black, fontSize = 14.sp, maxLines = 1)
            }
            Button(
                onClick = onDriver,
                modifier = Modifier.weight(0.95f).height(52.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CanonGold, contentColor = CanonGoldInk),
                contentPadding = PaddingValues(horizontal = 10.dp)
            ) {
                Icon(Icons.Default.DirectionsCar, contentDescription = null, tint = CanonGoldInk, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(6.dp))
                Text(appText("Я водитель", "Мин водитель"), fontWeight = FontWeight.Black, fontSize = 13.sp, maxLines = 1)
            }
        }
    }
}

// Приветствие по времени суток (утро/день/вечер/ночь).
@Composable
private fun timeGreeting(name: String): String {
    val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    return when (hour) {
        in 5..10 -> appText("Доброе утро, $name", "Хәйерле иртә, $name")
        in 11..16 -> appText("Добрый день, $name", "Хәйерле көн, $name")
        in 17..22 -> appText("Добрый вечер, $name", "Хәйерле кис, $name")
        else -> appText("Доброй ночи, $name", "Тыныс төн, $name")
    }
}

@Composable
private fun HomeHeader(onSos: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(timeGreeting(ApiClient.cachedName() ?: "Байрас"), color = CanonMuted, fontSize = 14.sp)
            Text(
                appText("Куда поедем?", "Ҡайҙа барабыҙ?"),
                color = CanonGreen,
                fontSize = 28.sp,
                lineHeight = 29.sp,
                fontWeight = FontWeight.Black
            )
        }
        // Тумблер день/ночь: иконка солнца в тёмной теме (тап → светлая), луны в светлой (тап → тёмная).
        val themeCtx = LocalContext.current
        val isDarkNow = appIsDark()
        Surface(
            modifier = Modifier
                .bounceClick {
                    val newDark = !isDarkNow
                    ThemePrefs.darkOverride = newDark
                    themeCtx.getSharedPreferences("yuldash_theme", Context.MODE_PRIVATE)
                        .edit().putBoolean("dark_override", newDark).apply()
                }
                .size(44.dp),
            shape = RoundedCornerShape(16.dp),
            color = CanonSurface,
            border = BorderStroke(1.dp, CanonBorder)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    if (isDarkNow) Icons.Default.LightMode else Icons.Default.DarkMode,
                    contentDescription = appText(
                        if (isDarkNow) "Светлая тема" else "Тёмная тема",
                        if (isDarkNow) "Яҡты тема" else "Ҡараңғы тема"
                    ),
                    tint = CanonGreen2,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Surface(
            modifier = Modifier.bounceClick(onSos),
            shape = RoundedCornerShape(16.dp),
            color = CanonDangerBg,
            border = BorderStroke(1.dp, Color(0xFFFFC8C0))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Shield, contentDescription = "SOS", tint = CanonRed, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text("SOS", color = CanonRed, fontWeight = FontWeight.Black, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun QuickSearchCard(
    feed: List<MapFeedCard>,
    onCollapse: () -> Unit,
    onRouteChange: (PopularRoute) -> Unit,
    compact: Boolean = false
) {
    val safeFeed = feed.ifEmpty { mapFeedFrom(demoPopularRoutes) }
    val count = safeFeed.size
    // Бесконечная карусель: виртуальный счётчик страниц, контент по модулю → всегда вперёд, без отката.
    val pagerState = rememberPagerState(
        initialPage = if (count > 1) count * 1000 else 0,
        pageCount = { if (count > 1) Int.MAX_VALUE else count }
    )

    LaunchedEffect(count) {
        if (count <= 1) return@LaunchedEffect
        while (true) {
            delay(4_500)
            if (!pagerState.isScrollInProgress) {
                pagerState.animateScrollToPage(pagerState.currentPage + 1)
            }
        }
    }
    // Выбор для «Найти поездку» обновляем только на карточках-маршрутах (на цифрах/фактах — держим прошлый).
    LaunchedEffect(pagerState.currentPage, count) {
        safeFeed[pagerState.currentPage % count].route?.let(onRouteChange)
    }
    var dragAccum by remember { mutableStateOf(0f) }

    Card(
        modifier = Modifier.fillMaxWidth().pointerInput(Unit) {
            // Свайп вправо по карточке → свернуть в язычок (карусель листается сама).
            detectHorizontalDragGestures(
                onDragEnd = { if (dragAccum > 110f) onCollapse(); dragAccum = 0f },
                onDragCancel = { dragAccum = 0f }
            ) { _, dragAmount -> if (dragAmount > 0f) dragAccum += dragAmount }
        },
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = CanonCardShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(if (compact) 12.dp else 14.dp)) {
            HorizontalPager(
                state = pagerState,
                pageSpacing = 12.dp,
                userScrollEnabled = false
            ) { page ->
                val card = safeFeed[page % count]
                val icon = when (card.kind) {
                    FeedKind.Route -> Icons.Default.Star
                    FeedKind.Live -> Icons.Default.Bolt
                    FeedKind.Top -> Icons.Default.EmojiEvents
                    FeedKind.Fact -> Icons.Default.Lightbulb
                    FeedKind.Community -> Icons.Default.Favorite
                }
                // Единый макет: бейдж+пилюля (верх) · заголовок фикс.высоты · подпись+точки (низ).
                // Фикс. высота заголовка → все карточки ровно одного размера, карусель не «прыгает».
                Column(verticalArrangement = Arrangement.spacedBy(if (compact) 7.dp else 9.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = CanonMint, shape = RoundedCornerShape(999.dp)) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    appText(card.badge, card.badgeBa),
                                    color = CanonGreen2, fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        Surface(color = CanonMint, shape = RoundedCornerShape(14.dp)) {
                            Text(
                                appText(card.pill, card.pillBa),
                                modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                                color = CanonGreen, fontWeight = FontWeight.Black,
                                fontSize = 13.sp, maxLines = 1
                            )
                        }
                    }
                    Box(
                        modifier = Modifier.height(if (compact) 46.dp else 54.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            appText(card.title, card.titleBa),
                            color = CanonGreen, fontWeight = FontWeight.Black,
                            fontSize = if (compact) 19.sp else 23.sp,
                            lineHeight = if (compact) 22.sp else 26.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            appText(card.sub, card.subBa),
                            color = CanonMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            val active = pagerState.currentPage % count
                            safeFeed.forEachIndexed { index, _ ->
                                Box(
                                    modifier = Modifier
                                        .size(if (index == active) 7.dp else 6.dp)
                                        .background(if (index == active) CanonGreen2 else CanonBorder, CircleShape)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SeniorAccessCard(onSimpleMode: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .bounceClick(onSimpleMode),
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = CanonItemShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = BorderStroke(1.dp, Color(0x1A0B6B3A))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(color = CanonMint, shape = CircleShape) {
                Icon(
                    Icons.Default.VolumeUp,
                    contentDescription = null,
                    tint = CanonGreen2,
                    modifier = Modifier.padding(8.dp).size(20.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    appText("Простой режим", "Ябай режим"),
                    color = CanonText,
                    fontWeight = FontWeight.Black,
                    fontSize = 15.sp,
                    lineHeight = 18.sp
                )
                Text(
                    appText("Крупные кнопки и голос", "Ҙур төймәләр һәм тауыш"),
                    color = CanonMuted,
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Switch(
                checked = false,
                onCheckedChange = { onSimpleMode() }
            )
        }
    }
}

// Координаты для карты (Башкортостан). Старт — Баймаҡ, финиш — Сибай.
private val BaymakPoint = Point(52.5911, 58.3222)
private val SibayPoint = Point(52.7236, 58.6651)
// Согласие на показ геолокации — общий флаг (Профиль → Конфиденциальность ↔ карта).
// Радиус «рядом»: поездки, чья точка выезда дальше — отсекаем (только когда знаем позицию клиента).
private const val NEARBY_RADIUS_KM = 50.0

internal object LocationPrefs {
    var sharingEnabled by mutableStateOf(false)
    // Последняя позиция клиента (с карты) — для «сколько в N км от тебя» в «Ближайших поездках».
    var lastLat by mutableStateOf<Double?>(null)
    var lastLng by mutableStateOf<Double?>(null)
}

private val MapMidPoint = Point(52.55, 58.49) // южнее центра маршрута → точки рисуются в верхней части, не под плашкой

// Город → точка: частые города Башкортостана мгновенно; прочие догружает геокодер (см. YandexMapCard).
private fun cityPoint(city: String): Point? = when (city.trim().lowercase()) {
    "баймаҡ", "баймак" -> BaymakPoint
    "сибай" -> SibayPoint
    "темясово" -> Point(52.9686, 58.3206)
    "уфа" -> Point(54.7388, 55.9721)
    "учалы" -> Point(54.3050, 59.4040)
    "магнитогорск" -> Point(53.4072, 58.9794)
    "стерлитамак" -> Point(53.6303, 55.9311)
    "салават" -> Point(53.3617, 55.9244)
    "нефтекамск" -> Point(56.0911, 54.2486)
    "октябрьский" -> Point(54.4817, 53.4708)
    "белорецк" -> Point(53.9694, 58.4097)
    "ишимбай" -> Point(53.4528, 56.0386)
    "туймазы" -> Point(54.6014, 53.6947)
    "кумертау" -> Point(52.7639, 55.7964)
    else -> null
}

// Маркер-«ценник» (стиль Яндекс/Airbnb): белая пилюля с ценой, цветная рамка, остриё вниз.
// Boosted-поездка — золотой акцент, обычная — фирменный зелёный.
// Метка «моя геопозиция»: круглая точка (тень + белое кольцо + зелёный центр).
private fun userPuckBitmap(): Bitmap {
    val size = 64
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val cx = size / 2f
    val cy = size / 2f
    c.drawCircle(cx, cy, 19f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.parseColor("#22000000") })
    c.drawCircle(cx, cy, 16f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE })
    c.drawCircle(cx, cy, 11f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.parseColor("#0B6B3A") })
    return bmp
}

// Флажок пункта назначения (точка Б) — зелёный вымпел на флагштоке. Якорь у основания.
private fun destFlagBitmap(): Bitmap {
    val w = 50
    val h = 62
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val green = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.parseColor("#0B6B3A") }
    val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
    val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22000000 }
    c.drawOval(android.graphics.RectF(4f, h - 12f, 22f, h - 2f), shadow)   // тень у земли
    c.drawRect(11f, 8f, 14.5f, h - 6f, green)                              // флагшток
    // полотнище: белая кайма + зелёный вымпел
    c.drawPath(android.graphics.Path().apply { moveTo(14.5f, 6f); lineTo(46f, 16f); lineTo(14.5f, 28f); close() }, white)
    c.drawPath(android.graphics.Path().apply { moveTo(16f, 9.5f); lineTo(41f, 16f); lineTo(16f, 24.5f); close() }, green)
    c.drawCircle(12.7f, h - 6f, 5f, white)                                 // точка у основания
    c.drawCircle(12.7f, h - 6f, 3f, green)
    return bmp
}

private fun ridePinBitmap(price: String, boosted: Boolean): Bitmap {
    val accent = android.graphics.Color.parseColor(if (boosted) "#C98A00" else "#0B6B3A")
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent
        textSize = 34f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val padX = 22f
    val padY = 13f
    val pointer = 16f
    val pad = 6f // запас под тень
    val pillH = textPaint.textSize + padY * 2
    val pillW = textPaint.measureText(price) + padX * 2
    val bmp = Bitmap.createBitmap(
        (pillW + pad * 2).toInt(),
        (pillH + pointer + pad * 2).toInt(),
        Bitmap.Config.ARGB_8888
    )
    val c = Canvas(bmp)
    val left = pad; val right = pad + pillW
    val pillTop = pad + pointer; val pillBottom = pillTop + pillH
    val radius = pillH / 2
    val cx = (left + right) / 2
    val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
    val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f; color = accent
    }
    val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22000000 }
    // остриё СВЕРХУ (смотрит на город), пилюля под ним → цена ниже названия города
    val tip = android.graphics.Path().apply {
        moveTo(cx - pointer / 2, pillTop + 2f)
        lineTo(cx + pointer / 2, pillTop + 2f)
        lineTo(cx, pad)
        close()
    }
    c.drawRoundRect(left, pillTop + 3f, right, pillBottom + 3f, radius, radius, shadow)
    c.drawPath(tip, white)
    c.drawPath(tip, border)
    c.drawRoundRect(left, pillTop, right, pillBottom, radius, radius, white)
    c.drawRoundRect(left, pillTop, right, pillBottom, radius, radius, border)
    val fm = textPaint.fontMetrics
    val ty = pillTop + pillH / 2 - (fm.ascent + fm.descent) / 2
    c.drawText(price, left + padX, ty, textPaint)
    return bmp
}

/**
 * Настоящая Яндекс-карта на вкладке «Карта». Инициализируется ЛЕНИВО (только когда
 * экран открыт) — экономим бесплатный тариф MapKit. Поверх карты — наши брендовые
 * накладки: метки городов, пилюля «43 км» и плашка-замок про приватность геолокации.
 *
 * Приватность: точную точку не показываем — рисуем приблизительные зоны (круги ~600 м)
 * у старта (зелёный) и финиша (золотой), как обещает плашка.
 */
@Composable
private fun YandexMapCard(
    modifier: Modifier = Modifier,
    activeTrip: Ride? = null,
    onRideTap: (Ride) -> Unit = {},
    showPrivacyNotice: Boolean = true
) {
    val context = LocalContext.current
    // Геолокация управляется из Профиль → Конфиденциальность (общий LocationPrefs); FAB «к себе» тоже включает.
    var lastUserPoint by remember { mutableStateOf<Point?>(null) }
    val locationPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) LocationPrefs.sharingEnabled = true
    }
    val nightMap = appIsDark()   // тёмная тема → ночной стиль карты
    // Свежие ссылки на активную поездку/тап, чтобы tap-listener не «застревал» на старых данных.
    val currentTrip by rememberUpdatedState(activeTrip)
    val currentOnTap by rememberUpdatedState(onRideTap)
    val tapListener = remember {
        MapObjectTapListener { obj, _ ->
            val trip = currentTrip?.takeIf { it.id == obj.userData as? String }
            if (trip != null) currentOnTap(trip)
            trip != null
        }
    }
    val mapView = remember {
        MapKitFactory.setLocale("ru_RU")   // карта на русском — без дублей англ./транслита
        MapKitFactory.initialize(context)
        MapView(context).also { view ->
            val map = view.mapWindow.map
            map.isNightModeEnabled = nightMap
            map.move(CameraPosition(MapMidPoint, 9.0f, 0f, 0f))
            // Маршруты-линии + ценники поездок рисуются ниже (LaunchedEffect, по реальным заказам).
            // Карта внутри прокручиваемого списка: на касании просим родителя (LazyColumn)
            // не перехватывать жест — иначе тап по маркеру и панорамирование «съедает» скролл.
            view.setOnTouchListener { v, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                }
                false
            }
        }
    }
    // Тёмная тема → ночной стиль карты (обновляется при смене темы).
    LaunchedEffect(nightMap) { mapView.mapWindow.map.isNightModeEnabled = nightMap }
    // Жесты пальцами: щипок-зум держит мою точку по центру; панорама пальцем — выключает слежение.
    DisposableEffect(Unit) {
        val map = mapView.mapWindow.map
        var prevZoom = map.cameraPosition.zoom
        val cl = CameraListener { _, pos, reason, finished ->
            // Щипок-зум пальцами → держим мою точку по центру (как кнопки зума). Панораму не трогаем.
            if (reason == CameraUpdateReason.GESTURES && finished &&
                LocationPrefs.sharingEnabled && kotlin.math.abs(pos.zoom - prevZoom) > 0.05f) {
                lastUserPoint?.let { p -> map.move(CameraPosition(p, pos.zoom, 0f, 0f), Animation(Animation.Type.SMOOTH, 0.2f), null) }
            }
            prevZoom = pos.zoom
        }
        map.addCameraListener(cl)
        onDispose { map.removeCameraListener(cl) }
    }
    // «Моя геопозиция» — СВОЯ точка-плейсмарк через LocationManager (полный контроль, без дефолтной стрелки MapKit).
    DisposableEffect(LocationPrefs.sharingEnabled) {
        val map = mapView.mapWindow.map
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        var placemark: com.yandex.mapkit.map.PlacemarkMapObject? = null
        var firstFix = true
        val listener = object : android.location.LocationListener {
            override fun onLocationChanged(loc: android.location.Location) {
                val pt = Point(loc.latitude, loc.longitude)
                lastUserPoint = pt   // запоминаем — кнопка «к себе» центрирует на ней в любой момент
                LocationPrefs.lastLat = loc.latitude; LocationPrefs.lastLng = loc.longitude
                val pm = placemark
                if (pm == null) {
                    placemark = map.mapObjects.addPlacemark(pt).apply {
                        setIcon(ImageProvider.fromBitmap(userPuckBitmap()))
                    }
                } else {
                    pm.geometry = pt
                }
                if (firstFix) {
                    firstFix = false
                    map.move(CameraPosition(pt, 15f, 0f, 0f), Animation(Animation.Type.SMOOTH, 0.5f), null)
                }
            }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
        }
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (LocationPrefs.sharingEnabled && granted) {
            try {
                lm.requestLocationUpdates(android.location.LocationManager.GPS_PROVIDER, 2000L, 5f, listener)
                lm.requestLocationUpdates(android.location.LocationManager.NETWORK_PROVIDER, 2000L, 5f, listener)
                (lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                    ?: lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER))?.let { listener.onLocationChanged(it) }
            } catch (e: SecurityException) {
            } catch (e: IllegalArgumentException) {
            }
        }
        onDispose {
            lm.removeUpdates(listener)
            placemark?.let { map.mapObjects.remove(it) }
        }
    }
    // Маршрут на карте — ТОЛЬКО для активной (подтверждённой) поездки, как в Яндекс Такси:
    // линия from→to + ценник у отправления (под городом) + флажок назначения. Завершилась → всё исчезает.
    val cityCache = remember { mutableMapOf<String, Point>() }
    val tripScope = rememberCoroutineScope()
    DisposableEffect(activeTrip) {
        val map = mapView.mapWindow.map
        val added = mutableListOf<com.yandex.mapkit.map.MapObject>()
        var roadSession: com.yandex.mapkit.directions.driving.DrivingSession? = null
        val job = tripScope.launch {
            val trip = activeTrip ?: return@launch
            suspend fun resolve(city: String): Point? {
                val k = city.trim()
                cityPoint(k)?.let { return it }
                cityCache[k]?.let { return it }
                val hit = GeocoderClient.suggest(k).firstOrNull() ?: return null
                return Point(hit.lat, hit.lon).also { cityCache[k] = it }
            }
            val fromPt = resolve(trip.from) ?: return@launch
            val toPt = resolve(trip.to)
            if (toPt != null) {
                val straightLine = map.mapObjects.addPolyline(Polyline(listOf(fromPt, toPt))).apply {
                    setStrokeColor(0xCC0B6B3A.toInt())
                    strokeWidth = 4f
                }
                added += straightLine
                // Маршрут ПО ДОРОГАМ (full SDK + DrivingRouter). Ошибка/нет квоты роутинга → остаётся прямая линия (фоллбэк, без поломки карты).
                roadSession = runCatching {
                    val router = com.yandex.mapkit.directions.DirectionsFactory.getInstance()
                        .createDrivingRouter(com.yandex.mapkit.directions.driving.DrivingRouterType.COMBINED)
                    val reqPoints = listOf(
                        com.yandex.mapkit.RequestPoint(fromPt, com.yandex.mapkit.RequestPointType.WAYPOINT, null, null, null),
                        com.yandex.mapkit.RequestPoint(toPt, com.yandex.mapkit.RequestPointType.WAYPOINT, null, null, null),
                    )
                    router.requestRoutes(
                        reqPoints,
                        com.yandex.mapkit.directions.driving.DrivingOptions(),
                        com.yandex.mapkit.directions.driving.VehicleOptions(),
                        object : com.yandex.mapkit.directions.driving.DrivingSession.DrivingRouteListener {
                            override fun onDrivingRoutes(routes: MutableList<com.yandex.mapkit.directions.driving.DrivingRoute>) {
                                val r = routes.firstOrNull() ?: return
                                runCatching {
                                    map.mapObjects.remove(straightLine)
                                    added.remove(straightLine)
                                    added += map.mapObjects.addPolyline(r.geometry).apply {
                                        setStrokeColor(0xCC0B6B3A.toInt()); strokeWidth = 5f
                                    }
                                }
                            }
                            override fun onDrivingRoutesError(error: com.yandex.runtime.Error) { /* фоллбэк: прямая остаётся */ }
                        }
                    )
                }.getOrNull()
                added += map.mapObjects.addPlacemark().apply {
                    geometry = toPt
                    setIcon(ImageProvider.fromBitmap(destFlagBitmap()))
                    setIconStyle(IconStyle().setAnchor(PointF(0.24f, 0.9f)))
                }
                map.move(
                    CameraPosition(Point((fromPt.latitude + toPt.latitude) / 2, (fromPt.longitude + toPt.longitude) / 2), 9.5f, 0f, 0f),
                    Animation(Animation.Type.SMOOTH, 0.5f), null
                )
            }
            added += map.mapObjects.addPlacemark().apply {
                geometry = fromPt
                setIcon(ImageProvider.fromBitmap(ridePinBitmap("${trip.price} ₽", trip.boosted)))
                setIconStyle(IconStyle().setAnchor(PointF(0.5f, 0f)))
                userData = trip.id
                addTapListener(tapListener)
            }
        }
        onDispose {
            job.cancel()
            runCatching { roadSession?.cancel() }
            added.forEach { runCatching { map.mapObjects.remove(it) } }
        }
    }
    // Жизненный цикл карты привязан к появлению/скрытию экрана «Карта».
    DisposableEffect(Unit) {
        MapKitFactory.getInstance().onStart()
        mapView.onStart()
        onDispose {
            mapView.onStop()
            MapKitFactory.getInstance().onStop()
        }
    }
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .border(1.dp, Color(0x1A000000), RoundedCornerShape(24.dp))
    ) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        // Кнопки масштаба (как в Яндекс.Картах): правый верх, под чипом расстояния.
        MapZoomControls(
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 14.dp, end = 14.dp),
            onZoomIn = {
                val cam = mapView.mapWindow.map.cameraPosition
                val t = if (LocationPrefs.sharingEnabled) (lastUserPoint ?: cam.target) else cam.target
                mapView.mapWindow.map.move(CameraPosition(t, (cam.zoom + 1f).coerceAtMost(18f), cam.azimuth, cam.tilt), Animation(Animation.Type.SMOOTH, 0.25f), null)
            },
            onZoomOut = {
                val cam = mapView.mapWindow.map.cameraPosition
                val t = if (LocationPrefs.sharingEnabled) (lastUserPoint ?: cam.target) else cam.target
                mapView.mapWindow.map.move(CameraPosition(t, (cam.zoom - 1f).coerceAtLeast(3f), cam.azimuth, cam.tilt), Animation(Animation.Type.SMOOTH, 0.25f), null)
            }
        )
        // Кнопка «к себе» (как в Яндекс.Картах): центр на моей позиции; если выключено — включает.
        Surface(
            onClick = {
                when {
                    LocationPrefs.sharingEnabled -> lastUserPoint?.let { p ->
                        mapView.mapWindow.map.move(
                            CameraPosition(p, 15f, 0f, 0f),
                            Animation(Animation.Type.SMOOTH, 0.4f), null
                        )
                    }
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED -> LocationPrefs.sharingEnabled = true
                    else -> locationPermLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }
            },
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 100.dp, end = 14.dp).size(38.dp).zIndex(6f),
            shape = RoundedCornerShape(13.dp),
            color = Color.White,
            shadowElevation = 4.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.NearMe,
                    contentDescription = appText("Где я", "Мин ҡайҙа"),
                    tint = if (LocationPrefs.sharingEnabled) CanonGreen2 else CanonMuted,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        if (showPrivacyNotice) {
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(14.dp),
                colors = CardDefaults.cardColors(containerColor = CanonSurface),
                shape = RoundedCornerShape(18.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(appText("Геолокация откроется после подтверждения поездки", "Геолокация сәфәр раҫланғас асыла"), fontSize = 15.sp)
                }
            }
        }
    }
}

@Composable
private fun MapMarkerHitTargets(rides: List<Ride>, onRideTap: (Ride) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().zIndex(5f)) {
        val byCity = rides.groupBy { it.from }
        byCity["Баймаҡ"]?.firstOrNull()?.let { ride ->
            Box(
                Modifier
                    .offset(x = maxWidth * 0.34f, y = maxHeight * 0.18f)
                    .size(width = 96.dp, height = 64.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onRideTap(ride) }
                    )
            )
        }
        byCity["Сибай"]?.firstOrNull()?.let { ride ->
            Box(
                Modifier
                    .offset(x = maxWidth * 0.62f, y = maxHeight * 0.39f)
                    .size(width = 112.dp, height = 72.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onRideTap(ride) }
                    )
            )
        }
    }
}

// Дистанция между городами по координатам (для превью маршрута). null — если город неизвестен.
internal fun cityDistanceText(from: String, to: String): String? {
    val a = cityPoint(from) ?: return null
    val b = cityPoint(to) ?: return null
    val sLat = Math.sin(Math.toRadians(b.latitude - a.latitude) / 2)
    val sLon = Math.sin(Math.toRadians(b.longitude - a.longitude) / 2)
    val h = sLat * sLat + Math.cos(Math.toRadians(a.latitude)) * Math.cos(Math.toRadians(b.latitude)) * sLon * sLon
    return "${Math.round(2 * 6371.0 * Math.asin(Math.sqrt(h)))} км"
}

// Пикер точки сбора: полноэкранная карта + фикс-пин в центре. Двигаешь карту — пин на месте встречи.
@Composable
internal fun PickupPickerOverlay(
    initial: Point?,
    onConfirm: (Double, Double) -> Unit,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    val mapView = remember {
        runCatching { MapKitFactory.initialize(ctx) }   // локаль уже задана при первой карте; повторный setLocale кинул бы исключение
        MapView(ctx).also { v ->
            v.mapWindow.map.move(CameraPosition(initial ?: MapMidPoint, if (initial != null) 15f else 11f, 0f, 0f))
        }
    }
    DisposableEffect(Unit) {
        MapKitFactory.getInstance().onStart(); mapView.onStart()
        onDispose { mapView.onStop(); MapKitFactory.getInstance().onStop() }
    }
    Box(Modifier.fillMaxSize().background(CanonBg)) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        // фикс-пин в центре (кончик смотрит на центр карты)
        Icon(
            Icons.Default.LocationOn, contentDescription = null, tint = CanonRed,
            modifier = Modifier.align(Alignment.Center).size(48.dp).offset(y = (-24).dp)
        )
        Row(
            Modifier.align(Alignment.TopStart).fillMaxWidth().statusBarsPadding().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(onClick = onDismiss, shape = CircleShape, color = CanonSurface, shadowElevation = 3.dp) {
                Icon(Icons.Default.ArrowBackIosNew, contentDescription = appText("Назад", "Кире"), tint = CanonText, modifier = Modifier.padding(12.dp).size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Surface(shape = RoundedCornerShape(14.dp), color = CanonSurface, shadowElevation = 3.dp) {
                Text(appText("Двигай карту — пин на месте встречи", "Картаны күсер — пин осрашыу урынында"), Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = CanonText, fontSize = 13.sp)
            }
        }
        Button(
            onClick = {
                val t = mapView.mapWindow.map.cameraPosition.target
                onConfirm(t.latitude, t.longitude)
            },
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(16.dp).height(54.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)
        ) {
            Icon(Icons.Default.LocationOn, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(appText("Готово — точка здесь", "Әҙер — нөктә бында"), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
internal fun MapPreview(modifier: Modifier = Modifier, from: String = "Баймаҡ", to: String = "Сибай", distance: String? = "43 км") {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.linearGradient(listOf(Color(0xFFDDEEDF), Color(0xFFEEF3E5), Color(0xFFFFE7AC))),
                RoundedCornerShape(24.dp)
            )
            .border(1.dp, Color(0x1A000000), RoundedCornerShape(24.dp))
            .padding(0.dp)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Color.White.copy(alpha = 0.55f), radius = 170f, center = Offset(size.width * 0.05f, size.height * 0.12f))
            drawCircle(Color(0xFF0B6B3A).copy(alpha = 0.08f), radius = 210f, center = Offset(size.width * 0.95f, size.height * 0.88f))
            val route = Path().apply {
                moveTo(size.width * 0.16f, size.height * 0.28f)
                cubicTo(
                    size.width * 0.36f,
                    size.height * 0.18f,
                    size.width * 0.54f,
                    size.height * 0.62f,
                    size.width * 0.84f,
                    size.height * 0.68f
                )
            }
            val sideRoad = Path().apply {
                moveTo(size.width * 0.02f, size.height * 0.58f)
                cubicTo(size.width * 0.28f, size.height * 0.50f, size.width * 0.48f, size.height * 0.38f, size.width * 0.74f, size.height * 0.18f)
            }
            drawPath(sideRoad, Color.White.copy(alpha = 0.75f), style = Stroke(width = 11f, cap = StrokeCap.Round))
            drawPath(sideRoad, Color(0xFF8DB39A).copy(alpha = 0.45f), style = Stroke(width = 3f, cap = StrokeCap.Round))
            drawPath(route, Color.White, style = Stroke(width = 22f, cap = StrokeCap.Round))
            drawPath(route, Color(0xFF0B6B3A), style = Stroke(width = 7f, cap = StrokeCap.Round))
            drawCircle(Color(0xFF0B6B3A), radius = 15f, center = Offset(size.width * 0.16f, size.height * 0.28f))
            drawCircle(Color(0xFFE2A11B), radius = 15f, center = Offset(size.width * 0.84f, size.height * 0.68f))
        }
        MapLabel(from, Modifier.align(Alignment.TopStart).padding(20.dp))
        MapLabel(to, Modifier.align(Alignment.CenterEnd).padding(20.dp))
        distance?.let { dist ->
            Surface(
                modifier = Modifier.align(Alignment.TopEnd).padding(18.dp),
                color = Color.White.copy(alpha = 0.92f),
                shape = RoundedCornerShape(999.dp)
            ) {
                Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Route, contentDescription = null, modifier = Modifier.size(16.dp), tint = CanonGreen2)
                    Spacer(Modifier.width(5.dp))
                    Text(dist, fontWeight = FontWeight.Bold)
                }
            }
        }
        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(14.dp),
            colors = CardDefaults.cardColors(containerColor = CanonSurface),
            shape = RoundedCornerShape(18.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Lock, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(appText("Геолокация откроется после подтверждения поездки", "Геолокация сәфәр раҫланғас асыла"), fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun MapLabel(text: String, modifier: Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = Color.White,
        shadowElevation = 3.dp
    ) {
        Text(text, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontWeight = FontWeight.Bold)
    }
}

// Кнопки масштаба карты ＋/− (стек справа, как в Яндекс.Картах).
@Composable
private fun MapZoomControls(modifier: Modifier = Modifier, onZoomIn: () -> Unit, onZoomOut: () -> Unit) {
    Surface(modifier = modifier, color = Color.White.copy(alpha = 0.95f), shape = RoundedCornerShape(13.dp), shadowElevation = 3.dp) {
        Column {
            IconButton(onClick = onZoomIn, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Default.Add, contentDescription = appText("Приблизить", "Яҡынайтыу"), tint = CanonGreen2, modifier = Modifier.size(18.dp))
            }
            Box(Modifier.width(20.dp).height(1.dp).background(Color(0x14000000)).align(Alignment.CenterHorizontally))
            IconButton(onClick = onZoomOut, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Default.Remove, contentDescription = appText("Отдалить", "Йыраҡлаштырыу"), tint = CanonGreen2, modifier = Modifier.size(18.dp))
            }
        }
    }
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
                    Text(appText("Байрас · Юлдаш", "Байрас · Юлдаш"), color = CanonMuted, fontSize = 13.sp)
                }
            }
            Text(
                appText(
                    "Откройте банк → Переводы → По номеру телефона (СБП) → вставьте номер и сумму $amountRub ₽.",
                    "Банк ҡушымтаһын асығыҙ → Күсереүҙәр → Телефон номеры буйынса (СБП) → номерҙы һәм $amountRub ₽ сумманы ҡуйығыҙ."
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

fun openTelegramLogin(context: android.content.Context) {
    val bot = BuildConfig.TELEGRAM_BOT
    if (bot.isBlank()) {
        // Бот ещё не зарегистрирован (нет YULDASH_TELEGRAM_BOT в local.properties).
        Toast.makeText(context, "Вход через Telegram скоро · Telegram аша инеү тиҙҙән", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        // Бот по start=auth присылает обратно yuldash://auth/telegram?user_id=...&username=...&first_name=...
        val telegramUrl = "https://t.me/$bot?start=auth"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(telegramUrl)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "Не удалось открыть Telegram · Telegram асып булманы", Toast.LENGTH_SHORT).show()
    }
}

/**
 * VK OAuth через браузер.
 * Клиент: BuildConfig.VK_APP_ID (из local.properties)
 * Редирект: https://yulbash.ru/auth/vk/callback
 */
fun openVKLogin(context: android.content.Context) {
    val vkAppId = BuildConfig.VK_APP_ID
    if (vkAppId.isBlank()) {
        // VK-приложение ещё не заведено (нет YULDASH_VK_APP_ID в local.properties).
        Toast.makeText(context, "Вход через VK скоро · VK аша инеү тиҙҙән", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        // Сервер на /auth/vk/callback меняет code→token и редиректит в yuldash://auth/vk?access_token=...&user_id=...
        val redirectUri = "https://yulbash.ru/auth/vk/callback"
        val scope = "email,phone"
        val vkUrl = "https://oauth.vk.com/authorize?" +
            "client_id=$vkAppId&" +
            "redirect_uri=${Uri.encode(redirectUri)}&" +
            "scope=$scope&" +
            "response_type=code&" +
            "v=5.131"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(vkUrl)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "Не удалось открыть VK · VK асып булманы", Toast.LENGTH_SHORT).show()
    }
}

/**
 * WhatsApp-вход. Пока заглушка «скоро» — настоящий вход требует WhatsApp Business API.
 */
fun openWhatsAppLogin(context: android.content.Context) {
    // Честно: вход «через WhatsApp» нельзя сделать ссылкой wa.me — она открывает чат,
    // но не возвращает подтверждённую личность в приложение. Настоящий вход требует
    // WhatsApp Business API (код на номер, как SMS). До подключения — заглушка «скоро».
    // Приёмник yuldash://auth/whatsapp в YuldashApp готов, если такой бэкенд появится.
    Toast.makeText(context, "Вход через WhatsApp скоро · WhatsApp аша инеү тиҙҙән", Toast.LENGTH_SHORT).show()
}
