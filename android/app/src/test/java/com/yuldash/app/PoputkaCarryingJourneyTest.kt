package com.yuldash.app

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList

/** Isolates the non-professional carrier path; parcel creation is not covered here. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PoputkaCarryingJourneyTest {
    @get:Rule val compose = createComposeRule()
    private val mounted = mutableStateOf(true)
    private lateinit var server: MockWebServer
    private val requests = CopyOnWriteArrayList<String>()
    private val transitions = CopyOnWriteArrayList<String>()
    @Volatile private var status = "created"
    private var pendingApplication = false

    @Before fun setup() {
        ApiClient.resetForTest()
        ApiClient.init(ApplicationProvider.getApplicationContext<Context>())
        ApiClient.saveToken("local-poputka-carrier")
        LocationPrefs.lastLat = null
        LocationPrefs.lastLng = null
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    requests.add("${request.method} $path")
                    return when (path) {
                        "/courier/application" -> json(if (pendingApplication) """{"application":{"id":7,"status":"pending"}}""" else """{"application":null}""")
                        "/parcels/available" -> json("""{"items":[${if (status == "created") parcel() else ""}]}""")
                        "/parcels/81/accept" -> { status = "accepted"; json(parcel()) }
                        "/parcels/carrying" -> json("""{"items":[${if (status != "created") parcel() else ""}]}""")
                        "/parcels/81/status" -> {
                            val next = JSONObject(request.body.readUtf8()).getString("status")
                            if (status != "accepted" || next != "in_transit") return json("{}", 409)
                            transitions.add(next)
                            status = next
                            json(parcel())
                        }
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

    @Test fun carrierWithoutApplicationCanFindAcceptedParcelAndPickItUp() = acceptAndPickUp()

    @Test fun pendingProfessionalApplicationDoesNotHidePoputkaParcel() {
        pendingApplication = true
        acceptAndPickUp()
    }

    private fun acceptAndPickUp() {
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                CourierScreen(onBack = {}, onBecomeCourier = {})
            }
        }
        waitForText("Взять заказ")
        scrollTo("Взять заказ")
        compose.onNodeWithText("Взять заказ").performClick()
        compose.waitUntil(15000) { status == "accepted" }
        assertEquals(1, requests.count { it == "POST /parcels/81/accept" })
        // Accept already succeeded; its toast directs the user to this tab.
        compose.onNodeWithText("Везу").assertIsDisplayed().performClick()
        waitForText("В пути")
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasText("В пути") and hasClickAction())
        compose.onNode(hasText("В пути") and hasClickAction()).performClick()
        waitForText("Забрал, еду")
        compose.onNodeWithText("Забрал, еду").performClick()
        compose.waitUntil(15000) { status == "in_transit" }
        assertEquals(listOf("in_transit"), transitions.toList())
        assertTrue(requests.contains("GET /parcels/carrying"))
        assertFalse(requests.any { it.endsWith("/courier/me") || it.endsWith("/courier/available") })
    }

    private fun scrollTo(label: String) {
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasText(label))
    }
    private fun waitForText(label: String) {
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()
        }
    }
    private fun parcel() = """{"id":81,"sender_id":3,"courier_id":${if (status == "created") "null" else "9"},"from_city":"Пункт А","to_city":"Пункт Б","size":"small","description":"Тестовая коробка","receiver_name":"Тестовый получатель","sender_name":"Тестовый отправитель","status":"$status","delivery_type":"poputka","price":250,"fee_kop":2500,"created_at":"2026-09-24T09:00:00Z"}"""
    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code)
        .setHeader("Content-Type", "application/json").setBody(body)
}
