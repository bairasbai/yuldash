package com.yuldash.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onAllNodesWithText
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
 * Волна 1 (только попутки), кабинеты:
 *  - водитель из «Едут с тобой» открывает экран поездки кнопкой «Открыть поездку»;
 *  - пока такси выключено, в кабинетах нет рабочих дверей такси (линия, панель, поездки такси,
 *    предзаказы, готовность к линии), а вход в анкету таксиста остаётся открытым.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2600dp")   // высокое окно: весь кабинет в кадре
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DriverCabinetWave1Test {

    @get:Rule
    val composeRule = createComposeRule()

    private fun booking(
        bookingId: Int = 10,
        status: String = "confirmed",
        passengerName: String = "Гульнара",
    ) = DriverBookingDto(
        bookingId = bookingId,
        passengerName = passengerName,
        passengerRating = null,
        route = "Сибай → Уфа",
        status = status,
    )

    private fun approvedApplication() = com.yuldash.app.data.TaxiApplicationDto(
        id = 1, status = "approved", inn = "", permitNumber = "", permitPhotoUrl = "", osagoUrl = "",
        selfieUrl = "", criminalRecordUrl = "", birthDate = "", licenseSinceYear = 2010,
        comment = "", createdAt = "", reviewedAt = null,
    )

    /** Есть ли на экране хоть один узел с этим текстом (без падения, если их несколько). */
    private fun present(text: String): Boolean =
        composeRule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun scrollTo(text: String) {
        composeRule.onAllNodes(hasScrollToNodeAction()).onFirst()
            .performScrollToNode(hasText(text))
    }

    private fun driver(
        taxiOn: Boolean = true,
        bookings: List<DriverBookingDto> = emptyList(),
        taxiApplication: com.yuldash.app.data.TaxiApplicationDto? = null,
        taxiAppLoaded: Boolean = false,
        language: AppLanguage = AppLanguage.Ru,
        onOpenTrip: (Int) -> Unit = {},
    ) {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides language) {
                DriverCabinetContent(
                    online = false,
                    driverRides = emptyList(),
                    driverBookings = bookings,
                    ratingText = "—",
                    onToggleOnline = {},
                    onRate = { _, _, done -> done(true) },
                    onCreateRide = {},
                    onVerifyDriver = {},
                    onBoost = {},
                    onRequestsFeed = {},
                    taxiApplication = taxiApplication,
                    taxiAppLoaded = taxiAppLoaded,
                    taxiOn = taxiOn,
                    onOpenTrip = onOpenTrip,
                )
            }
        }
    }

    private fun passenger(taxiOn: Boolean, language: AppLanguage = AppLanguage.Ru) {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides language) {
                PassengerCabinetContent(
                    loading = false, error = false, activeCount = 0, requestCount = 0, ratingText = "—",
                    activeRide = null, activeStatus = null,
                    onRetry = {}, onMyTrips = {}, onOpenBooking = { _, _ -> }, onFindRide = {},
                    onCreateRequest = {}, onSafety = {}, onInstantOrder = {},
                    taxiOn = taxiOn,
                )
            }
        }
    }

    // ── «Едут с тобой» → «Открыть поездку» ──

    @Test
    fun `у подтверждённой брони есть кнопка и она отдаёт номер брони`() {
        var opened = -1
        driver(bookings = listOf(booking(bookingId = 77, status = "confirmed")), onOpenTrip = { opened = it })
        scrollTo("Едут с тобой")
        composeRule.onNodeWithText("Открыть поездку").assertIsDisplayed()
        composeRule.onNodeWithText("Открыть поездку").performClick()
        assertEquals(77, opened)
    }

    @Test
    fun `у брони в пути кнопка тоже есть`() {
        var opened = -1
        driver(bookings = listOf(booking(bookingId = 78, status = "onboard")), onOpenTrip = { opened = it })
        scrollTo("Едут с тобой")
        composeRule.onNodeWithText("Открыть поездку").performClick()
        assertEquals(78, opened)
    }

    @Test
    fun `у каждой брони своя кнопка и она открывает именно эту бронь`() {
        val opened = mutableListOf<Int>()
        driver(
            bookings = listOf(booking(bookingId = 11, status = "confirmed"), booking(bookingId = 12, status = "onboard", passengerName = "Азамат")),
            onOpenTrip = { opened += it },
        )
        scrollTo("Едут с тобой")
        composeRule.onAllNodesWithText("Открыть поездку")[1].performClick()
        composeRule.onAllNodesWithText("Открыть поездку")[0].performClick()
        assertEquals(listOf(12, 11), opened)
    }

    @Test
    fun `по-башкирски кнопка называется Сәфәрҙе асыу`() {
        driver(bookings = listOf(booking(status = "confirmed")), language = AppLanguage.Ba)
        scrollTo("Һинең менән баралар")
        composeRule.onNodeWithText("Сәфәрҙе асыу").assertIsDisplayed()
    }

    @Test
    fun `у ждущей и завершённой брони кнопки открытия поездки нет`() {
        driver(bookings = listOf(booking(bookingId = 1, status = "pending"), booking(bookingId = 2, status = "done")))
        scrollTo("Ждут подтверждения")
        composeRule.onNodeWithText("Подтвердить").assertIsDisplayed()
        composeRule.onNodeWithText("Открыть поездку").assertDoesNotExist()
    }

    // ── Кабинет водителя при выключенном такси ──

    @Test
    fun `такси включено — линия и поездки такси на месте`() {
        driver(taxiOn = true)
        composeRule.onNodeWithText("Я на линии").assertIsDisplayed()
        scrollTo("Кошелёк")
        assertTrue(present("Мои поездки такси"))
        scrollTo("Поднять маршрут")
        assertTrue(present("Готовность к работе"))
    }

    @Test
    fun `такси выключено — нет линии нет поездок такси нет готовности к линии`() {
        driver(taxiOn = false)
        composeRule.onNodeWithText("Маршруты и проверка").assertIsDisplayed()
        composeRule.onNodeWithText("Я на линии").assertDoesNotExist()
        scrollTo("Кошелёк")
        composeRule.onNodeWithText("Мои поездки такси").assertDoesNotExist()
        scrollTo("Поднять маршрут")
        composeRule.onNodeWithText("Готовность к работе").assertDoesNotExist()
        // Попутки на месте: публикация и проверка водителя.
        assertTrue(present("Создать поездку"))
        assertTrue(present("Проверка водителя"))
    }

    @Test
    fun `такси выключено — вход в анкету таксиста остаётся`() {
        driver(taxiOn = false, taxiApplication = null, taxiAppLoaded = true)
        composeRule.onNodeWithText("Стать таксистом Юлдаша").assertIsDisplayed()
        composeRule.onNodeWithText("Я на линии").assertDoesNotExist()
    }

    @Test
    fun `такси выключено — одобренному таксисту анкету не навязываем`() {
        driver(
            taxiOn = false, taxiAppLoaded = true,
            taxiApplication = approvedApplication(),
        )
        composeRule.onNodeWithText("Стать таксистом Юлдаша").assertDoesNotExist()
        composeRule.onNodeWithText("Я на линии").assertDoesNotExist()
    }

    // ── Кабинет пассажира при выключенном такси ──

    @Test
    fun `у пассажира такси включено — предзаказы и поездки такси на месте`() {
        passenger(taxiOn = true)
        scrollTo("Создать заявку")
        assertTrue(present("Мои предзаказы"))
        assertTrue(present("Мои поездки на такси"))
    }

    @Test
    fun `у пассажира такси выключено — предзаказов и поездок такси нет а попутки есть`() {
        passenger(taxiOn = false)
        scrollTo("Создать заявку")
        composeRule.onNodeWithText("Мои предзаказы").assertDoesNotExist()
        composeRule.onNodeWithText("Мои поездки на такси").assertDoesNotExist()
        assertTrue(present("Найти поездку"))
        assertTrue(present("Создать заявку"))
    }
}
