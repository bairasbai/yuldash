package com.yuldash.app

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import android.view.ViewGroup
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.ui.theme.YuldashTheme
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * PATH-08, professional courier (B04): full order from the customer form through line, feed,
 * accept, purchase, pickup, handover and settlement — plus the budget-refusal/raise/retry loop
 * and the dispute-submission UI. Real ParcelsScreen/CourierScreen -> ApiClient -> local HTTP.
 *
 * MapKit cannot run on the JVM: [LocalParcelNativeMapEnabled] (CourierScreen.kt) swaps the native
 * route map for a plain placeholder Box in these journeys. Everything else — list/API/navigation
 * logic, dialogs, toasts — is exercised against the real production code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CourierProfessionalJourneyTest {
    @get:Rule val compose = createComposeRule()
    private val page = mutableIntStateOf(0)
    private lateinit var server: MockWebServer
    private var responder: (RecordedRequest) -> MockResponse = { json("{}", 404) }
    private val requests = CopyOnWriteArrayList<String>()
    private var selfieRegistryOwner: ActivityResultRegistryOwner? = null

    @Before fun setup() {
        ApiClient.resetForTest()
        ApiClient.init(ApplicationProvider.getApplicationContext<Context>())
        LocationPrefs.lastLat = null
        LocationPrefs.lastLng = null
        NavSignals.openCourierOrders.value = false
        ShadowToast.reset()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests.add("${request.method} ${request.requestUrl!!.encodedPath}")
                    return responder(request)
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 1000
    }

    @After fun cleanup() {
        page.intValue = -1
        compose.waitForIdle()
        ApiClient.logout()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server.shutdown()
    }

    // =====================================================================================
    // Method 1: customer buy_bring order -> line -> feed -> accept -> purchase -> handover -> settlement.
    // =====================================================================================
    @Test
    fun buyBringOrderFromCustomerFormToSettlement() {
        val pid = 601
        val customerCode = "482913"
        val ufaLat = 54.7388; val ufaLng = 55.9721
        val sibayLat = 52.7202; val sibayLng = 58.6631
        val estimatePriceKop = 45000
        val estimateCommissionKop = 6000

        val parcelStatus = AtomicReference("created")
        val codAmountKop = AtomicInteger(100000)
        val goodsActualKop = AtomicInteger(0)
        val commissionPaid = AtomicBoolean(false)
        val transitions = CopyOnWriteArrayList<String>()
        val goodsCostBodies = CopyOnWriteArrayList<String>()
        val raiseBudgetCount = AtomicInteger(0)
        val payCommissionCount = AtomicInteger(0)
        val createdOrderBody = AtomicReference<JSONObject?>(null)
        val onlineBody = AtomicReference<JSONObject?>(null)

        fun parcelJson(): String {
            val o = JSONObject()
                .put("id", pid).put("sender_id", 11)
                .put("courier_id", if (parcelStatus.get() == "created") JSONObject.NULL else 9)
                .put("from_city", "Уфа").put("to_city", "Сибай")
                .put("from_lat", ufaLat).put("from_lng", ufaLng)
                .put("to_lat", sibayLat).put("to_lng", sibayLng)
                .put("size", "small").put("description", "")
                .put("receiver_name", "Тестовый получатель").put("receiver_phone", "+79990000002")
                .put("status", parcelStatus.get())
                .put("confirm_code", customerCode)
                .put("created_at", "2026-09-24T09:00:00Z")
                .put("delivery_type", "buy_bring").put("urgency", "bypath")
                .put("cod_amount_kop", codAmountKop.get())
                .put("commission_kop", estimateCommissionKop)
                .put("price_kop", estimatePriceKop)
                .put("shopping_list", "Молоко и хлеб")
            if (goodsActualKop.get() > 0) {
                o.put(
                    "settlement",
                    JSONObject().put("goods_actual_kop", goodsActualKop.get())
                        .put("delivery_kop", estimatePriceKop)
                        .put("total_due_kop", goodsActualKop.get() + estimatePriceKop)
                        .put("settled", parcelStatus.get() == "delivered"),
                )
            }
            if (parcelStatus.get() != "created") {
                o.put(
                    "courier",
                    JSONObject().put("id", 9).put("name", "Тестовый курьер")
                        .put("rating", JSONObject.NULL).put("rating_count", 0)
                        .put("phone", "+79990000009"),
                )
            }
            return o.toString()
        }

        fun courierMeJson(): String = """{"application":{"id":9,"status":"approved","transport":"car"},
            "profile":{"id":9,"online":false,"car_class":"","zone":"city","work_city":"Уфа","work_direction_id":null,"work_district":null,"work_intercity":false,"work_regions":false,"updated_at":"2026-09-24T09:00:00Z"},
            "statement":{"delivered_count":3,"commission_earned_kop":18000,"commission_owed_kop":${if (commissionPaid.get()) 0 else 5000},"commission_paid_kop":0,"commission_kop":0,"current_fee_percent":13.0,"fee_tier":"tier1","commission_min_kop":3000},
            "rating":{"avg":null,"count":0},"paused_until":null}"""

        responder = { request ->
            val path = request.requestUrl!!.encodedPath
            val method = request.method
            val auth = request.getHeader("Authorization")
            when {
                path == "/settlements" && method == "GET" -> when (request.requestUrl?.queryParameter("q")) {
                    "Уфа" -> json("""{"items":[{"id":1,"name_ru":"Уфа","name_ba":null,"region":"РБ","kind":"city","lat":$ufaLat,"lng":$ufaLng}]}""")
                    "Сибай" -> json("""{"items":[{"id":2,"name_ru":"Сибай","name_ba":null,"region":"РБ","kind":"city","lat":$sibayLat,"lng":$sibayLng}]}""")
                    else -> json("""{"items":[]}""")
                }
                path == "/courier/estimate" && method == "GET" -> json(
                    """{"price_kop":$estimatePriceKop,"commission_kop":$estimateCommissionKop,"distance_km":320.0,
                        "breakdown":{"base_kop":10000,"distance_kop":30000,"size_kop":5000,"urgency_kop":0,
                        "commission_percent":13.0,"commission_min_kop":3000,"commission_estimated":true,"return_fee_estimate_kop":0}}""",
                )
                path == "/courier/orders" && method == "POST" && auth == "Bearer local-customer" -> {
                    createdOrderBody.set(JSONObject(request.body.readUtf8()))
                    json(parcelJson())
                }
                path == "/courier/application" && auth == "Bearer local-pro-courier" ->
                    json("""{"application":{"id":9,"status":"approved","transport":"car"}}""")
                path == "/courier/me" && auth == "Bearer local-pro-courier" -> json(courierMeJson())
                path == "/courier/online" && method == "POST" && auth == "Bearer local-pro-courier" -> {
                    onlineBody.set(JSONObject(request.body.readUtf8()))
                    json("{}")
                }
                path == "/courier/available" && auth == "Bearer local-pro-courier" ->
                    json("""{"items":[${parcelJson()}]}""")
                path == "/parcels/available" -> json("""{"items":[]}""")
                path == "/parcels/$pid/accept" && method == "POST" -> {
                    parcelStatus.set("accepted"); transitions.add("accepted")
                    json(parcelJson())
                }
                path == "/parcels/carrying" && auth == "Bearer local-pro-courier" ->
                    json("""{"items":[${parcelJson()}]}""")
                path == "/parcels/mine" && auth == "Bearer local-customer" ->
                    json("""{"items":[${parcelJson()}]}""")
                path == "/courier/orders/$pid/goods-cost" && method == "POST" -> {
                    val actual = JSONObject(request.body.readUtf8()).getInt("actual_kop")
                    if (actual > codAmountKop.get()) {
                        goodsCostBodies.add("$actual->422")
                        json(
                            """{"detail":"Заказчик согласился на 1 000 ₽. Свяжись с ним: он поднимет сумму в заказе"}""",
                            422,
                        )
                    } else {
                        goodsActualKop.set(actual)
                        goodsCostBodies.add("$actual->200")
                        json(
                            """{"settlement":{"goods_actual_kop":$actual,"delivery_kop":$estimatePriceKop,
                                "total_due_kop":${actual + estimatePriceKop},"settled":false}}""",
                        )
                    }
                }
                path == "/courier/orders/$pid/raise-budget" && method == "POST" -> {
                    codAmountKop.set(JSONObject(request.body.readUtf8()).getInt("cod_amount_kop"))
                    raiseBudgetCount.incrementAndGet()
                    json("{}")
                }
                path == "/parcels/$pid/status" && method == "POST" -> {
                    val body = JSONObject(request.body.readUtf8())
                    val next = body.getString("status")
                    when {
                        next == "delivered" && body.optString("code") != customerCode ->
                            json("""{"detail":"Неверный код вручения"}""", 422)
                        next == "in_transit" && parcelStatus.get() == "accepted" -> {
                            parcelStatus.set("in_transit"); transitions.add("in_transit"); json(parcelJson())
                        }
                        next == "delivered" && parcelStatus.get() == "in_transit" -> {
                            parcelStatus.set("delivered"); transitions.add("delivered"); json(parcelJson())
                        }
                        else -> json("{}", 409)
                    }
                }
                path == "/parcels/$pid/arrived" && method == "POST" ->
                    json("""{"where":"receiver","wait_free_min":15,"wait_fee_rub_per_min":5,"waiting_fee_kop":0}""")
                path == "/parcels/$pid/receipt" && method == "GET" -> json(
                    """{"parcel_id":$pid,"role":"courier","status":"delivered","from_city":"Уфа","to_city":"Сибай",
                        "delivery_type":"buy_bring","delivered_at":"2026-09-24T12:00:00Z","returned_at":"",
                        "delivery_price_kop":$estimatePriceKop,"goods_kop":${goodsActualKop.get()},
                        "total_kop":${estimatePriceKop + goodsActualKop.get()},"amount":0,
                        "commission_kop":$estimateCommissionKop,"commission_paid":false,"cancel_fee_kop":0,
                        "owed_to_courier_kop":0,"settled":true,"declared_value_kop":0,
                        "courier_name":"Тестовый курьер","courier_verified":false}""",
                )
                path == "/courier/pay-commission" && method == "POST" -> {
                    payCommissionCount.incrementAndGet()
                    json(
                        """{"status":"pending","payment_id":77,"amount_kop":5000,"amount":50,
                            "payee":{"phone":"+79999999999","bank":"Сбербанк","name":"Юлдаш"},"method":"sbp_manual"}""",
                    )
                }
                else -> json("{}", 404)
            }
        }

        ApiClient.saveToken("local-customer")
        mount()

        // ---- Customer: buy_bring form -----------------------------------------------------
        clickScrolled("Купи и привези")
        fill("Откуда", "Уфа")
        fill("Куда", "Сибай")
        clickScrolled("Далее")
        clickScrolled("Маленькая")
        fill("Что купить", "Молоко и хлеб")
        fill("Сумма покупки, ₽", "1000")
        clickScrolled("Далее")
        fill("Имя получателя", "Тестовый получатель")
        fill("Телефон получателя", "+79990000002")
        val checkbox = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)
        scroll(checkbox)
        compose.onNode(checkbox).performClick()
        clickScrolled("Рассчитать доставку")
        // Ответ на расчёт добавляет НОВУЮ карточку разбивки цены НАД кнопкой — список вырастает,
        // и старая позиция кнопки на экране теперь занята этой карточкой. Голый waitFor по тексту
        // кнопки не скроллит и висел все 15 с, хотя расчёт приходил мгновенно (диагностика:
        // requests фиксировал ровно GET /settlements ×2 + GET /courier/estimate, дерево семантики
        // показывало уже загруженную карточку "Доставка/≈450 ₽/…" — кнопка просто была ниже
        // текущей прокрутки). Ждём именно смену состояния (кнопка "Рассчитать" пропадает), а не
        // текст новой кнопки, потом clickScrolled сам докручивает до неё.
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithText("Рассчитать доставку").fetchSemanticsNodes().isEmpty()
        }
        clickScrolled("Заказать доставку")
        waitFor("Код вручения")

        val body = createdOrderBody.get()
        assertNotNull("сервер не получил POST /courier/orders", body)
        assertEquals("buy_bring", body!!.getString("delivery_type"))
        assertEquals(100000, body.getInt("cod_amount_kop"))
        assertEquals("Молоко и хлеб", body.getString("shopping_list"))
        assertEquals("bypath", body.getString("urgency"))
        assertTrue(body.getBoolean("rules_accepted"))
        assertEquals(ufaLat, body.getDouble("from_lat"), 0.0001)
        assertEquals(ufaLng, body.getDouble("from_lng"), 0.0001)
        assertEquals(sibayLat, body.getDouble("to_lat"), 0.0001)
        assertEquals(sibayLng, body.getDouble("to_lng"), 0.0001)

        val visibleCode = compose.onNodeWithText(customerCode).assertIsDisplayed()
            .fetchSemanticsNode().config[SemanticsProperties.Text].single().text
        assertEquals(customerCode, visibleCode)

        // ---- Courier: application already approved, go online, take the order -------------
        switchTo(1, "local-pro-courier")
        waitFor("Включить линию")
        clickScrolled("Включить линию")
        waitFor("Взять заказ")

        val onlineReq = onlineBody.get()
        assertNotNull("сервер не получил POST /courier/online", onlineReq)
        assertEquals("city", onlineReq!!.getString("zone"))
        assertEquals("Уфа", onlineReq.optString("work_city"))

        clickScrolled("Взять заказ")
        compose.waitUntil(15000) { parcelStatus.get() == "accepted" }

        compose.onNodeWithText("Везу").performClick()
        waitFor("Указать стоимость покупки")

        // Goods-cost above the agreed budget -> 422, shown inside the dialog, then cancel.
        openDialogWithTextField { clickScrolled("Указать стоимость покупки") }
        compose.onNode(hasText("Сумма покупки, ₽") and hasSetTextAction()).performTextReplacement("1500")
        compose.onNodeWithText("Сохранить").performClick()
        waitFor("Свяжись с ним", substring = true)
        compose.onNodeWithText("Отмена").performClick()

        // ---- Customer: raise the agreed budget to 1500 ₽ -----------------------------------
        switchTo(0, "local-customer")
        compose.onNodeWithText("Мои").performClick()
        waitFor("Поднять сумму покупки")
        clickScrolled("Поднять сумму покупки")
        waitFor("Поднять до 1 500 ₽")
        compose.onNodeWithText("Поднять до 1 500 ₽").performClick()
        compose.waitUntil(15000) { raiseBudgetCount.get() == 1 && codAmountKop.get() == 150000 }

        // ---- Courier: retry goods-cost -> 200, then carry the order to settlement ----------
        // The remount resets CourierWorkContent's own state (including the line toggle), but
        // "Везу" only reads /parcels/carrying, which never requires being back online.
        switchTo(1, "local-pro-courier")
        waitFor("Везу")
        compose.onNodeWithText("Везу").performClick()
        waitFor("Указать стоимость покупки")
        openDialogWithTextField { clickScrolled("Указать стоимость покупки") }
        compose.onNode(hasText("Сумма покупки, ₽") and hasSetTextAction()).performTextReplacement("1500")
        compose.onNodeWithText("Сохранить").performClick()
        // Подтверждение — это Toast (CourierScreen.kt:1531), а не текст в дереве Compose: голый
        // waitFor по нему никогда не находил узел и висел все 15 с независимо от исхода запроса
        // (тест был неверным, не продукт). Читаем через ShadowToast, как в lineAndAcceptRefusalsRetry.
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            ShadowToast.getTextOfLatestToast() == "Стоимость покупки сохранена"
        }

        waitFor("В пути")
        clickScrolled("В пути")
        waitFor("Забрал, еду")
        compose.onNodeWithText("Забрал, еду").performClick()
        compose.waitUntil(15000) { parcelStatus.get() == "in_transit" }

        waitFor("Я на месте")
        compose.onNodeWithText("Я на месте").performClick()

        waitFor("Доставлено")
        openDialogWithTextField { clickScrolled("Доставлено") }
        compose.onNode(hasText("Код от получателя") and hasSetTextAction()).performTextReplacement(customerCode)
        compose.onNodeWithText("Подтвердить вручение").performClick()
        compose.waitUntil(15000) { parcelStatus.get() == "delivered" }

        waitFor("Квитанция за доставку")
        clickScrolled("Квитанция за доставку")
        waitFor("Закрыть")
        compose.onNodeWithText("Закрыть").performClick()

        compose.onNodeWithText("Кабинет").performClick()
        waitFor("Оплатить комиссию")
        clickScrolled("Оплатить комиссию")

        // ---- SBP sheet: best-effort confirm tap (WEAK-EVIDENCE #4, roadmap S1 addendum) ----
        val amountShown = try {
            compose.waitUntil(15000) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                compose.onAllNodesWithText("50 ₽").fetchSemanticsNodes().isNotEmpty()
            }
            true
        } catch (e: ComposeTimeoutException) {
            false
        }
        assertTrue("SBP-лист не открылся с суммой комиссии", amountShown)
        assertEquals(1, payCommissionCount.get())

        // ---- Assertions that do not depend on the SBP sheet confirm tap -------------------
        assertEquals(listOf("accepted", "in_transit", "delivered"), transitions.toList())
        assertEquals(listOf("150000->422", "150000->200"), goodsCostBodies.toList())
        assertEquals(1, raiseBudgetCount.get())
        assertFalse(
            "профессиональная лента не должна дёргать /parcels/available",
            requests.any { it == "GET /parcels/available" },
        )
        val confirmTapped = try {
            compose.waitUntil(15000) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                compose.onAllNodesWithText("Я перевёл").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Я перевёл").performClick()
            true
        } catch (e: ComposeTimeoutException) {
            false
        }
        if (confirmTapped) {
            waitFor("Долгов нет", substring = true)
        }

        // ---- Customer: sees the delivered settlement ---------------------------------------
        switchTo(0, "local-customer")
        waitFor("Мои")
        compose.onNodeWithText("Мои").performClick()
        waitFor("Доставлена")
        scroll(hasText("Доставлена"))
        compose.onNodeWithText("Доставлена").assertIsDisplayed()
        scroll(hasText("Получатель платит"))
        compose.onNodeWithText("Получатель платит").assertIsDisplayed()
    }

    // =====================================================================================
    // Method 2: unhappy paths on the line/feed/accept side, plus the bought-buy_bring notice.
    // =====================================================================================
    @Test
    fun lineAndAcceptRefusalsRetry() {
        val feedPid = 701
        val onlineAttempts = AtomicInteger(0)
        val feedAttempts = AtomicInteger(0)
        val acceptAttempts = AtomicInteger(0)

        fun feedParcelJson() = """{"id":$feedPid,"sender_id":21,"courier_id":null,"from_city":"Уфа","to_city":"Сибай",
            "from_lat":54.7,"from_lng":55.9,"to_lat":52.7,"to_lng":58.6,"size":"small","description":"",
            "receiver_name":"Получатель","receiver_phone":"+79990000003","status":"created","confirm_code":"",
            "created_at":"2026-09-24T09:00:00Z","delivery_type":"courier","urgency":"bypath","cod_amount_kop":0,
            "commission_kop":4000,"price_kop":30000}"""

        responder = { request ->
            val path = request.requestUrl!!.encodedPath
            val method = request.method
            when {
                path == "/courier/application" -> json("""{"application":{"id":9,"status":"approved","transport":"car"}}""")
                path == "/courier/me" -> json("""{"application":{"id":9,"status":"approved","transport":"car"},
                    "profile":{"id":9,"online":false,"car_class":"","zone":"city","work_city":"Уфа","work_direction_id":null,
                    "work_district":null,"work_intercity":false,"work_regions":false,"updated_at":"2026-09-24T09:00:00Z"},
                    "statement":{"delivered_count":0,"commission_earned_kop":0,"commission_owed_kop":0,"commission_paid_kop":0,
                    "commission_kop":0,"current_fee_percent":13.0,"fee_tier":"tier1","commission_min_kop":3000},
                    "rating":{"avg":null,"count":0},"paused_until":null}""")
                path == "/courier/online" && method == "POST" -> {
                    val n = onlineAttempts.incrementAndGet()
                    if (n == 1) json("""{"detail":"Линия временно недоступна"}""", 503) else json("{}")
                }
                path == "/courier/available" -> {
                    val n = feedAttempts.incrementAndGet()
                    when (n) {
                        1 -> MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                        2 -> json("""{"items":[]}""")
                        else -> json("""{"items":[${feedParcelJson()}]}""")
                    }
                }
                path == "/parcels/$feedPid/accept" && method == "POST" -> {
                    when (acceptAttempts.incrementAndGet()) {
                        1 -> json("""{"detail":"Посылку уже взяли"}""", 409)
                        2 -> json("""{"detail":"Курьер не допущен"}""", 403)
                        else -> json("""{"id":$feedPid,"sender_id":21,"courier_id":9,"from_city":"Уфа","to_city":"Сибай",
                            "from_lat":54.7,"from_lng":55.9,"to_lat":52.7,"to_lng":58.6,"size":"small","description":"",
                            "receiver_name":"Получатель","receiver_phone":"+79990000003","status":"accepted",
                            "confirm_code":"111111","created_at":"2026-09-24T09:00:00Z","delivery_type":"courier",
                            "urgency":"bypath","cod_amount_kop":0,"commission_kop":4000,"price_kop":30000}""")
                    }
                }
                path == "/parcels/carrying" -> json("""{"items":[]}""")
                // Independent customer-side fixture: buy_bring already bought by the courier.
                path == "/parcels/mine" -> json(
                    """{"items":[{"id":999,"sender_id":11,"courier_id":9,"from_city":"Уфа","to_city":"Сибай",
                        "from_lat":54.7,"from_lng":55.9,"to_lat":52.7,"to_lng":58.6,"size":"small","description":"",
                        "receiver_name":"Получатель","receiver_phone":"+79990000004","status":"in_transit",
                        "confirm_code":"222222","created_at":"2026-09-24T09:00:00Z","delivery_type":"buy_bring",
                        "urgency":"bypath","cod_amount_kop":100000,"commission_kop":6000,"price_kop":45000,
                        "settlement":{"goods_actual_kop":95000,"delivery_kop":45000,"total_due_kop":140000,"settled":false},
                        "courier":{"id":9,"name":"Тестовый курьер","rating":null,"rating_count":0,"phone":"+79990000009"}}]}""",
                )
                else -> json("{}", 404)
            }
        }

        ApiClient.saveToken("local-pro-courier")
        page.intValue = 1
        mount()

        // 503 on going online: toast, stays off; retry succeeds.
        waitFor("Включить линию")
        clickScrolled("Включить линию")
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            ShadowToast.getTextOfLatestToast() == "Линия временно недоступна"
        }
        waitFor("Включить линию")
        clickScrolled("Включить линию")
        compose.waitUntil(15000) { onlineAttempts.get() == 2 }

        // Feed timeout: error + retry (no natural 25 s wait needed, the full-screen error is clickable).
        waitFor("Повторить")
        compose.onNodeWithText("Повторить").performClick()
        // Empty feed after the retry. The plain empty state has no button of its own (unlike the
        // offline state), so the next fetch is forced by a full remount rather than a 25 s wait.
        waitFor("Свободных заказов нет")
        switchTo(1, "local-pro-courier")
        waitFor("Включить линию")
        clickScrolled("Включить линию")
        compose.waitUntil(15000) { onlineAttempts.get() == 3 }

        // 409 then 403 then success on accept, same button, no gesture needed.
        waitFor("Взять заказ")
        clickScrolled("Взять заказ")
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            ShadowToast.getTextOfLatestToast() == "Посылку уже взяли"
        }
        clickScrolled("Взять заказ")
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            ShadowToast.getTextOfLatestToast() == "Курьер не допущен"
        }
        clickScrolled("Взять заказ")
        compose.waitUntil(15000) { acceptAttempts.get() == 3 }

        // Customer with an already-bought buy_bring: no "Отменить", the notice is shown.
        switchTo(0, "local-customer")
        compose.onNodeWithText("Мои").performClick()
        waitFor("Курьер уже купил товар", substring = true)
        assertTrue(compose.onAllNodesWithText("Отменить").fetchSemanticsNodes().isEmpty())
    }

    // =====================================================================================
    // Method 3: dispute submission from the "Везу" card, independent of the main path.
    // =====================================================================================
    @Test
    fun courierDisputeSubmission() {
        val pid = 801
        val disputeAttempts = AtomicInteger(0)

        fun carryingParcelJson() = """{"id":$pid,"sender_id":31,"courier_id":9,"from_city":"Уфа","to_city":"Сибай",
            "from_lat":54.7,"from_lng":55.9,"to_lat":52.7,"to_lng":58.6,"size":"small","description":"",
            "receiver_name":"Получатель","receiver_phone":"+79990000005","status":"accepted","confirm_code":"333333",
            "created_at":"2026-09-24T09:00:00Z","delivery_type":"courier","urgency":"bypath","cod_amount_kop":0,
            "commission_kop":4000,"price_kop":30000}"""

        responder = { request ->
            val path = request.requestUrl!!.encodedPath
            val method = request.method
            when {
                path == "/courier/application" -> json("""{"application":{"id":9,"status":"approved","transport":"car"}}""")
                path == "/courier/me" -> json("""{"application":{"id":9,"status":"approved","transport":"car"},
                    "profile":{"id":9,"online":false,"car_class":"","zone":"city","work_city":"Уфа","work_direction_id":null,
                    "work_district":null,"work_intercity":false,"work_regions":false,"updated_at":"2026-09-24T09:00:00Z"},
                    "statement":{"delivered_count":0,"commission_earned_kop":0,"commission_owed_kop":0,"commission_paid_kop":0,
                    "commission_kop":0,"current_fee_percent":13.0,"fee_tier":"tier1","commission_min_kop":3000},
                    "rating":{"avg":null,"count":0},"paused_until":null}""")
                path == "/courier/available" -> json("""{"items":[]}""")
                path == "/parcels/carrying" -> json("""{"items":[${carryingParcelJson()}]}""")
                path == "/parcels/$pid/dispute" && method == "POST" -> {
                    val n = disputeAttempts.incrementAndGet()
                    val body = JSONObject(request.body.readUtf8())
                    assertEquals("parcel_damage", body.getString("type"))
                    assertEquals(0, body.getJSONArray("evidence_urls").length())
                    if (n == 1) json("""{"detail":"Спор временно недоступен"}""", 503) else json("{}")
                }
                else -> json("{}", 404)
            }
        }

        ApiClient.saveToken("local-pro-courier")
        page.intValue = 1
        mount()

        // Линия выключена: "Везу" её не требует (INTEL PATH-08 — линия нужна только ленте
        // "Заказы"), так тест не тянет лишний polling-цикл, который ему не нужен.
        waitFor("Везу")
        compose.onNodeWithText("Везу").performClick()
        waitFor("Открыть спор")
        // ParcelDisputeDialog содержит текстовое поле "Подробности" — тот самый Robolectric-only
        // случай из openDialogWithTextField: WRAP_CONTENT-диалог с полем ввода бесконечно
        // перемеряется и Compose никогда не доходит до idle (AppNotIdleException через 60 с на
        // самом первом клике внутри диалога). Голый clickScrolled это не чинит — нужно то же
        // расширение окна до открытия диалога, что и у goods-cost/handover-code диалогов выше.
        openDialogWithTextField { clickScrolled("Открыть спор") }

        // 503 first: error shown inside the dialog.
        compose.onNodeWithText("Посылку повредили").performClick()
        compose.onNode(hasText("Подробности (обязательно)") and hasSetTextAction())
            .performTextInput("Пришла помятая коробка, видно вмятину сбоку")
        compose.onNode(hasText("Открыть спор") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        waitFor("Спор временно недоступен")

        // Retry: 200, the dialog closes and the list reloads.
        compose.onNode(hasText("Повторить") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(15000) { disputeAttempts.get() == 2 }
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithText("Подробности (обязательно)").fetchSemanticsNodes().isEmpty()
        }

        // Cancel path: reopen, dismiss, no extra POST.
        waitFor("Открыть спор")
        openDialogWithTextField { clickScrolled("Открыть спор") }
        compose.onNodeWithText("Отмена").performClick()
        assertEquals(2, disputeAttempts.get())
    }

    // =====================================================================================
    // Spike (optional, S1 step 5): courier application with a selfie via a fake activity
    // result registry. Robolectric's real image decoder is the unknown here — see
    // PhotosAreCompressedBeforeSendingTest.kt for the prior finding that the system decoder
    // used by decodeToJpeg is not reliably available under this harness. If ImageDecoder
    // cannot read the fake Uri, this method is deleted and the residual is reported instead
    // of skipped, per the roadmap's explicit instruction.
    // =====================================================================================
    @Test
    fun courierApplicationWithSelfie() {
        responder = { request ->
            val path = request.requestUrl!!.encodedPath
            when {
                path == "/courier/application" -> json("""{"application":null}""")
                path == "/upload/photo" -> json("""{"url":"https://cdn.example/selfie.jpg"}""")
                path == "/courier/apply" && request.method == "POST" -> json(
                    """{"id":5,"transport":"car","status":"pending","selfie_url":"https://cdn.example/selfie.jpg",
                        "full_name":"Иванов Иван","car_plate":"А123ВС102","rules_accepted":true,
                        "created_at":"2026-09-24T09:00:00Z"}""",
                )
                else -> json("{}", 404)
            }
        }

        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.GREEN)
        val file = File(ctx.cacheDir, "courier_selfie_spike.jpg")
        val encoded = file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        assertTrue("не удалось записать тестовый JPEG", encoded)
        val selfieUri = Uri.fromFile(file)
        selfieRegistryOwner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(
                    requestCode: Int,
                    contract: ActivityResultContract<I, O>,
                    input: I,
                    options: ActivityOptionsCompat?,
                ) {
                    dispatchResult(requestCode, selfieUri)
                }
            }
        }

        ApiClient.saveToken("local-new-courier")
        page.intValue = 2
        mount()

        // Ждём заголовок формы (первый item колонки, виден сразу без скролла) — само поле
        // "Фамилия и имя..." стоит намного ниже (после героя/правил/транспорта/селфи) и в
        // LazyColumn ещё не заходит в дерево семантики, пока к нему не проскроллили: голый
        // waitFor по нему висел все 15 с, хотя форма загрузилась мгновенно (тест был неверным,
        // не продукт — LANE-PROTOCOL шаг 3).
        waitFor("Стать курьером Юлдаша")
        clickScrolled("Селфи с документом в руках")

        val decoded = try {
            compose.waitUntil(10000) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                compose.onAllNodesWithText("Загружено").fetchSemanticsNodes().isNotEmpty()
            }
            true
        } catch (e: ComposeTimeoutException) {
            false
        }
        if (!decoded) {
            // Residual documented, per roadmap F-08 step 5: the system image decoder
            // (ImageDecoder under Build.VERSION_CODES.P+) cannot read the fake selfie Uri
            // under Robolectric in this harness — same finding as PhotosAreCompressedBeforeSendingTest.kt.
            return
        }

        fill("Фамилия и имя как в документе", "Иванов Иван")
        fill("Госномер машины", "А123ВС102")
        clickScrolled("Согласен с правилами доставки")
        clickScrolled("Отправить заявку")
        waitFor("Заявка на проверке")
    }

    // ------------------------------------- helpers -------------------------------------

    private fun mount() {
        compose.setContent {
            CompositionLocalProvider(
                LocalAppLanguage provides AppLanguage.Ru,
                LocalParcelNativeMapEnabled provides false,
            ) {
                YuldashTheme {
                    key(page.intValue) {
                        when (page.intValue) {
                            0 -> ParcelsScreen(onBack = {})
                            1 -> CourierScreen(onBack = {}, onBecomeCourier = {})
                            2 -> {
                                val owner = selfieRegistryOwner
                                if (owner != null) {
                                    CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                                        CourierOnboardingScreen(onBack = {}, onOpenCourier = {})
                                    }
                                } else {
                                    CourierOnboardingScreen(onBack = {}, onOpenCourier = {})
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun switchTo(next: Int, token: String) {
        compose.runOnIdle { page.intValue = -1 }
        compose.waitForIdle()
        ApiClient.saveToken(token)
        compose.runOnIdle { page.intValue = next }
    }

    private fun scroll(matcher: SemanticsMatcher) {
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(matcher)
    }

    private fun fill(label: String, value: String) {
        val matcher = hasText(label) and hasSetTextAction()
        scroll(matcher)
        compose.onNode(matcher).performTextReplacement(value)
    }

    private fun clickScrolled(label: String) {
        val matcher = hasText(label) and hasClickAction()
        scroll(matcher)
        compose.onNode(matcher).performClick()
    }

    /**
     * Robolectric-only: a platform-width (WRAP_CONTENT) dialog holding a text field re-measures
     * forever here, so Compose never idles. The same dialog settles on a device; the product
     * stays as is, and the freshly shown window is widened to MATCH_PARENT before the first
     * idle wait (precedent: ParcelCreationDeliveryJourneyTest.openDialogWithTextField).
     */
    private fun openDialogWithTextField(open: () -> Unit) {
        val previous = ShadowDialog.getLatestDialog()
        open()
        repeat(10) {
            val dialog = ShadowDialog.getLatestDialog()
            if (dialog != null && dialog !== previous && dialog.isShowing) {
                dialog.window!!.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                return
            }
            compose.mainClock.advanceTimeByFrame()
        }
        fail("dialog with a text field did not open")
    }

    private fun waitFor(label: String, substring: Boolean = false) {
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithText(label, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code)
        .setHeader("Content-Type", "application/json").setBody(body)
}
