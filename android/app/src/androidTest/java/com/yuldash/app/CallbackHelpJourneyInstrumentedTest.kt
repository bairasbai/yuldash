package com.yuldash.app

import android.content.Context
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuldash.app.data.ApiClient
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Actual SimpleMode navigation, real ApiClient HTTP and screen on Android.
 * Loopback is a controlled HTTP substitute, not FastAPI/Telegram/operator delivery.
 * StorageAuditRunner suppresses product Application hooks; use the isolated emulator.
 */
@RunWith(AndroidJUnit4::class)
class CallbackHelpJourneyInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
    private val mounted = mutableStateOf(true)
    private lateinit var server: Loopback
    private lateinit var vm: YuldashViewModel
    private var lang = AppLanguage.Ru
    private val token = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxIn0.synthetic-signature"
    private var contentSet = false

    @Before fun setup() {
        val context = instrumentation.targetContext.applicationContext
        assertTrue(context.getSharedPreferences("yuldash_prefs", Context.MODE_PRIVATE).edit()
            .clear().putBoolean("onboarding_completed", true).commit())
        assertTrue("This request profile needs no configured support phone", BuildConfig.YULDASH_SUPPORT_PHONE.isBlank())
        server = Loopback()
        ApiClient.resetForTest()
        ApiClient.testBaseUrl = server.url
        ApiClient.testTimeoutMs = 15_000
        ApiClient.init(context); ApiClient.logout(); ApiClient.init(context)
        ApiClient.saveToken(token); ApiClient.saveName("QA_SYNTH_CALLBACK_DEVICE"); ApiClient.saveRole("passenger")
    }

    @After fun cleanup() {
        server.release.countDown()
        if (contentSet) { compose.runOnIdle { mounted.value = false }; compose.waitForIdle() }
        owner.viewModelStore.clear()
        ApiClient.logout(); ApiClient.resetForTest(); ApiClient.testTimeoutMs = null
        server.close()
    }

    @Test fun russianPendingAndPhysicalDoubleTapSendOneRequest() = journey(AppLanguage.Ru, 200)
    @Test fun bashkirFailurePreservesDraftAndRetryUsesTheSameText() = journey(AppLanguage.Ba, 503)

    private fun journey(language: AppLanguage, firstStatus: Int) {
        lang = language; server.firstStatus = firstStatus
        vm = ViewModelProvider(owner, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
                YuldashViewModel(SavedStateHandle(mapOf("yuldash_screen" to Screen.SimpleMode.name,
                    "yuldash_lang" to lang.name))) as T
        })[YuldashViewModel::class.java]
        contentSet = true
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                YuldashTheme { YuldashApp() }
            }
        }
        val entry = if (lang == AppLanguage.Ba) "Миңә шылтырат" else "Позвони мне"
        compose.waitUntil(15_000) { compose.onAllNodesWithText(entry).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(Screen.SimpleMode, vm.screen.value)
        compose.onNodeWithText(entry).performScrollTo().performClick()
        compose.waitUntil(15_000) { vm.screen.value == Screen.CallbackHelp &&
            compose.onAllNodesWithTag("callback_btn").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, server.records.size)
        val draft = if (lang == AppLanguage.Ba) "QA_SYNTH_DEVICE: Өфөгә иртәгә сәфәр табырға ярҙам ит 🌿" else "QA_SYNTH_DEVICE: помоги найти поездку завтра"
        compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement(draft)
        button().performScrollTo().performTouchInput { click() }
        compose.waitUntil(15_000) { server.entered.count == 0L }
        assertFalse(vm.callbackRequested.value)
        noConfirmation()
        button().assertIsNotEnabled().performTouchInput { click() }
        compose.waitForIdle()
        assertEquals(1, server.records.size)
        assertEquals(0L, server.records[0].returnedAt)
        screenshot("pending")
        marker("pending")
        server.release.countDown()
        if (firstStatus == 503) {
            compose.waitUntil(15_000) {
                server.records[0].status == 503 &&
                    !button().fetchSemanticsNode().config.contains(SemanticsProperties.Disabled)
            }
            assertFalse(vm.callbackRequested.value); noConfirmation()
            assertEquals(draft, draft())
            assertEquals(1, server.records.size)
            screenshot("after-503"); marker("after-503")
            button().assertIsEnabled().performClick()
        }
        compose.waitUntil(15_000) { vm.callbackRequested.value &&
            compose.onAllNodesWithText(confirmation()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(confirmation()).performScrollTo().assertIsDisplayed()
        assertEquals(if (firstStatus == 503) 2 else 1, server.records.size)
        assertFalse("A deadline is a fixture error", server.timedOut.get())
        server.records.forEach {
            assertTrue(it.authMatches && it.returnedAt > it.receivedAt)
            assertEquals(draft, JSONObject(it.body).getString("note"))
        }
        assertEquals(200, server.records.last().status)
        if (firstStatus == 503) assertTrue(server.records[1].receivedAt > server.records[0].returnedAt)
        screenshot("accepted"); marker("accepted")
    }

    private fun button() = compose.onNodeWithTag("callback_btn")
    private fun confirmation() = if (lang == AppLanguage.Ba) "Шылтыратыу һоралды" else "Звонок запрошен"
    private fun noConfirmation() = compose.onNodeWithText(confirmation()).assertDoesNotExist()
    private fun draft() = compose.onNode(hasSetTextAction()).fetchSemanticsNode().config[SemanticsProperties.EditableText].text
    private fun screenshot(stage: String) {
        compose.waitForIdle()
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), "qa-callback-${lang.name}-$stage.png")
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { output.outputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }
    private fun marker(stage: String) {
        val metrics = instrumentation.targetContext.resources.displayMetrics
        // LazyColumn may dispose an offscreen CTA when the accepted card is scrolled into view.
        val buttons = compose.onAllNodesWithTag("callback_btn").fetchSemanticsNodes()
        val records = org.json.JSONArray()
        server.records.forEach { records.put(JSONObject().put("note", JSONObject(it.body).getString("note"))
            .put("authorizationMatchesSynthetic", it.authMatches).put("receivedAt", it.receivedAt)
            .put("returnedAt", it.returnedAt).put("status", it.status)) }
        Log.i("QA_CALLBACK_DEVICE", JSONObject().put("stage", stage).put("language", lang.name)
            .put("screen", vm.screen.value.name).put("requested", vm.callbackRequested.value)
            .put("buttonComposed", buttons.size)
            .put("disabled", buttons.firstOrNull()?.config?.contains(SemanticsProperties.Disabled) ?: JSONObject.NULL)
            .put("width", metrics.widthPixels).put("height", metrics.heightPixels)
            .put("fontScale", instrumentation.targetContext.resources.configuration.fontScale)
            .put("gateTimedOut", server.timedOut.get()).put("requests", records).toString())
    }

    private inner class Loopback : AutoCloseable {
        private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val running = AtomicBoolean(true)
        private val workers = Executors.newCachedThreadPool()
        private val clients = CopyOnWriteArrayList<Socket>()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val timedOut = AtomicBoolean(false)
        val records = CopyOnWriteArrayList<Record>()
        @Volatile var firstStatus = 200
        val url = "http://127.0.0.1:${socket.localPort}"
        private val acceptor = thread(name = "qa-callback-loopback", isDaemon = true) {
            while (running.get()) try {
                val client = socket.accept(); clients.add(client); workers.execute { serve(client) }
            } catch (e: Exception) { if (running.get()) Log.e("QA_CALLBACK_DEVICE", "accept ${e.javaClass.name}") }
        }
        private fun serve(client: Socket) {
            try { client.use {
                client.soTimeout = 5_000
                val input = client.getInputStream()
                fun line(): String {
                    val bytes = java.io.ByteArrayOutputStream()
                    while (true) { val b = input.read(); require(b >= 0); if (b == 10) break
                        require(bytes.size() < 4_096); if (b != 13) bytes.write(b) }
                    return bytes.toString("US-ASCII")
                }
                val request = line().split(' '); require(request.size >= 2)
                val headers = mutableMapOf<String,String>(); var n = 0
                while (true) { val s = line(); if (s.isEmpty()) break; require(++n <= 32)
                    val i = s.indexOf(':'); require(i > 0); headers[s.substring(0,i).lowercase()] = s.substring(i+1).trim() }
                val length = headers["content-length"]?.toInt() ?: 0; require(length in 0..16_384)
                val bytes = ByteArray(length); var offset = 0
                while (offset < length) { val read = input.read(bytes, offset, length-offset); require(read > 0); offset += read }
                var status = 200
                val body = if (request[0] == "POST" && request[1] == "/callback") {
                    val record = synchronized(records) { Record(String(bytes, Charsets.UTF_8), headers["authorization"] == "Bearer $token", System.nanoTime()).also { records.add(it) } }
                    if (record === records.first()) { entered.countDown()
                        if (!release.await(10, TimeUnit.SECONDS)) { timedOut.set(true); status = 503 } else status = firstStatus }
                    record.status = status; record.returnedAt = System.nanoTime()
                    if (status == 200) """{"ok":true}""" else """{"detail":{"ru":"QA отказ","ba":"QA баш тартыу"}}"""
                } else when (request[1]) {
                    "/me" -> """{"id":1,"name":"QA_SYNTH_CALLBACK_DEVICE","role":"passenger"}"""
                    "/version/min" -> """{"min_version_code":0,"latest_version_code":0}"""
                    "/rides", "/requests/mine", "/trusted-contacts", "/ads" -> """{"items":[]}"""
                    "/me/update", "/auth/logout", "/push/unregister", "/push/register" -> "{}"
                    else -> { status = 404; "{}" }
                }
                val response = body.toByteArray(Charsets.UTF_8)
                client.getOutputStream().apply { write(("HTTP/1.1 $status Test\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)); write(response); flush() }
            } } catch (e: Exception) { if (running.get()) Log.e("QA_CALLBACK_DEVICE", "serve ${e.javaClass.name}") }
            finally { clients.remove(client) }
        }
        override fun close() {
            running.set(false); release.countDown(); socket.close(); clients.forEach { runCatching { it.close() } }
            workers.shutdownNow(); acceptor.join(5_000)
            check(!acceptor.isAlive && workers.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
    private data class Record(val body: String, val authMatches: Boolean, val receivedAt: Long,
        @Volatile var returnedAt: Long = 0, @Volatile var status: Int = 0)
}
