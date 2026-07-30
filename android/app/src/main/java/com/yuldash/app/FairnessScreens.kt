package com.yuldash.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.unit.sp
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.ApiException
import com.yuldash.app.data.IncidentDto
import com.yuldash.app.data.StandingDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * ════════════════════════════════════════════════════════════════════════════
 *  «Центр справедливости» — двусторонний разбор споров
 * ════════════════════════════════════════════════════════════════════════════
 *  Обещание Юлдаша — «обе стороны слышимы». На сервере это работало давно: 13 ручек,
 *  фото-доказательства, апелляции, лестница наказаний. В приложении подсистемы НЕ БЫЛО
 *  вообще (греп по android/ был пуст, аудит 2026-07-26): таксист нахамил, посылка пропала —
 *  человеку идти некуда, и обещание оставалось словами.
 *
 *  Порядок разбора, который здесь виден человеку:
 *   1. кто-то открывает спор и прикладывает фото;
 *   2. вторая сторона ОБЯЗАТЕЛЬНО получает право объясниться (тоже с фото);
 *   3. решает человек и объясняет ОБЕИМ сторонам одним текстом;
 *   4. несогласный подаёт апелляцию — один раз;
 *   5. договорились сами → «решили миром», без последствий для обоих.
 *
 *  Приватность: телефон второй стороны участнику не показывается никогда, фото лежат в
 *  приватной области и открываются только сторонам спора.
 *
 *  ── Визуальный канон раздела (правки 2026-07-30) ───────────────────────────
 *  Тема разговора тяжёлая, поэтому экран намеренно тихий: никакой пестроты, весь цвет
 *  работает как смысл (жёлтый — ждём тебя, красный — пауза/апелляция, зелёный — решено).
 *  Три правила, которые тут держат «дорогой» вид:
 *   • ЧЕТЫРЕ размера текста и ни одного лишнего (см. FairMetric/FairTitle/FairBody/FairMeta);
 *   • всё кратно 4dp: карточка дышит 20dp, строка списка 16dp, блоки 12dp, пары 4dp;
 *   • один значок статуса (44dp) и одна пилюля на весь раздел — шапки карточек не «прыгают».
 */

// ─────────────────────────── Типографика раздела ───────────────────────────
// Четыре размера, у каждого ОДНА роль. Больше — каша, меньше — пропадает иерархия.
//   Metric — единственное крупное число (Надёжность). Title — заголовок карточки/секции.
//   Body   — читаемый текст, который человек действительно читает.
//   Meta   — подпись, дата, статус-пилюля, сноска.
// Вес тоже закреплён за ролью: Black — заголовки и число, Bold — акцентная мета (пилюля,
// «твоя очередь»), Normal — всё остальное. Двух весов на одну роль в файле нет.
private val FairMetric = 26.sp
private val FairTitle = 17.sp
private val FairBody = 14.sp
private val FairMeta = 12.sp
private val FairMetricLine = 30.sp
private val FairTitleLine = 22.sp
private val FairBodyLine = 20.sp
private val FairMetaLine = 16.sp

// ─────────────────────────── Ритм раздела (сетка 4dp) ───────────────────────────
private val FairCardPad = 20.dp    // внутренний воздух крупной карточки
private val FairRowPad = 16.dp     // внутренний воздух строки списка / вложенной плашки
private val FairGap = 12.dp        // между смысловыми блоками
private val FairGapTight = 8.dp    // между близкими элементами
private val FairGapHair = 4.dp     // пара «подпись + значение»
private val FairBadge = 44.dp      // диаметр значка статуса (один на весь раздел)
private val FairTouch = 48.dp      // минимальная тач-цель
private val FairIcon = 20.dp       // иконка в строке/значке
private val FairIconSmall = 16.dp  // иконка внутри мелкой плашки

// Типы споров — ровно те, что принимает сервер (safety_logic.INCIDENT_TYPES).
// Неизвестный тип он отвергнет, поэтому список здесь и там должен совпадать.
internal data class IncidentTypeUi(val key: String, val ru: String, val ba: String)

internal val incidentTypesRide = listOf(
    IncidentTypeUi("rude", "Нагрубили", "Ҡупал һөйләште"),
    IncidentTypeUi("unsafe", "Опасная езда", "Хәүефле йөрөтөү"),
    IncidentTypeUi("non_payment", "Не заплатили", "Түләмәнеләр"),
    IncidentTypeUi("overcharge", "Взяли больше договорённого", "Килешкәндән артыҡ алдылар"),
    IncidentTypeUi("route_detour", "Повезли не той дорогой", "Икенсе юлдан алып барҙылар"),
    IncidentTypeUi("passenger_no_show", "Пассажир не вышел", "Юлаусы сыҡманы"),
    IncidentTypeUi("driver_no_show", "Водитель не приехал", "Водитель килмәне"),
    IncidentTypeUi("harassment", "Приставания, угрозы", "Бәйләнеү, янау"),
    IncidentTypeUi("rules_violation", "Нарушение правил", "Ҡағиҙәләрҙе боҙоу"),
)

// Типы по доставке — заведены на сервере и теперь достижимы (у спора появилась привязка к посылке).
internal val incidentTypesParcel = listOf(
    IncidentTypeUi("parcel_damage", "Посылку повредили", "Бандеролде боҙғандар"),
    IncidentTypeUi("parcel_lost", "Посылка пропала", "Бандероль юғалған"),
    IncidentTypeUi("parcel_delay", "Сильно опоздали", "Бик һуңланылар"),
    IncidentTypeUi("recipient_absent", "Получателя не было", "Алыусы юҡ ине"),
    IncidentTypeUi("wrong_contents", "Внутри не то", "Эсендә башҡа нәмә"),
)

@Composable
internal fun incidentTypeLabel(key: String): String =
    (incidentTypesRide + incidentTypesParcel).firstOrNull { it.key == key }
        ?.let { appText(it.ru, it.ba) }
        ?: appText("Спор", "Бәхәс")

