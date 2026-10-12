package com.yuldash.app

import android.app.Application
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
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
class NavSignalLoginTest {
    @get:Rule val compose = createComposeRule()
    private val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
    private val mounted = mutableStateOf(true)
    private lateinit var server: MockWebServer
    private lateinit var vm: YuldashViewModel
    private val rootRegistry=mutableStateOf(SaveableStateRegistry(null) {true})
    private val requestedPaths=java.util.concurrent.CopyOnWriteArrayList<String>()

    @Before fun prepare() {
        val context: Context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("yuldash_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("onboarding_completed", false).commit()
        ApiClient.resetForTest()
        clearSignals()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requestedPaths+=request.requestUrl!!.encodedPath
                    return MockResponse().setResponseCode(503).setBody("{}")
                }
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
        clearSignals()
        owner.viewModelStore.clear()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server.shutdown()
    }

    private fun clearSignals() {
        NavSignals.openDriverCabinet.value = false
        NavSignals.openAdsCabinet.value = false
        NavSignals.openPartnerCabinet.value = false
        NavSignals.openInstantOrder.value = false
    }

    private fun mount() {
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(LocalViewModelStoreOwner provides owner,LocalSaveableStateRegistry provides rootRegistry.value) {
                YuldashTheme { YuldashApp() }
            }
        }
        compose.onAllNodes(isRoot()).fetchSemanticsNodes()
    }

    private fun awaitState(condition: () -> Boolean) {
        compose.waitUntil(10_000) {
            compose.onAllNodes(isRoot()).fetchSemanticsNodes()
            condition()
        }
    }

    private fun loginJourney(destination: Screen, signal: MutableState<Boolean>) {
        signal.value = true
        mount()
        compose.runOnIdle {
            assertEquals(Screen.Login, vm.screen.value)
            assertTrue("$destination signal must survive login", signal.value)
        }
        compose.runOnIdle {
            ApiClient.saveToken("local-nav-signal-login")
            vm.screen.value = Screen.Notifications
        }
        awaitState { vm.screen.value == destination }
        compose.runOnIdle {
            assertEquals(destination, vm.screen.value)
            assertFalse("Destination signal must be consumed after opening", signal.value)
        }
        compose.runOnIdle { vm.screen.value = Screen.Notifications }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onAllNodes(isRoot()).fetchSemanticsNodes()
        compose.runOnIdle {
            assertEquals("Consumed signal must not reopen its screen", Screen.Notifications, vm.screen.value)
            assertFalse(signal.value)
        }
    }

    @Test fun driverCabinetSignalSurvivesLogin() = loginJourney(Screen.DriverCabinet, NavSignals.openDriverCabinet)

    @Test fun adsCabinetSignalSurvivesLogin() = loginJourney(Screen.AdsCabinet, NavSignals.openAdsCabinet)

    @Test fun partnerCabinetSignalSurvivesLogin() = loginJourney(Screen.PartnerCabinet, NavSignals.openPartnerCabinet)

    @Test fun instantOrderSignalSurvivesLogin() = loginJourney(Screen.InstantOrder, NavSignals.openInstantOrder)

    @Test fun driverCabinetSignalDoesNotInterruptAnOpenDriverTrip() {
        ApiClient.saveToken("local-driver-trip-guard")
        vm.screen.value = Screen.Notifications
        mount()
        // Use the observed production packet and owner, rather than an invalid ID-less route.
        val saved=compose.runOnIdle {rootRegistry.value.performSave()}.toMutableMap()
        val slot=saved.flatMap { (key,values) -> values.mapIndexedNotNull {index,value ->
            val packet=value as? List<*>
            if(packet?.getOrNull(1)=="instantTripOrderId") Triple(key,index,packet) else null
        }}.single()
        val packet=slot.third.toMutableList();packet[3]=42
        val providers=saved.getValue(slot.first).toMutableList();providers[slot.second]=ArrayList(packet);saved[slot.first]=providers
        compose.runOnIdle {mounted.value=false};compose.waitForIdle()
        compose.runOnIdle {rootRegistry.value=SaveableStateRegistry(saved) {true};vm.screen.value=Screen.InstantDriverTrip;mounted.value=true}
        awaitState {requestedPaths.any {it=="/instant/orders/42"}}
        compose.runOnIdle { NavSignals.openDriverCabinet.value = true }
        awaitState { !NavSignals.openDriverCabinet.value }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onAllNodes(isRoot()).fetchSemanticsNodes()
        compose.runOnIdle {
            assertEquals("Cabinet notification must not replace the open driver trip", Screen.InstantDriverTrip, vm.screen.value)
            assertFalse(requestedPaths.any {it.startsWith("/instant/orders/0")})
        }
    }
}
