package com.yuldash.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * «В глубину» №4 — добор непокрытого в AccessibilityScreens.kt. Берём то, чего НЕ было в
 * сериях ScreensContent/Deep/Deep2/Deep3/Forms:
 *  - `SimpleModeScreen` (умный экран, но входы — только List<LocalRequest> + колбэки): шапка,
 *    большие/малые действия зовут свои колбэки, блок «Последние заявки» через `LocalRequestCard`
 *    (флип private→internal).
 *  - `CreatePassengerRequestContent` НИЖНИЕ блоки, которых Deep3 не касался (он проверял только
 *    верх: шапка/адреса/дата/1-й чип): секция «Условия поездки» (свитчи `PrefToggleRow`),
 *    поля «Мест»/«Цена»/«Комментарий», чеклист «Проверка заявки», клики по чипам категорий,
 *    тумблеры условий и состояние кнопки submit (disabled при loading/невалидности, click при валидной).
 *
 * ⭐ Окно высокое (`w411dp-h2600dp`) → форма-заявка целиком в кадре, `performScrollToNode` НЕ нужен
 * (на этом экране скролл + autoAdvance=false раньше давал дедлок — поэтому клок НЕ морозим и НЕ скроллим).
 * Строки — дословно из исходника; RU-тесты держат RU-строки, BA-тесты — BA (не смешиваем).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccessibilityDeep4ContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    // ──────────────────────── SimpleModeScreen ────────────────────────

    @Composable
    private fun SimpleMode(
        requests: List<LocalRequest> = emptyList(),
        onBack: () -> Unit = {},
        onVoiceRequest: () -> Unit = {},
        onFamilyOrder: () -> Unit = {},
        onTrustedContacts: () -> Unit = {},
        onRepeatTrip: () -> Unit = {},
        onCallbackHelp: () -> Unit = {},
        onSos: () -> Unit = {},
        onChat: () -> Unit = {},
    ) {
        CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
            SimpleModeScreen(
                latestRequests = requests,
                onBack = onBack,
                onVoiceRequest = onVoiceRequest,
                onFamilyOrder = onFamilyOrder,
                onTrustedContacts = onTrustedContacts,
                onRepeatTrip = onRepeatTrip,
                onCallbackHelp = onCallbackHelp,
                onSos = onSos,
                onChat = onChat,
            )
        }
    }

    @Test
    fun simpleMode_showsHeaderAndBigActions() {
        composeRule.setContent { SimpleMode() }
        composeRule.onNodeWithText("Юлдаш без сложностей").assertIsDisplayed()
        composeRule.onNodeWithText("Сказать маршрут").assertIsDisplayed()
        composeRule.onNodeWithText("Позвоните мне").assertIsDisplayed()
        composeRule.onNodeWithText("SOS").assertIsDisplayed()
        composeRule.onNodeWithText("Частые маршруты").assertIsDisplayed()
    }

    @Test
    fun simpleMode_bashkir_showsHeader() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                SimpleModeScreen(
                    latestRequests = emptyList(),
                    onBack = {}, onVoiceRequest = {}, onFamilyOrder = {}, onTrustedContacts = {},
                    onRepeatTrip = {}, onCallbackHelp = {}, onSos = {}, onChat = {},
                )
            }
        }
        composeRule.onNodeWithText("Юлдаш еңел").assertIsDisplayed()
    }

    @Test
    fun simpleMode_voiceActionClick_firesCallback() {
        var fired = false
        composeRule.setContent { SimpleMode(onVoiceRequest = { fired = true }) }
        composeRule.onNodeWithText("Сказать маршрут").performClick()
        assertTrue(fired)
    }

    @Test
    fun simpleMode_sosActionClick_firesCallback() {
        var fired = false
        composeRule.setContent { SimpleMode(onSos = { fired = true }) }
        composeRule.onNodeWithText("SOS").performClick()
        assertTrue(fired)
    }

    @Test
    fun simpleMode_smallActions_showTitlesAndFireCallbacks() {
        var family = false
        var chat = false
        composeRule.setContent { SimpleMode(onFamilyOrder = { family = true }, onChat = { chat = true }) }
        composeRule.onNodeWithText("За близкого").assertIsDisplayed()
        composeRule.onNodeWithText("Контакты").assertIsDisplayed()
        composeRule.onNodeWithText("Чат").performClick()
        assertTrue(chat)
        composeRule.onNodeWithText("За близкого").performClick()
        assertTrue(family)
    }

    @Test
    fun simpleMode_empty_hidesLatestRequestsSection() {
        composeRule.setContent { SimpleMode(requests = emptyList()) }
        // Заголовок «Последние заявки» появляется только когда список непустой.
        composeRule.onNodeWithText("Последние заявки").assertDoesNotExist()
    }

    @Test
    fun simpleMode_withRequests_showsLatestSectionAndCard() {
        val req = LocalRequest(
            title = "Обычная",
            route = "Баймаҡ → Сибай",
            time = "сейчас",
            passenger = "Мама",
            status = "ждём отклики",
        )
        composeRule.setContent { SimpleMode(requests = listOf(req)) }
        composeRule.onNodeWithText("Последние заявки").assertIsDisplayed()
        // LocalRequestCard рисует заголовок, статус-бейдж, маршрут и «время · пассажир».
        composeRule.onNodeWithText("Обычная").assertIsDisplayed()
        composeRule.onNodeWithText("ждём отклики").assertIsDisplayed()
        composeRule.onNodeWithText("Баймаҡ → Сибай").assertIsDisplayed()
        composeRule.onNodeWithText("сейчас · Мама").assertIsDisplayed()
    }

    // ──────────────────────── LocalRequestCard (напрямую) ────────────────────────

    @Test
    fun localRequestCard_withTrustedContact_showsStatusReceiverLine() {
        val req = LocalRequest(
            title = "Заказ за близкого",
            route = "Уфа → Сибай",
            time = "сегодня",
            passenger = "Айгуль",
            status = "ждём отклики",
            trustedContact = "Гульназ",
        )
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                LocalRequestCard(req)
            }
        }
        composeRule.onNodeWithText("Статус получит: Гульназ").assertIsDisplayed()
    }

    @Test
    fun localRequestCard_bashkir_showsStatusReceiverLine() {
        val req = LocalRequest(
            title = "Ябай заявка",
            route = "Өфө → Сибай",
            time = "хәҙер",
            passenger = "Әсәй",
            status = "яуаптар көтәбеҙ",
            trustedContact = "Гөлназ",
        )
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                LocalRequestCard(req)
            }
        }
        composeRule.onNodeWithText("Статус ала: Гөлназ").assertIsDisplayed()
    }

    // ──────────────────── CreatePassengerRequestContent: нижние блоки ────────────────────

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
        womenOnly: Boolean = false,
        childSeat: Boolean = false,
        pets: Boolean = false,
        wheelchair: Boolean = false,
        baggage: Boolean = false,
        nonSmoking: Boolean = false,
        airConditioner: Boolean = false,
        loading: Boolean = false,
        selectedCategoryText: String = "Обычная",
        onCategoryChange: (String) -> Unit = {},
        onSeatsChange: (String) -> Unit = {},
        onPriceChange: (String) -> Unit = {},
        onCommentChange: (String) -> Unit = {},
        onWomenOnlyChange: (Boolean) -> Unit = {},
        onChildSeatChange: (Boolean) -> Unit = {},
        onPetsChange: (Boolean) -> Unit = {},
        onSubmit: () -> Unit = {},
    ) {
        CompositionLocalProvider(LocalAppLanguage provides language) {
            CreatePassengerRequestContent(
                from = from, to = to, time = time, seats = seats, category = category, price = price,
                comment = comment, categories = categories, selectedCategoryText = selectedCategoryText,
                womenOnly = womenOnly, childSeat = childSeat, pets = pets, wheelchair = wheelchair,
                baggage = baggage, nonSmoking = nonSmoking, airConditioner = airConditioner,
                loading = loading,
                onCategoryChange = onCategoryChange, onSeatsChange = onSeatsChange, onPriceChange = onPriceChange,
                onCommentChange = onCommentChange, onTimeChange = {},
                onWomenOnlyChange = onWomenOnlyChange, onChildSeatChange = onChildSeatChange, onPetsChange = onPetsChange,
                onWheelchairChange = {}, onBaggageChange = {}, onNonSmokingChange = {},
                onAirConditionerChange = {},
                // «Только для своих» — параметр появился в экране, тест про него не знал.
                onlyTrusted = false, onOnlyTrustedChange = {},
                onSubmit = onSubmit,
                // Слоты полей адреса не передаём → Content рисует простые OutlinedTextField (без гео-сети).
            )
        }
    }

    /** Раскрыть блок «Дополнительно»: условия поездки, «только для своих» и комментарий свёрнуты
     *  по умолчанию (осознанно — чтобы форма не пугала объёмом). Без этого их просто нет на экране. */
    private fun expandExtras(ba: Boolean = false) {
        composeRule.onNodeWithText(if (ba) "Өҫтәмә" else "Дополнительно").performClick()
    }

    @Test
    fun passenger_conditionsSection_showsTitleAndToggleLabels() {
        composeRule.setContent { PassengerContent() }
        // Отдельного заголовка «Условия поездки» больше нет — блок свернули под «Дополнительно»,
        // а что внутри, перечислено в его подписи. Проверяем подпись по вхождению и сами тумблеры.
        composeRule.onNodeWithText("Условия поездки", substring = true).assertIsDisplayed()
        expandExtras()
        composeRule.onNodeWithText("Только женщины").assertIsDisplayed()
        composeRule.onNodeWithText("Детское кресло").assertIsDisplayed()
        composeRule.onNodeWithText("Еду с животным").assertIsDisplayed()
        composeRule.onNodeWithText("Нужен кондиционер").assertIsDisplayed()
    }

    @Test
    fun passenger_conditionsSection_bashkir_showsTitle() {
        composeRule.setContent {
            PassengerContent(language = AppLanguage.Ba, selectedCategoryText = "Ғәҙәти")
        }
        composeRule.onNodeWithText("Сәфәр шарттары", substring = true).assertIsDisplayed()
        expandExtras(ba = true)
        composeRule.onNodeWithText("Тик ҡатын-ҡыҙ").assertIsDisplayed()
    }

    @Test
    fun passenger_seatsPriceAndCommentFields_areDisplayed() {
        composeRule.setContent { PassengerContent() }
        composeRule.onNodeWithText("Мест").assertIsDisplayed()
        composeRule.onNodeWithText("Цена, ₽").assertIsDisplayed()
        // Комментарий переехал в свёрнутый блок «Дополнительно» — раскрываем, как человек.
        expandExtras()
        composeRule.onNodeWithText("Комментарий").assertIsDisplayed()
    }

    @Test
    fun passenger_checklistCard_showsTitleAndRouteLine() {
        composeRule.setContent { PassengerContent() }
        composeRule.onNodeWithText("Проверка заявки").assertIsDisplayed()
        // Строка маршрута чеклиста собрана из from → to.
        composeRule.onNodeWithText("Баймаҡ → Сибай").assertIsDisplayed()
        // Готовая сумма из цены.
        composeRule.onNodeWithText("Готовая сумма: 350 ₽").assertIsDisplayed()
    }

    @Test
    fun passenger_checklist_bashkir_showsTitle() {
        composeRule.setContent {
            PassengerContent(language = AppLanguage.Ba, selectedCategoryText = "Ғәҙәти")
        }
        composeRule.onNodeWithText("Заявканы тикшереү").assertIsDisplayed()
    }

    @Test
    fun passenger_categoryChipClick_firesOnCategoryChange() {
        var picked: String? = null
        composeRule.setContent { PassengerContent(onCategoryChange = { picked = it }) }
        // «Срочно» — неактивный чип (OutlinedButton) → клик выбирает категорию urgent.
        composeRule.onNodeWithText("Срочно").performClick()
        assertEquals("urgent", picked)
    }

    @Test
    fun passenger_womenOnlyToggle_firesCallback() {
        var toggled: Boolean? = null
        composeRule.setContent { PassengerContent(onWomenOnlyChange = { toggled = it }) }
        // Тумблеров нет, пока блок «Дополнительно» свёрнут — сначала раскрываем.
        expandExtras()
        // Первый свитч в секции «Условия поездки» — «Только женщины» (по порядку PrefToggleRow).
        composeRule.onAllNodes(isToggleable())[0].performClick()
        assertEquals(true, toggled)
    }

    @Test
    fun passenger_submit_disabledWhenTimeBlank() {
        // Пустая дата → форма невалидна → кнопка «Создать заявку» disabled.
        composeRule.setContent { PassengerContent(time = "") }
        composeRule.onNodeWithTag("passenger_submit_btn").assertIsNotEnabled()
    }

    @Test
    fun passenger_submit_disabledWhenLoading() {
        // Гард двойного нажатия: loading=true → кнопка disabled даже при валидной форме.
        // loading рисует бесконечный CircularProgressIndicator → морозим клок (скролла тут нет — окно высокое).
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent { PassengerContent(loading = true) }
        composeRule.onNodeWithTag("passenger_submit_btn").assertIsNotEnabled()
    }

    @Test
    fun passenger_submit_enabledAndClickFiresOnSubmit_whenValid() {
        var submitted = false
        composeRule.setContent { PassengerContent(onSubmit = { submitted = true }) }
        composeRule.onNodeWithTag("passenger_submit_btn").assertIsEnabled()
        composeRule.onNodeWithTag("passenger_submit_btn").performClick()
        assertTrue(submitted)
    }
}