@Composable
internal fun incidentStatusLabel(status: String): String = when (status) {
    "awaiting_response" -> appText("Ждём объяснения", "Аңлатма көтәбеҙ")
    "under_review" -> appText("На разборе", "Ҡарала")
    "appealed" -> appText("Подана апелляция", "Ялыу бирелгән")
    "resolved" -> appText("Решение принято", "Ҡарар ҡабул ителде")
    "closed" -> appText("Закрыт миром", "Тыныслыҡ менән ябылды")
    else -> appText("Открыт", "Асыҡ")
}

/** Цвета статуса: живой спор — жёлтый, решённый — зелёный, закрытый миром — зелёный. */
@Composable
private fun statusColors(status: String): Pair<Color, Color> = when (status) {
    "resolved", "closed" -> CanonMint to CanonGreen2
    "appealed" -> CanonDangerBg to CanonRed
    else -> CanonWarnBg to CanonWarn
}

/** Значок статуса. Только из уже используемых в разделе иконок — новых сущностей не плодим. */
private fun statusIcon(status: String): ImageVector = when (status) {
    "resolved", "closed" -> Icons.Default.CheckCircle
    "appealed" -> Icons.Default.Report
    else -> Icons.Default.Shield
}

// ─────────────────────────── Общие мелкие блоки раздела ───────────────────────────

/**
 * Круглый значок статуса — ОДИН размер (44dp) на весь раздел. До этого шапки карточек
 * жили каждая своей жизнью (46dp в одной, 40dp в другой), и вертикальный ритм скакал.
 *
 * [description] — двуязычная озвучка для TalkBack. Передаём её только там, где значок
 * САМ несёт смысл (статус спора). Если рядом стоит тот же текст — оставляем null,
 * иначе TalkBack читает одно и то же дважды.
 */
@Composable
private fun StatusBadge(icon: ImageVector, tint: Color, bg: Color, description: String? = null) {
    Surface(color = bg, shape = CircleShape, modifier = Modifier.size(FairBadge)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(FairIcon))
        }
    }
}

/** Пилюля-статус: одна форма и один размер текста на весь раздел. */
@Composable
private fun StatusPill(text: String, bg: Color, fg: Color, modifier: Modifier = Modifier) {
    Surface(color = bg, shape = CircleShape, modifier = modifier) {
        Text(
            text,
            color = fg,
            fontSize = FairMeta,
            lineHeight = FairMetaLine,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = FairGap, vertical = FairGapTight),
        )
    }
}

/** Плашка-сообщение внутри карточки (ошибка, ожидание, подсказка). Единый вид на весь раздел. */
@Composable
private fun FairNotice(text: String, bg: Color, fg: Color, modifier: Modifier = Modifier) {
    Surface(color = bg, shape = CanonItemShape, modifier = modifier.fillMaxWidth()) {
        Text(
            text,
            color = fg,
            fontSize = FairBody,
            lineHeight = FairBodyLine,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(FairRowPad),
        )
    }
}

/** Поля ввода раздела: одна форма и брендовый зелёный фокус вместо фиолетового по умолчанию. */
@Composable
private fun fairFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = CanonGreen2,
    cursorColor = CanonGreen2,
    focusedLabelColor = CanonGreen2,
)

private val FairFieldShape = RoundedCornerShape(16.dp)

// ─────────────────────────── Центр справедливости ───────────────────────────

@Composable
internal fun FairnessCenterScreen(onBack: () -> Unit, onOpenIncident: (Int) -> Unit) {
    var standing by remember { mutableStateOf<StandingDto?>(null) }
    var list by remember { mutableStateOf<List<IncidentDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(reload) {
        loading = true; error = false
        ApiClient.getMyStanding().onSuccess { standing = it }
        ApiClient.getMyIncidents()
            .onSuccess { list = it }
            .onFailure { error = true }
        loading = false
    }

    // Сколько споров ждут именно тебя — самое важное число на экране.
    val waitingForMe = remember(list) { list.count { it.needsMyStatement } }

    Scaffold(
        containerColor = CanonBg,
        topBar = { ScreenTopBar(appText("Центр справедливости", "Ғәҙеллек үҙәге"), onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = FairRowPad),
            verticalArrangement = Arrangement.spacedBy(FairGap),
            contentPadding = PaddingValues(top = FairGapTight, bottom = 24.dp),
        ) {
            item(key = "lede") {
                Text(
                    appText(
                        "Здесь разбираются спорные ситуации. Правило одно: слушаем обе стороны и объясняем решение обоим.",
                        "Бында бәхәсле хәлдәр ҡарала. Ҡағиҙә бер: ике яҡты ла тыңлайбыҙ һәм ҡарарҙы икеһенә лә аңлатабыҙ.",
                    ),
                    color = CanonMutedStrong,
                    fontSize = FairBody,
                    lineHeight = FairBodyLine,
                    modifier = Modifier.appearIn(0).padding(vertical = FairGapHair),
                )
            }

            // Моё положение. Пока грузим — скелетон той же формы, чтобы карточка не «выпрыгивала».
            if (loading || standing != null) {
                item(key = "standing") {
                    AnimatedContent(
                        targetState = standing,
                        transitionSpec = { fadeIn(tween(280)) togetherWith fadeOut(tween(140)) },
                        label = "fair-standing",
                    ) { st ->
                        if (st == null) SkeletonCard(lines = 2) else StandingCard(st)
                    }
                }
            }

            item(key = "section") {
                Row(
                    Modifier.fillMaxWidth().padding(top = FairGapHair),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        appText("Мои разборы", "Минең бәхәстәр"),
                        color = CanonText,
                        fontWeight = FontWeight.Black,
                        fontSize = FairTitle,
                        lineHeight = FairTitleLine,
                        modifier = Modifier.weight(1f),
                    )
                    // Row без spacedBy → скрытая пилюля не оставляет «фантомного» отступа.
                    AnimatedVisibility(
                        visible = waitingForMe > 0,
                        enter = fadeIn(tween(240)),
                        exit = fadeOut(tween(140)),
                    ) {
                        StatusPill(
                            appText("Ждут тебя: $waitingForMe", "Һине көтә: $waitingForMe"),
                            CanonWarnBg,
                            CanonWarn,
                        )
                    }
                }
            }

            when {
                loading && list.isEmpty() -> items(2) { i ->
                    Box(Modifier.appearIn(i)) { SkeletonCard(lines = 3) }
                }
                error && list.isEmpty() -> item(key = "error") {
                    Box(Modifier.appearIn(0)) { AppErrorState(onRetry = { reload++ }) }
                }
                list.isEmpty() -> item(key = "empty") {
                    Box(Modifier.appearIn(0)) {
                        AppEmptyState(
                            title = appText("Споров нет", "Бәхәс юҡ"),
                            text = appText(
                                "И пусть так и останется. Если что-то случится — спор можно открыть из завершённой поездки или доставки.",
                                "Шулай ҡалһын. Берәй хәл булһа — тамамланған сәфәрҙән йәки илтеүҙән бәхәс асып була.",
                            ),
                            icon = Icons.Default.Handshake,
                            actionLabel = appText("Обновить", "Яңыртыу"),
                            onAction = { reload++ },
                        )
                    }
                }
                else -> itemsIndexed(list, key = { _, inc -> inc.id }) { i, inc ->
                    Box(Modifier.appearIn(i.coerceAtMost(6))) {
                        IncidentRow(inc, onClick = { onOpenIncident(inc.id) })
                    }
                }
            }
        }
    }
}

