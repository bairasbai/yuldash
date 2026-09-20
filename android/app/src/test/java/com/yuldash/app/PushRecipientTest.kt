package com.yuldash.app

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.messaging.RemoteMessage
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.FcmService
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PushRecipientTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private fun token(user: Int) = "header." + java.util.Base64.getUrlEncoder().withoutPadding()
        .encodeToString("{\"sub\":\"$user\"}".toByteArray()) + ".signature"
    @Before fun setup() {
        ApiClient.resetForTest()
        ApiClient.saveToken(token(2))
        AppPrefs.setNotifications(context, true)
        manager.cancelAll()
    }
    @After fun cleanup() { ApiClient.resetForTest() }
    private fun receive(recipient: String?, type: String = "chat") {
        val message = RemoteMessage.Builder("local-test")
            .addData("title", "Synthetic private message").addData("body", "Only for its recipient")
            .addData("type", type).addData("id", "42").addData("order_id", "42")
        recipient?.let { message.addData("recipient_user_id", it) }
        Robolectric.buildService(FcmService::class.java).create().get().onMessageReceived(message.build())
    }
    @Test fun lateMessageForPreviousAccountIsDropped() {
        ApiClient.saveToken(token(1))
        ApiClient.saveToken(token(2))
        receive("1")
        Assert.assertTrue("Previous account text is shown to the new account", shadowOf(manager).allNotifications.isEmpty())
    }
    @Test fun messageForCurrentAccountIsShown() {
        receive("2")
        Assert.assertEquals(1, shadowOf(manager).allNotifications.size)
    }
    @Test fun missingOrMalformedRecipientIsNotGuessed() {
        for (recipient in listOf(null, "", "invalid", "-1", "0")) {
            manager.cancelAll()
            receive(recipient)
            Assert.assertTrue("Unverifiable recipient=$recipient was displayed", shadowOf(manager).allNotifications.isEmpty())
        }
    }
    @Test fun taxiOfferForPreviousAccountIsDroppedBeforeNotifier() {
        receive("1", "instant_offer")
        Assert.assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }
    @Test fun taxiOfferForCurrentAccountStillAppears() {
        receive("2", "instant_offer")
        Assert.assertEquals(1, shadowOf(manager).allNotifications.size)
    }
    @Test fun unreadableLocalIdentityDoesNotAcceptARecipient() {
        ApiClient.saveToken("invalid-session")
        receive("2")
        Assert.assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }
}
