package com.yuldash.app

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Куда ведёт пуш, ссылка и карточка уведомления про бронь (запуск волнами, 16.10.2026).
 *
 * Было: водитель, тапнув «новая бронь», попадал на пассажирскую «Бронь» — там «Ждём ответа
 * водителя» и красная «Отменить бронь», а «Подтвердить» нет. Теперь водитель с ожидающей
 * бронью идёт в кабинет (там «Ждут подтверждения»), с подтверждённой — сразу на экран поездки
 * («Я выехал / Подъезжаю»). Пассажир ведёт себя как раньше.
 *
 * `YuldashApp` после ответа сервера зовёт ровно `YuldashViewModel.applyBookingLink` — её и проверяем.
 */
class BookingLinkDestinationTest {

    private fun newVm() = YuldashViewModel(SavedStateHandle())

    private val earlier = Ride(
        id = "5", from = "Уфа", to = "Сибай", time = "", driver = "Айрат", car = "",
        price = 500, seats = 2, rating = 4.9, verified = true, boosted = false,
    )

    // ── Решение по роли и статусу ──

    @Test
    fun `водитель с ожидающей бронью не попадает на пассажирскую Бронь`() {
        assertNotEquals(Screen.Booking, bookingLinkScreen("driver", "pending", completed = false))
        assertEquals(Screen.DriverCabinet, bookingLinkScreen("driver", "pending", completed = false))
    }

    @Test
    fun `водитель с подтверждённой или идущей бронью сразу на экран поездки`() {
        assertEquals(Screen.ActiveTrip, bookingLinkScreen("driver", "confirmed", completed = false))
        assertEquals(Screen.ActiveTrip, bookingLinkScreen("driver", "onboard", completed = false))
    }

    @Test
    fun `водитель с отменённой бронью идёт в кабинет а не на Бронь`() {
        assertEquals(Screen.DriverCabinet, bookingLinkScreen("driver", "cancelled", completed = false))
        assertEquals(Screen.DriverCabinet, bookingLinkScreen("driver", "", completed = false))
    }

    @Test
    fun `водитель с завершённой бронью видит итог на экране поездки`() {
        assertEquals(Screen.ActiveTrip, bookingLinkScreen("driver", "done", completed = false))
        assertEquals(Screen.ActiveTrip, bookingLinkScreen("driver", "done", completed = true))
    }

    @Test
    fun `пассажирский путь не изменился`() {
        for (status in listOf("pending", "confirmed", "onboard", "cancelled", "done", "")) {
            assertEquals("пассажир, статус «$status»", Screen.Booking, bookingLinkScreen("passenger", status, completed = false))
        }
        for (status in listOf("pending", "confirmed", "onboard", "done")) {
            assertEquals("пассажир, завершённая, статус «$status»", Screen.ActiveTrip, bookingLinkScreen("passenger", status, completed = true))
        }
    }

    // ── Что происходит с состоянием приложения ──

    @Test
    fun `пуш водителю про ожидающую бронь открывает кабинет и не трогает бронь пассажира`() {
        val vm = newVm()
        vm.selectedRide.value = earlier
        vm.activeBookingId.value = 5
        val target = vm.applyBookingLink(43, "driver", "pending", completed = false)
        assertEquals(Screen.DriverCabinet, target)
        assertEquals(Screen.DriverCabinet, vm.screen.value)
        assertNotEquals(Screen.Booking, vm.screen.value)
        assertSame(earlier, vm.selectedRide.value)
        assertEquals(5, vm.activeBookingId.value)
    }

    @Test
    fun `пуш водителю про подтверждённую бронь открывает экран поездки с живым гео`() {
        val vm = newVm()
        val target = vm.applyBookingLink(43, "driver", "confirmed", completed = false)
        assertEquals(Screen.ActiveTrip, target)
        assertEquals(Screen.ActiveTrip, vm.screen.value)
        assertEquals(43, vm.activeBookingId.value)
        assertEquals("43", vm.selectedRide.value?.id)
        assertNotNull("живое гео водителя выключено", vm.activeTrip.value)
    }

