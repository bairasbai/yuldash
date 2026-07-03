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
 * ИНТЕГРАЦИОННЫЙ тест: вся цепочка целиком, а не деталь по отдельности.
 * Рендерим УМНЫЙ экран `AdminReviewsScreen` (стейт + LaunchedEffect + ApiClient) под Robolectric,
 * сеть заворачиваем на локальный MockWebServer через тест-хук `ApiClient.testBaseUrl`.
 * Проверяем: экран открылся → сам сходил в «сервер» → распарсил → показал результат / пусто.
 * Покрывает «умную» обёртку (load/LaunchedEffect/состояния), которую unit-тесты Content не трогают.
 *
 * Здесь — стабильные сценарии (успех + пусто). «Плохие» сценарии сети (нет интернета / 500 / битый JSON)
 * детально покрыты быстрыми unit-тестами `ApiClientCriticalBadPathTest` (30 тестов) — в интеграции они
 * упираются в реальный readTimeout и флейкуют под нагрузкой полного прогона, поэтому вынесены в unit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdminReviewsIntegrationTest {

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
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun screenLoadsReviewsFromServerAndDisplaysThem() {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"items":[{"id":1,"name":"Айгуль","city":"Уфа","stars":5,"text":"Отличная поездка"}]}""")
        )
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                AdminReviewsScreen(onBack = {})
            }
        }
        // Экран сам сходил на «сервер» → распарсил → показал отзыв и кнопку одобрения.
        waitForText("«Отличная поездка»")
        composeRule.onNodeWithText("«Отличная поездка»").assertExists()
        composeRule.onNodeWithText("Одобрить для сайта").assertExists()
    }

    @Test
    fun emptyServerResponse_showsEmptyState() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"items":[]}"""))
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                AdminReviewsScreen(onBack = {})
            }
        }
        waitForText("Новых отзывов нет")
        composeRule.onNodeWithText("Всё разобрано").assertExists()
    }
}
