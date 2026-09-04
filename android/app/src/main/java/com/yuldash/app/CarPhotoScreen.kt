package com.yuldash.app

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.BiText
import com.yuldash.app.data.CarPhotoDemandDto
import com.yuldash.app.data.CarPhotoDto
import com.yuldash.app.data.CarPhotoSlotDto
import kotlinx.coroutines.launch

/**
 * ФОТОКОНТРОЛЬ МАШИНЫ (580-ФЗ).
 *
 * Раз в две недели человек показывает, на чём он возит: такси — четыре стороны кузова и
 * салон, курьер — две стороны и багажник. До этого машину видели ОДИН раз, на фото при
 * регистрации, а дальше верили галочке «машина исправна».
 *
 * ТОН ЭКРАНА. Это не проверка на честность и не наказание — это «покажи машину, дело на две
 * минуты». Поэтому:
 *  • срок показан словами («осталось три дня»), а не красным счётчиком;
 *  • последствие названо ЗАРАНЕЕ, а не в момент, когда заказы уже не идут;
 *  • кадр, который не подошёл, объясняется тут же и одной фразой — человек стоит у машины
 *    и может переснять прямо сейчас;
 *  • зимой чистого кузова не просим и прямо об этом пишем: требование, которое невозможно
 *    выполнить, учит обходить правила.
 *
 * ПРИВАТНОСТЬ. Снимки лежат в закрытой части хранилища, видны только владельцу и админу,
 * и через 90 дней стираются сами. Об этом сказано на экране — человек должен знать, что
 * будет с фотографией его машины.
 */

private const val CAR_PHOTO_SIDE = 1600
private const val CAR_PHOTO_QUALITY = 88

@Composable
internal fun CarPhotoScreen(mode: String, onBack: () -> Unit) {
    // Экран показывает снимки машины из приватного хранилища — закрываем от скриншота,
    // как разбор спора и документы. Кадр кузова с номером у подъезда — это адрес человека,
    // и уносить его в сельский чат одним нажатием нельзя.
    SecureWindow()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val lang = LocalAppLanguage.current

    var state by remember { mutableStateOf<CarPhotoDto?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var busySlot by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    var shotError by remember { mutableStateOf<String?>(null) }
    var pickFor by remember { mutableStateOf<String?>(null) }
    // Какой контроль сейчас снимаем: плановый обход или требование по жалобе.
    var pickKind by remember { mutableStateOf("periodic") }

    val netFail = appText("Не получилось. Проверь сеть и повтори.",
                          "Килеп сыҡманы. Селтәрҙе тикшереп ҡабатла.")

    LaunchedEffect(reload) {
        loading = true; error = false
        ApiClient.getCarPhoto(mode)
            .onSuccess { state = it }
            .onFailure { error = true }
        loading = false
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        val slot = pickFor
        val kind = pickKind
        pickFor = null
        if (uri == null || slot == null) return@rememberLauncherForActivityResult
        busySlot = slot
        shotError = null
        scope.launch {
            val bytes = decodeToJpeg(ctx, uri, maxSize = CAR_PHOTO_SIDE, quality = CAR_PHOTO_QUALITY)
            if (bytes == null) {
                shotError = netFail
            } else {
                ApiClient.uploadCarPhoto(mode, slot, bytes, kind = kind)
                    .onSuccess { shot -> if (!shot.ok) shotError = carPhotoReasonText(lang, shot.reason) }
                    .onFailure { shotError = netFail }
                ApiClient.getCarPhoto(mode).onSuccess { state = it }
            }
            busySlot = null
        }
    }

    fun send(kind: String = "periodic") {
        if (sending) return
        sending = true; shotError = null
        scope.launch {
            ApiClient.submitCarPhoto(mode, kind)
                .onSuccess { state = it }
                .onFailure { shotError = netFail }
            sending = false
        }
    }

    val title = appText("Фотоконтроль машины", "Машина фотоконтроле")
    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(title, onBack) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).systemBarsPadding()) {
            when {
                loading && state == null -> CarPhotoLoading()
                error && state == null -> CarPhotoError(onRetry = { reload++ })
                else -> {
                    val data = state
                    if (data == null || !data.enabled || (!data.required && data.demand == null)) {
                        CarPhotoNothingToDo(data)
                    } else {
                        CarPhotoBody(
                            data = data,
                            busySlot = busySlot,
                            sending = sending,
                            shotError = shotError,
                            onPick = { slot, kind ->
                                pickFor = slot; pickKind = kind; picker.launch("image/*")
                            },
                            onSend = { kind -> send(kind) },
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────── Состояния ───────────────────────────

@Composable
private fun CarPhotoLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = CanonGreen2)
    }
}

@Composable
private fun CarPhotoError(onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(CanonSpace.lg),
        verticalArrangement = Arrangement.spacedBy(CanonSpace.lg, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = CanonMuted,
             modifier = Modifier.size(40.dp))
        Text(appText("Не удалось загрузить. Проверь сеть.", "Йөкләп булманы. Селтәрҙе тикшер."),
             color = CanonMuted, fontSize = 16.sp, lineHeight = 23.sp)
        AppButton(text = appText("Повторить", "Ҡабатларға"), onClick = onRetry,
                  style = AppButtonStyle.Secondary, fillWidth = false)
    }
}

/** Контроль сейчас не нужен: выключен или уже пройден. Спокойное «всё в порядке». */
@Composable
private fun CarPhotoNothingToDo(data: CarPhotoDto?) {
    val прошлый = shortDate(data?.lastPassedAt)
    Column(
        Modifier.fillMaxSize().padding(CanonSpace.lg),
        verticalArrangement = Arrangement.spacedBy(CanonSpace.md, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(color = CanonMint, shape = CircleShape) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CanonGreen2,
                 modifier = Modifier.padding(CanonSpace.lg).size(40.dp))
        }
        Text(appText("Сейчас ничего не нужно", "Хәҙер бер нәмә лә кәрәкмәй"),
             color = CanonText, fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold)
        Text(
            if (прошлый != null)
                appText("Машину показывали $прошлый. Мы напомним заранее, когда придёт время.",
                        "Машина $прошлый күрһәтелгән. Ваҡыты еткәс, алдан иҫкә төшөрөрбөҙ.")
            else
                appText("Мы напомним заранее, когда придёт время показать машину.",
                        "Машинаны күрһәтер ваҡыт еткәс, алдан иҫкә төшөрөрбөҙ."),
            color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp,
        )
    }
}

