package com.yuldash.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * «В глубину», раунд 4: добираем НЕпокрытые вторичные экраны SecondaryScreens.kt, которые уже
 * `internal` и не требуют выноса — они статичны либо читают только SharedPreferences (Robolectric
 * даёт Context на JVM). Ничего в исходнике не меняли (0 изменений поведения).
 *
 * Покрываем то, чего не касались Secondary{,Deep,Deep2,Deep3}ContentTest / FeedCabinetIntegrationTest:
 *  • [ThemePickerDialog]  — выбор темы (radio-опции, onPick/onDismiss, отметка текущей).
 *  • [RulesScreen]        — статические «Правила поездок» (нумерация, back).
 *  • [PaymentInfoScreen]  — статическая «Оплата поездок» (шаги, back).
 *  • [FiltersScreen]      — фильтры по умолчанию (тумблеры сохраняются в SharedPreferences).
 *  • [HelpScreen]         — «Помощь»: FAQ-раскрытие, поиск-фильтр, кнопки поддержки, пустая реклама.
 *
 * ⭐ Высокое окно (w411dp-h2600dp): плоские экраны без своего скролла целиком попадают в кадр,
 *    performScrollToNode не нужен и не флейкует. Строки — ДОСЛОВНО из исходника под нужный язык.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SecondaryDeep4ContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    // Context на JVM берём через Robolectric (androidx.test:core недоступен в testImplementation).
    private fun ctx(): Context = org.robolectric.RuntimeEnvironment.getApplication()

    @Before
    fun clearPrefs() {
        // Чистим prefs фильтров И настроек — иначе состояние «протекает» между тестами и валит асёрты.
        ctx().getSharedPreferences("yuldash_filters", Context.MODE_PRIVATE).edit().clear().apply()
        ctx().getSharedPreferences("yuldash_settings", Context.MODE_PRIVATE).edit().clear().apply()
    }

    // ================= ThemePickerDialog =================

    @Test
    fun themePicker_russian_showsAllThreeOptionsAndTitle() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                ThemePickerDialog(current = null, onPick = {}, onDismiss = {})
            }
        }
        composeRule.onNodeWithText("Тема оформления").assertIsDisplayed()
        composeRule.onNodeWithText("Как в системе").assertIsDisplayed()
        composeRule.onNodeWithText("Светлая").assertIsDisplayed()
        composeRule.onNodeWithText("Тёмная").assertIsDisplayed()
        composeRule.onNodeWithText("Готово").assertIsDisplayed()
    }

    @Test
    fun themePicker_bashkir_showsTitle() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                ThemePickerDialog(current = null, onPick = {}, onDismiss = {})
            }
        }
        composeRule.onNodeWithText("Биҙәлеш темаһы").assertIsDisplayed()
        composeRule.onNodeWithText("Ҡараңғы").assertIsDisplayed()   // «Тёмная»
    }

    @Test
    fun themePicker_pickDark_firesOnPickWithTrue() {
        var picked: Boolean? = null
        var pickedCalled = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                ThemePickerDialog(current = null, onPick = { picked = it; pickedCalled = true }, onDismiss = {})
            }
        }
        composeRule.onNodeWithText("Тёмная").performClick()
        assertTrue(pickedCalled)
        assertEquals(true, picked)
    }

    @Test
    fun themePicker_pickSystem_firesOnPickWithNull() {
        var pickedCalled = false
        var picked: Boolean? = true   // заведомо не-null: убедимся, что «Как в системе» кладёт null
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                ThemePickerDialog(current = false, onPick = { picked = it; pickedCalled = true }, onDismiss = {})
            }
        }
        composeRule.onNodeWithText("Как в системе").performClick()
        assertTrue(pickedCalled)
        assertNull(picked)
    }

    @Test
    fun themePicker_doneButton_firesOnDismiss() {
        var dismissed = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                ThemePickerDialog(current = null, onPick = {}, onDismiss = { dismissed = true })
            }
        }
        assertFalse(dismissed)
        composeRule.onNodeWithText("Готово").performClick()
        assertTrue(dismissed)
    }

    // ================= RulesScreen =================

    @Test
    fun rules_russian_showsHeaderAndFirstRule() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                RulesScreen(onBack = {})
            }
        }
        composeRule.onNodeWithText("Правила поездок").assertIsDisplayed()   // заголовок TopBar
        composeRule.onNodeWithText("Уважайте друг друга").assertIsDisplayed()
        composeRule.onNodeWithText("Безопасность прежде всего").assertIsDisplayed()
    }

    @Test
    fun rules_bashkir_showsHeader() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                RulesScreen(onBack = {})
            }
        }
        composeRule.onNodeWithText("Сәфәр ҡағиҙәләре").assertIsDisplayed()
        composeRule.onNodeWithText("Бер-берегеҙҙе хөрмәт итегеҙ").assertIsDisplayed()   // «Уважайте друг друга»
    }

    @Test
    fun rules_showsNumberedBadges() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                RulesScreen(onBack = {})
            }
        }
        // Нумерация карточек: 1..5 (высокое окно → все влезают).
        composeRule.onNodeWithText("1").assertIsDisplayed()
        composeRule.onNodeWithText("5").assertIsDisplayed()
    }

    @Test
    fun rules_backButton_firesOnBack() {
        var backed = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                RulesScreen(onBack = { backed = true })
            }
        }
        // Кнопка «назад» в ScreenTopBar — иконка-кнопка с contentDescription «Назад».
        composeRule.onNodeWithContentDescription("Назад").performClick()
        assertTrue(backed)
    }

    // ================= PaymentInfoScreen =================

    @Test
    fun paymentInfo_russian_showsHeaderCardAndSteps() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                PaymentInfoScreen(onBack = {})
            }
        }
        composeRule.onNodeWithText("Оплата поездок").assertIsDisplayed()   // заголовок TopBar
        composeRule.onNodeWithText("Оплата напрямую водителю").assertIsDisplayed()
        composeRule.onNodeWithText("Как это работает").assertIsDisplayed()
        // Шаги оплаты (PaymentStepRow) — реальные строки контента.
        composeRule.onNodeWithText("Договоритесь о цене в чате").assertIsDisplayed()
    }

    @Test
    fun paymentInfo_bashkir_showsHeader() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                PaymentInfoScreen(onBack = {})
            }
        }
        composeRule.onNodeWithText("Сәфәр түләүе").assertIsDisplayed()
        composeRule.onNodeWithText("Тура водителгә түләү").assertIsDisplayed()   // «Оплата напрямую водителю»
    }

    @Test
    fun paymentInfo_showsAllThreeStepNumbers() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                PaymentInfoScreen(onBack = {})
            }
        }
        composeRule.onNodeWithText("1").assertIsDisplayed()
        composeRule.onNodeWithText("2").assertIsDisplayed()
        composeRule.onNodeWithText("3").assertIsDisplayed()
    }

    @Test
    fun paymentInfo_backButton_firesOnBack() {
        var backed = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                PaymentInfoScreen(onBack = { backed = true })
            }
        }
        composeRule.onNodeWithContentDescription("Назад").performClick()
        assertTrue(backed)
    }

    // ================= FiltersScreen =================

    @Test
    fun filters_russian_showsDescriptionAndToggles() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                FiltersScreen(onBack = {})
            }
        }
        composeRule.onNodeWithText("Фильтры по умолчанию").assertIsDisplayed()   // заголовок TopBar
        composeRule.onNodeWithText("Только женщины").assertIsDisplayed()
        composeRule.onNodeWithText("Детское кресло").assertIsDisplayed()
        composeRule.onNodeWithText("Некурящий").assertIsDisplayed()
    }

    @Test
    fun filters_bashkir_showsToggle() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                FiltersScreen(onBack = {})
            }
        }
        composeRule.onNodeWithText("Ғәҙәти фильтрҙар").assertIsDisplayed()
        composeRule.onNodeWithText("Тик ҡатын-ҡыҙ").assertIsDisplayed()   // «Только женщины»
    }

    @Test
    fun filters_toggleSwitch_persistsToPrefs() {
        // Стартовое состояние — чисто (см. @Before): набор фильтров пуст.
        assertTrue(FilterPrefs.load(ctx()).isEmpty())
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                FiltersScreen(onBack = {})
            }
        }
        // Первый тумблер («Только женщины» → ключ "women") — включаем.
        composeRule.onAllNodes(isToggleable()).onFirst().performClick()
        composeRule.runOnIdle {
            // Клик реально сохранился в SharedPreferences (toggle("women")).
            assertTrue("women" in FilterPrefs.load(ctx()))
        }
    }

    @Test
    fun filters_backButton_firesOnBack() {
        var backed = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                FiltersScreen(onBack = { backed = true })
            }
        }
        composeRule.onNodeWithContentDescription("Назад").performClick()
        assertTrue(backed)
    }

    // ================= HelpScreen =================

    // Пустая реклама → usefulAd == null → рекламная карточка не рисуется; FAQ статичен.
    @androidx.compose.runtime.Composable
    private fun helpScreen(onSelectTab: (HomeTab) -> Unit = {}) {
        HelpScreen(
            ads = emptyList(),
            adStats = emptyMap(),
            onBack = {},
            onSelectTab = onSelectTab,
            onAdImpression = {},
            onAdClick = {},
        )
    }

    @Test
    fun help_russian_showsHeaderAndFaqQuestions() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) { helpScreen() }
        }
        composeRule.onNodeWithText("Помощь").assertIsDisplayed()
        composeRule.onNodeWithText("Популярные вопросы").assertIsDisplayed()
        composeRule.onNodeWithText("Как найти поездку?").assertIsDisplayed()
        composeRule.onNodeWithText("Как создать заявку?").assertIsDisplayed()
    }

    @Test
    fun help_bashkir_showsHeader() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) { helpScreen() }
        }
        composeRule.onNodeWithText("Ярдам").assertIsDisplayed()   // «Помощь»
        composeRule.onNodeWithText("Сәфәрҙе нисек табырға?").assertIsDisplayed()   // «Как найти поездку?»
    }

    @Test
    fun help_expandFaqRow_revealsAnswer() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) { helpScreen() }
        }
        // Ответ скрыт (AnimatedVisibility collapsed) до клика по вопросу.
        val answerStart = "Откройте вкладку «Карта» или «Поездки»."
        composeRule.onAllNodesWithText(answerStart, substring = true).assertCountEquals(0)
        composeRule.onNodeWithText("Как найти поездку?").performClick()
        // После клика ответ раскрылся (substring — текст длинный).
        composeRule.onAllNodesWithText(answerStart, substring = true).onFirst().assertIsDisplayed()
    }

    @Test
    fun help_searchNoMatch_showsNothingFoundCard() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) { helpScreen() }
        }
        // Вводим заведомо отсутствующий запрос → FAQ фильтруется в пусто → заглушка «Ничего не найдено».
        composeRule.onNodeWithText("Поиск по вопросам").performTextInput("zzzчепуха")
        composeRule.onNodeWithText("Ничего не найдено").assertIsDisplayed()
        composeRule.onNodeWithText("Как найти поездку?").assertDoesNotExist()
    }

    @Test
    fun help_searchNarrowsToSingleQuestion() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) { helpScreen() }
        }
        // «водител» встречается в вопросе про проверку водителя → сужаем список до него.
        composeRule.onNodeWithText("Поиск по вопросам").performTextInput("водител")
        composeRule.onNodeWithText("Как проходит проверка водителя?").assertIsDisplayed()
        composeRule.onNodeWithText("Как найти поездку?").assertDoesNotExist()
    }

    @Test
    fun help_supportSection_showsContactRows() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) { helpScreen() }
        }
        // Заголовок секции и обе строки-навигации поддержки (высокое окно → всё в кадре).
        composeRule.onNodeWithText("Написать в Telegram").assertIsDisplayed()
        composeRule.onNodeWithText("Мы отвечаем ежедневно с 9:00 до 21:00").assertIsDisplayed()
    }

    @Test
    fun help_telegramRow_clickDoesNotCrash() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) { helpScreen() }
        }
        // runCatching внутри onClick глотает отсутствие Telegram-приложения на JVM → клик безопасен.
        composeRule.onNodeWithText("Написать в Telegram").performClick()
        composeRule.onNodeWithText("Помощь").assertIsDisplayed()   // экран жив после клика
    }
}
