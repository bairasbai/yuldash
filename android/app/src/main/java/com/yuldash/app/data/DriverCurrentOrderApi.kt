package com.yuldash.app.data

import com.yuldash.app.DriverAcceptRead
import com.yuldash.app.classifyDriverAcceptRead
import kotlinx.coroutines.withTimeoutOrNull

internal sealed interface DriverCurrentOrder {
    data class Active(val order: InstantOrderDto) : DriverCurrentOrder
    data object None : DriverCurrentOrder
    data object Unavailable : DriverCurrentOrder
    data object SessionChanged : DriverCurrentOrder
}

/** Server snapshot, not evidence that an in-flight Accept failed. Never replays a mutation. */
internal suspend fun ApiClient.readDriverCurrentOrder(
    generation: Long,
    driverId: Int,
): DriverCurrentOrder {
    fun sameOwner() = isCurrentSession(generation) && myUserId() == driverId && driverId > 0
    if (!sameOwner()) return DriverCurrentOrder.SessionChanged
    val result = withTimeoutOrNull(5_000) { readDriverCurrentOrderIdOnce(generation) }
    if (!sameOwner()) return DriverCurrentOrder.SessionChanged
    if (result == null || result.isFailure) return DriverCurrentOrder.Unavailable
    val id = result.getOrNull() ?: return DriverCurrentOrder.None
    val order = withTimeoutOrNull(5_000) { readDriverAcceptOnce(id, generation) }?.getOrNull()
    if (!sameOwner()) return DriverCurrentOrder.SessionChanged
    if (order != null && classifyDriverAcceptRead(
            id, driverId, order.id, order.role, order.driverId, order.status,
        ) == DriverAcceptRead.Active
    ) return DriverCurrentOrder.Active(order)
    return DriverCurrentOrder.Unavailable
}
