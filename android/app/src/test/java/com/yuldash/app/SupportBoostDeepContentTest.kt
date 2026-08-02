package com.yuldash.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.yuldash.app.data.BoostPlanDto
import com.yuldash.app.data.BoostResultDto
import com.yuldash.app.data.RideDto
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * «В глубину»: вынос стейта из `BoostScreen` (экран поднятия объявления). Умная обёртка
 * (сеть/стейт/Toast/Intent) отделена от чистого `BoostContent(state, callbacks)`. Здесь Content
 * покрыт во ВСЕХ состояниях (загрузка / ошибка+повтор / пусто / список + выбор + оплата) — то,
 * что раньше было 0%. `BoostResultCard` (QR/буфер обмена) остался в обёртке и приходит слотом
 * `resultSlot` → в тестах передаём пустой слот `{}`.
 *
 * Заголовок класса — как в `AdminReviewsContentTest` / `SupportBoostContentTest`. Карточки Boost
 * анимируются (`animateColorAsState` рамки + `bounceClick`), поэтому в тестах со списком первой
 * строкой гасим авто-часы: `composeRule.mainClock.autoAdvance = false`, иначе бесконечный idle-sync.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SupportBoostDeepContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun ride(
        id: Int = 1,
        fromCity: String = "Уфа",
        toCity: String = "Казань",
        seatsLeft: Int = 3,
        price: Int = 500,
        boosted: Boolean = false,
    ) = RideDto(
        id = id,
        fromCity = fromCity,
        toCity = toCity,
        departAt = "2026-07-03T10:00",
        seatsTotal = 4,
        seatsLeft = seatsLeft,
        price = price,
        category = "people",
        driverName = "Азат",
        driverRating = 4.9,
        driverVerified = true,
        driverCar = "Kia Rio",
        boosted = boosted,
    )

    private fun plan(tier: String = "quick", title: String = "Быстрый подъём", price: Int = 49, hours: Int = 2) =
        BoostPlanDto(tier = tier, title = title, price = price, hours = hours)

    // Полный набор параметров Content — тесты меняют только нужные поля (state hoisting наглядно).
    @androidx.compose.runtime.Composable
    private fun Content(
        loading: Boolean = false,
        loadError: Boolean = false,
        rides: List<RideDto> = listOf(ride()),
        plans: List<BoostPlanDto> = listOf(plan()),
        selectedRideId: Int? = 1,
        selectedTier: String? = null,
        submitting: Boolean = false,
        error: String? = null,
        credits: Int = 0,
        result: BoostResultDto? = null,
        onRetry: () -> Unit = {},
        onEmptyAction: () -> Unit = {},
        onSelectRide: (Int) -> Unit = {},
        onSelectTier: (String) -> Unit = {},
        onBoostFree: () -> Unit = {},
        onPay: () -> Unit = {},
    ) = BoostContent(
        loading = loading,
        loadError = loadError,
        rides = rides,
        plans = plans,
        selectedRideId = selectedRideId,
        selectedTier = selectedTier,
        submitting = submitting,
        error = error,
        credits = credits,
        result = result,
        onRetry = onRetry,
        onEmptyAction = onEmptyAction,
        onSelectRide = onSelectRide,
        onSelectTier = onSelectTier,
        onBoostFree = onBoostFree,
        onPay = onPay,
        resultSlot = {},
    )

    // --- Загрузка: только спиннер, списка нет ---

    @Test
    fun loading_showsSpinnerNotList() {
        composeRule.mainClock.autoAdvance = false   // бесконечный спиннер
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                Content(loading = true)
            }
        }
        composeRule.onNodeWithText("Какую поездку поднять").assertDoesNotExist()
    }

    // --- Ошибка загрузки: сообщение + кнопка «Повторить» → onRetry ---

    @Test
    fun loadError_showsMessageAndRetry() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                Content(loadError = true, rides = emptyList())
            }
        }
        composeRule.onNodeWithText("Не удалось загрузить").assertIsDisplayed()
        composeRule.onNodeWithText("Повторить").assertIsDisplayed()
    }

    @Test
    fun loadError_retryClick_firesOnRetry() {
        var retried = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                Content(loadError = true, rides = emptyList(), onRetry = { retried = true })
            }
        }
        composeRule.onNodeWithText("Повторить").performClick()
        assertTrue(retried)
    }

    // --- Пусто: нет активных поездок + кнопка «Понятно» → onEmptyAction ---

    @Test
    fun empty_showsFriendlyPlaceholder() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                Content(rides = emptyList())
            }
        }
        composeRule.onNodeWithText("Нет активных поездок").assertIsDisplayed()
        composeRule.onNodeWithText("Понятно").assertIsDisplayed()
    }

    @Test
    fun empty_bashkir_showsBashkirPlaceholder() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                Content(rides = emptyList())
            }
        }
        composeRule.onNodeWithText("Әүҙем сәфәрҙәр юҡ").assertIsDisplayed()
    }

    @Test
    fun empty_action_firesOnEmptyAction() {
        var acted = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                Content(rides = emptyList(), onEmptyAction = { acted = true })
            }
        }
        composeRule.onNodeWithText("Понятно").performClick()
        assertTrue(acted)
    }

    // --- Список: заголовок, строка поездки, карточка тарифа ---

    @Test
    fun list_showsHeaderRideAndPlan() {
        composeRule.mainClock.autoAdvance = false   // карточки анимируются
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                Content(
                    rides = listOf(ride(fromCity = "Уфа", toCity = "Казань", seatsLeft = 3, price = 500)),
                    plans = listOf(plan(tier = "quick", title = "Быстрый подъём", price = 49, hours = 2)),
                )
            }
        }
        composeRule.onNodeWithText("Какую поездку поднять").assertIsDisplayed()
        composeRule.onNodeWithText("Уфа → Казань").assertIsDisplayed()
        composeRule.onNodeWithText("3 места · 500 ₽").assertIsDisplayed()
        composeRule.onNodeWithText("Быстрый подъём").assertIsDisplayed()
        composeRule.onNodeWithText("49 ₽").assertIsDisplayed()
    }

    // --- Оплата: без тарифа кнопка «Выбери тариф», с тарифом «Оплатить N ₽» ---

    @Test
    fun list_noTier_showsChooseTierButton() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                Content(selectedTier = null)
            }
        }
        composeRule.onNodeWithText("Выбери тариф").assertIsDisplayed()
    }

    @Test
    fun list_tierSelected_payClick_firesOnPay() {
        composeRule.mainClock.autoAdvance = false
        var paid = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                Content(
                    plans = listOf(plan(tier = "quick", title = "Быстрый подъём", price = 49, hours = 2)),
                    selectedTier = "quick",
                    onPay = { paid = true },
                )
            }
        }
        assertFalse(paid)
        composeRule.onNodeWithText("Оплатить 49 ₽").performClick()
        assertTrue(paid)
    }

    // --- Выбор поездки: тап по строке → onSelectRide(id) ---

    @Test
    fun list_selectRide_firesOnSelectRideWithId() {
        composeRule.mainClock.autoAdvance = false
        var picked: Int? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                Content(
                    rides = listOf(ride(id = 42, fromCity = "Сибай", toCity = "Уфа")),
                    onSelectRide = { picked = it },
                )
            }
        }
        composeRule.onNodeWithText("Сибай → Уфа").performClick()
        assertTrue(picked == 42)
    }

    // --- Бонусы: credits>0 → кнопка бесплатного поднятия, клик → onBoostFree ---

    @Test
    fun credits_freeBoostButton_shownAndClickable() {
        composeRule.mainClock.autoAdvance = false
        var free = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                Content(credits = 2, onBoostFree = { free = true })
            }
        }
        composeRule.onNodeWithText("Поднять бесплатно (2 бонус.)").assertIsDisplayed()
        composeRule.onNodeWithText("Поднять бесплатно (2 бонус.)").performClick()
        assertTrue(free)
    }

    @Test
    fun noCredits_freeBoostButtonHidden() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                Content(credits = 0)
            }
        }
        composeRule.onNodeWithText("Поднять бесплатно (0 бонус.)").assertDoesNotExist()
    }
}
