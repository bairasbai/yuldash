package com.yuldash.app

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.TAXI_CONSENT_LOCAL_API
import com.yuldash.app.data.TAXI_CONSENT_PENDING
import com.yuldash.app.data.TAXI_CONSENT_PREFS
import com.yuldash.app.data.TaxiQuoteConsentApi
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TaxiConsentTerminalReturnTest {
    @get:Rule val compose = createComposeRule()

    @Test fun newOrderFromCancelledCardReturnsToOtherAttemptsBeforeDisabledCityOrHistory() {
        assumeTrue(TaxiQuoteConsentApi.requested)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val keys = (1..3).map { "20000000-0000-4000-8000-00000000000$it" }
        val paths = CopyOnWriteArrayList<String>()
        val lookedUp = CopyOnWriteArrayList<String>()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                paths.add("${request.method} $path")
                return when (path) {
                    "/instant/orders/result/v1" -> {
                        lookedUp.add(JSONObject(request.body.readUtf8()).getString("request_key"))
                        MockResponse().setBody("""{"schema":"taxi-consent-result-v1","state":"committed","order_id":77,"initial_payable_kop":45000,"next":"fetch_order"}""")
                    }
                    "/instant/orders/77" -> MockResponse().setBody(ConsentFixture.orderJson("cancelled").toString())
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val mounted = mutableStateOf(false)
        ApiClient.resetForTest()
        server.start(InetAddress.getByName("127.0.0.1"), 8000)
        try {
            ApiClient.testBaseUrl = TAXI_CONSENT_LOCAL_API
            ApiClient.init(context)
            ApiClient.saveToken("synthetic.eyJzdWIiOiIxNyJ9.signature")
            val owner = requireNotNull(TaxiQuoteConsentApi.owner())
            assertEquals(TAXI_CONSENT_LOCAL_API, owner.binding.origin)
            FeatureFlags.setForTest(FeatureSet(emptySet(), known = true, fetchedAtMs = 1))
            val rows = JSONArray().apply {
                keys.forEach { key -> put(JSONObject().put("v", 2).put("scope", owner.binding.scope)
                    .put("origin", owner.binding.origin).put("request_key", key).put("close_requested", false)) }
            }
            val prefs = context.getSharedPreferences(TAXI_CONSENT_PREFS, Context.MODE_PRIVATE)
            assertTrue(prefs.edit().putString(TAXI_CONSENT_PENDING, rows.toString()).commit())
            mounted.value = true
            compose.setContent {
                if (mounted.value) CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                    InstantOrderScreen(onBack = {}, onLoginRequired = { error("Synthetic session lost") },
                        embedded = true, renderNativeMap = false)
                }
            }
            waitUntil { compose.onAllNodesWithTag("taxiConsentAttemptPicker").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("taxiConsentAttemptPicker").performClick()
            compose.onNodeWithTag("taxiConsentAttemptOption3").performClick()
            compose.onNodeWithTag("taxiConsentCheckResult").performClick()
            waitUntil {
                compose.onAllNodesWithText("Новый заказ").fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithTag("taxiConsentAttemptPicker").fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithText("Новый заказ").performClick()
            waitUntil { compose.onAllNodesWithTag("taxiConsentAttemptPicker").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("taxiConsentAttemptPicker").performClick()
            compose.onNodeWithTag("taxiConsentAttemptOption1").assertIsDisplayed().assertTextEquals("Попытка 1")
            compose.onNodeWithTag("taxiConsentAttemptOption2").assertIsDisplayed().assertTextEquals("Попытка 2")
            compose.onNodeWithTag("taxiConsentAttemptOption3").assertDoesNotExist()
            assertEquals(keys.take(2), TaxiQuoteConsentApi.markers(context).all(owner.binding).map { it.slot })
            assertEquals(listOf(keys.last()), lookedUp.toList())
            assertEquals(listOf("POST /instant/orders/result/v1", "GET /instant/orders/77"),
                paths.filter { it.substringAfter(' ').startsWith("/instant/") })
        } finally {
            compose.runOnIdle { mounted.value = false }
            ApiClient.logout()
            ApiClient.resetForTest()
            server.shutdown()
        }
    }

    private fun waitUntil(ready: () -> Boolean) {
        compose.waitUntil(15_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
            ready()
        }
    }
}
