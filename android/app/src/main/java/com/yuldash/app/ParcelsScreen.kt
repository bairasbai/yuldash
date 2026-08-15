package com.yuldash.app

// ═══════════════════ M3: Доставка посылок (отправитель) ═══════════════════
// Экран того, кто ОТПРАВЛЯЕТ бандероль «между своими». Две вкладки:
//  • «Отправить» — форма (города, размер, что за посылка, получатель) + обязательный чекбокс правил →
//                  createParcel → крупный КОД вручения (передать получателю).
//  • «Мои» — мои посылки: шкала доставки «Забрать → В пути → Вручить», статус, код вручения,
//            курьер (если принята), «Отменить». Список сам освежается раз в 25 с (в фоне — пауза)
//            и тянется вниз для обновления.
// Работа курьера (взять заказ, «Везу») живёт в CourierScreen. Раньше она дублировалась и здесь —
// вкладкой «Возить», но на ДРУГОМ фиде: только «по пути» и без гейта одобрения (аудит 2026-08-03).
// Всё двуязычно, все состояния (загрузка/пусто/ошибка), только Canon*, анимации плавные, тон тёплый на «ты».

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeliveryDining
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.semantics.contentDescription
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.CourierEstimateDto
import com.yuldash.app.data.GeoHit
import com.yuldash.app.data.GeocoderClient
import com.yuldash.app.data.PARCEL_ADDRESS_MAX_LEN
import com.yuldash.app.data.ParcelDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale

/** Как часто список отправителя сам подтягивает свежие статусы (в фоне цикл стоит на паузе). */
private const val MY_PARCELS_REFRESH_INTERVAL_MS = 25_000L

// ───────────────── Типографика доставки: ровно ЧЕТЫРЕ размера ─────────────────
// Больше кеглей = «самоделка»: до этого прохода на трёх экранах доставки жило 16 разных
// размеров (11,12,13,14,15,16,17,18,20,22,24,26,28,30,32,44) — глаз не понимал, что главнее.
// Роли жёсткие, других размеров в ParcelsScreen/CourierScreen/CourierOnboardingScreen нет:
//   Display — ОДНА главная цифра на карточку (код вручения, сумма, счётчик, рейтинг)
//   Title   — заголовок экрана/секции и крупный денежный акцент в ленте
//   Body    — содержательный текст; Black/Bold = заголовок строки или карточки
//   Caption — подписи, пояснения, чипы, статусы
// Размеры в sp → уважают системный шрифт и глобальный тумблер «Крупный шрифт» (FontScalePrefs).
internal val DeliveryDisplay = 34.sp
internal val DeliveryDisplayLine = 40.sp
internal val DeliveryTitle = 19.sp
internal val DeliveryTitleLine = 25.sp
internal val DeliveryBody = 16.sp
internal val DeliveryBodyLine = 23.sp
internal val DeliveryCaption = 14.sp
internal val DeliveryCaptionLine = 20.sp

/**
 * Город/село для расчёта доставки — из НАШЕГО справочника, в формате геокодера.
 * Деревень там тысячи вместе с координатами, а геокодер про «Кузяново (Ишимбайский р-н)»
 * знает хуже. Название возвращаем вместе с районом: иначе в посылке останется «Берёзовка»,
 * и через день никто не вспомнит, какая из четырёх. Не нашли — null, дальше геокодер.
 */
private suspend fun settlementHit(city: String): GeoHit? =
    ApiClient.settlementByName(city)?.let { s ->
        val d = s.district?.trim().orEmpty()
        val title = if (s.kind == "village" && d.isNotEmpty()) "${s.nameRu} ($d)" else s.nameRu
        GeoHit(title, s.lat, s.lng)
    }

/** Заголовок раздела формы. Один вид на все три экрана доставки — раньше каждый раздел
 *  подписывался вручную своим кеглем (15/17/20), и разделы выглядели разной важности. */
@Composable
internal fun DeliverySectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text, color = CanonText, fontWeight = FontWeight.Bold,
        fontSize = DeliveryTitle, lineHeight = DeliveryTitleLine, modifier = modifier,
    )
}

/** Пояснение под полем/блоком: спокойный мелкий текст. [tone] — обычно CanonMuted, у лимитов CanonRed. */
@Composable
internal fun DeliveryHint(text: String, modifier: Modifier = Modifier, tone: Color = CanonMuted) {
    Text(text, color = tone, fontSize = DeliveryCaption, lineHeight = DeliveryCaptionLine, modifier = modifier)
}

/**
 * Ошибка формы: карточка с иконкой вместо голой красной строки — её было легко не заметить
 * ровно тогда, когда заметить нужнее всего. Пусто → блок схлопнут и не занимает место.
 */
@Composable
internal fun DeliveryErrorCard(message: String?, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn(tween(CanonMotion.QUICK)) + expandVertically(tween(CanonMotion.QUICK)),
        exit = fadeOut(tween(CanonMotion.QUICK)) + shrinkVertically(tween(CanonMotion.QUICK)),
        modifier = modifier,
    ) {
        Surface(color = CanonDangerBg, shape = CanonItemShape, border = BorderStroke(1.dp, CanonDangerBorder)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = CanonRed, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(message ?: "", color = CanonText, fontSize = DeliveryBody, lineHeight = DeliveryBodyLine)
            }
        }
    }
}

// ─────────────────────────── Хелперы посылок ───────────────────────────

/** Двуязычная подпись размера посылки. */
@Composable
internal fun parcelSizeLabel(size: String): String = when (size.lowercase()) {
    "small" -> appText("Маленькая", "Бәләкәй")
    "medium" -> appText("Средняя", "Уртаса")
    "large" -> appText("Большая", "Ҙур")
    else -> size
}

/**
 * Одно правило на все дробные числа доставки: разделитель — запятая, независимо от локали телефона.
 * Раньше формат брал локаль устройства: на английской «4.8», на русской «4,8», а ставка комиссии
 * ещё и «чинилась» заменой точки, которой на этой локали в строке не было (аудит 2026-08-03).
 */
internal fun deliveryDecimal(value: Double, digits: Int = 1): String =
    String.format(java.util.Locale.US, "%.${digits}f", value).replace('.', ',')

/** Короткий намёк на габарит размера. */
@Composable
internal fun parcelSizeHint(size: String): String = when (size.lowercase()) {
    "small" -> appText("документы, ключи, конверт", "документтар, асҡыстар, конверт")
    "medium" -> appText("небольшая коробка, книга", "бәләкәй ҡумта, китап")
    "large" -> appText("сумка, крупная коробка", "һумка, ҙур ҡумта")
    else -> ""
}

// ─────────────────── Что за груз: вес · тип · хрупкость ───────────────────
// Размер (small/medium/large) отвечает ровно на ОДИН вопрос — «влезет ли». Курьер же перед
// «беру / не беру» решает три совсем других:
//   • «унесу ли»    — коробка 40×40 бывает и подушкой на 3 кг, и картошкой на 40 кг;
//   • «возьмусь ли» — лекарства, техника и продукты означают разную ответственность и разную спешку;
//   • «как положить» — хрупкое нельзя ставить под низ и класть в общую кучу.
// Поэтому вес, тип и «хрупкое» живут отдельно от размера и видны курьеру ДО принятия заказа.
// Все три поля необязательные: без них заказ всё равно должен уходить, поэтому пустое значение
// (0 / "" / false) нигде не рисуется — пустая строка «Вес: —» хуже, чем её отсутствие.

/** Потолок веса в форме. Больше 100 кг — это уже грузоперевозка, а не посылка «между своими». */
internal const val PARCEL_MAX_WEIGHT_KG = 100

/** Коды типов груза (те же, что понимает сервер) в порядке показа в форме.
 *  Порядок не алфавитный, а по частоте в селе: документы и лекарства возят чаще всего. */
internal val PARCEL_CARGO_TYPES = listOf("documents", "medicine", "food", "clothes", "tech", "other")

/** Двуязычная подпись типа груза. Лежит рядом с parcelSizeLabel и по той же причине:
 *  её читают И форма отправителя, И карточка курьера — две копии разъехались бы при первой правке. */
@Composable
internal fun cargoTypeLabel(code: String): String = when (code.lowercase()) {
    "documents" -> appText("Документы", "Документтар")
    "medicine" -> appText("Лекарства", "Дарыуҙар")
    "food" -> appText("Продукты", "Аҙыҡ-түлек")
    "clothes" -> appText("Одежда", "Кейем")
    "tech" -> appText("Техника", "Техника")
    "other" -> appText("Другое", "Башҡаһы")
    else -> code   // незнакомый код с сервера показываем как есть, а не прячем
}

/** Значок типа груза. Не украшение: в ленте из десятка заказов курьер узнаёт «лекарства»
 *  по картинке раньше, чем успевает прочитать подпись. */
internal fun cargoTypeEmoji(code: String): String = when (code.lowercase()) {
    "documents" -> "📄"
    "medicine" -> "💊"
    "food" -> "🥫"
    "clothes" -> "👕"
    "tech" -> "📱"
    else -> "📦"
}

/** Вес одной строкой, без лишних нулей: 4.0 → «4 кг», 3.5 → «3,5 кг».
 *  Форма спрашивает целые килограммы, но дробное значение может прийти с сервера — не врём. */
@Composable
internal fun parcelWeightLabel(weightKg: Double): String {
    val value = if (weightKg % 1.0 == 0.0) deliveryDecimal(weightKg, 0) else deliveryDecimal(weightKg, 1)
    return value + appText(" кг", " кг")
}

// ─────────────────── Срок доставки: «к какому дню нужно» ───────────────────
// Это НЕ городской ETA «привезём за 2 ч 15 мин». В селе доставка держится на том, поедет ли
// кто-то в ту сторону, и минутный прогноз тут был бы враньём. Поэтому срок — ДОГОВОРЁННОСТЬ:
// отправитель называет день, курьер видит этот день ДО того, как взяться, и решает, успеет ли.
// Формат везде один и тот же, серверный (2026-08-05); пустая строка = «не срочно».

/**
 * Дата через [days] дней в формате сервера (2026-08-05).
 *
 * Locale.US задаём явно: под локалью телефона в строку попали бы её собственные цифры, и сервер
 * получил бы мусор. Та же грабля, что у денежного форматтера выше (аудит 2026-08-03).
 */
internal fun deliveryDateAhead(days: Int): String {
    val cal = java.util.Calendar.getInstance()
    cal.add(java.util.Calendar.DAY_OF_YEAR, days)
    return String.format(
        java.util.Locale.US, "%04d-%02d-%02d",
        cal.get(java.util.Calendar.YEAR),
        cal.get(java.util.Calendar.MONTH) + 1,
        cal.get(java.util.Calendar.DAY_OF_MONTH),
    )
}

private val deliveryMonthsRu = listOf(
    "января", "февраля", "марта", "апреля", "мая", "июня",
    "июля", "августа", "сентября", "октября", "ноября", "декабря",
)
private val deliveryMonthsBa = listOf(
    "ғинуар", "февраль", "март", "апрель", "май", "июнь",
    "июль", "август", "сентябрь", "октябрь", "ноябрь", "декабрь",
)

/** «2026-08-05» → «5 августа». Нечитаемую строку отдаём как есть: врать датой нельзя. */
@Composable
private fun deliveryDateHuman(date: String): String {
    val parts = date.split("-")
    if (parts.size != 3) return date
    val month = parts[1].toIntOrNull() ?: return date
    val day = parts[2].toIntOrNull() ?: return date
    if (month < 1 || month > 12) return date
    return "$day " + appText(deliveryMonthsRu[month - 1], deliveryMonthsBa[month - 1])
}

/** Как этот день называют вслух: «Сегодня», «Завтра» или «5 августа».
 *  Пусто остаётся пустым — «не срочно» рисуется отдельно, а не прочерком. */
