package com.yuldash.app

import android.app.Application
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yuldash.app.data.*
import com.yuldash.app.ui.theme.YuldashTheme
import kotlinx.coroutines.runBlocking
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
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Legacy-callable regression: production screen, banner, real disk model and resumed lifecycle.
 * Viewport/insets are the captured 1080x2340 profile: status136px, navigation80px.
 * Test-only inset emulation is explicit because Robolectric supplies zero system insets.
 * No scroll, alternate production rendering, new optional parameter or reduced font scale.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w320dp-h694dp-540dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OfflineActiveTripSnapshotResponsiveTest {
    @get:Rule val compose = createComposeRule()
    private class Host : LifecycleOwner { override val lifecycle = LifecycleRegistry(this) }
    private val host = Host()
    private val mounted = mutableStateOf(true)
    private val disk = SnapshotTestDisk()
    private val stamp = SnapshotClock(3, 1000, 1000, 1000)
    private lateinit var snapshot: OfflineTripSnapshot
    private var retries = 0
    private var backs = 0
    private lateinit var viewportDensity: Density

    @Before fun setup() = runBlocking {
        ApiClient.resetForTest()
        val own = SnapshotOwner(7, UUID.randomUUID().toString(), ApiClient.queueSessionGeneration())
        OfflineTripSnapshots.installForTest(disk) { stamp }
        OfflineTripSnapshots.activate(own.userId, own.scope, own.generation)
        val repo = OfflineTripSnapshotRepository(disk, AtomicReference<SnapshotOwner?>(own), { stamp })
        repo.observe(repo.ticket()!!, 105, SnapshotState("passenger", "onboard",
            onboardAt = "2026-10-28T22:30:00Z"), 22,
            SnapshotRoute("Уфа", "Стерлитамак", "2026-10-28T22:30:00", "", "", false))
        snapshot = requireNotNull(OfflineTripSnapshots.load(own, 105))
        host.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        host.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        host.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        ApiClient.serverUnreachable.value = true
    }

    @After fun cleanup() {
        compose.runOnIdle { mounted.value = false }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        ApiClient.serverUnreachable.value = false
        OfflineTripSnapshots.resetForTest()
        ApiClient.resetForTest()
    }

    @Test fun russianLightFirstColdFrameContainsAllRequiredText() = firstFrame(AppLanguage.Ru, false)
    @Test fun russianDarkFirstColdFrameContainsAllRequiredText() = firstFrame(AppLanguage.Ru, true)
    @Test fun bashkirLightFirstColdFrameContainsAllRequiredText() = firstFrame(AppLanguage.Ba, false)
    @Test fun bashkirDarkFirstColdFrameContainsAllRequiredText() = firstFrame(AppLanguage.Ba, true)

    private fun firstFrame(language: AppLanguage, dark: Boolean) {
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(LocalAppLanguage provides language,
                LocalNightRide provides dark, LocalLifecycleOwner provides host,
                LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                YuldashTheme(darkTheme = dark) {
                    // Same flow host as YuldashApp:1146-1170: banner then weighted content,
                    // consume status inset already owned by first visible banner. No UpdateBanner
                    // was visible in FAIL-android.png. Offline route mounts this production screen
                    // directly (OfflineTripSnapshotNavigation:59).
                    val density = LocalDensity.current
                    SideEffect { viewportDensity = density }
                    check(WindowInsets.statusBars.getTop(density) == 0 &&
                        WindowInsets.navigationBars.getBottom(density) == 0) {
                        "Synthetic inset host requires zero platform insets; avoid double accounting"
                    }
                    Box(Modifier.requiredSize(320.dp, (2340f / (1080f / 320f)).dp)
                        .testTag("cold-window")) {
                        Column(Modifier.fillMaxSize().background(CanonBg)
                            .padding(top = (136f / (1080f / 320f)).dp,
                                bottom = (80f / (1080f / 320f)).dp)) {
                            ConnectionBanner(Modifier.align(Alignment.CenterHorizontally))
                            Box(Modifier.weight(1f).consumeWindowInsets(WindowInsets.statusBars)) {
                                OfflineActiveTripSnapshot(OfflineTripSnapshotMode(snapshot, 1),
                                    { true }, { retries++ }, { backs++ })
                            }
                        }
                    }
                }
            }
        }
        val stagePrefix = if (language == AppLanguage.Ru) "Последнее состояние:" else "Һуңғы хәл:"
        // Await actual lifecycle/cache load, never scroll or extend the production TTL.
        compose.waitUntil(10000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithText(stagePrefix, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        // Settle finite Compose banner/fade animations only; do not advance its10000ms health timer.
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        val window = compose.onNodeWithTag("cold-window").getUnclippedBoundsInRoot()
        assertEquals(3.375f, viewportDensity.density, 0f)
        assertEquals(1080, with(viewportDensity) { (window.right - window.left).roundToPx() })
        assertEquals(2340, with(viewportDensity) { (window.bottom - window.top).roundToPx() })
        println("cold-profile density=${viewportDensity.density} fontScale=${viewportDensity.fontScale} window=$window")
        assertEquals(320f, (window.right - window.left).value, 0.1f)
        val expectedHeight = with(viewportDensity) { (2340f / (1080f / 320f)).dp.roundToPx().toDp().value }
        assertEquals(expectedHeight, (window.bottom - window.top).value, 0f)
        val labels = if (language == AppLanguage.Ru) listOf(
            "Последние сохранённые данные",
            "Показываем сохранённую поездку. Состояние могло измениться.",
            "Ты пассажир", "Уфа → Стерлитамак",
            "Посадка: начало маршрута · Уфа", "Высадка: конец маршрута · Стерлитамак",
            "Последнее состояние: Пассажир сел") else listOf(
            "Һуңғы һаҡланған мәғлүмәт",
            "Һаҡланған сәфәрҙе күрһәтәбеҙ. Хәл үҙгәргән булыуы мөмкин.",
            "Һин пассажир", "Уфа → Стерлитамак",
            "Ултырыу: юлдың башы · Уфа", "Төшөү: юлдың аҙағы · Стерлитамак",
            "Һуңғы хәл: Пассажир ултырҙы")
        for (label in labels) wholeText(compose.onNodeWithText(label, useUnmergedTree = true), window)
        val timePrefix = if (language == AppLanguage.Ru) "Сохранено:" else "Һаҡланған ваҡыт:"
        wholeText(compose.onNode(hasText(timePrefix, substring = true), useUnmergedTree = true), window)
        assertEquals(0, retries)
        assertEquals(0, backs)
        // Screen remains read-only. Existing privacy/expiry tests verify actual invalidation.
        val allowed = if (language == AppLanguage.Ru) listOf("Назад", "Повторить") else listOf("Артҡа", "Ҡабатлау")
        val controls = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        assertEquals("Only source-reviewed Back/Retry actions", 2, controls.size)
        for (label in allowed) compose.onNode(hasClickAction() and
            (hasText(label) or hasContentDescription(label))).assertExists()
    }

    private fun wholeText(node: SemanticsNodeInteraction, window: DpRect) {
        val bounds = node.getUnclippedBoundsInRoot()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val diagnostic = "bounds=$bounds window=$window layout=${layout.size} lines=${layout.lineCount} " +
            "density=${layout.layoutInput.density} constraints=${layout.layoutInput.constraints} " +
            "overflowWidth=${layout.didOverflowWidth} overflowHeight=${layout.didOverflowHeight} " +
            "paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}"
        assertEquals(diagnostic, 1.3f, layout.layoutInput.density.fontScale, 0f)
        // A paragraph may keep the wider wrapping canvas while the Text node uses intrinsic width.
        // Check actual laid-out lines and complete source coverage, never the empty canvas width.
        assertTrue(diagnostic, layout.lineCount > 0)
        assertFalse(diagnostic, layout.didOverflowHeight)
        assertFalse(diagnostic, layout.multiParagraph.didExceedMaxLines)
        assertEquals(diagnostic, layout.layoutInput.text.length,
            layout.getLineEnd(layout.lineCount - 1, visibleEnd = false))
        for (line in 0 until layout.lineCount) {
            val lineDiagnostic = "$diagnostic line=$line edges=${layout.getLineLeft(line)},${layout.getLineTop(line)},${layout.getLineRight(line)},${layout.getLineBottom(line)}"
            assertFalse(lineDiagnostic, layout.isLineEllipsized(line))
            assertTrue(lineDiagnostic, layout.getLineLeft(line) >= 0f)
            assertTrue(lineDiagnostic, layout.getLineRight(line) <= layout.size.width.toFloat())
            assertTrue(lineDiagnostic, layout.getLineTop(line) >= 0f)
            assertTrue(lineDiagnostic, layout.getLineBottom(line) <= layout.size.height.toFloat())
        }
        assertTrue(diagnostic, bounds.left >= window.left && bounds.right <= window.right)
        assertTrue(diagnostic, bounds.top >= window.top + (window.bottom - window.top) * 0.03f)
        assertTrue(diagnostic, bounds.bottom < window.top + (window.bottom - window.top) * 0.96f)
        node.assertIsDisplayed()
    }

    /** Runs on old/new API unchanged; live default must retain its full segment label. */
    @Test fun liveDefaultStillShowsSegmentHeadingAndBothCanonicalStops() {
        compose.setContent { CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
            YuldashTheme { BookingSegmentNote("Стерлитамак", "Салават", true, "Уфа", "Мелеуз") }
        } }
        compose.onNodeWithText("Участок: Стерлитамак → Салават").assertExists()
        compose.onNodeWithText("Посадка: Стерлитамак").assertExists()
        compose.onNodeWithText("Высадка: Салават").assertExists()
    }
}
