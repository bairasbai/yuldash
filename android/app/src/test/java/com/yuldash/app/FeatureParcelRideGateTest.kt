package com.yuldash.app

import android.app.Application
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.toRideDto
import com.yuldash.app.ui.theme.YuldashTheme
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Реальные entry points: восстановленный draft и сохранённый OnClick не создают доставку. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FeatureParcelRideGateTest {
    @get:Rule val compose = createComposeRule()
    private val mounted = mutableStateOf(true)
    private lateinit var server: MockWebServer
    private fun flags(on: Boolean) = FeatureFlags.setForTest(FeatureSet(if (on) setOf(Feature.Parcels) else emptySet(), true, 0))
    @Before fun setup() {
        ApiClient.resetForTest(); ApiClient.init(ApplicationProvider.getApplicationContext<Context>())
        ApiClient.saveToken("local-parcel-ride-owner")
        server = MockWebServer().apply {
            dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = MockResponse()
                    .setHeader("Content-Type", "application/json").setBody("{\"items\":[]}")
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 1000
        flags(false)
    }
    @After fun cleanup() { compose.runOnIdle { mounted.value = false }; ApiClient.resetForTest(); ApiClient.testTimeoutMs = null; server.shutdown() }
    private fun ride(category: String = "regular") = Ride("42", "Уфа", "Сибай", "завтра", driver = "Ринат", car = "Lada", price = 300, seats = 2, rating = 5.0, verified = true, boosted = false).copy(category = category)
    private fun booking(category: String, id: Int? = null, confirmed: () -> Unit = {}) {
        compose.setContent { if (mounted.value) CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
            YuldashTheme { BookingScreen(ride(category), id, emptyList(), emptyMap(), {}, {}, {}, {},
                onConfirmRide = { _, _, _, _, _ -> confirmed() }) }
        } }
    }
    @Test fun parcelNewBookingIsSoon() { booking("parcel"); compose.onNodeWithText("Скоро в Юлдаше").assertIsDisplayed(); compose.onNodeWithText("Забронировать место").assertDoesNotExist() }
    @Test fun cargoNewBookingIsSoon() { booking("cargo"); compose.onNodeWithText("Скоро в Юлдаше").assertIsDisplayed(); compose.onNodeWithText("Забронировать место").assertDoesNotExist() }
    @Test fun existingCargoBookingCanStillOpen() { booking("cargo", 73); compose.onNodeWithText("Открыть поездку").assertIsDisplayed(); compose.onNodeWithText("Скоро в Юлдаше").assertDoesNotExist() }
    @Test fun retainedBookingClickDoesNotConfirmAfterFlagsClose() {
        flags(true); var confirms = 0; booking("parcel", confirmed = { confirms++ })
        val click = compose.onNodeWithText("Забронировать место").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { flags(false); click() }
        assertEquals(0, confirms)
        compose.onNodeWithText("Скоро в Юлдаше").assertIsDisplayed()
    }
    @Test fun savedParcelDraftRemainsSoonAfterRestoration() {
        flags(true)
        val restore = StateRestorationTester(compose)
        restore.setContent { if (mounted.value) CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
            YuldashTheme { CreateRideScreen({}, { fail("closed draft published") }) }
        } }
        compose.onNodeWithText("Посылка").performScrollTo().performClick()
        compose.runOnIdle { flags(false) }
        compose.onNodeWithText("Скоро в Юлдаше").assertIsDisplayed()
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Скоро в Юлдаше").assertIsDisplayed()
    }
    @Test fun dtoCategorySurvivesMappingWithoutChangingRegularDefault() {
        for (category in listOf("parcel", "cargo", "regular", "urgent", "hospital")) {
            val dto = JSONObject("{\"id\":42,\"category\":\"$category\"}").toRideDto()
            assertEquals(category, dto.toUiRide().category)
        }
        assertEquals("regular", Ride("42", "Уфа", "Сибай", "завтра", driver = "Ринат", car = "Lada", price = 300, seats = 2, rating = 5.0, verified = true, boosted = false).category)
    }
    @Test fun onlyExactDeliveryCategoriesAreClosed() {
        for (category in listOf("regular", "urgent", "hospital", "unknown", "")) assertFalse(rideCategoryClosed(category))
        for (category in listOf("parcel", "cargo")) { assertTrue(rideCategoryClosed(category)); flags(true); assertFalse(rideCategoryClosed(category)); flags(false) }
    }
}
