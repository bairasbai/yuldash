package com.yuldash.app

// ════════════════════════════════════════════════════════════════════════════
//  Система «Справедливость» (Trust, Safety & Fairness) — весь UI в одном файле.
//  Контракт: docs/trust-safety.md §7. Хаб + жалоба + споры + деталь + правила + админ.
//  Тон: тёплый, по-соседски, спокойный. Спор — не «уголовка»: мягкие цвета, объяснения,
//  право на ответ, «решить миром» вперёд наказания.
// ════════════════════════════════════════════════════════════════════════════

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.MoneyOff
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.SentimentDissatisfied
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Sos
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuldash.app.data.AdminIncidentDto
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.StandingDto
import kotlinx.coroutines.launch

// ─────────────────────────── Токены-хелперы справедливости ───────────────────────────

/** Иконка типа инцидента (§1). */
internal fun incidentTypeIcon(type: IncidentType): ImageVector = when (type) {
    IncidentType.PassengerNoShow -> Icons.Default.PersonOff
    IncidentType.DriverNoShow -> Icons.Default.DirectionsCar
    IncidentType.NonPayment -> Icons.Default.MoneyOff
    IncidentType.Rude -> Icons.Default.SentimentDissatisfied
    IncidentType.Unsafe -> Icons.Default.Warning
    IncidentType.Harassment -> Icons.Default.Gavel
    IncidentType.RouteDetour -> Icons.Default.Route
    IncidentType.Overcharge -> Icons.Default.Sell
    IncidentType.RulesViolation -> Icons.Default.Rule
    IncidentType.ParcelDamage -> Icons.Default.Inventory2
    IncidentType.ParcelLost -> Icons.Default.SearchOff
    IncidentType.ParcelDelay -> Icons.Default.Schedule
    IncidentType.RecipientAbsent -> Icons.Default.PersonOff
    IncidentType.WrongContents -> Icons.Default.Block
    IncidentType.Other -> Icons.Default.Flag
}

@Composable
internal fun standingLabel(standing: Standing): String = when (standing) {
    Standing.Good -> appText("Всё хорошо", "Бөтәһе яҡшы")
    Standing.Warned -> appText("Замечание", "Иҫкәртеү")
    Standing.Limited -> appText("Ограничения", "Сикләүҙәр")
    Standing.Suspended -> appText("Пауза аккаунта", "Иҫәп паузаһы")
}

/** Цвет текста бейджа standing (адаптивный, из Canon*). */
internal val Standing.ink: Color
    @Composable get() = when (this) {
        Standing.Good -> CanonGreen2
        Standing.Warned, Standing.Limited -> CanonWarn
        Standing.Suspended -> CanonRed
    }

/** Подложка бейджа standing (адаптивная). */
internal val Standing.bg: Color
    @Composable get() = when (this) {
        Standing.Good -> CanonMint
        Standing.Warned, Standing.Limited -> CanonWarnBg
        Standing.Suspended -> CanonDangerBg
    }

@Composable
internal fun incidentStatusLabel(status: IncidentStatus): String = when (status) {
    IncidentStatus.Open -> appText("Открыт", "Асыҡ")
    IncidentStatus.AwaitingResponse -> appText("Ждём ответ", "Яуап көтөлә")
    IncidentStatus.UnderReview -> appText("На разборе", "Ҡаралышта")
    IncidentStatus.Resolved -> appText("Решён", "Хәл ителде")
    IncidentStatus.Appealed -> appText("Обжалован", "Шикәйәт ителгән")
    IncidentStatus.Closed -> appText("Закрыт", "Ябылған")
}

internal val IncidentStatus.ink: Color
    @Composable get() = when (this) {
        IncidentStatus.Resolved, IncidentStatus.Closed -> CanonGreen2
        IncidentStatus.UnderReview, IncidentStatus.Appealed -> CanonWarn
        else -> CanonMuted
    }

internal val IncidentStatus.bg: Color
    @Composable get() = when (this) {
        IncidentStatus.Resolved, IncidentStatus.Closed -> CanonMint
        IncidentStatus.UnderReview, IncidentStatus.Appealed -> CanonWarnBg
        else -> CanonMint
    }

@Composable
internal fun resolutionLabel(resolution: String): String = when (resolution) {
    "dismissed" -> appText("Отклонён — без последствий", "Кире ҡағылды — эҙемтәһеҙ")
    "warning" -> appText("Предупреждение", "Иҫкәртеү")
    "strike" -> appText("Страйк", "Страйк")
    "compensation" -> appText("Предложена компенсация", "Компенсация тәҡдим ителде")
    "rating_adjust" -> appText("Оценка скорректирована", "Баһа төҙәтелде")
    "suspend" -> appText("Пауза аккаунта", "Иҫәп паузаһы")
    "ban" -> appText("Блокировка", "Блоклау")
    "mutual_resolved" -> appText("Решено миром", "Тыныслыҡ менән хәл ителде")
    else -> appText("Решение не принято", "Ҡарар ҡабул ителмәгән")
}

@Composable
internal fun faultLabel(fault: String): String = when (fault) {
    "reporter" -> appText("заявитель", "ялыусы")
    "respondent" -> appText("вторая сторона", "икенсе яҡ")
    "both" -> appText("обе стороны", "ике яҡ")
    "unclear" -> appText("не установлено", "асыҡланмаған")
    else -> appText("вины нет", "ғәйеп юҡ")
}

// ─────────────────────────── TrustBadge (переиспользуемый) ───────────────────────────

