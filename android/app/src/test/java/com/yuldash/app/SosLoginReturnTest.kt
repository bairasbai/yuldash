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
import androidx.compose.ui.test.junit4.createComposeRule
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
import org.json.JSONObject
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
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SosLoginReturnTest {
    @get:Rule val compose = createComposeRule()
    private val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
    private val mounted = mutableStateOf(true)
    private val sentBodies = CopyOnWriteArrayList<String>()
    private val sentTokens = CopyOnWriteArrayList<String>()
    private lateinit var vm: YuldashViewModel
    private lateinit var server: MockWebServer
    private val sendLabel = "Сообщить близким и поддержке"

    @Before fun prepare() {
        val context: Context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("yuldash_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("onboarding_completed", false).commit()
        ApiClient.resetForTest()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/sos" && request.method == "POST") {
                        sentTokens.add(request.getHeader("Authorization").orEmpty())
                        sentBodies.add(request.body.readUtf8())
                        return MockResponse().setResponseCode(200).setBody("{}")
                    }
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

    private fun clickSend() {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(sendLabel))
        compose.onNode(hasText(sendLabel) and hasClickAction()).assertIsDisplayed().performClick()
    }

    @Test fun loginReturnsToSosButRequiresANewExplicitSend() {
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                YuldashTheme { YuldashApp() }
            }
        }
        compose.onAllNodes(isRoot()).fetchSemanticsNodes()
        compose.runOnIdle { assertEquals(Screen.Sos, vm.screen.value) }
        clickSend()
        awaitState { vm.screen.value == Screen.Login }
        assertTrue("Anonymous send action must not send an SOS", sentBodies.isEmpty())

        compose.runOnIdle {
            ApiClient.saveToken("local-sos-login")
            vm.screen.value = Screen.Notifications
        }
        awaitState { vm.screen.value == Screen.Sos }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onAllNodes(isRoot()).fetchSemanticsNodes()
        assertTrue("Returning after login must never automatically send an SOS", sentBodies.isEmpty())

        clickSend()
        awaitState { sentBodies.size == 1 }
        assertEquals(listOf("Bearer local-sos-login"), sentTokens.toList())
        val body = JSONObject(sentBodies.single())
        assertFalse("Generic return must not invent an order context", body.has("order_id"))
        assertFalse("Generic return must not invent a booking context", body.has("booking_id"))
        compose.runOnIdle { vm.screen.value = Screen.Notifications }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onAllNodes(isRoot()).fetchSemanticsNodes()
        compose.runOnIdle { assertEquals("Return destination must be consumed once", Screen.Notifications, vm.screen.value) }
        assertEquals("Navigation must not repeat an SOS", 1, sentBodies.size)
    }
}
