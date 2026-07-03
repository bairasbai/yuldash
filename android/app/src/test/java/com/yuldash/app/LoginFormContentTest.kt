package com.yuldash.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
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
 * Критический путь — форма входа (регистрация/вход). Экран `LoginScreen` разрезан на умную обёртку
 * (стейт/сеть/Telegram) и чистый `LoginFormContent(state, callbacks)`. Здесь Content покрыт в «плохих»
 * сценариях: невалидный телефон/код (хелперы), ошибка сервера, двойное нажатие (кнопка disabled при
 * loading), переключение шага телефон↔код. Это то, что раньше жило внутри монолита и не тестировалось.
 *
 * Заголовок класса — как в AdminReviewsContentTest. autoAdvance=false только там, где крутится спиннер.
 * В тестовом BuildConfig SMS_LOGIN_ENABLED=false и TELEGRAM_BOT="" (нет local.properties), поэтому
 * поле кода в UI берём из Telegram-потока (tgMode=true) — SMS-блок в этой сборке скрыт флагом.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LoginFormContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    // Все параметры имеют дефолты → каждый тест меняет только нужное. Колбэки по умолчанию — no-op.
    private fun form(
        step: Int = 0,
        phone: String = "",
        code: String = "",
        name: String = "",
        loading: Boolean = false,
        error: String? = null,
        needPhone: Boolean = false,
        tgMode: Boolean = false,
        showPhone: Boolean = false,
        language: AppLanguage = AppLanguage.Ru,
        onTelegramStart: () -> Unit = {},
        onTgVerify: () -> Unit = {},
        onSmsPrimary: () -> Unit = {},
    ) {
        composeRule.setContent {
            LoginFormContent(
                currentLanguage = language,
                step = step,
                phone = phone,
                code = code,
                name = name,
                loading = loading,
                error = error,
                needPhone = needPhone,
                tgMode = tgMode,
                showPhone = showPhone,
                onPhoneChange = {},
                onCodeChange = {},
                onNameChange = {},
                onTelegramStart = onTelegramStart,
                onTgVerify = onTgVerify,
                onTelegramOpen = {},
                onBackFromTg = {},
                onToggleSmsForm = {},
                onChangePhone = {},
                onSmsPrimary = onSmsPrimary,
            )
        }
    }

    // ─────────────────── ПЛОХОЙ СЦЕНАРИЙ: невалидный телефон/код (чистые хелперы валидации) ───────────────────

    @Test
    fun phoneValidation_emptyShortAndBlank_areInvalid_andRealPhoneValid() {
        // «плохой» ввод — не проходит валидацию (правило 1:1 со старым onClick: phone.trim().length < 5)
        assertFalse(isLoginPhoneValid(""))
        assertFalse(isLoginPhoneValid("   "))     // только пробелы
        assertFalse(isLoginPhoneValid("123"))     // короткий
        assertFalse(isLoginPhoneValid(" 12 "))    // короткий после trim
        // валидный — проходит
        assertTrue(isLoginPhoneValid("+79991234567"))
        assertTrue(isLoginPhoneValid("12345"))    // ровно граница (≥ 5)
    }

    @Test
    fun codeValidation_emptyShortAndFiveDigits_areInvalid_andSixDigitsValid() {
        // «плохой» код — не проходит (правило 1:1: code.length < 6)
        assertFalse(isLoginCodeValid(""))
        assertFalse(isLoginCodeValid("123"))      // короткий
        assertFalse(isLoginCodeValid("12345"))    // 5 цифр — на одну меньше
        // валидный — ровно 6 цифр
        assertTrue(isLoginCodeValid("123456"))
        assertTrue(isLoginCodeValid(" 123456 "))  // trim не мешает
    }

    // ─────────────────── ПЛОХОЙ СЦЕНАРИЙ: ошибка сервера видна пользователю ───────────────────

    @Test
    fun serverError_isShownToUser_inTelegramCodeStep() {
        // текст ровно из кода (ветка else в tgVerify.onFailure): «Неверный код».
        // Текст ошибки идёт под полем кода → проверяем присутствие в дереве (assertExists), позиция от вьюпорта не зависит.
        form(tgMode = true, code = "123456", error = "Неверный код")
        composeRule.onNodeWithText("Неверный код").assertExists()
    }

    @Test
    fun phoneRequiredBanner_isShown_whenNeedPhone() {
        // 403 phone_required → баннер с инструкцией поделиться номером (RU). Баннер вверху tgMode → виден.
        form(tgMode = true, needPhone = true)
        composeRule.onNodeWithText(
            "Для безопасности нужен номер. В Telegram нажми «📱 Поделиться номером», потом вернись и нажми «Войти».",
        ).assertIsDisplayed()
    }

    // ─────────────────── ПЛОХОЙ СЦЕНАРИЙ: двойное нажатие (гард loading → кнопка disabled) ───────────────────

    @Test
    fun telegramButton_whenLoading_isDisabled_guardsDoubleTap() {
        composeRule.mainClock.autoAdvance = false   // в кнопке крутится спиннер (без него тест зависнет)
        form(tgMode = false, loading = true)
        // экран выбора: кнопка «Войти через Telegram» заблокирована на время запроса.
        // При loading текст скрыт спиннером → целимся по testTag, не по тексту (как в UiKitButtonTest).
        composeRule.onNodeWithTag(TAG_LOGIN_TELEGRAM_BTN).assertIsNotEnabled()
    }

    @Test
    fun telegramButton_whenIdle_isEnabled_andClickFiresCallback() {
        var started = false
        form(tgMode = false, loading = false, onTelegramStart = { started = true })
        composeRule.onNodeWithTag(TAG_LOGIN_TELEGRAM_BTN).assertIsEnabled()
        composeRule.onNodeWithTag(TAG_LOGIN_TELEGRAM_BTN).performClick()
        assertTrue(started)   // одиночный тап зовёт колбэк
    }

    @Test
    fun tgVerifyButton_whenLoading_isDisabled_guardsDoubleTap() {
        composeRule.mainClock.autoAdvance = false   // спиннер в кнопке «Войти»
        form(tgMode = true, code = "123456", loading = true)
        // шаг ввода Telegram-кода: кнопка «Войти» заблокирована при loading (гард двойного тапа)
        composeRule.onNodeWithTag(TAG_LOGIN_TG_VERIFY_BTN).assertIsNotEnabled()
    }

    @Test
    fun tgVerifyButton_whenIdle_isEnabled_andClickFiresCallback() {
        var verified = false
        form(tgMode = true, code = "123456", loading = false, onTgVerify = { verified = true })
        composeRule.onNodeWithTag(TAG_LOGIN_TG_VERIFY_BTN).assertIsEnabled()
        composeRule.onNodeWithTag(TAG_LOGIN_TG_VERIFY_BTN).performClick()
        assertTrue(verified)
    }

    // ─────────────────── ПЛОХОЙ СЦЕНАРИЙ: пустой код — кнопка не залочена, гард держит колбэк ───────────────────

    @Test
    fun tgVerify_withEmptyCode_buttonStaysEnabled_andHelperTreatsCodeInvalid() {
        // Поведение 1:1: кнопка «Войти» enabled (гард только по loading), пустой код ловит колбэк-гард.
        var fired = false
        form(tgMode = true, code = "", loading = false, onTgVerify = { fired = true })
        assertFalse(isLoginCodeValid(""))                            // внутри колбэка это → показ ошибки «Введите код…»
        composeRule.onNodeWithTag(TAG_LOGIN_TG_VERIFY_BTN).assertIsEnabled() // не залочена при loading=false (как старый onClick)
        composeRule.onNodeWithTag(TAG_LOGIN_TG_VERIFY_BTN).performClick()
        assertTrue(fired)
    }

    // ─────────────────── Шаг телефон ↔ код (доступный UI: экран выбора vs Telegram-код) ───────────────────

    @Test
    fun selectionStep_showsTelegramEntry_notCodeInstruction() {
        // step=0, tgMode=false — экран выбора входа: видна Telegram-кнопка, инструкции про код ещё нет
        form(tgMode = false)
        composeRule.onNodeWithText("Войти в Юлдаш").assertIsDisplayed()
        composeRule.onNodeWithText("Войти через Telegram").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Открой Telegram, нажми «Старт» — бот пришлёт 6-значный код. Введи его сюда.",
        ).assertDoesNotExist()
    }

    @Test
    fun codeStep_showsCodeInstructionAndBackButton() {
        // tgMode=true — «шаг кода»: инструкция про 6-значный код (вверху, видна) и кнопка «Назад»
        // (внизу формы → проверяем присутствие в дереве, чтобы тест не зависел от высоты вьюпорта).
        form(tgMode = true, code = "12")
        composeRule.onNodeWithText(
            "Открой Telegram, нажми «Старт» — бот пришлёт 6-значный код. Введи его сюда.",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Назад").assertExists()
    }

    @Test
    fun codeStep_bashkir_showsBashkirInstruction() {
        // двуязычие на «шаге кода»: башкирская инструкция вверху формы (черновой BA → на проверку носителю)
        form(tgMode = true, language = AppLanguage.Ba)
        composeRule.onNodeWithText(
            "Telegram'ды ас, «Старт» баҫ — бот 6 һанлы код ебәрер. Шуны индер.",
        ).assertIsDisplayed()
    }
}