// ─────────────────────────── Основной ход ───────────────────────────

@Composable
private fun CarPhotoBody(
    data: CarPhotoDto,
    busySlot: String?,
    sending: Boolean,
    shotError: String?,
    onPick: (String, String) -> Unit,
    onSend: (String) -> Unit,
) {
    val готово = data.missing.isEmpty()
    val требование = data.demand
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(CanonSpace.lg),
        verticalArrangement = Arrangement.spacedBy(CanonSpace.md),
    ) {
        // Требование по жалобе — ВЫШЕ всего: у него сутки, а у планового обхода недели.
        if (требование != null) {
            item(key = "demand") { Box(Modifier.appearIn(0)) { CarPhotoDemandHeader(требование) } }
            if (требование.status == "waiting") {
                item(key = "demand-rules") {
                    Box(Modifier.appearIn(1)) { CarPhotoCleanRules(data.cleanRules) }
                }
                items(требование.slots, key = { "demand-" + it.code }) { слот ->
                    CarPhotoSlotRow(
                        slot = слот,
                        busy = busySlot == слот.code,
                        onClick = { onPick(слот.code, "complaint") },
                    )
                }
                item(key = "demand-send") {
                    AppButton(
                        text = appText("Отправить фото салона", "Салон фотоһын ебәрергә"),
                        onClick = { onSend("complaint") },
                        loading = sending,
                        enabled = требование.missing.isEmpty() && !sending,
                        icon = Icons.Default.CheckCircle,
                    )
                }
            }
        }
        if (!data.required) return@LazyColumn

        item(key = "head") { Box(Modifier.appearIn(0)) { CarPhotoHeader(data) } }

        if (data.status == "review") {
            item(key = "review") { Box(Modifier.appearIn(1)) { CarPhotoWaitingForUs() } }
            return@LazyColumn
        }

        if (data.rejectReason.isNotBlank()) {
            item(key = "reject") { Box(Modifier.appearIn(1)) { CarPhotoRejectNote(data.rejectReason) } }
        }
        item(key = "rules") { Box(Modifier.appearIn(1)) { CarPhotoRules(data) } }
        item(key = "label") {
            Text(appText("КАДРЫ", "КАДРҘАР"), color = CanonMuted, fontSize = 12.sp,
                 lineHeight = 17.sp, fontWeight = FontWeight.Bold)
        }
        items(data.slots, key = { it.code }) { слот ->
            CarPhotoSlotRow(
                slot = слот,
                busy = busySlot == слот.code,
                onClick = { onPick(слот.code, "periodic") },
            )
        }
        item(key = "shot-error") {
            AnimatedVisibility(
                visible = shotError != null,
                enter = fadeIn(tween(CanonMotion.NORMAL)) + expandVertically(tween(CanonMotion.NORMAL)),
                exit = fadeOut(tween(CanonMotion.QUICK)) + shrinkVertically(tween(CanonMotion.QUICK)),
            ) {
                Surface(color = CanonDangerBg, shape = CanonItemShape) {
                    Text(shotError.orEmpty(), color = CanonRed, fontSize = 14.sp, lineHeight = 20.sp,
                         modifier = Modifier.fillMaxWidth().padding(CanonSpace.md))
                }
            }
        }
        item(key = "send") {
            AppButton(
                text = appText("Отправить на проверку", "Тикшереүгә ебәрергә"),
                onClick = { onSend("periodic") },
                loading = sending,
                enabled = готово && !sending,
                icon = Icons.Default.CheckCircle,
            )
        }
        item(key = "privacy") {
            Text(
                appText(
                    "Снимки видим только мы и ты — они лежат в закрытой части и удаляются " +
                        "сами через ${data.keepDays} дней.",
                    "Һүрәттәрҙе беҙ һәм һин генә күрәбеҙ — улар ябыҡ өлөштә ята һәм " +
                        "${data.keepDays} көндән үҙҙәре юйыла.",
                ),
                color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp,
            )
        }
    }
}

