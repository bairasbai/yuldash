package com.yuldash.app

import androidx.compose.ui.res.painterResource
import android.graphics.PointF
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalTaxi
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Redeem
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Sos
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.yandex.mapkit.MapKitFactory
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.map.CameraPosition
import com.yandex.mapkit.map.IconStyle
import com.yandex.mapkit.mapview.MapView
import com.yandex.runtime.image.ImageProvider
import com.yuldash.app.data.Analytics
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.ApiException
import com.yuldash.app.data.GeocoderClient
import com.yuldash.app.data.InstantAlternativeDto
import com.yuldash.app.data.InstantEstimateDto
import com.yuldash.app.data.InstantOrderDto
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// ============================ «Быстрый заказ» (такси-режим, Фаза 2) ============================
// Отдельный поток от плановых поездок. Пассажир: «Куда едем?» → цена → «Ищем машину» → «Водитель едет».
// Водитель: presence-heartbeat + входящий оффер (таймер) → поездка (приехал/посадил/завершил).
// Приватность: телефон стороны сервер отдаёт только после accept; координаты не логируем.

// Города-опоры РБ для дефолтной камеры, если позиция ещё не определилась.
private val InstantDefaultPoint = Point(52.5980, 58.4419) // Баймак

/**
 * Yandex Point is not Bundle-saveable. Persist only latitude/longitude so an unfinished
 * taxi route survives Activity recreation without retaining any SDK object.
 */
private val InstantPointStateSaver = Saver<MutableState<Point?>, DoubleArray>(
    save = { state ->
        state.value?.let { point -> doubleArrayOf(point.latitude, point.longitude) }
            ?: doubleArrayOf()
    },
    restore = { saved ->
        mutableStateOf(
            saved.takeIf { it.size == 2 }?.let { Point(it[0], it[1]) },
        )
    },
)

// ───────────────────────── Типографика экрана: РОВНО четыре размера ─────────────────────────
// До этого раунда в файле их было двенадцать (11·12·13·14·15·16·18·19·22·26·30·34) — от этого
// экран читался как таблица, а не как ответ на вопрос «что сейчас происходит». Теперь у каждого
// размера одна роль, и доминанта на экране всегда одна: цена или статус.
// Локальная шкала выровнена по общей (CanonTokens.kt) 2026-08-03. Размеры подтянуты к ней,
// но главное — МЕЖСТРОЧНЫЕ: было 1.33 у тела и 1.38 у подписи, стало 1.44 и 1.43. Строки
// перестают слипаться, и экран читается спокойнее, хотя кегль вырос всего на единицу.
// Hero оставлен 28: это цена, у неё своя роль, и 34 из общей шкалы тут переполнит карточку.
private val TxHero = 34.sp        // ДОМИНАНТА: цена и статус. Ровно одна такая надпись на экран.
private val TxTitle = 19.sp       // заголовок экрана или карточки (= CanonHeading)
private val TxBody = 16.sp        // тело: адреса, имена, значения, надписи кнопок (= CanonBody)
private val TxCaption = 14.sp     // подпись: мета, пояснения, лейблы полей (= CanonCaption)
// Межстрочные — кратны 4dp, чтобы вертикальный ритм не плыл на длинном башкирском.
private val LhHero = 40.sp
private val LhTitle = 25.sp
private val LhBody = 23.sp
private val LhCaption = 20.sp

// Единая форма интерактивных элементов внутри карточек (поля, кнопки-строки, чипы).
// Отдельная от CanonCardShape(28)/CanonItemShape(22) — это «мелкая» ступень той же лестницы.
private val InstantControlShape = RoundedCornerShape(14.dp)

/**
 * Тон финальной карточки. До этого раунда крестик отмены был ЗЕЛЁНЫМ: цвет говорил «всё
 * хорошо», а надпись под ним — «заказ отменён». Человек читает цвет раньше текста, поэтому
 * они обязаны говорить одно и то же.
 */
private enum class InstantTone { Good, Bad }

/**
 * Полоска фаз заказа: «Ищем → Едет → На месте → В пути». Человек на морозе не читает —
 * он смотрит. Полоска отвечает «где я сейчас» за долю секунды и заполняется с анимацией,
 * поэтому смена фазы видна как движение вперёд, а не как подмена надписи.
 *
 * @param step 0 = ищем, 1 = водитель едет, 2 = машина на месте, 3 = в пути.
 */
@Composable
private fun InstantTripPhaseBar(step: Int, modifier: Modifier = Modifier) {
    val labels = listOf(
        appText("Ищем", "Эҙләйбеҙ"),
        appText("Едет", "Килә"),
        appText("На месте", "Урынында"),
        appText("В пути", "Юлда"),
    )
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        labels.forEachIndexed { i, label ->
            val passed = i <= step
            val segment by animateColorAsState(
                if (passed) CanonGreen2 else CanonBorder, tween(CanonMotion.SLOW), label = "phaseSeg$i",
            )
            val ink by animateColorAsState(
                if (i == step) CanonText else CanonMuted, tween(CanonMotion.SLOW), label = "phaseInk$i",
            )
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(segment))
                Spacer(Modifier.height(4.dp))
                Text(
                    label, color = ink, fontSize = TxCaption, lineHeight = LhCaption,
                    fontWeight = if (i == step) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                )
            }
        }
    }
}

// ------------------------------ Время сервера (UTC ISO) ------------------------------
/** Сервер обычно шлёт наивный UTC ISO, но принимаем также Z/offset и безопасно отвергаем мусор. */
private fun isoUtcToEpochMs(iso: String): Long? {
    val value = iso.trim()
    if (value.isEmpty()) return null
    return runCatching { java.time.Instant.parse(value).toEpochMilli() }.getOrNull()
        ?: runCatching { java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching {
            java.time.LocalDateTime.parse(value.removeSuffix("Z"))
                .toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        }.getOrNull()
}

/**
 * Остаток серверного окна оффера. null/пустая/битая дата = 0: в спорной ситуации заказ
 * не принимаем, потому что сервер мог уже отдать его другому водителю.
 */
internal fun instantOfferRemainingMillis(offerExpiresAt: String?, nowMillis: Long): Long {
    val expiresAt = offerExpiresAt?.let(::isoUtcToEpochMs) ?: return 0L
    if (expiresAt <= nowMillis) return 0L
    return expiresAt - nowMillis
}

/** Целые секунды для UI, округлённые вверх, чтобы «1 с» не исчезала раньше дедлайна. */
internal fun instantOfferSecondsLeft(offerExpiresAt: String?, nowMillis: Long): Int {
    val remaining = instantOfferRemainingMillis(offerExpiresAt, nowMillis)
    if (remaining <= 0L) return 0
    val seconds = remaining / 1_000L + if (remaining % 1_000L == 0L) 0L else 1L
    return seconds.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

/** Денежный формат такси хранит и показывает копейки без потери точности. */
internal fun formatTaxiKop(kop: Int): String {
    val safe = kop.coerceAtLeast(0)
    val rubles = safe / 100
    val coins = safe % 100
    return if (coins == 0) "$rubles ₽" else "$rubles,${coins.toString().padStart(2, '0')} ₽"
}

private fun formatTaxiMultiplier(value: Double): String =
    String.format(java.util.Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')

/** Прозрачная расшифровка серверной цены: клиент только показывает факторы и не считает цену. */
@Composable
private fun TaxiPricingBreakdown(estimate: InstantEstimateDto?) {
    if (estimate == null || estimate.pricingVersion != "v2") return
    Card(
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = CanonCardShape,
        border = BorderStroke(1.dp, CanonBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        appText("Цена рассчитана программой", "Хаҡ программа менән иҫәпләнде"),
                        color = CanonText, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    )
                    Text(
                        appText(
                            "База ${estimate.basePrice.takeIf { it > 0 } ?: estimate.price} ₽",
                            "Нигеҙ ${estimate.basePrice.takeIf { it > 0 } ?: estimate.price} ₽",
                        ),
                        color = CanonMuted, fontSize = 12.sp,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("${estimate.price} ₽", color = CanonGreen2, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    // Иначе рядом две разные суммы без объяснения: тут расчёт поездки, а промокод
                    // вычитается уже сверху — человек не должен гадать, какая цифра настоящая.
                    if (estimate.hasPromoDiscount) {
                        Text(
                            appText("до скидки по промокоду", "промокод ташламаһына тиклем"),
                            color = CanonMuted, fontSize = 12.sp,
                        )
                    }
                }
            }

            estimate.priceFactors.forEach { factor ->
                Row(verticalAlignment = Alignment.Top) {
                    Surface(shape = CircleShape, color = CanonTaxiBg, modifier = Modifier.size(9.dp)) {}
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            appText(
                                factor.titleRu.ifBlank { factor.code },
                                factor.titleBa.ifBlank { factor.titleRu.ifBlank { factor.code } },
                            ),
                            color = CanonText, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        )
                        Text(
                            appText(
                                factor.descriptionRu,
                                factor.descriptionBa.ifBlank { factor.descriptionRu },
                            ),
                            color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp,
                        )
                    }
                    val badge = when (factor.kind) {
                        "multiplier" -> "×${formatTaxiMultiplier(factor.k)}"
                        "cap" -> appText("лимит", "сик")
                        "notice" -> "!"
                        else -> appText("учтено", "иҫәптә")
                    }
                    Surface(shape = RoundedCornerShape(8.dp), color = CanonTaxiBg) {
                        Text(
                            badge, color = CanonTaxiText, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }

            Surface(shape = CanonItemShape, color = CanonTaxiBg) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        // §9: говорим по-человечески. «Коэффициент» и «сервер» — слова из кода,
                        // пассажиру они ничего не объясняют, а на экране про деньги это важно вдвойне.
                        if (estimate.dynamicK > 1.0) appText(
                            "Сейчас наценка ×${formatTaxiMultiplier(estimate.dynamicK)} · выше ×${formatTaxiMultiplier(estimate.pricingCapK)} не поднимаем",
                            "Хәҙер өҫтәмә ×${formatTaxiMultiplier(estimate.dynamicK)} · ×${formatTaxiMultiplier(estimate.pricingCapK)}-тән юғары күтәрмәйбеҙ",
                        ) else appText("Наценки сейчас нет", "Хәҙер өҫтәмә хаҡ юҡ"),
                        color = CanonTaxiText, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    )
                    Text(
                        appText(
                            "Цену считаем мы — по расстоянию, времени и спросу. Вручную её никто не накручивает.",
                            "Хаҡты беҙ иҫәпләйбеҙ — ара, ваҡыт һәм һорау буйынса. Уны ҡулдан берәү ҙә арттырмай.",
                        ),
                        color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp,
                    )
                }
            }
        }
    }
}

// ------------------------------ Промокод в такси: выгода и честность ------------------------------
/**
 * Выгода по промокоду ДО заказа: сколько было, сколько стало и кто платит разницу.
 *
 * Почему это отдельная карточка, а не строчка мелким шрифтом. Промокоды в Юлдаше были, но в
 * такси человек вводил код и не видел ни рубля выгоды — код выглядел бумажкой. Здесь ровно то,
 * ради чего он его вводил: «−150 ₽», старая цена зачёркнута, новая крупная.
 *
 * Скидки нет (или сервер старый) → на экране НИЧЕГО: ни «−0 ₽», ни пустой плашки.
 */
@Composable
private fun TaxiPromoSavingsCard(estimate: InstantEstimateDto?) {
    val est = estimate ?: return
    if (!est.hasPromoDiscount) return
    // Скидка бывает процентной, поэтому копейки у неё обычное дело. Рядом стоит цена
    // в формате с копейками — «−37 ₽» против «188,50 ₽» читалось бы как разные деньги.
    val savedText = formatTaxiKop(est.promoDiscountKop)
    var shown by remember(est.promoCode, est.promoDiscountKop) { mutableStateOf(false) }
    LaunchedEffect(est.promoCode, est.promoDiscountKop) { shown = true }
    AnimatedVisibility(
        visible = shown,
        enter = expandVertically(tween(CanonMotion.NORMAL)) + fadeIn(tween(CanonMotion.NORMAL)),
        exit = shrinkVertically(tween(CanonMotion.QUICK)) + fadeOut(tween(CanonMotion.QUICK)),
    ) {
        Surface(color = CanonMint, shape = CanonCardShape, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Redeem, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            appText("Промокод сработал", "Промокод эшләне"),
                            color = CanonGreen2, fontSize = TxBody, lineHeight = LhBody, fontWeight = FontWeight.Bold,
                        )
                        if (est.promoCode.isNotBlank()) {
                            Text(
                                appText("Код ${est.promoCode} · один раз", "Код ${est.promoCode} · бер тапҡыр"),
                                color = CanonGreen2, fontSize = TxCaption, lineHeight = LhCaption,
                            )
                        }
                    }
                    // Сама выгода одним числом. «Было → стало» повторять не нужно: обе цены
                    // стоят на 40dp выше, в блоке цены, — дважды одни и те же цифры только шумят.
                    Text(
                        "−$savedText",
                        color = CanonGreen2, fontSize = TxTitle, lineHeight = LhTitle, fontWeight = FontWeight.Bold,
                    )
                }
                // Подсказку пишет сервер (RU+BA) — она и объясняет, кто оплачивает скидку.
                // Сервер промолчал → говорим то же самое своими словами, а не оставляем пустоту.
                Text(
                    appText(est.promoNoteRu, est.promoNoteBa).ifBlank { taxiPromoHonestText() },
                    color = CanonGreen2, fontSize = TxCaption, lineHeight = LhCaption,
                )
            }
        }
    }
}

/**
 * Одна честная фраза про то, ЧЬИ это деньги. Пассажир платит водителю напрямую, платформа
 * денег за поездку не касается — поэтому скидку оплачивает Юлдаш из своей комиссии, а не
 * водитель из своего кармана. Без этой строки водитель, увидев меньшую сумму, решит, что его
 * обманули, а пассажир — что скидка «не считается».
 */
@Composable
private fun taxiPromoHonestText(): String = appText(
    "Скидку оплачивает Юлдаш из своей комиссии — водитель получит своё полностью.",
    "Ташламаны Юлдаш үҙ комиссияһынан түләй — йөрөтөүсе үҙенекен тулыһынса ала.",
)

/**
 * Сумма «на руки» в активном заказе, чеке и на экране водителя: сколько человек РЕАЛЬНО отдаёт.
 * Ошибка в этой цифре — спор с водителем на дороге, поэтому она одна и та же у обеих сторон.
 * Скидки нет → карточка не рисуется вовсе: обычному заказу лишняя плашка только мешает.
 */
@Composable
internal fun TaxiPromoPayRow(order: InstantOrderDto, forDriver: Boolean, modifier: Modifier = Modifier) {
    if (!order.hasPromoDiscount) return
    val savedText = formatTaxiKop(order.promoDiscountKop)
    Surface(color = CanonMint, shape = CanonItemShape, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Redeem, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (forDriver) appText(
                        "Пассажир отдаёт на руки ${formatTaxiKop(order.passengerPayKop)}",
                        "Пассажир ҡулға ${formatTaxiKop(order.passengerPayKop)} бирә",
                    ) else appText(
                        "К оплате водителю ${formatTaxiKop(order.passengerPayKop)}",
                        "Йөрөтөүсегә түләргә ${formatTaxiKop(order.passengerPayKop)}",
                    ),
                    color = CanonGreen2, fontSize = TxBody, lineHeight = LhBody, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    formatTaxiKop(order.fullPriceKop),
                    color = CanonMuted, fontSize = TxCaption, lineHeight = LhCaption,
                    textDecoration = TextDecoration.LineThrough,
                )
            }
            Text(
                if (forDriver) appText(
                    "Промокод −$savedText оплачивает Юлдаш: разницу берём из своей комиссии, не хватит — доплатим тебе в кошелёк. Ты получаешь столько же, как без промокода.",
                    "Промокод −$savedText хаҡын Юлдаш түләй: айырманы үҙ комиссиябыҙҙан алабыҙ, етмәһә — кеҫәңә өҫтәйбеҙ. Һин промокодһыҙҙағы кеүек үк алаһың.",
                ) else appText(
                    "Скидка по промокоду −$savedText. Её оплачивает Юлдаш — водитель получит своё полностью, спорить не о чем.",
                    "Промокод буйынса ташлама −$savedText. Уны Юлдаш түләй — йөрөтөүсе үҙенекен тулыһынса ала, бәхәсләшер нәмә юҡ.",
                ),
                color = CanonGreen2, fontSize = TxCaption, lineHeight = LhCaption,
            )
        }
    }
}

/**
 * Тикающее «сейчас» (раз в секунду) для живых таймеров ожидания.
 *
 * В фоне тикание останавливается: пересчитывать секунды для экрана, которого не видно, —
 * зря разбуженный процессор раз в секунду на всё время ожидания машины. При возврате время
 * берётся заново из системных часов, поэтому таймер сразу показывает правильное значение,
 * а не досчитывает пропущенное.
 */
@Composable
internal fun rememberNowMs(): State<Long> {
    val state = remember { mutableStateOf(System.currentTimeMillis()) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                state.value = System.currentTimeMillis()
                delay(1_000)
            }
        }
    }
    return state
}

/** Секунды → «M:SS». Часы не нужны: дольше двух часов мы цифру ожидания вообще не показываем. */
internal fun mmSs(sec: Long): String {
    val s = sec.coerceAtLeast(0)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

// ------------------------------ Гео: моя позиция ------------------------------
/** Моя позиция через LocationManager (как на «Карте»). active=false → не подписываемся (экономим батарею). */
@Composable
internal fun rememberMyPoint(active: Boolean = true): State<Point?> {
    val context = LocalContext.current
    val state = remember { mutableStateOf<Point?>(LocationPrefs.lastLat?.let { la -> LocationPrefs.lastLng?.let { lo -> Point(la, lo) } }) }
    DisposableEffect(active) {
        if (!active) return@DisposableEffect onDispose { }
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!granted) return@DisposableEffect onDispose { }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        val listener = object : android.location.LocationListener {
            override fun onLocationChanged(loc: android.location.Location) {
                state.value = Point(loc.latitude, loc.longitude)
                LocationPrefs.lastLat = loc.latitude; LocationPrefs.lastLng = loc.longitude
            }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
        }
        try {
            lm.requestLocationUpdates(android.location.LocationManager.GPS_PROVIDER, 3000L, 10f, listener)
            lm.requestLocationUpdates(android.location.LocationManager.NETWORK_PROVIDER, 3000L, 10f, listener)
            (lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER))?.let { state.value = Point(it.latitude, it.longitude) }
        } catch (e: SecurityException) {
        } catch (e: IllegalArgumentException) {
        }
        onDispose { runCatching { lm.removeUpdates(listener) } }
    }
    return state
}

// ------------------------------ Компактная карта маршрута A→B ------------------------------
/** Реальная Яндекс-карта: точка подачи (A) + назначения (B) + дорожный маршрут между ними.
 *  car/carBearing (B7a-3) — live-позиция машины (нав-стрелка, как у попутки): один placemark,
 *  двигаем geometry без пересоздания — плавно и без мигания.
 *  Локаль MapKit задаётся при первой карте приложения; тут только initialize (повторный setLocale бы упал). */
/** Навести камеру так, чтобы в кадр попали обе точки маршрута. Одна на создание и на кнопку. */
private fun fitRouteCamera(map: com.yandex.mapkit.map.Map, from: Point, to: Point) {
    runCatching {
        val bbox = com.yandex.mapkit.geometry.BoundingBox(
            Point(minOf(from.latitude, to.latitude), minOf(from.longitude, to.longitude)),
            Point(maxOf(from.latitude, to.latitude), maxOf(from.longitude, to.longitude)),
        )
        val fit = map.cameraPosition(com.yandex.mapkit.geometry.Geometry.fromBoundingBox(bbox))
        map.move(CameraPosition(fit.target, (fit.zoom - 0.6f).coerceIn(3f, 15f), 0f, 0f))
    }
}

@Composable
internal fun InstantRouteMap(
    from: Point?,
    to: Point?,
    modifier: Modifier = Modifier,
    car: Point? = null,
    carBearing: Double? = null,
    nearbyDrivers: List<com.yuldash.app.data.NearbyDriverDto> = emptyList(),
    // Счётчик «наведись на маршрут заново». Меняется — камера возвращается к А и Б.
    // Нужен кнопке возврата: увёл карту пальцем — машина пропадала из кадра насовсем.
    recenterTick: Int = 0,
) {
    val ctx = LocalContext.current
    // Цвет маршрута берём из токена темы, а не из константы: в тёмной теме CanonGreen2 светлее,
    // иначе линия сливается с тёмной картой. Альфа 0.8 = прежняя 0xCC.
    val routeArgb = CanonGreen2.copy(alpha = 0.8f).toArgb()
    // Подпись минут на машинке считаем ЗДЕСЬ: внутри DisposableEffect appText не позвать
    // (он @Composable), и раньше «мин» уезжало в бабл мимо двуязычия (аудит P1-6).
    val etaMinWord = appText("мин", "мин")
    // Ночной стиль карты. MapKit сам про тему приложения НЕ знает: без этой строки карта
    // остаётся светлой и на тёмном экране выглядит белым пятном во весь блок — поймано на
    // эмуляторе 2026-08-03. На вкладке «Попутка» ночной режим давно включён (MapScreen.kt),
    // а здесь про него забыли: у экрана заказа своя карта.
    val nightMap = appIsDark()
    val mapView = remember {
        ensureMapKit(ctx)   // русская локаль ставится тут же: голый initialize оставлял логотип «Yandex Maps» по-английски
        MapView(ctx).also { v ->
            val center = from ?: to ?: InstantDefaultPoint
            v.mapWindow.map.move(CameraPosition(center, 12f, 0f, 0f))
        }
    }
    // Отдельным эффектом, а не только при создании: тему переключают тумблером на ходу,
    // а mapView живёт в remember и заново не создаётся.
    LaunchedEffect(nightMap) { mapView.mapWindow.map.isNightModeEnabled = nightMap }
    DisposableEffect(Unit) {
        MapKitFactory.getInstance().onStart(); mapView.onStart()
        onDispose { mapView.onStop(); MapKitFactory.getInstance().onStop() }
    }
    // Перерисовываем маршрут при смене точек. Все объекты снимаем при следующей смене/уходе.
    DisposableEffect(from, to, routeArgb) {
        val map = mapView.mapWindow.map
        val added = mutableListOf<com.yandex.mapkit.map.MapObject>()
        var session: com.yandex.mapkit.directions.driving.DrivingSession? = null
        if (from != null) {
            added += map.mapObjects.addPlacemark(from).apply { setIcon(ImageProvider.fromBitmap(userPuckBitmap())) }
        }
        if (to != null) {
            added += map.mapObjects.addPlacemark(to).apply { setIcon(ImageProvider.fromBitmap(destFlagBitmap())) }
        }
        if (from != null && to != null) {
            val straight = map.mapObjects.addPolyline(com.yandex.mapkit.geometry.Polyline(listOf(from, to))).apply {
                setStrokeColor(routeArgb); strokeWidth = 4f
            }
            added += straight
            session = runCatching {
                val router = com.yandex.mapkit.directions.DirectionsFactory.getInstance()
                    .createDrivingRouter(com.yandex.mapkit.directions.driving.DrivingRouterType.COMBINED)
                val reqPoints = listOf(
                    com.yandex.mapkit.RequestPoint(from, com.yandex.mapkit.RequestPointType.WAYPOINT, null, null, null),
                    com.yandex.mapkit.RequestPoint(to, com.yandex.mapkit.RequestPointType.WAYPOINT, null, null, null),
                )
                router.requestRoutes(
                    reqPoints,
                    com.yandex.mapkit.directions.driving.DrivingOptions(),
                    com.yandex.mapkit.directions.driving.VehicleOptions(),
                    object : com.yandex.mapkit.directions.driving.DrivingSession.DrivingRouteListener {
                        override fun onDrivingRoutes(routes: MutableList<com.yandex.mapkit.directions.driving.DrivingRoute>) {
                            val r = routes.firstOrNull() ?: return
                            runCatching {
                                map.mapObjects.remove(straight)
                                added.remove(straight)
                                added += map.mapObjects.addPolyline(r.geometry).apply { setStrokeColor(routeArgb); strokeWidth = 5f }
                            }
                        }
                        override fun onDrivingRoutesError(error: com.yandex.runtime.Error) {}
                    }
                )
            }.getOrNull()
            fitRouteCamera(map, from, to)
        } else {
            (from ?: to)?.let { map.move(CameraPosition(it, 14f, 0f, 0f)) }
        }
        onDispose {
            runCatching { session?.cancel() }
            added.forEach { runCatching { map.mapObjects.remove(it) } }
        }
    }
    // Кнопка «вернуть карту»: возвращаем камеру к маршруту. Первый проход пропускаем —
    // при создании карта уже наведена, и повторное движение выглядело бы дёрганьем.
    LaunchedEffect(recenterTick) {
        if (recenterTick > 0) {
            val map = mapView.mapWindow.map
            if (from != null && to != null) fitRouteCamera(map, from, to)
            else (from ?: to)?.let { map.move(CameraPosition(it, 14f, 0f, 0f)) }
        }
    }

    // Маркер машины (live-трек, B7a-3): нав-стрелка (как стрелка попутчика), поворот по bearing.
    // Placemark один на жизнь карты — обновляем geometry/direction, не пересоздаём (без мигания).
    val carPm = remember { mutableStateOf<com.yandex.mapkit.map.PlacemarkMapObject?>(null) }
    LaunchedEffect(car, carBearing) {
        val map = mapView.mapWindow.map
        val point = car
        if (point == null) {
            carPm.value?.let { runCatching { it.isVisible = false } }
            return@LaunchedEffect
        }
        val pm = carPm.value ?: runCatching {
            map.mapObjects.addPlacemark(point).apply {
                setIcon(ImageProvider.fromBitmap(peerArrowBitmap()))
                setIconStyle(
                    com.yandex.mapkit.map.IconStyle()
                        .setAnchor(android.graphics.PointF(0.5f, 0.5f))
                        .setRotationType(com.yandex.mapkit.map.RotationType.ROTATE)
                )
            }
        }.getOrNull()?.also { carPm.value = it } ?: return@LaunchedEffect
        runCatching {
            pm.isVisible = true
            pm.geometry = point
            carBearing?.let { pm.direction = it.toFloat() }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            carPm.value?.let { runCatching { mapView.mapWindow.map.mapObjects.remove(it) } }
            carPm.value = null
        }
    }
    // «Честные машины рядом»: показываем ТОЛЬКО до выбора адреса (to == null), чтобы не мешать
    // маршруту. Каждая машинка — реальная точка из presence (без цены и без личности).
    //
    // Зум карты держим в состоянии: от него зависит, сливать ли соседние машины в один
    // кружок. Слушатель камеры срабатывает на каждый кадр движения, поэтому записываем
    // только заметные изменения — иначе перерисовка идёт на каждый пиксель жеста.
    var mapZoom by remember { mutableFloatStateOf(mapView.mapWindow.map.cameraPosition.zoom) }
    DisposableEffect(Unit) {
        val listener = com.yandex.mapkit.map.CameraListener { _, pos, _, _ ->
            if (kotlin.math.abs(pos.zoom - mapZoom) > 0.4f) mapZoom = pos.zoom
        }
        mapView.mapWindow.map.addCameraListener(listener)
        onDispose { runCatching { mapView.mapWindow.map.removeCameraListener(listener) } }
    }
    // Куда машины ехали в прошлый раз — чтобы новое положение не «телепортировалось».
    val prevCars = remember { mutableStateOf<List<Point>>(emptyList()) }
    val carShown = remember { mutableStateOf<List<Point>>(emptyList()) }
    // Класс кузова каждой машины — тем же порядком, что и точки: сопоставление по близости
    // сохраняет порядок целевого списка, поэтому индексы совпадают.
    val carCats = remember { mutableStateOf<List<String?>>(emptyList()) }
    LaunchedEffect(nearbyDrivers, to) {
        val target = if (to == null) nearbyDrivers.map { Point(it.lat, it.lng) } else emptyList()
        carCats.value = if (to == null) nearbyDrivers.map { it.category } else emptyList()
        val from = matchByProximity(prevCars.value, target)
        prevCars.value = target
        if (from.isEmpty() || target.isEmpty()) { carShown.value = target; return@LaunchedEffect }
        // Ход в 12 кадров: этого хватает, чтобы движение читалось глазом, и мало,
        // чтобы карта не работала анимацией вместо карты.
        val steps = 12
        for (i in 1..steps) {
            val k = i.toFloat() / steps
            carShown.value = target.mapIndexed { idx, t ->
                val f = from.getOrNull(idx) ?: t
                Point(f.latitude + (t.latitude - f.latitude) * k,
                      f.longitude + (t.longitude - f.longitude) * k)
            }
            kotlinx.coroutines.delay(60)
        }
        carShown.value = target
    }
    val тёмная = appIsDark()
    DisposableEffect(carShown.value, carCats.value, mapZoom, to, тёмная) {
        val map = mapView.mapWindow.map
        val carObjs = mutableListOf<com.yandex.mapkit.map.MapObject>()
        // Ячейка сетки в градусах: чем дальше камера, тем крупнее клетка и тем охотнее
        // соседние машины собираются в одну метку. На близком зуме клетка меньше дома,
        // и группировка не срабатывает вовсе — видно каждую машину отдельно.
        val cell = 0.6 / Math.pow(2.0, mapZoom.toDouble() - 4.0)
        val cats = carCats.value
        groupCars(carShown.value, cell).forEach { (center, члены) ->
            runCatching {
                carObjs += map.mapObjects.addPlacemark(center).apply {
                    // Одна машина — показываем её класс. Несколько — кружок с числом:
                    // спорить, чьей картинкой изображать группу из эконома и бизнеса, незачем.
                    setIcon(
                        if (члены.size > 1) ImageProvider.fromBitmap(carClusterBitmap(члены.size))
                        else ImageProvider.fromBitmap(
                            mapVehicleBitmap(ctx, mapVehicleIconFor(cats.getOrNull(члены[0]), тёмная)),
                        ),
                    )
                    setIconStyle(IconStyle().setAnchor(PointF(0.5f, 0.5f)))
                }
            }
        }
        onDispose { carObjs.forEach { runCatching { map.mapObjects.remove(it) } } }
    }
    AndroidView(factory = { mapView }, modifier = modifier)
}

/**
 * Сопоставить прошлые положения машин с новыми — по близости.
 *
 * У машин на карте НЕТ идентификатора: сервер отдаёт только координаты, чтобы по ним нельзя
 * было проследить путь конкретного водителя. Поэтому «та же самая машина» определяется
 * единственным доступным способом — какая из прошлых точек ближе всего к новой.
 *
 * Дальше километра не сопоставляем: это уже не та машина уехала, а другая появилась.
 * Ошибка здесь стоит дёшево — машины анонимны и взаимозаменяемы, а движение выглядит живым.
 */
internal fun matchByProximity(prev: List<Point>, target: List<Point>): List<Point> {
    if (prev.isEmpty() || target.isEmpty()) return emptyList()
    val free = prev.toMutableList()
    return target.map { t ->
        val best = free.minByOrNull { p ->
            val dLat = p.latitude - t.latitude
            val dLng = (p.longitude - t.longitude) * Math.cos(Math.toRadians(t.latitude))
            dLat * dLat + dLng * dLng
        }
        // ~0.01 градуса ≈ километр. Дальше — считаем, что машина новая, и ставим сразу на место.
        if (best != null && Math.abs(best.latitude - t.latitude) < 0.01 &&
            Math.abs(best.longitude - t.longitude) < 0.02
        ) {
            free.remove(best); best
        } else t
    }
}

/**
 * Собрать точки, попавшие в одну клетку сетки, в одну метку с числом.
 *
 * Восемь машин в квартале на отдалённой карте — каша, за которой не видно ни улиц, ни
 * маршрута. Клетка задаётся вызывающим по зуму: на близком масштабе она меньше дома
 * и ничего не склеивает, на дальнем — собирает целый район в один кружок.
 */
internal fun groupCars(points: List<Point>, cell: Double): List<Pair<Point, List<Int>>> {
    if (points.isEmpty()) return emptyList()
    if (cell <= 0.0) return points.mapIndexed { i, p -> p to listOf(i) }
    val buckets = LinkedHashMap<Pair<Long, Long>, MutableList<Int>>()
    points.forEachIndexed { i, p ->
        val key = Math.round(p.latitude / cell) to Math.round(p.longitude / cell)
        buckets.getOrPut(key) { mutableListOf() }.add(i)
    }
    return buckets.values.map { члены ->
        // Метка встаёт в середину своей группы, а не в угол клетки: иначе кружок
        // повисает на пустом месте рядом с машинами, которые он изображает.
        val lat = члены.sumOf { points[it].latitude } / члены.size
        val lng = члены.sumOf { points[it].longitude } / члены.size
        Point(lat, lng) to члены.toList()
    }
}

/** То же самое, но без состава группы — только точка и сколько машин в ней. */
internal fun groupPoints(points: List<Point>, cell: Double): List<Pair<Point, Int>> =
    groupCars(points, cell).map { (center, члены) -> center to члены.size }

// ==================================== ПАССАЖИР ====================================

