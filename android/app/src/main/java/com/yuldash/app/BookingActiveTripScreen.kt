package com.yuldash.app

// Экраны брони (BookingScreen) и активной поездки (ActiveTripScreen: чат/статус/SOS).
// Вынесено из MainActivity (Фаза 2). Импорты целиком — лишние = варнинги.

import com.yuldash.app.R
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
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.Woman
import androidx.compose.material.icons.filled.SmokingRooms
import androidx.compose.material.icons.filled.Luggage
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.filled.AddBox
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.EscalatorWarning
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
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Edit
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.text.font.FontStyle
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
import androidx.compose.material.icons.filled.Phone
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
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.VolunteerActivism
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
import androidx.compose.foundation.layout.sizeIn
import coil.compose.AsyncImage
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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
import com.yuldash.app.data.DriverStageApi
import com.yuldash.app.data.ApiException
import com.yuldash.app.data.MessageDto
import com.yuldash.app.data.MeetingPointInput
import com.yuldash.app.data.ChatSocket
import com.yuldash.app.data.GeocoderClient
import com.yuldash.app.data.GeoHit
import com.yuldash.app.data.ConversationDto
import com.yuldash.app.data.PopularRouteDto
import com.yuldash.app.data.FeedDto
import com.yuldash.app.data.RequestDto
import com.yuldash.app.data.NotifDto
import com.yuldash.app.data.AdDto
import com.yuldash.app.data.TripPassStore
import com.yuldash.app.data.OfflineTripSnapshots
import com.yuldash.app.data.Outbox
import com.yuldash.app.ui.theme.YuldashTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Офлайн-паспорт брони (saveTripPass) и связь с водителем — RideshareTripContacts.kt.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookingScreen(
    ride: Ride?,
    bookingId: Int? = null,
    ads: List<PartnerAd>,
    adStats: Map<String, AdStats>,
    onBack: () -> Unit,
    onMessage: () -> Unit,
    onAdImpression: (PartnerAd) -> Unit,
    onAdClick: (PartnerAd) -> Unit,
    canOpenActiveTrip: Boolean = true,
    isCurrentParent: (() -> Boolean)? = null,
    // Состояние брони (pending | confirmed | cancelled | …). Нужно, чтобы человек, который
    // ЖДЁТ ответа водителя, мог передумать, а тот, кому отказали, — узнал об этом
    // (аудит сценариев 30.08, два P0). Пусто = состояние неизвестно, ведём себя как раньше.
    bookingStatus: String = "",
    onBookingStatusChange: (String) -> Unit = {},   // свежий статус из деталей брони (подтвердили, пока экран открыт)
    onCancelBooking: (() -> Unit)? = null,
    onPassengerCancelled: () -> Unit = {},
    onFindAnotherRide: () -> Unit = {}, onSos: () -> Unit = {}, frontSeat: Boolean = false, onFrontSeatChange: (Boolean) -> Unit = {},   // + «хочу переднее» (SeatOptions.kt); SOS — отмена в пути (CancelledInTransit.kt)
    pickupStop: String? = ride?.let(::initialPickupStop), onPickupStopChange: (String) -> Unit = {},
    dropoffStop: String? = ride?.let(::initialDropoffStop), onDropoffStopChange: (String) -> Unit = {},
    selectedSeats: Int = 1, onSeatsChange: (Int) -> Unit = {}, bookingBusy: Boolean = false,
    // RS-03 А: точное место встречи/высадки по желанию (состояние — наверху, экран рисует).
    meetPickup: MeetingPointInput? = null, onMeetPickupChange: (MeetingPointInput) -> Unit = {},
    meetDropoff: MeetingPointInput? = null, onMeetDropoffChange: (MeetingPointInput) -> Unit = {},
    recovery: BookingRecoveryState? = null, onOpenRecoveredBooking: (com.yuldash.app.data.BookingCreatedDto) -> Unit = {},
    onRefreshBookingTerms: (() -> Unit)? = null,
    initialMinor: Boolean = false,
    initialForAnother: Boolean = false,
    trustedContacts: List<TrustedContact> = emptyList(),
    onTrustedContacts: () -> Unit = {},
    onGuestBooking: ((Boolean, Boolean) -> Unit)? = null,
    onConfirmRide: (payMethod: String, payAmount: Int?, minor: Boolean, guardianName: String, guardianPhone: String,
        travelerName: String?) -> Unit
) {
    // Бронировать нечего: экран открылся без поездки. Демо-поездку вместо настоящей
    // не подставляем — молча возвращаемся назад.
    if (ride == null) {
        BookingRideUnavailable(bookingId, onBack)
        return
    }
    if (bookingId == null && RideCategoryBlocked(ride.category, onBack, onFindAnotherRide)) return
    val routeAd = ads.forPlacement(AdPlacement.TripDetails).firstOrNull { it.matchesRoute(ride.from, ride.to) }
    val context = LocalContext.current
    val bookingScope = rememberCoroutineScope()
    val bookingOwner = remember(bookingId) { OfflineTripSnapshots.currentOwner() }
    val currentParent by rememberUpdatedState(isCurrentParent)
    var rawDetails by remember(bookingId) { mutableStateOf<com.yuldash.app.data.BookingDetailsDto?>(null, androidx.compose.runtime.referentialEqualityPolicy()) }
    val detailsRevision by OfflineTripSnapshots.version.collectAsState()
    val details = detailsRevision.let { currentBookingParentDetails(rawDetails, bookingId, ride) }
    // Договорённость об оплате (ЗАПИСЬ, не платёж): что выбрал пассажир до брони.
    // Способ по умолчанию — «договоримся»; сумма по умолчанию — из цены СВОЕГО участка (RS-02 Б).
    var payMethod by remember(bookingId) { mutableStateOf("negotiate") }
    var payAmountText by rememberBookingPayAmount(ride, bookingId, selectedSeats,
        perSeat = segmentPriceRub(ride, pickupStop, dropoffStop) ?: ride.price)
    // Едет подросток: отметка + взрослый на связи (сервер без него бронь не создаст).
    var minorPassenger by remember(initialMinor) { mutableStateOf(initialMinor) }
    var forAnother by remember(initialForAnother) { mutableStateOf(initialForAnother) }
    var travelerName by remember { mutableStateOf("") }
    LaunchedEffect(recovery?.slot?.state) {
        if (recovery?.slot?.state == com.yuldash.app.data.CommandIntent.State.Closed) travelerName = ""
    }
    var guardianName by remember { mutableStateOf("") }
    var guardianPhone by remember { mutableStateOf("") }
    var detailsLoading by remember(bookingId) { mutableStateOf(bookingId != null) }
    var detailsError by remember(bookingId) { mutableStateOf(false) }
    var detailsReload by remember(bookingId) { mutableIntStateOf(0) }
    var offlineSaveFailed by remember(bookingId) { mutableStateOf(false) }
    var offlineSaveBusy by remember(bookingId) { mutableStateOf(false) }
    var offlineSaveRetry by remember(bookingId) { mutableIntStateOf(0) }
    var detailsSession by remember(bookingId) { mutableStateOf(ApiClient.queueSessionGeneration()) }
    val statusChanged by rememberUpdatedState(onBookingStatusChange)
    fun isBookingDisplayCurrent() = bookingScope.isActive && detailsSession == ApiClient.queueSessionGeneration() &&
        OfflineTripSnapshots.currentOwner() == bookingOwner && (currentParent?.invoke() != false)
    LaunchedEffect(bookingId, detailsReload) {
        val bid = bookingId ?: return@LaunchedEffect
        val session = detailsSession
        if (session != ApiClient.queueSessionGeneration() || !isBookingDisplayCurrent()) return@LaunchedEffect
        detailsLoading = true
        val result = ApiClient.getBookingDetails(bid)
        if (session != ApiClient.queueSessionGeneration() || !isBookingDisplayCurrent()) return@LaunchedEffect
        result
            .onSuccess { loaded ->
                if (!ride.matchesPrivateBookingRide(bid, loaded.rideId)) {
                    detailsError = true
                    return@onSuccess
                }
                detailsSession = session
                rawDetails = loaded; detailsError = false
                if (loaded.status.isNotBlank()) statusChanged(loaded.status)
                // F11: как только бронь подтверждена (телефон/встреча открыты) — сохраняем офлайн-паспорт.
                // Так экран активной поездки поднимет данные без сети на трассе без связи.
                // Только ПАССАЖИРУ (RS-03 А): паспорт с кодом посадки — его документ, на устройстве
                // водителя он чужой файл (план RS-03, Android п.1).
                if (loaded.contactUnlocked && loaded.role == "passenger" && loaded.status in CONTACT_STATUSES) {
                    offlineSaveBusy = true
                    offlineSaveFailed = !saveTripPass(context, loaded, session)
                    offlineSaveBusy = false
                } else offlineSaveFailed = false
            }
            .onFailure { detailsError = true }
        detailsLoading = false
    }
    BookingOfflinePassRetry(
        context, bookingId, ride, details, detailsSession, offlineSaveRetry, ::isBookingDisplayCurrent,
        onDetails = { loaded ->
            rawDetails = loaded
            detailsError = false
            if (loaded.status.isNotBlank()) statusChanged(loaded.status)
        },
        onDetailsFailed = { detailsError = true },
        onSaveFailed = { offlineSaveFailed = it },
        onBusy = { offlineSaveBusy = it },
    )
    var historyRemovalFailed by remember(bookingId) { mutableStateOf(false) }
    var historyRemovalBusy by remember(bookingId) { mutableStateOf(false) }
    var historyRemovalRetry by remember(bookingId) { mutableIntStateOf(0) }
    // Only fresh server details authorize removing the old offline copy, not a list hint.
    val historyTerminal = details?.status in setOf("done", "cancelled")
    LaunchedEffect(bookingId, historyTerminal, detailsSession, historyRemovalRetry) {
        val id = bookingId ?: return@LaunchedEffect
        if (!historyTerminal || detailsSession != ApiClient.queueSessionGeneration()) return@LaunchedEffect
        historyRemovalBusy = true
        val removalSession = detailsSession
        try {
            val result = withContext(Dispatchers.IO) {
                TripPassStore.requestRemoval(context, id, expectedGeneration = removalSession)
            }
            if (removalSession != ApiClient.queueSessionGeneration()) return@LaunchedEffect
            historyRemovalFailed = result == TripPassStore.RemovalResult.NOT_SAVED
        } finally {
            historyRemovalBusy = false
        }
    }
    val displayRide = details?.let {
        ride.copy(
            id = it.rideId.toString(),
            from = it.fromCity.ifBlank { ride.from },
            to = it.toCity.ifBlank { ride.to },
            time = formatDepart(it.departAt.ifBlank { ride.time }),
            driver = it.driverName.ifBlank { ride.driver },
            car = it.driverCar.ifBlank { ride.car },
            price = if (it.price > 0) it.price else ride.price,
            seats = it.seats,
            verified = it.driverVerified || ride.verified,
            pickup = it.pickup,
            pickupLat = it.pickupLat,
            pickupLng = it.pickupLng,
        )
    } ?: ride
    val contactUnlocked = details?.contactUnlocked == true && OfflineTripSnapshots.privateDetails(details)
    val driverPhone = details?.driverPhone.orEmpty()
    val exactPickup = if (contactUnlocked) displayRide.pickup else ""
    val pickupLat = if (contactUnlocked) displayRide.pickupLat else null
    val pickupLng = if (contactUnlocked) displayRide.pickupLng else null
    // Для ещё не созданной брони серверных координат деталей нет. Известные города берём из
    // локального справочника: это публичные центры городов, не скрытая точка встречи пассажира.
    // Поэтому вариант B показывает честную карту уже до нажатия «Забронировать место».
    val routeFromPoint = details?.let { d -> d.fromLat?.let { lat -> d.fromLng?.let { lng -> Point(lat, lng) } } }
        ?: cityPoint(displayRide.from)
    val routeToPoint = details?.let { d -> d.toLat?.let { lat -> d.toLng?.let { lng -> Point(lat, lng) } } }
        ?: cityPoint(displayRide.to)
    val distanceText = cityDistanceText(displayRide.from, displayRide.to)
    val driverFallback = appText("Водитель", "Йөрөтөүсе")
    val driverName = displayRide.driver.ifBlank { driverFallback }
    val driverRating = displayRide.rating.takeIf { it > 0.0 }
        ?.let { String.format(java.util.Locale.US, "%.1f", it).replace('.', ',') }
    val carSummary = buildList {
        details?.driverCarColor?.takeIf { it.isNotBlank() }?.let(::add)
        displayRide.carText().takeIf { it.isNotBlank() }?.let(::add)
        details?.driverPlate?.takeIf { it.isNotBlank() }?.let(::add)
    }.joinToString(" · ")
    val shareTitle = appText("Позвать соседа", "Күршене саҡырырға")
    val shareText = bookingRideShareText(displayRide.copy(price = details?.takeIf { it.seats > 1 && it.price > 0 }?.let { it.price / it.seats } ?: displayRide.price), showPrice = bookingId == null || details != null)   // соседу — цена места, не всей брони
    // Свежие детали знают статус лучше, чем список, из которого открыли бронь.
    val normalizedBookingStatus = (details?.status?.takeIf { it.isNotBlank() } ?: bookingStatus).trim().lowercase()
    val bookingPending = bookingId != null && normalizedBookingStatus == "pending"
    val bookingTraveler = visibleBookingTraveler(details?.takeIf(OfflineTripSnapshots::currentAcceptedDetails)?.traveler)
    var showPendingShare by remember(bookingId) { mutableStateOf(false) }
    val canSharePending = bookingPending && details?.role == "passenger"
    LaunchedEffect(canSharePending) { if (!canSharePending) showPendingShare = false }
    if (showPendingShare && canSharePending) {
        BookingShareSheet(bookingId, trustedContacts, detailsSession,
            isCurrent = { isBookingDisplayCurrent() && details?.let(OfflineTripSnapshots::currentAcceptedDetails) == true && canSharePending },
            onDismiss = { showPendingShare = false }, onTrustedContacts = onTrustedContacts)
    }
    // Ждёт ответа водителя — тихо сверяемся с сервером, пока экран на виду: пуш может не дойти, а
    // подтверждение открывает телефон и точку встречи (приёмка 18.10). Сменился статус — полная загрузка.
    BookingPendingRefresh(bookingId, bookingPending, details?.status, detailsSession, onDetails = { loaded ->
        if (isBookingDisplayCurrent() && ride.matchesPrivateBookingRide(loaded.bookingId, loaded.rideId)) rawDetails = loaded
    }) { detailsReload++ }
    val passengerCancellation = if (bookingId != null && onCancelBooking == null) {
        rememberPassengerCancellation(detailsSession, bookingId, "pending".takeIf { bookingPending },
            isCurrent = ::isBookingDisplayCurrent) { result ->
            if (result?.bookingStatus == "cancelled") onPassengerCancelled() else detailsReload++
        }
    } else null
    val bookingCancelled = bookingId != null && normalizedBookingStatus == "cancelled"
    var showCancelConfirmation by rememberSaveable(bookingId, normalizedBookingStatus) { mutableStateOf(false) }
    // RS-02 А: решение о брони — по СВОЕМУ участку, а не по всему пути (трое до Ургазы
    // не закрывают Булату Ургаза→Акъяр: seats_left=0, а на его звене места есть).
    val segmentSeats = bookableSeats(displayRide, pickupStop, dropoffStop)
    val canCreateBooking = bookingId == null && segmentSeats > 0
    val canRepeatOriginal = bookingId == null && recovery?.preparedRetry != null
    val requiredContactsReady = onGuestBooking != null || !minorPassenger ||
        (guardianName.isNotBlank() && guardianPhone.isNotBlank())
    val travelerReady = !forAnother || (!ride.womenOnly &&
        (onGuestBooking != null || normalizedBookingTravelerName(travelerName) != null))
    val primaryLabel = when {
        bookingId == null && recovery?.knownBooking?.status?.let { it != "cancelled" } == true -> appText("Бронь найдена", "Бронь табылды")
        canRepeatOriginal -> appText("Повторить тот же запрос", "Шул уҡ һорауҙы ҡабатлау")
        bookingId == null && !canCreateBooking -> appText("Мест нет", "Урын юҡ")
        bookingId == null && recovery?.slot?.bookingTerms?.forAnother == true && recovery.retry ->
            appText("Сначала проверь запрос", "Тәүҙә һорауҙы тикшер")
        bookingId == null && recovery?.retry == true -> appText("Повторить тот же запрос", "Шул уҡ һорауҙы ҡабатлау")
        bookingId == null && recovery?.outcome?.booking?.status == "cancelled" -> appText("Забронировать снова", "Яңынан бронларға")
        bookingId == null -> bookSeatsLabel(selectedSeats)
        bookingPending -> appText("Отменить бронь", "Бронде кире алыу")
        bookingCancelled -> appText("Найти другую поездку", "Башҡа сәфәр табыу")
        canOpenActiveTrip -> appText("Открыть поездку", "Сәфәрҙе асыу")
        else -> appText("Ждём водителя", "Йөрөтөүсене көтәбеҙ")
    }
    val primaryEnabled = if (bookingId == null) !bookingBusy && recovery?.canSubmit != false &&
        (canRepeatOriginal || (canCreateBooking && requiredContactsReady && travelerReady &&
            canBookSeats(segmentSeats, selectedSeats, bookingBusy) && segmentReady(ride, pickupStop, dropoffStop)))
        else !bookingBusy && (bookingPending || bookingCancelled || canOpenActiveTrip) &&
            !(bookingPending && passengerCancellation?.blocked == true)
    val primaryStyle = if (bookingPending) AppButtonStyle.Danger else AppButtonStyle.Primary
    val primaryIcon = when {
        bookingPending -> Icons.Default.Close
        bookingCancelled -> Icons.Default.Search
        primaryEnabled -> Icons.Default.EventSeat
        else -> Icons.Default.Schedule
    }
    if (showCancelConfirmation && onCancelBooking != null) {
        AlertDialog(
            onDismissRequest = { showCancelConfirmation = false },
            title = { Text(appText("Отменить бронь?", "Бронде кире алырғамы?")) },
            text = {
                Text(
                    appText(
                        "Место освободится, а водитель получит уведомление.",
                        "Урын бушаясаҡ, ә йөрөтөүсе хәбәр аласаҡ.",
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCancelConfirmation = false
                        onCancelBooking()
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(appText("Да, отменить", "Эйе, кире алырға"), color = CanonRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showCancelConfirmation = false },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(appText("Оставить бронь", "Бронде ҡалдырырға"))
                }
            },
        )
    }
    // Отменили в пути — экран помощи; детали отменённой брони ещё грузятся — загрузка (CancelledInTransit.kt).
    if (bookingId != null && bookingCancelledOverride(details, normalizedBookingStatus, detailsLoading, onSos, onBack)) return
    Scaffold(
        containerColor = CanonBg,
        topBar = { ScreenTopBar(appText("Детали поездки", "Сәфәр тураһында"), onBack) },
        bottomBar = {
            if (bookingId == null && isOwnRide(displayRide)) OwnRideBar() else Column {
                if (bookingId == null && !bookingBusy && recovery?.guardianPhoneRejected == true) {
                    Surface(color = CanonSurface) {
                        Text(
                            guardianPhoneBookingError(),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = CanonSpace.lg, vertical = CanonSpace.sm)
                                .semantics { liveRegion = LiveRegionMode.Polite },
                            color = CanonRed,
                            style = CanonBody,
                        )
                    }
                }
                BookingDecisionBar(
                    primaryText = primaryLabel,
                    primaryEnabled = primaryEnabled,
                    primaryStyle = primaryStyle,
                    primaryIcon = primaryIcon,
                    onMessage = onMessage,
                    onPrimary = {
                        if (bookingBusy || (bookingId == null && (!primaryEnabled || rideCategoryClosed(ride.category)))) return@BookingDecisionBar
                        when {
                            bookingPending -> if (passengerCancellation != null) passengerCancellation.open()
                                else showCancelConfirmation = true
                            bookingCancelled -> onFindAnotherRide()
                            onGuestBooking != null -> onGuestBooking(minorPassenger, forAnother)
                            else -> onConfirmRide(
                                payMethod,
                                payAmountText.trim().toIntOrNull(),
                                minorPassenger,
                                guardianName.trim(),
                                guardianPhone.trim(),
                                normalizedBookingTravelerName(travelerName).takeIf { forAnother },
                            )
                        }
                    },
                )
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp).tripAuthorityDraw { isBookingDisplayCurrent() && (details == null || OfflineTripSnapshots.freshDetails(details) || OfflineTripSnapshots.terminal(details.bookingId) != null) },
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
        ) {
            item(key = "history-offline-removal") {
                AnimatedVisibility(visible = historyRemovalFailed && historyTerminal) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (historyRemovalBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = CanonGreen2)
                        EmptyStateCard(
                            title = appText("Не удалось удалить сохранённую поездку", "Һаҡланған сәфәрҙе юйып булманы"),
                            text = appText(
                                "Поездка уже закрыта. Её копия может остаться на телефоне. Попробуй удалить ещё раз.",
                                "Сәфәр ябылған инде. Уның күсермәһе телефонда ҡалырға мөмкин. Тағы юйып ҡара.",
                            ),
                            icon = Icons.Default.Refresh,
                            action = if (historyRemovalBusy) null else appText("Повторить удаление", "Тағы юйырға"),
                            onAction = {
                                if (!historyRemovalBusy) {
                                    historyRemovalBusy = true
                                    historyRemovalRetry++
                                }
                            },
                        )
                    }
                }
            }
            passengerCancellation?.content?.let { content -> item(key = "passenger-cancellation") { content() } }
            if (bookingPending) {
                item {
                    InfoCard(
                        title = appText("Ждём ответа водителя", "Йөрөтөүсенең яуабын көтәбеҙ"),
                        text = appText(
                            "Водитель уже получил запрос. Пока он решает, бронь можно отменить.",
                            "Йөрөтөүсе һорауҙы алды. Ул ҡарар иткәнсе бронде кире алырға була.",
                        ),
                        icon = Icons.Default.Schedule,
                    )
                }
            } else if (bookingCancelled) {
                item { BookingCancelledCard(declined = details?.declined == true) }   // RideshareDecline.kt
            }
            if (detailsLoading) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = CanonSurface), shape = CanonItemShape) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), color = CanonGreen2, strokeWidth = 2.dp)
                            Text(appText("Обновляем подтверждение поездки", "Сәфәр раҫланыуын яңыртабыҙ"), color = CanonMuted, fontSize = 14.sp)
                        }
                    }
                }
            }
            item(key = "offline-pass-save") {
                AnimatedVisibility(visible = offlineSaveFailed) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (offlineSaveBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = CanonGreen2)
                        EmptyStateCard(
                            title = appText("Не удалось сохранить поездку без интернета", "Сәфәрҙе интернетһыҙ ҡулланыу өсөн һаҡлап булманы"),
                            text = appText(
                                "Данные поездки загружены. Чтобы открыть их без сети, попробуй сохранить ещё раз.",
                                "Сәфәр мәғлүмәттәре йөкләнде. Уларҙы интернетһыҙ асыу өсөн тағы һаҡлап ҡара.",
                            ),
                            icon = Icons.Default.Refresh,
                            action = if (offlineSaveBusy) null else appText("Сохранить ещё раз", "Тағы һаҡларға"),
                            onAction = {
                                if (!offlineSaveBusy && isBookingDisplayCurrent()) {
                                    offlineSaveBusy = true
                                    offlineSaveRetry++
                                }
                            },
                        )
                    }
                }
            }
            if (detailsError) {
                item {
                    EmptyStateCard(
                        title = appText("Не удалось обновить детали", "Ентекле мәғлүмәтте яңыртып булманы"),
                        text = appText("Телефон и точка встречи останутся закрытыми, пока сервер не подтвердит доступ.", "Сервер рөхсәтте раҫлағансы, телефон һәм осрашыу урыны ябыҡ ҡала."),
                        icon = Icons.Default.Refresh,
                        action = appText("Повторить", "Ҡабатлау"),
                        onAction = { detailsReload++ }
                    )
                }
            }
            item {
                BookingDriverHeroCard(
                    driverName = driverName,
                    avatarUrl = displayRide.driverAvatar,
                    rating = driverRating,
                    verified = displayRide.verified,
                    trips = displayRide.driverTrips,
                    since = displayRide.driverSince,
                    carSummary = carSummary,
                    phoneUnlocked = contactUnlocked && driverPhone.isNotBlank(),
                    onCall = {
                        if (isBookingDisplayCurrent() && OfflineTripSnapshots.privateDetails(details)) runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$driverPhone"))) }
                    },
                )
            }
            item {
                BookingRouteDecisionCard(displayRide, bookingId != null, distanceText, routeFromPoint, routeToPoint, contactUnlocked,
                    onShare = { shareRide(context, shareText, shareTitle) }, pending = bookingId != null && details == null)
            }
            routeAd?.let { ad ->
                item {
                    PartnerAdCard(
                        ad = ad,
                        stats = adStats[ad.id] ?: AdStats(),
                        compact = true,
                        label = appText("По маршруту", "Маршрут буйынса"),
                        onImpression = onAdImpression,
                        onClick = onAdClick,
                    )
                }
            }
            if (bookingId == null && recovery != null) item {
                BookingRecoveryActions(recovery, bookingBusy, onOpenRecoveredBooking,
                    isCurrent = ::isBookingDisplayCurrent, onRefreshTerms = onRefreshBookingTerms, showGuardianPhoneError = false)
            }
            if (!canRepeatOriginal) {
                pickupStopItem(ride, bookingId, details, pickupStop, onPickupStopChange, dropoffStop, onDropoffStopChange)
                bookingSeatCountItem(ride, bookingId, selectedSeats, bookingBusy, pickupStop, dropoffStop, onSeatsChange)
            }
            // RS-03 А: точное место по желанию — только при создании брони (смена потом — RS-03 Б).
            if (ride != null && bookingId == null && !canRepeatOriginal && onGuestBooking == null) {
                item {
                    BookingMeetingPoints(displayRide, pickupStop, dropoffStop,
                        meetPickup, onMeetPickupChange, meetDropoff, onMeetDropoffChange,
                        enabled = !bookingBusy)
                }
            }
            item {
                // RS-03 А: своя точка встречи важнее общего пина поездки (её пассажир и ставил).
                if (!hasOwnPickup(details)) BookingMeetingCard(
                    contactUnlocked = contactUnlocked,
                    bookingExists = bookingId != null,
                    exactPickup = exactPickup,
                    pickupLat = pickupLat,
                    pickupLng = pickupLng,
                    isCurrent = { isBookingDisplayCurrent() && OfflineTripSnapshots.privateDetails(details) },
                )
            }
            // RS-03 Б: смена/предложение места и отзыв — у живой брони пассажира.
            if (details != null && bookingId != null) {
                item(key = "booking-meeting-$bookingId") {
                    PassengerMeetingPoints(currentOwnBookingDetails(details), isCurrentParent = ::isBookingDisplayCurrent) { detailsReload++ }
                }
            }
            if (bookingTraveler != null) item { BookingTravelerText(bookingTraveler) }
            if (canSharePending) item { ShareTripRow(onClick = { showPendingShare = true }) }
            item {
                // Кнопка звонка появляется не сразу. Без объяснения это читается как поломка,
                // а на деле это защита: номер и точная точка встречи открываются только после
                // согласия обеих сторон (§8 «между своими»).
                if (contactUnlocked && driverPhone.isNotBlank()) {
                    InfoCard(
                        title = appText("Телефон водителя открыт", "Йөрөтөүсенең телефоны асылды"),
                        text = driverPhone,
                        icon = Icons.Default.Phone,
                    )
                } else {
                    InfoCard(
                        title = if (bookingId != null)
                            appText("Телефон откроется после подтверждения водителем",
                                    "Телефон йөрөтөүсе раҫлағас асыла")
                        else
                            appText("Телефон откроется после подтверждения поездки",
                                    "Телефон сәфәр раҫланғандан һуң асыла"),
                        text = appText(
                            "Так мы защищаем номер и точную геолокацию до взаимного согласия.",
                            "Шулай итеп номерҙы һәм теүәл геолокацияны ике яҡ ризалығына тиклем һаҡлайбыҙ.",
                        ),
                        icon = Icons.Default.Lock,
                    )
                }
            }
            if (!canRepeatOriginal) item {
                PayAgreementBlock(
                    editable = bookingId == null,
                    method = if (bookingId == null) payMethod else (details?.payMethod ?: "negotiate"),
                    amountText = payAmountText,
                    summaryAmount = details?.payAmount,
                    onMethod = { payMethod = it },
                    onAmount = { payAmountText = it },
                )
            }
            if (!canRepeatOriginal) frontSeatItem(ride, bookingId, details, frontSeat, onFrontSeatChange)
            if (bookingId == null && !canRepeatOriginal) {
                if (ride.category !in setOf("parcel", "cargo") && (!ride.womenOnly || forAnother)) item {
                    BookingTravelerChoice(forAnother, travelerName, ride.womenOnly, onGuestBooking != null,
                        !bookingBusy, onChecked = {
                            forAnother = it
                            if (!it) travelerName = ""
                        }, onName = { travelerName = it })
                }
                item {
                    if (!ride.noMinors) {
                        MinorPassengerBlock(
                            checked = minorPassenger,
                            guardianName = guardianName,
                            guardianPhone = guardianPhone,
                            onChecked = { minorPassenger = it },
                            onName = { guardianName = it },
                            onPhone = { guardianPhone = it },
                            showContacts = onGuestBooking == null,
                        )
                    } else {
                        InfoCard(
                            title = appText("Водитель берёт только 18+", "Йөрөтөүсе тик 18+ ала"),
                            text = appText(
                                "Этот водитель не везёт пассажиров младше 18 без взрослого. Поищи другую поездку — их много.",
                                "Был йөрөтөүсе 18-ҙән кесе юлсыларҙы оло кешеһеҙ йөрөтмәй. Башҡа сәфәр эҙлә — улар күп.",
                            ),
                            icon = Icons.Default.EscalatorWarning,
                        )
                    }
                }
            } else if (details?.minorPassenger == true && OfflineTripSnapshots.privateDetails(details)) {
                item {
                    InfoCard(
                        title = appText("Едет пассажир младше 18", "18-ҙән кесе юлсы бара"),
                        text = listOf(details?.minorGuardianName.orEmpty(), details?.minorGuardianPhone.orEmpty())
                            .filter { it.isNotBlank() }.joinToString(" · ")
                            .ifBlank { appText("Взрослый на связи указан", "Оло кеше күрһәтелгән") },
                        icon = Icons.Default.EscalatorWarning,
                    )
                }
            }
            item {
                InfoCard(
                    title = appText("Мы бережём твою безопасность", "Беҙ һинең хәүефһеҙлегеңде һаҡлайбыҙ"),
                    text = appText("Все поездки защищены и отслеживаются службой поддержки Юлдаш.", "Бөтә сәфәрҙәр Юлдаш ярҙам хеҙмәте тарафынан күҙәтелә."),
                    icon = Icons.Default.Shield
                )
            }
        }
    }
}

