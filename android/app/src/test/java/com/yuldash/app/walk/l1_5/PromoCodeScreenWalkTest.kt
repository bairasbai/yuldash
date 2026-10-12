package com.yuldash.app.walk.l1_5

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.AppLanguage
import com.yuldash.app.LocalAppLanguage
import com.yuldash.app.PromoCodeScreen
import com.yuldash.app.data.ApiClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * leaf-1.5 (PromoCodeScreen.kt): промокод нельзя применить дважды двойным нажатием, скидка на
 * такси показывается верно и исчезает, когда скидки нет (без «0 ₽»), ошибка первой загрузки даёт
 * понятный текст и рабочее «Повторить», успех говорит на языке интерфейса.
 *
 * Заголовок экрана и подпись поля ввода — оба буквально «Промокод» (`PromoCodeScreen.kt`:
 * `ScreenTopBar` и `label` поля используют один и тот же `appText`), поэтому поле ищем через
 * `hasSetTextAction()`, а не голым `onNodeWithText` — иначе лишний совпавший узел роняет тест
 * с «Found 2 nodes…», а не с содержательной причиной.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PromoCodeScreenWalkTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var server: MockWebServer
    private val applyRequests = CopyOnWriteArrayList<String>()
    private val mineAttempts = AtomicInteger(0)

    @Volatile private var mineFailsOnce = false
    /** null = «кода ещё нет»; иначе отдаём готовый JSON объекта `{"promo":{...}, "discount_kop":…}`. */
    @Volatile private var minePromo: String? = null
    @Volatile private var applyDiscountKop = 15050
    @Volatile private var applyKind = "taxi_ride"
    @Volatile private var applyPerkValue = 0
    @Volatile private var applyFailsWith409Once = false
    /** Что вернёт `/promo/mine` ПОСЛЕ неудачного `/promo/apply` — для теста восстановления после 409. */
    @Volatile private var recoveredPromoJson: String? = null

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
                    return when (path) {
                        "/promo/mine" -> {
                            val attempt = mineAttempts.incrementAndGet()
                            when {
                                // Пустое тело, БЕЗ detail.ru/ba — проверяем общий по коду текст из
                                // ApiClient.genericByStatus, а не доверенную строку сервера.
                                attempt == 1 && mineFailsOnce -> json("{}", 500)
                                // После неудачного /promo/apply экран сверяет реальное состояние —
                                // отдаём ЕГО, а не «кода ещё нет».
                                applyRequests.isNotEmpty() && recoveredPromoJson != null -> json(recoveredPromoJson!!)
                                else -> json(minePromo ?: """{"promo":null}""")
                            }
                        }
                        "/promo/apply" -> {
                            applyRequests.add(request.body.readUtf8())
                            if (applyRequests.size == 1 && applyFailsWith409Once) {
                                // Таймаут на самом первом нажатии: сервер код мог уже принять, а
                                // ответ потерялся — повтор получает 409 «уже активировал».
                                json(
                                    """{"detail":{"ru":"Ты уже активировал промокод",
                                        "ba":"Һин промокодты активлаштырҙың инде"}}""",
                                    409,
                                )
                            } else {
                                json(
                                    """{"ok":true,"kind":"$applyKind","perk_value":$applyPerkValue,
                                        "message_ru":"Готово — скидка уже ждёт","message_ba":"Әҙер — ташлама көтә",
                                        "discount_kop":$applyDiscountKop}""",
                                )
                            }
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

    // R1: «Применить» не шлёт второй запрос, пока первый не завершился.
    @Test
    fun applyButtonSendsExactlyOneRequestEvenWhenTappedTwiceInARow() {
        compose.setContent { PromoCodeScreen(onBack = {}) }
        waitForText("Есть промокод?")
        codeField().performTextInput("YULDASH100")

        // Один и тот же захваченный обработчик, вызванный дважды подряд — гонка двойного тапа, от
        // которой защищает `if (busy || code.isBlank()) return@AppButton`. Кнопка сама становится
        // недоступной для тапа сразу после первого нажатия (Compose снимает действие OnClick целиком,
        // пока она disabled), поэтому берём ссылку на обработчик один раз, пока кнопка ещё активна,
        // и вызываем её напрямую дважды — так проверяется именно внутренняя защита. ВАЖНО: без
        // `useUnmergedTree` — слитое дерево даёт узел самой кнопки (с её OnClick), а не внутренний
        // текстовый узел, у которого своего действия клика нет.
        val node = compose.onNodeWithText("Применить").fetchSemanticsNode()
        val onClick = node.config[SemanticsActions.OnClick].action!!
        onClick()
        onClick()

        waitForText("Промокод применён!")
        assertEquals("apply must fire exactly once for two rapid taps", 1, applyRequests.size)
    }

    // R4: ошибка первой загрузки «моего промокода» — понятный текст и рабочее «Повторить».
    @Test
    fun initialLoadErrorShowsRetryThenRecovers() {
        mineFailsOnce = true
        compose.setContent { PromoCodeScreen(onBack = {}) }
        waitForText("Ошибка сервера. Попробуй позже.")
        compose.onNodeWithText("Повторить").performClick()
        waitForText("Есть промокод?")
    }

    // R3 (деньги): скидку на такси сразу после применения показываем с верной суммой в рублях.
    @Test
    fun successScreenShowsDiscountNoteWithCorrectRubAmount() {
        applyDiscountKop = 15050
        applyKind = "taxi_ride"
        compose.setContent { PromoCodeScreen(onBack = {}) }
        waitForText("Есть промокод?")
        codeField().performTextInput("YULDASH100")
        compose.onNodeWithText("Применить").performClick()
        waitForText("Промокод применён!")
        waitForText("Вводить больше ничего не нужно")
        assertHasText("150,50 ₽")
    }

    // R3 (деньги): нет скидки → блок про такси не рисуем вовсе — ни «0 ₽», ни пустой карточки.
    @Test
    fun successScreenHidesDiscountNoteWhenThereIsNoDiscount() {
        applyDiscountKop = 0
        applyKind = "welcome"
        compose.setContent { PromoCodeScreen(onBack = {}) }
        waitForText("Есть промокод?")
        codeField().performTextInput("WELCOME1")
        compose.onNodeWithText("Применить").performClick()
        waitForText("Промокод применён!")
        settle()
        assertNoText("Вводить больше ничего не нужно")
        assertNoText("0 ₽")
    }

    // R3 (деньги): постоянный экран «промокод уже применён» — то же правило для старой скидки,
    // и верная сумма в состоянии «ждёт следующего заказа».
    @Test
    fun appliedPermanentCardShowsWaitingDiscountWithCorrectAmount() {
        minePromo = """{"promo":{"code":"YULDASH100","title":"","kind":"taxi_ride","perk_value":0},
                         "discount_kop":15050,"discount_available":true,"discount_used_order_id":null}"""
        compose.setContent { PromoCodeScreen(onBack = {}) }
        waitForText("Промокод активен")
        waitForText("150,50 ₽ ждут следующего заказа такси")
    }

    @Test
    fun appliedPermanentCardHidesDiscountStatusWhenThereIsNoDiscount() {
        minePromo = """{"promo":{"code":"WELCOME1","title":"","kind":"welcome","perk_value":1},
                         "discount_kop":0,"discount_available":false,"discount_used_order_id":null}"""
        compose.setContent { PromoCodeScreen(onBack = {}) }
        waitForText("Промокод активен")
        settle()
        assertNoText("такси")
        assertNoText("0 ₽")
    }

    // R (новое, из независимого ревью): таймаут на первом нажатии мог означать, что сервер код
    // УЖЕ принял, а ответ потерялся; повтор утыкается в 409 «уже активировал». Раньше человек
    // оставался на пустой форме ввода с текстом отказа, не видя, что код на самом деле есть.
    @Test
    fun applyTimeoutFollowedBy409RecoversToAlreadyAppliedState() {
        applyFailsWith409Once = true
        recoveredPromoJson = """{"promo":{"code":"YULDASH100","title":"","kind":"taxi_ride","perk_value":0},
                                  "discount_kop":15050,"discount_available":true,"discount_used_order_id":null}"""
        compose.setContent { PromoCodeScreen(onBack = {}) }
        waitForText("Есть промокод?")
        codeField().performTextInput("YULDASH100")
        compose.onNodeWithText("Применить").performClick()
        waitForText("Промокод активен")
        waitForText("150,50 ₽ ждут следующего заказа такси")
    }

    // R (новое, из независимого ревью, деньги): "kind=taxi_ride" с discountKop=0 (сервер разрешает
    // perk_value=0, либо общий потолок снижен до 0) не должен подставлять старое/нулевое число —
    // показываем только то, что сервер ПОДТВЕРДИЛ суммой в этом же ответе.
    @Test
    fun successScreenDoesNotClaimAnUnconfirmedTaxiDiscountAmount() {
        applyDiscountKop = 0
        applyKind = "taxi_ride"
        applyPerkValue = 300   // «устаревшее» число — не должно просочиться на экран как сумма скидки
        compose.setContent { PromoCodeScreen(onBack = {}) }
        waitForText("Есть промокод?")
        codeField().performTextInput("YULDASH100")
        compose.onNodeWithText("Применить").performClick()
        waitForText("Промокод применён!")
        settle()
        assertNoText("скидки на такси")
        assertNoText("₽")
    }

    // Двуязычие: серверное сообщение успеха приходит на языке интерфейса, а не всегда по-русски.
    @Test
    fun successMessageRespectsBashkirInterfaceLanguage() {
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                PromoCodeScreen(onBack = {})
            }
        }
        waitForText("Промокодың бармы?")
        codeField().performTextInput("YULDASH100")
        compose.onNodeWithText("Ҡулланыу").performClick()
        waitForText("Әҙер — ташлама көтә")
        settle()
        assertNoText("Готово — скидка уже ждёт")
    }

    /** Поле ввода кода: подпись совпадает с заголовком экрана («Промокод» дважды), поэтому ищем
     *  по способности принимать текст, а не по голому тексту. */
    private fun codeField() = compose.onNode(hasText("Промокод") and hasSetTextAction())

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

    private fun assertNoText(text: String) {
        assertEquals(
            "unexpected text present: $text",
            0,
            compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().size,
        )
    }

    private fun assertHasText(text: String) {
        assertTrue(
            "expected text not found: $text",
            compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty(),
        )
    }

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code)
        .setHeader("Content-Type", "application/json").setBody(body)
}
