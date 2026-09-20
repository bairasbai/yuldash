package com.yuldash.app.data

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AuthStorageCorruptionTest {
    private lateinit var plain: SharedPreferences
    private lateinit var secure: SharedPreferences
    private lateinit var context: Context
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        plain = context.getSharedPreferences("yuldash", 0)
        secure = context.getSharedPreferences("corruption-secure", 0)
        plain.edit().clear().commit()
        secure.edit().clear().commit()
        ApiClient.resetForTest()
        ApiClient.testBaseUrl = "http://127.0.0.1:9"
    }
    @After fun cleanup() { ApiClient.resetForTest() }

    private fun guest(selection: AuthStorePolicy.Selection) {
        assertNull(selection.prefs.getString("token", null))
        assertEquals("logout", plain.getString(AuthStorePolicy.STATE_KEY, null))
        SessionKeys.CLEARED_ON_LOGOUT.forEach {
            assertFalse(plain.contains(it)); assertFalse(secure.contains(it))
        }
    }
    @Test fun corruptSecureRoleNeverPublishesHalfAnAccount() {
        plain.edit().putString(AuthStorePolicy.STATE_KEY, "secure").commit()
        secure.edit().putString("token", "A").putInt("user_role", 7).commit()
        guest(AuthStorePolicy.resolve(plain, secure))
    }
    @Test fun corruptLegacyTokenCanRecoverToNewLogin() {
        plain.edit().putInt("token", 7).commit()
        val selected = AuthStorePolicy.resolve(plain, secure)
        guest(selected)
        assertTrue(AuthStorePolicy.writeSession(plain, selected) { it.putString("token", "B") })
        assertEquals("B", AuthStorePolicy.resolve(plain, secure).prefs.getString("token", null))
    }
    @Test fun corruptMarkerDoesNotChooseEitherOldAccount() {
        plain.edit().putInt(AuthStorePolicy.STATE_KEY, 7).putString("token", "A").commit()
        secure.edit().putString("token", "B").commit()
        guest(AuthStorePolicy.resolve(plain, secure))
    }
    @Test fun unreadableEncryptedEntryIsRemovedWithoutRestoringPlainAccount() {
        plain.edit().putString(AuthStorePolicy.STATE_KEY, "secure").putString("token", "old").commit()
        secure.edit().putString("token", "unreadable").commit()
        val broken = object : SharedPreferences by secure {
            override fun getString(key: String?, defValue: String?): String? {
                if (key == "token" && secure.contains(key)) throw SecurityException("Invalid encrypted value")
                return secure.getString(key, defValue)
            }
        }
        guest(AuthStorePolicy.resolve(plain, broken))
    }
    @Test fun fallbackInitWithCorruptRoleStartsAsGuestAndCanSaveNewLogin() {
        plain.edit().putString(AuthStorePolicy.STATE_KEY, "plain")
            .putString("token", "A").putInt("user_role", 7).commit()
        ApiClient.init(context)
        assertFalse(ApiClient.isLoggedIn())
        assertNull(ApiClient.cachedRole())
        ApiClient.saveToken("B")
        ApiClient.init(context)
        assertEquals("B", ApiClient.currentToken())
    }
    @Test fun corruptMarkerInitDoesNotPermanentlyDisableLogin() {
        plain.edit().putInt(AuthStorePolicy.STATE_KEY, 7).putString("token", "A").commit()
        ApiClient.init(context)
        assertFalse(ApiClient.isLoggedIn())
        ApiClient.saveToken("B")
        ApiClient.init(context)
        assertEquals("B", ApiClient.currentToken())
    }
    @Test fun permanentlyUnreadableStoreMustNotBeReturnedToApiClient() {
        plain.edit().putString(AuthStorePolicy.STATE_KEY, "secure").commit()
        val broken = object : SharedPreferences by secure {
            override fun getString(key: String?, defValue: String?): String? =
                throw SecurityException("Store remains unreadable after cleanup")
        }
        assertTrue(runCatching { AuthStorePolicy.resolve(plain, broken) }.isFailure)
        assertEquals("logout", plain.getString(AuthStorePolicy.STATE_KEY, null))
    }
    @Test fun validSecureSessionIgnoresCorruptObsoletePlainFields() {
        plain.edit().putString(AuthStorePolicy.STATE_KEY, "secure").putInt("user_role", 7).commit()
        secure.edit().putString("token", "B").putString("user_role", "driver").commit()
        val selected = AuthStorePolicy.resolve(plain, secure)
        assertEquals("B", selected.prefs.getString("token", null))
        assertEquals("driver", selected.prefs.getString("user_role", null))
        assertFalse(plain.contains("user_role"))
    }
    @Test fun encryptedGetterReturningDefaultForWrongTypeStillRejectsIdentity() {
        plain.edit().putString(AuthStorePolicy.STATE_KEY, "secure").commit()
        secure.edit().putString("token", "A").putInt("user_role", 7).commit()
        val lenient = object : SharedPreferences by secure {
            override fun getString(key: String?, defValue: String?): String? =
                secure.all[key] as? String ?: defValue
        }
        guest(AuthStorePolicy.resolve(plain, lenient))
    }
}
