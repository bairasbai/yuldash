package com.yuldash.app

// ============================ «Стать курьером Юлдаша» (C1) ============================
// Онбординг курьера: тёплые правила простыми словами (комиссия по ступени 3/8/15 прозрачно, что нельзя возить,
// ответственность, «купи и привези» с потолком 5000 ₽) + выбор транспорта (Легковой/Грузовой)
// + селфи с документом (сверка лица) + «кто пригласил» (из реферала). Состояния: форма → загрузка →
// на проверке (pending) → одобрено (approved) / отклонено (rejected + причина + повтор).
// Бэкенд: POST /courier/apply, GET /courier/application (методы в data/ApiClient.kt).
// courier_enabled=false на сервере → apply вернёт 403 «Курьер скоро» — показываем дружелюбно.

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeliveryDining
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.ApiException
import com.yuldash.app.data.CourierApplicationDto
import kotlinx.coroutines.launch

// ------------------------------ Внутреннее состояние экрана ------------------------------
private sealed interface CourierGateUi {
    object Loading : CourierGateUi
    object LoadError : CourierGateUi
    data class Form(val prefill: CourierApplicationDto?) : CourierGateUi
    data class Pending(val app: CourierApplicationDto) : CourierGateUi
    object Approved : CourierGateUi
    data class Rejected(val app: CourierApplicationDto) : CourierGateUi
}

/**
 * «Стать курьером Юлдаша»: правила → выбор транспорта → селфи → статус заявки.
 * onOpenCourier — после одобрения ведём в режим курьера (работа).
 */
// Документы пережимаем перед отправкой, но БЕРЕЖНЕЕ обычных фото: модератор должен разобрать
// серию, номер и даты в правах, а после сжатия «как для чата» мелкий шрифт плывёт. При этом
// снимок с камеры весит 5-6 МБ, и таксист с четырьмя документами отправлял двадцать мегабайт
// по сельской сети: минуты ожидания, дорогой трафик и потеря всего при обрыве
// (аудит 2026-08-08, волна 88). 1600 px по длинной стороне и качество 92 — читаемо и в разы легче.
private const val DOC_PHOTO_SIDE = 1600
private const val DOC_PHOTO_QUALITY = 92

@Composable
internal fun CourierOnboardingScreen(onBack: () -> Unit, onOpenCourier: () -> Unit) {
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf(false) }
    var application by remember { mutableStateOf<CourierApplicationDto?>(null) }
    var editing by rememberSaveable { mutableStateOf(false) }   // rejected → «Подать снова» открывает форму
    var reloadKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(reloadKey) {
        loading = true; loadError = false
        ApiClient.getCourierApplication()
            .onSuccess { application = it }
            .onFailure { loadError = true }
        loading = false
    }

    val app = application
    val ui: CourierGateUi = when {
        loading -> CourierGateUi.Loading
        loadError -> CourierGateUi.LoadError
        app == null -> CourierGateUi.Form(null)
        editing -> CourierGateUi.Form(app)
        app.status == "approved" -> CourierGateUi.Approved
        app.status == "rejected" -> CourierGateUi.Rejected(app)
        else -> CourierGateUi.Pending(app)
    }

    Scaffold(
        containerColor = CanonBg,
        topBar = { ScreenTopBar(appText("Курьер Юлдаш", "Юлдаш курьеры"), onBack) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            AnimatedContent(
                targetState = ui,
                transitionSpec = {
                    (fadeIn(tween(CanonMotion.NORMAL)) + slideInVertically(tween(CanonMotion.SLOW)) { it / 14 })
                        .togetherWith(fadeOut(tween(CanonMotion.QUICK)))
                },
                label = "courierGate",
            ) { state ->
                when (state) {
                    CourierGateUi.Loading -> Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) { AppLoading(appText("Проверяем твою заявку…", "Заявкаңды тикшерәбеҙ…")) }
                    CourierGateUi.LoadError -> Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Center) {
                        AppErrorState(onRetry = { reloadKey++ })
                    }
                    is CourierGateUi.Pending -> CourierPendingContent(state.app, onRefresh = { reloadKey++ }, onDone = onBack)
                    CourierGateUi.Approved -> CourierApprovedContent(onOpenCourier)
                    is CourierGateUi.Rejected -> CourierRejectedContent(state.app, onResubmit = { editing = true }, onDone = onBack)
                    is CourierGateUi.Form -> CourierApplyFormContent(
                        prefill = state.prefill,
                        onSubmitted = { submitted -> application = submitted; editing = false },
                    )
                }
            }
        }
    }
}

