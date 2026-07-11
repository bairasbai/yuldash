package com.yuldash.app

// ═══════════════════ M3: Доставка посылок (пользователь) ═══════════════════
// Попутчик везёт бандероль «между своими». Три вкладки:
//  • «Отправить» — форма (города, размер, что за посылка, получатель) + обязательный чекбокс правил →
//                  createParcel → крупный КОД вручения (передать получателю).
//  • «Мои» — мои посылки со статусом, кодом вручения, курьером (если принята), «Отменить».
//  • «Возить» — доступные посылки (курьер берёт) + «Везу» (телефон получателя, «В пути»/«Доставлено» + код).
// Всё двуязычно, все состояния (загрузка/пусто/ошибка), только Canon*, анимации плавные, тон тёплый на «ты».

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.ParcelDto
import kotlinx.coroutines.launch

// ─────────────────────────── Хелперы посылок ───────────────────────────

/** Двуязычная подпись размера посылки. */
@Composable
internal fun parcelSizeLabel(size: String): String = when (size.lowercase()) {
    "small" -> appText("Маленькая", "Бәләкәй")
    "medium" -> appText("Средняя", "Уртаса")
    "large" -> appText("Большая", "Ҙур")
    else -> size
}

/** Короткий намёк на габарит размера. */
@Composable
internal fun parcelSizeHint(size: String): String = when (size.lowercase()) {
    "small" -> appText("документы, ключи, конверт", "документтар, асҡыстар, конверт")
    "medium" -> appText("небольшая коробка, книга", "бәләкәй ҡумта, китап")
    "large" -> appText("сумка, крупная коробка", "һумка, ҙур ҡумта")
    else -> ""
}

private data class ParcelStatusStyle(val bg: Color, val fg: Color, val ru: String, val ba: String)

@Composable
private fun parcelStatusStyle(status: String): ParcelStatusStyle = when (status.lowercase()) {
    "accepted" -> ParcelStatusStyle(CanonMint, CanonGreen2, "У курьера", "Курьерҙа")
    "in_transit" -> ParcelStatusStyle(CanonMint, CanonGreen2, "В пути", "Юлда")
    "delivered" -> ParcelStatusStyle(CanonMint, CanonGreen2, "Доставлена", "Тапшырылды")
    "canceled", "cancelled" -> ParcelStatusStyle(CanonDangerBg, CanonRed, "Отменена", "Кире алынған")
    else -> ParcelStatusStyle(CanonWarnBg, CanonWarn, "Ждёт курьера", "Курьерҙы көтә")
}

@Composable
internal fun ParcelStatusChip(status: String) {
    val s = parcelStatusStyle(status)
    Surface(color = s.bg, shape = RoundedCornerShape(10.dp)) {
        Text(appText(s.ru, s.ba), color = s.fg, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
    }
}

/** Строка «маршрут»: Откуда → Куда. */
@Composable
private fun ParcelRouteRow(from: String, to: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Default.Place, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(16.dp))
        Text(from.ifBlank { "—" }, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text("→", color = CanonMuted, fontSize = 14.sp)
        Text(to.ifBlank { "—" }, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

// ─────────────────────────── Экран ───────────────────────────

@Composable
internal fun ParcelsScreen(onBack: () -> Unit) {
    var tab by remember { mutableStateOf(0) }   // 0 = отправить, 1 = мои, 2 = возить

    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Посылки", "Бандеролдәр"), onBack) }) { padding ->
        Column(Modifier.padding(padding).fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ParcelTab(appText("Отправить", "Ебәреү"), tab == 0, Modifier.weight(1f)) { tab = 0 }
                ParcelTab(appText("Мои", "Минеке"), tab == 1, Modifier.weight(1f)) { tab = 1 }
                ParcelTab(appText("Возить", "Илтеү"), tab == 2, Modifier.weight(1f)) { tab = 2 }
            }
            AnimatedContent(
                targetState = tab,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
                label = "parcel-tab",
            ) { t ->
                when (t) {
                    0 -> SendParcelTab(onSent = { tab = 1 })
                    1 -> MyParcelsTab()
                    else -> CarryTab()
                }
            }
        }
    }
}

@Composable
private fun ParcelTab(label: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val bg by animateColorAsState(if (active) CanonMint else CanonSurface, tween(220), label = "ptab")
    Surface(
        onClick = onClick, color = bg, shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, if (active) CanonGreen2 else CanonBorder),
        modifier = modifier.height(46.dp),
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(label, color = if (active) CanonGreen2 else CanonMuted, fontWeight = FontWeight.Black, fontSize = 14.sp)
        }
    }
}

