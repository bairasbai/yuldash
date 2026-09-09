package com.yuldash.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.yuldash.app.data.ApiClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** Real screen -> ApiClient -> local HTTP -> screen. Server permissions are tested in backend. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RoleCabinetIntegrationTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var server: MockWebServer
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private var responder: (RecordedRequest) -> MockResponse = { json("{}", 404) }

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code)
        .setHeader("Content-Type", "application/json").setBody(body)

    @Before fun setup() {
        ApiClient.resetForTest()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.add(request)
                return responder(request)
            }
        }
        server.start()
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 1000
        ApiClient.saveToken("local-role-test")
    }

    @After fun cleanup() {
        ApiClient.logout()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server.shutdown()
    }

    private fun waitFor(text: String) {
        compose.waitUntil(20_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun newCourierCanOpenApplicationAndDoesNotCallProfessionalEndpoints() {
        responder = { request -> when (request.requestUrl?.encodedPath) {
            "/courier/application" -> json("""{"application":null}""")
            "/parcels/available" -> json("""{"items":[]}""")
            else -> json("{}", 404)
        } }
        var opened = false
        compose.setContent { CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
            CourierScreen(onBack = {}, onBecomeCourier = { opened = true })
        } }
        waitFor("Стань курьером Юлдаша")
        compose.onNodeWithText("Стать").performClick()
        assertTrue(opened)
        assertFalse(requests.any { it.requestUrl?.encodedPath in listOf("/courier/me", "/courier/available") })
    }

    @Test fun pendingCourierSeesApplicationAndCannotUseProfessionalFeed() {
        responder = { request -> when (request.requestUrl?.encodedPath) {
            "/courier/application" -> json("""{"application":{"id":7,"status":"pending"}}""")
            "/parcels/available" -> json("""{"items":[]}""")
            else -> json("{}", 404)
        } }
        compose.setContent { CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
            CourierScreen(onBack = {}, onBecomeCourier = {})
        } }
        waitFor("Заявка на проверке")
        assertFalse(requests.any { it.requestUrl?.encodedPath in listOf("/courier/me", "/courier/available") })
    }

    @Test fun pendingBusinessShowsModerationWithoutLoadingCoupons() {
        responder = { request -> if (request.requestUrl?.encodedPath == "/partner/me")
            json("""{"partner":{"id":7,"name":"Local Cafe","status":"pending"}}""")
            else json("{}", 404)
        }
        compose.setContent { CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
            PartnerCabinetScreen(onBack = {})
        } }
        waitFor("Бизнес на проверке")
        assertFalse(requests.any { it.requestUrl?.encodedPath?.contains("coupons") == true })
    }

    @Test fun rejectedBusinessResubmitsEditedDataAndReloadsModerationStatus() {
        val submitted = AtomicBoolean(false)
        responder = { request -> when (request.requestUrl?.encodedPath) {
            "/partner/me" -> json("""{"partner":{"id":7,"name":"Local Cafe","city":"Уфа","category":"cafe","address":"Old","status":"${if (submitted.get()) "pending" else "rejected"}","reject_reason":"Исправь адрес"}}""")
            "/partner/7" -> { submitted.set(true); json("""{"id":7,"status":"pending"}""") }
            else -> json("{}", 404)
        } }
        compose.setContent { CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
            PartnerCabinetScreen(onBack = {})
        } }
        waitFor("Заявка отклонена")
        compose.onNodeWithText("Адрес").performScrollTo().performTextReplacement("New address")
        compose.onNodeWithText("Сохранить и отправить снова").performScrollTo().performClick()
        waitFor("Бизнес на проверке")
        val posts = requests.filter { it.requestUrl?.encodedPath == "/partner/7" }
        assertEquals(1, posts.size)
        assertEquals("POST", posts.single().method)
        assertEquals("New address", org.json.JSONObject(posts.single().body.readUtf8()).getString("address"))
    }
}
