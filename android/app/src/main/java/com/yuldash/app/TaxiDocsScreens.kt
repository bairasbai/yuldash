package com.yuldash.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.imePadding
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
import kotlinx.coroutines.delay
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
private val TitleSize = 19.sp   // заголовок карточки (один на карточку)
private val BodySize = 16.sp    // название документа/пункта и значение (дата)
private val SubSize = 14.sp     // пояснение под заголовком, текст плашек
private val CapSize = 12.sp     // служебное: подпись секции, пилюля, сноска, счётчик
private val TitleLead = 25.sp
private val BodyLead = 23.sp
private val SubLead = 20.sp
private val CapLead = 17.sp

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

    // «Дата сохранена» висела до ухода с экрана. Через минуту водитель менял ДРУГУЮ дату, а
    // старая зелёная плашка всё ещё стояла рядом — и читалась как «и эта сохранена тоже».
    // Успех сам уходит через 3,5 секунды. Ошибку не прячем: по ней человеку надо что-то сделать.
    LaunchedEffect(msg) {
        if (msg != null) {
            delay(3_500)
            msg = null
        }
    }

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
                error && a == null -> item(key = "error") {
                    Box(Modifier.appearIn(0)) { AppErrorState(onRetry = { reload++ }) }
                }
                a == null -> item(key = "empty") {
                    Box(Modifier.appearIn(0)) {
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

/** Что показывала плашка в последний раз. Обычный объект, не state: обновлять его
 *  во время композиции безопасно — лишней перерисовки не вызывает. */
private class NoticeMemo(var text: String = "", var ok: Boolean = true)

/**
 * Плашка-ответ на действие: получилось (зелёная) или нет (красная).
 * [text] = null → плашки нет и она не занимает места; появление/скрытие — мягкое.
 */
@Composable
private fun InlineNotice(text: String?, ok: Boolean) {
    // Последнее показанное помним: иначе за время сворачивания плашка мигнёт пустой.
    val memo = remember { NoticeMemo() }
    if (text != null) {
        memo.text = text
        memo.ok = ok
    }
    val shownText = memo.text
    val shownOk = memo.ok
    AnimatedVisibility(
        visible = text != null,
        enter = fadeIn(tween(CanonMotion.QUICK)) + expandVertically(tween(CanonMotion.QUICK)),
        exit = fadeOut(tween(CanonMotion.QUICK)) + shrinkVertically(tween(CanonMotion.QUICK)),
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
        tween(CanonMotion.SLOW), label = "docsHeroBg",
    )
    val fg by animateColorAsState(
        when { expired -> CanonRed; soon || attention -> CanonWarn; else -> CanonGreen2 },
        tween(CanonMotion.SLOW), label = "docsHeroFg",
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
                    transitionSpec = { fadeIn(tween(CanonMotion.NORMAL)) togetherWith fadeOut(tween(CanonMotion.QUICK)) },
                    label = "docsHeroTitle",
                ) { t ->
                    Text(t, color = CanonText, fontWeight = FontWeight.Bold, fontSize = TitleSize, lineHeight = TitleLead)
                }
                AnimatedContent(
                    targetState = subtitle,
                    transitionSpec = { fadeIn(tween(CanonMotion.NORMAL)) togetherWith fadeOut(tween(CanonMotion.QUICK)) },
                    label = "docsHeroSubtitle",
                ) { s ->
                    Text(s, color = CanonMuted, fontSize = SubSize, lineHeight = SubLead)
                }
            }
        }
    }
}

