package com.yuldash.app.data

/**
 * Мок-реализация: данные в памяти, как сейчас в приложении (пропадают при перезапуске).
 * Заменяется на ApiRepository (реальный сервер) без изменения экранов.
 * Когда подключим сервер — создаём ApiRepository : YuldashRepository и меняем
 * одну строку в месте создания репозитория.
 */
class MockRepository : YuldashRepository {

    private var user: User? = null

    private val rides = mutableListOf(
        Ride("1", "d1", "Ильдар", "Баймаҡ", "Сибай", "Сегодня, 17:30", 3, 2, 350, RideCategory.Regular, rating = 4.8, verified = true, boosted = true),
        Ride("2", "d2", "Айгуль", "Темясово", "Уфа", "Завтра, 06:00", 3, 2, 1400, RideCategory.Regular, rating = 4.9, verified = true),
        Ride("3", "d3", "Рустам", "Сибай", "Баймаҡ", "Пятница, 13:20", 2, 1, 300, RideCategory.Regular, rating = 4.6),
    )
    private val requests = mutableListOf<RideRequest>()
    private val bookings = mutableListOf<Booking>()
    private var driverOnline = false

    // --- Auth ---
    override suspend fun requestCode(phone: String) { /* мок: код «отправлен» */ }
    override suspend fun verifyCode(phone: String, code: String): User =
        User("u1", phone, "Байрас", UserRole.Passenger, verified = true).also { user = it }
    override suspend fun currentUser(): User? = user
    override suspend fun logout() { user = null }

    // --- Rides ---
    override suspend fun search(from: String?, to: String?, category: RideCategory?): List<Ride> =
        rides.filter { r ->
            (from.isNullOrBlank() || r.from.contains(from, ignoreCase = true)) &&
                (to.isNullOrBlank() || r.to.contains(to, ignoreCase = true)) &&
                (category == null || r.category == category) &&
                r.status == RideStatus.Active
        }
    override suspend fun nearby(): List<Ride> = rides.filter { it.status == RideStatus.Active }
    override suspend fun byId(id: String): Ride? = rides.find { it.id == id }
    override suspend fun publish(ride: Ride): Ride { rides.add(0, ride); return ride }

    // --- Requests ---
    override suspend fun myRequests(): List<RideRequest> = requests.toList()
    override suspend fun create(request: RideRequest): RideRequest { requests.add(0, request); return request }
    override suspend fun feedForDriver(): List<RideRequest> = requests.toList()

    // --- Bookings ---
    override suspend fun book(rideId: String, seats: Int): Booking {
        val price = rides.find { it.id == rideId }?.price ?: 0
        val booking = Booking("b${bookings.size + 1}", rideId, user?.id ?: "u1", "d1", seats, price, BookingStatus.Pending, boardingCode = "1234")
        bookings.add(booking)
        return booking
    }
    override suspend fun confirm(bookingId: String): Booking {
        val i = bookings.indexOfFirst { it.id == bookingId }
        val confirmed = bookings[i].copy(status = BookingStatus.Confirmed)
        bookings[i] = confirmed
        return confirmed
    }
    override suspend fun myBookings(): List<Booking> = bookings.toList()

    // --- Driver ---
    override suspend fun setOnline(online: Boolean) { driverOnline = online }
    override suspend fun profile(): DriverProfile? =
        DriverProfile("d1", online = driverOnline, rating = 4.8, tripsCount = 42)

    // --- Match (упрощённо, см. docs/backend.md §4) ---
    override suspend fun ridesForRequest(requestId: String): List<Ride> {
        val req = requests.find { it.id == requestId } ?: return emptyList()
        return rides.filter {
            it.status == RideStatus.Active &&
                it.from.contains(req.from, ignoreCase = true) &&
                it.to.contains(req.to, ignoreCase = true) &&
                it.seatsLeft >= req.seats &&
                it.category == req.category
        }.sortedWith(compareByDescending<Ride> { it.boosted }.thenByDescending { it.rating })
    }
}
