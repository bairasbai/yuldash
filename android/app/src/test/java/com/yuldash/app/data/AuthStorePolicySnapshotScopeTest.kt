package com.yuldash.app.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AuthStorePolicySnapshotScopeTest {
    @Test fun optionalSnapshotScopeTypeFailureDoesNotDestroyAValidLegacyLogin() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val plain = app.getSharedPreferences("scope-policy-plain", 0)
        val secure = app.getSharedPreferences("scope-policy-secure", 0)
        plain.edit().clear().putString("token", "valid-access").putString("refresh_token", "valid-refresh")
            .putInt(OfflineTripSnapshots.AUTH_SCOPE, 42).commit()
        secure.edit().clear().commit()
        val resolved = AuthStorePolicy.resolve(plain, secure)
        assertEquals("valid-access", resolved.prefs.getString("token", null))
        assertEquals("valid-refresh", resolved.prefs.getString("refresh_token", null))
        assertTrue(resolved.snapshotScopeRejected)
    }
    @Test fun corruptedRealTokenStillUsesTheExistingFailClosedPolicy() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val plain = app.getSharedPreferences("scope-policy-bad-auth", 0)
        plain.edit().clear().putInt("token", 42).putString(OfflineTripSnapshots.AUTH_SCOPE, "bad-scope").commit()
        val resolved = AuthStorePolicy.resolve(plain, null)
        assertNull(resolved.prefs.getString("token", null))
    }
}
