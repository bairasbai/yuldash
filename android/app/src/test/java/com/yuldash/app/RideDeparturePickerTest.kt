package com.yuldash.app

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import com.yuldash.app.ui.theme.YuldashTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w320dp-h470dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RideDeparturePickerTest {
    @get:Rule val compose = createComposeRule()
    private val mounted = mutableStateOf(true)
    private var darkBefore: Boolean? = null
    private lateinit var localeBefore: Locale
    private lateinit var formatBefore: Locale
    private lateinit var displayBefore: Locale

    @Before fun prepare() {
        darkBefore = ThemePrefs.darkOverride
        ThemePrefs.darkOverride = true
        localeBefore = Locale.getDefault()
        formatBefore = Locale.getDefault(Locale.Category.FORMAT)
        displayBefore = Locale.getDefault(Locale.Category.DISPLAY)
        Locale.setDefault(Locale.US)
        Locale.setDefault(Locale.Category.FORMAT, Locale.FRANCE)
        Locale.setDefault(Locale.Category.DISPLAY, Locale.JAPAN)
    }

    @After fun cleanup() {
        compose.runOnIdle { mounted.value = false }
        ThemePrefs.darkOverride = darkBefore
        Locale.setDefault(localeBefore)
        Locale.setDefault(Locale.Category.FORMAT, formatBefore)
        Locale.setDefault(Locale.Category.DISPLAY, displayBefore)
    }

    @Test fun strictParserRejectsImpossibleDatesInsteadOfRollingIntoNextMonth() {
        assertNotNull(parseRideDeparture("29.02.2028, 09:45"))
        assertNull(parseRideDeparture("29.02.2027, 09:45"))
        assertNull(parseRideDeparture("31.04.2030, 09:45"))
        assertNull(parseRideDeparture("02.01.2030, 24:00"))
        assertNull(parseRideDeparture("2.1.2030, 9:45"))
    }

    @Test fun switchingMonthAndYearClampsOnlyTheDayThatDoesNotExist() {
        val today = LocalDate.of(2026, 10, 19)
        assertEquals(LocalDate.of(2028, 2, 29), clampRideDepartureDate(2028, 2, 31, today))
        assertEquals(LocalDate.of(2027, 2, 28), clampRideDepartureDate(2027, 2, 29, today))
        assertEquals(LocalDate.of(2030, 4, 30), clampRideDepartureDate(2030, 4, 31, today))
        assertEquals(LocalDate.of(2030, 5, 15), clampRideDepartureDate(2030, 5, 15, today))
    }

    @Test fun noDateBeforeTodayCanBeSelected() {
        val today = LocalDate.of(2026, 10, 19)
        assertEquals(today, clampRideDepartureDate(2020, 1, 1, today))
        assertEquals(today, clampRideDepartureDate(2026, 10, 18, today))
        assertEquals(LocalDate.of(2026, 11, 1), clampRideDepartureDate(2026, 11, 1, today))
    }

    @Test fun openingPreservesSavedDateAndUsesThreeHoursFromNowForAnEmptyForm() {
        val now = LocalDateTime.of(2026, 10, 19, 23, 30)
        assertEquals(LocalDateTime.of(2030, 1, 2, 9, 45), initialRideDeparture("02.01.2030, 09:45", now))
        assertEquals(LocalDateTime.of(2026, 10, 20, 2, 30), initialRideDeparture("", now))
        assertEquals(LocalDateTime.of(2026, 10, 19, 9, 45), initialRideDeparture("02.01.2020, 09:45", now))
    }

    @Test fun timeRequiresAsciiDigitsAndValidTwentyFourHourRanges() {
        assertEquals(LocalTime.MIDNIGHT, rideDepartureTime("00", "00"))
        assertEquals(LocalTime.of(23, 59), rideDepartureTime("23", "59"))
        listOf("" to "00", "24" to "00", "09" to "60", "-1" to "00", "123" to "00", "٠٩" to "00")
            .forEach { (hour, minute) -> assertNull(rideDepartureTime(hour, minute)) }
    }

    @Test fun submittedDateUsesAsciiEvenWhenSystemUsesArabicDigits() {
        Locale.setDefault(Locale.forLanguageTag("ar-EG"))
        assertEquals("29.02.2028, 00:07", formatRideDeparture(LocalDate.of(2028, 2, 29), LocalTime.of(0, 7)))
    }

    @Test fun narrowRussianDarkDialogRejectsInvalidTimeAndReturnsCorrectedValue() = invalidTime(AppLanguage.Ru)
    @Test fun narrowBashkirDarkDialogRejectsInvalidTimeAndReturnsCorrectedValue() = invalidTime(AppLanguage.Ba)

    private fun invalidTime(language: AppLanguage) {
        val confirmed = mutableListOf<String>()
        compose.setContent {
            if (mounted.value) {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalAppLanguage provides language,
                    LocalDensity provides Density(density.density, 1.3f),
                ) { YuldashTheme { RideDeparturePicker("02.01.2030, 09:45", {}, { confirmed += it }) } }
            }
        }
        compose.onNodeWithTag(RIDE_DEPARTURE_MONTH).assertContentDescriptionEquals(
            if (language == AppLanguage.Ba) "Ай: Ғинуар" else "Месяц: Январь")
        compose.onNodeWithTag(RIDE_DEPARTURE_HOUR).performScrollTo().performTextReplacement("24")
        compose.onNodeWithTag(RIDE_DEPARTURE_CONFIRM).assertIsNotEnabled()
        val error = if (language == AppLanguage.Ba) "Ваҡытты 00:00–23:59 араһында күрһәт."
            else "Укажи время от 00:00 до 23:59."
        compose.onNodeWithText(error).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(RIDE_DEPARTURE_HOUR).performScrollTo().performTextReplacement("23")
        compose.onNodeWithTag(RIDE_DEPARTURE_MINUTE).performScrollTo().performTextReplacement("60")
        compose.onNodeWithTag(RIDE_DEPARTURE_CONFIRM).assertIsNotEnabled()
        assertTrue(confirmed.isEmpty())
        compose.onNodeWithTag(RIDE_DEPARTURE_MINUTE).performTextReplacement("59")
        compose.onNodeWithTag(RIDE_DEPARTURE_CONFIRM).assertIsEnabled().performClick()
        assertEquals(listOf("02.01.2030, 23:59"), confirmed)
        assertEquals(Locale.US, Locale.getDefault())
        assertEquals(Locale.FRANCE, Locale.getDefault(Locale.Category.FORMAT))
        assertEquals(Locale.JAPAN, Locale.getDefault(Locale.Category.DISPLAY))
    }
}
