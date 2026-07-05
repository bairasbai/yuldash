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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.filled.People
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
import androidx.compose.ui.layout.layout
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

// ─────────────────────────── Валидация входа (чистые функции, без Compose) ───────────────────────────
// Вынесены из onClick в отдельные функции → тестируются на JVM напрямую (без рендера) и переиспользуются
// формой (кнопка «disabled» при невалиде). Правила ровно те же, что были инлайном в LoginFormCard.

/** Телефон валиден для отправки кода: после trim осталось ≥ 5 символов (как было: `phone.trim().length < 5`). */
internal fun isLoginPhoneValid(phone: String): Boolean = phone.trim().length >= 5

/** Код (SMS/Telegram) валиден: ровно 6 цифр (как было: `code.length < 6`). Ввод и так фильтрует нецифры. */
internal fun isLoginCodeValid(code: String): Boolean = code.trim().length == 6

// testTag'и кнопок входа: при loading текст скрывается спиннером, поэтому в тестах целимся в кнопку
// по тегу (проверить disabled/двойной тап). На вид/поведение не влияют.
internal const val TAG_LOGIN_TELEGRAM_BTN = "login_telegram_btn"   // экран выбора: «Войти через Telegram»
internal const val TAG_LOGIN_TG_VERIFY_BTN = "login_tg_verify_btn" // шаг Telegram-кода: «Войти»
internal const val TAG_LOGIN_SMS_PRIMARY_BTN = "login_sms_primary_btn" // SMS-форма (заморожена): «Получить код»/«Войти»

