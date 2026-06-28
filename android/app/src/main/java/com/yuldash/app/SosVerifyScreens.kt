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

// Описание одной экстренной службы для ползунков.
private data class SosService(
    val key: String,
    val number: String,        // прямой номер для звонка с мобильного
    val label: LocalizedText,
    val icon: ImageVector,
    val sosCategory: String     // что шлём «своим» в наш бэкенд
)

@Composable
internal fun SosScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    // Россия, звонок с мобильного: 102 полиция · 101 пожарные · 103 скорая. 112 — единый (по умолчанию / когда выбрано несколько).
    val services = remember {
        listOf(
            SosService("police", "102", LocalizedText("Полиция", "Полиция"), Icons.Default.LocalPolice, "other"),
            SosService("fire", "101", LocalizedText("Пожарные", "Янғын һүндереүселәр"), Icons.Default.LocalFireDepartment, "breakdown"),
            SosService("ambulance", "103", LocalizedText("Скорая", "Тиҙ ярҙам"), Icons.Default.LocalHospital, "medical")
        )
    }
    val on = remember { mutableStateListOf(false, false, false) }
    var description by remember { mutableStateOf("") }

    // Строки для Toast (вне Composable-контекста лямбд) — считаем заранее.
    val tCopied = appText("Скопировано", "Күсерелде")
    val tCallFail = appText("Не удалось открыть звонок", "Шылтырауҙы аса алманыҡ")

    // Состояния второго канала — сигнал «своим» водителям через бэкенд.
    var sent by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    // Какой номер набрать: ничего/несколько → 112 (единый), ровно один → прямой номер службы.
    val selectedIdx = on.indexOfFirst { it }.takeIf { on.count { v -> v } == 1 }
    val dialNumber = if (selectedIdx != null) services[selectedIdx].number else "112"

    // Координаты — берём уже кешированную геопозицию (если включена), показываем для диктовки оператору.
    val lat = LocationPrefs.lastLat
    val lng = LocationPrefs.lastLng
    val coordsText = if (lat != null && lng != null) String.format("%.5f, %.5f", lat, lng) else null

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
                            Text(appText("Выбери службу — откроется звонок с твоего номера.", "Хеҙмәтте һайла — үҙ номерыңдан шылтырау асыла."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // Ползунки служб.
            itemsIndexed(services) { i, svc ->
                SosServiceToggle(
                    title = svc.label.text(),
                    number = svc.number,
                    icon = svc.icon,
                    checked = on[i],
                    onCheckedChange = { on[i] = it }
                )
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

            // Что продиктовать оператору: текст + координаты (в звонок их вложить нельзя — даём скопировать/прочитать).
            if (description.isNotBlank() || coordsText != null) {
                item {
                    Surface(color = CanonMint, shape = CanonItemShape) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(appText("Продиктуй оператору", "Операторға әйт"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 15.sp)
                            if (description.isNotBlank()) Text(description.trim(), color = CanonText, fontSize = 15.sp, lineHeight = 20.sp)
                            if (coordsText != null) Text(appText("Координаты: ", "Координаталар: ") + coordsText, color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            OutlinedButton(
                                onClick = {
                                    clipboard.setText(AnnotatedString(dictText()))
                                    Toast.makeText(context, tCopied, Toast.LENGTH_SHORT).show()
                                },
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

            // 🟥 Главная кнопка — звонок в госслужбу.
            item {
                Button(
                    onClick = { dial(dialNumber) },
                    modifier = Modifier.fillMaxWidth().height(60.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CanonRed)
                ) {
                    Icon(Icons.Default.Call, contentDescription = null, tint = Color.White)
                    Spacer(Modifier.width(10.dp))
                    Text(appText("Позвонить $dialNumber", "$dialNumber — шылтыратыу"), color = Color.White, fontWeight = FontWeight.Black, fontSize = 18.sp)
                }
            }
            item {
                Text(
                    appText("Звонок идёт с твоего номера. 112 — единый номер всех служб.", "Шылтырау үҙ номерыңдан бара. 112 — бөтә хеҙмәттәрҙең уртаҡ номеры."),
                    color = CanonMuted, fontSize = 13.sp, lineHeight = 18.sp
                )
            }

            // 🟧 Второй канал — сигнал «своим» водителям рядом (наш бэкенд).
            item { Spacer(Modifier.height(4.dp)) }
            item {
                Text(appText("Или сигнал своим", "Йәки үҙебеҙҙекеләргә сигнал"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 17.sp)
            }
            if (sent) {
                item {
                    InfoCard(
                        title = appText("Сигнал отправлен", "Сигнал ебәрелде"),
                        text = appText("Ближайшие водители увидят твой SOS. Статус: ожидание отклика.", "Яҡындағы водителдәр SOS-ыңды күрер. Статус: яуап көтөү."),
                        icon = Icons.Default.Sos
                    )
                }
            }
            if (failed) {
                item {
                    InfoCard(
                        title = appText("Сигнал не отправлен", "Сигнал ебәрелмәне"),
                        text = appText("Похоже, нет сети. Проверь связь и нажми ещё раз.", "Бәйләнеш юҡ кеүек. Тикшереп, тағы баҫ."),
                        icon = Icons.Default.Sos
                    )
                }
            }
            item {
                AppButton(
                    text = if (sending) appText("Отправляем…", "Ебәрәбеҙ…") else appText("Сигнал водителям рядом", "Яҡындағы водителдәргә сигнал"),
                    onClick = {
                        if (sending) return@AppButton
                        failed = false
                        sent = false
                        sending = true
                        // Категория «своим»: берём выбранную службу (если одна), иначе общий note со всеми выбранными.
                        val chosen = services.filterIndexed { i, _ -> on[i] }
                        val cat = chosen.singleOrNull()?.sosCategory ?: "other"
                        val note = buildString {
                            if (chosen.isNotEmpty()) append("Службы: " + chosen.joinToString(", ") { it.label.ru } + ". ")
                            if (description.isNotBlank()) append(description.trim())
                        }.ifBlank { "SOS" }
                        scope.launch {
                            val r = ApiClient.sos(cat, note)   // ждём сервер, НЕ fire-and-forget (кнопка безопасности)
                            sending = false
                            if (r.isSuccess) sent = true else failed = true
                        }
                    },
                    style = AppButtonStyle.Danger,
                    loading = sending
                )
            }

            item {
                Text(
                    appText("Ложный вызов экстренных служб наказуем по закону.", "Ялған ашығыс саҡырыу закон буйынса язаға тарттырыла."),
                    color = CanonMuted, fontSize = 12.sp, lineHeight = 16.sp
                )
            }
            item { TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(appText("Назад", "Кире")) } }
        }
    }
}

@Composable
private fun SosServiceToggle(
    title: String,
    number: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val borderColor by animateColorAsState(if (checked) CanonRed else CanonBorder, label = "sosToggleBorder")
    Surface(
        color = if (checked) CanonDangerBg else CanonSurface,
        shape = CanonItemShape,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) }
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = if (checked) CanonRed else CanonMuted, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(number, color = CanonMuted, fontSize = 13.sp)
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(checkedTrackColor = CanonRed, checkedThumbColor = Color.White)
            )
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
