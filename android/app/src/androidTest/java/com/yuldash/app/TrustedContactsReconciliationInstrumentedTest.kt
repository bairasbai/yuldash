package com.yuldash.app

import android.graphics.Bitmap
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Native wrapper + real HTTP: unknown commit -> failed read -> read-only retry. */
@RunWith(AndroidJUnit4::class)
class TrustedContactsReconciliationInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext.applicationContext
    private val mounted = mutableStateOf(false)
    private val contacts = mutableStateListOf<TrustedContact>()
    private lateinit var server: Loopback
    private var caseTag = ""
    @Before fun prepare() {
        server = Loopback()
        ApiClient.resetForTest(); ApiClient.init(context)
        ApiClient.saveToken("header.eyJzdWIiOiIxMSJ9.signature")
        ApiClient.testBaseUrl = server.url; ApiClient.testTimeoutMs = 30000
        contacts.add(TrustedContact("Контакт А", "Синтетический", "+70000000007", true, 7))
    }
    @After fun cleanup() {
        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
        server.close(); ApiClient.resetForTest(); ApiClient.testTimeoutMs = null
    }
    @Test fun deletedContactReconcilesAfterMalformedReplyRu() = scenario(AppLanguage.Ru, true)
    @Test fun addedContactReconcilesAfterMalformedReplyBa() = scenario(AppLanguage.Ba, false)
    @Test fun addedContactReconcilesAfterMalformedReplyRu() = scenario(AppLanguage.Ru, false)
    @Test fun addedContactReconcilesAfterLostReplyRu() = scenario(AppLanguage.Ru, false, lost = true)
    @Test fun addedContactReconcilesAfterLostReplyBa() = scenario(AppLanguage.Ba, false, lost = true)
    @Test fun addedContactCanBeDeletedByServerIdBa() {
        scenario(AppLanguage.Ba, false)
        caseTag += "-delete-id8"
        compose.onAllNodesWithContentDescription("Контактты алыу")[1].performClick()
        compose.onNodeWithText("Алырға").performClick()
        compose.waitUntil(15000) { contacts.map { it.id } == listOf(7) && compose.onAllNodesWithText("Контакт Б").fetchSemanticsNodes().isEmpty() }
        assertEquals(2, server.mutations.get()); assertEquals(4, server.gets.get())
        assertTrue(server.failures.toString(), server.failures.isEmpty())
        screenshot(AppLanguage.Ba, "success", "Контакт А")
    }
    private fun scenario(language: AppLanguage, deleting: Boolean, lost: Boolean = false) {
        caseTag = "${if (deleting) "delete" else "add"}-${if (lost) "lost" else "malformed"}"
        server.lost = lost
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(LocalAppLanguage provides language) {
                YuldashTheme {
                    TrustedContactsScreen(contacts, onBack = {}, onContactsChanged = { contacts.clear(); contacts.addAll(it) })
                }
            }
        }
        compose.runOnIdle { mounted.value = true }
        compose.waitUntil(15000) { server.gets.get() == 1 && compose.onAllNodesWithText("Контакт А").fetchSemanticsNodes().isNotEmpty() }
        val add = if (language == AppLanguage.Ru) "Добавить контакт" else "Контакт өҫтәү"
        compose.onNodeWithText(add).performScrollTo().assertIsEnabled()
        if (deleting) {
            compose.onNodeWithContentDescription("Убрать контакт").performClick()
            compose.onNodeWithText("Убрать").performClick()
        } else {
            compose.onNodeWithText(add).performClick()
            val inputs = compose.onAllNodes(hasSetTextAction())
            inputs[0].performTextInput("Черновик"); inputs[1].performTextInput("Синтетический"); inputs[2].performTextInput("+70000000008")
            compose.onNodeWithText(if (language == AppLanguage.Ru) "Добавить" else "Өҫтәргә").performClick()
        }
        val retry = if (language == AppLanguage.Ru) "Повторить" else "Ҡабатлау"
        compose.waitUntil(15000) { compose.onAllNodesWithText(retry).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(retry).performScrollTo().assertIsDisplayed()
        assertEquals(1, server.mutations.get()); assertEquals(2, server.gets.get())
        compose.onNodeWithText(add).assertIsNotEnabled()
        screenshot(language, "error", retry)
        compose.onNodeWithText(retry).performClick()
        val expected = if (deleting) "Пока нет контактов" else "Контакт Б"
        compose.waitUntil(15000) { compose.onAllNodesWithText(expected).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(expected).performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Черновик").assertCountEquals(0)
        compose.onAllNodesWithText(retry).assertCountEquals(0)
        assertEquals(if (deleting) emptyList<Int>() else listOf(7, 8), contacts.map { it.id })
        assertEquals(1, server.mutations.get()); assertEquals(3, server.gets.get())
        assertTrue(server.failures.toString(), server.failures.isEmpty())
        screenshot(language, "success", expected)
        Log.i("B02ContactsReconcile", "verified language=${language.name} mutation=${if (deleting) "DELETE" else "POST"} gets=3 mutations=1 wrapper=true")
    }
    private fun screenshot(language: AppLanguage, stage: String, text: String) {
        compose.onNodeWithText(text).captureToImage() // PixelCopy waits for a drawn native frame.
        instrumentation.waitForIdleSync()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(context.filesDir, "b02-contacts-reconcile-${language.name}-$caseTag-$stage.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }
    private class Loopback : AutoCloseable {
        private val listener = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val workers = Executors.newCachedThreadPool { task -> Thread(task, "contacts-http").apply { isDaemon = true } }
        private val sockets = CopyOnWriteArrayList<Socket>()
        val failures = CopyOnWriteArrayList<String>()
        val gets = AtomicInteger(); val mutations = AtomicInteger()
        @Volatile private var deleted = false
        @Volatile private var added = false
        @Volatile var lost = false
        @Volatile private var closed = false
        val url = "http://127.0.0.1:${listener.localPort}"
        private val accept = thread(isDaemon = true, name = "contacts-accept") {
            while (!closed) {
                val socket = runCatching { listener.accept() }.getOrNull() ?: break
                sockets += socket
                workers.submit { runCatching { respond(socket) }.onFailure { if (!closed) failures += it.javaClass.simpleName } }
            }
        }
        private fun respond(socket: Socket) {
            socket.use {
                socket.soTimeout = 20000
                val input = socket.getInputStream().buffered()
                fun line(): String {
                    val bytes = ArrayList<Byte>()
                    while (true) { val next = input.read(); check(next >= 0); if (next == 10) break; if (next != 13) bytes += next.toByte() }
                    return bytes.toByteArray().toString(Charsets.US_ASCII)
                }
                val parts = line().split(' ')
                var length = 0; var bearer: String? = null
                while (true) {
                    val header = line()
                    if (header.isEmpty()) break
                    if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
                    if (header.startsWith("Authorization:", true)) bearer = header.substringAfter(':').trim()
                }
                val body = input.readNBytes(length); check(body.size == length)
                if (parts[0] == "POST") {
                    val fields = JSONObject(body.toString(Charsets.UTF_8))
                    check(fields.length() == 4 && fields.getString("name") == "Черновик" && fields.getString("phone") == "+70000000008")
                }
                check(bearer == "Bearer header.eyJzdWIiOiIxMSJ9.signature")
                var status = 200
                val response = when {
                    parts[0] == "DELETE" -> {
                        check(parts[1] == "/trusted-contacts/7" || parts[1] == "/trusted-contacts/8")
                        if (parts[1].endsWith("/7")) deleted = true else added = false
                        mutations.incrementAndGet(); if (lost) return@use; if (parts[1].endsWith("/8")) "{\"ok\":true}" else "{\"ok\":"
                    }
                    parts[0] == "POST" -> { check(parts[1] == "/trusted-contacts"); added = true; mutations.incrementAndGet(); if (lost) return@use; "{\"ok\":" }
                    else -> {
                        check(parts[0] == "GET" && parts[1] == "/trusted-contacts")
                        if (gets.incrementAndGet() == 2) { status = 503; "{}" }
                        else {
                            val rows = mutableListOf<String>()
                            if (!deleted) rows += """{"id":7,"name":"Контакт А","relation":"Синтетический","phone":"+70000000007","notify_by_default":true}"""
                            if (added) rows += """{"id":8,"name":"Контакт Б","relation":"Синтетический","phone":"+70000000008","notify_by_default":true}"""
                            """{"items":[${rows.joinToString()}]}"""
                        }
                    }
                }.toByteArray(Charsets.UTF_8)
                socket.getOutputStream().apply {
                    write("HTTP/1.1 $status OK\r\nContent-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII)); write(response); flush()
                }
            }
            sockets.remove(socket)
        }
        override fun close() {
            closed = true; listener.close(); sockets.forEach { runCatching { it.close() } }; workers.shutdownNow(); accept.join(2000)
        }
    }
}
