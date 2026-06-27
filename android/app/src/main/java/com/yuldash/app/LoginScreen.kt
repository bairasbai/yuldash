package com.yuldash.app

// Экран входа (LoginScreen + BrandHero/TrustCard/LoginFormCard/SafetyFooter).
// Вынесено из MainActivity.kt (Фаза 1 разрезки). Импорты скопированы целиком — лишние = варнинги.

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
internal fun LoginScreen(
    currentLanguage: AppLanguage,
    onToggleLanguage: () -> Unit,
    onContinue: () -> Unit,
    onTelegramLogin: () -> Unit = {},
    onVKLogin: () -> Unit = {},
    onWhatsAppLogin: () -> Unit = {}
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = CanonBg
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            contentPadding = PaddingValues(bottom = 28.dp)
        ) {
            item {
                Column {
                    BrandHero(
                        currentLanguage = currentLanguage,
                        onToggleLanguage = onToggleLanguage,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(360.dp)
                    )
                    LoginFormCard(
                        currentLanguage = currentLanguage,
                        onContinue = onContinue,
                        onTelegramLogin = onTelegramLogin,
                        onVKLogin = onVKLogin,
                        onWhatsAppLogin = onWhatsAppLogin,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp)
                            .offset(y = (-44).dp)
                    )
                }
            }
            item {
                TrustCard(
                    currentLanguage = currentLanguage,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                )
            }
            item {
                SafetyFooter(
                    currentLanguage = currentLanguage,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                )
            }
        }
    }
}

