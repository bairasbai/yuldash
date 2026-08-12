package com.yuldash.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.yuldash.app.data.RouteWeatherDto
import com.yuldash.app.data.WeatherWarningDto
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Карточка погоды на маршруте.
 *
 * Главное, что здесь проверяется, — МОЛЧАНИЕ. Карточка, которая появляется каждый день,
 * перестаёт читаться, и в день гололёда её пролистают вместе с остальным. Поэтому нет
 * предупреждений — нет и карточки, даже когда данные пришли.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WeatherWarningCardTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun ice() = WeatherWarningDto(
        kind = "ice",
        ru = "Гололёд на дороге. Тормозной путь длиннее в разы.",
        ba = "Юлда быҙлауыҡ. Туҡтау юлы бермә-бер оҙонораҡ.",
        severe = true,
    )

    private fun fog() = WeatherWarningDto(
        kind = "fog",
        ru = "Туман: видимость меньше 500 метров.",
        ba = "Томан: күренеү 500 метрҙан кәм.",
        severe = false,
    )

    @Test
    fun `хорошая погода — карточки нет`() {
        composeRule.setContent {
            WeatherWarningCard(RouteWeatherDto(available = true, warnings = emptyList(), temperatureC = 5.0))
        }
        composeRule.onNodeWithText("Погода на маршруте").assertDoesNotExist()
    }

    @Test
    fun `нет данных — карточки нет`() {
        composeRule.setContent {
            WeatherWarningCard(RouteWeatherDto(available = false, warnings = listOf(ice()), temperatureC = null))
        }
        composeRule.onNodeWithText("Погода на маршруте").assertDoesNotExist()
    }

    @Test
    fun `погоду не загрузили — карточки нет`() {
        composeRule.setContent { WeatherWarningCard(null) }
        composeRule.onNodeWithText("Погода на маршруте").assertDoesNotExist()
    }

    @Test
    fun `гололёд показан текстом с сервера`() {
        composeRule.setContent {
            WeatherWarningCard(RouteWeatherDto(true, listOf(ice()), -4.0))
        }
        composeRule.onNodeWithText("Погода на маршруте").assertIsDisplayed()
        composeRule.onNodeWithText(ice().ru).assertIsDisplayed()
        // Температура рядом с заголовком: «сейчас −4°» помогает понять, о чём речь.
        composeRule.onNodeWithText("Сейчас -4°").assertIsDisplayed()
    }

    @Test
    fun `в башкирском показывается башкирская строка`() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                WeatherWarningCard(RouteWeatherDto(true, listOf(ice()), -4.0))
            }
        }
        composeRule.onNodeWithText(ice().ba).assertIsDisplayed()
        composeRule.onNodeWithText(ice().ru).assertDoesNotExist()
    }

    @Test
    fun `все предупреждения видны сразу`() {
        composeRule.setContent {
            WeatherWarningCard(RouteWeatherDto(true, listOf(ice(), fog()), -4.0))
        }
        composeRule.onNodeWithText(ice().ru).assertIsDisplayed()
        composeRule.onNodeWithText(fog().ru).assertIsDisplayed()
    }

    @Test
    fun `карточка не запрещает поездку и говорит об этом`() {
        composeRule.setContent {
            WeatherWarningCard(RouteWeatherDto(true, listOf(ice()), -4.0))
        }
        composeRule.onNodeWithText("Это предупреждение, а не запрет — решай сам.").assertIsDisplayed()
    }
}
