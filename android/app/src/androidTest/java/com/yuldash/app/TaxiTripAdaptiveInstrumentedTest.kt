package com.yuldash.app

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuldash.app.data.InstantOrderDto
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Живой рендер утверждённой связки C + A вместе с настоящей MapKit-картой. */
@RunWith(AndroidJUnit4::class)
class TaxiTripAdaptiveInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun skipOnNewApi() {
        assumeTrue("Инструментальные Compose-тесты карты — только API ≤ 36", Build.VERSION.SDK_INT <= 36)
    }

    @Test
    fun acceptedTrip_opensCompactMapWithAllImmediateActions() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                TaxiTripScreen(
                    order = previewOrder(),
                    onCancel = {},
                    onMinimize = {},
                    enableLiveTracking = false,
                    mapContent = { modifier ->
                        InstantRouteMap(
                            from = com.yandex.mapkit.geometry.Point(52.5931, 58.3186),
                            to = com.yandex.mapkit.geometry.Point(52.6076, 58.3338),
                            car = com.yandex.mapkit.geometry.Point(52.6028, 58.3281),
                            carBearing = 222.0,
                            modifier = modifier,
                        )
                    },
                )
            }
        }

        composeRule.onNodeWithText("Ильдар").assertIsDisplayed()
        composeRule.onNodeWithText("Написать").assertIsDisplayed()
        composeRule.onNodeWithText("Уже выхожу").assertIsDisplayed()
        composeRule.waitForIdle()

        if (InstrumentationRegistry.getArguments().getString("expand") == "true") {
            composeRule.onNodeWithTag("taxiSheetDragArea").performTouchInput {
                swipeUp(durationMillis = 700)
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Ильдар будет через 3 мин").assertIsDisplayed()
        }

        // Для ручной визуальной проверки можно передать -e holdMs 30000 и дополнительно
        // -e expand true: тест сам выполнит настоящий жест по шапке и остановится на A.
        val holdMs = InstrumentationRegistry.getArguments()
            .getString("holdMs")
            ?.toLongOrNull()
            ?.coerceIn(0L, 60_000L)
            ?: 2_500L
        Thread.sleep(holdMs)
    }

    @Test
    fun onboardPassenger_opensBalancedNavigatorWithRouteAndSafety() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                TaxiTripScreen(
                    order = previewOrder().copy(status = "onboard", etaMin = 23.0),
                    onCancel = {},
                    onMinimize = {},
                    enableLiveTracking = false,
                    mapContent = { modifier -> previewMap(modifier) },
                )
            }
        }

        composeRule.onNodeWithText("В пути · 23 мин").assertIsDisplayed()
        composeRule.onNodeWithText("Едем к месту").assertIsDisplayed()
        composeRule.onNodeWithText("Безопасность").assertIsDisplayed()
        composeRule.waitForIdle()
        holdIfRequested()
    }

    @Test
    fun onboardDriver_opensSameNavigatorWithFinishAndExternalVoiceFallback() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                TaxiDriverOnboardNavigator(
                    order = previewOrder().copy(status = "onboard", etaMin = 23.0),
                    locationFix = TaxiLocationFix(
                        point = com.yandex.mapkit.geometry.Point(52.6028, 58.3281),
                        accuracyMeters = 8f,
                        headingDegrees = 222f,
                        deviceHeadingDegrees = 222f,
                    ),
                    busy = false,
                    actionError = null,
                    onBack = {},
                    onChat = {},
                    onCall = {},
                    onSafety = {},
                    onOpenExternalNavigator = {},
                    onFinish = {},
                )
            }
        }

        composeRule.onNodeWithText("В пути · 23 мин").assertIsDisplayed()
        composeRule.onNodeWithText("Открыть голосовой навигатор").assertIsDisplayed()
        composeRule.onNodeWithText("Завершить поездку").assertIsDisplayed()
        composeRule.waitForIdle()
        holdIfRequested()
    }

    @Composable
    private fun previewMap(modifier: androidx.compose.ui.Modifier) {
        InstantRouteMap(
            from = com.yandex.mapkit.geometry.Point(52.5931, 58.3186),
            to = com.yandex.mapkit.geometry.Point(52.6076, 58.3338),
            car = com.yandex.mapkit.geometry.Point(52.6028, 58.3281),
            carBearing = 222.0,
            modifier = modifier,
        )
    }

    private fun holdIfRequested() {
        val holdMs = InstrumentationRegistry.getArguments()
            .getString("holdMs")
            ?.toLongOrNull()
            ?.coerceIn(0L, 60_000L)
            ?: 0L
        Thread.sleep(holdMs)
    }

    private fun previewOrder() = InstantOrderDto(
        id = 90_001,
        status = "accepted",
        role = "passenger",
        fromLat = 52.5931,
        fromLng = 58.3186,
        toLat = 52.6076,
        toLng = 58.3338,
        fromText = "ул. Ленина, 12",
        toText = "ул. Гагарина, 8",
        category = "standard",
        paymentMethod = "cash",
        priceEstimate = 350,
        priceFinal = null,
        distanceKm = 3.8,
        etaMin = 3.0,
        driverId = 77,
        offerExpiresAt = null,
        cancelBy = "",
        cancelReason = "",
        surgeK = 1.0,
        waitingStartedAt = null,
        waitingFeeKop = 0,
        cancelFeeKop = 0,
        noShow = false,
        waitFreeMin = 5,
        waitFeeRubPerMin = 5,
        noShowAt = null,
        cancelFeeNowKop = 0,
        passengerRating = null,
        passengerTrips = 0,
        driverName = "Ильдар",
        driverCar = "Kia Rio",
        driverCarColor = "серебристый",
        driverVerified = true,
        driverRating = 4.9,
        driverPhone = "+7 927 000-00-00",
        passengerName = "Александр",
        passengerPhone = "+7 927 111-11-11",
        driverTrips = 312,
        driverFrom = "Баймак",
        driverPlate = "А123ВС 02",
    )
}
