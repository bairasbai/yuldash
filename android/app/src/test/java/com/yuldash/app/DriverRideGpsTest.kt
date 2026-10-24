package com.yuldash.app

import androidx.lifecycle.SavedStateHandle
import com.yuldash.app.data.parseDriverBookingDto
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Д2 (24.10.2026): через чей канал идёт GPS водителя, когда несущая бронь закрылась, и куда водитель
 * попадает после «Завершить». Правило выбора — общий с сайтом набор случаев
 * (`src/test/resources/driver-ride-carrier-cases.json`, его же читает webapp/tests/driver-ride-share-test.mjs):
 * правило живёт в двух клиентах и не должно разойтись (P2-5 проверки плана). Строки разбирает настоящий
 * разбор ответа `/driver/bookings` — `parseDriverBookingDto`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DriverRideGpsTest {

    private val cases = JSONObject(
        requireNotNull(javaClass.classLoader?.getResource("driver-ride-carrier-cases.json")) { "нет набора случаев" }.readText()
    ).getJSONArray("cases")

    @Test fun `набор случаев на месте и не пустой`() {
        assertTrue("набор случаев выбора несущей пуст — сторож ослеп", cases.length() >= 10)
    }

    @Test fun `каждый случай общего набора — как ждёт сайт`() {
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val name = c.getString("name")
            val rows = c.getJSONArray("rows").let { a -> (0 until a.length()).map { parseDriverBookingDto(a.getJSONObject(it)) } }
            val closed = c.getJSONArray("closed").let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() }
            val pick = pickRideCarrier(rows, c.getInt("anchor"), closed)
            val e = c.getJSONObject("expect")
            assertEquals("$name: рейс", e.getInt("ride_id"), pick.rideId)
            val live = e.getJSONArray("live").let { a -> (0 until a.length()).map { a.getInt(it) } }
            assertEquals("$name: живые брони для «Едут с тобой»", live, pick.liveBookings)
            assertEquals("$name: несущая", if (e.isNull("carrier")) null else e.getInt("carrier"), pick.carrier)
            assertEquals("$name: срок несущей", if (e.isNull("carrier_ttl_sec")) null else e.getInt("carrier_ttl_sec"), pick.carrierTtlSec)
        }
    }

    @Test fun `режим рейса кончается через 6 часов по любым из двух часов`() {
        val mode = DriverRideMode.start(7, setOf(1, 2), nowElapsedMs = 1_000L, nowWallMs = 50_000L)
        assertFalse(mode.expired(1_000L + DRIVER_RIDE_MAX_MS - 1, 50_000L))
        assertTrue("по монотонным часам", mode.expired(1_000L + DRIVER_RIDE_MAX_MS, 50_000L))
        assertTrue("по стенным часам (перевели вперёд)", mode.expired(2_000L, 50_000L + DRIVER_RIDE_MAX_MS))
        assertTrue("часы загрузки сбились — не верим сроку дальше 6 часов", mode.expired(0L, 50_000L))
    }

    @Test fun `закрытую поездку заменяет «Едут с тобой», «Назад» в неё не ведёт`() {
        val vm = YuldashViewModel(SavedStateHandle())
        vm.navHistory.clear()
        vm.navHistory.addAll(listOf(Screen.Home, Screen.DriverCabinet))
        vm.activeTrip.value = bookingLinkStubRide(42)
        vm.screen.value = Screen.ActiveTrip

        vm.returnToDriverRide()

        assertEquals(Screen.DriverCabinet, vm.screen.value)
        assertEquals("в трейле не осталось ни закрытой поездки, ни второго кабинета", listOf(Screen.Home), vm.navHistory.toList())
        assertNull("маршрут закрытой брони не висит на карте", vm.activeTrip.value)
        assertTrue("кабинет должен доехать до «Едут с тобой»", vm.focusRidingSection.value)
        assertTrue("переход не записывается в историю как шаг вперёд", vm.navPopping.value)
    }
}