/**
 * Требование по жалобе: пассажир написал, что было грязно.
 *
 * Тон здесь важнее всего на экране. Это НЕ обвинение и НЕ наказание: человек чаще всего
 * ни в чём не виноват, а фото закрывает вопрос за минуту в обе стороны. Поэтому сразу и
 * прямо сказано главное — работа не ограничена, и чистое фото снимает жалобу без следа.
 */
@Composable
private fun CarPhotoDemandHeader(demand: CarPhotoDemandDto) {
    val прислано = demand.status == "review"
    val фон = if (прислано) CanonMint else CanonWarnBg
    val чернила = if (прислано) CanonGreen2 else CanonWarn
    Surface(color = фон, shape = CanonCardShape) {
        Column(Modifier.fillMaxWidth().padding(CanonSpace.lg),
               verticalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (прислано) Icons.Default.HourglassEmpty else Icons.Default.PhotoCamera,
                     contentDescription = null, tint = чернила, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(CanonSpace.sm))
                Text(
                    if (прислано) appText("Фото салона у нас", "Салон фотоһы беҙҙә")
                    else appText("Покажи салон", "Салонды күрһәт"),
                    color = чернила, fontSize = 19.sp, lineHeight = 25.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                if (прислано)
                    appText("Посмотрим и ответим. Если всё в порядке — жалоба закроется без следа.",
                            "Ҡарап сығабыҙ һәм яуап бирәбеҙ. Бөтәһе лә тәртиптә булһа — ялыу эҙһеҙ ябыла.")
                else
                    appText(
                        "Пассажир написал, что в машине было грязно. Одно фото — и вопрос " +
                            "закрыт, без последствий. Заказы идут как обычно.",
                        "Юлаусы машинала бысраҡ булған тип яҙған. Бер фото — һорау ябыла, " +
                            "эҙемтәһеҙ. Заказдар ғәҙәттәгесә бара.",
                    ),
                color = чернила, fontSize = 14.sp, lineHeight = 20.sp,
            )
            if (!прислано) {
                Text(
                    if (demand.overdue)
                        appText("Сутки истекли — пришли фото, пока не разобрали без тебя.",
                                "Тәүлек үтте — фотоны ебәр, һинһеҙ ҡарап бөтмәгәндә.")
                    else
                        appText("Осталось ${pluralRu(demand.hoursLeft, "час", "часа", "часов")}.",
                                "${demand.hoursLeft} сәғәт ҡалды."),
                    color = чернила, fontSize = 12.sp, lineHeight = 17.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/** По каким пунктам смотрят салон. Все три видны на фото — в этом весь смысл списка. */
@Composable
private fun CarPhotoCleanRules(rules: List<BiText>) {
    if (rules.isEmpty()) return
    Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
        Column(Modifier.fillMaxWidth().padding(CanonSpace.md),
               verticalArrangement = Arrangement.spacedBy(CanonSpace.xs)) {
            Text(appText("Смотрим три вещи", "Өс нәмәгә ҡарайбыҙ"),
                 color = CanonText, fontSize = 14.sp, lineHeight = 20.sp,
                 fontWeight = FontWeight.Bold)
            rules.forEach { правило ->
                Row(verticalAlignment = Alignment.Top) {
                    Text("•", color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp)
                    Spacer(Modifier.width(CanonSpace.sm))
                    Text(appText(правило.ru, правило.ba), color = CanonMutedStrong,
                         fontSize = 14.sp, lineHeight = 20.sp)
                }
            }
            Text(
                appText("Запах по фото не проверить — и мы его не спрашиваем.",
                        "Еҫте фото буйынса тикшереп булмай — беҙ уны һорамайбыҙ ҙа."),
                color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp,
            )
        }
    }
}

/** Шапка: до какого числа и что будет, если опоздать. Цвет — по ступени лестницы. */
@Composable
private fun CarPhotoHeader(data: CarPhotoDto) {
    val (фон, чернила) = when (data.stage) {
        "blocked" -> CanonDangerBg to CanonRed
        "slow", "remind" -> CanonWarnBg to CanonWarn
        else -> CanonMint to CanonGreen2
    }
    val фонПлавно by animateColorAsState(фон, tween(CanonMotion.NORMAL), label = "carPhotoHeadBg")
    val срок = shortDate(data.dueAt?.take(10))
    val заголовок = when (data.stage) {
        "blocked" -> appText("Заказы на паузе", "Заказдар паузала")
        "slow" -> appText("Заказы уходят другим", "Заказдар башҡаларға китә")
        "remind" -> appText("Срок прошёл", "Ваҡыт үтте")
        else -> appText("Покажи машину", "Машинаны күрһәт")
    }
    val пояснение = when (data.stage) {
        "blocked" -> appText(
            "Пришли фото — и заказы вернутся сразу. Попутка работает как обычно.",
            "Фотоны ебәр — заказдар шунда уҡ ҡайта. Юлдаш ғәҙәттәгесә эшләй.")
        "slow" -> appText(
            "Просрочено ${pluralRu(data.lateDays, "день", "дня", "дней")}: пока фото нет, " +
                "заказы первыми видят другие. Пришлёшь — вернёшься сразу.",
            "${data.lateDays} көн үтте: фото булмағанда, заказдарҙы башҡалар беренсе күрә. " +
                "Ебәрһәң — шунда уҡ ҡайтаһың.")
        "remind" -> appText(
            "Пока это ни на что не влияет. Но через несколько дней заказы начнут уходить другим.",
            "Хәҙергә был бер нәмәгә лә тәьҫир итмәй. Әммә бер нисә көндән заказдар " +
                "башҡаларға китә башлай.")
        else -> {
            val дней = data.daysLeft
            if (дней <= 0) appText("Сегодня последний день.", "Бөгөн һуңғы көн.")
            else appText("Осталось ${pluralRu(дней, "день", "дня", "дней")}" +
                             (if (срок != null) ", до $срок." else "."),
                         "$дней көн ҡалды" + (if (срок != null) ", $срок тиклем." else "."))
        }
    }
    Surface(color = фонПлавно, shape = CanonCardShape) {
        Column(Modifier.fillMaxWidth().padding(CanonSpace.lg),
               verticalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.DirectionsCar, contentDescription = null, tint = чернила,
                     modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(CanonSpace.sm))
                Text(заголовок, color = чернила, fontSize = 19.sp, lineHeight = 25.sp,
                     fontWeight = FontWeight.Bold)
            }
            Text(пояснение, color = чернила, fontSize = 14.sp, lineHeight = 20.sp)
        }
    }
}

/** Кадры отправлены. Главное здесь — сказать, что человек уже свободен. */
@Composable
private fun CarPhotoWaitingForUs() {
    Surface(color = CanonMint, shape = CanonCardShape) {
        Column(Modifier.fillMaxWidth().padding(CanonSpace.lg),
               verticalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.HourglassEmpty, contentDescription = null, tint = CanonGreen2,
                     modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(CanonSpace.sm))
                Text(appText("Кадры у нас", "Кадрҙар беҙҙә"), color = CanonGreen2,
                     fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                appText(
                    "Посмотрим и ответим. Работать можно как обычно — ждать нас не нужно.",
                    "Ҡарап сығабыҙ һәм яуап бирәбеҙ. Эшләргә була — беҙҙе көтөп тороу кәрәкмәй.",
                ),
                color = CanonGreen2, fontSize = 14.sp, lineHeight = 20.sp,
            )
        }
    }
}

