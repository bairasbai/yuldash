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

/** Actual dispatch/default VM/observed Bundle; cleared bridges simulate memory loss, not OS kill. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BookingNotificationRestorationTest {
    private val controllers = mutableListOf<ActivityController<MainActivity>>()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val a = "header.eyJzdWIiOiIxMSJ9.signature"; private val b = "header.eyJzdWIiOiIxMiJ9.signature"
    @Before fun setup() { ApiClient.resetForTest(); ApiClient.init(context); ApiClient.saveToken(a); clear() }
    @After fun cleanup() { controllers.reversed().forEach { it.destroy() }; clear(); ApiClient.resetForTest() }
    private fun clear() { DeepLink.pendingBookingChatId.value = null; DeepLink.pendingCompletedBookingId.value = null; DeepLink.pendingRideId.value = null }
    private fun intent(type: String, id: Int) = Intent(context, MainActivity::class.java).putExtra("type", type).putExtra("id", id.toString()).putExtra("recipient_user_id", "11")
    private fun create(i: Intent = Intent(context, MainActivity::class.java), s: Bundle? = null) = Robolectric.buildActivity(MainActivity::class.java, i).also { controllers += it }.create(s)
    private fun vm(c: ActivityController<MainActivity>) = ViewModelProvider(c.get())[YuldashViewModel::class.java]
    private fun receipt(): ActivityController<MainActivity> = create().also {
        vm(it).screen.value = Screen.TripReceipt; vm(it).activeBookingId.value = 42
        vm(it).navHistory.add(Screen.ActiveTrip); vm(it).persistNav()
    }
    private fun deliver(c: ActivityController<MainActivity>, type: String, id: Int) {
        MainActivity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java).apply { isAccessible = true }.invoke(c.get(), intent(type, id))
    }
    private fun saved(c: ActivityController<MainActivity>) = Bundle().also { c.saveInstanceState(it) }
    private fun restore(s: Bundle, i: Intent = Intent(context, MainActivity::class.java)) = clear().let { create(i, s) }
    @Test fun bookingSurvivesNewActivityOverSavedReceipt() {
        val c = receipt(); deliver(c, "booking", 43); val next = restore(saved(c))
        assertNotSame(vm(c), vm(next)); assertEquals(Screen.TripReceipt, vm(next).screen.value)
        assertEquals(42, vm(next).activeBookingId.value); assertEquals(43, DeepLink.pendingBookingChatId.value)
    }
    @Test fun chatSurvivesNewActivityOverSavedReceipt() {
        val c = receipt(); deliver(c, "chat", 43); restore(saved(c)); assertEquals(43, DeepLink.pendingBookingChatId.value)
    }
    @Test fun newestChatOverridesPreviousCompletedDestination() {
        val c = receipt(); deliver(c, "booking_done", 43); deliver(c, "chat", 44); restore(saved(c))
        assertEquals(44, DeepLink.pendingBookingChatId.value); assertNull(DeepLink.pendingCompletedBookingId.value)
    }
    @Test fun newestCompletedOverridesPreviousChatDestination() {
        val c = receipt(); deliver(c, "chat", 43); deliver(c, "booking_done", 44); restore(saved(c))
        assertEquals(44, DeepLink.pendingCompletedBookingId.value); assertNull(DeepLink.pendingBookingChatId.value)
    }
    @Test fun newPendingChatBeatsCopiedOldCompletedIntent() {
        val c = receipt(); deliver(c, "chat", 43); restore(saved(c), intent("booking_done", 42))
        assertEquals(43, DeepLink.pendingBookingChatId.value); assertNull(DeepLink.pendingCompletedBookingId.value)
    }
    @Test fun sameOwnerTokenReplacementKeepsPendingBooking() {
        val c = receipt(); deliver(c, "booking", 43); val s = saved(c); ApiClient.saveToken(a + "new")
        restore(s); assertEquals(43, DeepLink.pendingBookingChatId.value)
    }
    @Test fun ownedChatDoesNotCrossAccountAtRestore() {
        val c = receipt(); deliver(c, "chat", 43); val s = saved(c); ApiClient.saveToken(b)
        restore(s); assertNull(DeepLink.pendingBookingChatId.value); assertNull(DeepLink.pendingCompletedBookingId.value)
    }
    @Test fun loggedOutBookingIsRejectedAndNewAfterLoginAccepted() {
        ApiClient.logout(); val c = create(intent("booking", 43)); vm(c).screen.value = Screen.Login; vm(c).persistNav()
        assertNull(vm(c).pendingBookingNavigation.value); val next = restore(saved(c)); assertNull(DeepLink.pendingBookingChatId.value)
        ApiClient.saveToken(a); deliver(next, "booking", 43); assertEquals(43, DeepLink.pendingBookingChatId.value)
    }
    @Test fun clearUserDataImmediatelyRemovesChatBridge() {
        val c = receipt(); deliver(c, "chat", 43); vm(c).clearUserData(); assertNull(DeepLink.pendingBookingChatId.value)
    }
    @Test fun clearUserDataPreventsReplayingCopiedIntentAfterLoginSave() {
        val original = intent("chat", 43); val c = create(Intent(original)); vm(c).clearUserData(); ApiClient.logout()
        vm(c).screen.value = Screen.Login; vm(c).persistNav(); restore(saved(c), Intent(original))
        assertNull(DeepLink.pendingBookingChatId.value); assertNull(DeepLink.pendingCompletedBookingId.value)
    }
    @Test fun invalidFreshIdDoesNotReplaceValidChat() {
        val c = receipt(); deliver(c, "chat", 43); deliver(c, "booking", -1); assertEquals(43, DeepLink.pendingBookingChatId.value)
    }
    @Test fun coldChatWithoutSavedBundleIsAccepted() {
        val c = create(intent("chat", 43)); assertEquals(43, vm(c).pendingBookingNavigation.value?.bookingId)
        assertEquals(43, DeepLink.pendingBookingChatId.value)
    }
    @Test fun freshSameIdIntentAfterClearedRestoreIsAcceptedWithNewRevision() {
        val c = create(intent("chat", 43)); val revision = vm(c).pendingBookingNavigation.value!!.revision
        vm(c).clearUserData(); vm(c).screen.value = Screen.Login; vm(c).persistNav()
        val next = restore(saved(c), intent("chat", 43)); assertNull(vm(next).pendingBookingNavigation.value)
        deliver(next, "chat", 43); assertTrue(vm(next).pendingBookingNavigation.value!!.revision > revision)
    }
    @Test fun savedClearedBookingDoesNotReplay() = clearedRestore("booking")
    @Test fun savedClearedCompletedDoesNotReplay() = clearedRestore("booking_done")
    private fun clearedRestore(type: String) {
        val c = create(intent(type, 43)); vm(c).clearUserData(); vm(c).screen.value = Screen.Login; vm(c).persistNav()
        val next = restore(saved(c), intent(type, 43)); assertNull(vm(next).pendingBookingNavigation.value)
        assertNull(DeepLink.pendingBookingChatId.value); assertNull(DeepLink.pendingCompletedBookingId.value)
    }
    @Test fun privateReplaySuppressionDoesNotBlockPublicRideUri() {
        val c = create(intent("chat", 43)); vm(c).clearUserData(); vm(c).screen.value = Screen.Login; vm(c).persistNav()
        restore(saved(c), intent("chat", 43).setData(android.net.Uri.parse("https://yulbash.ru/r/77")))
        assertNull(DeepLink.pendingBookingChatId.value); assertEquals(77, DeepLink.pendingRideId.value)
    }
    @Test fun consumedNotificationDoesNotReplayOverSavedHomeWithoutBooking() {
        val c = create(intent("chat", 43)); val pending = vm(c).pendingBookingNavigation.value!!
        assertTrue(vm(c).consumePendingBooking(pending) {})
        vm(c).screen.value = Screen.Home; vm(c).activeBookingId.value = null; vm(c).persistNav()
        restore(saved(c), intent("chat", 43)); assertNull(DeepLink.pendingBookingChatId.value)
    }
}
