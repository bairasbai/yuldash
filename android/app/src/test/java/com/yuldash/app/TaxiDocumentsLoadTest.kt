package com.yuldash.app

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.yuldash.app.data.ApiClient
import okhttp3.mockwebserver.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TaxiDocumentsLoadTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var server: MockWebServer
    @Volatile private var code = 404
    private val responses = java.util.concurrent.CopyOnWriteArrayList<Int>()
    @Before fun setup() {
        ApiClient.resetForTest()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val status = if (request.path == "/taxi/application") code else 404
                    if (request.path == "/taxi/application") responses.add(status)
                    return MockResponse().setResponseCode(status).setBody("{}")
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 1000
    }
    @After fun cleanup() {
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server.shutdown()
    }
    private fun show() {
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                TaxiDocumentsScreen(onBack = {})
            }
        }
    }
    private fun awaitText(text: String) {
        // HTTP completes outside Compose's clock; drain the Android callback queue as well.
        try {
            compose.waitUntil(20000) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (failure: ComposeTimeoutException) {
            throw AssertionError("Text '$text' not shown; application HTTP responses: $responses", failure)
        }
        compose.onNodeWithText(text).assertIsDisplayed()
    }
    @Test fun missingApplicationShowsEmptyState() {
        show()
        awaitText("Заявка не подана")
        assertTrue(responses.contains(404))
        compose.onNodeWithText("Что-то пошло не так").assertDoesNotExist()
    }
    @Test fun unavailableServerShowsErrorAndRetryCanRecoverToEmpty() {
        code = 503
        show()
        awaitText("Что-то пошло не так")
        assertTrue(responses.contains(503))
        compose.onNodeWithText("Заявка не подана").assertDoesNotExist()
        code = 404
        compose.onNodeWithText("Повторить").performClick()
        awaitText("Заявка не подана")
        assertTrue(responses.contains(404))
    }
}
