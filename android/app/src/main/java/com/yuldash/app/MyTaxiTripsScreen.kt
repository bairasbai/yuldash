package com.yuldash.app

// ============================ «Мои поездки на такси» (пассажир) ============================
// Зачем экран: чек за поездку жил ровно одну сессию — он открывался только из финальной карточки
// только что завершённого заказа. Закрыл экран → чек потерян навсегда. А чек нужен позже: справка
// на работу, спор по сумме, «забыл вещь в машине», просто «сколько я потратил за месяц».
// У Яндекс Такси история поездок — базовый экран, у нас её не было вовсе.
//
// Что показываем: завершённые и отменённые заказы сверху вниз, с датой, маршрутом и суммой.
// Тап по завершённой → чек. Все состояния (загрузка / пусто / ошибка) + обновление жестом.

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocalTaxi
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.InstantOrderDto

/**
 * История поездок пассажира на такси.
 *
 * @param onOpenReceipt открыть чек за поездку (для завершённых)
 */
@Composable
internal fun MyTaxiTripsScreen(onBack: () -> Unit, onOpenReceipt: (Int) -> Unit) {
    var orders by remember { mutableStateOf<List<InstantOrderDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(reload) {
        loading = true
        ApiClient.getMyInstantOrders(limit = 50)
            .onSuccess { list ->
                // Показываем только завершённое: активный заказ живёт на своём экране,
                // здесь он бы сбивал с толку («поездка идёт, а я в истории»).
                orders = list.filter { it.status == "done" || it.status == "cancelled" || it.status == "expired" }
                error = false
            }
            .onFailure { error = true }
        loading = false
    }

    Scaffold(
        containerColor = CanonBg,
        topBar = { ScreenTopBar(appText("Мои поездки на такси", "Такситағы сәфәрҙәрем"), onBack) },
    ) { padding ->
        AppPullRefresh(
            refreshing = loading && orders.isNotEmpty(),
            onRefresh = { reload++ },
            modifier = Modifier.padding(padding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
            ) {
                when {
                    loading && orders.isEmpty() -> item {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            repeat(3) { SkeletonCard(lines = 2) }
                        }
                    }
                    error && orders.isEmpty() -> item { AppErrorState(onRetry = { reload++ }) }
                    orders.isEmpty() -> item {
                        AppEmptyState(
                            title = appText("Поездок пока нет", "Сәфәрҙәр әлегә юҡ"),
                            text = appText(
                                "Здесь появятся все твои поездки на такси — с чеком за каждую.",
                                "Бында такситағы бөтә сәфәрҙәрең күренер — һәр береһе өсөн чек менән.",
                            ),
                            icon = Icons.Default.History,
                        )
                    }
                    else -> items(orders, key = { it.id }) { o ->
                        TaxiTripHistoryCard(
                            order = o,
                            onClick = { if (o.status == "done") onOpenReceipt(o.id) },
                        )
                    }
                }
            }
        }
    }
}

/** Карточка одной поездки: дата, маршрут, сумма. Завершённая — открывает чек. */
@Composable
private fun TaxiTripHistoryCard(order: InstantOrderDto, onClick: () -> Unit) {
    val done = order.status == "done"
    // Сумма, которую человек РЕАЛЬНО отдал: со скидкой по промокоду, если она была. Полная цена
    // здесь была бы враньём — в истории ищут, сколько потратили, а не прейскурант. Фолбэк на
    // старый сервер уже внутри passengerPayKop, второй раз его городить не нужно.
    // Копейки не режем: цена такси считается по тарифу и суржу, круглой почти не бывает.
    // «188 ₽» в списке против «188,50 ₽» в чеке — первый же повод усомниться в приложении.
    val payKop = order.passengerPayKop
    AppCard(onClick = if (done) onClick else null) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = if (done) CanonMint else CanonWarnBg, shape = CircleShape) {
                    Icon(
                        Icons.Default.LocalTaxi,
                        contentDescription = null,
                        tint = if (done) CanonGreen2 else CanonWarn,
                        modifier = Modifier.padding(8.dp).size(20.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        tripDayLabel(order.createdAt),
                        color = CanonText, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    )
                    Text(
                        when (order.status) {
                            "done" -> appText("Поездка завершена", "Сәфәр тамамланды")
                            "cancelled" -> appText("Отменена", "Кире алынған")
                            else -> appText("Машину не нашли", "Машина табылманы")
                        },
                        color = if (done) CanonMuted else CanonWarn, fontSize = 12.sp,
                    )
                }
                if (done && payKop > 0) {
                    Text(
                        kopToRub(payKop),
                        color = CanonText, fontSize = 19.sp, fontWeight = FontWeight.Bold,
                    )
                }
            }
            TripRouteLine(appText("Откуда", "Ҡайҙан"), order.fromText)
            TripRouteLine(appText("Куда", "Ҡайҙа"), order.toText)
            if (done) {
                Text(
                    appText("Нажми, чтобы открыть чек", "Чекты асыр өсөн баҫ"),
                    color = CanonGreen2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** Строка маршрута: подпись + адрес. Длинный адрес переносится, а не режется многоточием. */
@Composable
private fun TripRouteLine(label: String, value: String) {
    if (value.isBlank()) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = CanonMuted, fontSize = 14.sp, modifier = Modifier.width(64.dp))
        Text(value, color = CanonText, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.fillMaxWidth())
    }
}

/** ISO-время с сервера (UTC) → «ДД.ММ.ГГГГ, ЧЧ:ММ» в часах человека.
 *  Нарезка строки показывала бы UTC как местное — поездка в 23:10 попадала бы во вчерашний
 *  день, и человек не нашёл бы её в истории там, где ищет (разбор №2, 2026-08-03). */
@Composable
private fun tripDayLabel(iso: String?): String {
    if (iso.isNullOrBlank()) return appText("Поездка", "Сәфәр")
    val ms = parseIsoUtcMillis(iso) ?: return iso.substringBefore('T')
    val c = java.util.Calendar.getInstance().apply { timeInMillis = ms }
    return String.format(
        java.util.Locale.US, "%02d.%02d.%04d, %02d:%02d",
        c.get(java.util.Calendar.DAY_OF_MONTH), c.get(java.util.Calendar.MONTH) + 1,
        c.get(java.util.Calendar.YEAR),
        c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE),
    )
}
