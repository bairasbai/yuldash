package com.yuldash.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Verified
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.ApiException
import com.yuldash.app.data.TripReceiptDto

/*
 * ════════════════════════════════════════════════════════════════════════════
 *  Квитанция завершённой поездки — GET /trips/{booking_id}/receipt
 * ════════════════════════════════════════════════════════════════════════════
 *  Тёплая карточка «как договорились»: маршрут, дата, места, сумма, способ оплаты,
 *  водитель (+ «Проверен»). Не платёж — запись о поездке. Можно поделиться текстом.
 *  Состояния: загрузка / ошибка+повтор / 409 «ещё не завершена» → спокойный текст.
 */

@Composable
internal fun TripReceiptScreen(bookingId: Int, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var receipt by remember(bookingId) { mutableStateOf<TripReceiptDto?>(null) }
    var loading by remember(bookingId) { mutableStateOf(true) }
    var errorStatus by remember(bookingId) { mutableStateOf<Int?>(null) }   // null = нет ошибки; 409 = не завершена
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(bookingId, reload) {
        if (bookingId <= 0) { loading = false; errorStatus = -1; return@LaunchedEffect }
        loading = true; errorStatus = null
        ApiClient.getTripReceipt(bookingId)
            .onSuccess { receipt = it; errorStatus = null }
            .onFailure { errorStatus = (it as? ApiException)?.status ?: -1 }
        loading = false
    }

    Scaffold(
        containerColor = CanonBg,
        topBar = { ScreenTopBar(appText("Квитанция", "Квитанция"), onBack) },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when {
                loading -> ReceiptSkeleton()
                errorStatus == 409 -> ReceiptPendingCard()
                receipt == null -> AppErrorState(onRetry = { reload++ })
                else -> ReceiptCard(receipt!!)
            }
        }
    }
}

// ─────────────────── Карточка квитанции ───────────────────

@Composable
private fun ReceiptCard(r: TripReceiptDto) {
    val ctx = LocalContext.current
    val payLabel = payMethodLabel(r.payMethod)
    val shareChooser = appText("Поделиться квитанцией", "Квитанция менән бүлешеү")
    // Строки для шеринга считаем ЗАРАНЕЕ (appText — @Composable, внутри buildString его звать нельзя).
    val shTitle = appText("Юлдаш · Квитанция поездки", "Юлдаш · Сәфәр квитанцияһы")
    val shAmount = appText("Сумма", "Сумма")
    val shDriver = appText("Водитель", "Йөрөтөүсе")
    val shareText = buildString {
        appendLine(shTitle)
        appendLine("${r.fromCity} → ${r.toCity}")
        appendLine(formatDepart(r.departAt))
        appendLine("$shAmount: ${r.amount} ₽ · $payLabel")
        if (r.driverName.isNotBlank()) appendLine("$shDriver: ${r.driverName}")
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // Шапка «Поездка завершена» — фиксированный ink-зелёный (белый текст читаем в обеих темах).
        Surface(shape = CanonCardShape, color = Color.Transparent) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(CanonGreenInk, CanonGreenInkDark)), CanonCardShape)
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AnimatedVisibility(visible = true, enter = scaleIn(tween(300)) + fadeIn()) {
                    Box(Modifier.size(56.dp).background(Color.White.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color.White, modifier = Modifier.size(34.dp))
                    }
                }
                Text(appText("Поездка завершена", "Сәфәр тамамланды"), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black)
                Text("${r.fromCity} → ${r.toCity}", color = Color.White.copy(alpha = 0.92f), fontSize = 15.sp, textAlign = TextAlign.Center)
            }
        }

        // Детали
        AppCard {
            Column(Modifier.padding(4.dp)) {
                ReceiptRow(Icons.Default.Place, appText("Маршрут", "Маршрут"), "${r.fromCity} → ${r.toCity}")
                ReceiptDivider()
                ReceiptRow(Icons.Default.Schedule, appText("Дата и время", "Көн һәм ваҡыт"), formatDepart(r.departAt))
                ReceiptDivider()
                ReceiptRow(
                    Icons.Default.Person,
                    appText("Мест", "Урын"),
                    r.seats.toString(),
                )
                if (r.driverName.isNotBlank()) {
                    ReceiptDivider()
                    ReceiptDriverRow(r.driverName, r.driverVerified)
                }
            }
        }

        // Сумма + способ оплаты (акцент)
        AppCard {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(appText("Сумма поездки", "Сәфәр суммаһы"), color = CanonMuted, fontSize = 13.sp)
                        Text("${r.amount} ₽", color = CanonText, fontSize = 34.sp, fontWeight = FontWeight.Black)
                    }
                    if (r.paid) {
                        Surface(shape = RoundedCornerShape(999.dp), color = CanonMint) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(5.dp))
                                Text(appText("Оплачено", "Түләнгән"), color = CanonGreen2, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Payments, contentDescription = null, tint = CanonMuted, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(payLabel, color = CanonMutedStrong, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
                Text(
                    appText("Это запись о поездке, как вы договорились. Оплата — напрямую между вами.",
                        "Был — килешкәнсә сәфәр яҙмаһы. Түләү — туранан-тура араларҙа."),
                    color = CanonMuted, fontSize = 12.sp, lineHeight = 16.sp,
                )
            }
        }

        // Тёплое спасибо + шеринг
        Text(
            appText("Спасибо, что едешь с Юлдашем 💚", "Юлдаш менән йөрөгәнең өсөн рәхмәт 💚"),
            color = CanonMuted, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
        AppButton(
            text = appText("Поделиться", "Бүлешеү"),
            onClick = { shareRide(ctx, shareText, shareChooser) },
            style = AppButtonStyle.Secondary,
            icon = Icons.Default.IosShare,
        )
    }
}

