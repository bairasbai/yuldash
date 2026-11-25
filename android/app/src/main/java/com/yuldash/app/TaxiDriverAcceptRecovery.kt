package com.yuldash.app

internal enum class DriverAcceptRead { Active, Closed, Unknown }

internal fun classifyDriverAcceptRead(
    orderId: Int,
    driverId: Int,
    resultId: Int?,
    resultRole: String?,
    assignedDriverId: Int?,
    status: String?,
    failure: Int? = null,
    failureMessage: String? = null,
): DriverAcceptRead = when {
    // A gateway 403 is not proof that the offer was lost.
    failure == 403 && failureMessage in listOf("Нет доступа к заказу", "Заказға рөхсәт юҡ") -> DriverAcceptRead.Closed
    failure == 404 && failureMessage in listOf("Заказ не найден", "Заказ табылманы") -> DriverAcceptRead.Closed
    resultId != orderId -> DriverAcceptRead.Unknown
    resultRole == "driver" && assignedDriverId == driverId &&
        status in listOf("accepted", "arriving", "onboard") -> DriverAcceptRead.Active
    status in listOf("expired", "cancelled", "done") -> DriverAcceptRead.Closed
    else -> DriverAcceptRead.Unknown
}