/** Бейдж доверия: состояние + «Надёжность N%». Ставится на карточки пользователя/водителя. */
@Composable
internal fun TrustBadge(
    standing: Standing,
    reliability: Int,
    modifier: Modifier = Modifier,
    showStanding: Boolean = true,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (showStanding) {
            Surface(color = standing.bg, shape = RoundedCornerShape(999.dp)) {
                Row(Modifier.padding(horizontal = 9.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Shield, contentDescription = null, tint = standing.ink, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(standingLabel(standing), color = standing.ink, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        val relColor = if (reliability >= 80) CanonGreen2 else if (reliability >= 50) CanonWarn else CanonRed
        Surface(color = relColor.copy(alpha = 0.12f), shape = RoundedCornerShape(999.dp)) {
            Row(Modifier.padding(horizontal = 9.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CheckCircle, contentDescription = appText("Надёжность", "Ышаныслылыҡ"), tint = relColor, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(4.dp))
                Text("$reliability%", color = relColor, fontSize = 12.sp, fontWeight = FontWeight.Black)
            }
        }
    }
}

/** Кольцо «Надёжности» — плавно заполняется (animateFloatAsState). Зелёное/жёлтое/красное по значению. */
@Composable
internal fun ReliabilityRing(percent: Int, modifier: Modifier = Modifier, size: Dp = 96.dp, stroke: Dp = 10.dp) {
    val pct = percent.coerceIn(0, 100)
    val sweep by animateFloatAsState(targetValue = pct / 100f * 360f, animationSpec = tween(900), label = "ring")
    val ringColor = if (pct >= 80) CanonGreen2 else if (pct >= 50) CanonWarn else CanonRed
    val trackColor = CanonBorder
    val textColor = CanonText
    val labelColor = CanonMuted
    val strokePx = with(LocalDensity.current) { stroke.toPx() }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(stroke / 2)) {
            val s = Stroke(width = strokePx, cap = StrokeCap.Round)
            drawArc(color = trackColor, startAngle = -90f, sweepAngle = 360f, useCenter = false, style = s)
            drawArc(color = ringColor, startAngle = -90f, sweepAngle = sweep, useCenter = false, style = s)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$pct%", color = textColor, fontWeight = FontWeight.Black, fontSize = 22.sp)
            Text(appText("надёжность", "ышаныс"), color = labelColor, fontSize = 10.sp)
        }
    }
}

/** Бейдж «Гарантия цены» (§1.1 Рычаг 1). Ненавязчивый чип у цены; тап → спокойное объяснение.
 *  Козырь доверия против «бампинга»: договорённую цену не поднимут. */
@Composable
internal fun PriceGuaranteeBadge(modifier: Modifier = Modifier) {
    var show by remember { mutableStateOf(false) }
    Surface(
        color = CanonMint, shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, CanonHairlineGreen),
        modifier = modifier.bounceClick { show = true },
    ) {
        Row(Modifier.padding(horizontal = 9.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Shield, contentDescription = appText("Гарантия цены", "Хаҡ гарантияһы"), tint = CanonGreen2, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(4.dp))
            Text(appText("Гарантия цены", "Хаҡ гарантияһы"), color = CanonGreen2, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
    if (show) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { show = false },
            containerColor = CanonSurface,
            icon = { Icon(Icons.Default.Shield, contentDescription = null, tint = CanonGreen2) },
            title = { Text(appText("Гарантия цены", "Хаҡ гарантияһы"), color = CanonText, fontWeight = FontWeight.Black) },
            text = {
                Text(
                    appText("Договорённую цену не поднимут. Цена фиксируется при бронировании — доплату на месте требовать нельзя.",
                        "Килешелгән хаҡты күтәрмәйҙәр. Хаҡ брондағанда беркетелә — урында өҫтәмә түләү һорау ярамай."),
                    color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp,
                )
            },
            confirmButton = { TextButton(onClick = { show = false }) { Text(appText("Понятно", "Аңлашыла"), color = CanonGreen2, fontWeight = FontWeight.Bold) } },
        )
    }
}

// ═══════════════════════════ 1. Центр справедливости (хаб) ═══════════════════════════

@Composable
internal fun SafetyCenterScreen(
    onBack: () -> Unit,
    onSelectTab: (HomeTab) -> Unit,
    onFileComplaint: () -> Unit,
    onMyDisputes: () -> Unit,
    onRules: () -> Unit,
    onSos: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var standing by remember { mutableStateOf<StandingDto?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    val loggedIn = remember { ApiClient.isLoggedIn() }
    fun reload() {
        if (!loggedIn) { loading = false; return }
        loading = true; error = false
        scope.launch {
            ApiClient.getMyStanding().onSuccess { standing = it; error = false }.onFailure { error = true }
            loading = false
        }
    }
    LaunchedEffect(Unit) { reload() }

    Scaffold(
        containerColor = CanonBg,
        topBar = { ScreenTopBar(appText("Центр справедливости", "Ғәҙеллек үҙәге"), onBack) },
        bottomBar = { YuldashBottomBar(selectedTab = HomeTab.Profile, onSelect = onSelectTab) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(top = 10.dp, bottom = 20.dp),
        ) {
            item {
                Text(
                    appText("Справедливо к каждому. Мир важнее наказания, а рейтинг можно вернуть.",
                        "Һәр кемгә ғәҙел. Тыныслыҡ язаанан мөһимерәк, ә баһаны кире ҡайтарып була."),
                    color = CanonMuted, fontSize = 15.sp, lineHeight = 20.sp,
                )
            }
            item {
                AnimatedContent(
                    targetState = loading && standing == null,
                    transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
                    label = "standingCard",
                ) { isLoading ->
                    when {
                        isLoading -> SkeletonCard(lines = 2, modifier = Modifier.appearIn(0))
                        !loggedIn -> StandingSignedOutCard()
                        error && standing == null -> AppErrorState(onRetry = { reload() })
                        else -> StandingHeroCard(standing)
                    }
                }
            }
            val s = standing
            if (s != null && !s.suspendedUntil.isNullOrBlank()) {
                item { SuspendedBanner(until = s.suspendedUntil, reason = s.suspendReason, onDisputes = onMyDisputes, modifier = Modifier.appearIn(1)) }
            }
            item {
                Box(Modifier.appearIn(2)) {
                    AppButton(
                        text = appText("Пожаловаться · открыть спор", "Ялыу · бәхәс асыу"),
                        onClick = onFileComplaint,
                        icon = Icons.Default.Report,
                        style = AppButtonStyle.Primary,
                    )
                }
            }
            item {
                Box(Modifier.appearIn(3)) {
                    SettingsGroup {
                        SettingsNavRow(Icons.Default.ListAlt, appText("Мои споры", "Минең бәхәстәр"),
                            appText("Жалобы и разборы, где ты — сторона", "Һин ҡатнашҡан ялыуҙар һәм ҡараштар"), onClick = onMyDisputes)
                        SettingsNavRow(Icons.Default.Gavel, appText("Правила справедливости", "Ғәҙеллек ҡағиҙәләре"),
                            appText("7 принципов, лестница, как обжаловать", "7 принцип, баҫҡыс, нисек шикәйәт итергә"), onClick = onRules)
                    }
                }
            }
            item {
                Box(Modifier.appearIn(4)) {
                    Surface(color = CanonDangerBg, shape = CanonItemShape, border = BorderStroke(1.dp, CanonDangerBorder), modifier = Modifier.bounceClick(onSos)) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Sos, contentDescription = appText("Экстренный вызов", "Ашығыс саҡырыу"), tint = CanonRed, modifier = Modifier.size(30.dp))
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(appText("Опасность прямо сейчас?", "Хәҙер үк хәүефме?"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 16.sp)
                                Text(appText("SOS: службы 112 и близкие получат сигнал", "SOS: 112 хеҙмәте һәм яҡындар сигнал ала"), color = CanonMuted, fontSize = 13.sp, lineHeight = 17.sp)
                            }
                            Surface(color = CanonRed, shape = RoundedCornerShape(14.dp)) {
                                Text("SOS", color = Color.White, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp))
                            }
                        }
                    }
                }
            }
            item {
                Box(Modifier.appearIn(5)) {
                    InfoCard(
                        appText("Что такое «Надёжность»", "«Ышаныслылыҡ» ул нимә"),
                        appText("Это доля завершённых поездок за последние 30. Новичок стартует со 100% — мы верим в лучшее. Каждая честная поездка возвращает рейтинг.",
                            "Был һуңғы 30 сәфәрҙең тамамланғандары өлөшө. Яңы ҡатнашыусы 100% менән башлай — беҙ яҡшыға ышанабыҙ. Һәр намыҫлы сәфәр баһаны кире ҡайтара."),
                        Icons.Default.CheckCircle,
                    )
                }
            }
        }
    }
}

@Composable
private fun StandingHeroCard(s: StandingDto?) {
    val standing = Standing.fromCode(s?.standing ?: "good")
    val reliability = s?.reliability ?: 100
    AppCard {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            ReliabilityRing(percent = reliability)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(color = standing.bg, shape = RoundedCornerShape(999.dp)) {
                    Row(Modifier.padding(horizontal = 11.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Shield, contentDescription = null, tint = standing.ink, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(standingLabel(standing), color = standing.ink, fontSize = 14.sp, fontWeight = FontWeight.Black)
                    }
                }
                val strikes = s?.strikes ?: 0
                val warnings = s?.warnings ?: 0
                val active = s?.activeIncidents ?: 0
                Text(
                    when {
                        standing == Standing.Good -> appText("Ты добрый сосед. Так держать!", "Һин яҡшы күрше. Шулай тот!")
                        standing == Standing.Warned -> appText("Есть замечание. Ничего страшного — просто будь внимательнее.", "Иҫкәртеү бар. Ҡурҡыныс түгел — иғтибарлыраҡ бул.")
                        standing == Standing.Limited -> appText("Пока действуют мягкие ограничения. Пара хороших поездок всё вернёт.", "Әлегә йомшаҡ сикләүҙәр бар. Бер-ике яҡшы сәфәр бөтәһен кире ҡайтара.")
                        else -> appText("Аккаунт на паузе. Ниже — когда снимется.", "Иҫәп паузала. Түбәндә — ҡасан алына.")
                    },
                    color = CanonMuted, fontSize = 13.sp, lineHeight = 18.sp,
                )
                if (strikes > 0 || warnings > 0 || active > 0) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (warnings > 0) MiniStat(appText("замечаний", "иҫкәртеү"), warnings.toString(), CanonWarn)
                        if (strikes > 0) MiniStat(appText("страйков", "страйк"), strikes.toString(), CanonRed)
                        if (active > 0) MiniStat(appText("активных споров", "әүҙем бәхәс"), active.toString(), CanonGreen2)
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, fontWeight = FontWeight.Black, fontSize = 18.sp)
        Text(label, color = CanonMuted, fontSize = 11.sp)
    }
}

@Composable
private fun StandingSignedOutCard() {
    AppCard {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Shield, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(34.dp))
            Text(appText("Войди, чтобы видеть свою надёжность", "Ышаныслылығыңды күреү өсөн ин"), color = CanonText, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
            Text(appText("Правила справедливости открыты всем — читай ниже.", "Ғәҙеллек ҡағиҙәләре бөтәһенә асыҡ — түбәндә уҡы."), color = CanonMuted, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun SuspendedBanner(until: String, reason: String, onDisputes: () -> Unit, modifier: Modifier = Modifier) {
    Surface(color = CanonDangerBg, shape = CanonItemShape, border = BorderStroke(1.dp, CanonDangerBorder), modifier = modifier) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Schedule, contentDescription = null, tint = CanonRed, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text(appText("Аккаунт на паузе", "Иҫәп паузала"), color = CanonRed, fontWeight = FontWeight.Black, fontSize = 16.sp)
            }
            Text(appText("Снимется: ", "Алына: ") + prettyDate(until), color = CanonText, fontSize = 14.sp)
            if (reason.isNotBlank()) Text(reason, color = CanonMuted, fontSize = 13.sp, lineHeight = 18.sp)
            AppButton(appText("Мои споры и обжалование", "Бәхәстәр һәм шикәйәт"), onDisputes, style = AppButtonStyle.Secondary, icon = Icons.Default.Gavel)
        }
    }
}

