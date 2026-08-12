package com.yuldash.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Content-композаблы вкладок Поездки/Заявки/Чат: чистые (без сети/state/анимаций) под-компоненты
 * из RidesRequestsChatScreens.kt стали `internal` → покрываем на JVM через Robolectric (без эмулятора).
 *
 * Два берут `appText` внутри (двуязычие) → оборачиваем в CompositionLocalProvider(LocalAppLanguage…),
 * проверяем RU и BA. Два берут готовые String → тестируем рендер и колбэки напрямую.
 *
 * Заголовок класса — 1-в-1 как в RobolectricSmokeTest / SecondaryScreensContentTest.
 * Спиннеров/бесконечных анимаций в этих компонентах нет → autoAdvance не трогаем.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RidesRequestsChatContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    // --- RequestSummaryCard: карточка заявки (маршрут + мета + кнопка «Посмотреть отклики») ---
    // Внутри есть appText («Отменить заявку») → провайдер языка обязателен.

    @Test
    fun requestSummaryCard_showsRouteDateAndAction_ru() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                RequestSummaryCard(
                    icon = Icons.Default.DirectionsCar,
                    from = "Баймак",
                    to = "Уфа",
                    date = "12 мая, 08:00",
                    reason = "В больницу",
                    price = "450 ₽ предлагаю",
                    badge = "Активна",
                    action = "Посмотреть отклики",
                    onAction = {},
                    onCancel = null,
                )
            }
        }
        // Маршрут склеивается как "$from  →  $to" — проверяем точную строку из кода.
        composeRule.onNodeWithText("Баймак  →  Уфа").assertIsDisplayed()
        composeRule.onNodeWithText("12 мая, 08:00").assertIsDisplayed()
        composeRule.onNodeWithText("450 ₽ предлагаю").assertIsDisplayed()
        composeRule.onNodeWithText("Активна").assertIsDisplayed()
        composeRule.onNodeWithText("Посмотреть отклики").assertIsDisplayed()
    }

    @Test
    fun requestSummaryCard_cancelButton_bashkirLabelAndCallback() {
        // onCancel != null → показывается кнопка отмены с appText → под Ba видим башкирский текст.
        var cancelled = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                RequestSummaryCard(
                    icon = Icons.Default.DirectionsCar,
                    from = "Сибай",
                    to = "Магнитогорск",
                    date = "13 май",
                    reason = "Эшкә",
                    price = "хаҡ килешеү буйынса",
                    badge = "Актив",
                    action = "Яуаптарҙы ҡарау",
                    onAction = {},
                    onCancel = { cancelled = true },
                )
            }
        }
        composeRule.onNodeWithText("Яуаптарҙы ҡарау").assertIsDisplayed()
        // Башкирский вариант "Отменить заявку" = "Заявканы кире алыу" (appText внутри onCancel-блока).
        composeRule.onNodeWithText("Заявканы кире алыу").assertIsDisplayed()
        assertFalse(cancelled)
        composeRule.onNodeWithText("Заявканы кире алыу").performClick()
        assertTrue(cancelled)
    }

    // --- ChatEmptyState: заглушка «нет диалогов». appText внутри → проверяем оба языка. ---

    @Test
    fun chatEmptyState_russianCopy() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                ChatEmptyState()
            }
        }
        composeRule.onNodeWithText("Здесь будут твои чаты").assertIsDisplayed()
    }

    @Test
    fun chatEmptyState_bashkirCopy() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                ChatEmptyState()
            }
        }
        composeRule.onNodeWithText("Бында чаттарың булыр").assertIsDisplayed()
    }

    // --- ChatCard: строка диалога. Берёт готовые String; avatarUrl="" → рисует инициал (без сети). ---

    @Test
    fun chatCard_showsNameSubtitleMessageAndUnread() {
        composeRule.setContent {
            ChatCard(
                initial = "Р",
                name = "Рамиль",
                subtitle = "Баймак → Уфа",
                message = "Буду через 5 минут",
                time = "12:30",
                unread = 3,
                verified = true,
                avatarUrl = "", // пусто → показывается Text(initial), AsyncImage не вызывается
            )
        }
        composeRule.onNodeWithText("Рамиль").assertIsDisplayed()
        composeRule.onNodeWithText("Баймак → Уфа").assertIsDisplayed()
        composeRule.onNodeWithText("Буду через 5 минут").assertIsDisplayed()
        composeRule.onNodeWithText("12:30").assertIsDisplayed()
        composeRule.onNodeWithText("Р").assertIsDisplayed()      // инициал в аватарке
        composeRule.onNodeWithText("3").assertIsDisplayed()      // бейдж непрочитанных
    }

    @Test
    fun chatCard_clickFiresCallback() {
        var opened = false
        composeRule.setContent {
            ChatCard(
                initial = "А",
                name = "Азат",
                subtitle = "Поддержка",
                message = "Чем помочь?",
                time = "09:00",
                unread = 0,
                verified = false,
                onClick = { opened = true },
            )
        }
        assertFalse(opened)
        composeRule.onNodeWithText("Азат").performClick()
        assertTrue(opened)
    }

    // --- EmojiPicker: сетка эмодзи. Колбэк onPick(emoji) при тапе по ячейке. ---

    @Test
    fun emojiPicker_showsEmojisAndPicksOne() {
        var picked = ""
        composeRule.setContent {
            EmojiPicker(onPick = { picked = it })
        }
        // 🚗 — первый эмодзи в CHAT_EMOJIS; он точно отрисован.
        composeRule.onNodeWithText("🚗").assertIsDisplayed()
        assertEquals("", picked)
        composeRule.onNodeWithText("🚗").performClick()
        assertEquals("🚗", picked)
    }
}
