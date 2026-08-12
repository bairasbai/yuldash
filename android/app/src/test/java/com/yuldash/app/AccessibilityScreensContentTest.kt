package com.yuldash.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HeadsetMic
import androidx.compose.material.icons.filled.Person
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
 * Content-композаблы экранов доступности (простой режим, голосовая заявка): чистые
 * под-компоненты без сети/state/эффектов стали `internal` → покрываем их на JVM через
 * Robolectric. Проверяем ядро продукта: двуязычие (RU/BA) прокидывается в рендер как есть
 * и нажатия зовут колбэк.
 *
 * Заголовок класса — как в RobolectricSmokeTest. У SeniorBigAction/SimpleSmallAction есть
 * bounceClick (анимация нажатия), поэтому первой строкой глушим авто-часы: autoAdvance = false.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccessibilityScreensContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    // ---------- SeniorBigAction: большая карточка простого режима ----------

    @Test
    fun seniorBigAction_showsTitleAndSubtitle() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            SeniorBigAction(
                icon = Icons.Default.HeadsetMic,
                title = "Сказать маршрут",
                subtitle = "Голосом создать заявку",
                onClick = {}
            )
        }
        composeRule.onNodeWithText("Сказать маршрут").assertIsDisplayed()
        composeRule.onNodeWithText("Голосом создать заявку").assertIsDisplayed()
    }

    @Test
    fun seniorBigAction_rendersBashkirTextFromAppTextFor() {
        composeRule.mainClock.autoAdvance = false
        // Двуязычный конвейер отдаёт башкирский → компонент рисует ровно его.
        val title = appTextFor(AppLanguage.Ba, "Позвони мне", "Миңә шылтырат")
        val subtitle = appTextFor(AppLanguage.Ba, "Помощник сам перезвонит", "Ярдамсы үҙе шылтыратыр")
        composeRule.setContent {
            SeniorBigAction(icon = Icons.Default.HeadsetMic, title = title, subtitle = subtitle, onClick = {})
        }
        composeRule.onNodeWithText("Миңә шылтырат").assertIsDisplayed()
        composeRule.onNodeWithText("Ярдамсы үҙе шылтыратыр").assertIsDisplayed()
    }

    @Test
    fun seniorBigAction_clickFiresCallback() {
        composeRule.mainClock.autoAdvance = false
        var clicked = false
        composeRule.setContent {
            SeniorBigAction(
                icon = Icons.Default.HeadsetMic,
                title = "SOS",
                subtitle = "Экстренная помощь",
                onClick = { clicked = true }
            )
        }
        composeRule.onNodeWithText("SOS").performClick()
        assertTrue(clicked)
    }

    // ---------- SimpleSmallAction: маленькая квадратная кнопка ----------

    @Test
    fun simpleSmallAction_showsTitle() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            SimpleSmallAction(title = "Чат", icon = Icons.Default.Person, onClick = {})
        }
        composeRule.onNodeWithText("Чат").assertIsDisplayed()
    }

    @Test
    fun simpleSmallAction_clickFiresCallback() {
        composeRule.mainClock.autoAdvance = false
        var clicked = false
        composeRule.setContent {
            SimpleSmallAction(
                title = "Контакты",
                icon = Icons.Default.Person,
                onClick = { clicked = true }
            )
        }
        composeRule.onNodeWithText("Контакты").performClick()
        assertTrue(clicked)
    }

    // ---------- VoiceParsedCard: карточка чеклиста «проверка заявки» ----------

    @Test
    fun voiceParsedCard_showsTitleAndAllLines() {
        composeRule.setContent {
            VoiceParsedCard(
                title = "Проверка заявки",
                lines = listOf(
                    CheckLine("Баймаҡ → Сибай"),
                    CheckLine("1 место · Обычная"),
                    CheckLine("Готовая сумма: 350 ₽", done = false)
                )
            )
        }
        composeRule.onNodeWithText("Проверка заявки").assertIsDisplayed()
        composeRule.onNodeWithText("Баймаҡ → Сибай").assertIsDisplayed()
        composeRule.onNodeWithText("1 место · Обычная").assertIsDisplayed()
        composeRule.onNodeWithText("Готовая сумма: 350 ₽").assertIsDisplayed()
    }

    @Test
    fun voiceParsedCard_bashkirTitleFromAppTextFor() {
        val title = appTextFor(AppLanguage.Ba, "Распознано", "Танылды")
        composeRule.setContent {
            VoiceParsedCard(title = title, lines = listOf(CheckLine("Баймаҡ → Сибай")))
        }
        composeRule.onNodeWithText("Танылды").assertIsDisplayed()
    }
}
