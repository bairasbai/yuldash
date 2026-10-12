package com.yuldash.app

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual auth boundary and publication state; no simulated session predicate or network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TaxiNavigationOwnershipTest {
    private var generation = 0L
    private val first = Any()
    private val second = Any()
    @Before fun setup() {
        ApiClient.resetForTest()
        ApiClient.init(ApplicationProvider.getApplicationContext<Context>())
        ApiClient.saveToken("header.eyJzdWIiOiIxMSJ9.signature")
        generation = ApiClient.queueSessionGeneration()
        NavSignals.activeTaxiTrip.value = 0
        NavSignals.taxiOrderOnScreen.value = false
        NavSignals.taxiTripOnScreen.value = false
        assertTrue(TaxiNavigationState.claimScreen(generation, first))
    }
    @After fun cleanup() {
        NavSignals.activeTaxiTrip.value = 0
        TaxiNavigationState.releaseScreen(generation, first)
        TaxiNavigationState.releaseScreen(generation, second)
        ApiClient.logout()
        ApiClient.resetForTest()
    }
    private fun publish(publisher: Any = first, phase: String = "enroute", id: Int? = 91) =
        TaxiNavigationState.publishScreen(generation, publisher, phase, id)
    private fun receipt() = TaxiNavigationState.currentTrip()!!

    @Test fun checkingAndRestoreErrorKeepMinimizedTrip() {
        assertTrue(publish()); val active = receipt()
        TaxiNavigationState.releaseScreen(generation, first)
        TaxiNavigationState.claimScreen(generation, second)
        assertTrue(publish(second, "checking", null)); assertSame(active, receipt())
        assertTrue(publish(second, "restoreError", null)); assertSame(active, receipt())
        assertFalse(TaxiNavigationState.tripOnScreen())
    }
    @Test fun definitiveEmptyRestorationClearsTrip() {
        publish(); assertTrue(publish(phase = "picker", id = null))
        assertNull(TaxiNavigationState.currentTrip())
    }
    @Test fun repeatedStatusRetainsPublicationIdentity() {
        publish(); val active = receipt(); publish()
        assertSame(active, receipt())
    }
    @Test fun newScreenWithSameIdGetsNewReceipt() {
        publish(); val old = receipt()
        TaxiNavigationState.claimScreen(generation, second); publish(second)
        assertNotSame(old, receipt()); assertFalse(TaxiNavigationState.finishTrip(old))
        assertEquals(91, receipt().orderId)
    }
    @Test fun oldScreenCannotPublishOrReleaseNewScreen() {
        publish(); TaxiNavigationState.claimScreen(generation, second); publish(second)
        val current = receipt()
        assertFalse(publish(first, "done")); TaxiNavigationState.releaseScreen(generation, first)
        assertSame(current, receipt()); assertTrue(TaxiNavigationState.tripOnScreen())
        assertTrue(TaxiNavigationState.orderOnScreen())
    }
    @Test fun matchingTerminalClearsTripAndPresence() {
        publish(); assertTrue(publish(phase = "done"))
        assertNull(TaxiNavigationState.currentTrip()); assertFalse(TaxiNavigationState.tripOnScreen())
        assertFalse(TaxiNavigationState.orderOnScreen())
    }
    @Test fun terminalForDifferentIdCannotClearTrip() {
        publish(); val active = receipt(); publish(phase = "done", id = 92)
        assertSame(active, receipt())
    }
    @Test fun currentScreenReleaseKeepsTripButRestoresMenus() {
        publish(); val active = receipt(); TaxiNavigationState.releaseScreen(generation, first)
        assertSame(active, receipt()); assertFalse(TaxiNavigationState.tripOnScreen())
        assertFalse(TaxiNavigationState.orderOnScreen())
    }
    @Test fun sameFrameAccountChangeHidesAllOldState() {
        publish(); val old = receipt()
        ApiClient.saveToken("header.eyJzdWIiOiIxMiJ9.signature")
        assertNull(TaxiNavigationState.currentTrip()); assertEquals(0, NavSignals.activeTaxiTrip.value)
        assertFalse(TaxiNavigationState.tripOnScreen()); assertFalse(TaxiNavigationState.orderOnScreen())
        assertFalse(TaxiNavigationState.claimScreen(generation, first))
        assertFalse(publish()); assertFalse(TaxiNavigationState.finishTrip(old))
    }
    @Test fun staleReleaseAndTerminalCannotAlterNewAccount() {
        publish(); val old = receipt()
        ApiClient.saveToken("header.eyJzdWIiOiIxMiJ9.signature")
        val newGeneration = ApiClient.queueSessionGeneration()
        assertTrue(TaxiNavigationState.claimScreen(newGeneration, second))
        assertTrue(TaxiNavigationState.publishScreen(newGeneration, second, "enroute", 91))
        val current = receipt()
        TaxiNavigationState.releaseScreen(generation, first)
        assertFalse(TaxiNavigationState.finishTrip(old)); assertSame(current, receipt())
        assertTrue(TaxiNavigationState.tripOnScreen())
        TaxiNavigationState.releaseScreen(newGeneration, second)
    }
    @Test fun sameFrameLogoutRejectsTripAction() {
        publish(); val active = receipt(); ApiClient.logout()
        var called = false
        assertFalse(TaxiNavigationState.runIfCurrentTrip(active) { called = true })
        assertFalse(called); assertEquals(0, NavSignals.activeTaxiTrip.value)
        assertFalse(NavSignals.taxiOrderOnScreen.value); assertFalse(NavSignals.taxiTripOnScreen.value)
    }
    @Test fun sameNumberAfterZeroRejectsOldActionAndAcceptsCurrentAction() {
        publish(); val old = receipt()
        NavSignals.activeTaxiTrip.value = 0; NavSignals.activeTaxiTrip.value = 91
        var calls = 0
        assertFalse(TaxiNavigationState.runIfCurrentTrip(old) { calls++ })
        assertFalse(TaxiNavigationState.finishTrip(old))
        assertTrue(TaxiNavigationState.runIfCurrentTrip(receipt()) { calls++ })
        assertEquals(1, calls)
    }
    @Test fun finishedPublicationCannotBeRevivedByItsOldPublisher() {
        publish(); val active = receipt()
        assertTrue(TaxiNavigationState.finishTrip(active))
        assertFalse(publish())
        assertNull(TaxiNavigationState.currentTrip())
        assertFalse(TaxiNavigationState.tripOnScreen())
        assertFalse(TaxiNavigationState.orderOnScreen())
        TaxiNavigationState.claimScreen(generation, second)
        assertTrue(publish(second)); assertEquals(91, receipt().orderId)
    }
    @Test fun compatibilityVisibilityWritesKeepFinishedPublicationRetired() {
        publish(); assertTrue(TaxiNavigationState.finishTrip(receipt()))
        NavSignals.taxiOrderOnScreen.value = false
        NavSignals.taxiTripOnScreen.value = false
        assertFalse(publish())
        assertNull(TaxiNavigationState.currentTrip())
        assertFalse(TaxiNavigationState.tripOnScreen())
    }
}
