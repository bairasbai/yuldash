package com.yuldash.app

// Доменные UI-модели (Фаза 0 разрезки). Чистые данные без Compose.
// Тот же пакет com.yuldash.app → импортов не нужно; видимость internal (виден экранам в своих файлах).

internal data class Ride(
    val id: String,
    val from: String,
    val to: String,
    val time: String,
    val timeBa: String? = null,
    val driver: String,
    val driverAvatar: String = "",
    val driverOnline: Boolean = false,
    val driverTrips: Int = 0,        // F8: завершённых поездок водителя (бейдж «N поездок»)
    val driverSince: String = "",    // F8: месяц регистрации "YYYY-MM" (бейдж «С нами с …»)
    val car: String,
    val carBa: String? = null,
    val price: Int,
    val seats: Int,
    val rating: Double,
    val verified: Boolean,
    val boosted: Boolean,
    val petsAllowed: Boolean = false,
    val childSeat: Boolean = false,
    val womenOnly: Boolean = false,
    val smoking: Boolean = false,
    val baggage: Boolean = false,
    val airConditioner: Boolean = false,
    val pickup: String = "",
    val pickupLat: Double? = null,
    val pickupLng: Double? = null,
    val receiverName: String = "",   // посылка: кому отдать
    val parcelSize: String = ""      // посылка: габарит/вес
)

internal data class PopularRoute(
    val from: String,
    val to: String,
    val minutes: String,
    val minutesBa: String? = null,
    val distance: String,
    val nearbyCount: Int,
    val label: String,
    val labelBa: String? = null
)

internal data class TrustedContact(
    val name: String,
    val relation: String,
    val phone: String,
    val notifyByDefault: Boolean,
    val id: Int = 0,
    val relationBa: String? = null
)

internal data class FrequentTrip(
    val title: String,
    val titleBa: String,
    val from: String,
    val to: String,
    val timeHint: String,
    val timeHintBa: String,
    val categoryKey: String
)

internal data class LocalRequest(
    val title: String,
    val route: String,
    val time: String,
    val passenger: String,
    val status: String,
    val price: Int = 0,
    val trustedContact: String? = null,
    val voiceUrl: String? = null,
    val serverId: Int = 0   // id заявки на сервере → открыть её отклики (0 = локальная/без id)
)

internal data class LocalVoiceMessage(
    val author: String,
    val transcript: String,
    val time: String,
    val audioPath: String? = null,
    val durationSec: Int = 0
)