@Composable
internal fun BookingDecisionBar(
    primaryText: String,
    primaryEnabled: Boolean,
    primaryStyle: AppButtonStyle,
    primaryIcon: ImageVector,
    onMessage: () -> Unit,
    onPrimary: () -> Unit,
) {
    val largeText = LocalDensity.current.fontScale > 1f   // уже при 1.15 на 320 dp «Забронировать» рвалось в строке
    Surface(color = CanonSurface, shadowElevation = CanonDepth.sheet) {
        val barModifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)
        if (largeText) {
            Column(barModifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AppButton(
                    text = appText("Написать", "Яҙырға"),
                    onClick = onMessage,
                    style = AppButtonStyle.Secondary,
                    icon = Icons.Default.ChatBubbleOutline,
                )
                AppButton(
                    text = primaryText,
                    onClick = onPrimary,
                    enabled = primaryEnabled,
                    style = primaryStyle,
                    icon = primaryIcon,
                )
            }
        } else {
            val compactPadding = PaddingValues(horizontal = CanonSpace.md, vertical = CanonSpace.sm)
            Row(
                barModifier.height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppButton(
                    text = appText("Написать", "Яҙырға"),
                    onClick = onMessage,
                    modifier = Modifier.width(IntrinsicSize.Max).fillMaxHeight(),   // по ширине подписи: доля weight на 320 dp рвала «Написа/ть»
                    style = AppButtonStyle.Secondary,
                    fillWidth = false, contentPadding = compactPadding,
                )
                AppButton(
                    text = primaryText,
                    onClick = onPrimary,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    enabled = primaryEnabled,
                    style = primaryStyle,
                    icon = primaryIcon,
                    fillWidth = false, contentPadding = compactPadding,
                )
            }
        }
    }
}

