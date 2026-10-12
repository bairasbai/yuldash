package com.yuldash.app

import android.graphics.Bitmap
import android.os.Process
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuldash.app.data.ApiClient
import com.yuldash.app.ui.theme.YuldashTheme
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Real Compose and auth storage/HTTP; HTTP-only fixture deliberately refuses the WS upgrade. */
@RunWith(AndroidJUnit4::class)
class ActiveTripChatUiInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext.applicationContext
    private val mounted = mutableStateOf(false)
    private var language = AppLanguage.Ru
    private var backs = 0
    private lateinit var server: Loopback

    @Before fun prepare() = runBlocking {
        server = Loopback()
        ApiClient.resetForTest()
        ApiClient.serverUnreachable.value = false
        ApiClient.testBaseUrl = server.url
        ApiClient.testTimeoutMs = 10000
        ApiClient.init(context)
        ApiClient.logout()
        assertFalse(ApiClient.secureStorageUnavailable)
        ApiClient.verifyCode("+70000000000", "000000", "A").getOrThrow()
        Unit
    }
    @After fun cleanup() {
        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
        val bearer = ApiClient.currentToken()?.let { "Bearer $it" }
        ApiClient.logout()
        if (bearer != null) compose.waitUntil(10000) { server.requests.contains("POST /auth/logout" to bearer) }
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server.close()
        assertTrue(server.failures.toString(), server.failures.isEmpty())
    }
    private fun mount() {
        mounted.value = true
        compose.setContent {
            YuldashTheme {
                CompositionLocalProvider(LocalAppLanguage provides language) {
                    if (mounted.value) ActiveTripScreen(ride = null, contacts = emptyList(), bookingId = 42,
                        onBack = { backs++; mounted.value = false }, onTripEnd = {}, onSos = {})
                    else Text(appText("Экран закрыт", "Экран ябылған"))
                }
            }
        }
        openChat("private-history-A")
    }
    private fun openChat(history: String) {
        val write = if (language == AppLanguage.Ba) "Яҙырға" else "Написать"
        compose.waitUntil(15000) { compose.onAllNodesWithText(write).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(write).performClick()
        compose.onNode(hasScrollToNodeAction() and SemanticsMatcher.keyIsDefined(
            androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange)).performScrollToNode(hasText(history))
        compose.waitUntil(15000) { compose.onAllNodesWithText(history).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(history).assertIsDisplayed()
    }
    private fun screenshot(name: String, marker: String) {
        compose.waitUntil(5000) {
            val pixels = compose.onNodeWithText(marker).captureToImage().toPixelMap()
            var low = 1f; var high = 0f
            for (x in 0 until pixels.width step 2) for (y in 0 until pixels.height step 2) {
                val color = pixels[x, y]
                val light = (color.red + color.green + color.blue) / 3
                low = minOf(low, light); high = maxOf(high, light)
            }
            high - low > .15f
        }
        instrumentation.waitForIdleSync()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(context.filesDir, "b02-chat-ui-$name.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }
    private fun assertClosed() {
        val closedText = if (language == AppLanguage.Ba) "Экран ябылған" else "Экран закрыт"
        compose.waitUntil(15000) { compose.onAllNodesWithText(closedText).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("private-history-A").assertCountEquals(0)
        compose.onAllNodesWithText("private-draft-A").assertCountEquals(0)
        compose.onAllNodesWithText("1234").assertCountEquals(0)
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        assertEquals(1, backs)
        screenshot("${language.name}-closed", closedText)
    }
    @Test fun russianLogoutRemovesHistoryDraftAndBookingScreen() {
        mount()
        compose.onNode(hasSetTextAction()).performTextInput("private-draft-A")
        screenshot("Ru-before", "private-history-A")
        compose.runOnIdle { ApiClient.logout() }
        assertClosed()
        Log.i("B02ChatUiDevice", "verified Ru logout pid=${Process.myPid()} backs=1 httpOnly=true")
    }
    @Test fun bashkirAccountSwitchClosesAAndReopensTheSameBookingForB() {
        language = AppLanguage.Ba
        mount()
        compose.onNode(hasSetTextAction()).performTextInput("private-draft-A")
        screenshot("Ba-before", "private-history-A")
        // Both real auth mutations occur while main is busy; StateFlow may conflate them.
        compose.runOnIdle { runBlocking {
            ApiClient.logout()
            ApiClient.verifyCode("+70000000000", "000000", "B").getOrThrow()
        } }
        assertClosed()
        assertFalse(server.requests.any { it.first.contains("/bookings/42/") && it.second == "Bearer ${server.tokenB}" })
        compose.runOnIdle { mounted.value = true }
        openChat("private-history-B")
        compose.onAllNodesWithText("private-history-A").assertCountEquals(0)
        compose.onAllNodesWithText("private-draft-A").assertCountEquals(0)
        compose.onNode(hasSetTextAction()).assertTextEquals("")
        screenshot("Ba-B", "private-history-B")
        Log.i("B02ChatUiDevice", "verified Ba A-to-B reopen pid=${Process.myPid()} backs=1 httpOnly=true")
    }
    @Test fun sameSessionHttpRefreshKeepsRussianHistoryAndDraft() {
        mount()
        compose.onNode(hasSetTextAction()).performTextInput("private-draft-A")
        val generation = ApiClient.queueSessionGeneration()
        runBlocking { ApiClient.getContacts().getOrThrow() }
        assertEquals(server.tokenA2, ApiClient.currentToken())
        assertEquals(generation, ApiClient.queueSessionGeneration())
        compose.waitForIdle()
        compose.onNodeWithText("private-history-A").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertTextContains("private-draft-A")
        assertEquals(0, backs)
        screenshot("Ru-refresh", "private-history-A")
        Log.i("B02ChatUiDevice", "verified Ru same-owner refresh pid=${Process.myPid()} backs=0 httpOnly=true")
    }

    private class Loopback : AutoCloseable {
        private val listener = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val workers = Executors.newCachedThreadPool { job -> Thread(job, "b02-chat-ui-http").apply { isDaemon = true } }
        private val sockets = CopyOnWriteArrayList<Socket>()
        val requests = CopyOnWriteArrayList<Pair<String, String?>>()
        val failures = CopyOnWriteArrayList<String>()
        val tokenA = "header.eyJzdWIiOiIxMSJ9.signature"
        val tokenA2 = "header.eyJzdWIiOiIxMSJ9.refreshed"
        val tokenB = "header.eyJzdWIiOiIzMyJ9.signature"
        @Volatile private var closed = false
        val url = "http://127.0.0.1:${listener.localPort}"
        private val acceptor = thread(isDaemon = true, name = "b02-chat-ui-accept") {
            while (!closed) try {
                val socket = listener.accept(); sockets += socket
                workers.execute { serve(socket) }
            } catch (e: Exception) { if (!closed) failures += e.toString() }
        }
        private fun serve(socket: Socket) = socket.use {
            try {
                socket.soTimeout = 10000
                val input = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                val first = input.readLine() ?: return@use
                val parts = first.split(' ')
                val method = parts[0]; val path = parts[1]
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                    headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                }
                val chars = CharArray(headers["content-length"]?.toIntOrNull() ?: 0)
                var read = 0
                while (read < chars.size) { val count = input.read(chars, read, chars.size - read); check(count > 0); read += count }
                val bearer = headers["authorization"]
                requests += "$method $path" to bearer
                var status = 200
                val body = when (path) {
                    "/auth/verify" -> {
                        val account = JSONObject(String(chars)).getString("name")
                        """{"access_token":"${if (account == "A") tokenA else tokenB}","refresh_token":"refresh-$account"}"""
                    }
                    "/auth/refresh" -> """{"access_token":"$tokenA2","refresh_token":"refresh-A2"}"""
                    "/trusted-contacts" -> { if (bearer == "Bearer $tokenA") status = 401; """{"items":[]}""" }
                    "/auth/logout", "/push/unregister" -> "{}"
                    "/bookings/42/role" -> """{"role":"passenger","status":"confirmed","driver_phase":"departed"}"""
                    "/bookings/42/messages" -> """{"items":[{"id":1,"sender_id":22,"text":"${if (bearer == "Bearer $tokenB") "private-history-B" else "private-history-A"}"}]}"""
                    "/bookings/42/boarding-code" -> """{"code":"1234"}"""
                    "/bookings/42/details" -> """{"booking_id":42,"role":"passenger","status":"confirmed","pay_method":"cash","pay_amount":700}"""
                    "/ws/bookings/42" -> { status = 404; "{}" } // Explicitly HTTP-only native fixture.
                    else -> { failures += "Unexpected $method $path"; status = 404; "{}" }
                }
                val bytes = body.toByteArray(Charsets.UTF_8)
                val output = socket.getOutputStream()
                output.write("HTTP/1.1 $status Local\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                output.write(bytes); output.flush()
            } catch (e: Exception) { if (!closed) failures += e.toString() }
            finally { sockets.remove(socket) }
        }
        override fun close() {
            closed = true
            listener.close()
            sockets.forEach { runCatching { it.close() } }
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
            acceptor.join(5000)
            assertFalse(acceptor.isAlive)
        }
    }
}
