package com.yuldash.app

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.RequestFeedDto
import kotlinx.coroutines.launch

internal enum class RequestsFeedFilter { All, AlongTheWay, Today }

/** Owner/session-scoped state; opening a sheet never sends an offer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RequestsFeedScreen(onBack: () -> Unit) {
    val session by ApiClient.sessionChanges.collectAsState()
    val ownerRole by ApiClient.roleChanges.collectAsState()
    var mounted by remember(session, ownerRole) { mutableStateOf(true) }
    DisposableEffect(session, ownerRole) { onDispose { mounted = false } }
    fun current() = mounted && ApiClient.isCurrentSession(session) && ApiClient.cachedRole() == ownerRole
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var feed by remember(session, ownerRole) { mutableStateOf<List<RequestFeedDto>>(emptyList()) }
    var partial by remember(session, ownerRole) { mutableStateOf(false) }
    var loading by remember(session, ownerRole) { mutableStateOf(true) }
    var error by remember(session, ownerRole) { mutableStateOf(false) }
    var target by remember(session, ownerRole) { mutableStateOf<RequestFeedDto?>(null) }
    var price by remember(session, ownerRole) { mutableStateOf("") }
    var departureNote by remember(session, ownerRole) { mutableStateOf("") }
    var comment by remember(session, ownerRole) { mutableStateOf("") }
    var selectedFilter by remember(session, ownerRole) { mutableStateOf(RequestsFeedFilter.All) }
    var bindToRide by remember(session, ownerRole, target?.id) { mutableStateOf<Boolean?>(null) }
    var responding by remember(session, ownerRole) { mutableStateOf(false) }
    var withdrawTarget by remember(session, ownerRole) { mutableStateOf<RequestFeedDto?>(null) }
    var withdrawing by remember(session, ownerRole) { mutableStateOf(false) }
    var tick by remember(session, ownerRole) { mutableIntStateOf(0) }
    var hasOwnRides by remember(session, ownerRole) { mutableStateOf<Boolean?>(null) }
    val sent = appText("Отклик отправлен", "Яуап ебәрелде")
    val fail = appText("Не удалось отправить отклик. Проверь сеть и повтори.", "Яуап ебәреп булманы. Сетте тикшереп ҡабатла.")
    val withdrawn = appText("Отклик отозван", "Яуап кире алынды")
    val withdrawFail = appText("Не удалось отозвать. Проверь сеть и повтори.", "Кире алып булманы. Сетте тикшереп ҡабатла.")
    val withdrawTaken = appText("Пассажир уже принял отклик — отозвать нельзя.", "Пассажир яуапты ҡабул иткән — кире алып булмай.")
    LaunchedEffect(session, ownerRole, selectedFilter, tick) {
        if (!current()) return@LaunchedEffect
        loading = true; error = false
        val result = ApiClient.getRequestsFeed(onRoute = selectedFilter == RequestsFeedFilter.AlongTheWay)
        if (!current()) return@LaunchedEffect
        result.onSuccess { feed = it.items; partial = it.partial }.onFailure { error = true }
        if (selectedFilter == RequestsFeedFilter.AlongTheWay && result.getOrNull()?.isEmpty() == true) {
            val rides = ApiClient.getDriverRides().getOrNull()
            if (!current()) return@LaunchedEffect
            hasOwnRides = rides?.any { it.status == "active" }
        }
        loading = false
    }
    withdrawTarget?.let { request ->
        AlertDialog(
            onDismissRequest = { if (!withdrawing) withdrawTarget = null }, containerColor = CanonSurface,
            title = { Text(appText("Отозвать отклик?", "Яуапты кире аларғамы?"), style = CanonBodyStrong, color = CanonText) },
            // Последствие словами: пассажир больше не увидит отклик (как было до переноса экрана).
            text = { Text(appText("Пассажир больше не увидит твой отклик на «${request.from} → ${request.to}».",
                "Пассажир «${request.from} → ${request.to}» яуабыңды башҡа күрмәйәсәк."), style = CanonBody, color = CanonMuted) },
            confirmButton = { TextButton(enabled = !withdrawing, onClick = {
                val id = request.myResponseId ?: return@TextButton
                if (!current() || withdrawing) return@TextButton
                val capturedSession = session
                withdrawing = true
                scope.launch {
                    if (!current() || session != capturedSession) return@launch
                    val result = ApiClient.deleteResponse(id)
                    if (!current() || session != capturedSession) return@launch
                    withdrawing = false
                    result.onSuccess { withdrawTarget = null; Toast.makeText(ctx, withdrawn, Toast.LENGTH_SHORT).show(); tick++ }
                        .onFailure { e ->
                            // 409 — отклик уже приняли: наш текст точнее, диалог закрываем и обновляем ленту.
                            val taken = (e as? com.yuldash.app.data.ApiException)?.status == 409
                            Toast.makeText(ctx, if (taken) withdrawTaken else serverSaid(e, withdrawFail), Toast.LENGTH_LONG).show()
                            if (taken) { withdrawTarget = null; tick++ }
                        }
                }
            }) { Text(if (withdrawing) appText("Отзываем…", "Кире алабыҙ…") else appText("Отозвать", "Кире алыу"), color = CanonRed) } },
            dismissButton = { TextButton(enabled = !withdrawing, onClick = { withdrawTarget = null }) { Text(appText("Оставить", "Ҡалдырыу"), color = CanonMuted) } },
        )
    }
    target?.let { request ->
        AppModalBottomSheet(onDismissRequest = { if (!responding) target = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = CanonSurface) {
            RequestOfferSheet(request, price, departureNote, comment, responding,
                onPriceChange = { price = it.filter(Char::isDigit).take(6) },
                onDepartureNoteChange = { departureNote = it.take(120) }, onCommentChange = { comment = it.take(300) },
                bindToRide = bindToRide, onBindToRideChange = { if (!responding) bindToRide = it },
                onSubmit = {
                    if (!current() || responding || target?.id != request.id || !requestOfferReady(request, bindToRide)) return@RequestOfferSheet
                    val rideId = if (bindToRide == true) request.matchedRideId else null
                    val capturedSession = session
                    responding = true
                    scope.launch {
                        if (!current() || session != capturedSession || target?.id != request.id) return@launch
                        val details = listOf(departureNote.trim(), comment.trim()).filter(String::isNotBlank).joinToString(" · ")
                        val result = ApiClient.respondToRequest(request.id, price.toIntOrNull() ?: 0, details, rideId)
                        if (!current() || session != capturedSession || target?.id != request.id) return@launch
                        responding = false
                        result.onSuccess { target = null; price = ""; departureNote = ""; comment = ""; tick++; Toast.makeText(ctx, sent, Toast.LENGTH_SHORT).show() }
                            .onFailure { e ->
                                Toast.makeText(ctx, serverSaid(e, fail), Toast.LENGTH_LONG).show()
                                if ((e as? com.yuldash.app.data.ApiException)?.status == 409) { target = null; tick++ }
                            }
                    }
                })
        }
    }
    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Заявки пассажиров", "Пассажир заявкалары"), onBack) }) { padding ->
        RequestsFeedContent(loading, error, feed, onRetry = { tick++ },
            onRespond = { request -> if (current() && !responding) {
                target = request; price = request.maxPrice?.takeIf { it > 0 }?.toString().orEmpty(); departureNote = ""; comment = ""; bindToRide = null
            } }, onWithdraw = { if (current()) withdrawTarget = it }, selectedFilter = selectedFilter,
            onFilterChange = { if (!responding) { selectedFilter = it; target = null; hasOwnRides = null } },
            modifier = Modifier.padding(padding), hasOwnRides = hasOwnRides, partial = partial)
    }
}
