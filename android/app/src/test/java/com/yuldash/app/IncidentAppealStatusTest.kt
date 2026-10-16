package com.yuldash.app

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IncidentAppealStatusTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var server: MockWebServer
    private val mounted = mutableStateOf(true)
    private val requests = CopyOnWriteArrayList<String>()
    private var status = "resolved"
    private var appeal = "overturned"

    @Before fun setup() {
        ApiClient.resetForTest()
        ApiClient.init(ApplicationProvider.getApplicationContext<Context>())
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += "${request.method} ${request.path}"
                    return MockResponse().setHeader("Content-Type","application/json").setBody(
                        """{"id":1,"booking_id":2,"type":"passenger_no_show","status":"$status","reporter_role":"driver","description":"QA_DESCRIPTION","respondent_statement":"QA_RESPONSE","resolution":"dismissed","fault":"none","resolution_note":"QA_APPEAL_RESULT","appeal_status":"$appeal","appeal_text":"QA_APPEAL","created_at":"2026-10-15T10:00:00Z","resolved_at":"2026-10-15T10:10:00Z","my_role":"reporter","other_name":"TestCounterparty","booking_route":"Сибай → Магнитогорск"}"""
                    )
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 1000
        ApiClient.saveToken("local-appeal-status-only")
    }

    @After fun cleanup() {
        compose.mainClock.autoAdvance = true
        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
        ApiClient.logout()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server.shutdown()
    }

    private fun verify(state: String, appealState: String, language: AppLanguage, waiting: Boolean) {
        status = state
        appeal = appealState
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides language) {
                if (mounted.value) IncidentDetailScreen(incidentId = 1, onBack = {})
            }
        }
        try { compose.waitUntil(15000) {
            compose.mainClock.advanceTimeBy(100)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Snapshot.sendApplyNotifications()
            compose.onAllNodesWithText("QA_DESCRIPTION").fetchSemanticsNodes().isNotEmpty()
        }
        } catch (e: Throwable) { throw AssertionError("Incident requests=$requests; tree=" + compose.onRoot().printToString(), e) }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        val pending = if (language == AppLanguage.Ru)
            "Апелляция подана — ждём разбора человеком."
        else "Ялыу бирелде — кешенең ҡарауын көтәбеҙ."
        val list = compose.onNode(hasScrollAction())
        if (waiting) {
            list.performScrollToNode(hasText(pending))
            compose.onNodeWithText(pending).assertIsDisplayed()
        } else {
            list.performScrollToNode(hasText("QA_APPEAL_RESULT"))
            repeat(2) { list.performTouchInput { swipeUp() }; compose.waitForIdle() }
            compose.onNodeWithText("QA_APPEAL_RESULT").assertIsDisplayed()
            compose.onAllNodesWithText(pending).assertCountEquals(0)
        }
        assertEquals(listOf("GET /incidents/1"), requests.toList())
    }

    @Test fun requestedAppealWaitsRu() = verify("appealed","requested",AppLanguage.Ru,true)
    @Test fun requestedAppealWaitsBa() = verify("appealed","requested",AppLanguage.Ba,true)
    @Test fun upheldAppealIsFinishedRu() = verify("resolved","upheld",AppLanguage.Ru,false)
    @Test fun upheldAppealIsFinishedBa() = verify("resolved","upheld",AppLanguage.Ba,false)
    @Test fun overturnedAppealIsFinishedRu() = verify("resolved","overturned",AppLanguage.Ru,false)
    @Test fun overturnedAppealIsFinishedBa() = verify("resolved","overturned",AppLanguage.Ba,false)
    @Test fun resolvedStaleRequestDoesNotWait() = verify("resolved","requested",AppLanguage.Ru,false)
    @Test fun closedStaleRequestDoesNotWait() = verify("closed","requested",AppLanguage.Ba,false)
}
