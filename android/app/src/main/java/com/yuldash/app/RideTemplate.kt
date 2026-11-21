package com.yuldash.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.CommandIntent
import com.yuldash.app.data.RidePublicationApi
import com.yuldash.app.data.RideReturnTemplate
import com.yuldash.app.data.getReturnTemplate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal data class RideCreatePrefill(val date: String? = null, val template: RideReturnTemplate? = null) {
    companion object {
        fun restore(saved: String?): RideCreatePrefill = if (saved?.startsWith("{") == true) {
            RideCreatePrefill(template = RideReturnTemplate.restore(saved))
        } else {
            RideCreatePrefill(date = saved)
        }
    }
}

internal fun createRideStateKey(ownerId: Int?, savedPrefill: String?): String =
    if (savedPrefill == null) "$ownerId:CreateRide" else "$ownerId:CreateRide:$savedPrefill"

internal data class RideReturnActions(
    val busyRide: Int?,
    val failedRide: Int?,
    val pendingRide: Int?,
    val open: (Int) -> Unit,
)

@Composable
internal fun rememberRideReturnActions(onTemplate: (RideReturnTemplate) -> Unit): RideReturnActions {
    val generation by ApiClient.sessionChanges.collectAsState()
    val onReady by rememberUpdatedState(onTemplate)
    val scope = rememberCoroutineScope()
    var busy by remember(generation) { mutableStateOf<Int?>(null) }
    var failure by remember(generation) { mutableStateOf<Int?>(null) }
    var pending by remember(generation) { mutableStateOf<Int?>(null) }
    return RideReturnActions(busy, failure, pending) { id ->
        val owner = generation
        if (busy == null && ApiClient.isCurrentSession(owner)) {
            busy = id
            failure = null
            pending = null
            scope.launch {
                try {
                    val before = RidePublicationApi.saved(owner).getOrThrow()
                    if (!ApiClient.isCurrentSession(owner)) return@launch
                    if (before?.state == CommandIntent.State.Unknown) {
                        pending = id
                        return@launch
                    }
                    val template = ApiClient.getReturnTemplate(id, owner).getOrThrow()
                    val latest = RidePublicationApi.saved(owner).getOrThrow()
                    if (!ApiClient.isCurrentSession(owner)) return@launch
                    if (before?.key != latest?.key || latest?.state == CommandIntent.State.Unknown) pending = id
                    else onReady(template)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    if (ApiClient.isCurrentSession(owner)) failure = id
                } finally {
                    busy = null
                }
            }
        }
    }
}

@Composable
internal fun RideReturnTemplateButton(
    rideId: Int?,
    actions: RideReturnActions?,
    reviewPrevious: (() -> Unit)? = null,
    published: Boolean = false,
) {
    if (rideId == null || actions == null) return
    Column {
        TextButton(onClick = { actions.open(rideId) }, enabled = actions.busyRide == null,
            modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(Icons.Default.SwapHoriz, contentDescription = null)
            Text(
                when {
                    actions.busyRide == rideId -> appText("Готовим черновик…", "Ҡаралама әҙерләйбеҙ…")
                    actions.failedRide == rideId -> appText("Повторить", "Ҡабатларға")
                    published -> appText("Опубликовать обратно", "Кире яҡҡа сәфәр баҫтырырға")
                    else -> appText("Обратно", "Кире яҡҡа")
                },
                modifier = Modifier.padding(start = CanonSpace.sm),
                style = CanonButton,
            )
        }
        if (actions.failedRide == rideId) {
            Text(appText("Не удалось загрузить обратный маршрут.", "Кире маршрутты тейәп булманы."),
                color = CanonText, style = CanonBody)
        }
        if (actions.pendingRide == rideId) {
            Text(appText("Сначала проверь прошлую публикацию.", "Тәүҙә алдағы баҫтырыуҙы тикшер."),
                color = CanonText, style = CanonBody)
            if (reviewPrevious != null) {
                TextButton(onClick = reviewPrevious, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(appText("Проверить публикацию", "Баҫтырыуҙы тикшерергә"), style = CanonButton)
                }
            }
        }
    }
}

@Composable
internal fun RideReturnTemplateNotice(template: RideReturnTemplate) {
    Text(
        appText("Заполни заново время, место встречи и комментарий для обратной поездки.",
            "Кире сәфәр өсөн ваҡытты, осрашыу урынын һәм аңлатманы яңынан яҙ."),
        color = CanonText, style = CanonBody,
    )
    if ("stop_prices" in template.dropped) {
        Text(appText("Проверь цены остановок.", "Туҡталыш хаҡтарын тикшер."), color = CanonText, style = CanonBody)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RidePublishedSheet(ride: Ride, onTemplate: (RideReturnTemplate) -> Unit, onDone: () -> Unit) {
    val actions = rememberRideReturnActions(onTemplate)
    AppModalBottomSheet(onDismissRequest = onDone, containerColor = CanonSurface) {
        Column(Modifier.fillMaxWidth().padding(CanonSpace.lg), verticalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
            Text(appText("Поездка опубликована", "Сәфәр баҫтырылды"), style = CanonTitle, color = CanonText)
            RideReturnTemplateButton(ride.id.toIntOrNull(), actions, published = true)
            TextButton(onClick = onDone, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(appText("Готово", "Әҙер"), style = CanonButton)
            }
        }
    }
}