@Composable
internal fun ReceiptRow(icon: ImageVector, label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(color = CanonMint, shape = CircleShape) {
            Icon(icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(9.dp).size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(label, color = CanonMuted, fontSize = 14.sp)
        Spacer(Modifier.weight(1f))
        Text(value, color = CanonText, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
    }
}

@Composable
internal fun ReceiptDriverRow(name: String, verified: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(color = CanonMint, shape = CircleShape) {
            Icon(Icons.Default.Person, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(9.dp).size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(appText("Водитель", "Йөрөтөүсе"), color = CanonMuted, fontSize = 14.sp)
        Spacer(Modifier.weight(1f))
        if (verified) {
            Icon(Icons.Default.Verified, contentDescription = appText("Проверен", "Тикшерелгән"), tint = CanonGreen2, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(5.dp))
        }
        Text(name, color = CanonText, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
    }
}

@Composable
internal fun ReceiptDivider() {
    Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(1.dp).background(CanonHairlineGreen))
}

// ─────────────────── Состояния ───────────────────

@Composable
private fun ReceiptSkeleton() {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SkeletonBox(widthFraction = 1f, height = 120.dp, shape = CanonCardShape)
        SkeletonCard(lines = 4)
        SkeletonCard(lines = 2)
    }
}

/** 409: поездка ещё не завершена — спокойный, тёплый текст без тревоги. */
@Composable
private fun ReceiptPendingCard() {
    AppCard {
        Column(
            Modifier.padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(color = CanonMint, shape = CircleShape) {
                Icon(Icons.Default.Schedule, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(16.dp).size(30.dp))
            }
            Text(appText("Квитанция ещё не готова", "Квитанция әҙер түгел"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 18.sp, textAlign = TextAlign.Center)
            Text(
                appText("Она появится после завершения поездки. Хорошей дороги!", "Ул сәфәр тамамланғас барлыҡҡа килер. Юлың уң булһын!"),
                color = CanonMuted, fontSize = 14.sp, lineHeight = 19.sp, textAlign = TextAlign.Center,
            )
        }
    }
}

