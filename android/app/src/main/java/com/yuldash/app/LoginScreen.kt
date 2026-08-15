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
import androidx.compose.animation.core.EaseOutExpo
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.ui.text.TextStyle
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

// ─────────────────────────── Типографика и сетка экрана входа ───────────────────────────
// РОВНО ЧЕТЫРЕ размера текста, у каждого своя роль. Пятого не заводим: экран входа — первое
// касание, разнобой кеглей читается как «самоделка». Аудитория — сёла, много пожилых, поэтому
// нижняя ступень 14sp (а не 12sp), а основной текст крупнее обычного.
private val LoginDisplay = 52.sp    // 1 · витрина  — только слово «Юлдаш» на геро, один раз на экране
private val LoginTitle = 24.sp      // 2 · заголовок — «Войти в Юлдаш», слоган на геро, введённый код
private val LoginBody = 16.sp       // 3 · основной — кнопки, поля, подписи фич, ошибки, подсказки
private val LoginCaption = 14.sp    // 4 · сноска   — согласие, вторичные ссылки, «или», описания фич

// Ритм экрана — кратно 4dp. Боковое поле одно и то же у геро и у формы: текст на фото и текст
// в карточке стоят на одной вертикали, поэтому «шов» между ними не читается.
private val LoginGutter = 24.dp

