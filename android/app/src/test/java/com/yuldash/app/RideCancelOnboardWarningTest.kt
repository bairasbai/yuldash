package com.yuldash.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.yuldash.app.data.DriverBookingDto
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * «Снять поездку?» в кабинете водителя, когда пассажиры уже в машине (решение 23.10.2026).
 *
 * Снять поездку «в пути» водителю можно (сломалась машина, высадил за поведение), но он должен
 * понимать, что делает: довёз — нажать «Завершить рейс»; снимает — пассажирам уйдёт срочное
 * уведомление, а близкие, с кем они поделились поездкой, увидят по ссылке «отменили в пути». Без этого
 * предупреждения водитель, который довёз людей, мог по ошибке снять поездку вместо завершения —
 * и родные пассажиров увидели бы тревожное «отменили в пути».
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RideCancelOnboardWarningTest {

    @get:Rule val compose = createComposeRule()

    private val ride = Ride(id = "7", from = "Сибай", to = "Уфа", time = "Сегодня 14:30", driver = "Марат",
        car = "", price = 500, seats = 1, seatsTotal = 4, rating = 0.0, verified = true, boosted = false)

    private fun booking(id: Int, rideId: Int, status: String) = DriverBookingDto(
        bookingId = id, passengerName = "Гульнара", passengerRating = null, route = "Сибай → Уфа",
        status = status, rideId = rideId,
    )

    private fun openCancelDialog(bookings: List<DriverBookingDto>, language: AppLanguage = AppLanguage.Ru) {
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides language) {
                DriverCabinetContent(
                    online = false, driverRides = listOf(ride), driverBookings = bookings, ratingText = "—",
                    onToggleOnline = {}, onRate = { _, _, done -> done(true) }, onCreateRide = {},
                    onVerifyDriver = {}, onBoost = {}, onRequestsFeed = {},
                )
            }
        }
        val drop = if (language == AppLanguage.Ba) "Сәфәрҙе алыу" else "Снять поездку"
        compose.onAllNodesWithText(drop).onFirst().performClick()
        compose.onNodeWithText(if (language == AppLanguage.Ba) "Сәфәрҙе алырғамы?" else "Снять поездку?").assertIsDisplayed()
    }

    private val sameRide = listOf(booking(1, 7, "onboard"), booking(2, 7, "onboard"), booking(3, 7, "confirmed"))

    @Test fun `в машине пассажиры — окно предупреждает и называет число`() {
        // Ещё один «в пути» на ДРУГОЙ поездке того же маршрута — не считается.
        openCancelDialog(sameRide + booking(4, 8, "onboard"))
        compose.onNodeWithTag("rideCancelOnboardWarning").assertIsDisplayed()
        compose.onNodeWithText(
            "В машине пассажиры: 2. Если довёз — нажми «Завершить рейс». Если снимаешь — им придёт уведомление, " +
                "а близкие, с кем они поделились поездкой, увидят по ссылке, что поездку отменили в пути.",
        ).assertIsDisplayed()
        // Прежний текст окна на месте: сколько людей рассчитывали на машину.
        compose.onNodeWithText("Сибай → Уфа. Места забронировали 3 человек. Все брони снимутся, всем придёт уведомление.")
            .assertIsDisplayed()
    }

    @Test fun `по-башкирски — то же предупреждение`() {
        openCancelDialog(sameRide, AppLanguage.Ba)
        compose.onNodeWithText("Машинала юлаусылар: 2.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("«Рейсты тамамлау»", substring = true).assertIsDisplayed()
    }

    @Test fun `длинный текст окна прокручивается — крупный шрифт его не обрежет`() {
        openCancelDialog(sameRide, AppLanguage.Ba)
        compose.onNodeWithTag("rideCancelConfirmText").assert(hasScrollAction())
    }

    @Test fun `никого в машине — предупреждения нет`() {
        openCancelDialog(listOf(booking(3, 7, "confirmed"), booking(5, 7, "done"), booking(4, 8, "onboard")))
        compose.onAllNodesWithTag("rideCancelOnboardWarning").assertCountEquals(0)
        compose.onAllNodesWithText("В машине пассажиры", substring = true).assertCountEquals(0)
    }

    @Test fun `счёт — только «в пути» этой поездки, старый сервер без ride_id — ноль`() {
        assertEquals(2, onboardPassengers(7, sameRide))
        assertEquals(0, onboardPassengers(8, sameRide))
        assertEquals("поездка без числового id", 0, onboardPassengers(null, sameRide))
        assertEquals("старый сервер: ride_id нет", 0, onboardPassengers(7, listOf(booking(1, 0, "onboard"))))
        assertEquals(0, onboardPassengers(0, listOf(booking(1, 0, "onboard"))))
    }
}
