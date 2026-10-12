package com.yuldash.app

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
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
import org.robolectric.annotation.Config

/** Actual FcmService -> shown PendingIntent -> Activity after account replacement. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PrivateNotificationOwnerTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val tokenA = "header.eyJzdWIiOiIxMSJ9.signature"; private val tokenB = "header.eyJzdWIiOiIxMiJ9.signature"
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    @Before fun setup() { org.robolectric.Shadows.shadowOf(context as Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS); ApiClient.resetForTest(); ApiClient.init(context); ApiClient.saveToken(tokenA); AppPrefs.setNotifications(context, true); manager.cancelAll(); clear() }
    @After fun cleanup() { manager.cancelAll(); clear(); ApiClient.resetForTest() }
    private fun clear() { NavSignals.openInstantOrder.value = false; NavSignals.openInstantChat.value = 0; NavSignals.openDriverCabinet.value = false; DeepLink.pendingSupport.value = false; DeepLink.pendingParcels.value = false; DeepLink.pendingBookingChatId.value = null; DeepLink.pendingCompletedBookingId.value = null }
    private fun shown(type: String): Intent {
        val c = Robolectric.buildService(FcmService::class.java).create()
        c.get().onMessageReceived(RemoteMessage.Builder("local-only").addData("recipient_user_id", "11").addData("title", "Local event").addData("type", type).addData("id", "43").addData("order_id", "43").build())
        val notification = shadowOf(manager).allNotifications.single(); c.destroy()
        return Intent(shadowOf(notification.contentIntent).savedIntent)
    }
    private fun dispatch(i: Intent) { val c = Robolectric.buildActivity(MainActivity::class.java).create(); MainActivity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java).apply { isAccessible = true }.invoke(c.get(), i); c.destroy() }
    private fun assertNone() { assertFalse(NavSignals.openInstantOrder.value); assertEquals(0, NavSignals.openInstantChat.value); assertFalse(NavSignals.openDriverCabinet.value); assertFalse(DeepLink.pendingSupport.value); assertFalse(DeepLink.pendingParcels.value); assertNull(DeepLink.pendingBookingChatId.value); assertNull(DeepLink.pendingCompletedBookingId.value) }
    private fun foreign(type: String) { val intent = shown(type); ApiClient.saveToken(tokenB); dispatch(intent); assertNone() }
    @Test fun shownTaxiStatusDoesNotOpenForNextAccount() = foreign("instant_status")
    @Test fun shownTaxiChatDoesNotOpenForNextAccount() = foreign("order_chat")
    @Test fun shownParcelsDoNotOpenForNextAccount() = foreign("parcel")
    @Test fun shownSupportDoesNotOpenForNextAccount() = foreign("support")
    @Test fun shownBookingDoesNotOpenForNextAccount() = foreign("booking")
    @Test fun shownCompletedDoesNotOpenForNextAccount() = foreign("booking_done")
    @Test fun rawForeignRecipientDoesNotOpenTaxi() { ApiClient.saveToken(tokenB); dispatch(Intent().putExtra("type", "instant_status").putExtra("id", "43").putExtra("recipient_user_id", "11")); assertNone() }
    @Test fun rawInvalidRecipientDoesNotOpenSupport() { dispatch(Intent().putExtra("type", "support").putExtra("id", "43").putExtra("recipient_user_id", "invalid")); assertNone() }
    @Test fun sameRecipientShownTaxiStillOpens() { dispatch(shown("instant_status")); assertTrue(NavSignals.openInstantOrder.value) }
    @Test fun shownSupportAfterLogoutDoesNotBecomeUnbound() { val intent = shown("support"); ApiClient.logout(); dispatch(intent); assertNone() }
    @Test fun shownTaxiOfferDoesNotOpenForNextAccount() = foreign("instant_offer")
    @Test fun sameRecipientShownTaxiOfferStillOpensCabinet() { dispatch(shown("instant_offer")); assertTrue(NavSignals.openDriverCabinet.value) }
}
