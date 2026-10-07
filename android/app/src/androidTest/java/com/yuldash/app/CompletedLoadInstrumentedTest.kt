package com.yuldash.app

import android.graphics.Bitmap
import android.os.Process
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuldash.app.data.ApiClient
import com.yuldash.app.ui.theme.YuldashTheme
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.json.JSONObject
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

/** Native Completed touch and loopback HTTP. No payment, real backend or push delivery. */
@RunWith(AndroidJUnit4::class)
class CompletedLoadInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext.applicationContext
    private val mounted = mutableStateOf(true)
    private val tokenA = "header.eyJzdWIiOiIxMSJ9.signature"
    private lateinit var server: Loopback
    @Before fun prepare() {
        server = Loopback(); ApiClient.resetForTest(); ApiClient.init(context)
        ApiClient.testBaseUrl = server.url; ApiClient.testTimeoutMs = 60000; ApiClient.saveToken(tokenA)
        assertFalse(ApiClient.secureStorageUnavailable)
    }
    @After fun cleanup() {
        server.release.countDown(); compose.runOnIdle { mounted.value = false }; compose.waitForIdle()
        ApiClient.resetForTest(); ApiClient.testTimeoutMs = null; server.close()
        assertTrue(server.failures.toString(), server.failures.isEmpty())
    }
    private fun mount(language: AppLanguage) {
        compose.setContent {
            YuldashTheme { CompositionLocalProvider(LocalAppLanguage provides language) {
                if (mounted.value) RideshareCompletedScreen(42, null, "passenger", "cash", 400,
                    onClose = { mounted.value = false }, onOpenReceipt = {}, onSupport = {})
            } }
        }
        compose.waitUntil(15000) { server.receipts.get() == 1 && server.tips.get() == 1 }
        compose.waitForIdle()
    }
    private fun tag(name: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("rideshareCompletedList").performScrollToNode(hasTestTag(name))
        return compose.onNodeWithTag(name)
    }
    private fun text(value: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("rideshareCompletedList").performScrollToNode(hasText(value))
        return compose.onNodeWithText(value)
    }
    private fun waitTag(name: String) = compose.waitUntil(10000) {
        runCatching { tag(name).assertIsDisplayed(); true }.getOrDefault(false)
    }
    private fun waitText(value: String, enabled: Boolean = false) = compose.waitUntil(10000) {
        runCatching { text(value).assertIsDisplayed().also { if (enabled) it.assertIsEnabled() }; true }.getOrDefault(false)
    }
    private fun shot(name: String, target: SemanticsNodeInteraction) {
        compose.waitForIdle(); target.assertIsDisplayed().captureToImage()
        instrumentation.waitForIdleSync(); Thread.sleep(150)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(context.filesDir, "b02-completed-load-$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
    @Test fun russianReceiptErrorHeldRetryAndUserRating() {
        server.receiptFailures = 1; server.held = "receipt"
        mount(AppLanguage.Ru); waitTag("rideshareReceiptRetry")
        shot("Ru-error", tag("rideshareReceiptError"))
        val old = tag("rideshareReceiptRetry").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        tag("rideshareReceiptRetry").performTouchInput { click() }
        compose.waitUntil(10000) { server.started.count == 0L }
        tag("rideshareReceiptRetry").assertIsNotEnabled()
        compose.runOnIdle { old() }; assertEquals(2, server.receipts.get())
        tag("rideshareStar4").performTouchInput { click() }
        server.release.countDown()
        waitText("Оплата подтверждена")
        compose.onNodeWithTag("rideshareRatingSubmit").assertIsEnabled().performTouchInput { click() }
        compose.waitUntil(10000) { server.bodies.any { it.first == "POST /bookings/42/rate" } }
        assertEquals(4, JSONObject(server.bodies.single { it.first == "POST /bookings/42/rate" }.second).getInt("stars"))
        shot("Ru-loaded", tag("ridesharePaymentSummary"))
        text("Оплата подтверждена").assertIsDisplayed()
        assertEquals(2, server.receipts.get()); assertEquals(1, server.tips.get())
        assertEquals(0, server.posts()); assertTrue(server.requests.filter { it.first != "GET /health" }.all { it.second == "Bearer $tokenA" })
        Log.i("B02CompletedLoadDevice", "verified Ru receipt503/heldRetry/rating4 actualHttp pid=${Process.myPid()} receiptGets=2 tipGets=1 posts=0 ratingPost4=1")
    }
    @Test fun bashkirTipErrorAndTruncatedCommittedThanksReconcile() {
        server.tipFailures = 1; server.held = "tip"; server.truncateThanks = true
        mount(AppLanguage.Ba); waitTag("rideshareThanksRetry")
        shot("Ba-error", tag("rideshareThanksError"))
        val oldRetry = tag("rideshareThanksRetry").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        tag("rideshareThanksRetry").performTouchInput { click() }
        compose.waitUntil(10000) { server.started.count == 0L }
        text("«Рәхмәт» әйтеү").assertIsNotEnabled()
        compose.runOnIdle { oldRetry() }; assertEquals(2, server.tips.get()); assertEquals(0, server.posts())
        server.release.countDown()
        waitText("«Рәхмәт» әйтеү", enabled = true)
        val oldSend = text("«Рәхмәт» әйтеү").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        text("«Рәхмәт» әйтеү").performTouchInput { click() }; waitTag("rideshareThanksRetry")
        compose.runOnIdle { oldSend() }; assertEquals(1, server.posts())
        tag("rideshareThanksRetry").performTouchInput { click() }
        waitText("Рәхмәт ебәрелде")
        shot("Ba-saved", text("Рәхмәт һаҡланды"))
        assertEquals(3, server.tips.get()); assertEquals(1, server.posts())
        assertTrue(server.requests.filter { it.first != "GET /health" }.all { it.second == "Bearer $tokenA" })
        Log.i("B02CompletedLoadDevice", "verified Ba tip503/heldRetry/truncatedThanks/reconcile actualHttp pid=${Process.myPid()} receiptGets=1 tipGets=3 posts=1 saved=true")
    }
    private inner class Loopback : AutoCloseable {
        val listener = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${listener.localPort}"
        val requests = CopyOnWriteArrayList<Pair<String, String?>>()
        val bodies = CopyOnWriteArrayList<Pair<String, String>>()
        val failures = CopyOnWriteArrayList<String>()
        val sockets = CopyOnWriteArrayList<Socket>()
        val receipts = AtomicInteger(); val tips = AtomicInteger()
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        @Volatile var held: String? = null
        @Volatile var receiptFailures = 0
        @Volatile var tipFailures = 0
        @Volatile var truncateThanks = false
        @Volatile var thanked = false
        @Volatile private var closed = false
        private val workers = Executors.newCachedThreadPool()
        private val acceptor = thread(name = "audit-completed-load-loopback") {
            try { while (!closed) { val socket = listener.accept(); sockets += socket; workers.execute { serve(socket) } } }
            catch (e: Exception) { if (!closed) failures += e.toString() }
        }
        fun posts() = requests.count { it.first == "POST /bookings/42/thanks" }
        private fun serve(socket: Socket) {
            try { socket.use {
                val input = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                val first = input.readLine().split(' '); val method = first[0]; val path = first[1]
                val headers = mutableMapOf<String, String>()
                while (true) { val line = input.readLine() ?: break; if (line.isEmpty()) break
                    headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim() }
                val chars = CharArray(headers["content-length"]?.toIntOrNull() ?: 0)
                var read = 0; while (read < chars.size) { val n = input.read(chars, read, chars.size - read); check(n > 0); read += n }
                requests += "$method $path" to headers["authorization"]
                bodies += "$method $path" to String(chars)
                val kind = when (path) {
                    "/trips/42/receipt" -> "receipt"
                    "/bookings/42/tip" -> "tip"
                    "/bookings/42/thanks" -> "thanks"
                    "/bookings/42/rate" -> "rate"
                    "/health" -> { check(method == "GET"); "health" }
                    else -> error("Unexpected $method $path")
                }
                val ordinal = when (kind) { "receipt" -> receipts.incrementAndGet(); "tip" -> tips.incrementAndGet(); else -> 1 }
                if (kind == "thanks") thanked = true
                if (kind == held && ordinal == 2) {
                    started.countDown(); if (!release.await(40, TimeUnit.SECONDS)) failures += "$kind timeout"
                }
                val code = if ((kind == "receipt" && ordinal <= receiptFailures) || (kind == "tip" && ordinal <= tipFailures)) 503 else 200
                val body = if (code != 200) "{}" else when (kind) {
                    "receipt" -> """{"booking_id":42,"ride_id":9,"role":"passenger","from_city":"Уфа","to_city":"Бирск","amount":400,"pay_method":"cash","paid":true,"counterparty_name":"Водитель","my_stars":0}"""
                    "tip" -> """{"driver_name":"Водитель","already_thanked":$thanked}"""
                    "health" -> """{"status":"ok"}"""
                    else -> "{}"
                }
                val bytes = body.toByteArray(Charsets.UTF_8); val output = socket.getOutputStream()
                output.write("HTTP/1.1 $code Local\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                output.write(if (kind == "thanks" && truncateThanks) bytes.copyOf(1) else bytes); output.flush()
            } } catch (e: Exception) { if (!closed) failures += e.toString() }
            finally { sockets.remove(socket) }
        }
        override fun close() {
            closed = true; listener.close(); sockets.forEach { runCatching { it.close() } }; workers.shutdownNow()
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS)); acceptor.join(5000); assertFalse(acceptor.isAlive)
        }
    }
}
