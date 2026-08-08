package com.yuldash.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Критический путь — вход/регистрация. Добираем ЗАМОРОЖЕННУЮ SMS-форму входа [LoginSmsSection], которая
 * в проде видна только при `BuildConfig.SMS_LOGIN_ENABLED`, а в unit-сборке флаг всегда false → через
 * `LoginFormContent` этот блок недостижим (три прежних теста его специально обходили через Telegram-поток).
 * SMS-блок вынесен из `LoginFormContent` в `internal ColumnScope.LoginSmsSection` (вёрстка 1:1) → тут он
 * рендерится НАПРЯМУЮ, минуя флаг. Покрываем: тумблер формы, шаг телефона (step=0) и шаг кода (step=1),
 * двуязычие RU/BA, «плохие» сценарии (loading → primary-кнопка disabled, ошибка входа видна) и колбэки.
 *
 * Не дублирует LoginFormContentTest / LoginScreenContentTest / LoginDeepContentTest (там — Telegram-поток,
 * hero/divider/langToggle/footer, LoginConsent, ошибки tgVerify). Раннер и заголовок — как в LoginDeepContentTest.
 * autoAdvance трогаем только там, где крутится спиннер (loading в primary-кнопке).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2600dp")   // высокое окно: вся SMS-форма влезает, скролл не нужен
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LoginDeep2ContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    // LoginSmsSection — ColumnScope-extension (в проде живёт в Column формы с spacedBy(16.dp)).
    // В тесте повторяем тот же контейнер, оборачивая в скролл-Column, и рендерим секцию напрямую (минуя build-флаг).
    // Все параметры с дефолтами → тест меняет только нужное. Колбэки по умолчанию — no-op.
    private fun smsSection(
        step: Int = 0,
        phone: String = "",
        code: String = "",
        name: String = "",
        loading: Boolean = false,
        error: String? = null,
        showPhone: Boolean = true,   // по умолчанию форма раскрыта — так виден весь UI полей/кнопок
        language: AppLanguage = AppLanguage.Ru,
        onToggleSmsForm: () -> Unit = {},
        onChangePhone: () -> Unit = {},
        onSmsPrimary: () -> Unit = {},
    ) {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides language) {
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)   // как в родительском Column формы
                ) {
                    LoginSmsSection(
                        currentLanguage = language,
                        step = step,
                        phone = phone,
                        code = code,
                        name = name,
                        loading = loading,
                        error = error,
                        showPhone = showPhone,
                        onPhoneChange = {},
                        onCodeChange = {},
                        onNameChange = {},
                        onToggleSmsForm = onToggleSmsForm,
                        onChangePhone = onChangePhone,
                        onSmsPrimary = onSmsPrimary,
                    )
                }
            }
        }
    }

    // ─────────────────── Тумблер SMS-формы + разделитель (шапка секции, всегда видна) ───────────────────

    @Test
    fun toggle_russian_showsPhoneLoginButtonAndDivider() {
        // Свёрнутая форма (showPhone=false): виден разделитель «или» и кнопка-тумблер, полей ещё нет.
        smsSection(showPhone = false, language = AppLanguage.Ru)
        composeRule.onNodeWithText("или").assertIsDisplayed()
        composeRule.onNodeWithText("Войти по номеру телефона").assertIsDisplayed()
        // поле телефона скрыто, пока не тапнули тумблер
        composeRule.onNodeWithText("Номер телефона").assertDoesNotExist()
    }

    @Test
    fun toggle_bashkir_showsPhoneLoginButton() {
        smsSection(showPhone = false, language = AppLanguage.Ba)
        composeRule.onNodeWithText("йәки").assertIsDisplayed()
        composeRule.onNodeWithText("Телефон номеры аша инеү").assertIsDisplayed()
    }

    @Test
    fun toggle_click_firesCallback() {
        // тап по «Войти по номеру телефона» раскрывает/сворачивает SMS-форму (колбэк onToggleSmsForm)
        var toggled = false
        smsSection(showPhone = false, onToggleSmsForm = { toggled = true })
        composeRule.onNodeWithText("Войти по номеру телефона").performClick()
        assertTrue(toggled)
    }

    // ─────────────────── Шаг телефона (step=0): подпись, поле, primary-кнопка «Получить код» ───────────────────

    @Test
    fun phoneStep_russian_showsHintPlaceholderAndPrimaryLabel() {
        // step=0 + showPhone=true: подпись про скрытие номера, плейсхолдер поля и текст кнопки «Получить код».
        smsSection(step = 0, showPhone = true, language = AppLanguage.Ru)
        composeRule.onNodeWithText("Номер будет скрыт до подтверждения брони.").assertIsDisplayed()
        composeRule.onNodeWithText("Номер телефона").assertIsDisplayed()
        composeRule.onNodeWithText("Получить код").assertIsDisplayed()
    }

    @Test
    fun phoneStep_bashkir_showsHintPlaceholderAndPrimaryLabel() {
        smsSection(step = 0, showPhone = true, language = AppLanguage.Ba)
        composeRule.onNodeWithText("Телефон номеры бронь раҫланғанға тиклем йәшерелә.").assertIsDisplayed()
        composeRule.onNodeWithText("Телефон номеры").assertIsDisplayed()
        composeRule.onNodeWithText("Код алыу").assertIsDisplayed()
    }

    // ─────────────────── Шаг кода (step=1): подпись с номером, поля имени/кода, «Изменить номер», «Войти» ───────────────────

    @Test
    fun codeStep_russian_showsSentHintNamePlaceholderCodePlaceholderAndPrimary() {
        // step=1 + showPhone=true: «Код отправлен на <номер>» (интерполяция $phone), поля имени и кода,
        // кнопка «Изменить номер» и primary «Войти».
        smsSection(step = 1, phone = "+79991234567", showPhone = true, language = AppLanguage.Ru)
        composeRule.onNodeWithText("Код отправлен на +79991234567").assertIsDisplayed()
        composeRule.onNodeWithText("Твоё имя (необязательно)").assertIsDisplayed()
        composeRule.onNodeWithText("Код из SMS").assertIsDisplayed()
        composeRule.onNodeWithText("Изменить номер").assertIsDisplayed()
        composeRule.onNodeWithText("Войти").assertIsDisplayed()
    }

    @Test
    fun codeStep_bashkir_showsPlaceholdersChangeNumberAndPrimary() {
        smsSection(step = 1, phone = "+79991234567", showPhone = true, language = AppLanguage.Ba)
        composeRule.onNodeWithText("Исемең (мотлаҡ түгел)").assertIsDisplayed()
        composeRule.onNodeWithText("SMS коды").assertIsDisplayed()
        composeRule.onNodeWithText("Номерҙы үҙгәртеү").assertIsDisplayed()
        composeRule.onNodeWithText("Инеү").assertIsDisplayed()
    }

    @Test
    fun codeStep_changeNumber_click_firesCallback() {
        // тап по «Изменить номер» возвращает на шаг телефона (колбэк onChangePhone)
        var changed = false
        smsSection(step = 1, phone = "+79991234567", showPhone = true, onChangePhone = { changed = true })
        composeRule.onNodeWithText("Изменить номер").performClick()
        assertTrue(changed)
    }

    // ─────────────────── ПЛОХОЙ СЦЕНАРИЙ: ошибка входа видна пользователю (SMS-поток) ───────────────────

    @Test
    fun serverError_isShownToUser_inSmsForm() {
        // «Неверный код» (ветка verifyCode.onFailure) отрисован под полями SMS-формы → присутствие в дереве.
        smsSection(step = 1, phone = "+79991234567", code = "123456", showPhone = true, error = "Неверный код")
        composeRule.onNodeWithText("Неверный код").assertExists()
    }

    // ─────────────────── ПЛОХОЙ СЦЕНАРИЙ: двойное нажатие (гард loading → primary disabled) ───────────────────

    @Test
    fun smsPrimary_whenLoading_isDisabled_guardsDoubleTap() {
        composeRule.mainClock.autoAdvance = false   // в primary-кнопке крутится спиннер (иначе тест зависнет)
        smsSection(step = 0, phone = "+79991234567", showPhone = true, loading = true)
        // при loading текст скрыт спиннером → целимся по testTag, кнопка заблокирована (гард двойного тапа)
        composeRule.onNodeWithTag(TAG_LOGIN_SMS_PRIMARY_BTN).assertIsNotEnabled()
    }

    @Test
    fun smsPrimary_whenIdle_isEnabled_andClickFiresCallback() {
        // idle: primary enabled, одиночный тап зовёт колбэк onSmsPrimary (гард только по loading — поведение 1:1)
        var fired = false
        smsSection(step = 0, phone = "+79991234567", showPhone = true, loading = false, onSmsPrimary = { fired = true })
        composeRule.onNodeWithTag(TAG_LOGIN_SMS_PRIMARY_BTN).assertIsEnabled()
        composeRule.onNodeWithTag(TAG_LOGIN_SMS_PRIMARY_BTN).performClick()
        assertTrue(fired)
    }
}