@Composable
private fun LoginFormCard(
    currentLanguage: AppLanguage,
    onContinue: () -> Unit,
    onTelegramLogin: () -> Unit = {},
    onVKLogin: () -> Unit = {},
    onWhatsAppLogin: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(0) }            // 0 — ввод телефона, 1 — ввод кода
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showPhone by remember { mutableStateOf(false) }   // SMS-форма (заморожена) раскрывается по тапу
    var tgMode by remember { mutableStateOf(false) }      // true — ждём ввод кода из Telegram
    var tgRequestId by remember { mutableStateOf("") }
    val context = LocalContext.current

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            Text(
                text = appTextFor(currentLanguage, "Войти в Юлдаш", "Юлдашҡа инеү"),
                color = CanonText,
                fontSize = 24.sp,
                lineHeight = 28.sp,
                fontWeight = FontWeight.Black
            )
            if (tgMode) {
                // --- Ввод 4-значного кода, который бот прислал в Telegram ---
                val errEnterTgCode = appTextFor(currentLanguage, "Введите код из Telegram", "Telegram кодын индерегеҙ")
                val errBadTgCode = appTextFor(currentLanguage, "Неверный код. Проверь и введи снова.", "Код дөрөҫ түгел. Тикшереп, ҡабат индер.")
                val errExpiredCode = appTextFor(currentLanguage, "Код истёк. Получи новый — открой Telegram ещё раз.", "Код ваҡыты бөттө. Яңыһын ал — Telegram'ды тағы ас.")
                val errTooManyCode = appTextFor(currentLanguage, "Слишком много попыток. Получи новый код.", "Бик күп омтылыш. Яңы код ал.")
                Text(
                    text = appTextFor(currentLanguage, "Открой Telegram, нажми «Старт» — бот пришлёт код. Введи его сюда.", "Telegram'ды ас, «Старт» баҫ — бот код ебәрер. Шуны индер."),
                    color = CanonMuted, fontSize = 16.sp, lineHeight = 22.sp
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.filter { c -> c.isDigit() }.take(4); error = null },
                    placeholder = { Text(appTextFor(currentLanguage, "Код из Telegram", "Telegram коды"), fontSize = 16.sp) },
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = CanonMuted) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth().height(58.dp),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )
                error?.let { Text(it, color = CanonRed, fontSize = 14.sp, lineHeight = 19.sp) }
                Button(
                    onClick = {
                        if (loading) return@Button
                        if (code.length < 4) { error = errEnterTgCode; return@Button }
                        loading = true; error = null
                        scope.launch {
                            ApiClient.tgVerify(tgRequestId, code.trim())
                                .onSuccess { loading = false; onContinue() }
                                .onFailure { e ->
                                    loading = false
                                    error = when ((e as? ApiException)?.status) {
                                        410 -> errExpiredCode          // код истёк
                                        429 -> errTooManyCode          // много попыток
                                        else -> errBadTgCode           // неверный код
                                    }
                                }
                        }
                    },
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)
                ) {
                    if (loading) CircularProgressIndicator(modifier = Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                    else Text(appTextFor(currentLanguage, "Войти", "Инеү"), fontWeight = FontWeight.Black, fontSize = 16.sp)
                }
                TextButton(onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/${BuildConfig.TELEGRAM_BOT}?start=$tgRequestId")).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
                    }
                }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(appTextFor(currentLanguage, "Открыть Telegram ещё раз", "Telegram'ды тағы асырға"), color = CanonGreen2, fontSize = 14.sp)
                }
                TextButton(onClick = { tgMode = false; code = ""; error = null }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(appTextFor(currentLanguage, "Назад", "Кире"), color = CanonMuted, fontSize = 14.sp)
                }
            } else {
            Text(
                text = appTextFor(currentLanguage, "Быстрый вход — выбери мессенджер", "Тиҙ инеү — мессенджер һайла"),
                color = CanonMuted,
                fontSize = 16.sp,
                lineHeight = 22.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            // Telegram — рабочий вход (бот шлёт 4-значный код). VK/WhatsApp — «скоро».
            val errTgStart = appTextFor(currentLanguage, "Не удалось начать вход. Повтори.", "Инеүҙе башлап булманы. Ҡабатла.")
            val tgSoon = appTextFor(currentLanguage, "Вход через Telegram скоро", "Telegram аша инеү тиҙҙән")
            Button(
                onClick = {
                    if (loading) return@Button
                    if (BuildConfig.TELEGRAM_BOT.isBlank()) { error = tgSoon; return@Button }
                    loading = true; error = null
                    scope.launch {
                        ApiClient.tgStart()
                            .onSuccess { req ->
                                loading = false
                                tgRequestId = req
                                code = ""
                                tgMode = true
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/${BuildConfig.TELEGRAM_BOT}?start=$req")).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
                                }
                            }
                            .onFailure { loading = false; error = errTgStart }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0088CC))
            ) {
                Text(appTextFor(currentLanguage, "Вход через Telegram", "Telegram аша инеү"), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = onVKLogin,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0077FF))
            ) {
                Text(appTextFor(currentLanguage, "Вход через VK", "VK аша инеү"), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = onWhatsAppLogin,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366))
            ) {
                Text(appTextFor(currentLanguage, "Вход через WhatsApp", "WhatsApp аша инеү"), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            // SMS-вход ЗАМОРОЖЕН (нет юр.лица для sms.ru). Форма цела — видна только при SMS_LOGIN_ENABLED.
            if (BuildConfig.SMS_LOGIN_ENABLED) {
            Spacer(modifier = Modifier.height(6.dp))
            TextButton(onClick = { showPhone = !showPhone }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(appTextFor(currentLanguage, "Войти по номеру телефона", "Телефон номеры аша инеү"), color = CanonGreen2, fontSize = 14.sp)
            }
            if (showPhone) {
            Text(
                text = if (step == 0) appTextFor(currentLanguage, "Номер будет скрыт до подтверждения брони.", "Телефон номеры бронь раҫланғанға тиклем йәшерелә.")
                else appTextFor(currentLanguage, "Код отправлен на $phone", "Код $phone номерыңа ебәрелде"),
                color = CanonMuted,
                fontSize = 16.sp,
                lineHeight = 22.sp
            )
            if (step == 0) {
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it; error = null },
                    placeholder = { Text(appTextFor(currentLanguage, "Номер телефона", "Телефон номеры"), fontSize = 16.sp) },
                    leadingIcon = {
                        Icon(Icons.Default.PhoneLocked, contentDescription = null, tint = CanonMuted)
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )
            } else {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.filter { c -> c.isDigit() }.take(6); error = null },
                    placeholder = { Text(appTextFor(currentLanguage, "Код из SMS", "SMS коды"), fontSize = 16.sp) },
                    leadingIcon = {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = CanonMuted)
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )
                TextButton(onClick = { step = 0; code = ""; error = null }) {
                    Text(appTextFor(currentLanguage, "Изменить номер", "Номерҙы үҙгәртеү"), color = CanonGreen2)
                }
            }
            error?.let {
                Text(it, color = CanonRed, fontSize = 14.sp, lineHeight = 19.sp)
            }
            // строки ошибок считаем здесь (в @Composable-контексте); в onClick отдаём готовый текст
            val errEnterPhone = appTextFor(currentLanguage, "Введите номер телефона", "Телефон номерын индерегеҙ")
            val errSendFail = appTextFor(currentLanguage, "Не получилось отправить код. Повтори.", "Код ебәреп булманы. Ҡабатла.")
            val errEnterCode = appTextFor(currentLanguage, "Введите код из SMS", "SMS кодын индерегеҙ")
            val errBadCode = appTextFor(currentLanguage, "Неверный код", "Код дөрөҫ түгел")
            Button(
                onClick = {
                    if (loading) return@Button
                    error = null
                    if (step == 0) {
                        val p = phone.trim()
                        if (p.length < 5) {
                            error = errEnterPhone
                            return@Button
                        }
                        loading = true
                        scope.launch {
                            ApiClient.requestCode(p)
                                .onSuccess { loading = false; step = 1 }
                                .onFailure {
                                    loading = false
                                    error = errSendFail
                                }
                        }
                    } else {
                        if (code.length < 4) {
                            error = errEnterCode
                            return@Button
                        }
                        loading = true
                        scope.launch {
                            ApiClient.verifyCode(phone.trim(), code.trim(), "")
                                .onSuccess { loading = false; onContinue() }
                                .onFailure {
                                    loading = false
                                    error = errBadCode
                                }
                        }
                    }
                },
                enabled = !loading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)
            ) {
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Text(
                        text = if (step == 0) appTextFor(currentLanguage, "Получить код", "Код алыу") else appTextFor(currentLanguage, "Войти", "Инеү"),
                        fontWeight = FontWeight.Black,
                        fontSize = 16.sp
                    )
                }
            }
            }   // конец if (showPhone)
            }   // конец if (BuildConfig.SMS_LOGIN_ENABLED) — SMS-вход заморожен
            }   // конец else (tgMode == false) — экран выбора входа
        }
    }
}

