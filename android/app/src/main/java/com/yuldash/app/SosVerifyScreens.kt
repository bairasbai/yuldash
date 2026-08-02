package com.yuldash.app

// Экраны SOS и проверки водителя (фото прав/авто). Вынесено из MainActivity (Фаза 1/2).
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
import androidx.compose.material.icons.filled.LocalPolice
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Call
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
import androidx.compose.material3.SwitchDefaults
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

// Категории сигнала «своим» — ровно те, что принимает сервер (Literal в /sos):
// medical | breakdown | other. Больше не придумываем: неизвестную строку сервер отвергнет 422.
internal const val SOS_CATEGORY_MEDICAL = "medical"
internal const val SOS_CATEGORY_BREAKDOWN = "breakdown"
internal const val SOS_CATEGORY_OTHER = "other"

/** Один вариант «что случилось» для сигнала поддержке и близким. */
internal data class SosCategoryUi(val key: String, val icon: ImageVector, val ru: String, val ba: String)

internal val sosCategories = listOf(
    SosCategoryUi(SOS_CATEGORY_MEDICAL, Icons.Default.LocalHospital, "Плохо человеку", "Кешегә насар"),
    SosCategoryUi(SOS_CATEGORY_BREAKDOWN, Icons.Default.DirectionsCar, "Машина сломалась", "Машина ватылған"),
    SosCategoryUi(SOS_CATEGORY_OTHER, Icons.Default.QuestionMark, "Другое", "Башҡа"),
)

// Описание одной экстренной службы для ползунков.
internal data class SosService(
    val key: String,
    val number: String,        // прямой номер для звонка с мобильного
    val label: LocalizedText,
    val icon: ImageVector,
    val sosCategory: String     // что шлём «своим» в наш бэкенд
)

