package com.yuldash.app

import android.Manifest
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Process
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuldash.app.ui.theme.YuldashTheme
import java.io.File
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Actual emulator MediaRecorder and AAC container; virtual audio, not a physical microphone/phone. */
@RunWith(AndroidJUnit4::class)
class VoiceRecordingDisposalInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext.applicationContext
    private val mounted = mutableStateOf(true)
    private val handedOff = mutableListOf<String>()
    private val files = mutableListOf<File>()
    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private val audio get() = context.getSystemService(AudioManager::class.java)

    @Before fun grant() {
        instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.RECORD_AUDIO}")
            .use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
    }
    @After fun cleanup() {
        compose.runOnIdle { mounted.value = false }; compose.waitForIdle()
        files.forEach { it.delete() }
    }
    private fun screenshot(language: String, phase: String, marker: String) {
        compose.waitForIdle()
        compose.onNodeWithText(marker).assertIsDisplayed()
        compose.onNodeWithText(marker).captureToImage()
        instrumentation.waitForIdleSync()
        // Allow the committed Compose frame to reach the window before UIAutomation captures pixels.
        Thread.sleep(150)
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            File(context.filesDir, "b02-voice-disposal-$language-$phase.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            bitmap.recycle()
        }
    }
    private fun scenario(request: Boolean, ba: Boolean) {
        val language = if (ba) "Ba" else "Ru"
        val sibling = File.createTempFile("voice_sibling_", ".m4a", context.cacheDir).apply { writeText("keep"); files += this }
        fun cacheFiles() = context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("voice_") }.toSet()
        val baseline = cacheFiles()
        compose.setContent {
            YuldashTheme {
                CompositionLocalProvider(LocalLifecycleOwner provides owner,
                    LocalAppLanguage provides if (ba) AppLanguage.Ba else AppLanguage.Ru) {
                    if (!mounted.value) Text(appText("Экран закрыт", "Экран ябылған"))
                    else if (request) VoiceRequestScreen(emptyList(), { mounted.value = false }, {})
                    // Match the real LazyColumn item constraints; quick replies must not consume a bounded Box height.
                    else LazyColumn(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                        item { ChatComposer("", {}, {}, { path, _ -> handedOff += path }) }
                    }
                }
            }
        }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        fun begin(): File {
            if (request) compose.onNodeWithText(if (ba) "Заявканы әйтеү" else "Сказать заявку").performClick()
            else compose.onNodeWithContentDescription(if (ba) "Тауыш яҙҙырыу" else "Записать голос").performClick()
            compose.waitUntil(10000) { (cacheFiles() - baseline).size == 1 && audio.activeRecordingConfigurations.isNotEmpty() }
            return (cacheFiles() - baseline).single().also { files += it }
        }
        fun inactive(file: File) {
            compose.waitUntil(10000) { !file.exists() && audio.activeRecordingConfigurations.isEmpty() }
            assertTrue(sibling.exists()); assertTrue(handedOff.isEmpty())
        }
        val paused = begin()
        screenshot(language, "recording", if (request) "Туҡта — әҙер" else "Идёт запись… отправить →")
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        inactive(paused)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        val abandoned = begin()
        assertNotEquals(paused.absolutePath, abandoned.absolutePath)
        compose.runOnIdle { mounted.value = false }; compose.waitForIdle()
        inactive(abandoned)
        compose.runOnIdle { mounted.value = true }; compose.waitForIdle()
        val completed = begin()
        // Real recorder needs audio frames before stop; this bounded wait supplies virtual emulator audio.
        Thread.sleep(1500)
        if (request) compose.onNodeWithText(if (ba) "Туҡта — әҙер" else "Стоп — готово").performClick()
        else compose.onNodeWithContentDescription(if (ba) "Яҙманы ебәреү" else "Отправить запись").performClick()
        compose.waitUntil(10000) { audio.activeRecordingConfigurations.isEmpty() && completed.length() > 0 }
        val duration = MediaMetadataRetriever().let {
            try { it.setDataSource(completed.absolutePath); requireNotNull(it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)).toLong() }
            finally { it.release() }
        }
        assertTrue("No recorded AAC duration", duration > 0)
        val mime = MediaExtractor().let {
            try { it.setDataSource(completed.absolutePath); assertEquals(1, it.trackCount); it.getTrackFormat(0).getString(MediaFormat.KEY_MIME) }
            finally { it.release() }
        }
        assertEquals("audio/mp4a-latm", mime)
        val completedBytes = completed.length()
        if (!request) assertEquals(completed.absolutePath, handedOff.single())
        screenshot(language, "completed", if (request) "Ҡабат яҙыу" else "Сообщение")
        compose.runOnIdle { mounted.value = false }; compose.waitForIdle()
        assertEquals("Completed file ownership", !request, completed.exists())
        assertTrue(sibling.exists()); assertTrue(audio.activeRecordingConfigurations.isEmpty())
        Log.i("B02VoiceDevice", "verified $language request=$request pid=${Process.myPid()} pause+unmount+remount AAC durationMs=$duration bytes=$completedBytes handedOff=${handedOff.size} sibling=true")
    }
    @Test fun chatRuReleasesOnPauseAndUnmountAndTransfersCompletedAac() = scenario(false, false)
    @Test fun requestBaReleasesOnPauseAndUnmountAndDeletesCompletedPreview() = scenario(true, true)
}