// ═══════════════════════════ 2. Пожаловаться / открыть спор ═══════════════════════════

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FileComplaintScreen(
    onBack: () -> Unit,
    onDone: () -> Unit,
    presetRespondentId: Int? = null,
    presetRespondentName: String = "",
    presetBookingId: Int? = null,
    presetType: IncidentType? = null,
    parcelContext: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var users by remember { mutableStateOf<List<com.yuldash.app.data.ReportableUserDto>>(emptyList()) }
    var usersLoading by remember { mutableStateOf(presetRespondentId == null) }
    var usersError by remember { mutableStateOf(false) }
    var targetId by remember { mutableStateOf(presetRespondentId) }
    var targetName by remember { mutableStateOf(presetRespondentName) }
    var type by remember { mutableStateOf(presetType) }
    var showParcel by remember { mutableStateOf(parcelContext || presetType?.parcel == true) }
    var description by remember { mutableStateOf("") }
    var photos by remember { mutableStateOf<List<String>>(emptyList()) }
    var uploading by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }

    val sentMsg = appText("Спор открыт. Мы разберёмся по-справедливости.", "Бәхәс асылды. Ғәҙеллек буйынса ҡарарбыҙ.")
    val failMsg = appText("Не удалось отправить. Проверь сеть.", "Ебәреп булманы. Селтәрҙе тикшер.")
    val loadErr = appText("Не удалось загрузить. Проверь интернет.", "Йөкләп булманы. Интернетты тикшер.")
    val uploadFailMsg = appText("Фото не загрузилось. Повтори.", "Фото йөкләнмәне. Ҡабатла.")

    fun reloadUsers() {
        if (presetRespondentId != null) return
        usersLoading = true; usersError = false
        scope.launch {
            ApiClient.getReportableUsers().onSuccess { users = it }.onFailure { usersError = true }
            usersLoading = false
        }
    }
    LaunchedEffect(Unit) { reloadUsers() }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { u ->
            uploading = true
            scope.launch {
                val bytes = runCatching { ctx.contentResolver.openInputStream(u)?.use { it.readBytes() } }.getOrNull()
                if (bytes == null) { uploading = false; Toast.makeText(ctx, uploadFailMsg, Toast.LENGTH_SHORT).show(); return@launch }
                ApiClient.uploadChatPhoto(bytes)
                    .onSuccess { url -> photos = photos + url }
                    .onFailure { Toast.makeText(ctx, uploadFailMsg, Toast.LENGTH_SHORT).show() }
                uploading = false
            }
        }
    }

    val rideTypes = listOf(
        IncidentType.PassengerNoShow, IncidentType.DriverNoShow, IncidentType.NonPayment,
        IncidentType.Rude, IncidentType.Unsafe, IncidentType.Harassment,
        IncidentType.RouteDetour, IncidentType.Overcharge, IncidentType.RulesViolation, IncidentType.Other,
    )
    val parcelTypes = listOf(
        IncidentType.ParcelDamage, IncidentType.ParcelLost, IncidentType.ParcelDelay,
        IncidentType.RecipientAbsent, IncidentType.WrongContents,
    )
    val canSubmit = targetId != null && type != null && description.trim().length >= 5 && !submitting

    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Пожаловаться", "Ялыу итеү"), onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 28.dp),
        ) {
            item {
                Text(appText("Расскажи спокойно, что случилось. Вторая сторона сможет объясниться — мы решим честно к обоим.",
                    "Ниҙең булғанын тыныс ҡына һөйлә. Икенсе яҡ аңлатыу бирер — беҙ ике яҡҡа ла ғәҙел ҡарарбыҙ."),
                    color = CanonMuted, fontSize = 14.sp, lineHeight = 19.sp)
            }

            // Шаг 1 — на кого
            item { SectionHeader(appText("1. На кого", "1. Кемгә")) }
            if (targetId != null && presetRespondentId != null) {
                item { SelectedPersonCard(targetName.ifBlank { appText("Участник поездки", "Сәфәр ҡатнашыусыһы") }) }
            } else {
                when {
                    usersLoading -> item { AppLoading() }
                    usersError -> item { AppErrorState(onRetry = { reloadUsers() }) }
                    users.isEmpty() -> item {
                        AppEmptyState(
                            appText("Пока не на кого жаловаться", "Әлегә ялыу итергә кеше юҡ"),
                            appText("Здесь появятся попутчики после поездок.", "Бында сәфәрҙән һуң юлдаштар күренер."),
                            Icons.Default.PersonOff,
                        )
                    }
                    else -> items(users, key = { it.id }) { u ->
                        val selected = targetId == u.id
                        Surface(
                            color = if (selected) CanonMint else CanonSurface,
                            shape = CanonItemShape,
                            border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) CanonGreen2 else CanonBorder),
                            modifier = Modifier.bounceClick { targetId = u.id; targetName = u.name },
                        ) {
                            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                SmallAvatar(url = "", initial = u.name.take(1).uppercase(), size = 40)
                                Spacer(Modifier.width(12.dp))
                                Text(u.name, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                                if (selected) Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CanonGreen2)
                            }
                        }
                    }
                }
            }

            // Шаг 2 — тип
            item { SectionHeader(appText("2. Что произошло", "2. Ни булды")) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ContextToggle(appText("Поездка / такси", "Сәфәр / такси"), !showParcel) { showParcel = false }
                    ContextToggle(appText("Курьер / посылка", "Курьер / йөк"), showParcel) { showParcel = true }
                }
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    (if (showParcel) parcelTypes else rideTypes).forEach { t ->
                        TypeChip(t, selected = type == t) { type = t }
                    }
                }
            }
            type?.let { t ->
                if (t.severe) item {
                    Surface(color = CanonDangerBg, shape = CanonItemShape, border = BorderStroke(1.dp, CanonDangerBorder)) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = CanonRed, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(appText("Это серьёзно. Разберёт человек как можно быстрее. Если опасность сейчас — жми SOS.",
                                "Был етди. Кеше тиҙ арала ҡарар. Хәҙер хәүеф булһа — SOS баҫ."),
                                color = CanonText, fontSize = 13.sp, lineHeight = 18.sp)
                        }
                    }
                }
            }

            // Шаг 3 — описание
            item { SectionHeader(appText("3. Опиши подробнее", "3. Тәфсирләп яҙ")) }
            item {
                OutlinedTextField(
                    value = description,
                    onValueChange = { if (it.length <= 2000) description = it },
                    placeholder = { Text(appText("Когда, где, что именно случилось…", "Ҡасан, ҡайҙа, ниҙер булды…")) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    shape = RoundedCornerShape(16.dp),
                )
            }

            // Шаг 4 — фото
            item { SectionHeader(appText("4. Фото (по желанию)", "4. Фото (теләк буйынса)"), appText("Доказательства помогают решить честно", "Дәлилдәр ғәҙел ҡарарға ярҙам итә")) }
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    photos.forEach { url ->
                        coil.compose.AsyncImage(
                            model = url,
                            contentDescription = appText("Фото-доказательство", "Дәлил фотоһы"),
                            modifier = Modifier.size(84.dp).clip(RoundedCornerShape(14.dp)),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        )
                    }
                    Surface(
                        color = CanonMint, shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.size(84.dp).bounceClick { if (!uploading) photoPicker.launch("image/*") },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (uploading) CircularProgressIndicator(Modifier.size(22.dp), color = CanonGreen2, strokeWidth = 2.dp)
                            else Icon(Icons.Default.PhotoCamera, contentDescription = appText("Добавить фото", "Фото өҫтәү"), tint = CanonGreen2, modifier = Modifier.size(26.dp))
                        }
                    }
                }
            }

            item {
                AppButton(
                    text = appText("Отправить спор", "Бәхәсте ебәреү"),
                    onClick = {
                        val id = targetId
                        val t = type
                        if (id != null && t != null) {
                            submitting = true
                            scope.launch {
                                ApiClient.fileIncident(id, t.code, description.trim(), presetBookingId, photos)
                                    .onSuccess { submitting = false; Toast.makeText(ctx, sentMsg, Toast.LENGTH_LONG).show(); onDone() }
                                    .onFailure { submitting = false; Toast.makeText(ctx, (it as? com.yuldash.app.data.ApiException)?.message ?: failMsg, Toast.LENGTH_LONG).show() }
                            }
                        }
                    },
                    enabled = canSubmit,
                    loading = submitting,
                    icon = Icons.Default.Report,
                )
            }
            item {
                Text(appText("Жалоба-месть, чтобы уронить рейтинг, не пройдёт: нужен паттерн или подтверждение. Спорную оценку админ уберёт из среднего.",
                    "Баһаны төшөрөр өсөн үс ялыуы үтмәй: өлгө йәки раҫлау кәрәк. Бәхәсле баһаны админ уртаса иҫәптән алып ташлай."),
                    color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp)
            }
        }
    }
}

