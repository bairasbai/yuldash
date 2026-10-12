package com.yuldash.app

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.messaging.RemoteMessage
import com.yuldash.app.data.FcmService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PushRoutingChainTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    @Before fun reset() {
        com.yuldash.app.data.ApiClient.saveToken("header.eyJzdWIiOiIyIn0.signature")
        manager.cancelAll()
        AppPrefs.setNotifications(context, true)
        NavSignals.openInstantOrder.value = false
        DeepLink.pendingApplicationScreen.value = null
        DeepLink.pendingRequestsFeed.value = false
        DeepLink.pendingRequestResponsesId.value = null
        DeepLink.pendingCompletedBookingId.value = null
    }

    private fun dispatch(intent: Intent) {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        MainActivity::class.java.getDeclaredMethod("handleNavIntent", Intent::class.java).apply {
            isAccessible = true
        }.invoke(activity, intent)
        controller.destroy()
    }

    private fun checkLocalNotification(type: String, idField: String = "id") {
        val service = Robolectric.buildService(FcmService::class.java).create().get()
        service.onMessageReceived(RemoteMessage.Builder("local-test").addData("recipient_user_id", "2")
            .addData("title", "Local taxi event")
            .addData("type", type).addData(idField, "42").build())
        val notification = shadowOf(manager).allNotifications.single()
        val intent = Intent(shadowOf(notification.contentIntent).savedIntent)
        dispatch(intent)
        assertTrue("Notification $type must open the taxi order", NavSignals.openInstantOrder.value)
        NavSignals.openInstantOrder.value = false
        dispatch(intent)
        assertFalse("Consumed notification must not reopen after recreation", NavSignals.openInstantOrder.value)
    }

    @Test fun statusNotificationOpensOrder() = checkLocalNotification("instant_status")
    @Test fun completedBookingNotificationOpensRatingDestinationOnce() {
        val service = Robolectric.buildService(FcmService::class.java).create().get()
        service.onMessageReceived(RemoteMessage.Builder("local-test").addData("recipient_user_id", "2")
            .addData("title", "Trip completed").addData("type", "booking_done").addData("id", "42").build())
        val intent = Intent(shadowOf(shadowOf(manager).allNotifications.single().contentIntent).savedIntent)
        dispatch(intent)
        assertEquals(42, DeepLink.pendingCompletedBookingId.value)
        assertNull(DeepLink.pendingBookingChatId.value)
        DeepLink.pendingCompletedBookingId.value = null
        dispatch(intent)
        assertNull(DeepLink.pendingCompletedBookingId.value)
    }
    @Test fun requestWatcherOpensFeedAndAuthorStillOpensResponses() {
        val service = Robolectric.buildService(FcmService::class.java).create().get()
        service.onMessageReceived(RemoteMessage.Builder("local-test").addData("recipient_user_id", "2")
            .addData("title", "Passenger on route").addData("type", "request_watch").addData("id", "42").build())
        val intent = Intent(shadowOf(shadowOf(manager).allNotifications.single().contentIntent).savedIntent)
        dispatch(intent)
        assertTrue(DeepLink.pendingRequestsFeed.value)
        assertNull(DeepLink.pendingRequestResponsesId.value)
        DeepLink.pendingRequestsFeed.value = false
        dispatch(intent)
        assertFalse(DeepLink.pendingRequestsFeed.value)
        dispatch(Intent().putExtra("type", "request").putExtra("id", "42").putExtra("recipient_user_id", "2"))
        assertEquals(42, DeepLink.pendingRequestResponsesId.value)
        assertFalse(DeepLink.pendingRequestsFeed.value)
    }
    private fun checkApplication(type: String, expected: Screen) {
        val service = Robolectric.buildService(FcmService::class.java).create().get()
        service.onMessageReceived(RemoteMessage.Builder("local-test").addData("recipient_user_id", "2")
            .addData("title", "Application decision").addData("type", type).addData("id", "42").build())
        val intent = Intent(shadowOf(shadowOf(manager).allNotifications.single().contentIntent).savedIntent)
        dispatch(intent)
        assertEquals(expected, DeepLink.pendingApplicationScreen.value)
        DeepLink.pendingApplicationScreen.value = null
        dispatch(intent)
        assertNull(DeepLink.pendingApplicationScreen.value)
    }
    @Test fun taxiApplicationOpensItsScreen() = checkApplication("taxi_apply", Screen.TaxiOnboarding)
    @Test fun courierApplicationOpensItsScreen() = checkApplication("courier_apply", Screen.CourierOnboarding)
    @Test fun paymentNotificationOpensOrder() = checkLocalNotification("instant_payment")
    @Test fun passengerComingNotificationOpensOrder() = checkLocalNotification("instant_im_coming")

    @Test fun serverOrderIdPayloadsOpenOrder() {
        for (type in listOf("instant_status", "instant_payment", "instant_im_coming")) {
            reset()
            checkLocalNotification(type, "order_id")
        }
    }

    @Test fun rawFcmExtrasOpenOrderForAllTaxiEvents() {
        for (type in listOf("instant_status", "instant_payment", "instant_im_coming")) {
            NavSignals.openInstantOrder.value = false
            dispatch(Intent().putExtra("type", type).putExtra("id", "42").putExtra("recipient_user_id", "2"))
            assertTrue("Raw FCM $type must open the taxi order", NavSignals.openInstantOrder.value)
        }
    }
}
