package com.yuldash.app

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.ui.theme.YuldashTheme
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Base64

/**
 * Мелкие находки приёмки 18.10:
 *  - п.12 — водитель на своей поездке видел «Забронировать место» и «Написать»;
 *  - п.13 — проверенный водитель в профиле был «Пассажир» и без «Проверен».
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RideshareSmallFixesTest {
    @get:Rule val compose = createComposeRule()
    private val mounted = mutableStateOf(true)
    private lateinit var server: MockWebServer

    /** Вход как пользователь 77: myUserId() читает `sub` из JWT. */
    private fun tokenFor(userId: Int): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        return "${enc.encodeToString("{}".toByteArray())}.${enc.encodeToString("{\"sub\":\"$userId\"}".toByteArray())}.sig"
    }

    @Before fun setup() {
        ApiClient.resetForTest(); ApiClient.init(ApplicationProvider.getApplicationContext<Context>())
        ApiClient.saveToken(tokenFor(77))
        server = MockWebServer().apply {
            dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = MockResponse()
                    .setHeader("Content-Type", "application/json").setBody("{\"items\":[]}")
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 1000
    }

    @After fun cleanup() { compose.runOnIdle { mounted.value = false }; ApiClient.logout(); ApiClient.resetForTest(); ApiClient.testTimeoutMs = null; server.shutdown() }

    private fun ride(driverId: Int) = Ride("42", "Уфа", "Сибай", "завтра", driver = "Ринат", car = "Lada", price = 300,
        seats = 2, rating = 5.0, verified = true, boosted = false, driverId = driverId)

    private fun details(driverId: Int) {
        compose.setContent { if (mounted.value) CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
            YuldashTheme { BookingScreen(ride(driverId), null, emptyList(), emptyMap(), {}, {}, {}, {},
                onConfirmRide = { _, _, _, _, _ -> }) }
        } }
        compose.waitForIdle()
    }

    @Test fun driverOnOwnRideSeesWhereBookingsLiveInsteadOfBookAndWrite() {
        details(driverId = 77)
        compose.onAllNodesWithText("Забронировать место").assertCountEquals(0)
        compose.onAllNodesWithText("Написать").assertCountEquals(0)
        compose.onNodeWithText("Это твоя поездка", substring = true).assertIsDisplayed()
    }

    @Test fun someoneElsesRideIsStillBookable() {
        details(driverId = 5)
        compose.onNodeWithText("Забронировать место").assertIsDisplayed()
        compose.onAllNodesWithText("Это твоя поездка", substring = true).assertCountEquals(0)
    }

    @Test fun ownRideNeedsAKnownDriverAndALoggedInUser() {
        assertFalse(isOwnRide(ride(driverId = 0), myId = 0))
        assertFalse(isOwnRide(ride(driverId = 77), myId = null))
        assertTrue(isOwnRide(ride(driverId = 77), myId = 77))
    }

    @Test fun verifiedDriverIsADriverWithAMarkInBothLanguages() {
        compose.setContent { androidx.compose.foundation.layout.Column {
            for (lang in listOf(AppLanguage.Ru, AppLanguage.Ba)) CompositionLocalProvider(LocalAppLanguage provides lang) {
                Row { Text(roleLabel("passenger", verifiedDriver = true)); ProfileVerifiedMark() }
            }
        } }
        for (text in listOf("Водитель", "Проверен", "Йөрөтөүсе", "Тикшерелгән")) compose.onNodeWithText(text).assertIsDisplayed()
    }

    @Test fun roleLabelKeepsPassengersAndAdmins() {
        compose.setContent { CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
            Row { Text(roleLabel("passenger")); Text(" / "); Text(roleLabel("admin", verifiedDriver = true)) }
        } }
        compose.onNodeWithText("Пассажир").assertIsDisplayed()
        compose.onNodeWithText("Администратор").assertIsDisplayed()
    }
}