@Composable
private fun BookingDriverHeroCard(
    driverName: String,
    avatarUrl: String,
    rating: String?,
    verified: Boolean,
    trips: Int,
    since: String,
    carSummary: String,
    phoneUnlocked: Boolean,
    onCall: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = CanonMint),
        shape = CanonCardShape,
        border = BorderStroke(1.dp, CanonBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.card),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SmallAvatar(avatarUrl, driverName, 76)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        driverName,
                        color = CanonText,
                        fontSize = 19.sp,
                        lineHeight = 25.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (rating != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Star, contentDescription = null, tint = CanonStar, modifier = Modifier.size(19.dp))
                            Spacer(Modifier.width(5.dp))
                            Text(rating, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                    }
                    DriverTrustBadges(verified = verified, trips = trips, since = since)
                }
                if (phoneUnlocked) {
                    Surface(
                        color = CanonSurface,
                        shape = CircleShape,
                        border = BorderStroke(1.dp, CanonBorder),
                        modifier = Modifier.size(48.dp).bounceClick(onCall),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Phone,
                                contentDescription = appText("Позвонить", "Шылтыратыу"),
                                tint = CanonGreen2,
                                modifier = Modifier.size(21.dp),
                            )
                        }
                    }
                }
            }
            if (carSummary.isNotBlank()) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(CanonHairlineGreen))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = CanonSurface, shape = CircleShape) {
                        Icon(
                            Icons.Default.DirectionsCar,
                            contentDescription = null,
                            tint = CanonGreen2,
                            modifier = Modifier.padding(9.dp).size(19.dp),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        carSummary,
                        color = CanonText,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun BookingMeetingCard(
    contactUnlocked: Boolean,
    bookingExists: Boolean,
    exactPickup: String,
    pickupLat: Double?,
    pickupLng: Double?,
    isCurrent: () -> Boolean,
) {
    val context = LocalContext.current
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TripInfoRow(
                if (contactUnlocked) Icons.Default.LocationOn else Icons.Default.Lock,
                appText("Место встречи", "Осрашыу урыны"),
                when {
                    contactUnlocked && exactPickup.isNotBlank() -> exactPickup
                    contactUnlocked -> appText("Уточни точку в чате", "Нөктәне чатта асыҡла")
                    bookingExists -> appText("Откроется после подтверждения водителем", "Йөрөтөүсе раҫлағас асыла")
                    else -> appText("Откроется после подтверждения поездки", "Сәфәр раҫланғас асыла")
                },
            )
            if (!contactUnlocked) {
                Text(
                    appText(
                        "Номер и точная геолокация скрыты до взаимного согласия.",
                        "Номер һәм теүәл геолокация ике яҡ ризалығына тиклем йәшерелгән.",
                    ),
                    color = CanonMuted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }
            if (pickupLat != null && pickupLng != null) {
                val meet = appText("Место встречи", "Осрашыу урыны")
                AppButton(
                    text = appText("Открыть точку на карте", "Нөктәне картала асыу"),
                    onClick = {
                        if (!isCurrent()) return@AppButton
                        val uri = Uri.parse("geo:$pickupLat,$pickupLng?q=$pickupLat,$pickupLng(" + Uri.encode(meet) + ")")
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    },
                    style = AppButtonStyle.Secondary,
                    icon = Icons.Default.Map,
                )
            }
        }
    }
}

/** Способы оплаты-договорённости: ключ на бэке + иконка + двуязычная подпись.
 * Это ЗАПИСЬ «как договорились платить», НЕ платёж и не движение денег. */
internal val payMethodKeys = listOf("cash", "sbp", "negotiate")

@Composable
internal fun payMethodLabel(method: String): String = when (method) {
    "cash" -> appText("Наличными", "Наличный менән")
    "sbp" -> appText("Перевод по СБП", "СБП аша күсереү")
    else -> appText("Договоримся", "Килешербеҙ")
}

internal fun payMethodIcon(method: String) = when (method) {
    "cash" -> Icons.Default.Payments
    "sbp" -> Icons.Default.CreditCard
    else -> Icons.Default.VolunteerActivism
}

/**
 * Блок «Как договорились платить» в деталях брони.
 * editable=true (до брони) — пассажир выбирает способ (чипы) и сумму.
 * editable=false (бронь есть) — только показ договорённости обеим сторонам.
 * ВАЖНО: это запись договорённости, а НЕ оплата — деньги через приложение не идут.
 */
@Composable
internal fun PayAgreementBlock(
    editable: Boolean,
    method: String,
    amountText: String,
    summaryAmount: Int?,
    onMethod: (String) -> Unit,
    onAmount: (String) -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, CanonBorder, CanonItemShape)
            .background(CanonBg, CanonItemShape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Handshake, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(appText("Как договорились платить", "Түләү тураһында нисек килешкәнбеҙ"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
        Text(
            appText(
                "Это просто запись договорённости — деньги через приложение не проходят.",
                "Был — тик килешеү яҙмаһы, аҡса ҡулланма аша үтмәй."
            ),
            color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp
        )
        if (editable) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                payMethodKeys.forEach { key ->
                    // Способ оплаты — решение про деньги, тач-цель ≥ 48dp (§4.5), не мелкий фильтр.
                    NearbyFilterChip(
                        payMethodIcon(key), payMethodLabel(key), method == key,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { onMethod(key) }
                }
            }
            OutlinedTextField(
                value = amountText,
                onValueChange = { new -> onAmount(new.filter { it.isDigit() }.take(6)) },
                label = { Text(appText("Сумма, ₽ (необязательно)", "Сумма, ₽ (мотлаҡ түгел)")) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(color = CanonMint, shape = RoundedCornerShape(999.dp)) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(payMethodIcon(method), contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(payMethodLabel(method), color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
                if (summaryAmount != null) {
                    Text(kopToRub(rideshareRubToKop(summaryAmount)), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
internal fun BookingRouteMapPreview(   // internal: живой MapKit-рендер маршрута покрывается инструментальным тестом на ≤API-36 (JVM не может)
    modifier: Modifier = Modifier,
    from: String,
    to: String,
    fromPoint: Point,
    toPoint: Point,
    contactUnlocked: Boolean,
    showPrivacyNotice: Boolean = true,
    liveBookingId: Int? = null,
) {
    // Экран брони — единственный путь пассажира к «Забронировать»: без ключа MapKit (чистый worktree, CI)
    // он не падает, а показывает заглушку маршрута. Шов — MapKitGate.kt.
    if (!mapKitKeyAvailable()) { RouteMapUnavailableCard(modifier); return }
    val context = LocalContext.current
    val mapView = remember(fromPoint, toPoint) {
        ensureMapKit(context)   // русская локаль ставится тут же: голый initialize оставлял логотип «Yandex Maps» по-английски
        MapView(context).also { view ->
            view.setOnTouchListener { v, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                }
                false
            }
        }
    }
    DisposableEffect(mapView) {
        MapKitFactory.getInstance().onStart()
        mapView.onStart()
        onDispose {
            mapView.onStop()
            MapKitFactory.getInstance().onStop()
        }
    }
    LaunchedEffect(mapView, fromPoint, toPoint) {
        val map = mapView.mapWindow.map
        fitBookingRouteCamera(map, fromPoint, toPoint)?.let { camera ->
            map.move(camera, Animation(Animation.Type.SMOOTH, 0.25f), null)
        }
        map.mapObjects.addPolyline(Polyline(listOf(fromPoint, toPoint))).apply {
            setStrokeColor(CANON_ROUTE_MAIN_ARGB)
            strokeWidth = 5f
        }
        map.mapObjects.addPlacemark().apply { geometry = fromPoint }
        map.mapObjects.addPlacemark().apply { geometry = toPoint }
    }
    // На активной поездке эта же карта показывает живую позицию второго участника.
    // Маркер разрешён только для текущей брони: сохранённая точка другой поездки не должна
    // на мгновение появляться на новом экране. Первая точка ставится сразу, следующие мягко
    // догоняются каждый кадр — без рывков между редкими GPS-обновлениями.
    LaunchedEffect(mapView, liveBookingId) {
        if (liveBookingId == null) return@LaunchedEffect
        val map = mapView.mapWindow.map
        val marker = map.mapObjects.addPlacemark().apply {
            setIcon(ImageProvider.fromBitmap(peerArrowBitmap()))
            setIconStyle(
                IconStyle()
                    .setAnchor(PointF(0.5f, 0.5f))
                    .setRotationType(com.yandex.mapkit.map.RotationType.ROTATE)
            )
            isVisible = false
        }
        try {
            var currentLat = 0.0
            var currentLng = 0.0
            var currentBearing = 0f
            var hasPosition = false
            while (true) {
                androidx.compose.runtime.withFrameNanos { }
                val peer = com.yuldash.app.data.TripLocationBus.peer
                    ?.takeIf { com.yuldash.app.data.TripLocationBus.bookingId == liveBookingId }
                if (peer == null) {
                    if (hasPosition) {
                        marker.isVisible = false
                        hasPosition = false
                    }
                    kotlinx.coroutines.delay(200)
                    continue
                }
                if (!hasPosition) {
                    currentLat = peer.lat
                    currentLng = peer.lng
                    currentBearing = (peer.bearing ?: 0.0).toFloat()
                    marker.geometry = Point(currentLat, currentLng)
                    marker.setDirection(currentBearing)
                    marker.isVisible = true
                    hasPosition = true
                    continue
                }
                val alpha = 0.16f
                currentLat += (peer.lat - currentLat) * alpha
                currentLng += (peer.lng - currentLng) * alpha
                marker.geometry = Point(currentLat, currentLng)
                peer.bearing?.let { target ->
                    val delta = ((target.toFloat() - currentBearing) % 360f + 540f) % 360f - 180f
                    currentBearing = ((currentBearing + delta * alpha) % 360f + 360f) % 360f
                    marker.setDirection(currentBearing)
                }
            }
        } finally {
            runCatching { map.mapObjects.remove(marker) }
        }
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .border(1.dp, CanonBorder, RoundedCornerShape(22.dp))
    ) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        BookingMapLabel(from, Modifier.align(Alignment.TopStart).padding(12.dp))
        BookingMapLabel(to, Modifier.align(Alignment.CenterEnd).padding(12.dp))
        if (!contactUnlocked && showPrivacyNotice) {
            Card(
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                colors = CardDefaults.cardColors(containerColor = CanonSurface),
                shape = RoundedCornerShape(14.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.card)
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = CanonText)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        appText(
                            "Точная точка встречи откроется после подтверждения",
                            "Теүәл осрашыу урыны раҫланғандан һуң асыла"
                        ),
                        color = CanonText,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )
                }
            }
        }
    }
}

@Composable
internal fun BookingMapLabel(text: String, modifier: Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = CanonSurface,
        shadowElevation = CanonDepth.raised
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            color = CanonText,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun fitBookingRouteCamera(map: com.yandex.mapkit.map.Map, from: Point, to: Point): CameraPosition? = runCatching {
    val bbox = com.yandex.mapkit.geometry.BoundingBox(
        Point(minOf(from.latitude, to.latitude), minOf(from.longitude, to.longitude)),
        Point(maxOf(from.latitude, to.latitude), maxOf(from.longitude, to.longitude))
    )
    val fit = map.cameraPosition(com.yandex.mapkit.geometry.Geometry.fromBoundingBox(bbox))
    CameraPosition(fit.target, (fit.zoom - 0.55f).coerceIn(3f, 16f), 0f, 0f)
}.getOrNull()

@Composable
internal fun RouteMapUnavailableCard(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = CanonCardShape,
        border = BorderStroke(1.dp, CanonBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.card)
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(color = CanonMint, shape = CircleShape) {
                Icon(painterResource(R.drawable.yu_route), contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(12.dp).size(24.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    appText("Карта маршрута загружается", "Маршрут картаһы йөкләнә"),
                    color = CanonText,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Text(
                    appText(
                        "Покажем реальный маршрут, когда сервер вернёт координаты.",
                        "Сервер координаталарҙы биргәс, ысын маршрутты күрһәтербеҙ."
                    ),
                    color = CanonMuted,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
            }
        }
    }
}

@Composable
internal fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = CanonSurface), shape = CanonItemShape, elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.card)) {
        Column(Modifier.padding(vertical = 8.dp), content = content)
    }
}

@Composable
internal fun SettingsNavRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
    badge: Int = 0,   // >0 → зелёный бейдж непрочитанного (напр. новые ответы поддержки)
) {
    val modifier = if (onClick != null) Modifier.bounceClick(onClick) else Modifier
    Row(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(color = CanonMint, shape = RoundedCornerShape(14.dp)) {
            Icon(icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(12.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(subtitle, color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp)
        }
        if (badge > 0) {
            Surface(color = CanonGreen2, shape = RoundedCornerShape(999.dp)) {
                Text(
                    if (badge > 99) "99+" else badge.toString(),
                    color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
        }
        if (onClick != null) {
            Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = CanonMuted)
        }
    }
}

@Composable
internal fun SettingSwitchRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(color = CanonMint, shape = RoundedCornerShape(14.dp)) {
            Icon(icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(12.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(subtitle, color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp)
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

@Composable
internal fun CompactProfileBanner() {
    Card(colors = CardDefaults.cardColors(containerColor = Color.Transparent), shape = CanonItemShape, elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.card)) {
        Row(
            modifier = Modifier
                .background(Brush.linearGradient(listOf(CanonGreenInk, CanonGreenInkDark)), CanonItemShape)  // фикс тёмной темы: белый текст на фиксированном ink-зелёном
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(62.dp).background(Color.White.copy(alpha = 0.18f), CircleShape), contentAlignment = Alignment.Center) {
                Text((ApiClient.cachedName() ?: appText("Я", "Мин")).take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 24.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(ApiClient.cachedName() ?: appText("Я", "Мин"), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                // Настоящая роль из кеша /me (города в профиле нет — не выдумываем «Баймаҡ»).
                Text(roleLabel(ApiClient.cachedRole() ?: ""), color = Color.White.copy(alpha = 0.78f), fontSize = 14.sp)
                Text(appText("Телефон скрыт до подтверждения", "Телефон раҫланғанға тиклем йәшерен"), color = Color.White.copy(alpha = 0.78f), fontSize = 14.sp)
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun ActiveTripContent(
    ride: Ride?, contacts: List<TrustedContact>, bookingId: Int?,
    onBack: () -> Unit, onTripEnd: () -> Unit, onSos: () -> Unit,
    onSupport: () -> Unit, onOpenReceipt: (Int) -> Unit, tripSession: Long,
    chatOpen: Boolean?, onChatOpenChange: ((Boolean) -> Unit)?,
    terminalHint: ActiveTripTerminalHint?,
    onTripStopped: () -> Unit, onTrustedContacts: () -> Unit, onDriverFinished: (suspend (Int) -> Boolean)?,
    driverStageScope: kotlinx.coroutines.CoroutineScope? = null,
    isCurrentParent: (() -> Boolean)?,
    isCurrentReceiptParent: (() -> Boolean)?,
) {
    ResizeWindowForKeyboard()
    val context = LocalContext.current
    val voiceScope = rememberCoroutineScope()
    val tripOwner = remember(bookingId, tripSession) { OfflineTripSnapshots.currentOwner() }
    // Русский тут на «ты», башкирский был на «вы» (тикшерегеҙ) — тон обязан совпадать в обоих языках.
    val statusErrMsg = appText("Не удалось сохранить статус. Проверь сеть.", "Хәлде һаҡлап булманы. Селтәрҙе тикшер.")
    val driverNotifiedMsg = appText("Пассажир уведомлён", "Пассажир хәбәрҙар ителде")
    // Роль в этой брони: водитель видит «Я выехал/Подъезжаю» (push пассажиру), пассажир — «сел/доехал/завершить».
    // Ключуем по bookingId (как driverPhase/bookingStatus ниже): иначе при открытии ДРУГОЙ брони до первого
    // опроса видны кнопки чужой роли (водительские «Я выехал» у пассажира).
    var role by remember(bookingId) { mutableStateOf(terminalHint?.role.orEmpty()) }
    var driverPhase by remember(bookingId) { mutableStateOf("") }   // ""/departed/arriving — для live-баннера пассажиру
    // Сервер сверил «подъезжаю» с GPS водителя → пассажир видит «подтверждено по GPS» и знает,
    // что машина правда рядом, а не «уже почти» на словах (разбор конкурентов 2026-08-07).
    var arrivalVerified by remember(bookingId) { mutableStateOf(false) }
    // Попутчики вышли, остался один на один с водителем (см. AlonePassengerHint).
    var aloneWithDriver by remember(bookingId) { mutableStateOf(false) }
    var peerId by remember(bookingId) { mutableIntStateOf(0) }   // вторая сторона брони: пассажиру — водитель («Сообщить о проблеме»)
    var boardingUndoSec by remember(bookingId) { mutableIntStateOf(0) }   // водителю: сколько ещё можно отменить свою отметку «Сел»
    var bookingStatus by remember(bookingId) { mutableStateOf(terminalHint?.status.orEmpty()) }
    // Связь с водителем из деталей брони (RideshareTripContacts.kt); подтвердили при открытом экране — перечитать.
    var liveContacts by remember(bookingId) { mutableStateOf<TripContacts?>(null) }
    var contactsLoaded by remember(bookingId) { mutableStateOf(false) }
    var pickupDetails by remember(bookingId) { mutableStateOf<com.yuldash.app.data.BookingDetailsDto?>(null) }
    val tripDetails = remember(bookingId) { ActiveTripDetailsState(bookingId, tripSession) }
    val inTransit = rememberCancelledInTransitState(bookingId, terminalHint, tripSession)   // отменили в пути: экран остаётся
    val boardingGate = remember(bookingId) { BoardingUndoState() }
    var boardingUndo by remember(bookingId) { mutableStateOf(com.yuldash.app.data.BoardingUndoTerms()) }
    var boardingCode by remember(bookingId) { mutableStateOf("") }
    var codeLoadRetry by remember(bookingId) { mutableStateOf(0) }
    fun acceptBookingStatus(value: String, terms: com.yuldash.app.data.BoardingUndoTerms = boardingUndo, undoSec: Int? = null): Boolean {
        // A delayed poll must not revive a terminal booking or interrupt cleanup/retry.
        if (!boardingGate.accept(bookingStatus, value, terms)) return false
        if (bookingStatus == "onboard" && value == "confirmed") { boardingCode = ""; codeLoadRetry++ }
        tripDetails.statusChanged(bookingStatus, value)
        bookingStatus = value; boardingUndo = terms
        if (undoSec != null) boardingUndoSec = undoSec
        return true
    }
    // F11: офлайн-паспорт брони. Читаем СРАЗУ из локального (secure) хранилища — данные видны без сети.
    val authorityRevision by OfflineTripSnapshots.version.collectAsState()
    fun isTripSessionCurrent() = voiceScope.isActive && ApiClient.isCurrentSession(tripSession) && OfflineTripSnapshots.currentOwner() == tripOwner && (isCurrentParent?.invoke() != false)
    // Only receipt navigation can use the lifetime of its explicit, stronger parent.
    fun isTripReceiptSessionCurrent() = ApiClient.isCurrentSession(tripSession) &&
        OfflineTripSnapshots.currentOwner() == tripOwner &&
        (isCurrentReceiptParent?.invoke() ?: (voiceScope.isActive && isCurrentParent?.invoke() != false))
    val offlinePassState = rememberActiveTripOfflinePass(bookingId, tripSession, boardingCode, bookingStatus.takeIf { it in setOf("done", "cancelled") }, role, pickupDetails, bookingStatus) { isTripSessionCurrent() && bookingId?.let(OfflineTripSnapshots::allowsLive) == true }
    var tripPass by offlinePassState::pass
    fun isTripDetailsCurrent() = voiceScope.isActive && isTripSessionCurrent() && bookingStatus != "done" && bookingStatus != "cancelled"
    fun isTripChatActive() = isTripDetailsCurrent() && (bookingId == null || OfflineTripSnapshots.allowsLive(bookingId))
    LaunchedEffect(bookingId, terminalHint) {
        val id = bookingId ?: return@LaunchedEffect
        val hint = terminalHint ?: return@LaunchedEffect
        val accepted = OfflineTripSnapshots.terminal(id) ?: return@LaunchedEffect
        if (isTripSessionCurrent() && hint.bookingId == id && accepted.owner == tripOwner &&
            accepted.status == hint.status && accepted.role == hint.role) {
            // Apply the accepted server fact before terminal cleanup observes the new status.
            // The incoming hint alone cannot promote an ordinary cancellation to in-transit help.
            inTransit.acceptTerminal(accepted.status, accepted.inTransit)
            role = accepted.role
            acceptBookingStatus(accepted.status)
        }
    }
    val terminalBooking = bookingStatus.takeIf { it == "done" || it == "cancelled" }
    // Конец поездки: паспорт удаляется, экран закрывается сам — кроме отмены в пути (CancelledInTransit.kt).
    ActiveTripTerminalCleanup(bookingId, terminalBooking, tripSession, role, inTransit,
        onPassDropped = {
            inTransit.keep(tripPass)   // машина и госномер из паспорта остаются на экране помощи, телефон — нет
            tripPass = null
        },
        onFinish = onTripEnd)
    // offline = последний опрос состояния упал по СЕТИ (не по ответу сервера). Тогда показываем паспорт+плашку.
    var offline by remember(bookingId) { mutableStateOf(false) }
    var serverBack by remember(bookingId) { mutableIntStateOf(0) }   // опрос снова дошёл до сервера → разгрузить очередь
    // Опрос состояния поездки раз в ~12с: роль + подфаза водителя. Так пассажир видит «водитель выехал/
    // подъезжает» LIVE (раньше это приходило только пушем — его легко пропустить, а UI не обновлялся).
    // На паузе в фоне (repeatOnLifecycle RESUMED) — не дёргаем сервер и батарею, когда приложение свёрнуто.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(bookingId, lifecycleOwner) {
        val id = bookingId ?: return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // Recheck on lifecycle resume and before every poll: this screen belongs to one login.
            while (tripSession == ApiClient.queueSessionGeneration()) {
                val detailsFailure = tripDetails.failure
                var acceptedRole: com.yuldash.app.data.AcceptedRoleRead? = null
                val stateResult = boardingGate.readFresh(id) { requestedId ->
                    acceptedRole = null
                    val read = ApiClient.getTripStateForNavigation(requestedId)
                    acceptedRole = read.acceptedRole
                    read.result
                }
                if (tripSession != ApiClient.queueSessionGeneration()) return@repeatOnLifecycle
                stateResult
                    .onSuccess { st ->
                        if (bookingStatus == "cancelled" && inTransit.keepsScreen(role) &&
                            st.status == "cancelled" && st.role == "passenger" &&
                            tripOwner != null && tripOwner.generation == tripSession && isTripSessionCurrent() &&
                            acceptedRole?.matches(id, tripOwner, st) == true) {
                            inTransit.accept(st)
                            return@onSuccess
                        }
                        if (!acceptBookingStatus(st.status, st.boardingUndo, st.boardingUndoSec)) return@onSuccess
                        if (offline || Outbox.hasPending(context)) serverBack++
                        if (st.role.isNotBlank()) role = st.role; driverPhase = st.driverPhase; arrivalVerified = st.arrivalVerified; aloneWithDriver = st.aloneWithDriver; peerId = st.peerId; boardingUndoSec = st.boardingUndoSec; inTransit.accept(st); offline = false
                        if (isTripChatActive()) tripDetails.serverReached(detailsFailure, role, bookingStatus)
                    }
                    // Сетевой сбой (не ApiException) → уходим в офлайн-режим: поднимаем сохранённый паспорт.
                    .onFailure { e -> if (e !is ApiException) offline = true }
                kotlinx.coroutines.delay(12_000)
            }
        }
    }
    // F12 «Зимний протокол»: мягкая проверка «доехал?». Показываем ОДИН раз за поездку
    // (rememberSaveable переживает поворот и kill процесса). departIso — старт поездки (raw ISO).
    // Момент проверки считаем по РАСЧЁТНОЙ ETA маршрута (расстояние/скорость + запас), а НЕ по
    // фиксированному часу — иначе на длинном межгороде спросили бы «доехал?» в середине пути и
    // могли зря потревожить близкого через шаринг. Нет координат маршрута → щедрый фолбэк.
    // Диалог и таймер — общие с такси и доставкой (WinterProtocol.kt): три копии уже однажды
    // разошлись, и попутка осталась единственным сценарием с зимним протоколом.
    val showArrivalCheck = rememberSaveable(bookingId) { mutableStateOf(false) }
    val arrivalAsked = rememberSaveable(bookingId) { mutableStateOf(false) }
    var departIso by remember(bookingId) { mutableStateOf("") }
    var armAfterMs by remember(bookingId) { mutableStateOf(ARRIVAL_CHECK_FALLBACK_MS) }
    fun isWinterBookingCurrent() = bookingId != null && bookingId > 0 && isTripChatActive() &&
        role == "passenger" && bookingStatusAllowsBoarding(bookingStatus)
    // F12: пробудить проверку «доехал?» один раз, когда прошёл буфер после выезда, а поездка
    // ещё активна (не done/cancelled). Буфер — эвристика (ETA в этом экране нет): сервер сам
    // не пошлёт пуш до depart_at и не эскалирует раньше 30 мин + активного шаринга.
    WinterArrivalWatcher(
        key = bookingId,
        startMs = {
            val iso = departIso.ifBlank { tripPass?.departAt ?: "" }
            iso.takeIf { it.isNotBlank() }?.let(::parseIsoUtcMillis)
        },
        // Водителя не спрашиваем: протокол сторожит того, кого везут.
        active = ::isWinterBookingCurrent,
        asked = arrivalAsked,
        show = showArrivalCheck,
        armAfterMs = armAfterMs,
        ownerGeneration = tripSession,
        isCurrentTarget = ::isWinterBookingCurrent,
        onArm = { owner -> ApiClient.winterCheck(bookingId!!, expectedGeneration = owner) },
    )
    // Ключуем по bookingId: выбранный статус не должен утекать в другую бронь (черновик — в TripChatState).
    var status by remember(bookingId) { mutableStateOf<String?>(null) }
    var showDriverFinishConfirmation by rememberSaveable(bookingId) { mutableStateOf(false) }
    var driverFinishing by remember(bookingId, tripSession) { mutableStateOf(false) }
    var showShare by remember { mutableStateOf(false) }

    val frostyNight = remember { isFrostyWinterNight() }   // F12: морозная ночь — считаем один раз (LazyListScope не @Composable)
    var codeLoadFailed by remember(bookingId) { mutableStateOf(false) }
    var codeSaveFailed by offlinePassState::failed
    var codeSaveBusy by offlinePassState::busy
    var codeSaveRetry by offlinePassState::retry

    // Договорённость об оплате (ЗАПИСЬ, не платёж) — показываем обеим сторонам в активной поездке.
    var payMethod by remember(bookingId) { mutableStateOf("negotiate") }
    var payAmount by remember(bookingId) { mutableStateOf<Int?>(null) }
    // Точки маршрута для новой B-карты. Берём точные координаты из деталей брони; если
    // старый сервер их не отдаёт, ниже остаётся безопасный фолбэк на публичные центры городов.
    var routeFromPoint by remember(bookingId) { mutableStateOf<Point?>(null) }
    var routeToPoint by remember(bookingId) { mutableStateOf<Point?>(null) }
    var detailsRoute by remember(bookingId) { mutableStateOf("" to "") }   // города из деталей брони: запас, пока в поездке-заглушке пусто (TripRouteLabel.kt)
    val queuedMsg = appText("Нет сети — отправим позже", "Селтәр юҡ — һуңыраҡ ебәрербеҙ")
    val queueSaveFailMsg = appText("Не получилось сохранить. Повтори попытку.", "Һаҡлап булманы. Ҡабатлап ҡара.")
    // Чат поездки: лента, сокет, отправка, голос, фото (TripChatState.kt). Один экземпляр на оба
    // вида — карточку здесь и полноэкранный чат, поэтому переход между ними ничего не теряет.
    val chatTexts = rememberTripChatTexts(queuedMsg, queueSaveFailMsg)
    val chat = remember(bookingId) {
        TripChatState(context, bookingId, tripSession, voiceScope, ::isTripChatActive, ::isTripSessionCurrent, chatTexts,
            isHistoryParentCurrent = ::isTripSessionCurrent)
    }
    val chatListState = rememberLazyListState()
    // Вид поездки уходит из композиции, пока открыт чат: его сохраняемое состояние (закрытые
    // подсказки, позиции внутри списка) держим здесь, чтобы при возврате всё было как было.
    val viewStates = rememberSaveableStateHolder()
    // Вид экрана: поездка или чат. С навигацией вид задаёт Screen.TripChat (chatOpen), а пока
    // экран уезжает (chatOpen == null) — держим последний, чтобы чат не мигнул поездкой.
    var localChat by rememberSaveable(bookingId) { mutableStateOf(chatOpen == true) }
    val lastChatView = remember { booleanArrayOf(chatOpen == true) }
    if (chatOpen != null) lastChatView[0] = chatOpen
    val showChat = if (onChatOpenChange == null) localChat else lastChatView[0]
    fun showChatView(open: Boolean) { if (onChatOpenChange == null) localChat = open else onChatOpenChange(open) }

    // Детали брони: оплата, маршрут и — пассажиру подтверждённой брони — телефон, машина, место встречи.
    suspend fun loadTripDetails() {
        val d = tripDetails.load(::isTripDetailsCurrent, { bookingStatus }, { role },
            confirmsBoardingCorrection = { current, incoming -> boardingGate.canConfirmCorrection(current, incoming.status, incoming.boardingUndo) }, initial = bookingId?.let { OfflineTripSnapshots.projection(it)?.details }) ?: return
        // Details are authoritative too: retry can recover even while the independent role poll is offline.
        if (role.isBlank()) role = d.role
        if (d.status.isNotBlank() && !acceptBookingStatus(d.status, d.boardingUndo)) return
        inTransit.accept(d)
        pickupDetails = d
        contactsLoaded = true
        payMethod = d.payMethod
        payAmount = d.payAmount
        if (d.departAt.isNotBlank()) departIso = d.departAt
        routeFromPoint = d.fromLat?.let { lat -> d.fromLng?.let { lng -> Point(lat, lng) } }
        routeToPoint = d.toLat?.let { lat -> d.toLng?.let { lng -> Point(lat, lng) } }
        detailsRoute = d.fromCity to d.toCity
        armAfterMs = arrivalCheckAfterMs(d.fromLat, d.fromLng, d.toLat, d.toLng)
        liveContacts = passengerContacts(d)
        tripDetails.privateRecoveryCommitted(d, ::isTripDetailsCurrent, role, bookingStatus)
    }
    // История независима от кода и приватных деталей поездки.
    val historyReady = authorityRevision.let { isTripChatActive() }
    val requestedHistoryTick = chat.historyTick
    LaunchedEffect(bookingId, chat, requestedHistoryTick, historyReady) {
        val id = bookingId ?: run { chat.historyLoading = false; return@LaunchedEffect }
        if (!historyReady || !isTripChatActive()) return@LaunchedEffect
        chat.loadInitialHistory(id, requestedHistoryTick)
    }
    ActiveTripDetailsRefresh(tripDetails, ::isTripDetailsCurrent, ::loadTripDetails)
    val contactCancelMsg = appText(
        "Договорились ехать? Заверши поездку в приложении — так работает защита и SOS 💚",
        "Барырға һөйләштегеҙме? Сәфәрҙе ҡушымтала тамамла — шулай яҡлау һәм SOS эшләй 💚",
    )
    val cancelOkMsg = appText("Поездка отменена", "Сәфәр кире алынды")
    val passengerTripCancellation = if (bookingId != null && role == "passenger") {
        rememberPassengerCancellation(tripSession, bookingId,
            "confirmed".takeIf { activeTripShowsCancel(role, hasBooking = true, bookingStatus = bookingStatus) },
            isCurrent = ::isTripSessionCurrent) { result ->
            val status = result?.bookingStatus
            if (status != null) acceptBookingStatus(status)
            if (status == "cancelled") {
                val message = if (result?.contactThenCancel == true) contactCancelMsg else cancelOkMsg
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            } else if (status != "done") tripDetails.retry()
        }
    } else null

    // Начальный код и его повтор — один независимый запрос, без истории/деталей/изменений поездки.
    val privateDetailsReady = authorityRevision.let { currentTripPrivateDetails(bookingId)?.role in setOf("driver", "passenger") }
    val privateDataMissing = isTripDetailsCurrent() && tripDetails.privateDataMissing(privateDetailsReady, role, bookingStatus)
    // Only a completed screen load is a new CODE intent, even when DTO fields stayed equal.
    // CODE may internally renew DETAILS; that preflight belongs to this same intent.
    val codeDetailsCycle = tripDetails.completedLoad.takeIf {
        tripDetails.completedDetails != null && !tripDetails.loading && !tripDetails.needsReload
    }
    val canFetchCode = bookingId?.let(OfflineTripSnapshots::allowsLive) == true && privateDetailsReady && codeDetailsCycle != null && !tripDetails.privateRecoveryPending
    LaunchedEffect(bookingId, codeLoadRetry, canFetchCode, codeDetailsCycle) {
        val id = bookingId ?: return@LaunchedEffect
        if (!isTripChatActive() || !canFetchCode || tripDetails.completedLoad != codeDetailsCycle ||
            tripDetails.loading || tripDetails.needsReload || !tripDetails.privateCodeReadStarted()) return@LaunchedEffect
        codeLoadFailed = false
        val result = ApiClient.getBoardingCode(id)
        if (!isTripSessionCurrent() || bookingStatus == "done" || bookingStatus == "cancelled" ||
            tripDetails.completedLoad != codeDetailsCycle || tripDetails.loading || tripDetails.needsReload) return@LaunchedEffect
        val code = result.getOrNull()
        if (code != null && (code.isBlank() || !isTripChatActive() || currentTripPrivateDetails(id) == null ||
                OfflineTripSnapshots.currentBoardingCode(id) != code)) return@LaunchedEffect
        codeLoadFailed = code.isNullOrBlank()
        if (code != null) boardingCode = code
    }

    // Realtime по WebSocket и дотяжка пропущенного после обрыва — у владельца, не у вида чата.
    TripChatConnection(chat, terminalBooking ?: "unverified".takeUnless { isTripChatActive() })
    // С навигацией закрытая поездка показывается видом поездки (экран завершения, очистка):
    // заменяем чат поездкой, чтобы «Назад» не водил по двум одинаковым экранам. Только пока чат
    // действительно на экране (chatOpen == true), а не во время ухода с него.
    LaunchedEffect(terminalBooking, chatOpen) {
        if (terminalBooking != null && chatOpen == true && onChatOpenChange != null) onChatOpenChange(false)
    }

    // F11: авто-ретрай очереди исходящих при появлении сети. Слушаем ConnectivityManager: сеть вернулась →
    // разгружаем очередь (сообщения/статусы), затем подтягиваем авторитетную историю и состояние.
    fun flushOutbox() {
        val id = bookingId ?: return
        if (!isTripChatActive()) return
        voiceScope.launch {
            // Очередь и лента чата — у состояния чата (TripChatState.flushQueued): неотправленное остаётся на экране.
            if (!isTripChatActive() || !chat.flushQueued() || !isTripChatActive()) return@launch
            boardingGate.readFresh(id).onSuccess { st ->
                if (!isTripChatActive()) return@onSuccess
                if (!acceptBookingStatus(st.status, st.boardingUndo, st.boardingUndoSec)) return@onSuccess
                if (st.role.isNotBlank()) role = st.role; driverPhase = st.driverPhase; arrivalVerified = st.arrivalVerified
                aloneWithDriver = st.aloneWithDriver; peerId = st.peerId; boardingUndoSec = st.boardingUndoSec; inTransit.accept(st); offline = false
            }
        }
    }
    // Разгружаем очередь при входе на экран (мог накопить в прошлой сессии без сети) и когда опрос снова
    // дошёл до сервера: интернет у телефона мог и не пропадать — лежал сервер (приёмка 18.10).
    LaunchedEffect(bookingId, serverBack) { if (bookingId != null) flushOutbox() }
    DisposableEffect(bookingId) {
        val id = bookingId
        if (id == null) { onDispose { } }
        else {
            val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            val cb = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) { flushOutbox() }
            }
            runCatching { cm?.registerDefaultNetworkCallback(cb) }
            onDispose { runCatching { cm?.unregisterNetworkCallback(cb) } }
        }
    }

    val activeFromCity = tripRouteCity(ride?.from, detailsRoute.first, tripPass?.fromCity)
    val activeToCity = tripRouteCity(ride?.to, detailsRoute.second, tripPass?.toCity)
    val activeFromPoint = routeFromPoint ?: activeFromCity.takeIf { it.isNotBlank() }?.let(::cityPoint)
    val activeToPoint = routeToPoint ?: activeToCity.takeIf { it.isNotBlank() }?.let(::cityPoint)
    val canChangeTripStatus = bookingId == null || (role.isNotBlank() && bookingStatusAllowsBoarding(bookingStatus))
    val nextTripStatus = activeTripNextStatus(role = role, driverPhase = driverPhase, passengerStatus = status, bookingStatus = bookingStatus)
    val activeTripListState = rememberLazyListState()

    // Один шов для всех status-CTA: новая закреплённая кнопка B и старые сценарии используют
    // тот же серверный переход, очередь без сети и очистку офлайн-паспорта.
    val gpsAction = rememberDriverGpsAction(bookingId)
    val stageRecovery = rememberDriverStageRecovery(tripSession, role == "driver" && bookingId != null, bookingId)
    fun updateTripStatus(st: String) {
        val bid = bookingId
        if (!isTripChatActive()) return
        val queueSession = tripSession
        val driverStage = if (role == "driver") when (st) {
            "boarded" -> DriverStageApi.Stage.Boarded
            "done" -> DriverStageApi.Stage.Done
            else -> null
        } else null
        if (driverStage != null && bid != null && stageRecovery.blocked(bid)) {
            if (st == "done") { driverFinishing = false; showDriverFinishConfirmation = false }
            return
        }
        val gpsTicket = if (role == "driver" && bid != null) gpsAction.begin(bid) else null
        if (role == "driver") {
            if (bid == null) {
                bookingStatus = when (st) { "done" -> "done"; "boarded" -> "onboard"; else -> bookingStatus }
            } else (if (driverStage != null) driverStageScope ?: voiceScope else voiceScope).launch { try {
                if (!isTripSessionCurrent()) return@launch
                if (driverStage != null) {
                    stageRecovery.busy = true
                    stageRecovery.feedback = null
                    stageRecovery.error = null
                    val result = DriverStageApi.advance(queueSession, bid, driverStage)
                    if (!ApiClient.isCurrentSession(queueSession)) return@launch
                    val fresh = result.getOrNull()?.trip
                    val terminal = fresh?.status in setOf("done", "cancelled")
                    if (terminal) DriverGpsActions.closed(bid)
                    gpsAction.finish(gpsTicket, if (st == "done") terminal else fresh?.status == "onboard", closed = terminal)
                    if (fresh?.status == "done" && onDriverFinished?.invoke(bid) == true) return@launch
                    if (!isTripSessionCurrent()) return@launch
                    result.onSuccess { stageRecovery.feedback = it }.onFailure { stageRecovery.error = it }
                    stageRecovery.reload++
                    if (fresh != null && acceptBookingStatus(fresh.status, fresh.boardingUndo, fresh.boardingUndoSec)) {
                        boardingGate.actionCommitted()
                        role = fresh.role; driverPhase = fresh.driverPhase; arrivalVerified = fresh.arrivalVerified
                        aloneWithDriver = fresh.aloneWithDriver; peerId = fresh.peerId; boardingUndoSec = fresh.boardingUndoSec
                        inTransit.accept(fresh)
                    }
                    return@launch
                }
                ApiClient.driverStatus(bid, st)
                    .also { gpsAction.finish(gpsTicket, it.isSuccess, closed = st == "done") }
                    .onSuccess {
                        if (!isTripSessionCurrent()) return@onSuccess
                        if (st == "done") {
                            if (onDriverFinished?.invoke(bid) == true) return@onSuccess
                            bookingStatus = "done"
                        } else {
                            Toast.makeText(context, driverNotifiedMsg, Toast.LENGTH_SHORT).show()
                            boardingGate.readFresh(bid).onSuccess { s ->
                                if (!isTripSessionCurrent()) return@onSuccess
                                if (!acceptBookingStatus(s.status, s.boardingUndo, s.boardingUndoSec)) return@onSuccess
                                if (s.role.isNotBlank()) role = s.role
                                driverPhase = s.driverPhase
                                arrivalVerified = s.arrivalVerified
                                aloneWithDriver = s.aloneWithDriver; peerId = s.peerId; boardingUndoSec = s.boardingUndoSec
                                inTransit.accept(s)
                                if (isTripDetailsCurrent()) tripDetails.serverReached(tripDetails.failure, role, bookingStatus)
                            }
                        }
                    }
                    .onFailure { e ->
                        if (!isTripSessionCurrent()) return@onFailure
                        if (e !is ApiException) {
                            val saved = withContext(Dispatchers.IO) { Outbox.enqueue(context, Outbox.newDriverStatus(bid, st), queueSession) }
                            if (!isTripSessionCurrent()) return@onFailure
                            Toast.makeText(context, if (saved) queuedMsg else queueSaveFailMsg, Toast.LENGTH_SHORT).show()
                        } else {
                            // Сервер объясняет отказ подробно: «ты ещё далеко от места
                            // подачи (≈1.4 км)». Своё «проверь сеть» здесь неправда —
                            // сеть работает, а водитель жмёт кнопку снова и снова.
                            Toast.makeText(context, serverSaid(e, statusErrMsg),
                                           Toast.LENGTH_LONG).show()
                        }
                    }
            } finally {
                if (driverStage != null) {
                    DriverGpsActions.fail(gpsTicket)
                    stageRecovery.busy = false
                }
                if (st == "done") { driverFinishing = false; showDriverFinishConfirmation = false }
            } }
        } else {
            val previousStatus = status
            status = st
            if (bid == null) {
                if (st == "done") bookingStatus = "done"
            } else voiceScope.launch {
                if (!isTripSessionCurrent()) return@launch
                ApiClient.setTripStatus(bid, st)
                    .onSuccess {
                        if (!isTripSessionCurrent()) return@onSuccess
                        if (st == "done") {
                            bookingStatus = "done"
                        } else boardingGate.readFresh(bid).onSuccess { s ->
                                if (!acceptBookingStatus(s.status, s.boardingUndo, s.boardingUndoSec)) return@onSuccess
                            if (!isTripSessionCurrent()) return@onSuccess
                            if (s.role.isNotBlank()) role = s.role
                            driverPhase = s.driverPhase
                            arrivalVerified = s.arrivalVerified
                            aloneWithDriver = s.aloneWithDriver; peerId = s.peerId; boardingUndoSec = s.boardingUndoSec
                            inTransit.accept(s)
                        }
                    }
                    .onFailure { e ->
                        if (!isTripSessionCurrent()) return@onFailure
                        if (e !is ApiException) {
                            val saved = withContext(Dispatchers.IO) { Outbox.enqueue(context, Outbox.newTripStatus(bid, st), queueSession) }
                            if (!isTripSessionCurrent()) return@onFailure
                            if (!saved && status == st) status = previousStatus
                            Toast.makeText(context, if (saved) queuedMsg else queueSaveFailMsg, Toast.LENGTH_SHORT).show()
                        } else {
                            // A rejected server transition must leave the same passenger action retryable.
                            // Do not undo a newer state if another operation already advanced it.
                            if (status == st) status = previousStatus
                            // Сервер объясняет отказ подробно: «ты ещё далеко от места
                            // подачи (≈1.4 км)». Своё «проверь сеть» здесь неправда —
                            // сеть работает, а водитель жмёт кнопку снова и снова.
                            Toast.makeText(context, serverSaid(e, statusErrMsg),
                                           Toast.LENGTH_LONG).show()
                        }
                    }
            }
        }
    }

    // Завершение больше не выбрасывает человека сразу на карту. Сначала показываем отдельный
    // экран B: благодарность, оценку и действия после поездки. У демо без bookingId нет
    // серверного чека и контрагента, поэтому оно остаётся в прежнем локальном сценарии.
    if (bookingStatus == "done" && bookingId != null) {
        RideshareCompletedScreen(
            bookingId = bookingId,
            ride = ride,
            fallbackRole = role,
            fallbackPayMethod = payMethod,
            fallbackPayAmount = payAmount,
            isCurrentParent = ::isTripSessionCurrent,
            isCurrentReceiptParent = ::isTripReceiptSessionCurrent,
            driverStageRecovery = if (role == "driver" && stageRecovery.visible) ({
                DriverStageRecovery(tripSession, stageRecovery, driverStageScope) { id, fresh ->
                    if (fresh.status == "done") onDriverFinished?.invoke(id)
                }
            }) else null,
            onClose = onTripEnd,
            onOpenReceipt = { onOpenReceipt(bookingId) },
            onSupport = onSupport,
        )
        return
    }

    // Отменили, когда пассажир был в машине (23.10.2026): не закрываемся — SOS, «Застрял», разбор, машина и номер.
    if (bookingId != null && bookingStatus == "cancelled" && inTransit.keepsScreen(role)) {
        CancelledInTransitScreen(bookingId, inTransit, tripSession, onSos, onClose = onTripEnd, onShown = onTripStopped)
        return
    }
    // An unknown booking is not a confirmed ride. Keep the independent loaders and retry alive,
    // but do not expose a boarding code, contacts, map or trip actions before authoritative data.
    // A passenger's existing encrypted offline passport remains usable without the role response.
    if (bookingId != null && (bookingStatus == "cancelled" || authorityRevision.let { !OfflineTripSnapshots.allowsLive(bookingId) })) {
        val completingAcceptedDriverTrip = driverFinishing && showDriverFinishConfirmation &&
            role == "driver" && isTripSessionCurrent() &&
            OfflineTripSnapshots.terminal(bookingId)?.let {
                it.owner == tripOwner && it.role == "driver" && it.status == "done"
            } == true
        if (completingAcceptedDriverTrip) {
            DriverFinishDialog(busy = true, onConfirm = {}, onDismiss = {})
            return
        }
        ActiveTripUnverifiedScreen(tripDetails.loading || tripDetails.needsReload,
            tripDetails.failure ?: IllegalStateException(), { if (isTripDetailsCurrent()) tripDetails.retryUnverified() },
            onBack, cancelled = bookingStatus == "cancelled")
        return
    }

    if (showDriverFinishConfirmation) DriverFinishDialog(
        busy = driverFinishing,
        onDismiss = { showDriverFinishConfirmation = false },
        onConfirm = {
            if (!driverFinishing && isTripChatActive()) {
                driverFinishing = bookingId != null
                if (bookingId == null) showDriverFinishConfirmation = false
                updateTripStatus("done")
            }
        },
    )

    // Шапка чата — с кем переписка. Имя из вкладки «Чат» известно сразу; пассажиру собеседник —
    // водитель брони, водителю — пассажир из деталей брони; пока имени нет — пишем роль.
    val chatPeer = ride?.chatPeer?.takeIf { it.isNotBlank() } ?: if (role != "passenger") pickupDetails?.passengerName?.takeIf { it.isNotBlank() }
        else ride?.driver.orEmpty().ifBlank { tripPass?.driverName.orEmpty() }.takeIf { it.isNotBlank() }
    val chatTitle = chatPeer ?: when (role) {
        "driver" -> appText("Чат с пассажиром", "Пассажир менән чат")
        "passenger" -> appText("Чат с водителем", "Йөрөтөүсе менән чат")
        else -> appText("Чат по поездке", "Сәфәр буйынса чат")
    }
    val chatRoute = tripRouteLabel(activeFromCity, activeToCity)
    // Связь с водителем: свежая из деталей, без сети — из паспорта. Водителю и закрытой брони — нет.
    val tripContacts = (currentTripPrivateDetails(bookingId)?.let(::passengerContacts) ?: tripPass?.takeIf(OfflineTripSnapshots::matchesPass)?.contacts()).takeIf {
        terminalBooking == null && role != "driver" && (bookingStatus.isBlank() || bookingStatus in CONTACT_STATUSES)
    }
    // Без навигации системное «Назад» из чата возвращает к поездке (с навигацией — общий goBack).
    BackHandler(enabled = showChat && onChatOpenChange == null) { localChat = false }
    AnimatedContent(targetState = showChat, transitionSpec = { tripChatTransition(opening = targetState) }, label = "trip-chat") { chatShown ->
    if (chatShown) TripChatScreen(
        chat = chat, title = chatTitle, peerInitial = chatPeer?.let(::firstGrapheme), subtitle = chatRoute,
        listState = chatListState,
        onBack = { if (onChatOpenChange == null) localChat = false else onBack() },
        onOpenTrip = { showChatView(false) },
        onSos = onSos,
    ) else viewStates.SaveableStateProvider("trip-view") { Scaffold(
        modifier = Modifier.tripAuthorityDraw(::isTripChatActive),
        containerColor = CanonBg,
        topBar = { ScreenTopBar(appText("Моя поездка", "Минең сәфәр"), onBack) },
        bottomBar = {
            if (canChangeTripStatus) {
                ActiveTripDecisionBar(
                    primaryText = activeTripActionLabel(nextTripStatus),
                    primaryEnabled = role != "driver" || bookingId == null || nextTripStatus !in setOf("boarded", "done") || !stageRecovery.blocked(bookingId),
                    finishEnabled = bookingId == null || !stageRecovery.blocked(bookingId),
                    onMessage = { showChatView(true) },   // переписка — отдельным экраном, как в мессенджерах
                    unread = chat.unread,
                    onPrimary = {
                        if (isTripSessionCurrent() &&
                            (role != "driver" || bookingId == null || nextTripStatus !in setOf("boarded", "done") ||
                                !stageRecovery.blocked(bookingId))) {
                            if (role == "driver" && nextTripStatus == "done") showDriverFinishConfirmation = true
                            else updateTripStatus(nextTripStatus)
                        }
                    },
                    finishText = if (activeTripShowsDriverFinish(role, driverPhase, nextTripStatus)) {
                        appText("Завершить поездку", "Сәфәрҙе тамамлау")
                    } else null,
                    onFinish = {
                        if (isTripSessionCurrent() && (bookingId == null || !stageRecovery.blocked(bookingId))) {
                            showDriverFinishConfirmation = true
                        }
                    },
                    boarding = activeTripBoardingSlot(role, bookingStatus, bookingId, peerId, if (role == "driver") "" else ride?.driver.orEmpty().ifBlank { tripPass?.driverName.orEmpty() },
                        boardingUndoSec, undoTerms = boardingUndo, onUndone = { r -> if (isTripSessionCurrent() && boardingGate.resultIsCurrent(bookingStatus, r) && acceptBookingStatus(r.status, r.undo, r.undoSec)) boardingGate.actionCommitted() }),
                )
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp).imePadding().testTag("activeTripList"), // поле отзыва (демо без брони) не прячется за клавиатурой
            state = activeTripListState,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            if (role == "driver" && stageRecovery.visible) item(key = "trip-driver-stage-recovery") {
                DriverStageRecovery(tripSession, stageRecovery, driverStageScope) { id, fresh ->
                    if (fresh.status == "done") onDriverFinished?.invoke(id)
                    if (id == bookingId && isTripSessionCurrent() &&
                        acceptBookingStatus(fresh.status, fresh.boardingUndo, fresh.boardingUndoSec)) {
                        boardingGate.actionCommitted()
                        role = fresh.role; driverPhase = fresh.driverPhase; arrivalVerified = fresh.arrivalVerified
                        aloneWithDriver = fresh.aloneWithDriver; peerId = fresh.peerId; boardingUndoSec = fresh.boardingUndoSec
                        inTransit.accept(fresh)
                    }
                }
            }
            if (role == "driver" && bookingId != null && bookingStatus in setOf("confirmed", "onboard")) item(key = "driver-gps-share") { DriverRideShareButton(bookingId, pickupDetails?.rideId ?: 0) }
            item(key = "boarding-code-save") {
                AnimatedVisibility(visible = codeSaveFailed && terminalBooking == null) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (codeSaveBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = CanonGreen2)
                        EmptyStateCard(
                            title = appText("Код не сохранён на телефоне", "Код телефонда һаҡланманы"),
                            action = if (codeSaveBusy) null else appText("Сохранить код ещё раз", "Кодты тағы һаҡларға"),
                            text = appText(
                                "Код виден сейчас, но без сети может быть недоступен. Попробуй сохранить ещё раз. Если не получится, открой бронь и сохрани поездку заново.",
                                "Код хәҙер күренә, ләкин интернетһыҙ асылмауы мөмкин. Тағы һаҡлап ҡара. Булмаһа, бронде ас та сәфәрҙе яңынан һаҡла.",
                            ),
                            icon = Icons.Default.Refresh,
                            onAction = {
                                if (!codeSaveBusy) {
                                    codeSaveBusy = true
                                    codeSaveRetry++
                                }
                            },
                        )
                    }
                }
            }
            if (terminalBooking == null && (tripDetails.loading || tripDetails.failure != null || privateDataMissing)) item(key = "trip-details-load") {
                ActiveTripDetailsNotice(tripDetails.loading || tripDetails.needsReload, tripDetails.failure,
                    { tripDetails.retryData(::isTripDetailsCurrent, role, bookingStatus) })
            }
            // Водителю вверху — тот, кого он везёт, а не карточка «Водитель» с ним самим (приёмка 18.10, п.14).
            val heroRide = ride.takeIf { role != "driver" }
            item {
                ActiveTripOptionBHero(
                    driverName = if (role == "driver") pickupDetails?.passengerName.orEmpty().ifBlank { appText("Пассажир", "Пассажир") }
                        else heroRide?.driver.orEmpty().ifBlank { tripPass?.driverName.orEmpty() },
                    avatarUrl = heroRide?.driverAvatar.orEmpty(),
                    rating = heroRide?.rating?.takeIf { it > 0.0 }
                        ?.let { String.format(java.util.Locale.US, "%.1f", it).replace('.', ',') },
                    verified = heroRide?.verified == true,
                    trips = heroRide?.driverTrips ?: 0,
                    since = heroRide?.driverSince.orEmpty(),
                    carSummary = tripCarSummary(tripContacts, heroRide?.car),
                    // Водителю — звонок пассажиру, пока бронь подтверждена (решение 23.10.2026; сервер сам закрывает номер).
                    phoneUnlocked = if (role == "driver") driverCanCallPassenger(currentTripPrivateDetails(bookingId), bookingStatus, terminalBooking != null)
                        else !tripContacts?.phone.isNullOrBlank(),
                    onCall = { if (isTripChatActive()) currentTripPrivateDetails(bookingId)?.let { dialDriver(context, if (role == "driver") it.passengerPhone else it.driverPhone) } },
                    boardingCode = currentTripCode(bookingId, boardingCode),
                    boardingCodeLoadFailed = codeLoadFailed,
                    onRetryBoardingCode = { tripDetails.retryCode(::isTripDetailsCurrent, role, bookingStatus) { codeLoadRetry++ } },
                    boardingCodeAvailable = !privateDataMissing,
                    role = role,
                    driverPhase = driverPhase,
                    passengerStatus = status,
                    bookingStatus = bookingStatus,
                    arrivalVerified = arrivalVerified,
                    from = activeFromCity,
                    to = activeToCity,
                    fromPoint = activeFromPoint,
                    toPoint = activeToPoint,
                    liveBookingId = bookingId,
                    modifier = Modifier.appearIn(0),
                )
            }
            // Пассажир до посадки: номер водителя и точка встречи. Без сети они в паспорте ниже.
            pickupDetails?.takeIf { it.role in setOf("passenger", "driver") && it.status in setOf("pending", "confirmed", "onboard") }?.let { d -> item(key = "trip-pickup-stop") { BookingSegmentNote(d.pickupStop, d.dropoffStop, d.rideHasStops, d.fromCity, d.toCity) } }
            item(key = "trip-traveler") {
                val details = authorityRevision.let { currentTripPrivateDetails(bookingId) }
                visibleBookingTraveler(details?.traveler)?.let { name ->
                    BookingTravelerText(name, details?.passengerName.takeIf { role == "driver" })
                }
            }
            val guardianContact = authorityRevision.let {
                if (role == "driver" && isTripChatActive()) {
                    driverGuardianContact(currentTripPrivateDetails(bookingId), bookingStatus)
                } else null
            }
            guardianContact?.let { contact ->
                item(key = "trip-guardian-contact") {
                    DriverGuardianContactCard(contact) {
                        role == "driver" && isTripChatActive() &&
                            driverGuardianContact(currentTripPrivateDetails(bookingId), bookingStatus) === contact
                    }
                }
            }
            tripContacts?.takeIf { !boardingDone(status, bookingStatus) && !(offline && tripPass != null) }?.let { c ->
                item(key = "trip-contacts") { ActiveTripContactsCard(c, Modifier.appearIn(1), showMeeting = !hasOwnPickup(pickupDetails), isCurrent = { isTripChatActive() && currentTripPrivateDetails(bookingId)?.let(::passengerContacts) == c }) }
            }
            item(key = "trip-meeting-points") {
                    val meetingDetails = currentTripPrivateDetails(bookingId)
                PassengerMeetingPoints(meetingDetails, isCurrentParent = ::isTripChatActive) { voiceScope.launch { loadTripDetails() } }
            }
            // F11: офлайн-режим — сервер недоступен, но паспорт поездки сохранён локально.
            if (offline && tripPass != null) {
                item { OfflineTripBanner(modifier = Modifier.appearIn(0)) }
                item {
                    // A known newer server code must not be contradicted by the old offline copy.
                    val stored = tripPass!!
                    val shown = stored
                    TripPassCard(pass = shown, modifier = Modifier.appearIn(1))
                }
            }
            // F12 «Зимний протокол»: спокойное напоминание в морозную ночь (ноя–мар + ночь).
            if (frostyNight) {
                item { FrostyNightBanner(modifier = Modifier.appearIn(1)) }
            }
            // Погода на маршруте — пока поездка не завершена. После неё предупреждать не о чем,
            // а до выезда это ровно тот момент, когда человек решает, ехать ли сегодня.
            // Города — из поездки, деталей брони или офлайн-паспорта (пустая заглушка не в счёт): сервер сам найдёт координаты.
            if (bookingStatus != "done" && bookingStatus != "cancelled") {
                if (activeFromCity.isNotBlank()) {
                    item {
                        WeatherWarningCard(
                            rememberRouteWeather(fromCity = activeFromCity, toCity = activeToCity),
                            modifier = Modifier.appearIn(1),
                        )
                    }
                }
            }
            // Подсказка только пассажиру и только когда салон реально опустел. Водителю её нет.
            if (role == "passenger" && aloneWithDriver) {
                item {
                    AlonePassengerHint(onShare = { showShare = true }, modifier = Modifier.appearIn(1))
                }
            }
            if (bookingId != null && (!offline || tripPass?.payMethod in payMethodKeys)) {
                item {
                    Box(Modifier.appearIn(1)) {
                        PayAgreementBlock(
                            editable = false,
                            method = if (offline) tripPass!!.payMethod!! else payMethod,
                            amountText = "",
                            summaryAmount = if (offline) tripPass!!.effectivePayAmount else payAmount,
                            onMethod = {},
                            onAmount = {}
                        )
                    }
                }
            }
            if (bookingStatus == "done") item {
                var myStars by remember { mutableStateOf(0) }
                var reviewText by remember { mutableStateOf("") }
                var rating by remember { mutableStateOf(false) }   // запрос в полёте — блок повторных тапов, откат при сбое
                var reviewSent by remember { mutableStateOf(false) }
                var pickedTags by remember { mutableStateOf(setOf<String>()) }
                val isDriver = role == "driver"
                val thanksMsg = appText("Спасибо за оценку", "Баһа өсөн рәхмәт")
                val reviewSentMsg = appText("Спасибо! Отзыв на проверке", "Рәхмәт! Фекер тикшереүҙә")
                val rateFailMsg = appText("Не получилось оценить", "Баһалап булманы")
                // Кого оцениваем: пассажир → водителя, водитель → пассажира.
                val rateTitle = if (isDriver) appText("Оцени попутчика", "Юлдашты баһала")
                                else appText("Оцени водителя", "Йөрөтөүсене баһала")
                Card(modifier = Modifier.appearIn(2), colors = CardDefaults.cardColors(containerColor = CanonSurface), shape = CanonItemShape, elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.card)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(rateTitle, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            (1..5).forEach { n ->
                                val starCd = starsText(n)
                                // Тач-цель ≥48dp (иконка визуально 38dp внутри), клик на всей зоне.
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clickable(enabled = !rating) {
                                            val prev = myStars
                                            myStars = n
                                            val id = bookingId
                                            if (id != null) {
                                                rating = true
                                                voiceScope.launch {
                                                    // Звёзды уходят сразу; текст и метки (если уже выбраны) прикрепляем тем же запросом.
                                                    ApiClient.rateBooking(id, n, reviewText, pickedTags.toList())
                                                        .onSuccess {
                                                            rating = false
                                                            if (!reviewSent) Toast.makeText(context, thanksMsg, Toast.LENGTH_SHORT).show()
                                                            // Честный рост: просим оценку в Play только у довольного пассажира (5★).
                                                            // Play сам решит, показывать ли; не чаще раза в 30 дней; без Play — no-op.
                                                            if (n == 5 && !isDriver) maybeRequestStoreReview(context)
                                                        }
                                                        .onFailure { rating = false; myStars = prev; Toast.makeText(context, serverSaid(it, rateFailMsg), Toast.LENGTH_LONG).show() }   // откат: не показываем «оценено», если не сохранилось
                                                }
                                            }
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Default.Star,
                                        contentDescription = starCd,
                                        tint = if (n <= myStars) CanonStar else CanonMuted,
                                        modifier = Modifier.size(48.dp),
                                    )
                                }
                            }
                        }
                        // Быстрые метки — появляются сразу после звёзд. Тапнул пару штук и свободен:
                        // писать отзыв согласны единицы, а метку ставят почти все.
                        //
                        // Показываем те, что подходят выставленной оценке: за 4–5★ хвалебные,
                        // за 1–3★ те, что объясняют низкую. Предлагать «Грубый» человеку, который
                        // только что поставил пятёрку, — навязывать ссору на пустом месте.
                        //
                        // Набор зависит и от того, КОГО оцениваем: «Чисто в машине» пассажиру
                        // не адресуешь, машина не его.
                        if (myStars > 0) {
                            val tagOptions: List<Pair<String, String>> = if (myStars >= 4) {
                                if (isDriver) listOf(
                                    "polite" to appText("Вежливый", "Итәғәтле"),
                                    "ontime" to appText("Вовремя вышел", "Ваҡытында сыҡты"),
                                    "helpful" to appText("Помог в дороге", "Юлда ярҙам итте"),
                                ) else listOf(
                                    "polite" to appText("Вежливый", "Итәғәтле"),
                                    "ontime" to appText("Приехал вовремя", "Ваҡытында килде"),
                                    "clean" to appText("Чисто в машине", "Машинала таҙа"),
                                    "safe" to appText("Везёт аккуратно", "Һаҡ йөрөтә"),
                                    "comfortable" to appText("Ехать удобно", "Барыуы уңайлы"),
                                )
                            } else {
                                if (isDriver) listOf(
                                    "late" to appText("Опоздал", "Һуңланы"),
                                    "rude" to appText("Грубый", "Тупаҫ"),
                                ) else listOf(
                                    "late" to appText("Опоздал", "Һуңланы"),
                                    "rude" to appText("Грубый", "Тупаҫ"),
                                    "unsafe" to appText("Опасная езда", "Хәүефле йөрөтөү"),
                                    "dirty" to appText("Грязно в машине", "Машинала бысраҡ"),
                                    "detour" to appText("Вёз кругами", "Урап йөрөттө"),
                                )
                            }
                            // FlowRow, а не прокрутка вбок: башкирская подпись длиннее русской,
                            // и при крупном системном шрифте метки обязаны переноситься,
                            // а не уезжать за край, где их никто не найдёт.
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                tagOptions.forEach { (code, label) ->
                                    val on = code in pickedTags
                                    val chipBg by animateColorAsState(if (on) CanonMint else CanonSurface, tween(CanonMotion.QUICK), label = "tag-bg")
                                    val chipLine by animateColorAsState(if (on) CanonGreen2 else CanonBorder, tween(CanonMotion.QUICK), label = "tag-line")
                                    val chipInk by animateColorAsState(if (on) CanonGreen2 else CanonMutedStrong, tween(CanonMotion.QUICK), label = "tag-ink")
                                    Surface(
                                        onClick = {
                                            val next = if (on) pickedTags - code else pickedTags + code
                                            pickedTags = next
                                            // Уходит сразу, как и звёзды: отдельной кнопки «сохранить метки»
                                            // нет, иначе человек выберет и уйдёт, не нажав.
                                            val id = bookingId
                                            if (id != null) voiceScope.launch {
                                                ApiClient.rateBooking(id, myStars, reviewText, next.toList())
                                            }
                                        },
                                        enabled = !rating,
                                        color = chipBg,
                                        shape = CanonFieldShape,
                                        border = BorderStroke(1.dp, chipLine),
                                        modifier = Modifier
                                            .heightIn(min = 48.dp)          // тач-цель ≥48dp
                                            // this. обязательно: у самого экрана есть параметр `role`
                                            // (строка «driver»/«passenger»), и без уточнения Kotlin
                                            // подставляет его вместо семантики доступности.
                                            .semantics(mergeDescendants = true) {
                                                this.role = Role.Checkbox
                                                this.selected = on
                                            },
                                    ) {
                                        Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                                            Text(label, color = chipInk, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        }
                                    }
                                }
                            }
                        }
                        // Текстовый отзыв — появляется после выбора звёзд. Идёт на модерацию.
                        if (myStars > 0 && !reviewSent) {
                            OutlinedTextField(
                                value = reviewText,
                                onValueChange = { if (it.length <= 500) reviewText = it },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text(appText("Пара слов о поездке (необязательно)", "Сәфәр тураһында бер-ике һүҙ (мотлаҡ түгел)")) },
                                minLines = 2,
                                maxLines = 4,
                                shape = CanonItemShape,
                            )
                            Text(
                                appText("Отзыв появится после проверки", "Фекер тикшереүҙән һуң күренер"),
                                color = CanonMuted, fontSize = 12.sp,
                            )
                            Button(
                                onClick = {
                                    val id = bookingId
                                    if (id != null && reviewText.isNotBlank()) {
                                        rating = true
                                        voiceScope.launch {
                                            ApiClient.rateBooking(id, myStars, reviewText, pickedTags.toList())
                                                .onSuccess { rating = false; reviewSent = true; Toast.makeText(context, reviewSentMsg, Toast.LENGTH_SHORT).show() }
                                                .onFailure { rating = false; Toast.makeText(context, serverSaid(it, rateFailMsg), Toast.LENGTH_LONG).show() }
                                        }
                                    }
                                },
                                enabled = !rating && reviewText.isNotBlank(),
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2),
                            ) {
                                Text(appText("Оставить отзыв", "Фекер ҡалдырыу"), fontWeight = FontWeight.Bold)
                            }
                        }
                        if (reviewSent) {
                            Text(appText("✓ Отзыв отправлен на проверку", "✓ Фекер тикшереүгә ебәрелде"), color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                }
            }
            // Квитанция завершённой поездки: маршрут, дата, сумма, способ оплаты, водитель.
            if (bookingStatus == "done" && bookingId != null) item {
                AppButton(
                    text = appText("Квитанция поездки", "Сәфәр квитанцияһы"),
                    onClick = { onOpenReceipt(bookingId) },
                    style = AppButtonStyle.Secondary,
                    icon = Icons.Default.ReceiptLong,
                    modifier = Modifier.appearIn(2),
                )
            }
            // Мягкое, ненавязчивое предложение поддержать платформу после завершённой поездки.
            // Легко закрыть (крестик / «Не сейчас») — поддержка строго по желанию.
            if (bookingStatus == "done") item {
                var supportDismissed by rememberSaveable(bookingId) { mutableStateOf(false) }
                AnimatedVisibility(visible = !supportDismissed) {
                    Card(
                        modifier = Modifier.appearIn(3),
                        colors = CardDefaults.cardColors(containerColor = CanonGreen.copy(alpha = 0.10f)),
                        shape = CanonItemShape,
                        elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.flat)
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.VolunteerActivism, contentDescription = null, tint = CanonGreen)
                                Text(appText("Юлдаш делают для своих 🌱", "Юлдашты үҙебеҙ өсөн эшләйбеҙ 🌱"), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                IconButton(onClick = { supportDismissed = true }) {
                                    Icon(Icons.Default.Close, contentDescription = appText("Закрыть", "Ябыу"), tint = CanonMuted)
                                }
                            }
                            Text(
                                appText("Если нравится — поддержи, это по желанию.", "Оҡшаһа — ярҙам ит, был ирекле."),
                                color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(
                                    onClick = onSupport,
                                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = CanonGreen.copy(alpha = 0.18f))
                                ) {
                                    Text(appText("Поддержать", "Ярҙам итеү"), color = CanonGreen, fontWeight = FontWeight.Bold)
                                }
                                TextButton(onClick = { supportDismissed = true }) {
                                    Text(appText("Не сейчас", "Хәҙер түгел"), color = CanonMuted)
                                }
                            }
                        }
                    }
                }
            }
            if (bookingId == null || bookingStatusAllowsBoarding(bookingStatus)) item {
                ShareTripRow(onClick = { showShare = true }, modifier = Modifier.appearIn(3))
            }
            passengerTripCancellation?.content?.let { content -> item(key = "passenger-cancellation") { content() } }
            if (activeTripShowsCancel(role, hasBooking = bookingId != null, bookingStatus = bookingStatus)) item {
                var closeLocalTrip by remember { mutableStateOf(false) }
                OutlinedButton(
                    onClick = {
                        if (!isTripChatActive()) return@OutlinedButton
                        if (bookingId == null) closeLocalTrip = true else passengerTripCancellation?.open()
                    },
                    enabled = passengerTripCancellation?.blocked != true,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, CanonRed)
                ) {
                    Icon(Icons.Default.Close, contentDescription = null, tint = CanonRed)
                    Spacer(Modifier.width(8.dp))
                    Text(appText("Отменить поездку", "Сәфәрҙе кире алыу"), color = CanonRed, fontWeight = FontWeight.Bold)
                }
                if (closeLocalTrip) AlertDialog(
                    onDismissRequest = { closeLocalTrip = false },
                    title = { Text(appText("Отменить поездку?", "Сәфәрҙе кире аларғамы?")) },
                    confirmButton = {
                        TextButton(onClick = { closeLocalTrip = false; if (isTripChatActive()) onTripEnd() }) {
                            Text(appText("Да, отменить", "Эйе, кире алырға"))
                        }
                    },
                    dismissButton = { TextButton(onClick = { closeLocalTrip = false }) { Text(appText("Назад", "Артҡа")) } },
                )
            }
            // Чат по поездке — карточкой: последнее сообщение и новые. Сама переписка — отдельным
            // экраном (TripChatScreen): там строка ввода прибита к низу, как в мессенджерах.
            item(key = "trip-chat") { TripChatCard(chat, onOpen = { showChatView(true) }, modifier = Modifier.appearIn(4)) }
            // F12 «Застрял на трассе» — уровень мягче паники SOS: зовём своих на помощь в дороге.
            // Только в реальной активной поездке (есть бронь и посадка подтверждена).
            if (bookingId != null && bookingStatusAllowsBoarding(bookingStatus)) {
                item {
                    // Блок общий с такси и доставкой (RoadsideHelp.kt): копии разошлись текстами.
                    RoadsideHelpAction(key = bookingId, modifier = Modifier.appearIn(5),
                        ownerGeneration = tripSession,
                        isCurrentParent = { isTripChatActive() && bookingStatusAllowsBoarding(bookingStatus) }) { lat, lng, generation ->
                        ApiClient.roadsideHelp(bookingId, lat, lng, "", expectedGeneration = generation)
                    }
                }
            }
            item {
                Button(
                    onClick = onSos,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CanonRed)
                ) {
                    // Icons.Default.Sos сам рисует буквы «SOS» → с Text("SOS") выходило «SOS SOS». Щит (как на карте).
                    Icon(Icons.Default.Shield, contentDescription = appText("Экстренный вызов", "Ашығыс саҡырыу"))
                    Spacer(Modifier.width(8.dp))
                    Text("SOS", fontWeight = FontWeight.Bold)
                }
            }
        }
    } }
    }

    WinterArrivalDialog(showArrivalCheck, bookingId, tripSession, ::isWinterBookingCurrent) { owner ->
        ApiClient.winterCheckOk(bookingId!!, expectedGeneration = owner)
    }

    if (showShare && isTripChatActive()) {
        BookingShareSheet(bookingId, contacts, tripSession, ::isTripChatActive,
            onDismiss = { showShare = false }, onTrustedContacts = onTrustedContacts)
    }
}

