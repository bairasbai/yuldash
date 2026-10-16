package com.yuldash.app

import android.Manifest
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
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
import kotlin.concurrent.thread

/** Actual App/default VM/TG form and Android HTTP; external Telegram intent captured by fixture. */
@RunWith(AndroidJUnit4::class)
class LoginContactsBootstrapInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val mounted = mutableStateOf(false)
    private lateinit var vm: YuldashViewModel
    private lateinit var server: Loopback
    private val opened = CopyOnWriteArrayList<String>()
    @Before fun setup() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(PackageManager.PERMISSION_GRANTED, ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS))
        ApiClient.testTrace = null; ApiClient.resetForTest(); ApiClient.init(context); ApiClient.logout()
        TelegramLoginBot.testName = "yuldash_test_bot"
        context.getSharedPreferences("yuldash_prefs", 0).edit().clear().putBoolean("onboarding_completed", false)
            .putString("mode_last", "pooling").putBoolean("mode_hint_shown", true).commit()
        NavSignals.openInstantOrder.value = false; NavSignals.openInstantChat.value = 0
        DeepLink.pendingBookingChatId.value = null
        server = Loopback(); ApiClient.testBaseUrl = server.url; ApiClient.testTimeoutMs = 60000
        compose.runOnIdle { vm = ViewModelProvider(compose.activity)[YuldashViewModel::class.java]; vm.screen.value = Screen.Login; vm.persistNav() }
    }
    @After fun cleanup() {
        server.release.countDown(); compose.runOnIdle { mounted.value = false }; compose.waitForIdle()
        server.close(); TelegramLoginBot.testName = null; ApiClient.testTrace = null; ApiClient.resetForTest(); ApiClient.testTimeoutMs = null
    }
    private fun contacts() = server.records.filter { it.path == "/trusted-contacts" }
    private fun screenshot(name: String) {
        compose.waitForIdle(); compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.filesDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    private fun scenario(language: AppLanguage) {
        compose.runOnIdle { vm.language.value = language; AppPrefs.setLanguage(context, language) }
        val host = compose.activity
        val opener = object : ContextWrapper(host) {
            override fun startActivity(intent: Intent) {
                if (intent.action == Intent.ACTION_VIEW && intent.dataString?.startsWith("https://t.me/") == true) opened += intent.dataString!!
                else super.startActivity(intent)
            }
        }
        compose.runOnUiThread {
            mounted.value = true; host.setContent {
                if (mounted.value) CompositionLocalProvider(LocalViewModelStoreOwner provides host, LocalContext provides opener, LocalPoolingNativeMapEnabled provides false) {
                    YuldashTheme { YuldashApp() }
                }
            }
        }
        compose.waitForIdle(); assertEquals(Screen.Login, vm.screen.value); assertEquals(0, contacts().size)
        val before = ApiClient.queueSessionGeneration()
        compose.onNodeWithTag(TAG_LOGIN_TELEGRAM_BTN).performScrollTo().performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithTag(TAG_LOGIN_TG_VERIFY_BTN).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(if (language == AppLanguage.Ru) "Код из Telegram" else "Telegram коды").performTextInput("123456")
        compose.onNodeWithTag(TAG_LOGIN_TG_VERIFY_BTN).performScrollTo().performClick()
        compose.waitUntil(15000) { vm.screen.value == Screen.Home && ApiClient.currentToken() == server.token && server.started.count == 0L }
        repeat(20) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle(); Thread.sleep(10) }
        assertEquals(before + 1, ApiClient.queueSessionGeneration()); assertEquals(12, ApiClient.myUserId())
        assertEquals("Actual login must start one bootstrap while its response is held", 1, contacts().size)
        assertTrue(vm.trustedContacts.isEmpty()); assertEquals("Bearer ${server.token}", contacts().single().bearer)
        assertEquals(1, server.records.count { it.path == "/auth/tg/start" })
        val verified = server.records.single { it.path == "/auth/tg/verify" }; assertEquals("POST", verified.method); assertNull(verified.bearer)
        val payload = JSONObject(verified.body.toString(Charsets.UTF_8)); assertEquals("synthetic-request", payload.getString("request_id")); assertEquals("123456", payload.getString("code"))
        assertEquals(listOf("https://t.me/yuldash_test_bot?start=synthetic-request"), opened.toList())
        screenshot("b02-login-bootstrap-$language-before-release.png")
        server.release.countDown(); compose.waitUntil(15000) { vm.trustedContacts.map { it.id } == listOf(9) }
        compose.waitForIdle(); assertEquals(1, contacts().size)
        compose.runOnIdle { vm.navigateLocally { vm.screen.value = Screen.TrustedContacts } }
        compose.waitUntil(15000) { compose.onAllNodesWithText("Контакт нового входа").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Контакт нового входа").assertIsDisplayed(); assertEquals(2, contacts().size)
        assertEquals(listOf(9), vm.trustedContacts.map { it.id }); assertTrue(server.errors.toString(), server.errors.isEmpty())
        screenshot("b02-login-bootstrap-$language-after-contacts.png")
    }
    @Test fun realTelegramLoginBootstrapRu() = scenario(AppLanguage.Ru)
    @Test fun realTelegramLoginBootstrapBa() = scenario(AppLanguage.Ba)
    private data class Record(val method: String, val path: String, val bearer: String?, val body: ByteArray)
    private class Loopback : AutoCloseable {
        private val listener = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val workers = Executors.newCachedThreadPool { r -> Thread(r, "login-bootstrap-http").apply { isDaemon = true } }
        private val sockets = CopyOnWriteArrayList<Socket>()
        val records = CopyOnWriteArrayList<Record>(); val errors = CopyOnWriteArrayList<String>()
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        val token = "header.eyJzdWIiOiIxMiJ9.signature"
        @Volatile private var closed = false
        val url = "http://127.0.0.1:${listener.localPort}"
        private val accept = thread(isDaemon = true, name = "login-bootstrap-accept") {
            while (!closed) {
                val socket = runCatching { listener.accept() }.getOrNull() ?: break
                sockets += socket; workers.submit {
                    try { respond(socket) } catch (e: Exception) { if (!closed) errors += e.toString() }
                    finally { sockets.remove(socket) }
                }
            }
        }
        private fun respond(socket: Socket) = socket.use {
            socket.soTimeout = 60000; val input = socket.getInputStream().buffered()
            fun line(): String {
                val bytes = ArrayList<Byte>()
                while (true) { val n = input.read(); check(n >= 0); if (n == 10) break; if (n != 13) bytes += n.toByte() }
                return bytes.toByteArray().toString(Charsets.US_ASCII)
            }
            val request = line().split(' '); val headers = mutableMapOf<String, String>()
            while (true) { val h = line(); if (h.isEmpty()) break; headers[h.substringBefore(':').lowercase()] = h.substringAfter(':').trim() }
            val body = ByteArray(headers["content-length"]?.toInt() ?: 0); var offset = 0
            while (offset < body.size) { val n = input.read(body, offset, body.size - offset); check(n > 0); offset += n }
            val r = Record(request[0], request[1].substringBefore('?'), headers["authorization"], body); records += r
            val status: Int; val text: String
            when (r.path) {
                "/auth/tg/start" -> { status = 200; text = """{"request_id":"synthetic-request"}""" }
                "/auth/tg/verify" -> { status = 200; text = """{"access_token":"$token","refresh_token":"synthetic-refresh","user":{"name":"Synthetic B","role":"passenger"}}""" }
                "/trusted-contacts" -> { check(r.method == "GET" && r.bearer == "Bearer $token"); started.countDown(); check(release.await(40, TimeUnit.SECONDS)); status = 200; text = """{"items":[{"id":9,"name":"Контакт нового входа","phone":"+70000000009"}]}""" }
                "/me" -> { status = if (r.bearer == "Bearer $token") 200 else 503; text = """{"id":12,"name":"Synthetic B","role":"passenger"}""" }
                else -> { status = if (r.path == "/instant/orders/active") 503 else 200; text = """{"items":[],"unread":0}""" }
            }
            val bytes = text.toByteArray(Charsets.UTF_8)
            socket.getOutputStream().apply { write("HTTP/1.1 $status Result\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII)); write(bytes); flush() }
        }
        override fun close() {
            release.countDown(); closed = true; listener.close(); sockets.forEach { runCatching { it.close() } }
            workers.shutdownNow(); check(workers.awaitTermination(10, TimeUnit.SECONDS)); accept.join(10000); check(!accept.isAlive)
        }
    }
}
