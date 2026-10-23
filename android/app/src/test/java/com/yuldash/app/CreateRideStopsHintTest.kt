package com.yuldash.app

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Водитель узнаёт правило остановок до ввода, а не ошибкой после «Опубликовать»: с адресом или
 * номером дома остановка не пройдёт ни проверку приложения, ни сервер. Пока остановок нет —
 * лишней строки нет.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CreateRideStopsHintTest {
    @get:Rule val compose = createComposeRule()

    @Test fun ruleAppearsWithFirstStopRu() = ruleAppearsWithFirstStop(
        AppLanguage.Ru, "Добавить остановку",
        "Только сёла и города из подсказок — без адреса и цифр. До четырёх остановок.",
    )

    @Test fun ruleAppearsWithFirstStopBa() = ruleAppearsWithFirstStop(
        AppLanguage.Ba, "Туҡталыш өҫтәү",
        "Тәҡдимдәрҙән ауылдар һәм ҡалалар ғына — адресһыҙ һәм һандарһыҙ. Дүрткә тиклем туҡталыш.",
    )

    private fun ruleAppearsWithFirstStop(language: AppLanguage, add: String, rule: String) {
        val stops = mutableStateOf(emptyList<String>())
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides language) {
                CreateRideStopsEditor(stops.value, { stops.value = it }, null)
            }
        }
        compose.onAllNodesWithText(rule).assertCountEquals(0)
        compose.onNodeWithText(add).performClick()
        compose.onNodeWithText(rule).assertIsDisplayed()
        compose.onNodeWithContentDescription(if (language == AppLanguage.Ba) "Туҡталышты алып ташлау" else "Убрать остановку")
            .performClick()
        compose.onAllNodesWithText(rule).assertCountEquals(0)
    }
}