@Composable
internal fun deliveryDayName(date: String): String = when {
    date.isBlank() -> ""
    date == deliveryDateAhead(0) -> appText("Сегодня", "Бөгөн")
    date == deliveryDateAhead(1) -> appText("Завтра", "Иртәгә")
    else -> deliveryDateHuman(date)
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

/**
 * Бейдж статуса посылки. Статус меняется у человека на глазах («Ждёт курьера» → «У курьера» →
 * «В пути» → «Доставлена»), и раньше он подменялся мгновенно — читалось как сбой отрисовки.
 * Теперь подложка и текст переезжают плавно: цвета через animateColorAsState, сама надпись —
 * через AnimatedContent (уходит вверх, новая приходит снизу).
 */
@Composable
internal fun ParcelStatusChip(status: String) {
    val s = parcelStatusStyle(status)
    val bg by animateColorAsState(s.bg, tween(CanonMotion.SLOW), label = "pstatus-bg")
    val fg by animateColorAsState(s.fg, tween(CanonMotion.SLOW), label = "pstatus-fg")
    Surface(color = bg, shape = RoundedCornerShape(14.dp)) {
        AnimatedContent(
            targetState = appText(s.ru, s.ba),
            transitionSpec = { fadeIn(tween(CanonMotion.NORMAL)) togetherWith fadeOut(tween(CanonMotion.QUICK)) },
            label = "pstatus-label",
        ) { label ->
            Text(
                label, color = fg, fontWeight = FontWeight.Bold,
                fontSize = DeliveryCaption, lineHeight = DeliveryCaptionLine,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
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
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                title, color = CanonWarn, fontWeight = FontWeight.Bold,
                fontSize = DeliveryCaption, lineHeight = DeliveryCaptionLine,
            )
            if (reason.isNotBlank()) {
                Text(
                    appText("Причина: ", "Сәбәбе: ") + reason,
                    color = CanonText,
                    fontSize = DeliveryCaption,
                    lineHeight = DeliveryCaptionLine,
                )
            }
        }
    }
}

/**
 * Срок на карточке заказа — одинаковый у курьера и у отправителя.
 *
 * Курьеру он нужен, чтобы решить, браться ли: «нужно завтра» и «нужно к пятнице» — разные заказы.
 * Отправителю — чтобы понять, ждать ещё или уже пора написать. Просрочку считает СЕРВЕР (поле
 * overdue): у телефона своя дата и свой часовой пояс, и клиентский подсчёт красил бы одну и ту же
 * посылку по-разному на двух экранах.
 *
 * Срока нет → не рисуем вовсе: «Срок: —» читалось бы как «отправитель поленился написать».
 * Завершённый заказ срок тоже не показывает — там он уже не новость.
 */
@Composable
internal fun ParcelDeadlineNote(
    deliverBy: String?,
    overdue: Boolean,
    status: String,
    forCourier: Boolean,
) {
    val date = deliverBy.orEmpty()
    if (date.isBlank() || isParcelTerminal(status)) return
    // Просрочка наступает у человека на глазах (список сам обновляется раз в 25 с) — цвет
    // переезжает плавно, иначе спокойная карточка «щёлкает» в тревожную одним кадром.
    val bg by animateColorAsState(if (overdue) CanonDangerBg else CanonCourierBg, tween(CanonMotion.SLOW), label = "pdue-bg")
    val ink by animateColorAsState(if (overdue) CanonRed else CanonCourier, tween(CanonMotion.SLOW), label = "pdue-ink")
    val label = if (overdue) appText("Срок вышел · было нужно", "Ваҡыт үтте · кәрәк ине")
    else appText("Нужно доставить", "Илтергә кәрәк")
    val day = deliveryDayName(date)
    // Спокойно и без обвинений: просрочка тут чаще про «попутчик не нашёлся», чем про чью-то вину.
    val calmNote = if (forCourier) appText(
        "Отправитель ждёт. Напиши ему, если не успеваешь.",
        "Ебәреүсе көтә. Өлгөрмәһәң, уға яҙ.",
    ) else appText(
        "Посылка ещё в пути. Так бывает, когда попутчик не нашёлся сразу.",
        "Бандероль әле юлда. Юлдаш шунда уҡ табылмаһа, шулай була.",
    )
    Surface(
        color = bg,
        shape = CanonItemShape,
        border = if (overdue) BorderStroke(1.dp, CanonDangerBorder) else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp)
                // Голосом блок читается одной мыслью: «Нужно доставить. Завтра» — а не тремя
                // обрывками, между которыми проваливается смысл.
                .semantics(mergeDescendants = true) {
                    contentDescription = "$label. $day" + if (overdue) ". $calmNote" else ""
                },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Default.Schedule, contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    label, color = ink, fontWeight = FontWeight.Bold,
                    fontSize = DeliveryCaption, lineHeight = DeliveryCaptionLine,
                )
                Text(
                    day, color = CanonText, fontWeight = FontWeight.Bold,
                    fontSize = DeliveryBody, lineHeight = DeliveryBodyLine,
                )
                if (overdue) {
                    Text(
                        calmNote, color = CanonText,
                        fontSize = DeliveryCaption, lineHeight = DeliveryCaptionLine,
                    )
                }
            }
        }
    }
}

/** Строка «маршрут»: Откуда → Куда. Города сжимаются, а не уезжают за экран:
 *  башкирские названия длиннее русских, и без weight+ellipsis «Ҡара-Йылға» ломал карточку. */
@Composable
private fun ParcelRouteRow(from: String, to: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Default.Place, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(16.dp))
        Text(
            from.ifBlank { "—" }, color = CanonText, fontWeight = FontWeight.Bold,
            fontSize = DeliveryBody, lineHeight = DeliveryBodyLine,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
        )
        Text("→", color = CanonMuted, fontSize = DeliveryBody)
        Text(
            to.ifBlank { "—" }, color = CanonText, fontWeight = FontWeight.Bold,
            fontSize = DeliveryBody, lineHeight = DeliveryBodyLine,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
        )
    }
}

/**
 * «Где забрать» и «Куда привезти» — свободный ориентир от отправителя.
 *
 * Город без ориентира курьеру бесполезен: он брал заказ и ехал «в Баймак» — ни дома, ни калитки.
 * В башкирском селе адрес чаще ориентир, чем улица с табличкой («у мечети», «синие ворота»,
 * «за магазином»), поэтому это ОДНА свободная строка на сторону, а не разбор на улицу-дом-квартиру.
 *
 * Приватность (§8): в открытой ленте свободных заказов сервер адресов не отдаёт — они приходят
 * только принявшему курьеру и самому отправителю. Пустая строка = «не пришло»: строку не рисуем
 * вовсе — ни прочерка, ни заглушки, иначе курьер решит, что отправитель поленился написать.
 *
 * [prominent] = карточка курьера: он по этому едет, поэтому зелёная рамка и жирное начертание.
 * false = карточка отправителя, ему достаточно спокойно свериться с тем, что он сам указал.
 */
/**
 * Снимки посылки на двух границах ответственности: какой её ВЗЯЛИ и какой ОТДАЛИ.
 *
 * Зачем: спор «было битое / стало битое» иначе упирается в слово против слова. Сервер хранил
 * оба снимка давно, но показать их было негде — то есть доказательство существовало и было
 * недоступно обеим сторонам. Видят их и отправитель, и курьер: это защита для обоих.
 *
 * Пусто → блок не рисуется вовсе. Плашки «фото нет» тут были бы обвинением без повода.
 */
@Composable
internal fun ParcelPhotoStrip(pickupUrl: String, deliveryUrl: String) {
    val shots = remember(pickupUrl, deliveryUrl) {
        buildList {
            if (pickupUrl.isNotBlank()) add(pickupUrl to true)
            if (deliveryUrl.isNotBlank()) add(deliveryUrl to false)
        }
    }
    if (shots.isEmpty()) return
    var viewing by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    val token = remember { ApiClient.currentToken() ?: "" }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            appText("Как выглядела посылка", "Бандероль ниндәй ине"),
            color = CanonMuted, fontSize = DeliveryCaption, lineHeight = DeliveryCaptionLine,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            shots.forEach { (url, isPickup) ->
                val label = if (isPickup) appText("Когда забрал", "Алғанда")
                else appText("Когда вручил", "Тапшырғанда")
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    coil.compose.AsyncImage(
                        model = authedImageRequest(ctx, url, token),
                        contentDescription = label,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(84.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(CanonBg)
                            .clickable { viewing = url },
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(label, color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp)
                }
            }
        }
    }

    // Просмотр во весь экран: тема спорная и тяжёлая — никаких жестов, только посмотреть и закрыть.
    viewing?.let { url ->
        Dialog(onDismissRequest = { viewing = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(
                Modifier.fillMaxSize().background(CanonScrim).clickable { viewing = null },
                contentAlignment = Alignment.Center,
            ) {
                coil.compose.AsyncImage(
                    model = authedImageRequest(ctx, url, token),
                    contentDescription = appText("Фото посылки", "Бандероль фотоһы"),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
        }
    }
}

@Composable
internal fun ParcelAddressBlock(
    fromAddress: String,
    toAddress: String,
    prominent: Boolean,
    modifier: Modifier = Modifier,
) {
    val hasFrom = fromAddress.isNotBlank()
    val hasTo = toAddress.isNotBlank()
    if (!hasFrom && !hasTo) return
    Surface(
        color = CanonSurface,
        shape = CanonItemShape,
        border = BorderStroke(if (prominent) 2.dp else 1.dp, if (prominent) CanonGreen2 else CanonBorder),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (hasFrom) {
                ParcelAddressRow(
                    icon = Icons.Default.Place,
                    tint = CanonGreen2,
                    label = appText("Где забрать", "Ҡайҙан алырға"),
                    value = fromAddress,
                    prominent = prominent,
                )
            }
            // Волосок между точками только когда их правда две — одинокая линия под одной строкой
            // читалась бы как «вторую забыли показать».
            if (hasFrom && hasTo) {
                Surface(color = CanonHairlineGreen, modifier = Modifier.fillMaxWidth().height(1.dp)) {}
            }
            if (hasTo) {
                ParcelAddressRow(
                    icon = Icons.Default.Flag,
                    tint = CanonCourier,
                    label = appText("Куда привезти", "Ҡайҙа илтергә"),
                    value = toAddress,
                    prominent = prominent,
                )
            }
        }
    }
}

/** Одна точка блока адресов: иконка · подпись · сам ориентир. Читается голосом целиком. */
@Composable
private fun ParcelAddressRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    label: String,
    value: String,
    prominent: Boolean,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = "$label. $value" },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                label, color = CanonMutedStrong, fontWeight = FontWeight.Bold,
                fontSize = DeliveryCaption, lineHeight = DeliveryCaptionLine,
            )
            Text(
                value, color = CanonText,
                fontWeight = if (prominent) FontWeight.Bold else FontWeight.Bold,
                fontSize = DeliveryBody, lineHeight = DeliveryBodyLine,
            )
        }
    }
}

// ─────────────────── C2: расчёт «купи и привези» + спор (общее для двух экранов) ───────────────────

/** Блок расчёта «купи и привези»: за товар · доставка · получатель платит.
 *  forCourier=true — подсказка курьеру («укажи, сколько потратил»); false — отправителю. */
@Composable
internal fun ParcelSettlementBlock(s: com.yuldash.app.data.ParcelSettlementDto, forCourier: Boolean) {
    val hasGoods = s.goodsActualKop > 0
    Surface(color = CanonWarnBg, shape = CanonItemShape) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.ShoppingBag, contentDescription = null, tint = CanonWarn, modifier = Modifier.size(16.dp))
                Text(appText("Купи и привези", "Ал да килтер"), color = CanonWarn, fontWeight = FontWeight.Bold, fontSize = DeliveryCaption, lineHeight = DeliveryCaptionLine)
            }
            if (hasGoods) SettlementAmountRow(appText("За товар", "Тауар өсөн"), kopToRub(s.goodsActualKop))
            SettlementAmountRow(appText("Доставка", "Илтеү"), kopToRub(s.deliveryKop))
            if (hasGoods) {
                // Итог получателя — главная цифра блока, поэтому в колонку: длинная башкирская
                // подпись и сумма в одной строке отжимали друг друга.
                Surface(color = CanonSurface, shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(appText("Получатель платит", "Алыусы түләй"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = DeliveryCaption, lineHeight = DeliveryCaptionLine)
                        Text(kopToRub(s.totalDueKop), color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = DeliveryDisplay, lineHeight = DeliveryDisplayLine)
                    }
                }
            } else {
                Text(
                    if (forCourier) appText("Укажи, сколько потратил на товар, — сумма для получателя посчитается сама.", "Тауарға күпме тотонғаныңды күрһәт — алыусыға сумма үҙе иҫәпләнер.")
                    else appText("Курьер купит товар на свои. Стоимость появится здесь после покупки — получатель вернёт её плюс доставку.", "Курьер тауарҙы үҙ аҡсаһына алыр. Хаҡы һатып алғас бында күренер — алыусы уны һәм илтеүҙе кире ҡайтарыр."),
                    color = CanonWarn, fontSize = DeliveryCaption, lineHeight = DeliveryCaptionLine,
                )
            }
            if (s.settled) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(16.dp))
                    Text(appText("Расчёт закрыт", "Иҫәп ябылды"), color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = DeliveryCaption, lineHeight = DeliveryCaptionLine)
                }
            }
        }
    }
}

@Composable
private fun SettlementAmountRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = CanonWarn, fontSize = DeliveryCaption, lineHeight = DeliveryCaptionLine, modifier = Modifier.weight(1f))
        Text(value, color = CanonWarn, fontWeight = FontWeight.Bold, fontSize = DeliveryCaption, lineHeight = DeliveryCaptionLine)
    }
}