@Composable
internal fun LoginScreen(
    currentLanguage: AppLanguage,
    onToggleLanguage: () -> Unit,
    onContinue: () -> Unit,
) {
    // Появление формы. Флаг стартует false и включается ПОСЛЕ первой композиции — иначе
    // animate*AsState берёт цель уже на первом кадре и никакого «выезда» не будет (мёртвая анимация).
    var appear by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appear = true }
    val cardIn by animateFloatAsState(
        targetValue = if (appear) 1f else 0f,
        animationSpec = tween(560, delayMillis = 140, easing = EaseOutExpo),
        label = "loginCardIn",
    )
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = CanonBg
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            // heightIn, а не height: при системном КРУПНОМ шрифте (им пользуются пожилые) геро
            // растёт под текст, а не режет его. Фото внутри тянется matchParentSize.
            BrandHero(
                currentLanguage = currentLanguage,
                onToggleLanguage = onToggleLanguage,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 560.dp)
            )
            // Форма «висит» над геро: поднимаем карточку на heroOverlap И на столько же ужимаем
            // её высоту в layout. Голый offset сдвигает только рисование, а layout-высоту не меняет —
            // отсюда брался пустой фон снизу (зазор при скролле в конец). Custom layout сдвигает
            // карточку вверх и одновременно укорачивает прокручиваемую высоту → дыры нет.
            val heroOverlap = 120.dp
            Column(
                modifier = Modifier
                    .graphicsLayer {
                        alpha = cardIn
                        translationY = (1f - cardIn) * 40.dp.toPx()   // карточка «поднимается» из-под геро
                    }
                    .layout { measurable, constraints ->
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
    val errEnterTgCode = appTextFor(currentLanguage, "Введи код из Telegram", "Telegram кодын индер")
    val errBadTgCode = appTextFor(currentLanguage, "Неверный код. Проверь и введи снова.", "Код дөрөҫ түгел. Тикшереп, ҡабат индер.")
    val errExpiredCode = appTextFor(currentLanguage, "Код истёк. Получи новый — открой Telegram ещё раз.", "Код ваҡыты бөттө. Яңыһын ал — Telegram'ды тағы ас.")
    val errTooManyCode = appTextFor(currentLanguage, "Слишком много попыток. Получи новый код.", "Бик күп омтылыш. Яңы код ал.")
    val errPhoneRequired = appTextFor(currentLanguage, "Для безопасности нужен номер. В Telegram нажми «📱 Поделиться номером», потом вернись и нажми «Войти».", "Хәүефһеҙлек өсөн номер кәрәк. Telegram'да «📱 Номер менән бүлешергә» баҫ, аҙаҡ кире ҡайтып «Инеү» баҫ.")
    // Под наплывом (запуск) Telegram шлёт коды с задержкой (~30/сек на бота) → сервер
    // отвечает 409 «код ещё не пришёл». Честное сообщение, чтобы юзер не думал, что ошибся.
    val errCodeNotYet = appTextFor(currentLanguage, "Код ещё идёт от Telegram — подожди пару секунд и нажми «Войти» снова.", "Код Telegram'дан килә — бер-ике секунд көт тә «Инеү» баҫ.")
    // Этот сбой почти всегда = нет связи (запрос к серверу вообще не ушёл). Говорим об этом прямо,
    // а не «не удалось» — человеку в селе полезнее подсказка «проверь интернет», чем код ошибки.
    val errTgStart = appTextFor(currentLanguage, "Не получилось связаться с сервером. Проверь интернет и повтори.", "Сервер менән бәйләнеш булманы. Интернетты тикшер ҙә ҡабатла.")
    val tgSoon = appTextFor(currentLanguage, "Вход через Telegram скоро", "Telegram аша инеү тиҙҙән")
    val errEnterPhone = appTextFor(currentLanguage, "Введи номер телефона", "Телефон номерын индер")
    val errSendFail = appTextFor(currentLanguage, "Не получилось отправить код. Проверь интернет и повтори.", "Код ебәреп булманы. Интернетты тикшер ҙә ҡабатла.")
    val errEnterCode = appTextFor(currentLanguage, "Введи код из SMS", "SMS кодын индер")
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
        elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.sheet),
        shape = RoundedCornerShape(topStart = 44.dp, topEnd = 44.dp, bottomStart = 0.dp, bottomEnd = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = LoginGutter, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = appTextFor(currentLanguage, "Войти в Юлдаш", "Юлдашҡа инеү"),
                color = CanonText,
                fontSize = LoginTitle,
                lineHeight = 30.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Text(
                text = appTextFor(
                    currentLanguage,
                    "Используйте Telegram для быстрого и безопасного входа",
                    "Тиҙ һәм хәүефһеҙ инеү өсөн Telegram ҡуллан"
                ),
                color = CanonMuted,
                fontSize = LoginBody,
                lineHeight = 23.sp,
                textAlign = TextAlign.Center
            )
            if (tgMode) {
                // --- Ввод 6-значного кода, который бот прислал в Telegram ---
                val errPhoneRequired = appTextFor(currentLanguage, "Для безопасности нужен номер. В Telegram нажми «📱 Поделиться номером», потом вернись и нажми «Войти».", "Хәүефһеҙлек өсөн номер кәрәк. Telegram'да «📱 Номер менән бүлешергә» баҫ, аҙаҡ кире ҡайтып «Инеү» баҫ.")
                Text(
                    text = appTextFor(currentLanguage, "Открой Telegram, нажми «Старт» — бот пришлёт 6-значный код. Введи его сюда.", "Telegram'ды ас, «Старт» баҫ — бот 6 һанлы код ебәрер. Шуны индер."),
                    color = CanonMuted, fontSize = LoginBody, lineHeight = 23.sp
                )
                // Баннер «нужен номер» приходит после 403 — значит на входе его нет и появление
                // действительно анимируется (не мёртвый AnimatedVisibility(visible = true)).
                AnimatedVisibility(
                    visible = needPhone,
                    enter = fadeIn(tween(CanonMotion.NORMAL)) + expandVertically(tween(CanonMotion.NORMAL, easing = EaseOutExpo)),
                    exit = fadeOut(tween(CanonMotion.QUICK)) + shrinkVertically(tween(CanonMotion.QUICK)),
                ) {
                    Surface(color = CanonWarnBg, shape = CanonItemShape) {
                        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Default.Shield, contentDescription = null, tint = CanonWarn, modifier = Modifier.size(24.dp))
                            Text(errPhoneRequired, color = CanonWarn, fontSize = LoginCaption, lineHeight = 20.sp)
                        }
                    }
                }
                // heightIn вместо height: при системном крупном шрифте поле растёт, а не режет текст.
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    placeholder = { Text(appTextFor(currentLanguage, "Твоё имя (необязательно)", "Исемең (мотлаҡ түгел)"), fontSize = LoginBody) },
                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = CanonMuted) },
                    textStyle = TextStyle(fontSize = LoginBody, color = CanonText),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                    singleLine = true,
                    shape = CanonItemShape
                )
                // Код — главное поле шага: крупно и с разрядкой, чтобы 6 цифр читались с руки.
                OutlinedTextField(
                    value = code,
                    onValueChange = onCodeChange,
                    placeholder = { Text(appTextFor(currentLanguage, "Код из Telegram", "Telegram коды"), fontSize = LoginBody) },
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = CanonMuted) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    textStyle = TextStyle(fontSize = LoginTitle, fontWeight = FontWeight.Bold, letterSpacing = 6.sp, color = CanonText),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
                    singleLine = true,
                    shape = CanonItemShape
                )
                LoginErrorBanner(
                    currentLanguage = currentLanguage,
                    text = error,
                    onRetry = onTgVerify,   // «Повторить» = та же проверка кода, что и кнопка «Войти»
                )
                AppButton(
                    text = appTextFor(currentLanguage, "Войти", "Инеү"),
                    loading = loading,
                    onClick = onTgVerify,
                    enabled = !loading,   // гард двойного тапа; пустой код ловит колбэк (показывает ошибку) — поведение 1:1
                    height = 64.dp,
                    modifier = Modifier.testTag(TAG_LOGIN_TG_VERIFY_BTN),
                )
                LoginLoadingHint(
                    visible = loading,
                    text = appTextFor(currentLanguage, "Проверяем код…", "Кодты тикшерәбеҙ…"),
                )
                TextButton(
                    onClick = onTelegramOpen,
                    modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp),
                ) {
                    Text(
                        if (needPhone) appTextFor(currentLanguage, "Открыть Telegram и поделиться номером", "Telegram'ды асып, номер менән бүлешергә")
                        else appTextFor(currentLanguage, "Открыть Telegram ещё раз", "Telegram'ды тағы асырға"),
                        color = CanonGreen2, fontSize = LoginBody, lineHeight = 23.sp, textAlign = TextAlign.Center
                    )
                }
                TextButton(
                    onClick = onBackFromTg,
                    modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp),
                ) {
                    Text(appTextFor(currentLanguage, "Назад", "Кире"), color = CanonMuted, fontSize = LoginCaption)
                }
            } else {
            // Подпись про «6-значный код» убрана — бейдж на геро уже это говорит (без дубля).
            // Telegram — рабочий вход (бот шлёт 6-значный код). VK/WhatsApp — «скоро».
            // Отклик на нажатие: кнопка чуть «проседает» (0.97) и возвращается — как в iOS.
            val tgPress = remember { MutableInteractionSource() }
            val tgPressed by tgPress.collectIsPressedAsState()
            val tgScale by animateFloatAsState(
                if (tgPressed) 0.97f else 1f, tween(CanonMotion.QUICK, easing = EaseOutExpo), label = "tgPress",
            )
            Button(
                onClick = onTelegramStart,
                enabled = !loading,   // гард двойного тапа
                interactionSource = tgPress,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp)   // растёт под крупный шрифт, не обрезая надпись
                    .graphicsLayer { scaleX = tgScale; scaleY = tgScale }
                    .testTag(TAG_LOGIN_TELEGRAM_BTN),
                shape = CanonItemShape,
                colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)
            ) {
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(16.dp))
                        Text(
                            appTextFor(currentLanguage, "Войти через Telegram", "Telegram аша инеү"),
                            color = Color.White, fontSize = LoginBody, lineHeight = 23.sp,
                            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            LoginLoadingHint(
                visible = loading,
                text = appTextFor(currentLanguage, "Открываем Telegram…", "Telegram'ды асабыҙ…"),
            )
            // РАНЬШЕ ОШИБКИ ЗДЕСЬ НЕ БЫЛО ВИДНО: `error` на экране выбора не рисовался нигде (его показывал
            // только SMS-блок за выключенным флагом). Упала сеть или бот не настроен — человек видел
            // «мёртвую» кнопку и ноль объяснений. Теперь: текст + «Повторить».
            LoginErrorBanner(
                currentLanguage = currentLanguage,
                // при развёрнутой SMS-форме ту же ошибку показывает она сама — не дублируем
                text = if (BuildConfig.SMS_LOGIN_ENABLED && showPhone) null else error,
                onRetry = onTelegramStart,
            )
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
            Spacer(Modifier.height(8.dp))
            LoginConsent(
                currentLanguage = currentLanguage,
                modifier = Modifier.fillMaxWidth()
            )
            }   // конец else (tgMode == false) — экран выбора входа
        }
    }
}

