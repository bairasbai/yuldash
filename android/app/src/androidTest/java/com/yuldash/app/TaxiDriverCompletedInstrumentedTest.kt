package com.yuldash.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
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

/** Настоящий Compose-экран A: деньги, оценка, восстановительные действия и два выхода. */
@RunWith(AndroidJUnit4::class)
class TaxiDriverCompletedInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun completed_opensWithIncomeFirstAndFixedReturnAction() {
        render()

        composeRule.onNodeWithText("Поездка завершена").assertIsDisplayed()
        composeRule.onNodeWithText("Чистыми за поездку").assertIsDisplayed()
        composeRule.onNodeWithText("620 ₽").assertIsDisplayed()
        composeRule.onNodeWithText("674 ₽").assertIsDisplayed()
        composeRule.onNodeWithText("54 ₽").assertIsDisplayed()
        composeRule.onNodeWithText("Наличные").assertIsDisplayed()
        composeRule.onNodeWithText("Айгуль").assertIsDisplayed()
        composeRule.onNodeWithText("Вернуться на линию").assertIsDisplayed()
        composeRule.waitForIdle()
        holdIfRequested()
    }

    @Test
    fun completed_submitsPassengerRatingAndKeepsRecoveryActions() {
        render()

        composeRule.onNodeWithContentDescription("5 звёзд").performClick()
        composeRule.onNodeWithText("Вежливо").performClick()
        // Кнопка живёт в прокручиваемой карточке над фиксированными действиями.
        // Скроллим до реальной видимой позиции, чтобы тест нажимал так же, как водитель.
        composeRule.onNodeWithText("Отправить оценку").performScrollTo().assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Спасибо за оценку").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Спасибо за оценку").assertIsDisplayed()
        composeRule.onNodeWithText("Чек и детали").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Пассажир не заплатил").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Завершить смену").assertIsDisplayed()
        holdIfRequested()
    }

    @Test
    fun completed_keepsBashkirCoreCopyVisible() {
        render(language = AppLanguage.Ba)

        composeRule.onNodeWithText("Сәфәр тамамланды").assertIsDisplayed()
        composeRule.onNodeWithText("Сәфәрҙән таҙа килем").assertIsDisplayed()
        composeRule.onNodeWithText("Линияға ҡайтыу").assertIsDisplayed()
        composeRule.onNodeWithText("Сменаны тамамлау").assertIsDisplayed()
    }

    private fun render(language: AppLanguage = AppLanguage.Ru) {
        composeRule.setContent {
            YuldashTheme {
                CompositionLocalProvider(LocalAppLanguage provides language) {
                    TaxiDriverCompletedScreen(
                        order = previewOrder(),
                        onReturnToLine = {},
                        onShiftFinished = {},
                        onOpenReceipt = {},
                        rateOrder = { _, _, _ -> Result.success(Unit) },
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

    private fun previewOrder() = InstantOrderDto(
        id = 90_101,
        status = "done",
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
        priceFinal = 674,
        distanceKm = 6.4,
        etaMin = 18.0,
        driverId = 77,
        passengerId = 91,
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
        passengerRating = 4.9,
        passengerTrips = 38,
        driverName = "Ильдар",
        driverCar = "Kia Rio",
        driverVerified = true,
        driverRating = 4.9,
        driverPhone = "+7 927 000-00-00",
        passengerName = "Айгуль",
        passengerPhone = "+7 927 111-11-11",
        passengerPriceKop = 67_400,
        driverGrossKop = 67_400,
        driverFeeKop = 5_400,
        driverNetKop = 62_000,
        driverFeePercent = 8.0,
    )
}
