package com.yuldash.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuldash.app.data.ApiClient
import com.yuldash.app.ui.theme.YuldashTheme
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Real buttons and local HTTP, including commit followed by a lost acknowledgement. */
@RunWith(AndroidJUnit4::class)
class NearbyWatchButtonInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val mounted = mutableStateOf(false)
    private val accepts = mutableStateOf(true)
    private val opens = AtomicInteger()
    private val calls = CopyOnWriteArrayList<String>()
    private val payloads = CopyOnWriteArrayList<JSONObject>()
    private val errors = CopyOnWriteArrayList<String>()
    private val logout = CountDownLatch(1)
    private lateinit var listener: ServerSocket
    private lateinit var worker: Thread
    @Volatile private var exists = false
    private val route = NearbyWatchRoute("Ургаза", "Акъяр", "2030-05-02")

    @Before fun prepare() {
        listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        worker = thread(name = "nearby-watch-test-http", isDaemon = true) {
            while (!listener.isClosed) {
                val client = runCatching { listener.accept() }.getOrNull() ?: break
                try {
                    client.use { socket ->
                        socket.soTimeout = 5000
                        val input = socket.getInputStream().buffered()
                        fun line(): String {
                            val bytes = ArrayList<Byte>()
                            while (true) {
                                val byte = input.read()
                                check(byte >= 0)
                                if (byte == 10) break
                                if (byte != 13) bytes += byte.toByte()
                            }
                            return bytes.toByteArray().toString(Charsets.US_ASCII)
                        }
                        val request = line().substringBefore(" HTTP/")
                        var length = 0
                        var header = line()
                        while (header.isNotEmpty()) {
                            if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
                            header = line()
                        }
                        val body = ByteArray(length)
                        var read = 0
                        while (read < length) {
                            val count = input.read(body, read, length - read)
                            check(count > 0)
                            read += count
                        }
                        calls += request
                        val response = when (request) {
                            "GET /route-watch" -> if (exists) "[$neighbor,$watch]" else "[$neighbor]"
                            "POST /route-watch" -> {
                                payloads += JSONObject(body.toString(Charsets.UTF_8))
                                exists = true
                                null // The write committed, then its acknowledgement was lost.
                            }
                            "DELETE /route-watch/71" -> { exists = false; null }
                            "POST /auth/logout" -> "{}"
                            else -> { errors += request; "{}" }
                        }
                        if (response != null) {
                            val data = response.toByteArray(Charsets.UTF_8)
                            socket.getOutputStream().apply {
                                write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${data.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                                write(data)
                                flush()
                            }
                            if (request == "POST /auth/logout") logout.countDown()
                        }
                    }
                } catch (failure: Exception) { if (!listener.isClosed) errors += failure.toString() }
            }
        }
        ApiClient.resetForTest()
        ApiClient.testBaseUrl = "http://127.0.0.1:${listener.localPort}"
        ApiClient.testTimeoutMs = 5000
        ApiClient.init(context)
        ApiClient.logout()
    }

    @After fun cleanup() {
        try {
            compose.runOnIdle { mounted.value = false }
            val signedIn = ApiClient.isLoggedIn()
            ApiClient.logout()
            if (signedIn) assertTrue(logout.await(6, TimeUnit.SECONDS))
        } finally {
            ApiClient.resetForTest()
            ApiClient.testTimeoutMs = null
            listener.close()
            worker.join(6000)
            assertFalse(worker.isAlive)
            assertTrue(errors.toString(), errors.isEmpty())
        }
    }

    @Test fun russianLight() = journey(AppLanguage.Ru, false)
    @Test fun russianDark() = journey(AppLanguage.Ru, true)
    @Test fun bashkirLight() = journey(AppLanguage.Ba, false)
    @Test fun bashkirDark() = journey(AppLanguage.Ba, true)

    private fun journey(language: AppLanguage, dark: Boolean) {
        val follow = if (language == AppLanguage.Ru) "Следить за маршрутом" else "Маршрутты күҙәтеү"
        val unwatch = if (language == AppLanguage.Ru) "Не следить" else "Күҙәтмәҫкә"
        mounted.value = true
        compose.setContent {
            val owner by ApiClient.sessionChanges.collectAsState()
            YuldashTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalAppLanguage provides language, LocalNightRide provides dark) {
                    if (mounted.value) key(owner) { NearbyWatchButton(route, owner, { accepts.value }) { opens.incrementAndGet() } }
                }
            }
        }
        compose.onNodeWithText(follow).assertIsDisplayed().performClick()
        assertEquals(1, opens.get())
        assertTrue(calls.isEmpty())
        compose.runOnIdle { ApiClient.saveToken("synthetic-nearby-watch-owner") }
        compose.waitUntil(15000) { compose.onAllNodes(hasText(follow) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(follow).performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText(unwatch).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(unwatch).assertIsDisplayed()
        assertEquals(1, payloads.size)
        assertEquals("Ургаза", payloads.single().getString("from_city"))
        assertEquals("Акъяр", payloads.single().getString("to_city"))
        assertEquals("rides", payloads.single().getString("watch_kind"))
        assertEquals("forward", payloads.single().getString("direction"))
        assertEquals("2030-05-02T00:00:00", payloads.single().getString("watch_date"))
        compose.onNodeWithText(unwatch).performClick()
        compose.waitUntil(15000) { !exists && calls.count { it == "GET /route-watch" } >= 3 &&
            compose.onAllNodes(hasText(follow) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(follow).assertIsDisplayed()
        assertEquals(1, calls.count { it == "POST /route-watch" })
        assertEquals(1, calls.count { it == "DELETE /route-watch/71" })
        assertFalse(calls.contains("DELETE /route-watch/70"))
    }

    private companion object {
        val watch = """{"id":71,"from_city":"Ургаза","to_city":"Акъяр","direction":"forward","watch_kind":"rides","watch_date":"2030-05-01T19:00:00"}"""
        val neighbor = """{"id":70,"from_city":"Ургаза","to_city":"Акъяр","direction":"forward","watch_kind":"requests","watch_date":"2030-05-01T19:00:00"}"""
    }
}
