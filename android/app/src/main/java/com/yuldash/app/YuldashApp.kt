package com.yuldash.app

// Корень навигации: YuldashApp (when(screen)) + HomeScreen (Scaffold+вкладки) + нижнее меню.
// Вынесено из MainActivity (Фаза 3). Импорты целиком — лишние = варнинги.

import com.yuldash.app.R
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.QuestionAnswer
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
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MoneyOff
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
import androidx.compose.material.icons.filled.RocketLaunch
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.MutableState
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
import com.yuldash.app.data.Analytics
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
import kotlinx.coroutines.isActive
import androidx.compose.ui.platform.testTag

internal fun bookingStatusAllowsActiveTrip(status: String): Boolean =
    status == "confirmed" || status == "onboard" || status == "done"

internal fun bookingStatusAllowsBoarding(status: String): Boolean =
    status == "confirmed" || status == "onboard"

/** Код места показа с сервера → место в приложении. Незнакомое имя (сервер завёл новое,
 *  приложение ещё не знает) → null: такое объявление просто не покажем в этом слоте,
 *  вместо того чтобы уронить разбор всего списка рекламы. */
private fun adPlacementOf(code: String): AdPlacement? = when (code.trim().lowercase()) {
    "route" -> AdPlacement.Route
    "rideslist" -> AdPlacement.RidesList
    "nearby" -> AdPlacement.Nearby
    "tripdetails" -> AdPlacement.TripDetails
    "profile" -> AdPlacement.Profile
    "help" -> AdPlacement.Help
    else -> null
}

/**
 * Погасить ВСЮ живую трансляцию позиции — одной строкой на всех, кто уходит из аккаунта.
 *
 * Зачем (аудит 2026-08-08, волна 109). Сервисов, льющих GPS, два: поездка и доставка курьера.
 * Выход из аккаунта и удаление аккаунта глушили только поездку. Доставка оставалась: её сервис
 * умеет воскресать после смерти процесса по номерам посылок, сохранённым на диске, — то есть
 * телефон нового владельца мог сам снова начать светить дорогу за прошлого.
 *
 * Появится третий такой сервис — гасить его надо здесь, и это держит `LogoutLeavesNothingTest`.
 */
internal fun stopLiveTracking(context: android.content.Context) {
    TripLocationService.stop(context)
    CourierLocationService.stop(context)
    // Линия такси — третий сервис, который льёт GPS (волна 115). Волна 109 собрала гашение
    // в одну строку, но нашла соседей по имени «…LocationService», а этот назван иначе —
    // и остался жить: после выхода телефон продолжал слать координаты до двенадцати часов,
    // уже у нового владельца.
    TaxiLineService.stop(context)
}

/**
 * Конец сессии — что бы его ни вызвало.
 *
 * Дверей три: кнопка «Выйти», удаление аккаунта и «сессия истекла» (сервер разлогинил,
 * продлить не удалось). Первые две убирали за собой, третья — нет (волна 115): человека
 * просто выкидывало на экран входа, а живой GPS продолжал идти, и в памяти оставались
 * чужие доверенные контакты с телефонами. Следующий вошедший на этом телефоне видел их.
 */
internal fun endSession(context: android.content.Context, vm: YuldashViewModel) {
    stopLiveTracking(context)
    vm.clearUserData()
}

@Composable
internal fun YuldashApp() {
    // При изменении positional Compose/launcher slots меняем namespace, не угадываем старые ключи.
    key("yuldash-app-saveable-v1") { YuldashAppContent() }
}

