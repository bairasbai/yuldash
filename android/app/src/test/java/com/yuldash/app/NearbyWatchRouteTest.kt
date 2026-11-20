package com.yuldash.app

import androidx.compose.runtime.saveable.SaverScope
import com.yuldash.app.data.RouteWatchDto
import com.yuldash.app.data.ApiClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NearbyWatchRouteTest {
    private val route = NearbyWatchRoute(" Ургаза ", "Акъяр", "2030-05-02")
    private val watch = RouteWatchDto(9, "УРГАЗА", "Акъяр", "forward", "2030-05-01T19:00:00")

    @Test fun usesSingleSelectedPlaceBeforeFocusWithoutChoosingFromSeveral() {
        assertEquals(route.copy(from = "Ургаза"), nearbyWatchRoute(setOf("Ургаза"), emptySet(), "Сибай", "Акъяр", route.day))
        val ambiguous = nearbyWatchRoute(setOf("Сибай", "Ургаза"), setOf("Акъяр"), "Сибай", "Уфа", route.day)
        assertEquals(NearbyWatchRoute(null, "Акъяр", route.day), ambiguous)
        assertFalse(ambiguous.hasPlaces)
    }

    @Test fun exactDateKindAndDirectionChooseSameSmallestIdAsServer() {
        val other = listOf(
            watch.copy(id = 1, watchKind = "requests"), watch.copy(id = 2, watchKind = "both"),
            watch.copy(id = 3, direction = "both"), watch.copy(id = 4, fromCity = "Акъяр", toCity = "Ургаза"),
            watch.copy(id = 5, watchDate = null), watch.copy(id = 6, watchDate = "2030-05-01T18:59:59"),
        )
        assertEquals("2030-05-02", routeWatchLocalDay(watch.watchDate!!))
        assertNull(matchingNearbyWatch(other, route))
        assertEquals(8, matchingNearbyWatch(other + watch + watch.copy(id = 8), route)?.id)
        assertEquals(5, matchingNearbyWatch(other + watch, route.copy(day = null))?.id)
        assertEquals(9, matchingNearbyWatch(listOf(watch.copy(fromCity = "Straße")), route.copy(from = "STRASSE"))?.id)
    }

    @Test fun guestDraftContinuesOnlyForTheNextLoginThatOpensWatches() {
        val navigation = RouteWatchNavigation()
        navigation.prepare(20, signedIn = false, route)
        assertNull(navigation.prefill(20))
        navigation.signedIn(21, resumeWatch = true)
        assertEquals(route, navigation.prefill(21))
        assertNull(navigation.prefill(22))
        navigation.prepare(23, signedIn = false, route)
        navigation.signedIn(25, resumeWatch = true)
        assertNull(navigation.prefill(25))
        navigation.prepare(26, signedIn = false, route)
        navigation.signedIn(27, resumeWatch = false)
        assertNull(navigation.prefill(27))
        navigation.prepare(28, signedIn = true, route)
        assertEquals(route, navigation.prefill(28))
        navigation.clear()
        assertNull(navigation.prefill(28))
    }

    @Test fun savedGuestAndSignedInDraftsRestoreWithinTheirSession() {
        val scope = object : SaverScope { override fun canBeSaved(value: Any) = true }
        val navigation = RouteWatchNavigation()
        navigation.prepare(20, signedIn = false, route)
        val guestState = with(RouteWatchNavigation.Saver) { scope.save(navigation) }
        val restoredGuest = RouteWatchNavigation.Saver.restore(requireNotNull(guestState))!!
        restoredGuest.signedIn(21, resumeWatch = true)
        assertEquals(route, restoredGuest.prefill(21))
        val signedInState = with(RouteWatchNavigation.Saver) { scope.save(restoredGuest) }
        val restoredSignedIn = RouteWatchNavigation.Saver.restore(requireNotNull(signedInState))!!
        assertEquals(route, restoredSignedIn.prefill(21))
        assertNull(restoredSignedIn.prefill(22))
    }

    @Test fun processRestoreRebindsOnlyAnUnsignedGuestDraft() {
        ApiClient.resetForTest()
        val scope = object : SaverScope { override fun canBeSaved(value: Any) = true }
        val navigation = RouteWatchNavigation()
        navigation.prepare(20, signedIn = false, route)
        val saved = with(RouteWatchNavigation.Saver) { scope.save(navigation) }!!.toMutableList()
        saved[0] = "previous-process"
        val restored = RouteWatchNavigation.Saver.restore(saved)!!
        val next = ApiClient.queueSessionGeneration() + 1
        restored.signedIn(next, resumeWatch = true)
        assertEquals(route, restored.prefill(next))
        ApiClient.resetForTest()
    }
}