@Composable
internal fun LoginScreen(
    currentLanguage: AppLanguage,
    onToggleLanguage: () -> Unit,
    onContinue: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = CanonBg
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            BrandHero(
                currentLanguage = currentLanguage,
                onToggleLanguage = onToggleLanguage,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(700.dp)
            )
            // Форма «висит» над геро: поднимаем карточку на heroOverlap И на столько же ужимаем
            // её высоту в layout. Голый offset сдвигает только рисование, а layout-высоту не меняет —
            // отсюда брался пустой фон снизу (зазор при скролле в конец). Custom layout сдвигает
            // карточку вверх и одновременно укорачивает прокручиваемую высоту → дыры нет.
            val heroOverlap = 118.dp
            Column(
                modifier = Modifier.layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    val dy = -heroOverlap.roundToPx()
                    layout(placeable.width, (placeable.height + dy).coerceAtLeast(0)) {
                        placeable.place(0, dy)
                    }
                }
            ) {
                LoginFormCard(
                    currentLanguage = currentLanguage,
                    onContinue = onContinue,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

// «Умная» обёртка формы входа: держит стейт (step/phone/code/name/loading/error/tgMode/needPhone),
// ходит в ApiClient и открывает Telegram. Весь рендер — в чистом [LoginFormContent], который покрыт
// тестами на JVM (Robolectric). Поведение 1:1 с прежней монолитной версией.
@Composable
private fun LoginFormCard(
    currentLanguage: AppLanguage,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    // rememberSaveable: переживают смерть процесса. Главный кейс — юзер уходит в Telegram за кодом,
    // ОС выгружает приложение; на возврате он остаётся на вводе кода (tgMode/tgRequestId/code), а не в начале входа.
    var step by rememberSaveable { mutableStateOf(0) }            // 0 — ввод телефона, 1 — ввод кода
    var phone by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showPhone by rememberSaveable { mutableStateOf(false) }   // SMS-форма (заморожена) раскрывается по тапу
    var tgMode by rememberSaveable { mutableStateOf(false) }      // true — ждём ввод кода из Telegram
    var tgRequestId by rememberSaveable { mutableStateOf("") }
    var nameInput by rememberSaveable { mutableStateOf("") }       // имя при регистрации (необязательно)
    var needPhone by rememberSaveable { mutableStateOf(false) }   // сервер требует номер (403 phone_required)
    val context = LocalContext.current

    // Строки ошибок считаем здесь (в @Composable-контексте с currentLanguage) — колбэки получают готовый текст.
    val errEnterTgCode = appTextFor(currentLanguage, "Введите код из Telegram", "Telegram кодын индерегеҙ")
    val errBadTgCode = appTextFor(currentLanguage, "Неверный код. Проверь и введи снова.", "Код дөрөҫ түгел. Тикшереп, ҡабат индер.")
    val errExpiredCode = appTextFor(currentLanguage, "Код истёк. Получи новый — открой Telegram ещё раз.", "Код ваҡыты бөттө. Яңыһын ал — Telegram'ды тағы ас.")
    val errTooManyCode = appTextFor(currentLanguage, "Слишком много попыток. Получи новый код.", "Бик күп омтылыш. Яңы код ал.")
    val errPhoneRequired = appTextFor(currentLanguage, "Для безопасности нужен номер. В Telegram нажми «📱 Поделиться номером», потом вернись и нажми «Войти».", "Хәүефһеҙлек өсөн номер кәрәк. Telegram'да «📱 Номер менән бүлешергә» баҫ, аҙаҡ кире ҡайтып «Инеү» баҫ.")
    // Под наплывом (запуск) Telegram шлёт коды с задержкой (~30/сек на бота) → сервер
    // отвечает 409 «код ещё не пришёл». Честное сообщение, чтобы юзер не думал, что ошибся.
    val errCodeNotYet = appTextFor(currentLanguage, "Код ещё идёт от Telegram — подожди пару секунд и нажми «Войти» снова.", "Код Telegram'дан килә — бер-ике секунд көт тә «Инеү» баҫ.")
    val errTgStart = appTextFor(currentLanguage, "Не удалось начать вход. Повтори.", "Инеүҙе башлап булманы. Ҡабатла.")
    val tgSoon = appTextFor(currentLanguage, "Вход через Telegram скоро", "Telegram аша инеү тиҙҙән")
    val errEnterPhone = appTextFor(currentLanguage, "Введите номер телефона", "Телефон номерын индерегеҙ")
    val errSendFail = appTextFor(currentLanguage, "Не получилось отправить код. Повтори.", "Код ебәреп булманы. Ҡабатла.")
    val errEnterCode = appTextFor(currentLanguage, "Введите код из SMS", "SMS кодын индерегеҙ")
    val errBadCode = appTextFor(currentLanguage, "Неверный код", "Код дөрөҫ түгел")

    fun openTelegram(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
        }
    }

    LoginFormContent(
        currentLanguage = currentLanguage,
        step = step,
        phone = phone,
        code = code,
        name = nameInput,
        loading = loading,
        error = error,
        needPhone = needPhone,
        tgMode = tgMode,
        showPhone = showPhone,
        onPhoneChange = { phone = it; error = null },
        onCodeChange = { code = it.filter { c -> c.isDigit() }.take(6); error = null },
        onNameChange = { nameInput = it.take(120) },
        // Главный вход через Telegram (экран выбора). Гард двойного тапа: `if (loading) return`.
        onTelegramStart = {
            if (!loading) {
                if (BuildConfig.TELEGRAM_BOT.isBlank()) { error = tgSoon } else {
                    loading = true; error = null
                    scope.launch {
                        ApiClient.tgStart()
                            .onSuccess { req ->
                                loading = false
                                tgRequestId = req
                                code = ""
                                tgMode = true
                                openTelegram("https://t.me/${BuildConfig.TELEGRAM_BOT}?start=$req")
                            }
                            .onFailure { loading = false; error = errTgStart }
                    }
                }
            }
        },
        // Кнопка «Войти» на шаге ввода Telegram-кода. Гард двойного тапа + пустого кода — как раньше.
        onTgVerify = {
            if (!loading) {
                if (!isLoginCodeValid(code)) { error = errEnterTgCode } else {
                    loading = true; error = null
                    scope.launch {
                        ApiClient.tgVerify(tgRequestId, code.trim())
                            .onSuccess {
                                loading = false
                                nameInput.trim().takeIf { it.isNotBlank() }?.let { ApiClient.fireUpdateName(it) }
                                onContinue()
                            }
                            .onFailure { e ->
                                loading = false
                                when ((e as? ApiException)?.status) {
                                    403 -> { needPhone = true; error = errPhoneRequired }   // нужен номер
                                    409 -> { needPhone = false; error = errCodeNotYet }    // код ещё идёт от Telegram под нагрузкой
                                    410 -> { needPhone = false; error = errExpiredCode }   // код истёк
                                    429 -> { needPhone = false; error = errTooManyCode }   // много попыток
                                    else -> { needPhone = false; error = errBadTgCode }    // неверный код
                                }
                            }
                    }
                }
            }
        },
        // Открыть Telegram из шага кода. Нужен номер → чат БЕЗ ?start (делимся контактом, код не перевыпускаем).
        // Иначе — начинаем СВЕЖУЮ сессию (новый request_id + новый код), а не переоткрываем старый:
        // после протухшего кода (410) или лимита попыток (429) переоткрытие старого request_id вело в тупик.
        onTelegramOpen = {
            if (needPhone) {
                openTelegram("https://t.me/${BuildConfig.TELEGRAM_BOT}")
            } else if (!loading) {
                loading = true; error = null
                scope.launch {
                    ApiClient.tgStart()
                        .onSuccess { req ->
                            loading = false
                            tgRequestId = req
                            code = ""
                            openTelegram("https://t.me/${BuildConfig.TELEGRAM_BOT}?start=$req")
                        }
                        .onFailure { loading = false; error = errTgStart }
                }
            }
        },
        onBackFromTg = { tgMode = false; code = ""; error = null },
        onToggleSmsForm = { showPhone = !showPhone },
        onChangePhone = { step = 0; code = ""; error = null },
        // SMS-кнопка «Получить код» / «Войти». Гарды двойного тапа и пустых полей — как в прежнем onClick.
        onSmsPrimary = {
            if (!loading) {
                error = null
                if (step == 0) {
                    if (!isLoginPhoneValid(phone)) { error = errEnterPhone } else {
                        loading = true
                        scope.launch {
                            ApiClient.requestCode(phone.trim())
                                .onSuccess { loading = false; step = 1 }
                                .onFailure { loading = false; error = errSendFail }
                        }
                    }
                } else {
                    if (!isLoginCodeValid(code)) { error = errEnterCode } else {
                        loading = true
                        scope.launch {
                            ApiClient.verifyCode(phone.trim(), code.trim(), nameInput.trim())
                                .onSuccess { loading = false; onContinue() }
                                .onFailure { loading = false; error = errBadCode }
                        }
                    }
                }
            }
        },
        modifier = modifier,
    )
}

/**
 * Чистый рендер формы входа: телефон/код/имя, оба потока (Telegram и замороженный SMS), все ошибки.
 * Стейт и сеть — параметрами и колбэками (без ApiClient/scope/эффектов) → тестируется на JVM (Robolectric).
 * Кнопки входа disabled при `loading` (гард двойного тапа) и при невалидном поле (см. isLoginPhoneValid/isLoginCodeValid).
 */
@Composable
internal fun LoginFormContent(
    currentLanguage: AppLanguage,
    step: Int,
    phone: String,
    code: String,
    name: String,
    loading: Boolean,
    error: String?,
    needPhone: Boolean,
    tgMode: Boolean,
    showPhone: Boolean,
    onPhoneChange: (String) -> Unit,
    onCodeChange: (String) -> Unit,
    onNameChange: (String) -> Unit,
    onTelegramStart: () -> Unit,
    onTgVerify: () -> Unit,
    onTelegramOpen: () -> Unit,
    onBackFromTg: () -> Unit,
    onToggleSmsForm: () -> Unit,
    onChangePhone: () -> Unit,
    onSmsPrimary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 16.dp),
        shape = RoundedCornerShape(topStart = 44.dp, topEnd = 44.dp, bottomStart = 0.dp, bottomEnd = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 32.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = appTextFor(currentLanguage, "Войти в Юлдаш", "Юлдашҡа инеү"),
                color = CanonText,
                fontSize = 32.sp,
                lineHeight = 36.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center
            )
            Text(
                text = appTextFor(
                    currentLanguage,
                    "Используйте Telegram для быстрого и безопасного входа",
                    "Тиҙ һәм хәүефһеҙ инеү өсөн Telegram ҡулланығыҙ"
                ),
                color = CanonMuted,
                fontSize = 20.sp,
                lineHeight = 26.sp,
                textAlign = TextAlign.Center
            )
            if (tgMode) {
                // --- Ввод 6-значного кода, который бот прислал в Telegram ---
                val errPhoneRequired = appTextFor(currentLanguage, "Для безопасности нужен номер. В Telegram нажми «📱 Поделиться номером», потом вернись и нажми «Войти».", "Хәүефһеҙлек өсөн номер кәрәк. Telegram'да «📱 Номер менән бүлешергә» баҫ, аҙаҡ кире ҡайтып «Инеү» баҫ.")
                Text(
                    text = appTextFor(currentLanguage, "Открой Telegram, нажми «Старт» — бот пришлёт 6-значный код. Введи его сюда.", "Telegram'ды ас, «Старт» баҫ — бот 6 һанлы код ебәрер. Шуны индер."),
                    color = CanonMuted, fontSize = 16.sp, lineHeight = 22.sp
                )
                if (needPhone) {
                    Surface(color = CanonWarnBg, shape = CanonItemShape) {
                        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Default.Shield, contentDescription = null, tint = CanonWarn, modifier = Modifier.size(22.dp))
                            Text(errPhoneRequired, color = CanonWarn, fontSize = 14.sp, lineHeight = 19.sp)
                        }
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    placeholder = { Text(appTextFor(currentLanguage, "Ваше имя (необязательно)", "Исемегеҙ (мотлаҡ түгел)"), fontSize = 16.sp) },
                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = CanonMuted) },
                    modifier = Modifier.fillMaxWidth().height(58.dp),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = onCodeChange,
                    placeholder = { Text(appTextFor(currentLanguage, "Код из Telegram", "Telegram коды"), fontSize = 16.sp) },
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = CanonMuted) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth().height(58.dp),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )
                error?.let { Text(it, color = CanonRed, fontSize = 14.sp, lineHeight = 19.sp) }
                AppButton(
                    text = appTextFor(currentLanguage, "Войти", "Инеү"),
                    loading = loading,
                    onClick = onTgVerify,
                    enabled = !loading,   // гард двойного тапа; пустой код ловит колбэк (показывает ошибку) — поведение 1:1
                    modifier = Modifier.testTag(TAG_LOGIN_TG_VERIFY_BTN),
                )
                TextButton(onClick = onTelegramOpen, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(
                        if (needPhone) appTextFor(currentLanguage, "Открыть Telegram и поделиться номером", "Telegram'ды асып, номер менән бүлешергә")
                        else appTextFor(currentLanguage, "Открыть Telegram ещё раз", "Telegram'ды тағы асырға"),
                        color = CanonGreen2, fontSize = 14.sp
                    )
                }
                TextButton(onClick = onBackFromTg, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(appTextFor(currentLanguage, "Назад", "Кире"), color = CanonMuted, fontSize = 14.sp)
                }
            } else {
            // Подпись про «6-значный код» убрана — бейдж на геро уже это говорит (без дубля).
            Spacer(modifier = Modifier.height(2.dp))
            // Telegram — рабочий вход (бот шлёт 6-значный код). VK/WhatsApp — «скоро».
            Button(
                onClick = onTelegramStart,
                enabled = !loading,   // гард двойного тапа
                modifier = Modifier.fillMaxWidth().height(72.dp).testTag(TAG_LOGIN_TELEGRAM_BTN),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)
            ) {
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
                        Spacer(Modifier.width(16.dp))
                        Text(appTextFor(currentLanguage, "Войти через Telegram", "Telegram аша инеү"), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
                    }
                }
            }
            // VK и WhatsApp убраны: VK требует ИНН (бизнес), WhatsApp — WhatsApp Business API. Оба недоступны физлицу.
            // SMS-вход ЗАМОРОЖЕН (нет юр.лица для sms.ru). Форма цела — видна только при SMS_LOGIN_ENABLED.
            if (BuildConfig.SMS_LOGIN_ENABLED) {
                LoginSmsSection(
                    currentLanguage = currentLanguage,
                    step = step,
                    phone = phone,
                    code = code,
                    name = name,
                    loading = loading,
                    error = error,
                    showPhone = showPhone,
                    onPhoneChange = onPhoneChange,
                    onCodeChange = onCodeChange,
                    onNameChange = onNameChange,
                    onToggleSmsForm = onToggleSmsForm,
                    onChangePhone = onChangePhone,
                    onSmsPrimary = onSmsPrimary,
                )
            }   // конец if (BuildConfig.SMS_LOGIN_ENABLED) — SMS-вход заморожен
            Spacer(Modifier.height(6.dp))
            LoginConsent(
                currentLanguage = currentLanguage,
                modifier = Modifier.fillMaxWidth()
            )
            }   // конец else (tgMode == false) — экран выбора входа
        }
    }
}

