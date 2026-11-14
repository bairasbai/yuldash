package com.yuldash.app

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.SnapshotWiringCleanupGuard
import com.yuldash.app.data.finishOfflineWriteFixture02
import com.yuldash.app.ui.theme.YuldashTheme
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w320dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RequestTravelerChoiceTest {
    companion object { private val cleanupGuard = SnapshotWiringCleanupGuard() }
    @get:Rule val compose = createComposeRule()
    private val visible = mutableStateOf(true)
    private lateinit var server: MockWebServer

    @Before fun setup() {
        cleanupGuard.requireHealthy()
        ApiClient.resetForTest()
        server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        ApiClient.testBaseUrl = "http://127.0.0.1:${server.port}"
        ApiClient.testTimeoutMs = 1_000
        ApiClient.init(ApplicationProvider.getApplicationContext<Application>())
        ApiClient.saveToken("header.eyJzdWIiOiIxMSJ9.signature")
    }

    @After fun cleanup() {
        if (!::server.isInitialized) return
        compose.runOnIdle { visible.value = false }
        finishOfflineWriteFixture02(cleanupGuard) {
            ApiClient.resetForTest()
            ApiClient.testTimeoutMs = null
            server.shutdown()
        }
    }

    @Test fun choosingSelfClearsAnEarlierNameAndConfirmsOnlySelf() {
        enqueueOffer()
        server.enqueue(MockResponse().setBody("""{"booking_id":45}"""))
        var accepted: Int? = null
        mount(AppLanguage.Ru, dark = false, fontScale = 1f) { accepted = it }
        val selfText = "Еду я"
        val otherText = "Едет другой человек"
        compose.waitUntil(10_000) { compose.onAllNodesWithText(selfText).fetchSemanticsNodes().isNotEmpty() }
        val self = compose.onNodeWithText(selfText)
        val other = compose.onNodeWithText(otherText)
        val confirm = compose.onNodeWithText("Подтвердить пассажира")
        self.assertIsNotSelected().assertHeightIsAtLeast(48.dp)
        other.assertIsNotSelected().assertHeightIsAtLeast(48.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        confirm.assertIsNotEnabled()
        other.performClick().assertIsSelected()
        self.assertIsNotSelected()
        val name = compose.onNode(hasSetTextAction())
        name.performScrollTo().performTextInput("Тест Пассажир")
        confirm.assertIsEnabled()
        self.performScrollTo().performClick().assertIsSelected()
        other.assertIsNotSelected()
        name.assertDoesNotExist()
        other.performClick().assertIsSelected()
        confirm.assertIsNotEnabled()
        self.performClick()
        confirm.performClick()
        compose.waitUntil(10_000) { accepted == 45 }
        checkNotNull(server.takeRequest(3, TimeUnit.SECONDS))
        val post = checkNotNull(server.takeRequest(3, TimeUnit.SECONDS))
        assertEquals("POST", post.method)
        val body = JSONObject(post.body.readUtf8())
        assertEquals(false, body.getBoolean("for_another"))
        assertFalse(body.has("traveler_name"))
    }

    @Test fun bashkirChoiceFitsAndReportsSelectionWithLargeTextInDarkMode() {
        enqueueOffer()
        mount(AppLanguage.Ba, dark = true, fontScale = 1.3f) { error("No booking expected") }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Мин барам").fetchSemanticsNodes().isNotEmpty() }
        val self = compose.onNodeWithText("Мин барам")
        val other = compose.onNodeWithText("Башҡа кеше бара")
        other.performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        self.assertIsNotSelected()
        other.performClick().assertIsSelected()
        self.performScrollTo().performClick().assertIsSelected()
        other.assertIsNotSelected()
        assertEquals(1, server.requestCount)
    }

    private fun enqueueOffer() {
        server.enqueue(MockResponse().setBody("""
            {"version":"${"a".repeat(64)}","for_another":false,"no_minors":true,
             "seats":1,"from_city":"Уфа","to_city":"Сибай","price":300}
        """.trimIndent()))
    }

    private fun mount(language: AppLanguage, dark: Boolean, fontScale: Float, onAccepted: (Int) -> Unit) {
        val owner = ApiClient.queueSessionGeneration()
        compose.setContent {
            CompositionLocalProvider(
                LocalAppLanguage provides language,
                LocalNightRide provides dark,
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
            ) {
                YuldashTheme(darkTheme = dark) {
                    if (visible.value) RequestTravelerDialog(7, owner, { true }, {}, onAccepted)
                }
            }
        }
    }
}
