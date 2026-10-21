package com.yuldash.app.data

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

/** Two explicit phases against the loopback adapter; the seed process kills itself after SQL commit. */
@RunWith(AndroidJUnit4::class)
class SosRetryProcessInstrumentedTest {
    private val base = "http://127.0.0.1:5198"
    private fun qa(path: String, post: Boolean = false): JSONObject {
        val connection = URL(base + path).openConnection() as HttpURLConnection
        connection.connectTimeout = 3000
        connection.readTimeout = 3000
        if (post) connection.requestMethod = "POST"
        return try {
            assertEquals(200, connection.responseCode)
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }
    @Test fun runPhase(): Unit = runBlocking {
        val phase = InstrumentationRegistry.getArguments().getString("sosRetryPhase")
        assumeTrue("Explicit isolated SOS retry audit only", phase == "seed" || phase == "verify")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val probe = context.getSharedPreferences("qa_sos_retry", 0)
        ApiClient.init(context)
        ApiClient.testBaseUrl = base
        ApiClient.testTimeoutMs = 2000
        assertFalse(ApiClient.secureStorageUnavailable)
        if (phase == "seed") {
            val fixture = qa("/__qa__/sos-bootstrap", post = true)
            val booking = fixture.getInt("booking_id")
            ApiClient.saveToken(fixture.getString("token"))
            assertTrue(probe.edit().clear().putInt("booking", booking).putInt("pid", Process.myPid()).commit())
            qa("/__qa__/sos-arm", post = true)
            assertTrue(ApiClient.sos("other", "Synthetic process emergency", bookingId = booking).isFailure)
            val stats = qa("/__qa__/sos-stats")
            assertEquals(1, stats.getInt("events"))
            assertEquals(1, stats.getInt("fanouts"))
            instrumentation.sendStatus(0, android.os.Bundle().apply {
                putBoolean("sos_saved_before_kill", true)
                putInt("process_id", Process.myPid())
            })
            Process.killProcess(Process.myPid())
        } else {
            assertNotEquals(probe.getInt("pid", -1), Process.myPid())
            val before = qa("/__qa__/sos-stats")
            assertEquals(1, before.getInt("events"))
            assertEquals(1, before.getInt("requests")) // Startup did not send anything.
            val booking = probe.getInt("booking", -1)
            assertTrue(ApiClient.sos("other", "Synthetic process emergency", bookingId = booking).isSuccess)
            val stats = qa("/__qa__/sos-stats")
            assertEquals(2, stats.getInt("requests"))
            assertEquals(1, stats.getInt("events"))
            assertEquals(1, stats.getInt("fanouts"))
            // Acknowledgement ends the old intent; an explicit new signal remains possible.
            assertTrue(ApiClient.sos("other", "Synthetic process emergency", bookingId = booking).isSuccess)
            assertEquals(2, qa("/__qa__/sos-stats").getInt("events"))
            instrumentation.sendStatus(0, android.os.Bundle().apply {
                putBoolean("single_sos_after_process_restart", true)
                putInt("process_id", Process.myPid())
            })
            ApiClient.logout()
            probe.edit().clear().commit()
        }
    }
}
