package com.yuldash.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LoginMethodsTest {
    @Test fun missingOrBrokenCacheFallsBackToTelegram() {
        assertEquals(listOf("telegram"), LoginMethods.decode(null))
        assertEquals(listOf("telegram"), LoginMethods.decode("not JSON"))
        assertEquals(listOf("telegram"), LoginMethods.decode("{}"))
    }

    @Test fun emptyServerListIsPreservedAndUnknownMethodsAreDiscarded() {
        assertEquals(emptyList<String>(), LoginMethods.decode("[]"))
        assertEquals(listOf("sms", "telegram"), LoginMethods.decode("""["future","telegram","sms","sms",17]"""))
        assertEquals(emptyList<String>(), LoginMethods.decode("""["future"]"""))
    }

    @Test fun cacheSurvivesReloadIncludingExplicitlyDisabledLogin() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("login_methods", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        try {
            LoginMethods.save(context, listOf("sms", "telegram", "future"))
            assertEquals(listOf("sms", "telegram"), LoginMethods.read(context))
            LoginMethods.save(context, emptyList())
            assertEquals(emptyList<String>(), LoginMethods.read(context))
        } finally { prefs.edit().clear().commit() }
    }

    @Test fun disabledCodeNeverMeansPhoneRequired() {
        val disabled = ApiException(403, "Способ выключен", "login_method_disabled")
        assertTrue(loginMethodDisabled(disabled))
        assertFalse(loginNeedsPhone(disabled))
        assertTrue(loginNeedsPhone(ApiException(403, "Нужен номер", "phone_required")))
        assertTrue(loginNeedsPhone(ApiException(403, "phone_required")))
        assertFalse(loginNeedsPhone(ApiException(403, "Другой отказ", "other_refusal")))
        assertFalse(loginNeedsPhone(IllegalStateException()))
    }
}
