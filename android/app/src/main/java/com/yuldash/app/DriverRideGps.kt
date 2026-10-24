package com.yuldash.app

import com.yuldash.app.data.DriverBookingDto

// GPS водителя на весь рейс (Д2, решение Александра 24.10.2026).
//
// Водитель везёт нескольких и нажал «Завершить» по одному — GPS идёт дальше, пока у рейса есть другие
// живые брони; гаснет, когда закрыта последняя (завершена, отменена, неявка, блок).
//
// Точка водителя по-прежнему уходит ТОЛЬКО в канал одной брони (`/ws/trip/{id}/location`), а кому и
// когда её показать, решает сервер по правилам каждой брони (`location.driver_point_for_booking`).
// Здесь — какую бронь взять «несущей», когда прежняя закрылась. Чистая логика: её гоняют JVM-тесты
// (`DriverRideGpsTest`) по общему с сайтом набору случаев (`driver-ride-carrier-cases.json`).

/** Сколько секунд ещё должно оставаться до срока «Не пришёл», чтобы бронь в начале маршрута годилась в несущие. */
internal const val RIDE_CARRIER_MIN_TTL_SEC = 60

/**
 * Итог выбора.
 * [rideId] — рейс закрытой брони по строке сервера (0 — строки нет или старый сервер: тогда ничего не берём).
 * [liveBookings] — живые брони того же рейса, кроме закрытых: есть хоть одна → водителю «Едут с тобой».
 * [carrier] — через чей канал дальше идёт GPS; null — GPS гаснет.
 * [carrierTtlSec] — несущая годится только столько секунд (бронь в начале маршрута до срока «Не пришёл»); null — без срока.
 * [rideBookings] — все брони этого рейса (режим рейса узнаёт по ним «свою» бронь).
 */
internal data class RideCarrierPick(
    val rideId: Int,
    val liveBookings: List<Int>,
    val carrier: Int?,
    val carrierTtlSec: Int?,
    val rideBookings: Set<Int>,
)

private fun DriverBookingDto.live() = status == "onboard" || status == "confirmed"

/**
 * Несущая бронь рейса после того, как закрылись [closed] (обычно — прежняя несущая [anchorBookingId]).
 *
 * Рейс берём ТОЛЬКО из серверной строки [anchorBookingId] (не из поездки-заглушки, где id = номер брони:
 * номер брони мог совпасть с номером другого рейса водителя — и точку получили бы чужие пассажиры).
 *
 * Ступени (в пределах ступени — меньший номер брони):
 *  1. «в пути» — пассажир в машине;
 *  2. подтверждена, посадка на ОСТАНОВКЕ — окно её канала держит сервер (`stop_live_window`: открывается,
 *     когда машина могла доехать, закрывается через 15 минут после срока «Не пришёл»);
 *  3. подтверждена, посадка в НАЧАЛЕ маршрута (или место не указано) — только пока до срока «Не пришёл»
 *     больше [RIDE_CARRIER_MIN_TTL_SEC]: свой канал такой брони сервер держит открытым без срока, и
 *     «Бота, которая не вышла» иначе часами видела бы машину по своему каналу. Срок — в [RideCarrierPick.carrierTtlSec].
 */
internal fun pickRideCarrier(rows: List<DriverBookingDto>, anchorBookingId: Int, closed: Set<Int>): RideCarrierPick {
    val rideId = rows.firstOrNull { it.bookingId == anchorBookingId }?.rideId ?: 0
    if (rideId <= 0) return RideCarrierPick(0, emptyList(), null, null, emptySet())
    val ofRide = rows.filter { it.rideId == rideId }
    val live = ofRide.filter { it.live() && it.bookingId !in closed }.sortedBy { it.bookingId }
    val onboard = live.firstOrNull { it.status == "onboard" }
    val atStop = live.firstOrNull { it.status == "confirmed" && !it.pickupStop.isNullOrEmpty() }
    val atStart = live.firstOrNull {
        it.status == "confirmed" && it.pickupStop.isNullOrEmpty() && it.noShowWaitSec > RIDE_CARRIER_MIN_TTL_SEC
    }
    val carrier = onboard ?: atStop ?: atStart
    return RideCarrierPick(
        rideId = rideId,
        liveBookings = live.map { it.bookingId },
        carrier = carrier?.bookingId,
        carrierTtlSec = if (onboard == null && atStop == null) atStart?.noShowWaitSec else null,
        rideBookings = ofRide.map { it.bookingId }.toSet(),
    )
}

/**
 * Режим рейса на ЭТОМ телефоне (P1-1 проверки плана): включается только шагом водителя здесь —
 * «Выехал», «Подъезжаю», «Сел», «Завершить», «Не пришёл» — по брони того же рейса, что везёт служба.
 * Без него служба ведёт себя как раньше: закрылся канал или ушли с экрана — GPS гаснет. Так планшет дома
 * с когда-то открытой поездкой не подхватывает чужую бронь и не прыгает машиной между домом и дорогой.
 *
 * [untilElapsedMs] / [untilWallMs] — потолок жизни (P1-2): начало режима + 6 часов. Переключение несущей,
 * уход с экрана и воскрешение службы его не продлевают; любые одни из двух часов сказали «пора» — гаснем.
 */
internal data class DriverRideMode(
    val rideId: Int,
    val bookings: Set<Int>,
    val untilElapsedMs: Long,
    val untilWallMs: Long,
) {
    fun expired(nowElapsedMs: Long, nowWallMs: Long): Boolean =
        nowElapsedMs >= untilElapsedMs || nowWallMs >= untilWallMs ||
            untilElapsedMs - nowElapsedMs > DRIVER_RIDE_MAX_MS   // часы загрузки сбились (перезагрузка) — не верим

    companion object {
        fun start(rideId: Int, bookings: Set<Int>, nowElapsedMs: Long, nowWallMs: Long) =
            DriverRideMode(rideId, bookings, nowElapsedMs + DRIVER_RIDE_MAX_MS, nowWallMs + DRIVER_RIDE_MAX_MS)
    }
}

/** 6 часов — заведомо дольше любой реальной поездки (тот же срок, что сторож службы). */
internal const val DRIVER_RIDE_MAX_MS = 6 * 3600_000L
