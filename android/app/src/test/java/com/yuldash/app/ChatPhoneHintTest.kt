package com.yuldash.app

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollToNode
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
 * Подсказка «Номер телефона — на экране поездки» в списке чатов не обещает лишнего (приёмка PR210).
 *
 * Было «Открывается обоим после подтверждения брони» — для брони пассажира младше 18 это неправда:
 * водитель получает телефон взрослого, а номер ребёнка не отдаётся (решение Александра 23.10.2026).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatPhoneHintTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var server: MockWebServer

    @Before fun setup() {
        ApiClient.resetForTest()
        ApiClient.logout()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse().setHeader("Content-Type", "application/json").setBody("""{"items":[]}""")
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

    private fun showHint(language: AppLanguage, part: String) {
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides language) {
                ChatScreen(voiceMessages = emptyList(), onAddVoiceMessage = {}, onNotifications = {}, onOpenChat = { _, _, _ -> })
            }
        }
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(part, substring = true))
    }

    @Test fun hint_saysDriverSeesAdultPhone_forMinor_ru() {
        showHint(AppLanguage.Ru, "телефон взрослого")
        compose.onAllNodesWithText("Если едет пассажир младше 18, водитель видит телефон взрослого, а не его", substring = true)
            .assertCountEquals(1)
        compose.onAllNodesWithText("Так мы бережём твою безопасность", substring = true).assertCountEquals(0)
    }

    @Test fun hint_saysDriverSeesAdultPhone_forMinor_ba() {
        showHint(AppLanguage.Ba, "оло кешенең телефонын")
        compose.onAllNodesWithText("18-ҙән кесе юлсы барһа, йөрөтөүсе уның түгел, оло кешенең телефонын күрә", substring = true)
            .assertCountEquals(1)
        compose.onAllNodesWithText("Шулай беҙ һинең хәүефһеҙлегеңде һаҡлайбыҙ", substring = true).assertCountEquals(0)
    }
}
