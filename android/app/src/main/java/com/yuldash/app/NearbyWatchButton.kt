package com.yuldash.app

import android.icu.lang.UCharacter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.ApiException
import com.yuldash.app.data.RouteWatchDto
import com.yuldash.app.data.createRouteWatch
import com.yuldash.app.data.deleteRouteWatch
import com.yuldash.app.data.getRouteWatches
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

internal data class NearbyWatchRoute(val from: String?, val to: String?, val day: String?) {
    val hasPlaces: Boolean get() = !from.isNullOrBlank() && !to.isNullOrBlank()
}

internal class RouteWatchNavigation {
    private var pending: Pair<Long, NearbyWatchRoute>? = null
    private var current: Pair<Long, NearbyWatchRoute>? = null

    fun prepare(generation: Long, signedIn: Boolean, route: NearbyWatchRoute) {
        pending = if (signedIn) null else generation to route
        current = if (signedIn) generation to route else null
    }

    fun signedIn(generation: Long, resumeWatch: Boolean) {
        current = pending?.takeIf { resumeWatch && it.first + 1 == generation }?.let { generation to it.second }
        pending = null
    }

    fun prefill(generation: Long): NearbyWatchRoute? = current?.takeIf { it.first == generation }?.second
    fun clear() { current = null; pending = null }

    companion object {
        private val processScope = java.util.UUID.randomUUID().toString()
        val Saver = listSaver<RouteWatchNavigation, Any>(
            save = { state ->
                val (generation, route) = state.pending ?: state.current ?: return@listSaver emptyList()
                listOf(processScope, state.pending != null, generation, route.from.orEmpty(), route.to.orEmpty(), route.day.orEmpty())
            },
            restore = { saved -> RouteWatchNavigation().apply {
                if (saved.size == 6) {
                    val sameProcess = saved[0] == processScope
                    val pendingGuest = saved[1] == true
                    val restoreGuest = pendingGuest && !ApiClient.isLoggedIn()
                    if (!sameProcess && !restoreGuest) return@apply
                    val generation = if (sameProcess) saved[2] as Long else ApiClient.queueSessionGeneration()
                    val value = generation to NearbyWatchRoute(
                        (saved[3] as String).ifBlank { null }, (saved[4] as String).ifBlank { null }, (saved[5] as String).ifBlank { null },
                    )
                    if (pendingGuest) pending = value else current = value
                }
            } },
        )
    }
}

internal fun nearbyWatchRoute(
    from: Set<String>, to: Set<String>, focusFrom: String?, focusTo: String?, day: String?,
): NearbyWatchRoute = NearbyWatchRoute(
    if (from.isEmpty()) focusFrom else from.singleOrNull(),
    if (to.isEmpty()) focusTo else to.singleOrNull(),
    day,
)

internal fun routeWatchLocalDay(iso: String): String? = parseIsoUtcMillis(iso)?.let {
    Instant.ofEpochMilli(it).atZone(ZoneId.of("Asia/Yekaterinburg")).toLocalDate().toString()
}

internal fun matchingNearbyWatch(watches: List<RouteWatchDto>, route: NearbyWatchRoute): RouteWatchDto? {
    if (!route.hasPlaces) return null
    val from = UCharacter.foldCase(route.from.orEmpty().trim(), true)
    val to = UCharacter.foldCase(route.to.orEmpty().trim(), true)
    return watches.filter {
        it.watchKind == "rides" && it.direction == "forward" &&
            UCharacter.foldCase(it.fromCity.trim(), true) == from && UCharacter.foldCase(it.toCity.trim(), true) == to &&
            (if (it.watchDate == null) route.day == null else routeWatchLocalDay(it.watchDate)?.let { day -> day == route.day } == true)
    }.minByOrNull { it.id }
}

@Composable
internal fun NearbyWatchButton(route: NearbyWatchRoute, owner: Long, accepts: () -> Boolean, onOpen: () -> Unit) {
    val follow = appText("Следить за маршрутом", "Маршрутты күҙәтеү")
    val canFollow = route.hasPlaces && validRouteWatchPlace(route.from.orEmpty().trim()) && validRouteWatchPlace(route.to.orEmpty().trim())
    if (!ApiClient.isLoggedIn() || !canFollow) {
        AppButton(follow, { if (accepts()) ApiClient.runIfUnchangedSession(owner, onOpen) },
            style = AppButtonStyle.Secondary, fillWidth = false)
        return
    }
    val scope = rememberCoroutineScope()
    var watch by remember(route, owner) { mutableStateOf<RouteWatchDto?>(null) }
    var busy by remember(route, owner) { mutableStateOf(false) }
    var status by remember(route, owner) { mutableStateOf("loading") }
    var error by remember(route, owner) { mutableStateOf(false) }
    var refresh by remember(route, owner) { mutableIntStateOf(0) }

    LaunchedEffect(route, owner, refresh) {
        status = "loading"
        val result = ApiClient.getRouteWatches(expectedGeneration = owner)
        ensureActive()
        if (ApiClient.isCurrentSession(owner) && accepts()) {
            watch = result.getOrNull()?.let { matchingNearbyWatch(it, route) }
            if (result.isSuccess) error = false
            status = if (result.isSuccess) "ready" else "error"
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(CanonSpace.xs)) {
        if (watch != null && status == "ready") {
            val places = "${route.from} → ${route.to}"
            Text(appText("Следим · $places", "Күҙәтәбеҙ · $places"), color = CanonMuted, style = CanonCaption)
        }
        if (error || status == "error") Text(
            appText("Не получилось обновить подписку. Попробуй ещё раз.", "Яҙылыуҙы яңыртып булманы. Тағы бер тапҡыр ҡара."),
            color = CanonRed, style = CanonCaption,
        )
        AppButton(
            text = when {
                status == "error" -> appText("Повторить", "Ҡабатлау")
                watch != null -> appText("Не следить", "Күҙәтмәҫкә")
                else -> follow
            },
            onClick = click@{
                if (busy || status == "loading" || !accepts() || !ApiClient.isCurrentSession(owner)) return@click
                if (status == "error") { refresh++; return@click }
                busy = true
                error = false
                scope.launch {
                    if (!accepts() || !ApiClient.isCurrentSession(owner)) { busy = false; return@launch }
                    val current = watch
                    if (current == null) {
                        val created = ApiClient.createRouteWatch(route.from.orEmpty().trim(), route.to.orEmpty().trim(),
                            watchDate = route.day?.let { "${it}T00:00:00" }, expectedGeneration = owner)
                        ensureActive()
                        if (created.isSuccess && ApiClient.isCurrentSession(owner) && accepts()) {
                            val persisted = ApiClient.getRouteWatches(expectedGeneration = owner)
                                .getOrNull()?.let { matchingNearbyWatch(it, route) }
                            ensureActive()
                            if (ApiClient.isCurrentSession(owner) && accepts()) {
                                watch = persisted?.takeIf { it.id == created.getOrNull() }
                                if (watch == null) status = "error"
                            }
                        } else if (ApiClient.isCurrentSession(owner) && accepts()) {
                            error = true
                            status = "loading"
                            refresh++
                        }
                    } else {
                        val deletion = ApiClient.deleteRouteWatch(current.id, expectedGeneration = owner)
                        ensureActive()
                        if (ApiClient.isCurrentSession(owner) && accepts()) {
                            error = deletion.isFailure && (deletion.exceptionOrNull() as? ApiException)?.status != 404
                            status = "loading"
                            refresh++
                        }
                    }
                    busy = false
                }
            },
            style = AppButtonStyle.Secondary,
            loading = busy || status == "loading",
            fillWidth = false,
        )
    }
}