@Composable
private fun BrandHero(
    currentLanguage: AppLanguage,
    onToggleLanguage: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(bottomStart = 34.dp, bottomEnd = 34.dp))
            .background(Color(0xFF0B6B3A))
    ) {
        Image(
            painter = painterResource(R.drawable.login_car_hero_square),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color(0x99175F3F),
                        0.44f to Color(0x33175F3F),
                        0.72f to Color(0x2206130F),
                        1f to Color(0xE606130F)
                    )
                )
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Color(0x66105239),
                        0.55f to Color.Transparent,
                        1f to Color(0x3306130F)
                    )
                )
        )

        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 16.dp, end = 24.dp)
                .clip(RoundedCornerShape(999.dp))
                .clickable(onClick = onToggleLanguage)
                .background(Color.White.copy(alpha = 0.16f))
                .border(1.dp, Color.White.copy(alpha = 0.30f), RoundedCornerShape(999.dp))
                .padding(horizontal = 15.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Default.Language, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            Text(
                text = if (currentLanguage == AppLanguage.Ba) "БАШ" else "РУС",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = Color.White, modifier = Modifier.size(17.dp))
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(top = 52.dp, start = 28.dp, end = 22.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(68.dp),
                    shape = CircleShape,
                    color = Color.White,
                    shadowElevation = 6.dp
                ) {
                    Image(
                        painter = painterResource(R.drawable.yuldash_logo),
                        contentDescription = null,
                        modifier = Modifier.padding(8.dp),
                        contentScale = ContentScale.Fit
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Юлдаш", color = Color.White, fontSize = 40.sp, lineHeight = 42.sp, fontWeight = FontWeight.Black)
                    Text(
                        text = appTextFor(currentLanguage, "Поездки между своими", "Үҙебеҙҙекеләр араһында юллашыу"),
                        color = Color.White.copy(alpha = 0.94f),
                        fontSize = 18.sp,
                        lineHeight = 22.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HeroPill(Icons.Default.PhoneLocked, appTextFor(currentLanguage, "Скрытый номер", "Йәшерен номер"))
                HeroPill(Icons.Default.Pin, appTextFor(currentLanguage, "Код посадки", "Ултырыу коды"))
            }
        }

    }
}

@Composable
private fun HeroPill(icon: ImageVector, text: String) {
    Surface(
        color = Color.White.copy(alpha = 0.16f),
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.34f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            Text(
                text = text,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun TrustCard(currentLanguage: AppLanguage, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = RoundedCornerShape(22.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            TrustRow(
                icon = Icons.Default.PhoneLocked,
                title = appTextFor(currentLanguage, "Телефон скрыт", "Телефон йәшерелгән"),
                subtitle = appTextFor(currentLanguage, "До подтверждения брони", "Бронь раҫланғанға тиклем")
            )
            TrustDivider()
            TrustRow(
                icon = Icons.Default.Shield,
                title = appTextFor(currentLanguage, "Код посадки", "Ултырыу коды"),
                subtitle = appTextFor(currentLanguage, "Для вашей безопасности", "Һеҙҙең хәүефһеҙлек өсөн")
            )
            TrustDivider()
            TrustRow(
                icon = Icons.Default.Verified,
                title = appTextFor(currentLanguage, "Проверка водителя и машины", "Водитель һәм машинаны тикшереү"),
                subtitle = appTextFor(currentLanguage, "Каждый водитель проходит проверку", "Һәр водитель тикшереү үтә")
            )
        }
    }
}

@Composable
private fun TrustDivider() {
    Box(
        Modifier
            .padding(start = 72.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(CanonBorder)
    )
}

@Composable
private fun TrustRow(icon: ImageVector, title: String, subtitle: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 66.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(48.dp),
            color = CanonMint,
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(12.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = CanonText, fontSize = 17.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = CanonMuted, fontSize = 14.sp, lineHeight = 18.sp)
        }
        Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = CanonMuted, modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun SafetyFooter(currentLanguage: AppLanguage, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Shield, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(19.dp))
            Text(
                text = appTextFor(currentLanguage, "Безопасность поездок — наш приоритет", "Сәфәр хәүефһеҙлеге — беҙҙең өҫтөнлөк"),
                color = CanonGreen2,
                fontSize = 14.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center
            )
        }
        Text(
            text = appTextFor(currentLanguage, "Юлдаш заботится о вас", "Юлдаш һеҙҙең хаҡта хәстәрләй"),
            color = CanonMuted,
            fontSize = 13.sp,
            lineHeight = 17.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
        )
    }
}