/**
 * Ошибка входа: что случилось + что делать + кнопка «Повторить». Один вид для обоих потоков.
 *
 * Почему так: вход — единственная дверь в приложение, и «упало молча» здесь дороже всего. Отдельной
 * строкой даём объяснение для двух самых частых случаев у нас — нет интернета и код от бота ещё не дошёл.
 *
 * Анимация живая: баннера при входе в композицию нет (`text == null`), он появляется в момент ошибки.
 * Текст держим в [shownText] — иначе на выходе баннер схлопывался бы пустым.
 * `internal` — чтобы покрыть на JVM (Robolectric) без сети и ApiClient.
 */
@Composable
internal fun LoginErrorBanner(
    currentLanguage: AppLanguage,
    text: String?,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var shownText by remember { mutableStateOf(text.orEmpty()) }
    LaunchedEffect(text) { if (text != null) shownText = text }
    AnimatedVisibility(
        visible = text != null,
        enter = fadeIn(tween(CanonMotion.NORMAL)) + expandVertically(tween(CanonMotion.NORMAL, easing = EaseOutExpo)),
        exit = fadeOut(tween(CanonMotion.QUICK)) + shrinkVertically(tween(CanonMotion.QUICK)),
        modifier = modifier,
    ) {
        Surface(color = CanonDangerBg, shape = CanonItemShape, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = appTextFor(currentLanguage, "Ошибка", "Хата"),
                        tint = CanonRed,
                        modifier = Modifier.size(24.dp),
                    )
                    Text(shownText, color = CanonRed, fontSize = LoginBody, lineHeight = 23.sp)
                }
                Text(
                    text = appTextFor(
                        currentLanguage,
                        "Нет интернета или код ещё не пришёл? Проверь связь и нажми «Повторить».",
                        "Интернет юҡмы, әллә код килеп еткәне юҡмы? Бәйләнеште тикшер ҙә «Ҡабатла» баҫ.",
                    ),
                    color = CanonMuted, fontSize = LoginCaption, lineHeight = 20.sp,
                )
                if (onRetry != null) {
                    AppButton(
                        text = appTextFor(currentLanguage, "Повторить", "Ҡабатлау"),
                        onClick = onRetry,
                        style = AppButtonStyle.Secondary,
                        icon = Icons.Default.Refresh,
                        height = 48.dp,
                    )
                }
            }
        }
    }
}