// ── Чистые карточки поездки/статуса (без карты/сокета/чата) ─────────────────────────────
// Вынесены из ActiveTripScreen: берут примитивы/лямбды, рисуют только Text/Icon/Row/Column/
// Surface/Card. Двуязычие считается внутри через appText (по LocalAppLanguage). Анимацию появления
// (`appearIn`) экран навешивает снаружи через modifier — тела остаются без анимаций/эффектов, что
// делает их покрываемыми на JVM (Robolectric). Поведение 1:1 с прежним инлайном.

/** F12: параметры мягкой проверки «доехал?». Момент проверки = расчётная ETA маршрута
 *  (расстояние по прямой / средняя скорость) + запас. Скорость консервативная (со стопами и
 *  трафиком), запас щедрый — чтобы НЕ спросить «доехал?» посреди длинной межгородской поездки
 *  и не потревожить близкого зря. Нет координат маршрута → фолбэк (3 ч). */
private const val ARRIVAL_CHECK_FALLBACK_MS = 3L * 60 * 60_000L   // нет ETA → щедрый фолбэк
private const val ARRIVAL_CHECK_GRACE_MS = 45L * 60_000L          // запас после расчётного прибытия
private const val ARRIVAL_ASSUMED_KMH = 45.0                      // консервативная средняя скорость

