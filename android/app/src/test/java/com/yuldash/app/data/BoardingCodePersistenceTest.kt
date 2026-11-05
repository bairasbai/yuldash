package com.yuldash.app.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BoardingCodePersistenceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val authority = TripPassAuthorityFixture()

    @Before fun authorizeSession() { authority.start() }

    @After fun closeSession() {
        awaitFixtureJobs()
        authority.close()
        TripPassStore.initStores(MemoryDiskPreferences(), null)
    }

    private fun awaitFixtureJobs() = runBlocking {
        // Keep the owned local origin/server until both process scopes have finished their jobs.
        val parents = listOf(
            ApiClient::class.java.getDeclaredField("bg").apply { isAccessible = true }.get(ApiClient),
            OfflineTripSnapshots::class.java.getDeclaredField("jobs").apply { isAccessible = true }.get(OfflineTripSnapshots),
        ).map { requireNotNull((it as CoroutineScope).coroutineContext[Job]) }
        try {
            withTimeout(10_000) {
                while (true) {
                    val children = parents.flatMap { it.children.toList() }
                    if (children.isEmpty()) break
                    children.forEach { it.join() }
                }
            }
        } catch (failure: TimeoutCancellationException) {
            throw AssertionError("Fixture jobs still running; local origin/server retained. Abort this isolated run.", failure)
        }
    }

    private fun pass(code: String, driverName: String = "Driver") = TripPass.fromJson(JSONObject()
        .put("booking_id", 42).put("boarding_code", code).put("driver_name", driverName))

    @Test fun failedWriteIsReportedAndOldDiskCodeSurvives() {
        val plain = MemoryDiskPreferences()
        TripPassStore.initStores(plain, null)
        assertTrue(TripPassStore.save(context, authority.authorizePositive(pass("1234", driverName = ""))))
        authority.authorizePositive(pass("5678", driverName = ""))
        assertEquals("1234", TripPassAuthorityFixture.durable(plain, 42)?.boardingCode)
        plain.failWriteOf = "pass_42"
        val result: Any = TripPassStore.updateBoardingCode(context, 42, "5678")
        assertEquals("Caller must learn that the code was not persisted", false, result)
        // The failed disk commit does not replace or revoke the current network projection.
        assertEquals("5678", TripPassStore.load(context, 42)?.boardingCode)
        TripPassStore.initStores(plain.restarted(), null)
        // Persistence is checked on durable bytes, independently of that live projection.
        assertEquals("1234", TripPassAuthorityFixture.durable(plain, 42)?.boardingCode)
        assertEquals("5678", TripPassStore.load(context, 42)?.boardingCode)
    }

    private fun seed(plain: MemoryDiskPreferences) {
        TripPassStore.initStores(plain, null)
        assertTrue(TripPassStore.save(context, authority.authorizePositive(pass("1234"))))
        assertEquals("1234", TripPassAuthorityFixture.durable(plain, 42)?.boardingCode)
    }

    @Test fun retryPersistsCodeAndKeepsOtherFields() {
        val plain = MemoryDiskPreferences()
        seed(plain)
        authority.authorizePositive(pass("5678"))
        assertEquals("1234", TripPassAuthorityFixture.durable(plain, 42)?.boardingCode)
        plain.failWriteOf = "pass_42"
        assertFalse(TripPassStore.updateBoardingCode(context, 42, "5678"))
        assertEquals("1234", TripPassAuthorityFixture.durable(plain, 42)?.boardingCode)
        plain.failWriteOf = null
        assertTrue(TripPassStore.updateBoardingCode(context, 42, "5678", retryMigration = true))
        TripPassStore.initStores(plain.restarted(), null)
        assertEquals("5678", TripPassStore.load(context, 42)?.boardingCode)
        assertEquals("Driver", TripPassStore.load(context, 42)?.driverName)
        assertEquals("5678", TripPassAuthorityFixture.durable(plain, 42)?.boardingCode)
        assertEquals("Driver", TripPassAuthorityFixture.durable(plain, 42)?.driverName)
    }

    @Test fun blankOrMissingPassportDoesNotClaimSuccessOrEraseCode() {
        val plain = MemoryDiskPreferences()
        seed(plain)
        assertFalse(TripPassStore.updateBoardingCode(context, 42, " "))
        assertFalse(TripPassStore.updateBoardingCode(context, 99, "5678"))
        assertEquals("1234", TripPassStore.load(context, 42)?.boardingCode)
        assertNull(TripPassStore.load(context, 99))
        assertEquals("1234", TripPassAuthorityFixture.durable(plain, 42)?.boardingCode)
        assertNull(TripPassAuthorityFixture.durable(plain, 99))
    }

    @Test fun lateSessionCannotWriteAnotherAccountsPassport() {
        val plain = MemoryDiskPreferences()
        seed(plain)
        assertFalse(TripPassStore.updateBoardingCode(context, 42, "5678",
            expectedGeneration = ApiClient.queueSessionGeneration() - 1, retryMigration = true))
        TripPassStore.initStores(plain.restarted(), null)
        assertEquals("1234", TripPassStore.load(context, 42)?.boardingCode)
        assertEquals("1234", TripPassAuthorityFixture.durable(plain, 42)?.boardingCode)
    }

    @Test fun removedPassportCannotBeRecreatedByCodeResponse() {
        val plain = MemoryDiskPreferences()
        seed(plain)
        TripPassStore.requestRemoval(context, 42)
        assertFalse(TripPassStore.updateBoardingCode(context, 42, "5678", retryMigration = true))
        TripPassStore.initStores(plain.restarted(), null)
        assertNull(TripPassStore.load(context, 42))
        assertNull(TripPassAuthorityFixture.durable(plain, 42))
    }

    @Test fun retryCompletesPendingPlainToSecureMigration() {
        val plain = MemoryDiskPreferences()
        val secure = MemoryDiskPreferences()
        seed(plain)
        authority.authorizePositive(pass("5678"))
        assertEquals("1234", TripPassAuthorityFixture.durable(plain, 42)?.boardingCode)
        plain.failRemovalOf = "pass_42"
        TripPassStore.initStores(plain, secure)
        assertTrue(plain.restarted().contains(OfflineMigration.JOURNAL))
        assertEquals("1234", TripPassAuthorityFixture.durable(plain, 42)?.boardingCode)
        assertEquals("1234", TripPassAuthorityFixture.durable(secure, 42)?.boardingCode)
        assertFalse(TripPassStore.updateBoardingCode(context, 42, "5678"))
        assertEquals("1234", TripPassAuthorityFixture.durable(secure, 42)?.boardingCode)
        plain.failRemovalOf = null
        assertTrue(TripPassStore.updateBoardingCode(context, 42, "5678", retryMigration = true))
        TripPassStore.initStores(plain.restarted(), secure.restarted())
        assertEquals("5678", TripPassStore.load(context, 42)?.boardingCode)
        assertEquals("5678", TripPassAuthorityFixture.durable(secure, 42)?.boardingCode)
        assertNull(TripPassAuthorityFixture.durable(plain, 42))
        assertFalse(plain.restarted().contains(OfflineMigration.JOURNAL))
    }

}
