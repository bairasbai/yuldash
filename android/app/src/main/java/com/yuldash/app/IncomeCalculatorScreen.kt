package com.yuldash.app

// Калькулятор дохода автора (админ-инструмент). Чистый расчёт на клиенте — без бэкенда.
// Вводишь: поездок/день, число бизнес-партнёров, цену подписки, Boost, вкл/выкл такси —
// видишь месячную выручку и «чистыми» вживую. Полезно тебе и для показа партнёрам.
// Все цифры — ОЦЕНКА (зависят от подключения людей). Двуязычно, стиль Canon*.

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocalTaxi
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * Формат рублей с пробелами: 45000 → «45 000 ₽». Переиспользует единственный денежный
 * форматтер приложения ([fmtRub] из WalletScreen.kt) вместо своей копии: самодельная версия
 * собирала разряды реверсом строки и на отрицательных суммах, чья ЦЕЛАЯ часть ровно кратна
 * трём цифрам (-100, -100 000, …), отрывала минус лишним пробелом — «- 100 000 ₽» вместо
 * «-100 000 ₽». Сейчас ни один ползунок калькулятора не даёт отрицательный доход, но это
 * ровно тот формат, которым подписаны деньги везде в приложении — ему нужно поведение,
 * которое не сломается, если считать когда-нибудь начнут не только «сверху».
 */
internal fun rub(v: Double): String = "${fmtRub(v.roundToInt())} ₽"

