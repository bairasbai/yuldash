package com.yuldash.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Percent
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.DriverTaxiRideDto
import com.yuldash.app.data.DriverTaxiRidesDto

/**
 * «Мои поездки такси» — расшифровка денег по каждой поездке (GET /driver/taxi-rides).
 *
 * Зачем: «Мой заработок» отдаёт одну сумму за период, и спор «Юлдаш говорит 4200, я насчитал
 * 4600 — где мои 400?» закрыть было нечем. В таксопарках это причина №1 ухода водителя.
 * Здесь по каждой поездке видно: цена пассажиру → комиссия платформы → чистыми водителю.
 *
 * Деньги: price — в ₽ (как показываем пассажиру), fee/net — в копейках (kopToRub).
 * Состояния: загрузка (скелетоны) / ошибка + «Повторить» / пусто (новичок без поездок).
 */
@Composable
internal fun DriverTaxiRidesScreen(onBack: () -> Unit, onOpenReceipt: (Int) -> Unit = {}) {
    var data by remember { mutableStateOf<DriverTaxiRidesDto?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(reload) {
        loading = true; error = false
        ApiClient.getDriverTaxiRides()
            .onSuccess { data = it }
            .onFailure { error = true }
        loading = false
    }

    Scaffold(
        containerColor = CanonBg,
        topBar = { ScreenTopBar(appText("Мои поездки такси", "Такси сәфәрҙәрем"), onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 28.dp),
        ) {
            val d = data
            when {
                loading && d == null -> {
                    item { SkeletonCard(lines = 3) }
                    item { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { repeat(5) { SkeletonCard(lines = 2) } } }
                }
                error && d == null -> item { AppErrorState(onRetry = { reload++ }) }
                d == null || d.rides.isEmpty() -> item {
                    AppEmptyState(
                        title = appText("Поездок пока нет", "Әлегә сәфәр юҡ"),
                        text = appText(
                            "Здесь появится расшифровка по каждой поездке: цена, наша комиссия и сколько остаётся тебе.",
                            "Бында һәр сәфәр буйынса яҙма күренәсәк: хаҡ, беҙҙең комиссия һәм һиңә күпме ҡала.",
                        ),
                        icon = Icons.Default.DirectionsCar,
                    )
                }
                else -> {
                    item { TaxiRidesTotalsCard(d) }
                    item {
                        Text(
                            appText("Каждая поездка", "Һәр сәфәр"),
                            color = CanonMuted, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    items(d.rides, key = { it.orderId }) { ride ->
                        TaxiRideRow(ride, onClick = { onOpenReceipt(ride.orderId) })
                    }
                    item {
                        Text(
                            appText(
                                "Комиссию мы не удерживаем из твоих денег: пассажир платит тебе целиком, а комиссия копится долгом и платится отдельно.",
                                "Комиссияны һинең аҡсанан тотоп ҡалмайбыҙ: юлаусы һиңә тулыһынса түләй, комиссия айырым бурыс булып йыйыла.",
                            ),
                            color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp,
                        )
                    }
                }
            }
        }
    }
}

/** Итоги: сколько собрано с пассажиров, сколько наша комиссия и сколько осталось водителю. */
@Composable
private fun TaxiRidesTotalsCard(d: DriverTaxiRidesDto) {
    AppCard {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                appText("За последние поездки", "Һуңғы сәфәрҙәр өсөн"),
                color = CanonMuted, fontSize = 13.sp,
            )
            Text("${fmtRub(d.totalNetKop / 100)} ₽", color = CanonText, fontSize = 36.sp, fontWeight = FontWeight.Black)
            Text(
                appText("твои чистыми", "һинең таҙа"),
                color = CanonGreen2, fontSize = 14.sp, fontWeight = FontWeight.Bold,
            )
            TaxiMoneyLine(
                Icons.Default.Payments, CanonMint, CanonGreen2,
                appText("Пассажиры заплатили", "Юлаусылар түләне"),
                "${fmtRub(d.totalPriceRub)} ₽",
            )
            TaxiMoneyLine(
                Icons.Default.Percent, CanonWarnBg, CanonWarn,
                appText("Комиссия Юлдаша", "Юлдаш комиссияһы"),
                kopToRub(d.totalFeeKop),
            )
            TaxiMoneyLine(
                Icons.Default.Savings, CanonMint, CanonGreen2,
                appText("Осталось тебе", "Һиңә ҡалды"),
                kopToRub(d.totalNetKop),
            )
        }
    }
}

@Composable
private fun TaxiMoneyLine(icon: ImageVector, bg: Color, tint: Color, label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Surface(color = bg, shape = CircleShape) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.padding(8.dp).size(17.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(label, color = CanonMuted, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, color = CanonText, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

/** Одна поездка: маршрут, дата, цена → комиссия → чистыми + метки «оплачено» / статус комиссии. */
@Composable
private fun TaxiRideRow(r: DriverTaxiRideDto, onClick: () -> Unit) {
    AppCard(onClick = onClick, shape = CanonItemShape) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${r.from.ifBlank { "—" }} → ${r.to.ifBlank { "—" }}",
                        color = CanonText, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Text(formatDepart(r.doneAt), color = CanonMuted, fontSize = 12.sp)
                }
                Spacer(Modifier.width(10.dp))
                Text("${fmtRub(r.priceRub)} ₽", color = CanonText, fontSize = 20.sp, fontWeight = FontWeight.Black)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    appText("Комиссия ", "Комиссия ") + kopToRub(r.feeKop),
                    color = CanonMuted, fontSize = 13.sp,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    appText("Чистыми ", "Таҙа ") + kopToRub(r.netKop),
                    color = CanonGreen2, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                TaxiRideTag(
                    if (r.paid) appText("Оплачено", "Түләнгән") else appText("Не отмечено", "Билдәләнмәгән"),
                    if (r.paid) CanonMint else CanonWarnBg,
                    if (r.paid) CanonGreen2 else CanonWarn,
                )
                if (r.paymentMethod.isNotBlank()) {
                    TaxiRideTag(payMethodLabel(r.paymentMethod), CanonMint, CanonGreen2)
                }
                feeStatusTag(r.feeStatus)?.let { (label, bg, tint) -> TaxiRideTag(label, bg, tint) }
            }
        }
    }
}

@Composable
private fun TaxiRideTag(label: String, bg: Color, tint: Color) {
    Surface(color = bg, shape = RoundedCornerShape(999.dp)) {
        Text(label, color = tint, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp))
    }
}

/** Статус комиссии по поездке. none = долга нет (промо 0% / грошовый заказ) → метку не рисуем. */
@Composable
private fun feeStatusTag(status: String): Triple<String, Color, Color>? = when (status) {
    "pending" -> Triple(appText("Комиссия не оплачена", "Комиссия түләнмәгән"), CanonWarnBg, CanonWarn)
    "declared" -> Triple(appText("Оплату проверяем", "Түләүҙе тикшерәбеҙ"), CanonWarnBg, CanonWarn)
    "paid" -> Triple(appText("Комиссия оплачена", "Комиссия түләнгән"), CanonMint, CanonGreen2)
    "void" -> Triple(appText("Комиссия списана", "Комиссия һүндерелгән"), CanonMint, CanonGreen2)
    else -> null
}
