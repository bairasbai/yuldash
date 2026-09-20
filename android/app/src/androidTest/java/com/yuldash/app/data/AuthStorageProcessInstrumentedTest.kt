package com.yuldash.app.data

import android.content.Context
import android.os.Process
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** Each seed/verify phase is a separate am instrument process, separated by force-stop.
 * Only fake local credentials; invoke exclusively with StorageAuditRunner.
 */
@RunWith(AndroidJUnit4::class)
class AuthStorageProcessInstrumentedTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val plain get() = ctx.getSharedPreferences("yuldash", Context.MODE_PRIVATE)
    private val probe get() = ctx.getSharedPreferences("qa_storage_probe", Context.MODE_PRIVATE)
    private fun secure(name: String = "yuldash_secure") = EncryptedSharedPreferences.create(
        ctx, name, MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
    private val nonce = "ab".repeat(32)
    private val api = "http://127.0.0.1:5189"
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun qa(path: String): JSONObject {
        val connection = URL(api + path).openConnection() as HttpURLConnection
        connection.connectTimeout = 3000; connection.readTimeout = 3000
        return try {
            check(connection.responseCode == 200)
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }

    private fun seedRefresh() = runBlocking {
        assertTrue(plain.edit().clear().commit()); assertTrue(secure().edit().clear().commit())
        val pair = qa("/qa/session")
        ApiClient.testBaseUrl = api; ApiClient.testTimeoutMs = 3000
        ApiClient.init(ctx)
        assertFalse(ApiClient.secureStorageUnavailable)
        ApiClient.saveToken("qa-expired-access")
        assertTrue(secure().edit().putString("refresh_token", pair.getString("refresh_token")).commit())
        ApiClient.init(ctx)
        qa("/qa/drop-next-refresh")
        assertTrue("Successful server response must be lost", ApiClient.getConsents().exceptionOrNull() is java.io.IOException)
        val intent = requireNotNull(secure().getString("refresh_rotation_id", null))
        assertTrue(intent.matches(Regex("[0-9a-f]{64}")))
        val stats = qa("/qa/stats")
        assertEquals(2, stats.getInt("rows")); assertEquals(1, stats.getInt("active"))
        assertTrue(probe.edit().clear().putInt("pid", Process.myPid()).putString("mode", "refresh")
            .putString("intent_hash", hash(intent)).putString("refresh_hash", hash(pair.getString("refresh_token"))).commit())
    }

    private fun verifyRefresh() = runBlocking {
        assertEquals("refresh", probe.getString("mode", null))
        assertNotEquals(probe.getInt("pid", -1), Process.myPid())
        ApiClient.testBaseUrl = api; ApiClient.testTimeoutMs = 3000
        ApiClient.init(ctx)
        assertFalse(ApiClient.secureStorageUnavailable)
        val encrypted = secure()
        assertEquals(probe.getString("intent_hash", null), hash(requireNotNull(encrypted.getString("refresh_rotation_id", null))))
        assertEquals(probe.getString("refresh_hash", null), hash(requireNotNull(encrypted.getString("refresh_token", null))))
        ApiClient.getConsents().getOrThrow()
        val stats = qa("/qa/stats")
        assertEquals(2, stats.getInt("rows")); assertEquals(1, stats.getInt("active"))
        assertEquals(stats.getString("active_hash"), hash(requireNotNull(encrypted.getString("refresh_token", null))))
        assertFalse(encrypted.contains("refresh_rotation_id")); assertFalse(encrypted.contains("refresh_rotation_token"))
    }

    private fun seed(mode: String) {
        assertTrue(plain.edit().clear().commit())
        val encrypted = secure()
        assertTrue(encrypted.edit().clear().commit())
        assertTrue(probe.edit().clear().putInt("pid", Process.myPid()).putString("mode", mode).commit())
        when (mode) {
            "durable_kill" -> {
                ApiClient.testBaseUrl = "http://127.0.0.1:9"
                ApiClient.init(ctx)
                ApiClient.saveToken("qa-durable-owner")
                assertTrue(TripPassStore.save(ctx, TripPass.fromJson(JSONObject().put("booking_id", 7))))
                assertTrue(Outbox.enqueue(ctx, Outbox.newMessage(7, "QA durable message")))
                // No test-side commit or orderly app shutdown after the product write.
                InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
                    putBoolean("durable_saved_before_kill", true)
                    putInt("process_id", Process.myPid())
                })
                Process.killProcess(Process.myPid())
            }
            "offline_cache" -> {
                ApiClient.testBaseUrl = "http://127.0.0.1:9"
                ApiClient.init(ctx)
                ApiClient.saveToken("qa-next-owner")
                val passPlain = ctx.getSharedPreferences("yuldash_trippass_v2", 0)
                val queuePlain = ctx.getSharedPreferences("yuldash_outbox_v2", 0)
                TripPassStore.initStores(passPlain, secure("yuldash_trippass_secure_v2"))
                Outbox.initStores(queuePlain, secure("yuldash_outbox_secure_v2"))
                TripPassStore.save(ctx, TripPass.fromJson(JSONObject().put("booking_id", 1)))
                Outbox.enqueue(ctx, Outbox.newMessage(1, "QA previous owner"))
                // Model an unavailable store without damaging the device's actual Keystore.
                TripPassStore.initStores(passPlain, null)
                Outbox.initStores(queuePlain, null)
                TripPassStore.clearAll(); Outbox.clearAll()
                TripPassStore.save(ctx, TripPass.fromJson(JSONObject().put("booking_id", 2)))
                Outbox.enqueue(ctx, Outbox.newMessage(2, "QA next owner"))
                assertNotNull(TripPassStore.load(ctx, 2))
                assertEquals(1, Outbox.count(ctx, 2))
                // Establish persisted input before testing recovery across process death.
                assertTrue(passPlain.edit().commit())
                assertTrue(queuePlain.edit().commit())
                assertTrue(passPlain.contains("pass_2"))
                assertTrue(passPlain.getBoolean(OfflineStoreReset.PENDING, false))
                assertTrue(queuePlain.getBoolean(OfflineStoreReset.PENDING, false))
            }
            "migration" -> assertTrue(plain.edit().putString("token", "qa-legacy-access")
                .putString("refresh_token", "qa-legacy-refresh").putString("user_name", "QA Driver")
                .putString("user_role", "driver").putString("refresh_rotation_id", nonce)
                .putString("refresh_rotation_token", "qa-legacy-refresh").commit())
            "authority" -> {
                assertTrue(encrypted.edit().putString("token", "qa-current-access")
                    .putString("refresh_token", "qa-current-refresh").putString("user_role", "driver").commit())
                assertTrue(plain.edit().putString("token", "qa-stale-access")
                    .putString("refresh_token", "qa-stale-refresh").putString("user_role", "passenger")
                    .putString("auth_store_state", "secure").commit())
            }
            "logout" -> {
                assertTrue(encrypted.edit().putString("token", "qa-revoked-access")
                    .putString("refresh_token", "qa-revoked-refresh").commit())
                assertTrue(plain.edit().putString("auth_store_state", "logout").commit())
            }
            "corruption", "personal_reset" -> {
                if (mode == "personal_reset") {
                    ApiClient.testBaseUrl = "http://127.0.0.1:9"
                    ApiClient.init(ctx)
                    ApiClient.saveToken("qa-previous-owner")
                    TripPassStore.save(ctx, TripPass.fromJson(JSONObject().put("booking_id", 77)
                        .put("driver_name", "QA Previous Owner")))
                    Outbox.enqueue(ctx, Outbox.newMessage(77, "QA previous owner's message"))
                    ctx.getSharedPreferences("trip_location_svc", 0).edit().putInt("last_booking", 77).commit()
                }
                assertTrue(encrypted.edit().putString("token", "qa-corrupt-access")
                    .putString("refresh_token", "qa-corrupt-refresh").putInt("user_role", 7).commit())
                assertTrue(plain.edit().putString("auth_store_state", "secure").commit())
            }
            else -> error("Unknown seed mode")
        }
    }

    private fun verify(mode: String) {
        assertEquals(mode, probe.getString("mode", null))
        assertNotEquals("Must run in a new OS process", probe.getInt("pid", -1), Process.myPid())
        ApiClient.testBaseUrl = "http://127.0.0.1:9"
        ApiClient.init(ctx)
        assertFalse("Real Android Keystore must be available", ApiClient.secureStorageUnavailable)
        val encrypted = secure()
        assertTrue(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            .containsAlias(MasterKey.DEFAULT_MASTER_KEY_ALIAS))
        when (mode) {
            "durable_kill" -> {
                assertEquals("qa-durable-owner", ApiClient.currentToken())
                assertNotNull(TripPassStore.load(ctx, 7))
                assertEquals(1, Outbox.count(ctx, 7))
            }
            "offline_cache" -> {
                assertEquals("qa-next-owner", ApiClient.currentToken())
                assertNull(TripPassStore.load(ctx, 1))
                assertNotNull(TripPassStore.load(ctx, 2))
                assertEquals(0, Outbox.count(ctx, 1))
                assertEquals(1, Outbox.count(ctx, 2))
                assertFalse(secure("yuldash_trippass_secure_v2").contains("pass_1"))
                assertFalse(ctx.getSharedPreferences("yuldash_trippass_v2", 0).contains(OfflineStoreReset.PENDING))
                assertFalse(ctx.getSharedPreferences("yuldash_outbox_v2", 0).contains(OfflineStoreReset.PENDING))
            }
            "migration" -> {
                assertEquals("qa-legacy-access", ApiClient.currentToken())
                assertEquals("driver", ApiClient.cachedRole())
                assertEquals(nonce, encrypted.getString("refresh_rotation_id", null))
                assertEquals("qa-legacy-refresh", encrypted.getString("refresh_rotation_token", null))
                for (key in SessionKeys.CLEARED_ON_LOGOUT) assertFalse("Plain retains $key", plain.contains(key))
                val disk = File(ctx.applicationInfo.dataDir, "shared_prefs/yuldash_secure.xml").readText()
                assertFalse(disk.contains("qa-legacy-access")); assertFalse(disk.contains(nonce))
            }
            "authority" -> {
                assertEquals("qa-current-access", ApiClient.currentToken())
                assertEquals("driver", ApiClient.cachedRole())
                assertFalse(plain.contains("token"))
            }
            "logout" -> {
                assertFalse(ApiClient.isLoggedIn())
                assertNull(encrypted.getString("token", null))
            }
            "corruption", "personal_reset" -> {
                assertFalse(ApiClient.isLoggedIn())
                assertNull(ApiClient.cachedRole())
                assertFalse(encrypted.contains("user_role"))
                assertNull(encrypted.getString("token", null))
                if (mode == "personal_reset") {
                    assertNull(TripPassStore.load(ctx, 77))
                    assertFalse(Outbox.hasPending(ctx))
                    assertFalse(ctx.getSharedPreferences("trip_location_svc", 0).contains("last_booking"))
                }
                ApiClient.saveToken("qa-recovered-access")
                ApiClient.init(ctx)
                assertEquals("qa-recovered-access", ApiClient.currentToken())
            }
        }
    }

    @Test fun runPhase() {
        val requested = InstrumentationRegistry.getArguments().getString("storagePhase")
        org.junit.Assume.assumeTrue("Explicit storage audit phases only", requested != null)
        val phase = requireNotNull(requested)
        val parts = phase.split(":", limit = 2)
        if (parts[1] == "refresh") {
            if (parts[0] == "seed") seedRefresh() else verifyRefresh()
        } else if (parts[0] == "seed") seed(parts[1]) else verify(parts[1])
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
            putString("storage_phase", phase); putInt("process_id", Process.myPid())
        })
    }
}
