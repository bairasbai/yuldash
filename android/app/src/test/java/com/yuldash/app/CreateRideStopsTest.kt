package com.yuldash.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** RS-02 Б: «Поделить по расстоянию» и проверка ввода цен остановок (та же таблица,
 *  что ждёт сервер: строгий рост, каждая меньше цены маршрута). */
class CreateRideStopsTest {

    @Test
    fun `деление пропорционально километрам, шаг 10 рублей`() {
        assertEquals(listOf(300), splitPriceByLegs(500, listOf(30.0, 20.0)))
        assertEquals(listOf(100, 200), splitPriceByLegs(500, listOf(10.0, 10.0, 30.0)))
    }

    @Test
    fun `незнакомые звенья делят поровну`() {
        assertEquals(listOf(250), splitPriceByLegs(500, listOf(null, null)))
    }

    @Test
    fun `сломанный рост чинится шагом в рубль`() {
        // 21 ₽ на три точки роста: округление до десятка даёт нули — рост важнее круглости.
        assertEquals(listOf(1, 10), splitPriceByLegs(21, listOf(1.0, 1.0, 1.0)))
    }

    @Test
    fun `не помещается — null (поля пустые, подсказка)`() {
        assertNull(splitPriceByLegs(2, listOf(1.0, 1.0, 1.0)))        // 2 ₽ на 2 остановки
        assertNull(splitPriceByLegs(500, listOf(1.0)))                 // без остановок нечего делить
        assertNull(splitPriceByLegs(500, emptyList()))
    }

    @Test
    fun `суммы участков из кумулятивных цен`() {
        assertEquals(listOf(300, 200), legPriceAmounts(listOf(300), 500))
        assertEquals(listOf(300, 100, 100), legPriceAmounts(listOf(300, 400), 500))
    }

    @Test
    fun `ввод цен — строгий рост и меньше маршрута`() {
        assertEquals(listOf(300), stopPricesInputValid(500, 1, listOf("300")))
        assertNull(stopPricesInputValid(500, 1, listOf("500")))        // ровно маршрут — нельзя
        assertNull(stopPricesInputValid(500, 2, listOf("300", "250"))) // не по росту
        assertNull(stopPricesInputValid(500, 2, listOf("300")))        // не все поля
        assertNull(stopPricesInputValid(500, 1, listOf("abc")))        // не число
        assertNull(stopPricesInputValid(500, 1, listOf("0")))          // ноль
        assertEquals(emptyList<Int>(), stopPricesInputValid(500, 1, emptyList())) // выключено
    }
}
