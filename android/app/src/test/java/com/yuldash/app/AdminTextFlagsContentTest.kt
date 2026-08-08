package com.yuldash.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.yuldash.app.data.TextFlagDto
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Экран «Помеченные тексты» (модерация стала видимой, 2026-08-08).
 *
 * Главное, что проверяем: админ видит КТО и ЗА ЧТО. Раньше в пульсе было только число
 * помеченных за сегодня — посмотреть было не на что, значит и среагировать не на что.
 *
 * Отдельно стережём приватность: сам текст на экран не приходит и не показывается —
 * только ссылка на запись (см. models.TextFlag, §8).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdminTextFlagsContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun flag(
        id: Int = 1, kind: String = "abuse", place: String = "Отзыв",
        name: String = "Айрат", phone: String = "+79990001122", total: Int = 1,
    ) = TextFlagDto(
        id = id, userId = 7, userName = name, userPhone = phone,
        kind = kind, placeLabel = place, refId = 42, createdAt = "", userFlagsTotal = total,
    )

    private fun content(
        loading: Boolean = false, error: Boolean = false,
        items: List<TextFlagDto> = listOf(flag()), kind: String = "",
        onKind: (String) -> Unit = {}, onRetry: () -> Unit = {},
    ) = @androidx.compose.runtime.Composable {
        AdminTextFlagsContent(loading, error, items, kind, onKind, onRetry)
    }

    @Test
    fun list_showsWhoAndForWhat() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) { content()() }
        }
        composeRule.onNodeWithText("Грубость — мат в тексте").assertIsDisplayed()
        composeRule.onNodeWithText("Отзыв").assertIsDisplayed()
        composeRule.onNodeWithText("Айрат · +79990001122").assertIsDisplayed()
    }

    @Test
    fun repeatOffender_showsCount() {
        // Разовое срабатывание бывает у любого — важна повторяемость, и её видно сразу,
        // чтобы админ не банил человека за одно неудачное слово.
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                content(items = listOf(flag(total = 5)))()
            }
        }
        composeRule.onNodeWithText("У этого человека пометок: 5").assertIsDisplayed()
    }

    @Test
    fun singleFlag_hidesCount() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                content(items = listOf(flag(total = 1)))()
            }
        }
        composeRule.onNodeWithText("У этого человека пометок: 1").assertDoesNotExist()
    }

    @Test
    fun phishing_isCalledOutAsMoney() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                content(items = listOf(flag(kind = "warn")))()
            }
        }
        composeRule.onNodeWithText("Обман — уводят деньги").assertIsDisplayed()
    }

    @Test
    fun empty_saysItIsGoodNews() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                content(items = emptyList())()
            }
        }
        composeRule.onNodeWithText("Помеченных текстов нет").assertIsDisplayed()
    }

    @Test
    fun error_offersRetry() {
        var retried = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                content(error = true, items = emptyList(), onRetry = { retried = true })()
            }
        }
        composeRule.onNodeWithText("Повторить").performClick()
        assertEquals(true, retried)
    }

    @Test
    fun kindChip_togglesFilter() {
        var picked = "нет"
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                content(onKind = { picked = it })()
            }
        }
        composeRule.onNodeWithText("Телефон в тексте").performClick()
        assertEquals("contact", picked)
    }

    @Test
    fun screenExplains_nothingIsBlocked() {
        // Тон важен: это пометка, а не наказание. Админ должен понимать это с первой строки.
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) { content()() }
        }
        composeRule.onNodeWithText(
            "Здесь видно, кто и за что помечен. Ничего не заблокировано — текст дошёл до получателя. Решение за тобой."
        ).assertIsDisplayed()
    }

    @Test
    fun bashkir_isShown() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                content(items = emptyList())()
            }
        }
        composeRule.onNodeWithText("Билдәләнгән текст юҡ").assertIsDisplayed()
    }
}
