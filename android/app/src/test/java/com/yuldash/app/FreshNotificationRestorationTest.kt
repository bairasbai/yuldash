package com.yuldash.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/** Actual Activity/default VM/current Bundle. Delivery markers are synthetic; no real FCM. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FreshNotificationRestorationTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val controllers = mutableListOf<ActivityController<MainActivity>>()
    private val marker = "yuldash_navigation_delivery_id"
    @Before fun setup() {
        ApiClient.resetForTest(); ApiClient.init(context)
        ApiClient.saveToken("header.eyJzdWIiOiIxMSJ9.signature")
        clearBridges()
    }
    @After fun cleanup() {
        controllers.reversed().forEach { it.destroy() }
        clearBridges(); ApiClient.resetForTest()
    }
    private fun clearBridges() {
        DeepLink.pendingBookingChatId.value = null; DeepLink.pendingCompletedBookingId.value = null
        DeepLink.pendingRideId.value = null; NavSignals.openInstantChat.value = 0
        NavSignals.openInstantOrder.value = false; NavSignals.openDriverCabinet.value = false
        DeepLink.pendingParcels.value = false; DeepLink.pendingSupport.value = false
        NavSignals.openAdsCabinet.value = false; NavSignals.openPartnerCabinet.value = false
        DeepLink.pendingFairness.value = false; DeepLink.pendingRequestResponsesId.value = null
        DeepLink.pendingRequestsFeed.value = false; DeepLink.pendingApplicationScreen.value = null
    }
    private fun notification(type: String, id: Int = 43, delivery: String = "fresh-delivery", recipient: String = "11") =
        Intent(context, MainActivity::class.java).putExtra("type", type).putExtra("id", id.toString())
            .putExtra(marker, delivery).putExtra("recipient_user_id", recipient)
    private fun create(intent: Intent = Intent(context, MainActivity::class.java), state: Bundle? = null) =
        Robolectric.buildActivity(MainActivity::class.java, intent).also { controllers += it }.create(state)
    private fun vm(c: ActivityController<MainActivity>) = ViewModelProvider(c.get())[YuldashViewModel::class.java]
    private fun save(c: ActivityController<MainActivity>) = Bundle().also { c.saveInstanceState(it) }
    private fun restore(c: ActivityController<MainActivity>, intent: Intent) = save(c).let { clearBridges(); create(intent, it) }
    private fun deliver(c: ActivityController<MainActivity>, intent: Intent) {
        MainActivity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java).apply { isAccessible = true }.invoke(c.get(), intent)
    }
    private fun consumed(delivery: String = "original-delivery"): ActivityController<MainActivity> {
        val c = create(notification("booking_done", 42, delivery))
        val m = vm(c); assertNotNull(m.pendingBookingNavigation.value)
        assertTrue(m.consumePendingBooking(m.pendingBookingNavigation.value!!) {})
        m.screen.value = Screen.TripReceipt; m.activeBookingId.value = 42
        m.navHistory.add(Screen.ActiveTrip); m.persistNav()
        return c
    }
    private fun freshBooking(type: String, completed: Boolean = false) {
        val next = restore(consumed(), notification(type))
        assertEquals(43, vm(next).pendingBookingNavigation.value?.bookingId)
        assertEquals(completed, vm(next).pendingBookingNavigation.value?.completed)
        assertEquals(11, vm(next).pendingBookingNavigation.value?.ownerId)
    }
    private fun freshScreen(type: String, expected: Screen) {
        val next = restore(consumed(), notification(type))
        assertEquals(expected, vm(next).pendingScreenNavigation.value?.destination)
        assertEquals(43, vm(next).pendingScreenNavigation.value?.targetId)
        assertEquals(11, vm(next).pendingScreenNavigation.value?.ownerId)
    }
    @Test fun freshBookingOverSavedReceiptIsAccepted() = freshBooking("booking")
    @Test fun freshChatOverSavedReceiptIsAccepted() = freshBooking("chat")
    @Test fun freshCompletedOverSavedReceiptIsAccepted() = freshBooking("booking_done", true)
    @Test fun freshTaxiChatOverSavedReceiptIsAccepted() = freshScreen("order_chat", Screen.InstantChat)
    @Test fun freshTaxiOrderOverSavedReceiptIsAccepted() = freshScreen("instant_status", Screen.InstantOrder)
    @Test fun freshInstantOverSavedReceiptIsAccepted() = freshScreen("instant", Screen.InstantOrder)
    @Test fun freshPaymentOverSavedReceiptIsAccepted() = freshScreen("instant_payment", Screen.InstantOrder)
    @Test fun freshImComingOverSavedReceiptIsAccepted() = freshScreen("instant_im_coming", Screen.InstantOrder)
    @Test fun freshDebtOverSavedReceiptIsAccepted() = freshScreen("debt", Screen.DriverCabinet)
    @Test fun freshSupportOverSavedReceiptIsAccepted() = freshScreen("support", Screen.SupportTickets)
    @Test fun freshParcelsOverSavedReceiptIsAccepted() = freshScreen("parcel_chat", Screen.Parcels)
    @Test fun freshAdOverSavedReceiptIsAccepted() = freshScreen("ad", Screen.AdsCabinet)
    @Test fun freshPartnerOverSavedReceiptIsAccepted() = freshScreen("partner", Screen.PartnerCabinet)
    @Test fun freshIncidentOverSavedReceiptIsAccepted() = freshScreen("incident", Screen.FairnessCenter)
    @Test fun freshRequestOverSavedReceiptIsAccepted() = freshScreen("request", Screen.RequestResponses)
    @Test fun freshWatchOverSavedReceiptIsAccepted() = freshScreen("request_watch", Screen.RequestsFeed)
    @Test fun freshTaxiApplicationOverSavedReceiptIsAccepted() = freshScreen("taxi_apply", Screen.TaxiOnboarding)
    @Test fun freshCourierApplicationOverSavedReceiptIsAccepted() = freshScreen("courier_apply", Screen.CourierOnboarding)
    private fun freshFlag(flag: String, destination: Screen) {
        val i = notification("unused").apply { removeExtra("type"); putExtra(flag, true) }
        val next = restore(consumed(), i)
        assertEquals(destination, vm(next).pendingScreenNavigation.value?.destination)
    }
    @Test fun freshOfferFlagOverSavedReceiptIsAccepted() = freshFlag(TaxiOfferNotifier.EXTRA_OPEN_OFFER, Screen.DriverCabinet)
    @Test fun freshOrderFlagOverSavedReceiptIsAccepted() = freshFlag(TaxiOfferNotifier.EXTRA_OPEN_ORDER, Screen.InstantOrder)
    @Test fun freshParcelsFlagOverSavedReceiptIsAccepted() = freshFlag(com.yuldash.app.data.FcmService.EXTRA_OPEN_PARCELS, Screen.Parcels)
    @Test fun sameTargetNewDeliveryOverConsumedRouteIsAccepted() {
        val next = restore(consumed(), notification("booking_done", 42, "second-delivery"))
        assertEquals(42, vm(next).pendingBookingNavigation.value?.bookingId)
        assertTrue(vm(next).pendingBookingNavigation.value!!.completed)
    }
    @Test fun freshChatReplacesOlderPendingCompletedRecord() {
        val c = create(notification("booking_done", 42, "original-delivery"))
        val oldRevision = vm(c).privateNavigationRevision
        val next = restore(c, notification("chat", 44))
        assertEquals(44, vm(next).pendingBookingNavigation.value?.bookingId)
        assertFalse(vm(next).pendingBookingNavigation.value!!.completed)
        assertTrue(vm(next).privateNavigationRevision > oldRevision)
        assertNull(DeepLink.pendingCompletedBookingId.value)
    }
    @Test fun freshRequestReplacesOlderPendingChatRecord() {
        val c = create(notification("chat", 42, "original-delivery"))
        val next = restore(c, notification("request", 44))
        assertNull(vm(next).pendingBookingNavigation.value)
        assertEquals(44, vm(next).pendingScreenNavigation.value?.targetId)
    }
    @Test fun copiedOriginalDoesNotReplaceNewerPendingNotification() {
        val original = notification("booking_done", 42, "original-delivery")
        val c = create(Intent(original)); deliver(c, notification("chat", 44, "newer-delivery"))
        val revision = vm(c).privateNavigationRevision
        val next = restore(c, Intent(original))
        assertEquals(44, vm(next).pendingBookingNavigation.value?.bookingId)
        assertEquals(revision, vm(next).privateNavigationRevision)
    }
    @Test fun copiedConsumedDeliveryDoesNotReopenAfterLocalHomeChoice() {
        val c = consumed(); vm(c).navigateLocally { vm(c).screen.value = Screen.Home; vm(c).activeBookingId.value = null }
        val revision = vm(c).privateNavigationRevision
        val next = restore(c, notification("booking_done", 42, "original-delivery"))
        assertNull(vm(next).pendingBookingNavigation.value); assertEquals(Screen.Home, vm(next).screen.value)
        assertEquals(revision, vm(next).privateNavigationRevision)
    }
    @Test fun repeatedDeliveryOnNewIntentDoesNotReopenConsumedRoute() {
        val c = consumed(); val revision = vm(c).privateNavigationRevision
        deliver(c, notification("booking_done", 42, "original-delivery"))
        assertNull(vm(c).pendingBookingNavigation.value); assertEquals(revision, vm(c).privateNavigationRevision)
    }
    @Test fun foreignFreshDeliveryKeepsCurrentOwnerPending() {
        val c = create(notification("chat", 42, "original-delivery"))
        val revision = vm(c).privateNavigationRevision
        val next = restore(c, notification("request", 44, recipient = "12"))
        assertEquals(42, vm(next).pendingBookingNavigation.value?.bookingId)
        assertEquals(revision, vm(next).privateNavigationRevision)
    }
    @Test fun invalidFreshTargetDoesNotConsumeDeliveryIdentity() {
        val c = create(notification("chat", 42, "original-delivery"))
        val next = restore(c, notification("request", 0))
        assertEquals(42, vm(next).pendingBookingNavigation.value?.bookingId)
        val last = restore(next, notification("request", 44))
        assertEquals(44, vm(last).pendingScreenNavigation.value?.targetId)
    }
    @Test fun freshIdentifiedDeliveryWorksOverLegacyHandledBundle() {
        val legacy = notification("booking_done", 42).apply { removeExtra(marker) }
        val c = create(legacy); val m = vm(c)
        assertTrue(m.consumePendingBooking(m.pendingBookingNavigation.value!!) {})
        m.screen.value = Screen.TripReceipt; m.activeBookingId.value = 42; m.persistNav()
        val next = restore(c, notification("chat", 44))
        assertEquals(44, vm(next).pendingBookingNavigation.value?.bookingId)
    }
    @Test fun currentOwnerFreshDeliveryPassesAfterForeignSavedPendingCleanup() {
        val c = create(notification("chat", 42, "original-delivery"))
        ApiClient.saveToken("header.eyJzdWIiOiIxMiJ9.signature")
        val next = restore(c, notification("request", 44, recipient = "12"))
        assertNull(vm(next).pendingBookingNavigation.value)
        assertEquals(44, vm(next).pendingScreenNavigation.value?.targetId)
        assertEquals(12, vm(next).pendingScreenNavigation.value?.ownerId)
    }
    @Test fun malformedRecipientKeepsCurrentPendingAndRevision() {
        val c = create(notification("chat", 42, "original-delivery")); val revision = vm(c).privateNavigationRevision
        val next = restore(c, notification("request", 44, recipient = "invalid"))
        assertEquals(42, vm(next).pendingBookingNavigation.value?.bookingId)
        assertEquals(revision, vm(next).privateNavigationRevision)
    }
    @Test fun malformedDeliveryMarkerKeepsCurrentPendingAndRevision() {
        val c = create(notification("chat", 42, "original-delivery")); val revision = vm(c).privateNavigationRevision
        for (bad in listOf("", "x".repeat(129))) {
            val next = restore(c, notification("request", 44, delivery = bad))
            assertEquals(42, vm(next).pendingBookingNavigation.value?.bookingId)
            assertEquals(revision, vm(next).privateNavigationRevision)
        }
    }
    @Test fun rejectedRecipientOnNewIntentDoesNotConsumeValidDeliveryMarker() {
        val c = consumed()
        deliver(c, notification("request", 44, recipient = "12"))
        assertNull(vm(c).pendingScreenNavigation.value)
        deliver(c, notification("request", 44))
        assertEquals(44, vm(c).pendingScreenNavigation.value?.targetId)
    }
}
