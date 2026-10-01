package com.yuldash.app.data

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A failed logout marker must not leave usable auth on a disk which can erase the identity. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AuthLogoutMarkerFailureTest {
    private lateinit var context: AuthContext
    private lateinit var secure: MemoryDiskPreferences

    private class AuthContext(base: Context) : ContextWrapper(base) {
        var plain = MemoryDiskPreferences()
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            if (name == SessionKeys.MAIN_PREFS) plain else super.getSharedPreferences(name, mode)
    }

    /**
     * commit remains available; apply changes process memory but its disk flush has not completed
     * before the simulated restart. The helper's removal fault implements that memory/disk split
     * only inside apply; it is restored before any later synchronous commit.
     */
    private class DeferredApplyPreferences(private val store: MemoryDiskPreferences) :
        SharedPreferences by store {
        override fun edit(): SharedPreferences.Editor {
            val editor = store.edit()
            return object : SharedPreferences.Editor by editor {
                override fun apply() {
                    val oldFault = store.failRemovalOf
                    try {
                        store.failRemovalOf = "token"
                        editor.commit()
                    } finally {
                        store.failRemovalOf = oldFault
                    }
                }
            }
        }
    }

    private fun setPreferenceField(name: String, value: Any?) {
        ApiClient::class.java.getDeclaredField(name).apply { isAccessible = true }.set(ApiClient, value)
    }

    @Before fun setup() {
        ApiClient.resetForTest()
        ApiClient.testBaseUrl = "http://127.0.0.1:9"
        ApiClient.testTimeoutMs = 100
        context = AuthContext(ApplicationProvider.getApplicationContext())
        ApiClient.init(context)
        ApiClient.saveToken("account-A")

        // Model an active fallback session, with a previously inaccessible secure store now
        // available for cleanup. No OTP/provider or server revocation is under test here.
        secure = MemoryDiskPreferences().apply {
            assertTrue(edit().putString("token", "account-A").putString("refresh_token", "A-refresh").commit())
        }
        assertTrue(context.plain.edit().putString(AuthStorePolicy.STATE_KEY, "plain")
            .putString("token", "account-A").putString("refresh_token", "A-refresh").commit())
        val selected = DeferredApplyPreferences(context.plain)
        setPreferenceField("prefs", selected)
        setPreferenceField("plainAuthPrefs", selected)
        setPreferenceField("secureAuthPrefs", secure)
        setPreferenceField("authStoreSource", "plain")
        assertEquals("account-A", ApiClient.currentToken())
    }

    @After fun cleanup() {
        context.plain.failWriteOf = null
        context.plain.edit().clear().commit()
        secure.edit().clear().commit()
        ApiClient.init(context)
        ApiClient.logout()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
    }

    private fun logoutAndRestart(failMarker: Boolean) {
        if (failMarker) context.plain.failWriteOf = AuthStorePolicy.STATE_KEY
        ApiClient.logout() // Exercises the real clearLocalSession path, including its apply fallback.
        assertFalse("The current process must leave account A", ApiClient.isLoggedIn())

        val diskPlain = context.plain.restarted()
        val diskSecure = secure.restarted()
        context.plain = diskPlain
        // Fresh disk objects discard unsuccessful edits. ApiClient.init is the production
        // startup path; this is still a JVM disk model, not a real Android process kill.
        ApiClient.init(context)

        assertFalse("Logout restored account A from disk after the marker write failed", ApiClient.isLoggedIn())
        assertNull("An available secure disk retained its old auth token", diskSecure.getString("token", null))
        assertNull("An available secure disk retained its old refresh token", diskSecure.getString("refresh_token", null))
    }

    @Test fun failedLogoutMarkerCannotRestoreAccountAfterFreshDiskInit() {
        logoutAndRestart(failMarker = true)
    }

    @Test fun successfulLogoutClearsBothDisksAndTheSameRestartModelRemainsGuest() {
        logoutAndRestart(failMarker = false)
    }
}
