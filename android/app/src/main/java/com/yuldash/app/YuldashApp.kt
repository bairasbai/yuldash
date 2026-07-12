package com.yuldash.app

// Корень навигации: YuldashApp (when(screen)) + HomeScreen (Scaffold+вкладки) + нижнее меню.
// Вынесено из MainActivity (Фаза 3). Импорты целиком — лишние = варнинги.

import com.yuldash.app.R
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.lifecycle.viewmodel.compose.viewModel
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

internal fun bookingStatusAllowsActiveTrip(status: String): Boolean =
    status == "confirmed" || status == "onboard" || status == "done"

internal fun bookingStatusAllowsBoarding(status: String): Boolean =
    status == "confirmed" || status == "onboard"

@Composable
internal fun YuldashApp() {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("yuldash_prefs", android.content.Context.MODE_PRIVATE)
    }
    // Всё состояние приложения живёт в YuldashViewModel (вынесено из god-composable).
    val vm: YuldashViewModel = viewModel()
    val rides = vm.rides
    val trustedContacts = vm.trustedContacts
    val localRequests = vm.localRequests
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
    // Сессия протухла на сервере (refresh мёртв) → не оставляем пустые экраны: говорим и уводим на вход.
    val sessionExpiredMsg = appText("Сессия истекла. Войди снова.", "Сессия тамамланды. Ҡабат кер.")
    LaunchedEffect(Unit) {
        ApiClient.sessionExpired.collect { expired ->
            if (expired) {
                Toast.makeText(context, sessionExpiredMsg, Toast.LENGTH_LONG).show()
                screen = Screen.Login
                ApiClient.sessionExpired.value = false
            }
        }
    }
    var selectedRide by vm.selectedRide
    var startHomeTab by vm.startHomeTab
    var callbackRequested by vm.callbackRequested
    var responsesRequestId by vm.responsesRequestId   // какую заявку открыть в «Откликах»
    var driverProfileId by rememberSaveable { mutableStateOf(0) }   // чей публичный профиль открыть (0 = никакой)
    var createRideReturnScreen by rememberSaveable { mutableStateOf(Screen.Home) }
    var createRideReturnHomeTab by rememberSaveable { mutableStateOf(HomeTab.Request) }
    var trustedContactsReturnScreen by rememberSaveable { mutableStateOf(Screen.SimpleMode) }
    var trustedContactsReturnHomeTab by rememberSaveable { mutableStateOf(HomeTab.Profile) }
    var selectedBookingStatus by rememberSaveable { mutableStateOf("") }
    var instantTripOrderId by rememberSaveable { mutableStateOf(0) }   // «Быстрый заказ»: id заказа для экрана поездки водителя
    var instantChatOrderId by rememberSaveable { mutableStateOf(0) }   // чат такси-заказа (B7b-1): id заказа
    var sosOrderId by rememberSaveable { mutableStateOf(0) }           // SOS с контекстом такси-заказа (B7b-2); 0 = без заказа
    // F13 «карауль поездку»: предзаполнение экрана «Мои подписки» маршрутом из карты (может быть пустым).
    var routeWatchPrefillFrom by rememberSaveable { mutableStateOf("") }
    var routeWatchPrefillTo by rememberSaveable { mutableStateOf("") }
    // Роль админа (Александр): показывает инструмент «Заявка за пользователя» в Настройках.
    var isAdmin by vm.isAdmin
    // Версия сессии: инкрементится при входе (onContinue), чтобы user-специфичные загрузки
    // (роль/мои заявки/доверенные контакты) перечитались ПОСЛЕ логина, а не только один раз на Splash
    // (иначе после входа в этой же сессии эти данные оставались пустыми до перезапуска приложения).
    var sessionVersion by remember { mutableStateOf(0) }
    LaunchedEffect(sessionVersion) { ApiClient.me().onSuccess { isAdmin = it.optString("role") == "admin" } }
    // Android 13+ требует РАНТАЙМ-разрешение на уведомления — без него пуши тихо не показываются
    // (FCM настроен end-to-end, но без этого запроса доставка на новых телефонах = no-op).
    // Просим один раз, когда пользователь уже в приложении (не на онбординге/входе).
    // Язык дублируем на диск (AppPrefs): FCM и фоновые сервисы живут вне Compose и берут его оттуда.
    LaunchedEffect(language) { AppPrefs.setLanguage(context, language) }
    // Полноэкранный оффер такси (B7a-2): тап/фуллскрин уведомления «Новый заказ» → MainActivity
    // ставит NavSignals → открываем кабинет водителя (там InstantOfferOverlay). Ждём, пока сплэш
    // отработает (он перезаписал бы screen), и не дёргаем навигацию на входе/онбординге.
    val wantDriverCabinet by NavSignals.openDriverCabinet
    LaunchedEffect(wantDriverCabinet, screen) {
        if (!wantDriverCabinet) return@LaunchedEffect
        if (screen == Screen.Splash || screen == Screen.Intro || screen == Screen.Onboarding) return@LaunchedEffect
        NavSignals.openDriverCabinet.value = false
        if (ApiClient.isLoggedIn() && screen != Screen.InstantDriverTrip) screen = Screen.DriverCabinet
    }
    // Кнопки «Написать»/SOS живут глубоко в экранах такси (в т.ч. встроенных в главную) —
    // навигация через NavSignals (паттерн openDriverCabinet), без колбэков через все слои.
    val wantInstantChat by NavSignals.openInstantChat
    LaunchedEffect(wantInstantChat) {
        if (wantInstantChat > 0 && ApiClient.isLoggedIn()) {
            instantChatOrderId = wantInstantChat
            NavSignals.openInstantChat.value = 0
            screen = Screen.InstantChat
        }
    }
    val wantSosForOrder by NavSignals.openSosForOrder
    LaunchedEffect(wantSosForOrder) {
        if (wantSosForOrder > 0) {
            sosOrderId = wantSosForOrder
            NavSignals.openSosForOrder.value = 0
            screen = Screen.Sos
        }
    }
    // Пуш о ходе такси-заказа (B9b-2): тап по «Водитель найден / Машина на месте / …» →
    // экран заказа пассажира (сам подхватывает активный заказ). Ждём, пока сплэш отработает.
    val wantInstantOrder by NavSignals.openInstantOrder
    LaunchedEffect(wantInstantOrder, screen) {
        if (!wantInstantOrder) return@LaunchedEffect
        if (screen == Screen.Splash || screen == Screen.Intro || screen == Screen.Onboarding) return@LaunchedEffect
        NavSignals.openInstantOrder.value = false
        if (ApiClient.isLoggedIn()) screen = Screen.InstantOrder
    }
    // Force-update (B9b-1): при старте ПАРАЛЛЕЛЬНО обычному запуску спрашиваем /version/min.
    // versionCode < min с сервера → блокирующий экран «Обнови Юлдаш» (ниже, поверх всего).
    // Офлайн / ошибка ручки / min=0 → НИЧЕГО не блокируем, приложение стартует как обычно.
    var forceUpdateRequired by rememberSaveable { mutableStateOf(false) }
    var forceUpdateStoreUrl by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) {
        ApiClient.minAppVersion().onSuccess { o ->
            val min = o.optInt("min_version_code", 0)
            if (min > 0 && BuildConfig.VERSION_CODE < min) {
                forceUpdateStoreUrl = o.optString("store_url", "")
                forceUpdateRequired = true
            }
        }
    }
    val notifPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var notifAsked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(screen) {
        if (!notifAsked && (screen == Screen.Home || screen == Screen.DriverCabinet) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifAsked = true
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    // F16 deep-link: пришли по ссылке yulbash.ru/r/{id} → тянем публичную витрину поездки
    // и открываем её карточку в приложении. Не нашлась/ошибка сети → тихо остаёмся где были
    // (ссылка всё равно открыла приложение). Реагируем и на холодный старт, и на новую ссылку.
    LaunchedEffect(DeepLink.pendingRideId.value) {
        val rideId = DeepLink.pendingRideId.value ?: return@LaunchedEffect
        DeepLink.pendingRideId.value = null   // одноразово — не переоткрываем при рекомпозиции
        ApiClient.getRide(rideId).onSuccess { dto ->
            selectedRide = dto.toUiRide()
            activeBookingId = null
            selectedBookingStatus = ""
            screen = Screen.Booking
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
    LaunchedEffect(screen, language, startHomeTab) { vm.persistNav() }

    // Лёгкий back-stack: трейл экранов, чтобы аппаратная «Назад» возвращалась по нему, а не прыгала на Home.
    val navHistory = vm.navHistory
    var navPopping by vm.navPopping
    var navPrev by vm.navPrev
    LaunchedEffect(screen) {
        val transient = navPrev == Screen.Splash || navPrev == Screen.Login || navPrev == Screen.Onboarding || navPrev == Screen.Intro
        if (!navPopping && screen != navPrev && !transient) navHistory.add(navPrev)   // forward → запоминаем, откуда пришли (Intro/Splash/Login/Onboarding — не в трейл)
        navPopping = false
        navPrev = screen
    }

    // После kill/restore: screen сохранён, но транзитные selectedRide/activeBookingId — нет.
    // Если восстановились на экране брони/активной поездки без данных → на Home (без краша/пустоты).
    LaunchedEffect(Unit) {
        if ((screen == Screen.Booking || screen == Screen.ActiveTrip) && selectedRide == null && activeBookingId == null) {
            screen = Screen.Home
        }
    }

    fun finishOnboarding(role: RideRole) {
        // Сохраняем выбор роли (раньше выбор был «мёртвым» — никуда не уходил).
        prefs.edit().putBoolean("onboarding_completed", true).putString("preferred_role", role.name).apply()
        Analytics.log("onboarding_complete", mapOf("role" to role.name))   // воронка: дошёл до конца онбординга
        startHomeTab = if (role == RideRole.Driver) HomeTab.Rides else HomeTab.Map
        screen = Screen.Login
    }

    fun openSos(orderId: Int = 0) {
        sosOrderId = orderId   // контекст такси-заказа (0 = обычный SOS) — не даём протечь старому
        screen = Screen.Sos
    }
    fun openHome(tab: HomeTab = HomeTab.Map) {
        navHistory.clear()          // Home = корень: сбрасываем трейл, чтобы «Назад» не возвращал в завершённые под-потоки
        navPopping = true           // сам переход-на-Home в историю не пишем
        startHomeTab = tab
        screen = Screen.Home
    }
    // Единый пошаговый «Назад» (верхняя стрелка И аппаратная кнопка): снимаем последний экран трейла.
    // Дошли до Home / трейл пуст → Home на ПОСЛЕДНЕЙ вкладке (startHomeTab синхронён с активной вкладкой Home).
    fun goBack() {
        val prev = navHistory.removeLastOrNull()
        if (prev != null && prev != screen && prev != Screen.Home) { navPopping = true; screen = prev }
        else openHome(startHomeTab)
    }
    fun openCreateRide(returnScreen: Screen = Screen.Home, returnHomeTab: HomeTab = HomeTab.Request) {
        createRideReturnScreen = returnScreen
        createRideReturnHomeTab = returnHomeTab
        screen = Screen.CreateRide
    }
    fun closeCreateRide() {
        if (createRideReturnScreen == Screen.Home) openHome(createRideReturnHomeTab) else screen = createRideReturnScreen
    }
    fun openTrustedContacts(returnScreen: Screen = Screen.SimpleMode, returnHomeTab: HomeTab = HomeTab.Profile) {
        trustedContactsReturnScreen = returnScreen
        trustedContactsReturnHomeTab = returnHomeTab
        screen = Screen.TrustedContacts
    }
    fun closeTrustedContacts() {
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
        LaunchedEffect(sessionVersion) {
            ApiClient.getMyRequests().onSuccess { reqs ->
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
                            passenger = r.forRelativeName ?: ApiClient.cachedName() ?: "Я",
                            status = reqWaitingStatus,
                            price = r.maxPrice,
                            trustedContact = r.comment.ifBlank { null },
                            serverId = r.id,
                        )
                    }
                )
            }
        }
        // Доверенные контакты — с сервера (после входа). Перечитываем и при смене sessionVersion (после логина).
        LaunchedEffect(sessionVersion) {
            ApiClient.getContacts().onSuccess { list ->
                if (list.isNotEmpty()) {
                    trustedContacts.clear()
                    trustedContacts.addAll(list.map { c -> TrustedContact(c.name, c.relation, c.phone, c.notifyByDefault, c.id) })
                }
            }
        }
        BackHandler(enabled = screen != Screen.Onboarding && screen != Screen.Login && screen != Screen.Home && screen != Screen.Splash && screen != Screen.Intro) {
            goBack()   // единый пошаговый возврат по трейлу — та же логика, что верхняя стрелка «Назад»
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
                // Зелёный «мост» — продолжение СИСТЕМНОГО сплэша, БЕЗ повторной анимации лого.
                // Это убирает «дубль» (раньше Compose-сплэш заново анимировал то же лого поверх системного).
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0B6B3A), Color(0xFF073F25)))))
                // Первый запуск → брендовое интро; повтор → быстро в приложение (без второго лого).
                LaunchedEffect(Unit) { delay(if (splashTarget == Screen.Intro) 60 else 140); screen = splashTarget }
            }
            Screen.Intro -> IntroScreen(onComplete = { screen = Screen.Onboarding })
            Screen.Onboarding -> OnboardingScreen(
                onFinish = ::finishOnboarding,
                language = language,
                onSelectLanguage = { language = it }
            )
            Screen.Login -> {
                LoginScreen(
                    currentLanguage = language,
                    onToggleLanguage = {
                        language = if (language == AppLanguage.Ru) AppLanguage.Ba else AppLanguage.Ru
                    },
                    onContinue = {
                        sessionVersion++   // вход завершён → перечитать роль/мои заявки/контакты под новым токеном
                        if (prefs.getString("preferred_role", "") == RideRole.Driver.name) {
                            startHomeTab = HomeTab.Profile   // назад из кабинета водителя → профиль
                            screen = Screen.DriverCabinet     // выбрал «Я водитель» → сразу в кабинет (проверка/публикация)
                        } else openHome()
                    },
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
                onTabChange = { startHomeTab = it },   // «Назад» с под-экранов вернётся на активную вкладку Home
                onCreateRide = { openCreateRide(returnScreen = Screen.Home, returnHomeTab = HomeTab.Request) },
                onCreateRequest = { screen = Screen.CreateRequest },
                onSupport = { screen = Screen.Support },
                onMyStats = { screen = Screen.MyStats },
                onCoupons = { screen = Screen.Coupons },
                onPromo = { screen = Screen.PromoCode },
                onParcels = { screen = Screen.Parcels },
                onCourier = { screen = Screen.Courier },
                onPartnerCabinet = { screen = Screen.PartnerCabinet },
                onReview = { screen = Screen.AppReview },
                onAdminReviews = { screen = Screen.AdminReviews },
                onAdminAds = { screen = Screen.AdminAds },
                onBoost = { screen = Screen.Boost },
                onPublishRide = { ride ->
                    rides.add(0, ride)
                    Toast.makeText(context, if (language == AppLanguage.Ba) "Заявка баҫтырылды" else "Заявка опубликована", Toast.LENGTH_SHORT).show()
                },
                onBookRide = { ride ->
                    selectedRide = ride
                    activeBookingId = null
                    selectedBookingStatus = ""
                    screen = Screen.Booking
                },
                onOpenBookingDetails = { ride, status ->
                    selectedRide = ride
                    activeTrip = null
                    activeBookingId = ride.id.toIntOrNull()
                    selectedBookingStatus = status
                    screen = Screen.Booking
                },
                onOpenActiveTrip = { ride, status ->
                    selectedRide = ride
                    // Живое гео гейтится на activeTrip != null (см. эффект TripLocationService выше):
                    // для подтверждённой поездки, открытой из списка, ставим activeTrip = ride, иначе
                    // сервис заглохнет и попутчик не увидит позицию. Для неактивной брони — null (как было).
                    activeTrip = if (bookingStatusAllowsActiveTrip(status)) ride else null
                    activeBookingId = ride.id.toIntOrNull()
                    selectedBookingStatus = status
                    screen = if (bookingStatusAllowsActiveTrip(status)) Screen.ActiveTrip else Screen.Booking
                },
                onShareRide = { ride ->
                    val rideTime = if (language == AppLanguage.Ba) ride.timeBa ?: ride.time else ride.time
                    // Красивая расшариваемая ссылка: откроется в приложении (deep-link) либо покажет
                    // веб-превью с OG-карточкой в WhatsApp/Telegram. Хост — из конфига, не localhost.
                    val link = "${BuildConfig.YULDASH_WEB_BASE_URL.trimEnd('/')}/r/${ride.id}"
                    val shareText = if (language == AppLanguage.Ba) {
                        "Юлдаш: ${ride.from} → ${ride.to}, $rideTime, йөрөтөүсе ${ride.driver}, ${ride.price} ₽, буш урын: ${ride.seats}.\n$link"
                    } else {
                        "Юлдаш: ${ride.from} → ${ride.to}, $rideTime, водитель ${ride.driver}, ${ride.price} ₽, свободно ${ride.seats} места.\n$link"
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
                onSos = { openSos() },
                onVerifyDriver = { screen = Screen.VerifyDriver },
                onTaxiOnboarding = { screen = Screen.TaxiOnboarding },
                onNotifications = { screen = Screen.Notifications },
                onRouteWatch = { from, to ->
                    routeWatchPrefillFrom = from ?: ""
                    routeWatchPrefillTo = to ?: ""
                    screen = Screen.RouteWatches
                },
                onOpenChat = { bid, peer, route ->
                    val parts = route.split("→").map { it.trim() }
                    selectedRide = Ride(id = bid.toString(), from = parts.getOrElse(0) { "" }, to = parts.getOrElse(1) { "" }, time = "", driver = peer, car = "", price = 0, seats = 1, rating = 0.0, verified = false, boosted = false)
                    activeBookingId = bid
                    selectedBookingStatus = ""
                    screen = Screen.ActiveTrip
                },
                onOpenResponses = { id -> responsesRequestId = id; screen = Screen.RequestResponses },
                onCancelRequest = { id ->
                    // Отмена заявки: успех — по факту сервера (убираем из списка), при сбое — серверная причина
                    // (matched-заявку нельзя отменить тут → бэк вернёт понятный текст).
                    appScope.launch {
                        ApiClient.cancelRequest(id)
                            .onSuccess {
                                localRequests.removeAll { it.serverId == id }
                                Toast.makeText(context, if (language == AppLanguage.Ba) "Заявка кире алынды" else "Заявка отменена", Toast.LENGTH_SHORT).show()
                            }
                            .onFailure { e ->
                                Toast.makeText(context, (e as? com.yuldash.app.data.ApiException)?.message ?: if (language == AppLanguage.Ba) "Булманы. Ҡабатла" else "Не получилось. Повтори", Toast.LENGTH_LONG).show()
                            }
                    }
                },
                onSafety = { screen = Screen.Safety },
                onSettings = { screen = Screen.Settings },
                onPrivacy = { screen = Screen.Privacy },
                onTrust = { if (ApiClient.isLoggedIn()) screen = Screen.Trust else screen = Screen.Login },
                onConsents = { if (ApiClient.isLoggedIn()) screen = Screen.Consents else screen = Screen.Login },
                onHelp = { screen = Screen.Help },
                onPassengerCabinet = { prefs.edit().putString("preferred_role", RideRole.Passenger.name).apply(); screen = Screen.PassengerCabinet },
                onDriverCabinet = { prefs.edit().putString("preferred_role", RideRole.Driver.name).apply(); screen = Screen.DriverCabinet },
                onClinicRides = { screen = Screen.ClinicRides },
                onSimpleMode = { screen = Screen.SimpleMode },
                onTrustedContacts = { openTrustedContacts(returnScreen = Screen.Home, returnHomeTab = HomeTab.Profile) },
                onCallbackHelp = { screen = Screen.CallbackHelp },
                onAdsCabinet = { screen = Screen.AdsCabinet },
                onToggleLanguage = {
                    language = if (language == AppLanguage.Ru) AppLanguage.Ba else AppLanguage.Ru
                },
                onAccountDeleted = { isAdmin = false; startHomeTab = HomeTab.Map; screen = Screen.Login },
                onInstantLogin = { screen = Screen.Login }   // такси требует входа → на экран входа
            )
            Screen.CreateRide -> CreateRideScreen(
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
            Screen.Booking -> BookingScreen(
                ride = selectedRide ?: rides.firstOrNull() ?: demoRides.first(),   // фоллбэк вместо краша на пустом списке
                bookingId = activeBookingId,
                ads = partnerAds,
                adStats = adStats,
                onBack = { goBack() },
                onSelectTab = { tab -> openHome(tab) },
                onMessage = { openHome(HomeTab.Chat) },
                onAdImpression = ::trackAdImpression,
                onAdClick = ::trackAdClick,
                canOpenActiveTrip = activeBookingId == null || bookingStatusAllowsActiveTrip(selectedBookingStatus),
                onConfirmRide = { payMethod, payAmount ->
                    if (activeBookingId != null) {
                        activeTrip = selectedRide
                        screen = Screen.ActiveTrip
                    } else {
                        val rid = selectedRide?.id?.toIntOrNull()
                        if (rid != null) {
                            appScope.launch {
                                ApiClient.book(rid, 1, payMethod, payAmount)
                                    .onSuccess { bid -> activeBookingId = bid; selectedBookingStatus = "confirmed"; activeTrip = selectedRide; screen = Screen.ActiveTrip }
                                    .onFailure { Toast.makeText(context, if (language == AppLanguage.Ba) "Бронләп булманы. Ҡабатла." else "Не удалось забронировать. Повтори.", Toast.LENGTH_SHORT).show() }
                            }
                        }
                    }
                }
            )
            Screen.ActiveTrip -> ActiveTripScreen(
                ride = selectedRide,
                contacts = trustedContacts,
                bookingId = activeBookingId,
                onBack = { goBack() },
                onTripEnd = { activeTrip = null; openHome(HomeTab.Map) },
                onSos = { openSos() },
                onSupport = { screen = Screen.Support }
            )
            Screen.Sos -> SosScreen(
                onBack = { goBack() },
                onLoginRequired = { screen = Screen.Login },
                orderId = sosOrderId.takeIf { it > 0 }   // контекст такси-заказа (B7b-2); 0 = обычный SOS
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
                    selectedRide = Ride(id = bid.toString(), from = "", to = "", time = "", driver = "", car = "", price = 0, seats = 1, rating = 0.0, verified = false, boosted = false)
                    activeBookingId = bid
                    selectedBookingStatus = ""
                    screen = Screen.Booking
                },
                // Тап по «отклик на заявку» → экран откликов этой заявки.
                onOpenResponses = { rid -> responsesRequestId = rid; screen = Screen.RequestResponses },
                onRouteWatches = { routeWatchPrefillFrom = ""; routeWatchPrefillTo = ""; screen = Screen.RouteWatches }
            )
            Screen.RouteWatches -> RouteWatchesScreen(
                onBack = { goBack() },
                prefillFrom = routeWatchPrefillFrom,
                prefillTo = routeWatchPrefillTo,
            )
            Screen.Privacy -> PrivacyScreen(onBack = { goBack() })
            Screen.Rules -> RulesScreen(onBack = { goBack() })
            Screen.PaymentInfo -> PaymentInfoScreen(onBack = { goBack() })
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
                onPayments = { screen = Screen.PaymentInfo },
                onFilters = { screen = Screen.Filters },
                isAdmin = isAdmin,
                onAdminCabinet = { screen = Screen.AdminCabinet },
                onLogout = { ApiClient.logout(); isAdmin = false; screen = Screen.Login }
            )
            Screen.AdminCabinet -> AdminCabinetScreen(
                onBack = { goBack() },
                onAdminRequest = { screen = Screen.AdminRequest },
                onAdminResponses = { screen = Screen.AdminResponses },
                onAds = { screen = Screen.AdsCabinet },
                onDrivers = { screen = Screen.AdminDrivers },
                onReports = { screen = Screen.AdminReports },
                onPaymentRequests = { screen = Screen.AdminPaymentRequests },
                onTaxi = { screen = Screen.AdminTaxi },
                onWaitlist = { screen = Screen.AdminWaitlist },
                onTaxiPulse = { screen = Screen.AdminTaxiPulse },
                onPartners = { screen = Screen.AdminPartners },
                onPromoAdmin = { screen = Screen.AdminPromo },
                onParcelsAdmin = { screen = Screen.AdminParcels },
                onCourierAdmin = { screen = Screen.AdminCourier },
                onIncomeCalc = { screen = Screen.IncomeCalculator }
            )
            Screen.IncomeCalculator -> IncomeCalculatorScreen(onBack = { goBack() })
            Screen.AdminDrivers -> AdminDriversScreen(onBack = { goBack() })
            Screen.AdminReports -> AdminReportsScreen(onBack = { goBack() })
            Screen.AdminPaymentRequests -> AdminPaymentRequestsScreen(onBack = { goBack() })
            Screen.AdminRequest -> AdminRequestScreen(onBack = { goBack() })
            Screen.AdminResponses -> AdminResponsesScreen(onBack = { goBack() })
            Screen.Help -> HelpScreen(
                ads = partnerAds,
                adStats = adStats,
                onBack = { goBack() },
                onSelectTab = { tab -> openHome(tab) },
                onAdImpression = ::trackAdImpression,
                onAdClick = ::trackAdClick
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
                onInstantOrder = { if (ApiClient.isLoggedIn()) screen = Screen.InstantOrder else screen = Screen.Login },
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
                onTaxiOnboarding = { screen = Screen.TaxiOnboarding }
            )
            Screen.InstantOrder -> InstantOrderScreen(
                onBack = { goBack() },
                onLoginRequired = { screen = Screen.Login },
                onTaxiOnboarding = { screen = Screen.TaxiOnboarding }
            )
            Screen.InstantDriverTrip -> InstantDriverTripScreen(
                orderId = instantTripOrderId,
                onBack = { goBack() },
                onFinished = { screen = Screen.DriverCabinet }
            )
            Screen.InstantChat -> InstantChatScreen(
                orderId = instantChatOrderId,
                onBack = { goBack() }
            )
            Screen.TaxiOnboarding -> TaxiOnboardingScreen(
                onBack = { goBack() },
                onOpenDriverCabinet = { screen = Screen.DriverCabinet }
            )
            Screen.AdminTaxi -> AdminTaxiScreen(onBack = { goBack() })
            Screen.AdminWaitlist -> AdminWaitlistScreen(onBack = { goBack() })
            Screen.AdminTaxiPulse -> AdminTaxiPulseScreen(onBack = { goBack() })
            Screen.RequestsFeed -> RequestsFeedScreen(onBack = { goBack() })
            Screen.RequestResponses -> ResponsesScreen(
                requestId = responsesRequestId,
                onBack = { goBack() },
                onAccepted = { bid -> activeBookingId = bid; activeTrip = null; screen = Screen.ActiveTrip }
            )
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
                onVoiceRequest = { if (ApiClient.isLoggedIn()) screen = Screen.VoiceRequest else screen = Screen.Login },
                onFamilyOrder = { if (ApiClient.isLoggedIn()) screen = Screen.FamilyOrder else screen = Screen.Login },
                onTrustedContacts = { if (ApiClient.isLoggedIn()) openTrustedContacts(returnScreen = Screen.SimpleMode) else screen = Screen.Login },
                onRepeatTrip = { if (ApiClient.isLoggedIn()) screen = Screen.RepeatTrip else screen = Screen.Login },
                onCallbackHelp = { if (ApiClient.isLoggedIn()) screen = Screen.CallbackHelp else screen = Screen.Login },
                onSos = { openSos() },
                onChat = { if (ApiClient.isLoggedIn()) openHome(HomeTab.Chat) else screen = Screen.Login }
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
                onBack = { goBack() },
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
                onLoginRequired = { screen = Screen.Login },
                onRepeat = { request ->
                    localRequests.add(0, request)
                    Toast.makeText(context, if (language == AppLanguage.Ba) "Йыш сәфәр ҡабатланды" else "Частая поездка повторена", Toast.LENGTH_SHORT).show()
                    goBack()
                }
            )
            Screen.CallbackHelp -> CallbackHelpScreen(
                requested = callbackRequested,
                onBack = { goBack() },
                onRequest = { note ->
                    // Ждём ответ сервера: «заявка создана» показываем по факту, при сбое — честная ошибка (не ложный успех).
                    callbackRequested = true
                    appScope.launch {
                        ApiClient.requestCallback(note)
                            .onSuccess { Toast.makeText(context, if (language == AppLanguage.Ba) "Шылтыратыу заявкаһы булдырылды" else "Заявка на звонок создана", Toast.LENGTH_SHORT).show() }
                            .onFailure {
                                callbackRequested = false
                                Toast.makeText(context, if (language == AppLanguage.Ba) "Булманы. Сетте тикшереп ҡабатла" else "Не получилось. Проверь сеть и повтори", Toast.LENGTH_SHORT).show()
                            }
                    }
                }
            )
            Screen.MyStats -> MyStatsScreen(onBack = { goBack() })
            Screen.AppReview -> AppReviewScreen(onBack = { goBack() })
            Screen.AdminReviews -> AdminReviewsScreen(onBack = { goBack() })
            Screen.AdminAds -> AdminAdsScreen(onBack = { goBack() })
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
            Screen.AdminPartners -> AdminPartnersScreen(onBack = { goBack() })
            Screen.PromoCode -> PromoCodeScreen(onBack = { goBack() })
            Screen.AdminPromo -> AdminPromoScreen(onBack = { goBack() })
            Screen.Parcels -> ParcelsScreen(onBack = { goBack() })
            Screen.AdminParcels -> AdminParcelsScreen(onBack = { goBack() })
            Screen.CourierOnboarding -> CourierOnboardingScreen(
                onBack = { goBack() },
                onOpenCourier = { screen = Screen.Courier },
            )
            Screen.Courier -> CourierScreen(
                onBack = { goBack() },
                onBecomeCourier = { screen = Screen.CourierOnboarding },
            )
            Screen.AdminCourier -> AdminCourierScreen(onBack = { goBack() })
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
private fun OnboardingScreen(onFinish: (RideRole) -> Unit, language: AppLanguage, onSelectLanguage: (AppLanguage) -> Unit) {
    // Слайды и воронка-эффект живут в обёртке (side-effect), вся разметка — в чистом OnboardingContent.
    val slides = remember { onboardingSlides() }
    LaunchedEffect(Unit) { Analytics.log("onboarding_start") }   // воронка: начало онбординга (с этим виден отвал внутри онбординга)
    OnboardingContent(
        slides = slides,
        language = language,
        onSelectLanguage = onSelectLanguage,
        onFinish = onFinish,
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
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp),
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
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(bottom = 14.dp)
                ) {
                    item { Spacer(Modifier.height(8.dp)) }
                    item { OnboardingHeroCard(slide) { (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction } }
                    item {
                        Text(
                            text = appText(slide.titleRu, slide.titleBa),
                            modifier = Modifier.onbAppear(0, played),
                            color = CanonText,
                            fontSize = 30.sp,
                            lineHeight = 33.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                    item {
                        Text(
                            text = appText(slide.bodyRu, slide.bodyBa),
                            modifier = Modifier.onbAppear(1, played),
                            color = CanonMuted,
                            fontSize = 17.sp,
                            lineHeight = 24.sp,
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
                    TextButton(onClick = { onFinish(role) }) {
                        Text(appText("Пропустить", "Үткәреп ебәреү"), color = CanonGreen2, fontWeight = FontWeight.Black)
                    }
                }
                Spacer(Modifier.height(10.dp))
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
                        .height(58.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)
                ) {
                    Text(
                        text = if (isLastPage) appText("Войти через Telegram", "Telegram аша инеү") else appText("Далее", "Артабан"),
                        fontWeight = FontWeight.Black,
                        fontSize = 18.sp
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
        Row(Modifier.padding(2.dp)) {
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
            .background(if (active) CanonGreen2 else Color.Transparent)
            .padding(horizontal = 11.dp, vertical = 4.dp)
    ) {
        Text(text, color = if (active) Color.White else CanonMuted, fontSize = 11.sp, fontWeight = FontWeight.Black)
    }
}

/** Спокойное появление элемента: fade + лёгкий сдвиг вверх, стаггер по index. Играет, когда слайд стал активным. */
@Composable
private fun Modifier.onbAppear(index: Int, play: Boolean): Modifier {
    val a by animateFloatAsState(if (play) 1f else 0f, tween(durationMillis = 430, delayMillis = if (play) index * 75 else 0), label = "onbA")
    val ty by animateFloatAsState(if (play) 0f else 34f, tween(durationMillis = 430, delayMillis = if (play) index * 75 else 0), label = "onbY")
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
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(16.dp),
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
        }

        Surface(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 18.dp, end = 92.dp, bottom = 18.dp)
                .graphicsLayer { translationX = pageOffset() * 62f },
            color = Color.White.copy(alpha = 0.18f),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.30f))
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Юлдаш",
                    color = Color.White,
                    fontSize = 24.sp,
                    lineHeight = 26.sp,
                    fontWeight = FontWeight.Black
                )
                Text(
                    text = appText(slide.eyebrowRu, slide.eyebrowBa),
                    color = Color.White.copy(alpha = 0.94f),
                    fontSize = 16.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 18.dp, bottom = 22.dp)
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
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            OnboardingIconBubble(item.icon, index)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(appText(item.titleRu, item.titleBa), color = CanonText, fontWeight = FontWeight.Black, fontSize = 18.sp, lineHeight = 21.sp)
                Text(appText(item.bodyRu, item.bodyBa), color = CanonMuted, fontSize = 15.sp, lineHeight = 20.sp)
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
internal fun OnboardingRoleCard(
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
internal fun OnboardingTrustStrip() {
    Card(colors = CardDefaults.cardColors(containerColor = CanonSurface), shape = CanonItemShape, elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
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
        Spacer(Modifier.height(6.dp))
        Text(label, color = CanonText, textAlign = TextAlign.Center, fontWeight = FontWeight.Black, fontSize = 12.sp, lineHeight = 13.sp, minLines = 2, maxLines = 2)
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
                    Text(index.toString(), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Black)
                }
            }
        }
    }
}

@Composable
internal fun OnboardingSafetyNote(text: String) {
    Surface(color = CanonMint, shape = CanonItemShape, border = BorderStroke(1.dp, CanonHairlineGreen)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Lock, contentDescription = null, tint = CanonGreen2)
            Spacer(Modifier.width(12.dp))
            Text(text, color = CanonGreen2, fontWeight = FontWeight.Black, fontSize = 15.sp, lineHeight = 19.sp)
        }
    }
}

