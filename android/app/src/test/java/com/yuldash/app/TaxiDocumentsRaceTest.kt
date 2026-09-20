package com.yuldash.app

import android.app.Application
import android.app.DatePickerDialog
import android.content.DialogInterface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.yuldash.app.data.ApiClient
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TaxiDocumentsRaceTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var server: MockWebServer
    private val reads = AtomicInteger()
    private val photos = AtomicInteger()
    private val release = CountDownLatch(1)
    @Volatile private var refreshCode = 200
    private fun body(date: String) = """{"id":1,"status":"approved","osago_until":"$date"}"""

    @Before fun setup() {
        ApiClient.resetForTest()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/taxi/application") {
                        if (reads.incrementAndGet() > 1) {
                            check(release.await(20, TimeUnit.SECONDS))
                            if (refreshCode != 200) return MockResponse().setResponseCode(refreshCode).setBody("{}")
                        }
                        return MockResponse().setBody(body("2030-01-01"))
                    }
                    if (request.path == "/taxi/documents")
                        return MockResponse().setBody(body("2031-02-03"))
                    photos.incrementAndGet()
                    return MockResponse().setResponseCode(404).setBody("{}")
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 25000
    }
    @After fun cleanup() {
        release.countDown()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server.shutdown()
    }
    private fun waitText(text: String) {
        compose.waitUntil(10000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun refreshStartedBeforeSaveCannotReplaceSavedDate() {
        verifyLateRefresh()
    }
    @Test fun oldMissingApplicationCannotEraseSavedDocuments() {
        refreshCode = 404
        verifyLateRefresh()
    }
    @Test fun oldServerErrorCannotMarkSavedDocumentsStale() {
        refreshCode = 503
        verifyLateRefresh()
        compose.onNodeWithText("Повторить").assertDoesNotExist()
    }
    private fun verifyLateRefresh() {
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                TaxiDocumentsScreen(onBack = {})
            }
        }
        waitText("до 01.01.2030")
        compose.waitUntil(5000) { photos.get() == 1 }
        compose.onNode(hasScrollAction()).performTouchInput { swipeDown(startY = top + 5f, endY = bottom - 5f, durationMillis = 1000) }
        compose.waitUntil(5000) { reads.get() == 2 }
        compose.onNodeWithText("ОСАГО").performScrollTo().performClick()
        compose.runOnIdle {
            val dialog = ShadowAlertDialog.getLatestAlertDialog() as DatePickerDialog
            dialog.datePicker.updateDate(2031, 1, 3)
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        }
        waitText("до 03.02.2031")
        release.countDown()
        compose.waitUntil(5000) { photos.get() == 2 }
        compose.waitForIdle()
        compose.onNodeWithText("до 03.02.2031").assertExists()
        compose.onNodeWithText("до 01.01.2030").assertDoesNotExist()
    }
}