/** Мягкая кнопка «Открыть спор» на карточке доставки. Тач-цель 48dp: у голого TextButton
 *  высота 40dp — по спеке доступности мало, а промах здесь стоит нервов в конфликтной ситуации. */
@Composable
internal fun ParcelDisputeButton(onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) {
        Icon(Icons.Default.ReportProblem, contentDescription = null, tint = CanonMuted, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(appText("Открыть спор", "Бәхәс асыу"), color = CanonMuted, fontWeight = FontWeight.Bold, fontSize = DeliveryCaption, lineHeight = DeliveryCaptionLine)
    }
}

/** Диалог спора (отправитель/курьер). onOpened вызывается после успешного открытия спора. */
@Composable
internal fun ParcelDisputeDialog(parcel: ParcelDto, onDismiss: () -> Unit, onOpened: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var disputeType by remember(parcel.id) { mutableStateOf("") }
    var reason by remember(parcel.id) { mutableStateOf("") }
    var photos by remember(parcel.id) { mutableStateOf<List<String>>(emptyList()) }
    var pendingPhotoUri by remember(parcel.id) { mutableStateOf<android.net.Uri?>(null) }
    var uploading by remember(parcel.id) { mutableStateOf(false) }
    var submitting by remember(parcel.id) { mutableStateOf(false) }
    var uploadError by remember(parcel.id) { mutableStateOf<String?>(null) }
    var submitError by remember(parcel.id) { mutableStateOf<String?>(null) }

    val maxPhotos = 3
    val minReasonLength = 10
    val reasonLength = reason.trim().length
    val reasonOk = reasonLength >= minReasonLength
    val failMsg = appText("Не получилось открыть спор. Проверь сеть.", "Бәхәс асып булманы. Селтәрҙе тикшер.")
    val okMsg = appText("Спор открыт. Мы разберёмся по-соседски.", "Бәхәс асылды. Күршеләрсә ҡарарбыҙ.")
    val uploadFail = appText("Фото не загрузилось. Попробуй ещё раз.", "Фото йөкләнмәне. Тағы ҡабатла.")
    val decodeFail = appText("Не удалось прочитать фото. Выбери другое.", "Фотоны уҡып булманы. Башҡаһын һайла.")
    val validationFail = appText(
        "Выбери тип спора и опиши ситуацию хотя бы в 10 символах.",
        "Бәхәс төрөн һайла һәм хәлде кәмендә 10 символ менән яҙ.",
    )
    val declared = parcel.declaredValueKop

    fun uploadPhoto(uri: android.net.Uri) {
        if (uploading || submitting || photos.size >= maxPhotos) return
        pendingPhotoUri = uri
        uploading = true
        uploadError = null
        scope.launch {
            val bytes = withContext(Dispatchers.IO) { decodeToJpeg(ctx, uri) }
            if (bytes == null) {
                pendingPhotoUri = null
                uploadError = decodeFail
                uploading = false
                return@launch
            }
            ApiClient.uploadEvidence(bytes)
                .onSuccess { url ->
                    if (url.isBlank()) {
                        uploadError = uploadFail
                    } else {
                        photos = (photos + url).distinct().take(maxPhotos)
                        pendingPhotoUri = null
                    }
                }
                .onFailure { uploadError = uploadFail }
            uploading = false
        }
    }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) uploadPhoto(uri)
    }

    val photoButtonText = when {
        uploading -> appText("Загружаем фото…", "Фото йөкләнә…")
        uploadError != null && pendingPhotoUri != null -> appText("Повторить загрузку фото", "Фотоны тағы йөкләү")
        uploadError != null -> appText("Выбрать другое фото", "Башҡа фото һайлау")
        photos.size >= maxPhotos -> appText("Добавлено 3 фото", "3 фото тағылды")
        else -> appText("Добавить фото (${photos.size}/3)", "Фото өҫтәргә (${photos.size}/3)")
    }

    AlertDialog(
        onDismissRequest = { if (!submitting && !uploading) onDismiss() },
        containerColor = CanonSurface,
        title = { Text(appText("Открыть спор", "Бәхәс асыу"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = DeliveryTitle, lineHeight = DeliveryTitleLine) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    appText(
                        "Выбери причину и расскажи, что произошло. Вторую сторону попросят объясниться.",
                        "Сәбәпте һайла һәм нимә булғанын яҙ. Икенсе яҡтан да аңлатма һорарҙар.",
                    ),
                    color = CanonMuted, fontSize = DeliveryCaption, lineHeight = DeliveryCaptionLine,
                )

                DeliverySectionTitle(appText("Что случилось?", "Нимә булды?"))
                Column(
                    Modifier.selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    incidentTypesParcel.forEach { item ->
                        ParcelDisputeTypeOption(
                            label = appText(item.ru, item.ba),
                            selected = disputeType == item.key,
                            enabled = !submitting && !uploading,
                            onClick = {
                                disputeType = item.key
                                submitError = null
                            },
                        )
                    }
                }

                OutlinedTextField(
                    value = reason,
                    onValueChange = {
                        reason = it.take(1000)
                        submitError = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(appText("Подробности (обязательно)", "Ентекле мәғлүмәт (мотлаҡ)")) },
                    shape = RoundedCornerShape(14.dp),
                    minLines = 3,
                    isError = reason.isNotBlank() && !reasonOk,
                )
                DeliveryHint(
                    text = if (reason.isNotBlank() && !reasonOk) {
                        appText(
                            "Добавь подробностей: минимум 10 символов.",
                            "Ентекләберәк яҙ: кәмендә 10 символ.",
                        )
                    } else {
                        appText(
                            "Опиши факты своими словами · $reasonLength из 1000",
                            "Хәлде үҙ һүҙҙәрең менән яҙ · 1000-дән $reasonLength",
                        )
                    },
                    tone = if (reason.isNotBlank() && !reasonOk) CanonRed else CanonMuted,
                )

                Text(
                    if (declared > 0) appText("Ориентир при споре — объявленная ценность: ", "Бәхәстә ориентир — иғлан ителгән хаҡ: ") + kopToRub(declared) + "."
                    else appText("Ценность не объявлена — решаем по договорённости между своими.", "Хаҡ иғлан ителмәгән — үҙ-ара килешеү буйынса хәл итәбеҙ."),
                    color = CanonMuted, fontSize = DeliveryCaption, lineHeight = DeliveryCaptionLine,
                )

                DeliverySectionTitle(appText("Фотографии (необязательно)", "Фотолар (мотлаҡ түгел)"))
                DeliveryHint(
                    appText(
                        "Добавь до трёх фото — например, упаковку, повреждение или содержимое.",
                        "Өс фотоға тиклем өҫтә — мәҫәлән, ҡапты, боҙолған ерҙе йәки эстәлекте.",
                    ),
                )
                photos.forEachIndexed { index, _ ->
                    Surface(color = CanonMint, shape = CanonItemShape) {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                appText("Фото ${index + 1} приложено", "Фото ${index + 1} тағылды"),
                                color = CanonText, fontSize = DeliveryCaption, lineHeight = DeliveryCaptionLine,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = {
                                    photos = photos.filterIndexed { photoIndex, _ -> photoIndex != index }
                                    uploadError = null
                                    pendingPhotoUri = null
                                },
                                enabled = !submitting && !uploading,
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Text(appText("Убрать", "Алып ташлау"), color = CanonMuted, fontSize = DeliveryCaption)
                            }
                        }
                    }
                }
                AppButton(
                    text = photoButtonText,
                    onClick = {
                        val retryUri = pendingPhotoUri
                        if (uploadError != null && retryUri != null) {
                            uploadPhoto(retryUri)
                        } else {
                            pickPhoto.launch("image/*")
                        }
                    },
                    style = AppButtonStyle.Secondary,
                    icon = Icons.Default.PhotoCamera,
                    loading = uploading,
                    enabled = !submitting && !uploading && photos.size < maxPhotos,
                    height = 48.dp,
                )
                DialogErrorLine(uploadError)
                DialogErrorLine(submitError)
            }
        },
        confirmButton = {
            TextButton(
                modifier = Modifier.heightIn(min = 48.dp),
                enabled = !submitting && !uploading && disputeType.isNotBlank() && reasonOk,
                onClick = {
                    if (disputeType.isBlank() || !reasonOk) {
                        submitError = validationFail
                        return@TextButton
                    }
                    submitting = true
                    submitError = null
                    scope.launch {
                        ApiClient.disputeParcel(parcel.id, reason.trim(), disputeType, photos)
                            .onSuccess { Toast.makeText(ctx, okMsg, Toast.LENGTH_SHORT).show(); onOpened() }
                            .onFailure { submitError = (it as? com.yuldash.app.data.ApiException)?.message ?: failMsg }
                        submitting = false
                    }
                },
            ) {
                if (submitting) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = CanonRed, strokeWidth = 2.dp)
                } else {
                    Text(
                        if (submitError != null) appText("Повторить", "Ҡабатлау")
                        else appText("Открыть спор", "Бәхәс асыу"),
                        color = CanonRed, fontWeight = FontWeight.Bold, fontSize = DeliveryBody,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(
                modifier = Modifier.heightIn(min = 48.dp),
                enabled = !submitting && !uploading,
                onClick = onDismiss,
            ) {
                Text(appText("Отмена", "Баш тартыу"), color = CanonMuted, fontSize = DeliveryBody)
            }
        },
    )
}

/** Один из серверных типов спора доставки: доступная single-choice строка с тач-целью 48dp. */
@Composable
private fun ParcelDisputeTypeOption(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val bg by animateColorAsState(if (selected) CanonMint else CanonBg, tween(CanonMotion.QUICK), label = "parcel-dispute-type-bg")
    val line by animateColorAsState(if (selected) CanonGreen2 else CanonBorder, tween(CanonMotion.QUICK), label = "parcel-dispute-type-line")
    val ink by animateColorAsState(if (selected) CanonGreen2 else CanonMuted, tween(CanonMotion.QUICK), label = "parcel-dispute-type-ink")
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = bg,
        shape = CanonItemShape,
        border = BorderStroke(1.dp, line),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics(mergeDescendants = true) {
                role = Role.RadioButton
                this.selected = selected
            },
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (selected) Icons.Default.CheckCircle else Icons.Default.ReportProblem,
                contentDescription = null,
                tint = ink,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(label, color = CanonText, fontSize = DeliveryBody, lineHeight = DeliveryBodyLine)
        }
    }
}

/** Ошибка внутри диалога: одинаковая на всех диалогах доставки, появляется плавно, а не рывком. */
@Composable
internal fun DialogErrorLine(message: String?) {
    AnimatedVisibility(visible = message != null, enter = fadeIn(tween(CanonMotion.QUICK)), exit = fadeOut(tween(CanonMotion.QUICK))) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = CanonRed, modifier = Modifier.size(16.dp))
            Text(message ?: "", color = CanonRed, fontSize = DeliveryCaption, lineHeight = DeliveryCaptionLine, fontWeight = FontWeight.Bold)
        }
    }
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
    Row(
        Modifier.heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Default.Star, contentDescription = null, tint = CanonStar, modifier = Modifier.size(16.dp))
        Text(appText("Спасибо, оценка учтена", "Рәхмәт, баһа иҫәпкә алынды"), color = CanonMuted, fontWeight = FontWeight.Bold, fontSize = DeliveryCaption, lineHeight = DeliveryCaptionLine)
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
        title = { Text(appText("Оценить доставку", "Илтеүҙе баһалау"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = DeliveryTitle, lineHeight = DeliveryTitleLine) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(whoQuestion, color = CanonMuted, fontSize = DeliveryCaption, lineHeight = DeliveryCaptionLine)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (1..5).forEach { i ->
                        val filled = i <= stars
                        val starDesc = appText("Поставить $i из 5", "5-тән $i ҡуйырға")
                        // Цвет звезды догоняет палец, а не перещёлкивается: 5 звёзд разом
                        // меняли тон рывком — дёшево выглядело именно в момент благодарности.
                        val tint by animateColorAsState(if (filled) CanonStar else CanonMuted, tween(CanonMotion.QUICK), label = "star$i")
                        Icon(
                            if (filled) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = starDesc,
                            tint = tint,
                            // 48dp — тач-цель целиком, иконка 36dp внутри. Раньше clickable вешался
                            // ПОСЛЕ padding, и живая зона была 38dp — мимо звезды промахивались.
                            modifier = Modifier.size(48.dp).bounceClick { stars = i; err = null }.padding(4.dp),
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
                DialogErrorLine(err)
            }
        },
        confirmButton = {
            TextButton(
                modifier = Modifier.heightIn(min = 48.dp),
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
            ) { Text(appText("Отправить оценку", "Оценка ебәреү"), color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = DeliveryBody) }
        },
        dismissButton = { TextButton(modifier = Modifier.heightIn(min = 48.dp), enabled = !submitting, onClick = onDismiss) { Text(appText("Позже", "Һуңыраҡ"), color = CanonMuted, fontSize = DeliveryBody) } },
    )
}

