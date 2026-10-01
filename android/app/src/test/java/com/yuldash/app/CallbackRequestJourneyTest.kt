package com.yuldash.app

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.ui.theme.YuldashTheme
import okhttp3.mockwebserver.*
import org.json.JSONArray
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * QA-B08-001: real SimpleMode -> protected navigation -> CallbackHelp -> ApiClient HTTP.
 * Synthetic authenticated session and loopback responses; no operator/Telegram delivery proof.
 * The held response is released by an event, never by a sleep or a modified product handler.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h1200dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CallbackRequestJourneyTest {
    private val accessToken = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxIn0.synthetic-signature"
    @get:Rule val compose = createComposeRule()
    private val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
    private val mounted = mutableStateOf(true)
    private val callbacks = CopyOnWriteArrayList<CallbackRecord>()
    private val paths = CopyOnWriteArrayList<String>()
    private val traces = CopyOnWriteArrayList<String>()
    private val firstEntered = CountDownLatch(1)
    private val releaseFirst = CountDownLatch(1)
    private val gateTimedOut = AtomicBoolean(false)
    private val releasedAt = AtomicLong(0)
    private lateinit var server: MockWebServer
    private lateinit var vm: YuldashViewModel
    private var contentSet = false
    private var language = AppLanguage.Ru
    @Volatile private var firstStatus = 200

    private data class CallbackRecord(
        val ordinal: Int,
        val body: String,
        val correctAuthorization: Boolean,
        val receivedAt: Long,
        @Volatile var returnedAt: Long = 0,
        @Volatile var responseStatus: Int = 0,
    )

    @Before fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        assertTrue(context.getSharedPreferences("yuldash_prefs", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("onboarding_completed", true).commit())
        ApiClient.resetForTest()
        ShadowToast.reset()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path?.substringBefore('?')
                    paths.add("${request.method} $path")
                    if (path == "/callback" && request.method == "POST") {
                        val record = synchronized(callbacks) {
                            CallbackRecord(callbacks.size + 1, request.body.readUtf8(),
                                request.getHeader("Authorization") == "Bearer $accessToken", System.nanoTime())
                                .also { callbacks.add(it) }
                        }
                        var status = 200
                        if (record.ordinal == 1) {
                            firstEntered.countDown()
                            if (!releaseFirst.await(10, TimeUnit.SECONDS)) {
                                gateTimedOut.set(true)
                                status = 503
                            } else status = firstStatus
                        }
                        record.responseStatus = status
                        record.returnedAt = System.nanoTime()
                        return if (status == 200) json("""{"ok":true}""")
                        else json("""{"detail":{"ru":"QA временный отказ","ba":"QA ваҡытлыса баш тартыу"}}""", status)
                    }
                    return when (path) {
                        "/me" -> json("""{"id":1,"name":"QA_SYNTH_CALLBACK","role":"passenger"}""")
                        "/version/min" -> json("""{"min_version_code":0,"latest_version_code":0}""")
                        "/rides", "/bookings/mine", "/requests/mine", "/trusted-contacts", "/contacts", "/ads", "/places/saved" ->
                            json("""{"items":[]}""")
                        "/me/update", "/auth/logout", "/push/register", "/push/unregister" -> json("{}")
                        else -> json("{}", 404)
                    }
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        // Equal to the product's 15s timeout; the bounded 10s gate detects a fixture timeout.
        ApiClient.testTimeoutMs = 15_000
        ApiClient.testTrace = { if (it.contains("/callback")) traces.add(it) }
        ApiClient.init(context)
        ApiClient.logout()
        ApiClient.init(context)
        ApiClient.saveToken(accessToken)
        ApiClient.saveName("QA_SYNTH_CALLBACK")
        ApiClient.saveRole("passenger")
        assertTrue("Request-profile calibration: configured support phone selects ACTION_DIAL instead of POST /callback",
            BuildConfig.YULDASH_SUPPORT_PHONE.isBlank())
    }

    @After fun cleanup() {
        releaseHeldResponse()
        if (contentSet) {
            compose.runOnIdle { mounted.value = false }
            compose.waitForIdle()
        }
        owner.viewModelStore.clear()
        ApiClient.logout()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        ApiClient.testTrace = null
        if (::server.isInitialized) server.shutdown()
    }

    @Test fun pendingResponseDoesNotClaimThatCallbackWasRequested() {
        openCallbackFromSimpleMode(AppLanguage.Ru, "QA_SYNTH_PENDING: помоги найти поездку")
        callbackButton().performClick()
        awaitHeldRequest()
        receipt("pending-before-response")
        assertFalse("The request is still held by the HTTP dispatcher", vm.callbackRequested.value)
        assertNoConfirmation()
        assertEquals(1, callbacks.size)
        assertEquals(0L, callbacks.single().returnedAt)
        releaseHeldResponse()
        awaitConfirmation()
        assertAcceptedRequests(1)
    }

    @Test fun bashkirDraftSurvives503AndRetryConfirmsOnlyTheAcceptedResponse() {
        firstStatus = 503
        val draft = "QA_SYNTH_RETRY: Өфөгә сәфәр табырға ярҙам ит, иртәгә 09:30"
        openCallbackFromSimpleMode(AppLanguage.Ba, draft)
        callbackButton().performClick()
        awaitHeldRequest()
        // Continue through the real rejection even if the pre-response state is defective.
        receipt("retry-held")
        releaseHeldResponse()
        awaitFailure()
        receipt("retry-after-503")
        assertFalse(vm.callbackRequested.value)
        assertNoConfirmation()
        assertEquals(draft, currentDraft())
        callbackButton().assertIsEnabled().performClick()
        awaitConfirmation()
        receipt("retry-after-200")
        assertAcceptedRequests(2)
        assertEquals(listOf(503, 200), callbacks.map { it.responseStatus })
        callbacks.forEach { assertEquals(draft, JSONObject(it.body).getString("note")) }
        assertTrue("Retry must follow the actual first HTTP response", callbacks[1].receivedAt > callbacks[0].returnedAt)
    }

    @Test fun secondPhysicalTapDuringPendingRequestDoesNotCreateAnotherPost() {
        firstStatus = 503
        val draft = "QA_SYNTH_DOUBLE: перезвони по поводу заявки"
        openCallbackFromSimpleMode(AppLanguage.Ru, draft)
        callbackButton().performTouchInput { click() }
        awaitHeldRequest()
        // A real second pointer tap while POST #1 is held, not invocation of a disabled callback.
        callbackButton().performScrollTo().performTouchInput { click() }
        settle()
        receipt("double-tap-held")
        callbackButton().assertIsNotEnabled()
        assertFalse(vm.callbackRequested.value)
        assertNoConfirmation()
        assertEquals("One logical submission while HTTP #1 has not returned", 1, callbacks.size)
        releaseHeldResponse()
        awaitFailure()
        receipt("double-tap-after-failure")
        assertEquals("The second tap must not become a delayed duplicate", 1, callbacks.size)
        assertEquals(draft, currentDraft())
        assertNoConfirmation()
        callbackButton().assertIsEnabled().performClick()
        awaitConfirmation()
        receipt("double-tap-retry-success")
        assertAcceptedRequests(2)
        assertEquals(listOf(503, 200), callbacks.map { it.responseStatus })
        callbacks.forEach { assertEquals(draft, JSONObject(it.body).getString("note")) }
        assertTrue(callbacks[1].receivedAt > callbacks[0].returnedAt)
    }

    private fun openCallbackFromSimpleMode(lang: AppLanguage, draft: String) {
        language = lang
        vm = ViewModelProvider(owner, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
                YuldashViewModel(SavedStateHandle(mapOf("yuldash_screen" to Screen.SimpleMode.name,
                    "yuldash_lang" to lang.name))) as T
        })[YuldashViewModel::class.java]
        contentSet = true
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                YuldashTheme {
                    // Only an unexpected Home is a sentinel to avoid native MapKit on the JVM.
                    // Both the starting SimpleMode and the expected CallbackHelp remain real.
                    if (vm.screen.value == Screen.Home) Text("Unexpected home destination") else YuldashApp()
                }
            }
        }
        val entry = if (lang == AppLanguage.Ba) "Миңә шылтырат" else "Позвони мне"
        awaitText(entry)
        assertEquals(Screen.SimpleMode, vm.screen.value)
        assertTrue(ApiClient.isLoggedIn())
        compose.onNodeWithText(entry).performScrollTo().performClick()
        awaitText(if (lang == AppLanguage.Ba) "Шылтыратыу ярҙамы" else "Помощь звонком")
        assertEquals(Screen.CallbackHelp, vm.screen.value)
        assertFalse(vm.callbackRequested.value)
        assertEquals(0, callbacks.size)
        assertNoConfirmation()
        compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement(draft)
        assertEquals(draft, currentDraft())
        callbackButton().performScrollTo().assertIsEnabled()
    }

    private fun callbackButton() = compose.onNodeWithTag("callback_btn")
    private fun confirmationTitle() = if (language == AppLanguage.Ba) "Шылтыратыу һоралды" else "Звонок запрошен"
    private fun successToast() = if (language == AppLanguage.Ba) "Шылтыратыу заявкаһы булдырылды" else "Заявка на звонок создана"
    private fun failureToast() = if (language == AppLanguage.Ba) "Булманы. Сетте тикшереп ҡабатла" else "Не получилось. Проверь сеть и повтори"
    private fun currentDraft() = compose.onNode(hasSetTextAction()).fetchSemanticsNode().config[SemanticsProperties.EditableText].text
    private fun assertNoConfirmation() = compose.onNodeWithText(confirmationTitle()).assertDoesNotExist()

    private fun awaitHeldRequest() = compose.waitUntil(15_000) {
        settle()
        callbackButton().fetchSemanticsNode()
        firstEntered.count == 0L
    }

    private fun awaitFailure() {
        compose.waitUntil(15_000) {
            settle()
            callbackButton().fetchSemanticsNode()
            callbacks.firstOrNull()?.responseStatus == 503 && ShadowToast.getTextOfLatestToast() == failureToast()
        }
        assertFalse("Dispatcher deadline is a fixture failure, not the requested HTTP 503", gateTimedOut.get())
    }

    private fun awaitConfirmation() {
        compose.waitUntil(15_000) {
            settle()
            compose.onAllNodesWithText(confirmationTitle()).fetchSemanticsNodes().isNotEmpty() &&
                ShadowToast.getTextOfLatestToast() == successToast()
        }
        compose.onNodeWithText(confirmationTitle()).performScrollTo().assertIsDisplayed()
        assertTrue(vm.callbackRequested.value)
        assertFalse(gateTimedOut.get())
    }

    private fun assertAcceptedRequests(count: Int) {
        assertEquals(count, callbacks.size)
        assertTrue(callbacks.all { it.correctAuthorization && it.returnedAt > it.receivedAt })
        assertTrue("The explicit release precedes the first response", releasedAt.get() in
            (callbacks.first().receivedAt + 1)..callbacks.first().returnedAt)
        assertEquals(200, callbacks.last().responseStatus)
    }

    private fun releaseHeldResponse() {
        releasedAt.compareAndSet(0, System.nanoTime())
        releaseFirst.countDown()
    }

    private fun receipt(stage: String) {
        val button = callbackButton().fetchSemanticsNode()
        val records = JSONArray()
        callbacks.forEach { records.put(JSONObject().put("ordinal", it.ordinal)
            .put("body", JSONObject(it.body)).put("authorizationMatchesSyntheticSession", it.correctAuthorization)
            .put("receivedAtNanos", it.receivedAt).put("returnedAtNanos", it.returnedAt).put("status", it.responseStatus)) }
        println("AUDIT_CALLBACK_UI " + JSONObject().put("stage", stage).put("language", language.name)
            .put("screen", vm.screen.value.name).put("requested", vm.callbackRequested.value)
            .put("confirmationNodes", compose.onAllNodesWithText(confirmationTitle()).fetchSemanticsNodes().size)
            .put("buttonDisabled", button.config.contains(SemanticsProperties.Disabled))
            .put("gateTimedOut", gateTimedOut.get()).put("releasedAtNanos", releasedAt.get())
            .put("requests", records).put("allPaths", JSONArray(paths)).put("callbackTrace", JSONArray(traces)))
    }

    private fun settle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        Snapshot.sendApplyNotifications()
    }
    private fun awaitText(text: String) = compose.waitUntil(15_000) {
        settle(); compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
    private fun json(body: String, status: Int = 200) = MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "application/json").setBody(body)
}
