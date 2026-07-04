package com.yuldash.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
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
 * Чистая форма экрана отзыва — [AppReviewFormContent], вынесенная из умного `AppReviewScreen`
 * (state/сеть/корутина остались в обёртке). Здесь покрываем всю форму: подсказка, звёзды, поле
 * отзыва со счётчиком, город, ошибка, кнопка отправки (спиннер/disabled) и сноска — на двух языках.
 * Ввод/выбор/отправка идут через колбэки-параметры (state hoisting), поэтому проверяем и то, что
 * колбэки зовутся с правильными значениями.
 *
 * Под-компоненты `ReviewStarsRow` / `ReviewThanksCard` уже покрыты в AppReviewContentTest — тут их
 * не дублируем, только их участие в форме (звёзды).
 *
 * Форма скроллится (`verticalScroll`), поэтому к элементам ниже сгиба (город, кнопка, сноска)
 * идём через `onNode(hasScrollToNodeAction()).performScrollToNode(hasText(...))`. Спиннера в
 * кнопке при `sending=true` → бесконечная анимация, поэтому в том тесте гасим авто-часы
 * (`autoAdvance = false`) и НЕ комбинируем со скроллом (иначе дедлок).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2600dp")   // высокое окно: вся форма влезает без скролла
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppReviewFormContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    // --- Русская форма: подсказка сверху, поле отзыва, счётчик 0/600 ---

    @Test
    fun russian_showsPromptFieldAndCounter() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                AppReviewFormContent(
                    stars = 5, text = "", city = "", sending = false, error = null, canSubmit = false,
                    onSelectStars = {}, onTextChange = {}, onCityChange = {}, onSubmit = {},
                )
            }
        }
        composeRule.onNodeWithText(
            "Как тебе Юлдаш? Оцени и напиши пару слов — это поможет другим решиться."
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Твой отзыв").assertIsDisplayed()
        composeRule.onNodeWithText("0/600").assertIsDisplayed()
    }

    // --- Башкирская форма: подсказка + метка поля отзыва на башкирском ---

    @Test
    fun bashkir_showsPromptAndFieldLabel() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                AppReviewFormContent(
                    stars = 5, text = "", city = "", sending = false, error = null, canSubmit = false,
                    onSelectStars = {}, onTextChange = {}, onCityChange = {}, onSubmit = {},
                )
            }
        }
        composeRule.onNodeWithText(
            "Юлдаш нисек? Баһала һәм бер-ике һүҙ яҙ — был башҡаларға ҡарар итергә ярҙам итер."
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Һинең фекерең").assertIsDisplayed()
    }

    // --- Счётчик считает по trimmed-длине (пробелы по краям не в счёт) ---

    @Test
    fun counter_usesTrimmedLength() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                AppReviewFormContent(
                    stars = 4, text = "  Отличная поездка!  ", city = "", sending = false, error = null, canSubmit = true,
                    onSelectStars = {}, onTextChange = {}, onCityChange = {}, onSubmit = {},
                )
            }
        }
        // "Отличная поездка!" = 17 символов после trim
        composeRule.onNodeWithText("17/600").assertIsDisplayed()
    }

    // --- Звёзды в форме: тап по звезде → onSelectStars(index) ---

    @Test
    fun tappingStar_firesOnSelectStarsWithIndex() {
        var picked = -1
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                AppReviewFormContent(
                    stars = 5, text = "", city = "", sending = false, error = null, canSubmit = false,
                    onSelectStars = { picked = it }, onTextChange = {}, onCityChange = {}, onSubmit = {},
                )
            }
        }
        assertEquals(-1, picked)
        composeRule.onNodeWithContentDescription("3 звёзд").performClick()
        assertEquals(3, picked)
    }

    // --- Ввод в поле отзыва → onTextChange с введённым текстом ---

    @Test
    fun typingReview_firesOnTextChange() {
        var typed = ""
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                AppReviewFormContent(
                    stars = 5, text = "", city = "", sending = false, error = null, canSubmit = false,
                    onSelectStars = {}, onTextChange = { typed = it }, onCityChange = {}, onSubmit = {},
                )
            }
        }
        // Поле отзыва матчим по метке (label семантически доступен, placeholder — нет).
        composeRule.onNodeWithText("Твой отзыв").performTextInput("Супер")
        assertEquals("Супер", typed)
    }

    // --- Ввод в поле города (ниже сгиба) → onCityChange ---

    @Test
    fun typingCity_firesOnCityChange() {
        var typedCity = ""
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                AppReviewFormContent(
                    stars = 5, text = "", city = "", sending = false, error = null, canSubmit = false,
                    onSelectStars = {}, onTextChange = {}, onCityChange = { typedCity = it }, onSubmit = {},
                )
            }
        }
        composeRule.onNodeWithText("Город (необязательно)").performTextInput("Уфа")
        assertEquals("Уфа", typedCity)
    }

    // --- Ошибка: показываем переданный текст ошибки ---

    @Test
    fun error_showsErrorText() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                AppReviewFormContent(
                    stars = 5, text = "Хорошо всё", city = "", sending = false,
                    error = "Не получилось отправить. Проверь интернет и попробуй ещё раз.",
                    canSubmit = true,
                    onSelectStars = {}, onTextChange = {}, onCityChange = {}, onSubmit = {},
                )
            }
        }
        composeRule.onNodeWithText("Не получилось отправить. Проверь интернет и попробуй ещё раз.")
            .assertIsDisplayed()
    }

    // --- Кнопка отправки: текст RU/BA, активна при canSubmit, тап → onSubmit ---

    @Test
    fun submitButton_russian_enabledAndClickable() {
        var submitted = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                AppReviewFormContent(
                    stars = 5, text = "Отличная поездка", city = "", sending = false, error = null, canSubmit = true,
                    onSelectStars = {}, onTextChange = {}, onCityChange = {}, onSubmit = { submitted = true },
                )
            }
        }
        composeRule.onNodeWithText("Отправить отзыв").assertIsEnabled()
        assertFalse(submitted)
        composeRule.onNodeWithText("Отправить отзыв").performClick()
        assertTrue(submitted)
    }

    @Test
    fun submitButton_bashkir_showsLabel() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                AppReviewFormContent(
                    stars = 5, text = "Яҡшы сәфәр ине", city = "", sending = false, error = null, canSubmit = true,
                    onSelectStars = {}, onTextChange = {}, onCityChange = {}, onSubmit = {},
                )
            }
        }
        composeRule.onNodeWithText("Фекерҙе ебәрергә").assertIsDisplayed()
    }

    // --- Disabled: canSubmit=false → кнопка не активна (защита от отправки «пустого» отзыва) ---

    @Test
    fun submitButton_disabled_whenCannotSubmit() {
        // canSubmit=false (текст короче 10) → кнопка заблокирована, отправить нельзя.
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                AppReviewFormContent(
                    stars = 5, text = "коротко", city = "", sending = false, error = null, canSubmit = false,
                    onSelectStars = {}, onTextChange = {}, onCityChange = {}, onSubmit = {},
                )
            }
        }
        composeRule.onNodeWithText("Отправить отзыв").assertIsNotEnabled()
    }

    // --- Отправка: sending=true → спиннер вместо надписи, надпись скрыта ---

    @Test
    fun sending_showsSpinnerNotSubmitLabel() {
        composeRule.mainClock.autoAdvance = false   // бесконечный спиннер в кнопке
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                AppReviewFormContent(
                    stars = 5, text = "Отличная поездка", city = "", sending = true, error = null, canSubmit = false,
                    onSelectStars = {}, onTextChange = {}, onCityChange = {}, onSubmit = {},
                )
            }
        }
        // При sending надпись кнопки заменяется спиннером (autoAdvance=false, без скролла — иначе дедлок).
        composeRule.onNodeWithText("Отправить отзыв").assertDoesNotExist()
    }

    // --- Сноска внизу формы (двуязычная), ниже сгиба ---

    @Test
    fun footer_russian_showsModerationNote() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                AppReviewFormContent(
                    stars = 5, text = "", city = "", sending = false, error = null, canSubmit = false,
                    onSelectStars = {}, onTextChange = {}, onCityChange = {}, onSubmit = {},
                )
            }
        }
        composeRule.onNodeWithText("Отзыв появится на сайте после короткой проверки — чтобы не было спама.")
            .assertIsDisplayed()
    }
}
