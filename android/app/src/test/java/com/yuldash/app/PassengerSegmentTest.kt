package com.yuldash.app

import com.yuldash.app.data.TripPass
import com.yuldash.app.data.parseBookingDetailsDto
import com.yuldash.app.data.parseBookingMineDto
import com.yuldash.app.data.parseDriverBookingDto
import com.yuldash.app.data.toRideDto
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PassengerSegmentTest {
    private fun ride(extra: String = "") = JSONObject("""{"id":7,"from_city":"Сибай","to_city":"Акъяр","waypoints":"Ургаза | Бурибай"$extra}""").toRideDto().toUiRide()

    @Test fun bothServerHintsSurviveMappingAndSameRideDetailsReload() {
        val hinted = ride(""","matched_stop":"Ургаза","matched_dropoff_stop":"Бурибай"""")
        val reloaded = ride().withRouteHints(hinted)
        assertEquals("Ургаза", initialPickupStop(reloaded))
        assertEquals("Бурибай", initialDropoffStop(reloaded))
        assertTrue(segmentReady(reloaded, initialPickupStop(reloaded), initialDropoffStop(reloaded)))
        assertNull(initialPickupStop(ride().copy(id = "8").withRouteHints(hinted)))
        assertNull(initialDropoffStop(ride().copy(id = "8").withRouteHints(hinted)))
    }

    @Test fun endpointHintsAreExplicitAndUnfilteredStopsRequireBothChoices() {
        val endpoints = ride(""","matched_from_start":true,"matched_to_end":true""")
        assertEquals("Сибай", initialPickupStop(endpoints)); assertEquals("Акъяр", initialDropoffStop(endpoints))
        assertNull(initialPickupStop(ride())); assertNull(initialDropoffStop(ride()))
        val direct = ride().copy(waypoints = emptyList())
        assertEquals("Сибай", initialPickupStop(direct)); assertEquals("Акъяр", initialDropoffStop(direct))
        assertTrue(segmentReady(direct, initialPickupStop(direct), initialDropoffStop(direct)))
    }

    @Test fun onlyPublishedForwardSegmentsCanBeBookedAndChangingBoardingResetsAlighting() {
        val r = ride()
        assertEquals(listOf("Бурибай", "Акъяр"), dropoffStops(r, "Ургаза"))
        assertTrue(segmentReady(r, "Ургаза", "Бурибай"))
        for ((pickup, dropoff) in listOf("Бурибай" to "Ургаза", "Ургаза" to "Ургаза", "Акъяр" to "Бурибай", "Уфа" to "Акъяр"))
            assertFalse(segmentReady(r, pickup, dropoff))
        assertFalse(segmentReady(r, "Ургаза", null))
        assertNull(keepValidDropoff(r, "Бурибай", "Бурибай"))
        assertEquals("Акъяр", keepValidDropoff(r, "Бурибай", "Акъяр"))
        assertNull(initialDropoffStop(ride(""","matched_stop":"Бурибай","matched_dropoff_stop":"Ургаза"""")))
    }

    @Test fun privateDtosAndOfflineSnapshotsDistinguishUnknownEndAndIntermediateStop() {
        for (raw in listOf("null", "\"\"", "\"Бурибай\"")) {
            val o = JSONObject("""{"dropoff_stop":$raw,"pickup_stop":"Ургаза","ride_has_stops":true}""")
            val expected = if (raw == "null") null else o.getString("dropoff_stop")
            assertEquals(expected, parseBookingMineDto(o).dropoffStop)
            assertEquals(expected, parseBookingDetailsDto(o).dropoffStop)
            assertEquals(expected, parseDriverBookingDto(o).dropoffStop)
            val restored = TripPass.fromJson(o)
            assertEquals(expected, restored.dropoffStop)
            assertEquals(expected, TripPass.fromJson(restored.toJson()).dropoffStop)
            assertEquals("Ургаза", restored.pickupStop)
        }
        assertNull(TripPass.fromJson(JSONObject()).dropoffStop)
        assertNull(parseBookingMineDto(JSONObject()).dropoffStop)
    }
}
