package com.yuldash.app

// Демо-данные (моки) + модели рекламы/ленты карты. Вынесено из MainActivity (Фаза 3, косметика).
// Все символы internal. Импорты целиком — лишние = варнинги. // MOCK — заменить на API позже.

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
    val linkUrl: String = "",   // ссылка/таргет партнёра (живая реклама с сервера); клик → открыть в браузере
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

internal val demoPartnerAds = listOf(
    PartnerAd(
        id = "ad-pharmacy-hospital",
        title = "Аптека «Здоровье»",
        titleBa = "«Здоровье» дарыуханаһы",
        description = "Скидка 10% для поездок в больницу",
        descriptionBa = "Больницаға сәфәрҙәр өсөн 10% ташлама",
        address = "Баймаҡ, ул. Ленина, 12",
        addressBa = "Баймаҡ, Ленин урамы, 12",
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

/*
 * Демо-поездки: показываются, пока сервер не ответил, и остаются, если он недоступен вовсе
 * («экран не пустеет»). Отсюда важное следствие про id.
 *
 * Раньше здесь стояли «1», «2», «3». Приложение читает id поездки как число (`toIntOrNull`)
 * и шлёт его серверу. То есть человек в селе с плохой связью видел ПРИМЕР, жал «Забронировать»
 * — и, если связь к этому моменту возвращалась, бронировал НАСТОЯЩУЮ поездку №1, которую
 * никогда не видел: чужой водитель, чужой маршрут, чужая цена. В соседнем комментарии
 * (`YuldashApp`, замена списка) про эту опасность уже написано — но закрыт был только путь
 * «сервер ответил», а путь «сервер молчит» остался открытым.
 *
 * Теперь id заведомо НЕ число: `toIntOrNull()` вернёт null, и демо-поездка физически не может
 * превратиться в серверную бронь. Держит `DemoRidesNeverBookableTest`.
 */
internal val demoRides = listOf(
    Ride(
        id = "demo-1",
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
        id = "demo-2",
        from = "Темясово",
        to = "Уфа",
        time = "Завтра, 06:00",
        timeBa = "Иртәгә, 06:00",
        driver = "Айгуль",
        driverIsWoman = true,   // F9: демо женщины-водителя (бейдж «за рулём женщина» в офлайне)
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
        id = "demo-3",
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

internal val demoPopularRoutes = listOf(
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
internal enum class FeedKind { Route, Live, Top, Fact, Community, Donate }

internal data class MapFeedCard(
    val kind: FeedKind,
    val badge: String, val badgeBa: String,
    val title: String, val titleBa: String,
    val sub: String, val subBa: String,
    val pill: String, val pillBa: String,
    val route: PopularRoute? = null    // задан → карточка-маршрут, обновляет выбор для «Найти поездку»
)

