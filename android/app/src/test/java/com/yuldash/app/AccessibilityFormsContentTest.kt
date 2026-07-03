package com.yuldash.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
 * Форм-экраны доступности/семьи. Разрезаны на умную обёртку (стейт/сеть/context) и чистый Content:
 *  - `FamilyOrderScreen` → `FamilyOrderFormContent` + хелпер `familyOrderValid`;
 *  - `CallbackHelpScreen` → `CallbackHelpContent`.
 *
 * «Плохой» сценарий валидации (пустые обязательные поля) покрыт прямыми тестами чистого хелпера
 * `familyOrderValid` — это и есть логика, по которой кнопка «Создать заявку» блокируется
 * (enabled = !loading && familyOrderValid). Гард двойного нажатия (loading→disabled) покрыт
 * аналогично в LoginForm (там кнопка доступна тесту); в форме семьи кнопка глубоко в LazyColumn
 * (проверка её UI-состояния приводила к дедлоку autoAdvance+scroll), поэтому здесь — логика + рендер.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccessibilityFormsContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    @androidx.compose.runtime.Composable
    private fun FamilyContent(
        passenger: String = "Мама",
        from: String = "Уфа",
        to: String = "Сибай",
        loading: Boolean = false,
        error: String? = null,
        onSubmit: () -> Unit = {},
    ) {
        CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
            FamilyOrderFormContent(
                passenger = passenger, phone = "", from = from, to = to,
                notifyContact = true, trustedName = "Айгуль",
                loading = loading, error = error,
                onPassengerChange = {}, onPhoneChange = {}, onNotifyContactChange = {},
                onSubmit = onSubmit,
            )
        }
    }

    // ── Плохие сценарии валидации (прямой вызов чистого хелпера) ──

    @Test
    fun familyValid_whenNameAndRouteFilled() {
        assertTrue(familyOrderValid("Мама", "Уфа", "Сибай"))
    }

    @Test
    fun familyInvalid_whenPassengerBlank() {
        assertFalse(familyOrderValid("", "Уфа", "Сибай"))
        assertFalse(familyOrderValid("   ", "Уфа", "Сибай"))
    }

    @Test
    fun familyInvalid_whenFromBlank() {
        assertFalse(familyOrderValid("Мама", "", "Сибай"))
    }

    @Test
    fun familyInvalid_whenToBlank() {
        assertFalse(familyOrderValid("Мама", "Уфа", ""))
    }

    // ── Базовый рендер формы (верх виден) ──

    @Test
    fun familyHeader_isDisplayed() {
        composeRule.setContent { FamilyContent() }
        composeRule.onNodeWithText("Кто поедет?").assertIsDisplayed()
    }

    @Test
    fun familyPassengerField_isDisplayed() {
        composeRule.setContent { FamilyContent() }
        composeRule.onNodeWithText("Имя пассажира").assertIsDisplayed()
    }

    @Test
    fun familyHeader_bashkir_isDisplayed() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                FamilyOrderFormContent(
                    passenger = "Әсәй", phone = "", from = "Өфө", to = "Сибай",
                    notifyContact = true, trustedName = null,
                    loading = false, error = null,
                    onPassengerChange = {}, onPhoneChange = {}, onNotifyContactChange = {},
                    onSubmit = {},
                )
            }
        }
        composeRule.onNodeWithText("Кем бара?").assertIsDisplayed()
    }

    // ── CallbackHelpContent — «помощь звонком» (без LazyColumn-скролла) ──

    @Test
    fun callback_render_showsInfoAndReasonField() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                CallbackHelpContent(reason = "Помогите", requested = false, hasSupportPhone = false, onReasonChange = {}, onPrimaryAction = {})
            }
        }
        composeRule.onNodeWithText("Мы перезвоним").assertIsDisplayed()
        composeRule.onNodeWithText("Что нужно?").assertIsDisplayed()
    }

    @Test
    fun callback_noPhone_showsRequestLabel() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                CallbackHelpContent(reason = "Помогите", requested = false, hasSupportPhone = false, onReasonChange = {}, onPrimaryAction = {})
            }
        }
        composeRule.onNodeWithText("Попросить звонок").assertIsDisplayed()
    }

    @Test
    fun callback_withPhone_showsCallSupportLabel() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                CallbackHelpContent(reason = "Помогите", requested = false, hasSupportPhone = true, onReasonChange = {}, onPrimaryAction = {})
            }
        }
        composeRule.onNodeWithText("Позвонить в поддержку").assertIsDisplayed()
    }

    @Test
    fun callback_requested_showsConfirmationCard() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                CallbackHelpContent(reason = "Помогите", requested = true, hasSupportPhone = false, onReasonChange = {}, onPrimaryAction = {})
            }
        }
        composeRule.onNodeWithText("Звонок запрошен").assertIsDisplayed()
    }

    @Test
    fun callback_buttonClick_firesPrimaryAction() {
        var fired = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                CallbackHelpContent(reason = "Помогите", requested = false, hasSupportPhone = false, onReasonChange = {}, onPrimaryAction = { fired = true })
            }
        }
        composeRule.onNodeWithTag("callback_btn").performClick()
        assertTrue(fired)
    }

    @Test
    fun callback_bashkir_showsRequestLabel() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                CallbackHelpContent(reason = "Ярҙам", requested = false, hasSupportPhone = false, onReasonChange = {}, onPrimaryAction = {})
            }
        }
        composeRule.onNodeWithText("Шылтыратыу һорау").assertIsDisplayed()
    }
}
