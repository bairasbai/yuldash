package com.yuldash.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Честность при обрыве связи. Аудит плохих сценариев (П9, 2026-08-04) на эмуляторе с
 * выключенной сетью нашёл: экран «Мои заявки» уверенно показывал «Заявок пока нет»
 * человеку, у которого заявка есть. Загрузка заканчивалась, ошибка нигде не всплывала —
 * `ApiClient.getMyRequests()` читался через `.onSuccess {}` без `.onFailure`.
 *
 * Это не косметика. Пассажир, увидев «заявок нет», решит, что его заявка не создалась,
 * и создаст вторую. Пустой список и недоступный сервер — разные вещи, и путать их нельзя.
 *
 * Тесты держат оба состояния раздельно, чтобы «пусто» больше никогда не значило «ошибка».
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OfflineHonestyTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun screen(loading: Boolean = false, error: Boolean = false, onRetry: () -> Unit = {}) {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                MyRequestsScreen(
                    requests = emptyList(),
                    onCreateNew = {},
                    onViewResponses = {},
                    onCancel = {},
                    loading = loading,
                    error = error,
                    onRetry = onRetry,
                )
            }
        }
    }

    @Test
    fun `обрыв связи показывает ошибку, а НЕ «заявок пока нет»`() {
        screen(error = true)
        composeRule.onNodeWithText("Не удалось загрузить заявки").assertIsDisplayed()
        // Главное утверждение теста: ложного «пусто» на экране нет.
        composeRule.onNodeWithText("Заявок пока нет").assertDoesNotExist()
    }

    @Test
    fun `при ошибке есть кнопка «Повторить» и она работает`() {
        var retried = 0
        screen(error = true, onRetry = { retried++ })
        composeRule.onNodeWithText("Повторить").assertIsDisplayed()
        composeRule.onNodeWithText("Повторить").performClick()
        assertEquals(1, retried)
    }

    @Test
    fun `реально пустой список показывает «заявок пока нет», а не ошибку`() {
        screen()   // загрузка кончилась, ошибки нет, заявок нет
        composeRule.onNodeWithText("Заявок пока нет").assertIsDisplayed()
        composeRule.onNodeWithText("Не удалось загрузить заявки").assertDoesNotExist()
    }

    @Test
    fun `пока грузим — ни «пусто», ни ошибки`() {
        // Скелетон вместо ложного «Заявок пока нет», которое мелькало на первой загрузке.
        screen(loading = true)
        composeRule.onNodeWithText("Заявок пока нет").assertDoesNotExist()
        composeRule.onNodeWithText("Не удалось загрузить заявки").assertDoesNotExist()
    }
}
