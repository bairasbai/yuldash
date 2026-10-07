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

/** Opt-in disk provisioning only. Root drives the real launcher/task outside instrumentation. */
@RunWith(AndroidJUnit4::class)
class CompletedProcessProvisionInstrumentedTest {
    @Test fun provisionOnly() {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue("Explicit isolated process audit only",args.getString("completedProcessAudit")=="provision")
        val language=args.getString("auditLanguage")
        assertTrue(language=="Ru" || language=="Ba")
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext.applicationContext
        assertTrue("Use the production Application and default AndroidJUnitRunner",context is YuldashApplication)
        assertEquals("http://127.0.0.1:5198",BuildConfig.YULDASH_API_BASE_URL)
        assertNull(ApiClient.testBaseUrl)
        assertFalse(ApiClient.secureStorageUnavailable)
        val token="header.eyJzdWIiOiIxMSJ9.signature"
        ApiClient.saveToken(token)
        assertEquals(token,ApiClient.currentToken())
        // No navigation, booking, review or SavedStateHandle is provisioned/copied.
        assertTrue(context.getSharedPreferences("yuldash_prefs",0).edit()
            .putBoolean("onboarding_completed",true).commit())
        assertTrue(context.getSharedPreferences("yuldash_settings",0).edit()
            .putString("app_language",language).commit())
        assertEquals(AppLanguage.valueOf(language!!),AppPrefs.language(context))
        instrumentation.sendStatus(0,Bundle().apply {
            putInt("provision_pid",Process.myPid());putString("language",language)
            putBoolean("disk_session_only",true);putBoolean("no_ui_or_saved_state",true)
        })
    }
}
