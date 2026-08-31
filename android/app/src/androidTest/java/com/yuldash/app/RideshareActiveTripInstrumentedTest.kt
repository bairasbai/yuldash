package com.yuldash.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yandex.mapkit.geometry.Point
import com.yuldash.app.ui.theme.YuldashTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Живой полноэкранный рендер варианта B с настоящей MapKit-картой на Pixel API 35. */
@RunWith(AndroidJUnit4::class)
class RideshareActiveTripInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun activeTripOptionB_showsSafeBoardingHierarchyAndFixedActions() {
        render()

        composeRule.onNodeWithText("Моя поездка").assertIsDisplayed()
        composeRule.onNodeWithText("Ринат Хабиров").assertIsDisplayed()
        composeRule.onNodeWithText("4,9").assertIsDisplayed()
        composeRule.onNodeWithText("Проверен").assertIsDisplayed()
        composeRule.onNodeWithText("Lada Vesta · белая · А123АА 102").assertIsDisplayed()
        composeRule.onNodeWithText("Код посадки").assertIsDisplayed()
        composeRule.onNodeWithText("4821").assertIsDisplayed()
        composeRule.onNodeWithText("Подтверждено").assertIsDisplayed()
        composeRule.onNodeWithText("Выехал").assertIsDisplayed()
        composeRule.onNodeWithText("Подъезжает").assertIsDisplayed()
        composeRule.onNodeWithText("Написать").assertIsDisplayed()
        composeRule.onNodeWithText("Я сел").assertIsDisplayed()
        composeRule.waitForIdle()
        holdIfRequested()
    }

    @Test
    fun activeTripOptionB_submitsRealNextPassengerStatus() {
        var submitted = ""
        render(onPrimary = { submitted = activeTripNextStatus("passenger", "departed", null) })

        composeRule.onNodeWithText("Я сел").performClick()
        assertEquals("sat", submitted)
    }

    @Test
    fun activeTripOptionB_keepsBashkirCoreCopyVisible() {
        render(language = AppLanguage.Ba)

        composeRule.onNodeWithText("Минең сәфәр").assertIsDisplayed()
        composeRule.onNodeWithText("Ултырыу коды").assertIsDisplayed()
        composeRule.onNodeWithText("Раҫланды").assertIsDisplayed()
        composeRule.onNodeWithText("Яҙырға").assertIsDisplayed()
        composeRule.onNodeWithText("Мин ултырҙым").assertIsDisplayed()
    }

    private fun render(
        language: AppLanguage = AppLanguage.Ru,
        onPrimary: () -> Unit = {},
    ) {
        composeRule.setContent {
            YuldashTheme {
                CompositionLocalProvider(LocalAppLanguage provides language) {
                    Scaffold(
                        containerColor = CanonBg,
                        topBar = { ScreenTopBar(appText("Моя поездка", "Минең сәфәр"), {}) },
                        bottomBar = {
                            ActiveTripDecisionBar(
                                primaryText = activeTripActionLabel("sat"),
                                onMessage = {},
                                onPrimary = onPrimary,
                            )
                        },
                    ) { padding ->
                        LazyColumn(
                            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(top = 8.dp, bottom = 20.dp),
                        ) {
                            item {
                                ActiveTripOptionBHero(
                                    driverName = "Ринат Хабиров",
                                    avatarUrl = "",
                                    rating = "4,9",
                                    verified = true,
                                    trips = 128,
                                    since = "2023-04",
                                    carSummary = "Lada Vesta · белая · А123АА 102",
                                    phoneUnlocked = true,
                                    onCall = {},
                                    boardingCode = "4821",
                                    role = "passenger",
                                    driverPhase = "departed",
                                    passengerStatus = null,
                                    bookingStatus = "confirmed",
                                    arrivalVerified = false,
                                    from = "Баймак",
                                    to = "Сибай",
                                    fromPoint = Point(52.5933, 58.3228),
                                    toPoint = Point(52.7181, 58.6658),
                                    liveBookingId = null,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun holdIfRequested() {
        val holdMs = InstrumentationRegistry.getArguments()
            .getString("holdMs")
            ?.toLongOrNull()
            ?.coerceIn(0L, 60_000L)
            ?: 0L
        Thread.sleep(holdMs)
    }
}