// ─────────────────────────── Вкладка «Отправить» ───────────────────────────

@Composable
private fun SendParcelTab(onSent: () -> Unit) {
    val scope = rememberCoroutineScope()
    var created by remember { mutableStateOf<ParcelDto?>(null) }

    val c = created
    if (c != null) {
        ParcelCreatedView(c, onDone = { created = null; onSent() })
        return
    }

    var fromCity by remember { mutableStateOf("") }
    var toCity by remember { mutableStateOf("") }
    var size by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var receiverName by remember { mutableStateOf("") }
    var receiverPhone by remember { mutableStateOf("") }
    var rulesAccepted by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val sendErr = appText("Не получилось отправить. Проверь сеть и повтори.", "Ебәреп булманы. Селтәрҙе тикшереп ҡабатла.")

    val canSend = fromCity.isNotBlank() && toCity.isNotBlank() && size.isNotBlank() &&
        receiverName.isNotBlank() && receiverPhone.isNotBlank() && rulesAccepted

    LazyColumn(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 4.dp, bottom = 120.dp),
    ) {
        item {
            Text(
                appText(
                    "Отправь посылку с попутчиком, который и так едет в нужный город. Дёшево и по-соседски.",
                    "Кәрәкле ҡалаға бараған юлдаш менән бандероль ебәр. Арзан һәм күршеләрсә.",
                ),
                color = CanonMuted, fontSize = 14.sp, lineHeight = 19.sp,
            )
        }
        // Маршрут
        item {
            ParcelField(fromCity, { fromCity = it }, appText("Откуда", "Ҡайҙан"), appText("Город отправления", "Ебәреү ҡалаһы"), cap = true)
        }
        item {
            ParcelField(toCity, { toCity = it }, appText("Куда", "Ҡайҙа"), appText("Город получения", "Алыу ҡалаһы"), cap = true)
        }
        // Размер
        item {
            Text(appText("Размер посылки", "Бандероль ҙурлығы"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 15.sp)
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("small", "medium", "large").forEach { s ->
                    ParcelSizeCard(s, selected = size == s) { size = s }
                }
            }
        }
        // Что за посылка
        item {
            ParcelField(description, { description = it }, appText("Что за посылка", "Нимә бул"), appText("Например: документы, книга, гостинец", "Мәҫәлән: документтар, китап, күстәнәс"), minLines = 2)
        }
        // Получатель
        item {
            Text(appText("Получатель", "Алыусы"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 15.sp)
        }
        item {
            ParcelField(receiverName, { receiverName = it }, appText("Имя получателя", "Алыусы исеме"), appText("Кто встретит курьера", "Курьерҙы кем ҡаршылай"), cap = true)
        }
        item {
            ParcelField(receiverPhone, { receiverPhone = it }, appText("Телефон получателя", "Алыусы телефоны"), "+7 …", phone = true)
        }
        // Обязательный чекбокс правил
        item {
            RulesCheckbox(rulesAccepted) { rulesAccepted = !rulesAccepted }
        }
        item {
            Surface(color = CanonMint, shape = CanonItemShape) {
                Text(
                    appText(
                        "Курьер — обычный попутчик, а не служба доставки. Не клади ценное, хрупкое или запрещённое. Ответственность за содержимое — на тебе.",
                        "Курьер — ябай юлдаш, доставка хеҙмәте түгел. Ҡиммәтле, ватыҡ йәки тыйылған әйберҙе һалма. Эстәлеге өсөн яуаплылыҡ — һиндә.",
                    ),
                    color = CanonGreen2, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(14.dp),
                )
            }
        }
        if (error != null) {
            item { Text(error ?: "", color = CanonRed, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
        }
        item {
            AppButton(
                text = appText("Отправить посылку", "Бандероль ебәреү"),
                onClick = {
                    if (sending || !canSend) return@AppButton
                    sending = true; error = null
                    scope.launch {
                        ApiClient.createParcel(
                            fromCity = fromCity.trim(), toCity = toCity.trim(), size = size,
                            description = description.trim(), receiverName = receiverName.trim(),
                            receiverPhone = receiverPhone.trim(), rulesAccepted = rulesAccepted,
                        )
                            .onSuccess { created = it }
                            .onFailure { error = (it as? com.yuldash.app.data.ApiException)?.message ?: sendErr }
                        sending = false
                    }
                },
                style = AppButtonStyle.Accent,
                icon = Icons.Default.Inventory2,
                loading = sending,
                enabled = canSend,
            )
        }
    }
}

@Composable
private fun ParcelField(
    value: String, onValue: (String) -> Unit, label: String, placeholder: String,
    cap: Boolean = false, phone: Boolean = false, minLines: Int = 1,
) {
    OutlinedTextField(
        value = value, onValueChange = onValue,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = { Text(placeholder, color = CanonMuted) },
        shape = RoundedCornerShape(14.dp),
        minLines = minLines,
        singleLine = minLines == 1,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (phone) KeyboardType.Phone else KeyboardType.Text,
            capitalization = if (cap) KeyboardCapitalization.Words else KeyboardCapitalization.Sentences,
        ),
    )
}

@Composable
private fun ParcelSizeCard(size: String, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) CanonMint else CanonSurface, tween(200), label = "psize")
    Surface(
        onClick = onClick, color = bg, shape = CanonItemShape,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) CanonGreen2 else CanonBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = if (selected) CanonGreen2 else CanonMint, shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Default.Inventory2, contentDescription = null, tint = if (selected) Color.White else CanonGreen2, modifier = Modifier.padding(9.dp).size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(parcelSizeLabel(size), color = CanonText, fontWeight = FontWeight.Black, fontSize = 15.sp)
                Text(parcelSizeHint(size), color = CanonMuted, fontSize = 12.sp)
            }
            if (selected) Icon(Icons.Default.CheckCircle, contentDescription = appText("Выбрано", "Һайланды"), tint = CanonGreen2, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun RulesCheckbox(checked: Boolean, onToggle: () -> Unit) {
    Surface(
        onClick = onToggle,
        color = if (checked) CanonMint else CanonSurface,
        shape = CanonItemShape,
        border = BorderStroke(if (checked) 2.dp else 1.dp, if (checked) CanonGreen2 else CanonBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(
                color = if (checked) CanonGreen2 else Color.Transparent,
                shape = RoundedCornerShape(7.dp),
                border = BorderStroke(2.dp, if (checked) CanonGreen2 else CanonMuted),
                modifier = Modifier.size(24.dp),
            ) {
                if (checked) Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color.White, modifier = Modifier.padding(2.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    appText("Подтверждаю правила доставки", "Илтеү ҡағиҙәләрен раҫлайым"),
                    color = CanonText, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                )
                Text(
                    appText(
                        "Не отправляю запрещённое: деньги, документы на предъявителя, лекарства без рецепта, скоропорт, оружие.",
                        "Тыйылғанды ебәрмәйем: аҡса, күрһәтеүсегә документтар, рецептһыҙ дарыу, тиҙ боҙолған аҙыҡ, ҡорал.",
                    ),
                    color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp,
                )
            }
        }
    }
}

// ─────────────────────────── Успех: код вручения ───────────────────────────

@Composable
private fun ParcelCreatedView(p: ParcelDto, onDone: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    LazyColumn(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Surface(color = CanonMint, shape = CircleShape) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(16.dp).size(36.dp))
            }
        }
        item {
            Text(appText("Посылка создана!", "Бандероль булдырылды!"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 22.sp, textAlign = TextAlign.Center)
        }
        item {
            Text(appText("Как только попутчик её возьмёт — ты увидишь курьера и его телефон.", "Юлдаш уны алыу менән — курьерҙы һәм телефонын күрерһең."), color = CanonMuted, fontSize = 15.sp, textAlign = TextAlign.Center)
        }
        // Крупный код вручения
        item {
            Surface(color = CanonSurface, shape = CanonCardShape, border = BorderStroke(2.dp, CanonGreen2)) {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(appText("Код вручения", "Тапшырыу коды"), color = CanonMuted, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(p.confirmCode, color = CanonGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, fontSize = 44.sp, textAlign = TextAlign.Center)
                    if (p.confirmCode.isNotBlank()) {
                        Surface(onClick = { clipboard.setText(AnnotatedString(p.confirmCode)) }, color = CanonMint, shape = RoundedCornerShape(12.dp)) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(appText("Скопировать", "Күсереп алыу"), color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
        }
        item {
            Surface(color = CanonMint, shape = CanonItemShape) {
                Text(
                    appText(
                        "Передай этот код получателю (например, в сообщении). Курьер спросит его при вручении — так посылка попадёт в нужные руки.",
                        "Был кодты алыусыға тапшыр (мәҫәлән, хәбәрҙә). Курьер уны тапшырғанда һорар — шулай бандероль кәрәкле ҡулға етер.",
                    ),
                    color = CanonGreen2, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(14.dp),
                )
            }
        }
        item {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ParcelRouteRow(p.fromCity, p.toCity)
                Text(appText("Получатель: ", "Алыусы: ") + p.receiverName, color = CanonMuted, fontSize = 13.sp)
            }
        }
        item { AppButton(appText("Готово", "Әҙер"), onDone, style = AppButtonStyle.Primary) }
    }
}

// ─────────────────────────── Вкладка «Мои посылки» ───────────────────────────

@Composable
private fun MyParcelsTab() {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var list by remember { mutableStateOf<List<ParcelDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var busyId by remember { mutableStateOf(0) }
    var cancelTarget by remember { mutableStateOf<ParcelDto?>(null) }
    val loadErr = appText("Не удалось загрузить посылки. Проверь интернет.", "Бандеролдәрҙе йөкләп булманы. Интернетты тикшер.")
    val actionErr = appText("Не получилось. Проверь сеть и повтори.", "Булманы. Селтәрҙе тикшереп ҡабатла.")
    val canceledMsg = appText("Посылка отменена", "Бандероль кире алынды")

    fun reload() {
        loading = true; error = null
        scope.launch {
            ApiClient.getMyParcels()
                .onSuccess { list = it.sortedByDescending { p -> p.createdAt } }
                .onFailure { error = (it as? com.yuldash.app.data.ApiException)?.message ?: loadErr }
            loading = false
        }
    }
    LaunchedEffect(Unit) { reload() }

    LazyColumn(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp),
    ) {
        when {
            loading && list.isEmpty() -> {
                item { SkeletonCard(lines = 3) }
                item { SkeletonCard(lines = 3) }
            }
            error != null && list.isEmpty() -> item { ListedError(error ?: "") { reload() } }
            list.isEmpty() -> item {
                AppEmptyState(
                    title = appText("Пока нет посылок", "Әлегә бандеролдәр юҡ"),
                    text = appText("Отправь первую на вкладке «Отправить» — код появится здесь.", "«Ебәреү» бүлегендә беренсеһен ебәр — код бында күренер."),
                    icon = Icons.Default.Inventory2,
                )
            }
            else -> items(list.size, key = { "myp-" + list[it].id }) { i ->
                Box(Modifier.appearIn(i.coerceAtMost(6))) {
                    MyParcelCard(
                        p = list[i],
                        busy = busyId == list[i].id,
                        onCancel = { cancelTarget = list[i] },
                    )
                }
            }
        }
    }

    cancelTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { cancelTarget = null },
            containerColor = CanonSurface,
            title = { Text(appText("Отменить посылку?", "Бандеролде кире алаһыңмы?"), color = CanonText, fontWeight = FontWeight.Black) },
            text = { Text(appText("Посылка исчезнет из ленты курьеров. Отменить можно, пока её не доставили.", "Бандероль курьерҙар лентаһынан юғала. Тапшырылғансы кире алып була."), color = CanonMuted, fontSize = 14.sp, lineHeight = 19.sp) },
            confirmButton = {
                TextButton(onClick = {
                    busyId = target.id
                    scope.launch {
                        ApiClient.cancelParcel(target.id)
                            .onSuccess { Toast.makeText(ctx, canceledMsg, Toast.LENGTH_SHORT).show(); reload() }
                            .onFailure { Toast.makeText(ctx, (it as? com.yuldash.app.data.ApiException)?.message ?: actionErr, Toast.LENGTH_SHORT).show() }
                        busyId = 0
                    }
                    cancelTarget = null
                }) { Text(appText("Отменить посылку", "Кире алыу"), color = CanonRed, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { cancelTarget = null }) { Text(appText("Оставить", "Ҡалдырыу"), color = CanonMuted) } },
        )
    }
}

@Composable
private fun MyParcelCard(p: ParcelDto, busy: Boolean, onCancel: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val active = p.status != "delivered" && p.status != "canceled" && p.status != "cancelled"
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ParcelRouteRow(p.fromCity, p.toCity)
                    Text(parcelSizeLabel(p.size) + (if (p.description.isNotBlank()) "  ·  ${p.description}" else ""), color = CanonMuted, fontSize = 13.sp)
                }
                ParcelStatusChip(p.status)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Person, contentDescription = null, tint = CanonMuted, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(appText("Получатель: ", "Алыусы: ") + p.receiverName, color = CanonText, fontSize = 13.sp)
                Spacer(Modifier.weight(1f))
                if (p.feeKop > 0) Text(kopToRub(p.feeKop), color = CanonGreen2, fontWeight = FontWeight.Black, fontSize = 15.sp)
            }
            // Курьер (когда принята)
            p.courier?.let { cr ->
                val courierFallback = appText("Курьер", "Курьер")
                Surface(color = CanonMint, shape = CanonItemShape) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = CanonSurface, shape = CircleShape) {
                            Icon(Icons.Default.LocalShipping, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(8.dp).size(20.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(cr.name.ifBlank { courierFallback }, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (cr.rating > 0) {
                                    Icon(Icons.Default.Star, contentDescription = null, tint = CanonGold, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(3.dp))
                                    Text(String.format("%.1f", cr.rating), color = CanonMuted, fontSize = 12.sp)
                                    Spacer(Modifier.width(8.dp))
                                }
                                if (cr.phone.isNotBlank()) Text(cr.phone, color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
            // Код вручения — вижу как отправитель, пока не доставлено
            if (p.confirmCode.isNotBlank() && active) {
                Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonGreen2)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(appText("Код вручения (передай получателю)", "Тапшырыу коды (алыусыға бир)"), color = CanonMuted, fontSize = 12.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(p.confirmCode, color = CanonGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, fontSize = 26.sp, modifier = Modifier.weight(1f))
                            Surface(onClick = { clipboard.setText(AnnotatedString(p.confirmCode)) }, color = CanonMint, shape = RoundedCornerShape(12.dp)) {
                                Icon(Icons.Default.ContentCopy, contentDescription = appText("Скопировать код", "Кодты күсереп алыу"), tint = CanonGreen2, modifier = Modifier.padding(9.dp).size(20.dp))
                            }
                        }
                    }
                }
            }
            if (active) {
                AppButton(
                    text = appText("Отменить", "Кире алыу"),
                    onClick = onCancel,
                    style = AppButtonStyle.Secondary,
                    enabled = !busy,
                    loading = busy,
                )
            }
        }
    }
}

