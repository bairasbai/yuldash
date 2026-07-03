package com.yuldash.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
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
 * КРИТИЧНЫЙ ПУТЬ — создание поездки. Экран `CreateRideScreen` разрезан на умную обёртку
 * (стейт/сеть/MapKit/пикер даты/оверлей точки) и чистый `CreateRideFormContent(state, callbacks)`.
 *
 * «Плохие» сценарии валидации покрыты прямыми тестами чистого хелпера `createRideValid`
 * (пустой маршрут / цена 0 / отрицательная / пусто / не число / выше максимума) — это и есть
 * логика, по которой кнопка «Опубликовать» блокируется (enabled = !loading && createRideValid).
 * Гард двойного нажатия (enabled=!loading) на кнопке при loading покрыт аналогично в Auth/Chat
 * (там кнопка доступна тесту); в форме поездки кнопка живёт глубоко в LazyColumn, поэтому её
 * UI-состояние тут не ассертим — вместо этого покрыта сама логика валидации.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CreateRideFormContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    @androidx.compose.runtime.Composable
    private fun Content(
        from: String = "Уфа",
        to: String = "Сибай",
        price: String = "300",
        typeKey: String = "regular",
        loading: Boolean = false,
        error: String? = null,
        onSelectType: (String) -> Unit = {},
        onPublish: () -> Unit = {},
    ) {
        CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
            CreateRideFormContent(
                from = from, to = to, dateText = "", seats = "2", price = price, comment = "",
                typeKey = typeKey, recurrence = "none", receiverName = "", parcelSize = "",
                pickup = "", pinned = false,
                womenOnly = false, childSeat = false, petsAllowed = false,
                baggage = false, airConditioner = false, smoking = false,
                priceHint = 0, loading = loading, error = error,
                onFromChange = {}, onToChange = {}, onSeatsChange = {}, onPriceChange = {},
                onCommentChange = {}, onSelectType = onSelectType, onSelectRecurrence = {},
                onReceiverNameChange = {}, onParcelSizeChange = {}, onPickupChange = {},
                onOpenPicker = {}, onOpenDatePicker = {}, onUsePriceHint = {},
                onWomenOnly = {}, onChildSeat = {}, onPetsAllowed = {}, onBaggage = {},
                onAirConditioner = {}, onSmoking = {},
                onPublish = onPublish, onCancel = {},
            )
        }
    }

    // ─────────── Плохие сценарии валидации (прямой вызов чистого хелпера) ───────────

    @Test
    fun valid_whenRouteFilledAndPriceInRange() {
        assertTrue(createRideValid("Уфа", "Сибай", "300"))
    }

    @Test
    fun invalid_whenFromBlank() {
        assertFalse(createRideValid("", "Сибай", "300"))
        assertFalse(createRideValid("   ", "Сибай", "300"))
    }

    @Test
    fun invalid_whenToBlank() {
        assertFalse(createRideValid("Уфа", "", "300"))
    }

    @Test
    fun invalid_whenPriceZeroNegativeEmptyOrNaN() {
        assertFalse(createRideValid("Уфа", "Сибай", "0"))
        assertFalse(createRideValid("Уфа", "Сибай", "-50"))
        assertFalse(createRideValid("Уфа", "Сибай", ""))
        assertFalse(createRideValid("Уфа", "Сибай", "abc"))
    }

    @Test
    fun invalid_whenPriceAboveMax() {
        assertFalse(createRideValid("Уфа", "Сибай", (RIDE_PRICE_MAX + 1).toString()))
    }

    // ─────────── Базовый рендер формы (верх виден) ───────────

    @Test
    fun header_isDisplayed() {
        composeRule.setContent { Content() }
        composeRule.onNodeWithText("Маршрут для своих").assertIsDisplayed()
    }

    @Test
    fun rideType_selectCargoChip_firesOnSelectTypeWithKey() {
        var selected: String? = null
        composeRule.setContent { Content(onSelectType = { selected = it }) }
        composeRule.onNodeWithText("Груз").performClick()   // ключ "cargo" (rideTypeMeta); чип в LazyRow вверху
        assertTrue(selected == "cargo")
    }
}