/**
 * Замороженная SMS-форма входа (видна только при [BuildConfig.SMS_LOGIN_ENABLED]). Чистый рендер на
 * колбэках — вынесен из [LoginFormContent] в `ColumnScope`-extension, чтобы элементы раскладывались
 * в тот же родительский Column (тот же `spacedBy(16.dp)`, вёрстка 1:1). `internal` → тестируется на
 * JVM (Robolectric) НАПРЯМУЮ, минуя build-флаг, который в unit-сборке всегда false. Логика 1:1 с прежним
 * инлайн-блоком: разделитель, тумблер SMS-формы, поля телефона/имени/кода по [step], ошибка, primary-кнопка
 * (disabled при [loading] — гард двойного тапа; пустое поле ловит колбэк [onSmsPrimary]).
 */
@Composable
internal fun ColumnScope.LoginSmsSection(
    currentLanguage: AppLanguage,
    step: Int,
    phone: String,
    code: String,
    name: String,
    loading: Boolean,
    error: String?,
    showPhone: Boolean,
    onPhoneChange: (String) -> Unit,
    onCodeChange: (String) -> Unit,
    onNameChange: (String) -> Unit,
    onToggleSmsForm: () -> Unit,
    onChangePhone: () -> Unit,
    onSmsPrimary: () -> Unit,
) {
    LoginDivider(currentLanguage)
    OutlinedButton(
        onClick = onToggleSmsForm,
        modifier = Modifier.fillMaxWidth().height(64.dp),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, CanonGreen2),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = CanonGreen2)
    ) {
        Icon(Icons.Default.Phone, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(14.dp))
        Text(appTextFor(currentLanguage, "Войти по номеру телефона", "Телефон номеры аша инеү"), color = CanonGreen2, fontSize = 20.sp, fontWeight = FontWeight.Bold)
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
                onValueChange = onPhoneChange,
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
                value = name,
                onValueChange = onNameChange,
                placeholder = { Text(appTextFor(currentLanguage, "Ваше имя (необязательно)", "Исемегеҙ (мотлаҡ түгел)"), fontSize = 16.sp) },
                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = CanonMuted) },
                modifier = Modifier.fillMaxWidth().height(58.dp),
                singleLine = true,
                shape = RoundedCornerShape(14.dp)
            )
            OutlinedTextField(
                value = code,
                onValueChange = onCodeChange,
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
            TextButton(onClick = onChangePhone) {
                Text(appTextFor(currentLanguage, "Изменить номер", "Номерҙы үҙгәртеү"), color = CanonGreen2)
            }
        }
        error?.let {
            Text(it, color = CanonRed, fontSize = 14.sp, lineHeight = 19.sp)
        }
        Button(
            onClick = onSmsPrimary,
            enabled = !loading,   // гард двойного тапа; пустое поле ловит колбэк (показывает ошибку) — поведение 1:1
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .testTag(TAG_LOGIN_SMS_PRIMARY_BTN),
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
}