/** Через сколько после выезда будить проверку «доехал?»: расчётное время в пути (haversine/скорость)
 *  + запас. Нет полных координат → фолбэк. Никаких сетевых вызовов — считаем локально. */
internal fun arrivalCheckAfterMs(fromLat: Double?, fromLng: Double?, toLat: Double?, toLng: Double?): Long {
    if (fromLat == null || fromLng == null || toLat == null || toLng == null) return ARRIVAL_CHECK_FALLBACK_MS
    val r = 6371.0
    val dLat = Math.toRadians(toLat - fromLat)
    val dLon = Math.toRadians(toLng - fromLng)
    val h = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
        Math.cos(Math.toRadians(fromLat)) * Math.cos(Math.toRadians(toLat)) * Math.sin(dLon / 2) * Math.sin(dLon / 2)
    val km = 2 * r * Math.asin(Math.min(1.0, Math.sqrt(h)))
    if (km <= 0.0) return ARRIVAL_CHECK_FALLBACK_MS
    val travelMs = (km / ARRIVAL_ASSUMED_KMH * 3_600_000.0).toLong()
    return travelMs + ARRIVAL_CHECK_GRACE_MS
}

/** Момент времени → ISO С ЧАСОВЫМ ПОЯСОМ («2026-08-05T10:00:00+05:00») для отправки на сервер.
 *
 *  Единственный правильный способ назвать серверу время: без пояса он вынужден догадываться,
 *  а догадка была неверной — уфимские часы принимались за UTC, и поездка «уезжала» на 5 часов
 *  (разбор №2, 2026-08-03). Такси-предзаказ так делал с самого начала, попутка и заявка — нет;
 *  теперь во всём приложении одно правило. */