@Composable
private fun SelectedPersonCard(name: String) {
    Surface(color = CanonMint, shape = CanonItemShape, border = BorderStroke(1.dp, CanonGreen2)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            SmallAvatar(url = "", initial = name.take(1).uppercase(), size = 40)
            Spacer(Modifier.width(12.dp))
            Text(name, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CanonGreen2)
        }
    }
}

@Composable
private fun ContextToggle(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) CanonGreen2 else CanonSurface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, if (selected) CanonGreen2 else CanonBorder),
        modifier = Modifier.bounceClick(onClick),
    ) {
        Text(label, color = if (selected) Color.White else CanonText, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp))
    }
}

@Composable
private fun TypeChip(type: IncidentType, selected: Boolean, onClick: () -> Unit) {
    val fg = if (selected) Color.White else CanonText
    Surface(
        color = if (selected) CanonGreen2 else CanonSurface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) CanonGreen2 else CanonBorder),
        modifier = Modifier.bounceClick(onClick),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(incidentTypeIcon(type), contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(incidentTypeLabel(type), color = fg, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

// ═══════════════════════════ 3. Мои споры ═══════════════════════════

@Composable
internal fun MyDisputesScreen(
    onBack: () -> Unit,
    onSelectTab: (HomeTab) -> Unit,
    onOpenDispute: (Int) -> Unit,
    onFileComplaint: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<Incident>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    fun reload() {
        loading = true; error = false
        scope.launch {
            ApiClient.getMyIncidents().onSuccess { items = it }.onFailure { error = true }
            loading = false
        }
    }
    LaunchedEffect(Unit) { reload() }

    Scaffold(
        containerColor = CanonBg,
        topBar = { ScreenTopBar(appText("Мои споры", "Минең бәхәстәр"), onBack) },
        bottomBar = { YuldashBottomBar(selectedTab = HomeTab.Profile, onSelect = onSelectTab) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 10.dp, bottom = 20.dp),
        ) {
            item {
                AppStateContainer(
                    loading = loading, error = error, items = items, onRetry = { reload() },
                    emptyTitle = appText("Споров нет — и хорошо", "Бәхәс юҡ — яҡшы"),
                    emptyText = appText("Здесь появятся жалобы и разборы, где ты — сторона.", "Бында һин ҡатнашҡан ялыуҙар һәм ҡараштар күренер."),
                    emptyIcon = Icons.Default.Handshake,
                    emptyActionLabel = appText("Пожаловаться", "Ялыу итеү"),
                    onEmptyAction = onFileComplaint,
                    loadingContent = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { repeat(3) { SkeletonCard() } } },
                ) { list ->
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        list.forEachIndexed { i, inc ->
                            Box(Modifier.appearIn(i)) { DisputeRowCard(inc) { onOpenDispute(inc.id) } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DisputeRowCard(inc: Incident, onClick: () -> Unit) {
    AppCard(onClick = onClick) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = CanonMint, shape = RoundedCornerShape(12.dp)) {
                    Icon(incidentTypeIcon(inc.type), contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(9.dp).size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(incidentTypeLabel(inc.type), color = CanonText, fontWeight = FontWeight.Black, fontSize = 16.sp)
                    val roleText = if (inc.myRole == "respondent") appText("на тебя · ты можешь объясниться", "һиңә · аңлатыу бирә алаһың") else appText("твоя жалоба", "һинең ялыуың")
                    Text(roleText, color = CanonMuted, fontSize = 12.sp)
                }
                StatusChip(inc.status)
            }
            if (inc.bookingRoute.isNotBlank() || inc.otherName.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (inc.bookingRoute.isNotBlank()) {
                        Icon(Icons.Default.Route, contentDescription = null, tint = CanonMuted, modifier = Modifier.size(14.dp))
                        Text(inc.bookingRoute, color = CanonMuted, fontSize = 13.sp)
                    }
                    if (inc.otherName.isNotBlank()) {
                        Spacer(Modifier.width(4.dp))
                        Text("· ${inc.otherName}", color = CanonMuted, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(status: IncidentStatus) {
    Surface(color = status.bg, shape = RoundedCornerShape(999.dp)) {
        Text(incidentStatusLabel(status), color = status.ink, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
    }
}

// ═══════════════════════════ 4. Деталь спора ═══════════════════════════

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DisputeDetailScreen(incidentId: Int, onBack: () -> Unit, onFindRide: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var inc by remember(incidentId) { mutableStateOf<Incident?>(null) }
    var loading by remember(incidentId) { mutableStateOf(true) }
    var error by remember(incidentId) { mutableStateOf(false) }
    var statement by remember(incidentId) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var showAppeal by remember { mutableStateOf(false) }
    var appealText by remember { mutableStateOf("") }
    var showWithdraw by remember { mutableStateOf(false) }

    val okRespond = appText("Спасибо, твоя версия записана", "Рәхмәт, версияң яҙылды")
    val okAppeal = appText("Обжалование отправлено — рассмотрит человек", "Шикәйәт ебәрелде — кеше ҡарар")
    val okWithdraw = appText("Спасибо, что договорились по-соседски 🤝", "Күршеләрсә килешкәнегеҙ өсөн рәхмәт 🤝")
    val failMsg = appText("Не получилось. Проверь сеть.", "Булманы. Селтәрҙе тикшер.")

    fun reload() {
        loading = true; error = false
        scope.launch {
            ApiClient.getIncident(incidentId).onSuccess { inc = it }.onFailure { error = true }
            loading = false
        }
    }
    LaunchedEffect(incidentId) { reload() }

    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Спор", "Бәхәс"), onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(top = 10.dp, bottom = 28.dp),
        ) {
            val i = inc
            when {
                loading && i == null -> item { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { repeat(2) { SkeletonCard() } } }
                error && i == null -> item { AppErrorState(onRetry = { reload() }) }
                i == null -> item { AppEmptyState(appText("Спор не найден", "Бәхәс табылманы"), appText("Возможно, он уже закрыт.", "Бәлки, ул ябылған."), Icons.Default.Handshake) }
                else -> {
                    // Заголовок
                    item {
                        Box(Modifier.appearIn(0)) {
                            AppCard {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Surface(color = CanonMint, shape = RoundedCornerShape(12.dp)) {
                                            Icon(incidentTypeIcon(i.type), contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(9.dp).size(20.dp))
                                        }
                                        Spacer(Modifier.width(12.dp))
                                        Text(incidentTypeLabel(i.type), color = CanonText, fontWeight = FontWeight.Black, fontSize = 18.sp, modifier = Modifier.weight(1f))
                                        StatusChip(i.status)
                                    }
                                    if (i.bookingRoute.isNotBlank()) Text(i.bookingRoute + (if (i.otherName.isNotBlank()) " · ${i.otherName}" else ""), color = CanonMuted, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                    // §1.1 «Бампинг»: спокойная плашка (презумпция невиновности) + альтернативы пассажиру.
                    if (i.type == IncidentType.DriverNoShow && i.suspectedBump) {
                        item {
                            Box(Modifier.appearIn(1)) {
                                Surface(color = CanonWarnBg, shape = CanonCardShape, border = BorderStroke(1.dp, CanonBorder)) {
                                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.Warning, contentDescription = null, tint = CanonWarn, modifier = Modifier.size(22.dp))
                                            Spacer(Modifier.width(10.dp))
                                            Text(appText("Возможная подмена пассажиров", "Пассажир алмаштырыу мөмкинлеге"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 16.sp)
                                        }
                                        Text(
                                            appText("Система отметила возможную подмену пассажиров — разбирается. Пока это только сигнал, вина никого не установлена.",
                                                "Система пассажир алмаштырыу мөмкинлеген билдәләне — ҡаралып тора. Был әле бары тик сигнал, бер кемдең дә ғәйебе иҫбатланмаған."),
                                            color = CanonMuted, fontSize = 13.sp, lineHeight = 18.sp,
                                        )
                                        if (i.myRole == "reporter") {
                                            AppButton(appText("Найти другую поездку", "Башҡа сәфәр табыу"), onFindRide, style = AppButtonStyle.Primary, icon = Icons.Default.Route)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // Версия заявителя
                    item {
                        Box(Modifier.appearIn(1)) {
                            VersionCard(
                                title = if (i.myRole == "reporter") appText("Твоя жалоба", "Һинең ялыуың") else appText("Что произошло — версия заявителя", "Ни булған — ялыусы версияһы"),
                                text = i.description,
                                photos = i.evidenceUrls,
                                accent = CanonGreen2,
                            )
                        }
                    }
                    // Версия обвинённого (если есть)
                    if (i.respondentStatement.isNotBlank()) {
                        item {
                            Box(Modifier.appearIn(2)) {
                                VersionCard(
                                    title = if (i.myRole == "respondent") appText("Твоё объяснение", "Һинең аңлатмаң") else appText("Объяснение второй стороны", "Икенсе яҡтың аңлатмаһы"),
                                    text = i.respondentStatement,
                                    photos = i.respondentEvidenceUrls,
                                    accent = CanonWarn,
                                )
                            }
                        }
                    }
                    // Блок «Объясниться» (respondent, до решения)
                    if (i.myRole == "respondent" && i.respondentStatement.isBlank() &&
                        (i.status == IncidentStatus.Open || i.status == IncidentStatus.AwaitingResponse)) {
                        item {
                            Box(Modifier.appearIn(3)) {
                                Surface(color = CanonWarnBg, shape = CanonCardShape, border = BorderStroke(1.dp, CanonBorder)) {
                                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text(appText("Объяснись — тебя услышат", "Аңлат — һине ишетәсәктәр"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 16.sp)
                                        Text(appText("Ни один страйк не ставится, пока ты не расскажешь свою версию.", "Һин үҙ версияңды һөйләгәнсе бер страйк ҡуйылмай."), color = CanonMuted, fontSize = 13.sp, lineHeight = 18.sp)
                                        OutlinedTextField(
                                            value = statement, onValueChange = { if (it.length <= 2000) statement = it },
                                            placeholder = { Text(appText("Как всё было на самом деле…", "Ысынында нисек булды…")) },
                                            modifier = Modifier.fillMaxWidth(), minLines = 3, shape = RoundedCornerShape(14.dp),
                                        )
                                        AppButton(
                                            text = appText("Отправить объяснение", "Аңлатманы ебәреү"),
                                            onClick = {
                                                busy = true
                                                scope.launch {
                                                    ApiClient.respondIncident(i.id, statement.trim())
                                                        .onSuccess { busy = false; inc = it; Toast.makeText(ctx, okRespond, Toast.LENGTH_SHORT).show() }
                                                        .onFailure { busy = false; Toast.makeText(ctx, failMsg, Toast.LENGTH_SHORT).show() }
                                                }
                                            },
                                            enabled = statement.trim().length >= 5 && !busy, loading = busy,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    // Решение админа (прозрачно)
                    if (i.resolution.isNotBlank() && i.resolution != "none" || i.resolutionNote.isNotBlank()) {
                        item {
                            Box(Modifier.appearIn(4)) { ResolutionCard(i) }
                        }
                    }
                    // Действия: обжаловать / решить миром
                    item {
                        Box(Modifier.appearIn(5)) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                val canWithdraw = i.myRole == "reporter" && i.status != IncidentStatus.Closed && i.resolution != "mutual_resolved"
                                val alreadyAppealed = i.appealStatus == "requested" || i.status == IncidentStatus.Appealed
                                val canAppeal = !alreadyAppealed && (i.resolution.isNotBlank() && i.resolution != "none" && i.resolution != "mutual_resolved" && i.resolution != "dismissed")
                                if (canWithdraw) {
                                    AppButton(appText("Мы решили миром", "Тыныслыҡ менән хәл иттек"), { showWithdraw = true }, style = AppButtonStyle.Secondary, icon = Icons.Default.Handshake)
                                }
                                if (alreadyAppealed) {
                                    Surface(color = CanonWarnBg, shape = CanonItemShape) {
                                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.Gavel, contentDescription = null, tint = CanonWarn, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(10.dp))
                                            Text(appText("Обжалование отправлено. Цель — ответить за 48 часов.", "Шикәйәт ебәрелде. Маҡсат — 48 сәғәттә яуап."), color = CanonText, fontSize = 13.sp, lineHeight = 18.sp)
                                        }
                                    }
                                } else if (canAppeal) {
                                    AppButton(appText("Обжаловать решение", "Ҡарарҙы шикәйәт итеү"), { showAppeal = true }, style = AppButtonStyle.Secondary, icon = Icons.Default.Gavel)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Диалог обжалования
    if (showAppeal && inc != null) {
        val i = inc!!
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showAppeal = false },
            containerColor = CanonSurface,
            title = { Text(appText("Обжаловать решение", "Ҡарарҙы шикәйәт итеү"), color = CanonText, fontWeight = FontWeight.Black) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(appText("Спокойно объясни, почему решение несправедливо. Его пересмотрит человек.", "Ҡарар ниңә ғәҙелһеҙ икәнен тыныс аңлат. Уны кеше ҡабат ҡарар."), color = CanonMuted, fontSize = 13.sp)
                    OutlinedTextField(appealText, { if (it.length <= 2000) appealText = it }, modifier = Modifier.fillMaxWidth(), minLines = 3, shape = RoundedCornerShape(14.dp))
                }
            },
            confirmButton = {
                TextButton(enabled = appealText.trim().length >= 5, onClick = {
                    val txt = appealText.trim(); showAppeal = false
                    scope.launch {
                        ApiClient.appealIncident(i.id, txt)
                            .onSuccess { inc = it; appealText = ""; Toast.makeText(ctx, okAppeal, Toast.LENGTH_LONG).show() }
                            .onFailure { Toast.makeText(ctx, failMsg, Toast.LENGTH_SHORT).show() }
                    }
                }) { Text(appText("Отправить", "Ебәреү"), color = CanonGreen2, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { showAppeal = false }) { Text(appText("Отмена", "Баш тартыу"), color = CanonMuted) } },
        )
    }

    // Диалог «решили миром»
    if (showWithdraw && inc != null) {
        val i = inc!!
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showWithdraw = false },
            containerColor = CanonSurface,
            title = { Text(appText("Закрыть спор миром?", "Бәхәсте тыныслыҡ менән ябырғамы?"), color = CanonText, fontWeight = FontWeight.Black) },
            text = { Text(appText("Спор закроется без последствий для второй стороны. Мы это ценим.", "Бәхәс икенсе яҡ өсөн эҙемтәһеҙ ябыла. Беҙ быны баһалайбыҙ."), color = CanonMuted) },
            confirmButton = {
                TextButton(onClick = {
                    showWithdraw = false
                    scope.launch {
                        ApiClient.withdrawIncident(i.id)
                            .onSuccess { inc = it; Toast.makeText(ctx, okWithdraw, Toast.LENGTH_LONG).show() }
                            .onFailure { Toast.makeText(ctx, failMsg, Toast.LENGTH_SHORT).show() }
                    }
                }) { Text(appText("Да, решили миром", "Эйе, килештек"), color = CanonGreen2, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { showWithdraw = false }) { Text(appText("Назад", "Кире"), color = CanonMuted) } },
        )
    }
}

@Composable
private fun VersionCard(title: String, text: String, photos: List<String>, accent: Color) {
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
                Spacer(Modifier.width(8.dp))
                Text(title, color = CanonText, fontWeight = FontWeight.Black, fontSize = 15.sp)
            }
            if (text.isNotBlank()) Text(text, color = CanonText, fontSize = 14.sp, lineHeight = 20.sp)
            if (photos.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    photos.forEach { url ->
                        coil.compose.AsyncImage(
                            model = url,
                            contentDescription = appText("Фото-доказательство", "Дәлил фотоһы"),
                            modifier = Modifier.size(96.dp).clip(RoundedCornerShape(14.dp)),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ResolutionCard(i: Incident) {
    Surface(color = CanonMint, shape = CanonCardShape, border = BorderStroke(1.dp, CanonHairlineGreen)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Gavel, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(appText("Решение", "Ҡарар"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 16.sp)
            }
            Text(resolutionLabel(i.resolution), color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            if (i.resolutionNote.isNotBlank()) Text(i.resolutionNote, color = CanonText, fontSize = 14.sp, lineHeight = 20.sp)
            if (i.fault.isNotBlank() && i.fault != "none") Text(appText("Ответственность: ", "Яуаплылыҡ: ") + faultLabel(i.fault), color = CanonMuted, fontSize = 13.sp)
            if (i.compensationKop > 0) Text(appText("Предложена компенсация: ", "Тәҡдим ителгән компенсация: ") + "${i.compensationKop / 100} ₽", color = CanonText, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(appText("Решение видно обеим сторонам. Каждое объяснено.", "Ҡарар ике яҡҡа ла күренә. Һәр береһе аңлатылған."), color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}

// ═══════════════════════════ 5. Правила справедливости ═══════════════════════════

@Composable
internal fun SafetyRulesScreen(onBack: () -> Unit) {
    val principles = listOf(
        Triple(Icons.Default.People, appText("Обе стороны слышимы", "Ике яҡ та ишетелә"),
            appText("На любое обвинение у второй стороны есть право объясниться до наказания. Ни один страйк не ставится молча.",
                "Һәр ғәйепләүгә икенсе яҡтың язаға тиклем аңлатыу хоҡуғы бар. Бер страйк та өнһөҙ ҡуйылмай.")),
        Triple(Icons.Default.Handshake, appText("Мир по умолчанию", "Тыныслыҡ — төп юл"),
            appText("Первый шаг — примирение и предупреждение, а не бан. Кнопка «Мы решили миром» закрывает спор без последствий.",
                "Беренсе аҙым — татыулашыу һәм иҫкәртеү, бан түгел. «Тыныслыҡ менән хәл иттек» төймәһе бәхәсте эҙемтәһеҙ ябыуы.")),
        Triple(Icons.Default.Rule, appText("Соразмерность и лестница", "Үлсәмлелек һәм баҫҡыс"),
            appText("1-е нарушение — предупреждение. 2-е — страйк. 3-е — короткая пауза. Мгновенный бан только за тяжёлое.",
                "1-се боҙоу — иҫкәртеү. 2-се — страйк. 3-сө — ҡыҫҡа пауза. Шунда уҡ бан тик ауыр хәл өсөн.")),
        Triple(Icons.Default.Schedule, appText("Затухание", "Һүнеү"),
            appText("Страйки сгорают через 60 дней хорошего поведения. Никакого «клейма навсегда».",
                "Страйктар 60 көн яҡшы тәртиптән һуң һүнә. «Мәңгегә тамға» юҡ.")),
        Triple(Icons.Default.Shield, appText("Защита оболганного", "Ялған ғәйепләнгәнде яҡлау"),
            appText("Одна жалоба-месть не рушит рейтинг — нужен паттерн. Спорную оценку админ убирает из среднего, есть «щит рейтинга».",
                "Бер үс ялыуы баһаны юймай — өлгө кәрәк. Бәхәсле баһаны админ уртасанан ала, «баһа ҡалҡаны» бар.")),
        Triple(Icons.Default.Verified, appText("Прозрачность", "Асыҡлыҡ"),
            appText("Каждое решение приходит обеим сторонам с человеческим объяснением «почему». Пороги видны в приложении.",
                "Һәр ҡарар ике яҡҡа ла «ниңә» тигән аңлатма менән килә. Сиктәр ҡушымтала күренә.")),
        Triple(Icons.Default.TrendingUp, appText("Возврат рейтинга", "Баһаны кире ҡайтарыу"),
            appText("Рейтинг — окно свежих поездок: старые ошибки тускнеют, пока ты снова возишь и ездишь хорошо. Прогресс, а не приговор.",
                "Баһа — яңы сәфәрҙәр тәҙрәһе: һин яңынан яҡшы йөрөһәң, иҫке хаталар һүрелә. Хөкөм түгел, үҫеш.")),
    )
    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Правила справедливости", "Ғәҙеллек ҡағиҙәләре"), onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 10.dp, bottom = 24.dp),
        ) {
            item {
                Text(appText("Юлдаш — попутки между своими. Здесь доверие — это продукт. Мы честнее и добрее: справедливо к каждому.",
                    "Юлдаш — үҙебеҙҙекеләр араһында юллашыу. Бында ышаныс — ул продукт. Беҙ намыҫлыраҡ: һәр кемгә ғәҙел."),
                    color = CanonMuted, fontSize = 15.sp, lineHeight = 21.sp)
            }
            principles.forEachIndexed { idx, p ->
                item {
                    Box(Modifier.appearIn(idx)) {
                        AppCard {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                                Surface(color = CanonMint, shape = RoundedCornerShape(14.dp)) {
                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(44.dp)) {
                                        Text("${idx + 1}", color = CanonGreen2, fontWeight = FontWeight.Black, fontSize = 18.sp)
                                    }
                                }
                                Spacer(Modifier.width(14.dp))
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(p.second, color = CanonText, fontWeight = FontWeight.Black, fontSize = 16.sp)
                                    Text(p.third, color = CanonMuted, fontSize = 13.sp, lineHeight = 19.sp)
                                }
                            }
                        }
                    }
                }
            }
            item {
                Box(Modifier.appearIn(principles.size)) {
                    InfoCard(
                        appText("Как обжаловать", "Нисек шикәйәт итергә"),
                        appText("Любое наказание можно обжаловать — открой спор в «Мои споры» и нажми «Обжаловать». Решение пересмотрит человек, цель — 48 часов.",
                            "Һәр язаны шикәйәт итеп була — «Минең бәхәстәр»ҙә бәхәсте асып «Шикәйәт итеү» баҫ. Ҡарарҙы кеше ҡабат ҡарар, маҡсат — 48 сәғәт."),
                        Icons.Default.Gavel,
                    )
                }
            }
        }
    }
}

// ═══════════════════════════ 6. Админ: разбор споров ═══════════════════════════

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AdminIncidentsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf("") }   // ""=все · under_review · appealed
    var list by remember { mutableStateOf<List<AdminIncidentDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var expandedId by remember { mutableStateOf<Int?>(null) }
    fun reload() {
        loading = true; error = false
        scope.launch {
            ApiClient.getAdminIncidents(filter).onSuccess { list = it }.onFailure { error = true }
            loading = false
        }
    }
    LaunchedEffect(filter) { reload() }

    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Разбор споров", "Бәхәстәрҙе ҡарау"), onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 10.dp, bottom = 24.dp),
        ) {
            item {
                Text(appText("Обе версии и фото перед глазами. Решай соразмерно и объясняй — текст увидят обе стороны.",
                    "Ике версия һәм фото күҙ алдында. Үлсәмле ҡарар ит һәм аңлат — текстты ике яҡ та күрә."),
                    color = CanonMuted, fontSize = 14.sp, lineHeight = 19.sp)
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ContextToggle(appText("Активные", "Әүҙем"), filter == "") { filter = "" }
                    ContextToggle(appText("На разборе", "Ҡаралышта"), filter == "under_review") { filter = "under_review" }
                    ContextToggle(appText("Обжалованные", "Шикәйәтле"), filter == "appealed") { filter = "appealed" }
                }
            }
            item {
                AppStateContainer(
                    loading = loading, error = error, items = list, onRetry = { reload() },
                    emptyTitle = appText("Споров нет", "Бәхәс юҡ"),
                    emptyText = appText("Очередь разбора пуста. Хорошая работа!", "Ҡарау сираты буш. Афарин!"),
                    emptyIcon = Icons.Default.Handshake,
                    loadingContent = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { repeat(3) { SkeletonCard() } } },
                ) { items ->
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items.forEach { dto ->
                            AdminIncidentCard(
                                dto = dto,
                                expanded = expandedId == dto.id,
                                onToggle = { expandedId = if (expandedId == dto.id) null else dto.id },
                                onResolved = { expandedId = null; reload() },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdminIncidentCard(dto: AdminIncidentDto, expanded: Boolean, onToggle: () -> Unit, onResolved: () -> Unit) {
    val type = IncidentType.fromCode(dto.type)
    AppCard {
        Column(Modifier.padding(16.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth().bounceClick(onToggle), verticalAlignment = Alignment.CenterVertically) {
                Surface(color = if (dto.severe) CanonDangerBg else CanonMint, shape = RoundedCornerShape(12.dp)) {
                    Icon(incidentTypeIcon(type), contentDescription = null, tint = if (dto.severe) CanonRed else CanonGreen2, modifier = Modifier.padding(9.dp).size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(incidentTypeLabel(type), color = CanonText, fontWeight = FontWeight.Black, fontSize = 16.sp)
                        if (dto.severe) {
                            Spacer(Modifier.width(6.dp))
                            Surface(color = CanonDangerBg, shape = RoundedCornerShape(999.dp)) {
                                Text(appText("срочно", "ашығыс"), color = CanonRed, fontSize = 10.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp))
                            }
                        }
                        if (dto.suspectedBump) {
                            Spacer(Modifier.width(6.dp))
                            Surface(color = CanonWarnBg, shape = RoundedCornerShape(999.dp)) {
                                Text(appText("подмена?", "алмаштырыу?"), color = CanonWarn, fontSize = 10.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp))
                            }
                        }
                    }
                    Text("${dto.reporterName} → ${dto.respondentName}", color = CanonMuted, fontSize = 13.sp)
                    if (dto.bookingRoute.isNotBlank()) Text(dto.bookingRoute, color = CanonMuted, fontSize = 12.sp)
                }
                StatusChip(IncidentStatus.fromCode(dto.status))
            }

            AnimatedVisibility(expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Телефоны сторон (только админ)
                    Surface(color = CanonMint, shape = CanonItemShape) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(appText("Заявитель: ", "Ялыусы: ") + "${dto.reporterName} · ${dto.reporterPhone}", color = CanonText, fontSize = 13.sp)
                            Text(appText("Вторая сторона: ", "Икенсе яҡ: ") + "${dto.respondentName} · ${dto.respondentPhone}", color = CanonText, fontSize = 13.sp)
                            if (dto.reporterRole.isNotBlank()) Text(appText("Роль заявителя: ", "Ялыусы роле: ") + dto.reporterRole, color = CanonMuted, fontSize = 12.sp)
                        }
                    }
                    VersionCard(appText("Версия заявителя", "Ялыусы версияһы"), dto.description, dto.evidenceUrls, CanonGreen2)
                    if (dto.respondentStatement.isNotBlank()) {
                        VersionCard(appText("Объяснение второй стороны", "Икенсе яҡ аңлатмаһы"), dto.respondentStatement, dto.respondentEvidenceUrls, CanonWarn)
                    } else {
                        Surface(color = CanonWarnBg, shape = CanonItemShape) {
                            Text(appText("Вторая сторона ещё не объяснилась", "Икенсе яҡ әле аңлатма бирмәгән"), color = CanonWarn, fontSize = 13.sp, modifier = Modifier.padding(12.dp))
                        }
                    }
                    ResolvePanel(dto = dto, onResolved = onResolved)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResolvePanel(dto: AdminIncidentDto, onResolved: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var resolution by remember(dto.id) { mutableStateOf("") }
    var fault by remember(dto.id) { mutableStateOf("") }
    var note by remember(dto.id) { mutableStateOf("") }
    var strike by remember(dto.id) { mutableStateOf(false) }
    var excludeRating by remember(dto.id) { mutableStateOf(false) }
    var shield by remember(dto.id) { mutableStateOf(false) }
    var suspendDays by remember(dto.id) { mutableStateOf(0) }
    var compRub by remember(dto.id) { mutableStateOf("") }
    var busy by remember(dto.id) { mutableStateOf(false) }
    val okMsg = appText("Решение применено, стороны уведомлены", "Ҡарар ҡулланылды, яҡтар хәбәрҙар ителде")
    val failMsg = appText("Не удалось. Проверь сеть.", "Булманы. Селтәрҙе тикшер.")

    val resolutions = listOf(
        "dismissed" to appText("Отклонить", "Кире ҡағыу"),
        "warning" to appText("Предупреждение", "Иҫкәртеү"),
        "strike" to appText("Страйк", "Страйк"),
        "compensation" to appText("Компенсация", "Компенсация"),
        "mutual_resolved" to appText("Мир", "Тыныслыҡ"),
        "suspend" to appText("Пауза", "Пауза"),
        "ban" to appText("Блок", "Блок"),
    )
    val faults = listOf(
        "none" to appText("нет вины", "ғәйеп юҡ"),
        "reporter" to appText("заявитель", "ялыусы"),
        "respondent" to appText("вторая сторона", "икенсе яҡ"),
        "both" to appText("обе", "икеһе"),
        "unclear" to appText("не ясно", "асыҡ түгел"),
    )

    Surface(color = CanonBg, shape = CanonCardShape, border = BorderStroke(1.dp, CanonBorder)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(appText("Решение", "Ҡарар"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 15.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                resolutions.forEach { (code, label) -> PickChip(label, resolution == code) { resolution = code } }
            }

            Text(appText("Ответственность", "Яуаплылыҡ"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                faults.forEach { (code, label) -> PickChip(label, fault == code) { fault = code } }
            }

            OutlinedTextField(
                value = note, onValueChange = { if (it.length <= 2000) note = it },
                placeholder = { Text(appText("Объяснение для обеих сторон (обязательно)", "Ике яҡ өсөн аңлатма (мотлаҡ)")) },
                modifier = Modifier.fillMaxWidth(), minLines = 2, shape = RoundedCornerShape(14.dp),
            )

            CheckRow(appText("Поставить страйк", "Страйк ҡуйыу"), strike) { strike = it }
            CheckRow(appText("Убрать спорную оценку из среднего", "Бәхәсле баһаны уртасанан алыу"), excludeRating) { excludeRating = it }
            CheckRow(appText("Щит рейтинга (защита оболганного)", "Баһа ҡалҡаны (ялғанланғанды яҡлау)"), shield) { shield = it }

            Text(appText("Пауза аккаунта, дней", "Иҫәп паузаһы, көн"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 3, 7, 30).forEach { d -> PickChip(if (d == 0) appText("нет", "юҡ") else "$d", suspendDays == d) { suspendDays = d } }
            }

            OutlinedTextField(
                value = compRub, onValueChange = { compRub = it.filter { c -> c.isDigit() }.take(6) },
                placeholder = { Text(appText("Компенсация, ₽ (0 = нет)", "Компенсация, ₽ (0 = юҡ)")) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(14.dp),
            )

            AppButton(
                text = appText("Применить решение", "Ҡарарҙы ҡулланыу"),
                onClick = {
                    busy = true
                    val kop = (compRub.toIntOrNull() ?: 0) * 100
                    scope.launch {
                        ApiClient.resolveIncident(
                            id = dto.id, resolution = resolution, fault = fault, note = note.trim(),
                            compensationKop = kop, strike = strike, suspendDays = suspendDays,
                            excludeRating = excludeRating, shield = shield,
                        ).onSuccess { busy = false; Toast.makeText(ctx, okMsg, Toast.LENGTH_SHORT).show(); onResolved() }
                            .onFailure { busy = false; Toast.makeText(ctx, failMsg, Toast.LENGTH_SHORT).show() }
                    }
                },
                enabled = resolution.isNotBlank() && note.trim().length >= 3 && !busy,
                loading = busy,
                icon = Icons.Default.Gavel,
            )
        }
    }
}

@Composable
private fun PickChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) CanonGreen2 else CanonSurface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (selected) CanonGreen2 else CanonBorder),
        modifier = Modifier.bounceClick(onClick),
    ) {
        Text(label, color = if (selected) Color.White else CanonText, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().bounceClick { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange, colors = CheckboxDefaults.colors(checkedColor = CanonGreen2))
        Spacer(Modifier.width(6.dp))
        Text(label, color = CanonText, fontSize = 14.sp, modifier = Modifier.weight(1f))
    }
}

// ═══════════════════════════ Хуки активной поездки (переиспользуемые) ═══════════════════════════

/** Таймер ожидания (§1.1): пока идёт отсчёт — напоминание, после — можно заявить неявку. */
@Composable
internal fun WaitTimerNoShowCard(
    role: String,
    waitMinutes: Int,
    onReport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var secondsLeft by remember { mutableStateOf(waitMinutes * 60) }
    LaunchedEffect(Unit) {
        while (secondsLeft > 0) { kotlinx.coroutines.delay(1000); secondsLeft -= 1 }
    }
    val ready = secondsLeft <= 0
    val mm = secondsLeft / 60
    val ss = secondsLeft % 60
    var confirming by remember { mutableStateOf(false) }
    Surface(color = CanonWarnBg, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder), modifier = modifier) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Schedule, contentDescription = null, tint = CanonWarn, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    if (role == "driver") appText("Ждём пассажира", "Пассажирҙы көтәбеҙ") else appText("Водитель на месте", "Водитель урынында"),
                    color = CanonText, fontWeight = FontWeight.Black, fontSize = 16.sp,
                )
            }
            AnimatedContent(targetState = ready, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) }, label = "wait") { isReady ->
                if (!isReady) {
                    Text(
                        (if (role == "driver") appText("Напоминание отправлено. Не наказываем за пару минут — подождём ещё ", "Иҫкәртеү ебәрелде. Бер-ике минут өсөн язаламайбыҙ — көтәбеҙ ")
                        else appText("Свяжись с водителем. Осталось ", "Водитель менән бәйлән. Ҡалды ")) +
                            "%d:%02d".format(mm, ss),
                        color = CanonMuted, fontSize = 13.sp, lineHeight = 18.sp,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            if (role == "driver") appText("Пассажир так и не вышел? Можно отметить неявку.", "Пассажир сыҡманымы? Килмәүен билдәләргә була.")
                            else appText("Водитель так и не приехал? Можно отметить.", "Водитель килмәнеме? Билдәләргә була."),
                            color = CanonText, fontSize = 13.sp, lineHeight = 18.sp,
                        )
                        if (confirming) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                AppButton(
                                    text = if (role == "driver") appText("Да, не вышел", "Эйе, сыҡманы") else appText("Да, не приехал", "Эйе, килмәне"),
                                    onClick = { confirming = false; onReport() },
                                    style = AppButtonStyle.Danger, fillWidth = false, icon = Icons.Default.PersonOff,
                                )
                                AppButton(appText("Ещё подожду", "Көтәм әле"), { confirming = false }, style = AppButtonStyle.Secondary, fillWidth = false)
                            }
                        } else {
                            AppButton(
                                text = if (role == "driver") appText("Пассажир не вышел", "Пассажир сыҡманы") else appText("Водитель не приехал", "Водитель килмәне"),
                                onClick = { confirming = true },
                                style = AppButtonStyle.Secondary, icon = Icons.Default.PersonOff,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Водитель на `done`: отметить получение наличной оплаты (или «не заплатил» → мягкий инцидент). */
@Composable
internal fun PaymentReceivedCard(onMark: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    var done by remember { mutableStateOf<Boolean?>(null) }
    Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder), modifier = modifier) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.MoneyOff, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(appText("Оплата наличными", "Наличныйҙан түләү"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 16.sp)
            }
            AnimatedContent(targetState = done, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) }, label = "pay") { d ->
                when (d) {
                    null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(appText("Всё получил за поездку?", "Сәфәр өсөн бөтәһен алдыңмы?"), color = CanonMuted, fontSize = 13.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            AppButton(appText("Оплату получил", "Түләүҙе алдым"), { done = true; onMark(true) }, style = AppButtonStyle.Primary, fillWidth = false, icon = Icons.Default.CheckCircle)
                            AppButton(appText("Не заплатил", "Түләмәне"), { done = false; onMark(false) }, style = AppButtonStyle.Secondary, fillWidth = false)
                        }
                    }
                    true -> Text(appText("Спасибо! Отметили как оплачено 💚", "Рәхмәт! Түләнде тип билдәләнек 💚"), color = CanonGreen2, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    false -> Text(appText("Записали. Пассажиру уйдёт мягкое напоминание перевести по СБП.", "Яҙҙыҡ. Пассажирға СБП аша күсереү тураһында йомшаҡ иҫкәртеү китә."), color = CanonWarn, fontSize = 13.sp, lineHeight = 18.sp)
                }
            }
        }
    }
}

/** Курьер: фото-доказательство при приёме/вручении (§1.11). Сам грузит фото и шлёт на сервер. */
@Composable
internal fun ParcelPhotoCard(bookingId: Int?, role: String, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var phase by remember { mutableStateOf("pickup") }
    var lastUrl by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var uploading by remember { mutableStateOf(false) }
    val savedMsg = appText("Фото сохранено как доказательство", "Фото дәлил булараҡ һаҡланды")
    val failMsg = appText("Не загрузилось. Повтори.", "Йөкләнмәне. Ҡабатла.")

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val bid = bookingId ?: return@rememberLauncherForActivityResult
        uri?.let { u ->
            uploading = true
            scope.launch {
                val bytes = runCatching { ctx.contentResolver.openInputStream(u)?.use { it.readBytes() } }.getOrNull()
                if (bytes == null) { uploading = false; Toast.makeText(ctx, failMsg, Toast.LENGTH_SHORT).show(); return@launch }
                ApiClient.uploadChatPhoto(bytes)
                    .onSuccess { url ->
                        ApiClient.uploadParcelPhoto(bid, phase, url)
                            .onSuccess { lastUrl = lastUrl + (phase to url); Toast.makeText(ctx, savedMsg, Toast.LENGTH_SHORT).show() }
                            .onFailure { Toast.makeText(ctx, failMsg, Toast.LENGTH_SHORT).show() }
                    }
                    .onFailure { Toast.makeText(ctx, failMsg, Toast.LENGTH_SHORT).show() }
                uploading = false
            }
        }
    }

    Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder), modifier = modifier) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Inventory2, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(appText("Фото посылки", "Йөк фотоһы"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 16.sp)
            }
            Text(appText("Сними «до» при приёме и «после» при вручении — сразу видно, где повредилось. Это защищает и тебя.",
                "Алғанда «тиклем», тапшырғанда «һуң» төшөр — ҡайҙа боҙолғаны күренә. Был һине лә яҡлай."),
                color = CanonMuted, fontSize = 13.sp, lineHeight = 18.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ContextToggle(appText("При приёме", "Алғанда"), phase == "pickup") { phase = "pickup" }
                ContextToggle(appText("При вручении", "Тапшырғанда"), phase == "delivery") { phase = "delivery" }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                lastUrl[phase]?.let { url ->
                    coil.compose.AsyncImage(model = url, contentDescription = appText("Фото посылки", "Йөк фотоһы"), modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                }
                AppButton(
                    text = if (lastUrl[phase] != null) appText("Переснять", "Ҡабат төшөрөү") else appText("Сделать фото", "Фото төшөрөү"),
                    onClick = { if (!uploading && bookingId != null) picker.launch("image/*") },
                    style = AppButtonStyle.Secondary, loading = uploading, fillWidth = false, icon = Icons.Default.PhotoCamera,
                )
            }
        }
    }
}

/** Лист выбора причины отмены (§4). Форс-мажор → без штрафа. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun CancelReasonSheet(onDismiss: () -> Unit, onConfirm: (reason: String, note: String) -> Unit) {
    val sheet = rememberModalBottomSheetState()
    var reason by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val reasons = listOf(
        "plans_changed" to appText("Планы изменились", "Пландар үҙгәрҙе"),
        "found_other" to appText("Нашёл другой вариант", "Башҡа вариант таптым"),
        "price" to appText("Не устроила цена", "Хаҡ тура килмәне"),
        "driver_late" to appText("Водитель опаздывает", "Водитель һуңлай"),
        "passenger_late" to appText("Пассажир опаздывает", "Пассажир һуңлай"),
        "emergency" to appText("Форс-мажор (болезнь, авария)", "Форс-мажор (ауырыу, авария)"),
        "other" to appText("Другое", "Башҡа"),
    )
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = CanonSurface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(appText("Почему отменяешь?", "Ниңә кире алаһың?"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 18.sp)
            Text(appText("Это помогает быть честными к обеим сторонам. Ранняя отмена — без последствий.", "Был ике яҡҡа ла ғәҙел булырға ярҙам итә. Иртә кире алыу — эҙемтәһеҙ."), color = CanonMuted, fontSize = 13.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                reasons.forEach { (code, label) -> PickChip(label, reason == code) { reason = code } }
            }
            AnimatedVisibility(reason == "emergency") {
                Surface(color = CanonMint, shape = CanonItemShape) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Shield, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(appText("Форс-мажор защищён: штрафа не будет. Опиши ниже — при необходимости приложишь фото.", "Форс-мажор яҡланған: штраф булмай. Түбәндә яҙ — кәрәк булһа фото ҡуйырһың."), color = CanonText, fontSize = 12.sp, lineHeight = 17.sp)
                    }
                }
            }
            OutlinedTextField(
                value = note, onValueChange = { if (it.length <= 500) note = it },
                placeholder = { Text(appText("Пара слов (по желанию)", "Бер-ике һүҙ (теләк буйынса)")) },
                modifier = Modifier.fillMaxWidth(), minLines = 2, shape = RoundedCornerShape(14.dp),
            )
            AppButton(
                text = appText("Отменить поездку", "Сәфәрҙе кире алыу"),
                onClick = { onConfirm(reason.ifBlank { "other" }, note.trim()) },
                style = AppButtonStyle.Danger, enabled = reason.isNotBlank(),
            )
        }
    }
}

// ─────────────────────────── Утилиты ───────────────────────────

/** ISO/дата → человекочитаемо (без времени). Мягкий фолбэк на исходную строку. */
internal fun prettyDate(iso: String): String {
    if (iso.isBlank()) return ""
    val datePart = iso.take(10)
    val parts = datePart.split("-")
    if (parts.size != 3) return iso
    return "${parts[2]}.${parts[1]}.${parts[0]}"
}