@Composable
internal fun SosScreen(onBack: () -> Unit, onLoginRequired: () -> Unit, orderId: Int? = null) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    // Россия, звонок с мобильного: 102 полиция · 101 пожарные · 103 скорая. 112 — единый (по умолчанию / когда выбрано несколько).
    val services = remember {
        listOf(
            SosService("police", "102", LocalizedText("Полиция", "Полиция"), Icons.Default.LocalPolice, "other"),
            SosService("fire", "101", LocalizedText("Пожарные", "Янғын"), Icons.Default.LocalFireDepartment, "breakdown"),
            SosService("ambulance", "103", LocalizedText("Скорая", "Тиҙ ярҙам"), Icons.Default.LocalHospital, "medical")
        )
    }
    var description by remember { mutableStateOf("") }
    // Тип сигнала «своим». Раньше клиент ВСЕГДА слал "other" — поле категории на сервере было,
    // но никогда не заполнялось, и дежурный не понимал, скорую вызывать или эвакуатор.
    var category by remember { mutableStateOf(SOS_CATEGORY_OTHER) }

    // Строки для Toast (вне Composable-контекста лямбд) — считаем заранее.
    val tCopied = appText("Скопировано", "Күсерелде")
    val tCallFail = appText("Не удалось открыть звонок", "Шылтырауҙы аса алманыҡ")

    // Состояния второго канала — сигнал «своим» водителям через бэкенд.
    var sent by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var rateLimited by remember { mutableStateOf(false) }  // 429 — «слишком часто», не «нет сети»
    val loggedIn = ApiClient.isLoggedIn()

    // Живая геолокация для ЧП — запрашиваем прямо здесь (а не ждём кеш с карты). Главное в SOS.
    var sosLat by remember { mutableStateOf(LocationPrefs.lastLat) }
    var sosLng by remember { mutableStateOf(LocationPrefs.lastLng) }
    var locating by remember { mutableStateOf(false) }
    fun applyLoc(loc: android.location.Location) {
        sosLat = loc.latitude; sosLng = loc.longitude
        LocationPrefs.lastLat = loc.latitude; LocationPrefs.lastLng = loc.longitude
    }
    fun fetchLoc() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        // 1) Мгновенно показать последнее известное (чтобы не было пусто).
        try {
            (lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                ?: lm.getLastKnownLocation(android.location.LocationManager.PASSIVE_PROVIDER))?.let { applyLoc(it) }
        } catch (e: SecurityException) {}
        // 2) Запросить СВЕЖИЙ одноразовый фикс — это и есть реальное «Обновить» (last-known может быть устаревшим).
        val provider = when {
            lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) -> android.location.LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) -> android.location.LocationManager.NETWORK_PROVIDER
            else -> null
        }
        if (provider != null) {
            locating = true
            try {
                lm.requestSingleUpdate(provider, object : android.location.LocationListener {
                    override fun onLocationChanged(loc: android.location.Location) { applyLoc(loc); locating = false }
                    override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
                    override fun onProviderEnabled(p: String) {}
                    override fun onProviderDisabled(p: String) {}
                }, android.os.Looper.getMainLooper())
            } catch (e: SecurityException) { locating = false } catch (e: Exception) { locating = false }
        }
    }
    val locPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) fetchLoc()
    }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) fetchLoc()
        else locPermLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    val coordsText = if (sosLat != null && sosLng != null) String.format(java.util.Locale.US, "%.5f, %.5f", sosLat, sosLng) else null

    fun dictText(): String = buildString {
        if (description.isNotBlank()) append(description.trim())
        if (coordsText != null) {
            if (isNotEmpty()) append("\n")
            append("Координаты: $coordsText")
        }
    }

    fun dial(number: String) {
        // ACTION_DIAL — открывает звонилку с набранным номером. Человек сам жмёт вызов (безопасно, без разрешения CALL_PHONE).
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { Toast.makeText(context, tCallFail, Toast.LENGTH_SHORT).show() }
    }

    fun requestLoc() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) fetchLoc()
        else locPermLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun sendSignal() {
        if (sending) return
        if (!loggedIn) {
            onLoginRequired()
            return
        }
        failed = false
        rateLimited = false
        sent = false
        sending = true
        // В note кладём текст + КООРДИНАТЫ (бэкенд без гео-поля → передаём строкой со ссылкой на карту).
        val note = buildString {
            if (description.isNotBlank()) append(description.trim() + " ")
            if (coordsText != null) append("Координаты: $coordsText (https://yandex.ru/maps/?pt=$sosLng,$sosLat&z=17)")
        }.trim().ifBlank { "SOS" }
        scope.launch {
            // Ждём сервер, НЕ fire-and-forget (кнопка безопасности). orderId — контекст такси-заказа (B7b-2).
            val r = ApiClient.sos(category, note, orderId)
            sending = false
            if (r.isSuccess) {
                sent = true
            } else {
                // 429 = «слишком часто» (rate-limit), а не «нет сети» — показываем честный текст.
                rateLimited = (r.exceptionOrNull() as? ApiException)?.status == 429
                failed = true
            }
        }
    }

    SosContent(
        services = services,
        description = description,
        onDescriptionChange = { description = it },
        category = category,
        onCategoryChange = { category = it },
        coordsText = coordsText,
        locating = locating,
        loggedIn = loggedIn,
        sent = sent,
        failed = failed,
        rateLimited = rateLimited,
        sending = sending,
        onDial = { dial(it) },
        onLocate = { requestLoc() },
        onCopy = {
            clipboard.setText(AnnotatedString(dictText()))
            Toast.makeText(context, tCopied, Toast.LENGTH_SHORT).show()
        },
        onSendSignal = { sendSignal() },
        onBack = onBack,
    )
}