/**
 * Потолок первой загрузки экрана такси. Сеть внутри повторяет попытки с паузами, и без общего
 * бюджета скелетон мог висеть больше минуты. Лучше через 12 секунд честно сказать «не получилось,
 * повторить», чем держать человека в вечной загрузке.
 */
private const val BOOT_BUDGET_MS = 12_000L

/**
 * «Быстрый заказ» пассажира. Один экран с внутренней машиной состояний по статусу заказа:
 * нет заказа → «Куда едем?» (A=моя позиция, B=поиск/карта, оценка цены) → создать →
 * searching «Ищем машину» → accepted/arriving/onboard «Водитель едет» → done/expired/cancelled.
 * При возврате на экран активный заказ восстанавливается (getMyInstantOrders).
 */
@Composable
internal fun InstantOrderScreen(
    onBack: () -> Unit,
    onLoginRequired: () -> Unit,
    embedded: Boolean = false,
    onTaxiOnboarding: () -> Unit = {},   // §11: из заглушки «Скоро» водитель может уйти в онбординг таксиста
    onOpenScheduled: () -> Unit = {},    // «На время»: предзаказ создан → «Мои предзаказы»
    // «Мои адреса»: дом, работа и свои места. Из шторки заказа туда ведёт постоянная строка —
    // без неё завести второй адрес неоткуда (решение Александра, Q2).
    onSavedPlaces: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val loggedIn = ApiClient.isLoggedIn()
    var order by remember { mutableStateOf<InstantOrderDto?>(null) }
    // Предзаказ «на время» создан → сохраняем только поля подтверждения. DTO в Bundle
    // не кладём, а минимальный receipt переживает recreation и не допускает повторный заказ.
    var scheduledConfirmId by rememberSaveable { mutableStateOf(0) }
    var scheduledConfirmAt by rememberSaveable { mutableStateOf<String?>(null) }
    var scheduledConfirmFrom by rememberSaveable { mutableStateOf("") }
    var scheduledConfirmTo by rememberSaveable { mutableStateOf("") }
    // ❄️ Зимний протокол в такси. Раньше он был только у попутки, хотя пассажир такси едет
    // те же четыре часа зимней трассы — и один с незнакомым водителем (аудит 2026-08-06).
    // Отсчёт ведём от момента, когда человек сел в машину: до посадки вопрос «доехал?»
    // бессмысленен, и сервер такой заход всё равно отклонит (too_early).
    val winterAsked = rememberSaveable { mutableStateOf(false) }
    val winterShow = rememberSaveable { mutableStateOf(false) }
    var onboardSeenAt by rememberSaveable { mutableStateOf(0L) }
    var checking by remember { mutableStateOf(loggedIn) }   // первичная загрузка: есть ли активный заказ
    // Гейт такси (волна 2): доступно ли такси в моей точке (глобальный флаг + города на сервере).
    // Сеть упала → фолбэк «доступно» (обычный пикер): сервер всё равно гейтит оценку и заказ.
    var availability by remember { mutableStateOf<com.yuldash.app.data.TaxiAvailabilityDto?>(null) }
    // Восстановление входа упало по сети → показываем retry вместо тихого падения в пикер (могли потерять живой заказ).
    var restoreError by remember { mutableStateOf(false) }

    // Запоминаем момент посадки один раз: DTO времени посадки не отдаёт, а таймеру нужна точка
    // отсчёта, переживающая поворот экрана.
    LaunchedEffect(order?.status) {
        if (order?.status == "onboard" && onboardSeenAt == 0L) {
            onboardSeenAt = System.currentTimeMillis()
        }
    }
    WinterArrivalWatcher(
        key = order?.id,
        startMs = { onboardSeenAt.takeIf { it > 0L } },
        active = { order?.status == "onboard" },
        asked = winterAsked,
        show = winterShow,
        onArm = { order?.id?.let { ApiClient.winterCheckOrder(it) } },
    )
    WinterArrivalDialog(winterShow) {
        val oid = order?.id
        if (oid != null) scope.launch { ApiClient.winterCheckOrderOk(oid) }
    }

    // Связь с сервером при поллинге активного заказа потеряна → мягкий баннер «пробуем ещё», не молчим.
    var pollOffline by remember { mutableStateOf(false) }
    var restoreTick by remember { mutableIntStateOf(0) }
    // Заказ, по которому человек уже нажал «Отменить» и мы спрашиваем причину. Держим id, а не
    // копию заказа: сумму подачи диалог возьмёт из живого заказа — пока человек читает, поллинг
    // успевает её обновить, а платить он будет по свежей.
    var cancelReasonForId by remember { mutableIntStateOf(0) }
    val ctx = LocalContext.current
    val cancelFailMsg = appText(
        "Не получилось отменить заказ. Проверь сеть и повтори.",
        "Заказды кире алып булманы. Селтәрҙе тикшереп ҡабатла.",
    )
    // Причина (long_wait|found_other|wrong_address|plans_changed|price|other) опциональна: пустая
    // строка = прежнее поведение. Раньше её не слали вообще, и в статистике было видно только
    // «отменил» — а «долго ждать» и «дорого» лечатся по-разному: первое матчингом, второе ценой.
    fun cancelOrder(id: Int, reason: String = "") {
        scope.launch {
            ApiClient.instantCancel(id, reason = reason)
                .onSuccess { order = it }
                .onFailure {
                    Toast.makeText(
                        ctx,
                        (it as? ApiException)?.message ?: cancelFailMsg,
                        Toast.LENGTH_SHORT,
                    ).show()
            }
        }
    }

    // Восстановление активного заказа при входе на экран + проверка доступности такси в точке.
    LaunchedEffect(restoreTick) {
        if (!loggedIn) { checking = false; return@LaunchedEffect }
        checking = true
        restoreError = false
        val lat = LocationPrefs.lastLat ?: InstantDefaultPoint.latitude
        val lng = LocationPrefs.lastLng ?: InstantDefaultPoint.longitude
        // Два независимых запроса — параллельно, а не друг за другом. Последовательно они складывали
        // свои ожидания: у каждого таймаут 15с и до трёх попыток с паузами, то есть экран мог честно
        // «грузиться» больше минуты, показывая всё это время скелетон.
        //
        // И общий бюджет на первую загрузку: что бы ни случилось с сетью, экран обязан выйти из
        // загрузки и сказать человеку правду. Вечная «загрузка» — это не состояние, это тупик:
        // человек на морозе не понимает, ждать ему или закрывать приложение.
        val restored = withTimeoutOrNull(BOOT_BUDGET_MS) {
            coroutineScope {
                // Доступность: сеть упала → фолбэк «доступно» (сервер всё равно гейтит).
                val availabilityJob = async { ApiClient.getTaxiAvailability(lat, lng) }
                // А вот список заказов важен: не загрузился — НЕ роняем в пикер молча, вдруг есть живой заказ.
                val ordersJob = async { ApiClient.getMyInstantOrders(limit = 5) }
                availabilityJob.await().onSuccess { availability = it }
                ordersJob.await()
            }
        }
        when {
            // Не уложились в бюджет — это тоже сбой связи, показываем ошибку с «Повторить».
            restored == null -> restoreError = true
            else -> restored
                // Предзаказы (scheduled) сюда не тянем — они живут в «Моих предзаказах», а не как активный заказ.
                // isWaitingQueue: заказ формально expired, но человек нажал «Подожду машину» —
                // воркер ещё ищет, и такой заказ надо восстановить как живой.
                .onSuccess { list -> order = list.firstOrNull { (!it.isTerminal || it.isWaitingQueue) && !it.isScheduled } }
                .onFailure { restoreError = true }
        }
        checking = false
    }

    // Поллинг статуса активного заказа (пока заказ есть и не терминальный или стоит в очереди ожидания).
    //
    // В фоне цикл СТОИТ (repeatOnLifecycle RESUMED). Раз в 3 секунды — самый частый опрос в
    // приложении, и свёрнутое ожидание машины (а сворачивают его всегда: человек листает другое,
    // пока едет такси) означало двести запросов за десять минут в никуда. О смене статуса на
    // погашенном экране сообщает пуш, экрану обновляться незачем — его не видно.
    //
    // Запрос идёт ПЕРЕД паузой: вернулся на экран — данные свежие сразу, а не через три секунды.
    val activeId = order?.takeIf { !it.isTerminal || it.isWaitingQueue }?.id
    val pollLifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(activeId, pollLifecycleOwner) {
        val id = activeId ?: return@LaunchedEffect
        pollLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                ApiClient.getInstantOrder(id)
                    .onSuccess { order = it; pollOffline = false }
                    .onFailure { pollOffline = true }   // связь потеряна — не глотаем, показываем баннер
                val o = order
                if (o != null && o.isTerminal && !o.isWaitingQueue) break
                delay(3_000)
            }
        }
    }

    // Встроенный режим (внутри переключателя Такси↔Попутка на главной): свою шапку не рисуем —
    // контекст задаёт сам переключатель. Самостоятельный экран (из кабинета) — с шапкой и «Назад».
    Scaffold(
        containerColor = CanonBg,
        topBar = { if (!embedded) ScreenTopBar(appText("Быстрый заказ", "Тиҙ заказ"), onBack) },
        // Встроенный режим: системные отступы уже учёл хаб над нами — второй раз их добавлять
        // нельзя, иначе под переключателем режимов зияет пустая полоса. Отдельный экран (из
        // кабинета) отступы сохраняет, иначе шапка залезет под статус-бар. Так же сделано
        // в «Попутке» — см. MapScreen.kt.
        contentWindowInsets = if (embedded) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets,
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val current = order
            val av = availability
            // Фаза заказа как ОДНО значение: и порядок веток тот же, что был, и есть чем кормить
            // AnimatedContent. Раньше `when` менял полэкрана мгновенно — человек, который стоит
            // на морозе, видел рывок вместо перехода «ищем → нашли».
            val phase = when {
                !loggedIn -> "login"
                checking -> "checking"
                scheduledConfirmId != 0 -> "scheduled"
                current == null && restoreError -> "restoreError"
                current == null && av?.enabled == false -> "comingSoon"
                current == null -> "picker"
                current.isSearching -> "searching"
                current.isActive -> "enroute"
                current.status == "expired" -> "expired"
                current.status == "cancelled" -> "cancelled"
                else -> "done"
            }
            // Пока человек едет, экран принадлежит поездке: переключатель сервисов и нижнее
            // меню прячутся (их читают HomeShell и PassengerModeHome).
            //
            // Сигнал НЕ гасим при уходе с экрана — специально. Человек может уйти в чат или
            // профиль, и поездка от этого не заканчивается: наоборот, именно тогда ему нужна
            // полоска «Ильдар едет · 3 мин» сверху. Гасит сигнал наблюдатель в HomeShell,
            // когда заказ действительно завершился.
            LaunchedEffect(phase, current?.id) {
                if (phase == "enroute") NavSignals.activeTaxiTrip.value = current?.id ?: 0
                else if (current == null || current.isTerminal) NavSignals.activeTaxiTrip.value = 0
            }
            // А вот «экран поездки прямо сейчас на виду» — состояние ровно этого экрана,
            // и оно обязано сбрасываться при уходе: иначе полоска не покажется никогда.
            DisposableEffect(phase) {
                if (phase == "enroute") NavSignals.taxiTripOnScreen.value = true
                onDispose { NavSignals.taxiTripOnScreen.value = false }
            }
            AnimatedContent(
                targetState = phase,
                // Спокойный кросс-фейд с еле заметным подъёмом: смена фазы читается как движение,
                // а не как подмена экрана. Уход быстрее прихода — так переход кажется отзывчивее.
                transitionSpec = {
                    (fadeIn(tween(CanonMotion.NORMAL)) + slideInVertically(tween(CanonMotion.SLOW)) { it / 20 })
                        .togetherWith(fadeOut(tween(CanonMotion.QUICK)))
                },
                label = "instantPhase",
            ) { p ->
                // Во время перехода недолго живут обе фазы, а данные уже новые — поэтому везде
                // безопасный `?.let`, без `!!`: уходящая карточка просто не рисуется.
                when (p) {
                    "login" -> InstantLoginNeeded(onLoginRequired)
                    "checking" -> InstantCheckingSkeleton()
                    // Предзаказ «на время» создан → спокойное подтверждение + путь в «Мои предзаказы».
                    "scheduled" -> InstantScheduledCreatedCard(
                        scheduledAt = scheduledConfirmAt,
                        fromText = scheduledConfirmFrom,
                        toText = scheduledConfirmTo,
                        onOpenScheduled = {
                            scheduledConfirmId = 0
                            scheduledConfirmAt = null
                            scheduledConfirmFrom = ""
                            scheduledConfirmTo = ""
                            onOpenScheduled()
                        },
                        onNewOrder = {
                            scheduledConfirmId = 0
                            scheduledConfirmAt = null
                            scheduledConfirmFrom = ""
                            scheduledConfirmTo = ""
                        },
                        onBack = onBack,
                    )
                    // Вход не загрузился по сети → не роняем в пикер молча (мог быть живой заказ), даём «Повторить».
                    "restoreError" -> InstantRetryCard(
                        onRetry = { restoreTick++ },
                        onBack = onBack,
                    )
                    // Гейт (a): такси выключено глобально или в этом городе → тёплая заглушка «Скоро».
                    // Активный заказ (если вдруг успел создаться до выключения) показываем как обычно.
                    "comingSoon" -> av?.let {
                        TaxiComingSoonCard(
                            availability = it,
                            onBackToPooling = onBack,
                            onTaxiOnboarding = onTaxiOnboarding,
                        )
                    }
                    "picker" -> InstantDestinationPicker(
                        onOrderCreated = { order = it },
                        onSavedPlaces = onSavedPlaces,
                        onScheduled = { scheduled ->
                            scheduledConfirmId = scheduled.id
                            scheduledConfirmAt = scheduled.scheduledAt
                            scheduledConfirmFrom = scheduled.fromText
                            scheduledConfirmTo = scheduled.toText
                        },
                    )
                    // Отмену не гасим сразу: сперва короткий вопрос «почему», и только потом запрос.
                    // В «Водитель едет» перед этим отрабатывает своё предупреждение о платной
                    // подаче — порядок «деньги → причина → отмена» специально не меняем.
                    "searching" -> current?.let { o ->
                        // Волна 160: поиск машины переехал на общую шторку — карта во весь
                        // рост с радаром вокруг точки подачи. Старая карточка
                        // (InstantSearchingCard) пока жива рядом, удалим после проверки.
                        TaxiSearchingScreen(
                            order = o,
                            onCancel = { cancelReasonForId = o.id },
                        )
                    }
                    "enroute" -> current?.let { o ->
                        // Волна 160: поездка переехала на собственный экран — карта во весь
                        // рост, шторка с тремя положениями. Старая карточка (InstantDriverEnRouteCard)
                        // пока жива рядом: удалим её, когда новый экран отходит на людях.
                        TaxiTripScreen(
                            order = o,
                            onCancel = { cancelReasonForId = o.id },
                        )
                    }
                    "expired" -> current?.let { o ->
                        InstantNoDriversCard(
                            order = o,
                            onRetry = { order = null },
                            onDone = onBack,
                            onOrderUpdated = { order = it },
                        )
                    }
                    "cancelled" -> current?.let { o ->
                        InstantFinalCard(
                            icon = Icons.Default.Close,
                            title = if (o.noShow) appText("Поездка не состоялась", "Сәфәр булманы")
                            else appText("Заказ отменён", "Заказ ҡабул ителмәне"),
                            // Честные тексты про штраф/страйки (Модель А: фиксируем, деньги не списываем).
                            subtitle = when {
                                // Сумму называем с копейками (formatTaxiKop): её человек переводит
                                // водителю из рук в руки, и «49 ₽» вместо 49,50 ₽ — это недоплата,
                                // которую потом разбирают двое незнакомых людей у обочины.
                                o.noShow -> appText(
                                    "Водитель ждал ${o.waitFreeMin}+ минут, но не дождался. Подача — ${formatTaxiKop(o.cancelFeeKop)}, переведи водителю. Частые такие отмены ставят такси на паузу.",
                                    "Йөрөтөүсе ${o.waitFreeMin}+ минут көттө, тик көтөп еткермәне. Килеү хаҡы — ${formatTaxiKop(o.cancelFeeKop)}, йөрөтөүсегә күсер. Йыш улай булһа — такси паузаға китә.")
                                o.cancelFeeKop > 0 && o.cancelBy == "passenger" -> appText(
                                    "Отмена была платной: ${formatTaxiKop(o.cancelFeeKop)} (подача) — переведи водителю. Частые платные отмены ставят такси на паузу.",
                                    "Кире алыу түләүле булды: ${formatTaxiKop(o.cancelFeeKop)} (килеү хаҡы) — йөрөтөүсегә күсер. Йыш түләүле кире алыуҙар таксиҙы паузаға ҡуя.")
                                o.cancelBy == "driver" -> appText("Водитель отменил. Попробуй заказать снова.", "Йөрөтөүсе баш тартты. Ҡабат заказ ит.")
                                else -> appText("Ты отменил заказ — бесплатно.", "Һин заказды кире алдың — бушлай.")
                            },
                            action = appText("Новый заказ", "Яңы заказ"),
                            onAction = { order = null },
                            onSecondary = onBack,
                            tone = InstantTone.Bad,   // отмена — красный кружок, не зелёный
                            // B8-8: телефон/чат уже открывались → мягко напоминаем завершать поездку в приложении.
                            extra = if (o.contactThenCancel) ({ ContactCancelSoftBanner() }) else null,
                        )
                    }
                    else -> current?.let { o ->   // done
                        InstantFinalCard(
                            icon = Icons.Default.CheckCircle,
                            title = appText("Поездка завершена", "Сәфәр тамамланды"),
                            // Цена — доминанта итога, маршрут под ней подписью: человек проверяет
                            // «сколько», а не перечитывает адреса. Со скидкой по промокоду тут
                            // стоит сумма, которую он реально отдал водителю, — иначе чек спорит
                            // с кошельком.
                            hero = formatTaxiKop(o.passengerPayKop),
                            subtitle = appText(
                                "${o.fromText.ifBlank { "Точка А" }} → ${o.toText.ifBlank { "Точка Б" }}",
                                "${o.fromText.ifBlank { "А нөктәһе" }} → ${o.toText.ifBlank { "Б нөктәһе" }}"),
                            action = appText("Новый заказ", "Яңы заказ"),
                            onAction = { order = null },
                            onSecondary = onBack,
                            extra = {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    // Скидка сработала → говорим, сколько сэкономлено и чьи это деньги.
                                    TaxiPromoPayRow(order = o, forDriver = false)
                                    // Онлайн-оплата завершённого такси-заказа (карта/СБП через ЮKassa, за флагом).
                                    // 503 (провайдер выключен) → карточка тихо исчезает на сессию (OnlinePayGate).
                                    // Платим ровно ту сумму, что человек и должен: со скидкой, а не полную.
                                    PayOnlineCard(
                                        amountKop = o.passengerPayKop.takeIf { it > 0 },
                                        pay = { m -> ApiClient.payInstantOrder(o.id, m) },
                                    )
                                    InstantRateAndReport(o, isDriver = false)   // §9: оценить/пожаловаться
                                    // Чек за поездку: справка на работу, «рәхмәт» водителю и «забыл вещь».
                                    TaxiReceiptLink(o.id)
                                    TaxiDisputeLink(o, isDriver = false)
                                }
                            },
                        )
                    }
                }
            }
            // Связь потеряна во время живого заказа: мягкий баннер сверху, поллинг сам возобновится.
            androidx.compose.animation.AnimatedVisibility(
                visible = pollOffline && current?.isTerminal == false,
                enter = fadeIn(), exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter),
            ) { InstantOfflineBanner() }
            // Вопрос «почему отменяешь» живёт СНАРУЖИ AnimatedContent: внутри он умирал бы
            // вместе с уходящей фазой прямо под пальцем.
            // Показываем, только пока заказ жив: если за это время его закрыл водитель, гасить
            // уже нечего — окно уходит само, вместо ошибки «заказ не найден» в ответ на касание.
            current?.takeIf { it.id == cancelReasonForId && !it.isTerminal }?.let { o ->
                InstantCancelReasonDialog(
                    feeKop = o.cancelFeeNowKop,
                    onPick = { code -> cancelReasonForId = 0; cancelOrder(o.id, code) },
                    onSkip = { cancelReasonForId = 0; cancelOrder(o.id) },
                    onDismiss = { cancelReasonForId = 0 },
                )
            }
        }
    }
}

