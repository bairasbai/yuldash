package com.yuldash.app

// ═══════════════════ M1: Админ — модерация бизнесов-партнёров (купоны) ═══════════════════
// По паттерну AdminWaitlistScreen: умная обёртка держит стейт+сеть, LazyColumn рисует все состояния.
// Заявки со статусом pending — сверху. Карточка бизнеса + «Одобрить» / «Отклонить с причиной».

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuldash.app.data.AdminPartnerDto
import com.yuldash.app.data.ApiClient
import kotlinx.coroutines.launch

@Composable
internal fun AdminPartnersScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var list by remember { mutableStateOf<List<AdminPartnerDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var busyId by remember { mutableStateOf(0) }
    var rejectTarget by remember { mutableStateOf<AdminPartnerDto?>(null) }

    val loadErr = appText("Не удалось загрузить. Проверь интернет.", "Йөкләп булманы. Интернетты тикшер.")
    val actionErr = appText("Не получилось. Проверь сеть и повтори.", "Булманы. Селтәрҙе тикшереп ҡабатла.")
    val approvedMsg = appText("Бизнес одобрен", "Бизнес раҫланды")
    val rejectedMsg = appText("Бизнес отклонён", "Бизнес кире ҡағылды")

    fun reload() {
        loading = true; error = null
        scope.launch {
            ApiClient.getAdminPartners()
                .onSuccess { fresh ->
                    // pending — сверху, затем по дате (свежие выше)
                    list = fresh.sortedWith(compareByDescending<AdminPartnerDto> { it.status == "pending" }.thenByDescending { it.createdAt })
                }
                .onFailure { error = (it as? com.yuldash.app.data.ApiException)?.message ?: loadErr }
            loading = false
        }
    }
    LaunchedEffect(Unit) { reload() }

    val pendingCount = list.count { it.status == "pending" }

    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Бизнесы-партнёры", "Партнёр-бизнестар"), onBack) }) { padding ->
        LazyColumn(
            Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            item {
                Text(
                    appText(
                        "Проверь заведения, которые хотят размещать купоны. Одобри — бизнес сможет публиковать скидки.",
                        "Купон ҡуйырға теләгән урындарҙы тикшер. Раҫла — бизнес ташлама баҫтыра алыр.",
                    ),
                    color = CanonMuted, fontSize = 14.sp, lineHeight = 19.sp,
                )
            }
            if (pendingCount > 0) {
                item {
                    Surface(color = CanonWarnBg, shape = RoundedCornerShape(12.dp)) {
                        Text(
                            appText("Ждут проверки: $pendingCount", "Тикшереүҙе көтә: $pendingCount"),
                            color = CanonWarn, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }
            when {
                loading && list.isEmpty() -> {
                    item { SkeletonCard(lines = 3) }
                    item { SkeletonCard(lines = 3) }
                }
                error != null && list.isEmpty() -> item { ListedError(error ?: "") { reload() } }
                list.isEmpty() -> item {
                    ListedEmpty(
                        appText("Пока нет заявок", "Әлегә заявкалар юҡ"),
                        appText("Здесь появятся заведения, которые хотят стать партнёрами.", "Бында партнёр булырға теләгән урындар күренер."),
                    )
                }
                else -> items(list.size, key = { "ap-" + list[it].id }) { i ->
                    Box(Modifier.appearIn(i.coerceAtMost(6))) {
                        AdminPartnerCard(
                            p = list[i],
                            busy = busyId == list[i].id,
                            onApprove = {
                                if (busyId != 0) return@AdminPartnerCard
                                busyId = list[i].id
                                scope.launch {
                                    ApiClient.approvePartner(list[i].id)
                                        .onSuccess { Toast.makeText(ctx, approvedMsg, Toast.LENGTH_SHORT).show(); reload() }
                                        .onFailure { Toast.makeText(ctx, (it as? com.yuldash.app.data.ApiException)?.message ?: actionErr, Toast.LENGTH_SHORT).show() }
                                    busyId = 0
                                }
                            },
                            onReject = { rejectTarget = list[i] },
                        )
                    }
                }
            }
        }
    }

    // Диалог отклонения с причиной
    rejectTarget?.let { target ->
        var reason by remember(target.id) { mutableStateOf("") }
        val defaultReason = appText("Не прошло модерацию", "Модерацияны үтмәне")
        AlertDialog(
            onDismissRequest = { rejectTarget = null },
            containerColor = CanonSurface,
            title = { Text(appText("Отклонить бизнес", "Бизнесты кире ҡағыу"), color = CanonText, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(appText("Напиши причину — заведение увидит её и сможет исправить.", "Сәбәпте яҙ — урын уны күрер һәм төҙәтә алыр."), color = CanonMuted, fontSize = 13.sp, lineHeight = 18.sp)
                    OutlinedTextField(
                        value = reason, onValueChange = { reason = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(appText("Причина отказа", "Кире ҡағыу сәбәбе")) },
                        shape = RoundedCornerShape(14.dp),
                        minLines = 2,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val r = reason.trim().ifBlank { defaultReason }
                        busyId = target.id
                        scope.launch {
                            ApiClient.rejectPartner(target.id, r)
                                .onSuccess { Toast.makeText(ctx, rejectedMsg, Toast.LENGTH_SHORT).show(); reload() }
                                .onFailure { Toast.makeText(ctx, (it as? com.yuldash.app.data.ApiException)?.message ?: actionErr, Toast.LENGTH_SHORT).show() }
                            busyId = 0
                        }
                        rejectTarget = null
                    },
                ) { Text(appText("Отклонить", "Кире ҡағыу"), color = CanonRed, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { rejectTarget = null }) { Text(appText("Отмена", "Баш тартыу"), color = CanonMuted) }
            },
        )
    }
}

@Composable
private fun AdminPartnerCard(p: AdminPartnerDto, busy: Boolean, onApprove: () -> Unit, onReject: () -> Unit) {
    Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = CanonMint, shape = RoundedCornerShape(14.dp)) {
                    Icon(couponCategoryIcon(p.category), contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(10.dp).size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(p.name, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(couponCategoryLabel(p.category) + (if (p.city.isNotBlank()) "  ·  ${p.city}" else ""), color = CanonMuted, fontSize = 13.sp)
                }
                AdminPartnerStatusChip(p.status)
            }
            if (p.address.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Place, contentDescription = null, tint = CanonMuted, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(p.address, color = CanonText, fontSize = 13.sp)
                }
            }
            if (p.phone.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Phone, contentDescription = null, tint = CanonMuted, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(p.phone, color = CanonText, fontSize = 13.sp)
                }
            }
            if (p.description.isNotBlank()) {
                Text(p.description, color = CanonMuted, fontSize = 13.sp, lineHeight = 18.sp)
            }
            if (p.status == "rejected" && p.rejectReason.isNotBlank()) {
                Surface(color = CanonDangerBg, shape = RoundedCornerShape(12.dp)) {
                    Text(appText("Отклонён: ", "Кире ҡағылды: ") + p.rejectReason, color = CanonRed, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
            // Действия — только для тех, кого ещё можно модерировать
            if (p.status == "pending" || p.status == "rejected") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (p.status == "pending") {
                        Button(
                            onClick = onApprove, enabled = !busy,
                            modifier = Modifier.weight(1f).height(46.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2),
                        ) { Text(appText("Одобрить", "Раҫлау"), fontWeight = FontWeight.Bold, fontSize = 14.sp) }
                    }
                    OutlinedButton(
                        onClick = onReject, enabled = !busy,
                        modifier = Modifier.weight(1f).height(46.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) { Text(appText("Отклонить", "Кире ҡағыу"), color = CanonRed, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
                }
            }
        }
    }
}

@Composable
private fun AdminPartnerStatusChip(status: String) {
    val (bg, fg, ru, ba) = when (status) {
        "active" -> PStatus(CanonMint, CanonGreen2, "Активен", "Актив")
        "rejected" -> PStatus(CanonDangerBg, CanonRed, "Отклонён", "Кире ҡағылған")
        "paused" -> PStatus(CanonWarnBg, CanonWarn, "Пауза", "Пауза")
        "archived" -> PStatus(CanonWarnBg, CanonMuted, "Архив", "Архив")
        else -> PStatus(CanonWarnBg, CanonWarn, "На проверке", "Тикшереүҙә")
    }
    Surface(color = bg, shape = RoundedCornerShape(10.dp)) {
        Text(appText(ru, ba), color = fg, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
    }
}

private data class PStatus(val bg: androidx.compose.ui.graphics.Color, val fg: androidx.compose.ui.graphics.Color, val ru: String, val ba: String)
