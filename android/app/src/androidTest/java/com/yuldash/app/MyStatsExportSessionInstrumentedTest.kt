package com.yuldash.app

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuldash.app.data.ApiClient
import com.yuldash.app.ui.theme.YuldashTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
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
 * Actual MyStats UI, loopback HTTP, Android Canvas/PNG, FileProvider and product SEND intent.
 * Only final external chooser dispatch is intercepted. A separate explicit OS URI grant to
 * com.android.shell tests a real recipient UID, including binary reads and same-path aliasing.
 * This is not Profile navigation, a real backend, the chooser UI or revocation of copies/open FDs.
 * Run with StorageAuditRunner on the isolated emulator; no new dependency or provider substitute.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class MyStatsExportSessionInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var context: Context
    private lateinit var screenContext: RecordingContext
    private lateinit var server: LoopbackServer
    private lateinit var initialFiles: Map<String, String>
    private lateinit var neutral: File
    private lateinit var neutralUri: Uri
    private var neutralCreated = false
    private var previousUrl: String? = null
    private var previousTimeout: Int? = null
    private var previousTrace: ((String) -> Unit)? = null
    private val started = CopyOnWriteArrayList<Intent>()
    private val apiTrace = CopyOnWriteArrayList<String>()
    private val granted = CopyOnWriteArrayList<Uri>()
    private val syntheticPngFiles = CopyOnWriteArrayList<File>()
    private val mounted = mutableStateOf(true)
    private val screenEpoch = mutableStateOf(0)
    private var writerEntered = CountDownLatch(1)
    private var writerReleased = CountDownLatch(1)
    private val writerIntercepted = AtomicBoolean(false)
    private val writerGateTimedOut = AtomicBoolean(false)
    @Volatile private var holdWriter = false
    @Volatile private var failWriter = false
    @Volatile private var effectJob: Job? = null
    private var baselineJobs = emptySet<Job>()
    private var shareJobs = emptyList<Job>()
    private var backCount = 0
    private val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
    private val tokenA = "QA-stats-account-A"
    private val tokenB = "QA-stats-account-B"
    private val neutralText = "QA022_NEUTRAL_UNRELATED"

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
        ApiClient.saveToken(tokenA)
        ApiClient.saveName("QA_SYNTH_A")
        initialFiles = sharedFiles()
        assertFalse("refuse to overwrite an existing statistics export", "my_yuldash.png" in initialFiles)
        neutral = File(context.cacheDir, "shared/qa022-stats-neutral.txt")
        assertFalse("refuse to overwrite another file", neutral.exists())
        check(neutral.parentFile!!.mkdirs() || neutral.parentFile!!.isDirectory)
        neutral.writeText(neutralText)
        neutralCreated = true
        neutralUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", neutral)
        assertEquals(neutralText, readUri(neutralUri)?.toString(Charsets.UTF_8))
        screenContext = RecordingContext(compose.activity)
        val metrics = context.resources.displayMetrics
        Log.i("QA022_STATS", "setup api=${Build.VERSION.SDK_INT} width=${metrics.widthPixels} " +
            "height=${metrics.heightPixels} density=${metrics.density} fontScale=${context.resources.configuration.fontScale}")
    }

    @After fun cleanup() {
        writerReleased.countDown()
        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
        if (shareJobs.isNotEmpty()) compose.waitUntil(15_000) { shareJobs.all { it.isCompleted } }
        rememberNewPngFiles()
        granted.forEach { context.revokeUriPermission(it, flags) }
        ApiClient.logout()
        ApiClient.resetForTest()
        syntheticPngFiles.forEach { it.delete() }
        if (neutralCreated) neutral.delete()
        if (::server.isInitialized) server.close()
        ApiClient.testBaseUrl = previousUrl
        ApiClient.testTimeoutMs = previousTimeout
        ApiClient.testTrace = previousTrace
    }

    private inner class RecordingContext(base: Context) : ContextWrapper(base) {
        override fun startActivity(intent: Intent) { started.add(Intent(intent)) }
        override fun getCacheDir(): File {
            if (failWriter && Looper.myLooper() != Looper.getMainLooper() && writerIntercepted.compareAndSet(false, true)) {
                writerEntered.countDown()
                throw IOException("QA022 synthetic cache access failure after actual bitmap rendering")
            }
            if (holdWriter && Looper.myLooper() != Looper.getMainLooper() && writerIntercepted.compareAndSet(false, true)) {
                writerEntered.countDown()
                val resumed = writerReleased.await(10, TimeUnit.SECONDS)
                writerGateTimedOut.set(!resumed)
                check(resumed) { "synthetic writer gate timed out" }
            }
            return super.getCacheDir()
        }
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun sharedFiles(): Map<String, String> {
        val dir = File(context.cacheDir, "shared")
        return dir.walkTopDown().filter { it.isFile }.associate {
            it.relativeTo(dir).invariantSeparatorsPath to digest(it.readBytes())
        }
    }

    private fun rememberNewPngFiles() {
        if (!::context.isInitialized || !::initialFiles.isInitialized) return
        val dir = File(context.cacheDir, "shared")
        dir.walkTopDown().filter { it.isFile && it.relativeTo(dir).invariantSeparatorsPath !in initialFiles }
            .forEach { file ->
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, options)
                if (options.outWidth == 1080 && options.outHeight == 1350 && options.outMimeType == "image/png") {
                    if (file !in syntheticPngFiles) syntheticPngFiles.add(file)
                }
            }
    }

    private fun readUri(uri: Uri): ByteArray? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }.getOrNull()

    private data class ShellRead(val bytes: ByteArray, val stderr: String)
    private fun shellRead(uri: Uri): ShellRead {
        val value = uri.toString()
        require(value.matches(Regex("content://[A-Za-z0-9._/%?=-]+")))
        // UiAutomation uses Runtime.exec: do not add shell quotes to the validated single URI.
        val descriptors = instrumentation.uiAutomation.executeShellCommandRwe("content read --uri $value")
        try {
            descriptors[1].close()
            val bytes = ParcelFileDescriptor.AutoCloseInputStream(descriptors[0]).use { it.readBytes() }
            val stderr = ParcelFileDescriptor.AutoCloseInputStream(descriptors[2]).bufferedReader().use { it.readText() }
            return ShellRead(bytes, stderr.replace(value, "<synthetic-uri>").take(600))
        } finally { descriptors.forEach { runCatching { it.close() } } }
    }

    private fun grantToShell(uri: Uri): Int {
        context.grantUriPermission("com.android.shell", uri, flags)
        granted.add(uri)
        val uid = context.packageManager.getApplicationInfo("com.android.shell", 0).uid
        assertTrue("a distinct real recipient is required", uid != Process.myUid() && uid != 0)
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkUriPermission(uri, Process.myPid(), uid, flags))
        return uid
    }

    private fun jobTree(job: Job): Set<Job> = buildSet {
        job.children.forEach { child -> add(child); addAll(jobTree(child)) }
    }

    private fun mount(language: AppLanguage) {
        compose.setContent {
            val observerScope = rememberCoroutineScope()
            // Public coroutine lifecycle only. The observer launches no work and changes no product scope.
            SideEffect { effectJob = observerScope.coroutineContext[Job]!!.parent }
            CompositionLocalProvider(LocalAppLanguage provides language, LocalContext provides screenContext) {
                YuldashTheme {
                    key(screenEpoch.value) {
                        if (mounted.value) MyStatsScreen(onBack = { backCount++; mounted.value = false })
                    }
                }
            }
        }
        waitForLoaded("QA_SYNTH_A", "QA_RANK_A")
    }

    private fun waitForLoaded(name: String, rank: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithText(rank).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(name).assertExists()
        compose.waitUntil(15_000) { apiTrace.any { it.contains("GET /me/achievements успешно (код 200)") } }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertNotNull("the Compose lifecycle must be observable", effectJob)
    }

    private fun clickShare(language: AppLanguage) {
        val label = if (language == AppLanguage.Ba) "Уртаҡлашыу" else "Поделиться"
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(label))
        compose.waitForIdle()
        compose.runOnIdle { baselineJobs = jobTree(requireNotNull(effectJob)) }
        compose.onNodeWithText(label).assertIsDisplayed().assertIsEnabled().performClick()
    }

    private fun captureActualShareJobs() {
        compose.runOnIdle {
            // The writer is held inside the actual withContext(IO), which is an active child
            // of the launched share Job. A newly started UI interaction collector has no
            // child and stays active while mounted: it must not be awaited as an operation.
            val candidates = requireNotNull(effectJob).children.flatMap { it.children }
                .filter { it.isActive && it !in baselineJobs }.toList()
            candidates.forEach { Log.i("QA022_STATS", "candidateTree=" + describeJobs(it)) }
            shareJobs = candidates.filter { it.children.any { child -> child.isActive } }
        }
        assertEquals("one held share launch with an active IO child must be present", 1, shareJobs.size)
        Log.i("QA022_STATS", "capturedActualOperationJobs=${shareJobs.size}")
        shareJobs.forEach { Log.i("QA022_STATS", "capturedTree=" + describeJobs(it)) }
    }

    private fun describeJobs(job: Job): String = "$job children=[${job.children.joinToString { describeJobs(it) }}]"

    private fun waitForShareJobs() {
        try {
            compose.waitUntil(15_000) { shareJobs.all { it.isCompleted } }
        } catch (error: Throwable) {
            shareJobs.forEach { Log.i("QA022_STATS", "timeoutTree=" + describeJobs(it)) }
            Log.i("QA022_STATS", "timeout intents=${started.size} files=${sharedFiles().keys}")
            throw error
        }
        compose.waitForIdle()
        assertFalse("the IO fixture must not time out", writerGateTimedOut.get())
    }

    @Suppress("DEPRECATION")
    private fun exportedImage(language: AppLanguage, rank: String): Pair<Uri, File> {
        val previousIntents = started.size
        clickShare(language)
        compose.waitUntil(15_000) { started.size > previousIntents }
        assertEquals("one logical share produces one intent", previousIntents + 1, started.size)
        val chooser = started.last()
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = requireNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("image/png", send.type)
        assertTrue(send.flags and flags != 0)
        val expected = if (language == AppLanguage.Ba && rank == "QA_RANK_B") {
            "Минең Юлдаш: 2 сәфәр, 50 км бергә, 90 ₽ янға ҡалды. Исемем — «$rank». Юлдашҡа ҡушыл: yulbash.ru"
        } else if (language == AppLanguage.Ba) {
            "Минең Юлдаш: 7 сәфәр, 120 км бергә, 350 ₽ янға ҡалды. Исемем — «$rank». Юлдашҡа ҡушыл: yulbash.ru"
        } else if (rank == "QA_RANK_B") {
            "Мой Юлдаш: 2 поездки, 50 км вместе, сэкономлено ~90 ₽. Звание — «$rank». Присоединяйся: yulbash.ru"
        } else {
            "Мой Юлдаш: 7 поездок, 120 км вместе, сэкономлено ~350 ₽. Звание — «$rank». Присоединяйся: yulbash.ru"
        }
        assertEquals("actual UTF caption must represent the requested synthetic statistics", expected, send.getStringExtra(Intent.EXTRA_TEXT))
        val uri = requireNotNull(send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertEquals("content", uri.scheme)
        assertEquals("${context.packageName}.fileprovider", uri.authority)
        val name = requireNotNull(uri.lastPathSegment)
        val file = requireNotNull(File(context.cacheDir, "shared").walkTopDown().firstOrNull { it.isFile && it.name == name })
        rememberNewPngFiles()
        val bytes = file.readBytes()
        assertArrayEquals("real provider bytes must equal the real Canvas PNG", bytes, readUri(uri))
        assertTrue(bytes.take(8).toByteArray().contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)))
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        try { assertEquals(1080, bitmap.width); assertEquals(1350, bitmap.height) } finally { bitmap.recycle() }
        return uri to file
    }

    @Test fun actualPngAndOldRecipientGrantDoNotSurviveLogoutOrExposeNextAccount() {
        val before = sharedFiles()
        mount(AppLanguage.Ba)
        val (uriA, fileA) = exportedImage(AppLanguage.Ba, "QA_RANK_A")
        val bytesA = fileA.readBytes()
        val hashA = digest(bytesA)
        File(context.cacheDir, "qa022-stats-actual-A.png").writeBytes(bytesA)
        assertEquals(1, server.requests.count { it.path == "/me/stats" && it.method == "GET" && it.auth == "Bearer $tokenA" })
        val shellUid = grantToShell(uriA)
        grantToShell(neutralUri)
        assertEquals("recipient must really read A before logout", hashA, digest(shellRead(uriA).bytes))
        assertEquals(neutralText, shellRead(neutralUri).bytes.toString(Charsets.UTF_8))
        compose.runOnIdle { ApiClient.logout(); ApiClient.saveToken(tokenB); ApiClient.saveName("QA_SYNTH_B") }
        val existsAfterLogout = fileA.exists()
        val afterLogout = sharedFiles()
        val oldGrant = context.checkUriPermission(uriA, Process.myPid(), shellUid, flags)
        val oldRead = shellRead(uriA)
        val appReadOld = readUri(uriA)
        // A second real screen mount tests old fixed-path grants exposing the next user's PNG.
        compose.runOnIdle { screenEpoch.value++ }
        waitForLoaded("QA_SYNTH_B", "QA_RANK_B")
        val (uriB, fileB) = exportedImage(AppLanguage.Ba, "QA_RANK_B")
        assertEquals(1, server.requests.count { it.path == "/me/stats" && it.method == "GET" && it.auth == "Bearer $tokenB" })
        val hashB = digest(fileB.readBytes())
        val nextReadViaOldUri = shellRead(uriA)
        val neutralGrant = context.checkUriPermission(neutralUri, Process.myPid(), shellUid, flags)
        val neutralRead = shellRead(neutralUri).bytes.toString(Charsets.UTF_8)
        val receipt = JSONObject().put("shellUid", shellUid).put("api", Build.VERSION.SDK_INT)
            .put("AHash", hashA).put("BHash", hashB).put("sameUri", uriA == uriB)
            .put("fileExistsAfterLogout", existsAfterLogout).put("oldGrantAfterLogout", oldGrant)
            .put("AReadableAfterLogout", digest(oldRead.bytes) == hashA)
            .put("AReadableByAppAfterLogout", appReadOld?.let(::digest) == hashA)
            .put("BReadableThroughOldUri", digest(nextReadViaOldUri.bytes) == hashB)
            .put("neutralGrant", neutralGrant).put("neutralReadable", neutralRead == neutralText)
            .put("oldReadStderr", oldRead.stderr).put("nextReadStderr", nextReadViaOldUri.stderr)
        File(context.cacheDir, "qa022-stats-privacy-receipt.json").writeText(receipt.toString(2))
        Log.i("QA022_STATS", receipt.toString())
        assertEquals(tokenB, ApiClient.currentToken())
        assertNotEquals("actual A and B cards must differ", hashA, hashB)
        assertEquals("unrelated shared files and grants must remain", before, afterLogout)
        assertEquals(PackageManager.PERMISSION_GRANTED, neutralGrant)
        assertEquals(neutralText, neutralRead)
        assertFalse("logout must erase the app-owned personal PNG", existsAfterLogout)
        assertEquals("logout must revoke the real old URI grant", PackageManager.PERMISSION_DENIED, oldGrant)
        assertFalse("old recipient must not read A after logout", digest(oldRead.bytes) == hashA)
        assertNull("the old own URI must stop opening A", appReadOld)
        assertFalse("old recipient grant must not expose the next account", digest(nextReadViaOldUri.bytes) == hashB)
    }

    @Test fun heldRealPngWriterAfterAccountSwitchAddsNoFileAndSendsNoOldIntent() {
        val before = sharedFiles()
        holdWriter = true
        mount(AppLanguage.Ba)
        clickShare(AppLanguage.Ba)
        try {
            compose.waitUntil(15_000) { writerEntered.count == 0L }
            captureActualShareJobs()
            assertTrue(apiTrace.any { it.contains("GET /me/stats успешно (код 200)") })
            assertEquals(before, sharedFiles())
            compose.runOnIdle { ApiClient.logout(); ApiClient.saveToken(tokenB); ApiClient.saveName("QA_SYNTH_B") }
            writerReleased.countDown()
            waitForShareJobs()
            rememberNewPngFiles()
            Log.i("QA022_STATS", "lateAccountSwitch completed=true intents=${started.size} fileSetUnchanged=${before == sharedFiles()}")
            assertEquals(tokenB, ApiClient.currentToken())
            assertEquals(neutralText, neutral.readText())
            assertEquals("late IO must not add or change any shared file", before, sharedFiles())
            assertTrue("late IO must not share an image or fallback text from A", started.isEmpty())
        } finally { writerReleased.countDown() }
    }

    @Test fun actualBackUnmountCancelsHeldPngWithoutImageTextFallbackOrOrphan() {
        val before = sharedFiles()
        holdWriter = true
        mount(AppLanguage.Ba)
        clickShare(AppLanguage.Ba)
        try {
            compose.waitUntil(15_000) { writerEntered.count == 0L }
            captureActualShareJobs()
            assertEquals(before, sharedFiles())
            compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Артҡа"))
            compose.onNodeWithContentDescription("Артҡа").assertIsDisplayed().performClick()
            compose.waitForIdle()
            assertEquals(1, backCount)
            assertFalse(mounted.value)
            assertTrue("actual UI unmount must cancel the launched operation", shareJobs.any { it.isCancelled })
            writerReleased.countDown()
            waitForShareJobs()
            rememberNewPngFiles()
            Log.i("QA022_STATS", "backUnmount completed=true intents=${started.size} fileSetUnchanged=${before == sharedFiles()}")
            assertEquals(tokenA, ApiClient.currentToken())
            assertEquals(neutralText, neutral.readText())
            assertEquals("a cancelled writer must not leave a private PNG", before, sharedFiles())
            assertTrue("cancellation must not turn into image or text sharing", started.isEmpty())
        } finally { writerReleased.countDown() }
    }

    @Suppress("DEPRECATION")
    @Test fun actualWriteFailureFallsBackToCurrentRussianCaptionWithoutFile() {
        val before = sharedFiles()
        mount(AppLanguage.Ru)
        failWriter = true
        clickShare(AppLanguage.Ru)
        compose.waitUntil(15_000) { started.isNotEmpty() }
        compose.waitForIdle()
        assertEquals("the real PNG writer must reach the failing cache access", 0L, writerEntered.count)
        assertEquals(1, started.size)
        val chooser = started.single()
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = requireNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertNull(send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertEquals("Мой Юлдаш: 7 поездок, 120 км вместе, сэкономлено ~350 ₽. Звание — «QA_RANK_A». Присоединяйся: yulbash.ru",
            send.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals(tokenA, ApiClient.currentToken())
        assertEquals("a failed writer must not leave a partial file", before, sharedFiles())
        compose.onNodeWithText("Поделиться").assertIsEnabled()
        Log.i("QA022_STATS", "realWriteFailure fallback=text/currentA fileSetUnchanged=true")
    }

    @Test fun loadedAccountACardCannotBeSharedUnderBWithoutScreenRemount() {
        val before = sharedFiles()
        holdWriter = true
        mount(AppLanguage.Ru)
        clickShare(AppLanguage.Ru)
        compose.waitUntil(15_000) { writerEntered.count == 0L }
        captureActualShareJobs()
        // Calibrate the exact public parent of the real product share launch while held.
        // It remains the same remembered scope when the loaded screen is not remounted.
        val sourceScope = requireNotNull(shareJobs.single().parent)
        writerReleased.countDown()
        waitForShareJobs()
        rememberNewPngFiles()
        assertEquals("positive A operation must finish before the ownership check", 1, started.size)
        assertTrue(sourceScope.isActive)
        compose.runOnIdle {
            ApiClient.logout(); ApiClient.saveToken(tokenB); ApiClient.saveName("QA_SYNTH_B")
            started.clear()
        }
        assertEquals(before, sharedFiles())
        compose.onNodeWithText("QA_SYNTH_A").assertExists()
        compose.onNodeWithText("QA_RANK_A").assertExists()
        writerEntered = CountDownLatch(1)
        writerReleased = CountDownLatch(1)
        writerIntercepted.set(false)
        compose.runOnIdle { assertTrue("calibrated product scope is idle", sourceScope.children.none()) }
        clickShare(AppLanguage.Ru)
        compose.waitForIdle()
        // This exact product scope excludes independent interaction/animation jobs. A wrong
        // owner guard starts a launch here and its real writer is held before file creation.
        compose.runOnIdle { shareJobs = sourceScope.children.toList() }
        if (shareJobs.isNotEmpty()) {
            compose.waitUntil(15_000) { writerEntered.count == 0L }
            writerReleased.countDown()
            waitForShareJobs()
            rememberNewPngFiles()
        }
        assertEquals(tokenB, ApiClient.currentToken())
        assertEquals("loaded A data must not be exported as B", before, sharedFiles())
        assertTrue("loaded A caption must not be shared as B", started.isEmpty())
        assertFalse(writerGateTimedOut.get())
        Log.i("QA022_STATS", "loadedAUnderB sameProductScope=true noFileOrIntent=true")
    }

    @Suppress("DEPRECATION")
    @Test fun rapidSecondTapWhileRealWriterHeldPublishesOneImage() {
        val before = sharedFiles()
        holdWriter = true
        mount(AppLanguage.Ru)
        clickShare(AppLanguage.Ru)
        try {
            compose.waitUntil(15_000) { writerEntered.count == 0L }
            captureActualShareJobs()
            compose.onNodeWithContentDescription("Поделиться").assertIsDisplayed().assertIsNotEnabled()
                .performTouchInput { click(); click() }
            assertTrue("nothing is published before the first writer is released", started.isEmpty())
            assertEquals(before, sharedFiles())
            writerReleased.countDown()
            waitForShareJobs()
            rememberNewPngFiles()
            assertEquals("rapid taps must produce one logical SEND", 1, started.size)
            val chooser = started.single()
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            val send = requireNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
            assertEquals(Intent.ACTION_SEND, send.action)
            assertEquals("image/png", send.type)
            val uri = requireNotNull(send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            assertNotNull(readUri(uri))
            val after = sharedFiles()
            assertEquals("one completed operation must add only one export", 1, (after.keys - before.keys).size)
            assertTrue(before.all { (name, hash) -> after[name] == hash })
            compose.onNodeWithText("Поделиться").assertIsEnabled()
            Log.i("QA022_STATS", "rapidTaps intents=1 addedFiles=1 currentA=true")
        } finally { writerReleased.countDown() }
    }

    private data class Request(val method: String, val path: String, val auth: String?)
    private inner class LoopbackServer : AutoCloseable {
        private val socket = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)) }
        private val running = AtomicBoolean(true)
        private val workers = Executors.newFixedThreadPool(3)
        private val clients = CopyOnWriteArrayList<Socket>()
        val requests = CopyOnWriteArrayList<Request>()
        val baseUrl = "http://127.0.0.1:${socket.localPort}"
        private val acceptor = thread(name = "qa-stats-loopback", isDaemon = true) {
            while (running.get()) {
                try {
                    val client = socket.accept()
                    clients.add(client)
                    workers.execute { serve(client) }
                } catch (error: Exception) {
                    if (running.get()) Log.e("QA022_STATS", "loopback accept error ${error.javaClass.name}")
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
                        request.method == "GET" && request.path == "/me/stats" -> {
                            val b = request.auth == "Bearer $tokenB"
                            JSONObject().put("trips", if (b) 2 else 7).put("km", if (b) 50 else 120)
                                .put("saved_rub", if (b) 90 else 350).put("co2_saved_kg", if (b) 3.5 else 12.5)
                                .put("rank", JSONObject().put("level", 1).put("title_ru", if (b) "QA_RANK_B" else "QA_RANK_A")
                                    .put("title_ba", if (b) "QA_RANK_B" else "QA_RANK_A").put("next_at", JSONObject.NULL)
                                    .put("next_title_ru", JSONObject.NULL).put("next_title_ba", JSONObject.NULL).put("to_next", 0))
                                .toString()
                        }
                        request.method == "GET" && request.path == "/me/achievements" -> "{\"achievements\":[],\"earned_count\":0}"
                        request.path == "/auth/logout" || request.path == "/push/unregister" -> "{}"
                        else -> { status = 404; "{}" }
                    }
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    val header = "HTTP/1.1 $status Test\r\nContent-Type: application/json; charset=utf-8\r\n" +
                        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    client.getOutputStream().apply { write(header.toByteArray(Charsets.US_ASCII)); write(bytes); flush() }
                }
            } catch (error: Exception) {
                if (running.get()) Log.e("QA022_STATS", "loopback request error ${error.javaClass.name}")
            } finally { clients.remove(client) }
        }

        override fun close() {
            running.set(false)
            socket.close()
            clients.forEach { runCatching { it.close() } }
            workers.shutdownNow()
            acceptor.join(5_000)
            check(!acceptor.isAlive && workers.awaitTermination(5, TimeUnit.SECONDS)) { "loopback workers did not stop" }
        }
    }
}
