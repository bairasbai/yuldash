package com.yuldash.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
 */

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

    Scaffold(
        containerColor = CanonBg,
        topBar = { ScreenTopBar(appText("Центр справедливости", "Ғәҙеллек үҙәге"), onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 28.dp),
        ) {
            item {
                Text(
                    appText(
                        "Здесь разбираются спорные ситуации. Правило одно: слушаем обе стороны и объясняем решение обоим.",
                        "Бында бәхәсле хәлдәр ҡарала. Ҡағиҙә бер: ике яҡты ла тыңлайбыҙ һәм ҡарарҙы икеһенә лә аңлатабыҙ.",
                    ),
                    color = CanonMuted, fontSize = 13.sp, lineHeight = 18.sp,
                )
            }
            standing?.let { st -> item { StandingCard(st) } }
            item {
                Text(appText("Мои разборы", "Минең бәхәстәр"), color = CanonText,
                    fontWeight = FontWeight.Black, fontSize = 17.sp, modifier = Modifier.padding(top = 4.dp))
            }
            when {
                loading && list.isEmpty() ->
                    item { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { repeat(2) { SkeletonCard(lines = 3) } } }
                error && list.isEmpty() -> item { AppErrorState(onRetry = { reload++ }) }
                list.isEmpty() -> item {
                    AppEmptyState(
                        title = appText("Споров нет", "Бәхәс юҡ"),
                        text = appText(
                            "И пусть так и останется. Если что-то случится — спор можно открыть из завершённой поездки или доставки.",
                            "Шулай ҡалһын. Берәй хәл булһа — тамамланған сәфәрҙән йәки илтеүҙән бәхәс асып була.",
                        ),
                        icon = Icons.Default.Handshake,
                    )
                }
                else -> items(list, key = { it.id }) { inc ->
                    IncidentRow(inc, onClick = { onOpenIncident(inc.id) })
                }
            }
        }
    }
}

