package com.yuldash.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddRoad
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.yuldash.app.data.BoostPlanDto
import com.yuldash.app.data.RideDto
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Content-композаблы экранов Boost/поднятие (`BoostRideRow` / `BoostPlanCard` / `StateMessage`):
 * чистые под-компоненты (примитивы + `appText`, без сети/state/context/QR) стали `internal` →
 * покрываем на JVM через Robolectric (без эмулятора). `BoostResultCard` НЕ трогаем: тянет
 * `SberPayBlock` (QR-битмап + LocalContext + буфер обмена) — не чистый.
 *
 * Заголовок класса — как в RobolectricSmokeTest / SecondaryScreensContentTest. Карточки Boost
 * анимируются (`animateColorAsState` рамки + `bounceClick`), поэтому в их тестах первой строкой
 * гасим авто-часы: `composeRule.mainClock.autoAdvance = false`, иначе бесконечный idle-sync.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SupportBoostContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun ride(
        id: Int = 1,
        fromCity: String = "Уфа",
        toCity: String = "Казань",
        seatsLeft: Int = 3,
        price: Int = 500,
        boosted: Boolean = false,
    ) = RideDto(
        id = id,
        fromCity = fromCity,
        toCity = toCity,
        departAt = "2026-07-03T10:00",
        seatsTotal = 4,
        seatsLeft = seatsLeft,
        price = price,
        category = "people",
        driverName = "Азат",
        driverRating = 4.9,
        driverVerified = true,
        driverCar = "Kia Rio",
        boosted = boosted,
    )

    // --- BoostRideRow: строка выбора поездки (маршрут + места·цена + бейдж «уже поднята») ---

    @Test
    fun boostRideRow_showsRouteAndSeatsPrice() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                BoostRideRow(ride(fromCity = "Уфа", toCity = "Казань", seatsLeft = 3, price = 500),
                    selected = false, onClick = {})
            }
        }
        composeRule.onNodeWithText("Уфа → Казань").assertIsDisplayed()
        composeRule.onNodeWithText("3 места · 500 ₽").assertIsDisplayed()
    }

    @Test
    fun boostRideRow_boosted_showsBashkirBadge() {
        // boosted=true + язык башкирский → бейдж «күтәрелгән» (ba-ветка appText).
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                BoostRideRow(ride(boosted = true), selected = true, onClick = {})
            }
        }
        composeRule.onNodeWithText("күтәрелгән").assertIsDisplayed()
    }

    @Test
    fun boostRideRow_click_firesCallback() {
        // Вся карточка кликабельна через bounceClick → тап по тексту маршрута срабатывает.
        composeRule.mainClock.autoAdvance = false
        var fired = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                BoostRideRow(ride(fromCity = "Сибай", toCity = "Уфа"),
                    selected = false, onClick = { fired = true })
            }
        }
        assertFalse(fired)
        composeRule.onNodeWithText("Сибай → Уфа").performClick()
        assertTrue(fired)
    }

    // --- BoostPlanCard: карточка тарифа поднятия (заголовок + подзаголовок по tier + цена) ---

    @Test
    fun boostPlanCard_showsTitleAndPrice() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                BoostPlanCard(
                    BoostPlanDto(tier = "quick", title = "Быстрый подъём", price = 49, hours = 2),
                    selected = false, onClick = {},
                )
            }
        }
        composeRule.onNodeWithText("Быстрый подъём").assertIsDisplayed()
        composeRule.onNodeWithText("49 ₽").assertIsDisplayed()
        // tier=quick, RU → "2 часа выше в списке"
        composeRule.onNodeWithText("2 часа выше в списке").assertIsDisplayed()
    }

    @Test
    fun boostPlanCard_click_firesCallback() {
        composeRule.mainClock.autoAdvance = false
        var fired = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                BoostPlanCard(
                    BoostPlanDto(tier = "day", title = "На день", price = 149, hours = 24),
                    selected = false, onClick = { fired = true },
                )
            }
        }
        assertFalse(fired)
        composeRule.onNodeWithText("На день").performClick()
        assertTrue(fired)
    }

    // --- StateMessage: заглушка состояния (иконка + заголовок + текст + кнопка) ---

    @Test
    fun stateMessage_showsTitleTextAndAction() {
        // Тексты приходят готовыми — компонент рисует их как есть, без appText внутри.
        composeRule.setContent {
            StateMessage(
                icon = Icons.Default.Refresh,
                title = "Не удалось загрузить",
                text = "Проверь соединение и попробуй снова.",
                actionText = "Повторить",
                onAction = {},
            )
        }
        composeRule.onNodeWithText("Не удалось загрузить").assertIsDisplayed()
        composeRule.onNodeWithText("Проверь соединение и попробуй снова.").assertIsDisplayed()
        composeRule.onNodeWithText("Повторить").assertIsDisplayed()
    }

    @Test
    fun stateMessage_clickingAction_firesCallback() {
        var fired = false
        composeRule.setContent {
            StateMessage(
                icon = Icons.Default.AddRoad,
                title = "Нет активных поездок",
                text = "Сначала опубликуй поездку.",
                actionText = "Понятно",
                onAction = { fired = true },
            )
        }
        assertFalse(fired)
        composeRule.onNodeWithText("Понятно").performClick()
        assertTrue(fired)
    }
}