@Composable
private fun YuldashAppContent() {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("yuldash_prefs", android.content.Context.MODE_PRIVATE)
    }
    // Память экранов: что было на экране, когда с него ушли (см. SaveableStateProvider ниже).
    // Черновики принадлежат аккаунту, а не устройству. Обновление токена того же
    // пользователя сохраняет их; выход или смена пользователя создаёт новое хранилище.
    val navigationSession by ApiClient.sessionChanges.collectAsState()
    val screenStateOwner = ApiClient.myUserId()
    val screenStates = key(screenStateOwner) { rememberSaveableStateHolder() }
    // Чем человек рассчитывается с водителем. Помним между заказами: платят изо дня в день
    // одинаково, и заставлять выбирать заново каждый раз — мелкая, но ежедневная работа.
    // Первый заказ — наличные: «договоримся» звучит нейтрально, но на деле это отложенный спор.
    var payMethod by remember {
        mutableStateOf(prefs.getString(PAY_METHOD_PREF, PayMethods.CASH) ?: PayMethods.CASH)
    }
    // Всё состояние приложения живёт в YuldashViewModel (вынесено из god-composable).
    val vm: YuldashViewModel = viewModel()
    val rides = vm.rides
    val trustedContacts = vm.trustedContacts
    val localRequests = vm.localRequests
    var requestsLoading by remember { mutableStateOf(true) }   // скелетон «Моих заявок» до первой загрузки
    var requestsError by remember { mutableStateOf(false) }   // обрыв связи ≠ «заявок нет» (аудит 2026-08-04)
    var bookingInFlight by remember(navigationSession) { mutableStateOf(false) }  // создание/отмена; свой флаг для каждой сессии
    var callbackInFlight by remember { mutableStateOf(false) }
    var requestsReload by remember { mutableStateOf(0) }      // кнопка «Повторить» на экране заявок
    val appScope = rememberCoroutineScope()
    var activeBookingId by vm.activeBookingId
    var activeTrip by vm.activeTrip   // подтверждённая поездка → маршрут на карте; исчезает при завершении
    // Live-позиция: foreground-сервис стримит мой GPS попутчику ТОЛЬКО в активной поездке и ТОЛЬКО при
    // выданном гео-разрешении (приватность). Поездка кончилась / нет разрешения → глушим стрим.
    LaunchedEffect(activeBookingId, activeTrip, vm.language.value) {
        val bid = activeBookingId
        val hasLoc = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (bid != null && activeTrip != null && hasLoc) TripLocationService.start(context, bid, vm.language.value)
        else TripLocationService.stop(context)
    }
    val voiceMessages = vm.voiceMessages
    // Экран после сплэша вычисляем один раз; сплэш показывается первым ~1.6с.
    val splashTarget = remember {
        // Старт ВСЕГДА на главном экране — КАРТА (Home). Раньше «помнили» режим и открывали кабинет
        // водителя — но главный вход в приложение это карта; в кабинет водитель идёт сам кнопкой «Я водитель».
        when {
            !prefs.getBoolean("onboarding_completed", false) -> Screen.Intro  // ПЕРВЫЙ запуск → брендовое интро (даже в debug)
            BuildConfig.DEBUG -> Screen.Home              // DEV-обход входа (повторные запуски) → карта
            ApiClient.isLoggedIn() -> Screen.Home         // уже вошёл → карта
            else -> Screen.Login
        }
    }
    // VM переживает поворот → selectedRide/activeTrip больше НЕ сбрасываются на повороте брони/поездки.
    // screen/language/startHomeTab переживают и смерть процесса (persistNav в SavedStateHandle, ниже).
    var screen by vm.screen
    var language by vm.language
    // Сохраняем только запрошенный экран, без данных прежнего аккаунта или автодействия.
    var destinationAfterLogin by rememberSaveable(saver = rootNavigationStateSaver<Screen?>("destinationAfterLogin")) { mutableStateOf<Screen?>(null) }
    // SharedPreferences — источник языка для настоящего холодного старта, когда SavedStateHandle пуст.
    // Пока гидратация не закончилась, не пишем дефолтный RU обратно на диск и сервер.
    val persistedLanguage = remember(context) { AppPrefs.language(context) }
    var languagePersistenceReady by remember { mutableStateOf(false) }
    LaunchedEffect(persistedLanguage) {
        vm.restorePersistedLanguage(persistedLanguage)
        languagePersistenceReady = true
    }
    // Сессия протухла на сервере (refresh мёртв) → не оставляем пустые экраны: говорим и уводим на вход.
    // `rememberUpdatedState`, а не просто val: `LaunchedEffect(Unit)` запускается ОДИН раз и
    // запоминает то, что было в момент запуска. Язык к этому моменту ещё не восстановлен из
    // настроек (это делает эффект строкой выше), поэтому у человека с башкирским интерфейсом
    // сообщение выходило по-русски — проверено на эмуляторе. Теперь эффект читает актуальное
    // значение и при смене языка тоже.
    val sessionExpiredMsg = rememberUpdatedState(appText("Сессия истекла. Войди снова.", "Сессия тамамланды. Ҡабат кер."))
    LaunchedEffect(Unit) {
        ApiClient.sessionExpired.collect { expired ->
            if (expired) {
                Toast.makeText(context, sessionExpiredMsg.value, Toast.LENGTH_LONG).show()
                // Сессия кончилась не по кнопке, а сама — но убрать за собой надо так же:
                // погасить живой GPS и стереть чужое из памяти (волна 115).
                endSession(context, vm)
                screen = Screen.Login
                ApiClient.sessionExpired.value = false
            }
        }
    }
    var selectedRide by vm.selectedRide
    var startHomeTab by vm.startHomeTab
    var callbackRequested by vm.callbackRequested
    var responsesRequestId by vm.responsesRequestId   // какую заявку открыть в «Откликах»
    var driverProfileId by rememberSaveable(screenStateOwner, saver = rootNavigationStateSaver<Int>("driverProfileId", screenStateOwner)) { mutableStateOf(0) }   // чей публичный профиль открыть (0 = никакой)
    var createRideReturnScreen by rememberSaveable(saver = rootNavigationStateSaver<Screen>("createRideReturnScreen")) { mutableStateOf(Screen.Home) }
    var createRideReturnHomeTab by rememberSaveable(saver = rootNavigationStateSaver<HomeTab>("createRideReturnHomeTab")) { mutableStateOf(HomeTab.Request) }
    var createRidePrefillDate by rememberSaveable(saver = rootNavigationStateSaver<String?>("createRidePrefillDate")) { mutableStateOf<String?>(null) }   // F15: дата-шаблон праздника для формы поездки
    var trustedContactsReturnScreen by rememberSaveable(saver = rootNavigationStateSaver<Screen>("trustedContactsReturnScreen")) { mutableStateOf(Screen.SimpleMode) }
    var trustedContactsReturnHomeTab by rememberSaveable(saver = rootNavigationStateSaver<HomeTab>("trustedContactsReturnHomeTab")) { mutableStateOf(HomeTab.Profile) }
    var selectedBookingStatus by rememberSaveable(screenStateOwner, saver = rootNavigationStateSaver<String>("selectedBookingStatus", screenStateOwner)) { mutableStateOf("") }
    var instantTripOrderId by rememberSaveable(screenStateOwner, saver = rootNavigationStateSaver<Int>("instantTripOrderId", screenStateOwner)) { mutableStateOf(0) }   // «Быстрый заказ»: id заказа для экрана поездки водителя
    var instantChatOrderId by vm.instantChatOrderId   // ID чата сохраняется вместе с маршрутом.
    var sosOrderId by rememberSaveable(screenStateOwner, saver = rootNavigationStateSaver<Int>("sosOrderId", screenStateOwner)) { mutableStateOf(0) }           // SOS с контекстом такси-заказа (B7b-2); 0 = без заказа
    var sosBookingId by rememberSaveable(screenStateOwner, saver = rootNavigationStateSaver<Int>("sosBookingId", screenStateOwner)) { mutableStateOf(0) }         // SOS с контекстом попутки; 0 = без поездки
    var sosContextNote by rememberSaveable(screenStateOwner, saver = rootNavigationStateSaver<String>("sosContextNote", screenStateOwner)) { mutableStateOf("") }      // подпись дежурному (курьер: маршрут доставки)
    var returnToSosAfterLogin by rememberSaveable(saver = rootNavigationStateSaver<Boolean>("returnToSosAfterLogin")) { mutableStateOf(false) }
    LaunchedEffect(returnToSosAfterLogin, screen) {
        if (!returnToSosAfterLogin || !ApiClient.isLoggedIn()) return@LaunchedEffect
        if (screen == Screen.Splash || screen == Screen.Intro || screen == Screen.Onboarding) return@LaunchedEffect
        // После входа аккаунт мог смениться. Возвращаем только экран, без чужого контекста
        // и без автоматической отправки: пользователь должен снова нажать кнопку SOS.
        sosOrderId = 0
        sosBookingId = 0
        sosContextNote = ""
        returnToSosAfterLogin = false
        screen = Screen.Sos
    }
    var receiptBookingId by rememberSaveable(screenStateOwner, saver = rootNavigationStateSaver<Int>("receiptBookingId", screenStateOwner)) { mutableStateOf(0) }     // Квитанция завершённой поездки: id брони
    var taxiReceiptOrderId by rememberSaveable(screenStateOwner, saver = rootNavigationStateSaver<Int>("taxiReceiptOrderId", screenStateOwner)) { mutableStateOf(0) }   // Чек за такси-поездку: id заказа
    // Чат по посылке: id + с кем говорим + статус (по нему чат уходит в read-only после закрытия).
    var parcelChatId by rememberSaveable(screenStateOwner, saver = rootNavigationStateSaver<Int>("parcelChatId", screenStateOwner)) { mutableStateOf(0) }
    var parcelChatPeerIsCourier by rememberSaveable(screenStateOwner, saver = rootNavigationStateSaver<Boolean>("parcelChatPeerIsCourier", screenStateOwner)) { mutableStateOf(true) }
    var parcelChatStatus by rememberSaveable(screenStateOwner, saver = rootNavigationStateSaver<String>("parcelChatStatus", screenStateOwner)) { mutableStateOf("") }
    var incidentId by vm.incidentId           // «Справедливость»: id открытого спора
    // Режим фотоконтроля машины: такси или курьер. Экран один, кадры разные — и человек,
    // который возит и людей, и посылки, показывает машину дважды, по разу за роль.
    var carPhotoMode by rememberSaveable(screenStateOwner, saver = rootNavigationStateSaver<String>("carPhotoMode", screenStateOwner)) { mutableStateOf("") }
    var supportTicketId by vm.supportTicketId
    // F13 «карауль поездку»: предзаполнение экрана «Мои подписки» маршрутом из карты (может быть пустым).
    var routeWatchPrefillFrom by rememberSaveable(screenStateOwner, saver = rootNavigationStateSaver<String>("routeWatchPrefillFrom", screenStateOwner)) { mutableStateOf("") }
    var routeWatchPrefillTo by rememberSaveable(screenStateOwner, saver = rootNavigationStateSaver<String>("routeWatchPrefillTo", screenStateOwner)) { mutableStateOf("") }
    // Роль админа (Александр): показывает инструмент «Заявка за пользователя» в Настройках.
    var isAdmin by vm.isAdmin
    // Версия сессии: инкрементится при входе (onContinue), чтобы user-специфичные загрузки
    // (роль/мои заявки/доверенные контакты) перечитались ПОСЛЕ логина, а не только один раз на Splash
    // (иначе после входа в этой же сессии эти данные оставались пустыми до перезапуска приложения).
    var sessionVersion by remember { mutableStateOf(0) }
    LaunchedEffect(sessionVersion) {
        ApiClient.me().onSuccess { isAdmin = it.optString("role") == "admin" }
        // Язык на сервер и после входа: LaunchedEffect(language) отработал ещё до логина,
        // когда слать было некому — иначе пуши остались бы русскими до смены языка вручную.
        if (languagePersistenceReady) {
            ApiClient.fireUpdateLanguage(if (language == AppLanguage.Ba) "ba" else "ru")
        }
    }
    // Android 13+ требует РАНТАЙМ-разрешение на уведомления — без него пуши тихо не показываются
    // (FCM настроен end-to-end, но без этого запроса доставка на новых телефонах = no-op).
    // Просим один раз, когда пользователь уже в приложении (не на онбординге/входе).
    // Язык дублируем на диск (AppPrefs): FCM и фоновые сервисы живут вне Compose и берут его оттуда.
    LaunchedEffect(language, languagePersistenceReady) {
        if (!languagePersistenceReady) return@LaunchedEffect
        AppPrefs.setLanguage(context, language)
        // И на сервер: пуши приходят на языке пользователя. Поле сервер принимал давно,
        // но клиент его не слал — башкироязычный получал русские уведомления.
        ApiClient.fireUpdateLanguage(if (language == AppLanguage.Ba) "ba" else "ru")
    }
    // Полноэкранный оффер такси (B7a-2): тап/фуллскрин уведомления «Новый заказ» → MainActivity
    // ставит NavSignals → открываем кабинет водителя (там InstantOfferOverlay). Ждём, пока сплэш
    // отработает (он перезаписал бы screen), и не дёргаем навигацию на входе/онбординге.
    // Старые внутренние кнопки пишут bridge; private уведомления уже сохранены Activity.
    LaunchedEffect(NavSignals.openDriverCabinet.value, NavSignals.openInstantOrder.value,
        NavSignals.openInstantChat.value, DeepLink.pendingParcels.value, DeepLink.pendingSupport.value,
        NavSignals.openAdsCabinet.value, NavSignals.openPartnerCabinet.value, DeepLink.pendingFairness.value,
        DeepLink.pendingRequestResponsesId.value, DeepLink.pendingRequestsFeed.value, DeepLink.pendingApplicationScreen.value, navigationSession) {
        val previous = vm.pendingScreenNavigation.value
        val bridge = when {
            NavSignals.openInstantChat.value > 0 -> Screen.InstantChat to NavSignals.openInstantChat.value
            NavSignals.openInstantOrder.value -> Screen.InstantOrder to 0
            NavSignals.openDriverCabinet.value -> Screen.DriverCabinet to 0
            DeepLink.pendingParcels.value -> Screen.Parcels to 0
            DeepLink.pendingSupport.value -> Screen.SupportTickets to 0
            NavSignals.openAdsCabinet.value -> Screen.AdsCabinet to 0
            NavSignals.openPartnerCabinet.value -> Screen.PartnerCabinet to 0
            DeepLink.pendingFairness.value -> Screen.FairnessCenter to 0
            (DeepLink.pendingRequestResponsesId.value ?: 0) > 0 -> Screen.RequestResponses to DeepLink.pendingRequestResponsesId.value!!
            DeepLink.pendingRequestsFeed.value -> Screen.RequestsFeed to 0
            DeepLink.pendingApplicationScreen.value != null -> DeepLink.pendingApplicationScreen.value!! to 0
            else -> null
        }
        vm.pendingScreenForOwner(ApiClient.myUserId())
        if (bridge != null && (previous?.destination != bridge.first ||
                (bridge.first in setOf(Screen.InstantChat, Screen.RequestResponses) && previous.targetId != bridge.second))) {
            vm.requestScreenDestination(bridge.first, ApiClient.myUserId(), bridge.second)
        }
    }
    LaunchedEffect(vm.pendingScreenNavigation.value, screen, navigationSession) {
        val destination = vm.pendingScreenForOwner(ApiClient.myUserId()) ?: return@LaunchedEffect
        if (screen in setOf(Screen.Splash, Screen.Intro, Screen.Onboarding)) return@LaunchedEffect
        if (!ApiClient.isLoggedIn()) { screen = Screen.Login; return@LaunchedEffect }
        ApiClient.runIfCurrentSession(navigationSession) {
            vm.consumePendingScreen(destination) {
                if (destination.destination == Screen.InstantChat) instantChatOrderId = destination.targetId
                if (destination.destination == Screen.SupportTicket) supportTicketId = destination.targetId
                if (destination.destination == Screen.RequestResponses) responsesRequestId = destination.targetId
                if (destination.destination == Screen.IncidentDetail) incidentId = destination.targetId
                // Оффер не выводит водителя из уже исполняемого заказа.
                if (destination.destination != Screen.DriverCabinet || screen != Screen.InstantDriverTrip) screen = destination.destination
            }
        }
    }
    // Кнопки «Написать»/SOS живут глубоко в экранах такси (в т.ч. встроенных в главную) —
    // навигация через NavSignals (паттерн openDriverCabinet), без колбэков через все слои.
    // Чек за такси-поездку: кнопка в финальной карточке заказа (и у пассажира, и у водителя).
    val wantTaxiReceipt by NavSignals.openTaxiReceipt
    LaunchedEffect(wantTaxiReceipt, screen) {
        if (wantTaxiReceipt <= 0) return@LaunchedEffect
        if (screen == Screen.Splash || screen == Screen.Intro || screen == Screen.Onboarding) return@LaunchedEffect
        if (ApiClient.isLoggedIn()) {
            taxiReceiptOrderId = wantTaxiReceipt
            NavSignals.openTaxiReceipt.value = 0
            screen = Screen.TaxiReceipt
        }
    }
    val wantSosForOrder by NavSignals.openSosForOrder
    LaunchedEffect(wantSosForOrder, screen) {
        if (wantSosForOrder <= 0) return@LaunchedEffect
        // P3: тот же guard — тап по пушу SOS на холодном старте не должен теряться под сплэшем.
        if (screen == Screen.Splash || screen == Screen.Intro || screen == Screen.Onboarding) return@LaunchedEffect
        sosOrderId = wantSosForOrder
        sosBookingId = 0
        sosContextNote = ""
        NavSignals.openSosForOrder.value = 0
        screen = Screen.Sos
    }
    // Красная кнопка курьера: он глубоко внутри вкладки «Доставка», колбэк тянуть незачем.
    // Подпись с маршрутом уходит дежурному в заметке сигнала — поля под доставку у события нет.
    val wantSosNote by NavSignals.openSosWithNote
    LaunchedEffect(wantSosNote, screen) {
        val note = wantSosNote ?: return@LaunchedEffect
        if (screen == Screen.Splash || screen == Screen.Intro || screen == Screen.Onboarding) return@LaunchedEffect
        NavSignals.openSosWithNote.value = null
        // Присваиваем состояние напрямую: openSos() объявлена ниже по телу композабла,
        // локальную функцию до объявления не вызвать (тем же способом ходит сигнал такси выше).
        sosOrderId = 0
        sosBookingId = 0
        sosContextNote = note
        screen = Screen.Sos
    }
    // Пуш о ходе такси-заказа (B9b-2): тап по «Водитель найден / Машина на месте / …» →
    // экран заказа пассажира (сам подхватывает активный заказ). Ждём, пока сплэш отработает.
    // Force-update (B9b-1): при старте ПАРАЛЛЕЛЬНО обычному запуску спрашиваем /version/min.
    // versionCode < min с сервера → блокирующий экран «Обнови Юлдаш» (ниже, поверх всего).
    // Офлайн / ошибка ручки / min=0 → НИЧЕГО не блокируем, приложение стартует как обычно.
    var forceUpdateRequired by rememberSaveable(saver = rootNavigationStateSaver<Boolean>("forceUpdateRequired")) { mutableStateOf(false) }
    var forceUpdateStoreUrl by rememberSaveable(saver = rootNavigationStateSaver<String>("forceUpdateStoreUrl")) { mutableStateOf("") }
    // Мягкое обновление (B9b-1b): версия ещё поддерживается, но вышла свежее → плашка сверху.
    // Списки «что нового» держим строкой через \n, а не List: rememberSaveable переживает
    // поворот экрана только для простых типов, иначе плашка теряла бы содержимое.
    var updateLatestCode by rememberSaveable(saver = rootNavigationStateSaver<Int>("updateLatestCode")) { mutableIntStateOf(0) }
    var updateVersionName by rememberSaveable(saver = rootNavigationStateSaver<String>("updateVersionName")) { mutableStateOf("") }
    var updateStoreUrl by rememberSaveable(saver = rootNavigationStateSaver<String>("updateStoreUrl")) { mutableStateOf("") }
    var updateWhatsNewRu by rememberSaveable(saver = rootNavigationStateSaver<String>("updateWhatsNewRu")) { mutableStateOf("") }
    var updateWhatsNewBa by rememberSaveable(saver = rootNavigationStateSaver<String>("updateWhatsNewBa")) { mutableStateOf("") }
    // Какую версию человек уже отклонил. Живёт в настройках устройства, а не в памяти:
    // иначе плашка возвращалась бы при каждом запуске, и «позже» ничего не значило.
    var updateDismissedCode by remember { mutableIntStateOf(prefs.getInt(PREF_UPDATE_DISMISSED, 0)) }
    // Срочная комиссия за дальнюю поездку: спрашиваем на входе, а не только в кабинете.
    // Иначе водитель, открывший приложение и не заглянувший в профиль, считался бы
    // предупреждённым (сервер судит по «заходил ли»), ничего при этом не увидев.
    // Не водитель или ошибка сети — точка просто не горит, экран из-за этого не страдает.
    LaunchedEffect(screen) {
        if (!ApiClient.isLoggedIn()) { NavSignals.payNowDebtKop.value = 0; return@LaunchedEffect }
        if (screen != Screen.Home) return@LaunchedEffect
        ApiClient.getDriverDebt().onSuccess { NavSignals.payNowDebtKop.value = it.payNowKop }
    }
    LaunchedEffect(Unit) {
        ApiClient.minAppVersion().onSuccess { o ->
            val min = o.optInt("min_version_code", 0)
            if (min > 0 && BuildConfig.VERSION_CODE < min) {
                forceUpdateStoreUrl = o.optString("store_url", "")
                forceUpdateRequired = true
                return@onSuccess   // блокирующий экран старше плашки — вместе они бессмысленны
            }
            val latest = o.optInt("latest_version_code", 0)
            val store = o.optString("store_url", "")
            // Без ссылки плашку не показываем: звать обновиться и никуда не вести — хуже, чем молчать.
            if (latest > BuildConfig.VERSION_CODE && store.isNotBlank()) {
                updateStoreUrl = store
                updateVersionName = o.optString("latest_version_name", "")
                o.optJSONObject("whats_new")?.let { wn ->
                    updateWhatsNewRu = jsonArrayToLines(wn.optJSONArray("ru"))
                    updateWhatsNewBa = jsonArrayToLines(wn.optJSONArray("ba"))
                }
                updateLatestCode = latest
            }
        }
    }
    val notifPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var notifAsked by rememberSaveable(saver = rootNavigationStateSaver<Boolean>("notifAsked")) { mutableStateOf(false) }
    LaunchedEffect(screen) {
        if (!notifAsked && (screen == Screen.Home || screen == Screen.DriverCabinet) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifAsked = true
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    var failedRideLink by rememberSaveable(screenStateOwner, saver = rootNavigationStateSaver<Int?>("failedRideLink", screenStateOwner)) { mutableStateOf<Int?>(null) }
    var rideLinkUnavailable by rememberSaveable(screenStateOwner, saver = rootNavigationStateSaver<Boolean>("rideLinkUnavailable", screenStateOwner)) { mutableStateOf(false) }
    // null: публичная поездка; false: чат брони; true: завершённая бронь.
    var failedBookingDestination by rememberSaveable(screenStateOwner, saver = rootNavigationStateSaver<Boolean?>("failedBookingDestination", screenStateOwner)) { mutableStateOf<Boolean?>(null) }
    var failedCompletedOwner by rememberSaveable(screenStateOwner, saver = rootNavigationStateSaver<Int?>("failedCompletedOwner", screenStateOwner)) { mutableStateOf<Int?>(null) }
    var failedCompletedRevision by rememberSaveable(screenStateOwner, saver = rootNavigationStateSaver<Long?>("failedCompletedRevision", screenStateOwner)) { mutableStateOf<Long?>(null) }
    var bookingLinkAttempt by remember { mutableIntStateOf(0) }
    // Отличает два запроса того же public id: старый callback не управляет новой ошибкой.
    var publicLinkAttempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(vm.privateNavigationRevision) {
        if (failedRideLink != null && failedCompletedRevision != null && failedCompletedRevision != vm.privateNavigationRevision) {
            failedRideLink = null
            failedBookingDestination = null
        }
    }
    // Старые внутренние producer/test bridge тоже принимаются, но HTTP читает durable record.
    LaunchedEffect(DeepLink.pendingCompletedBookingId.value, DeepLink.pendingBookingChatId.value, navigationSession) {
        val owner = ApiClient.myUserId()
        val previous = vm.pendingBookingNavigation.value
        val completedBridge = DeepLink.pendingCompletedBookingId.value
        val chatBridge = DeepLink.pendingBookingChatId.value
        vm.pendingBookingForOwner(owner)
        if (failedBookingDestination != null && failedCompletedOwner != null && failedCompletedOwner != owner) {
            failedRideLink = null
        }
        if (previous?.ownerId == null || previous.ownerId == owner) {
            if (completedBridge != null && (previous?.bookingId != completedBridge || !previous.completed)) {
                vm.requestCompletedBooking(completedBridge, owner)
            } else if (chatBridge != null && (previous?.bookingId != chatBridge || previous.completed)) {
                vm.requestBookingDestination(chatBridge, owner, completed = false)
            }
        }
    }
    // Публичная ссылка: ошибка не должна молча терять назначение, повтор использует тот же id.
    LaunchedEffect(DeepLink.pendingRideId.value, screen, vm.privateNavigationRevision, navigationSession) {
        val rideId = DeepLink.pendingRideId.value ?: return@LaunchedEffect
        if (screen == Screen.Splash || screen == Screen.Intro || screen == Screen.Onboarding) return@LaunchedEffect
        val requestedScreen = screen
        val requestedRevision = vm.privateNavigationRevision
        val requestedSession = ApiClient.queueSessionGeneration()
        val requestedAttempt = ++publicLinkAttempt
        failedRideLink = null
        failedBookingDestination = null
        val result = ApiClient.getRide(rideId)
        // Не менять ключ LaunchedEffect до окончания запроса: это отменяло саму загрузку.
        ApiClient.runIfUnchangedSession(requestedSession) {
            if (!isActive || DeepLink.pendingRideId.value != rideId || vm.screen.value != requestedScreen ||
                vm.privateNavigationRevision != requestedRevision || publicLinkAttempt != requestedAttempt) return@runIfUnchangedSession
            DeepLink.pendingRideId.value = null
            result.onSuccess { dto ->
                vm.navigateLocally {
                    selectedRide = dto.toUiRide()
                    activeBookingId = null
                    selectedBookingStatus = ""
                    screen = Screen.Booking
                }
            }.onFailure { error ->
                rideLinkUnavailable = (error as? ApiException)?.status in listOf(403, 404, 410)
                failedBookingDestination = null
                failedCompletedRevision = requestedRevision
                failedRideLink = rideId
            }
        }
    }
    failedRideLink?.takeIf {
        DeepLink.pendingRideId.value == null && DeepLink.pendingBookingChatId.value == null &&
            DeepLink.pendingCompletedBookingId.value == null
    }?.let { rideId ->
        val failedKind = failedBookingDestination
        val failedOwner = failedCompletedOwner
        val failedRevision = if (failedKind == null) failedCompletedRevision ?: vm.privateNavigationRevision else failedCompletedRevision
        val failedSession = ApiClient.queueSessionGeneration()
        val failedScreen = screen
        val failedPublicAttempt = publicLinkAttempt
        fun isDisplayedFailureCurrent() = appScope.isActive && failedRideLink == rideId && failedBookingDestination == failedKind &&
            vm.screen.value == failedScreen && (if (failedKind == null)
                vm.privateNavigationRevision == failedRevision && publicLinkAttempt == failedPublicAttempt
            else failedCompletedRevision == failedRevision)
        fun closeDisplayedFailure() {
            ApiClient.runIfUnchangedSession(failedSession) {
                if (isDisplayedFailureCurrent()) failedRideLink = null
            }
        }
        val tagPrefix = if (failedKind == null) "rideLink" else "bookingLink"
        AlertDialog(
            modifier = Modifier.testTag("${tagPrefix}Failure"),
            onDismissRequest = { closeDisplayedFailure() },
            shape = CanonCardShape,
            containerColor = CanonSurface,
            titleContentColor = CanonText,
            textContentColor = CanonMuted,
            title = { Text(if (rideLinkUnavailable)
                appText("Поездка недоступна", "Сәфәрҙе асып булмай")
            else appText("Не получилось открыть поездку", "Сәфәрҙе асып булманы")) },
            text = { Text(if (rideLinkUnavailable)
                appText("Поездка удалена или доступ к ней закрыт.", "Сәфәр юйылған йәки уға инеү рөхсәте юҡ.")
            else appText("Проверь сеть и попробуй снова.", "Селтәрҙе тикшереп ҡабатла.")) },
            confirmButton = {
                if (rideLinkUnavailable) {
                    TextButton(modifier = Modifier.testTag("${tagPrefix}Close"), onClick = { closeDisplayedFailure() }) {
                        Text(appText("Закрыть", "Ябыу"), color = CanonGreen2)
                    }
                } else {
                    TextButton(modifier = Modifier.testTag("${tagPrefix}Retry"), onClick = retry@{
                        if (failedRideLink != rideId || failedBookingDestination != failedKind ||
                            (failedKind != null && failedCompletedRevision != failedRevision)) return@retry
                        if (failedKind != null) {
                            val currentSession = ApiClient.queueSessionGeneration()
                            ApiClient.runIfCurrentSession(currentSession) {
                                // Старый callback не привязывает поездку к другому аккаунту.
                                // Refresh того же владельца допускает осознанный ручной повтор.
                                if (failedOwner != null && ApiClient.myUserId() != failedOwner) return@runIfCurrentSession
                                if (failedOwner == null && currentSession != failedSession) return@runIfCurrentSession
                                if (vm.privateNavigationRevision != failedRevision || vm.pendingScreenNavigation.value != null) return@runIfCurrentSession
                                if (vm.pendingBookingNavigation.value == null) vm.requestBookingDestination(rideId, failedOwner, failedKind)
                                failedRideLink = null
                            }
                            return@retry
                        }
                        ApiClient.runIfUnchangedSession(failedSession) {
                            if (!isDisplayedFailureCurrent() || DeepLink.pendingRideId.value != null ||
                                vm.pendingBookingNavigation.value != null || vm.pendingScreenNavigation.value != null) return@runIfUnchangedSession
                            publicLinkAttempt++
                            DeepLink.pendingRideId.value = rideId
                            failedRideLink = null
                        }
                    }) { Text(appText("Повторить", "Ҡабатлау"), color = CanonGreen2) }
                }
            },
            dismissButton = {
                if (!rideLinkUnavailable) {
                    TextButton(modifier = Modifier.testTag("${tagPrefix}Close"), onClick = { closeDisplayedFailure() }) {
                        Text(appText("Закрыть", "Ябыу"), color = CanonMuted)
                    }
                }
            },
        )
    }
    // Кнопка «Написать» из карточки посылки → чат с второй стороной доставки.
    LaunchedEffect(DeepLink.pendingParcelChat.value) {
        val target = DeepLink.pendingParcelChat.value ?: return@LaunchedEffect
        DeepLink.pendingParcelChat.value = null   // одноразово — не переоткрываем при рекомпозиции
        parcelChatId = target.parcelId
        parcelChatPeerIsCourier = target.peerIsCourier
        parcelChatStatus = target.status
        screen = Screen.ParcelChat
    }
    // Личное назначение сохраняем до входа. Не устанавливаем бронь до проверки участника.
    listOf(false, true).forEach { completed ->
        val pending = if (completed) DeepLink.pendingCompletedBookingId else DeepLink.pendingBookingChatId
        val revision = vm.pendingBookingNavigation.value?.takeIf { it.completed == completed }?.revision
        LaunchedEffect(pending.value, revision, screen, bookingLinkAttempt, navigationSession) {
            val destination = vm.pendingBookingForOwner(ApiClient.myUserId())?.takeIf { it.completed == completed } ?: return@LaunchedEffect
            if (destination.revision != revision) return@LaunchedEffect
            val bid = destination.bookingId
            if (screen == Screen.Splash || screen == Screen.Intro || screen == Screen.Onboarding) return@LaunchedEffect
            failedRideLink = null
            val sessionToken = ApiClient.currentToken()
            val requestedSession = navigationSession
            if (!ApiClient.isLoggedIn()) { screen = Screen.Login; return@LaunchedEffect }
            val result = ApiClient.getTripState(bid)
            if (!isActive || pending.value != bid) return@LaunchedEffect
            if (vm.pendingBookingNavigation.value != destination || !ApiClient.isCurrentSession(requestedSession)) return@LaunchedEffect
            // В том числе после обновления токена: новый запрос проверит текущую сессию.
            if (sessionToken != ApiClient.currentToken()) {
                if (ApiClient.isLoggedIn()) bookingLinkAttempt++ else screen = Screen.Login
                return@LaunchedEffect
            }
            val state = result.getOrNull()
            val applyResult = {
                if (state != null && state.role in listOf("driver", "passenger")) {
                    selectedRide = Ride(id = bid.toString(), from = "", to = "", time = "", driver = "", car = "",
                        price = 0, seats = 1, rating = 0.0, verified = false, boosted = false)
                    activeBookingId = bid
                    selectedBookingStatus = state.status
                    if (completed) activeTrip = null
                    screen = if (completed) Screen.ActiveTrip else Screen.Booking
                } else {
                    rideLinkUnavailable = (result.exceptionOrNull() as? ApiException)?.status in listOf(403, 404, 410)
                    failedBookingDestination = completed
                    failedCompletedOwner = destination.ownerId
                    failedCompletedRevision = destination.revision
                    failedRideLink = bid
                }
            }
            ApiClient.runIfCurrentSession(requestedSession) { vm.consumePendingBooking(destination, applyResult) }
        }
    }
    // Реклама — сервер-управляемая (/ads); демо-шаблон даёт оформление, демо-список — фоллбэк.
    var partnerAds by vm.partnerAds
    // Объявление, открытое в редакторе кабинета партнёра (null = создание нового).
    var adEditorTarget by remember { mutableStateOf<com.yuldash.app.data.MyAdDto?>(null) }
    LaunchedEffect(Unit) {
        ApiClient.getAds().onSuccess { srv ->
            // Сервер ОТВЕТИЛ (пусть даже пусто) → показываем именно его данные, а не демо.
            // Пустой ответ = нет рекламы, а не «оставить демо-аптеку с фейк-номером» (иначе при
            // живом пустом сервере кнопка «Позвонить» набирала бы демо +7 927 000-12-12).
            // Демо остаётся только как офлайн-фолбэк (стартовое значение vm.partnerAds при сбое сети).
            val tmpl = demoPartnerAds.firstOrNull()
            partnerAds = if (srv.isEmpty() || tmpl == null) emptyList() else srv.map { a ->
                // Шаблон даёт ТОЛЬКО оформление (иконка/места/категория). Все данные партнёра — с сервера.
                // КРИТИЧНО: contact/mapPoint/имя/адрес НЕ наследуем от демо (иначе клик звонил на демо-номер).
                tmpl.copy(
                    id = a.id, title = a.title, titleBa = a.title,
                    // Места показа — С СЕРВЕРА, а не от демо-шаблона (аудит 2026-08-07).
                    // Раньше их наследовали от демо, и партнёр, купивший пакет «Маршрут»
                    // (места route + ridesList), показывался в Nearby/Profile/Help/TripDetails —
                    // то есть ровно там, за что НЕ платил, и не показывался там, за что платил.
                    // Пустой список = показывать негде: лучше не показать, чем показать не то,
                    // за что человек отдал деньги.
                    placements = a.placements.mapNotNull(::adPlacementOf).toSet(),
                    description = a.text, descriptionBa = a.text, erid = a.erid,
                    advertiserName = a.partner.ifBlank { a.title },
                    city = a.city.ifBlank { tmpl.city },
                    address = a.city,                 // у сервера нет уличного адреса → город (а не демо-адрес)
                    contact = a.contact,              // реальный телефон партнёра ("" если не задан → клик не наберёт чужой номер)
                    mapPoint = "",                    // у сервера нет координат → пусто (а не демо-точка)
                    linkUrl = a.target,               // ссылка партнёра → клик откроет её
                    primaryButton = a.button, primaryButtonBa = a.button,
                )
            }
        }
    }
    val adStats = vm.adStats   // SnapshotStateMap: мутируем одну запись вместо копии всей карты на событие
    // Сохраняем survival-состояние в SavedStateHandle при изменении → переживает смерть процесса.
    // activeBookingId в ключах: смена активной брони тоже должна попасть в handle (H1).
    LaunchedEffect(screen, language, startHomeTab, activeBookingId, languagePersistenceReady, vm.navHistory.toList()) {
        if (languagePersistenceReady) vm.persistNav()
    }

    // Лёгкий back-stack: трейл экранов, чтобы аппаратная «Назад» возвращалась по нему, а не прыгала на Home.
    val navHistory = vm.navHistory
    var navPopping by vm.navPopping
    LaunchedEffect(screen) {
        vm.recordNavigationChange()
    }

    // SavedStateHandle хранит навигацию, но не объекты Ride. При входе/возврате на бронь
    // дочитываем её по сохранённому id. Ответ применяется только к исходному экрану, брони
    // и сессии; уход со страницы отменяет загрузчик. Live-гео включается лишь для активной поездки.
    // Не нашлась текущая бронь / нет сети → Home (прежнее поведение).
    LaunchedEffect(screen, activeBookingId, navigationSession) {
        if ((screen == Screen.Booking || screen == Screen.ActiveTrip) && selectedRide == null && activeTrip == null) {
            val bid = activeBookingId
            val requestedScreen = screen
            val requestedSession = navigationSession
            val restoreScope = this
            fun isCurrentRestore() = restoreScope.isActive && screen == requestedScreen &&
                activeBookingId == bid && selectedRide == null && activeTrip == null &&
                ApiClient.isCurrentSession(requestedSession)
            var restored = false
            if (bid != null) {
                val list = ApiClient.getMyBookingsDetailed().getOrNull()
                if (!isCurrentRestore()) return@LaunchedEffect
                val b = list?.firstOrNull { it.id == bid }
                if (b != null) {
                    // Сводку с сервера дополняем feed-поездкой по ride_id (как экран «Мои поездки»).
                    val feed = rides.firstOrNull { it.id == b.rideId.toString() }
                    val restoredRide = Ride(
                        id = b.id.toString(),
                        from = b.fromCity.ifBlank { feed?.from ?: "" },
                        to = b.toCity.ifBlank { feed?.to ?: "" },
                        time = b.departAt.takeIf { it.isNotBlank() }?.let(::formatDepart) ?: (feed?.time ?: ""),
                        timeBa = b.departAt.takeIf { it.isNotBlank() }?.let(::formatDepart) ?: (feed?.timeBa ?: feed?.time ?: ""),
                        driver = b.driverName.ifBlank { feed?.driver ?: "" },
                        car = feed?.car ?: "",
                        carBa = feed?.carBa ?: feed?.car ?: "",
                        price = if (b.price > 0) b.price else (feed?.price ?: 0),
                        seats = b.seats,
                        rating = feed?.rating ?: 0.0,
                        verified = b.driverVerified || (feed?.verified ?: false),
                        boosted = false,
                    )
                    selectedRide = restoredRide
                    selectedBookingStatus = b.status
                    // Live-гео и карта гейтятся на activeTrip: ставим его только для активной поездки.
                    activeTrip = if (bookingStatusAllowsBoarding(b.status)) restoredRide else null
                    // Отменённая бронь → детали; done остаётся на экране завершения и оценки.
                    if (screen == Screen.ActiveTrip && !bookingStatusAllowsActiveTrip(b.status)) screen = Screen.Booking
                    restored = true
                }
            }
            if (!restored && isCurrentRestore()) screen = Screen.Home
        }
    }

    // M4 — разгружаем очередь исходящих (TripPass Outbox) на СТАРТЕ приложения, а не только на экране
    // поездки: накопленные «сел/доехал»/сообщения уйдут, даже если пользователь не открывал ActiveTrip.
    // Переиспользуем ту же Outbox.flush (Mutex/FIFO) — второго параллельного отправителя не создаём.
    LaunchedEffect(Unit) {
        if (com.yuldash.app.data.Outbox.hasPending(context)) com.yuldash.app.data.Outbox.flush(context)
    }

    fun finishOnboarding(role: RideRole) {
        // Сохраняем выбор роли (раньше выбор был «мёртвым» — никуда не уходил).
        prefs.edit().putBoolean("onboarding_completed", true).putString("preferred_role", role.name).apply()
        Analytics.log("onboarding_complete", mapOf("role" to role.name))   // воронка: дошёл до конца онбординга
        startHomeTab = if (role == RideRole.Driver) HomeTab.Rides else HomeTab.Map
        screen = Screen.Login
    }

    fun openSos(orderId: Int = 0, bookingId: Int = 0, note: String = "") {
        sosOrderId = orderId       // контекст такси-заказа (0 = обычный SOS) — не даём протечь старому
        sosBookingId = bookingId   // контекст попутки (0 = обычный SOS)
        sosContextNote = note      // подпись дежурному (курьер: маршрут доставки)
        screen = Screen.Sos
    }
    fun openHome(tab: HomeTab = HomeTab.Map) {
        navHistory.clear()          // Home = корень: сбрасываем трейл, чтобы «Назад» не возвращал в завершённые под-потоки
        navPopping = true           // сам переход-на-Home в историю не пишем
        startHomeTab = tab
        screen = Screen.Home
    }
    fun openProtectedScreen(destination: Screen) {
        if (ApiClient.isLoggedIn()) screen = destination
        else {
            destinationAfterLogin = destination
            screen = Screen.Login
        }
    }
    // Единый пошаговый «Назад» (верхняя стрелка И аппаратная кнопка): снимаем последний экран трейла.
    // Дошли до Home / трейл пуст → Home на ПОСЛЕДНЕЙ вкладке (startHomeTab синхронён с активной вкладкой Home).
    fun goBack() {
        val prev = navHistory.removeLastOrNull()
        if (prev != null && prev != screen && prev != Screen.Home) { navPopping = true; screen = prev }
        else openHome(startHomeTab)
    }
    fun openCreateRide(returnScreen: Screen = Screen.Home, returnHomeTab: HomeTab = HomeTab.Request, prefillDate: String? = null) {
        createRideReturnScreen = returnScreen
        createRideReturnHomeTab = returnHomeTab
        createRidePrefillDate = prefillDate   // null для обычного создания → форма как раньше
        screen = Screen.CreateRide
    }
    fun closeCreateRide() {
        if (createRideReturnScreen == Screen.Home) openHome(createRideReturnHomeTab) else screen = createRideReturnScreen
    }
    fun openTrustedContacts(returnScreen: Screen = Screen.SimpleMode, returnHomeTab: HomeTab = HomeTab.Profile) {
        trustedContactsReturnScreen = returnScreen
        trustedContactsReturnHomeTab = returnHomeTab
        openProtectedScreen(Screen.TrustedContacts)
    }
    fun closeTrustedContacts() {
        navHistory.removeLastOrNull()
        navPopping = true
        if (trustedContactsReturnScreen == Screen.Home) openHome(trustedContactsReturnHomeTab) else screen = trustedContactsReturnScreen
    }

    // Один показ на объявление за сессию: карточка в LazyColumn пересоздаётся при скролле
    // (item ушёл за экран и вернулся) → LaunchedEffect(ad.id) срабатывал повторно и накручивал статистику.
    val countedImpressions = remember { mutableSetOf<String>() }
    fun trackAdImpression(ad: PartnerAd) {
        if (!countedImpressions.add(ad.id)) return   // уже засчитали → не шлём повторный impression при скролле
        val current = adStats[ad.id] ?: AdStats()
        adStats[ad.id] = current.copy(impressions = current.impressions + 1)
        ApiClient.fireAdEvent(ad.id, "impression")   // реальный показ на сервер
    }

    fun trackAdClick(ad: PartnerAd) {
        val current = adStats[ad.id] ?: AdStats()
        adStats[ad.id] = current.copy(clicks = current.clicks + 1)
        ApiClient.fireAdEvent(ad.id, "click")        // реальный клик на сервер (только учёт)
        // Само действие выполняет карточка по конкретной кнопке: «Открыть» → сайт/звонок (openAdTarget),
        // «Маршрут» → дорога до партнёра (routeToAd). Так две кнопки ведут в разные места, а не в одно.
    }

    // Поездки — с сервера. Стартуем с демо (мгновенно), при ответе заменяем на серверные.
    // Сервер недоступен (ТСПУ/офлайн) → остаются демо, экран не пустеет.
    LaunchedEffect(Unit) {
        ApiClient.getRides().onSuccess { dtos ->
            // Сервер ОТВЕТИЛ → показываем ровно его список. Пусто = пусто (честный empty-state),
            // а не «оставить демо с числовыми id 1..3», которые book() принял бы за реальные
            // серверные поездки и создал бронь на чужую поездку id=1.
            rides.clear()
            rides.addAll(
                dtos.map { d ->
                        Ride(
                            id = d.id.toString(),
                            from = d.fromCity,
                            to = d.toCity,
                            time = formatDepart(d.departAt),
                            driver = d.driverName,
                            driverAvatar = d.driverAvatar,
                            driverOnline = d.driverOnline,
                            driverIsWoman = d.driverIsWoman,
                            car = d.driverCar,
                            price = d.price,
                            seats = d.seatsLeft,
                            rating = d.driverRating,
                            verified = d.driverVerified,
                            boosted = d.boosted,                 // было хардкод false → Boost не подсвечивался
                            petsAllowed = d.petsAllowed,
                            childSeat = d.childSeat,
                            womenOnly = d.womenOnly,
                            smoking = d.smoking,
                            baggage = d.baggage,
                            airConditioner = d.airConditioner,
                            quiet = d.quiet,
                            noMinors = d.noMinors,
                            waypoints = d.waypoints,
                            pickup = d.pickup,
                            pickupLat = d.pickupLat,
                            pickupLng = d.pickupLng,
                        )
                    }
                )
        }
    }
    // Открыть публичный профиль водителя из любой карточки поездки (без протаскивания колбэков).
    // Трейл «Назад» ведётся авто-эффектом LaunchedEffect(screen) — ручной push не нужен.
    val openDriverProfile: (Int) -> Unit = { id ->
        if (id > 0) { driverProfileId = id; screen = Screen.DriverProfile }
    }
    // Синхронизируем язык сообщений об ошибке в слое данных (ApiClient — не Composable,
    // appText недоступен). Иначе башкир видел бы серверные/клиентские ошибки по-русски.
    LaunchedEffect(language) { ApiClient.setUiLanguageBashkir(language == AppLanguage.Ba) }
    // Не монтируем экран с утраченным root-контекстом: его загрузчик успел бы отправить GET /0.
    fun missingRootDestinations(): Set<Screen> = buildSet {
        if (receiptBookingId <= 0) add(Screen.TripReceipt)
        if (taxiReceiptOrderId <= 0) add(Screen.TaxiReceipt)
        if (parcelChatId <= 0) add(Screen.ParcelChat)
        if (instantTripOrderId <= 0) add(Screen.InstantDriverTrip)
        if (driverProfileId <= 0) add(Screen.DriverProfile)
        if (carPhotoMode !in setOf("taxi", "courier")) add(Screen.CarPhoto)
    }
    val unavailableRootDestinations = missingRootDestinations()
    val rootDestination = screen
    val rootRevision = vm.privateNavigationRevision
    val missingRootDestination = rootDestination in unavailableRootDestinations
    LaunchedEffect(rootDestination, unavailableRootDestinations, rootRevision, navigationSession) {
        if (missingRootDestination && vm.screen.value == rootDestination && vm.privateNavigationRevision == rootRevision) {
            ApiClient.runIfUnchangedSession(navigationSession) {
                val currentMissing = missingRootDestinations()
                if (rootDestination in currentMissing && vm.screen.value == rootDestination && vm.privateNavigationRevision == rootRevision)
                    vm.recoverMissingRootDestination(rootDestination, currentMissing)
            }
        }
    }
    CompositionLocalProvider(LocalAppLanguage provides language, LocalOpenDriverProfile provides openDriverProfile) {
        // Force-update (B9b-1): версия ниже минимальной → блокирующий экран вместо всего приложения.
        // Не экран навигации (enum Screen) намеренно: из него нельзя выйти «Назад» — только обновиться.
        if (forceUpdateRequired) {
            ForceUpdateScreen(storeUrl = forceUpdateStoreUrl)
            return@CompositionLocalProvider
        }
        // Мои заявки — с сервера (после входа). Точное время в Фазе 1 не храним.
        val reqWaitingStatus = appText("ждём отклики", "яуаптар көтәбеҙ")
        val reqByAgreement = appText("по договорённости", "килешеү буйынса")
        val passengerSelf = appText("Я", "Мин")   // BA-draft: «Мин» — на проверку носителю
        LaunchedEffect(sessionVersion, requestsReload) {
            requestsLoading = true
            requestsError = false
            ApiClient.getMyRequests().onFailure { requestsError = true }.onSuccess { reqs ->
                localRequests.clear()
                localRequests.addAll(
                    // Только активные: отменённые (cancelled) и принятые (matched — уже в «Поездках») здесь не показываем,
                    // иначе отменённая висела бы с ложным «ждём отклики».
                    reqs.filter { it.status == "active" }.map { r ->
                        LocalRequest(
                            title = apiCategoryToUiFor(language, r.category, r.withKids),
                            route = "${r.fromCity} → ${r.toCity}",
                            // Показываем выбранное время (если пассажир его задал), иначе «по договорённости».
                            time = r.desiredAt?.takeIf { it.isNotBlank() }?.let(::formatDepart) ?: reqByAgreement,
                            passenger = r.forRelativeName ?: ApiClient.cachedName() ?: passengerSelf,
                            status = reqWaitingStatus,
                            price = r.maxPrice,
                            trustedContact = r.comment.ifBlank { null },
                            serverId = r.id,
                        )
                    }
                )
            }
            requestsLoading = false
        }
        // Доверенные контакты — с сервера (после входа). Перечитываем и при смене sessionVersion (после логина).
        LaunchedEffect(sessionVersion) {
            ApiClient.getContacts().onSuccess { list ->
                // Чистим БЕЗУСЛОВНО: у нового вошедшего (после logout на общем устройстве) может быть
                // 0 контактов — тогда без clear() остались бы видны контакты (имена+телефоны) прошлого юзера.
                trustedContacts.clear()
                trustedContacts.addAll(list.map { c -> TrustedContact(c.name, c.relation, c.phone, c.notifyByDefault, c.id) })
            }
        }
        BackHandler(enabled = screen != Screen.Onboarding && screen != Screen.Login && screen != Screen.Home && screen != Screen.Splash && screen != Screen.Intro) {
            if (screen == Screen.TrustedContacts) closeTrustedContacts() else goBack()
        }
        // Плашка «нет связи с сервером» — одна на всё приложение, ВНУТРИ провайдера языка
        // (иначе надпись выходила по-русски в башкирском режиме) и В ПОТОКЕ, а не поверх:
        // накладка закрывала заголовок экрана. Причина плашки — ApiClient.serverUnreachable:
        // 84 места читают ответ через .onSuccess без .onFailure, и при обрыве связи экран
        // молча показывал «Заявок пока нет» вместо правды. Подробности — в UiKit.ConnectionBanner.
        // background(CanonBg): без него за плашкой просвечивал зелёный фон окна
        // (он остаётся от системного сплэша) — над экраном висела зелёная полоса.
        val offlineNow by ApiClient.serverUnreachable.collectAsState()
        // Плашка «вышла новая версия» (B9b-1b). На сплэше, интро, онбординге и входе не зовём:
        // человек ещё не в приложении, и предложение обновиться там читается как сбой.
        // Закрытую версию не показываем повторно — см. UpdateBanner.
        val updateVisible = updateLatestCode > 0 && updateLatestCode > updateDismissedCode &&
            screen != Screen.Splash && screen != Screen.Intro &&
            screen != Screen.Onboarding && screen != Screen.Login
        Column(Modifier.fillMaxSize().background(CanonBg)) {
        ConnectionBanner(Modifier.align(Alignment.CenterHorizontally))
        // Отступ под статус-бар даёт ПЕРВЫЙ видимый элемент сверху: если висит «нет связи» —
        // он уже её забота, и второй превратится в полосу пустоты (урок 2026-08-04).
        UpdateBanner(
            visible = updateVisible,
            versionName = updateVersionName,
            whatsNewRu = linesToList(updateWhatsNewRu),
            whatsNewBa = linesToList(updateWhatsNewBa),
            storeUrl = updateStoreUrl,
            ownsStatusBar = !offlineNow,
            onLater = {
                updateDismissedCode = updateLatestCode
                prefs.edit().putInt(PREF_UPDATE_DISMISSED, updateLatestCode).apply()
            },
        )
        // consumeWindowInsets только когда плашка ВИДНА: отступ под статус-бар уже отдала она,
        // и без гашения экран добавлял его вторым — над содержимым висела полоса пустоты.
        // Когда плашки нет, она занимает ноль высоты и отступ должен давать сам экран.
        Box(Modifier.weight(1f).then(
            if (offlineNow || updateVisible) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier
        )) {
        AnimatedContent(
            targetState = if (missingRootDestination) Screen.Notifications else screen,
            transitionSpec = {
                (fadeIn(animationSpec = tween(CanonMotion.NORMAL)) +
                    slideInHorizontally(animationSpec = tween(CanonMotion.SLOW)) { it / 12 })
                    .togetherWith(
                        fadeOut(animationSpec = tween(CanonMotion.QUICK)) +
                            slideOutHorizontally(animationSpec = tween(CanonMotion.SLOW)) { -it / 12 }
                    )
            },
            label = "screen"
        ) { scr ->
        if (scr in unavailableRootDestinations) return@AnimatedContent
        // Состояние экрана переживает уход с него: ушёл в «Способы оплаты» и вернулся —
        // маршрут, цена и введённый текст на месте. Обычный `when` уничтожает композицию
        // ушедшего экрана вместе с его состоянием, и человек вводил адрес заново.
        screenStates.SaveableStateProvider("$screenStateOwner:${scr.name}") {
        when (scr) {
            // Кабинет админа — двадцать один экран, и все просят одно и то же:
            // «назад» и «перейти». Держать их здесь значило раздувать выбор экрана до
            // размера, который Android отказывается ускорять (см. AppNavAdmin.kt).
            Screen.AdminCabinet,
            Screen.AdminDrivers,
            Screen.AdminReports,
            Screen.AdminTextFlags,
            Screen.AdminSupport,
            Screen.AdminPaymentRequests,
            Screen.AdminRequest,
            Screen.AdminResponses,
            Screen.AdminTaxi,
            Screen.AdminWaitlist,
            Screen.AdminTaxiPulse,
            Screen.AdminSos,
            Screen.AdminIncidents,
            Screen.AdminRatings,
            Screen.AdminReviews,
            Screen.AdminAds,
            Screen.AdminPartners,
            Screen.AdminModeration,
            Screen.AdminPromo,
            Screen.AdminParcels,
            Screen.AdminCourier -> AdminNav(
                screen = scr,
                onBack = { goBack() },
                onOpen = { screen = it },
            )
            Screen.InstantOrder,
            Screen.InstantDriverTrip,
            Screen.InstantChat,
            Screen.TaxiOnboarding,
            Screen.TaxiReceipt,
            Screen.DriverTaxiRides,
            Screen.MyTaxiTrips,
            Screen.ParcelChat,
            Screen.TaxiDocuments,
            Screen.CarPhoto,
            Screen.PretripCheck,
            Screen.CourierEarnings,
            Screen.Parcels,
            Screen.CourierOnboarding,
            Screen.Courier -> OrdersNav(
                screen = scr,
                instantTripOrderId = instantTripOrderId,
                instantChatOrderId = instantChatOrderId,
                taxiReceiptOrderId = taxiReceiptOrderId,
                carPhotoMode = carPhotoMode,
                parcelChatId = parcelChatId,
                parcelChatPeerIsCourier = parcelChatPeerIsCourier,
                parcelChatStatus = parcelChatStatus,
                onBack = { goBack() },
                onOpen = { screen = it },
                onOpenTaxiChat = { id -> instantChatOrderId = id; screen = Screen.InstantChat },
                onOpenReceipt = { id -> taxiReceiptOrderId = id; screen = Screen.TaxiReceipt },
                onOpenCarPhoto = { mode -> carPhotoMode = mode; screen = Screen.CarPhoto },
            )
            Screen.Splash -> {
                // Зелёный «мост» — продолжение СИСТЕМНОГО сплэша, БЕЗ повторной анимации лого.
                // Это убирает «дубль» (раньше Compose-сплэш заново анимировал то же лого поверх системного).
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(CanonGreenInk, CanonGreenInkDark))))
                // Первый запуск → брендовое интро; повтор → быстро в приложение (без второго лого).
                LaunchedEffect(Unit) { delay(if (splashTarget == Screen.Intro) 60 else 140); screen = splashTarget }
            }
            Screen.Intro -> IntroScreen(onComplete = { screen = Screen.Onboarding })
            Screen.Onboarding -> OnboardingScreen(
                onFinish = ::finishOnboarding,
                language = language,
                onSelectLanguage = { language = it },
                onSimpleMode = {
                    // Онбординг пройден + запоминаем выбор простого режима (те же prefs, без новых сущностей).
                    prefs.edit()
                        .putBoolean("onboarding_completed", true)
                        .putBoolean("simple_mode_opted_in", true)
                        .apply()
                    Analytics.log("onboarding_simple_mode")
                    screen = Screen.SimpleMode
                }
            )
            Screen.Login -> {
                LoginScreen(
                    currentLanguage = language,
                    onToggleLanguage = {
                        language = if (language == AppLanguage.Ru) AppLanguage.Ba else AppLanguage.Ru
                    },
                    onContinue = {
                        sessionVersion++   // вход завершён → перечитать роль/мои заявки/контакты под новым токеном
                        val destination = destinationAfterLogin
                        destinationAfterLogin = null
                        if (destination != null) {
                            if (destination == Screen.Home) openHome(startHomeTab) else screen = destination
                        } else if (prefs.getString("preferred_role", "") == RideRole.Driver.name) {
                            startHomeTab = HomeTab.Profile   // назад из кабинета водителя → профиль
                            screen = Screen.DriverCabinet     // выбрал «Я водитель» → сразу в кабинет (проверка/публикация)
                        } else openHome()
                    },
                )
            }
            // Главный экран — самая большая ветка выбора (154 строки). Вынесена целиком,
            // чтобы выбор экрана влезал в то, что Android соглашается ускорять (AppNavHome.kt).
            Screen.Home -> HomeRoute(
                vm = vm,
                prefs = prefs,
                appScope = appScope,
                requestsLoading = requestsLoading,
                requestsError = requestsError,
                payMethod = payMethod,
                onRetryRequests = { requestsReload++ },
                onLoginRequired = { openProtectedScreen(it) },
                onBookingStatus = { selectedBookingStatus = it },
                onRouteWatchPrefill = { from, to ->
                    routeWatchPrefillFrom = from ?: ""
                    routeWatchPrefillTo = to ?: ""
                },
                onCreateRide = { tab, date ->
                    openCreateRide(returnScreen = Screen.Home, returnHomeTab = tab, prefillDate = date)
                },
                onSos = { openSos() },
                onTrustedContacts = {
                    openTrustedContacts(returnScreen = Screen.Home, returnHomeTab = HomeTab.Profile)
                },
                onAdImpression = { trackAdImpression(it) },
                onAdClick = { trackAdClick(it) },
            )
            Screen.CreateRide -> CreateRideScreen(
                prefillDate = createRidePrefillDate,   // F15: если пришли из баннера — дата праздника уже стоит
                onBack = { goBack() },
                onPublish = { ride ->
                    rides.add(0, ride)
                    Toast.makeText(context, if (language == AppLanguage.Ba) "Сәфәр баҫтырылды" else "Поездка опубликована", Toast.LENGTH_SHORT).show()
                    if (createRideReturnScreen == Screen.DriverCabinet) screen = Screen.DriverCabinet else openHome(HomeTab.Rides)
                }
            )
            Screen.CreateRequest -> CreatePassengerRequestScreen(
                onBack = { goBack() },
                onCreateRequest = { request ->
                    localRequests.add(0, request)
                    Toast.makeText(context, if (language == AppLanguage.Ba) "Заявка булдырылды" else "Заявка создана", Toast.LENGTH_SHORT).show()
                    openHome(HomeTab.Request)
                }
            )
            Screen.Support -> SupportScreen(onBack = { goBack() })
            Screen.Boost -> BoostScreen(onBack = { goBack() })
            Screen.Booking -> {
                val displayedRide = selectedRide
                val displayedBookingId = activeBookingId
                val displayedStatus = selectedBookingStatus
                val displayedSession = navigationSession
                val displayedRevision = vm.privateNavigationRevision
                fun isDisplayedBookingUnchanged() = screen == Screen.Booking && selectedRide == displayedRide &&
                    activeBookingId == displayedBookingId && selectedBookingStatus == displayedStatus &&
                    vm.privateNavigationRevision == displayedRevision && ApiClient.queueSessionGeneration() == displayedSession
                fun isDisplayedBookingCurrent() = isDisplayedBookingUnchanged() && ApiClient.isCurrentSession(displayedSession)
                BookingScreen(
                // null = бронировать нечего (лента пуста и ничего не выбрано): экран сам вернёт
                // назад. Раньше тут подставлялась демо-поездка «вместо краша на пустом списке» —
                // человек видел карточку выдуманного водителя, а бронь не проходила (id не серверный).
                ride = selectedRide ?: rides.firstOrNull(),
                bookingId = activeBookingId,
                ads = partnerAds,
                adStats = adStats,
                onBack = { goBack() },
                onMessage = { openHome(HomeTab.Chat) },
                onAdImpression = ::trackAdImpression,
                onAdClick = ::trackAdClick,
                canOpenActiveTrip = activeBookingId == null || bookingStatusAllowsActiveTrip(selectedBookingStatus),
                bookingStatus = selectedBookingStatus,
                // Передумал, пока водитель молчит. Место возвращается в поездку, водителю
                // уходит уведомление — этим занимается сервер.
                onCancelBooking = {
                    if (displayedBookingId != null && isDisplayedBookingCurrent() && !bookingInFlight) {
                        bookingInFlight = true
                        appScope.launch {
                            try {
                                if (!isDisplayedBookingCurrent()) return@launch
                                val result = ApiClient.cancelBooking(displayedBookingId)
                                if (!isDisplayedBookingCurrent()) return@launch
                                ApiClient.runIfCurrentSession(displayedSession) {
                                    result.onSuccess {
                                        vm.navigateLocally {
                                            selectedBookingStatus = "cancelled"
                                            activeBookingId = null
                                            openHome(HomeTab.Rides)
                                        }
                                    }.onFailure {
                                        val текст = if (language == AppLanguage.Ba)
                                            "Кире алып булманы. Селтәрҙе тикшереп ҡабатла."
                                        else "Не получилось отменить. Проверь сеть и повтори."
                                        Toast.makeText(context, serverSaid(it, текст), Toast.LENGTH_LONG).show()
                                    }
                                }
                            } finally { bookingInFlight = false }
                        }
                    }
                },
                onFindAnotherRide = { openHome(HomeTab.Rides) },
                onConfirmRide = { payMethod, payAmount, minor, guardianName, guardianPhone ->
                    if (!isDisplayedBookingUnchanged()) return@BookingScreen
                    if (!ApiClient.isLoggedIn()) {
                        ApiClient.runIfUnchangedSession(displayedSession) {
                            if (isDisplayedBookingUnchanged()) vm.navigateLocally { openProtectedScreen(Screen.Booking) }
                        }
                        return@BookingScreen
                    }
                    if (!isDisplayedBookingCurrent()) return@BookingScreen
                    if (displayedBookingId != null) {
                        ApiClient.runIfCurrentSession(displayedSession) {
                            if (isDisplayedBookingCurrent()) vm.navigateLocally {
                                activeTrip = displayedRide.takeIf { bookingStatusAllowsBoarding(displayedStatus) }
                                screen = Screen.ActiveTrip
                            }
                        }
                    } else {
                        val rid = displayedRide?.id?.toIntOrNull()
                        // Демо-поездка (id не число) сюда не доходит: бронировать пример нельзя,
                        // и молчать об этом тоже нельзя — иначе кнопка просто «не работает».
                        if (rid == null) {
                            Toast.makeText(
                                context,
                                if (language == AppLanguage.Ba)
                                    "Был — өлгө сәфәр. Ысын сәфәрҙәр бәйләнеш ҡайтҡас күренер."
                                else
                                    "Это пример поездки. Настоящие появятся, когда вернётся связь.",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                        // Защита от двойного нажатия. Кнопка «Забронировать» гасла только по
                        // bookingId, а он приходит уже ПОСЛЕ ответа сервера — то есть всё время
                        // запроса кнопка оставалась живой. На медленной сети второй тап уходил
                        // вторым запросом: две брони на одну поездку, два занятых места и две
                        // договорённости об оплате. Остальные пишущие кнопки такой флаг уже имеют
                        // (оплата, посылка, приём доставки) — эта была единственной без него.
                        if (rid != null && !bookingInFlight) {
                            bookingInFlight = true
                            appScope.launch {
                                try {
                                    if (!isDisplayedBookingCurrent()) return@launch
                                    val result = ApiClient.bookWithStatus(rid, 1, payMethod, payAmount, minor, guardianName, guardianPhone)
                                    if (!isDisplayedBookingCurrent()) return@launch
                                    ApiClient.runIfCurrentSession(displayedSession) {
                                        result.onSuccess { booking ->
                                            vm.navigateLocally {
                                                activeBookingId = booking.id
                                                selectedBookingStatus = booking.status
                                                activeTrip = displayedRide.takeIf { bookingStatusAllowsBoarding(booking.status) }
                                                screen = if (bookingStatusAllowsActiveTrip(booking.status)) Screen.ActiveTrip else Screen.Booking
                                            }
                                        }.onFailure { e ->
                                            val msg = (e as? ApiException)?.message?.takeIf { it.isNotBlank() }
                                                ?: if (language == AppLanguage.Ba) "Бронләп булманы. Ҡабатла." else "Не удалось забронировать. Повтори."
                                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                        }
                                    }
                                } finally { bookingInFlight = false }
                            }
                        }
                    }
                }
            )
            }
            Screen.ActiveTrip -> ActiveTripScreen(
                ride = selectedRide,
                contacts = trustedContacts,
                bookingId = activeBookingId,
                onBack = { goBack() },
                onTripEnd = { activeTrip = null; openHome(HomeTab.Map) },
                // Дежурный должен узнать из сигнала, с кем и куда человек уехал (аудит 2026-08-06).
                onSos = { openSos(bookingId = activeBookingId ?: 0) },
                onSupport = { screen = Screen.Support },
                onOpenReceipt = { bid -> receiptBookingId = bid; screen = Screen.TripReceipt }
            )
            Screen.Sos -> SosScreen(
                onBack = { goBack() },
                onLoginRequired = { returnToSosAfterLogin = true; screen = Screen.Login },
                orderId = sosOrderId.takeIf { it > 0 },     // контекст такси-заказа (B7b-2); 0 = обычный SOS
                bookingId = sosBookingId.takeIf { it > 0 }, // контекст попутки; 0 = обычный SOS
                contextNote = sosContextNote.takeIf { it.isNotBlank() },  // курьер: маршрут доставки
            )
            Screen.VerifyDriver -> VerifyDriverScreen(
                onBack = { goBack() },
                onSelectTab = { tab -> openHome(tab) }
            )
            Screen.Notifications -> NotificationsScreen(
                onBack = { goBack() },
                onSelectTab = { tab -> openHome(tab) },
                // Deep-link: тап по брони/поездке/сообщению → детали брони (BookingScreen сам грузит их по id).
                onOpenBooking = { bid ->
                    vm.requestBookingDestination(bid, ApiClient.myUserId(), completed = false)
                },
                // Тап по «отклик на заявку» → экран откликов этой заявки.
                onOpenResponses = { rid -> vm.requestScreenDestination(Screen.RequestResponses, ApiClient.myUserId(), rid) },
                onOpenCompletedBooking = { bid -> vm.requestCompletedBooking(bid, ApiClient.myUserId()) },
                onOpenRequestsFeed = { vm.requestScreenDestination(Screen.RequestsFeed, ApiClient.myUserId()) },
                onRouteWatches = { routeWatchPrefillFrom = ""; routeWatchPrefillTo = ""; screen = Screen.RouteWatches },
                // Тап по уведомлению поддержки → тред обращения (ref_id = id тикета).
                onOpenSupport = { tid -> vm.requestScreenDestination(Screen.SupportTicket, ApiClient.myUserId(), tid) },
                // Доставка и такси (аудит 2026-08-06): раньше эти карточки не открывались вовсе.
                onOpenParcels = { vm.requestScreenDestination(Screen.Parcels, ApiClient.myUserId()) },
                onOpenInstantOrder = { vm.requestScreenDestination(Screen.InstantOrder, ApiClient.myUserId()) },
                // «Появилась поездка» / «Поездка завершена, оцени» → карточка поездки
                // (тем же путём, что ссылка yulbash.ru/r/{id}).
                onOpenRide = { rid -> DeepLink.pendingRideId.value = rid },
                // «Открыт разбор» / «Решение по спору» → карточка разбора: там причина и срок.
                onOpenIncident = { id -> vm.requestScreenDestination(Screen.IncidentDetail, ApiClient.myUserId(), id) },
                // Долг, списанная комиссия, пауза такси → кабинет водителя: там это всё видно.
                onOpenDriverCabinet = { vm.requestScreenDestination(Screen.DriverCabinet, ApiClient.myUserId()) },
                // Решение по заявке → её экран со статусом проверки.
                onOpenTaxiApply = { vm.requestScreenDestination(Screen.TaxiOnboarding, ApiClient.myUserId()) },
                onOpenCourierApply = { vm.requestScreenDestination(Screen.CourierOnboarding, ApiClient.myUserId()) },
                // Решение по бизнесу или купону → «Мой бизнес»; по рекламе → мои объявления.
                // За то и другое человек заплатил, поэтому сообщение обязано вести к делу.
                onOpenPartnerCabinet = { vm.requestScreenDestination(Screen.PartnerCabinet, ApiClient.myUserId()) },
                onOpenAdsCabinet = { vm.requestScreenDestination(Screen.AdsCabinet, ApiClient.myUserId()) },
            )
            Screen.RouteWatches -> RouteWatchesScreen(
                onBack = { goBack() },
                prefillFrom = routeWatchPrefillFrom,
                prefillTo = routeWatchPrefillTo,
            )
            Screen.Privacy -> PrivacyScreen(onBack = { goBack() })
            Screen.MyData -> MyDataScreen(onBack = { goBack() })
            Screen.Rules -> RulesScreen(onBack = { goBack() })
            Screen.PaymentInfo -> PaymentInfoScreen(onBack = { goBack() }, onOpenPricing = { screen = Screen.PricingInfo })
            Screen.PaymentMethods -> PaymentMethodsScreen(
                current = payMethod,
                // Идёт поездка → смена способа уходит и на сервер, а не только в память телефона.
                activeOrderId = NavSignals.activeTaxiTrip.value,
                // Выбор помним между заказами: человек платит одинаково изо дня в день,
                // и заставлять его каждый раз выбирать заново — мелкая, но ежедневная работа.
                onPick = { m ->
                    payMethod = m
                    prefs.edit().putString(PAY_METHOD_PREF, m).apply()
                },
                onBack = { goBack() },
            )
            Screen.PricingInfo -> PricingInfoScreen(onBack = { goBack() })
            Screen.Blocklist -> BlocklistScreen(onBack = { goBack() })
            Screen.Report -> ReportScreen(onBack = { goBack() })
            Screen.Filters -> FiltersScreen(onBack = { goBack() })
            Screen.Safety -> SafetyScreen(
                onBack = { goBack() },
                onSelectTab = { tab -> openHome(tab) },
                onSos = { openSos() },
                onShareTrip = { openTrustedContacts(returnScreen = Screen.Safety) },
                onRules = { screen = Screen.Rules },
                onBlocklist = { screen = Screen.Blocklist },
                onReport = { screen = Screen.Report }
            )
            Screen.Settings -> SettingsScreen(
                onBack = { goBack() },
                onSelectTab = { tab -> openHome(tab) },
                onToggleLanguage = {
                    language = if (language == AppLanguage.Ru) AppLanguage.Ba else AppLanguage.Ru
                },
                onPrivacy = { screen = Screen.Privacy },
                onConsents = { screen = Screen.Consents },
                onPayments = { screen = Screen.PaymentMethods },
                onFilters = { screen = Screen.Filters },
                isAdmin = isAdmin,
                onAdminCabinet = { screen = Screen.AdminCabinet },
                onLogout = {
                    ApiClient.logout()
                    endSession(context, vm)             // гасим весь live-GPS и стираем чужое из памяти
                    screen = Screen.Login
                }
            )
            Screen.IncomeCalculator -> IncomeCalculatorScreen(onBack = { goBack() })
            Screen.Help -> HelpScreen(
                ads = partnerAds,
                adStats = adStats,
                onBack = { goBack() },
                onSelectTab = { tab -> openHome(tab) },
                onAdImpression = ::trackAdImpression,
                onAdClick = ::trackAdClick,
                onSupportChat = { openProtectedScreen(Screen.SupportTickets) }
            )
            Screen.PassengerCabinet -> PassengerCabinetScreen(
                rides = rides,
                requests = localRequests,
                onBack = { goBack() },
                onMyTrips = { openHome(HomeTab.Rides) },
                onOpenBooking = { ride, status ->
                    selectedRide = ride
                    activeTrip = null
                    activeBookingId = ride.id.toIntOrNull()
                    screen = if (status == "confirmed" || status == "onboard") Screen.ActiveTrip else Screen.Booking
                },
                onFindRide = { openHome(HomeTab.Map) },
                onCreateRequest = { screen = Screen.CreateRequest },
                onInstantOrder = { openProtectedScreen(Screen.InstantOrder) },
                onScheduledOrders = { openProtectedScreen(Screen.ScheduledOrders) },
                onMyTaxiTrips = { openProtectedScreen(Screen.MyTaxiTrips) },
                onWallet = { openProtectedScreen(Screen.Wallet) },
                onSavedPlaces = { openProtectedScreen(Screen.SavedPlaces) },
                onSafety = { screen = Screen.Safety }
            )
            Screen.DriverCabinet -> DriverCabinetScreen(
                rides = rides,
                onBack = { goBack() },
                onCreateRide = { openCreateRide(returnScreen = Screen.DriverCabinet) },
                onVerifyDriver = { screen = Screen.VerifyDriver },
                onBoost = { screen = Screen.Boost },
                onRequestsFeed = { screen = Screen.RequestsFeed },
                onInstantTrip = { id -> instantTripOrderId = id; screen = Screen.InstantDriverTrip },
                onTaxiOnboarding = { screen = Screen.TaxiOnboarding },
                onWallet = { openProtectedScreen(Screen.Wallet) },
                onEarnings = { openProtectedScreen(Screen.DriverEarnings) },
                onTaxiRides = { openProtectedScreen(Screen.DriverTaxiRides) },
                onTaxiDocs = { openProtectedScreen(Screen.TaxiDocuments) },
                onPretrip = { openProtectedScreen(Screen.PretripCheck) },
                onMyResponses = { openProtectedScreen(Screen.DriverResponses) },
            )
            Screen.ScheduledOrders -> ScheduledOrdersScreen(
                onBack = { goBack() },
                // Активировал предзаказ → в обычный экран заказа: он восстановит заказ в поиске.
                onActivated = { screen = Screen.InstantOrder }
            )
            Screen.SupportTickets -> SupportTicketsScreen(
                onBack = { goBack() },
                onOpenTicket = { tid -> supportTicketId = tid; screen = Screen.SupportTicket }
            )
            Screen.SupportTicket -> SupportTicketScreen(
                ticketId = supportTicketId,
                onBack = { goBack() }
            )
            Screen.RequestsFeed -> RequestsFeedScreen(onBack = { goBack() })
            Screen.DriverResponses -> DriverResponsesScreen(
                onBack = { goBack() },
                // Согласился на встречную цену → сразу в поездку, как при обычном accept у пассажира.
                onOpenTrip = { bid -> activeBookingId = bid; activeTrip = null; screen = Screen.ActiveTrip },
            )
            Screen.RequestResponses -> {
                val displayedRequestId = responsesRequestId
                val responseSession = navigationSession
                val responseRevision = vm.privateNavigationRevision
                ResponsesScreen(
                requestId = displayedRequestId,
                onBack = { goBack() },
                onAccepted = { bid ->
                    ApiClient.runIfCurrentSession(responseSession) {
                        if (bid > 0 && screen == Screen.RequestResponses && responsesRequestId == displayedRequestId &&
                            vm.privateNavigationRevision == responseRevision) vm.navigateLocally {
                            selectedRide = null
                            activeBookingId = bid
                            activeTrip = null
                            screen = Screen.ActiveTrip
                        }
                    }
                }
            )
            }
            Screen.AdsCabinet -> AdsCabinetScreen(
                onBack = { goBack() },
                onCreateAd = { adEditorTarget = null; screen = Screen.AdEditor },
                onEditAd = { dto -> adEditorTarget = dto; screen = Screen.AdEditor },
            )
            Screen.AdEditor -> AdEditorScreen(
                initial = adEditorTarget,
                onBack = { goBack() },
                onSaved = { goBack() },   // сохранил → назад в кабинет (не оставляем редактор в трейле)
            )
            Screen.SimpleMode -> SimpleModeScreen(
                latestRequests = localRequests,
                onBack = { goBack() },
                onVoiceRequest = { openProtectedScreen(Screen.VoiceRequest) },
                onFamilyOrder = { openProtectedScreen(Screen.FamilyOrder) },
                onTrustedContacts = { openTrustedContacts(returnScreen = Screen.SimpleMode) },
                onRepeatTrip = { openProtectedScreen(Screen.RepeatTrip) },
                onCallbackHelp = { openProtectedScreen(Screen.CallbackHelp) },
                onSos = { openSos() },
                onChat = { startHomeTab = HomeTab.Chat; openProtectedScreen(Screen.Home) }
            )
            Screen.VoiceRequest -> VoiceRequestScreen(
                contacts = trustedContacts,
                onBack = { goBack() },
                onCreateRequest = { request ->
                    localRequests.add(0, request)
                    Toast.makeText(context, if (language == AppLanguage.Ba) "Заявка булдырылды" else "Заявка создана", Toast.LENGTH_SHORT).show()
                    goBack()
                }
            )
            Screen.FamilyOrder -> FamilyOrderScreen(
                contacts = trustedContacts,
                onBack = { goBack() },
                onCreateRequest = { request ->
                    localRequests.add(0, request)
                    Toast.makeText(context, if (language == AppLanguage.Ba) "Яҡын кеше өсөн сәфәр булдырылды" else "Поездка за близкого создана", Toast.LENGTH_SHORT).show()
                    goBack()
                }
            )
            Screen.TrustedContacts -> TrustedContactsScreen(
                contacts = trustedContacts,
                onBack = { closeTrustedContacts() },
                onAddContact = { contact ->
                    // Безопасность: контакт получает статус поездки/SOS — успех показываем ПО ФАКТУ сервера,
                    // при сбое откатываем (иначе fire-and-forget = «добавлен» на экране, а на сервере нет).
                    trustedContacts.add(contact)
                    appScope.launch {
                        ApiClient.addContact(contact.name, contact.relation, contact.phone, contact.notifyByDefault)
                            .onSuccess { Toast.makeText(context, if (language == AppLanguage.Ba) "Контакт өҫтәлде" else "Контакт добавлен", Toast.LENGTH_SHORT).show() }
                            .onFailure {
                                trustedContacts.remove(contact)
                                Toast.makeText(context, if (language == AppLanguage.Ba) "Булманы. Сетте тикшереп ҡабатла" else "Не получилось. Проверь сеть и повтори", Toast.LENGTH_SHORT).show()
                            }
                    }
                }
            )
            Screen.RepeatTrip -> RepeatTripScreen(
                contacts = trustedContacts,
                onBack = { goBack() },
                onLoginRequired = { openProtectedScreen(Screen.RepeatTrip) },
                onRepeat = { request ->
                    localRequests.add(0, request)
                    Toast.makeText(context, if (language == AppLanguage.Ba) "Йыш сәфәр ҡабатланды" else "Частая поездка повторена", Toast.LENGTH_SHORT).show()
                    goBack()
                }
            )
            Screen.CallbackHelp -> CallbackHelpScreen(
                requested = callbackRequested,
                loading = callbackInFlight,
                onBack = { goBack() },
                onRequest = { note ->
                    if (!callbackInFlight) {
                        callbackInFlight = true
                        val generation = ApiClient.queueSessionGeneration()
                        appScope.launch {
                            try {
                                val result = ApiClient.requestCallback(note)
                                if (ApiClient.isCurrentSession(generation)) {
                                    result.onSuccess {
                                        callbackRequested = true
                                        Toast.makeText(context, if (language == AppLanguage.Ba) "Шылтыратыу заявкаһы булдырылды" else "Заявка на звонок создана", Toast.LENGTH_SHORT).show()
                                    }.onFailure {
                                        callbackRequested = false
                                        Toast.makeText(context, if (language == AppLanguage.Ba) "Булманы. Сетте тикшереп ҡабатла" else "Не получилось. Проверь сеть и повтори", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            } finally {
                                callbackInFlight = false
                            }
                        }
                    }
                }
            )
            Screen.MyStats -> MyStatsScreen(onBack = { goBack() })
            Screen.Wallet -> WalletScreen(onBack = { goBack() })
            Screen.DriverEarnings -> DriverEarningsScreen(onBack = { goBack() })
            Screen.SavedPlaces -> SavedPlacesScreen(onBack = { goBack() })
            Screen.TripReceipt -> TripReceiptScreen(bookingId = receiptBookingId, onBack = { goBack() })
            Screen.FairnessCenter -> FairnessCenterScreen(
                onBack = { goBack() },
                onOpenIncident = { id -> incidentId = id; screen = Screen.IncidentDetail },
            )
            Screen.IncidentDetail -> IncidentDetailScreen(incidentId = incidentId, onBack = { goBack() })
            Screen.AppReview -> AppReviewScreen(onBack = { goBack() })
            Screen.DriverProfile -> DriverProfileScreen(driverId = driverProfileId, onBack = { goBack() })
            Screen.Trust -> TrustScreen(
                onBack = { goBack() },
                onOpenInvites = { screen = Screen.Invites },
                onOpenConsents = { screen = Screen.Consents },
                onEditProfile = { openHome(HomeTab.Profile) },   // L0→L1: имя и фото в профиле
                onVerify = { screen = Screen.VerifyDriver },     // L1→L2: проверка документов
            )
            Screen.Invites -> InvitesScreen(onBack = { goBack() })
            Screen.Consents -> ConsentsScreen(onBack = { goBack() })
            Screen.ClinicRides -> ClinicRidesScreen(
                onBack = { goBack() },
                onBookRide = { ride ->
                    selectedRide = ride
                    activeBookingId = null
                    selectedBookingStatus = ""
                    screen = Screen.Booking
                }
            )
            Screen.Coupons -> CouponsScreen(onBack = { goBack() })
            Screen.PartnerCabinet -> PartnerCabinetScreen(onBack = { goBack() })
            Screen.PromoCode -> PromoCodeScreen(onBack = { goBack() })
        }
        }
        }
        }
        }
    }
}

/** Field identity prevents same-type legacy ID collisions as well as invalid casts.
 * Anonymous pre-schema root state is intentionally discarded; owner-scoped screen drafts are separate. */
private inline fun <reified T> rootNavigationStateSaver(field: String, ownerId: Int? = null): Saver<MutableState<T>, Any> = Saver(
    save = { arrayListOf("yuldash-root-navigation-v1", field, ownerId, it.value) },
    restore = { stored ->
        val record = stored as? List<*>
        if (record?.size == 4 && record[0] == "yuldash-root-navigation-v1" && record[1] == field &&
            record[2] == ownerId && record[3] is T)
            mutableStateOf(record[3] as T)
        else null
    },
)

internal fun shareRide(context: android.content.Context, text: String, chooserTitle: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, chooserTitle))
}

@Composable
private fun OnboardingScreen(
    onFinish: (RideRole) -> Unit,
    language: AppLanguage,
    onSelectLanguage: (AppLanguage) -> Unit,
    onSimpleMode: () -> Unit
) {
    // Слайды и воронка-эффект живут в обёртке (side-effect), вся разметка — в чистом OnboardingContent.
    val slides = remember { onboardingSlides() }
    LaunchedEffect(Unit) { Analytics.log("onboarding_start") }   // воронка: начало онбординга (с этим виден отвал внутри онбординга)
    OnboardingContent(
        slides = slides,
        language = language,
        onSelectLanguage = onSelectLanguage,
        onFinish = onFinish,
        onSimpleMode = onSimpleMode,
    )
}

/**
 * Чистая разметка онбординга: пейджер слайдов, точки, кнопки «Далее/Пропустить/Войти», выбор роли.
 * Без сети/ViewModel/Analytics — те живут в обёртке OnboardingScreen. Тестируется на JVM (Robolectric).
 */
@Composable
internal fun OnboardingContent(
    slides: List<OnboardingSlide>,
    language: AppLanguage,
    onSelectLanguage: (AppLanguage) -> Unit,
    onFinish: (RideRole) -> Unit,
    modifier: Modifier = Modifier,
    onSimpleMode: () -> Unit = {},
) {
    val pagerState = rememberPagerState(pageCount = { slides.size })
    val scope = rememberCoroutineScope()
    var role by rememberSaveable { mutableStateOf(RideRole.Passenger) }  // переживает поворот: выбор «водитель» не сбрасывался в «пассажир»
    val isLastPage = pagerState.currentPage == slides.lastIndex

    Surface(
        modifier = modifier.fillMaxSize(),
        color = CanonBg
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.End
            ) {
                OnboardingLangToggle(language, onSelectLanguage)
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f)
            ) { page ->
                val slide = slides[page]
                var played by remember { mutableStateOf(false) }
                LaunchedEffect(pagerState.currentPage) { if (pagerState.currentPage == page) played = true }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 12.dp)
                ) {
                    item { Spacer(Modifier.height(8.dp)) }
                    item { OnboardingHeroCard(slide) { (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction } }
                    item {
                        Text(
                            text = appText(slide.titleRu, slide.titleBa),
                            modifier = Modifier.onbAppear(0, played),
                            color = CanonText,
                            fontSize = 34.sp,
                            lineHeight = 40.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    item {
                        Text(
                            text = appText(slide.bodyRu, slide.bodyBa),
                            modifier = Modifier.onbAppear(1, played),
                            color = CanonMuted,
                            fontSize = 16.sp,
                            lineHeight = 23.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    if (page == slides.lastIndex) {
                        item {
                            Box(Modifier.onbAppear(2, played)) {
                                OnboardingRoleChooser(
                                    selected = role,
                                    onSelect = { role = it }
                                )
                            }
                        }
                        item {
                            Box(Modifier.onbAppear(3, played)) {
                                OnboardingSimpleModeCard(onEnable = onSimpleMode)
                            }
                        }
                    } else {
                        itemsIndexed(slide.items, key = { _, item -> item.titleRu }) { index, item ->
                            Box(Modifier.onbAppear(2 + index, played)) {
                                OnboardingFeatureCard(item, index + 1)
                            }
                        }
                    }
                    if (slide.noteRu != null && slide.noteBa != null) {
                        item { Box(Modifier.onbAppear(5, played)) { OnboardingSafetyNote(appText(slide.noteRu, slide.noteBa)) } }
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
                            Text(appText("Назад", "Артҡа"), color = CanonGreen2, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Spacer(Modifier.width(82.dp))
                    }
                    OnboardingDots(
                        count = slides.size,
                        selected = pagerState.currentPage,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { onFinish(role) }) {
                        Text(appText("Пропустить", "Үткәреп ебәреү"), color = CanonGreen2, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (isLastPage) {
                            onFinish(role)
                        } else {
                            scope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 58.dp),
                    shape = RoundedCornerShape(22.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)
                ) {
                    Text(
                        text = if (isLastPage) appText("Войти через Telegram", "Telegram аша инеү") else appText("Далее", "Артабан"),
                        fontWeight = FontWeight.Bold,
                        fontSize = 19.sp
                    )
                }
            }
        }
    }
}

@Composable
internal fun OnboardingLangToggle(language: AppLanguage, onSelect: (AppLanguage) -> Unit) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = CanonSurface,
        border = BorderStroke(1.dp, CanonBorder)
    ) {
        Row(Modifier.padding(4.dp)) {
            OnboardingLangChip("РУС", language == AppLanguage.Ru) { onSelect(AppLanguage.Ru) }
            OnboardingLangChip("БАШ", language == AppLanguage.Ba) { onSelect(AppLanguage.Ba) }
        }
    }
}

@Composable
internal fun OnboardingLangChip(text: String, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .semantics { selected = active; role = Role.RadioButton }
            .background(if (active) CanonGreen2 else Color.Transparent)
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (active) CanonOnFilled else CanonMuted, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

/** Спокойное появление элемента: fade + лёгкий сдвиг вверх, стаггер по index. Играет, когда слайд стал активным. */
@Composable
private fun Modifier.onbAppear(index: Int, play: Boolean): Modifier {
    val a by animateFloatAsState(if (play) 1f else 0f, tween(CanonMotion.ENTRY, delayMillis = if (play) CanonMotion.cascadeIn(index) else 0), label = "onbA")
    val ty by animateFloatAsState(if (play) 0f else 34f, tween(CanonMotion.ENTRY, delayMillis = if (play) CanonMotion.cascadeIn(index) else 0), label = "onbY")
    return graphicsLayer { this.alpha = a; translationY = ty }
}

@Composable
internal fun OnboardingHeroCard(slide: OnboardingSlide, pageOffset: () -> Float = { 0f }) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(318.dp)
            .clip(CanonCardShape)
            .background(CanonGreen2)
    ) {
        Image(
            painter = painterResource(R.drawable.onboarding_bashkir_hero),
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = pageOffset() * 42f
                    scaleX = 1.04f
                    scaleY = 1.04f
                },
            contentScale = ContentScale.Crop
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.16f),
                        0.46f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.58f)
                    )
                )
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to CanonGreen2.copy(alpha = 0.54f),
                        0.45f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.14f)
                    )
                )
        )

        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(14.dp),
                color = Color.White,
                shadowElevation = CanonDepth.raised
            ) {
                Image(
                    painter = painterResource(R.drawable.yuldash_logo),
                    contentDescription = null,
                    modifier = Modifier.padding(4.dp),
                    contentScale = ContentScale.Fit
                )
            }
        }

        Surface(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, end = 92.dp, bottom = 16.dp)
                .graphicsLayer { translationX = pageOffset() * 62f },
            color = Color.White.copy(alpha = 0.18f),
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.30f))
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Юлдаш",
                    color = Color.White,
                    fontSize = 24.sp,
                    lineHeight = 30.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = appText(slide.eyebrowRu, slide.eyebrowBa),
                    color = Color.White.copy(alpha = 0.94f),
                    fontSize = 16.sp,
                    lineHeight = 23.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 24.dp)
                .graphicsLayer { translationX = pageOffset() * 120f; translationY = pageOffset() * -24f }
                .size(72.dp)
                .background(Color.White.copy(alpha = 0.20f), CircleShape)
                .border(1.dp, Color.White.copy(alpha = 0.34f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(onboardingHeroIcon(slide.hero), contentDescription = null, tint = Color.White, modifier = Modifier.size(38.dp))
        }
    }
}

