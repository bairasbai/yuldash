package com.yuldash.app

import android.app.Application
import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.ui.theme.YuldashTheme
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real HomeShell inside the same saved-screen holder contract as YuldashApp. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeReturnTabRestorationTest {
    @get:Rule val compose = createComposeRule()
    private val restoration = StateRestorationTester(compose)
    private val shown = mutableStateOf(true)
    private val mounted = mutableStateOf(true)
    private lateinit var chooseDestination: (HomeTab) -> Unit
    private var savedParentTab = HomeTab.Map
    private lateinit var server: MockWebServer

    @Before fun setup() {
        ApiClient.resetForTest()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(200).setBody("{}")
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 1000
        ApiClient.init(ApplicationProvider.getApplicationContext<Context>())
        ApiClient.logout()
        NavSignals.activeTaxiTrip.value = 0
        ThemePrefs.darkOverride = false
    }

    @After fun cleanup() {
        compose.runOnIdle { mounted.value = false }
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        ThemePrefs.darkOverride = null
        server.shutdown()
    }

    private fun mount(initial: HomeTab, language: AppLanguage = AppLanguage.Ru) {
        restoration.setContent {
            if (mounted.value) CompositionLocalProvider(LocalAppLanguage provides language) {
                YuldashTheme {
                    // Parent persists the accepted destination, just as vm.startHomeTab does.
                    var target by rememberSaveable { mutableStateOf(initial) }
                    val holder = rememberSaveableStateHolder()
                    SideEffect {
                        chooseDestination = { target = it }
                        savedParentTab = target
                    }
                    if (shown.value) holder.SaveableStateProvider("Home") {
                        HomeShell(initialTab = target, onTabChange = { target = it }) { tab, _ ->
                            Text("slot:${tab.name}")
                        }
                    } else Text("secondary-screen")
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("slot:${initial.name}").assertIsDisplayed()
    }

    private fun returnTo(from: HomeTab, to: HomeTab, language: AppLanguage = AppLanguage.Ru) {
        mount(from, language)
        compose.runOnIdle { shown.value = false }
        compose.waitForIdle()
        compose.onNodeWithText("secondary-screen").assertIsDisplayed()
        compose.runOnIdle { chooseDestination(to); shown.value = true }
        compose.waitForIdle()
        compose.onNodeWithText("slot:${to.name}").assertIsDisplayed()
        compose.runOnIdle { assertEquals(to, savedParentTab) }
    }

    @Test fun profileToChatHonoursExplicitReturnRu() = returnTo(HomeTab.Profile, HomeTab.Chat)
    @Test fun profileToChatHonoursExplicitReturnBa() = returnTo(HomeTab.Profile, HomeTab.Chat, AppLanguage.Ba)
    @Test fun profileToRidesHonoursExplicitReturn() = returnTo(HomeTab.Profile, HomeTab.Rides)
    @Test fun chatToProfileHonoursExplicitReturn() = returnTo(HomeTab.Chat, HomeTab.Profile)
    @Test fun ridesToMapHonoursExplicitReturn() = returnTo(HomeTab.Rides, HomeTab.Map)
    @Test fun sameDestinationKeepsItsTab() = returnTo(HomeTab.Profile, HomeTab.Profile)

    @Test fun latestDestinationWinsWhileHomeIsAbsent() {
        mount(HomeTab.Profile)
        compose.runOnIdle { shown.value = false }
        compose.waitForIdle()
        compose.runOnIdle { chooseDestination(HomeTab.Chat); chooseDestination(HomeTab.Rides); shown.value = true }
        compose.waitForIdle()
        compose.onNodeWithText("slot:Rides").assertIsDisplayed()
        compose.runOnIdle { assertEquals(HomeTab.Rides, savedParentTab) }
    }

    @Test fun currentTabSurvivesSavedInstanceRestorationThroughParent() {
        mount(HomeTab.Profile)
        compose.onNodeWithText("Чат").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("slot:Chat").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        compose.onNodeWithText("slot:Chat").assertIsDisplayed()
        compose.runOnIdle { assertEquals(HomeTab.Chat, savedParentTab) }
    }
}
