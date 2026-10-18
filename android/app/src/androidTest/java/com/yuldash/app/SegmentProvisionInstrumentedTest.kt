package com.yuldash.app

import android.os.Bundle
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuldash.app.data.ApiClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in provisioning for a real isolated loopback API. Never fabricates application or booking state. */
@RunWith(AndroidJUnit4::class)
class SegmentProvisionInstrumentedTest {
    @Test fun provisionOnly() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit isolated segment audit only", args.getString("segmentAudit") == "provision")
        val language = args.getString("auditLanguage")
        assertTrue(language == "Ru" || language == "Ba")
        val dark = args.getString("auditDark")
        assertTrue(dark == "true" || dark == "false")
        val token = requireNotNull(args.getString("qaToken")) { "Missing synthetic QA session" }
        val userId = requireNotNull(args.getString("qaUserId")?.toIntOrNull()) { "Missing synthetic QA owner" }
        assertTrue(userId > 0)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext.applicationContext
        assertTrue(context is YuldashApplication)
        assertEquals("http://127.0.0.1:5198", BuildConfig.YULDASH_API_BASE_URL)
        assertNull(ApiClient.testBaseUrl)
        assertFalse(ApiClient.secureStorageUnavailable)
        ApiClient.saveToken(token)
        assertTrue(ApiClient.isLoggedIn())
        assertEquals(userId, ApiClient.myUserId())
        assertTrue(context.getSharedPreferences("yuldash_prefs", 0).edit()
            .putBoolean("onboarding_completed", true).commit())
        assertTrue(context.getSharedPreferences("yuldash_theme", 0).edit().putBoolean("dark_override", dark == "true").commit())
        assertTrue(context.getSharedPreferences("yuldash_settings", 0).edit().putString("app_language", language).commit())
        assertEquals(AppLanguage.valueOf(language!!), AppPrefs.language(context))
        instrumentation.sendStatus(0, Bundle().apply {
            putInt("provision_pid", Process.myPid()); putInt("synthetic_owner", userId)
            putString("language", language); putBoolean("dark", dark == "true")
            putBoolean("disk_session_only", true); putBoolean("no_ui_or_saved_state", true)
        })
    }
}
