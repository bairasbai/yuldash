package com.yuldash.app

// ═══════════════════ «Мои отклики» (водитель): торг о цене ═══════════════════
// Водитель откликался на заявку и дальше не видел ничего: пассажир мог ответить встречной ценой,
// а узнать об этом было можно только из пуша — пропустил уведомление, потерял поездку.
// Здесь он видит все свои отклики, чью сейчас очередь ходить, как шёл торг, и может принять
// встречную цену или предложить свою.

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import android.widget.Toast
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.ResponseDto
import kotlinx.coroutines.launch

@Composable
internal fun DriverResponsesScreen(onBack: () -> Unit, onOpenTrip: (Int) -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var rows by remember { mutableStateOf<List<ResponseDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }
    var counterFor by remember { mutableStateOf<ResponseDto?>(null) }
    val failMsg = appText("Не получилось. Проверь сеть и повтори.", "Булманы. Селтәрҙе тикшереп ҡабатла.")

    LaunchedEffect(tick) {
        loading = true; error = false
        ApiClient.getMyResponses().onSuccess { rows = it }.onFailure { error = true }
        loading = false
    }

    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Мои отклики", "Минең яуаптарым"), onBack) }) { padding ->
        DriverResponsesContent(
            loading = loading,
            error = error,
            responses = rows,
            busy = busy,
            onRetry = { tick++ },
            onAccept = { r ->
                if (busy) return@DriverResponsesContent
                busy = true; val id = r.id
                scope.launch {
                    ApiClient.acceptResponse(id)
                        .onSuccess { bid -> busy = false; tick++; onOpenTrip(bid) }
                        .onFailure { Toast.makeText(ctx, (it as? com.yuldash.app.data.ApiException)?.message ?: failMsg, Toast.LENGTH_LONG).show(); busy = false }
                }
            },
            onCounter = { r -> counterFor = r },
            onDecline = { r ->
                if (busy) return@DriverResponsesContent
                busy = true; val id = r.id
                scope.launch {
                    ApiClient.declineResponse(id)
                        .onFailure { Toast.makeText(ctx, (it as? com.yuldash.app.data.ApiException)?.message ?: failMsg, Toast.LENGTH_SHORT).show() }
                    busy = false; tick++
                }
            },
            modifier = Modifier.padding(padding),
        )
    }

    counterFor?.let { target ->
        CounterPriceDialog(
            current = target.onTable,
            roundsLeft = (BARGAIN_MAX_TOTAL_UI - target.bargainRounds).coerceAtLeast(1),
            busy = busy,
            onDismiss = { counterFor = null },
            onSend = { price ->
                busy = true; val id = target.id
                scope.launch {
                    ApiClient.counterOffer(id, price)
                        .onFailure { Toast.makeText(ctx, (it as? com.yuldash.app.data.ApiException)?.message ?: failMsg, Toast.LENGTH_LONG).show() }
                    busy = false; counterFor = null; tick++
                }
            },
        )
    }
}

/** Чистый рендер: все состояния (загрузка / ошибка+повтор / пусто / список) — данные параметрами. */
@Composable
internal fun DriverResponsesContent(
    loading: Boolean,
    error: Boolean,
    responses: List<ResponseDto>,
    busy: Boolean,
    onRetry: () -> Unit,
    onAccept: (ResponseDto) -> Unit,
    onCounter: (ResponseDto) -> Unit = {},
    onDecline: (ResponseDto) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
    ) {
        item {
            Text(
                appText(
                    "Здесь видно, где пассажир ответил на твою цену. Торгуемся по очереди — можно принять или предложить своё.",
                    "Бында пассажир хаҡыңа яуап биргән урындар күренә. Сиратлап һатыулашабыҙ — ҡабул итергә йәки үҙеңдекен тәҡдим итергә була.",
                ),
                color = CanonMuted, fontSize = 14.sp, lineHeight = 19.sp,
            )
        }
        if (loading) {
            item { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { repeat(3) { SkeletonCard(lines = 3) } } }
        } else if (error) {
            item { ListedError(appText("Не удалось загрузить отклики. Проверь сеть.", "Яуаптарҙы йөкләп булманы. Сетте тикшер."), onRetry = onRetry) }
        } else if (responses.isEmpty()) {
            item {
                ListedEmpty(
                    appText("Ты пока никому не откликнулся", "Һин әле бер кемгә лә яуап бирмәнең"),
                    appText("Открой «Заявки пассажиров» и предложи свою цену.", "«Пассажир заявкалары»н асып, үҙ хаҡыңды тәҡдим ит."),
                )
            }
        } else {
            items(responses, key = { it.id }) { r -> DriverResponseCard(r, busy, onAccept, onCounter, onDecline) }
        }
    }
}

@Composable
private fun DriverResponseCard(
    r: ResponseDto,
    busy: Boolean,
    onAccept: (ResponseDto) -> Unit,
    onCounter: (ResponseDto) -> Unit,
    onDecline: (ResponseDto) -> Unit,
) {
    Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        if (r.haggled) appText("Идёт торг", "Һатыулашыу бара") else appText("Твой отклик", "Һинең яуабың"),
                        color = CanonText, fontWeight = FontWeight.Black, fontSize = 15.sp,
                    )
                    if (r.haggled && r.price > 0) {
                        Text(
                            appText("Ты предлагал ${r.price} ₽", "Һин ${r.price} һ тәҡдим иткәйнең"),
                            color = CanonMuted, fontSize = 12.sp,
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                if (r.onTable > 0) Text("${r.onTable} ₽", color = CanonGreen2, fontWeight = FontWeight.Black, fontSize = 18.sp)
            }
            if (r.comment.isNotBlank()) Text(r.comment, color = CanonMuted, fontSize = 14.sp)
            BargainSummary(r)
            if (r.canAccept) {
                AppButton(
                    text = appText("Согласиться на ${r.onTable} ₽", "${r.onTable} һ менән килешеү"),
                    onClick = { onAccept(r) },
                    style = AppButtonStyle.Accent,
                    enabled = !busy,
                )
            }
            if (r.canCounter) {
                TextButton(onClick = { onCounter(r) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(appText("Предложить свою цену", "Үҙ хаҡыңды тәҡдим итеү"), color = CanonGreen2, fontWeight = FontWeight.Bold)
                }
            }
            if (r.status == "offered" && (r.canAccept || r.canCounter)) {
                TextButton(onClick = { onDecline(r) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(appText("Не договорились", "Килешмәнек"), color = CanonMuted, fontSize = 13.sp)
                }
            }
        }
    }
}
