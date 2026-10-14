package com.yuldash.app

import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.ViewGroup
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.ui.theme.YuldashTheme
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.util.concurrent.CopyOnWriteArrayList

/** Настоящие навигация и кнопки вручения: новые направления OFF, старая посылка доезжает. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FeatureDisabledDeliveryTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var server: MockWebServer
    private val mounted = mutableStateOf(true)
    private val requests = CopyOnWriteArrayList<String>()
    @Volatile private var status = "in_transit"
    @Volatile private var deliveredCode: String? = null

    @Before fun setup() {
        ApiClient.resetForTest()
        ApiClient.init(ApplicationProvider.getApplicationContext<Context>())
        ApiClient.saveToken("wave1-local-carrier")
        FeatureFlags.setForTest(FeatureSet(emptySet(), known = true, fetchedAtMs = 0))
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    requests.add("${request.method} $path")
                    return when {
                        path == "/parcels/carrying" -> json("""{"items":[${parcel()}]}""")
                        path == "/parcels/mine" -> json("""{"items":[]}""")
                        path == "/parcels/81/status" && request.method == "POST" -> {
                            val body = JSONObject(request.body.readUtf8())
                            deliveredCode = body.optString("code")
                            if (body.optString("status") != "delivered" || deliveredCode != "317204") return json("{}", 422)
                            status = "delivered"
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

    @Test fun closedCourierRouteCompletesExistingParcelInRussian() = deliver(AppLanguage.Ru, parcels = false)
    @Test fun closedCourierRouteCompletesExistingParcelInBashkir() = deliver(AppLanguage.Ba, parcels = false)
    @Test fun closedParcelsRouteKeepsCarrierTab() = deliver(AppLanguage.Ru, parcels = true)

    private fun deliver(language: AppLanguage, parcels: Boolean) {
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(LocalAppLanguage provides language) {
                YuldashTheme {
                    OrdersNav(
                        screen = if (parcels) Screen.Parcels else Screen.Courier,
                        instantTripOrderId = 0, instantChatOrderId = 0, taxiReceiptOrderId = 0,
                        carPhotoMode = "courier", parcelChatId = 0, parcelChatPeerIsCourier = false,
                        parcelChatStatus = "", onBack = {}, onOpen = {}, onOpenTaxiChat = {},
                        onOpenReceipt = {}, onOpenCarPhoto = {},
                    )
                }
            }
        }
        if (parcels) compose.onNodeWithText("Везу").performClick()
        val delivered = if (language == AppLanguage.Ru) "Доставлено" else "Тапшырылды"
        waitFor(delivered, clickable = true)
        val previous = ShadowDialog.getLatestDialog()
        scrollClick(delivered)
        // Тот же тестовый обход WRAP_CONTENT, что в ParcelCreationDeliveryJourneyTest.
        for (attempt in 0 until 10) {
            val dialog = ShadowDialog.getLatestDialog()
            if (dialog != null && dialog !== previous && dialog.isShowing) {
                dialog.window!!.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                break
            }
            compose.mainClock.advanceTimeByFrame()
        }
        val codeLabel = if (language == AppLanguage.Ru) "Код от получателя" else "Алыусы коды"
        val confirm = if (language == AppLanguage.Ru) "Подтвердить вручение" else "Тапшырыуҙы раҫлау"
        waitFor(codeLabel)
        compose.onNodeWithText(codeLabel).performTextReplacement("317204")
        compose.onNodeWithText(confirm).performClick()
        compose.waitUntil(15000) { status == "delivered" }
        assertEquals("317204", deliveredCode)
        assertTrue(requests.contains("GET /parcels/carrying"))
        assertTrue(requests.contains("POST /parcels/81/status"))
        assertFalse(requests.any { it.endsWith("/parcels/available") || it.endsWith("/courier/me") ||
                it.endsWith("/courier/available") || it.endsWith("/courier/online") || it == "POST /parcels" })
    }

    private fun waitFor(label: String, clickable: Boolean = false) {
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            val match = if (clickable) hasText(label) and hasClickAction() and isEnabled() else hasText(label)
            compose.onAllNodes(match).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun scrollClick(label: String) {
        val match = hasText(label) and hasClickAction()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).performScrollToNode(match)
        compose.onNode(match).performClick()
    }

    private fun parcel() = JSONObject().apply {
        put("id", 81); put("sender_id", 3); put("courier_id", 9); put("status", status)
        put("from_city", "Пункт А"); put("to_city", "Пункт Б"); put("from_address", "У ворот"); put("to_address", "У школы")
        put("size", "small"); put("description", "Тестовая коробка"); put("delivery_type", "poputka")
        put("receiver_name", "Тестовый получатель"); put("receiver_phone", "+79990000001")
        put("created_at", "2026-10-14T09:00:00Z")
    }.toString()
    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code)
        .setHeader("Content-Type", "application/json").setBody(body)
}
