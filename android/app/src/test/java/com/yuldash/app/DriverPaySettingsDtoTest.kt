package com.yuldash.app

import com.yuldash.app.data.parseBookingDetailsDto
import com.yuldash.app.data.toDriverPaySettings
import com.yuldash.app.data.toRideDto
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriverPaySettingsDtoTest {
    @Test
    fun choicesParseConsistentlyAcrossSettingsRidesAndBookings() {
        val values = """["sbp","cash","cash","card"]"""
        val view = JSONObject("""{"methods":$values,"updated_at":"2030-01-02T10:00:00Z"}""").toDriverPaySettings()
        val ride = JSONObject("""{"driver_pay_methods":$values}""").toRideDto().toUiRide()
        val booking = parseBookingDetailsDto(JSONObject("""{"driver_pay_methods":$values}"""))
        assertEquals(listOf("cash", "sbp"), view.methods)
        assertEquals("2030-01-02T10:00:00Z", view.updatedAt)
        assertEquals(view.methods, ride.driverPayMethods)
        assertEquals(view.methods, booking.driverPayMethods)
    }

    @Test
    fun legacyAndEmptyViewsAllowBothMethodsAndAnAgreement() {
        val empty = JSONObject("""{"methods":[],"updated_at":null}""").toDriverPaySettings()
        assertNull(empty.updatedAt)
        assertEquals(listOf("cash", "sbp", "negotiate"), passengerPayChoices(empty.methods))
        assertEquals(listOf("cash", "sbp", "negotiate"), passengerPayChoices(JSONObject().toRideDto().driverPayMethods))
        assertEquals(listOf("sbp", "negotiate"), passengerPayChoices(listOf("sbp")))
    }

    @Test
    fun guestBookingReviewsAMethodTheDriverRemoved() {
        val ride = JSONObject("""{"id":42,"from_city":"A","to_city":"B","driver_pay_methods":["cash"]}""")
            .toRideDto().toUiRide()
        val draft = GuestBookingDraft(42, ride.termsVersion, routePlaces(ride),
            initialPickupStop(ride), initialDropoffStop(ride), 1, front = false, minor = false, payMethod = "cash")
        val current = draft.restore(ride)
        assertEquals("cash", current.payMethod)
        assertNull(current.review)
        val updated = draft.restore(ride.copy(driverPayMethods = listOf("sbp")))
        assertEquals("negotiate", updated.payMethod)
        assertTrue(requireNotNull(updated.review).payMethodUnavailable)
    }
}
