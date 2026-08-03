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
                contentPadding = PaddingValues(top = 12.dp, bottom = 28.dp),
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
    val rub = order.passengerPayKop / 100
    AppCard(onClick = if (done) onClick else null) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = if (done) CanonMint else CanonWarnBg, shape = CircleShape) {
                    Icon(
                        Icons.Default.LocalTaxi,
                        contentDescription = null,
                        tint = if (done) CanonGreen2 else CanonWarn,
                        modifier = Modifier.padding(9.dp).size(20.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        tripDayLabel(order.createdAt),
                        color = CanonText, fontSize = 15.sp, fontWeight = FontWeight.Bold,
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
                if (done && rub > 0) {
                    Text(
                        "${fmtRub(rub)} ₽",
                        color = CanonText, fontSize = 18.sp, fontWeight = FontWeight.Black,
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
        Text(label, color = CanonMuted, fontSize = 13.sp, modifier = Modifier.width(64.dp))
        Text(value, color = CanonText, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.fillMaxWidth())
    }
}

/** ISO-время → «ДД.ММ.ГГГГ, ЧЧ:ММ». Не разобралось — дата как есть, без падения. */
@Composable
private fun tripDayLabel(iso: String?): String {
    if (iso.isNullOrBlank()) return appText("Поездка", "Сәфәр")
    val date = iso.substringBefore('T')
    val time = iso.substringAfter('T', "").take(5)
    val parts = date.split("-")
    val human = if (parts.size == 3) "${parts[2]}.${parts[1]}.${parts[0]}" else date
    return if (time.length == 5) "$human, $time" else human
}