internal fun isoWithOffset(ms: Long): String =
    java.time.OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), java.time.ZoneId.systemDefault())
        .withNano(0).toString()

/** ISO выезда (UTC-наивный с сервера) → epoch millis. Терпимо к 'Z'/смещению/долям секунды. */
internal fun parseIsoUtcMillis(iso: String): Long? = try {
    // Если пояс указан явно — верим ему, а не соглашению. Раньше «+05:00» просто отрезался,
    // и время сдвигалось на этот же пояс. Сервер такие строки не шлёт, но клиент их теперь
    // ФОРМИРУЕТ (isoWithOffset), и однажды они вернутся эхом — пусть читается правильно.
    runCatching { java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrElse {
        val s = iso.substringBefore('.').substringBefore('+').removeSuffix("Z").take(19)
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        fmt.parse(s)?.time
    }
} catch (e: Exception) {
    null
}

@Composable
internal fun activeTripActionLabel(status: String): String? = when (status) {
    "departed" -> appText("Я выехал", "Мин сыҡтым")
    "arriving" -> appText("Подъезжаю", "Яҡынлашам")
    "boarded" -> appText("Пассажир сел", "Пассажир ултырҙы")   // посадку отмечает водитель (17.10.2026)
    "arrived" -> appText("Доехал", "Барып еттем")
    "wait" -> null   // пассажир до посадки: своей кнопки нет, «Водитель отметит посадку» (RideshareBoarding.kt)
    else -> appText("Завершить", "Тамамлау")
}

/** Утверждённый вариант B: безопасность посадки раньше второстепенных деталей. */
@Composable
internal fun ActiveTripOptionBHero(
    driverName: String,
    avatarUrl: String,
    rating: String?,
    verified: Boolean,
    trips: Int,
    since: String,
    carSummary: String,
    phoneUnlocked: Boolean,
    onCall: () -> Unit,
    boardingCode: String,
    role: String,
    driverPhase: String,
    passengerStatus: String?,
    bookingStatus: String,
    arrivalVerified: Boolean,
    from: String,
    to: String,
    fromPoint: Point?,
    toPoint: Point?,
    liveBookingId: Int?,
    modifier: Modifier = Modifier,
    boardingCodeLoadFailed: Boolean = false,
    onRetryBoardingCode: () -> Unit = {},
    boardingCodeAvailable: Boolean = true,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ActiveTripDriverCard(
            driverName = driverName.ifBlank { appText("Водитель", "Йөрөтөүсе") },
            avatarUrl = avatarUrl,
            rating = rating,
            verified = verified,
            trips = trips,
            since = since,
            carSummary = carSummary,
            phoneUnlocked = phoneUnlocked,
            onCall = onCall,
        )
        // Села — код больше не нужен: карточка плавно уходит, а не висит над «В пути».
        AnimatedVisibility(
            visible = boardingCodeAvailable && showBoardingCode(passengerStatus, bookingStatus),
            enter = fadeIn(tween(CanonMotion.NORMAL)) + expandVertically(tween(CanonMotion.NORMAL)),
            exit = fadeOut(tween(CanonMotion.QUICK)) + shrinkVertically(tween(CanonMotion.NORMAL)),
        ) {
            ActiveTripBoardingCodeCard(code = boardingCode, role = role,
                loadFailed = boardingCodeLoadFailed, onRetry = onRetryBoardingCode)
        }
        ActiveTripProgressCard(
            role = role,
            driverPhase = driverPhase,
            passengerStatus = passengerStatus,
            bookingStatus = bookingStatus,
        )
        ActiveTripRouteCard(
            from = from,
            to = to,
            fromPoint = fromPoint,
            toPoint = toPoint,
            driverPhase = driverPhase,
            passengerStatus = passengerStatus,
            bookingStatus = bookingStatus,
            arrivalVerified = arrivalVerified,
            liveBookingId = liveBookingId,
        )
    }
}

