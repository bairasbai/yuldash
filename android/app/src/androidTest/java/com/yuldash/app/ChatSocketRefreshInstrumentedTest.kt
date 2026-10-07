package com.yuldash.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.ChatSocket
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Real emulator socket/refresh transport. Host fixture is reached only by root-owned adb reverse. */
@RunWith(AndroidJUnit4::class)
class ChatSocketRefreshInstrumentedTest {
    private val base = "http://127.0.0.1:19079"
    private val events = CopyOnWriteArrayList<Boolean>()
    private val incoming = LinkedBlockingQueue<String>()
    private lateinit var chat: ChatSocket

    private fun control(path: String, post: Boolean = false): JSONObject {
        val connection = URL(base + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = if (post) "POST" else "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            assertEquals(200, connection.responseCode)
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }
    private fun state() = control("/audit/state")
    private fun waitFor(condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < until) Thread.sleep(20)
        assertTrue("Expected native transport boundary", condition())
    }

    @Before fun prepare() = runBlocking {
        control("/audit/prepare?mode=normal", true)
        ApiClient.resetForTest()
        ApiClient.testBaseUrl = base
        ApiClient.testTimeoutMs = 10000
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        ApiClient.init(context)
        ApiClient.logout()
        assertFalse("Native auth storage must use available Keystore", ApiClient.secureStorageUnavailable)
        ApiClient.verifyCode("+70000000000", "000000", "A").getOrThrow()
        chat = ChatSocket(42, onMessage = { incoming.offer(it.text) }, onConnected = { events += it })
        Unit
    }
    @After fun cleanup() {
        control("/audit/release", true)
        chat.close()
        val bearer = ApiClient.currentToken()?.let { "Bearer $it" }
        ApiClient.logout()
        if (bearer != null) waitFor {
            val requests = state().getJSONArray("requests")
            (0 until requests.length()).any { index ->
                val row = requests.getJSONObject(index)
                row.optString("path") == "/auth/logout" && row.optString("bearer") == bearer
            }
        }
        val finalState = state()
        assertEquals(0, finalState.getJSONArray("failures").length())
        Log.i("B02ChatRefreshDevice", "verified pid=${android.os.Process.myPid()} state=$finalState")
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
    }
    private fun refresh() = runBlocking {
        val generation = ApiClient.queueSessionGeneration()
        assertTrue(ApiClient.getContacts().isSuccess)
        assertEquals("access-A2", ApiClient.currentToken())
        assertEquals(generation, ApiClient.queueSessionGeneration())
        assertEquals(1, state().getInt("refreshes"))
    }
    private fun assertConversation(index: Int) {
        assertEquals("ready-$index", incoming.poll(10, TimeUnit.SECONDS))
        assertTrue(chat.send("reply-$index", -index))
        waitFor { state().getJSONArray("messages").length() > 0 }
        val last = state().getJSONArray("messages").let { it.getJSONObject(it.length()-1) }
        assertEquals(index, last.getInt("connection"))
        assertEquals("reply-$index", last.getString("text"))
    }
    private fun assertTokens(vararg expected: String) {
        val tokens = state().getJSONArray("auth")
        assertEquals(expected.size, tokens.length())
        expected.forEachIndexed { index, token -> assertEquals(token, tokens.getJSONObject(index).getString("token")) }
    }

    @Test fun heldHandshakeAuthenticatesWithRefreshedCredentials() {
        control("/audit/mode?value=held", true)
        chat.connect()
        waitFor { state().getBoolean("held") }
        refresh()
        control("/audit/release", true)
        assertConversation(1)
        assertTokens("access-A2")
        assertEquals(listOf(true), events.toList())
    }

    @Test fun expiredConnectionRecoversUsingAlreadyRefreshedCredentials() {
        chat.connect()
        assertEquals("ready-1", incoming.poll(10, TimeUnit.SECONDS))
        refresh()
        control("/audit/close-old", true)
        assertConversation(2)
        assertTokens("access-A", "access-A2")
        assertEquals(listOf(true, false, true), events.toList())
        assertEquals(2, state().getInt("upgrades"))
    }

    @Test fun currentCredentialPolicyRefusalDoesNotReconnect() {
        control("/audit/mode?value=held-reject", true)
        chat.connect()
        waitFor { state().getBoolean("held") }
        refresh()
        control("/audit/release", true)
        waitFor { events == listOf(true, false) }
        assertTokens("access-A2")
        assertNull("Fresh policy refusal must stay terminal during this window", incoming.poll(1600, TimeUnit.MILLISECONDS))
        assertEquals(1, state().getInt("upgrades"))
    }
}
