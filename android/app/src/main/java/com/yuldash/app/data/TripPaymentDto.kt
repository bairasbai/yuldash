package com.yuldash.app.data

/** Результат оплаты поездки (POST /bookings/{id}/pay, POST /instant/orders/{id}/pay).
 *  status: paid (наличные) | already_paid | succeeded (mock/dev) | pending (ждём подтверждения ЮKassa).
 *  pending + confirmationUrl → открыть браузер, потом поллить getPaymentStatus(paymentId). */
data class PayTripResultDto(
    val status: String,
    val method: String,
    val paymentId: Int?,
    val confirmationUrl: String?,
) {
    val isPaid: Boolean get() = status == "paid" || status == "already_paid" || status == "succeeded"
}

/** Квитанция за такси-поездку (GET /instant/orders/{id}/receipt). Телефонов в чеке нет.
 *  amount — ₽ (итог поездки), waitingFeeKop — платное ожидание, копейки. */
data class InstantReceiptDto(
    val orderId: Int, val role: String,
    val fromText: String, val toText: String, val doneAt: String,
    /** amount — рубли (для старых экранов), amountKop — точная сумма: её и показываем в чеке. */
    val distanceKm: Double, val amount: Int, val amountKop: Int, val waitingFeeKop: Int,
    val priceKop: Int = amountKop,
    val promoDiscountKop: Int = 0,
    val paymentMethod: String, val paid: Boolean,
    val driverName: String, val driverVerified: Boolean,
    val counterpartyId: Int = 0,
    val counterpartyName: String = "",
    /** Моя собственная оценка этой поездки. Ноль — ещё не оценивал. Нужна чеку: повторное
     *  открытие не должно показывать пустые звёзды после уже отправленной оценки. */
    val myStars: Int = 0,
    val myRatingTags: String = "",
    // --- Из чего сложилась сумма (2026-08-23). Раньше в чеке была одна цифра, и на вопрос
    // «куда делись деньги» ответить было нечем. Старый сервер полей не шлёт → нули, и чек
    // выглядит как прежде.
    val ridePrice: Int = 0,
    val rideBasePrice: Int = 0,
    val surgeRub: Int = 0,
    val pickupFeeKop: Int = 0,
    val pickupKm: Double = 0.0,
    val pickupEnroute: Boolean = false,
    val optionsFeeKop: Int = 0,
    val options: List<String> = emptyList(),
    val weatherFeeKop: Int = 0,
    val weatherKind: String = "",
    // --- Только для водителя: он реально платит комиссию, поэтому видит её целиком.
    // Пассажиру их не шлют вообще (в Модели А он платит водителю напрямую).
    val driverFeePercent: Double = 0.0,
    val driverFeeKop: Int = 0,
    val driverGrossKop: Int = 0,
    val driverNetKop: Int = 0,
    val commissionFreeKop: Int = 0,
)
