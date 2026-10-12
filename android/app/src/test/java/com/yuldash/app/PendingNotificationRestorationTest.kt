package com.yuldash.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/** Observed Activity saved Bundle, fresh Activity and cleared static bridge; not an OS kill. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PendingNotificationRestorationTest {
    private val controllers = mutableListOf<ActivityController<MainActivity>>()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val token11 = "header.eyJzdWIiOiIxMSJ9.signature"
    private val token12 = "header.eyJzdWIiOiIxMiJ9.signature"

    @Before fun setup() { ApiClient.resetForTest(); ApiClient.init(context); ApiClient.saveToken(token11); clearBridge() }
    @After fun cleanup() { controllers.reversed().forEach { it.destroy() }; clearBridge(); ApiClient.resetForTest() }
    private fun clearBridge() {
        DeepLink.pendingCompletedBookingId.value = null
        DeepLink.pendingBookingChatId.value = null
        DeepLink.pendingRideId.value = null
    }
    private fun notification(id: Int) = Intent(context, MainActivity::class.java)
        .putExtra("type", "booking_done").putExtra("id", id.toString()).putExtra("recipient_user_id", "11")
    private fun create(intent: Intent, state: Bundle? = null) =
        Robolectric.buildActivity(MainActivity::class.java, intent).also { controllers += it }.create(state)
    private fun vm(c: ActivityController<MainActivity>) = ViewModelProvider(c.get())[YuldashViewModel::class.java]
    private fun receipt(): ActivityController<MainActivity> {
        val c = create(notification(42)); val m = vm(c)
        m.screen.value = Screen.TripReceipt; m.activeBookingId.value = 42
        m.navHistory.add(Screen.ActiveTrip); m.persistNav(); clearBridge()
        return c
    }
    private fun deliver(c: ActivityController<MainActivity>, id: Int) {
        MainActivity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java)
            .apply { isAccessible = true }.invoke(c.get(), notification(id))
        assertEquals(id, DeepLink.pendingCompletedBookingId.value)
    }
    private fun snapshot(c: ActivityController<MainActivity>) = Bundle().also { c.saveInstanceState(it) }
    private fun restore(state: Bundle, intent: Intent = Intent(context, MainActivity::class.java)): ActivityController<MainActivity> {
        clearBridge(); return create(intent, state)
    }
    @Test fun unconsumedNewNotificationSurvivesReceiptSaveRestore() {
        val first = receipt(); deliver(first, 43); val second = restore(snapshot(first))
        assertNotSame(first.get(), second.get()); assertNotSame(vm(first), vm(second))
        assertEquals(Screen.TripReceipt, vm(second).screen.value)
        assertEquals(42, vm(second).activeBookingId.value)
        assertEquals(43, DeepLink.pendingCompletedBookingId.value)
    }
    @Test fun pendingNewNotificationOverridesCopiedOldInitialIntent() {
        val initial = notification(42); val c = create(Intent(initial)); val m = vm(c)
        m.screen.value = Screen.TripReceipt; m.activeBookingId.value = 42; m.persistNav(); clearBridge()
        deliver(c, 43); restore(snapshot(c), Intent(initial))
        assertEquals(43, DeepLink.pendingCompletedBookingId.value)
    }
    @Test fun newestNotificationBeforeSaveWins() {
        val c = receipt(); deliver(c, 43); deliver(c, 44); restore(snapshot(c))
        assertEquals(44, DeepLink.pendingCompletedBookingId.value)
    }
    @Test fun pendingNotificationSurvivesSameOwnerTokenReplacement() {
        val c = receipt(); deliver(c, 43); val state = snapshot(c)
        ApiClient.saveToken(token11 + "refreshed"); restore(state)
        assertEquals(43, DeepLink.pendingCompletedBookingId.value)
    }
    @Test fun pendingNotificationDoesNotCrossAccount() {
        val c = receipt(); deliver(c, 43); val state = snapshot(c)
        ApiClient.saveToken(token12); restore(state)
        assertNull(DeepLink.pendingCompletedBookingId.value)
    }
    @Test fun logoutClearsPendingBeforeSave() {
        val c = receipt(); deliver(c, 43); vm(c).clearUserData()
        vm(c).screen.value = Screen.Home; vm(c).persistNav(); ApiClient.logout()
        restore(snapshot(c)); assertNull(DeepLink.pendingCompletedBookingId.value)
    }
    @Test fun freshNotificationAfterRestoreStillOverridesPending() {
        val c = receipt(); deliver(c, 43); val second = restore(snapshot(c)); deliver(second, 44)
        assertEquals(44, DeepLink.pendingCompletedBookingId.value)
    }
    @Test fun loggedOutCompletedIsRejectedAcrossSaveAndNewAfterLoginAccepted() {
        ApiClient.logout(); val c = create(Intent(context, MainActivity::class.java)); vm(c).screen.value = Screen.Login
        vm(c).persistNav()
        MainActivity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java)
            .apply { isAccessible = true }.invoke(c.get(), notification(43))
        assertNull(vm(c).pendingBookingNavigation.value); assertEquals(0L, vm(c).privateNavigationRevision)
        val next = restore(snapshot(c)); assertNull(DeepLink.pendingCompletedBookingId.value)
        ApiClient.saveToken(token11); deliver(next, 43)
    }
}
