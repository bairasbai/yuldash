package com.yuldash.app.data

/**
 * Доменные модели для эпохи реального сервера (см. docs/backend.md §3).
 * Отдельный пакет `data` — НЕ трогает MainActivity. Экраны переведём на эти модели
 * по фазам, когда будем подключать сервер. Сейчас их отдаёт MockRepository.
 */

enum class UserRole { Passenger, Driver, Admin }
enum class AppLang { Ru, Ba }

data class User(
    val id: String,
    val phone: String,          // реальный номер — только для подтверждённых; UI маскирует
    val name: String,
    val role: UserRole,
    val language: AppLang = AppLang.Ru,
    val verified: Boolean = false,
)

data class Vehicle(
    val id: String,
    val make: String,
    val model: String,
    val color: String,
    val plate: String,
    val seats: Int,
    val photoUrl: String? = null,
)

enum class DocStatus { Pending, Approved, Rejected }

data class DriverProfile(
    val userId: String,
    val online: Boolean = false,
    val rating: Double = 5.0,
    val tripsCount: Int = 0,
    val about: String = "",
    val badges: List<String> = emptyList(),
    val vehicle: Vehicle? = null,
    val docsStatus: DocStatus = DocStatus.Pending,
)

enum class RideCategory { Regular, Hospital, Parcel }
enum class RideStatus { Active, Done, Cancelled }

/** Предложение водителя. */
data class Ride(
    val id: String,
    val driverId: String,
    val driverName: String,
    val from: String,
    val to: String,
    val departAt: String,       // позже — настоящий timestamp
    val seatsTotal: Int,
    val seatsLeft: Int,
    val price: Int,             // ₽
    val category: RideCategory = RideCategory.Regular,
    val comment: String = "",
    val rating: Double = 5.0,
    val verified: Boolean = false,
    val boosted: Boolean = false,
    val status: RideStatus = RideStatus.Active,
)

/** Заявка пассажира (что ему нужно). */
data class RideRequest(
    val id: String,
    val passengerId: String,
    val from: String,
    val to: String,
    val desiredAt: String,
    val seats: Int = 1,
    val maxPrice: Int? = null,
    val category: RideCategory = RideCategory.Regular,
    val withKids: Boolean = false,
    val baggage: Boolean = false,
    val comment: String = "",
    val forRelativeName: String? = null,    // семейный заказ за близкого
    val status: String = "active",
)

enum class BookingStatus { Pending, Confirmed, Onboard, Done, Cancelled }

data class Booking(
    val id: String,
    val rideId: String,
    val passengerId: String,
    val driverId: String,
    val seats: Int,
    val price: Int,
    val status: BookingStatus = BookingStatus.Pending,
    val boardingCode: String? = null,       // код посадки
)

data class TrustedContact(
    val id: String,
    val name: String,
    val relation: String,
    val phone: String,
    val notifyByDefault: Boolean = true,
)
