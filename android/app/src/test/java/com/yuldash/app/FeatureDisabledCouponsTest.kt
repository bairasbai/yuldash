package com.yuldash.app

import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.ViewGroup
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
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
import org.robolectric.shadows.ShadowDialog
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Business OFF: настоящий старый код виден и гасится, выпуска и подписки нет. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FeatureDisabledCouponsTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var server: MockWebServer
    private val mounted = mutableStateOf(true)
    private val requests = CopyOnWriteArrayList<String>()
    @Volatile private var redeemedCode: String? = null
    @Volatile private var holdOldRead = false
    private val releaseOldRead = CountDownLatch(1)
    private val oldReadReturned = CountDownLatch(1)

    @Before fun setup() {
        ApiClient.resetForTest()
        ApiClient.init(ApplicationProvider.getApplicationContext<Context>())
        ApiClient.saveToken("wave1-local-coupon-owner")
        FeatureFlags.setForTest(FeatureSet(emptySet(), known = true, fetchedAtMs = 0))
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    requests.add("${request.method} $path")
                    if (holdOldRead && path in listOf("/my/coupons", "/partner/me") && request.getHeader("Authorization") == "Bearer wave1-local-coupon-owner") {
                        releaseOldRead.await(10, TimeUnit.SECONDS)
                        oldReadReturned.countDown()
                    }
                    if (request.getHeader("Authorization") == "Bearer wave1-local-coupon-new-owner")
                        return json(if (path == "/my/coupons") "{\"items\":[]}" else "{\"partner\":null}")
                    return when (path) {
                        "/my/coupons" -> json("""{"items":[{"code":"A1B2C3","status":"reserved","coupon":{"id":7,"title":"Старый купон","discount_text":"10%","partner":{"id":4,"name":"Местное кафе","category":"cafe"}}}]}""")
                        "/partner/me" -> json("""{"partner":{"id":4,"name":"Местное кафе","status":"active","category":"cafe"},"statement":null}""")
                        "/coupons/redeem" -> {
                            redeemedCode = JSONObject(request.body.readUtf8()).optString("code")
                            if (redeemedCode != "A1B2C3") return json("{}", 422)
                            json("""{"coupon_title":"Старый купон","discount_text":"10%","customer_name":"Тестовый клиент"}""")
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
        releaseOldRead.countDown()
        ApiClient.logout(); ApiClient.resetForTest(); ApiClient.testTimeoutMs = null
        server.shutdown()
    }

    @Test fun oldCodeIsVisibleWithBusinessOffInRussian() = history(AppLanguage.Ru)
    @Test fun oldCodeIsVisibleWithBusinessOffInBashkir() = history(AppLanguage.Ba)
    @Test fun oldCodeIsRedeemedWithBusinessOffInRussian() = redeem(AppLanguage.Ru)
    @Test fun oldCodeIsRedeemedWithBusinessOffInBashkir() = redeem(AppLanguage.Ba)

    @Test fun lateHistoryGetAfterLogoutDoesNotShowOldCode() = lateRead(false, "logout")
    @Test fun lateHistoryGetAfterOwnerChangeDoesNotShowOldCode() = lateRead(false, "owner")
    @Test fun lateHistoryGetAfterUnmountDoesNotShowOldCode() = lateRead(false, "unmount")
    @Test fun latePartnerGetAfterLogoutDoesNotShowOldMerchant() = lateRead(true, "logout")
    @Test fun latePartnerGetAfterOwnerChangeDoesNotShowOldMerchant() = lateRead(true, "owner")
    @Test fun latePartnerGetAfterUnmountDoesNotShowOldMerchant() = lateRead(true, "unmount")

    private fun lateRead(partner: Boolean, transition: String) {
        holdOldRead = true
        ApiClient.testTimeoutMs = 10000
        mount(AppLanguage.Ru, partner)
        val endpoint = if (partner) "GET /partner/me" else "GET /my/coupons"
        compose.waitUntil(10000) { requests.contains(endpoint) }
        compose.runOnIdle {
            when (transition) {
                "logout" -> ApiClient.logout()
                "owner" -> ApiClient.saveToken("wave1-local-coupon-new-owner")
                "unmount" -> mounted.value = false
            }
        }
        releaseOldRead.countDown()
        compose.waitUntil(10000) { Shadows.shadowOf(Looper.getMainLooper()).idle(); oldReadReturned.count == 0L }
        compose.waitForIdle()
        compose.onNodeWithText("A1B2C3").assertDoesNotExist()
        compose.onNodeWithText("Местное кафе").assertDoesNotExist()
        assertFalse(requests.any { it.startsWith("POST ") || it == "GET /coupons" })
    }

    private fun mount(language: AppLanguage, partner: Boolean) {
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(LocalAppLanguage provides language) {
                YuldashTheme {
                    ScreenGate(
                        Feature.Business, ComingSoonKind.Business, {}, {},
                        closedContent = { if (partner) ExistingPartnerRedeemScreen({}, {}) else ExistingCouponHistoryScreen({}, {}) },
                    ) { throw AssertionError("New business content opened while OFF") }
                }
            }
        }
    }

    private fun history(language: AppLanguage) {
        mount(language, false)
        waitFor("A1B2C3")
        compose.onNodeWithText("A1B2C3").assertIsDisplayed()
        assertTrue(requests.contains("GET /my/coupons"))
        assertFalse(requests.any { it.startsWith("POST ") || it == "GET /coupons" })
        compose.onNodeWithText(if (language == AppLanguage.Ru) "Скидки рядом" else "Яҡындағы ташламалар").assertDoesNotExist()
    }

    private fun redeem(language: AppLanguage) {
        mount(language, true)
        waitFor("Местное кафе")
        val open = if (language == AppLanguage.Ru) "Погасить код клиента" else "Клиент кодын һүндереү"
        val previous = ShadowDialog.getLatestDialog()
        compose.onNode(hasText(open) and hasClickAction()).performClick()
        for (attempt in 0 until 10) {
            val dialog = ShadowDialog.getLatestDialog()
            if (dialog != null && dialog !== previous && dialog.isShowing) {
                dialog.window!!.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                break
            }
            compose.mainClock.advanceTimeByFrame()
        }
        val code = if (language == AppLanguage.Ru) "Код купона" else "Купон коды"
        waitFor(code)
        compose.onNodeWithText(code).performTextReplacement("A1B2C3")
        compose.onNodeWithText(if (language == AppLanguage.Ru) "Погасить" else "Һүндереү").performClick()
        waitFor(if (language == AppLanguage.Ru) "Скидка подтверждена" else "Ташлама раҫланды")
        assertEquals("A1B2C3", redeemedCode)
        assertTrue(requests.contains("GET /partner/me"))
        assertEquals(listOf("POST /coupons/redeem"), requests.filter { it.startsWith("POST ") })
        assertFalse(requests.any { it.contains("subscribe") || it.contains("payments") || it.contains("partner/coupons") || it.contains("partner/plans") })
    }

    private fun waitFor(label: String) {
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()
        }
    }
    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code)
        .setHeader("Content-Type", "application/json").setBody(body)
}
