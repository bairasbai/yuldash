package com.yuldash.app.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BoardingCodePersistenceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @Test fun failedWriteIsReportedAndOldDiskCodeSurvives() {
        val plain = MemoryDiskPreferences()
        TripPassStore.initStores(plain, null)
        assertTrue(TripPassStore.save(context, TripPass.fromJson(JSONObject().put("booking_id", 42).put("boarding_code", "1234"))))
        plain.failWriteOf = "pass_42"
        val result: Any = TripPassStore.updateBoardingCode(context, 42, "5678")
        assertEquals("Caller must learn that the code was not persisted", false, result)
        TripPassStore.initStores(plain.restarted(), null)
        assertEquals("1234", TripPassStore.load(context, 42)?.boardingCode)
    }
    private fun seed(plain: MemoryDiskPreferences) {
        TripPassStore.initStores(plain, null)
        assertTrue(TripPassStore.save(context, TripPass.fromJson(JSONObject().put("booking_id", 42)
            .put("boarding_code", "1234").put("driver_name", "Driver"))))
    }

    @Test fun retryPersistsCodeAndKeepsOtherFields() {
        val plain = MemoryDiskPreferences()
        seed(plain)
        plain.failWriteOf = "pass_42"
        assertFalse(TripPassStore.updateBoardingCode(context, 42, "5678"))
        plain.failWriteOf = null
        assertTrue(TripPassStore.updateBoardingCode(context, 42, "5678", retryMigration = true))
        TripPassStore.initStores(plain.restarted(), null)
        assertEquals("5678", TripPassStore.load(context, 42)?.boardingCode)
        assertEquals("Driver", TripPassStore.load(context, 42)?.driverName)
    }

    @Test fun blankOrMissingPassportDoesNotClaimSuccessOrEraseCode() {
        val plain = MemoryDiskPreferences()
        seed(plain)
        assertFalse(TripPassStore.updateBoardingCode(context, 42, " "))
        assertFalse(TripPassStore.updateBoardingCode(context, 99, "5678"))
        assertEquals("1234", TripPassStore.load(context, 42)?.boardingCode)
        assertNull(TripPassStore.load(context, 99))
    }

    @Test fun lateSessionCannotWriteAnotherAccountsPassport() {
        val plain = MemoryDiskPreferences()
        seed(plain)
        assertFalse(TripPassStore.updateBoardingCode(context, 42, "5678",
            expectedGeneration = ApiClient.queueSessionGeneration() - 1, retryMigration = true))
        TripPassStore.initStores(plain.restarted(), null)
        assertEquals("1234", TripPassStore.load(context, 42)?.boardingCode)
    }

    @Test fun removedPassportCannotBeRecreatedByCodeResponse() {
        val plain = MemoryDiskPreferences()
        seed(plain)
        TripPassStore.requestRemoval(context, 42)
        assertFalse(TripPassStore.updateBoardingCode(context, 42, "5678", retryMigration = true))
        TripPassStore.initStores(plain.restarted(), null)
        assertNull(TripPassStore.load(context, 42))
    }

    @Test fun retryCompletesPendingPlainToSecureMigration() {
        val plain = MemoryDiskPreferences()
        val secure = MemoryDiskPreferences()
        seed(plain)
        plain.failRemovalOf = "pass_42"
        TripPassStore.initStores(plain, secure)
        assertFalse(TripPassStore.updateBoardingCode(context, 42, "5678"))
        plain.failRemovalOf = null
        assertTrue(TripPassStore.updateBoardingCode(context, 42, "5678", retryMigration = true))
        TripPassStore.initStores(plain.restarted(), secure.restarted())
        assertEquals("5678", TripPassStore.load(context, 42)?.boardingCode)
    }

}