/** Почему не приняли прошлый раз — словами человека, который смотрел. */
@Composable
private fun CarPhotoRejectNote(reason: String) {
    Surface(color = CanonDangerBg, shape = CanonItemShape) {
        Column(Modifier.fillMaxWidth().padding(CanonSpace.md),
               verticalArrangement = Arrangement.spacedBy(CanonSpace.xs)) {
            Text(appText("Прошлые кадры не подошли", "Үткән кадрҙар тура килмәне"),
                 color = CanonRed, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold)
            Text(reason, color = CanonRed, fontSize = 14.sp, lineHeight = 20.sp)
        }
    }
}

/** Что именно смотрим: зима и первый контроль меняют требования. */
@Composable
private fun CarPhotoRules(data: CarPhotoDto) {
    val свет = appText("Снимай при свете и в фокусе: машину целиком, номер должен читаться.",
                       "Яҡтыла һәм фокуста төшөр: машина тулыһынса инһен, номер уҡылырлыҡ булһын.")
    val кузов =
        if (data.winter)
            appText("Зимой чистый кузов не требуем — только целостность: фары, бампер, ржавчина.",
                    "Ҡышын таҙа кузов талап итмәйбеҙ — бөтөнлөк кенә: фаралар, бампер, тут.")
        else
            appText("Кузов чистый настолько, чтобы было видно состояние.",
                    "Кузов хәлен күрерлек итеп таҙа булһын.")
    val салон = appText("Салон — как для пассажира: без мусора и личных вещей.",
                        "Салон — юлаусы өсөн кеүек: сүпһеҙ һәм шәхси әйберһеҙ.")
    val знаки = appText("В первый раз посмотрим ещё фонарь и «шашечки» — отдельный кадр не нужен.",
                        "Беренсе тапҡыр фонарь һәм «шашка»ларҙы ла ҡарайбыҙ — айырым кадр кәрәкмәй.")
    val документы = appText("Документы фотографировать не нужно — они у нас есть.",
                            "Документтарҙы төшөрөргә кәрәкмәй — улар беҙҙә бар.")
    val строки = listOfNotNull(свет, кузов, салон, знаки.takeIf { data.checkSigns }, документы)
    Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
        Column(Modifier.fillMaxWidth().padding(CanonSpace.md),
               verticalArrangement = Arrangement.spacedBy(CanonSpace.xs)) {
            строки.forEach { строка ->
                Row(verticalAlignment = Alignment.Top) {
                    Text("•", color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp)
                    Spacer(Modifier.width(CanonSpace.sm))
                    Text(строка, color = CanonMutedStrong, fontSize = 14.sp, lineHeight = 20.sp)
                }
            }
        }
    }
}

