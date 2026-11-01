package com.yuldash.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Сторож RS-02 А: решение «можно бронировать» везде считает вместимость ВЫБРАННОГО участка
 * через `bookableSeats`, а не по `seats` всего пути.
 *
 * Поймано на эмуляторной матрице §8.3 (01.11.2026): рейс Сибай→Акъяр, трое заняли
 * Сибай→Ургазу (по всему пути seats=0), Булат выбрал Ургаза→Акъяр (3 места свободно),
 * кнопка «Забронировать 2 места» была доступна (экран смотрел участок), а нажатие молча
 * ничего не делало: навигационные ворота `YuldashApp.onConfirmRide` читали
 * `selectedSeats in 1..displayedRide.seats` → `2 in 1..0`. Два места одного правила —
 * экран и ворота — держатся одной функцией `bookableSeats`.
 */
class SegmentBookingGateGuardTest {

    private val app = File("src/main/java/com/yuldash/app/YuldashApp.kt").readText()
    private val screen = File("src/main/java/com/yuldash/app/BookingActiveTripScreen.kt").readText()

    @Test
    fun `ворота брони считают места по участку`() {
        assertTrue(
            "onConfirmRide должен брать вместимость выбранного участка через bookableSeats",
            app.contains("selectedSeats in 1..bookableSeats(displayedRide, pickupStop, dropoffStop)"),
        )
    }

    @Test
    fun `сырая проверка по местам всего пути не возвращается`() {
        assertFalse(
            "мутация: ворота по seats всего пути молча глушат бронь свободного участка",
            app.contains("selectedSeats in 1..displayedRide.seats"),
        )
    }

    @Test
    fun `экран брони делит правило с воротами`() {
        assertTrue(
            "BookingActiveTripScreen считает segmentSeats тем же bookableSeats",
            screen.contains("val segmentSeats = bookableSeats(displayRide, pickupStop, dropoffStop)"),
        )
    }
}
