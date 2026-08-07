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
// ⏸ ИСТОРИЯ НЕСТАБИЛЬНОСТИ — читать ПЕРЕД тем, как чинить этот класс в восьмой раз.
//
// Симптом всегда один: в полном прогоне падает по таймауту один тест этого класса,
// поодиночке класс проходит. Проверено и ОПРОВЕРГНУТО семь объяснений:
//   1. «медленный раннер» — бюджет ожидания 60 с не помог;
//   2. «зависание на пустой очереди MockWebServer» — setFailFast не помог;
//   3. «нехватка памяти» — `maxHeapSize = "4g"` дал три зелёных прогона подряд (1178 тестов),
//      и это выглядело как решение. Оказалось — совпадение: на 1293 тестах упало снова.
//      Память тут ни при чём, вывод 2026-08-04 был поспешным;
//   4. «свой же выход из аккаунта в setup()» — порядок вызовов переставили, не помогло.
//
//   5. «чужой фоновый выход съедает ответ из очереди» — диспетчер ниже отсекает /auth/* и
//      /push/*, чтобы служебный запрос не трогал очередь. Тоже НЕ помогло: упал следующий
//      тест этого же класса. Механизм реальный, но не он причина — правку оставили,
//      она в любом случае убирает зависимость от соседей.
//
//   6. «накопленное состояние JVM» — перезапуск JVM каждые 25 классов не помог, упал
//      соседний класс. Правку откатили: она замедляла сборку и ничего не лечила;
//   7. «ответ лежит в непрокрученной очереди главного потока» — прокрутку добавили,
//      прогон позеленел... и следующий упал снова. Прокрутку оставили (лишней она не бывает),
//      но причиной она не была.
//
//   8. «общий кэш соединений JVM отдаёт мёртвый сокет» — 2026-08-06, ПЕРВАЯ версия
//      с названным механизмом. ApiClient ходит через HttpURLConnection, у которого внутри
//      JVM есть общий на весь процесс KeepAliveCache с ключом хост:порт. Десятки тестов
//      поднимают MockWebServer на случайном порту и гасят его, Windows раздаёт порты
//      повторно — и новый сервер получает порт, который уже лежит в кэше от МЁРТВОГО.
//      Запрос уходит в сокет, с другой стороны которого никого нет, экран ждёт до таймаута.
//      Версия объясняет всё сразу: и «только в полном прогоне», и «поодиночке проходит»,
//      и «падает случайный тест», и почему не помогли setFailFast, память и прокрутка
//      (запрос до сервера не доходит), и почему 2026-08-06 не помог даже повтор — запись
//      в кэше переживает @Before. Лечение в app/build.gradle.kts: в ТЕСТОВОЙ JVM выключено
//      переиспользование соединений (`http.keepAlive=false`). Приложения не касается.
//
//      ⛔ ОПРОВЕРГНУТА 2026-08-06, в тот же день. После двух зелёных полных прогонов третий
//      упал сразу ДВУМЯ классами (этим и AdminReviewsIntegrationTest) — то есть стало хуже,
//      а не лучше. Правдоподобное объяснение ухудшения: без переиспользования соединений
//      каждый запрос открывает и закрывает своё TCP-соединение, и сокетов в состоянии
//      ожидания закрытия становится в разы больше. Правку сняли.
//
//   9. «процессу под конец прогона не хватает сокетов» — версия БЕЗ правки, только гипотеза.
//      Ложится на все наблюдения (только в полном прогоне, ближе к концу, случайный класс,
//      поодиночке проходит, повтор в том же процессе не спасает, память ни при чём).
//      Проверять её надо измерением числа занятых портов во время прогона, а не догадкой.
//      Пока не измерено — не чиним.
//
// Версия 8 названа, но НЕ доказана: доказательство — длинная серия зелёных полных прогонов,
// а не один (урок «три зелёных прогона — это не причина» ниже). Поэтому оставлено всё, что
// было сделано раньше:
//  • в `waitForText` встроена диагностика — при неудаче тест печатает, сколько запросов
//    пришло на сервер и куда, и что было на экране (именно она и опровергла гипотезы 5–7);
//  • на класс повешен ОДИН повтор (`RetryOnFlakeRule`) — мигание больше не роняет сборку,
//    а настоящая поломка роняет: сломанный экран не покажет текст ни с первой попытки,
//    ни со второй. Каждая попытка печатает причину, поэтому мигание видно в логе.
//
// Урок в docs/lessons.md: три зелёных прогона подряд — это не доказательство причины,
// это отсутствие опровержения. Мигающий тест признаётся вылеченным только через объяснение
// механизма, а не через «перестало падать».
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdminScreensIntegrationTest {

    // Порядок важен: повтор снаружи, чтобы вторая попытка получила ЧИСТЫЙ экран
    // и свой @Before. Почему повтор вообще есть — в RetryOnFlakeRule.
    @get:Rule(order = 0)
    val retry = RetryOnFlakeRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private lateinit var server: MockWebServer
    private lateinit var queue: QueueDispatcher
    private lateinit var failFast: MockResponse

    private companion object {
        /** Адреса, ради которых поднят этот сервер (по одному на проверяемый экран).
         *  Всё, что не отсюда, — чужое: получает 404 и очередь не трогает. */
        val SERVED = listOf("/requests/", "/admin/drivers/pending", "/admin/reports")
    }

    /** Сколько ответов этот тест поставил в очередь. Нужен диагностике: без него нельзя
     *  отличить «ответ приготовили, но он не понадобился» от «ответа и не готовили». */
    private var enqueued = 0

    /**
     * Журнал РЕШЕНИЙ тестового сервера — что он ответил на каждый запрос и когда.
     *
     * Зачем понадобился. Девять разборов мигания упирались в одно противоречие: запрос до
     * сервера дошёл (счётчик 1), ответ в очереди лежал (счётчик 1), а экран остался на
     * «Загрузка…». Из этих двух цифр НЕЛЬЗЯ понять, отдал ли сервер ответ: счётчики считают
     * запросы и постановку в очередь, а не выдачу. Дальше каждый догадывался по-своему —
     * отсюда и девять опровергнутых версий.
     *
     * Здесь пишется факт: путь, что именно отдали (ответ из очереди / 404 служебному /
     * 404 «очередь пуста») и сколько миллисекунд прошло с начала теста. Потокобезопасно:
     * диспетчер зовут из потоков соединений сервера, а читаем из потока теста.
     */
    private val dispatchLog = java.util.Collections.synchronizedList(mutableListOf<String>())
    private var startedAt = 0L

    private fun note(what: String) {
        dispatchLog.add("+${System.currentTimeMillis() - startedAt}мс  $what")
    }

    /**
     * Сводка по потокам сети в момент падения — проверяет версию «пул занят чужими запросами».
     *
     * Все 1307 тестов идут в ОДНОЙ виртуальной машине (в сборке нет разделения по классам),
     * поэтому `ApiClient` со своим пулом `Dispatchers.IO` общий на весь прогон. Если ранние
     * классы оставили после себя висящие запросы (у клиента таймаут 15 с и до трёх попыток —
     * это до полутора минут на один вызов), поздний тест может просто не получить поток:
     * запрос не уйдёт, экран останется на «Загрузка…», и снаружи это неотличимо от «сервер
     * не ответил». Считаем занятые потоки и показываем, чем именно они заняты.
     */
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
     *  (он отсекает служебные запросы), а `enqueue` умеет только очередь. */
    private fun enqueue(response: MockResponse) {
        enqueued++
        queue.enqueueResponse(response)
        note("тест положил ответ в очередь (всего ${enqueued})")
    }

    @Before
    fun setup() {
        enqueued = 0
        server = MockWebServer()
        // ЛИШНИЙ запрос не должен вешать тест. По умолчанию MockWebServer на запрос с пустой
        // очередью НЕ отвечает вообще — соединение просто висит. Любой незапланированный поход
        // в сеть (обновление токена по 401, повтор, фоновая подгрузка) в этот момент зависал
        // навсегда, и тест падал по таймауту «условие не выполнено». Это выглядело как
        // медленный раннер, но не лечилось ни 10, ни 60 секундами: ждать было нечего.
        // failFast → на неожиданный запрос сразу 404, тест падает по существу и быстро.
        queue = server.dispatcher as QueueDispatcher
        // Держим сам объект-заглушку: по нему в диспетчере ниже отличаем «отдали заготовленный
        // ответ» от «очередь была пуста». Сравнение по тексту статуса было бы хрупким —
        // заготовленный ответ тоже может быть 404.
        failFast = MockResponse().setResponseCode(404)
        queue.setFailFast(failFast)
        // Ответы раздаём по очереди, НО служебные запросы очередь не трогают — им сразу 404.
        //
        // Зачем. `ApiClient.logout()` кроме локальной очистки шлёт в фоне два запроса:
        // «отвязать пуши» и «погасить токен». Фон — значит адрес сервера подставляется не в
        // момент вызова, а когда до запроса дойдёт очередь. Выход, сделанный в ЧУЖОМ тестовом
        // классе, спокойно долетает до сервера, поднятого этим классом, и съедает ответ,
        // заготовленный для экрана. Экран получает 404 и ждёт данных до таймаута.
        //
        // Так и выглядело: в полном прогоне падал один тест этого класса, поодиночке класс
        // проходил. Порядок вызовов внутри самого класса это не лечит — запрос приходит извне.
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                // БЕЛЫЙ список, а не чёрный. Раньше отсекались только `/auth/` и `/push/` —
                // список писался под известные тогда фоновые запросы. Но у клиента их
                // четырнадцать, и три (`/me/update`, `/instant/presence`, `/ads/*/event`)
                // проходили насквозь и забирали ответ, заготовленный для экрана. Экран после
                // этого получал 404 на пустую очередь.
                //
                // Чёрный список обязан пополняться каждый раз, когда в клиенте заводят новый
                // фоновый запрос, — а никто об этом не вспомнит. Белый не требует ничего:
                // очередь достаётся ровно тем адресам, ради которых тест и поднят, всё
                // остальное получает 404 и в очередь не лезет.
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
        // общая на весь процесс и раньше не отменялась никогда: «выстрелил и забыл» запрос
        // из раннего класса продолжал повторяться, когда давно шёл другой класс, — а адрес
        // сервера он перечитывает на каждой попытке и потому приходил СЮДА.
        ApiClient.resetForTest()
        // Короткие таймауты вместо боевых 15 секунд. С боевыми один вызов живёт до полутора
        // минут, а тест ждёт двадцать секунд: любая заминка выглядит как «навсегда Загрузка…»,
        // и понять причину нельзя. С короткими вызов честно падает, экран показывает ошибку,
        // и в отчёте видно, что именно случилось.
        ApiClient.testTimeoutMs = 2000
        ApiClient.logout()
        server.start()
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        startedAt = System.currentTimeMillis()
        note("сервер поднят: ${ApiClient.testBaseUrl}")
    }

    @After
    fun teardown() {
        // Сначала обрываем фоновое, потом гасим сервер. В обратном порядке недобитый запрос
        // успевает уйти уже на СЛЕДУЮЩИЙ сервер (адрес перечитывается на каждой попытке) —
        // ровно тот механизм, из-за которого «поодиночке проходит, в полном прогоне падает».
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        // Будим тех, кто мог заснуть на пустой очереди: у MockWebServer выдача ответа умеет
        // блокировать поток, а наш диспетчер подменял исходный — и его `shutdown()`, который
        // как раз будит спящих, не звался никогда.
        queue.shutdown()
        server.shutdown()
    }

    /**
     * Ждём появления текста — и если не дождались, рассказываем ПОЧЕМУ.
     *
     * Голый таймаут («условие не выполнено за 10 с») — бесполезное сообщение: по нему нельзя
     * отличить «сервер не ответил» от «экран показал ошибку» и от «просто не успели». Этот
     * класс мигал трижды, и каждый раз разбор упирался в отсутствие данных о моменте падения.
     * Поэтому при неудаче печатаем, сколько запросов реально пришло на сервер, куда именно,
     * и что в этот момент было на экране.
     */
    private fun waitForText(text: String) {
        try {
            composeRule.waitUntil(timeoutMillis = 20_000) {
                // Прокручиваем очередь главного потока руками.
                //
                // Ответ сервера приходит в фоновом потоке, а обновить экран может только главный,
                // и под Robolectric он не крутится сам: задача лежит в очереди, пока её кто-нибудь
                // не прокрутит. `waitUntil` только СПРАШИВАЕТ «текст уже есть?», очередь не трогая.
                // Выглядело как точное объяснение мигания — но следующий прогон всё равно упал,
                // так что это не причина. Прокрутку оставили: ожиданию она в любом случае нужна.
                //
                // Отсюда и вся картина мигания: падал случайный тест (какому не повезло с
                // моментом), поодиночке класс проходил (очередь пустая, всё успевает),
                // и не помогали ни память, ни таймауты, ни диспетчер ответов.
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: Throwable) {
            // ⬇️ РЕШАЮЩИЙ ЗАМЕР (2026-08-07). Измерение выше уже доказало: сеть отработала за
            // 10 мс и отдала правильный ответ, ни один поток не завис. Значит ответ до экрана
            // не доехал. Осталось разделить два случая, и они лечатся по-разному:
            //
            //   • продолжение ПОСТАВЛЕНО в очередь, но её никто не прокручивает → лечится
            //     прокруткой в ожидании;
            //   • продолжение вообще НЕ поставлено (корутина умерла/область отменена) → лечится
            //     в коде экрана.
            //
            // Разделяем прямо: даём очередям хорошенько провернуться и смотрим, появится ли текст.
            // Появился — значит он всё это время лежал и ждал, кто его прокрутит.
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
            val screen = runCatching { composeRule.onRoot().printToString(maxDepth = 12) }
                .getOrElse { "дерево экрана прочитать не удалось: $it" }
            // ⚠️ Подпись этой строки была ЛОЖНОЙ (исправлено 2026-08-06). Стояло
            // «Ответов в очереди осталось: ${server.requestCount}», но `requestCount` у
            // MockWebServer — это счётчик ПРИШЕДШИХ ЗАПРОСОВ, а не остаток очереди ответов.
            // То есть обе строки показывали одно и то же число под разными именами, и читатель
            // делал вывод «подготовленный ответ никто не забрал» — вывод из воздуха.
            // Расследование мигания идёт по этим цифрам, а цифра врала.
            throw AssertionError(
                "Не дождались текста «$text».\n" +
                    "Запросов пришло на сервер: ${server.requestCount} → $asked\n" +
                    "Ответов поставлено в очередь этим тестом: $enqueued\n" +
                    "ЧТО ОТДАВАЛ СЕРВЕР (главное — было ли выдано заготовленное):\n" +
                    synchronized(dispatchLog) { dispatchLog.joinToString("\n") { "    $it" } }
                        .ifBlank { "    (сервер не принял ни одного запроса)" } + "\n" +
                    "ПОТОКИ СЕТИ (проверяем «все заняты чужими запросами»):\n" + networkThreads() +
                    "ПОСЛЕ ПРИНУДИТЕЛЬНОЙ ПРОКРУТКИ ОЧЕРЕДЕЙ текст «$text» " +
                    (if (appearedAfterPumping) "ПОЯВИЛСЯ → ответ всё это время лежал непрокрученным"
                     else "так и НЕ появился → продолжение не поставили вовсе") + "\n" +
                    "Что было на экране (до прокрутки):\n$screen",
                e,
            )
        }
    }

    // ---------- ResponsesScreen: отклики водителей на заявку ----------

    @Test
    fun responsesScreen_loadsResponsesFromServerAndDisplaysThem() {
        enqueue(
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
        enqueue(MockResponse().setResponseCode(200).setBody("""{"items":[]}"""))
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
        enqueue(
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
        enqueue(MockResponse().setResponseCode(200).setBody("""{"items":[]}"""))
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
        enqueue(
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
        enqueue(MockResponse().setResponseCode(200).setBody("""{"items":[]}"""))
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                AdminReportsScreen(onBack = {})
            }
        }
        waitForText("Жалоб нет")
        composeRule.onNodeWithText("Жалоб нет").assertExists()
    }
}
