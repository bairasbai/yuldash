package com.yuldash.app

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import com.yuldash.app.data.ApiClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Форма нового обращения в поддержку прокручивается (приёмка PR210, эмулятор Android 15, 24.10.2026).
 *
 * Колонка формы отступает под клавиатуру, но не прокручивалась: при системном шрифте 130 % и открытой
 * клавиатуре «Отправить» оказывалась под клавиатурой, и дотянуться до неё было нечем — только закрыть
 * клавиатуру. Теперь кнопки внутри прокрутки.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SupportTicketFormScrollTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var server: MockWebServer

    @Before fun setup() {
        ApiClient.resetForTest()
        ApiClient.logout()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse().setHeader("Content-Type", "application/json").setBody("""{"items":[],"unread":0}""")
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

    @Test fun sendAndCancelAreInsideTheFormScroll() {
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                SupportTicketsScreen(onBack = {}, onOpenTicket = {})
            }
        }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Новое обращение").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Новое обращение").onFirst().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Отправить").fetchSemanticsNodes().isNotEmpty() }

        for (label in listOf("Отправить", "Отмена")) {
            compose.onAllNodes(hasText(label) and hasAnyAncestor(hasScrollAction())).assertCountEquals(1)
        }
    }
}
