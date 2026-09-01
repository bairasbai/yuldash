package com.yuldash.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuldash.app.ui.theme.YuldashTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Живой полноэкранный рендер варианта B на Pixel API 35 без сети и тестовых моков API. */
@RunWith(AndroidJUnit4::class)
class PassengerRequestPremiumInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun optionB_showsRouteAndSymmetricCore() {
        render()

        composeRule.onNodeWithText("Создать заявку").assertIsDisplayed()
        composeRule.onNodeWithText("Маршрут").assertIsDisplayed()
        composeRule.onNodeWithText("Дата и время").assertIsDisplayed()
        composeRule.onNodeWithText("Мест").assertIsDisplayed()
        composeRule.onNodeWithText("Опубликовать заявку").assertIsDisplayed()
        composeRule.waitForIdle()
        holdIfRequested()

        scrollTo("Дата и время")
        composeRule.onNodeWithText("18:30", substring = true).assertIsDisplayed()
        scrollTo("Бюджет")
        composeRule.onNodeWithText("Бюджет").assertIsDisplayed()
        scrollTo("Категория")
        composeRule.onNodeWithText("Категория").assertIsDisplayed()
        scrollTo("Где встречаемся?")
        composeRule.onNodeWithText("Где встречаемся?").assertIsDisplayed()
        scrollTo("Что важно")
        composeRule.onNodeWithText("Что важно").assertIsDisplayed()
    }

    @Test
    fun optionB_keepsCategoryPreferencesAndSubmitClickable() {
        var pickedCategory = ""
        var baggageClicks = 0
        var submitted = 0
        render(
            onCategoryChange = { pickedCategory = it },
            onBaggageChange = { baggageClicks++ },
            onSubmit = { submitted++ },
        )

        composeRule.onNodeWithTag("passenger_category_cell").performScrollTo().performClick()
        composeRule.waitForIdle()
        scrollTo("Срочно")
        composeRule.onNodeWithText("Срочно").performClick()
        scrollTo("Багаж")
        composeRule.onNodeWithText("Багаж").performClick()
        scrollTo("Дополнительно")
        composeRule.onNodeWithText("Дополнительно").performClick()
        composeRule.waitForIdle()
        scrollTo("Только женщины")
        composeRule.onNodeWithText("Только женщины").assertIsDisplayed()
        composeRule.onNodeWithText("Опубликовать заявку").performClick()

        assertEquals("urgent", pickedCategory)
        assertEquals(1, baggageClicks)
        assertEquals(1, submitted)
    }

    @Test
    fun optionB_keepsBashkirCoreCopyVisible() {
        render(language = AppLanguage.Ba)

        composeRule.onNodeWithText("Заявка булдырыу").assertIsDisplayed()
        composeRule.onNodeWithTag("passenger_route_card").assertIsDisplayed()
        scrollTo("Сәфәр тураһында")
        composeRule.onNodeWithText("Сәфәр тураһында").assertIsDisplayed()
        scrollTo("Дата һәм ваҡыт")
        composeRule.onNodeWithText("Дата һәм ваҡыт").assertIsDisplayed()
        scrollTo("Төр")
        composeRule.onNodeWithText("Төр").assertIsDisplayed()
        scrollTo("Нимә мөһим")
        composeRule.onNodeWithText("Нимә мөһим").assertIsDisplayed()
        composeRule.onNodeWithText("Заявканы баҫтырыу").assertIsDisplayed()
    }

    private fun render(
        language: AppLanguage = AppLanguage.Ru,
        onCategoryChange: (String) -> Unit = {},
        onBaggageChange: (Boolean) -> Unit = {},
        onSubmit: () -> Unit = {},
    ) {
        val categories = listOf(
            "regular" to LocalizedText("Обычная", "Ғәҙәти"),
            "urgent" to LocalizedText("Срочно", "Ашығыс"),
            "parcel" to LocalizedText("Посылка", "Бандероль"),
            "cargo" to LocalizedText("Груз", "Йөк"),
            "kids" to LocalizedText("С детьми", "Балалар менән"),
        )
        composeRule.setContent {
            var category by remember { mutableStateOf("regular") }
            var baggage by remember { mutableStateOf(false) }
            YuldashTheme {
                CompositionLocalProvider(LocalAppLanguage provides language) {
                    Scaffold(
                        containerColor = CanonBg,
                        topBar = { ScreenTopBar(appText("Создать заявку", "Заявка булдырыу"), {}) },
                    ) { padding ->
                        CreatePassengerRequestContent(
                            from = "Баймаҡ",
                            to = "Сибай",
                            time = "05.09.2026, 18:30",
                            seats = "1",
                            category = category,
                            price = "450",
                            comment = "",
                            categories = categories,
                            selectedCategoryText = categories.first { it.first == category }.second.text(),
                            womenOnly = false,
                            childSeat = false,
                            pets = false,
                            wheelchair = false,
                            baggage = baggage,
                            nonSmoking = true,
                            airConditioner = false,
                            onlyTrusted = true,
                            pickupLabel = appText("У автовокзала", "Автовокзал янында"),
                            loading = false,
                            onCategoryChange = { category = it; onCategoryChange(it) },
                            onSeatsChange = {},
                            onPriceChange = {},
                            onCommentChange = {},
                            onTimeChange = {},
                            onWomenOnlyChange = {},
                            onChildSeatChange = {},
                            onPetsChange = {},
                            onWheelchairChange = {},
                            onBaggageChange = { baggage = it; onBaggageChange(it) },
                            onNonSmokingChange = {},
                            onAirConditionerChange = {},
                            onOnlyTrustedChange = {},
                            onSubmit = onSubmit,
                            modifier = Modifier.padding(padding),
                        )
                    }
                }
            }
        }
    }

    private fun scrollTo(text: String) {
        composeRule.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(text))
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
