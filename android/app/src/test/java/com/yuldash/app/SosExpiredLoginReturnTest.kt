package com.yuldash.app

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.SnapshotWiringCleanupGuard
import com.yuldash.app.data.awaitOfflineWriteSnapshotReset02
import com.yuldash.app.data.finishOfflineWriteFixture02
import com.yuldash.app.data.poisonOfflinePassportFixture03
import kotlinx.coroutines.Job
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import com.yuldash.app.ui.theme.YuldashTheme
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Приёмка #192 (P3): вход истёк, и сервер ответил на SOS 401 — сигнал не принят, приложение ведёт ко
 * входу. Вошёл ТОТ ЖЕ человек — возвращаем его на экран SOS (без автоотправки: нажать ещё раз).
 * Вошёл другой аккаунт — он попадает к себе, чужой SOS ему не открываем (ExpiredSessionBackHistoryTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SosExpiredLoginReturnTest {
    companion object { private val cleanupGuard = SnapshotWiringCleanupGuard() }
    private val requestJobs = RequestJobs()
    @get:Rule val compose = createComposeRule(effectContext = requestJobs)
    private val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
    private val mounted = mutableStateOf(true)
    private val sosRequests = AtomicInteger()
    private lateinit var vm: YuldashViewModel
    private lateinit var server: MockWebServer
    private val user11 = "header.eyJzdWIiOiIxMSJ9.signature"
    private val user11Again = "header.eyJzdWIiOiIxMSJ9.fresh-signature"
    private val user33 = "header.eyJzdWIiOiIzMyJ9.signature"
    private val unknownToken = "synthetic-opaque-sos-replacement"
    private var setupEntered = false
    private var apiInitAttempted = false
    private var traceInstalled = false
    private var ownBase: String? = null
    private val heldPost = CountDownLatch(1)
    private val releasePost = CountDownLatch(1)
    private val expectedAuth = AtomicBoolean()
    private val refreshRequests = AtomicInteger()
    private val committedFrame = AtomicReference<Pair<Screen, Long>?>()
    @Volatile private var holdSos = false
    @Volatile private var unknownSos = false
    private val ownTrace: (String) -> Unit = { line ->
        // Retain jobs only; never retain or print the API text, path or request.
        if (line.startsWith("вошли в вызов ")) runCatching {
            requestJobs.record(line == "вошли в вызов POST /sos", line == "вошли в вызов POST /me/update")
        }.onFailure { requestJobs.unavailable = true }
    }

    @Before fun prepare() {
        cleanupGuard.requireHealthy()
        check(ApiClient.testTrace == null)
        setupEntered = true
        val context: Context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("yuldash_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("onboarding_completed", true).remove("preferred_role").commit()
        ApiClient.resetForTest()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/sos" && request.method == "POST") {
                        sosRequests.incrementAndGet()
                        if (holdSos) {
                            expectedAuth.set(request.getHeader("Authorization") ==
                                "Bearer " + (if (unknownSos) unknownToken else user11))
                            heldPost.countDown()
                            check(releasePost.await(5, TimeUnit.SECONDS))
                        }
                        return MockResponse().setResponseCode(401).setBody("{}")
                    }
                    if (request.path == "/auth/refresh") refreshRequests.incrementAndGet()
                    return MockResponse().setResponseCode(503).setBody("{}")
                }
            }
        }
        server.start()
        ownBase = server.url("/").toString().trimEnd('/')
        ApiClient.testBaseUrl = ownBase
        ApiClient.testTimeoutMs = 1_000
        apiInitAttempted = true
        ApiClient.init(context)
        ApiClient.saveToken(user11)
        vm = ViewModelProvider(owner, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                YuldashViewModel(SavedStateHandle(mapOf("yuldash_screen" to Screen.Sos.name))) as T
        })[YuldashViewModel::class.java]
        ApiClient.testTrace = ownTrace
        traceInstalled = true
    }

    @After fun cleanup() {
        try {
            cleanupGuard.requireHealthy()
            if (!setupEntered) return
            releasePost.countDown()
            compose.runOnIdle { mounted.value = false }
            compose.waitForIdle()
            owner.viewModelStore.clear()
            requestJobs.awaitCompleted()
            cleanupGuard.requireHealthy()
            check(ownBase == null || ApiClient.testBaseUrl == ownBase)
            check(if (traceInstalled) ApiClient.testTrace === ownTrace else ApiClient.testTrace == null)
            if (apiInitAttempted) ApiClient.logout()
            finishOfflineWriteFixture02(cleanupGuard) {
                if (apiInitAttempted) awaitOfflineWriteSnapshotReset02()
                cleanupGuard.requireHealthy() // Also after background/reset drain, before origin shutdown.
                check(ownBase == null || ApiClient.testBaseUrl == ownBase)
                check(if (traceInstalled) ApiClient.testTrace === ownTrace else ApiClient.testTrace == null)
                if (::server.isInitialized) server.shutdown()
                ApiClient.resetForTest()
                ApiClient.testTimeoutMs = null
            }
        } catch (failure: Throwable) {
            poisonOfflinePassportFixture03(cleanupGuard, failure)
        } finally {
            releasePost.countDown()
            if (ApiClient.testTrace === ownTrace) ApiClient.testTrace = null
        }
    }

    /** Observe inherited real jobs; never install a Job/dispatcher or cancel a caller. */
    private class RequestJobs : ThreadContextElement<Job?>, AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<RequestJobs>
        private val current = ThreadLocal<Job?>()
        private val reads = CopyOnWriteArrayList<Pair<Job, Job>>()
        @Volatile var unavailable = false
        @Volatile var sos: Pair<Job, Job>? = null
        @Volatile var sosGeneration = Long.MIN_VALUE
        override fun updateThreadContext(context: CoroutineContext): Job? = current.get().also { current.set(context[Job]) }
        override fun restoreThreadContext(context: CoroutineContext, oldState: Job?) { current.set(oldState) }
        @Synchronized fun record(isSos: Boolean, knownBackground: Boolean) {
            val transport = current.get() ?: run { if (!knownBackground) unavailable = true; return }
            val caller = transport.parent ?: run { unavailable = true; return }
            if (reads.size >= 64) { unavailable = true; return }
            reads += transport to caller
            if (isSos) { check(sos == null); sos = transport to caller; sosGeneration = ApiClient.queueSessionGeneration() }
        }
        fun sosCompleted(): Boolean = !unavailable && sos?.let { it.first.isCompleted && it.second.isCompleted } == true
        fun awaitCompleted() = runBlocking { withTimeout(5_000) {
            check(!unavailable) { "Owned API caller completion unavailable" }
            while (reads.any { !it.first.isCompleted || !it.second.isCompleted }) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                delay(10)
            }
            check(!unavailable)
        } }
    }

    private fun awaitState(condition: () -> Boolean) {
        try {
            compose.waitUntil(10_000) {
                compose.onAllNodes(isRoot()).fetchSemanticsNodes()
                condition()
            }
        } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            runCatching {
                val type = ApiClient::class.java
                val cachedUser = type.getDeclaredField("cachedUserId").apply { isAccessible = true }.get(ApiClient) as? Int
                val cachedFor = type.getDeclaredField("cachedUserIdForToken").apply { isAccessible = true }.get(ApiClient)
                println("REMAINING_AUTH_DIAG01 phase=sos-await-timeout" +
                    " screenSos=" + (vm.screen.value == Screen.Sos) +
                    " screenLogin=" + (vm.screen.value == Screen.Login) +
                    " screenNotifications=" + (vm.screen.value == Screen.Notifications) +
                    " loggedIn=" + ApiClient.isLoggedIn() +
                    " currentSessionValid=" + ApiClient.isCurrentSession(ApiClient.queueSessionGeneration()) +
                    " sessionExpired=" + ApiClient.sessionExpired.value +
                    " cachedUserIs11=" + (cachedUser == 11) +
                    " cachedUserIs33=" + (cachedUser == 33) +
                    " cachedForCurrentToken=" + (cachedFor == ApiClient.currentToken()) +
                    " historyHasSos=" + (Screen.Sos in vm.navHistory) +
                    " historyHasLogin=" + (Screen.Login in vm.navHistory) +
                    " sosRequestCount=" + sosRequests.get())
                com.yuldash.app.data.printAuthResetDiagnostic01("remaining-sos-await-failed")
            }.onFailure { diagnosticFailure ->
                runCatching { println("REMAINING_AUTH_DIAG01 phase=sos-await-timeout diagnosticUnavailable=" + diagnosticFailure.javaClass.simpleName) }
            }
            throw failure
        }
    }

    /** SOS с истёкшим входом → 401 → экран входа; затем входит [nextToken] и открывается другой экран. */
    private fun expireOnSosThenLoginAs(nextToken: String) {
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                YuldashTheme { YuldashApp() }
            }
        }
        val label = "Сообщить поддержке"
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(label))
        compose.onNode(hasText(label) and hasClickAction()).assertIsDisplayed().performClick()
        awaitState { vm.screen.value == Screen.Login }
        compose.runOnIdle { assertFalse(ApiClient.isLoggedIn()) }
        compose.runOnIdle {
            ApiClient.saveToken(nextToken)
            vm.screen.value = Screen.Notifications   // любой экран после входа (как в SosLoginReturnTest)
        }
    }

    private fun mountControl(): Long {
        ApiClient.testTimeoutMs = 5_000 // New held controls only; originals keep their 1_000ms HTTP cap.
        holdSos = true
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                val generation by ApiClient.sessionChanges.collectAsState()
                val frame = vm.screen.value to generation
                SideEffect { committedFrame.set(frame) } // Requested root frame, never an outgoing animation.
                YuldashTheme { YuldashApp() }
            }
        }
        val generation = ApiClient.queueSessionGeneration()
        awaitState { committedFrame.get() == (Screen.Sos to generation) }
        compose.runOnIdle { assertTrue(ApiClient.myUserId() == 11) }
        return generation
    }

    private fun sendAndAwaitHeldPost(generation: Long) {
        val label = "Сообщить поддержке"
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(label))
        compose.onNode(hasText(label) and hasClickAction()).assertIsDisplayed().performClick()
        awaitState { heldPost.count == 0L }
        assertTrue("POST reached the owned local server with current auth", expectedAuth.get())
        assertEquals(generation, requestJobs.sosGeneration)
        assertEquals(1, sosRequests.get())
    }

    private fun finishExpiredControlAndLogin() {
        releasePost.countDown()
        awaitState { requestJobs.sosCompleted() && vm.screen.value == Screen.Login && !ApiClient.sessionExpired.value }
        compose.runOnIdle {
            assertFalse(ApiClient.isLoggedIn())
            assertEquals(1, sosRequests.get())
            assertEquals(0, refreshRequests.get())
            assertEquals(requestJobs.sosGeneration + 1, ApiClient.queueSessionGeneration())
            ApiClient.saveToken(user11Again)
            vm.screen.value = Screen.Notifications
        }
        compose.mainClock.advanceTimeBy(2_000)
        compose.onAllNodes(isRoot()).fetchSemanticsNodes()
        compose.runOnIdle {
            assertEquals(Screen.Notifications, vm.screen.value)
            assertFalse(Screen.Sos in vm.navHistory)
        }
        assertEquals("Only the one explicit POST; no replay after fresh login", 1, sosRequests.get())
    }

    @Test fun leavingCommittedSosBeforeIts401DoesNotReturnAfterLogin() {
        try {
            val generation = mountControl()
            sendAndAwaitHeldPost(generation)
            compose.runOnIdle { vm.screen.value = Screen.Notifications }
            awaitState { committedFrame.get() == (Screen.Notifications to generation) }
            assertEquals(Screen.Notifications, vm.screen.value)
            assertEquals(generation, ApiClient.queueSessionGeneration())
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
            assertEquals(1, sosRequests.get()) // Count remains monotonic across leaving and login.
            finishExpiredControlAndLogin()
        } finally { releasePost.countDown() }
    }

    @Test fun unknownReplacementSosCannotAdoptThePreviousAccountReturn() {
        try {
            val oldGeneration = mountControl()
            compose.runOnIdle { ApiClient.saveToken(unknownToken) }
            val replacement = ApiClient.queueSessionGeneration()
            assertTrue(replacement != oldGeneration)
            awaitState { committedFrame.get() == (Screen.Sos to replacement) }
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
            compose.runOnIdle {
                assertTrue(ApiClient.isLoggedIn())
                assertTrue(ApiClient.isCurrentSession(replacement))
                assertTrue(ApiClient.myUserId() == null)
                assertEquals(Screen.Sos, vm.screen.value)
            }
            assertEquals("Replacement happened before any SOS POST", 0, sosRequests.get())
            unknownSos = true
            sendAndAwaitHeldPost(replacement)
            finishExpiredControlAndLogin()
        } finally { releasePost.countDown() }
    }

    @Test fun samePersonReturnsToSosWithoutAutomaticSend() {
        expireOnSosThenLoginAs(user11Again)
        awaitState { vm.screen.value == Screen.Sos }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onAllNodes(isRoot()).fetchSemanticsNodes()
        assertEquals("Возврат на SOS не отправляет сигнал сам", 1, sosRequests.get())
    }

    @Test fun anotherAccountStaysWhereLoginTookIt() {
        expireOnSosThenLoginAs(user33)
        compose.mainClock.advanceTimeBy(2_000)
        compose.onAllNodes(isRoot()).fetchSemanticsNodes()
        compose.runOnIdle {
            assertEquals("Чужой аккаунт не попадает в SOS прежнего", Screen.Notifications, vm.screen.value)
            assertFalse(Screen.Sos in vm.navHistory)
        }
        assertEquals(1, sosRequests.get())
    }
}
