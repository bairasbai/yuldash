package com.yuldash.app

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Process
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.Outbox
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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** QA-B02-004: real Android dialog, disk-backed synthetic stores and controlled loopback HTTP.
 * The refused commit is injected, not an actual full disk/Keystore failure. Use StorageAuditRunner.
 */
@RunWith(AndroidJUnit4::class)
class ActiveTripRemovalDialogInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext.applicationContext
    private val mounted = mutableStateOf(false)
    private lateinit var plain: RefusingPreferences
    private lateinit var secure: SharedPreferences
    private lateinit var server: Loopback
    private val trace = CopyOnWriteArrayList<String>()
    @Volatile private var finished = 0

    @Before fun prepare() {
        server = Loopback()
        ApiClient.resetForTest()
        ApiClient.testBaseUrl = server.url
        ApiClient.testTimeoutMs = 15000
        ApiClient.testTrace = { trace += it }
        ApiClient.init(context)
        ApiClient.logout() // No authenticated WebSocket or external live-map coordinates.
        plain = RefusingPreferences(context.getSharedPreferences("b02_dialog_plain", Context.MODE_PRIVATE))
        secure = context.getSharedPreferences("b02_dialog_secure", Context.MODE_PRIVATE)
        assertTrue(plain.edit().clear().commit())
        assertTrue(secure.edit().clear().commit())
        TripPassStore.initStores(plain, secure)
        Outbox.initStores(context.getSharedPreferences("b02_dialog_outbox", Context.MODE_PRIVATE), null)
        val pass = TripPass.fromJson(JSONObject().put("booking_id", 42).put("boarding_code", "old-code"))
        assertTrue(TripPassStore.save(context, pass))
        assertTrue(TripPassStore.save(context, pass.copy(bookingId = 99, boardingCode = "unrelated")))
        plain.allowCommit = false
        assertFalse("Fixture must refuse chained commits", plain.edit().putString("fixture_probe", "x").commit())
        assertFalse(plain.contains("fixture_probe"))
    }

    @After fun cleanup() {
        Log.i("B02DeviceAudit", "diagnostic requests=${server.requests} failures=${server.failures} trace=$trace")
        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
        plain.allowCommit = true
        ApiClient.logout()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        ApiClient.testTrace = null
        TripPassStore.initStores(plain, null)
        Outbox.initStores(context.getSharedPreferences("b02_dialog_outbox", Context.MODE_PRIVATE), null)
        server.close()
    }

    @Test fun russianDialogRetriesOnlyLocalRemoval() = journey(AppLanguage.Ru)
    @Test fun bashkirDialogRetriesOnlyLocalRemoval() = journey(AppLanguage.Ba)

    private fun journey(language: AppLanguage) {
        val ba = language == AppLanguage.Ba
        mounted.value = true
        compose.setContent {
            YuldashTheme {
                CompositionLocalProvider(LocalAppLanguage provides language) {
                    if (mounted.value) ActiveTripScreen(ride = null, contacts = emptyList(), bookingId = 42,
                        onBack = {}, onTripEnd = { finished++ }, onSos = {})
                }
            }
        }
        waitFor(if (ba) "Мин ултырҙым" else "Я сел")
        val cancelLabel = if (ba) "Сәфәрҙе кире алыу" else "Отменить поездку"
        compose.onNode(hasScrollToNodeAction() and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasText(cancelLabel))
        compose.onNodeWithText(cancelLabel).performClick()
        compose.onNodeWithText(if (ba) "Эйе, кире алырға" else "Да, отменить").performClick()
        val title = if (ba) "Һаҡланған сәфәрҙе юйып булманы" else "Не удалось удалить сохранённую поездку"
        waitFor(title)
        compose.onNodeWithText(title).assertIsDisplayed()
        assertEquals(1, server.cancels.get())
        assertEquals(0, finished)
        assertTrue(secure.contains("pass_42"))
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        val file = File(context.filesDir, "b02-removal-dialog-${language.name}.png")
        file.outputStream().use { assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        screenshot.recycle()
        plain.allowCommit = true
        compose.onNodeWithText(if (ba) "Тағы юйырға" else "Повторить удаление").performClick()
        compose.waitUntil(15000) { finished == 1 }
        compose.onAllNodesWithText(title).assertCountEquals(0)
        assertEquals("Retry repeated server cancellation", 1, server.cancels.get())
        assertEquals(1, server.requests.count { it == "POST /bookings/42/cancel" })
        assertFalse(secure.contains("pass_42"))
        assertNull(TripPassStore.load(context, 42))
        assertEquals("unrelated", TripPassStore.load(context, 99)?.boardingCode)
        assertTrue("Loopback fixture failures: ${server.failures}", server.failures.isEmpty())
        // Reinitialize disk-backed stores to check that successful cleanup survives reopening.
        TripPassStore.initStores(plain, secure)
        assertNull(TripPassStore.load(context, 42))
        assertEquals("unrelated", TripPassStore.load(context, 99)?.boardingCode)
        Log.i("B02DeviceAudit", "verified language=${language.name} pid=${Process.myPid()} cancelPosts=1 localRetry=1 screenshot=${file.name}")
    }

    private fun waitFor(text: String) = compose.waitUntil(15000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private class RefusingPreferences(private val delegate: SharedPreferences) : SharedPreferences by delegate {
        @Volatile var allowCommit = true
        override fun edit(): SharedPreferences.Editor {
            val editor = delegate.edit()
            return object : SharedPreferences.Editor by editor {
                override fun putString(key: String?, value: String?) = apply { editor.putString(key, value) }
                override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { editor.putStringSet(key, values) }
                override fun putInt(key: String?, value: Int) = apply { editor.putInt(key, value) }
                override fun putLong(key: String?, value: Long) = apply { editor.putLong(key, value) }
                override fun putFloat(key: String?, value: Float) = apply { editor.putFloat(key, value) }
                override fun putBoolean(key: String?, value: Boolean) = apply { editor.putBoolean(key, value) }
                override fun remove(key: String?) = apply { editor.remove(key) }
                override fun clear() = apply { editor.clear() }
                override fun commit(): Boolean = allowCommit && editor.commit()
            }
        }
    }

    private class Loopback : AutoCloseable {
        private val listener = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val workers = Executors.newCachedThreadPool { task -> Thread(task, "b02-loopback").apply { isDaemon = true } }
        private val sockets = CopyOnWriteArrayList<Socket>()
        val requests = CopyOnWriteArrayList<String>()
        val failures = CopyOnWriteArrayList<String>()
        val cancels = AtomicInteger()
        @Volatile private var closed = false
        @Volatile private var status = "confirmed"
        val url = "http://127.0.0.1:${listener.localPort}"
        private val accept = thread(isDaemon = true, name = "b02-loopback-accept") {
            while (!closed) {
                val socket = runCatching { listener.accept() }.getOrNull() ?: break
                sockets += socket
                workers.submit { runCatching { respond(socket) }.onFailure { if (!closed) failures += it.javaClass.simpleName } }
            }
        }
        private fun respond(socket: Socket) {
            socket.use {
                socket.soTimeout = 15000
                val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                val first = reader.readLine() ?: return
                val parts = first.split(' ')
                if (parts.size < 2) return
                val method = parts[0]
                val path = parts[1]
                var length = 0
                while (true) {
                    val header = reader.readLine() ?: return
                    if (header.isEmpty()) break
                    if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
                }
                repeat(length) { reader.read() }
                requests += "$method $path"
                val body = when (path) {
                    "/bookings/42/cancel" -> {
                        if (method == "POST") { cancels.incrementAndGet(); status = "cancelled" }
                        """{"status":"cancelled","contact_then_cancel":false}"""
                    }
                    "/bookings/42/role" -> """{"role":"passenger","status":"$status","driver_phase":"departed"}"""
                    "/bookings/42/messages" -> """{"items":[]}"""
                    "/bookings/42/boarding-code" -> """{"code":"1234"}"""
                    "/bookings/42/details" -> """{"booking_id":42,"role":"passenger","status":"$status"}"""
                    else -> "{}"
                }.toByteArray(Charsets.UTF_8)
                socket.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    write(body)
                    flush()
                }
            }
            sockets.remove(socket)
        }
        override fun close() {
            closed = true
            listener.close()
            sockets.forEach { runCatching { it.close() } }
            workers.shutdownNow()
            accept.join(2000)
        }
    }
}