/** Подпись под кнопкой на время запроса: видно, что процесс идёт, а не «кнопка сломалась». */
@Composable
private fun LoginLoadingHint(visible: Boolean, text: String) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(CanonMotion.QUICK)) + expandVertically(tween(CanonMotion.NORMAL, easing = EaseOutExpo)),
        exit = fadeOut(tween(CanonMotion.QUICK)) + shrinkVertically(tween(CanonMotion.QUICK)),
    ) {
        Text(text, color = CanonMuted, fontSize = LoginCaption, lineHeight = 20.sp, textAlign = TextAlign.Center)
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
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        shape = CanonItemShape,
        border = BorderStroke(1.dp, CanonGreen2),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = CanonGreen2)
    ) {
        Icon(Icons.Default.Phone, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Text(appTextFor(currentLanguage, "Войти по номеру телефона", "Телефон номеры аша инеү"), color = CanonGreen2, fontSize = LoginBody, lineHeight = 23.sp, fontWeight = FontWeight.Bold)
    }
    if (showPhone) {
        Text(
            text = if (step == 0) appTextFor(currentLanguage, "Номер будет скрыт до подтверждения брони.", "Телефон номеры бронь раҫланғанға тиклем йәшерелә.")
            else appTextFor(currentLanguage, "Код отправлен на $phone", "Код $phone номерыңа ебәрелде"),
            color = CanonMuted,
            fontSize = LoginBody,
            lineHeight = 23.sp
        )
        if (step == 0) {
            OutlinedTextField(
                value = phone,
                onValueChange = onPhoneChange,
                placeholder = { Text(appTextFor(currentLanguage, "Номер телефона", "Телефон номеры"), fontSize = LoginBody) },
                leadingIcon = {
                    Icon(Icons.Default.PhoneLocked, contentDescription = null, tint = CanonMuted)
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                textStyle = TextStyle(fontSize = LoginBody, color = CanonText),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp),
                singleLine = true,
                shape = CanonItemShape
            )
        } else {
            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                placeholder = { Text(appTextFor(currentLanguage, "Твоё имя (необязательно)", "Исемең (мотлаҡ түгел)"), fontSize = LoginBody) },
                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = CanonMuted) },
                textStyle = TextStyle(fontSize = LoginBody, color = CanonText),
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                singleLine = true,
                shape = CanonItemShape
            )
            OutlinedTextField(
                value = code,
                onValueChange = onCodeChange,
                placeholder = { Text(appTextFor(currentLanguage, "Код из SMS", "SMS коды"), fontSize = LoginBody) },
                leadingIcon = {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = CanonMuted)
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                textStyle = TextStyle(fontSize = LoginTitle, fontWeight = FontWeight.Bold, letterSpacing = 6.sp, color = CanonText),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp),
                singleLine = true,
                shape = CanonItemShape
            )
            TextButton(onClick = onChangePhone, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(appTextFor(currentLanguage, "Изменить номер", "Номерҙы үҙгәртеү"), color = CanonGreen2, fontSize = LoginBody)
            }
        }
        // Та же карточка ошибки, что и в Telegram-потоке: текст + «что делать» + «Повторить».
        LoginErrorBanner(
            currentLanguage = currentLanguage,
            text = error,
            onRetry = onSmsPrimary,
        )
        Button(
            onClick = onSmsPrimary,
            enabled = !loading,   // гард двойного тапа; пустое поле ловит колбэк (показывает ошибку) — поведение 1:1
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .testTag(TAG_LOGIN_SMS_PRIMARY_BTN),
            shape = CanonItemShape,
            colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
            } else {
                Text(
                    text = if (step == 0) appTextFor(currentLanguage, "Получить код", "Код алыу") else appTextFor(currentLanguage, "Войти", "Инеү"),
                    fontWeight = FontWeight.Bold,
                    fontSize = LoginBody
                )
            }
        }
        LoginLoadingHint(
            visible = loading,
            text = if (step == 0) appTextFor(currentLanguage, "Отправляем код…", "Код ебәрәбеҙ…")
            else appTextFor(currentLanguage, "Проверяем код…", "Кодты тикшерәбеҙ…"),
        )
    }   // конец if (showPhone)
}

