package com.yuldash.app

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.ApiException
import com.yuldash.app.data.RouteWatchDto
import com.yuldash.app.data.createRouteWatch
import com.yuldash.app.data.deleteRouteWatch
import com.yuldash.app.data.getRouteWatches
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// Экран «Мои подписки» (F13) вынесен из SecondaryScreens.kt целиком: он вырос на сетевой защите
// (вход владельца, поздний ответ, одноразовые изменения), а общий файл экранов расти не должен.

/**
 * F13 «Мои подписки» — подписка на маршрут «карауль поездку» (retention-двигатель).
 * Форма подписки (откуда/куда + туда-обратно) + список активных подписок с удалением.
 * Все состояния: загрузка / ошибка+повтор / пусто / список. Двуязычно.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RouteWatchesScreen(
    onBack: () -> Unit,
    prefillFrom: String = "",
    prefillTo: String = "",
) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val owner = ApiClient.sessionChanges.collectAsState().value
    var mounted by remember(owner) { mutableStateOf(true) }
    DisposableEffect(owner) { onDispose { mounted = false } }
    var watches by remember(owner) { mutableStateOf<List<RouteWatchDto>>(emptyList()) }
    var loading by remember(owner) { mutableStateOf(true) }
    var error by remember(owner) { mutableStateOf(false) }
    var from by rememberSaveable(owner) { mutableStateOf(prefillFrom) }
    var to by rememberSaveable(owner) { mutableStateOf(prefillTo) }
    var bothWays by rememberSaveable(owner) { mutableStateOf(false) }
    var submitting by remember(owner) { mutableStateOf(false) }
    var deletingId by remember(owner) { mutableStateOf<Int?>(null) }
    var failedDeleteId by remember(owner) { mutableStateOf<Int?>(null) }
    var createError by remember(owner) { mutableStateOf<String?>(null) }
    var loadVersion by remember(owner) { mutableIntStateOf(0) }
    var reloading by remember(owner) { mutableStateOf(false) }

    fun current() = mounted && ApiClient.isCurrentSession(owner)

    val savedMsg = appText("Подписка сохранена", "Яҙылыу һаҡланды")
    val failMsg = appText("Не получилось. Проверь сеть и повтори", "Булманы. Сетте тикшереп ҡабатла")
    val removedMsg = appText("Подписка удалена", "Яҙылыу юйылды")

    fun reload() {
        if (!current() || reloading || submitting || deletingId != null) return
        val version = ++loadVersion
        reloading = true
        loading = true; error = false
        scope.launch {
            if (!current() || version != loadVersion) return@launch
            val result = ApiClient.getRouteWatches(expectedGeneration = owner)
            if (!current() || version != loadVersion) return@launch
            result
                .onSuccess { watches = it; error = false }
                .onFailure { error = true }
            loading = false
            reloading = false
        }
    }
    LaunchedEffect(owner) {
        if (current()) reload() else loading = false
    }

    fun delete(id: Int) {
        if (!current() || submitting || deletingId != null || watches.none { it.id == id }) return
        deletingId = id
        failedDeleteId = null
        // A list read started before this mutation must not restore the removed row afterwards.
        loadVersion++
        reloading = false
        loading = false
        scope.launch {
            if (!current()) return@launch
            val result = ApiClient.deleteRouteWatch(id, expectedGeneration = owner)
            if (!current()) return@launch
            // The row was ours when tapped. 404 also means the intended absence is reached,
            // including a committed DELETE whose first acknowledgement was lost.
            if (result.isSuccess || (result.exceptionOrNull() as? ApiException)?.status == 404) {
                watches = watches.filterNot { it.id == id }
                Toast.makeText(ctx, removedMsg, Toast.LENGTH_SHORT).show()
            } else {
                failedDeleteId = id
            }
            deletingId = null
        }
    }

    Scaffold(
        containerColor = CanonBg,
        topBar = { ScreenTopBar(appText("Мои подписки", "Яҙылыуҙарым"), onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item {
                Text(
                    appText(
                        "Следи за нужным маршрутом. Сообщения появятся в разделе «Уведомления».",
                        "Кәрәкле маршрутты күҙәт. Хәбәрҙәр «Хәбәрҙәр» бүлегендә күренәсәк."
                    ),
                    color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp
                )
            }
            // --- Форма подписки ---
            item {
                AppCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            from, { from = it }, label = { Text(appText("Откуда", "Ҡайҙан")) },
                            leadingIcon = { Icon(Icons.Default.Route, contentDescription = null, tint = CanonGreen2) },
                            modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(14.dp), enabled = !submitting
                        )
                        OutlinedTextField(
                            to, { to = it }, label = { Text(appText("Куда", "Ҡайҙа")) },
                            leadingIcon = { Icon(Icons.Default.NotificationsActive, contentDescription = null, tint = CanonGreen2) },
                            modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(14.dp), enabled = !submitting
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.SwapHoriz, contentDescription = null, tint = CanonMuted, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(appText("И в обратную сторону", "Кире яҡҡа ла"), color = CanonText, fontSize = 16.sp, modifier = Modifier.weight(1f))
                            Switch(checked = bothWays, onCheckedChange = { bothWays = it }, enabled = !submitting)
                        }
                        val needBothMsg = appText("Укажи откуда и куда", "Ҡайҙан һәм ҡайҙа икәнен яҙ")
                        val invalidPlace = appText(
                            "В названии места нужны хотя бы две буквы, без цифр и до 120 знаков.",
                            "Урын исемендә кәм тигәндә ике хәреф булһын, һандарһыҙ һәм 120 тамғаға тиклем.",
                        )
                        AppButton(
                            text = appText("Следить за маршрутом", "Маршрутты күҙәтеү"),
                            onClick = {
                                if (!current() || submitting || deletingId != null) return@AppButton
                                val f = from.trim(); val t = to.trim()
                                if (f.isBlank() || t.isBlank()) {
                                    Toast.makeText(ctx, needBothMsg, Toast.LENGTH_SHORT).show()
                                    return@AppButton
                                }
                                if (!validRouteWatchPlace(f) || !validRouteWatchPlace(t)) {
                                    createError = invalidPlace
                                    return@AppButton
                                }
                                val direction = if (bothWays) "both" else "forward"
                                submitting = true
                                createError = null
                                loadVersion++
                                reloading = false
                                loading = false
                                scope.launch {
                                    if (!current()) return@launch
                                    val result = ApiClient.createRouteWatch(f, t, direction, expectedGeneration = owner)
                                    if (!current()) return@launch
                                    submitting = false
                                    result
                                        .onSuccess {
                                            Toast.makeText(ctx, savedMsg, Toast.LENGTH_SHORT).show()
                                            from = ""; to = ""; bothWays = false
                                            reload()
                                        }
                                        .onFailure {
                                            val m = (it as? com.yuldash.app.data.ApiException)?.message ?: failMsg
                                            createError = serverSaid(it, m)
                                        }
                                }
                            },
                            icon = Icons.Default.NotificationsActive,
                            loading = submitting,
                            enabled = deletingId == null,
                        )
                        createError?.let { Text(it, color = CanonRed, style = CanonBody) }
                    }
                }
            }
            // --- Список активных подписок ---
            item {
                Text(appText("Активные подписки", "Әүҙем яҙылыуҙар"), color = CanonGreen, fontWeight = FontWeight.Bold, fontSize = 19.sp, modifier = Modifier.padding(top = 4.dp))
            }
            failedDeleteId?.let { id ->
                item {
                    AppErrorState(
                        title = appText("Не получилось удалить подписку", "Яҙылыуҙы юйып булманы"),
                        text = failMsg,
                        onRetry = { delete(id) },
                    )
                }
            }
            if (error) item { AppErrorState(onRetry = { reload() }) }
            when {
                loading && watches.isEmpty() -> item { AppLoading(appText("Загрузка…", "Йөкләнә…")) }
                error && watches.isEmpty() -> Unit
                watches.isEmpty() -> item {
                    AppEmptyState(
                        title = appText("Пока нет подписок", "Әлегә яҙылыуҙар юҡ"),
                        text = appText("Укажи маршрут выше, чтобы начать следить за поездками.", "Сәфәрҙәрҙе күҙәтер өсөн, өҫтә маршрутты күрһәт."),
                        icon = Icons.Default.NotificationsActive,
                    )
                }
                else -> items(watches, key = { it.id }) { w ->
                    RouteWatchRow(
                        watch = w,
                        deleting = deletingId == w.id,
                        enabled = !submitting && deletingId == null,
                        onDelete = { delete(w.id) },
                    )
                }
            }
            item(key = "route-watch-notification-status") { RouteWatchNotificationStatus(watches.firstOrNull()) }
        }
    }
}

@Composable
private fun RouteWatchRow(watch: RouteWatchDto, deleting: Boolean, enabled: Boolean, onDelete: () -> Unit) {
    val deleteLabel = appText("Удалить", "Юйыу")
    AppCard {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = CanonMint, shape = CircleShape) {
                Icon(Icons.Default.Route, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(12.dp).size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (watch.direction == "both") "${watch.fromCity}  ⇄  ${watch.toCity}" else "${watch.fromCity}  →  ${watch.toCity}",
                    color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp
                )
                Text(
                    when (watch.watchKind) {
                        "requests" -> appText("Заявки пассажиров", "Юлсылар ғаризалары")
                        "both" -> appText("Поездки и заявки", "Сәфәрҙәр һәм ғаризалар")
                        else -> appText("Поездки", "Сәфәрҙәр")
                    },
                    color = CanonMuted, fontSize = 14.sp
                )
                watch.watchDate?.let(::parseIsoUtcMillis)?.let { millis ->
                    val day = Instant.ofEpochMilli(millis).atZone(routeWatchZone).format(routeWatchDayFormat)
                    Text(day, color = CanonMuted, style = CanonCaption)
                }
                val last = watch.lastNotifiedAt?.let(::formatDepart) ?: appText("Пока не было", "Әлегә булманы")
                Text(appText("Последнее сообщение · $last", "Һуңғы хәбәр · $last"), color = CanonMuted, style = CanonCaption)
            }
            IconButton(onClick = onDelete, enabled = enabled) {
                if (deleting) CircularProgressIndicator(
                    modifier = Modifier.size(CanonSpace.xl).semantics { contentDescription = deleteLabel },
                    color = CanonRed,
                )
                else Icon(Icons.Default.Delete, contentDescription = appText("Удалить", "Юйыу"), tint = CanonRed)
            }
        }
    }
}

private val routeWatchZone = ZoneId.of("Asia/Yekaterinburg")
private val routeWatchDayFormat = DateTimeFormatter.ofPattern("dd.MM.uuuu")

internal fun validRouteWatchPlace(name: String): Boolean {
    val codePoints = name.codePoints().toArray()
    if (codePoints.size > 120 || codePoints.count { Character.isLetter(it) } < 2) return false
    return codePoints.none {
        when (Character.getType(it)) {
            Character.DECIMAL_DIGIT_NUMBER.toInt(), Character.LETTER_NUMBER.toInt(), Character.OTHER_NUMBER.toInt() -> true
            else -> false
        }
    }
}

@Composable
private fun RouteWatchNotificationStatus(watch: RouteWatchDto?) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val manager = remember(context) { NotificationManagerCompat.from(context) }
    var systemAllowed by remember(manager) { mutableStateOf(manager.areNotificationsEnabled()) }
    DisposableEffect(lifecycle, manager) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) systemAllowed = manager.areNotificationsEnabled()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    AppCard {
        Column(Modifier.padding(CanonSpace.lg), verticalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
            val statusTexts = listOfNotNull(
                appText("Уведомления на этом устройстве разрешены", "Был ҡоролмала хәбәрҙәр рөхсәт ителгән").takeIf { systemAllowed },
                appText("Уведомления на этом устройстве выключены", "Был ҡоролмала хәбәрҙәр һүндерелгән").takeUnless { systemAllowed },
                appText("Канал приложения зарегистрирован", "Ҡушымта каналы теркәлгән").takeIf { watch?.registeredPushApp == true },
                appText("Канал браузера зарегистрирован", "Браузер каналы теркәлгән").takeIf { watch?.registeredPushWeb == true },
                appText("Каналы приложения и браузера не зарегистрированы. Сообщения остаются в «Уведомлениях».",
                    "Ҡушымта һәм браузер каналдары теркәлмәгән. Хәбәрҙәр «Хәбәрҙәр» бүлегендә ҡала.")
                    .takeIf { watch?.registeredPushApp == false && watch.registeredPushWeb == false },
            )
            statusTexts.forEach { Text(it, color = CanonMuted, style = CanonCaption) }
            val from = watch?.quietFrom
            val to = watch?.quietTo
            if (from != null && to != null && to > 0 && from != to) {
                val hours = "${from.toString().padStart(2, '0')}:00–${to.toString().padStart(2, '0')}:00"
                Text(appText("Тихие часы по Уфе · $hours. Сообщения остаются в «Уведомлениях» без звука.",
                    "Өфө ваҡыты буйынса тыныс сәғәттәр · $hours. Хәбәрҙәр «Хәбәрҙәр» бүлегендә тауышһыҙ ҡала."),
                    color = CanonMuted, style = CanonCaption)
            }
            if (!systemAllowed) AppButton(
                text = appText("Открыть настройки телефона", "Телефон көйләүҙәрен асыу"),
                onClick = { openNotificationSettings(context) },
                style = AppButtonStyle.Secondary,
            )
        }
    }
}