/**
 * Одна строка документа: название, срок и состояние.
 * Вся карточка — цель нажатия (открывает календарь), поэтому три громоздкие кнопки ушли,
 * и все документы теперь видно разом, без прокрутки. Статус вынесен в короткую пилюлю,
 * а строка даты осталась нейтральной («до …») — одно и то же не написано дважды.
 */
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
    val missing = iso.isNullOrBlank()
    val expired = left != null && left < 0
    val soon = left != null && left in 0..DOCS_WARN_DAYS
    // Смена статуса после продления — переездом цвета, а не рывком.
    val accent by animateColorAsState(
        when { expired -> CanonRed; soon -> CanonWarn; missing -> CanonMuted; else -> CanonGreen2 },
        tween(CanonMotion.SLOW), label = "docAccent",
    )
    val accentBg by animateColorAsState(
        when { expired -> CanonDangerBg; soon -> CanonWarnBg; missing -> CanonBg; else -> CanonMint },
        tween(CanonMotion.SLOW), label = "docAccentBg",
    )
    // Пока идёт сохранение — карточки приглушены и не ловят нажатия (раньше просто гасли кнопки).
    val dim by animateFloatAsState(if (busy) 0.55f else 1f, tween(CanonMotion.QUICK), label = "docBusy")
    val dateText = shortDate(iso) ?: iso.orEmpty()
    val dateLine = if (missing) appText("Дата не указана", "Дата күрһәтелмәгән") else appText("до $dateText", "$dateText тиклем")
    val action = if (missing) appText("Указать", "Күрһәтеү") else appText("Изменить", "Үҙгәртеү")
    // Пилюля коротка намеренно: длинный башкирский тут сжал бы название документа.
    val pill = when {
        expired -> appText("Истёк", "Үткән")
        left != null -> appText("$left дн.", "$left көн")
        else -> ""
    }

    AppCard(
        modifier = Modifier.alpha(dim),
        shape = CanonItemShape,
        onClick = { if (!busy) openFutureDatePicker(ctx, if (lang == AppLanguage.Ba) "ba" else "ru", onPicked) },
    ) {
        Column(Modifier.padding(ItemPad), verticalArrangement = Arrangement.spacedBy(GapM)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = accentBg, shape = CircleShape) {
                    // Название документа написано рядом — иконка тут украшение, озвучивать нечего.
                    Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.padding(GapM).size(20.dp))
                }
                Spacer(Modifier.width(GapM))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(GapXs)) {
                    Text(title, color = CanonText, fontWeight = FontWeight.Bold, fontSize = BodySize, lineHeight = BodyLead)
                    Text(hint, color = CanonMuted, fontSize = SubSize, lineHeight = SubLead)
                }
                AnimatedVisibility(
                    visible = expired || soon,
                    enter = fadeIn(tween(CanonMotion.QUICK)) + scaleIn(tween(CanonMotion.QUICK), initialScale = 0.8f),
                    exit = fadeOut(tween(CanonMotion.QUICK)) + scaleOut(tween(CanonMotion.QUICK), targetScale = 0.8f),
                ) {
                    DocStatusPill(text = pill, fg = accent, bg = accentBg)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                AnimatedContent(
                    targetState = dateLine,
                    transitionSpec = { fadeIn(tween(CanonMotion.NORMAL)) togetherWith fadeOut(tween(CanonMotion.QUICK)) },
                    label = "docDate",
                    modifier = Modifier.weight(1f),
                ) { line ->
                    Text(line, color = accent, fontSize = BodySize, lineHeight = BodyLead, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(GapS))
                Text(action, color = CanonGreen2, fontSize = SubSize, lineHeight = SubLead, fontWeight = FontWeight.Bold)
                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** Короткая пилюля статуса: «Истёк» или «14 дн.». Появляется только когда нужна реакция. */
@Composable
private fun DocStatusPill(text: String, fg: Color, bg: Color) {
    Surface(color = bg, shape = CircleShape) {
        Text(
            text, color = fg, fontSize = CapSize, lineHeight = CapLead,
            fontWeight = FontWeight.Bold, maxLines = 1,
            modifier = Modifier.padding(horizontal = GapM, vertical = GapS),
        )
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
    val filled = !iso.isNullOrBlank()
    val accent by animateColorAsState(if (filled) CanonGreen2 else CanonMuted, tween(CanonMotion.NORMAL), label = "dateFieldAccent")
    val line by animateColorAsState(if (filled) CanonGreen2 else CanonBorder, tween(CanonMotion.NORMAL), label = "dateFieldLine")
    // Подпись в форме уже заканчивается на «до», поэтому значение — просто дата, без повтора предлога.
    val value = if (filled) (shortDate(iso) ?: iso.orEmpty()) else hint
    Column {
        Surface(
            color = CanonSurface,
            shape = CanonItemShape,
            border = BorderStroke(1.dp, line),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .bounceClick { openFutureDatePicker(ctx, if (lang == AppLanguage.Ba) "ba" else "ru", onPicked) },
        ) {
            Row(Modifier.padding(ItemPad), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(GapXs)) {
                    Text(label, color = CanonText, fontWeight = FontWeight.Bold, fontSize = BodySize, lineHeight = BodyLead)
                    AnimatedContent(
                        targetState = value,
                        transitionSpec = { fadeIn(tween(CanonMotion.QUICK)) togetherWith fadeOut(tween(CanonMotion.QUICK)) },
                        label = "dateFieldValue",
                    ) { v ->
                        Text(v, color = accent, fontSize = SubSize, lineHeight = SubLead)
                    }
                }
                Spacer(Modifier.width(GapM))
                // Здесь иконка — единственный носитель состояния поля, поэтому её озвучиваем.
                Icon(
                    if (filled) Icons.Default.CheckCircle else Icons.Default.CalendarMonth,
                    contentDescription = if (filled) appText("Дата указана", "Дата күрһәтелгән")
                    else appText("Выбрать дату", "Дата һайлау"),
                    tint = accent,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        // Дата в прошлом сюда попасть не может (календарь ограничен), но если сервер уже
        // отдал просроченную — честно подсвечиваем, а не делаем вид, что всё хорошо.
        AnimatedVisibility(
            visible = left != null && left < 0,
            enter = fadeIn(tween(CanonMotion.QUICK)) + expandVertically(tween(CanonMotion.QUICK)),
            exit = fadeOut(tween(CanonMotion.QUICK)) + shrinkVertically(tween(CanonMotion.QUICK)),
        ) {
            Row(Modifier.padding(start = GapXs, top = GapS), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = CanonRed, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(GapS))
                Text(
                    appText("Срок истёк — обнови документ", "Ваҡыты үткән — документты яңырт"),
                    color = CanonRed, fontSize = CapSize, lineHeight = CapLead,
                )
            }
        }
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
    // Обязательна отметка сегодня или нет — решает сервер (флаг pretrip_check_required).
    // Раньше этот ответ приходил и молча выбрасывался: экран всем одинаково намекал, что
    // без отметки на линию не пустят. Пока флаг выключен — это неправда, и врать нельзя.
    val required = state?.required == true
    val allChecked = health && car && sober

    Scaffold(
        containerColor = CanonBg,
        topBar = { ScreenTopBar(appText("Готовность к работе", "Эшкә әҙерлек"), onBack) },
    ) { padding ->
        LazyColumn(
            // imePadding: на этом экране есть поле заметки — кнопка «Подтвердить» не должна
            // прятаться за клавиатурой (тот же приём, что в чате активной поездки).
            modifier = Modifier.padding(padding).padding(horizontal = ScreenPad).imePadding(),
            verticalArrangement = Arrangement.spacedBy(GapM),
            contentPadding = PaddingValues(top = GapS, bottom = BottomPad),
        ) {
            when {
                loading && state == null -> item(key = "skeleton") { PretripSkeleton() }
                error && state == null -> item(key = "error") {
                    Box(Modifier.appearIn(0)) { AppErrorState(onRetry = { reload++ }) }
                }
                confirmed -> item(key = "done") {
                    Box(Modifier.appearIn(0)) { PretripDoneCard(state?.confirmedAt) }
                }
                else -> {
                    item(key = "intro") { Box(Modifier.appearIn(0)) { PretripIntroCard(required) } }
                    item(key = "progress") {
                        PretripProgress(
                            done = (if (health) 1 else 0) + (if (car) 1 else 0) + (if (sober) 1 else 0),
                            total = 3,
                        )
                    }
                    item(key = "health") {
                        Box(Modifier.appearIn(1)) {
                            PretripCheckItem(
                                Icons.Default.Favorite,
                                appText("Чувствую себя хорошо", "Үҙемде яҡшы тоям"),
                                appText("Выспался, могу вести машину", "Йоҡлағанмын, машина йөрөтә алам"),
                                health,
                            ) { health = !health }
                        }
                    }
                    item(key = "car") {
                        Box(Modifier.appearIn(2)) {
                            PretripCheckItem(
                                Icons.Default.DirectionsCar,
                                appText("Машина исправна", "Машина төҙөк"),
                                appText("Тормоза, свет, резина, стёкла — в порядке", "Тормоз, ут, резина, быяла — тәртиптә"),
                                car,
                            ) { car = !car }
                        }
                    }
                    item(key = "sober") {
                        Box(Modifier.appearIn(3)) {
                            PretripCheckItem(
                                Icons.Default.Block,
                                appText("Алкоголя не было", "Эсемлек эсмәнем"),
                                appText("И лекарств, которые влияют на реакцию", "Реакцияға тәьҫир иткән дарыуҙар ҙа юҡ"),
                                sober,
                            ) { sober = !sober }
                        }
                    }
                    item(key = "note") {
                        OutlinedTextField(
                            value = note,
                            onValueChange = { note = it.take(300) },
                            label = { Text(appText("Заметка (необязательно)", "Билдә (мотлаҡ түгел)")) },
                            placeholder = { Text(appText("«Заменил лампу ближнего света»", "«Яҡын ут лампаһын алмаштырҙым»")) },
                            // Пустое поле — подсказка, начал писать — счётчик. Высота одинаковая,
                            // поле не «прыгает» на первом же символе.
                            supportingText = {
                                Text(
                                    if (note.isEmpty()) appText("Коротко, до 300 знаков", "Ҡыҫҡа, 300 билдәгә тиклем")
                                    else "${note.length} / 300",
                                    color = CanonMuted, fontSize = CapSize, lineHeight = CapLead,
                                )
                            },
                            minLines = 2,
                            maxLines = 4,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                        )
                    }
                    // Кнопка, подсказка и ошибка — одним блоком: скрытые части не оставляют
                    // после себя пустых зазоров, ритм списка не «дышит».
                    item(key = "confirm") {
                        Column {
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
                            PretripHint(visible = !allChecked)
                            InlineNotice(text = errText, ok = false)
                        }
                    }
                }
            }
        }
    }
}

/** Скелетон в форме будущего экрана: карточка-объяснение + три пункта. */
@Composable
private fun PretripSkeleton() {
    Column(verticalArrangement = Arrangement.spacedBy(GapM)) {
        SkeletonCard(lines = 3)
        repeat(3) { SkeletonCard(lines = 1) }
    }
}

/** Честное объяснение, зачем этот экран. Один заголовок — один абзац, без нравоучений.
 *  @param required правда ли, что без отметки сегодня на линию не выпустят (решает сервер). */
@Composable
private fun PretripIntroCard(required: Boolean) {
    AppCard {
        Column(Modifier.padding(CardPad), verticalArrangement = Arrangement.spacedBy(GapS)) {
            Text(
                appText("Перед выходом на линию", "Линияға сығыр алдынан"),
                color = CanonText, fontWeight = FontWeight.Bold, fontSize = TitleSize, lineHeight = TitleLead,
            )
            Text(
                appText(
                    "Отметь три пункта — раз в день. Это не медосмотр: врача у нас нет, и мы не будем притворяться. Это твоё слово, и оно остаётся записью — если что-то случится, будет видно, что ты подтвердил в этот день.",
                    "Өс пунктты билдәлә — көнөнә бер тапҡыр. Был медосмотр түгел: табибыбыҙ юҡ, һәм беҙ уны уйнап күрһәтмәйбеҙ. Был — һинең һүҙең, ул яҙма булып ҡала: берәй хәл булһа, ошо көндә нимә раҫлағаның күренәсәк.",
                ),
                color = CanonMuted, fontSize = SubSize, lineHeight = SubLead,
            )
            // Обязательно это сегодня или по желанию — человек должен знать до того, как начнёт
            // отмечать, а не после отказа на тумблере «на линии».
            Text(
                if (required) appText(
                    "Сегодня без этой отметки заказы такси брать нельзя.",
                    "Бөгөн был билдәһеҙ такси заказдары алып булмай.",
                ) else appText(
                    "Пока не обязательно — но отметка сохранится и пригодится при разборе.",
                    "Әлегә мотлаҡ түгел — әммә билдә һаҡлана һәм тикшереүҙә ярҙам итә.",
                ),
                color = if (required) CanonRed else CanonMuted,
                fontSize = SubSize, lineHeight = SubLead,
                fontWeight = if (required) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}

/** Сколько пунктов отмечено: тонкая полоска, которая доезжает до конца. Видно, что осталось. */
@Composable
private fun PretripProgress(done: Int, total: Int) {
    val fraction by animateFloatAsState(
        if (total == 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f),
        tween(CanonMotion.SLOW), label = "pretripProgress",
    )
    Column(
        verticalArrangement = Arrangement.spacedBy(GapS),
        modifier = Modifier.padding(start = GapXs, end = GapXs, top = GapXs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                appText("ОТМЕЧЕНО", "БИЛДӘЛӘНГӘН"),
                color = CanonMuted, fontSize = CapSize, lineHeight = CapLead,
                fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp,
            )
            Spacer(Modifier.weight(1f))
            AnimatedContent(
                targetState = done,
                transitionSpec = { fadeIn(tween(CanonMotion.QUICK)) togetherWith fadeOut(tween(CanonMotion.QUICK)) },
                label = "pretripDone",
            ) { d ->
                Text(
                    "$d / $total",
                    color = if (d == total) CanonGreen2 else CanonMuted,
                    fontSize = CapSize, lineHeight = CapLead, fontWeight = FontWeight.Bold,
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(CanonMint)) {
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().clip(CircleShape).background(CanonGreen2))
        }
    }
}

/** Подсказка «не выезжай, если что-то не так» — пока отмечены не все три пункта. */
@Composable
private fun PretripHint(visible: Boolean) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(CanonMotion.QUICK)) + expandVertically(tween(CanonMotion.QUICK)),
        exit = fadeOut(tween(CanonMotion.QUICK)) + shrinkVertically(tween(CanonMotion.QUICK)),
    ) {
        Text(
            appText(
                "Если хоть один пункт не про тебя сегодня — не выезжай. Заказы подождут, здоровье нет.",
                "Бөгөн пункттарҙың береһе лә тап килмәһә — сыҡма. Заказдар көтә, ә һаулыҡ көтмәй.",
            ),
            color = CanonMuted, fontSize = CapSize, lineHeight = SubLead, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = GapM, start = GapS, end = GapS),
        )
    }
}

/**
 * Пункт самодекларации. Смысловая иконка ведёт строку (как в списке документов),
 * а состояние держит галочка справа — её и озвучиваем.
 */
@Composable
private fun PretripCheckItem(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onToggle: () -> Unit) {
    val bg by animateColorAsState(if (checked) CanonMint else CanonSurface, tween(CanonMotion.NORMAL), label = "pretripBg")
    val line by animateColorAsState(if (checked) CanonGreen2 else CanonBorder, tween(CanonMotion.NORMAL), label = "pretripLine")
    val bubble by animateColorAsState(if (checked) CanonSurface else CanonMint, tween(CanonMotion.NORMAL), label = "pretripBubble")
    val tint by animateColorAsState(if (checked) CanonGreen2 else CanonMuted, tween(CanonMotion.NORMAL), label = "pretripTint")
    Surface(
        color = bg,
        shape = CanonItemShape,
        border = BorderStroke(1.dp, line),
        // bounceClick — то же лёгкое сжатие под пальцем, что у карточек по всему приложению.
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).bounceClick(onToggle),
    ) {
        Row(Modifier.padding(ItemPad), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = bubble, shape = CircleShape) {
                // Пункт назван словами рядом — иконка тут узнаваемость, а не информация.
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.padding(GapM).size(20.dp))
            }
            Spacer(Modifier.width(GapM))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(GapXs)) {
                Text(title, color = CanonText, fontWeight = FontWeight.Bold, fontSize = BodySize, lineHeight = BodyLead)
                Text(subtitle, color = CanonMuted, fontSize = SubSize, lineHeight = SubLead)
            }
            Spacer(Modifier.width(GapM))
            AnimatedContent(
                targetState = checked,
                transitionSpec = {
                    (fadeIn(tween(CanonMotion.QUICK)) + scaleIn(tween(CanonMotion.QUICK), initialScale = 0.7f)) togetherWith
                        (fadeOut(tween(CanonMotion.QUICK)) + scaleOut(tween(CanonMotion.QUICK), targetScale = 0.7f))
                },
                label = "pretripCheckMark",
            ) { on ->
                Icon(
                    if (on) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    contentDescription = if (on) appText("Отмечено", "Билдәләнгән") else appText("Не отмечено", "Билдәләнмәгән"),
                    tint = if (on) CanonGreen2 else CanonMuted,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

@Composable
private fun PretripDoneCard(confirmedAt: String?) {
    // Галочка «вырастает» один раз при открытии — маленькая награда за скучное обязательное действие.
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    AppCard {
        Column(
            Modifier.padding(CardPadLg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(GapM),
        ) {
            AnimatedVisibility(
                visible = shown,
                enter = fadeIn(tween(CanonMotion.SLOW)) + scaleIn(tween(CanonMotion.SLOW), initialScale = 0.6f),
                exit = fadeOut(tween(CanonMotion.QUICK)),
            ) {
                Surface(color = CanonMint, shape = CircleShape) {
                    // Ровно то же написано заголовком ниже — второй раз не читаем.
                    Icon(
                        Icons.Default.CheckCircle, contentDescription = null, tint = CanonGreen2,
                        modifier = Modifier.padding(GapL).size(32.dp),
                    )
                }
            }
            Text(
                appText("Готовность подтверждена", "Әҙерлек раҫланды"), color = CanonText,
                fontWeight = FontWeight.Bold, fontSize = TitleSize, lineHeight = TitleLead, textAlign = TextAlign.Center,
            )
            Text(
                appText("Хорошей смены и лёгкой дороги 💚", "Уңышлы смена һәм еңел юл 💚"),
                color = CanonMuted, fontSize = SubSize, lineHeight = SubLead, textAlign = TextAlign.Center,
            )
            confirmedAt?.let {
                Text(
                    appText("Отмечено: ", "Билдәләнде: ") + formatDepart(it),
                    color = CanonMuted, fontSize = CapSize, lineHeight = CapLead, textAlign = TextAlign.Center,
                )
            }
        }
    }
}
