package com.yuldash.app

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.OfflineTripSnapshots
import com.yuldash.app.data.RateNotificationAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in system notification exercise on a disposable emulator with a local fixture API. */
@RunWith(AndroidJUnit4::class)
class RateNotificationRuntimeInstrumentedTest {
    @Test
    fun confirmationAndCancellationUseActualNotificationActions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("rateNotificationAudit") == "local")
        assertEquals("http://127.0.0.1:8000", BuildConfig.YULDASH_API_BASE_URL)
        val context = instrumentation.targetContext.applicationContext
        val manager = requireNotNull(context.getSystemService(NotificationManager::class.java))
        val keyguard = requireNotNull(context.getSystemService(KeyguardManager::class.java))
        assertTrue(manager.areNotificationsEnabled())
        assertTrue("Unlock the disposable emulator for the confirmation probe", !keyguard.isDeviceLocked)
        ApiClient.saveToken("header.eyJzdWIiOiIxMSJ9.synthetic")
        val owner = requireNotNull(OfflineTripSnapshots.currentOwner())
        val until = System.currentTimeMillis() / 1_000 + 600
        val booking = 27
        val id = "booking_done|$booking".hashCode()
        val oldLanguage = AppPrefs.language(context)
        val oldTheme = ThemePrefs.darkOverride
        try {
            for (language in AppLanguage.entries) for (dark in listOf(false, true)) {
                instrumentation.runOnMainSync {
                    AppPrefs.setLanguage(context, language)
                    persistThemeChoice(context, dark)
                }
                requireNotNull(RateNotificationAction.askAction(context, owner, booking, until).actionIntent).send()
                val limit = SystemClock.elapsedRealtime() + 5_000
                var prompt: Notification? = null
                while (prompt == null && SystemClock.elapsedRealtime() < limit) {
                    prompt = manager.activeNotifications.firstOrNull { it.id == id && it.notification.actions?.size == 2 }?.notification
                    if (prompt == null) SystemClock.sleep(30)
                }
                assertNotNull(prompt)
                val actual = requireNotNull(prompt)
                assertEquals(appTextFor(language, "Поставить 5 ★?", "5 ★ ҡуйырғамы?"),
                    actual.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
                assertTrue(actual.publicVersion.actions.isNullOrEmpty())
                if (Build.VERSION.SDK_INT >= 31) assertTrue(actual.actions.first().isAuthenticationRequired)
                actual.actions.last().actionIntent.send()
                val untilCancelled = SystemClock.elapsedRealtime() + 5_000
                while (manager.activeNotifications.any { it.id == id } && SystemClock.elapsedRealtime() < untilCancelled) {
                    SystemClock.sleep(30)
                }
                assertTrue(manager.activeNotifications.none { it.id == id })
            }
        } finally {
            manager.cancel(id)
            instrumentation.runOnMainSync {
                AppPrefs.setLanguage(context, oldLanguage)
                persistThemeChoice(context, oldTheme)
            }
        }
    }

    @Test
    fun confirmedFiveKeepsExistingThreeFromLocalServer() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("rateNotificationAudit") == "local")
        assertEquals("http://127.0.0.1:8000", BuildConfig.YULDASH_API_BASE_URL)
        val context = instrumentation.targetContext.applicationContext
        val manager = requireNotNull(context.getSystemService(NotificationManager::class.java))
        assertTrue(manager.areNotificationsEnabled())
        ApiClient.saveToken("header.eyJzdWIiOiIxMSJ9.synthetic")
        val owner = requireNotNull(OfflineTripSnapshots.currentOwner())
        val booking = 37
        val id = "booking_done|$booking".hashCode()
        try {
            requireNotNull(RateNotificationAction.askAction(context, owner, booking, System.currentTimeMillis() / 1_000 + 600).actionIntent).send()
            waitForNotification(manager, id) { it.actions?.size == 2 }.actions.first().actionIntent.send()
            val expected = appTextFor(AppPrefs.language(context), "Оценка уже стоит: 3 ★", "Баһа ҡуйылған: 3 ★")
            waitForNotification(manager, id) { it.extras.getCharSequence(Notification.EXTRA_TEXT).toString() == expected }
        } finally {
            manager.cancel(id)
        }
    }

    private fun waitForNotification(manager: NotificationManager, id: Int, matches: (Notification) -> Boolean): Notification {
        val deadline = SystemClock.elapsedRealtime() + 8_000
        while (SystemClock.elapsedRealtime() < deadline) {
            manager.activeNotifications.firstOrNull { it.id == id && matches(it.notification) }?.let { return it.notification }
            SystemClock.sleep(30)
        }
        error("Expected rating notification was not shown")
    }
}
