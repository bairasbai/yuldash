package com.yuldash.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.ApiException
import com.yuldash.app.data.TaxiApplicationDto
import kotlinx.coroutines.launch

/*
 * ════════════════════════════════════════════════════════════════════════════
 *  580-ФЗ, честный минимум: сроки документов + готовность к работе
 * ════════════════════════════════════════════════════════════════════════════
 *  Раньше проверка документов была РАЗОВОЙ: одобрили в июле — человек считался годным вечно,
 *  в декабре возил с просроченным ОСАГО, а Юлдаш называл его «проверенным» (аудит 2026-07-26).
 *
 *  Два экрана:
 *   • [TaxiDocumentsScreen] — сроки ОСАГО / разрешения / диагностической карты. Продлил документ →
 *     обновил дату здесь, БЕЗ пере-подачи заявки (иначе человек терял допуск, пока идёт модерация:
 *     наказание за законопослушность).
 *   • [PretripCheckScreen] — подтверждение готовности на сегодня. Честно называем это
 *     самодекларацией, а не медосмотром: медцентра у платформы нет. Смысл в осознанном действии
 *     и в записи, которая остаётся.
 */

// Сколько дней до истечения считаем тревогой (совпадает с docs_warn_days на сервере).
private const val DOCS_WARN_DAYS = 14

/*
 * ── Сетка ──────────────────────────────────────────────────────────────────
 * Все отступы этих двух экранов берутся ТОЛЬКО отсюда и кратны 4dp — тогда
 * вертикальный ритм ровный, а не «на глаз» (было вперемешку 10/14/18/22dp).
 */
private val ScreenPad = 16.dp   // боковые поля экрана
private val CardPad = 20.dp     // внутри крупной карточки-шапки
private val CardPadLg = 24.dp   // внутри «праздничной» карточки (готовность подтверждена)
private val ItemPad = 16.dp     // внутри строки-карточки
private val GapXs = 4.dp        // заголовок ↔ подпись
private val GapS = 8.dp         // мелкий зазор
private val GapM = 12.dp        // между карточками и блоками внутри карточки
private val GapL = 16.dp        // иконка-кружок ↔ текст в шапке
private val BottomPad = 32.dp   // воздух под последним элементом списка

/*
 * ── Типографика ────────────────────────────────────────────────────────────
 * Ровно четыре размера, у каждого одна роль. Больше не заводим: экран
 * законный и скучный, читаться должен по иерархии, а не по разнобою кеглей.
 */
private val TitleSize = 18.sp   // заголовок карточки (один на карточку)
private val BodySize = 15.sp    // название документа/пункта и значение (дата)
private val SubSize = 13.sp     // пояснение под заголовком, текст плашек
private val CapSize = 12.sp     // служебное: подпись секции, пилюля, сноска, счётчик
private val TitleLead = 24.sp
private val BodyLead = 20.sp
private val SubLead = 18.sp
private val CapLead = 16.sp

/*
 * ── contentDescription ─────────────────────────────────────────────────────
 * Озвучиваем только те иконки, которые НЕСУТ смысл сами (галочка «отмечено»,
 * состояние поля даты, значок ошибки). Иконки, чей смысл слово в слово написан
 * рядом текстом, помечены null — иначе TalkBack читает одно и то же дважды.
 */

// ─────────────────────────── Документы и сроки ───────────────────────────

