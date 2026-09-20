package com.yuldash.app

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.messaging.RemoteMessage
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.FcmService
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PushLogoutPrivacyTest {
    @Test fun latePushAfterLogoutDoesNotRevealPreviousAccountMessage() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val manager = context.getSystemService(NotificationManager::class.java)
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("{\"ok\":true}"))
        server.start()
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        try {
            ApiClient.init(context)
            ApiClient.saveToken("header.eyJzdWIiOiIyIn0.signature")
            AppPrefs.setNotifications(context, true)
            ApiClient.logout()
            val service = Robolectric.buildService(FcmService::class.java).create().get()
            service.onMessageReceived(RemoteMessage.Builder("local-test").addData("recipient_user_id", "2")
                .addData("title", "Previous account message")
                .addData("body", "Synthetic private text")
                .addData("type", "chat").addData("id", "42").build())
            assertTrue("Late push reveals previous account message after logout",
                shadowOf(manager).allNotifications.isEmpty())
        } finally {
            server.shutdown()
            ApiClient.testBaseUrl = null
        }
    }
    @Test fun logoutRemovesDeliveredNotificationsWithoutRemovingChannels() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val manager = context.getSystemService(NotificationManager::class.java)
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("{\"ok\":true}"))
        server.start()
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        try {
            ApiClient.init(context)
            ApiClient.saveToken("header.eyJzdWIiOiIyIn0.signature")
            AppPrefs.setNotifications(context, true)
            manager.cancelAll()
            val service = Robolectric.buildService(FcmService::class.java).create().get()
            for (type in listOf("chat", "booking")) {
                service.onMessageReceived(RemoteMessage.Builder("local-test").addData("recipient_user_id", "2")
                    .addData("title", "Previous account message")
                    .addData("body", "Synthetic private text")
                    .addData("type", type).addData("id", "42").build())
            }
            assertEquals(2, shadowOf(manager).allNotifications.size)
            ApiClient.logout()
            assertTrue("Previous account notifications remain visible after logout",
                shadowOf(manager).allNotifications.isEmpty())
            assertTrue(manager.notificationChannels.isNotEmpty())
        } finally {
            server.shutdown()
            ApiClient.testBaseUrl = null
        }
    }
}
