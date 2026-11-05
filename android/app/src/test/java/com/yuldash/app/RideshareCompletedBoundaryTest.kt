package com.yuldash.app

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** Real Compose callbacks; deliberately non-cancellable dependency models a result racing disposal. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RideshareCompletedBoundaryTest {
    @get:Rule val compose = createComposeRule()
    private val mounted = mutableStateOf(true)
    private val booking = mutableStateOf(42)
    private val calls = mutableListOf<Pair<String, Int>>()
    private var closes = 0
    private var receipts = 0
    private var supports = 0
    private var held: String? = null
    private var pending: Continuation<Result<Unit>>? = null
    private var scope: CoroutineScope? = null
    private var actionJob: Job? = null
    private val tokenA = "header.eyJzdWIiOiIxMSJ9.signature"
    private val tokenB = "header.eyJzdWIiOiIzMyJ9.signature"

    private lateinit var server: MockWebServer
    private val requests = CopyOnWriteArrayList<Pair<String, String?>>()
    private val fixtureFailures = CopyOnWriteArrayList<String>()
    private val ownedJobs = CopyOnWriteArrayList<Job>()
    @Volatile private var httpReceipt: TripReceiptDto? = null
    private var contentMounted = false

    private fun awaitBackgroundCompletion() = runBlocking {
        val apiScope = ApiClient::class.java.getDeclaredField("bg").apply { isAccessible = true }.get(ApiClient) as CoroutineScope
        val storageScope = OfflineTripSnapshots::class.java.getDeclaredField("jobs").apply { isAccessible = true }.get(OfflineTripSnapshots) as CoroutineScope
        try {
            withTimeout(10_000) {
                while (true) {
                    val children = listOf(apiScope, storageScope).flatMap { requireNotNull(it.coroutineContext[Job]).children.toList() }
                    if (children.isEmpty()) break
                    children.forEach { it.join() }
                }
            }
        } catch (failure: TimeoutCancellationException) {
            throw AssertionError("Fixture background jobs still running; local origin/server retained. Abort this isolated run.", failure)
        }
    }
    private fun awaitSnapshotCleanup() = runBlocking {
        withTimeout(5_000) { while (!OfflineTripSnapshots.ensureResetCommitted()) delay(10) }
    }
    private fun awaitOwnedCompletion() {
        if (!contentMounted) return
        try {
            compose.waitUntil(10_000) {
                compose.mainClock.advanceTimeBy(100)
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                ownedJobs.all { it.isCompleted }
            }
        } catch (failure: ComposeTimeoutException) {
            throw AssertionError("Fixture foreground jobs still running; local origin/server retained. Abort this isolated run.", failure)
        }
    }
    private suspend fun loadHistory(id: Int): Result<TripReceiptDto> {
        currentCoroutineContext()[Job]?.let(ownedJobs::add)
        return ApiClient.getTripReceipt(id).onSuccess { httpReceipt = it }
    }
    private fun awaitHistory(id: Int) {
        compose.waitUntil(12_000) {
            compose.mainClock.advanceTimeBy(100)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            httpReceipt?.let { it.bookingId == id && OfflineTripSnapshots.allowsHistory(it) } == true &&
                compose.onAllNodesWithTag("rideshareStar4").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue("Actual current receipt HISTORY required", httpReceipt?.let(OfflineTripSnapshots::allowsHistory) == true)
        assertTrue("Actual authenticated receipt GET required", requests.any { it.first == "GET /trips/$id/receipt" && it.second == "Bearer $tokenA" })
    }
    @Before fun prepare() {
        awaitBackgroundCompletion(); awaitSnapshotCleanup()
        ApiClient.resetForTest()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val key = "${request.method} ${request.path}"
                    val bearer = request.getHeader("Authorization")
                    requests += key to bearer
                    val match = Regex("^/trips/(42|43)/receipt$").matchEntire(request.path.orEmpty())
                    if (request.method != "GET" || match == null || bearer != "Bearer $tokenA") {
                        fixtureFailures += "Unexpected local request $key"
                        return MockResponse().setResponseCode(403).setBody("{}")
                    }
                    val id = match.groupValues[1].toInt()
                    return MockResponse().setHeader("Content-Type", "application/json").setBody("""{"booking_id":$id,"ride_id":9,"role":"passenger","from_city":"Уфа","to_city":"Бирск","depart_at":"2026-10-07T10:00:00","seats":1,"amount":400,"pay_method":"cash","paid":true,"driver_name":"Водитель","driver_verified":true,"counterparty_name":"Водитель","my_stars":0,"my_rating_tags":""}""")
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 60_000
        ApiClient.init(ApplicationProvider.getApplicationContext<Context>())
        awaitSnapshotCleanup()
        ApiClient.saveToken(tokenA)
        assertEquals(11, OfflineTripSnapshots.currentOwner()?.userId)
        assertFalse("Initial login must not invent live authority", OfflineTripSnapshots.allowsLive(42))
    }
    @After fun cleanup() {
        if (!::server.isInitialized) { awaitBackgroundCompletion(); awaitSnapshotCleanup(); return }
        pending?.resume(Result.success(Unit)); pending = null
        if (contentMounted) compose.runOnIdle { mounted.value = false }
        awaitOwnedCompletion(); awaitBackgroundCompletion(); awaitSnapshotCleanup()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server.shutdown()
        assertTrue("Unexpected fixture requests: $fixtureFailures", fixtureFailures.isEmpty())
    }
    private suspend fun action(kind: String, id: Int): Result<Unit> {
        currentCoroutineContext()[Job]?.let(ownedJobs::add)
        calls += kind to id
        return if (held == kind && id == 42) suspendCoroutine { pending = it } else Result.success(Unit)
    }
    private fun mount() {
        contentMounted = true
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                RideshareCompletedScreen(booking.value, null, "passenger", "cash", 400,
                    onClose = { closes++ }, onOpenReceipt = { receipts++ }, onSupport = { supports++ },
                    loadReceipt = ::loadHistory,
                    rateBooking = { id, stars, _, _ -> assertEquals(4, stars); action("rating", id) },
                    loadThanks = { Result.success(TipInfoDto("Водитель", false, null)) },
                    sendThanks = { action("thanks", it) }, openLostItem = { action("lost", it) })
            }
        }
        awaitHistory(booking.value)
        compose.waitForIdle()
        compose.onNodeWithTag("rideshareStar4").assertIsEnabled().performScrollTo().performClick()
    }
    private fun node(kind: String): SemanticsNodeInteraction {
        val matcher = when (kind) {
            "thanks" -> hasText("Сказать «Рәхмәт»")
            "lost" -> hasTestTag("rideshareLostItem")
            "receipt" -> hasText("Квитанция")
            "support" -> hasText("Нужна помощь?")
            else -> null
        }
        if (matcher != null) compose.onNodeWithTag("rideshareCompletedList").performScrollToNode(matcher)
        return when (kind) {
        "rating" -> compose.onNodeWithTag("rideshareRatingSubmit")
        "thanks" -> compose.onNodeWithText("Сказать «Рәхмәт»")
        "lost" -> compose.onNodeWithTag("rideshareLostItem")
        "receipt" -> compose.onNodeWithText("Квитанция")
        "support" -> compose.onNodeWithText("Нужна помощь?")
        else -> error(kind)
        }
    }
    private fun click(kind: String): () -> Boolean {
        val n = node(kind)
        return n.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
    }
    private fun boundary(kind: String) {
        compose.runOnIdle {
            when (kind) {
                "unmount" -> mounted.value = false
                "booking" -> booking.value = 43
                "session" -> ApiClient.saveToken(tokenB)
                else -> error(kind)
            }
        }
        compose.waitForIdle()
        if (kind == "booking") awaitHistory(booking.value)
    }
    private fun retained(kind: String, end: String) {
        mount(); val old = click(kind)
        boundary(end)
        val before = Triple(closes, receipts, supports)
        compose.runOnIdle { old() }
        compose.waitForIdle()
        assertEquals("Disposed navigation callback was invoked", before, Triple(closes, receipts, supports))
        assertTrue("Old action reached dependency: $calls", calls.isEmpty())
        if (end == "booking") {
            // New booking still accepts its own receipt action.
            node("receipt").performScrollTo().performClick()
            assertEquals(before.second + 1, receipts)
        }
    }
    private fun late(kind: String, end: String, failure: Boolean = false) {
        held = kind; mount()
        val old = click(kind)
        scope = CompletedBoundaryProbe.captures(old, CoroutineScope::class.java).single()
        val beforeJobs = scope!!.coroutineContext[Job]!!.children.toSet()
        node(kind).performClick()
        compose.waitUntil(5000) { pending != null }
        actionJob = scope!!.coroutineContext[Job]!!.children.single { it !in beforeJobs }
        boundary(end)
        // Inspect abandoned state too: merely hiding its screen does not prove the callback was rejected.
        val states = CompletedBoundaryProbe.captures(old, MutableState::class.java).associateWith { it.value }
        pending!!.resume(if (failure) Result.failure(IllegalStateException("late-private-error")) else Result.success(Unit))
        pending = null
        compose.waitUntil(5000) { Shadows.shadowOf(Looper.getMainLooper()).idle(); actionJob!!.isCompleted }
        compose.waitForIdle()
        assertEquals("Late result wrote to disposed state", states, states.keys.associateWith { it.value })
        assertEquals(listOf(kind to 42), calls)
        if (end == "booking") {
            compose.onNodeWithText("late-private-error", substring = true).assertDoesNotExist()
            compose.onNodeWithTag("rideshareStar4").performScrollTo().performClick()
            node(kind).performClick()
            compose.waitUntil(5000) { calls.contains(kind to 43) }
        }
    }
    @Test fun ordinaryCompletedActionsRemainAvailable() {
        mount()
        node("receipt").performScrollTo().performClick(); assertEquals(1, receipts)
        node("support").performScrollTo().performClick(); assertEquals(1, supports)
        node("thanks").performScrollTo().performClick()
        node("lost").performScrollTo().performClick()
        node("rating").performClick()
        assertEquals(listOf("thanks" to 42, "lost" to 42, "rating" to 42), calls)
        node("rating").performClick(); assertEquals(1, closes)
    }
    @Test fun retainedReceiptAfterUnmount() = retained("receipt", "unmount")
    @Test fun retainedReceiptAfterBookingChange() = retained("receipt", "booking")
    @Test fun retainedReceiptAfterSessionChange() = retained("receipt", "session")
    @Test fun retainedSupportAfterUnmount() = retained("support", "unmount")
    @Test fun retainedSupportAfterBookingChange() = retained("support", "booking")
    @Test fun retainedSupportAfterSessionChange() = retained("support", "session")
    @Test fun retainedRatingAfterUnmount() = retained("rating", "unmount")
    @Test fun retainedRatingAfterBookingChange() = retained("rating", "booking")
    @Test fun retainedRatingAfterSessionChange() = retained("rating", "session")
    @Test fun retainedThanksAfterUnmount() = retained("thanks", "unmount")
    @Test fun retainedThanksAfterBookingChange() = retained("thanks", "booking")
    @Test fun retainedThanksAfterSessionChange() = retained("thanks", "session")
    @Test fun retainedLostItemAfterUnmount() = retained("lost", "unmount")
    @Test fun retainedLostItemAfterBookingChange() = retained("lost", "booking")
    @Test fun retainedLostItemAfterSessionChange() = retained("lost", "session")
    @Test fun lateRatingAfterUnmount() = late("rating", "unmount")
    @Test fun lateRatingAfterBookingChange() = late("rating", "booking")
    @Test fun lateRatingErrorAfterSessionChange() = late("rating", "session", true)
    @Test fun lateThanksAfterUnmount() = late("thanks", "unmount")
    @Test fun lateThanksAfterBookingChange() = late("thanks", "booking")
    @Test fun lateThanksErrorAfterSessionChange() = late("thanks", "session", true)
    @Test fun lateLostItemAfterUnmount() = late("lost", "unmount")
    @Test fun lateLostItemAfterBookingChange() = late("lost", "booking")
    @Test fun lateLostItemErrorAfterSessionChange() = late("lost", "session", true)
}

internal object CompletedBoundaryProbe {
    fun <T> captures(root: Any, type: Class<T>): List<T> {
        val seen = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        val found = mutableListOf<T>()
        fun visit(value: Any?, depth: Int) {
            if (value == null || depth > 10 || !seen.add(value)) return
            if (type.isInstance(value)) { found += type.cast(value); return }
            if (value is MutableState<*>) { visit(value.value, depth + 1); return }
            if (value !is kotlin.Function<*> && !value.javaClass.name.contains("Clickable")) return
            generateSequence(value.javaClass as Class<*>?) { it.superclass }.flatMap { it.declaredFields.asSequence() }
                .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .filter { value is kotlin.Function<*> || it.name in listOf("onClick", "onLongClick") }
                .forEach { it.isAccessible = true; visit(it.get(value), depth + 1) }
        }
        visit(root, 0)
        return found
    }
}
