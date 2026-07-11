package com.yuldash.app

// ═══════════════════ M2: Промокод (пользователь) ═══════════════════
// Один код на аккаунт. Если уже применён (getMyPromo) → показываем «Твой промокод» + что он дал
// (welcome — приветствие, boost — N бесплатных поднятий), без поля ввода. Иначе — поле ввода
// (заглавные, моноширинно) + «Применить». Успех — красивая галочка + серверное сообщение по языку.
// Промокод по желанию: отказ ничего не блокирует. Всё двуязычно, все состояния, только Canon*.

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Redeem
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.MyPromoDto
import com.yuldash.app.data.PromoApplyResultDto
import kotlinx.coroutines.launch

@Composable
internal fun PromoCodeScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var mine by remember { mutableStateOf<MyPromoDto?>(null) }
    var justApplied by remember { mutableStateOf<PromoApplyResultDto?>(null) } // экран успеха

    val loadErr = appText("Не удалось загрузить. Проверь интернет.", "Йөкләп булманы. Интернетты тикшер.")

    fun reload() {
        loading = true; error = null
        scope.launch {
            ApiClient.getMyPromo()
                .onSuccess { mine = it }
                .onFailure { error = (it as? com.yuldash.app.data.ApiException)?.message ?: loadErr }
            loading = false
        }
    }
    LaunchedEffect(Unit) { reload() }

    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Промокод", "Промокод"), onBack) }) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            when {
                loading && mine == null && justApplied == null -> {
                    item { SkeletonCard(lines = 2) }
                    item { SkeletonCard(lines = 3) }
                }
                error != null && mine == null && justApplied == null -> {
                    item { ListedError(error ?: "") { reload() } }
                }
                justApplied != null -> item { PromoSuccessCard(justApplied!!) }
                mine != null -> item { PromoAppliedCard(mine!!) }
                else -> item {
                    PromoInputCard(
                        onApplied = { res ->
                            justApplied = res
                            // Догружаем «мой промокод» — экран потом покажет постоянное состояние «применён».
                            scope.launch { ApiClient.getMyPromo().onSuccess { mine = it } }
                        },
                    )
                }
            }
            // Честный дисклеймер — виден на всех состояниях, кроме экрана успеха.
            if (justApplied == null && !(loading && mine == null)) {
                item { PromoHonestNote() }
            }
        }
    }
}

// ─────────────────────────── Ввод кода ───────────────────────────

@Composable
private fun PromoInputCard(onApplied: (PromoApplyResultDto) -> Unit) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val defaultErr = appText("Не получилось применить. Повтори.", "Ҡулланып булманы. Ҡабатла.")

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(color = CanonMint, shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Default.Redeem, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(12.dp).size(26.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(appText("Есть промокод?", "Промокодың бармы?"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 20.sp)
                Text(appText("Код друга или акции", "Дуҫ йәки акция коды"), color = CanonMuted, fontSize = 14.sp)
            }
        }

        AppCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { new -> code = new.uppercase().filter { !it.isWhitespace() }; err = null },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(appText("Промокод", "Промокод")) },
                    placeholder = { Text("YULDASH", color = CanonMuted, fontFamily = FontFamily.Monospace) },
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, fontSize = 20.sp, color = CanonText,
                    ),
                    shape = CanonItemShape,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CanonGreen2, cursorColor = CanonGreen2,
                        focusedLabelColor = CanonGreen2,
                    ),
                )
                if (err != null) {
                    Surface(color = CanonDangerBg, shape = RoundedCornerShape(12.dp)) {
                        Text(err ?: "", color = CanonRed, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                    }
                }
                AppButton(
                    text = appText("Применить", "Ҡулланыу"),
                    onClick = {
                        if (busy || code.isBlank()) return@AppButton
                        busy = true; err = null
                        scope.launch {
                            ApiClient.applyPromo(code)
                                .onSuccess { onApplied(it) }
                                .onFailure { err = (it as? com.yuldash.app.data.ApiException)?.message ?: defaultErr }
                            busy = false
                        }
                    },
                    style = AppButtonStyle.Accent,
                    icon = Icons.Default.Redeem,
                    loading = busy,
                    enabled = code.isNotBlank(),
                )
            }
        }
    }
}

