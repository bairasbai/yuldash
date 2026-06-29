package com.yuldash.app

// Экраны доступности/семьи: простой режим, голосовая заявка, заявка пассажира/за близкого,
// доверенные контакты, повтор маршрута, обратный звонок. Вынесено из MainActivity (Фаза 1).
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
internal fun SimpleModeScreen(
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
    val latest3 = remember(latestRequests) { latestRequests.take(3) }
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
                items(latest3, key = { it.route + it.time + it.passenger }) { request ->
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
        colors = CardDefaults.cardColors(containerColor = if (danger) CanonDangerBg else CanonSurface),
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
            modifier = Modifier.heightIn(min = 64.dp).padding(vertical = 14.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(26.dp))
            Spacer(Modifier.height(6.dp))
            Text(title, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// Поле адреса с автоподсказкой через Яндекс.Геокодер.
@Composable
internal fun AddressSuggestField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    leadingIcon: ImageVector
) {
    var hits by remember { mutableStateOf<List<GeoHit>>(emptyList()) }
    var picked by remember { mutableStateOf(true) }   // не подсказывать для предзаполненных значений при открытии
    LaunchedEffect(value) {
        if (picked) { picked = false; return@LaunchedEffect }
        if (value.trim().length < 2) { hits = emptyList(); return@LaunchedEffect }
        delay(350)
        hits = GeocoderClient.suggest(value)
    }
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            leadingIcon = { Icon(leadingIcon, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            singleLine = true
        )
        if (hits.isNotEmpty()) {
            Surface(
                color = CanonSurface,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, CanonBorder),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            ) {
                Column {
                    hits.forEach { hit ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { picked = true; onValueChange(hit.title); hits = emptyList() }
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.LocationOn, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(hit.title, color = CanonText, fontSize = 14.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
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
            request.voiceUrl?.let { url -> VoiceRequestPlayRow(url) }
        }
    }
}

@Composable
private fun VoiceRequestPlayRow(url: String) {
    var playing by remember { mutableStateOf(false) }
    val player = remember { mutableStateOf<MediaPlayer?>(null) }
    DisposableEffect(url) { onDispose { runCatching { player.value?.release() }; player.value = null } }
    Surface(color = CanonMint, shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = {
                    if (playing) {
                        runCatching { player.value?.stop(); player.value?.release() }; player.value = null; playing = false
                    } else runCatching {
                        player.value = MediaPlayer().apply {
                            setDataSource(url)
                            setOnPreparedListener { it.start() }
                            setOnCompletionListener { playing = false; runCatching { release() }; player.value = null }
                            prepareAsync()
                        }
                        playing = true
                    }
                },
                modifier = Modifier.size(34.dp)
            ) {
                Icon(if (playing) Icons.Default.Close else Icons.Default.PlayArrow, contentDescription = appText("Слушать заявку", "Заявканы тыңлау"), tint = CanonGreen2)
            }
            Text(appText("Голосовая заявка", "Тауыш заявкаһы"), color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
}

@Composable
internal fun VoiceRequestScreen(
    contacts: List<TrustedContact>,
    onBack: () -> Unit,
    onCreateRequest: (LocalRequest) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recorder = remember { VoiceRecorder(context) }
    var recording by remember { mutableStateOf(false) }
    var startMs by remember { mutableStateOf(0L) }
    var recordedPath by remember { mutableStateOf<String?>(null) }
    var recordedDur by remember { mutableStateOf(0) }
    var uploading by remember { mutableStateOf(false) }
    var submittingText by remember { mutableStateOf(false) }
    val trusted = contacts.firstOrNull()
    val voiceRequestStatus = appText("ищем водителя", "водитель эҙләйбеҙ")
    val vrTitle = appText("Голосовая заявка", "Тауыш заявкаһы")
    val vrRoute = appText("Голосом — водитель слушает", "Тауыш менән — водитель тыңлай")
    val vrNow = appText("сейчас", "хәҙер")
    val vrPrompt = appText("Скажите маршрут", "Маршрутты әйтегеҙ")
    val vrNoStt = appText("Распознавание недоступно на устройстве", "Таныу ҡорамалда юҡ")
    val vrSendError = appText("Не получилось отправить. Проверь сеть и повтори.", "Ебәреп булманы. Интернетте тикшереп ҡабатла.")
    val vrUploadError = appText("Не удалось загрузить запись. Проверь сеть и повтори.", "Яҙманы тейәп булманы. Интернетте тикшереп ҡабатла.")
    fun begin() { if (recorder.start()) { recording = true; startMs = SystemClock.elapsedRealtime() } }
    fun finish() {
        recordedPath = recorder.stop()
        recordedDur = ((SystemClock.elapsedRealtime() - startMs) / 1000).toInt().coerceAtLeast(1)
        recording = false
    }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) begin() }
    var recognizedText by remember { mutableStateOf<String?>(null) }
    val sttLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val t = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!t.isNullOrBlank()) recognizedText = t
        }
    }
    fun recognizeRu() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PROMPT, vrPrompt)
        }
        runCatching { sttLauncher.launch(intent) }.onFailure { Toast.makeText(context, vrNoStt, Toast.LENGTH_SHORT).show() }
    }
    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Голосовая заявка", "Тауыш заявкаһы"), onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                InfoCard(
                    title = appText("Нажмите и скажите", "Баҫығыҙ һәм әйтегеҙ"),
                    text = appText("Скажите голосом: откуда, куда и когда. Водитель послушает — на русском или башкирском.", "Тауыш менән әйтегеҙ: ҡайҙан, ҡайҙа, ҡасан. Водитель тыңлар — урыҫса йәки башҡортса."),
                    icon = Icons.Default.VolumeUp
                )
            }
            item {
                Button(
                    onClick = {
                        when {
                            recording -> finish()
                            recordedPath != null -> recordedPath = null
                            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> begin()
                            else -> perm.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(78.dp),
                    shape = RoundedCornerShape(22.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (recording) CanonRed else CanonGreen2)
                ) {
                    Icon(Icons.Default.HeadsetMic, contentDescription = null, modifier = Modifier.size(30.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (recording) appText("Стоп — готово", "Туҡта — әҙер") else if (recordedPath != null) appText("Записать заново", "Ҡабат яҙыу") else appText("Сказать заявку", "Заявканы әйтеү"),
                        fontWeight = FontWeight.Black, fontSize = 20.sp
                    )
                }
            }
            item {
                OutlinedButton(
                    onClick = { recognizeRu() },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Icon(Icons.Default.HeadsetMic, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(appText("Сказать на русском (текстом)", "Урыҫса әйтеү (текст)"), fontWeight = FontWeight.Bold)
                }
            }
            recognizedText?.let { text ->
                item { VoiceParsedCard(title = appText("Распознано", "Танылды"), lines = listOf(text)) }
                item {
                    AppButton(
                        text = appText("Создать заявку", "Заявка булдырыу"),
                        loading = submittingText,
                        onClick = {
                            // Разбор «откуда → куда» как в fireRequestFromRoute, но ждём ответ сервера.
                            val parts = text.split("→", "->", "-").map { it.trim() }.filter { it.isNotEmpty() }
                            val from = parts.getOrElse(0) { text.trim() }
                            val to = parts.getOrElse(1) { "" }
                            submittingText = true
                            scope.launch {
                                ApiClient.createRequest(from, to, 1, "regular", false, "", 0, transcript = text, assisted = true)
                                    .onSuccess {
                                        onCreateRequest(LocalRequest(title = vrTitle, route = text, time = vrNow, passenger = (ApiClient.cachedName() ?: "Я"), status = voiceRequestStatus, trustedContact = trusted?.name))
                                    }
                                    .onFailure {
                                        submittingText = false
                                        Toast.makeText(context, vrSendError, Toast.LENGTH_LONG).show()
                                    }
                            }
                        }
                    )
                }
            }
            recordedPath?.let { path ->
                item {
                    VoiceMessageCard(LocalVoiceMessage(appText("Вы", "Һеҙ"), "", appText("сейчас", "хәҙер"), audioPath = path, durationSec = recordedDur))
                }
                item {
                    AppButton(
                        text = if (uploading) appText("Отправка…", "Ебәрелә…") else appText("Создать голосовую заявку", "Тауыш заявкаһын булдырыу"),
                        onClick = {
                            uploading = true
                            scope.launch {
                                // Голос обязателен для голосовой заявки: загрузку не «проглатываем» — при ошибке
                                // показываем сообщение и сбрасываем флаг, не уводим экран как при успехе.
                                val bytes = runCatching { File(path).readBytes() }.getOrNull()
                                val url = if (bytes != null) ApiClient.uploadVoice(bytes).getOrNull() else null
                                if (url == null) {
                                    uploading = false
                                    Toast.makeText(context, vrUploadError, Toast.LENGTH_LONG).show()
                                    return@launch
                                }
                                ApiClient.createRequest(vrRoute, "", 1, "regular", false, "", 0, voiceUrl = url, transcript = recognizedText, assisted = true)
                                    .onSuccess {
                                        onCreateRequest(
                                            LocalRequest(
                                                title = vrTitle,
                                                route = vrRoute,
                                                time = vrNow,
                                                passenger = (ApiClient.cachedName() ?: "Я"),
                                                status = voiceRequestStatus,
                                                trustedContact = trusted?.name,
                                                voiceUrl = url
                                            )
                                        )
                                    }
                                    .onFailure {
                                        uploading = false
                                        Toast.makeText(context, vrSendError, Toast.LENGTH_LONG).show()
                                    }
                            }
                        },
                        loading = uploading
                    )
                }
            }
        }
    }
}

@Composable
internal fun CreatePassengerRequestScreen(
    onBack: () -> Unit,
    onCreateRequest: (LocalRequest) -> Unit
) {
    var from by remember { mutableStateOf("Баймаҡ") }
    var to by remember { mutableStateOf("Сибай") }
    var time by remember { mutableStateOf("") }
    var seats by remember { mutableStateOf("1") }
    var category by remember { mutableStateOf("regular") }
    var price by remember { mutableStateOf("350") }
    var comment by remember { mutableStateOf("") }
    val categories = listOf(
        "regular" to LocalizedText("Обычная", "Ғәҙәти"),
        "urgent" to LocalizedText("Срочно", "Ашығыс"),
        "parcel" to LocalizedText("Посылка", "Посылка"),
        "cargo" to LocalizedText("Груз", "Йөк"),
        "kids" to LocalizedText("С детьми", "Балалар менән")
    )
    val selectedCategoryText = categories.firstOrNull { it.first == category }?.second?.text() ?: categories.first().second.text()
    val waitingStatus = appText("ждём отклики", "яуаптар көтәбеҙ")
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sendError = appText("Не получилось отправить. Проверь сеть и повтори.", "Ебәреп булманы. Интернетте тикшереп ҡабатла.")
    var submitting by remember { mutableStateOf(false) }

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
            item { AddressSuggestField(from, { from = it }, appText("Откуда", "Ҡайҙан"), Icons.Default.LocationOn) }
            item { AddressSuggestField(to, { to = it }, appText("Куда", "Ҡайҙа"), Icons.Default.NearMe) }
            item {
                val ctxDt = LocalContext.current
                // Нативный календарь Android: башкирской локали (ba) в системе нет → русский для обоих языков (вместо англ.).
                val dtLocale = "ru"
                Box {
                    OutlinedTextField(
                        value = time,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(appText("Дата и время", "Дата һәм ваҡыт")) },
                        placeholder = { Text(appText("Выберите дату и время", "Дата һәм ваҡыт һайлағыҙ")) },
                        trailingIcon = { Icon(Icons.Default.CalendarMonth, contentDescription = appText("Выбрать дату", "Дата һайлау"), tint = CanonGreen2) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    )
                    Box(Modifier.matchParentSize().clickable { openDateTimePicker(ctxDt, dtLocale) { time = it } })
                }
            }
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
                    items(categories, key = { it.first }) { (key, label) ->
                        val labelText = label.text()
                        if (category == key) {
                            Button(
                                onClick = { category = key },
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)
                            ) { Text(labelText, fontWeight = FontWeight.Bold) }
                        } else {
                            OutlinedButton(
                                onClick = { category = key },
                                shape = RoundedCornerShape(16.dp)
                            ) { Text(labelText, fontWeight = FontWeight.Bold) }
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
                        "$time · $seats ${appText("место", "урын")} · $selectedCategoryText",
                        appText("Готовая сумма: $price ₽", "Әҙер сумма: $price ₽")
                    )
                )
            }
            item {
                AppButton(
                    text = appText("Создать заявку", "Заявка булдырыу"),
                    loading = submitting,
                    onClick = {
                        val (apiCat, withKids) = when (category) {
                            "urgent" -> "urgent" to false
                            "parcel" -> "parcel" to false
                            "cargo" -> "cargo" to false
                            "kids" -> "regular" to true
                            else -> "regular" to false
                        }
                        val priceVal = price.toIntOrNull() ?: 0
                        submitting = true
                        scope.launch {
                            // Ждём ответ сервера: «создано» показываем только при реальном успехе POST.
                            ApiClient.createRequest(
                                from.trim(), to.trim(),
                                seats.toIntOrNull() ?: 1,
                                apiCat, withKids, comment.trim(), priceVal,
                                assisted = true,   // заявка за близкого → уведомить админа
                            )
                                .onSuccess {
                                    onCreateRequest(
                                        LocalRequest(
                                            title = selectedCategoryText,
                                            route = "$from → $to",
                                            time = time,
                                            passenger = (ApiClient.cachedName() ?: "Я"),
                                            status = waitingStatus,
                                            price = priceVal,
                                            trustedContact = comment.ifBlank { null }
                                        )
                                    )
                                }
                                .onFailure {
                                    submitting = false
                                    Toast.makeText(context, sendError, Toast.LENGTH_LONG).show()
                                }
                        }
                    },
                    enabled = from.isNotBlank() && to.isNotBlank() && time.isNotBlank() && price.isNotBlank() && !submitting
                )
            }
        }
    }
}

