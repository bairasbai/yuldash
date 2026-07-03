package com.yuldash.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
 * «В глубину» по экрану активной поездки: чистые карточки статуса/поездки вынесены из
 * `ActiveTripScreen` в `internal`-композаблы (шапка маршрута, live-баннер «водитель выехал/
 * подъезжает», плашка кода посадки, кнопки статуса водитель/пассажир, строка «поделиться с
 * близким»). Все берут примитивы/лямбды и рисуют только Text/Icon/Row/Column/Surface/Card —
 * без карты/сокета/чата/MediaPlayer/сети → покрываем на JVM через Robolectric.
 *
 * Двуязычие считается внутри через appText → оборачиваем в CompositionLocalProvider(LocalAppLanguage).
 * Тексты в ассертах — ТОЧНО как в BookingActiveTripScreen.kt. Анимации (`appearIn`) навешивает
 * экран снаружи, здесь их нет → autoAdvance не нужен. Заголовок класса — как в RidesDeepContentTest.
 *
 * Карта/чат/поллинг и уже покрытые в BookingActiveTripContentTest подписи карты тут НЕ дублируем.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookingActiveTripContentDeepTest {

    @get:Rule
    val composeRule = createComposeRule()

    // --- TripRouteHeaderCard: шапка «откуда → куда», водитель, время ---

    @Test
    fun routeHeader_showsRouteDriverAndTime() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                TripRouteHeaderCard(from = "Уфа", to = "Баймак", driver = "Ринат", time = "08:30")
            }
        }
        composeRule.onNodeWithText("Уфа  →  Баймак").assertIsDisplayed()
        composeRule.onNodeWithText("Ринат").assertIsDisplayed()
        composeRule.onNodeWithText("08:30").assertIsDisplayed()
    }

    @Test
    fun routeHeader_nullValues_showDashAndDefaultDriver() {
        // Пустой рейс (демо/нет данных): маршрут «— → —», водитель — дефолтная надпись, время скрыто.
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                TripRouteHeaderCard(from = null, to = null, driver = null, time = null)
            }
        }
        composeRule.onNodeWithText("—  →  —").assertIsDisplayed()
        composeRule.onNodeWithText("Водитель").assertIsDisplayed()
    }

    // --- DriverApproachingBanner: live-баннер пассажиру (выехал / подъезжает) ---

    @Test
    fun approachingBanner_departed_showsDepartedText() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                DriverApproachingBanner(arriving = false)
            }
        }
        composeRule.onNodeWithText("Водитель выехал к вам").assertIsDisplayed()
    }

    @Test
    fun approachingBanner_arriving_showsArrivingText() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                DriverApproachingBanner(arriving = true)
            }
        }
        composeRule.onNodeWithText("Водитель подъезжает").assertIsDisplayed()
    }

    @Test
    fun approachingBanner_bashkir_showsBashkirText() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                DriverApproachingBanner(arriving = true)
            }
        }
        composeRule.onNodeWithText("Водитель яҡынлаша").assertIsDisplayed()
    }

    // --- BoardingCodeCard: плашка кода посадки ---

    @Test
    fun boardingCode_russian_showsTitleHintAndCode() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                BoardingCodeCard(code = "7421")
            }
        }
        composeRule.onNodeWithText("Код посадки").assertIsDisplayed()
        composeRule.onNodeWithText("Назовите водителю — он сверит. Это та самая машина.").assertIsDisplayed()
        composeRule.onNodeWithText("7421").assertIsDisplayed()
    }

    @Test
    fun boardingCode_bashkir_showsTitle() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                BoardingCodeCard(code = "0042")
            }
        }
        composeRule.onNodeWithText("Ултырыу коды").assertIsDisplayed()
        composeRule.onNodeWithText("0042").assertIsDisplayed()
    }

    // --- TripStatusButtons: кнопки статуса (водитель / пассажир) ---

    @Test
    fun statusButtons_driver_showsDriverLabels() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                TripStatusButtons(role = "driver", selectedStatus = null, onStatus = {})
            }
        }
        composeRule.onNodeWithText("Я выехал").assertIsDisplayed()
        composeRule.onNodeWithText("Подъезжаю").assertIsDisplayed()
        composeRule.onNodeWithText("Завершить").assertIsDisplayed()
        // Пассажирских ярлыков у водителя нет.
        composeRule.onNodeWithText("Я сел").assertDoesNotExist()
    }

    @Test
    fun statusButtons_passenger_showsPassengerLabels() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                TripStatusButtons(role = "passenger", selectedStatus = null, onStatus = {})
            }
        }
        composeRule.onNodeWithText("Я сел").assertIsDisplayed()
        composeRule.onNodeWithText("Доехал").assertIsDisplayed()
        composeRule.onNodeWithText("Завершить").assertIsDisplayed()
        composeRule.onNodeWithText("Я выехал").assertDoesNotExist()
    }

    @Test
    fun statusButtons_passenger_bashkirLabels() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                TripStatusButtons(role = "passenger", selectedStatus = null, onStatus = {})
            }
        }
        composeRule.onNodeWithText("Ултырҙым").assertIsDisplayed()
        composeRule.onNodeWithText("Барып еттем").assertIsDisplayed()
    }

    @Test
    fun statusButtons_click_firesOnStatusWithCode() {
        var fired: String? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                TripStatusButtons(role = "passenger", selectedStatus = null, onStatus = { fired = it })
            }
        }
        assertNull(fired)
        composeRule.onNodeWithText("Доехал").performClick()
        assertEquals("arrived", fired)
    }

    @Test
    fun statusButtons_driverDone_firesDoneCode() {
        var fired: String? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                TripStatusButtons(role = "driver", selectedStatus = null, onStatus = { fired = it })
            }
        }
        composeRule.onNodeWithText("Завершить").performClick()
        assertEquals("done", fired)
    }

    // --- ShareTripRow: строка «поделиться поездкой с близким» ---

    @Test
    fun shareRow_russian_showsTitleAndSubtitle() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                ShareTripRow(onClick = {})
            }
        }
        composeRule.onNodeWithText("Поделиться поездкой с близким").assertIsDisplayed()
        composeRule.onNodeWithText("Близкий будет видеть статус поездки").assertIsDisplayed()
    }

    @Test
    fun shareRow_bashkir_showsTitle() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                ShareTripRow(onClick = {})
            }
        }
        composeRule.onNodeWithText("Сәфәрҙе яҡының менән уртаҡлашыу").assertIsDisplayed()
    }

    @Test
    fun shareRow_click_firesCallback() {
        var fired = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                ShareTripRow(onClick = { fired = true })
            }
        }
        assertFalse(fired)
        composeRule.onNodeWithText("Поделиться поездкой с близким").performClick()
        assertTrue(fired)
    }
}
