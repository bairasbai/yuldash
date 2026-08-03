package com.yuldash.app

// ═══════════════════ M3: Админ — доставки посылок ═══════════════════
// Список всех посылок + честная выписка по деньгам: сколько курьеры реально оплатили,
// сколько должны, и отдельно — сбор «по пути», который выставить некому (это не выручка).
// По паттерну AdminPartnersScreen: умная обёртка держит стейт+сеть, LazyColumn рисует все состояния.

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Рычаги админа: раньше экран был «только посмотреть» — звонит бабушка «посылка две недели
    // висит», а отменить, снять курьера или закрыть вручную было НЕЧЕМ (аудит 2026-07-26).
    var action by remember { mutableStateOf<Pair<ParcelDto, String>?>(null) }
    val loadErr = appText("Не удалось загрузить. Проверь интернет.", "Йөкләп булманы. Интернетты тикшер.")

    fun reload(pull: Boolean = false) {
        if (pull) refreshing = true else loading = true
        error = null
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
            loading = false; refreshing = false
        }
    }
    LaunchedEffect(Unit) { reload() }

    // Какое состояние сейчас на экране — отдельным значением, чтобы скелетон, ошибка, «пусто»
    // и список сменяли друг друга плавно, а не подменялись кадром.
    val phase = when {
        loading && list.isEmpty() -> "load"
        error != null && list.isEmpty() -> "err"
        list.isEmpty() -> "empty"
        else -> "ok"
    }

    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Посылки", "Бандеролдәр"), onBack) }) { padding ->
        AppPullRefresh(
            refreshing = refreshing,
            onRefresh = { if (!refreshing) reload(pull = true) },
            modifier = Modifier.padding(padding),
        ) {
            LazyColumn(
                Modifier.padding(horizontal = 16.dp),
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
                item(key = "state") {
                    AnimatedContent(
                        targetState = phase,
                        transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(140)) },
                        label = "admin-parcels-state",
                    ) { p ->
                        when (p) {
                            "load" -> Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                SkeletonCard(lines = 3)
                                SkeletonCard(lines = 3)
                            }
                            "err" -> ListedError(error ?: "") { reload() }
                            "empty" -> ListedEmpty(
                                appText("Пока нет посылок", "Әлегә бандеролдәр юҡ"),
                                appText("Здесь появятся все отправленные посылки.", "Бында бөтә ебәрелгән бандеролдәр күренер."),
                            )
                            // Сам список — lazy-элементами ниже: внутри AnimatedContent прокрутка
                            // перестала бы быть ленивой.
                            else -> Spacer(Modifier.height(0.dp))
                        }
                    }
                }
                if (phase == "ok") {
                    items(list.size, key = { "adp-" + list[it].id }) { i ->
                        Box(Modifier.appearIn(i.coerceAtMost(6))) {
                            AdminParcelCard(list[i], onAction = { action = it })
                        }
                    }
                }
            }
        }
    }

    action?.let { (parcel, kind) ->
        AdminParcelActionDialog(
            parcel = parcel,
            kind = kind,
            onDismiss = { action = null },
            onDone = { action = null; reload() },
        )
    }
}

/** Что админ делает с зависшей доставкой. Три честных выхода, все с причиной для сторон. */
@Composable
private fun AdminParcelActionDialog(
    parcel: ParcelDto,
    kind: String,                 // cancel | release | close
    onDismiss: () -> Unit,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var reason by remember(parcel.id, kind) { mutableStateOf("") }
    var closeStatus by remember(parcel.id) { mutableStateOf("returned") }
    var busy by remember(parcel.id, kind) { mutableStateOf(false) }
    var err by remember(parcel.id, kind) { mutableStateOf<String?>(null) }
    val errFallback = appText("Не получилось. Проверь сеть и повтори.", "Булманы. Селтәрҙе тикшереп ҡабатла.")

    val title = when (kind) {
        "cancel" -> appText("Отменить доставку", "Илтеүҙе кире алыу")
        "release" -> appText("Снять курьера", "Курьерҙы алыу")
        else -> appText("Закрыть доставку", "Илтеүҙе ябыу")
    }
    val hint = when (kind) {
        "cancel" -> appText(
            "Обе стороны получат причину. Комиссию за неоказанную услугу не берём.",
            "Ике яҡ та сәбәбен ала. Күрһәтелмәгән хеҙмәт өсөн комиссия алмайбыҙ.",
        )
        "release" -> appText(
            "Посылка вернётся в общий список — её сможет взять другой курьер.",
            "Бандероль дөйөм исемлеккә ҡайта — уны башҡа курьер ала ала.",
        )
        else -> appText(
            "Когда разобрались вне приложения. При «вернулась» и «отменена» комиссия обнуляется.",
            "Ҡулланманан тыш хәл ителгәс. «Ҡайтты» һәм «кире алынды» осрағында комиссия юҡҡа сыға.",
        )
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = CanonSurface,
        title = { Text(title, color = CanonText, fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(hint, color = CanonMuted, fontSize = 13.sp, lineHeight = 18.sp)
                if (kind == "close") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        AdminCloseOption(appText("Вернулась отправителю", "Ебәреүсегә ҡайтты"), closeStatus == "returned") { closeStatus = "returned" }
                        AdminCloseOption(appText("Всё-таки доставлена", "Барыбер тапшырылған"), closeStatus == "delivered") { closeStatus = "delivered" }
                        AdminCloseOption(appText("Отменена", "Кире алынған"), closeStatus == "canceled") { closeStatus = "canceled" }
                    }
                }
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it.take(200) },
                    label = { Text(appText("Причина (её увидят стороны)", "Сәбәбе (яҡтар күрәсәк)")) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                )
                err?.let { Text(it, color = CanonRed, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true; err = null
                scope.launch {
                    val r = when (kind) {
                        "cancel" -> ApiClient.adminParcelCancel(parcel.id, reason)
                        "release" -> ApiClient.adminParcelReleaseCourier(parcel.id, reason)
                        else -> ApiClient.adminParcelClose(parcel.id, closeStatus, reason)
                    }
                    r.onSuccess { onDone() }
                        .onFailure { err = (it as? com.yuldash.app.data.ApiException)?.message ?: errFallback }
                    busy = false
                }
            }) { Text(appText("Подтвердить", "Раҫлау"), color = CanonGreen2, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text(appText("Отмена", "Кире алыу"), color = CanonMuted) }
        },
    )
}