// ─────────────────────────── Экран ───────────────────────────

@Composable
internal fun ParcelsScreen(onBack: () -> Unit, embedded: Boolean = false) {
    var tab by rememberSaveable { mutableStateOf(0) }   // 0 = отправить, 1 = мои

    // embedded = экран открыт внутри хаба режимов (над ним уже есть переключатель Попутка/Такси/Курьер),
    // поэтому своя шапка с «назад» была бы вторым заголовком подряд. Тот же приём, что в InstantOrderScreen.
    Scaffold(
        containerColor = CanonBg,
        topBar = { if (!embedded) ScreenTopBar(appText("Посылки", "Бандеролдәр"), onBack) },
        // Встроенный режим: системные отступы уже учёл хаб над нами — второй раз их добавлять
        // нельзя, иначе под переключателем режимов зияет пустая полоса. Отдельный экран (вход из
        // профиля) отступы сохраняет, иначе шапка залезет под статус-бар. Так же в MapScreen.kt.
        contentWindowInsets = if (embedded) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets,
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Surface(
                color = CanonSurface,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, CanonBorder),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ParcelTab(appText("Отправить", "Ебәреү"), tab == 0, Modifier.weight(1f)) { tab = 0 }
                    ParcelTab(appText("Мои", "Минеке"), tab == 1, Modifier.weight(1f)) { tab = 1 }
                }
            }
            AnimatedContent(
                targetState = tab,
                modifier = Modifier.fillMaxWidth().weight(1f),
                transitionSpec = { fadeIn(tween(CanonMotion.QUICK)) togetherWith fadeOut(tween(CanonMotion.QUICK)) },
                label = "parcel-tab",
            ) { t ->
                when (t) {
                    0 -> SendParcelTab(onSent = { tab = 1 })
                    else -> MyParcelsTab(onGoSend = { tab = 0 })
                }
            }
        }
    }
}

@Composable
private fun ParcelTab(label: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val bg by animateColorAsState(if (active) CanonMint else CanonSurface, tween(CanonMotion.QUICK), label = "ptab-bg")
    val line by animateColorAsState(if (active) CanonGreen2 else CanonBorder, tween(CanonMotion.QUICK), label = "ptab-line")
    val ink by animateColorAsState(if (active) CanonGreen2 else CanonMutedStrong, tween(CanonMotion.QUICK), label = "ptab-ink")
    Surface(
        onClick = onClick, color = bg, shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, line),
        modifier = modifier
            .heightIn(min = 48.dp)
            .semantics(mergeDescendants = true) {
                role = Role.Tab
                selected = active
            },
    ) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
            Text(
                label, color = ink, fontWeight = FontWeight.Bold,
                fontSize = DeliveryBody, lineHeight = DeliveryBodyLine,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            )
        }
    }
}

// ─────────────────────────── Вкладка «Отправить» ───────────────────────────

