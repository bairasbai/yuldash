package com.yuldash.app

import android.app.Application
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.DialogInterface
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.ui.theme.YuldashTheme
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
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
import org.robolectric.shadows.ShadowAlertDialog
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/** Actual form and retained button semantics; only the remote HTTP server is replaced. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h1400dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PassengerRequestSubmitOwnerBoundaryTest {
    @get:Rule val compose = createComposeRule()
    private val mounted = mutableStateOf(true)
    private val posts = CopyOnWriteArrayList<Pair<String?, String>>()
    private val created = CopyOnWriteArrayList<LocalRequest>()
    private lateinit var server: MockWebServer

    private fun tokenFor(id: Int) = "e30." + Base64.getUrlEncoder().withoutPadding()
        .encodeToString("""{"sub":"$id"}""".toByteArray()) + ".synthetic"

    @Before fun prepare() {
        ApiClient.resetForTest()
        ApiClient.init(ApplicationProvider.getApplicationContext<Context>())
        ApiClient.saveToken(tokenFor(11))
        FeatureFlags.setForTest(FeatureSet(Feature.entries.toSet(), known = true,
            fetchedAtMs = System.currentTimeMillis()))
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.method == "POST" && request.path == "/requests") {
                        posts += request.getHeader("Authorization") to request.body.readUtf8()
                        return MockResponse().setResponseCode(201).setHeader("Content-Type", "application/json")
                            .setBody("""{"id":42}""")
                    }
                    return MockResponse().setHeader("Content-Type", "application/json").setBody("""{"items":[]}""")
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 2000
    }

    @After fun cleanup() {
        compose.runOnIdle { mounted.value = false }
        ApiClient.logout()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server.shutdown()
    }

    private fun await(check: () -> Boolean) {
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            check()
        }
    }

    private fun mountAndRetainSubmit(): () -> Boolean {
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                YuldashTheme {
                    if (mounted.value) CreatePassengerRequestScreen({}, { created += it })
                }
            }
        }
        compose.onNodeWithTag("passenger_time_cell").performScrollTo().performClick()
        compose.runOnIdle {
            val date = ShadowAlertDialog.getLatestAlertDialog() as DatePickerDialog
            date.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            val time = ShadowAlertDialog.getLatestAlertDialog() as TimePickerDialog
            time.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        }
        compose.onNodeWithTag("passenger_submit_btn").assertIsEnabled()
        return compose.onNodeWithTag("passenger_submit_btn").fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action!!
    }

    @Test fun currentOwnerSubmitCreatesExactlyOneRealRequest() {
        val submit = mountAndRetainSubmit()
        compose.runOnIdle { submit() }
        await { created.size == 1 }
        assertEquals(1, posts.size)
        assertEquals("Bearer ${tokenFor(11)}", posts.single().first)
        assertEquals(42, created.single().serverId)
        val body = JSONObject(posts.single().second)
        assertEquals("Баймаҡ", body.getString("from_city"))
        assertEquals("Сибай", body.getString("to_city"))
        assertTrue(body.getString("desired_at").isNotBlank())
    }

    private fun oldSubmitAfter(change: () -> Unit) {
        val submit = mountAndRetainSubmit()
        // The actual handler is retained before changing the API account, not recreated for the new owner.
        compose.runOnIdle { change(); submit() }
        repeat(12) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
            Thread.sleep(20)
        }
        assertTrue("Old form must not post under a replacement login: $posts", posts.isEmpty())
        assertTrue("Old form must not report a created request", created.isEmpty())
    }

    @Test fun retainedSubmitCannotPublishUnderAnotherAccount() = oldSubmitAfter {
        ApiClient.saveToken(tokenFor(33))
    }

    @Test fun retainedSubmitCannotPublishAfterLogout() = oldSubmitAfter {
        ApiClient.logout()
    }
}
