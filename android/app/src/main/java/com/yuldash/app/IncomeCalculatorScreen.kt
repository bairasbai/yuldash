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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** Формат рублей с пробелами: 45000 → «45 000 ₽». */
private fun rub(v: Double): String {
    val n = v.roundToInt()
    val s = n.toString().reversed().chunked(3).joinToString(" ").reversed()
    return "$s ₽"
}

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

    Scaffold(
        containerColor = CanonBg,
        topBar = { ScreenTopBar(appText("Калькулятор дохода", "Килем калькуляторы"), onBack) },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            // ── Результат (большая карточка сверху) ──
            item {
                Surface(color = CanonGreen2, shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(appText("Чистыми тебе в месяц", "Айына таҙа килем"), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(rub(animatedNet.toDouble()), color = Color.White, fontWeight = FontWeight.Black, fontSize = 34.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            appText("Выручка ${rub(gross)} − расходы ~${costsPct.roundToInt()}% (серверы, налог, платёжка)",
                                    "Килем ${rub(gross)} − сығымдар ~${costsPct.roundToInt()}% (серверҙар, һалым, түләү)"),
                            color = Color.White, fontSize = 12.sp,
                        )
                    }
                }
            }
            // ── Из чего складывается ──
            item {
                Surface(color = CanonSurface, shape = CanonItemShape) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(appText("Из чего доход", "Килем нимәнән"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 15.sp)
                        BreakdownRow(appText("Купоны и реклама бизнеса", "Купондар һәм бизнес рекламаһы"), rub(couponsIncome * routes))
                        BreakdownRow(appText("Boost водителей", "Водителдәр Boost'ы"), rub(boostIncome * routes))
                        if (taxiOn) BreakdownRow(appText("Комиссия такси", "Такси комиссияһы"), rub(taxiIncome * routes))
                    }
                }
            }
            // ── Ползунки ввода ──
            item { CalcSlider(appText("Поездок в день (по маршруту)", "Көнөнә сәфәр (маршрут буйынса)"), ridesPerDay, 0f..300f, ridesPerDay.roundToInt().toString()) { ridesPerDay = it } }
            item { CalcSlider(appText("Бизнес-партнёров", "Бизнес-партнёрҙар"), partners, 0f..100f, partners.roundToInt().toString()) { partners = it } }
            item { CalcSlider(appText("Цена подписки партнёра, ₽/мес", "Партнёр яҙылыуы, ₽/ай"), subPrice, 500f..3000f, "${subPrice.roundToInt()} ₽") { subPrice = it } }
            item { CalcSlider(appText("Boost в день (подъёмов)", "Көнөнә Boost (күтәреү)"), boostsPerDay, 0f..50f, boostsPerDay.roundToInt().toString()) { boostsPerDay = it } }
            item { CalcSlider(appText("Маршрутов / городов", "Маршрут / ҡала"), routes, 1f..54f, routes.roundToInt().toString()) { routes = it } }

            // ── Такси (опционально) ──
            item {
                Surface(color = CanonSurface, shape = CanonItemShape) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.LocalTaxi, contentDescription = null, tint = CanonTaxi, modifier = Modifier.height(22.dp))
                            Spacer(Modifier.height(0.dp))
                            Text(appText("  Включить такси (комиссия)", "  Таксины ҡабыҙыу (комиссия)"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            Switch(checked = taxiOn, onCheckedChange = { taxiOn = it }, colors = SwitchDefaults.colors(checkedTrackColor = CanonGreen2))
                        }
                        if (taxiOn) {
                            CalcSlider(appText("Средний чек места, ₽", "Урын уртаса хаҡы, ₽"), avgCheck, 200f..1500f, "${avgCheck.roundToInt()} ₽") { avgCheck = it }
                            CalcSlider(appText("Комиссия, %", "Комиссия, %"), commissionPct, 0f..10f, "${commissionPct.roundToInt()} %") { commissionPct = it }
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
private fun CalcSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, valueText: String, onChange: (Float) -> Unit) {
    Surface(color = CanonSurface, shape = CanonItemShape) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = CanonText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Surface(color = CanonMint, shape = RoundedCornerShape(8.dp)) {
                    Text(valueText, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), color = CanonGreen2, fontWeight = FontWeight.Black, fontSize = 13.sp)
                }
            }
            Slider(
                value = value, onValueChange = onChange, valueRange = range,
                colors = SliderDefaults.colors(thumbColor = CanonGreen2, activeTrackColor = CanonGreen2, inactiveTrackColor = CanonBorder),
            )
        }
    }
}
