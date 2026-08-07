package com.yuldash.app

// Экран «Создать поездку» (водитель публикует рейс). Вынесено из MainActivity (Фаза 2).
// Импорты скопированы целиком — лишние = варнинги.

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
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.LocalGasStation
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
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.ui.platform.testTag
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
import com.yuldash.app.data.PickupPointDto
import com.yuldash.app.data.MessageDto
import com.yuldash.app.data.GeocoderClient
import com.yuldash.app.data.GeoHit
import com.yuldash.app.data.ConversationDto
import com.yuldash.app.data.PopularRouteDto
import com.yuldash.app.data.SettlementRouteDto
import com.yuldash.app.data.PriceHintDto
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import com.yuldash.app.data.FeedDto
import com.yuldash.app.data.RequestDto
import com.yuldash.app.data.NotifDto
import com.yuldash.app.data.AdDto
import com.yuldash.app.ui.theme.YuldashTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// Диапазон цены поездки (₽): не бесплатно, но и не абсурд. Чистая константа → используется и в
// хелпере валидации, и в UI-подсказке. Меняется в одном месте.
internal const val RIDE_PRICE_MIN = 1
internal const val RIDE_PRICE_MAX = 100_000

/**
 * Чистая валидация формы поездки: маршрут задан (откуда/куда не пусто) и цена — целое число
 * в диапазоне [RIDE_PRICE_MIN..RIDE_PRICE_MAX]. Без Compose/сети → тестируется прямым вызовом на JVM.
 * «Плохие» кейсы: пустой from/to → false; цена 0/отрицательная/пустая/нечисло/вне диапазона → false.
 */
