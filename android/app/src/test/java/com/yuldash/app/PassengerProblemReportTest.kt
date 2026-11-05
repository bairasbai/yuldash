package com.yuldash.app

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.awaitOfflineWriteSnapshotReset02
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import com.yuldash.app.data.ChatSocket
import com.yuldash.app.data.SnapshotWiringCleanupGuard
import com.yuldash.app.data.finishOfflineWriteFixture02
import com.yuldash.app.data.poisonOfflinePassportFixture03
import kotlinx.coroutines.Job
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import java.net.InetAddress
import com.yuldash.app.data.BoardingResult
import com.yuldash.app.data.TipInfoDto
import com.yuldash.app.data.TripReceiptDto
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * «Сообщить о проблеме» не пропадает тогда, когда оно нужнее всего (приёмка PR174).
 *
 * Водитель отметил «Сел» — у пассажира «в пути» отмены нет (сервер: `onboard_no_cancel`), и если он
 * не садился, разбор — единственный путь. Водитель мог и сразу закрыть поездку — тогда путь
 * остаётся на экране завершённой поездки, пока сервер держит окно (`problem_report_sec`, 6 часов).
 * Тип спора у пассажира сразу «Водитель не приехал». Водителю на экране поездки после окна
 * «Отменить» — разбор «Если пассажир на самом деле не садился» (Q4), как в кабинете.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PassengerProblemReportTest {
    companion object { private val cleanupGuard = SnapshotWiringCleanupGuard() }
    private val requestJobs = RequestJobs()
    private var setupEntered = false
    private var serverStarted = false
    private var sessionInstalled = false
    private var contentSet = false
    private val ownTrace: (String) -> Unit = {
        if (it.startsWith("вошли в вызов ")) requestJobs.record()
    }
    @get:Rule val compose = createComposeRule(effectContext = requestJobs)
    private val noop: (BoardingResult) -> Unit = {}
    private val mounted = mutableStateOf(true)
    private lateinit var server: MockWebServer
    private var receiptBearer: String? = null
    private val receiptReads = java.util.concurrent.atomic.AtomicInteger()

    @Before fun setup() {
        cleanupGuard.requireHealthy()
        check(ApiClient.testTrace == null)
        setupEntered = true
        ApiClient.resetForTest()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path != "/trips/42/receipt" || request.method != "GET")
                        return MockResponse().setResponseCode(404).setBody("{}")
                    if (request.getHeader("Authorization") != receiptBearer)
                        return MockResponse().setResponseCode(403).setBody("{}")
                    receiptReads.incrementAndGet()
                    val value = receipt()
                    val body = JSONObject().put("booking_id", value.bookingId).put("ride_id", value.rideId)
                        .put("role", value.role).put("from_city", value.fromCity).put("to_city", value.toCity)
                        .put("depart_at", value.departAt).put("seats", value.seats).put("amount", value.amount)
                        .put("pay_method", value.payMethod).put("paid", value.paid)
                        .put("driver_name", value.driverName).put("driver_verified", value.driverVerified)
                        .put("counterparty_name", value.counterpartyName).put("counterparty_id", value.counterpartyId)
                        .put("problem_report_sec", value.problemReportSec)
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(body.toString())
                }
            }
            start(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0)
        }
        serverStarted = true
        ApiClient.testBaseUrl = server.url("/").newBuilder().host("127.0.0.1").build().toString().trimEnd('/')
        ApiClient.testTimeoutMs = 1_000
        ApiClient.testTrace = ownTrace
        ApiClient.init(ApplicationProvider.getApplicationContext<Application>())
        awaitOfflineWriteSnapshotReset02()
        ApiClient.saveToken("header.eyJzdWIiOiIxMSJ9.signature")   // диалог разбора живёт только во входе
        sessionInstalled = true
        receiptBearer = "Bearer " + requireNotNull(ApiClient.currentToken())
    }

    @After fun cleanup() {
        try {
            cleanupGuard.requireHealthy()
            if (!setupEntered) return
            if (contentSet) {
                compose.runOnIdle { mounted.value = false }
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                compose.waitForIdle()
            }
            requestJobs.awaitCompleted(if (serverStarted) server.port else null)
            if (sessionInstalled) ApiClient.logout()
            finishOfflineWriteFixture02(cleanupGuard) {
                check(ApiClient.testTrace == null || ApiClient.testTrace === ownTrace)
                if (serverStarted) server.shutdown()
                ApiClient.resetForTest()
                ApiClient.testTimeoutMs = null
            }
        } catch (failure: Throwable) {
            poisonOfflinePassportFixture03(cleanupGuard, failure)
        } finally {
            if (ApiClient.testTrace === ownTrace) ApiClient.testTrace = null
        }
    }

    private fun waitFor(text: String, substring: Boolean = false) = compose.waitUntil(15_000) {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        Snapshot.sendApplyNotifications()
        compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
    }

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        contentSet = true
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) { content() }
        }
    }

    private fun receipt(role: String = "passenger", reportSec: Int = 3600, counterparty: Int = 9) = TripReceiptDto(
        bookingId = 42, rideId = 7, role = role, fromCity = "Уфа", toCity = "Бирск", departAt = "",
        seats = 1, amount = 400, payMethod = "cash", paid = true, driverName = "Ринат", driverVerified = true,
        counterpartyName = "Ринат", counterpartyId = counterparty, problemReportSec = reportSec,
    )

    @Test fun boardedPassengerCanStillReportAProblem() {
        val slot = activeTripBoardingSlot("passenger", "onboard", 42, 9, "Ринат", 0, noop)!!
        show { slot() }
        compose.onNodeWithText("Водитель отметил посадку").assertIsDisplayed()
        compose.onNodeWithText("Не садился — сообщи о проблеме.").assertIsDisplayed()
        compose.onNodeWithText("Сообщить о проблеме").performClick()
        waitFor("Водитель не приехал")
        // Тип уже выбран — список свёрнут в одну строку.
        compose.onAllNodesWithText("Нагрубили").assertCountEquals(0)
    }

    @Test fun waitingPassengerOpensTheDisputeWithDriverNoShow() {
        val slot = activeTripBoardingSlot("passenger", "confirmed", 42, 9, "Ринат", 0, noop)!!
        show { slot() }
        compose.onNodeWithText("Водитель отметит посадку").assertIsDisplayed()
        compose.onNodeWithText("Сообщить о проблеме").performClick()
        waitFor("Водитель не приехал")
    }

    @Test fun driverTripScreenOffersTheDisputeAfterTheUndoWindow() {
        val slot = activeTripBoardingSlot("driver", "onboard", 42, 7, "", 0, noop)!!
        show { slot() }
        compose.onNodeWithText("Пассажир в машине").assertIsDisplayed()
        compose.onAllNodesWithText("Отменить ·", substring = true).assertCountEquals(0)
        compose.onNodeWithText("Если пассажир на самом деле не садился", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Сообщить о проблеме").performClick()
        waitFor("Пассажир не вышел")
        // Имени пассажира на экране поездки нет — в тексте разбора «пассажира», а не пустое место.
        waitFor("Мы позовём пассажира объясниться", substring = true)
    }

    @Test fun completedTripKeepsTheReportForThePassenger() {
        show {
            RideshareCompletedScreen(42, null, "passenger", "cash", 400, onClose = {}, onOpenReceipt = {}, onSupport = {},
                loadThanks = { Result.success(TipInfoDto("Ринат", alreadyThanked = false, sbpPhone = null)) })
        }
        compose.waitUntil(15_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithTag("rideshareReceiptLoading").fetchSemanticsNodes().isEmpty()
        }
        assertEquals(1, receiptReads.get())
        compose.onNodeWithTag("rideshareCompletedList").performScrollToNode(hasTestTag("rideshareProblemReport"))
        compose.onNodeWithText("Не садился в машину?").assertIsDisplayed()
        compose.onNodeWithText("Сообщить о проблеме").performClick()
        waitFor("Водитель не приехал")
    }

    @Test fun afterTripReportFollowsTheServerWindowAndTheRole() {
        assertEquals(9, afterTripReportTo(receipt(), isDriver = false))
        assertEquals("окно сервера вышло", 0, afterTripReportTo(receipt(reportSec = 0), isDriver = false))
        assertEquals("водителю — нет", 0, afterTripReportTo(receipt(role = "driver"), isDriver = true))
        assertEquals("старый сервер без второй стороны", 0, afterTripReportTo(receipt(counterparty = 0), isDriver = false))
        assertEquals("квитанция ещё не пришла", 0, afterTripReportTo(null, isDriver = false))
    }

    @Test fun bashkirCopyForTheBoardedPassenger() {
        val slot = activeTripBoardingSlot("passenger", "onboard", 42, 9, "Ринат", 0, noop)!!
        contentSet = true
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) { slot() }
        }
        compose.onNodeWithText("Йөрөтөүсе ултырыуҙы билдәләне").assertIsDisplayed()
        compose.onNodeWithText("Проблема тураһында хәбәр итергә").assertIsDisplayed()
    }

    /** Observes actual API callers; never creates a Job or replaces a dispatcher. */
    private class RequestJobs : ThreadContextElement<Job?>, AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<RequestJobs>
        private val current = ThreadLocal<Job?>()
        private val started = java.util.concurrent.CopyOnWriteArrayList<Job>()
        @Volatile private var missingParent = false

        override fun updateThreadContext(context: CoroutineContext): Job? =
            current.get().also { current.set(context[Job]) }

        override fun restoreThreadContext(context: CoroutineContext, oldState: Job?) {
            current.set(oldState)
        }

        fun record() {
            current.get()?.let { job ->
                started += job
                val caller = job.parent
                if (caller == null) missingParent = true else started += caller
            }
        }

        fun awaitCompleted(port: Int?) = runBlocking {
            withTimeout(5_000) {
                check(!missingParent) { "Owned caller completion unavailable" }
                val field = ChatSocket::class.java.getDeclaredField("client\$delegate")
                    .apply { isAccessible = true }
                val lazyClient = field.get(null) as Lazy<*>
                val client = lazyClient.takeIf { it.isInitialized() }?.value as? okhttp3.OkHttpClient
                while (started.any { !it.isCompleted } || client?.dispatcher?.let {
                        it.runningCalls() + it.queuedCalls()
                    }.orEmpty().any { it.request().url.host == "127.0.0.1" && it.request().url.port == port }) {
                    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                    delay(10)
                }
            }
        }
    }
}
