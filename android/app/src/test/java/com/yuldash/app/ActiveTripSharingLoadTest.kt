package com.yuldash.app

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Actual ActiveTrip/Compose and loopback HTTP; no production link, SMS or external share. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ActiveTripSharingLoadTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mounted = mutableStateOf(true)
    private val requests = CopyOnWriteArrayList<Triple<String, String?, String>>()
    private val timeouts = CopyOnWriteArrayList<String>()
    private val started = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private lateinit var server: MockWebServer
    private var callbacks = emptyList<ConnectivityManager.NetworkCallback>()
    private var oldClick: (() -> Boolean)? = null
    private var job: Job? = null
    private var finished = 0
    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    @Volatile private var status = "confirmed"
    @Volatile private var held: String? = null
    @Volatile private var code = 200
    private var language = AppLanguage.Ru
    private var noContacts = false
    private var missingBooking = false
    private var dropMutationBody = false
    private val rows = linkedMapOf<Int, String>()
    private val listCalls = java.util.concurrent.atomic.AtomicInteger()
    private var listFailures = 0
    private var heldListOrdinal = -1
    private val listStarted = CountDownLatch(1)
    private val releaseList = CountDownLatch(1)
    private val listReplied = CountDownLatch(1)
    private var commitBeforeFailure = false
    private val createPath = "POST /bookings/42/share"
    private val revokePath = "DELETE /bookings/42/share/91"
    private val tokenA = "header.eyJzdWIiOiIxMSJ9.signature"
    private val tokenB = "header.eyJzdWIiOiIzMyJ9.signature"
    private val dto = """{"id":91,"contact_id":7,"token":"token91"}"""
    private val dto2 = """{"id":92,"contact_id":8,"token":"token92"}"""
    @Before fun prepare() {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(r: RecordedRequest): MockResponse {
                    val path = r.path.orEmpty()
                    val key = "${r.method} $path"
                    requests += Triple(key, r.getHeader("Authorization"), r.body.readUtf8())
                    if (path == "/bookings/42/shares") {
                        val ordinal = listCalls.incrementAndGet()
                        val snapshot = synchronized(rows) { rows.values.joinToString(",", "[", "]") }
                        if (ordinal == heldListOrdinal) {
                            listStarted.countDown()
                            if (!releaseList.await(40, TimeUnit.SECONDS)) timeouts += "list release"
                            listReplied.countDown()
                        }
                        return MockResponse().setResponseCode(if (ordinal <= listFailures) 503 else 200)
                            .setBody(if (ordinal <= listFailures) "{}" else snapshot)
                    }
                    if (key == createPath || key.startsWith("DELETE /bookings/42/share/")) {
                        val shareId = if (key == createPath) 91 else path.substringAfterLast('/').toInt()
                        if (code == 200 || commitBeforeFailure) synchronized(rows) {
                            if (key == createPath) rows[91] = dto else rows.remove(shareId)
                        }
                        if (key == held) {
                            started.countDown()
                            if (!release.await(40, TimeUnit.SECONDS)) timeouts += key
                        }
                        val response = MockResponse().setResponseCode(code).setBody(if (key == createPath) dto else "{}")
                        return if (dropMutationBody) response.setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY) else response
                    }
                    val body = when (path) {
                        "/bookings/42/role" -> """{"role":"passenger","status":"$status","driver_phase":"departed"}"""
                        "/bookings/42/messages" -> """{"items":[]}"""
                        "/bookings/42/boarding-code" -> """{"code":"1234"}"""
                        "/bookings/42/details" -> """{"booking_id":42,"role":"passenger","status":"$status","pay_method":"cash","pay_amount":400}"""
                        "/auth/logout", "/push/unregister" -> "{}"
                        "/trips/42/receipt" -> """{"booking_id":42,"role":"passenger","amount":400,"paid":true}"""
                        "/bookings/42/tip" -> """{"already_thanked":false}"""
                        else -> return MockResponse().setResponseCode(404).setBody("{}")
                    }
                    return MockResponse().setBody(body).setHeader("Content-Type", "application/json")
                }
            }
            start()
        }
        ApiClient.resetForTest(); ApiClient.init(context)
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 60000
        ApiClient.saveToken(tokenA)
        TripPassStore.initStores(MemoryDiskPreferences(), MemoryDiskPreferences())
        Outbox.initStores(MemoryDiskPreferences(), MemoryDiskPreferences())
        assertTrue(TripPassStore.save(context, TripPass.fromJson(JSONObject().put("booking_id", 42).put("boarding_code", "1234"))))
        ShadowToast.reset()
    }
    @After fun cleanup() {
        release.countDown(); releaseList.countDown()
        compose.runOnIdle { mounted.value = false; owner.registry.currentState = Lifecycle.State.DESTROYED }
        pump(300)
        callbacks.forEach { context.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        ApiClient.resetForTest(); ApiClient.testTimeoutMs = null
        TripPassStore.initStores(MemoryDiskPreferences(), null); Outbox.initStores(MemoryDiskPreferences(), null)
        server.shutdown()
        assertTrue("Fixture timed out: $timeouts", timeouts.isEmpty())
    }
    private fun pump(ms: Long = 700) {
        repeat((ms / 100).toInt()) {
            compose.mainClock.advanceTimeBy(100)
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
            Thread.sleep(10)
        }
        compose.waitForIdle()
    }
    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(12000) {
        compose.mainClock.advanceTimeBy(100); Shadows.shadowOf(Looper.getMainLooper()).idle(); condition()
    }
    private fun mount() {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val before = Shadows.shadowOf(cm).networkCallbacks.toSet()
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(LocalAppLanguage provides language, LocalLifecycleOwner provides owner) {
                ActiveTripScreen(null, if (noContacts) emptyList() else listOf(TrustedContact("Близкий", "Друг", "+70000000000", false, 7), TrustedContact("Другой", "Друг", "+70000000001", false, 8)), if (missingBooking) null else 42,
                    onBack = {}, onTripEnd = { finished++ }, onSos = {})
            }
        }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        if (!missingBooking) waitFor { requests.any { it.first == "GET /bookings/42/details" } }
        callbacks = Shadows.shadowOf(cm).networkCallbacks.filter { it !in before }
        compose.onNode(hasScrollToNodeAction() and SemanticsMatcher.keyIsDefined(
            androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasText(shareTitle()))
        compose.onNodeWithText(shareTitle()).performClick()
        if (!missingBooking) waitFor { requests.any { it.first == "GET /bookings/42/shares" } }
        pump()
    }

    private fun shareTitle() = if (language == AppLanguage.Ba) "Сәфәрҙе яҡының менән уртаҡлашыу" else "Поделиться поездкой с близким"
    private fun revokeLabel() = if (language == AppLanguage.Ba) "Кире алыу" else "Отозвать"
    private fun openAgain() {
        compose.onNodeWithText(shareTitle()).performClick()
        waitFor { listCalls.get() >= 2 }; pump()
    }
    private fun dismiss() {
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.Dismiss) { it() }
        pump()
        compose.onNode(isDialog()).assertDoesNotExist()
    }
    private fun retry() = compose.onNodeWithTag("tripSharesRetry").performClick()
    private fun link(id: Int) = server.url("/t/token$id").toString()
    private fun mutations() = requests.count { it.first == createPath || it.first.startsWith("DELETE /bookings/42/share/") }
    private fun action(kind: String) = if (kind == "create") compose.onNodeWithText("Близкий")
        else compose.onAllNodesWithText(revokeLabel())[0]
    private fun click(kind: String) = action(kind).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
    private fun waitAction(kind: String, click: () -> Boolean): kotlinx.coroutines.Job {
        val scopes = CompletedBoundaryProbe.captures(click, CoroutineScope::class.java).distinct()
        val before = scopes.flatMap { it.coroutineContext[kotlinx.coroutines.Job]!!.children.toList() }.toSet()
        compose.runOnIdle { click() }
        waitFor { started.count == 0L }
        return scopes.flatMap { it.coroutineContext[kotlinx.coroutines.Job]!!.children.toList() }.single { it !in before }
    }
    @Test fun ordinaryTokenListCreateAndRevokeControl() {
        mount(); action("create").performClick(); waitFor { mutations() == 1 }; pump()
        compose.onNodeWithText(link(91)).assertIsDisplayed()
        action("revoke").performClick(); waitFor { mutations() == 2 }; pump()
        compose.onNodeWithText("Кому отправить поездку").assertIsDisplayed()
        assertEquals(1, listCalls.get()); assertTrue(rows.isEmpty())
        assertEquals(7, JSONObject(requests.single { it.first == createPath }.third).getInt("contact_id"))
    }
    @Test fun emptyContactsControl() {
        noContacts = true; mount()
        compose.onNodeWithText("Сначала добавь доверенный контакт в профиле").assertIsDisplayed()
        assertEquals(0, mutations())
    }
    @Test fun missingBookingShowsUnavailableAndCanClose() {
        missingBooking = true; mount()
        compose.onNodeWithText("Не удалось открыть ссылки. Открой поездку заново.").assertIsDisplayed()
        compose.onNodeWithTag("tripSharesLoading").assertDoesNotExist()
        compose.onNodeWithText("Закрыть").performClick(); pump()
        compose.onNode(isDialog()).assertDoesNotExist(); assertEquals(0, listCalls.get()); assertEquals(0, mutations())
    }
    @Test fun existingTwoTokensControl() {
        rows[91] = dto; rows[92] = dto2; mount()
        compose.onNodeWithText(link(91)).assertIsDisplayed()
        assertEquals(2, compose.onAllNodesWithText(revokeLabel()).fetchSemanticsNodes().size)
    }
    @Test fun heldInitialListShowsLoadingAndBlocksMutation() {
        heldListOrdinal = 1; rows[91] = dto; mount()
        assertEquals(0L, listStarted.count)
        // If old UI exposes a contact, exercise it: requests are the authoritative mutation observation.
        if (compose.onAllNodesWithText("Близкий").fetchSemanticsNodes().isNotEmpty()) action("create").performClick()
        pump(); assertEquals("Mutation preceded authoritative initial list", 0, mutations())
        compose.onNodeWithTag("tripSharesLoading").assertIsDisplayed()
        compose.onNodeWithText("Близкий").assertDoesNotExist()
        releaseList.countDown(); waitFor { listReplied.count == 0L }; pump()
        compose.onNodeWithText(link(91)).assertIsDisplayed()
    }
    private fun listRetry(ba: Boolean, empty: Boolean) {
        language = if (ba) AppLanguage.Ba else AppLanguage.Ru
        listFailures = 1; if (!empty) rows[91] = dto
        mount(); compose.onNodeWithTag("tripSharesError").assertIsDisplayed()
        compose.onNodeWithText("Близкий").assertDoesNotExist(); assertEquals(0, mutations())
        retry(); waitFor { listCalls.get() == 2 }; pump()
        compose.onNodeWithTag("tripSharesError").assertDoesNotExist()
        if (empty) compose.onNodeWithText("Близкий").assertIsDisplayed()
        else compose.onNodeWithText(link(91)).assertIsDisplayed()
        assertEquals(0, mutations())
    }
    @Test fun russianListFailureRetriesToExistingToken() = listRetry(false, false)
    @Test fun bashkirListFailureRetriesToExistingToken() = listRetry(true, false)
    @Test fun listFailureRetriesToConfirmedEmpty() = listRetry(false, true)
    @Test fun doubleRetryStartsOneHeldGet() {
        listFailures = 1; heldListOrdinal = 2; mount()
        val old = compose.onNodeWithTag("tripSharesRetry").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { old(); old() }; waitFor { listStarted.count == 0L }; pump()
        assertEquals(2, listCalls.get()); compose.onNodeWithTag("tripSharesLoading").assertIsDisplayed()
        compose.runOnIdle { old() }; pump(); assertEquals(2, listCalls.get())
        releaseList.countDown(); pump()
    }
    @Test fun revokeFirstChoosesRemainingLiveToken() {
        rows[91] = dto; rows[92] = dto2; mount()
        action("revoke").performClick(); waitFor { mutations() == 1 }; pump()
        compose.onNodeWithText(link(91)).assertDoesNotExist()
        compose.onNodeWithText(link(92)).assertIsDisplayed()
        action("revoke").performClick(); waitFor { mutations() == 2 }; pump()
        compose.onNodeWithText(link(92)).assertDoesNotExist()
        compose.onNodeWithText("Кому отправить поездку").assertIsDisplayed()
    }
    private fun doubleMutation(kind: String, sameFrame: Boolean) {
        if (kind == "revoke") rows[91] = dto
        held = if (kind == "create") createPath else revokePath
        mount(); val old = click(kind)
        if (sameFrame) compose.runOnIdle { old(); old() }
        else { action(kind).performClick(); waitFor { started.count == 0L }; pump(); compose.runOnIdle { old() } }
        waitFor { started.count == 0L }; pump()
        assertEquals("More than one in-flight mutation", 1, mutations())
        action(kind).assertIsNotEnabled()
        if (kind == "create") compose.onNodeWithText("Другой").assertIsNotEnabled()
        release.countDown(); pump()
        if (kind == "create") compose.onNodeWithText(link(91)).assertIsDisplayed()
        else compose.onNodeWithText("Кому отправить поездку").assertIsDisplayed()
    }
    @Test fun sameFrameDoubleCreateSendsOnce() = doubleMutation("create", true)
    @Test fun laterDoubleCreateSendsOnce() = doubleMutation("create", false)
    @Test fun sameFrameDoubleRevokeSendsOnce() = doubleMutation("revoke", true)
    @Test fun laterDoubleRevokeSendsOnce() = doubleMutation("revoke", false)
    private fun reconcile(kind: String, accepted: Boolean, responseCode: Int) {
        if (kind == "revoke") { rows[91] = dto; rows[92] = dto2 }
        code = responseCode; commitBeforeFailure = accepted; mount()
        val old = click(kind); compose.runOnIdle { old() }
        waitFor { mutations() == 1 }; pump()
        compose.onNodeWithTag("tripSharesError").assertIsDisplayed()
        // An ambiguous result blocks retained clicks too until GET settles actual access.
        compose.runOnIdle { old() }; pump(); assertEquals(1, mutations())
        code = 200; retry(); waitFor { listCalls.get() == 2 }; pump()
        assertEquals(1, mutations())
        if (kind == "create" && accepted) compose.onNodeWithText(link(91)).assertIsDisplayed()
        if (kind == "create" && !accepted) compose.onNodeWithText("Близкий").assertIsDisplayed()
        if (kind == "revoke" && accepted) {
            compose.onNodeWithText(link(92)).assertIsDisplayed(); assertEquals(setOf(92), rows.keys)
        }
        if (kind == "revoke" && !accepted) {
            compose.onNodeWithText(link(91)).assertIsDisplayed(); assertEquals(setOf(91,92), rows.keys)
        }
    }
    @Test fun createCommittedBefore429IsReconciledWithoutPostRetry() = reconcile("create", true, 429)
    @Test fun createUncommitted503IsReconciledToEmpty() = reconcile("create", false, 503)
    @Test fun revokeCommittedBefore503KeepsSiblingToken() = reconcile("revoke", true, 503)
    @Test fun revokeUncommitted404DoesNotRemoveOwnToken() = reconcile("revoke", false, 404)
    @Test fun truncatedCommittedCreateBodyIsReconciledWithoutPostRetry() {
        dropMutationBody = true; reconcile("create", true, 200)
    }
    @Test fun truncatedCommittedRevokeBodyKeepsSiblingToken() {
        dropMutationBody = true; reconcile("revoke", true, 200)
    }
    @Test fun reconciliationGetFailureStillBlocksMutation() {
        code = 429; commitBeforeFailure = true; mount(); val old = click("create")
        compose.runOnIdle { old() }; waitFor { mutations() == 1 }; pump()
        listFailures = 2; retry(); waitFor { listCalls.get() == 2 }; pump()
        compose.onNodeWithTag("tripSharesError").assertIsDisplayed()
        compose.runOnIdle { old() }; pump(); assertEquals(1, mutations())
        listFailures = 0; retry(); waitFor { listCalls.get() == 3 }; pump()
        compose.onNodeWithText(link(91)).assertIsDisplayed()
    }
    private fun reopened(kind: String) {
        if (kind == "list") { heldListOrdinal = 1; rows[91] = dto }
        else held = createPath
        mount()
        var old: (() -> Boolean)? = null
        var oldJob: kotlinx.coroutines.Job? = null
        if (kind == "create") { old = click("create"); oldJob = waitAction("create", old) }
        dismiss()
        synchronized(rows) { rows.clear(); rows[92] = dto2 }
        openAgain(); compose.onNodeWithText(link(92)).assertIsDisplayed()
        ShadowToast.reset()
        if (kind == "list") { releaseList.countDown(); waitFor { listReplied.count == 0L } }
        else { release.countDown(); waitFor { oldJob!!.isCompleted }; compose.runOnIdle { old!!() } }
        pump(); compose.onNodeWithText(link(92)).assertIsDisplayed()
        compose.onNodeWithText(link(91)).assertDoesNotExist(); assertNull(ShadowToast.getTextOfLatestToast())
        assertEquals(if (kind == "list") 0 else 1, mutations())
    }
    @Test fun closedHeldListCannotReplaceReopenedSnapshot() = reopened("list")
    @Test fun closedHeldCreateCannotChangeReopenedSnapshot() = reopened("create")
}
