package com.yuldash.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.ViewGroup
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.ui.theme.YuldashTheme
import okhttp3.mockwebserver.*
import okhttp3.mockwebserver.SocketPolicy
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * PATH-09: real sender `ParcelsScreen` ("Мои") + real carrier `CourierScreen` (poputka path).
 * The track link itself is UI-local state (`ParcelTrackLinkBlock` keeps `link`/`smsSent` in
 * `remember(parcelId)`; it is never round-tripped through `GET /parcels/mine`), so the mock
 * server only has to answer each POST/DELETE call in sequence. The real receiver-facing page
 * (what `/t/{token}` actually renders) is proven separately by F-09's link-client harness.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ParcelTrackLinkJourneyTest {
    @get:Rule val compose = createComposeRule()
    private val page = mutableIntStateOf(0)
    private lateinit var server: MockWebServer
    @Volatile private var status = "created"
    private val requests = CopyOnWriteArrayList<String>()
    private val transitions = CopyOnWriteArrayList<String>()
    private val deliveredCodes = CopyOnWriteArrayList<String>()
    private val serverCode = "610452"
    private val trackUrl = "https://yulbash.ru/t/tok81demo"
    private val createCalls = AtomicInteger(0)
    private val revokeCalls = AtomicInteger(0)
    @Volatile private var createOutcomes: List<() -> MockResponse> = emptyList()
    @Volatile private var revokeOutcomes: List<() -> MockResponse> = emptyList()

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
                        path == "/parcels/mine" && sender -> items(true)
                        path == "/courier/application" && carrier -> json("""{"application":null}""")
                        path == "/parcels/available" && carrier -> items(status == "created")
                        path == "/parcels/81/accept" && carrier && status == "created" -> {
                            status = "accepted"; transitions.add(status); json(parcel())
                        }
                        path == "/parcels/carrying" && carrier ->
                            items(status in listOf("accepted", "in_transit", "delivered"))
                        path == "/parcels/81/status" && carrier -> {
                            val body = JSONObject(request.body.readUtf8())
                            val next = body.getString("status")
                            if (next == "delivered") deliveredCodes.add(body.optString("code"))
                            if (!((status == "accepted" && next == "in_transit") ||
                                    (status == "in_transit" && next == "delivered"))) return json("{}", 409)
                            status = next; transitions.add(next); json(parcel())
                        }
                        path == "/parcels/81/track-link" && request.method == "POST" && sender -> {
                            val n = createCalls.getAndIncrement()
                            createOutcomes.getOrNull(n)?.invoke()
                                ?: json("""{"url":"$trackUrl","sms_sent":true}""")
                        }
                        path == "/parcels/81/track-link" && request.method == "DELETE" && sender -> {
                            val n = revokeCalls.getAndIncrement()
                            revokeOutcomes.getOrNull(n)?.invoke() ?: json("{}")
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

    @Test fun senderLinkCodeHandoverClosesAccess() {
        mount()
        compose.onNodeWithText("Мои").performClick()
        waitFor("Дать получателю ссылку для слежения")
        assertTrue(
            "code block must stay hidden while the parcel is only created",
            compose.onAllNodesWithText("Код вручения (передай получателю)").fetchSemanticsNodes().isEmpty(),
        )

        clickScrolled("Дать получателю ссылку для слежения")
        waitFor("Ссылка отправлена получателю по SMS")
        scroll(hasText(trackUrl))
        compose.onNodeWithText(trackUrl).assertIsDisplayed()

        clickButtonScrolled("Отправить")
        compose.waitForIdle()
        val app = ApplicationProvider.getApplicationContext<Application>()
        val chooserIntent = Shadows.shadowOf(app).nextStartedActivity
        assertNotNull("share chooser was not started", chooserIntent)
        assertEquals(Intent.ACTION_CHOOSER, chooserIntent!!.action)
        @Suppress("DEPRECATION") val inner = chooserIntent.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent
        assertNotNull("chooser carried no wrapped ACTION_SEND intent", inner)
        assertEquals(Intent.ACTION_SEND, inner!!.action)
        assertEquals("text/plain", inner.type)
        val sentText = inner.getStringExtra(Intent.EXTRA_TEXT)
        assertNotNull("shared text was empty", sentText)
        assertTrue("shared text must carry the track link", sentText!!.contains(trackUrl))

        switchTo(1, "local-parcel-carrier")
        waitFor("Взять заказ")
        clickScrolled("Взять заказ")
        compose.waitUntil(15000) { status == "accepted" }

        switchTo(2, "local-parcel-sender")
        compose.onNodeWithText("Мои").performClick()
        waitFor("Код вручения (передай получателю)")
        scroll(hasText(serverCode))
        val visibleCode = compose.onNodeWithText(serverCode).assertIsDisplayed()
            .fetchSemanticsNode().config[SemanticsProperties.Text].single().text
        assertEquals(serverCode, visibleCode)

        switchTo(3, "local-parcel-carrier")
        waitFor("Везу")
        compose.onNodeWithText("Везу").performClick()
        waitFor("В пути")
        clickScrolled("В пути")
        waitFor("Забрал, еду")
        compose.onNodeWithText("Забрал, еду").performClick()
        compose.waitUntil(15000) { status == "in_transit" }
        waitFor("Доставлено")
        openDialogWithTextField { clickScrolled("Доставлено") }
        waitFor("Код от получателя")
        compose.onNodeWithText("Код от получателя").performTextReplacement(visibleCode)
        compose.onNodeWithText("Подтвердить вручение").performClick()
        compose.waitUntil(15000) { status == "delivered" }

        switchTo(4, "local-parcel-sender")
        compose.onNodeWithText("Мои").performClick()
        waitFor("Доставлена")
        assertTrue(
            "handover code must disappear once delivered",
            compose.onAllNodesWithText("Код вручения (передай получателю)").fetchSemanticsNodes().isEmpty(),
        )
        assertTrue(
            "track-link block must disappear once delivered",
            compose.onAllNodesWithText("Дать получателю ссылку для слежения").fetchSemanticsNodes().isEmpty(),
        )
        assertTrue(compose.onAllNodesWithText(trackUrl).fetchSemanticsNodes().isEmpty())

        assertEquals(1, requests.count { it == "POST /parcels/81/track-link" })
        assertEquals(listOf("accepted", "in_transit", "delivered"), transitions.toList())
        assertEquals(listOf(visibleCode), deliveredCodes.toList())
    }

    @Test fun linkCreateRevokeFailures() {
        createOutcomes = listOf(
            { json("""{"detail":"Сервис временно недоступен"}""", 503) },
            { json("""{"url":"$trackUrl","sms_sent":true}""") },
            { json("""{"detail":"Посылка уже завершена"}""", 409) },
            { MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) },
            { json("""{"url":"$trackUrl","sms_sent":true}""") },
        )
        revokeOutcomes = listOf(
            { json("""{"detail":"Сервис временно недоступен"}""", 503) },
            { json("{}") },
        )
        mount()
        compose.onNodeWithText("Мои").performClick()
        waitFor("Дать получателю ссылку для слежения")

        // 1) create fails (503) -> error line, link stays absent.
        clickScrolled("Дать получателю ссылку для слежения")
        waitFor("Сервис временно недоступен")

        // 2) retry succeeds -> link appears.
        clickScrolled("Дать получателю ссылку для слежения")
        waitFor("Ссылка отправлена получателю по SMS")
        scroll(hasText(trackUrl))
        compose.onNodeWithText(trackUrl).assertIsDisplayed()

        // 3) revoke fails (503) -> error line, link/buttons stay as they were.
        clickScrolled("Отозвать")
        waitFor("Сервис временно недоступен")
        compose.onNodeWithText(trackUrl).assertIsDisplayed()

        // 4) revoke retry succeeds -> button reverts to "create link".
        clickScrolled("Отозвать")
        waitFor("Дать получателю ссылку для слежения")

        // 5) create again, this time the parcel is already finished server-side.
        clickScrolled("Дать получателю ссылку для слежения")
        waitFor("Посылка уже завершена")

        // 6) create times out -> generic network error, button still usable afterwards.
        clickScrolled("Дать получателю ссылку для слежения")
        waitFor("Не получилось. Проверь сеть.")

        // 7) working retry after the timeout.
        clickScrolled("Дать получателю ссылку для слежения")
        waitFor("Ссылка отправлена получателю по SMS")
        scroll(hasText(trackUrl))
        compose.onNodeWithText(trackUrl).assertIsDisplayed()

        assertEquals(5, createCalls.get())
        assertEquals(2, revokeCalls.get())
    }

    private fun mount() {
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                YuldashTheme {
                    key(page.intValue) {
                        when (page.intValue) {
                            0, 2, 4 -> ParcelsScreen(onBack = {})
                            1, 3 -> CourierScreen(onBack = {}, onBecomeCourier = {})
                        }
                    }
                }
            }
        }
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
    private fun clickScrolled(label: String) {
        val matcher = hasText(label) and hasClickAction()
        scroll(matcher)
        compose.onNode(matcher).performClick()
    }
    /** Disambiguates from the "Отправить" tab (`role = Role.Tab`): only the real button matches. */
    private fun clickButtonScrolled(label: String) {
        val matcher = hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
        scroll(matcher)
        compose.onNode(matcher).performClick()
    }
    /**
     * Robolectric-only: a platform-width (WRAP_CONTENT) dialog holding a text field re-measures
     * forever here, so Compose never idles. The product stays as is; the freshly shown window is
     * widened to MATCH_PARENT before the first idle wait (same seam as ParcelCreationDeliveryJourneyTest).
     */
    private fun openDialogWithTextField(open: () -> Unit) {
        val previous = ShadowDialog.getLatestDialog()
        open()
        repeat(10) {
            val dialog = ShadowDialog.getLatestDialog()
            if (dialog != null && dialog !== previous && dialog.isShowing) {
                dialog.window!!.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                return
            }
            compose.mainClock.advanceTimeByFrame()
        }
        fail("dialog with a text field did not open")
    }
    private fun waitFor(label: String, substring: Boolean = false) {
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithText(label, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }
    private fun parcel(): String = JSONObject().apply {
        put("id", 81); put("sender_id", 3)
        put("courier_id", if (status == "created") JSONObject.NULL else 9)
        put("from_city", "Пункт А"); put("to_city", "Пункт Б")
        put("from_address", "У синих ворот"); put("to_address", "У школы")
        put("size", "small"); put("description", "Тестовая коробка")
        put("receiver_name", "Тестовый получатель"); put("receiver_phone", "+79990000002")
        put("price_kop", 25000); put("confirm_code", serverCode)
        put("status", status); put("delivery_type", "poputka")
        put("created_at", "2026-09-24T09:00:00Z")
    }.toString()
    private fun items(include: Boolean) = json("""{"items":[${if (include) parcel() else ""}]}""")
    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code)
        .setHeader("Content-Type", "application/json").setBody(body)
}
