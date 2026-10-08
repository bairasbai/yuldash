package com.yuldash.app

import android.app.PendingIntent
import android.content.Intent
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuldash.app.data.ApiClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit local fixture only; actual Android PendingIntent send to the production Activity. */
@RunWith(AndroidJUnit4::class)
class FreshNotificationRuntimeInstrumentedTest {
    @Test fun ownedMarkedPendingIntentReachesRealActivity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("freshNotificationAudit") == "runtime")
        val context = instrumentation.targetContext.applicationContext
        assertTrue(context is YuldashApplication)
        assertNull(ApiClient.testBaseUrl)
        assertEquals("http://127.0.0.1:5198", BuildConfig.YULDASH_API_BASE_URL)
        ApiClient.saveToken("header.eyJzdWIiOiIxMSJ9.signature")
        assertEquals(11, ApiClient.myUserId())
        assertTrue(context.getSharedPreferences("yuldash_prefs", 0).edit()
            .putBoolean("onboarding_completed", true).putBoolean("mode_hint_shown", true).commit())
        val initial = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(initial).use { scenario ->
            fun await(check: (YuldashViewModel) -> Boolean) {
                val ok = AtomicBoolean(); val end = SystemClock.uptimeMillis() + 15000
                while (!ok.get() && SystemClock.uptimeMillis() < end) {
                    scenario.onActivity { ok.set(check(ViewModelProvider(it)[YuldashViewModel::class.java])) }
                    SystemClock.sleep(50)
                }
                assertTrue("Expected navigation state not reached", ok.get())
            }
            await { it.screen.value == Screen.Home }
            val marked = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("type", "booking"); putExtra("id", "42"); putExtra("recipient_user_id", "11")
                putExtra(MainActivity.EXTRA_NAVIGATION_DELIVERY_ID, UUID.randomUUID().toString())
            }
            PendingIntent.getActivity(context, 42666, marked,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT).send()
            await { it.pendingBookingNavigation.value?.bookingId == 42 || it.activeBookingId.value == 42 }
            instrumentation.sendStatus(0, android.os.Bundle().apply {
                putBoolean("native_pendingIntent_accepted", true); putInt("synthetic_owner", 11)
                putString("scope", "Real PendingIntent/MainActivity/default VM; no fresh-create saved Bundle or process death assertion")
            })
        }
    }
}
