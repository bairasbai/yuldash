package com.yuldash.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

sealed interface TaxiStopControl {
    data object Legacy : TaxiStopControl
    data object Invalid : TaxiStopControl
    data class V1(
        val admission: Boolean, val routeRevision: String?, val waitRevision: String?, val active: TaxiActiveWait?,
        val passengerEdit: Boolean = false,
    ) : TaxiStopControl
}

sealed interface TaxiActiveWait {
    data object LegacyUnknown : TaxiActiveWait
    data class Planned(val waitId: String, val waypointId: String) : TaxiActiveWait
    data class Unplanned(val waitId: String) : TaxiActiveWait
}

internal fun taxiStopId(value: Any?): String? = (value as? String)?.takeIf { id ->
    runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
}

internal fun JSONObject.taxiStopControl(): TaxiStopControl {
    if (!has("stop_control")) {
        val stops = optJSONArray("stops")
        if (stops != null && (0 until stops.length()).any { stops.optJSONObject(it)?.has("waypoint_id") == true })
            return TaxiStopControl.Invalid
        return TaxiStopControl.Legacy
    }
    return runCatching {
        val wire = getJSONObject("stop_control")
        fun id(key: String): String? = if (wire.get(key) === JSONObject.NULL) null
            else requireNotNull(taxiStopId(wire.get(key)))
        val admission = wire.get("admission_enabled") as Boolean
        val revision = id("route_revision")
        val wait = id("wait_revision")
        val stops = getJSONArray("stops")
        val ids = (0 until stops.length()).map { i ->
            val stop = stops.getJSONObject(i)
            if (revision == null) {
                require(!stop.has("waypoint_id"))
                null
            } else requireNotNull(taxiStopId(stop.get("waypoint_id")))
        }.filterNotNull()
        require(ids.distinct() == ids)
        val active = if (wire.get("active_wait") === JSONObject.NULL) null else {
            val value = wire.getJSONObject("active_wait")
            val activeWait = value.get("wait_id")
            val point = value.get("waypoint_id")
            when (value.get("kind")) {
                "legacy_unknown" -> TaxiActiveWait.LegacyUnknown.also {
                    require(activeWait === JSONObject.NULL && point === JSONObject.NULL)
                }
                "unplanned" -> TaxiActiveWait.Unplanned(requireNotNull(wait)).also {
                    require(activeWait == wait && point === JSONObject.NULL)
                }
                "planned" -> TaxiActiveWait.Planned(requireNotNull(wait), requireNotNull(taxiStopId(point))).also {
                    require(activeWait == wait && revision != null)
                    require((0 until stops.length()).count { i ->
                        val stop = stops.getJSONObject(i)
                        stop.opt("waypoint_id") == it.waypointId && stop.opt("done") == false
                    } == 1)
                }
                else -> error("Unsupported stop wait")
            }
        }
        require(get("standing") == (active != null) && get("status") == "onboard")
        require(admission || active is TaxiActiveWait.Planned || active is TaxiActiveWait.Unplanned)
        val passengerEdit = if (wire.has("passenger_edit_v1")) wire.get("passenger_edit_v1") as Boolean else false
        require(!passengerEdit || (revision != null && (admission || active is TaxiActiveWait.Planned)))
        TaxiStopControl.V1(admission, revision, wait, active, passengerEdit)
    }.getOrDefault(TaxiStopControl.Invalid)
}

internal fun InstantOrderDto.isActivePlannedStop(stop: TaxiStop): Boolean =
    ((stopControl as? TaxiStopControl.V1)?.active as? TaxiActiveWait.Planned)?.waypointId
        ?.let { it == stop.waypointId } == true

internal sealed interface TaxiStopCommand {
    data object LegacyToggle : TaxiStopCommand
    data object Prepare : TaxiStopCommand
    data class Planned(val wait: String?, val revision: String, val waypoint: String) : TaxiStopCommand
    data class Unplanned(val wait: String?) : TaxiStopCommand
    data class Resume(val wait: String) : TaxiStopCommand

