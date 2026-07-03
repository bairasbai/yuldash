package com.yuldash.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Content-компоненты экрана «Создать поездку»: чистые под-компоненты (чип типа поездки и подсказка
 * цены) вынесены из CreateRideScreen и стали `internal` → покрываем на JVM через Robolectric.
 * Стейт (выбранный тип, значение цены) живёт в экране, сюда приходит параметром → тестируем рендер
 * двух языков и колбэк клика напрямую, без сети/анимаций.
 *
 * Заголовок класса — как в RobolectricSmokeTest / SecondaryScreensContentTest. Анимаций нет → autoAdvance не нужен.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CreateRideContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    // --- RideTypeChip: чип выбора типа поездки (иконка + двуязычная подпись) ---

    @Test
    fun rideTypeChip_showsRussianLabel_whenLanguageRu() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                RideTypeChip(icon = Icons.Default.LocalShipping, ru = "Груз", ba = "Йөк", selected = false) {}
            }
        }
        composeRule.onNodeWithText("Груз").assertIsDisplayed()
    }

    @Test
    fun rideTypeChip_showsBashkirLabel_whenLanguageBa() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                RideTypeChip(icon = Icons.Default.LocalShipping, ru = "Груз", ba = "Йөк", selected = true) {}
            }
        }
        composeRule.onNodeWithText("Йөк").assertIsDisplayed()
    }

    @Test
    fun rideTypeChip_click_firesCallback() {
        var fired = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                RideTypeChip(icon = Icons.Default.DirectionsCar, ru = "Пассажиры", ba = "Пассажирҙар", selected = false) { fired = true }
            }
        }
        assertFalse(fired)
        composeRule.onNodeWithText("Пассажиры").performClick()
        assertTrue(fired)
    }

    // --- PriceHintChip: подсказка «обычно по маршруту ~N ₽ · нажми, чтобы подставить» ---

    @Test
    fun priceHintChip_showsRussianHintWithPrice() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                PriceHintChip(price = 350) {}
            }
        }
        composeRule.onNodeWithText("Обычно по маршруту ~350 ₽ · нажми, чтобы подставить").assertIsDisplayed()
    }

    @Test
    fun priceHintChip_showsBashkirHintWithPrice() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                PriceHintChip(price = 420) {}
            }
        }
        composeRule.onNodeWithText("Был юл буйынса ғәҙәттә ~420 ₽ · ҡуйыр өсөн баҫ").assertIsDisplayed()
    }

    @Test
    fun priceHintChip_click_firesCallback() {
        var fired = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                PriceHintChip(price = 300) { fired = true }
            }
        }
        assertFalse(fired)
        composeRule.onNodeWithText("Обычно по маршруту ~300 ₽ · нажми, чтобы подставить").performClick()
        assertTrue(fired)
    }
}
