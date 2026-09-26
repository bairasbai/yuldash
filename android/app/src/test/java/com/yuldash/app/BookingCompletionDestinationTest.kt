package com.yuldash.app

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
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
    @get:Rule val compose = createComposeRule()
    private val owner = object : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }
    private lateinit var server: MockWebServer
    private lateinit var vm: YuldashViewModel
    private val mounted = mutableStateOf(true)
    private val ratings = Collections.synchronizedList(mutableListOf<String>())

    @Before fun setUp() {
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
                        "/bookings/42/details" -> "{}"
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
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 300
        ApiClient.init(context)
        // No account or real service is needed to exercise the startup navigation race.
        ApiClient.saveToken("")
        CanonMotion.enabled = true
        vm = ViewModelProvider(owner, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                YuldashViewModel(SavedStateHandle()) as T
        })[YuldashViewModel::class.java]
        DeepLink.pendingCompletedBookingId.value = 42
    }

    @After fun tearDown() {
        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
        DeepLink.pendingCompletedBookingId.value = null
        DeepLink.pendingRideId.value = null
        owner.viewModelStore.clear()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server.shutdown()
    }

    @Test fun completedBookingDestinationSurvivesSplashAndUnfinishedOnboarding() {
        compose.mainClock.autoAdvance = false
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
            ApiClient.saveToken("local-completion-test")
            vm.screen.value = Screen.Login
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(Screen.ActiveTrip, vm.screen.value)
            assertEquals(42, vm.activeBookingId.value)
            assertEquals(null, DeepLink.pendingCompletedBookingId.value)
        }
        // Follow the real ActiveTripScreen -> RideshareCompletedScreen, with all data
        // loaded through ApiClient from a local HTTP fixture (no injected UI callbacks).
        compose.mainClock.autoAdvance = true
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("rideshareCompletedHero").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("rideshareCompletedHero").assertIsDisplayed()
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
        ApiClient.saveToken("local-restore-test")
        owner.viewModelStore.clear()
        vm = ViewModelProvider(owner, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                YuldashViewModel(SavedStateHandle(mapOf(
                    "yuldash_screen" to Screen.ActiveTrip.name,
                    "yuldash_active_bid" to 42,
                ))) as T
        })[YuldashViewModel::class.java]
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
        DeepLink.pendingRideId.value = 42
        compose.mainClock.autoAdvance = false
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
                DeepLink.pendingRideId.value)
        }
        compose.runOnIdle {
            ApiClient.saveToken("local-ride-test")
            vm.screen.value = Screen.Login
        }
        compose.mainClock.autoAdvance = true
        compose.waitUntil(timeoutMillis = 10_000) {
            vm.screen.value == Screen.Booking
        }
        compose.runOnIdle {
            assertEquals(Screen.Booking, vm.screen.value)
            assertEquals("42", vm.selectedRide.value?.id)
            assertEquals(null, DeepLink.pendingRideId.value)
        }
    }
}
