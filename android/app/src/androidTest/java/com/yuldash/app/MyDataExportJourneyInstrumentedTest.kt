package com.yuldash.app

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuldash.app.data.ApiClient
import com.yuldash.app.ui.theme.YuldashTheme
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Actual screen -> loopback HTTP -> product writer -> Android FileProvider -> share intent.
 * The final external chooser launch is intercepted to keep this Activity in the foreground.
 * This proves the produced intent, not the OS chooser UI or recipient grants; those grants
 * have separate evidence in MyDataExportPrivacyInstrumentedTest. Run with StorageAuditRunner
 * on the isolated emulator. No backend, SMS, payments, MapKit or FCM is exercised here.
 */
@RunWith(AndroidJUnit4::class)
class MyDataExportJourneyInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var context: Context
    private lateinit var server: LoopbackServer
    private lateinit var neutral: File
    private lateinit var initialFiles: Map<String, String>
    private lateinit var screenContext: RecordingContext
    private val mounted = mutableStateOf(true)
    private val started = CopyOnWriteArrayList<Intent>()
    private val apiTrace = CopyOnWriteArrayList<String>()
    private val writerEntered = CountDownLatch(1)
    private val writerReleased = CountDownLatch(1)
    private val writerIntercepted = AtomicBoolean(false)
    private val writerGateTimedOut = AtomicBoolean(false)
    @Volatile private var holdWriter = false
    private var previousUrl: String? = null
    private var previousTimeout: Int? = null
    private var previousTrace: ((String) -> Unit)? = null
    private val filename = "yuldash-my-data.txt"
    private val dump = "Юлдаш · synthetic-account-A\nSynthetic private export A\nҺинең мәғлүмәттәр: QA_EXPORT_A\n"

    @Before fun setup() {
        context = instrumentation.targetContext.applicationContext
        previousUrl = ApiClient.testBaseUrl
        previousTimeout = ApiClient.testTimeoutMs
        previousTrace = ApiClient.testTrace
        server = LoopbackServer()
        ApiClient.resetForTest()
        ApiClient.testBaseUrl = server.baseUrl
        ApiClient.testTimeoutMs = 15_000
        ApiClient.testTrace = { apiTrace.add(it) }
        ApiClient.init(context)
        ApiClient.logout()
        ApiClient.saveToken("QA-export-account-A")
        initialFiles = sharedFiles()
        neutral = File(context.cacheDir, "shared/qa-mydata-journey-neutral.txt")
        assertFalse("the fixture must not overwrite another file", neutral.exists())
        check(neutral.parentFile!!.mkdirs() || neutral.parentFile!!.isDirectory)
        neutral.writeText("QA neutral cache item")
        screenContext = RecordingContext(compose.activity)
        val authority = "${context.packageName}.fileprovider"
        val info = requireNotNull(context.packageManager.resolveContentProvider(authority, PackageManager.GET_META_DATA))
        assertFalse(info.exported)
        assertTrue(info.grantUriPermissions)
        // No shadow provider or stream substitution: Android resolves the actual manifest.
        assertEquals("QA neutral cache item", readUri(FileProvider.getUriForFile(context, authority, neutral)))
        val metrics = context.resources.displayMetrics
        Log.i("QA021_JOURNEY", "setup api=${Build.VERSION.SDK_INT} width=${metrics.widthPixels} " +
            "height=${metrics.heightPixels} density=${metrics.density} fontScale=${context.resources.configuration.fontScale}")
    }

    @After fun cleanup() {
        writerReleased.countDown()
        if (::server.isInitialized) server.releaseExport.countDown()
        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
        ApiClient.logout()
        ApiClient.resetForTest()
        if (::neutral.isInitialized) neutral.delete()
        // Remove only newly created files containing this probe's exact synthetic dump.
        if (::context.isInitialized && ::initialFiles.isInitialized) {
            val dir = File(context.cacheDir, "shared")
            dir.walkTopDown().filter { it.isFile && it.relativeTo(dir).invariantSeparatorsPath !in initialFiles }
                .forEach { if (runCatching { it.readText() == dump }.getOrDefault(false)) it.delete() }
        }
        if (::server.isInitialized) server.close()
        ApiClient.testBaseUrl = previousUrl
        ApiClient.testTimeoutMs = previousTimeout
        ApiClient.testTrace = previousTrace
    }

    private inner class RecordingContext(base: Context) : ContextWrapper(base) {
        override fun startActivity(intent: Intent) {
            // Record the actual product intent. Only the final external dispatch is stopped.
            started.add(Intent(intent))
        }

        override fun getCacheDir(): File {
            if (holdWriter && Looper.myLooper() != Looper.getMainLooper() && writerIntercepted.compareAndSet(false, true)) {
                writerEntered.countDown()
                val resumed = writerReleased.await(10, TimeUnit.SECONDS)
                writerGateTimedOut.set(!resumed)
                check(resumed) { "synthetic writer gate timed out" }
            }
            return super.getCacheDir()
        }
    }

    private fun sharedFiles(): Map<String, String> {
        val dir = File(context.cacheDir, "shared")
        return dir.walkTopDown().filter { it.isFile }.associate { file ->
            file.relativeTo(dir).invariantSeparatorsPath to MessageDigest.getInstance("SHA-256")
                .digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        }
    }

    private fun mount(language: AppLanguage) {
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides language, LocalContext provides screenContext) {
                YuldashTheme { if (mounted.value) MyDataScreen(onBack = {}) }
            }
        }
        val anchor = if (language == AppLanguage.Ba) "3 сәфәр" else "3 поездки"
        compose.waitUntil(15_000) { compose.onAllNodesWithText(anchor).fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    private fun clickExport(language: AppLanguage) {
        val label = if (language == AppLanguage.Ba) "Йөкләргә" else "Скачать"
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(label))
        compose.onNodeWithText(label).assertIsDisplayed().assertIsEnabled().performClick()
    }

    private fun waitForExportCompletion(language: AppLanguage = AppLanguage.Ru) {
        val label = if (language == AppLanguage.Ba) "Йөкләргә" else "Скачать"
        compose.waitUntil(15_000) { compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(label).assertIsEnabled()
    }

    @Suppress("DEPRECATION")
    private fun exportFromScreen(language: AppLanguage): Pair<Uri, File> {
        val before = sharedFiles()
        mount(language)
        clickExport(language)
        compose.waitUntil(15_000) { started.isNotEmpty() }
        assertEquals("one UI operation must produce one share intent", 1, started.size)
        val chooser = started.single()
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = requireNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val uri = requireNotNull(send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertEquals("content", uri.scheme)
        assertEquals("${context.packageName}.fileprovider", uri.authority)
        val actualName = requireNotNull(uri.lastPathSegment)
        val dir = File(context.cacheDir, "shared")
        val file = requireNotNull(dir.walkTopDown().firstOrNull { it.isFile && it.name == actualName })
        assertEquals("the actual UTF-8 writer must persist the supplied export", dump, file.readText())
        assertEquals("the actual Android provider must read the created file", dump, readUri(uri))
        val cursor = requireNotNull(context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null))
        cursor.use {
            assertTrue(it.moveToFirst())
            assertEquals("the public filename must preserve the backend display name", filename,
                it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)))
        }
        val after = sharedFiles()
        assertEquals("a single export must add one actual file", 1, (after.keys - before.keys).size)
        before.forEach { (path, checksum) -> assertEquals("unrelated shared file must be unchanged", checksum, after[path]) }
        val code = if (language == AppLanguage.Ba) "ba" else "ru"
        assertEquals(1, server.requests.count { it.path == "/me/data" && it.method == "GET" && it.auth == "Bearer QA-export-account-A" })
        assertEquals(1, server.requests.count { it.path == "/me/export?lang=$code" && it.method == "GET" && it.auth == "Bearer QA-export-account-A" })
        return uri to file
    }

    private fun readUri(uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.bufferedReader(Charsets.UTF_8).readText() }
    }.getOrNull()

    private fun saveScreenshot(language: AppLanguage) {
        val label = if (language == AppLanguage.Ba) "Йөкләргә" else "Скачать"
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(label))
        compose.onNodeWithText(label).assertIsDisplayed().assertIsEnabled()
        compose.waitForIdle()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val code = if (language == AppLanguage.Ba) "ba" else "ru"
        val file = File(context.cacheDir, "qa021-export-$code.png")
        try {
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        Log.i("QA021_JOURNEY", "screenshot=$file")
    }

    @Test fun successfulRussianExportProducesReadableFileAndShareIntentBeforeLogout() {
        val (uri, file) = exportFromScreen(AppLanguage.Ru)
        assertTrue(file.exists())
        assertEquals(dump, readUri(uri))
        assertEquals("QA neutral cache item", neutral.readText())
        saveScreenshot(AppLanguage.Ru)
    }

    @Test fun logoutErasesExportAndPreviousUriCannotReadTheOldContent() {
        val before = sharedFiles()
        val (uri, file) = exportFromScreen(AppLanguage.Ru)
        compose.runOnIdle { ApiClient.logout() }
        assertFalse(ApiClient.isLoggedIn())
        assertFalse("the app-owned personal copy must be erased on logout", file.exists())
        assertNull("the previous URI must not read the erased own copy", readUri(uri))
        assertEquals("logout must preserve unrelated shared files", before, sharedFiles())
        assertEquals("QA neutral cache item", neutral.readText())
    }

    @Test fun bashkirExportDoesNotSurviveLogoutAndNewAccount() {
        val before = sharedFiles()
        val (uri, file) = exportFromScreen(AppLanguage.Ba)
        saveScreenshot(AppLanguage.Ba)
        compose.runOnIdle { ApiClient.logout(); ApiClient.saveToken("QA-export-account-B") }
        assertEquals("QA-export-account-B", ApiClient.currentToken())
        assertFalse("B must not inherit A's app-owned copy", file.exists())
        assertNull("account switch must not leave old personal content readable", readUri(uri))
        assertEquals("account switch must preserve unrelated shared files", before, sharedFiles())
        assertEquals("QA neutral cache item", neutral.readText())
    }

    @Test fun lateExportResponseAfterAccountSwitchDoesNotCreateOrShareOldData() {
        val before = sharedFiles()
        server.holdExport = true
        mount(AppLanguage.Ru)
        clickExport(AppLanguage.Ru)
        compose.waitUntil(15_000) { server.exportEntered.count == 0L }
        compose.runOnIdle { ApiClient.logout(); ApiClient.saveToken("QA-export-account-B") }
        server.releaseExport.countDown()
        waitForExportCompletion()
        assertFalse("the HTTP gate must not time out", server.exportGateTimedOut.get())
        assertEquals(1, server.requests.count { it.path == "/me/export?lang=ru" && it.auth == "Bearer QA-export-account-A" })
        assertEquals("QA-export-account-B", ApiClient.currentToken())
        assertEquals("late HTTP must not add or change any shared file", before, sharedFiles())
        assertTrue("late HTTP must not produce a share intent", started.isEmpty())
        assertEquals("QA neutral cache item", neutral.readText())
    }

    @Test fun logoutAfterHttpSuccessBeforeFileWriteDoesNotRecreateOldExport() {
        val before = sharedFiles()
        holdWriter = true
        mount(AppLanguage.Ru)
        clickExport(AppLanguage.Ru)
        try {
            compose.waitUntil(15_000) { writerEntered.count == 0L }
            assertTrue("actual HTTP must succeed before entering the writer gate",
                apiTrace.any { it.contains("GET /me/export?lang=ru успешно (код 200)") })
            assertEquals(1, server.requests.count { it.path == "/me/export?lang=ru" && it.auth == "Bearer QA-export-account-A" })
            assertEquals("the paused writer has not created a file", before, sharedFiles())
            compose.runOnIdle { ApiClient.logout(); ApiClient.saveToken("QA-export-account-B") }
            writerReleased.countDown()
            waitForExportCompletion()
            assertFalse("the writer gate must not time out", writerGateTimedOut.get())
            assertEquals("QA-export-account-B", ApiClient.currentToken())
            assertEquals("late IO must not add or change any shared file", before, sharedFiles())
            assertTrue("late IO must not produce a share intent", started.isEmpty())
            assertEquals("QA neutral cache item", neutral.readText())
        } finally { writerReleased.countDown() }
    }

    private data class Request(val method: String, val path: String, val auth: String?)

    /** Bounded test-only HTTP/1.1 adapter. It listens exclusively on loopback. */
    private inner class LoopbackServer : AutoCloseable {
        private val socket = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)) }
        private val running = AtomicBoolean(true)
        private val workers = Executors.newFixedThreadPool(3)
        private val clients = CopyOnWriteArrayList<Socket>()
        val requests = CopyOnWriteArrayList<Request>()
        val exportEntered = CountDownLatch(1)
        val releaseExport = CountDownLatch(1)
        val exportGateTimedOut = AtomicBoolean(false)
        @Volatile var holdExport = false
        val baseUrl = "http://127.0.0.1:${socket.localPort}"
        private val acceptor = thread(name = "qa-mydata-loopback", isDaemon = true) {
            while (running.get()) {
                try {
                    val client = socket.accept()
                    clients.add(client)
                    workers.execute { serve(client) }
                } catch (error: Exception) {
                    if (running.get()) Log.e("QA021_JOURNEY", "loopback accept error ${error.javaClass.name}")
                }
            }
        }

        private fun serve(client: Socket) {
            try {
                client.use {
                    client.soTimeout = 5_000
                    val reader = client.getInputStream().bufferedReader(Charsets.US_ASCII)
                    val first = requireNotNull(reader.readLine()).also { require(it.length <= 4_096) }.split(' ')
                    require(first.size >= 2)
                    val headers = mutableMapOf<String, String>()
                    var count = 0
                    while (true) {
                        val line = requireNotNull(reader.readLine())
                        if (line.isEmpty()) break
                        require(line.length <= 4_096 && ++count <= 32)
                        val colon = line.indexOf(':')
                        require(colon > 0)
                        headers[line.substring(0, colon).lowercase()] = line.substring(colon + 1).trim()
                    }
                    val length = headers["content-length"]?.toInt() ?: 0
                    require(length in 0..16_384)
                    repeat(length) { require(reader.read() >= 0) }
                    val request = Request(first[0], first[1], headers["authorization"])
                    requests.add(request)
                    var status = 200
                    val body = when {
                        request.path == "/me/data" -> """{"rides":3,"rides_days":180,"driver_docs":0,"card_stored":false}"""
                        request.path.startsWith("/me/export?") -> {
                            if (holdExport) {
                                exportEntered.countDown()
                                val resumed = releaseExport.await(10, TimeUnit.SECONDS)
                                exportGateTimedOut.set(!resumed)
                                if (!resumed) status = 503
                            }
                            JSONObject().put("filename", filename).put("text", dump).toString()
                        }
                        request.path == "/auth/logout" || request.path == "/push/unregister" -> "{}"
                        else -> { status = 404; "{}" }
                    }
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    val header = "HTTP/1.1 $status Test\r\nContent-Type: application/json; charset=utf-8\r\n" +
                        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    client.getOutputStream().apply { write(header.toByteArray(Charsets.US_ASCII)); write(bytes); flush() }
                }
            } catch (error: Exception) {
                if (running.get()) Log.e("QA021_JOURNEY", "loopback request error ${error.javaClass.name}")
            } finally { clients.remove(client) }
        }

        override fun close() {
            running.set(false)
            releaseExport.countDown()
            socket.close()
            clients.forEach { runCatching { it.close() } }
            workers.shutdownNow()
            acceptor.join(5_000)
            check(!acceptor.isAlive && workers.awaitTermination(5, TimeUnit.SECONDS)) { "loopback workers did not stop" }
        }
    }
}