@Composable
internal fun TaxiDocumentsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val lang = LocalAppLanguage.current

    var app by remember { mutableStateOf<TaxiApplicationDto?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var errText by remember { mutableStateOf<String?>(null) }

    val savedMsg = appText("Дата сохранена", "Дата һаҡланды")
    val errFallback = appText("Не получилось сохранить. Проверь сеть.", "Һаҡлап булманы. Селтәрҙе тикшер.")

    LaunchedEffect(reload) {
        loading = true; error = false
        ApiClient.getMyTaxiApplication()
            .onSuccess { app = it }
            .onFailure { error = true }
        loading = false
    }

    fun save(field: String, isoDate: String) {
        if (busy) return
        busy = true; errText = null; msg = null
        scope.launch {
            val r = when (field) {
                "osago" -> ApiClient.updateTaxiDocuments(osagoUntil = isoDate)
                "permit" -> ApiClient.updateTaxiDocuments(permitUntil = isoDate)
                else -> ApiClient.updateTaxiDocuments(inspectionUntil = isoDate)
            }
            r.onSuccess { app = it; msg = savedMsg }
                .onFailure { errText = (it as? ApiException)?.message ?: errFallback }
            busy = false
        }
    }

    Scaffold(
        containerColor = CanonBg,
        topBar = { ScreenTopBar(appText("Документы и сроки", "Документтар һәм ваҡыттар"), onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = ScreenPad),
            verticalArrangement = Arrangement.spacedBy(GapM),
            contentPadding = PaddingValues(top = GapS, bottom = BottomPad),
        ) {
            val a = app
            when {
                loading && a == null -> item(key = "skeleton") { TaxiDocsSkeleton() }
                error && a == null -> item(key = "error") { AppErrorState(onRetry = { reload++ }) }
                a == null -> item(key = "empty") {
                    AppEmptyState(
                        title = appText("Заявка не подана", "Заявка бирелмәгән"),
                        text = appText(
                            "Сроки документов появятся здесь, когда ты подашь заявку таксиста.",
                            "Документ ваҡыттары такси заявкаһын биргәс бында күренәсәк.",
                        ),
                        icon = Icons.Default.Description,
                        actionLabel = appText("Вернуться назад", "Кире ҡайтыу"),
                        onAction = onBack,
                    )
                }
                else -> {
                    // Шапка и ответ на действие — одним блоком: плашка «сохранено/не вышло»
                    // раскрывается прямо под статусом, где взгляд уже находится, и не оставляет
                    // после себя пустой дыры в ритме списка, когда её нет.
                    item(key = "hero") {
                        Column(Modifier.appearIn(0)) {
                            TaxiDocsHeader(a)
                            InlineNotice(text = errText ?: msg, ok = errText == null)
                        }
                    }
                    item(key = "label") { SmallSectionLabel(appText("ДОКУМЕНТЫ", "ДОКУМЕНТТАР")) }
                    item(key = "osago") {
                        Box(Modifier.appearIn(1)) {
                            TaxiDocRow(
                                icon = Icons.Default.Shield,
                                title = appText("ОСАГО", "ОСАГО"),
                                hint = appText("Страховка — без неё при ДТП платить некому", "Страховка — ДТП булһа түләүсе булмай"),
                                iso = a.osagoUntil, busy = busy, lang = lang, ctx = ctx,
                                onPicked = { save("osago", it) },
                            )
                        }
                    }
                    item(key = "permit") {
                        Box(Modifier.appearIn(2)) {
                            TaxiDocRow(
                                icon = Icons.Default.Description,
                                title = appText("Разрешение на такси", "Такси рөхсәте"),
                                hint = appText("Номер в реестре перевозчиков", "Йөрөтөүселәр реестрындағы номер"),
                                iso = a.permitUntil, busy = busy, lang = lang, ctx = ctx,
                                onPicked = { save("permit", it) },
                            )
                        }
                    }
                    item(key = "inspection") {
                        Box(Modifier.appearIn(3)) {
                            TaxiDocRow(
                                icon = Icons.Default.DirectionsCar,
                                title = appText("Диагностическая карта", "Диагностика картаһы"),
                                hint = appText("Техосмотр машины", "Машинаның техник ҡарауы"),
                                iso = a.inspectionUntil, busy = busy, lang = lang, ctx = ctx,
                                onPicked = { save("inspection", it) },
                            )
                        }
                    }
                    item(key = "footnote") {
                        FootnoteRow(
                            appText(
                                "Мы напомним за две недели и ещё раз за три дня. Если срок всё же выйдет — такси встанет на паузу, а попутки продолжат работать. Обновишь дату — допуск вернётся сразу.",
                                "Ике аҙна алдан һәм тағы өс көн ҡалғас иҫкә төшөрәбеҙ. Ваҡыт үтһә — такси паузаға китә, ә юлдаш сәфәрҙәре эшләй бирә. Датаны яңыртҡас — рөхсәт шунда уҡ ҡайта.",
                            )
                        )
                    }
                }
            }
        }
    }
}

/** Скелетон в форме будущего экрана: шапка + три строки документов (не голый спиннер). */
@Composable
private fun TaxiDocsSkeleton() {
    Column(verticalArrangement = Arrangement.spacedBy(GapM)) {
        SkeletonCard(lines = 1)
        repeat(3) { SkeletonCard(lines = 2) }
    }
}

