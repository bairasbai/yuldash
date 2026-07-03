package com.yuldash.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.yuldash.app.data.ReviewItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * «В глубину»: пилот выноса стейта. Экран `AdminReviewsScreen` разрезан на умную обёртку
 * (сеть/стейт) и чистый `AdminReviewsContent(state, callbacks)`. Здесь Content покрыт во ВСЕХ
 * состояниях (загрузка / ошибка+повтор / пусто / список / отправка) — то, что раньше было 0%.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdminReviewsContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun review(id: Int = 1, name: String = "Айгуль", city: String = "Уфа", stars: Int = 5, text: String = "Отличная поездка") =
        ReviewItem(id = id, name = name, city = city, stars = stars, text = text)

    @Test
    fun loading_showsSpinnerNotList() {
        composeRule.mainClock.autoAdvance = false   // бесконечный спиннер
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                AdminReviewsContent(loading = true, error = null, reviews = listOf(review()), publishingId = null, onRetry = {}, onApprove = {})
            }
        }
        composeRule.onNodeWithText("«Отличная поездка»").assertDoesNotExist()
    }

    @Test
    fun error_showsMessageAndRetryButton() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                AdminReviewsContent(loading = false, error = "Сеть упала", reviews = emptyList(), publishingId = null, onRetry = {}, onApprove = {})
            }
        }
        composeRule.onNodeWithText("Сеть упала").assertIsDisplayed()
        composeRule.onNodeWithText("Повторить").assertIsDisplayed()
    }

    @Test
    fun error_retryClick_firesOnRetry() {
        var retried = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                AdminReviewsContent(loading = false, error = "Ошибка", reviews = emptyList(), publishingId = null, onRetry = { retried = true }, onApprove = {})
            }
        }
        composeRule.onNodeWithText("Повторить").performClick()
        assertTrue(retried)
    }

    @Test
    fun empty_russian_showsFriendlyPlaceholder() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                AdminReviewsContent(loading = false, error = null, reviews = emptyList(), publishingId = null, onRetry = {}, onApprove = {})
            }
        }
        composeRule.onNodeWithText("Новых отзывов нет").assertIsDisplayed()
        composeRule.onNodeWithText("Всё разобрано").assertIsDisplayed()
    }

    @Test
    fun empty_bashkir_showsBashkirPlaceholder() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                AdminReviewsContent(loading = false, error = null, reviews = emptyList(), publishingId = null, onRetry = {}, onApprove = {})
            }
        }
        composeRule.onNodeWithText("Яңы фекерҙәр юҡ").assertIsDisplayed()
    }

    @Test
    fun list_showsReviewTextAuthorAndApproveButton() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                AdminReviewsContent(loading = false, error = null, reviews = listOf(review()), publishingId = null, onRetry = {}, onApprove = {})
            }
        }
        composeRule.onNodeWithText("«Отличная поездка»").assertIsDisplayed()
        composeRule.onNodeWithText("Айгуль, Уфа").assertIsDisplayed()
        composeRule.onNodeWithText("Одобрить для сайта").assertIsDisplayed()
    }

    @Test
    fun list_approveClick_firesOnApproveWithItem() {
        var approved: ReviewItem? = null
        val r = review(id = 77)
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                AdminReviewsContent(loading = false, error = null, reviews = listOf(r), publishingId = null, onRetry = {}, onApprove = { approved = it })
            }
        }
        composeRule.onNodeWithText("Одобрить для сайта").performClick()
        assertEquals(77, approved?.id)
    }

    @Test
    fun list_blankNameAndCity_showsAnonymous() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                AdminReviewsContent(loading = false, error = null, reviews = listOf(review(name = "", city = "")), publishingId = null, onRetry = {}, onApprove = {})
            }
        }
        composeRule.onNodeWithText("Аноним").assertIsDisplayed()
    }

    @Test
    fun list_publishingItem_showsSpinnerInsteadOfApproveLabel() {
        composeRule.mainClock.autoAdvance = false   // спиннер в кнопке
        val r = review(id = 5)
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                AdminReviewsContent(loading = false, error = null, reviews = listOf(r), publishingId = 5, onRetry = {}, onApprove = {})
            }
        }
        composeRule.onNodeWithText("Одобрить для сайта").assertDoesNotExist()
    }
}