@Composable
internal fun FamilyOrderScreen(
    contacts: List<TrustedContact>,
    onBack: () -> Unit,
    onCreateRequest: (LocalRequest) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val defaultPassenger = appText("Мама", "Әсәй")
    val familyRequestTitle = appText("Заказ за близкого", "Яҡын кеше өсөн заказ")
    val familyRequestTime = appText("сегодня после 17:00", "бөгөн 17:00-тан һуң")
    val familyRequestStatus = appText("ждём отклики", "яуаптар көтәбеҙ")
    val commentLabel = appText("Заказ за близкого", "Яҡын кеше өсөн заказ")
    val sendError = appText("Не получилось отправить. Проверь сеть и повтори.", "Ебәреп булманы. Интернетте тикшереп ҡабатла.")
    var passenger by remember { mutableStateOf(defaultPassenger) }
    var phone by remember { mutableStateOf("") }
    var fromCity by remember { mutableStateOf("") }
    var toCity by remember { mutableStateOf("") }
    var notifyContact by remember { mutableStateOf(true) }
    var submitting by remember { mutableStateOf(false) }
    val trusted = contacts.firstOrNull()
    val canSubmit = passenger.isNotBlank() && fromCity.isNotBlank() && toCity.isNotBlank()
    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Заказать за близкого", "Яҡын өсөн заказ"), onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item { Text(appText("Кто поедет?", "Кем бара?"), color = CanonGreen, fontSize = 28.sp, fontWeight = FontWeight.Black) }
            item { OutlinedTextField(value = passenger, onValueChange = { passenger = it }, label = { Text(appText("Имя пассажира", "Пассажир исеме")) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) }
            item { OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text(appText("Телефон пассажира", "Пассажир телефоны")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) }
            item { Text(appText("Маршрут", "Маршрут"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 16.sp) }
            item { AddressSuggestField(fromCity, { fromCity = it }, appText("Откуда", "Ҡайҙан"), Icons.Default.LocationOn) }
            item { AddressSuggestField(toCity, { toCity = it }, appText("Куда", "Ҡайҙа"), Icons.Default.NearMe) }
            item {
                SettingSwitchRow(
                    Icons.Default.Notifications,
                    appText("Уведомлять доверенного", "Ышаныслы кешегә хәбәр итеү"),
                    appText("Статус поездки получит ${trusted?.name ?: "контакт"}", "Сәфәр статусын ${trusted?.name ?: "контакт"} ала"),
                    notifyContact
                ) { notifyContact = it }
            }
            item {
                AppButton(
                    text = appText("Создать заявку", "Заявка булдырыу"),
                    enabled = canSubmit && !submitting,
                    loading = submitting,
                    onClick = {
                        val f = fromCity.trim(); val t = toCity.trim()
                        val comment = if (phone.isBlank()) commentLabel else "$commentLabel · ${phone.trim()}"
                        submitting = true
                        scope.launch {
                            // Реальная серверная заявка: ждём ответ сервера — успех показываем только при удаче.
                            ApiClient.createRequest(f, t, 1, "regular", false, comment, 0, assisted = true, relativeName = passenger.trim())
                                .onSuccess {
                                    onCreateRequest(
                                        LocalRequest(
                                            title = familyRequestTitle,
                                            route = "$f → $t",
                                            time = familyRequestTime,
                                            passenger = passenger,
                                            status = familyRequestStatus,
                                            trustedContact = if (notifyContact) trusted?.name else null
                                        )
                                    )
                                }
                                .onFailure {
                                    submitting = false
                                    Toast.makeText(context, sendError, Toast.LENGTH_LONG).show()
                                }
                        }
                    }
                )
            }
        }
    }
}