@Composable
internal fun OnboardingDots(count: Int, selected: Int, modifier: Modifier = Modifier) {
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
internal fun HomeScreen(
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
    onMyStats: () -> Unit = {},
    onCoupons: () -> Unit = {},
    onPartnerCabinet: () -> Unit = {},
    onPromo: () -> Unit = {},
    onParcels: () -> Unit = {},
    onCourier: () -> Unit = {},
    onToggleLanguage: () -> Unit,
    onAccountDeleted: () -> Unit = {},
    onInstantLogin: () -> Unit = {},
    onTaxiOnboarding: () -> Unit = {},   // §11: из заглушки «Такси скоро» водитель уходит в онбординг
    onTabChange: (HomeTab) -> Unit = {}
) {
    var ridesPresetTo by remember { mutableStateOf("") }
    var ridesPresetToday by remember { mutableStateOf(false) }

    // Шелл (таб-стейт + нижнее меню + смена вкладок) вынесен в чистый HomeShell — тестируется на JVM.
    // HomeScreen остаётся «умной» обёрткой: раздаёт данные/колбэки в тело конкретной вкладки.
    HomeShell(initialTab = initialTab, onTabChange = onTabChange) { tab, selectTab ->
            fun openRides(to: String = "", today: Boolean = false) {
                ridesPresetTo = to
                ridesPresetToday = today
                selectTab(HomeTab.Rides)
            }
            when (tab) {
                HomeTab.Map -> PassengerModeHome(
                    rides = rides,
                    activeTrip = activeTrip,
                    ads = ads,
                    adStats = adStats,
                    onBookRide = onBookRide,
                    onShareRide = onShareRide,
                    onAdImpression = onAdImpression,
                    onAdClick = onAdClick,
                    onSos = onSos,
                    onOpenPopular = { route -> openRides(to = route.to, today = true) },
                    onDriver = onCreateRide,
                    onBoost = onBoost,
                    onInstantLogin = onInstantLogin,
                    onTaxiOnboarding = onTaxiOnboarding,
                    onClinicRides = onClinicRides,
                    onRouteWatch = onRouteWatch
                )
                HomeTab.Rides -> RidesScreen(
                    rides = rides,
                    ads = ads,
                    adStats = adStats,
                    presetTo = ridesPresetTo,
                    presetToday = ridesPresetToday,
                    onBookRide = onBookRide,
                    onOpenBookingDetails = onOpenBookingDetails,
                    onOpenActiveTrip = onOpenActiveTrip,
                    onMessage = { selectTab(HomeTab.Chat) },
                    onShareRide = onShareRide,
                    onBoost = onBoost,
                    onCreateRequest = { selectTab(HomeTab.Request) },
                    onAdImpression = onAdImpression,
                    onAdClick = onAdClick
                )
                HomeTab.Request -> MyRequestsScreen(
                    requests = requests,
                    onCreateNew = onCreateRequest,
                    onViewResponses = onOpenResponses,   // открыть отклики ИМЕННО этой заявки (раньше терялся id → кидало на вкладку Чат)
                    onCancel = onCancelRequest
                )
                HomeTab.Chat -> ChatScreen(
                    voiceMessages = voiceMessages,
                    onAddVoiceMessage = onAddVoiceMessage,
                    onNotifications = onNotifications,
                    onOpenChat = onOpenChat,
                    onOpenResponses = onOpenResponses
                )
                HomeTab.Profile -> ProfileScreen(
                    ads = ads,
                    adStats = adStats,
                    onSupport = onSupport,
                    onVerifyDriver = onVerifyDriver,
                    onSafety = onSafety,
                    onSettings = onSettings,
                    onPrivacy = onPrivacy,
                    onTrust = onTrust,
                    onConsents = onConsents,
                    onHelp = onHelp,
                    onReview = onReview,
                    onAdminReviews = onAdminReviews,
                    onAdminAds = onAdminAds,
                    onPassengerCabinet = onPassengerCabinet,
                    onDriverCabinet = onDriverCabinet,
                    onSimpleMode = onSimpleMode,
                    onTrustedContacts = onTrustedContacts,
                    onCallbackHelp = onCallbackHelp,
                    onAdsCabinet = onAdsCabinet,
                    onMyStats = onMyStats,
                    onCoupons = onCoupons,
                    onPromo = onPromo,
                    onParcels = onParcels,
                    onCourier = onCourier,
                    onPartnerCabinet = onPartnerCabinet,
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
    tabContent: @Composable (tab: HomeTab, selectTab: (HomeTab) -> Unit) -> Unit,
) {
    var selectedTab by rememberSaveable(initialTab) { mutableStateOf(initialTab) }   // вкладка переживает поворот
    LaunchedEffect(selectedTab) { onTabChange(selectedTab) }

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
                tabContent(tab) { selectedTab = it }
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
                .navigationBarsPadding()   // на жест-навигации иконки меню не уезжают под системную полосу
                .height(78.dp)
                .padding(horizontal = 6.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
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
                label = appText("Заявка", "Заявка"),
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
                onClick = { onSelect(HomeTab.Profile) }
            )
        }
    }
}

@Composable
private fun RowScope.YuldashBottomItem(
    selected: Boolean,
    label: String,
    iconRes: Int,
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
                painterResource(iconRes),
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