@Composable
private fun SendParcelTab(onSent: () -> Unit) {
    val scope = rememberCoroutineScope()
    // Keep the post-create receipt across Activity recreation. Saving the whole DTO is unsafe
    // and unnecessary; these are exactly the fields rendered on the success screen.
    var createdId by rememberSaveable { mutableStateOf(0) }
    var createdCode by rememberSaveable { mutableStateOf("") }
    var createdFrom by rememberSaveable { mutableStateOf("") }
    var createdTo by rememberSaveable { mutableStateOf("") }
    var createdReceiver by rememberSaveable { mutableStateOf("") }

    var deliveryType by rememberSaveable { mutableStateOf("poputka") }   // poputka | courier | buy_bring
    var fromCity by rememberSaveable { mutableStateOf("") }
    var toCity by rememberSaveable { mutableStateOf("") }
    // Где именно забрать и куда привезти. Города мало: курьер брал заказ и ехал «в Баймак» —
    // ни дома, ни калитки. Оба поля необязательные: без ориентира заказ всё равно должен уходить.
    var fromAddress by rememberSaveable { mutableStateOf("") }
    var toAddress by rememberSaveable { mutableStateOf("") }
    var size by rememberSaveable { mutableStateOf("") }
    // Что за груз. Размер говорит только «влезет ли»; эти три поля отвечают курьеру на
    // «унесу ли», «возьмусь ли» и «как положить» (подробнее — у PARCEL_CARGO_TYPES выше).
    // Вес держим строкой: пользователь стирает поле до пустого, а 0.0 и «не указано» —
    // одно и то же, и превращать одно в другое на каждом нажатии клавиши незачем.
    var weightInput by rememberSaveable { mutableStateOf("") }
    var cargoType by rememberSaveable { mutableStateOf("") }
    var fragile by rememberSaveable { mutableStateOf(false) }
    var description by rememberSaveable { mutableStateOf("") }
    var receiverName by rememberSaveable { mutableStateOf("") }
    var receiverPhone by rememberSaveable { mutableStateOf("") }
    var rulesAccepted by rememberSaveable { mutableStateOf(false) }
    var urgency by rememberSaveable { mutableStateOf("bypath") }          // bypath | now
    // К какому дню нужно (2026-08-05). Пусто = «не срочно» — и это значение по умолчанию:
    // большинству в селе спешить некуда, а навязанный дедлайн отпугивает курьеров, которым
    // он не по пути. До сервера пустая строка не доедет вовсе (ApiClient её отсекает).
    var deliverBy by rememberSaveable { mutableStateOf("") }
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
    // Вес: пусто = «не указан» и это нормально. Перебор по весу не молчим и не «подрезаем»
    // втихую до 100 — иначе человек уверен, что заказал 150 кг, а курьер увидит 100.
    val weightKgInt = weightInput.toIntOrNull()
    val weightOk = weightInput.isBlank() || (weightKgInt != null && weightKgInt <= PARCEL_MAX_WEIGHT_KG)
    val overWeight = weightInput.isNotBlank() && !weightOk

    // Мастер по шагам. Раньше это была ОДНА простыня из ~15 блоков: человек видел сразу все поля
    // (включая необязательные) и не понимал, сколько ещё осталось. Три коротких шага — как в
    // Самокате и Яндекс Доставке: на каждом видно, что спрашивают и сколько до конца.
    var step by rememberSaveable { mutableStateOf(0) }
    val stepRoute = 0      // способ доставки + откуда/куда
    val stepParcel = 1     // что за посылка: размер, срочность, описание, цена, ценность
    val stepReceiver = 2   // получатель, правила, отправка
    val stepTotal = 3
    // Валидация ПОШАГОВАЯ: дальше не пускаем, пока шаг не заполнен. Это и есть смысл мастера —
    // ошибку видно сразу, а не в конце длинной формы.
    val routeOk = fromCity.isNotBlank() && toCity.isNotBlank()
    val parcelOk = size.isNotBlank() && buyBringOk && weightOk
    val stepTitle = when (step) {
        stepRoute -> appText("Куда и как", "Ҡайҙа һәм нисек")
        stepParcel -> appText("Что за посылка", "Ниндәй бандероль")
        else -> appText("Получатель", "Алыусы")
    }

    fun showCreatedReceipt(parcel: ParcelDto) {
        createdId = parcel.id
        createdCode = parcel.confirmCode
        createdFrom = parcel.fromCity
        createdTo = parcel.toCity
        createdReceiver = parcel.receiverName

        // A successful submit starts a fresh draft, preventing accidental duplicate orders.
        deliveryType = "poputka"
        fromCity = ""; toCity = ""; fromAddress = ""; toAddress = ""; size = ""; description = ""
        weightInput = ""; cargoType = ""; fragile = false
        receiverName = ""; receiverPhone = ""; rulesAccepted = false
        urgency = "bypath"; shoppingList = ""; declaredRub = ""; deliverBy = ""
        priceRub = ""; productRub = ""; estimate = null
        fromLat = null; fromLng = null; toLat = null; toLng = null
        error = null
        step = stepRoute   // новый черновик начинается с первого шага, а не с последнего
    }

    if (createdId != 0) {
        ParcelCreatedView(
            confirmCode = createdCode,
            fromCity = createdFrom,
            toCity = createdTo,
            receiverName = createdReceiver,
            onDone = {
                createdId = 0
                createdCode = ""; createdFrom = ""; createdTo = ""; createdReceiver = ""
                onSent()
            },
        )
        return
    }

    LazyColumn(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
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
                // «Илтеү» — так это же слово переведено ещё в трёх местах приложения
                // (уведомление курьера, чип заказа, строка расчёта). Тут башкирская
                // сторона была копией русской, и на башкирском экране бейдж оставался
                // единственной русской надписью.
                badge = appText("Доставка", "Илтеү"),
            )
        }
        item { ParcelStepProgress(step = step, total = stepTotal, title = stepTitle) }
        // Сводка маршрута на последующих шагах. Не украшение: геокодер ЗАМЕНЯЕТ введённый город
        // на распознанный (чтобы опечатка не ушла тихо в другой НП), а поля ввода остались на
        // шаге 1 — без этой строки человек не увидел бы подмену. Плюс всегда виден и правится
        // уже сделанный выбор.
        if (step != stepRoute) item {
            ParcelRouteSummary(fromCity = fromCity, toCity = toCity, onEdit = { if (!working) step = stepRoute })
        }
        // ── Шаг 1: способ доставки + маршрут ───────────────────────────────────────
        // Тип доставки
        if (step == stepRoute) item {
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
                // Способ выбран — сразу честно про ожидание. Без этой строки человек видел, ЧТО
                // за способ, но не понимал, сегодня приедет или через неделю.
                DeliveryWaitNote(deliveryType)
            }
        }
        // Маршрут. Каждый город идёт в паре со своим ориентиром: «Откуда» — это село целиком,
        // «Где забрать» — конкретная калитка в нём. Порядок не декоративный: человек заполняет
        // сверху вниз ровно так, как рассказывал бы дорогу вслух.
        if (step == stepRoute) item {
            ParcelField(fromCity, { fromCity = it; estimate = null }, appText("Откуда", "Ҡайҙан"), appText("Город отправления", "Ебәреү ҡалаһы"), cap = true)
        }
        if (step == stepRoute) item {
            ParcelField(
                fromAddress, { fromAddress = it.take(PARCEL_ADDRESS_MAX_LEN) },
                appText("Где забрать", "Ҡайҙан алырға"),
                appText("У мечети, синие ворота", "Мәсет янында, зәңгәр ҡапҡа"),
                minLines = 2,
            )
        }
        if (step == stepRoute) item {
            ParcelField(toCity, { toCity = it; estimate = null }, appText("Куда", "Ҡайҙа"), appText("Город получения", "Алыу ҡалаһы"), cap = true)
        }
        if (step == stepRoute) item {
            ParcelField(
                toAddress, { toAddress = it.take(PARCEL_ADDRESS_MAX_LEN) },
                appText("Куда привезти", "Ҡайҙа илтергә"),
                appText("За школой, белый дом с зелёной крышей", "Мәктәп артында, йәшел түбәле аҡ йорт"),
                minLines = 2,
            )
        }
        // Одна честная строка про оба поля: кто это увидит и когда. Про «необязательно» не пишем
        // отдельно — важнее объяснить, что писать, иначе человек напишет городской адрес.
        if (step == stepRoute) item {
            val atLimit = fromAddress.length >= PARCEL_ADDRESS_MAX_LEN || toAddress.length >= PARCEL_ADDRESS_MAX_LEN
            DeliveryHint(
                if (atLimit) appText(
                    "Больше $PARCEL_ADDRESS_MAX_LEN символов не влезет — оставь самое главное.",
                    "$PARCEL_ADDRESS_MAX_LEN символдан артыҡ һыймай — иң мөһимен ҡалдыр.",
                ) else appText(
                    "Не улица с табличкой, а как объясняешь соседу: «у мечети», «синие ворота», «за магазином». Курьер увидит это, когда возьмёт посылку.",
                    "Таблицалы урам түгел, ә күршегә аңлатҡан кеүек: «мәсет янында», «зәңгәр ҡапҡа», «кибет артында». Курьер быны бандеролде алғас күрер.",
                ),
                tone = if (atLimit) CanonRed else CanonMuted,
            )
        }
        // ── Шаг 2: сама посылка ────────────────────────────────────────────────────
        // Размер
        if (step == stepParcel) item {
            DeliverySectionTitle(appText("Размер посылки", "Бандероль ҙурлығы"))
        }
        if (step == stepParcel) item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("small", "medium", "large").forEach { s ->
                    ParcelSizeCard(s, selected = size == s) { size = s; estimate = null }
                }
            }
        }
        // Что за груз — сразу за размером, потому что отвечает на СОСЕДНИЕ вопросы. Размер
        // говорит «влезет ли в багажник», а курьер перед «беру / не беру» решает ещё три:
        // «унесу ли» (коробка 40×40 — это и подушка 3 кг, и картошка 40 кг), «возьмусь ли»
        // (лекарства и техника — разная ответственность) и «как положить» (хрупкое — не вниз).
        // Все три поля необязательные: пропустил — заказ всё равно уходит.
        if (step == stepParcel) item {
            DeliverySectionTitle(appText("Что за груз", "Ниндәй йөк"))
        }
        if (step == stepParcel) item {
            ParcelField(
                weightInput, { weightInput = it.filter(Char::isDigit).take(3) },
                appText("Вес", "Ауырлыҡ"),
                appText("Примерно, в килограммах", "Яҡынса, килограммда"),
                phone = true,
            )
        }
        if (step == stepParcel) item {
            DeliveryHint(
                if (overWeight) appText(
                    "Больше $PARCEL_MAX_WEIGHT_KG кг — это уже грузоперевозка, а не посылка. Уменьши вес.",
                    "$PARCEL_MAX_WEIGHT_KG кг-дан артыҡ — был инде йөк ташыу, бандероль түгел. Ауырлыҡты кәметер.",
                ) else appText(
                    "Необязательно, но курьер сразу поймёт, унесёт ли один. Точность до грамма не нужна — хватит «на глаз».",
                    "Мотлаҡ түгел, әммә курьер шунда уҡ яңғыҙы күтәрә аламы-юҡмы аңлар. Граммға тиклем теүәллек кәрәкмәй — «күҙ менән» етә.",
                ),
                tone = if (overWeight) CanonRed else CanonMuted,
            )
        }
        if (step == stepParcel) item {
            ParcelCargoTypePicker(cargoType) { cargoType = it }
        }
        if (step == stepParcel) item {
            ParcelFragileSwitch(fragile) { fragile = !fragile }
        }
        // Срок: к какому дню нужно. Спрашиваем у ВСЕХ типов, включая «по пути» — именно там
        // человек и не понимает, сегодня посылка уедет или через неделю.
        if (step == stepParcel) item {
            DeliverySectionTitle(appText("Когда нужно", "Ҡасан кәрәк"))
        }
        if (step == stepParcel) item {
            ParcelDeadlinePicker(value = deliverBy, onValue = { deliverBy = it })
        }
        // Способ перевозки (курьер / купи-привези). Заголовок именно про «как», а не про «когда»:
        // это выбор скорости и цены, а срок из блока выше — про день, к которому нужно.
        if (isCourier && step == stepParcel) {
            item {
                // Блок появляется только у курьерских типов — значит въезжает мягко,
                // а не выпрыгивает посреди формы (appearIn стартует с нуля, не с цели).
                DeliverySectionTitle(appText("Как везти", "Нисек илтергә"), Modifier.appearIn())
            }
            item {
                Row(Modifier.appearIn(1), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
        if (deliveryType == "buy_bring" && step == stepParcel) {
            item {
                Box(Modifier.appearIn()) {
                    ParcelField(shoppingList, { shoppingList = it }, appText("Что купить", "Нимә алырға"), appText("Например: хлеб, молоко, лекарство из аптеки", "Мәҫәлән: икмәк, һөт, дарыуханан дарыу"), minLines = 2)
                }
            }
            item {
                Box(Modifier.appearIn(1)) {
                    ParcelField(productRub, { productRub = it.filter(Char::isDigit).take(5); estimate = null }, appText("Сумма покупки, ₽", "Һатып алыу суммаһы, ₽"), "0", phone = true)
                }
            }
            item {
                val overLimit = productRubInt != null && productRubInt > 5000
                DeliveryHint(
                    if (overLimit) appText("Лимит покупки — 5000 ₽. Уменьши сумму.", "Һатып алыу лимиты — 5000 ₽. Сумманы кәметер.")
                    else appText("Курьер купит на эту сумму, а получатель вернёт её при вручении.", "Курьер шул суммаға алыр, алыусы тапшырғанда кире ҡайтарыр."),
                    tone = if (overLimit) CanonRed else CanonMuted,
                )
            }
        }
        // Что за посылка / комментарий
        if (step == stepParcel) item {
            ParcelField(description, { description = it }, appText("Что за посылка", "Нимә бул"), appText("Например: документы, книга, гостинец", "Мәҫәлән: документтар, китап, күстәнәс"), minLines = 2)
        }
        // Сколько заплатишь попутчику. Только для «по пути»: у курьерских типов цену считает
        // сервер (EstimateCard ниже). Раньше поля не было, и человек соглашался везти вслепую.
        if (!isCourier && step == stepParcel) {
            item {
                ParcelField(
                    priceRub, { priceRub = it.filter(Char::isDigit).take(6) },
                    appText("Сколько заплатишь попутчику, ₽", "Юлдашҡа күпме түләйһең, ₽"),
                    "0", phone = true,
                )
            }
            item {
                DeliveryHint(
                    if ((priceRub.toIntOrNull() ?: 0) > 0)
                        appText(
                            "Отдашь эти деньги попутчику лично — Юлдаш к ним не прикасается.",
                            "Был аҡсаны юлдашҡа үҙең бирәһең — Юлдаш уға ҡағылмай.",
                        )
                    else appText(
                        "Оставь пусто — значит по-соседски, бесплатно. Так и увидит попутчик.",
                        "Буш ҡалдыр — тимәк күрше хаҡы, бушлай. Юлдаш шулай күрер.",
                    ),
                )
            }
        }
        // Объявленная ценность. Поля не было вообще — и любой спор о повреждении падал в ветку
        // «ценность не объявлена»: доказывать было нечем, ориентира для компенсации не существовало.
        if (step == stepParcel) item {
            ParcelField(
                declaredRub, { declaredRub = it.filter(Char::isDigit).take(6) },
                appText("Ценность посылки, ₽ (необязательно)", "Бандероль хаҡы, ₽ (мотлаҡ түгел)"),
                "0", phone = true,
            )
        }
        if (step == stepParcel) item {
            DeliveryHint(
                appText(
                    "Если что-то случится, это будет ориентиром при разборе. Не страховка — но без цифры спорить не о чем.",
                    "Берәй хәл булһа, был ҡарағанда ориентир булыр. Страховка түгел — әммә һанһыҙ бәхәсләшер нәмә юҡ.",
                ),
            )
        }
        // ── Шаг 3: получатель, правила, отправка ───────────────────────────────────
        if (step == stepReceiver) item {
            DeliverySectionTitle(appText("Получатель", "Алыусы"))
        }
        if (step == stepReceiver) item {
            ParcelField(receiverName, { receiverName = it }, appText("Имя получателя", "Алыусы исеме"), appText("Кто встретит курьера", "Курьерҙы кем ҡаршылай"), cap = true)
        }
        if (step == stepReceiver) item {
            ParcelField(receiverPhone, { receiverPhone = it }, appText("Телефон получателя", "Алыусы телефоны"), "+7 …", phone = true)
        }
        // Оценка стоимости (курьер / купи-привези) — показываем ЧЕСТНО, из чего сложилась цена.
        // Это главный момент формы: цена посчиталась → карточка всплывает, а не возникает.
        estimate?.let { est ->
            if (isCourier && step == stepReceiver) item { Box(Modifier.appearIn()) { EstimateCard(est) } }
        }
        // Обязательный чекбокс правил
        if (step == stepReceiver) item {
            RulesCheckbox(rulesAccepted) { rulesAccepted = !rulesAccepted }
        }
        if (step == stepReceiver) item {
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
                    color = CanonGreen2, fontSize = DeliveryCaption, lineHeight = DeliveryCaptionLine, modifier = Modifier.padding(16.dp),
                )
            }
        }
        item { DeliveryErrorCard(error) }
        // Шаги 1-2: «Далее».
        //
        // Кнопка гаснет, пока шаг не заполнен, — но ОДНОГО этого мало. Живая проверка на
        // эмуляторе (2026-08-03) показала, чем это оборачивается: не выбран размер посылки,
        // а сам выбор уехал вверх за край экрана. Человек видит серую кнопку, жмёт её,
        // ничего не происходит, и никто нигде не говорит почему. Он решает, что приложение
        // сломалось, и уходит. Поэтому под погасшей кнопкой прямо пишем, чего не хватает.
        if (step != stepReceiver) item {
            val blocked = if (step == stepRoute) !routeOk else !parcelOk
            AppButton(
                text = appText("Далее", "Артабан"),
                onClick = { if (step == stepRoute && routeOk) step = stepParcel else if (step == stepParcel && parcelOk) step = stepReceiver },
                style = AppButtonStyle.Accent,
                enabled = !blocked,
            )
            val hint = when {
                step == stepRoute && fromCity.isBlank() -> appText("Укажи, откуда забрать посылку", "Бандерольде ҡайҙан алырға икәнен күрһәт")
                step == stepRoute && toCity.isBlank() -> appText("Укажи, куда её привезти", "Уны ҡайҙа илтергә икәнен күрһәт")
                step == stepParcel && size.isBlank() -> appText("Выбери размер посылки — он выше", "Бандероль үлсәмен һайла — ул юғарыраҡ")
                step == stepParcel && !weightOk -> appText("Вес больше 100 кг — это уже грузоперевозка", "Ауырлыҡ 100 кг-дан артыҡ — был инде йөк ташыу")
                step == stepParcel && !buyBringOk -> appText("Укажи стоимость покупки", "Һатып алыу хаҡын күрһәт")
                else -> null
            }
            if (blocked && hint != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    hint,
                    color = CanonWarn,
                    fontSize = DeliveryCaption,
                    lineHeight = DeliveryCaptionLine,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        // Та же беда, что и на «Далее»: последний шаг гаснет молча, а незаполненное поле
        // (имя, телефон или галочка правил) может быть выше края экрана.
        if (step == stepReceiver) item {
            val hint = when {
                receiverName.isBlank() -> appText("Впиши имя получателя", "Алыусының исемен яҙ")
                receiverPhone.isBlank() -> appText("Впиши телефон получателя", "Алыусының телефонын яҙ")
                !rulesAccepted -> appText("Отметь галочку с правилами доставки", "Доставка ҡағиҙәләре янындағы билдәне ҡуй")
                else -> null
            }
            if (hint != null) {
                Text(
                    hint,
                    color = CanonWarn,
                    fontSize = DeliveryCaption,
                    lineHeight = DeliveryCaptionLine,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )
            }
        }
        if (step == stepReceiver) item {
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
                                // Где забрать и куда привезти. Пустые до сервера не доедут —
                                // их отсекает сам ApiClient, чтобы не слать пустой шум.
                                fromAddress = fromAddress.trim(), toAddress = toAddress.trim(),
                                // Срок «к какому дню нужно». Пусто = «не срочно» и в тело не попадёт.
                                deliverBy = deliverBy,
                                // Что везём. 0 / "" / false до сервера не доедут — их отсекает
                                // сам ApiClient, чтобы «не указано» не превращалось в «ноль кг».
                                weightKg = (weightKgInt ?: 0).coerceIn(0, PARCEL_MAX_WEIGHT_KG).toDouble(),
                                cargoType = cargoType,
                                fragile = fragile,
                            )
                                .onSuccess { showCreatedReceipt(it) }
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
                            // Сначала наш справочник (там все сёла РБ и приграничья с координатами),
                            // и только потом геокодер: «Кузяново (Ишимбайский р-н)» он ищет хуже.
                            val fromHit = settlementHit(fromCity) ?: GeocoderClient.suggest(fromCity.trim()).firstOrNull()
                            val toHit = settlementHit(toCity) ?: GeocoderClient.suggest(toCity.trim()).firstOrNull()
                            if (fromHit == null || toHit == null) {
                                error = geoErr; working = false; return@launch
                            }
                            // Показываем, ЧТО распозналось (канонизируем поля) — чтобы опечатка в городе
                            // не ушла тихо в неверный НП: пользователь видит подставленное название.
                            fromCity = fromHit.title; toCity = toHit.title
                            fromLat = fromHit.lat; fromLng = fromHit.lon
                            toLat = toHit.lat; toLng = toHit.lon
                            ApiClient.courierEstimate(
                                fromHit.lat,
                                fromHit.lon,
                                toHit.lat,
                                toHit.lon,
                                size,
                                urgency,
                                deliveryType,
                            )
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
                                fromAddress = fromAddress.trim(), toAddress = toAddress.trim(),
                                deliverBy = deliverBy,
                                // Что везём — те же три поля, что и у «по пути»: заказ курьера
                                // отличается ценой и сроками, а не тем, что внутри коробки.
                                weightKg = (weightKgInt ?: 0).coerceIn(0, PARCEL_MAX_WEIGHT_KG).toDouble(),
                                cargoType = cargoType,
                                fragile = fragile,
                            )
                                .onSuccess { showCreatedReceipt(it) }
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
        // «Назад» — на любом шаге кроме первого. Черновик при этом не теряется: все поля
        // живут в rememberSaveable, ходить между шагами можно свободно.
        if (step != stepRoute) item {
            AppButton(
                text = appText("Назад", "Артҡа"),
                onClick = { if (!working) step -= 1 },
                style = AppButtonStyle.Secondary,
                enabled = !working,
            )
        }
    }
}

