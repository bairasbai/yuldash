package com.yuldash.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yuldash.app.data.InstantOrderDto
import com.yuldash.app.data.TaxiStop
import com.yuldash.app.data.TaxiStopControl
import com.yuldash.app.data.canEditTaxiStopsById
import com.yuldash.app.data.isActivePlannedStop

internal fun taxiStopRemovable(order: InstantOrderDto, index: Int): Boolean =
    order.stops.getOrNull(index)?.let { !it.done && order.stopControl != TaxiStopControl.Invalid &&
        !order.isActivePlannedStop(it) } == true

internal fun canEditTaxiStops(order: InstantOrderDto, requested: List<TaxiStop>): Boolean =
    order.stopControl != TaxiStopControl.Invalid && order.stops.filter(order::isActivePlannedStop)
        .all { protected -> requested.count { it == protected } == 1 }

@Composable
internal fun TaxiStopConflictNotice(code: String) {
    Text(if (code == "taxi_active_waypoint_protected") appText(
        "Не удалось сохранить изменения. Сохрани остановку, на которой ждёт водитель.",
        "Үҙгәрештәрҙе һаҡлап булманы. Йөрөтөүсе көткән туҡталышты ҡалдыр.")
    else appText("Не удалось подтвердить изменение. Проверь обновлённый маршрут.",
        "Үҙгәреште раҫлап булманы. Яңыртылған маршрутты тикшер."),
        style = CanonCaption, color = CanonText)
}

@Composable
internal fun TaxiPassengerStopRows(
    order: InstantOrderDto, removeLabel: String, editing: Boolean = true, onReplaceStop: (Int) -> Unit = {}, onRemoveStop: (Int) -> Unit,
) {
    order.stops.forEachIndexed { i, st ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CanonSpace.sm),
        ) {
            Icon(Icons.Default.Place, contentDescription = null,
                tint = if (st.done) CanonMuted else CanonGreen2, modifier = Modifier.size(18.dp))
            Text(st.text, style = CanonBody, color = if (st.done) CanonMuted else CanonText,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (order.isActivePlannedStop(st)) {
                Text(appText("Водитель ждёт", "Йөрөтөүсе көтә"), style = CanonCaption, color = CanonText)
            } else if (editing && taxiStopRemovable(order, i)) {
                if (order.canEditTaxiStopsById() && st.waypointId != null) {
                    Surface(onClick = { onReplaceStop(i) }, shape = CircleShape, color = CanonBg,
                        modifier = Modifier.minimumInteractiveComponentSize().testTag("taxiReplaceStop$i")) {
                        Icon(Icons.Default.Edit, contentDescription = appText("Заменить остановку", "Туҡталышты алмаштырыу"),
                            tint = CanonMuted, modifier = Modifier.padding(CanonSpace.md).size(18.dp))
                    }
                }
                Surface(onClick = { onRemoveStop(i) }, shape = CircleShape, color = CanonBg,
                    modifier = Modifier.minimumInteractiveComponentSize().testTag("taxiRemoveStop$i")) {
                    Icon(Icons.Default.Close, contentDescription = removeLabel, tint = CanonMuted,
                        modifier = Modifier.padding(CanonSpace.md).size(18.dp))
                }
            }
        }
    }
}

/** Маршрут: откуда, остановки, куда. Отсюда же меняют адрес и добавляют остановку. */
@Composable
internal fun TripRouteBlock(
    order: InstantOrderDto,
    editStops: Boolean,
    onChangeDestination: () -> Unit,
    onAddStop: () -> Unit,
    onReplaceStop: (Int) -> Unit,
    onRemoveStop: (Int) -> Unit,
) {
    val active = order.status in setOf("accepted", "arriving", "onboard")
    val removeLabel = appText("Убрать остановку", "Туҡталышты алыу")
    val changeLabel = appText("Изменить адрес", "Адресты үҙгәртеү")
    Surface(color = CanonBg, shape = CanonItemShape, modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(CanonSpace.md),
            verticalArrangement = Arrangement.spacedBy(CanonSpace.sm),
        ) {
            MobilityRouteTimeline(from = order.fromText, to = order.toText, compact = true)
            TaxiPassengerStopRows(order, removeLabel, editStops, onReplaceStop, onRemoveStop)
            if (active) {
                Column {
                    TextButton(onClick = onAddStop, enabled = editStops && order.stopControl != TaxiStopControl.Invalid, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(CanonSpace.xs))
                        Text(
                            if (order.stops.isEmpty()) appText("Заехать по пути", "Юлда инеп сығыу")
                            else appText("Ещё остановка", "Тағы туҡталыш"),
                            style = CanonBody,
                            color = CanonGreen2,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(onClick = onChangeDestination, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Icon(Icons.Default.SwapHoriz, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(CanonSpace.xs))
                        Text(changeLabel, style = CanonBody, color = CanonGreen2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
