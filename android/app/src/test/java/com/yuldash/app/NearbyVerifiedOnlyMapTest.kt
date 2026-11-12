package com.yuldash.app

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
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
import org.junit.Assert.assertFalse
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
    @Volatile private var delayedDate: CountDownLatch? = null
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
                    // Карточка «Популярно» — только из настоящих маршрутов сервера (демо-маршрутов нет с 23.10).
                    if (path == "/popular-routes") return MockResponse().setHeader("Content-Type", "application/json")
                        .setBody("""{"items":[{"from_city":"Баймаҡ","to_city":"Сибай","count":5}]}""")
                    if (!path.startsWith("/rides/near")) return MockResponse().setResponseCode(404).setBody("{}")
                    if ("date=" in path) delayedDate?.let { gate ->
                        gate.await(10, TimeUnit.SECONDS)
                        return MockResponse().setHeader("Content-Type", "application/json")
                            .setBody("""{"items":[],"count":0}""")
                    }
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

    // Ждём простую переменную (запросы к серверу), а «Показать» пишет состояние из колбэка нажатия —
    // вне композиции. Если GlobalSnapshotManager после сброса Looper «уснул», запись до экрана не
    // доходила, и тест изредка падал по таймауту (полный прогон 24.10.2026). Каждый шаг ожидания
    // доставляет её сам (docs/testing.md, правило 5); контроль — тест с withLostGlobalSnapshotNotification.
    private fun waitFor(what: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(15000) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                Snapshot.sendApplyNotifications()
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

    /** Контроль правила 5: без доставки в ожидании этот тест красный всегда, а не через раз. */
    @Test fun showReloadsFeedEvenWhenSnapshotNotificationIsLost() {
        mount()
        waitFor("подсказка о непроверенных") { compose.onAllNodesWithText(hint).fetchSemanticsNodes().isNotEmpty() }
        withLostGlobalSnapshotNotification {
            compose.onNodeWithText("Показать").performSemanticsAction(SemanticsActions.OnClick)
            waitFor("лента того же поиска без фильтра") { nearRequests().any { "verified_only" !in it } }
        }
    }

    @Test fun passengerWhoTurnedItOffKeepsTheChoice() {
        AppPrefs.setVerifiedOnly(ctx, false)
        mount()
        waitFor("первая лента") { nearRequests().isNotEmpty() }
        assertTrue("сам выключил — фильтр не должен вернуться: ${nearRequests()}", nearRequests().none { "verified_only" in it })
        assertTrue("подсказки нет — фильтр выключен", compose.onAllNodesWithText(hint).fetchSemanticsNodes().isEmpty())
    }

    @Test fun oldRideDisappearsWhileNewDateIsStillLoading() {
        AppPrefs.setVerifiedOnly(ctx, false)
        ApiClient.testTimeoutMs = 12_000
        val gate = CountDownLatch(1)
        delayedDate = gate
        fun oldRideVisible() = compose.onAllNodesWithText("750 ₽", substring = true).fetchSemanticsNodes().isNotEmpty()
        try {
            mount()
            waitFor("карточка прежней выдачи") { oldRideVisible() }
            compose.onNodeWithText("Когда").performSemanticsAction(SemanticsActions.OnClick)
            waitFor("шторка даты") { compose.onAllNodesWithText("Завтра").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Завтра").performSemanticsAction(SemanticsActions.OnClick)
            waitFor("сервер получил новую дату") { nearRequests().any { "date=" in it } }
            assertFalse("До ответа по новой дате видна старая поездка", oldRideVisible())
            assertFalse("До ответа по новой дате виден старый счётчик",
                compose.onAllNodesWithText("2 рядом").fetchSemanticsNodes().isNotEmpty())
        } finally {
            gate.countDown()
        }
        waitFor("пустой результат по новой дате") {
            compose.onAllNodesWithText("Сбросить фильтры").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
