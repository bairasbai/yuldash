package com.yuldash.app.walk.l1_5

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.CouponsScreen
import com.yuldash.app.data.ApiClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * leaf-1.5 (CouponsScreen.kt): купон нельзя погасить дважды двойным нажатием, ошибки сервера/сети
 * на витрине показывают понятный текст с «Повторить», и повтор реально восстанавливает экран.
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
    private val couponsListAttempts = AtomicInteger(0)

    private enum class FailureMode { NONE, SERVER_500_ONCE, NETWORK_DROP_ONCE }
    @Volatile private var couponsListFailureMode = FailureMode.NONE

    private val couponJson = """
        {"id":501,"partner":{"id":9,"name":"Кафе Тестовое","category":"cafe","city":"Уфа",
         "address":"ул. Тестовая, 1","phone":"+79990000000"},
         "title":"Скидка на обед","description":"Проверочный купон","discount_text":"-20%",
         "city":"Уфа","route_hint":[],"valid_from":null,"valid_until":"2026-07-05T21:00:00",
         "limit_total":100,"limit_per_user":1,"redeemed_count":0,"remaining":5,"premium":false,
         "status":"active"}
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
                    return when {
                        path == "/me" -> json("{}")
                        path == "/coupons" -> {
                            val attempt = couponsListAttempts.incrementAndGet()
                            when {
                                // Пустое тело, БЕЗ detail.ru/ba — сервер не всегда успевает отдать
                                // переведённую причину (например, сам упал); проверяем именно общий
                                // по коду текст из ApiClient.genericByStatus, а не доверенный "boom".
                                attempt == 1 && couponsListFailureMode == FailureMode.SERVER_500_ONCE ->
                                    json("{}", 500)
                                attempt == 1 && couponsListFailureMode == FailureMode.NETWORK_DROP_ONCE ->
                                    MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                                else -> json("""{"items":[$couponJson]}""")
                            }
                        }
                        path == "/coupons/501" -> json(couponJson)
                        path == "/coupons/501/activate" -> {
                            activateRequests.add("${request.method} $path")
                            json(
                                """{"code":"ABC123","status":"reserved","reserved_at":"2026-07-01T00:00:00",
                                    "coupon":$couponJson}""",
                            )
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
