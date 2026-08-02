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
 * ИНТЕГРАЦИОННЫЙ тест админ-экранов: вся цепочка целиком (умный экран → ApiClient → MockWebServer → UI),
 * а не деталь по отдельности. Рендерим УМНУЮ обёртку (стейт + LaunchedEffect + ApiClient) под Robolectric,
 * сеть заворачиваем на локальный MockWebServer через тест-хук `ApiClient.testBaseUrl`.
 * Проверяем: экран открылся → сам сходил в «сервер» → распарсил → показал результат / пусто.
 * Покрывает «умную» обёртку (load/LaunchedEffect/состояния), которую unit-тесты Content не трогают.
 *
 * Покрытые экраны:
 *  - ResponsesScreen     (GET /requests/{id}/responses, ResponseDto)
 *  - AdminDriversScreen  (GET /admin/drivers/pending,   PendingDriverDto)
 *  - AdminReportsScreen  (GET /admin/reports,           AdminReportDto)
 *
 * Здесь — только стабильные сценарии (успех + пусто). «Плохие» сценарии сети (нет интернета / 500 / битый JSON)
 * детально покрыты быстрыми unit-тестами `ApiClientCriticalBadPathTest` — в интеграции они упираются в реальный
 * readTimeout и флейкуют под нагрузкой полного прогона, поэтому вынесены в unit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdminScreensIntegrationTest {

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
        server.shutdown()
    }

    private fun waitForText(text: String) {
        // 60 секунд, а не 10. Это НЕ ослабление проверки: сломанный экран нужного текста не
        // покажет никогда и тест всё равно упадёт. Но эти тесты идут первыми в прогоне, на
        // холодной JVM — Robolectric поднимает песочницу Android и грузит классы, и на
        // загруженном раннере CI одна только подготовка съедала весь бюджет. Падало через раз
        // (на одном и том же коде: прогон в 16:41 зелёный, в 17:01 — таймаут), а мигающий
        // тест в блокирующем гейте хуже отсутствующего: к красному CI привыкают.
        composeRule.waitUntil(timeoutMillis = 60_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    // ---------- ResponsesScreen: отклики водителей на заявку ----------

    @Test
    fun responsesScreen_loadsResponsesFromServerAndDisplaysThem() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"items":[{"id":7,"driver_id":42,"driver_name":"Динар","driver_rating":4.9,"price":350,"comment":"Довезу с ветерком","status":"pending","driver_avatar":""}]}"""
            )
        )
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                ResponsesScreen(requestId = 1, onBack = {}, onAccepted = {})
            }
        }
        // Экран сам сходил на «сервер» → распарсил → показал имя водителя, цену и кнопку выбора.
        waitForText("Динар")
        composeRule.onNodeWithText("Динар").assertExists()
        composeRule.onNodeWithText("350 ₽").assertExists()
        composeRule.onNodeWithText("Поехать с этим водителем").assertExists()
    }

    @Test
    fun responsesScreen_emptyServerResponse_showsEmptyState() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"items":[]}"""))
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                ResponsesScreen(requestId = 1, onBack = {}, onAccepted = {})
            }
        }
        waitForText("Откликов пока нет")
        composeRule.onNodeWithText("Откликов пока нет").assertExists()
    }

    // ---------- AdminDriversScreen: модерация водителей ----------

    @Test
    fun adminDriversScreen_loadsPendingDriversFromServerAndDisplaysThem() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"items":[{"user_id":15,"name":"Рустам","phone":"+79990001122","car":"Lada Vesta","license_url":"","car_photo_url":"","autocheck_result":"","autocheck_score":0.0,"autocheck_data":""}]}"""
            )
        )
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                AdminDriversScreen(onBack = {})
            }
        }
        // Экран сам сходил на «сервер» → распарсил → показал имя водителя и кнопку одобрения.
        waitForText("Рустам")
        composeRule.onNodeWithText("Рустам").assertExists()
        composeRule.onNodeWithText("Одобрить").assertExists()
    }

    @Test
    fun adminDriversScreen_emptyServerResponse_showsEmptyState() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"items":[]}"""))
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                AdminDriversScreen(onBack = {})
            }
        }
        waitForText("Нет заявок на проверку")
        composeRule.onNodeWithText("Нет заявок на проверку").assertExists()
    }

    // ---------- AdminReportsScreen: жалобы пользователей ----------

    @Test
    fun adminReportsScreen_loadsReportsFromServerAndDisplaysThem() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"items":[{"id":3,"reporter_name":"Айгуль","target_name":"Марат","target_phone":"+79995554433","reason":"Опоздал на час","created_at":"2026-07-01T10:00:00"}]}"""
            )
        )
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                AdminReportsScreen(onBack = {})
            }
        }
        // Экран сам сходил на «сервер» → распарсил → показал «кто → на кого» и причину жалобы.
        waitForText("Айгуль  →  Марат")
        composeRule.onNodeWithText("Айгуль  →  Марат").assertExists()
        composeRule.onNodeWithText("Опоздал на час").assertExists()
    }

    @Test
    fun adminReportsScreen_emptyServerResponse_showsEmptyState() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"items":[]}"""))
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                AdminReportsScreen(onBack = {})
            }
        }
        waitForText("Жалоб нет")
        composeRule.onNodeWithText("Жалоб нет").assertExists()
    }
}
