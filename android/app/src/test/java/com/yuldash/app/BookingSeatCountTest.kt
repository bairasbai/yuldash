package com.yuldash.app

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.yuldash.app.ui.theme.YuldashTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w320dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookingSeatCountTest {
    @get:Rule val compose = createComposeRule()

    private fun check(language: AppLanguage) {
        val selected = mutableIntStateOf(1)
        val busy = mutableStateOf(false)
        val ba = language == AppLanguage.Ba
        val less = if (ba) "Урындарҙы кәметеү" else "Меньше мест"
        val more = if (ba) "Урындарҙы арттырыу" else "Больше мест"
        compose.setContent {
            YuldashTheme {
                CompositionLocalProvider(LocalAppLanguage provides language) {
                    BookingSeatCount(available = 3, selected = selected.intValue, price = 500,
                        enabled = !busy.value, onChange = { selected.intValue = it })
                }
            }
        }
        compose.onNodeWithContentDescription(less).assertIsNotEnabled()
        repeat(2) { compose.onNodeWithContentDescription(more).performClick() }
        compose.onNodeWithText((if (ba) "Бөтәһе: " else "Итого: ") + "1 500 ₽").assertExists()
        compose.onNodeWithContentDescription(more).assertIsNotEnabled()
        compose.onNodeWithContentDescription(less).performClick()
        compose.runOnIdle { assertEquals(2, selected.intValue); busy.value = true }
        compose.onNodeWithContentDescription(less).assertIsNotEnabled()
        compose.onNodeWithContentDescription(more).assertIsNotEnabled()
        compose.runOnIdle { busy.value = false }
        compose.onNodeWithContentDescription(more).assertIsEnabled()
    }

    @Test fun quantityAndTotalRu() = check(AppLanguage.Ru)
    @Test fun quantityAndTotalBa() = check(AppLanguage.Ba)

    /** RS-02 В: мест стало меньше выбранного — число НЕ трогаем молча: блок отправки
     *  (canBookSeats) и явное действие «Уменьшить до K», человек решает сам. */
    private fun shrinks(language: AppLanguage) {
        val selected = mutableIntStateOf(3)
        val available = mutableIntStateOf(3)
        compose.setContent {
            YuldashTheme {
                CompositionLocalProvider(LocalAppLanguage provides language) {
                    BookingSeatCount(available = available.intValue, selected = selected.intValue, price = 500,
                        enabled = true, onChange = { selected.intValue = it })
                    Text(bookSeatsLabel(selected.intValue))
                }
            }
        }
        val ba = language == AppLanguage.Ba
        compose.onNodeWithText(if (ba) "3 урынды бронләү" else "Забронировать 3 места").assertExists()
        compose.runOnIdle { available.intValue = 2 }
        // Выбранное осталось 3 (не clamp молча), рядом — предупреждение и кнопка «Уменьшить до 2».
        compose.runOnIdle { assertEquals(3, selected.intValue) }
        compose.onNodeWithText(if (ba) "2 тиклем кәметеү" else "Уменьшить до 2").assertExists()
        compose.onNodeWithText(if (ba) "2 тиклем кәметеү" else "Уменьшить до 2").performClick()
        compose.runOnIdle { assertEquals(2, selected.intValue) }
        compose.onNodeWithText(if (ba) "2 урынды бронләү" else "Забронировать 2 места").assertExists()
        compose.onNodeWithText((if (ba) "Бөтәһе: " else "Итого: ") + "1 000 ₽").assertExists()
        compose.runOnIdle { selected.intValue = 1 }
        compose.onNodeWithText(if (ba) "Урынды бронләү" else "Забронировать место").assertExists()
    }

    @Test fun shrinksToAvailableRu() = shrinks(AppLanguage.Ru)
    @Test fun shrinksToAvailableBa() = shrinks(AppLanguage.Ba)
}

/** RS-02 В: правила кнопки брони — чистая логика без UI. */
class BookingSeatCountRulesTest {

    @Test
    fun `выбранное в пределах доступного — можно`() {
        assertTrue(canBookSeats(available = 3, selected = 1, busy = false))
        assertTrue(canBookSeats(available = 3, selected = 3, busy = false))
    }

    @Test
    fun `мест стало меньше выбранного — отправка заблокирована, а не clamp`() {
        assertFalse(canBookSeats(available = 2, selected = 3, busy = false))
    }

    @Test
    fun `ноль доступного или занятая отправка — нельзя`() {
        assertFalse(canBookSeats(available = 0, selected = 1, busy = false))
        assertFalse(canBookSeats(available = 3, selected = 1, busy = true))
    }
}