internal fun onboardingHeroIcon(hero: OnboardingHero): ImageVector {
    return when (hero) {
        OnboardingHero.Route -> Icons.Default.NearMe
        OnboardingHero.Security -> Icons.Default.Shield
        OnboardingHero.Steps -> Icons.Default.Route
        OnboardingHero.Start -> Icons.Default.RocketLaunch
    }
}

@Composable
internal fun OnboardingFeatureCard(item: OnboardingItem, index: Int) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = CanonItemShape,
        border = BorderStroke(1.dp, CanonBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.card)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            OnboardingIconBubble(item.icon, index)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(appText(item.titleRu, item.titleBa), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 19.sp, lineHeight = 25.sp)
                Text(appText(item.bodyRu, item.bodyBa), color = CanonMuted, fontSize = 16.sp, lineHeight = 23.sp)
            }
        }
    }
}

@Composable
internal fun OnboardingRoleChooser(selected: RideRole, onSelect: (RideRole) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OnboardingRoleCard(
            icon = Icons.Default.Person,
            title = appText("Я пассажир", "Мин пассажир"),
            body = appText("Ищу поездки, создаю заявки и общаюсь с водителями.", "Сәфәр эҙләйем, заявка булдырам һәм йөрөтөүселәр менән һөйләшәм."),
            selected = selected == RideRole.Passenger,
            onClick = { onSelect(RideRole.Passenger) }
        )
        OnboardingRoleCard(
            icon = Icons.Default.DirectionsCar,
            title = appText("Я водитель", "Мин йөрөтөүсе"),
            body = appText("Публикую поездки, откликаюсь на заявки и прохожу проверку.", "Сәфәрҙәр ҡуям, заявкаларға яуап бирәм һәм тикшереү үтәм."),
            selected = selected == RideRole.Driver,
            onClick = { onSelect(RideRole.Driver) }
        )
        OnboardingTrustStrip()
    }
}

