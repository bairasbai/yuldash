package com.yuldash.app.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TripPassPaymentPersistenceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val plain = MemoryDiskPreferences()
    private val secure = MemoryDiskPreferences()
    @Before fun prepare() { TripPassStore.initStores(plain, secure) }
    @After fun cleanup() { TripPassStore.initStores(MemoryDiskPreferences(), null) }
    private fun pass(method: String = "cash", amount: Int? = 400, id: Int = 42) = TripPass.fromJson(JSONObject()
        .put("booking_id", id).put("price", 700).put("boarding_code", "4821")
        .put("pay_method", method).put("pay_amount", amount ?: JSONObject.NULL))
    private fun assertAgreement(json: JSONObject, method: String, amount: Int?) {
        assertEquals(method, json.optString("pay_method"))
        if (amount == null) assertTrue(json.isNull("pay_amount")) else assertEquals(amount, json.optInt("pay_amount", -1))
        assertEquals(700, json.optInt("price"))
    }
    private fun disk(store: MemoryDiskPreferences = secure) = JSONObject(requireNotNull(store.restarted().getString("pass_42", null)))

    @Test fun agreedCashAndOriginalPriceSurviveRoundTripAndDiskReopen() {
        assertTrue(TripPassStore.save(context, pass()))
        assertAgreement(disk(), "cash", 400)
        TripPassStore.initStores(plain.restarted(), secure.restarted())
        assertAgreement(requireNotNull(TripPassStore.load(context, 42)).toJson(), "cash", 400)
    }
    @Test fun explicitZeroIsNotReplacedByOriginalPrice() {
        assertTrue(TripPassStore.save(context, pass(amount = 0)))
        TripPassStore.initStores(plain.restarted(), secure.restarted())
        assertAgreement(requireNotNull(TripPassStore.load(context, 42)).toJson(), "cash", 0)
    }
    @Test fun nullAgreementIsDistinctFromZero() {
        assertTrue(TripPassStore.save(context, pass("negotiate", null)))
        assertAgreement(disk(), "negotiate", null)
    }
    @Test fun plainToSecureMigrationPreservesAgreementAndClearsOldCopy() {
        TripPassStore.initStores(plain, null)
        assertTrue(TripPassStore.save(context, pass()))
        TripPassStore.initStores(plain, secure)
        assertFalse(plain.restarted().contains("pass_42"))
        assertAgreement(disk(), "cash", 400)
        TripPassStore.initStores(plain.restarted(), secure.restarted())
        assertAgreement(requireNotNull(TripPassStore.load(context, 42)).toJson(), "cash", 400)
    }
    @Test fun refusedWriteKeepsOldDiskAgreementUntilExplicitRetrySucceeds() {
        assertTrue(TripPassStore.save(context, pass("sbp", 700)))
        secure.failWriteOf = "pass_42"
        assertFalse(TripPassStore.save(context, pass("cash", 400)))
        assertAgreement(disk(), "sbp", 700) // Separate durable state, not failed-write process memory.
        secure.failWriteOf = null
        assertTrue(TripPassStore.save(context, pass("cash", 400), retryMigration = true))
        TripPassStore.initStores(plain.restarted(), secure.restarted())
        assertAgreement(requireNotNull(TripPassStore.load(context, 42)).toJson(), "cash", 400)
    }
    @Test fun staleGenerationCannotReplaceStoredAgreement() {
        assertTrue(TripPassStore.save(context, pass("sbp", 700)))
        assertFalse(TripPassStore.save(context, pass("cash", 400),
            expectedGeneration = ApiClient.queueSessionGeneration() - 1))
        assertAgreement(disk(), "sbp", 700)
    }
    @Test fun boardingCodeUpdateRetainsPaymentMetadata() {
        assertTrue(TripPassStore.save(context, pass()))
        assertTrue(TripPassStore.updateBoardingCode(context, 42, "5932"))
        assertAgreement(disk(), "cash", 400)
        assertEquals("5932", disk().getString("boarding_code"))
    }
    @Test fun terminalDeletionRemovesOnlyItsAgreementAndRejectsLateSave() {
        assertTrue(TripPassStore.save(context, pass()))
        assertTrue(TripPassStore.save(context, pass("sbp", 900, id = 99)))
        assertEquals(TripPassStore.RemovalResult.CLEARED, TripPassStore.requestRemoval(context, 42))
        assertFalse(TripPassStore.save(context, pass()))
        TripPassStore.initStores(plain.restarted(), secure.restarted())
        assertNull(TripPassStore.load(context, 42))
        assertAgreement(requireNotNull(TripPassStore.load(context, 99)).toJson(), "sbp", 900)
    }
    @Test fun legacySnapshotKeepsUnknownMethodAndNullAgreement() {
        // Seed the raw old disk JSON, without first rewriting it through the new model.
        assertTrue(secure.edit().putString("pass_42", JSONObject().put("booking_id", 42)
            .put("price", 700).put("payment_note", "наличными").toString()).commit())
        TripPassStore.initStores(plain.restarted(), secure.restarted())
        val json = requireNotNull(TripPassStore.load(context, 42)).toJson()
        assertTrue(json.isNull("pay_method"))
        assertTrue(json.isNull("pay_amount"))
        assertEquals(700, json.getInt("price"))
    }
}
