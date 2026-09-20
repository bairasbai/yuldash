package com.yuldash.app.data

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AuthStorePolicyTest {
    private lateinit var plain: SharedPreferences
    private lateinit var secure: SharedPreferences
    @Before fun setup() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        plain = app.getSharedPreferences("policy-plain", 0)
        secure = app.getSharedPreferences("policy-secure", 0)
        plain.edit().clear().commit()
        secure.edit().clear().commit()
    }
    private fun session(p: SharedPreferences, token: String, role: String = "passenger") {
        p.edit().putString("token", token).putString("refresh_token", "$token-refresh")
            .putString("user_name", token).putString("user_role", role)
            .putString("refresh_rotation_id", "$token-intent")
            .putString("refresh_rotation_token", "$token-refresh")
            .putString("push_token", "$token-push").commit()
    }
    private fun marker(value: String) { plain.edit().putString(AuthStorePolicy.STATE_KEY, value).commit() }
    private fun assertCleared(p: SharedPreferences) {
        SessionKeys.CLEARED_ON_LOGOUT.forEach { assertFalse("Retained $it", p.contains(it)) }
    }
    private fun failingCommit(p: SharedPreferences, failAt: Int): SharedPreferences = object : SharedPreferences by p {
        var commits = 0
        override fun edit(): SharedPreferences.Editor {
            val editor = p.edit()
            return object : SharedPreferences.Editor by editor {
                override fun commit(): Boolean = if (++commits == failAt) false else editor.commit()
            }
        }
    }
    @Test fun legacyPlainMigratesCompleteSessionAndRemovesPersonalData() {
        session(plain, "A", "driver")
        val result = AuthStorePolicy.resolve(plain, secure)
        assertSame(secure, result.prefs)
        assertEquals("driver", secure.getString("user_role", null))
        assertEquals("A-intent", secure.getString("refresh_rotation_id", null))
        assertEquals("A-refresh", secure.getString("refresh_rotation_token", null))
        assertEquals("A-push", secure.getString("push_token", null))
        assertCleared(plain)
    }
    @Test fun secureMarkerNeverRollsBackToStalePlain() {
        session(plain, "A"); session(secure, "B"); marker("secure")
        assertEquals("B", AuthStorePolicy.resolve(plain, secure).prefs.getString("token", null))
        assertCleared(plain)
    }
    @Test fun explicitFallbackReplacesOlderSecureIncludingRole() {
        session(plain, "B", "passenger"); session(secure, "A", "driver"); marker("plain")
        AuthStorePolicy.resolve(plain, secure)
        assertEquals("B", secure.getString("token", null))
        assertEquals("passenger", secure.getString("user_role", null))
    }
    @Test fun ambiguousLegacyAccountsFailClosed() {
        session(plain, "A"); session(secure, "B")
        AuthStorePolicy.resolve(plain, secure)
        assertCleared(plain); assertCleared(secure)
        assertEquals("logout", plain.getString(AuthStorePolicy.STATE_KEY, null))
    }
    @Test fun sameAccessButDifferentRefreshIsAlsoAmbiguous() {
        session(plain, "A"); session(secure, "A")
        secure.edit().putString("refresh_token", "new-refresh").commit()
        AuthStorePolicy.resolve(plain, secure)
        assertCleared(plain); assertCleared(secure)
    }
    @Test fun identicalLegacyTokensKeepSecureIdentity() {
        session(plain, "A", "passenger"); session(secure, "A", "driver")
        AuthStorePolicy.resolve(plain, secure)
        assertEquals("driver", secure.getString("user_role", null))
        assertCleared(plain)
    }
    @Test fun missingSecureDoesNotReadStalePlain() {
        session(plain, "A"); marker("secure")
        assertNull(AuthStorePolicy.resolve(plain, null).prefs.getString("token", null))
        assertEquals("secure", plain.getString(AuthStorePolicy.STATE_KEY, null))
    }
    @Test fun logoutWhileSecureUnavailableSurvivesRecovery() {
        session(secure, "A"); marker("secure")
        assertTrue(AuthStorePolicy.logout(plain, null, plain))
        AuthStorePolicy.resolve(plain, secure)
        assertCleared(secure); assertCleared(plain)
    }
    @Test fun pendingWriteNeverResurrectsEitherAccount() {
        session(plain, "A"); session(secure, "B"); marker("pending")
        AuthStorePolicy.resolve(plain, secure)
        assertCleared(secure); assertCleared(plain)
    }
    @Test fun writePersistsPendingBeforeTouchingTokens() {
        val saved = AuthStorePolicy.writeSession(plain, AuthStorePolicy.Selection(secure, "secure")) {
            assertEquals("pending", plain.getString(AuthStorePolicy.STATE_KEY, null))
            it.putString("token", "B")
        }
        assertTrue(saved)
        assertEquals("secure", plain.getString(AuthStorePolicy.STATE_KEY, null))
        assertEquals("B", secure.getString("token", null))
    }
    @Test fun failedWriteLeavesPendingAndRestartClearsOldSession() {
        session(secure, "A")
        assertFalse(AuthStorePolicy.writeSession(plain, AuthStorePolicy.Selection(secure, "secure")) {
            throw IllegalStateException("Injected disk failure")
        })
        assertEquals("pending", plain.getString(AuthStorePolicy.STATE_KEY, null))
        AuthStorePolicy.resolve(plain, secure)
        assertCleared(secure)
    }
    @Test fun logoutKeepsDeviceSettings() {
        session(plain, "A"); session(secure, "B")
        plain.edit().putString("app_language", "ba").commit()
        assertTrue(AuthStorePolicy.logout(plain, secure, secure))
        assertCleared(plain); assertCleared(secure)
        assertEquals("ba", plain.getString("app_language", null))
    }
    @Test fun failedPendingMarkerDoesNotTouchSecureSession() {
        session(secure, "A")
        assertFalse(AuthStorePolicy.writeSession(failingCommit(plain, 1), AuthStorePolicy.Selection(secure, "secure")) {
            it.putString("token", "B")
        })
        assertEquals("A", secure.getString("token", null))
    }
    @Test fun failedTokenCommitLeavesPending() {
        session(secure, "A")
        assertFalse(AuthStorePolicy.writeSession(plain, AuthStorePolicy.Selection(failingCommit(secure, 1), "secure")) {
            it.putString("token", "B")
        })
        assertEquals("pending", plain.getString(AuthStorePolicy.STATE_KEY, null))
        AuthStorePolicy.resolve(plain, secure)
        assertCleared(secure)
    }
    @Test fun failedFinalMarkerLeavesPendingAndCannotRestoreCommittedSession() {
        // plain commits: pending, stale-data cleanup, final authoritative marker.
        assertFalse(AuthStorePolicy.writeSession(failingCommit(plain, 3), AuthStorePolicy.Selection(secure, "secure")) {
            it.putString("token", "B")
        })
        assertEquals("pending", plain.getString(AuthStorePolicy.STATE_KEY, null))
        AuthStorePolicy.resolve(plain, secure)
        assertCleared(secure)
    }
    @Test fun failedMigrationCleanupCannotRollBackLaterSecureSession() {
        session(plain, "A"); marker("plain")
        // The secure authority marker is committed before clearing plaintext.
        assertThrows(IllegalStateException::class.java) {
            AuthStorePolicy.resolve(failingCommit(plain, 2), secure)
        }
        assertEquals("secure", plain.getString(AuthStorePolicy.STATE_KEY, null))
        session(secure, "B")
        assertEquals("B", AuthStorePolicy.resolve(plain, secure).prefs.getString("token", null))
        assertCleared(plain)
    }
}
