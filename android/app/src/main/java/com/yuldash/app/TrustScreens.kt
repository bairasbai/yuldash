package com.yuldash.app

// Экраны доверия «между своими» (Фаза 4): уровень L0–L3, инвайты «позвать своего», согласия (152-ФЗ).
// Бэкенд: GET /me/trust, POST /invites, GET /invites/mine, POST /invites/redeem, GET/POST /me/consents.
// Деликатность: L0 — полноправный участник; уровни МОТИВИРУЮТ, а не унижают.
// Все надписи двуязычны через appText(ru, ba); черновой башкирский → docs/tasks.md.

import android.content.Intent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.Bilingual
import com.yuldash.app.data.ConsentDto
import com.yuldash.app.data.InviteDto
import com.yuldash.app.data.TrustSummaryDto
import kotlinx.coroutines.launch

/** Двуязычная пара с бэкенда → строка на текущем языке интерфейса. */
@Composable
private fun Bilingual.localized(): String = appText(ru, ba)

// Названия уровней L0–L3 (для лесенки прогресса). Совпадают с бэкендом (trust_service._LEVELS).
@Composable
private fun trustLadderTitles(): List<String> = listOf(
    appText("Новичок", "Яңы"),
    appText("Знакомый", "Таныш"),
    appText("Проверен", "Тикшерелгән"),
    appText("Свой", "Үҙебеҙҙеке"),
)

private fun levelIcon(level: Int): ImageVector = when (level) {
    3 -> Icons.Default.Groups
    2 -> Icons.Default.Verified
    1 -> Icons.Default.Person
    else -> Icons.Default.Person
}

