package com.yuldash.app.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Process
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuldash.app.StorageAuditRunner
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * QA-B01-010: seed an old-format installation, cold-open v2, then cold-open saved B data.
 * Only an explicitly enabled, root-owned cleared debug installation is permitted.
 * Real files and Android Keystore are used; old APK execution and physical phones are not.
 */
@RunWith(AndroidJUnit4::class)
class LegacyOfflineUpgradeProcessInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val probe get() = context.getSharedPreferences("qa_legacy_upgrade_probe", Context.MODE_PRIVATE)
    private val legacyNames = listOf(
        "yuldash_trippass", "yuldash_trippass_secure",
        "yuldash_outbox", "yuldash_outbox_secure",
    )
    private val currentNames = listOf(
        "yuldash_trippass_v2", "yuldash_trippass_secure_v2",
        "yuldash_outbox_v2", "yuldash_outbox_secure_v2",
    )
    private val ownerToken = "qa-legacy-upgrade-owner-B"
    private val newBookingId = 801
    private val oldBookingIds = listOf(701, 702)

    private fun store(name: String): SharedPreferences = if ("_secure" in name) {
        EncryptedSharedPreferences.create(
            context, name,
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } else context.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun keyFor(name: String) = if ("trippass" in name) {
        "pass_${if ("_secure" in name) 702 else 701}"
    } else "queue"

    private fun hash(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun assertLegacyUntouched() {
        for (name in legacyNames) {
            val value = requireNotNull(store(name).getString(keyFor(name), null))
            assertEquals("Legacy data changed during the upgrade: $name", probe.getString("hash_$name", null), hash(value))
            val file = File(context.applicationInfo.dataDir, "shared_prefs/$name.xml")
            assertTrue("The legacy source must be a real on-disk file: $name", file.isFile)
            if ("_secure" in name) {
                assertFalse("Legacy synthetic data must actually be encrypted", file.readText().contains("QA-LEGACY-A"))
            }
        }
    }

    private fun assertLegacyInaccessible() {
        for (id in oldBookingIds) {
            assertNull("Unattributed legacy passport became available under B", TripPassStore.load(context, id))
            assertEquals("Unattributed legacy actions became available under B", 0, Outbox.count(context, id))
        }
    }

    private fun seed() {
        assertFalse("Run only after root cleared this owned test installation", probe.contains("seed_pid"))
        for (name in legacyNames + currentNames) assertTrue(store(name).edit().clear().commit())
        ApiClient.init(context)
        assertFalse("The real Android Keystore is required for this criterion", ApiClient.secureStorageUnavailable)
        ApiClient.saveToken(ownerToken)
        assertEquals(ownerToken, ApiClient.currentToken())
        assertEquals(ownerToken, store("yuldash_secure").getString("token", null))
        val markers = probe.edit().putInt("seed_pid", Process.myPid()).putString("stage", "seeded")
        for (name in legacyNames) {
            val id = if ("_secure" in name) 702 else 701
            val value = if ("trippass" in name) {
                TripPass.fromJson(JSONObject().put("booking_id", id)
                    .put("boarding_code", "QA-LEGACY-A-$id")).toJson().toString()
            } else {
                JSONArray().put(JSONObject().put("id", id.toLong()).put("booking_id", id)
                    .put("kind", "message").put("payload", "QA-LEGACY-A-$id")
                    .put("created_at", System.currentTimeMillis())).toString()
            }
            assertTrue(store(name).edit().putString(keyFor(name), value).commit())
            markers.putString("hash_$name", hash(value))
        }
        assertTrue(markers.commit())
        assertLegacyUntouched()
        assertLegacyInaccessible()
        assertFalse(Outbox.hasPending(context))
    }

    private fun upgrade(server: LocalRecorder) = runBlocking {
        assertEquals("seeded", probe.getString("stage", null))
        assertNotEquals("A new actual process is required", probe.getInt("seed_pid", -1), Process.myPid())
        assertLegacyUntouched()
        ApiClient.init(context)
        assertFalse(ApiClient.secureStorageUnavailable)
        assertEquals(ownerToken, ApiClient.currentToken())
        assertLegacyInaccessible()
        assertFalse(Outbox.hasPending(context))
        assertFalse("Legacy queue must not be sent, modified or adopted", Outbox.flush(context))
        // flush awaits each HTTP operation; a completed empty flush cannot hide a pending send.
        assertEquals("Legacy startup/flush must issue no HTTP", emptyList<String>(), server.requests.toList())
        server.failure.get()?.let { throw it }
        assertLegacyUntouched()
        val newPass = TripPass.fromJson(JSONObject().put("booking_id", newBookingId).put("boarding_code", "QA-CURRENT-B"))
        assertTrue(TripPassStore.save(context, newPass))
        assertTrue(Outbox.enqueue(context, Outbox.newMessage(newBookingId, "QA-CURRENT-B message")))
        assertEquals("QA-CURRENT-B", TripPassStore.load(context, newBookingId)?.boardingCode)
        assertEquals(1, Outbox.count(context, newBookingId))
        assertLegacyInaccessible()
        assertTrue(probe.edit().putInt("upgrade_pid", Process.myPid()).putString("stage", "upgraded").commit())
    }

    private fun verify(server: LocalRecorder) {
        assertEquals("upgraded", probe.getString("stage", null))
        assertNotEquals(probe.getInt("seed_pid", -1), Process.myPid())
        assertNotEquals(probe.getInt("upgrade_pid", -1), Process.myPid())
        ApiClient.init(context)
        assertFalse(ApiClient.secureStorageUnavailable)
        assertEquals(ownerToken, ApiClient.currentToken())
        assertEquals("QA-CURRENT-B", TripPassStore.load(context, newBookingId)?.boardingCode)
        assertEquals(1, Outbox.count(context, newBookingId))
        assertTrue(Outbox.hasPending(context))
        assertLegacyInaccessible()
        assertLegacyUntouched()
        assertEquals(emptyList<String>(), server.requests.toList())
        ApiClient.logout()
        assertNull(ApiClient.currentToken())
        assertNull(TripPassStore.load(context, newBookingId))
        assertFalse(Outbox.hasPending(context))
        assertLegacyInaccessible()
        for (name in legacyNames + currentNames) {
            val prefs = store(name)
            assertFalse("Logout left queued personal data in $name", prefs.contains("queue"))
            for (id in oldBookingIds + newBookingId) assertFalse("Logout left passport $id in $name", prefs.contains("pass_$id"))
        }
        assertTrue("The synthetic logout must finish on the owned loopback server", server.logout.await(5, TimeUnit.SECONDS))
        server.failure.get()?.let { throw it }
        assertEquals(listOf("POST /auth/logout"), server.requests.toList())
        assertTrue(probe.edit().putInt("verify_pid", Process.myPid()).putString("stage", "verified").commit())
    }

    @Test fun runPhase() {
        val phase = InstrumentationRegistry.getArguments().getString("legacyUpgradePhase")
        assumeTrue("Explicit isolated legacy upgrade audit only", phase in listOf("seed", "upgrade", "verify"))
        assertTrue("StorageAuditRunner prevents production Application startup", instrumentation is StorageAuditRunner)
        val previousUrl = ApiClient.testBaseUrl
        val previousTimeout = ApiClient.testTimeoutMs
        LocalRecorder().use { server ->
            ApiClient.testBaseUrl = server.url
            ApiClient.testTimeoutMs = 2000
            try {
                server.calibrate()
                when (phase) {
                    "seed" -> seed()
                    "upgrade" -> upgrade(server)
                    "verify" -> verify(server)
                }
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("legacy_upgrade_phase_verified", phase)
                    putInt("process_id", Process.myPid())
                    putBoolean("real_keystore", true)
                    putBoolean("legacy_visible", false)
                })
            } finally {
                ApiClient.testBaseUrl = previousUrl
                ApiClient.testTimeoutMs = previousTimeout
            }
        }
        // No orderly Application shutdown or reset may manufacture persistence between phases.
        if (phase != "verify") Process.killProcess(Process.myPid())
    }

    /** The only available endpoint is an owned Android loopback socket, never a provider. */
    private class LocalRecorder : AutoCloseable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${socket.localPort}"
        val requests = CopyOnWriteArrayList<String>()
        val failure = AtomicReference<Throwable?>()
        val logout = CountDownLatch(1)
        private val worker = Thread({
            try {
                while (!socket.isClosed) socket.accept().use { client ->
                    client.soTimeout = 2000
                    val reader = client.getInputStream().bufferedReader(StandardCharsets.UTF_8)
                    val request = requireNotNull(reader.readLine())
                    val parts = request.split(' ')
                    requests += "${parts[0]} ${parts[1]}"
                    var length = 0
                    while (true) {
                        val header = requireNotNull(reader.readLine())
                        if (header.isEmpty()) break
                        if (header.startsWith("Content-Length:", ignoreCase = true)) length = header.substringAfter(':').trim().toInt()
                    }
                    repeat(length) { check(reader.read() >= 0) }
                    client.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}".toByteArray(StandardCharsets.US_ASCII))
                        flush()
                    }
                    if (parts[1] == "/auth/logout") logout.countDown()
                }
            } catch (error: Throwable) { if (!socket.isClosed) failure.set(error) }
        }, "qa-legacy-loopback").apply { isDaemon = true; start() }

        fun calibrate() {
            val connection = URL("$url/qa/ping").openConnection() as HttpURLConnection
            connection.connectTimeout = 2000
            connection.readTimeout = 2000
            try {
                assertEquals(200, connection.responseCode)
                assertEquals("{}", connection.inputStream.bufferedReader().use { it.readText() })
            } finally { connection.disconnect() }
            assertEquals(listOf("GET /qa/ping"), requests.toList())
            failure.get()?.let { throw it }
            requests.clear()
        }

        override fun close() {
            socket.close()
            worker.join(2500)
            check(!worker.isAlive) { "Owned loopback worker did not stop" }
            failure.get()?.let { throw it }
        }
    }
}