/** Один кадр: миниатюра или пустое место, название, подсказка или причина отказа. */
@Composable
private fun CarPhotoSlotRow(slot: CarPhotoSlotDto, busy: Boolean, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val принят = slot.url != null && slot.verdict == "ok"
    val отказ = slot.url != null && slot.verdict.isNotBlank() && slot.verdict != "ok"
    val имя = appText(slot.ru, slot.ba)
    val снято = appText("снято", "төшөрөлгән")
    val нужно = appText("нужно снять", "төшөрөргә кәрәк")
    val озвучка = "$имя. " + if (принят) снято else нужно
    val рамка by animateColorAsState(
        when {
            отказ -> CanonRed
            принят -> CanonGreen2
            else -> CanonBorder
        },
        tween(CanonMotion.NORMAL), label = "carPhotoSlotBorder",
    )
    Surface(
        color = CanonSurface,
        shape = CanonItemShape,
        border = BorderStroke(1.dp, рамка),
        modifier = Modifier.fillMaxWidth()
            .clickable(enabled = !busy, onClick = onClick)
            .semantics { contentDescription = озвучка },
    ) {
        Row(Modifier.fillMaxWidth().padding(CanonSpace.md),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(64.dp).clip(CanonFieldShape), contentAlignment = Alignment.Center) {
                val ссылка = slot.url
                if (ссылка != null) {
                    coil.compose.AsyncImage(
                        model = authedImageRequest(ctx, ссылка, ApiClient.currentToken().orEmpty()),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Surface(color = CanonMint, shape = CanonFieldShape,
                            modifier = Modifier.matchParentSize()) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.PhotoCamera, contentDescription = null,
                                 tint = CanonGreen2, modifier = Modifier.size(24.dp))
                        }
                    }
                }
                if (busy) {
                    // Затемнение поверх кадра: видно, что именно этот кадр сейчас грузится,
                    // а не «замер весь экран».
                    Surface(color = CanonScrim, shape = CanonFieldShape,
                            modifier = Modifier.matchParentSize()) {
                        Box(contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = CanonOnAccent,
                                                      modifier = Modifier.size(24.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.width(CanonSpace.md))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CanonSpace.xs)) {
                Text(имя, color = CanonText, fontSize = 16.sp, lineHeight = 23.sp,
                     fontWeight = FontWeight.Bold)
                Text(
                    if (отказ) carPhotoReasonText(LocalAppLanguage.current, slot.verdict)
                    else appText(slot.hintRu, slot.hintBa),
                    color = if (отказ) CanonRed else CanonMuted,
                    fontSize = 12.sp, lineHeight = 17.sp,
                )
            }
            if (принят) {
                Icon(Icons.Default.CheckCircle,
                     contentDescription = appText("Снято", "Төшөрөлгән"),
                     tint = CanonGreen2, modifier = Modifier.size(24.dp))
            }
        }
    }
}

