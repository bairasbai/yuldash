package com.yuldash.app

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.ui.theme.YuldashTheme
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Real screen/HTTP/session tests for late responses and late file I/O.
 * Successful file/provider/share paths are exercised on Android in
 * MyDataExportJourneyInstrumentedTest: Windows FileProvider paths are incompatible.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MyDataExportSessionTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var context: Application
    private lateinit var server: MockWebServer
    private val mounted = mutableStateOf(true)
    private val requests = CopyOnWriteArrayList<Pair<String, String?>>()
    private val apiTrace = CopyOnWriteArrayList<String>()
    private val held = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val ownedFiles = mutableListOf<File>()
    @Volatile private var holdExport = false
    private val filename = "yuldash-my-data.txt"
    private val dump = "Юлдаш · synthetic-account-A\nSynthetic private export A\nҺинең мәғлүмәттәр: QA_EXPORT_A\n"
    private lateinit var neutral: File

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        ApiClient.resetForTest()
        ApiClient.testTrace = { apiTrace.add(it) }
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    requests.add("${request.method} $path" to request.getHeader("Authorization"))
                    if (path.startsWith("/me/export") && holdExport) {
                        held.countDown()
                        if (!release.await(10, TimeUnit.SECONDS)) return json("{}", 503)
                    }
                    return when {
                        path == "/me/data" -> json("""{"rides":3,"rides_days":180,"driver_docs":0,"card_stored":false}""")
                        path.startsWith("/me/export") -> json(JSONObject().put("filename", filename).put("text", dump).toString())
                        path == "/auth/logout" || path == "/push/unregister" -> json("{}")
                        else -> json("{}", 404)
                    }
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        // The late-response test holds an actual request for a bounded 10 seconds at most.
        ApiClient.testTimeoutMs = 15_000
        ApiClient.init(context)
        ApiClient.logout()
        ApiClient.saveToken("QA-export-account-A")

        neutral = File(context.cacheDir, "shared/qa-mydata-neutral.txt").apply {
            parentFile!!.mkdirs()
            writeText("QA neutral cache item")
        }
        ownedFiles.add(neutral)
        while (Shadows.shadowOf(context).nextStartedActivity != null) { /* discard only setup intents */ }
    }

    @After fun cleanup() {
        release.countDown()
        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
        ApiClient.logout()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        ApiClient.testTrace = null
        // Only files created by this probe are removed; product cleanup is asserted beforehand.
        ownedFiles.forEach { it.delete() }
        server.shutdown()
    }

    private fun json(body: String, status: Int = 200) = MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "application/json; charset=utf-8").setBody(body)

    private fun sharedFiles(): Map<String, String> {
        val dir = File(context.cacheDir, "shared")
        return dir.walkTopDown().filter { it.isFile }.associate { file ->
            file.relativeTo(dir).invariantSeparatorsPath to MessageDigest.getInstance("SHA-256")
                .digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        }
    }

    private fun settle() {
        Snapshot.sendApplyNotifications()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    private fun mount(language: AppLanguage, screenContext: Context = context) {
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides language, LocalContext provides screenContext) {
                YuldashTheme { if (mounted.value) MyDataScreen(onBack = {}) }
            }
        }
        val anchor = if (language == AppLanguage.Ba) "3 сәфәр" else "3 поездки"
        compose.waitUntil(15_000) {
            settle()
            compose.onAllNodesWithText(anchor).fetchSemanticsNodes().isNotEmpty()
        }
        compose.mainClock.advanceTimeBy(1_000) // Finish the real card appearance animation.
        compose.waitForIdle()
    }

    private fun clickExport(language: AppLanguage) {
        val label = if (language == AppLanguage.Ba) "Йөкләргә" else "Скачать"
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(label))
        compose.onNodeWithText(label).assertIsEnabled().performClick()
    }

    @Test fun lateExportResponseAfterAccountSwitchDoesNotCreateOrShareOldData() {
        val before = sharedFiles()
        holdExport = true
        mount(AppLanguage.Ru)
        clickExport(AppLanguage.Ru)
        compose.waitUntil(15_000) { settle(); held.count == 0L }
        compose.runOnIdle {
            ApiClient.logout()
            ApiClient.saveToken("QA-export-account-B")
        }
        release.countDown()
        compose.waitUntil(15_000) {
            settle()
            compose.onAllNodesWithText("Скачать").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Скачать").assertIsEnabled()
        assertEquals("QA-export-account-B", ApiClient.currentToken())
        assertEquals(1, requests.count { it.first == "GET /me/export?lang=ru" && it.second == "Bearer QA-export-account-A" })
        assertNull("a rejected stale export must not start a share intent", Shadows.shadowOf(context).peekNextStartedActivity())
        assertEquals("a stale response must not add or change any shared file", before, sharedFiles())
        assertEquals("QA neutral cache item", neutral.readText())
    }

    @Test fun logoutAfterHttpSuccessBeforeFileWriteDoesNotRecreateOldExport() {
        val writerEntered = CountDownLatch(1)
        val writerReleased = CountDownLatch(1)
        val intercepted = AtomicBoolean(false)
        val gateTimedOut = AtomicBoolean(false)
        val before = sharedFiles()
        // Only the screen's first IO cache lookup is delayed. The directory, writer,
        // provider and ApiClient logout remain real, and ApiClient uses the Application.
        val delayedContext = object : ContextWrapper(context) {
            override fun getCacheDir(): File {
                if (Looper.myLooper() != Looper.getMainLooper() && intercepted.compareAndSet(false, true)) {
                    writerEntered.countDown()
                    val resumed = writerReleased.await(10, TimeUnit.SECONDS)
                    gateTimedOut.set(!resumed)
                    check(resumed) { "synthetic writer gate timed out" }
                }
                return super.getCacheDir()
            }
        }
        try {
            mount(AppLanguage.Ru, delayedContext)
            clickExport(AppLanguage.Ru)
            compose.waitUntil(15_000) {
                settle()
                compose.onAllNodesWithText("Скачать").fetchSemanticsNodes()
                writerEntered.count == 0L
            }
            assertEquals(1, requests.count { it.first == "GET /me/export?lang=ru" && it.second == "Bearer QA-export-account-A" })
            assertTrue("the actual HTTP response must succeed before the writer gate",
                apiTrace.any { it.contains("GET /me/export?lang=ru успешно (код 200)") })
            assertEquals("no shared file was created while the write was paused", before, sharedFiles())
            compose.runOnIdle {
                ApiClient.logout()
                ApiClient.saveToken("QA-export-account-B")
            }
            writerReleased.countDown()
            assertFalse("the gate must be released by the test, not by its timeout", gateTimedOut.get())
            compose.waitUntil(15_000) {
                settle()
                compose.onAllNodesWithText("Скачать").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Скачать").assertIsEnabled()
            println("QA021 post-HTTP switch intercepted=${intercepted.get()} sharedFilesChanged=${before != sharedFiles()} " +
                "chooserStarted=${Shadows.shadowOf(context).peekNextStartedActivity() != null} neutralExists=${neutral.exists()}")
            assertEquals("QA-export-account-B", ApiClient.currentToken())
            assertEquals("completed HTTP must not add or change any shared file after logout", before, sharedFiles())
            assertNull("old-session IO must not share after account switch", Shadows.shadowOf(context).peekNextStartedActivity())
            assertEquals("QA neutral cache item", neutral.readText())
        } finally { writerReleased.countDown() }
    }
}
