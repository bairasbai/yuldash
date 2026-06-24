package com.yuldash.app

import android.content.Intent
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
import androidx.compose.material.icons.filled.LocalHospital
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

private enum class HomeTab {
    Map,
    Rides,
    Request,
    Chat,
    Profile
}

private enum class AppLanguage {
    Ru,
    Ba
}

private data class LocalizedText(
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

private enum class AdPlacement {
    Nearby,
    Route,
    RidesList,
    TripDetails,
    Profile,
    Help
}

private enum class AdStatus {
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

private val LocalAppLanguage = staticCompositionLocalOf { AppLanguage.Ru }

// Адаптивная палитра: один и тот же `CanonX` отдаёт светлый/тёмный цвет по системной теме.
// 410 использований не трогаем — меняется только определение (@Composable-геттер).
// СБП-перевод по номеру телефона (донат/boost) — P2P, без мерчант-аккаунта. Позже вынести в конфиг/бэкенд.
private const val SBP_PHONE_DISPLAY = "+7 (999) 134-82-75"
private const val SBP_PHONE_DIGITS = "+79991348275"

// Тема приложения: null = как в системе, true = тёмная, false = светлая (тумблер день/ночь в шапке).
private object ThemePrefs {
    var darkOverride by mutableStateOf<Boolean?>(null)
}

@Composable
private fun appIsDark(): Boolean = ThemePrefs.darkOverride ?: isSystemInDarkTheme()

private val CanonGreen: Color @Composable get() = if (appIsDark()) Color(0xFF7FE3AB) else Color(0xFF073F25)
private val CanonGreen2: Color @Composable get() = if (appIsDark()) Color(0xFF2FB36E) else Color(0xFF0B6B3A)
private val CanonMint: Color @Composable get() = if (appIsDark()) Color(0xFF143024) else Color(0xFFE7F5EC)
private val CanonYellow: Color @Composable get() = if (appIsDark()) Color(0xFF4A3A14) else Color(0xFFFFE3A1)
// Брендовое золото (как дорога на карте/лого) — заливка акцентной кнопки. Золотое в обеих темах → текст фиксированно тёмный.
private val CanonGold: Color @Composable get() = if (appIsDark()) Color(0xFFE8C36B) else Color(0xFFF5B301)
private val CanonGoldInk: Color = Color(0xFF0B3D20)
private val CanonBg: Color @Composable get() = if (appIsDark()) Color(0xFF0F1613) else Color(0xFFFAFAF6)
private val CanonText: Color @Composable get() = if (appIsDark()) Color(0xFFEAF2EC) else Color(0xFF0B1F14)
private val CanonMuted: Color @Composable get() = if (appIsDark()) Color(0xFF9BA49D) else Color(0xFF686F66)
private val CanonBorder: Color @Composable get() = if (appIsDark()) Color(0x24FFFFFF) else Color(0x1F000000)
private val CanonRed: Color @Composable get() = if (appIsDark()) Color(0xFFFF6B5E) else Color(0xFFD93025)
// Поверхность карточек: была хардкод Color.White — теперь адаптивная.
private val CanonSurface: Color @Composable get() = if (appIsDark()) Color(0xFF192420) else Color(0xFFFFFFFF)
// Подложка опасности/ошибки (SOS, ошибки) — адаптивная (светло-розовая / тёмно-красная).
private val CanonDangerBg: Color @Composable get() = if (appIsDark()) Color(0xFF3A1B18) else Color(0xFFFDECEA)
// Предупреждение/в процессе (pending, черновик): подложка + текст — адаптивные.
private val CanonWarnBg: Color @Composable get() = if (appIsDark()) Color(0xFF3A2E12) else Color(0xFFFFF2D6)
private val CanonWarn: Color @Composable get() = if (appIsDark()) Color(0xFFE8B86A) else Color(0xFFB87400)
private val CanonCardShape = RoundedCornerShape(28.dp)
private val CanonItemShape = RoundedCornerShape(22.dp)

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
private fun appText(ru: String, ba: String): String {
    return if (LocalAppLanguage.current == AppLanguage.Ba) ba else ru
}

private fun appTextFor(language: AppLanguage, ru: String, ba: String): String {
    return if (language == AppLanguage.Ba) ba else ru
}

@Composable
private fun LocalizedText.text(): String = appText(ru, ba)

@Composable
private fun seatsText(count: Int): String = appText("$count места", "$count урын")

@Composable
private fun Ride.timeText(): String = appText(time, timeBa ?: time)

@Composable
private fun Ride.carText(): String = appText(car, carBa ?: car)

@Composable
private fun PopularRoute.minutesText(): String = appText(minutes, minutesBa ?: minutes)

@Composable
private fun PopularRoute.labelText(): String = appText(label, labelBa ?: label)

@Composable
private fun TrustedContact.relationText(): String = appText(relation, relationBa ?: relation)

@Composable
private fun FrequentTrip.titleText(): String = appText(title, titleBa)

@Composable
private fun FrequentTrip.timeHintText(): String = appText(timeHint, timeHintBa)

/** ISO-дата сервера "2026-06-22T22:24:07" → "22.06, 22:24" для карточки поездки. */
private fun formatDepart(iso: String): String = try {
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
)

/** Километры коротко: «2.3 км» вблизи, «243 км» вдали. */
private fun fmtKm(d: Double): String =
    if (d < 10) String.format(java.util.Locale.US, "%.1f", d) else Math.round(d).toString()

/** Категория из UI → (enum бэкенда, признак «с детьми»). */
private fun categoryToApi(ui: String): Pair<String, Boolean> = when (ui) {
    "В больницу" -> "hospital" to false
    "Посылка" -> "parcel" to false
    "С детьми" -> "regular" to true
    else -> "regular" to false
}

// Заявка из строки-маршрута «Откуда → Куда» (голосовая / за близкого) → реальная серверная заявка.
private fun fireRequestFromRoute(route: String, comment: String = "") {
    val parts = route.split("→", "->", "-").map { it.trim() }.filter { it.isNotEmpty() }
    val from = parts.getOrElse(0) { route.trim() }
    val to = parts.getOrElse(1) { "" }
    if (from.isNotBlank()) ApiClient.fireCreateRequest(from, to, 1, "regular", false, comment, 0)
}

// Нативный календарь + часы → строка «ДД.ММ.ГГГГ, ЧЧ:ММ» в поле даты заявки/поездки.
private fun openDateTimePicker(context: android.content.Context, onPicked: (String) -> Unit) {
    val cal = java.util.Calendar.getInstance()
    android.app.DatePickerDialog(
        context,
        { _, y, m, d ->
            android.app.TimePickerDialog(
                context,
                { _, h, min -> onPicked(String.format(java.util.Locale.getDefault(), "%02d.%02d.%d, %02d:%02d", d, m + 1, y, h, min)) },
                cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE), true
            ).show()
        },
        cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH), cal.get(java.util.Calendar.DAY_OF_MONTH)
    ).apply { datePicker.minDate = cal.timeInMillis }.show()
}

private fun apiCategoryToUiFor(language: AppLanguage, category: String, withKids: Boolean): String = when {
    category == "hospital" -> appTextFor(language, "В больницу", "Больницаға")
    category == "parcel" -> appTextFor(language, "Посылка", "Посылка")
    withKids -> appTextFor(language, "С детьми", "Балалар менән")
    else -> appTextFor(language, "Обычная", "Ғәҙәти")
}

private data class Ride(
    val id: String,
    val from: String,
    val to: String,
    val time: String,
    val timeBa: String? = null,
    val driver: String,
    val car: String,
    val carBa: String? = null,
    val price: Int,
    val seats: Int,
    val rating: Double,
    val verified: Boolean,
    val boosted: Boolean,
    val petsAllowed: Boolean = false,
    val childSeat: Boolean = false,
    val womenOnly: Boolean = false,
    val smoking: Boolean = false,
    val baggage: Boolean = false,
    val airConditioner: Boolean = false
)

private data class PopularRoute(
    val from: String,
    val to: String,
    val minutes: String,
    val minutesBa: String? = null,
    val distance: String,
    val nearbyCount: Int,
    val label: String,
    val labelBa: String? = null
)

private data class TrustedContact(
    val name: String,
    val relation: String,
    val phone: String,
    val notifyByDefault: Boolean,
    val id: Int = 0,
    val relationBa: String? = null
)

private data class FrequentTrip(
    val title: String,
    val titleBa: String,
    val from: String,
    val to: String,
    val timeHint: String,
    val timeHintBa: String,
    val categoryKey: String
)

private data class LocalRequest(
    val title: String,
    val route: String,
    val time: String,
    val passenger: String,
    val status: String,
    val price: Int = 0,
    val trustedContact: String? = null,
    val voiceUrl: String? = null
)

private data class LocalVoiceMessage(
    val author: String,
    val transcript: String,
    val time: String,
    val audioPath: String? = null,
    val durationSec: Int = 0
)

// Запись голоса с микрофона: MediaRecorder → m4a в кэше приложения.
private class VoiceRecorder(private val context: Context) {
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

private val demoFrequentTrips = listOf(
    FrequentTrip("В больницу", "Больницаға", "Баймаҡ", "Сибай", "завтра утром", "иртәгә иртән", "hospital"),
    FrequentTrip("К детям", "Балаларға", "Баймаҡ", "Уфа", "пятница, 08:00", "йома, 08:00", "intercity"),
    FrequentTrip("На рынок", "Баҙарға", "Баймаҡ", "Сибай", "сегодня после 15:00", "бөгөн 15:00-тан һуң", "regular")
)

private data class PartnerAd(
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

private data class AdStats(
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
    }

    fun trackAdClick(ad: PartnerAd) {
        val current = adStats[ad.id] ?: AdStats()
        adStats = adStats + (ad.id to current.copy(clicks = current.clicks + 1))
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
            Screen.Login -> LoginScreen(
                currentLanguage = language,
                onToggleLanguage = {
                    language = if (language == AppLanguage.Ru) AppLanguage.Ba else AppLanguage.Ru
                },
                onContinue = { openHome() }
            )
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

private fun shareRide(context: android.content.Context, text: String, chooserTitle: String) {
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

@Composable
private fun LoginScreen(
    currentLanguage: AppLanguage,
    onToggleLanguage: () -> Unit,
    onContinue: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = CanonBg
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            contentPadding = PaddingValues(bottom = 28.dp)
        ) {
            item {
                Column {
                    BrandHero(
                        currentLanguage = currentLanguage,
                        onToggleLanguage = onToggleLanguage,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(360.dp)
                    )
                    LoginFormCard(
                        currentLanguage = currentLanguage,
                        onContinue = onContinue,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp)
                            .offset(y = (-44).dp)
                    )
                }
            }
            item {
                TrustCard(
                    currentLanguage = currentLanguage,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                )
            }
            item {
                SafetyFooter(
                    currentLanguage = currentLanguage,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                )
            }
        }
    }
}

@Composable
private fun LoginFormCard(
    currentLanguage: AppLanguage,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(0) }            // 0 — ввод телефона, 1 — ввод кода
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            Text(
                text = appTextFor(currentLanguage, "Войти по телефону", "Телефон аша инеү"),
                color = CanonText,
                fontSize = 24.sp,
                lineHeight = 28.sp,
                fontWeight = FontWeight.Black
            )
            Text(
                text = if (step == 0) appTextFor(currentLanguage, "Номер будет скрыт до подтверждения брони.", "Телефон номеры бронь раҫланғанға тиклем йәшерелә.")
                else appTextFor(currentLanguage, "Код отправлен на $phone", "Код $phone номерыңа ебәрелде"),
                color = CanonMuted,
                fontSize = 16.sp,
                lineHeight = 22.sp
            )
            if (step == 0) {
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it; error = null },
                    placeholder = { Text(appTextFor(currentLanguage, "Номер телефона", "Телефон номеры"), fontSize = 16.sp) },
                    leadingIcon = {
                        Icon(Icons.Default.PhoneLocked, contentDescription = null, tint = CanonMuted)
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )
            } else {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.filter { c -> c.isDigit() }.take(6); error = null },
                    placeholder = { Text(appTextFor(currentLanguage, "Код из SMS", "SMS коды"), fontSize = 16.sp) },
                    leadingIcon = {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = CanonMuted)
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )
                TextButton(onClick = { step = 0; code = ""; error = null }) {
                    Text(appTextFor(currentLanguage, "Изменить номер", "Номерҙы үҙгәртеү"), color = CanonGreen2)
                }
            }
            error?.let {
                Text(it, color = CanonRed, fontSize = 14.sp, lineHeight = 19.sp)
            }
            // строки ошибок считаем здесь (в @Composable-контексте); в onClick отдаём готовый текст
            val errEnterPhone = appTextFor(currentLanguage, "Введите номер телефона", "Телефон номерын индерегеҙ")
            val errSendFail = appTextFor(currentLanguage, "Не получилось отправить код. Повтори.", "Код ебәреп булманы. Ҡабатла.")
            val errEnterCode = appTextFor(currentLanguage, "Введите код из SMS", "SMS кодын индерегеҙ")
            val errBadCode = appTextFor(currentLanguage, "Неверный код", "Код дөрөҫ түгел")
            Button(
                onClick = {
                    if (loading) return@Button
                    error = null
                    if (step == 0) {
                        val p = phone.trim()
                        if (p.length < 5) {
                            error = errEnterPhone
                            return@Button
                        }
                        loading = true
                        scope.launch {
                            ApiClient.requestCode(p)
                                .onSuccess { loading = false; step = 1 }
                                .onFailure {
                                    loading = false
                                    error = errSendFail
                                }
                        }
                    } else {
                        if (code.length < 4) {
                            error = errEnterCode
                            return@Button
                        }
                        loading = true
                        scope.launch {
                            ApiClient.verifyCode(phone.trim(), code.trim(), "")
                                .onSuccess { loading = false; onContinue() }
                                .onFailure {
                                    loading = false
                                    error = errBadCode
                                }
                        }
                    }
                },
                enabled = !loading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)
            ) {
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Text(
                        text = if (step == 0) appTextFor(currentLanguage, "Получить код", "Код алыу") else appTextFor(currentLanguage, "Войти", "Инеү"),
                        fontWeight = FontWeight.Black,
                        fontSize = 16.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun BrandHero(
    currentLanguage: AppLanguage,
    onToggleLanguage: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(bottomStart = 34.dp, bottomEnd = 34.dp))
            .background(Color(0xFF0B6B3A))
    ) {
        Image(
            painter = painterResource(R.drawable.login_car_hero_square),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color(0x99175F3F),
                        0.44f to Color(0x33175F3F),
                        0.72f to Color(0x2206130F),
                        1f to Color(0xE606130F)
                    )
                )
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Color(0x66105239),
                        0.55f to Color.Transparent,
                        1f to Color(0x3306130F)
                    )
                )
        )

        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 16.dp, end = 24.dp)
                .clip(RoundedCornerShape(999.dp))
                .clickable(onClick = onToggleLanguage)
                .background(Color.White.copy(alpha = 0.16f))
                .border(1.dp, Color.White.copy(alpha = 0.30f), RoundedCornerShape(999.dp))
                .padding(horizontal = 15.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Default.Language, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            Text(
                text = if (currentLanguage == AppLanguage.Ba) "БАШ" else "РУС",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = Color.White, modifier = Modifier.size(17.dp))
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(top = 52.dp, start = 28.dp, end = 22.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(68.dp),
                    shape = CircleShape,
                    color = Color.White,
                    shadowElevation = 6.dp
                ) {
                    Image(
                        painter = painterResource(R.drawable.yuldash_logo),
                        contentDescription = null,
                        modifier = Modifier.padding(8.dp),
                        contentScale = ContentScale.Fit
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Юлдаш", color = Color.White, fontSize = 40.sp, lineHeight = 42.sp, fontWeight = FontWeight.Black)
                    Text(
                        text = appTextFor(currentLanguage, "Поездки между своими", "Үҙебеҙҙекеләр араһында юллашыу"),
                        color = Color.White.copy(alpha = 0.94f),
                        fontSize = 18.sp,
                        lineHeight = 22.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HeroPill(Icons.Default.PhoneLocked, appTextFor(currentLanguage, "Скрытый номер", "Йәшерен номер"))
                HeroPill(Icons.Default.Pin, appTextFor(currentLanguage, "Код посадки", "Ултырыу коды"))
            }
        }

    }
}

