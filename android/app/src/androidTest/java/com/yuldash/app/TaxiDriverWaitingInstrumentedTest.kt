package com.yuldash.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yandex.mapkit.geometry.Point
import com.yuldash.app.data.DemandZoneDto
import com.yuldash.app.data.InstantZoneDto
import com.yuldash.app.data.TaxiWorkdayDto
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Живые C и A на Pixel: настоящая MapKit-карта, круги спроса и реальный жест шторки. */
@RunWith(AndroidJUnit4::class)
class TaxiDriverWaitingInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun waiting_opensCalmCompactMap() {
        render()

        composeRule.onNodeWithText("На линии · ищем заказ").assertIsDisplayed()
        composeRule.onNodeWithText("Сегодня 2 480 ₽ · 4 поездки").assertIsDisplayed()
        composeRule.onNodeWithText("Смена 2 ч 18 мин · отдых через 5 ч 42 мин").assertIsDisplayed()
        composeRule.onNodeWithText("Зона: Уфа").assertIsDisplayed()
        composeRule.onNodeWithText("Уйти с линии").assertIsDisplayed()
        composeRule.waitForIdle()
        holdIfRequested()
    }

    @Test
    fun waiting_swipeUpOpensDetailedShift() {
        render()
        composeRule.onNodeWithTag("taxiSheetDragArea").performTouchInput {
            swipeUp(durationMillis = 700)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Ищем заказ рядом").assertIsDisplayed()
        composeRule.onNodeWithText("Спрос выше рядом").assertIsDisplayed()
        composeRule.onNodeWithText("2 ч 18 мин").assertIsDisplayed()
        composeRule.onNodeWithText("5 ч 42 мин").assertIsDisplayed()
        composeRule.onNodeWithText("2 480 ₽").assertIsDisplayed()
        composeRule.onNodeWithText("Уйти с линии").assertIsDisplayed()
        composeRule.waitForIdle()
        holdIfRequested()
    }

    @Test
    fun waiting_keepsBashkirCompactStateReadable() {
        render(language = AppLanguage.Ba)

        composeRule.onNodeWithText("Линияла · заказ эҙләйбеҙ").assertIsDisplayed()
        composeRule.onNodeWithText("Бөгөн 2 480 ₽ · 4 сәфәр").assertIsDisplayed()
        composeRule.onNodeWithText("Смена 2 сәғ 18 мин · ялға тиклем 5 сәғ 42 мин").assertIsDisplayed()
        composeRule.onNodeWithText("Зона: Уфа").assertIsDisplayed()
        composeRule.onNodeWithText("Линиянан сығыу").assertIsDisplayed()
    }

    private fun render(language: AppLanguage = AppLanguage.Ru) {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides language) {
                InstantDriverWaitingScreen(
                    locationFix = TaxiLocationFix(
                        point = Point(54.7351, 55.9587),
                        accuracyMeters = 8f,
                        headingDegrees = 32f,
                        deviceHeadingDegrees = 32f,
                    ),
                    demandZones = listOf(
                        DemandZoneDto(54.7358, 55.9578, weight = 1.0, requests = 6),
                        DemandZoneDto(54.7442, 55.9860, weight = 0.58, requests = 3),
                    ),
                    demandLoading = false,
                    demandError = false,
                    workday = TaxiWorkdayDto(
                        day = "2026-08-31",
                        secondsOnline = 2 * 3600 + 18 * 60,
                        limitSec = 8 * 3600,
                        remainingSec = 5 * 3600 + 42 * 60,
                        limitHours = 8,
                        blocked = false,
                        unlockAt = null,
                        returnRideUsed = false,
                        earningsToday = 2_700,
                        grossTodayKop = 270_000,
                        feeTodayKop = 22_000,
                        netTodayKop = 248_000,
                        ordersToday = 4,
                    ),
                    zone = InstantZoneDto(
                        workZone = "city",
                        workCity = "Уфа",
                        workDirectionId = null,
                        workDirection = null,
                    ),
                    connectionLost = false,
                    onGoOffline = {},
                )
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