/** Сводка «Откуда → Куда» на шагах 2-3 с кнопкой «изменить».
 *  Показывает РАСПОЗНАННЫЕ названия городов (геокодер канонизирует ввод), поэтому опечатка,
 *  уехавшая в соседний населённый пункт, остаётся заметной и после ухода с первого шага. */
@Composable
private fun ParcelRouteSummary(fromCity: String, toCity: String, onEdit: () -> Unit) {
    Surface(
        color = CanonSurface,
        shape = CanonItemShape,
        border = BorderStroke(1.dp, CanonBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Default.LocalShipping, contentDescription = null, tint = CanonCourier, modifier = Modifier.size(20.dp))
            Text(
                "$fromCity → $toCity",
                color = CanonText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                modifier = Modifier.weight(1f),
            )
            // TextButton, а не Text с clickable: даёт ripple и тач-цель ≥48dp (§4.5).
            TextButton(onClick = onEdit) {
                Text(
                    appText("изменить", "үҙгәртергә"),
                    color = CanonCourier,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/** Полоска прогресса мастера: сколько шагов пройдено и как называется текущий.
 *  Без неё длинная форма ощущается бесконечной — человек не знает, сколько ещё осталось. */
@Composable
private fun ParcelStepProgress(step: Int, total: Int, title: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
            repeat(total) { i ->
                val passed = i <= step
                val barColor by animateColorAsState(
                    if (passed) CanonCourier else CanonBorder, tween(CanonMotion.NORMAL), label = "parcelStep$i",
                )
                Surface(
                    color = barColor,
                    shape = CircleShape,
                    modifier = Modifier.weight(1f).height(5.dp),
                ) {}
            }
        }
        Text(
            appText("Шаг ${step + 1} из $total · $title", "${step + 1}-се аҙым, барыһы $total · $title"),
            color = CanonMuted,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        )
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
    val bg by animateColorAsState(if (selected) CanonMint else CanonSurface, tween(CanonMotion.QUICK), label = "dtype-bg")
    val line by animateColorAsState(if (selected) CanonGreen2 else CanonBorder, tween(CanonMotion.QUICK), label = "dtype-line")
    // Толщина рамки тоже переезжает плавно: скачок 1→2dp читался как «дёрнулось».
    val lineWidth by animateDpAsState(if (selected) 2.dp else 1.dp, tween(CanonMotion.QUICK), label = "dtype-w")
    val iconBg by animateColorAsState(if (selected) CanonGreen2 else CanonMint, tween(CanonMotion.QUICK), label = "dtype-ic")
    Surface(
        onClick = onClick, color = bg, shape = CanonItemShape,
        border = BorderStroke(lineWidth, line),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 78.dp)
            .semantics(mergeDescendants = true) {
                role = Role.RadioButton
                this.selected = selected
            },
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = iconBg, shape = RoundedCornerShape(14.dp)) {
                Icon(icon, contentDescription = null, tint = if (selected) Color.White else CanonGreen2, modifier = Modifier.padding(8.dp).size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = CanonText, fontWeight = FontWeight.Bold, fontSize = DeliveryBody, lineHeight = DeliveryBodyLine)
                Text(subtitle, color = CanonMuted, fontSize = DeliveryCaption, lineHeight = DeliveryCaptionLine)
            }
            AnimatedVisibility(visible = selected, enter = scaleIn(tween(CanonMotion.QUICK)) + fadeIn(tween(CanonMotion.QUICK)), exit = fadeOut(tween(CanonMotion.QUICK))) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(start = 12.dp).size(24.dp))
            }
        }
    }
}

/** Чип срочности доставки. heightIn вместо height: башкирское «Юл ыңғайы · арзаныраҡ» длиннее
 *  русского, и при крупном системном шрифте вторая строка обрезалась жёсткой высотой. */
@Composable
private fun UrgencyChip(title: String, subtitle: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) CanonMint else CanonSurface, tween(CanonMotion.QUICK), label = "urg-bg")
    val line by animateColorAsState(if (selected) CanonGreen2 else CanonBorder, tween(CanonMotion.QUICK), label = "urg-line")
    val lineWidth by animateDpAsState(if (selected) 2.dp else 1.dp, tween(CanonMotion.QUICK), label = "urg-w")
    Surface(
        onClick = onClick,
        color = bg,
        shape = CanonItemShape,
        border = BorderStroke(lineWidth, line),
        modifier = modifier
            .heightIn(min = 64.dp)
            .semantics(mergeDescendants = true) {
                role = Role.RadioButton
                this.selected = selected
            },
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                title, color = if (selected) CanonGreen2 else CanonText,
                fontSize = DeliveryBody, lineHeight = DeliveryBodyLine, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle, color = CanonMuted, fontSize = DeliveryCaption, lineHeight = DeliveryCaptionLine,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Одна честная строка про ожидание — прямо под выбором способа доставки.
 *
 * Раньше экран говорил, ЧТО за способ, но молчал о том, сколько ждать: человек выбирал «по пути»
 * и не понимал, приедет сегодня или через неделю. Точное время обещать нельзя — попутчик может
 * и не найтись, — но объяснить, от чего ожидание зависит, можно и нужно.
 */
@Composable
private fun DeliveryWaitNote(deliveryType: String) {
    val text = when (deliveryType) {
        "courier" -> appText(
            "Курьер выезжает, как только возьмёт заказ.",
            "Курьер заказды алыу менән юлға сыға.",
        )
        "buy_bring" -> appText(
            "Курьер сам купит и выедет, как только возьмёт заказ.",
            "Курьер үҙе һатып алыр һәм заказды алғас юлға сығыр.",
        )
        else -> appText(
            "Везёт попутчик, который и так едет. Ждать можно день-другой — пока кто-нибудь не поедет в ту сторону.",
            "Юл ыңғайы бараған юлдаш илтә. Бер-ике көн көтөргә тура килеүе ихтимал — шул яҡҡа кемдер киткәнсе.",
        )
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Default.Schedule, contentDescription = null, tint = CanonMuted, modifier = Modifier.size(16.dp))
        // Способ щёлкают туда-сюда: подмена строки одним кадром читалась бы как сбой отрисовки.
        AnimatedContent(
            targetState = text,
            modifier = Modifier.weight(1f),
            transitionSpec = { fadeIn(tween(CanonMotion.QUICK)) togetherWith fadeOut(tween(CanonMotion.QUICK)) },
            label = "delivery-wait-note",
        ) { line -> DeliveryHint(line) }
    }
}

/**
 * Срок: к какому дню нужно. Не календарь-комбайн, а четыре ответа из жизни — «да когда
 * получится», «сегодня», «завтра», «к пятнице».
 *
 * По умолчанию «Не срочно» (пустая строка): большинству в селе спешить некуда, а навязанный
 * дедлайн отпугнёт курьеров, которым он не по пути. «Выбрать день» открывает системный календарь
 * с минимумом «сегодня» — тот же, что у водительских документов (openFutureDatePicker).
 */
@Composable
private fun ParcelDeadlinePicker(value: String, onValue: (String) -> Unit) {
    val ctx = LocalContext.current
    val lang = LocalAppLanguage.current
    val today = deliveryDateAhead(0)
    val tomorrow = deliveryDateAhead(1)
    // Черновик мог пролежать до ночи, и «сегодня» стало вчерашним. Просроченный срок отправлять
    // нельзя — тихо возвращаемся к «не срочно», не пугая человека ошибкой на пустом месте.
    LaunchedEffect(value, today) { if (value.isNotBlank() && value < today) onValue("") }
    val customDay = value.isNotBlank() && value != today && value != tomorrow
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            UrgencyChip(
                appText("Не срочно", "Ашығыс түгел"),
                appText("когда получится", "ҡасан булһа ла"),
                value.isBlank(), Modifier.weight(1f),
            ) { onValue("") }
            UrgencyChip(
                appText("Сегодня", "Бөгөн"),
                deliveryDateHuman(today),
                value == today, Modifier.weight(1f),
            ) { onValue(today) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            UrgencyChip(
                appText("Завтра", "Иртәгә"),
                deliveryDateHuman(tomorrow),
                value == tomorrow, Modifier.weight(1f),
            ) { onValue(tomorrow) }
            UrgencyChip(
                appText("Выбрать день", "Көн һайлау"),
                if (customDay) deliveryDateHuman(value) else appText("другой день", "башҡа көн"),
                customDay, Modifier.weight(1f),
            ) {
                openFutureDatePicker(ctx, if (lang == AppLanguage.Ba) "ba" else "ru") { onValue(it) }
            }
        }
        DeliveryHint(
            if (value.isBlank()) appText(
                "Без срока берут охотнее: курьер подстроит доставку под свою дорогу.",
                "Ваҡытһыҙ теләберәк алалар: курьер илтеүҙе үҙ юлына яраҡлаштыра.",
            ) else appText(
                "Курьер увидит этот день до того, как взяться, — и возьмётся, только если успевает.",
                "Курьер был көндө эшкә тотонғанға тиклем күрер һәм өлгөрһә генә алыр.",
            ),
        )
    }
}

/** Карточка оценки цены — честно показываем итог и наш сбор + разбивку. */
@Composable
private fun EstimateCard(est: CourierEstimateDto) {
    val commissionNote = if (est.breakdown.commissionEstimated) {
        appText(" (ориентировочно)", " (самаға)")
    } else {
        ""
    }
    Surface(color = CanonSurface, shape = CanonCardShape, border = BorderStroke(2.dp, CanonGreen2)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(appText("Доставка", "Илтеү"), color = CanonMuted, fontWeight = FontWeight.Bold, fontSize = DeliveryCaption, lineHeight = DeliveryCaptionLine)
            Text("≈ " + kopToRub(est.priceKop), color = CanonGreen, fontWeight = FontWeight.Bold, fontSize = DeliveryDisplay, lineHeight = DeliveryDisplayLine)
            Text(
                appText("Курьер получит ", "Курьер аласаҡ ") +
                    kopToRub(courierNetKop(est.priceKop, est.commissionKop)) +
                    appText(". Наша комиссия ", ". Беҙҙең комиссия ") +
                    kopToRub(est.commissionKop) + commissionNote + ".",
                color = CanonMuted, fontSize = DeliveryCaption, lineHeight = DeliveryCaptionLine,
            )
            Surface(color = CanonMint, shape = CanonItemShape) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    EstimateRow(appText("Подача", "Килеү"), kopToRub(est.breakdown.baseKop))
                    EstimateRow(appText("Расстояние", "Ара") + " (${deliveryDecimal(est.distanceKm, 0)} км)", kopToRub(est.breakdown.distanceKop))
                    EstimateRow(appText("Размер", "Ҙурлыҡ"), kopToRub(est.breakdown.sizeKop))
                    if (est.breakdown.urgencyKop > 0) {
                        EstimateRow(appText("Срочность", "Ашығыслыҡ"), kopToRub(est.breakdown.urgencyKop))
                    }
                }
            }
        }
    }
}

@Composable
private fun EstimateRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = CanonGreen2, fontSize = DeliveryCaption, lineHeight = DeliveryCaptionLine, modifier = Modifier.weight(1f))
        Text(value, color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = DeliveryCaption, lineHeight = DeliveryCaptionLine)
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

