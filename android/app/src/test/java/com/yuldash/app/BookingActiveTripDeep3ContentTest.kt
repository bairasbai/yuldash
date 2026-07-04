package com.yuldash.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isToggleable
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
 * «В глубину-3» по `BookingActiveTripScreen.kt`: покрываем ОСТАВШИЕСЯ чистые компоненты настроек/
 * профиля, что живут в этом файле и раньше не тестировались нигде — строка-навигация настроек
 * [SettingsNavRow], строка-тумблер [SettingSwitchRow], обёртка группы [SettingsGroup] и
 * компактный баннер профиля [CompactProfileBanner]. Все `internal`, рисуют только
 * Text/Icon/Row/Column/Surface/Card/Switch — без карты/сокета/чата/сети → покрываемы на JVM
 * через Robolectric.
 *
 * Двуязычие — через appText (LocalAppLanguage). Тексты в ассертах — ДОСЛОВНО из
 * BookingActiveTripScreen.kt под нужный язык (RU/BA не мешаем). Switch → onNode(isToggleable())
 * (клик по тумблеру, не по тексту). Анимаций у этих компонентов нет → autoAdvance не трогаем.
 *
 * Пузырь чата (MessageBubble) и карточки статуса покрыты в BookingActiveTripContentDeepTest /
 * BookingActiveTripDeep2ContentTest — тут их НЕ дублируем. Заголовок класса — как в тех файлах.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookingActiveTripDeep3ContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    // --- SettingsNavRow: иконка + заголовок + подпись + (опц.) стрелка/клик ---

    @Test
    fun navRow_showsTitleAndSubtitle() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                SettingsNavRow(
                    icon = Icons.Default.Language,
                    title = "Язык",
                    subtitle = "Русский",
                    onClick = {},
                )
            }
        }
        composeRule.onNodeWithText("Язык").assertIsDisplayed()
        composeRule.onNodeWithText("Русский").assertIsDisplayed()
    }

    @Test
    fun navRow_click_firesCallback() {
        var fired = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                SettingsNavRow(
                    icon = Icons.Default.Notifications,
                    title = "Уведомления",
                    subtitle = "Push и звук",
                    onClick = { fired = true },
                )
            }
        }
        assertFalse(fired)
        composeRule.onNodeWithText("Уведомления").performClick()
        assertTrue(fired)
    }

    @Test
    fun navRow_noOnClick_rendersWithoutCrash() {
        // onClick == null → без стрелки и без bounceClick: строка просто отображается.
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                SettingsNavRow(
                    icon = Icons.Default.Language,
                    title = "Тел",
                    subtitle = "Башҡортса",
                    onClick = null,
                )
            }
        }
        composeRule.onNodeWithText("Тел").assertIsDisplayed()
        composeRule.onNodeWithText("Башҡортса").assertIsDisplayed()
    }

    // --- SettingSwitchRow: иконка + заголовок + подпись + тумблер ---

    @Test
    fun switchRow_showsTitleAndSubtitle() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                SettingSwitchRow(
                    icon = Icons.Default.Notifications,
                    title = "Пуш-уведомления",
                    subtitle = "О новых сообщениях",
                    checked = true,
                    onCheckedChange = {},
                )
            }
        }
        composeRule.onNodeWithText("Пуш-уведомления").assertIsDisplayed()
        composeRule.onNodeWithText("О новых сообщениях").assertIsDisplayed()
    }

    @Test
    fun switchRow_toggle_firesOnCheckedChange() {
        // Тумблер стартует checked=false → клик по нему отдаёт true в колбэк.
        var newValue: Boolean? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                SettingSwitchRow(
                    icon = Icons.Default.Notifications,
                    title = "Звук",
                    subtitle = "Проигрывать сигнал",
                    checked = false,
                    onCheckedChange = { newValue = it },
                )
            }
        }
        assertEquals(null, newValue)
        composeRule.onNode(isToggleable()).performClick()
        assertEquals(true, newValue)
    }

    // --- SettingsGroup: карточка-обёртка рисует переданное содержимое ---

    @Test
    fun settingsGroup_rendersChildContent() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                SettingsGroup {
                    Text("Внутри группы")
                }
            }
        }
        composeRule.onNodeWithText("Внутри группы").assertIsDisplayed()
    }

    // --- CompactProfileBanner: аватар + имя (фолбэк «Я») + роль/город + подпись о телефоне ---

    @Test
    fun compactProfileBanner_russian_showsRoleAndPhoneNote() {
        // cachedName() без залогиненного пользователя == null → имя падает в фолбэк «Я».
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                CompactProfileBanner()
            }
        }
        composeRule.onNodeWithText("Пассажир · Баймаҡ").assertIsDisplayed()
        composeRule.onNodeWithText("Телефон скрыт до подтверждения").assertIsDisplayed()
    }

    @Test
    fun compactProfileBanner_bashkir_showsPhoneNote() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                CompactProfileBanner()
            }
        }
        composeRule.onNodeWithText("Телефон раҫланғанға тиклем йәшерен").assertIsDisplayed()
    }
}
