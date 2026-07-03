package com.yuldash.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.yuldash.app.data.ApiClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * ИНТЕГРАЦИОННЫЙ тест: вся цепочка целиком (экран → ApiClient → MockWebServer → UI), а не деталь отдельно.
 * Рендерим УМНЫЕ экраны ленты заявок и кабинетов (стейт + LaunchedEffect + ApiClient) под Robolectric,
 * сеть заворачиваем на локальный MockWebServer через тест-хук `ApiClient.testBaseUrl`.
 * Проверяем: экран открылся → сам сходил в «сервер» → распарсил → показал результат / пусто.
 * Покрывает «умную» обёртку (load/LaunchedEffect/состояния), которую unit-тесты Content не трогают.
 *
 * Здесь — стабильные сценарии (успех + пусто). «Плохие» сценарии сети (нет интернета / 500 / битый JSON)
 * упираются в реальный readTimeout и флейкуют под нагрузкой полного прогона, поэтому не дублируем их тут
 * (они детально покрыты быстрыми unit-тестами `ApiClientCriticalBadPathTest`).
 *
 * Покрытые экраны:
 *  • RequestsFeedScreen — GET /requests/feed (RequestFeedDto)
 *  • RepeatTripScreen   — GET /my-routes  (нужен токен: экран грузит маршруты только если isLoggedIn())
 *  • AdsCabinetScreen   — GET /ad-packages, затем GET /ads/mine (2 запроса по порядку, MyAdDto)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FeedCabinetIntegrationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var server: MockWebServer

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.logout()   // чистая сессия/кеш перед тестом
    }

    @After
    fun teardown() {
        ApiClient.testBaseUrl = null
        ApiClient.logout()   // не тащим токен/кеш в соседний тест
        server.shutdown()
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    // ---------- RequestsFeedScreen: GET /requests/feed ----------

    @Test
    fun requestsFeed_loadsFromServerAndDisplaysThem() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"items":[{"id":7,"passenger_name":"Айгуль","from_city":"Уфа","to_city":"Казань","seats":2,"comment":"Еду утром, беру одного","responded":false,"prefs":[]}]}"""
            )
        )
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                RequestsFeedScreen(onBack = {})
            }
        }
        // Экран сам сходил на «сервер» → распарсил → показал маршрут заявки, комментарий и кнопку отклика.
        waitForText("Уфа → Казань")
        composeRule.onNodeWithText("Уфа → Казань").assertExists()
        composeRule.onNodeWithText("Еду утром, беру одного").assertExists()
        composeRule.onNodeWithText("Предложить поездку").assertExists()
    }

    @Test
    fun requestsFeed_emptyServerResponse_showsEmptyState() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"items":[]}"""))
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                RequestsFeedScreen(onBack = {})
            }
        }
        waitForText("Заявок пока нет")
        composeRule.onNodeWithText("Здесь появятся заявки пассажиров.").assertExists()
    }

    // ---------- RepeatTripScreen: GET /my-routes (требует токен) ----------

    @Test
    fun repeatTrip_loadsMyRoutesFromServerAndDisplaysThem() {
        // Экран грузит маршруты только для вошедшего пользователя — ставим токен после чистого logout().
        ApiClient.saveToken("test-token")
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"items":[{"from_city":"Уфа","to_city":"Казань","count":3}]}"""
            )
        )
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                RepeatTripScreen(
                    contacts = emptyList(),
                    onBack = {},
                    onLoginRequired = {},
                    onRepeat = {},
                )
            }
        }
        // Экран сам сходил на «сервер» → распарсил историю → показал частый маршрут (счётчик + направление).
        waitForText("Уфа → Казань")
        composeRule.onNodeWithText("Уфа → Казань").assertExists()
        composeRule.onNodeWithText("3 раза").assertExists()
    }

    @Test
    fun repeatTrip_emptyServerResponse_showsEmptyState() {
        ApiClient.saveToken("test-token")
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"items":[]}"""))
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                RepeatTripScreen(
                    contacts = emptyList(),
                    onBack = {},
                    onLoginRequired = {},
                    onRepeat = {},
                )
            }
        }
        waitForText("Истории пока нет")
        composeRule.onNodeWithText("После первой заявки частые маршруты появятся здесь.").assertExists()
    }

    // ---------- AdsCabinetScreen: GET /ad-packages, затем GET /ads/mine (2 запроса) ----------

    @Test
    fun adsCabinet_loadsMyAdsFromServerAndDisplaysThem() {
        // Экран делает ДВА запроса по порядку: сначала тарифы, потом мои объявления — оба в очередь.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"items":[{"code":"week","title":"Неделя","title_ba":"Аҙна","amount_kop":50000,"period_days":7}]}"""
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"items":[{"id":"ad1","title":"Шиномонтаж у Рустама","text":"Быстро и недорого","status":"draft","package":"week","package_title":"Неделя","budget_kop":50000,"period_days":7,"paid":false}]}"""
            )
        )
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                AdsCabinetScreen(onBack = {}, onCreateAd = {}, onEditAd = {})
            }
        }
        // Экран сам сходил на «сервер» → распарсил → показал карточку объявления (заголовок + статус-бейдж).
        waitForText("Шиномонтаж у Рустама")
        composeRule.onNodeWithText("Шиномонтаж у Рустама").assertExists()
        composeRule.onNodeWithText("Черновик").assertExists()
    }

    @Test
    fun adsCabinet_emptyServerResponse_showsShowcase() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"items":[{"code":"week","title":"Неделя","title_ba":"Аҙна","amount_kop":50000,"period_days":7}]}"""
            )
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"items":[]}"""))
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                AdsCabinetScreen(onBack = {}, onCreateAd = {}, onEditAd = {})
            }
        }
        // Своих объявлений нет → показываем витрину «рекламируйся у нас».
        waitForText("Реклама в Юлдаше")
        composeRule.onNodeWithText("Разместить рекламу").assertExists()
    }
}
