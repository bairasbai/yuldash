package com.yuldash.app

import android.Manifest
import android.app.Application
import android.os.SystemClock
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onNodeWithContentDescription
import org.robolectric.shadows.ShadowDialog
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DriverRideShareUiTest {
    @get:Rule val compose = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val server = MockWebServer()
    private val entered = CountDownLatch(1)
    private val release = CountDownLatch(1)
    @Before fun setup() {
        ApiClient.resetForTest(); ApiClient.init(app); ApiClient.saveToken("fixture.eyJzdWIiOiIxIn0.fixture")
        DriverGpsActions.revoke(); shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "Held GPS fixture was not explicitly released" }
                return MockResponse().setBody("""[{"booking_id":42,"ride_id":8,"status":"onboard"}]""")
            }
        }
        server.start(); ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
    }
    @After fun cleanup() { release.countDown(); DriverGpsActions.revoke(); ApiClient.resetForTest(); server.shutdown() }
    @Test fun liveShareButtonSetsDeadlineBeforeHeldGetAndDoubleTapMakesOneRead() {
        compose.setContent { DriverRideShareButton(42, 8) }
        val click = compose.onNodeWithText("Показывать машину пассажирам").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { click(); click() } // retained handler: the second tap precedes the loading recomposition
        // Robolectric runs this test on main: a blocking latch would prevent the UI coroutine from starting.
        compose.waitUntil(5000) {
            shadowOf(Looper.getMainLooper()).idle()
            entered.count == 0L
        }
        assertNull(DriverGpsActions.current())
        ShadowSystemClock.advanceBy(Duration.ofMinutes(1))
        val responseAt = SystemClock.elapsedRealtime()
        release.countDown()
        compose.waitUntil(15000) { DriverGpsActions.current() != null }
        assertTrue(DriverGpsActions.current()!!.ticket.untilElapsed <= responseAt + DRIVER_GPS_MAX_MS - 30_000)
        assertEquals(1, server.requestCount)
        assertEquals("GET", server.takeRequest().method)
    }
    @Test fun russianAndBashkirContentExposeExplicitRetryWithoutBusinessStatusAction() {
        var calls = 0
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                DriverRideShareContent(false, false, true) { calls++ }
            }
        }
        compose.onNodeWithText("Машинаны пассажирҙарға күрһәтергә").performClick()
        assertEquals(1, calls)
        assertEquals(0, server.requestCount)
    }

    private fun pendingTicket(): DriverGpsTicket? =
        DriverGpsGrant::class.java.getDeclaredField("pending").apply { isAccessible = true }
            .get(DriverGpsActions.grant) as DriverGpsTicket?

    @Test fun explanationBackCancelsPendingAndAllowsFreshOwnedRetry() {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        compose.setContent { DriverRideShareButton(42, 8) }
        val pressedAt = SystemClock.elapsedRealtime()
        val pressedWall = System.currentTimeMillis()
        compose.onNodeWithText("Показывать машину пассажирам").performClick()
        compose.onNode(isDialog()).assertExists()
        val first = compose.runOnIdle { pendingTicket()!! }
        assertEquals(DriverGpsActions.owner(), first.owner)
        assertTrue(first.untilElapsed >= pressedAt + DRIVER_GPS_MAX_MS)
        assertTrue(first.untilElapsed <= SystemClock.elapsedRealtime() + DRIVER_GPS_MAX_MS)
        assertTrue(first.untilWall >= pressedWall + DRIVER_GPS_MAX_MS)
        assertTrue(first.untilWall <= System.currentTimeMillis() + DRIVER_GPS_MAX_MS)
        compose.onNodeWithContentDescription("Показывать машину пассажирам").assertIsNotEnabled()
        assertNull(DriverGpsActions.current())
        assertEquals(0, server.requestCount)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(1))
        compose.runOnIdle {
            assertSame(first, pendingTicket())
            ShadowDialog.getLatestDialog().onBackPressed()
        }
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.runOnIdle { assertNull(pendingTicket()) }
        compose.onNodeWithText("Показывать машину пассажирам").assertIsEnabled()
        compose.onNodeWithText("Не получилось включить передачу. Проверь точный геодоступ и сеть, затем повтори.").assertExists()
        assertNull(DriverGpsActions.current())
        assertEquals(0, server.requestCount)
        compose.runOnIdle { shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION) }
        assertNull(DriverGpsActions.current())
        assertFalse(DriverGpsActions.grant.complete(first, first.owner, 8, 42, Long.MAX_VALUE,
            SystemClock.elapsedRealtime(), System.currentTimeMillis()))
        assertEquals(0, server.requestCount)
        compose.onNodeWithText("Показывать машину пассажирам").performClick()
        compose.waitUntil(5000) {
            shadowOf(Looper.getMainLooper()).idle()
            entered.count == 0L
        }
        val retry = compose.runOnIdle { pendingTicket()!! }
        assertNotSame(first, retry)
        assertNotEquals(first.id, retry.id)
        assertEquals(first.owner, retry.owner)
        assertTrue(retry.untilElapsed > first.untilElapsed)
        assertNull(DriverGpsActions.current())
        release.countDown()
        compose.waitUntil(15000) { DriverGpsActions.current() != null }
        assertSame(retry, DriverGpsActions.current()!!.ticket)
        assertEquals(retry.untilElapsed, DriverGpsActions.current()!!.ticket.untilElapsed)
        assertEquals(retry.untilWall, DriverGpsActions.current()!!.ticket.untilWall)
        assertEquals(1, server.requestCount)
        assertEquals("GET", server.takeRequest().method)
    }
}
