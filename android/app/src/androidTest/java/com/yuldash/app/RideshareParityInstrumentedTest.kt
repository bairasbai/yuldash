package com.yuldash.app

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.view.WindowCompat
import com.yuldash.app.ui.theme.YuldashTheme
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android typography and touches: pickup choice and unlocked contacts in all four variants. */
@RunWith(AndroidJUnit4::class)
class RideshareParityInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun verify(language: AppLanguage, dark: Boolean) {
        val ride = Ride(
            id = "42", from = "Сибай", to = "Акъяр", time = "18:00", driver = "Тестовый водитель",
            car = "Lada Vesta", price = 50000, seats = 3, rating = 5.0, verified = true,
            boosted = false, waypoints = listOf("Ургаза", "Бурибай"),
        )
        val picked = mutableStateOf<String?>(null)
        val dropoff = mutableStateOf<String?>(null)
        val bind = mutableStateOf<Boolean?>(null)
        val matchedRequest = com.yuldash.app.data.RequestFeedDto(
            id = 5, passengerName = "Тестовый пассажир", from = "Ургаза", to = "Бурибай", seats = 1, comment = "", responded = false,
            matchedRideId = 42, matchedRideFromCity = "Сибай", matchedRideToCity = "Акъяр", matchedRideDepartAt = "2030-01-02T10:00:00Z",
            matchedPickupStop = "Ургаза", matchedDropoffStop = "Бурибай",
        )
        val stops = mutableStateOf(listOf("Абзаково (Учалинский)"))
        val detailsState = mutableStateOf(0)
        var detailsRetryCalls = 0
        val phone = "+70000000001" // Synthetic fixture, never a real account.
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides language, LocalNightRide provides dark) {
                YuldashTheme(darkTheme = dark) {
                    Column(
                        Modifier.fillMaxSize().background(CanonBg).systemBarsPadding().verticalScroll(rememberScrollState()).padding(CanonSpace.md),
                        verticalArrangement = Arrangement.spacedBy(CanonSpace.md),
                    ) {
                        if (detailsState.value != 2) ActiveTripDetailsNotice(
                            loading = detailsState.value == 0,
                            failure = if (detailsState.value == 1) IOException("Synthetic offline fixture") else null,
                            onRetry = { detailsRetryCalls++; detailsState.value = 0 },
                        )
                        BookingSegmentPicker(ride, picked.value, dropoff.value, {
                            picked.value = it; dropoff.value = keepValidDropoff(ride, it, dropoff.value)
                        }, { dropoff.value = it })
                        BookingSegmentNote(picked.value, dropoff.value, true, ride.from, ride.to)
                        CreateRideStopsEditor(stops.value, { stops.value = it }) { index ->
                            StopSuggestField(stops.value[index], { value ->
                                stops.value = stops.value.toMutableList().also { it[index] = value }
                            }, endpoints = listOf("Сибай", "Акъяр"))
                        }
                        ActiveTripContactsCard(TripContacts(phone, "белая Lada Vesta", "А000АА 00", "Ургаза", null, null))
                        RequestRouteOfferChoice(matchedRequest, bind.value, { bind.value = it })
                    }
                }
            }
        }
        val loadingText = if (language == AppLanguage.Ba) "Сәфәр мәғлүмәттәрен яңыртабыҙ" else "Обновляем данные поездки"
        compose.onNodeWithText(loadingText).assertIsDisplayed()
        capture(language, dark, "loading")
        compose.runOnIdle { detailsState.value = 1 }
        val errorText = if (language == AppLanguage.Ba) "Сәфәр мәғлүмәттәрен йөкләп булманы" else "Не удалось загрузить данные поездки"
        compose.onNodeWithText(errorText).assertIsDisplayed()
        capture(language, dark, "error")
        val retryText = if (language == AppLanguage.Ba) "Мәғлүмәттәрҙе яңынан йөкләргә" else "Повторить загрузку данных"
        compose.onNodeWithText(retryText).performClick()
        compose.onNodeWithText(loadingText).assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, detailsRetryCalls); detailsState.value = 2 }
        val title = if (language == AppLanguage.Ba) "Ҡайҙа ултыраһың?" else "Где садишься?"
        compose.onNodeWithText(title).performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Ургаза", useUnmergedTree = true).onFirst().performClick()
        compose.runOnIdle { assertEquals("Ургаза", picked.value) }
        compose.onAllNodesWithText("Бурибай", useUnmergedTree = true).onLast().performScrollTo().performClick()
        compose.runOnIdle { assertEquals("Бурибай", dropoff.value) }
        capture(language, dark, "segment")
        val selected = if (language == AppLanguage.Ba) "Ултырыу: Ургаза" else "Посадка: Ургаза"
        compose.onAllNodesWithText(selected).onFirst().performScrollTo().assertIsDisplayed()
        val stopsTitle = if (language == AppLanguage.Ba) "Юл буйындағы туҡталыштар" else "Остановки по пути"
        compose.onNodeWithText(stopsTitle).performScrollTo().assertIsDisplayed()
        capture(language, dark, "stops")
        compose.onNodeWithText(phone).performScrollTo().assertIsDisplayed()
        capture(language, dark, "contacts")
        val ownRide = if (language == AppLanguage.Ba) "Үҙ сәфәремдә урын" else "Место в моём рейсе"
        compose.onNodeWithText(ownRide).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(true, bind.value) }
        val segment = if (language == AppLanguage.Ba) "Юл өлөшө: Ургаза → Бурибай" else "Участок: Ургаза → Бурибай"
        compose.onAllNodesWithText(segment).onLast().performScrollTo().assertIsDisplayed()
        capture(language, dark, "bound-offer")
    }

    private fun capture(language: AppLanguage, dark: Boolean, state: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "rideshare-parity-20261018")
        directory.mkdirs()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(directory, "${language.name}-${if (dark) "dark" else "light"}-$state.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @Test fun russianLight() = verify(AppLanguage.Ru, false)
    @Test fun russianDark() = verify(AppLanguage.Ru, true)
    @Test fun bashkirLight() = verify(AppLanguage.Ba, false)
    @Test fun bashkirDark() = verify(AppLanguage.Ba, true)
}
