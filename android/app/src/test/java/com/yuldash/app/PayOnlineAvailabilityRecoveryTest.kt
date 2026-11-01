package com.yuldash.app

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.InstantReceiptDto
import com.yuldash.app.ui.theme.YuldashTheme
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Shared production card/receipt, real loopback HTTP, synthetic owners, no payment provider. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PayOnlineAvailabilityRecoveryTest {
    @get:Rule val compose = createComposeRule()
    private val tokenA = "header.eyJzdWIiOiIxMSJ9.signature"
    private val tokenB = "header.eyJzdWIiOiIxMiJ9.signature"
    private val mounted = mutableStateOf(true)
    private val slot = mutableStateOf(0)
    private val amount = mutableStateOf<Int?>(25_000)
    private val healthRequests = AtomicInteger()
    private val paymentRequests = AtomicInteger()
    private val started = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val returned = CountDownLatch(1)
    private lateinit var server: MockWebServer
    private var receiptScreen = false
    @Volatile private var healthResponse: (Int) -> MockResponse = { allowed() }
    @Volatile private var holdPayment = false

    private fun json(body: String, status: Int = 200) = MockResponse()
        .setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)
    private fun allowed() = json("""{"payments":"yookassa"}""")
    private fun denied() = json("""{"payments":"off"}""")
    private fun temporaryFailure() = json("""{"detail":"Temporary upstream failure"}""", 503)

    @Before fun setup() {
        ApiClient.resetForTest()
        ApiClient.init(ApplicationProvider.getApplicationContext<Application>())
        ApiClient.saveToken(tokenA)
        OnlinePayGate.asked = false
        OnlinePayGate.enabled = false
        OnlinePayGate.unavailable = false
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                    "/health" -> healthResponse(healthRequests.incrementAndGet())
                    "/instant/orders/91/pay" -> {
                        paymentRequests.incrementAndGet()
                        if (holdPayment) {
                            started.countDown()
                            assertTrue("held payment must be released", release.await(30, TimeUnit.SECONDS))
                        }
                        json("""{"status":"paid","method":"card","payment_id":31}""")
                    }
                    else -> json("{}", 404)
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 60_000
    }

    @After fun cleanup() {
        release.countDown()
        compose.runOnIdle { mounted.value = false }
        pump()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        OnlinePayGate.asked = false
        OnlinePayGate.enabled = false
        OnlinePayGate.unavailable = false
        server.shutdown()
    }

    private fun pump() {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(100)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    private fun await(check: () -> Boolean) = compose.waitUntil(15_000) {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(100)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        check()
    }

    private fun mount() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            if (mounted.value) key(slot.value) {
                YuldashTheme {
                    if (receiptScreen) {
                        TaxiReceiptScreen(
                            orderId = 91, onBack = {}, loadRemote = false,
                            initialReceipt = InstantReceiptDto(
                                orderId = 91, role = "passenger", fromText = "Пункт А", toText = "Пункт Б",
                                doneAt = "2026-11-01T10:00:00Z", distanceKm = 4.0,
                                amount = 250, amountKop = 25_000, waitingFeeKop = 0,
                                paymentMethod = "card", paid = false, driverName = "Водитель", driverVerified = true,
                            ),
                        )
                    } else {
                        PayOnlineCard(amountKop = amount.value, targetKey = "taxi:91", pay = { method, owner ->
                            ApiClient.payInstantOrder(91, method, expectedGeneration = owner)
                        })
                    }
                }
            }
        }
        pump()
    }

    private fun hasRetry() = compose.onAllNodesWithTag("pay_online_retry").fetchSemanticsNodes().isNotEmpty()
    private fun hasSubmit() = compose.onAllNodesWithTag("pay_online_submit").fetchSemanticsNodes().isNotEmpty()
    private fun assertNoPayment() {
        compose.onNodeWithTag("pay_online_submit").assertDoesNotExist()
        assertEquals(0, paymentRequests.get())
    }

    private fun awaitFailedCheck() {
        // The old implementation reaches unavailable instead; fail on the contract, not a 15 s timeout.
        await { OnlinePayGate.unavailable || hasRetry() }
        assertNoPayment()
    }

    private fun retainedRetry(): () -> Boolean {
        awaitFailedCheck()
        compose.onNodeWithTag("pay_online_retry").assertExists()
        assertFalse("a failed read is not a server denial", OnlinePayGate.unavailable)
        if (receiptScreen) compose.onNodeWithTag("pay_online_retry").performScrollTo()
        return compose.onNodeWithTag("pay_online_retry").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
    }

    private fun reopen() {
        compose.runOnIdle { mounted.value = false }
        pump()
        compose.runOnIdle { mounted.value = true; slot.value++ }
        pump()
    }

    @Test fun networkFailureCanBeRetriedAfterConnectivityReturns() {
        ApiClient.testTimeoutMs = 500
        healthResponse = { MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) }
        mount()
        val retry = retainedRetry()
        assertTrue("the HTTP read really failed on the network", ApiClient.serverUnreachable.value)
        assertEquals(1, healthRequests.get())
        healthResponse = { allowed() }
        ApiClient.testTimeoutMs = 60_000
        compose.runOnIdle { retry() }
        await { hasSubmit() }
        assertEquals(2, healthRequests.get())
        assertEquals(0, paymentRequests.get())
    }

    @Test fun health503OffersRetryWithoutTreatingItAsProviderDenial() {
        healthResponse = { temporaryFailure() }
        mount()
        val retry = retainedRetry()
        healthResponse = { allowed() }
        compose.runOnIdle { retry() }
        await { hasSubmit() }
        assertEquals(2, healthRequests.get())
        assertFalse(OnlinePayGate.unavailable)
    }

    @Test fun receiptReopensAndRechecksAfterFailedAvailabilityRequest() {
        receiptScreen = true
        healthResponse = { temporaryFailure() }
        mount()
        awaitFailedCheck()
        healthResponse = { allowed() }
        reopen()
        await { hasSubmit() }
        assertEquals(2, healthRequests.get())
        assertEquals(0, paymentRequests.get())
    }

    @Test fun explicitServerDenialStaysBlockedWhenCardReopens() {
        healthResponse = { denied() }
        mount()
        await { OnlinePayGate.unavailable }
        healthResponse = { allowed() }
        reopen()
        assertNoPayment()
        compose.onNodeWithTag("pay_online_retry").assertDoesNotExist()
        assertEquals(1, healthRequests.get())
    }

    @Test fun closingDuringAvailabilityRequestDoesNotPoisonNextMount() {
        healthResponse = { request ->
            if (request == 1) {
                started.countDown()
                assertTrue(release.await(30, TimeUnit.SECONDS))
                returned.countDown()
                denied()
            } else allowed()
        }
        mount()
        await { started.count == 0L }
        assertNoPayment()
        reopen()
        await { hasSubmit() }
        release.countDown()
        assertTrue(returned.await(5, TimeUnit.SECONDS))
        pump()
        assertFalse(OnlinePayGate.unavailable)
        compose.onNodeWithTag("pay_online_submit").assertExists()
        assertEquals(2, healthRequests.get())
    }

    @Test fun lateAvailabilityReplyFromAccountACannotDisableAccountB() {
        healthResponse = { request ->
            if (request == 1) {
                started.countDown()
                assertTrue(release.await(30, TimeUnit.SECONDS))
                returned.countDown()
                denied()
            } else allowed()
        }
        mount()
        await { started.count == 0L }
        compose.runOnIdle { ApiClient.saveToken(tokenB); slot.value++ }
        await { hasSubmit() }
        release.countDown()
        assertTrue(returned.await(5, TimeUnit.SECONDS))
        pump()
        assertTrue(OnlinePayGate.enabledFor(ApiClient.queueSessionGeneration()))
        assertFalse(OnlinePayGate.unavailable)
        assertEquals(2, healthRequests.get())
        assertEquals(0, paymentRequests.get())
    }

    @Test fun duplicateRetryStartsOneCheckAndDoesNotEnablePaymentWhilePending() {
        healthResponse = { request ->
            if (request == 1) temporaryFailure() else {
                started.countDown()
                assertTrue(release.await(30, TimeUnit.SECONDS))
                allowed()
            }
        }
        mount()
        val retry = retainedRetry()
        compose.runOnIdle { retry(); retry() }
        await { started.count == 0L }
        assertEquals(2, healthRequests.get())
        assertNoPayment()
        release.countDown()
        await { hasSubmit() }
        assertEquals(2, healthRequests.get())
    }

    @Test fun unchangedFailureDoesNotPollOnRecompositionOrElapsedTime() {
        healthResponse = { temporaryFailure() }
        mount()
        retainedRetry()
        compose.runOnIdle { amount.value = 30_000 }
        compose.mainClock.advanceTimeBy(30_000)
        pump()
        assertEquals(1, healthRequests.get())
        compose.onNodeWithTag("pay_online_retry").assertExists()
        assertNoPayment()
    }

    @Test fun retainedRetryCannotRunAfterLogoutBeforeNextFrame() {
        healthResponse = { temporaryFailure() }
        mount()
        val retry = retainedRetry()
        compose.runOnIdle { ApiClient.logout(); retry() }
        pump()
        assertEquals(1, healthRequests.get())
        assertNoPayment()
    }

    @Test fun retainedRetryCannotRunUnderAnotherAccountBeforeNextFrame() {
        healthResponse = { temporaryFailure() }
        mount()
        val retry = retainedRetry()
        compose.runOnIdle { ApiClient.saveToken(tokenB); retry() }
        pump()
        assertEquals(1, healthRequests.get())
        assertNoPayment()
    }

    @Test fun retainedRetryCannotRunAfterCardDisposal() {
        healthResponse = { temporaryFailure() }
        mount()
        val retry = retainedRetry()
        compose.runOnIdle { mounted.value = false }
        pump()
        compose.runOnIdle { retry() }
        pump()
        assertEquals(1, healthRequests.get())
        assertNoPayment()
    }

    @Test fun duplicatePayAfterRecoveryStillSendsOnlyOnePayment() {
        healthResponse = { temporaryFailure() }
        holdPayment = true
        mount()
        val retry = retainedRetry()
        healthResponse = { allowed() }
        compose.runOnIdle { retry() }
        await { hasSubmit() }
        val pay = compose.onNodeWithTag("pay_online_submit").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { pay(); pay() }
        await { started.count == 0L }
        assertEquals(1, paymentRequests.get())
        release.countDown()
        await { compose.onAllNodesWithText("Оплачено — спасибо!").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, paymentRequests.get())
        assertEquals(2, healthRequests.get())
    }
}
