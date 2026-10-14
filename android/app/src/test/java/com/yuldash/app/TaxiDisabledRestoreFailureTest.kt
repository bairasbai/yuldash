package com.yuldash.app

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList

/** Real screen/HTTP: unavailable recovery must not reopen a disabled new taxi. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TaxiDisabledRestoreFailureTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var server: MockWebServer
    private val mounted = mutableStateOf(true)
    private val requests = CopyOnWriteArrayList<String>()
    @Volatile private var restoreExisting = false
    private var backCount = 0

    @Before fun setup() {
        ApiClient.resetForTest()
        ApiClient.init(ApplicationProvider.getApplicationContext<Context>())
        ApiClient.saveToken("local-offline-taxi")
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    requests += "${request.method} $path"
                    return when {
                        path == "/instant/availability" -> json("{}", 503)
                        path == "/instant/orders/mine" && restoreExisting -> json("""{"items":[$existing]}""")
                        path == "/instant/orders/mine" -> json("{}", 503)
                        path == "/instant/orders/71" -> json(existing)
                        else -> json("{}", 404)
                    }
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 1000
    }

    @After fun cleanup() {
        compose.runOnIdle { mounted.value = false }
        ApiClient.logout()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server.shutdown()
    }

    @Test fun knownOffShowsSoonAndCanReturnToPooling() {
        mount(FeatureSet(emptySet(), known = true, fetchedAtMs = 1))
        waitFor("Скоро в Юлдаше")
        compose.onNodeWithText("Проверить мой заказ").assertIsDisplayed()
        compose.onAllNodesWithText("Не удалось проверить заказ").assertCountEquals(0)
        compose.onNodeWithText("Найти попутку").performClick()
        compose.runOnIdle { assertEquals(1, backCount) }
        assertNoNewTaxi()
    }

    @Test fun unknownOfflineAlsoShowsSoon() {
        mount(FeatureSet.UNKNOWN)
        waitFor("Скоро в Юлдаше")
        compose.onNodeWithText("Проверить мой заказ").assertIsDisplayed()
        compose.onAllNodesWithText("Не удалось проверить заказ").assertCountEquals(0)
        assertNoNewTaxi()
    }

    @Test fun enabledTaxiKeepsItsRecoveryError() {
        mount(FeatureSet(setOf(Feature.Taxi), known = true, fetchedAtMs = 1))
        waitFor("Не удалось проверить заказ")
        compose.onNodeWithText("Повторить").assertIsDisplayed()
        compose.onAllNodesWithText("Скоро в Юлдаше").assertCountEquals(0)
        assertNoNewTaxi()
    }

    @Test fun retryFromClosedTaxiRestoresAnExistingOrderWithoutCreatingOne() {
        mount(FeatureSet(emptySet(), known = true, fetchedAtMs = 1))
        waitFor("Скоро в Юлдаше")
        val earlierReads = requests.count { it == "GET /instant/orders/mine" }
        restoreExisting = true
        compose.onNodeWithText("Проверить мой заказ").performClick()
        waitFor("Тестовый водитель едет")
        assertTrue(requests.count { it == "GET /instant/orders/mine" } > earlierReads)
        compose.onAllNodesWithText("Скоро в Юлдаше").assertCountEquals(0)
        assertNoNewTaxi()
    }

    private fun mount(features: FeatureSet) {
        FeatureFlags.setForTest(features)
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                InstantOrderScreen(onBack = { backCount++ }, onLoginRequired = {}, embedded = true,
                    payMethod = PayMethods.CASH, renderNativeMap = false)
            }
        }
    }

    private fun waitFor(text: String) {
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun assertNoNewTaxi() {
        assertTrue("Actual restoration request required", requests.contains("GET /instant/orders/mine"))
        assertFalse("No new taxi action: $requests", requests.any { it.startsWith("POST ") })
    }

    private fun json(body: String, status: Int = 200) = MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "application/json").setBody(body)

    private val existing = """{"id":71,"status":"accepted","role":"passenger","from_lat":54.735,"from_lng":55.958,"to_lat":54.751,"to_lng":56.001,"from_text":"Моя позиция","to_text":"Уфа, Ленина 10","category":"standard","payment_method":"cash","price_estimate":250,"price_final":250,"driver_name":"Тестовый водитель","driver_car":"Тестовая машина","driver_plate":"А001АА"}"""
}
