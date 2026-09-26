package com.yuldash.app

// ═══════════════════ B07/B08: бизнес-партнёр — регистрация → модерация → купон → погашение ═══════════════════
// Реальные экраны -> ApiClient -> локальный HTTP. RoleCabinetIntegrationTest уже покрывает
// «pending без загрузки купонов» и «rejected -> правка -> pending»: здесь это не повторяется.
// Четыре участника одной цепочки: владелец бизнеса, админ, клиент — переключаемся токеном,
// как в ParcelCreationDeliveryJourneyTest (INTEL-COMMON, проверенный паттерн).

import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.ViewGroup
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.ui.theme.YuldashTheme
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BusinessCouponJourneyTest {
    @get:Rule val compose = createComposeRule()
    private val page = mutableIntStateOf(0)
    private lateinit var server: MockWebServer
    private val requests = CopyOnWriteArrayList<String>()

    private val ownerToken = "local-business"
    private val adminToken = "local-admin"
    private val clientToken = "local-client"
    private val couponTitle = "Скидка 20% на завтрак"
    private val couponDiscount = "-20%"

    @Before fun setup() {
        ApiClient.resetForTest()
        ApiClient.init(ApplicationProvider.getApplicationContext<Context>())
        ApiClient.saveToken(ownerToken)
    }

    @After fun cleanup() {
        page.intValue = -1
        compose.waitForIdle()
        ApiClient.logout()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server.shutdown()
    }

    private fun startServer(dispatch: (RecordedRequest) -> MockResponse) {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests.add("${request.method} ${request.requestUrl!!.encodedPath}")
                    return dispatch(request)
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 1000
    }

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code)
        .setHeader("Content-Type", "application/json").setBody(body)
    private fun err(code: Int, ru: String) =
        json("""{"detail":{"ru":"$ru","ba":"$ru"}}""", code)

    // ─────────────────────────── Конструкторы тел ответа ───────────────────────────

    private fun partnerJson(
        id: Int, status: String, category: String, rejectReason: String = "",
        subActive: Boolean = false, ownerId: Int = 9001,
    ): String = """
        {"id":$id,"owner_id":$ownerId,"name":"Кафе Юлдаш","category":"$category","city":"Уфа",
         "address":"ул. Ленина, 1","phone":"+79991234567","description":"Уютное кафе для попутчиков",
         "status":"$status","reject_reason":"$rejectReason",
         "subscription_plan":"${if (subActive) "start" else ""}","subscription_active":$subActive,
         "has_premium":false,"created_at":"2026-09-20T09:00:00Z"}
    """.trimIndent()

    private fun ownerCouponJson(id: Int, partnerId: Int, status: String, review: String): String = """
        {"id":$id,"partner_id":$partnerId,"title":"$couponTitle","description":"","discount_text":"$couponDiscount",
         "city":"Уфа","route_hint":[],"limit_total":100,"limit_per_user":1,"redeemed_count":0,"activations":0,
         "premium":false,"status":"$status","created_at":"2026-09-24T09:00:00Z",
         "review":"$review","review_note":"","reports_count":0}
    """.trimIndent()

    private fun adminCouponJson(id: Int, partnerId: Int, status: String, review: String): String = """
        {"id":$id,"partner_id":$partnerId,"partner_name":"Кафе Юлдаш","city":"Уфа","title":"$couponTitle",
         "description":"","discount_text":"$couponDiscount","status":"$status","review":"$review",
         "review_flag":"","review_note":"","reports_count":0,"visible":true,"created_at":"2026-09-24T09:00:00Z"}
    """.trimIndent()

    private fun publicCouponJson(id: Int, partnerId: Int, category: String, status: String): String = """
        {"id":$id,"partner":{"id":$partnerId,"name":"Кафе Юлдаш","category":"$category","city":"Уфа",
         "address":"ул. Ленина, 1","phone":"+79991234567"},"title":"$couponTitle","description":"",
         "discount_text":"$couponDiscount","city":"Уфа","route_hint":[],"limit_total":100,"limit_per_user":1,
         "redeemed_count":0,"remaining":99,"premium":false,"status":"$status"}
    """.trimIndent()

    private fun myCouponJson(code: String, status: String, id: Int, partnerId: Int, category: String): String = """
        {"code":"$code","status":"$status","reserved_at":"2026-09-25T10:00:00Z",
         "redeemed_at":${if (status == "redeemed") "\"2026-09-25T10:05:00Z\"" else "null"},
         "coupon":${publicCouponJson(id, partnerId, category, "active")}}
    """.trimIndent()

    // ─────────────────────────── Монтирование и переключение участника ───────────────────────────

    private fun mount() {
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                YuldashTheme {
                    key(page.intValue) {
                        when (page.intValue) {
                            0 -> PartnerCabinetScreen(onBack = {})
                            1 -> AdminPartnersScreen(onBack = {})
                            2 -> AdminModerationScreen(onBack = {}, onOpenPartners = {})
                            3 -> CouponsScreen(onBack = {})
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

    // ─────────────────────────── Общие помощники (частные копии проверенного паттерна) ───────────────────────────

    /** Best-effort: some states have no scrollable at all (a short error Column, a dialog window,
     *  the tab row that sits beside — not inside — the LazyColumn), and their target is already
     *  on screen. Scrolling there would throw even though the later direct click/type still works,
     *  so a miss here is swallowed and left to that direct action to report clearly if it fails. */
    private fun scroll(matcher: SemanticsMatcher) {
        val scrollable = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
        if (scrollable.fetchSemanticsNodes().isEmpty()) return
        runCatching { scrollable.onFirst().performScrollToNode(matcher) }
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
    private fun assertVisible(label: String) {
        scroll(hasText(label))
        compose.onNodeWithText(label).assertIsDisplayed()
    }
    private fun waitFor(label: String) {
        compose.waitUntil(15_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()
        }
    }
    /** Диалог с текстовым полем никогда не идёт (Robolectric-only); устройство — доказательство
     *  в ParcelHandoverDialogInstrumentedTest, 2/2 на API35 (INTEL-COMMON). Частная копия на файл. */
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
    /** Единственное текстовое поле диалога (фон под ним полей не держит — без неоднозначности). */
    private fun fillDialog(value: String) {
        compose.onNode(hasSetTextAction()).performTextReplacement(value)
    }

    // ═══════════════════ Метод 1: регистрация → модерация → купон → активация → погашение ═══════════════════

    // Состояние стенда метода 1 — читается из потока теста, пишется из потока MockWebServer
    // (dispatch() выполняется на собственном потоке сервера, а не на Looper-потоке композиции),
    // поэтому обычный var здесь был бы гонкой данных: держим @Volatile, как в проверенном
    // паттерне (ParcelCreationDeliveryJourneyTest, BookingConfirmationJourneyTest).
    @Volatile private var m1PartnerCategory = "cafe"
    @Volatile private var m1PartnerStatus = "none"      // none|pending|active
    @Volatile private var m1CouponExists = false
    @Volatile private var m1CouponStatus = "draft"      // draft|active
    @Volatile private var m1CouponReview = "pending"    // pending|approved
    // Captured request bodies (G5 P3): the stand must not ignore what the app actually sends.
    @Volatile private var m1CouponCreateBody: JSONObject? = null
    @Volatile private var m1CouponStatusRequest: String? = null
    @Volatile private var m1RedeemStatus = "none"       // none|reserved|redeemed
    private val m1RedeemCode = "REDEEM77"
    private val m1RedeemResponseCodes = CopyOnWriteArrayList<Int>()

    @Test fun registrationModerationCouponActivationRedemption() {
        fun couponVisible() = m1CouponExists && m1CouponStatus == "active" && m1CouponReview == "approved"

        startServer { request ->
            val path = request.requestUrl!!.encodedPath
            val auth = request.getHeader("Authorization")
            val method = request.method
            when {
                path == "/partner/me" && auth == "Bearer $ownerToken" -> when (m1PartnerStatus) {
                    "none" -> json("""{"partner":null}""")
                    else -> json("""{"partner":${partnerJson(301, m1PartnerStatus, m1PartnerCategory, subActive = m1PartnerStatus == "active")}}""")
                }
                path == "/partner" && method == "POST" && auth == "Bearer $ownerToken" -> {
                    val body = JSONObject(request.body.readUtf8())
                    m1PartnerCategory = body.optString("category")
                    m1PartnerStatus = "pending"
                    json(partnerJson(301, "pending", m1PartnerCategory))
                }
                path == "/admin/partners" && method == "GET" && auth == "Bearer $adminToken" ->
                    json("""{"items":[${if (m1PartnerStatus == "pending") partnerJson(301, "pending", m1PartnerCategory) else ""}]}""")
                path == "/admin/partners/301/approve" && auth == "Bearer $adminToken" -> {
                    m1PartnerStatus = "active"; json("{}")
                }
                path == "/partner/coupons" && method == "GET" && auth == "Bearer $ownerToken" ->
                    json("""{"items":[${if (m1CouponExists) ownerCouponJson(501, 301, m1CouponStatus, m1CouponReview) else ""}]}""")
                path == "/partner/coupons" && method == "POST" && auth == "Bearer $ownerToken" -> {
                    m1CouponCreateBody = JSONObject(request.body.readUtf8())
                    m1CouponExists = true
                    json(ownerCouponJson(501, 301, m1CouponStatus, m1CouponReview))
                }
                path == "/partner/coupons/501/status" && auth == "Bearer $ownerToken" -> {
                    m1CouponStatusRequest = JSONObject(request.body.readUtf8()).optString("status")
                    m1CouponStatus = "active"; json("{}")
                }
                path == "/admin/moderation" && method == "GET" && auth == "Bearer $adminToken" -> {
                    val queued = m1CouponExists && m1CouponReview == "pending"
                    json("""{"partners":[],"coupons":[${if (queued) adminCouponJson(501, 301, m1CouponStatus, m1CouponReview) else ""}],"total":${if (queued) 1 else 0}}""")
                }
                path == "/admin/coupons/501/approve" && auth == "Bearer $adminToken" -> {
                    m1CouponReview = "approved"; json("{}")
                }
                path == "/coupons" && method == "GET" ->
                    json("""{"items":[${if (couponVisible()) publicCouponJson(501, 301, m1PartnerCategory, m1CouponStatus) else ""}]}""")
                path == "/coupons/501" && method == "GET" ->
                    if (couponVisible()) json(publicCouponJson(501, 301, m1PartnerCategory, m1CouponStatus))
                    else err(404, "Купон не найден")
                path == "/coupons/501/activate" && method == "POST" && auth == "Bearer $clientToken" -> {
                    m1RedeemStatus = "reserved"
                    json("""{"code":"$m1RedeemCode","status":"reserved","reserved_at":"2026-09-25T10:00:00Z",
                        "coupon":${publicCouponJson(501, 301, m1PartnerCategory, m1CouponStatus)}}""")
                }
                path == "/coupons/redeem" && method == "POST" && auth == "Bearer $ownerToken" -> {
                    val code = JSONObject(request.body.readUtf8()).optString("code")
                    when {
                        code != m1RedeemCode -> { m1RedeemResponseCodes.add(404); err(404, "Код не найден") }
                        m1RedeemStatus == "redeemed" -> { m1RedeemResponseCodes.add(409); err(409, "Код уже погашён") }
                        else -> {
                            m1RedeemStatus = "redeemed"; m1RedeemResponseCodes.add(200)
                            json("""{"coupon_title":"$couponTitle","discount_text":"$couponDiscount","customer_name":"Тестовый клиент"}""")
                        }
                    }
                }
                path == "/my/coupons" && method == "GET" && auth == "Bearer $clientToken" ->
                    json("""{"items":[${if (m1RedeemStatus != "none") myCouponJson(m1RedeemCode, m1RedeemStatus, 501, 301, m1PartnerCategory) else ""}]}""")
                path == "/me" -> json("{}")
                else -> json("{}", 404)
            }
        }
        mount()

        // Владелец: первичная Android-регистрация (шесть полей + категория-чип).
        waitFor("Название заведения")
        fill("Название заведения", "Кафе Юлдаш")
        clickScrolled("Ресторан")
        fill("Город", "Уфа")
        fill("Адрес", "ул. Ленина, 1")
        fill("Телефон", "+79991234567")
        fill("Описание", "Уютное кафе для попутчиков")
        clickScrolled("Отправить на проверку")
        waitFor("Бизнес на проверке")
        assertEquals("restaurant", m1PartnerCategory)

        // Админ: одобряет заявку.
        switchTo(1, adminToken)
        waitFor("Одобрить")
        clickScrolled("Одобрить")
        compose.waitUntil(10_000) { m1PartnerStatus == "active" }

        // Владелец: кабинет активен — создаёт купон (черновик) и включает его.
        switchTo(0, ownerToken)
        waitFor("Мои купоны")
        clickScrolled("Создать")
        waitFor("Заголовок")
        fill("Заголовок", couponTitle)
        fill("Скидка (например: -20%)", couponDiscount)
        clickScrolled("Создать купон")
        // Ждём сигнал, которого не может быть в самой форме (G5 P2 b): «Включить» существует
        // только на карточке купона в списке (PartnerCabinetScreen.kt:511-514), а не в CouponForm.
        waitFor("Включить")
        assertEquals(couponTitle, m1CouponCreateBody?.getString("title"))
        assertEquals(couponDiscount, m1CouponCreateBody?.getString("discount_text"))
        clickScrolled("Включить")
        waitFor("Пауза")
        assertEquals("active", m1CouponStatusRequest)

        // Админ: купон в очереди модерации — одобряет.
        switchTo(2, adminToken)
        waitFor("Всё в порядке")
        clickScrolled("Всё в порядке")
        compose.waitUntil(10_000) { m1CouponReview == "approved" }

        // Клиент: активирует купон на витрине. Код читаем с самого экрана (G5 P1) — так,
        // как это делает живой человек, а не берём константу стенда напрямую: если бы
        // экран показал пустой или неверный код, редемпшн ниже упёрся бы в 404.
        switchTo(3, clientToken)
        waitFor(couponTitle)
        clickScrolled(couponTitle)
        waitFor("Активировать скидку")
        clickScrolled("Активировать скидку")
        waitFor("Скидка активирована!")
        scroll(hasText(m1RedeemCode))
        val visibleCode = compose.onNodeWithText(m1RedeemCode).assertIsDisplayed()
            .fetchSemanticsNode().config[SemanticsProperties.Text].single().text
        clickScrolled("Готово")

        // Владелец: гасит код клиента, повтор того же кода — уже погашён.
        switchTo(0, ownerToken)
        waitFor("Погасить код клиента")
        openDialogWithTextField { clickScrolled("Погасить код клиента") }
        // Диалог и кнопка под ним делят один и тот же текст — оба узла подтверждают, что
        // заголовок диалога («Погасить код клиента», PartnerCabinetScreen.kt:769) реально отрисован
        // (тот же приём, что и «Отклонить» в businessUnhappyPaths). Поле — «Код купона» (:788).
        assertEquals(2, compose.onAllNodesWithText("Погасить код клиента").fetchSemanticsNodes().size)
        assertVisible("Код купона")
        fillDialog(visibleCode)
        clickScrolled("Погасить")
        waitFor("Скидка подтверждена")
        clickScrolled("Готово")
        openDialogWithTextField { clickScrolled("Погасить код клиента") }
        fillDialog(visibleCode)
        clickScrolled("Погасить")
        waitFor("Код уже погашён")
        clickScrolled("Отмена")

        // Клиент: купон теперь помечен использованным.
        switchTo(3, clientToken)
        clickScrolled("Мои купоны")
        waitFor("Использован")

        assertEquals(1, requests.count { it == "POST /partner" })
        assertEquals(1, requests.count { it == "POST /coupons/501/activate" })
        assertEquals(2, requests.count { it == "POST /coupons/redeem" })
        assertEquals(listOf(200, 409), m1RedeemResponseCodes.toList())
    }

    // ═══════════════════ Метод 2: несчастливые пути ═══════════════════

    // Тот же повод для @Volatile, что у m1*-полей выше: m2ActiveOverride пишется из потока
    // теста (после waitFor("Заявка отклонена")), а читается из потока MockWebServer.
    @Volatile private var m2PartnerStatus = "none"     // none|pending|rejected
    @Volatile private var m2RejectReason = ""
    @Volatile private var m2ActiveOverride = false

    @Test fun businessUnhappyPaths() {
        val partnerMeCalls = AtomicInteger(0)
        val registerCalls = AtomicInteger(0)
        val couponsCalls = AtomicInteger(0)

        startServer { request ->
            val path = request.requestUrl!!.encodedPath
            val auth = request.getHeader("Authorization")
            val method = request.method
            when {
                path == "/partner/me" && auth == "Bearer $ownerToken" -> when {
                    partnerMeCalls.getAndIncrement() == 0 -> json("{}", 503)
                    m2ActiveOverride -> json("""{"partner":${partnerJson(401, "active", "cafe", subActive = true)}}""")
                    m2PartnerStatus == "none" -> json("""{"partner":null}""")
                    m2PartnerStatus == "rejected" -> json("""{"partner":${partnerJson(401, "rejected", "cafe", rejectReason = m2RejectReason)}}""")
                    else -> json("""{"partner":${partnerJson(401, m2PartnerStatus, "cafe")}}""")
                }
                path == "/partner" && method == "POST" && auth == "Bearer $ownerToken" ->
                    if (registerCalls.getAndIncrement() == 0) err(422, "Проверь номер телефона")
                    else { m2PartnerStatus = "pending"; json(partnerJson(401, "pending", "cafe")) }
                path == "/admin/partners" && method == "GET" && auth == "Bearer $adminToken" ->
                    json("""{"items":[${if (m2PartnerStatus == "pending") partnerJson(401, "pending", "cafe") else ""}]}""")
                path == "/admin/partners/401/reject" && auth == "Bearer $adminToken" -> {
                    m2RejectReason = JSONObject(request.body.readUtf8()).optString("reason")
                    m2PartnerStatus = "rejected"
                    json("{}")
                }
                path == "/partner/coupons" && method == "GET" && auth == "Bearer $ownerToken" -> json("""{"items":[]}""")
                path == "/coupons/redeem" && method == "POST" && auth == "Bearer $ownerToken" -> err(404, "Код не найден")
                path == "/coupons" && method == "GET" -> when (couponsCalls.getAndIncrement()) {
                    0 -> MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                    1 -> json("""{"items":[${publicCouponJson(601, 402, "cafe", "active")}]}""")
                    else -> json("""{"items":[]}""")
                }
                path == "/coupons/601" && method == "GET" -> json(publicCouponJson(601, 402, "cafe", "active"))
                path == "/coupons/601/activate" && method == "POST" && auth == "Bearer $clientToken" ->
                    err(409, "Купоны закончились")
                path == "/me" -> json("{}")
                else -> json("{}", 404)
            }
        }
        mount()

        // /partner/me 503 -> ошибка с «Повторить».
        waitFor("Ошибка сервера. Попробуй позже.")
        clickScrolled("Повторить")

        // Регистрация: 422 сохраняет форму; второй сабмит проходит.
        waitFor("Название заведения")
        fill("Название заведения", "Кафе Юлдаш")
        fill("Город", "Уфа")
        clickScrolled("Отправить на проверку")
        waitFor("Проверь номер телефона")
        assertVisible("Отправить на проверку")
        clickScrolled("Отправить на проверку")
        waitFor("Бизнес на проверке")

        // Админ отклоняет заявку с причиной (диалог с текстовым полем — обязательна
        // openDialogWithTextField, иначе платформенно-широкий диалог никогда не идёт).
        switchTo(1, adminToken)
        waitFor("Отклонить")
        openDialogWithTextField { clickScrolled("Отклонить") }
        waitFor("Отклонить бизнес")
        fillDialog("Нет лицензии на продажу")
        compose.onAllNodesWithText("Отклонить").onLast().performClick()
        compose.waitUntil(10_000) { m2PartnerStatus == "rejected" }

        // Владелец видит отказ и причину. Правку -> pending здесь не повторяем
        // (RoleCabinetIntegrationTest.rejectedBusinessResubmitsEditedDataAndReloadsModerationStatus).
        switchTo(0, ownerToken)
        waitFor("Заявка отклонена")
        assertVisible("Нет лицензии на продажу")

        // Тот же кабинет, но уже активный (для проверки диалога погашения): отмена — без запроса,
        // неизвестный код — 404.
        m2ActiveOverride = true
        switchTo(0, ownerToken)
        waitFor("Погасить код клиента")
        openDialogWithTextField { clickScrolled("Погасить код клиента") }
        clickScrolled("Отмена")
        assertEquals(0, requests.count { it == "POST /coupons/redeem" })
        openDialogWithTextField { clickScrolled("Погасить код клиента") }
        fillDialog("NOPE99")
        clickScrolled("Погасить")
        waitFor("Код не найден")
        clickScrolled("Отмена")
        assertEquals(1, requests.count { it == "POST /coupons/redeem" })

        // Клиент: таймаут витрины -> «Повторить», активация 409 (лимит исчерпан), затем пусто.
        switchTo(3, clientToken)
        waitFor("Не удалось загрузить скидки. Проверь интернет.")
        clickScrolled("Повторить")
        waitFor(couponTitle)
        clickScrolled(couponTitle)
        waitFor("Активировать скидку")
        clickScrolled("Активировать скидку")
        waitFor("Купоны закончились")
        compose.onNodeWithContentDescription("Назад").performClick()
        waitFor("Скидок пока нет")

        assertEquals(2, requests.count { it == "POST /partner" })
        assertEquals(1, requests.count { it == "POST /admin/partners/401/reject" })
        assertEquals(1, requests.count { it == "POST /coupons/601/activate" })
    }
}
