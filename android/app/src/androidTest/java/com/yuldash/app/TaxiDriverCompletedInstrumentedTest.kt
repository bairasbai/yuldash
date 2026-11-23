package com.yuldash.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
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
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
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

    @Test
    fun completed_recoversSavedStarsEvenWhenRatingWindowClosed() {
        val writes = AtomicInteger()
        render(
            readRating = { _, _ -> Result.success(previewOrder().copy(myStars = 4, canRate = false)) },
            rateOrder = { _, _, _, _ -> writes.incrementAndGet(); Result.success(Unit) },
        )

        composeRule.onNodeWithText("Спасибо за оценку").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("4 звезды").assertIsDisplayed()
        composeRule.onNodeWithText("Отправить оценку").assertDoesNotExist()
        assertEquals(0, writes.get())
    }

    @Test
    fun completed_deniedRatingKeepsRecoveryActions() {
        render(readRating = { _, _ -> Result.success(previewOrder().copy(canRate = false)) })

        composeRule.onNodeWithText("Оценка этой поездки сейчас недоступна.").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Отправить оценку").assertDoesNotExist()
        composeRule.onNodeWithText("Чек и детали").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun completed_lostWriteAnswerReReadsWithoutSecondWrite() {
        val reads = AtomicInteger()
        val writes = AtomicInteger()
        render(
            readRating = { _, _ ->
                Result.success(previewOrder().copy(myStars = if (reads.incrementAndGet() == 1) 0 else 5))
            },
            rateOrder = { _, _, _, _ ->
                writes.incrementAndGet()
                Result.failure(java.io.IOException("Test response lost"))
            },
        )

        composeRule.onNodeWithContentDescription("5 звёзд").performScrollTo().performClick()
        composeRule.onNodeWithText("Отправить оценку").performScrollTo().performClick()
        composeRule.onNodeWithText("Спасибо за оценку").performScrollTo().assertIsDisplayed()
        assertEquals(2, reads.get())
        assertEquals(1, writes.get())
    }

    @Test
    fun completed_unconfirmedWriteRechecksButNeverResends() {
        val reads = AtomicInteger()
        val writes = AtomicInteger()
        val restoration = StateRestorationTester(composeRule)
        render(
            restoration = restoration,
            readRating = { _, _ ->
                reads.incrementAndGet()
                Result.success(previewOrder())
            },
            rateOrder = { _, _, _, _ ->
                writes.incrementAndGet()
                Result.failure(java.io.IOException("Test response lost"))
            },
        )

        composeRule.onNodeWithContentDescription("5 звёзд").performScrollTo().performClick()
        composeRule.onNodeWithText("Отправить оценку").performScrollTo().performClick()
        composeRule.onNodeWithText("Повторить").performScrollTo().performClick()
        composeRule.onNodeWithText("Отправка не подтверждена. Проверь оценку ещё раз.")
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Отправить оценку").assertDoesNotExist()
        assertEquals(3, reads.get())
        restoration.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithText("Отправка не подтверждена. Проверь оценку ещё раз.")
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Отправить оценку").assertDoesNotExist()
        assertEquals(4, reads.get())
        assertEquals(1, writes.get())
    }

    @Test
    fun passengerCompleted_readsOwnSavedRatingAndDoesNotSubmit() {
        val writes = AtomicInteger()
        val order = previewOrder().copy(role = "passenger", myStars = 0)
        composeRule.setContent {
            YuldashTheme {
                CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                    TaxiPassengerCompletedScreen(
                        order = order,
                        onClose = {},
                        onNewOrder = {},
                        onOpenReceipt = {},
                        onOpenChat = {},
                        readRating = { _, _ -> Result.success(order.copy(myStars = 2)) },
                        rateOrder = { _, _, _, _ -> writes.incrementAndGet(); Result.success(Unit) },
                        openLostItem = { Result.success(Unit) },
                    )
                }
            }
        }

        composeRule.onNodeWithText("Спасибо за оценку").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("2 звезды").assertIsDisplayed()
        composeRule.onNodeWithText("Отправить оценку").assertDoesNotExist()
        assertEquals(0, writes.get())
    }

    private fun render(
        language: AppLanguage = AppLanguage.Ru,
        readRating: TaxiRatingRead = { _, _ -> Result.success(previewOrder()) },
        rateOrder: TaxiRatingWrite = { _, _, _, _ -> Result.success(Unit) },
        restoration: StateRestorationTester? = null,
    ) {
        val content: @androidx.compose.runtime.Composable () -> Unit = {
            YuldashTheme {
                CompositionLocalProvider(LocalAppLanguage provides language) {
                    TaxiDriverCompletedScreen(
                        order = previewOrder(),
                        onReturnToLine = {},
                        onShiftFinished = {},
                        onOpenReceipt = {},
                        readRating = readRating,
                        rateOrder = rateOrder,
                        finishShift = { Result.success(Unit) },
                    )
                }
            }
        }
        if (restoration != null) restoration.setContent(content) else composeRule.setContent(content)
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
        canRate = true,
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
