package com.yuldash.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
 * КРИТИЧНЫЙ ПУТЬ — «Создать поездку», покрытие вглубь #2. Добирает НЕпокрытое, которого нет в
 * CreateRideFormContentTest (валидация+заголовок), CreateRideContentTest (RideTypeChip/PriceHintChip
 * в изоляции) и CreateRideDeepContentTest (верхушка формы + повтор + подсказка цены + границы цены):
 *
 *  - Блок «посылка/груз» (isCargo): поля получателя «Кому передать (имя)» и «Габарит / вес»
 *    показываются ТОЛЬКО для parcel/cargo и скрыты для regular (обе локали);
 *  - Комментарий меняет лейбл/плейсхолдер под груз («Что везёте») vs обычный («Комментарий»);
 *  - Карточка «Условия поездки» (PrefToggleRow ×6): заголовок, все 6 подписей, тумблеры Switch
 *    и их колбэки (women/child/pets/baggage/AC/smoking) — «тумблер обязан что-то делать»;
 *  - Блок pickup: поле «Где встречаемся» + кнопка карты в двух состояниях (не отмечено / отмечено);
 *  - InfoCard «Платное поднятие»;
 *  - Кнопка «Опубликовать»: строка ошибки над ней, состояния enabled/disabled прямо в UI
 *    (валидный маршрут+цена vs пустой from / цена вне диапазона / loading), клик по колбэку;
 *  - «Отмена» (onCancel).
 *
 * Всё — чистый `CreateRideFormContent(state, callbacks)` без сети/MapKit/пикера (их держит умная
 * обёртка CreateRideScreen). Высокое окно (h2600dp) → весь LazyColumn в кадре, performScrollToNode
 * НЕ нужен (обходим и виртуализацию, и вложенные скроллеры). Switch → onNode(isToggleable()).
 * Строки — ДОСЛОВНО из CreateRideScreen.kt под нужный язык, RU и BA не мешаем.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CreateRideDeep2ContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** Общий рендер Content с дефолтами; переопределяем только нужные поля/колбэки под тест. */
    @Composable
    private fun Content(
        language: AppLanguage = AppLanguage.Ru,
        from: String = "Уфа",
        to: String = "Сибай",
        price: String = "300",
        comment: String = "",
        typeKey: String = "regular",
        receiverName: String = "",
        parcelSize: String = "",
        pickup: String = "",
        pinned: Boolean = false,
        womenOnly: Boolean = false,
        childSeat: Boolean = false,
        petsAllowed: Boolean = false,
        baggage: Boolean = false,
        airConditioner: Boolean = false,
        smoking: Boolean = false,
        loading: Boolean = false,
        error: String? = null,
        onReceiverNameChange: (String) -> Unit = {},
        onParcelSizeChange: (String) -> Unit = {},
        onPickupChange: (String) -> Unit = {},
        onOpenPicker: () -> Unit = {},
        onWomenOnly: (Boolean) -> Unit = {},
        onChildSeat: (Boolean) -> Unit = {},
        onPetsAllowed: (Boolean) -> Unit = {},
        onBaggage: (Boolean) -> Unit = {},
        onAirConditioner: (Boolean) -> Unit = {},
        onSmoking: (Boolean) -> Unit = {},
        onPublish: () -> Unit = {},
        onCancel: () -> Unit = {},
    ) {
        CompositionLocalProvider(LocalAppLanguage provides language) {
            CreateRideFormContent(
                from = from, to = to, dateText = "", seats = "2", price = price, comment = comment,
                typeKey = typeKey, recurrence = "none", receiverName = receiverName, parcelSize = parcelSize,
                pickup = pickup, pinned = pinned,
                womenOnly = womenOnly, childSeat = childSeat, petsAllowed = petsAllowed,
                baggage = baggage, airConditioner = airConditioner, smoking = smoking,
                quiet = false, waypoints = emptyList(), onlyTrusted = false,
                priceHint = 0, loading = loading, error = error,
                onFromChange = {}, onToChange = {}, onSeatsChange = {}, onPriceChange = {},
                onCommentChange = {}, onSelectType = {}, onSelectRecurrence = {},
                onReceiverNameChange = onReceiverNameChange, onParcelSizeChange = onParcelSizeChange,
                onPickupChange = onPickupChange, onOpenPicker = onOpenPicker,
                onOpenDatePicker = {}, onUsePriceHint = {},
                onWomenOnly = onWomenOnly, onChildSeat = onChildSeat, onPetsAllowed = onPetsAllowed,
                onBaggage = onBaggage, onAirConditioner = onAirConditioner, onSmoking = onSmoking,
                onQuiet = {}, onWaypointsChange = {}, onOnlyTrusted = {},
                onPublish = onPublish, onCancel = onCancel,
            )
        }
    }

    // ─────────── Блок «посылка/груз»: поля получателя (только parcel/cargo) ───────────

    @Test
    fun parcelFields_hidden_whenTypeRegular_ru() {
        composeRule.setContent { Content(language = AppLanguage.Ru, typeKey = "regular") }
        composeRule.onNodeWithText("Кому передать (имя)").assertDoesNotExist()
        composeRule.onNodeWithText("Габарит / вес").assertDoesNotExist()
    }

    @Test
    fun parcelFields_shown_whenTypeParcel_ru() {
        composeRule.setContent { Content(language = AppLanguage.Ru, typeKey = "parcel") }
        composeRule.onNodeWithText("Кому передать (имя)").assertIsDisplayed()
        composeRule.onNodeWithText("Габарит / вес").assertIsDisplayed()
    }

    @Test
    fun parcelFields_shown_whenTypeCargo_ru() {
        composeRule.setContent { Content(language = AppLanguage.Ru, typeKey = "cargo") }
        composeRule.onNodeWithText("Кому передать (имя)").assertIsDisplayed()
        composeRule.onNodeWithText("Габарит / вес").assertIsDisplayed()
    }

    @Test
    fun parcelFields_shown_whenTypeParcel_ba() {
        composeRule.setContent { Content(language = AppLanguage.Ba, typeKey = "parcel") }
        composeRule.onNodeWithText("Кемгә тапшырырға (исем)").assertIsDisplayed()
        composeRule.onNodeWithText("Үлсәм / ауырлыҡ").assertIsDisplayed()
    }

    @Test
    fun receiverNameField_input_firesCallback() {
        var typed: String? = null
        composeRule.setContent {
            Content(language = AppLanguage.Ru, typeKey = "parcel", onReceiverNameChange = { typed = it })
        }
        composeRule.onNodeWithText("Кому передать (имя)").performTextInput("Айгуль")
        assertEquals("Айгуль", typed)
    }

    @Test
    fun parcelSizeField_input_firesCallback() {
        var typed: String? = null
        composeRule.setContent {
            Content(language = AppLanguage.Ru, typeKey = "cargo", onParcelSizeChange = { typed = it })
        }
        composeRule.onNodeWithText("Габарит / вес").performTextInput("5 кг")
        assertEquals("5 кг", typed)
    }

    // ─────────── Комментарий: лейбл меняется под груз ───────────

    @Test
    fun commentLabel_regular_isComment_ru() {
        composeRule.setContent { Content(language = AppLanguage.Ru, typeKey = "regular") }
        composeRule.onNodeWithText("Комментарий").assertIsDisplayed()
        composeRule.onNodeWithText("Что везёте").assertDoesNotExist()
    }

    @Test
    fun commentLabel_cargo_isWhatYouCarry_ru() {
        composeRule.setContent { Content(language = AppLanguage.Ru, typeKey = "cargo") }
        composeRule.onNodeWithText("Что везёте").assertIsDisplayed()
    }

    @Test
    fun commentLabel_cargo_isWhatYouCarry_ba() {
        composeRule.setContent { Content(language = AppLanguage.Ba, typeKey = "parcel") }
        composeRule.onNodeWithText("Нимә алып бараһығыҙ").assertIsDisplayed()
    }

    // ─────────── Карточка «Условия поездки»: заголовок + 6 подписей (два языка) ───────────

    @Test
    fun conditionsCard_title_isDisplayed_ru() {
        composeRule.setContent { Content(language = AppLanguage.Ru) }
        composeRule.onNodeWithText("Условия поездки").assertIsDisplayed()
    }

    @Test
    fun conditionsCard_title_isDisplayed_ba() {
        composeRule.setContent { Content(language = AppLanguage.Ba) }
        composeRule.onNodeWithText("Сәфәр шарттары").assertIsDisplayed()
    }

    @Test
    fun conditionsCard_allSixRows_isDisplayed_ru() {
        composeRule.setContent { Content(language = AppLanguage.Ru) }
        composeRule.onNodeWithText("Только женщины").assertIsDisplayed()
        composeRule.onNodeWithText("Детское кресло / бустер").assertIsDisplayed()
        composeRule.onNodeWithText("Можно с животным").assertIsDisplayed()
        composeRule.onNodeWithText("Есть место под багаж").assertIsDisplayed()
        composeRule.onNodeWithText("Кондиционер").assertIsDisplayed()
        composeRule.onNodeWithText("Можно курить").assertIsDisplayed()
    }

    @Test
    fun conditionsCard_rows_isDisplayed_ba() {
        composeRule.setContent { Content(language = AppLanguage.Ba) }
        composeRule.onNodeWithText("Тик ҡатын-ҡыҙ өсөн").assertIsDisplayed()
        composeRule.onNodeWithText("Балалар ултырғысы / бустер").assertIsDisplayed()
        composeRule.onNodeWithText("Хайуан менән").assertIsDisplayed()
        composeRule.onNodeWithText("Багаж урыны бар").assertIsDisplayed()
        composeRule.onNodeWithText("Тартырға ярай").assertIsDisplayed()
    }

    // ─────────── Тумблеры условий: клик по Switch дёргает колбэк (тумблер обязан что-то делать) ───────────
    // 6 PrefToggleRow → 6 Switch. Кликаем по конкретному по индексу в onAllNodes(isToggleable()),
    // порядок в дереве = порядок в коде (women, child, pets, baggage, AC, smoking).

    @Test
    fun womenOnlyToggle_off_click_firesTrue() {
        var v: Boolean? = null
        composeRule.setContent { Content(language = AppLanguage.Ru, womenOnly = false, onWomenOnly = { v = it }) }
        composeRule.onAllNodes(isToggleable())[0].performClick()   // 1-й тумблер = «Только женщины»
        assertEquals(true, v)
    }

    @Test
    fun childSeatToggle_off_click_firesTrue() {
        var v: Boolean? = null
        composeRule.setContent { Content(language = AppLanguage.Ru, childSeat = false, onChildSeat = { v = it }) }
        composeRule.onAllNodes(isToggleable())[1].performClick()   // 2-й = «Детское кресло / бустер»
        assertEquals(true, v)
    }

    @Test
    fun petsToggle_off_click_firesTrue() {
        var v: Boolean? = null
        composeRule.setContent { Content(language = AppLanguage.Ru, petsAllowed = false, onPetsAllowed = { v = it }) }
        composeRule.onAllNodes(isToggleable())[2].performClick()   // 3-й = «Можно с животным»
        assertEquals(true, v)
    }

    @Test
    fun baggageToggle_off_click_firesTrue() {
        var v: Boolean? = null
        composeRule.setContent { Content(language = AppLanguage.Ru, baggage = false, onBaggage = { v = it }) }
        composeRule.onAllNodes(isToggleable())[3].performClick()   // 4-й = «Есть место под багаж»
        assertEquals(true, v)
    }

    @Test
    fun airConditionerToggle_off_click_firesTrue() {
        var v: Boolean? = null
        composeRule.setContent { Content(language = AppLanguage.Ru, airConditioner = false, onAirConditioner = { v = it }) }
        composeRule.onAllNodes(isToggleable())[4].performClick()   // 5-й = «Кондиционер»
        assertEquals(true, v)
    }

    @Test
    fun smokingToggle_on_click_firesFalse() {
        var v: Boolean? = null
        composeRule.setContent { Content(language = AppLanguage.Ru, smoking = true, onSmoking = { v = it }) }
        composeRule.onAllNodes(isToggleable())[5].performClick()   // 6-й = «Можно курить», включён → просит false
        assertEquals(false, v)
    }

    // ─────────── Блок pickup: поле «Где встречаемся» + кнопка карты (два состояния) ───────────

    @Test
    fun pickupField_label_isDisplayed_ru() {
        composeRule.setContent { Content(language = AppLanguage.Ru) }
        composeRule.onNodeWithText("Где встречаемся").assertIsDisplayed()
    }

    @Test
    fun pickupField_label_isDisplayed_ba() {
        composeRule.setContent { Content(language = AppLanguage.Ba) }
        composeRule.onNodeWithText("Ҡайҙа осрашабыҙ").assertIsDisplayed()
    }

    @Test
    fun pickupField_input_firesCallback() {
        var typed: String? = null
        composeRule.setContent { Content(language = AppLanguage.Ru, onPickupChange = { typed = it }) }
        composeRule.onNodeWithText("Где встречаемся").performTextInput("у автовокзала")
        assertEquals("у автовокзала", typed)
    }

    @Test
    fun mapButton_notPinned_showsMarkOnMap_ru() {
        composeRule.setContent { Content(language = AppLanguage.Ru, pinned = false) }
        composeRule.onNodeWithText("Отметить на карте").assertIsDisplayed()
        composeRule.onNodeWithText("Точка на карте отмечена · изменить").assertDoesNotExist()
    }

    @Test
    fun mapButton_pinned_showsMarked_ru() {
        composeRule.setContent { Content(language = AppLanguage.Ru, pinned = true) }
        composeRule.onNodeWithText("Точка на карте отмечена · изменить").assertIsDisplayed()
    }

    @Test
    fun mapButton_notPinned_showsMarkOnMap_ba() {
        composeRule.setContent { Content(language = AppLanguage.Ba, pinned = false) }
        composeRule.onNodeWithText("Картала билдәләргә").assertIsDisplayed()
    }

    @Test
    fun mapButton_click_firesOnOpenPicker() {
        var opened = false
        composeRule.setContent { Content(language = AppLanguage.Ru, pinned = false, onOpenPicker = { opened = true }) }
        composeRule.onNodeWithText("Отметить на карте").performClick()
        assertTrue(opened)
    }

    // ─────────── InfoCard «Платное поднятие» ───────────

    @Test
    fun boostInfoCard_isDisplayed_ru() {
        composeRule.setContent { Content(language = AppLanguage.Ru) }
        composeRule.onNodeWithText("Платное поднятие").assertIsDisplayed()
        composeRule.onNodeWithText("Можно добавить после публикации. Обычные поездки остаются бесплатными.").assertIsDisplayed()
    }

    @Test
    fun boostInfoCard_isDisplayed_ba() {
        composeRule.setContent { Content(language = AppLanguage.Ba) }
        composeRule.onNodeWithText("Түләүле күтәреү").assertIsDisplayed()
    }

    // ─────────── Кнопка «Опубликовать»: ошибка + enabled/disabled в UI + клик ───────────
    // Кнопка помечена testTag("publish_btn"). Высокое окно → она в кадре, ассертим состояние прямо.

    @Test
    fun errorText_shownAboveButton_whenPresent_ru() {
        val err = "Не удалось опубликовать. Проверь сеть и повтори."
        composeRule.setContent { Content(language = AppLanguage.Ru, error = err) }
        composeRule.onNodeWithText(err).assertIsDisplayed()
    }

    @Test
    fun publishButton_label_isDisplayed_ru() {
        composeRule.setContent { Content(language = AppLanguage.Ru) }
        composeRule.onNodeWithText("Опубликовать").assertIsDisplayed()
    }

    @Test
    fun publishButton_label_isDisplayed_ba() {
        composeRule.setContent { Content(language = AppLanguage.Ba) }
        composeRule.onNodeWithText("Баҫтырыу").assertIsDisplayed()
    }

    @Test
    fun publishButton_enabled_whenRouteAndPriceValid() {
        composeRule.setContent { Content(from = "Уфа", to = "Сибай", price = "300") }
        composeRule.onNodeWithText("Опубликовать").assertIsEnabled()
    }

    @Test
    fun publishButton_disabled_whenFromBlank() {
        composeRule.setContent { Content(from = "", to = "Сибай", price = "300") }
        composeRule.onNodeWithText("Опубликовать").assertIsNotEnabled()
    }

    @Test
    fun publishButton_disabled_whenPriceOutOfRange() {
        composeRule.setContent { Content(from = "Уфа", to = "Сибай", price = "0") }
        composeRule.onNodeWithText("Опубликовать").assertIsNotEnabled()
    }

    @Test
    fun publishButton_disabled_whenLoading_evenIfValid() {
        composeRule.setContent { Content(from = "Уфа", to = "Сибай", price = "300", loading = true) }
        // При loading кнопка показывает спиннер (не текст) и disabled → ищем по testTag.
        composeRule.onNodeWithTag("publish_btn").assertIsNotEnabled()
    }

    @Test
    fun publishButton_click_firesOnPublish_whenValid() {
        var published = false
        composeRule.setContent { Content(from = "Уфа", to = "Сибай", price = "300", onPublish = { published = true }) }
        composeRule.onNodeWithText("Опубликовать").performClick()
        assertTrue(published)
    }

    // ─────────── «Отмена» ───────────

    @Test
    fun cancelButton_isDisplayed_andClickFires_ru() {
        var cancelled = false
        composeRule.setContent { Content(language = AppLanguage.Ru, onCancel = { cancelled = true }) }
        assertFalse(cancelled)
        composeRule.onNodeWithText("Отмена").performClick()
        assertTrue(cancelled)
    }

    @Test
    fun cancelButton_isDisplayed_ba() {
        composeRule.setContent { Content(language = AppLanguage.Ba) }
        composeRule.onNodeWithText("Кире алыу").assertIsDisplayed()
    }
}