internal fun createRideValid(from: String, to: String, price: String): Boolean {
    if (from.isBlank() || to.isBlank()) return false
    val p = price.trim().toIntOrNull() ?: return false
    return p in RIDE_PRICE_MIN..RIDE_PRICE_MAX
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CreateRideScreen(onBack: () -> Unit, onPublish: (Ride) -> Unit, prefillDate: String? = null) {
    var from by remember { mutableStateOf("") }
    var to by remember { mutableStateOf("") }
    // F15: если открыли из баннера «на праздник» — дата события уже стоит (можно поменять пикером).
    var dateTime by remember { mutableStateOf(prefillDate.orEmpty()) }
    var seats by remember { mutableStateOf("2") }
    var price by remember { mutableStateOf("300") }
    var comment by remember { mutableStateOf("") }
    var petsAllowed by remember { mutableStateOf(false) }
    var childSeat by remember { mutableStateOf(false) }
    var womenOnly by remember { mutableStateOf(false) }
    var smoking by remember { mutableStateOf(false) }
    var baggage by remember { mutableStateOf(false) }
    var airConditioner by remember { mutableStateOf(false) }
    var onlyTrusted by remember { mutableStateOf(false) }   // «только для своих» (L3)
    var quiet by remember { mutableStateOf(false) }
    var waypoints by remember { mutableStateOf(listOf<String>()) }
    var recurrence by remember { mutableStateOf("none") }
    var category by remember { mutableStateOf("regular") }
    var partnerId by remember { mutableStateOf<Int?>(null) }   // F22: клиника-назначение (для category=hospital)
    var partners by remember { mutableStateOf<List<com.yuldash.app.data.MedicalPartnerDto>>(emptyList()) }
    LaunchedEffect(Unit) { ApiClient.getMedicalPartners().onSuccess { partners = it } }   // справочник клиник (тихо; форма работает и без него)
    var receiverName by remember { mutableStateOf("") }   // посылка: кому отдать
    var parcelSize by remember { mutableStateOf("") }     // посылка: габарит/вес
    var pickup by remember { mutableStateOf("") }
    var pickupLat by remember { mutableStateOf<Double?>(null) }
    var pickupLng by remember { mutableStateOf<Double?>(null) }
    var pickupPointId by remember { mutableStateOf<Int?>(null) }   // F14: id выбранной точки справочника (null = ручной ввод/карта)
    val isBa = LocalAppLanguage.current == AppLanguage.Ba
    var showPicker by remember { mutableStateOf(false) }
    // Подсказка цены + честный расчёт бензина по маршруту (аддитивные поля сервера).
    var priceHintDto by remember { mutableStateOf<PriceHintDto?>(null) }
    val priceHint = priceHintDto?.takeIf { it.count > 0 }?.avg ?: 0
    var publishing by remember { mutableStateOf(false) }   // ждём ответ сервера, блок двойного нажатия
    var publishError by remember { mutableStateOf<String?>(null) }
    val publishScope = rememberCoroutineScope()
    // Популярные направления из справочника географии — чипы над формой (тап заполняет оба поля).
    // Ошибка сети → чипов просто нет, форма работает как раньше.
    var geoRoutes by remember { mutableStateOf<List<SettlementRouteDto>>(emptyList()) }
    LaunchedEffect(Unit) { ApiClient.getSettlementPopularRoutes().onSuccess { geoRoutes = it } }
    LaunchedEffect(from, to) {
        priceHintDto = if (from.isNotBlank() && to.isNotBlank()) {
            delay(450)
            ApiClient.getPriceHint(from.trim(), to.trim()).getOrNull()
        } else null
    }
    val defaultTime = appText("Сегодня, 18:00", "Бөгөн, 18:00")
    val defaultCar = appText("Моя машина", "Минең машина")
    val dropPinLabel = appText("Точка на карте", "Картала нөктә")
    val errPublish = appText("Не удалось опубликовать. Проверь сеть и повтори.", "Баҫтырып булманы. Селтәрҙе тикшереп ҡабатла.")
    val ctxDt = LocalContext.current
    Box(Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { ScreenTopBar(appText("Создать поездку", "Сәфәр булдырыу"), onBack) }
    ) { padding ->
        CreateRideFormContent(
            from = from, to = to, dateText = dateTime, seats = seats, price = price, comment = comment,
            typeKey = category, recurrence = recurrence,
            receiverName = receiverName, parcelSize = parcelSize,
            pickup = pickup, pinned = pickupLat != null,
            womenOnly = womenOnly, childSeat = childSeat, petsAllowed = petsAllowed,
            baggage = baggage, airConditioner = airConditioner, smoking = smoking,
            onlyTrusted = onlyTrusted, quiet = quiet, waypoints = waypoints,
            priceHint = priceHint,
            fuelDistanceKm = priceHintDto?.distanceKm, fuelEstimateKop = priceHintDto?.fuelEstimateKop,
            loading = publishing, error = publishError,
            onFromChange = { from = it }, onToChange = { to = it },
            onSeatsChange = { seats = it.filter(Char::isDigit) },
            onPriceChange = { price = it.filter(Char::isDigit) },
            onCommentChange = { comment = it },
            onSelectType = { category = it }, onSelectRecurrence = { recurrence = it },
            partners = partners, selectedPartnerId = partnerId, onSelectPartner = { partnerId = it },
            onReceiverNameChange = { receiverName = it }, onParcelSizeChange = { parcelSize = it },
            onPickupChange = { pickup = it; pickupPointId = null }, onOpenPicker = { showPicker = true },
            onOpenDatePicker = { openDateTimePicker(ctxDt, "ru") { dateTime = it } },
            onUsePriceHint = { price = priceHint.toString() },
            onWomenOnly = { womenOnly = it }, onChildSeat = { childSeat = it },
            onPetsAllowed = { petsAllowed = it }, onBaggage = { baggage = it },
            onAirConditioner = { airConditioner = it }, onSmoking = { smoking = it },
            onOnlyTrusted = { onlyTrusted = it }, onQuiet = { quiet = it }, onWaypointsChange = { waypoints = it },
            onPublish = {
                if (publishing) return@CreateRideFormContent
                val fromVal = from.ifBlank { "Баймаҡ" }
                val toVal = to.ifBlank { "Сибай" }
                val priceVal = price.toIntOrNull() ?: 300
                val seatsVal = (seats.toIntOrNull() ?: 2).coerceAtLeast(1)   // мест не меньше 1
                // Берём выбранную дату из пикера ("dd.MM.yyyy, HH:mm"); если пусто/не распарсилось — now+3ч.
                // Время выезда шлём С ЧАСОВЫМ ПОЯСОМ. Раньше уходила голая строка «10:00», и сервер
                // считал её десятью часами UTC — поездка уезжала на 5 часов (разбор №2). Пояс
                // снимает догадку: сервер переводит точно, где бы ни был телефон.
                val departIso = runCatching {
                    val picked = java.text.SimpleDateFormat("dd.MM.yyyy, HH:mm", java.util.Locale.US).parse(dateTime)
                    isoWithOffset(picked!!.time)
                }.getOrElse { isoWithOffset(System.currentTimeMillis() + 3 * 3600_000L) }
                val ride = Ride(
                    id = "local-${System.currentTimeMillis()}",
                    from = fromVal, to = toVal,
                    time = dateTime.ifBlank { defaultTime }, timeBa = dateTime.ifBlank { defaultTime },
                    driver = ApiClient.cachedName() ?: "Я",
                    car = comment.ifBlank { defaultCar }, carBa = comment.ifBlank { defaultCar },
                    price = priceVal, seats = seatsVal, rating = 5.0, verified = false, boosted = false,
                    petsAllowed = petsAllowed, childSeat = childSeat, womenOnly = womenOnly,
                    smoking = smoking, baggage = baggage, airConditioner = airConditioner, quiet = quiet,
                    waypoints = waypoints.filter { it.isNotBlank() },
                )
                publishError = null
                publishing = true
                // Ждём ответ сервера: успех → навигация, ошибка → сообщение (не уходим, не теряем ввод).
                publishScope.launch {
                    ApiClient.publishRide(fromVal, toVal, departIso, seatsVal, priceVal, comment.trim(), petsAllowed, childSeat, womenOnly, smoking, baggage, airConditioner, recurrence, category, pickup.trim(), pickupLat, pickupLng, onlyTrusted, receiverName.trim(), parcelSize.trim(), pickupPointId, if (category == "hospital") partnerId else null, quiet, waypoints.filter { it.isNotBlank() }.joinToString(" | "))
                        .onSuccess { publishing = false; onPublish(ride) }
                        // Сервер объясняет отказ по-человечески («время выезда уже прошло»,
                        // «слишком много активных поездок»). Показываем именно его слова:
                        // раньше на любой отказ писали «проверь сеть» — водитель проверял сеть,
                        // жал ещё раз и получал то же самое, так и не узнав причину.
                        // Без ответа сервера (нет связи) остаётся прежний текст про сеть.
                        .onFailure { e -> publishing = false; publishError = serverSaid(e, errPublish) }
                }
            },
            onCancel = onBack,
            // «Умные» поля с собственными эффектами (гео-подсказки) — слотами, чтобы Content остался чистым.
            fromField = { AddressSuggestField(from, { from = it }, appText("Откуда", "Ҡайҙан"), Icons.Default.LocationOn) },
            toField = { AddressSuggestField(to, { to = it }, appText("Куда", "Ҡайҙа"), Icons.Default.NearMe) },
            routeChips = { PopularRouteChips(geoRoutes) { f, t -> from = f; to = t } },
            pickupChips = {
                PickupSuggestionChips(city = from, selectedId = pickupPointId) { p ->
                    pickup = if (isBa && p.titleBa.isNotBlank()) p.titleBa else p.titleRu
                    pickupLat = p.lat; pickupLng = p.lng; pickupPointId = p.id
                }
            },
            modifier = Modifier.padding(padding),
        )
    }
        if (showPicker) {
            PickupPickerOverlay(
                initial = pickupLat?.let { la -> pickupLng?.let { ln -> Point(la, ln) } },
                onConfirm = { la, ln -> pickupLat = la; pickupLng = ln; pickupPointId = null; if (pickup.isBlank()) pickup = dropPinLabel; showPicker = false },
                onDismiss = { showPicker = false }
            )
        }
    }
}

