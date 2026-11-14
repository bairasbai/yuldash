package com.yuldash.app

import com.yuldash.app.data.NearbyPage
import com.yuldash.app.data.parseNearbyPage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NearbySeatsTest {
    @Test fun suggestionUsesOnlyTheCountForTheBestRemainingSize() {
        val response = JSONObject("""{"count":0,"hidden_by_seats":5,"max_free_seats":3,"count_at_max_free":2}""")
        val page = parseNearbyPage(response, emptyList())
        assertEquals(SmallerNearbyGroup(seats = 3, rides = 2), page.smallerGroup(4))
        assertNull(page.smallerGroup(3))
        assertNull(page.copy(countAtMaxFree = 6).smallerGroup(4))
        assertNull(parseNearbyPage(JSONObject("{}"), emptyList()).smallerGroup(4))
    }

    @Test fun groupSizeAndOwnerInvalidateExistingResults() {
        fun key(seats: Int, owner: Long) =
            nearbySearch("Ургаза", "Акъяр", null, emptySet(), emptySet(), emptySet(), true, seats, owner)

        assertNotEquals(key(3, 10L), key(2, 10L))
        assertNotEquals(key(3, 10L), key(3, 11L))
        assertEquals(1..8, NearbyGroupSizes)
    }
}
