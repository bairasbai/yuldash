package com.yuldash.app

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.TripPassAuthorityFixture
import com.yuldash.app.data.awaitOfflineWriteSnapshotReset02
import com.yuldash.app.data.ChatSocket
import com.yuldash.app.data.SnapshotWiringCleanupGuard
import com.yuldash.app.data.finishOfflineWriteFixture02
import com.yuldash.app.data.poisonOfflinePassportFixture03
import kotlinx.coroutines.Job
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import java.net.InetAddress
import com.yuldash.app.ui.theme.YuldashTheme
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Collections

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookingCompletionDestinationTest {
    companion object { private val cleanupGuard = SnapshotWiringCleanupGuard() }
    private val requestJobs = RequestJobs()
    private var setupEntered = false
    private var serverStarted = false
    private var sessionInstalled = false
    private var contentSet = false
    private val ownTrace: (String) -> Unit = {
        if (it.startsWith("вошли в вызов ")) requestJobs.record()
    }
    @get:Rule val compose = createComposeRule(effectContext = requestJobs)
    private val owner = object : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }
    private lateinit var server: MockWebServer
    private lateinit var vm: YuldashViewModel
    private val mounted = mutableStateOf(true)
    private val ratings = Collections.synchronizedList(mutableListOf<String>())

    @Before fun setUp() {
        cleanupGuard.requireHealthy()
        check(ApiClient.testTrace == null)
        setupEntered = true
        val context: Context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("yuldash_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("onboarding_completed", false).commit()
        ApiClient.resetForTest()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/rides/42") {
                        return MockResponse().setHeader("Content-Type", "application/json")
                            .setBodyDelay(200, java.util.concurrent.TimeUnit.MILLISECONDS)
                            .setBody("""{"id":42,"from_city":"Тестовый пункт А","to_city":"Тестовый пункт Б","depart_at":"2026-12-01T10:00:00Z","seats_total":3,"seats_left":2,"price":500,"category":"ride","driver_id":7,"driver_name":"Тестовый водитель"}""")
                    }
                    val body = when (request.path) {
                        "/bookings/mine" -> """{"items":[{"id":42,"ride_id":7,"status":"done","seats":1,"from_city":"Тестовый пункт А","to_city":"Тестовый пункт Б","price":500,"driver_name":"Тестовый водитель"}]}"""
                        "/bookings/42/role" -> """{"role":"passenger","status":"done","driver_phase":""}"""
                        "/bookings/42/messages" -> """{"items":[]}"""
                        "/bookings/42/boarding-code" -> """{"code":""}"""
                        "/bookings/42/details" -> """{"booking_id":42,"ride_id":7,"role":"passenger","status":"done","from_city":"Тестовый пункт А","to_city":"Тестовый пункт Б"}"""
                        "/trips/42/receipt" -> """{"booking_id":42,"ride_id":7,"role":"passenger","from_city":"Уфа","to_city":"Казань","amount":500,"pay_method":"cash","my_stars":0,"counterparty_name":"Тестовый водитель"}"""
                        "/bookings/42/tip" -> """{"already_thanked":false}"""
                        "/bookings/42/rate" -> {
                            if (request.method != "POST") return MockResponse().setResponseCode(405)
                            ratings += request.body.readUtf8()
                            "{}"
                        }
                        else -> return MockResponse().setResponseCode(503).setBody("{}")
                    }
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
                }
            }
            start(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0)
        }
        serverStarted = true
        ApiClient.testBaseUrl = server.url("/").newBuilder().host("127.0.0.1").build().toString().trimEnd('/')
        ApiClient.testTrace = ownTrace
        // Ни один запрос здесь не должен упираться в таймаут: сервер местный и отвечает сразу
        // (`/rides/42` — через 200 мс). Прежние 300 мс — впритык: на медленном раннере GitHub
        // ответ о роли в поездке мог не успеть, и бронь вместо открытия уходила в ошибку.
        ApiClient.testTimeoutMs = 5_000
        ApiClient.init(context)
        awaitOfflineWriteSnapshotReset02()
        // A parseable synthetic owner exercises the actual startup navigation and authority.
        ApiClient.saveToken("")
        sessionInstalled = true
        CanonMotion.enabled = true
        vm = ViewModelProvider(owner, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                YuldashViewModel(SavedStateHandle()) as T
        })[YuldashViewModel::class.java]
        DeepLink.pendingCompletedBookingId.value = 42
    }

    @After fun tearDown() {
        try {
            cleanupGuard.requireHealthy()
            if (!setupEntered) return
            if (contentSet) {
                compose.runOnIdle { mounted.value = false }
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                compose.waitForIdle()
            }
            DeepLink.pendingCompletedBookingId.value = null
            owner.viewModelStore.clear()
            requestJobs.awaitCompleted(if (serverStarted) server.port else null) { compose.mainClock.advanceTimeByFrame() }
            if (sessionInstalled) ApiClient.logout()
            finishOfflineWriteFixture02(cleanupGuard) {
                check(ApiClient.testTrace == null || ApiClient.testTrace === ownTrace)
                if (serverStarted) server.shutdown()
                ApiClient.resetForTest()
                ApiClient.testTimeoutMs = null
            }
        } catch (failure: Throwable) {
            poisonOfflinePassportFixture03(cleanupGuard, failure)
        } finally {
            if (ApiClient.testTrace === ownTrace) ApiClient.testTrace = null
        }
    }

    /** Ручные часы: каждый шаг доставляет изменения состояния и кадр, пока не наступит событие. */
    private fun awaitManualFrames(check: () -> Boolean) = compose.waitUntil(timeoutMillis = 10_000) {
        Snapshot.sendApplyNotifications(); compose.mainClock.advanceTimeBy(100)
        Shadows.shadowOf(Looper.getMainLooper()).idle(); check()
    }

    @Test fun completedBookingDestinationSurvivesSplashAndUnfinishedOnboarding() {
        compose.mainClock.autoAdvance = false
        contentSet = true
        compose.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                YuldashTheme { if (mounted.value) YuldashApp() }
            }
        }
        // Run the real Splash delay and AnimatedContent transition. The pending destination
        // must survive until startup finishes; these assertions do not duplicate its logic.
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue("Completed booking destination was consumed before startup finished; screen=${vm.screen.value}",
                DeepLink.pendingCompletedBookingId.value == 42)
            assertTrue("Startup was unexpectedly bypassed: ${vm.screen.value}",
                vm.screen.value == Screen.Intro || vm.screen.value == Screen.Onboarding)
        }
        // Finish startup without going through native MapKit: the destination must now open.
        compose.runOnIdle {
            ApiClient.saveToken(TripPassAuthorityFixture.jwt(11))
            vm.screen.value = Screen.Login
        }
        // Бронь открывается только после ответа сервера о роли (настоящий HTTP на местный
        // сервер). Ждём сам переход, а не секунду времени: секунды хватало быстрой машине,
        // но не раннеру GitHub.
        awaitManualFrames { vm.screen.value == Screen.ActiveTrip }
        compose.runOnIdle {
            assertEquals(Screen.ActiveTrip, vm.screen.value)
            assertEquals(42, vm.activeBookingId.value)
            assertEquals(null, DeepLink.pendingCompletedBookingId.value)
        }
        // Follow the real ActiveTripScreen -> RideshareCompletedScreen, with all data
        // loaded through ApiClient from a local HTTP fixture (no injected UI callbacks).
        compose.mainClock.autoAdvance = true
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("rideshareCompletedHero").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText("Уфа  →  Казань").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("rideshareCompletedHero").assertIsDisplayed()
        compose.onNodeWithText("Уфа  →  Казань").assertIsDisplayed()
        compose.onNodeWithTag("rideshareCompletedList")
            .performScrollToNode(hasTestTag("rideshareStar4"))
        compose.onNodeWithTag("rideshareStar4").assertIsDisplayed().performClick()
        compose.onNodeWithTag("rideshareRatingSubmit").assertIsDisplayed().performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Готово").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Готово").assertIsDisplayed()
        assertEquals(listOf("{\"stars\":4}"), synchronized(ratings) { ratings.toList() })
    }

    @Test fun restoredCompletedBookingDoesNotReactivateLiveTrip() {
        DeepLink.pendingCompletedBookingId.value = null
        ApiClient.saveToken(TripPassAuthorityFixture.jwt(11))
        owner.viewModelStore.clear()
        vm = ViewModelProvider(owner, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                YuldashViewModel(SavedStateHandle(mapOf(
                    "yuldash_screen" to Screen.ActiveTrip.name,
                    "yuldash_active_bid" to 42,
                ))) as T
        })[YuldashViewModel::class.java]
        contentSet = true
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                YuldashTheme { YuldashApp() }
            }
        }
        compose.waitUntil(10_000) { vm.selectedRide.value != null }
        compose.runOnIdle {
            assertEquals(Screen.ActiveTrip, vm.screen.value)
            assertEquals(42, vm.activeBookingId.value)
            assertEquals("Completed booking must not enable live location after restoration", null, vm.activeTrip.value)
        }
        // Поездка приходит раньше, чем экран дорисовывает итог: на быстрой машине плашка уже
        // стоит, в CI её ещё не было. Ждём сам итог, а не косвенный признак.
        compose.waitUntil(10_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithTag("rideshareCompletedHero").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("rideshareCompletedHero").assertExists()
    }

    @Test fun rideDestinationIsNotConsumedBeforeStartupFinishes() {
        DeepLink.pendingCompletedBookingId.value = null
        vm.requestPublicRide(42)
        compose.mainClock.autoAdvance = false
        contentSet = true
        compose.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                YuldashTheme { if (mounted.value) YuldashApp() }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue("Startup was unexpectedly bypassed: ${vm.screen.value}",
                vm.screen.value == Screen.Intro || vm.screen.value == Screen.Onboarding)
            assertEquals("Ride destination was consumed before startup finished", 42,
                vm.pendingPublicRideNavigation.value?.rideId)
        }
        compose.runOnIdle {
            ApiClient.saveToken(TripPassAuthorityFixture.jwt(11))
            vm.screen.value = Screen.Login
        }
        compose.mainClock.autoAdvance = true
        compose.waitUntil(timeoutMillis = 10_000) {
            vm.screen.value == Screen.Booking
        }
        compose.runOnIdle {
            assertEquals(Screen.Booking, vm.screen.value)
            assertEquals("42", vm.selectedRide.value?.id)
            assertEquals(null, vm.pendingPublicRideNavigation.value?.rideId)
        }
    }

    /** Observes actual API callers; never creates a Job or replaces a dispatcher. */
    private class RequestJobs : ThreadContextElement<Job?>, AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<RequestJobs>
        private val current = ThreadLocal<Job?>()
        private val started = java.util.concurrent.CopyOnWriteArrayList<Job>()
        @Volatile private var missingParent = false

        override fun updateThreadContext(context: CoroutineContext): Job? =
            current.get().also { current.set(context[Job]) }

        override fun restoreThreadContext(context: CoroutineContext, oldState: Job?) {
            current.set(oldState)
        }

        fun record() {
            current.get()?.let { job ->
                started += job
                val caller = job.parent
                if (caller == null) missingParent = true else started += caller
            }
        }

        fun awaitCompleted(port: Int?, advanceFrame: () -> Unit) = runBlocking {
            withTimeout(5_000) {
                check(!missingParent) { "Owned caller completion unavailable" }
                val field = ChatSocket::class.java.getDeclaredField("client\$delegate")
                    .apply { isAccessible = true }
                val lazyClient = field.get(null) as Lazy<*>
                val client = lazyClient.takeIf { it.isInitialized() }?.value as? okhttp3.OkHttpClient
                while (started.any { !it.isCompleted } || client?.dispatcher?.let {
                        it.runningCalls() + it.queuedCalls()
                    }.orEmpty().any { it.request().url.host == "127.0.0.1" && it.request().url.port == port }) {
                    Snapshot.sendApplyNotifications()
                    advanceFrame()
                    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                    delay(10)
                }
            }
        }
    }
}
