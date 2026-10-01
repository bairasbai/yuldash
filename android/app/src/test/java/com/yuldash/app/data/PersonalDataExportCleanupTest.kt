package com.yuldash.app.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Logout's actual file cleanup and ownership boundaries; OS grants have device evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PersonalDataExportCleanupTest {
    private lateinit var context: Application
    private val ownFiles = mutableListOf<File>()

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        ApiClient.resetForTest()
        ApiClient.testBaseUrl = "http://127.0.0.1:1"
        ApiClient.testTimeoutMs = 50
        ApiClient.init(context)
        ApiClient.logout()
        ApiClient.saveToken("QA-export-cleanup-account-A")
    }

    @After fun cleanup() {
        ApiClient.logout()
        ownFiles.forEach { it.delete() }
        ApiClient.resetForTest()
    }

    private fun file(name: String, text: String) = File(context.cacheDir, "shared/$name").apply {
        parentFile!!.mkdirs()
        writeText(text)
        ownFiles.add(this)
    }

    @Test fun logoutErasesAllOwnCopiesAndPreservesUnrelatedSimilarNames() {
        val legacy = file("yuldash-my-data.txt", "QA_PRIVATE_LEGACY")
        val first = file("yuldash-my-data-12345678-1234-1234-1234-123456789abc.txt", "QA_PRIVATE_ONE")
        val second = file("yuldash-my-data-87654321-1234-1234-1234-cba987654321.txt", "QA_PRIVATE_TWO")
        val legacyPng = file("my_yuldash.png", "QA_PRIVATE_PNG_LEGACY")
        val firstPng = file("my-yuldash-12345678-1234-1234-1234-123456789abc.png", "QA_PRIVATE_PNG_ONE")
        val secondPng = file("my-yuldash-87654321-1234-1234-1234-cba987654321.png", "QA_PRIVATE_PNG_TWO")
        val notes = file("yuldash-my-data-user-notes.txt", "QA_UNRELATED_NOTES")
        val pngNotes = file("my-yuldash-user-notes.png", "QA_UNRELATED_PNG_NOTES")
        val neutral = file("qa-unrelated-image.png", "QA_UNRELATED_IMAGE")

        ApiClient.logout()

        assertFalse(ApiClient.isLoggedIn())
        listOf(legacy, first, second, legacyPng, firstPng, secondPng).forEach {
            assertFalse("private export survives logout: ${it.name}", it.exists())
        }
        assertEquals("QA_UNRELATED_NOTES", notes.readText())
        assertEquals("QA_UNRELATED_PNG_NOTES", pngNotes.readText())
        assertEquals("QA_UNRELATED_IMAGE", neutral.readText())
    }

    @Test fun rejectedOldPreparationCannotOverwriteOrDeleteAnotherAccountsCopy() {
        val generationA = ApiClient.queueSessionGeneration()
        ApiClient.logout()
        ApiClient.saveToken("QA-export-cleanup-account-B")
        val current = file("yuldash-my-data-87654321-1234-1234-1234-cba987654321.txt", "QA_PRIVATE_CURRENT_B")
        val before = current.parentFile!!.listFiles()!!.map { it.name }.toSet()

        val result = PersonalDataExports.prepare(context, "yuldash-my-data.txt", "QA_REJECTED_OLD_A", generationA)

        assertNull(result)
        assertEquals("QA-export-cleanup-account-B", ApiClient.currentToken())
        assertEquals("QA_PRIVATE_CURRENT_B", current.readText())
        assertEquals(before, current.parentFile!!.listFiles()!!.map { it.name }.toSet())
    }
}
