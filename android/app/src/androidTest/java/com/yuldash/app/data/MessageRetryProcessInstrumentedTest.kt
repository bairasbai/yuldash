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

/** Explicit local adapter only, with airplane mode and adb reverse tcp:5191. */
@RunWith(AndroidJUnit4::class)
class MessageRetryProcessInstrumentedTest {
    private val base = "http://127.0.0.1:5191"
    private fun qa(path: String): JSONObject {
        val connection = URL(base + path).openConnection() as HttpURLConnection
        connection.connectTimeout = 3000
        connection.readTimeout = 3000
        return try {
            assertEquals(200, connection.responseCode)
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }
    @Test fun runPhase() = runBlocking {
        val phase = InstrumentationRegistry.getArguments().getString("messageRetryPhase")
        assumeTrue("Explicit isolated message retry audit only", phase == "seed" || phase == "verify")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val probe = context.getSharedPreferences("qa_message_retry", 0)
        ApiClient.testBaseUrl = base
        ApiClient.testTimeoutMs = 2000
        ApiClient.init(context)
        assertFalse(ApiClient.secureStorageUnavailable)
        if (phase == "seed") {
            val fixture = qa("/qa/session")
            val booking = fixture.getInt("booking_id")
            ApiClient.saveToken(fixture.getString("token"))
            Outbox.clearAll()
            assertTrue(probe.edit().clear().putInt("booking", booking).putInt("pid", Process.myPid()).commit())
            val action = Outbox.newMessage(booking, "fixture Android process retry")
            assertTrue(ApiClient.sendMessage(booking, action.payload, action.requestKey).isFailure)
            assertEquals(1, qa("/qa/stats").getInt("messages"))
            assertTrue(Outbox.enqueue(context, action))
            instrumentation.sendStatus(0, android.os.Bundle().apply {
                putBoolean("message_saved_before_kill", true)
                putInt("process_id", Process.myPid())
            })
            Process.killProcess(Process.myPid())
        } else {
            assertNotEquals(probe.getInt("pid", -1), Process.myPid())
            val booking = probe.getInt("booking", -1)
            assertEquals(1, Outbox.count(context, booking))
            assertTrue(Outbox.flush(context))
            val stats = qa("/qa/stats")
            assertEquals(2, stats.getInt("requests"))
            assertEquals(1, stats.getInt("messages"))
            assertEquals(1, stats.getInt("receipts"))
            assertEquals(1, stats.getInt("live"))
            assertEquals(1, stats.getInt("push"))
            assertFalse(Outbox.hasPending(context))
            instrumentation.sendStatus(0, android.os.Bundle().apply {
                putInt("process_id", Process.myPid())
                putBoolean("single_message_after_restart", true)
            })
        }
    }
}
