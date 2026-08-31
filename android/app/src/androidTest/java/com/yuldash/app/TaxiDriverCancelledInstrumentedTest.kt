package com.yuldash.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuldash.app.data.InstantOrderDto
import com.yuldash.app.ui.theme.YuldashTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Живые сценарии C + A: неявка, бесплатная отмена и башкирская локаль. */
@RunWith(AndroidJUnit4::class)
class TaxiDriverCancelledInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun noShow_showsRouteFactsAndRecordedFee() {
        render(previewNoShow())

        composeRule.onNodeWithText("Пассажир не вышел").assertIsDisplayed()
        composeRule.onNodeWithText("Точка подачи").assertIsDisplayed()
        composeRule.onNodeWithText("ул. Ленина, 12").assertIsDisplayed()
        composeRule.onNodeWithText("Куда ехали").assertIsDisplayed()
        composeRule.onNodeWithText("ул. Гагарина, 8").assertIsDisplayed()
        composeRule.onNodeWithText("Прибыл").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("7+ мин").assertIsDisplayed()
        composeRule.onNodeWithText("Комфорт").assertIsDisplayed()
        composeRule.onNodeWithText("Наличные").assertIsDisplayed()
        composeRule.onNodeWithText("180 ₽").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Плата за подачу зафиксирована").assertIsDisplayed()
        composeRule.onNodeWithText("Вернуться на линию").assertIsDisplayed()
        composeRule.waitForIdle()
        holdIfRequested()
    }

    @Test
    fun freePassengerCancel_isHonestAndProblemOpens() {
        render(previewFreeCancel())

        composeRule.onNodeWithText("Пассажир отменил заказ").assertIsDisplayed()
        composeRule.onNodeWithText("Отмена без платы", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Без компенсации").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("0 ₽").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Отмена произошла до платного окна.").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Проблема с заказом").performScrollTo().performClick()
        composeRule.onNodeWithText("Что случилось?").assertIsDisplayed()
        composeRule.onNodeWithText("Сообщить о нарушении").assertIsDisplayed()
        composeRule.onNodeWithText("Открыть разбор").assertIsDisplayed()
        composeRule.waitForIdle()
        holdIfRequested()
    }

    @Test
    fun freePassengerCancel_showsNoCompensationAndRecoveryAction() {
        render(previewFreeCancel())

        composeRule.onNodeWithText("Без компенсации").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("0 ₽").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Отмена произошла до платного окна.").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Проблема с заказом").performScrollTo().assertIsDisplayed()
        composeRule.waitForIdle()
        holdIfRequested()
    }

    @Test
    fun noShow_keepsBashkirCoreCopyVisible() {
        render(previewNoShow(), AppLanguage.Ba)

        composeRule.onNodeWithText("Пассажир сыҡманы").assertIsDisplayed()
        composeRule.onNodeWithText("Килеү нөктәһе").assertIsDisplayed()
        composeRule.onNodeWithText("Килеү хаҡы теркәлде").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Линияға ҡайтыу").assertIsDisplayed()
        composeRule.onNodeWithText("Сменаны тамамлау").assertIsDisplayed()
    }

    private fun render(order: InstantOrderDto, language: AppLanguage = AppLanguage.Ru) {
        composeRule.setContent {
            YuldashTheme {
                CompositionLocalProvider(LocalAppLanguage provides language) {
                    TaxiDriverCancelledScreen(
                        order = order,
                        onReturnToLine = {},
                        onShiftFinished = {},
                        finishShift = { Result.success(Unit) },
                    )
                }
            }
        }
    }

    private fun holdIfRequested() {
        val holdMs = InstrumentationRegistry.getArguments()
            .getString("holdMs")
            ?.toLongOrNull()
            ?.coerceIn(0L, 60_000L)
            ?: 0L
        Thread.sleep(holdMs)
    }

    private fun previewNoShow() = InstantOrderDto(
        id = 90_101,
        status = "cancelled",
        role = "driver",
        fromLat = 54.7351,
        fromLng = 55.9587,
        toLat = 54.7442,
        toLng = 55.9860,
        fromText = "ул. Ленина, 12",
        toText = "ул. Гагарина, 8",
        category = "comfort",
        paymentMethod = "cash",
        priceEstimate = 674,
        priceFinal = null,
        distanceKm = 6.4,
        etaMin = 18.0,
        driverId = 77,
        passengerId = 91,
        offerExpiresAt = null,
        cancelBy = "driver",
        cancelReason = "passenger_no_show",
        surgeK = 1.0,
        waitingStartedAt = "2026-08-31T16:42:00Z",
        waitingFeeKop = 0,
        cancelFeeKop = 18_000,
        noShow = true,
        waitFreeMin = 5,
        waitFeeRubPerMin = 5,
        noShowAt = "2026-08-31T16:49:00Z",
        cancelFeeNowKop = 18_000,
        passengerRating = 4.9,
        passengerTrips = 38,
        driverName = "Ильдар",
        driverCar = "Kia Rio",
        driverVerified = true,
        driverRating = 4.9,
        driverPhone = "+7 927 000-00-00",
        passengerName = "Айгуль",
        passengerPhone = "+7 927 111-11-11",
        passengerPriceKop = 0,
        driverGrossKop = 0,
        driverFeeKop = 0,
        driverNetKop = 0,
        driverFeePercent = 8.0,
    )

    private fun previewFreeCancel() = previewNoShow().copy(
        noShow = false,
        cancelBy = "passenger",
        waitingStartedAt = null,
        noShowAt = null,
        cancelFeeKop = 0,
    )
}
