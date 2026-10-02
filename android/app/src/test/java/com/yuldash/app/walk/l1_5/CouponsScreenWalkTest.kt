package com.yuldash.app.walk.l1_5

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.AppLanguage
import com.yuldash.app.CouponsScreen
import com.yuldash.app.LocalAppLanguage
import com.yuldash.app.data.ApiClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * leaf-1.5 (CouponsScreen.kt): купон нельзя погасить дважды двойным нажатием, ошибки сервера/сети
 * на витрине показывают понятный текст с «Повторить», повтор реально восстанавливает экран; срок
 * купона на экране — реальная дата из фикстуры; смена города не ловит гонку ответов; истёкший
 * купон в «Моих купонах» честно помечен; жалоба не теряет текст при неудаче.
 *
 * Сеть — настоящий `MockWebServer` вместо живого `yulbash.ru` (пункт 0 CLAUDE.md: к боевому
 * серверу не обращаемся); `ApiClient.testBaseUrl`/`testTimeoutMs` — штатные тест-хуки самого клиента.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CouponsScreenWalkTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var server: MockWebServer
    private val activateRequests = CopyOnWriteArrayList<String>()
    private val reportRequests = CopyOnWriteArrayList<String>()
    private val couponsListAttempts = AtomicInteger(0)

    private enum class FailureMode { NONE, SERVER_500_ONCE, NETWORK_DROP_ONCE }
    @Volatile private var couponsListFailureMode = FailureMode.NONE
    @Volatile private var meCity: String? = null
    @Volatile private var myCouponsBody: String = """{"items":[]}"""
    @Volatile private var reportFailsOnce = false

    // Гонка городов (R: NearbyCouponsTab): запрос «все города» блокируется до явного разрешения,
    // запрос «по городу» отвечает сразу — так тест детерминированно воспроизводит «поздний ответ
    // старого запроса приходит ПОСЛЕ свежего», а не надеется на случайную разницу в миллисекундах.
    private val allCitiesRequestReceived = CountDownLatch(1)
    private val allowAllCitiesResponse = CountDownLatch(1)

    private fun couponJsonWithValidUntil(validUntil: String) = """
        {"id":501,"partner":{"id":9,"name":"Кафе Тестовое","category":"cafe","city":"Белорецк",
         "address":"ул. Тестовая, 1","phone":"+79990000000"},
         "title":"Скидка на обед","description":"Проверочный купон","discount_text":"-20%",
         "city":"Белорецк","route_hint":[],"valid_from":null,"valid_until":"$validUntil",
         "limit_total":100,"limit_per_user":1,"redeemed_count":0,"remaining":5,"premium":false,
         "status":"active"}
    """.trimIndent()

    // Реальная прод-запись (разбор ревью): форма партнёра просила «до 31.10», `client_dt_to_utc`
    // положил это как 00:00 31.10 по Уфе → "2026-10-30T19:00:00" UTC. Правильный показ — 30.10
    // (исключающая граница `valid_until`, см. CouponLastAcceptedDayTest).
    private val couponJson = couponJsonWithValidUntil("2026-10-30T19:00:00")
    private val staleCityCouponJson = """
        {"id":777,"partner":{"id":1,"name":"Кафе Уфимское","category":"cafe","city":"Уфа",
         "address":"","phone":""},"title":"Чужой купон","description":"","discount_text":"-10%",
         "city":"Уфа","route_hint":[],"valid_from":null,"valid_until":null,"limit_total":0,
         "limit_per_user":1,"redeemed_count":0,"remaining":null,"premium":false,"status":"active"}
    """.trimIndent()

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ApiClient.resetForTest()
        ApiClient.init(context)
        ApiClient.saveToken("local-passenger")
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    val city = request.requestUrl!!.queryParameter("city")
                    return when {
                        path == "/me" -> json(meCity?.let { """{"city":"$it"}""" } ?: "{}")
                        path == "/coupons" && city == null -> {
                            // «Все города» — ровно тот запрос, что уходит ДО того, как подтянется
                            // город из профиля. Блокируем до явного разрешения тестом.
                            allCitiesRequestReceived.countDown()
                            val attempt = couponsListAttempts.incrementAndGet()
                            when {
                                attempt == 1 && couponsListFailureMode == FailureMode.SERVER_500_ONCE ->
                                    json("{}", 500)
                                attempt == 1 && couponsListFailureMode == FailureMode.NETWORK_DROP_ONCE ->
                                    MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                                meCity != null -> {
                                    // В тесте гонки город из профиля ЕСТЬ — значит это намеренно
                                    // устаревший запрос «до подтягивания города». Держим его, пока
                                    // тест сам не отпустит, и отвечаем ЧУЖИМ купоном.
                                    if (!allowAllCitiesResponse.await(10, TimeUnit.SECONDS)) return json("{}", 504)
                                    json("""{"items":[$staleCityCouponJson]}""")
                                }
                                else -> json("""{"items":[$couponJson]}""")
                            }
                        }
                        path == "/coupons" -> json("""{"items":[$couponJson]}""")   // city == "Белорецк"
                        path == "/coupons/501" -> json(couponJson)
                        path == "/coupons/501/activate" -> {
                            activateRequests.add("${request.method} $path")
                            json(
                                """{"code":"ABC123","status":"reserved","reserved_at":"2026-07-01T00:00:00",
                                    "coupon":$couponJson}""",
                            )
                        }
                        path == "/coupons/501/report" -> {
                            reportRequests.add(request.body.readUtf8())
                            // Пустое тело 500, БЕЗ detail.ru/ba — общий текст по коду
                            // (ApiClient.genericByStatus), не «activate»-текст и не серверный detail.
                            // Мгновенный ответ — обрыв сети (NO_RESPONSE+реальный таймаут) внутри
                            // открытого AlertDialog не даёт Compose settle(), тест висит и падает по
                            // времени независимо от самой проверки; сути фикса это не меняет.
                            if (reportRequests.size == 1 && reportFailsOnce) json("{}", 500)
                            else json("""{"already":false}""")
                        }
                        path == "/my/coupons" -> json(myCouponsBody)
                        else -> json("{}", 404)
                    }
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 1000
    }

    @After
    fun cleanup() {
        ApiClient.logout()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server.shutdown()
    }

    // R1: «Активировать скидку» не шлёт второй запрос, пока первый не завершился.
    @Test
    fun activateButtonSendsExactlyOneRequestEvenWhenTappedTwiceInARow() {
        compose.setContent { CouponsScreen(onBack = {}) }
        waitForText("Кафе Тестовое")
        compose.onNodeWithText("Кафе Тестовое").performClick()
        waitForText("Активировать скидку")

        // Один и тот же захваченный обработчик, вызванный дважды подряд БЕЗ промежуточного
        // ожидания — именно та гонка двойного тапа, от которой защищает
        // `if (activating) return@AppButton` в CouponDetailView. Кнопка реально становится
        // недоступной для тапа сразу после первого нажатия (Compose снимает действие OnClick
        // целиком, пока она disabled — второй живой performClick() здесь просто упал бы, не
        // проверив внутреннюю защиту). Поэтому берём ссылку на сам обработчик ОДИН раз, пока
        // кнопка ещё активна, и вызываем её напрямую дважды — это и есть гонка, от которой код
        // защищается. ВАЖНО: без `useUnmergedTree` — слитое дерево даёт узел самой кнопки (с её
        // OnClick), а не внутренний текстовый узел, у которого своего действия клика нет.
        val node = compose.onNodeWithText("Активировать скидку").fetchSemanticsNode()
        val onClick = node.config[SemanticsActions.OnClick].action!!
        onClick()
        onClick()

        waitForText("Твой код")
        assertEquals("activate must fire exactly once for two rapid taps", 1, activateRequests.size)
        // R2: реальная прод-запись («до 31.10» по форме партнёра) должна показывать 30.10 —
        // последний день, когда кассa ещё примет код (исключающая граница valid_until).
        waitForText("Действует до 30.10.2026")
    }

    // R3: ошибка сервера на витрине — понятный текст и «Повторить», который реально чинит экран.
    @Test
    fun nearbyListShowsServerErrorThenRecoversOnRetry() {
        couponsListFailureMode = FailureMode.SERVER_500_ONCE
        compose.setContent { CouponsScreen(onBack = {}) }
        // Свой detail от сервера не пришёл — общий текст по коду 500 из ApiClient.genericByStatus.
        waitForText("Ошибка сервера. Попробуй позже.")
        compose.onNodeWithText("Повторить").performClick()
        waitForText("Кафе Тестовое")
    }

    // R3: обрыв сети — тоже понятный текст (свой у экрана, не серверный) и рабочее «Повторить».
    @Test
    fun nearbyListShowsConnectionDropMessageThenRecoversOnRetry() {
        couponsListFailureMode = FailureMode.NETWORK_DROP_ONCE
        compose.setContent { CouponsScreen(onBack = {}) }
        waitForText("Не удалось загрузить скидки. Проверь интернет.")
        compose.onNodeWithText("Повторить").performClick()
        waitForText("Кафе Тестовое")
    }

    // R (новое, из независимого ревью): поздний ответ устаревшего запроса «все города» не должен
    // переписать свежий ответ по городу, пришедший позже него по времени запроса, но раньше по
    // времени ответа.
    @Test
    fun lateResponseForStaleAllCitiesRequestDoesNotOverwriteNewerCityResult() {
        meCity = "Белорецк"
        compose.setContent { CouponsScreen(onBack = {}) }
        // Подтверждаем, что гонка реально началась: запрос «все города» ушёл и застрял.
        assertTrue(
            "stale all-cities request must actually have been sent",
            allCitiesRequestReceived.await(10, TimeUnit.SECONDS),
        )
        // Запрос по городу (из LaunchedEffect(cityFilter), сработавшего после me()) не блокируется
        // и должен победить.
        waitForText("Кафе Тестовое")
        // Отпускаем устаревший ответ и даём очереди эффектов шанс его обработать.
        allowAllCitiesResponse.countDown()
        repeat(20) { settle(); compose.mainClock.advanceTimeByFrame() }
        assertEquals(
            "stale response must not resurrect the old city's coupon",
            0,
            compose.onAllNodesWithText("Кафе Уфимское").fetchSemanticsNodes().size,
        )
        assertTrue(compose.onAllNodesWithText("Кафе Тестовое").fetchSemanticsNodes().isNotEmpty())
    }

    // R (новое, из независимого ревью): сервер никогда не ставит статус "expired" у брони
    // (известное ограничение, лист 1.3) — купон с истёкшим valid_until не должен выглядеть
    // действующим («Ждёт показа» + живой код).
    @Test
    fun myCouponsShowsExpiredStatusAndHidesCodeWhenPastValidUntil() {
        myCouponsBody = """{"items":[{"code":"OLD123","status":"reserved","reserved_at":"2020-01-01T00:00:00",
            "redeemed_at":null,"coupon":${couponJsonWithValidUntil("2020-01-02T00:00:00")}}]}"""
        compose.setContent { CouponsScreen(onBack = {}) }
        compose.onNodeWithText("Мои купоны").performClick()
        waitForText("Истёк")
        settle()
        assertEquals(
            "expired coupon must not show a still-usable 'waiting to be shown' status",
            0,
            compose.onAllNodesWithText("Ждёт показа").fetchSemanticsNodes().size,
        )
        assertEquals(
            "expired coupon's code must not be shown as if it were still usable",
            0,
            compose.onAllNodesWithText("OLD123").fetchSemanticsNodes().size,
        )
    }

    // R (новое, из независимого ревью): неудачная жалоба на купон показывала чужой текст
    // («Не получилось активировать») и теряла набранный текст (диалог закрывался до ответа).
    @Test
    fun reportFailureShowsItsOwnErrorAndKeepsTheTypedReason() {
        reportFailsOnce = true
        compose.setContent { CouponsScreen(onBack = {}) }
        waitForText("Кафе Тестовое")
        compose.onNodeWithText("Кафе Тестовое").performClick()
        waitForText("Тут что-то не так — сообщить")
        // AlertDialog имеет собственную анимацию появления (как бесконечный спиннер в
        // AppReviewFormContentTest) — под авточасами Compose остаётся «занято» и ПЕРВЫЙ же
        // waitForText после открытия диалога висит до таймаута Espresso. Гасим авточасы ДО
        // открытия (без скролла в этом диалоге — дедлока, как предупреждают там же, не будет) и
        // дальше сверяемся через settle()/fetchSemanticsNodes() напрямую, а не через waitUntil.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Тут что-то не так — сообщить").performClick()
        repeat(30) { settle(); compose.mainClock.advanceTimeByFrame() }
        assertTrue(compose.onAllNodesWithText("Что не так с этой скидкой?").fetchSemanticsNodes().isNotEmpty())
        // Единственное поле ввода на экране в этот момент — диалог жалобы, его детали (плейсхолдер)
        // необязательно попадают в семантику так же, как подпись; ищем по способности принимать текст.
        val reasonField = compose.onNode(hasSetTextAction())
        reasonField.performTextInput("Скидку не дали")
        compose.onNodeWithText("Отправить").performClick()
        repeat(30) { settle(); compose.mainClock.advanceTimeByFrame() }
        // Сервер не прислал detail.ru/ba — общий текст по коду 500 (ApiClient.genericByStatus),
        // НЕ «activate»-текст.
        assertTrue(compose.onAllNodesWithText("Ошибка сервера. Попробуй позже.").fetchSemanticsNodes().isNotEmpty())
        // Чужой текст («...активировать») не должен появляться на экране ошибки жалобы.
        assertEquals(0, compose.onAllNodesWithText("Не получилось активировать", substring = true).fetchSemanticsNodes().size)
        // Диалог остался открытым, набранный текст не потерян.
        assertTrue(compose.onAllNodesWithText("Скидку не дали", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    // R (новое, из независимого ревью): жёсткая height(48.dp) резала башкирскую подпись вкладки
    // («Яҡындағы ташламалар» на половине ширины узкого экрана) на крупном системном шрифте —
    // heightIn(min=48dp) должен позволить вкладке вырасти под две строки вместо обрезки.
    @Test
    fun couponTabGrowsBeyondMinHeightForWrappedBashkirLabelAtLargeFontScale() {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(
                LocalAppLanguage provides AppLanguage.Ba,
                LocalDensity provides Density(density, fontScale = 2.5f),
            ) {
                CouponsScreen(onBack = {})
            }
        }
        waitForText("Яҡындағы ташламалар")
        // Без useUnmergedTree: слитое дерево даёт узел самой вкладки (Surface), а не внутренний
        // текстовый узел — нас интересует, выросла ли ВКЛАДКА, а не тесная рамка вокруг текста.
        val bounds = compose.onNodeWithText("Яҡындағы ташламалар").getUnclippedBoundsInRoot()
        val height = bounds.bottom - bounds.top
        assertTrue(
            "tab must grow past the 48dp minimum to fit a wrapped label at large font scale, " +
                "not stay clipped at a fixed height: $height",
            height > 48.dp,
        )
    }

    private fun settle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        Snapshot.sendApplyNotifications()
    }

    private fun waitForText(text: String) {
        compose.waitUntil(15000) {
            settle()
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code)
        .setHeader("Content-Type", "application/json").setBody(body)
}
