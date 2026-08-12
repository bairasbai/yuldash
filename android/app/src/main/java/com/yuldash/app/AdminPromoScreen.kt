package com.yuldash.app

// ═══════════════════ M2: Админ — промокоды и кампании ═══════════════════
// По паттерну AdminPartnersScreen: умная обёртка держит стейт+сеть, LazyColumn рисует все состояния.
// Карточка кода: code + title + kind/perk + воронка applied→active + лимиты/срок + тумблер вкл/выкл.
// «Создать код» → форма (для блогеров и акций). Понятная ошибка на дубль кода.

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Loyalty
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.yuldash.app.data.AdminPromoDto
import com.yuldash.app.data.ApiClient
import kotlinx.coroutines.launch

@Composable
internal fun AdminPromoScreen(onBack: () -> Unit) {
    var creating by remember { mutableStateOf(false) }

    if (creating) {
        PromoCreateForm(onBack = { creating = false }, onCreated = { creating = false })
    } else {
        AdminPromoList(onBack = onBack, onCreate = { creating = true })
    }
}

// ─────────────────────────── Список кампаний ───────────────────────────

@Composable
private fun AdminPromoList(onBack: () -> Unit, onCreate: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var list by remember { mutableStateOf<List<AdminPromoDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var busyId by remember { mutableStateOf(0) }

    val loadErr = appText("Не удалось загрузить. Проверь интернет.", "Йөкләп булманы. Интернетты тикшер.")
    val actionErr = appText("Не получилось. Проверь сеть и повтори.", "Булманы. Селтәрҙе тикшереп ҡабатла.")

    fun reload() {
        loading = true; error = null
        scope.launch {
            ApiClient.adminListPromo()
                .onSuccess { fresh ->
                    // Включённые — сверху, затем по дате (свежие выше).
                    list = fresh.sortedWith(compareByDescending<AdminPromoDto> { it.activeFlag }.thenByDescending { it.createdAt })
                }
                .onFailure { error = (it as? com.yuldash.app.data.ApiException)?.message ?: loadErr }
            loading = false
        }
    }
    LaunchedEffect(Unit) { reload() }

    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Промокоды и кампании", "Промокодтар һәм акциялар"), onBack) }) { padding ->
        LazyColumn(
            Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 96.dp),
        ) {
            item {
                Text(
                    appText(
                        "Коды для блогеров и акций. Applied — сколько ввели, Active — сколько стали активными.",
                        "Блогерҙар һәм акциялар өсөн кодтар. Applied — нисә кеше индерҙе, Active — нисәһе актив булды.",
                    ),
                    color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp,
                )
            }
            item {
                AppButton(
                    text = appText("Создать код", "Код булдырыу"),
                    onClick = onCreate,
                    style = AppButtonStyle.Accent,
                    icon = Icons.Default.Add,
                )
            }
            when {
                loading && list.isEmpty() -> {
                    item { SkeletonCard(lines = 3) }
                    item { SkeletonCard(lines = 3) }
                }
                error != null && list.isEmpty() -> item { ListedError(error ?: "") { reload() } }
                list.isEmpty() -> item {
                    ListedEmpty(
                        appText("Пока нет кодов", "Әлегә кодтар юҡ"),
                        appText("Создай первый промокод — для блогера или акции.", "Беренсе промокодты булдыр — блогер йәки акция өсөн."),
                    )
                }
                else -> items(list.size, key = { "promo-" + list[it].id }) { i ->
                    Box(Modifier.appearIn(i.coerceAtMost(6))) {
                        AdminPromoCard(
                            p = list[i],
                            busy = busyId == list[i].id,
                            onToggle = { newActive ->
                                if (busyId != 0) return@AdminPromoCard
                                busyId = list[i].id
                                scope.launch {
                                    ApiClient.adminSetPromoStatus(list[i].id, newActive)
                                        .onSuccess { reload() }
                                        .onFailure { Toast.makeText(ctx, (it as? com.yuldash.app.data.ApiException)?.message ?: actionErr, Toast.LENGTH_SHORT).show() }
                                    busyId = 0
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminPromoCard(p: AdminPromoDto, busy: Boolean, onToggle: (Boolean) -> Unit) {
    val isBoost = p.kind.equals("boost", ignoreCase = true)
    Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = CanonMint, shape = RoundedCornerShape(14.dp)) {
                    Icon(
                        if (isBoost) Icons.Default.RocketLaunch else Icons.Default.CardGiftcard,
                        contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(8.dp).size(22.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(p.code, color = CanonGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                    Text(p.title.ifBlank { p.campaign }, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                Switch(
                    checked = p.activeFlag, onCheckedChange = onToggle, enabled = !busy,
                    colors = SwitchDefaults.colors(checkedThumbColor = CanonSurface, checkedTrackColor = CanonGreen2),
                )
            }
            // Тип + бонус
            Surface(color = CanonMint, shape = RoundedCornerShape(8.dp)) {
                Text(
                    if (isBoost) appText("Boost · ${p.perkValue} поднятий", "Boost · ${p.perkValue} күтәреү")
                    else appText("Приветствие", "Сәләмләү"),
                    color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            // Воронка applied → active
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PromoMetric(appText("Ввели", "Индерҙе"), p.applied.toString())
                Text("→", color = CanonMuted, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                PromoMetric(appText("Активны", "Актив"), p.active.toString())
                Spacer(Modifier.weight(1f))
                Text(
                    appText("из ${p.limitTotal}", "${p.limitTotal} тан"),
                    color = CanonMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                )
            }
            // Лимиты + описание + срок
            Text(
                appText(
                    "Лимит: ${p.limitTotal} всего · один раз на человека",
                    "Лимит: ${p.limitTotal} бөтәһе · бер кешегә бер тапҡыр",
                ),
                color = CanonMuted, fontSize = 14.sp,
            )
            if (p.ownerId != null) {
                Text(appText("Код блогера (владелец #${p.ownerId})", "Блогер коды (эйәһе #${p.ownerId})"), color = CanonMuted, fontSize = 12.sp)
            }
            val until = shortDate(p.validUntil)
            if (until != null) {
                Text(appText("Действует до $until", "$until тиклем ғәмәлдә"), color = CanonMuted, fontSize = 12.sp)
            }
            if (!p.activeFlag) {
                Surface(color = CanonWarnBg, shape = RoundedCornerShape(8.dp)) {
                    Text(appText("Выключен", "Һүндерелгән"), color = CanonWarn, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun PromoMetric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 19.sp)
        Text(label, color = CanonMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

// ─────────────────────────── Форма создания ───────────────────────────

@Composable
private fun PromoCreateForm(onBack: () -> Unit, onCreated: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var code by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var campaign by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("welcome") }   // welcome | boost
    var perkValue by remember { mutableStateOf("1") }
    var ownerPhone by remember { mutableStateOf("") }
    var limitTotal by remember { mutableStateOf("100") }
    var validUntil by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }

    val defaultErr = appText("Не получилось создать. Повтори.", "Булдырып булманы. Ҡабатла.")
    val createdMsg = appText("Промокод создан", "Промокод булдырылды")
    val fillCodeErr = appText("Заполни код и кампанию.", "Код һәм кампанияны тултыр.")
    val isBoost = kind == "boost"

    fun submit() {
        if (busy) return
        val c = code.trim()
        if (c.isBlank() || campaign.isBlank()) {
            err = fillCodeErr
            return
        }
        busy = true; err = null
        scope.launch {
            ApiClient.adminCreatePromo(
                code = c,
                title = title.trim().ifBlank { c },
                description = "",
                campaign = campaign.trim(),
                kind = kind,
                perkValue = if (isBoost) (perkValue.toIntOrNull() ?: 0) else 0,
                limitTotal = limitTotal.toIntOrNull() ?: 0,
                limitPerUser = 1,        // на человека код всегда один — см. подпись в форме
                ownerPhone = ownerPhone.trim().ifBlank { null },
                validUntil = validUntil.trim().ifBlank { null },
            )
                .onSuccess { Toast.makeText(ctx, createdMsg, Toast.LENGTH_SHORT).show(); onCreated() }
                .onFailure { err = (it as? com.yuldash.app.data.ApiException)?.message ?: defaultErr }
            busy = false
        }
    }

    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Новый промокод", "Яңы промокод"), onBack) }) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 96.dp),
        ) {
            item {
                PromoField(
                    value = code, onChange = { code = it.uppercase().filter { c -> !c.isWhitespace() } },
                    label = appText("Код", "Код"), placeholder = "BLOGER10", mono = true, caps = true,
                )
            }
            item { PromoField(value = title, onChange = { title = it }, label = appText("Название", "Исеме"), placeholder = appText("Осенняя акция", "Көҙгө акция")) }
            item { PromoField(value = campaign, onChange = { campaign = it }, label = appText("Кампания", "Кампания"), placeholder = "autumn_2026") }
            // Тип
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(appText("Что даёт код", "Код нимә бирә"), color = CanonMuted, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PromoKindChip(appText("Приветствие", "Сәләмләү"), Icons.Default.CardGiftcard, kind == "welcome", Modifier.weight(1f)) { kind = "welcome" }
                        PromoKindChip(appText("Поднятия", "Күтәреү"), Icons.Default.RocketLaunch, kind == "boost", Modifier.weight(1f)) { kind = "boost" }
                    }
                }
            }
            if (isBoost) {
                item {
                    PromoField(
                        value = perkValue, onChange = { perkValue = it.filter { c -> c.isDigit() } },
                        label = appText("Сколько бесплатных поднятий", "Нисә бушлай күтәреү"), placeholder = "3", number = true,
                    )
                }
            }
            item {
                PromoField(
                    value = ownerPhone, onChange = { ownerPhone = it },
                    label = appText("Телефон блогера (необязательно)", "Блогер телефоны (мәжбүри түгел)"), placeholder = "+7 917 000 00 00", number = true,
                )
            }
            item {
                // Поле «на человека» убрано намеренно (аудит 2026-08-12, волна 27): сервер
                // выдаёт код ОДИН РАЗ НА ЖИЗНЬ аккаунта и другого числа выполнить не может.
                // Редактируемое поле здесь врало — админ ставил «5», а работала всё равно одна
                // активация. Вместо настройки — честная подпись.
                PromoField(value = limitTotal, onChange = { limitTotal = it.filter { c -> c.isDigit() } }, label = appText("Лимит всего", "Бөтә лимит"), placeholder = "100", number = true)
            }
            item {
                PromoField(
                    value = validUntil, onChange = { validUntil = it },
                    label = appText("Действует до (гггг-мм-дд, необязательно)", "Тиклем ғәмәлдә (гггг-мм-дд, мәжбүри түгел)"), placeholder = "2026-12-31",
                )
            }
            if (err != null) {
                item {
                    Surface(color = CanonDangerBg, shape = RoundedCornerShape(14.dp)) {
                        Text(err ?: "", color = CanonRed, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                    }
                }
            }
            item {
                AppButton(
                    text = appText("Создать код", "Код булдырыу"),
                    onClick = { submit() },
                    style = AppButtonStyle.Accent,
                    icon = Icons.Default.Loyalty,
                    loading = busy,
                    enabled = code.isNotBlank() && campaign.isNotBlank(),
                )
            }
        }
    }
}

@Composable
private fun PromoField(
    value: String, onChange: (String) -> Unit, label: String,
    placeholder: String = "", mono: Boolean = false, caps: Boolean = false, number: Boolean = false,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(label) },
        placeholder = { if (placeholder.isNotBlank()) Text(placeholder, color = CanonMuted) },
        textStyle = if (mono) androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 19.sp, color = CanonText)
        else androidx.compose.ui.text.TextStyle(fontSize = 16.sp, color = CanonText),
        shape = CanonItemShape,
        keyboardOptions = KeyboardOptions(
            capitalization = if (caps) KeyboardCapitalization.Characters else KeyboardCapitalization.None,
            keyboardType = if (number) KeyboardType.Number else KeyboardType.Text,
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = CanonGreen2, cursorColor = CanonGreen2, focusedLabelColor = CanonGreen2,
        ),
    )
}

@Composable
private fun PromoKindChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = if (active) CanonMint else CanonSurface,
        shape = CanonItemShape,
        border = BorderStroke(1.dp, if (active) CanonGreen2 else CanonBorder),
        modifier = modifier.height(52.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = if (active) CanonGreen2 else CanonMuted, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, color = if (active) CanonGreen2 else CanonMuted, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}
