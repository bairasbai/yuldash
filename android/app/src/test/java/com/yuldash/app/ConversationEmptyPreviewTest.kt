package com.yuldash.app

import android.app.Application
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking

/** Real HTTP parser -> production ConversationCard. Native runs cover the full async inbox. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34], application=Application::class, qualifiers="w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConversationEmptyPreviewTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var server: MockWebServer
    private val mounted = mutableStateOf(true)
    private val requests = CopyOnWriteArrayList<String>()
    private var marker: Any? = null
    private var text = "Чат открыт"

    @Before fun setup() {
        ApiClient.resetForTest()
        ApiClient.init(ApplicationProvider.getApplicationContext<Context>())
        server=MockWebServer().apply {
            dispatcher=object: Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += "${request.method} ${request.path}"
                    val row=JSONObject().put("booking_id",42).put("peer_name","PreviewPeer")
                        .put("route","Сибай → Магнитогорск").put("last_message",text)
                    if(marker != null) row.put("has_messages",marker)
                    val body=when(request.path) {
                        "/conversations" -> "[$row]"
                        "/notifications" -> "{\"items\":[],\"unread\":0}"
                        else -> "[]"
                    }
                    return MockResponse().setHeader("Content-Type","application/json").setBody(body)
                }
            }
            start()
        }
        ApiClient.testBaseUrl=server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs=1000
        ApiClient.saveToken("local-preview-test")
    }
    @After fun cleanup() {
        compose.runOnIdle { mounted.value=false }
        ApiClient.logout(); ApiClient.resetForTest(); ApiClient.testTimeoutMs=null
        server.shutdown()
    }
    private fun verify(flag: Any?, input: String, language: AppLanguage, expected: String) {
        marker=flag; text=input
        val conversation = runBlocking { ApiClient.getConversations().getOrThrow().single() }
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides language) {
                if(mounted.value) ConversationCard(conversation) {_,_,_->}
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText(expected).assertIsDisplayed()
        if(expected != input) compose.onAllNodesWithText(input).assertCountEquals(0)
        assertTrue(requests.contains("GET /conversations"))
    }
    @Test fun emptyRu()=verify(false,"Чат открыт",AppLanguage.Ru,"Чат открыт")
    @Test fun emptyBa()=verify(false,"Чат открыт",AppLanguage.Ba,"Чат асылды")
    @Test fun emptyUsesMetadataRu()=verify(false,"legacy-server-placeholder",AppLanguage.Ru,"Чат открыт")
    @Test fun emptyUsesMetadataBa()=verify(false,"legacy-server-placeholder",AppLanguage.Ba,"Чат асылды")
    @Test fun literalHumanTextBa()=verify(true,"Чат открыт",AppLanguage.Ba,"Чат открыт")
    @Test fun arbitraryHumanTextBa()=verify(true,"Пишу по-русски",AppLanguage.Ba,"Пишу по-русски")
    @Test fun olderServerIsNotGuessed()=verify(null,"Чат открыт",AppLanguage.Ba,"Чат открыт")
    @Test fun malformedFlagIsNotGuessed()=verify("false","Чат открыт",AppLanguage.Ba,"Чат открыт")
}
