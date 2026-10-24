package com.yuldash.app

import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.yuldash.app.data.ApiClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Регрессия DELTA06: действия внизу должны соответствовать настоящему статусу брони. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AuditDelta06BookingStatusTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val ride = Ride(
        id = "17",
        from = "Город А",
        to = "Город Б",
        time = "завтра, 09:00",
        driver = "Айрат",
        car = "Lada Vesta",
        price = 700,
        seats = 2,
        rating = 4.9,
        verified = true,
        boosted = false,
    )

    private fun show(
        status: String,
        canOpen: Boolean,
        onCancel: () -> Unit = {},
        onFindAnother: () -> Unit = {},
        onOpen: () -> Unit = {},
    ) {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                BookingScreen(
                    ride = ride,
                    bookingId = 91,
                    ads = emptyList(),
                    adStats = emptyMap(),
                    onBack = {},
                    onMessage = {},
                    onAdImpression = {},
                    onAdClick = {},
                    canOpenActiveTrip = canOpen,
                    bookingStatus = status,
                    onCancelBooking = onCancel,
                    onFindAnotherRide = onFindAnother,
                    onConfirmRide = { _, _, _, _, _ -> onOpen() },
                )
            }
        }
    }

    @Test
    fun pendingOffersCancellationOnlyAfterConfirmation() {
        var cancelCalls = 0
        show(status = "pending", canOpen = false, onCancel = { cancelCalls++ })

        composeRule.onNodeWithText("Ждём ответа водителя").assertIsDisplayed()
        composeRule.onNodeWithText("Отменить бронь").assertIsDisplayed().performClick()
        assertEquals(0, cancelCalls)

        composeRule.onNodeWithText("Да, отменить").assertIsDisplayed().performClick()
        assertEquals(1, cancelCalls)
    }

    @Test
    fun cancelledIsHonestAndStartsAnotherSearch() {
        // Статус «отменена» пришёл из списка: пока свежие детали не пришли, экран грузится — это могла
        // быть отмена в пути (CancelledInTransit.kt, 24.10.2026). Детали — обычная отмена: как раньше.
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.path == "/bookings/91/details") MockResponse().setHeader("Content-Type", "application/json")
                        .setBody("""{"booking_id":91,"ride_id":17,"role":"passenger","status":"cancelled","from_city":"Город А","to_city":"Город Б"}""")
                    else MockResponse().setResponseCode(404).setBody("{}")
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 1000
        try {
            var findCalls = 0
            show(status = "cancelled", canOpen = false, onFindAnother = { findCalls++ })
            composeRule.waitUntil(15_000) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                Snapshot.sendApplyNotifications()
                composeRule.onAllNodesWithText("Бронь отменена").fetchSemanticsNodes().isNotEmpty()
            }

            composeRule.onNodeWithText("Бронь отменена").assertIsDisplayed()
            composeRule.onNodeWithText("Ждём водителя").assertDoesNotExist()
            composeRule.onNodeWithText("Найти другую поездку").assertIsDisplayed().performClick()
            assertEquals(1, findCalls)
        } finally {
            ApiClient.resetForTest()
            ApiClient.testTimeoutMs = null
            server.shutdown()
        }
    }

    @Test
    fun confirmedStillOpensTrip() {
        var openCalls = 0
        show(status = "confirmed", canOpen = true, onOpen = { openCalls++ })

        composeRule.onNodeWithText("Открыть поездку").assertIsDisplayed().performClick()
        assertEquals(1, openCalls)
    }
}
