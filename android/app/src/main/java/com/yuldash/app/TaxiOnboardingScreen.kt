package com.yuldash.app

// ============================ «Стать таксистом Юлдаша» (580-ФЗ) ============================
// Онбординг таксиста: тёплые правила простыми словами (комиссия 3→5→8%, оплата раз в неделю по СБП,
// лимит 8 часов на линии) + форма заявки (ИНН самозанятого, разрешение на такси, ОСАГО, возраст 20+,
// стаж 2+). Состояния: форма → загрузка → на проверке (pending) → одобрено / отклонено (+повторная подача).
// Бэкенд: POST /taxi/apply, GET /taxi/application (методы в data/ApiClient.kt).

import androidx.compose.foundation.layout.FlowRow
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.layout.heightIn
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocalTaxi
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.ApiException
import com.yuldash.app.data.DriverClassesDto
import com.yuldash.app.data.DriverClassDto
import com.yuldash.app.data.TaxiApplicationDto
import kotlinx.coroutines.launch

// ------------------------------ Внутреннее состояние экрана ------------------------------
private sealed interface TaxiGateUi {
    object Loading : TaxiGateUi
    object LoadError : TaxiGateUi
    data class Form(val prefill: TaxiApplicationDto?) : TaxiGateUi
    data class Pending(val app: TaxiApplicationDto) : TaxiGateUi
    object Approved : TaxiGateUi
    data class Rejected(val app: TaxiApplicationDto) : TaxiGateUi
}

/** "1990-05-01" → "01.05.1990" (для предзаполнения поля). Кривой ввод возвращаем как есть. */
private fun isoToRuDate(iso: String): String {
    val p = iso.take(10).split("-")
    return if (p.size == 3 && p[0].length == 4) "${p[2]}.${p[1]}.${p[0]}" else iso
}

/** "01.05.1990" (или недоформатированное) → ISO "1990-05-01", либо null если дата не настоящая. */
private fun ruDateToIso(text: String): String? {
    val d = text.filter(Char::isDigit)
    if (d.length != 8) return null
    val day = d.substring(0, 2).toIntOrNull() ?: return null
    val month = d.substring(2, 4).toIntOrNull() ?: return null
    val year = d.substring(4, 8).toIntOrNull() ?: return null
    return runCatching { java.time.LocalDate.of(year, month, day) }
        .getOrNull()?.toString()
}

/** Ввод даты «ДД.ММ.ГГГГ»: оставляем цифры и сами расставляем точки. */
private fun formatRuDateInput(raw: String): String {
    val d = raw.filter(Char::isDigit).take(8)
    return buildString {
        d.forEachIndexed { i, c ->
            append(c)
            if ((i == 1 || i == 3) && i < d.length - 1) append('.')
        }
    }
}

/** Полных лет на сегодня по ISO-дате рождения; null — дата не разобралась. */
internal fun taxiAgeYears(birthIso: String): Int? =
    runCatching { java.time.Period.between(java.time.LocalDate.parse(birthIso.take(10)), java.time.LocalDate.now()).years }.getOrNull()

// ==================================== ЭКРАН ====================================
/**
 * «Стать таксистом Юлдаша»: правила → форма → статус заявки.
 * Вход — из кабинета водителя (зона «Такси»). onOpenDriverCabinet — после одобрения ведём в кабинет.
 */
// ─────────────────────────── Типографика такси-водителя ───────────────────────────
// Пять ролей и ни одной лишней. Числа совпадают со «Сроками документов» (TaxiDocsScreens.kt) —
// водитель ходит между этими экранами, и текст не должен «прыгать» в размере.
// До этого здесь жили одиннадцать разных размеров вперемешку (11, 12, 13, 14, 15, 16, 17, 19,
// 20, 22), половина без межстрочного интервала — башкирский, который длиннее русского, слипался.
internal object TaxiType {
    val Hero = 24.sp        // заголовок экрана: один на экран
    val HeroLine = 30.sp
    val Title = 19.sp       // заголовок карточки/секции
    val TitleLine = 25.sp
    val Body = 16.sp        // читаемый текст, значения, названия пунктов
    val BodyLine = 23.sp
    val Caption = 14.sp     // подпись, пояснение, сноска, мета
    val CaptionLine = 20.sp
    val EmojiRow = 20.sp    // эмодзи-значок в строке правила (картинка, не текст)
    val EmojiHero = 44.sp   // эмодзи в круге на экране статуса
}

