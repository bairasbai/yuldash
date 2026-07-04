package com.yuldash.app

import com.yandex.mapkit.geometry.Point
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Гео-хелперы карты (MapGeo.kt) — чистая математика/строки: город→точка, парс координат,
 * азимут/дистанция (haversine), остаток ETA, дистанция городов. Yandex [Point] требует Android
 * runtime → идём через Robolectric (без эмулятора). Эталонные расстояния/азимуты посчитаны
 * независимо (формулы haversine/great-circle) и сверены с допуском.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapGeoTest {

    // --- cityPoint: известный город → координаты, синонимы/регистр, неизвестный → null ---

    @Test
    fun cityPoint_knownCity_returnsCoordinates() {
        val ufa = cityPoint("Уфа")
        assertNotNull(ufa)
        assertEquals(54.7388, ufa!!.latitude, 1e-4)
        assertEquals(55.9721, ufa.longitude, 1e-4)
    }

    @Test
    fun cityPoint_baymakSynonymsAndCase_resolveToSamePoint() {
        // "Баймаҡ" (башк.) и "баймак" (рус.) — один город; трим/регистр не важны.
        val a = cityPoint("Баймаҡ")
        val b = cityPoint("  БАЙМАК  ")
        assertNotNull(a)
        assertNotNull(b)
        assertEquals(a!!.latitude, b!!.latitude, 1e-9)
        assertEquals(a.longitude, b.longitude, 1e-9)
    }

    @Test
    fun cityPoint_unknownCity_returnsNull() {
        assertNull(cityPoint("Париж"))
        assertNull(cityPoint(""))
    }

    // --- parseMapPoint: "lat,lng" → Point, мусор → null ---

    @Test
    fun parseMapPoint_validString_returnsPoint() {
        val p = parseMapPoint("53.9306, 58.3142")
        assertNotNull(p)
        assertEquals(53.9306, p!!.latitude, 1e-6)
        assertEquals(58.3142, p.longitude, 1e-6)
    }

    @Test
    fun parseMapPoint_trimsWhitespaceAroundNumbers() {
        val p = parseMapPoint("  52.5911 ,  58.3222 ")
        assertNotNull(p)
        assertEquals(52.5911, p!!.latitude, 1e-6)
        assertEquals(58.3222, p.longitude, 1e-6)
    }

    @Test
    fun parseMapPoint_garbageOrWrongArity_returnsNull() {
        assertNull(parseMapPoint("не координаты"))
        assertNull(parseMapPoint("53.9306"))            // одна часть
        assertNull(parseMapPoint("53.9, 58.3, 10.0"))   // три части
        assertNull(parseMapPoint("abc, 58.3"))          // нечисловая широта
        assertNull(parseMapPoint("53.9, xyz"))          // нечисловая долгота
        assertNull(parseMapPoint(""))
    }

    // --- bearingBetween: 0..360°, стороны света ---

    @Test
    fun bearingBetween_north_isZero() {
        // движение строго на север (та же долгота, широта растёт) → 0°.
        val b = bearingBetween(Point(50.0, 58.0), Point(51.0, 58.0))
        assertEquals(0.0, b, 1e-6)
    }

    @Test
    fun bearingBetween_east_isNinety() {
        // строго на восток (та же широта, долгота растёт) → ~90° (не ровно из-за сходимости меридианов).
        val b = bearingBetween(Point(50.0, 58.0), Point(50.0, 59.0))
        assertEquals(90.0, b, 0.5)
    }

    @Test
    fun bearingBetween_south_isOneEighty() {
        val b = bearingBetween(Point(51.0, 58.0), Point(50.0, 58.0))
        assertEquals(180.0, b, 1e-6)
    }

    @Test
    fun bearingBetween_west_isTwoSeventy() {
        val b = bearingBetween(Point(50.0, 59.0), Point(50.0, 58.0))
        assertEquals(270.0, b, 0.5)
    }

    @Test
    fun bearingBetween_alwaysInRange0To360() {
        val b = bearingBetween(Point(52.5911, 58.3222), Point(52.7236, 58.6651))
        assertTrue("bearing $b out of [0,360)", b in 0.0..360.0)
        assertEquals(57.4, b, 0.5) // Баймаҡ→Сибай, северо-восток
    }

    // --- geoMeters: haversine в метрах, известные расстояния ---

    @Test
    fun geoMeters_samePoint_isZero() {
        val p = Point(52.5911, 58.3222)
        assertEquals(0.0, geoMeters(p, p), 1e-6)
    }

    @Test
    fun geoMeters_oneDegreeLatitude_isAbout111km() {
        // 1° широты ≈ 111.19 км по большому кругу (радиус 6371 км).
        val m = geoMeters(Point(0.0, 0.0), Point(1.0, 0.0))
        assertEquals(111195.0, m, 50.0)
    }

    @Test
    fun geoMeters_baymakToSibay_matchesReference() {
        // Эталон посчитан независимо: ≈ 27422 м.
        val m = geoMeters(Point(52.5911, 58.3222), Point(52.7236, 58.6651))
        assertEquals(27422.0, m, 100.0)
    }

    @Test
    fun geoMeters_isSymmetric() {
        val a = Point(54.7388, 55.9721)
        val b = Point(52.7236, 58.6651)
        assertEquals(geoMeters(a, b), geoMeters(b, a), 1e-3)
    }

    // --- remainingEtaSec: доля непройденного пути × полное время ---

    @Test
    fun remainingEtaSec_atStart_isFullTime() {
        // pos == первая точка (bestI=0) → остаток = total * (n-0)/n = total.
        val path = listOf(Point(0.0, 0.0), Point(0.0, 1.0), Point(0.0, 2.0), Point(0.0, 3.0))
        assertEquals(600, remainingEtaSec(path, Point(0.0, 0.0), 600.0))
    }

    @Test
    fun remainingEtaSec_atEnd_isNearZero() {
        // pos == последняя точка (bestI=n-1) → остаток = total * 1/n (одна точка «осталась»).
        val path = listOf(Point(0.0, 0.0), Point(0.0, 1.0), Point(0.0, 2.0), Point(0.0, 3.0))
        // n=4, total=600 → 600*1/4=150
        assertEquals(150, remainingEtaSec(path, Point(0.0, 3.0), 600.0))
    }

    @Test
    fun remainingEtaSec_midway_isRoughlyHalf() {
        // pos у 3-й из 4 точек (индекс 2) → 600 * (4-2)/4 = 300.
        val path = listOf(Point(0.0, 0.0), Point(0.0, 1.0), Point(0.0, 2.0), Point(0.0, 3.0))
        assertEquals(300, remainingEtaSec(path, Point(0.0, 2.0), 600.0))
    }

    @Test
    fun remainingEtaSec_degenerateInputs_returnZero() {
        val single = listOf(Point(0.0, 0.0))
        assertEquals(0, remainingEtaSec(single, Point(0.0, 0.0), 600.0)) // path.size < 2
        val path = listOf(Point(0.0, 0.0), Point(0.0, 1.0))
        assertEquals(0, remainingEtaSec(path, Point(0.0, 0.0), 0.0))     // totalSec <= 0
        assertEquals(0, remainingEtaSec(emptyList(), Point(0.0, 0.0), 600.0))
    }

    // --- cityDistanceText: "X км" по координатам, null для неизвестного города ---

    @Test
    fun cityDistanceText_knownCities_formatsKm() {
        // Баймаҡ→Сибай ≈ 27 км (округление до целого).
        assertEquals("27 км", cityDistanceText("Баймаҡ", "Сибай"))
    }

    @Test
    fun cityDistanceText_longDistance_matchesReference() {
        // Уфа→Сибай ≈ 286 км.
        assertEquals("286 км", cityDistanceText("Уфа", "Сибай"))
    }

    @Test
    fun cityDistanceText_unknownCity_returnsNull() {
        assertNull(cityDistanceText("Париж", "Сибай"))
        assertNull(cityDistanceText("Уфа", "Лондон"))
    }
}
