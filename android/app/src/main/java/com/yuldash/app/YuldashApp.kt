package com.yuldash.app

// Корень навигации: YuldashApp (when(screen)) + HomeScreen (Scaffold+вкладки) + нижнее меню.
// Вынесено из MainActivity (Фаза 3). Импорты целиком — лишние = варнинги.

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

@Composable
internal fun YuldashApp() {
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
    var screen by rememberSaveable { mutableStateOf(Screen.Splash) }   // переживает поворот И kill процесса
    var language by rememberSaveable { mutableStateOf(AppLanguage.Ru) }   // переживает поворот экрана
    var selectedRide by remember { mutableStateOf<Ride?>(null) }
    var startHomeTab by rememberSaveable { mutableStateOf(HomeTab.Map) }
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
    // SnapshotStateMap: при показе/клике мутируем ТОЛЬКО одну запись вместо копии всей карты
    // на каждый импрешн (adStats + (..) аллоцировал новый Map при каждом событии рекламы).
    val adStats = remember {
        androidx.compose.runtime.mutableStateMapOf<String, AdStats>().apply {
            putAll(demoPartnerAds.associate { it.id to AdStats() })
        }
    }

    // После kill/restore: screen сохранён, но транзитные selectedRide/activeBookingId — нет.
    // Если восстановились на экране брони/активной поездки без данных → на Home (без краша/пустоты).
    LaunchedEffect(Unit) {
        if ((screen == Screen.Booking || screen == Screen.ActiveTrip) && selectedRide == null && activeBookingId == null) {
            screen = Screen.Home
        }
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
        adStats[ad.id] = current.copy(impressions = current.impressions + 1)
        ApiClient.fireAdEvent(ad.id, "impression")   // реальный показ на сервер
    }

    fun trackAdClick(ad: PartnerAd) {
        val current = adStats[ad.id] ?: AdStats()
        adStats[ad.id] = current.copy(clicks = current.clicks + 1)
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
                            passenger = r.forRelativeName ?: ApiClient.cachedName() ?: "Я",
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
                LoginScreen(
                    currentLanguage = language,
                    onToggleLanguage = {
                        language = if (language == AppLanguage.Ru) AppLanguage.Ba else AppLanguage.Ru
                    },
                    onContinue = { openHome() },
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
                ride = selectedRide ?: rides.firstOrNull() ?: demoRides.first(),   // фоллбэк вместо краша на пустом списке
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
            Screen.Rules -> RulesScreen(onBack = { screen = Screen.Safety })
            Screen.PaymentInfo -> PaymentInfoScreen(onBack = { screen = Screen.Settings })
            Screen.Blocklist -> BlocklistScreen(onBack = { screen = Screen.Safety })
            Screen.Report -> ReportScreen(onBack = { screen = Screen.Safety })
            Screen.Filters -> FiltersScreen(onBack = { screen = Screen.Settings })
            Screen.Safety -> SafetyScreen(
                onBack = { openHome(HomeTab.Profile) },
                onSelectTab = { tab -> openHome(tab) },
                onSos = { screen = Screen.Sos },
                onShareTrip = { screen = Screen.TrustedContacts },
                onRules = { screen = Screen.Rules },
                onBlocklist = { screen = Screen.Blocklist },
                onReport = { screen = Screen.Report }
            )
            Screen.Settings -> SettingsScreen(
                onBack = { openHome(HomeTab.Profile) },
                onSelectTab = { tab -> openHome(tab) },
                onToggleLanguage = {
                    language = if (language == AppLanguage.Ru) AppLanguage.Ba else AppLanguage.Ru
                },
                onPrivacy = { screen = Screen.Privacy },
                onPayments = { screen = Screen.PaymentInfo },
                onFilters = { screen = Screen.Filters }
            )
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
                        items(slide.items, key = { it.titleRu }) { item ->
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
        bodyRu = "Войдите через Telegram — быстро и безопасно, без SMS и паролей.",
        bodyBa = "Telegram аша инегеҙ — тиҙ һәм хәүефһеҙ, SMS-һыҙ һәм паролһеҙ.",
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
    var selectedTab by rememberSaveable(initialTab) { mutableStateOf(initialTab) }   // вкладка переживает поворот
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