@Composable
private fun AdminCloseOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = if (selected) CanonMint else CanonBg,
        shape = CanonItemShape,
        border = BorderStroke(1.dp, if (selected) CanonGreen2 else CanonBorder),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (selected) Icons.Default.CheckCircle else Icons.Default.Place,
                contentDescription = null,
                tint = if (selected) CanonGreen2 else CanonMuted,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(label, color = CanonText, fontSize = 14.sp)
        }
    }
}

/** Выписка по деньгам доставки. Раньше здесь была одна строка «Собранный сбор» — и она врала:
 *  сбор «по пути» никому не выставляется, платить его некому, а цифра выглядела как выручка
 *  (аудит 2026-07-26). Теперь три разных числа и каждое означает ровно то, что написано. */
@Composable
private fun ParcelStatementCard(s: ParcelStatementDto) {
    Surface(color = CanonMint, shape = CanonCardShape, border = BorderStroke(1.dp, CanonGreen2)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = CanonSurface, shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Default.Payments, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(12.dp).size(26.dp))
                }
                Spacer(Modifier.width(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(appText("Курьеры оплатили", "Курьерҙар түләне"), color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(kopToRub(s.collectedFeeKop), color = CanonText, fontWeight = FontWeight.Black, fontSize = 26.sp)
                    Text(appText("Доставлено посылок: ${s.deliveredCount}", "Тапшырылған бандеролдәр: ${s.deliveredCount}"), color = CanonMuted, fontSize = 13.sp)
                }
            }
            if (s.owedCommissionKop > 0 || s.unbilledFeeKop > 0) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(CanonHairlineGreen))
                if (s.owedCommissionKop > 0) {
                    StatementRow(
                        label = appText("Ждём от курьеров", "Курьерҙарҙан көтәбеҙ"),
                        value = kopToRub(s.owedCommissionKop),
                        hint = appText("начислено, ещё не оплачено", "иҫәпләнгән, әле түләнмәгән"),
                        accent = CanonWarn,
                    )
                }
                if (s.unbilledFeeKop > 0) {
                    StatementRow(
                        label = appText("Сбор «по пути»", "«Юл ыңғайы» йыйымы"),
                        value = kopToRub(s.unbilledFeeKop),
                        hint = appText("выставить некому — это не выручка", "талап итер кеше юҡ — был килем түгел"),
                        accent = CanonMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatementRow(label: String, value: String, hint: String, accent: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(hint, color = CanonMuted, fontSize = 11.sp, lineHeight = 15.sp)
        }
        Spacer(Modifier.width(12.dp))
        Text(value, color = accent, fontWeight = FontWeight.Black, fontSize = 17.sp)
    }
}

@Composable
private fun AdminParcelCard(p: ParcelDto, onAction: (Pair<ParcelDto, String>) -> Unit) {
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
                // Сумма сделки — то, что отправитель платит курьеру. Наш сбор здесь не показываем:
                // это разные деньги, и раньше их путали (аудит 2026-07-26).
                if (p.priceKop > 0) Text(kopToRub(p.priceKop), color = CanonGreen2, fontWeight = FontWeight.Black, fontSize = 15.sp)
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
            // Рычаги — только пока доставка живая: закрытую трогать нечего.
            val finished = p.status == "delivered" || p.status == "canceled" ||
                p.status == "cancelled" || p.status == "returned"
            if (!finished) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (p.courier != null) {
                        AppButton(
                            text = appText("Снять курьера", "Курьерҙы алыу"),
                            onClick = { onAction(p to "release") },
                            style = AppButtonStyle.Secondary,
                            fillWidth = false,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    AppButton(
                        text = appText("Отменить", "Кире алыу"),
                        onClick = { onAction(p to "cancel") },
                        style = AppButtonStyle.Danger,
                        fillWidth = false,
                        modifier = Modifier.weight(1f),
                    )
                }
                TextButton(onClick = { onAction(p to "close") }, modifier = Modifier.fillMaxWidth()) {
                    Text(appText("Закрыть вручную", "Ҡулдан ябыу"), color = CanonMuted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
