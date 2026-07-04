package com.yuldash.app

// Экраны «Поддержать Юлдаш» (донат) и Boost (поднятие). Вынесено из MainActivity (Фаза 1).
// Импорты скопированы целиком — лишние = варнинги.

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
import com.yuldash.app.data.BoostPlanDto
import com.yuldash.app.data.BoostResultDto
import com.yuldash.app.data.RideDto
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

/**
 * Экран-обёртка («умная» часть): держит стейт доната, ходит в ApiClient за реквизитами СБП,
 * показывает нижний лист перевода. Весь рендер вынесен в чистый [SupportContent].
 * `SbpTransferSheet` тянет буфер обмена / QR / `LocalContext` (`SberPayBlock`) — он НЕ чистый,
 * поэтому остаётся здесь и отдаётся в Content слотом `sbpSlot`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SupportScreen(onBack: () -> Unit) {
    val minAmount = 10
    val maxAmount = 100_000
    var selectedAmount by remember { mutableIntStateOf(30) }
    var customMode by remember { mutableStateOf(false) }
    var customInput by remember { mutableStateOf("") }
    var completed by remember { mutableStateOf(false) }
    var showSbp by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var donation by remember { mutableStateOf<com.yuldash.app.data.BoostResultDto?>(null) }
    var sending by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf(false) }

    // Сумма к отправке: либо пресет, либо введённая вручную (если режим «своя сумма»).
    val customAmount = customInput.toIntOrNull()
    val effectiveAmount = if (customMode) customAmount else selectedAmount
    val amountValid = effectiveAmount != null && effectiveAmount in minAmount..maxAmount

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { ScreenTopBar(appText("Поддержать Юлдаш", "Юлдашҡа ярҙам итеү"), onBack) }
    ) { padding ->
        SupportContent(
            selectedAmount = selectedAmount,
            customMode = customMode,
            customInput = customInput,
            completed = completed,
            sending = sending,
            sendError = sendError,
            minAmount = minAmount,
            effectiveAmount = effectiveAmount,
            amountValid = amountValid,
            onSelectAmount = { amount ->
                selectedAmount = amount
                customMode = false
                completed = false
                sendError = false
            },
            onToggleCustom = {
                customMode = !customMode
                completed = false
                sendError = false
            },
            onCustomInputChange = { new ->
                // Только цифры, максимум 6 знаков (до 100 000).
                customInput = new.filter { it.isDigit() }.take(6)
                completed = false
                sendError = false
            },
            onDonate = {
                val amount = effectiveAmount ?: return@SupportContent
                sending = true
                sendError = false
                scope.launch {
                    ApiClient.createDonation(amount)
                        .onSuccess { donation = it; showSbp = true }
                        .onFailure { sendError = true }
                    sending = false
                }
            },
            onBack = onBack,
            modifier = Modifier.padding(padding),
        )
        if (showSbp) SbpTransferSheet(
            donation?.amount ?: effectiveAmount ?: selectedAmount,
            onPaid = { showSbp = false; completed = true },
            onDismiss = { showSbp = false },
            payeePhone = donation?.payeePhone, payeeBank = donation?.payeeBank, payeeName = donation?.payeeName,
        )
    }
}

/**
 * Чистый рендер экрана поддержки: суммы-пресеты, «своя сумма», кнопка доната, ошибка, «ждём перевод».
 * Стейт и действия — параметрами/колбэками → без сети/эффектов → тестируется на JVM (Robolectric).
 * Нижний лист перевода (`SbpTransferSheet` с QR/буфером) живёт в обёртке [SupportScreen], не тут.
 */