@Composable
internal fun LoginDivider(currentLanguage: AppLanguage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(CanonBorder)
        )
        Text(
            text = appTextFor(currentLanguage, "или", "йәки"),
            color = CanonMuted,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(CanonBorder)
        )
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
            .background(CanonGreen2)
    ) {
        Image(
            painter = painterResource(R.drawable.login_salavat_yulaev_hero),
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = 1.0f
                    scaleY = 1.0f
                    translationY = 0f
                },
            contentScale = ContentScale.Crop
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to CanonGreen2.copy(alpha = 0.60f),
                        0.44f to CanonGreen2.copy(alpha = 0.22f),
                        0.72f to Color.Black.copy(alpha = 0.14f),
                        1f to Color.Black.copy(alpha = 0.90f)
                    )
                )
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to CanonGreen2.copy(alpha = 0.40f),
                        0.55f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.20f)
                    )
                )
        )

        LoginLangToggle(
            currentLanguage = currentLanguage,
            onToggleLanguage = onToggleLanguage,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 16.dp, end = 24.dp)
        )

        Surface(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(top = 16.dp, start = 24.dp)
                .size(54.dp),
            shape = RoundedCornerShape(18.dp),
            color = Color.White,
            shadowElevation = 6.dp
        ) {
            Image(
                painter = painterResource(R.drawable.yuldash_logo),
                contentDescription = null,
                modifier = Modifier.padding(7.dp),
                contentScale = ContentScale.Fit
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(top = 104.dp, start = 24.dp, end = 24.dp)
        ) {
            Text("Юлдаш", color = Color.White, fontSize = 64.sp, lineHeight = 66.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(5.dp))
            Text(
                text = appTextFor(currentLanguage, "Поездки между своими", "Үҙебеҙҙекеләр араһында юллашыу"),
                color = Color.White.copy(alpha = 0.94f),
                fontSize = 24.sp,
                lineHeight = 29.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(18.dp))
            LoginHeroFeatures(currentLanguage)
        }

    }
}

