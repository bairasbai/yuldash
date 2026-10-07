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
import com.yuldash.app.data.Outbox
import com.yuldash.app.data.TripPassStore
import com.yuldash.app.data.TripPass
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.CountDownLatch
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
class ActiveTripChatActionsInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext.applicationContext
    private val mounted = mutableStateOf(false)
    private var language = AppLanguage.Ru
    private var backs = 0
    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private lateinit var server: Loopback

    @Before fun prepare() = runBlocking {
        server = Loopback()
        ApiClient.resetForTest()
        ApiClient.serverUnreachable.value = false
        ApiClient.testBaseUrl = server.url
        ApiClient.testTimeoutMs = 60000
        ApiClient.init(context)
        ApiClient.logout()
        assertFalse(ApiClient.secureStorageUnavailable)
        ApiClient.verifyCode("+70000000000", "000000", "A").getOrThrow()
        assertTrue(TripPassStore.save(context, TripPass.fromJson(JSONObject().put("booking_id", 42).put("boarding_code", "1234"))))
        Unit
    }
    @After fun cleanup() {
        server.release.countDown()
        compose.runOnIdle { mounted.value = false; if (owner.registry.currentState != Lifecycle.State.INITIALIZED) owner.registry.currentState = Lifecycle.State.DESTROYED }
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
                CompositionLocalProvider(LocalAppLanguage provides language, LocalLifecycleOwner provides owner) {
                    if (mounted.value) ActiveTripScreen(ride = null, contacts = emptyList(), bookingId = 42,
                        onBack = { backs++; mounted.value = false }, onTripEnd = {}, onSos = {})
                    else Text(appText("Экран закрыт", "Экран ябылған"))
                }
            }
        }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
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
        File(context.filesDir, "b02-chat-actions-$name.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }
    private fun heldDone(selected: AppLanguage, edit: Boolean) {
        language = selected
        mount()
        compose.onNodeWithText("private-history-A").performTouchInput { longClick() }
        if (edit) {
            compose.onNodeWithText(if (language == AppLanguage.Ba) "Үҙгәртеү" else "Редактировать").performClick()
            compose.onNode(hasScrollToNodeAction() and SemanticsMatcher.keyIsDefined(
                androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange)).performScrollToNode(hasSetTextAction())
            compose.onNode(hasSetTextAction()).performTextReplacement("native-edit")
            compose.onNodeWithContentDescription(if (language == AppLanguage.Ba) "Ебәреү" else "Отправить").performClick()
        } else compose.onNodeWithText(if (language == AppLanguage.Ba) "Барыһында юйыу" else "Удалить у всех").performClick()
        assertTrue(server.held.await(5, TimeUnit.SECONDS))
        compose.onNode(hasScrollToNodeAction() and SemanticsMatcher.keyIsDefined(
            androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange)).performScrollToNode(hasText("private-history-A"))
        screenshot("${language.name}-before", "private-history-A")
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        server.status = "done"
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        val done = if (language == AppLanguage.Ba) "Сәфәр тамамланды" else "Поездка завершена"
        compose.waitUntil(15000) { compose.onAllNodesWithText(done).fetchSemanticsNodes().isNotEmpty() && TripPassStore.load(context, 42) == null }
        val histories = server.requests.count { it.first == "GET /bookings/42/messages" }
        server.release.countDown()
        compose.waitUntil(10000) { server.replied.count == 0L }
        Thread.sleep(700) // Bounded observation after server response, not a join of the UI job.
        compose.waitForIdle()
        assertEquals(histories, server.requests.count { it.first == "GET /bookings/42/messages" })
        assertEquals(if (edit) "POST /bookings/42/messages/1/edit" else "DELETE /bookings/42/messages/1?scope=all",
            server.requests.single { it.first.contains("/messages/1") }.first)
        assertFalse(server.requests.any { it.first == "POST /bookings/42/messages" })
        compose.onNodeWithText(done).assertIsDisplayed()
        screenshot("${language.name}-done", done)
        Log.i("B02ChatActionsDevice", "verified ${language.name} held-${if (edit) "edit" else "delete"} done pid=${Process.myPid()} histories=$histories httpOnly=true")
    }
    @Test fun russianHeldEditStopsAtDone() = heldDone(AppLanguage.Ru, true)
    @Test fun bashkirHeldDeleteStopsAtDone() = heldDone(AppLanguage.Ba, false)

    private class Loopback : AutoCloseable {
        private val listener = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val workers = Executors.newCachedThreadPool { job -> Thread(job, "b02-chat-actions-http").apply { isDaemon = true } }
        private val sockets = CopyOnWriteArrayList<Socket>()
        val requests = CopyOnWriteArrayList<Pair<String, String?>>()
        val failures = CopyOnWriteArrayList<String>()
        val posts = CopyOnWriteArrayList<Pair<String, String?>>()
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val replied = CountDownLatch(1)
        @Volatile var status = "confirmed"
        val tokenA = "header.eyJzdWIiOiIxMSJ9.signature"
        val tokenA2 = "header.eyJzdWIiOiIxMSJ9.refreshed"
        val tokenB = "header.eyJzdWIiOiIzMyJ9.signature"
        @Volatile private var closed = false
        val url = "http://127.0.0.1:${listener.localPort}"
        private val acceptor = thread(isDaemon = true, name = "b02-chat-actions-accept") {
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
                if (path.contains("/bookings/42/messages/1")) {
                    posts += path to headers["idempotency-key"]
                    if (held.count == 1L) {
                        held.countDown()
                        if (!release.await(45, TimeUnit.SECONDS)) failures += "Held POST release timeout"
                    }
                }
                var responseStatus = 200
                val body = when (path) {
                    "/auth/verify" -> {
                        val account = JSONObject(String(chars)).getString("name")
                        """{"access_token":"${if (account == "A") tokenA else tokenB}","refresh_token":"refresh-$account"}"""
                    }
                    "/auth/refresh" -> """{"access_token":"$tokenA2","refresh_token":"refresh-A2"}"""
                    "/trusted-contacts" -> { if (bearer == "Bearer $tokenA") responseStatus = 401; """{"items":[]}""" }
                    "/auth/logout", "/push/unregister" -> "{}"
                    "/bookings/42/role" -> """{"role":"passenger","status":"$status","driver_phase":"departed"}"""
                    "/bookings/99/messages" -> "{}"
                    "/trips/42/receipt" -> """{"booking_id":42,"role":"passenger","from_city":"Город А","to_city":"Город Б","counterparty_name":"Водитель","amount":700,"paid":true}"""
                    "/bookings/42/tip" -> """{"already_thanked":false}"""
                    "/bookings/42/messages" -> if (method == "POST") "{}" else """{"items":[{"id":1,"sender_id":11,"text":"${if (bearer == "Bearer $tokenB") "private-history-B" else "private-history-A"}"}]}"""
                    "/bookings/42/messages/1/edit", "/bookings/42/messages/1?scope=all" -> "{}"
                    "/bookings/42/boarding-code" -> """{"code":"1234"}"""
                    "/bookings/42/details" -> """{"booking_id":42,"role":"passenger","status":"confirmed","pay_method":"cash","pay_amount":700}"""
                    "/ws/bookings/42" -> { responseStatus = 404; "{}" } // Explicitly HTTP-only native fixture.
                    else -> { failures += "Unexpected $method $path"; responseStatus = 404; "{}" }
                }
                val bytes = body.toByteArray(Charsets.UTF_8)
                val output = socket.getOutputStream()
                output.write("HTTP/1.1 $responseStatus Local\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                output.write(bytes); output.flush()
                if (path.contains("/bookings/42/messages/1")) replied.countDown()
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