@Composable
internal fun TaxiOnboardingScreen(onBack: () -> Unit, onOpenDriverCabinet: () -> Unit) {
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf(false) }
    var application by remember { mutableStateOf<TaxiApplicationDto?>(null) }
    var editing by rememberSaveable { mutableStateOf(false) }   // rejected → «Подать снова» открывает форму с предзаполнением
    var reloadKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(reloadKey) {
        loading = true; loadError = false
        ApiClient.getMyTaxiApplication()
            .onSuccess { application = it }
            .onFailure { e ->
                // 404 = ещё не подавал → честная пустая форма; остальное — сетевая ошибка с «Повторить».
                if ((e as? ApiException)?.status == 404) application = null else loadError = true
            }
        loading = false
    }

    val app = application
    val ui: TaxiGateUi = when {
        loading -> TaxiGateUi.Loading
        loadError -> TaxiGateUi.LoadError
        app == null -> TaxiGateUi.Form(null)
        editing -> TaxiGateUi.Form(app)
        app.status == "approved" -> TaxiGateUi.Approved
        app.status == "rejected" -> TaxiGateUi.Rejected(app)
        else -> TaxiGateUi.Pending(app)
    }

    Scaffold(
        containerColor = CanonBg,
        topBar = { ScreenTopBar(appText("Такси Юлдаш", "Юлдаш такси"), onBack) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            AnimatedContent(
                targetState = ui,
                transitionSpec = {
                    (fadeIn(tween(CanonMotion.NORMAL)) + slideInVertically(tween(CanonMotion.SLOW)) { it / 14 })
                        .togetherWith(fadeOut(tween(CanonMotion.QUICK)))
                },
                label = "taxiGate",
            ) { state ->
                when (state) {
                    TaxiGateUi.Loading -> Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) { AppLoading(appText("Проверяем твою заявку…", "Заявкаңды тикшерәбеҙ…")) }
                    TaxiGateUi.LoadError -> Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Center) {
                        AppErrorState(onRetry = { reloadKey++ })
                    }
                    is TaxiGateUi.Pending -> TaxiPendingContent(state.app, onRefresh = { reloadKey++ }, onDone = onBack)
                    TaxiGateUi.Approved -> TaxiApprovedContent(onOpenDriverCabinet)
                    is TaxiGateUi.Rejected -> TaxiRejectedContent(state.app, onResubmit = { editing = true }, onDone = onBack)
                    is TaxiGateUi.Form -> TaxiApplyFormContent(
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
private fun TaxiApplyFormContent(prefill: TaxiApplicationDto?, onSubmitted: (TaxiApplicationDto) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var inn by rememberSaveable { mutableStateOf(prefill?.inn ?: "") }
    var permitNumber by rememberSaveable { mutableStateOf(prefill?.permitNumber ?: "") }
    var licenseYear by rememberSaveable { mutableStateOf(prefill?.licenseSinceYear?.takeIf { it > 0 }?.toString() ?: "") }
    var birthText by rememberSaveable { mutableStateOf(prefill?.birthDate?.takeIf { it.isNotBlank() }?.let { isoToRuDate(it) } ?: "") }
    var permitPhotoUrl by rememberSaveable { mutableStateOf(prefill?.permitPhotoUrl?.takeIf { it.isNotBlank() }) }
    var osagoUrl by rememberSaveable { mutableStateOf(prefill?.osagoUrl?.takeIf { it.isNotBlank() }) }
    // Проверки водителя, Уровень 1: селфи с правами (обязательно, сверка лица) + справка о несудимости (опц.).
    var selfieUrl by rememberSaveable { mutableStateOf(prefill?.selfieUrl?.takeIf { it.isNotBlank() }) }
    var criminalUrl by rememberSaveable { mutableStateOf(prefill?.criminalRecordUrl?.takeIf { it.isNotBlank() }) }
    var uploadingPermit by remember { mutableStateOf(false) }
    var uploadingOsago by remember { mutableStateOf(false) }
    var uploadingSelfie by remember { mutableStateOf(false) }
    var uploadingCriminal by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var submitError by remember { mutableStateOf<String?>(null) }
    // Класс машины больше НЕ выбирается — он считается на сервере из этих ответов
    // (backend/app/car_class.py). Поле оставлено только для совместимости со старым API.
    val carClass = "economy"
    var carColor by rememberSaveable { mutableStateOf("") }
    var carYearText by rememberSaveable { mutableStateOf("") }
    var seatsText by rememberSaveable { mutableStateOf("") }
    var carAc by rememberSaveable { mutableStateOf(false) }
    var carSedan by rememberSaveable { mutableStateOf(false) }
    var carLeather by rememberSaveable { mutableStateOf(false) }
    // Опции салона CSV-строкой: Set в Bundle не кладётся, а строка переживает поворот экрана.
    var carOptionsCsv by rememberSaveable { mutableStateOf("") }
    val carOptions = remember(carOptionsCsv) {
        carOptionsCsv.split(",").filter { it.isNotBlank() }.toSet()
    }

    // Локальная валидация — до похода на сервер (сервер продублирует).
    val currentYear = remember { java.time.LocalDate.now().year }
    val innDigits = inn.filter(Char::isDigit)
    val innOk = innDigits.length in 10..12
    val year = licenseYear.toIntOrNull()
    val yearOk = year != null && year in 1950..(currentYear - 2)   // стаж от 2 лет
    val birthIso = ruDateToIso(birthText)
    val age = birthIso?.let { taxiAgeYears(it) }
    val ageOk = age != null && age >= 20
    val photosOk = permitPhotoUrl != null && osagoUrl != null && selfieUrl != null   // селфи обязательно (сверка лица)
    // Сроки документов (580-ФЗ, аудит 2026-07-26). Раньше документы были ТОЛЬКО картинками:
    // одобрили в июле — человек возит с просроченным ОСАГО в декабре. Для НОВЫХ заявок даты
    // обязательны: без них контроль сроков невозможен, а «проверенный водитель» — пустое слово.
    var osagoUntil by rememberSaveable { mutableStateOf(prefill?.osagoUntil) }
    var permitUntil by rememberSaveable { mutableStateOf(prefill?.permitUntil) }
    var inspectionUntil by rememberSaveable { mutableStateOf(prefill?.inspectionUntil) }
    val datesOk = !osagoUntil.isNullOrBlank() && !permitUntil.isNullOrBlank() && !inspectionUntil.isNullOrBlank()
    val canSubmit = innOk && permitNumber.trim().isNotBlank() && yearOk && ageOk && photosOk && datesOk && !submitting

    val uploadFailMsg = appText("Не удалось загрузить фото, попробуй ещё раз", "Фотоны йөкләп булманы, тағы ҡабатла")
    val submitFailMsg = appText("Не получилось отправить. Проверь сеть и повтори.", "Ебәреп булманы. Селтәрҙе тикшереп ҡабатла.")

    val pickPermit = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            uploadingPermit = true
            scope.launch {
                val bytes = runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                val url = if (bytes != null) ApiClient.uploadPhoto(bytes).getOrNull() else null
                if (url != null) permitPhotoUrl = url
                else android.widget.Toast.makeText(ctx, uploadFailMsg, android.widget.Toast.LENGTH_SHORT).show()
                uploadingPermit = false
            }
        }
    }
    val pickOsago = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            uploadingOsago = true
            scope.launch {
                val bytes = runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                val url = if (bytes != null) ApiClient.uploadPhoto(bytes).getOrNull() else null
                if (url != null) osagoUrl = url
                else android.widget.Toast.makeText(ctx, uploadFailMsg, android.widget.Toast.LENGTH_SHORT).show()
                uploadingOsago = false
            }
        }
    }
    val pickSelfie = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            uploadingSelfie = true
            scope.launch {
                val bytes = runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                val url = if (bytes != null) ApiClient.uploadPhoto(bytes).getOrNull() else null
                if (url != null) selfieUrl = url
                else android.widget.Toast.makeText(ctx, uploadFailMsg, android.widget.Toast.LENGTH_SHORT).show()
                uploadingSelfie = false
            }
        }
    }
    val pickCriminal = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            uploadingCriminal = true
            scope.launch {
                val bytes = runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                val url = if (bytes != null) ApiClient.uploadPhoto(bytes).getOrNull() else null
                if (url != null) criminalUrl = url
                else android.widget.Toast.makeText(ctx, uploadFailMsg, android.widget.Toast.LENGTH_SHORT).show()
                uploadingCriminal = false
            }
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
    ) {
        // Герой: тёплое приглашение.
        item {
            Surface(color = CanonTaxiBg, shape = CanonCardShape, border = BorderStroke(1.dp, CanonTaxi.copy(alpha = 0.4f))) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = CircleShape, color = CanonTaxi) {
                            Icon(
                                Icons.Default.LocalTaxi,
                                contentDescription = appText("Такси", "Такси"),
                                tint = CanonTaxiInk,
                                modifier = Modifier.padding(12.dp).size(26.dp),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            appText("Стать таксистом Юлдаша", "Юлдаш таксисы булыу"),
                            color = CanonText, fontSize = TaxiType.Hero, lineHeight = TaxiType.HeroLine, fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(
                        appText(
                            "Вози своих — по-соседски и без жадных процентов. Заполни заявку, мы проверим документы и подключим тебя к заказам.",
                            "Үҙебеҙҙекеләрҙе йөрөт — күршеләрсә һәм йыртҡыс процентһыҙ. Заявканы тултыр, документтарҙы тикшереп, һине заказдарға тоташтырабыҙ.",
                        ),
                        color = CanonText, fontSize = TaxiType.Body, lineHeight = TaxiType.BodyLine,
                    )
                }
            }
        }
        // Правила простыми словами.
        item { SectionHeader(appText("Наши условия", "Беҙҙең шарттар"), appText("Просто и честно", "Ябай һәм намыҫлы")) }
        item {
            AppCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TaxiRuleRow(
                        "💚",
                        appText("Комиссия — втрое ниже, чем у Яндекса", "Комиссия — Яндексҡа ҡарағанда өс тапҡыр түбәнерәк"),
                        appText("Первый месяц 3%, второй — 5%, дальше — 8%. Остальное — твоё.", "Тәүге ай 3%, икенсеһе — 5%, артабан — 8%. Ҡалғаны — һинеке."),
                    )
                    TaxiCommissionSteps()
                    TaxiRuleRow(
                        "📅",
                        appText("Комиссия — раз в неделю по СБП", "Комиссия — аҙнаға бер СБП аша"),
                        appText("Никаких автосписаний: раз в неделю переводишь долг сам, по-человечески.", "Бер ниндәй автоалыу юҡ: аҙнаға бер бурысты үҙең күсерәһең, кешеләрсә."),
                    )
                    TaxiRuleRow(
                        "⏱",
                        appText("На линии — до 8 часов в день", "Линияла — көнөнә 8 сәғәткә тиклем"),
                        appText("Так требует закон, и так безопаснее для тебя и пассажиров.", "Закон шулай талап итә, һәм был һинең һәм пассажирҙар өсөн хәүефһеҙерәк."),
                    )
                }
            }
        }
        item {
            AppCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        appText("По закону (580-ФЗ) нужно", "Закон буйынса (580-ФЗ) кәрәк"),
                        color = CanonText, fontWeight = FontWeight.Bold, fontSize = TaxiType.Title, lineHeight = TaxiType.TitleLine,
                    )
                    TaxiRuleRow("🧾", appText("Самозанятость (ИНН)", "Үҙмәшғүллек (ИНН)"),
                        appText("Оформляется бесплатно в приложении «Мой налог» за 10 минут.", "«Мой налог» ҡушымтаһында 10 минутта бушлай яһала."))
                    TaxiRuleRow("📄", appText("Разрешение на такси", "Таксиға рөхсәт"),
                        appText("Выдаёт Минтранс через Госуслуги. Без него возить нельзя.", "Минтранс Госуслуги аша бирә. Унһыҙ йөрөтөргә ярамай."))
                    TaxiRuleRow("🛡", appText("Полис ОСАГО", "ОСАГО полисы"),
                        appText("Действующий, на твою машину.", "Ғәмәлдәге, үҙ машинаңа."))
                    TaxiRuleRow("🎂", appText("Возраст 20+ и стаж от 2 лет", "Йәш 20+ һәм 2 йылдан артыҡ стаж"),
                        appText("Это требование закона, проверим по заявке.", "Был закон талабы, заявка буйынса тикшерәбеҙ."))
                    // B7b-4: обязанность самозанятого — чек после каждой поездки.
                    TaxiRuleRow("🧾", appText("Чек после каждой поездки", "Һәр сәфәрҙән һуң чек"),
                        appText("После каждой поездки выдай чек в приложении «Мой налог» — это обязанность самозанятого. Мы мягко напомним.",
                            "Һәр сәфәрҙән һуң «Мой налог» ҡушымтаһында чек бир — был үҙмәшғүл бурысы. Беҙ йомшаҡ ҡына иҫкә төшөрөрбөҙ."))
                }
            }
        }
        // Комментарий админа после отклонения — над формой, чтобы было видно, что исправить.
        if (prefill != null && prefill.comment.isNotBlank()) {
            item {
                Surface(color = CanonDangerBg, shape = CanonItemShape, border = BorderStroke(1.dp, CanonDangerBorder)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(appText("Комментарий админа", "Админ комментарийы"), color = CanonRed, fontWeight = FontWeight.Bold, fontSize = TaxiType.Body, lineHeight = TaxiType.BodyLine)
                        Text(prefill.comment, color = CanonText, fontSize = TaxiType.Body, lineHeight = TaxiType.BodyLine)
                    }
                }
            }
        }
        // Форма.
        item { SectionHeader(appText("Заявка", "Ғариза"), appText("Пара минут — и на проверку", "Бер нисә минут — һәм тикшереүгә")) }
        item {
            OutlinedTextField(
                value = inn,
                onValueChange = { inn = it.filter(Char::isDigit).take(12) },
                label = { Text(appText("ИНН самозанятого", "Үҙмәшғүл ИНН-ы")) },
                supportingText = {
                    if (inn.isNotBlank() && !innOk) Text(appText("ИНН — 10–12 цифр", "ИНН — 10–12 һан"), color = CanonRed)
                    else Text(appText("10–12 цифр", "10–12 һан"), color = CanonMuted)
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            )
        }
        item {
            OutlinedTextField(
                value = permitNumber,
                onValueChange = { permitNumber = it.take(40) },
                label = { Text(appText("Номер разрешения на такси", "Такси рөхсәте номеры")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = licenseYear,
                    onValueChange = { licenseYear = it.filter(Char::isDigit).take(4) },
                    label = { Text(appText("Год получения прав", "Права алған йыл")) },
                    supportingText = {
                        if (licenseYear.length == 4 && !yearOk) Text(appText("Стаж — от 2 лет", "Стаж — 2 йылдан"), color = CanonRed)
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                )
                OutlinedTextField(
                    value = birthText,
                    onValueChange = { birthText = formatRuDateInput(it) },
                    label = { Text(appText("Дата рождения", "Тыуған көн")) },
                    placeholder = { Text("01.05.1990", color = CanonMuted) },
                    supportingText = {
                        if (birthText.filter(Char::isDigit).length == 8 && !ageOk)
                            Text(if (birthIso == null) appText("Проверь дату", "Датаны тикшер") else appText("Возраст — от 20 лет", "Йәш — 20-нән"), color = CanonRed)
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                )
            }
        }
        // Машина. Класс отсюда СЧИТАЕТСЯ — водитель его не выбирает. Раньше выбирал, и любой
        // мог поставить себе «Комфорт»: пассажир платил за Комфорт, приезжала Гранта.
        item { Text(appText("Твоя машина", "Һинең машинаң"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = TaxiType.Title, lineHeight = TaxiType.TitleLine) }

        // ЦВЕТ — ПЕРВЫМ ВОПРОСОМ. В Башкирии такси может быть только чёрным, белым или жёлтым
        // (закон РБ № 77-з ст. 15.2), и это отсеивает больше людей, чем всё остальное вместе.
        // Узнать об этом после заполнения всей анкеты — обидно и неуважительно к чужому времени.
        item {
            Text(
                appText("Какого цвета кузов?", "Кузов ниндәй төҫтә?"),
                color = CanonText, fontSize = TaxiType.Body, lineHeight = TaxiType.BodyLine,
                fontWeight = FontWeight.Bold,
            )
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TaxiColorOptions.forEach { c ->
                    TaxiChoiceChip(
                        text = appText(c.labelRu, c.labelBa),
                        selected = carColor == c.code,
                        onClick = { carColor = c.code },
                    )
                }
            }
        }
        item {
            AnimatedVisibility(
                visible = carColor == "other",
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Surface(shape = RoundedCornerShape(14.dp), color = CanonRed.copy(alpha = 0.08f),
                        border = BorderStroke(1.dp, CanonRed.copy(alpha = 0.4f))) {
                    Text(
                        appText(
                            "В Башкирии такси может быть только чёрным, белым или жёлтым. " +
                                "С другим цветом разрешение не дадут. Но попутка работает с любым — " +
                                "там это не требуется, и заработать можно уже сегодня.",
                            "Башҡортостанда такси ҡара, аҡ йәки һары ғына була ала. Башҡа төҫкә рөхсәт " +
                                "бирелмәй. Ләкин юлдаш (попутка) теләһә ниндәй төҫ менән эшләй — " +
                                "унда был талап юҡ, бөгөн үк эшләй алаһың.",
                        ),
                        color = CanonText, fontSize = TaxiType.Caption, lineHeight = TaxiType.CaptionLine,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = carYearText,
                    onValueChange = { v -> carYearText = v.filter(Char::isDigit).take(4) },
                    label = { Text(appText("Год выпуска", "Сығарылған йыл")) },
                    placeholder = { Text("2019", color = CanonMuted) },
                    supportingText = {
                        Text(appText("Из СТС. Без него доступен только Эконом",
                            "СТС-тан. Ул булмаһа, Эконом ғына"), color = CanonMuted)
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                )
                OutlinedTextField(
                    value = seatsText,
                    onValueChange = { v -> seatsText = v.filter(Char::isDigit).take(2) },
                    label = { Text(appText("Мест для пассажиров", "Пассажир урыны")) },
                    placeholder = { Text("4", color = CanonMuted) },
                    supportingText = {
                        val n = seatsText.toIntOrNull() ?: 0
                        if (n > 8) Text(appText("Больше 8 — это уже автобус, нужна лицензия",
                            "8-ҙән күп — был автобус, лицензия кәрәк"), color = CanonRed)
                        else Text(appText("6 и больше — Минивэн", "6 һәм күберәк — Минивэн"), color = CanonMuted)
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                )
            }
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TaxiChoiceChip(appText("Кондиционер работает", "Кондиционер эшләй"), carAc) { carAc = !carAc }
                TaxiChoiceChip(appText("Кузов — седан", "Кузов — седан"), carSedan) { carSedan = !carSedan }
                TaxiChoiceChip(appText("Салон кожаный", "Күн салон"), carLeather) { carLeather = !carLeather }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(14.dp), color = CanonTaxiBg, border = BorderStroke(1.dp, CanonTaxi)) {
                Text(
                    appText(
                        "Класс машины посчитаем сами — по этим ответам. Так у пассажира не будет " +
                            "сюрприза: заказал Комфорт — приедет Комфорт.",
                        "Машина класын үҙебеҙ иҫәпләйбеҙ — ошо яуаптар буйынса. Пассажирға сюрприз " +
                            "булмаясаҡ: Комфорт заказ иткән — Комфорт килә.",
                    ),
                    color = CanonText, fontSize = TaxiType.Caption, lineHeight = TaxiType.CaptionLine,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        // Опции салона — заказы, которые придут только тебе. Это не «класс», а конкретная
        // возможность: кресло, коляска, собака-проводник. Их часто ищут и почти нигде не находят.
        item { Text(appText("Что есть в салоне", "Салонда нимә бар"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = TaxiType.Title, lineHeight = TaxiType.TitleLine) }
        item {
            Text(
                appText("Отметь — и такие заказы будут приходить тебе. Их ищут часто, а находят редко.",
                    "Билдәлә — шундай заказдар һиңә киләсәк. Уларҙы йыш эҙләйҙәр, әммә һирәк табалар."),
                color = CanonMuted, fontSize = TaxiType.Caption, lineHeight = TaxiType.CaptionLine,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InstantOptions.forEach { opt ->
                    val on = opt.code in carOptions
                    TaxiChoiceChip(
                        text = "${opt.emoji} " + appText(opt.labelRu, opt.labelBa),
                        selected = on,
                        onClick = {
                            val next = if (on) carOptions - opt.code else carOptions + opt.code
                            carOptionsCsv = next.joinToString(",")
                        },
                    )
                }
            }
        }
        item { Text(appText("Документы (фото)", "Документтар (фото)"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = TaxiType.Title, lineHeight = TaxiType.TitleLine) }
        item { UploadTile(appText("Фото разрешения на такси", "Такси рөхсәте фотоһы"), permitPhotoUrl != null, uploadingPermit) { pickPermit.launch("image/*") } }
        item { UploadTile(appText("Фото полиса ОСАГО", "ОСАГО полисы фотоһы"), osagoUrl != null, uploadingOsago) { pickOsago.launch("image/*") } }
        // Сроки документов: по ним мы напомним заранее и снимем допуск, если срок всё же выйдет.
        item { Text(appText("Сроки документов", "Документтар ваҡыты"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = TaxiType.Title, lineHeight = TaxiType.TitleLine) }
        item {
            Text(
                appText("Мы напомним за две недели до истечения — чтобы такси не встало для тебя неожиданно.",
                        "Ваҡыт бөтөүенә ике аҙна ҡалғас иҫкә төшөрәбеҙ — такси көтмәгәндә туҡтамаһын."),
                color = CanonMuted, fontSize = TaxiType.Caption, lineHeight = TaxiType.CaptionLine, modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        item {
            TaxiDocDateField(
                appText("ОСАГО до", "ОСАГО ваҡыты"),
                appText("Выбери дату окончания полиса", "Полис бөтә торған датаны һайла"),
                osagoUntil,
            ) { osagoUntil = it }
        }
        item {
            TaxiDocDateField(
                appText("Разрешение на такси до", "Такси рөхсәте ваҡыты"),
                appText("Дата окончания разрешения", "Рөхсәт бөтә торған дата"),
                permitUntil,
            ) { permitUntil = it }
        }
        item {
            TaxiDocDateField(
                appText("Диагностическая карта до", "Диагностика картаһы ваҡыты"),
                appText("Техосмотр машины", "Машинаның техник ҡарауы"),
                inspectionUntil,
            ) { inspectionUntil = it }
        }
        item {
            Text(
                appText("Селфи с правами в руках — чтобы за рулём был именно ты (как в Яндекс.Такси).",
                        "Ҡулыңда права менән селфи — рулдә нәҡ һин булыуың өсөн (Яндекс.Такси кеүек)."),
                color = CanonMuted, fontSize = TaxiType.Caption, lineHeight = TaxiType.CaptionLine, modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        item { UploadTile(appText("Селфи с правами в руках", "Ҡулыңда права менән селфи"), selfieUrl != null, uploadingSelfie) { pickSelfie.launch("image/*") } }
        item {
            Text(
                appText("Справка о несудимости (Госуслуги) — по желанию, но повышает доверие соседей.",
                        "Судимлек юҡлығы тураһында белешмә (Госуслуги) — теләк буйынса, әммә күршеләр ышанысын арттыра."),
                color = CanonMuted, fontSize = TaxiType.Caption, lineHeight = TaxiType.CaptionLine, modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        item { UploadTile(appText("Справка о несудимости (по желанию)", "Судимлек юҡлығы белешмәһе (теләк буйынса)"), criminalUrl != null, uploadingCriminal) { pickCriminal.launch("image/*") } }
        // Ошибка отправки (текст сервера — например «стаж меньше 2 лет»).
        item {
            AnimatedVisibility(visible = submitError != null) {
                Surface(color = CanonDangerBg, shape = CanonItemShape) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = CanonRed, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(submitError ?: "", color = CanonText, fontSize = TaxiType.Body, lineHeight = TaxiType.BodyLine)
                    }
                }
            }
        }
        item {
            AppButton(
                text = if (prefill != null) appText("Подать снова", "Ҡабат биреү") else appText("Отправить заявку", "Заявка ебәреү"),
                onClick = {
                    val iso = birthIso ?: return@AppButton
                    val y = year ?: return@AppButton
                    submitting = true; submitError = null
                    scope.launch {
                        ApiClient.applyTaxi(
                            innDigits, permitNumber.trim(), iso, y,
                            permitPhotoUrl ?: "", osagoUrl ?: "", selfieUrl ?: "", criminalUrl ?: "", carClass,
                            osagoUntil = osagoUntil ?: "", permitUntil = permitUntil ?: "",
                            inspectionUntil = inspectionUntil ?: "",
                            // Характеристики машины: из них сервер считает класс.
                            carYear = carYearText.toIntOrNull(),
                            seats = seatsText.toIntOrNull(),
                            carColor = TaxiColorOptions.firstOrNull { it.code == carColor }?.serverValue ?: "",
                            carAc = carAc, carSedan = carSedan, carLeather = carLeather,
                            carOptions = carOptions.toList(),
                        )
                            .onSuccess { onSubmitted(it) }
                            .onFailure { submitError = (it as? ApiException)?.message ?: submitFailMsg }
                        submitting = false
                    }
                },
                style = AppButtonStyle.Accent,
                icon = Icons.Default.LocalTaxi,
                loading = submitting,
                enabled = canSubmit,
            )
        }
        item {
            Text(
                appText("Фото нужны только для проверки и не видны другим пользователям.", "Фотолар тик тикшереү өсөн, башҡаларға күренмәй."),
                color = CanonMuted, fontSize = TaxiType.Caption, lineHeight = TaxiType.CaptionLine,
            )
        }
    }
}

/** Чип выбора класса машины (Эконом/Комфорт) — тач-цель 76dp, выделение рамкой. */
@Composable
private fun TaxiClassChip(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = CanonItemShape,
        color = if (selected) CanonGreen2.copy(alpha = 0.08f) else CanonSurface,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) CanonGreen2 else CanonSurface),
        modifier = modifier.height(64.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.Center) {
            Text(title, color = if (selected) CanonGreen2 else CanonText, fontSize = TaxiType.Body, lineHeight = TaxiType.BodyLine, fontWeight = FontWeight.Bold)
            Text(subtitle, color = CanonMuted, fontSize = TaxiType.Caption, lineHeight = TaxiType.CaptionLine, maxLines = 1)
        }
    }
}


/** Цвет кузова. `serverValue` — то слово, по которому сервер узнаёт цвет (см. car_class.py).
 *  «Другой» шлём как есть: сервер честно ответит, что с таким цветом разрешение не дадут. */
internal data class TaxiColorOption(
    val code: String, val labelRu: String, val labelBa: String, val serverValue: String,
)

internal val TaxiColorOptions = listOf(
    TaxiColorOption("black", "Чёрный", "Ҡара", "чёрный"),
    TaxiColorOption("white", "Белый", "Аҡ", "белый"),
    TaxiColorOption("yellow", "Жёлтый", "Һары", "жёлтый"),
    TaxiColorOption("other", "Другой", "Башҡа", "другой"),
)

/** Чип-переключатель: цвет кузова, галочки про машину, опции салона.
 *  Тач-цель 48dp — палец в перчатке зимой в это попадает, в 32dp нет. */
@Composable
private fun TaxiChoiceChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(
        if (selected) CanonGreen2.copy(alpha = 0.10f) else CanonSurface,
        tween(CanonMotion.QUICK), label = "chipBg",
    )
    val border by animateColorAsState(
        if (selected) CanonGreen2 else CanonBorder, tween(CanonMotion.QUICK), label = "chipBorder",
    )
    val state = if (selected) appText("Выбрано", "Һайланған") else appText("Не выбрано", "Һайланмаған")
    Surface(
        onClick = onClick,
        shape = CanonTinyShape,
        color = bg,
        border = BorderStroke(if (selected) 2.dp else 1.dp, border),
        modifier = Modifier.heightIn(min = 48.dp).semantics {
            role = Role.Checkbox
            this.selected = selected
            stateDescription = state
        },
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text,
                color = if (selected) CanonGreen2 else CanonText,
                fontSize = TaxiType.Body, lineHeight = TaxiType.BodyLine,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}

/** Шкала комиссии 3% → 5% → 8%: три шага месяцев. */
@Composable
private fun TaxiCommissionSteps() {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        TaxiCommissionStep("3%", appText("1-й месяц", "1-се ай"), Modifier.weight(1f))
        TaxiCommissionStep("5%", appText("2-й месяц", "2-се ай"), Modifier.weight(1f))
        TaxiCommissionStep("8%", appText("дальше", "артабан"), Modifier.weight(1f))
    }
}

@Composable
private fun TaxiCommissionStep(value: String, label: String, modifier: Modifier = Modifier) {
    Surface(color = CanonMint, shape = RoundedCornerShape(14.dp), modifier = modifier) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(value, color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = TaxiType.Hero, lineHeight = TaxiType.HeroLine)
            Text(label, color = CanonMuted, fontSize = TaxiType.Caption, lineHeight = TaxiType.CaptionLine, maxLines = 1)
        }
    }
}

/** Строка правила: эмодзи-значок + заголовок + пояснение. */
@Composable
private fun TaxiRuleRow(emoji: String, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top) {
        Surface(color = CanonBg, shape = RoundedCornerShape(14.dp), modifier = Modifier.size(40.dp)) {
            Box(contentAlignment = Alignment.Center) { Text(emoji, fontSize = TaxiType.EmojiRow) }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = CanonText, fontWeight = FontWeight.Bold, fontSize = TaxiType.Body, lineHeight = TaxiType.BodyLine)
            Text(body, color = CanonMuted, fontSize = TaxiType.Caption, lineHeight = TaxiType.CaptionLine)
        }
    }
}

// ==================================== СТАТУСЫ ЗАЯВКИ ====================================
@Composable
private fun TaxiPendingContent(app: TaxiApplicationDto, onRefresh: () -> Unit, onDone: () -> Unit) {
    TaxiStatusScaffold(
        emoji = "⏳",
        tint = CanonWarn,
        tintBg = CanonWarnBg,
        title = appText("Заявка на проверке", "Заявка тикшереүҙә"),
        body = appText(
            "Мы уже смотрим твои документы. Обычно это занимает меньше дня — пришлём уведомление, как только всё готово.",
            "Документтарыңды ҡарайбыҙ инде. Ғәҙәттә был бер көндән дә әҙерәк ваҡыт ала — әҙер булыу менән хәбәр ебәрәбеҙ.",
        ),
        summary = app,
        primaryLabel = appText("Обновить статус", "Статусты яңыртыу"),
        onPrimary = onRefresh,
        secondaryLabel = appText("Понятно", "Аңлашылды"),
        onSecondary = onDone,
    )
}

@Composable
private fun TaxiApprovedContent(onOpenDriverCabinet: () -> Unit) {
    TaxiStatusScaffold(
        emoji = "🎉",
        tint = CanonGreen2,
        tintBg = CanonMint,
        title = appText("Поздравляем — ты таксист Юлдаша!", "Ҡотлайбыҙ — һин Юлдаш таксисы!"),
        body = appText(
            "Документы проверены. Включай «Я на линии» в кабинете водителя — и заказы начнут приходить.",
            "Документтар тикшерелде. Водитель кабинетында «Мин эштә» тумблерын ҡабыҙ — заказдар килә башлар.",
        ),
        summary = null,
        primaryLabel = appText("К кабинету такси", "Такси кабинетына"),
        onPrimary = onOpenDriverCabinet,
        secondaryLabel = null,
        onSecondary = null,
        extra = { TaxiMyClassesCard() },
    )
}

@Composable
private fun TaxiRejectedContent(app: TaxiApplicationDto, onResubmit: () -> Unit, onDone: () -> Unit) {
    TaxiStatusScaffold(
        emoji = "✋",
        tint = CanonRed,
        tintBg = CanonDangerBg,
        title = appText("Заявку пока отклонили", "Заявка әлегә кире ҡағылды"),
        body = if (app.comment.isNotBlank())
            appText("Комментарий админа: ", "Админ комментарийы: ") + app.comment
        else appText(
            "Проверь данные и фото документов — и подай снова. Мы поможем разобраться.",
            "Мәғлүмәтте һәм документ фотоларын тикшер ҙә ҡабат бир. Аңларға ярҙам итәбеҙ.",
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
private fun TaxiStatusScaffold(
    emoji: String,
    tint: androidx.compose.ui.graphics.Color,
    tintBg: androidx.compose.ui.graphics.Color,
    title: String,
    body: String,
    summary: TaxiApplicationDto?,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String?,
    onSecondary: (() -> Unit)?,
    // Дополнительный блок над кнопкой: одобренному водителю показываем его классы и набор
    // по району — это первое, что он хочет узнать после «поздравляем».
    extra: (@Composable () -> Unit)? = null,
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        contentPadding = PaddingValues(vertical = 24.dp),
    ) {
        item {
            Surface(shape = CircleShape, color = tintBg, border = BorderStroke(1.dp, tint.copy(alpha = 0.3f))) {
                Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) { Text(emoji, fontSize = TaxiType.EmojiHero) }
            }
            Spacer(Modifier.height(16.dp))
        }
        item {
            Text(title, color = CanonText, fontSize = TaxiType.Hero, lineHeight = TaxiType.HeroLine, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(body, color = CanonMuted, fontSize = TaxiType.Body, lineHeight = TaxiType.BodyLine, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
        }
        if (summary != null) {
            item {
                AppCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        TaxiSummaryRow(appText("ИНН", "ИНН"), summary.inn)
                        TaxiSummaryRow(appText("Разрешение", "Рөхсәт"), summary.permitNumber)
                        if (summary.licenseSinceYear > 0) TaxiSummaryRow(appText("Права с", "Права алынған йыл"), summary.licenseSinceYear.toString())
                        TaxiSummaryRow(
                            appText("Документы", "Документтар"),
                            if (summary.permitPhotoUrl.isNotBlank() && summary.osagoUrl.isNotBlank()) appText("загружены", "йөкләнгән") else appText("частично", "өлөшләтә"),
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
        if (extra != null) {
            item {
                extra()
                Spacer(Modifier.height(16.dp))
            }
        }
        item {
            AppButton(primaryLabel, onPrimary, style = AppButtonStyle.Primary)
            if (secondaryLabel != null && onSecondary != null) {
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onSecondary, modifier = Modifier.fillMaxWidth()) {
                    Text(secondaryLabel, color = CanonMuted, fontSize = TaxiType.Body, lineHeight = TaxiType.BodyLine)
                }
            }
        }
    }
}

// ==================================== МОИ КЛАССЫ ====================================
/** Коды причин от сервера → человеческий текст. Сервер шлёт коды, потому что подписи живут
 *  на двух языках и меняются чаще, чем логика. */
@Composable
private fun classMissingText(code: String): String = when (code) {
    "too_old" -> appText("машина старше нужного", "машина кәрәгенән иҫкерәк")
    "year_unknown" -> appText("не указан год выпуска", "сығарылған йыл күрһәтелмәгән")
    "no_ac" -> appText("нужен рабочий кондиционер", "эшләүсе кондиционер кәрәк")
    "clean_salon" -> appText("салон без чехлов и запаха", "салон чехолһыҙ, еҫһеҙ")
    "body" -> appText("кузов без вмятин и ржавчины", "кузов бөгөлмәгән, тутыҡмаған")
    "few_seats" -> appText("не хватает мест", "урын етмәй")
    "not_sedan" -> appText("нужен седан", "седан кәрәк")
    "color_business" -> appText("нужен чёрный или белый кузов", "ҡара йәки аҡ кузов кәрәк")
    "no_leather" -> appText("нужен кожаный салон", "күн салон кәрәк")
    "not_verified_premium" -> appText("нужен осмотр машины — напиши нам", "машинаны ҡарау кәрәк — беҙгә яҙ")
    "too_many_seats" -> appText("больше 8 мест — нужна лицензия на автобус", "8-ҙән күп урын — автобус лицензияһы кәрәк")
    else -> code
}

/**
 * Классы водителя: что доступно машине, что он берёт, чего не хватает до остальных
 * и сколько водителей уже набралось в его районе.
 *
 * Счётчик набора здесь не ради статистики: видя «не хватает одного», человек сам зовёт
 * знакомого — и класс открывается им обоим. В попутках «между своими» это работает сильнее
 * любой рекламы.
 */
@Composable
private fun TaxiMyClassesCard() {
    var data by remember { mutableStateOf<DriverClassesDto?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(reloadKey) {
        loading = true; failed = false
        ApiClient.getMyTaxiClasses().onSuccess { data = it }.onFailure { failed = true }
        loading = false
    }

    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                appText("Какие заказы тебе приходят", "Һиңә ниндәй заказдар килә"),
                color = CanonText, fontSize = TaxiType.Title, lineHeight = TaxiType.TitleLine,
                fontWeight = FontWeight.Bold,
            )
            when {
                loading -> Text(
                    appText("Загружаем…", "Йөкләйбеҙ…"),
                    color = CanonMuted, fontSize = TaxiType.Caption, lineHeight = TaxiType.CaptionLine,
                )
                failed -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        appText("Не получилось загрузить.", "Йөкләп булманы."),
                        color = CanonMuted, fontSize = TaxiType.Caption, lineHeight = TaxiType.CaptionLine,
                    )
                    TextButton(onClick = { reloadKey++ }) {
                        Text(appText("Повторить", "Ҡабатларға"), color = CanonGreen2,
                             fontSize = TaxiType.Body, lineHeight = TaxiType.BodyLine)
                    }
                }
                else -> {
                    val d = data
                    if (d == null || d.classes.isEmpty()) {
                        Text(
                            appText("Пока только Эконом — это нормальный старт.",
                                "Әлегә Эконом ғына — был ғәҙәти башланғыс."),
                            color = CanonMuted, fontSize = TaxiType.Caption, lineHeight = TaxiType.CaptionLine,
                        )
                    } else {
                        if (d.place.isNotBlank()) {
                            Text(
                                appText("Твой район: ${d.place}", "Һинең районың: ${d.place}"),
                                color = CanonMuted, fontSize = TaxiType.Caption, lineHeight = TaxiType.CaptionLine,
                            )
                        }
                        d.classes.forEach { c ->
                            val info = InstantClasses.firstOrNull { it.category == c.category }
                            val title = info?.let { appText(it.titleRu, it.titleBa) } ?: c.carClass
                            TaxiClassRow(
                                title = title,
                                cls = c,
                                enabled = !busy,
                                onToggle = {
                                    if (!busy && c.available) {
                                        busy = true
                                        val next = d.classes.filter { it.enabled }.map { it.carClass }.toMutableSet()
                                        if (c.enabled) next.remove(c.carClass) else next.add(c.carClass)
                                        scope.launch {
                                            ApiClient.setMyTaxiClasses(classesEnabled = next.toList())
                                                .onSuccess { data = it }
                                            busy = false
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Одна строка класса: включён / доступен, чего не хватает, сколько набралось в районе. */
@Composable
private fun TaxiClassRow(title: String, cls: DriverClassDto, enabled: Boolean, onToggle: () -> Unit) {
    val on = cls.enabled && cls.available
    val bg by animateColorAsState(
        if (on) CanonGreen2.copy(alpha = 0.08f) else CanonSurface,
        tween(CanonMotion.QUICK), label = "clsRowBg",
    )
    Surface(
        onClick = onToggle,
        shape = CanonItemShape,
        color = bg,
        border = BorderStroke(if (on) 2.dp else 1.dp, if (on) CanonGreen2 else CanonBorder),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title, color = if (on) CanonGreen2 else CanonText,
                    fontSize = TaxiType.Body, lineHeight = TaxiType.BodyLine,
                    fontWeight = FontWeight.Bold,
                )
                // Причины считаем ДО when: appText — @Composable, внутри joinToString его не вызвать.
                val missingText = cls.missing.take(2).map { classMissingText(it) }.joinToString(" · ")
                when {
                    // Машина не подходит — говорим ЧЕМ именно, а не «недоступно».
                    !cls.available -> Text(
                        missingText,
                        color = CanonMuted, fontSize = TaxiType.Caption, lineHeight = TaxiType.CaptionLine,
                    )
                    // Первому в районе — статус, а не ноль. «Набралось 0 из 3» демотивирует.
                    cls.first -> Text(
                        appText("Ты будешь первым здесь", "Һин бында беренсе булаһың"),
                        color = CanonTaxi, fontSize = TaxiType.Caption, lineHeight = TaxiType.CaptionLine,
                        fontWeight = FontWeight.Bold,
                    )
                    !cls.open -> Text(
                        appText("Нас ${cls.driversHave} из ${cls.driversNeed} — позови знакомого, и класс откроется",
                            "Беҙ ${cls.driversHave}/${cls.driversNeed} — танышыңды саҡыр, класс асыла"),
                        color = CanonTaxi, fontSize = TaxiType.Caption, lineHeight = TaxiType.CaptionLine,
                    )
                    on -> Text(
                        appText("Заказы приходят", "Заказдар килә"),
                        color = CanonMuted, fontSize = TaxiType.Caption, lineHeight = TaxiType.CaptionLine,
                    )
                    else -> Text(
                        appText("Выключен — заказы не приходят", "Һүндерелгән — заказдар килмәй"),
                        color = CanonMuted, fontSize = TaxiType.Caption, lineHeight = TaxiType.CaptionLine,
                    )
                }
            }
            if (cls.available) {
                Text(
                    if (on) "✓" else "○",
                    color = if (on) CanonGreen2 else CanonMuted,
                    fontSize = TaxiType.Title, lineHeight = TaxiType.TitleLine,
                )
            }
        }
    }
}

@Composable
private fun TaxiSummaryRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = CanonMuted, fontSize = TaxiType.Caption, lineHeight = TaxiType.CaptionLine, modifier = Modifier.width(120.dp))
        Text(value.ifBlank { "—" }, color = CanonText, fontSize = TaxiType.Caption, lineHeight = TaxiType.CaptionLine, fontWeight = FontWeight.Bold)
    }
}
