package com.yuldash.app

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.yuldash.app.data.ApiClient

/** Identity is the publication receipt: the same number published again is a different trip. */
internal class TaxiTripPublication(val generation: Long, val orderId: Int, val publisher: Any)

/** The most recently mounted passenger screen owns publication and its visibility flags. */
internal object TaxiNavigationState {
    private data class ScreenOwner(val generation: Long, val publisher: Any, val orderVisible: Boolean, val tripVisible: Boolean, val finishedOrderIds: Set<Int> = emptySet())
    private val trip = mutableStateOf<TaxiTripPublication?>(null)
    private val screen = mutableStateOf<ScreenOwner?>(null)

    fun currentTrip(generation: Long = ApiClient.queueSessionGeneration()): TaxiTripPublication? {
        var current: TaxiTripPublication? = null
        ApiClient.runIfCurrentSession(generation) { current = trip.value?.takeIf { it.generation == generation } }
        return current
    }

    fun orderOnScreen(generation: Long = ApiClient.queueSessionGeneration()): Boolean =
        currentScreen(generation)?.orderVisible == true

    fun tripOnScreen(generation: Long = ApiClient.queueSessionGeneration()): Boolean =
        currentScreen(generation)?.tripVisible == true

    private fun currentScreen(generation: Long): ScreenOwner? {
        var current: ScreenOwner? = null
        ApiClient.runIfCurrentSession(generation) { current = screen.value?.takeIf { it.generation == generation } }
        return current
    }

    fun claimScreen(generation: Long, publisher: Any): Boolean = ApiClient.runIfCurrentSession(generation) {
        screen.value = ScreenOwner(generation, publisher, orderVisible = false, tripVisible = false)
    }

    fun isScreenOwner(generation: Long, publisher: Any): Boolean =
        currentScreen(generation)?.publisher === publisher

    fun publishScreen(generation: Long, publisher: Any, phase: String, orderId: Int?): Boolean {
        var accepted = false
        ApiClient.runIfCurrentSession(generation) {
            val owner = screen.value
            if (owner?.generation == generation && owner.publisher === publisher) {
                // A recomposition of an old accepted DTO must not revive a finished receipt.
                if (phase == "enroute" && orderId != null && orderId in owner.finishedOrderIds) return@runIfCurrentSession
                accepted = true
                val finished = if (phase in listOf("done", "expired", "cancelled") && orderId != null && orderId > 0)
                    owner.finishedOrderIds + orderId else owner.finishedOrderIds
                screen.value = owner.copy(orderVisible = phase == "searching" || phase == "enroute", tripVisible = phase == "enroute", finishedOrderIds = finished)
                val active = trip.value
                if (phase == "enroute" && orderId != null && orderId > 0) {
                    if (active?.generation != generation || active.orderId != orderId || active.publisher !== publisher) {
                        trip.value = TaxiTripPublication(generation, orderId, publisher)
                    }
                } else if (phase == "picker" || (phase in listOf("done", "expired", "cancelled") &&
                        active?.generation == generation && active.orderId == orderId && active.publisher === publisher)) {
                    trip.value = null
                }
            }
        }
        return accepted
    }

    fun releaseScreen(generation: Long, publisher: Any) {
        ApiClient.runIfUnchangedSession(generation) {
            if (screen.value?.generation == generation && screen.value?.publisher === publisher) screen.value = null
        }
    }

    fun finishTrip(expected: TaxiTripPublication): Boolean {
        var accepted = false
        ApiClient.runIfCurrentSession(expected.generation) {
            if (trip.value === expected) {
                trip.value = null
                val owner = screen.value
                if (owner?.generation == expected.generation && owner.publisher === expected.publisher) {
                    screen.value = owner.copy(orderVisible = false, tripVisible = false, finishedOrderIds = owner.finishedOrderIds + expected.orderId)
                }
                accepted = true
            }
        }
        return accepted
    }

    fun runIfCurrentTrip(expected: TaxiTripPublication, action: () -> Unit): Boolean {
        var accepted = false
        ApiClient.runIfCurrentSession(expected.generation) {
            if (trip.value === expected) { action(); accepted = true }
        }
        return accepted
    }

    // Compatibility views preserve existing callers; product writers use explicit receipts/leases.
    val activeTrip: MutableState<Int> = projection(
        read = { currentTrip()?.orderId ?: 0 },
        write = { id ->
            val generation = ApiClient.queueSessionGeneration()
            if (id == 0) ApiClient.runIfUnchangedSession(generation) { trip.value = null }
            else ApiClient.runIfCurrentSession(generation) { trip.value = TaxiTripPublication(generation, id, Any()) }
        },
    )
    val orderVisible: MutableState<Boolean> = projection(
        read = { orderOnScreen() }, write = { setCompatibilityVisibility(order = it) },
    )
    val tripVisible: MutableState<Boolean> = projection(
        read = { tripOnScreen() }, write = { setCompatibilityVisibility(inTrip = it) },
    )
    private fun setCompatibilityVisibility(order: Boolean? = null, inTrip: Boolean? = null) {
        val generation = ApiClient.queueSessionGeneration()
        ApiClient.runIfUnchangedSession(generation) {
            val old = screen.value?.takeIf { it.generation == generation }
            screen.value = ScreenOwner(generation, old?.publisher ?: Any(), order ?: old?.orderVisible ?: false, inTrip ?: old?.tripVisible ?: false, old?.finishedOrderIds ?: emptySet())
        }
    }
    private fun <T> projection(read: () -> T, write: (T) -> Unit): MutableState<T> = object : MutableState<T> {
        override var value: T
            get() = read()
            set(value) = write(value)
        override fun component1(): T = value
        override fun component2(): (T) -> Unit = { value = it }
    }
}
