package com.yuldash.app

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowMediaRecorder

/** Actual Compose semantic clicks/disposal; recorder bytes and failures are synthetic. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, shadows = [RecordingFailureShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VoiceRecordingDisposalUiTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mounted = mutableStateOf(true)
    private val allowed = mutableStateOf(true)
    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private val currentOwner = mutableStateOf<LifecycleOwner>(owner)
    private val handedOff = mutableListOf<String>()
    private lateinit var adjacent: File
    private var permissionCode: Int? = null
    private val permissionOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
                options: ActivityOptionsCompat?) {
                assertEquals(Manifest.permission.RECORD_AUDIO, input)
                permissionCode = requestCode
            }
        }
    }

    @Before fun setup() {
        RecordingFailureShadow.resetFixture()
        Shadows.shadowOf(context as Application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        adjacent = File.createTempFile("voice_sibling_", ".m4a", context.cacheDir).apply { writeText("keep") }
    }
    @After fun cleanup() {
        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
        RecordingFailureShadow.instances.forEach { it.forceRelease() }
        RecordingFailureShadow.files.forEach { it.delete() }
        adjacent.delete()
        RecordingFailureShadow.resetFixture()
    }

    private fun mount(request: Boolean, ba: Boolean = false) {
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(LocalLifecycleOwner provides currentOwner.value,
                LocalActivityResultRegistryOwner provides permissionOwner,
                LocalAppLanguage provides if (ba) AppLanguage.Ba else AppLanguage.Ru) {
                if (request) VoiceRequestScreen(emptyList(), { mounted.value = false }, {})
                else Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    ChatComposer("", {}, {}, { path, _ -> handedOff += path }, recordingAllowed = allowed.value)
                }
            }
        }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
    }
    private fun start(request: Boolean, ba: Boolean = false) {
        if (request) compose.onNodeWithText(if (ba) "Заявканы әйтеү" else "Сказать заявку").performSemanticsAction(SemanticsActions.OnClick) { it() }
        else compose.onNodeWithContentDescription(if (ba) "Тауыш яҙҙырыу" else "Записать голос").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitForIdle()
        assertEquals(ShadowMediaRecorder.STATE_RECORDING, RecordingFailureShadow.instances.last().state)
        assertTrue(RecordingFailureShadow.files.last().exists())
    }
    private fun finish(request: Boolean, ba: Boolean = false) {
        if (request) compose.onNodeWithText(if (ba) "Туҡта — әҙер" else "Стоп — готово").performSemanticsAction(SemanticsActions.OnClick) { it() }
        else compose.onNodeWithContentDescription(if (ba) "Яҙманы ебәреү" else "Отправить запись").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitForIdle()
    }
    private fun dispose(request: Boolean, ba: Boolean = false) {
        mount(request, ba); start(request, ba)
        compose.runOnIdle { mounted.value = false }; compose.waitForIdle()
        assertEquals(ShadowMediaRecorder.STATE_RELEASED, RecordingFailureShadow.instances.single().state)
        assertFalse(RecordingFailureShadow.files.single().exists())
        assertTrue(adjacent.exists()); assertTrue(handedOff.isEmpty())
    }
    private fun pause(request: Boolean) {
        mount(request); start(request)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }; compose.waitForIdle()
        assertEquals(ShadowMediaRecorder.STATE_RELEASED, RecordingFailureShadow.instances.single().state)
        assertFalse(RecordingFailureShadow.files.single().exists())
        assertTrue(handedOff.isEmpty()); assertTrue(adjacent.exists())
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        start(request)
        assertEquals(2, RecordingFailureShadow.instances.size)
        finish(request)
        assertTrue(RecordingFailureShadow.files.last().exists())
    }
    @Test fun chatUnmountReleasesRecordingRu() = dispose(false)
    @Test fun chatUnmountReleasesRecordingBa() = dispose(false, true)
    @Test fun requestUnmountReleasesRecordingRu() = dispose(true)
    @Test fun requestUnmountReleasesRecordingBa() = dispose(true, true)
    @Test fun chatPauseCancelsAndResumeCanRecordAgain() = pause(false)
    @Test fun requestPauseCancelsAndResumeCanRecordAgain() = pause(true)
    @Test fun chatOrdinaryFinishTransfersFileAndUnmountPreservesIt() {
        mount(false); start(false); finish(false)
        val file = File(handedOff.single()); assertTrue(file.exists())
        compose.runOnIdle { mounted.value = false }; compose.waitForIdle()
        assertTrue(file.exists()); assertTrue(adjacent.exists())
    }
    @Test fun requestFinishedPreviewSurvivesPauseButUnmountDeletesIt() {
        mount(true); start(true); finish(true)
        val file = RecordingFailureShadow.files.single()
        compose.onNodeWithText("Записать заново").assertExists()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }; compose.waitForIdle()
        assertTrue(file.exists())
        compose.runOnIdle { mounted.value = false }; compose.waitForIdle()
        assertFalse(file.exists()); assertTrue(adjacent.exists())
    }

    private fun permission(request: Boolean, boundary: String) {
        Shadows.shadowOf(context as Application).denyPermissions(Manifest.permission.RECORD_AUDIO)
        mount(request)
        if (request) compose.onNodeWithText("Сказать заявку").performSemanticsAction(SemanticsActions.OnClick) { it() }
        else compose.onNodeWithContentDescription("Записать голос").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Разрешить").performSemanticsAction(SemanticsActions.OnClick) { it() }
        assertNotNull(permissionCode)
        compose.runOnIdle {
            if (boundary == "pause") owner.registry.currentState = Lifecycle.State.STARTED
            if (boundary == "unmount") mounted.value = false
        }
        compose.waitForIdle()
        compose.runOnIdle { permissionOwner.activityResultRegistry.dispatchResult(permissionCode!!, true) }
        compose.waitForIdle()
        if (boundary == "active") assertEquals(ShadowMediaRecorder.STATE_RECORDING, RecordingFailureShadow.instances.single().state)
        else assertTrue("Late permission started a recorder", RecordingFailureShadow.instances.isEmpty())
    }
    @Test fun chatActualPermissionGrantStartsWhileForeground() = permission(false, "active")
    @Test fun requestActualPermissionGrantStartsWhileForeground() = permission(true, "active")
    @Test fun chatActualPermissionGrantWhilePausedDoesNotStart() = permission(false, "pause")
    @Test fun requestActualPermissionGrantWhilePausedDoesNotStart() = permission(true, "pause")
    @Test fun chatActualPermissionGrantAfterUnmountIsUnregistered() = permission(false, "unmount")
    @Test fun requestActualPermissionGrantAfterUnmountIsUnregistered() = permission(true, "unmount")

    private fun retainedMic(request: Boolean) {
        mount(request)
        val node = if (request) compose.onNodeWithText("Сказать заявку") else compose.onNodeWithContentDescription("Записать голос")
        val savedClick = node.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { mounted.value = false }; compose.waitForIdle()
        // Deliberate retained callback injection, not a physical tap on a removed screen.
        compose.runOnIdle { savedClick() }
        assertTrue("Retained click restarted a disposed recorder", RecordingFailureShadow.instances.isEmpty())
    }
    @Test fun chatRetainedMicClickCannotRestartAfterUnmount() = retainedMic(false)
    @Test fun requestRetainedMicClickCannotRestartAfterUnmount() = retainedMic(true)

    @Test fun chatDisableCancelsRecordingAndReenableStartsFresh() {
        mount(false); start(false)
        val first = RecordingFailureShadow.files.single()
        compose.runOnIdle { allowed.value = false }; compose.waitForIdle()
        assertFalse(first.exists())
        assertEquals(ShadowMediaRecorder.STATE_RELEASED, RecordingFailureShadow.instances.single().state)
        compose.onNodeWithContentDescription("Записать голос").assertIsNotEnabled()
        compose.runOnIdle { allowed.value = true }; compose.waitForIdle()
        start(false); finish(false)
        assertNotEquals(first.absolutePath, handedOff.single()); assertTrue(adjacent.exists())
    }
    private fun replaceOwner(request: Boolean) {
        mount(request); start(request)
        val first = RecordingFailureShadow.files.single()
        val replacement = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        compose.runOnIdle { replacement.registry.currentState = Lifecycle.State.RESUMED; currentOwner.value = replacement }
        compose.waitForIdle()
        assertFalse(first.exists())
        assertEquals(ShadowMediaRecorder.STATE_RELEASED, RecordingFailureShadow.instances.single().state)
        start(request); finish(request)
        assertTrue(RecordingFailureShadow.files.last().exists()); assertTrue(adjacent.exists())
    }
    @Test fun chatOwnerReplacementClosesOldRecorderAndResetsUi() = replaceOwner(false)
    @Test fun requestOwnerReplacementClosesOldRecorderAndResetsUi() = replaceOwner(true)
}
