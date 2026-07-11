package com.yuldash.app

// ═══════════════════ M3: Админ — доставки посылок ═══════════════════
// Список всех посылок + плашка дохода (сколько доставлено, собранный сбор Юлдаша).
// По паттерну AdminPartnersScreen: умная обёртка держит стейт+сеть, LazyColumn рисует все состояния.

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.ParcelDto
import com.yuldash.app.data.ParcelStatementDto
import kotlinx.coroutines.launch

@Composable
internal fun AdminParcelsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<ParcelDto>>(emptyList()) }
    var statement by remember { mutableStateOf<ParcelStatementDto?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val loadErr = appText("Не удалось загрузить. Проверь интернет.", "Йөкләп булманы. Интернетты тикшер.")

    fun reload() {
        loading = true; error = null
        scope.launch {
            ApiClient.adminListParcels()
                .onSuccess {
                    statement = it.statement
                    // Активные сверху, затем по дате (свежие выше).
                    list = it.parcels.sortedWith(
                        compareByDescending<ParcelDto> { p -> p.status != "delivered" && p.status != "canceled" && p.status != "cancelled" }
                            .thenByDescending { p -> p.createdAt },
                    )
                }
                .onFailure { error = (it as? com.yuldash.app.data.ApiException)?.message ?: loadErr }
            loading = false
        }
    }
    LaunchedEffect(Unit) { reload() }

    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Посылки", "Бандеролдәр"), onBack) }) { padding ->
        LazyColumn(
            Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            item {
                Text(
                    appText(
                        "Все доставки посылок и собранный сбор Юлдаша. Видно только администратору.",
                        "Бөтә бандероль илтеүҙәре һәм йыйылған Юлдаш сборы. Тик админға күренә.",
                    ),
                    color = CanonMuted, fontSize = 14.sp, lineHeight = 19.sp,
                )
            }
            statement?.let { s -> item { ParcelStatementCard(s) } }
            when {
                loading && list.isEmpty() -> {
                    item { SkeletonCard(lines = 3) }
                    item { SkeletonCard(lines = 3) }
                }
                error != null && list.isEmpty() -> item { ListedError(error ?: "") { reload() } }
                list.isEmpty() -> item {
                    ListedEmpty(
                        appText("Пока нет посылок", "Әлегә бандеролдәр юҡ"),
                        appText("Здесь появятся все отправленные посылки.", "Бында бөтә ебәрелгән бандеролдәр күренер."),
                    )
                }
                else -> items(list.size, key = { "adp-" + list[it].id }) { i ->
                    Box(Modifier.appearIn(i.coerceAtMost(6))) { AdminParcelCard(list[i]) }
                }
            }
        }
    }
}

@Composable
private fun ParcelStatementCard(s: ParcelStatementDto) {
    Surface(color = CanonMint, shape = CanonCardShape, border = BorderStroke(1.dp, CanonGreen2)) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = CanonSurface, shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Default.Payments, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(12.dp).size(26.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(appText("Собранный сбор", "Йыйылған сбор"), color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(kopToRub(s.collectedFeeKop), color = CanonText, fontWeight = FontWeight.Black, fontSize = 26.sp)
                Text(appText("Доставлено посылок: ${s.deliveredCount}", "Тапшырылған бандеролдәр: ${s.deliveredCount}"), color = CanonMuted, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun AdminParcelCard(p: ParcelDto) {
    Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.Place, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(16.dp))
                    Text(p.fromCity.ifBlank { "—" }, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("→", color = CanonMuted, fontSize = 14.sp)
                    Text(p.toCity.ifBlank { "—" }, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                ParcelStatusChip(p.status)
            }
            if (p.description.isNotBlank()) {
                Text(parcelSizeLabel(p.size) + "  ·  " + p.description, color = CanonMuted, fontSize = 13.sp)
            } else {
                Text(parcelSizeLabel(p.size), color = CanonMuted, fontSize = 13.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Person, contentDescription = null, tint = CanonMuted, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text(appText("Получатель: ", "Алыусы: ") + p.receiverName, color = CanonText, fontSize = 13.sp)
                Spacer(Modifier.weight(1f))
                if (p.feeKop > 0) Text(kopToRub(p.feeKop), color = CanonGreen2, fontWeight = FontWeight.Black, fontSize = 15.sp)
            }
            p.courier?.let { cr ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LocalShipping, contentDescription = null, tint = CanonMuted, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(appText("Курьер: ", "Курьер: ") + cr.name.ifBlank { "#${cr.id}" }, color = CanonMuted, fontSize = 13.sp)
                }
            }
            shortDate(p.deliveredAt)?.let { d ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(appText("Доставлена $d", "$d тапшырылды"), color = CanonGreen2, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
