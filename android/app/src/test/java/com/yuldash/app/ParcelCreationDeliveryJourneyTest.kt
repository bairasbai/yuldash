package com.yuldash.app

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.ui.theme.YuldashTheme
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

/** Real sender/carrier screens; account switching and server storage are supplied by the stand. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ParcelCreationDeliveryJourneyTest {
    @get:Rule val compose = createComposeRule()
    private val page = mutableIntStateOf(0)
    private lateinit var server: MockWebServer
    @Volatile private var created: JSONObject? = null
    @Volatile private var status = ""
    @Volatile private var rejectFirstCreate = false
    private val requests = CopyOnWriteArrayList<String>()
    private val transitions = CopyOnWriteArrayList<String>()
    private val deliveredCodes = CopyOnWriteArrayList<String>()
    private val serverCode = "317204"

    @Before fun setup() {
        ApiClient.resetForTest()
        ApiClient.init(ApplicationProvider.getApplicationContext<Context>())
        ApiClient.saveToken("local-parcel-sender")
        LocationPrefs.lastLat = null
        LocationPrefs.lastLng = null
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    requests.add("${request.method} $path")
                    val sender = request.getHeader("Authorization") == "Bearer local-parcel-sender"
                    val carrier = request.getHeader("Authorization") == "Bearer local-parcel-carrier"
                    if (!sender && !carrier) return json("{}", 401)
                    return when {
                        path == "/parcels" && request.method == "POST" && sender -> {
                            val body = JSONObject(request.body.readUtf8())
                            if (rejectFirstCreate) {
                                rejectFirstCreate = false
                                return json("""{"detail":"Создание временно недоступно"}""", 503)
                            }
                            if (created != null) return json("{}", 409)
                            created = body
                            status = "created"
                            json(parcel(true))
                        }
                        path == "/parcels/mine" && sender -> items(created != null, true)
                        path == "/courier/application" && carrier -> json("""{"application":null}""")
                        path == "/parcels/available" && carrier -> items(status == "created", false)
                        path == "/parcels/81/accept" && carrier && status == "created" -> {
                            status = "accepted"; transitions.add(status); json(parcel(false))
                        }
                        path == "/parcels/carrying" && carrier -> items(status in listOf("accepted", "in_transit", "delivered"), false)
                        path == "/parcels/81/status" && carrier -> {
                            val body = JSONObject(request.body.readUtf8())
                            val next = body.getString("status")
                            if (next == "delivered") {
                                deliveredCodes.add(body.optString("code"))
                                if (body.optString("code") != serverCode) return json("""{"detail":"Неверный код вручения"}""", 422)
                            }
                            if (!((status == "accepted" && next == "in_transit") ||
                                    (status == "in_transit" && next == "delivered"))) return json("{}", 409)
                            status = next; transitions.add(next); json(parcel(false))
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
        page.intValue = -1
        compose.waitForIdle()
        ApiClient.logout()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server.shutdown()
    }

    @Test fun emptySenderFormToDeliveryAndBothHistories() = journey(false)

    @Test fun createFailureAndWrongDeliveryCodeCanBeRetried() {
        rejectFirstCreate = true
        journey(true)
    }

    private fun journey(withFailures: Boolean) {
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                YuldashTheme {
                    key(page.intValue) {
                        when (page.intValue) {
                            0, 2 -> ParcelsScreen(onBack = {})
                            1 -> CourierScreen(onBack = {}, onBecomeCourier = {})
                        }
                    }
                }
            }
        }
        fill("Откуда", "Пункт А")
        fill("Где забрать", "У синих ворот")
        fill("Куда", "Пункт Б")
        fill("Куда привезти", "У школы")
        clickScrolled("Далее")
        clickScrolled("Маленькая")
        fill("Что за посылка", "Тестовая коробка")
        fill("Сколько заплатишь попутчику, ₽", "250")
        clickScrolled("Далее")
        fill("Имя получателя", "Тестовый получатель")
        fill("Телефон получателя", "+79990000001")
        val checkbox = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)
        scroll(checkbox)
        compose.onNode(checkbox).performClick()
        clickScrolled("Отправить посылку")
        if (withFailures) {
            waitFor("Создание временно недоступно")
            assertNull(created)
            clickScrolled("Отправить посылку")
        }
        waitFor("Код вручения")
        scroll(hasText(serverCode))
        val visibleCode = compose.onNodeWithText(serverCode).assertIsDisplayed()
            .fetchSemanticsNode().config[SemanticsProperties.Text].single().text
        assertEquals("Пункт А", created!!.getString("from_city"))
        assertEquals("Пункт Б", created!!.getString("to_city"))
        assertEquals("У синих ворот", created!!.getString("from_address"))
        assertEquals("У школы", created!!.getString("to_address"))
        assertEquals("small", created!!.getString("size"))
        assertEquals(25000, created!!.getInt("price_kop"))
        assertTrue(created!!.getBoolean("rules_accepted"))
        clickScrolled("Готово")
        compose.waitUntil(15000) { requests.contains("GET /parcels/mine") }
        waitFor("Ждёт курьера")
        switchTo(1, "local-parcel-carrier")
        waitFor("Взять заказ")
        clickScrolled("Взять заказ")
        compose.waitUntil(15000) { status == "accepted" }
        compose.onNodeWithText("Везу").performClick()
        waitFor("В пути")
        clickScrolled("В пути")
        waitFor("Забрал, еду")
        compose.onNodeWithText("Забрал, еду").performClick()
        compose.waitUntil(15000) { status == "in_transit" }
        waitFor("Доставлено")
        clickScrolled("Доставлено")
        waitFor("Код от получателя")
        if (withFailures) {
            compose.onNodeWithText("Код от получателя").performTextReplacement("000000")
            compose.onNodeWithText("Подтвердить вручение").performClick()
            waitFor("Неверный код вручения")
            assertEquals("in_transit", status)
        }
        compose.onNodeWithText("Код от получателя").performTextReplacement(visibleCode)
        compose.onNodeWithText("Подтвердить вручение").performClick()
        compose.waitUntil(15000) { status == "delivered" }
        waitFor("Доставлена")
        scroll(hasText("Доставлена"))
        compose.onNodeWithText("Доставлена").assertIsDisplayed()
        switchTo(2, "local-parcel-sender")
        compose.onNodeWithText("Мои").performClick()
        waitFor("Доставлена")
        scroll(hasText("Доставлена"))
        compose.onNodeWithText("Доставлена").assertIsDisplayed()
        assertEquals(listOf("accepted", "in_transit", "delivered"), transitions.toList())
        assertEquals(if (withFailures) listOf("000000", visibleCode) else listOf(visibleCode), deliveredCodes.toList())
        assertEquals(if (withFailures) 2 else 1, requests.count { it == "POST /parcels" })
        assertFalse(requests.any { it.endsWith("/courier/me") || it.endsWith("/courier/available") })
    }

    private fun switchTo(next: Int, token: String) {
        compose.runOnIdle { page.intValue = -1 }
        compose.waitForIdle()
        ApiClient.saveToken(token)
        compose.runOnIdle { page.intValue = next }
    }
    private fun scroll(matcher: SemanticsMatcher) {
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(matcher)
    }
    private fun fill(label: String, value: String) {
        val matcher = hasText(label) and hasSetTextAction()
        scroll(matcher)
        compose.onNode(matcher).performTextReplacement(value)
    }
    private fun clickScrolled(label: String) {
        val matcher = hasText(label) and hasClickAction()
        scroll(matcher)
        compose.onNode(matcher).performClick()
    }
    private fun waitFor(label: String, substring: Boolean = false) {
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithText(label, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }
    private fun parcel(sender: Boolean): String = JSONObject(created!!.toString()).apply {
        put("id", 81); put("sender_id", 3); put("courier_id", if (status == "created") JSONObject.NULL else 9)
        put("status", status); put("delivery_type", "poputka"); put("created_at", "2026-09-24T09:00:00Z")
        if (sender) put("confirm_code", serverCode)
    }.toString()
    private fun items(include: Boolean, sender: Boolean) = json("""{"items":[${if (include) parcel(sender) else ""}]}""")
    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code)
        .setHeader("Content-Type", "application/json").setBody(body)
}
