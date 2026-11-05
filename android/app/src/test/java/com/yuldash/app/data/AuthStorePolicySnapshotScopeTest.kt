package com.yuldash.app.data

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test

/** Only synthetic in-memory identities. No app initialization, real auth preferences or token injection. */
class AuthStorePolicySnapshotScopeTest {
    private fun healthy(prefs: SharedPreferences) {
        assertTrue(prefs.edit().putString("token", "synthetic-access").putString("refresh_token", "synthetic-refresh")
            .putString("user_role", "passenger").commit())
    }
    @Test fun malformedOptionalScopeDoesNotDestroyHealthyLegacyIdentityDuringMigration() {
        val plain = MemoryDiskPreferences(); val secure = MemoryDiskPreferences()
        healthy(plain); plain.edit().putInt(SNAPSHOT_AUTH_SCOPE, 42).commit()
        val result = AuthStorePolicy.resolve(plain, secure)
        assertSame(secure, result.prefs); assertTrue(result.snapshotScopeRejected)
        assertEquals("synthetic-access", secure.getString("token", null))
        assertEquals("synthetic-refresh", secure.getString("refresh_token", null))
        assertEquals("passenger", secure.getString("user_role", null))
        assertNull(plain.getString("token", null)); assertNull(secure.getString(SNAPSHOT_AUTH_SCOPE, null))
    }
    @Test fun malformedScopeInSelectedSecureStoreDoesNotRollBackToPlainIdentity() {
        val plain = MemoryDiskPreferences(); val secure = MemoryDiskPreferences()
        healthy(secure); plain.edit().putString(AuthStorePolicy.STATE_KEY, "secure").commit()
        secure.edit().putInt(SNAPSHOT_AUTH_SCOPE, 42).commit()
        val result = AuthStorePolicy.resolve(plain, secure)
        assertSame(secure, result.prefs); assertTrue(result.snapshotScopeRejected)
        assertEquals("synthetic-access", secure.getString("token", null))
        assertNull(plain.getString("token", null))
    }
    @Test fun optionalScopeDecryptFailureDoesNotInvalidateAnOtherwiseHealthySecureIdentity() {
        val plain = MemoryDiskPreferences(); val secure = MemoryDiskPreferences()
        healthy(secure); plain.edit().putString(AuthStorePolicy.STATE_KEY, "secure").commit()
        val wrapped = object : SharedPreferences by secure {
            override fun getString(key: String?, defValue: String?): String? =
                if (key == SNAPSHOT_AUTH_SCOPE) throw SecurityException("synthetic scope decrypt failure") else secure.getString(key, defValue)
        }
        val result = AuthStorePolicy.resolve(plain, wrapped)
        assertTrue(result.snapshotScopeRejected)
        assertEquals("synthetic-access", result.prefs.getString("token", null))
        assertEquals("secure", plain.getString(AuthStorePolicy.STATE_KEY, null))
    }
    @Test fun unavailableEncryptionAndBadOptionalScopeKeepTheValidPlainIdentity() {
        val plain = MemoryDiskPreferences(); healthy(plain)
        plain.edit().putInt(SNAPSHOT_AUTH_SCOPE, 42).commit()
        val result = AuthStorePolicy.resolve(plain, null)
        assertSame(plain, result.prefs); assertTrue(result.snapshotScopeRejected)
        assertEquals("synthetic-access", plain.getString("token", null))
    }
    @Test fun corruptedActualIdentityStillUsesTheExistingStrictLogoutPolicy() {
        val plain = MemoryDiskPreferences()
        plain.edit().putInt("token", 42).putInt(SNAPSHOT_AUTH_SCOPE, 42).commit()
        val result = AuthStorePolicy.resolve(plain, null)
        assertNull(result.prefs.getString("token", null))
        assertNull(result.prefs.getString(SNAPSHOT_AUTH_SCOPE, null))
        assertEquals("logout", plain.getString(AuthStorePolicy.STATE_KEY, null))
    }
    @Test fun validOptionalScopeMigratesAndLogoutStillErasesItInBothStores() {
        val plain = MemoryDiskPreferences(); val secure = MemoryDiskPreferences(); healthy(plain)
        val scope = "00000000-0000-4000-8000-000000000007"
        plain.edit().putString(SNAPSHOT_AUTH_SCOPE, scope).commit()
        val result = AuthStorePolicy.resolve(plain, secure)
        assertFalse(result.snapshotScopeRejected); assertEquals(scope, secure.getString(SNAPSHOT_AUTH_SCOPE, null))
        assertTrue(AuthStorePolicy.logout(plain, secure, secure))
        assertFalse(plain.contains(SNAPSHOT_AUTH_SCOPE)); assertFalse(secure.contains(SNAPSHOT_AUTH_SCOPE))
        assertTrue("methods" in SessionKeys.SURVIVES_LOGOUT)
    }
}