/** Тихая подпись-разделитель секции. Заглавные буквы + разрядка — держит структуру без лишней карточки. */
@Composable
private fun SmallSectionLabel(text: String) {
    Text(
        text,
        color = CanonMuted,
        fontSize = CapSize,
        lineHeight = CapLead,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
        modifier = Modifier.padding(start = GapXs, top = GapXs),
    )
}

/** Сноска: значок «инфо» + спокойный поясняющий текст. Не карточка — чтобы не спорила с контентом. */
@Composable
private fun FootnoteRow(text: String) {
    Row(Modifier.padding(horizontal = GapXs, vertical = GapXs), verticalAlignment = Alignment.Top) {
        Icon(Icons.Default.Info, contentDescription = null, tint = CanonMuted, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(GapS))
        Text(text, color = CanonMuted, fontSize = CapSize, lineHeight = SubLead)
    }
}

/**
 * Плашка-ответ на действие: получилось (зелёная) или нет (красная).
 * [text] = null → плашки нет и она не занимает места; появление/скрытие — мягкое.
 */
@Composable
private fun InlineNotice(text: String?, ok: Boolean) {
    // Последний показанный текст помним: иначе на время сворачивания плашка мигнёт пустой.
    var shownText by remember { mutableStateOf("") }
    var shownOk by remember { mutableStateOf(true) }
    if (text != null && (shownText != text || shownOk != ok)) {
        shownText = text
        shownOk = ok
    }
    AnimatedVisibility(
        visible = text != null,
        enter = fadeIn(tween(220)) + expandVertically(tween(220)),
        exit = fadeOut(tween(140)) + shrinkVertically(tween(140)),
    ) {
        Surface(
            color = if (shownOk) CanonMint else CanonDangerBg,
            shape = CanonItemShape,
            border = BorderStroke(1.dp, if (shownOk) CanonHairlineGreen else CanonDangerBorder),
            modifier = Modifier.fillMaxWidth().padding(top = GapM),
        ) {
            Row(Modifier.padding(ItemPad), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (shownOk) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                    contentDescription = if (shownOk) appText("Готово", "Әҙер") else appText("Ошибка", "Хата"),
                    tint = if (shownOk) CanonGreen2 else CanonRed,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(GapM))
                Text(
                    shownText,
                    color = if (shownOk) CanonGreen2 else CanonRed,
                    fontSize = SubSize, lineHeight = SubLead, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Шапка: общее состояние допуска. Просрочено — красная, скоро/не указано — жёлтая, всё хорошо — зелёная. */
@Composable
private fun TaxiDocsHeader(a: TaxiApplicationDto) {
    val left = a.docsDaysLeft
    val expired = a.docsExpired || (left != null && left < 0)
    val soon = !expired && left != null && left <= DOCS_WARN_DAYS
    // Сроки не заполнены — это тоже «нужно внимание». Раньше сюда падала зелёная галочка,
    // и картинка спорила с подписью «Укажи сроки документов».
    val attention = !expired && !soon && a.docsMissing.isNotEmpty()
    // Цвет статуса анимируем: продлил документ — шапка сама переезжает из красной в зелёную.
    val bg by animateColorAsState(
        when { expired -> CanonDangerBg; soon || attention -> CanonWarnBg; else -> CanonMint },
        tween(320), label = "docsHeroBg",
    )
    val fg by animateColorAsState(
        when { expired -> CanonRed; soon || attention -> CanonWarn; else -> CanonGreen2 },
        tween(320), label = "docsHeroFg",
    )
    val icon = when {
        expired -> Icons.Default.ErrorOutline
        soon || attention -> Icons.Default.Schedule
        else -> Icons.Default.CheckCircle
    }
    val title = when {
        expired -> appText("Такси на паузе", "Такси паузала")
        soon -> appText("Скоро истекает документ", "Документ ваҡыты бөтә")
        attention -> appText("Укажи сроки документов", "Документ ваҡыттарын күрһәт")
        else -> appText("Документы в порядке", "Документтар тәртиптә")
    }
    val subtitle = when {
        expired -> appText("Обнови дату — вернём допуск сразу. Попутки работают.", "Датаны яңырт — рөхсәтте шунда уҡ ҡайтарабыҙ. Юлдаш сәфәрҙәре эшләй.")
        soon && left != null -> appText("Осталось $left дн. — лучше продлить заранее", "$left көн ҡалды — алдан оҙайтҡан яҡшыраҡ")
        attention -> appText("Так мы предупредим заранее, а не по факту", "Шунда алдан иҫкәртәбеҙ, эш үткәс түгел")
        else -> appText("Спасибо, что держишь их актуальными", "Уларҙы яңы килеш тотҡаның өсөн рәхмәт")
    }
    AppCard {
        Row(Modifier.padding(CardPad), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = bg, shape = CircleShape) {
                // Смысл иконки слово в слово написан рядом (заголовок) → не озвучиваем дважды.
                Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.padding(GapM).size(24.dp))
            }
            Spacer(Modifier.width(GapL))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(GapXs)) {
                AnimatedContent(
                    targetState = title,
                    transitionSpec = { fadeIn(tween(240)) togetherWith fadeOut(tween(140)) },
                    label = "docsHeroTitle",
                ) { t ->
                    Text(t, color = CanonText, fontWeight = FontWeight.Black, fontSize = TitleSize, lineHeight = TitleLead)
                }
                AnimatedContent(
                    targetState = subtitle,
                    transitionSpec = { fadeIn(tween(240)) togetherWith fadeOut(tween(140)) },
                    label = "docsHeroSubtitle",
                ) { s ->
                    Text(s, color = CanonMuted, fontSize = SubSize, lineHeight = SubLead)
                }
            }
        }
    }
}

/** Одна строка документа: срок + кнопка выбора даты в календаре. */
@Composable
private fun TaxiDocRow(
    icon: ImageVector,
    title: String,
    hint: String,
    iso: String?,
    busy: Boolean,
    lang: AppLanguage,
    ctx: android.content.Context,
    onPicked: (String) -> Unit,
) {
    val left = daysUntilIso(iso)
    val expired = left != null && left < 0
    val soon = left != null && left in 0..DOCS_WARN_DAYS
    AppCard(shape = CanonItemShape) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = when { expired -> CanonDangerBg; soon -> CanonWarnBg; else -> CanonMint },
                    shape = CircleShape,
                ) {
                    Icon(
                        icon, contentDescription = null,
                        tint = when { expired -> CanonRed; soon -> CanonWarn; else -> CanonGreen2 },
                        modifier = Modifier.padding(10.dp).size(18.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(hint, color = CanonMuted, fontSize = 12.sp, lineHeight = 16.sp)
                }
            }
            Text(
                when {
                    iso.isNullOrBlank() -> appText("Срок не указан", "Ваҡыт күрһәтелмәгән")
                    expired -> appText("Истёк ", "Ваҡыты үткән ") + (shortDate(iso) ?: iso)
                    else -> appText("Действует до ", "Ғәмәлдә ") + (shortDate(iso) ?: iso)
                },
                color = when { expired -> CanonRed; soon -> CanonWarn; iso.isNullOrBlank() -> CanonMuted; else -> CanonText },
                fontSize = 15.sp, fontWeight = FontWeight.Bold,
            )
            AppButton(
                text = if (iso.isNullOrBlank()) appText("Указать дату", "Датаны күрһәтеү")
                else appText("Изменить дату", "Датаны үҙгәртеү"),
                onClick = { openFutureDatePicker(ctx, if (lang == AppLanguage.Ba) "ba" else "ru", onPicked) },
                style = AppButtonStyle.Secondary,
                enabled = !busy,
            )
        }
    }
}