// Чистая презентация экрана SOS: без сети/GPS/эффектов — всё через примитивы и колбэки.
// Обёртка `SosScreen` держит геолокацию, разрешения и вызов бэкенда, а рисует этот Content.
// Пульсирующих (бесконечных) анимаций тут нет → безопасно тестировать целиком на JVM.
@Composable
internal fun SosContent(
    services: List<SosService>,
    description: String,
    onDescriptionChange: (String) -> Unit,
    // Тип сигнала «своим» (значения — как у сервера: medical | breakdown | other).
    // Значения по умолчанию — чтобы старые вызовы/тесты собирались без правок.
    category: String = SOS_CATEGORY_OTHER,
    onCategoryChange: (String) -> Unit = {},
    coordsText: String?,
    locating: Boolean,
    loggedIn: Boolean,
    sent: Boolean,
    failed: Boolean,
    rateLimited: Boolean,
    sending: Boolean,
    onDial: (String) -> Unit,
    onLocate: () -> Unit,
    onCopy: () -> Unit,
    onSendSignal: () -> Unit,
    onBack: () -> Unit,
) {
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
                            Text(appText("Звонок в экстренные службы с твоего номера.", "Ашығыс хеҙмәттәргә үҙ номерыңдан шылтырау."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // 🟥 Главная кнопка — единый 112. Сразу после шапки: в панике нужна одна очевидная кнопка.
            item {
                Button(
                    onClick = { onDial("112") },
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CanonRed)
                ) {
                    Icon(Icons.Default.Call, contentDescription = null, tint = Color.White)
                    Spacer(Modifier.width(10.dp))
                    Text(appText("Позвонить 112", "112 — шылтыратыу"), color = Color.White, fontWeight = FontWeight.Black, fontSize = 20.sp)
                }
            }
            item {
                Text(
                    appText("Звонок идёт с твоего номера. 112 — единый номер всех служб.", "Шылтырау үҙ номерыңдан бара. 112 — бөтә хеҙмәттәрҙең уртаҡ номеры."),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    color = CanonMuted, fontSize = 13.sp, lineHeight = 18.sp
                )
            }

            // Прямой вызов конкретной службы — быстрее 112 (без оператора-маршрутизатора). Тап = сразу звонок.
            item {
                Text(appText("Прямой вызов службы", "Хеҙмәткә туранан-тура"), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, color = CanonText, fontWeight = FontWeight.Black, fontSize = 15.sp)
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    services.forEach { svc ->
                        SosDirectCallChip(
                            label = svc.label.text(),
                            number = svc.number,
                            icon = svc.icon,
                            // Позвонил в скорую → сигнал «своим» уже помечен как медицинский.
                            // Человеку в беде не до выбора категорий — угадываем за него, но видимо
                            // (чип ниже подсветится, можно переключить).
                            onClick = { onCategoryChange(svc.sosCategory); onDial(svc.number) }
                        )
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = description,
                    onValueChange = onDescriptionChange,
                    label = { Text(appText("Что случилось?", "Нимә булды?")) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                )
            }

            // Что продиктовать оператору: текст + координаты (в звонок их вложить нельзя — даём скопировать/прочитать).
            item {
                Surface(color = CanonMint, shape = CanonItemShape) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(appText("Продиктуй оператору", "Операторға әйт"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 15.sp)
                        if (description.isNotBlank()) Text(description.trim(), color = CanonText, fontSize = 15.sp, lineHeight = 20.sp)
                        if (coordsText != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.LocationOn, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(appText("Координаты: ", "Координаталар: ") + coordsText, color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                        } else {
                            Text(appText("Геолокация выключена — включи, чтобы продиктовать координаты.", "Геолокация һүндерелгән — координаталарҙы әйтер өсөн ҡабыҙ."), color = CanonMuted, fontSize = 13.sp, lineHeight = 18.sp)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = onLocate,
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Icon(Icons.Default.NearMe, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(if (locating) appText("Обновляю…", "Яңыртам…") else if (coordsText != null) appText("Обновить", "Яңыртыу") else appText("Включить гео", "Геоны ҡабыҙыу"))
                            }
                            if (description.isNotBlank() || coordsText != null) {
                                OutlinedButton(
                                    onClick = onCopy,
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(appText("Скопировать", "Күсереү"))
                                }
                            }
                        }
                    }
                }
            }

            // 🟧 Второй канал — уведомление доверенным контактам (SMS) + поддержке (Telegram админу). Реальный бэкенд.
            item { Spacer(Modifier.height(4.dp)) }
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(appText("Сообщить близким и поддержке", "Яҡындарға һәм ярҙамға хәбәр итеү"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 17.sp, textAlign = TextAlign.Center)
                    Text(
                        if (loggedIn)
                            appText("SMS твоим доверенным контактам + сигнал поддержке Юлдаш с твоими координатами.", "Ышаныслы контакттарыңа SMS + Юлдаш ярҙамына координаталарың менән сигнал.")
                        else
                            appText("Для SMS близким и сигнала поддержке нужно войти. Звонок 112 работает без входа.", "Яҡындарға SMS һәм ярҙамға сигнал өсөн инергә кәрәк. 112 шылтырауы инеүһеҙ эшләй."),
                        color = CanonMuted, fontSize = 13.sp, lineHeight = 18.sp, textAlign = TextAlign.Center
                    )
                }
            }
            // Тип сигнала: дежурный сразу видит, скорую звать или эвакуатор. Тач-цель 48dp (§4.5) —
            // в панике палец не целится. Горизонтальный скролл: башкирские подписи длиннее русских.
            item {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    sosCategories.forEach { c ->
                        NearbyFilterChip(
                            c.icon, appText(c.ru, c.ba), category == c.key,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { onCategoryChange(c.key) }
                    }
                }
            }
            if (sent) {
                item {
                    InfoCard(
                        title = appText("Сигнал отправлен", "Сигнал ебәрелде"),
                        // Честно: сигнал поддержке (Telegram) уходит всегда; SMS близким зависит от оператора и
                        // при mock-провайдере не доходит. В экстренной ситуации не обещаем SMS — подсказываем позвонить самому.
                        text = appText("Поддержка Юлдаш получила сигнал с твоими координатами. Не жди — если можешь, позвони 112 и близким сам.", "Юлдаш ярҙамы координаталарың менән сигнал алды. Көтмә — мөмкин булһа, 112-гә һәм яҡындарыңа үҙең шылтырат."),
                        icon = Icons.Default.Sos
                    )
                }
            }
            if (failed) {
                item {
                    InfoCard(
                        title = if (rateLimited) appText("Слишком часто", "Артыҡ йыш") else appText("Сигнал не отправлен", "Сигнал ебәрелмәне"),
                        text = if (rateLimited)
                            appText("Сигнал уже отправлялся недавно. Подожди немного и нажми ещё раз.", "Сигнал күптән түгел ебәрелгән. Бер аҙ көт тә тағы баҫ.")
                        else
                            appText("Похоже, нет сети. Проверь связь и нажми ещё раз.", "Бәйләнеш юҡ кеүек. Тикшереп, тағы баҫ."),
                        icon = Icons.Default.Sos
                    )
                }
            }
            item {
                AppButton(
                    text = if (sending) appText("Отправляем…", "Ебәрәбеҙ…") else appText("Сообщить близким и поддержке", "Яҡындарға һәм ярҙамға хәбәр итеү"),
                    onClick = onSendSignal,
                    style = AppButtonStyle.Danger,
                    loading = sending
                )
            }

            item {
                Text(
                    appText("Ложный вызов экстренных служб наказуем по закону.", "Ялған ашығыс саҡырыу закон буйынса язаға тарттырыла."),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    color = CanonMuted, fontSize = 12.sp, lineHeight = 16.sp
                )
            }
            item { TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(appText("Назад", "Кире")) } }
        }
    }
}

