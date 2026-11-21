package com.yuldash.app.data

import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class RideSeriesIntentTest {
    private val draft = JSONObject()
        .put("from_city", "Уфа")
        .put("to_city", "Сибай")
        .put("price", 0)
        .put("depart_at", "ignored")
    private val early = "2030-11-01T05:00:00Z"
    private val late = "2030-11-03T05:00:00Z"
    private val baseKey = "cmd1_test-series-key"

    @Test
    fun offsetVariantsUseTheSameUtcKeyAndPwaFingerprint() {
        val intent = RideSeriesIntent.create(draft, listOf(late, "2030-11-01T10:00:55+05:00", early), baseKey)
        assertEquals(listOf(early, late), intent.dates.map { it.departureUtc })
        val expectedEnvelope = """{"body":{"depart_at":"2030-11-01T05:00:00Z","ride":{"from_city":"Уфа","price":0,"to_city":"Сибай"}},"scope":"ride.create","target_id":null}"""
        val expectedHash = MessageDigest.getInstance("SHA-256")
            .digest(expectedEnvelope.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val first = intent.dates.first()
        assertEquals("cmd1_test-series-key-203011010500", first.commandKey)
        assertEquals(expectedHash, first.payloadHash)
        assertFalse(intent.requestBody().getJSONObject("ride").has("depart_at"))
        assertEquals(0, intent.requestBody().getJSONObject("ride").getInt("price"))
    }

    @Test
    fun repeatOfOneDateStaysInTheOriginalSeries() {
        val intent = RideSeriesIntent.create(draft, listOf(early, late), baseKey)
        val date = intent.dates.last()
        val subset = intent.requestBody(listOf(date))
        assertEquals(1, subset.getJSONArray("departures").length())
        assertEquals(late, subset.getJSONArray("departures").getString(0))
        assertEquals(date, RideSeriesIntent.create(draft, listOf(late), baseKey).dates.single())
        assertThrows(IllegalArgumentException::class.java) {
            intent.requestBody(listOf(date.copy(commandKey = "some other series")))
        }
    }

    @Test
    fun boundsMatchThePublicationEndpoint() {
        assertThrows(IllegalArgumentException::class.java) {
            RideSeriesIntent.create(draft, List(11) { early }, baseKey)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RideSeriesIntent.create(draft, listOf(early), "x".repeat(116))
        }
    }
}