@Composable
internal fun LoginDivider(currentLanguage: AppLanguage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
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
            fontSize = LoginCaption,
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
    // Появление геро. Флаг включается ПОСЛЕ первой композиции — иначе анимация мертва (цель берётся
    // на первом кадре). Фото наезжает (1.06→1.0), бренд приходит первым, обещания — следом.
    var appear by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appear = true }
    val photoIn by animateFloatAsState(
        if (appear) 1f else 1.06f, tween(1600, easing = EaseOutExpo), label = "heroPhoto",
    )
    val brandIn by animateFloatAsState(
        if (appear) 1f else 0f, tween(620, delayMillis = 80, easing = EaseOutExpo), label = "heroBrand",
    )
    val featuresIn by animateFloatAsState(
        if (appear) 1f else 0f, tween(680, delayMillis = 300, easing = EaseOutExpo), label = "heroFeatures",
    )
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(bottomStart = 36.dp, bottomEnd = 36.dp))
            .background(CanonGreen2)
    ) {
        // matchParentSize (а не fillMaxSize): фото и вуали ТЯНУТСЯ под геро, но не решают его высоту —
        // высоту задаёт колонка с текстом, поэтому крупный шрифт растит блок, а не обрезается им.
        Image(
            painter = painterResource(R.drawable.login_salavat_yulaev_hero),
            contentDescription = null,
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer {
                    scaleX = photoIn
                    scaleY = photoIn
                },
            contentScale = ContentScale.Crop
        )
        Box(
            Modifier
                .matchParentSize()
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
                .matchParentSize()
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
                .padding(top = 16.dp, end = LoginGutter)
        )

        Surface(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(top = 16.dp, start = LoginGutter)
                .size(56.dp),
            shape = RoundedCornerShape(22.dp),
            color = Color.White,
            shadowElevation = CanonDepth.raised
        ) {
            Image(
                painter = painterResource(R.drawable.yuldash_logo),
                contentDescription = appTextFor(currentLanguage, "Логотип Юлдаш", "Юлдаш логотибы"),
                modifier = Modifier.padding(8.dp),
                contentScale = ContentScale.Fit
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                // Нижнее поле держит «воздух» под обещаниями: 120dp из них перекроет карточка формы,
                // остальное — открытый кадр. Высота геро = высота этой колонки (см. matchParentSize выше).
                .padding(top = 104.dp, start = LoginGutter, end = LoginGutter, bottom = 184.dp)
        ) {
            Column(
                modifier = Modifier.graphicsLayer {
                    alpha = brandIn
                    translationY = (1f - brandIn) * 24.dp.toPx()
                }
            ) {
                Text("Юлдаш", color = Color.White, fontSize = LoginDisplay, lineHeight = 56.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = appTextFor(currentLanguage, "Поездки между своими", "Үҙебеҙҙекеләр араһында юллашыу"),
                    color = Color.White.copy(alpha = 0.94f),
                    fontSize = LoginTitle,
                    lineHeight = 30.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            Spacer(Modifier.height(24.dp))
            Box(
                modifier = Modifier.graphicsLayer {
                    alpha = featuresIn
                    translationY = (1f - featuresIn) * 16.dp.toPx()
                }
            ) {
                LoginHeroFeatures(currentLanguage)
            }
        }

    }
}

// Переключатель языка — тот же сегментированный стиль, что на онбординге (белая «таблетка», активный чип зелёный).
@Composable
internal fun LoginHeroFeatures(currentLanguage: AppLanguage) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
            modifier = Modifier.size(56.dp),
            shape = RoundedCornerShape(14.dp),
            color = CanonGreen2.copy(alpha = 0.92f),
            shadowElevation = CanonDepth.raised
        ) {
            // Иконка декоративная: смысл несёт заголовок рядом, дублировать его для TalkBack не нужно.
            Icon(
                icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.padding(16.dp)
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = Color.White, fontSize = LoginBody, lineHeight = 23.sp, fontWeight = FontWeight.Bold)
            Text(body, color = Color.White.copy(alpha = 0.92f), fontSize = LoginCaption, lineHeight = 20.sp)
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
        shadowElevation = CanonDepth.raised
    ) {
        Row(Modifier.padding(4.dp)) {
            LoginLangChip("РУС", currentLanguage == AppLanguage.Ru) { if (currentLanguage != AppLanguage.Ru) onToggleLanguage() }
            LoginLangChip("БАШ", currentLanguage == AppLanguage.Ba) { if (currentLanguage != AppLanguage.Ba) onToggleLanguage() }
        }
    }
}

