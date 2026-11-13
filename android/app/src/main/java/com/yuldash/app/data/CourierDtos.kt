package com.yuldash.app.data

// ═══════════ C1: Курьер Юлдаша ═══════════

/** Заявка «Стать курьером». status: pending | approved | rejected. */
/** День заработка курьера (чистыми = цена доставки минус комиссия платформы). */
data class CourierEarningsDayDto(val date: String, val netKop: Int, val deliveries: Int)

/** Заработок курьера за период. Всё в КОПЕЙКАХ (у водителя аналогичный экран — в рублях). */
data class CourierEarningsDto(
    val period: String,
    val netKop: Int,
    val commissionKop: Int,
    val deliveries: Int,
    val byDay: List<CourierEarningsDayDto>,
)

data class CourierApplicationDto(
    val id: Int,
    val transport: String,        // car | cargo
    val status: String,           // pending | approved | rejected
    val selfieUrl: String,
    val fullName: String = "",    // ФИО как в документе (сверка с селфи)
    val carPlate: String = "",    // госномер — по нему узнают машину
    val rulesAccepted: Boolean = false,
    val invitedBy: String?,       // «кто пригласил» (реферал, доверие между своими)
    val rejectReason: String,     // причина отклонения (видит курьер)
    val createdAt: String,
    val reviewedAt: String?,
    // Только в админ-списке (в личной заявке пустые):
    val userId: Int = 0,
    val name: String = "",
    val phone: String = "",
)

/** Разбивка цены доставки курьером — показываем честно (из чего сложилась цена). */
data class CourierEstimateBreakdown(
    val baseKop: Int, val distanceKop: Int, val sizeKop: Int, val urgencyKop: Int, val commissionPercent: Double,
    val commissionMinKop: Int = 0,            // пол комиссии за доставку (25 ₽)
    val commissionEstimated: Boolean = false, // комиссия до вручения — оценка (финал после вручения)
    // «Если получателя не будет» — оценка возврата, показанная ДО заказа. Конституционный суд
    // (декабрь 2022) признал недопустимым брать плату за возврат с человека, которого о ней
    // заранее не предупредили. Без этой строки на экране заказа наша компенсация висит в воздухе.
    val returnFeeEstimateKop: Int = 0,
)

/** Оценка стоимости доставки курьером (сервер считает по своей формуле). */
data class CourierEstimateDto(
    val priceKop: Int, val commissionKop: Int, val distanceKm: Double, val breakdown: CourierEstimateBreakdown,
)

/** Профиль курьера (режим работы). */
data class CourierProfileDto(
    val id: Int, val online: Boolean, val carClass: String, val zone: String,
    val workCity: String?, val workDirectionId: Int?, val updatedAt: String,
    // Зона по-новому: район как база + два согласия (см. InstantZoneDto — правила общие).
    val workDistrict: String? = null,
    val workIntercity: Boolean = false,
    val workRegions: Boolean = false,
)

/** C3: выписка курьера по нашей комиссии.
 *  earned — всего наша комиссия за доставки; owed — к оплате сейчас; paid — уже оплачено.
 *  commissionKop — легаси-поле (== earned), оставлено для совместимости. */
data class CourierStatementDto(
    val deliveredCount: Int,
    val commissionEarnedKop: Int,
    val commissionOwedKop: Int,
    val commissionPaidKop: Int,
    val commissionKop: Int,
    val currentFeePercent: Double = 0.0,     // текущая ставка комиссии курьера, %
    val feeTier: String = "",                // ступень: promo | tier1 | tier2 | tier3
    val commissionMinKop: Int = 0,           // пол комиссии за доставку (25 ₽)
)

/** C3: рейтинг курьера. avg=null — пока нет оценок. */
data class CourierRatingDto(val avg: Double?, val count: Int)

/** C3: ответ /parcels/{id}/rate — новый рейтинг оценённого пользователя. */
data class RateResultDto(val rateeId: Int, val rating: Double, val count: Int)

/** C3: ответ /courier/pay-commission — заявка на оплату комиссии + реквизиты СБП. */
data class PayCommissionDto(
    val status: String, val paymentId: Int, val amountKop: Int, val amount: Int,
    val payeePhone: String, val payeeBank: String, val payeeName: String,
    val method: String = "sbp_manual",           // yookassa → оплата картой, иначе СБП «на доверии»
    val confirmationUrl: String? = null,          // ЮKassa redirect (открыть в браузере)
)

/** Ответ на оплату долга такси: yookassa (оплата картой) или sbp_manual (перевод «на доверии»). */
data class DebtPayResultDto(
    val method: String, val status: String, val paymentId: Int,
    val pendingKop: Int, val confirmationUrl: String?,
)

/** Ответ /courier/me: заявка + профиль (если одобрен) + выписка + рейтинг + пауза по качеству. */
data class CourierMeDto(
    val application: CourierApplicationDto?,
    val profile: CourierProfileDto?,
    val statement: CourierStatementDto,
    val rating: CourierRatingDto = CourierRatingDto(null, 0),
    val pausedUntil: String? = null,
)

/** Выписка по посылкам (админ). Три разных числа вместо одного «собрано», которое врало:
 *  сбор «по пути» никому не выставляется, а показывался как выручка (аудит 2026-07-26). */
data class ParcelStatementDto(
    val deliveredCount: Int,
    val collectedFeeKop: Int,          // деньги дошли: курьеры оплатили комиссию
    val owedCommissionKop: Int = 0,    // начислено курьерам, ещё не оплачено (долг)
    val unbilledFeeKop: Int = 0,       // сбор «по пути»: выставить некому — это не выручка
)

/** Ответ /admin/parcels: все посылки + выписка. */
data class ParcelAdminListDto(val parcels: List<ParcelDto>, val statement: ParcelStatementDto)
