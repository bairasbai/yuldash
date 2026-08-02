package com.yuldash.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.EventSeat
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.yuldash.app.data.DriverBookingDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * «В глубину» (раунд 3) в ProfileScreen.kt. Экран `DriverCabinetScreen` (кабинет ВОДИТЕЛЯ) разрезан на
 * умную обёртку (сеть/стейт: online-переключение, оценка пассажира) и чистый
 * `DriverCabinetContent(state, callbacks)`. Здесь Content покрыт: тумблер «на линии», метрики,
 * пусто-заглушка / список маршрутов, блок оценки пассажиров, нижние действия, двуязычие (RU/BA).
 * Раньше весь этот блок был 0% (жил внутри @Composable с LaunchedEffect/ApiClient).
 *
 * Заголовок/аннотации класса — как в ProfileDeep2ContentTest. Тексты взяты ДОСЛОВНО из ProfileScreen.kt.
 * Тумблер настроек → onNode(isToggleable()).performClick(). Кнопки ниже сгиба → скролл по
 * hasScrollToNodeAction (внутри LazyColumn несколько прокручиваемых узлов → onFirst).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfileDeep3ContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun ride(
        id: String = "1",
        from: String = "Уфа",
        to: String = "Сибай",
        time: String = "Завтра 08:00",
        seats: Int = 2,
        price: Int = 350,
    ) = Ride(
        id = id, from = from, to = to, time = time,
        driver = "Айдар", car = "", price = price, seats = seats,
        rating = 0.0, verified = true, boosted = false,
    )

    private fun booking(
        bookingId: Int = 10,
        passengerName: String = "Гульназ",
        passengerRating: Double? = null,
        route: String = "Уфа → Сибай",
        status: String = "done",
        myStars: Int = 0,
    ) = DriverBookingDto(
        bookingId = bookingId,
        passengerName = passengerName,
        passengerRating = passengerRating,
        route = route,
        status = status,
        myStars = myStars,
    )

    // Хелпер: чистый Content с дефолтами, оборачиваем в провайдер языка.
    private fun content(
        online: Boolean = false,
        driverRides: List<Ride> = emptyList(),
        driverBookings: List<DriverBookingDto> = emptyList(),
        ratingText: String = "—",
        onToggleOnline: (Boolean) -> Unit = {},
        onRate: (Int, Int, (Boolean) -> Unit) -> Unit = { _, _, done -> done(true) },
        onCreateRide: () -> Unit = {},
        onVerifyDriver: () -> Unit = {},
        onBoost: () -> Unit = {},
        onRequestsFeed: () -> Unit = {},
        language: AppLanguage = AppLanguage.Ru,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides language) {
                DriverCabinetContent(
                    online = online,
                    driverRides = driverRides,
                    driverBookings = driverBookings,
                    ratingText = ratingText,
                    onToggleOnline = onToggleOnline,
                    onRate = onRate,
                    onCreateRide = onCreateRide,
                    onVerifyDriver = onVerifyDriver,
                    onBoost = onBoost,
                    onRequestsFeed = onRequestsFeed,
                )
            }
        }
    }

    // --- Шапка + двуязычие ---

    /** Прокрутить кабинет до строки с текстом.
     *  Кабинет водителя вырос (появились новые строки), и часть контента уехала ниже видимой
     *  области теста. Узел в дереве ЕСТЬ, но assertIsDisplayed проверяет попадание в окно —
     *  поэтому без прокрутки падает «is not displayed». В этом же файле такой приём уже
     *  используется в проходящих тестах. */
    private fun scrollTo(text: String) {
        composeRule.onAllNodes(hasScrollToNodeAction()).onFirst()
            .performScrollToNode(hasText(text))
    }

    @Test
    fun header_russian_showsTitleAndSubtitle() {
        content()
        composeRule.onNodeWithText("Маршруты и проверка").assertIsDisplayed()
        composeRule.onNodeWithText("Публикуйте поездки, проходите проверку и поднимайте маршрут выше.").assertIsDisplayed()
    }

    @Test
    fun header_bashkir_showsBashkirTitle() {
        content(language = AppLanguage.Ba)
        composeRule.onNodeWithText("Маршруттар һәм тикшереү").assertIsDisplayed()
        composeRule.onNodeWithText("Мин эштә").assertIsDisplayed()   // подпись тумблера на башкирском
    }

    // --- Тумблер «Я на линии» ---

    @Test
    fun onlineSwitch_showsRowRussian() {
        content(online = false)
        composeRule.onNodeWithText("Я на линии").assertIsDisplayed()
        composeRule.onNodeWithText("Пассажиры видят, что вы готовы везти сейчас").assertIsDisplayed()
    }

    @Test
    fun onlineSwitch_click_firesOnToggleWithNewValue() {
        // online=false → клик по тумблеру должен позвать onToggleOnline(true).
        var toggledTo: Boolean? = null
        content(online = false, onToggleOnline = { toggledTo = it })
        // В кабинете водителя теперь ДВА тумблера: «на линии» и «женщина за рулём» (F9),
        // поэтому onNode(isToggleable()) падал с «Expected exactly 1 node but found 2».
        // Берём первый по порядку — это «на линии» (он выше по вёрстке, y≈112 против y≈304).
        composeRule.onAllNodes(isToggleable()).onFirst().performClick()
        assertEquals(true, toggledTo)
    }

    @Test
    fun onlineSwitch_whenOn_click_firesOnToggleFalse() {
        var toggledTo: Boolean? = null
        content(online = true, onToggleOnline = { toggledTo = it })
        // В кабинете водителя теперь ДВА тумблера: «на линии» и «женщина за рулём» (F9),
        // поэтому onNode(isToggleable()) падал с «Expected exactly 1 node but found 2».
        // Берём первый по порядку — это «на линии» (он выше по вёрстке, y≈112 против y≈304).
        composeRule.onAllNodes(isToggleable()).onFirst().performClick()
        assertEquals(false, toggledTo)
    }

    // --- Метрики ---

    @Test
    fun metrics_showRoutesFreeSeatsAndRating() {
        // 1 маршрут, 2 свободных места, рейтинг «4.9».
        content(driverRides = listOf(ride(seats = 2)), ratingText = "4.9")
        composeRule.onNodeWithText("Мои маршруты").assertIsDisplayed()
        composeRule.onNodeWithText("Свободно").assertIsDisplayed()
        composeRule.onNodeWithText("Рейтинг").assertIsDisplayed()
        composeRule.onNodeWithText("4.9").assertIsDisplayed()
    }

    // --- Пусто: нет маршрутов ---

    @Test
    fun empty_noRides_showsEmptyStateWithPublishAction() {
        content(driverRides = emptyList())
        scrollTo("Ваших маршрутов пока нет")
        composeRule.onNodeWithText("Ваших маршрутов пока нет").assertIsDisplayed()
        scrollTo("Опубликовать маршрут")
        composeRule.onNodeWithText("Опубликовать маршрут").assertIsDisplayed()
    }

    @Test
    fun empty_bashkir_showsBashkirEmptyState() {
        content(driverRides = emptyList(), language = AppLanguage.Ba)
        scrollTo("Һеҙҙең маршруттар әлегә юҡ")
        composeRule.onNodeWithText("Һеҙҙең маршруттар әлегә юҡ").assertIsDisplayed()
    }

    @Test
    fun empty_publishClick_firesOnCreateRide() {
        var created = false
        content(driverRides = emptyList(), onCreateRide = { created = true })
        scrollTo("Опубликовать маршрут")
        composeRule.onNodeWithText("Опубликовать маршрут").performClick()
        assertTrue(created)
    }

    // --- Список маршрутов ---

    @Test
    fun list_showsRideCardWithRouteAndStatus() {
        content(driverRides = listOf(ride()))
        scrollTo("Уфа → Сибай")
        composeRule.onNodeWithText("Уфа → Сибай").assertIsDisplayed()
        composeRule.onNodeWithText("Опубликована").assertIsDisplayed()
        composeRule.onNodeWithText("2 места · 350 ₽").assertIsDisplayed()
    }

    @Test
    fun list_boostClick_firesOnBoost() {
        var boosted = false
        content(driverRides = listOf(ride()), onBoost = { boosted = true })
        scrollTo("Поднять")
        composeRule.onNodeWithText("Поднять").performClick()
        assertTrue(boosted)
    }

    @Test
    fun list_bashkir_showsBashkirStatus() {
        content(driverRides = listOf(ride()), language = AppLanguage.Ba)
        scrollTo("Баҫтырылды")
        composeRule.onNodeWithText("Баҫтырылды").assertIsDisplayed()
    }

    // --- Блок «оцените пассажиров» ---

    @Test
    fun bookings_showsHeaderAndPassengerName() {
        content(driverBookings = listOf(booking(passengerName = "Гульназ")))
        composeRule.onAllNodes(hasScrollToNodeAction()).onFirst()
            .performScrollToNode(hasText("Пассажиры — оцените после поездки"))
        composeRule.onNodeWithText("Пассажиры — оцените после поездки").assertIsDisplayed()
        composeRule.onNodeWithText("Гульназ").assertIsDisplayed()
    }

    @Test
    fun bookings_starClickAlone_doesNotSendRating() {
        // Промах пальцем по звезде НЕ должен уходить на сервер: раньше единица улетала
        // мгновенно и вернуть её было нечем. Оценка отправляется только по кнопке.
        var fired = false
        content(
            driverBookings = listOf(booking(bookingId = 77)),
            onRate = { _, _, done -> fired = true; done(true) },
        )
        composeRule.onAllNodes(hasScrollToNodeAction()).onFirst()
            .performScrollToNode(hasText("Пассажиры — оцените после поездки"))
        composeRule.onNodeWithContentDescription("4 звезды").performClick()
        assertEquals(false, fired)
        composeRule.onNodeWithText("Выбрано 4 звезды — подтвердите").assertIsDisplayed()
    }

    @Test
    fun bookings_confirmButton_firesOnRateWithBookingIdAndStars() {
        var ratedBooking: Int? = null
        var ratedStars: Int? = null
        content(
            driverBookings = listOf(booking(bookingId = 77)),
            onRate = { id, n, done -> ratedBooking = id; ratedStars = n; done(true) },
        )
        composeRule.onAllNodes(hasScrollToNodeAction()).onFirst()
            .performScrollToNode(hasText("Пассажиры — оцените после поездки"))
        // Звёзды помечены contentDescription "1".."5" (не text) → четвёртая = "4".
        composeRule.onNodeWithContentDescription("4 звезды").performClick()
        composeRule.onNodeWithText("Отправить оценку").performClick()
        assertEquals(77, ratedBooking)
        assertEquals(4, ratedStars)
    }

    @Test
    fun bookings_notFinishedTrip_notOfferedForRating() {
        // Поездка ещё не состоялась — сервер оценку не примет (409). Значит и звёзд быть не должно:
        // раньше водитель тапал и получал «Не получилось оценить» без объяснения.
        content(driverBookings = listOf(booking(status = "confirmed")))
        composeRule.onNodeWithText("Пассажиры — оцените после поездки").assertDoesNotExist()
        composeRule.onAllNodes(hasScrollToNodeAction()).onFirst()
            .performScrollToNode(hasText("Едут с тобой"))
        composeRule.onNodeWithText("Едут с тобой").assertIsDisplayed()
    }

    @Test
    fun bookings_alreadyRated_showsMyStarsAndChangeButton() {
        // После перезагрузки экрана оценка не исчезает: сервер помнит её (my_stars).
        content(driverBookings = listOf(booking(myStars = 4)))
        composeRule.onAllNodes(hasScrollToNodeAction()).onFirst()
            .performScrollToNode(hasText("Вы поставили 4 звезды"))
        composeRule.onNodeWithText("Вы поставили 4 звезды").assertIsDisplayed()
        composeRule.onNodeWithText("Отправить оценку").assertDoesNotExist()
        composeRule.onNodeWithText("Изменить").assertIsDisplayed()
    }

    @Test
    fun bookings_empty_noRatePrompt() {
        // Броней нет → заголовок блока оценки не рисуем.
        content(driverBookings = emptyList())
        composeRule.onNodeWithText("Пассажиры — оцените после поездки").assertDoesNotExist()
    }

    // --- Нижние действия (ниже сгиба → скролл) ---

    @Test
    fun bottomActions_requestsFeedClick_firesCallback() {
        var opened = false
        content(driverRides = emptyList(), onRequestsFeed = { opened = true })
        composeRule.onAllNodes(hasScrollToNodeAction()).onFirst()
            .performScrollToNode(hasText("Заявки пассажиров"))
        composeRule.onNodeWithText("Заявки пассажиров").performClick()
        assertTrue(opened)
    }

    @Test
    fun bottomActions_verifyDriverClick_firesCallback() {
        var verified = false
        content(driverRides = emptyList(), onVerifyDriver = { verified = true })
        composeRule.onAllNodes(hasScrollToNodeAction()).onFirst()
            .performScrollToNode(hasText("Права, машина, фото и госномер"))
        composeRule.onNodeWithText("Права, машина, фото и госномер").performClick()
        assertTrue(verified)
    }

    // --- Мелкие чистые компоненты профиля (готовые строки, без сети) ---

    @Test
    fun infoCard_showsTitleAndText() {
        composeRule.setContent {
            InfoCard(title = "Проверка водителя", text = "Права и авто", icon = Icons.Default.DirectionsCar)
        }
        composeRule.onNodeWithText("Проверка водителя").assertIsDisplayed()
        composeRule.onNodeWithText("Права и авто").assertIsDisplayed()
    }

    @Test
    fun infoCard_click_firesCallback() {
        var clicked = false
        composeRule.setContent {
            InfoCard(title = "Проверка", text = "Инфо", icon = Icons.Default.DirectionsCar, onClick = { clicked = true })
        }
        composeRule.onNodeWithText("Проверка").performClick()
        assertTrue(clicked)
    }

    @Test
    fun emptyStateCard_withAction_showsAndFiresCallback() {
        var acted = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                EmptyStateCard(
                    title = "Пока пусто",
                    text = "Ничего не найдено",
                    icon = Icons.Default.EventSeat,
                    action = "Обновить",
                    onAction = { acted = true },
                )
            }
        }
        composeRule.onNodeWithText("Пока пусто").assertIsDisplayed()
        composeRule.onNodeWithText("Ничего не найдено").assertIsDisplayed()
        composeRule.onNodeWithText("Обновить").performClick()
        assertTrue(acted)
    }

    @Test
    fun tripInfoRow_showsLabelAndValue() {
        composeRule.setContent {
            TripInfoRow(icon = Icons.Default.Schedule, label = "Время", value = "08:00")
        }
        composeRule.onNodeWithText("Время").assertIsDisplayed()
        composeRule.onNodeWithText("08:00").assertIsDisplayed()
    }

    @Test
    fun detailMeta_showsText() {
        composeRule.setContent {
            DetailMeta(icon = Icons.Default.Schedule, text = "Завтра 08:00")
        }
        composeRule.onNodeWithText("Завтра 08:00").assertIsDisplayed()
    }
}
