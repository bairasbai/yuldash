package com.yuldash.app

import android.app.Application
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.ui.theme.YuldashTheme
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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

/**
 * DESIGN-018: actual loaded MyDataScreen at RU/BA, normal/large font and two narrow widths.
 * The oracle checks lexical line boundaries, not a particular Row/Column implementation.
 * The existing short words fit on these viewports; forced intraword breaks make them unreadable.
 * JVM API34 font scaling is controlled here; physical devices/Android35 shaping remain separate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h1400dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MyDataFontScaleLayoutTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var server: MockWebServer
    private val mounted = mutableStateOf(true)
    private val requests = CopyOnWriteArrayList<Pair<String, String?>>()

    @Before fun setup() {
        ApiClient.resetForTest()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests.add("${request.method} ${request.path}" to request.getHeader("Authorization"))
                    return when (request.path) {
                        "/me/data" -> json("""{"rides":3,"rides_days":180,"driver_docs":0,"card_stored":false}""")
                        "/auth/logout", "/push/unregister" -> json("{}")
                        else -> json("{}", 404)
                    }
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 1_000
        ApiClient.init(ApplicationProvider.getApplicationContext<Application>())
        ApiClient.logout()
        ApiClient.saveToken("QA-design018-synthetic")
    }

    @After fun cleanup() {
        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
        ApiClient.logout()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server.shutdown()
    }

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code)
        .setHeader("Content-Type", "application/json; charset=utf-8").setBody(body)

    private fun settle() {
        Snapshot.sendApplyNotifications()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    private fun verify(language: AppLanguage, scale: Float, width: Int) {
        val ru = language == AppLanguage.Ru
        val title = if (ru) "Данные карты" else "Карта мәғлүмәттәре"
        val note = if (ru) "Деньги идут мимо нас — напрямую водителю" else "Аҡса беҙҙән үтмәй — тура шоферға бара"
        val value = if (ru) "Не храним" else "Һаҡламайбыҙ"
        val anchor = if (ru) "3 поездки" else "3 сәфәр"
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalAppLanguage provides language, LocalDensity provides Density(density, scale)) {
                YuldashTheme {
                    Box(Modifier.width(width.dp).height(1_100.dp).testTag("mydata-viewport")) {
                        if (mounted.value) MyDataScreen(onBack = {})
                    }
                }
            }
        }
        compose.waitUntil(10_000) { settle(); compose.onAllNodesWithText(anchor).fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertEquals("the actual screen must fetch its data once", 1,
            requests.count { it.first == "GET /me/data" && it.second == "Bearer QA-design018-synthetic" })
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(title))
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(value))
        compose.onNodeWithText(value).assertIsDisplayed()

        val titleWords = if (ru) setOf("Данные", "карты") else setOf("Карта", "мәғлүмәттәре")
        val noteWords = if (ru) setOf("Деньги", "идут", "мимо", "нас", "напрямую", "водителю")
            else setOf("Аҡса", "беҙҙән", "үтмәй", "тура", "шоферға", "бара")
        val violations = mutableListOf<String>()
        val valueWords = if (ru) setOf("Не", "храним") else setOf("Һаҡламайбыҙ")
        for ((text, words) in listOf(title to titleWords, value to valueWords, note to noteWords)) {
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))
            val node = compose.onNodeWithText(text, useUnmergedTree = true)
            node.assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertEquals("the oracle must inspect the actual expected Text", text, layout.layoutInput.text.text)
            assertEquals("the real screen must receive the requested font scale", scale, layout.layoutInput.density.fontScale, 0f)
            // A paragraph can retain maxWidth while Text shrinks to its intrinsic width:
            // measured control: 188px paragraph, 120px Text, lineRight=118.671875px.
            // Check the actual laid-out line extents and complete characters, not blank space.
            assertFalse("maxLines must not discard text", layout.multiParagraph.didExceedMaxLines)
            for (line in 0 until layout.lineCount) {
                assertTrue("line must fit horizontally: $text line=$line", layout.getLineLeft(line) >= 0f &&
                    layout.getLineRight(line) <= layout.size.width.toFloat())
                assertTrue("line must fit vertically: $text line=$line", layout.getLineTop(line) >= 0f &&
                    layout.getLineBottom(line) <= layout.size.height.toFloat())
            }
            assertEquals("the layout must contain the complete text", text.length, layout.getLineEnd(layout.lineCount - 1))
            val tokens = Regex("\\p{L}+").findAll(text).filter { it.value in words }.toList()
            assertEquals("all selected Unicode words must be observed", words, tokens.map { it.value }.toSet())
            val lines = (0 until layout.lineCount).map { line ->
                text.substring(layout.getLineStart(line), layout.getLineEnd(line))
            }
            val breaks = (0 until layout.lineCount - 1).map { layout.getLineEnd(it) }.filter { offset ->
                offset in 1 until text.length && text[offset - 1].isLetter() && text[offset].isLetter()
            }
            for (offset in breaks) {
                val token = tokens.firstOrNull { offset > it.range.first && offset <= it.range.last }
                if (token != null) violations.add("${token.value}: ${text.substring(token.range.first, offset)}|" +
                    text.substring(offset, token.range.last + 1))
            }
            println("DESIGN018 language=$language font=$scale viewportDp=$width lines=$lines intraword=$violations")
        }
        assertTrue("$language font=$scale viewport=$width: words must not be split inside a word: $violations", violations.isEmpty())
    }

    @Test fun width393RussianNormal() = verify(AppLanguage.Ru, 1f, 393)
    @Test fun width393BashkirNormal() = verify(AppLanguage.Ba, 1f, 393)
    @Test fun width393RussianLarge() = verify(AppLanguage.Ru, 2f, 393)
    @Test fun width393BashkirLarge() = verify(AppLanguage.Ba, 2f, 393)
    @Test fun width411RussianNormal() = verify(AppLanguage.Ru, 1f, 411)
    @Test fun width411BashkirNormal() = verify(AppLanguage.Ba, 1f, 411)
    @Test fun width411RussianLarge() = verify(AppLanguage.Ru, 2f, 411)
    @Test fun width411BashkirLarge() = verify(AppLanguage.Ba, 2f, 411)
}