// ==================================== ФОРМА + ПРАВИЛА ====================================
@Composable
private fun CourierApplyFormContent(prefill: CourierApplicationDto?, onSubmitted: (CourierApplicationDto) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var transport by rememberSaveable { mutableStateOf(prefill?.transport?.takeIf { it.isNotBlank() } ?: "car") }
    var selfieUrl by rememberSaveable { mutableStateOf(prefill?.selfieUrl?.takeIf { it.isNotBlank() }) }
    var uploadingSelfie by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var submitError by remember { mutableStateOf<String?>(null) }

    // Кто и на чём везёт (аудит 2026-07-26): раньше «стать курьером» = селфи + тип транспорта.
    // Человеку доверяли чужую посылку, зная о нём меньше, чем о попутчике.
    var fullName by rememberSaveable { mutableStateOf(prefill?.fullName ?: "") }
    var carPlate by rememberSaveable { mutableStateOf(prefill?.carPlate ?: "") }
    var rulesAccepted by rememberSaveable { mutableStateOf(prefill?.rulesAccepted ?: false) }
    val identityOk = fullName.trim().split(" ").filter { it.isNotBlank() }.size >= 2 &&
        carPlate.isNotBlank() && rulesAccepted

    val canSubmit = selfieUrl != null && identityOk && !submitting
    val uploadFailMsg = appText("Не удалось загрузить фото, попробуй ещё раз", "Фотоны йөкләп булманы, тағы ҡабатла")
    val submitFailMsg = appText("Не получилось отправить. Проверь сеть и повтори.", "Ебәреп булманы. Селтәрҙе тикшереп ҡабатла.")

    val pickSelfie = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            uploadingSelfie = true
            scope.launch {
                val bytes = decodeToJpeg(ctx, uri, maxSize = DOC_PHOTO_SIDE, quality = DOC_PHOTO_QUALITY)
                val url = if (bytes != null) ApiClient.uploadPhoto(bytes).getOrNull() else null
                if (url != null) selfieUrl = url
                else android.widget.Toast.makeText(ctx, uploadFailMsg, android.widget.Toast.LENGTH_SHORT).show()
                uploadingSelfie = false
            }
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
    ) {
        // Герой: тёплое приглашение.
        item {
            Surface(color = CanonMint, shape = CanonCardShape, border = BorderStroke(1.dp, CanonGreen2.copy(alpha = 0.35f))) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = CircleShape, color = CanonGreen2) {
                            Icon(
                                Icons.Default.DeliveryDining,
                                contentDescription = appText("Курьер", "Курьер"),
                                tint = Color.White,
                                modifier = Modifier.padding(12.dp).size(24.dp),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            appText("Стать курьером Юлдаша", "Юлдаш курьеры булыу"),
                            color = CanonText, fontSize = DeliveryTitle, lineHeight = DeliveryTitleLine, fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(
                        appText(
                            "Развози посылки своим — по-соседски и без жадных процентов. Оставь заявку, мы проверим тебя и подключим к заказам.",
                            "Үҙебеҙҙекеләргә бандеролдәр илт — күршеләрсә һәм йыртҡыс процентһыҙ. Заявка ҡалдыр, беҙ һине тикшереп заказдарға тоташтырабыҙ.",
                        ),
                        color = CanonText, fontSize = DeliveryBody, lineHeight = DeliveryBodyLine,
                    )
                }
            }
        }
        // Правила простыми словами.
        item { SectionHeader(appText("Наши условия", "Беҙҙең шарттар"), appText("Просто и честно", "Ябай һәм намыҫлы")) }
        item {
            AppCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CourierRuleRow(
                        "💚",
                        appText("Комиссия — по ступени, прозрачно", "Комиссия — баҫҡыс буйынса, асыҡ"),
                        appText("Текущая ставка комиссии — в кабинете курьера, сумма по доставке — в её расчёте.", "Хәҙерге комиссия ставкаһы — курьер кабинетында, илтеү өсөн сумма — уның иҫәбендә."),
                    )
                    CourierRuleRow(
                        "🚫",
                        appText("Что нельзя возить", "Нимә илтергә ярамай"),
                        appText("Деньги, документы на предъявителя, лекарства без рецепта, скоропорт, оружие, запрещённое законом.", "Аҡса, күрһәтеүсегә документтар, рецептһыҙ дарыу, тиҙ боҙолған аҙыҡ, ҡорал, закон менән тыйылған."),
                    )
                    CourierRuleRow(
                        "🤝",
                        appText("Ответственность на курьере", "Яуаплылыҡ курьерҙа"),
                        appText("Ты отвечаешь за сохранность посылки от приёма до вручения по коду. Береги чужое как своё.", "Бандеролде алғандан код буйынса тапшырғанға тиклем һаҡлау — һинең өҫтөңдә. Кеше әйберен үҙеңдеке кеүек һаҡла."),
                    )
                    CourierRuleRow(
                        "🛒",
                        appText("«Купи и привези» — до 5000 ₽", "«Ал да килтер» — 5000 ₽-ға тиклем"),
                        appText("Можешь купить товар за клиента и привезти. Лимит суммы покупки — 5000 ₽, чтобы ты не рисковал крупным.", "Клиент өсөн тауар алып килтерә алаһың. Һатып алыу лимиты — 5000 ₽, ҙур аҡса менән тәүәккәлләмәҫ өсөн."),
                    )
                }
            }
        }
        // Комментарий админа после отклонения — над формой.
        if (prefill != null && prefill.rejectReason.isNotBlank()) {
            item {
                Surface(color = CanonDangerBg, shape = CanonItemShape, border = BorderStroke(1.dp, CanonDangerBorder)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(appText("Причина отказа", "Кире ҡағыу сәбәбе"), color = CanonRed, fontWeight = FontWeight.Bold, fontSize = DeliveryBody, lineHeight = DeliveryBodyLine)
                        Text(prefill.rejectReason, color = CanonText, fontSize = DeliveryBody, lineHeight = DeliveryBodyLine)
                    }
                }
            }
        }
        // Транспорт.
        item { SectionHeader(appText("Транспорт", "Транспорт"), appText("На чём возишь", "Нимәлә йөрөтәһең")) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CourierTransportChip(
                    title = appText("Легковой", "Еңел машина"),
                    subtitle = appText("посылки, покупки", "бандероль, һатып алыу"),
                    icon = Icons.Default.TwoWheeler,
                    selected = transport == "car",
                    onClick = { transport = "car" },
                    modifier = Modifier.weight(1f),
                )
                CourierTransportChip(
                    title = appText("Грузовой", "Йөк машинаһы"),
                    subtitle = appText("крупное, мебель", "ҙур, мебель"),
                    icon = Icons.Default.LocalShipping,
                    selected = transport == "cargo",
                    onClick = { transport = "cargo" },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        // Кто пригласил (из реферала).
        item {
            Surface(color = CanonMint, shape = CanonItemShape) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("🤝", fontSize = DeliveryTitle, lineHeight = DeliveryTitleLine)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(appText("Кто пригласил", "Кем саҡырҙы"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = DeliveryBody, lineHeight = DeliveryBodyLine)
                        DeliveryHint(
                            if (prefill?.invitedBy != null && prefill.invitedBy.isNotBlank())
                                appText("Тебя пригласил: ", "Һине саҡырҙы: ") + prefill.invitedBy
                            else appText("Возьмём из твоего реферального кода — так админ видит, кто за тебя поручился.", "Реферал кодыңдан алабыҙ — админ кем һинең өсөн яуаплы икәнен күрер."),
                        )
                    }
                }
            }
        }
        // Селфи с документом.
        item { SectionHeader(appText("Селфи с документом", "Документ менән селфи"), appText("Чтобы возил именно ты", "Нәҡ һин йөрөтөүең өсөн")) }
        item {
            DeliveryHint(
                appText("Сделай селфи с паспортом или правами в руках — так соседи знают, кому доверяют посылку (как в Яндекс.Доставке).",
                        "Ҡулыңда паспорт йәки права менән селфи яһа — шулай күршеләр бандеролде кемгә ышанғандарын белә (Яндекс.Доставка кеүек)."),
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        item { UploadTile(appText("Селфи с документом в руках", "Ҡулыңда документ менән селфи"), selfieUrl != null, uploadingSelfie) { pickSelfie.launch("image/*") } }
        item { DeliverySectionTitle(appText("О себе", "Үҙең тураһында")) }
        item {
            OutlinedTextField(
                value = fullName,
                onValueChange = { fullName = it.take(120) },
                label = { Text(appText("Фамилия и имя как в документе", "Документтағыса фамилия һәм исем")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            )
        }
        item {
            OutlinedTextField(
                value = carPlate,
                onValueChange = { carPlate = it.take(16).uppercase() },
                label = { Text(appText("Госномер машины", "Машинаның дәүләт номеры")) },
                placeholder = { Text("Х123УХ102") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            )
        }
        item {
            Surface(
                onClick = { rulesAccepted = !rulesAccepted },
                color = if (rulesAccepted) CanonMint else CanonSurface,
                shape = CanonItemShape,
                border = BorderStroke(1.dp, if (rulesAccepted) CanonGreen2 else CanonBorder),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Галочка согласия — единственный «замок» формы: она должна отзываться живо.
                    val agreeTint by animateColorAsState(if (rulesAccepted) CanonGreen2 else CanonMuted, tween(CanonMotion.QUICK), label = "agree-tint")
                    Icon(
                        if (rulesAccepted) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = agreeTint,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(appText("Согласен с правилами доставки", "Илтеү ҡағиҙәләре менән килешәм"),
                            color = CanonText, fontWeight = FontWeight.Bold, fontSize = DeliveryBody, lineHeight = DeliveryBodyLine)
                        DeliveryHint(
                            appText(
                                "Везу бережно, не вскрываю, запрещённое не беру. Если что-то пошло не так — говорю сразу, а не молчу.",
                                "Һаҡ илтәм, асмайым, тыйылғанды алмайым. Берәй хәл булһа — шунда уҡ әйтәм, өндәшмәй ҡалмайым.",
                            ),
                        )
                    }
                }
            }
        }
        // Ошибка отправки (текст сервера — например «Заявка уже на рассмотрении» или «Курьер скоро»).
        item {
            // Та же карточка ошибки, что на форме посылки: иконка + рамка, появляется и
            // схлопывается плавно. Раньше блок «прыгал» без exit-анимации и в своём стиле.
            DeliveryErrorCard(submitError)
        }
        item {
            AppButton(
                text = if (prefill != null) appText("Подать снова", "Ҡабат биреү") else appText("Отправить заявку", "Заявка ебәреү"),
                onClick = {
                    val url = selfieUrl ?: return@AppButton
                    val t = transport
                    submitting = true; submitError = null
                    scope.launch {
                        ApiClient.applyCourier(
                            t, url,
                            fullName = fullName.trim(), carPlate = carPlate.trim(),
                            rulesAccepted = rulesAccepted,
                        )
                            .onSuccess { onSubmitted(it) }
                            .onFailure { submitError = (it as? ApiException)?.message ?: submitFailMsg }
                        submitting = false
                    }
                },
                style = AppButtonStyle.Accent,
                icon = Icons.Default.DeliveryDining,
                loading = submitting,
                enabled = canSubmit,
            )
        }
        item {
            DeliveryHint(appText("Фото нужно только для проверки и не видно другим пользователям.", "Фото тик тикшереү өсөн, башҡаларға күренмәй."))
        }
    }
}

/** Чип выбора транспорта (Легковой/Грузовой) — иконка + заголовок + подпись, выделение рамкой. */
@Composable
private fun CourierTransportChip(
    title: String,
    subtitle: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Выбор транспорта — самый «кликаемый» элемент формы: подложка, рамка и надпись переезжают
    // плавно, иначе выбор читается как мигание, а не как отклик.
    val bg by animateColorAsState(if (selected) CanonMint else CanonSurface, tween(CanonMotion.QUICK), label = "transport-bg")
    val line by animateColorAsState(if (selected) CanonGreen2 else CanonBorder, tween(CanonMotion.QUICK), label = "transport-line")
    val ink by animateColorAsState(if (selected) CanonGreen2 else CanonText, tween(CanonMotion.QUICK), label = "transport-ink")
    val iconTint by animateColorAsState(if (selected) CanonGreen2 else CanonMuted, tween(CanonMotion.QUICK), label = "transport-icon")
    Surface(
        onClick = onClick,
        shape = CanonItemShape,
        color = bg,
        border = BorderStroke(if (selected) 2.dp else 1.dp, line),
        // heightIn, а не height: при системном «крупном шрифте» строки выше, и жёсткие 96dp
        // обрезали подпись. Оба чипа устроены одинаково, поэтому растут одинаково.
        modifier = modifier.heightIn(min = 96.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(24.dp))
            Text(
                title, color = ink, fontSize = DeliveryBody, lineHeight = DeliveryBodyLine, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle, color = CanonMuted, fontSize = DeliveryCaption, lineHeight = DeliveryCaptionLine,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Строка правила: эмодзи-значок + заголовок + пояснение. */
@Composable
private fun CourierRuleRow(emoji: String, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top) {
        Surface(color = CanonBg, shape = RoundedCornerShape(14.dp), modifier = Modifier.size(40.dp)) {
            Box(contentAlignment = Alignment.Center) { Text(emoji, fontSize = DeliveryTitle, lineHeight = DeliveryTitleLine) }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = CanonText, fontWeight = FontWeight.Bold, fontSize = DeliveryBody, lineHeight = DeliveryBodyLine)
            DeliveryHint(body)
        }
    }
}

// ==================================== СТАТУСЫ ЗАЯВКИ ====================================
@Composable
private fun CourierPendingContent(app: CourierApplicationDto, onRefresh: () -> Unit, onDone: () -> Unit) {
    CourierStatusScaffold(
        emoji = "⏳",
        tintBg = CanonWarnBg,
        tint = CanonWarn,
        title = appText("Заявка на проверке", "Заявка тикшереүҙә"),
        body = appText(
            "Мы уже смотрим твою заявку. Обычно это занимает меньше дня — пришлём уведомление, как только всё готово.",
            "Заявкаңды ҡарайбыҙ инде. Ғәҙәттә был бер көндән дә әҙерәк ваҡыт ала — әҙер булыу менән хәбәр ебәрәбеҙ.",
        ),
        summary = app,
        primaryLabel = appText("Обновить статус", "Статусты яңыртыу"),
        onPrimary = onRefresh,
        secondaryLabel = appText("Понятно", "Аңлашылды"),
        onSecondary = onDone,
    )
}

@Composable
private fun CourierApprovedContent(onOpenCourier: () -> Unit) {
    CourierStatusScaffold(
        emoji = "🎉",
        tintBg = CanonMint,
        tint = CanonGreen2,
        title = appText("Поздравляем — ты курьер Юлдаша!", "Ҡотлайбыҙ — һин Юлдаш курьеры!"),
        body = appText(
            "Заявка одобрена. Открывай «Режим курьера», включай «На линии» — и заказы начнут приходить.",
            "Заявка раҫланды. «Курьер режимын» ас, «Линияла» тумблерын ҡабыҙ — заказдар килә башлар.",
        ),
        summary = null,
        primaryLabel = appText("В режим курьера", "Курьер режимына"),
        onPrimary = onOpenCourier,
        secondaryLabel = null,
        onSecondary = null,
    )
}

@Composable
private fun CourierRejectedContent(app: CourierApplicationDto, onResubmit: () -> Unit, onDone: () -> Unit) {
    CourierStatusScaffold(
        emoji = "✋",
        tintBg = CanonDangerBg,
        tint = CanonRed,
        title = appText("Заявку пока отклонили", "Заявка әлегә кире ҡағылды"),
        body = if (app.rejectReason.isNotBlank())
            appText("Причина: ", "Сәбәбе: ") + app.rejectReason
        else appText(
            "Проверь селфи и транспорт — и подай снова. Мы поможем разобраться.",
            "Селфи менән транспортты тикшер ҙә ҡабат бир. Аңларға ярҙам итәбеҙ.",
        ),
        summary = app,
        primaryLabel = appText("Подать снова", "Ҡабат биреү"),
        onPrimary = onResubmit,
        secondaryLabel = appText("Позже", "Һуңыраҡ"),
        onSecondary = onDone,
    )
}

/** Общий каркас статус-экрана заявки: большой эмодзи-значок, заголовок, текст, сводка, кнопки. */
@Composable
private fun CourierStatusScaffold(
    emoji: String,
    tint: Color,
    tintBg: Color,
    title: String,
    body: String,
    summary: CourierApplicationDto?,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String?,
    onSecondary: (() -> Unit)?,
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        contentPadding = PaddingValues(vertical = 24.dp),
    ) {
        item {
            // Значок статуса вырастает ПОСЛЕ первого кадра: с visible = true сразу
            // AnimatedVisibility не проигрывает ничего, и «⏳» просто возникал бы на месте.
            var badgeShown by remember { mutableStateOf(false) }
            LaunchedEffect(emoji) { badgeShown = true }
            AnimatedVisibility(visible = badgeShown, enter = scaleIn(tween(CanonMotion.ENTRY)) + fadeIn(tween(CanonMotion.ENTRY))) {
                Surface(shape = CircleShape, color = tintBg, border = BorderStroke(1.dp, tint.copy(alpha = 0.3f))) {
                    Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
                        Text(emoji, fontSize = DeliveryDisplay, lineHeight = DeliveryDisplayLine)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        item {
            Text(title, color = CanonText, fontSize = DeliveryTitle, lineHeight = DeliveryTitleLine, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(body, color = CanonMuted, fontSize = DeliveryBody, lineHeight = DeliveryBodyLine, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
        }
        if (summary != null) {
            item {
                AppCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CourierSummaryRow(appText("Транспорт", "Транспорт"), courierTransportLabel(summary.transport))
                        CourierSummaryRow(
                            appText("Селфи", "Селфи"),
                            if (summary.selfieUrl.isNotBlank()) appText("загружено", "йөкләнгән") else appText("нет", "юҡ"),
                        )
                        summary.invitedBy?.takeIf { it.isNotBlank() }?.let {
                            CourierSummaryRow(appText("Пригласил", "Саҡырҙы"), it)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
        item {
            AppButton(primaryLabel, onPrimary, style = AppButtonStyle.Primary)
            if (secondaryLabel != null && onSecondary != null) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onSecondary, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(secondaryLabel, color = CanonMuted, fontSize = DeliveryBody, lineHeight = DeliveryBodyLine)
                }
            }
        }
    }
}

@Composable
private fun CourierSummaryRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = CanonMuted, fontSize = DeliveryCaption, lineHeight = DeliveryCaptionLine, modifier = Modifier.width(120.dp))
        Text(
            value.ifBlank { "—" }, color = CanonText, fontSize = DeliveryCaption, lineHeight = DeliveryCaptionLine,
            fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
        )
    }
}

/** Двуязычная подпись транспорта. */
@Composable
internal fun courierTransportLabel(transport: String): String = when (transport.lowercase()) {
    "car" -> appText("Легковой", "Еңел машина")
    "cargo" -> appText("Грузовой", "Йөк машинаһы")
    else -> transport
}