@Composable
internal fun IncomeCalculatorScreen(onBack: () -> Unit) {
    // Ввод (значения-«ползунки»). Дефолты = «реальный» сценарий Сибай–Магнитогорск.
    var ridesPerDay by remember { mutableStateOf(80f) }
    var partners by remember { mutableStateOf(30f) }
    var subPrice by remember { mutableStateOf(1500f) }
    var boostsPerDay by remember { mutableStateOf(15f) }
    var taxiOn by remember { mutableStateOf(false) }
    var avgCheck by remember { mutableStateOf(500f) }
    var commissionPct by remember { mutableStateOf(8f) }
    var routes by remember { mutableStateOf(1f) }
    // Бензин водителя — чтобы доход был честным, а не завышенным (расход реально ест выручку).
    var kmPerRide by remember { mutableStateOf(40f) }     // средняя длина поездки, км
    var fuelPer100 by remember { mutableStateOf(8f) }     // расход, л/100 км
    var fuelPrice by remember { mutableStateOf(55f) }     // цена литра, ₽

    // Расчёт (в месяц). Boost — фикс ~70 ₽/подъём; расходы (серверы/налог/платёжка) ~15%.
    val boostPrice = 70.0
    val costsPct = 15.0
    val couponsIncome = partners.toDouble() * subPrice.toDouble()          // купоны/реклама
    val boostIncome = boostsPerDay.toDouble() * boostPrice * 30            // Boost водителей
    val taxiIncome = if (taxiOn) ridesPerDay.toDouble() * avgCheck.toDouble() * (commissionPct.toDouble() / 100.0) * 30 else 0.0
    val perRoute = couponsIncome + boostIncome + taxiIncome
    val gross = perRoute * routes.toDouble()                               // выручка со всех маршрутов
    val net = gross * (1.0 - costsPct / 100.0)                             // чистыми автору
    val animatedNet by animateFloatAsState(net.toFloat(), label = "net")

    // Честная экономика водителя (такси-режим): что платят пассажиры − бензин − комиссия сервиса.
    val fuelPerRide = kmPerRide.toDouble() / 100.0 * fuelPer100.toDouble() * fuelPrice.toDouble()
    val fuelMonth = fuelPerRide * ridesPerDay.toDouble() * 30 * routes.toDouble()
    val driverGrossMonth = ridesPerDay.toDouble() * avgCheck.toDouble() * 30 * routes.toDouble()
    val driverCommissionMonth = driverGrossMonth * (commissionPct.toDouble() / 100.0)
    // БЕЗ coerceAtLeast(0.0) (P3, решение Александра): при жадных настройках бензина строка
    // ниже честно вычитает бензин+комиссию из валового — если спрятать результат за нулём,
    // «вычли X + Y» перестаёт сходиться с «чистыми 0 ₽», и цифры в карточке противоречат
    // друг другу. Показываем убыток как есть — он с тем же знаком минус, что и везде в
    // приложении (rub() → fmtRub, минус не отрывается).
    val driverNetMonth = driverGrossMonth - fuelMonth - driverCommissionMonth

    Scaffold(
        containerColor = CanonBg,
        topBar = { ScreenTopBar(appText("Калькулятор дохода", "Килем калькуляторы"), onBack) },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            // ── Результат (большая карточка сверху) ──
            item {
                Surface(color = CanonGreenInk, shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(appText("Чистыми тебе в месяц", "Айына таҙа килем"), color = CanonOnAccent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(rub(animatedNet.toDouble()), color = CanonOnAccent, fontWeight = FontWeight.Bold, fontSize = 34.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            appText("Выручка ${rub(gross)} − расходы ~${costsPct.roundToInt()}% (серверы, налог, платёжка)",
                                    "Килем ${rub(gross)} − сығымдар ~${costsPct.roundToInt()}% (серверҙар, һалым, түләү)"),
                            color = CanonOnAccent, fontSize = 12.sp,
                        )
                    }
                }
            }
            // ── Из чего складывается ──
            item {
                Surface(color = CanonSurface, shape = CanonItemShape) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(appText("Из чего доход", "Килем нимәнән"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        BreakdownRow(appText("Купоны и реклама бизнеса", "Купондар һәм бизнес рекламаһы"), rub(couponsIncome * routes))
                        BreakdownRow(appText("Boost водителей", "Йөрөтөүселәр Boost'ы"), rub(boostIncome * routes))
                        if (taxiOn) BreakdownRow(appText("Комиссия такси", "Такси комиссияһы"), rub(taxiIncome * routes))
                    }
                }
            }
            // ── Ползунки ввода ──
            item { CalcSlider(appText("Поездок в день (по маршруту)", "Көнөнә сәфәр (маршрут буйынса)"), ridesPerDay, 0f..300f, ridesPerDay.roundToInt().toString(), testTag = "calc_ridesPerDay") { ridesPerDay = it } }
            item { CalcSlider(appText("Бизнес-партнёров", "Бизнес-партнёрҙар"), partners, 0f..100f, partners.roundToInt().toString()) { partners = it } }
            item { CalcSlider(appText("Цена подписки партнёра, ₽/мес", "Партнёр яҙылыуы, ₽/ай"), subPrice, 500f..3000f, "${subPrice.roundToInt()} ₽") { subPrice = it } }
            item { CalcSlider(appText("Boost в день (подъёмов)", "Көнөнә Boost (күтәреү)"), boostsPerDay, 0f..50f, boostsPerDay.roundToInt().toString()) { boostsPerDay = it } }
            item { CalcSlider(appText("Маршрутов / городов", "Маршрут / ҡала"), routes, 1f..54f, routes.roundToInt().toString()) { routes = it } }

            // ── Такси (опционально) ──
            item {
                Surface(color = CanonSurface, shape = CanonItemShape) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.LocalTaxi, contentDescription = null, tint = CanonTaxi, modifier = Modifier.height(22.dp))
                            Spacer(Modifier.height(0.dp))
                            Text(appText("  Включить такси (комиссия)", "  Таксины ҡабыҙыу (комиссия)"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                            // appText — @Composable, внутри .semantics{} (обычная лямбда) звать
                            // его нельзя: значение берём ЗАРАНЕЕ, снаружи.
                            val taxiSwitchLabel = appText("Включить такси", "Таксины ҡабыҙыу")
                            Switch(
                                checked = taxiOn, onCheckedChange = { taxiOn = it },
                                colors = SwitchDefaults.colors(checkedTrackColor = CanonGreen2),
                                // Без этого TalkBack читает голый «включено/выключено» без темы —
                                // человек не понимает, какой именно переключатель перед ним.
                                modifier = Modifier.semantics { contentDescription = taxiSwitchLabel },
                            )
                        }
                        if (taxiOn) {
                            CalcSlider(appText("Средний чек места, ₽", "Урын уртаса хаҡы, ₽"), avgCheck, 200f..1500f, "${avgCheck.roundToInt()} ₽", testTag = "calc_avgCheck") { avgCheck = it }
                            CalcSlider(appText("Комиссия, %", "Комиссия, %"), commissionPct, 0f..10f, "${commissionPct.roundToInt()} %", testTag = "calc_commissionPct") { commissionPct = it }
                            // Бензин — вычитаем честно, иначе доход водителя завышен.
                            CalcSlider(appText("Длина поездки, км", "Сәфәр оҙонлоғо, км"), kmPerRide, 5f..300f, "${kmPerRide.roundToInt()} км", testTag = "calc_kmPerRide") { kmPerRide = it }
                            CalcSlider(appText("Расход бензина, л/100 км", "Бензин сарыфы, л/100 км"), fuelPer100, 4f..15f, "${fuelPer100.roundToInt()} л", testTag = "calc_fuelPer100") { fuelPer100 = it }
                            CalcSlider(appText("Цена бензина, ₽/л", "Бензин хаҡы, ₽/л"), fuelPrice, 40f..80f, "${fuelPrice.roundToInt()} ₽", testTag = "calc_fuelPrice") { fuelPrice = it }
                            // «Чистыми после бензина» рядом с валовым — прозрачно, что именно вычли.
                            Surface(color = CanonMint, shape = RoundedCornerShape(14.dp)) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(appText("Сколько остаётся водителю", "Йөрөтөүсегә күпме ҡала"), color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(appText("Валовый (что платят)", "Ялпы (нимә түләйҙәр)"), color = CanonMuted, fontSize = 12.sp)
                                            Text(rub(driverGrossMonth), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                        }
                                        Column(Modifier.weight(1f)) {
                                            Text(appText("Чистыми после бензина", "Бензиндан һуң таҙа"), color = CanonMuted, fontSize = 12.sp)
                                            // Убыток — тем же красным, что и ошибки ввода по всему приложению:
                                            // «0 ₽» при реальном минусе выглядело бы как «всё в порядке».
                                            Text(
                                                rub(driverNetMonth),
                                                color = if (driverNetMonth < 0) CanonRed else CanonGreen2,
                                                fontWeight = FontWeight.Bold, fontSize = 19.sp,
                                            )
                                        }
                                    }
                                    Text(
                                        appText("Из валового вычли: бензин ${rub(fuelMonth)} + комиссия ${commissionPct.roundToInt()}% (${rub(driverCommissionMonth)}).",
                                                "Ялпынан алдыҡ: бензин ${rub(fuelMonth)} + комиссия ${commissionPct.roundToInt()}% (${rub(driverCommissionMonth)})."),
                                        color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp,
                                    )
                                }
                            }
                        } else {
                            Text(appText("Попутка бесплатна для людей — доход только с бизнеса. Такси добавляет комиссию.",
                                         "Юлдаш кешеләргә бушлай — килем тик бизнестан. Такси комиссия өҫтәй."),
                                 color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp)
                        }
                    }
                }
            }
            // ── Дисклеймер ──
            item {
                Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = CanonMuted, modifier = Modifier.height(18.dp))
                    Spacer(Modifier.height(0.dp))
                    Text(
                        appText("  Это оценка, а не обещание: доход зависит от того, сколько людей и бизнесов подключится. Цены — стартовые, поменяешь в админке.",
                                "  Был баһалау, вәғәҙә түгел: килем күпме кеше һәм бизнес ҡушылыуына бәйле. Хаҡтар — башланғыс, админкала үҙгәртәһең."),
                        color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun BreakdownRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = CanonMuted, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

@Composable
private fun CalcSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    // Пусто в продакшене ничего не меняет (testTag не рисуется); лист 1.4 проставляет его
    // ползункам, чьё ровно заданное значение проверяет тест честной экономики водителя
    // (gross − бензин − комиссия), не полагаясь на подбор координат жеста по пикселям.
    testTag: String = "",
    onChange: (Float) -> Unit,
) {
    Surface(color = CanonSurface, shape = CanonItemShape) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = CanonText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Surface(color = CanonMint, shape = RoundedCornerShape(8.dp)) {
                    Text(valueText, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
            Slider(
                value = value, onValueChange = onChange, valueRange = range,
                // Без contentDescription TalkBack читал голое число/проценты со шкалы —
                // «80» вместо «Поездок в день (по маршруту)»; label уже двуязычный (appText).
                modifier = Modifier
                    .semantics { contentDescription = label }
                    .let { if (testTag.isEmpty()) it else it.testTag(testTag) },
                colors = SliderDefaults.colors(thumbColor = CanonGreen2, activeTrackColor = CanonGreen2, inactiveTrackColor = CanonBorder),
            )
        }
    }
}
