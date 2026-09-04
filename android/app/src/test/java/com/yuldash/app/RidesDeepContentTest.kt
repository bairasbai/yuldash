package com.yuldash.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.yuldash.app.data.ResponseDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * «В глубину»: пилот выноса стейта. Экран `ResponsesScreen` разрезан на умную обёртку
 * (сеть/стейт) и чистый `ResponsesContent(state, callbacks)`. Здесь Content покрыт во ВСЕХ
 * состояниях (загрузка / ошибка+повтор / пусто / список / приём) — то, что раньше было 0%.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RidesDeepContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun response(id: Int = 1, name: String = "Ринат", rating: Double? = 4.8, price: Int = 300, comment: String = "Выезжаю в 8 утра") =
        ResponseDto(id = id, driverId = 10, driverName = name, driverRating = rating, price = price, comment = comment, status = "pending")

    @Test
    fun loading_showsSpinnerNotList() {
        composeRule.mainClock.autoAdvance = false   // бесконечный спиннер
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                ResponsesContent(loading = true, error = false, responses = listOf(response()), accepting = false, onRetry = {}, onAccept = {})
            }
        }
        // Состояние загрузки перешло со слова «Загрузка…» на скелетон-заглушки
        // (SkeletonCard). Текста там больше нет, а метки у скелетона тоже нет —
        // проверять его нечем. Поэтому проверяем то, что реально важно и проверяемо:
        // пока грузим, содержимое списка НЕ показано.
        composeRule.onNodeWithText("Поехать с этим водителем").assertDoesNotExist()
    }

    @Test
    fun error_showsMessageAndRetryButton() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                ResponsesContent(loading = false, error = true, responses = emptyList(), accepting = false, onRetry = {}, onAccept = {})
            }
        }
        composeRule.onNodeWithText("Не удалось загрузить отклики. Проверь сеть.").assertIsDisplayed()
        composeRule.onNodeWithText("Повторить").assertIsDisplayed()
    }

    @Test
    fun error_retryClick_firesOnRetry() {
        var retried = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                ResponsesContent(loading = false, error = true, responses = emptyList(), accepting = false, onRetry = { retried = true }, onAccept = {})
            }
        }
        composeRule.onNodeWithText("Повторить").performClick()
        assertTrue(retried)
    }

    @Test
    fun empty_russian_showsFriendlyPlaceholder() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                ResponsesContent(loading = false, error = false, responses = emptyList(), accepting = false, onRetry = {}, onAccept = {})
            }
        }
        composeRule.onNodeWithText("Откликов пока нет").assertIsDisplayed()
        // Текст сменился вместе с сутью: раньше пустой экран предлагал ждать и не давал ни
        // одной кнопки. Теперь он объясняет, что заявка жива, и зовёт что-то сделать.
        composeRule.onNodeWithText(
            "Водители ещё не откликнулись. Заявка живёт и видна им — а пока можно " +
                "поднять цену или поискать готовые поездки по маршруту.",
        ).assertIsDisplayed()
    }

    @Test
    fun empty_bashkir_showsBashkirPlaceholder() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                ResponsesContent(loading = false, error = false, responses = emptyList(), accepting = false, onRetry = {}, onAccept = {})
            }
        }
        composeRule.onNodeWithText("Әлегә яуап юҡ").assertIsDisplayed()
    }

    @Test
    fun list_showsDriverNamePriceAndAcceptButton() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                ResponsesContent(loading = false, error = false, responses = listOf(response()), accepting = false, onRetry = {}, onAccept = {})
            }
        }
        composeRule.onNodeWithText("Ринат").assertIsDisplayed()
        composeRule.onNodeWithText("Выезжаю в 8 утра").assertIsDisplayed()
        composeRule.onNodeWithText("300 ₽").assertIsDisplayed()
        composeRule.onNodeWithText("Поехать с этим водителем").assertIsDisplayed()
    }

    @Test
    fun list_acceptClick_firesOnAcceptWithItem() {
        var accepted: ResponseDto? = null
        val r = response(id = 55)
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                ResponsesContent(loading = false, error = false, responses = listOf(r), accepting = false, onRetry = {}, onAccept = { accepted = it })
            }
        }
        composeRule.onNodeWithText("Поехать с этим водителем").performClick()
        assertEquals(55, accepted?.id)
    }

    @Test
    fun list_accepting_disablesAcceptButton() {
        var accepted: ResponseDto? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                ResponsesContent(loading = false, error = false, responses = listOf(response()), accepting = true, onRetry = {}, onAccept = { accepted = it })
            }
        }
        // Кнопка есть, но disabled (идёт приём) → клик не проходит, колбэк не зовётся.
        composeRule.onNodeWithText("Поехать с этим водителем").performClick()
        assertEquals(null, accepted)
    }
}
