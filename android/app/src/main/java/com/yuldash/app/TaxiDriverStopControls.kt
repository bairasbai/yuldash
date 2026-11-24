package com.yuldash.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.yuldash.app.data.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.IOException

private data class PendingStopRead(
    val before: InstantOrderDto, val command: TaxiStopCommand?, val drainedReceipt: InstantOrderDto? = null,
    val canOfferSameCommand: Boolean = false,
)

@Composable
internal fun TaxiDriverStopControls(
    order: InstantOrderDto,
    generation: Long,
    onOrder: (InstantOrderDto) -> Unit,
    send: suspend (Int, TaxiStopCommand, Long) -> Result<JSONObject> = ::sendTaxiStopCommand,
    read: suspend (Int, Long) -> Result<InstantOrderDto> = ::readTaxiStopOrder,
) {
    val session by ApiClient.sessionChanges.collectAsState()
    if (order.status != "onboard" || order.role != "driver" || session != generation ||
        !ApiClient.isCurrentSession(generation)) return
    val latest by rememberUpdatedState(order)
    val publish by rememberUpdatedState(onOrder)
    val scope = rememberCoroutineScope()
    var picker by remember(order.id, generation) { mutableStateOf<InstantOrderDto?>(null) }
    var busy by remember(order.id, generation) { mutableStateOf(false) }
    var mustRead by remember(order.id, generation) { mutableStateOf(false) }
    var pending by remember(order.id, generation) { mutableStateOf<PendingStopRead?>(null) }
    var offeredRetry by remember(order.id, generation) { mutableStateOf<PendingStopRead?>(null) }
    var changed by remember(order.id, generation) { mutableStateOf<String?>(null) }
    fun live() = scope.isActive && ApiClient.isCurrentSession(generation) &&
        latest.id == order.id && latest.status == "onboard" && latest.role == "driver"
    fun sameState(snapshot: InstantOrderDto) = latest.stops == snapshot.stops &&
        latest.stopControl == snapshot.stopControl && latest.standing == snapshot.standing
    fun dispatch(snapshot: InstantOrderDto, command: TaxiStopCommand?, retry: PendingStopRead? = null) {
        val explicitRetry = retry != null && retry === offeredRetry && retry === pending &&
            retry.command == command && retry.before.sameStopSnapshot(snapshot)
        if (busy || !live() || !sameState(snapshot) || (mustRead && command != null && !explicitRetry)) return
        offeredRetry = null
        picker = null
        busy = true
        changed = null
        scope.launch {
            try {
                if (!live() || !sameState(snapshot)) return@launch
                val typed = command != null && command != TaxiStopCommand.LegacyToggle
                if (typed) {
                    val launch = read(snapshot.id, generation).getOrNull()
                    if (!live()) return@launch
                    if (launch == null || !snapshot.sameStopSnapshot(launch) || !sameState(snapshot)) {
                        if (!explicitRetry) pending = PendingStopRead(snapshot, null)
                        mustRead = true
                        changed = "taxi_wait_stale"
                        return@launch
                    }
                }
                val result = command?.let { send(snapshot.id, it, generation) }
                if (!live()) return@launch
                val receipt = if (typed && command != null) taxiStopCommandReceipt(snapshot, command, result?.getOrNull()) else null
                val denial = result?.exceptionOrNull() as? ApiException
                val refused = denial?.status == 409 && denial.detailCode in setOf("taxi_wait_stale", "taxi_waypoints_stale")
                if (typed) pending = PendingStopRead(snapshot, command.takeUnless { refused },
                    drainedReceipt = receipt?.takeIf { command is TaxiStopCommand.Resume && it.stopControl == TaxiStopControl.Legacy },
                    canOfferSameCommand = result?.exceptionOrNull() is IOException &&
                        (command is TaxiStopCommand.Planned || command is TaxiStopCommand.Unplanned ||
                            command is TaxiStopCommand.Resume || command == TaxiStopCommand.Prepare))
                changed = result?.exceptionOrNull()?.let { (it as? ApiException)?.detailCode.orEmpty() }
                    ?: if (typed && receipt == null) "" else null
                // Neither a receipt nor an attempted mutation directly changes the displayed order.
                val fresh = read(snapshot.id, generation).getOrNull()?.takeIf {
                    it.id == snapshot.id && it.role == "driver"
                }
                if (!live()) return@launch
                val proof = pending
                val coherent = fresh != null && (proof == null || if (proof.command == null) {
                    fresh.status == "onboard" && fresh.stopControl is TaxiStopControl.V1
                } else taxiStopCommandObserved(proof.before, proof.command, fresh, proof.drainedReceipt?.sameStopSnapshot(fresh) == true))
                mustRead = !coherent || !sameState(snapshot)
                // Only a separate read tap may arm the exact same typed command; the following button
                // performs another fresh admission read. A different offer/wait/route cannot inherit it.
                if (mustRead && command == null && proof?.canOfferSameCommand == true && fresh != null &&
                    sameState(snapshot) && proof.before.sameStopSnapshot(snapshot) && proof.before.sameStopSnapshot(fresh))
                    offeredRetry = proof
                if (receipt != null && (fresh == null || !receipt.sameStopSnapshot(fresh))) changed = ""
                if (!mustRead && fresh != null) {
                    pending = null
                    ApiClient.runIfCurrentSession(generation) { if (live()) publish(fresh) }
                    val control = fresh.stopControl as? TaxiStopControl.V1
                    if (command == TaxiStopCommand.Prepare && receipt?.sameStopSnapshot(fresh) == true &&
                        control?.admission == true && control.active == null && control.routeRevision != null) picker = fresh
                }
            } finally {
                if (live()) busy = false
            }
        }
    }
    val control = order.stopControl
    when {
        mustRead || control == TaxiStopControl.Invalid -> {
            AppButton(appText("Обновить остановки", "Туҡталыштарҙы яңыртыу"),
                onClick = { dispatch(order, null) }, enabled = !busy)
            offeredRetry?.takeIf { it.before.sameStopSnapshot(order) }?.let { exact ->
                val label = when (exact.command) {
                    TaxiStopCommand.Prepare -> appText("Повторить загрузку остановок", "Туҡталыштарҙы тейәүҙе ҡабатлау")
                    is TaxiStopCommand.Resume -> appText("Повторить завершение ожидания", "Көтөүҙе тамамлауҙы ҡабатлау")
                    else -> appText("Повторить начало ожидания", "Көтөүҙе башлауҙы ҡабатлау")
                }
                val tag = when (exact.command) {
                    TaxiStopCommand.Prepare -> "taxiRetrySamePrepare"
                    is TaxiStopCommand.Resume -> "taxiRetrySameWaitResume"
                    else -> "taxiRetrySameWaitStart"
                }
                AppButton(label, onClick = { dispatch(order, exact.command, retry = exact) }, enabled = !busy,
                    modifier = Modifier.testTag(tag))
            }
        }
        control == TaxiStopControl.Legacy || (control as? TaxiStopControl.V1)?.active == TaxiActiveWait.LegacyUnknown ->
            LegacyDriverStopButton(order, onToggle = { dispatch(order, TaxiStopCommand.LegacyToggle) }, enabled = !busy)
        control is TaxiStopControl.V1 -> {
            val waitId = when (val active = control.active) {
                is TaxiActiveWait.Planned -> active.waitId
                is TaxiActiveWait.Unplanned -> active.waitId
                else -> null
            }
            if (waitId != null || control.admission) {
                AppButton(if (waitId != null) appText("Поехали", "Киттек") else appText("Стоим", "Туҡтап торабыҙ"),
                    onClick = {
                        if (!busy && !mustRead && live() && sameState(order)) {
                            if (waitId != null) dispatch(order, TaxiStopCommand.Resume(waitId))
                            else if (control.routeRevision == null && order.stops.any { !it.done })
                                dispatch(order, TaxiStopCommand.Prepare)
                            else picker = order
                        }
                    }, enabled = !busy, modifier = Modifier.testTag("taxiStopAction"))
            }
        }
    }
    changed?.let { code ->
        Text(when (code) {
            "taxi_wait_stale", "taxi_waypoints_stale", "taxi_explicit_wait_required" ->
                appText("Состояние остановки изменилось. Проверь его перед новым действием.",
                    "Туҡталыштың торошо үҙгәрҙе. Яңы ғәмәл алдынан уны тикшер.")
            else -> appText("Не удалось подтвердить действие. Проверь состояние остановки перед новым действием.",
                "Ғәмәлде раҫлап булманы. Яңы ғәмәл алдынан туҡталыштың торошон тикшер.")
        }, style = CanonCaption, color = CanonText)
    }
    picker?.let { snapshot ->
        val ready = snapshot.stopControl as? TaxiStopControl.V1
        if (sameState(snapshot) && ready?.admission == true && ready.active == null && !busy && !mustRead) {
            AlertDialog(
                onDismissRequest = { picker = null },
                title = { Text(appText("Где остановились?", "Ҡайҙа туҡтанығыҙ?"), style = CanonHeading, color = CanonText) },
                containerColor = CanonSurface,
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
                        val revision = ready.routeRevision
                        if (revision != null) snapshot.stops.forEachIndexed { index, stop ->
                            val id = stop.waypointId
                            if (!stop.done && id != null) AppButton(
                                appText("Остановка ${index + 1}: ${stop.text}", "${index + 1}-се туҡталыш: ${stop.text}"),
                                onClick = { dispatch(snapshot, TaxiStopCommand.Planned(ready.waitRevision, revision, id)) },
                                modifier = Modifier.testTag("taxiPlannedStop$index"))
                        }
                        AppButton(appText("Внеплановая остановка", "Пландан тыш туҡталыш"),
                            onClick = { dispatch(snapshot, TaxiStopCommand.Unplanned(ready.waitRevision)) },
                            modifier = Modifier.testTag("taxiUnplannedStop"))
                    }
                },
                confirmButton = {
                    TextButton(onClick = { picker = null }) {
                        Text(appText("Назад", "Артҡа"), style = CanonButton, color = CanonGreen2)
                    }
                },
            )
        } else SideEffect { picker = null }
    }
}
