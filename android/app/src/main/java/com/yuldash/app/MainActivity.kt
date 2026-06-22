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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.filled.AddBox
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
import androidx.compose.ui.res.stringResource
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
import com.yandex.mapkit.map.IconStyle
import com.yandex.mapkit.map.MapObjectTapListener
import com.yandex.mapkit.mapview.MapView
import com.yandex.runtime.image.ImageProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.MessageDto
import com.yuldash.app.ui.theme.YuldashTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            YuldashTheme {
                YuldashApp()
            }
        }
    }
}

private enum class Screen {
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
private val CanonGreen: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF7FE3AB) else Color(0xFF073F25)
private val CanonGreen2: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF2FB36E) else Color(0xFF0B6B3A)
private val CanonMint: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF143024) else Color(0xFFE7F5EC)
private val CanonYellow: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF4A3A14) else Color(0xFFFFE3A1)
private val CanonBg: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF0F1613) else Color(0xFFFAFAF6)
private val CanonText: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFFEAF2EC) else Color(0xFF0B1F14)
private val CanonMuted: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF9BA49D) else Color(0xFF686F66)
private val CanonBorder: Color @Composable get() = if (isSystemInDarkTheme()) Color(0x24FFFFFF) else Color(0x1F000000)
private val CanonRed: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFFFF6B5E) else Color(0xFFD93025)
// Поверхность карточек: была хардкод Color.White — теперь адаптивная.
private val CanonSurface: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF192420) else Color(0xFFFFFFFF)
private val CanonCardShape = RoundedCornerShape(28.dp)
private val CanonItemShape = RoundedCornerShape(22.dp)

@Composable
private fun appText(ru: String, ba: String): String {
    return if (LocalAppLanguage.current == AppLanguage.Ba) ba else ru
}

/** ISO-дата сервера "2026-06-22T22:24:07" → "22.06, 22:24" для карточки поездки. */
private fun formatDepart(iso: String): String = try {
    val d = iso.substringBefore('T')
    val t = iso.substringAfter('T')
    "${d.substring(8, 10)}.${d.substring(5, 7)}, ${t.substring(0, 5)}"
} catch (e: Exception) {
    iso
}

/** Категория из UI → (enum бэкенда, признак «с детьми»). */
private fun categoryToApi(ui: String): Pair<String, Boolean> = when (ui) {
    "В больницу" -> "hospital" to false
    "Посылка" -> "parcel" to false
    "С детьми" -> "regular" to true
    else -> "regular" to false
}

/** Категория бэкенда → подпись для карточки заявки. */
private fun apiCategoryToUi(category: String, withKids: Boolean): String = when {
    category == "hospital" -> "В больницу"
    category == "parcel" -> "Посылка"
    withKids -> "С детьми"
    else -> "Обычная"
}

/** Категория SOS из UI → enum бэкенда. */
private fun sosCategoryToApi(ui: String): String = when (ui) {
    "Медицина" -> "medical"
    "Поломка авто" -> "breakdown"
    else -> "other"
}

private data class Ride(
    val id: String,
    val from: String,
    val to: String,
    val time: String,
    val driver: String,
    val car: String,
    val price: Int,
    val seats: Int,
    val rating: Double,
    val verified: Boolean,
    val boosted: Boolean
)

private data class PopularRoute(
    val from: String,
    val to: String,
    val minutes: String,
    val distance: String,
    val nearbyCount: Int,
    val label: String
)

private data class TrustedContact(
    val name: String,
    val relation: String,
    val phone: String,
    val notifyByDefault: Boolean,
    val id: Int = 0
)

private data class FrequentTrip(
    val title: String,
    val from: String,
    val to: String,
    val timeHint: String,
    val category: String
)

private data class LocalRequest(
    val title: String,
    val route: String,
    val time: String,
    val passenger: String,
    val status: String,
    val price: Int = 0,
    val trustedContact: String? = null
)

private data class LocalVoiceMessage(
    val author: String,
    val transcript: String,
    val time: String
)

private val demoTrustedContacts = listOf(
    TrustedContact("Айгуль", "Дочь", "+7 927 111-22-33", true),
    TrustedContact("Рамиль", "Сосед", "+7 927 444-55-66", false)
)

private val demoFrequentTrips = listOf(
    FrequentTrip("В больницу", "Баймаҡ", "Сибай", "завтра утром", "В больницу"),
    FrequentTrip("К детям", "Баймаҡ", "Уфа", "пятница, 08:00", "Межгород"),
    FrequentTrip("На рынок", "Баймаҡ", "Сибай", "сегодня после 15:00", "Обычная")
)

private data class PartnerAd(
    val id: String,
    val title: String,
    val description: String,
    val address: String,
    val advertiserName: String,
    val erid: String,
    val city: String,
    val routeFrom: String? = null,
    val routeTo: String? = null,
    val category: String? = null,
    val startDate: String,
    val endDate: String,
    val status: AdStatus,
    val placements: Set<AdPlacement>,
    val packageName: String,
    val budgetLabel: String,
    val targetAction: String,
    val contact: String,
    val mapPoint: String,
    val primaryButton: String,
    val secondaryButton: String? = null,
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
        description = "Скидка 10% для поездок в больницу",
        address = "Баймаҡ, ул. Ленина, 12",
        advertiserName = "ООО «Аптека Здоровье»",
        erid = "2VtzqxXXXX",
        city = "Баймаҡ",
        routeFrom = "Баймаҡ",
        routeTo = "Сибай",
        category = "В больницу",
        startDate = "2026-06-22",
        endDate = "2026-07-22",
        status = AdStatus.Active,
        placements = setOf(AdPlacement.Nearby, AdPlacement.Profile, AdPlacement.Help, AdPlacement.TripDetails),
        packageName = "Город + категория",
        budgetLabel = "3 000 ₽ / 30 дней",
        targetAction = "Открыть карточку и построить маршрут",
        contact = "+7 927 000-12-12",
        mapPoint = "53.9306, 58.3142",
        primaryButton = "Открыть",
        secondaryButton = "Маршрут",
        icon = Icons.Default.LocalHospital
    ),
    PartnerAd(
        id = "ad-cafe-route",
        title = "Кафе «Юлдаш»",
        description = "Горячий чай и еда по дороге Баймаҡ → Сибай",
        address = "5 минут от трассы",
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
        budgetLabel = "2 500 ₽ / 14 дней",
        targetAction = "Показать предложение по маршруту",
        contact = "+7 927 000-23-23",
        mapPoint = "53.7442, 58.6638",
        primaryButton = "Посмотреть",
        icon = Icons.Default.Star
    ),
    PartnerAd(
        id = "ad-service-rides",
        title = "СТО «АвтоМастер»",
        description = "Проверка машины перед дальней поездкой",
        address = "Сибай",
        advertiserName = "ООО «АвтоМастер»",
        erid = "2VtzqzZZZZ",
        city = "Сибай",
        routeFrom = "Сибай",
        routeTo = "Баймаҡ",
        category = "Межгород",
        startDate = "2026-06-22",
        endDate = "2026-07-22",
        status = AdStatus.Active,
        placements = setOf(AdPlacement.RidesList, AdPlacement.Route),
        packageName = "Спонсор списка",
        budgetLabel = "5 000 ₽ / 30 дней",
        targetAction = "Позвонить или открыть точку на карте",
        contact = "+7 927 000-34-34",
        mapPoint = "52.7200, 58.6650",
        primaryButton = "Позвонить",
        secondaryButton = "На карте",
        icon = Icons.Default.Settings
    ),
    PartnerAd(
        id = "ad-hotel-moderation",
        title = "Гостиница «Ирендык»",
        description = "Номер на ночь для тех, кто едет через Сибай",
        address = "Сибай, центр",
        advertiserName = "ИП Каримова",
        erid = "ожидает присвоения",
        city = "Сибай",
        routeFrom = "Баймаҡ",
        routeTo = "Сибай",
        category = "Межгород",
        startDate = "2026-07-01",
        endDate = "2026-07-31",
        status = AdStatus.Moderation,
        placements = setOf(AdPlacement.Route, AdPlacement.Help),
        packageName = "Маршрут",
        budgetLabel = "4 000 ₽ / 30 дней",
        targetAction = "Открыть карточку гостиницы",
        contact = "+7 927 000-45-45",
        mapPoint = "52.7182, 58.6657",
        primaryButton = "Посмотреть",
        icon = Icons.Default.LocationOn
    )
)

private val demoRides = listOf(
    Ride(
        id = "1",
        from = "Баймаҡ",
        to = "Сибай",
        time = "Сегодня, 17:30",
        driver = "Ильдар",
        car = "Lada Vesta, белая",
        price = 350,
        seats = 2,
        rating = 4.8,
        verified = true,
        boosted = true
    ),
    Ride(
        id = "2",
        from = "Темясово",
        to = "Уфа",
        time = "Завтра, 06:00",
        driver = "Айгуль",
        car = "Hyundai Solaris, серебро",
        price = 1400,
        seats = 2,
        rating = 4.9,
        verified = true,
        boosted = false
    ),
    Ride(
        id = "3",
        from = "Сибай",
        to = "Баймак",
        time = "Пятница, 13:20",
        driver = "Рустам",
        car = "Renault Logan, синий",
        price = 300,
        seats = 1,
        rating = 4.6,
        verified = false,
        boosted = false
    )
)