// Кнопка прямого вызова службы: тап = сразу звонок на её номер (без вкл/выкл). 3 в ряд.
@Composable
internal fun RowScope.SosDirectCallChip(
    label: String,
    number: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Surface(
        color = CanonSurface,
        shape = CanonItemShape,
        border = BorderStroke(1.dp, CanonBorder),
        modifier = Modifier.weight(1f).bounceClick(onClick)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Icon(icon, contentDescription = null, tint = CanonRed, modifier = Modifier.size(26.dp))
            Text(
                label,
                color = CanonText,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                lineHeight = 15.sp,
                maxLines = 2,
                textAlign = TextAlign.Center,
                overflow = TextOverflow.Ellipsis
            )
            Surface(color = CanonDangerBg, shape = RoundedCornerShape(8.dp)) {
                Text(number, color = CanonRed, fontWeight = FontWeight.Black, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 9.dp, vertical = 2.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VerifyDriverScreen(onBack: () -> Unit, onSelectTab: (HomeTab) -> Unit) {
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
    // Результат авто-проверки прав (OCR) — чтобы показать водителю причину, а не только админу.
    var autocheckResult by remember { mutableStateOf("") }
    var autocheckData by remember { mutableStateOf("") }
    // Тихие сбои сети → показываем понятную ошибку, а не молчим / не врём про «отправлено».
    var submitError by remember { mutableStateOf(false) }
    var uploadError by remember { mutableStateOf(false) }

    // Строки для Toast (вне Composable-контекста лямбд) — считаем заранее.
    val tUploadFail = appText("Не удалось загрузить фото, попробуй ещё раз", "Фотоны йөкләп булманы, тағы ҡабатла")
    val tSubmitFail = appText("Не получилось отправить. Проверь сеть и повтори", "Ебәреп булманы. Сетте тикшереп ҡабатла")
    val tStatusFail = appText("Не удалось загрузить твой статус водителя. Проверь сеть.", "Водитель статусыңды йөкләп булманы. Сетте тикшер.")

    // При сетевом сбое честно предупреждаем (не молчим и не показываем пустую форму как
    // «документы не отправлены», если статус на сервере другой). Повтор — переоткрытием экрана.
    LaunchedEffect(Unit) {
        ApiClient.getDriverStatus()
            .onSuccess { s ->
                docsStatus = s.docsStatus
                verified = s.verified
                autocheckResult = s.autocheckResult
                autocheckData = s.autocheckData
                if (s.carMake.isNotBlank()) make = s.carMake
                if (s.carModel.isNotBlank()) model = s.carModel
                if (s.carColor.isNotBlank()) carColor = s.carColor
                if (s.carPlate.isNotBlank()) plate = s.carPlate
                if (s.seats > 0) seats = s.seats.toString()
                if (s.licenseUrl.isNotBlank()) licenseUrl = s.licenseUrl
                if (s.carPhotoUrl.isNotBlank()) carPhotoUrl = s.carPhotoUrl
            }
            // 401 (не вошёл) — норм, показываем чистую форму. Иначе сеть упала → предупреждаем.
            .onFailure { e -> if ((e as? com.yuldash.app.data.ApiException)?.status != 401) Toast.makeText(context, tStatusFail, Toast.LENGTH_LONG).show() }
    }
    val pickLicense = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            uploadingLicense = true
            uploadError = false
            scope.launch {
                val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                val url = if (bytes != null) ApiClient.uploadPhoto(bytes).getOrNull() else null
                if (url != null) licenseUrl = url
                else { uploadError = true; Toast.makeText(context, tUploadFail, Toast.LENGTH_SHORT).show() }  // не молчим при сбое загрузки
                uploadingLicense = false
            }
        }
    }
    val pickCar = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            uploadingCar = true
            uploadError = false
            scope.launch {
                val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                val url = if (bytes != null) ApiClient.uploadPhoto(bytes).getOrNull() else null
                if (url != null) carPhotoUrl = url
                else { uploadError = true; Toast.makeText(context, tUploadFail, Toast.LENGTH_SHORT).show() }
                uploadingCar = false
            }
        }
    }
    val canSubmit = licenseUrl != null && carPhotoUrl != null && !submitting

    fun submit() {
        submitting = true
        submitError = false
        scope.launch {
            // Профиль и отправка на проверку — обе должны пройти. Любой сбой → честная ошибка, не «pending».
            val profileOk = ApiClient.setDriverProfile(make.trim(), model.trim(), carColor.trim(), plate.trim(), seats.toIntOrNull() ?: 4).isSuccess
            val submitOk = profileOk && ApiClient.submitDriverVerify(licenseUrl ?: "", carPhotoUrl ?: "").isSuccess
            if (submitOk) {
                docsStatus = "pending"
                autocheckResult = ""  // прошлый отказ больше не актуален
                autocheckData = ""
            } else {
                submitError = true
                Toast.makeText(context, tSubmitFail, Toast.LENGTH_SHORT).show()
            }
            submitting = false
        }
    }

    VerifyDriverContent(
        make = make, onMakeChange = { make = it },
        model = model, onModelChange = { model = it },
        carColor = carColor, onColorChange = { carColor = it },
        plate = plate, onPlateChange = { plate = it },
        seats = seats, onSeatsChange = { seats = it.filter(Char::isDigit) },
        licenseUrl = licenseUrl,
        carPhotoUrl = carPhotoUrl,
        uploadingLicense = uploadingLicense,
        uploadingCar = uploadingCar,
        docsStatus = docsStatus,
        verified = verified,
        submitting = submitting,
        submitError = submitError,
        autocheckResult = autocheckResult,
        autocheckData = autocheckData,
        canSubmit = canSubmit,
        onPickLicense = { pickLicense.launch("image/*") },
        onPickCar = { pickCar.launch("image/*") },
        onSubmit = { submit() },
        onSelectTab = onSelectTab,
    )
}