/** Моё положение: Надёжность + страйки + пауза. Формулировки без запугивания. */
@Composable
private fun StandingCard(st: StandingDto) {
    val paused = !st.canAct
    // Акцент шапки меняется плавно: «всё в порядке» ↔ «пауза» без резкого перекраса.
    val accent by animateColorAsState(if (paused) CanonRed else CanonGreen2, tween(320), label = "fair-accent")
    val accentBg by animateColorAsState(if (paused) CanonDangerBg else CanonMint, tween(320), label = "fair-accentBg")
    // Надёжность подрастает до своего значения — число «оживает», а не подставляется.
    var counted by remember { mutableStateOf(false) }
    LaunchedEffect(st.reliability) { counted = true }
    val reliability by animateIntAsState(if (counted) st.reliability else 0, tween(760), label = "fair-reliability")
    val reliabilityTint = if (st.reliability >= 60) CanonGreen2 else CanonWarn

    val okTitle = appText("Всё в порядке", "Бөтәһе лә тәртиптә")
    val pausedTitle = appText("Аккаунт на паузе", "Аккаунт паузала")

    AppCard {
        Column(Modifier.padding(FairCardPad), verticalArrangement = Arrangement.spacedBy(FairGap)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(
                    icon = if (paused) Icons.Default.Report else Icons.Default.Shield,
                    tint = accent,
                    bg = accentBg,
                    description = if (paused) pausedTitle else okTitle,
                )
                Spacer(Modifier.width(FairGap))
                AnimatedContent(
                    targetState = paused,
                    transitionSpec = { fadeIn(tween(240)) togetherWith fadeOut(tween(140)) },
                    label = "fair-standingTitle",
                    modifier = Modifier.weight(1f),
                ) { isPaused ->
                    Column(verticalArrangement = Arrangement.spacedBy(FairGapHair)) {
                        Text(
                            if (isPaused) pausedTitle else okTitle,
                            color = CanonText,
                            fontWeight = FontWeight.Black,
                            fontSize = FairTitle,
                            lineHeight = FairTitleLine,
                        )
                        Text(
                            if (isPaused) appText("Новые заказы пока недоступны", "Яңы заказдар әлегә юҡ")
                            else appText("С тобой спокойно ехать", "Һинең менән тыныс барырға"),
                            color = CanonMuted,
                            fontSize = FairMeta,
                            lineHeight = FairMetaLine,
                        )
                    }
                }
            }

            // IntrinsicSize.Max: длинное башкирское «Ышаныслылыҡ» переносится на две строки,
            // но все три плитки остаются одной высоты — низ ряда не «рвётся».
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(FairGapTight),
            ) {
                StandingTile(
                    appText("Надёжность", "Ышаныслылыҡ"), "$reliability%", reliabilityTint,
                    Modifier.weight(1f).fillMaxHeight(),
                )
                StandingTile(
                    appText("Предупреждений", "Иҫкәртеү"), st.warnings.toString(),
                    if (st.warnings > 0) CanonWarn else CanonMutedStrong,
                    Modifier.weight(1f).fillMaxHeight(),
                )
                StandingTile(
                    appText("Страйков", "Страйк"), st.strikes.toString(),
                    if (st.strikes > 0) CanonRed else CanonMutedStrong,
                    Modifier.weight(1f).fillMaxHeight(),
                )
            }

            if (paused && st.suspendReason.isNotBlank()) {
                Surface(color = CanonDangerBg, shape = CanonItemShape, modifier = Modifier.appearIn(1)) {
                    Column(
                        Modifier.fillMaxWidth().padding(FairRowPad),
                        verticalArrangement = Arrangement.spacedBy(FairGapHair),
                    ) {
                        Text(st.suspendReason, color = CanonRed, fontSize = FairBody, lineHeight = FairBodyLine)
                        st.suspendedUntil?.let {
                            // Порядок слов разный: по-русски предлог впереди, по-башкирски послелог сзади.
                            Text(
                                appText("До " + formatDepart(it), formatDepart(it) + " ҡәҙәр"),
                                color = CanonRed,
                                fontSize = FairMeta,
                                lineHeight = FairMetaLine,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }

            if (st.ratingShield) {
                Surface(color = CanonMint, shape = CanonItemShape, modifier = Modifier.appearIn(2)) {
                    Row(
                        Modifier.fillMaxWidth().padding(FairRowPad),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Shield,
                            contentDescription = appText("Щит рейтинга", "Рейтинг ҡалҡаны"),
                            tint = CanonGreen2,
                            modifier = Modifier.size(FairIcon),
                        )
                        Spacer(Modifier.width(FairGap))
                        Text(
                            appText(
                                "Спорная оценка исключена из твоего рейтинга — мы разобрались, что она была несправедливой.",
                                "Бәхәсле баһа рейтингыңдан алып ташланды — уның ғәҙелһеҙ булғанын асыҡланыҡ.",
                            ),
                            color = CanonGreen2,
                            fontSize = FairMeta,
                            lineHeight = FairMetaLine,
                        )
                    }
                }
            }

            Text(
                appText(
                    "Надёжность — это доля поездок, которые прошли без срывов. Она растёт сама, когда всё хорошо.",
                    "Ышаныслылыҡ — өҙөлөүһеҙ үткән сәфәрҙәр өлөшө. Бөтәһе лә яҡшы барһа, ул үҙе үҫә.",
                ),
                color = CanonMuted,
                fontSize = FairMeta,
                lineHeight = FairMetaLine,
            )
        }
    }
}

@Composable
private fun StandingTile(label: String, value: String, tint: Color, modifier: Modifier = Modifier) {
    Surface(color = CanonBg, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder), modifier = modifier) {
        Column(
            Modifier.fillMaxWidth().heightIn(min = 96.dp).padding(vertical = FairRowPad, horizontal = FairGapTight),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(FairGapHair, Alignment.CenterVertically),
        ) {
            Text(
                value,
                color = tint,
                fontWeight = FontWeight.Black,
                fontSize = FairMetric,
                lineHeight = FairMetricLine,
                maxLines = 1,
            )
            Text(
                label,
                color = CanonMuted,
                fontSize = FairMeta,
                lineHeight = FairMetaLine,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun IncidentRow(inc: IncidentDto, onClick: () -> Unit) {
    val (bg, fg) = statusColors(inc.status)
    // Статус меняется после действия (объяснил → «На разборе») — цвет переезжает плавно.
    val pillBg by animateColorAsState(bg, tween(300), label = "fair-rowBg")
    val pillFg by animateColorAsState(fg, tween(300), label = "fair-rowFg")
    val statusText = incidentStatusLabel(inc.status)

    AppCard(onClick = onClick, shape = CanonItemShape) {
        Column(Modifier.padding(FairRowPad), verticalArrangement = Arrangement.spacedBy(FairGap)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(statusIcon(inc.status), pillFg, pillBg, statusText)
                Spacer(Modifier.width(FairGap))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FairGapHair)) {
                    Text(
                        incidentTypeLabel(inc.type),
                        color = CanonText,
                        fontWeight = FontWeight.Black,
                        fontSize = FairTitle,
                        lineHeight = FairTitleLine,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        (inc.route ?: appText("Без маршрута", "Маршрутһыҙ")) + " · " + formatDepart(inc.createdAt),
                        color = CanonMuted,
                        fontSize = FairMeta,
                        lineHeight = FairMetaLine,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // Классическая «двухконцевая» строка: слева статус, справа — кто открыл спор.
            // Подпись справа тянется весом и обрезается многоточием, поэтому длинный
            // башкирский статус ничего не ломает — просто отъедает место у второстепенного.
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill(statusText, pillBg, pillFg)
                Spacer(Modifier.width(FairGapTight))
                Text(
                    if (inc.myRole == "reporter") appText("Ты открыл · ", "Һин астың · ") + inc.otherName
                    else appText("Открыл ", "Асҡан: ") + inc.otherName,
                    color = CanonMutedStrong,
                    fontSize = FairMeta,
                    lineHeight = FairMetaLine,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
            }
            // Главное — не потерять своё право объясниться: подсвечиваем, когда ход за тобой.
            if (inc.needsMyStatement) {
                FairNotice(
                    appText("Твоя очередь: расскажи, как было", "Һинең сират: нисек булғанын һөйлә"),
                    CanonWarnBg,
                    CanonWarn,
                )
            }
        }
    }
}

// ─────────────────────────── Карточка спора ───────────────────────────

@Composable
internal fun IncidentDetailScreen(incidentId: Int, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var inc by remember(incidentId) { mutableStateOf<IncidentDto?>(null) }
    var loading by remember(incidentId) { mutableStateOf(true) }
    var error by remember(incidentId) { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }

    var statement by remember(incidentId) { mutableStateOf("") }
    var appealText by remember(incidentId) { mutableStateOf("") }
    var photos by remember(incidentId) { mutableStateOf<List<String>>(emptyList()) }
    var uploading by remember(incidentId) { mutableStateOf(false) }
    var busy by remember(incidentId) { mutableStateOf(false) }
    var errText by remember(incidentId) { mutableStateOf<String?>(null) }
    var confirmPeace by remember(incidentId) { mutableStateOf(false) }
    var appealOpen by remember(incidentId) { mutableStateOf(false) }

    val errFallback = appText("Не получилось. Проверь сеть и повтори.", "Булманы. Селтәрҙе тикшереп ҡабатла.")
    val uploadFail = appText("Фото не загрузилось, попробуй ещё раз", "Фото йөкләнмәне, тағы ҡабатла")

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        uploading = true; errText = null
        scope.launch {
            val bytes = withContext(Dispatchers.IO) { decodeToJpeg(ctx, uri) }
            if (bytes == null) { uploading = false; errText = uploadFail; return@launch }
            ApiClient.uploadEvidence(bytes)
                .onSuccess { url -> if (url.isNotBlank()) photos = photos + url }
                .onFailure { errText = uploadFail }
            uploading = false
        }
    }

    LaunchedEffect(incidentId, reload) {
        loading = true; error = false
        ApiClient.getIncident(incidentId)
            .onSuccess { inc = it }
            .onFailure { error = true }
        loading = false
    }

    fun act(block: suspend () -> Result<IncidentDto>) {
        if (busy) return
        busy = true; errText = null
        scope.launch {
            block()
                .onSuccess { inc = it; statement = ""; appealText = ""; photos = emptyList() }
                .onFailure { errText = (it as? ApiException)?.message ?: errFallback }
            busy = false
        }
    }

    Scaffold(
        containerColor = CanonBg,
        topBar = { ScreenTopBar(appText("Разбор спора", "Бәхәсте ҡарау"), onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = FairRowPad),
            verticalArrangement = Arrangement.spacedBy(FairGap),
            contentPadding = PaddingValues(top = FairGapTight, bottom = 24.dp),
        ) {
            val i = inc
            when {
                loading && i == null -> items(3) { n ->
                    Box(Modifier.appearIn(n)) { SkeletonCard(lines = 3) }
                }
                i == null -> item(key = "error") {
                    Box(Modifier.appearIn(0)) { AppErrorState(onRetry = { reload++ }) }
                }
                else -> {
                    item(key = "header") { Box(Modifier.appearIn(0)) { IncidentHeaderCard(i) } }
                    item(key = "side-reporter") {
                        Box(Modifier.appearIn(1)) {
                            IncidentSideCard(
                                title = if (i.myRole == "reporter") appText("Твоя версия", "Һинең версия")
                                else appText("Версия ${i.otherName}", "${i.otherName} версияһы"),
                                text = i.description,
                                photos = i.evidenceUrls,
                            )
                        }
                    }
                    if (i.respondentStatement.isNotBlank()) {
                        item(key = "side-respondent") {
                            Box(Modifier.appearIn(2)) {
                                IncidentSideCard(
                                    title = if (i.myRole == "respondent") appText("Твоё объяснение", "Һинең аңлатма")
                                    else appText("Объяснение ${i.otherName}", "${i.otherName} аңлатмаһы"),
                                    text = i.respondentStatement,
                                    photos = i.respondentEvidenceUrls,
                                )
                            }
                        }
                    }
                    if (i.resolution.isNotBlank()) {
                        item(key = "verdict") { Box(Modifier.appearIn(3)) { IncidentVerdictCard(i) } }
                    }

                    // 1. Право объясниться — главное в системе. Показываем крупно и первым из действий.
                    if (i.needsMyStatement) {
                        item(key = "statement") {
                            Box(Modifier.appearIn(4)) {
                                AppCard {
                                    Column(
                                        Modifier.padding(FairCardPad),
                                        verticalArrangement = Arrangement.spacedBy(FairGap),
                                    ) {
                                        StatusPill(
                                            appText("Твоя очередь", "Һинең сират"),
                                            CanonWarnBg,
                                            CanonWarn,
                                        )
                                        Text(
                                            appText("Расскажи, как было", "Нисек булғанын һөйлә"),
                                            color = CanonText,
                                            fontWeight = FontWeight.Black,
                                            fontSize = FairTitle,
                                            lineHeight = FairTitleLine,
                                        )
                                        Text(
                                            appText(
                                                "Мы не решаем ничего, пока не услышим тебя. Пиши спокойно и по делу — читать будет человек.",
                                                "Һине ишетмәйенсә бер нәмә лә хәл итмәйбеҙ. Тыныс һәм эшлекле яҙ — уны кеше уҡыясаҡ.",
                                            ),
                                            color = CanonMuted,
                                            fontSize = FairMeta,
                                            lineHeight = FairMetaLine,
                                        )
                                        OutlinedTextField(
                                            value = statement,
                                            onValueChange = { statement = it.take(2000) },
                                            label = {
                                                Text(
                                                    appText("Как было на самом деле", "Ысынында нисек булды"),
                                                    fontSize = FairBody,
                                                )
                                            },
                                            minLines = 4,
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = FairFieldShape,
                                            colors = fairFieldColors(),
                                        )
                                        EvidencePicker(photos, uploading) { pickPhoto.launch("image/*") }
                                        AppButton(
                                            text = appText("Отправить объяснение", "Аңлатманы ебәреү"),
                                            onClick = { act { ApiClient.respondIncident(i.id, statement, photos) } },
                                            enabled = statement.isNotBlank() && !uploading,
                                            loading = busy,
                                            icon = Icons.Default.Send,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 2. «Решили миром» — заявитель закрывает спор без последствий для второй стороны.
                    if (i.canWithdraw) {
                        item(key = "peace") {
                            Box(Modifier.appearIn(5)) {
                                AppCard {
                                    Column(
                                        Modifier.padding(FairCardPad),
                                        verticalArrangement = Arrangement.spacedBy(FairGap),
                                    ) {
                                        Text(
                                            appText("Договорились сами?", "Үҙегеҙ килештегеҙме?"),
                                            color = CanonText,
                                            fontWeight = FontWeight.Black,
                                            fontSize = FairTitle,
                                            lineHeight = FairTitleLine,
                                        )
                                        Text(
                                            appText(
                                                "Закроем спор миром — без страйков и последствий для обоих. Это лучший исход, и мы за него.",
                                                "Бәхәсте тыныслыҡ менән ябабыҙ — икегеҙгә лә страйксыҙ һәм эҙемтәһеҙ. Был иң яҡшы юл.",
                                            ),
                                            color = CanonMuted,
                                            fontSize = FairMeta,
                                            lineHeight = FairMetaLine,
                                        )
                                        AppButton(
                                            text = appText("Мы решили миром", "Тыныслыҡ менән хәл иттек"),
                                            onClick = { confirmPeace = true },
                                            style = AppButtonStyle.Secondary,
                                            icon = Icons.Default.Handshake,
                                            enabled = !busy,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 3. Апелляция — один раз, только на вынесенное решение.
                    if (i.canAppeal) {
                        item(key = "appeal") {
                            Box(Modifier.appearIn(6)) {
                                AppCard {
                                    Column(
                                        Modifier.padding(FairCardPad),
                                        verticalArrangement = Arrangement.spacedBy(FairGap),
                                    ) {
                                        Text(
                                            appText("Не согласен с решением?", "Ҡарар менән килешмәйһеңме?"),
                                            color = CanonText,
                                            fontWeight = FontWeight.Black,
                                            fontSize = FairTitle,
                                            lineHeight = FairTitleLine,
                                        )
                                        Text(
                                            appText(
                                                "Апелляцию можно подать один раз. Напиши, что, по-твоему, не учли — решение пересмотрит человек.",
                                                "Ялыуҙы бер тапҡыр бирергә була. Нимә иҫәпкә алынмаған, шуны яҙ — ҡарарҙы кеше ҡабат ҡарай.",
                                            ),
                                            color = CanonMuted,
                                            fontSize = FairMeta,
                                            lineHeight = FairMetaLine,
                                        )
                                        AppButton(
                                            text = appText("Подать апелляцию", "Ялыу бирергә"),
                                            onClick = { appealOpen = true },
                                            style = AppButtonStyle.Secondary,
                                            icon = Icons.Default.Report,
                                            enabled = !busy,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (i.appealStatus.isNotBlank()) {
                        item(key = "appeal-status") {
                            Box(Modifier.appearIn(7)) {
                                FairNotice(
                                    appText(
                                        "Апелляция подана — ждём разбора человеком.",
                                        "Ялыу бирелде — кешенең ҡарауын көтәбеҙ.",
                                    ),
                                    CanonWarnBg,
                                    CanonWarn,
                                )
                            }
                        }
                    }

                    // Ошибка действия — последним элементом: и появляется рядом с кнопкой,
                    // и «фантомный» отступ скрытого блока прячется в нижнем контент-паддинге.
                    item(key = "act-error") {
                        AnimatedVisibility(
                            visible = errText != null,
                            enter = fadeIn(tween(220)),
                            exit = fadeOut(tween(140)),
                        ) {
                            FairNotice(errText.orEmpty(), CanonDangerBg, CanonRed)
                        }
                    }
                }
            }
        }
    }

    if (confirmPeace) {
        val i = inc
        AlertDialog(
            onDismissRequest = { confirmPeace = false },
            containerColor = CanonSurface,
            shape = CanonCardShape,
            title = {
                Text(
                    appText("Закрыть спор миром?", "Бәхәсте тыныслыҡ менән ябырғамы?"),
                    color = CanonText,
                    fontWeight = FontWeight.Black,
                )
            },
            text = {
                Text(
                    appText(
                        "Спор закроется без последствий для второй стороны. Открыть его заново по этой же поездке будет нельзя.",
                        "Бәхәс икенсе яҡ өсөн эҙемтәһеҙ ябыла. Ошо сәфәр буйынса уны ҡабат асып булмаясаҡ.",
                    ),
                    color = CanonMuted,
                    fontSize = FairBody,
                    lineHeight = FairBodyLine,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    modifier = Modifier.heightIn(min = FairTouch),
                    onClick = {
                        confirmPeace = false
                        if (i != null) act { ApiClient.withdrawIncident(i.id) }
                    },
                ) {
                    Text(
                        appText("Да, решили миром", "Эйе, килештек"),
                        color = CanonGreen2,
                        fontWeight = FontWeight.Bold,
                        fontSize = FairBody,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmPeace = false }, modifier = Modifier.heightIn(min = FairTouch)) {
                    Text(appText("Отмена", "Кире алыу"), color = CanonMuted, fontSize = FairBody)
                }
            },
        )
    }

    if (appealOpen) {
        val i = inc
        AlertDialog(
            onDismissRequest = { if (!busy) appealOpen = false },
            containerColor = CanonSurface,
            shape = CanonCardShape,
            title = { Text(appText("Апелляция", "Ялыу"), color = CanonText, fontWeight = FontWeight.Black) },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(FairGap),
                ) {
                    Text(
                        appText("Что, по-твоему, не учли при решении?", "Ҡарар ҡабул иткәндә нимә иҫәпкә алынмаған?"),
                        color = CanonMuted,
                        fontSize = FairBody,
                        lineHeight = FairBodyLine,
                    )
                    OutlinedTextField(
                        value = appealText,
                        onValueChange = { appealText = it.take(2000) },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                        shape = FairFieldShape,
                        colors = fairFieldColors(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy && appealText.isNotBlank(),
                    modifier = Modifier.heightIn(min = FairTouch),
                    onClick = {
                        appealOpen = false
                        if (i != null) act { ApiClient.appealIncident(i.id, appealText) }
                    },
                ) {
                    Text(appText("Подать", "Бирергә"), color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = FairBody)
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !busy,
                    onClick = { appealOpen = false },
                    modifier = Modifier.heightIn(min = FairTouch),
                ) {
                    Text(appText("Отмена", "Кире алыу"), color = CanonMuted, fontSize = FairBody)
                }
            },
        )
    }
}

@Composable
private fun IncidentHeaderCard(i: IncidentDto) {
    val (bg, fg) = statusColors(i.status)
    val badgeBg by animateColorAsState(bg, tween(300), label = "fair-headBg")
    val badgeFg by animateColorAsState(fg, tween(300), label = "fair-headFg")
    val statusText = incidentStatusLabel(i.status)

    AppCard {
        Column(Modifier.padding(FairCardPad), verticalArrangement = Arrangement.spacedBy(FairGap)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(statusIcon(i.status), badgeFg, badgeBg, statusText)
                Spacer(Modifier.width(FairGap))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FairGapHair)) {
                    Text(
                        incidentTypeLabel(i.type),
                        color = CanonText,
                        fontWeight = FontWeight.Black,
                        fontSize = FairTitle,
                        lineHeight = FairTitleLine,
                    )
                    AnimatedContent(
                        targetState = statusText,
                        transitionSpec = { fadeIn(tween(240)) togetherWith fadeOut(tween(140)) },
                        label = "fair-headStatus",
                    ) { s ->
                        Text(s, color = badgeFg, fontSize = FairMeta, lineHeight = FairMetaLine, fontWeight = FontWeight.Bold)
                    }
                }
            }
            i.route?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = CanonMutedStrong, fontSize = FairBody, lineHeight = FairBodyLine)
            }
            Text(
                appText("Вторая сторона: ", "Икенсе яҡ: ") + i.otherName + " · " + formatDepart(i.createdAt),
                color = CanonMuted,
                fontSize = FairMeta,
                lineHeight = FairMetaLine,
            )
        }
    }
}

/** Версия одной стороны: текст + приложенные фото (миниатюрами не грузим — приватная область). */
@Composable
private fun IncidentSideCard(title: String, text: String, photos: List<String>) {
    AppCard(shape = CanonItemShape) {
        Column(Modifier.padding(FairRowPad), verticalArrangement = Arrangement.spacedBy(FairGapTight)) {
            // Заголовок стороны — это подпись «кто говорит», а не заголовок карточки:
            // роль Meta/Bold, чтобы читалась сама история, а не её ярлык.
            Text(
                title,
                color = CanonMutedStrong,
                fontSize = FairMeta,
                lineHeight = FairMetaLine,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text.ifBlank { appText("Без описания", "Тасуирламаһыҙ") },
                color = if (text.isBlank()) CanonMuted else CanonText,
                fontSize = FairBody,
                lineHeight = FairBodyLine,
            )
            if (photos.isNotEmpty()) {
                StatusPill(
                    appText("Приложено фото: ${photos.size}", "Фото тағылған: ${photos.size}"),
                    CanonMint,
                    CanonGreen2,
                )
            }
        }
    }
}

/** Решение: главное — человеческое объяснение, которое видят ОБЕ стороны одинаковым текстом. */
@Composable
private fun IncidentVerdictCard(i: IncidentDto) {
    AppCard {
        Column(Modifier.padding(FairCardPad), verticalArrangement = Arrangement.spacedBy(FairGap)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(Icons.Default.CheckCircle, CanonGreen2, CanonMint)
                Spacer(Modifier.width(FairGap))
                Text(
                    appText("Решение", "Ҡарар"),
                    color = CanonText,
                    fontWeight = FontWeight.Black,
                    fontSize = FairTitle,
                    lineHeight = FairTitleLine,
                )
            }
            Text(
                i.resolutionNote.ifBlank { appText("Решение принято.", "Ҡарар ҡабул ителде.") },
                color = CanonText,
                fontSize = FairBody,
                lineHeight = FairBodyLine,
            )
            if (i.compensationKop > 0) {
                Surface(color = CanonMint, shape = CanonItemShape) {
                    Column(
                        Modifier.fillMaxWidth().padding(FairRowPad),
                        verticalArrangement = Arrangement.spacedBy(FairGapHair),
                    ) {
                        Text(
                            appText("Компенсация", "Компенсация"),
                            color = CanonGreen2,
                            fontSize = FairMeta,
                            lineHeight = FairMetaLine,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            kopToRub(i.compensationKop),
                            color = CanonGreen2,
                            fontWeight = FontWeight.Black,
                            fontSize = FairTitle,
                            lineHeight = FairTitleLine,
                        )
                        Text(
                            appText(
                                "Переводом напрямую, как договоритесь.",
                                "Килешкәнсә, туранан-тура күсереп.",
                            ),
                            color = CanonMutedStrong,
                            fontSize = FairMeta,
                            lineHeight = FairMetaLine,
                        )
                    }
                }
            }
            i.resolvedAt?.let {
                // Русский: «Решено 12.07, 14:30». Башкирский: дата впереди, глагол сзади.
                Text(
                    appText("Решено " + formatDepart(it), formatDepart(it) + " — хәл ителде"),
                    color = CanonMuted,
                    fontSize = FairMeta,
                    lineHeight = FairMetaLine,
                )
            }
        }
    }
}

/** Прикрепление фото-доказательств: приватные, видят только стороны спора и разбирающий. */
@Composable
private fun EvidencePicker(photos: List<String>, uploading: Boolean, onPick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(FairGapTight)) {
        if (photos.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(FairGapTight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                photos.forEachIndexed { idx, _ ->
                    Surface(color = CanonMint, shape = CanonItemShape, modifier = Modifier.appearIn(idx.coerceAtMost(4))) {
                        Row(
                            Modifier.padding(horizontal = FairGap, vertical = FairGapTight),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = CanonGreen2,
                                modifier = Modifier.size(FairIconSmall),
                            )
                            Spacer(Modifier.width(FairGapTight))
                            Text(
                                appText("Фото ${idx + 1}", "Фото ${idx + 1}"),
                                color = CanonGreen2,
                                fontSize = FairMeta,
                                lineHeight = FairMetaLine,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
        AppButton(
            text = if (uploading) appText("Загружаем фото…", "Фото йөкләнә…")
            else appText("Приложить фото", "Фото тағыу"),
            onClick = onPick,
            style = AppButtonStyle.Secondary,
            icon = Icons.Default.PhotoCamera,
            loading = uploading,
            enabled = !uploading && photos.size < 10,
        )
        Text(
            appText(
                "Фото видят только вы двое и тот, кто разбирает спор. В общий доступ они не попадают.",
                "Фотоны тик икегеҙ һәм бәхәсте ҡараусы күрә. Дөйөм ҡулланыуға улар эләкмәй.",
            ),
            color = CanonMuted,
            fontSize = FairMeta,
            lineHeight = FairMetaLine,
        )
    }
}

// ─────────────────────────── Подача спора по поездке ───────────────────────────

/**
 * Диалог «Открыть разбор» по завершённой поездке. Переиспользуемый: вызывается из деталей
 * брони (попутка) и из финальной карточки такси-заказа.
 *
 * Почему это отдельно от жалобы (`Report`): жалоба анонимна и односторонняя, а разбор —
 * двусторонний: вторую сторону пригласят объясниться, и решение объяснят обоим.
 *
 * Содержимое ОБЯЗАТЕЛЬНО прокручивается: девять типов спора + поле + фото не помещаются
 * в диалог даже на большом телефоне, а Material-диалог сам не скроллит — без этого нижние
 * типы и кнопка «Открыть разбор» просто обрезались бы.
 */
@Composable
internal fun FileIncidentDialog(
    respondentId: Int,
    respondentName: String,
    bookingId: Int? = null,
    orderId: Int? = null,     // такси-заказ как контекст (для попутки передают bookingId)
    onDismiss: () -> Unit,
    onFiled: (IncidentDto) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var type by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var photos by remember { mutableStateOf<List<String>>(emptyList()) }
    var uploading by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }

    val errFallback = appText("Не получилось открыть разбор. Проверь сеть.", "Ҡарауҙы асып булманы. Селтәрҙе тикшер.")
    val uploadFail = appText("Фото не загрузилось, попробуй ещё раз", "Фото йөкләнмәне, тағы ҡабатла")

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        uploading = true; err = null
        scope.launch {
            val bytes = withContext(Dispatchers.IO) { decodeToJpeg(ctx, uri) }
            if (bytes == null) { uploading = false; err = uploadFail; return@launch }
            ApiClient.uploadEvidence(bytes)
                .onSuccess { url -> if (url.isNotBlank()) photos = photos + url }
                .onFailure { err = uploadFail }
            uploading = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = CanonSurface,
        shape = CanonCardShape,
        title = { Text(appText("Открыть разбор", "Ҡарауҙы асыу"), color = CanonText, fontWeight = FontWeight.Black) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(FairGap),
            ) {
                Text(
                    appText(
                        "Мы позовём ${respondentName} объясниться и решим по-соседски. Решение объясним вам обоим.",
                        "${respondentName} кешене аңлатырға саҡырабыҙ һәм күршеләрсә хәл итәбеҙ. Ҡарарҙы икегеҙгә лә аңлатабыҙ.",
                    ),
                    color = CanonMuted,
                    fontSize = FairBody,
                    lineHeight = FairBodyLine,
                )
                Text(
                    appText("Что случилось?", "Нимә булды?"),
                    color = CanonText,
                    fontWeight = FontWeight.Black,
                    fontSize = FairTitle,
                    lineHeight = FairTitleLine,
                )
                Column(verticalArrangement = Arrangement.spacedBy(FairGapTight)) {
                    incidentTypesRide.forEach { t ->
                        IncidentTypeOption(
                            label = appText(t.ru, t.ba),
                            selected = type == t.key,
                            onClick = { type = t.key },
                        )
                    }
                }
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it.take(2000) },
                    label = { Text(appText("Как было", "Нисек булды"), fontSize = FairBody) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                    shape = FairFieldShape,
                    colors = fairFieldColors(),
                )
                EvidencePicker(photos, uploading) { pickPhoto.launch("image/*") }
                AnimatedVisibility(
                    visible = err != null,
                    enter = fadeIn(tween(220)),
                    exit = fadeOut(tween(140)),
                ) {
                    FairNotice(err.orEmpty(), CanonDangerBg, CanonRed)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && !uploading && type.isNotBlank() && description.isNotBlank(),
                modifier = Modifier.heightIn(min = FairTouch),
                onClick = {
                    busy = true; err = null
                    scope.launch {
                        ApiClient.fileIncident(respondentId, type, description, bookingId, orderId, photos)
                            .onSuccess { onFiled(it) }
                            .onFailure { err = (it as? ApiException)?.message ?: errFallback }
                        busy = false
                    }
                },
            ) {
                // Отправка не должна выглядеть как «ничего не произошло»: подпись сменяется спиннером.
                AnimatedContent(
                    targetState = busy,
                    transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                    label = "fair-fileBusy",
                ) { sending ->
                    if (sending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(FairIcon),
                            color = CanonGreen2,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(
                            appText("Открыть разбор", "Ҡарауҙы асыу"),
                            color = CanonGreen2,
                            fontWeight = FontWeight.Bold,
                            fontSize = FairBody,
                        )
                    }
                }
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss, modifier = Modifier.heightIn(min = FairTouch)) {
                Text(appText("Отмена", "Кире алыу"), color = CanonMuted, fontSize = FairBody)
            }
        },
    )
}

/** Один тип спора в списке выбора. Тач-цель 48dp, выбор подсвечивается плавно. */
@Composable
private fun IncidentTypeOption(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) CanonMint else CanonBg, tween(220), label = "fair-typeBg")
    val border by animateColorAsState(if (selected) CanonGreen2 else CanonBorder, tween(220), label = "fair-typeBorder")
    val tint by animateColorAsState(if (selected) CanonGreen2 else CanonMuted, tween(220), label = "fair-typeTint")
    Surface(
        onClick = onClick,
        color = bg,
        shape = CanonItemShape,
        border = BorderStroke(1.dp, border),
        modifier = Modifier.fillMaxWidth().heightIn(min = FairTouch),
    ) {
        Row(
            Modifier.padding(horizontal = FairGap, vertical = FairGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (selected) Icons.Default.CheckCircle else Icons.Default.Flag,
                // Галочка — единственный признак выбора, поэтому её озвучиваем; невыбранный
                // флажок декоративен, рядом стоит сам текст пункта.
                contentDescription = if (selected) appText("Выбрано", "Һайланды") else null,
                tint = tint,
                modifier = Modifier.size(FairIcon),
            )
            Spacer(Modifier.width(FairGapTight))
            Text(label, color = CanonText, fontSize = FairBody, lineHeight = FairBodyLine)
        }
    }
}