/**
 * Мягкое предложение простого режима в онбординге (рядом с выбором роли).
 * Крупная кнопка ведёт в SimpleModeScreen / включает режим; «Не сейчас» — прячет карточку.
 * Тёплый тон, всё двуязычно; вход в простой режим также остаётся в профиле.
 */
@Composable
internal fun OnboardingSimpleModeCard(onEnable: () -> Unit) {
    var dismissed by rememberSaveable { mutableStateOf(false) }
    if (!dismissed) {
        Card(
            colors = CardDefaults.cardColors(containerColor = CanonMint),
            shape = CanonItemShape,
            elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.card)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(46.dp).background(CanonSurface, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.VolumeUp, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        appText("Тебе удобнее крупные кнопки и голосовой заказ?", "Һиңә эре төймәләр һәм тауыш менән заказ уңайлыраҡмы?"),
                        modifier = Modifier.weight(1f),
                        color = CanonText,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        lineHeight = 23.sp
                    )
                }
                Text(
                    appText(
                        "Простой режим — большие кнопки, меньше шагов и заказ голосом. Включить можно и позже в профиле.",
                        "Ябай режим — эре төймәләр, аҙыраҡ аҙым һәм тауыш менән заказ. Һуңынан профилдә лә тоҡандырып була."
                    ),
                    color = CanonText.copy(alpha = 0.82f),
                    fontSize = 16.sp,
                    lineHeight = 23.sp
                )
                Button(
                    onClick = onEnable,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)
                ) {
                    Icon(Icons.Default.VolumeUp, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        appText("Включить простой режим", "Ябай режимды тоҡандырыу"),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
                TextButton(
                    onClick = { dismissed = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Text(appText("Не сейчас", "Хәҙер түгел"), color = CanonMuted, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
internal fun OnboardingRoleCard(
    icon: ImageVector,
    title: String,
    body: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.bounceClick(onClick).semantics(mergeDescendants = true) {
            this.selected = selected
            role = Role.RadioButton
        },
        colors = CardDefaults.cardColors(containerColor = if (selected) CanonMint else CanonSurface),
        shape = CanonItemShape,
        elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.card)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            OnboardingIconBubble(icon)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                Text(body, color = CanonMuted, fontSize = 16.sp, lineHeight = 23.sp)
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
internal fun OnboardingTrustStrip() {
    Card(colors = CardDefaults.cardColors(containerColor = CanonSurface), shape = CanonItemShape, elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.card)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            OnboardingMiniTrust(Icons.Default.Handshake, appText("Между\nсвоими", "Үҙ кеше\nараһында"), Modifier.weight(1f))
            OnboardingMiniTrust(Icons.Default.MoneyOff, appText("Без\nкомиссии", "Комиссия\nюҡ"), Modifier.weight(1f))
            OnboardingMiniTrust(Icons.Default.Map, appText("Весь\nБашкортостан", "Бөтә\nБашҡортостан"), Modifier.weight(1f))
        }
    }
}

@Composable
internal fun OnboardingMiniTrust(icon: ImageVector, label: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, tint = CanonGreen2)
        Spacer(Modifier.height(4.dp))
        Text(label, color = CanonText, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, fontSize = 12.sp, lineHeight = 17.sp, minLines = 2, maxLines = 2)
    }
}

@Composable
internal fun OnboardingIconBubble(icon: ImageVector, index: Int? = null) {
    Box(
        modifier = Modifier
            .size(58.dp)
            .background(CanonMint, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(30.dp))
        if (index != null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(22.dp),
                shape = CircleShape,
                color = CanonGreen2
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(index.toString(), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
internal fun OnboardingSafetyNote(text: String) {
    Surface(color = CanonMint, shape = CanonItemShape, border = BorderStroke(1.dp, CanonHairlineGreen)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Lock, contentDescription = null, tint = CanonGreen2)
            Spacer(Modifier.width(12.dp))
            Text(text, color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 23.sp)
        }
    }
}

@Composable
internal fun OnboardingDots(count: Int, selected: Int, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { index ->
            val active = selected == index
            val w by animateDpAsState(if (active) 26.dp else 8.dp, tween(CanonMotion.SLOW), label = "dotW")
            val c by animateColorAsState(if (active) CanonGreen2 else CanonMuted.copy(alpha = 0.32f), tween(CanonMotion.SLOW), label = "dotC")
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(width = w, height = 8.dp)
                    .background(c, RoundedCornerShape(999.dp))
            )
        }
    }
}

internal fun onboardingSlides() = listOf(
    OnboardingSlide(
        eyebrowRu = "Дорога по Башкортостану",
        eyebrowBa = "Башҡортостан буйлап юл",
        titleRu = "Едешь с юлдашом, не с незнакомцем",
        titleBa = "Ят кеше менән түгел, юлдаш менән бараһың",
        bodyRu = "Поездки и заявки между своими: Баймак, Сибай, Уфа и другие привычные маршруты рядом.",
        bodyBa = "Үҙ кешеләр араһында сәфәрҙәр һәм заявкалар: Баймаҡ, Сибай, Өфө һәм яҡын маршруттар.",
        hero = OnboardingHero.Route,
        items = listOf(
            OnboardingItem(Icons.Default.Search, "Нашёл маршрут", "Маршрут таптың", "Смотри ближайшие поездки или оставь заявку, если машины ещё нет.", "Яҡындағы сәфәрҙәрҙе ҡара йәки машина юҡ икән заявка ҡалдыр."),
            OnboardingItem(Icons.Default.ChatBubbleOutline, "Договорился в чате", "Чатта килештең", "После отклика можно спокойно уточнить место, время и багаж.", "Яуаптан һуң урын, ваҡыт һәм багаж тураһында һөйләшергә була."),
            OnboardingItem(Icons.Default.DirectionsCar, "Поехал спокойно", "Тыныс юлға сыҡтың", "Важные детали поездки остаются внутри приложения.", "Сәфәрҙең мөһим деталдәре ҡушымта эсендә ҡала.")
        )
    ),
    OnboardingSlide(
        eyebrowRu = "Доверие важнее скорости",
        eyebrowBa = "Ышаныс тиҙлектән мөһимерәк",
        titleRu = "Безопасность перед дорогой",
        titleBa = "Юл алдынан хәүефһеҙлек",
        bodyRu = "Водитель может пройти проверку, номер не раскрывается заранее, а в поездке есть SOS и связь с близкими.",
        bodyBa = "Водитель тикшереү үтә ала, номер алдан асылмай, ә сәфәрҙә SOS һәм яҡындар менән бәйләнеш бар.",
        hero = OnboardingHero.Security,
        items = listOf(
            OnboardingItem(Icons.Default.Verified, "Проверка водителя", "Водителде тикшереү", "Профиль водителя и фото машины уходят на модерацию.", "Водитель профиле һәм машина фотоһы модерацияға китә."),
            OnboardingItem(Icons.Default.VisibilityOff, "Номер скрыт", "Номер йәшерелгән", "Контакты открываются только после подтверждения поездки.", "Контакттар сәфәр раҫланғандан һуң ғына асыла."),
            OnboardingItem(Icons.Default.Sos, "SOS рядом", "SOS яҡында", "В экстренной ситуации можно быстро отправить сигнал помощи.", "Ашығыс хәлдә ярҙам сигналы ебәрергә була."),
            OnboardingItem(Icons.Default.Share, "Близкий видит поездку", "Яҡының сәфәрҙе күрә", "Поделись маршрутом — родной человек на связи всю дорогу.", "Маршрут менән бүлеш — яҡының юл буйы бәйләнештә.")
        )
    ),
    OnboardingSlide(
        eyebrowRu = "Когда машины ещё нет",
        eyebrowBa = "Машина әле юҡ икән",
        titleRu = "Заявка не пропадает в пустоту",
        titleBa = "Заявка бушҡа юғалмай",
        bodyRu = "Пассажир оставляет маршрут, водитель видит заявку, откликается, а после принятия появляется поездка с чатом.",
        bodyBa = "Пассажир маршрут ҡалдыра, водитель заявканы күрә, яуап бирә, ҡабул иткәс чатлы сәфәр асыла.",
        hero = OnboardingHero.Steps,
        items = listOf(
            OnboardingItem(Icons.Default.EditNote, "Создай заявку", "Заявка булдыр", "Укажи маршрут, время и что важно в дороге.", "Маршрутты, ваҡытты һәм юлдағы мөһим шарттарҙы күрһәт."),
            OnboardingItem(Icons.Default.QuestionAnswer, "Водитель откликнется", "Водитель яуап бирер", "Отклики приходят к пассажиру, можно выбрать подходящий вариант.", "Яуаптар пассажирға килә, уңайлы вариантты һайларға була."),
            OnboardingItem(Icons.Default.Pin, "Код посадки", "Ултырыу коды", "Подтверждённая поездка получает чат и код посадки.", "Раҫланған сәфәрҙә чат һәм ултырыу коды була.")
        ),
        noteRu = "Так закрывается путь: заявка → отклик → поездка",
        noteBa = "Шулай юл ябыла: заявка → яуап → сәфәр"
    ),
    OnboardingSlide(
        eyebrowRu = "Готово к первой поездке",
        eyebrowBa = "Беренсе сәфәргә әҙер",
        titleRu = "Начнём?",
        titleBa = "Башлайбыҙмы?",
        bodyRu = "Войди через Telegram: бот пришлёт код, а Юлдаш откроет карту, заявки, чат и профиль.",
        bodyBa = "Telegram аша ин: бот код ебәрер, ә Юлдаш карта, заявкалар, чат һәм профилде асыр.",
        hero = OnboardingHero.Start,
        items = emptyList()
    )
)


@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScreenTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.Bold) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBackIosNew, contentDescription = appText("Назад", "Артҡа"))
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
            navigationIconContentColor = MaterialTheme.colorScheme.onBackground
        )
    )
}

