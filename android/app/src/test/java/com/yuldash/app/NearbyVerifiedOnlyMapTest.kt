package com.yuldash.app

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Лента «Ближайшие поездки» целиком: «только проверенные» по умолчанию и «Показать» на один поиск
 * (решение Александра, 17.10.2026).
 *
 * Сервер-заглушка ведёт себя как настоящий: с `verified_only=true` проверенных нет, но две поездки
 * непроверенных спрятаны (`hidden_unverified: 2`); без фильтра отдаёт эти две. Проверяем по запросам
 * и по экрану: новый человек просит только проверенных, видит «Есть 2 поездки…», жмёт «Показать» —
 * лента перезапрашивается без фильтра, а настройка «Безопасности» остаётся включённой.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NearbyVerifiedOnlyMapTest {
    @get:Rule val compose = createComposeRule()
    private val requests = CopyOnWriteArrayList<String>()
    private lateinit var server: MockWebServer
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private val hint = "Есть 2 поездки непроверенных водителей — показать?"

    private fun ride(id: Int) = """{"id":$id,"from_city":"Пункт А","to_city":"Пункт Б","depart_at":"2030-01-02T10:00:00+03:00",""" +
        """"seats_total":3,"seats_left":3,"price":750,"category":"regular","driver_id":$id,"driver_name":"Водитель $id","driver_verified":false}"""

    @Before fun setup() {
        ctx.getSharedPreferences("yuldash_settings", Context.MODE_PRIVATE).edit().clear().commit()
        ApiClient.resetForTest()
        ApiClient.logout()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = URLDecoder.decode(request.path.orEmpty(), "UTF-8")
                    requests.add("${request.method} $path")
                    if (!path.startsWith("/rides/near")) return MockResponse().setResponseCode(404).setBody("{}")
                    val body = if ("verified_only=true" in path) """{"items":[],"count":0,"hidden_unverified":2}"""
                    else """{"items":[${ride(41)},${ride(42)}],"count":2}"""
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 1000
    }

    @After fun cleanup() {
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server.shutdown()
    }

    private fun nearRequests() = requests.filter { it.startsWith("GET /rides/near?") }

    private fun waitFor(what: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(15000) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                condition()
            }
        } catch (e: Throwable) {
            println("WAIT_FAILED=$what requests=${requests.toList()}")
            throw e
        }
    }

    private fun mount() {
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                MapScreen(
                    rides = emptyList(), activeTrip = null, ads = emptyList(), adStats = emptyMap(),
                    onBookRide = {}, onShareRide = {}, onAdImpression = {}, onAdClick = {}, onSos = {},
                    onDriver = {}, onBoost = {}, onClinicRides = {},
                )
            }
        }
    }

    @Test fun newPassengerSeesVerifiedOnlyAndCanShowUnverifiedForThisSearch() {
        mount()
        waitFor("первая лента") { nearRequests().isNotEmpty() }
        assertTrue("по умолчанию лента просит только проверенных: ${nearRequests()}", nearRequests().first().contains("verified_only=true"))

        waitFor("подсказка о непроверенных") { compose.onAllNodesWithText(hint).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Показать").performSemanticsAction(SemanticsActions.OnClick)

        waitFor("лента того же поиска без фильтра") { nearRequests().any { "verified_only" !in it } }
        waitFor("подсказка ушла — показаны поездки") { compose.onAllNodesWithText(hint).fetchSemanticsNodes().isEmpty() }
        assertTrue("«Показать» поменял настройку «Безопасности»", AppPrefs.verifiedOnly(ctx))
    }

    @Test fun passengerWhoTurnedItOffKeepsTheChoice() {
        AppPrefs.setVerifiedOnly(ctx, false)
        mount()
        waitFor("первая лента") { nearRequests().isNotEmpty() }
        assertTrue("сам выключил — фильтр не должен вернуться: ${nearRequests()}", nearRequests().none { "verified_only" in it })
        assertTrue("подсказки нет — фильтр выключен", compose.onAllNodesWithText(hint).fetchSemanticsNodes().isEmpty())
    }
}