@Composable
private fun ActiveTripDriverCard(
    driverName: String,
    avatarUrl: String,
    rating: String?,
    verified: Boolean,
    trips: Int,
    since: String,
    carSummary: String,
    phoneUnlocked: Boolean,
    onCall: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = CanonMint),
        shape = CanonCardShape,
        border = BorderStroke(1.dp, CanonBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.card),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SmallAvatar(avatarUrl, driverName, 64)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        driverName,
                        color = CanonText,
                        fontSize = 19.sp,
                        lineHeight = 25.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (rating != null) {
                            Icon(Icons.Default.Star, contentDescription = null, tint = CanonStar, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(rating, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        if (rating != null && verified) Spacer(Modifier.width(10.dp))
                        if (verified) {
                            Icon(Icons.Default.Verified, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(appText("Проверен", "Тикшерелгән"), color = CanonGreen2, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    CompactTrustLine(trips = trips, since = since)
                }
                if (phoneUnlocked) {
                    Spacer(Modifier.width(10.dp))
                    Surface(
                        color = CanonSurface,
                        shape = CircleShape,
                        border = BorderStroke(1.dp, CanonBorder),
                        modifier = Modifier.size(48.dp).bounceClick(onCall),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Phone,
                                contentDescription = appText("Позвонить", "Шылтыратыу"),
                                tint = CanonGreen2,
                                modifier = Modifier.size(21.dp),
                            )
                        }
                    }
                }
            }
            if (carSummary.isNotBlank()) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(CanonHairlineGreen))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = CanonSurface, shape = CircleShape) {
                        Icon(
                            Icons.Default.DirectionsCar,
                            contentDescription = null,
                            tint = CanonGreen2,
                            modifier = Modifier.padding(8.dp).size(18.dp),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        carSummary,
                        color = CanonText,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ActiveTripProgressCard(
    role: String,
    driverPhase: String,
    passengerStatus: String?,
    bookingStatus: String,
) {
    val afterBoarding = boardingDone(passengerStatus, bookingStatus)
    val labels = if (afterBoarding) {
        listOf(
            appText("Посадка", "Ултырыу"),
            appText("В пути", "Юлда"),
            appText("Прибытие", "Барып етеү"),
        )
    } else {
        listOf(
            appText("Подтверждено", "Раҫланды"),
            appText("Выехал", "Сыҡты"),
            appText("Подъезжает", "Яҡынлаша"),
        )
    }
    val activeIndex = if (afterBoarding) {
        when {
            passengerStatus == "arrived" || bookingStatus == "done" -> 2
            else -> 1
        }
    } else {
        when (driverPhase) {
            "arriving" -> 2
            "departed" -> 1
            else -> 0
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = CanonItemShape,
        border = BorderStroke(1.dp, CanonBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.flat),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(
                if (role == "driver") appText("Статус для пассажира", "Пассажир өсөн хәл")
                else appText("Как едет машина", "Машина нисек килә"),
                color = CanonText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
            Box(Modifier.fillMaxWidth().height(28.dp)) {
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = 38.dp).height(2.dp)
                        .align(Alignment.Center).background(CanonBorder)
                )
                Box(
                    Modifier.fillMaxWidth(activeIndex / 2f).padding(start = 38.dp).height(2.dp)
                        .align(Alignment.CenterStart).background(CanonGreen2)
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    labels.indices.forEach { index ->
                        Surface(
                            color = if (index <= activeIndex) CanonGreen2 else CanonSurface,
                            shape = CircleShape,
                            border = BorderStroke(2.dp, if (index <= activeIndex) CanonGreen2 else CanonBorder),
                            modifier = Modifier.size(28.dp),
                        ) {
                            if (index < activeIndex) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = CanonOnFilled,
                                    modifier = Modifier.padding(5.dp),
                                )
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth()) {
                labels.forEachIndexed { index, label ->
                    Text(
                        label,
                        modifier = Modifier.weight(1f),
                        color = if (index <= activeIndex) CanonText else CanonMuted,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        fontWeight = if (index == activeIndex) FontWeight.Bold else FontWeight.Normal,
                        textAlign = when (index) {
                            0 -> TextAlign.Start
                            2 -> TextAlign.End
                            else -> TextAlign.Center
                        },
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

@Composable
private fun ActiveTripRouteCard(
    from: String,
    to: String,
    fromPoint: Point?,
    toPoint: Point?,
    driverPhase: String,
    passengerStatus: String?,
    bookingStatus: String,
    arrivalVerified: Boolean,
    liveBookingId: Int?,
) {
    val statusText = when {
        bookingStatus == "done" -> appText("Поездка завершена", "Сәфәр тамамланды")
        passengerStatus == "arrived" -> appText("Ты на месте", "Һин барып еттең")
        passengerStatus == "sat" || bookingStatus == "onboard" -> appText("Поездка идёт", "Сәфәр бара")
        driverPhase == "arriving" && arrivalVerified -> appText("Машина рядом · GPS подтверждено", "Машина янда · GPS раҫланы")
        driverPhase == "arriving" -> appText("Водитель подъезжает", "Йөрөтөүсе яҡынлаша")
        driverPhase == "departed" -> appText("Водитель выехал к тебе", "Йөрөтөүсе һиңә сыҡты")
        else -> appText("Поездка подтверждена", "Сәфәр раҫланды")
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = CanonCardShape,
        elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.card),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = CanonMint, shape = CircleShape) {
                    Icon(Icons.Default.Route, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(9.dp).size(18.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        "${from.ifBlank { "—" }} → ${to.ifBlank { "—" }}",
                        color = CanonText,
                        fontSize = 19.sp,
                        lineHeight = 25.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(statusText, color = CanonGreen2, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
            if (fromPoint != null && toPoint != null) {
                BookingRouteMapPreview(
                    modifier = Modifier.height(190.dp),
                    from = from,
                    to = to,
                    fromPoint = fromPoint,
                    toPoint = toPoint,
                    contactUnlocked = true,
                    showPrivacyNotice = false,
                    liveBookingId = liveBookingId,
                )
            } else {
                RouteMapUnavailableCard(Modifier.height(148.dp))
            }
        }
    }
}

/** Шапка активной поездки: «откуда → куда», водитель, время. Пустые значения → «—». */
@Composable
internal fun TripRouteHeaderCard(
    from: String?,
    to: String?,
    driver: String?,
    time: String?,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = CanonSurface), shape = CanonCardShape, elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.card)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${from ?: "—"}  →  ${to ?: "—"}", fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.DirectionsCar, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(driver ?: appText("Водитель", "Йөрөтөүсе"), color = CanonMuted)
                time?.let { Spacer(Modifier.width(8.dp)); Text(it, color = CanonMuted) }
            }
        }
    }
}

/** Live-баннер пассажиру: водитель выехал / подъезжает. `arriving` → акцентная (зелёная) плашка.
 *
 * `verified` — сервер сверил «подъезжаю» с живым GPS водителя и убедился, что машина рядом.
 * Строка подтверждения — наш ответ на массовую жалобу к inDrive («жмут „я приехал“, стоя
 * за километры»): пассажир выходит из дома, только когда за словами водителя есть проверка.
 */
@Composable
internal fun DriverApproachingBanner(
    arriving: Boolean,
    modifier: Modifier = Modifier,
    verified: Boolean = false,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = if (arriving) CanonGreen2 else CanonMint,
        shape = CanonCardShape
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.DirectionsCar, contentDescription = null, tint = if (arriving) Color.White else CanonGreen2, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (arriving) appText("Водитель подъезжает", "Йөрөтөүсе яҡынлаша") else appText("Водитель выехал к тебе", "Йөрөтөүсе сыҡты"),
                    color = if (arriving) Color.White else CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp
                )
                // Показываем ТОЛЬКО когда проверка реально прошла. Нет подтверждения — молчим,
                // а не рисуем галочку авансом: ложное «проверено» хуже отсутствующего.
                AnimatedVisibility(visible = arriving && verified) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.CheckCircle, contentDescription = null,
                            tint = Color.White, modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            appText("Подтверждено по GPS — машина рядом", "GPS раҫланы — машина янда"),
                            color = Color.White, fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

/**
 * Плашка кода посадки. У КАЖДОЙ стороны свой текст — раньше он был один на двоих.
 *
 * Водитель открывал экран и читал «Назови водителю — сверят»: инструкцию для пассажира,
 * обращённую к нему самому. Что делать с цифрами, ему никто не говорил, и сверять было
 * нечем — а весь смысл кода как раз в сверке (аудит сценариев 30.08).
 *
 * Блок «сверь машину» — только пассажиру: водитель свою машину знает.
 */
@Composable
internal fun BoardingCodeCard(
    code: String,
    modifier: Modifier = Modifier,
    car: String = "",      // «белая Lada Vesta» — как выглядит машина
    plate: String = "",    // госномер: по нему и сверяют
    isDriver: Boolean = false,
) {
    Surface(modifier = modifier, color = CanonMint, shape = CanonCardShape) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Pin, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(appText("Код посадки", "Ултырыу коды"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    // «он сверит» → «сверят»: за рулём бывают женщины, род тут не нужен.
                    // Башкирский был на «вы» (әйтегеҙ) — приложение везде обращается на «ты».
                    Text(
                        if (isDriver) appText(
                            "Спроси у пассажира код и сверь с этими цифрами.",
                            "Юлаусынан код һора ла ошо һандар менән сағыштыр.",
                        ) else appText(
                            "Назови код водителю и сверь номер машины.",
                            "Кодты йөрөтөүсегә әйт һәм машина номерын сағыштыр.",
                        ),
                        color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(code, color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 34.sp, letterSpacing = 4.sp)
            }
            // Обещание «это та самая машина» до сих пор нечем было проверить: пассажир видел
            // марку, но не номер. Разбор конкурентов 2026-08-07 — у BlaBlaCar приезжала другая
            // машина с другим человеком за рулём. Показываем ровно то, что сверяют глазами.
            if (!isDriver && (car.isNotBlank() || plate.isNotBlank())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.DirectionsCar,
                        contentDescription = appText("Машина водителя", "Йөрөтөүсе машинаһы"),
                        tint = CanonGreen2, modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            appText("Сверь машину перед посадкой", "Ултырыр алдынан машинаны тикшер"),
                            color = CanonMuted, fontSize = 12.sp,
                        )
                        if (car.isNotBlank()) {
                            Text(car, color = CanonText, fontSize = 14.sp)
                        }
                    }
                    if (plate.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Surface(color = CanonSurface, shape = CanonTinyShape) {
                            Text(
                                plate,
                                color = CanonText, fontWeight = FontWeight.Bold,
                                fontSize = 19.sp, letterSpacing = 1.sp,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * F11 — плашка офлайн-режима. Сервер недоступен (трасса без связи), но паспорт поездки
 * сохранён локально: спокойно сообщаем об этом, без тревоги, тёплым тоном.
 */
@Composable
internal fun OfflineTripBanner(modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxWidth(), color = CanonWarnBg, shape = CanonCardShape) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CloudOff, contentDescription = appText("Нет сети", "Селтәр юҡ"), tint = CanonWarn, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(appText("Офлайн — данные сохранены", "Офлайн — мәғлүмәт һаҡланған"), color = CanonWarn, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    appText("Показываем сохранённую поездку. Сообщения и статусы отправим, как появится сеть.",
                        "Һаҡланған сәфәрҙе күрһәтәбеҙ. Хәбәр һәм хәлдәрҙе селтәр булғас ебәрербеҙ."),
                    color = CanonWarn, fontSize = 14.sp, lineHeight = 20.sp
                )
            }
        }
    }
}

/**
 * F11 — карточка «Паспорт поездки»: всё главное для встречи с водителем без сети —
 * маршрут, время, водитель+машина, телефон (кнопка «Позвонить»), код посадки, точка сбора, оплата.
 * Телефон — ПДн, показываем участнику брони; НЕ логируем.
 */
@Composable
internal fun TripPassCard(pass: com.yuldash.app.data.TripPass, modifier: Modifier = Modifier) {
    if (!OfflineTripSnapshots.matchesPass(pass)) return
    val context = LocalContext.current
    val cardScope = rememberCoroutineScope()
    val latest by rememberUpdatedState(pass)
    fun current() = cardScope.isActive && latest === pass && OfflineTripSnapshots.matchesPass(pass)
    Card(
        modifier = modifier.fillMaxWidth().tripAuthorityDraw(::current),
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = CanonItemShape,
        elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.card)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Lock, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(appText("Паспорт поездки", "Сәфәр паспорты"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            TripPassRow(Icons.Default.Route, appText("Маршрут", "Юл"), "${pass.fromCity} → ${pass.toCity}")
            if (pass.departAt.isNotBlank()) TripPassRow(Icons.Default.Schedule, appText("Время", "Ваҡыт"), formatDepart(pass.departAt))
            val driverLine = listOf(pass.driverName, pass.driverCar).filter { it.isNotBlank() }.joinToString(" · ")
            if (driverLine.isNotBlank()) TripPassRow(Icons.Default.Person, appText("Водитель", "Йөрөтөүсе"), driverLine)
            // Номер отдельной строкой, а не в хвосте описания машины: его сверяют глазами
            // в темноте у обочины, и он должен читаться сразу.
            if (pass.driverPlate.isNotBlank()) {
                TripPassRow(Icons.Default.DirectionsCar, appText("Госномер", "Дәүләт номеры"), pass.driverPlate)
            }
            if (pass.driverPhone.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TripPassRow(Icons.Default.Phone, appText("Телефон", "Телефон"), pass.driverPhone, modifier = Modifier.weight(1f))
                    FilledTonalButton(
                        onClick = {
                            if (current()) runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${pass.driverPhone}"))) }
                        },
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = CanonMint, contentColor = CanonGreen2)
                    ) {
                        Icon(Icons.Default.Phone, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(appText("Позвонить", "Шылтыратыу"), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
            }
            BookingSegmentNote(pass.pickupStop, pass.dropoffStop, pass.rideHasStops, pass.fromCity, pass.toCity)
            val meeting = pass.contacts()
            val meetingLabel = (if (LocalAppLanguage.current == AppLanguage.Ba) meeting.pickupBa else meeting.pickup)
                .ifBlank { if (meeting.exactMeeting) appText("Точка на карте", "Карталағы нөктә") else "" }
            if (meetingLabel.isNotBlank()) TripPassRow(Icons.Default.LocationOn, appText("Точка сбора", "Йыйылыу урыны"), meetingLabel)
            val meetingLat = meeting.pickupLat
            val meetingLng = meeting.pickupLng
            if (meetingLat != null && meetingLng != null) {
                val label = appText("Место встречи", "Осрашыу урыны")
                AppButton(text = appText("Открыть точку на карте", "Нөктәне картала асыу"),
                    style = AppButtonStyle.Secondary, icon = Icons.Default.Map,
                    onClick = {
                        if (!current()) return@AppButton
                        val uri = Uri.parse("geo:$meetingLat,$meetingLng?q=$meetingLat,$meetingLng(" + Uri.encode(label) + ")")
                        runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, uri), null)) }
                    })
            }
            if (pass.boardingCode.isNotBlank()) TripPassRow(Icons.Default.Pin, appText("Код посадки", "Ултырыу коды"), pass.boardingCode)
            if (pass.payAmount != null || pass.price > 0 || pass.payMethod != null) {
                val method = pass.payMethod?.takeIf { it in payMethodKeys }?.let { payMethodLabel(it) }
                    ?: appText("Способ оплаты не сохранён", "Түләү ысулы һаҡланмаған")
                TripPassRow(Icons.Default.Payments, appText("Оплата", "Түләү"),
                    "${pass.effectivePayAmount} ₽ · $method")
            }
        }
    }
}

@Composable
private fun TripPassRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, color = CanonMuted, fontSize = 12.sp)
            Text(value, color = CanonText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 23.sp)
        }
    }
}

