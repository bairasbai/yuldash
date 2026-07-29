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
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeliveryDining
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.mergeDescendants
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
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
import com.yuldash.app.data.CourierEstimateDto
import com.yuldash.app.data.GeocoderClient
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
    // Возврат: получателя нет дома / отказался — курьер везёт посылку обратно отправителю.
    // Без этих двух веток статусы падали в else и врали пользователю «Ждёт курьера».
    "returning" -> ParcelStatusStyle(CanonWarnBg, CanonWarn, "Везут обратно", "Кире алып киләләр")
    "returned" -> ParcelStatusStyle(CanonWarnBg, CanonWarn, "Вернулась", "Кире ҡайтты")
    else -> ParcelStatusStyle(CanonWarnBg, CanonWarn, "Ждёт курьера", "Курьерҙы көтә")
}

@Composable
internal fun ParcelStatusChip(status: String) {
    val s = parcelStatusStyle(status)
    Surface(color = s.bg, shape = RoundedCornerShape(10.dp)) {
        Text(appText(s.ru, s.ba), color = s.fg, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
    }
}

@Composable
internal fun ParcelReturnNotice(
    status: String,
    reason: String,
    forCourier: Boolean,
) {
    if (status != "returning" && status != "returned") return
    val title = when {
        status == "returned" -> appText(
            "Возврат завершён — посылка у отправителя",
            "Ҡайтарыу тамам — бандероль ебәреүселә",
        )
        forCourier -> appText(
            "Ты везёшь посылку обратно отправителю",
            "Һин бандеролде ебәреүсегә кире илтәһең",
        )
        else -> appText(
            "Курьер везёт посылку обратно",
            "Курьер бандеролде кире алып килә",
        )
    }
    Surface(color = CanonWarnBg, shape = CanonItemShape, border = BorderStroke(1.dp, CanonWarn)) {
        Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = CanonWarn, fontSize = 13.sp, fontWeight = FontWeight.Black)
            if (reason.isNotBlank()) {
                Text(
                    appText("Причина: ", "Сәбәбе: ") + reason,
                    color = CanonText,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }
        }
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

// ─────────────────── C2: расчёт «купи и привези» + спор (общее для двух экранов) ───────────────────

/** Блок расчёта «купи и привези»: за товар · доставка · получатель платит.
 *  forCourier=true — подсказка курьеру («укажи, сколько потратил»); false — отправителю. */
@Composable
internal fun ParcelSettlementBlock(s: com.yuldash.app.data.ParcelSettlementDto, forCourier: Boolean) {
    val hasGoods = s.goodsActualKop > 0
    Surface(color = CanonWarnBg, shape = CanonItemShape) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.ShoppingBag, contentDescription = null, tint = CanonWarn, modifier = Modifier.size(16.dp))
                Text(appText("Купи и привези", "Ал да килтер"), color = CanonWarn, fontWeight = FontWeight.Black, fontSize = 13.sp)
            }
            if (hasGoods) SettlementAmountRow(appText("За товар", "Тауар өсөн"), kopToRub(s.goodsActualKop))
            SettlementAmountRow(appText("Доставка", "Илтеү"), kopToRub(s.deliveryKop))
            if (hasGoods) {
                Surface(color = CanonSurface, shape = RoundedCornerShape(10.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(appText("Получатель платит", "Алыусы түләй"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Text(kopToRub(s.totalDueKop), color = CanonGreen2, fontWeight = FontWeight.Black, fontSize = 18.sp)
                    }
                }
            } else {
                Text(
                    if (forCourier) appText("Укажи, сколько потратил на товар, — сумма для получателя посчитается сама.", "Тауарға күпме тотонғаныңды күрһәт — алыусыға сумма үҙе иҫәпләнер.")
                    else appText("Курьер купит товар на свои. Стоимость появится здесь после покупки — получатель вернёт её плюс доставку.", "Курьер тауарҙы үҙ аҡсаһына алыр. Хаҡы һатып алғас бында күренер — алыусы уны һәм илтеүҙе кире ҡайтарыр."),
                    color = CanonWarn, fontSize = 12.sp, lineHeight = 17.sp,
                )
            }
            if (s.settled) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(15.dp))
                    Text(appText("Расчёт закрыт", "Иҫәп ябылды"), color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun SettlementAmountRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = CanonWarn, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = CanonWarn, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

/** Мягкая кнопка «Открыть спор» на карточке доставки. */
@Composable
internal fun ParcelDisputeButton(onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(Icons.Default.ReportProblem, contentDescription = null, tint = CanonMuted, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(appText("Открыть спор", "Бәхәс асыу"), color = CanonMuted, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

/** Диалог спора (отправитель/курьер). onOpened вызывается после успешного открытия спора. */
@Composable
internal fun ParcelDisputeDialog(parcel: ParcelDto, onDismiss: () -> Unit, onOpened: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var reason by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val failMsg = appText("Не получилось открыть спор. Проверь сеть.", "Бәхәс асып булманы. Селтәрҙе тикшер.")
    val okMsg = appText("Спор открыт. Мы разберёмся по-соседски.", "Бәхәс асылды. Күршеләрсә ҡарарбыҙ.")
    val declared = parcel.declaredValueKop
    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        containerColor = CanonSurface,
        title = { Text(appText("Открыть спор", "Бәхәс асыу"), color = CanonText, fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(appText("Расскажи, что случилось. Мы посмотрим детали заказа и поможем.", "Нимә булғанын яҙ. Беҙ заказ мәғлүмәтен ҡарап ярҙам итербеҙ."), color = CanonMuted, fontSize = 13.sp, lineHeight = 18.sp)
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it; err = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(appText("Что случилось", "Нимә булды")) },
                    shape = RoundedCornerShape(14.dp),
                    minLines = 3,
                    isError = err != null,
                )
                Text(
                    if (declared > 0) appText("Ориентир при споре — объявленная ценность: ", "Бәхәстә ориентир — иғлан ителгән хаҡ: ") + kopToRub(declared) + "."
                    else appText("Ценность не объявлена — решаем по договорённости между своими.", "Хаҡ иғлан ителмәгән — үҙ-ара килешеү буйынса хәл итәбеҙ."),
                    color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp,
                )
                if (err != null) Text(err ?: "", color = CanonRed, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !submitting && reason.isNotBlank(),
                onClick = {
                    submitting = true; err = null
                    scope.launch {
                        ApiClient.disputeParcel(parcel.id, reason.trim())
                            .onSuccess { Toast.makeText(ctx, okMsg, Toast.LENGTH_SHORT).show(); onOpened() }
                            .onFailure { err = (it as? com.yuldash.app.data.ApiException)?.message ?: failMsg }
                        submitting = false
                    }
                },
            ) { Text(appText("Открыть спор", "Бәхәс асыу"), color = CanonRed, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(enabled = !submitting, onClick = onDismiss) { Text(appText("Отмена", "Баш тартыу"), color = CanonMuted) } },
    )
}

// ─────────────────── C3: оценка доставки (общее для двух экранов) ───────────────────

/** Кнопка «Оценить доставку» на карточке доставленного заказа. */
@Composable
internal fun ParcelRateButton(onClick: () -> Unit) {
    AppButton(
        text = appText("Оценить доставку", "Илтеүҙе баһалау"),
        onClick = onClick,
        style = AppButtonStyle.Secondary,
        icon = Icons.Default.Star,
    )
}

/** Строка «Спасибо, оценка учтена» — вместо кнопки после оценки. */
@Composable
internal fun ParcelRatedRow() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Default.Star, contentDescription = null, tint = CanonStar, modifier = Modifier.size(16.dp))
        Text(appText("Спасибо, оценка учтена", "Рәхмәт, баһа иҫәпкә алынды"), color = CanonMuted, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

/** Диалог оценки доставки. Доступен обеим сторонам после вручения (delivered).
 *  raterIsCourier=true — курьер оценивает отправителя; false — отправитель оценивает курьера.
 *  onRated вызывается после успешной оценки (или мягкого «ты уже оценил»). */
@Composable
internal fun ParcelRateDialog(parcel: ParcelDto, raterIsCourier: Boolean, onDismiss: () -> Unit, onRated: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var stars by remember { mutableStateOf(0) }
    var comment by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val failMsg = appText("Не получилось сохранить оценку. Проверь сеть.", "Оценканы һаҡлап булманы. Селтәрҙе тикшер.")
    val okMsg = appText("Спасибо, оценка учтена!", "Рәхмәт, баһа иҫәпкә алынды!")
    val whoQuestion = if (raterIsCourier) appText("Как всё прошло с отправителем?", "Ебәреүсе менән барыһы ла нисек үтте?")
                      else appText("Как справился курьер?", "Курьер эште нисек башҡарҙы?")
    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        containerColor = CanonSurface,
        title = { Text(appText("Оценить доставку", "Илтеүҙе баһалау"), color = CanonText, fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(whoQuestion, color = CanonMuted, fontSize = 13.sp, lineHeight = 18.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (1..5).forEach { i ->
                        val filled = i <= stars
                        val starDesc = appText("Поставить $i из 5", "5-тән $i ҡуйырға")
                        Icon(
                            if (filled) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = starDesc,
                            tint = if (filled) CanonStar else CanonMuted,
                            modifier = Modifier.minimumInteractiveComponentSize().size(42.dp).padding(2.dp).bounceClick { stars = i; err = null },
                        )
                    }
                }
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it.take(300); err = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(appText("Пару слов (необязательно)", "Бер-ике һүҙ (мотлаҡ түгел)")) },
                    shape = RoundedCornerShape(14.dp),
                    minLines = 2,
                )
                if (err != null) Text(err ?: "", color = CanonRed, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !submitting && stars in 1..5,
                onClick = {
                    val note = comment.trim().takeIf { it.isNotBlank() }
                    submitting = true; err = null
                    scope.launch {
                        ApiClient.rateParcel(parcel.id, stars, note)
                            .onSuccess { Toast.makeText(ctx, okMsg, Toast.LENGTH_SHORT).show(); onRated() }
                            .onFailure { err = (it as? com.yuldash.app.data.ApiException)?.message ?: failMsg }
                        submitting = false
                    }
                },
            ) { Text(appText("Отправить оценку", "Оценка ебәреү"), color = CanonGreen2, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(enabled = !submitting, onClick = onDismiss) { Text(appText("Позже", "Һуңыраҡ"), color = CanonMuted) } },
    )
}

// ─────────────────────────── Экран ───────────────────────────

@Composable
internal fun ParcelsScreen(onBack: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(0) }   // 0 = отправить, 1 = мои, 2 = возить

    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Посылки", "Бандеролдәр"), onBack) }) { padding ->
        Column(Modifier.padding(padding).fillMaxWidth()) {
            Surface(
                color = CanonSurface,
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, CanonBorder),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ParcelTab(appText("Отправить", "Ебәреү"), tab == 0, Modifier.weight(1f)) { tab = 0 }
                    ParcelTab(appText("Мои", "Минеке"), tab == 1, Modifier.weight(1f)) { tab = 1 }
                    ParcelTab(appText("Возить", "Илтеү"), tab == 2, Modifier.weight(1f)) { tab = 2 }
                }
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
    MobilitySegmentTab(label = label, active = active, onClick = onClick, modifier = modifier)
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

    var deliveryType by rememberSaveable { mutableStateOf("poputka") }   // poputka | courier | buy_bring
    var fromCity by rememberSaveable { mutableStateOf("") }
    var toCity by rememberSaveable { mutableStateOf("") }
    var size by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var receiverName by rememberSaveable { mutableStateOf("") }
    var receiverPhone by rememberSaveable { mutableStateOf("") }
    var rulesAccepted by rememberSaveable { mutableStateOf(false) }
    var urgency by rememberSaveable { mutableStateOf("bypath") }          // bypath | now
    var shoppingList by rememberSaveable { mutableStateOf("") }
    // Объявленная ценность: поля в форме не было вообще, поэтому спор о повреждении ВСЕГДА падал
    // в ветку «ценность не объявлена» — доказывать было нечем (аудит 2026-07-26).
    var declaredRub by rememberSaveable { mutableStateOf("") }
    // Сколько отправитель платит попутчику. У «по пути» цены не было ВООБЩЕ: курьер видел
    // маршрут и размер, а за сколько везти — нигде (аудит 2026-07-26). Пусто = «по-соседски».
    var priceRub by rememberSaveable { mutableStateOf("") }
    var productRub by rememberSaveable { mutableStateOf("") }
    var estimate by remember { mutableStateOf<CourierEstimateDto?>(null) }
    var fromLat by rememberSaveable { mutableStateOf<Double?>(null) }
    var fromLng by rememberSaveable { mutableStateOf<Double?>(null) }
    var toLat by rememberSaveable { mutableStateOf<Double?>(null) }
    var toLng by rememberSaveable { mutableStateOf<Double?>(null) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val sendErr = appText("Не получилось отправить. Проверь сеть и повтори.", "Ебәреп булманы. Селтәрҙе тикшереп ҡабатла.")
    val geoErr = appText("Не удалось определить города. Проверь названия.", "Ҡалаларҙы билдәләп булманы. Атамаларҙы тикшер.")
    val estErr = appText("Не удалось рассчитать. Проверь сеть.", "Иҫәпләп булманы. Селтәрҙе тикшер.")

    val isCourier = deliveryType != "poputka"
    val productRubInt = productRub.filter(Char::isDigit).toIntOrNull()
    val buyBringOk = deliveryType != "buy_bring" ||
        (shoppingList.isNotBlank() && productRubInt != null && productRubInt in 1..5000)
    val baseFilled = fromCity.isNotBlank() && toCity.isNotBlank() && size.isNotBlank()
    val receiverOk = receiverName.isNotBlank() && receiverPhone.isNotBlank()

    LazyColumn(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 4.dp, bottom = 120.dp),
    ) {
        item {
            MobilityScreenIntro(
                mode = MobilityMode.Courier,
                title = appText("Что доставим?", "Нимә илтәбеҙ?"),
                subtitle = appText(
                    "Выбери способ — маршрут, цена и условия будут видны до заказа.",
                    "Ысулды һайла — маршрут, хаҡ һәм шарттар заказға тиклем күренә.",
                ),
                badge = appText("Доставка", "Доставка"),
            )
        }
        // Тип доставки
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DeliveryTypeCard(
                    "poputka", deliveryType == "poputka",
                    appText("По пути", "Юл ыңғайы"),
                    appText("Попутчик, который и так едет. Дёшево, по-соседски.", "Юл ыңғайы бараған юлдаш. Арзан, күршеләрсә."),
                    Icons.Default.LocalShipping,
                ) { deliveryType = "poputka"; estimate = null }
                DeliveryTypeCard(
                    "courier", deliveryType == "courier",
                    appText("Заказать курьера", "Курьер заказлау"),
                    appText("Проверенный курьер Юлдаша заберёт и доставит. Цена — сразу.", "Юлдаштың тикшерелгән курьеры алып илтер. Хаҡы — шунда уҡ."),
                    Icons.Default.DeliveryDining,
                ) { deliveryType = "courier"; estimate = null }
                DeliveryTypeCard(
                    "buy_bring", deliveryType == "buy_bring",
                    appText("Купи и привези", "Ал да килтер"),
                    appText("Курьер купит товар за тебя и привезёт. До 5000 ₽.", "Курьер һинең өсөн тауар алып килтерер. 5000 ₽-ға тиклем."),
                    Icons.Default.ShoppingBag,
                ) { deliveryType = "buy_bring"; estimate = null }
            }
        }
        // Маршрут
        item {
            ParcelField(fromCity, { fromCity = it; estimate = null }, appText("Откуда", "Ҡайҙан"), appText("Город отправления", "Ебәреү ҡалаһы"), cap = true)
        }
        item {
            ParcelField(toCity, { toCity = it; estimate = null }, appText("Куда", "Ҡайҙа"), appText("Город получения", "Алыу ҡалаһы"), cap = true)
        }
        // Размер
        item {
            Text(appText("Размер посылки", "Бандероль ҙурлығы"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 15.sp)
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("small", "medium", "large").forEach { s ->
                    ParcelSizeCard(s, selected = size == s) { size = s; estimate = null }
                }
            }
        }
        // Срочность (курьер / купи-привези)
        if (isCourier) {
            item {
                Text(appText("Когда доставить", "Ҡасан илтергә"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 15.sp)
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    UrgencyChip(
                        appText("По пути", "Юл ыңғайы"), appText("дешевле", "арзаныраҡ"),
                        urgency == "bypath", Modifier.weight(1f),
                    ) { urgency = "bypath"; estimate = null }
                    UrgencyChip(
                        appText("Срочно", "Ашығыс"), appText("сейчас", "хәҙер"),
                        urgency == "now", Modifier.weight(1f),
                    ) { urgency = "now"; estimate = null }
                }
            }
        }
        // Купи и привези: список покупок + сумма товара
        if (deliveryType == "buy_bring") {
            item {
                ParcelField(shoppingList, { shoppingList = it }, appText("Что купить", "Нимә алырға"), appText("Например: хлеб, молоко, лекарство из аптеки", "Мәҫәлән: икмәк, һөт, дарыуханан дарыу"), minLines = 2)
            }
            item {
                ParcelField(productRub, { productRub = it.filter(Char::isDigit).take(5); estimate = null }, appText("Сумма покупки, ₽", "Һатып алыу суммаһы, ₽"), "0", phone = true)
            }
            item {
                val overLimit = productRubInt != null && productRubInt > 5000
                Text(
                    if (overLimit) appText("Лимит покупки — 5000 ₽. Уменьши сумму.", "Һатып алыу лимиты — 5000 ₽. Сумманы кәметер.")
                    else appText("Курьер купит на эту сумму, а получатель вернёт её при вручении.", "Курьер шул суммаға алыр, алыусы тапшырғанда кире ҡайтарыр."),
                    color = if (overLimit) CanonRed else CanonMuted, fontSize = 12.sp, lineHeight = 17.sp,
                )
            }
        }
        // Что за посылка / комментарий
        item {
            ParcelField(description, { description = it }, appText("Что за посылка", "Нимә бул"), appText("Например: документы, книга, гостинец", "Мәҫәлән: документтар, китап, күстәнәс"), minLines = 2)
        }
        // Сколько заплатишь попутчику. Только для «по пути»: у курьерских типов цену считает
        // сервер (EstimateCard ниже). Раньше поля не было, и человек соглашался везти вслепую.
        if (!isCourier) {
            item {
                ParcelField(
                    priceRub, { priceRub = it.filter(Char::isDigit).take(6) },
                    appText("Сколько заплатишь попутчику, ₽", "Юлдашҡа күпме түләйһең, ₽"),
                    "0", phone = true,
                )
            }
            item {
                Text(
                    if ((priceRub.toIntOrNull() ?: 0) > 0)
                        appText(
                            "Отдашь эти деньги попутчику лично — Юлдаш к ним не прикасается.",
                            "Был аҡсаны юлдашҡа үҙең бирәһең — Юлдаш уға ҡағылмай.",
                        )
                    else appText(
                        "Оставь пусто — значит по-соседски, бесплатно. Так и увидит попутчик.",
                        "Буш ҡалдыр — тимәк күрше хаҡы, бушлай. Юлдаш шулай күрер.",
                    ),
                    color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp,
                )
            }
        }
        // Объявленная ценность. Поля не было вообще — и любой спор о повреждении падал в ветку
        // «ценность не объявлена»: доказывать было нечем, ориентира для компенсации не существовало.
        item {
            ParcelField(
                declaredRub, { declaredRub = it.filter(Char::isDigit).take(6) },
                appText("Ценность посылки, ₽ (необязательно)", "Бандероль хаҡы, ₽ (мотлаҡ түгел)"),
                "0", phone = true,
            )
        }
        item {
            Text(
                appText(
                    "Если что-то случится, это будет ориентиром при разборе. Не страховка — но без цифры спорить не о чем.",
                    "Берәй хәл булһа, был ҡарағанда ориентир булыр. Страховка түгел — әммә һанһыҙ бәхәсләшер нәмә юҡ.",
                ),
                color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp,
            )
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
        // Оценка стоимости (курьер / купи-привези) — показываем ЧЕСТНО, из чего сложилась цена
        estimate?.let { est ->
            if (isCourier) item { EstimateCard(est) }
        }
        // Обязательный чекбокс правил
        item {
            RulesCheckbox(rulesAccepted) { rulesAccepted = !rulesAccepted }
        }
        item {
            Surface(color = CanonMint, shape = CanonItemShape) {
                Text(
                    if (isCourier)
                        appText(
                            "Курьер отвечает за сохранность. Не отправляй запрещённое: деньги, документы на предъявителя, лекарства без рецепта, скоропорт, оружие.",
                            "Курьер һаҡлыҡ өсөн яуаплы. Тыйылғанды ебәрмә: аҡса, күрһәтеүсегә документтар, рецептһыҙ дарыу, тиҙ боҙолған аҙыҡ, ҡорал.",
                        )
                    else appText(
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
            when {
                // Попутка — как в M3: сразу создаём посылку.
                !isCourier -> AppButton(
                    text = appText("Отправить посылку", "Бандероль ебәреү"),
                    onClick = {
                        val canSend = baseFilled && receiverOk && rulesAccepted
                        if (working || !canSend) return@AppButton
                        working = true; error = null
                        scope.launch {
                            ApiClient.createParcel(
                                fromCity = fromCity.trim(), toCity = toCity.trim(), size = size,
                                description = description.trim(), receiverName = receiverName.trim(),
                                receiverPhone = receiverPhone.trim(), rulesAccepted = rulesAccepted,
                                // Цена попутчику и объявленная ценность: раньше «по пути»
                                // не слал ни того, ни другого — курьер вёз вслепую, а спор
                                // всегда падал в ветку «ценность не объявлена».
                                // Потолок тот же, что на сервере (100 000 ₽): без него шесть
                                // цифр в поле давали 422 вместо понятного ответа.
                                priceKop = ((priceRub.toIntOrNull() ?: 0) * 100).coerceIn(0, 100_000_00),
                                declaredValueKop = ((declaredRub.toIntOrNull() ?: 0) * 100).coerceIn(0, 100_000_00),
                            )
                                .onSuccess { created = it }
                                .onFailure { error = (it as? com.yuldash.app.data.ApiException)?.message ?: sendErr }
                            working = false
                        }
                    },
                    style = AppButtonStyle.Accent,
                    icon = Icons.Default.Inventory2,
                    loading = working,
                    enabled = baseFilled && receiverOk && rulesAccepted,
                )
                // Курьер/купи-привези, шаг 1 — рассчитать цену (геокод городов + оценка сервера).
                estimate == null -> AppButton(
                    text = appText("Рассчитать доставку", "Илтеүҙе иҫәпләү"),
                    onClick = {
                        if (working || !(baseFilled && buyBringOk)) return@AppButton
                        working = true; error = null
                        scope.launch {
                            val fromHit = GeocoderClient.suggest(fromCity.trim()).firstOrNull()
                            val toHit = GeocoderClient.suggest(toCity.trim()).firstOrNull()
                            if (fromHit == null || toHit == null) {
                                error = geoErr; working = false; return@launch
                            }
                            // Показываем, ЧТО распозналось (канонизируем поля) — чтобы опечатка в городе
                            // не ушла тихо в неверный НП: пользователь видит подставленное название.
                            fromCity = fromHit.title; toCity = toHit.title
                            fromLat = fromHit.lat; fromLng = fromHit.lon
                            toLat = toHit.lat; toLng = toHit.lon
                            ApiClient.courierEstimate(fromHit.lat, fromHit.lon, toHit.lat, toHit.lon, size, urgency)
                                .onSuccess { estimate = it }
                                .onFailure { error = (it as? com.yuldash.app.data.ApiException)?.message ?: estErr }
                            working = false
                        }
                    },
                    style = AppButtonStyle.Primary,
                    icon = Icons.Default.Calculate,
                    loading = working,
                    enabled = baseFilled && buyBringOk,
                )
                // Курьер/купи-привези, шаг 2 — оформить заказ.
                else -> AppButton(
                    text = appText("Заказать доставку", "Илтеүҙе заказлау"),
                    onClick = {
                        val fLat = fromLat; val fLng = fromLng; val tLat = toLat; val tLng = toLng
                        val canOrder = receiverOk && rulesAccepted
                        if (working || !canOrder || fLat == null || fLng == null || tLat == null || tLng == null) return@AppButton
                        working = true; error = null
                        scope.launch {
                            ApiClient.createCourierOrder(
                                fromCity = fromCity.trim(), toCity = toCity.trim(),
                                fromLat = fLat, fromLng = fLng, toLat = tLat, toLng = tLng,
                                size = size, description = description.trim(),
                                receiverName = receiverName.trim(), receiverPhone = receiverPhone.trim(),
                                rulesAccepted = rulesAccepted, deliveryType = deliveryType, urgency = urgency,
                                codAmountKop = if (deliveryType == "buy_bring") (productRubInt ?: 0) * 100 else null,
                                shoppingList = if (deliveryType == "buy_bring") shoppingList.trim() else null,
                                // Потолок как на сервере — шесть цифр в поле иначе дают 422.
                                declaredValueKop = declaredRub.toIntOrNull()?.takeIf { it > 0 }
                                    ?.let { (it * 100).coerceAtMost(100_000_00) },
                            )
                                .onSuccess { created = it }
                                .onFailure { error = (it as? com.yuldash.app.data.ApiException)?.message ?: sendErr }
                            working = false
                        }
                    },
                    style = AppButtonStyle.Accent,
                    icon = Icons.Default.DeliveryDining,
                    loading = working,
                    enabled = receiverOk && rulesAccepted,
                )
            }
        }
    }
}

/** Карточка выбора типа доставки (По пути / Заказать курьера / Купи и привези). */
@Composable
private fun DeliveryTypeCard(
    @Suppress("UNUSED_PARAMETER") value: String,
    selected: Boolean,
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    CourierServiceTypeTile(
        title = title,
        subtitle = subtitle,
        icon = icon,
        selected = selected,
        onClick = onClick,
    )
}

/** Чип срочности доставки. */
@Composable
private fun UrgencyChip(title: String, subtitle: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = if (selected) CanonMint else CanonSurface,
        shape = CanonItemShape,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) CanonGreen2 else CanonBorder),
        modifier = modifier
            .height(64.dp)
            .semantics(mergeDescendants = true) {
                role = Role.RadioButton
                this.selected = selected
            },
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.Center) {
            Text(title, color = if (selected) CanonGreen2 else CanonText, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = CanonMuted, fontSize = 12.sp, maxLines = 1)
        }
    }
}

/** Карточка оценки цены — честно показываем итог и наш сбор + разбивку. */
@Composable
private fun EstimateCard(est: CourierEstimateDto) {
    val commEst = if (est.breakdown.commissionEstimated) appText(" ≈", " ≈") else ""
    CourierFareSummary(
        total = "≈ " + kopToRub(est.priceKop),
        courierGets = kopToRub((est.priceKop - est.commissionKop).coerceAtLeast(0)),
        fee = kopToRub(est.commissionKop) + commEst,
        distance = String.format("%.0f км", est.distanceKm),
    ) {
        EstimateRow(appText("Подача", "Килеү"), kopToRub(est.breakdown.baseKop))
        EstimateRow(appText("Расстояние", "Ара") + " (${String.format("%.0f", est.distanceKm)} км)", kopToRub(est.breakdown.distanceKop))
        EstimateRow(appText("Размер", "Ҙурлыҡ"), kopToRub(est.breakdown.sizeKop))
        if (est.breakdown.urgencyKop > 0) {
            EstimateRow(appText("Срочность", "Ашығыслыҡ"), kopToRub(est.breakdown.urgencyKop))
        }
    }
}

@Composable
private fun EstimateRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = CanonGreen2, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 13.sp)
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
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                role = Role.RadioButton
                this.selected = selected
            },
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
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                role = Role.Checkbox
                toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
            },
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
                        Surface(onClick = { clipboard.setText(AnnotatedString(p.confirmCode)) }, modifier = Modifier.minimumInteractiveComponentSize(), color = CanonMint, shape = RoundedCornerShape(12.dp)) {
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
    var disputeTarget by remember { mutableStateOf<ParcelDto?>(null) }
    var rateTarget by remember { mutableStateOf<ParcelDto?>(null) }
    var ratedIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
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
                        rated = ratedIds.contains(list[i].id),
                        onCancel = { cancelTarget = list[i] },
                        onDispute = { disputeTarget = list[i] },
                        onRate = { rateTarget = list[i] },
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

    disputeTarget?.let { target ->
        ParcelDisputeDialog(
            parcel = target,
            onDismiss = { disputeTarget = null },
            onOpened = { disputeTarget = null; reload() },
        )
    }

    rateTarget?.let { target ->
        ParcelRateDialog(
            parcel = target,
            raterIsCourier = false,
            onDismiss = { rateTarget = null },
            onRated = { ratedIds = ratedIds + target.id; rateTarget = null },
        )
    }
}

