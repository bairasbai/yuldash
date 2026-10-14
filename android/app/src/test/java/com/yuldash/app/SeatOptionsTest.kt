package com.yuldash.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.yuldash.app.data.parseBookingDetailsDto
import com.yuldash.app.data.parseDriverBookingDto
import com.yuldash.app.data.toRideDto
import org.json.JSONObject
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
 * Опции мест (2026-10-14): «сзади максимум двое», «переднее свободно», «хочу переднее».
 * Проверяем то, что видит человек на двух языках, и что тумблеры правда меняют состояние.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SeatOptionsTest {

    @get:Rule
    val compose = createComposeRule()

    private fun lang(language: AppLanguage, content: @Composable () -> Unit) {
        compose.setContent { CompositionLocalProvider(LocalAppLanguage provides language) { content() } }
    }

    // ─────────── потолок мест — зеркало сервера ───────────

    @Test
    fun seatCap_mirrorsServer() {
        assertEquals(8, seatCap(backMaxTwo = false, frontSeatAvailable = true))
        assertEquals(8, seatCap(backMaxTwo = false, frontSeatAvailable = false))
        assertEquals(3, seatCap(backMaxTwo = true, frontSeatAvailable = true))
        assertEquals(2, seatCap(backMaxTwo = true, frontSeatAvailable = false))
        assertEquals("3", clampSeatsText("4", backMaxTwo = true, frontSeatAvailable = true))
        assertEquals("2", clampSeatsText("4", backMaxTwo = true, frontSeatAvailable = false))
        assertEquals("1", clampSeatsText("1", backMaxTwo = true, frontSeatAvailable = true))
        assertEquals("", clampSeatsText("", backMaxTwo = true, frontSeatAvailable = true))
    }

    // ─────────── водитель: карточка «Места в машине» ───────────

    @Test
    fun seatOptionsCard_ru_togglesAndExplains() {
        lang(AppLanguage.Ru) {
            var back by remember { mutableStateOf(false) }
            var front by remember { mutableStateOf(true) }
            SeatOptionsCard(back, front, { back = it }, { front = it })
        }
        compose.onNodeWithText("Места в машине").assertIsDisplayed()
        compose.onNodeWithText("Сзади максимум двое").assertIsDisplayed()
        compose.onNodeWithText("Переднее место свободно").assertIsDisplayed()
        compose.onNodeWithText("Обычно 4 места: 1 спереди и 3 сзади. Включи, если сзади поедут только двое — будет 3 места")
            .assertIsDisplayed()
        compose.onAllNodes(isToggleable())[0].performClick()   // «сзади двое» → вкл
        compose.waitForIdle()
        compose.onNodeWithText("Сзади поедут двое и один спереди — мест не больше 3").assertIsDisplayed()
        compose.onAllNodes(isToggleable())[1].performClick()   // переднее занято своим
        compose.waitForIdle()
        compose.onNodeWithText("Сзади поедут двое, спереди — свой человек: мест не больше 2").assertIsDisplayed()
    }

    @Test
    fun seatOptionsCard_ba() {
        lang(AppLanguage.Ba) { SeatOptionsCard(backMaxTwo = false, frontSeatAvailable = true, onBackMaxTwo = {}, onFrontSeatAvailable = {}) }
        compose.onNodeWithText("Машинала урындар").assertIsDisplayed()
        compose.onNodeWithText("Артта күп тигәндә икәү").assertIsDisplayed()
        compose.onNodeWithText("Алғы урын буш").assertIsDisplayed()
    }

    @Test
    fun createRideForm_hasSeatOptions_andFirstToggleIsBackMaxTwo() {
        var asked: Boolean? = null
        lang(AppLanguage.Ru) {
            CreateRideFormContent(
                from = "Уфа", to = "Сибай", dateText = "", seats = "4", price = "300", comment = "",
                typeKey = "regular", recurrence = "none", receiverName = "", parcelSize = "",
                pickup = "", pinned = false, womenOnly = false, childSeat = false, petsAllowed = false,
                baggage = false, airConditioner = false, smoking = false, onlyTrusted = false, quiet = false,
                waypoints = emptyList(), priceHint = 0, loading = false, error = null,
                onFromChange = {}, onToChange = {}, onSeatsChange = {}, onPriceChange = {},
                onCommentChange = {}, onSelectType = {}, onSelectRecurrence = {},
                onReceiverNameChange = {}, onParcelSizeChange = {}, onPickupChange = {}, onOpenPicker = {},
                onOpenDatePicker = {}, onUsePriceHint = {},
                onWomenOnly = {}, onChildSeat = {}, onPetsAllowed = {}, onBaggage = {}, onAirConditioner = {},
                onSmoking = {}, onOnlyTrusted = {}, onQuiet = {}, onWaypointsChange = {},
                onPublish = {}, onCancel = {},
                onBackMaxTwo = { asked = it },
            )
        }
        compose.onNodeWithText("Места в машине").assertExists()
        compose.onAllNodes(isToggleable())[0].performClick()
        assertEquals(true, asked)
    }

    // ─────────── пассажир: «Хочу переднее место» ───────────

    @Test
    fun frontSeatRequest_free_ru_toggles() {
        var asked = false
        lang(AppLanguage.Ru) {
            var checked by remember { mutableStateOf(false) }
            FrontSeatRequestBlock(frontSeatAvailable = true, frontSeatTaken = false, checked = checked,
                onChecked = { checked = it; asked = it })
        }
        compose.onNodeWithText("Хочу переднее место").assertIsDisplayed()
        compose.onAllNodes(isToggleable())[0].performClick()
        assertTrue(asked)
    }

    @Test
    fun frontSeatRequest_taken_saysSo_noToggle() {
        lang(AppLanguage.Ru) { FrontSeatRequestBlock(frontSeatAvailable = true, frontSeatTaken = true, checked = false, onChecked = {}) }
        compose.onNodeWithText("Переднее место уже занято").assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(isToggleable()).fetchSemanticsNodes().size)
    }

    @Test
    fun frontSeatRequest_notOffered_ba() {
        lang(AppLanguage.Ba) { FrontSeatRequestBlock(frontSeatAvailable = false, frontSeatTaken = false, checked = false, onChecked = {}) }
        compose.onNodeWithText("Алғы урында йөрөтөүсенең үҙ кешеһе").assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(isToggleable()).fetchSemanticsNodes().size)
    }

    @Test
    fun frontSeatRequest_taken_ba() {
        lang(AppLanguage.Ba) { FrontSeatRequestBlock(frontSeatAvailable = true, frontSeatTaken = true, checked = false, onChecked = {}) }
        compose.onNodeWithText("Алғы урын инде алынған").assertIsDisplayed()
    }

    @Test
    fun driverBadge_bothLanguages() {
        lang(AppLanguage.Ru) { FrontSeatRequestBadge() }
        compose.onNodeWithText("Просит переднее место").assertIsDisplayed()
    }

    @Test
    fun driverBadge_ba() {
        lang(AppLanguage.Ba) { FrontSeatRequestBadge() }
        compose.onNodeWithText("Алғы урынды һорай").assertIsDisplayed()
    }

    // ─────────── лента: значки на карточке поездки ───────────

    private val ride = Ride(
        id = "7", from = "Баймак", to = "Сибай", time = "10:00", driver = "Азат", car = "Лада",
        price = 300, seats = 2, seatsTotal = 3, rating = 4.9, verified = true, boosted = false,
    )

    @Test
    fun feedCard_showsBackTwo_andFrontTaken_ru() {
        lang(AppLanguage.Ru) {
            RideCard(ride.copy(backMaxTwo = true, frontSeatTaken = true), compact = true, fullWidth = true,
                onBook = {}, onShare = {}, onBoost = {})
        }
        compose.onNodeWithText("Сзади двое").assertExists()
        compose.onNodeWithText("Переднее занято").assertExists()
        compose.onNodeWithText("Переднее свободно").assertDoesNotExist()
    }

    @Test
    fun feedCard_frontFree_ba_andHiddenWhenNotOffered() {
        lang(AppLanguage.Ba) {
            RideCard(ride, compact = true, fullWidth = true, onBook = {}, onShare = {}, onBoost = {})
        }
        compose.onNodeWithText("Алғы урын буш").assertExists()
        compose.onNodeWithText("Артта икәү").assertDoesNotExist()
    }

    @Test
    fun feedCard_noFrontChip_whenDriverKeepsItForOwnPerson() {
        lang(AppLanguage.Ru) {
            RideCard(ride.copy(frontSeatAvailable = false), compact = true, fullWidth = true,
                onBook = {}, onShare = {}, onBoost = {})
        }
        compose.onNodeWithText("Переднее свободно").assertDoesNotExist()
        compose.onNodeWithText("Переднее занято").assertDoesNotExist()
    }

    // ─────────── разбор ответов сервера ───────────

    @Test
    fun parse_rideOptions_andOldServerDefaults() {
        val dto = JSONObject("""{"id":1,"back_max_two":true,"front_seat_available":false,"front_seat_taken":true}""").toRideDto()
        assertTrue(dto.backMaxTwo)
        assertFalse(dto.frontSeatAvailable)
        assertTrue(dto.frontSeatTaken)
        // Старый сервер поля не присылает: переднее свободно, как было всегда.
        val old = JSONObject("""{"id":2}""").toRideDto()
        assertFalse(old.backMaxTwo)
        assertTrue(old.frontSeatAvailable)
        assertFalse(old.frontSeatTaken)
        val ui = dto.toUiRide()
        assertTrue(ui.backMaxTwo && !ui.frontSeatAvailable && ui.frontSeatTaken)
    }

    @Test
    fun parse_bookingFrontSeat() {
        assertTrue(parseDriverBookingDto(JSONObject("""{"booking_id":5,"front_seat":true}""")).frontSeat)
        assertFalse(parseDriverBookingDto(JSONObject("""{"booking_id":5}""")).frontSeat)
        assertTrue(parseBookingDetailsDto(JSONObject("""{"booking_id":5,"ride_id":1,"front_seat":true}""")).frontSeat)
    }
}
