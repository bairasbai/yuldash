package com.yuldash.app.data

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real FileProvider/OS grants and logout; actual screen/share intent has a separate Android journey. */
@RunWith(AndroidJUnit4::class)
class MyDataExportPrivacyInstrumentedTest {
    private fun shellRead(uri: Uri, quoted: Boolean = false): String {
        val value = uri.toString()
        require(value.matches(Regex("content://[A-Za-z0-9._/%?=-]+")))
        // UiAutomationConnection calls Runtime.exec, which does not strip shell quotes.
        // The whitelist and encoded URI permit one argument without shell interpretation.
        val argument = if (quoted) "'$value'" else value
        val descriptors = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommandRwe("content read --uri $argument")
        try {
            descriptors[1].close()
            val output = android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptors[0])
                .bufferedReader().use { it.readText() }
            val stderr = android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptors[2])
                .bufferedReader().use { it.readText() }
            println("QA021_READ quoted=$quoted syntheticReadable=${output.contains("QA_PRIVATE") || output.contains("QA_NEUTRAL")} " +
                "stderr=${stderr.replace(value, "<synthetic-uri>").take(400)}")
            return output
        } finally {
            descriptors.forEach { runCatching { it.close() } }
        }
    }

    private fun checkFiles(checkGrant: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val originalUrl = ApiClient.testBaseUrl
        val originalTimeout = ApiClient.testTimeoutMs
        ApiClient.resetForTest()
        ApiClient.testBaseUrl = "http://127.0.0.1:1"
        ApiClient.testTimeoutMs = 500
        ApiClient.init(context)
        ApiClient.logout()
        ApiClient.saveToken("QA-export-device-account-A")
        val file = File(context.cacheDir, "shared/yuldash-my-data.txt")
        val neutral = File(context.cacheDir, "shared/qa-export-neutral.txt")
        assertFalse("fixture refuses to overwrite an unrelated export", file.exists())
        assertFalse("fixture refuses to overwrite an unrelated neutral file", neutral.exists())
        file.parentFile!!.mkdirs()
        file.writeText("QA_PRIVATE_EXPORT_A")
        neutral.writeText("QA_NEUTRAL_FILE")
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, file)
        val neutralUri = FileProvider.getUriForFile(context, authority, neutral)
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        try {
            context.grantUriPermission("com.android.shell", uri, flags)
            context.grantUriPermission("com.android.shell", neutralUri, flags)
            val shellUid = context.packageManager.getApplicationInfo("com.android.shell", 0).uid
            assertTrue(shellUid != Process.myUid() && shellUid != 0)
            assertEquals(PackageManager.PERMISSION_GRANTED, context.checkUriPermission(uri, Process.myPid(), shellUid, flags))
            // Preserve actual stderr for the previous stand failure before the real read.
            shellRead(uri, quoted = true)
            assertTrue("actual recipient must read synthetic A before logout", shellRead(uri).contains("QA_PRIVATE_EXPORT_A"))
            ApiClient.logout()
            val existsAfterLogout = file.exists()
            val oldDataReadable = shellRead(uri).contains("QA_PRIVATE_EXPORT_A")
            val grantAfterLogout = context.checkUriPermission(uri, Process.myPid(), shellUid, flags)
            ApiClient.saveToken("QA-export-device-account-B")
            // Same fixed filename as the actual backend: stale grant must not expose the next copy.
            file.writeText("QA_PRIVATE_EXPORT_B")
            val newDataReadableThroughOldGrant = shellRead(uri).contains("QA_PRIVATE_EXPORT_B")
            val neutralGrant = context.checkUriPermission(neutralUri, Process.myPid(), shellUid, flags)
            val neutralReadable = shellRead(neutralUri).contains("QA_NEUTRAL_FILE")
            println("QA021_DEVICE checkGrant=$checkGrant shellUid=$shellUid exportExistsAfterLogout=$existsAfterLogout oldDataReadable=$oldDataReadable grantAfterLogout=$grantAfterLogout nextAccountReadableThroughOldGrant=$newDataReadableThroughOldGrant neutralReadable=$neutralReadable api=${android.os.Build.VERSION.SDK_INT}")
            assertEquals("QA-export-device-account-B", ApiClient.currentToken())
            if(checkGrant) {
                assertEquals("the old recipient grant must be revoked on logout", PackageManager.PERMISSION_DENIED, grantAfterLogout)
                assertFalse("an old recipient grant must not read the next account's same-path export", newDataReadableThroughOldGrant)
            } else {
                assertFalse("own private export must be removed on logout", existsAfterLogout)
                assertFalse("the external recipient must not read old A after logout", oldDataReadable)
            }
            assertEquals("unrelated grant must not be revoked", PackageManager.PERMISSION_GRANTED, neutralGrant)
            assertTrue(neutralReadable)
            assertEquals("QA_NEUTRAL_FILE", neutral.readText())
        } finally {
            context.revokeUriPermission(uri, flags)
            context.revokeUriPermission(neutralUri, flags)
            file.delete()
            neutral.delete()
            ApiClient.logout()
            ApiClient.resetForTest()
            ApiClient.testBaseUrl = originalUrl
            ApiClient.testTimeoutMs = originalTimeout
        }
    }

    @Test fun logoutDeletesOwnExportAndStopsRealRecipientReadingOldData() = checkFiles(false)
    @Test fun logoutRevokesOldGrantBeforeAnotherAccountReusesTheFilename() = checkFiles(true)

    @Test fun logoutRevokesActualPreparedUriIncludingItsDisplayNameQuery() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val originalUrl = ApiClient.testBaseUrl
        val originalTimeout = ApiClient.testTimeoutMs
        ApiClient.resetForTest()
        ApiClient.testBaseUrl = "http://127.0.0.1:1"
        ApiClient.testTimeoutMs = 500
        ApiClient.init(context)
        ApiClient.logout()
        ApiClient.saveToken("QA-export-device-account-A")
        val prepared = requireNotNull(PersonalDataExports.prepare(context, "yuldash-my-data.txt", "QA_PRIVATE_QUERY_EXPORT", ApiClient.queueSessionGeneration()))
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        try {
            assertEquals("yuldash-my-data.txt", prepared.uri.getQueryParameter("displayName"))
            context.grantUriPermission("com.android.shell", prepared.uri, flags)
            val shellUid = context.packageManager.getApplicationInfo("com.android.shell", 0).uid
            assertTrue(shellRead(prepared.uri).contains("QA_PRIVATE_QUERY_EXPORT"))
            ApiClient.logout()
            val permission = context.checkUriPermission(prepared.uri, Process.myPid(), shellUid, flags)
            val readable = shellRead(prepared.uri).contains("QA_PRIVATE_QUERY_EXPORT")
            println("QA021_QUERY_DEVICE exportExists=${prepared.file.exists()} grantAfterLogout=$permission oldDataReadable=$readable api=${android.os.Build.VERSION.SDK_INT}")
            assertFalse(prepared.file.exists())
            assertEquals(PackageManager.PERMISSION_DENIED, permission)
            assertFalse(readable)
        } finally {
            context.revokeUriPermission(prepared.uri, flags)
            prepared.discard()
            ApiClient.logout()
            ApiClient.resetForTest()
            ApiClient.testBaseUrl = originalUrl
            ApiClient.testTimeoutMs = originalTimeout
        }
    }
}
