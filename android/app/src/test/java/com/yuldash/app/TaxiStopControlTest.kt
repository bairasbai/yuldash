package com.yuldash.app

import com.yuldash.app.data.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TaxiStopControlTest {
    private val id1 = "12b49230-26dc-48a4-b1c3-b36efc57d812"
    private val id2 = "a032b720-88bf-44dd-b7fe-0148fbca6eb0"
    private val id3 = "ec45e4b2-47ed-406d-a859-fc2bb5488b2a"
    private val revision = "be8bbb09-f8aa-4383-b673-f7003cf6b187"
    private val wait = "758b563a-8c7e-46e5-9e7d-e63591175290"

    private fun point(id: String, done: Boolean = false) = JSONObject().put("waypoint_id", id)
        .put("lat", 52.5).put("lng", 58.1).put("text", "Synthetic").put("done", done)
    private fun payload(kind: String = "planned", enabled: Boolean = true): JSONObject {
        val active = JSONObject().put("kind", kind)
            .put("wait_id", if (kind == "legacy_unknown") JSONObject.NULL else wait)
            .put("waypoint_id", if (kind == "planned") id3 else JSONObject.NULL)
        return ConsentFixture.orderJson(status = "onboard")
            .put("standing", true)
            .put("stops", JSONArray().put(point(id1, done = true)).put(point(id2)).put(point(id3)))
            .put("stop_control", JSONObject().put("admission_enabled", enabled)
                .put("route_revision", revision).put("wait_revision", wait).put("active_wait", active))
    }

    @Test fun exactSecondDuplicateIsLockedEvenWhenAdmissionOff_futureOrdinaryEditRemainsAllowed() {
        val order = ApiClient.parseTaxiConsentOrder(payload(enabled = false))
        val control = order.stopControl as TaxiStopControl.V1
        assertFalse(control.admission)
        assertEquals(TaxiActiveWait.Planned(wait, id3), control.active)
        assertFalse(taxiStopRemovable(order, 0))
        assertTrue(taxiStopRemovable(order, 1))
        assertFalse(taxiStopRemovable(order, 2))
        val ordinaryFuture = TaxiStop(52.6, 58.2, "Synthetic future")
        assertTrue(canEditTaxiStops(order, listOf(order.stops[1], order.stops[2], ordinaryFuture)))
        assertTrue(canEditTaxiStops(order.copy(stops = order.stops + ordinaryFuture), order.stops.drop(1)))
        assertFalse(canEditTaxiStops(order, listOf(order.stops[1], ordinaryFuture)))
    }

    @Test fun unplannedAndLegacyWaitNeverImplyAPlannedPoint() {
        for (kind in listOf("unplanned", "legacy_unknown")) {
            val order = ApiClient.parseTaxiConsentOrder(payload(kind))
            assertTrue(taxiStopRemovable(order, 1))
            assertTrue(taxiStopRemovable(order, 2))
        }
        assertEquals(TaxiStopControl.Legacy, ConsentFixture.orderJson().taxiStopControl())
    }

    @Test fun malformedPresentCapabilityNeverDegradesToLegacy() {
        val manipulations = listOf<(JSONObject) -> Unit>(
            { it.put("stop_control", JSONObject.NULL) },
            { it.getJSONObject("stop_control").put("admission_enabled", "true") },
            { it.put("standing", false) },
            { it.getJSONArray("stops").getJSONObject(1).put("waypoint_id", id3) },
            { it.getJSONArray("stops").getJSONObject(2).put("done", true) },
            { it.getJSONObject("stop_control").getJSONObject("active_wait").put("waypoint_id", id1) },
            { it.getJSONObject("stop_control").getJSONObject("active_wait").put("wait_id", revision) },
        )
        for (change in manipulations) {
            val value = payload().also(change)
            assertEquals(TaxiStopControl.Invalid, value.taxiStopControl())
            assertFalse(taxiStopRemovable(ApiClient.parseTaxiConsentOrder(value), 1))
        }
    }

    @Test fun v3KeepNamesExactDuplicateAndReplacementHasOnlyNewValues() {
        val wire = payload(enabled = false)
        wire.getJSONObject("stop_control").getJSONObject("active_wait").put("waypoint_id", id2)
        wire.getJSONObject("stop_control").put("passenger_edit_v1", true)
        val order = ApiClient.parseTaxiConsentOrder(wire)
        assertTrue(order.canEditTaxiStopsById())
        val kept = order.stops[1]
        val deleted = taxiStopEditBody(order, listOf(kept))!!
        assertEquals(revision, deleted.getString("route_revision"))
        assertEquals(wait, deleted.getString("wait_revision"))
        val rows = deleted.getJSONArray("waypoints")
        assertEquals(1, rows.length())
        assertEquals("keep", rows.getJSONObject(0).getString("kind"))
        assertEquals(id2, rows.getJSONObject(0).getString("waypoint_id"))
        assertEquals(2, rows.getJSONObject(0).length())
        val replacement = TaxiStop(52.6, 58.2, "Replacement")
        val swapped = taxiStopEditBody(order, listOf(kept, replacement))!!.getJSONArray("waypoints")
        assertEquals(id2, swapped.getJSONObject(0).getString("waypoint_id"))
        assertEquals(setOf("kind", "lat", "lng", "text"), swapped.getJSONObject(1).keys().asSequence().toSet())
        assertEquals("new", swapped.getJSONObject(1).getString("kind"))
        assertEquals("Replacement", swapped.getJSONObject(1).getString("text"))
        assertNull(taxiStopEditBody(order, listOf(order.stops[2], replacement)))
        assertNull(taxiStopEditBody(order, listOf(kept, kept.copy(text = "Not actually retained"))))
        assertNull(taxiStopEditBody(order.copy(role = "driver"), listOf(kept)))
    }

    @Test fun v3CapabilityMustBeAnExactBooleanAndV2HasNoExplicitRows() {
        val wire = payload()
        val control = wire.getJSONObject("stop_control")
        val absent = ApiClient.parseTaxiConsentOrder(wire)
        assertFalse(absent.canEditTaxiStopsById())
        assertFalse(taxiStopEditBody(absent, absent.stops.filterNot { it.done })!!.has("route_revision"))
        control.put("passenger_edit_v1", false)
        assertEquals(absent.stopControl, wire.taxiStopControl())
        control.put("passenger_edit_v1", "true")
        assertEquals(TaxiStopControl.Invalid, wire.taxiStopControl())
    }

    @Test fun generic429DoesNotProveThatTypedEditWasRefusedBeforeWriting() {
        assertFalse(taxiStopEditDefinitivelyRefused(ApiException(429, "Synthetic gateway limit")))
        assertFalse(taxiStopEditDefinitivelyRefused(ApiException(429, "Synthetic other limit", "rate_limited")))
        assertFalse(taxiStopEditDefinitivelyRefused(ApiException(503, "Synthetic outage", "taxi_waypoint_edit_cooldown")))
        assertTrue(taxiStopEditDefinitivelyRefused(ApiException(429, "Synthetic cooldown", "taxi_waypoint_edit_cooldown")))
    }

    @Test fun typedPassengerReceiptRequiresV3NewRevisionAndExactRetainedOccurrence() {
        val wire = payload()
        wire.getJSONObject("stop_control").put("passenger_edit_v1", true)
        val prior = ApiClient.parseTaxiConsentOrder(wire)
        val requested = listOf(prior.stops.last())
        fun receipt(raw: JSONObject) = JSONObject().put("ok", true).put("applied", true).put("order", raw)
        assertNull(taxiStopEditReceipt(prior, requested, receipt(wire)))
        val changed = JSONObject(wire.toString()).put("stops", JSONArray().put(point(id1, true)).put(point(id3)))
        changed.getJSONObject("stop_control").put("route_revision", id2)
        assertNotNull(taxiStopEditReceipt(prior, requested, receipt(changed)))
        val confirmed = taxiStopEditReceipt(prior, requested, receipt(changed))!!
        val rival = JSONObject(changed.toString())
        rival.getJSONObject("stop_control").put("route_revision", id1)
        rival.getJSONArray("stops").getJSONObject(1).put("text", "Other same-order update")
        val rivalFresh = ApiClient.parseTaxiConsentOrder(rival)
        assertTrue(prior.hasAdvancedTaxiStopEdit(rivalFresh))
        assertFalse(confirmed.sameStopSnapshot(rivalFresh))
        val v2 = JSONObject(changed.toString()).also { it.getJSONObject("stop_control").remove("passenger_edit_v1") }
        assertNull(taxiStopEditReceipt(prior, requested, receipt(v2)))
        assertFalse(prior.hasAdvancedTaxiStopEdit(ApiClient.parseTaxiConsentOrder(v2)))
        // An add picker opened on the V3 snapshot is not authorized by the later V2 poll.
        assertFalse(prior.sameStopSnapshot(ApiClient.parseTaxiConsentOrder(v2)))
        assertFalse(prior.sameStopSnapshot(prior.copy(stopControl = TaxiStopControl.Legacy)))
        val legacy = ConsentFixture.orderJson("onboard").put("standing", true)
        assertNull(taxiStopEditReceipt(prior, requested, receipt(legacy)))
        assertFalse(prior.hasAdvancedTaxiStopEdit(ApiClient.parseTaxiConsentOrder(legacy)))
        val typedFresh = ApiClient.parseTaxiConsentOrder(changed)
        assertFalse(canResolveTaxiStopEdit(prior, ApiClient.parseTaxiConsentOrder(legacy), typedFresh, mustAdvance = true))
        assertFalse(canResolveTaxiStopEdit(prior, prior, prior, mustAdvance = true))
        assertTrue(canResolveTaxiStopEdit(prior, prior, typedFresh, mustAdvance = true))
        assertNull(taxiStopEditReceipt(prior, requested, receipt(changed).put("applied", false)))
    }

    @Test fun offResumeMayLoseCapabilityOnlyWithAReceiptAndIdleDriverRead() {
        val prior = ApiClient.parseTaxiConsentOrder(payload(enabled = false).put("role", "driver"))
        val idleWire = ConsentFixture.orderJson("onboard").put("standing", false).put("role", "driver")
        val idle = ApiClient.parseTaxiConsentOrder(idleWire)
        val resume = TaxiStopCommand.Resume(wait)
        assertFalse(taxiStopCommandObserved(prior, resume, idle, allowDrained = false))
        val receipt = JSONObject().put("ok", true).put("standing", false).put("order", idleWire)
        assertEquals(idle, taxiStopCommandReceipt(prior, resume, receipt))
        assertNull(taxiStopCommandReceipt(prior, TaxiStopCommand.Resume(id1), receipt))
        assertNull(taxiStopCommandReceipt(prior, TaxiStopCommand.Unplanned(wait), receipt))
    }

    @Test fun sameSelectedStopInDifferentDriverWaitEpisodesIsNotSameReceipt() {
        val active = ApiClient.parseTaxiConsentOrder(payload().put("role", "driver"))
        val rival = payload().put("role", "driver")
        rival.getJSONObject("stop_control").put("wait_revision", id1)
            .getJSONObject("active_wait").put("wait_id", id1)
        assertFalse(active.sameStopSnapshot(ApiClient.parseTaxiConsentOrder(rival)))
    }

    @Test fun commandsNameExactOwnerStopOrWaitAndPreserveJsonNull() {
        val start = TaxiStopCommand.Planned(null, revision, id3).body()!!
        assertEquals("start_planned", start.getString("action"))
        assertEquals(JSONObject.NULL, start.get("wait_revision"))
        assertEquals(revision, start.getString("route_revision"))
        assertEquals(id3, start.getString("waypoint_id"))
        val unplanned = TaxiStopCommand.Unplanned(wait).body()!!
        assertEquals("start_unplanned", unplanned.getString("action"))
        assertEquals(wait, unplanned.getString("wait_revision"))
        assertFalse(unplanned.has("waypoint_id"))
        val resumed = TaxiStopCommand.Resume(wait).body()!!
        assertEquals("resume", resumed.getString("action"))
        assertEquals(wait, resumed.getString("wait_id"))
        assertEquals(2, resumed.length())
        assertNull(TaxiStopCommand.LegacyToggle.body())
        val waypoints = stopsArray(listOf(TaxiStop(52.5, 58.1, "Synthetic", waypointId = id3)))
        assertEquals(setOf("lat", "lng", "text"), waypoints.getJSONObject(0).keys().asSequence().toSet())
    }
}
