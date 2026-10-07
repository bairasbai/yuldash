package com.yuldash.app

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/** Owns only the active recording. A successful stop transfers its file to the caller once. */
internal class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var path: String? = null
    var isClosed: Boolean = false
        private set

    fun start(): Boolean {
        if (isClosed || recorder != null) return false
        return try {
            path = File.createTempFile("voice_", ".m4a", context.cacheDir).absolutePath
            val candidate = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context)
                else @Suppress("DEPRECATION") MediaRecorder()
            // Own the candidate before configuration: prepare/start can fail too.
            recorder = candidate
            candidate.setAudioSource(MediaRecorder.AudioSource.MIC)
            candidate.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            candidate.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            candidate.setOutputFile(path)
            candidate.prepare()
            candidate.start()
            true
        } catch (_: Exception) {
            cancel()
            false
        }
    }

    fun stop(): String? {
        val active = recorder ?: return null
        val ownedPath = path
        recorder = null
        path = null
        val stopped = runCatching { active.stop() }.isSuccess
        val released = runCatching { active.release() }.isSuccess
        return if (stopped && released) ownedPath else {
            deleteOwnedFile(ownedPath)
            null
        }
    }

    /** Pause abandons unsent audio; a later foreground start may create a new recording. */
    fun cancel() {
        val active = recorder
        val ownedPath = path
        recorder = null
        path = null
        runCatching { active?.release() }
        deleteOwnedFile(ownedPath)
    }

    /** Disposal is permanent, including for a retained permission/click callback. */
    fun close() {
        isClosed = true
        cancel()
    }

    private fun deleteOwnedFile(ownedPath: String?) {
        if (ownedPath != null) runCatching { File(ownedPath).delete() }
    }
}
