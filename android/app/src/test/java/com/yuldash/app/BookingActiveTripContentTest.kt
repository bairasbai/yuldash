package com.yuldash.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Content-композаблы экрана брони/поездки: чистые (без карты/сети/state/таймеров) под-компоненты
 * стали `internal` → покрываем на JVM через Robolectric. Здесь два таких: подпись точки на карте
 * (`BookingMapLabel`, берёт готовый String) и заглушка «карта недоступна» (`RouteMapUnavailableCard`,
 * рисует двуязычный текст через appText). Тяжёлые части экрана — карта MapKit, чат с MediaPlayer,
 * поллинг статуса — намеренно НЕ трогаем.
 *
 * Заголовок класса — как в RobolectricSmokeTest / SecondaryScreensContentTest. Анимаций в этих
 * компонентах нет → autoAdvance не нужен.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookingActiveTripContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    // --- BookingMapLabel: подпись города на карте (готовый String → одинаково для любого языка) ---

    @Test
    fun bookingMapLabel_showsGivenText() {
        composeRule.setContent { BookingMapLabel("Уфа", Modifier) }
        composeRule.onNodeWithText("Уфа").assertIsDisplayed()
    }

    @Test
    fun bookingMapLabel_showsBashkirText() {
        // Текст приходит готовым сверху — компонент рисует его как есть, без своей логики языка.
        composeRule.setContent { BookingMapLabel("Баймаҡ", Modifier) }
        composeRule.onNodeWithText("Баймаҡ").assertIsDisplayed()
    }

    // --- RouteMapUnavailableCard: заглушка «маршрут загружается» (двуязычный текст через appText) ---

    @Test
    fun routeMapUnavailableCard_russian_showsTitleAndText() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                RouteMapUnavailableCard()
            }
        }
        composeRule.onNodeWithText("Карта маршрута загружается").assertIsDisplayed()
        composeRule.onNodeWithText("Покажем реальный маршрут, когда сервер вернёт координаты.").assertIsDisplayed()
    }

    @Test
    fun routeMapUnavailableCard_bashkir_showsTitle() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                RouteMapUnavailableCard()
            }
        }
        composeRule.onNodeWithText("Маршрут картаһы йөкләнә").assertIsDisplayed()
    }
}