/**
 * Чистый рендер формы «Создать поездку»: весь стейт приходит параметрами, все действия — колбэками.
 * Импур-куски (гео-подсказка адреса) приняты слотами [fromField]/[toField]; MapKit/пикер даты/оверлей
 * точки живут в умной обёртке [CreateRideScreen]. Кнопка «Опубликовать» блокируется при [loading]
 * (гард двойного нажатия) и когда маршрут/цена невалидны ([createRideValid]). Тестируется на JVM (Robolectric).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CreateRideFormContent(
    from: String,
    to: String,
    dateText: String,
    seats: String,
    price: String,
    comment: String,
    typeKey: String,
    recurrence: String,
    receiverName: String,
    parcelSize: String,
    pickup: String,
    pinned: Boolean,
    womenOnly: Boolean,
    childSeat: Boolean,
    petsAllowed: Boolean,
    baggage: Boolean,
    airConditioner: Boolean,
    quiet: Boolean,
    waypoints: List<String>,
    smoking: Boolean,
    onlyTrusted: Boolean,
    priceHint: Int,
    fuelDistanceKm: Float? = null,
    fuelEstimateKop: Int? = null,
    loading: Boolean,
    error: String?,
    onFromChange: (String) -> Unit,
    onToChange: (String) -> Unit,
    onSeatsChange: (String) -> Unit,
    onPriceChange: (String) -> Unit,
    onCommentChange: (String) -> Unit,
    onSelectType: (String) -> Unit,
    onSelectRecurrence: (String) -> Unit,
    onReceiverNameChange: (String) -> Unit,
    onParcelSizeChange: (String) -> Unit,
    onPickupChange: (String) -> Unit,
    onOpenPicker: () -> Unit,
    onOpenDatePicker: () -> Unit,
    onUsePriceHint: () -> Unit,
    onWomenOnly: (Boolean) -> Unit,
    onChildSeat: (Boolean) -> Unit,
    onPetsAllowed: (Boolean) -> Unit,
    onBaggage: (Boolean) -> Unit,
    onAirConditioner: (Boolean) -> Unit,
    onQuiet: (Boolean) -> Unit,
    onWaypointsChange: (List<String>) -> Unit,
    onSmoking: (Boolean) -> Unit,
    onOnlyTrusted: (Boolean) -> Unit,
    onPublish: () -> Unit,
    onCancel: () -> Unit,
    partners: List<com.yuldash.app.data.MedicalPartnerDto> = emptyList(),  // F22: клиники-партнёры
    selectedPartnerId: Int? = null,
    onSelectPartner: (Int?) -> Unit = {},
    fromField: (@Composable () -> Unit)? = null,
    toField: (@Composable () -> Unit)? = null,
    routeChips: (@Composable () -> Unit)? = null,
    pickupChips: (@Composable () -> Unit)? = null,   // F14: подсказки точек сбора (умный слот)
    modifier: Modifier = Modifier,
) {
    val isCargo = typeKey == "parcel" || typeKey == "cargo"
    val isHospital = typeKey == "hospital"
    LazyColumn(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(appText("Маршрут для своих", "Үҙ кешеләрең өсөн маршрут"), fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(appText("Укажите путь, места и цену. Контакты откроются после подтверждения.", "Юлды, урындарҙы һәм хаҡты күрһәтегеҙ. Контакттар раҫланғандан һуң асыла."), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Чипы популярных направлений (слот): тап заполняет «откуда/куда». Пустой список — ничего не рисует.
        routeChips?.let { chips -> item { chips() } }
        // Поля адреса: если слот дан (реальный экран с гео-подсказками) — рисуем его; иначе (тест/фолбэк) —
        // простое поле с тем же поведением ввода. Оба варианта поведенчески идентичны для пользователя.
        item {
            if (fromField != null) fromField() else OutlinedTextField(
                value = from, onValueChange = onFromChange,
                label = { Text(appText("Откуда", "Ҡайҙан")) },
                leadingIcon = { Icon(Icons.Default.LocationOn, contentDescription = null) },
                singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)
            )
        }
        item {
            if (toField != null) toField() else OutlinedTextField(
                value = to, onValueChange = onToChange,
                label = { Text(appText("Куда", "Ҡайҙа")) },
                leadingIcon = { Icon(Icons.Default.NearMe, contentDescription = null) },
                singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)
            )
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(appText("Тип поездки", "Сәфәр төрө"), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                val rideTypeKeys = remember { listOf("regular", "parcel", "cargo", "urgent", "hospital") }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(rideTypeKeys, key = { it }) { key ->
                        val (icon, ru, ba) = rideTypeMeta(key)
                        RideTypeChip(icon = icon, ru = ru, ba = ba, selected = typeKey == key) { onSelectType(key) }
                    }
                }
            }
        }
        // F22: выбор клиники-назначения (только для типа «В больницу»). Деликатно — это логистика:
        // куда едешь, чтобы пассажиры из района могли подсесть. Без мед.данных.
        if (isHospital) {
            item {
                Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.LocalHospital, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(appText("Клиника назначения", "Билдәләнгән клиника"), fontWeight = FontWeight.Bold, color = CanonText, fontSize = 16.sp)
                        }
                        Text(
                            appText("Выбери, к какой клинике едешь — попутчики к ней смогут подсесть. Это просто точка назначения.",
                                "Ҡайһы клиникаға бараһың — юлдаштар ҡушыла алһын. Был бары тик билдәләнгән нөктә."),
                            color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp,
                        )
                        when {
                            partners.isEmpty() -> Text(appText("Список клиник загружается…", "Клиникалар исемлеге йөкләнә…"), color = CanonMuted, fontSize = 12.sp)
                            else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(partners, key = { it.id }) { p ->
                                    FilledTonalButton(
                                        onClick = { onSelectPartner(if (selectedPartnerId == p.id) null else p.id) },
                                        shape = RoundedCornerShape(14.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp),
                                        colors = ButtonDefaults.filledTonalButtonColors(
                                            containerColor = if (selectedPartnerId == p.id) CanonMint else CanonBg,
                                            contentColor = if (selectedPartnerId == p.id) CanonGreen2 else CanonText,
                                        ),
                                    ) {
                                        Text("${p.name} · ${p.city}", fontSize = 14.sp, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            Box {
                OutlinedTextField(
                    value = dateText,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(appText("Дата и время", "Дата һәм ваҡыт")) },
                    placeholder = { Text(appText("Выберите дату и время", "Дата һәм ваҡыт һайлағыҙ")) },
                    leadingIcon = { Icon(Icons.Default.Schedule, null) },
                    trailingIcon = { Icon(Icons.Default.CalendarMonth, contentDescription = appText("Выбрать дату", "Дата һайлау"), tint = CanonGreen2) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                )
                Box(Modifier.matchParentSize().clickable { onOpenDatePicker() })
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(appText("Повтор", "Ҡабатлау"), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                val recOpts = listOf(
                    "none" to appText("Разово", "Бер тапҡыр"),
                    "weekdays" to appText("По будням", "Эш көндәрендә"),
                    "daily" to appText("Каждый день", "Һәр көн"),
                    "weekly" to appText("Еженедельно", "Аҙна һайын"),
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(recOpts, key = { it.first }) { (key, label) ->
                        FilledTonalButton(
                            onClick = { onSelectRecurrence(key) },
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = if (recurrence == key) CanonMint else CanonSurface,
                                contentColor = if (recurrence == key) CanonGreen2 else CanonText
                            )
                        ) { Text(label, fontSize = 14.sp, maxLines = 1) }
                    }
                }
                if (recurrence != "none") Text(appText("Создадим ближайшие 4 рейса этой серии.", "Был серияның иң яҡын 4 рейсын булдырабыҙ."), color = CanonMuted, fontSize = 12.sp)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = seats, onValueChange = onSeatsChange, label = { Text(appText("Мест", "Урын")) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp))
                OutlinedTextField(value = price, onValueChange = onPriceChange, label = { Text(appText("Цена, ₽", "Хаҡ, ₽")) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp))
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (priceHint > 0) {
                    PriceHintChip(price = priceHint) { onUsePriceHint() }
                }
                // Честно про бензин: примерная длина маршрута + расход и по-соседски справедливый сплит.
                val fuelKop = fuelEstimateKop
                if (fuelKop != null && fuelKop > 0) {
                    val fuelRub = (fuelKop / 100.0).roundToInt()
                    val seatsInt = (seats.toIntOrNull() ?: 0).coerceAtLeast(1)
                    val perPerson = (fuelRub.toDouble() / seatsInt).roundToInt().coerceAtLeast(1)
                    val km = fuelDistanceKm?.let { it.roundToInt() } ?: 0
                    FuelHintBlock(km = km, fuelRub = fuelRub, perPerson = perPerson, seats = seatsInt)
                }
                Text(appText("Цену ставишь ты. Оплата — напрямую тебе после поездки. Юлдаш комиссию не берёт.", "Хаҡты үҙең ҡуяһың. Түләү — сәфәрҙән һуң тура һиңә. Юлдаш комиссия алмай."), color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                pickupChips?.invoke()   // F14: чипы «частые точки сбора» для выбранного города (если есть)
                OutlinedTextField(
                    value = pickup,
                    onValueChange = onPickupChange,
                    label = { Text(appText("Где встречаемся", "Ҡайҙа осрашабыҙ")) },
                    placeholder = { Text(appText("Напр.: у автовокзала, АЗС на выезде", "Мәҫәлән: автовокзал янында, сығыштағы АЗС")) },
                    leadingIcon = { Icon(Icons.Default.LocationOn, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                )
                OutlinedButton(
                    onClick = onOpenPicker,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, if (pinned) CanonGreen2 else CanonBorder)
                ) {
                    Icon(if (pinned) Icons.Default.CheckCircle else Icons.Default.Map, contentDescription = null, tint = if (pinned) CanonGreen2 else CanonText, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (pinned) appText("Точка на карте отмечена · изменить", "Картала билдәләнде · үҙгәртергә") else appText("Отметить на карте", "Картала билдәләргә"), color = if (pinned) CanonGreen2 else CanonText)
                }
            }
        }
        item {
            OutlinedTextField(
                value = comment,
                onValueChange = onCommentChange,
                label = { Text(if (isCargo) appText("Что везёте", "Нимә алып бараһығыҙ") else appText("Комментарий", "Аңлатма")) },
                placeholder = { Text(if (isCargo) appText("Напр.: диван и 2 коробки, хрупкое", "Мәҫәлән: диван һәм 2 ҡумта, һынғыс") else appText("Например: могу взять посылку, заеду через Темясово", "Мәҫәлән: посылка ала алам, Темясово аша инәм")) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            )
        }
        // Посылка: получатель + габарит/вес (только parcel/cargo).
        if (isCargo) {
            item {
                OutlinedTextField(
                    value = receiverName,
                    onValueChange = onReceiverNameChange,
                    label = { Text(appText("Кому передать (имя)", "Кемгә тапшырырға (исем)")) },
                    placeholder = { Text(appText("Напр.: Айгуль, заберёт на автовокзале", "Мәҫәлән: Айгүл, автовокзалда алыр")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                )
            }
            item {
                OutlinedTextField(
                    value = parcelSize,
                    onValueChange = onParcelSizeChange,
                    label = { Text(appText("Габарит / вес", "Үлсәм / ауырлыҡ")) },
                    placeholder = { Text(appText("Напр.: до 5 кг, коробка 40×30", "Мәҫәлән: 5 кг ҡәҙәр, ҡумта 40×30")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                )
            }
        }
        item {
            // Остановки по пути (несколько точек): A → точки → B. Заезды по дороге, чтобы
            // попутчики с этих мест могли найти поездку. До 4 остановок.
            Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.yu_multi_stop), contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(appText("Остановки по пути", "Юл буйындағы туҡталыштар"), fontWeight = FontWeight.Bold, color = CanonText, fontSize = 16.sp)
                    }
                    Text(
                        appText("Куда заезжаешь по дороге — так тебя найдут попутчики с этих мест.", "Юлда ҡайҙа туҡтайһың — шул урындарҙан юлдаштар һине табыр."),
                        color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(horizontal = 12.dp),
                    )
                    waypoints.forEachIndexed { i, wp ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = wp,
                                onValueChange = { v -> onWaypointsChange(waypoints.toMutableList().also { it[i] = v.take(80) }) },
                                placeholder = { Text(appText("Например, Темясово", "Мәҫәлән, Темәс")) },
                                singleLine = true, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp),
                            )
                            IconButton(onClick = { onWaypointsChange(waypoints.toMutableList().also { it.removeAt(i) }) }, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Default.Close, contentDescription = appText("Убрать остановку", "Туҡталышты алып ташлау"), tint = CanonMuted)
                            }
                        }
                    }
                    if (waypoints.size < 4) {
                        TextButton(onClick = { onWaypointsChange(waypoints + "") }, modifier = Modifier.padding(horizontal = 8.dp)) {
                            Icon(Icons.Default.Add, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(appText("Добавить остановку", "Туҡталыш өҫтәү"), color = CanonGreen2, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        item {
            Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    Text(appText("Условия поездки", "Сәфәр шарттары"), modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), fontWeight = FontWeight.Bold, color = CanonText, fontSize = 16.sp)
                    PrefToggleRow(R.drawable.yu_women_only, appText("Только женщины", "Тик ҡатын-ҡыҙ өсөн"), womenOnly) { onWomenOnly(it) }
                    PrefToggleRow(R.drawable.yu_child_seat, appText("Детское кресло / бустер", "Балалар ултырғысы / бустер"), childSeat) { onChildSeat(it) }
                    PrefToggleRow(R.drawable.yu_pet, appText("Можно с животным", "Хайуан менән"), petsAllowed) { onPetsAllowed(it) }
                    PrefToggleRow(R.drawable.yu_luggage, appText("Есть место под багаж", "Багаж урыны бар"), baggage) { onBaggage(it) }
                    PrefToggleRow(R.drawable.yu_ac, appText("Кондиционер", "Кондиционер"), airConditioner) { onAirConditioner(it) }
                    PrefToggleRow(R.drawable.yu_quiet, appText("Тихая поездка", "Тыныс сәфәр"), quiet) { onQuiet(it) }
                    PrefToggleRow(Icons.Default.SmokingRooms, appText("Можно курить", "Тартырға ярай"), smoking) { onSmoking(it) }
                }
            }
        }
        item {
            Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    PrefToggleRow(Icons.Default.Groups, appText("Только для своих", "Тик үҙебеҙҙекеләр өсөн"), onlyTrusted) { onOnlyTrusted(it) }
                    Text(
                        appText(
                            "Поездку увидят и возьмут только проверенные «свои» (уровень «Свой»).",
                            "Сәфәрҙе тик тикшерелгән «үҙебеҙҙекеләр» (Үҙебеҙҙеке кимәле) күрер һәм алыр.",
                        ),
                        modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp),
                        color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp,
                    )
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
            error?.let {
                Text(it, color = CanonRed, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(bottom = 8.dp))
            }
            AppButton(
                text = appText("Опубликовать", "Баҫтырыу"),
                loading = loading,
                onClick = onPublish,
                enabled = !loading && createRideValid(from, to, price),   // маршрут задан + цена в диапазоне
                modifier = Modifier.testTag("publish_btn"),
            )
        }
        item {
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text(appText("Отмена", "Кире алыу"))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PrivacyScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        LocationPrefs.sharingEnabled = granted
    }
    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Конфиденциальность", "Хосусилыҡ"), onBack) }) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(4.dp))
            Text(appText("Управляй тем, что видят другие", "Башҡалар нимә күрә — үҙең хәл ит"), color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp)
            Card(colors = CardDefaults.cardColors(containerColor = CanonSurface), shape = CanonCardShape, elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.card)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LocationOn, contentDescription = null, tint = CanonGreen2)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(appText("Моя геолокация", "Минең геолокация"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(appText("Показывать мою точку на карте", "Картала минең нөктәне күрһәтеү"), color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp)
                    }
                    Spacer(Modifier.width(8.dp))
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

// Чистый чип типа поездки: иконка + двуязычная подпись, подсветка выбранного. Стейт (какой выбран)
// живёт в экране — сюда приходит `selected` + `onClick`. Без состояния/сети → покрыт Robolectric.
@Composable
internal fun RideTypeChip(icon: ImageVector, ru: String, ba: String, selected: Boolean, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = if (selected) CanonMint else CanonSurface,
            contentColor = if (selected) CanonGreen2 else CanonText
        )
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text(appText(ru, ba), fontSize = 14.sp, maxLines = 1)
    }
}

/**
 * Чипы популярных направлений (из справочника /settlements/popular-routes): тап заполняет
 * «откуда» и «куда» разом. Имена — на текущем языке (BA, если есть перевод). Пустой список —
 * ничего не рисуем (ошибка сети/нет данных); появление — мягкое (fade + разворот).
 */