/**
 * Кнопки статуса поездки. Водитель: «Я выехал / Подъезжаю / Завершить»; пассажир: «Доехал /
 * Завершить» — посадку отмечает водитель (17.10.2026). Клик отдаёт код статуса в [onStatus].
 * У пассажира выбранный статус подсвечен ([selectedStatus]); у водителя подсветки нет.
 */
@Composable
internal fun TripStatusButtons(
    role: String,
    selectedStatus: String?,
    onStatus: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val statusButtons = if (role == "driver")
            // + «Завершить»: водитель тоже закрывает поездку. Раньше закрыть бронь мог ТОЛЬКО
            // пассажир → если он забывал, бронь висела активной, а места поездки не освобождались.
            listOf("departed" to appText("Я выехал", "Сыҡтым"), "arriving" to appText("Подъезжаю", "Яҡынлашам"), "done" to appText("Завершить", "Тамам"))
        else
            listOf("arrived" to appText("Доехал", "Барып еттем"), "done" to appText("Завершить", "Тамам"))
        statusButtons.forEach { (st, label) ->
            FilledTonalButton(
                onClick = { onStatus(st) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = if (role != "driver" && selectedStatus == st) CanonMint else CanonSurface,
                    contentColor = CanonText
                )
            ) { Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/**
 * «Едет пассажир младше 18» — отметка и взрослый, который за него отвечает.
 *
 * Возраст у нас не спрашивался нигде: подросток регистрировался и садился к незнакомому
 * человеку, водитель об этом не знал, а отвечать в случае чего пришлось бы ему. Запрещать
 * нельзя — сайт прямо обещает «школьник доберётся», и в районе это реальная нужда: до школы,
 * в райцентр, к врачу. Поэтому не запрет, а взрослый на связи + честная видимость.
 *
 * Имя и телефон взрослого обязательны (сервер без них бронь не создаст): это и есть запись
 * согласия, и водителю есть кому позвонить, если что-то пойдёт не так.
 */
@Composable
internal fun MinorPassengerBlock(
    checked: Boolean,
    guardianName: String,
    guardianPhone: String,
    onChecked: (Boolean) -> Unit,
    onName: (String) -> Unit,
    onPhone: (String) -> Unit,
    modifier: Modifier = Modifier,
    showContacts: Boolean = true,
) {
    Surface(modifier = modifier.fillMaxWidth(), color = CanonSurface, shape = CanonCardShape, border = BorderStroke(1.dp, CanonBorder)) {
        Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SettingSwitchRow(
                Icons.Default.EscalatorWarning,
                appText("Едет пассажир младше 18", "18-ҙән кесе юлсы бара"),
                appText("Водитель увидит это до подтверждения", "Йөрөтөүсе быны раҫлауға тиклем күрер"),
                checked,
            ) { onChecked(it) }
            AnimatedVisibility(visible = checked && showContacts) {
                Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        appText("Кто из взрослых отвечает за поездку. Водителю будет кому позвонить.",
                            "Сәфәр өсөн ҡайһы оло кеше яуаплы. Йөрөтөүсегә шылтыратырға кем булыр."),
                        color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp,
                    )
                    OutlinedTextField(
                        guardianName, onName,
                        label = { Text(appText("Имя взрослого", "Оло кешенең исеме")) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                    OutlinedTextField(
                        guardianPhone, onPhone,
                        label = { Text(appText("Телефон взрослого", "Оло кешенең телефоны")) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    )
                }
            }
            if (checked && !showContacts) {
                Text(
                    appText("Имя и телефон взрослого понадобятся после входа.",
                        "Оло кешенең исеме һәм телефоны ингәндән һуң кәрәк буласаҡ."),
                    color = CanonMuted, style = CanonCaption, modifier = Modifier.padding(horizontal = CanonSpace.md),
                )
            }
        }
    }
}

@Composable
internal fun GuestBookingReviewDialog(review: GuestBookingReview, onReview: () -> Unit, onBack: () -> Unit) {
    val changes = listOfNotNull(
        if (review.routeChanged) appText("Маршрут изменился. Проверь остановки и число мест.",
            "Маршрут үҙгәрҙе. Туҡталыштарҙы һәм урындар һанын тикшер.") else null,
        if (review.frontUnavailable) appText("Переднее место недоступно. Продолжай, если подходит место сзади.",
            "Алғы урын буш түгел. Арттағы урын тура килһә, дауам ит.") else null,
        if (review.minorsUnavailable) appText("Водитель берёт только 18+. Продолжай, если поедет взрослый.",
            "Йөрөтөүсе тик 18+ ала. Оло кеше барһа, дауам ит.") else null,
        if (review.termsChanged) appText("Проверь актуальные условия поездки.", "Сәфәрҙең хәҙерге шарттарын тикшер.") else null,
    )
    AlertDialog(
        onDismissRequest = onBack,
        title = { Text(appText("Условия поездки изменились", "Сәфәр шарттары үҙгәрҙе")) },
        text = { Text(changes.joinToString("\n")) },
        confirmButton = {
            TextButton(onClick = onReview) { Text(appText("Проверить и продолжить", "Тикшереп дауам итергә")) }
        },
        dismissButton = {
            TextButton(onClick = onBack) { Text(appText("Найти другую поездку", "Башҡа сәфәр табыу")) }
        },
    )
}

/**
 * Тихая подсказка, когда салон опустел: попутчики вышли, человек остался один на один
 * с водителем.
 *
 * Откуда взялось: история на 849 голосов (r/india) — приставания начались ровно после высадки
 * второй пассажирки. Женщина доплатила сверху и промолчала, лишь бы доехать без конфликта.
 * SOS в такой момент не жмут: он ощущается как «поднять шум из-за слов».
 *
 * Поэтому здесь НЕТ тревоги: спокойный факт и одно действие. Ни пуша, ни звука, ни красного
 * цвета — иначе подсказка сама становится источником страха. Водителю она не видна и
 * обвинением не является: поделиться поездкой — обычная вещь, а не сигнал недоверия.
 */
@Composable
internal fun AlonePassengerHint(
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxWidth(), color = CanonMint, shape = CanonCardShape) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Person, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    appText("Попутчики вышли — пассажир остался с водителем",
                        "Юлдаштар төштө — юлсы йөрөтөүсе менән ҡалды"),
                    color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                )
                Text(
                    appText("Можно отправить близкому ссылку на поездку.",
                        "Яҡыныңа сәфәр һылтанмаһын ебәрергә була."),
                    color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp,
                )
            }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = onShare, shape = RoundedCornerShape(14.dp)) {
                Text(appText("Отправить", "Ебәрергә"), fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Строка «Поделиться поездкой с близким» — открывает шит выбора контакта. */
@Composable
internal fun ShareTripRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(colors = CardDefaults.cardColors(containerColor = CanonSurface), shape = CanonItemShape, elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.card)) {
        Row(
            modifier.fillMaxWidth().clickable { onClick() }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(color = CanonMint, shape = CircleShape) {
                Icon(Icons.Default.Person, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(8.dp).size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(appText("Поделиться поездкой с близким", "Сәфәрҙе яҡының менән уртаҡлашыу"), fontWeight = FontWeight.Bold)
                Text(appText("Близкий будет видеть статус поездки", "Яҡының сәфәр хәлен күреп торор"), color = CanonMuted, fontSize = 14.sp)
            }
            Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = CanonMuted)
        }
    }
}

/** Список активных live-ссылок поездки с кнопкой «Отозвать» (приватность B7c). */
@Composable
internal fun ActiveSharesList(
    shares: List<Pair<com.yuldash.app.data.TripShareDto, String>>,
    enabled: Boolean = true,
    onRevoke: (com.yuldash.app.data.TripShareDto) -> Unit,
) {
    if (shares.isEmpty()) return
    Spacer(Modifier.height(12.dp))
    Text(
        appText("Активные ссылки", "Әүҙем һылтанмалар"),
        color = CanonMuted, fontSize = 14.sp, fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(4.dp))
    shares.forEach { (share, name) ->
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Person, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(name, color = CanonText, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(
                onClick = { onRevoke(share) },
                enabled = enabled,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(Icons.Default.Close, contentDescription = appText("Отозвать ссылку", "Һылтанманы кире алыу"), tint = CanonRed, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(appText("Отозвать", "Кире алыу"), color = CanonRed, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
    }
}

/**
 * Сообщение, в котором фото или голосовое лежит на ЧУЖОМ сервере. Показываем сам адрес
 * строкой и честно говорим, почему не открыли: содержимое не теряется, но телефон никуда
 * не ходит — значит, чужой сервер не узнает ни IP, ни город, ни время просмотра.
 */
@Composable
private fun ForeignMediaBubble(link: String, mine: Boolean) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(
            link,
            color = if (mine) CanonOnFilled else CanonText,
            fontSize = 16.sp,
            maxLines = 3,                 // «адресом» может прийти простыня в 4000 знаков
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "⚠️ " + appText(
                "Ссылка ведёт на чужой сайт — не открываем",
                "Һылтанма ят сайтҡа илтә — асмайбыҙ",
            ),
            color = if (mine) CanonOnFilled else CanonWarn,
            fontSize = 12.sp, lineHeight = 17.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MessageBubble(
    text: String,
    voiceUrl: String?,
    mine: Boolean,
    failed: Boolean = false,
    queued: Boolean = false,
    deleted: Boolean = false,
    edited: Boolean = false,
    flag: String = "",           // метка сервера: warn (фишинг) / contact (телефон) / abuse (грубость)
    fromAdmin: Boolean = false,  // B8-9: бейдж «Юлдаш ✓» (только серверный флаг)
    canEdit: Boolean = false,
    canDeleteAll: Boolean = false,
    canDeleteMine: Boolean = false,
    onRetry: () -> Unit = {},
    onEdit: () -> Unit = {},
    onDelete: (String) -> Unit = {},
) {
    var playing by remember { mutableStateOf(false) }
    val player = remember { mutableStateOf<MediaPlayer?>(null) }
    // Медиа в сообщении — это ссылка, которую собеседник набирает сам (текст с префиксом
    // «[img]» и адрес голосового). Открывать её можно, только если она ведёт на наш сервер:
    // иначе телефон сам сходит на чужой сайт, и его хозяин запишет IP, город и время того,
    // кто просто открыл чат. Чужую ссылку показываем строкой — сообщение не пропадает,
    // а перехода не происходит (так же сделано на сайте).
    val ownVoice = voiceUrl?.takeIf { isOwnMediaHost(it) }
    val isImage = text.startsWith(ApiClient.IMG_PREFIX)
    val ownPhoto = if (isImage) ownImageModel(text.removePrefix(ApiClient.IMG_PREFIX)) else null
    val foreignMedia = (voiceUrl != null && ownVoice == null) || (isImage && ownPhoto == null)
    DisposableEffect(ownVoice) { onDispose { runCatching { player.value?.release() }; player.value = null } }
    var menu by remember { mutableStateOf(false) }
    val showMenu = !deleted && (canEdit || canDeleteAll || canDeleteMine)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
    if (fromAdmin && !deleted) YuldashOfficialBadge(Modifier.padding(bottom = 4.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Box {
        Surface(
            color = if (deleted) CanonSurface else if (mine) CanonGreen2 else CanonSurface,
            shape = RoundedCornerShape(14.dp),
            shadowElevation = CanonDepth.card,
            border = if (deleted) BorderStroke(1.dp, CanonBorder) else null,
            modifier = if (showMenu) Modifier.combinedClickable(onClick = {}, onLongClick = { menu = true }) else Modifier
        ) {
            if (deleted) {
                Text(
                    appText("Сообщение удалено", "Хәбәр юйылды"),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    color = CanonMuted, fontSize = 14.sp, fontStyle = FontStyle.Italic
                )
            } else if (ownVoice != null) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = {
                            if (playing) {
                                runCatching { player.value?.stop(); player.value?.release() }; player.value = null; playing = false
                            } else runCatching {
                                player.value = MediaPlayer().apply {
                                    setDataSource(ownVoice)
                                    setOnPreparedListener { it.start() }
                                    setOnCompletionListener { playing = false; runCatching { release() }; player.value = null }
                                    prepareAsync()
                                }
                                playing = true
                            }
                        },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(if (playing) Icons.Default.Close else Icons.Default.PlayArrow,
                            contentDescription = if (playing) appText("Остановить", "Туҡтатыу") else appText("Воспроизвести", "Уйнатыу"),
                            tint = if (mine) CanonOnFilled else CanonGreen2)
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(appText("Голосовое", "Тауыш"), modifier = Modifier.padding(end = 8.dp), color = if (mine) CanonOnFilled else CanonText, fontSize = 14.sp)
                }
            } else if (ownPhoto != null) {
                AsyncImage(
                    model = ownPhoto,
                    contentDescription = appText("Фото", "Фото"),
                    modifier = Modifier
                        .padding(4.dp)
                        .sizeIn(maxWidth = 240.dp, maxHeight = 320.dp)
                        .clip(RoundedCornerShape(14.dp)),
                    contentScale = ContentScale.Fit
                )
            } else if (foreignMedia) {
                ForeignMediaBubble(link = voiceUrl ?: text.removePrefix(ApiClient.IMG_PREFIX), mine = mine)
            } else {
                Text(
                    text,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    color = if (mine) CanonOnFilled else CanonText,
                    fontSize = 16.sp
                )
            }
        }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (canEdit) DropdownMenuItem(
                    text = { Text(appText("Редактировать", "Үҙгәртеү")) },
                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, tint = CanonGreen2) },
                    onClick = { menu = false; onEdit() }
                )
                if (canDeleteMine) DropdownMenuItem(
                    text = { Text(appText("Удалить у себя", "Үҙемдә юйыу")) },
                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = CanonMuted) },
                    onClick = { menu = false; onDelete("me") }
                )
                if (canDeleteAll) DropdownMenuItem(
                    text = { Text(appText("Удалить у всех", "Барыһында юйыу"), color = CanonRed) },
                    leadingIcon = { Icon(Icons.Default.DeleteForever, contentDescription = null, tint = CanonRed) },
                    onClick = { menu = false; onDelete("all") }
                )
            }
        }
    }
        if (flag.isNotEmpty() && !deleted) {
            // Кому показывать — решает сама плашка: фишинг и грубость видит получатель,
            // предупреждение про телефон — отправитель (рискует именно он).
            ChatFlagPlate(flag, mine, Modifier.padding(top = 4.dp))
        }
        if (edited && !deleted) {
            Text(
                appText("изменено", "үҙгәртелде"),
                color = CanonMuted, fontSize = 14.sp,
                modifier = Modifier.padding(top = 4.dp, end = 4.dp)
            )
        }
        if (failed) {
            Text(
                appText("Не доставлено · Повторить", "Ебәрелмәне · Ҡабатлау"),
                color = CanonRed,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp, end = 4.dp).bounceClick { onRetry() }
            )
        } else if (queued) {
            // F11: сообщение в очереди — уйдёт само, когда вернётся сеть.
            Row(
                modifier = Modifier.padding(top = 4.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(Icons.Default.Schedule, contentDescription = null, tint = CanonMuted, modifier = Modifier.size(13.dp))
                Text(
                    appText("В очереди · отправим при сети", "Сиратта · селтәр булғас ебәрербеҙ"),
                    color = CanonMuted, fontSize = 12.sp
                )
            }
        }
    }
}
