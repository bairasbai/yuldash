package com.yuldash.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Критический путь — вход/регистрация. Добираем НЕпокрытые ветки `LoginFormContent` (шаг ввода кода,
 * тексты ошибок входа, переключение ссылки Telegram по needPhone) и вынесенный `LoginConsent`
 * (согласие + ссылки на Условия/Политику). Двуязычие RU/BA — как на первом экране (двуязычие критично).
 *
 * Не дублирует LoginFormContentTest (хелперы, disabled-кнопки, needPhone-баннер, шаг телефон↔код)
 * и LoginScreenContentTest (hero/divider/langToggle/footer). Заголовок и раннер — как в LoginFormContentTest.
 * В тестовом BuildConfig SMS_LOGIN_ENABLED=false, TELEGRAM_BOT="" → доступный UI ввода кода = Telegram-поток
 * (tgMode=true), SMS-блок скрыт флагом. autoAdvance трогаем только там, где крутится спиннер (loading).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2600dp")   // высокое окно: вся форма влезает, скролл-вызовы = no-op
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LoginDeepContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    // Все параметры с дефолтами → тест меняет только нужное. Колбэки по умолчанию — no-op.
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
        onTelegramOpen: () -> Unit = {},
        onBackFromTg: () -> Unit = {},
    ) {
        // LoginFormContent сам по себе — обычный Column без скролла (в проде скролл даёт обёртка LoginScreen).
        // В тесте оборачиваем в verticalScroll, чтобы контент ниже сгиба был доступен через performScrollToNode.
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides language) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
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
                    onTelegramStart = {},
                    onTgVerify = {},
                    onTelegramOpen = onTelegramOpen,
                    onBackFromTg = onBackFromTg,
                    onToggleSmsForm = {},
                    onChangePhone = {},
                    onSmsPrimary = {},
                )
                }
            }
        }
    }

    // ─────────────────── Заголовок формы входа — двуязычие (шапка карточки, всегда видна) ───────────────────

    @Test
    fun header_russian_showsTitleAndSubtitle() {
        form(tgMode = false, language = AppLanguage.Ru)
        composeRule.onNodeWithText("Войти в Юлдаш").assertIsDisplayed()
        // Было «Используйте Telegram…» — единственное «вы» на первом экране приложения, при том
        // что двумя строками ниже уже «Входя, ты подтверждаешь», а башкирский рядом с самого
        // начала на «ты» («ҡуллан»). Сторож тона это пропускал: он знал список глаголов, а не
        // правило (исправлено 2026-08-13, ToneSourceGuardTest).
        composeRule.onNodeWithText("Заходи через Telegram — быстро и безопасно").assertIsDisplayed()
    }

    @Test
    fun header_bashkir_showsTitleAndSubtitle() {
        form(tgMode = false, language = AppLanguage.Ba)
        composeRule.onNodeWithText("Юлдашҡа инеү").assertIsDisplayed()
        composeRule.onNodeWithText("Тиҙ һәм хәүефһеҙ инеү өсөн Telegram ҡуллан").assertIsDisplayed()
    }

    // ─────────────────── Шаг ввода кода: поля имени и кода (плейсхолдеры, оба языка) ───────────────────

    @Test
    fun codeStep_russian_showsNameAndCodePlaceholders() {
        // tgMode=true — видны поля «Твоё имя (необязательно)» и «Код из Telegram» (вверху формы → видны).
        form(tgMode = true, language = AppLanguage.Ru)
        composeRule.onNodeWithText("Твоё имя (необязательно)").assertIsDisplayed()
        composeRule.onNodeWithText("Код из Telegram").assertIsDisplayed()
    }

    @Test
    fun codeStep_bashkir_showsCodePlaceholder() {
        form(tgMode = true, language = AppLanguage.Ba)
        composeRule.onNodeWithText("Исемең (мотлаҡ түгел)").assertIsDisplayed()
        composeRule.onNodeWithText("Telegram коды").assertIsDisplayed()
    }

    // ─────────────────── Ссылка «Открыть Telegram» переключается по needPhone (RU+BA) ───────────────────

    @Test
    fun codeStep_openLink_default_isRepeatOpen_ru() {
        // needPhone=false → «Открыть Telegram ещё раз» (не про номер). Ссылка ниже кнопки → скроллим.
        form(tgMode = true, needPhone = false, language = AppLanguage.Ru)
        composeRule.onNodeWithText("Открыть Telegram ещё раз").assertIsDisplayed()
    }

    @Test
    fun codeStep_openLink_whenNeedPhone_isShareNumber_ru() {
        // needPhone=true → «Открыть Telegram и поделиться номером». Другой текст ссылки (ветка if(needPhone)).
        form(tgMode = true, needPhone = true, language = AppLanguage.Ru)
        composeRule.onNodeWithText("Открыть Telegram и поделиться номером").assertIsDisplayed()
    }

    @Test
    fun codeStep_openLink_whenNeedPhone_isShareNumber_bashkir() {
        form(tgMode = true, needPhone = true, language = AppLanguage.Ba)
        composeRule.onNodeWithText("Telegram'ды асып, номер менән бүлешергә").assertIsDisplayed()
    }

    @Test
    fun codeStep_openTelegramLink_click_firesCallback() {
        var opened = false
        form(tgMode = true, needPhone = false, language = AppLanguage.Ru, onTelegramOpen = { opened = true })
        composeRule.onNodeWithText("Открыть Telegram ещё раз").performClick()
        assertTrue(opened)   // тап по ссылке открывает Telegram (колбэк отработал)
    }

    @Test
    fun codeStep_backButton_click_firesCallback() {
        // «Назад» внизу формы (ниже сгиба) → скроллим к нему, затем тап → колбъэк onBackFromTg.
        var back = false
        form(tgMode = true, language = AppLanguage.Ru, onBackFromTg = { back = true })
        composeRule.onNodeWithText("Назад").performClick()
        assertTrue(back)
    }

    // ─────────────────── ПЛОХОЙ СЦЕНАРИЙ: тексты ошибок входа видны пользователю (маппинг статусов) ───────────────────

    @Test
    fun error_expiredCode_isShownToUser_ru() {
        // 410 → «Код истёк…». Текст под полем кода → присутствие в дереве (assertExists), позиция от вьюпорта не зависит.
        form(tgMode = true, code = "123456", error = "Код истёк. Получи новый — открой Telegram ещё раз.")
        composeRule.onNodeWithText("Код истёк. Получи новый — открой Telegram ещё раз.").assertExists()
    }

    @Test
    fun error_tooManyAttempts_isShownToUser_ru() {
        // 429 → «Слишком много попыток…»
        form(tgMode = true, code = "123456", error = "Слишком много попыток. Получи новый код.")
        composeRule.onNodeWithText("Слишком много попыток. Получи новый код.").assertExists()
    }

    @Test
    fun error_codeNotYet_isShownToUser_ru() {
        // 409 → «Код ещё идёт от Telegram…» (честное сообщение под нагрузкой)
        form(tgMode = true, code = "123456", error = "Код ещё идёт от Telegram — подожди пару секунд и нажми «Войти» снова.")
        composeRule.onNodeWithText("Код ещё идёт от Telegram — подожди пару секунд и нажми «Войти» снова.").assertExists()
    }

    @Test
    fun error_badCode_bashkir_isShownToUser() {
        // двуязычие ошибок: неверный код по-башкирски (ветка else в tgVerify.onFailure)
        form(tgMode = true, code = "123456", error = "Код дөрөҫ түгел. Тикшереп, ҡабат индер.", language = AppLanguage.Ba)
        composeRule.onNodeWithText("Код дөрөҫ түгел. Тикшереп, ҡабат индер.").assertExists()
    }

    // ─────────────────── Баннер needPhone по-башкирски (403 phone_required, вверху tgMode → виден) ───────────────────

    @Test
    fun phoneRequiredBanner_bashkir_isShown_whenNeedPhone() {
        form(tgMode = true, needPhone = true, language = AppLanguage.Ba)
        composeRule.onNodeWithText(
            "Хәүефһеҙлек өсөн номер кәрәк. Telegram'да «📱 Номер менән бүлешергә» баҫ, аҙаҡ кире ҡайтып «Инеү» баҫ.",
        ).assertIsDisplayed()
    }

    // ─────────────────── LoginConsent — согласие + ссылки на Условия/Политику (вынос private→internal) ───────────────────

    @Test
    fun consent_russian_showsTextAndBothLinks() {
        composeRule.setContent { LoginConsent(AppLanguage.Ru) }
        composeRule.onNodeWithText("Входя, ты подтверждаешь, что тебе есть 18 лет, и принимаешь").assertIsDisplayed()
        composeRule.onNodeWithText("Условия").assertIsDisplayed()
        composeRule.onNodeWithText("Политику конфиденциальности").assertIsDisplayed()
    }

    @Test
    fun consent_bashkir_showsTextAndBothLinks() {
        composeRule.setContent { LoginConsent(AppLanguage.Ba) }
        composeRule.onNodeWithText("Инеп, һин 18 йәшең тулғанын раҫлайһың һәм ҡабул итәһең:").assertIsDisplayed()
        composeRule.onNodeWithText("Шарттарҙы").assertIsDisplayed()
        composeRule.onNodeWithText("Конфиденциаллек сәйәсәтен").assertIsDisplayed()
    }

    @Test
    fun consent_termsLink_isClickable_doesNotCrash() {
        // Ссылка «Условия» кликабельна (открывает yulbash.ru/terms). В тесте Intent не летит — проверяем,
        // что клик не роняет композицию (runCatching в open() гасит отсутствие Activity). Enabled + performClick.
        composeRule.setContent { LoginConsent(AppLanguage.Ru) }
        composeRule.onNodeWithText("Условия").assertIsEnabled()
        composeRule.onNodeWithText("Условия").performClick()
        composeRule.onNodeWithText("Условия").assertIsDisplayed()   // жива после клика
    }

    @Test
    fun consent_privacyLink_isClickable_doesNotCrash() {
        composeRule.setContent { LoginConsent(AppLanguage.Ru) }
        composeRule.onNodeWithText("Политику конфиденциальности").performClick()
        composeRule.onNodeWithText("Политику конфиденциальности").assertIsDisplayed()   // жива после клика
    }

    // ─────────────────── Экран выбора: блок согласия отрисован внизу формы (интеграция в LoginFormContent) ───────────────────

    @Test
    fun selectionStep_rendersConsentBlock() {
        // tgMode=false → внизу формы есть согласие. Ниже сгиба → скроллим к тексту согласия.
        form(tgMode = false, language = AppLanguage.Ru)
        composeRule.onNodeWithText("Входя, ты подтверждаешь, что тебе есть 18 лет, и принимаешь").assertIsDisplayed()
    }
}
