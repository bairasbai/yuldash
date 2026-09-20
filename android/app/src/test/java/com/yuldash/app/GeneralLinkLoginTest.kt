package com.yuldash.app

import android.app.Application
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.isRoot
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GeneralLinkLoginTest {
    @get:Rule val compose = createComposeRule()
    private val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
    private val mounted = mutableStateOf(true)
    private lateinit var server: MockWebServer
    private lateinit var vm: YuldashViewModel

    @Before fun prepare() {
        val context: Context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("yuldash_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("onboarding_completed", false).commit()
        ApiClient.resetForTest()
        clearDestinations()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse().setResponseCode(503).setBody("{}")
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 1_000
        ApiClient.init(context)
        ApiClient.saveToken("")
        vm = ViewModelProvider(owner, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                YuldashViewModel(SavedStateHandle(mapOf("yuldash_screen" to Screen.Login.name))) as T
        })[YuldashViewModel::class.java]
    }

    @After fun cleanup() {
        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
        clearDestinations()
        owner.viewModelStore.clear()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server.shutdown()
    }

    private fun clearDestinations() {
        DeepLink.pendingParcels.value = false
        DeepLink.pendingSupport.value = false
        DeepLink.pendingFairness.value = false
        DeepLink.pendingRequestsFeed.value = false
        DeepLink.pendingRequestResponsesId.value = null
        DeepLink.pendingApplicationScreen.value = null
    }

    private fun loginJourney(destination: Screen, setPending: () -> Unit, isPending: () -> Boolean) {
        setPending()
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                YuldashTheme { YuldashApp() }
            }
        }
        compose.onAllNodes(isRoot()).fetchSemanticsNodes()
        compose.runOnIdle {
            assertEquals("Anonymous destination must wait at login", Screen.Login, vm.screen.value)
            assertTrue("Destination $destination was lost before login", isPending())
        }
        compose.runOnIdle {
            ApiClient.saveToken("local-general-link-login")
            // Model successful login without loading the native home map.
            vm.screen.value = Screen.Notifications
        }
        compose.waitUntil(10_000) {
            compose.onAllNodes(isRoot()).fetchSemanticsNodes()
            vm.screen.value == destination
        }
        compose.runOnIdle {
            assertEquals(destination, vm.screen.value)
            assertFalse("Successful navigation must consume its destination", isPending())
            if (destination == Screen.RequestResponses) assertEquals(42, vm.responsesRequestId.value)
        }
        compose.runOnIdle { vm.screen.value = Screen.Notifications }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onAllNodes(isRoot()).fetchSemanticsNodes()
        compose.runOnIdle {
            assertEquals("Consumed link must not reopen after leaving its screen", Screen.Notifications, vm.screen.value)
            assertFalse(isPending())
        }
    }

    @Test fun parcelLinkSurvivesLoginAndIsConsumedOnce() = loginJourney(
        Screen.Parcels, { DeepLink.pendingParcels.value = true }, { DeepLink.pendingParcels.value },
    )

    @Test fun supportLinkSurvivesLoginAndIsConsumedOnce() = loginJourney(
        Screen.Support, { DeepLink.pendingSupport.value = true }, { DeepLink.pendingSupport.value },
    )

    @Test fun fairnessLinkSurvivesLoginAndIsConsumedOnce() = loginJourney(
        Screen.FairnessCenter, { DeepLink.pendingFairness.value = true }, { DeepLink.pendingFairness.value },
    )

    @Test fun requestsFeedLinkSurvivesLoginAndIsConsumedOnce() = loginJourney(
        Screen.RequestsFeed, { DeepLink.pendingRequestsFeed.value = true }, { DeepLink.pendingRequestsFeed.value },
    )

    @Test fun requestResponsesLinkPreservesItsIdThroughLogin() = loginJourney(
        Screen.RequestResponses, { DeepLink.pendingRequestResponsesId.value = 42 },
        { DeepLink.pendingRequestResponsesId.value == 42 },
    )

    @Test fun taxiApplicationLinkSurvivesLoginAndIsConsumedOnce() = loginJourney(
        Screen.TaxiOnboarding, { DeepLink.pendingApplicationScreen.value = Screen.TaxiOnboarding },
        { DeepLink.pendingApplicationScreen.value == Screen.TaxiOnboarding },
    )

    @Test fun courierApplicationLinkSurvivesLoginAndIsConsumedOnce() = loginJourney(
        Screen.CourierOnboarding, { DeepLink.pendingApplicationScreen.value = Screen.CourierOnboarding },
        { DeepLink.pendingApplicationScreen.value == Screen.CourierOnboarding },
    )
}
