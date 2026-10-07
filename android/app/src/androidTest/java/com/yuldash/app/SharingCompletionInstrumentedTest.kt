package com.yuldash.app

import android.graphics.Bitmap
import android.os.Process
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuldash.app.data.*
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
import kotlin.concurrent.thread

/** Real native Compose and loopback HTTP. No external link delivery; WS fixture returns 404. */
@RunWith(AndroidJUnit4::class)
class SharingCompletionInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext.applicationContext
    private val mounted = mutableStateOf(true)
    private val tokenA = "header.eyJzdWIiOiIxMSJ9.signature"
    private val tokenB = "header.eyJzdWIiOiIzMyJ9.signature"
    private lateinit var server: Loopback
    private var receipts = 0
    private var backs = 0
    private var ends = 0
    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    @Before fun prepare() {
        server = Loopback()
        ApiClient.resetForTest(); ApiClient.init(context)
        ApiClient.testBaseUrl = server.url; ApiClient.testTimeoutMs = 60000
        ApiClient.saveToken(tokenA)
        assertFalse(ApiClient.secureStorageUnavailable)
        assertTrue(TripPassStore.save(context, TripPass.fromJson(JSONObject().put("booking_id", 42).put("boarding_code", "1234"))))
    }
    @After fun cleanup() {
        server.release.countDown()
        compose.runOnIdle {
            mounted.value = false
            if (owner.registry.currentState != Lifecycle.State.INITIALIZED) owner.registry.currentState = Lifecycle.State.DESTROYED
        }
        compose.waitForIdle(); ApiClient.resetForTest(); ApiClient.testTimeoutMs = null
        server.close(); assertTrue(server.failures.toString(), server.failures.isEmpty())
    }
    private fun touch(text: String) = compose.onNodeWithText(text).performTouchInput { click() }
    private fun scroll(text: String) {
        compose.onNode(hasScrollToNodeAction() and SemanticsMatcher.keyIsDefined(
            androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange)).performScrollToNode(hasText(text))
    }
    private fun shot(name: String, text: String) {
        compose.waitForIdle(); val node = compose.onNodeWithText(text).assertIsDisplayed()
        node.captureToImage(); instrumentation.waitForIdleSync(); Thread.sleep(150)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(context.filesDir, "b02-sharing-completion-$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
    @Test fun russianShareResponseAfterDoneKeepsCompletedScreen() {
        compose.setContent {
            YuldashTheme { CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru, LocalLifecycleOwner provides owner) {
                if (mounted.value) ActiveTripScreen(null, listOf(TrustedContact("Близкий", "Друг", "+70000000000", false, 7)), 42,
                    onBack = { backs++; mounted.value = false }, onTripEnd = { ends++; mounted.value = false }, onSos = {})
                else Text("Экран закрыт")
            } }
        }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil(15000) { compose.onAllNodesWithText("Я сел").fetchSemanticsNodes().isNotEmpty() }
        scroll("Поделиться поездкой с близким"); touch("Поделиться поездкой с близким")
        compose.waitUntil(10000) { compose.onAllNodesWithText("Близкий").fetchSemanticsNodes().isNotEmpty() }
        shot("Ru-sharing", "Кому отправить поездку")
        touch("Близкий"); assertTrue(server.started.await(10, TimeUnit.SECONDS))
        server.status = "done"
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.waitForIdle()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil(15000) { compose.onAllNodesWithText("Поездка завершена").fetchSemanticsNodes().isNotEmpty() }
        server.release.countDown(); assertTrue(server.replied.await(10, TimeUnit.SECONDS))
        compose.waitForIdle(); Thread.sleep(300)
        compose.onNodeWithText("Кому отправить поездку").assertDoesNotExist()
        compose.onNodeWithText("Активные ссылки").assertDoesNotExist()
        assertEquals(1, server.requests.count { it.first == "POST /bookings/42/share" })
        assertEquals(7, JSONObject(server.requests.single { it.first == "POST /bookings/42/share" }.third).getInt("contact_id"))
        shot("Ru-completed", "Поездка завершена")
        compose.runOnIdle { ApiClient.saveToken(tokenB) }
        compose.waitUntil(10000) { compose.onAllNodesWithText("Экран закрыт").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("Parent and completed child exited twice", 1, backs + ends)
        Log.i("B02ShareDevice", "verified Ru sharing->done->login actualHttp pid=${Process.myPid()} posts=1 exits=1")
    }
    @Test fun bashkirCompletedActionsAndOldReceiptAfterAccountSwitch() {
        compose.setContent {
            YuldashTheme { CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                if (mounted.value) RideshareCompletedScreen(42, null, "passenger", "cash", 400,
                    onClose = { mounted.value = false }, onOpenReceipt = { receipts++ }, onSupport = {})
                else Text("Экран ябылған")
            } }
        }
        compose.waitUntil(10000) { server.requests.any { it.first == "GET /trips/42/receipt" } }
        compose.waitUntil(10000) { compose.onAllNodesWithTag("rideshareReceiptLoading").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("rideshareStar4").performScrollTo().performTouchInput { click() }
        compose.onNodeWithTag("rideshareRatingSubmit").performTouchInput { click() }
        compose.waitUntil(10000) { server.requests.any { it.first == "POST /bookings/42/rate" } }
        scroll("«Рәхмәт» әйтеү"); touch("«Рәхмәт» әйтеү")
        compose.waitUntil(10000) { server.requests.any { it.first == "POST /bookings/42/thanks" } }
        scroll("Әйбер оноттоңмо?"); touch("Әйбер оноттоңмо?")
        compose.waitUntil(10000) { compose.onAllNodesWithText("Сәфәр чаты яңынан асылды").fetchSemanticsNodes().isNotEmpty() }
        shot("Ba-completed", "Сәфәр чаты яңынан асылды")
        scroll("Сәфәр квитанцияһы")
        val oldReceipt = compose.onNodeWithText("Сәфәр квитанцияһы").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        touch("Сәфәр квитанцияһы"); assertEquals(1, receipts)
        compose.runOnIdle { ApiClient.saveToken(tokenB); oldReceipt() }
        compose.waitUntil(10000) { compose.onAllNodesWithText("Экран ябылған").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, receipts)
        assertEquals(4, JSONObject(server.requests.single { it.first == "POST /bookings/42/rate" }.third).getInt("stars"))
        assertTrue(server.requests.filter { it.first.contains("/bookings/42/") || it.first.contains("/trips/42/") }.all { it.second == "Bearer $tokenA" })
        shot("Ba-closed", "Экран ябылған")
        Log.i("B02ShareDevice", "verified Ba completed actions actualHttp oldReceiptRejected pid=${Process.myPid()} stars=4")
    }
    private inner class Loopback : AutoCloseable {
        val listener = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${listener.localPort}"
        val requests = CopyOnWriteArrayList<Triple<String, String?, String>>()
        val failures = CopyOnWriteArrayList<String>()
        val sockets = CopyOnWriteArrayList<Socket>()
        val started = CountDownLatch(1); val release = CountDownLatch(1); val replied = CountDownLatch(1)
        @Volatile var status = "confirmed"
        @Volatile private var closed = false
        private val workers = Executors.newCachedThreadPool()
        private val acceptor = thread(name = "audit-share-loopback") {
            try { while (!closed) { val socket = listener.accept(); sockets += socket; workers.execute { serve(socket) } } }
            catch (e: Exception) { if (!closed) failures += e.toString() }
        }
        private fun serve(socket: Socket) {
            try { socket.use {
                val input = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                val first = input.readLine().split(' '); val method = first[0]; val path = first[1]
                val headers = mutableMapOf<String, String>()
                while (true) { val line = input.readLine() ?: break; if (line.isEmpty()) break
                    headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim() }
                val chars = CharArray(headers["content-length"]?.toIntOrNull() ?: 0)
                var read = 0; while (read < chars.size) { val n = input.read(chars, read, chars.size - read); check(n > 0); read += n }
                requests += Triple("$method $path", headers["authorization"], String(chars))
                if (path == "/bookings/42/share") { started.countDown(); if (!release.await(40, TimeUnit.SECONDS)) failures += "share timeout" }
                var code = 200
                val body = when (path) {
                    "/bookings/42/role" -> """{"role":"passenger","status":"$status","driver_phase":"departed"}"""
                    "/bookings/42/messages", "/bookings/42/shares" -> """{"items":[]}"""
                    "/bookings/42/share" -> """{"id":91,"contact_id":7,"link":"https://yulbash.ru/s/audit-local-only"}"""
                    "/bookings/42/boarding-code" -> """{"code":"1234"}"""
                    "/bookings/42/details" -> """{"booking_id":42,"role":"passenger","status":"$status","pay_method":"cash","pay_amount":400}"""
                    "/trips/42/receipt" -> """{"booking_id":42,"role":"passenger","from_city":"Уфа","to_city":"Бирск","amount":400,"paid":true,"my_stars":0}"""
                    "/bookings/42/tip" -> """{"already_thanked":false}"""
                    "/bookings/42/rate", "/bookings/42/thanks", "/bookings/42/lost-item", "/auth/logout", "/push/unregister" -> "{}"
                    "/ws/bookings/42" -> { code = 404; "{}" }
                    else -> { failures += "Unexpected $method $path"; code = 404; "{}" }
                }
                val bytes = body.toByteArray(Charsets.UTF_8)
                val output = socket.getOutputStream()
                output.write("HTTP/1.1 $code Local\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                output.write(bytes); output.flush()
                if (path == "/bookings/42/share") replied.countDown()
            } } catch (e: Exception) { if (!closed) failures += e.toString() }
            finally { sockets.remove(socket) }
        }
        override fun close() {
            closed = true; listener.close(); sockets.forEach { runCatching { it.close() } }; workers.shutdownNow()
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS)); acceptor.join(5000); assertFalse(acceptor.isAlive)
        }
    }
}
