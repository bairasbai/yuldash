package com.yuldash.app

import android.app.Application
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.ui.semantics.SemanticsActions
import kotlinx.coroutines.Job
import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.IdentityHashMap
import android.content.Context
import android.net.ConnectivityManager
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import java.util.concurrent.CountDownLatch
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Actual Compose actions and HTTP. Audio bytes and picker URI are synthetic; no physical mic/gallery. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ActiveTripChatActionsBoundaryTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mounted = mutableStateOf(false)
    private val requests = CopyOnWriteArrayList<Pair<String, String?>>()
    private val sockets = CopyOnWriteArrayList<WebSocket>()
    private val auth = LinkedBlockingQueue<String>()
    private val sent = LinkedBlockingQueue<String>()
    private val closed = AtomicInteger()
    private val refreshed = AtomicInteger()
    private val historyHeld = CountDownLatch(1)
    private val releaseHistory = CountDownLatch(1)
    private val postHeld = CountDownLatch(1)
    private val releasePost = CountDownLatch(1)
    private val fixtureFailures = CopyOnWriteArrayList<String>()
    private val postKeys = CopyOnWriteArrayList<String?>()
    private val jobs = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var holdInitialHistory = false
    @Volatile private var holdNextHistory = false
    @Volatile private var historyCode = 200
    @Volatile private var holdPostBooking: Int? = null
    @Volatile private var postReply = "success"
    @Volatile private var heldPath: String? = null
    @Volatile private var heldCode = 200
    @Volatile private var ordinaryHistory = false
    @Volatile private var refreshUploads = false
    private var deleteScope = "all"
    private val actionHeld = CountDownLatch(1)
    private val releaseAction = CountDownLatch(1)
    private val bodies = CopyOnWriteArrayList<Pair<String, String>>()
    private val files = CopyOnWriteArrayList<File>()
    private var actionJob: Job? = null
    private lateinit var composerScope: CoroutineScope
    private val picker = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                assertEquals("image/*", input)
                val uri = Uri.parse("content://audit/owned-photo")
                val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.GREEN) }
                val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                bitmap.recycle()
                Shadows.shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))
                dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(uri))
            }
        }
    }
    private var backs = 0
    private var finished = 0
    private var language = AppLanguage.Ru
    private var ownedNetworkCallbacks = emptyList<ConnectivityManager.NetworkCallback>()
    private lateinit var server: MockWebServer
    @Volatile private var remoteStatus = "confirmed"
    private val tokenA = "header.eyJzdWIiOiIxMSJ9.signature"
    private val tokenA2 = "header.eyJzdWIiOiIxMSJ9.refreshed"
    private val tokenB = "header.eyJzdWIiOiIzMyJ9.signature"
    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Before fun prepare() = runBlocking {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    val bearer = request.getHeader("Authorization")
                    requests += "${request.method} $path" to bearer
                    if (path == "/ws/bookings/42") return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) { sockets += webSocket }
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val body = JSONObject(text)
                            if (body.getString("type") == "auth") auth.offer(body.getString("token"))
                            else sent.offer(body.getString("text"))
                        }
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.incrementAndGet() }
                    })
                    if (path == "/voice" || path == "/upload/chat-photo" || path.contains("/messages/1") ||
                        request.method == "POST" && path == "/bookings/42/messages") {
                        bodies += path to request.body.readUtf8()
                        if (path == heldPath) {
                            actionHeld.countDown()
                            if (!releaseAction.await(45, TimeUnit.SECONDS)) fixtureFailures += "Action release timeout: $path"
                        }
                        val code = if (path == heldPath && !(refreshUploads && bearer == "Bearer $tokenA2")) heldCode else 200
                        return MockResponse().setResponseCode(code).setBody(if (code == 200 && path in listOf("/voice", "/upload/chat-photo"))
                            """{"url":"https://yulbash.ru/audit-media"}""" else "{}")
                    }
                    if (request.method == "POST" && path.matches(Regex("/bookings/[0-9]+/messages"))) {
                        postKeys += request.getHeader("Idempotency-Key")
                        if (path == "/bookings/$holdPostBooking/messages" && postHeld.count == 1L) {
                            postHeld.countDown()
                            if (!releasePost.await(25, TimeUnit.SECONDS)) fixtureFailures += "POST release timeout"
                            if (postReply == "temporary") return MockResponse().setResponseCode(503).setBody("{}")
                            if (postReply == "network") return MockResponse().setBody("{partial-data").setHeader("Content-Length", 1000)
                                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                        }
                        return MockResponse().setBody("{}")
                    }
                    if (request.method == "GET" && path == "/bookings/42/messages") {
                        if (holdInitialHistory || holdNextHistory) {
                            historyHeld.countDown()
                            if (!releaseHistory.await(25, TimeUnit.SECONDS)) fixtureFailures += "History release timeout"
                            return MockResponse().setResponseCode(historyCode).setBody(if (historyCode == 200)
                                """{"items":[{"id":77,"sender_id":22,"text":"late-history"}]}""" else "{}")
                        }
                        return MockResponse().setBody(if (ordinaryHistory && bodies.isNotEmpty())
                            """{"items":[{"id":88,"sender_id":11,"text":"updated-history"}]}""" else
                            """{"items":[{"id":1,"sender_id":11,"text":"initial-history"}]}""")
                    }
                    val body = when (path) {
                        "/auth/verify" -> {
                            val account = JSONObject(request.body.readUtf8()).getString("name")
                            """{"access_token":"${if (account == "A") tokenA else tokenB}","refresh_token":"refresh-$account"}"""
                        }
                        "/auth/refresh" -> { refreshed.incrementAndGet(); """{"access_token":"$tokenA2","refresh_token":"refresh-A2"}""" }
                        "/trusted-contacts" -> return MockResponse().setResponseCode(if (bearer == "Bearer $tokenA") 401 else 200).setBody("""{"items":[]}""")
                        "/auth/logout", "/push/unregister" -> "{}"
                        "/bookings/42/role" -> """{"role":"passenger","status":"$remoteStatus","driver_phase":"departed"}"""
                        "/bookings/42/messages" -> """{"items":[{"id":1,"sender_id":22,"text":"${if (bearer == "Bearer $tokenB") "private-history-B" else "private-history-A"}"}]}"""
                        "/bookings/42/boarding-code" -> """{"code":"1234"}"""
                        "/bookings/42/details" -> """{"booking_id":42,"role":"passenger","status":"confirmed","pay_method":"cash","pay_amount":700}"""
                        "/trips/42/receipt" -> """{"booking_id":42,"role":"passenger","from_city":"Город А","to_city":"Город Б","counterparty_name":"Водитель","amount":700,"paid":true}"""
                        "/bookings/42/tip" -> """{"already_thanked":false}"""
                        else -> return MockResponse().setResponseCode(404).setBody("{}")
                    }
                    return MockResponse().setBody(body).setHeader("Content-Type", "application/json")
                }
            }
            start()
        }
        ApiClient.resetForTest()
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 60000
        ApiClient.init(context)
        ApiClient.logout()
        ApiClient.verifyCode("+70000000000", "000000", "A").getOrThrow()
        TripPassStore.initStores(MemoryDiskPreferences(), MemoryDiskPreferences())
        Outbox.initStores(MemoryDiskPreferences(), MemoryDiskPreferences())
        assertTrue(TripPassStore.save(context, TripPass.fromJson(JSONObject().put("booking_id", 42).put("boarding_code", "1234"))))
        ShadowToast.reset()
        Unit
    }

    @After fun cleanup() {
        releaseHistory.countDown()
        releasePost.countDown()
        releaseAction.countDown()
        jobs.cancel()
        compose.runOnIdle {
            mounted.value = false
            if (owner.registry.currentState != Lifecycle.State.INITIALIZED) owner.registry.currentState = Lifecycle.State.DESTROYED
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        sockets.forEach { it.close(1000, null) }
        val bearer = ApiClient.currentToken()?.let { "Bearer $it" }
        ApiClient.logout()
        if (bearer != null) waitFor { requests.contains("POST /auth/logout" to bearer) }
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        TripPassStore.initStores(MemoryDiskPreferences(), null)
        Outbox.initStores(MemoryDiskPreferences(), null)
        pump(500)
        files.forEach { it.delete() }
        server.shutdown()
        assertTrue("Fixture timeouts: $fixtureFailures", fixtureFailures.isEmpty())
    }

    private fun listeners(): List<Any> = (NetworkMonitor::class.java.getDeclaredField("listeners").apply { isAccessible = true }
        .get(NetworkMonitor) as List<*>).mapNotNull { (it as WeakReference<*>).get() }
    private fun screenSocket(before: List<Any>): ChatSocket = listeners().filter { it !in before }.flatMap { listener ->
        listener.javaClass.declaredFields.mapNotNull { field -> field.isAccessible = true; field.get(listener) as? ChatSocket }
    }.single()
    private data class Callbacks(
        val message: (ChatSocket.Incoming) -> Unit, val connected: (Boolean) -> Unit,
        val rejected: (Int, String) -> Unit, val messages: MutableState<*>,
        val failures: MutableState<*>, val connection: MutableState<*>,
    )
    @Suppress("UNCHECKED_CAST")
    private fun callbacks(socket: ChatSocket): Callbacks {
        fun field(name: String) = ChatSocket::class.java.getDeclaredField(name).apply { isAccessible = true }.get(socket)
        val message = field("onMessage") as (ChatSocket.Incoming) -> Unit
        val connected = field("onConnected") as (Boolean) -> Unit
        val rejected = field("onRejected") as (Int, String) -> Unit
        // Kotlin's invokedynamic lambdas name captures arg$N; select the unique semantic value type.
        fun state(callback: Any, name: String) = callback.javaClass.declaredFields.mapNotNull {
            it.isAccessible = true; it.get(callback) as? MutableState<*>
        }.single { when (name) {
            "messages" -> it.value is List<*>
            "failedIds" -> it.value is Set<*>
            "wsConnected" -> it.value is Boolean
            else -> false
        } }
        return Callbacks(message, connected, rejected, state(message, "messages"), state(rejected, "failedIds"), state(connected, "wsConnected"))
    }
    private fun mount(openChat: Boolean = !holdInitialHistory): Callbacks {
        val before = listeners()
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val callbacksBefore = Shadows.shadowOf(connectivity).networkCallbacks.toSet()
        mounted.value = true
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(LocalAppLanguage provides language, LocalLifecycleOwner provides owner, LocalActivityResultRegistryOwner provides picker) {
                ActiveTripScreen(ride = null, contacts = emptyList(), bookingId = 42,
                    onBack = { backs++ }, onTripEnd = { finished++ }, onSos = {})
            }
        }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        waitFor { auth.peek() == tokenA && requests.any { it.first == "GET /bookings/42/messages" } &&
            compose.onAllNodesWithText("Я сел").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(tokenA, auth.poll())
        ownedNetworkCallbacks = Shadows.shadowOf(connectivity).networkCallbacks.filter { it !in callbacksBefore }
        assertEquals(1, ownedNetworkCallbacks.size)
        val result = callbacks(screenSocket(before))
        if (openChat) {
            waitFor { requests.any { it.first == "GET /bookings/42/details" } }
            compose.onNodeWithText("Написать").performClick()
            pump(1000)
            tripList().performScrollToNode(hasText("initial-history"))
            compose.onNodeWithText("initial-history").assertIsDisplayed()
            tripList().performScrollToNode(hasContentDescription("Записать голос"))
            val click = compose.onNodeWithContentDescription("Записать голос").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            composerScope = captures(result.connected, CoroutineScope::class.java).distinct().single()
            tripList().performScrollToNode(hasText("initial-history"))
        }
        return result
    }
    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(15000) {
        compose.mainClock.advanceTimeBy(100)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        condition()
    }
    private fun tripList() = compose.onNode(hasScrollToNodeAction() and
        SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange))
    private fun pump(millis: Long = 2000) {
        repeat((millis / 100).toInt()) {
            compose.mainClock.advanceTimeBy(100)
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
            Thread.sleep(10)
        }
        compose.waitForIdle()
    }
    private fun terminal(value: String) {
        remoteStatus = value
        // A lifecycle resume starts a real role GET immediately, avoiding a virtual 12-second timer claim.
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        pump(300)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        waitFor { TripPassStore.load(context, 42) == null && (value != "cancelled" || finished == 1) }
        if (value == "done") compose.onNodeWithText("Поездка завершена").assertIsDisplayed()
    }
    // Read captured jobs to await the actual UI action, without replacing product callbacks.
    private fun <T> captures(root: Any, type: Class<T>): List<T> {
        val seen = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        val found = mutableListOf<T>()
        fun visit(value: Any?, depth: Int) {
            if (value == null || depth > 8 || !seen.add(value)) return
            if (type.isInstance(value)) { found += type.cast(value); return }
            if (value is MutableState<*>) { visit(value.value, depth + 1); return }
            if (value !is kotlin.Function<*> && !value.javaClass.name.contains("Clickable")) return
            val fields = generateSequence(value.javaClass as Class<*>?) { it.superclass }.flatMap { it.declaredFields.asSequence() }
            fields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .filter { value is kotlin.Function<*> || it.name in listOf("onClick", "onLongClick") }.forEach {
                it.isAccessible = true; visit(it.get(value), depth + 1)
            }
        }
        visit(root, 0)
        return found
    }
    private fun actionPath(kind: String) = when (kind) {
        "edit" -> "/bookings/42/messages/1/edit"
        "delete" -> "/bookings/42/messages/1?scope=$deleteScope"
        "voice" -> "/voice"
        "photo" -> "/upload/chat-photo"
        else -> error(kind)
    }
    private fun startAction(kind: String) {
        val before = composerScope.coroutineContext[Job]!!.children.toSet()
        if (kind == "edit" || kind == "delete") {
            tripList().performScrollToNode(hasText("initial-history"))
            compose.onNodeWithText("initial-history").performTouchInput { longClick() }
            if (kind == "edit") {
                compose.onNodeWithText("Редактировать").performClick()
                tripList().performScrollToNode(hasSetTextAction())
                compose.onNode(hasSetTextAction()).performTextReplacement("edited-message")
                compose.onNodeWithContentDescription("Отправить").performClick()
            } else compose.onNodeWithText(if (deleteScope == "me") "Удалить у себя" else "Удалить у всех").performClick()
        } else {
            tripList().performScrollToNode(hasContentDescription("Записать голос"))
            if (kind == "photo") compose.onNodeWithContentDescription("Прикрепить фото").performClick()
            else {
                Shadows.shadowOf(context as Application).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
                val click = compose.onNodeWithContentDescription("Записать голос").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
                val recorder = captures(click, VoiceRecorder::class.java).single()
                compose.onNodeWithContentDescription("Записать голос").performClick()
                waitFor { compose.onAllNodesWithContentDescription("Отправить запись").fetchSemanticsNodes().isNotEmpty() }
                val path = VoiceRecorder::class.java.getDeclaredField("path").apply { isAccessible = true }.get(recorder) as String
                val file = File(path).also { it.writeBytes(byteArrayOf(1, 2, 3, 4)) }; files += file
                compose.onNodeWithContentDescription("Отправить запись").performClick()
            }
        }
        waitFor { composerScope.coroutineContext[Job]!!.children.any { it !in before } }
        actionJob = composerScope.coroutineContext[Job]!!.children.filter { it !in before }.single()
    }
    private fun awaitAction() { waitFor { actionJob!!.isCompleted }; pump(300) }
    private fun historyCount() = requests.count { it.first == "GET /bookings/42/messages" }
    private fun messagePosts() = requests.count { it.first == "POST /bookings/42/messages" }
    private fun assertFilesClean() = files.forEach { assertFalse("Owned voice file survived action completion: ${it.name}", it.exists()) }
    private fun heldMutation(kind: String, status: String, code: Int, sendPhase: Boolean = false) {
        mount()
        heldPath = if (sendPhase) "/bookings/42/messages" else actionPath(kind); heldCode = code
        startAction(kind); waitFor { actionHeld.count == 0L }
        terminal(status); ShadowToast.reset()
        val histories = historyCount(); val posts = messagePosts()
        releaseAction.countDown(); awaitAction()
        assertEquals("Late action fetched history", histories, historyCount())
        assertEquals("Late upload submitted a message", posts, messagePosts())
        assertNull("Late action showed Toast", ShadowToast.getTextOfLatestToast())
        assertFilesClean()
    }
    private fun heldHistory(kind: String, boundary: String) {
        val cb = mount(); holdNextHistory = true
        startAction(kind); waitFor { historyHeld.count == 0L }
        val before = cb.messages.value
        when (boundary) {
            "done", "cancelled" -> terminal(boundary)
            "switch" -> { compose.runOnIdle { ApiClient.saveToken(tokenB) }; pump(300) }
            "back" -> { compose.runOnIdle { mounted.value = false }; pump(300) }
        }
        ShadowToast.reset(); releaseHistory.countDown(); awaitAction()
        assertEquals("Late history changed old state", before, cb.messages.value)
        assertNull(ShadowToast.getTextOfLatestToast())
        assertFalse(requests.any { it.first.contains("/bookings/42/") && it.second == "Bearer $tokenB" })
        assertFilesClean()
    }
    private fun removedDuringUpload(kind: String, boundary: String) {
        mount(); heldPath = actionPath(kind)
        startAction(kind); waitFor { actionHeld.count == 0L }
        if (boundary == "switch") compose.runOnIdle { ApiClient.saveToken(tokenB) }
        else compose.runOnIdle { mounted.value = false }
        pump(300); ShadowToast.reset(); val histories = historyCount()
        releaseAction.countDown(); awaitAction()
        assertEquals(0, messagePosts()); assertEquals(histories, historyCount())
        assertNull(ShadowToast.getTextOfLatestToast()); assertFilesClean()
    }
    private fun ordinary(kind: String, refresh: Boolean = false) {
        val cb = mount(); heldPath = actionPath(kind); ordinaryHistory = true
        val generation = ApiClient.queueSessionGeneration()
        refreshUploads = refresh; if (refresh) heldCode = 401
        startAction(kind); waitFor { actionHeld.count == 0L }; releaseAction.countDown(); awaitAction()
        assertEquals(2, historyCount())
        assertEquals(if (kind in listOf("voice", "photo")) 1 else 0, messagePosts())
        assertEquals("Bearer ${if (refresh) tokenA2 else tokenA}", requests.last { it.first.endsWith(actionPath(kind)) }.second)
        assertEquals(generation, ApiClient.queueSessionGeneration())
        if (refresh) { assertEquals(1, refreshed.get()); assertEquals(2, requests.count { it.first == "POST ${actionPath(kind)}" }) }
        assertEquals("updated-history", (cb.messages.value as List<*>).map { it as MessageDto }.single().text)
        tripList().performScrollToNode(hasText("updated-history"))
        compose.onNodeWithText("updated-history").assertIsDisplayed()
        if (kind == "edit") {
            assertTrue(requests.any { it.first == "POST ${actionPath(kind)}" })
            assertEquals("edited-message", JSONObject(bodies.single { it.first == actionPath(kind) }.second).getString("text"))
        }
        if (kind == "delete") assertTrue(requests.any { it.first == "DELETE ${actionPath(kind)}" })
        if (kind == "voice") {
            assertTrue(bodies.last { it.first == "/voice" }.second.contains("filename=\"voice.m4a\""))
            assertTrue(bodies.last { it.first == "/voice" }.second.contains("\u0001\u0002\u0003\u0004"))
            assertEquals("https://yulbash.ru/audit-media", JSONObject(bodies.single { it.first == "/bookings/42/messages" }.second).getString("voice_url"))
        }
        if (kind == "photo") {
            assertTrue(bodies.last { it.first == "/upload/chat-photo" }.second.contains("filename=\"photo.jpg\""))
            assertEquals("[img]https://yulbash.ru/audit-media", JSONObject(bodies.single { it.first == "/bookings/42/messages" }.second).getString("text"))
        }
        assertFilesClean()
    }
    private fun changeBoundary(boundary: String) {
        when (boundary) {
            "done", "cancelled" -> terminal(boundary)
            "switch" -> { compose.runOnIdle { ApiClient.saveToken(tokenB) }; pump(300) }
            "back" -> { compose.runOnIdle { mounted.value = false }; pump(300) }
        }
    }
    private fun savedVoiceFinish(boundary: String) {
        mount(); tripList().performScrollToNode(hasContentDescription("Записать голос"))
        Shadows.shadowOf(context as Application).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        val mic = compose.onNodeWithContentDescription("Записать голос").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val recorder = captures(mic, VoiceRecorder::class.java).single()
        compose.onNodeWithContentDescription("Записать голос").performClick()
        waitFor { compose.onAllNodesWithContentDescription("Отправить запись").fetchSemanticsNodes().isNotEmpty() }
        val finish = compose.onNodeWithContentDescription("Отправить запись").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val path = VoiceRecorder::class.java.getDeclaredField("path").apply { isAccessible = true }.get(recorder) as String
        changeBoundary(boundary)
        val voice = File(path).also { it.writeBytes(byteArrayOf(1, 2, 3, 4)) }; files += voice
        val adjacent = File(context.cacheDir, "keep_chat_action.jpg").also { it.writeText("keep") }; files += adjacent
        ShadowToast.reset(); val before = requests.size
        // Retained click is invoked deliberately after disposal/terminal, not a real tap on a hidden screen.
        compose.runOnIdle { finish(); if (boundary == "cancel-launch") composerScope.cancel() }; pump(1000)
        assertFalse("Late retained voice callback left its own recording", voice.exists())
        assertTrue("Voice cleanup removed an unrelated file", adjacent.exists())
        if (boundary == "cancel-launch") assertEquals("Голос записан", ShadowToast.getTextOfLatestToast())
        else assertNull("Retained voice callback showed Toast", ShadowToast.getTextOfLatestToast())
        assertFalse(requests.drop(before).any { it.first == "POST /voice" || it.first.contains("/messages") })
    }
    private fun savedEditSend(boundary: String) {
        mount(); tripList().performScrollToNode(hasText("initial-history"))
        compose.onNodeWithText("initial-history").performTouchInput { longClick() }
        compose.onNodeWithText("Редактировать").performClick()
        tripList().performScrollToNode(hasSetTextAction())
        compose.onNode(hasSetTextAction()).performTextReplacement("retained-edit")
        val send = compose.onNodeWithContentDescription("Отправить").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        changeBoundary(boundary); ShadowToast.reset(); val before = requests.size
        compose.runOnIdle { send() }; pump(1000)
        assertFalse("Retained edit callback submitted HTTP", requests.drop(before).any { it.first.contains("/messages") })
        assertNull(ShadowToast.getTextOfLatestToast())
    }
    @Test fun retainedVoiceFinishAtDone() = savedVoiceFinish("done")
    @Test fun retainedVoiceFinishAtCancelled() = savedVoiceFinish("cancelled")
    @Test fun retainedVoiceFinishAtSwitch() = savedVoiceFinish("switch")
    @Test fun retainedVoiceFinishAtBack() = savedVoiceFinish("back")
    @Test fun voiceCancelledInSameMainBatchBeforeUpload() = savedVoiceFinish("cancel-launch")
    @Test fun deleteMineOrdinaryActionStillWorks() { deleteScope = "me"; ordinary("delete") }
    @Test fun voiceUploadRefreshKeepsSameOwner() = ordinary("voice", refresh = true)
    @Test fun photoUploadRefreshKeepsSameOwner() = ordinary("photo", refresh = true)
    @Test fun retainedEditSendAtDone() = savedEditSend("done")
    @Test fun retainedEditSendAtCancelled() = savedEditSend("cancelled")
    @Test fun retainedEditSendAtSwitch() = savedEditSend("switch")
    @Test fun retainedEditSendAtBack() = savedEditSend("back")
    @Test fun editHeld200AtDone() = heldMutation("edit", "done", 200)
    @Test fun editHeld503AtDone() = heldMutation("edit", "done", 503)
    @Test fun editLateHistoryAtDone() = heldHistory("edit", "done")
    @Test fun editHeld200AtCancelled() = heldMutation("edit", "cancelled", 200)
    @Test fun editHeld503AtCancelled() = heldMutation("edit", "cancelled", 503)
    @Test fun editLateHistoryAtCancelled() = heldHistory("edit", "cancelled")
    @Test fun editLateHistoryAtSwitch() = heldHistory("edit", "switch")
    @Test fun editLateHistoryAtBack() = heldHistory("edit", "back")
    @Test fun editOrdinaryActionStillWorks() = ordinary("edit")
    @Test fun deleteHeld200AtDone() = heldMutation("delete", "done", 200)
    @Test fun deleteHeld503AtDone() = heldMutation("delete", "done", 503)
    @Test fun deleteLateHistoryAtDone() = heldHistory("delete", "done")
    @Test fun deleteHeld200AtCancelled() = heldMutation("delete", "cancelled", 200)
    @Test fun deleteHeld503AtCancelled() = heldMutation("delete", "cancelled", 503)
    @Test fun deleteLateHistoryAtCancelled() = heldHistory("delete", "cancelled")
    @Test fun deleteLateHistoryAtSwitch() = heldHistory("delete", "switch")
    @Test fun deleteLateHistoryAtBack() = heldHistory("delete", "back")
    @Test fun deleteOrdinaryActionStillWorks() = ordinary("delete")
    @Test fun voiceHeld200AtDone() = heldMutation("voice", "done", 200)
    @Test fun voiceHeld503AtDone() = heldMutation("voice", "done", 503)
    @Test fun voiceLateHistoryAtDone() = heldHistory("voice", "done")
    @Test fun voiceHeld200AtCancelled() = heldMutation("voice", "cancelled", 200)
    @Test fun voiceHeld503AtCancelled() = heldMutation("voice", "cancelled", 503)
    @Test fun voiceLateHistoryAtCancelled() = heldHistory("voice", "cancelled")
    @Test fun voiceLateHistoryAtSwitch() = heldHistory("voice", "switch")
    @Test fun voiceLateHistoryAtBack() = heldHistory("voice", "back")
    @Test fun voiceOrdinaryActionStillWorks() = ordinary("voice")
    @Test fun photoHeld200AtDone() = heldMutation("photo", "done", 200)
    @Test fun photoHeld503AtDone() = heldMutation("photo", "done", 503)
    @Test fun photoLateHistoryAtDone() = heldHistory("photo", "done")
    @Test fun photoHeld200AtCancelled() = heldMutation("photo", "cancelled", 200)
    @Test fun photoHeld503AtCancelled() = heldMutation("photo", "cancelled", 503)
    @Test fun photoLateHistoryAtCancelled() = heldHistory("photo", "cancelled")
    @Test fun photoLateHistoryAtSwitch() = heldHistory("photo", "switch")
    @Test fun photoLateHistoryAtBack() = heldHistory("photo", "back")
    @Test fun photoOrdinaryActionStillWorks() = ordinary("photo")
    @Test fun voiceUploadAtSwitch() = removedDuringUpload("voice", "switch")
    @Test fun voiceUploadAtBack() = removedDuringUpload("voice", "back")
    @Test fun voiceSendHeld200AtDone() = heldMutation("voice", "done", 200, true)
    @Test fun voiceSendHeld503AtDone() = heldMutation("voice", "done", 503, true)
    @Test fun voiceSendHeld200AtCancelled() = heldMutation("voice", "cancelled", 200, true)
    @Test fun voiceSendHeld503AtCancelled() = heldMutation("voice", "cancelled", 503, true)
    @Test fun photoUploadAtSwitch() = removedDuringUpload("photo", "switch")
    @Test fun photoUploadAtBack() = removedDuringUpload("photo", "back")
    @Test fun photoSendHeld200AtDone() = heldMutation("photo", "done", 200, true)
    @Test fun photoSendHeld503AtDone() = heldMutation("photo", "done", 503, true)
    @Test fun photoSendHeld200AtCancelled() = heldMutation("photo", "cancelled", 200, true)
    @Test fun photoSendHeld503AtCancelled() = heldMutation("photo", "cancelled", 503, true)
}
