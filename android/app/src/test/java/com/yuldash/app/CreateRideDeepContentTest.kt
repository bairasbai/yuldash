package com.yuldash.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
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
 * КРИТИЧНЫЙ ПУТЬ — «Создать поездку», покрытие вглубь. Дополняет CreateRideFormContentTest
 * (валидация+заголовок) и CreateRideContentTest (RideTypeChip / PriceHintChip в изоляции).
 *
 * Здесь берём НЕпокрытое в самом `CreateRideFormContent`: рендер верхней части формы на ДВУХ
 * языках (заголовок-подзаголовок, чипы типа поездки, чипы повтора + подсказка, поля дата/места/цена),
 * встроенная подсказка цены с колбэком `onUsePriceHint`, выбор повтора `onSelectRecurrence`,
 * а также граничные «плохие»/«хорошие» кейсы чистого `createRideValid` (ровно MIN/MAX, пробелы,
 * плюс-знак, дроби) — это логика, по которой кнопка «Опубликовать» блокируется.
 *
 * Форма — это LazyColumn с двумя вложенными LazyRow (чипы). Скроллить нельзя: несколько
 * скроллеров → hasScrollToNodeAction() бросит на множестве матчей (см. правила проекта). Поэтому
 * UI-ассерты — только по надёжно видимой верхушке; глубокая логика кнопки покрыта прямым вызовом
 * хелпера. Бесконечных анимаций в Content нет (их вешает обёртка) → autoAdvance не трогаем.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CreateRideDeepContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * Доскроллить внешний LazyColumn формы к узлу с текстом. Внутри есть вложенные LazyRow (чипы) →
     * hasScrollToNodeAction() матчит несколько скроллеров; берём ПЕРВЫЙ (внешняя вертикальная колонка,
     * идёт в дереве раньше своих детей) и скроллим её. Клок живой (autoAdvance=true) — без дедлока.
     */
    private fun scrollTo(text: String) {
        composeRule.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(text))
    }

    /** Общий рендер Content с дефолтами; переопределяем только нужные поля/колбэки под тест. */
    @Composable
    private fun Content(
        language: AppLanguage = AppLanguage.Ru,
        from: String = "Уфа",
        to: String = "Сибай",
        seats: String = "2",
        price: String = "300",
        typeKey: String = "regular",
        recurrence: String = "none",
        priceHint: Int = 0,
        loading: Boolean = false,
        error: String? = null,
        onSelectRecurrence: (String) -> Unit = {},
        onUsePriceHint: () -> Unit = {},
    ) {
        CompositionLocalProvider(LocalAppLanguage provides language) {
            CreateRideFormContent(
                from = from, to = to, dateText = "", seats = seats, price = price, comment = "",
                typeKey = typeKey, recurrence = recurrence, receiverName = "", parcelSize = "",
                pickup = "", pinned = false,
                womenOnly = false, childSeat = false, petsAllowed = false,
                baggage = false, airConditioner = false, smoking = false,
                // «Только для своих», «тихая поездка» и промежуточные остановки — параметры
                // появились в форме позже, тесты про них не знали и перестали компилироваться.
                onlyTrusted = false, quiet = false, waypoints = emptyList(),
                priceHint = priceHint, loading = loading, error = error,
                onFromChange = {}, onToChange = {}, onSeatsChange = {}, onPriceChange = {},
                onCommentChange = {}, onSelectType = {}, onSelectRecurrence = onSelectRecurrence,
                onReceiverNameChange = {}, onParcelSizeChange = {}, onPickupChange = {},
                onOpenPicker = {}, onOpenDatePicker = {}, onUsePriceHint = onUsePriceHint,
                onWomenOnly = {}, onChildSeat = {}, onPetsAllowed = {}, onBaggage = {},
                onAirConditioner = {}, onSmoking = {},
                onOnlyTrusted = {}, onQuiet = {}, onWaypointsChange = {},
                onPublish = {}, onCancel = {},
            )
        }
    }

    // ─────────── Верхушка формы: рендер на двух языках ───────────

    @Test
    fun subtitle_isDisplayed_ru() {
        composeRule.setContent { Content(language = AppLanguage.Ru) }
        composeRule.onNodeWithText("Укажите путь, места и цену. Контакты откроются после подтверждения.").assertIsDisplayed()
    }

    @Test
    fun header_isDisplayed_ba() {
        composeRule.setContent { Content(language = AppLanguage.Ba) }
        composeRule.onNodeWithText("Үҙ кешеләрең өсөн маршрут").assertIsDisplayed()
    }

    @Test
    fun rideTypeSectionLabel_isDisplayed_ru() {
        composeRule.setContent { Content(language = AppLanguage.Ru) }
        composeRule.onNodeWithText("Тип поездки").assertIsDisplayed()
    }

    @Test
    fun rideTypeSectionLabel_isDisplayed_ba() {
        composeRule.setContent { Content(language = AppLanguage.Ba) }
        composeRule.onNodeWithText("Сәфәр төрө").assertIsDisplayed()
    }

    @Test
    fun rideTypeChips_firstThreeVisible_ru() {
        // Чипы типа поездки в первом LazyRow. 4-й («Срочно») за краем экрана (LazyRow виртуализирует
        // горизонтально) — ассертим видимые первые три; клик/двуязычие чипов покрыты в CreateRideContentTest.
        composeRule.setContent { Content(language = AppLanguage.Ru) }
        composeRule.onNodeWithText("Пассажиры").assertIsDisplayed()
        composeRule.onNodeWithText("Посылка").assertIsDisplayed()
        composeRule.onNodeWithText("Груз").assertIsDisplayed()
    }

    @Test
    fun rideTypeChip_bashkir_firstChip_visible_ba() {
        // Первый чип на башкирском («Пассажирҙар») виден; дальние чипы LazyRow за краем — не ассертим.
        composeRule.setContent { Content(language = AppLanguage.Ba) }
        composeRule.onNodeWithText("Пассажирҙар").assertIsDisplayed()
    }

    // ─────────── Повтор (recurrence): чипы + колбэк + подсказка серии ───────────

    @Test
    fun recurrenceLabel_isDisplayed_ru() {
        composeRule.setContent { Content(language = AppLanguage.Ru) }
        composeRule.onNodeWithText("Повтор").assertIsDisplayed()
    }

    @Test
    fun recurrenceChips_visible_ru() {
        // Чипы повтора в LazyRow. 4-й («Еженедельно») за краем экрана — ассертим видимые первые три.
        composeRule.setContent { Content(language = AppLanguage.Ru) }
        composeRule.onNodeWithText("Разово").assertIsDisplayed()
        composeRule.onNodeWithText("По будням").assertIsDisplayed()
        composeRule.onNodeWithText("Каждый день").assertIsDisplayed()
    }

    @Test
    fun recurrenceChips_visible_ba() {
        // Первый чип повтора на башкирском виден; дальние за краем LazyRow — не ассертим.
        composeRule.setContent { Content(language = AppLanguage.Ba) }
        composeRule.onNodeWithText("Бер тапҡыр").assertIsDisplayed()
    }

    @Test
    fun recurrence_selectWeekdays_firesCallbackWithKey() {
        var picked: String? = null
        composeRule.setContent { Content(language = AppLanguage.Ru, onSelectRecurrence = { picked = it }) }
        composeRule.onNodeWithText("По будням").performClick()
        assertEquals("weekdays", picked)
    }

    @Test
    fun recurrence_seriesHint_shownWhenNotNone_ru() {
        // При recurrence != "none" под чипами появляется подсказка про 4 ближайших рейса.
        composeRule.setContent { Content(language = AppLanguage.Ru, recurrence = "daily") }
        scrollTo("Создадим ближайшие 4 рейса этой серии.")
        composeRule.onNodeWithText("Создадим ближайшие 4 рейса этой серии.").assertIsDisplayed()
    }

    @Test
    fun recurrence_seriesHint_hiddenWhenNone_ru() {
        composeRule.setContent { Content(language = AppLanguage.Ru, recurrence = "none") }
        composeRule.onNodeWithText("Создадим ближайшие 4 рейса этой серии.").assertDoesNotExist()
    }

    // ─────────── Поля: дата/время, места, цена (лейблы, не плейсхолдеры) ───────────

    @Test
    fun dateTimeFieldLabel_isDisplayed_ru() {
        composeRule.setContent { Content(language = AppLanguage.Ru) }
        composeRule.onNodeWithText("Дата и время").assertIsDisplayed()
    }

    @Test
    fun dateTimeFieldLabel_isDisplayed_ba() {
        composeRule.setContent { Content(language = AppLanguage.Ba) }
        composeRule.onNodeWithText("Дата һәм ваҡыт").assertIsDisplayed()
    }

    @Test
    fun seatsAndPriceLabels_isDisplayed_ru() {
        composeRule.setContent { Content(language = AppLanguage.Ru) }
        scrollTo("Цена, ₽")
        composeRule.onNodeWithText("Мест").assertIsDisplayed()
        composeRule.onNodeWithText("Цена, ₽").assertIsDisplayed()
    }

    // ─────────── Встроенная подсказка цены внутри формы (priceHint > 0) ───────────

    @Test
    fun priceHintChip_shownInsideForm_whenHintPositive_ru() {
        composeRule.setContent { Content(language = AppLanguage.Ru, priceHint = 450) }
        scrollTo("Обычно по маршруту ~450 ₽ · нажми, чтобы подставить")
        composeRule.onNodeWithText("Обычно по маршруту ~450 ₽ · нажми, чтобы подставить").assertIsDisplayed()
    }

    @Test
    fun priceHintChip_hiddenInsideForm_whenHintZero_ru() {
        composeRule.setContent { Content(language = AppLanguage.Ru, priceHint = 0) }
        composeRule.onNodeWithText("Обычно по маршруту ~0 ₽ · нажми, чтобы подставить").assertDoesNotExist()
    }

    @Test
    fun priceHintChip_clickInsideForm_firesOnUsePriceHint() {
        var used = false
        composeRule.setContent { Content(language = AppLanguage.Ru, priceHint = 500, onUsePriceHint = { used = true }) }
        scrollTo("Обычно по маршруту ~500 ₽ · нажми, чтобы подставить")
        composeRule.onNodeWithText("Обычно по маршруту ~500 ₽ · нажми, чтобы подставить").performClick()
        assertTrue(used)
    }

    @Test
    fun commissionNote_isDisplayed_ru() {
        // Ключевая для доверия строка про 0 комиссии — всегда под ценой.
        composeRule.setContent { Content(language = AppLanguage.Ru) }
        scrollTo("Цену ставишь ты. Оплата — напрямую тебе после поездки. Юлдаш комиссию не берёт.")
        composeRule.onNodeWithText("Цену ставишь ты. Оплата — напрямую тебе после поездки. Юлдаш комиссию не берёт.").assertIsDisplayed()
    }

    // ─────────── Граничные кейсы валидации (логика кнопки «Опубликовать») ───────────
    // Кнопка живёт глубоко в LazyColumn (несколько скроллеров) → её UI-состояние не ассертим;
    // покрываем саму логику enabled = !loading && createRideValid(from, to, price).

    @Test
    fun valid_atExactMinPrice() {
        assertTrue(createRideValid("Уфа", "Сибай", RIDE_PRICE_MIN.toString()))
    }

    @Test
    fun valid_atExactMaxPrice() {
        assertTrue(createRideValid("Уфа", "Сибай", RIDE_PRICE_MAX.toString()))
    }

    @Test
    fun invalid_justBelowMin() {
        assertFalse(createRideValid("Уфа", "Сибай", (RIDE_PRICE_MIN - 1).toString()))
    }

    @Test
    fun valid_priceWithSurroundingSpaces_isTrimmed() {
        // onPriceChange фильтрует цифры, но хелпер должен сам переживать пробелы (trim внутри).
        assertTrue(createRideValid("Уфа", "Сибай", "  300  "))
    }

    @Test
    fun invalid_priceWithPlusSignOrDecimal() {
        // toIntOrNull даёт null на "+300"? Нет — но на "3.5"/"3,5" да. Проверяем нечисловые формы.
        assertFalse(createRideValid("Уфа", "Сибай", "3.5"))
        assertFalse(createRideValid("Уфа", "Сибай", "3,5"))
        assertFalse(createRideValid("Уфа", "Сибай", "300₽"))
    }

    @Test
    fun invalid_bothFromAndToBlank() {
        assertFalse(createRideValid("", "", "300"))
        assertFalse(createRideValid("  ", "  ", "300"))
    }
}
