package com.yuldash.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import com.yuldash.app.data.ApiClient
import com.yuldash.app.ui.theme.YuldashTheme
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

/** Actual App/default VM; old DELETE commits after the reopened editor accepted a fresh snapshot. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class TrustedContactsLateCommitInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext.applicationContext
    private val mounted = mutableStateOf(false)
    private lateinit var vm: YuldashViewModel
    private lateinit var server: Loopback
    private val parsed = CountDownLatch(1)
    @Before fun prepare() {
        // Home requests this permission on API33+; a system dialog would pause the test host and hide Compose roots.
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(PackageManager.PERMISSION_GRANTED, ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS))
        ApiClient.resetForTest(); ApiClient.init(context); ApiClient.saveToken("header.eyJzdWIiOiIxMSJ9.signature")
        context.getSharedPreferences("yuldash_prefs", 0).edit().clear().putBoolean("onboarding_completed", false)
            .putString("mode_last", "pooling").putBoolean("mode_hint_shown", true).commit()
        NavSignals.openInstantOrder.value = false; NavSignals.openInstantChat.value = 0
        DeepLink.pendingBookingChatId.value = null
        server = Loopback(); ApiClient.testBaseUrl = server.url; ApiClient.testTimeoutMs = 60000
        ApiClient.testTrace = { line ->
            if (line.startsWith("ВЫШЛИ из вызова GET /trusted-contacts") && server.release.count == 0L) parsed.countDown()
        }
        compose.runOnIdle {
            vm = ViewModelProvider(compose.activity)[YuldashViewModel::class.java]
            vm.screen.value = Screen.Home; vm.startHomeTab.value = HomeTab.Profile; vm.persistNav()
        }
    }
    @After fun cleanup() {
        server.release.countDown()
        compose.runOnIdle { mounted.value = false }; compose.waitForIdle()
        server.close(); ApiClient.testTrace = null; ApiClient.resetForTest(); ApiClient.testTimeoutMs = null
    }
    @Test fun lateDeleteUpdatesReopenedFreshSnapshotRu() = scenario(AppLanguage.Ru)
    @Test fun lateDeleteUpdatesReopenedFreshSnapshotBa() = scenario(AppLanguage.Ba)
    private fun scenario(language: AppLanguage) {
        val empty = if (language == AppLanguage.Ru) "Пока нет контактов" else "Әлегә контакттар юҡ"
        val add = if (language == AppLanguage.Ru) "Добавить контакт" else "Контакт өҫтәү"
        compose.runOnIdle { vm.language.value = language; AppPrefs.setLanguage(context, language) }
        val host = compose.activity
        compose.runOnUiThread {
            mounted.value = true
            host.setContent {
                if (mounted.value) CompositionLocalProvider(LocalViewModelStoreOwner provides host, LocalPoolingNativeMapEnabled provides false) {
                    YuldashTheme { YuldashApp() }
                }
            }
        }
        compose.waitForIdle()
        compose.waitUntil(15000) { vm.trustedContacts.map { it.id } == listOf(7) }
        compose.runOnIdle { vm.navigateLocally { vm.screen.value = Screen.TrustedContacts } }
        compose.waitUntil(15000) { server.gets.get() == 2 }; compose.waitForIdle()
        compose.onNodeWithText("Контакт А").assertIsDisplayed(); compose.onNodeWithText(add).performScrollTo().assertIsEnabled()
        compose.onNodeWithContentDescription(if (language == AppLanguage.Ru) "Убрать контакт" else "Контактты алыу").performClick()
        compose.onNodeWithText(if (language == AppLanguage.Ru) "Убрать" else "Алырға").performClick()
        compose.waitUntil(15000) { server.started.count == 0L }; assertFalse(server.deleted)
        compose.runOnIdle { vm.navigateLocally { vm.screen.value = Screen.Support } }; compose.waitForIdle()
        compose.runOnIdle { vm.navigateLocally { vm.screen.value = Screen.TrustedContacts } }
        compose.waitUntil(15000) { server.gets.get() == 3 }; compose.waitForIdle()
        compose.onNodeWithText("Контакт А").assertIsDisplayed(); compose.onNodeWithText(add).performScrollTo().assertIsEnabled()
        assertEquals(listOf(7), vm.trustedContacts.map { it.id }); assertFalse(server.deleted)
        assertEquals(1L, server.returned.count); screenshot(language, "before-commit", "Контакт А")
        val revision = vm.privateNavigationRevision; val owner = ApiClient.queueSessionGeneration()
        server.release.countDown(); assertTrue(server.returned.await(5, TimeUnit.SECONDS))
        compose.waitUntil(15000) { server.gets.get() == 4 && vm.trustedContacts.isEmpty() }; compose.waitForIdle()
        compose.onNodeWithText(empty).assertIsDisplayed(); compose.onNodeWithText(add).performScrollTo().assertIsEnabled()
        compose.onAllNodesWithText("Контакт А").assertCountEquals(0)
        assertTrue(parsed.await(5, TimeUnit.SECONDS)); compose.mainClock.advanceTimeBy(1000)
        instrumentation.waitForIdleSync(); compose.waitForIdle()
        assertEquals(4, server.gets.get()); assertEquals(1, server.mutations.get()); assertTrue(server.deleted)
        assertEquals(Screen.TrustedContacts, vm.screen.value); assertEquals(revision, vm.privateNavigationRevision)
        assertEquals(owner, ApiClient.queueSessionGeneration()); assertTrue(server.failures.toString(), server.failures.isEmpty())
        screenshot(language, "after-commit", empty)
        Log.i("B02ContactsLateCommit", "verified language=${language.name} gets=4 delete=1 parentVmEmpty=true heldBeforeCommit=true malformedMutation=true app=true host=ComponentActivity")
    }

    private fun screenshot(language: AppLanguage, stage: String, text: String) {
        compose.onNodeWithText(text).captureToImage(); instrumentation.waitForIdleSync()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(context.filesDir, "b02-contacts-late-commit-${language.name}-$stage.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }; bitmap.recycle()
    }
    private class Loopback : AutoCloseable {
        private val listener = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val workers = Executors.newCachedThreadPool { task -> Thread(task, "bootstrap-http").apply { isDaemon = true } }
        private val sockets = CopyOnWriteArrayList<Socket>()
        val failures = CopyOnWriteArrayList<String>(); val gets = AtomicInteger(); val mutations = AtomicInteger()
        val started = CountDownLatch(1); val release = CountDownLatch(1); val returned = CountDownLatch(1)
        @Volatile var deleted = false
        @Volatile private var closed = false
        val url = "http://127.0.0.1:${listener.localPort}"
        private val accept = thread(isDaemon = true, name = "bootstrap-accept") {
            while (!closed) {
                val socket = runCatching { listener.accept() }.getOrNull() ?: break
                sockets += socket; workers.submit { runCatching { respond(socket) }.onFailure { if (!closed) failures += it.javaClass.simpleName } }
            }
        }
        private fun respond(socket: Socket) {
            socket.use {
                socket.soTimeout = 20000; val input = socket.getInputStream().buffered()
                fun line(): String {
                    val bytes = ArrayList<Byte>()
                    while (true) { val next = input.read(); check(next >= 0); if (next == 10) break; if (next != 13) bytes += next.toByte() }
                    return bytes.toByteArray().toString(Charsets.US_ASCII)
                }
                val parts = line().split(' '); var length = 0; var bearer: String? = null
                while (true) {
                    val header = line(); if (header.isEmpty()) break
                    if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
                    if (header.startsWith("Authorization:", true)) bearer = header.substringAfter(':').trim()
                }
                check(input.readNBytes(length).size == length)
                val contacts = parts[1].startsWith("/trusted-contacts")
                if (contacts) check(bearer == "Bearer header.eyJzdWIiOiIxMSJ9.signature")
                val response = when {
                    parts[0] == "DELETE" && parts[1] == "/trusted-contacts/7" -> {
                        mutations.incrementAndGet(); started.countDown(); check(release.await(45, TimeUnit.SECONDS))
                        deleted = true; returned.countDown(); "{\"ok\":"
                    }
                    parts[0] == "GET" && parts[1] == "/trusted-contacts" -> {
                        val snapshot = if (deleted) "[]" else """[{"id":7,"name":"Контакт А","relation":"Синтетический","phone":"+70000000007","notify_by_default":true}]"""
                        gets.incrementAndGet()
                        """{"items":$snapshot}"""
                    }
                    else -> { check(!contacts); """{"items":[],"unread":0}""" }
                }.toByteArray(Charsets.UTF_8)
                val status = if (parts[1] == "/instant/orders/active") 503 else 200
                socket.getOutputStream().apply {
                    write("HTTP/1.1 $status OK\r\nContent-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII)); write(response); flush()
                }
            }; sockets.remove(socket)
        }
        override fun close() {
            closed = true; release.countDown(); listener.close(); sockets.forEach { runCatching { it.close() } }; workers.shutdownNow(); accept.join(2000)
        }
    }
}
