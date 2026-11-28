package com.yuldash.app.data

import kotlinx.coroutines.withTimeoutOrNull

internal sealed interface DriverCurrentOrder<out T> {
    data class Active<T>(val order: T) : DriverCurrentOrder<T>
    data object None : DriverCurrentOrder<Nothing>
    data object Conflict : DriverCurrentOrder<Nothing>
    data object Unavailable : DriverCurrentOrder<Nothing>
    data object SessionChanged : DriverCurrentOrder<Nothing>
}

internal class DriverCurrentOrderConflict : Exception()

internal fun driverOwnsActiveOrder(
    requestedId: Int, ownerId: Int, id: Int, driverId: Int?, role: String, status: String,
): Boolean = requestedId == id && ownerId == driverId && role == "driver" &&
    (status == "accepted" || status == "arriving" || status == "onboard")

/** Read-only. A null assignment is not proof that an in-flight Accept failed. */
internal suspend fun <T> lookupDriverCurrentOrder(
    sameOwner: () -> Boolean,
    readId: suspend () -> Result<Int?>,
    readOrder: suspend (Int) -> Result<T>,
    isOwnedActive: (Int, T) -> Boolean,
): DriverCurrentOrder<T> {
    if (!sameOwner()) return DriverCurrentOrder.SessionChanged
    val assignment = withTimeoutOrNull(5_000) { readId() }
    if (!sameOwner()) return DriverCurrentOrder.SessionChanged
    if (assignment?.exceptionOrNull() is DriverCurrentOrderConflict) return DriverCurrentOrder.Conflict
    if (assignment == null || assignment.isFailure) return DriverCurrentOrder.Unavailable
    val id = assignment.getOrNull() ?: return DriverCurrentOrder.None
    if (id <= 0) return DriverCurrentOrder.Unavailable
    val detail = withTimeoutOrNull(5_000) { readOrder(id) }?.getOrNull()
    if (!sameOwner()) return DriverCurrentOrder.SessionChanged
    return if (detail != null && isOwnedActive(id, detail)) DriverCurrentOrder.Active(detail)
    else DriverCurrentOrder.Unavailable
}
