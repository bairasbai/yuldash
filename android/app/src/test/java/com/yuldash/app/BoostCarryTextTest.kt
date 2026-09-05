package com.yuldash.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Остаток перенесённого поднятия человеческими словами.
 *
 * Приёмка 2026-08-31 поймала на живом экране: 5,5 часа показывались как «5 ч» — водитель
 * видел меньше, чем у него есть. Обмана нет (никогда не завышаем), но полчаса пропадали
 * из виду. Теперь минуты не теряются.
 *
 * Две функции, а не одна: единица измерения — тоже надпись, и по-башкирски она своя.
 */
class BoostCarryTextTest {

    @Test
    fun `целые часы без минут`() {
        assertEquals("6 ч", boostCarryRu(6 * 3600))
        assertEquals("6 сәғәт", boostCarryBa(6 * 3600))
    }

    @Test
    fun `часы с минутами не теряют остаток`() {
        assertEquals("5 ч 30 мин", boostCarryRu(5 * 3600 + 1800))
        assertEquals("5 сәғәт 30 минут", boostCarryBa(5 * 3600 + 1800))
    }

    @Test
    fun `меньше часа — только минуты`() {
        assertEquals("45 мин", boostCarryRu(45 * 60))
        assertEquals("45 минут", boostCarryBa(45 * 60))
    }

    @Test
    fun `совсем короткий остаток не превращается в ноль`() {
        // Ноль минут выглядит как ошибка: человек решит, что поднятие пропало.
        assertEquals("1 мин", boostCarryRu(20))
        assertEquals("1 минут", boostCarryBa(20))
    }
}