// ─────────────────────────── Вкладка «Возить» (курьер) ───────────────────────────

@Composable
private fun CarryTab() {
    var sub by remember { mutableStateOf(0) }   // 0 = доступные, 1 = везу

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ParcelTab(appText("Доступные", "Буш"), sub == 0, Modifier.weight(1f)) { sub = 0 }
            ParcelTab(appText("Везу", "Илтәм"), sub == 1, Modifier.weight(1f)) { sub = 1 }
        }
        Spacer(Modifier.height(12.dp))
        AnimatedContent(
            targetState = sub,
            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(140)) },
            label = "carry-sub",
        ) { s -> if (s == 0) AvailableParcelsTab() else CarryingParcelsTab() }
    }
}

@Composable
private fun AvailableParcelsTab() {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var list by remember { mutableStateOf<List<ParcelDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var cityFilter by remember { mutableStateOf("") }
    var busyId by remember { mutableStateOf(0) }
    val loadErr = appText("Не удалось загрузить. Проверь интернет.", "Йөкләп булманы. Интернетты тикшер.")
    val actionErr = appText("Не получилось взять. Проверь сеть.", "Алып булманы. Селтәрҙе тикшер.")
    val tookMsg = appText("Ты взял посылку. Она во вкладке «Везу».", "Бандеролде алдың. Ул «Илтәм» бүлегендә.")

    fun reload() {
        loading = true; error = null
        scope.launch {
            ApiClient.getAvailableParcels(fromCity = cityFilter.takeIf { it.isNotBlank() })
                .onSuccess { list = it }
                .onFailure { error = (it as? com.yuldash.app.data.ApiException)?.message ?: loadErr }
            loading = false
        }
    }
    LaunchedEffect(cityFilter) { reload() }

    val cities = remember(list, cityFilter) {
        (list.map { it.fromCity }.filter { it.isNotBlank() } + listOfNotNull(cityFilter.takeIf { it.isNotBlank() })).distinct()
    }

    LazyColumn(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        item {
            Text(
                appText(
                    "Едешь в другой город? Захвати посылку по пути — получишь сбор Юлдаша.",
                    "Икенсе ҡалаға бараһыңмы? Юл ыңғайы бандероль ал — Юлдаш сборын алырһың.",
                ),
                color = CanonMuted, fontSize = 14.sp, lineHeight = 19.sp,
            )
        }
        if (cities.isNotEmpty()) {
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ParcelFilterChip(appText("Все города", "Бөтә ҡалалар"), cityFilter == "") { cityFilter = "" }
                    cities.forEach { city -> ParcelFilterChip(city, cityFilter == city) { cityFilter = if (cityFilter == city) "" else city } }
                }
            }
        }
        when {
            loading && list.isEmpty() -> {
                item { SkeletonCard(lines = 3) }
                item { SkeletonCard(lines = 3) }
            }
            error != null && list.isEmpty() -> item { ListedError(error ?: "") { reload() } }
            list.isEmpty() -> item {
                AppEmptyState(
                    title = appText("Свободных посылок нет", "Буш бандеролдәр юҡ"),
                    text = appText("Загляни позже — соседи скоро что-нибудь отправят.", "Һуңыраҡ кер — күршеләр тиҙҙән берәй нәмә ебәрер."),
                    icon = Icons.Default.LocalShipping,
                )
            }
            else -> items(list.size, key = { "avp-" + list[it].id }) { i ->
                Box(Modifier.appearIn(i.coerceAtMost(6))) {
                    AvailableParcelCard(
                        p = list[i],
                        busy = busyId == list[i].id,
                        onTake = {
                            if (busyId != 0) return@AvailableParcelCard
                            busyId = list[i].id
                            scope.launch {
                                ApiClient.acceptParcel(list[i].id)
                                    .onSuccess { Toast.makeText(ctx, tookMsg, Toast.LENGTH_SHORT).show(); reload() }
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

@Composable
private fun ParcelFilterChip(label: String, active: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = if (active) CanonMint else CanonSurface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, if (active) CanonGreen2 else CanonBorder),
        modifier = Modifier.height(44.dp),
    ) {
        Box(Modifier.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            Text(label, color = if (active) CanonGreen2 else CanonMuted, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}

@Composable
private fun AvailableParcelCard(p: ParcelDto, busy: Boolean, onTake: () -> Unit) {
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = CanonMint, shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Default.Inventory2, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(10.dp).size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ParcelRouteRow(p.fromCity, p.toCity)
                    Text(parcelSizeLabel(p.size), color = CanonMuted, fontSize = 13.sp)
                }
                if (p.feeKop > 0) Text(kopToRub(p.feeKop), color = CanonGreen2, fontWeight = FontWeight.Black, fontSize = 18.sp)
            }
            if (p.description.isNotBlank()) {
                Text(p.description, color = CanonText, fontSize = 14.sp, lineHeight = 19.sp)
            }
            Text(appText("Телефон получателя откроется, когда возьмёшь посылку.", "Алыусы телефоны бандеролде алғас асыла."), color = CanonMuted, fontSize = 12.sp)
            AppButton(
                text = appText("Взять посылку", "Бандеролде алыу"),
                onClick = onTake,
                style = AppButtonStyle.Primary,
                icon = Icons.Default.LocalShipping,
                enabled = !busy,
                loading = busy,
            )
        }
    }
}

@Composable
private fun CarryingParcelsTab() {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var list by remember { mutableStateOf<List<ParcelDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var busyId by remember { mutableStateOf(0) }
    var deliverTarget by remember { mutableStateOf<ParcelDto?>(null) }
    val loadErr = appText("Не удалось загрузить. Проверь интернет.", "Йөкләп булманы. Интернетты тикшер.")
    val actionErr = appText("Не получилось. Проверь сеть и повтори.", "Булманы. Селтәрҙе тикшереп ҡабатла.")
    val transitMsg = appText("Статус обновлён: в пути", "Статус яңырҙы: юлда")
    val deliveredMsg = appText("Посылка вручена. Спасибо!", "Бандероль тапшырылды. Рәхмәт!")

    fun reload() {
        loading = true; error = null
        scope.launch {
            ApiClient.getCarryingParcels()
                .onSuccess { list = it.sortedByDescending { p -> p.acceptedAt ?: p.createdAt } }
                .onFailure { error = (it as? com.yuldash.app.data.ApiException)?.message ?: loadErr }
            loading = false
        }
    }
    LaunchedEffect(Unit) { reload() }

    LazyColumn(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        when {
            loading && list.isEmpty() -> {
                item { SkeletonCard(lines = 3) }
                item { SkeletonCard(lines = 3) }
            }
            error != null && list.isEmpty() -> item { ListedError(error ?: "") { reload() } }
            list.isEmpty() -> item {
                AppEmptyState(
                    title = appText("Ты пока ничего не везёшь", "Әлегә бер нәмә лә илтмәйһең"),
                    text = appText("Возьми посылку во вкладке «Доступные» — она появится здесь.", "«Буш» бүлегендә бандероль ал — ул бында күренер."),
                    icon = Icons.Default.LocalShipping,
                )
            }
            else -> items(list.size, key = { "carp-" + list[it].id }) { i ->
                Box(Modifier.appearIn(i.coerceAtMost(6))) {
                    CarryingParcelCard(
                        p = list[i],
                        busy = busyId == list[i].id,
                        onTransit = {
                            if (busyId != 0) return@CarryingParcelCard
                            busyId = list[i].id
                            scope.launch {
                                ApiClient.setParcelStatus(list[i].id, "in_transit")
                                    .onSuccess { Toast.makeText(ctx, transitMsg, Toast.LENGTH_SHORT).show(); reload() }
                                    .onFailure { Toast.makeText(ctx, (it as? com.yuldash.app.data.ApiException)?.message ?: actionErr, Toast.LENGTH_SHORT).show() }
                                busyId = 0
                            }
                        },
                        onDeliver = { deliverTarget = list[i] },
                    )
                }
            }
        }
    }

    deliverTarget?.let { target ->
        var code by remember(target.id) { mutableStateOf("") }
        var codeError by remember(target.id) { mutableStateOf<String?>(null) }
        var submitting by remember(target.id) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!submitting) deliverTarget = null },
            containerColor = CanonSurface,
            title = { Text(appText("Код вручения", "Тапшырыу коды"), color = CanonText, fontWeight = FontWeight.Black) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(appText("Спроси код у получателя и введи его. Так подтвердим, что посылка попала по адресу.", "Кодты алыусынан һора һәм индер. Шулай бандероль дөрөҫ ергә барғанын раҫлайбыҙ."), color = CanonMuted, fontSize = 13.sp, lineHeight = 18.sp)
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it; codeError = null },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(appText("Код от получателя", "Алыусы коды")) },
                        shape = RoundedCornerShape(14.dp),
                        singleLine = true,
                        isError = codeError != null,
                    )
                    if (codeError != null) Text(codeError ?: "", color = CanonRed, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !submitting && code.isNotBlank(),
                    onClick = {
                        submitting = true; codeError = null
                        scope.launch {
                            ApiClient.setParcelStatus(target.id, "delivered", code.trim())
                                .onSuccess {
                                    Toast.makeText(ctx, deliveredMsg, Toast.LENGTH_SHORT).show()
                                    deliverTarget = null; reload()
                                }
                                .onFailure { codeError = (it as? com.yuldash.app.data.ApiException)?.message ?: actionErr }
                            submitting = false
                        }
                    },
                ) { Text(appText("Подтвердить вручение", "Тапшырыуҙы раҫлау"), color = CanonGreen2, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(enabled = !submitting, onClick = { deliverTarget = null }) { Text(appText("Отмена", "Баш тартыу"), color = CanonMuted) } },
        )
    }
}

@Composable
private fun CarryingParcelCard(p: ParcelDto, busy: Boolean, onTransit: () -> Unit, onDeliver: () -> Unit) {
    val delivered = p.status == "delivered"
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ParcelRouteRow(p.fromCity, p.toCity)
                    Text(parcelSizeLabel(p.size) + (if (p.description.isNotBlank()) "  ·  ${p.description}" else ""), color = CanonMuted, fontSize = 13.sp)
                }
                ParcelStatusChip(p.status)
            }
            // Получатель + телефон (виден курьеру)
            Surface(color = CanonMint, shape = CanonItemShape) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Person, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(p.receiverName, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                    if (p.receiverPhone.isNotBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Phone, contentDescription = appText("Телефон получателя", "Алыусы телефоны"), tint = CanonGreen2, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(p.receiverPhone, color = CanonGreen2, fontWeight = FontWeight.Black, fontSize = 15.sp)
                        }
                    }
                }
            }
            if (p.feeKop > 0) {
                Text(appText("Твой сбор: ", "Һинең сбор: ") + kopToRub(p.feeKop), color = CanonMuted, fontSize = 13.sp)
            }
            if (!delivered) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (p.status == "accepted") {
                        AppButton(
                            text = appText("В пути", "Юлда"),
                            onClick = onTransit,
                            style = AppButtonStyle.Secondary,
                            fillWidth = false,
                            modifier = Modifier.weight(1f),
                            enabled = !busy,
                            loading = busy,
                        )
                    }
                    AppButton(
                        text = appText("Доставлено", "Тапшырылды"),
                        onClick = onDeliver,
                        style = AppButtonStyle.Primary,
                        icon = Icons.Default.CheckCircle,
                        fillWidth = false,
                        modifier = Modifier.weight(1f),
                        enabled = !busy,
                    )
                }
            }
        }
    }
}