@Composable
private fun MyParcelCard(p: ParcelDto, busy: Boolean, rated: Boolean, onCancel: () -> Unit, onDispute: () -> Unit, onRate: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val terminal = isParcelTerminal(p.status)
    val canCancel = canSenderCancelParcel(
        status = p.status,
        deliveryType = p.deliveryType,
        goodsActualKop = p.settlement?.goodsActualKop ?: 0,
    )
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ParcelRouteRow(p.fromCity, p.toCity)
                    Text(parcelSizeLabel(p.size) + (if (p.description.isNotBlank()) "  ·  ${p.description}" else ""), color = CanonMuted, fontSize = 13.sp)
                }
                ParcelStatusChip(p.status)
            }
            ParcelReturnNotice(status = p.status, reason = p.returnReason, forCourier = false)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Person, contentDescription = null, tint = CanonMuted, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(appText("Получатель: ", "Алыусы: ") + p.receiverName, color = CanonText, fontSize = 13.sp)
                Spacer(Modifier.weight(1f))
                if (p.priceKop > 0) Text(kopToRub(p.priceKop), color = CanonGreen2, fontWeight = FontWeight.Black, fontSize = 15.sp)
            }
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
                                val crRating = cr.rating
                                if (crRating != null && crRating > 0) {
                                    Icon(Icons.Default.Star, contentDescription = null, tint = CanonStar, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(3.dp))
                                    Text(String.format("%.1f", crRating) + (if (cr.ratingCount > 0) " · ${cr.ratingCount}" else ""), color = CanonMuted, fontSize = 12.sp)
                                    Spacer(Modifier.width(8.dp))
                                } else {
                                    Text(appText("новый курьер", "яңы курьер"), color = CanonMuted, fontSize = 12.sp)
                                    Spacer(Modifier.width(8.dp))
                                }
                                if (cr.phone.isNotBlank()) Text(cr.phone, color = CanonGreen2, fontWeight = FontWeight.Black, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
            // Existing privacy scope: live map is available only during the forward delivery.
            if (p.courier != null && (p.status == "accepted" || p.status == "in_transit")) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(appText("Курьер в пути — следи на карте", "Курьер юлда — картала күҙәт"), color = CanonMuted, fontSize = 12.sp)
                    ParcelTrackMap(p, asCourier = false)
                }
            }
            if (!terminal) ParcelTrackLinkBlock(p.id)
            if (p.deliveryType == "buy_bring") {
                p.settlement?.let { ParcelSettlementBlock(it, forCourier = false) }
            }
            if (p.confirmCode.isNotBlank() && canCourierDeliverParcel(p.status)) {
                Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonGreen2)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(appText("Код вручения (передай получателю)", "Тапшырыу коды (алыусыға бир)"), color = CanonMuted, fontSize = 12.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(p.confirmCode, color = CanonGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, fontSize = 26.sp, modifier = Modifier.weight(1f))
                            Surface(onClick = { clipboard.setText(AnnotatedString(p.confirmCode)) }, modifier = Modifier.minimumInteractiveComponentSize(), color = CanonMint, shape = RoundedCornerShape(12.dp)) {
                                Icon(Icons.Default.ContentCopy, contentDescription = appText("Скопировать код", "Кодты күсереп алыу"), tint = CanonGreen2, modifier = Modifier.padding(9.dp).size(20.dp))
                            }
                        }
                    }
                }
            }
            if (canCancel) {
                AppButton(
                    text = appText("Отменить", "Кире алыу"),
                    onClick = onCancel,
                    style = AppButtonStyle.Secondary,
                    enabled = !busy,
                    loading = busy,
                )
            }
            if (p.status == "delivered" && p.courier != null) {
                if (rated) ParcelRatedRow() else ParcelRateButton(onClick = onRate)
            }
            if (p.courier != null && canOpenParcelDispute(p.status)) {
                ParcelDisputeButton(onClick = onDispute)
            }
        }
    }
}

