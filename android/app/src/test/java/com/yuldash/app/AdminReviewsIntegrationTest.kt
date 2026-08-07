package com.yuldash.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import com.yuldash.app.data.ApiClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.QueueDispatcher
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

    // Порядок важен: повтор снаружи, чтобы вторая попытка получила ЧИСТЫЙ экран
    // и свой @Before. Почему повтор вообще есть — в RetryOnFlakeRule.
    @get:Rule(order = 0)
    val retry = RetryOnFlakeRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private lateinit var server: MockWebServer

    @Before
    fun setup() {
        server = MockWebServer()
        // ЛИШНИЙ запрос не должен вешать тест. По умолчанию MockWebServer на запрос с пустой
        // очередью НЕ отвечает вообще — соединение просто висит. Любой незапланированный поход
        // в сеть (обновление токена по 401, повтор, фоновая подгрузка) в этот момент зависал
        // навсегда, и тест падал по таймауту «условие не выполнено». Это выглядело как
        // медленный раннер, но не лечилось ни 10, ни 60 секундами: ждать было нечего.
        // failFast → на неожиданный запрос сразу 404, тест падает по существу и быстро.
        (server.dispatcher as QueueDispatcher).setFailFast(MockResponse().setResponseCode(404))
        // Обрываем всё фоновое, что осталось от предыдущих тестов. Область корутин у клиента
        // общая на весь процесс и раньше не отменялась никогда: «выстрелил и забыл» запрос из
        // раннего класса продолжал повторяться, когда давно шёл другой класс, — а адрес сервера
        // он перечитывает на каждой попытке и потому приходил СЮДА, съедая чужой ответ.
        ApiClient.resetForTest()
        // Короткие таймауты вместо боевых 15 секунд. С боевыми один вызов живёт до полутора
        // минут, а тест ждёт двадцать: любая заминка выглядит как «навсегда Загрузка…».
        ApiClient.testTimeoutMs = 2000
        // Выход — ДО подмены адреса: `logout()` шлёт в фоне два запроса, и после подмены они
        // прилетели бы на тестовый сервер и съели ответ, заготовленный для экрана.
        ApiClient.logout()
        server.start()
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
    }

    @After
    fun teardown() {
        // Порядок важен: сначала обрываем фоновое, потом гасим сервер. Иначе недобитый запрос
        // успевает уйти уже на СЛЕДУЮЩИЙ сервер — ровно тот механизм, из-за которого класс
        // проходит поодиночке и падает в полном прогоне.
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server.shutdown()
    }

    private fun waitForText(text: String) {
        try {
            composeRule.waitUntil(timeoutMillis = 20_000) {
                // Прокручиваем очередь главного потока руками. Ответ сервера приходит в фоновом
                // потоке, а обновить экран может только главный — и под Robolectric он не крутится
                // сам. `waitUntil` только спрашивает «текст уже есть?», очередь не трогая.
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: Throwable) {
            // Тот же решающий замер, что и в AdminScreensIntegrationTest. Там измерение уже
            // доказало: сеть отдаёт правильный ответ за 10 мс и потоки свободны — значит ответ
            // не доезжает до экрана. Осталось разделить два случая, они лечатся по-разному:
            // продолжение стоит в очереди и её никто не крутит, либо продолжения нет вовсе.
            val appearedAfterPumping = runCatching {
                composeRule.mainClock.advanceTimeBy(5_000)
                repeat(50) {
                    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                    composeRule.mainClock.advanceTimeByFrame()
                }
                composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
            }.getOrElse { false }
            val asked = buildList {
                while (true) {
                    val r = server.takeRequest(50, java.util.concurrent.TimeUnit.MILLISECONDS) ?: break
                    add("${r.method} ${r.path}")
                }
            }
            val screen = runCatching { composeRule.onRoot().printToString(maxDepth = 10) }
                .getOrElse { "дерево экрана прочитать не удалось: $it" }
            throw AssertionError(
                "Не дождались текста «$text».\n" +
                    "Запросов пришло на сервер: ${server.requestCount} → $asked\n" +
                    "ПОСЛЕ ПРИНУДИТЕЛЬНОЙ ПРОКРУТКИ ОЧЕРЕДЕЙ текст «$text» " +
                    (if (appearedAfterPumping) "ПОЯВИЛСЯ → ответ всё это время лежал непрокрученным"
                     else "так и НЕ появился → продолжение не поставили вовсе") + "\n" +
                    "Что было на экране (до прокрутки):\n$screen",
                e,
            )
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
