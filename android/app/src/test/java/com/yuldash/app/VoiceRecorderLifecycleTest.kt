package com.yuldash.app

import android.app.Application
import android.content.Context
import android.media.MediaRecorder
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowMediaRecorder

/** Synthetic recorder bytes and injected Android failures; no physical microphone evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, shadows = [RecordingFailureShadow::class])
class VoiceRecorderLifecycleTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var recorder: VoiceRecorder

    @Before fun setup() {
        RecordingFailureShadow.resetFixture()
        recorder = VoiceRecorder(context)
    }

    @After fun cleanup() {
        RecordingFailureShadow.instances.forEach { it.forceRelease() }
        RecordingFailureShadow.files.forEach { it.delete() }
        RecordingFailureShadow.resetFixture()
    }

    @Test fun ordinaryStartStopTransfersExistingFile() {
        assertTrue(recorder.start())
        assertEquals(ShadowMediaRecorder.STATE_RECORDING, RecordingFailureShadow.instances.single().state)
        val path = requireNotNull(recorder.stop())
        assertTrue(File(path).exists())
        assertEquals(ShadowMediaRecorder.STATE_RELEASED, RecordingFailureShadow.instances.single().state)
    }

    @Test fun repeatedStartKeepsOneOwnedRecorder() {
        assertTrue(recorder.start())
        assertFalse(recorder.start())
        assertEquals(1, RecordingFailureShadow.instances.size)
        assertEquals(1, RecordingFailureShadow.files.size)
    }

    @Test fun stoppedFileIsTransferredOnlyOnce() {
        assertTrue(recorder.start())
        val path = requireNotNull(recorder.stop())
        assertNull(recorder.stop())
        assertTrue(File(path).exists())
    }

    @Test fun prepareFailureReleasesCandidateAndDeletesOwnedFile() = failedStart("prepare")
    @Test fun startFailureReleasesCandidateAndDeletesOwnedFile() = failedStart("start")
    @Test fun configurationFailureReleasesCandidateAndDeletesOwnedFile() = failedStart("encoder")

    private fun failedStart(point: String) {
        val before = context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("voice_") }.toSet()
        RecordingFailureShadow.failAt = point
        assertFalse(recorder.start())
        assertEquals(1, RecordingFailureShadow.instances.single().releaseAttempts)
        assertTrue(RecordingFailureShadow.files.none { it.exists() })
        assertEquals("Failed configuration left an owned temporary file", before,
            context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("voice_") }.toSet())
        RecordingFailureShadow.failAt = null
        assertTrue(recorder.start())
        assertNotNull(recorder.stop())
    }

    @Test fun stopFailureDeletesOwnedFileAndCannotReturnItAgain() {
        assertTrue(recorder.start())
        RecordingFailureShadow.failAt = "stop"
        assertNull(recorder.stop())
        assertEquals(1, RecordingFailureShadow.instances.single().releaseAttempts)
        assertTrue(RecordingFailureShadow.files.none { it.exists() })
        assertNull(recorder.stop())
    }

    @Test fun releaseFailureDoesNotEscapeAndDiscardsUnreleasedRecording() {
        assertTrue(recorder.start())
        RecordingFailureShadow.failAt = "release"
        assertNull(recorder.stop())
        assertEquals(1, RecordingFailureShadow.instances.single().releaseAttempts)
        assertTrue(RecordingFailureShadow.files.none { it.exists() })
        assertNull(recorder.stop())
    }

    @Test fun cancelIsIdempotentAndKeepsSiblingFile() {
        val sibling = File.createTempFile("voice_sibling_", ".m4a", context.cacheDir).apply { writeText("keep") }
        try {
            assertTrue(recorder.start())
            recorder.cancel(); recorder.cancel()
            assertEquals(1, RecordingFailureShadow.instances.single().releaseAttempts)
            assertTrue(RecordingFailureShadow.files.none { it.exists() })
            assertNull(recorder.stop()); assertTrue(sibling.exists())
        } finally { sibling.delete() }
    }
    @Test fun cancelAllowsRestartWithUniqueFileAtSameClockTime() {
        assertTrue(recorder.start())
        val first = RecordingFailureShadow.files.single()
        recorder.cancel()
        assertTrue(recorder.start())
        val second = RecordingFailureShadow.files.last()
        assertNotEquals(first.absolutePath, second.absolutePath)
        assertFalse(first.exists()); assertTrue(second.exists())
        assertNotNull(recorder.stop())
    }
    @Test fun closeIsPermanentAndIdempotent() {
        assertTrue(recorder.start())
        recorder.close(); recorder.close()
        assertTrue(recorder.isClosed)
        assertFalse(recorder.start()); assertNull(recorder.stop())
        assertEquals(1, RecordingFailureShadow.instances.single().releaseAttempts)
        assertTrue(RecordingFailureShadow.files.none { it.exists() })
    }
    @Test fun closeAfterSuccessfulTransferKeepsCallersFile() {
        assertTrue(recorder.start())
        val file = File(requireNotNull(recorder.stop()))
        recorder.close(); recorder.cancel()
        assertTrue(file.exists()); assertNull(recorder.stop())
        assertEquals(1, RecordingFailureShadow.instances.single().releaseAttempts)
    }
    @Test fun failedStartAndFailedReleaseStillClearPathWithoutThrowing() {
        RecordingFailureShadow.failAt = "start+release"
        assertFalse(recorder.start())
        assertEquals(1, RecordingFailureShadow.instances.single().releaseAttempts)
        assertTrue(RecordingFailureShadow.files.none { it.exists() })
        assertNull(recorder.stop())
        RecordingFailureShadow.failAt = null
        assertTrue(recorder.start()); assertNotNull(recorder.stop())
    }
}

@Implements(MediaRecorder::class)
class RecordingFailureShadow : ShadowMediaRecorder() {
    var releaseAttempts = 0
    companion object {
        var failAt: String? = null
        val instances = mutableListOf<RecordingFailureShadow>()
        val files = mutableListOf<File>()
        fun resetFixture() { failAt = null; instances.clear(); files.clear() }
    }

    private fun fail(point: String) { if (point in failAt.orEmpty().split('+')) throw IllegalStateException("injected $point") }

    @Implementation override fun setAudioSource(source: Int) {
        if (this !in instances) instances += this
        super.setAudioSource(source)
    }
    @Implementation override fun setAudioEncoder(encoder: Int) { fail("encoder"); super.setAudioEncoder(encoder) }
    @Implementation override fun setOutputFile(path: String) {
        super.setOutputFile(path)
        File(path).also { it.writeBytes(byteArrayOf(1, 2, 3)); files += it }
    }
    @Implementation override fun prepare() { fail("prepare"); super.prepare() }
    @Implementation override fun start() { fail("start"); super.start() }
    @Implementation override fun stop() { fail("stop"); super.stop() }
    @Implementation override fun release() { releaseAttempts++; fail("release"); super.release() }
    fun forceRelease() { super.release() }
}
