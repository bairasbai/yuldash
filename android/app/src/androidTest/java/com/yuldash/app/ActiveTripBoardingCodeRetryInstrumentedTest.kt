package com.yuldash.app

import android.content.Context
import android.graphics.Bitmap
import android.os.Process
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.TripPass
import com.yuldash.app.data.TripPassStore
import com.yuldash.app.ui.theme.YuldashTheme
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Actual RU/BA error -> held retry/loading -> success and real encrypted passport update. */
@RunWith(AndroidJUnit4::class)
class ActiveTripBoardingCodeRetryInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext.applicationContext
    private val mounted = mutableStateOf(false)
    private lateinit var server: Loopback

    @Before fun prepare() {
        server = Loopback()
        ApiClient.resetForTest()
        ApiClient.serverUnreachable.value = false
        ApiClient.testBaseUrl = server.url
        ApiClient.testTimeoutMs = 45000
        ApiClient.init(context)
        ApiClient.logout()
        TripPassStore.init(context)
        val pass = TripPass.fromJson(JSONObject().put("booking_id", 42).put("boarding_code", "1234")
            .put("price", 700).put("pay_method", "cash").put("pay_amount", 400))
        assertTrue(TripPassStore.save(context, pass))
        assertTrue(TripPassStore.save(context, pass.copy(bookingId = 99, boardingCode = "other", payMethod = "sbp", payAmount = 900)))
        assertFalse(context.getSharedPreferences("yuldash_trippass_v2", Context.MODE_PRIVATE).contains("pass_42"))
    }
    @After fun cleanup() {
        server.release.countDown()
        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
        ApiClient.logout()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server.close()
    }

    @Test fun russianCodeErrorLoadingAndRetry() = journey(AppLanguage.Ru)
    @Test fun bashkirCodeErrorLoadingAndRetry() = journey(AppLanguage.Ba)

    private fun journey(language: AppLanguage) {
        val ba = language == AppLanguage.Ba
        val error = if (ba) "Кодты йөкләп булманы" else "Не удалось загрузить код"
        val retry = if (ba) "Кодты яңынан йөкләргә" else "Повторить загрузку кода"
        val loading = if (ba) "Код йөкләнә" else "Код загружается"
        mounted.value = true
        compose.setContent {
            YuldashTheme {
                CompositionLocalProvider(LocalAppLanguage provides language) {
                    if (mounted.value) ActiveTripScreen(ride = null, contacts = emptyList(), bookingId = 42,
                        onBack = {}, onTripEnd = {}, onSos = {})
                }
            }
        }
        compose.waitUntil(15000) { compose.onAllNodesWithText(error).fetchSemanticsNodes().isNotEmpty() &&
            server.requests.contains("GET /bookings/42/details") }
        compose.onNodeWithText(error).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(retry).assertIsDisplayed()
        screenshot(language, "error", error)
        assertEquals("1234", TripPassStore.load(context, 42)?.boardingCode)
        compose.onNodeWithText(retry).performClick()
        compose.waitUntil(15000) { server.held.count == 0L }
        compose.onNodeWithText(loading).performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText(retry).assertCountEquals(0)
        assertEquals(2, server.codeCalls.get())
        assertEquals("1234", TripPassStore.load(context, 42)?.boardingCode)
        screenshot(language, "loading", loading)
        server.release.countDown()
        compose.waitUntil(15000) { TripPassStore.load(context, 42)?.boardingCode == "5678" }
        compose.onNodeWithText("5678").performScrollTo().assertIsDisplayed()
        screenshot(language, "success", "5678")
        TripPassStore.init(context) // Real encrypted preference reopen, not process death.
        val saved = requireNotNull(TripPassStore.load(context, 42))
        assertEquals("5678", saved.boardingCode)
        assertEquals("cash", saved.payMethod)
        assertEquals(400, saved.payAmount)
        val sibling = requireNotNull(TripPassStore.load(context, 99))
        assertEquals("other", sibling.boardingCode)
        assertEquals("sbp", sibling.payMethod)
        assertEquals(900, sibling.payAmount)
        assertEquals(2, server.codeCalls.get())
        assertEquals(1, server.requests.count { it == "GET /bookings/42/messages" })
        assertFalse(server.requests.any { it.startsWith("POST /bookings/") })
        assertTrue(server.failures.toString(), server.failures.isEmpty())
        Log.i("B02CodeRetryDevice", "verified language=${language.name} pid=${Process.myPid()} codeGets=2 historyGets=1 encryptedReopen=true")
    }
    private fun screenshot(language: AppLanguage, stage: String, visibleText: String) {
        // Semantics can precede the first drawn frame of appearIn. PixelCopy confirms the
        // actual green code card behind the expected text before taking a whole-window PNG.
        compose.waitUntil(5000) {
            val pixels = compose.onNodeWithText(visibleText).captureToImage().toPixelMap()
            (0..4).any { x -> (0..4).any { y ->
                val c = pixels[(pixels.width - 1) * x / 4, (pixels.height - 1) * y / 4]
                c.red < .25f && c.green > c.red + .1f && c.blue < c.green
            } }
        }
        instrumentation.waitForIdleSync()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(context.filesDir, "b02-code-retry-${language.name}-$stage.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }

    private class Loopback : AutoCloseable {
        private val listener = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val workers = Executors.newCachedThreadPool { task -> Thread(task, "b02-code-http").apply { isDaemon = true } }
        private val sockets = CopyOnWriteArrayList<Socket>()
        val requests = CopyOnWriteArrayList<String>()
        val failures = CopyOnWriteArrayList<String>()
        val codeCalls = AtomicInteger()
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        @Volatile private var closed = false
        val url = "http://127.0.0.1:${listener.localPort}"
        private val accept = thread(isDaemon = true, name = "b02-code-accept") {
            while (!closed) {
                val socket = runCatching { listener.accept() }.getOrNull() ?: break
                sockets += socket
                workers.submit { runCatching { respond(socket) }.onFailure { if (!closed) failures += it.javaClass.simpleName } }
            }
        }
        private fun respond(socket: Socket) {
            socket.use {
                socket.soTimeout = 20000
                val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                val first = reader.readLine() ?: return
                val parts = first.split(' ')
                if (parts.size < 2) return
                while (true) {
                    val header = reader.readLine() ?: return
                    if (header.isEmpty()) break
                }
                val path = parts[1]
                requests += "${parts[0]} $path"
                var status = 200
                val response = when (path) {
                    "/bookings/42/boarding-code" -> {
                        if (codeCalls.incrementAndGet() == 1) { status = 503; "{}" }
                        else {
                            held.countDown()
                            check(release.await(30, TimeUnit.SECONDS)) { "Held retry was never released" }
                            """{"code":"5678"}"""
                        }
                    }
                    "/bookings/42/messages" -> """{"items":[]}"""
                    "/bookings/42/role" -> """{"role":"passenger","status":"confirmed","driver_phase":"departed"}"""
                    "/bookings/42/details" -> """{"booking_id":42,"role":"passenger","status":"confirmed","price":700,"pay_method":"cash","pay_amount":400}"""
                    else -> "{}"
                }.toByteArray(Charsets.UTF_8)
                socket.getOutputStream().apply {
                    write("HTTP/1.1 $status ${if (status == 200) "OK" else "Service Unavailable"}\r\nContent-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    write(response)
                    flush()
                }
            }
            sockets.remove(socket)
        }
        override fun close() {
            closed = true
            release.countDown()
            listener.close()
            sockets.forEach { runCatching { it.close() } }
            workers.shutdownNow()
            accept.join(2000)
        }
    }
}