@Composable
internal fun PopularRouteChips(routes: List<SettlementRouteDto>, onPick: (String, String) -> Unit) {
    val language = LocalAppLanguage.current
    AnimatedVisibility(
        visible = routes.isNotEmpty(),
        enter = fadeIn(tween(CanonMotion.QUICK)) + expandVertically(tween(CanonMotion.QUICK)),
        exit = fadeOut(tween(CanonMotion.QUICK)) + shrinkVertically(tween(CanonMotion.QUICK)),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(appText("Популярные направления", "Популяр йүнәлештәр"), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = CanonText)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(routes, key = { "${it.from.id}-${it.to.id}" }) { r ->
                    val f = settlementTitleFor(language, r.from)
                    val t = settlementTitleFor(language, r.to)
                    FilledTonalButton(
                        onClick = { onPick(f, t) },
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        modifier = Modifier.heightIn(min = 48.dp),   // тач-цель ≥48dp
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = CanonMint, contentColor = CanonGreen2)
                    ) {
                        Icon(painterResource(R.drawable.yu_route), contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("$f → $t", fontSize = 14.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

// Чистая подсказка цены: «обычно по маршруту ~N ₽ · нажми, чтобы подставить». Значение приходит
// параметром (считается выше через API), клик подставляет цену. Без состояния/сети → покрыт Robolectric.
@Composable
internal fun PriceHintChip(price: Int, onClick: () -> Unit) {
    Surface(
        color = CanonMint, shape = RoundedCornerShape(14.dp),
        modifier = Modifier.minimumInteractiveComponentSize().clickable { onClick() }
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.TrendingUp, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(appText("Обычно по маршруту ~$price ₽ · нажми, чтобы подставить", "Был юл буйынса ғәҙәттә ~$price ₽ · ҡуйыр өсөн баҫ"), color = CanonGreen2, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}

/**
 * Честный бензин по маршруту: примерная длина + оценка топлива (с сервера) + мягкий по-соседски
 * справедливый сплит «≈ N ₽ с человека». Только информирует — цену водитель ставит сам.
 * Данные приходят параметрами (сервер), поля отсутствуют → блок не рисуется (гейт выше).
 */
@Composable
internal fun FuelHintBlock(km: Int, fuelRub: Int, perPerson: Int, seats: Int) {
    Surface(color = CanonSurface, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, CanonBorder)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Default.LocalGasStation, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (km > 0) appText("≈ $km км · бензин ≈ $fuelRub ₽", "≈ $km км · бензин ≈ $fuelRub ₽")
                    else appText("Бензин на маршрут ≈ $fuelRub ₽", "Юлға бензин ≈ $fuelRub ₽"),
                    color = CanonText, fontSize = 14.sp, fontWeight = FontWeight.Bold, lineHeight = 20.sp,
                )
                Text(
                    appText("По-соседски: ≈ $perPerson ₽ с человека, если разделить на $seats.", "Күршеләрсә: бүлешһәгеҙ, ≈ $perPerson ₽ бер кешенән ($seats кешегә)."),
                    color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp,
                )
            }
        }
    }
}

/**
 * F14 · Подсказки точек сбора по ориентирам города/села («у мечети», «у Магнита», «автовокзал»).
 * РБ-фишка: в сёлах адресов нет — «встретимся у мечети» понятнее координат. Сам грузит справочник
 * для [city] (публичный, без токена); тап по чипу отдаёт выбранную точку через [onSelect] (координаты
 * подставятся вместо ручного тыка в карту). Пусто/нет сети → ничего не показываем, ручной выбор остаётся.
 */
@Composable
internal fun PickupSuggestionChips(
    city: String,
    selectedId: Int?,
    onSelect: (PickupPointDto) -> Unit,
) {
    val isBa = LocalAppLanguage.current == AppLanguage.Ba
    var points by remember { mutableStateOf<List<PickupPointDto>>(emptyList()) }
    // Дебаунс: город печатают по буквам — не дёргаем сервер на каждый символ.
    LaunchedEffect(city) {
        val c = city.trim()
        if (c.isBlank()) { points = emptyList(); return@LaunchedEffect }
        delay(350)
        points = ApiClient.getPickupPoints(c).getOrNull().orEmpty()
    }
    AnimatedVisibility(visible = points.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                appText("Частые точки сбора рядом", "Яҡындағы йыш осрашыу нөктәләре"),
                fontSize = 14.sp, fontWeight = FontWeight.Medium, color = CanonMuted
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(points, key = { it.id }) { p ->
                    val selected = p.id == selectedId
                    val title = if (isBa && p.titleBa.isNotBlank()) p.titleBa else p.titleRu
                    Surface(
                        color = if (selected) CanonMint else CanonSurface,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .heightIn(min = 40.dp)
                            .border(
                                BorderStroke(1.dp, if (selected) CanonGreen2 else CanonBorder),
                                RoundedCornerShape(14.dp)
                            )
                            .clickable { onSelect(p) }
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                if (selected) Icons.Default.CheckCircle else Icons.Default.LocationOn,
                                contentDescription = null,
                                tint = if (selected) CanonGreen2 else CanonMuted,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                title,
                                fontSize = 14.sp,
                                maxLines = 1,
                                color = if (selected) CanonGreen2 else CanonText
                            )
                        }
                    }
                }
            }
        }
    }
}
