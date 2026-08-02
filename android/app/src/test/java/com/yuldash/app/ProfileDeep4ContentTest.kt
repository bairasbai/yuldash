package com.yuldash.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.EventSeat
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
 * Раунд 4 добора покрытия ProfileScreen.kt. Берём то, что раньше было 0%:
 *  - `DriverCabinetContent` — самый большой невыпокрытый умный-Content (тумблер «на линии»,
 *    метрики, пусто-заглушка / список маршрутов, звёзды-оценка пассажиров, нижние строки);
 *  - карточки-строки: `InfoCard`, `EmptyStateCard`, `DetailMeta`, `TripInfoRow`;
 *  - `PartnerAdCard` и `InlinePartnerAdCard` (через готовый demoPartnerAds — валидный инстанс,
 *    чтобы не тащить 30+ полей руками): erid-подпись, кнопки, admin-детали, показ (impression).
 *
 * ГЛАВНЫЙ УРОК: вынесенный Content — плоский `LazyColumn` без своей прокрутки в тесте, поэтому
 * ставим высокое окно `w411dp-h2600dp` — весь экран в кадре, скролл не нужен, работаем напрямую.
 * Switch → `onNode(isToggleable())` (клик по тумблеру, не по тексту). Звёзды → contentDescription.
 * Строки — дословно из ProfileScreen.kt под нужный язык, RU/BA не мешаем.
 * Дубли с ProfileScreenContentTest / ProfileMoreContentTest / ProfileDeep{,2}ContentTest — исключены.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfileDeep4ContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun ride(
        id: String = "1",
        from: String = "Сибай",
        to: String = "Уфа",
        time: String = "Сегодня 14:30",
        seats: Int = 3,
        price: Int = 500,
    ) = Ride(
        id = id, from = from, to = to, time = time,
        driver = "Марат", car = "", price = price, seats = seats,
        rating = 0.0, verified = true, boosted = false,
    )

    private fun booking(
        bookingId: Int = 10,
        passengerName: String = "Гульнара",
        passengerRating: Double? = null,
        route: String = "Сибай → Уфа",
        status: String = "confirmed",
        myStars: Int = 0,
    ) = DriverBookingDto(
        bookingId = bookingId,
        passengerName = passengerName,
        passengerRating = passengerRating,
        route = route,
        status = status,
        myStars = myStars,
    )

    // Дефолтные пустые колбэки — переопределяем только нужный сценарию.
    private fun driverContent(
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

    // --- DriverCabinetContent: пусто (нет маршрутов) → заглушка + метрики + нижние строки. ---

    @Test
    fun driver_empty_showsEmptyStateAndMetrics() {
        driverContent(driverRides = emptyList(), ratingText = "—")
        composeRule.onNodeWithText("Маршруты и проверка").assertIsDisplayed()
        // Заглушка «маршрутов нет» + кнопка публикации.
        composeRule.onNodeWithText("Ваших маршрутов пока нет").assertIsDisplayed()
        composeRule.onNodeWithText("Опубликовать маршрут").assertIsDisplayed()
        // Метрики: заголовки видны, счётчики 0 (нет маршрутов).
        composeRule.onNodeWithText("Мои маршруты").assertIsDisplayed()
        composeRule.onNodeWithText("Свободно").assertIsDisplayed()
    }

    @Test
    fun driver_empty_bashkir_showsBashkirEmptyState() {
        driverContent(driverRides = emptyList(), language = AppLanguage.Ba)
        composeRule.onNodeWithText("Маршруттар һәм тикшереү").assertIsDisplayed()
        composeRule.onNodeWithText("Һеҙҙең маршруттар әлегә юҡ").assertIsDisplayed()
        composeRule.onNodeWithText("Маршрут баҫтырыу").assertIsDisplayed()
    }

    @Test
    fun driver_empty_publishClick_firesOnCreateRide() {
        var created = false
        driverContent(driverRides = emptyList(), onCreateRide = { created = true })
        composeRule.onNodeWithText("Опубликовать маршрут").performClick()
        assertTrue(created)
    }

    // --- Тумблер «Я на линии»: состояние + переключение через колбэк (клик по Switch). ---

    @Test
    fun driver_onlineSwitch_showsTitleAndSubtitle() {
        driverContent(online = false)
        composeRule.onNodeWithText("Я на линии").assertIsDisplayed()
        composeRule.onNodeWithText("Пассажиры видят, что вы готовы везти сейчас").assertIsDisplayed()
    }

    @Test
    fun driver_offlineSwitch_toggleClick_firesWithTrue() {
        var newValue: Boolean? = null
        driverContent(online = false, onToggleOnline = { newValue = it })
        // Тумблер выключен → клик просит включить (true). Кликаем по самому Switch, не по тексту.
        // В кабинете водителя теперь ДВА тумблера: «на линии» и «женщина за рулём» (F9),
        // поэтому onNode(isToggleable()) падал с «Expected exactly 1 node but found 2».
        // Берём первый по порядку — это «на линии» (он выше по вёрстке, y≈112 против y≈304).
        composeRule.onAllNodes(isToggleable()).onFirst().performClick()
        assertEquals(true, newValue)
    }

    @Test
    fun driver_onlineSwitch_toggleClick_firesWithFalse() {
        var newValue: Boolean? = null
        driverContent(online = true, onToggleOnline = { newValue = it })
        // Тумблер включён → клик просит выключить (false).
        // В кабинете водителя теперь ДВА тумблера: «на линии» и «женщина за рулём» (F9),
        // поэтому onNode(isToggleable()) падал с «Expected exactly 1 node but found 2».
        // Берём первый по порядку — это «на линии» (он выше по вёрстке, y≈112 против y≈304).
        composeRule.onAllNodes(isToggleable()).onFirst().performClick()
        assertEquals(false, newValue)
    }

    // --- Список маршрутов: карточка поездки + метрики считают места; заглушки нет. ---

    @Test
    fun driver_withRides_showsTripCardNotEmptyState() {
        driverContent(driverRides = listOf(ride()), ratingText = "4.9")
        // Есть маршрут → карточка поездки, а заглушки «нет маршрутов» быть не должно.
        composeRule.onNodeWithText("Ваших маршрутов пока нет").assertDoesNotExist()
        composeRule.onNodeWithText("Сибай → Уфа").assertIsDisplayed()
        composeRule.onNodeWithText("Опубликована").assertIsDisplayed()
        composeRule.onNodeWithText("4.9").assertIsDisplayed()
    }

    @Test
    fun driver_withRides_boostClick_firesOnBoost() {
        var boosted = false
        driverContent(driverRides = listOf(ride()), onBoost = { boosted = true })
        // «Поднять» — primaryAction карточки опубликованного маршрута.
        composeRule.onNodeWithText("Поднять").performClick()
        assertTrue(boosted)
    }

    // --- Блок «оцените пассажиров»: рисуется только при наличии driverBookings. ---

    @Test
    fun driver_withBookings_showsRateSectionAndPassenger() {
        driverContent(driverBookings = listOf(booking(status = "done")))
        composeRule.onNodeWithText("Пассажиры — оцените после поездки").assertIsDisplayed()
        composeRule.onNodeWithText("Гульнара").assertIsDisplayed()
    }

    @Test
    fun driver_bookingInProgress_goesToRidingSectionNotRating() {
        driverContent(driverBookings = listOf(booking(status = "onboard")))
        composeRule.onNodeWithText("Пассажиры — оцените после поездки").assertDoesNotExist()
        composeRule.onNodeWithText("Едут с тобой").assertIsDisplayed()
        composeRule.onNodeWithText("Гульнара").assertIsDisplayed()
    }

    @Test
    fun driver_noBookings_hidesRateSection() {
        driverContent(driverBookings = emptyList())
        composeRule.onNodeWithText("Пассажиры — оцените после поездки").assertDoesNotExist()
    }

    @Test
    fun driver_ratePassenger_confirmButton_firesOnRateWithBookingAndStars() {
        var ratedBooking: Int? = null
        var ratedStars: Int? = null
        driverContent(
            // Оценивают только завершённую поездку — незавершённую сервер не примет.
            driverBookings = listOf(booking(bookingId = 77, status = "done")),
            onRate = { id, n, done -> ratedBooking = id; ratedStars = n; done(true) },
        )
        // Звёзды — иконки-кнопки с contentDescription "1".."5". Жмём 4-ю, потом подтверждаем.
        composeRule.onNodeWithContentDescription("4 звезды").performClick()
        composeRule.onNodeWithText("Отправить оценку").performClick()
        assertEquals(77, ratedBooking)
        assertEquals(4, ratedStars)
    }

    @Test
    fun driver_ratePassenger_sendFailed_starsRollBack() {
        // Сеть отвалилась → на экране не должна остаться оценка, которой на сервере нет.
        driverContent(
            driverBookings = listOf(booking(bookingId = 78, status = "done")),
            onRate = { _, _, done -> done(false) },
        )
        composeRule.onNodeWithContentDescription("4 звезды").performClick()
        composeRule.onNodeWithText("Отправить оценку").performClick()
        composeRule.onNodeWithText("Выберите оценку").assertIsDisplayed()
        composeRule.onNodeWithText("Вы поставили 4 звезды").assertDoesNotExist()
    }

    // --- Нижние строки водителя: «Заявки пассажиров» → колбэк onRequestsFeed. ---

    @Test
    fun driver_requestsFeedRow_click_firesOnRequestsFeed() {
        var opened = false
        driverContent(onRequestsFeed = { opened = true })
        composeRule.onNodeWithText("Заявки пассажиров").performClick()
        assertTrue(opened)
    }

    @Test
    fun driver_createRideRow_click_firesOnCreateRide() {
        var created = false
        // Пустой список → EmptyStateCard тоже зовёт onCreateRide, но у него текст «Опубликовать маршрут».
        // Здесь есть маршрут → заглушки нет, кликаем именно нижнюю строку «Создать поездку».
        driverContent(driverRides = listOf(ride()), onCreateRide = { created = true })
        composeRule.onNodeWithText("Создать поездку").performClick()
        assertTrue(created)
    }

    // --- InfoCard: иконка + заголовок + текст; клик через bounceClick (когда задан onClick). ---

    @Test
    fun infoCard_showsTitleAndText() {
        composeRule.setContent {
            InfoCard(
                title = "Безопасность",
                text = "SOS и скрытый номер",
                icon = Icons.Default.EventSeat,
            )
        }
        composeRule.onNodeWithText("Безопасность").assertIsDisplayed()
        composeRule.onNodeWithText("SOS и скрытый номер").assertIsDisplayed()
    }

    @Test
    fun infoCard_click_firesCallback() {
        var clicked = false
        composeRule.setContent {
            InfoCard(
                title = "Безопасность",
                text = "SOS и скрытый номер",
                icon = Icons.Default.EventSeat,
                onClick = { clicked = true },
            )
        }
        composeRule.onNodeWithText("Безопасность").performClick()
        assertTrue(clicked)
    }

    // --- EmptyStateCard: заголовок + текст + опциональная кнопка действия (через AppButton). ---

    @Test
    fun emptyStateCard_withAction_showsAllAndClickFires() {
        var acted = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                EmptyStateCard(
                    title = "Пока пусто",
                    text = "Здесь появятся ваши поездки",
                    icon = Icons.Default.DirectionsCar,
                    action = "Повторить",
                    onAction = { acted = true },
                )
            }
        }
        composeRule.onNodeWithText("Пока пусто").assertIsDisplayed()
        composeRule.onNodeWithText("Здесь появятся ваши поездки").assertIsDisplayed()
        composeRule.onNodeWithText("Повторить").performClick()
        assertTrue(acted)
    }

    @Test
    fun emptyStateCard_withoutAction_showsNoButton() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                EmptyStateCard(
                    title = "Пусто",
                    text = "Ничего нет",
                    icon = Icons.Default.DirectionsCar,
                )
            }
        }
        composeRule.onNodeWithText("Пусто").assertIsDisplayed()
        // Кнопки действия нет, когда action/onAction не заданы.
        composeRule.onNodeWithText("Повторить").assertDoesNotExist()
    }

    // --- DetailMeta: иконка + текст (строка метаданных поездки). ---

    @Test
    fun detailMeta_showsText() {
        composeRule.setContent {
            DetailMeta(icon = Icons.Default.Schedule, text = "Завтра 09:00")
        }
        composeRule.onNodeWithText("Завтра 09:00").assertIsDisplayed()
    }

    // --- TripInfoRow: строка «иконка + label + value + стрелка». ---

    @Test
    fun tripInfoRow_showsLabelAndValue() {
        composeRule.setContent {
            TripInfoRow(icon = Icons.Default.Schedule, label = "Время", value = "14:30")
        }
        composeRule.onNodeWithText("Время").assertIsDisplayed()
        composeRule.onNodeWithText("14:30").assertIsDisplayed()
    }

    // --- PartnerAdCard: реклама-карточка. Берём готовый demoPartnerAds (валидный инстанс). ---

    @Test
    fun partnerAdCard_showsEridAndPrimaryButton_firesImpression() {
        val ad = demoPartnerAds.first()
        var impressed: PartnerAd? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                PartnerAdCard(
                    ad = ad,
                    stats = AdStats(),
                    onImpression = { impressed = it },
                    onClick = {},
                )
            }
        }
        // erid-подпись обязательна (маркировка рекламы) + основная кнопка «Открыть».
        composeRule.onNodeWithText("Реклама · erid: ${ad.erid}").assertIsDisplayed()
        composeRule.onNodeWithText("Открыть").assertIsDisplayed()
        // LaunchedEffect(ad.id) → показ засчитан один раз.
        assertEquals(ad.id, impressed?.id)
    }

    @Test
    fun partnerAdCard_adminDetails_showAdvertiserAndStats() {
        val ad = demoPartnerAds.first()
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                PartnerAdCard(
                    ad = ad,
                    stats = AdStats(impressions = 100, clicks = 5),
                    showAdminDetails = true,
                    onImpression = {},
                    onClick = {},
                )
            }
        }
        // В admin-режиме показываем рекламодателя и метрики показов/кликов/CTR.
        composeRule.onNodeWithText("Рекламодатель: ${ad.advertiserName}").assertIsDisplayed()
        composeRule.onNode(hasText("CTR 5%", substring = true)).assertIsDisplayed()
    }

    @Test
    fun partnerAdCard_route_onRouteProvided_firesInAppRoute() {
        val ad = demoPartnerAds.first()
        var routed: PartnerAd? = null
        var clicked = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                PartnerAdCard(
                    ad = ad,
                    stats = AdStats(),
                    onImpression = {},
                    onClick = { clicked = true },
                    onRoute = { routed = it },
                )
            }
        }
        // Вторичная кнопка «Маршрут»: onRoute задан → маршрут строим в приложении (не внешние карты).
        composeRule.onNodeWithText("Маршрут").performClick()
        assertEquals(ad.id, routed?.id)
        assertTrue(clicked)
    }

    // --- InlinePartnerAdCard: компактная реклама-строка (лента). ---

    @Test
    fun inlinePartnerAdCard_showsLabelAndErid_firesImpression() {
        val ad = demoPartnerAds.first()
        var impressed = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                InlinePartnerAdCard(
                    ad = ad,
                    label = "Партнёр в ленте",
                    onImpression = { impressed = true },
                    onClick = {},
                )
            }
        }
        composeRule.onNodeWithText("Партнёр в ленте").assertIsDisplayed()
        composeRule.onNodeWithText("Реклама · erid: ${ad.erid}").assertIsDisplayed()
        assertTrue(impressed)
    }
}