/**
 * Компактное поле «срок действия» для формы заявки (онбординг таксиста).
 * Вынесено сюда, чтобы экран заявки не тащил себе календарь и иконки: там и так 40 КБ.
 */
@Composable
internal fun TaxiDocDateField(label: String, hint: String, iso: String?, onPicked: (String) -> Unit) {
    val ctx = LocalContext.current
    val lang = LocalAppLanguage.current
    val left = daysUntilIso(iso)
    Surface(
        onClick = { openFutureDatePicker(ctx, if (lang == AppLanguage.Ba) "ba" else "ru", onPicked) },
        color = CanonSurface,
        shape = CanonItemShape,
        border = BorderStroke(1.dp, if (iso.isNullOrBlank()) CanonBorder else CanonGreen2),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(
                    if (iso.isNullOrBlank()) hint
                    else appText("Действует до ", "Ғәмәлдә ") + (shortDate(iso) ?: iso),
                    color = if (iso.isNullOrBlank()) CanonMuted else CanonGreen2,
                    fontSize = 13.sp, lineHeight = 17.sp,
                )
            }
            Spacer(Modifier.width(10.dp))
            Icon(
                if (iso.isNullOrBlank()) Icons.Default.RadioButtonUnchecked else Icons.Default.CheckCircle,
                contentDescription = null,
                tint = if (iso.isNullOrBlank()) CanonMuted else CanonGreen2,
                modifier = Modifier.size(22.dp),
            )
        }
    }
    // Дата в прошлом сюда попасть не может (календарь ограничен), но если сервер уже
    // отдал просроченную — честно подсвечиваем, а не делаем вид, что всё хорошо.
    if (left != null && left < 0) {
        Text(
            appText("Срок истёк — обнови документ", "Ваҡыты үткән — документты яңырт"),
            color = CanonRed, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp, top = 4.dp),
        )
    }
}

