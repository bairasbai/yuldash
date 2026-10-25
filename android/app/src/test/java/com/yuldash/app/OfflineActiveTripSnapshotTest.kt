package com.yuldash.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.drawToBitmap
import android.view.View
import java.util.concurrent.atomic.AtomicReference
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
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
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

/** Synthetic encrypted-model fixture; real Compose/lifecycle/expiry, no network or location. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w320dp-h1800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OfflineActiveTripSnapshotTest {
    @get:Rule val compose = createComposeRule()
    private class Host : LifecycleOwner { override val lifecycle = LifecycleRegistry(this) }
    private val host = Host()
    private val mounted = mutableStateOf(true)
    private val mode = mutableStateOf<OfflineTripSnapshotMode?>(null)
    private val language = mutableStateOf(AppLanguage.Ru)
    private val dark = mutableStateOf(false)
    private val own = SnapshotOwner(7, UUID.randomUUID().toString(), ApiClient.queueSessionGeneration())
    private val stamp = SnapshotClock(3, 1000, 1000, 1000)
    @Volatile private var now: SnapshotClock? = stamp
    private var retries = 0
    private var background = Color.Unspecified
    private var textInk = Color.Unspecified
    private val renderedView = AtomicReference<View>()
    private val disk = SnapshotTestDisk()
    private val snapshots = mutableListOf<OfflineTripSnapshot>()
    @Before fun setup() = runBlocking {
        OfflineTripSnapshots.installForTest(disk) { now }
        OfflineTripSnapshots.activate(own.userId, own.scope, own.generation)
        for ((index, role) in listOf("passenger", "driver").withIndex()) {
            val state = SnapshotState(role, "onboard", onboardAt = "2026-10-24T12:00:00Z")
            val ticket = OfflineTripSnapshots.ticket()!!
            val repo = OfflineTripSnapshotRepository(disk, java.util.concurrent.atomic.AtomicReference<SnapshotOwner?>(own), { stamp })
            repo.observe(repo.ticket()!!, 11 + index, state, 22,
                SnapshotRoute("Уфа", "Мелеуз", "2026-10-25T10:00:00", "Стерлитамак", "Салават", true))
            snapshots += OfflineTripSnapshots.load(ticket.owner, 11 + index)!!
        }
        host.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        host.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        host.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }
    @After fun cleanup() {
        compose.runOnIdle { mounted.value = false }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        OfflineTripSnapshots.resetForTest()
    }
    private fun mount(index: Int = 0) {
        mode.value = OfflineTripSnapshotMode(snapshots[index], 1)
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(LocalAppLanguage provides language.value,
                LocalNightRide provides dark.value, LocalLifecycleOwner provides host) {
                YuldashTheme(darkTheme = dark.value) {
                    val bg = CanonBg
                    val ink = CanonText
                    val view = LocalView.current
                    SideEffect { background = bg; textInk = ink; renderedView.set(view) }
                    OfflineActiveTripSnapshot(mode.value!!, { true }, { retries++ }, {})
                }
            }
        }
    }
    private fun waitText(text: String) = compose.waitUntil(10000) {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }
    private fun noStage() = compose.waitUntil(10000) {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        compose.onAllNodesWithText("Последнее состояние:", substring = true).fetchSemanticsNodes().isEmpty() &&
            compose.onAllNodesWithText("Һуңғы хәл:", substring = true).fetchSemanticsNodes().isEmpty()
    }
    @Test fun bothRolesBothLanguagesBothThemesShowOwnStopsAndOnlyReadOnlyActions() {
        mount()
        for (index in snapshots.indices) for (lang in AppLanguage.entries) for (night in listOf(false, true)) {
            compose.runOnIdle { mode.value = OfflineTripSnapshotMode(snapshots[index], 1); language.value = lang; dark.value = night }
            val title = if (lang == AppLanguage.Ru) "Последние сохранённые данные" else "Һуңғы һаҡланған мәғлүмәт"
            val stage = if (lang == AppLanguage.Ru) "Последнее состояние:" else "Һуңғы хәл:"
            waitText(stage)
            compose.onNodeWithText(title).assertExists()
            compose.onAllNodesWithText("Стерлитамак", substring = true).onFirst().assertExists()
            compose.onAllNodesWithText("Салават", substring = true).onFirst().assertExists()
            for (forbidden in listOf("Позвонить", "Шылтыратырға", "Код посадки", "Ултырыу коды", "Я выехал", "Подъезжаю", "Завершить поездку", "Отменить бронь"))
                compose.onAllNodesWithText(forbidden).assertCountEquals(0)
        }
    }
    @Test fun activeDeadlineRemovesStageWithoutGetOrUserInteraction() {
        val age = OfflineTripSnapshotPolicy.MAX_AGE_MS - 350
        now = stamp.copy(wall = stamp.wall + age, before = stamp.before + age, after = stamp.after + age)
        mount(); waitText("Последнее состояние:")
        val end = stamp.wall + OfflineTripSnapshotPolicy.MAX_AGE_MS
        now = stamp.copy(wall = end, before = end, after = end)
        noStage()
        assertEquals(0, retries)
    }
    @Test fun pauseHidesDataAndResumeAfterDeadlineCannotRestoreOldStage() {
        mount(); waitText("Последнее состояние:")
        compose.runOnIdle { host.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE) }
        noStage()
        val end = stamp.wall + OfflineTripSnapshotPolicy.MAX_AGE_MS
        now = stamp.copy(wall = end, before = end, after = end)
        compose.runOnIdle { host.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME) }
        noStage(); assertEquals(0, retries)
    }
    @Test fun explicitWallClockChangeAndAccountChangeBothHideAlreadyShownStage() {
        mount(); waitText("Последнее состояние:")
        ApplicationProvider.getApplicationContext<Context>().sendBroadcast(Intent(Intent.ACTION_TIME_CHANGED))
        noStage()
        compose.runOnIdle { mode.value = OfflineTripSnapshotMode(snapshots[1], 2) }
        waitText("Последнее состояние:")
        OfflineTripSnapshots.activate(8, UUID.randomUUID().toString(), 2)
        noStage()
    }
    @Test fun acceptedTerminalInvalidatesTheDisplayedVersionBeforeTheNextIdleFrame() {
        mount(); waitText("Последнее состояние:")
        val area = compose.onNodeWithText("Последнее состояние: Пассажир сел").fetchSemanticsNode().boundsInRoot
        assertStageInkPresent(area)
        compose.runOnIdle { runBlocking { OfflineTripSnapshots.close(OfflineTripSnapshots.ackTicket(), 11) } }
        compose.waitForIdle()
        compose.onAllNodesWithText("Последнее состояние:", substring = true).assertCountEquals(0)
        assertStagePixelsCleared(area)
        assertEquals(0, retries)
    }
    @Test fun actualAcceptedQueuedDoneClearsStageSemanticsAndPixelsAtTheNextIdleFrame() {
        mount(); waitText("Последнее состояние:")
        val area = compose.onNodeWithText("Последнее состояние: Пассажир сел").fetchSemanticsNode().boundsInRoot
        assertStageInkPresent(area)
        val server = MockWebServer().apply { start(); enqueue(MockResponse().setBody("[]")) }
        val oldUrl = ApiClient.testBaseUrl
        val oldTimeout = ApiClient.testTimeoutMs
        try {
            ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/'); ApiClient.testTimeoutMs = 500
            compose.runOnIdle { runBlocking {
                assertTrue(ApiClient.sendQueuedAction(OutboxAction(1, 11, "trip_status", "done", 1000), own.generation).isSuccess)
            } }
            compose.waitForIdle()
            compose.onAllNodesWithText("Последнее состояние:", substring = true).assertCountEquals(0)
            assertStagePixelsCleared(area)
            assertEquals(1, server.requestCount); assertEquals(0, retries)
        } finally { ApiClient.testBaseUrl = oldUrl; ApiClient.testTimeoutMs = oldTimeout; server.shutdown() }
    }
    private fun assertStagePixelsCleared(area: Rect) {
        // WindowCapture.forceRedraw waits for real PixelCopy callbacks absent in Robolectric.
        // Native View.drawToBitmap is the project's StatusBarProtectionTest capture path.
        val pixels = compose.runOnIdle { renderedView.get().drawToBitmap() }
        val expected = background.toArgb()
        for (x in area.left.toInt() until area.right.toInt()) for (y in area.top.toInt() until area.bottom.toInt())
            assertEquals("The former stage rectangle must contain only the screen background", expected, pixels.getPixel(x, y))
    }
    private fun assertStageInkPresent(area: Rect) {
        val pixels = compose.runOnIdle { renderedView.get().drawToBitmap() }
        val ink = textInk.toArgb()
        var actualInk = false
        for (x in area.left.toInt() until area.right.toInt()) for (y in area.top.toInt() until area.bottom.toInt())
            if (pixels.getPixel(x, y) == ink) actualInk = true
        assertTrue("Native capture must contain actual stage TEXT ink before ack, not just its card background", actualInk)
    }
}
