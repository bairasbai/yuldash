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