@Composable
internal fun TrustedContactsScreen(
    contacts: List<TrustedContact>,
    onBack: () -> Unit,
    onAddContact: (TrustedContact) -> Unit
) {
    var showAdd by remember { mutableStateOf(false) }
    var nm by remember { mutableStateOf("") }
    var rel by remember { mutableStateOf("") }
    var ph by remember { mutableStateOf("") }
    val defaultRel = appText("Контакт", "Контакт")
    // Свой экран сам тянет контакты с сервера: переданный список — затравка/фолбэк, плюс добавленные тут локально.
    var serverContacts by remember { mutableStateOf<List<TrustedContact>>(emptyList()) }
    val locallyAdded = remember { mutableStateListOf<TrustedContact>() }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        ApiClient.getContacts()
            .onSuccess { list -> serverContacts = list.map { c -> TrustedContact(c.name, c.relation, c.phone, c.notifyByDefault, c.id) } }
        loading = false
    }
    // Слияние: затравка → сервер → добавленные локально; дубли убираем по телефону, порядок сохраняем.
    val merged = remember(contacts, serverContacts, locallyAdded.toList()) {
        (contacts + serverContacts + locallyAdded).distinctBy { it.phone }
    }
    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            containerColor = CanonSurface,
            title = { Text(appText("Новый контакт", "Яңы контакт"), color = CanonText, fontWeight = FontWeight.Black) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(nm, { nm = it.take(60) }, label = { Text(appText("Имя", "Исем")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(rel, { rel = it.take(40) }, label = { Text(appText("Кто это (сестра, сын…)", "Кем (һеңле, ул…)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(ph, { ph = it.take(20) }, label = { Text(appText("Телефон", "Телефон")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(enabled = nm.isNotBlank() && ph.isNotBlank(), onClick = {
                    val r = rel.trim().ifBlank { defaultRel }
                    val newContact = TrustedContact(nm.trim(), r, ph.trim(), true, relationBa = r)
                    onAddContact(newContact)
                    // Показать сразу в списке, не дожидаясь обновления родителя/сервера.
                    if (merged.none { it.phone == newContact.phone }) locallyAdded.add(newContact)
                    nm = ""; rel = ""; ph = ""; showAdd = false
                }) { Text(appText("Добавить", "Өҫтәү"), color = CanonGreen2, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text(appText("Отмена", "Баш тартыу"), color = CanonMuted) } },
        )
    }
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
            when {
                loading && merged.isEmpty() -> item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = CanonGreen2)
                    }
                }
                merged.isEmpty() -> item {
                    Card(colors = CardDefaults.cardColors(containerColor = CanonSurface), shape = CanonItemShape, elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.PhoneLocked, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(36.dp))
                            Text(appText("Пока нет контактов", "Әлегә контакттар юҡ"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 17.sp, textAlign = TextAlign.Center)
                            Text(appText("Добавь близкого — он сможет видеть статус твоей поездки.", "Яҡыныңды өҫтә — ул сәфәреңдең статусын күрә алыр."), color = CanonMuted, fontSize = 14.sp, lineHeight = 19.sp, textAlign = TextAlign.Center)
                        }
                    }
                }
                else -> itemsIndexed(merged, key = { _, c -> c.phone }) { index, contact ->
                    Box(Modifier.appearIn(index)) { TrustedContactCard(contact) }
                }
            }
            item {
                AppButton(
                    text = appText("Добавить контакт", "Контакт өҫтәү"),
                    onClick = { showAdd = true }
                )
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
                Text("${contact.relationText()} · ${contact.phone}", color = CanonMuted, fontSize = 13.sp)
            }
            Text(if (contact.notifyByDefault) appText("Статус", "Статус") else appText("Только SOS", "Тик SOS"), color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
    }
}