// ─────────────────────────── Вкладка «Возить» (курьер) ───────────────────────────

@Composable
private fun CarryTab() {
    var sub by rememberSaveable { mutableStateOf(0) }   // 0 = доступные, 1 = везу

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
    var cityFilter by rememberSaveable { mutableStateOf("") }
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
        modifier = Modifier
            .height(48.dp)
            .semantics(mergeDescendants = true) {
                role = Role.RadioButton
                selected = active
            },
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
                // Что получит попутчик. Раньше здесь стоял НАШ сбор — курьер видел «30 ₽» и
                // думал, что это его деньги, а про свою оплату не знал ничего (аудит 2026-07-26).
                if (p.priceKop > 0) {
                    Text(kopToRub(p.priceKop), color = CanonGreen2, fontWeight = FontWeight.Black, fontSize = 18.sp)
                } else {
                    Text(appText("По-соседски", "Күрше хаҡы"), color = CanonMuted, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
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
    var goodsTarget by remember { mutableStateOf<ParcelDto?>(null) }
    var disputeTarget by remember { mutableStateOf<ParcelDto?>(null) }
    var troubleTarget by remember { mutableStateOf<ParcelDto?>(null) }
    var rateTarget by remember { mutableStateOf<ParcelDto?>(null) }
    var ratedIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    val loadErr = appText("Не удалось загрузить. Проверь интернет.", "Йөкләп булманы. Интернетты тикшер.")
    val actionErr = appText("Не получилось. Проверь сеть и повтори.", "Булманы. Селтәрҙе тикшереп ҡабатла.")
    val transitMsg = appText("Статус обновлён: в пути", "Статус яңырҙы: юлда")
    val deliveredMsg = appText("Посылка вручена. Спасибо!", "Бандероль тапшырылды. Рәхмәт!")
    val goodsSavedMsg = appText("Стоимость покупки сохранена", "Һатып алыу хаҡы һаҡланды")

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
                        onSetGoods = { goodsTarget = list[i] },
                        onDispute = { disputeTarget = list[i] },
                        onTrouble = { troubleTarget = list[i] },
                        rated = ratedIds.contains(list[i].id),
                        onRate = { rateTarget = list[i] },
                    )
                }
            }
        }
    }

    // C2: курьер вводит фактическую стоимость купленного товара (buy_bring).
    goodsTarget?.let { target ->
        var rub by remember(target.id) { mutableStateOf(((target.settlement?.goodsActualKop ?: 0) / 100).takeIf { it > 0 }?.toString() ?: "") }
        var goodsError by remember(target.id) { mutableStateOf<String?>(null) }
        var saving by remember(target.id) { mutableStateOf(false) }
        val rubInt = rub.filter(Char::isDigit).toIntOrNull()
        val goodsOk = rubInt != null && rubInt in 1..5000
        AlertDialog(
            onDismissRequest = { if (!saving) goodsTarget = null },
            containerColor = CanonSurface,
            title = { Text(appText("Стоимость покупки", "Һатып алыу хаҡы"), color = CanonText, fontWeight = FontWeight.Black) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(appText("Сколько ты потратил на товар? Получатель вернёт эту сумму плюс доставку.", "Тауарға күпме тотондоң? Алыусы был сумманы һәм илтеүҙе кире ҡайтарыр."), color = CanonMuted, fontSize = 13.sp, lineHeight = 18.sp)
                    OutlinedTextField(
                        value = rub,
                        onValueChange = { rub = it.filter(Char::isDigit).take(5); goodsError = null },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(appText("Сумма покупки, ₽", "Һатып алыу суммаһы, ₽")) },
                        placeholder = { Text("0", color = CanonMuted) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(14.dp),
                        singleLine = true,
                        isError = goodsError != null,
                    )
                    Text(appText("Лимит покупки — 5000 ₽.", "Һатып алыу лимиты — 5000 ₽."), color = CanonMuted, fontSize = 12.sp)
                    if (goodsError != null) Text(goodsError ?: "", color = CanonRed, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !saving && goodsOk,
                    onClick = {
                        val kop = (rubInt ?: 0) * 100
                        saving = true; goodsError = null
                        scope.launch {
                            ApiClient.setGoodsCost(target.id, kop)
                                .onSuccess {
                                    Toast.makeText(ctx, goodsSavedMsg, Toast.LENGTH_SHORT).show()
                                    goodsTarget = null; reload()
                                }
                                .onFailure { goodsError = (it as? com.yuldash.app.data.ApiException)?.message ?: actionErr }
                            saving = false
                        }
                    },
                ) { Text(appText("Сохранить", "Һаҡлау"), color = CanonGreen2, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(enabled = !saving, onClick = { goodsTarget = null }) { Text(appText("Отмена", "Баш тартыу"), color = CanonMuted) } },
        )
    }

    // C2: спор по заказу.
    disputeTarget?.let { target ->
        ParcelDisputeDialog(
            parcel = target,
            onDismiss = { disputeTarget = null },
            onOpened = { disputeTarget = null; reload() },
        )
    }

    troubleTarget?.let { target ->
        CourierTroubleDialog(
            parcel = target,
            onDismiss = { troubleTarget = null },
            onDone = { troubleTarget = null; reload() },
        )
    }

    // C3: курьер оценивает отправителя после вручения.
    rateTarget?.let { target ->
        ParcelRateDialog(
            parcel = target,
            raterIsCourier = true,
            onDismiss = { rateTarget = null },
            onRated = { ratedIds = ratedIds + target.id; rateTarget = null },
        )
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
private fun CarryingParcelCard(
    p: ParcelDto,
    busy: Boolean,
    onTransit: () -> Unit,
    onDeliver: () -> Unit,
    onSetGoods: () -> Unit,
    onDispute: () -> Unit,
    onTrouble: () -> Unit,
    rated: Boolean,
    onRate: () -> Unit,
) {
    val delivered = p.status == "delivered"
    val canDeliver = canCourierDeliverParcel(p.status)
    val buyBring = p.deliveryType == "buy_bring"
    val needGoods = buyBring && (p.settlement?.goodsActualKop ?: 0) == 0
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ParcelRouteRow(p.fromCity, p.toCity)
                    Text(parcelSizeLabel(p.size) + (if (p.description.isNotBlank()) "  ·  ${p.description}" else ""), color = CanonMuted, fontSize = 13.sp)
                }
                ParcelStatusChip(p.status)
            }
            CourierDeliveryProgress(status = p.status)
            ParcelReturnNotice(status = p.status, reason = p.returnReason, forCourier = true)
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
            if (buyBring) {
                p.settlement?.let { ParcelSettlementBlock(it, forCourier = true) }
            }
            if (p.priceKop > 0) {
                Text(appText("Тебе заплатят: ", "Һиңә түләйәсәктәр: ") + kopToRub(p.priceKop), color = CanonMuted, fontSize = 13.sp)
            } else {
                Text(appText("По-соседски, без оплаты", "Күрше хаҡы, түләүһеҙ"), color = CanonMuted, fontSize = 13.sp)
            }
            if (canDeliver) {
                if (needGoods) {
                    AppButton(
                        text = appText("Указать стоимость покупки", "Һатып алыу хаҡын күрһәтеү"),
                        onClick = onSetGoods,
                        style = AppButtonStyle.Accent,
                        icon = Icons.Default.ShoppingBag,
                        enabled = !busy,
                    )
                    Text(
                        appText("Сначала укажи стоимость покупки — потом сможешь вручить.", "Тәүҙә һатып алыу хаҡын күрһәт — шунан тапшыра алырһың."),
                        color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp,
                    )
                }
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
                        enabled = !busy && !needGoods,
                    )
                }
            }
            if (canCourierResolveParcelTrouble(p.status)) {
                CourierTroubleButton(returning = p.status == "returning", onClick = onTrouble)
            }
            if (delivered) {
                if (rated) ParcelRatedRow() else ParcelRateButton(onClick = onRate)
            }
            if (canOpenParcelDispute(p.status)) {
                ParcelDisputeButton(onClick = onDispute)
            }
        }
    }
}