/**
 * Тип груза — одиночный выбор из шести чипов.
 *
 * Не поле для текста: «что внутри» отправитель уже пишет в описании своими словами, а курьеру
 * нужен ОДИН понятный признак, по которому решается «возьмусь или нет» — лекарства везут срочно,
 * технику осторожно, продукты не в жару. Чипы вместо списка, потому что вариантов мало и все
 * должны быть видны сразу, без разворачивания.
 *
 * FlowRow: башкирские подписи длиннее русских («Аҙыҡ-түлек»), и при крупном системном шрифте
 * жёсткий ряд из трёх колонок обрезал бы текст — здесь чипы просто переносятся на новую строку.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ParcelCargoTypePicker(value: String, onValue: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            appText("Что внутри", "Эсендә нимә"),
            color = CanonText, fontWeight = FontWeight.Bold,
            fontSize = DeliveryBody, lineHeight = DeliveryBodyLine,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().selectableGroup(),
        ) {
            PARCEL_CARGO_TYPES.forEach { code ->
                ParcelCargoChip(
                    label = cargoTypeEmoji(code) + "  " + cargoTypeLabel(code),
                    selected = value == code,
                    // Повторное нажатие снимает выбор. Поле необязательное, и «передумал»
                    // не должно требовать перезаполнения всей формы.
                    onClick = { onValue(if (value == code) "" else code) },
                )
            }
        }
        DeliveryHint(
            appText(
                "Необязательно. Нажми ещё раз, чтобы снять выбор.",
                "Мотлаҡ түгел. Һайлауҙы алып ташлар өсөн тағы бер тапҡыр баҫ.",
            ),
        )
    }
}

/** Один чип типа груза. Тач-цель 48dp, цвета переезжают плавно — как у остальных выборов формы. */
@Composable
private fun ParcelCargoChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) CanonMint else CanonSurface, tween(CanonMotion.QUICK), label = "cargo-bg")
    val line by animateColorAsState(if (selected) CanonGreen2 else CanonBorder, tween(CanonMotion.QUICK), label = "cargo-line")
    val lineWidth by animateDpAsState(if (selected) 2.dp else 1.dp, tween(CanonMotion.QUICK), label = "cargo-w")
    val ink by animateColorAsState(if (selected) CanonGreen2 else CanonText, tween(CanonMotion.QUICK), label = "cargo-ink")
    Surface(
        onClick = onClick, color = bg, shape = CanonItemShape,
        border = BorderStroke(lineWidth, line),
        modifier = Modifier
            .heightIn(min = 48.dp)
            .semantics(mergeDescendants = true) {
                role = Role.RadioButton
                this.selected = selected
            },
    ) {
        Box(Modifier.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            Text(
                label, color = ink, fontWeight = FontWeight.Bold,
                fontSize = DeliveryBody, lineHeight = DeliveryBodyLine,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * «Хрупкое» — переключатель, а не ещё один чип: это не «что везём», а обещание, как с посылкой
 * обойдутся. Подложка предупреждающая (CanonWarnBg/CanonWarn), чтобы включённое состояние
 * читалось как пометка на коробке, а не как обычная галочка в форме.
 */
@Composable
private fun ParcelFragileSwitch(checked: Boolean, onToggle: () -> Unit) {
    val bg by animateColorAsState(if (checked) CanonWarnBg else CanonSurface, tween(CanonMotion.QUICK), label = "fragile-bg")
    val line by animateColorAsState(if (checked) CanonWarn else CanonBorder, tween(CanonMotion.QUICK), label = "fragile-line")
    val lineWidth by animateDpAsState(if (checked) 2.dp else 1.dp, tween(CanonMotion.QUICK), label = "fragile-w")
    val ink by animateColorAsState(if (checked) CanonWarn else CanonMuted, tween(CanonMotion.QUICK), label = "fragile-ink")
    Surface(
        onClick = onToggle, color = bg, shape = CanonItemShape,
        border = BorderStroke(lineWidth, line),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics(mergeDescendants = true) {
                role = Role.Switch
                toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
            },
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.ReportProblem, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    appText("Хрупкое", "Ватыла торған"),
                    color = CanonText, fontWeight = FontWeight.Bold,
                    fontSize = DeliveryBody, lineHeight = DeliveryBodyLine,
                )
                Text(
                    appText(
                        "Курьер положит отдельно и не поставит под низ",
                        "Курьер айырым һалыр һәм аҫҡа ҡуймаҫ",
                    ),
                    color = CanonMuted, fontSize = DeliveryCaption, lineHeight = DeliveryCaptionLine,
                )
            }
            Spacer(Modifier.width(12.dp))
            // Ползунок зелёный, как все включённые тумблеры приложения: он говорит «признак
            // включён», а тревожный смысл несут подложка и рамка карточки.
            Switch(
                checked = checked,
                onCheckedChange = { onToggle() },
                colors = SwitchDefaults.colors(checkedTrackColor = CanonGreen2),
            )
        }
    }
}

@Composable
private fun ParcelSizeCard(size: String, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) CanonMint else CanonSurface, tween(CanonMotion.QUICK), label = "psize")
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
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = if (selected) CanonGreen2 else CanonMint, shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Default.Inventory2, contentDescription = null, tint = if (selected) Color.White else CanonGreen2, modifier = Modifier.padding(8.dp).size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(parcelSizeLabel(size), color = CanonText, fontWeight = FontWeight.Bold, fontSize = DeliveryBody, lineHeight = DeliveryBodyLine)
                Text(parcelSizeHint(size), color = CanonMuted, fontSize = DeliveryCaption, lineHeight = DeliveryCaptionLine)
            }
            // Галочка не выскакивает рывком: как в карточке типа доставки — вырастает с затуханием.
            AnimatedVisibility(visible = selected, enter = scaleIn(tween(CanonMotion.QUICK)) + fadeIn(tween(CanonMotion.QUICK)), exit = fadeOut(tween(CanonMotion.QUICK))) {
                Icon(Icons.Default.CheckCircle, contentDescription = appText("Выбрано", "Һайланды"), tint = CanonGreen2, modifier = Modifier.padding(start = 12.dp).size(24.dp))
            }
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
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Заливка квадратика переезжает плавно — иначе согласие «щёлкает» рывком.
            val boxBg by animateColorAsState(if (checked) CanonGreen2 else Color.Transparent, tween(CanonMotion.QUICK), label = "rules-box")
            val boxLine by animateColorAsState(if (checked) CanonGreen2 else CanonMuted, tween(CanonMotion.QUICK), label = "rules-line")
            Surface(
                color = boxBg,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(2.dp, boxLine),
                modifier = Modifier.size(24.dp),
            ) {
                AnimatedVisibility(visible = checked, enter = scaleIn(tween(CanonMotion.QUICK)) + fadeIn(tween(CanonMotion.QUICK)), exit = fadeOut(tween(CanonMotion.QUICK))) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color.White, modifier = Modifier.padding(4.dp))
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    appText("Подтверждаю правила доставки", "Илтеү ҡағиҙәләрен раҫлайым"),
                    color = CanonText, fontWeight = FontWeight.Bold,
                    fontSize = DeliveryBody, lineHeight = DeliveryBodyLine,
                )
                Text(
                    appText(
                        "Не отправляю запрещённое: деньги, документы на предъявителя, лекарства без рецепта, скоропорт, оружие.",
                        "Тыйылғанды ебәрмәйем: аҡса, күрһәтеүсегә документтар, рецептһыҙ дарыу, тиҙ боҙолған аҙыҡ, ҡорал.",
                    ),
                    color = CanonMuted, fontSize = DeliveryCaption, lineHeight = DeliveryCaptionLine,
                )
            }
        }
    }
}

// ─────────────────────────── Успех: код вручения ───────────────────────────

@Composable
private fun ParcelCreatedView(
    confirmCode: String,
    fromCity: String,
    toCity: String,
    receiverName: String,
    onDone: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    LazyColumn(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            // Печать «готово» вырастает после первого кадра — иначе экран успеха просто «появляется».
            // Ставить visible = true сразу нельзя: AnimatedVisibility тогда не проигрывает ничего.
            var sealShown by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { sealShown = true }
            AnimatedVisibility(visible = sealShown, enter = scaleIn(tween(420)) + fadeIn(tween(420))) {
                Surface(color = CanonMint, shape = CircleShape) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(16.dp).size(36.dp))
                }
            }
        }
        item {
            Text(appText("Посылка создана!", "Бандероль булдырылды!"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = DeliveryTitle, lineHeight = DeliveryTitleLine, textAlign = TextAlign.Center)
        }
        item {
            Text(appText("Как только попутчик её возьмёт — ты увидишь курьера и его телефон.", "Юлдаш уны алыу менән — курьерҙы һәм телефонын күрерһең."), color = CanonMuted, fontSize = DeliveryBody, lineHeight = DeliveryBodyLine, textAlign = TextAlign.Center)
        }
        // Крупный код вручения
        item {
            Surface(color = CanonSurface, shape = CanonCardShape, border = BorderStroke(2.dp, CanonGreen2)) {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(appText("Код вручения", "Тапшырыу коды"), color = CanonMuted, fontWeight = FontWeight.Bold, fontSize = DeliveryCaption, lineHeight = DeliveryCaptionLine)
                    Text(
                        confirmCode, color = CanonGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                        fontSize = DeliveryDisplay, lineHeight = DeliveryDisplayLine, textAlign = TextAlign.Center,
                    )
                    if (confirmCode.isNotBlank()) {
                        Surface(onClick = { clipboard.setText(AnnotatedString(confirmCode)) }, modifier = Modifier.minimumInteractiveComponentSize(), color = CanonMint, shape = RoundedCornerShape(14.dp)) {
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(appText("Скопировать", "Күсереп алыу"), color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = DeliveryBody, lineHeight = DeliveryBodyLine)
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
                    color = CanonGreen2, fontSize = DeliveryCaption, lineHeight = DeliveryCaptionLine, modifier = Modifier.padding(16.dp),
                )
            }
        }
        item {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ParcelRouteRow(fromCity, toCity)
                Text(appText("Получатель: ", "Алыусы: ") + receiverName, color = CanonMuted, fontSize = DeliveryCaption, lineHeight = DeliveryCaptionLine)
            }
        }
        item { AppButton(appText("Готово", "Әҙер"), onDone, style = AppButtonStyle.Primary) }
    }
}

// ─────────────────────────── Вкладка «Мои посылки» ───────────────────────────

