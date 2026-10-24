package com.yuldash.app

import android.app.Application
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.parseBookingMineDto
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * «Мои поездки»: отказ водителя подписан «Отклонена», а не «Отменена» (приёмка PR210).
 *
 * Экран брони уже говорил «Водитель отклонил бронь», а список поездок того же человека — «Отменена»:
 * будто он сам передумал или сорвалась поездка. `/bookings/mine` теперь отдаёт `declined`. Заодно
 * башкирский: «Отменена» здесь была «Кире ҡағылды» — а это и есть «Отклонена»; теперь «Кире алынды».
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MyTripsDeclinedLabelTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var server: MockWebServer

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ApiClient.resetForTest()
        ApiClient.init(context)
        ApiClient.saveToken("local-passenger")
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = if (request.requestUrl!!.encodedPath == "/bookings/mine") """[
                        {"id":41,"ride_id":7,"status":"cancelled","declined":true,"seats":1,"price":500,"from_city":"Баймаҡ","to_city":"Өфө","depart_at":"2030-01-02T10:00:00+05:00","driver_name":"Ильдар"},
                        {"id":42,"ride_id":8,"status":"cancelled","declined":false,"seats":1,"price":500,"from_city":"Сибай","to_city":"Учалы","depart_at":"2030-01-03T10:00:00+05:00","driver_name":"Айгуль"}
                    ]""" else """{"items":[]}"""
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 1000
    }

    @After fun cleanup() {
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server.shutdown()
    }

    private fun showHistory(language: AppLanguage, historyTab: String) {
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides language) {
                RidesScreen(
                    rides = emptyList(), ads = emptyList(), adStats = emptyMap(),
                    onBookRide = {}, onOpenBookingDetails = { _, _ -> }, onOpenActiveTrip = { _, _ -> },
                    onMessage = {}, onShareRide = {}, onBoost = {}, onCreateRequest = {},
                    onAdImpression = {}, onAdClick = {},
                )
            }
        }
        compose.onAllNodesWithText(historyTab).onFirst().performClick()
        // Карточка показывает маршрут (имя водителя — нет): ждём обе брони.
        compose.waitUntil(5000) { compose.onAllNodesWithText("→", substring = true).fetchSemanticsNodes().size >= 2 }
    }

    @Test fun declinedBookingSaysDeclined_ru() {
        showHistory(AppLanguage.Ru, "История")
        compose.onAllNodesWithText("Отклонена", substring = true).assertCountEquals(1)
        compose.onAllNodesWithText("Отменена", substring = true).assertCountEquals(1)
    }

    @Test fun declinedBookingSaysDeclined_ba() {
        showHistory(AppLanguage.Ba, "Тарих")
        compose.onAllNodesWithText("Кире ҡағылған", substring = true).assertCountEquals(1)
        compose.onAllNodesWithText("Кире алынды", substring = true).assertCountEquals(1)
        compose.onAllNodesWithText("Кире ҡағылды", substring = true).assertCountEquals(0)
    }

    @Test fun listParsesTheDeclinedFlagAndOldServerMeansNo() {
        assertTrue(parseBookingMineDto(JSONObject("""{"id":41,"status":"cancelled","declined":true}""")).declined)
        assertFalse(parseBookingMineDto(JSONObject("""{"id":41,"status":"cancelled"}""")).declined)
    }
}