@Composable
internal fun RepeatTripScreen(
    contacts: List<TrustedContact>,
    onBack: () -> Unit,
    onRepeat: (LocalRequest) -> Unit
) {
    val trusted = contacts.firstOrNull()
    // Частые поездки — из истории юзера (сервер); демо, пока истории нет.
    var frequent by remember { mutableStateOf(demoFrequentTrips) }
    LaunchedEffect(Unit) {
        ApiClient.getMyRoutes().onSuccess { srv ->
            if (srv.isNotEmpty()) frequent = srv.map { s ->
                FrequentTrip(title = "Частая поездка", titleBa = "Йыш сәфәр", from = s.from, to = s.to, timeHint = "", timeHintBa = "", categoryKey = "regular")
            }
        }
    }
    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Повторить поездку", "Сәфәрҙе ҡабатлау"), onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item { Text(appText("Частые поездки", "Йыш сәфәрҙәр"), color = CanonGreen, fontSize = 28.sp, fontWeight = FontWeight.Black) }
            itemsIndexed(frequent, key = { _, t -> t.from + "→" + t.to }) { index, trip ->
                val repeatTitle = appText("Повтор: ${trip.title}", "Ҡабатлау: ${trip.titleBa}")
                val repeatStatus = appText("создана", "булдырылды")
                val repeatTime = trip.timeHintText()
                Box(Modifier.appearIn(index)) {
                FrequentTripCard(trip) {
                    onRepeat(
                        LocalRequest(
                            title = repeatTitle,
                            route = "${trip.from} → ${trip.to}",
                            time = repeatTime,
                            passenger = (ApiClient.cachedName() ?: "Я"),
                            status = repeatStatus,
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
                Icon(if (trip.categoryKey == "hospital") Icons.Default.LocalHospital else Icons.Default.Route, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(12.dp).size(28.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(trip.titleText(), color = CanonText, fontWeight = FontWeight.Black, fontSize = 18.sp)
                Text("${trip.from} → ${trip.to}", color = CanonGreen, fontWeight = FontWeight.Bold)
                Text(trip.timeHintText(), color = CanonMuted, fontSize = 13.sp)
            }
            Icon(Icons.Default.Refresh, contentDescription = null, tint = CanonGreen2)
        }
    }
}

@Composable
internal fun CallbackHelpScreen(requested: Boolean, onBack: () -> Unit, onRequest: (String) -> Unit) {
    val defaultReason = appText("Помогите создать заявку", "Заявка булдырырға ярҙам итегеҙ")
    var reason by remember { mutableStateOf(defaultReason) }
    val context = LocalContext.current
    val supportPhone = BuildConfig.YULDASH_SUPPORT_PHONE
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
                        text = appText("Заявка ушла помощнику Юлдаш. Мы перезвоним — обычно в течение дня.", "Заявка Юлдаш ярҙамсыһына китте. Беҙ шылтыратырбыҙ — ғәҙәттә көн эсендә."),
                        icon = Icons.Default.CheckCircle
                    )
                }
            }
            item {
                Button(
                    onClick = {
                        if (supportPhone.isNotBlank()) {
                            runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:$supportPhone"))) }
                        } else onRequest(reason)
                    },
                    modifier = Modifier.fillMaxWidth().height(58.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)
                ) {
                    if (supportPhone.isNotBlank()) {
                        Icon(Icons.Default.HeadsetMic, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        if (supportPhone.isNotBlank()) appText("Позвонить в поддержку", "Ярҙамға шылтыратыу") else appText("Попросить звонок", "Шылтыратыу һорау"),
                        fontWeight = FontWeight.Black, fontSize = 17.sp
                    )
                }
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