@Composable
private fun InstantOfflineBanner() {
    Surface(
        color = CanonWarnBg,
        contentColor = CanonText,
        shape = CanonItemShape,
        shadowElevation = CanonDepth.raised,
        modifier = Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(strokeWidth = 2.dp, color = CanonWarn, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                appText("Связь потеряна — пробуем ещё…", "Бәйләнеш өҙөлдө — тағы тырышабыҙ…"),
                color = CanonWarn, fontSize = TxCaption, lineHeight = LhCaption, fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * Первая загрузка экрана: проверяем, нет ли живого заказа. Голый спиннер по центру не говорил
 * ничего — теперь показываем форму будущего контента (скелетон карты и карточек маршрута), и
 * появление реального экрана не выглядит скачком.
 */
@Composable
private fun InstantCheckingSkeleton() {
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SkeletonBox(widthFraction = 0.56f, height = 28.dp, shape = InstantControlShape)
        SkeletonBox(widthFraction = 0.84f, height = 16.dp, shape = InstantControlShape)
        Spacer(Modifier.height(4.dp))
        SkeletonBox(height = 192.dp, shape = CanonItemShape)
        SkeletonBox(height = 76.dp, shape = CanonItemShape)
        SkeletonBox(height = 120.dp, shape = CanonItemShape)
        Spacer(Modifier.height(4.dp))
        Text(
            appText("Проверяем, нет ли активного заказа…", "Әүҙем заказ бармы — тикшерәбеҙ…"),
            color = CanonMuted, fontSize = TxCaption, lineHeight = LhCaption,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
    }
}

// Вход не загрузился по сети: не роняем в пикер — предлагаем повторить (мог быть живой заказ).
@Composable
private fun InstantRetryCard(onRetry: () -> Unit, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(shape = CircleShape, color = CanonWarnBg, modifier = Modifier.appearIn(0)) {
            Icon(Icons.Default.CloudOff, contentDescription = null, tint = CanonWarn, modifier = Modifier.padding(16.dp).size(40.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(
            appText("Не удалось проверить заказ", "Заказды тикшереп булманы"),
            color = CanonText, fontWeight = FontWeight.Bold, fontSize = TxTitle, lineHeight = LhTitle,
            textAlign = TextAlign.Center, modifier = Modifier.appearIn(1),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            appText("Проверь интернет и попробуй ещё раз.", "Интернетты тикшереп, ҡабат ҡара."),
            color = CanonMuted, fontSize = TxBody, lineHeight = LhBody,
            textAlign = TextAlign.Center, modifier = Modifier.appearIn(2),
        )
        Spacer(Modifier.height(24.dp))
        Column(Modifier.appearIn(3), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AppButton(text = appText("Повторить", "Ҡабатлау"), onClick = onRetry, style = AppButtonStyle.Primary)
            AppButton(text = appText("Назад", "Артҡа"), onClick = onBack, style = AppButtonStyle.Secondary)
        }
    }
}

// ------------------------------ «Куда едем?» (пикер + оценка) ------------------------------

/** Русское склонение: «1 машина», «2 машины», «5 машин». Раньше всегда было «N машин рядом»,
 *  и при одной свободной машине бейдж читался как опечатка. В башкирском счётное слово
 *  остаётся в единственном числе — там менять нечего. */
/** Счётное слово к минутам: 1 минута, 2 минуты, 5 минут. В башкирском форма одна. */
private fun minutesWordRu(n: Int): String {
    val h = n % 100
    val t = n % 10
    return when {
        h in 11..14 -> "минут"
        t == 1 -> "минуту"
        t in 2..4 -> "минуты"
        else -> "минут"
    }
}

private fun carsWordRu(n: Int): String {
    val h = n % 100
    val t = n % 10
    return when {
        h in 11..14 -> "машин"
        t == 1 -> "машина"
        t in 2..4 -> "машины"
        else -> "машин"
    }
}

/**
 * Бейдж поверх карты: сколько РЕАЛЬНЫХ машин на линии рядом. Вынесен в отдельную функцию не
 * ради красоты — `AnimatedVisibility` внутри `Box`, когда выше по коду есть `Column`, не
 * компилируется (компилятор берёт ColumnScope-версию, а получателя нет).
 *
 * @param loaded пришёл ли успешный ответ. Пока нет — молчим: «рядом никого» без ответа сервера
 *               было бы выдумкой.
 * @param routeSet назвал ли человек, куда едет.
 *
 * Плохую новость («рядом машин нет») показываем ТОЛЬКО когда маршрут уже задан, то есть
 * человек в процессе заказа и новость ему полезна. До этого она встречала его первой строкой
 * на экране — сообщением о проблеме, которой ещё нет, и отбивала желание пробовать.
 * Хорошую новость («3 машины рядом») показываем всегда — она, наоборот, придаёт уверенности.
 */
@Composable
private fun InstantNearbyBadge(
    count: Int,
    loaded: Boolean,
    routeSet: Boolean,
    modifier: Modifier = Modifier,
    // Через сколько минут приедет ближайшая. 0 — не знаем (машин нет или ещё грузим).
    etaMin: Int = 0,
) {
    AnimatedVisibility(
        visible = loaded && (count > 0 || routeSet),
        enter = fadeIn(tween(CanonMotion.NORMAL)) + expandVertically(),
        exit = fadeOut(tween(CanonMotion.QUICK)) + shrinkVertically(),
        modifier = modifier,
    ) {
        Surface(color = CanonSurface, shape = CircleShape, shadowElevation = CanonDepth.card) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 4.dp).heightIn(min = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.LocalTaxi,
                    contentDescription = appText("Машины рядом", "Яҡындағы машиналар"),
                    tint = if (count > 0) CanonTaxi else CanonMuted, modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                // Отвечаем на вопрос «когда за мной приедут», а не «сколько машин вокруг».
                // Счётчик машин человеку ничего не решает: одна в двух минутах лучше пяти
                // в пятнадцати. Число оставляем только когда минут ещё не знаем.
                AnimatedContent(targetState = count to etaMin, label = "nearbyEta") { (n, eta) ->
                    Text(
                        when {
                            n > 0 && eta > 0 -> appText("Машина за $eta ${minutesWordRu(eta)}", "Машина $eta минутта")
                            n > 0 -> appText("$n ${carsWordRu(n)} рядом", "$n машина яҡында")
                            else -> appText("Рядом машин нет — поищем дальше", "Яҡында машина юҡ — арыраҡ ҡарайбыҙ")
                        },
                        color = if (n > 0) CanonText else CanonMuted,
                        fontSize = TxCaption, lineHeight = LhCaption, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Подсказки адреса — со ВСЕМИ состояниями, а не только с успехом. Раньше здесь был голый
 * `forEach`: пока геокодер думал, список был пуст, и «ещё ищем» ничем не отличалось от
 * «ничего не нашли» — человек в обоих случаях видел пустоту и не понимал, ждать ему или
 * переписывать адрес.
 */
@Composable
private fun InstantAddressResults(
    query: String,
    searching: Boolean,
    failed: Boolean,
    hits: List<com.yuldash.app.data.GeoHit>,
    onPick: (com.yuldash.app.data.GeoHit) -> Unit,
    onRetry: () -> Unit,
) {
    // Короче двух букв не ищем вообще (дебаунс в пикере) — значит и «не нашли» показывать не за что.
    val asked = query.trim().length >= 2
    val state = when {
        searching -> "loading"
        // «Не смогли спросить» ≠ «не нашли» (аудит P0-4). Человек в лифте вводил правильный
        // адрес, а мы уверенно отвечали «такого адреса нет» — теперь честно: нет связи, повторить.
        failed -> "error"
        asked && hits.isEmpty() -> "empty"
        hits.isEmpty() -> "idle"
        else -> "hits"
    }
    AnimatedContent(
        targetState = state,
        transitionSpec = { fadeIn(tween(CanonMotion.QUICK)).togetherWith(fadeOut(tween(CanonMotion.QUICK))) },
        label = "addrResults",
    ) { s ->
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (s) {
                // Форма будущего списка вместо пустоты: видно, что поиск идёт.
                "loading" -> repeat(3) { i ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        SkeletonBox(widthFraction = if (i == 2) 0.52f else 0.86f, height = 14.dp, shape = CircleShape)
                    }
                }
                "error" -> AppErrorState(
                    onRetry = onRetry,
                    title = appText("Не получилось поискать адрес", "Адресты эҙләп булманы"),
                    text = appText(
                        "Похоже, пропала связь — адрес ты ввёл верно. Проверь интернет и повтори.",
                        "Бәйләнеш өҙөлгән буғай — адресты дөрөҫ яҙғанһың. Интернетты тикшереп ҡабатла.",
                    ),
                )
                "empty" -> Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, tint = CanonMuted, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        appText("Такого адреса не нашли. Попробуй короче — «Ленина 12» — или выбери точку на карте.",
                            "Бындай адрес табылманы. Ҡыҫҡараҡ яҙып ҡара — «Ленина 12» — йәки картанан нөктә һайла."),
                        color = CanonMuted, fontSize = TxCaption, lineHeight = LhCaption,
                    )
                }
                "hits" -> hits.forEach { hit ->
                    Row(
                        Modifier.fillMaxWidth()
                            // heightIn до clickable: тач-цель = 48dp, а длинный адрес в две строки
                            // растягивает строку, а не обрезается.
                            .heightIn(min = 48.dp)
                            .clip(InstantControlShape)
                            .clickable { onPick(hit) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.LocationOn, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            hit.title, color = CanonText, fontSize = TxBody, lineHeight = LhBody,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InstantDestinationPicker(
    onOrderCreated: (InstantOrderDto) -> Unit,
    onScheduled: (InstantOrderDto) -> Unit = {},
    onSavedPlaces: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val myPoint by rememberMyPoint(active = true)

    // Подача: сейчас (null) или «на время» (epoch ms выбранного времени). ≤7 суток, не в прошлом.
    var scheduledAtMs by rememberSaveable { mutableStateOf<Long?>(null) }

    var fromPoint by rememberSaveable(saver = InstantPointStateSaver) { mutableStateOf<Point?>(null) }
    var fromText by rememberSaveable { mutableStateOf("") }
    // A по умолчанию = моя позиция (пока пользователь не выбрал вручную).
    var fromManual by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(myPoint) { if (!fromManual && myPoint != null) fromPoint = myPoint }
    val effFrom = fromPoint ?: myPoint

    var toPoint by rememberSaveable(saver = InstantPointStateSaver) { mutableStateOf<Point?>(null) }
    var toText by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<com.yuldash.app.data.GeoHit>>(emptyList()) }
    // Идёт ли сейчас поиск адреса. Без этого флага «ничего не нашли» мигало бы во время
    // дебаунса на каждой букве — а «пусто» обязано отличаться от «ещё ищем».
    var searchingAddr by remember { mutableStateOf(false) }
    // Поиск сорвался по сети — это третье состояние, не «не нашли» (аудит P0-4).
    var searchFailed by remember { mutableStateOf(false) }
    var searchTick by remember { mutableIntStateOf(0) }   // ручной «Повторить» после сбоя

    var estimate by remember { mutableStateOf<InstantEstimateDto?>(null) }
    var estimating by remember { mutableStateOf(false) }
    // Ручной повтор оценки цены после сетевой ошибки: раньше ошибка была тупиком —
    // красная строка без единой кнопки, и заказать было нельзя вообще.
    var estimateTick by remember { mutableIntStateOf(0) }
    var creating by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var category by rememberSaveable { mutableStateOf("standard") }   // standard | comfort | business | minivan
    // Класс, который человек хотел, но в его городе ещё не набралось водителей. Держим, чтобы
    // показать тёплое «записали» — и чтобы посчитать спрос до того, как искать машины.
    var classWanted by rememberSaveable { mutableStateOf<String?>(null) }
    // Что нужно в салоне: кресло по возрасту, бустер, коляска, собака-проводник, животное, багаж.
    // Храним CSV-строкой: Set в Bundle не кладётся, а строка переживает поворот экрана
    // и возврат с карты без единой строчки лишнего кода.
    var orderOptionsCsv by rememberSaveable { mutableStateOf("") }
    val orderOptions = remember(orderOptionsCsv) {
        orderOptionsCsv.split(",").filter { it.isNotBlank() }.toSet()
    }
    var pickOnMap by rememberSaveable { mutableStateOf(false) }   // оверлей выбора точки Б на карте
    var pickFromOnMap by rememberSaveable { mutableStateOf(false) }

    // Детали заказа (аудит 2026-07-26). Свёрнуты по умолчанию: 9 заказов из 10 — обычные,
    // лишние поля на главном пути только мешают.
    var detailsOpen by rememberSaveable { mutableStateOf(false) }
    var comment by rememberSaveable { mutableStateOf("") }     // «за магазином, синие ворота»
    var entrance by rememberSaveable { mutableStateOf("") }    // подъезд / квартира / этаж
    // «Только женщина за рулём». В попутках выбор был всегда, в такси его не было — хотя
    // ночью в машину к незнакомому человеку садятся именно здесь (аудит 2026-08-06).
    var womenOnly by rememberSaveable { mutableStateOf(false) }
    // Остановки по пути: «заедем за мамой», «в аптеку». Смысл в том, что за них платят —
    // иначе водитель везёт лишние километры даром, а пассажир не понимает, почему цена та же.
    var stops by remember { mutableStateOf<List<com.yuldash.app.data.TaxiStop>>(emptyList()) }
    var addingStop by remember { mutableStateOf(false) }
    // Круговой рейс: «отвези и привези обратно». Доступен только на межгороде — решает сервер.
    var roundTrip by rememberSaveable { mutableStateOf(false) }
    // Сколько водитель ждёт на месте. Час по умолчанию: типичное «сходить и вернуться».
    var returnWaitMin by rememberSaveable { mutableIntStateOf(60) }
    var forOther by rememberSaveable { mutableStateOf(false) } // заказ ДЛЯ ДРУГОГО человека
    var forName by rememberSaveable { mutableStateOf("") }
    var forPhone by rememberSaveable { mutableStateOf("") }

    // Сохранённые (Дом/Работа) + недавние: быстрый выбор адреса Б без повторного геокодинга.
    var savedPlaces by remember { mutableStateOf<List<com.yuldash.app.data.SavedPlaceDto>>(emptyList()) }
    var recentPlaces by remember { mutableStateOf<List<com.yuldash.app.data.RecentPlaceDto>>(emptyList()) }
    var placesReload by remember { mutableIntStateOf(0) }
    // Раньше обрабатывали только успех: при сбое блок «Дом/Работа/недавние» просто не рисовался,
    // и человек думал, что потерял свои адреса (аудит P1-8). Теперь сбой видно, и есть «Повторить».
    var placesFailed by remember { mutableStateOf(false) }
    LaunchedEffect(placesReload) {
        if (ApiClient.isLoggedIn()) {
            var failed = false
            ApiClient.getSavedPlaces().onSuccess { savedPlaces = it }.onFailure { failed = true }
            ApiClient.getRecentPlaces().onSuccess { recentPlaces = it }.onFailure { failed = true }
            placesFailed = failed
        }
    }
    // Быстрый выбор точки Б: подставляем адрес и координаты сразу (без сети).
    fun pickDestination(address: String, lat: Double, lng: Double) {
        toPoint = Point(lat, lng); toText = address; query = ""; suggestions = emptyList()
    }

    // Строки для колбэков (вне composition appText звать нельзя) — считаем заранее.
    val estimateFailMsg = appText("Не удалось оценить цену", "Хаҡты баһалап булманы")
    val createFailMsg = appText("Не удалось создать заказ. Повтори.", "Заказ булманы. Ҡабатла.")
    val myPosText = appText("Моя позиция", "Минең урын")
    val mapPointText = appText("Точка на карте", "Картала нөктә")
    // Пока геокодер отвечает. Показываем не пустоту и не «Точка на карте»: человек должен
    // видеть, что адрес сейчас появится, иначе решит, что так и останется.
    val resolvingText = appText("Определяем адрес…", "Адресты билдәләйбеҙ…")

    // Единый путь запроса (Permissions.kt): объясняем зачем → просим → если система больше
    // не спрашивает, ведём в настройки. Раньше был голый launch(): после двух отказов кнопка
    // «Включить» переставала делать что-либо вообще, и выхода из тупика не было (аудит P0-3).
    val askMyLocation = rememberPermissionGate(
        permission = Manifest.permission.ACCESS_FINE_LOCATION,
        titleRu = "Откуда тебя забрать?",
        titleBa = "Һине ҡайҙан алырға?",
        whyRu = "По геолокации мы сами подставим точку подачи и покажем свободные машины рядом — не придётся набирать свой адрес руками. Точку видит только водитель твоего заказа.",
        whyBa = "Геолокация буйынса килеү нөктәһен үҙебеҙ ҡуябыҙ һәм яҡындағы буш машиналарҙы күрһәтәбеҙ — адресты ҡулдан яҙырға кәрәкмәй. Нөктәне тик һинең заказ водителе генә күрә.",
        onGranted = { LocationPrefs.sharingEnabled = true },
    )
    val hasLocPerm = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    // Поиск адреса Б (дебаунс 350мс). searchTick — ручной повтор после сетевого сбоя.
    LaunchedEffect(query, searchTick) {
        if (query.trim().length < 2) {
            suggestions = emptyList(); searchingAddr = false; searchFailed = false
            return@LaunchedEffect
        }
        searchingAddr = true; searchFailed = false
        delay(350)
        GeocoderClient.suggestResult(query)
            .onSuccess { suggestions = it.take(6) }
            .onFailure { suggestions = emptyList(); searchFailed = true }
        searchingAddr = false
    }

    // Оценка цены, когда есть обе точки (и при смене класса — тариф другой).
    LaunchedEffect(effFrom, toPoint, category, estimateTick, roundTrip, returnWaitMin, stops) {
        val f = effFrom; val t = toPoint
        if (f == null || t == null) { estimate = null; return@LaunchedEffect }
        delay(350)   // дебаунс: позиция уточняется GPS-фиксами — не дёргаем /estimate на каждый
        estimating = true; errorText = null
        ApiClient.instantEstimate(f.latitude, f.longitude, t.latitude, t.longitude, fromText, toText, category,
            roundTrip = roundTrip, returnWaitMin = returnWaitMin, stops = stops)
            .onSuccess { estimate = it }
            .onFailure {
                estimate = null   // сбрасываем устаревшую цену → кнопка «Вызвать» гаснет, не заказываем по старой оценке
                errorText = (it as? ApiException)?.message ?: estimateFailMsg
            }
        estimating = false
    }

    // «Честные машины рядом»: реальные машины на линии у точки А (presence), раз в 15с.
    // Отвечают на вопрос «когда за мной приедут» — и до выбора адреса Б, и после него.
    //
    // Раньше опрос выключался, как только выбран адрес Б, а список обнулялся. Ноль машин
    // при выбранном маршруте попадал в ветку «рядом машин нет — поищем дальше», и человек
    // читал это ровно над кнопкой «Вызвать»: машина стоит в минуте езды, а приложение
    // отговаривает заказывать. На самом деле это было не «машин нет», а «я перестал
    // спрашивать» — разницу видно только из кода. На карте машины по-прежнему прячутся
    // при выбранном маршруте (см. блок отрисовки), там их место занимает сам маршрут.
    var nearbyDrivers by remember { mutableStateOf<List<com.yuldash.app.data.NearbyDriverDto>>(emptyList()) }
    // Пришёл ли хоть один УСПЕШНЫЙ ответ. Без этого «машин рядом нет» и «ещё не спросили»
    // выглядят одинаково — пустым местом, и мы молча врём человеку, что рядом пусто.
    var nearbyLoaded by remember { mutableStateOf(false) }
    // Пока экран перед глазами — переспрашиваем, какие машины рядом. Свернул приложение →
    // опрос засыпает: искать машины для человека, который сейчас не выбирает поездку, незачем.
    RepeatWhileVisible(effFrom) {
        val f = effFrom ?: return@RepeatWhileVisible
        var fails = 0
        while (isActive) {
            ApiClient.getNearbyDrivers(f.latitude, f.longitude)
                .onSuccess { nearbyDrivers = it; nearbyLoaded = true; fails = 0 }
                .onFailure { fails = (fails + 1).coerceAtMost(4) }
            // В подвале и в дороге сеть пропадает надолго. Раньше экран всё равно стучался
            // каждые 15 секунд — впустую жёг батарею и квоту. Теперь после сбоя пауза растёт
            // 15с → 30 → 60 → 120 → 4 мин, а первый же успешный ответ возвращает обычные 15с.
            delay(15_000L shl fails)
        }
    }

    // Шторка над картой — тот же язык, что у экрана поездки (TaxiSheet.kt).
    //
    // Было: одна простыня из семнадцати блоков, и до кнопки «Вызвать» человек прокручивал
    // пять экранов. Заказ такси читался как список настроек, а не как «поехали».
    //
    // Стало: карта во весь рост; свёрнутая шторка показывает откуда и куда, средняя — тарифы,
    // цену и кнопку, полная — всё остальное. Пока маршрут не задан, шторка открыта: там поле
    // адреса и быстрый выбор, ради которых человек сюда и пришёл.
    // Счётчик «наведи камеру заново». Растёт по кнопке возврата; сама карта по нему решает,
    // показать маршрут целиком или вернуться к одной точке.
    var mapRecenterTick by remember { mutableIntStateOf(0) }
    var sheetStop by remember { mutableStateOf(TaxiSheetStop.Full) }
    var sheetTouched by remember { mutableStateOf(false) }
    // Выбрал адрес — сама съезжает к тарифам и кнопке. Пока он их не трогал руками.
    LaunchedEffect(toPoint != null) {
        if (!sheetTouched) sheetStop = if (toPoint == null) TaxiSheetStop.Full else TaxiSheetStop.Half
    }

    TaxiSheetScaffold(
        stop = sheetStop,
        onStopChange = { sheetTouched = true; sheetStop = it },
        map = { m ->
            // Карта с РЕАЛЬНЫМИ машинами рядом (честно, без выдуманной цены): видно, что
            // помощь близко. Машинки — из presence, ≈ETA до подачи.
            Box(m) {
                InstantRouteMap(
                    from = effFrom,
                    to = toPoint,
                    nearbyDrivers = nearbyDrivers,
                    recenterTick = mapRecenterTick,
                    modifier = Modifier.fillMaxSize(),
                )
                InstantNearbyBadge(
                    count = nearbyDrivers.size,
                    loaded = nearbyLoaded,
                    routeSet = toPoint != null,
                    // Ближайшая из тех, кто на линии. Не среднее и не «примерно»: человек
                    // планирует по самому раннему сроку, а не по среднему по больнице.
                    etaMin = nearbyDrivers.minOfOrNull { it.etaMin } ?: 0,
                    modifier = Modifier.align(Alignment.TopStart).padding(CanonSpace.md),
                )
                // Вернуть карту. Одна кнопка на два случая: маршрут не задан — ведёт к тебе,
                // задан — показывает всю поездку целиком. Механизм внутри карты сам разбирает,
                // какой случай; кнопке остаётся его позвать.
                val hasRoute = toPoint != null
                Surface(
                    onClick = {
                        // Ровно как в попутке: выключена — включаем (спросив разрешение),
                        // включена — ведём карту. Выключить кнопкой нельзя намеренно:
                        // её жмут, чтобы найти себя, а не чтобы потеряться. Выключение —
                        // в Профиль → Конфиденциальность, где оно осознанное.
                        when {
                            LocationPrefs.sharingEnabled -> mapRecenterTick++
                            hasLocPerm -> LocationPrefs.sharingEnabled = true
                            else -> askMyLocation()
                        }
                    },
                    shape = CircleShape,
                    color = CanonSurface,
                    shadowElevation = CanonDepth.raised,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(CanonSpace.md)
                        .size(48.dp),   // тач-цель 48dp (a11y §4.5)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (hasRoute) Icons.Default.ZoomOutMap else Icons.Default.NearMe,
                            contentDescription = if (hasRoute)
                                appText("Показать весь путь", "Бөтә юлды күрһәтеү")
                            else appText("Где я", "Мин ҡайҙа"),
                            tint = CanonGreen2,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        },
        header = {
            // Обе точки — в ОДНОЙ карточке. Маршрут это одна вещь, «откуда и куда»; двумя
            // отдельными плашками с зазором он читался как два независимых вопроса.
            // Строки разделяет черта, начинающаяся под текстом, а не от края: приём списков,
            // он и говорит, что строки принадлежат одному объекту.
            Surface(color = CanonBg, shape = CanonItemShape, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth()) {
                    OrderPointRow(
                        icon = Icons.Default.MyLocation,
                        tint = CanonGreen2,
                        text = when {
                            fromManual && fromText.isNotBlank() -> fromText
                            effFrom != null -> appText("Моя позиция", "Минең урын")
                            hasLocPerm -> appText("Определяем…", "Билдәләйбеҙ…")
                            else -> appText("Включи геолокацию", "Геолокацияны ҡабыҙ")
                        },
                        actionLabel = if (!hasLocPerm && !fromManual) appText("Включить", "Ҡабыҙ")
                                      else appText("На карте", "Картала"),
                        onAction = { if (!hasLocPerm && !fromManual) askMyLocation() else pickFromOnMap = true },
                    )
                    // Отступ слева равен ширине иконки с полями: черта идёт под текстом,
                    // а не под точкой маршрута — иначе она разрезала бы сам маршрут пополам.
                    Box(
                        Modifier
                            .padding(start = 50.dp)
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(CanonBorder),
                    )
                    // Остановки по пути — между «откуда» и «куда», в порядке заезда.
                    // Раньше они жили отдельным блоком ниже по списку, и маршрут читался
                    // разорванным: две точки сверху, а то, что между ними, — где-то там.
                    stops.forEachIndexed { i, stop ->
                        OrderStopRow(
                            text = stop.text,
                            onRemove = { stops = stops.filterIndexed { j, _ -> j != i } },
                        )
                        Box(
                            Modifier
                                .padding(start = 50.dp)
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(CanonBorder),
                        )
                    }
                    if (toPoint != null) {
                        OrderDestinationRow(
                            text = toText.ifBlank { appText("Точка на карте", "Картала нөктә") },
                            // Время в пути — подписью под адресом. Сервер его считает всегда,
                            // а видно его было только в раскрытых деталях заказа: человек знал
                            // цену и не знал, во сколько будет на месте.
                            tripMin = estimate?.etaMin?.toInt()?.takeIf { it > 0 },
                            canAddStop = stops.size < InstantStopsMax,
                            onAddStop = { addingStop = true },
                            onEdit = { toPoint = null; query = ""; estimate = null },
                        )
                    } else {
                        // Поле «куда» — в шапке, а не в прокрутке. Шапка видна в любом положении
                        // шторки, и свёрнутая шторка остаётся рабочей: карта во весь рост, а задать
                        // адрес по-прежнему можно, не разворачивая список.
                        InstantWhereField(
                            value = query,
                            onValueChange = { q ->
                                query = q
                                if (q.isBlank()) { toPoint = null; estimate = null }
                                // Начал печатать — раскрываем: подсказки живут ниже, и в свёрнутом
                                // виде человек писал бы вслепую.
                                else if (sheetStop != TaxiSheetStop.Full) { sheetTouched = true; sheetStop = TaxiSheetStop.Full }
                            },
                            onMap = { pickOnMap = true },
                        )
                    }
                }
            }
        },
        body = {
            if (toPoint == null) {
                // Быстрый выбор: Дом/Работа + недавние (до ввода адреса). Тап подставляет адрес и координаты сразу.
                // Поле «куда» — ПЕРВЫМ, список сохранённых адресов под ним. Раньше было
                // наоборот: человек, у которого нужного адреса в списке нет (а это каждый раз,
                // когда едешь в новое место), сначала просматривал чужие строки и только потом
                // добирался до строки ввода. Главное действие экрана не может стоять вторым.
                // Само поле уехало в шапку (видно в любом положении шторки), здесь остаются
                // только подсказки: их бывает шесть, и место им в прокрутке.
                InstantAddressResults(
                    query = query,
                    searching = searchingAddr,
                    failed = searchFailed,
                    hits = suggestions,
                    onPick = { hit ->
                        toPoint = Point(hit.lat, hit.lon); toText = hit.title; query = ""; suggestions = emptyList()
                    },
                    onRetry = { searchTick++ },
                )
                if (toPoint == null) {
                    // Ничего не загрузилось и была ошибка → честно говорим об этом. Загрузилась хотя бы
                    // часть — показываем её и не пугаем: адреса на месте, просто связь моргнула.
                    if (placesFailed && savedPlaces.isEmpty() && recentPlaces.isEmpty()) {
                        AppErrorState(
                            onRetry = { placesReload++ },
                            title = appText("Не удалось загрузить твои адреса", "Адрестарыңды йөкләп булманы"),
                            text = appText(
                                "Дом, работа и недавние поездки никуда не делись — просто пропала связь.",
                                "Өй, эш һәм һуңғы сәфәрҙәр юғалманы — тик бәйләнеш өҙөлдө.",
                            ),
                        )
                    } else {
                        QuickPlacesBlock(
                            saved = savedPlaces,
                            recent = recentPlaces,
                            onPick = { address, lat, lng -> pickDestination(address, lat, lng) },
                            // Убираем строку сразу, запрос летит следом. Жест, после которого
                            // полсекунды ничего не происходит, читается как «не сработало».
                            // Сервер отказал → перечитываем список, и адрес возвращается.
                            onDeleteRecent = { id ->
                                recentPlaces = recentPlaces.filterNot { it.id == id }
                                scope.launch {
                                    ApiClient.deleteRecentPlace(id).onFailure { placesReload++ }
                                }
                            },
                            onOpenSavedPlaces = onSavedPlaces,
                            // Убрали именованное место — список перечитываем с сервера:
                            // порядок там свой, локально его не воспроизвести честно.
                            onDeleteSaved = { place ->
                                savedPlaces = savedPlaces.filterNot { it.id == place.id }
                                scope.launch {
                                    ApiClient.deleteSavedPlace(place.id).onFailure { placesReload++ }
                                }
                            },
                        )
                    }
                }
            } else {
                // Класс машины: Эконом / Комфорт / Бизнес / Минивэн — все цены сразу, выбранная уходит
                // в заказ. Класс, в котором в этом городе ещё не набралось водителей, приходит с
                // open=false: показываем «скоро» вместо кнопки, за которой пусто. Ткнуть в пустоту и
                // не дождаться — верный способ потерять человека навсегда.
                if (toPoint != null) {
                    val opts = estimate?.options.orEmpty().associateBy { it.category }
                    LazyRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(CanonSpace.sm),
                        contentPadding = PaddingValues(horizontal = 2.dp),
                    ) {
                        items(InstantClasses, key = { it.category }) { cls ->
                            val opt = opts[cls.category]
                            val isOpen = opt?.open ?: true
                            InstantClassCard(
                                title = appText(cls.titleRu, cls.titleBa),
                                subtitle = if (isOpen) appText(cls.subtitleRu, cls.subtitleBa)
                                else appText("скоро", "тиҙҙән"),
                                price = opt?.price,
                                selected = isOpen && category == cls.category,
                                enabled = isOpen,
                                iconRes = cls.iconRes,
                                iconNightRes = cls.iconNightRes,
                                // Минуты — только для открытого класса и только когда машины
                                // этого класса реально рядом: иначе цифра станет обещанием.
                                pickupEtaMin = if (isOpen) opt?.pickupEtaMin else null,
                                onClick = {
                                    if (isOpen) {
                                        category = cls.category
                                    } else {
                                        // Нажатие на закрытый класс — это заявка спроса. Считаем её:
                                        // так видно, сколько людей ждут Бизнес в Уфе, ещё до того,
                                        // как мы начнём искать под него водителей.
                                        Analytics.log("class_wanted_${cls.category}")
                                        classWanted = cls.category
                                    }
                                },
                                // Ширина под картинку машины: в экран влезает три карточки и
                                // край четвёртой. Обрезанный край — самый честный намёк «листай
                                // дальше»: без него Минивэн просто не существовал для человека.
                                modifier = Modifier.width(132.dp),
                            )
                        }
                    }
                    // Что выбрано в салоне — сразу под машинами. Опция относится к машине,
                    // поэтому и стоит рядом с её выбором, а не в свёрнутом списке ниже.
                    InstantChosenOptionsRow(
                        selected = orderOptions,
                        onRemove = { code -> orderOptionsCsv = (orderOptions - code).joinToString(",") },
                    )
                }
                // Подтверждение по «скоро»-классу: тёплым текстом, без формы и обещаний срока.
                AnimatedVisibility(
                    visible = classWanted != null,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    Surface(shape = CanonItemShape, color = CanonTaxiBg, border = BorderStroke(1.dp, CanonTaxi)) {
                        Row(
                            Modifier.fillMaxWidth().padding(CanonSpace.md),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("🔔", fontSize = TxTitle, lineHeight = LhTitle)
                            Spacer(Modifier.width(CanonSpace.sm))
                            Text(
                                appText(
                                    "Записали. Напишем, как только этот класс появится рядом.",
                                    "Яҙып ҡуйҙыҡ. Был класс яҡында барлыҡҡа килгәс, хәбәр итәбеҙ.",
                                ),
                                color = CanonText, fontSize = TxBody, lineHeight = LhBody,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                // Оценка цены: одна крупная сумма + три ответа, которые нужны до заказа.
                //
                // Показываем только в ПОЛНОМ положении шторки. Та же сумма написана на кнопке
                // «Вызвать за 100 ₽» и на плитке выбранного тарифа — третий раз крупно она
                // не нужна, а места занимала столько, что от карты оставалась полоска
                // в сантиметр. У Яндекса отдельного блока с ценой нет вовсе.
                if (toPoint != null && sheetStop == TaxiSheetStop.Full) {
                    Card(colors = CardDefaults.cardColors(containerColor = CanonSurface), shape = CanonCardShape) {
                        // Три состояния цены сменяются кросс-фейдом и в ОДНОЙ высоте: раньше карточка
                        // на каждом пересчёте схлопывалась и раздувалась, и кнопка «Вызвать» прыгала
                        // под пальцем. Скелетон вместо спиннера держит форму будущего числа.
                        val estPhase = when {
                            estimating -> "calc"
                            errorText != null -> "err"
                            estimate != null -> "ok"
                            else -> "none"
                        }
                        AnimatedContent(
                            targetState = estPhase,
                            transitionSpec = { fadeIn(tween(CanonMotion.NORMAL)).togetherWith(fadeOut(tween(CanonMotion.QUICK))) },
                            label = "estimatePhase",
                        ) { phase ->
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                when (phase) {
                                    "calc" -> {
                                        SkeletonBox(widthFraction = 0.42f, height = 30.dp, shape = InstantControlShape)
                                        SkeletonBox(widthFraction = 0.72f, height = 14.dp, shape = CircleShape)
                                        Text(
                                            appText("Считаем цену…", "Хаҡты иҫәпләйбеҙ…"),
                                            color = CanonMuted, fontSize = TxCaption, lineHeight = LhCaption,
                                        )
                                    }
                                    // Ошибка больше не тупик: раньше это была красная строка без единой
                                    // кнопки — заказать нельзя, повторить нечем, выход только «назад».
                                    "err" -> {
                                        Text(
                                            errorText ?: appText("Не удалось оценить цену", "Хаҡты баһалап булманы"),
                                            color = CanonRed, fontSize = TxBody, lineHeight = LhBody,
                                        )
                                        Text(
                                            appText("Проверь интернет — без цены заказывать нельзя.",
                                                "Интернетты тикшер — хаҡһыҙ заказ итеп булмай."),
                                            color = CanonMuted, fontSize = TxCaption, lineHeight = LhCaption,
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        AppButton(
                                            text = appText("Повторить", "Ҡабатлау"),
                                            onClick = { estimateTick++ },
                                            style = AppButtonStyle.Secondary,
                                            icon = Icons.Default.Search,
                                            height = 48.dp,
                                        )
                                    }
                                    "ok" -> estimate?.let { est ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            // Доминанта экрана: сюда человек и смотрит. Со скидкой здесь
                                            // стоит сумма, которую он реально отдаст, — а не та, которую
                                            // «якобы» экономит: цена в кнопке и в кармане обязана совпасть.
                                            AnimatedContent(targetState = est.priceToPay, label = "estPrice") { p ->
                                                Text(
                                                    "$p ₽",
                                                    color = if (est.hasPromoDiscount) CanonGreen2 else CanonText,
                                                    fontSize = TxHero, lineHeight = LhHero,
                                                    fontWeight = FontWeight.Bold,
                                                )
                                            }
                                            Spacer(Modifier.width(8.dp))
                                            Column {
                                                // «Было столько» — только когда скидка реально есть.
                                                // Нет скидки → нет ни перечёркнутой цены, ни пустого места.
                                                if (est.hasPromoDiscount) {
                                                    Text(
                                                        "${est.price} ₽",
                                                        color = CanonMuted, fontSize = TxCaption, lineHeight = LhCaption,
                                                        textDecoration = TextDecoration.LineThrough,
                                                    )
                                                }
                                                Text(
                                                    appText("примерно", "яҡынса"),
                                                    color = CanonMuted, fontSize = TxCaption, lineHeight = LhCaption,
                                                )
                                            }
                                        }
                                        val meta = buildList {
                                            if (est.distanceKm > 0) add(appText("≈ ${est.distanceKm.toInt()} км", "≈ ${est.distanceKm.toInt()} км"))
                                            if (est.etaMin > 0) add(appText("≈ ${est.etaMin.toInt()} мин в пути", "≈ ${est.etaMin.toInt()} мин юлда"))
                                            // Время ПОДАЧИ — то, что человек на самом деле хочет знать перед
                                            // заказом. Раньше его не показывали вообще: была только длительность
                                            // поездки, и «когда приедет?» оставалось без ответа.
                                            est.pickupEtaMin?.let { add(appText("машина через ≈$it мин", "машина ≈$it минуттан")) }
                                        }.joinToString("  ·  ")
                                        if (meta.isNotBlank()) Text(
                                            meta, color = CanonMuted, fontSize = TxCaption, lineHeight = LhCaption,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    // Погода на маршруте — рядом с ценой, до нажатия «Вызвать». В такси человек чаще
                    // всего едет прямо сейчас, и гололёд для него не «планы на завтра», а через минуту.
                    // Координаты уже выбраны на карте — геокодить ничего не нужно.
                    if (toPoint != null) {
                        WeatherWarningCard(
                            rememberRouteWeather(
                                fromLat = fromPoint?.latitude, fromLng = fromPoint?.longitude,
                                toLat = toPoint?.latitude, toLng = toPoint?.longitude,
                            )
                        )
                    }
                    // Выгода по промокоду — сразу под ценой. Это единственное место, где человек видит,
                    // что промокод не бумажка, и решает ДО нажатия «Вызвать».
                    TaxiPromoSavingsCard(estimate)
                    // Pricing v2 приходит только с сервера: клиент показывает
                    // базу, факторы и общий потолок, но не пересчитывает сумму сам.
                    TaxiPricingBreakdown(estimate)
                }
            }
        },
        extra = {
            if (toPoint != null) {
                // Полная версия того, что под кнопкой сказано одной строкой.
                Text(
                    if (scheduledAtMs != null) appText(
                        "Предзаказ ждёт своего времени. Открой Юлдаш ко времени подачи, чтобы начать поиск. Цену уточним при подаче.",
                        "Алдан заказ үҙ ваҡытын көтә. Эҙләүҙе башлар өсөн Юлдашты килеү ваҡытына ас. Хаҡты килгәндә асыҡлайбыҙ.")
                    else appText(
                        "Оплата водителю напрямую. Телефон водителя откроется после того, как он примет заказ.",
                        "Түләү йөрөтөүсегә тура. Йөрөтөүсе заказды алғас, уның телефоны асыла."),
                    color = CanonMuted, fontSize = TxCaption, lineHeight = LhCaption)

                // «Сохранить как Дом/Работу» для выбранного адреса — потом заказывать в один тап.
                if (toPoint != null && toText.isNotBlank()) {
                    SaveAsPlaceChips(
                        address = toText,
                        lat = toPoint!!.latitude,
                        lng = toPoint!!.longitude,
                        savedHome = savedPlaces.firstOrNull { it.kind == "home" },
                        savedWork = savedPlaces.firstOrNull { it.kind == "work" },
                        onSaved = { placesReload++ },
                    )
                }
                // Сурж — честно и ДО заказа: почему дороже и на сколько (потолок ×1.5 на сервере).
                AnimatedVisibility(
                    visible = (estimate?.surgeK ?: 1.0) > 1.0,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    val est = estimate
                    val pct = (((est?.surgeK ?: 1.0) - 1.0) * 100).toInt()
                    Surface(shape = CanonItemShape, color = CanonTaxiBg, border = BorderStroke(1.dp, CanonTaxi)) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("⚡", fontSize = TxTitle, lineHeight = LhTitle)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    // Серверный текст (двуязычный) — источник правды; локальный — фолбэк.
                                    appText(
                                        est?.surgeNoteRu?.ifBlank { null } ?: "Сейчас заказов больше обычного — цена выше на $pct%. Вызвать или подождать?",
                                        est?.surgeNoteBa?.ifBlank { null } ?: "Хәҙер заказдар күберәк — хаҡ $pct%-ҡа юғарыраҡ. Саҡырырғамы, әллә көтөргәме?",
                                    ),
                                    color = CanonText, fontSize = TxCaption, lineHeight = LhCaption,
                                )
                            }
                        }
                    }
                }
                // Что нужно в салоне: детское кресло по возрасту, коляска, животное, багаж.
                // Это НЕ класс — галочка поверх любого класса: машина не может стоять в двух классах,
                // а кресло возить может любая. Фильтр на сервере жёсткий: без кресла заказ не придёт.
                if (toPoint != null) {
                    InstantOptionsBlock(
                        selected = orderOptions,
                        onToggle = { code ->
                            val next = if (code in orderOptions) orderOptions - code else orderOptions + code
                            orderOptionsCsv = next.joinToString(",")
                        },
                    )
                }
                // Детали: как найти пассажира и «заказ для другого». Появляются вместе с маршрутом.
                if (toPoint != null) {
                    InstantOrderDetails(
                        open = detailsOpen,
                        onToggle = { detailsOpen = !detailsOpen },
                        comment = comment, onComment = { comment = it.take(300) },
                        entrance = entrance, onEntrance = { entrance = it.take(60) },
                        forOther = forOther,
                        onForOther = {
                            forOther = it
                            if (!it) { forName = ""; forPhone = "" }   // выключили — не шлём чужие ПДн
                        },
                        forName = forName, onForName = { forName = it.take(120) },
                        forPhone = forPhone, onForPhone = { forPhone = it.take(32) },
                    )
                }
                // Остановки переехали в карточку маршрута наверху — там им и место: это
                // точки пути, а не настройка заказа. Здесь остаётся только лист выбора адреса.
                if (addingStop) {
                    PickStopSheet(
                        onDismiss = { addingStop = false },
                        onPicked = { hit ->
                            // Добавляем в конец: порядок задаёт пассажир тем, как добавляет.
                            // Переставлять потом нельзя — водитель уже поедет к первой точке.
                            stops = stops + com.yuldash.app.data.TaxiStop(hit.lat, hit.lon, hit.title)
                            addingStop = false
                        },
                    )
                }
                // Круговой рейс — только межгород. Доступность решает сервер: он знает, где проходит
                // граница зоны. Переехал человек точку Б в черту города — предложение исчезает, и
                // включённый флаг надо погасить, иначе он молча уедет в заказ и цена не сойдётся.
                val rtAvailable = estimate?.roundTripAvailable == true
                LaunchedEffect(rtAvailable) { if (!rtAvailable) roundTrip = false }
                if (toPoint != null && rtAvailable) {
                    InstantRoundTripCard(
                        estimate = estimate,
                        checked = roundTrip,
                        onChecked = { roundTrip = it },
                        waitMin = returnWaitMin,
                        onWaitMin = { returnWaitMin = it },
                    )
                }
                // «Только женщина за рулём». Честно предупреждаем про цену выбора: женщин-водителей
                // меньше, машину можно ждать дольше или не дождаться. Подменять её мужчиной мы не
                // будем ни при каких условиях — иначе галочка ничего не значит (аудит 2026-08-06).
                if (toPoint != null) {
                    Surface(color = CanonSurface, shape = CanonItemShape) {
                        SettingSwitchRow(
                            icon = Icons.Default.Shield,
                            title = appText("Только женщина за рулём", "Тик ҡатын-ҡыҙ йөрөтөүсе"),
                            subtitle = appText(
                                "Заказ увидят только женщины-водители. Их меньше — машину можно ждать дольше или не дождаться.",
                                "Заказды тик ҡатын-ҡыҙ йөрөтөүселәр күрәсәк. Улар аҙыраҡ — машинаны оҙағыраҡ көтөргә тура килеүе бар.",
                            ),
                            checked = womenOnly,
                            onCheckedChange = { womenOnly = it },
                        )
                    }
                }
                // Когда подать машину: «Сейчас» или «На время» (предзаказ). Появляется, когда есть маршрут.
                if (toPoint != null) {
                    InstantTimingPicker(
                        scheduledAtMs = scheduledAtMs,
                        onNow = { scheduledAtMs = null },
                        onPickTime = { picked -> scheduledAtMs = picked },
                    )
                }

            }
        },
        // Подвал живёт только когда есть куда ехать: до выбора адреса кнопки «Вызвать» нет,
        // и его отступы оставляли под списком адресов пустую полосу.
        hasFooter = toPoint != null,
        footer = {
            // Главное действие экрана — вне прокрутки и видно в любом положении
            // шторки. Раньше кнопка жила в «полном» положении: в среднем её просто
            // не было, а это единственное, ради чего сюда пришли.
            if (toPoint != null) {
                val scheduled = scheduledAtMs != null
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(CanonSpace.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = {
                            val f = effFrom ?: return@Button
                            val t = toPoint ?: return@Button
                            creating = true; errorText = null
                            val fText = fromText.ifBlank { myPosText }
                            val tText = toText.ifBlank { mapPointText }
                            scope.launch {
                                if (scheduled) {
                                    val iso = isoFromMillis(scheduledAtMs!!)
                                    ApiClient.scheduleInstantOrder(f.latitude, f.longitude, t.latitude, t.longitude, iso, fText, tText, category)
                                        .onSuccess {
                                            ApiClient.fireAddRecentPlace(tText, t.latitude, t.longitude)
                                            onScheduled(it)
                                        }
                                        .onFailure { errorText = (it as? ApiException)?.message ?: createFailMsg }
                                } else {
                                    ApiClient.createInstantOrder(
                                        f.latitude, f.longitude, t.latitude, t.longitude, fText, tText, category,
                                        comment = comment, entrance = entrance,
                                        forName = if (forOther) forName else "",
                                        forPhone = if (forOther) forPhone else "",
                                        womenOnly = womenOnly,
                                        options = orderOptions.toList(),
                                        roundTrip = roundTrip,
                                        returnWaitMin = if (roundTrip) returnWaitMin else 0,
                                        stops = stops,
                                    )
                                        .onSuccess {
                                            // Наполняем «Недавние» точкой Б (best-effort, на долгоживущем scope — не блокирует заказ).
                                            ApiClient.fireAddRecentPlace(tText, t.latitude, t.longitude)
                                            onOrderCreated(it)
                                        }
                                        .onFailure { errorText = (it as? ApiException)?.message ?: createFailMsg }
                                }
                                creating = false
                            }
                        },
                        enabled = effFrom != null && toPoint != null && estimate != null && !creating,
                        // heightIn: на крупном системном шрифте фиксированные 54dp срезали надпись с ценой.
                        modifier = Modifier.weight(1f).heightIn(min = 54.dp),
                        shape = InstantControlShape,
                        colors = ButtonDefaults.buttonColors(containerColor = CanonTaxi, contentColor = CanonTaxiInk),   // жёлтый — режим такси
                    ) {
                        if (creating) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = CanonTaxiInk)
                        } else {
                            Icon(if (scheduled) Icons.Default.AccessTime else Icons.Default.DirectionsCar, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            val label = when {
                                // Предзаказ «на время»: показываем время подачи.
                                scheduled -> appText("Заказать на ${clockHm(scheduledAtMs!!)}", "${clockHm(scheduledAtMs!!)}-ға заказ итеү")
                                // Глагол-действие: «Вызвать машину» понятнее, чем «Заказать» (эталон Яндекс/inDrive).
                                // Цена на кнопке — уже со скидкой: человек нажимает ровно ту сумму, что заплатит.
                                estimate != null -> appText("Вызвать за ${estimate!!.priceToPay} ₽", "${estimate!!.priceToPay} ₽-ға саҡырыу")
                                else -> appText("Вызвать машину", "Машина саҡырыу")
                            }
                            // Цена в кнопке меняется вместе с классом машины — без анимации это выглядело
                            // как подмена суммы в последний момент перед нажатием.
                            AnimatedContent(targetState = label, label = "orderCta") { text ->
                                Text(
                                    text, fontSize = TxBody, lineHeight = LhBody, fontWeight = FontWeight.Bold,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                    // Ползунки — вход в детали заказа: опции салона, остановки, «как меня найти»,
                    // время подачи. Раньше до них нужно было догадаться потянуть шторку вверх;
                    // теперь рядом с главной кнопкой стоит видимая дверь, как у Яндекса.
                    Surface(
                        onClick = { sheetTouched = true; sheetStop = TaxiSheetStop.Full },
                        shape = InstantControlShape,
                        color = CanonBg,
                        modifier = Modifier.minimumInteractiveComponentSize().size(54.dp),
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Tune,
                                contentDescription = appText("Детали заказа", "Заказ деталдәре"),
                                tint = CanonGreen2,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }
                // Одной короткой строкой: двухстрочное объяснение под кнопкой обрезалось
                // нижним краем и вместе с кнопкой съедало карту. Подробности — в «полном»
                // положении шторки, там для них есть место.
                Text(
                    if (scheduled) appText("Предзаказ ждёт своего времени", "Алдан заказ үҙ ваҡытын көтә")
                    else appText("Оплата водителю напрямую", "Түләү йөрөтөүсегә тура"),
                    color = CanonMuted, fontSize = TxCaption, lineHeight = LhCaption,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
    )

    if (pickOnMap) {
        PickupPickerOverlay(
            initial = toPoint ?: effFrom,
            onConfirm = { lat, lng ->
                toPoint = Point(lat, lng); toText = resolvingText
                query = ""; suggestions = emptyList(); pickOnMap = false
                // Спрашиваем, как называется это место. Не дождались — остаётся «Точка
                // на карте»: координаты у водителя есть, заказ из-за адреса не ломаем.
                scope.launch {
                    val addr = GeocoderClient.addressAt(lat, lng)
                    toText = if (addr.isNotBlank()) addr else mapPointText
                }
            },
            onDismiss = { pickOnMap = false },
            // Не «место встречи»: туда едут, а не встречаются.
            hint = appText("Двигай карту — пин там, куда едешь", "Картаны күсер — пин барасаҡ урында"),
        )
    }
    if (pickFromOnMap) {
        PickupPickerOverlay(
            initial = effFrom,
            onConfirm = { lat, lng ->
                fromPoint = Point(lat, lng); fromText = resolvingText
                fromManual = true; pickFromOnMap = false
                scope.launch {
                    val addr = GeocoderClient.addressAt(lat, lng)
                    fromText = if (addr.isNotBlank()) addr else mapPointText
                }
            },
            onDismiss = { pickFromOnMap = false },
            hint = appText("Двигай карту — пин там, откуда поедешь", "Картаны күсер — пин сығасаҡ урында"),
        )
    }
}

// ------------------------------ Детали заказа: «как найти» и «для другого» ------------------------------
/**
 * Свёрнутый блок с двумя вещами, которых не хватало (аудит 2026-07-26):
 *
 *  1. «Как меня найти» — комментарий и подъезд. В селе «Ленина 12» — пять домов без табличек,
 *     а чат открывается только ПОСЛЕ принятия заказа: водитель наматывал круги вслепую.
 *  2. «Заказ для другого» — сын из Уфы вызывает такси маме в Баймаке. Без этих полей водитель
 *     звонил заказчику в другой город, а мама стояла у ворот и не знала, приехала ли машина.
 *
 * Свёрнуто по умолчанию: обычному заказу лишние поля на главном пути только мешают.
 */
@Composable
private fun InstantOrderDetails(
    open: Boolean,
    onToggle: () -> Unit,
    comment: String, onComment: (String) -> Unit,
    entrance: String, onEntrance: (String) -> Unit,
    forOther: Boolean, onForOther: (Boolean) -> Unit,
    forName: String, onForName: (String) -> Unit,
    forPhone: String, onForPhone: (String) -> Unit,
) {
    // Свёрнуто, но что-то заполнено → показываем короткую сводку, чтобы человек не потерял ввод.
    val filled = comment.isNotBlank() || entrance.isNotBlank() || forOther
    Card(colors = CardDefaults.cardColors(containerColor = CanonSurface), shape = CanonItemShape) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(
                onClick = onToggle,
                color = CanonSurface,
                shape = CanonItemShape,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LocationOn, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(appText("Как меня найти", "Мине нисек табырға"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        if (!open && filled) {
                            Text(
                                listOf(entrance, comment).filter { it.isNotBlank() }.joinToString(" · ")
                                    .ifBlank { appText("Заказ для другого", "Икенсе кеше өсөн") },
                                color = CanonMuted, fontSize = 12.sp, maxLines = 1,
                            )
                        } else if (!open) {
                            Text(
                                appText("Подъезд, ориентир, заказ для другого", "Подъезд, ориентир, икенсе кеше өсөн"),
                                color = CanonMuted, fontSize = 12.sp, maxLines = 1,
                            )
                        }
                    }
                    Text(
                        if (open) appText("Скрыть", "Йәшереү") else appText("Указать", "Күрһәтеү"),
                        color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    )
                }
            }

            AnimatedVisibility(
                visible = open,
                enter = expandVertically(tween(CanonMotion.QUICK)) + fadeIn(tween(CanonMotion.QUICK)),
                exit = shrinkVertically(tween(CanonMotion.QUICK)) + fadeOut(tween(CanonMotion.QUICK)),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = entrance,
                        onValueChange = onEntrance,
                        label = { Text(appText("Подъезд, квартира, этаж", "Подъезд, фатир, ҡат")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    )
                    OutlinedTextField(
                        value = comment,
                        onValueChange = onComment,
                        label = { Text(appText("Комментарий водителю", "Йөрөтөүсегә аңлатма")) },
                        placeholder = { Text(appText("«За магазином, синие ворота»", "«Кибет артында, зәңгәр ҡапҡа»")) },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    )

                    // Заказ для другого — отдельный переключатель, чтобы чужой телефон не улетал случайно.
                    Surface(
                        onClick = { onForOther(!forOther) },
                        color = if (forOther) CanonMint else CanonBg,
                        shape = CanonItemShape,
                        border = BorderStroke(1.dp, if (forOther) CanonGreen2 else CanonBorder),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (forOther) Icons.Default.CheckCircle else Icons.Default.Person,
                                contentDescription = null,
                                tint = if (forOther) CanonGreen2 else CanonMuted,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(appText("Заказ для другого человека", "Икенсе кеше өсөн заказ"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text(
                                    appText("Водитель будет звонить ему, а не тебе", "Йөрөтөүсе һиңә түгел, уға шылтырата"),
                                    color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp,
                                )
                            }
                        }
                    }
                    AnimatedVisibility(
                        visible = forOther,
                        enter = expandVertically(tween(CanonMotion.QUICK)) + fadeIn(tween(CanonMotion.QUICK)),
                        exit = shrinkVertically(tween(CanonMotion.QUICK)) + fadeOut(tween(CanonMotion.QUICK)),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = forName,
                                onValueChange = onForName,
                                label = { Text(appText("Кого везём (имя)", "Кемде илтәбеҙ (исем)")) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                            )
                            OutlinedTextField(
                                value = forPhone,
                                onValueChange = onForPhone,
                                label = { Text(appText("Его телефон", "Уның телефоны")) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                            )
                            Text(
                                appText(
                                    "Телефон увидит только водитель и только после того, как примет заказ.",
                                    "Телефонды тик йөрөтөүсе, тик заказды алғандан һуң күрә.",
                                ),
                                color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------ Когда подать: «Сейчас» / «На время» ------------------------------
@Composable
private fun InstantTimingPicker(
    scheduledAtMs: Long?,
    onNow: () -> Unit,
    onPickTime: (Long) -> Unit,
) {
    val ctx = LocalContext.current
    val scheduled = scheduledAtMs != null
    Card(colors = CardDefaults.cardColors(containerColor = CanonSurface), shape = CanonItemShape) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(appText("Когда подать машину?", "Машина ҡасан килһен?"), color = CanonText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TimingChoiceChip(
                    title = appText("Сейчас", "Хәҙер"),
                    selected = !scheduled,
                    onClick = onNow,
                    modifier = Modifier.weight(1f),
                )
                TimingChoiceChip(
                    title = appText("На время", "Ваҡытҡа"),
                    selected = scheduled,
                    onClick = { showDateTimePicker(ctx, scheduledAtMs, onPickTime) },
                    modifier = Modifier.weight(1f),
                )
            }
            AnimatedVisibility(visible = scheduled, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AccessTime, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(scheduledAtMs?.let { fullWhen(it) } ?: "", color = CanonText, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { showDateTimePicker(ctx, scheduledAtMs, onPickTime) }) {
                        Text(appText("Изменить", "Үҙгәртеү"), color = CanonGreen2, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun TimingChoiceChip(title: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        color = if (selected) CanonMint else CanonBg,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.5.dp, if (selected) CanonGreen2 else CanonBorder),
        modifier = modifier.heightIn(min = 48.dp),
    ) {
        Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
            Text(title, color = if (selected) CanonGreen2 else CanonMuted, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

// Предзаказ создан → спокойное подтверждение + путь в «Мои предзаказы».
@Composable
private fun InstantScheduledCreatedCard(
    scheduledAt: String?,
    fromText: String,
    toText: String,
    onOpenScheduled: () -> Unit,
    onNewOrder: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(color = CanonMint, shape = CircleShape) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(16.dp).size(38.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(
            scheduledAt?.let { appText("Предзаказ на ${formatDepart(it)} создан", "${formatDepart(it)}-ға алдан заказ булдырылды") }
                ?: appText("Предзаказ создан", "Алдан заказ булдырылды"),
            fontWeight = FontWeight.Bold, fontSize = 19.sp, textAlign = TextAlign.Center, color = CanonText,
        )
        Spacer(Modifier.height(8.dp))
        Text("${fromText.ifBlank { appText("Точка А", "А нөктәһе") }} → ${toText.ifBlank { appText("Точка Б", "Б нөктәһе") }}",
            color = CanonMuted, fontSize = 14.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(appText("Поиск машины запустится автоматически ко времени подачи. Мы напомним.",
            "Машина эҙләү килеү ваҡытына автоматик башланыр. Беҙ иҫкә төшөрөрбөҙ."),
            color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        AppButton(text = appText("Мои предзаказы", "Минең алдан заказдар"), onClick = onOpenScheduled, style = AppButtonStyle.Primary)
        Spacer(Modifier.height(8.dp))
        AppButton(text = appText("Новый заказ", "Яңы заказ"), onClick = onNewOrder, style = AppButtonStyle.Secondary)
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onBack) { Text(appText("Готово", "Әҙер"), color = CanonMuted) }
    }
}

// Диалог выбора даты и времени подачи: не в прошлом, ≤ 7 суток.
private fun showDateTimePicker(ctx: Context, initialMs: Long?, onPicked: (Long) -> Unit) {
    val now = java.util.Calendar.getInstance()
    val start = java.util.Calendar.getInstance().apply {
        timeInMillis = initialMs ?: (now.timeInMillis + 30 * 60_000L)   // по умолчанию через ~30 мин
    }
    val maxMs = now.timeInMillis + 7L * 24 * 60 * 60 * 1000   // потолок 7 суток
    android.app.DatePickerDialog(
        ctx,
        { _, year, month, day ->
            android.app.TimePickerDialog(
                ctx,
                { _, hour, minute ->
                    val picked = java.util.Calendar.getInstance().apply {
                        set(java.util.Calendar.YEAR, year); set(java.util.Calendar.MONTH, month)
                        set(java.util.Calendar.DAY_OF_MONTH, day)
                        set(java.util.Calendar.HOUR_OF_DAY, hour); set(java.util.Calendar.MINUTE, minute)
                        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
                    }
                    // Не в прошлом (мин. через 5 мин) и не дальше 7 суток.
                    val floor = System.currentTimeMillis() + 5 * 60_000L
                    val ms = picked.timeInMillis.coerceIn(floor, maxMs)
                    onPicked(ms)
                },
                start.get(java.util.Calendar.HOUR_OF_DAY), start.get(java.util.Calendar.MINUTE), true,
            ).show()
        },
        start.get(java.util.Calendar.YEAR), start.get(java.util.Calendar.MONTH), start.get(java.util.Calendar.DAY_OF_MONTH),
    ).apply {
        datePicker.minDate = now.timeInMillis
        datePicker.maxDate = maxMs
    }.show()
}

// epoch ms → ISO с локальным смещением (однозначно для сервера).
// Одна функция на приложение: копия здесь и `isoWithOffset` в BookingActiveTripScreen делали
// одно и то же, а две копии расходятся при первой же правке (урок «один шов»).
private fun isoFromMillis(ms: Long): String = isoWithOffset(ms)

// epoch ms → «ЧЧ:ММ» (для кнопки).
private fun clockHm(ms: Long): String {
    val c = java.util.Calendar.getInstance().apply { timeInMillis = ms }
    return String.format(java.util.Locale.US, "%02d:%02d", c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
}

// epoch ms → «ДД.ММ, ЧЧ:ММ» (для строки выбранного времени).
private fun fullWhen(ms: Long): String {
    val c = java.util.Calendar.getInstance().apply { timeInMillis = ms }
    return String.format(
        java.util.Locale.US, "%02d.%02d, %02d:%02d",
        c.get(java.util.Calendar.DAY_OF_MONTH), c.get(java.util.Calendar.MONTH) + 1,
        c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE),
    )
}

// ------------------------------ Классы машин ------------------------------
/** Класс в витрине пассажира. Порядок = порядок на экране: от дешёвого к дорогому,
 *  Минивэн последним — он не «дороже», а «вместительнее», и нужен реже остальных. */
internal data class InstantClassInfo(
    val category: String,
    val titleRu: String, val titleBa: String,
    val subtitleRu: String, val subtitleBa: String,
    /** Картинка машины класса — главный опознавательный знак тарифа. Файлы лежат
     *  в res/drawable-nodpi, меняются без правки кода. */
    val iconRes: Int,
    /** Ночная версия картинки. Нужна там, где дневная тонет в тёмном фоне: чёрный седан
     *  Бизнеса ночью превращался в пару фар. Нет ночной — показываем дневную. */
    val iconNightRes: Int? = null,
)

internal val InstantClasses = listOf(
    InstantClassInfo("standard", "Эконом", "Эконом", "обычная машина", "ғәҙәти машина",
                     R.drawable.yuldash_tariff_economy),
    InstantClassInfo("comfort", "Комфорт", "Комфорт", "новее и с кондиционером", "яңыраҡ, кондиционерлы",
                     R.drawable.yuldash_tariff_comfort),
    InstantClassInfo("business", "Бизнес", "Бизнес", "седан премиум-класса", "премиум класслы седан",
                     R.drawable.yuldash_tariff_business,
                     iconNightRes = R.drawable.yuldash_tariff_business_night),
    InstantClassInfo("minivan", "Минивэн", "Минивэн", "6–8 мест", "6–8 урын",
                     R.drawable.yuldash_tariff_minivan),
)

@Composable
private fun InstantClassCard(
    title: String,
    subtitle: String,
    price: Int?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    iconRes: Int? = null,
    iconNightRes: Int? = null,
    pickupEtaMin: Int? = null,
) {
    // Сохраняем общий Mobility-компонент с radio-семантикой и добавляем
    // мягкий тактильный масштаб из входящей ветки.
    val scale by animateFloatAsState(if (selected) 1f else 0.98f, tween(CanonMotion.QUICK), label = "clsScale")
    // Закрытый класс не прячем и не блокируем: приглушаем и оставляем нажимаемым. Нажатие —
    // это заявка «хочу», по ней мы и поймём, где заводить водителей следующими.
    val alpha by animateFloatAsState(if (enabled) 1f else 0.55f, tween(CanonMotion.QUICK), label = "clsAlpha")
    TaxiServiceClassTile(
        title = title,
        subtitle = subtitle,
        price = price,
        selected = selected,
        onClick = onClick,
        modifier = modifier.graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha },
        iconRes = iconRes,
        iconNightRes = iconNightRes,
        pickupEtaMin = pickupEtaMin,
    )
}

// ------------------------------ Опции салона ------------------------------
/** Опция — не класс. Одна машина не может стоять в двух классах, а детское кресло возить
 *  может любая: поэтому кресла, коляска и животные живут галочками поверх любого класса.
 *  Коды совпадают с backend/app/car_class.py. */
internal data class InstantOptionInfo(
    val code: String,
    val labelRu: String, val labelBa: String,
    val emoji: String,
)

internal val InstantOptions = listOf(
    InstantOptionInfo("seat_0_1", "Люлька 0–1", "Бәпес арбаһы 0–1", "👶"),
    InstantOptionInfo("seat_1_4", "Кресло 1–4", "Ултырғыс 1–4", "🧒"),
    InstantOptionInfo("seat_4_7", "Кресло 4–7", "Ултырғыс 4–7", "🧒"),
    InstantOptionInfo("booster", "Бустер 7–12", "Бустер 7–12", "💺"),
    InstantOptionInfo("stroller", "Коляска", "Балалар арбаһы", "🍼"),
    InstantOptionInfo("wheelchair", "Инвалидная коляска", "Инвалид коляскаһы", "♿"),
    InstantOptionInfo("guide_dog", "Собака-проводник", "Юл күрһәтеүсе эт", "🦮"),
    InstantOptionInfo("pets", "С животным", "Хайуан менән", "🐾"),
    InstantOptionInfo("big_luggage", "Большой багаж", "Ҙур багаж", "🧳"),
)

/**
 * Выбранные опции салона — чипами. Тап по чипу снимает опцию.
 *
 * Крестик тут не отдельная кнопка, а знак «нажми — уберётся»: две цели касания рядом на
 * чипе шириной в палец промахиваются друг в друга, и человек снимает не то, что хотел.
 */
@Composable
private fun InstantChosenOptionsRow(selected: Set<String>, onRemove: (String) -> Unit) {
    AnimatedVisibility(
        visible = selected.isNotEmpty(),
        enter = fadeIn(tween(CanonMotion.NORMAL)) + expandVertically(),
        exit = fadeOut(tween(CanonMotion.QUICK)) + shrinkVertically(),
    ) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(CanonSpace.sm),
            verticalArrangement = Arrangement.spacedBy(CanonSpace.xs),
        ) {
            InstantOptions.filter { it.code in selected }.forEach { opt ->
                val label = appText(opt.labelRu, opt.labelBa)
                Surface(
                    onClick = { onRemove(opt.code) },
                    shape = CanonTinyShape,
                    color = CanonTaxiBg,
                    modifier = Modifier.minimumInteractiveComponentSize(),
                ) {
                    Row(
                        Modifier.padding(horizontal = CanonSpace.sm, vertical = CanonSpace.xs),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(CanonSpace.xs),
                    ) {
                        Text(opt.emoji, fontSize = TxCaption, lineHeight = LhCaption)
                        Text(label, color = CanonText, fontSize = TxCaption, lineHeight = LhCaption,
                             fontWeight = FontWeight.Medium, maxLines = 1)
                        Icon(
                            Icons.Default.Close,
                            contentDescription = appText("Убрать: $label", "Алыу: $label"),
                            tint = CanonMutedStrong,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}


@Composable
private fun InstantOptionsBlock(selected: Set<String>, onToggle: (String) -> Unit) {
    // Свёрнут по умолчанию: девяти заказам из десяти ничего этого не нужно, и держать девять
    // галочек на главном пути — значит мешать всем ради немногих. Но открыть — один тап.
    var open by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
        Surface(
            onClick = { open = !open },
            shape = CanonItemShape,
            color = if (selected.isEmpty()) CanonSurface else CanonTaxiBg,
            border = BorderStroke(1.dp, if (selected.isEmpty()) CanonBorder else CanonTaxi),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = CanonSpace.md, vertical = CanonSpace.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("🧸", fontSize = TxBody, lineHeight = LhBody)
                Spacer(Modifier.width(CanonSpace.sm))
                Text(
                    if (selected.isEmpty()) appText("Нужно кресло, коляска, животное?",
                        "Ултырғыс, арба, хайуан кәрәкме?")
                    else appText("Выбрано: ${selected.size}", "Һайланды: ${selected.size}"),
                    color = CanonText, fontSize = TxBody, lineHeight = LhBody,
                    fontWeight = if (selected.isEmpty()) FontWeight.Normal else FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                val turn by animateFloatAsState(if (open) 180f else 0f, tween(CanonMotion.QUICK), label = "optTurn")
                Icon(
                    Icons.Default.ExpandMore,
                    contentDescription = if (open) appText("Свернуть", "Йыйырға")
                    else appText("Развернуть", "Асырға"),
                    tint = CanonMuted,
                    modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = turn },
                )
            }
        }
        AnimatedVisibility(
            visible = open,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(CanonSpace.sm),
                verticalArrangement = Arrangement.spacedBy(CanonSpace.sm),
            ) {
                InstantOptions.forEach { opt ->
                    val on = opt.code in selected
                    val optState = if (on) appText("Выбрано", "Һайланған")
                    else appText("Не выбрано", "Һайланмаған")
                    val bg by animateColorAsState(
                        if (on) CanonTaxiBg else CanonSurface, tween(CanonMotion.QUICK), label = "optBg")
                    val border by animateColorAsState(
                        if (on) CanonTaxi else CanonBorder, tween(CanonMotion.QUICK), label = "optBorder")
                    Surface(
                        onClick = { onToggle(opt.code) },
                        shape = CanonTinyShape,
                        color = bg,
                        border = BorderStroke(if (on) 2.dp else 1.dp, border),
                        modifier = Modifier.heightIn(min = 48.dp).semantics {
                            role = Role.Checkbox
                            this.selected = on
                            stateDescription = optState
                        },
                    ) {
                        Row(
                            Modifier.padding(horizontal = CanonSpace.md, vertical = CanonSpace.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(opt.emoji, fontSize = TxBody, lineHeight = LhBody)
                            Spacer(Modifier.width(CanonSpace.xs))
                            Text(
                                appText(opt.labelRu, opt.labelBa),
                                color = CanonText, fontSize = TxCaption, lineHeight = LhCaption,
                                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------ Таймер ожидания (обе стороны) ------------------------------
/** «Машина на месте»: живой таймер — бесплатное окно тикает вниз, дальше честно копится
 *  платное ожидание (+N ₽/мин, целыми минутами — как считает сервер). */
@Composable
internal fun InstantWaitingRow(order: InstantOrderDto) {
    val startMs = order.waitingStartedAt?.let { isoUtcToEpochMs(it) } ?: return
    val now by rememberNowMs()
    val elapsedSec = ((now - startMs) / 1000).coerceAtLeast(0)
    val freeSec = order.waitFreeMin * 60L
    val isFree = elapsedSec < freeSec
    val accent by animateColorAsState(if (isFree) CanonGreen2 else CanonRed, tween(CanonMotion.SLOW), label = "waitAccent")
    // Доля бесплатного окна, что ещё осталась. Тикает раз в секунду — тянем плавно, чтобы
    // полоска ползла, а не дёргалась. Стартовое значение = реальный остаток (это обратный
    // отсчёт, а не «рост»: анимировать его с нуля было бы враньём).
    val freeLeftFraction = if (freeSec > 0) ((freeSec - elapsedSec).toFloat() / freeSec).coerceIn(0f, 1f) else 0f
    val freeProgress by animateFloatAsState(freeLeftFraction, tween(1000, easing = LinearEasing), label = "waitFree")
    Surface(shape = CanonItemShape, color = accent.copy(alpha = 0.08f), border = BorderStroke(1.dp, accent.copy(alpha = 0.4f))) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth().heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AccessTime, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                if (isFree) {
                    val left = freeSec - elapsedSec
                    // Locale явно: без него в локалях с восточно-арабскими цифрами таймер
                    // рисуется чужими знаками. В остальных местах такси Locale уже указан.
                    val leftMmSs = String.format(java.util.Locale.US, "%d:%02d", left / 60, left % 60)
                    Text(
                        appText("Бесплатное ожидание $leftMmSs", "Бушлай көтөү $leftMmSs"),
                        color = CanonText, fontSize = TxBody, lineHeight = LhBody, fontWeight = FontWeight.Bold,
                    )
                } else {
                    val paidRub = ((elapsedSec / 60 - order.waitFreeMin).coerceAtLeast(0)) * order.waitFeeRubPerMin
                    Text(
                        appText("Платное ожидание · +${order.waitFeeRubPerMin} ₽/мин" + (if (paidRub > 0) " (уже +$paidRub ₽)" else ""),
                            "Түләүле көтөү · +${order.waitFeeRubPerMin} ₽/мин" + (if (paidRub > 0) " (инде +$paidRub ₽)" else "")),
                        color = CanonText, fontSize = TxBody, lineHeight = LhBody, fontWeight = FontWeight.Bold,
                    )
                }
            }
            // Пока окно бесплатное — видно, СКОЛЬКО его осталось, без чтения цифр.
            if (isFree) {
                LinearProgressIndicator(
                    progress = { freeProgress },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                    color = accent, trackColor = CanonBorder,
                )
            }
        }
    }
}

// ------------------------------ «Ищем машину» ------------------------------
@Composable
private fun InstantSearchingCard(order: InstantOrderDto, onCancel: () -> Unit) {
    val infinite = rememberInfiniteTransition(label = "search")
    val pulse by infinite.animateFloat(
        initialValue = 0.4f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "pulse",
    )
    // Сколько человек ждёт НА САМОМ ДЕЛЕ. Точку отсчёта даёт сервер (created_at / searching_at):
    // свой таймер обнулялся при каждом возврате на экран, и после пяти минут ожидания человек
    // снова читал «обычно машина находится за 1–3 минуты» — это было враньём.
    // Часы телефона могут врать, поэтому отрицательное и неправдоподобно большое (>2 ч) значение
    // считаем негодным и откатываемся на локальный отсчёт БЕЗ цифр (см. showElapsed ниже).
    val serverStartMs = remember(order.id, order.searchClockFrom) {
        order.searchClockFrom?.let(::parseIsoUtcMillis)
    }
    val screenOpenedMs = remember(order.id) { System.currentTimeMillis() }
    val now by rememberNowMs()
    val serverSec = serverStartMs?.let { (now - it) / 1000 }?.takeIf { it in 0..7_200L }
    val watchedSec = serverSec ?: ((now - screenOpenedMs) / 1000).coerceAtLeast(0)
    val waitStage = when {
        watchedSec < 25L -> 0
        watchedSec < 70L -> 1
        else -> 2
    }
    // Цифру показываем только когда ожидание уже затянулось: на двадцатой секунде секундомер
    // давит, на второй минуте — наоборот, отвечает на «сколько уже?». И только когда время
    // подтверждено сервером — выдуманных чисел на экране быть не должно.
    val showElapsed = serverSec != null && serverSec >= 70L
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(120.dp)) {
            Box(Modifier.size((60 + pulse * 56).dp).background(CanonGreen2.copy(alpha = 0.12f * pulse), CircleShape))
            Surface(shape = CircleShape, color = CanonGreen2) {
                Icon(
                    painterResource(R.drawable.yu_map_car),
                    contentDescription = appText("Ищем машину", "Машина эҙләйбеҙ"),
                    tint = CanonBg, modifier = Modifier.padding(16.dp).size(34.dp),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            appText("Ищем машину рядом…", "Яҡында машина эҙләйбеҙ…"),
            color = CanonText, fontSize = TxTitle, lineHeight = LhTitle,
            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        MobilityRouteTimeline(
            from = order.fromText,
            to = order.toText,
            compact = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        // Сумма здесь — та, что человек отдаст водителю: скидка по промокоду уже зафиксирована
        // в заказе, и показывать полную цену значило бы обещать одно, а взять другое.
        val payWhileSearching = formatTaxiKop(order.passengerPayKop)
        Text(
            appText("≈ $payWhileSearching · подбираем ближайшего водителя", "≈ $payWhileSearching · яҡын йөрөтөүсене табабыҙ"),
            color = CanonMuted, fontSize = TxCaption, lineHeight = LhCaption, textAlign = TextAlign.Center,
        )
        if (order.hasPromoDiscount) {
            Spacer(Modifier.height(CanonSpace.md))
            TaxiPromoPayRow(order = order, forDriver = false)
        }
        // Пауза перед строкой ожидания — самая длинная на карточке: дальше идёт то, ради чего
        // человек и смотрит в экран («сколько ещё ждать»), и её стоит отделить воздухом.
        Spacer(Modifier.height(CanonSpace.xl))
        // Ответ на «сколько ещё ждать». Пустого обещания не даём — по мере ожидания текст
        // честно меняется, и человек видит, что приложение про него не забыло.
        AnimatedContent(
            targetState = waitStage,
            transitionSpec = { fadeIn(tween(CanonMotion.NORMAL)).togetherWith(fadeOut(tween(CanonMotion.QUICK))) },
            label = "searchStage",
        ) { stage ->
            Text(
                when (stage) {
                    0 -> appText("Обычно машина находится за 1–3 минуты",
                        "Ғәҙәттә машина 1–3 минутта табыла")
                    1 -> appText("Ещё ищем — свободных машин рядом сейчас мало",
                        "Әле лә эҙләйбеҙ — яҡында буш машина аҙ")
                    else -> appText("Ищем дольше обычного. Можно подождать — как найдём, сразу сообщим",
                        "Ғәҙәттәгенән оҙағыраҡ эҙләйбеҙ. Көтөргә була — тапҡас, шунда уҡ хәбәр итәбеҙ")
                },
                color = CanonMuted, fontSize = TxCaption, lineHeight = LhCaption, textAlign = TextAlign.Center,
            )
        }
        AnimatedVisibility(visible = showElapsed, enter = fadeIn(tween(CanonMotion.SLOW)), exit = fadeOut(tween(CanonMotion.QUICK))) {
            Text(
                appText("Ищем уже ${mmSs(watchedSec)}", "Инде ${mmSs(watchedSec)} эҙләйбеҙ"),
                color = CanonMuted, fontSize = TxCaption, lineHeight = LhCaption,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        // «В Комфорте сейчас никого» — предложение поискать в соседнем классе.
        // Появляется не сразу: первые секунды честнее отдать тому классу, что человек выбрал.
        // Молча класс НЕ подменяем никогда: «заказал Комфорт — приехал Логан» это главная
        // претензия к агрегаторам, и решать тут должен пассажир, а не мы за него.
        InstantAlternativesBlock(order = order, watchedSec = watchedSec)

        Spacer(Modifier.height(24.dp))
        MobilityProgressRail(
            labels = listOf(
                appText("Запрос", "Һорау"),
                appText("Водитель", "Йөрөтөүсе"),
                appText("Подача", "Килеү"),
            ),
            currentIndex = 0,
            accent = CanonTaxi,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Spacer(Modifier.height(24.dp))
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.heightIn(min = 48.dp),
            shape = InstantControlShape,
        ) {
            Text(
                appText("Отменить заказ", "Заказды кире алыу"),
                color = CanonRed, fontSize = TxBody, lineHeight = LhBody,
            )
        }
    }
}

// ------------------------------ Соседний класс, когда своих машин нет ------------------------------
/**
 * Через `after_sec` секунд поиска показываем классы, в которых машины есть, — с ценой.
 * Решает пассажир: одна кнопка расширяет поиск, ничего не подменяя за спиной.
 *
 * Цена, названная на этой карточке, и есть та, что он заплатит: сервер фиксирует её в момент
 * согласия. Никаких «а потом оказалось дороже».
 */
@Composable
internal fun InstantAlternativesBlock(order: InstantOrderDto, watchedSec: Long) {
    var options by remember(order.id) { mutableStateOf<List<InstantAlternativeDto>>(emptyList()) }
    var afterSec by remember(order.id) { mutableIntStateOf(20) }
    var added by remember(order.id) { mutableStateOf<String?>(null) }
    var busy by remember(order.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Тянем список один раз — когда ожидание перевалило за порог. Раньше незачем, а дёргать
    // сервер каждую секунду ради «а вдруг появилось» — только жечь батарею и трафик в селе.
    val ready = watchedSec >= afterSec
    LaunchedEffect(order.id, ready) {
        if (ready && options.isEmpty() && added == null) {
            ApiClient.getInstantAlternatives(order.id).onSuccess { (sec, list) ->
                afterSec = sec
                options = list
            }
        }
    }

    AnimatedVisibility(
        visible = ready && (options.isNotEmpty() || added != null),
        enter = fadeIn(tween(CanonMotion.NORMAL)) + expandVertically(),
        exit = fadeOut(tween(CanonMotion.QUICK)) + shrinkVertically(),
    ) {
        Surface(
            shape = CanonCardShape,
            color = CanonTaxiBg,
            border = BorderStroke(1.dp, CanonTaxi),
            modifier = Modifier.fillMaxWidth().padding(top = CanonSpace.lg),
        ) {
            Column(
                Modifier.padding(CanonSpace.md),
                verticalArrangement = Arrangement.spacedBy(CanonSpace.sm),
            ) {
                val addedTitle = added?.let { cat ->
                    InstantClasses.firstOrNull { it.category == cat }
                        ?.let { appText(it.titleRu, it.titleBa) }
                }
                if (addedTitle != null) {
                    Text(
                        appText("Ищем и в классе «$addedTitle» — цена уже пересчитана",
                            "«$addedTitle» класында ла эҙләйбеҙ — хаҡ иҫәпләнде"),
                        color = CanonText, fontSize = TxBody, lineHeight = LhBody,
                        fontWeight = FontWeight.Bold,
                    )
                } else {
                    val mine = InstantClasses.firstOrNull { it.category == order.category }
                    val mineTitle = mine?.let { appText(it.titleRu, it.titleBa) }
                        ?: appText("этом классе", "был класта")
                    Text(
                        appText("В классе «$mineTitle» рядом сейчас никого",
                            "«$mineTitle» класында яҡында әлегә бер кем юҡ"),
                        color = CanonText, fontSize = TxBody, lineHeight = LhBody,
                        fontWeight = FontWeight.Bold,
                    )
                    options.forEach { alt ->
                        val info = InstantClasses.firstOrNull { it.category == alt.category }
                        val title = info?.let { appText(it.titleRu, it.titleBa) } ?: alt.category
                        val cheaper = alt.priceDiff < 0
                        Column(verticalArrangement = Arrangement.spacedBy(CanonSpace.xs)) {
                            Text(
                                if (cheaper)
                                    appText("Рядом есть «$title» — ${alt.price} ₽, это дешевле",
                                        "Яҡында «$title» бар — ${alt.price} һум, был арзаныраҡ")
                                else
                                    appText("Рядом есть «$title» — ${alt.price} ₽",
                                        "Яҡында «$title» бар — ${alt.price} һум"),
                                color = CanonMuted, fontSize = TxCaption, lineHeight = LhCaption,
                            )
                            Button(
                                onClick = {
                                    if (!busy) {
                                        busy = true
                                        scope.launch {
                                            ApiClient.addInstantAlternative(order.id, alt.category)
                                                .onSuccess { added = alt.category; options = emptyList() }
                                            busy = false
                                        }
                                    }
                                },
                                enabled = !busy,
                                shape = InstantControlShape,
                                colors = ButtonDefaults.buttonColors(containerColor = CanonTaxi),
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            ) {
                                Text(
                                    appText("Искать и в классе «$title»", "«$title» класында ла эҙләргә"),
                                    color = CanonBg, fontSize = TxBody, lineHeight = LhBody,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------ «Водитель едет» ------------------------------
@Composable
internal fun InstantDriverEnRouteCard(
    order: InstantOrderDto,
    onCancel: () -> Unit,
    enableLiveTracking: Boolean = true,
    mapContent: (@Composable (Modifier) -> Unit)? = null,
) {
    val ctx = LocalContext.current
    // Смена адреса: шторка выбора нового места. Доступна всю активную поездку —
    // «передумал» случается и пока водитель едет, и уже в машине.
    var showChangeDestination by remember { mutableStateOf(false) }
    // Правка остановок на ходу: «заедем ещё в аптеку» или «мама сама доехала».
    var addingStopEnRoute by remember { mutableStateOf(false) }
    val stopsScope = rememberCoroutineScope()
    // Семантика фаз (§5, структура как Яндекс): accepted = водитель едет к тебе,
    // arriving = «Я на месте» (машина ждёт — тикает ожидание), onboard = в пути.
    val phaseTitle = when (order.status) {
        "accepted" -> appText("Водитель едет к тебе", "Йөрөтөүсе һиңә килә")
        "arriving" -> appText("Машина на месте", "Машина урынында")
        "onboard" -> appText("В пути", "Юлда")
        else -> appText("Водитель едет", "Йөрөтөүсе килә")
    }
    // Шаг для полоски фаз: тот же смысл, что и заголовок, но читается без чтения.
    val phaseStep = when (order.status) {
        "arriving" -> 2
        "onboard" -> 3
        else -> 1
    }
    var confirmPaidCancel by remember { mutableStateOf(false) }
    var showShare by remember(order.id) { mutableStateOf(false) }   // «Поделиться поездкой» (B7b-2)
    // Цена отмены — с копейками: её называют на кнопке и в подтверждении, а платят наличными.
    val cancelFeeText = formatTaxiKop(order.cancelFeeNowKop)
    // Live-трек машины (B7a-3): пока заказ активен — держим WS такси-заказа и двигаем маркер.
    // Колбэк приходит с потока OkHttp — snapshot-state потокобезопасен. Ушли с экрана → close.
    var carPoint by remember(order.id) { mutableStateOf<Point?>(null) }
    var carBearing by remember(order.id) { mutableStateOf<Double?>(null) }
    DisposableEffect(order.id, enableLiveTracking) {
        if (!enableLiveTracking) return@DisposableEffect onDispose { }
        val socket = com.yuldash.app.data.InstantLocationSocket(order.id, onPeer = { peer ->
            if (peer.role == "driver") {
                carPoint = Point(peer.lat, peer.lng)
                carBearing = peer.bearing
            }
        }).also { it.connect() }
        onDispose { socket.close() }
    }
    Column(Modifier.fillMaxSize()) {
        val activeMapModifier = Modifier.fillMaxWidth().weight(0.36f)
        if (mapContent != null) {
            mapContent(activeMapModifier)
        } else {
            InstantRouteMap(
                from = Point(order.fromLat, order.fromLng),
                to = Point(order.toLat, order.toLng),
                modifier = activeMapModifier,
                car = carPoint,
                carBearing = carBearing,
            )
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = CanonSurface),
            shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
            // Это шторка снизу, а не карточка в потоке: ей положено отделяться от карты,
            // поэтому берём ступень sheet, а не card. Значение из шкалы, не с потолка.
            elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.sheet),
            modifier = Modifier.fillMaxWidth().weight(0.64f),
        ) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(CanonSpace.lg).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // «Где я сейчас» одним взглядом — до того, как человек начнёт читать надписи.
                InstantTripPhaseBar(step = phaseStep)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Navigation, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    // Смена фазы — главное событие этого экрана. Мгновенная подмена надписи
                    // («едет» → «на месте») читалась как сбой; теперь это движение вверх.
                    AnimatedContent(
                        targetState = phaseTitle,
                        transitionSpec = {
                            (fadeIn(tween(CanonMotion.NORMAL)) + slideInVertically(tween(CanonMotion.SLOW)) { it / 3 })
                                .togetherWith(fadeOut(tween(CanonMotion.QUICK)))
                        },
                        modifier = Modifier.weight(1f),
                        label = "enroutePhaseTitle",
                    ) { title ->
                        Text(
                            title, color = CanonText, fontSize = TxTitle, lineHeight = LhTitle,
                            fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (order.etaMin > 0 && order.status != "onboard") {
                        Spacer(Modifier.width(8.dp))
                        // «Сколько ещё ждать» — второе по важности число после статуса.
                        // Меняется плавно: скачущие минуты выглядят как ошибка связи.
                        AnimatedContent(targetState = order.etaMin.toInt(), label = "enrouteEta") { m ->
                            Text(
                                appText("≈ $m мин", "≈ $m мин"), color = CanonGreen2,
                                fontSize = TxBody, lineHeight = LhBody, fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
                TaxiTripProgress(status = order.status)
                // MapView не участвует в TalkBack-дереве как текстовый маршрут: даём человеку
                // явные точки А/Б, которые читаются одним осмысленным описанием.
                MobilityRouteTimeline(
                    from = order.fromText,
                    to = order.toText,
                    compact = true,
                )
                // Карточка водителя
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = CircleShape, color = CanonBg, modifier = Modifier.size(46.dp)) {
                        Icon(Icons.Default.Person, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(12.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                order.driverName.ifBlank { appText("Водитель", "Йөрөтөүсе") },
                                color = CanonText, fontSize = TxBody, lineHeight = LhBody,
                                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (order.driverVerified) {
                                Spacer(Modifier.width(4.dp))
                                Icon(Icons.Default.Verified, contentDescription = appText("Проверен", "Тикшерелгән"), tint = CanonGreen2, modifier = Modifier.size(16.dp))
                            }
                        }
                        val sub = buildList {
                            if (order.driverRating > 0) add("★ ${String.format(java.util.Locale.US, "%.1f", order.driverRating)}")
                            if (order.driverCar.isNotBlank()) add(order.driverCar)
                        }.joinToString("  ·  ")
                        if (sub.isNotBlank()) Text(
                            sub, color = CanonMuted, fontSize = TxCaption, lineHeight = LhCaption,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        // ГОСНОМЕР — по нему во дворе узнают машину. «Белая Гранта» не помогает,
                        // когда во дворе три белых Гранты; номер отдаётся только после accept.
                        if (order.driverPlate.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Surface(color = CanonTaxiBg, shape = RoundedCornerShape(8.dp)) {
                                Text(
                                    order.driverPlate,
                                    // CanonTaxiText, а НЕ CanonTaxiInk: ink рассчитан на жёлтый CanonTaxi,
                                    // а здесь подложка CanonTaxiBg — в тёмной теме тёмно-коричневая, и
                                    // номер на ней давал контраст 1.05, то есть был не виден совсем.
                                    color = CanonTaxiText, fontSize = TxBody, lineHeight = LhBody,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                    // «Изменить адрес». Маршрут выбирает тот, кто едет: пассажир меняет сам,
                    // без разрешения. Спрашиваем водителя только если поездка стала
                    // междугородной или цена выросла втрое — это уже другая работа.
                    if (order.status in setOf("accepted", "arriving", "onboard")) {
                        Surface(
                            modifier = Modifier.minimumInteractiveComponentSize(),
                            onClick = { showChangeDestination = true },
                            shape = CircleShape, color = CanonMint,
                        ) {
                            Icon(
                                Icons.Default.SwapHoriz,
                                contentDescription = appText("Изменить адрес", "Адресты үҙгәртеү"),
                                tint = CanonGreen2,
                                modifier = Modifier.padding(12.dp).size(20.dp),
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    // «Написать» (B7b-1): чат заказа — не звоня, уточнить подъезд/этаж/ориентир.
                    Surface(
                        // Тач-цель 48dp (CLAUDE.md §4.5): иконка 20dp с отступом 12dp даёт 44dp.
                        // Пассажир жмёт это на улице, водитель — за рулём; шесть пикселей тут
                        // не косметика. Модификатор расширяет ОБЛАСТЬ НАЖАТИЯ, не меняя вид.
                        modifier = Modifier.minimumInteractiveComponentSize(),
                        onClick = { NavSignals.openInstantChat.value = order.id },
                        shape = CircleShape, color = CanonMint,
                    ) {
                        Icon(Icons.Default.ChatBubble, contentDescription = appText("Написать водителю", "Йөрөтөүсегә яҙырға"), tint = CanonGreen2, modifier = Modifier.padding(12.dp).size(20.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    // Телефон — ТОЛЬКО после accept (сервер отдаёт его непустым).
                    if (order.driverPhone.isNotBlank()) {
                        Surface(
                            modifier = Modifier.minimumInteractiveComponentSize(),   // тач-цель 48dp, см. выше
                            onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${order.driverPhone}"))) } },
                            shape = CircleShape, color = CanonGreen2,
                        ) {
                            Icon(Icons.Default.Phone, contentDescription = appText("Позвонить водителю", "Йөрөтөүсегә шылтыратыу"), tint = CanonBg, modifier = Modifier.padding(12.dp).size(20.dp))
                        }
                    }
                }
                // Сколько отдать водителю. Раньше в активном заказе суммы не было вообще: человек
                // садился в машину и вспоминал цену по памяти, а со скидкой по промокоду это
                // прямой спор на дороге. Показываем ровно то, что уходит из рук.
                if (order.hasPromoDiscount) {
                    TaxiPromoPayRow(order = order, forDriver = false)
                } else {
                    Surface(color = CanonBg, shape = CanonItemShape, modifier = Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    appText("К оплате водителю", "Йөрөтөүсегә түләргә"),
                                    color = CanonMuted, fontSize = TxCaption, lineHeight = LhCaption,
                                )
                                Text(
                                    appText("наличными или переводом", "аҡсалата йәки күсереп"),
                                    color = CanonMuted, fontSize = TxCaption, lineHeight = LhCaption,
                                )
                            }
                            Text(
                                formatTaxiKop(order.passengerPayKop),
                                color = CanonText, fontSize = TxBody, lineHeight = LhBody,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
                // «Я на месте» → живой таймер ожидания (бесплатное окно → платно).
                if (order.status == "arriving") {
                    InstantWaitingRow(order)
                }
                // Водитель минуту не подтверждает, что видел новый адрес. Заставить его
                // посмотреть в телефон за рулём мы не можем — но сказать пассажиру правду
                // и дать кнопку звонка можем. Это честнее вечного спиннера.
                if (order.destinationAckOverdue) {
                    Surface(color = CanonWarnBg, shape = CanonItemShape,
                            modifier = Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().padding(CanonSpace.md),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(CanonSpace.sm),
                        ) {
                            Icon(Icons.Default.AccessTime, contentDescription = null, tint = CanonWarn)
                            Text(
                                appText("Водитель ещё не увидел новый адрес",
                                        "Йөрөтөүсе яңы адресты әле күрмәгән"),
                                style = CanonCaption, color = CanonText,
                                modifier = Modifier.weight(1f),
                            )
                            if (order.driverPhone.isNotBlank()) {
                                TextButton(onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + order.driverPhone))) } }) {
                                    Text(appText("Позвонить", "Шылтыратыу"), color = CanonGreen2)
                                }
                            }
                        }
                    }
                }

                // Остановки в пути. Добавлять можно всегда, убирать — тоже: «мама сама
                // доехала» это живой случай, цена при этом падает, водителю проще.
                // А порядок менять нельзя: водитель уже едет к первой точке, и перестановка
                // на ходу — путаница ради редкого случая.
                if (order.status in setOf("accepted", "arriving", "onboard")) {
                    Surface(color = CanonSurface, shape = CanonItemShape,
                            modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(CanonSpace.md),
                               verticalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
                            order.stops.forEachIndexed { i, st ->
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
                                    Icon(Icons.Default.Place, contentDescription = null,
                                         tint = if (st.done) CanonMuted else CanonGreen2,
                                         modifier = Modifier.size(18.dp))
                                    Text(st.text, style = CanonBody,
                                         color = if (st.done) CanonMuted else CanonText,
                                         maxLines = 1, overflow = TextOverflow.Ellipsis,
                                         modifier = Modifier.weight(1f))
                                    // Проеденную остановку убрать нельзя — уже проехали,
                                    // спорить не о чем.
                                    if (!st.done) {
                                        Surface(
                                            modifier = Modifier.minimumInteractiveComponentSize(),
                                            onClick = {
                                                stopsScope.launch {
                                                    ApiClient.setWaypoints(
                                                        order.id,
                                                        order.stops.filter { !it.done }
                                                            .filterIndexed { j, _ -> j != i },
                                                    )
                                                }
                                            },
                                            shape = CircleShape, color = CanonSurface,
                                        ) {
                                            Icon(Icons.Default.Close,
                                                 contentDescription = appText("Убрать остановку",
                                                                              "Туҡталышты алыу"),
                                                 tint = CanonMuted,
                                                 modifier = Modifier.padding(12.dp).size(18.dp))
                                        }
                                    }
                                }
                            }
                            TextButton(onClick = { addingStopEnRoute = true },
                                       modifier = Modifier.heightIn(min = 48.dp)) {
                                Icon(Icons.Default.Add, contentDescription = null,
                                     tint = CanonGreen2, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(CanonSpace.xs))
                                Text(
                                    if (order.stops.isEmpty()) appText("Заехать по пути", "Юлда инеп сығыу")
                                    else appText("Ещё остановка", "Тағы туҡталыш"),
                                    style = CanonBody, color = CanonGreen2,
                                )
                            }
                        }
                    }
                }
                if (addingStopEnRoute) {
                    PickStopSheet(
                        onDismiss = { addingStopEnRoute = false },
                        onPicked = { hit ->
                            addingStopEnRoute = false
                            stopsScope.launch {
                                ApiClient.setWaypoints(
                                    order.id,
                                    order.stops.filter { !it.done } +
                                        com.yuldash.app.data.TaxiStop(hit.lat, hit.lon, hit.title),
                                )
                            }
                        },
                    )
                }

                if (showChangeDestination) {
                    ChangeDestinationSheet(
                        order = order,
                        onDismiss = { showChangeDestination = false },
                        // Экран сам переспросит сервер через пару секунд — новую цену и адрес
                        // он возьмёт оттуда, а не из нашего локального предположения.
                        onChanged = {},
                    )
                }

                // Безопасность (B7b-2): SOS + «Поделиться поездкой с близким» — всю активную поездку.
                InstantSafetyRow(orderId = order.id, onShare = { showShare = true })
                if (order.status != "onboard") {
                    OutlinedButton(
                        // Поздняя отмена платная (подача) — честно предупреждаем ДО тапа.
                        onClick = { if (order.cancelFeeNowKop > 0) confirmPaidCancel = true else onCancel() },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = InstantControlShape,
                    ) {
                        Text(
                            if (order.cancelFeeNowKop > 0)
                                appText("Отменить · $cancelFeeText", "Кире алыу · $cancelFeeText")
                            else appText("Отменить заказ", "Заказды кире алыу"),
                            color = CanonRed, fontSize = TxBody, lineHeight = LhBody,
                        )
                    }
                }
            }
        }
    }
    if (showShare) {
        InstantShareDialog(orderId = order.id, onDismiss = { showShare = false })
    }
    if (confirmPaidCancel) {
        AlertDialog(
            onDismissRequest = { confirmPaidCancel = false },
            containerColor = CanonSurface,
            title = { Text(appText("Отмена сейчас платная", "Хәҙер кире алыу түләүле"), color = CanonText, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    appText(
                        "Водитель уже приехал и ждёт. Отмена — $cancelFeeText (подача), переведи водителю напрямую. Частые платные отмены ставят такси на паузу.",
                        "Йөрөтөүсе килде инде һәм көтә. Кире алыу — $cancelFeeText (килеү хаҡы), йөрөтөүсегә туранан күсер. Йыш түләүле кире алыуҙар таксиҙы паузаға ҡуя.",
                    ),
                    color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmPaidCancel = false; onCancel() }) {
                    Text(appText("Всё равно отменить", "Барыбер кире алыу"), color = CanonRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmPaidCancel = false }) {
                    Text(appText("Я выхожу", "Мин сығам"), color = CanonGreen2, fontWeight = FontWeight.Bold)
                }
            },
        )
    }
}

// ------------------------------ Причина отмены / отказа ------------------------------
/**
 * Одна строка выбора причины — общая для пассажира (диалог отмены) и для водителя (панель
 * «почему не взял»). Заводим её одну на два места специально: два почти одинаковых чипа
 * в одном файле через месяц разъезжаются по цвету и высоте, и экран начинает выглядеть
 * самодельным. Форма и тач-цель те же, что у остальных чипов файла (≥48dp).
 */
@Composable
private fun InstantReasonChip(
    label: String,
    modifier: Modifier = Modifier,
    picked: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    // Выбранная причина подсвечивается мятой не для красоты: палец должен получить ответ
    // «попал» раньше, чем экран успеет смениться. Переход цвета — плавный, без мигания.
    val bg by animateColorAsState(if (picked) CanonMint else CanonBg, tween(CanonMotion.QUICK), label = "reasonBg")
    val fg by animateColorAsState(if (picked) CanonGreen2 else CanonText, tween(CanonMotion.QUICK), label = "reasonFg")
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = bg,
        shape = InstantControlShape,
        border = BorderStroke(1.dp, if (picked) CanonGreen2 else CanonBorder),
        modifier = modifier.heightIn(min = 48.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Башкирская строка длиннее русской — потому высота минимальная, а не фиксированная:
            // текст переносится, а не обрезается.
            Text(
                label,
                color = fg, fontSize = TxBody, lineHeight = LhBody,
                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            )
        }
    }
}

/** Причины отмены пассажиром: код уходит на сервер, подпись — человеку. Порядок не случайный:
 *  сверху то, что случается чаще всего («долго ждать»), внизу «другое» — так почти никто не
 *  докручивает до конца списка, и ответ занимает одно касание. */
/** Группа причин отмены: заголовок и сами причины (код → надпись). */
internal data class CancelReasonGroup(val title: String, val reasons: List<Pair<String, String>>)

/**
 * Причины отмены ГРУППАМИ (образец — Яндекс, экран «Что случилось?»).
 *
 * Шесть чипов вперемешку человек читал целиком, а мы получали причину, по которой нельзя
 * понять, кто виноват: сервис не нашёл машину, водитель не поехал или планы изменились.
 * Заголовки решают обе задачи разом — глаз идёт сразу в свою группу, а разбор жалоб видит
 * претензии к водителю отдельно от «передумал».
 *
 * Коды остаются строками и уходят на сервер как есть (`reason`, до 200 символов).
 */
@Composable
private fun instantCancelGroups(): List<CancelReasonGroup> = listOf(
    CancelReasonGroup(
        appText("По моей инициативе", "Үҙ теләгем менән"),
        listOf(
            "plans_changed" to appText("Планы изменились", "Пландар үҙгәрҙе"),
            "wrong_address" to appText("Ошибся адресом", "Адресты яңылыш яҙғанмын"),
            "found_other" to appText("Уехал другим способом", "Башҡа юл менән киттем"),
        ),
    ),
    CancelReasonGroup(
        appText("Из-за водителя", "Йөрөтөүсе арҡаһында"),
        listOf(
            "driver_not_moving" to appText("Не двигается", "Ҡуҙғалмай"),
            "driver_asked_more" to appText("Просил больше денег", "Күберәк аҡса һораны"),
            "driver_refused" to appText("Отказался везти", "Илтеүҙән баш тартты"),
            "driver_silent" to appText("Не отвечает", "Яуап бирмәй"),
        ),
    ),
    CancelReasonGroup(
        appText("Не понравился сервис", "Хеҙмәт оҡшаманы"),
        listOf(
            "long_wait" to appText("Долго ждать машину", "Машинаны оҙаҡ көтөргә"),
            "price" to appText("Дорого", "Ҡиммәт"),
            "other" to appText("Другая причина", "Башҡа сәбәп"),
        ),
    ),
)

/**
 * «Почему отменяешь?» — короткий вопрос перед отменой заказа.
 *
 * Причина НИКОГДА не стоит между человеком и отменой: «Пропустить» гасит заказ сразу, тап мимо
 * окна возвращает к заказу целым. Отмена важнее статистики — если бы вопрос мог заблокировать
 * отмену, человек на морозе остался бы с ненужной машиной ради нашего графика.
 *
 * Если отмена уже платная, сумма подачи стоит прямо в этом окне: предупреждение о деньгах в
 * «Водитель едет» показалось раньше, и прятать сумму за вопросом про статистику нельзя —
 * человек подтверждает, глядя на цифру, а не по памяти.
 *
 * @param feeKop плата за подачу в копейках; 0 — отмена бесплатная, полоса про деньги не рисуется.
 */
@Composable
private fun InstantCancelReasonDialog(
    feeKop: Int,
    onPick: (String) -> Unit,
    onSkip: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Material-диалог появляется мгновенно, без анимации. Поэтому содержимое «доезжает» само:
    // подъём на 20dp с проявлением — то же спокойное движение, что у смены фаз заказа выше.
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val appear by animateFloatAsState(if (shown) 1f else 0f, tween(CanonMotion.NORMAL), label = "cancelReasonAppear")
    // Защёлка от второго касания: отмена — необратимое действие, и на медленном телефоне палец
    // успевает нажать дважды, пока окно закрывается. Второй тап должен уйти в никуда.
    var picked by remember { mutableStateOf<String?>(null) }
    val groups = instantCancelGroups()
    val feeText = formatTaxiKop(feeKop)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CanonSurface,
        shape = CanonCardShape,
        title = {
            Text(
                appText("Почему отменяешь?", "Ниңә кире алаһың?"),
                color = CanonText, fontSize = TxTitle, lineHeight = LhTitle, fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .graphicsLayer { alpha = appear; translationY = (1f - appear) * 20.dp.toPx() },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Деньги — первой строкой. Человек уже подтвердил их в предыдущем окне, но
                // между подтверждением и последним касанием сумма не должна исчезать с экрана.
                if (feeKop > 0) {
                    Surface(color = CanonWarnBg, shape = CanonItemShape, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            appText(
                                "Отмена сейчас платная: $feeText за подачу — переведи водителю.",
                                "Хәҙер кире алыу түләүле: килеү өсөн $feeText — йөрөтөүсегә күсер.",
                            ),
                            color = CanonWarn, fontSize = TxCaption, lineHeight = LhCaption,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                        )
                    }
                }
                Text(
                    appText(
                        "Ответ не обязателен. Он нужен нам, чтобы машины подъезжали быстрее.",
                        "Яуап мотлаҡ түгел. Ул машиналар тиҙерәк килһен өсөн кәрәк.",
                    ),
                    color = CanonMuted, fontSize = TxCaption, lineHeight = LhCaption,
                )
                // Группами с заголовками (образец — Яндекс): глаз идёт сразу в свою группу,
                // а разбор жалоб видит претензии к водителю отдельно от «планы изменились».
                groups.forEach { group ->
                    Text(
                        group.title,
                        color = CanonMuted, fontSize = TxCaption, lineHeight = LhCaption,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    group.reasons.forEach { (code, label) ->
                        InstantReasonChip(
                            label = label,
                            picked = picked == code,
                            enabled = picked == null,
                            modifier = Modifier.fillMaxWidth(),
                            // Одно касание = и причина, и отмена. Второй кнопки «подтвердить»
                            // тут нет сознательно: решение человек уже принял на прошлом экране.
                            onClick = { picked = code; onPick(code) },
                        )
                    }
                }
                Text(
                    appText(
                        "Не хочешь отвечать — нажми «Пропустить», заказ всё равно отменится.",
                        "Яуап бирергә теләмәһәң — «Үткәреп ебәреү»гә баҫ, заказ барыбер кире алына.",
                    ),
                    color = CanonMuted, fontSize = TxCaption, lineHeight = LhCaption,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = picked == null,
                onClick = onSkip,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(
                    appText("Пропустить", "Үткәреп ебәреү"),
                    color = CanonRed, fontSize = TxBody, lineHeight = LhBody, fontWeight = FontWeight.Bold,
                )
            }
        },
        dismissButton = {
            TextButton(
                enabled = picked == null,
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(
                    appText("Не отменять", "Кире алмайым"),
                    color = CanonGreen2, fontSize = TxBody, lineHeight = LhBody, fontWeight = FontWeight.Bold,
                )
            }
        },
    )
}

// ------------------------------ Безопасность в поездке (B7b-2) ------------------------------
/** SOS + «Поделиться поездкой»: доверие = продукт (§8). Обе кнопки ≥48dp, спокойные цвета. */
@Composable
private fun InstantSafetyRow(orderId: Int, onShare: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { NavSignals.openSosForOrder.value = orderId },
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            shape = InstantControlShape,
            border = BorderStroke(1.dp, CanonRed.copy(alpha = 0.5f)),
        ) {
            Icon(
                Icons.Default.Sos,
                contentDescription = appText("Экстренная помощь", "Ашығыс ярҙам"),
                tint = CanonRed, modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                appText("SOS", "SOS"), color = CanonRed,
                fontSize = TxBody, lineHeight = LhBody, fontWeight = FontWeight.Bold,
            )
        }
        if (onShare != null) {
            OutlinedButton(
                onClick = onShare,
                modifier = Modifier.weight(1.6f).heightIn(min = 48.dp),
                shape = InstantControlShape,
            ) {
                Icon(
                    Icons.Default.IosShare,
                    contentDescription = appText("Поделиться поездкой с близким", "Сәфәр менән яҡыныңа бүлешеү"),
                    tint = CanonGreen2, modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    appText("Поделиться поездкой", "Сәфәр менән бүлешеү"), color = CanonGreen2,
                    fontSize = TxBody, lineHeight = LhBody, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    // Зимний протокол для такси: мягче SOS, но реальный. Раньше работал только для попуток,
    // хотя четыре часа трассы Сибай–Уфа зимой — это как раз такси (аудит 2026-07-26).
    // Сам блок общий на три сценария (RoadsideHelp.kt): две копии уже разошлись текстами.
    RoadsideHelpAction(key = orderId) { lat, lng ->
        ApiClient.instantRoadsideHelp(orderId, lat, lng)
    }
}

/** Выбор близкого для шаринга такси-заказа: близкий получит SMS со ссылкой live-поездки (B7c),
 *  а пассажиру тут же показываем ссылку — скопировать или отправить самому (share-sheet).
 *  Состояния честные: загрузка / пусто (подсказка добавить контакт) / список / ошибка / ссылка. */
@Composable
internal fun InstantShareDialog(orderId: Int, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var contacts by remember { mutableStateOf<List<com.yuldash.app.data.ContactDto>?>(null) }
    var loadError by remember { mutableStateOf(false) }
    // Это единственный путь дать близкому live-ссылку во время поездки: ошибка без «Повторить»
    // была тупиком прямо посреди дороги (аудит P1-7).
    var contactsTick by remember { mutableIntStateOf(0) }
    var liveLink by remember { mutableStateOf<String?>(null) }   // ссылка после share (B7c)
    // Приватность: активные ссылки этого заказа (сервер отдаёт GET shares) + отозвать.
    var activeShares by remember { mutableStateOf<List<com.yuldash.app.data.TripShareDto>>(emptyList()) }
    val sharedMsg = appText("Близкий получит SMS о поездке", "Яҡын кеше сәфәр тураһында SMS алыр")
    val shareFailMsg = appText("Не получилось. Повтори.", "Булманы. Ҡабатла.")
    val revokedMsg = appText("Ссылка отозвана", "Һылтанма кире алынды")
    LaunchedEffect(contactsTick) {
        contacts = null; loadError = false   // повтор начинается с честной загрузки, а не с ошибки
        ApiClient.getContacts()
            .onSuccess { contacts = it }
            .onFailure { loadError = true; contacts = emptyList() }
        ApiClient.getInstantShares(orderId).onSuccess { activeShares = it }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CanonSurface,
        title = {
            Text(
                if (liveLink != null) appText("Ссылка для близкого", "Яҡын кеше өсөн һылтанма")
                else appText("Поделиться поездкой", "Сәфәр менән бүлешеү"),
                color = CanonText, fontWeight = FontWeight.Bold,
            )
        },
        text = {
            val list = contacts
            val link = liveLink
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when {
                    link != null -> LiveLinkCard(link)
                    list == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = CanonGreen2)
                        Spacer(Modifier.width(8.dp))
                        Text(appText("Загружаем близких…", "Яҡындарҙы йөкләйбеҙ…"), color = CanonMuted, fontSize = 14.sp)
                    }
                    loadError -> AppErrorState(
                        onRetry = { contactsTick++ },
                        title = appText("Не удалось загрузить близких", "Яҡындарҙы йөкләп булманы"),
                        text = appText(
                            "Проверь интернет и повтори — список никуда не пропал.",
                            "Интернетты тикшереп ҡабатла — исемлек юғалманы.",
                        ),
                    )
                    list.isEmpty() -> Text(appText("Добавь близкого в «Доверенные контакты» в профиле — и делись поездкой в одно касание.",
                        "Профилдә «Ышаныслы кешеләр»гә яҡыныңды өҫтә — сәфәр менән бер баҫыуҙа бүлеш."), color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp)
                    else -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        list.forEach { c ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                                    .clickable {
                                        scope.launch {
                                            ApiClient.shareInstantTrip(orderId, c.id)
                                                .onSuccess { share ->
                                                    Toast.makeText(ctx, "$sharedMsg: ${c.name}", Toast.LENGTH_SHORT).show()
                                                    if (share != null) {
                                                        activeShares = activeShares.filterNot { it.id == share.id } + share
                                                        if (!share.link.isNullOrBlank()) liveLink = share.link
                                                    } else onDismiss()
                                                }
                                                .onFailure {
                                                    onDismiss()
                                                    Toast.makeText(ctx, serverSaid(it, shareFailMsg), Toast.LENGTH_LONG).show()
                                                }
                                        }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Default.Person, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(c.name, color = CanonText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                    if (c.relation.isNotBlank()) Text(c.relation, color = CanonMuted, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
                // Приватность (B7c): активные ссылки заказа + «Отозвать» (сгорит /t/{token}, SMS-статусы стоп).
                if (activeShares.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(appText("Активные ссылки", "Әүҙем һылтанмалар"), color = CanonMuted, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    activeShares.forEach { share ->
                        val name = contacts?.firstOrNull { it.id == share.contactId }?.name
                            ?: appText("Близкий", "Яҡын кеше")
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Person, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(name, color = CanonText, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1)
                            TextButton(onClick = {
                                scope.launch {
                                    ApiClient.revokeInstantShare(orderId, share.id)
                                        .onSuccess {
                                            activeShares = activeShares.filterNot { it.id == share.id }
                                            Toast.makeText(ctx, revokedMsg, Toast.LENGTH_SHORT).show()
                                        }
                                        .onFailure { Toast.makeText(ctx, serverSaid(it, shareFailMsg), Toast.LENGTH_LONG).show() }
                                }
                            }, modifier = Modifier.heightIn(min = 44.dp)) {
                                Text(appText("Отозвать", "Кире алыу"), color = CanonRed, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (liveLink != null) TextButton(onClick = { liveLink = null }) {
                Text(appText("Поделиться ещё", "Йәнә бүлешеү"), color = CanonGreen2, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(appText("Закрыть", "Ябыу"), color = CanonMuted) }
        },
    )
}

// ------------------------------ «Рядом никого» (expired) ------------------------------
/**
 * Свободных водителей нет. Раньше это был тупик: отказ приходил за две секунды и всё, повтора
 * поиска не существовало. В райцентре ночью на линии 2–3 водителя и оба заняты — это норма, а не
 * исключение: человек получал отказ и уходил к конкуренту (аудит 2026-07-26).
 *
 * Теперь главное действие — «Подожду машину»: заказ встаёт в очередь, фоновый воркер сам
 * перезапускает поиск и пришлёт пуш, как только машина найдётся. Ручной повтор остаётся рядом.
 */
@Composable
private fun InstantNoDriversCard(
    order: InstantOrderDto,
    onRetry: () -> Unit,
    onDone: () -> Unit,
    onOrderUpdated: (InstantOrderDto) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var busy by remember(order.id) { mutableStateOf(false) }
    // Сервер уже мог поставить заказ в очередь (вернулись на экран) — тогда сразу «ждём».
    var waitMinutes by remember(order.id) { mutableIntStateOf(if (order.waitUntil.isNullOrBlank()) 0 else -1) }
    var err by remember(order.id) { mutableStateOf<String?>(null) }
    val waiting = waitMinutes != 0
    val errFallback = appText("Не получилось. Проверь сеть и повтори.", "Булманы. Селтәрҙе тикшереп ҡабатла.")

    InstantFinalCard(
        // Два РАЗНЫХ состояния, и выглядеть они обязаны по-разному. «Рядом никого» — тупик,
        // из которого нужен выход (красное такси). «Ищем дальше» — работа идёт, человек может
        // убрать телефон в карман (зелёные часы + живая полоска ниже).
        icon = if (waiting) Icons.Default.AccessTime else Icons.Default.LocalTaxi,
        tone = if (waiting) InstantTone.Good else InstantTone.Bad,
        title = if (waiting) appText("Ищем машину дальше", "Машинаны эҙләүҙе дауам итәбеҙ")
        else appText("Рядом пока никого", "Яҡында әлегә бер кем дә юҡ"),
        subtitle = when {
            // waitMinutes = -1 → пришли на экран с уже поставленной очередью, точный срок не знаем.
            waiting && waitMinutes > 0 -> appText(
                "Будем искать ещё $waitMinutes минут. Как машина найдётся — сразу пришлём уведомление, приложение можно закрыть.",
                "Тағы $waitMinutes минут эҙләйбеҙ. Машина табылыу менән хәбәр итәбеҙ, ҡулланманы ябырға була.",
            )
            waiting -> appText(
                "Поиск продолжается. Как машина найдётся — сразу пришлём уведомление, приложение можно закрыть.",
                "Эҙләү дауам итә. Машина табылыу менән хәбәр итәбеҙ, ҡулланманы ябырға була.",
            )
            // Выбор «только женщина за рулём» сужает круг машин, и человек имеет право знать,
            // что дело в этом, а не в поломке приложения. Молча снять его мы не можем: тогда
            // галочка ничего не значила бы (аудит 2026-08-06).
            order.womenOnly -> appText(
                "Свободных женщин-водителей рядом не нашли. Мы не подставим вместо них другого водителя — ты просила именно женщину. Можем подождать: как только кто-то освободится, пришлём уведомление.",
                "Яҡында буш ҡатын-ҡыҙ йөрөтөүсе табылманы. Уның урынына башҡа йөрөтөүсене тәҡдим итмәйбеҙ — һин нәҡ ҡатын-ҡыҙ һораның. Көтә алабыҙ: берәйһе бушаныу менән хәбәр итәбеҙ.",
            )
            else -> appText(
                "Свободных водителей рядом не нашли. Можем подождать — как только кто-то освободится, пришлём уведомление.",
                "Яҡында буш йөрөтөүсе табылманы. Көтә алабыҙ — берәйһе бушаныу менән хәбәр итәбеҙ.",
            )
        },
        action = if (waiting) appText("Заказать заново", "Яңынан заказ итеү")
        else appText("Попробовать ещё раз", "Ҡабат итеп ҡарау"),
        onAction = onRetry,
        onSecondary = onDone,
        extra = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Очередь ожидания: живая полоска — единственное доказательство, что поиск идёт,
                // когда экран статичен, а телефон лежит в кармане.
                if (waiting) InstantQueuePulse()
                if (!waiting) {
                    AppButton(
                        text = appText("Подожду машину", "Машинаны көтәм"),
                        onClick = {
                            if (busy) return@AppButton
                            busy = true; err = null
                            scope.launch {
                                ApiClient.waitForDriver(order.id)
                                    .onSuccess { w ->
                                        waitMinutes = if (w.waitMinutes > 0) w.waitMinutes else -1
                                        w.order?.let(onOrderUpdated)
                                    }
                                    .onFailure { err = (it as? ApiException)?.message ?: errFallback }
                                busy = false
                            }
                        },
                        icon = Icons.Default.AccessTime,
                        loading = busy,
                    )
                }
                err?.let {
                    Text(
                        it, color = CanonRed, fontSize = TxCaption, lineHeight = LhCaption,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
    )
}

/**
 * «Мы всё ещё ищем» для очереди ожидания. Неопределённая полоска — честный сигнал: срок
 * неизвестен, но работа идёт. Показываем ТОЛЬКО в очереди: в состоянии «рядом никого» такая
 * же полоска врала бы, что поиск продолжается.
 */
@Composable
private fun InstantQueuePulse() {
    Surface(shape = CanonItemShape, color = CanonMint, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                appText("Поиск идёт прямо сейчас", "Эҙләү нәҡ хәҙер бара"),
                color = CanonGreen2, fontSize = TxCaption, lineHeight = LhCaption,
                fontWeight = FontWeight.Bold,
            )
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                color = CanonGreen2, trackColor = CanonBorder,
            )
        }
    }
}

/**
 * Ссылка «Чек за поездку» в финальной карточке заказа. Открывает [TaxiReceiptScreen] через
 * [NavSignals] — карточка живёт глубоко в экране такси (в т.ч. встроенном в главную),
 * тянуть колбэк через все слои ради одной кнопки не стоит.
 */
/**
 * «Открыть разбор» по завершённому такси-заказу. Отличается от жалобы: жалоба анонимна и
 * односторонняя, а разбор двусторонний — вторую сторону позовут объясниться, и решение
 * объяснят обоим. До этого раунда спор по такси был технически невозможен: публичная ручка
 * принимала только бронь попутки (аудит 2026-07-26).
 */
@Composable
private fun TaxiDisputeLink(order: InstantOrderDto, isDriver: Boolean) {
    var open by remember(order.id) { mutableStateOf(false) }
    var filed by remember(order.id) { mutableStateOf(false) }
    // Кому предъявляем: пассажир — водителю, водитель — пассажиру. Нет второй стороны → нечего разбирать.
    val respondentId = if (isDriver) order.passengerId else order.driverId
    val respondentName = if (isDriver) order.passengerName.ifBlank { appText("пассажира", "юлаусыны") }
    else order.driverName.ifBlank { appText("водителя", "йөрөтөүсене") }
    if (respondentId == null || respondentId <= 0) return

    if (filed) {
        Surface(color = CanonMint, shape = CanonItemShape) {
            Text(
                appText(
                    "Разбор открыт. Мы позовём вторую сторону объясниться и напишем решение вам обоим.",
                    "Ҡарау асылды. Икенсе яҡты аңлатырға саҡырабыҙ һәм ҡарарҙы икегеҙгә лә яҙабыҙ.",
                ),
                color = CanonGreen2, fontSize = 14.sp, lineHeight = 20.sp,
                modifier = Modifier.fillMaxWidth().padding(12.dp),
            )
        }
        return
    }
    TextButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
        Text(appText("Открыть разбор", "Ҡарауҙы асыу"), color = CanonMuted, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
    if (open) {
        FileIncidentDialog(
            respondentId = respondentId,
            respondentName = respondentName,
            orderId = order.id,
            onDismiss = { open = false },
            onFiled = { open = false; filed = true },
        )
    }
}

@Composable
private fun TaxiReceiptLink(orderId: Int) {
    OutlinedButton(
        onClick = { NavSignals.openTaxiReceipt.value = orderId },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = InstantControlShape,
    ) {
        Icon(
            Icons.Default.IosShare,
            contentDescription = appText("Открыть чек за поездку", "Сәфәр чеген асыу"),
            tint = CanonGreen2, modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            appText("Чек за поездку", "Сәфәр чегы"), color = CanonGreen2,
            fontSize = TxBody, lineHeight = LhBody, fontWeight = FontWeight.Bold,
            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        )
    }
}

// ------------------------------ Общая финальная карточка ------------------------------
@Composable
private fun InstantFinalCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    // Крупное число итога — цена завершённой поездки. Человек открывает этот экран, чтобы
    // проверить «сколько», а не перечитать адреса, поэтому сумма стоит выше маршрута и
    // доминирует над ним. null — карточка без суммы (отмена, «заказ не найден»).
    hero: String? = null,
    subtitle: String,
    action: String,
    onAction: () -> Unit,
    onSecondary: () -> Unit,
    // Цвет кружка с иконкой. Good — зелёный (успех, ожидание), Bad — красный (отмена, «не найден»).
    tone: InstantTone = InstantTone.Good,
    extra: (@Composable () -> Unit)? = null,   // §9: блок оценки/жалобы после done
) {
    val accent = if (tone == InstantTone.Bad) CanonRed else CanonGreen2
    val accentBg = if (tone == InstantTone.Bad) CanonDangerBg else CanonMint
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(shape = CircleShape, color = accentBg, modifier = Modifier.appearIn(0)) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.padding(24.dp).size(40.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(
            title, color = CanonText, fontSize = TxTitle, lineHeight = LhTitle,
            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            modifier = Modifier.appearIn(1),
        )
        if (hero != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                hero, color = accent, fontSize = TxHero, lineHeight = LhHero,
                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                modifier = Modifier.appearIn(2),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            subtitle, color = CanonMuted, fontSize = TxBody, lineHeight = LhBody,
            textAlign = TextAlign.Center, modifier = Modifier.appearIn(3),
        )
        if (extra != null) {
            Spacer(Modifier.height(16.dp))
            Column(Modifier.fillMaxWidth().appearIn(4)) { extra() }
        }
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onAction,
            // heightIn, а не height: при системном крупном шрифте фиксированная высота срезает надпись.
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).appearIn(5),
            shape = InstantControlShape,
            colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2),
        ) {
            Text(action, fontSize = TxBody, lineHeight = LhBody, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onSecondary, modifier = Modifier.heightIn(min = 48.dp).appearIn(6)) {
            Text(appText("Закрыть", "Ябыу"), color = CanonMuted, fontSize = TxBody, lineHeight = LhBody)
        }
    }
}

/**
 * §9 Качество: взаимная оценка завершённого заказа (звёзды, анонимно) + «Пожаловаться»
 * (категории из перечня, анонимно). Обе стороны: пассажир оценивает водителя, водитель —
 * пассажира. Плавное появление, тач-цели 40dp+, честная строка «оценка анонимна».
 */
@Composable
private fun InstantRateAndReport(order: InstantOrderDto, isDriver: Boolean) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var stars by remember(order.id) { mutableIntStateOf(0) }
    var rated by remember(order.id) { mutableStateOf(false) }
    var showReport by remember(order.id) { mutableStateOf(false) }
    val thanksMsg = appText("Спасибо за оценку!", "Баһа өсөн рәхмәт!")
    val rateFail = appText("Не получилось оценить. Проверь сеть.", "Баһалап булманы. Селтәрҙе тикшер.")
    val sentMsg = appText("Жалоба отправлена. Спасибо, разберёмся.", "Ялыу ебәрелде. Рәхмәт, тикшерербеҙ.")
    val sendFail = appText("Не удалось отправить. Проверь сеть.", "Ебәреп булманы. Селтәрҙе тикшер.")
    Card(colors = CardDefaults.cardColors(containerColor = CanonSurface), shape = CanonItemShape, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (isDriver) appText("Как прошла поездка с пассажиром?", "Пассажир менән сәфәр нисек үтте?")
                else appText("Как прошла поездка?", "Сәфәр нисек үтте?"),
                color = CanonText, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                (1..5).forEach { n ->
                    val filled = n <= stars
                    val scale by animateFloatAsState(if (filled) 1f else 0.86f, label = "star$n")
                    val starCd = starsText(n)
                    // Тач-цель ≥48dp (иконка визуально 40dp внутри), анимация масштаба сохранена.
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clickable {
                                stars = n
                                scope.launch {
                                    ApiClient.rateInstantOrder(order.id, n)
                                        .onSuccess { if (!rated) { rated = true; Toast.makeText(ctx, thanksMsg, Toast.LENGTH_SHORT).show() } }
                                        .onFailure { Toast.makeText(ctx, serverSaid(it, rateFail), Toast.LENGTH_LONG).show() }
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Star,
                            contentDescription = starCd,
                            tint = if (filled) CanonStar else CanonMuted,
                            modifier = Modifier.size(40.dp).graphicsLayer { scaleX = scale; scaleY = scale },
                        )
                    }
                }
            }
            Text(
                appText("Оценка анонимна — видно только средний рейтинг.", "Баһа аноним — тик уртаса рейтинг күренә."),
                color = CanonMuted, fontSize = 12.sp, textAlign = TextAlign.Center,
            )
            // «Рәхмәт» — сразу здесь, а не только в чеке: до чека нужно догадаться, а сказать
            // спасибо хочется в ту же минуту. Денег не двигаем: это жест, а не чаевые, и кнопки
            // с суммой у нас нет намеренно — платёж идёт мимо приложения, и такая кнопка врала бы.
            if (!isDriver) InstantThanksRow(orderId = order.id)
            TextButton(onClick = { showReport = true }) {
                Text(
                    if (isDriver) appText("Пожаловаться на пассажира", "Пассажирға ялыу")
                    else appText("Пожаловаться на водителя", "Йөрөтөүсегә ялыу"),
                    color = CanonMuted, fontSize = 14.sp,
                )
            }
        }
    }
    if (showReport) {
        ReportCategoryDialog(
            title = if (isDriver) appText("Жалоба на пассажира", "Пассажирға ялыу")
            else appText("Жалоба на водителя", "Йөрөтөүсегә ялыу"),
            categories = if (isDriver) reportCategoriesPassenger() else reportCategoriesDriver(),
            onDismiss = { showReport = false },
            onSend = { category, details ->
                showReport = false
                scope.launch {
                    ApiClient.reportUser(reason = details, category = category, orderId = order.id)
                        .onSuccess { Toast.makeText(ctx, sentMsg, Toast.LENGTH_SHORT).show() }
                        .onFailure { Toast.makeText(ctx, serverSaid(it, sendFail), Toast.LENGTH_LONG).show() }
                }
            },
        )
    }
}

/**
 * B8-8: мягкий баннер пассажиру после отмены, когда телефон/чат уже открывались
 * («увод мимо приложения»). Не обвиняем — по-добрососедски напоминаем про защиту и SOS.
 */
@Composable
internal fun ContactCancelSoftBanner(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().background(CanonMint, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            appText(
                "Договорились ехать? Заверши поездку в приложении — так работает защита и SOS 💚",
                "Барырға һөйләштегеҙме? Сәфәрҙе ҡушымтала тамамла — шулай яҡлау һәм SOS эшләй 💚",
            ),
            color = CanonGreen2, fontSize = 14.sp, lineHeight = 20.sp,
        )
    }
}


/**
 * B8-7: «Пассажир не заплатил» — одним тапом на экране завершённой поездки (такси и попутка).
 * Создаёт жалобу категории unpaid (сервер: только водитель, только done, дедуп — одна на
 * поездку) → пассажиру страйк по механике §5/§9 + пометка на заказе. Повторный тап безопасен.
 */
@Composable
internal fun UnpaidReportButton(orderId: Int? = null, bookingId: Int? = null, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var sent by remember(orderId, bookingId) { mutableStateOf(false) }
    var sending by remember(orderId, bookingId) { mutableStateOf(false) }
    val failMsg = appText("Не получилось отметить. Проверь сеть.", "Билдәләп булманы. Селтәрҙе тикшер.")
    if (sent) {
        Row(
            modifier = modifier.fillMaxWidth().background(CanonMint, RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                appText("Отмечено: пассажир не заплатил. Мы разберёмся.",
                        "Билдәләнде: пассажир түләмәгән. Беҙ тикшерербеҙ."),
                color = CanonGreen2, fontSize = 14.sp, lineHeight = 20.sp,
            )
        }
    } else {
        OutlinedButton(
            onClick = {
                if (sending) return@OutlinedButton
                sending = true
                scope.launch {
                    ApiClient.reportUser(category = "unpaid", orderId = orderId, bookingId = bookingId)
                        .onSuccess { sent = true }
                        .onFailure { Toast.makeText(ctx, serverSaid(it, failMsg), Toast.LENGTH_LONG).show() }
                    sending = false
                }
            },
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, CanonRed),
            modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(appText("Пассажир не заплатил", "Пассажир түләмәне"), color = CanonRed, fontWeight = FontWeight.Bold)
        }
    }
}


// Водитель отмечает неявку пассажира (no-show). Само-содержащая кнопка (как UnpaidReportButton):
// дёргает /bookings/{id}/no-show, при успехе показывает подтверждение на месте. Места возвращаются на сервере.
@Composable
internal fun NoShowButton(bookingId: Int, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var sent by remember(bookingId) { mutableStateOf(false) }
    var sending by remember(bookingId) { mutableStateOf(false) }
    val failMsg = appText("Не получилось отметить. Проверь сеть.", "Билдәләп булманы. Селтәрҙе тикшер.")
    if (sent) {
        Row(
            modifier = modifier.fillMaxWidth().background(CanonMint, RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                appText("Отмечена неявка. Место вернулось в поездку.",
                        "Килмәгәнлек билдәләнде. Урын сәфәргә ҡайтты."),
                color = CanonGreen2, fontSize = 14.sp, lineHeight = 20.sp,
            )
        }
    } else {
        OutlinedButton(
            onClick = {
                if (sending) return@OutlinedButton
                sending = true
                scope.launch {
                    ApiClient.markNoShow(bookingId)
                        .onSuccess { sent = true }
                        .onFailure { Toast.makeText(ctx, serverSaid(it, failMsg), Toast.LENGTH_LONG).show() }
                    sending = false
                }
            },
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, CanonRed),
            modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(appText("Пассажир не явился", "Пассажир килмәне"), color = CanonRed, fontWeight = FontWeight.Bold)
        }
    }
}


@Composable
private fun InstantCenterLoader(text: String) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator(color = CanonGreen2, strokeWidth = 3.dp)
        Spacer(Modifier.height(12.dp))
        Text(text, color = CanonMuted, fontSize = 14.sp)
    }
}

// ------------------------------ «Такси скоро» (гейт по флагу/городу) ------------------------------
private val WaitlistPhoneRegex = Regex("^\\+?\\d{10,15}$")   // как на сервере (family.py/waitlist.py)

/** Такси в этой точке пока выключено (глобальный запуск или город ещё не подключён).
 *  Тёплая заглушка вместо пикера + ранний доступ (§11): «оставь номер — сообщим, когда включим»
 *  и CTA для водителей «стань первым таксистом города». Успех — «Ты в списке! 🎉». */
@Composable
private fun TaxiComingSoonCard(
    availability: com.yuldash.app.data.TaxiAvailabilityDto,
    onBackToPooling: () -> Unit,
    onTaxiOnboarding: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val title = if (availability.reason == "global_off")
        appText("Такси Юлдаш совсем скоро 🚕", "Юлдаш таксиы бик тиҙҙән 🚕")
    else appText("Такси скоро в твоём городе 🚕", "Тиҙҙән таксиы һинең ҡалаңда ла 🚕")
    val serverMsg = appText(availability.messageRu, availability.messageBa)
    val body = (if (serverMsg.isNotBlank()) "$serverMsg\n\n" else "") + appText(
        "Мы подключаем города по очереди, чтобы машины точно были рядом. А попутка уже работает по всей республике.",
        "Ҡалаларҙы сиратлап тоташтырабыҙ — машиналар яҡында булһын өсөн. Ә юлдаш инде бөтә республикала эшләй.",
    )

    // Форма листа ожидания: телефон (предзаполнен у залогиненного), город (из availability), роль.
    var phone by rememberSaveable { mutableStateOf("") }
    var city by rememberSaveable { mutableStateOf(availability.city) }
    var role by rememberSaveable { mutableStateOf("passenger") }
    var sending by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val sendErr = appText("Не получилось отправить. Проверь сеть и повтори.", "Ебәреп булманы. Селтәрҙе тикшереп ҡабатла.")
    val badPhoneErr = appText("Проверь номер: 10–15 цифр, можно с +", "Номерҙы тикшер: 10–15 һан, + менән дә мөмкин")

    // Предзаполняем телефон из профиля (Telegram-плейсхолдер tg<id> не подставляем).
    LaunchedEffect(Unit) {
        if (ApiClient.isLoggedIn()) {
            ApiClient.me().onSuccess { me ->
                val p = me.optString("phone")
                if (phone.isBlank() && WaitlistPhoneRegex.matches(p.replace(" ", "").replace("-", ""))) phone = p
            }
        }
    }

    fun submit() {
        val normalized = phone.replace(Regex("[\\s\\-()]"), "")
        if (!WaitlistPhoneRegex.matches(normalized)) { error = badPhoneErr; return }
        sending = true; error = null
        scope.launch {
            ApiClient.joinWaitlist(normalized, city.trim(), role)
                .onSuccess { sent = true }
                .onFailure { error = sendErr }
            sending = false
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))
        Surface(shape = CircleShape, color = CanonTaxiBg) {
            Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) { Text("🚕", fontSize = 44.sp) }
        }
        Spacer(Modifier.height(16.dp))
        Text(title, color = CanonText, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))

        // ---------- Ранний доступ: форма или «Ты в списке!» ----------
        AnimatedVisibility(visible = sent, enter = fadeIn(tween(CanonMotion.SLOW)) + expandVertically(tween(CanonMotion.SLOW))) {
            Surface(color = CanonMint, shape = CanonCardShape, border = BorderStroke(1.dp, CanonGreen2), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(appText("Ты в списке! 🎉", "Һин исемлектә! 🎉"), color = CanonGreen2, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (role == "driver")
                            appText("Позовём одним из первых — 0% комиссии первые 3 месяца.", "Беренселәрҙән булып саҡырырбыҙ — тәүге 3 айҙа 0% комиссия.")
                        else appText("Сообщим, как только такси заработает в твоём городе.", "Такси һинең ҡалаңда эшләй башлағас та хәбәр итербеҙ."),
                        color = CanonText, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center,
                    )
                }
            }
        }
        AnimatedVisibility(visible = !sent, exit = fadeOut(tween(CanonMotion.QUICK)) + shrinkVertically(tween(CanonMotion.NORMAL))) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(color = CanonSurface, shape = CanonCardShape, border = BorderStroke(1.dp, CanonBorder), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            appText("Оставь номер — сообщим, когда включим", "Номерыңды ҡалдыр — ҡабыҙғас та хәбәр итербеҙ"),
                            color = CanonText, fontSize = 16.sp, fontWeight = FontWeight.Bold, lineHeight = 23.sp,
                        )
                        // Роль: пассажир / водитель (тач-цель ≥48dp).
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            WaitlistRoleChip(appText("Я пассажир", "Мин пассажир"), role == "passenger", Modifier.weight(1f)) { role = "passenger" }
                            WaitlistRoleChip(appText("Я водитель", "Мин йөрөтөүсе"), role == "driver", Modifier.weight(1f)) { role = "driver" }
                        }
                        OutlinedTextField(
                            value = phone,
                            onValueChange = { phone = it.take(20); error = null },
                            label = { Text(appText("Телефон", "Телефон")) },
                            placeholder = { Text("+7 9xx xxx-xx-xx") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                        )
                        OutlinedTextField(
                            value = city,
                            onValueChange = { city = it.take(40) },
                            label = { Text(appText("Город", "Ҡала")) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                        )
                        error?.let { Text(it, color = CanonRed, fontSize = 14.sp, lineHeight = 20.sp) }
                        Button(
                            onClick = { submit() },
                            enabled = phone.isNotBlank() && !sending,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2),
                        ) {
                            if (sending) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = CanonSurface)
                            else Text(appText("Записаться", "Яҙылыу"), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                // ---------- CTA для водителей: застолби город ----------
                Surface(color = CanonTaxiBg, shape = CanonCardShape, border = BorderStroke(1.dp, CanonTaxi), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            appText("Стань первым таксистом города 🚖", "Ҡаланың беренсе таксисы бул 🚖"),
                            color = CanonTaxiText, fontSize = 16.sp, fontWeight = FontWeight.Bold, lineHeight = 23.sp,
                        )
                        Text(
                            appText("Первым водителям — 0% комиссии первые 3 месяца. Оставь номер как водитель, и город твой.",
                                "Тәүге йөрөтөүселәргә — тәүге 3 айҙа 0% комиссия. Номерыңды йөрөтөүсе итеп ҡалдыр — ҡала һинеке."),
                            color = CanonTaxiText, fontSize = 14.sp, lineHeight = 20.sp,
                        )
                        if (role != "driver") {
                            OutlinedButton(
                                onClick = { role = "driver" },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, CanonTaxiText),
                            ) { Text(appText("Хочу возить", "Йөрөтөргә теләйем"), color = CanonTaxiText, fontWeight = FontWeight.Bold) }
                        }
                        // Такси уже включено (глобально), просто не в этом городе → проверку 580-ФЗ можно пройти заранее.
                        if (availability.reason == "city_off") {
                            TextButton(onClick = onTaxiOnboarding, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text(
                                    appText("Пройти проверку таксиста заранее →", "Таксист тикшереүен алдан үтергә →"),
                                    color = CanonTaxiText, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onBackToPooling,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2),
        ) {
            Icon(Icons.Default.DirectionsCar, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(appText("Поехали попуткой", "Юлдаш менән киттек"), fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** Чип выбора роли в форме листа ожидания (тач-цель ≥48dp). */
@Composable
private fun WaitlistRoleChip(label: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = if (active) CanonMint else CanonBg,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, if (active) CanonGreen2 else CanonBorder),
        modifier = modifier.height(48.dp),
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(label, color = if (active) CanonGreen2 else CanonMuted, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}

@Composable
private fun InstantLoginNeeded(onLoginRequired: () -> Unit) {
    InstantFinalCard(
        icon = Icons.Default.DirectionsCar,
        title = appText("Войди, чтобы заказать машину", "Машина заказлар өсөн ин"),
        subtitle = appText("Быстрый заказ доступен после входа — так водитель видит, кому ехать.",
            "Тиҙ заказ ингәндән һуң эшләй — йөрөтөүсе кемгә барырын күрә."),
        action = appText("Войти", "Инеү"),
        onAction = onLoginRequired,
        onSecondary = onLoginRequired,
    )
}

// ==================================== ВОДИТЕЛЬ ====================================
/**
 * Контроллер водителя «на линии»: пока online — шлём presence-heartbeat (координаты, ~раз в 12с) и
 * опрашиваем входящий оффер (~раз в 4с). Пришёл оффер → полноэкранная карточка с таймером.
 * «Взять» → accept → onOpenTrip(orderId); «Пропустить» → decline. Встраивается в кабинет водителя.
 */
@Composable
internal fun InstantDriverOnlineController(online: Boolean, onOpenTrip: (Int) -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val myPoint by rememberMyPoint(active = online)
    var offer by remember { mutableStateOf<InstantOrderDto?>(null) }
    var accepting by remember { mutableStateOf(false) }
    var presenceFails by remember { mutableIntStateOf(0) }   // подряд-неудачи heartbeat → «нет связи»
    // Заказ, от которого водитель ТОЛЬКО ЧТО отказался и по которому мы необязательным шагом
    // спрашиваем «почему». Отказ уже ушёл на сервер — тут остался один вопрос, не блокирующий.
    var declineAskFor by remember { mutableStateOf<Int?>(null) }
    val acceptTakenMsg = appText("Заказ уже взял другой водитель", "Заказды башҡа йөрөтөүсе алды")
    val acceptNetMsg = appText("Не удалось взять заказ. Проверь связь и попробуй снова.", "Заказды алып булманы. Бәйләнеште тикшереп ҡабатла.")

    // Presence-heartbeat (координаты не логируем). Следим за связью: если heartbeat не долетает,
    // водитель невидим серверу — честно показываем это чипом, а не делаем вид, что он «на линии».
    //
    // НАМЕРЕННО работает и со свёрнутым приложением (в отличие от соседних опросов на этом
    // экране). Это не обновление картинки, а сигнал «я на линии, шлите заказы»: пропал сигнал —
    // сервер через ~45 секунд забывает водителя, и тот стоит на трассе без заказов, думая что
    // просто нет спроса. Обычно в фоне сигнал шлёт сервис линии, но если он не поднялся или его
    // прибила система, этот цикл остаётся единственным. Дублирование раз в 12 секунд стоит
    // копейки, пустой рабочий день водителя — нет.
    LaunchedEffect(online) {
        if (!online) { presenceFails = 0; return@LaunchedEffect }
        while (isActive) {
            myPoint?.let {
                ApiClient.instantPresence(it.latitude, it.longitude)
                    .onSuccess { presenceFails = 0 }
                    .onFailure { presenceFails = (presenceFails + 1).coerceAtMost(99) }
            }
            delay(12_000)
        }
    }
    // Поллинг входящего оффера (пока нет активного на экране) — только пока экран перед глазами.
    // Со свёрнутым приложением офферы ловит фоновый сервис линии и показывает полноэкранное
    // уведомление: это водителю полезнее, чем обновлённый экран, которого он не видит.
    RepeatWhileVisible(online) {
        if (!online) { offer = null; return@RepeatWhileVisible }
        while (isActive) {
            if (offer == null) {
                val incoming = ApiClient.getDriverOffer().getOrNull()
                if (incoming != null && incoming.status == "offered") {
                    if (instantOfferRemainingMillis(incoming.offerExpiresAt, System.currentTimeMillis()) > 0L) {
                        offer = incoming
                    } else {
                        // Битый или уже истёкший дедлайн не показываем и не даём принять.
                        ApiClient.instantDecline(incoming.id)
                    }
                }
            }
            delay(4_000)
        }
    }

    val current = offer
    // Вопрос «почему не взял» рисуем ДО оверлея: сиблинги в Box накладываются по порядку, и
    // пришедший следом новый оффер обязан перекрыть панель целиком, а не наоборот — иначе она
    // на доли секунды висит над кнопкой «Взять заказ».
    InstantDeclineReasonPanel(
        // Новый оффер на экране → вопрос про прошлый молча уходит: время водителя дороже статистики.
        // Ушёл с линии — тем более: с выключенным тумблером отчитываться уже не перед кем.
        orderId = declineAskFor.takeIf { current == null && online },
        onPick = { id, code ->
            declineAskFor = null
            // Причина досылается ВТОРЫМ запросом: сам отказ уже прошёл, заказ ушёл следующему.
            // Упало — молчим: это диагностика матчинга, водителю про неё знать незачем.
            scope.launch { ApiClient.instantDecline(id, reason = code) }
        },
        onDismiss = { declineAskFor = null },
    )
    if (online && current != null) {
        InstantOfferOverlay(
            order = current,
            accepting = accepting,
            onAccept = {
                if (accepting) return@InstantOfferOverlay
                if (instantOfferRemainingMillis(current.offerExpiresAt, System.currentTimeMillis()) <= 0L) {
                    scope.launch { ApiClient.instantDecline(current.id) }
                    offer = null
                    return@InstantOfferOverlay
                }
                accepting = true
                scope.launch {
                    ApiClient.instantAccept(current.id)
                        .onSuccess { offer = null; onOpenTrip(it.id) }
                        .onFailure { e ->
                            // 409 (гонку проиграли/оффер протух) — заказ ушёл, закрываем и ждём следующий.
                            // Сеть/5xx — не молчим: говорим, что не взяли, оффер оставляем на повтор.
                            val st = (e as? ApiException)?.status
                            if (st == 409 || st == 410) {
                                Toast.makeText(ctx, acceptTakenMsg, Toast.LENGTH_SHORT).show(); offer = null
                            } else {
                                Toast.makeText(ctx, acceptNetMsg, Toast.LENGTH_SHORT).show()
                            }
                        }
                    accepting = false
                }
            },
            // Дедлайн оффера истёк — отказ без вопросов: водитель ничего не решал, спрашивать нечего.
            onDecline = {
                if (accepting) return@InstantOfferOverlay
                scope.launch { ApiClient.instantDecline(current.id) }
                offer = null
            },
            // Водитель сам нажал «Пропустить». Сначала ОТКАЗ — заказ мгновенно уходит следующему
            // и не ждёт, пока водитель за рулём выберет причину. Вопрос — уже вдогонку.
            onSkipTap = {
                if (accepting) return@InstantOfferOverlay
                val declinedId = current.id
                scope.launch { ApiClient.instantDecline(declinedId) }
                offer = null
                declineAskFor = declinedId
            },
        )
    }

    // Связь потеряна, пока «на линии» и нет оффера на экране: мягкий чип «нет связи».
    // Не молчим — иначе водитель ждёт заказы, а сервер его не видит. Восстановится сам.
    AnimatedVisibility(
        visible = online && current == null && presenceFails >= 2,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            Surface(shape = CircleShape, color = CanonGold.copy(alpha = 0.16f)) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Default.CloudOff, contentDescription = null, tint = CanonGold, modifier = Modifier.size(16.dp))
                    Text(
                        appText("Нет связи — переподключаемся…", "Бәйләнеш юҡ — ҡабат тоташабыҙ…"),
                        color = CanonText, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

/** Причины отказа водителя: код уходит на сервер, подпись — человеку. Порядок — от самого
 *  частого («далеко подавать») к «другому»: почти всегда хватает первой строки. */
@Composable
private fun instantDeclineReasons(): List<Pair<String, String>> = listOf(
    "far" to appText("Далеко подавать", "Килергә алыҫ"),
    "cheap" to appText("Мало денег", "Аҡса аҙ"),
    "direction" to appText("Не по пути", "Юл ыңғайы түгел"),
    "busy" to appText("Уже занят", "Мәшғүлмен"),
    "break" to appText("Перерыв", "Тәнәфес"),
    "other" to appText("Другое", "Башҡаһы"),
)

/**
 * «Почему не взял?» — вопрос ПОСЛЕ отказа, а не вместо него.
 *
 * У оффера тикает обратный отсчёт, и любой вопрос до отказа съедал бы чужие секунды: пока
 * водитель за рулём выбирает причину, пассажир ждёт машину, которая уже не приедет. Поэтому
 * отказ уходит по первому касанию, заказ сразу идёт следующему водителю, а панель появляется
 * следом и ничего не держит: не ответил за десять секунд — она молча исчезла.
 *
 * Отказ здесь ни при каких условиях не наказывается и не блокируется — это диагностика
 * матчинга. Без причин видно только «не берут», и сервер продолжает слать те же заказы тем же
 * людям: «далеко подавать» и «мало денег» лечатся совершенно по-разному.
 *
 * @param orderId заказ, по которому спрашиваем; null — панели нет (и она плавно уходит).
 */
@Composable
private fun InstantDeclineReasonPanel(
    orderId: Int?,
    onPick: (Int, String) -> Unit,
    onDismiss: () -> Unit,
) {
    // Последний заданный вопрос: во время анимации ухода orderId уже null, а чипы под пальцем
    // ещё живут — им нужен тот же заказ, а не ноль.
    var askId by remember { mutableIntStateOf(0) }
    var picked by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(orderId) {
        if (orderId == null) return@LaunchedEffect
        askId = orderId
        picked = null
        delay(10_000)
        onDismiss()   // вопрос без ответа не висит над кабинетом: водитель работает, а не отчитывается
    }
    val reasons = instantDeclineReasons()
    Box(
        Modifier.fillMaxSize().navigationBarsPadding().padding(16.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visible = orderId != null,
            // Панель выезжает снизу и так же спокойно уходит — как системные подсказки iOS.
            enter = fadeIn(tween(CanonMotion.NORMAL)) + slideInVertically(tween(CanonMotion.SLOW)) { it / 2 },
            exit = fadeOut(tween(CanonMotion.QUICK)) + shrinkVertically(tween(CanonMotion.QUICK)),
        ) {
            Surface(
                color = CanonSurface,
                shape = CanonCardShape,
                border = BorderStroke(1.dp, CanonBorder),
                shadowElevation = CanonDepth.sheet,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                appText("Почему не взял?", "Ниңә алманың?"),
                                color = CanonText, fontSize = TxBody, lineHeight = LhBody,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                appText(
                                    "Заказ уже ушёл дальше. Ответ не обязателен — он помогает не слать тебе лишнее.",
                                    "Заказ артабан китте инде. Яуап мотлаҡ түгел — ул һиңә артыҡ заказ килмәһен өсөн.",
                                ),
                                color = CanonMuted, fontSize = TxCaption, lineHeight = LhCaption,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            onClick = onDismiss,
                            shape = CircleShape,
                            color = CanonBg,
                            modifier = Modifier.size(48.dp),
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = appText("Закрыть вопрос", "Һорауҙы ябыу"),
                                    tint = CanonMuted, modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                    // Две колонки: шесть коротких причин видны разом, без прокрутки и без поиска
                    // нужной — водитель отвечает одним касанием и возвращается к дороге.
                    reasons.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEach { (code, label) ->
                                InstantReasonChip(
                                    label = label,
                                    picked = picked == code,
                                    enabled = picked == null,
                                    modifier = Modifier.weight(1f),
                                    onClick = { picked = code; onPick(askId, code) },
                                )
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------ Полноэкранный входящий оффер ------------------------------
/**
 * @param onDecline отказ БЕЗ участия водителя: серверный дедлайн истёк или пришёл битым.
 * @param onSkipTap водитель сам нажал «Пропустить». Отделено от [onDecline] специально: спросить
 *   «почему не взял?» осмысленно только у того, кто действительно решал, а не у того, кто просто
 *   не успел ответить. Не задан → кнопка работает как раньше, через [onDecline].
 */
@Composable
internal fun InstantOfferOverlay(
    order: InstantOrderDto,
    accepting: Boolean = false,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onSkipTap: (() -> Unit)? = null,
) {
    // Водитель за рулём смотрит на дорогу, а не в телефон: у оффера в приложении не было
    // ни звука, ни вибрации — заказ можно было просто не заметить (аудит P1-10).
    // Короткий «тук» в момент появления карточки; на новый заказ — новый «тук».
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(order.id) { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
    val nowMillis by rememberNowMs()
    val initialWindowMillis = remember(order.id, order.offerExpiresAt) {
        instantOfferRemainingMillis(order.offerExpiresAt, System.currentTimeMillis()).coerceAtLeast(1L)
    }
    val remainingMillis = instantOfferRemainingMillis(order.offerExpiresAt, nowMillis)
    val secondsLeft = instantOfferSecondsLeft(order.offerExpiresAt, nowMillis)
    val canAccept = remainingMillis > 0L
    var expiryHandled by remember(order.id, order.offerExpiresAt) { mutableStateOf(false) }
    LaunchedEffect(canAccept, order.id, order.offerExpiresAt) {
        if (!canAccept && !expiryHandled) {
            expiryHandled = true
            onDecline() // серверный дедлайн истёк (или был некорректным) — безопасно пропускаем
        }
    }
    Box(Modifier.fillMaxSize().background(CanonBg).statusBarsPadding()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = CanonGreen2) {
                    Icon(Icons.Default.DirectionsCar, contentDescription = null, tint = CanonBg, modifier = Modifier.padding(12.dp).size(24.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(appText("Новый заказ!", "Яңы заказ!"), color = CanonText, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (canAccept) appText("Ответь за $secondsLeft с", "$secondsLeft секундта яуап бир")
                        else appText("Время вышло", "Ваҡыт үтте"),
                        color = CanonMuted, fontSize = 14.sp,
                    )
                }
                Surface(shape = CircleShape, color = CanonSurface) {
                    // Плавная смена цифры таймера (в такт анимированной полоске), без рывка.
                    AnimatedContent(targetState = secondsLeft, label = "offerTimer") { s ->
                        Text("$s", color = CanonGreen2, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                }
            }
            LinearProgressIndicator(
                progress = { (remainingMillis.toDouble() / initialWindowMillis.toDouble()).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = CanonGreen2, trackColor = CanonSurface,
            )
            Card(colors = CardDefaults.cardColors(containerColor = CanonSurface), shape = CanonCardShape, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            val hasServerNet = order.driverGrossKop > 0
                            Text(
                                if (hasServerNet) appText("Тебе чистыми", "Һиңә таҙа килем")
                                else appText("Цена поездки", "Сәфәр хаҡы"),
                                color = CanonMuted,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                if (hasServerNet) formatTaxiKop(order.driverNetKop) else "${order.priceEstimate} ₽",
                                color = CanonText,
                                fontSize = 34.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            if (hasServerNet) {
                                Text(
                                    appText(
                                        "Пассажир: ${formatTaxiKop(order.driverGrossKop)} · комиссия ${formatTaxiKop(order.driverFeeKop)} (${formatTaxiMultiplier(order.driverFeePercent)}%)",
                                        "Пассажир: ${formatTaxiKop(order.driverGrossKop)} · комиссия ${formatTaxiKop(order.driverFeeKop)} (${formatTaxiMultiplier(order.driverFeePercent)}%)",
                                    ),
                                    color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp,
                                )
                            }
                            // «Тебе чистыми» посчитано с полной цены — и это верно: скидку пассажира
                            // оплачивает Юлдаш. Но на руки водитель получит меньше, и узнать об
                            // этом он должен ДО того, как возьмёт заказ, а не в машине.
                            if (order.hasPromoDiscount) {
                                Text(
                                    appText(
                                        "На руки от пассажира: ${formatTaxiKop(order.passengerPayKop)} — у него промокод −${formatTaxiKop(order.promoDiscountKop)}, разницу платит Юлдаш. Твой доход прежний.",
                                        "Пассажирҙан ҡулға: ${formatTaxiKop(order.passengerPayKop)} — унда промокод −${formatTaxiKop(order.promoDiscountKop)}, айырманы Юлдаш түләй. Һинең килемең үҙгәрмәй.",
                                    ),
                                    color = CanonGreen2, fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                        if (order.category == "comfort") {
                            Surface(shape = RoundedCornerShape(8.dp), color = CanonMint) {
                                Text(appText("Комфорт", "Комфорт"), color = CanonGreen2, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                            }
                        }
                    }
                    MobilityRouteTimeline(
                        from = order.fromText,
                        to = order.toText,
                        fromLabel = appText("Подача", "Килеп алыу"),
                        toLabel = appText("Назначение", "Барыр урын"),
                        compact = true,
                    )
                    // Пассажир (B7a-4): рейтинг + опыт — водитель решает по данным; новичок — честно.
                    // Агрегат анонимен; имя/телефон откроются только после «Взять заказ».
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Person, contentDescription = null, tint = CanonMuted, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        val rating = order.passengerRating
                        Text(
                            if (rating != null) {
                                val stars = String.format(java.util.Locale.US, "%.1f", rating)
                                appText("Пассажир: ★ $stars · ${order.passengerTrips} поездок",
                                    "Пассажир: ★ $stars · ${order.passengerTrips} сәфәр")
                            } else appText("Пассажир: новичок 🌱", "Пассажир: яңы юлсы 🌱"),
                            color = CanonText, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        )
                    }
                    val meta = buildList {
                        if (order.distanceKm > 0) add(appText("≈ ${order.distanceKm.toInt()} км поездка", "≈ ${order.distanceKm.toInt()} км сәфәр"))
                        if (order.etaMin > 0) add(appText("≈ ${order.etaMin.toInt()} мин", "≈ ${order.etaMin.toInt()} мин"))
                    }.joinToString("  ·  ")
                    if (meta.isNotBlank()) Text(meta, color = CanonMuted, fontSize = 14.sp)
                }
            }
                Spacer(Modifier.height(4.dp))
            }
            Spacer(Modifier.height(12.dp))
            // Решение всегда остаётся под пальцем; детали заказа прокручиваются независимо.
            Column(
                Modifier.navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppButton(
                    text = appText("Взять заказ", "Заказды алыу"),
                    onClick = { if (canAccept) onAccept() },
                    style = AppButtonStyle.Primary,
                    icon = Icons.Default.CheckCircle,
                    loading = accepting,
                    enabled = !accepting && canAccept,
                    height = 56.dp,
                )
                AppButton(
                    text = appText("Пропустить", "Үткәреп ебәреү"),
                    // Скорость отказа не трогаем: тап отдаёт заказ дальше немедленно, а причину
                    // (если водитель захочет) спрашивает уже панель поверх кабинета.
                    onClick = { if (onSkipTap != null) onSkipTap() else onDecline() },
                    style = AppButtonStyle.Secondary,
                    enabled = !accepting,
                    height = 48.dp,
                )
            }
        }
    }
}

@Composable
private fun InstantPointRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(label, color = CanonMuted, fontSize = 12.sp)
            Text(value, color = CanonText, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

// ------------------------------ Экран поездки водителя ------------------------------
/** После accept: навигация к пассажиру + кнопки «Я на месте» → «Пассажир сел» → «Завершить».
 *  На месте — живой таймер ожидания; по таймингу (5 бесплатных + 3 сверх) появляется
 *  «Пассажир не вышел» (no-show: заказ закрывается, штраф-подача фиксируется пассажиру). */
@Composable
internal fun InstantDriverTripScreen(
    orderId: Int,
    onBack: () -> Unit,
    onFinished: () -> Unit,
    initialOrder: InstantOrderDto? = null,
    observeRemote: Boolean = true,
    mapContent: (@Composable (Modifier) -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var order by remember(orderId, initialOrder) { mutableStateOf(initialOrder) }
    var loading by remember(orderId, initialOrder) { mutableStateOf(initialOrder == null) }
    var busy by remember { mutableStateOf(false) }
    var confirmNoShow by remember { mutableStateOf(false) }
    var confirmCancel by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    val actionFailMsg = appText(
        "Не получилось обновить поездку. Проверь сеть и повтори.",
        "Сәфәрҙе яңыртып булманы. Селтәрҙе тикшереп ҡабатла.",
    )
    // Первая загрузка упала по СЕТИ (не 404) → показываем «Повторить», а не «Заказ не найден».
    var loadError by remember { mutableStateOf(false) }
    var reloadTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(orderId, reloadTick, observeRemote, initialOrder) {
        if (!observeRemote) {
            order = initialOrder
            loading = false
            loadError = false
            return@LaunchedEffect
        }
        loading = true; loadError = false
        ApiClient.getInstantOrder(orderId)
            .onSuccess { order = it }
            .onFailure { e -> loadError = (e as? ApiException)?.status?.let { it >= 500 } ?: true }
        loading = false
    }

    // Дальше статус заказа переспрашиваем каждые 5 секунд — но ТОЛЬКО пока экран перед глазами.
    // Свернул приложение — опрос засыпает вместе с ним: про смену статуса и так придёт пуш, а
    // будить телефон каждые пять секунд ради экрана, на который никто не смотрит, незачем.
    // Вернулся — опрос просыпается сразу, до первой паузы, поэтому свежее видно мгновенно.
    // Первичная загрузка осталась выше отдельно: иначе при каждом возврате мигал бы спиннер.
    RepeatWhileVisible(orderId, reloadTick, observeRemote) {
        if (!observeRemote) return@RepeatWhileVisible
        ApiClient.getInstantOrder(orderId).onSuccess { order = it }
        while (isActive) {
            if (order?.status == "done" || order?.status == "cancelled" || order?.status == "expired") break
            delay(5_000)
            ApiClient.getInstantOrder(orderId).onSuccess { order = it }
        }
    }

    // Live-трек (B7a-3): пока заказ активен — шлём свою позицию пассажиру (WS, не чаще ~5с),
    // он видит движущуюся машину. Заказ кончился / ушли с экрана → сокет закрывается.
    val isOrderActive = observeRemote && order?.isActive == true
    val trackSocket = remember { mutableStateOf<com.yuldash.app.data.InstantLocationSocket?>(null) }
    DisposableEffect(orderId, isOrderActive) {
        if (!isOrderActive) return@DisposableEffect onDispose { }
        val s = com.yuldash.app.data.InstantLocationSocket(orderId, onPeer = { }).also { it.connect() }
        trackSocket.value = s
        onDispose { s.close(); trackSocket.value = null }
    }
    val myLivePoint by rememberMyPoint(active = isOrderActive)
    var lastLocSentMs by remember { mutableStateOf(0L) }
    var prevSentPoint by remember { mutableStateOf<Point?>(null) }
    LaunchedEffect(myLivePoint, isOrderActive) {
        val p = myLivePoint ?: return@LaunchedEffect
        if (!isOrderActive) return@LaunchedEffect
        val now = System.currentTimeMillis()
        if (now - lastLocSentMs < 5_000) return@LaunchedEffect
        lastLocSentMs = now
        // Курс из двух последних фиксов (нос стрелки по движению); стоим на месте → без поворота.
        val bearing = prevSentPoint?.let { q ->
            val dLat = p.latitude - q.latitude
            val dLng = p.longitude - q.longitude
            if (kotlin.math.abs(dLat) + kotlin.math.abs(dLng) < 0.00005) null
            else (Math.toDegrees(kotlin.math.atan2(dLng * kotlin.math.cos(Math.toRadians(p.latitude)), dLat)) + 360) % 360
        }
        prevSentPoint = p
        trackSocket.value?.sendLoc(p.latitude, p.longitude, bearing)
    }

    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Поездка", "Сәфәр"), onBack) }) { padding ->
        val current = order
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                loading && current == null -> InstantCenterLoader(appText("Загружаем заказ…", "Заказды йөкләйбеҙ…"))
                // Сеть упала на загрузке — не выдаём за «не найден», даём «Повторить».
                current == null && loadError -> InstantRetryCard(onRetry = { reloadTick++ }, onBack = onBack)
                current == null -> InstantFinalCard(
                    icon = Icons.Default.Close,
                    title = appText("Заказ не найден", "Заказ табылманы"),
                    subtitle = appText("Возможно, он уже завершён или отменён.", "Бәлки, ул тамамланған йәки кире алынған."),
                    action = appText("К заказам", "Заказдарға"), onAction = onBack, onSecondary = onBack,
                    tone = InstantTone.Bad,
                )
                current.status == "done" -> InstantFinalCard(
                    icon = Icons.Default.CheckCircle,
                    title = appText("Поездка завершена", "Сәфәр тамамланды"),
                    // «Заплатил» — то, что реально легло в руку (со скидкой пассажира это меньше
                    // полной цены). «Чистыми» при этом не падает: скидку оплатил Юлдаш.
                    subtitle = if (current.driverGrossKop > 0) appText(
                        "Пассажир заплатил ${formatTaxiKop(current.passengerPayKop)} · чистыми ${formatTaxiKop(current.driverNetKop)}",
                        "Пассажир ${formatTaxiKop(current.passengerPayKop)} түләне · таҙа килем ${formatTaxiKop(current.driverNetKop)}",
                    ) else appText(
                        "Получено ${formatTaxiKop(current.passengerPayKop)}. Спасибо!",
                        "${formatTaxiKop(current.passengerPayKop)} алынды. Рәхмәт!",
                    ),
                    action = appText("Готово", "Әҙер"), onAction = onFinished, onSecondary = onFinished,
                    extra = {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            // Промокод пассажира: почему на руки пришло меньше и где остальное.
                            TaxiPromoPayRow(order = current, forDriver = true)
                            InstantReceiptReminder()   // B7b-4: чек самозанятого — мягко, не назидательно
                            InstantRateAndReport(current, isDriver = true)   // §9: оценить/пожаловаться
                            UnpaidReportButton(orderId = current.id)   // B8-7: «пассажир не заплатил» одним тапом
                            // Чек поездки: там же водитель отмечает «наличные получил» и «нашёл вещь».
                            TaxiReceiptLink(current.id)
                            TaxiDisputeLink(current, isDriver = true)
                        }
                    },
                )
                current.status == "cancelled" -> InstantFinalCard(
                    icon = Icons.Default.Close,
                    title = if (current.noShow) appText("Пассажир не вышел", "Пассажир сыҡманы")
                    else appText("Заказ отменён", "Заказ кире алынды"),
                    subtitle = when {
                        // Водитель видит ту же сумму, что и пассажир на своём экране: расходиться
                        // на копейки нельзя — по ней они и рассчитываются между собой.
                        current.noShow -> appText(
                            "Заказ закрыт. Пассажиру зафиксирована плата за подачу — ${formatTaxiKop(current.cancelFeeKop)}.",
                            "Заказ ябылды. Пассажирға килеү хаҡы яҙылды — ${formatTaxiKop(current.cancelFeeKop)}.")
                        current.cancelBy == "passenger" && current.cancelFeeKop > 0 -> appText(
                            "Пассажир отменил поздно — ему зафиксирована подача ${formatTaxiKop(current.cancelFeeKop)}.",
                            "Пассажир һуң кире алды — уға килеү хаҡы яҙылды: ${formatTaxiKop(current.cancelFeeKop)}.")
                        current.cancelBy == "passenger" -> appText("Пассажир отменил заказ.", "Пассажир заказды кире алды.")
                        else -> appText("Заказ отменён.", "Заказ кире алынды.")
                    },
                    action = appText("К заказам", "Заказдарға"), onAction = onFinished, onSecondary = onFinished,
                    tone = InstantTone.Bad,
                )
                else -> {
                    val (primaryLabel, nextStatus) = when (current.status) {
                        "accepted" -> appText("Я на месте", "Мин урында") to "arriving"
                        "arriving" -> appText("Пассажир сел", "Пассажир ултырҙы") to "onboard"
                        "onboard" -> appText("Завершить поездку", "Сәфәрҙе тамамлау") to "done"
                        else -> appText("Обновить", "Яңыртыу") to ""
                    }
                    Column(Modifier.fillMaxSize()) {
                    val tripMapModifier = Modifier.fillMaxWidth().weight(0.36f)
                    if (mapContent != null) {
                        mapContent(tripMapModifier)
                    } else {
                        InstantRouteMap(
                            from = Point(current.fromLat, current.fromLng),
                            to = Point(current.toLat, current.toLng),
                            modifier = tripMapModifier,
                        )
                    }
                    Card(
                        colors = CardDefaults.cardColors(containerColor = CanonSurface),
                        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.sheet),
                        modifier = Modifier.fillMaxWidth().weight(0.64f),
                    ) {
                        Column(Modifier.fillMaxSize()) {
                        Column(
                            Modifier.weight(1f).verticalScroll(rememberScrollState())
                                .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            TaxiTripProgress(status = current.status)
                            // Смена адреса. Стоит ПЕРВОЙ и до всего остального: если пассажир
                            // поменял точку Б, это самое важное на экране — водитель ведёт
                            // маршрут во внешнем навигаторе, и тот сам о смене не узнает.
                            // Локальная копия:  — изменяемая переменная, и внутри
                            // лямбд компилятор за её содержимое не ручается.
                            val ord = current
                            DriverDestinationCard(
                                order = ord,
                                onAck = {
                                    scope.launch {
                                        ApiClient.ackDestination(ord.id)   // экран сам переспросит сервер через пару секунд
                                        // Сразу открываем навигатор с новым адресом: водитель за рулём,
                                        // и лишний тап — это лишний взгляд в телефон вместо дороги.
                                        openNavigator(ctx, ord.toLat, ord.toLng)
                                    }
                                },
                                onAccept = {
                                    scope.launch {
                                        ApiClient.acceptDestination(ord.id)
                                        openNavigator(ctx, ord.toLat, ord.toLng)
                                    }
                                },
                                onDecline = { reason ->
                                    scope.launch {
                                        ApiClient.declineDestination(ord.id, reason)
                                    }
                                },
                            )
                            // Остановки: куда заезжать по пути и отметка стоянки.
                            if (ord.stops.isNotEmpty()) {
                                Surface(color = CanonMint, shape = CanonItemShape) {
                                    Column(Modifier.fillMaxWidth().padding(CanonSpace.md),
                                           verticalArrangement = Arrangement.spacedBy(CanonSpace.xs)) {
                                        Text(appText("Остановки по пути", "Юлдағы туҡталыштар"),
                                             style = CanonCaption, color = CanonMuted)
                                        ord.stops.forEach { st ->
                                            Row(verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
                                                Icon(Icons.Default.Place, contentDescription = null,
                                                     tint = if (st.done) CanonMuted else CanonGreen2,
                                                     modifier = Modifier.size(18.dp))
                                                Text(st.text, style = CanonBody,
                                                     color = if (st.done) CanonMuted else CanonText,
                                                     maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                        }
                                    }
                                }
                            }
                            DriverStopButton(
                                order = ord,
                                onToggle = { scope.launch { ApiClient.toggleStop(ord.id) } },
                            )
                            // Пассажир + телефон (после accept)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Person, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(current.passengerName.ifBlank { appText("Пассажир", "Пассажир") }, color = CanonText, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                        // Заказ ДЛЯ ДРУГОГО: везём не заказчика. Имя и телефон выше — уже
                                        // того, кого забираем, но водитель должен это ПОНИМАТЬ заранее.
                                        if (current.forOther) {
                                            Spacer(Modifier.width(4.dp))
                                            Surface(color = CanonTaxiBg, shape = RoundedCornerShape(8.dp)) {
                                                Text(
                                                    appText("заказ для другого", "икенсе кеше өсөн"),
                                                    color = CanonTaxiText, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                )
                                            }
                                        }
                                    }
                                    Text("${current.fromText.ifBlank { appText("Точка А", "А нөктәһе") }} → ${current.toText.ifBlank { appText("Точка Б", "Б нөктәһе") }}", color = CanonMuted, fontSize = 14.sp, maxLines = 1)
                                }
                                // «Написать» (B7b-1): чат заказа — водителю удобнее коротким текстом на месте.
                                Surface(modifier = Modifier.minimumInteractiveComponentSize(), onClick = { NavSignals.openInstantChat.value = current.id }, shape = CircleShape, color = CanonMint) {
                                    Icon(Icons.Default.ChatBubble, contentDescription = appText("Написать пассажиру", "Пассажирға яҙырға"), tint = CanonGreen2, modifier = Modifier.padding(12.dp).size(20.dp))
                                }
                                Spacer(Modifier.width(8.dp))
                                if (current.passengerPhone.isNotBlank()) {
                                    Surface(modifier = Modifier.minimumInteractiveComponentSize(), onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${current.passengerPhone}"))) } }, shape = CircleShape, color = CanonGreen2) {
                                        Icon(Icons.Default.Phone, contentDescription = appText("Позвонить пассажиру", "Пассажирға шылтыратыу"), tint = CanonBg, modifier = Modifier.padding(12.dp).size(20.dp))
                                    }
                                }
                            }
                            // «Как меня найти» — комментарий и подъезд от пассажира. В селе адрес
                            // «Ленина 12» — это пять домов без табличек, а чат открывается только
                            // ПОСЛЕ принятия заказа: без этой подсказки водитель наматывал круги.
                            if (current.comment.isNotBlank() || current.entrance.isNotBlank()) {
                                Surface(color = CanonMint, shape = CanonItemShape) {
                                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.LocationOn, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text(appText("Как найти", "Нисек табырға"), color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        }
                                        if (current.entrance.isNotBlank()) {
                                            Text(current.entrance, color = CanonText, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        }
                                        if (current.comment.isNotBlank()) {
                                            Text(current.comment, color = CanonText, fontSize = 14.sp, lineHeight = 20.sp)
                                        }
                                    }
                                }
                            }
                            // Ожидание тарифицируется поминутно — копейки тут обычное дело,
                            // а сумма стоит рядом с ценой поездки, где формат уже с копейками.
                            val waitKop = current.waitingFeeKop
                            // Сумма «на руки» — та же, что видит пассажир. Если у него сработал
                            // промокод, он отдаст меньше полной цены, и водитель обязан узнать об
                            // этом ЗДЕСЬ, а не у машины (объяснение — в карточке ниже).
                            val payToDriver = formatTaxiKop(current.passengerPayKop)
                            Text(
                                appText(
                                    "Пассажир платит: $payToDriver" + (if (waitKop > 0) " + ${formatTaxiKop(waitKop)} ожидание" else "") + " · наличными/переводом",
                                    "Пассажир түләй: $payToDriver" + (if (waitKop > 0) " + ${formatTaxiKop(waitKop)} көтөү" else "") + " · аҡсалата/күсереп",
                                ),
                                color = CanonMuted, fontSize = 14.sp,
                            )
                            TaxiPromoPayRow(order = current, forDriver = true)
                            if (current.driverGrossKop > 0) {
                                Text(
                                    appText(
                                        "Ориентир чистыми: ${formatTaxiKop(current.driverNetKop)} до платного ожидания",
                                        "Таҙа килем самаһы: ${formatTaxiKop(current.driverNetKop)} түләүле көтөүгә тиклем",
                                    ),
                                    color = CanonGreen2, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                                )
                            }
                            // «Навигатор» (B7a-3): до посадки ведём к подаче (А), после — к назначению (Б).
                            // Яндекс Навигатор → Яндекс Карты → любое geo:-приложение.
                            OutlinedButton(
                                onClick = {
                                    val toDest = current.status == "onboard"
                                    openNavigator(
                                        ctx,
                                        if (toDest) current.toLat else current.fromLat,
                                        if (toDest) current.toLng else current.fromLng,
                                    )
                                },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                shape = RoundedCornerShape(14.dp),
                            ) {
                                Icon(Icons.Default.Navigation, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (current.status == "onboard") appText("Навигатор · к точке Б", "Навигатор · Б нөктәһенә")
                                    else appText("Навигатор · к пассажиру", "Навигатор · пассажирға"),
                                    color = CanonGreen2, fontWeight = FontWeight.Bold,
                                )
                            }
                            // «Я на месте» → таймер ожидания (бесплатное окно и платные минуты — как у пассажира).
                            if (current.status == "arriving") {
                                InstantWaitingRow(current)
                            }
                            // «Пассажир не вышел» — появляется по честному таймингу сервера
                            // (5 бесплатных минут + 3 сверх после «Я на месте»).
                            val nowMs by rememberNowMs()
                            val noShowReady = current.status == "arriving" &&
                                (current.noShowAt?.let { isoUtcToEpochMs(it) }?.let { nowMs >= it } == true)
                            AnimatedVisibility(visible = noShowReady, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                                OutlinedButton(
                                    onClick = { confirmNoShow = true },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                    shape = RoundedCornerShape(14.dp),
                                ) {
                                    Text(appText("Пассажир не вышел", "Пассажир сыҡманы"), color = CanonRed, fontWeight = FontWeight.Bold)
                                }
                            }
                            if (actionError != null) {
                                Text(actionError!!, color = CanonRed, fontSize = 14.sp)
                            }
                            // SOS (B7b-2): безопасность водителя — тоже продукт (обе стороны заказа).
                            InstantSafetyRow(orderId = current.id)
                            TextButton(
                                onClick = { if (!busy) confirmCancel = true },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            ) { Text(appText("Отменить заказ", "Заказды кире алыу"), color = CanonRed) }
                        }
                        // Главный переход фазы закреплён снизу: «Завершить» не уезжает за край
                        // даже на 320dp и при fontScale 1.5, а детали выше остаются прокручиваемыми.
                        Box(
                            Modifier.fillMaxWidth().navigationBarsPadding()
                                .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 12.dp),
                        ) {
                            Button(
                                onClick = {
                                    if (nextStatus.isBlank() || busy) return@Button
                                    busy = true
                                    scope.launch {
                                        val res = when (nextStatus) {
                                            "arriving" -> ApiClient.instantArrived(current.id)
                                            "onboard" -> ApiClient.instantOnboard(current.id)
                                            else -> ApiClient.instantDone(current.id)
                                        }
                                        res.onSuccess { order = it; actionError = null }
                                        res.onFailure { actionError = (it as? ApiException)?.message ?: actionFailMsg }
                                        busy = false
                                    }
                                },
                                enabled = !busy && nextStatus.isNotBlank(),
                                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                                shape = InstantControlShape,
                                colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2),
                            ) {
                                if (busy) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = CanonBg)
                                } else {
                                    Text(primaryLabel, fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
        }
    }
    val activeOrder = order
    if (confirmCancel && activeOrder != null) {
        AlertDialog(
            onDismissRequest = { if (!busy) confirmCancel = false },
            containerColor = CanonSurface,
            title = { Text(appText("Отменить поездку?", "Сәфәрҙе кире алаһыңмы?"), color = CanonText, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    if (activeOrder.status == "onboard") {
                        appText(
                            "Пассажир уже в машине. Отменяй только если продолжать небезопасно; после этого открой спор или поддержку.",
                            "Пассажир машинала инде. Дауам итеү хәүефле булһа ғына кире ал; һуңынан бәхәс йәки ярҙам ас.",
                        )
                    } else {
                        // Про паузу говорим ЗАРАНЕЕ (разбор №2): с этой версии несколько
                        // отменённых принятых заказов подряд ставят офферы на паузу. Наказание,
                        // о котором человек узнаёт постфактум, читается как поломка приложения —
                        // а он всего лишь не знал правила. Один раз — ничего не будет, и это
                        // тоже сказано прямо, чтобы честная отмена не выглядела угрозой.
                        appText(
                            "Пассажир получит уведомление, заказ закроется. Это действие нельзя отменить. " +
                                "Один раз — ничего страшного, но если отменять принятые заказы часто, " +
                                "новые заказы какое-то время приходить не будут.",
                            "Пассажирға хәбәр бара, заказ ябыла. Был эште кире ҡайтарып булмай. " +
                                "Бер тапҡыр — бер ни ҙә булмай, әммә ҡабул ителгән заказдарҙы йыш кире алһаң, " +
                                "яңы заказдар бер аҙ ваҡыт килмәйәсәк.",
                        )
                    },
                    color = CanonMuted,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        actionError = null
                        scope.launch {
                            ApiClient.instantCancel(activeOrder.id, reason = "driver_cancel")
                                .onSuccess { order = it; confirmCancel = false }
                                .onFailure { actionError = (it as? ApiException)?.message ?: actionFailMsg }
                            busy = false
                        }
                    },
                ) { Text(appText("Отменить поездку", "Сәфәрҙе кире алыу"), color = CanonRed, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { confirmCancel = false }) {
                    Text(appText("Продолжить поездку", "Сәфәрҙе дауам итеү"), color = CanonGreen2, fontWeight = FontWeight.Bold)
                }
            },
        )
    }
    if (confirmNoShow && activeOrder != null) {
        AlertDialog(
            onDismissRequest = { confirmNoShow = false },
            containerColor = CanonSurface,
            title = { Text(appText("Пассажир не вышел?", "Пассажир сыҡманымы?"), color = CanonText, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    appText(
                        "Заказ закроется, пассажиру зафиксируется плата за подачу. Позвони ему перед этим — вдруг уже бежит.",
                        "Заказ ябыла, пассажирға килеү хаҡы яҙыла. Тәүҙә шылтыратып ҡара — бәлки, йүгереп килә лә.",
                    ),
                    color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmNoShow = false
                    scope.launch {
                        ApiClient.instantCancel(activeOrder.id, reason = "no_show")
                            .onSuccess { order = it; actionError = null }
                            .onFailure { actionError = (it as? ApiException)?.message ?: actionFailMsg }
                    }
                }) { Text(appText("Да, не вышел", "Эйе, сыҡманы"), color = CanonRed, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { confirmNoShow = false }) {
                    Text(appText("Ещё подожду", "Тағы көтәм"), color = CanonGreen2, fontWeight = FontWeight.Bold)
                }
            },
        )
    }
}

/** B7b-4: мягкое напоминание самозанятому о чеке после завершённой поездки (не интеграция —
 *  просто тёплая подсказка; пуш-напоминание с дедупом 1/сутки шлёт сервер). */
@Composable
private fun InstantReceiptReminder() {
    Surface(shape = CanonItemShape, color = CanonMint, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("🧾", fontSize = 24.sp)
            Spacer(Modifier.width(8.dp))
            Text(
                appText("Не забудь чек в «Мой налог» — пара касаний, и обязанность самозанятого выполнена 💚",
                    "«Мой налог»да чек бирергә онотма — бер-ике баҫыу, һәм үҙмәшғүл бурысы үтәлде 💚"),
                color = CanonText, fontSize = 14.sp, lineHeight = 20.sp,
            )
        }
    }
}

/** Открыть внешний навигатор к точке (B7a-3): Яндекс Навигатор → Яндекс Карты → любое geo:-приложение.
 *  Ничего не установлено → тихо ничего (кнопка не роняет экран). */
private fun openNavigator(ctx: Context, lat: Double, lng: Double) {
    val uris = listOf(
        "yandexnavi://build_route_on_map?lat_to=$lat&lon_to=$lng",
        "yandexmaps://maps.yandex.ru/?rtext=~$lat,$lng&rtt=auto",
        "geo:$lat,$lng?q=$lat,$lng",
    )
    for (u in uris) {
        val ok = runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) }.isSuccess
        if (ok) return
    }
}


// ------------------------------ круговой рейс ------------------------------
// Зачем он есть. Дальняя поездка ломается о пустую дорогу назад: водитель везёт человека
// 450 км и столько же едет обратно ни с кем. Это его день и его бензин, а платят за половину.
// Поднять цену всем — наказать и тех, у кого обратный заказ есть. Поэтому предлагаем выбор:
// берёшь машину в обе стороны — обратная дорога со скидкой. Пассажиру дешевле двух отдельных
// заказов, водителю — второй конец без поиска клиента. Так делает и рынок межгорода.
//
// Карточка появляется ТОЛЬКО когда сервер сказал, что рейс доступен (межгород). В городе
// порожняка нет, и предлагать нечего.

/** Сколько водитель ждёт на месте. Часы, а не минуты: человек мыслит «на часок» / «до вечера». */
private val ROUND_TRIP_WAIT_HOURS = listOf(1, 2, 3, 4)

@Composable
private fun InstantRoundTripCard(
    estimate: com.yuldash.app.data.InstantEstimateDto?,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    waitMin: Int,
    onWaitMin: (Int) -> Unit,
) {
    val discount = estimate?.roundTripDiscountPercent ?: 0
    val maxHours = (estimate?.roundTripMaxWaitHours ?: 4).coerceAtLeast(1)
    Surface(color = CanonSurface, shape = CanonItemShape) {
        Column {
            SettingSwitchRow(
                icon = Icons.Default.SwapHoriz,
                title = appText("Обратно тоже", "Кире лә"),
                subtitle = if (discount > 0) appText(
                    "Водитель довезёт, подождёт и вернёт обратно. Обратная дорога — на $discount% дешевле.",
                    "Йөрөтөүсе илтә, көтә һәм кире ҡайтара. Кире юл $discount% арзаныраҡ.",
                ) else appText(
                    "Водитель довезёт, подождёт и вернёт обратно.",
                    "Йөрөтөүсе илтә, көтә һәм кире ҡайтара.",
                ),
                checked = checked,
                onCheckedChange = onChecked,
            )
            // Выбор времени показываем только включённым: пока переключатель выключен, эти
            // кнопки — лишний шум на и без того плотном экране заказа.
            AnimatedVisibility(
                visible = checked,
                enter = expandVertically(tween(CanonMotion.NORMAL)) + fadeIn(tween(CanonMotion.NORMAL)),
                exit = shrinkVertically(tween(CanonMotion.QUICK)) + fadeOut(tween(CanonMotion.QUICK)),
            ) {
                Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                    Text(
                        appText("Сколько ждать на месте", "Урында күпме көтөргә"),
                        color = CanonMuted, style = CanonCaption,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ROUND_TRIP_WAIT_HOURS.filter { it <= maxHours }.forEach { h ->
                            RoundTripWaitChip(
                                hours = h,
                                selected = waitMin == h * 60,
                                onClick = { onWaitMin(h * 60) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    // Ожидание отдельно не оплачивается — оно уже оплачено тем, что второй конец
                    // достался водителю без поиска пассажира. Но человек должен понимать рамку:
                    // это не «жди сколько хочешь», у водителя есть смена.
                    Spacer(Modifier.height(8.dp))
                    Text(
                        appText(
                            "Ждать дольше водитель не сможет — у него смена. Задержишься — поездку придётся заказать заново.",
                            "Йөрөтөүсе оҙағыраҡ көтә алмай — уның сменаһы бар. Һуңлаһаң, сәфәрҙе яңынан заказ итергә тура килә.",
                        ),
                        color = CanonMuted, style = CanonMicro,
                    )
                }
            }
        }
    }
}

@Composable
private fun RoundTripWaitChip(
    hours: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // CanonGreen2, а не CanonGreen: второй в тёмной теме превращается в светлую мяту, и белая
    // надпись на нём становится нечитаемой. CanonGreen2 затемнён специально под белый текст.
    val bg by animateColorAsState(if (selected) CanonGreen2 else CanonMint, tween(CanonMotion.QUICK), label = "rtChipBg")
    val fg by animateColorAsState(if (selected) CanonOnAccent else CanonText, tween(CanonMotion.QUICK), label = "rtChipFg")
    Surface(
        color = bg,
        shape = CanonTinyShape,
        modifier = modifier.heightIn(min = 48.dp).clickable(onClick = onClick),   // тач-цель ≥48dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                appText("$hours ч", "$hours сәғ"),
                color = fg,
                style = if (selected) CanonBodyStrong else CanonBody,
            )
        }
    }
}


// ------------------------------ смена адреса: экран водителя ------------------------------
// Почему карточку нельзя пролистать. До сих пор водитель не получал по заказу НИ ОДНОГО
// уведомления: все пуши шли пассажиру, а он узнавал новости, переспрашивая сервер раз в пять
// секунд — и только пока держал экран открытым. За рулём он его не держит.
//
// А главное — маршрут он ведёт во ВНЕШНЕМ навигаторе. Мы запускаем его один раз, передав
// координаты, и связь на этом кончается: поменяется точка Б — навигатор об этом не узнает
// никогда. Поэтому «Понял» здесь не вежливость, а единственный момент, когда мы точно знаем,
// что человек видел новый адрес. И сразу за ним открываем навигатор заново — водитель за
// рулём, лишний тап это лишний взгляд в телефон вместо дороги.

/** Причины, по которым водитель может сойти с маршрута. Список закрытый: свободный текст
 *  никто не читает, а выбор из четырёх — это данные о том, что чинить. */
private data class DeclineReason(val code: String, val ru: String, val ba: String)

private val DECLINE_REASONS = listOf(
    DeclineReason("shift_end", "Заканчивается смена", "Сменам бөтә"),
    DeclineReason("out_of_zone", "Далеко от моей зоны", "Минең зонанан алыҫ"),
    DeclineReason("no_fuel", "Не хватит топлива", "Яғыулыҡ етмәй"),
    DeclineReason("other", "Другая причина", "Башҡа сәбәп"),
)

@Composable
internal fun DriverDestinationCard(
    order: com.yuldash.app.data.InstantOrderDto,
    onAck: () -> Unit,
    onAccept: () -> Unit,
    onDecline: (String) -> Unit,
) {
    val pending = order.pendingToText.isNotBlank() || order.pendingPrice > 0
    val changed = order.destinationChanges > 0 && !order.destinationAck
    if (!pending && !changed) return

    var askReason by remember { mutableStateOf(false) }

    Surface(color = CanonWarnBg, shape = CanonCardShape, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(CanonSpace.md), verticalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
                Icon(Icons.Default.SwapHoriz, contentDescription = null, tint = CanonWarn)
                Text(
                    if (pending) appText("Пассажир просит изменить маршрут",
                                         "Юлсы маршрутты үҙгәртеүҙе һорай")
                    else appText("Адрес изменился", "Адрес үҙгәрҙе"),
                    style = CanonBodyStrong, color = CanonText,
                )
            }

            val where = if (pending) order.pendingToText else order.toText
            if (where.isNotBlank()) Text(where, style = CanonBody, color = CanonText)

            val price = if (pending) order.pendingPrice else order.priceEstimate
            if (price > 0) {
                Text(
                    if (pending) appText("Станет $price ₽", "$price һум була")
                    else appText("Цена — $price ₽", "Хаҡ — $price һум"),
                    style = CanonBodyStrong, color = CanonText,
                )
            }

            // Крупная смена: объясняем, почему вообще спрашиваем. Пять часов за руль и
            // ночёвка в чужом городе — это не «поменял адрес», это другая работа.
            if (pending) {
                Text(
                    when (order.pendingReason) {
                        "zone" -> appText("Поездка станет междугородной — это другая работа, поэтому спрашиваем тебя.",
                                          "Сәфәр ҡалалар-ара була — был башҡа эш, шуға һинән һорайбыҙ.")
                        else -> appText("Цена вырастет втрое — решать тебе.",
                                        "Хаҡ өс тапҡырға арта — үҙең хәл ит.")
                    },
                    style = CanonMicro, color = CanonMuted,
                )
            }

            if (askReason) {
                Text(appText("Почему не сможешь?", "Ниңә бара алмайһың?"),
                     style = CanonCaption, color = CanonMuted)
                DECLINE_REASONS.forEach { r ->
                    Surface(
                        color = CanonSurface, shape = CanonItemShape,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .clickable { askReason = false; onDecline(r.code) },
                    ) {
                        Box(Modifier.padding(horizontal = CanonSpace.md), contentAlignment = Alignment.CenterStart) {
                            Text(appText(r.ru, r.ba), style = CanonBody, color = CanonText)
                        }
                    }
                }
                TextButton(onClick = { askReason = false }) {
                    Text(appText("Назад", "Кире"), color = CanonMuted)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
                    Button(
                        onClick = { if (pending) onAccept() else onAck() },
                        colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) {
                        Text(if (pending) appText("Согласен", "Риза") else appText("Понял", "Аңланым"),
                             style = CanonButton, color = CanonOnAccent)
                    }
                    OutlinedButton(
                        onClick = { askReason = true },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) {
                        Text(appText("Не смогу", "Бара алмайым"), style = CanonButton, color = CanonRed)
                    }
                }
                // Отказ от НОВОГО маршрута не рвёт тот, на который человек соглашался.
                Text(
                    if (pending) appText("Откажешься — поедете по прежнему адресу за прежнюю цену.",
                                         "Баш тартһаң, элекке адрес буйынса элекке хаҡҡа барабыҙ.")
                    else appText("Не сможешь — поездка завершится здесь, деньги за проеденное твои.",
                                 "Бара алмаһаң, сәфәр ошонда тамамлана, үтелгән юл аҡсаһы һинеке."),
                    style = CanonMicro, color = CanonMuted,
                )
            }
        }
    }
}


// ------------------------------ смена адреса: экран пассажира ------------------------------
// Пассажир — хозяин маршрута: меняет сам, без разрешения. Но цену он должен увидеть ДО
// того, как согласится, и увидеть, ИЗ ЧЕГО она сложилась: «проехали 6 км · осталось 9».
// Иначе новая цифра выглядит как произвол, хотя за ней честная арифметика.
//
// Сети нет — говорим прямо. Запоминать «на потом» нельзя: человек будет уверен, что адрес
// сменился, машина поедет по старому, а смена прилетит водителю через десять минут, когда
// он уже почти на месте.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChangeDestinationSheet(
    order: InstantOrderDto,
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<com.yuldash.app.data.GeoHit>>(emptyList()) }
    var picked by remember { mutableStateOf<com.yuldash.app.data.GeoHit?>(null) }
    var quote by remember { mutableStateOf<com.yuldash.app.data.DestinationQuoteDto?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Тексты считаем ЗДЕСЬ: внутри лямбд обработки ответа язык уже не спросишь —
    // appText работает только в разметке.
    val failCount = appText("Не получилось посчитать. Проверь связь",
                            "Иҫәпләп булманы. Бәйләнеште тикшер")
    val failChange = appText("Не получилось. Попробуй ещё раз", "Булманы. Ҡабат ҡара")

    // Подсказки адреса — с той же паузой, что на экране заказа: маршрут у карты платный,
    // и дёргать его на каждую букву незачем.
    LaunchedEffect(query) {
        if (query.trim().length < 2) { suggestions = emptyList(); return@LaunchedEffect }
        delay(350)
        GeocoderClient.suggestResult(query).onSuccess { suggestions = it.take(6) }
    }

    // Выбрали адрес — сразу считаем, во что это обойдётся. Ничего пока не меняя.
    LaunchedEffect(picked) {
        val place = picked ?: return@LaunchedEffect
        busy = true; error = null
        ApiClient.previewDestination(order.id, place.lat, place.lon, place.title)
            .onSuccess { quote = it }
            .onFailure {
                quote = null
                error = (it as? ApiException)?.message
                    ?: failCount
            }
        busy = false
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = CanonSurface) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = CanonSpace.lg).padding(bottom = CanonSpace.xl),
            verticalArrangement = Arrangement.spacedBy(CanonSpace.md),
        ) {
            Text(appText("Куда едем?", "Ҡайҙа барабыҙ?"), style = CanonTitle, color = CanonText)

            OutlinedTextField(
                value = query,
                onValueChange = { query = it; picked = null; quote = null },
                placeholder = { Text(appText("Новый адрес", "Яңы адрес"), color = CanonMuted) },
                singleLine = true,
                shape = CanonFieldShape,
                modifier = Modifier.fillMaxWidth(),
            )

            suggestions.forEach { sug ->
                Surface(
                    color = CanonBg, shape = CanonItemShape,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .clickable { picked = sug; query = sug.title; suggestions = emptyList() },
                ) {
                    Box(Modifier.padding(horizontal = CanonSpace.md), contentAlignment = Alignment.CenterStart) {
                        Text(sug.title, style = CanonBody, color = CanonText, maxLines = 2)
                    }
                }
            }

            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = CanonGreen2)
                    Text(appText("Считаем цену…", "Хаҡты иҫәпләйбеҙ…"), style = CanonCaption, color = CanonMuted)
                }
            }

            error?.let { Text(it, style = CanonCaption, color = CanonRed) }

            quote?.let { q ->
                Surface(color = CanonMint, shape = CanonItemShape) {
                    Column(Modifier.fillMaxWidth().padding(CanonSpace.md),
                           verticalArrangement = Arrangement.spacedBy(CanonSpace.xs)) {
                        Text(
                            appText("Было ${q.oldPrice} ₽ → станет ${q.price} ₽",
                                    "${q.oldPrice} һум ине → ${q.price} һум була"),
                            style = CanonBodyStrong, color = CanonText,
                        )
                        // Показываем, из чего цена. Проеденное водителю платят в любом
                        // случае — это не «накрутка», а уже сделанная работа.
                        Text(
                            appText("Проехали ${q.drivenKm.toInt()} км · осталось ${q.restKm.toInt()} км",
                                    "${q.drivenKm.toInt()} км үттек · ${q.restKm.toInt()} км ҡалды"),
                            style = CanonMicro, color = CanonMuted,
                        )
                        if (q.needsDriverOk) {
                            Text(
                                if (q.askReason == "zone")
                                    appText("Это уже междугородняя поездка — спросим водителя, сможет ли он.",
                                            "Был инде ҡалалар-ара сәфәр — йөрөтөүсенән һорайбыҙ.")
                                else appText("Цена вырастет втрое — спросим водителя.",
                                             "Хаҡ өс тапҡырға арта — йөрөтөүсенән һорайбыҙ."),
                                style = CanonMicro, color = CanonWarn,
                            )
                        }
                    }
                }
            }

            Button(
                onClick = {
                    val place = picked ?: return@Button
                    busy = true; error = null
                    scope.launch {
                        ApiClient.changeDestination(order.id, place.lat, place.lon, place.title)
                            .onSuccess { onChanged(); onDismiss() }
                            .onFailure {
                                error = (it as? ApiException)?.message
                                    ?: failChange
                            }
                        busy = false
                    }
                },
                enabled = picked != null && !busy,
                colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(
                    if (quote?.needsDriverOk == true) appText("Спросить водителя", "Йөрөтөүсенән һорау")
                    else appText("Изменить адрес", "Адресты үҙгәртеү"),
                    style = CanonButton, color = CanonOnAccent,
                )
            }
        }
    }
}


// ------------------------------ остановки по пути ------------------------------
// Смысл остановки в том, что за неё платят. Без этого водитель везёт лишние километры даром,
// а пассажир не понимает, почему цена не изменилась.
//
// Больше трёх не даём: каждая остановка удлиняет поездку и цену, а водитель на четвёртой
// начинает отказываться — заказ висит, и виноватым выглядит приложение. Сами строки живут
// в карточке маршрута (OrderStopRow), здесь остаётся только выбор адреса.
internal const val InstantStopsMax = 3

/** Выбор места для остановки. Тот же поиск адреса, что на экране заказа. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PickStopSheet(
    onDismiss: () -> Unit,
    onPicked: (com.yuldash.app.data.GeoHit) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<com.yuldash.app.data.GeoHit>>(emptyList()) }

    LaunchedEffect(query) {
        if (query.trim().length < 2) { suggestions = emptyList(); return@LaunchedEffect }
        delay(350)   // та же пауза, что на экране заказа: запросы к карте платные
        GeocoderClient.suggestResult(query).onSuccess { suggestions = it.take(6) }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = CanonSurface) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = CanonSpace.lg).padding(bottom = CanonSpace.xl),
            verticalArrangement = Arrangement.spacedBy(CanonSpace.md),
        ) {
            Text(appText("Куда заехать?", "Ҡайҙа инеп сығырға?"), style = CanonTitle, color = CanonText)
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                placeholder = { Text(appText("Адрес остановки", "Туҡталыш адресы"), color = CanonMuted) },
                singleLine = true, shape = CanonFieldShape, modifier = Modifier.fillMaxWidth(),
            )
            suggestions.forEach { hit ->
                Surface(
                    color = CanonBg, shape = CanonItemShape,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .clickable { onPicked(hit) },
                ) {
                    Box(Modifier.padding(horizontal = CanonSpace.md), contentAlignment = Alignment.CenterStart) {
                        Text(hit.title, style = CanonBody, color = CanonText, maxLines = 2)
                    }
                }
            }
        }
    }
}

/** «Стоим» / «Поехали» — водитель отмечает стоянку на остановке.
 *
 *  Кнопкой, а не автоматом по координатам: машина, застрявшая в пробке у светофора рядом
 *  с остановкой, начала бы «зарабатывать» сама, а разбираться пришлось бы пассажиру.
 *  Нажал — оба видят, что счётчик пошёл. Правила те же, что при подаче. */
@Composable
internal fun DriverStopButton(
    order: com.yuldash.app.data.InstantOrderDto,
    onToggle: () -> Unit,
) {
    if (order.status != "onboard" || order.stops.isEmpty()) return
    val standing = order.standing
    Surface(
        color = if (standing) CanonWarnBg else CanonMint,
        shape = CanonItemShape,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onToggle),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = CanonSpace.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CanonSpace.sm),
        ) {
            Icon(Icons.Default.AccessTime, contentDescription = null,
                 tint = if (standing) CanonWarn else CanonGreen2)
            Column(Modifier.weight(1f)) {
                Text(
                    if (standing) appText("Поехали", "Киттек") else appText("Стоим", "Туҡтап торабыҙ"),
                    style = CanonBodyStrong, color = CanonText,
                )
                Text(
                    if (standing) appText("Идёт ожидание — нажми, когда тронешься",
                                          "Көтөү бара — ҡуҙғалғас баҫ")
                    else appText("Отметь остановку — пойдёт ожидание",
                                 "Туҡталышты билдәлә — көтөү китә"),
                    style = CanonMicro, color = CanonMuted,
                )
            }
        }
    }
}

/**
 * «Куда» в шапке шторки заказа — вторая половина маршрута.
 *
 * Отдельной строкой, а не внутри поля поиска: поле нужно, пока адрес ищут, а когда он выбран,
 * человеку нужно видеть результат и иметь возможность передумать одним тапом.
 */
@Composable
private fun OrderDestinationRow(
    text: String,
    onEdit: () -> Unit,
    tripMin: Int? = null,
    canAddStop: Boolean = false,
    onAddStop: () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = CanonSpace.md, end = CanonSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Place, contentDescription = null, tint = CanonGold,
             modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(CanonSpace.md))
        Column(Modifier.weight(1f)) {
            Text(text, style = CanonBodyStrong, color = CanonText,
                 maxLines = 1, overflow = TextOverflow.Ellipsis)
            // Появляется вместе с расчётом — плавно, а не рывком: цена и время приходят
            // одним ответом, и подпись не должна дёргать строку адреса при каждом пересчёте.
            AnimatedVisibility(
                visible = tripMin != null,
                enter = fadeIn(tween(CanonMotion.NORMAL)),
                exit = fadeOut(tween(CanonMotion.QUICK)),
            ) {
                Text(
                    appText("≈ $tripMin мин в пути", "≈ $tripMin мин юлда"),
                    style = CanonCaption, color = CanonMuted, maxLines = 1,
                )
            }
        }
        // «Заехать по пути» — круглой кнопкой у адреса (образец Яндекс). Механизм остановок
        // готов давно, но вход в него лежал в деталях заказа: о нём знал только тот, кто уже
        // полез в настройки. «Заедем за мамой» — обычная поездка, а не настройка.
        if (canAddStop) {
            Surface(
                onClick = onAddStop,
                shape = CircleShape,
                color = CanonBg,
                modifier = Modifier.minimumInteractiveComponentSize(),
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = appText("Заехать по пути", "Юлда инеп сығыу"),
                    tint = CanonGreen2,
                    modifier = Modifier.padding(CanonSpace.sm).size(20.dp),
                )
            }
        }
        TextButton(onClick = onEdit, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(appText("Изменить", "Үҙгәртеү"), style = CanonBody, color = CanonGreen2, maxLines = 1)
        }
    }
}

/** Остановка по пути — строка в карточке маршрута. Убрать можно одним тапом: список
 *  собирают на ходу, и ошибиться адресом здесь так же легко, как в «куда». */
@Composable
private fun OrderStopRow(text: String, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = CanonSpace.md, end = CanonSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Place, contentDescription = null, tint = CanonGreen2,
             modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(CanonSpace.md))
        Text(text, style = CanonBody, color = CanonText, maxLines = 1,
             overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Surface(
            onClick = onRemove,
            shape = CircleShape,
            color = CanonBg,
            modifier = Modifier.minimumInteractiveComponentSize(),
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = appText("Убрать остановку", "Туҡталышты алыу"),
                tint = CanonMuted,
                modifier = Modifier.padding(CanonSpace.sm).size(18.dp),
            )
        }
    }
}

/**
 * Строка точки маршрута: иконка, адрес, действие справа.
 *
 * Одна на подачу и на назначение — чтобы они не разъехались по высоте и выравниванию.
 * Подписи-лейбла над адресом нет намеренно (образец — Яндекс): иконка говорит то же самое,
 * а ярус текста забирает место у карты.
 */
@Composable
private fun OrderPointRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    text: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    // Без собственной подложки и без обёртки: строка живёт внутри общей карточки маршрута.
    // Своя заливка резала бы карточку на куски, а лишний контейнер растягивался на всю
    // доступную высоту и раздувал шапку на весь экран.
    // «Готово» от «жду ввода» отличает сам текст — адрес плотный и тёмный, приглашение серое.
    run {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = CanonSpace.md, end = CanonSpace.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(CanonSpace.md))
            // «Определяем…» → «Моя позиция» приходило рывком, будто экран моргнул.
            AnimatedContent(targetState = text, label = "orderPoint", modifier = Modifier.weight(1f)) { value ->
                Text(
                    value,
                    style = CanonBodyStrong,
                    color = CanonText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(actionLabel, style = CanonBody, color = CanonGreen2, maxLines = 1)
            }
        }
    }
}

/**
 * «Сказать рәхмәт» водителю — строкой на экране завершения.
 *
 * Механизм существовал давно: ручка на сервере, метод в клиенте, кнопка в чеке. Но чек человек
 * открывает редко — сначала надо догадаться, что он есть. Тёплое спасибо жило за двумя тапами
 * от той минуты, когда его хочется сказать.
 *
 * Это же и наш ответ чаевым: кнопки с суммой у нас нет намеренно — платёж идёт мимо приложения,
 * и такая кнопка врала бы. Жест ничего не стоит и до водителя доходит.
 */
@Composable
private fun InstantThanksRow(orderId: Int) {
    val scope = rememberCoroutineScope()
    var thanked by remember(orderId) { mutableStateOf(false) }
    var busy by remember(orderId) { mutableStateOf(false) }
    // Спрашиваем сервер, не благодарил ли он уже: предлагать второй раз то, что человек
    // сделал минуту назад, — мелкая, но обидная небрежность.
    LaunchedEffect(orderId) {
        ApiClient.getInstantTipInfo(orderId).onSuccess { thanked = it.alreadyThanked }
    }
    Surface(
        onClick = {
            if (thanked || busy) return@Surface
            busy = true
            scope.launch {
                ApiClient.sayInstantThanks(orderId).onSuccess { thanked = true }
                busy = false
            }
        },
        color = if (thanked) CanonMint else CanonBg,
        shape = CanonItemShape,
        modifier = Modifier.fillMaxWidth(),
    ) {
        // По центру, как и всё в карточке оценки: заголовок, звёзды, подпись и «пожаловаться»
        // центрированы, и один блок, прижатый влево, ломал ось — глаз спотыкался именно
        // на нём, хотя виноват был не он, а разнобой.
        Column(
            Modifier.fillMaxWidth().padding(CanonSpace.md).heightIn(min = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(CanonSpace.xs),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(CanonSpace.sm),
            ) {
                // Одно сердце, а не два: рядом с иконкой стояло ещё и эмодзи в тексте.
                Icon(
                    Icons.Default.Favorite,
                    contentDescription = null,
                    tint = CanonGreen2,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    if (thanked) appText("«Рәхмәт» сказан", "Рәхмәт әйтелде")
                    else appText("Понравилось? Скажи «рәхмәт»", "Оҡшанымы? Рәхмәт әйт"),
                    style = CanonBodyStrong,
                    color = CanonText,
                    textAlign = TextAlign.Center,
                )
            }
            Text(
                if (thanked) appText("Водитель это увидит", "Йөрөтөүсе быны күрер")
                else appText("Тёплое спасибо водителю — без денег", "Йөрөтөүсегә йылы рәхмәт — аҡсаһыҙ"),
                style = CanonCaption,
                color = CanonMuted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Поле «Куда едем?» — главная строка экрана заказа.
 *
 * Своё, а не Material: OutlinedTextField обязательно рисует рамку и плавающую подпись, а
 * именно они и делали экран похожим на анкету. Здесь вместо рамки — мягкая подложка, вместо
 * подписи — прямой вопрос, который исчезает, как только человек начал печатать.
 *
 * Кнопка карты живёт внутри поля: «куда» — одна мысль, и оба способа её задать (напечатать
 * или ткнуть в карту) должны быть в одном месте, а не в разных углах экрана.
 */
@Composable
private fun InstantWhereField(
    value: String,
    onValueChange: (String) -> Unit,
    onMap: () -> Unit,
) {
    // Без своей подложки и обёртки: поле — вторая строка внутри карточки маршрута.
    // Вторая заливка дала бы двойное скругление, а контейнер с fillMaxHeight растянул бы
    // строку на весь экран (проверено — шапка заняла всю шторку).
    run {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = CanonSpace.md, end = CanonSpace.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                tint = CanonMuted,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(CanonSpace.md))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(
                        appText("Куда едем?", "Ҡайҙа барабыҙ?"),
                        style = CanonBody,
                        color = CanonMuted,
                        maxLines = 1,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = CanonBodyStrong.copy(color = CanonText),
                    cursorBrush = SolidColor(CanonGreen2),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            IconButton(onClick = onMap, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.Default.Map,
                    contentDescription = appText("Выбрать точку на карте", "Картала нөктә һайлау"),
                    tint = CanonGreen2,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}
