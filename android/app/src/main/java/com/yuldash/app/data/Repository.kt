package com.yuldash.app.data

/**
 * Контракт данных приложения (см. docs/backend.md §5 — API).
 * Сейчас реализован моками (MockRepository), позже — реальным сервером
 * (ApiRepository поверх FastAPI/Ktor). Экраны зовут ЭТИ интерфейсы, а не моки напрямую,
 * поэтому переключение «моки → сервер» = подмена реализации, без переписывания экранов.
 *
 * Все методы suspend — чтобы реальная сетевая реализация не блокировала UI.
 */

interface AuthRepository {
    suspend fun requestCode(phone: String)                       // отправить SMS-код
    suspend fun verifyCode(phone: String, code: String): User    // проверить код → сессия
    suspend fun currentUser(): User?
    suspend fun logout()
}

interface RidesRepository {
    suspend fun search(from: String?, to: String?, category: RideCategory?): List<Ride>
    suspend fun nearby(): List<Ride>
    suspend fun byId(id: String): Ride?
    suspend fun publish(ride: Ride): Ride
}

interface RequestsRepository {
    suspend fun myRequests(): List<RideRequest>
    suspend fun create(request: RideRequest): RideRequest
    suspend fun feedForDriver(): List<RideRequest>               // подходящие заявки водителю
}

interface BookingsRepository {
    suspend fun book(rideId: String, seats: Int): Booking
    suspend fun confirm(bookingId: String): Booking
    suspend fun myBookings(): List<Booking>
}

interface DriverRepository {
    suspend fun setOnline(online: Boolean)
    suspend fun profile(): DriverProfile?
}

/** Матчинг — главная фича против такси (см. docs/backend.md §4). */
interface MatchRepository {
    suspend fun ridesForRequest(requestId: String): List<Ride>
}

/** Всё вместе — один объект, который экраны получают и зовут. */
interface YuldashRepository :
    AuthRepository, RidesRepository, RequestsRepository, BookingsRepository, DriverRepository, MatchRepository