/**
 * Календарь только на будущее → строка «ГГГГ-ММ-ДД» (формат сервера).
 * minDate = сегодня: просроченную дату сервер и так отвергнет, но лучше не давать её выбрать.
 */
internal fun openFutureDatePicker(context: android.content.Context, localeTag: String, onPicked: (String) -> Unit) {
    val cfg = android.content.res.Configuration(context.resources.configuration)
        .apply { setLocale(java.util.Locale(localeTag)) }
    val ctx = android.view.ContextThemeWrapper(context, 0).apply { applyOverrideConfiguration(cfg) }
    val cal = java.util.Calendar.getInstance()
    android.app.DatePickerDialog(
        ctx, com.yuldash.app.R.style.Theme_Yuldash_DatePicker,
        { _, y, m, d -> onPicked(String.format(java.util.Locale.US, "%04d-%02d-%02d", y, m + 1, d)) },
        cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH), cal.get(java.util.Calendar.DAY_OF_MONTH),
    ).apply { datePicker.minDate = cal.timeInMillis }.show()
}

/** Сколько дней до даты «ГГГГ-ММ-ДД». null — дата пустая или нечитаемая (не гадаем). */
internal fun daysUntilIso(iso: String?): Int? {
    if (iso.isNullOrBlank()) return null
    return runCatching {
        val d = java.time.LocalDate.parse(iso.take(10))
        java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.now(), d).toInt()
    }.getOrNull()
}

// ─────────────────────────── Готовность к работе ───────────────────────────