/** Моё положение: Надёжность + страйки + пауза. Формулировки без запугивания. */
@Composable
private fun StandingCard(st: StandingDto) {
    val paused = !st.canAct
    AppCard {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = if (paused) CanonDangerBg else CanonMint, shape = CircleShape) {
                    Icon(
                        if (paused) Icons.Default.Report else Icons.Default.Shield,
                        contentDescription = null,
                        tint = if (paused) CanonRed else CanonGreen2,
                        modifier = Modifier.padding(12.dp).size(22.dp),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (paused) appText("Аккаунт на паузе", "Аккаунт паузала")
                        else appText("Всё в порядке", "Бөтәһе лә тәртиптә"),
                        color = CanonText, fontWeight = FontWeight.Black, fontSize = 18.sp,
                    )
                    Text(
                        if (paused) appText("Новые заказы пока недоступны", "Яңы заказдар әлегә юҡ")
                        else appText("С тобой спокойно ехать", "Һинең менән тыныс барырға"),
                        color = CanonMuted, fontSize = 13.sp,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StandingTile(appText("Надёжность", "Ышаныслылыҡ"), "${st.reliability}%", CanonGreen2, Modifier.weight(1f))
                StandingTile(
                    appText("Предупреждений", "Иҫкәртеү"), st.warnings.toString(),
                    if (st.warnings > 0) CanonWarn else CanonMuted, Modifier.weight(1f),
                )
                StandingTile(
                    appText("Страйков", "Страйк"), st.strikes.toString(),
                    if (st.strikes > 0) CanonRed else CanonMuted, Modifier.weight(1f),
                )
            }
            if (paused && st.suspendReason.isNotBlank()) {
                Surface(color = CanonDangerBg, shape = CanonItemShape) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(st.suspendReason, color = CanonRed, fontSize = 14.sp, lineHeight = 19.sp)
                        st.suspendedUntil?.let {
                            Text(appText("До ", "Ҡәҙәр ") + formatDepart(it), color = CanonRed, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            if (st.ratingShield) {
                Text(
                    appText(
                        "Спорная оценка исключена из твоего рейтинга — мы разобрались, что она была несправедливой.",
                        "Бәхәсле баһа рейтингыңдан алып ташланды — уның ғәҙелһеҙ булғанын асыҡланыҡ.",
                    ),
                    color = CanonGreen2, fontSize = 12.sp, lineHeight = 17.sp,
                )
            }
            Text(
                appText(
                    "Надёжность — это доля поездок, которые прошли без срывов. Она растёт сама, когда всё хорошо.",
                    "Ышаныслылыҡ — өҙөлөүһеҙ үткән сәфәрҙәр өлөшө. Бөтәһе лә яҡшы барһа, ул үҙе үҫә.",
                ),
                color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp,
            )
        }
    }
}

@Composable
private fun StandingTile(label: String, value: String, tint: Color, modifier: Modifier = Modifier) {
    Surface(color = CanonBg, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder), modifier = modifier) {
        Column(
            Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(value, color = tint, fontWeight = FontWeight.Black, fontSize = 22.sp)
            Text(label, color = CanonMuted, fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 2)
        }
    }
}

@Composable
private fun IncidentRow(inc: IncidentDto, onClick: () -> Unit) {
    val (bg, fg) = statusColors(inc.status)
    AppCard(onClick = onClick, shape = CanonItemShape) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(incidentTypeLabel(inc.type), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(
                        (inc.route ?: appText("Без маршрута", "Маршрутһыҙ")) + " · " + formatDepart(inc.createdAt),
                        color = CanonMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Surface(color = bg, shape = RoundedCornerShape(999.dp)) {
                    Text(incidentStatusLabel(inc.status), color = fg, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                }
            }
            Text(
                (if (inc.myRole == "reporter") appText("Ты открыл спор с ", "Һин бәхәс астың: ")
                else appText("Спор открыл ", "Бәхәсте асҡан: ")) + inc.otherName,
                color = CanonMutedStrong, fontSize = 13.sp,
            )
            // Главное — не потерять своё право объясниться: подсвечиваем, когда ход за тобой.
            if (inc.needsMyStatement) {
                Surface(color = CanonWarnBg, shape = CanonItemShape) {
                    Text(
                        appText("Твоя очередь: расскажи, как было", "Һинең сират: нисек булғанын һөйлә"),
                        color = CanonWarn, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                    )
                }
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
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 28.dp),
        ) {
            val i = inc
            when {
                loading && i == null -> item { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { repeat(3) { SkeletonCard(lines = 3) } } }
                i == null -> item { AppErrorState(onRetry = { reload++ }) }
                else -> {
                    item { IncidentHeaderCard(i) }
                    item {
                        IncidentSideCard(
                            title = if (i.myRole == "reporter") appText("Твоя версия", "Һинең версия")
                            else appText("Версия ${i.otherName}", "${i.otherName} версияһы"),
                            text = i.description,
                            photos = i.evidenceUrls,
                        )
                    }
                    if (i.respondentStatement.isNotBlank()) {
                        item {
                            IncidentSideCard(
                                title = if (i.myRole == "respondent") appText("Твоё объяснение", "Һинең аңлатма")
                                else appText("Объяснение ${i.otherName}", "${i.otherName} аңлатмаһы"),
                                text = i.respondentStatement,
                                photos = i.respondentEvidenceUrls,
                            )
                        }
                    }
                    if (i.resolution.isNotBlank()) item { IncidentVerdictCard(i) }

                    // 1. Право объясниться — главное в системе. Показываем крупно и первым.
                    if (i.needsMyStatement) {
                        item {
                            AppCard {
                                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(appText("Расскажи, как было", "Нисек булғанын һөйлә"), color = CanonText,
                                        fontWeight = FontWeight.Black, fontSize = 17.sp)
                                    Text(
                                        appText(
                                            "Мы не решаем ничего, пока не услышим тебя. Пиши спокойно и по делу — читать будет человек.",
                                            "Һине ишетмәйенсә бер нәмә лә хәл итмәйбеҙ. Тыныс һәм эшлекле яҙ — уны кеше уҡыясаҡ.",
                                        ),
                                        color = CanonMuted, fontSize = 13.sp, lineHeight = 18.sp,
                                    )
                                    OutlinedTextField(
                                        value = statement,
                                        onValueChange = { statement = it.take(2000) },
                                        label = { Text(appText("Как было на самом деле", "Ысынында нисек булды")) },
                                        minLines = 4,
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(16.dp),
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

                    // 2. «Решили миром» — заявитель закрывает спор без последствий для второй стороны.
                    if (i.canWithdraw) {
                        item {
                            AppCard {
                                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(appText("Договорились сами?", "Үҙегеҙ килештегеҙме?"), color = CanonText,
                                        fontWeight = FontWeight.Black, fontSize = 17.sp)
                                    Text(
                                        appText(
                                            "Закроем спор миром — без страйков и последствий для обоих. Это лучший исход, и мы за него.",
                                            "Бәхәсте тыныслыҡ менән ябабыҙ — икегеҙгә лә страйксыҙ һәм эҙемтәһеҙ. Был иң яҡшы юл.",
                                        ),
                                        color = CanonMuted, fontSize = 13.sp, lineHeight = 18.sp,
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

                    // 3. Апелляция — один раз, только на вынесенное решение.
                    if (i.canAppeal) {
                        item {
                            AppCard {
                                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(appText("Не согласен с решением?", "Ҡарар менән килешмәйһеңме?"), color = CanonText,
                                        fontWeight = FontWeight.Black, fontSize = 17.sp)
                                    Text(
                                        appText(
                                            "Апелляцию можно подать один раз. Напиши, что, по-твоему, не учли — решение пересмотрит человек.",
                                            "Ялыуҙы бер тапҡыр бирергә була. Нимә иҫәпкә алынмаған, шуны яҙ — ҡарарҙы кеше ҡабат ҡарай.",
                                        ),
                                        color = CanonMuted, fontSize = 13.sp, lineHeight = 18.sp,
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
                    if (i.appealStatus.isNotBlank()) {
                        item {
                            Surface(color = CanonWarnBg, shape = CanonItemShape) {
                                Text(
                                    appText("Апелляция подана — ждём разбора человеком.", "Ялыу бирелде — кешенең ҡарауын көтәбеҙ."),
                                    color = CanonWarn, fontSize = 13.sp, lineHeight = 18.sp,
                                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                                )
                            }
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

    if (confirmPeace) {
        val i = inc
        AlertDialog(
            onDismissRequest = { confirmPeace = false },
            containerColor = CanonSurface,
            title = { Text(appText("Закрыть спор миром?", "Бәхәсте тыныслыҡ менән ябырғамы?"), color = CanonText, fontWeight = FontWeight.Black) },
            text = {
                Text(
                    appText(
                        "Спор закроется без последствий для второй стороны. Открыть его заново по этой же поездке будет нельзя.",
                        "Бәхәс икенсе яҡ өсөн эҙемтәһеҙ ябыла. Ошо сәфәр буйынса уны ҡабат асып булмаясаҡ.",
                    ),
                    color = CanonMuted, fontSize = 13.sp, lineHeight = 18.sp,
                )
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    confirmPeace = false
                    if (i != null) act { ApiClient.withdrawIncident(i.id) }
                }) { Text(appText("Да, решили миром", "Эйе, килештек"), color = CanonGreen2, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { confirmPeace = false }) { Text(appText("Отмена", "Кире алыу"), color = CanonMuted) }
            },
        )
    }

    if (appealOpen) {
        val i = inc
        AlertDialog(
            onDismissRequest = { if (!busy) appealOpen = false },
            containerColor = CanonSurface,
            title = { Text(appText("Апелляция", "Ялыу"), color = CanonText, fontWeight = FontWeight.Black) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        appText("Что, по-твоему, не учли при решении?", "Ҡарар ҡабул иткәндә нимә иҫәпкә алынмаған?"),
                        color = CanonMuted, fontSize = 13.sp, lineHeight = 18.sp,
                    )
                    OutlinedTextField(
                        value = appealText,
                        onValueChange = { appealText = it.take(2000) },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = !busy && appealText.isNotBlank(), onClick = {
                    appealOpen = false
                    if (i != null) act { ApiClient.appealIncident(i.id, appealText) }
                }) { Text(appText("Подать", "Бирергә"), color = CanonGreen2, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { appealOpen = false }) { Text(appText("Отмена", "Кире алыу"), color = CanonMuted) }
            },
        )
    }
}

@Composable
private fun IncidentHeaderCard(i: IncidentDto) {
    val (bg, fg) = statusColors(i.status)
    AppCard {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = bg, shape = CircleShape) {
                    Icon(Icons.Default.Shield, contentDescription = null, tint = fg, modifier = Modifier.padding(12.dp).size(22.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(incidentTypeLabel(i.type), color = CanonText, fontWeight = FontWeight.Black, fontSize = 18.sp)
                    Text(incidentStatusLabel(i.status), color = fg, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
            i.route?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = CanonMutedStrong, fontSize = 14.sp)
            }
            Text(
                appText("Вторая сторона: ", "Икенсе яҡ: ") + i.otherName + " · " + formatDepart(i.createdAt),
                color = CanonMuted, fontSize = 12.sp,
            )
        }
    }
}

/** Версия одной стороны: текст + приложенные фото (миниатюрами не грузим — приватная область). */
@Composable
private fun IncidentSideCard(title: String, text: String, photos: List<String>) {
    AppCard(shape = CanonItemShape) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(
                text.ifBlank { appText("Без описания", "Тасуирламаһыҙ") },
                color = if (text.isBlank()) CanonMuted else CanonText,
                fontSize = 14.sp, lineHeight = 20.sp,
            )
            if (photos.isNotEmpty()) {
                Text(
                    appText("Приложено фото: ${photos.size}", "Фото тағылған: ${photos.size}"),
                    color = CanonGreen2, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/** Решение: главное — человеческое объяснение, которое видят ОБЕ стороны одинаковым текстом. */
@Composable
private fun IncidentVerdictCard(i: IncidentDto) {
    AppCard {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = CanonMint, shape = CircleShape) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CanonGreen2,
                        modifier = Modifier.padding(10.dp).size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text(appText("Решение", "Ҡарар"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 17.sp)
            }
            Text(
                i.resolutionNote.ifBlank { appText("Решение принято.", "Ҡарар ҡабул ителде.") },
                color = CanonText, fontSize = 14.sp, lineHeight = 20.sp,
            )
            if (i.compensationKop > 0) {
                Surface(color = CanonMint, shape = CanonItemShape) {
                    Text(
                        appText("Компенсация: ", "Компенсация: ") + kopToRub(i.compensationKop) +
                            appText(" — переводом напрямую, как договоритесь.", " — килешкәнсә, туранан-тура күсереп."),
                        color = CanonGreen2, fontSize = 13.sp, lineHeight = 18.sp,
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                    )
                }
            }
            i.resolvedAt?.let {
                Text(appText("Решено ", "Хәл ителде ") + formatDepart(it), color = CanonMuted, fontSize = 12.sp)
            }
        }
    }
}

/** Прикрепление фото-доказательств: приватные, видят только стороны спора и разбирающий. */
@Composable
private fun EvidencePicker(photos: List<String>, uploading: Boolean, onPick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            photos.forEachIndexed { idx, _ ->
                Surface(color = CanonMint, shape = CanonItemShape) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(appText("Фото ${idx + 1}", "Фото ${idx + 1}"), color = CanonGreen2, fontSize = 13.sp, fontWeight = FontWeight.Bold)
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
            color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp,
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
        title = { Text(appText("Открыть разбор", "Ҡарауҙы асыу"), color = CanonText, fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    appText(
                        "Мы позовём ${respondentName} объясниться и решим по-соседски. Решение объясним вам обоим.",
                        "${respondentName} кешене аңлатырға саҡырабыҙ һәм күршеләрсә хәл итәбеҙ. Ҡарарҙы икегеҙгә лә аңлатабыҙ.",
                    ),
                    color = CanonMuted, fontSize = 13.sp, lineHeight = 18.sp,
                )
                Text(appText("Что случилось?", "Нимә булды?"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    incidentTypesRide.forEach { t ->
                        Surface(
                            onClick = { type = t.key },
                            color = if (type == t.key) CanonMint else CanonBg,
                            shape = CanonItemShape,
                            border = BorderStroke(1.dp, if (type == t.key) CanonGreen2 else CanonBorder),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (type == t.key) Icons.Default.CheckCircle else Icons.Default.Flag,
                                    contentDescription = null,
                                    tint = if (type == t.key) CanonGreen2 else CanonMuted,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(appText(t.ru, t.ba), color = CanonText, fontSize = 14.sp)
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it.take(2000) },
                    label = { Text(appText("Как было", "Нисек булды")) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                )
                EvidencePicker(photos, uploading) { pickPhoto.launch("image/*") }
                err?.let { Text(it, color = CanonRed, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && !uploading && type.isNotBlank() && description.isNotBlank(),
                onClick = {
                    busy = true; err = null
                    scope.launch {
                        ApiClient.fileIncident(respondentId, type, description, bookingId, orderId, photos)
                            .onSuccess { onFiled(it) }
                            .onFailure { err = (it as? ApiException)?.message ?: errFallback }
                        busy = false
                    }
                },
            ) { Text(appText("Открыть разбор", "Ҡарауҙы асыу"), color = CanonGreen2, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text(appText("Отмена", "Кире алыу"), color = CanonMuted) }
        },
    )
}