/**
 * «Отправить ссылку получателю» — он следит за доставкой в браузере, без установки приложения.
 * Телефоны в ссылке не светятся. Отозвать можно тут же: опечатка в номере → ссылка ушла чужому
 * человеку, который иначе 72 часа видел бы точки А/Б и живую позицию курьера.
 */
@Composable
private fun ParcelTrackLinkBlock(parcelId: Int) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var link by remember(parcelId) { mutableStateOf<String?>(null) }
    var smsSent by remember(parcelId) { mutableStateOf(false) }
    var busy by remember(parcelId) { mutableStateOf(false) }
    var err by remember(parcelId) { mutableStateOf<String?>(null) }
    val failMsg = appText("Не получилось. Проверь сеть.", "Булманы. Селтәрҙе тикшер.")
    val chooser = appText("Отправить ссылку", "Һылтанманы ебәреү")

    val url = link
    if (url == null) {
        TextButton(
            onClick = {
                if (busy) return@TextButton
                busy = true; err = null
                scope.launch {
                    ApiClient.createParcelTrackLink(parcelId)
                        .onSuccess { link = it.url; smsSent = it.smsSent }
                        .onFailure { err = (it as? com.yuldash.app.data.ApiException)?.message ?: failMsg }
                    busy = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.IosShare, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(appText("Дать получателю ссылку для слежения", "Алыусыға күҙәтеү һылтанмаһы биреү"),
                color = CanonGreen2, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        err?.let { Text(it, color = CanonRed, fontSize = 12.sp) }
        return
    }

    Surface(color = CanonMint, shape = CanonItemShape) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (smsSent) appText("Ссылка отправлена получателю по SMS", "Һылтанма алыусыға SMS менән ебәрелде")
                else appText("Ссылка готова — отправь её получателю", "Һылтанма әҙер — алыусыға ебәр"),
                color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 14.sp,
            )
            Text(url, color = CanonText, fontSize = 12.sp, lineHeight = 17.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppButton(
                    text = appText("Отправить", "Ебәреү"),
                    onClick = { shareRide(ctx, url, chooser) },
                    style = AppButtonStyle.Secondary,
                    icon = Icons.Default.IosShare,
                    fillWidth = false,
                    modifier = Modifier.weight(1f),
                )
                AppButton(
                    text = appText("Отозвать", "Кире алыу"),
                    onClick = {
                        if (busy) return@AppButton
                        busy = true
                        scope.launch {
                            ApiClient.revokeParcelTrackLink(parcelId).onSuccess { link = null; smsSent = false }
                            busy = false
                        }
                    },
                    style = AppButtonStyle.Secondary,
                    loading = busy,
                    fillWidth = false,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                appText(
                    "Ошиблись номером? Отзови ссылку — она сразу перестанет работать.",
                    "Номерҙа хата булдымы? Һылтанманы кире ал — ул шунда уҡ эшләмәй башлай.",
                ),
                color = CanonMuted, fontSize = 11.sp, lineHeight = 15.sp,
            )
        }
    }
}