@Composable
internal fun SupportContent(
    selectedAmount: Int,
    customMode: Boolean,
    customInput: String,
    completed: Boolean,
    sending: Boolean,
    sendError: Boolean,
    minAmount: Int,
    effectiveAmount: Int?,
    amountValid: Boolean,
    onSelectAmount: (Int) -> Unit,
    onToggleCustom: () -> Unit,
    onCustomInputChange: (String) -> Unit,
    onDonate: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val amounts = listOf(10, 30, 50, 100)
    val errMsg = appText("Не получилось. Проверь сеть и повтори.", "Булманы. Селтәрҙе тикшереп ҡабатла.")
    LazyColumn(
        modifier = modifier.padding(16.dp),
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
                        onClick = { onSelectAmount(amount) },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = if (!customMode && selectedAmount == amount) MaterialTheme.colorScheme.primaryContainer else CanonSurface
                        )
                    ) {
                        Text("$amount ₽")
                    }
                }
            }
        }
        item {
            OutlinedButton(
                onClick = onToggleCustom,
                modifier = Modifier.fillMaxWidth(),
                colors = if (customMode) ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                         else ButtonDefaults.outlinedButtonColors()
            ) {
                Text(appText("Своя сумма", "Үҙеңдең сумма"))
            }
        }
        if (customMode) {
            item {
                OutlinedTextField(
                    value = customInput,
                    onValueChange = onCustomInputChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text(appText("Сумма, ₽", "Сумма, ₽")) },
                    placeholder = { Text(appText("Например, 200", "Мәҫәлән, 200")) },
                    suffix = { Text("₽") },
                    isError = customInput.isNotEmpty() && !amountValid,
                    supportingText = {
                        if (customInput.isNotEmpty() && !amountValid)
                            Text(appText("От $minAmount до 100 000 ₽", "$minAmount‑дан 100 000 ₽‑ҡа тиклем"))
                    }
                )
            }
        }
        item {
            AppButton(
                text = if (amountValid) appText("Поддержать на $effectiveAmount ₽", "$effectiveAmount ₽ менән ярҙам итеү")
                       else appText("Поддержать", "Ярҙам итеү"),
                onClick = onDonate,
                style = AppButtonStyle.Accent,
                icon = Icons.Default.Payments,
                loading = sending,
                enabled = amountValid && !sending,
            )
        }
        if (sendError) {
            item {
                Text(errMsg, color = CanonRed, fontSize = 14.sp, modifier = Modifier.fillMaxWidth())
            }
        }
        if (completed) {
            item {
                InfoCard(
                    // Нейтральный заголовок: карточка появляется по нажатию «Я перевёл» ДО подтверждения перевода —
                    // «Спасибо за поддержку» читалось как «оплата прошла». Честно: перевод ещё ждём.
                    title = appText("Ждём перевод", "Күсереүҙе көтәбеҙ"),
                    text = appText("Когда админ увидит перевод — донат засчитается. Спасибо! Деньги идут на серверы, карты и SMS.", "Админ күсереүҙе күргәс — донат иҫәпләнә. Рәхмәт! Аҡса серверҙарға, карталарға һәм SMS-ҡа китә."),
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
}

/**
 * Экран-обёртка («умная» часть): держит стейт, грузит планы/поездки/бонусы, ходит в ApiClient,
 * дёргает Toast/Intent. Весь рендер вынесен в чистый [BoostContent].
 * `BoostResultCard` тянет QR/буфер обмена (`SberPayBlock` + `LocalClipboardManager`) — он НЕ чистый,
 * поэтому остаётся здесь и передаётся в Content слотом `resultSlot`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BoostScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var plans by remember { mutableStateOf<List<BoostPlanDto>>(emptyList()) }
    var rides by remember { mutableStateOf<List<RideDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf(false) }
    var selectedRideId by remember { mutableStateOf<Int?>(null) }
    var selectedTier by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<BoostResultDto?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var credits by remember { mutableStateOf(0) }   // реферальные бонусы = бесплатные поднятия
    val failText = appText("Не получилось. Повтори.", "Булманы. Ҡабатла.")  // appText @Composable → хойстим из корутины
    val freeBoostOkMsg = appText("Поездка поднята бесплатно на 24 часа", "Сәфәр 24 сәғәткә бушлай күтәрелде")

    fun reload() {
        loading = true; loadError = false
        scope.launch {
            val p = ApiClient.getBoostPlans()
            val r = ApiClient.getDriverRides()
            p.onSuccess { plans = it }
            r.onSuccess { list -> rides = list; if (selectedRideId == null) selectedRideId = list.firstOrNull()?.id }
            ApiClient.getReferral().onSuccess { credits = it.credits }
            loadError = p.isFailure || r.isFailure
            loading = false
        }
    }
    LaunchedEffect(Unit) { reload() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { ScreenTopBar(appText("Поднять объявление", "Иғланды өҫкә күтәреү"), onBack) }
    ) { padding ->
        BoostContent(
            loading = loading,
            loadError = loadError,
            rides = rides,
            plans = plans,
            selectedRideId = selectedRideId,
            selectedTier = selectedTier,
            submitting = submitting,
            error = error,
            credits = credits,
            result = result,
            onRetry = { reload() },
            onEmptyAction = onBack,
            onSelectRide = { rid -> selectedRideId = rid; result = null; error = null },
            onSelectTier = { tier -> selectedTier = tier; result = null; error = null },
            onBoostFree = {
                val rid = selectedRideId ?: return@BoostContent
                submitting = true; error = null
                scope.launch {
                    ApiClient.boostFree(rid)
                        .onSuccess { left -> credits = left; Toast.makeText(context, freeBoostOkMsg, Toast.LENGTH_SHORT).show(); reload() }
                        .onFailure { e -> error = (e as? ApiException)?.message ?: failText }
                    submitting = false
                }
            },
            onPay = {
                val rid = selectedRideId ?: return@BoostContent
                val tier = selectedTier ?: return@BoostContent
                submitting = true; error = null; result = null
                scope.launch {
                    ApiClient.createBoost(rid, tier)
                        .onSuccess { res ->
                            result = res
                            if (res.method == "yookassa" && !res.confirmationUrl.isNullOrBlank()) {
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(res.confirmationUrl))) }
                            }
                            if (res.status == "succeeded") reload()
                        }
                        .onFailure { e -> error = (e as? ApiException)?.message ?: failText }
                    submitting = false
                }
            },
            resultSlot = { res -> BoostResultCard(res, clipboard) },
            modifier = Modifier.padding(padding).fillMaxSize(),
        )
    }
}

/**
 * Чистый рендер экрана поднятия: все состояния (загрузка / ошибка+повтор / пусто / список тарифов+поездок).
 * Данные и колбэки — параметрами. Сеть/стейт/Toast/Intent живут в обёртке [BoostScreen].
 * `resultSlot` — слот под результат оплаты (там QR/буфер обмена, поэтому рисуется снаружи, не тут).
 */
@Composable
internal fun BoostContent(
    loading: Boolean,
    loadError: Boolean,
    rides: List<RideDto>,
    plans: List<BoostPlanDto>,
    selectedRideId: Int?,
    selectedTier: String?,
    submitting: Boolean,
    error: String?,
    credits: Int,
    result: BoostResultDto?,
    onRetry: () -> Unit,
    onEmptyAction: () -> Unit,
    onSelectRide: (Int) -> Unit,
    onSelectTier: (String) -> Unit,
    onBoostFree: () -> Unit,
    onPay: () -> Unit,
    resultSlot: @Composable (BoostResultDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        when {
            loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = CanonGreen)
            loadError -> StateMessage(
                icon = Icons.Default.Refresh,
                title = appText("Не удалось загрузить", "Йөкләп булманы"),
                text = appText("Проверь соединение и попробуй снова.", "Бәйләнеште тикшереп, ҡабат ҡара."),
                actionText = appText("Повторить", "Ҡабатлау"),
                onAction = onRetry,
            )
            rides.isEmpty() -> StateMessage(
                icon = Icons.Default.AddRoad,
                title = appText("Нет активных поездок", "Әүҙем сәфәрҙәр юҡ"),
                text = appText("Сначала опубликуй поездку — потом её можно поднять выше в списке.", "Башта сәфәр бастыр — һуңынан уны исемлектә өҫкә күтәреп була."),
                actionText = appText("Понятно", "Аңлашыла"),
                onAction = onEmptyAction,
            )
            else -> LazyColumn(
                modifier = Modifier.padding(16.dp).fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(appText("Какую поездку поднять", "Ҡайһы сәфәрҙе күтәрергә"),
                        fontWeight = FontWeight.Black, fontSize = 15.sp, color = CanonText)
                }
                items(rides, key = { it.id }) { ride ->
                    BoostRideRow(ride, selected = ride.id == selectedRideId,
                        onClick = { onSelectRide(ride.id) })
                }
                item {
                    Text(appText("Тариф поднятия", "Күтәреү тарифы"),
                        fontWeight = FontWeight.Black, fontSize = 15.sp, color = CanonText,
                        modifier = Modifier.padding(top = 4.dp))
                }
                itemsIndexed(plans, key = { i, it -> "${it.tier}#$i" }) { i, plan ->
                    BoostPlanCard(plan, selected = plan.tier == selectedTier,
                        onClick = { onSelectTier(plan.tier) })
                }
                error?.let { msg ->
                    item { Text(msg, color = CanonRed, fontSize = 14.sp) }
                }
                if (credits > 0) {
                    item {
                        AppButton(
                            text = appText("Поднять бесплатно ($credits бонус.)", "Бушлай күтәреү ($credits бонус)"),
                            onClick = onBoostFree,
                            style = AppButtonStyle.Secondary,
                            icon = Icons.Default.TrendingUp,
                            enabled = selectedRideId != null && !submitting,
                        )
                    }
                }
                item {
                    val plan = plans.firstOrNull { it.tier == selectedTier }
                    AppButton(
                        text = if (plan != null) appText("Оплатить ${plan.price} ₽", "${plan.price} ₽ түләү")
                               else appText("Выбери тариф", "Тариф һайла"),
                        onClick = onPay,
                        style = AppButtonStyle.Accent,
                        icon = Icons.Default.Payments,
                        enabled = selectedTier != null && selectedRideId != null && !submitting,
                    )
                }
                result?.let { res -> item { resultSlot(res) } }
                item {
                    Text(
                        appText("Поднятие не гарантирует бронирование и влияет только на релевантные результаты.", "Күтәреү бронде гарантияламай һәм тик тура килгән һөҙөмтәләргә генә йоғонто яһай."),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp
                    )
                }
            }
        }
    }
}

