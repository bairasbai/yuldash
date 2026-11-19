package com.yuldash.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.FilterCity
import com.yuldash.app.data.OfflineTripSnapshots
import com.yuldash.app.data.TripPassAuthorityFixture
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearchHistoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val disk get() = context.getSharedPreferences(SearchHistory.PREFS, Context.MODE_PRIVATE)
    private val ur = FilterCity("Ургаза", "Урғаҙа")
    private val ak = FilterCity("Акъяр", "Аҡъяр")
    private val route = SearchRoute(listOf(ur), listOf(ak))
    private val partial = SearchRoute(listOf(ur), emptyList())

    @Before fun prepare() {
        ApiClient.resetForTest()
        ApiClient.testBaseUrl = "http://127.0.0.1:1"
        disk.edit().clear().commit()
        ApiClient.init(context)
    }

    @After fun reset() {
        ApiClient.resetForTest()
        disk.edit().clear().commit()
    }

    @Test fun refiningWithinTwoMinutesAndReplayingAnEntryAreDifferentOperations() {
        val first = SearchHistory.record(emptyList(), partial, 100)
        assertEquals(listOf(route), SearchHistory.record(first, route, 120_099).map { it.route })
        assertEquals(listOf(route, partial), SearchHistory.record(first, route, 120_100).map { it.route })
        val narrowed = SearchHistory.record(SearchHistory.record(emptyList(), route, 100), partial, 200)
        assertEquals(listOf(route, partial), SearchHistory.record(narrowed, route, 300, refine = false).map { it.route })
        assertEquals(narrowed, SearchHistory.record(narrowed, SearchRoute(emptyList(), emptyList()), 400))
    }

    @Test fun identicalCitiesUseTheSameKeyAndOnlyFiveRoutesAreKept() {
        val a = SearchRoute(listOf(FilterCity("Орёл", "Орёл"), ur), listOf(ak))
        val b = SearchRoute(listOf(ur, FilterCity("ОРЕЛ", "Орёл")), listOf(ak))
        assertEquals(a.key, b.key)
        val rows = (1..8).fold(emptyList<SearchHistoryEntry>()) { list, index ->
            SearchHistory.record(list, SearchRoute(listOf(FilterCity("Йорт $index", "Йорт $index")), emptyList()), index.toLong())
        }
        assertEquals(5, rows.size)
        assertEquals("Йорт 8", rows.first().route.from.single().ru)
    }

    @Test fun guestOnlyKeepsHistoryInMemoryAndAStaleGuestCannotApplyIt() {
        val guest = NearbySearchHistoryState(context, ApiClient.queueSessionGeneration())
        guest.record(route)
        assertEquals(route, guest.entries.single().route)
        assertFalse(disk.contains(SearchHistory.KEY))
        val restarted = NearbySearchHistoryState(context, ApiClient.queueSessionGeneration())
        restarted.load()
        assertTrue(restarted.entries.isEmpty())
        ApiClient.saveToken(TripPassAuthorityFixture.jwt(7))
        var applied = false
        guest.repeat(route.key) { applied = true }
        assertFalse(applied)
        assertFalse(disk.contains(SearchHistory.KEY))
    }

    @Test fun savedRouteSurvivesInitForOneLoginAndOldActionsDoNotReachAnotherLogin() {
        ApiClient.saveToken(TripPassAuthorityFixture.jwt(7))
        val old = NearbySearchHistoryState(context, ApiClient.queueSessionGeneration())
        old.record(route)
        val saved = disk.getString(SearchHistory.KEY, null)
        assertNotNull(saved)
        val owner = requireNotNull(OfflineTripSnapshots.currentOwner())
        assertEquals(route, SearchHistory.decode(saved, owner.scope).single().route)
        assertTrue(SearchHistory.decode(saved, "different-owner").isEmpty())
        assertTrue(SearchHistory.decode("{", owner.scope).isEmpty())
        ApiClient.init(context)
        val restored = NearbySearchHistoryState(context, ApiClient.queueSessionGeneration())
        restored.load()
        assertEquals(route, restored.entries.single().route)

        ApiClient.saveToken(TripPassAuthorityFixture.jwt(8))
        assertFalse(disk.contains(SearchHistory.KEY))
        val next = NearbySearchHistoryState(context, ApiClient.queueSessionGeneration())
        next.record(partial)
        val current = disk.getString(SearchHistory.KEY, null)
        restored.record(route)
        restored.remove(partial.key)
        restored.clear()
        var applied = false
        restored.repeat(route.key) { applied = true }
        assertFalse(applied)
        assertEquals(current, disk.getString(SearchHistory.KEY, null))
        assertEquals(partial, next.entries.single().route)
        next.remove(partial.key)
        assertFalse(disk.contains(SearchHistory.KEY))
        assertTrue(next.showHistoryChip)
        assertTrue(next.entries.isEmpty())
        ApiClient.logout()
        val loggedOut = NearbySearchHistoryState(context, ApiClient.queueSessionGeneration())
        loggedOut.load()
        assertFalse(loggedOut.showHistoryChip)
        assertTrue(loggedOut.entries.isEmpty())
    }
}
