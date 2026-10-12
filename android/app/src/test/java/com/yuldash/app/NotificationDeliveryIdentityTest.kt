package com.yuldash.app

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.messaging.RemoteMessage
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.FcmService
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.util.UUID

/** Real shown notification/PendingIntent; local RemoteMessage fixture, not remote FCM. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NotificationDeliveryIdentityTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val activities = mutableListOf<ActivityController<MainActivity>>()
    @Before fun setup() {
        shadowOf(context as Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        ApiClient.resetForTest(); ApiClient.init(context); ApiClient.saveToken("header.eyJzdWIiOiIxMSJ9.signature")
        AppPrefs.setNotifications(context, true); manager.cancelAll()
        clear()
    }
    @After fun cleanup() {
        activities.reversed().forEach { it.destroy() }; manager.cancelAll(); clear(); ApiClient.resetForTest()
    }
    private fun clear() {
        DeepLink.pendingBookingChatId.value = null; DeepLink.pendingCompletedBookingId.value = null
        NavSignals.openDriverCabinet.value = false
    }
    private fun shown(offer: Boolean): Notification {
        val c = Robolectric.buildService(FcmService::class.java).create()
        try {
            c.get().onMessageReceived(RemoteMessage.Builder("local-only")
                .addData("recipient_user_id", "11").addData("title", "Local event")
                .addData("type", if (offer) "instant_offer" else "booking_done")
                .addData("id", "42").addData("order_id", "42").build())
            return shadowOf(manager).allNotifications.single()
        } finally { c.destroy() }
    }
    private fun intent(n: Notification) = Intent(shadowOf(n.contentIntent).savedIntent)
    private fun marker(i: Intent): String {
        val id = i.getStringExtra(MainActivity.EXTRA_NAVIGATION_DELIVERY_ID)
        assertNotNull(id); assertEquals(id, UUID.fromString(id).toString()); return id!!
    }
    private fun create(i: Intent, state: Bundle? = null) =
        Robolectric.buildActivity(MainActivity::class.java, i).also { activities += it }.create(state)
    private fun vm(c: ActivityController<MainActivity>) = ViewModelProvider(c.get())[YuldashViewModel::class.java]
    private fun repeated(offer: Boolean) {
        val first = shown(offer); val original = intent(first); val firstMarker = marker(original)
        val second = shown(offer); val latest = intent(second)
        assertEquals(first.contentIntent, second.contentIntent)
        assertNotEquals(firstMarker, marker(latest)); assertEquals(firstMarker, marker(Intent(original)))
        assertEquals("11", latest.getStringExtra("recipient_user_id"))
    }
    @Test fun fcmReplacementHasNewMarkerAndRetainsCopiedOriginalMarker() = repeated(false)
    @Test fun taxiReplacementHasNewMarkerAndRetainsCopiedOriginalMarker() = repeated(true)
    private fun freshOverBundle(offer: Boolean) {
        val original = intent(shown(offer)); val c = create(Intent(original)); val m = vm(c)
        if (offer) assertTrue(m.consumePendingScreen(m.pendingScreenNavigation.value!!) {})
        else assertTrue(m.consumePendingBooking(m.pendingBookingNavigation.value!!) {})
        m.screen.value = Screen.TripReceipt; m.activeBookingId.value = 42; m.persistNav()
        val state = Bundle().also { c.saveInstanceState(it) }; val fresh = intent(shown(offer)); clear()
        val next = create(fresh, state)
        if (offer) assertEquals(Screen.DriverCabinet, vm(next).pendingScreenNavigation.value?.destination)
        else assertEquals(42, vm(next).pendingBookingNavigation.value?.bookingId)
        assertTrue(vm(next).privateNavigationRevision > m.privateNavigationRevision)
    }
    @Test fun actualFcmSameTargetFreshMarkerOverSavedReceiptIsAccepted() = freshOverBundle(false)
    @Test fun actualTaxiSameTargetFreshMarkerOverSavedReceiptIsAccepted() = freshOverBundle(true)
}
