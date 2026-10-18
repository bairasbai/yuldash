package com.yuldash.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.yuldash.app.data.parseBookingDetailsDto
import com.yuldash.app.data.parseBookingMineDto
import com.yuldash.app.data.parseDriverBookingDto
import com.yuldash.app.data.toRideDto
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h1000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PickupStopTest {
    @get:Rule val compose = createComposeRule()
    private fun ride(extra: String = "") = JSONObject("""{"id":7,"from_city":"Баймак","to_city":"Уфа","waypoints":"Акъяр | Сибай"$extra}""").toRideDto().toUiRide()

    @Test fun serverHintSurvivesUiMappingAndUnfilteredLookupDoesNotChooseStart() {
        assertNull(initialPickupStop(ride()))
        assertEquals("Акъяр", initialPickupStop(ride(",\"matched_stop\":\"Акъяр\"")))
        assertEquals("Баймак", initialPickupStop(ride(",\"matched_from_start\":true")))
        assertNull(initialPickupStop(ride(",\"matched_stop\":\"Уфа\"")))
        assertFalse(pickupStopReady(ride(), null))
        assertFalse(pickupStopReady(ride(), ""))
        assertFalse(pickupStopReady(ride(), "Уфа"))
        assertTrue(pickupStopReady(ride(), "Баймак"))
        assertTrue(pickupStopReady(ride(), "Сибай"))
        assertTrue(pickupStopReady(ride().copy(waypoints = emptyList()), null))
    }

    @Test fun privateBookingParsersKeepUnknownStartAndStopDistinct() {
        for (raw in listOf("null", "\"\"", "\"Акъяр\"")) {
            val obj = JSONObject("""{"pickup_stop":$raw,"ride_has_stops":true}""")
            val expected = if (raw == "null") null else obj.getString("pickup_stop")
            assertEquals(expected, parseBookingDetailsDto(obj).pickupStop)
            assertEquals(expected, parseBookingMineDto(obj).pickupStop)
            assertEquals(expected, parseDriverBookingDto(obj).pickupStop)
            assertTrue(parseBookingDetailsDto(obj).rideHasStops)
            assertTrue(parseBookingMineDto(obj).rideHasStops)
            assertTrue(parseDriverBookingDto(obj).rideHasStops)
        }
        assertNull(parseBookingDetailsDto(JSONObject()).pickupStop)
        assertNull(parseBookingMineDto(JSONObject()).pickupStop)
        assertNull(parseDriverBookingDto(JSONObject()).pickupStop)
    }

    @Test fun passengerExplicitlySelectsStartOrWaypoint() {
        val selected = mutableStateOf<String?>(null)
        compose.setContent { CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) { PickupStopPicker(ride(), selected.value, { selected.value = it }) } }
        compose.onNodeWithText("Выбери место посадки, чтобы забронировать.").assertExists()
        compose.onNodeWithText("Акъяр").performClick().assertIsSelected()
        assertEquals("Акъяр", selected.value)
        compose.onNodeWithText("Начало маршрута · Баймак").performClick().assertIsSelected()
        assertEquals("Баймак", selected.value)
    }

    @Test fun bashkirUnknownBookingDoesNotPretendToBeStart() {
        compose.setContent { CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) { PickupStopNote(null, true, "Баймак") } }
        compose.onNodeWithText("Ултырыу урыны күрһәтелмәгән — чатта асыҡла").assertExists()
        compose.onNodeWithText("Ултырыу: юлдың башы · Баймак").assertDoesNotExist()
    }
}
