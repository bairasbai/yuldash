package com.yuldash.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * «В глубину»: пилот выноса стейта. Экран `RepeatTripScreen` разрезан на умную обёртку
 * (сеть/стейт) и чистый `RepeatTripContent(state, callbacks)`. Здесь Content покрыт во ВСЕХ
 * состояниях (нужно войти / загрузка / ошибка+повтор / пусто / список + клик) — то, что раньше было 0%.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccessibilityDeepContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun trip(
        title: String = "Мой рейс",
        titleBa: String = "Минең рейс",
        from: String = "Баймаҡ",
        to: String = "Сибай",
        timeHint: String = "из вашей истории",
        timeHintBa: String = "һеҙҙең тарихтан",
        categoryKey: String = "regular",
    ) = FrequentTrip(title, titleBa, from, to, timeHint, timeHintBa, categoryKey)

    @Test
    fun notLoggedIn_showsLoginPrompt() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                RepeatTripContent(
                    loggedIn = false, loading = false, loadError = false,
                    frequentTrips = emptyList(), demoTrips = emptyList(), submittingRoute = null,
                    onLoginRequired = {}, onRetry = {}, onRepeat = {},
                )
            }
        }
        composeRule.onNodeWithText("Нужно войти").assertIsDisplayed()
        composeRule.onNodeWithText("Войти").assertIsDisplayed()
    }

    @Test
    fun notLoggedIn_loginClick_firesOnLoginRequired() {
        var asked = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                RepeatTripContent(
                    loggedIn = false, loading = false, loadError = false,
                    frequentTrips = emptyList(), demoTrips = emptyList(), submittingRoute = null,
                    onLoginRequired = { asked = true }, onRetry = {}, onRepeat = {},
                )
            }
        }
        composeRule.onNodeWithText("Войти").performClick()
        assertTrue(asked)
    }

    @Test
    fun loading_showsSkeletonNotTrips() {
        composeRule.mainClock.autoAdvance = false   // бесконечная пульсация скелетона
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                RepeatTripContent(
                    loggedIn = true, loading = true, loadError = false,
                    frequentTrips = listOf(trip()), demoTrips = emptyList(), submittingRoute = null,
                    onLoginRequired = {}, onRetry = {}, onRepeat = {},
                )
            }
        }
        // Список во время загрузки не показываем — только скелетоны.
        composeRule.onNodeWithText("Мой рейс").assertDoesNotExist()
    }

    @Test
    fun error_showsMessageAndRetryButton() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                RepeatTripContent(
                    loggedIn = true, loading = false, loadError = true,
                    frequentTrips = emptyList(), demoTrips = emptyList(), submittingRoute = null,
                    onLoginRequired = {}, onRetry = {}, onRepeat = {},
                )
            }
        }
        composeRule.onNodeWithText("Маршруты не загрузились").assertIsDisplayed()
        composeRule.onNodeWithText("Повторить").assertIsDisplayed()
    }

    @Test
    fun error_retryClick_firesOnRetry() {
        var retried = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                RepeatTripContent(
                    loggedIn = true, loading = false, loadError = true,
                    frequentTrips = emptyList(), demoTrips = emptyList(), submittingRoute = null,
                    onLoginRequired = {}, onRetry = { retried = true }, onRepeat = {},
                )
            }
        }
        composeRule.onNodeWithText("Повторить").performClick()
        assertTrue(retried)
    }

    @Test
    fun empty_loggedIn_showsHistoryPlaceholderAndQuickOptions() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                RepeatTripContent(
                    loggedIn = true, loading = false, loadError = false,
                    frequentTrips = emptyList(), demoTrips = demoFrequentTrips, submittingRoute = null,
                    onLoginRequired = {}, onRetry = {}, onRepeat = {},
                )
            }
        }
        composeRule.onNodeWithText("Истории пока нет").assertIsDisplayed()
        // Пусто + залогинен → показываем «Быстрые варианты» с демо-маршрутами.
        composeRule.onNodeWithText("Быстрые варианты").assertIsDisplayed()
    }

    @Test
    fun empty_bashkir_showsBashkirPlaceholder() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                RepeatTripContent(
                    loggedIn = true, loading = false, loadError = false,
                    frequentTrips = emptyList(), demoTrips = emptyList(), submittingRoute = null,
                    onLoginRequired = {}, onRetry = {}, onRepeat = {},
                )
            }
        }
        composeRule.onNodeWithText("Тарих әлегә юҡ").assertIsDisplayed()
    }

    @Test
    fun list_showsTripTitleAndRoute() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                RepeatTripContent(
                    loggedIn = true, loading = false, loadError = false,
                    frequentTrips = listOf(trip()), demoTrips = demoFrequentTrips, submittingRoute = null,
                    onLoginRequired = {}, onRetry = {}, onRepeat = {},
                )
            }
        }
        composeRule.onNodeWithText("Мой рейс").assertIsDisplayed()
        composeRule.onNodeWithText("Баймаҡ → Сибай").assertIsDisplayed()
        // Есть реальные маршруты → блок «Быстрые варианты» скрыт.
        composeRule.onNodeWithText("Быстрые варианты").assertDoesNotExist()
    }

    @Test
    fun list_tripClick_firesOnRepeatWithTrip() {
        var repeated: FrequentTrip? = null
        val t = trip(title = "Мой рейс")
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                RepeatTripContent(
                    loggedIn = true, loading = false, loadError = false,
                    frequentTrips = listOf(t), demoTrips = emptyList(), submittingRoute = null,
                    onLoginRequired = {}, onRetry = {}, onRepeat = { repeated = it },
                )
            }
        }
        composeRule.onNodeWithText("Мой рейс").performClick()
        assertEquals("Мой рейс", repeated?.title)
    }
}