// Переключатель языка — тот же сегментированный стиль, что на онбординге (белая «таблетка», активный чип зелёный).
@Composable
internal fun LoginHeroFeatures(currentLanguage: AppLanguage) {
    Column(verticalArrangement = Arrangement.spacedBy(15.dp)) {
        LoginHeroFeature(
            icon = Icons.Default.Lock,
            title = appTextFor(currentLanguage, "Вход без пароля", "Парольһеҙ инеү"),
            body = appTextFor(currentLanguage, "Только код — ни паролей, ни анкет", "Бары код — пароль да, анкета ла юҡ")
        )
        LoginHeroFeature(
            icon = Icons.Default.Block,
            title = appTextFor(currentLanguage, "Никакого спама", "Спам юҡ"),
            body = appTextFor(currentLanguage, "Не звоним и не шлём SMS", "Шылтыратмайбыҙ, SMS ебәрмәйбеҙ")
        )
        LoginHeroFeature(
            icon = Icons.Default.Shield,
            title = appTextFor(currentLanguage, "Данные под защитой", "Мәғлүмәт һаҡлауҙа"),
            body = appTextFor(currentLanguage, "Шифруем и не передаём третьим", "Шифрлайбыҙ, өсөнсө яҡҡа бирмәйбеҙ")
        )
    }
}

