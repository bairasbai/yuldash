package com.yuldash.app.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Temporary app-owned exports; separate paths prevent an old writer overwriting a new user. */
internal object PersonalDataExports {
    private const val LEGACY_NAME = "yuldash-my-data.txt"
    private const val LEGACY_PNG = "my_yuldash.png"
    private val ownName = Regex("(?:yuldash-my-data-[0-9a-f-]{36}\\.txt|my-yuldash-[0-9a-f-]{36}\\.png)")

    data class Prepared(val uri: Uri, val file: File) {
        fun discard() { runCatching { file.delete() } }
    }

    /** File I/O remains outside the session monitor. Publication has a separate session gate. */
    fun prepare(context: Context, displayName: String, text: String, generation: Long): Prepared? =
        prepareFile(context, displayName, generation, "yuldash-my-data-", ".txt") { it.writeText(text) }

    fun preparePng(context: Context, bitmap: Bitmap, generation: Long): Prepared? =
        prepareFile(context, LEGACY_PNG, generation, "my-yuldash-", ".png") { file ->
            FileOutputStream(file).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }

    private fun prepareFile(context: Context, displayName: String, generation: Long,
                            prefix: String, suffix: String, write: (File) -> Unit): Prepared? {
        if (!ApiClient.isCurrentSession(generation)) return null
        val dir = File(context.cacheDir, "shared")
        if (!ApiClient.isCurrentSession(generation)) return null
        check(dir.mkdirs() || dir.isDirectory)
        val file = File(dir, "$prefix${UUID.randomUUID()}$suffix")
        var keep = false
        try {
            write(file)
            if (!ApiClient.isCurrentSession(generation)) return null
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file, displayName)
            if (!ApiClient.isCurrentSession(generation)) return null
            keep = true
            return Prepared(uri, file)
        } finally {
            if (!keep || !ApiClient.isCurrentSession(generation)) runCatching { file.delete() }
        }
    }

    fun clear(context: Context) {
        val dir = File(context.cacheDir, "shared")
        // Revoke the legacy URI even when the file is absent: another user may reuse its path.
        val currentFiles = runCatching {
            dir.listFiles { file -> file.isFile && ownName.matches(file.name) }.orEmpty().toList()
        }.getOrDefault(emptyList())
        val files = listOf(File(dir, LEGACY_NAME), File(dir, LEGACY_PNG)) + currentFiles
        files.forEach { file ->
            runCatching {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            // A provider/grant failure must not skip deletion of the private copy.
            runCatching { file.delete() }
        }
    }
}
