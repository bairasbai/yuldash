package com.yuldash.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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

/** Живой полноэкранный рендер утверждённого варианта B на Pixel API 35. */
@RunWith(AndroidJUnit4::class)
class RideshareCompletedInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun completedOptionB_showsWarmHierarchyAndFixedRatingActions() {
        render()

        composeRule.onNodeWithText("Поездка завершена").assertIsDisplayed()
        composeRule.onNodeWithText("Рәхмәт, Ринат!").assertIsDisplayed()
        composeRule.onNodeWithText("Баймак  →  Сибай").assertIsDisplayed()
        composeRule.onNodeWithText("Ринат Хабиров").assertIsDisplayed()
        composeRule.onNodeWithText("Оценишь поездку?").assertIsDisplayed()
        composeRule.onNodeWithText("Пропустить").assertIsDisplayed()
        composeRule.onNodeWithText("Отправить оценку").assertIsDisplayed()
        composeRule.waitForIdle()
        holdIfRequested()
        composeRule.onNodeWithText("Сказать «Рәхмәт»").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Квитанция").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Забыли вещь?").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("rideshareCompletedList").performScrollToNode(hasText("Итог поездки"))
        composeRule.onNodeWithText("Итог поездки").assertIsDisplayed()
        composeRule.onNodeWithText("Как договорились").assertIsDisplayed()
    }

    @Test
    fun completedOptionB_submitsExplicitStarsAndTags() {
        var submittedStars = 0
        var submittedTags = emptyList<String>()
        render(onSubmit = { stars, tags -> submittedStars = stars; submittedTags = tags })

        composeRule.onNodeWithTag("rideshareStar5").performClick()
        composeRule.onNodeWithText("Чистая машина").performScrollTo().performClick()
        composeRule.onNodeWithTag("rideshareRatingSubmit").assertIsEnabled().performClick()

        assertEquals(5, submittedStars)
        assertEquals(listOf("clean"), submittedTags)
    }

    @Test
    fun completedOptionB_keepsThanksReceiptAndLostItemClickable() {
        var thanksOpened = false
        var receiptOpened = false
        var lostItemOpened = false
        render(
            onThanks = { thanksOpened = true },
            onReceipt = { receiptOpened = true },
            onLostItem = { lostItemOpened = true },
        )

        composeRule.onNodeWithText("Сказать «Рәхмәт»").performScrollTo().performClick()
        composeRule.onNodeWithText("Квитанция").performClick()
        composeRule.onNodeWithText("Забыли вещь?").performScrollTo().performClick()

        assertEquals(true, thanksOpened)
        assertEquals(true, receiptOpened)
        assertEquals(true, lostItemOpened)
    }

    @Test
    fun completedOptionB_keepsBashkirCoreCopyVisible() {
        render(language = AppLanguage.Ba)

        composeRule.onNodeWithText("Сәфәр тамамланды").assertIsDisplayed()
        composeRule.onNodeWithText("Ринат, рәхмәт!").assertIsDisplayed()
        composeRule.onNodeWithText("Сәфәрҙе баһаларһыңмы?").assertIsDisplayed()
        composeRule.onNodeWithText("Үткәреп ебәреү").assertIsDisplayed()
        composeRule.onNodeWithText("Баһаны ебәреү").assertIsDisplayed()
    }

    private fun render(
        language: AppLanguage = AppLanguage.Ru,
        onSubmit: (Int, List<String>) -> Unit = { _, _ -> },
        onThanks: () -> Unit = {},
        onReceipt: () -> Unit = {},
        onLostItem: () -> Unit = {},
    ) {
        composeRule.setContent {
            YuldashTheme {
                CompositionLocalProvider(LocalAppLanguage provides language) {
                    var stars by remember { mutableStateOf(0) }
                    var tags by remember { mutableStateOf(emptyList<String>()) }
                    var review by remember { mutableStateOf("") }
                    RideshareCompletedContent(
                        isDriver = false,
                        from = "Баймак",
                        to = "Сибай",
                        counterpartyName = "Ринат Хабиров",
                        counterpartyAvatar = "",
                        counterpartyRating = 4.9,
                        counterpartyVerified = true,
                        car = "Lada Vesta · белая",
                        amount = 450,
                        payMethod = "cash",
                        paid = false,
                        receiptLoading = false,
                        receiptError = null,
                        onRetryReceipt = {},
                        stars = stars,
                        selectedTags = tags,
                        reviewText = review,
                        reviewExpanded = false,
                        ratingBusy = false,
                        ratingSent = false,
                        thanked = false,
                        thanksBusy = false,
                        lostOpened = false,
                        lostBusy = false,
                        actionError = null,
                        onStar = { stars = it; tags = emptyList() },
                        onTag = { code -> tags = if (code in tags) tags - code else tags + code },
                        onReviewExpanded = {},
                        onReviewText = { review = it },
                        onSubmit = { onSubmit(stars, tags) },
                        onSkip = {},
                        onThanks = onThanks,
                        onReceipt = onReceipt,
                        onLostItem = onLostItem,
                        onSupport = {},
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
}