    fun body(): JSONObject? = when (this) {
        LegacyToggle, Prepare -> null
        is Planned -> JSONObject().put("action", "start_planned").put("wait_revision", wait ?: JSONObject.NULL)
            .put("route_revision", revision).put("waypoint_id", waypoint)
        is Unplanned -> JSONObject().put("action", "start_unplanned").put("wait_revision", wait ?: JSONObject.NULL)
        is Resume -> JSONObject().put("action", "resume").put("wait_id", wait)
    }
}

internal suspend fun sendTaxiStopCommand(orderId: Int, command: TaxiStopCommand, generation: Long): Result<JSONObject> {
    val suffix = when (command) {
        TaxiStopCommand.Prepare -> "waypoints/v1/prepare"
        TaxiStopCommand.LegacyToggle -> "stop"
        else -> "stop/v1"
    }
    return ApiClient.taxiStopRequest("/instant/orders/$orderId/$suffix", generation, post = true, body = command.body())
}

/** Only the established legacy coordinate/text fields; Android never invents or sends waypoint IDs here. */
internal fun stopsArray(stops: List<TaxiStop>): JSONArray = JSONArray().apply {
    stops.forEach { put(JSONObject().put("lat", it.lat).put("lng", it.lng).put("text", it.text)) }
}

internal fun InstantOrderDto.canEditTaxiStopsById(): Boolean =
    role == "passenger" && status == "onboard" && (stopControl as? TaxiStopControl.V1)?.passengerEdit == true

internal fun taxiStopEditBody(order: InstantOrderDto, stops: List<TaxiStop>): JSONObject? {
    val control = order.stopControl as? TaxiStopControl.V1
    if (!order.canEditTaxiStopsById()) {
        if (order.stopControl == TaxiStopControl.Invalid || control?.passengerEdit == true) return null
        return JSONObject().put("waypoints", stopsArray(stops))
    }
    val route = control?.routeRevision ?: return null
    val pending = order.stops.filterNot { it.done }
    val ids = stops.mapNotNull { it.waypointId }
    if (stops.size > 3 || stops.any { it.done } || pending.filter { it.waypointId in ids }.map { it.waypointId } != ids)
        return null
    val active = control.active as? TaxiActiveWait.Planned
    if (active != null && active.waypointId !in ids) return null
    val rows = JSONArray()
    for (stop in stops) {
        val id = stop.waypointId
        // Only accept exactly the retained row from this authorized snapshot; no client-chosen ID for new.
        if (id != null && pending.singleOrNull { it.waypointId == id } != stop) return null
        val row = if (id != null) JSONObject().put("kind", "keep").put("waypoint_id", id)
            else JSONObject().put("kind", "new").put("lat", stop.lat).put("lng", stop.lng).put("text", stop.text)
        rows.put(row)
    }
    return JSONObject().put("waypoints", rows).put("route_revision", route)
        .put("wait_revision", control.waitRevision ?: JSONObject.NULL)
}

internal suspend fun changeTaxiStopsOnce(order: InstantOrderDto, stops: List<TaxiStop>, generation: Long): Result<JSONObject> {
    val body = taxiStopEditBody(order, stops) ?: return Result.failure(IllegalArgumentException("Stale stop edit"))
    val suffix = if (order.canEditTaxiStopsById()) "/v1" else ""
    return ApiClient.taxiStopRequest("/instant/orders/${order.id}/waypoints$suffix", generation, post = true, body = body)
}

internal suspend fun readTaxiStopOrder(orderId: Int, generation: Long): Result<InstantOrderDto> =
    ApiClient.taxiStopRequest("/instant/orders/$orderId", generation, post = false).mapCatching { raw ->
        require(raw.get("id") == orderId && raw.get("role") in setOf("driver", "passenger") && raw.get("standing") is Boolean)
        ApiClient.parseTaxiConsentOrder(raw)
    }

internal fun InstantOrderDto.sameStopSnapshot(fresh: InstantOrderDto): Boolean =
    id == fresh.id && role == fresh.role && status == fresh.status && standing == fresh.standing &&
        stops == fresh.stops && stopControl == fresh.stopControl

internal fun InstantOrderDto.hasAdvancedTaxiStopEdit(fresh: InstantOrderDto): Boolean =
    id == fresh.id && canEditTaxiStopsById() && fresh.canEditTaxiStopsById() &&
        (stopControl as TaxiStopControl.V1).routeRevision != (fresh.stopControl as TaxiStopControl.V1).routeRevision

