package com.yuldash.app

import com.yuldash.app.data.RideReturnTemplate
import java.time.LocalDate
import java.time.LocalDateTime
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RideTemplateTest {
    private fun fixture() = JSONObject(
        """
        {
          "from_city":"Акъяр", "to_city":"Сибай", "waypoints":"Бурибай | Ургаза",
          "seats_total":2, "price":500, "stop_prices":[100,200], "category":"regular",
          "pets_allowed":true, "child_seat":true, "women_only":true, "smoking":true,
          "baggage":true, "air_conditioner":true, "only_trusted":true, "quiet":true,
          "no_minors":true, "back_max_two":true, "front_seat_available":false,
          "dropped":["depart_at","pickup","comment"], "suggested_date":null
        }
        """.trimIndent()
    )

    @Test fun restoresReverseStopsPricesFlagsAndBlankDeparture() {
        val json = RideReturnTemplate.parse(fixture()).savedJson()
        val draft = RideCreatePrefill.restore(json)
        val template = checkNotNull(draft.template)
        assertNull(draft.date)
        assertEquals("Акъяр", template.fromCity)
        assertEquals("Сибай", template.toCity)
        assertEquals(listOf("Бурибай", "Ургаза"), template.waypoints)
        assertEquals(2, template.seatsTotal)
        assertEquals(500, template.price)
        assertEquals(listOf(100, 200), template.stopPrices)
        assertTrue(template.onlyTrusted && template.womenOnly && template.backMaxTwo)
        assertFalse(template.frontSeatAvailable)
        assertNotEquals(createRideStateKey(7, null), createRideStateKey(7, json))
        assertNotEquals(createRideStateKey(7, json), createRideStateKey(8, json))
    }

    @Test fun negotiatedPriceAndLegacyHolidayKeepTheirOwnMeaning() {
        val template = RideReturnTemplate.parse(fixture().put("price", 0).put("stop_prices", JSONArray()))
        assertEquals(0, template.price)
        assertTrue(template.stopPrices.isEmpty())
        val holiday = RideCreatePrefill.restore("02.01.2030, 10:00")
        assertEquals("02.01.2030, 10:00", holiday.date)
        assertNull(holiday.template)
        assertNull(RideCreatePrefill.restore("{broken").date)
    }

    @Test fun suggestedDayStartsPickerWithoutWritingDeparture() {
        val template = RideReturnTemplate.parse(fixture().put("suggested_date", "2030-04-17"))
        val draft = RideCreatePrefill.restore(template.savedJson())
        assertNull(draft.date)
        val now = LocalDateTime.of(2030, 4, 15, 14, 0)
        assertEquals(LocalDate.of(2030, 4, 17), initialRideDeparture("", now, template.suggestedDate).toLocalDate())
        val typed = "16.04.2030, 09:30"
        assertEquals(LocalDateTime.of(2030, 4, 16, 9, 30), initialRideDeparture(typed, now, template.suggestedDate))
    }

    @Test fun parserRejectsWrongNumberOrOrderOfStopPrices() {
        for (prices in listOf("[100]", "[200,100]", "[100,500]")) {
            assertThrows(IllegalArgumentException::class.java) {
                RideReturnTemplate.parse(fixture().put("stop_prices", JSONArray(prices)))
            }
        }
    }
}
