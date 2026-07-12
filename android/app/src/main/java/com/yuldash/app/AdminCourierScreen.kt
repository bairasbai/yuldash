package com.yuldash.app

// ============================ Админ: заявки курьеров (C1) ============================
// По паттерну AdminTaxiScreen: умная обёртка держит стейт+сеть, LazyColumn рисует все состояния.
// Заявки курьеров: имя, телефон, транспорт, «кто пригласил», селфи с документом (Coil+Bearer),
// Одобрить / Отклонить (с причиной). Pending — сверху. Бэкенд: GET /admin/courier-applications,
// POST /admin/courier-applications/{id}/approve, POST .../{id}/reject {reason}.

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.CourierApplicationDto
import kotlinx.coroutines.launch

@Composable
internal fun AdminCourierScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val token = remember { ApiClient.currentToken() ?: "" }

    var filter by remember { mutableStateOf("pending") }   // клиентский фильтр
    var apps by remember { mutableStateOf<List<CourierApplicationDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    var rejectingId by remember { mutableStateOf<Int?>(null) }
    var rejectReason by remember { mutableStateOf("") }

    val approvedMsg = appText("Курьер одобрен", "Курьер раҫланды")
    val rejectedMsg = appText("Заявка отклонена", "Заявка кире ҡағылды")
    val actionErrMsg = appText("Не получилось. Проверь сеть и повтори.", "Булманы. Селтәрҙе тикшереп ҡабатла.")
    val loadErr = appText("Не удалось загрузить. Проверь интернет.", "Йөкләп булманы. Интернетты тикшер.")

    fun reload() {
        loading = true; error = null
        scope.launch {
            ApiClient.adminListCourierApps().onSuccess { apps = it }.onFailure { error = loadErr }
            loading = false
        }
    }
    LaunchedEffect(Unit) { reload() }

    // pending — сверху, затем по дате (свежие выше); клиентский фильтр по статусу.
    val visible = apps
        .filter { filter == "all" || it.status == filter }
        .sortedWith(
            compareByDescending<CourierApplicationDto> { it.status == "pending" }
                .thenByDescending { it.createdAt },
        )

    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Курьеры", "Курьерҙар"), onBack) }) { padding ->
        LazyColumn(
            Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            item {
                Text(
                    appText(
                        "Заявки «Стать курьером»: сверь селфи с документом, транспорт и кто пригласил, потом одобри или отклони с причиной.",
                        "«Курьер булыу» заявкалары: документ менән селфины, транспортты һәм кем саҡырғанын тикшер, аҙаҡ раҫла йәки сәбәп менән кире ҡаҡ.",
                    ),
                    color = CanonMuted, fontSize = 14.sp, lineHeight = 19.sp,
                )
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CourierFilterChip(appText("На проверке", "Тикшереүҙә"), filter == "pending") { filter = "pending" }
                    CourierFilterChip(appText("Одобрены", "Раҫланған"), filter == "approved") { filter = "approved" }
                    CourierFilterChip(appText("Отклонены", "Кире ҡағылған"), filter == "rejected") { filter = "rejected" }
                    CourierFilterChip(appText("Все", "Барыһы"), filter == "all") { filter = "all" }
                }
            }
            when {
                loading && apps.isEmpty() -> {
                    item { SkeletonCard(lines = 4) }
                    item { SkeletonCard(lines = 4) }
                }
                error != null && apps.isEmpty() -> item { ListedError(error ?: "") { reload() } }
                visible.isEmpty() -> item {
                    ListedEmpty(
                        appText("Заявок нет", "Заявка юҡ"),
                        appText("Здесь появятся соседи, которые хотят возить посылки.", "Бында бандероль илтергә теләгән күршеләр күренер."),
                    )
                }
                else -> items(visible.size, key = { "capp-" + visible[it].id }) { i ->
                    val a = visible[i]
                    val noName = appText("Без имени", "Исемһеҙ")
                    Box(Modifier.appearIn(i.coerceAtMost(6))) {
                        Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(a.name.ifBlank { noName }, color = CanonText, fontWeight = FontWeight.Black, fontSize = 17.sp, modifier = Modifier.weight(1f))
                                    CourierStatusBadge(a.status)
                                }
                                if (a.phone.isNotBlank()) Text(a.phone, color = CanonMuted, fontSize = 13.sp)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        if (a.transport == "cargo") Icons.Default.LocalShipping else Icons.Default.TwoWheeler,
                                        contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(appText("Транспорт: ", "Транспорт: ") + courierTransportLabel(a.transport), color = CanonText, fontSize = 14.sp)
                                }
                                a.invitedBy?.takeIf { it.isNotBlank() }?.let {
                                    Text(appText("Пригласил: ", "Саҡырҙы: ") + it, color = CanonGreen2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                }
                                if (a.selfieUrl.isNotBlank()) {
                                    Text(appText("Селфи с документом (сверь лицо)", "Документ менән селфи (йөҙҙө сағыштыр)"), color = CanonMuted, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    CourierDocImage(a.selfieUrl, token)
                                }
                                if (a.status == "rejected" && a.rejectReason.isNotBlank()) {
                                    Text(appText("Причина: ", "Сәбәбе: ") + a.rejectReason, color = CanonRed, fontSize = 13.sp, lineHeight = 18.sp)
                                }
                                if (a.status == "pending") {
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Button(
                                            onClick = {
                                                val id = a.id
                                                scope.launch {
                                                    ApiClient.adminApproveCourier(id)
                                                        .onSuccess { Toast.makeText(ctx, approvedMsg, Toast.LENGTH_SHORT).show(); reload() }
                                                        .onFailure { Toast.makeText(ctx, actionErrMsg, Toast.LENGTH_SHORT).show() }
                                                }
                                            },
                                            modifier = Modifier.weight(1f).height(48.dp),
                                            shape = RoundedCornerShape(14.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2),
                                        ) { Text(appText("Одобрить", "Раҫлау"), fontWeight = FontWeight.Bold) }
                                        OutlinedButton(
                                            onClick = {
                                                if (rejectingId == a.id) { rejectingId = null } else { rejectingId = a.id; rejectReason = "" }
                                            },
                                            modifier = Modifier.weight(1f).height(48.dp),
                                            shape = RoundedCornerShape(14.dp),
                                        ) { Text(appText("Отклонить", "Кире ҡағыу"), color = CanonRed, fontWeight = FontWeight.Bold) }
                                    }
                                    AnimatedVisibility(visible = rejectingId == a.id) {
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedTextField(
                                                value = rejectReason,
                                                onValueChange = { rejectReason = it.take(300) },
                                                label = { Text(appText("Почему отклоняешь (увидит курьер)", "Ниңә кире ҡағаһың (курьер күрер)")) },
                                                modifier = Modifier.fillMaxWidth(),
                                                shape = RoundedCornerShape(14.dp),
                                            )
                                            Button(
                                                onClick = {
                                                    val id = a.id
                                                    val reason = rejectReason.trim()
                                                    scope.launch {
                                                        ApiClient.adminRejectCourier(id, reason)
                                                            .onSuccess { Toast.makeText(ctx, rejectedMsg, Toast.LENGTH_SHORT).show(); rejectingId = null; reload() }
                                                            .onFailure { Toast.makeText(ctx, actionErrMsg, Toast.LENGTH_SHORT).show() }
                                                    }
                                                },
                                                enabled = rejectReason.isNotBlank(),
                                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                                shape = RoundedCornerShape(14.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = CanonRed),
                                            ) { Text(appText("Отклонить с причиной", "Сәбәп менән кире ҡағыу"), fontWeight = FontWeight.Bold) }
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
}

/** Чип фильтра статуса заявок курьеров (тач-цель ≥48dp). */
@Composable
private fun CourierFilterChip(label: String, active: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = if (active) CanonMint else CanonSurface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, if (active) CanonGreen2 else CanonBorder),
        modifier = Modifier.height(48.dp),
    ) {
        Box(Modifier.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            Text(label, color = if (active) CanonGreen2 else CanonMuted, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}

/** Бейдж статуса заявки курьера. */
@Composable
private fun CourierStatusBadge(status: String) {
    val (label, fg, bg) = when (status) {
        "approved" -> Triple(appText("Одобрен", "Раҫланған"), CanonGreen2, CanonMint)
        "rejected" -> Triple(appText("Отклонён", "Кире ҡағылған"), CanonRed, CanonDangerBg)
        else -> Triple(appText("На проверке", "Тикшереүҙә"), CanonWarn, CanonWarnBg)
    }
    Surface(color = bg, shape = RoundedCornerShape(10.dp)) {
        Text(label, color = fg, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

/** Фото документа/селфи с Bearer-токеном (приватный медиа-эндпоинт). */
@Composable
private fun CourierDocImage(url: String, token: String) {
    val ctx = LocalContext.current
    coil.compose.AsyncImage(
        model = coil.request.ImageRequest.Builder(ctx).data(url).addHeader("Authorization", "Bearer $token").crossfade(true).build(),
        contentDescription = null,
        modifier = Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(12.dp)),
        contentScale = ContentScale.Crop,
    )
}
