package com.yuldash.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import com.yuldash.app.data.ApiClient
import kotlinx.coroutines.test.StandardTestDispatcher
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
// ⏸ ЭТОТ КЛАСС МИГАЕТ ТОЖЕ. История версий и что уже опровергнуто — в `AdminScreensIntegrationTest`,
// не дублирую. Важно другое: 2026-08-06 в полном прогоне упали ОБА класса сразу, но разбирать было
// нечего — вся диагностика стояла только в соседнем классе, а тут наружу выходил голый таймаут.
// Поэтому 2026-08-08 обвязка здесь сделана ТАКОЙ ЖЕ: белый список адресов, журнал решений сервера,
// след клиента и сводка по потокам. Расхождение классов по оснастке и есть причина, по которой
// половина падений не давала показаний.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdminReviewsIntegrationTest {

    // ⛔ Повтор упавшего теста СНЯТ 2026-08-08, файл правила (`RetryOnFlakeRule.kt`) удалён —
    // причина мигания найдена и починена, подробности в `AdminScreensIntegrationTest`.
    // Пока костыль стоит, «починили» и «повезло дважды» выглядят одинаково. Разбор самого
    // костыля — в docs/lessons.md, раздел «Костыль-повтор уничтожил диагностику».

    // ⬇️ КОРЕНЬ МИГАНИЯ НАЗВАН И УБРАН (2026-08-08). Обрати внимание на `.v2` в импорте и на аргумент правила —
    // это ПОЧИНКА, а не украшение. Не убирать. Полный разбор механизма — в `AdminScreensIntegrationTest`.
    // Коротко: старое правило крутило эффекты экрана на `UnconfinedTestDispatcher`: он НЕ переотправляет
    // продолжение, и после `withContext(IO)` внутри ApiClient корутина экрана возобновлялась прямо
    // на сетевом потоке. Стейт (`list`, `loading = false`) писался оттуда — композиция узнавала об этом
    // не сразу, а «когда-нибудь»: 40 мс на здоровом прогоне, 13 секунд на медленном, больше 20 секунд
    // на падающем. Порога нет, разница зелёного и красного была количественной — отсюда «падает
    // случайный тест» и «поодиночке проходит».
    //
    // `v2` и `StandardTestDispatcher` ставят продолжение В ОЧЕРЕДЬ планировщика теста и возвращают его
    // на главный поток — как в настоящем приложении, где эффекты живут на `AndroidUiDispatcher.Main`.
    @get:Rule(order = 1)
    val composeRule = createComposeRule(StandardTestDispatcher())

    private lateinit var server: MockWebServer
    private lateinit var queue: QueueDispatcher
    private lateinit var failFast: MockResponse

    private companion object {
        /** Адреса, ради которых поднят этот сервер. Всё, что не отсюда, — чужое:
         *  получает 404 и очередь ответов не трогает. */
        val SERVED = listOf("/admin/reviews")
    }

    /** Сколько ответов этот тест поставил в очередь. Без него нельзя отличить
     *  «ответ приготовили, но он не понадобился» от «ответа и не готовили». */
    private var enqueued = 0

    /** Журнал РЕШЕНИЙ тестового сервера: что именно отдали на каждый запрос и когда.
     *  Потокобезопасно: пишут потоки соединений сервера, читает поток теста. */
    private val dispatchLog = java.util.Collections.synchronizedList(mutableListOf<String>())
    private var startedAt = 0L

    private fun note(what: String) {
        dispatchLog.add("+${System.currentTimeMillis() - startedAt}мс  $what")
    }

    /** Сводка по потокам сети в момент падения — проверяет версию «пул занят чужими запросами».
     *  Все тесты идут в ОДНОЙ виртуальной машине, поэтому `ApiClient` с пулом `Dispatchers.IO`
     *  общий на весь прогон: висящий запрос раннего класса может съесть поток у позднего. */
    private fun networkThreads(): String {
        val all = Thread.getAllStackTraces()
        val io = all.keys.filter { it.name.startsWith("DefaultDispatcher") || it.name.startsWith("kotlinx.coroutines") }
        val busy = io.filter { t ->
            all[t]?.any { it.className.contains("HttpURLConnection") || it.className.contains("SocketInputStream") } == true
        }
        val sample = busy.take(3).joinToString("\n") { t ->
            "      «${t.name}» → " + (all[t]?.take(3)?.joinToString(" ← ") { "${it.className.substringAfterLast('.')}.${it.methodName}" } ?: "")
        }
        return "    потоков сети всего: ${io.size}, из них сидят в сетевом вызове: ${busy.size}\n" +
            (if (sample.isBlank()) "" else "$sample\n")
    }

    /** Ответ в очередь. Идёт мимо `server.enqueue`: у сервера свой диспетчер
     *  (он отсекает чужие запросы), а `enqueue` умеет только очередь. */
    private fun enqueue(response: MockResponse) {
        enqueued++
        queue.enqueueResponse(response)
        note("тест положил ответ в очередь (всего $enqueued)")
    }

    @Before
    fun setup() {
        enqueued = 0
        server = MockWebServer()
        // ЛИШНИЙ запрос не должен вешать тест. По умолчанию MockWebServer на запрос с пустой
        // очередью НЕ отвечает вообще — соединение просто висит, и тест падает по таймауту
        // «условие не выполнено». Это выглядело как медленный раннер, но не лечилось ни 10,
        // ни 60 секундами: ждать было нечего. failFast → сразу 404, падаем по существу.
        queue = server.dispatcher as QueueDispatcher
        // Держим сам объект-заглушку: по нему отличаем «отдали заготовленный ответ»
        // от «очередь была пуста». Сравнение по коду было бы хрупким — заготовленный
        // ответ тоже может быть 404.
        failFast = MockResponse().setResponseCode(404)
        queue.setFailFast(failFast)
        // БЕЛЫЙ список, а не чёрный: очередь достаётся ровно тем адресам, ради которых тест
        // и поднят. У клиента четырнадцать фоновых запросов (`/me/update`, `/instant/presence`,
        // `/ads/*/event`, выход, пуши…), и любой из них, прилетев из ЧУЖОГО тестового класса,
        // забирал ответ, заготовленный для экрана. Чёрный список пришлось бы пополнять при
        // каждом новом фоновом запросе — а никто об этом не вспомнит.
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                if (SERVED.none { path.startsWith(it) }) {
                    note("${request.method} $path → 404 (чужой запрос, очередь не тронута)")
                    return MockResponse().setResponseCode(404)
                }
                // Пусто ли в очереди — смотрим ДО обращения: `dispatch` очередь опустошает,
                // и после него уже не отличить «отдали заготовленный» от «отдали 404 на пустой».
                val fromQueue = queue.peek() !== failFast
                val r = queue.dispatch(request)
                note("${request.method} $path → ${r.status} (${if (fromQueue) "ЗАГОТОВЛЕННЫЙ ответ" else "очередь была ПУСТА"})")
                return r
            }
        }
        // Обрываем всё фоновое, что осталось от предыдущих тестов. Область корутин у клиента
        // общая на весь процесс и раньше не отменялась никогда: «выстрелил и забыл» запрос из
        // раннего класса продолжал повторяться, когда давно шёл другой класс, — а адрес сервера
        // он перечитывает на каждой попытке и потому приходил СЮДА, съедая чужой ответ.
        ApiClient.resetForTest()
        // Короткие таймауты вместо боевых 15 секунд. С боевыми один вызов живёт до полутора
        // минут, а тест ждёт двадцать: любая заминка выглядит как «навсегда Загрузка…».
        ApiClient.testTimeoutMs = 2000
        // Выход — ДО подмены адреса: `logout()` шлёт в фоне два запроса, и после подмены они
        // прилетели бы на тестовый сервер (белый список их всё равно отсечёт, но лишний шум
        // в журнале ни к чему).
        ApiClient.logout()
        server.start()
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        startedAt = System.currentTimeMillis()
        // След самого клиента: вошли в вызов / вышли с итогом / отменили. Показывает границу
        // ответственности — если клиент вышел успешно, а экран пуст, виноват код экрана.
        ApiClient.testTrace = { note("клиент: $it") }
        note("сервер поднят: ${ApiClient.testBaseUrl}")
    }

    @After
    fun teardown() {
        // Лента событий печатается ВСЕГДА, а не только при падении (2026-08-08). Первый
        // админ-тест в прогоне стоит ~13 секунд при сети в 14 мс, а падение — это тот же тест,
        // которому не хватило двадцати. Разница зелёного и красного количественная, значит
        // «где потерялись секунды» видно и на зелёном прогоне. Уходит в `system-out` отчёта.
        println(
            "[СЛЕД ${javaClass.simpleName}] " +
                synchronized(dispatchLog) { dispatchLog.joinToString("\n    ", prefix = "\n    ") }
        )
        // Порядок важен: сначала обрываем фоновое, потом гасим сервер. Иначе недобитый запрос
        // успевает уйти уже на СЛЕДУЮЩИЙ сервер — ровно тот механизм, из-за которого класс
        // проходит поодиночке и падает в полном прогоне.
        ApiClient.testTrace = null
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        // Будим тех, кто мог заснуть на пустой очереди: выдача ответа у MockWebServer умеет
        // блокировать поток, а наш диспетчер подменял исходный — и его `shutdown()`,
        // который как раз будит спящих, не звался никогда.
        queue.shutdown()
        server.shutdown()
    }

    /**
     * Ждём появления текста — и если не дождались, рассказываем ПОЧЕМУ.
     *
     * Голый таймаут («условие не выполнено за 20 с») — бесполезное сообщение: по нему нельзя
     * отличить «сервер не ответил» от «экран показал ошибку» и от «просто не успели».
     */
    private fun waitForText(text: String) {
        try {
            composeRule.waitUntil(timeoutMillis = 20_000) {
                // Прокручиваем очередь главного потока руками. Ответ сервера приходит в фоновом
                // потоке, а обновить экран может только главный — и под Robolectric он не крутится
                // сам. `waitUntil` только спрашивает «текст уже есть?», очередь не трогая.
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
            }
            // Отметка успеха — без неё лента обрывается на «клиент вышел», и на зелёном прогоне
            // не видно, СКОЛЬКО экран шёл от полученных данных до нарисованного текста.
            note("текст «$text» ПОЯВИЛСЯ на экране")
        } catch (e: Throwable) {
            // ⚠️ Итог прокрутки — ТРИ исхода, а не два. Раньше в соседнем классе стояло
            // `.getOrElse { false }`, и любое падение самой прокрутки молча превращалось
            // в «текст не появился» → отчёт печатал вывод «продолжение не поставили вовсе».
            // Упасть тут есть чему: `advanceTimeBy`/`advanceTimeByFrame` крутят рекомпозицию
            // и перебрасывают исключение, упавшее в композиции, а `fetchSemanticsNodes`
            // падает на разрушенном дереве. Несостоявшееся измерение обязано называться
            // несостоявшимся, иначе расследование идёт по выдуманному факту.
            val pumped = runCatching {
                composeRule.mainClock.advanceTimeBy(5_000)
                repeat(50) {
                    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                    composeRule.mainClock.advanceTimeByFrame()
                }
                composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
            }
            val pumpVerdict = pumped.fold(
                onSuccess = { appeared ->
                    if (appeared) "ПОЯВИЛСЯ → ответ всё это время лежал непрокрученным"
                    else "так и НЕ появился → продолжение не поставили вовсе"
                },
                onFailure = { why ->
                    "ИЗМЕРИТЬ НЕ УДАЛОСЬ — прокрутка упала сама: " +
                        "${why::class.java.simpleName}: ${why.message}. " +
                        "Вывод о продолжении по этому прогону делать НЕЛЬЗЯ."
                },
            )
            val asked = buildList {
                while (true) {
                    val r = server.takeRequest(50, java.util.concurrent.TimeUnit.MILLISECONDS) ?: break
                    add("${r.method} ${r.path}")
                }
            }
            val screen = runCatching { composeRule.onRoot().printToString(maxDepth = 10) }
                .getOrElse { "дерево экрана прочитать не удалось: $it" }
            // Сколько РЕАЛЬНОГО времени прожил тест до падения. Число маленькое (≈20 с) — ожидание
            // честно вышло по своему таймауту, и виновата цепочка «сервер → экран». Число большое
            // (≈минута и больше) — время съели ДО ожидания, а `createComposeRule` держит тело теста
            // внутри `runTest`, у которого свой таймаут; по его срабатыванию область корутин теста
            // отменяется вместе с областью экрана, и продолжение действительно «не ставится» —
            // но виноват тогда не экран, а бюджет времени. Эти два случая до сих пор не различали.
            val livedMs = System.currentTimeMillis() - startedAt
            throw AssertionError(
                "Не дождались текста «$text».\n" +
                    "Тест прожил до падения: $livedMs мс (само ожидание — 20 000 мс)\n" +
                    "Запросов пришло на сервер: ${server.requestCount} → $asked\n" +
                    "Ответов поставлено в очередь этим тестом: $enqueued\n" +
                    "ЧТО ОТДАВАЛ СЕРВЕР (главное — было ли выдано заготовленное):\n" +
                    synchronized(dispatchLog) { dispatchLog.joinToString("\n") { "    $it" } }
                        .ifBlank { "    (сервер не принял ни одного запроса)" } + "\n" +
                    "ПОТОКИ СЕТИ (проверяем «все заняты чужими запросами»):\n" + networkThreads() +
                    "ПОСЛЕ ПРИНУДИТЕЛЬНОЙ ПРОКРУТКИ ОЧЕРЕДЕЙ текст «$text» " + pumpVerdict + "\n" +
                    "Что было на экране (до прокрутки):\n$screen",
                e,
            )
        }
    }

    @Test
    fun screenLoadsReviewsFromServerAndDisplaysThem() {
        enqueue(
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
        enqueue(MockResponse().setResponseCode(200).setBody("""{"items":[]}"""))
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                AdminReviewsScreen(onBack = {})
            }
        }
        waitForText("Новых отзывов нет")
        composeRule.onNodeWithText("Всё разобрано").assertExists()
    }
}
