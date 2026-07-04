package com.yuldash.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.yuldash.app.data.BoostPlanDto
import com.yuldash.app.data.BoostResultDto
import com.yuldash.app.data.RideDto
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * «В глубину-4» по экранам поддержки/поднятия (`SupportContent` + `BoostContent`). Добираем ветки,
 * не покрытые SupportBoostContentTest / Deep / Deep2 / Deep3:
 *
 *  SupportContent — башкирские ветки состояний (кнопка доната / ошибка / «ждём перевод» / «Не сейчас»)
 *    + «своя сумма» с ВАЛИДНЫМ вводом (подсказка-диапазона скрыта, кнопка активна) на двух языках.
 *  BoostContent — ветки, которые Deep не задел: `error` (текст ошибки оплаты item'ом), финальный
 *    дисклеймер, НЕпустой `resultSlot` (result != null → слот зовётся), башкирские заголовки списка.
 *
 * Заголовок класса — как в SupportBoostDeep3ContentTest. Высокое окно (`w411dp-h2600dp`) → экран
 * целиком, скролл не нужен. Карточки Boost анимируются (`animateColorAsState` рамки + `bounceClick`,
 * оба конечные) → в тестах со списком первой строкой гасим авто-часы `autoAdvance = false` (idle-sync);
 * контент при этом полностью размещён и виден (alpha=1), скролл НЕ комбинируем (см. lessons).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SupportBoostDeep4ContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    // ==================== SupportContent: башкирские ветки + валидная «своя сумма» ====================

    @Composable
    private fun Support(
        selectedAmount: Int = 30,
        customMode: Boolean = false,
        customInput: String = "",
        completed: Boolean = false,
        sending: Boolean = false,
        sendError: Boolean = false,
        effectiveAmount: Int? = 30,
        amountValid: Boolean = true,
        onSelectAmount: (Int) -> Unit = {},
        onToggleCustom: () -> Unit = {},
        onCustomInputChange: (String) -> Unit = {},
        onDonate: () -> Unit = {},
        onBack: () -> Unit = {},
    ) = SupportContent(
        selectedAmount = selectedAmount, customMode = customMode, customInput = customInput,
        completed = completed, sending = sending, sendError = sendError, minAmount = 10,
        effectiveAmount = effectiveAmount, amountValid = amountValid,
        onSelectAmount = onSelectAmount, onToggleCustom = onToggleCustom,
        onCustomInputChange = onCustomInputChange, onDonate = onDonate, onBack = onBack,
    )

    @Test
    fun support_bashkir_validAmount_showsBashkirDonateLabel() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                Support(effectiveAmount = 100, amountValid = true)
            }
        }
        // amountValid + BA → "$effectiveAmount ₽ менән ярҙам итеү"
        composeRule.onNodeWithText("100 ₽ менән ярҙам итеү").assertIsDisplayed()
    }

    @Test
    fun support_bashkir_sendError_showsBashkirErrorMessage() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                Support(sendError = true)
            }
        }
        composeRule.onNodeWithText("Булманы. Селтәрҙе тикшереп ҡабатла.").assertIsDisplayed()
    }

    @Test
    fun support_bashkir_completed_showsBashkirWaitingCard() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                Support(completed = true)
            }
        }
        composeRule.onNodeWithText("Күсереүҙе көтәбеҙ").assertIsDisplayed()
    }

    @Test
    fun support_bashkir_notNow_showsBashkirLabelAndFiresBack() {
        var backed = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                Support(onBack = { backed = true })
            }
        }
        composeRule.onNodeWithText("Хәҙер түгел").assertIsDisplayed()
        composeRule.onNodeWithText("Хәҙер түгел").performClick()
        assertTrue(backed)
    }

    @Test
    fun support_customMode_validInput_hidesRangeHint() {
        // customMode + валидный ввод (amountValid=true) → подсказка «От 10 до 100 000 ₽» НЕ показывается,
        // а кнопка доната показывает конкретную сумму (ветка «good» custom, Deep2 бил только invalid).
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                Support(customMode = true, customInput = "200", effectiveAmount = 200, amountValid = true)
            }
        }
        composeRule.onNodeWithText("От 10 до 100 000 ₽").assertDoesNotExist()
        composeRule.onNodeWithText("Поддержать на 200 ₽").assertIsDisplayed()
    }

    // ==================== BoostContent: error / дисклеймер / resultSlot / башкирские заголовки ====================

    private fun ride(id: Int = 1) = RideDto(
        id = id, fromCity = "Уфа", toCity = "Казань", departAt = "2026-07-04T10:00",
        seatsTotal = 4, seatsLeft = 3, price = 500, category = "people",
        driverName = "Азат", driverRating = 4.9, driverVerified = true, driverCar = "Kia Rio",
        boosted = false,
    )

    private fun plan(tier: String = "quick", title: String = "Быстрый подъём", price: Int = 49, hours: Int = 2) =
        BoostPlanDto(tier = tier, title = title, price = price, hours = hours)

    private fun result() = BoostResultDto(
        status = "pending", method = "sbp_manual", paymentId = 1, amount = 149,
        confirmationUrl = null, payeePhone = "+79990001122", payeeBank = "Сбербанк", payeeName = "Азат А.",
    )

    @Composable
    private fun Boost(
        error: String? = null,
        credits: Int = 0,
        selectedTier: String? = null,
        result: BoostResultDto? = null,
        resultSlot: @Composable (BoostResultDto) -> Unit = {},
    ) = BoostContent(
        loading = false, loadError = false,
        rides = listOf(ride()), plans = listOf(plan()),
        selectedRideId = 1, selectedTier = selectedTier, submitting = false,
        error = error, credits = credits, result = result,
        onRetry = {}, onEmptyAction = {}, onSelectRide = {}, onSelectTier = {},
        onBoostFree = {}, onPay = {}, resultSlot = resultSlot,
    )

    @Test
    fun boost_paymentError_showsErrorTextItem() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                Boost(error = "Не получилось. Повтори.")
            }
        }
        composeRule.onNodeWithText("Не получилось. Повтори.").assertIsDisplayed()
    }

    @Test
    fun boost_russian_showsTrailingDisclaimer() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                Boost()
            }
        }
        composeRule.onNodeWithText(
            "Поднятие не гарантирует бронирование и влияет только на релевантные результаты."
        ).assertIsDisplayed()
    }

    @Test
    fun boost_bashkir_showsBashkirHeadersAndDisclaimer() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                Boost()
            }
        }
        composeRule.onNodeWithText("Ҡайһы сәфәрҙе күтәрергә").assertIsDisplayed()
        composeRule.onNodeWithText("Күтәреү тарифы").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Күтәреү бронде гарантияламай һәм тик тура килгән һөҙөмтәләргә генә йоғонто яһай."
        ).assertIsDisplayed()
    }

    @Test
    fun boost_nonNullResult_invokesResultSlot() {
        // result != null → item { resultSlot(res) } зовётся; слот получает тот же res (импур-часть снаружи).
        composeRule.mainClock.autoAdvance = false
        var slotCalls = 0
        var slotAmount = -1
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                Boost(
                    result = result(),
                    resultSlot = { res -> slotCalls++; slotAmount = res.amount; Text("СЛОТ-РЕЗУЛЬТАТ") },
                )
            }
        }
        composeRule.onNodeWithText("СЛОТ-РЕЗУЛЬТАТ").assertIsDisplayed()
        assertTrue(slotCalls >= 1)
        assertTrue(slotAmount == 149)
    }

    @Test
    fun boost_tierSelected_bashkir_showsBashkirPayLabel() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                Boost(selectedTier = "quick")
            }
        }
        // plan.price=49, tier выбран, BA → "49 ₽ түләү"
        composeRule.onNodeWithText("49 ₽ түләү").assertIsDisplayed()
    }
}
