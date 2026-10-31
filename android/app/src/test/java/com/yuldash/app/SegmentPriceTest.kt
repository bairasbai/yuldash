package com.yuldash.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** RS-02 А/Б: цена и свободные места участка на клиенте — та же таблица, что у сервера
 *  (`backend/tests/test_segment_price.py::test_segment_price_math`). Сибай → Ургаза →
 *  Бурибай → Акъяр за 500 ₽, цены до остановок [300, 400]. */
class SegmentPriceTest {

    private fun ride(
        stops: List<String> = listOf("Ургаза", "Бурибай"),
        prices: List<Int> = listOf(300, 400),
        legs: List<Int> = emptyList(),
        full: Int = 500,
    ) = Ride(
        id = "1", from = "Сибай", to = "Акъяр", time = "", driver = "", car = "",
        price = full, seats = 3, rating = 5.0, verified = false, boosted = false,
        waypoints = stops,
        stopPrices = stops.zip(prices).map { (s, p) -> StopPrice(s, p) },
        seatsByLeg = legs,
    )

    @Test
    fun `без цен остановок — полная цена за любой участок`() {
        val r = ride(prices = emptyList())
        assertEquals(500, segmentPriceRub(r, "Ургаза", "Акъяр"))
        assertEquals(500, segmentPriceRub(r, "Сибай", "Ургаза"))
    }

    @Test
    fun `цены по частям маршрута — cum(высадка) минус cum(посадка)`() {
        val r = ride()
        assertEquals(300, segmentPriceRub(r, "Сибай", "Ургаза"))
        assertEquals(200, segmentPriceRub(r, "Ургаза", "Акъяр"))
        assertEquals(100, segmentPriceRub(r, "Ургаза", "Бурибай"))
        assertEquals(500, segmentPriceRub(r, "Сибай", "Акъяр"))
    }

    @Test
    fun `незнакомые и пустые концы — края маршрута`() {
        val r = ride()
        assertEquals(500, segmentPriceRub(r, null, null))
        assertEquals(200, segmentPriceRub(r, "Ургаза", null))
        assertEquals(300, segmentPriceRub(r, null, "Ургаза"))
    }

    @Test
    fun `длина цен не сошлась с остановками — полная цена, не выдумываем`() {
        val r = ride(prices = listOf(300))
        assertEquals(500, segmentPriceRub(r, "Ургаза", "Акъяр"))
    }

    @Test
    fun `места на участке — минимум по его звеньям`() {
        val r = ride(legs = listOf(0, 3, 3))
        assertEquals(0, seatsOnSpan(r, "Сибай", "Ургаза"))
        assertEquals(3, seatsOnSpan(r, "Ургаза", "Акъяр"))
        assertEquals(0, seatsOnSpan(r, "Сибай", "Акъяр"))
    }

    @Test
    fun `места — список пуст или не сошёлся, тогда null`() {
        assertNull(seatsOnSpan(ride(), "Сибай", "Ургаза"))
        assertNull(seatsOnSpan(ride(legs = listOf(1, 2)), "Сибай", "Ургаза"))
    }
}
