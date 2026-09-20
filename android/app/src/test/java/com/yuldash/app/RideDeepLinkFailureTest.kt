package com.yuldash.app

import android.app.Application
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.assertIsDisplayed
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Full navigation host and HTTP requests; no injected failure or retry callbacks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RideDeepLinkFailureTest {
    @get:Rule val compose = createComposeRule()
    private val owner = object : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }
    private val mounted = mutableStateOf(true)
    private val responseCode = AtomicInteger(503)
    private val rideRequests = CopyOnWriteArrayList<String>()
    private lateinit var server: MockWebServer
    private lateinit var vm: YuldashViewModel

    @Before fun prepare() {
        val context: Context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("yuldash_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("onboarding_completed", false).commit()
        ApiClient.resetForTest()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path in listOf("/rides/42", "/rides/43")) {
                        val id = request.path!!.substringAfterLast("/")
                        rideRequests.add(request.path!!)
                        val code = responseCode.get()
                        return MockResponse().setResponseCode(code)
                            .setHeader("Content-Type", "application/json")
                            .setBody(if (code == 200) {
                                """{"id":$id,"from_city":"Тестовый пункт А","to_city":"Тестовый пункт Б","depart_at":"2026-12-01T10:00:00Z","seats_total":3,"seats_left":2,"price":500,"category":"ride","driver_id":7,"driver_name":"Тестовый водитель"}"""
                            } else """{"detail":"Fixture failure"}""")
                    }
                    return MockResponse().setResponseCode(503).setBody("{}")
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 1_000
        ApiClient.init(context)
        ApiClient.saveToken("local-ride-link-failure")
        vm = ViewModelProvider(owner, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                YuldashViewModel(SavedStateHandle(mapOf("yuldash_screen" to Screen.Login.name))) as T
        })[YuldashViewModel::class.java]
        vm.screen.value = Screen.Login
        DeepLink.pendingRideId.value = 42
    }

    @After fun cleanup() {
        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
        DeepLink.pendingRideId.value = null
        owner.viewModelStore.clear()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server.shutdown()
    }

    private fun showFailure(code: Int) {
        responseCode.set(code)
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(
                LocalViewModelStoreOwner provides owner,
                LocalAppLanguage provides AppLanguage.Ru,
            ) {
                YuldashTheme { YuldashApp() }
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("rideLinkFailure").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("rideLinkFailure").assertIsDisplayed()
        compose.runOnIdle { assertEquals("Failure must preserve the existing screen", Screen.Login, vm.screen.value) }
        assertEquals(listOf("/rides/42"), rideRequests.toList())
    }

    @Test fun temporaryFailureOffersRetryAndOpensTheSameRideAfterRecovery() {
        showFailure(503)
        compose.onNodeWithText("Повторить").assertIsDisplayed()
        responseCode.set(200)
        compose.onNodeWithTag("rideLinkRetry").assertIsDisplayed().performClick()
        try {
            compose.waitUntil(10_000) {
                // Drive the host composition after the separate dialog window closes.
                compose.onAllNodes(isRoot()).fetchSemanticsNodes()
                vm.screen.value == Screen.Booking
            }
        } catch (e: Throwable) {
            throw AssertionError("screen=${vm.screen.value}; selected=${vm.selectedRide.value?.id}; pending=${DeepLink.pendingRideId.value}; requests=$rideRequests", e)
        }
        compose.runOnIdle {
            assertEquals("42", vm.selectedRide.value?.id)
            assertEquals(null, DeepLink.pendingRideId.value)
        }
        compose.onNodeWithTag("rideLinkFailure").assertDoesNotExist()
        assertEquals(listOf("/rides/42", "/rides/42"), rideRequests.toList())
    }

    @Test fun newRideLinkReplacesTheOldFailure() {
        showFailure(503)
        responseCode.set(200)
        compose.runOnIdle { DeepLink.pendingRideId.value = 43 }
        try {
            compose.waitUntil(10_000) {
                // Drive the host composition after the separate dialog window closes.
                compose.onAllNodes(isRoot()).fetchSemanticsNodes()
                vm.screen.value == Screen.Booking
            }
        } catch (e: Throwable) {
            throw AssertionError("screen=${vm.screen.value}; selected=${vm.selectedRide.value?.id}; pending=${DeepLink.pendingRideId.value}; requests=$rideRequests", e)
        }
        compose.runOnIdle { assertEquals("43", vm.selectedRide.value?.id) }
        compose.onNodeWithTag("rideLinkFailure").assertDoesNotExist()
        assertEquals(listOf("/rides/42", "/rides/43"), rideRequests.toList())
    }

    @Test fun missingRideShowsUnavailableWithoutRetryAndClosePreservesScreen() {
        unavailableRide(404)
    }

    @Test fun forbiddenRideShowsUnavailableWithoutRetryAndClosePreservesScreen() {
        unavailableRide(403)
    }

    private fun unavailableRide(code: Int) {
        showFailure(code)
        compose.onNodeWithText("Поездка недоступна").assertIsDisplayed()
        compose.onNodeWithTag("rideLinkRetry").assertDoesNotExist()
        closeFailure()
    }

    @Test fun closingTemporaryFailureDoesNotRetryOrNavigateAway() {
        showFailure(503)
        closeFailure()
    }

    private fun closeFailure() {
        compose.onNodeWithTag("rideLinkClose").assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("rideLinkFailure").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(Screen.Login, vm.screen.value)
            assertEquals(null, DeepLink.pendingRideId.value)
        }
        assertEquals("Closing a failure must not make another request", listOf("/rides/42"), rideRequests.toList())
    }
}