private val demoPopularRoutes = listOf(
    PopularRoute(
        from = "Баймаҡ",
        to = "Сибай",
        minutes = "15 мин",
        distance = "43 км",
        nearbyCount = 3,
        label = "Популярный маршрут"
    ),
    PopularRoute(
        from = "Сибай",
        to = "Баймаҡ",
        minutes = "18 мин",
        distance = "43 км",
        nearbyCount = 2,
        label = "Возвращаются домой"
    ),
    PopularRoute(
        from = "Темясово",
        to = "Уфа",
        minutes = "3 ч 40 мин",
        distance = "310 км",
        nearbyCount = 1,
        label = "Межгород сегодня"
    )
)

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
    val voiceMessages = remember { mutableStateListOf<LocalVoiceMessage>() }
    var screen by remember {
        mutableStateOf(
            when {
                !prefs.getBoolean("onboarding_completed", false) -> Screen.Onboarding
                ApiClient.isLoggedIn() -> Screen.Home          // уже вошёл → сразу домой
                else -> Screen.Login
            }
        )
    }
    var language by remember { mutableStateOf(AppLanguage.Ru) }
    var selectedRide by remember { mutableStateOf<Ride?>(null) }
    var startHomeTab by remember { mutableStateOf(HomeTab.Map) }
    var callbackRequested by remember { mutableStateOf(false) }
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
        Toast.makeText(context, "${ad.title}: ${ad.primaryButton}", Toast.LENGTH_SHORT).show()
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
                            title = apiCategoryToUi(r.category, r.withKids),
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
        BackHandler(enabled = screen != Screen.Onboarding && screen != Screen.Login && screen != Screen.Home) {
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
                requests = localRequests,
                ads = demoPartnerAds,
                adStats = adStats,
                voiceMessages = voiceMessages,
                initialTab = startHomeTab,
                onCreateRide = { screen = Screen.CreateRide },
                onCreateRequest = { screen = Screen.CreateRequest },
                onSupport = { screen = Screen.Support },
                onBoost = { screen = Screen.Boost },
                onPublishRide = { ride ->
                    rides.add(0, ride)
                    Toast.makeText(context, "Заявка опубликована", Toast.LENGTH_SHORT).show()
                },
                onBookRide = { ride ->
                    selectedRide = ride
                    screen = Screen.Booking
                },
                onShareRide = { ride -> shareRide(context, ride) },
                onAdImpression = ::trackAdImpression,
                onAdClick = ::trackAdClick,
                onAddVoiceMessage = { message ->
                    voiceMessages.add(0, message)
                    Toast.makeText(context, "Голосовое отправлено", Toast.LENGTH_SHORT).show()
                },
                onSos = { screen = Screen.Sos },
                onVerifyDriver = { screen = Screen.VerifyDriver },
                onNotifications = { screen = Screen.Notifications },
                onSafety = { screen = Screen.Safety },
                onSettings = { screen = Screen.Settings },
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
                    Toast.makeText(context, "Поездка опубликована", Toast.LENGTH_SHORT).show()
                    openHome(HomeTab.Rides)
                }
            )
            Screen.CreateRequest -> CreatePassengerRequestScreen(
                onBack = { openHome(HomeTab.Request) },
                onCreateRequest = { request ->
                    localRequests.add(0, request)
                    Toast.makeText(context, "Заявка создана", Toast.LENGTH_SHORT).show()
                    openHome(HomeTab.Request)
                }
            )
            Screen.Support -> SupportScreen(onBack = { openHome(HomeTab.Profile) })
            Screen.Boost -> BoostScreen(onBack = { openHome(HomeTab.Rides) })
            Screen.Booking -> BookingScreen(
                ride = selectedRide ?: rides.first(),
                ads = demoPartnerAds,
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
                                .onSuccess { bid -> activeBookingId = bid; screen = Screen.ActiveTrip }
                                .onFailure { Toast.makeText(context, "Не удалось забронировать. Повтори.", Toast.LENGTH_SHORT).show() }
                        }
                    }
                }
            )
            Screen.ActiveTrip -> ActiveTripScreen(
                ride = selectedRide,
                contacts = trustedContacts,
                bookingId = activeBookingId,
                onBack = { openHome(HomeTab.Rides) },
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
            Screen.Safety -> SafetyScreen(
                onBack = { openHome(HomeTab.Profile) },
                onSelectTab = { tab -> openHome(tab) },
                onSos = { screen = Screen.Sos }
            )
            Screen.Settings -> SettingsScreen(onBack = { openHome(HomeTab.Profile) }, onSelectTab = { tab -> openHome(tab) }, onToggleLanguage = {
                language = if (language == AppLanguage.Ru) AppLanguage.Ba else AppLanguage.Ru
            })
            Screen.Help -> HelpScreen(
                ads = demoPartnerAds,
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
                ads = demoPartnerAds,
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
                    Toast.makeText(context, "Заявка создана", Toast.LENGTH_SHORT).show()
                    screen = Screen.SimpleMode
                }
            )
            Screen.FamilyOrder -> FamilyOrderScreen(
                contacts = trustedContacts,
                onBack = { screen = Screen.SimpleMode },
                onCreateRequest = { request ->
                    localRequests.add(0, request)
                    Toast.makeText(context, "Поездка за близкого создана", Toast.LENGTH_SHORT).show()
                    screen = Screen.SimpleMode
                }
            )
            Screen.TrustedContacts -> TrustedContactsScreen(
                contacts = trustedContacts,
                onBack = { screen = Screen.SimpleMode },
                onAddContact = { contact ->
                    trustedContacts.add(contact)
                    ApiClient.fireAddContact(contact.name, contact.relation, contact.phone, contact.notifyByDefault)
                    Toast.makeText(context, "Контакт добавлен", Toast.LENGTH_SHORT).show()
                }
            )
            Screen.RepeatTrip -> RepeatTripScreen(
                contacts = trustedContacts,
                onBack = { screen = Screen.SimpleMode },
                onRepeat = { request ->
                    localRequests.add(0, request)
                    Toast.makeText(context, "Частая поездка повторена", Toast.LENGTH_SHORT).show()
                    screen = Screen.SimpleMode
                }
            )
            Screen.CallbackHelp -> CallbackHelpScreen(
                requested = callbackRequested,
                onBack = { screen = Screen.SimpleMode },
                onRequest = {
                    callbackRequested = true
                    Toast.makeText(context, "Заявка на звонок создана", Toast.LENGTH_SHORT).show()
                }
            )
        }
        }
    }
}