/**
 * Почему кадр не подошёл — на языке человека, а не кодом сервера.
 *
 * Текст живёт в приложении, а не приходит с сервера, ровно по одной причине: язык
 * переключается кнопкой в профиле, и ответ, полученный по-русски час назад, обязан
 * прочитаться по-башкирски сразу после переключения. Язык передаём явно: эту же строку
 * собирает колбэк загрузки, где композиции уже нет.
 */
internal fun carPhotoReasonText(lang: AppLanguage, reason: String): String = when (reason) {
    "too_small" -> appTextFor(lang, "Кадр мелкий — ничего не разглядеть. Сними камерой поближе.",
                              "Кадр ваҡ — бер нәмә лә күренмәй. Камера менән яҡыныраҡ төшөр.")
    "too_dark" -> appTextFor(lang, "На кадре темно — ничего не разглядеть. Сними днём или под фонарём.",
                             "Кадр ҡараңғы — бер нәмә лә күренмәй. Көндөҙ йәки ут аҫтында төшөр.")
    "too_bright" -> appTextFor(lang, "Кадр пересвечен — деталей не видно. Встань так, чтобы солнце было сбоку.",
                               "Кадр артыҡ яҡты — детальдәр күренмәй. Ҡояш ян яҡта булырлыҡ итеп баҫ.")
    "blurry" -> appTextFor(lang, "Кадр размыт. Придержи телефон и сними ещё раз.",
                           "Кадр йәйелгән. Телефонды тотоп тор ҙа тағы төшөр.")
    "screenshot" -> appTextFor(lang, "Похоже на скриншот. Нужна фотография самой машины.",
                               "Экран һүрәтенә оҡшаған. Машинаның үҙен төшөрөргә кәрәк.")
    "stale" -> appTextFor(lang, "Снимок сделан давно. Покажи машину такой, какая она сейчас.",
                          "Һүрәт күптән төшөрөлгән. Машинаны хәҙерге хәлендә күрһәт.")
    "duplicate" -> appTextFor(lang, "Это фото уже присылали. Нужен новый снимок.",
                              "Был һүрәт ебәрелгән инде. Яңы кадр кәрәк.")
    else -> appTextFor(lang, "Кадр не подошёл. Сними ещё раз.",
                       "Кадр тура килмәне. Тағы бер тапҡыр төшөр.")
}