/** Each animated tab owns its actions even while its outgoing content recomposes. */
internal class HomeTabActionGate(
    private val accepts: () -> Boolean,
    private val choose: (HomeTab, () -> Unit) -> Unit = { _, action -> action() },
) {
    fun select(tab: HomeTab, afterSelection: () -> Unit) { if (accepts()) choose(tab, afterSelection) }
    fun guard(action: () -> Unit): () -> Unit = { if (accepts()) action() }
    fun <A> guard(action: (A) -> Unit): (A) -> Unit = { a -> if (accepts()) action(a) }
    fun <A, B> guard(action: (A, B) -> Unit): (A, B) -> Unit = { a, b -> if (accepts()) action(a, b) }
    fun <A, B, C> guard(action: (A, B, C) -> Unit): (A, B, C) -> Unit = { a, b, c -> if (accepts()) action(a, b, c) }
}
private val LocalHomeTabActionGate = staticCompositionLocalOf { HomeTabActionGate(accepts = { true }) }

@Composable
internal fun HomeScreen(
    rides: List<Ride>,
    activeTrip: Ride?,
    requests: List<LocalRequest>,
    requestsLoading: Boolean = false,
    requestsError: Boolean = false,
    onRetryRequests: () -> Unit = {},
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
    onOpenBookingDetails: (Ride, String) -> Unit,
    onOpenActiveTrip: (Ride, String) -> Unit,
    onShareRide: (Ride) -> Unit,
    onAdImpression: (PartnerAd) -> Unit,
    onAdClick: (PartnerAd) -> Unit,
    onAddVoiceMessage: (LocalVoiceMessage) -> Unit,
    onSos: () -> Unit,
    onVerifyDriver: () -> Unit,
    onNotifications: () -> Unit,
    onRouteWatch: (String?, String?) -> Unit = { _, _ -> },   // F13: открыть «Мои подписки» (опц. с маршрутом)
    onOpenChat: (Int, String, String) -> Unit,
    onOpenResponses: (Int) -> Unit = {},
    onCancelRequest: (Int) -> Unit = {},
    onEditRequest: (Int, String, String, Int, String) -> Unit = { _, _, _, _, _ -> },   // F3: правка заявки
    onSafety: () -> Unit,
    onSettings: () -> Unit,
    onPrivacy: () -> Unit,
    onTrust: () -> Unit,
    onConsents: () -> Unit,
    onHelp: () -> Unit,
    onReview: () -> Unit,
    onAdminReviews: () -> Unit,
    onAdminAds: () -> Unit,
    onPassengerCabinet: () -> Unit,
    onDriverCabinet: () -> Unit,
    onClinicRides: () -> Unit,   // F22: раздел «Поездки к клинике»
    onSimpleMode: () -> Unit,
    onTrustedContacts: () -> Unit,
    onCallbackHelp: () -> Unit,
    onAdsCabinet: () -> Unit,
    onFairness: () -> Unit = {},   // «Центр справедливости» — вход из профиля
    onMyStats: () -> Unit = {},
    onCoupons: () -> Unit = {},
    onPartnerCabinet: () -> Unit = {},
    onMyData: () -> Unit = {},
    onPromo: () -> Unit = {},
    onParcels: () -> Unit = {},
    onCourier: () -> Unit = {},
    onToggleLanguage: () -> Unit,
    onAccountDeleted: (Long) -> Unit = {},
    onInstantLogin: () -> Unit = {},
    onTaxiOnboarding: () -> Unit = {},   // §11: из заглушки «Такси скоро» водитель уходит в онбординг
    onOpenScheduled: () -> Unit = {},    // «На время»: предзаказ создан из встроенного такси → «Мои предзаказы»
    onSavedPlaces: () -> Unit = {},      // «Мои адреса»: дом, работа и свои места — из шторки заказа
    // Способ расчёта общий для всех заказов: живёт над экранами и переживает уходы с них.
    payMethod: String = PayMethods.CASH,
    onOpenPayments: () -> Unit = {},
    onCourierMode: () -> Unit = {},      // из режима «Курьер» — к работе курьера (заказы, линия, заработок)
    onSeasonalPublish: (String) -> Unit = {},   // F15: баннер «на праздник» → создать поездку с датой-шаблоном
    onTabChange: (HomeTab) -> Unit = {},
    onSelectTab: (HomeTab, () -> Unit) -> Unit = { _, commit -> commit() }
) {
    var ridesPresetTo by remember { mutableStateOf("") }
    var ridesPresetToday by remember { mutableStateOf(false) }

    // Шелл (таб-стейт + нижнее меню + смена вкладок) вынесен в чистый HomeShell — тестируется на JVM.
    // HomeScreen остаётся «умной» обёрткой: раздаёт данные/колбэки в тело конкретной вкладки.
    HomeShell(initialTab = initialTab, onTabChange = onTabChange, onSelectTab = onSelectTab) { tab, selectTab ->
            val gate = LocalHomeTabActionGate.current
            fun openRides(to: String = "", today: Boolean = false) {
                gate.select(HomeTab.Rides) {
                    ridesPresetTo = to
                    ridesPresetToday = today
                }
            }
            when (tab) {
                HomeTab.Map -> PassengerModeHome(
                    rides = rides,
                    activeTrip = activeTrip,
                    ads = ads,
                    adStats = adStats,
                    onBookRide = gate.guard(onBookRide),
                    onShareRide = onShareRide,
                    onAdImpression = onAdImpression,
                    onAdClick = onAdClick,
                    onSos = gate.guard(onSos),
                    onOpenPopular = { route -> openRides(to = route.to, today = true) },
                    onDriver = gate.guard(onCreateRide),
                    onBoost = gate.guard(onBoost),
                    onInstantLogin = gate.guard(onInstantLogin),
                    onTaxiOnboarding = gate.guard(onTaxiOnboarding),
                    onClinicRides = gate.guard(onClinicRides),
                    onRouteWatch = gate.guard(onRouteWatch),
                    onOpenScheduled = gate.guard(onOpenScheduled),
                    onSavedPlaces = gate.guard(onSavedPlaces),
                    payMethod = payMethod,
                    onOpenPayments = gate.guard(onOpenPayments),
                    onCourierMode = gate.guard(onCourierMode),
                    onSeasonalPublish = gate.guard(onSeasonalPublish),   // F15: баннер «на праздник» → создать поездку (с датой-шаблоном)
                )
                HomeTab.Rides -> RidesScreen(
                    rides = rides,
                    ads = ads,
                    adStats = adStats,
                    presetTo = ridesPresetTo,
                    presetToday = ridesPresetToday,
                    onBookRide = gate.guard(onBookRide),
                    onOpenBookingDetails = gate.guard(onOpenBookingDetails),
                    onOpenActiveTrip = gate.guard(onOpenActiveTrip),
                    onMessage = { selectTab(HomeTab.Chat) },
                    onShareRide = onShareRide,
                    onBoost = gate.guard(onBoost),
                    onCreateRequest = { selectTab(HomeTab.Request) },
                    onAdImpression = onAdImpression,
                    onAdClick = onAdClick,
                    onDriverCabinet = gate.guard(onDriverCabinet),
                )
                HomeTab.Request -> MyRequestsScreen(
                    requests = requests,
                    onCreateNew = gate.guard(onCreateRequest),
                    onViewResponses = gate.guard(onOpenResponses),   // открыть отклики ИМЕННО этой заявки (раньше терялся id → кидало на вкладку Чат)
                    onCancel = onCancelRequest,
                    loading = requestsLoading,
                    error = requestsError,
                    onRetry = onRetryRequests,
                    onEditRequest = onEditRequest,
                    onOpenRide = { dto -> gate.guard { onBookRide(dto.toUiRide()) }() }   // авто-подбор → открыть бронь поездки
                )
                HomeTab.Chat -> ChatScreen(
                    voiceMessages = voiceMessages,
                    onAddVoiceMessage = onAddVoiceMessage,
                    onNotifications = gate.guard(onNotifications),
                    onOpenChat = gate.guard(onOpenChat),
                    onOpenResponses = gate.guard(onOpenResponses)
                )
                HomeTab.Profile -> ProfileScreen(
                    ads = ads,
                    adStats = adStats,
                    onSupport = gate.guard(onSupport),
                    onVerifyDriver = gate.guard(onVerifyDriver),
                    onSafety = gate.guard(onSafety),
                    onSettings = gate.guard(onSettings),
                    onPrivacy = gate.guard(onPrivacy),
                    onTrust = gate.guard(onTrust),
                    onConsents = gate.guard(onConsents),
                    onHelp = gate.guard(onHelp),
                    onReview = gate.guard(onReview),
                    onAdminReviews = gate.guard(onAdminReviews),
                    onAdminAds = gate.guard(onAdminAds),
                    onPassengerCabinet = gate.guard(onPassengerCabinet),
                    onDriverCabinet = gate.guard(onDriverCabinet),
                    onSimpleMode = gate.guard(onSimpleMode),
                    onTrustedContacts = gate.guard(onTrustedContacts),
                    onCallbackHelp = gate.guard(onCallbackHelp),
                    onAdsCabinet = gate.guard(onAdsCabinet),
                    onFairness = gate.guard(onFairness),
                    onMyStats = gate.guard(onMyStats),
                    onCoupons = gate.guard(onCoupons),
                    onPromo = gate.guard(onPromo),
                    onParcels = gate.guard(onParcels),
                    onCourier = gate.guard(onCourier),
                    onPartnerCabinet = gate.guard(onPartnerCabinet),
                    onMyData = gate.guard(onMyData),
                    onToggleLanguage = onToggleLanguage,
                    onAccountDeleted = onAccountDeleted,
                    onAdImpression = onAdImpression,
                    onAdClick = onAdClick
                )
            }
    }
}