@Composable
private fun HeroPill(icon: ImageVector, text: String) {
    Surface(
        color = Color.White.copy(alpha = 0.16f),
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.34f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            Text(
                text = text,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun TrustCard(currentLanguage: AppLanguage, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = RoundedCornerShape(22.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            TrustRow(
                icon = Icons.Default.PhoneLocked,
                title = appTextFor(currentLanguage, "Телефон скрыт", "Телефон йәшерелгән"),
                subtitle = appTextFor(currentLanguage, "До подтверждения брони", "Бронь раҫланғанға тиклем")
            )
            TrustDivider()
            TrustRow(
                icon = Icons.Default.Shield,
                title = appTextFor(currentLanguage, "Код посадки", "Ултырыу коды"),
                subtitle = appTextFor(currentLanguage, "Для вашей безопасности", "Һеҙҙең хәүефһеҙлек өсөн")
            )
            TrustDivider()
            TrustRow(
                icon = Icons.Default.Verified,
                title = appTextFor(currentLanguage, "Проверка водителя и машины", "Водитель һәм машинаны тикшереү"),
                subtitle = appTextFor(currentLanguage, "Каждый водитель проходит проверку", "Һәр водитель тикшереү үтә")
            )
        }
    }
}

@Composable
private fun TrustDivider() {
    Box(
        Modifier
            .padding(start = 72.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(CanonBorder)
    )
}

@Composable
private fun TrustRow(icon: ImageVector, title: String, subtitle: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 66.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(48.dp),
            color = CanonMint,
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(12.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = CanonText, fontSize = 17.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = CanonMuted, fontSize = 14.sp, lineHeight = 18.sp)
        }
        Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = CanonMuted, modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun SafetyFooter(currentLanguage: AppLanguage, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Shield, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(19.dp))
            Text(
                text = appTextFor(currentLanguage, "Безопасность поездок — наш приоритет", "Сәфәр хәүефһеҙлеге — беҙҙең өҫтөнлөк"),
                color = CanonGreen2,
                fontSize = 14.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center
            )
        }
        Text(
            text = appTextFor(currentLanguage, "Юлдаш заботится о вас", "Юлдаш һеҙҙең хаҡта хәстәрләй"),
            color = CanonMuted,
            fontSize = 13.sp,
            lineHeight = 17.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScreenTopBar(title: String, onBack: () -> Unit) {
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
private fun YuldashBottomBar(
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
private fun Modifier.bounceClick(onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, tween(120), label = "bounce")
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = interaction, indication = null, onClick = onClick)
}

/** Карточка мягко всплывает снизу при появлении. index = задержка для каскада. */
@Composable
private fun Modifier.appearIn(index: Int = 0): Modifier {
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
private fun SeniorAccessCard(onSimpleMode: () -> Unit) {
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

private object LocationPrefs {
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
                added += map.mapObjects.addPolyline(Polyline(listOf(fromPt, toPt))).apply {
                    setStrokeColor(0xCC0B6B3A.toInt())
                    strokeWidth = 4f
                }
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
private fun cityDistanceText(from: String, to: String): String? {
    val a = cityPoint(from) ?: return null
    val b = cityPoint(to) ?: return null
    val sLat = Math.sin(Math.toRadians(b.latitude - a.latitude) / 2)
    val sLon = Math.sin(Math.toRadians(b.longitude - a.longitude) / 2)
    val h = sLat * sLat + Math.cos(Math.toRadians(a.latitude)) * Math.cos(Math.toRadians(b.latitude)) * sLon * sLon
    return "${Math.round(2 * 6371.0 * Math.asin(Math.sqrt(h)))} км"
}

@Composable
private fun MapPreview(modifier: Modifier = Modifier, from: String = "Баймаҡ", to: String = "Сибай", distance: String? = "43 км") {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RidesScreen(
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
private fun SegmentedTabs(
    tabs: List<String>,
    selected: String,
    onSelect: (String) -> Unit
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(tabs) { tab ->
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
private fun MyTripCard(
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
private fun NearbyRideCard(dto: com.yuldash.app.data.RideDto, soonest: Boolean, onOpen: () -> Unit) {
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
private fun NearbySkeletonCard() {
    Card(
        modifier = Modifier.width(286.dp).height(152.dp),
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = CanonItemShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.fillMaxWidth(0.7f).height(16.dp).background(CanonMint, RoundedCornerShape(8.dp)))
            Box(Modifier.fillMaxWidth(0.4f).height(13.dp).background(CanonMint, RoundedCornerShape(7.dp)))
            Box(Modifier.fillMaxWidth(0.55f).height(13.dp).background(CanonMint, RoundedCornerShape(7.dp)))
            Spacer(Modifier.weight(1f))
            Box(Modifier.fillMaxWidth(0.5f).height(34.dp).background(CanonMint, RoundedCornerShape(12.dp)))
        }
    }
}

@Composable
private fun NearbyEmptyCard(hasRoute: Boolean, onRetry: () -> Unit) {
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
            Icon(Icons.Default.DirectionsCar, contentDescription = null, tint = CanonMuted, modifier = Modifier.size(34.dp))
            Text(
                if (hasRoute) appText("На этом маршруте пока нет машин", "Был маршрутта әлегә машина юҡ")
                else appText("Поездок рядом пока нет", "Яҡында сәфәрҙәр әлегә юҡ"),
                fontWeight = FontWeight.Bold, fontSize = 15.sp
            )
            Text(appText("Появятся — покажем здесь", "Барлыҡҡа килһә — бында күрһәтәбеҙ"), color = CanonMuted, fontSize = 13.sp)
            TextButton(onClick = onRetry) {
                Text(appText("Обновить", "Яңыртыу"), color = CanonGreen2, fontWeight = FontWeight.Black)
            }
        }
    }
}

@Composable
private fun RideCard(
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
private fun PrefToggleRow(icon: ImageVector, label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
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
private fun NearbyFilterChip(icon: ImageVector, label: String, active: Boolean, onToggle: () -> Unit) {
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
private fun MyRequestsScreen(requests: List<LocalRequest>, onCreateNew: () -> Unit, onViewResponses: () -> Unit) {
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
                items(requests) { req ->
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
private fun ChatScreen(
    voiceMessages: List<LocalVoiceMessage>,
    onAddVoiceMessage: (LocalVoiceMessage) -> Unit,
    onNotifications: () -> Unit,
    onOpenChat: (Int, String, String) -> Unit
) {
    var selected by remember { mutableStateOf("active") }
    var voiceSent by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var latestBookingId by remember { mutableStateOf<Int?>(null) }
    var conversations by remember { mutableStateOf<List<ConversationDto>>(emptyList()) }
    var myRequests by remember { mutableStateOf<List<RequestDto>>(emptyList()) }
    val chatTabs = listOf(
        "active" to LocalizedText("Активные", "Актив"),
        "requests" to LocalizedText("Заявки", "Заявкалар"),
        "system" to LocalizedText("Система", "Система")
    )
    val nowText = appText("сейчас", "хәҙер")
    LaunchedEffect(Unit) {
        ApiClient.getMyBookings().onSuccess { latestBookingId = it.maxOrNull() }
        ApiClient.getConversations().onSuccess { conversations = it }
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
                            onAddVoiceMessage(LocalVoiceMessage("Байрас", text, nowText))
                            draft = ""
                        }
                    },
                    onVoiceRecorded = { path, dur ->
                        onAddVoiceMessage(LocalVoiceMessage("Байрас", "", nowText, audioPath = path, durationSec = dur))
                        voiceSent = true
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
                itemsIndexed(myRequests) { i, r ->
                    Box(Modifier.appearIn(i)) {
                        ChatCard(
                            initial = r.fromCity.firstOrNull()?.uppercase() ?: "З",
                            name = "${r.fromCity} → ${r.toCity}",
                            subtitle = appText("Заявка · ${r.seats} мест", "Заявка · ${r.seats} урын"),
                            message = r.comment.ifBlank { appText("Ждём отклика водителя", "Водитель яуабын көтәбеҙ") },
                            time = "",
                            unread = 0,
                            verified = false
                        )
                    }
                }
            }
        } else {
            // Вкладка «Активные» — чаты по поездкам + записанные голосовые.
            items(voiceMessages) { message ->
                VoiceMessageCard(message)
            }
            if (conversations.isNotEmpty()) {
                itemsIndexed(conversations) { i, c ->
                    Box(Modifier.appearIn(i)) {
                        ChatCard(
                            initial = c.peerName.take(1).uppercase(),
                            name = c.peerName,
                            subtitle = c.route,
                            message = c.lastMessage,
                            time = "",
                            unread = 0,
                            verified = true,
                            onClick = { onOpenChat(c.bookingId, c.peerName, c.route) }
                        )
                    }
                }
            } else {
                // Демо-диалоги, пока нет реальных переписок (новый юзер / офлайн).
                item { Box(Modifier.appearIn(0)) { ChatCard(initial = "Р", name = "Рамиль", subtitle = "Баймаҡ → Сибай", message = appText("Буду у вокзала в 17:20", "17:20-лә вокзалда булам"), time = "16:48", unread = 2, verified = true) } }
                item { Box(Modifier.appearIn(1)) { ChatCard(initial = "Л", name = "Лилия", subtitle = appText("Заявка в больницу", "Больницаға заявка"), message = appText("Могу забрать после 18:00", "18:00-дән һуң алып китә алам"), time = "15:30", unread = 0, verified = false) } }
                item { Box(Modifier.appearIn(2)) { ChatCard(initial = "Ю", name = appText("Поддержка Юлдаш", "Юлдаш ярҙамы"), subtitle = appText("Система", "Система"), message = appText("Ваш профиль подтверждён", "Профилегеҙ раҫланды"), time = appText("Вчера", "Кисә"), unread = 0, verified = true, support = true) } }
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

@Composable
private fun ChatCard(initial: String, name: String, subtitle: String, message: String, time: String, unread: Int, verified: Boolean, support: Boolean = false, onClick: (() -> Unit)? = null) {
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
                Text(initial, fontSize = 20.sp, fontWeight = FontWeight.Black, color = if (support) Color.White else MaterialTheme.colorScheme.primary)
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
private fun ChatComposer(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onVoiceRecorded: (String, Int) -> Unit
) {
    val context = LocalContext.current
    val recorder = remember { VoiceRecorder(context) }
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
    Card(
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = RoundedCornerShape(22.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (recording) {
                Spacer(Modifier.width(6.dp))
                Box(Modifier.size(12.dp).background(CanonRed, CircleShape))
                Spacer(Modifier.width(10.dp))
                Text(appText("Идёт запись… нажмите, чтобы отправить", "Яҙыла… ебәреү өсөн баҫығыҙ"), modifier = Modifier.weight(1f), color = CanonText, fontSize = 14.sp)
            } else {
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    placeholder = { Text(appText("Сообщение", "Хәбәр"), fontSize = 14.sp) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(18.dp),
                    maxLines = 3
                )
            }
            Spacer(Modifier.width(10.dp))
            IconButton(
                onClick = {
                    when {
                        recording -> finish()
                        draft.isNotBlank() -> onSend()
                        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> begin()
                        else -> permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                modifier = Modifier
                    .size(52.dp)
                    .background(if (recording) CanonRed else CanonGreen2, CircleShape)
            ) {
                Icon(
                    if (recording || draft.isNotBlank()) Icons.Default.NearMe else Icons.Default.HeadsetMic,
                    contentDescription = if (recording) appText("Отправить запись", "Яҙманы ебәреү") else if (draft.isBlank()) appText("Записать голос", "Тауыш яҙҙырыу") else appText("Отправить", "Ебәреү"),
                    tint = Color.White
                )
            }
        }
    }
}

@Composable
private fun VoiceMessageCard(message: LocalVoiceMessage) {
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

@Composable
private fun SimpleModeScreen(
    latestRequests: List<LocalRequest>,
    onBack: () -> Unit,
    onVoiceRequest: () -> Unit,
    onFamilyOrder: () -> Unit,
    onTrustedContacts: () -> Unit,
    onRepeatTrip: () -> Unit,
    onCallbackHelp: () -> Unit,
    onSos: () -> Unit,
    onChat: () -> Unit
) {
    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Простой режим", "Ябай режим"), onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = CanonSurface), shape = CanonCardShape, elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(appText("Юлдаш без сложностей", "Юлдаш еңел"), color = CanonGreen, fontSize = 27.sp, lineHeight = 29.sp, fontWeight = FontWeight.Black)
                        Text(appText("Большие кнопки для родителей, бабушек и дедушек. Можно говорить голосом или попросить звонок.", "Ата-әсә, өләсәй һәм олатайҙар өсөн ҙур төймәләр. Тауыш менән әйтергә йәки шылтыратыу һорарға була."), color = CanonMuted, fontSize = 16.sp, lineHeight = 21.sp)
                    }
                }
            }
            item { Box(Modifier.appearIn(0)) { SeniorBigAction(Icons.Default.HeadsetMic, appText("Сказать маршрут", "Маршрутты әйтеү"), appText("Голосом создать заявку", "Тауыш менән заявка"), onVoiceRequest) } }
            item { Box(Modifier.appearIn(1)) { SeniorBigAction(Icons.Default.PhoneLocked, appText("Позвоните мне", "Миңә шылтыратығыҙ"), appText("Помощник сам перезвонит", "Ярдамсы үҙе шылтыратыр"), onCallbackHelp) } }
            item { Box(Modifier.appearIn(2)) { SeniorBigAction(Icons.Default.Shield, appText("SOS", "SOS"), appText("Экстренная помощь", "Ашығыс ярҙам"), onSos, danger = true) } }
            item { Box(Modifier.appearIn(3)) { SeniorBigAction(Icons.Default.Refresh, appText("Частые маршруты", "Йыш маршруттар"), appText("В больницу, к детям, на рынок", "Больницаға, балаларға, баҙарға"), onRepeatTrip) } }
            item { Text(appText("Ещё", "Тағы"), color = CanonMuted, fontWeight = FontWeight.Bold) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SimpleSmallAction(appText("За близкого", "Яҡын өсөн"), Icons.Default.Person, onFamilyOrder, Modifier.weight(1f))
                    SimpleSmallAction(appText("Контакты", "Контакттар"), Icons.Default.PhoneLocked, onTrustedContacts, Modifier.weight(1f))
                    SimpleSmallAction(appText("Чат", "Чат"), Icons.Default.ChatBubble, onChat, Modifier.weight(1f))
                }
            }
            if (latestRequests.isNotEmpty()) {
                item { Text(appText("Последние заявки", "Һуңғы заявкалар"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 18.sp) }
                items(latestRequests.take(3)) { request ->
                    LocalRequestCard(request)
                }
            }
        }
    }
}

@Composable
private fun SeniorBigAction(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit, danger: Boolean = false) {
    Card(
        modifier = Modifier.bounceClick(onClick).fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = if (danger) CanonDangerBg else CanonSurface),
        shape = CanonItemShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = CanonMint, shape = RoundedCornerShape(18.dp)) {
                Icon(icon, contentDescription = null, tint = if (danger) CanonRed else CanonGreen2, modifier = Modifier.padding(14.dp).size(32.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = CanonText, fontWeight = FontWeight.Black, fontSize = 22.sp, lineHeight = 24.sp)
                Text(subtitle, color = CanonMuted, fontSize = 15.sp, lineHeight = 19.sp)
            }
            Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = if (danger) CanonRed else CanonMuted)
        }
    }
}

@Composable
private fun SimpleSmallAction(title: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.bounceClick(onClick),
        color = CanonSurface,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, CanonBorder),
        shadowElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(24.dp))
            Text(title, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// Поле адреса с автоподсказкой через Яндекс.Геокодер.
@Composable
private fun AddressSuggestField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    leadingIcon: ImageVector
) {
    var hits by remember { mutableStateOf<List<GeoHit>>(emptyList()) }
    var picked by remember { mutableStateOf(true) }   // не подсказывать для предзаполненных значений при открытии
    LaunchedEffect(value) {
        if (picked) { picked = false; return@LaunchedEffect }
        if (value.trim().length < 2) { hits = emptyList(); return@LaunchedEffect }
        delay(350)
        hits = GeocoderClient.suggest(value)
    }
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            leadingIcon = { Icon(leadingIcon, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            singleLine = true
        )
        if (hits.isNotEmpty()) {
            Surface(
                color = CanonSurface,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, CanonBorder),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            ) {
                Column {
                    hits.forEach { hit ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { picked = true; onValueChange(hit.title); hits = emptyList() }
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.LocationOn, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(hit.title, color = CanonText, fontSize = 14.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalRequestCard(request: LocalRequest) {
    Card(colors = CardDefaults.cardColors(containerColor = CanonSurface), shape = CanonItemShape, elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(request.title, modifier = Modifier.weight(1f), color = CanonText, fontWeight = FontWeight.Black, fontSize = 17.sp)
                Surface(color = CanonMint, shape = RoundedCornerShape(999.dp)) {
                    Text(request.status, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp), color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
            Text(request.route, color = CanonGreen, fontWeight = FontWeight.Bold)
            Text("${request.time} · ${request.passenger}", color = CanonMuted)
            request.trustedContact?.let {
                Text(appText("Статус получит: $it", "Статус ала: $it"), color = CanonMuted, fontSize = 12.sp)
            }
            request.voiceUrl?.let { url -> VoiceRequestPlayRow(url) }
        }
    }
}

@Composable
private fun VoiceRequestPlayRow(url: String) {
    var playing by remember { mutableStateOf(false) }
    val player = remember { mutableStateOf<MediaPlayer?>(null) }
    DisposableEffect(url) { onDispose { runCatching { player.value?.release() }; player.value = null } }
    Surface(color = CanonMint, shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = {
                    if (playing) {
                        runCatching { player.value?.stop(); player.value?.release() }; player.value = null; playing = false
                    } else runCatching {
                        player.value = MediaPlayer().apply {
                            setDataSource(url)
                            setOnPreparedListener { it.start() }
                            setOnCompletionListener { playing = false; runCatching { release() }; player.value = null }
                            prepareAsync()
                        }
                        playing = true
                    }
                },
                modifier = Modifier.size(34.dp)
            ) {
                Icon(if (playing) Icons.Default.Close else Icons.Default.PlayArrow, contentDescription = appText("Слушать заявку", "Заявканы тыңлау"), tint = CanonGreen2)
            }
            Text(appText("Голосовая заявка", "Тауыш заявкаһы"), color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
}

@Composable
private fun VoiceRequestScreen(
    contacts: List<TrustedContact>,
    onBack: () -> Unit,
    onCreateRequest: (LocalRequest) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recorder = remember { VoiceRecorder(context) }
    var recording by remember { mutableStateOf(false) }
    var startMs by remember { mutableStateOf(0L) }
    var recordedPath by remember { mutableStateOf<String?>(null) }
    var recordedDur by remember { mutableStateOf(0) }
    var uploading by remember { mutableStateOf(false) }
    val trusted = contacts.firstOrNull()
    val voiceRequestStatus = appText("ищем водителя", "водитель эҙләйбеҙ")
    val vrTitle = appText("Голосовая заявка", "Тауыш заявкаһы")
    val vrRoute = appText("Голосом — водитель слушает", "Тауыш менән — водитель тыңлай")
    val vrNow = appText("сейчас", "хәҙер")
    val vrPrompt = appText("Скажите маршрут", "Маршрутты әйтегеҙ")
    val vrNoStt = appText("Распознавание недоступно на устройстве", "Таныу ҡорамалда юҡ")
    fun begin() { if (recorder.start()) { recording = true; startMs = SystemClock.elapsedRealtime() } }
    fun finish() {
        recordedPath = recorder.stop()
        recordedDur = ((SystemClock.elapsedRealtime() - startMs) / 1000).toInt().coerceAtLeast(1)
        recording = false
    }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) begin() }
    var recognizedText by remember { mutableStateOf<String?>(null) }
    val sttLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val t = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!t.isNullOrBlank()) recognizedText = t
        }
    }
    fun recognizeRu() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PROMPT, vrPrompt)
        }
        runCatching { sttLauncher.launch(intent) }.onFailure { Toast.makeText(context, vrNoStt, Toast.LENGTH_SHORT).show() }
    }
    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Голосовая заявка", "Тауыш заявкаһы"), onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                InfoCard(
                    title = appText("Нажмите и скажите", "Баҫығыҙ һәм әйтегеҙ"),
                    text = appText("Скажите голосом: откуда, куда и когда. Водитель послушает — на русском или башкирском.", "Тауыш менән әйтегеҙ: ҡайҙан, ҡайҙа, ҡасан. Водитель тыңлар — урыҫса йәки башҡортса."),
                    icon = Icons.Default.VolumeUp
                )
            }
            item {
                Button(
                    onClick = {
                        when {
                            recording -> finish()
                            recordedPath != null -> recordedPath = null
                            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> begin()
                            else -> perm.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(78.dp),
                    shape = RoundedCornerShape(22.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (recording) CanonRed else CanonGreen2)
                ) {
                    Icon(Icons.Default.HeadsetMic, contentDescription = null, modifier = Modifier.size(30.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (recording) appText("Стоп — готово", "Туҡта — әҙер") else if (recordedPath != null) appText("Записать заново", "Ҡабат яҙыу") else appText("Сказать заявку", "Заявканы әйтеү"),
                        fontWeight = FontWeight.Black, fontSize = 20.sp
                    )
                }
            }
            item {
                OutlinedButton(
                    onClick = { recognizeRu() },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Icon(Icons.Default.HeadsetMic, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(appText("Сказать на русском (текстом)", "Урыҫса әйтеү (текст)"), fontWeight = FontWeight.Bold)
                }
            }
            recognizedText?.let { text ->
                item { VoiceParsedCard(title = appText("Распознано", "Танылды"), lines = listOf(text)) }
                item {
                    Button(
                        onClick = {
                            fireRequestFromRoute(text)
                            onCreateRequest(LocalRequest(title = vrTitle, route = text, time = vrNow, passenger = "Байрас", status = voiceRequestStatus, trustedContact = trusted?.name))
                        },
                        modifier = Modifier.fillMaxWidth().height(58.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)
                    ) { Text(appText("Создать заявку", "Заявка булдырыу"), fontWeight = FontWeight.Black, fontSize = 17.sp) }
                }
            }
            recordedPath?.let { path ->
                item {
                    VoiceMessageCard(LocalVoiceMessage(appText("Вы", "Һеҙ"), "", appText("сейчас", "хәҙер"), audioPath = path, durationSec = recordedDur))
                }
                item {
                    Button(
                        onClick = {
                            uploading = true
                            scope.launch {
                                val bytes = runCatching { File(path).readBytes() }.getOrNull()
                                val url = if (bytes != null) ApiClient.uploadVoice(bytes).getOrNull() else null
                                fireRequestFromRoute(vrRoute)
                                onCreateRequest(
                                    LocalRequest(
                                        title = vrTitle,
                                        route = vrRoute,
                                        time = vrNow,
                                        passenger = "Байрас",
                                        status = voiceRequestStatus,
                                        trustedContact = trusted?.name,
                                        voiceUrl = url ?: path
                                    )
                                )
                            }
                        },
                        enabled = !uploading,
                        modifier = Modifier.fillMaxWidth().height(58.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)
                    ) { Text(if (uploading) appText("Отправка…", "Ебәрелә…") else appText("Создать голосовую заявку", "Тауыш заявкаһын булдырыу"), fontWeight = FontWeight.Black, fontSize = 17.sp) }
                }
            }
        }
    }
}

@Composable
private fun CreatePassengerRequestScreen(
    onBack: () -> Unit,
    onCreateRequest: (LocalRequest) -> Unit
) {
    var from by remember { mutableStateOf("Баймаҡ") }
    var to by remember { mutableStateOf("Сибай") }
    val defaultRequestTime = appText("сегодня после 17:00", "бөгөн 17:00-тан һуң")
    var time by remember { mutableStateOf(defaultRequestTime) }
    var seats by remember { mutableStateOf("1") }
    var category by remember { mutableStateOf("regular") }
    var price by remember { mutableStateOf("350") }
    var comment by remember { mutableStateOf("") }
    val categories = listOf(
        "regular" to LocalizedText("Обычная", "Ғәҙәти"),
        "hospital" to LocalizedText("В больницу", "Больницаға"),
        "parcel" to LocalizedText("Посылка", "Посылка"),
        "kids" to LocalizedText("С детьми", "Балалар менән")
    )
    val selectedCategoryText = categories.firstOrNull { it.first == category }?.second?.text() ?: categories.first().second.text()
    val waitingStatus = appText("ждём отклики", "яуаптар көтәбеҙ")

    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Создать заявку", "Заявка булдырыу"), onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                Text(
                    appText("Заявка пассажира", "Пассажир заявкаһы"),
                    color = CanonGreen,
                    fontSize = 28.sp,
                    lineHeight = 30.sp,
                    fontWeight = FontWeight.Black
                )
            }
            item {
                InfoCard(
                    title = appText("Водители увидят условия", "Водителдәр шарттарҙы күрә"),
                    text = appText("Телефон и точная геолокация откроются только после подтверждения поездки.", "Телефон һәм теүәл геолокация сәфәр раҫланғандан һуң ғына асыла."),
                    icon = Icons.Default.Lock
                )
            }
            item { AddressSuggestField(from, { from = it }, appText("Откуда", "Ҡайҙан"), Icons.Default.LocationOn) }
            item { AddressSuggestField(to, { to = it }, appText("Куда", "Ҡайҙа"), Icons.Default.NearMe) }
            item {
                val ctxDt = LocalContext.current
                Box {
                    OutlinedTextField(
                        value = time,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(appText("Дата и время", "Дата һәм ваҡыт")) },
                        trailingIcon = { Icon(Icons.Default.CalendarMonth, contentDescription = appText("Выбрать дату", "Дата һайлау"), tint = CanonGreen2) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    )
                    Box(Modifier.matchParentSize().clickable { openDateTimePicker(ctxDt) { time = it } })
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = seats,
                        onValueChange = { seats = it.filter(Char::isDigit).take(2) },
                        label = { Text(appText("Мест", "Урын")) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp)
                    )
                    OutlinedTextField(
                        value = price,
                        onValueChange = { price = it.filter(Char::isDigit).take(5) },
                        label = { Text(appText("Цена, ₽", "Хаҡ, ₽")) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp)
                    )
                }
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(categories) { (key, label) ->
                        val labelText = label.text()
                        if (category == key) {
                            Button(
                                onClick = { category = key },
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)
                            ) { Text(labelText, fontWeight = FontWeight.Bold) }
                        } else {
                            OutlinedButton(
                                onClick = { category = key },
                                shape = RoundedCornerShape(16.dp)
                            ) { Text(labelText, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    label = { Text(appText("Комментарий", "Комментарий")) },
                    placeholder = { Text(appText("Например: буду с ребёнком", "Мәҫәлән: бала менән булам")) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                    shape = RoundedCornerShape(16.dp)
                )
            }
            item {
                VoiceParsedCard(
                    title = appText("Проверка заявки", "Заявканы тикшереү"),
                    lines = listOf(
                        "$from → $to",
                        "$time · $seats ${appText("место", "урын")} · $selectedCategoryText",
                        appText("Готовая сумма: $price ₽", "Әҙер сумма: $price ₽")
                    )
                )
            }
            item {
                Button(
                    onClick = {
                        val (apiCat, withKids) = when (category) {
                            "hospital" -> "hospital" to false
                            "parcel" -> "parcel" to false
                            "kids" -> "regular" to true
                            else -> "regular" to false
                        }
                        val priceVal = price.toIntOrNull() ?: 0
                        ApiClient.fireCreateRequest(
                            from.trim(), to.trim(),
                            seats.toIntOrNull() ?: 1,
                            apiCat, withKids, comment.trim(), priceVal,
                        )
                        onCreateRequest(
                            LocalRequest(
                                title = selectedCategoryText,
                                route = "$from → $to",
                                time = time,
                                passenger = "Байрас",
                                status = waitingStatus,
                                price = priceVal,
                                trustedContact = comment.ifBlank { null }
                            )
                        )
                    },
                    enabled = from.isNotBlank() && to.isNotBlank() && time.isNotBlank() && price.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(58.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)
                ) { Text(appText("Создать заявку", "Заявка булдырыу"), fontWeight = FontWeight.Black, fontSize = 17.sp) }
            }
        }
    }
}

@Composable
private fun FamilyOrderScreen(
    contacts: List<TrustedContact>,
    onBack: () -> Unit,
    onCreateRequest: (LocalRequest) -> Unit
) {
    val defaultPassenger = appText("Мама", "Әсәй")
    val familyRequestTitle = appText("Заказ за близкого", "Яҡын кеше өсөн заказ")
    val familyRequestTime = appText("сегодня после 17:00", "бөгөн 17:00-тан һуң")
    val familyRequestStatus = appText("ждём отклики", "яуаптар көтәбеҙ")
    var passenger by remember { mutableStateOf(defaultPassenger) }
    var phone by remember { mutableStateOf("+7 927 222-33-44") }
    var notifyContact by remember { mutableStateOf(true) }
    val trusted = contacts.firstOrNull()
    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Заказать за близкого", "Яҡын өсөн заказ"), onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item { Text(appText("Кто поедет?", "Кем бара?"), color = CanonGreen, fontSize = 28.sp, fontWeight = FontWeight.Black) }
            item { OutlinedTextField(value = passenger, onValueChange = { passenger = it }, label = { Text(appText("Имя пассажира", "Пассажир исеме")) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) }
            item { OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text(appText("Телефон пассажира", "Пассажир телефоны")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) }
            item {
                SettingSwitchRow(
                    Icons.Default.Notifications,
                    appText("Уведомлять доверенного", "Ышаныслы кешегә хәбәр итеү"),
                    appText("Статус поездки получит ${trusted?.name ?: "контакт"}", "Сәфәр статусын ${trusted?.name ?: "контакт"} ала"),
                    notifyContact
                ) { notifyContact = it }
            }
            item {
                VoiceParsedCard(
                    title = appText("Маршрут для близкого", "Яҡын кеше маршруты"),
                    lines = listOf("Баймаҡ → Сибай", appText("Сегодня после 17:00", "Бөгөн 17:00-тан һуң"), appText("Телефон пассажира скрыт до подтверждения", "Пассажир телефоны раҫлағанға тиклем йәшерен"))
                )
            }
            item {
                Button(
                    onClick = {
                        fireRequestFromRoute("Баймаҡ → Сибай")
                        onCreateRequest(
                            LocalRequest(
                                title = familyRequestTitle,
                                route = "Баймаҡ → Сибай",
                                time = familyRequestTime,
                                passenger = passenger,
                                status = familyRequestStatus,
                                trustedContact = if (notifyContact) trusted?.name else null
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth().height(58.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)
                ) { Text(appText("Создать заявку", "Заявка булдырыу"), fontWeight = FontWeight.Black, fontSize = 17.sp) }
            }
        }
    }
}

@Composable
private fun TrustedContactsScreen(
    contacts: List<TrustedContact>,
    onBack: () -> Unit,
    onAddContact: (TrustedContact) -> Unit
) {
    var added by remember { mutableStateOf(false) }
    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Доверенные контакты", "Ышаныслы контакттар"), onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                InfoCard(
                    title = appText("Близкие видят статус поездки", "Яҡындар сәфәр статусын күрә"),
                    text = appText("Можно отправить маршрут, время и статус без раскрытия лишних данных.", "Маршрут, ваҡыт һәм статусты артыҡ мәғлүмәтһеҙ ебәрергә була."),
                    icon = Icons.Default.Shield
                )
            }
            itemsIndexed(contacts) { index, contact ->
                Box(Modifier.appearIn(index)) { TrustedContactCard(contact) }
            }
            item {
                Button(
                    onClick = {
                        if (!added) {
                            onAddContact(TrustedContact("Гульназ", "Сестра", "+7 927 777-88-99", true, relationBa = "Һеңле"))
                            added = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)
                ) {
                    Text(if (added) appText("Контакт добавлен", "Контакт өҫтәлде") else appText("Добавить сестру", "Һеңлене өҫтәү"), fontWeight = FontWeight.Black)
                }
            }
        }
    }
}

@Composable
private fun TrustedContactCard(contact: TrustedContact) {
    Card(colors = CardDefaults.cardColors(containerColor = CanonSurface), shape = CanonItemShape, elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = CanonMint, shape = CircleShape) {
                Text(contact.name.first().toString(), modifier = Modifier.padding(14.dp), color = CanonGreen2, fontWeight = FontWeight.Black, fontSize = 20.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(contact.name, color = CanonText, fontWeight = FontWeight.Black, fontSize = 17.sp)
                Text("${contact.relationText()} · ${contact.phone}", color = CanonMuted, fontSize = 13.sp)
            }
            Text(if (contact.notifyByDefault) appText("Статус", "Статус") else appText("Только SOS", "Тик SOS"), color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
    }
}

@Composable
private fun RepeatTripScreen(
    contacts: List<TrustedContact>,
    onBack: () -> Unit,
    onRepeat: (LocalRequest) -> Unit
) {
    val trusted = contacts.firstOrNull()
    // Частые поездки — из истории юзера (сервер); демо, пока истории нет.
    var frequent by remember { mutableStateOf(demoFrequentTrips) }
    LaunchedEffect(Unit) {
        ApiClient.getMyRoutes().onSuccess { srv ->
            if (srv.isNotEmpty()) frequent = srv.map { s ->
                FrequentTrip(title = "Частая поездка", titleBa = "Йыш сәфәр", from = s.from, to = s.to, timeHint = "", timeHintBa = "", categoryKey = "regular")
            }
        }
    }
    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Повторить поездку", "Сәфәрҙе ҡабатлау"), onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item { Text(appText("Частые поездки", "Йыш сәфәрҙәр"), color = CanonGreen, fontSize = 28.sp, fontWeight = FontWeight.Black) }
            itemsIndexed(frequent) { index, trip ->
                val repeatTitle = appText("Повтор: ${trip.title}", "Ҡабатлау: ${trip.titleBa}")
                val repeatStatus = appText("создана", "булдырылды")
                val repeatTime = trip.timeHintText()
                Box(Modifier.appearIn(index)) {
                FrequentTripCard(trip) {
                    onRepeat(
                        LocalRequest(
                            title = repeatTitle,
                            route = "${trip.from} → ${trip.to}",
                            time = repeatTime,
                            passenger = "Байрас",
                            status = repeatStatus,
                            trustedContact = trusted?.name
                        )
                    )
                }
                }
            }
        }
    }
}

@Composable
private fun FrequentTripCard(trip: FrequentTrip, onClick: () -> Unit) {
    Card(modifier = Modifier.bounceClick(onClick).fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = CanonSurface), shape = CanonItemShape, elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = CanonMint, shape = RoundedCornerShape(16.dp)) {
                Icon(if (trip.categoryKey == "hospital") Icons.Default.LocalHospital else Icons.Default.Route, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(12.dp).size(28.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(trip.titleText(), color = CanonText, fontWeight = FontWeight.Black, fontSize = 18.sp)
                Text("${trip.from} → ${trip.to}", color = CanonGreen, fontWeight = FontWeight.Bold)
                Text(trip.timeHintText(), color = CanonMuted, fontSize = 13.sp)
            }
            Icon(Icons.Default.Refresh, contentDescription = null, tint = CanonGreen2)
        }
    }
}

@Composable
private fun CallbackHelpScreen(requested: Boolean, onBack: () -> Unit, onRequest: () -> Unit) {
    val defaultReason = appText("Помогите создать заявку", "Заявка булдырырға ярҙам итегеҙ")
    var reason by remember { mutableStateOf(defaultReason) }
    val context = LocalContext.current
    val supportPhone = BuildConfig.YULDASH_SUPPORT_PHONE
    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Помощь звонком", "Шылтыратыу ярҙамы"), onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                InfoCard(
                    title = appText("Мы перезвоним", "Беҙ шылтыратырбыҙ"),
                    text = appText("Это не SOS. Помощник Юлдаш поможет создать заявку или найти поездку.", "Был SOS түгел. Юлдаш ярҙамсыһы заявка булдырырға йәки сәфәр табырға ярҙам итә."),
                    icon = Icons.Default.HeadsetMic
                )
            }
            item { OutlinedTextField(value = reason, onValueChange = { reason = it }, label = { Text(appText("Что нужно?", "Нимә кәрәк?")) }, minLines = 3, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) }
            if (requested) {
                item {
                    InfoCard(
                        title = appText("Звонок запрошен", "Шылтыратыу һоралды"),
                        text = appText("В прототипе заявка сохранена локально. В реальном приложении уйдёт оператору.", "Прототипта заявка локаль һаҡланды. Реаль ҡушымтала операторға китә."),
                        icon = Icons.Default.CheckCircle
                    )
                }
            }
            item {
                Button(
                    onClick = {
                        if (supportPhone.isNotBlank()) {
                            runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:$supportPhone"))) }
                        } else onRequest()
                    },
                    modifier = Modifier.fillMaxWidth().height(58.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)
                ) {
                    if (supportPhone.isNotBlank()) {
                        Icon(Icons.Default.HeadsetMic, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        if (supportPhone.isNotBlank()) appText("Позвонить в поддержку", "Ярҙамға шылтыратыу") else appText("Попросить звонок", "Шылтыратыу һорау"),
                        fontWeight = FontWeight.Black, fontSize = 17.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceParsedCard(title: String, lines: List<String>) {
    Card(colors = CardDefaults.cardColors(containerColor = CanonSurface), shape = CanonItemShape, elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = CanonText, fontWeight = FontWeight.Black, fontSize = 18.sp)
            lines.forEach { line ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(line, color = CanonText, fontSize = 15.sp, lineHeight = 19.sp)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateRideScreen(onBack: () -> Unit, onPublish: (Ride) -> Unit) {
    var from by remember { mutableStateOf("") }
    var to by remember { mutableStateOf("") }
    var dateTime by remember { mutableStateOf("") }
    var seats by remember { mutableStateOf("2") }
    var price by remember { mutableStateOf("300") }
    var comment by remember { mutableStateOf("") }
    var petsAllowed by remember { mutableStateOf(false) }
    var childSeat by remember { mutableStateOf(false) }
    var womenOnly by remember { mutableStateOf(false) }
    var smoking by remember { mutableStateOf(false) }
    var baggage by remember { mutableStateOf(false) }
    var airConditioner by remember { mutableStateOf(false) }
    val defaultTime = appText("Сегодня, 18:00", "Бөгөн, 18:00")
    val defaultCar = appText("Моя машина", "Минең машина")
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { ScreenTopBar(appText("Создать поездку", "Сәфәр булдырыу"), onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(appText("Маршрут для своих", "Үҙ кешеләрең өсөн маршрут"), fontSize = 24.sp, fontWeight = FontWeight.Black)
                Text(appText("Укажите путь, места и цену. Контакты откроются после подтверждения.", "Юлды, урындарҙы һәм хаҡты күрһәтегеҙ. Контакттар раҫланғандан һуң асыла."), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item { AddressSuggestField(from, { from = it }, appText("Откуда", "Ҡайҙан"), Icons.Default.LocationOn) }
            item { AddressSuggestField(to, { to = it }, appText("Куда", "Ҡайҙа"), Icons.Default.NearMe) }
            item { OutlinedTextField(value = dateTime, onValueChange = { dateTime = it }, label = { Text(appText("Дата и время", "Дата һәм ваҡыт")) }, placeholder = { Text(defaultTime) }, leadingIcon = { Icon(Icons.Default.Schedule, null) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = seats, onValueChange = { seats = it.filter(Char::isDigit) }, label = { Text(appText("Мест", "Урын")) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp))
                    OutlinedTextField(value = price, onValueChange = { price = it.filter(Char::isDigit) }, label = { Text(appText("Цена, ₽", "Хаҡ, ₽")) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp))
                }
            }
            item {
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    label = { Text(appText("Комментарий", "Комментарий")) },
                    placeholder = { Text(appText("Например: могу взять посылку, заеду через Темясово", "Мәҫәлән: посылка ала алам, Темясово аша инәм")) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                )
            }
            item {
                Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Text(appText("Условия поездки", "Сәфәр шарттары"), modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp), fontWeight = FontWeight.Black, color = CanonText, fontSize = 16.sp)
                        PrefToggleRow(Icons.Default.Woman, appText("Только женщины", "Тик ҡатын-ҡыҙ өсөн"), womenOnly) { womenOnly = it }
                        PrefToggleRow(Icons.Default.ChildCare, appText("Детское кресло / бустер", "Балалар ултырғысы / бустер"), childSeat) { childSeat = it }
                        PrefToggleRow(Icons.Default.Pets, appText("Можно с животным", "Хайуан менән"), petsAllowed) { petsAllowed = it }
                        PrefToggleRow(Icons.Default.Luggage, appText("Есть место под багаж", "Багаж урыны бар"), baggage) { baggage = it }
                        PrefToggleRow(Icons.Default.AcUnit, appText("Кондиционер", "Кондиционер"), airConditioner) { airConditioner = it }
                        PrefToggleRow(Icons.Default.SmokingRooms, appText("Можно курить", "Тартырға ярай"), smoking) { smoking = it }
                    }
                }
            }
            item {
                InfoCard(
                    title = appText("Платное поднятие", "Түләүле күтәреү"),
                    text = appText("Можно добавить после публикации. Обычные поездки остаются бесплатными.", "Баҫтырғандан һуң өҫтәп була. Ғәҙәти сәфәрҙәр бушлай ҡала."),
                    icon = Icons.Default.TrendingUp
                )
            }
            item {
                Button(
                    onClick = {
                        val fromVal = from.ifBlank { "Баймаҡ" }
                        val toVal = to.ifBlank { "Сибай" }
                        val priceVal = price.toIntOrNull() ?: 300
                        val seatsVal = seats.toIntOrNull() ?: 2
                        val departIso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                            .format(java.util.Date(System.currentTimeMillis() + 3 * 3600_000L))
                        ApiClient.firePublishRide(fromVal, toVal, departIso, seatsVal, priceVal, comment.trim(), petsAllowed, childSeat, womenOnly, smoking, baggage, airConditioner)
                        onPublish(
                            Ride(
                                id = "local-${System.currentTimeMillis()}",
                                from = fromVal,
                                to = toVal,
                                time = dateTime.ifBlank { defaultTime },
                                timeBa = dateTime.ifBlank { defaultTime },
                                driver = "Байрас",
                                car = comment.ifBlank { defaultCar },
                                carBa = comment.ifBlank { defaultCar },
                                price = priceVal,
                                seats = seatsVal,
                                rating = 5.0,
                                verified = false,
                                boosted = false,
                                petsAllowed = petsAllowed,
                                childSeat = childSeat,
                                womenOnly = womenOnly,
                                smoking = smoking,
                                baggage = baggage,
                                airConditioner = airConditioner
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(appText("Опубликовать", "Баҫтырыу"))
                }
            }
            item {
                TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                    Text(appText("Отмена", "Кире алыу"))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PrivacyScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        LocationPrefs.sharingEnabled = granted
    }
    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Конфиденциальность", "Хосусилыҡ"), onBack) }) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(Modifier.height(6.dp))
            Text(appText("Управляй тем, что видят другие", "Башҡалар нимә күрә — үҙең хәл ит"), color = CanonMuted, fontSize = 14.sp, lineHeight = 19.sp)
            Card(colors = CardDefaults.cardColors(containerColor = CanonSurface), shape = CanonCardShape, elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LocationOn, contentDescription = null, tint = CanonGreen2)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(appText("Моя геолокация", "Минең геолокация"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(appText("Показывать мою точку на карте", "Картала минең нөктәне күрһәтеү"), color = CanonMuted, fontSize = 13.sp, lineHeight = 17.sp)
                    }
                    Spacer(Modifier.width(10.dp))
                    Switch(
                        checked = LocationPrefs.sharingEnabled,
                        onCheckedChange = { on ->
                            when {
                                !on -> LocationPrefs.sharingEnabled = false
                                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED -> LocationPrefs.sharingEnabled = true
                                else -> launcher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                            }
                        }
                    )
                }
            }
            InfoCard(
                title = appText("Геолокация скрыта по умолчанию", "Геолокация башта йәшерелгән"),
                text = appText("Точка видна только когда ползунок включён. Точный адрес — лишь после подтверждения поездки.", "Нөктә ползунок ҡабул булғанда ғына күренә. Теүәл адрес — сәфәр раҫланғандан һуң ғына."),
                icon = Icons.Default.Lock
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileScreen(
    ads: List<PartnerAd>,
    adStats: Map<String, AdStats>,
    onSupport: () -> Unit,
    onVerifyDriver: () -> Unit,
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
    onHospitalTrips: () -> Unit,
    onToggleLanguage: () -> Unit,
    onAdImpression: (PartnerAd) -> Unit,
    onAdClick: (PartnerAd) -> Unit
) {
    val isBashkir = LocalAppLanguage.current == AppLanguage.Ba
    val profileAd = ads.forPlacement(AdPlacement.Profile).firstOrNull { it.city == "Баймаҡ" }
    // Свой рейтинг (как пассажира) — из реальных оценок водителей. null, пока никто не оценил.
    var myRating by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(Unit) {
        ApiClient.me().onSuccess { o -> myRating = if (o.isNull("rating")) null else o.optDouble("rating") }
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { Spacer(Modifier.height(2.dp)) }
            item { Text(appText("Профиль", "Профиль"), color = CanonGreen, fontSize = 29.sp, lineHeight = 31.sp, fontWeight = FontWeight.Black) }
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                    shape = CanonCardShape,
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .background(
                                Brush.linearGradient(listOf(CanonGreen, Color(0xFF0E6C3F))),
                                CanonCardShape
                            )
                            .padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(13.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(70.dp)
                                    .background(Color.White.copy(alpha = 0.18f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text((ApiClient.cachedName() ?: "Байрас").take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Black, fontSize = 28.sp)
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(ApiClient.cachedName() ?: "Байрас", color = Color.White, fontWeight = FontWeight.Black, fontSize = 22.sp)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(appText("Пассажир · Баймаҡ", "Пассажир · Баймаҡ"), color = Color.White.copy(alpha = 0.78f), fontSize = 13.sp)
                                    myRating?.let { r ->
                                        Spacer(Modifier.width(8.dp))
                                        Icon(Icons.Default.Star, contentDescription = null, tint = Color(0xFFFFD54A), modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(2.dp))
                                        Text(r.toString(), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                                Text(appText("Телефон скрыт до подтверждения поездки", "Телефон сәфәр раҫланғанға тиклем йәшерелгән"), color = Color.White.copy(alpha = 0.78f), fontSize = 13.sp, lineHeight = 16.sp)
                            }
                        }
                        Surface(color = CanonMint, shape = RoundedCornerShape(999.dp)) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(appText("Профиль подтверждён", "Профиль раҫланған"), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
            item {
                Text(appText("Личный кабинет", "Шәхси кабинет"), color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
            }
            item { Box(Modifier.appearIn(1)) { ProfileActionCard(appText("Кабинет пассажира", "Пассажир кабинеты"), appText("Мои брони, заявки и безопасность", "Брондәр, заявкалар һәм хәүефһеҙлек"), Icons.Default.EventSeat, onPassengerCabinet) } }
            item { Box(Modifier.appearIn(2)) { ProfileActionCard(appText("Кабинет водителя", "Водитель кабинеты"), appText("Маршруты, проверка и поднятие", "Маршруттар, тикшереү һәм күтәреү"), Icons.Default.DirectionsCar, onDriverCabinet) } }
            item { Box(Modifier.appearIn(3)) { ProfileActionCard(appText("Язык", "Тел"), if (isBashkir) "Башҡортса / Русский" else "Русский / Башҡортса", Icons.Default.Language, onToggleLanguage) } }
            item { Box(Modifier.appearIn(4)) { ProfileActionCard(appText("Проверка водителя", "Водителде тикшереү"), appText("Права, машина, фото авто", "Права, машина, авто фотоһы"), Icons.Default.Verified, onVerifyDriver) } }
            item { Box(Modifier.appearIn(5)) { ProfileActionCard(appText("Поездки в больницу", "Больницаға сәфәрҙәр"), appText("Быстрый фильтр для важных поездок", "Мөһим сәфәрҙәр өсөн тиҙ фильтр"), Icons.Default.LocalHospital, onHospitalTrips) } }
            item { Box(Modifier.appearIn(6)) { ProfileActionCard(appText("Безопасность", "Хәүефһеҙлек"), appText("SOS, скрытый телефон, подтверждённые участники", "SOS, йәшерен телефон, раҫланған ҡатнашыусылар"), Icons.Default.Shield, onSafety) } }
            item { Box(Modifier.appearIn(7)) { ProfileActionCard(appText("Поддержать Юлдаш", "Юлдашҡа ярҙам итеү"), appText("Серверы, карты, SMS и поддержка", "Серверҙар, карталар, SMS һәм ярҙам"), Icons.Default.VolunteerActivism, onSupport) } }
            item {
                Text(appText("Для родителей и близких", "Ата-әсә һәм яҡындар өсөн"), color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
            }
            item { Box(Modifier.appearIn(8)) { SeniorAccessCard(onSimpleMode = onSimpleMode) } }
            item { Box(Modifier.appearIn(9)) { ProfileActionCard(appText("Доверенные контакты", "Ышаныслы контакттар"), appText("Кому отправлять статус поездки", "Сәфәр статусын кемгә ебәрергә"), Icons.Default.Person, onTrustedContacts) } }
            item { Box(Modifier.appearIn(10)) { ProfileActionCard(appText("Попросить звонок", "Шылтыратыу һорау"), appText("Помощь без чата и сложных форм", "Чатһыҙ һәм ҡатмарлы формаларһыҙ ярҙам"), Icons.Default.HeadsetMic, onCallbackHelp) } }
            item {
                Text(appText("Настройки и помощь", "Көйләүҙәр һәм ярҙам"), color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
            }
            item { Box(Modifier.appearIn(11)) { ProfileActionCard(appText("Настройки", "Көйләүҙәр"), appText("Уведомления, карта, предпочтения", "Хәбәрҙәр, карта, өҫтөнлөктәр"), Icons.Default.Settings, onSettings) } }
            item { Box(Modifier.appearIn(11)) { ProfileActionCard(appText("Конфиденциальность", "Хосусилыҡ"), appText("Геолокация и разрешения", "Геолокация һәм рөхсәттәр"), Icons.Default.Shield, onPrivacy) } }
            item { Box(Modifier.appearIn(12)) { ProfileActionCard(appText("Помощь", "Ярдам"), appText("Ответы на частые вопросы", "Йыш һорауҙарға яуаптар"), Icons.Default.Help, onHelp) } }
            item {
                Text(appText("Партнёры Юлдаш", "Юлдаш партнёрҙары"), color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
            }
            item { Box(Modifier.appearIn(13)) { ProfileActionCard(appText("Кабинет рекламы", "Реклама кабинеты"), appText("Объявления, erid, показы и клики", "Иғландар, erid, күрһәтеү һәм баҫыу"), Icons.Default.Payments, onAdsCabinet) } }
            profileAd?.let { ad ->
                item {
                    Box(Modifier.appearIn(12)) {
                        PartnerAdCard(
                            ad = ad,
                            stats = adStats[ad.id] ?: AdStats(),
                            compact = true,
                            label = appText("Городской партнёр", "Ҡала партнёры"),
                            onImpression = onAdImpression,
                            onClick = onAdClick
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(92.dp)) }
        }
    }
}

@Composable
private fun ProfileActionCard(
    title: String,
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: (() -> Unit)?
) {
    val clickModifier = if (onClick != null) Modifier.bounceClick(onClick) else Modifier
    Card(
        modifier = clickModifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = CanonItemShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(color = CanonMint, shape = RoundedCornerShape(16.dp)) {
                Icon(icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(9.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, color = CanonText, fontWeight = FontWeight.Black, fontSize = 16.sp, lineHeight = 19.sp)
                Text(text, color = CanonMuted, fontSize = 13.sp, lineHeight = 17.sp)
            }
            Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = CanonMuted)
        }
    }
}

@Composable
private fun PassengerCabinetScreen(
    rides: List<Ride>,
    requests: List<LocalRequest>,
    onBack: () -> Unit,
    onFindRide: () -> Unit,
    onCreateRequest: () -> Unit,
    onSafety: () -> Unit
) {
    val activeRide = rides.firstOrNull()
    Scaffold(
        containerColor = CanonBg,
        topBar = { ScreenTopBar(appText("Кабинет пассажира", "Пассажир кабинеты"), onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                Text(appText("Ваши поездки и заявки", "Һеҙҙең сәфәрҙәр һәм заявкалар"), color = CanonGreen, fontSize = 25.sp, lineHeight = 28.sp, fontWeight = FontWeight.Black)
                Text(appText("Быстрый доступ к бронированиям, заявкам и защите поездки.", "Брондәргә, заявкаларға һәм хәүефһеҙлеккә тиҙ инеү."), color = CanonMuted, fontSize = 14.sp, lineHeight = 19.sp)
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CabinetMetric(appText("Активные", "Актив"), rides.size.toString(), Modifier.weight(1f))
                    CabinetMetric(appText("Заявки", "Заявкалар"), requests.size.toString(), Modifier.weight(1f))
                    CabinetMetric(appText("Рейтинг", "Рейтинг"), "5.0", Modifier.weight(1f))
                }
            }
            activeRide?.let { ride ->
                item {
                    MyTripCard(
                        ride = ride,
                        status = appText("Ближайшая", "Яҡындағы"),
                        statusColor = CanonMint,
                        icon = Icons.Default.EventSeat,
                        primaryAction = appText("Найти похожую", "Оҡшашын табыу"),
                        secondaryAction = appText("Безопасность", "Хәүефһеҙлек"),
                        onPrimary = onFindRide,
                        onSecondary = onSafety
                    )
                }
            }
            item {
                SettingsGroup {
                    SettingsNavRow(Icons.Default.Search, appText("Найти поездку", "Сәфәр табыу"), appText("Открыть список ближайших маршрутов", "Яҡындағы маршруттарҙы асыу"), onClick = onFindRide)
                    SettingsNavRow(Icons.Default.AddRoad, appText("Создать заявку", "Заявка булдырыу"), appText("Если готовой поездки нет", "Әҙер сәфәр булмаһа"), onClick = onCreateRequest)
                    SettingsNavRow(Icons.Default.Shield, appText("Безопасность поездки", "Сәфәр хәүефһеҙлеге"), appText("SOS, скрытый номер и доверенные контакты", "SOS, йәшерен номер һәм ышаныслы контакттар"), onClick = onSafety)
                }
            }
        }
    }
}

@Composable
private fun DriverCabinetScreen(
    rides: List<Ride>,
    onBack: () -> Unit,
    onCreateRide: () -> Unit,
    onVerifyDriver: () -> Unit,
    onBoost: () -> Unit
) {
    val driverRides = rides.filter { it.driver == "Байрас" }
    val ctx = LocalContext.current
    val rateScope = rememberCoroutineScope()
    var driverBookings by remember { mutableStateOf<List<com.yuldash.app.data.DriverBookingDto>>(emptyList()) }
    LaunchedEffect(Unit) { ApiClient.getDriverBookings().onSuccess { driverBookings = it } }
    val thanksMsg = appText("Спасибо за оценку", "Баһа өсөн рәхмәт")
    val rateFailMsg = appText("Не получилось оценить", "Баһалап булманы")
    Scaffold(
        containerColor = CanonBg,
        topBar = { ScreenTopBar(appText("Кабинет водителя", "Водитель кабинеты"), onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                Text(appText("Маршруты и проверка", "Маршруттар һәм тикшереү"), color = CanonGreen, fontSize = 25.sp, lineHeight = 28.sp, fontWeight = FontWeight.Black)
                Text(appText("Публикуйте поездки, проходите проверку и поднимайте маршрут выше.", "Сәфәр баҫтырығыҙ, тикшереү үтегеҙ һәм маршрутты өҫкә күтәрегеҙ."), color = CanonMuted, fontSize = 14.sp, lineHeight = 19.sp)
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CabinetMetric(appText("Мои маршруты", "Минең маршруттар"), driverRides.size.toString(), Modifier.weight(1f))
                    CabinetMetric(appText("Свободно", "Буш"), driverRides.sumOf { it.seats }.toString(), Modifier.weight(1f))
                    CabinetMetric(appText("Рейтинг", "Рейтинг"), "5.0", Modifier.weight(1f))
                }
            }
            if (driverRides.isEmpty()) {
                item {
                    EmptyStateCard(
                        title = appText("Ваших маршрутов пока нет", "Һеҙҙең маршруттар әлегә юҡ"),
                        text = appText("Опубликуйте поездку, чтобы пассажиры могли откликнуться.", "Пассажирҙар яуап бирһен өсөн сәфәр баҫтырығыҙ."),
                        icon = Icons.Default.DirectionsCar,
                        action = appText("Опубликовать маршрут", "Маршрут баҫтырыу"),
                        onAction = onCreateRide
                    )
                }
            } else {
                items(driverRides) { ride ->
                    MyTripCard(
                        ride = ride,
                        status = appText("Опубликована", "Баҫтырылды"),
                        statusColor = CanonMint,
                        icon = Icons.Default.DirectionsCar,
                        primaryAction = appText("Поднять", "Күтәреү"),
                        secondaryAction = appText("Новый маршрут", "Яңы маршрут"),
                        onPrimary = onBoost,
                        onSecondary = onCreateRide
                    )
                }
            }
            if (driverBookings.isNotEmpty()) {
                item {
                    Text(appText("Пассажиры — оцените после поездки", "Пассажирҙар — сәфәрҙән һуң баһалағыҙ"), fontWeight = FontWeight.Black, fontSize = 16.sp)
                }
                items(driverBookings) { b ->
                    var stars by remember(b.bookingId) { mutableStateOf(0) }
                    Card(colors = CardDefaults.cardColors(containerColor = CanonSurface), shape = CanonItemShape, elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(34.dp).background(CanonMint, CircleShape), contentAlignment = Alignment.Center) {
                                    Text(b.passengerName.take(1).uppercase(), fontWeight = FontWeight.Black, color = CanonGreen2)
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(b.passengerName, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (b.route.isNotBlank()) Text(b.route, color = CanonMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                b.passengerRating?.let {
                                    Icon(Icons.Default.Star, contentDescription = null, tint = Color(0xFFE7A921), modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(3.dp))
                                    Text(it.toString(), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                (1..5).forEach { n ->
                                    Icon(
                                        Icons.Default.Star,
                                        contentDescription = "$n",
                                        tint = if (n <= stars) Color(0xFFE7A921) else CanonBorder,
                                        modifier = Modifier.size(34.dp).clickable {
                                            stars = n
                                            rateScope.launch {
                                                ApiClient.rateBooking(b.bookingId, n)
                                                    .onSuccess { Toast.makeText(ctx, thanksMsg, Toast.LENGTH_SHORT).show() }
                                                    .onFailure { Toast.makeText(ctx, rateFailMsg, Toast.LENGTH_SHORT).show() }
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
            item {
                SettingsGroup {
                    SettingsNavRow(Icons.Default.AddRoad, appText("Создать поездку", "Сәфәр булдырыу"), appText("Маршрут, места, цена и время", "Маршрут, урын, хаҡ һәм ваҡыт"), onClick = onCreateRide)
                    SettingsNavRow(Icons.Default.Verified, appText("Проверка водителя", "Водителде тикшереү"), appText("Права, машина, фото и госномер", "Права, машина, фото һәм номер"), onClick = onVerifyDriver)
                    SettingsNavRow(Icons.Default.TrendingUp, appText("Поднять маршрут", "Маршрутты күтәреү"), appText("Показать выше в списке поездок", "Сәфәрҙәр исемлегендә өҫтәрәк күрһәтеү"), onClick = onBoost)
                }
            }
        }
    }
}

@Composable
private fun CabinetMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, color = CanonSurface, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, CanonBorder)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, color = CanonGreen2, fontSize = 20.sp, fontWeight = FontWeight.Black, maxLines = 1)
            Text(label, color = CanonMuted, fontSize = 11.sp, lineHeight = 13.sp, textAlign = TextAlign.Center, maxLines = 2)
        }
    }
}

@Composable
private fun AdsCabinetScreen(
    ads: List<PartnerAd>,
    adStats: Map<String, AdStats>,
    onBack: () -> Unit
) {
    Scaffold(
        containerColor = CanonBg,
        topBar = { ScreenTopBar(appText("Кабинет рекламы", "Реклама кабинеты"), onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                Text(
                    appText("Партнёрские объявления Юлдаш", "Юлдаш партнёр иғландары"),
                    color = CanonGreen,
                    fontSize = 24.sp,
                    lineHeight = 27.sp,
                    fontWeight = FontWeight.Black
                )
                Text(
                    appText("Создание, сроки, erid, показы и клики собраны отдельно от профиля пользователя.", "Иғлан, ваҡыт, erid, күрһәтеү һәм баҫыу айырым кабинетта."),
                    color = CanonMuted,
                    fontSize = 14.sp,
                    lineHeight = 19.sp
                )
            }
            item { AdsAdminPreview(ads = ads, adStats = adStats) }
        }
    }
}

@Composable
private fun AdsAdminPreview(ads: List<PartnerAd>, adStats: Map<String, AdStats>) {
    val activeCount = ads.count { it.status == AdStatus.Active }
    val moderationCount = ads.count { it.status == AdStatus.Moderation }
    val totalImpressions = ads.sumOf { adStats[it.id]?.impressions ?: 0 }
    val totalClicks = ads.sumOf { adStats[it.id]?.clicks ?: 0 }
    Card(
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = CanonItemShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = BorderStroke(1.dp, Color(0x1A0B6B3A))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = CanonMint, shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Default.AdminPanelSettings, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(11.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(appText("Кабинет рекламы", "Реклама кабинеты"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 17.sp)
                    Text(appText("Создание, сроки, erid, показы и клики", "Булдырыу, ваҡыт, erid, күрһәтеү һәм баҫыу"), color = CanonMuted, fontSize = 13.sp, lineHeight = 17.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AdSummaryMetric(appText("Активно", "Актив"), activeCount.toString(), Modifier.weight(1f))
                AdSummaryMetric(appText("Модерация", "Модерация"), moderationCount.toString(), Modifier.weight(1f))
                AdSummaryMetric(appText("Клики", "Баҫыу"), totalClicks.toString(), Modifier.weight(1f))
            }
            Text(
                appText("Всего показов: $totalImpressions · общий CTR: ${if (totalImpressions == 0) 0 else (totalClicks * 100) / totalImpressions}%", "Бөтә күрһәтеү: $totalImpressions · дөйөм CTR: ${if (totalImpressions == 0) 0 else (totalClicks * 100) / totalImpressions}%"),
                color = CanonMuted,
                fontSize = 12.sp
            )
            Text(appText("Пакеты размещения", "Урынлаштырыу пакеттары"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 15.sp)
            AdPackageRow(appText("Город", "Ҡала"), appText("Показы в одном городе", "Бер ҡалала күрһәтеү"), "1 000–3 000 ₽ / мес")
            AdPackageRow(appText("Маршрут", "Маршрут"), appText("Показы на выбранном направлении", "Һайланған йүнәлештә күрһәтеү"), "2 000–5 000 ₽ / мес")
            AdPackageRow(appText("Главный партнёр", "Төп партнёр"), appText("Выше обычных партнёров маршрута", "Маршрут партнёрҙарынан юғарыраҡ"), "5 000–15 000 ₽ / мес")
            Text(
                appText("Цены — стартовая гипотеза, не рыночный факт.", "Хаҡтар — башланғыс фараз, баҙар факты түгел."),
                color = CanonMuted,
                fontSize = 11.sp
            )
            Text(appText("Запуск рекламы", "Рекламаны башлау"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 15.sp)
            AdsLaunchChecklist()
            Text(appText("Объявления", "Иғландар"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 15.sp)
            ads.take(4).forEach { ad ->
                val stats = adStats[ad.id] ?: AdStats()
                Surface(color = CanonSurface, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, CanonBorder)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(ad.titleText(), modifier = Modifier.weight(1f), color = CanonText, fontWeight = FontWeight.Black, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Surface(color = ad.status.color().copy(alpha = 0.12f), shape = RoundedCornerShape(999.dp)) {
                                Text(ad.status.label(), modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = ad.status.color(), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            }
                        }
                        Text("erid: ${ad.eridText()} · ${ad.advertiserName}", color = CanonMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            appText(
                                "Пакет: ${ad.packageText()} · бюджет: ${ad.budgetText()}",
                                "Пакет: ${ad.packageText()} · бюджет: ${ad.budgetText()}"
                            ),
                            color = CanonMuted,
                            fontSize = 11.sp,
                            lineHeight = 14.sp
                        )
                        Text(appText("Показы: ${ad.placementsLabel()}", "Күрһәтә: ${ad.placementsLabel()}"), color = CanonMuted, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(appText("Точка: ${ad.mapPoint} · ${ad.contact}", "Нөктә: ${ad.mapPoint} · ${ad.contact}"), color = CanonMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${ad.startDate} — ${ad.endDate} · impressions_count ${stats.impressions} · clicks_count ${stats.clicks}", color = CanonMuted, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun AdSummaryMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, color = CanonMint, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, color = CanonGreen2, fontWeight = FontWeight.Black, fontSize = 18.sp)
            Text(label, color = CanonMuted, fontSize = 10.sp, maxLines = 1)
        }
    }
}

@Composable
private fun AdsLaunchChecklist() {
    SettingsGroup {
        AdChecklistRow(appText("Креатив", "Креатив"), appText("Название, описание, адрес, кнопка", "Исем, аңлатма, адрес, төймә"), true)
        AdChecklistRow(appText("Таргетинг", "Таргетинг"), appText("Город, маршрут или категория", "Ҡала, маршрут йәки категория"), true)
        AdChecklistRow(appText("Маркировка", "Билдәләү"), appText("Рекламодатель и erid", "Реклама биреүсе һәм erid"), true)
        AdChecklistRow(appText("Модерация", "Модерация"), appText("Проверка перед показами", "Күрһәткәнгә тиклем тикшереү"), false)
    }
}

@Composable
private fun AdChecklistRow(title: String, subtitle: String, done: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(color = if (done) CanonMint else CanonWarnBg, shape = CircleShape) {
            Icon(if (done) Icons.Default.CheckCircle else Icons.Default.Schedule, contentDescription = null, tint = if (done) CanonGreen2 else CanonWarn, modifier = Modifier.padding(9.dp).size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = CanonText, fontWeight = FontWeight.Black, fontSize = 14.sp)
            Text(subtitle, color = CanonMuted, fontSize = 12.sp, lineHeight = 15.sp)
        }
    }
}

@Composable
private fun AdPackageRow(title: String, subtitle: String, price: String) {
    Surface(color = CanonSurface, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, CanonBorder)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Payments, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, color = CanonText, fontWeight = FontWeight.Black, fontSize = 13.sp)
                Text(subtitle, color = CanonMuted, fontSize = 11.sp, lineHeight = 14.sp)
            }
            Text(price, color = CanonGreen2, fontWeight = FontWeight.Black, fontSize = 12.sp)
        }
    }
}

@Composable
private fun InfoCard(
    title: String,
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: (() -> Unit)? = null
) {
    val clickModifier = if (onClick != null) Modifier.bounceClick(onClick) else Modifier
    Card(
        modifier = clickModifier,
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = CanonItemShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(color = CanonMint, shape = RoundedCornerShape(16.dp)) {
                Icon(icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(10.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, color = CanonText, fontWeight = FontWeight.Bold)
                Text(text, color = CanonMuted)
            }
        }
    }
}

@Composable
private fun EmptyStateCard(
    title: String,
    text: String,
    icon: ImageVector,
    action: String? = null,
    onAction: (() -> Unit)? = null
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = CanonCardShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = BorderStroke(1.dp, CanonBorder)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(color = CanonMint, shape = CircleShape) {
                Icon(icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(15.dp).size(30.dp))
            }
            Text(title, color = CanonText, fontWeight = FontWeight.Black, fontSize = 18.sp, textAlign = TextAlign.Center)
            Text(text, color = CanonMuted, fontSize = 14.sp, lineHeight = 19.sp, textAlign = TextAlign.Center)
            if (action != null && onAction != null) {
                Button(
                    onClick = onAction,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)
                ) {
                    Text(action, fontWeight = FontWeight.Black)
                }
            }
        }
    }
}

@Composable
private fun InlinePartnerAdCard(
    ad: PartnerAd,
    label: String,
    onImpression: (PartnerAd) -> Unit,
    onClick: (PartnerAd) -> Unit
) {
    LaunchedEffect(ad.id) {
        onImpression(ad)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, Color(0x12000000))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    appText("Реклама · erid: ${ad.eridText()}", "Реклама · erid: ${ad.eridText()}"),
                    modifier = Modifier.weight(1f),
                    color = CanonMuted,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Surface(color = CanonMint, shape = RoundedCornerShape(999.dp), border = BorderStroke(1.dp, CanonBorder)) {
                    Text(label, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = CanonGreen2, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = CanonMint, shape = RoundedCornerShape(14.dp)) {
                    Icon(ad.icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(8.dp).size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(ad.titleText(), color = CanonText, fontSize = 14.sp, lineHeight = 17.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(ad.descriptionText(), color = CanonMuted, fontSize = 12.sp, lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = { onClick(ad) }) {
                    Text(ad.primaryButtonText(), color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun PartnerAdCard(
    ad: PartnerAd,
    stats: AdStats,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    label: String? = null,
    showAdminDetails: Boolean = false,
    onImpression: (PartnerAd) -> Unit,
    onClick: (PartnerAd) -> Unit
) {
    LaunchedEffect(ad.id) {
        onImpression(ad)
    }
    val labelText = label ?: appText("Партнёр рядом", "Яҡындағы партнёр")

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = CanonItemShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = BorderStroke(1.dp, Color(0x1A0B6B3A))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    appText("Реклама · erid: ${ad.eridText()}", "Реклама · erid: ${ad.eridText()}"),
                    modifier = Modifier.weight(1f),
                    color = CanonMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
                Surface(color = CanonMint, shape = RoundedCornerShape(999.dp)) {
                    Text(labelText, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp), color = CanonGreen2, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                AdChip(ad.packageText(), Icons.Default.Payments, Modifier.weight(0.9f))
                AdChip(if (showAdminDetails) ad.placementsLabel() else (ad.categoryText() ?: ad.city), Icons.Default.Map, Modifier.weight(1.2f))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = CanonMint, shape = RoundedCornerShape(16.dp)) {
                    Icon(ad.icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(12.dp).size(if (compact) 22.dp else 28.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(ad.titleText(), color = CanonText, fontWeight = FontWeight.Black, fontSize = if (compact) 16.sp else 18.sp, lineHeight = 20.sp)
                    Text(ad.descriptionText(), color = CanonText, fontSize = 14.sp, lineHeight = 18.sp, maxLines = if (compact) 2 else 3, overflow = TextOverflow.Ellipsis)
                    Text(ad.addressText(), color = CanonMuted, fontSize = 13.sp, lineHeight = 16.sp)
                }
            }
            Text(
                if (showAdminDetails) {
                    appText("Рекламодатель: ${ad.advertiserName}", "Реклама биреүсе: ${ad.advertiserName}")
                } else {
                    appText("Партнёр Юлдаш · ${ad.city}", "Юлдаш партнёры · ${ad.city}")
                },
                color = CanonMuted,
                fontSize = 11.sp,
                lineHeight = 14.sp
            )
            if (showAdminDetails) {
                Text(
                    appText("Действие: ${ad.targetActionText()} · контакт: ${ad.contact}", "Ғәмәл: ${ad.targetActionText()} · бәйләнеш: ${ad.contact}"),
                    color = CanonMuted,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { onClick(ad) },
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(15.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)
                ) {
                    Text(ad.primaryButtonText(), fontWeight = FontWeight.Black, fontSize = 13.sp, maxLines = 1)
                }
                ad.secondaryButtonText()?.let { button ->
                    OutlinedButton(
                        onClick = { onClick(ad) },
                        modifier = Modifier.weight(1f).height(44.dp),
                        shape = RoundedCornerShape(15.dp),
                        border = BorderStroke(1.dp, CanonGreen2)
                    ) {
                        Text(button, color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
                    }
                }
            }
            if (showAdminDetails) {
                Text(
                    appText(
                        "Период: ${ad.startDate} — ${ad.endDate} · показы ${stats.impressions} · клики ${stats.clicks} · CTR ${stats.ctrPercent}%",
                        "Ваҡыт: ${ad.startDate} — ${ad.endDate} · күрһәтеү ${stats.impressions} · баҫыу ${stats.clicks} · CTR ${stats.ctrPercent}%"
                    ),
                    color = CanonMuted,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun AdChip(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, color = CanonSurface, shape = RoundedCornerShape(999.dp), border = BorderStroke(1.dp, CanonBorder)) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(5.dp))
            Text(text, color = CanonGreen2, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun PartnerAd.matchesRoute(from: String, to: String): Boolean {
    return routeFrom == null || routeTo == null || (routeFrom == from && routeTo == to)
}

@Composable
private fun PartnerAd.titleText(): String = appText(title, titleBa ?: title)

@Composable
private fun PartnerAd.descriptionText(): String = appText(description, descriptionBa ?: description)

@Composable
private fun PartnerAd.addressText(): String = appText(address, addressBa ?: address)

@Composable
private fun PartnerAd.categoryText(): String? = category?.let { appText(it, categoryBa ?: it) }

@Composable
private fun PartnerAd.packageText(): String = appText(packageName, packageNameBa ?: packageName)

@Composable
private fun PartnerAd.budgetText(): String = appText(budgetLabel, budgetLabelBa ?: budgetLabel)

@Composable
private fun PartnerAd.targetActionText(): String = appText(targetAction, targetActionBa ?: targetAction)

@Composable
private fun PartnerAd.primaryButtonText(): String = appText(primaryButton, primaryButtonBa ?: primaryButton)

@Composable
private fun PartnerAd.secondaryButtonText(): String? = secondaryButton?.let { appText(it, secondaryButtonBa ?: it) }

@Composable
private fun PartnerAd.eridText(): String = appText(
    erid,
    if (erid == "ожидает присвоения") "бирелеүен көтә" else erid
)

private fun List<PartnerAd>.activeAds(): List<PartnerAd> = filter { it.status == AdStatus.Active }

private fun List<PartnerAd>.forPlacement(placement: AdPlacement): List<PartnerAd> {
    return activeAds().filter { placement in it.placements }
}

private fun List<PartnerAd>.forRoute(from: String, to: String): List<PartnerAd> {
    return activeAds().filter { it.matchesRoute(from, to) }
}

private fun List<PartnerAd>.forCity(city: String): List<PartnerAd> {
    return activeAds().filter { it.city == city }
}

private fun List<PartnerAd>.forCategory(category: String): List<PartnerAd> {
    return activeAds().filter { it.category == category }
}

@Composable
private fun AdStatus.label(): String {
    return when (this) {
        AdStatus.Draft -> appText("Черновик", "Черновик")
        AdStatus.Moderation -> appText("На модерации", "Модерацияла")
        AdStatus.Active -> appText("Активна", "Актив")
        AdStatus.Paused -> appText("Пауза", "Туҡтатылған")
        AdStatus.Finished -> appText("Завершена", "Тамамланған")
    }
}

@Composable
private fun AdStatus.color(): Color {
    return when (this) {
        AdStatus.Active -> CanonGreen2
        AdStatus.Moderation -> CanonWarn
        AdStatus.Draft -> CanonMuted
        AdStatus.Paused -> Color(0xFF7C5C00)
        AdStatus.Finished -> Color(0xFF6F7570)
    }
}

@Composable
private fun AdPlacement.label(): String {
    val isBashkir = LocalAppLanguage.current == AppLanguage.Ba
    return labelForLanguage(isBashkir)
}

@Composable
private fun PartnerAd.placementsLabel(): String {
    val isBashkir = LocalAppLanguage.current == AppLanguage.Ba
    return placements.joinToString(" · ") { it.labelForLanguage(isBashkir) }
}

private fun AdPlacement.labelForLanguage(isBashkir: Boolean): String {
    return when (this) {
        AdPlacement.Nearby -> if (isBashkir) "Яҡындағы партнёр" else "Партнёр рядом"
        AdPlacement.Route -> "Маршрут"
        AdPlacement.RidesList -> if (isBashkir) "Сәфәрҙәр исемлеге" else "Список поездок"
        AdPlacement.TripDetails -> if (isBashkir) "Сәфәр тураһында" else "Детали поездки"
        AdPlacement.Profile -> "Профиль"
        AdPlacement.Help -> if (isBashkir) "Ярҙам" else "Помощь"
    }
}

@Composable
private fun DetailMeta(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = CanonMuted, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = CanonMuted, fontSize = 13.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RouteMiniIcon() {
    val routeColor = CanonGreen2  // читаем адаптивный цвет ДО DrawScope (там @Composable недоступен)
    Canvas(Modifier.size(width = 34.dp, height = 70.dp)) {
        val start = Offset(size.width * 0.55f, size.height * 0.12f)
        val end = Offset(size.width * 0.35f, size.height * 0.88f)
        val path = Path().apply {
            moveTo(start.x, start.y)
            cubicTo(size.width * 0.08f, size.height * 0.30f, size.width * 0.86f, size.height * 0.55f, end.x, end.y)
        }
        drawPath(path, color = routeColor, style = Stroke(width = 5f, cap = StrokeCap.Round))
        drawCircle(routeColor, radius = 10f, center = start)
        drawCircle(routeColor, radius = 10f, center = end)
    }
}

@Composable
private fun TripInfoRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String
) {
    Surface(
        color = CanonSurface,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, Color(0x2235A363))
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(30.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(label, color = CanonMuted, fontSize = 14.sp)
                Text(value, color = CanonText, fontWeight = FontWeight.Medium, fontSize = 18.sp)
            }
            Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = CanonMuted)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookingScreen(
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
                    Text(appText("Детали поездки", "Сәфәр тураһында"), color = CanonGreen, fontSize = 26.sp, lineHeight = 28.sp, fontWeight = FontWeight.Black)
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
                        TripInfoRow(Icons.Default.LocationOn, appText("Место встречи", "Осрашыу урыны"), appText("Автовокзал, вход 2", "Автовокзал, 2-се инеү"))
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotificationsScreen(onBack: () -> Unit, onSelectTab: (HomeTab) -> Unit) {
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
                items(visibleNotifications) { (icon, title, subtitle) ->
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
private fun SafetyScreen(onBack: () -> Unit, onSelectTab: (HomeTab) -> Unit, onSos: () -> Unit) {
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
                    SettingsNavRow(Icons.Default.Person, appText("Поделиться поездкой с близким", "Сәфәрҙе яҡын кешегә ебәреү"), appText("Отправьте данные о поездке близкому человеку.", "Сәфәр мәғлүмәтен яҡын кешегә ебәрегеҙ."))
                    SettingsNavRow(Icons.Default.Block, appText("Чёрный список", "Ҡара исемлек"), appText("Пользователи, с которыми вы не хотите совершать поездки.", "Сәфәр итмәҫкә теләгән ҡулланыусылар."))
                    SettingsNavRow(Icons.Default.Report, appText("Пожаловаться на пользователя", "Ҡулланыусыға ялыу"), appText("Сообщите о нарушении правил или безопасности.", "Ҡағиҙә йәки хәүефһеҙлек боҙолоуын хәбәр итегеҙ."))
                    SettingsNavRow(Icons.Default.Description, appText("Правила поездок", "Сәфәр ҡағиҙәләре"), appText("Ознакомьтесь с правилами сервиса Юлдаш.", "Юлдаш ҡағиҙәләре менән танышығыҙ."))
                }
            }
            item {
                InfoCard(appText("Мы заботимся о вашей безопасности", "Беҙ хәүефһеҙлек тураһында ҡайғыртабыҙ"), appText("Проверяем участников, скрываем телефон и даём быстрый SOS.", "Ҡатнашыусыларҙы тикшерәбеҙ, телефонды йәшерәбеҙ һәм тиҙ SOS бирәбеҙ."), Icons.Default.Shield)
            }
        }
    }
}

@Composable
private fun SettingsScreen(onBack: () -> Unit, onSelectTab: (HomeTab) -> Unit, onToggleLanguage: () -> Unit) {
    var notifications by remember { mutableStateOf(true) }
    var sounds by remember { mutableStateOf(true) }
    val isBashkir = LocalAppLanguage.current == AppLanguage.Ba
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
                    SettingsNavRow(Icons.Default.Map, appText("Тема карты", "Карта темаһы"), appText("Светлая", "Яҡты"))
                }
            }
            item {
                SettingsGroup {
                    SettingsNavRow(Icons.Default.Shield, appText("Приватность", "Махсуслыҡ"), appText("Управление безопасностью и данными", "Хәүефһеҙлек һәм мәғлүмәт"))
                    SettingSwitchRow(Icons.Default.VolumeUp, appText("Звуки", "Тауыштар"), appText("Звуковые уведомления и эффекты", "Тауышлы хәбәрҙәр"), sounds) { sounds = it }
                }
            }
            item {
                SettingsGroup {
                    SettingsNavRow(Icons.Default.Tune, appText("Фильтры по умолчанию", "Ғәҙәти фильтрҙар"), appText("Настройте фильтры для поиска поездок", "Сәфәр эҙләү фильтрҙары"))
                    SettingsNavRow(Icons.Default.CreditCard, appText("Способы оплаты", "Түләү ысулдары"), appText("Управление картами и платежами", "Карталар һәм түләүҙәр"))
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

@Composable
private fun HelpScreen(
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

@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = CanonSurface), shape = CanonItemShape, elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(Modifier.padding(vertical = 8.dp), content = content)
    }
}

@Composable
private fun SettingsNavRow(
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
private fun SettingSwitchRow(
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
private fun CompactProfileBanner() {
    Card(colors = CardDefaults.cardColors(containerColor = Color.Transparent), shape = CanonItemShape, elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Row(
            modifier = Modifier
                .background(Brush.linearGradient(listOf(CanonGreen, Color(0xFF0E6C3F))), CanonItemShape)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(62.dp).background(Color.White.copy(alpha = 0.18f), CircleShape), contentAlignment = Alignment.Center) {
                Text((ApiClient.cachedName() ?: "Байрас").take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Black, fontSize = 24.sp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(ApiClient.cachedName() ?: "Байрас", color = Color.White, fontWeight = FontWeight.Black, fontSize = 20.sp)
                Text(appText("Пассажир · Баймаҡ", "Пассажир · Баймаҡ"), color = Color.White.copy(alpha = 0.78f), fontSize = 13.sp)
                Text(appText("Телефон скрыт до подтверждения", "Телефон раҫланғанға тиклем йәшерен"), color = Color.White.copy(alpha = 0.78f), fontSize = 13.sp)
            }
            Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = Color.White)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActiveTripScreen(
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

    LaunchedEffect(bookingId) {
        bookingId?.let { id -> ApiClient.getMessages(id).onSuccess { messages = it } }
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
            item { Text(appText("Чат по поездке", "Сәфәр буйынса чат"), fontWeight = FontWeight.Bold, modifier = Modifier.appearIn(4)) }
            item {
                val voiceSoon = appText("Голос записан", "Тауыш яҙылды")
                ChatComposer(
                    draft = draft,
                    onDraftChange = { draft = it },
                    onSend = {
                        val t = draft.trim()
                        if (t.isNotEmpty() && bookingId != null) {
                            ApiClient.fireSendMessage(bookingId, t)
                            messages = messages + MessageDto(0, t, -1)
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
            items(messages) { m -> MessageBubble(m.text, m.voiceUrl, mine = m.senderId == -1) }
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

@Composable
private fun SosScreen(onBack: () -> Unit) {
    val categories = listOf(
        "medical" to LocalizedText("Медицина", "Медицина"),
        "breakdown" to LocalizedText("Поломка авто", "Машина боҙолдо"),
        "other" to LocalizedText("Другое", "Башҡа")
    )
    var selected by remember { mutableStateOf(categories.first().first) }
    var description by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf(false) }
    val selectedLabel = categories.firstOrNull { it.first == selected }?.second?.text() ?: categories.first().second.text()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { ScreenTopBar("SOS", onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = CanonSurface),
                    shape = RoundedCornerShape(24.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = CanonDangerBg, shape = CircleShape) {
                            Icon(Icons.Default.Sos, contentDescription = null, tint = CanonRed, modifier = Modifier.padding(14.dp))
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(appText("Срочный вызов", "Ашығыс саҡырыу"), fontSize = 24.sp, fontWeight = FontWeight.Black)
                            Text(appText("Сигнал ближайшим водителям в радиусе 15 км.", "15 км радиустағы яҡын водителдәргә сигнал."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    categories.forEach { (key, label) ->
                        val labelText = label.text()
                        FilledTonalButton(
                            onClick = { selected = key; sent = false },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(18.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = if (selected == key) CanonDangerBg else CanonSurface
                            ),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                        ) {
                            Text(
                                labelText,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(appText("Что случилось?", "Нимә булды?")) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                )
            }
            if (sent) {
                item {
                    InfoCard(
                        title = appText("SOS отправлен", "SOS ебәрелде"),
                        text = appText("Категория: $selectedLabel. Статус: ожидание отклика.", "Категория: $selectedLabel. Статус: яуап көтөү."),
                        icon = Icons.Default.Sos
                    )
                }
            }
            item {
                Button(
                    onClick = {
                        sent = true
                        ApiClient.fireSos(
                            when (selected) {
                                "medical" -> "medical"
                                "breakdown" -> "breakdown"
                                else -> "other"
                            },
                            description.trim()
                        )
                    },
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CanonRed)
                ) { Text(appText("Отправить SOS", "SOS ебәреү")) }
            }
            item { TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(appText("Назад", "Кире")) } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VerifyDriverScreen(onBack: () -> Unit, onSelectTab: (HomeTab) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var make by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var carColor by remember { mutableStateOf("") }
    var plate by remember { mutableStateOf("") }
    var seats by remember { mutableStateOf("4") }
    var licenseUrl by remember { mutableStateOf<String?>(null) }
    var carPhotoUrl by remember { mutableStateOf<String?>(null) }
    var uploadingLicense by remember { mutableStateOf(false) }
    var uploadingCar by remember { mutableStateOf(false) }
    var docsStatus by remember { mutableStateOf("none") }
    var verified by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        ApiClient.getDriverStatus().onSuccess { s ->
            docsStatus = s.docsStatus
            verified = s.verified
            if (s.carMake.isNotBlank()) make = s.carMake
            if (s.carModel.isNotBlank()) model = s.carModel
            if (s.carColor.isNotBlank()) carColor = s.carColor
            if (s.carPlate.isNotBlank()) plate = s.carPlate
            if (s.seats > 0) seats = s.seats.toString()
            if (s.licenseUrl.isNotBlank()) licenseUrl = s.licenseUrl
            if (s.carPhotoUrl.isNotBlank()) carPhotoUrl = s.carPhotoUrl
        }
    }
    val pickLicense = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            uploadingLicense = true
            scope.launch {
                val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                val url = if (bytes != null) ApiClient.uploadPhoto(bytes).getOrNull() else null
                if (url != null) licenseUrl = url
                uploadingLicense = false
            }
        }
    }
    val pickCar = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            uploadingCar = true
            scope.launch {
                val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                val url = if (bytes != null) ApiClient.uploadPhoto(bytes).getOrNull() else null
                if (url != null) carPhotoUrl = url
                uploadingCar = false
            }
        }
    }
    val canSubmit = licenseUrl != null && carPhotoUrl != null && !submitting

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
                Text(appText("Проверка водителя", "Водителде тикшереү"), color = CanonGreen, fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.Black)
                Text(appText("Пройдите проверку, чтобы пассажиры вам доверяли", "Пассажирҙар ышанһын өсөн тикшереүҙе үтегеҙ"), color = CanonMuted, fontSize = 15.sp, lineHeight = 20.sp)
            }
            item {
                when {
                    verified -> StatusBanner(Icons.Default.Verified, appText("Профиль подтверждён", "Профиль раҫланды"), appText("Вам доверяют — значок «Проверен» виден пассажирам.", "Һеҙгә ышаналар — «Тикшерелгән» билдәһе күренә."), CanonMint, CanonGreen2)
                    docsStatus == "pending" -> StatusBanner(Icons.Default.Schedule, appText("На проверке", "Тикшереүҙә"), appText("Обычно занимает немного времени. Сообщим о результате.", "Ғәҙәттә әҙ ваҡыт ала. Һөҙөмтә тураһында хәбәр итәбеҙ."), CanonMint, CanonGreen2)
                    docsStatus == "rejected" -> StatusBanner(Icons.Default.Shield, appText("Отклонено", "Кире ҡағылды"), appText("Проверьте фото и отправьте снова.", "Фотоларҙы тикшереп, ҡабат ебәрегеҙ."), CanonDangerBg, CanonRed)
                    else -> StatusBanner(Icons.Default.Shield, appText("Проверка не пройдена", "Тикшереү үтелмәгән"), appText("Заполните данные авто и загрузите фото.", "Машина мәғлүмәтен тултырып, фото йөкләгеҙ."), CanonMint, CanonGreen2)
                }
            }
            item { Text(appText("Данные автомобиля", "Машина мәғлүмәте"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 17.sp) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = make, onValueChange = { make = it }, label = { Text(appText("Марка", "Марка")) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp), singleLine = true)
                    OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text(appText("Модель", "Модель")) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp), singleLine = true)
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = carColor, onValueChange = { carColor = it }, label = { Text(appText("Цвет", "Төҫ")) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp), singleLine = true)
                    OutlinedTextField(value = plate, onValueChange = { plate = it }, label = { Text(appText("Госномер", "Дәүләт номеры")) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp), singleLine = true)
                }
            }
            item {
                OutlinedTextField(value = seats, onValueChange = { seats = it.filter(Char::isDigit) }, label = { Text(appText("Количество мест", "Урындар һаны")) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), singleLine = true)
            }
            item { Text(appText("Документы (фото)", "Документтар (фото)"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 17.sp) }
            item { UploadTile(appText("Фото водительских прав", "Водитель танытмаһы фотоһы"), licenseUrl != null, uploadingLicense) { pickLicense.launch("image/*") } }
            item { UploadTile(appText("Фото автомобиля", "Машина фотоһы"), carPhotoUrl != null, uploadingCar) { pickCar.launch("image/*") } }
            item {
                Button(
                    onClick = {
                        submitting = true
                        scope.launch {
                            ApiClient.setDriverProfile(make.trim(), model.trim(), carColor.trim(), plate.trim(), seats.toIntOrNull() ?: 4)
                            val ok = ApiClient.submitDriverVerify(licenseUrl ?: "", carPhotoUrl ?: "").isSuccess
                            if (ok) docsStatus = "pending"
                            submitting = false
                        }
                    },
                    enabled = canSubmit,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)
                ) {
                    Icon(Icons.Default.Verified, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (submitting) appText("Отправка…", "Ебәрелә…") else appText("Отправить на проверку", "Тикшереүгә ебәреү"), fontWeight = FontWeight.Black, fontSize = 16.sp)
                }
            }
            item {
                Text(appText("Фото нужны только для проверки и не видны другим пользователям.", "Фотолар тик тикшереү өсөн, башҡаларға күренмәй."), color = CanonMuted, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
    }
}

@Composable
private fun StatusBanner(icon: ImageVector, title: String, sub: String, bg: Color, fg: Color) {
    Surface(color = bg, shape = CanonItemShape) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, color = CanonText, fontWeight = FontWeight.Black, fontSize = 17.sp)
                Text(sub, color = CanonMuted, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
    }
}

@Composable
private fun UploadTile(title: String, done: Boolean, loading: Boolean, onClick: () -> Unit) {
    Surface(
        color = CanonSurface,
        shape = CanonItemShape,
        border = BorderStroke(1.dp, if (done) CanonGreen2 else CanonBorder),
        modifier = Modifier.fillMaxWidth().bounceClick(onClick)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = CanonMint, shape = RoundedCornerShape(12.dp)) {
                Icon(if (done) Icons.Default.CheckCircle else Icons.Default.PhotoCamera, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(11.dp).size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(
                    if (loading) appText("Загрузка…", "Йөкләнә…") else if (done) appText("Загружено", "Йөкләнде") else appText("Нажмите, чтобы выбрать фото", "Фото һайлау өсөн баҫығыҙ"),
                    color = if (done) CanonGreen2 else CanonMuted, fontSize = 13.sp
                )
            }
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = CanonGreen2)
            } else {
                Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = CanonMuted)
            }
        }
    }
}

@Composable
private fun StepDot(done: Boolean) {
    Surface(color = if (done) CanonGreen2 else CanonSurface, shape = CircleShape, border = BorderStroke(1.dp, if (done) CanonGreen2 else CanonBorder)) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            if (done) Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun DocumentRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, status: String, loaded: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(color = CanonMint, shape = RoundedCornerShape(14.dp)) {
            Icon(icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(11.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = CanonText, fontWeight = FontWeight.Black, fontSize = 16.sp)
            Text(status, color = if (loaded) CanonGreen2 else CanonWarn, fontSize = 13.sp)
        }
        Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = CanonMuted)
    }
}

// Перевод по СБП на номер телефона (без мерчанта). Копировать номер + инструкция + «я перевёл».
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SbpTransferSheet(amountRub: Int, onPaid: () -> Unit, onDismiss: () -> Unit) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SupportScreen(onBack: () -> Unit) {
    val amounts = listOf(10, 30, 50, 100)
    var selectedAmount by remember { mutableIntStateOf(30) }
    var completed by remember { mutableStateOf(false) }
    var showSbp by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { ScreenTopBar(appText("Поддержать Юлдаш", "Юлдашҡа ярҙам итеү"), onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = CanonSurface),
                    shape = RoundedCornerShape(24.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(appText("Добровольная поддержка", "Ирекле ярҙам"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                        Text(appText("Помогает оплачивать серверы, карты, SMS и поддержку.", "Серверҙарҙы, карталарҙы, SMS һәм ярҙам хеҙмәтен түләргә ярҙам итә."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    amounts.forEach { amount ->
                        FilledTonalButton(
                            onClick = {
                                selectedAmount = amount
                                completed = false
                            },
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = if (selectedAmount == amount) MaterialTheme.colorScheme.primaryContainer else CanonSurface
                            )
                        ) {
                            Text("$amount ₽")
                        }
                    }
                }
            }
            item {
                OutlinedButton(onClick = { selectedAmount = 150; completed = false }, modifier = Modifier.fillMaxWidth()) {
                    Text(appText("Своя сумма", "Үҙеңдең сумма"))
                }
            }
            item {
                Button(onClick = { showSbp = true }, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Default.Payments, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(appText("Поддержать на $selectedAmount ₽", "$selectedAmount ₽ менән ярҙам итеү"))
                }
            }
            if (completed) {
                item {
                    InfoCard(
                        title = appText("Спасибо за поддержку", "Ярҙәмегеҙ өсөн рәхмәт"),
                        text = appText("Если перевод по СБП на $selectedAmount ₽ прошёл — спасибо! Деньги идут на серверы, карты и SMS.", "СБП аша $selectedAmount ₽ күсерелгән булһа — рәхмәт! Аҡса серверҙарға, карталарға һәм SMS-ҡа китә."),
                        icon = Icons.Default.VolunteerActivism
                    )
                }
            }
            item {
                TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                    Text(appText("Не сейчас", "Хәҙер түгел"))
                }
            }
        }
        if (showSbp) SbpTransferSheet(selectedAmount, onPaid = { showSbp = false; completed = true }, onDismiss = { showSbp = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BoostScreen(onBack: () -> Unit) {
    var activatedPlan by remember { mutableStateOf<String?>(null) }
    var pendingPlan by remember { mutableStateOf<Pair<String, Int>?>(null) }
    val activatedPlanText = when (activatedPlan) {
        "quick" -> appText("Быстрое поднятие", "Тиҙ күтәреү")
        "day" -> appText("День вверху", "Көн буйы өҫтә")
        "urgent" -> appText("Срочная поездка", "Ашығыс сәфәр")
        else -> null
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { ScreenTopBar(appText("Поднять объявление", "Иғланды өҫкә күтәреү"), onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { BoostPlan(appText("Быстрое поднятие", "Тиҙ күтәреү"), appText("2 часа выше в списке", "2 сәғәт исемлектә өҫтәрәк"), "20 ₽", onClick = { pendingPlan = "quick" to 20 }) }
            item { BoostPlan(appText("День вверху", "Көн буйы өҫтә"), appText("24 часа выше в списке + выделение на карте", "24 сәғәт исемлектә өҫтәрәк + картала айырыу"), "50 ₽", onClick = { pendingPlan = "day" to 50 }) }
            item { BoostPlan(appText("Срочная поездка", "Ашығыс сәфәр"), appText("6 часов выше в списке, выделение, метка срочно", "6 сәғәт исемлектә өҫтәрәк, айырыу, ашығыс билдәһе"), "70 ₽", onClick = { pendingPlan = "urgent" to 70 }) }
            if (activatedPlan != null) {
                item {
                    InfoCard(
                        title = appText("Поднятие включено", "Күтәреү ҡабыҙылды"),
                        text = appText("Тариф «$activatedPlanText» включён после перевода по СБП. Спасибо!", "«$activatedPlanText» тарифы СБП аша түләүҙән һуң ҡабыҙылды. Рәхмәт!"),
                        icon = Icons.Default.TrendingUp
                    )
                }
            }
            item {
                Text(
                    appText("Поднятие не гарантирует бронирование и влияет только на релевантные результаты.", "Күтәреү бронде гарантияламай һәм тик тура килгән һөҙөмтәләргә генә йоғонто яһай."),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        pendingPlan?.let { (key, price) ->
            SbpTransferSheet(price, onPaid = { activatedPlan = key; pendingPlan = null }, onDismiss = { pendingPlan = null })
        }
    }
}

@Composable
private fun BoostPlan(title: String, text: String, price: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.bounceClick(onClick),
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.TrendingUp, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Black)
                Text(text)
            }
            Button(onClick = onClick, colors = ButtonDefaults.buttonColors()) {
                Text(price)
            }
        }
    }
}