@Composable
private fun MyParcelsTab(onGoSend: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var list by remember { mutableStateOf<List<ParcelDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var busyId by remember { mutableStateOf(0) }
    var cancelTarget by remember { mutableStateOf<ParcelDto?>(null) }
    var disputeTarget by remember { mutableStateOf<ParcelDto?>(null) }
    var rateTarget by remember { mutableStateOf<ParcelDto?>(null) }
    var receiptId by remember { mutableStateOf<Int?>(null) }   // чек открывается по номеру доставки
    var ratedIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var refreshing by remember { mutableStateOf(false) }
    val loadErr = appText("Не удалось загрузить посылки. Проверь интернет.", "Бандеролдәрҙе йөкләп булманы. Интернетты тикшер.")
    val actionErr = appText("Не получилось. Проверь сеть и повтори.", "Булманы. Селтәрҙе тикшереп ҡабатла.")
    val canceledMsg = appText("Посылка отменена", "Бандероль кире алынды")

    // Одна точка правды, как ответ сервера ложится на экран. Тихое обновление НЕ затирает уже
    // показанный список ошибкой: у отправителя в дороге связь рвётся, а карточка с кодом вручения
    // нужна ему прямо сейчас.
    fun applyParcels(res: Result<List<ParcelDto>>) {
        res.onSuccess { fresh -> list = fresh.sortedByDescending { p -> p.createdAt }; error = null }
            .onFailure { e ->
                if (list.isEmpty()) error = (e as? com.yuldash.app.data.ApiException)?.message ?: loadErr
            }
    }

    fun reload() {
        loading = true; error = null
        scope.launch {
            applyParcels(ApiClient.getMyParcels())
            loading = false
        }
    }
    LaunchedEffect(Unit) { reload() }

    // Авто-обновление списка отправителя. Раньше здесь было ровно одно разовое чтение при входе:
    // курьер брал посылку и выходил в путь, а отправитель об этом не узнавал, пока не переключит
    // вкладку туда-обратно. Теперь как у курьера — раз в 25 с, и с паузой, пока приложение в фоне
    // (repeatOnLifecycle RESUMED): не жжём батарею и трафик впустую.
    val lifecycleOwner = LocalLifecycleOwner.current
    var skipFirstAuto by remember { mutableStateOf(true) }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // Первый показ грузит reload() выше — не дублируем. Возврат из фона → сразу свежее.
            if (skipFirstAuto) skipFirstAuto = false else applyParcels(ApiClient.getMyParcels())
            while (true) {
                delay(MY_PARCELS_REFRESH_INTERVAL_MS)
                applyParcels(ApiClient.getMyParcels())
            }
        }
    }

    AppPullRefresh(
        refreshing = refreshing,
        onRefresh = {
            if (!refreshing) {
                refreshing = true
                scope.launch { applyParcels(ApiClient.getMyParcels()); refreshing = false }
            }
        },
    ) {
        LazyColumn(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
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
                        text = appText("Отправь первую — код появится здесь.",
                                       "Беренсеһен ебәр — код бында күренер."),
                        icon = Icons.Default.Inventory2,
                        // Раньше текст звал на вкладку «Отправить», а перейти туда было нечем:
                        // подсказка называла действие, но не давала его сделать.
                        actionLabel = appText("Отправить посылку", "Бандероль ебәреү"),
                        onAction = onGoSend,
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
                            onReceipt = { receiptId = list[i].id },
                        )
                    }
                }
            }
        }
    }

    cancelTarget?.let { target ->
        val courierAlreadyAssigned = target.status == "accepted" || target.status == "in_transit"
        AlertDialog(
            onDismissRequest = { cancelTarget = null },
            containerColor = CanonSurface,
            title = {
                Text(
                    appText("Отменить посылку?", "Бандеролде кире алаһыңмы?"),
                    color = CanonText, fontWeight = FontWeight.Bold,
                    fontSize = DeliveryTitle, lineHeight = DeliveryTitleLine,
                )
            },
            text = {
                Text(
                    if (courierAlreadyAssigned) {
                        // Сумму называем ДО решения. Раньше писали «сумма появится в карточке» —
                        // человек соглашался на деньги, не зная, на какие.
                        // С копейками: компенсацию человек отдаёт курьеру из рук в руки,
                        // и «100 ₽» вместо 100,80 ₽ — это спор у подъезда на ровном месте.
                        val feeText = kopToRub(target.cancelFeePreviewKop)
                        if (target.cancelFeePreviewKop > 0) appText(
                            "Курьер уже принял заказ. Отмена сейчас — компенсация курьеру $feeText " +
                                "за потраченное время и дорогу. Расчёт напрямую с курьером.",
                            "Курьер заказды алған инде. Хәҙер кире алһаң — курьерға ваҡыт һәм юл " +
                                "өсөн $feeText компенсация. Иҫәпләшеү курьер менән туранан-тура.",
                        ) else appText(
                            "Курьер уже принял заказ. После отмены сервис зафиксирует компенсацию за потраченное время и дорогу; сумма появится в карточке, расчёт — напрямую.",
                            "Курьер заказды алған инде. Кире алғандан һуң сервис ваҡыт һәм юл өсөн компенсацияны теркәр; сумма карточкала күренер, иҫәпләшеү — туранан-тура.",
                        )
                    } else appText(
                        "Посылка исчезнет из ленты курьеров. Это действие нельзя отменить.",
                        "Бандероль курьерҙар таҫмаһынан юғалыр. Был ғәмәлде кире ҡайтарып булмай.",
                    ),
                    color = CanonMuted,
                    fontSize = DeliveryBody,
                    lineHeight = DeliveryBodyLine,
                )
            },
            confirmButton = {
                TextButton(modifier = Modifier.heightIn(min = 48.dp), onClick = {
                    busyId = target.id
                    scope.launch {
                        ApiClient.cancelParcel(target.id)
                            .onSuccess { Toast.makeText(ctx, canceledMsg, Toast.LENGTH_SHORT).show(); reload() }
                            .onFailure { Toast.makeText(ctx, (it as? com.yuldash.app.data.ApiException)?.message ?: actionErr, Toast.LENGTH_SHORT).show() }
                        busyId = 0
                    }
                    cancelTarget = null
                }) { Text(appText("Отменить посылку", "Кире алыу"), color = CanonRed, fontWeight = FontWeight.Bold, fontSize = DeliveryBody) }
            },
            dismissButton = { TextButton(modifier = Modifier.heightIn(min = 48.dp), onClick = { cancelTarget = null }) { Text(appText("Оставить", "Ҡалдырыу"), color = CanonMuted, fontSize = DeliveryBody) } },
        )
    }

    disputeTarget?.let { target ->
        ParcelDisputeDialog(
            parcel = target,
            onDismiss = { disputeTarget = null },
            onOpened = { disputeTarget = null; reload() },
        )
    }

    ParcelReceiptDialog(receiptId) { receiptId = null }

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
private fun MyParcelCard(p: ParcelDto, busy: Boolean, rated: Boolean, onCancel: () -> Unit,
                         onDispute: () -> Unit, onRate: () -> Unit, onReceipt: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val terminal = isParcelTerminal(p.status)
    val goodsAlreadyBought =
        p.deliveryType == "buy_bring" && (p.settlement?.goodsActualKop ?: 0) > 0
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
                    Text(
                        parcelSizeLabel(p.size) + (if (p.description.isNotBlank()) "  ·  ${p.description}" else ""),
                        color = CanonMuted, fontSize = DeliveryCaption, lineHeight = DeliveryCaptionLine,
                    )
                }
                ParcelStatusChip(p.status)
            }
            // Тот, кто волнуется за посылку, до этого видел одно слово в маленьком чипе, а курьер —
            // полноценный таймлайн. Теперь шкала одна на двоих: «Забрать → В пути → Вручить»
            // (с ветками «Возврат/Возвращено»). У отменённой шкалы нет — первый шаг там врал бы.
            if (p.status != "canceled" && p.status != "cancelled") {
                CourierDeliveryProgress(status = p.status)
            }
            ParcelReturnNotice(status = p.status, reason = p.returnReason, forCourier = false)
            // Срок, который отправитель сам назвал. Если он вышел — человек должен узнать об этом
            // из приложения, а не догадываться, почему посылка всё ещё «в пути».
            ParcelDeadlineNote(
                deliverBy = p.deliverBy,
                overdue = p.overdue,
                status = p.status,
                forCourier = false,
            )
            // То, что отправитель сам написал курьеру. Спокойный вариант блока: ему не ехать
            // по этим ориентирам, ему нужно сверить — не перепутал ли он ворота.
            ParcelAddressBlock(fromAddress = p.fromAddress, toAddress = p.toAddress, prominent = false)
            // Снимки на границах ответственности — отправителю они нужны так же, как курьеру.
            ParcelPhotoStrip(pickupUrl = p.pickupPhotoUrl, deliveryUrl = p.deliveryPhotoUrl)
            if (p.cancelFeeKop > 0) {
                Surface(color = CanonWarnBg, shape = CanonItemShape) {
                    Text(
                        appText(
                            "Компенсация курьеру после отмены: ",
                            "Кире алғандан һуң курьерға компенсация: ",
                        ) + kopToRub(p.cancelFeeKop) + appText(
                            ". Рассчитайтесь напрямую.",
                            ". Туранан-тура иҫәпләшегеҙ.",
                        ),
                        color = CanonWarn,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Person, contentDescription = null, tint = CanonMuted, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                // weight отдан имени: длинное «Получатель: …» сжимается многоточием и НЕ
                // выталкивает сумму за экран (башкирская подпись длиннее русской).
                Text(
                    appText("Получатель: ", "Алыусы: ") + p.receiverName, color = CanonText,
                    fontSize = DeliveryCaption, lineHeight = DeliveryCaptionLine,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                // Цифра здесь — сколько отправитель платит курьеру. Раньше показывался наш
                // сервисный сбор, и человек читал его как цену доставки (аудит 2026-07-26).
                if (p.priceKop > 0) Text(kopToRub(p.priceKop), color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = DeliveryBody, lineHeight = DeliveryBodyLine)
            }
            p.courier?.let { cr ->
                val courierFallback = appText("Курьер", "Курьер")
                Surface(color = CanonMint, shape = CanonItemShape) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = CanonSurface, shape = CircleShape) {
                            Icon(Icons.Default.LocalShipping, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(8.dp).size(20.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                cr.name.ifBlank { courierFallback }, color = CanonText, fontWeight = FontWeight.Bold,
                                fontSize = DeliveryBody, lineHeight = DeliveryBodyLine,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val crRating = cr.rating
                                if (crRating != null && crRating > 0) {
                                    Icon(Icons.Default.Star, contentDescription = null, tint = CanonStar, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(deliveryDecimal(crRating) + (if (cr.ratingCount > 0) " · ${cr.ratingCount}" else ""), color = CanonMuted, fontSize = DeliveryCaption, lineHeight = DeliveryCaptionLine)
                                    Spacer(Modifier.width(8.dp))
                                } else {
                                    Text(appText("новый курьер", "яңы курьер"), color = CanonMuted, fontSize = DeliveryCaption, lineHeight = DeliveryCaptionLine)
                                    Spacer(Modifier.width(8.dp))
                                }
                                if (cr.phone.isNotBlank()) Text(cr.phone, color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = DeliveryCaption, lineHeight = DeliveryCaptionLine)
                            }
                        }
                    }
                }
                // «Оставь у соседей», «я на работе до шести» — на такое звонить незачем, а раньше
                // другого способа не было вовсе. Переписка ещё и остаётся, если дойдёт до спора.
                AppButton(
                    text = appText("Написать курьеру", "Курьерға яҙырға"),
                    onClick = {
                        DeepLink.pendingParcelChat.value =
                            ParcelChatTarget(p.id, peerIsCourier = true, status = p.status)
                    },
                    style = AppButtonStyle.Secondary,
                    icon = Icons.Default.ChatBubble,
                    height = 48.dp,
                )
            }
            // Existing privacy scope: live map is available only during the forward delivery.
            if (p.courier != null && (p.status == "accepted" || p.status == "in_transit")) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(appText("Курьер в пути — следи на карте", "Курьер юлда — картала күҙәт"), color = CanonMuted, fontSize = DeliveryCaption, lineHeight = DeliveryCaptionLine)
                    ParcelTrackMap(p, asCourier = false)
                }
            }
            if (!terminal) ParcelTrackLinkBlock(p.id)
            if (p.deliveryType == "buy_bring") {
                p.settlement?.let { ParcelSettlementBlock(it, forCourier = false) }
            }
            if (goodsAlreadyBought && !terminal) {
                Surface(color = CanonWarnBg, shape = CanonItemShape) {
                    Text(
                        appText(
                            "Курьер уже купил товар. Обычная отмена недоступна — если что-то пошло не так, открой спор.",
                            "Курьер тауарҙы һатып алған инде. Ғәҙәти кире алыу мөмкин түгел — проблема булһа, бәхәс ас.",
                        ),
                        color = CanonWarn,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                    )
                }
            }
            if (p.confirmCode.isNotBlank() && canCourierDeliverParcel(p.status)) {
                Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonGreen2)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(appText("Код вручения (передай получателю)", "Тапшырыу коды (алыусыға бир)"), color = CanonMuted, fontSize = DeliveryCaption, lineHeight = DeliveryCaptionLine)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                p.confirmCode, color = CanonGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                                fontSize = DeliveryDisplay, lineHeight = DeliveryDisplayLine, modifier = Modifier.weight(1f),
                            )
                            Surface(onClick = { clipboard.setText(AnnotatedString(p.confirmCode)) }, modifier = Modifier.minimumInteractiveComponentSize(), color = CanonMint, shape = RoundedCornerShape(14.dp)) {
                                Icon(Icons.Default.ContentCopy, contentDescription = appText("Скопировать код", "Кодты күсереп алыу"), tint = CanonGreen2, modifier = Modifier.padding(8.dp).size(20.dp))
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
                // Чек за доставку — такой же, как за попутку и за такси (аудит 2026-08-06).
                TextButton(onClick = onReceipt, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        appText("Квитанция за доставку", "Илтеү өсөн квитанция"),
                        color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    )
                }
            }
            if (p.courier != null && canOpenParcelDispute(p.status)) {
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
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            enabled = !busy,
        ) {
            Icon(Icons.Default.IosShare, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            // Пока ссылка создаётся на сервере, кнопка молчала и выглядела «не нажалась».
            // Теперь надпись меняется — и меняется плавно, а не подменяется кадром.
            AnimatedContent(
                targetState = busy,
                transitionSpec = { fadeIn(tween(CanonMotion.QUICK)) togetherWith fadeOut(tween(CanonMotion.QUICK)) },
                label = "tracklink-cta",
            ) { working ->
                Text(
                    if (working) appText("Готовим ссылку…", "Һылтанма әҙерләнә…")
                    else appText("Дать получателю ссылку для слежения", "Алыусыға күҙәтеү һылтанмаһы биреү"),
                    color = CanonGreen2, fontSize = DeliveryCaption, lineHeight = DeliveryCaptionLine, fontWeight = FontWeight.Bold,
                )
            }
        }
        DialogErrorLine(err)
        return
    }

    Surface(color = CanonMint, shape = CanonItemShape) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (smsSent) appText("Ссылка отправлена получателю по SMS", "Һылтанма алыусыға SMS менән ебәрелде")
                else appText("Ссылка готова — отправь её получателю", "Һылтанма әҙер — алыусыға ебәр"),
                color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = DeliveryBody, lineHeight = DeliveryBodyLine,
            )
            Text(url, color = CanonText, fontSize = DeliveryCaption, lineHeight = DeliveryCaptionLine)
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
                            ApiClient.revokeParcelTrackLink(parcelId)
                                .onSuccess { link = null; smsSent = false; err = null }
                                .onFailure { err = (it as? com.yuldash.app.data.ApiException)?.message ?: failMsg }
                            busy = false
                        }
                    },
                    style = AppButtonStyle.Secondary,
                    loading = busy,
                    fillWidth = false,
                    modifier = Modifier.weight(1f),
                )
            }
            DeliveryHint(
                appText(
                    "Ошиблись номером? Отзови ссылку — она сразу перестанет работать.",
                    "Номерҙа хата булдымы? Һылтанманы кире ал — ул шунда уҡ эшләмәй башлай.",
                ),
            )
            err?.let { Text(it, color = CanonRed, fontSize = 12.sp, lineHeight = 17.sp) }
        }
    }
}
