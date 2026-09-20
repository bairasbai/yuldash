package com.yuldash.app

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.ui.theme.YuldashTheme
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RequestWatchInboxTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var server: MockWebServer
    @Before fun setup() {
        ApiClient.resetForTest()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path?.startsWith("/notifications") == true && request.method == "GET") {
                        val items = listOf("request_watch", "request", "request", "booking_done").mapIndexed { i, kind ->
                            val type = if (i < 2) "request_watch" else if (i == 2) "request_response" else "ride"
                            """{"id":${i+1},"type":"$type","title_ru":"Notice $i","title_ba":"Notice $i","body_ru":"Body","body_ba":"Body","ref_kind":"$kind","ref_id":42,"read":true,"created_at":"2026-09-12T12:00:00Z"}"""
                        }.joinToString(",")
                        return MockResponse().setBody("""{"unread":0,"items":[$items]}""")
                    }
                    return MockResponse().setBody("{}")
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.init(ApplicationProvider.getApplicationContext())
        ApiClient.saveToken("local-inbox-test")
    }
    @After fun teardown() { ApiClient.resetForTest(); server.shutdown() }
    @Test fun currentAndLegacyWatcherNotesOpenFeedButAuthorOpensResponses() {
        var feedOpened = 0
        var responseId: Int? = null
        var completedId: Int? = null
        compose.setContent { YuldashTheme {
            NotificationsScreen(onBack = {}, onSelectTab = {},
                onOpenCompletedBooking = { completedId = it },
                onOpenRequestsFeed = { feedOpened++ }, onOpenResponses = { responseId = it })
        } }
        compose.waitUntil(10_000) {
            runCatching { compose.onNodeWithText("Notice 0").assertExists(); true }.getOrDefault(false)
        }
        compose.onNodeWithText("Notice 0").performClick()
        compose.runOnIdle { Assert.assertEquals(1, feedOpened); Assert.assertNull(responseId) }
        compose.onNodeWithText("Notice 1").performClick()
        compose.runOnIdle { Assert.assertEquals(2, feedOpened); Assert.assertNull(responseId) }
        compose.onNode(hasScrollAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasText("Notice 2"))
        compose.onNodeWithText("Notice 2").performClick()
        compose.runOnIdle { Assert.assertEquals(42, responseId); Assert.assertEquals(2, feedOpened) }
        compose.onNode(hasScrollAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasText("Notice 3"))
        compose.onNodeWithText("Notice 3").performClick()
        compose.runOnIdle { Assert.assertEquals(42, completedId); Assert.assertEquals(2, feedOpened) }
    }
}
