package com.yuldash.app

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.ui.theme.YuldashTheme
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h1400dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SosSubmitOwnerBoundaryTest {
    @get:Rule val compose = createComposeRule()
    private val mounted = mutableStateOf(true)
    private val posts = CopyOnWriteArrayList<RecordedRequest>()
    private lateinit var server: MockWebServer
    private var dropReply = false
    @Before fun prepare() {
        ApiClient.resetForTest()
        ApiClient.init(ApplicationProvider.getApplicationContext<Application>())
        ApiClient.saveToken("synthetic-owner-a")
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.method == "POST" && request.path == "/sos") posts += request
                    if (dropReply) return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                    return MockResponse().setBody("{\"id\":7,\"status\":\"new\"}")
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 1000
    }
    @After fun cleanup() {
        compose.runOnIdle { mounted.value = false }
        ApiClient.logout()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server.shutdown()
    }
    private fun retainedSend(): () -> Boolean {
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                YuldashTheme { if (mounted.value) SosScreen({}, {}, bookingId = 42) }
            }
        }
        return compose.onNode(hasText("Сообщить поддержке") and hasClickAction())
            .performScrollTo().fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
    }
    private fun settle() {
        compose.runOnIdle { Shadows.shadowOf(Looper.getMainLooper()).idle() }
        compose.waitForIdle()
    }
    @Test fun currentOwnerMaySendOneSignal() {
        val send = retainedSend()
        compose.runOnIdle { send(); send() }
        compose.waitUntil(10000) { Shadows.shadowOf(Looper.getMainLooper()).idle(); posts.size == 1 }
        assertEquals("Bearer synthetic-owner-a", posts.single().getHeader("Authorization"))
    }
    @Test fun retainedHandlerCannotSendUnderReplacementAccount() {
        val send = retainedSend()
        ApiClient.saveToken("synthetic-owner-b")
        compose.runOnIdle { send() }
        settle()
        assertTrue(posts.isEmpty())
    }
    @Test fun retainedHandlerCannotSendAfterLogout() {
        val send = retainedSend()
        ApiClient.logout()
        compose.runOnIdle { send() }
        settle()
        assertTrue(posts.isEmpty())
    }
    @Test fun retainedHandlerCannotSendAfterLeavingScreen() {
        val send = retainedSend()
        compose.runOnIdle { mounted.value = false }
        compose.runOnIdle { send() }
        settle()
        assertTrue(posts.isEmpty())
    }
    @Test fun lostReplyShowsUnconfirmedInsteadOfClaimingNothingWasSent() {
        dropReply = true
        val send = retainedSend()
        compose.runOnIdle { send() }
        compose.waitUntil(10000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithText("Не удалось подтвердить отправку").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(1, posts.size)
    }
}
