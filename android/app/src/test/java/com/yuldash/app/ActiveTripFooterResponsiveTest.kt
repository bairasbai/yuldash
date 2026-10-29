package com.yuldash.app

import android.app.Application
import android.os.Looper
import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.BoardingUndoTerms
import com.yuldash.app.ui.theme.YuldashTheme
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowDialog
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Legacy-compatible: ни одного обращения к новым production Local/helper API. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h1000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ActiveTripFooterResponsiveTest {
    @get:Rule val compose = createComposeRule()
    private val width = mutableStateOf(320.dp)
    private lateinit var inheritedButtonStyle: TextStyle
    private var messages = 0
    private var primaries = 0
    private var answers = 0
    private var server: MockWebServer? = null

    @Before fun setup() {
        ApiClient.resetForTest()
        ApiClient.init(ApplicationProvider.getApplicationContext<Application>())
        ApiClient.saveToken("header.eyJzdWIiOiIxMSJ9.signature")
    }

    @After fun cleanup() {
        ApiClient.resetForTest()
        server?.shutdown()
    }

    @Test fun pendingRussianLightPreservesMainViewport() = pendingLayout(AppLanguage.Ru, false)
    @Test fun pendingRussianDarkPreservesMainViewport() = pendingLayout(AppLanguage.Ru, true)
    @Test fun pendingBashkirLightPreservesMainViewport() = pendingLayout(AppLanguage.Ba, false)
    @Test fun pendingBashkirDarkPreservesMainViewport() = pendingLayout(AppLanguage.Ba, true)

    @Test fun actualPrimaryTextKeepsMaterialButtonTypography() {
        render(AppLanguage.Ru, false)
        val layout = wholeText("Доехал", viewport())
        // Считываем TextLayoutResult реального AppButton, не функцию решения о ширине.
        assertEquals(CanonButton.fontSize, layout.layoutInput.style.fontSize)
        assertEquals(FontWeight.Bold, layout.layoutInput.style.fontWeight)
        assertEquals(inheritedButtonStyle.letterSpacing, layout.layoutInput.style.letterSpacing)
        assertEquals(inheritedButtonStyle.lineHeight, layout.layoutInput.style.lineHeight)
        assertEquals(inheritedButtonStyle.fontFamily, layout.layoutInput.style.fontFamily)
        assertEquals(inheritedButtonStyle.fontStyle, layout.layoutInput.style.fontStyle)
        assertEquals(inheritedButtonStyle.fontSynthesis, layout.layoutInput.style.fontSynthesis)
        assertEquals(1.3f, layout.layoutInput.density.fontScale, 0f)
        val button = compose.onNode(hasClickAction() and hasText("Доехал"))
        touchSize(button)
        val textBounds = compose.onNodeWithText("Доехал", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertInside(textBounds, button.getUnclippedBoundsInRoot(), "Полная надпись внутри основной кнопки")
    }

    @Test fun messageStillCallsTheOriginalCallbackExactlyOnceWithUnreadBadge() {
        render(AppLanguage.Ru, false)
        compose.onNodeWithText("7", useUnmergedTree = true).assertIsDisplayed()
        val message = messageButton("Написать")
        touchSize(message)
        message.performClick()
        compose.waitForIdle()
        assertEquals(1, messages)
        assertEquals(0, primaries)
        assertEquals(0, answers)
    }

    @Test fun nonPendingPassengerKeepsFullMarkedExplanation() {
        render(AppLanguage.Ru, false, pending = false)
        wholeText("Водитель отметил посадку", viewport())
        wholeText("Не садился — сообщи о проблеме.", viewport())
        wholeText("Сообщить о проблеме", viewport())
        compose.onAllNodesWithText("Водитель просит исправить посадку").assertCountEquals(0)
    }

    @Test fun openReportDraftSurvivesCompactToFullAndBack() {
        render(AppLanguage.Ru, false)
        openReportDialog()
        compose.onNodeWithText("Как было").assertIsDisplayed()
        val draft = "Проверяю, что описание не теряется"
        compose.onNode(hasSetTextAction()).performTextInput(draft)
        compose.runOnIdle { width.value = 411.dp }
        compose.onNode(hasSetTextAction()).assertTextContains(draft)
        compose.runOnIdle { width.value = 320.dp }
        compose.onNode(hasSetTextAction()).assertTextContains(draft)
        assertEquals(0, answers)
        assertEquals(0, primaries)
        compose.onNodeWithText("Отмена").performClick()
        compose.onNodeWithText("Сообщить о проблеме").assertIsDisplayed()
    }

    @Test fun pendingResponseSurvivesWidthChangeWithoutDuplicateSubmission() {
        val stub = MockWebServer().apply { start() }
        server = stub
        ApiClient.testBaseUrl = stub.url("/").toString().trimEnd('/')
        val release = CountDownLatch(1)
        stub.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (!release.await(30, TimeUnit.SECONDS)) return MockResponse().setResponseCode(504)
                return MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("""{"status":"onboard","boarding_undo_sec":0}""")
            }
        }
        try {
            render(AppLanguage.Ru, false)
            compose.onNodeWithText("Я в машине").performClick()
            waitUntil { stub.requestCount == 1 }
            compose.runOnIdle { width.value = 411.dp }
            compose.onNode(hasClickAction() and hasText("Я в машине")).assertIsNotEnabled()
            compose.runOnIdle { width.value = 320.dp }
            compose.onNode(hasClickAction() and hasText("Я в машине")).assertIsNotEnabled()
        } finally {
            release.countDown()
        }
        waitUntil { answers == 1 }
        assertEquals(1, stub.requestCount)
        assertEquals("/bookings/42/boarded/undo/respond", stub.takeRequest(1, TimeUnit.SECONDS)?.path)
        assertEquals(0, primaries)
    }

    @Test fun responseErrorSurvivesCompactToFullAndBack() {
        val stub = MockWebServer().apply { start() }
        server = stub
        ApiClient.testBaseUrl = stub.url("/").toString().trimEnd('/')
        val error = "Не получилось ответить. Проверь сеть и повтори."
        stub.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setResponseCode(400).setBody("""{"detail":"$error"}"""))
        render(AppLanguage.Ru, false)
        compose.onNodeWithText("Я в машине").performClick()
        waitUntil { compose.onAllNodesWithText(error, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        wholeText(error, viewport())
        compose.runOnIdle { width.value = 411.dp }
        wholeText(error, viewport())
        compose.runOnIdle { width.value = 320.dp }
        wholeText(error, viewport())
        compose.onNode(hasClickAction() and hasText("Я в машине")).assertIsEnabled()
        assertEquals(1, stub.requestCount)
        assertEquals(0, answers)
    }

    @Test fun filedReportSurvivesCompactToFullAndBack() {
        val stub = MockWebServer().apply { start() }
        server = stub
        ApiClient.testBaseUrl = stub.url("/").toString().trimEnd('/')
        stub.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"id":42,"booking_id":42,"type":"driver_no_show","status":"open"}"""))
        render(AppLanguage.Ru, false)
        openReportDialog()
        compose.onNode(hasSetTextAction()).performTextInput("Описание для проверки сохранения разбора")
        compose.onNode(hasClickAction() and hasText("Открыть разбор")).performClick()
        val filed = "Разбор открыт. Позовём водителя объясниться, а решение объясним обеим сторонам."
        waitUntil { compose.onAllNodesWithText(filed, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        wholeText(filed, viewport())
        compose.runOnIdle { width.value = 411.dp }
        wholeText(filed, viewport())
        compose.runOnIdle { width.value = 320.dp }
        wholeText(filed, viewport())
        assertEquals("/incidents", stub.takeRequest(1, TimeUnit.SECONDS)?.path)
        compose.onAllNodesWithText("Сообщить о проблеме").assertCountEquals(0)
        assertEquals(0, answers)
    }

    private fun pendingLayout(language: AppLanguage, dark: Boolean) {
        render(language, dark)
        val frame = viewport()
        val main = compose.onNodeWithTag("footer-regression-main").getUnclippedBoundsInRoot()
        val details = "language=$language dark=$dark frame=$frame main=$main\n" +
            compose.onRoot(useUnmergedTree = true).printToString()
        // Тот же12% барьер native-scroll; размеров будущего решения здесь нет.
        assertTrue("Main viewport must pass the unchanged native geometry: $details",
            (main.bottom - main.top).value >= (frame.bottom - frame.top).value * 0.12f)
        assertTrue("Main width must pass the unchanged native geometry: $details",
            (main.right - main.left).value >= (frame.right - frame.left).value * 0.35f)
        val labels = if (language == AppLanguage.Ru) listOf(
            "Водитель просит исправить посадку", "Подтверди только если ты ещё не в машине.",
            "Я ещё не сел", "Я в машине", "Сообщить о проблеме", "Доехал",
        ) else listOf(
            "Йөрөтөүсе ултырыу билдәһен төҙәтергә һорай", "Машинаға әле ултырмаған булһаң ғына раҫла.",
            "Мин әле ултырманым", "Мин машиналамын", "Проблема тураһында хәбәр итергә", "Барып еттем",
        )
        labels.forEach { wholeText(it, frame) }
        labels.take(4).forEach {
            val bounds = compose.onNodeWithText(it, useUnmergedTree = true).getUnclippedBoundsInRoot()
            assertTrue("Consent must be below main/topbar and above safe bottom: $it $bounds main=$main",
                bounds.top >= main.bottom && bounds.bottom <= frame.bottom)
        }
        val yes = compose.onNode(hasClickAction() and hasText(labels[2]))
        val no = compose.onNode(hasClickAction() and hasText(labels[3]))
        val done = compose.onNode(hasClickAction() and hasText(labels.last()))
        listOf(yes, no, done).forEach { touchSize(it) }
        val message = messageButton(if (language == AppLanguage.Ru) "Написать" else "Яҙырға")
        touchSize(message)
        val doneBounds = done.getUnclippedBoundsInRoot()
        val messageBounds = message.getUnclippedBoundsInRoot()
        assertInside(doneBounds, frame, "Основная кнопка внутри экрана")
        assertInside(messageBounds, frame, "Сообщение внутри экрана")
        assertTrue("Footer action targets must not overlap", doneBounds.left >= messageBounds.right ||
            doneBounds.top >= messageBounds.bottom || messageBounds.top >= doneBounds.bottom)
        val segment = if (language == AppLanguage.Ru) "Участок: Уфа → Мелеуз" else "Юл өлөшө: Уфа → Мелеуз"
        compose.onNodeWithTag("footer-regression-main").performScrollToNode(hasText(segment))
        val currentMain = compose.onNodeWithTag("footer-regression-main").getUnclippedBoundsInRoot()
        wholeText(segment, currentMain)
        val stops = if (language == AppLanguage.Ru) listOf("Посадка: Уфа", "Высадка: Мелеуз")
            else listOf("Ултырыу: Уфа", "Төшөү: Мелеуз")
        stops.forEach {
            compose.onNodeWithTag("footer-regression-main").performScrollToNode(hasText(it))
            wholeText(it, currentMain)
        }
        assertEquals(0, answers)
        assertEquals(0, primaries)
    }

    private fun render(language: AppLanguage, dark: Boolean, pending: Boolean = true) {
        compose.setContent {
            YuldashTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalAppLanguage provides language,
                    LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                    inheritedButtonStyle = LocalTextStyle.current.merge(MaterialTheme.typography.labelLarge)
                    // Только тестовый device profile из настоящего PNG:2340px/(1080px/320dp).
                    Box(Modifier.requiredSize(width.value, (2340f / (1080f / 320f)).dp)
                        .testTag("footer-regression-viewport")) {
                        Scaffold(containerColor = CanonBg,
                            topBar = { ScreenTopBar(appText("Моя поездка", "Минең сәфәр"), {}) },
                            bottomBar = {
                                ActiveTripDecisionBar(primaryText = activeTripActionLabel("arrived"),
                                    onMessage = { messages++ }, onPrimary = { primaries++ }, unread = 7,
                                    boarding = activeTripBoardingSlot("passenger", "onboard", 42, 9, "Ринат", 0,
                                        onUndone = { answers++ }, undoTerms = BoardingUndoTerms(
                                            pending = pending, requestId = if (pending) "request-footer-1" else null,
                                            waitSec = if (pending) 120 else 0)))
                            }) { padding ->
                            LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = CanonSpace.lg)
                                .testTag("footer-regression-main"), verticalArrangement = Arrangement.spacedBy(CanonSpace.md)) {
                                item { BookingSegmentNote("Уфа", "Мелеуз", true, "Уфа", "Мелеуз") }
                                items(20) { Text(appText("Моя поездка", "Минең сәфәр"), style = CanonBody) }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** Existing Robolectric-only WRAP_CONTENT/TextField remeasure workaround; product unchanged. */
    private fun openReportDialog() {
        val action = compose.onNodeWithText("Сообщить о проблеме").fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action!!
        val previous = ShadowDialog.getLatestDialog()
        compose.runOnIdle { assertTrue(action()) }
        repeat(10) {
            // Direct OnClick writes state; flush its snapshot and queued Android work before lookup.
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            val dialog = ShadowDialog.getLatestDialog()
            if (dialog != null && dialog !== previous && dialog.isShowing) {
                dialog.window!!.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                return
            }
        }
        fail("Report text-field dialog did not open")
    }

    private fun viewport() = compose.onNodeWithTag("footer-regression-viewport").getUnclippedBoundsInRoot()

    private fun messageButton(label: String): SemanticsNodeInteraction = compose.onNode(hasClickAction() and
        (hasText(label) or hasContentDescription(label)))

    private fun touchSize(node: SemanticsNodeInteraction) {
        val bounds = node.assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("Actual clickable target>=48dp: $bounds", (bounds.right - bounds.left).value >= 48f && (bounds.bottom - bounds.top).value >= 48f)
    }

    private fun wholeText(text: String, container: DpRect): TextLayoutResult {
        val node = compose.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val diagnostics = "text=$text style=${layout.layoutInput.style} lines=${layout.lineCount} " +
            "constraints=${layout.layoutInput.constraints} size=${layout.size} container=$container"
        assertEquals(diagnostics, text, layout.layoutInput.text.text)
        assertFalse(diagnostics, layout.multiParagraph.didExceedMaxLines)
        assertEquals(diagnostics, text.length, layout.getLineEnd(layout.lineCount - 1))
        for (line in 0 until layout.lineCount) {
            assertFalse(diagnostics, layout.isLineEllipsized(line))
            assertTrue(diagnostics, layout.getLineLeft(line) >= -1f &&
                layout.getLineRight(line) <= layout.layoutInput.constraints.maxWidth + 1f)
            assertTrue(diagnostics, layout.getLineTop(line) >= -1f &&
                layout.getLineBottom(line) <= layout.size.height + 1f)
        }
        assertInside(node.getUnclippedBoundsInRoot(), container, diagnostics)
        return layout
    }

    private fun assertInside(inner: DpRect, outer: DpRect, reason: String) {
        assertTrue("$reason inner=$inner outer=$outer", inner.left >= outer.left && inner.right <= outer.right &&
            inner.top >= outer.top && inner.bottom <= outer.bottom)
    }

    private fun waitUntil(condition: () -> Boolean) = compose.waitUntil(15_000) {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        Snapshot.sendApplyNotifications()
        condition()
    }
}