// Чип языка. Тач-цель ≥48dp через defaultMinSize: он задаёт МИНИМАЛЬНЫЕ КОНСТРЕЙНТЫ внутреннему
// clickable, поэтому нажимается вся «таблетка». minimumInteractiveComponentSize так не умеет — он
// раздвигает раскладку ВОКРУГ маленького clickable, и палец мимо букв не попадал.
// Смена языка — не мгновенный перескок цвета, а короткий перелив (animateColorAsState).
@Composable
private fun LoginLangChip(text: String, active: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(
        if (active) CanonGreen2 else Color.Transparent, tween(CanonMotion.NORMAL, easing = EaseOutExpo), label = "langChipBg",
    )
    val fg by animateColorAsState(
        if (active) Color.White else CanonMuted, tween(CanonMotion.NORMAL, easing = EaseOutExpo), label = "langChipFg",
    )
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 64.dp, minHeight = 48.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .clickable(onClickLabel = appText("Сменить язык: $text", "Телде алмаштырыу: $text"), onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = fg, fontSize = LoginCaption, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun SafetyFooter(currentLanguage: AppLanguage, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Shield, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(20.dp))
            Text(
                text = appTextFor(currentLanguage, "Безопасность поездок — наш приоритет", "Сәфәр хәүефһеҙлеге — беҙҙең өҫтөнлөк"),
                color = CanonGreen2,
                fontSize = LoginCaption,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }
        Text(
            text = appTextFor(currentLanguage, "Юлдаш заботится о тебе", "Юлдаш һинең хаҡта хәстәрләй"),
            color = CanonMuted,
            fontSize = LoginCaption,
            lineHeight = 20.sp,
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
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // 18+ стоит В ОДНОЙ строке с офертой, а не отдельной галочкой (разбор №2, 2026-08-03).
        // В оферте возрастное ограничение было, в приложении — нигде; для такси и денег это
        // первый вопрос стора. Отдельный чекбокс дал бы лишний тап на самом чувствительном
        // экране (тут люди и так отваливаются), а юридически значим факт: текст виден рядом
        // с кнопкой, и сам вход пишет согласие `age18` в реестр на сервере с датой.
        Text(
            text = appTextFor(
                currentLanguage,
                "Входя, ты подтверждаешь, что тебе есть 18 лет, и принимаешь",
                "Инеп, һин 18 йәшең тулғанын раҫлайһың һәм ҡабул итәһең:",
            ),
            color = CanonMuted,
            fontSize = LoginCaption,
            lineHeight = 20.sp,
            textAlign = TextAlign.Center
        )
        // Ссылки — тач-цель ≥48dp по высоте (вертикальный паддинг у самого clickable), а не «попади в 12sp».
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = appTextFor(currentLanguage, "Условия", "Шарттарҙы"),
                color = CanonGreen2,
                fontSize = LoginCaption,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(
                        onClickLabel = appTextFor(currentLanguage, "Открыть условия", "Шарттарҙы асыу"),
                    ) { open("https://yulbash.ru/terms/") }
                    .padding(horizontal = 8.dp, vertical = 16.dp)
            )
            Text("·", color = CanonMuted, fontSize = LoginCaption)
            Text(
                text = appTextFor(currentLanguage, "Политику конфиденциальности", "Конфиденциаллек сәйәсәтен"),
                color = CanonGreen2,
                fontSize = LoginCaption,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(
                        onClickLabel = appTextFor(currentLanguage, "Открыть политику конфиденциальности", "Конфиденциаллек сәйәсәтен асыу"),
                    ) { open("https://yulbash.ru/privacy/") }
                    .padding(horizontal = 8.dp, vertical = 16.dp)
            )
        }
    }
}
