package com.yuldash.app

import android.app.Notification
import android.app.NotificationManager
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.firebase.messaging.FirebaseMessaging
import com.yuldash.app.data.ApiClient
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in only; targets an unauthenticated emulator and a local API. */
@RunWith(AndroidJUnit4::class)
class LiveFcmProviderInstrumentedTest {
    private fun context() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun requireLocalProbe() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveFcm") == "true")
        assertTrue(Uri.parse(BuildConfig.YULDASH_API_BASE_URL).host in setOf("10.0.2.2", "127.0.0.1", "localhost"))
        assertFalse("Use an unauthenticated test installation", ApiClient.isLoggedIn())
    }

    @Test fun registerTestDevice() {
        requireLocalProbe()
        val target = File(context().filesDir, "audit-fcm-token.txt")
        target.delete()
        try {
            val token = Tasks.await(FirebaseMessaging.getInstance().token, 45, TimeUnit.SECONDS)
            assertTrue("Empty registration token", token.isNotBlank())
            target.writeText(token)
        } catch (error: Exception) {
            throw AssertionError("FCM registration failed (${error.javaClass.simpleName}); token omitted")
        }
    }

    @Test fun receiveTestNotification() {
        requireLocalProbe()
        val manager = context().getSystemService(NotificationManager::class.java)
        assertTrue("Notification permission is required on the test emulator", manager.areNotificationsEnabled())
        val marker = "Yuldash local FCM audit 20260909"
        val notification = manager.activeNotifications.firstOrNull {
            it.notification.extras.getString(Notification.EXTRA_TITLE) == marker
        }
        try {
            assertTrue("No notification from the real FCM delivery was found", notification != null)
            assertTrue(notification?.notification?.channelId == "yuldash_default")
        } finally {
            if (notification != null) manager.cancel(notification.id)
            File(context().filesDir, "audit-fcm-token.txt").delete()
        }
    }
}
