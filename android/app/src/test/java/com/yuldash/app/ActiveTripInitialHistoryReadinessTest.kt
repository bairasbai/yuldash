package com.yuldash.app

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.tooling.CompositionData
import com.yuldash.app.data.SnapshotAuthorityPermit
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.MemoryDiskPreferences
import com.yuldash.app.data.OfflineTripSnapshots
import com.yuldash.app.data.TripPrivateProjection
import com.yuldash.app.data.Outbox
import com.yuldash.app.data.SnapshotWiringCleanupGuard
import com.yuldash.app.data.TripPassStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real cold/held HTTP controls. No product effect or authority implementation is copied here. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ActiveTripInitialHistoryReadinessTest {
    companion object { private val cleanupGuard = SnapshotWiringCleanupGuard() }
    private val clientContext = ClientJobContext()
    @get:Rule val compose = createComposeRule(effectContext = clientContext)
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mounted = mutableStateOf(false)
    private var contentSet = false
    private val backCalls = AtomicInteger()
    private lateinit var server: MockWebServer
    private val requests = CopyOnWriteArrayList<String>()
    private val sockets = CopyOnWriteArrayList<WebSocket>()
    private val historyCalls = AtomicInteger()
    private val clientReads = CopyOnWriteArrayList<ClientRead>()
    private var dataErrorLastObserved09: Boolean? = null
    private var compositionData10: CompositionData? = null
    private val roleArrived = CountDownLatch(1)
    private val detailsArrived = CountDownLatch(1)
    private val authorityRelease = CountDownLatch(1)
    private val historyStarted = CountDownLatch(1)
    private val historyRelease = CountDownLatch(1)
    private val codeCanReply = CountDownLatch(1)
    private val authenticated = CountDownLatch(1)
    private val departure = Instant.now().plusSeconds(86_400).toString()
    private val tokenA = "header.eyJzdWIiOiIxMSJ9.signature"
    private val tokenB = "header.eyJzdWIiOiIyMiJ9.signature"
    @Volatile private var holdAuthority = false
    @Volatile private var holdHistory = false
    @Volatile private var firstCodeWaitsForHistory = false
    @Volatile private var acceptSockets = true
    @Volatile private var codeValue = "1234"
    @Volatile private var initialMarker = "INITIAL_42"
    private val freshMarker = "FRESH_42"
    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Before fun setup() = cleanupGuard.runSetup {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = requireNotNull(request.requestUrl).encodedPath
                requests += request.method + " " + path
                if (path in setOf("/auth/logout", "/push/unregister")) return json(JSONObject())
                if (path == "/ws/bookings/42") {
                    if (!acceptSockets) return MockResponse().setResponseCode(503)
                    return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) { sockets += webSocket }
                        override fun onMessage(webSocket: WebSocket, text: String) { authenticated.countDown() }
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
                    })
                }
                return when (path) {
                    "/bookings/42/role" -> {
                        roleArrived.countDown()
                        if (holdAuthority) check(authorityRelease.await(60, TimeUnit.SECONDS))
                        json(JSONObject().put("role", "passenger").put("status", "confirmed").put("driver_phase", ""))
                    }
                    "/bookings/42/details" -> {
                        detailsArrived.countDown()
                        if (holdAuthority) check(authorityRelease.await(60, TimeUnit.SECONDS))
                        json(details())
                    }
                    "/bookings/42/boarding-code" -> {
                        val captured = codeValue
                        if (firstCodeWaitsForHistory && captured.isEmpty()) check(codeCanReply.await(60, TimeUnit.SECONDS))
                        json(JSONObject().put("code", captured))
                    }
                    "/bookings/42/messages" -> {
                        val ordinal = historyCalls.incrementAndGet()
                        val captured = if (ordinal == 1) initialMarker else freshMarker
                        historyStarted.countDown()
                        codeCanReply.countDown()
                        if (ordinal == 1 && holdHistory) check(historyRelease.await(60, TimeUnit.SECONDS))
                        json(JSONObject().put("items", JSONArray().put(JSONObject().put("id", ordinal)
                            .put("sender_id", 22).put("text", captured))))
                    }
                    else -> MockResponse().setResponseCode(404).setBody("{}")
                }
            }
        }
        server.start()
        ApiClient.resetForTest()
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        // Only this new held-response fixture: timeout cannot imitate cancellation during bounded UI steps.
        ApiClient.testTimeoutMs = 60_000
        ApiClient.init(context)
        ApiClient.logout()
        awaitReset()
        ApiClient.saveToken(tokenA)
        TripPassStore.initStores(MemoryDiskPreferences(), MemoryDiskPreferences())
        Outbox.initStores(MemoryDiskPreferences(), MemoryDiskPreferences())
        context.getSharedPreferences("yuldash", Context.MODE_PRIVATE).edit().putBoolean("chat_safety_seen", true).commit()
        assertOwnSession(11)
        ApiClient.testTrace = ::captureClientRead
    }

    // Observation only: inherited by the real Compose effects and their withContext(IO).
    // No dispatcher/Job replacement, cancellation, request hook delay or product effect copy.
    private class ClientJobContext : ThreadContextElement<Job?>, AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<ClientJobContext>
        private val current = ThreadLocal<Job?>()
        override fun updateThreadContext(context: CoroutineContext): Job? = current.get().also { current.set(context[Job]) }
        override fun restoreThreadContext(context: CoroutineContext, oldState: Job?) { current.set(oldState) }
        fun currentJob(): Job = requireNotNull(current.get()) { "Owned client coroutine context unavailable" }
    }
    private data class ClientRead(val path: String, val transport: Job, val caller: Job,
        val lastFixedPhase09: AtomicInteger = AtomicInteger(1))
    private fun captureClientRead(line: String) {
        // Fixed phases only: 1 entered, 2 HTTP 200 JSON decoded, 3 transport cancellation.
        // Phase 2 is before endpoint parsing/authority acceptance; it never claims semantic denial.
        val event = when (line) {
            "вошли в вызов GET /bookings/42/role" -> "/bookings/42/role" to 1
            "вошли в вызов GET /bookings/42/details" -> "/bookings/42/details" to 1
            "вошли в вызов GET /bookings/42/boarding-code" -> "/bookings/42/boarding-code" to 1
            "вошли в вызов GET /bookings/42/messages" -> "/bookings/42/messages" to 1
            "ВЫШЛИ из вызова GET /bookings/42/role успешно (код 200)" -> "/bookings/42/role" to 2
            "ВЫШЛИ из вызова GET /bookings/42/details успешно (код 200)" -> "/bookings/42/details" to 2
            "ВЫШЛИ из вызова GET /bookings/42/boarding-code успешно (код 200)" -> "/bookings/42/boarding-code" to 2
            "ВЫШЛИ из вызова GET /bookings/42/messages успешно (код 200)" -> "/bookings/42/messages" to 2
            "вызов GET /bookings/42/role ОТМЕНЁН (корутину закрыли снаружи)" -> "/bookings/42/role" to 3
            "вызов GET /bookings/42/details ОТМЕНЁН (корутину закрыли снаружи)" -> "/bookings/42/details" to 3
            "вызов GET /bookings/42/boarding-code ОТМЕНЁН (корутину закрыли снаружи)" -> "/bookings/42/boarding-code" to 3
            "вызов GET /bookings/42/messages ОТМЕНЁН (корутину закрыли снаружи)" -> "/bookings/42/messages" to 3
            else -> return
        }
        val path = event.first
        val transport = clientContext.currentJob()
        if (event.second != 1) {
            clientReads.lastOrNull { it.path == path && it.transport === transport }?.lastFixedPhase09?.set(event.second)
            return
        }
        // Current ApiClient.call has one withContext(IO), directly inside the calling production coroutine.
        val caller = requireNotNull(transport.parent) { "Owned client caller unavailable" }
        clientReads += ClientRead(path, transport, caller)
    }
    private fun clientRead(path: String): ClientRead {
        waitFor { clientReads.any { it.path == path } }
        return clientReads.first { it.path == path }
    }
    private fun awaitClientCompletion(reads: List<ClientRead>) {
        waitFor { reads.all { it.transport.isCompleted && it.caller.isCompleted } }
        compose.waitForIdle()
    }

    private fun details() = JSONObject().put("booking_id", 42).put("ride_id", 7)
        .put("role", "passenger").put("status", "confirmed").put("contact_unlocked", true)
        .put("from_city", "Пункт А").put("to_city", "Пункт Б").put("driver_name", "InitialHistoryDriver")
        .put("depart_at", departure).put("pay_method", "negotiate")
    private fun json(value: JSONObject) = MockResponse().setHeader("Content-Type", "application/json").setBody(value.toString())
    private fun awaitReset() = runBlocking {
        withTimeout(5_000) { while (!OfflineTripSnapshots.ensureResetCommitted()) delay(10) }
    }
    private fun assertOwnSession(user: Int) {
        val current = OfflineTripSnapshots.currentOwner()
        assertTrue("Prepared owner must be current at the owned local origin", current != null && current.userId == user &&
            current.origin == com.yuldash.app.data.snapshotOrigin(ApiClient.apiBase()) && ApiClient.isCurrentSession(current.generation))
    }
    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(10_000) {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        Snapshot.sendApplyNotifications()
        condition()
    }
    private fun textExists(value: String) = compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty().also {
        if (value == "Не удалось загрузить данные поездки") dataErrorLastObserved09 = it
    }
    private fun pump(millis: Long) {
        repeat((millis / 100).toInt()) {
            compose.mainClock.advanceTimeBy(100)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Snapshot.sendApplyNotifications()
            Thread.sleep(5)
        }
        compose.waitForIdle()
    }
    @OptIn(androidx.compose.runtime.InternalComposeApi::class)
    private fun mount() {
        if (!contentSet) {
            contentSet = true
            mounted.value = true
            compose.setContent {
                compositionData10 = currentComposer.compositionData
                if (mounted.value) CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru, LocalLifecycleOwner provides owner) {
                    ActiveTripScreen(ride = null, contacts = emptyList(), bookingId = 42,
                        onBack = { backCalls.incrementAndGet(); mounted.value = false }, onTripEnd = {}, onSos = {})
                }
            }
        } else compose.runOnIdle { mounted.value = true }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
    }
    private fun openChat() {
        waitFor { textExists("Написать") }
        compose.onNodeWithText("Написать").performClick()
        waitFor { compose.onAllNodesWithTag("tripChatScreen").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun noBusinessWrites() {
        val allowed = setOf("POST /auth/logout", "POST /push/unregister")
        assertTrue("Read controls must not send trip mutations", requests.all { it.startsWith("GET ") || it in allowed })
    }
    private inline fun observeHeldSoftFailure09(oldRead: ClientRead, action: () -> Unit) {
        try {
            action()
        } catch (failure: AssertionError) {
            runCatching { printHeldSoftFailure09(oldRead) }
            throw failure
        } catch (failure: ComposeTimeoutException) {
            runCatching { printHeldSoftFailure09(oldRead) }
            throw failure
        }
    }

    private fun printHeldSoftFailure09(oldRead: ClientRead) {
        // Raw RAM and pure gate predicates only: allowsLive/projection/privateDetails may expire authority.
        val authority = runCatching {
            val snapshot = OfflineTripSnapshots
            synchronized(snapshot) {
                val type = snapshot.javaClass
                val raw = (type.getDeclaredField("details").apply { isAccessible = true }.get(snapshot) as Map<*, *>)[42]
                    as? TripPrivateProjection
                val live = (type.getDeclaredField("live").apply { isAccessible = true }.get(snapshot) as Map<*, *>)[42]
                val livePermit = live?.javaClass?.getDeclaredField("permit")?.apply { isAccessible = true }?.get(live) as? SnapshotAuthorityPermit
                val currentOwner = snapshot.currentOwner()
                val permit = raw?.permit
                listOf("ownerPresent" to (currentOwner != null),
                    "ownerUser11" to (currentOwner?.userId == 11),
                    "ownerGenerationCurrent" to (currentOwner != null && ApiClient.isCurrentSession(currentOwner.generation)),
                    "rawLiveEntryPresent" to (live != null), "rawDetailsPresent" to (raw != null),
                    "rawLivePermitAvailable" to (live == null || livePermit != null),
                    "rawLivePermitObservable" to (livePermit?.input?.let {
                        snapshot.gate.canObserve(livePermit, livePermit.ticket, 42, it, livePermit.inputRide, livePermit.inputRoute)
                    } == true),
                    "rawDetailsBooking42" to (raw?.details?.bookingId == 42),
                    "rawDetailsOwnerCurrent" to (raw != null && raw.owner == currentOwner),
                    "rawDetailsProofVerified" to (raw?.proof?.verified() == true),
                    "rawDetailsProofOwnerCurrent" to (raw != null && raw.proof.owner == currentOwner),
                    "rawDetailsProofBooking42" to (raw?.proof?.request?.bookingId == 42),
                    "rawDetailsCodeBlocked" to (raw?.driverCodeBlocked == true),
                    "rawDetailsPermitPresent" to (permit != null),
                    "rawDetailsPermitObservable" to (permit?.input?.let {
                        snapshot.gate.canObserve(permit, permit.ticket, 42, it, permit.inputRide, permit.inputRoute)
                    } == true),
                    "privateGateAllowed" to (raw?.let { snapshot.gate.allowsPrivate(it.owner, 42, it.fingerprint) } == true))
            }
        }.getOrNull()
        val paths = listOf("/bookings/42/role", "/bookings/42/details",
            "/bookings/42/boarding-code", "/bookings/42/messages")
        val reads = clientReads.toList()
        val readJobs = paths.associateWith { path ->
            reads.filter { it.path == path }.map { read ->
                listOf("lastFixedPhase" to read.lastFixedPhase09.get(),
                    "transportCompleted" to read.transport.isCompleted, "transportCancelled" to read.transport.isCancelled,
                    "callerCompleted" to read.caller.isCompleted, "callerCancelled" to read.caller.isCancelled)
            }
        }
        // UI cache is from the original wait only. No new semantics query/idle/pump/wait occurs here.
        println("CHAT_HELD_SOFT_AFTER_FAILURE09 authorityAvailable=" + (authority != null) + " authority=" + authority +
            " arrivals=" + paths.associateWith { path -> requests.count { it == "GET $path" } } +
            " codeCalls=" + requests.count { it == "GET /bookings/42/boarding-code" } + " historyCalls=" + historyCalls.get() +
            " historyStarted=" + (historyStarted.count == 0L) + " historyReleased=" + (historyRelease.count == 0L) +
            " codeReplyAllowed=" + (codeCanReply.count == 0L) + " mounted=" + mounted.value + " backCalls=" + backCalls.get() +
            " oldHistoryTransportCompleted=" + oldRead.transport.isCompleted + " oldHistoryTransportCancelled=" + oldRead.transport.isCancelled +
            " oldHistoryCallerCompleted=" + oldRead.caller.isCompleted + " oldHistoryCallerCancelled=" + oldRead.caller.isCancelled +
            " readJobs=" + readJobs + " uiCurrentAvailable=false dataErrorLastObservedAvailable=" + (dataErrorLastObserved09 != null) +
            " dataErrorLastObserved=" + (dataErrorLastObserved09 == true) + " dataLoadingAvailable=false")
        println("CHAT_HELD_SOFT_AFTER_FAILURE10 details=" + detailsWitness10() + " denial=" + blankDenialWitness10())
    }

    @OptIn(androidx.compose.runtime.InternalComposeApi::class)
    private fun detailsWitness10(): String = runCatching {
        val root = compositionData10 ?: return@runCatching "available=false reason=notCaptured"
        val pending = java.util.ArrayDeque<androidx.compose.runtime.tooling.CompositionGroup>()
        val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<androidx.compose.runtime.tooling.CompositionGroup, Boolean>())
        val states = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<ActiveTripDetailsState, Boolean>())
        var groups = 0; var values = 0; var limited = false
        fun enqueue(children: Iterable<androidx.compose.runtime.tooling.CompositionGroup>) {
            for (child in children) {
                if (pending.size >= 2048) { limited = true; break }
                pending.addLast(child)
            }
        }
        enqueue(root.compositionGroups)
        while (pending.isNotEmpty() && !limited) {
            val group = pending.removeFirst()
            if (groups == 2048) { limited = true; break }
            if (!visited.add(group)) continue
            groups++
            for (value in group.data) {
                if (values == 8192) { limited = true; break }
                values++
                if (value is ActiveTripDetailsState) states.add(value)
                if (states.size > 1) break
            }
            if (states.size > 1) break
            enqueue(group.compositionGroups)
        }
        val state = states.singleOrNull()
        if (limited || state == null || states.size != 1) "available=false limited=$limited instances=" + states.size
        else "available=true loading=" + state.loading + " needsReload=" + state.needsReload +
            " failurePresent=" + (state.failure != null) + " reloadTick=" + state.reloadTick
    }.getOrElse { "available=false reason=readFailed" }

    private fun blankDenialWitness10(): String = runCatching {
        synchronized(OfflineTripSnapshots) outer@ {
            val snapshot = OfflineTripSnapshots
            val owner = snapshot.currentOwner() ?: return@outer "available=false reason=noOwner"
            val gate = snapshot.gate
            synchronized(gate) inner@ {
                val type = gate.javaClass
                val process = type.getDeclaredField("process").apply { isAccessible = true }.get(gate) as String
                val upper = type.getDeclaredField("sequence").apply { isAccessible = true }.get(gate) as Long
                if (upper !in 0L..64L) return@inner "available=false reason=rangeLimit"
                // This invalid-format sentinel can never be stored by expireCandidate; true means owner is not current.
                if (gate.candidateDenied(owner, 42, "probe10")) return@inner "available=false reason=gateOwnerNotCurrent"
                val found = (1L..upper).any { sequence -> gate.candidateDenied(owner, 42,
                    "blocked:" + com.yuldash.app.data.snapshotHash("blank-code:$process:$sequence")) }
                if (snapshot.currentOwner() != owner || snapshot.gate !== gate || gate.candidateDenied(owner, 42, "probe10"))
                    "available=false reason=ownerChanged"
                else "available=true retainedBlankDenial=$found"
            }
        }
    }.getOrElse { "available=false reason=readFailed" }

    private fun warmHeldHistory(marker: String): ClientRead {
        initialMarker = marker
        holdHistory = true
        // A real positive ROLE before this fault, not an injected permit or after-denial grant.
        runBlocking(clientContext) { ApiClient.getTripState(42).getOrThrow() }
        mount()
        waitFor { historyStarted.count == 0L }
        assertEquals(1, historyCalls.get())
        return clientRead("/bookings/42/messages")
    }

    @Test fun coldReadyStartsOneHistoryAndCompatibleReadsDoNotRepeatIt() {
        holdAuthority = true
        mount()
        waitFor { roleArrived.count == 0L && detailsArrived.count == 0L }
        pump(300)
        assertEquals(0, historyCalls.get())
        authorityRelease.countDown()
        waitFor { authenticated.count == 0L && textExists("1234") }
        waitFor { historyCalls.get() >= 1 }
        assertEquals(1, historyCalls.get())
        openChat()
        waitFor { textExists(initialMarker) }
        compose.onNodeWithText(initialMarker).assertIsDisplayed()
        awaitClientCompletion(listOf(clientRead("/bookings/42/messages")))
        val compatibleCaller = runBlocking(clientContext) {
            ApiClient.getTripState(42).getOrThrow()
            ApiClient.getBookingDetails(42).getOrThrow()
            ApiClient.getBoardingCode(42).getOrThrow()
            requireNotNull(coroutineContext[Job])
        }
        waitFor { compatibleCaller.isCompleted }
        compose.waitForIdle()
        assertEquals(1, historyCalls.get())
        noBusinessWrites()
    }

    @Test fun heldInitialAfterSoftDenialShowsErrorAndNeedsExplicitHistoryRetry() {
        acceptSockets = false // No successful reconnect is allowed to compete with this initial-read control.
        codeValue = ""
        firstCodeWaitsForHistory = true
        val oldRead = warmHeldHistory("OLD_SOFT_42")
        observeHeldSoftFailure09(oldRead) {
            waitFor { textExists("Не удалось загрузить данные поездки") }
        }
        assertEquals(1, historyCalls.get())
        val recoveryBoundary = requests.size
        codeValue = "5678"
        compose.onNodeWithText("Повторить загрузку данных").performScrollTo().assertIsEnabled().performClick()
        waitFor { textExists("5678") }
        assertEquals(listOf("GET /bookings/42/details", "GET /bookings/42/boarding-code"),
            requests.drop(recoveryBoundary).filter { it in setOf("GET /bookings/42/details", "GET /bookings/42/boarding-code") })
        assertEquals("Recovery must not automatically repeat a started initial history", 1, historyCalls.get())
        openChat()
        historyRelease.countDown()
        awaitClientCompletion(listOf(oldRead))
        waitFor { textExists("Не удалось загрузить чат") }
        compose.onAllNodesWithText("OLD_SOFT_42").assertCountEquals(0)
        compose.onAllNodesWithText("Пока нет сообщений. Напиши первым").assertCountEquals(0)
        compose.onNodeWithText("Не удалось загрузить чат").assertIsDisplayed()
        assertEquals(1, historyCalls.get())
        compose.onNodeWithText("Повторить").assertIsDisplayed().assertIsEnabled().performClick()
        waitFor { historyCalls.get() >= 2 && textExists(freshMarker) }
        assertEquals(2, historyCalls.get())
        compose.onNodeWithText(freshMarker).assertIsDisplayed()
        compose.onAllNodesWithText("OLD_SOFT_42").assertCountEquals(0)
        compose.onAllNodesWithText("Не удалось загрузить чат").assertCountEquals(0)
        noBusinessWrites()
    }

    @Test fun parentRemovedBeforeFirstReadyCannotStartOldHistory() {
        holdAuthority = true
        mount()
        waitFor { roleArrived.count == 0L && detailsArrived.count == 0L }
        pump(300)
        assertEquals(0, historyCalls.get())
        val oldReads = listOf(clientRead("/bookings/42/role"), clientRead("/bookings/42/details"))
        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
        authorityRelease.countDown()
        awaitClientCompletion(oldReads)
        assertEquals(0, historyCalls.get())
        compose.onAllNodesWithTag("tripChatScreen").assertCountEquals(0)
        compose.onAllNodesWithText(initialMarker).assertCountEquals(0)
        noBusinessWrites()
    }

    @Test fun ownerChangedBeforeFirstReadyCannotStartOldHistory() {
        holdAuthority = true
        mount()
        waitFor { roleArrived.count == 0L && detailsArrived.count == 0L }
        pump(300)
        assertEquals(0, historyCalls.get())
        val oldReads = listOf(clientRead("/bookings/42/role"), clientRead("/bookings/42/details"))
        val generation = ApiClient.queueSessionGeneration()
        ApiClient.logout()
        awaitReset()
        ApiClient.saveToken(tokenB)
        assertOwnSession(22)
        assertNotEquals(generation, ApiClient.queueSessionGeneration())
        waitFor { backCalls.get() > 0 }
        authorityRelease.countDown()
        awaitClientCompletion(oldReads)
        assertEquals(0, historyCalls.get())
        compose.onAllNodesWithTag("tripChatScreen").assertCountEquals(0)
        compose.onAllNodesWithText(initialMarker).assertCountEquals(0)
        assertOwnSession(22)
        noBusinessWrites()
    }

    @Test fun heldInitialCannotApplyAfterReplacementSessionOfSameUser() {
        val oldRead = warmHeldHistory("OLD_SESSION_42")
        waitFor { textExists("1234") }
        val generation = ApiClient.queueSessionGeneration()
        ApiClient.logout()
        awaitReset()
        ApiClient.saveToken(tokenA)
        assertOwnSession(11)
        assertNotEquals(generation, ApiClient.queueSessionGeneration())
        waitFor { backCalls.get() > 0 }
        historyRelease.countDown()
        awaitClientCompletion(listOf(oldRead))
        assertEquals(1, historyCalls.get())
        compose.onAllNodesWithText("OLD_SESSION_42").assertCountEquals(0)
        compose.onAllNodesWithTag("tripChatScreen").assertCountEquals(0)
        assertOwnSession(11)
        noBusinessWrites()
    }

    @Test fun heldInitialIsNotAdoptedByDisposedParentAndFreshParentReadsAgain() {
        val oldRead = warmHeldHistory("OLD_PARENT_42")
        waitFor { textExists("1234") }
        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
        historyRelease.countDown()
        awaitClientCompletion(listOf(oldRead))
        assertEquals(1, historyCalls.get())
        compose.onAllNodesWithText("OLD_PARENT_42").assertCountEquals(0)
        mount()
        waitFor { historyCalls.get() >= 2 }
        assertEquals(2, historyCalls.get())
        openChat()
        waitFor { textExists(freshMarker) }
        compose.onNodeWithText(freshMarker).assertIsDisplayed()
        compose.onAllNodesWithText("OLD_PARENT_42").assertCountEquals(0)
        noBusinessWrites()
    }

    @After fun cleanup() {
        val failures = mutableListOf<Throwable>()
        try {
            // A pre-existing poison skips mutations, but the finally still releases this instance's gates.
            cleanupGuard.requireHealthy()
            cleanupPhase(failures) {
                if (contentSet) compose.runOnIdle {
                    mounted.value = false
                    if (owner.registry.currentState != Lifecycle.State.INITIALIZED) owner.registry.currentState = Lifecycle.State.DESTROYED
                }
            }
        } finally {
            authorityRelease.countDown()
            historyRelease.countDown()
            codeCanReply.countDown()
        }
        cleanupPhase(failures) { Shadows.shadowOf(Looper.getMainLooper()).idle() }
        sockets.forEach { socket -> cleanupPhase(failures) { socket.close(1000, null) } }
        cleanupPhase(failures) { ApiClient.logout() }
        cleanupPhase(failures) { awaitClientCompletion(clientReads.toList()) }
        cleanupPhase(failures) { awaitFixtureJobs() }
        if (failures.isNotEmpty()) poisonCleanup(failures)
        try {
            cleanupGuard.runCleanup(true) {
                ApiClient.testTrace = null
                ApiClient.resetForTest()
                ApiClient.testTimeoutMs = null
                TripPassStore.initStores(MemoryDiskPreferences(), null)
                Outbox.initStores(MemoryDiskPreferences(), null)
                context.getSharedPreferences("yuldash", Context.MODE_PRIVATE).edit().remove("chat_safety_seen").commit()
                if (::server.isInitialized) server.shutdown()
            }
        } catch (failure: Throwable) { poisonCleanup(listOf(failure)) }
    }
    private fun cleanupPhase(failures: MutableList<Throwable>, action: () -> Unit) {
        try { action() } catch (failure: Throwable) { failures += failure }
    }
    private fun poisonCleanup(failures: List<Throwable>): Nothing {
        try { cleanupGuard.runCleanup(false) {} } catch (poison: Throwable) {
            failures.filter { it !== poison }.forEach { poison.addSuppressed(it) }
            throw poison
        }
        throw AssertionError(SnapshotWiringCleanupGuard.POISON_MARKER)
    }
    private fun awaitFixtureJobs() = runBlocking {
        val parents = listOf(ApiClient to "bg", OfflineTripSnapshots to "jobs").map { (owner, name) ->
            val scope = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner) as CoroutineScope
            requireNotNull(scope.coroutineContext[Job])
        }
        withTimeout(10_000) {
            while (true) {
                val children = parents.flatMap { it.children.toList() }
                if (children.isEmpty()) break
                children.forEach { it.join() }
            }
        }
    }
}
