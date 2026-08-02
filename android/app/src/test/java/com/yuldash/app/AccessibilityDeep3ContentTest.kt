package com.yuldash.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * «В глубину» (продолжение серии Deep/Deep2): критичный путь «Заявка пассажира».
 * Экран `CreatePassengerRequestScreen` разрезан на умную обёртку (сеть/стейт/context) и чистый
 * `CreatePassengerRequestContent(примитивы, колбэки, слоты полей адреса)` + хелпер валидации
 * `passengerRequestValid`. Раньше этот экран (≈213 строк) был непокрыт целиком.
 *
 * «Плохой» сценарий (пустые обязательные поля → кнопка «Создать заявку» disabled) покрыт прямыми
 * тестами чистого хелпера `passengerRequestValid` — это и есть логика, по которой блокируется кнопка
 * (enabled = !loading && passengerRequestValid(from, to, time, price)).
 *
 * Форма живёт глубоко в LazyColumn. Верхушку проверяем напрямую; к нижним блокам (условия/чеклист)
 * доезжаем через performScrollToNode при ЖИВОМ клоке (autoAdvance=true по умолчанию) — комбинация
 * autoAdvance=false + скролл на этом экране раньше давала ДЕДЛОК, поэтому клок НЕ морозим. Скроллер
 * один (одна LazyColumn) → hasScrollToNodeAction однозначен.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccessibilityDeep3ContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    // Категории — как в CreatePassengerRequestScreen (нужны Content'у для LazyRow-чипов).
    private val categories = listOf(
        "regular" to LocalizedText("Обычная", "Ғәҙәти"),
        "urgent" to LocalizedText("Срочно", "Ашығыс"),
        "parcel" to LocalizedText("Посылка", "Посылка"),
        "cargo" to LocalizedText("Груз", "Йөк"),
        "kids" to LocalizedText("С детьми", "Балалар менән")
    )

    @Composable
    private fun PassengerContent(
        language: AppLanguage = AppLanguage.Ru,
        from: String = "Баймаҡ",
        to: String = "Сибай",
        time: String = "05.07.2026, 09:00",
        seats: String = "1",
        category: String = "regular",
        price: String = "350",
        comment: String = "",
        loading: Boolean = false,
        selectedCategoryText: String = "Обычная",
        onSubmit: () -> Unit = {},
    ) {
        CompositionLocalProvider(LocalAppLanguage provides language) {
            CreatePassengerRequestContent(
                from = from, to = to, time = time, seats = seats, category = category, price = price,
                comment = comment, categories = categories, selectedCategoryText = selectedCategoryText,
                womenOnly = false, childSeat = false, pets = false, wheelchair = false,
                baggage = false, nonSmoking = false, airConditioner = false,
                loading = loading,
                onCategoryChange = {}, onSeatsChange = {}, onPriceChange = {},
                onCommentChange = {}, onTimeChange = {},
                onWomenOnlyChange = {}, onChildSeatChange = {}, onPetsChange = {},
                onWheelchairChange = {}, onBaggageChange = {}, onNonSmokingChange = {},
                onAirConditionerChange = {},
                // «Только для своих» — параметр появился в экране, тест про него не знал.
                onlyTrusted = false, onOnlyTrustedChange = {},
                onSubmit = onSubmit,
                // Слоты полей адреса не передаём → Content рисует простые OutlinedTextField (без гео-сети).
            )
        }
    }

    // ── Плохие сценарии валидации (прямой вызов чистого хелпера) ──

    @Test
    fun passengerValid_whenAllFieldsFilled() {
        assertTrue(passengerRequestValid("Баймаҡ", "Сибай", "05.07.2026, 09:00", "350"))
    }

    @Test
    fun passengerInvalid_whenFromBlank() {
        assertFalse(passengerRequestValid("", "Сибай", "05.07.2026, 09:00", "350"))
        assertFalse(passengerRequestValid("   ", "Сибай", "05.07.2026, 09:00", "350"))
    }

    @Test
    fun passengerInvalid_whenToBlank() {
        assertFalse(passengerRequestValid("Баймаҡ", "", "05.07.2026, 09:00", "350"))
    }

    @Test
    fun passengerInvalid_whenTimeBlank() {
        // Дата/время — обязательное поле: пусто → кнопка disabled.
        assertFalse(passengerRequestValid("Баймаҡ", "Сибай", "", "350"))
    }

    @Test
    fun passengerInvalid_whenPriceBlank() {
        assertFalse(passengerRequestValid("Баймаҡ", "Сибай", "05.07.2026, 09:00", ""))
    }

    // ── Happy-path рендер формы (верх виден, RU) ──

    @Test
    fun header_isDisplayed() {
        composeRule.setContent { PassengerContent() }
        composeRule.onNodeWithText("Заявка пассажира").assertIsDisplayed()
    }

    @Test
    fun privacyInfoCard_isDisplayed() {
        composeRule.setContent { PassengerContent() }
        // Ключевое обещание доверия: телефон/гео открываются только после подтверждения.
        composeRule.onNodeWithText("Водители увидят условия").assertIsDisplayed()
    }

    @Test
    fun addressFields_showLabels() {
        composeRule.setContent { PassengerContent() }
        composeRule.onNodeWithText("Откуда").assertIsDisplayed()
        composeRule.onNodeWithText("Куда").assertIsDisplayed()
    }

    @Test
    fun dateTimeField_showsLabel() {
        composeRule.setContent { PassengerContent() }
        composeRule.onNodeWithText("Дата и время").assertIsDisplayed()
    }

    // ── Двуязычие (BA): та же форма на башкирском ──

    @Test
    fun header_bashkir_isDisplayed() {
        composeRule.setContent {
            PassengerContent(language = AppLanguage.Ba, selectedCategoryText = "Ғәҙәти")
        }
        composeRule.onNodeWithText("Пассажир заявкаһы").assertIsDisplayed()
    }

    @Test
    fun addressFields_bashkir_showLabels() {
        composeRule.setContent {
            PassengerContent(language = AppLanguage.Ba, selectedCategoryText = "Ғәҙәти")
        }
        composeRule.onNodeWithText("Ҡайҙан").assertIsDisplayed()
        composeRule.onNodeWithText("Ҡайҙа").assertIsDisplayed()
    }

    // ── Категории: активный чип виден (LazyRow вверху формы) ──

    @Test
    fun categoryChips_showFirstRegularLabel() {
        composeRule.setContent { PassengerContent() }
        // Ряд категорий ниже сгиба + это вложенный LazyRow → hasScrollToNodeAction() матчит несколько
        // скроллеров; берём ПЕРВЫЙ (внешняя колонка) и доезжаем к «Обычная» (1-й, левый чип виден после скролла).
        composeRule.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("Обычная"))
        composeRule.onNodeWithText("Обычная").assertIsDisplayed()
    }

    // ── AddressSuggestField (без гео-подсказок при монтировании) ──

    @Test
    fun addressSuggestField_rendersLabelAndValue() {
        // На первом кадре picked=true → эффект сразу выходит, гео-сеть не дёргается: видим только поле.
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                AddressSuggestField(
                    value = "Баймаҡ",
                    onValueChange = {},
                    label = "Откуда",
                    leadingIcon = Icons.Default.LocationOn,
                )
            }
        }
        composeRule.onNodeWithText("Откуда").assertIsDisplayed()
        composeRule.onNodeWithText("Баймаҡ").assertIsDisplayed()
    }
}
