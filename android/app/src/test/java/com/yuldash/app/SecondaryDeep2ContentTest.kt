package com.yuldash.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.yuldash.app.data.AdminReportDto
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * «В глубину»: продолжение выноса стейта. Экран `AdminReportsScreen` (жалобы) разрезан на умную
 * обёртку (сеть/стейт) и чистый `AdminReportsContent(state, callbacks)`. Здесь Content покрыт во
 * ВСЕХ состояниях (загрузка / ошибка+повтор / пусто / список) — то, что раньше было 0%.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SecondaryDeep2ContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun report(
        id: Int = 1,
        reporterName: String = "Айгуль",
        targetName: String = "Рамиль",
        targetPhone: String = "+7 917 000-00-00",
        reason: String = "Опоздал на час",
        createdAt: String = "2026-07-01T10:00:00",
    ) = AdminReportDto(
        id = id,
        reporterName = reporterName,
        targetName = targetName,
        targetPhone = targetPhone,
        reason = reason,
        createdAt = createdAt,
    )

    @Test
    fun loading_showsSpinnerTextNotList() {
        composeRule.mainClock.autoAdvance = false   // «висящее» состояние загрузки
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                AdminReportsContent(loading = true, error = null, reports = listOf(report()), onRetry = {})
            }
        }
        composeRule.onNodeWithText("Загрузка…").assertIsDisplayed()
        composeRule.onNodeWithText("Айгуль  →  Рамиль").assertDoesNotExist()
    }

    @Test
    fun error_showsMessageAndRetryButton() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                AdminReportsContent(loading = false, error = "Сеть упала", reports = emptyList(), onRetry = {})
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
                AdminReportsContent(loading = false, error = "Ошибка", reports = emptyList(), onRetry = { retried = true })
            }
        }
        composeRule.onNodeWithText("Повторить").performClick()
        assertTrue(retried)
    }

    @Test
    fun empty_russian_showsFriendlyPlaceholder() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                AdminReportsContent(loading = false, error = null, reports = emptyList(), onRetry = {})
            }
        }
        composeRule.onNodeWithText("Жалоб нет").assertIsDisplayed()
        composeRule.onNodeWithText("Хороший знак — пользователи довольны.").assertIsDisplayed()
    }

    @Test
    fun empty_bashkir_showsBashkirPlaceholder() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                AdminReportsContent(loading = false, error = null, reports = emptyList(), onRetry = {})
            }
        }
        composeRule.onNodeWithText("Ялыу юҡ").assertIsDisplayed()
    }

    @Test
    fun list_showsReporterTargetPhoneReasonAndDate() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                AdminReportsContent(loading = false, error = null, reports = listOf(report()), onRetry = {})
            }
        }
        composeRule.onNodeWithText("Айгуль  →  Рамиль").assertIsDisplayed()
        composeRule.onNodeWithText("+7 917 000-00-00").assertIsDisplayed()
        composeRule.onNodeWithText("Опоздал на час").assertIsDisplayed()
        composeRule.onNodeWithText("2026-07-01").assertIsDisplayed()   // createdAt.take(10)
    }

    @Test
    fun list_blankReason_showsNoReasonFallback() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                AdminReportsContent(loading = false, error = null, reports = listOf(report(reason = "")), onRetry = {})
            }
        }
        // Текст фолбэка переписан: было «без причины», стало «без деталей» (SecondaryScreens.kt,
        // r.reason.ifBlank { ... }). Прокрутка не нужна — в списке всего две строки, обе видны;
        // моя прежняя правда со скроллом падала внутри самого скролла, потому что искомого
        // узла не существовало вовсе.
        composeRule.onNodeWithText("без деталей").assertIsDisplayed()
    }

    @Test
    fun list_blankPhone_hidesPhoneRow() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                AdminReportsContent(loading = false, error = null, reports = listOf(report(targetPhone = "")), onRetry = {})
            }
        }
        // Телефон рисуется только если непустой — пустой номер не должен появиться как строка.
        composeRule.onNodeWithText("+7 917 000-00-00").assertDoesNotExist()
        composeRule.onNodeWithText("Айгуль  →  Рамиль").assertIsDisplayed()
    }
}
