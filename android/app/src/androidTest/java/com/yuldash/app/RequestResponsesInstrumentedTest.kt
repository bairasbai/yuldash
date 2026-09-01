package com.yuldash.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuldash.app.data.ResponseDto
import com.yuldash.app.ui.theme.YuldashTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Живой полноэкранный рендер гибрида B+A на Pixel API 35. */
@RunWith(AndroidJUnit4::class)
class RequestResponsesInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun optionBPlusA_showsSymmetricDealAndDriverTrust() {
        render()

        composeRule.onNodeWithText("Отклики на заявку").assertIsDisplayed()
        composeRule.onNodeWithText("Баймак").assertIsDisplayed()
        composeRule.onNodeWithText("Сибай").assertIsDisplayed()
        composeRule.onNodeWithText("Ваш бюджет").assertIsDisplayed()
        composeRule.onAllNodesWithText("450 ₽")[0].assertIsDisplayed()
        composeRule.onNodeWithText("Предложение").assertIsDisplayed()
        composeRule.onAllNodesWithText("480 ₽")[0].assertIsDisplayed()
        composeRule.onNodeWithText("Ринат Хабиров").assertIsDisplayed()
        composeRule.onNodeWithText("128 поездок").assertIsDisplayed()
        composeRule.onNodeWithText("Lada Vesta · белая").assertIsDisplayed()
        composeRule.onNodeWithText("Цена фиксируется после принятия").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Отказать").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Своя цена").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Принять").performScrollTo().assertIsDisplayed()
        composeRule.waitForIdle()
        holdIfRequested()
    }

    @Test
    fun optionBPlusA_keepsAllThreeDecisionsClickable() {
        var declined = 0
        var countered = 0
        var accepted = 0
        render(
            onDecline = { declined++ },
            onCounter = { countered++ },
            onAccept = { accepted++ },
        )

        composeRule.onNodeWithText("Отказать").performScrollTo().performClick()
        composeRule.onNodeWithText("Своя цена").performScrollTo().performClick()
        composeRule.onNodeWithText("Принять").performScrollTo().performClick()

        assertEquals(1, declined)
        assertEquals(1, countered)
        assertEquals(1, accepted)
    }

    @Test
    fun optionBPlusA_keepsBashkirCoreCopyVisible() {
        render(language = AppLanguage.Ba)

        composeRule.onNodeWithText("Заявкаға яуаптар").assertIsDisplayed()
        composeRule.onNodeWithText("Һинең бюджетың").assertIsDisplayed()
        composeRule.onNodeWithText("Тәҡдим").assertIsDisplayed()
        composeRule.onNodeWithText("Хаҡ ҡабул иткәндән һуң теркәлә").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Баш тартыу").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Үҙ хаҡың").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Ҡабул итеү").performScrollTo().assertIsDisplayed()
    }

    private fun render(
        language: AppLanguage = AppLanguage.Ru,
        onAccept: () -> Unit = {},
        onCounter: () -> Unit = {},
        onDecline: () -> Unit = {},
    ) {
        val response = ResponseDto(
            id = 7,
            driverId = 42,
            driverName = "Ринат Хабиров",
            driverRating = 4.9,
            price = 500,
            comment = "Буду на месте вовремя",
            status = "offered",
            currentPrice = 480,
            lastOfferBy = "driver",
            bargainRounds = 2,
            canCounter = true,
            canAccept = true,
            bargainHistory = "d:500,p:450,d:480",
            driverVerified = true,
            driverTripsCount = 128,
            driverCar = "Lada Vesta · белая",
            requestFromCity = "Баймак",
            requestToCity = "Сибай",
            requestSeats = 1,
            requestMaxPrice = 450,
            requestDesiredAt = "2026-09-01T18:30:00",
        )
        composeRule.setContent {
            YuldashTheme {
                CompositionLocalProvider(LocalAppLanguage provides language) {
                    Scaffold(
                        containerColor = CanonBg,
                        topBar = { ScreenTopBar(appText("Отклики на заявку", "Заявкаға яуаптар"), {}) },
                    ) { padding ->
                        ResponsesContent(
                            loading = false,
                            error = false,
                            responses = listOf(response),
                            accepting = false,
                            onRetry = {},
                            onAccept = { onAccept() },
                            onCounter = { onCounter() },
                            onDecline = { onDecline() },
                            modifier = Modifier.padding(padding),
                        )
                    }
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
}