@Composable
internal fun BoostRideRow(ride: RideDto, selected: Boolean, onClick: () -> Unit) {
    val border by animateColorAsState(if (selected) CanonGreen else Color.Transparent, label = "rideBorder")
    Card(
        modifier = Modifier.fillMaxWidth().bounceClick(onClick).border(2.dp, border, RoundedCornerShape(18.dp)),
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (selected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                contentDescription = null, tint = if (selected) CanonGreen else CanonMuted)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("${ride.fromCity} → ${ride.toCity}", fontWeight = FontWeight.Bold, color = CanonText)
                Text(appText("${ride.seatsLeft} мест · ${ride.price} ₽", "${ride.seatsLeft} урын · ${ride.price} ₽"),
                    fontSize = 13.sp, color = CanonMuted)
            }
            if (ride.boosted) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.TrendingUp, contentDescription = null, tint = CanonGreen, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(appText("уже поднята", "күтәрелгән"), fontSize = 12.sp, color = CanonGreen)
                }
            }
        }
    }
}

@Composable
internal fun BoostPlanCard(plan: BoostPlanDto, selected: Boolean, onClick: () -> Unit) {
    val border by animateColorAsState(if (selected) CanonGreen else Color.Transparent, label = "planBorder")
    val sub = when (plan.tier) {
        "quick" -> appText("${plan.hours} часа выше в списке", "${plan.hours} сәғәт исемлектә өҫтәрәк")
        "day" -> appText("${plan.hours} часа выше + выделение на карте", "${plan.hours} сәғәт өҫтә + картала айырыу")
        "urgent" -> appText("${plan.hours} часов выше, выделение, метка срочно", "${plan.hours} сәғәт өҫтә, айырыу, ашығыс билдәһе")
        else -> appText("${plan.hours} ч выше в списке", "${plan.hours} сәғәт өҫтәрәк")
    }
    Card(
        modifier = Modifier.fillMaxWidth().bounceClick(onClick).border(2.dp, border, RoundedCornerShape(20.dp)),
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.TrendingUp, contentDescription = null, tint = if (selected) CanonGreen else CanonMuted)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(plan.title, fontWeight = FontWeight.Black, color = CanonText)
                Text(sub, fontSize = 13.sp, color = CanonMuted)
            }
            Surface(color = if (selected) CanonGreen else CanonGreen.copy(alpha = 0.12f), shape = RoundedCornerShape(50)) {
                Text("${plan.price} ₽", color = if (selected) Color.White else CanonGreen,
                    fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
    }
}

