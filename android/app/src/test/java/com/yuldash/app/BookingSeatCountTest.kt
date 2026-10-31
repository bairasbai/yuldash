package com.yuldash.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** RS-02 В: правила кнопки брони. Главное — намерение группы: мест стало меньше,
 *  чем выбрано, — отправка БЛОКИРУЕТСЯ (без молчаливого clamp), человек сам жмёт
 *  «Уменьшить до K» или выбирает другую поездку. */
class BookingSeatCountTest {

    @Test
    fun `выбранное в пределах доступного — можно`() {
        assertTrue(canBookSeats(available = 3, selected = 1, busy = false))
        assertTrue(canBookSeats(available = 3, selected = 3, busy = false))
    }

    @Test
    fun `мест стало меньше выбранного — отправка заблокирована, а не clamp`() {
        assertFalse(canBookSeats(available = 2, selected = 3, busy = false))
    }

    @Test
    fun `ноль доступного или занятая отправка — нельзя`() {
        assertFalse(canBookSeats(available = 0, selected = 1, busy = false))
        assertFalse(canBookSeats(available = 3, selected = 1, busy = true))
    }
}
