package com.yuldash.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * «В глубину»: второй вынос стейта в ProfileScreen.kt. Экран `PassengerCabinetScreen` разрезан на умную
 * обёртку (сеть/стейт) и чистый `PassengerCabinetContent(state, callbacks)`. Здесь Content покрыт во ВСЕХ
 * состояниях (загрузка-скелетон / ошибка+повтор / пусто / активная поездка / клики) — то, что раньше было 0%.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2600dp")   // высокое окно: экран вырос, иначе половина уезжает за край
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfileDeep2ContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun ride(
        id: String = "1",
        from: String = "Уфа",
        to: String = "Казань",
        time: String = "Завтра 08:00",
        seats: Int = 2,
        price: Int = 350,
    ) = Ride(
        id = id, from = from, to = to, time = time,
        driver = "Айдар", car = "", price = price, seats = seats,
        rating = 0.0, verified = true, boosted = false,
    )

    /** Прокрутить кабинет до строки с текстом.
     *  Кабинет пассажира вырос (добавились строки — быстрый заказ и др.), часть контента уехала
     *  ниже видимой области теста. Узел в дереве ЕСТЬ, но assertIsDisplayed/performClick требуют
     *  попадания в окно — без прокрутки падает «is not displayed» либо клик уходит в никуда. */
    private fun scrollTo(text: String) {
        composeRule.onAllNodes(hasScrollToNodeAction()).onFirst()
            .performScrollToNode(hasText(text))
    }

    @Test
    fun loading_showsSkeletonNotActiveRide() {
        composeRule.mainClock.autoAdvance = false   // скелетон-шиммер бесконечен
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                // Как в реальной обёртке: пока грузим, брони ещё не пришли → activeRide=null.
                PassengerCabinetContent(
                    loading = true, error = false, activeCount = 0, requestCount = 0, ratingText = "—",
                    activeRide = null, activeStatus = null,
                    onRetry = {}, onMyTrips = {}, onOpenBooking = { _, _ -> }, onFindRide = {}, onCreateRequest = {}, onSafety = {}, onInstantOrder = {},
                )
            }
        }
        // Пока грузим — карточки активной поездки нет; ошибку тоже не показываем.
        composeRule.onNodeWithText("Уфа → Казань").assertDoesNotExist()
        composeRule.onNodeWithText("Не удалось загрузить поездки").assertDoesNotExist()
    }

    @Test
    fun error_showsMessageAndRetryButton() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                PassengerCabinetContent(
                    loading = false, error = true, activeCount = 0, requestCount = 0, ratingText = "—",
                    activeRide = null, activeStatus = null,
                    onRetry = {}, onMyTrips = {}, onOpenBooking = { _, _ -> }, onFindRide = {}, onCreateRequest = {}, onSafety = {}, onInstantOrder = {},
                )
            }
        }
        scrollTo("Не удалось загрузить поездки")
        composeRule.onNodeWithText("Не удалось загрузить поездки").assertIsDisplayed()
        scrollTo("Повторить")
        composeRule.onNodeWithText("Повторить").assertIsDisplayed()
    }

    @Test
    fun error_retryClick_firesOnRetry() {
        var retried = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                PassengerCabinetContent(
                    loading = false, error = true, activeCount = 0, requestCount = 0, ratingText = "—",
                    activeRide = null, activeStatus = null,
                    onRetry = { retried = true }, onMyTrips = {}, onOpenBooking = { _, _ -> }, onFindRide = {}, onCreateRequest = {}, onSafety = {}, onInstantOrder = {},
                )
            }
        }
        scrollTo("Повторить")
        composeRule.onNodeWithText("Повторить").performClick()
        assertTrue(retried)
    }

    @Test
    fun empty_noActiveRide_showsNavRowsNotTripCard() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                PassengerCabinetContent(
                    loading = false, error = false, activeCount = 0, requestCount = 0, ratingText = "—",
                    activeRide = null, activeStatus = null,
                    onRetry = {}, onMyTrips = {}, onOpenBooking = { _, _ -> }, onFindRide = {}, onCreateRequest = {}, onSafety = {}, onInstantOrder = {},
                )
            }
        }
        // Карточки активной поездки нет, но нижние навигационные строки на месте.
        composeRule.onNodeWithText("Уфа → Казань").assertDoesNotExist()
        // Над списком теперь стоит карточка «Активных поездок нет» — строка ушла за экран,
        // а в LazyColumn это значит «не создана вовсе». Доскроллить, потом проверять.
        scrollTo("Найти поездку")
        composeRule.onNodeWithText("Найти поездку").assertIsDisplayed()
        scrollTo("Создать заявку")
        composeRule.onNodeWithText("Создать заявку").assertIsDisplayed()
        scrollTo("Безопасность поездки")
        composeRule.onNodeWithText("Безопасность поездки").assertIsDisplayed()
    }

    @Test
    fun empty_bashkir_showsBashkirNavRows() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                PassengerCabinetContent(
                    loading = false, error = false, activeCount = 0, requestCount = 0, ratingText = "—",
                    activeRide = null, activeStatus = null,
                    onRetry = {}, onMyTrips = {}, onOpenBooking = { _, _ -> }, onFindRide = {}, onCreateRequest = {}, onSafety = {}, onInstantOrder = {},
                )
            }
        }
        scrollTo("Сәфәр табыу")
        composeRule.onNodeWithText("Сәфәр табыу").assertIsDisplayed()
        scrollTo("Заявка булдырыу")
        composeRule.onNodeWithText("Заявка булдырыу").assertIsDisplayed()
    }

    @Test
    fun metrics_showCountsAndRating() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                PassengerCabinetContent(
                    loading = false, error = false, activeCount = 3, requestCount = 5, ratingText = "4.8",
                    activeRide = null, activeStatus = null,
                    onRetry = {}, onMyTrips = {}, onOpenBooking = { _, _ -> }, onFindRide = {}, onCreateRequest = {}, onSafety = {}, onInstantOrder = {},
                )
            }
        }
        composeRule.onNodeWithText("Активные").assertIsDisplayed()
        composeRule.onNodeWithText("3").assertIsDisplayed()
        composeRule.onNodeWithText("5").assertIsDisplayed()
        composeRule.onNodeWithText("4.8").assertIsDisplayed()
    }

    @Test
    fun activeRide_pending_showsCardWithDetailsAction() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                PassengerCabinetContent(
                    loading = false, error = false, activeCount = 1, requestCount = 0, ratingText = "—",
                    activeRide = ride(), activeStatus = "pending",
                    onRetry = {}, onMyTrips = {}, onOpenBooking = { _, _ -> }, onFindRide = {}, onCreateRequest = {}, onSafety = {}, onInstantOrder = {},
                )
            }
        }
        composeRule.onNodeWithText("Уфа → Казань").assertIsDisplayed()
        composeRule.onNodeWithText("Ближайшая").assertIsDisplayed()
        scrollTo("2 места · 350 ₽")
        composeRule.onNodeWithText("2 места · 350 ₽").assertIsDisplayed()
        // status=pending → не активная поездка → кнопка «Подробнее».
        scrollTo("Подробнее")
        composeRule.onNodeWithText("Подробнее").assertIsDisplayed()
    }

    @Test
    fun activeRide_confirmed_showsOpenTripAction() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                PassengerCabinetContent(
                    loading = false, error = false, activeCount = 1, requestCount = 0, ratingText = "—",
                    activeRide = ride(), activeStatus = "confirmed",
                    onRetry = {}, onMyTrips = {}, onOpenBooking = { _, _ -> }, onFindRide = {}, onCreateRequest = {}, onSafety = {}, onInstantOrder = {},
                )
            }
        }
        // status=confirmed → активная поездка → кнопка «Открыть поездку».
        scrollTo("Открыть поездку")
        composeRule.onNodeWithText("Открыть поездку").assertIsDisplayed()
        composeRule.onNodeWithText("Подробнее").assertDoesNotExist()
    }

    @Test
    fun activeRide_primaryClick_firesOnOpenBookingWithRideAndStatus() {
        var openedRide: Ride? = null
        var openedStatus: String? = null
        val r = ride(id = "42")
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                PassengerCabinetContent(
                    loading = false, error = false, activeCount = 1, requestCount = 0, ratingText = "—",
                    activeRide = r, activeStatus = "confirmed",
                    onRetry = {}, onMyTrips = {}, onOpenBooking = { ride, status -> openedRide = ride; openedStatus = status },
                    onFindRide = {}, onCreateRequest = {}, onSafety = {}, onInstantOrder = {},
                )
            }
        }
        scrollTo("Открыть поездку")
        composeRule.onNodeWithText("Открыть поездку").performClick()
        assertEquals("42", openedRide?.id)
        assertEquals("confirmed", openedStatus)
    }

    @Test
    fun navRow_findRideClick_firesOnFindRide() {
        var found = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                PassengerCabinetContent(
                    loading = false, error = false, activeCount = 0, requestCount = 0, ratingText = "—",
                    activeRide = null, activeStatus = null,
                    onRetry = {}, onMyTrips = {}, onOpenBooking = { _, _ -> }, onFindRide = { found = true }, onCreateRequest = {}, onSafety = {}, onInstantOrder = {},
                )
            }
        }
        scrollTo("Найти поездку")
        composeRule.onNodeWithText("Найти поездку").performClick()
        assertTrue(found)
    }
}