/**
 * Чистый шелл главного экрана: держит выбранную вкладку (переживает поворот), рисует Scaffold с нижним
 * меню (YuldashBottomBar) и анимированно переключает тело вкладки. Тело каждой вкладки приходит слотом
 * `tabContent(tab, selectTab)` — так HomeShell не знает про данные/сеть и тестируется на JVM (Robolectric).
 * Аппаратная «Назад» с любой вкладки, кроме Карты, возвращает на Карту. Смену вкладки прокидываем наверх
 * (onTabChange) — чтобы «Назад» с под-экранов возвращался на активную вкладку, а не на Карту.
 */
@Composable
internal fun HomeShell(
    initialTab: HomeTab,
    onTabChange: (HomeTab) -> Unit = {},
    onSelectTab: (HomeTab, () -> Unit) -> Unit = { _, commit -> commit() },
    tabContent: @Composable (tab: HomeTab, selectTab: (HomeTab) -> Unit) -> Unit,
) {
    var selectedTab by rememberSaveable(initialTab) { mutableStateOf(initialTab) }   // вкладка переживает поворот
    val shellScope = rememberCoroutineScope()
    var selectionEpoch by remember { mutableIntStateOf(0) }
    val callbackEpoch = selectionEpoch
    fun selectTab(tab: HomeTab, afterSelection: () -> Unit = {}) {
        if (shellScope.isActive && callbackEpoch == selectionEpoch) {
            onSelectTab(tab) {
                selectedTab = tab
                selectionEpoch++
                afterSelection()
            }
        }
    }
    LaunchedEffect(selectedTab) { onTabChange(selectedTab) }

    BackHandler(enabled = selectedTab != HomeTab.Map) {
        selectTab(HomeTab.Map)
    }

    // Пока идёт поиск или сама поездка, нижнее меню прячется. Заказ уже живой: случайный
    // переход в другой раздел только теряет контекст, а освободившееся место нужно карте.
    val taxiOrderOnScreen = NavSignals.taxiOrderOnScreen.value
    Scaffold(
        containerColor = CanonBg,
        bottomBar = {
            AnimatedVisibility(
                visible = !taxiOrderOnScreen,
                enter = fadeIn(tween(CanonMotion.NORMAL)),
                exit = fadeOut(tween(CanonMotion.QUICK)),
            ) {
                YuldashBottomBar(
                    selectedTab = selectedTab,
                    onSelect = { selectTab(it) }
                )
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding)) {
            // Полоска стоит НАД содержимым, а не поверх него: наложенная, она закрывала
            // переключатель сервисов, и тот торчал из-под неё краями.
            val renderedTripId = NavSignals.activeTaxiTrip.value
            ActiveTripBar(onOpen = {
                if (renderedTripId != 0 && renderedTripId == NavSignals.activeTaxiTrip.value &&
                    !NavSignals.taxiTripOnScreen.value) {
                    selectTab(HomeTab.Map) { NavSignals.openInstantOrder.value = true }
                }
            })
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    (fadeIn(animationSpec = tween(CanonMotion.NORMAL)) +
                        slideInVertically(animationSpec = tween(CanonMotion.NORMAL)) { it / 18 })
                        .togetherWith(fadeOut(animationSpec = tween(CanonMotion.QUICK)))
                },
                label = "homeTab"
            ) { tab ->
                val renderedEpoch = selectionEpoch
                val gate = HomeTabActionGate(
                    accepts = { shellScope.isActive && tab == selectedTab && renderedEpoch == selectionEpoch },
                    choose = { target, after -> selectTab(target, after) },
                )
                CompositionLocalProvider(LocalHomeTabActionGate provides gate) {
                    tabContent(tab) { target -> gate.guard { selectTab(target) }() }
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
        shadowElevation = CanonDepth.sheet
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()   // на жест-навигации иконки меню не уезжают под системную полосу
                .heightIn(min = 78.dp)
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.Top
        ) {
            YuldashBottomItem(
                selected = selectedTab == HomeTab.Map,
                label = appText("Карта", "Карта"),
                iconRes = R.drawable.yu_map_tab,
                onClick = { onSelect(HomeTab.Map) }
            )
            YuldashBottomItem(
                selected = selectedTab == HomeTab.Rides,
                label = appText("Поездки", "Сәфәрҙәр"),
                iconRes = R.drawable.yu_trip_list,
                onClick = { onSelect(HomeTab.Rides) }
            )
            YuldashBottomItem(
                selected = selectedTab == HomeTab.Request,
                label = appText("Заявка", "Ғариза"),
                iconRes = R.drawable.yu_request_add,
                onClick = { onSelect(HomeTab.Request) }
            )
            YuldashBottomItem(
                selected = selectedTab == HomeTab.Chat,
                label = appText("Чат", "Чат"),
                iconRes = R.drawable.yu_chat,
                onClick = { onSelect(HomeTab.Chat) }
            )
            YuldashBottomItem(
                selected = selectedTab == HomeTab.Profile,
                label = appText("Профиль", "Профиль"),
                iconRes = R.drawable.yu_profile,
                onClick = { onSelect(HomeTab.Profile) },
                badge = NavSignals.payNowDebtKop.value > 0
            )
        }
    }
}