// Чистая презентация экрана «Проверка водителя»: без сети/пикеров/эффектов — только примитивы и колбэки.
// Обёртка `VerifyDriverScreen` держит загрузку статуса, выбор фото и отправку, а рисует этот Content.
// Бесконечных анимаций нет → тестируется целиком на JVM (Robolectric).
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VerifyDriverContent(
    make: String, onMakeChange: (String) -> Unit,
    model: String, onModelChange: (String) -> Unit,
    carColor: String, onColorChange: (String) -> Unit,
    plate: String, onPlateChange: (String) -> Unit,
    seats: String, onSeatsChange: (String) -> Unit,
    licenseUrl: String?,
    carPhotoUrl: String?,
    uploadingLicense: Boolean,
    uploadingCar: Boolean,
    docsStatus: String,
    verified: Boolean,
    submitting: Boolean,
    submitError: Boolean,
    autocheckResult: String,
    autocheckData: String,
    canSubmit: Boolean,
    onPickLicense: () -> Unit,
    onPickCar: () -> Unit,
    onSubmit: () -> Unit,
    onSelectTab: (HomeTab) -> Unit,
) {
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
            // Причина отказа/правки для ВОДИТЕЛЯ (раньше видел только админ): что не так и что делать.
            item { DriverReasonBanner(docsStatus, autocheckResult, autocheckData) }
            if (submitError) {
                item { SubmitErrorBanner() }
            }
            item { Text(appText("Данные автомобиля", "Машина мәғлүмәте"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 17.sp) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = make, onValueChange = onMakeChange, label = { Text(appText("Марка", "Марка")) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp), singleLine = true)
                    OutlinedTextField(value = model, onValueChange = onModelChange, label = { Text(appText("Модель", "Модель")) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp), singleLine = true)
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = carColor, onValueChange = onColorChange, label = { Text(appText("Цвет", "Төҫ")) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp), singleLine = true)
                    OutlinedTextField(value = plate, onValueChange = onPlateChange, label = { Text(appText("Госномер", "Дәүләт номеры")) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp), singleLine = true)
                }
            }
            item {
                OutlinedTextField(value = seats, onValueChange = onSeatsChange, label = { Text(appText("Количество мест", "Урындар һаны")) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), singleLine = true)
            }
            item { Text(appText("Документы (фото)", "Документтар (фото)"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 17.sp) }
            item { UploadTile(appText("Фото водительских прав", "Водитель танытмаһы фотоһы"), licenseUrl != null, uploadingLicense, onPickLicense) }
            item { UploadTile(appText("Фото автомобиля", "Машина фотоһы"), carPhotoUrl != null, uploadingCar, onPickCar) }
            item {
                Button(
                    onClick = onSubmit,
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
internal fun StatusBanner(icon: ImageVector, title: String, sub: String, bg: Color, fg: Color) {
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

// Причина авто-проверки прав для ВОДИТЕЛЯ: что не так и что делать. Зеркало админского AutoCheckRow,
// но человеческим языком и с действием. Показываем при отказе и когда авто-проверка нашла проблему.
@Composable
internal fun DriverReasonBanner(docsStatus: String, autocheckResult: String, autocheckData: String) {
    val rejected = docsStatus == "rejected"
    // Показываем баннер если: заявку отклонили ИЛИ авто-проверка дала reject/needs_human (есть что объяснить).
    val hasAutocheck = autocheckResult == "reject" || autocheckResult == "needs_human"
    if (!rejected && !hasAutocheck) return

    // Парсим JSON защищённо — кривой/пустой ответ не должен ронять экран.
    val parsed = remember(autocheckData) {
        try { org.json.JSONObject(autocheckData) } catch (e: Exception) { org.json.JSONObject() }
    }
    val licenseNumber = parsed.optString("license_number")
    val expiry = parsed.optString("expiry")
    val reasons = remember(autocheckData) {
        val arr = parsed.optJSONArray("reasons")
        if (arr == null) emptyList() else (0 until arr.length()).map { arr.optString(it) }
    }

    // Машинные коды причин → дружелюбный двуязычный текст (коды из backend/driver_check.py).
    val explanations: List<String> = buildList {
        if (reasons.contains("not_a_license")) add(appText("Не разобрали номер прав на фото.", "Фотола права номерын таный алманыҡ."))
        if (reasons.contains("no_license_number")) add(appText("Не нашли номер водительского удостоверения.", "Водитель танытмаһы номерын тапманыҡ."))
        if (reasons.contains("license_expired")) add(appText("Похоже, срок действия прав истёк.", "Права ваҡыты үткән кеүек."))
        if (reasons.contains("no_expiry_date")) add(appText("Не нашли срок действия на фото.", "Фотола ваҡыт срогын тапманыҡ."))
        if (reasons.contains("doc_not_found") || reasons.contains("doc_read_error")) add(appText("Фото прав не открылось. Загрузи его ещё раз.", "Права фотоһы асылманы. Тағы йөклә."))
        // Серверный OCR временно недоступен — это не вина фото; заявку посмотрит человек.
        if (reasons.contains("ocr_unavailable")) add(appText("Авто-проверка временно недоступна — заявку посмотрит человек.", "Авто-тикшереү ваҡытлыса юҡ — заявканы кеше ҡарай."))
        // Совет по качеству фото — общий, когда конкретного кода нет, но что-то пошло не так.
        if (isEmpty()) add(appText("Сделай фото прав чётким: хорошо освещено, без бликов, номер и срок читаются.", "Права фотоһын асыҡ яса: яҡшы яҡтыртылған, ялтырауһыҙ, номер һәм ваҡыт уҡыла."))
    }

    val needsHuman = autocheckResult == "needs_human" && !rejected
    val title = when {
        rejected -> appText("Почему отклонили", "Ниңә кире ҡағылды")
        needsHuman -> appText("Нужна ручная проверка", "Ҡул менән тикшереү кәрәк")
        else -> appText("Что улучшить в фото", "Фотоны нисек яҡшыртырға")
    }
    val tone = if (rejected) CanonRed else CanonWarn

    Surface(color = tone.copy(alpha = 0.10f), shape = CanonItemShape) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, contentDescription = null, tint = tone, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text(title, color = CanonText, fontWeight = FontWeight.Black, fontSize = 16.sp)
            }
            explanations.forEach { line ->
                Row(verticalAlignment = Alignment.Top) {
                    Text("•  ", color = tone, fontSize = 14.sp, fontWeight = FontWeight.Black)
                    Text(line, color = CanonText, fontSize = 14.sp, lineHeight = 19.sp)
                }
            }
            // Распознанные данные (если есть) — чтобы водитель сверил с реальными правами.
            if (licenseNumber.isNotBlank() || expiry.isNotBlank()) {
                val recog = buildString {
                    if (licenseNumber.isNotBlank()) append(appText("№ прав: ", "права №: ")).append(licenseNumber)
                    if (licenseNumber.isNotBlank() && expiry.isNotBlank()) append("   ·   ")
                    if (expiry.isNotBlank()) append(appText("срок до ", "ваҡыты ")).append(expiry)
                }
                Text(
                    appText("Мы распознали: ", "Таныныҡ: ") + recog,
                    color = CanonMuted, fontSize = 13.sp, lineHeight = 18.sp
                )
            }
            Text(
                appText("Проверь данные и фото, затем отправь снова.", "Мәғлүмәт менән фотоны тикшереп, ҡабат ебәр."),
                color = CanonMuted, fontSize = 13.sp, lineHeight = 18.sp
            )
        }
    }
}

// Инлайн-ошибка отправки заявки (в дополнение к Toast) — не теряется, если Toast пропустили.
@Composable
internal fun SubmitErrorBanner() {
    Surface(color = CanonRed.copy(alpha = 0.10f), shape = CanonItemShape) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Info, contentDescription = null, tint = CanonRed, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(appText("Не отправилось", "Ебәрелмәне"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 16.sp)
                Text(
                    appText("Проверь интернет и нажми «Отправить на проверку» ещё раз.", "Интернетты тикшереп, «Тикшереүгә ебәреү»гә тағы баҫ."),
                    color = CanonMuted, fontSize = 13.sp, lineHeight = 18.sp
                )
            }
        }
    }
}

@Composable
internal fun UploadTile(title: String, done: Boolean, loading: Boolean, onClick: () -> Unit) {
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
internal fun DocumentRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, status: String, loaded: Boolean) {
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
