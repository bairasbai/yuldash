package com.yuldash.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.yuldash.app.data.RequestFeedDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * «В глубину»: продолжение выноса стейта. Экран `RequestsFeedScreen` (лента заявок пассажиров)
 * разрезан на умную обёртку (сеть/стейт/диалог отклика) и чистый `RequestsFeedContent(state, callbacks)`.
 * Здесь Content покрыт во ВСЕХ состояниях (загрузка / ошибка+повтор / пусто / список / отклик) — то, что раньше было 0%.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RidesFeedContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun request(
        id: Int = 1,
        passengerName: String = "Айгуль",
        from: String = "Баймак",
        to: String = "Сибай",
        seats: Int = 2,
        comment: String = "Еду утром",
        responded: Boolean = false,
        prefs: List<String> = emptyList(),
    ) = RequestFeedDto(id = id, passengerName = passengerName, from = from, to = to, seats = seats, comment = comment, responded = responded, passengerAvatar = "", prefs = prefs)

    @Test
    fun loading_showsSpinnerNotList() {
        composeRule.mainClock.autoAdvance = false   // бесконечный спиннер
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                RequestsFeedContent(loading = true, error = false, feed = listOf(request()), onRetry = {}, onRespond = {})
            }
        }
        composeRule.onNodeWithText("Загрузка…").assertIsDisplayed()
        composeRule.onNodeWithText("Баймак → Сибай").assertDoesNotExist()
    }

    @Test
    fun error_showsMessageAndRetryButton() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                RequestsFeedContent(loading = false, error = true, feed = emptyList(), onRetry = {}, onRespond = {})
            }
        }
        composeRule.onNodeWithText("Не удалось загрузить заявки. Проверь сеть.").assertIsDisplayed()
        composeRule.onNodeWithText("Повторить").assertIsDisplayed()
    }

    @Test
    fun error_retryClick_firesOnRetry() {
        var retried = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                RequestsFeedContent(loading = false, error = true, feed = emptyList(), onRetry = { retried = true }, onRespond = {})
            }
        }
        composeRule.onNodeWithText("Повторить").performClick()
        assertTrue(retried)
    }

    @Test
    fun empty_russian_showsFriendlyPlaceholder() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                RequestsFeedContent(loading = false, error = false, feed = emptyList(), onRetry = {}, onRespond = {})
            }
        }
        composeRule.onNodeWithText("Заявок пока нет").assertIsDisplayed()
        composeRule.onNodeWithText("Здесь появятся заявки пассажиров.").assertIsDisplayed()
    }

    @Test
    fun empty_bashkir_showsBashkirPlaceholder() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                RequestsFeedContent(loading = false, error = false, feed = emptyList(), onRetry = {}, onRespond = {})
            }
        }
        composeRule.onNodeWithText("Әлегә заявкалар юҡ").assertIsDisplayed()
    }

    @Test
    fun list_showsRouteAuthorAndRespondButton() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                RequestsFeedContent(loading = false, error = false, feed = listOf(request()), onRetry = {}, onRespond = {})
            }
        }
        composeRule.onNodeWithText("Баймак → Сибай").assertIsDisplayed()
        composeRule.onNodeWithText("Айгуль · 2 места").assertIsDisplayed()
        composeRule.onNodeWithText("Еду утром").assertIsDisplayed()
        composeRule.onNodeWithText("Предложить поездку").assertIsDisplayed()
    }

    @Test
    fun list_respondClick_firesOnRespondWithItem() {
        var responded: RequestFeedDto? = null
        val r = request(id = 77)
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                RequestsFeedContent(loading = false, error = false, feed = listOf(r), onRetry = {}, onRespond = { responded = it })
            }
        }
        composeRule.onNodeWithText("Предложить поездку").performClick()
        assertEquals(77, responded?.id)
    }

    @Test
    fun list_respondedItem_showsRespondedLabelNotButton() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                RequestsFeedContent(loading = false, error = false, feed = listOf(request(responded = true)), onRetry = {}, onRespond = {})
            }
        }
        composeRule.onNodeWithText("Вы откликнулись").assertIsDisplayed()
        composeRule.onNodeWithText("Предложить поездку").assertDoesNotExist()
    }
}