// ════════════════════════════════════════════════════════════════════════
//  Экран «Доверие» — уровень L0–L3 + путь к следующему
// ════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrustScreen(
    onBack: () -> Unit,
    onOpenInvites: () -> Unit,
    onOpenConsents: () -> Unit,
    onEditProfile: () -> Unit,   // L0→L1: добавить имя и фото (профиль)
    onVerify: () -> Unit,        // L1→L2: пройти проверку документов
) {
    var reload by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var data by remember { mutableStateOf<TrustSummaryDto?>(null) }

    LaunchedEffect(reload) {
        loading = data == null
        error = false
        ApiClient.getMyTrust()
            .onSuccess { data = it; loading = false }
            .onFailure { loading = false; error = true }
    }

    Scaffold(
        containerColor = CanonBg,
        topBar = { ScreenTopBar(appText("Доверие", "Ышаныс"), onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
        ) {
            item {
                Text(
                    appText(
                        "Юлдаш — поездки между своими. Чем выше доверие, тем шире круг.",
                        "Юлдаш — үҙебеҙҙекеләр араһында сәфәр. Ышаныс күпме юғары — түңәрәк шул тиклем киң.",
                    ),
                    color = CanonMuted, fontSize = 14.sp, lineHeight = 19.sp,
                )
            }
            val d = data
            when {
                loading && d == null -> item { AppLoading(appText("Загружаем твой уровень…", "Кимәлеңде йөкләйбеҙ…")) }
                error && d == null -> item { AppErrorState(onRetry = { reload++ }) }
                d != null -> {
                    item { TrustLevelCard(d) }
                    val nextLevel = d.next?.level
                    if (nextLevel != null) {
                        item {
                            TrustNextCard(
                                d = d,
                                onAction = {
                                    when (nextLevel) {
                                        1 -> onEditProfile()
                                        2 -> onVerify()
                                        else -> onOpenInvites()
                                    }
                                },
                            )
                        }
                    }
                    if (d.canInvite) {
                        item { TrustInviteCallout(onOpenInvites) }
                    }
                    item {
                        SettingsGroup {
                            SettingsNavRow(
                                Icons.Default.PersonAdd,
                                appText("Позвать своего", "Үҙеңдекен саҡыр"),
                                appText("Пригласительные коды в круг доверия", "Ышаныс түңәрәгенә саҡырыу кодтары"),
                                onClick = onOpenInvites,
                            )
                            SettingsNavRow(
                                Icons.Default.Description,
                                appText("Согласия и данные", "Ризалыҡтар һәм мәғлүмәт"),
                                appText("Оферта, политика, геолокация", "Оферта, сәйәсәт, геолокация"),
                                onClick = onOpenConsents,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Карточка текущего уровня: бейдж + лесенка L0→L3 + «что тебе доступно». */
@Composable
private fun TrustLevelCard(d: TrustSummaryDto) {
    val isTop = d.level >= 3
    val badgeBg = if (isTop) CanonGold else CanonMint
    val badgeTint = if (isTop) CanonGoldInk else CanonGreen2
    AppCard {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = badgeBg, shape = CircleShape) {
                    Icon(levelIcon(d.level), contentDescription = null, tint = badgeTint, modifier = Modifier.padding(14.dp).size(28.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(appText("Твой уровень", "Кимәлең"), color = CanonMuted, fontSize = 13.sp)
                    Text(d.title.localized(), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 28.sp)
                }
            }
            TrustLadder(d.level)
            if (d.level == 0) {
                Text(
                    appText(
                        "Ты полноправный участник Юлдаша. Уровень открывает новые возможности — двигайся в своём темпе.",
                        "Һин Юлдаштың тулы хоҡуҡлы ҡатнашыусыһы. Кимәл яңы мөмкинлектәр аса — үҙ тиҙлегеңдә бар.",
                    ),
                    color = CanonMuted, fontSize = 13.sp, lineHeight = 18.sp,
                )
            }
            if (d.benefits.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(appText("Что тебе доступно", "Һиңә нимә асыҡ"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    d.benefits.forEach { BenefitRow(it.localized()) }
                }
            }
        }
    }
}

/** Горизонтальная лесенка уровней доверия: 4 сегмента, заполнены до текущего. */
@Composable
private fun TrustLadder(level: Int) {
    val titles = trustLadderTitles()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        titles.forEachIndexed { i, t ->
            val reached = i <= level
            val fill by animateFloatAsState(
                targetValue = if (reached) 1f else 0f,
                animationSpec = tween(durationMillis = 480, delayMillis = i * 90),
                label = "ladderFill$i",
            )
            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(CanonBorder)) {
                    Box(
                        Modifier.fillMaxWidth(fill).height(6.dp).clip(CircleShape)
                            .background(if (i == level) CanonGold else CanonGreen2),
                    )
                }
                Text(
                    t,
                    color = if (reached) CanonText else CanonMuted,
                    fontSize = 11.sp,
                    lineHeight = 13.sp,
                    fontWeight = if (i == level) FontWeight.Bold else FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun BenefitRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, color = CanonText, fontSize = 14.sp, lineHeight = 19.sp, modifier = Modifier.weight(1f))
    }
}

/** Карточка «следующий уровень»: что даёт + как получить + действие. */
@Composable
private fun TrustNextCard(d: TrustSummaryDto, onAction: () -> Unit) {
    val next = d.next ?: return
    val actionLabel = when (next.level) {
        1 -> appText("Заполнить профиль", "Профильде тултыр")
        2 -> appText("Пройти проверку", "Тикшереүҙе үт")
        else -> appText("Ввести код приглашения", "Саҡырыу кодын индер")
    }
    Surface(color = CanonMint, shape = CanonCardShape) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(appText("Следующий уровень", "Киләһе кимәл"), color = CanonGreen2, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(next.title.localized(), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text(next.how.localized(), color = CanonMuted, fontSize = 14.sp, lineHeight = 19.sp)
            }
            if (next.benefits.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    next.benefits.forEach { BenefitRow(it.localized()) }
                }
            }
            AppButton(actionLabel, onAction, style = AppButtonStyle.Primary)
        }
    }
}

/** Мягкий призыв для L2+: «ты можешь звать своих». */
@Composable
private fun TrustInviteCallout(onOpenInvites: () -> Unit) {
    AppCard {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = CanonMint, shape = CircleShape) {
                    Icon(Icons.Default.PersonAdd, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(11.dp).size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(appText("Ты можешь звать своих", "Һин үҙеңдекеләрҙе саҡыра алаһың"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(appText("Приглашай тех, кому доверяешь", "Ышанған кешеләреңде саҡыр"), color = CanonMuted, fontSize = 13.sp, lineHeight = 17.sp)
                }
            }
            AppButton(appText("Позвать своего", "Үҙеңдекен саҡыр"), onOpenInvites, style = AppButtonStyle.Secondary, icon = Icons.Default.PersonAdd)
        }
    }
}

// ════════════════════════════════════════════════════════════════════════
//  Экран «Позвать своего» — инвайты: ввести код + мои коды
// ════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InvitesScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val lang = LocalAppLanguage.current
    val scope = rememberCoroutineScope()

    var reload by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var trust by remember { mutableStateOf<TrustSummaryDto?>(null) }
    var invites by remember { mutableStateOf<List<InviteDto>>(emptyList()) }

    var codeInput by remember { mutableStateOf("") }
    var redeeming by remember { mutableStateOf(false) }
    var redeemMsg by remember { mutableStateOf<String?>(null) }   // ошибка redeem
    var justJoined by remember { mutableStateOf(false) }          // «Теперь ты свой»
    var creating by remember { mutableStateOf(false) }

    LaunchedEffect(reload) {
        loading = trust == null
        error = false
        ApiClient.getMyTrust()
            .onSuccess { t ->
                trust = t
                loading = false
                if (t.canInvite) {
                    ApiClient.getMyInvites().onSuccess { invites = it }
                }
            }
            .onFailure { loading = false; error = true }
    }

    fun shareCode(code: String) {
        val text = appTextFor(
            lang,
            "Заходи в Юлдаш — поездки «между своими». Мой код приглашения: $code",
            "Юлдашҡа кил — «үҙебеҙҙекеләр араһында» сәфәрҙәр. Минең саҡырыу кодым: $code",
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        runCatching { ctx.startActivity(Intent.createChooser(send, null)) }
    }

    Scaffold(
        containerColor = CanonBg,
        topBar = { ScreenTopBar(appText("Позвать своего", "Үҙеңдекен саҡырыу"), onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
        ) {
            val t = trust
            when {
                loading && t == null -> item { AppLoading() }
                error && t == null -> item { AppErrorState(onRetry = { reload++ }) }
                t != null -> {
                    // Шаг 1. Ввести код — если ещё не «свой»
                    if (!t.isInsider) {
                        item {
                            AppCard {
                                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(appText("У тебя есть код?", "Кодың бармы?"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                    Text(
                                        appText("Введи код от своего — и войдёшь в круг доверия.", "Үҙеңдекенән кодты индер — ышаныс түңәрәгенә инерһең."),
                                        color = CanonMuted, fontSize = 14.sp, lineHeight = 19.sp,
                                    )
                                    OutlinedTextField(
                                        value = codeInput,
                                        onValueChange = { codeInput = it.uppercase().take(12); redeemMsg = null },
                                        singleLine = true,
                                        placeholder = { Text(appText("Например, A1B2C3", "Мәҫәлән, A1B2C3")) },
                                        keyboardOptions = KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Characters),
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    if (redeemMsg != null) {
                                        Text(redeemMsg!!, color = CanonRed, fontSize = 13.sp)
                                    }
                                    AppButton(
                                        appText("Активировать код", "Кодты активлаштыр"),
                                        onClick = {
                                            val code = codeInput.trim()
                                            if (code.isBlank()) return@AppButton
                                            redeeming = true; redeemMsg = null
                                            scope.launch {
                                                ApiClient.redeemInvite(code)
                                                    .onSuccess {
                                                        redeeming = false
                                                        justJoined = true
                                                        codeInput = ""
                                                        reload++   // обновим уровень/коды
                                                    }
                                                    .onFailure { e ->
                                                        redeeming = false
                                                        redeemMsg = (e as? com.yuldash.app.data.ApiException)?.message
                                                            ?: appTextFor(lang, "Не получилось. Проверь код.", "Булманы. Кодты тикшер.")
                                                    }
                                            }
                                        },
                                        loading = redeeming,
                                    )
                                }
                            }
                        }
                    } else {
                        item {
                            AppCard {
                                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Surface(color = CanonGold, shape = CircleShape) {
                                        Icon(Icons.Default.Groups, contentDescription = null, tint = CanonGoldInk, modifier = Modifier.padding(12.dp).size(24.dp))
                                    }
                                    Spacer(Modifier.width(14.dp))
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(appText("Ты в кругу своих", "Һин үҙебеҙҙекеләр араһында"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                                        Text(appText("Видишь поездки «только для своих»", "«Үҙебеҙҙекеләр өсөн генә» сәфәрҙәрҙе күрәһең"), color = CanonMuted, fontSize = 13.sp, lineHeight = 17.sp)
                                    }
                                }
                            }
                        }
                    }

                    // Успех активации — «Теперь ты свой»
                    if (justJoined) {
                        item {
                            Surface(color = CanonMint, shape = CanonCardShape) {
                                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(30.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(appText("Теперь ты свой!", "Хәҙер һин үҙебеҙҙеке!"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                                        Text(appText("Добро пожаловать в круг доверия 💚", "Ышаныс түңәрәгенә рәхим ит 💚"), color = CanonMuted, fontSize = 13.sp)
                                    }
                                }
                            }
                        }
                    }

                    // Шаг 2. Мои коды — если проверенный, уровень L2+
                    if (t.canInvite) {
                        item {
                            SectionHeader(
                                appText("Мои коды", "Минең кодтарым"),
                                appText("Поделись кодом с тем, кому доверяешь", "Ышанған кешеңә кодты бир"),
                            )
                        }
                        if (invites.isEmpty()) {
                            item {
                                Text(
                                    appText("Пока нет кодов. Создай первый — и позови своего.", "Әлегә код юҡ. Беренсеһен булдыр — үҙеңдекен саҡыр."),
                                    color = CanonMuted, fontSize = 14.sp, lineHeight = 19.sp,
                                )
                            }
                        } else {
                            invites.forEach { inv ->
                                item(key = inv.code) { InviteCodeRow(inv, onShare = { shareCode(inv.code) }) }
                            }
                        }
                        item {
                            AppButton(
                                appText("Создать код", "Код булдырыу"),
                                onClick = {
                                    creating = true
                                    scope.launch {
                                        ApiClient.createInvite()
                                            .onSuccess { creating = false; reload++ }
                                            .onFailure { e ->
                                                creating = false
                                                val msg = (e as? com.yuldash.app.data.ApiException)?.message
                                                    ?: appTextFor(lang, "Не получилось создать код", "Код булдырып булманы")
                                                android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
                                            }
                                    }
                                },
                                style = AppButtonStyle.Accent,
                                icon = Icons.Default.PersonAdd,
                                loading = creating,
                            )
                        }
                    } else if (!t.isInsider) {
                        // Ещё не проверен — объясняем деликатно, без «нельзя».
                        item {
                            Surface(color = CanonMint, shape = CanonCardShape) {
                                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Lock, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(24.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        appText(
                                            "Приглашать своих смогут проверенные участники. Пройди проверку — и откроется.",
                                            "Үҙеңдекеләрҙе тикшерелгән ҡатнашыусылар саҡыра ала. Тикшереүҙе үт — асыла.",
                                        ),
                                        color = CanonText, fontSize = 14.sp, lineHeight = 19.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InviteCodeRow(inv: InviteDto, onShare: () -> Unit) {
    val used = inv.usesLeft <= 0
    AppCard {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(inv.code, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text(
                    if (used) appText("Уже использован", "Ҡулланылған")
                    else appText("Ждёт активации", "Активлаштырыуҙы көтә"),
                    color = if (used) CanonMuted else CanonGreen2, fontSize = 13.sp,
                )
            }
            if (!used) {
                Surface(
                    color = CanonMint,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.bounceClick(onShare),
                ) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Share, contentDescription = appText("Поделиться", "Уртаҡлашыу"), tint = CanonGreen2, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(appText("Поделиться", "Уртаҡлашыу"), color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════════
//  Экран «Согласия и данные» (152-ФЗ): оферта / политика / геолокация
// ════════════════════════════════════════════════════════════════════════

private data class ConsentKindMeta(val kind: String, val icon: ImageVector)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConsentsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var reload by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var consents by remember { mutableStateOf<List<ConsentDto>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var savingKind by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reload) {
        loading = !loaded
        error = false
        ApiClient.getConsents()
            .onSuccess { consents = it; loaded = true; loading = false }
            .onFailure { loading = false; error = true }
    }

    val kinds = listOf(
        ConsentKindMeta("offer", Icons.Default.Description),
        ConsentKindMeta("privacy", Icons.Default.PrivacyTip),
        ConsentKindMeta("geo", Icons.Default.Lock),
    )

    Scaffold(
        containerColor = CanonBg,
        topBar = { ScreenTopBar(appText("Согласия и данные", "Ризалыҡтар һәм мәғлүмәт"), onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
        ) {
            item {
                Text(
                    appText(
                        "Мы храним минимум данных и фиксируем твои согласия — это твоё право знать и контролировать.",
                        "Беҙ мәғлүмәтте минимум һаҡлайбыҙ һәм ризалыҡтарыңды теркәйбеҙ — был һинең белеү һәм контролдә тотоу хоҡуғың.",
                    ),
                    color = CanonMuted, fontSize = 14.sp, lineHeight = 19.sp,
                )
            }
            when {
                loading && !loaded -> item { AppLoading() }
                error && !loaded -> item { AppErrorState(onRetry = { reload++ }) }
                else -> {
                    item {
                        SettingsGroup {
                            kinds.forEach { meta ->
                                val granted = consents.firstOrNull { it.kind == meta.kind }
                                ConsentRow(
                                    meta = meta,
                                    grantedAt = granted?.grantedAt,
                                    saving = savingKind == meta.kind,
                                    onGrant = {
                                        savingKind = meta.kind
                                        scope.launch {
                                            ApiClient.setConsent(meta.kind)
                                                .onSuccess { savingKind = null; reload++ }
                                                .onFailure { savingKind = null; reload++ }
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
}

@Composable
private fun ConsentRow(meta: ConsentKindMeta, grantedAt: String?, saving: Boolean, onGrant: () -> Unit) {
    val (title, subtitle) = when (meta.kind) {
        "offer" -> appText("Оферта", "Оферта") to appText("Условия использования сервиса", "Хеҙмәттән файҙаланыу шарттары")
        "privacy" -> appText("Политика конфиденциальности", "Йәшерен сәйәсәт") to appText("Как мы обрабатываем твои данные", "Мәғлүмәтеңде нисек эшкәртәбеҙ")
        else -> appText("Обработка геолокации", "Геолокацияны эшкәртеү") to appText("Только во время активной поездки", "Тик актив сәфәр ваҡытында")
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(color = CanonMint, shape = RoundedCornerShape(14.dp)) {
            Icon(meta.icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(11.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(subtitle, color = CanonMuted, fontSize = 13.sp, lineHeight = 17.sp)
            if (grantedAt != null) {
                Text(
                    appText("Согласие дано ", "Ризалыҡ бирелгән ") + prettyDate(grantedAt),
                    color = CanonGreen2, fontSize = 12.sp,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        if (grantedAt != null) {
            Icon(Icons.Default.CheckCircle, contentDescription = appText("Согласие дано", "Ризалыҡ бирелгән"), tint = CanonGreen2, modifier = Modifier.size(26.dp))
        } else {
            AppButton(
                appText("Отметить", "Билдәләү"),
                onClick = onGrant,
                style = AppButtonStyle.Secondary,
                fillWidth = false,
                height = 48.dp,
                loading = saving,
            )
        }
    }
}

/** ISO-время с сервера (UTC) → «ДД.ММ.ГГГГ» в часах человека.
 *  Это дата согласия на обработку данных — юридический факт, и показывать её на день раньше
 *  из-за непереведённого пояса нельзя (разбор №2, 2026-08-03). Ночные согласия (после 19:00
 *  по Уфе) при нарезке строки уезжали на предыдущие сутки. */
private fun prettyDate(iso: String): String {
    val ms = parseIsoUtcMillis(iso) ?: return iso.substringBefore('T')
    val c = java.util.Calendar.getInstance().apply { timeInMillis = ms }
    return String.format(
        java.util.Locale.US, "%02d.%02d.%04d",
        c.get(java.util.Calendar.DAY_OF_MONTH), c.get(java.util.Calendar.MONTH) + 1,
        c.get(java.util.Calendar.YEAR),
    )
}
