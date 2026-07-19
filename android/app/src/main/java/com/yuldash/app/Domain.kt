package com.yuldash.app

import androidx.compose.runtime.Composable

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

// ════════════════════════════════════════════════════════════════════════════
//  Система «Справедливость» (Trust, Safety & Fairness) — доменные модели.
//  Контракт: docs/trust-safety.md (§1 типы, §2 лестница, §5/§6 данные+API).
//  Философия: справедливо к каждой стороне, мир важнее наказания, рейтинг возвращается.
// ════════════════════════════════════════════════════════════════════════════

/** Состояние пользователя в системе справедливости (лестница §2): good→warned→limited→suspended. */
internal enum class Standing {
    Good, Warned, Limited, Suspended;

    companion object {
        fun fromCode(code: String): Standing = when (code) {
            "warned" -> Warned
            "limited" -> Limited
            "suspended" -> Suspended
            else -> Good
        }
    }
}

/** Статус спора (§5.1): open→awaiting_response→under_review→resolved/appealed/closed. */
internal enum class IncidentStatus {
    Open, AwaitingResponse, UnderReview, Resolved, Appealed, Closed;

    companion object {
        fun fromCode(code: String): IncidentStatus = when (code) {
            "awaiting_response" -> AwaitingResponse
            "under_review" -> UnderReview
            "resolved" -> Resolved
            "appealed" -> Appealed
            "closed" -> Closed
            else -> Open
        }
    }
}

/**
 * Тип инцидента — код из §1 матрицы. severe=true → мгновенный разбор человеком (минуя лестницу).
 * parcel=true → сценарий курьера/посылки (показываем в отдельной группе выбора типа).
 */
internal enum class IncidentType(val code: String, val severe: Boolean = false, val parcel: Boolean = false) {
    PassengerNoShow("passenger_no_show"),
    DriverNoShow("driver_no_show"),
    NonPayment("non_payment", severe = true),
    Rude("rude"),
    Unsafe("unsafe", severe = true),
    Harassment("harassment", severe = true),
    RouteDetour("route_detour"),
    Overcharge("overcharge"),
    RulesViolation("rules_violation"),
    ParcelDamage("parcel_damage", parcel = true),
    ParcelLost("parcel_lost", severe = true, parcel = true),
    ParcelDelay("parcel_delay", parcel = true),
    RecipientAbsent("recipient_absent", parcel = true),
    WrongContents("wrong_contents", severe = true, parcel = true),
    Other("other");

    companion object {
        fun fromCode(code: String): IncidentType = values().firstOrNull { it.code == code } ?: Other
    }
}

/** Двуязычная подпись типа инцидента (§1). Черновой башкирский — на проверку носителю (docs/tasks.md). */
@Composable
internal fun incidentTypeLabel(type: IncidentType): String = when (type) {
    IncidentType.PassengerNoShow -> appText("Пассажир не вышел", "Пассажир сыҡманы")
    IncidentType.DriverNoShow -> appText("Водитель не приехал", "Водитель килмәне")
    IncidentType.NonPayment -> appText("Не оплатил поездку", "Сәфәр өсөн түләмәне")
    IncidentType.Rude -> appText("Грубость, хамство", "Тупаҫлыҡ")
    IncidentType.Unsafe -> appText("Опасное вождение", "Хәүефле йөрөтөү")
    IncidentType.Harassment -> appText("Угрозы, домогательство", "Янау, теймәлеү")
    IncidentType.RouteDetour -> appText("Накрутка маршрута", "Юлды оҙонайтыу")
    IncidentType.Overcharge -> appText("Обман с ценой", "Хаҡ менән алдау")
    IncidentType.RulesViolation -> appText("Нарушил условия поездки", "Сәфәр шарттарын боҙҙо")
    IncidentType.ParcelDamage -> appText("Повредил посылку", "Йөктө боҙҙо")
    IncidentType.ParcelLost -> appText("Потерял посылку", "Йөктө юғалтты")
    IncidentType.ParcelDelay -> appText("Просрочил доставку", "Тапшырыуҙы кисектерҙе")
    IncidentType.RecipientAbsent -> appText("Получатель отсутствовал", "Алыусы урынында булманы")
    IncidentType.WrongContents -> appText("Запрещённое вложение", "Тыйылған эсәклек")
    IncidentType.Other -> appText("Другое", "Башҡа")
}

/** Спор/инцидент (§5.1 + IncidentOut §6). Версия заявителя + версия обвинённого + решение админа. */
internal data class Incident(
    val id: Int,
    val bookingId: Int?,
    val type: IncidentType,
    val status: IncidentStatus,
    val suspectedBump: Boolean = false, // §1.1 «бампинг»: водитель бросил подтверждённых ради платящих больше/родни
    val myRole: String,               // "reporter" | "respondent" | "" (я заявитель / обвинённый)
    val reporterRole: String,         // passenger/driver/courier/sender/recipient
    val description: String,          // версия заявителя
    val evidenceUrls: List<String>,   // фото заявителя
    val respondentStatement: String,  // объяснение обвинённого
    val respondentEvidenceUrls: List<String>,
    val resolution: String,           // none/dismissed/warning/strike/compensation/rating_adjust/suspend/ban/mutual_resolved
    val fault: String,                // none/reporter/respondent/both/unclear
    val resolutionNote: String,       // человеческое объяснение админа (видно обеим сторонам)
    val compensationKop: Int,         // предложенная компенсация (не списываем автоматически)
    val appealText: String,
    val appealStatus: String,         // ""/requested/upheld/overturned
    val otherName: String,            // имя второй стороны (для показа)
    val bookingRoute: String,         // "Город→Город"
    val createdAt: String,
    val respondedAt: String?,
    val resolvedAt: String?,
)

/** Публичный снимок доверия пользователя (§6 GET /users/{id}/trust). Без телефона/приватного. */
internal data class TrustSnapshot(
    val rating: Double,
    val ratingCount: Int,
    val trips: Int,
    val verified: Boolean,
    val reliability: Int,             // 0..100 — «Надёжность»
    val memberSince: String,
)

/** Пороги системы (§5.5, GET /safety/policy). Клиент показывает числа с сервера, не хардкодит. */
internal data class SafetyPolicy(
    val freeCancelMin: Int,
    val lateCancelBeforeDepartMin: Int,
    val waitTimerMin: Int,
    val strikesToLimit: Int,
    val strikesToSuspend: Int,
    val suspend1Days: Int,
    val suspend2Days: Int,
    val suspend3Days: Int,
    val strikeDecayDays: Int,
    val minRating: Double,
    val reliabilityWindow: Int,
)
