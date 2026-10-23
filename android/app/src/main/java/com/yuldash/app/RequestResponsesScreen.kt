package com.yuldash.app

import android.widget.Toast
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.yuldash.app.data.ApiClient
import kotlinx.coroutines.launch

// Экран откликов на заявку пассажира вынесен из RidesRequestsChatScreens.kt: обёртка выросла на
// защите границы входа и роли (поздний ответ чужому аккаунту не применяется), а общий файл расти
// не должен. Чистый рендер — по-прежнему [ResponsesContent] там же.

/** Отклики водителей на МОЮ заявку — пассажир выбирает → поездка+чат.
 *  Умная обёртка: держит стейт, грузит отклики, ходит в ApiClient.
 *  Рендер вынесен в чистый [ResponsesContent] → его покрывают Robolectric-тесты. */
@Composable
internal fun ResponsesScreen(requestId: Int, onBack: () -> Unit, onAccepted: (Int) -> Unit) {
    val session by ApiClient.sessionChanges.collectAsState()
    val ownerRole by ApiClient.roleChanges.collectAsState()
    var mounted by remember(requestId, session, ownerRole) { mutableStateOf(true) }
    DisposableEffect(requestId, session, ownerRole) { mounted = true; onDispose { mounted = false } }
    fun current() = mounted && ApiClient.isCurrentSession(session) && ApiClient.cachedRole() == ownerRole
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var resps by remember(requestId, session, ownerRole) { mutableStateOf<List<com.yuldash.app.data.ResponseDto>>(emptyList()) }
    var loading by remember(requestId, session, ownerRole) { mutableStateOf(true) }
    var error by remember(requestId, session, ownerRole) { mutableStateOf(false) }
    var accepting by remember(requestId, session, ownerRole) { mutableStateOf(false) }
    var reloadTick by remember(requestId, session, ownerRole) { mutableStateOf(0) }
    var counterFor by remember(requestId, session, ownerRole) { mutableStateOf<com.yuldash.app.data.ResponseDto?>(null) }
    val failMsg = appText("Не получилось принять", "Ҡабул итеп булманы")
    // Своя цена нужна только пустому экрану — но узнать её можно лишь из списка заявок.
    // Молчит сервер — кнопка «Поднять цену» просто не появится, врать цифрой нельзя.
    var myRequest by remember(requestId, session, ownerRole) { mutableStateOf<com.yuldash.app.data.RequestDto?>(null) }
    LaunchedEffect(requestId, session, ownerRole, loading, error, resps.isEmpty()) {
        // Только когда отклики ЗАГРУЗИЛИСЬ и их правда нет: цена нужна одной кнопке на
        // пустом экране, а тянуть весь список заявок при каждом открытии — лишняя работа.
        if (!loading && !error && resps.isEmpty() && myRequest == null) {
            ApiClient.getMyRequests().onSuccess { list -> if (current()) myRequest = list.firstOrNull { it.id == requestId } }
        }
    }
    val raiseOk = appText("Цену подняли — водители увидят новую",
                          "Хаҡ күтәрелде — йөрөтөүселәр яңыһын күрәсәк")
    val raiseFail = appText("Не получилось поднять цену. Проверь связь.",
                            "Хаҡты күтәреп булманы. Бәйләнеште тикшер.")
    // Сбой загрузки откликов больше не выглядит как «откликов нет» — ошибка + «Повторить».
    LaunchedEffect(requestId, reloadTick, session, ownerRole) { loading = true; error = false; val result = ApiClient.getRequestResponses(requestId); if (!current()) return@LaunchedEffect; result.onSuccess { resps = it }.onFailure { error = true }; loading = false }
    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Отклики на заявку", "Заявкаға яуаптар"), onBack) }) { padding ->
        ResponsesContent(
            loading = loading,
            error = error,
            responses = resps,
            accepting = accepting,
            onRetry = { reloadTick++ },
            maxPrice = myRequest?.maxPrice ?: 0,
            // Поднять цену прямо отсюда: заявка редактируется той же ручкой, что и на экране
            // правки, — новая цифра сразу уходит в ленту водителей.
            onRaisePrice = if (myRequest == null) null else { newPrice ->
                scope.launch {
                    if (!current()) return@launch
                    ApiClient.editRequest(requestId, maxPrice = newPrice)
                        .onSuccess {
                            if (!current()) return@onSuccess
                            myRequest = myRequest?.copy(maxPrice = newPrice)
                            Toast.makeText(ctx, raiseOk, Toast.LENGTH_SHORT).show()
                            reloadTick++
                        }
                        .onFailure {
                            if (!current()) return@onFailure
                            Toast.makeText(ctx, serverSaid(it, raiseFail), Toast.LENGTH_LONG).show()
                        }
                }
            },
            // Готовые поездки по этому маршруту живут на карточке заявки (подбор уже там):
            // возвращаем человека туда, а не плодим второй такой же список.
            onFindRides = onBack,
            onAccept = { r ->
                if (accepting || !current()) return@ResponsesContent
                accepting = true; val id = r.id
                scope.launch {
                    if (!current()) return@launch
                    ApiClient.acceptResponse(id)
                        .onSuccess { bid -> if (current()) onAccepted(bid) }
                        .onFailure { if (current()) { Toast.makeText(ctx, serverSaid(it, failMsg), Toast.LENGTH_LONG).show(); accepting = false; reloadTick++ } }
                }
            },
            // Торг: своя цена и «не договорились». Ошибку сервера показываем как есть — она
            // двуязычная и объясняет причину («сейчас ход другой стороны», «торг окончен»).
            onCounter = { r -> if (current() && !accepting) counterFor = r },
            onDecline = { r ->
                if (accepting || !current()) return@ResponsesContent
                accepting = true; val id = r.id
                scope.launch {
                    if (!current()) return@launch
                    ApiClient.declineResponse(id)
                        .onFailure { if (current()) Toast.makeText(ctx, (it as? com.yuldash.app.data.ApiException)?.message ?: failMsg, Toast.LENGTH_SHORT).show() }
                    if (current()) { accepting = false; reloadTick++ }
                }
            },
            modifier = Modifier.padding(padding),
        )
    }
    counterFor?.let { target ->
        CounterPriceDialog(
            current = target.onTable,
            roundsLeft = (BARGAIN_MAX_TOTAL_UI - target.bargainRounds).coerceAtLeast(1),
            busy = accepting,
            onDismiss = { counterFor = null },
            onSend = { price ->
                if (!current() || accepting) return@CounterPriceDialog
                accepting = true; val id = target.id
                scope.launch {
                    if (!current()) return@launch
                    ApiClient.counterOffer(id, price)
                        .onFailure { if (current()) Toast.makeText(ctx, (it as? com.yuldash.app.data.ApiException)?.message ?: failMsg, Toast.LENGTH_LONG).show() }
                    if (current()) { accepting = false; counterFor = null; reloadTick++ }
                }
            },
        )
    }
}
