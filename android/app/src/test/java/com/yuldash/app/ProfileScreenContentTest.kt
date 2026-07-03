package com.yuldash.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EventSeat
import androidx.compose.material.icons.filled.Payments
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Пилот «content-композаблы» экрана ПРОФИЛЯ: чистые (без сети/state/эффектов) под-компоненты
 * стали `internal` → покрываем их на JVM через Robolectric. Проверяем ядро продукта: двуязычие
 * (RU/BA) и клик по карточкам действий.
 *
 * Заголовок класса — как в RobolectricSmokeTest. Анимация нажатия (bounceClick) затухает сама →
 * autoAdvance отключать не нужно (как в LoginScreenContentTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfileScreenContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    // AdStatusBadge: сам резолвит текст через appText(ru, ba) по LocalAppLanguage → оборачиваем в провайдер.
    @Test
    fun adStatusBadge_active_russian() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                AdStatusBadge(status = "active")
            }
        }
        composeRule.onNodeWithText("Активно").assertIsDisplayed()
    }

    @Test
    fun adStatusBadge_active_bashkir() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                AdStatusBadge(status = "active")
            }
        }
        composeRule.onNodeWithText("Актив").assertIsDisplayed()
    }

    @Test
    fun adStatusBadge_pendingReview_russian() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                AdStatusBadge(status = "pending_review")
            }
        }
        composeRule.onNodeWithText("На модерации").assertIsDisplayed()
    }

    @Test
    fun adStatusBadge_draft_bashkir() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                AdStatusBadge(status = "draft")
            }
        }
        composeRule.onNodeWithText("Ҡаралама").assertIsDisplayed()
    }

    // ProfileActionCard: заголовок и подпись приходят готовыми строками; клик проходит через bounceClick.
    @Test
    fun profileActionCard_showsTitleAndText() {
        composeRule.setContent {
            ProfileActionCard(
                title = "Кабинет пассажира",
                text = "Мои брони, заявки и безопасность",
                icon = Icons.Default.EventSeat,
                onClick = {},
            )
        }
        composeRule.onNodeWithText("Кабинет пассажира").assertIsDisplayed()
        composeRule.onNodeWithText("Мои брони, заявки и безопасность").assertIsDisplayed()
    }

    @Test
    fun profileActionCard_click_firesCallback() {
        var clicked = false
        composeRule.setContent {
            ProfileActionCard(
                title = "Кабинет пассажира",
                text = "Мои брони, заявки и безопасность",
                icon = Icons.Default.EventSeat,
                onClick = { clicked = true },
            )
        }
        composeRule.onNodeWithText("Кабинет пассажира").performClick()
        assertTrue(clicked)
    }

    // DangerActionCard: красная карточка удаления аккаунта — тоже готовые строки + клик через bounceClick.
    @Test
    fun dangerActionCard_showsTitleAndText() {
        composeRule.setContent {
            DangerActionCard(
                title = "Удалить аккаунт",
                text = "Навсегда удалить профиль и все данные",
                onClick = {},
            )
        }
        composeRule.onNodeWithText("Удалить аккаунт").assertIsDisplayed()
        composeRule.onNodeWithText("Навсегда удалить профиль и все данные").assertIsDisplayed()
    }

    @Test
    fun dangerActionCard_click_firesCallback() {
        var clicked = false
        composeRule.setContent {
            DangerActionCard(
                title = "Удалить аккаунт",
                text = "Навсегда удалить профиль и все данные",
                onClick = { clicked = true },
            )
        }
        composeRule.onNodeWithText("Удалить аккаунт").performClick()
        assertTrue(clicked)
    }

    // AdChip: маленький чип с иконкой и текстом — просто отображает переданный текст.
    @Test
    fun adChip_showsText() {
        composeRule.setContent {
            AdChip(text = "Городской партнёр", icon = Icons.Default.Payments)
        }
        composeRule.onNodeWithText("Городской партнёр").assertIsDisplayed()
    }
}