@Composable
private fun RowScope.YuldashBottomItem(
    selected: Boolean,
    label: String,
    iconRes: Int,
    onClick: () -> Unit,
    badge: Boolean = false
) {
    val pillColor by animateColorAsState(if (selected) CanonGold else Color.Transparent, tween(CanonMotion.NORMAL), label = "navPill")
    val iconTint by animateColorAsState(if (selected) CanonGoldInk else CanonMuted, tween(CanonMotion.NORMAL), label = "navTint")
    val labelColor by animateColorAsState(if (selected) CanonGreen else CanonMutedStrong, tween(CanonMotion.NORMAL), label = "navLabel")
    val iconScale by animateFloatAsState(if (selected) 1.12f else 1f, tween(CanonMotion.NORMAL), label = "navScale")
    val badgeScale by animateFloatAsState(if (badge) 1f else 0f, tween(CanonMotion.QUICK), label = "navBadge")
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .weight(1f)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics(mergeDescendants = true) { this.selected = selected; role = Role.Tab }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box {
            Surface(
                color = pillColor,
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(
                    painterResource(iconRes),
                    contentDescription = label,
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .size(21.dp)
                        .graphicsLayer { scaleX = iconScale; scaleY = iconScale },
                    tint = iconTint
                )
            }
            // Точка «загляни сюда»: срочный долг за дальнюю поездку. Живёт на вкладке, а не
            // только в кабинете, потому что пуш может не дойти вовсе — уведомления выключены,
            // нет устройства, антишторм. Без неё водитель зашёл бы в приложение, не увидел
            // требования и всё равно считался бы предупреждённым.
            if (badgeScale > 0.01f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 2.dp, end = 8.dp)
                        .size(9.dp)
                        .graphicsLayer { scaleX = badgeScale; scaleY = badgeScale }
                        .background(CanonRed, CircleShape)
                        .border(2.dp, CanonSurface, CircleShape)
                )
            }
        }
        Text(
            text = label,
            color = labelColor,
            style = CanonMicro,
            modifier = Modifier.fillMaxWidth(),
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
