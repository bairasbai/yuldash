package com.yuldash.app

import android.app.Application
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookingLinkLoginTest {
    @get:Rule val compose = createComposeRule()
    private val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
    private val mounted = mutableStateOf(true)
    private val roleCode = AtomicInteger(200)
    private val roleTokens = CopyOnWriteArrayList<String>()
    private val firstRequestStarted = CountDownLatch(1)
    private val releaseFirstResponse = CountDownLatch(1)
    @Volatile private var holdFirstResponse = false
    @Volatile private var firstResponseCode = 200
    @Volatile private var tripStatus = "done"
    private lateinit var vm: YuldashViewModel
    private lateinit var server: MockWebServer

    @Before fun prepare() {
        val context: Context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("yuldash_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("onboarding_completed", false).commit()
        ApiClient.resetForTest()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/bookings/42/role") {
                        roleTokens.add(request.getHeader("Authorization").orEmpty())
                        val first = roleTokens.size == 1
                        val code = if (holdFirstResponse && first) firstResponseCode else roleCode.get()
                        if (first) {
                            firstRequestStarted.countDown()
                            if (holdFirstResponse && !releaseFirstResponse.await(10, TimeUnit.SECONDS)) {
                                return MockResponse().setResponseCode(504)
                            }
                        }
                        return MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json")
                            .setBody(if (code == 200) """{"role":"passenger","status":"$tripStatus","driver_phase":""}"""
                                else """{"detail":"Fixture rejection"}""")
                    }
                    val body = when (request.path) {
                        "/bookings/mine" -> """{"items":[{"id":42,"ride_id":7,"status":"$tripStatus","seats":1,"from_city":"Тест А","to_city":"Тест Б","price":500,"driver_name":"Тест"}]}"""
                        "/bookings/42/messages" -> """{"items":[]}"""
                        "/bookings/42/boarding-code" -> """{"code":""}"""
                        "/bookings/42/details" -> "{}"
                        "/trips/42/receipt" -> """{"booking_id":42,"ride_id":7,"role":"passenger","from_city":"Тест А","to_city":"Тест Б","amount":500,"pay_method":"cash","my_stars":0,"counterparty_name":"Тест"}"""
                        "/bookings/42/tip" -> """{"already_thanked":false}"""
                        "/notifications" -> """{"items":[]}"""
                        else -> return MockResponse().setResponseCode(503).setBody("{}")
                    }
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 15_000
        ApiClient.init(context)
        ApiClient.saveToken("")
        vm = ViewModelProvider(owner, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                YuldashViewModel(SavedStateHandle(mapOf("yuldash_screen" to Screen.Login.name))) as T
        })[YuldashViewModel::class.java]
        DeepLink.pendingBookingChatId.value = null
        DeepLink.pendingCompletedBookingId.value = null
    }

    @After fun cleanup() {
        releaseFirstResponse.countDown()
        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
        DeepLink.pendingBookingChatId.value = null
        DeepLink.pendingCompletedBookingId.value = null
        owner.viewModelStore.clear()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server.shutdown()
    }

    private fun mount(completed: Boolean) {
        if (completed) DeepLink.pendingCompletedBookingId.value = 42
        else DeepLink.pendingBookingChatId.value = 42
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                YuldashTheme { YuldashApp() }
            }
        }
        compose.onAllNodes(isRoot()).fetchSemanticsNodes()
        compose.runOnIdle {
            assertEquals(Screen.Login, vm.screen.value)
            assertNull("Anonymous link must not install a booking", vm.activeBookingId.value)
            assertEquals(42, if (completed) DeepLink.pendingCompletedBookingId.value else DeepLink.pendingBookingChatId.value)
        }
        assertTrue("Anonymous link must not query protected booking data", roleTokens.isEmpty())
    }

    private fun login(token: String = "local-account-A", screen: Screen = Screen.Notifications) {
        compose.runOnIdle { ApiClient.saveToken(token); vm.screen.value = screen }
    }

    private fun awaitState(predicate: () -> Boolean) {
        compose.waitUntil(10_000) {
            compose.onAllNodes(isRoot()).fetchSemanticsNodes()
            predicate()
        }
    }

    private fun awaitFailure() {
        awaitState { compose.onAllNodesWithTag("bookingLinkFailure").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("bookingLinkFailure").assertIsDisplayed()
        compose.runOnIdle { assertNull("Rejected booking must never become active", vm.activeBookingId.value) }
    }

    private fun authenticatedDestination(completed: Boolean) {
        tripStatus = if (completed) "done" else "confirmed"
        holdFirstResponse = true
        mount(completed)
        login()
        awaitState { firstRequestStarted.count == 0L }
        compose.runOnIdle {
            assertEquals("Navigation must wait for server authorization", Screen.Notifications, vm.screen.value)
            assertNull(vm.activeBookingId.value)
        }
        releaseFirstResponse.countDown()
        awaitState { vm.screen.value == if (completed) Screen.ActiveTrip else Screen.Booking }
        compose.runOnIdle {
            assertEquals(42, vm.activeBookingId.value)
            assertNull(if (completed) DeepLink.pendingCompletedBookingId.value else DeepLink.pendingBookingChatId.value)
        }
        assertEquals("Bearer local-account-A", roleTokens.first())
    }

    @Test fun completedBookingSurvivesLoginAndWaitsForServerAuthorization() = authenticatedDestination(true)

    @Test fun bookingChatSurvivesLoginAndWaitsForServerAuthorization() = authenticatedDestination(false)

    @Test fun forbiddenBookingDoesNotOpenAndClosingFailurePreservesScreen() {
        roleCode.set(403)
        mount(true)
        login()
        awaitFailure()
        compose.onNodeWithText("Поездка недоступна").assertIsDisplayed()
        compose.onNodeWithTag("bookingLinkRetry").assertDoesNotExist()
        compose.onNodeWithTag("bookingLinkClose").performClick()
        awaitState { compose.onAllNodesWithTag("bookingLinkFailure").fetchSemanticsNodes().isEmpty() }
        compose.runOnIdle { assertEquals(Screen.Notifications, vm.screen.value); assertNull(vm.activeBookingId.value) }
    }

    @Test fun temporaryBookingFailureCanRetryAfterLogin() {
        roleCode.set(503)
        mount(true)
        login()
        awaitFailure()
        val attemptsBeforeRetry = roleTokens.size
        roleCode.set(200)
        compose.onNodeWithTag("bookingLinkRetry").assertIsDisplayed().performClick()
        awaitState { vm.screen.value == Screen.ActiveTrip }
        compose.runOnIdle { assertEquals(42, vm.activeBookingId.value) }
        assertTrue(roleTokens.size > attemptsBeforeRetry)
    }

    @Test fun oldAccountResponseCannotOpenBookingForTheNewAccount() {
        holdFirstResponse = true
        firstResponseCode = 200
        roleCode.set(403)
        mount(true)
        login()
        awaitState { firstRequestStarted.count == 0L }
        login(token = "local-account-B", screen = Screen.Support)
        releaseFirstResponse.countDown()
        awaitFailure()
        compose.onNodeWithText("Поездка недоступна").assertIsDisplayed()
        compose.runOnIdle {
            assertNull(vm.activeBookingId.value)
            assertEquals(Screen.Support, vm.screen.value)
        }
        assertTrue(roleTokens.contains("Bearer local-account-A"))
        assertTrue("New account must be independently authorized", roleTokens.contains("Bearer local-account-B"))
    }

    @Test fun tokenChangeWithoutNavigationStillRejectsTheOldAccountResponse() {
        holdFirstResponse = true
        firstResponseCode = 200
        roleCode.set(403)
        mount(true)
        login()
        awaitState { firstRequestStarted.count == 0L }
        compose.runOnIdle {
            assertEquals(Screen.Notifications, vm.screen.value)
            ApiClient.saveToken("local-account-B")
            // Keep screen and destination unchanged: cancellation by a navigation key
            // must not be the mechanism protecting the second account.
        }
        releaseFirstResponse.countDown()
        awaitFailure()
        compose.onNodeWithText("Поездка недоступна").assertIsDisplayed()
        compose.runOnIdle {
            assertNull(vm.activeBookingId.value)
            assertEquals(Screen.Notifications, vm.screen.value)
        }
        assertTrue(roleTokens.contains("Bearer local-account-A"))
        assertTrue("Token change alone requires a new authorization check", roleTokens.contains("Bearer local-account-B"))
    }
}
