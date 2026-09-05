package com.yuldash.app

import com.yandex.mapkit.geometry.Point
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapLogicTest {

    @Test
    fun parseMapPoint_acceptsCommaSeparatedLatLonWithSpaces() {
        val point = parseMapPoint("53.9306, 58.3142")

        assertNotNull(point)
        assertEquals(53.9306, point!!.latitude, 0.00001)
        assertEquals(58.3142, point.longitude, 0.00001)
    }

    @Test
    fun parseMapPoint_rejectsBrokenCoordinatesWithoutCrash() {
        assertNull(parseMapPoint(""))
        assertNull(parseMapPoint("53.9306"))
        assertNull(parseMapPoint("53.9306, 58.3142, 1"))
        assertNull(parseMapPoint("lat, lon"))
    }

    @Test
    fun cityDistanceText_returnsKnownDistanceForSupportedCities() {
        val distance = cityDistanceText("Баймаҡ", "Сибай")

        assertNotNull(distance)
        assertTrue(distance!!.endsWith("км"))
    }

    @Test
    fun cityDistanceText_returnsNullForUnknownCities() {
        assertNull(cityDistanceText("Unknown", "Сибай"))
        assertNull(cityDistanceText("Баймаҡ", "Unknown"))
    }

    @Test
    fun remainingEtaSec_returnsZeroForInvalidInputs() {
        assertEquals(0, remainingEtaSec(emptyList(), Point(0.0, 0.0), 100.0))
        assertEquals(0, remainingEtaSec(listOf(Point(0.0, 0.0)), Point(0.0, 0.0), 100.0))
        assertEquals(0, remainingEtaSec(listOf(Point(0.0, 0.0), Point(1.0, 1.0)), Point(0.0, 0.0), 0.0))
    }

    @Test
    fun remainingEtaSec_decreasesAlongRoute() {
        val path = listOf(
            Point(0.0, 0.0),
            Point(0.0, 1.0),
            Point(0.0, 2.0),
            Point(0.0, 3.0),
        )

        val atStart = remainingEtaSec(path, Point(0.0, 0.0), 400.0)
        val nearEnd = remainingEtaSec(path, Point(0.0, 2.9), 400.0)

        assertEquals(400, atStart)
        assertEquals(100, nearEnd)
        assertTrue(nearEnd < atStart)
    }

    @Test
    fun sberPayLink_keepsOnlyDigitsInPhone() {
        assertEquals(
            "https://www.sberbank.com/sms/pbpn?requisiteNumber=79991348275",
            sberPayLink("+7 (999) 134-82-75"),
        )
    }
}
