package com.yuldash.app

import android.app.Application
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
import com.yuldash.app.ui.theme.YuldashTheme
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExpiredSessionBackHistoryTest {
    // StandardTestDispatcher queues state-flow effects on the UI test scheduler.
    // The legacy unconfined rule can resume the session-expiry collector on the HTTP
    // worker, where Android's real Toast has no Looper.
    @get:Rule val compose = createComposeRule()
    private val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
    private val mounted = mutableStateOf(true)
    private val sosRequests = AtomicInteger()
    private lateinit var vm: YuldashViewModel
    private lateinit var server: MockWebServer

    @Before fun prepare() {
        val context: Context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("yuldash_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("onboarding_completed", true)
            .putString("preferred_role", RideRole.Driver.name).commit()
        ApiClient.resetForTest()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/sos" && request.method == "POST") {
                        sosRequests.incrementAndGet()
                        return MockResponse().setResponseCode(401).setBody("{}")
                    }
                    return MockResponse().setResponseCode(503).setBody("{}")
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 1_000
        ApiClient.init(context)
        ApiClient.saveToken("local-account-A-expiring")
        vm = ViewModelProvider(owner, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                YuldashViewModel(SavedStateHandle(mapOf("yuldash_screen" to Screen.Sos.name))) as T
        })[YuldashViewModel::class.java]
    }

    @After fun cleanup() {
        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
        owner.viewModelStore.clear()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server.shutdown()
    }

    private fun awaitState(condition: () -> Boolean) {
        compose.waitUntil(10_000) {
            compose.onAllNodes(isRoot()).fetchSemanticsNodes()
            condition()
        }
    }

    @Test fun expiryCannotReinsertThePreviousAccountsSosIntoBackHistory() {
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                YuldashTheme { YuldashApp() }
            }
        }
        val label = "Сообщить близким и поддержке"
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(label))
        compose.onNode(hasText(label) and hasClickAction()).assertIsDisplayed().performClick()
        awaitState { vm.screen.value == Screen.Login }
        compose.runOnIdle {
            assertEquals(1, sosRequests.get())
            assertFalse(ApiClient.isLoggedIn())
        }
        // Capture the result before the next login changes the navigation history again.
        val historyAfterExpiry = compose.runOnIdle { vm.navHistory.toList() }
        compose.runOnIdle {
            ApiClient.saveToken("local-account-B")
            // Mirrors the existing preferred_role=Driver branch of LoginScreen.onContinue.
            // Passenger login uses openHome(), which already clears history.
            vm.startHomeTab.value = HomeTab.Profile
            vm.screen.value = Screen.DriverCabinet
        }
        awaitState { vm.screen.value == Screen.DriverCabinet }
        compose.onNodeWithContentDescription("Назад").assertIsDisplayed().performClick()
        compose.onAllNodes(isRoot()).fetchSemanticsNodes()
        compose.runOnIdle {
            assertFalse("Expiry must not reinsert the old SOS after clearing history: $historyAfterExpiry", Screen.Sos in historyAfterExpiry)
            assertEquals("Real Back must return the new driver to their profile home", Screen.Home, vm.screen.value)
            assertFalse("Old private screen must not remain reachable", Screen.Sos in vm.navHistory)
        }
        assertEquals("Back navigation must never send another SOS", 1, sosRequests.get())
    }
}