    @Test
    fun `завершённая бронь водителя открывает итог без живого гео`() {
        val vm = newVm()
        vm.activeTrip.value = earlier
        vm.applyBookingLink(43, "driver", "done", completed = true)
        assertEquals(Screen.ActiveTrip, vm.screen.value)
        assertEquals(43, vm.activeBookingId.value)
        assertNull(vm.activeTrip.value)
    }

    @Test
    fun `пассажир по-прежнему попадает на Бронь и чужое живое гео не трогаем`() {
        val vm = newVm()
        vm.activeTrip.value = earlier
        val target = vm.applyBookingLink(43, "passenger", "pending", completed = false)
        assertEquals(Screen.Booking, target)
        assertEquals(Screen.Booking, vm.screen.value)
        assertEquals(43, vm.activeBookingId.value)
        assertEquals("43", vm.selectedRide.value?.id)
        assertSame(earlier, vm.activeTrip.value)
    }

    @Test
    fun `завершённая бронь пассажира открывает итог и гасит живое гео`() {
        val vm = newVm()
        vm.activeTrip.value = earlier
        vm.applyBookingLink(43, "passenger", "done", completed = true)
        assertEquals(Screen.ActiveTrip, vm.screen.value)
        assertNull(vm.activeTrip.value)
    }

    @Test
    fun `кнопка Открыть поездку в кабинете ведёт на экран поездки этой брони`() {
        val vm = newVm()
        vm.openDriverTrip(77)
        assertEquals(Screen.ActiveTrip, vm.screen.value)
        assertEquals(77, vm.activeBookingId.value)
        assertEquals("77", vm.selectedRide.value?.id)
        assertNotNull(vm.activeTrip.value)
    }

    @Test
    fun `заглушка поездки непустая и несёт номер брони`() {
        val stub = bookingLinkStubRide(43)
        assertEquals("43", stub.id)
        assertEquals(1, stub.seats)
    }

    // ── Проводка: решение принимается в одном месте и общее для пуша, центра уведомлений и повтора ──

    private val root = File("src/main/java/com/yuldash/app")
    private fun src(name: String): String = File(root, name).readText()

    @Test
    fun `YuldashApp применяет ссылку брони через applyBookingLink а не напрямую`() {
        val app = src("YuldashApp.kt")
        assertTrue("выбор экрана по роли пропал", app.contains("vm.applyBookingLink(bid, state.role, state.status, completed)"))
        assertTrue(
            "обе роли снова идут на «Бронь» без разбора",
            !app.contains("screen = if (completed) Screen.ActiveTrip else Screen.Booking"),
        )
    }

    @Test
    fun `пуш и центр уведомлений идут через ту же очередь брони`() {
        val main = src("MainActivity.kt")
        val bookingPushes = Regex("""requestBookingDestination\(id, ApiClient\.myUserId\(\), completed = false\)""").findAll(main).count()
        assertTrue("пуши «chat» и «booking» должны класть бронь в очередь: $bookingPushes", bookingPushes >= 2)
        val app = src("YuldashApp.kt")
        assertTrue(
            "центр уведомлений открывает бронь мимо общей очереди",
            Regex("""onOpenBooking = \{ bid ->\s*vm\.requestBookingDestination\(bid, ApiClient\.myUserId\(\), completed = false\)""")
                .containsMatchIn(app),
        )
    }

    @Test
    fun `кабинет водителя получает кнопку открытия поездки`() {
        assertTrue(
            "кабинет не знает, куда вести «Открыть поездку»",
            src("YuldashApp.kt").contains("onOpenTrip = { bid -> vm.openDriverTrip(bid) }"),
        )
        assertTrue(
            "в «Едут с тобой» пропала кнопка «Открыть поездку»",
            src("ProfileScreen.kt").contains("AppButton(text = appText(\"Открыть поездку\", \"Сәфәрҙе асыу\"), onClick = { onOpenTrip(b.bookingId) })"),
        )
    }
}