internal fun taxiStopEditDefinitivelyRefused(error: Throwable?): Boolean = error is ApiException && when (error.status) {
    429 -> error.detailCode == "taxi_waypoint_edit_cooldown"
    409 -> error.detailCode in setOf("taxi_active_waypoint_protected", "taxi_waypoints_stale")
    else -> false
}

internal fun canResolveTaxiStopEdit(
    before: InstantOrderDto, current: InstantOrderDto, fresh: InstantOrderDto, mustAdvance: Boolean,
): Boolean = current.id == before.id && current.canEditTaxiStopsById() &&
    (before.hasAdvancedTaxiStopEdit(fresh) || !mustAdvance && before.sameStopSnapshot(fresh))

private fun JSONObject.boundStopReceipt(before: InstantOrderDto): InstantOrderDto? = runCatching {
    val raw = getJSONObject("order")
    require(raw.get("id") == before.id && raw.get("role") == before.role && raw.get("status") == "onboard" && raw.get("standing") is Boolean)
    ApiClient.parseTaxiConsentOrder(raw)
}.getOrNull()

internal fun taxiStopEditReceipt(before: InstantOrderDto, requested: List<TaxiStop>, response: JSONObject?): InstantOrderDto? {
    if (response?.opt("ok") != true || response.opt("applied") != true) return null
    val fresh = response.boundStopReceipt(before) ?: return null
    if (!before.hasAdvancedTaxiStopEdit(fresh)) return null
    val prior = before.stopControl as TaxiStopControl.V1
    val current = fresh.stopControl as TaxiStopControl.V1
    if (prior.waitRevision != current.waitRevision || prior.active != current.active ||
        before.stops.filter { it.done } != fresh.stops.filter { it.done }) return null
    val pending = fresh.stops.filterNot { it.done }
    val previousIds = before.stops.mapNotNull { it.waypointId }.toSet()
    return fresh.takeIf { pending.size == requested.size && requested.zip(pending).all { (a, b) ->
        if (a.waypointId != null) a == b else b.waypointId !in previousIds && a == b.copy(waypointId = null)
    } }
}

internal fun taxiStopCommandReceipt(
    before: InstantOrderDto, command: TaxiStopCommand, response: JSONObject?,
): InstantOrderDto? {
    if (response?.opt("ok") != true) return null
    val fresh = response.boundStopReceipt(before) ?: return null
    if (response.opt("standing") != fresh.standing) return null
    return fresh.takeIf { taxiStopCommandObserved(before, command, fresh, allowDrained = true) }
}

internal fun taxiStopCommandObserved(
    before: InstantOrderDto, command: TaxiStopCommand, fresh: InstantOrderDto, allowDrained: Boolean,
): Boolean {
    if (before.id != fresh.id || fresh.role != "driver" || before.role != fresh.role || fresh.status != "onboard") return false
    val control = fresh.stopControl as? TaxiStopControl.V1
    val previous = before.stopControl as? TaxiStopControl.V1 ?: return false
    return when (command) {
        TaxiStopCommand.LegacyToggle -> false
        TaxiStopCommand.Prepare -> previous.admission && previous.routeRevision == null && control != null && control.admission && control.routeRevision != null &&
            control.waitRevision == previous.waitRevision && control.active == previous.active
        is TaxiStopCommand.Planned -> (control?.active as? TaxiActiveWait.Planned)?.let {
            previous.admission && previous.active == null && previous.waitRevision == command.wait &&
                previous.routeRevision == command.revision && it.waypointId == command.waypoint && it.waitId != command.wait
        } == true
        is TaxiStopCommand.Unplanned -> (control?.active as? TaxiActiveWait.Unplanned)?.let {
            previous.admission && previous.active == null && previous.waitRevision == command.wait && it.waitId != command.wait
        } == true
        is TaxiStopCommand.Resume -> previous.active.let {
            (it as? TaxiActiveWait.Planned)?.waitId ?: (it as? TaxiActiveWait.Unplanned)?.waitId
        } == command.wait && !fresh.standing && when {
            control != null -> control.active == null && control.waitRevision != null && control.waitRevision != command.wait
            else -> allowDrained && fresh.stopControl == TaxiStopControl.Legacy
        }
    }
}