/**
 * Обёртка результата оплаты буста: держит импур-часть (буфер обмена для «Скопировать» и QR-блок
 * `SberPayBlock`, тянущий `LocalContext`) и отдаёт её в чистый [BoostResultContent] через колбэк
 * `onCopyPhone` и слот `sberPaySlot`. Весь текст/ветвление — в Content → тестируется на JVM.
 */
@Composable
internal fun BoostResultCard(res: BoostResultDto, clipboard: androidx.compose.ui.platform.ClipboardManager) {
    BoostResultContent(
        res = res,
        onCopyPhone = { phone -> clipboard.setText(AnnotatedString(phone)) },
        sberPaySlot = { phone -> SberPayBlock(phone, Modifier.padding(top = 4.dp)) },
    )
}

/**
 * Чистый рендер результата оплаты: 3 ветки (успех / ручной СБП / переход к ЮKassa) + двуязычные
 * тексты и фолбэк «реквизиты не пришли». Буфер обмена и QR (`SberPayBlock`) — снаружи: копирование
 * через `onCopyPhone`, QR-блок через `sberPaySlot`. Без сети/контекста → Robolectric на JVM.
 */
@Composable
internal fun BoostResultContent(
    res: BoostResultDto,
    onCopyPhone: (String) -> Unit,
    sberPaySlot: @Composable (String) -> Unit,
) {
    when {
        res.status == "succeeded" -> InfoCard(
            title = appText("Объявление поднято", "Иғлан күтәрелде"),
            text = appText("Поднятие включено. Спасибо!", "Күтәреү ҡабыҙылды. Рәхмәт!"),
            icon = Icons.Default.CheckCircle
        )
        res.method == "sbp_manual" -> Card(
            colors = CardDefaults.cardColors(containerColor = CanonSurface),
            shape = RoundedCornerShape(20.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(appText("Переведи ${res.amount} ₽ по СБП", "СБП аша ${res.amount} ₽ күсер"),
                    fontWeight = FontWeight.Black, fontSize = 16.sp, color = CanonText)
                val payPhone = res.payeePhone?.takeIf { it.isNotBlank() }
                if (payPhone != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(payPhone, fontWeight = FontWeight.Bold, color = CanonText, fontSize = 17.sp)
                            Text(listOfNotNull(res.payeeBank, res.payeeName).joinToString(" · "),
                                fontSize = 13.sp, color = CanonMuted)
                        }
                        OutlinedButton(onClick = { onCopyPhone(payPhone) }) {
                            Text(appText("Скопировать", "Күсереп алыу"))
                        }
                    }
                    // Быстрая оплата: QR + «Оплатить в Сбербанке».
                    sberPaySlot(payPhone)
                } else {
                    // Реквизиты не пришли с сервера → не оставляем юзера без инструкции (фолбэк вместо пустоты).
                    Text(
                        appText("Реквизиты для перевода ещё не подгрузились — напиши в поддержку, поможем перевести.",
                                "Күсереү реквизиттары әле килмәне — ярҙам хеҙмәтенә яҙ, күсерергә ярҙам итәбеҙ."),
                        fontSize = 13.sp, color = CanonMuted
                    )
                }
                Text(
                    appText("После перевода поднятие включим вручную — обычно быстро. Чек придёт от самозанятого.",
                            "Күсергәндән һуң күтәреүҙе ҡулдан ҡабыҙабыҙ — ғәҙәттә тиҙ. Чек самозанятыйҙан килер."),
                    fontSize = 13.sp, color = CanonMuted
                )
            }
        }
        else -> InfoCard(
            title = appText("Переходим к оплате", "Түләүгә күсәбеҙ"),
            text = appText("Заверши оплату в открывшемся окне. После оплаты поднятие включится.",
                           "Асылған тәҙрәлә түләүҙе тамамла. Түләүҙән һуң күтәреү ҡабыҙыла."),
            icon = Icons.Default.Payments
        )
    }
}

@Composable
internal fun StateMessage(icon: ImageVector, title: String, text: String, actionText: String, onAction: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = null, tint = CanonMuted, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(14.dp))
        Text(title, fontWeight = FontWeight.Black, fontSize = 18.sp, color = CanonText, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(text, color = CanonMuted, textAlign = TextAlign.Center, fontSize = 14.sp)
        Spacer(Modifier.height(18.dp))
        Button(onClick = onAction, colors = ButtonDefaults.buttonColors(containerColor = CanonGreen)) {
            Text(actionText, color = Color.White)
        }
    }
}

// ==================== OAUTH / СОЦИАЛЬНЫЕ ВХОДЫ ====================

/**
 * Открывает Telegram бота для входа через OAuth.
 * Бот: t.me/yuldash_bot
 * Bot передаёт userData обратно в приложение через DeepLink.
 */
