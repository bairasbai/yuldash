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
class SharingLoadInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext.applicationContext
    private val mounted = mutableStateOf(true)
    private val tokenA = "header.eyJzdWIiOiIxMSJ9.signature"
    private lateinit var server: Loopback
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
        server.release.countDown(); server.releaseList.countDown()
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
        File(context.filesDir, "b02-sharing-load-$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    private fun mount(language: AppLanguage) {
        compose.setContent {
            YuldashTheme { CompositionLocalProvider(LocalAppLanguage provides language, LocalLifecycleOwner provides owner) {
                if (mounted.value) ActiveTripScreen(null, listOf(
                    TrustedContact("Близкий", "Друг", "+70000000000", false, 7),
                    TrustedContact("Другой", "Друг", "+70000000001", false, 8)), 42,
                    onBack = { mounted.value = false }, onTripEnd = { mounted.value = false }, onSos = {})
            } }
        }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil(15000) { server.requests.any { it.first == "GET /bookings/42/details" } }
        val title = if (language == AppLanguage.Ru) "Поделиться поездкой с близким" else "Сәфәрҙе яҡының менән уртаҡлашыу"
        scroll(title); touch(title)
        compose.waitUntil(10000) { compose.onAllNodesWithTag("tripSharesError").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun link(id: Int) = server.url + "/t/token$id"
    private fun retry() = compose.onNodeWithTag("tripSharesRetry").performTouchInput { click() }
    private fun waitText(text: String) = compose.waitUntil(10000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun assertOwnRequests() = assertTrue(server.requests.filter { it.first.contains("/bookings/42/") }.all { it.second == "Bearer $tokenA" })
    @Test fun russianRetryLoadingAndDoubleRevokeSelectRemainingToken() {
        server.rows[91] = server.dto; server.rows[92] = server.dto2
        server.heldListOrdinal = 2; server.heldMutation = "DELETE /bookings/42/share/91"
        mount(AppLanguage.Ru)
        shot("Ru-error", "Не удалось загрузить ссылки. Проверь сеть и повтори.")
        // GET is launched by recomposition; let the Compose test clock deliver that frame.
        retry(); compose.waitUntil(10000) { server.listStarted.count == 0L }
        compose.onNodeWithTag("tripSharesLoading").assertIsDisplayed()
        compose.onNodeWithText("Близкий").assertDoesNotExist(); assertEquals(0, server.mutations())
        server.releaseList.countDown(); waitText(link(91))
        compose.onAllNodesWithText("Отозвать")[0].performTouchInput { click(); click() }
        assertTrue(server.started.await(10, TimeUnit.SECONDS))
        compose.onAllNodesWithText("Отозвать").assertAll(isNotEnabled())
        assertEquals(1, server.mutations())
        server.release.countDown(); waitText(link(92))
        compose.onNodeWithText(link(91)).assertDoesNotExist()
        shot("Ru-token92", link(92))
        assertEquals(2, server.listCalls.get()); assertEquals(setOf(92), server.rows.keys)
        assertOwnRequests()
        Log.i("B02ShareLoadDevice", "verified Ru loading/retry/doubleRevoke actualHttp pid=${Process.myPid()} gets=2 mutations=1 token92")
    }
    @Test fun bashkirCommittedCreate429ReconcilesWithoutDuplicate() {
        server.heldMutation = "POST /bookings/42/share"; server.createCode = 429
        mount(AppLanguage.Ba)
        shot("Ba-error", "Һылтанмаларҙы йөкләп булманы. Селтәрҙе тикшереп ҡабатла.")
        retry(); waitText("Близкий")
        val old = compose.onNodeWithText("Близкий").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        touch("Близкий"); assertTrue(server.started.await(10, TimeUnit.SECONDS))
        compose.runOnIdle { old() }
        compose.onNodeWithText("Близкий").assertIsNotEnabled()
        compose.onNodeWithText("Другой").assertIsNotEnabled(); assertEquals(1, server.mutations())
        server.release.countDown()
        waitText("Һөҙөмтәне раҫлап булманы. Һылтанмалар исемлеген яңырт.")
        compose.runOnIdle { old() }; assertEquals(1, server.mutations())
        retry(); waitText(link(91)); compose.onNodeWithTag("tripSharesError").assertDoesNotExist()
        shot("Ba-token91", link(91))
        assertEquals(3, server.listCalls.get()); assertEquals(1, server.mutations())
        assertEquals(7, JSONObject(server.requests.single { it.first == "POST /bookings/42/share" }.third).getInt("contact_id"))
        assertOwnRequests()
        Log.i("B02ShareLoadDevice", "verified Ba committedCreate429/reconcile actualHttp pid=${Process.myPid()} gets=3 mutations=1 token91")
    }
    private inner class Loopback : AutoCloseable {
        val listener = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${listener.localPort}"
        val requests = CopyOnWriteArrayList<Triple<String, String?, String>>()
        val failures = CopyOnWriteArrayList<String>()
        val sockets = CopyOnWriteArrayList<Socket>()
        
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        val listStarted = CountDownLatch(1); val releaseList = CountDownLatch(1)
        val listCalls = java.util.concurrent.atomic.AtomicInteger()
        val rows = java.util.concurrent.ConcurrentHashMap<Int, String>()
        val dto = """{"id":91,"contact_id":7,"token":"token91"}"""
        val dto2 = """{"id":92,"contact_id":8,"token":"token92"}"""
        @Volatile var heldMutation: String? = null
        @Volatile var heldListOrdinal = -1
        @Volatile var createCode = 200
        fun mutations() = requests.count { it.first == "POST /bookings/42/share" || it.first.startsWith("DELETE /bookings/42/share/") }

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

                val key = "$method $path"
                var code = 200
                val body = when {
                    path == "/bookings/42/shares" -> {
                        val ordinal = listCalls.incrementAndGet()
                        val snapshot = rows.toSortedMap().values.joinToString(",", "[", "]")
                        if (ordinal == heldListOrdinal) {
                            listStarted.countDown(); if (!releaseList.await(40, TimeUnit.SECONDS)) failures += "list timeout"
                        }
                        if (ordinal == 1) { code = 503; "{}" } else snapshot
                    }
                    key == "POST /bookings/42/share" -> {
                        rows[91] = dto
                        if (key == heldMutation) { started.countDown(); if (!release.await(40, TimeUnit.SECONDS)) failures += "create timeout" }
                        code = createCode; dto
                    }
                    key.startsWith("DELETE /bookings/42/share/") -> {
                        rows.remove(path.substringAfterLast('/').toInt())
                        if (key == heldMutation) { started.countDown(); if (!release.await(40, TimeUnit.SECONDS)) failures += "revoke timeout" }
                        "{}"
                    }
                    path == "/bookings/42/role" -> """{"role":"passenger","status":"$status","driver_phase":"departed"}"""
                    path == "/bookings/42/messages" -> """{"items":[]}"""
                    path == "/bookings/42/boarding-code" -> """{"code":"1234"}"""
                    path == "/bookings/42/details" -> """{"booking_id":42,"role":"passenger","status":"$status","pay_method":"cash","pay_amount":400}"""
                    path == "/trips/42/receipt" -> """{"booking_id":42,"role":"passenger","amount":400,"paid":true,"my_stars":0}"""
                    path == "/bookings/42/tip" -> """{"already_thanked":false}"""
                    path == "/auth/logout" || path == "/push/unregister" -> "{}"
                    path == "/ws/bookings/42" -> { code = 404; "{}" }
                    else -> { failures += "Unexpected $method $path"; code = 404; "{}" }
                }
                val bytes = body.toByteArray(Charsets.UTF_8)
                val output = socket.getOutputStream()
                output.write("HTTP/1.1 $code Local\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                output.write(bytes); output.flush()
            } } catch (e: Exception) { if (!closed) failures += e.toString() }
            finally { sockets.remove(socket) }
        }
        override fun close() {
            closed = true; listener.close(); sockets.forEach { runCatching { it.close() } }; workers.shutdownNow()
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS)); acceptor.join(5000); assertFalse(acceptor.isAlive)
        }
    }
}
