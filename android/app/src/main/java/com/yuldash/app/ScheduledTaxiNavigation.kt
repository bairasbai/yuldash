package com.yuldash.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.InstantOrderDto
import com.yuldash.app.data.OfflineTripSnapshots

/** Save only the requested ID, under the durable login and API scope. Fetch the card again on entry. */
@Composable
internal fun rememberScheduledTaxiTarget(screen: Screen, generation: Long): MutableIntState {
    val owner = OfflineTripSnapshots.currentOwner()
    val target = if (owner != null) key("scheduled-taxi-target-v1", owner.namespace) {
        rememberSaveable { mutableIntStateOf(0) }
    } else remember(generation) { mutableIntStateOf(0) }
    LaunchedEffect(target, screen) { if (screen != Screen.InstantOrder) target.intValue = 0 }
    return target
}

@Composable
internal fun ScheduledTaxiDestination(vm: YuldashViewModel, target: MutableIntState, onBack: () -> Unit) {
    val generation by ApiClient.sessionChanges.collectAsState()
    val revision by vm.privateNavigationRevisions.collectAsState()
    key(generation, revision) {
        val sourceRevision = revision
        val sourceGeneration = generation
        val current = {
            vm.screen.value == Screen.ScheduledOrders && vm.privateNavigationRevision == sourceRevision &&
                ApiClient.isCurrentSession(sourceGeneration)
        }
        ScheduledOrdersScreen(onBack, onActivated = { id ->
            if (current()) vm.navigateLocally {
                target.intValue = id
                vm.screen.value = Screen.InstantOrder
            }
        }, isCurrentParent = current)
    }
}

internal enum class ScheduledActivation { WAITING, OPEN, ENDED, INVALID }

internal fun scheduledActivation(order: InstantOrderDto, requestedId: Int, userId: Int?): ScheduledActivation {
    if (requestedId <= 0 || order.id != requestedId || order.role != "passenger" ||
        (order.passengerId != null && order.passengerId != userId)) return ScheduledActivation.INVALID
    return when (order.status) {
        "scheduled" -> ScheduledActivation.WAITING
        "created", "searching", "offered", "accepted", "arriving", "onboard", "expired" -> ScheduledActivation.OPEN
        "done", "cancelled" -> ScheduledActivation.ENDED
        else -> ScheduledActivation.INVALID
    }
}

internal fun matchesScheduledTaxiCard(order: InstantOrderDto, requestedId: Int, userId: Int?): Boolean =
    when (scheduledActivation(order, requestedId, userId)) {
        ScheduledActivation.OPEN, ScheduledActivation.ENDED -> true
        else -> false
    }