// ─────────────────────────── Успех (только что применён) ───────────────────────────

@Composable
private fun PromoSuccessCard(res: PromoApplyResultDto) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val message = appText(res.messageRu, res.messageBa)
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AnimatedVisibility(visible = shown, enter = scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy)) + fadeIn()) {
            Surface(color = CanonMint, shape = CircleShape) {
                Icon(
                    Icons.Default.CheckCircle, contentDescription = appText("Успешно", "Уңышлы"),
                    tint = CanonGreen2,
                    modifier = Modifier.padding(18.dp).size(44.dp),
                )
            }
        }
        Text(
            appText("Промокод применён!", "Промокод ҡулланылды!"),
            color = CanonText, fontWeight = FontWeight.Black, fontSize = 22.sp, textAlign = TextAlign.Center,
        )
        if (message.isNotBlank()) {
            Surface(color = CanonSurface, shape = CanonCardShape, border = BorderStroke(1.dp, CanonGreen2)) {
                Text(
                    message, color = CanonText, fontSize = 16.sp, lineHeight = 22.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                )
            }
        }
        PromoPerkChip(res.kind, res.perkValue)
    }
}

// ─────────────────────────── Уже применён (постоянное состояние) ───────────────────────────

@Composable
private fun PromoAppliedCard(m: MyPromoDto) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(color = CanonMint, shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(12.dp).size(26.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(appText("Промокод активен", "Промокод актив"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 20.sp)
                Text(m.title.ifBlank { appText("Спасибо, что с нами", "Беҙҙең менән булғаныңа рәхмәт") }, color = CanonMuted, fontSize = 14.sp)
            }
        }
        AppCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(appText("Твой промокод", "Һинең промокодың"), color = CanonMuted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Surface(color = CanonMint, shape = CanonItemShape, border = BorderStroke(1.dp, CanonGreen2)) {
                    Text(
                        m.code, color = CanonGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, fontSize = 26.sp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), textAlign = TextAlign.Center,
                    )
                }
                PromoPerkChip(m.kind, m.perkValue)
            }
        }
        Surface(color = CanonMint, shape = CanonItemShape) {
            Text(
                appText("Один промокод на аккаунт — этот уже с тобой. Хорошей дороги!", "Бер аккаунтҡа бер промокод — был һинеке инде. Юлың уңышлы булһын!"),
                color = CanonGreen2, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(14.dp),
            )
        }
    }
}

// ─────────────────────────── Что дал код (welcome / boost) ───────────────────────────

@Composable
private fun PromoPerkChip(kind: String, perkValue: Int) {
    val isBoost = kind.equals("boost", ignoreCase = true)
    val icon = if (isBoost) Icons.Default.RocketLaunch else Icons.Default.CardGiftcard
    val label = if (isBoost) {
        appText("$perkValue бесплатных поднятий", "$perkValue бушлай күтәреү")
    } else {
        appText("Приветственный бонус", "Сәләмләү бүләге")
    }
    Surface(color = CanonGold, shape = CanonItemShape) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(icon, contentDescription = null, tint = CanonGoldInk, modifier = Modifier.size(22.dp))
            Text(label, color = CanonGoldInk, fontWeight = FontWeight.Black, fontSize = 16.sp)
        }
    }
}

// ─────────────────────────── Честный дисклеймер ───────────────────────────

@Composable
private fun PromoHonestNote() {
    Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
        Text(
            appText(
                "Промокод — по желанию. Без него всё работает так же. Никаких обязательных условий.",
                "Промокод — теләккә ҡарап. Уныһыҙ ҙа бөтәһе шулай уҡ эшләй. Мәжбүри шарттар юҡ.",
            ),
            color = CanonMuted, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(14.dp),
        )
    }
}
