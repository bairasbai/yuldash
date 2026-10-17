package com.yuldash.app

// Шаги поездки попутки для закреплённой кнопки экрана активной поездки. Вынесено из
// BookingActiveTripScreen.kt (храповик размера файлов, 14.10.2026): чистая логика без Compose.

/**
 * Следующий РЕАЛЬНЫЙ серверный переход для одной закреплённой CTA варианта B.
 *
 * Посадку отмечает ВОДИТЕЛЬ (решение Александра 17.10.2026): «Я выехал» → «Подъезжаю» →
 * «Пассажир сел» (`boarded`) → «Завершить». У пассажира до посадки своей кнопки нет — `wait`
 * («Водитель отметит посадку»): пассажирский «Я сел» можно было нажать из дома, чтобы водитель
 * уже не отметил «Не пришёл». После отметки водителя у пассажира «Доехал» → «Завершить».
 *
 * [passengerStatus] — что пассажир нажал в ЭТОМ запуске (живёт в памяти экрана), [bookingStatus] —
 * статус брони с сервера. Посадку решает сервер: «в пути» (`onboard`) приходит опросом. Водитель
 * отменил свою отметку — бронь снова `confirmed`, и пассажир снова ждёт, что бы он ни нажимал.
 */
internal fun activeTripNextStatus(role: String, driverPhase: String, passengerStatus: String?, bookingStatus: String): String =
    if (role == "driver") {
        when {
            bookingStatus == "onboard" || driverPhase == "done" -> "done"
            driverPhase == "departed" -> "arriving"
            driverPhase == "arriving" -> "boarded"
            else -> "departed"
        }
    } else {
        when {
            bookingStatus == "done" || passengerStatus == "done" -> "done"
            bookingStatus != "onboard" -> "wait"
            passengerStatus == "arrived" -> "done"
            else -> "arrived"
        }
    }

/**
 * Показывать ли «Отменить поездку» на экране активной попутки: только пассажиру и только по
 * подтверждённой брони. Причины в окне — пассажирские («Водитель не отвечает»): нажатые водителем,
 * они уходили пассажиру как «Водитель не выходил на связь» (паритет с сайтом, 17.10.2026).
 * Водитель снимает поездку из кабинета, неявку отмечает «Не пришёл». Пока роль не пришла с
 * сервера (`""`), кнопки нет. Без брони — прежнее поведение: кнопка просто закрывает экран.
 */
internal fun activeTripShowsCancel(role: String, hasBooking: Boolean, bookingStatus: String): Boolean =
    !hasBooking || (role == "passenger" && bookingStatus == "confirmed")
