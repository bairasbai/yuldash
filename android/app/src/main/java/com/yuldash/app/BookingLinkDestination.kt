package com.yuldash.app

// Куда вести по пушу, ссылке или карточке уведомления про бронь.
//
// Один вход на всех: пуш «бронь» и «чат», центр уведомлений и повтор неудавшейся ссылки
// кладут бронь в очередь (`YuldashViewModel.requestBookingDestination`), а `YuldashApp`
// спрашивает у сервера роль человека в этой брони и выбирает экран здесь.
//
// Почему не один экран на всех. «Бронь» (`BookingScreen`) написана для пассажира: пока водитель
// молчит, она говорит «Ждём ответа водителя» и предлагает красную «Отменить бронь». Водитель,
// которому пришёл пуш «новая бронь», попадал именно туда: подтвердить негде, а чужую бронь
// отменить можно одним нажатием. Подтверждает водитель в своём кабинете («Ждут подтверждения»),
// а когда пассажир уже подтверждён, ему нужен экран поездки («Я выехал / Подъезжаю / Завершить»).

/** Экран поездки открывается брони подтверждённой, в пути или завершённой (оценка и квитанция). */
internal fun bookingStatusAllowsActiveTrip(status: String): Boolean =
    status == "confirmed" || status == "onboard" || status == "done"

/** Посадка и живое гео — только у идущей поездки. */
internal fun bookingStatusAllowsBoarding(status: String): Boolean =
    status == "confirmed" || status == "onboard"

/**
 * Экран, на который ведёт ссылка на бронь.
 *
 * Завершённая бронь (`completed`) открывает экран поездки у обеих ролей — там итог и оценка.
 * Водитель: подтверждённая, «в пути» или завершённая бронь — экран поездки, всё остальное
 * (ждёт подтверждения, отменена) — кабинет водителя. Пассажир — «Бронь», как и раньше.
 */
internal fun bookingLinkScreen(role: String, status: String, completed: Boolean): Screen = when {
    completed -> Screen.ActiveTrip
    role == "driver" -> if (bookingStatusAllowsActiveTrip(status)) Screen.ActiveTrip else Screen.DriverCabinet
    else -> Screen.Booking
}

/**
 * Поездка-заглушка для экрана, который сам дочитывает всё по номеру брони. Нужна непустая:
 * без неё `YuldashApp` решит, что поездку потеряли, и вернёт человека на главную (бронь водителя
 * в списке «Мои брони» пассажира не лежит, восстановить её оттуда нельзя).
 */
internal fun bookingLinkStubRide(bookingId: Int): Ride = Ride(
    id = bookingId.toString(), from = "", to = "", time = "", driver = "", car = "",
    price = 0, seats = 1, rating = 0.0, verified = false, boosted = false,
)

/**
 * Разобранная ссылка на бронь → состояние приложения; возвращает экран, на который ушли.
 *
 * Живое гео (`activeTrip`) включается только у подтверждённой или идущей брони водителя — как при
 * «Открыть поездку» в «Брони»; у завершённой оно гаснет; пассажира на «Бронь» не трогаем.
 * Кабинет водителя ничего из брони не берёт: он сам читает свои списки.
 */
internal fun YuldashViewModel.applyBookingLink(bookingId: Int, role: String, status: String, completed: Boolean): Screen {
    val target = bookingLinkScreen(role, status, completed)
    if (target != Screen.DriverCabinet) {
        val stub = bookingLinkStubRide(bookingId)
        selectedRide.value = stub
        activeBookingId.value = bookingId
        activeDriverBookingId.value = bookingId.takeIf { role == "driver" }   // роль для живого гео (пульс стоянки)
        if (completed) activeTrip.value = null
        else if (target == Screen.ActiveTrip) activeTrip.value = stub.takeIf { bookingStatusAllowsBoarding(status) }
    }
    screen.value = target
    return target
}

/** «Едут с тобой» → экран поездки водителя. Бронь подтверждённая, поэтому живое гео включаем сразу. */
internal fun YuldashViewModel.openDriverTrip(bookingId: Int) {
    val stub = bookingLinkStubRide(bookingId)
    selectedRide.value = stub
    activeBookingId.value = bookingId
    activeDriverBookingId.value = bookingId   // я водитель этой брони — живому гео нужен пульс стоянки
    activeTrip.value = stub
    screen.value = Screen.ActiveTrip
}
