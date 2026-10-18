package com.yuldash.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.SavedStateHandle
import com.yuldash.app.data.DriverBookingDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        rides: List<Ride> = emptyList(),
        onOpenCompletedTrip: (Int) -> Unit = {},
        fontScale: Float = 1f,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides language,
                LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                DriverCabinetContent(
                    online = false,
                    driverRides = rides,
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
                    onOpenCompletedTrip = onOpenCompletedTrip,
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
    fun `у ждущей и отменённой брони кнопки открытия поездки нет`() {
        driver(bookings = listOf(booking(bookingId = 1, status = "pending"), booking(bookingId = 2, status = "cancelled")))
        scrollTo("Ждут подтверждения")
        composeRule.onNodeWithText("Подтвердить").assertIsDisplayed()
        composeRule.onNodeWithText("Открыть поездку").assertDoesNotExist()
    }

    @Test fun completedUnratedBookingReopensByBookingId() = completedBookingReopens(AppLanguage.Ru, 0)
    @Test fun completedRatedBookingReopensByBookingId() = completedBookingReopens(AppLanguage.Ru, 3)
    @Test fun bashkirCompletedUnratedBookingReopensByBookingId() = completedBookingReopens(AppLanguage.Ba, 0)
    @Test fun bashkirCompletedRatedBookingReopensByBookingId() = completedBookingReopens(AppLanguage.Ba, 3)

    private fun publishedRide() = Ride(id = "7", from = "Сибай", to = "Акъяр", time = "18:00", driver = "Водитель", car = "",
        price = 700, seats = 2, rating = 5.0, verified = true, boosted = false, waypoints = listOf("Ургаза", "Бурибай"))

    private fun completedBookingReopens(language: AppLanguage, stars: Int) {
        var opened = -1
        var activeCallbacks = 0
        val ride = publishedRide()
        val bookingId = 77
        val vm = YuldashViewModel(SavedStateHandle())
        vm.activeTrip.value = ride
        vm.screen.value = Screen.DriverCabinet
        driver(taxiOn = false, bookings = listOf(booking(bookingId = bookingId, status = "done").copy(myStars = stars)),
            language = language, rides = listOf(ride), onOpenTrip = { activeCallbacks++; vm.openDriverTrip(it) },
            onOpenCompletedTrip = { opened = it; vm.applyBookingLink(it, "driver", "done", true) })
        val label = if (language == AppLanguage.Ru) "Открыть поездку" else "Сәфәрҙе асыу"
        scrollTo(label)
        composeRule.onNodeWithText(label).assertIsDisplayed().performClick()
        assertTrue(bookingId != ride.id.toInt())
        assertEquals(bookingId, opened)
        assertEquals(0, activeCallbacks)
        assertEquals(Screen.ActiveTrip, vm.screen.value)
        assertEquals(bookingId, vm.activeBookingId.value)
        assertEquals(bookingId.toString(), vm.selectedRide.value?.id)
        assertNull("Завершённая бронь не должна включать сопровождение активной поездки", vm.activeTrip.value)
    }

    @Test fun completedBookingWithoutCallbackNeverFallsBackToActiveTrip() {
        var activeCallbacks = 0
        driver(taxiOn = false, bookings = listOf(booking(status = "done")), onOpenTrip = { activeCallbacks++ })
        scrollTo("Открыть поездку")
        composeRule.onNodeWithText("Открыть поездку").performClick()
        assertEquals(0, activeCallbacks)
    }

    @Test @Config(qualifiers = "w320dp-h2600dp")
    fun russianRatingEditFitsNarrowLargeTextAndOpensEditor() = ratingEditFitsNarrowLargeText(AppLanguage.Ru)

    @Test @Config(qualifiers = "w320dp-h2600dp")
    fun bashkirRatingEditFitsNarrowLargeTextAndOpensEditor() = ratingEditFitsNarrowLargeText(AppLanguage.Ba)

    private fun ratingEditFitsNarrowLargeText(language: AppLanguage) {
        driver(taxiOn = false, language = language, fontScale = 1.3f,
            bookings = listOf(booking(bookingId = 77, status = "done").copy(myStars = 3)))
        val edit = if (language == AppLanguage.Ru) "Изменить" else "Үҙгәртеү"
        scrollTo(edit)
        assertRatingActionFits(edit, language)
        composeRule.onNodeWithText(edit).performClick()
        composeRule.onNodeWithText(if (language == AppLanguage.Ru) "Выбрано 3 звезды — подтверди"
            else "3 йондоҙ һайланды — раҫла").assertExists()
        val send = if (language == AppLanguage.Ru) "Отправить оценку" else "Баһаны ебәреү"
        scrollTo(send)
        assertRatingActionFits(send, language)
        composeRule.onNodeWithText(edit).assertDoesNotExist()
    }

    private fun assertRatingActionFits(label: String, language: AppLanguage) {
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val diagnostics = "language=$language; label=$label; size=${layout.size}; paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}; " +
            "overflowWidth=${layout.didOverflowWidth}; overflowHeight=${layout.didOverflowHeight}; exceeded=${layout.multiParagraph.didExceedMaxLines}; " +
            "line0 left=${layout.getLineLeft(0)} right=${layout.getLineRight(0)} top=${layout.getLineTop(0)} bottom=${layout.getLineBottom(0)} end=${layout.getLineEnd(0)}; " +
            "constraints=${layout.layoutInput.constraints}; maxW=${layout.layoutInput.constraints.maxWidth}; maxH=${layout.layoutInput.constraints.maxHeight}"
        assertEquals("Подпись кнопки должна помещаться в одну строку; $diagnostics", 1, layout.lineCount)
        // Диагностика «Изменить» RU: узел 87px, абзац 240px, буквы около 86px — hasVisualOverflow
        // сравнивает узел с абзацем и ошибочно сообщает обрезку. Проверяем сами буквы и высоту.
        assertFalse("Все строки должны помещаться; $diagnostics", layout.multiParagraph.didExceedMaxLines)
        assertTrue("Буквы должны помещаться в доступную ширину; $diagnostics",
            layout.getLineLeft(0) >= -1f && layout.getLineRight(0) <= layout.layoutInput.constraints.maxWidth + 1f)
        assertTrue("Текст должен помещаться по высоте; $diagnostics", layout.multiParagraph.height <= layout.size.height + 1f)
        assertEquals("Все буквы подписи должны быть видны; $diagnostics", label.length, layout.getLineEnd(0))
        assertFalse("Текст кнопки нельзя обрезать многоточием; $diagnostics", layout.isLineEllipsized(0))
        composeRule.onNodeWithText(label).assertHeightIsAtLeast(48.dp)
    }

    @Test fun ownPublishedRideDoesNotAskForPassengerDropoff() = ownPublishedRideHasNoBookingWarning(AppLanguage.Ru)
    @Test fun bashkirOwnPublishedRideDoesNotAskForPassengerDropoff() = ownPublishedRideHasNoBookingWarning(AppLanguage.Ba)

    private fun ownPublishedRideHasNoBookingWarning(language: AppLanguage) {
        driver(taxiOn = false, language = language, rides = listOf(publishedRide()))
        scrollTo(if (language == AppLanguage.Ru) "Опубликована" else "Баҫтырылды")
        composeRule.onNodeWithText("Сибай → Акъяр").assertIsDisplayed()
        composeRule.onNodeWithText(if (language == AppLanguage.Ru) "Место высадки не указано — уточни в чате"
            else "Төшөү урыны күрһәтелмәгән — чатта асыҡла").assertDoesNotExist()
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

    @Test
    fun `у пассажира такси включено — зелёная карточка Быстрый заказ на месте`() {
        passenger(taxiOn = true)
        composeRule.onNodeWithText("Быстрый заказ").assertIsDisplayed()
        composeRule.onNodeWithText("Вызвать машину сейчас — цену видно заранее").assertIsDisplayed()
    }

    @Test
    fun `у пассажира такси выключено — карточки Быстрый заказ нет а кабинет не пустой`() {
        passenger(taxiOn = false)
        composeRule.onNodeWithText("Быстрый заказ").assertDoesNotExist()
        composeRule.onNodeWithText("Вызвать машину сейчас — цену видно заранее").assertDoesNotExist()
        scrollTo("Создать заявку")
        assertTrue(present("Найти поездку"))
    }

    @Test
    fun `у пассажира такси выключено — по-башкирски карточки Тиҙ заказ тоже нет`() {
        passenger(taxiOn = false, language = AppLanguage.Ba)
        composeRule.onNodeWithText("Тиҙ заказ").assertDoesNotExist()
        composeRule.onNodeWithText("Хәҙер машина саҡырыу — хаҡ алдан күренә").assertDoesNotExist()
    }
}