private fun shareRide(context: android.content.Context, ride: Ride) {
    val text = "Юлдаш: ${ride.from} → ${ride.to}, ${ride.time}, водитель ${ride.driver}, ${ride.price} ₽, свободно ${ride.seats} места."
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "Поделиться поездкой"))
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
                    item { OnboardingHeroCard(slide) }
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
private fun OnboardingHeroCard(slide: OnboardingSlide) {
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
                .padding(top = 78.dp, end = 14.dp)
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
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
        colors = CardDefaults.cardColors(containerColor = if (selected) CanonMint else Color.White),
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
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(width = if (selected == index) 24.dp else 8.dp, height = 8.dp)
                    .background(if (selected == index) CanonGreen2 else Color.Black.copy(alpha = 0.18f), RoundedCornerShape(999.dp))
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
        color = Color(0xFFFAFBF8)
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                )
            }
            item {
                SafetyFooter(
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
                text = stringResource(R.string.login_title),
                color = Color(0xFF111816),
                fontSize = 24.sp,
                lineHeight = 28.sp,
                fontWeight = FontWeight.Black
            )
            Text(
                text = if (step == 0) stringResource(R.string.login_subtitle)
                else appText("Код отправлен на $phone", "Код $phone номерыңа ебәрелде"),
                color = Color(0xFF626D67),
                fontSize = 16.sp,
                lineHeight = 22.sp
            )
            if (step == 0) {
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it; error = null },
                    placeholder = { Text(stringResource(R.string.phone_number), fontSize = 16.sp) },
                    leadingIcon = {
                        Icon(Icons.Default.PhoneLocked, contentDescription = null, tint = Color(0xFFADB5C2))
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
                    placeholder = { Text(appText("Код из SMS", "SMS коды"), fontSize = 16.sp) },
                    leadingIcon = {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = Color(0xFFADB5C2))
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )
                TextButton(onClick = { step = 0; code = ""; error = null }) {
                    Text(appText("Изменить номер", "Номерҙы үҙгәртеү"), color = Color(0xFF078347))
                }
            }
            error?.let {
                Text(it, color = CanonRed, fontSize = 14.sp, lineHeight = 19.sp)
            }
            // строки ошибок считаем здесь (в @Composable-контексте); в onClick отдаём готовый текст
            val errEnterPhone = appText("Введите номер телефона", "Телефон номерын индерегеҙ")
            val errSendFail = appText("Не получилось отправить код. Повтори.", "Код ебәреп булманы. Ҡабатла.")
            val errEnterCode = appText("Введите код из SMS", "SMS кодын индерегеҙ")
            val errBadCode = appText("Неверный код", "Код дөрөҫ түгел")
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
                                    error = it.message ?: errSendFail
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
                                    error = it.message ?: errBadCode
                                }
                        }
                    }
                },
                enabled = !loading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF078347))
            ) {
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Text(
                        text = if (step == 0) appText("Получить код", "Код алыу") else appText("Войти", "Инеү"),
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
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF058356), Color(0xFF2AAD69), Color(0xFFF0C84F))
                )
            )
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Color.White.copy(alpha = 0.18f), radius = 58f, center = Offset(size.width * 0.86f, size.height * 0.53f))
            drawCircle(Color(0xFFF8D874).copy(alpha = 0.92f), radius = 21f, center = Offset(size.width * 0.86f, size.height * 0.47f))

            val cloud = Path().apply {
                moveTo(size.width * 0.18f, size.height * 0.24f)
                cubicTo(size.width * 0.29f, size.height * 0.15f, size.width * 0.43f, size.height * 0.26f, size.width * 0.57f, size.height * 0.22f)
                cubicTo(size.width * 0.68f, size.height * 0.19f, size.width * 0.75f, size.height * 0.25f, size.width * 0.82f, size.height * 0.23f)
            }
            drawPath(cloud, Color.White.copy(alpha = 0.15f), style = Stroke(width = 36f, cap = StrokeCap.Round))

            val farMountains = Path().apply {
                moveTo(0f, size.height * 0.68f)
                lineTo(size.width * 0.20f, size.height * 0.58f)
                lineTo(size.width * 0.38f, size.height * 0.64f)
                lineTo(size.width * 0.55f, size.height * 0.52f)
                lineTo(size.width * 0.70f, size.height * 0.64f)
                lineTo(size.width * 0.88f, size.height * 0.54f)
                lineTo(size.width, size.height * 0.61f)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(farMountains, Color(0xFF2B9367).copy(alpha = 0.76f))

            val nearHills = Path().apply {
                moveTo(0f, size.height * 0.78f)
                cubicTo(size.width * 0.18f, size.height * 0.67f, size.width * 0.34f, size.height * 0.76f, size.width * 0.52f, size.height * 0.68f)
                cubicTo(size.width * 0.70f, size.height * 0.59f, size.width * 0.82f, size.height * 0.76f, size.width, size.height * 0.64f)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(nearHills, Color(0xFF147A4B).copy(alpha = 0.70f))

            val treeLine = Path().apply {
                moveTo(0f, size.height * 0.72f)
                lineTo(size.width, size.height * 0.70f)
                lineTo(size.width, size.height * 0.78f)
                lineTo(0f, size.height * 0.80f)
                close()
            }
            drawPath(treeLine, Color(0xFF08613F).copy(alpha = 0.54f))

            val road = Path().apply {
                moveTo(size.width * 0.35f, size.height)
                cubicTo(size.width * 0.48f, size.height * 0.82f, size.width * 0.68f, size.height * 0.80f, size.width, size.height * 0.70f)
                lineTo(size.width, size.height * 0.83f)
                cubicTo(size.width * 0.72f, size.height * 0.87f, size.width * 0.60f, size.height * 0.93f, size.width * 0.54f, size.height)
                close()
            }
            drawPath(road, Color(0xFF5A6159).copy(alpha = 0.92f))
            val roadEdge = Path().apply {
                moveTo(size.width * 0.37f, size.height * 0.98f)
                cubicTo(size.width * 0.51f, size.height * 0.84f, size.width * 0.69f, size.height * 0.82f, size.width * 0.98f, size.height * 0.72f)
            }
            drawPath(roadEdge, Color.White.copy(alpha = 0.88f), style = Stroke(width = 6f, cap = StrokeCap.Round))
            val roadLine = Path().apply {
                moveTo(size.width * 0.58f, size.height * 0.98f)
                cubicTo(size.width * 0.66f, size.height * 0.88f, size.width * 0.76f, size.height * 0.84f, size.width * 0.95f, size.height * 0.77f)
            }
            drawPath(roadLine, Color.White.copy(alpha = 0.85f), style = Stroke(width = 5f, cap = StrokeCap.Round))
        }

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
                        text = appText("Поездки между своими", "Үҙебеҙҙекеләр араһында юллашыу"),
                        color = Color.White.copy(alpha = 0.94f),
                        fontSize = 18.sp,
                        lineHeight = 22.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HeroPill(Icons.Default.PhoneLocked, appText("Скрытый номер", "Йәшерен номер"))
                HeroPill(Icons.Default.Pin, appText("Код посадки", "Ултырыу коды"))
            }
        }

        Surface(
        modifier = Modifier
            .align(Alignment.BottomEnd)
                .padding(end = 50.dp, bottom = 70.dp)
                .size(width = 70.dp, height = 42.dp),
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFF0D6549).copy(alpha = 0.88f),
            shadowElevation = 4.dp
        ) {
            Icon(
                Icons.Default.DirectionsCar,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.padding(9.dp)
            )
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
private fun TrustCard(modifier: Modifier = Modifier) {
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
                title = appText("Телефон скрыт", "Телефон йәшерелгән"),
                subtitle = appText("До подтверждения брони", "Бронь раҫланғанға тиклем")
            )
            TrustDivider()
            TrustRow(
                icon = Icons.Default.Shield,
                title = appText("Код посадки", "Ултырыу коды"),
                subtitle = appText("Для вашей безопасности", "Һеҙҙең хәүефһеҙлек өсөн")
            )
            TrustDivider()
            TrustRow(
                icon = Icons.Default.Verified,
                title = appText("Проверка водителя и машины", "Водитель һәм машинаны тикшереү"),
                subtitle = appText("Каждый водитель проходит проверку", "Һәр водитель тикшереү үтә")
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
            .background(Color(0xFFE9ECE8))
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
            color = Color(0xFFEAF4EF),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(icon, contentDescription = null, tint = Color(0xFF078347), modifier = Modifier.padding(12.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = Color(0xFF111816), fontSize = 17.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = Color(0xFF758094), fontSize = 14.sp, lineHeight = 18.sp)
        }
        Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = Color(0xFF9BA3AF), modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun SafetyFooter(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Shield, contentDescription = null, tint = Color(0xFF078347), modifier = Modifier.size(19.dp))
            Text(
                text = appText("Безопасность поездок — наш приоритет", "Сәфәр хәүефһеҙлеге — беҙҙең өҫтөнлөк"),
                color = Color(0xFF078347),
                fontSize = 14.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center
            )
        }
        Text(
            text = appText("Юлдаш заботится о вас", "Юлдаш һеҙҙең хаҡта хәстәрләй"),
            color = Color(0xFF758094),
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
                Icon(Icons.Default.ArrowBackIosNew, contentDescription = "Назад")
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
    onShareRide: (Ride) -> Unit,
    onAdImpression: (PartnerAd) -> Unit,
    onAdClick: (PartnerAd) -> Unit,
    onAddVoiceMessage: (LocalVoiceMessage) -> Unit,
    onSos: () -> Unit,
    onVerifyDriver: () -> Unit,
    onNotifications: () -> Unit,
    onSafety: () -> Unit,
    onSettings: () -> Unit,
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
                    onNotifications = onNotifications
                )
                HomeTab.Profile -> ProfileScreen(
                    ads = ads,
                    adStats = adStats,
                    onSupport = onSupport,
                    onVerifyDriver = onVerifyDriver,
                    onSafety = onSafety,
                    onSettings = onSettings,
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
    val pillColor by animateColorAsState(if (selected) CanonYellow else Color.Transparent, tween(280), label = "navPill")
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
    Scaffold(containerColor = CanonBg) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            item { Spacer(Modifier.height(2.dp)) }
            item { Box(Modifier.appearIn(0)) { HomeHeader(onSos = onSos) } }
            item {
                Box(Modifier.appearIn(1)) {
                    MapHero(
                        rides = rides,
                        onRideTap = { selectedRide = it },
                        onFind = onOpenPopular,
                        onDriver = onDriver
                    )
                }
            }
            item { Box(Modifier.appearIn(2)) { SeniorAccessCard(onSimpleMode = onSimpleMode) } }
            item {
                Box(Modifier.appearIn(3)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(appText("Ближайшие поездки", "Яҡындағы сәфәрҙәр"), modifier = Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Black)
                    Text(appText("${rides.size} рядом", "${rides.size} яҡында"), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
                }
            }
            item {
                Box(Modifier.appearIn(4)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(rides) { ride ->
                        RideCard(
                            ride = ride,
                            compact = true,
                            onBook = { onBookRide(ride) },
                            onShare = { onShareRide(ride) },
                            onBoost = onBoost
                        )
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
            item { Spacer(Modifier.height(2.dp)) }
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
    rides: List<Ride>,
    onRideTap: (Ride) -> Unit,
    onFind: (PopularRoute) -> Unit,
    onDriver: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(430.dp)
    ) {
        if (BuildConfig.YANDEX_MAPKIT_KEY.isNotBlank()) {
            YandexMapCard(
                modifier = Modifier.matchParentSize(),
                rides = rides,
                onRideTap = onRideTap,
                showPrivacyNotice = false
            )
        } else {
            MapPreview(Modifier.matchParentSize())
        }
        Surface(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(14.dp)
                .widthIn(max = 214.dp),
            color = Color.White.copy(alpha = 0.94f),
            shape = RoundedCornerShape(999.dp),
            shadowElevation = 3.dp
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Lock, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    appText("Геолокация скрыта", "Геолокация йәшерелгән"),
                    color = CanonText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(12.dp)
        ) {
            QuickSearchCard(routes = demoPopularRoutes, onFind = onFind, onDriver = onDriver, compact = true)
        }
    }
}

@Composable
private fun HomeHeader(onSos: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(appText("Доброе утро, Байрас", "Хәйерле иртә, Байрас"), color = CanonMuted, fontSize = 14.sp)
            Text(
                appText("Куда поедем?", "Ҡайҙа барабыҙ?"),
                color = CanonGreen,
                fontSize = 28.sp,
                lineHeight = 29.sp,
                fontWeight = FontWeight.Black
            )
        }
        Surface(
            modifier = Modifier.bounceClick(onSos),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFFFFE8E4),
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
    routes: List<PopularRoute>,
    onFind: (PopularRoute) -> Unit,
    onDriver: () -> Unit,
    compact: Boolean = false
) {
    val safeRoutes = routes.ifEmpty { demoPopularRoutes.take(1) }
    val pagerState = rememberPagerState(pageCount = { safeRoutes.size })

    LaunchedEffect(safeRoutes.size) {
        if (safeRoutes.size <= 1) return@LaunchedEffect
        while (true) {
            delay(4_500)
            if (!pagerState.isScrollInProgress) {
                pagerState.animateScrollToPage((pagerState.currentPage + 1) % safeRoutes.size)
            }
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = CanonCardShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(if (compact) 12.dp else 14.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp)
        ) {
            HorizontalPager(
                state = pagerState,
                pageSpacing = 10.dp
            ) { page ->
                val route = safeRoutes[page]
                Column(verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = CanonMint, shape = RoundedCornerShape(999.dp)) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Star, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    route.label,
                                    color = CanonGreen2,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = if (compact) 11.sp else 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        Surface(color = CanonMint, shape = RoundedCornerShape(16.dp)) {
                            Text(
                                route.minutes,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                color = CanonGreen,
                                fontWeight = FontWeight.Black,
                                fontSize = if (compact) 13.sp else 14.sp,
                                maxLines = 1
                            )
                        }
                    }
                    Text(
                        "${route.from} → ${route.to}",
                        color = CanonGreen,
                        fontWeight = FontWeight.Black,
                        fontSize = if (compact) 22.sp else 24.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            appText("${route.nearbyCount} рядом", "${route.nearbyCount} яҡында"),
                            color = CanonMuted,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("· ${route.distance}", color = CanonMuted, fontSize = 12.sp)
                        Spacer(Modifier.weight(1f))
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            safeRoutes.forEachIndexed { index, _ ->
                                Box(
                                    modifier = Modifier
                                        .size(if (index == pagerState.currentPage) 7.dp else 6.dp)
                                        .background(
                                            if (index == pagerState.currentPage) CanonGreen2 else Color(0x33000000),
                                            CircleShape
                                        )
                                )
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { onFind(route) },
                            modifier = Modifier.weight(1.25f).height(if (compact) 48.dp else 52.dp),
                            shape = RoundedCornerShape(18.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2),
                            contentPadding = PaddingValues(horizontal = 10.dp)
                        ) {
                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(appText("Найти поездку", "Сәфәр табыу"), fontWeight = FontWeight.Black, fontSize = 13.sp, maxLines = 1)
                        }
                        OutlinedButton(
                            onClick = onDriver,
                            modifier = Modifier.weight(0.95f).height(if (compact) 48.dp else 52.dp),
                            shape = RoundedCornerShape(18.dp),
                            border = BorderStroke(1.dp, CanonBorder),
                            contentPadding = PaddingValues(horizontal = 10.dp)
                        ) {
                            Icon(Icons.Default.DirectionsCar, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(appText("Я водитель", "Мин водитель"), fontWeight = FontWeight.Black, color = CanonText, fontSize = 12.sp, maxLines = 1)
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
private val MapMidPoint = Point(52.71, 58.45)

// Город → точка на карте (mock-геоданные для маркеров поездок).
private fun cityPoint(city: String): Point? = when (city.trim().lowercase()) {
    "баймаҡ", "баймак" -> BaymakPoint
    "сибай" -> SibayPoint
    "темясово" -> Point(52.9686, 58.3206)
    "уфа" -> Point(54.7388, 55.9721)
    "учалы" -> Point(54.3050, 59.4040)
    "магнитогорск" -> Point(53.4072, 58.9794)
    else -> null
}

// Маркер-«ценник» (стиль Яндекс/Airbnb): белая пилюля с ценой, цветная рамка, остриё вниз.
// Boosted-поездка — золотой акцент, обычная — фирменный зелёный.
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
    val left = pad; val top = pad; val right = pad + pillW; val bottom = pad + pillH
    val radius = pillH / 2
    val cx = (left + right) / 2
    val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
    val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f; color = accent
    }
    val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22000000 }
    // остриё рисуем первым — пилюля сверху перекроет его верхнюю грань
    val tip = android.graphics.Path().apply {
        moveTo(cx - pointer / 2, bottom - 2f)
        lineTo(cx + pointer / 2, bottom - 2f)
        lineTo(cx, bottom + pointer)
        close()
    }
    c.drawRoundRect(left, top + 3f, right, bottom + 3f, radius, radius, shadow)
    c.drawPath(tip, white)
    c.drawPath(tip, border)
    c.drawRoundRect(left, top, right, bottom, radius, radius, white)
    c.drawRoundRect(left, top, right, bottom, radius, radius, border)
    val fm = textPaint.fontMetrics
    val ty = top + pillH / 2 - (fm.ascent + fm.descent) / 2
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
    rides: List<Ride> = emptyList(),
    onRideTap: (Ride) -> Unit = {},
    showPrivacyNotice: Boolean = true
) {
    val context = LocalContext.current
    // Свежие ссылки на rides/onRideTap, чтобы tap-listener не «застревал» на старых данных.
    val currentRides by rememberUpdatedState(rides)
    val currentOnTap by rememberUpdatedState(onRideTap)
    // Один tap-listener на все маркеры; держим в remember (MapKit хранит listener слабо).
    val tapListener = remember {
        MapObjectTapListener { obj, _ ->
            val ride = currentRides.firstOrNull { it.id == obj.userData as? String }
            if (ride != null) currentOnTap(ride)
            ride != null
        }
    }
    val mapView = remember {
        MapKitFactory.initialize(context)
        MapView(context).also { view ->
            val map = view.mapWindow.map
            map.move(CameraPosition(MapMidPoint, 9.0f, 0f, 0f))
            map.mapObjects.addPolyline(Polyline(listOf(BaymakPoint, SibayPoint))).apply {
                setStrokeColor(0xFF0B6B3A.toInt())
                strokeWidth = 4.5f
            }
            map.mapObjects.addCircle(Circle(BaymakPoint, 600f)).apply {
                strokeColor = 0xFFFFFFFF.toInt()
                strokeWidth = 2.5f
                fillColor = 0xFF167A4A.toInt()
            }
            map.mapObjects.addCircle(Circle(SibayPoint, 600f)).apply {
                strokeColor = 0xFFFFFFFF.toInt()
                strokeWidth = 2.5f
                fillColor = 0xFFE2A11B.toInt()
            }
            // Маркеры-ценники поездок: тап → карточка снизу.
            rides.forEach { ride ->
                val point = cityPoint(ride.from) ?: return@forEach
                map.mapObjects.addPlacemark().apply {
                    geometry = point
                    setIcon(ImageProvider.fromBitmap(ridePinBitmap("${ride.price} ₽", ride.boosted)))
                    setIconStyle(IconStyle().setAnchor(PointF(0.5f, 1f)))
                    userData = ride.id
                    addTapListener(tapListener)
                }
            }
            // Карта внутри прокручиваемого списка: на касании просим родителя (LazyColumn)
            // не перехватывать жест — иначе тап по маркеру и панорамирование «съедает» скролл.
            view.setOnTouchListener { v, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    val width = v.width.toFloat().coerceAtLeast(1f)
                    val height = v.height.toFloat().coerceAtLeast(1f)
                    val x = event.x / width
                    val y = event.y / height
                    val tappedRide = when {
                        kotlin.math.abs(x - 0.66f) < 0.13f && kotlin.math.abs(y - 0.45f) < 0.12f ->
                            currentRides.firstOrNull { it.from == "Сибай" } ?: currentRides.firstOrNull()
                        kotlin.math.abs(x - 0.36f) < 0.14f && kotlin.math.abs(y - 0.22f) < 0.14f ->
                            currentRides.firstOrNull { it.from == "Баймаҡ" } ?: currentRides.firstOrNull()
                        else -> null
                    }
                    if (tappedRide != null) {
                        currentOnTap(tappedRide)
                        return@setOnTouchListener true
                    }
                }
                false
            }
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
        MapMarkerHitTargets(rides = rides, onRideTap = onRideTap)
        MapLabel("Баймаҡ", Modifier.align(Alignment.TopStart).padding(20.dp))
        MapLabel("Сибай", Modifier.align(Alignment.CenterEnd).padding(20.dp))
        Surface(
            modifier = Modifier.align(Alignment.TopEnd).padding(18.dp),
            color = Color.White.copy(alpha = 0.92f),
            shape = RoundedCornerShape(999.dp),
            shadowElevation = 3.dp
        ) {
            Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Route, contentDescription = null, modifier = Modifier.size(16.dp), tint = CanonGreen2)
                Spacer(Modifier.width(5.dp))
                Text("43 км", fontWeight = FontWeight.Bold)
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

@Composable
private fun MapPreview(modifier: Modifier = Modifier) {
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
            drawCircle(Color(0xFF167A4A).copy(alpha = 0.08f), radius = 210f, center = Offset(size.width * 0.95f, size.height * 0.88f))
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
            drawPath(route, Color(0xFF167A4A), style = Stroke(width = 7f, cap = StrokeCap.Round))
            drawCircle(Color(0xFF167A4A), radius = 15f, center = Offset(size.width * 0.16f, size.height * 0.28f))
            drawCircle(Color(0xFFE2A11B), radius = 15f, center = Offset(size.width * 0.84f, size.height * 0.68f))
        }
        MapLabel("Баймаҡ", Modifier.align(Alignment.TopStart).padding(20.dp))
        MapLabel("Сибай", Modifier.align(Alignment.CenterEnd).padding(20.dp))
        Surface(
            modifier = Modifier.align(Alignment.TopEnd).padding(18.dp),
            color = Color.White.copy(alpha = 0.92f),
            shape = RoundedCornerShape(999.dp)
        ) {
            Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Route, contentDescription = null, modifier = Modifier.size(16.dp), tint = CanonGreen2)
                Spacer(Modifier.width(5.dp))
                Text("43 км", fontWeight = FontWeight.Bold)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RidesScreen(
    rides: List<Ride>,
    ads: List<PartnerAd>,
    adStats: Map<String, AdStats>,
    presetTo: String,
    presetToday: Boolean,
    onBookRide: (Ride) -> Unit,
    onMessage: () -> Unit,
    onShareRide: (Ride) -> Unit,
    onBoost: () -> Unit,
    onCreateRequest: () -> Unit,
    onAdImpression: (PartnerAd) -> Unit,
    onAdClick: (PartnerAd) -> Unit
) {
    var selectedStatus by remember { mutableStateOf("Активные") }
    val routeAd = ads.forPlacement(AdPlacement.Route).filter { it.matchesRoute("Баймаҡ", "Сибай") }.firstOrNull { it.id == "ad-cafe-route" }
        ?: ads.forPlacement(AdPlacement.Route).firstOrNull { it.matchesRoute("Баймаҡ", "Сибай") }
    val sponsoredAd = ads.forPlacement(AdPlacement.RidesList).firstOrNull { it.id == "ad-service-rides" }
    val inlineAd = routeAd ?: sponsoredAd
    LaunchedEffect(presetTo, presetToday) {
        if (presetTo.isNotBlank() || presetToday) selectedStatus = "Активные"
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
                    tabs = listOf("Активные", "История", "Все"),
                    selected = selectedStatus,
                    onSelect = { selectedStatus = it }
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
                        onPrimary = { onBookRide(rides.first()) },
                        onSecondary = onMessage
                    )
                    }
                }
                item {
                    Box(Modifier.appearIn(1)) {
                    MyTripCard(
                        ride = rides.getOrElse(2) { rides.first() },
                        status = appText("Ожидает", "Көтә"),
                        statusColor = Color(0xFFFFF0D1),
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
                        ride = Ride("done", "Баймаҡ", "Сибай", "12 мая, 17:40", "Рамиль", "Lada Vesta", 300, 2, 5.0, true, false),
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
                color = if (selected == tab) CanonGreen2 else Color.White,
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
                    DetailMeta(Icons.Default.Schedule, ride.time)
                    DetailMeta(Icons.Default.Person, "${ride.seats} места · ${ride.price} ₽")
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
                    fontSize = if (compact) 22.sp else 18.sp,
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
                Text(ride.time, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            Icon(Icons.Default.Verified, contentDescription = null, tint = Color(0xFF1D7A46), modifier = Modifier.size(16.dp))
                        }
                    }
                    Text(ride.car, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Default.Star, contentDescription = null, tint = Color(0xFFE7A921), modifier = Modifier.size(18.dp))
                Text(ride.rating.toString())
            }
            if (compact) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Text("${ride.seats} места · ", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    Metric(Icons.Default.EventSeat, "${ride.seats} места", Modifier.weight(1f))
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
                        Icon(Icons.Default.IosShare, contentDescription = "Поделиться")
                    }
                    IconButton(onClick = onBoost) {
                        Icon(Icons.Default.TrendingUp, contentDescription = stringResource(R.string.boost_route))
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
        color = Color(0xFFFFF1C7),
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, Color(0xFFE2A11B).copy(alpha = 0.25f))
    ) {
        Row(modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.TrendingUp, contentDescription = null, modifier = Modifier.size(15.dp), tint = Color(0xFF167A4A))
            Spacer(Modifier.width(4.dp))
            Text("Вверху", fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
    }
}

@Composable
private fun Metric(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = Color(0xFFF6F6EF),
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
                    DetailMeta(Icons.Default.Payments, "$price  предлагаю")
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
                Text("Баймаҡ → Уфа", color = CanonText, fontWeight = FontWeight.Black, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("18 мая, в 10:00", color = CanonMuted, fontSize = 13.sp)
                Text("450 ₽ предлагаю", color = CanonMuted, fontSize = 13.sp)
            }
            Surface(color = Color(0xFFFFE3A1), shape = RoundedCornerShape(999.dp)) {
                Text(appText("Черновик", "Черновик"), modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = Color(0xFFC17800), fontWeight = FontWeight.Bold, fontSize = 12.sp)
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
                        Text(ride.time, color = CanonMuted, fontSize = 14.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Person, contentDescription = null, tint = CanonMuted, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("${ride.seats} места", color = CanonMuted, fontSize = 14.sp)
                        Text(" · ", color = CanonMuted, fontSize = 14.sp)
                        Text("${ride.price} ₽", color = CanonGreen2, fontWeight = FontWeight.Black, fontSize = 14.sp)
                    }
                }
            }
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
                    Icon(Icons.Default.IosShare, contentDescription = "Поделиться", tint = CanonText)
                }
                IconButton(onClick = onBoost) {
                    Icon(Icons.Default.TrendingUp, contentDescription = stringResource(R.string.boost_route), tint = CanonText)
                }
            }
        }
    }
}

@Composable
private fun ChatScreen(
    voiceMessages: List<LocalVoiceMessage>,
    onAddVoiceMessage: (LocalVoiceMessage) -> Unit,
    onNotifications: () -> Unit
) {
    var selected by remember { mutableStateOf("Активные") }
    var voiceSent by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var latestBookingId by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) {
        ApiClient.getMyBookings().onSuccess { latestBookingId = it.maxOrNull() }
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
                listOf(
                    "Активные" to Icons.Default.ChatBubble,
                    "Заявки" to Icons.Default.ListAlt,
                    "Система" to Icons.Default.Settings
                ).forEach { (label, icon) ->
                    FilledTonalButton(
                        onClick = {
                            if (label == "Система") onNotifications() else selected = label
                        },
                        modifier = Modifier.weight(1f).height(50.dp),
                        shape = RoundedCornerShape(18.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = if (selected == label) CanonMint else Color.White,
                            contentColor = CanonText
                        )
                    ) {
                        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = if (selected == label) CanonGreen2 else CanonMuted)
                        Spacer(Modifier.width(5.dp))
                        Text(label, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        item {
            ChatComposer(
                draft = draft,
                onDraftChange = { draft = it },
                onSend = {
                    val text = draft.trim()
                    if (text.isNotEmpty()) {
                        latestBookingId?.let { ApiClient.fireSendMessage(it, text) }
                        onAddVoiceMessage(LocalVoiceMessage("Байрас", text, "сейчас"))
                        draft = ""
                    }
                },
                onVoice = {
                    onAddVoiceMessage(LocalVoiceMessage("Байрас", "Я буду у вокзала, подойдите к главному входу.", "сейчас"))
                    voiceSent = true
                }
            )
        }
        items(voiceMessages) { message ->
            VoiceMessageCard(message)
        }
        item { Box(Modifier.appearIn(0)) { ChatCard(initial = "Р", name = "Рамиль", subtitle = "Баймаҡ → Сибай", message = "Буду у вокзала в 17:20", time = "16:48", unread = 2, verified = true) } }
        item { Box(Modifier.appearIn(1)) { ChatCard(initial = "Л", name = "Лилия", subtitle = "Заявка в больницу", message = "Могу забрать после 18:00", time = "15:30", unread = 0, verified = false) } }
        item { Box(Modifier.appearIn(2)) { ChatCard(initial = "Ю", name = "Поддержка Юлдаш", subtitle = "Система", message = "Ваш профиль подтверждён", time = "Вчера", unread = 0, verified = true, support = true) } }
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
private fun ChatCard(initial: String, name: String, subtitle: String, message: String, time: String, unread: Int, verified: Boolean, support: Boolean = false) {
    Card(
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
    onVoice: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = RoundedCornerShape(22.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                placeholder = { Text(appText("Сообщение", "Хәбәр"), fontSize = 14.sp) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(18.dp),
                maxLines = 3
            )
            Spacer(Modifier.width(10.dp))
            IconButton(
                onClick = if (draft.isBlank()) onVoice else onSend,
                modifier = Modifier
                    .size(52.dp)
                    .background(CanonGreen2, CircleShape)
            ) {
                if (draft.isBlank()) {
                    Icon(Icons.Default.HeadsetMic, contentDescription = appText("Записать голос", "Тауыш яҙҙырыу"), tint = Color.White)
                } else {
                    Icon(Icons.Default.NearMe, contentDescription = appText("Отправить", "Ебәреү"), tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun VoiceMessageCard(message: LocalVoiceMessage) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF7FAF5)), shape = CanonItemShape, elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = CanonGreen2, shape = CircleShape) {
                Icon(Icons.Default.VolumeUp, contentDescription = null, tint = Color.White, modifier = Modifier.padding(12.dp).size(24.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(appText("Голосовое от ${message.author}", "Тауыш хәбәр: ${message.author}"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 15.sp)
                Text(message.transcript, color = CanonText, fontSize = 14.sp, lineHeight = 18.sp)
                Text(appText("Расшифровка для водителя", "Водитель өсөн текст"), color = CanonMuted, fontSize = 12.sp)
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
        colors = CardDefaults.cardColors(containerColor = if (danger) Color(0xFFFFF2F0) else Color.White),
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
        }
    }
}

@Composable
private fun VoiceRequestScreen(
    contacts: List<TrustedContact>,
    onBack: () -> Unit,
    onCreateRequest: (LocalRequest) -> Unit
) {
    var recognized by remember { mutableStateOf(false) }
    val trusted = contacts.firstOrNull()
    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Голосовая заявка", "Тауыш заявкаһы"), onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                InfoCard(
                    title = appText("Нажмите и скажите", "Баҫығыҙ һәм әйтегеҙ"),
                    text = appText("Например: «Мне завтра утром из Баймака в Сибай, в больницу».", "Мәҫәлән: «Иртәгә иртән Баймаҡтан Сибайға, больницаға»." ),
                    icon = Icons.Default.VolumeUp
                )
            }
            item {
                Button(
                    onClick = { recognized = true },
                    modifier = Modifier.fillMaxWidth().height(78.dp),
                    shape = RoundedCornerShape(22.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)
                ) {
                    Icon(Icons.Default.HeadsetMic, contentDescription = null, modifier = Modifier.size(30.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(appText("Сказать заявку", "Заявканы әйтеү"), fontWeight = FontWeight.Black, fontSize = 20.sp)
                }
            }
            if (recognized) {
                item {
                    VoiceParsedCard(
                        title = appText("Распознано", "Танылды"),
                        lines = listOf(
                            appText("Откуда: Баймаҡ", "Ҡайҙан: Баймаҡ"),
                            appText("Куда: Сибай", "Ҡайҙа: Сибай"),
                            appText("Когда: завтра утром", "Ҡасан: иртәгә иртән"),
                            appText("Цель: в больницу", "Маҡсат: больницаға"),
                            appText("Близкий: ${trusted?.name ?: "не выбран"}", "Яҡын: ${trusted?.name ?: "һайланмаған"}")
                        )
                    )
                }
                item {
                    Button(
                        onClick = {
                            onCreateRequest(
                                LocalRequest(
                                    title = "Голосовая заявка",
                                    route = "Баймаҡ → Сибай",
                                    time = "завтра утром",
                                    passenger = "Байрас",
                                    status = "ищем водителя",
                                    trustedContact = trusted?.name
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
}

@Composable
private fun CreatePassengerRequestScreen(
    onBack: () -> Unit,
    onCreateRequest: (LocalRequest) -> Unit
) {
    var from by remember { mutableStateOf("Баймаҡ") }
    var to by remember { mutableStateOf("Сибай") }
    var time by remember { mutableStateOf("сегодня после 17:00") }
    var seats by remember { mutableStateOf("1") }
    var category by remember { mutableStateOf("Обычная") }
    var price by remember { mutableStateOf("350") }
    var comment by remember { mutableStateOf("") }
    val categories = listOf("Обычная", "В больницу", "Посылка", "С детьми")
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
            item { OutlinedTextField(value = from, onValueChange = { from = it }, label = { Text(appText("Откуда", "Ҡайҙан")) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) }
            item { OutlinedTextField(value = to, onValueChange = { to = it }, label = { Text(appText("Куда", "Ҡайҙа")) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) }
            item { OutlinedTextField(value = time, onValueChange = { time = it }, label = { Text(appText("Дата и время", "Дата һәм ваҡыт")) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) }
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
                    items(categories) { item ->
                        if (category == item) {
                            Button(
                                onClick = { category = item },
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)
                            ) { Text(item, fontWeight = FontWeight.Bold) }
                        } else {
                            OutlinedButton(
                                onClick = { category = item },
                                shape = RoundedCornerShape(16.dp)
                            ) { Text(item, fontWeight = FontWeight.Bold) }
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
                        "$time · $seats ${appText("место", "урын")} · $category",
                        appText("Готовая сумма: $price ₽", "Әҙер сумма: $price ₽")
                    )
                )
            }
            item {
                Button(
                    onClick = {
                        val (apiCat, withKids) = categoryToApi(category)
                        val priceVal = price.toIntOrNull() ?: 0
                        ApiClient.fireCreateRequest(
                            from.trim(), to.trim(),
                            seats.toIntOrNull() ?: 1,
                            apiCat, withKids, comment.trim(), priceVal,
                        )
                        onCreateRequest(
                            LocalRequest(
                                title = category,
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
    var passenger by remember { mutableStateOf("Мама") }
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
                        onCreateRequest(
                            LocalRequest(
                                title = "Заказ за близкого",
                                route = "Баймаҡ → Сибай",
                                time = "сегодня после 17:00",
                                passenger = passenger,
                                status = "ждём отклики",
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
                            onAddContact(TrustedContact("Гульназ", "Сестра", "+7 927 777-88-99", true))
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
                Text("${contact.relation} · ${contact.phone}", color = CanonMuted, fontSize = 13.sp)
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
    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Повторить поездку", "Сәфәрҙе ҡабатлау"), onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item { Text(appText("Частые поездки", "Йыш сәфәрҙәр"), color = CanonGreen, fontSize = 28.sp, fontWeight = FontWeight.Black) }
            itemsIndexed(demoFrequentTrips) { index, trip ->
                Box(Modifier.appearIn(index)) {
                FrequentTripCard(trip) {
                    onRepeat(
                        LocalRequest(
                            title = "Повтор: ${trip.title}",
                            route = "${trip.from} → ${trip.to}",
                            time = trip.timeHint,
                            passenger = "Байрас",
                            status = "создана",
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
                Icon(if (trip.category == "В больницу") Icons.Default.LocalHospital else Icons.Default.Route, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(12.dp).size(28.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(trip.title, color = CanonText, fontWeight = FontWeight.Black, fontSize = 18.sp)
                Text("${trip.from} → ${trip.to}", color = CanonGreen, fontWeight = FontWeight.Bold)
                Text(trip.timeHint, color = CanonMuted, fontSize = 13.sp)
            }
            Icon(Icons.Default.Refresh, contentDescription = null, tint = CanonGreen2)
        }
    }
}

@Composable
private fun CallbackHelpScreen(requested: Boolean, onBack: () -> Unit, onRequest: () -> Unit) {
    var reason by remember { mutableStateOf("Помогите создать заявку") }
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
                    onClick = onRequest,
                    modifier = Modifier.fillMaxWidth().height(58.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)
                ) { Text(appText("Попросить звонок", "Шылтыратыу һорау"), fontWeight = FontWeight.Black, fontSize = 17.sp) }
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
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { ScreenTopBar("Создать поездку", onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("Маршрут для своих", fontSize = 24.sp, fontWeight = FontWeight.Black)
                Text("Укажите путь, места и цену. Контакты откроются после подтверждения.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item { OutlinedTextField(value = from, onValueChange = { from = it }, label = { Text("Откуда") }, leadingIcon = { Icon(Icons.Default.LocationOn, null) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) }
            item { OutlinedTextField(value = to, onValueChange = { to = it }, label = { Text("Куда") }, leadingIcon = { Icon(Icons.Default.NearMe, null) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) }
            item { OutlinedTextField(value = dateTime, onValueChange = { dateTime = it }, label = { Text("Дата и время") }, placeholder = { Text("Сегодня, 18:00") }, leadingIcon = { Icon(Icons.Default.Schedule, null) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = seats, onValueChange = { seats = it.filter(Char::isDigit) }, label = { Text("Мест") }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp))
                    OutlinedTextField(value = price, onValueChange = { price = it.filter(Char::isDigit) }, label = { Text("Цена, ₽") }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp))
                }
            }
            item {
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    label = { Text("Комментарий") },
                    placeholder = { Text("Например: могу взять посылку, заеду через Темясово") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                )
            }
            item {
                InfoCard(
                    title = "Платное поднятие",
                    text = "Можно добавить после публикации. Обычные поездки остаются бесплатными.",
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
                        ApiClient.firePublishRide(fromVal, toVal, departIso, seatsVal, priceVal, comment.trim())
                        onPublish(
                            Ride(
                                id = "local-${System.currentTimeMillis()}",
                                from = fromVal,
                                to = toVal,
                                time = dateTime.ifBlank { "Сегодня, 18:00" },
                                driver = "Байрас",
                                car = comment.ifBlank { "Моя машина" },
                                price = priceVal,
                                seats = seatsVal,
                                rating = 5.0,
                                verified = false,
                                boosted = false
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("Опубликовать")
                }
            }
            item {
                TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                    Text("Отмена")
                }
            }
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
                                Text("Б", color = Color.White, fontWeight = FontWeight.Black, fontSize = 28.sp)
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Байрас", color = Color.White, fontWeight = FontWeight.Black, fontSize = 22.sp)
                                Text(appText("Пассажир · Баймаҡ", "Пассажир · Баймаҡ"), color = Color.White.copy(alpha = 0.78f), fontSize = 13.sp)
                                Text(appText("Телефон скрыт до подтверждения поездки", "Телефон сәфәр раҫланғанға тиклем йәшерелгән"), color = Color.White.copy(alpha = 0.78f), fontSize = 13.sp, lineHeight = 16.sp)
                            }
                        }
                        Surface(color = Color(0xFFDDF5E7), shape = RoundedCornerShape(999.dp)) {
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
            item { Box(Modifier.appearIn(8)) { ProfileActionCard(appText("Простой режим", "Ябай режим"), appText("Большие кнопки и голосовая заявка", "Ҙур төймәләр һәм тауыш заявкаһы"), Icons.Default.VolumeUp, onSimpleMode) } }
            item { Box(Modifier.appearIn(9)) { ProfileActionCard(appText("Доверенные контакты", "Ышаныслы контакттар"), appText("Кому отправлять статус поездки", "Сәфәр статусын кемгә ебәрергә"), Icons.Default.Person, onTrustedContacts) } }
            item { Box(Modifier.appearIn(10)) { ProfileActionCard(appText("Попросить звонок", "Шылтыратыу һорау"), appText("Помощь без чата и сложных форм", "Чатһыҙ һәм ҡатмарлы формаларһыҙ ярҙам"), Icons.Default.HeadsetMic, onCallbackHelp) } }
            item {
                Text(appText("Настройки и помощь", "Көйләүҙәр һәм ярҙам"), color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
            }
            item { Box(Modifier.appearIn(11)) { ProfileActionCard(appText("Настройки", "Көйләүҙәр"), appText("Уведомления, карта, предпочтения", "Хәбәрҙәр, карта, өҫтөнлөктәр"), Icons.Default.Settings, onSettings) } }
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
                Surface(color = Color(0xFFF7FAF5), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, CanonBorder)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(ad.title, modifier = Modifier.weight(1f), color = CanonText, fontWeight = FontWeight.Black, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Surface(color = ad.status.color().copy(alpha = 0.12f), shape = RoundedCornerShape(999.dp)) {
                                Text(ad.status.label(), modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = ad.status.color(), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            }
                        }
                        Text("erid: ${ad.erid} · ${ad.advertiserName}", color = CanonMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            appText(
                                "Пакет: ${ad.packageName} · бюджет: ${ad.budgetLabel}",
                                "Пакет: ${ad.packageName} · бюджет: ${ad.budgetLabel}"
                            ),
                            color = CanonMuted,
                            fontSize = 11.sp,
                            lineHeight = 14.sp
                        )
                        Text("Показы: ${ad.placementsLabel()}", color = CanonMuted, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("Точка: ${ad.mapPoint} · ${ad.contact}", color = CanonMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        Surface(color = if (done) CanonMint else Color(0xFFFFF0D1), shape = CircleShape) {
            Icon(if (done) Icons.Default.CheckCircle else Icons.Default.Schedule, contentDescription = null, tint = if (done) CanonGreen2 else Color(0xFFC17800), modifier = Modifier.padding(9.dp).size(18.dp))
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
    Surface(color = Color(0xFFF7FAF5), shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, CanonBorder)) {
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
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAF6)),
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
                    appText("Реклама · erid: ${ad.erid}", "Реклама · erid: ${ad.erid}"),
                    modifier = Modifier.weight(1f),
                    color = CanonMuted,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Surface(color = Color.White, shape = RoundedCornerShape(999.dp), border = BorderStroke(1.dp, Color(0x10000000))) {
                    Text(label, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = CanonGreen2, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = CanonMint, shape = RoundedCornerShape(14.dp)) {
                    Icon(ad.icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(8.dp).size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(ad.title, color = CanonText, fontSize = 14.sp, lineHeight = 17.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(ad.description, color = CanonMuted, fontSize = 12.sp, lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = { onClick(ad) }) {
                    Text(ad.primaryButton, color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
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
                    appText("Реклама · erid: ${ad.erid}", "Реклама · erid: ${ad.erid}"),
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
                AdChip(ad.packageName, Icons.Default.Payments, Modifier.weight(0.9f))
                AdChip(if (showAdminDetails) ad.placementsLabel() else (ad.category ?: ad.city), Icons.Default.Map, Modifier.weight(1.2f))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = CanonMint, shape = RoundedCornerShape(16.dp)) {
                    Icon(ad.icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(12.dp).size(if (compact) 22.dp else 28.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(ad.title, color = CanonText, fontWeight = FontWeight.Black, fontSize = if (compact) 16.sp else 18.sp, lineHeight = 20.sp)
                    Text(ad.description, color = CanonText, fontSize = 14.sp, lineHeight = 18.sp, maxLines = if (compact) 2 else 3, overflow = TextOverflow.Ellipsis)
                    Text(ad.address, color = CanonMuted, fontSize = 13.sp, lineHeight = 16.sp)
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
                    appText("Действие: ${ad.targetAction} · контакт: ${ad.contact}", "Ғәмәл: ${ad.targetAction} · бәйләнеш: ${ad.contact}"),
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
                    Text(ad.primaryButton, fontWeight = FontWeight.Black, fontSize = 13.sp, maxLines = 1)
                }
                ad.secondaryButton?.let { button ->
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
    Surface(modifier = modifier, color = Color(0xFFF7FAF5), shape = RoundedCornerShape(999.dp), border = BorderStroke(1.dp, CanonBorder)) {
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
        AdStatus.Moderation -> Color(0xFFC17800)
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
        AdPlacement.Help -> if (isBashkir) "Ярдам" else "Помощь"
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
                        Icon(Icons.Default.ArrowBackIosNew, contentDescription = "Назад", tint = CanonText)
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
                                DetailMeta(Icons.Default.CalendarMonth, ride.time, modifier = Modifier.weight(1.45f))
                                DetailMeta(Icons.Default.Person, "${ride.seats} места", modifier = Modifier.weight(0.8f))
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
                                Text("Р", modifier = Modifier.padding(22.dp), color = CanonGreen2, fontWeight = FontWeight.Black, fontSize = 22.sp)
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
                                DetailMeta(Icons.Default.DirectionsCar, ride.car)
                            }
                            Surface(color = CanonMint, shape = CircleShape) {
                                Icon(Icons.Default.PhoneLocked, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(16.dp))
                            }
                        }
                        TripInfoRow(Icons.Default.LocationOn, appText("Место встречи", "Осрашыу урыны"), appText("Автовокзал, вход 2", "Автовокзал, 2-се инеү"))
                        MapPreview(Modifier.height(170.dp))
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
    var selected by remember { mutableStateOf("Все") }
    var cleared by remember { mutableStateOf(false) }
    val notifications = listOf(
        Triple(Icons.Default.DirectionsCar, appText("Водитель откликнулся на заявку", "Водитель заявкаға яуап бирҙе"), appText("Рамиль едет к вам", "Рамиль һеҙгә килә")),
        Triple(Icons.Default.CheckCircle, appText("Поездка подтверждена", "Сәфәр раҫланды"), "Баймаҡ → Сибай, сегодня в 17:30"),
        Triple(Icons.Default.ChatBubble, appText("Новое сообщение в чате", "Чатта яңы хәбәр"), "Рамиль: «Буду у вокзала в 17:20»"),
        Triple(Icons.Default.Shield, appText("Профиль успешно проверен", "Профиль уңышлы тикшерелде"), appText("Ваш профиль подтверждён", "Профилегеҙ раҫланды")),
        Triple(Icons.Default.Schedule, appText("Поездка начнётся через 30 минут", "Сәфәр 30 минуттан башлана"), "Баймаҡ → Сибай, сегодня в 17:30")
    )
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
                SegmentedTabs(listOf("Все", "Поездки", "Чат", "Система"), selected, onSelect = { selected = it })
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
                    selected == "Все" ||
                        (selected == "Поездки" && icon != Icons.Default.ChatBubble && icon != Icons.Default.Shield) ||
                        (selected == "Чат" && icon == Icons.Default.ChatBubble) ||
                        (selected == "Система" && icon == Icons.Default.Shield)
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
                Surface(color = Color(0xFFFFF5F3), shape = CanonItemShape, border = BorderStroke(1.dp, Color(0x33D93025))) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = Color(0xFFFFE1DD), shape = RoundedCornerShape(18.dp)) {
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
                Text("Б", color = Color.White, fontWeight = FontWeight.Black, fontSize = 24.sp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Байрас", color = Color.White, fontWeight = FontWeight.Black, fontSize = 20.sp)
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
    onSos: () -> Unit
) {
    val context = LocalContext.current
    var messages by remember { mutableStateOf<List<MessageDto>>(emptyList()) }
    var draft by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var showShare by remember { mutableStateOf(false) }
    val shareSheet = rememberModalBottomSheetState()

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
                Card(colors = CardDefaults.cardColors(containerColor = CanonSurface), shape = CanonCardShape, elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
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
            item { Text(appText("Статус поездки", "Сәфәр хәле"), fontWeight = FontWeight.Bold) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "sat" to appText("Я сел", "Ултырҙым"),
                        "arrived" to appText("Доехал", "Барып еттем"),
                        "done" to appText("Завершить", "Тамам")
                    ).forEach { (st, label) ->
                        FilledTonalButton(
                            onClick = { status = st; bookingId?.let { ApiClient.fireSetTripStatus(it, st) } },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = if (status == st) CanonMint else Color.White,
                                contentColor = CanonText
                            )
                        ) { Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = CanonSurface), shape = CanonItemShape, elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
                    Row(
                        Modifier.fillMaxWidth().clickable { showShare = true }.padding(16.dp),
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
            item { Text(appText("Чат по поездке", "Сәфәр буйынса чат"), fontWeight = FontWeight.Bold) }
            item {
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
                    onVoice = {}
                )
            }
            items(messages) { m -> MessageBubble(m.text, mine = m.senderId == -1) }
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
                            Toast.makeText(context, "Поездка отправлена: ${c.name}", Toast.LENGTH_SHORT).show()
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
private fun MessageBubble(text: String, mine: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Surface(
            color = if (mine) CanonGreen2 else Color.White,
            shape = RoundedCornerShape(18.dp),
            shadowElevation = 1.dp
        ) {
            Text(
                text,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                color = if (mine) Color.White else CanonText,
                fontSize = 15.sp
            )
        }
    }
}

@Composable
private fun SosScreen(onBack: () -> Unit) {
    val categories = listOf("Медицина", "Поломка авто", "Другое")
    var selected by remember { mutableStateOf(categories.first()) }
    var description by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf(false) }

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
                        Surface(color = Color(0xFFFFE8E4), shape = CircleShape) {
                            Icon(Icons.Default.Sos, contentDescription = null, tint = Color(0xFFD43C31), modifier = Modifier.padding(14.dp))
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
                    categories.forEach { category ->
                        FilledTonalButton(
                            onClick = { selected = category; sent = false },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(18.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = if (selected == category) Color(0xFFFFD5CE) else Color.White
                            ),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                        ) {
                            Text(
                                category,
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
                        text = appText("Категория: $selected. Статус: ожидание отклика.", "Категория: $selected. Статус: яуап көтөү."),
                        icon = Icons.Default.Sos
                    )
                }
            }
            item {
                Button(
                    onClick = {
                        sent = true
                        ApiClient.fireSos(sosCategoryToApi(selected), description.trim())
                    },
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD43C31))
                ) { Text(appText("Отправить SOS", "SOS ебәреү")) }
            }
            item { TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(appText("Назад", "Кире")) } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VerifyDriverScreen(onBack: () -> Unit, onSelectTab: (HomeTab) -> Unit) {
    var sent by remember { mutableStateOf(false) }

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
                Text(appText("Проверка водителя", "Водителде тикшереү"), color = CanonGreen, fontSize = 34.sp, lineHeight = 36.sp, fontWeight = FontWeight.Black)
                Text(appText("Мы проверяем ваши данные, чтобы пассажиры могли вам доверять", "Пассажирҙар ышанһын өсөн мәғлүмәтте тикшерәбеҙ"), color = CanonMuted, fontSize = 15.sp, lineHeight = 20.sp)
            }
            item {
                Surface(color = Color(0xFFF2FAF5), shape = CanonItemShape, border = BorderStroke(1.dp, Color(0x2235A363))) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(color = CanonMint, shape = CircleShape) {
                                Icon(Icons.Default.Shield, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(20.dp).size(36.dp))
                            }
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(appText("Статус: В процессе", "Статус: Бара"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 18.sp)
                                Text(appText("Проверяем документы и информацию об автомобиле.", "Документтарҙы һәм машина мәғлүмәтен тикшерәбеҙ."), color = CanonMuted, lineHeight = 19.sp)
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StepDot(done = true)
                            Box(Modifier.weight(1f).height(2.dp).background(CanonGreen2))
                            StepDot(done = true)
                            Box(Modifier.weight(1f).height(2.dp).background(CanonBorder))
                            StepDot(done = false)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(appText("Документы", "Документтар"), color = CanonMuted, fontSize = 12.sp)
                            Text(appText("Автомобиль", "Автомобиль"), color = CanonMuted, fontSize = 12.sp)
                            Text(appText("Готово", "Әҙер"), color = CanonMuted, fontSize = 12.sp)
                        }
                    }
                }
            }
            item { Text(appText("Документы и информация", "Документтар һәм мәғлүмәт"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 17.sp) }
            item {
                SettingsGroup {
                    DocumentRow(Icons.Default.Badge, appText("Права", "Права"), appText("Загружено", "Йөкләнде"), true)
                    DocumentRow(Icons.Default.DirectionsCar, appText("Машина", "Машина"), appText("Загружено", "Йөкләнде"), true)
                    DocumentRow(Icons.Default.PhotoCamera, appText("Фото авто", "Авто фотоһы"), appText("Требуется", "Кәрәк"), false)
                    DocumentRow(Icons.Default.Description, appText("Госномер", "Дәүләт номеры"), appText("Требуется", "Кәрәк"), false)
                }
            }
            item { Text(appText("Информация об автомобиле", "Автомобиль тураһында"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 17.sp) }
            item {
                SettingsGroup {
                    SettingsNavRow(Icons.Default.DirectionsCar, appText("Марка авто", "Машина маркаһы"), "Kia Rio")
                    SettingsNavRow(Icons.Default.Tune, appText("Цвет", "Төҫ"), appText("Белый", "Аҡ"))
                    SettingsNavRow(Icons.Default.Person, appText("Количество мест", "Урындар һаны"), "4")
                }
            }
            if (sent) {
                item { InfoCard(appText("Заявка отправлена", "Заявка ебәрелде"), appText("Мы покажем статус здесь после проверки.", "Тикшереүҙән һуң статус бында күрһәтелә."), Icons.Default.Verified) }
            }
            item {
                Button(
                    onClick = { sent = true },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)
                ) {
                    Icon(Icons.Default.Verified, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(appText("Отправить на проверку", "Тикшереүгә ебәреү"), fontWeight = FontWeight.Black, fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
private fun StepDot(done: Boolean) {
    Surface(color = if (done) CanonGreen2 else Color.White, shape = CircleShape, border = BorderStroke(1.dp, if (done) CanonGreen2 else CanonBorder)) {
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
            Text(status, color = if (loaded) CanonGreen2 else Color(0xFFC17800), fontSize = 13.sp)
        }
        Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = CanonMuted)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SupportScreen(onBack: () -> Unit) {
    val amounts = listOf(10, 30, 50, 100)
    var selectedAmount by remember { mutableIntStateOf(30) }
    var completed by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { ScreenTopBar(stringResource(R.string.support_yuldash), onBack) }
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
                        Text("Добровольная поддержка", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                        Text(stringResource(R.string.support_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                containerColor = if (selectedAmount == amount) MaterialTheme.colorScheme.primaryContainer else Color.White
                            )
                        ) {
                            Text("$amount ₽")
                        }
                    }
                }
            }
            item {
                OutlinedButton(onClick = { selectedAmount = 150; completed = false }, modifier = Modifier.fillMaxWidth()) {
                    Text("Своя сумма")
                }
            }
            item {
                Button(onClick = { completed = true }, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Default.Payments, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Поддержать на $selectedAmount ₽")
                }
            }
            if (completed) {
                item {
                    InfoCard(
                        title = "Спасибо за поддержку",
                        text = "Мок-платёж на $selectedAmount ₽ отмечен как успешный. Реальную оплату подключим позже.",
                        icon = Icons.Default.VolunteerActivism
                    )
                }
            }
            item {
                TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                    Text("Не сейчас")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BoostScreen(onBack: () -> Unit) {
    var activatedPlan by remember { mutableStateOf<String?>(null) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { ScreenTopBar(stringResource(R.string.boost_route), onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { BoostPlan("Быстрое поднятие", "2 часа выше в списке", "20 ₽", onClick = { activatedPlan = "Быстрое поднятие" }) }
            item { BoostPlan("День вверху", "24 часа выше в списке + выделение на карте", "50 ₽", onClick = { activatedPlan = "День вверху" }) }
            item { BoostPlan("Срочная поездка", "6 часов выше в списке, выделение, метка срочно", "70 ₽", onClick = { activatedPlan = "Срочная поездка" }) }
            if (activatedPlan != null) {
                item {
                    InfoCard(
                        title = "Поднятие включено",
                        text = "Тариф «$activatedPlan» активирован в мок-режиме. Реальный платёж подключим позже.",
                        icon = Icons.Default.TrendingUp
                    )
                }
            }
            item {
                Text(
                    "Поднятие не гарантирует бронирование и влияет только на релевантные результаты.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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
