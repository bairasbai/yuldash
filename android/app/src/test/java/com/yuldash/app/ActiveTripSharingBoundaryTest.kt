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
class ActiveTripSharingBoundaryTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mounted = mutableStateOf(true)
    private val requests = CopyOnWriteArrayList<Triple<String, String?, String>>()
    @Volatile private var preparingPass: TripPass? = null
    private val preparationRequests = CopyOnWriteArrayList<String>()
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
    private var existing = false
    private val tokenA = "header.eyJzdWIiOiIxMSJ9.signature"
    private val tokenB = "header.eyJzdWIiOiIzMyJ9.signature"
    private val dto = """{"id":91,"contact_id":7,"link":"https://yulbash.ru/s/audit-local-only"}"""
    @Before fun prepare() {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(r: RecordedRequest): MockResponse {
                    val path = r.path.orEmpty()
                    val key = "${r.method} $path"
                    preparationResponse(r)?.let { return it }
                    requests += Triple(key, r.getHeader("Authorization"), r.body.readUtf8())
                    if (key == held) {
                        started.countDown()
                        if (!release.await(40, TimeUnit.SECONDS)) timeouts += key
                        return MockResponse().setResponseCode(code).setBody(if (key.startsWith("POST")) dto else "{}")
                    }
                    val body = when (path) {
                        "/bookings/42/role" -> """{"role":"passenger","status":"$status","driver_phase":"departed"}"""
                        "/bookings/42/messages" -> """{"items":[]}"""
                        "/bookings/42/boarding-code" -> """{"code":"1234"}"""
                        "/bookings/42/details" -> """{"booking_id":42,"role":"passenger","status":"$status","pay_method":"cash","pay_amount":400}"""
                        "/bookings/42/shares" -> if (existing) """{"items":[$dto]}""" else """{"items":[]}"""
                        "/bookings/42/share" -> dto
                        "/bookings/42/share/91", "/auth/logout", "/push/unregister" -> "{}"
                        "/trips/42/receipt" -> """{"booking_id":42,"role":"passenger","amount":400,"paid":true}"""
                        "/bookings/42/tip" -> """{"already_thanked":false}"""
                        else -> return MockResponse().setResponseCode(404).setBody("{}")
                    }
                    return MockResponse().setBody(canonicalTripBody(path, body)).setHeader("Content-Type", "application/json")
                }
            }
            start()
        }
        ApiClient.resetForTest()
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 60000
        ApiClient.init(context)
        awaitSnapshotCleanup()
        ApiClient.saveToken(tokenA)
        TripPassStore.initStores(MemoryDiskPreferences(), MemoryDiskPreferences())
        Outbox.initStores(MemoryDiskPreferences(), MemoryDiskPreferences())
        preparePositive(TripPass.fromJson(JSONObject().put("booking_id", 42).put("boarding_code", "1234").put("pay_method", "negotiate")))
        assertTrue(TripPassStore.save(context, TripPass.fromJson(JSONObject().put("booking_id", 42).put("boarding_code", "1234").put("pay_method", "negotiate"))))
        ShadowToast.reset()
    }
    @After fun cleanup() {
        release.countDown()
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
            if (mounted.value) CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru, LocalLifecycleOwner provides owner) {
                ActiveTripScreen(null, listOf(TrustedContact("Близкий", "Друг", "+70000000000", false, 7)), 42,
                    onBack = {}, onTripEnd = { finished++ }, onSos = {})
            }
        }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        waitFor { compose.onAllNodesWithText("Водитель отметит посадку").fetchSemanticsNodes().isNotEmpty() }
        callbacks = Shadows.shadowOf(cm).networkCallbacks.filter { it !in before }
        compose.onNode(hasScrollToNodeAction() and SemanticsMatcher.keyIsDefined(
            androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasText("Поделиться поездкой с близким"))
        compose.onNodeWithText("Поделиться поездкой с близким").performClick()
        waitFor { requests.any { it.first == "GET /bookings/42/shares" } }
        pump()
    }
    private fun action(kind: String) = compose.onNodeWithText(if (kind == "create") "Близкий" else "Отозвать")
    private fun path(kind: String) = if (kind == "create") "POST /bookings/42/share" else "DELETE /bookings/42/share/91"
    private fun start(kind: String) {
        oldClick = action(kind).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val scopes = CompletedBoundaryProbe.captures(oldClick!!, CoroutineScope::class.java).distinct()
        val before = scopes.flatMap { it.coroutineContext[Job]!!.children.toList() }.toSet()
        action(kind).performClick()
        waitFor { started.count == 0L }
        job = scopes.flatMap { it.coroutineContext[Job]!!.children.toList() }.single { it !in before }
    }
    private fun end(boundary: String) {
        when (boundary) {
            "dismiss" -> {
                // Sheet's own onDismissRequest, exercised by the modal's semantic dismiss action.
                compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss), useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.Dismiss) { it() }
            }
            "unmount" -> compose.runOnIdle { mounted.value = false }
            "session" -> compose.runOnIdle { ApiClient.saveToken(tokenB) }
            "done", "cancelled" -> {
                status = boundary
                compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }; pump(200)
                compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
                waitFor { TripPassStore.load(context, 42) == null && (boundary != "cancelled" || finished == 1) }
            }
        }
        pump(); ShadowToast.reset()
    }
    private fun late(kind: String, boundary: String, error: Boolean = false) {
        existing = kind == "revoke"; held = path(kind); if (error) code = 503
        mount(); start(kind); end(boundary)
        release.countDown(); waitFor { job!!.isCompleted }; pump()
        assertNull("Disposed share action showed notification", ShadowToast.getTextOfLatestToast())
        assertEquals(1, requests.count { it.first == path(kind) })
        assertFalse(requests.any { it.first.contains("/share") && it.second == "Bearer $tokenB" })
        if (boundary == "done") compose.onNodeWithText("Поездка завершена").assertIsDisplayed()
    }
    private fun retained(kind: String, boundary: String) {
        existing = kind == "revoke"; mount()
        val old = action(kind).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        end(boundary); compose.runOnIdle { old() }; pump()
        assertEquals("Retained callback sent a request", 0, requests.count { it.first == path(kind) })
        assertNull(ShadowToast.getTextOfLatestToast())
    }
    @Test fun ordinaryCreateAndRevokeUseOwnBookingAndContact() {
        mount(); action("create").performClick()
        waitFor { requests.any { it.first == path("create") } }; pump()
        compose.onNodeWithText("Активные ссылки").assertIsDisplayed()
        assertEquals(7, JSONObject(requests.single { it.first == path("create") }.third).getInt("contact_id"))
        assertEquals("Ссылка готова: Близкий", ShadowToast.getTextOfLatestToast())
        action("revoke").performClick(); waitFor { requests.any { it.first == path("revoke") } }; pump()
        compose.onNodeWithText("Кому отправить поездку").assertIsDisplayed()
        assertEquals("Ссылка отозвана", ShadowToast.getTextOfLatestToast())
        assertTrue(requests.filter { it.first.contains("/share") }.all { it.second == "Bearer $tokenA" })
    }
    @Test fun lateCreateAfterDismiss() = late("create", "dismiss")
    @Test fun lateCreateAfterDone() = late("create", "done")
    @Test fun lateCreateErrorAfterCancelled() = late("create", "cancelled", true)
    @Test fun lateCreateErrorAfterSessionChange() = late("create", "session", true)
    @Test fun lateCreateAfterUnmount() = late("create", "unmount")
    @Test fun lateRevokeAfterDismiss() = late("revoke", "dismiss")
    @Test fun lateRevokeAfterDone() = late("revoke", "done")
    @Test fun lateRevokeErrorAfterCancelled() = late("revoke", "cancelled", true)
    @Test fun lateRevokeErrorAfterSessionChange() = late("revoke", "session", true)
    @Test fun lateRevokeAfterUnmount() = late("revoke", "unmount")
    @Test fun retainedCreateAfterDismiss() = retained("create", "dismiss")
    @Test fun retainedCreateAfterDone() = retained("create", "done")
    @Test fun retainedCreateAfterSessionChange() = retained("create", "session")
    @Test fun retainedRevokeAfterDismiss() = retained("revoke", "dismiss")
    @Test fun retainedRevokeAfterCancelled() = retained("revoke", "cancelled")
    @Test fun retainedRevokeAfterSessionChange() = retained("revoke", "session")
    private fun awaitSnapshotCleanup() = kotlinx.coroutines.runBlocking {
        kotlinx.coroutines.withTimeout(5000) {
            while (!com.yuldash.app.data.OfflineTripSnapshots.ensureResetCommitted()) kotlinx.coroutines.delay(10)
        }
    }

    private fun preparationResponse(request: RecordedRequest): MockResponse? {
        val pass = preparingPass ?: return null
        val prefix = "/bookings/${pass.bookingId}"
        val body = when (request.path) {
            "$prefix/role" -> JSONObject().put("role", "passenger").put("status", "confirmed").put("driver_phase", "departed")
            "$prefix/details" -> pass.toJson().put("ride_id", pass.bookingId + 1000)
                .put("role", "passenger").put("status", "confirmed").put("contact_unlocked", true)
                .put("pay_method", pass.payMethod ?: "negotiate")
            "$prefix/boarding-code" -> JSONObject().put("code", pass.boardingCode)
            else -> return null
        }
        preparationRequests += "${request.method} ${request.path}"
        return MockResponse().setHeader("Content-Type", "application/json").setBody(body.toString())
    }
    private fun preparePositive(pass: TripPass) = kotlinx.coroutines.runBlocking {
        val start = preparationRequests.size
        check(preparingPass == null)
        preparingPass = pass
        try {
            ApiClient.getTripState(pass.bookingId).getOrThrow()
            val actual = ApiClient.getBookingDetails(pass.bookingId).getOrThrow()
            assertTrue("Setup must obtain its own actual current DETAILS", OfflineTripSnapshots.privateDetails(actual))
            assertEquals(pass.boardingCode, ApiClient.getBoardingCode(pass.bookingId).getOrThrow())
        } finally { preparingPass = null }
        val prefix = "GET /bookings/${pass.bookingId}"
        assertEquals(listOf("$prefix/role", "$prefix/details", "$prefix/boarding-code"),
            preparationRequests.drop(start))
    }

    private fun canonicalTripBody(path: String?, body: String): String {
        val endpoint = path.orEmpty()
        val details = endpoint.endsWith("/details")
        val role = endpoint.endsWith("/role")
        val receipt = endpoint.endsWith("/receipt")
        if (!details && !role && !receipt) return body
        val value = org.json.JSONObject(body)
        if ((details || receipt) && !value.has("ride_id")) value.put("ride_id", 1042)
        if (details) {
            if (!value.has("contact_unlocked")) value.put("contact_unlocked", value.optString("status") in setOf("confirmed", "onboard"))
            if (!value.has("pay_method")) value.put("pay_method", "negotiate")
        }
        if (role && value.optString("driver_phase") == "waiting") value.put("driver_phase", "")
        if ((role || details) && value.optString("status") == "onboard" && !value.has("onboard_at"))
            value.put("onboard_at", "2031-02-03T09:00:00.123456Z")
        return value.toString()
    }

}