@Composable
internal fun PretripCheckScreen(onBack: () -> Unit, onConfirmed: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<com.yuldash.app.data.PretripDto?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }

    // Три галочки: подтверждать за человека нельзя, поэтому изначально все выключены.
    var health by remember { mutableStateOf(false) }
    var car by remember { mutableStateOf(false) }
    var sober by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var errText by remember { mutableStateOf<String?>(null) }

    val errFallback = appText("Не получилось. Проверь сеть и повтори.", "Булманы. Селтәрҙе тикшереп ҡабатла.")

    LaunchedEffect(reload) {
        loading = true; error = false
        ApiClient.getPretrip().onSuccess { state = it }.onFailure { error = true }
        loading = false
    }

    val confirmed = state?.confirmed == true
    val allChecked = health && car && sober

    Scaffold(
        containerColor = CanonBg,
        topBar = { ScreenTopBar(appText("Готовность к работе", "Эшкә әҙерлек"), onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 28.dp),
        ) {
            when {
                loading && state == null -> item { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { repeat(3) { SkeletonCard(lines = 2) } } }
                error && state == null -> item { AppErrorState(onRetry = { reload++ }) }
                confirmed -> item { PretripDoneCard(state?.confirmedAt) }
                else -> {
                    item {
                        AppCard {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(appText("Перед выходом на линию", "Линияға сығыр алдынан"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 18.sp)
                                Text(
                                    appText(
                                        "Отметь три пункта — раз в день. Это не медосмотр: врача у нас нет, и мы не будем притворяться. Это твоё слово, и оно остаётся записью — если что-то случится, будет видно, что ты подтвердил в этот день.",
                                        "Өс пунктты билдәлә — көнөнә бер тапҡыр. Был медосмотр түгел: табибыбыҙ юҡ, һәм беҙ уны уйнап күрһәтмәйбеҙ. Был — һинең һүҙең, ул яҙма булып ҡала: берәй хәл булһа, ошо көндә нимә раҫлағаның күренәсәк.",
                                    ),
                                    color = CanonMuted, fontSize = 13.sp, lineHeight = 18.sp,
                                )
                            }
                        }
                    }
                    item {
                        PretripCheckItem(
                            Icons.Default.Favorite,
                            appText("Чувствую себя хорошо", "Үҙемде яҡшы тоям"),
                            appText("Выспался, могу вести машину", "Йоҡлағанмын, машина йөрөтә алам"),
                            health,
                        ) { health = !health }
                    }
                    item {
                        PretripCheckItem(
                            Icons.Default.DirectionsCar,
                            appText("Машина исправна", "Машина төҙөк"),
                            appText("Тормоза, свет, резина, стёкла — в порядке", "Тормоз, ут, резина, быяла — тәртиптә"),
                            car,
                        ) { car = !car }
                    }
                    item {
                        PretripCheckItem(
                            Icons.Default.Block,
                            appText("Алкоголя не было", "Эсемлек эсмәнем"),
                            appText("И лекарств, которые влияют на реакцию", "Реакцияға тәьҫир иткән дарыуҙар ҙа юҡ"),
                            sober,
                        ) { sober = !sober }
                    }
                    item {
                        OutlinedTextField(
                            value = note,
                            onValueChange = { note = it.take(300) },
                            label = { Text(appText("Заметка (необязательно)", "Билдә (мотлаҡ түгел)")) },
                            placeholder = { Text(appText("«Заменил лампу ближнего света»", "«Яҡын ут лампаһын алмаштырҙым»")) },
                            minLines = 2,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                        )
                    }
                    item {
                        AppButton(
                            text = appText("Подтвердить готовность", "Әҙерлекте раҫлау"),
                            onClick = {
                                if (busy) return@AppButton
                                busy = true; errText = null
                                scope.launch {
                                    ApiClient.confirmPretrip(note)
                                        .onSuccess { state = it; onConfirmed() }
                                        .onFailure { errText = (it as? ApiException)?.message ?: errFallback }
                                    busy = false
                                }
                            },
                            enabled = allChecked,
                            loading = busy,
                            icon = Icons.Default.CheckCircle,
                        )
                    }
                    if (!allChecked) {
                        item {
                            Text(
                                appText(
                                    "Если хоть один пункт не про тебя сегодня — не выезжай. Заказы подождут, здоровье нет.",
                                    "Бөгөн пункттарҙың береһе лә тап килмәһә — сыҡма. Заказдар көтә, ә һаулыҡ көтмәй.",
                                ),
                                color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp, textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    errText?.let {
                        item {
                            Surface(color = CanonDangerBg, shape = CanonItemShape) {
                                Text(it, color = CanonRed, fontSize = 13.sp, lineHeight = 18.sp,
                                    modifier = Modifier.fillMaxWidth().padding(14.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PretripCheckItem(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onToggle: () -> Unit) {
    Surface(
        onClick = onToggle,
        color = if (checked) CanonMint else CanonSurface,
        shape = CanonItemShape,
        border = BorderStroke(1.dp, if (checked) CanonGreen2 else CanonBorder),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (checked) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (checked) CanonGreen2 else CanonMuted,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(subtitle, color = CanonMuted, fontSize = 12.sp, lineHeight = 16.sp)
            }
            Spacer(Modifier.width(10.dp))
            Icon(icon, contentDescription = null, tint = if (checked) CanonGreen2 else CanonMuted, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun PretripDoneCard(confirmedAt: String?) {
    AppCard {
        Column(
            Modifier.padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AnimatedVisibility(visible = true, enter = fadeIn(), exit = fadeOut()) {
                Surface(color = CanonMint, shape = CircleShape) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CanonGreen2,
                        modifier = Modifier.padding(16.dp).size(30.dp))
                }
            }
            Text(appText("Готовность подтверждена", "Әҙерлек раҫланды"), color = CanonText,
                fontWeight = FontWeight.Black, fontSize = 18.sp, textAlign = TextAlign.Center)
            Text(
                appText("Хорошей смены и лёгкой дороги 💚", "Уңышлы смена һәм еңел юл 💚"),
                color = CanonMuted, fontSize = 14.sp, lineHeight = 19.sp, textAlign = TextAlign.Center,
            )
            confirmedAt?.let {
                Text(
                    appText("Отмечено: ", "Билдәләнде: ") + formatDepart(it),
                    color = CanonMuted, fontSize = 12.sp,
                )
            }
        }
    }
}
