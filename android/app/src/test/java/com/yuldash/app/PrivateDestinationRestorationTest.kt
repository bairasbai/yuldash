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

/** Real Activity dispatch/default VM/observed saved Bundle, not a simulated OS kill. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PrivateDestinationRestorationTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val controllers = mutableListOf<ActivityController<MainActivity>>()
    private val tokenA = "header.eyJzdWIiOiIxMSJ9.signature"
    private val tokenB = "header.eyJzdWIiOiIxMiJ9.signature"
    @Before fun setup() { ApiClient.resetForTest(); ApiClient.init(context); ApiClient.saveToken(tokenA); clear() }
    @After fun cleanup() { controllers.reversed().forEach { it.destroy() }; clear(); ApiClient.resetForTest() }
    private fun clear() {
        NavSignals.openDriverCabinet.value = false; NavSignals.openInstantOrder.value = false; NavSignals.openInstantChat.value = 0
        DeepLink.pendingParcels.value = false; DeepLink.pendingSupport.value = false
        DeepLink.pendingBookingChatId.value = null; DeepLink.pendingCompletedBookingId.value = null; DeepLink.pendingRideId.value = null
    }
    private fun intent(type: String, id: Int = 43) = Intent(context, MainActivity::class.java).putExtra("type", type).putExtra("id", id.toString()).putExtra("recipient_user_id", "11")
    private fun create(i: Intent = Intent(context, MainActivity::class.java), state: Bundle? = null) =
        Robolectric.buildActivity(MainActivity::class.java, i).also { controllers += it }.create(state)
    private fun vm(c: ActivityController<MainActivity>) = ViewModelProvider(c.get())[YuldashViewModel::class.java]
    private fun deliver(c: ActivityController<MainActivity>, i: Intent) =
        MainActivity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java).apply { isAccessible = true }.invoke(c.get(), i)
    private fun save(c: ActivityController<MainActivity>) = Bundle().also { c.saveInstanceState(it) }
    private fun receipt() = create().also { vm(it).screen.value = Screen.TripReceipt; vm(it).activeBookingId.value = 42; vm(it).recordNavigationChange(); vm(it).persistNav() }
    private fun pending(type: String) = when (type) {
        "order_chat" -> NavSignals.openInstantChat.value == 43
        "instant", "instant_status", "instant_payment", "instant_im_coming" -> NavSignals.openInstantOrder.value
        "support" -> DeepLink.pendingSupport.value
        "debt" -> NavSignals.openDriverCabinet.value
        else -> DeepLink.pendingParcels.value
    }
    private fun survival(type: String) {
        val old = receipt(); deliver(old, intent(type)); assertTrue(pending(type))
        val state = save(old); clear(); val next = create(state = state)
        assertNotSame(vm(old), vm(next)); assertEquals(Screen.TripReceipt, vm(next).screen.value); assertTrue("Lost $type", pending(type))
    }
    @Test fun taxiChatSurvivesSavedReceipt() = survival("order_chat")
    @Test fun taxiOrderSurvivesSavedReceipt() = survival("instant")
    @Test fun taxiStatusSurvivesSavedReceipt() = survival("instant_status")
    @Test fun taxiPaymentSurvivesSavedReceipt() = survival("instant_payment")
    @Test fun taxiComingSurvivesSavedReceipt() = survival("instant_im_coming")
    @Test fun parcelsSurviveSavedReceipt() = survival("parcel")
    @Test fun parcelChatPushKeepsExistingListDestination() = survival("parcel_chat")
    @Test fun supportSurvivesSavedReceipt() = survival("support")
    @Test fun driverCabinetSurvivesSavedReceipt() = survival("debt")
    @Test fun newestSupportReplacesTaxiChat() {
        val c = receipt(); deliver(c, intent("order_chat")); deliver(c, intent("support"))
        assertEquals(0, NavSignals.openInstantChat.value); assertTrue(DeepLink.pendingSupport.value)
    }
    @Test fun newestParcelsReplacePendingBooking() {
        val c = receipt(); deliver(c, intent("booking")); deliver(c, intent("parcel"))
        assertNull(DeepLink.pendingBookingChatId.value); assertTrue(DeepLink.pendingParcels.value)
    }
    @Test fun newestBookingReplacesPendingSupport() {
        val c = receipt(); deliver(c, intent("support")); deliver(c, intent("booking"))
        assertFalse(DeepLink.pendingSupport.value); assertEquals(43, DeepLink.pendingBookingChatId.value)
    }
    @Test fun clearUserDataImmediatelyRemovesTaxiChat() {
        val c = receipt(); deliver(c, intent("order_chat")); vm(c).clearUserData(); assertEquals(0, NavSignals.openInstantChat.value)
    }
    @Test fun clearUserDataImmediatelyRemovesParcels() {
        val c = receipt(); deliver(c, intent("parcel")); vm(c).clearUserData(); assertFalse(DeepLink.pendingParcels.value)
    }
    private fun clearedCopied(type: String) {
        val original = intent(type); val c = create(Intent(original)); vm(c).clearUserData(); ApiClient.logout()
        vm(c).screen.value = Screen.Login; vm(c).persistNav(); val state = save(c); clear(); create(Intent(original), state)
        assertFalse("Copied $type returned after logout", pending(type))
    }
    @Test fun copiedSupportDoesNotReturnAfterLogout() = clearedCopied("support")
    @Test fun copiedTaxiChatDoesNotReturnAfterLogout() = clearedCopied("order_chat")
    @Test fun copiedParcelsDoNotReturnAfterLogout() = clearedCopied("parcel")
    @Test fun loggedOutTaxiChatRejectedAcrossSaveAndNewAfterLoginAccepted() {
        ApiClient.logout(); val c = create(intent("order_chat")); vm(c).screen.value = Screen.Login; vm(c).persistNav()
        assertNull(vm(c).pendingScreenNavigation.value); val state = save(c); clear(); val next = create(state = state); assertFalse(pending("order_chat"))
        ApiClient.saveToken(tokenA); deliver(next, intent("order_chat")); assertTrue(pending("order_chat"))
    }
    @Test fun sameOwnerTokenReplacementKeepsSupport() {
        val c = receipt(); deliver(c, intent("support")); val state = save(c); ApiClient.saveToken(tokenA + "new")
        clear(); create(state = state); assertTrue(pending("support"))
    }
    @Test fun restoredSupportCannotCrossAccount() {
        val c = create(intent("support")); vm(c).screen.value = Screen.Login; vm(c).persistNav(); val state = save(c)
        ApiClient.saveToken(tokenB); clear(); create(intent("support"), state); assertFalse(pending("support"))
    }
    @Test fun freshTaxiChatWithoutSavedBundleIsAccepted() { create(intent("order_chat")); assertTrue(pending("order_chat")) }
    @Test fun invalidTaxiChatIdDoesNotReplaceSupport() {
        val c = receipt(); deliver(c, intent("support")); deliver(c, intent("order_chat", -1))
        assertTrue(pending("support")); assertEquals(0, NavSignals.openInstantChat.value)
    }
    private fun consumed(destination: Screen) {
        val c=receipt(); val viewModel=vm(c)
        viewModel.requestScreenDestination(destination,11,43)
        assertTrue(viewModel.consumePendingScreen(viewModel.pendingScreenNavigation.value!!) {
            viewModel.screen.value=destination
            if(destination==Screen.InstantChat) viewModel.instantChatOrderId.value=43 else viewModel.supportTicketId.value=43
        })
        val state=save(c);clear();val next=create(state=state)
        assertEquals(destination,vm(next).screen.value);assertNull(vm(next).pendingScreenNavigation.value)
        assertEquals(43,if(destination==Screen.InstantChat) vm(next).instantChatOrderId.value else vm(next).supportTicketId.value)
        assertTrue(vm(next).navHistory.contains(Screen.TripReceipt))
    }
    @Test fun consumedTaxiChatIdAndBackTrailSurviveBundle() = consumed(Screen.InstantChat)
    @Test fun consumedSupportTicketIdAndBackTrailSurviveBundle() = consumed(Screen.SupportTicket)
    @Test fun consumedParcelDoesNotReplayOverSavedHome() {
        val c=create(intent("parcel")); val viewModel=vm(c)
        assertTrue(viewModel.consumePendingScreen(viewModel.pendingScreenNavigation.value!!) { viewModel.screen.value=Screen.Home })
        val state=save(c);clear();val next=create(intent("parcel"),state)
        assertNull(vm(next).pendingScreenNavigation.value);assertFalse(DeepLink.pendingParcels.value)
        deliver(next,intent("parcel"));assertTrue(DeepLink.pendingParcels.value)
    }
}