@Composable
private fun LoginHeroFeature(icon: ImageVector, title: String, body: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Surface(
            modifier = Modifier.size(54.dp),
            shape = RoundedCornerShape(16.dp),
            color = CanonGreen2.copy(alpha = 0.92f),
            shadowElevation = 4.dp
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.padding(13.dp)
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = Color.White, fontSize = 20.sp, lineHeight = 23.sp, fontWeight = FontWeight.Black)
            Text(body, color = Color.White.copy(alpha = 0.92f), fontSize = 16.sp, lineHeight = 20.sp)
        }
    }
}

@Composable
internal fun LoginLangToggle(
    currentLanguage: AppLanguage,
    onToggleLanguage: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = CanonSurface,
        border = BorderStroke(1.dp, CanonBorder),
        shadowElevation = 3.dp
    ) {
        Row(Modifier.padding(2.dp)) {
            LoginLangChip("РУС", currentLanguage == AppLanguage.Ru) { if (currentLanguage != AppLanguage.Ru) onToggleLanguage() }
            LoginLangChip("БАШ", currentLanguage == AppLanguage.Ba) { if (currentLanguage != AppLanguage.Ba) onToggleLanguage() }
        }
    }
}

@Composable
private fun LoginLangChip(text: String, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .background(if (active) CanonGreen2 else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 5.dp)
    ) {
        Text(text, color = if (active) Color.White else CanonMuted, fontSize = 12.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
internal fun SafetyFooter(currentLanguage: AppLanguage, modifier: Modifier = Modifier) {
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

// Согласие с офертой: ссылки ведут на реальные страницы лендинга (yulbash.ru/terms, /privacy).
// internal (а не private) → покрыт на JVM (Robolectric): двуязычие текста + ссылки «Условия»/«Политику…».
@Composable
internal fun LoginConsent(currentLanguage: AppLanguage, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    fun open(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
        }
    }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = appTextFor(currentLanguage, "Входя, ты принимаешь", "Инеп, һин ҡабул итәһең:"),
            color = CanonMuted,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            textAlign = TextAlign.Center
        )
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = appTextFor(currentLanguage, "Условия", "Шарттарҙы"),
                color = CanonGreen2,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { open("https://yulbash.ru/terms/") }
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            )
            Text(" · ", color = CanonMuted, fontSize = 12.sp)
            Text(
                text = appTextFor(currentLanguage, "Политику конфиденциальности", "Конфиденциаллек сәйәсәтен"),
                color = CanonGreen2,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { open("https://yulbash.ru/privacy/") }
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }
    }
}
