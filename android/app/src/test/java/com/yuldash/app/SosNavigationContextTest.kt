package com.yuldash.app

import android.app.Application
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.ui.theme.YuldashTheme
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.LinkedBlockingQueue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SosNavigationContextTest {
    @get:Rule val compose = createComposeRule()
    private val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
    private val mounted = mutableStateOf(true)
    private val sent = LinkedBlockingQueue<String>()
    private lateinit var server: MockWebServer
    private lateinit var vm: YuldashViewModel

    @Before fun prepare() {
        val context: Context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("yuldash_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("onboarding_completed", false).commit()
        ApiClient.resetForTest()
        NavSignals.openSosForOrder.value = 0
        NavSignals.openSosWithNote.value = null
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.method == "POST" && request.path == "/sos") {
                        sent.add(request.body.readUtf8())
                        return MockResponse().setResponseCode(200).setBody("{\"ok\":true}")
                    }
                    return MockResponse().setResponseCode(503).setBody("{}")
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 1_000
        ApiClient.init(context)
        ApiClient.saveToken("local-sos-context-test")
        vm = ViewModelProvider(owner, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                YuldashViewModel(SavedStateHandle(mapOf("yuldash_screen" to Screen.Notifications.name))) as T
        })[YuldashViewModel::class.java]
    }

    @After fun cleanup() {
        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
        NavSignals.openSosForOrder.value = 0
        NavSignals.openSosWithNote.value = null
        owner.viewModelStore.clear()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server.shutdown()
    }

    @Test fun taxiSosDoesNotReuseThePreviousCourierNote() {
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                YuldashTheme { YuldashApp() }
            }
        }
        compose.onAllNodes(isRoot()).fetchSemanticsNodes()
        compose.runOnIdle { NavSignals.openSosWithNote.value = "Доставка #7 Уфа — Сибай" }
        compose.waitUntil(10_000) {
            compose.onAllNodes(isRoot()).fetchSemanticsNodes()
            vm.screen.value == Screen.Sos
        }
        compose.runOnIdle {
            assertNull(NavSignals.openSosWithNote.value)
            assertTrue("Navigation alone must not send SOS", sent.isEmpty())
            vm.screen.value = Screen.Notifications
        }
        compose.waitForIdle()
        compose.runOnIdle { NavSignals.openSosForOrder.value = 42 }
        compose.waitUntil(10_000) {
            compose.onAllNodes(isRoot()).fetchSemanticsNodes()
            vm.screen.value == Screen.Sos && NavSignals.openSosForOrder.value == 0
        }
        val sendButton = hasText("Сообщить близким и поддержке") and hasClickAction()
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(sendButton)
        // Only the in-process MockWebServer sees this POST. It cannot notify anyone.
        compose.onNode(sendButton).performClick()
        compose.waitUntil(10_000) { sent.isNotEmpty() }
        val payload = JSONObject(sent.poll())
        assertEquals(42, payload.getInt("order_id"))
        assertFalse("Taxi SOS must not carry the previous delivery's context", payload.getString("note").contains("Доставка #7"))
        assertFalse(payload.has("booking_id"))
    }
}
