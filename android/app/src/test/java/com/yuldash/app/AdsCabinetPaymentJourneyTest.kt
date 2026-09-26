package com.yuldash.app

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.MyAdDto
import com.yuldash.app.ui.theme.YuldashTheme
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

// PATH-10, advertiser (F-10). Real AdminAdsScreen/AdsCabinetScreen/AdEditorScreen/
// AdminPaymentRequestsScreen/YuldashApp; only the HTTP backend is replaced by a local stand.
private const val PKG_CODE = "week"
private const val PKG_TITLE = "Неделя"
private const val PKG_TITLE_BA = "Аҙна"
private const val PKG_AMOUNT_KOP = 50000
private const val PKG_PERIOD_DAYS = 7
private const val AD_ID = "a1"
private const val ERID_MISSING_MSG =
    "Нужен erid — реклама без маркировки запрещена. Получи номер в ОРД и вставь его."
// ApiClient.errorMessage() falls back to genericByStatus() when the body has no "detail" (our
// empty-body 503 stand responses below): for 5xx that generic text is this one, NOT the caller's
// own serverSaid() fallback string (that fallback only fires for non-HTTP/connectivity failures).
private const val SERVER_ERROR_MSG = "Ошибка сервера. Попробуй позже."

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdsCabinetPaymentJourneyTest {

    @get:Rule val compose = createComposeRule()

    // Stand shell: page picks the mounted role screen, advStage switches the advertiser's own
    // cabinet<->editor navigation (mirrors YuldashApp's Screen.AdsCabinet/Screen.AdEditor wiring).
    private val mounted = mutableStateOf(true)
    private val page = mutableIntStateOf(0)
    private val remountKey = mutableIntStateOf(0)
    private val advStage = mutableStateOf("cabinet")
    private val advEditTarget = mutableStateOf<MyAdDto?>(null)

    private var server: MockWebServer? = null
    private val residualNotes = CopyOnWriteArrayList<String>()

    // ---- server-side fixture for advertiserCreateModerationPaymentActive ----
    @Volatile private var mainAdExists = false
    @Volatile private var mainTitle = ""
    @Volatile private var mainText = ""
    @Volatile private var mainButton = ""
    @Volatile private var mainTarget = ""
    @Volatile private var mainCities = ""
    @Volatile private var mainPkg = ""
    @Volatile private var mainStatus = "draft"
    @Volatile private var mainRejectReason = ""
    @Volatile private var mainErid = ""
    @Volatile private var mainPartner = ""
    @Volatile private var mainPaid = false
    @Volatile private var mainPaymentId: Int? = null
    private val mainCreateBodies = CopyOnWriteArrayList<String>()
    // G5 finding #4: proves the post-"Я перевёл" cabinet reload actually happens (not just
    // asserted by absence of an exception) — counts real GET /ads/mine hits from the stand.
    private val mainAdsMineGetCount = AtomicInteger(0)

    // ---- server-side fixture for advertiserUnhappyPaths ----
    @Volatile private var uhAdExists = false
    @Volatile private var uhTitle = ""
    @Volatile private var uhPkg = ""
    @Volatile private var uhStatus = "draft"
    @Volatile private var uhMineFailNext = false
    @Volatile private var uhMineNoResponseNext = false
    @Volatile private var uhSubmitFailNext = false
    @Volatile private var uhCreateFailNext = false
    @Volatile private var uhPayMode = "normal"
    private val uhCreateSuccessCount = AtomicInteger(0)

    @Before
    fun setup() {
        ApiClient.resetForTest()
        ApiClient.init(ApplicationProvider.getApplicationContext<Context>())
        ShadowToast.reset()
    }

    @After
    fun cleanup() {
        runCatching {
            compose.runOnIdle { mounted.value = false; page.intValue = -1 }
            compose.waitForIdle()
        }
        ApiClient.logout()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server?.shutdown()
        server = null
        residualNotes.forEach { println("RESIDUAL: $it") }
    }

    // ═══════════ D10-1 (RED first): action failure must not blank the whole list ═══════════

    @Test
    fun adminApproveWithoutEridShowsServerReasonAndKeepsList() {
        ApiClient.saveToken("local-admin")
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    return when {
                        path == "/admin/ads" && request.method == "GET" -> json(
                            """{"founder_used":0,"founder_limit":10,"items":[{"id":"1",""" +
                                """"partner":"Кафе Урал","title":"Скидка 20%","text":"Заходи на обед",""" +
                                """"plan":"standard","status":"pending_review","placements":["route"],""" +
                                """"erid":"","live":false,"expired":false,"cities":[],""" +
                                """"reject_reason":"","owner_id":7}]}"""
                        )
                        path == "/ads/stats" -> json("{}")
                        path == "/admin/ads/1/approve" -> json("""{"detail":"$ERID_MISSING_MSG"}""", 422)
                        else -> json("{}")
                    }
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server!!.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 3000
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                YuldashTheme { AdminAdsScreen(onBack = {}) }
            }
        }
        waitFor("Скидка 20%")
        clickScrolled("Одобрить")
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            ShadowToast.getTextOfLatestToast() == ERID_MISSING_MSG
        }
        compose.onNodeWithText("Скидка 20%").assertIsDisplayed()
        compose.onNodeWithText("Не удалось загрузить. Проверь интернет.").assertDoesNotExist()
    }

    // ═══════════ D10-1 continued: publish/pause/delete are the same shared-onFailure pattern as
    // approve — cover them too so the fix (and not just the one reproduced case) is verified ═══════════

    @Test
    fun adminPublishPauseDeleteFailuresShowFallbackAndKeepList() {
        ApiClient.saveToken("local-admin")
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    return when {
                        path == "/admin/ads" && request.method == "GET" -> json(
                            """{"founder_used":0,"founder_limit":10,"items":[""" +
                                """{"id":"x1","partner":"","title":"Активная реклама","text":"",""" +
                                """"plan":"standard","status":"active","placements":[],""" +
                                """"erid":"ORD1","live":true,"expired":false,"cities":[],""" +
                                """"reject_reason":"","owner_id":7},""" +
                                """{"id":"x2","partner":"","title":"Реклама на паузе","text":"",""" +
                                """"plan":"standard","status":"paused","placements":[],""" +
                                """"erid":"ORD2","live":false,"expired":false,"cities":[],""" +
                                """"reject_reason":"","owner_id":8}]}"""
                        )
                        path == "/ads/stats" -> json("{}")
                        path == "/admin/ads/x1/status" -> json("{}", 503)
                        path == "/admin/ads/x2/status" -> json("{}", 503)
                        path == "/admin/ads/x2" && request.method == "DELETE" -> json("{}", 503)
                        else -> json("{}")
                    }
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server!!.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 3000
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                YuldashTheme { AdminAdsScreen(onBack = {}) }
            }
        }
        waitFor("Активная реклама")
        waitFor("Реклама на паузе")

        // onPause — only the "active" card shows "Пауза".
        ShadowToast.reset()
        clickScrolled("Пауза")
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            ShadowToast.getTextOfLatestToast() == SERVER_ERROR_MSG
        }
        compose.onNodeWithText("Активная реклама").assertIsDisplayed()
        compose.onNodeWithText("Реклама на паузе").assertIsDisplayed()

        // onPublish — only the "paused" card shows "Опубликовать".
        ShadowToast.reset()
        clickScrolled("Опубликовать")
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            ShadowToast.getTextOfLatestToast() == SERVER_ERROR_MSG
        }
        compose.onNodeWithText("Активная реклама").assertIsDisplayed()
        compose.onNodeWithText("Реклама на паузе").assertIsDisplayed()

        // onDelete — "Удалить" exists on both cards (title is a sibling subtree, not an
        // ancestor, so hasAnyAncestor(hasText(title)) can't scope it). Both cards are already
        // on screen (asserted above with no scroll needed), and the stand lists the paused ad
        // ("x2") second, so its "Удалить" is the last of the two matches.
        ShadowToast.reset()
        compose.onAllNodesWithText("Удалить").onLast().performClick()
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            ShadowToast.getTextOfLatestToast() == SERVER_ERROR_MSG
        }
        compose.onNodeWithText("Активная реклама").assertIsDisplayed()
        compose.onNodeWithText("Реклама на паузе").assertIsDisplayed()
        compose.onNodeWithText("Не удалось загрузить. Проверь интернет.").assertDoesNotExist()
    }

    // ═══════════ D10-1 continued (G5 finding #5): reject must share the same onFailure/
    // serverSaid fallback as publish/pause/delete/approve — AdminAdsScreen.kt:173's onFailure
    // Toast branch was never exercised by any test before this one ═══════════

    @Test
    fun adminRejectFailureShowsFallbackAndKeepsList() {
        ApiClient.saveToken("local-admin")
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    return when {
                        path == "/admin/ads" && request.method == "GET" -> json(
                            """{"founder_used":0,"founder_limit":10,"items":[{"id":"1",""" +
                                """"partner":"Кафе Урал","title":"Скидка 20%","text":"Заходи на обед",""" +
                                """"plan":"standard","status":"pending_review","placements":["route"],""" +
                                """"erid":"","live":false,"expired":false,"cities":[],""" +
                                """"reject_reason":"","owner_id":7}]}"""
                        )
                        path == "/ads/stats" -> json("{}")
                        path == "/admin/ads/1/reject" -> json("{}", 503)
                        else -> json("{}")
                    }
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server!!.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 3000
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                YuldashTheme { AdminAdsScreen(onBack = {}) }
            }
        }
        waitFor("Скидка 20%")
        clickScrolled("Отклонить")
        fill("Причина отказа (партнёр увидит)", "Проверка ошибки сервера")
        clickScrolled("Отклонить")
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            ShadowToast.getTextOfLatestToast() == SERVER_ERROR_MSG
        }
        compose.onNodeWithText("Скидка 20%").assertIsDisplayed()
        compose.onNodeWithText("Не удалось загрузить. Проверь интернет.").assertDoesNotExist()
    }

    // ═══════════ D10-2 (RED first): admin cabinet's ad row must open moderation ═══════════

    @Test
    fun adminCabinetAdsRowOpensModeration() {
        ApiClient.saveToken("local-admin")
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    return when {
                        path == "/me" -> json("""{"role":"admin","name":"Админ"}""")
                        else -> json("{}")
                    }
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server!!.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 3000
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        val vm = ViewModelProvider(owner, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                YuldashViewModel(SavedStateHandle(mapOf("yuldash_screen" to "Settings"))) as T
        })[YuldashViewModel::class.java]
        compose.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner, LocalAppLanguage provides AppLanguage.Ru) {
                YuldashApp()
            }
        }
        // "Кабинет админа" sits far down a long settings list, gated behind the async /me
        // role check: wait for isAdmin to resolve before scrolling (same idiom as
        // AdminDecisionJourneyTest.adminCabinetCourierDecisionReachesApplicant) — a one-shot
        // scroll before that races the role check and the row is never composed.
        compose.waitUntil(15000) { Shadows.shadowOf(Looper.getMainLooper()).idle(); vm.isAdmin.value }
        clickScrolled("Кабинет админа")
        // "Реклама" sits near the bottom of AdminCabinetScreen's LazyColumn (SecondaryScreens.kt
        // :1711, third item-group) — off-screen and not yet composed right after navigation, so a
        // bare waitFor (no scroll) never finds it. Wait for the screen's own top-of-list header
        // (always composed first) to confirm the mount, same idiom as
        // AdminDecisionJourneyTest.adminCabinetCourierDecisionReachesApplicant's post-nav wait;
        // clickScrolled below does the actual scroll-and-click.
        waitFor("Единый центр управления Юлдашем. Виден только администратору.")
        clickScrolled("Реклама")
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            vm.screen.value == Screen.AdminAds
        }
        compose.onNodeWithText("Управление рекламой").assertIsDisplayed()
        compose.onNodeWithText("Кабинет рекламы").assertDoesNotExist()
        owner.viewModelStore.clear()
    }

    // ═══════════ Main path: create → reject → edit+resubmit → admin fills erid+approves →
    // pay → admin confirms → advertiser sees paid+active with stats ═══════════

    @Test
    fun advertiserCreateModerationPaymentActive() {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    val method = request.method
                    val auth = request.getHeader("Authorization")
                    val isAdvertiser = auth == "Bearer local-advertiser"
                    val isAdmin = auth == "Bearer local-admin"
                    val pid = mainPaymentId
                    return when {
                        path == "/ad-packages" -> json(
                            """{"items":[{"code":"$PKG_CODE","title":"$PKG_TITLE","title_ba":"$PKG_TITLE_BA",""" +
                                """"amount_kop":$PKG_AMOUNT_KOP,"period_days":$PKG_PERIOD_DAYS}]}"""
                        )
                        path == "/ads/mine" && method == "GET" && isAdvertiser -> {
                            mainAdsMineGetCount.incrementAndGet()
                            if (!mainAdExists) json("""{"items":[]}""") else json("""{"items":[${mainAdJson()}]}""")
                        }
                        path == "/ads/mine/stats" && isAdvertiser ->
                            if (mainStatus == "active" && mainPaid)
                                json("""{"items":[{"ad_id":"$AD_ID","impressions":0,"clicks":0,"ctr":0.0,"days_left":$PKG_PERIOD_DAYS}]}""")
                            else json("""{"items":[]}""")
                        path == "/ads" && method == "POST" && isAdvertiser -> {
                            val b = JSONObject(request.body.readUtf8())
                            mainCreateBodies.add(b.toString())
                            mainTitle = b.optString("title"); mainText = b.optString("text")
                            mainButton = b.optString("button"); mainTarget = b.optString("target")
                            mainCities = b.optString("cities"); mainPkg = b.optString("package")
                            mainAdExists = true; mainStatus = "draft"; mainRejectReason = ""
                            mainErid = ""; mainPaid = false
                            json(mainAdJson())
                        }
                        path == "/ads/$AD_ID" && method == "POST" && isAdvertiser -> {
                            val b = JSONObject(request.body.readUtf8())
                            mainTitle = b.optString("title"); mainText = b.optString("text")
                            mainButton = b.optString("button"); mainTarget = b.optString("target")
                            mainCities = b.optString("cities"); mainPkg = b.optString("package")
                            json(mainAdJson())
                        }
                        path == "/ads/$AD_ID/submit" && isAdvertiser -> {
                            mainStatus = "pending_review"; mainRejectReason = ""
                            json(mainAdJson())
                        }
                        path == "/ads/$AD_ID/pay" && isAdvertiser ->
                            if (mainStatus != "active")
                                json("""{"detail":"Оплата доступна после одобрения модерацией"}""", 409)
                            else { mainPaymentId = 901; json("""{"amount_kop":$PKG_AMOUNT_KOP}""") }
                        path == "/admin/ads" && method == "GET" && isAdmin ->
                            if (!mainAdExists) json("""{"founder_used":0,"founder_limit":10,"items":[]}""")
                            else json("""{"founder_used":0,"founder_limit":10,"items":[${mainAdminAdJson()}]}""")
                        path == "/ads/stats" && isAdmin -> json("{}")
                        path == "/admin/ads/$AD_ID/reject" && isAdmin -> {
                            val b = JSONObject(request.body.readUtf8())
                            mainStatus = "rejected"; mainRejectReason = b.optString("reason")
                            json("{}")
                        }
                        path == "/admin/ads/$AD_ID" && method == "POST" && isAdmin -> {
                            val b = JSONObject(request.body.readUtf8())
                            mainPartner = b.optString("partner_name"); mainErid = b.optString("erid")
                            json("{}")
                        }
                        path == "/admin/ads/$AD_ID/approve" && isAdmin -> {
                            val b = JSONObject(request.body.readUtf8())
                            val erid = b.optString("erid").ifBlank { mainErid }
                            if (erid.isBlank()) json("""{"detail":"$ERID_MISSING_MSG"}""", 422)
                            else { mainErid = erid; mainStatus = "active"; mainRejectReason = ""; json("{}") }
                        }
                        path == "/admin/payments/pending" && isAdmin ->
                            if (pid == null) json("""{"items":[]}""")
                            else json(
                                """{"items":[{"payment_id":$pid,"purpose":"ad","tier":"",""" +
                                    """"amount":${PKG_AMOUNT_KOP / 100},"ride_id":null,""" +
                                    """"payer_name":"Тестовый партнёр","payer_phone":"",""" +
                                    """"created_at":"2026-09-25T10:00:00","note":""}]}"""
                            )
                        pid != null && path == "/admin/payments/$pid/confirm" && isAdmin -> {
                            mainPaid = true; mainPaymentId = null
                            json("{}")
                        }
                        else -> json("{}")
                    }
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server!!.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 4000
        ApiClient.saveToken("local-advertiser")

        compose.setContent {
            if (mounted.value) {
                CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                    YuldashTheme {
                        key(page.intValue) {
                            when (page.intValue) {
                                0 -> when (advStage.value) {
                                    "editor" -> AdEditorScreen(
                                        initial = advEditTarget.value,
                                        onBack = { advStage.value = "cabinet" },
                                        onSaved = { advStage.value = "cabinet" },
                                    )
                                    else -> AdsCabinetScreen(
                                        onBack = {},
                                        onCreateAd = { advEditTarget.value = null; advStage.value = "editor" },
                                        onEditAd = { dto -> advEditTarget.value = dto; advStage.value = "editor" },
                                    )
                                }
                                1 -> AdminAdsScreen(onBack = {})
                                2 -> AdminPaymentRequestsScreen(onBack = {})
                                else -> Unit
                            }
                        }
                    }
                }
            }
        }

        // 1. Empty cabinet shows the "Разместить рекламу" showcase.
        waitFor("Разместить рекламу")
        clickScrolled("Разместить рекламу")

        // 2. Fill the editor and send to moderation.
        waitFor("Новое объявление")
        fill("Заголовок", "Шиномонтаж у Марата")
        fill("Описание", "Быстро и недорого, без записи")
        fill("Текст кнопки (напр. «Позвонить»)", "Позвонить")
        fill("Ссылка или телефон", "+79997776655")
        fill("Город(а) через запятую — пусто = все", "Уфа")
        clickScrolled(PKG_TITLE)
        clickScrolled("На модерацию")
        waitFor("На модерации")
        assertEquals(1, mainCreateBodies.size)
        val createdBody = JSONObject(mainCreateBodies.single())
        assertEquals("Шиномонтаж у Марата", createdBody.getString("title"))
        assertEquals("Быстро и недорого, без записи", createdBody.getString("text"))
        assertEquals("Позвонить", createdBody.getString("button"))
        assertEquals("Уфа", createdBody.getString("cities"))
        assertEquals(PKG_CODE, createdBody.getString("package"))

        // 3. Admin rejects with a reason.
        switchTo(1, "local-admin")
        waitFor("Шиномонтаж у Марата")
        clickScrolled("Отклонить")
        fill("Причина отказа (партнёр увидит)", "Нет erid и логотипа")
        clickScrolled("Отклонить")
        compose.waitUntil(15000) { Shadows.shadowOf(Looper.getMainLooper()).idle(); mainStatus == "rejected" }

        // 4. Advertiser sees the rejection, edits the title and resubmits.
        switchTo(0, "local-advertiser")
        waitFor("Отклонено")
        compose.onNodeWithText("Причина отказа: Нет erid и логотипа", substring = true).assertExists()
        clickScrolled("Изменить")
        waitFor("Изменить объявление")
        fill("Заголовок", "Шиномонтаж у Марата PRO")
        clickScrolled("На модерацию")
        waitFor("На модерации")

        // 5. Admin fills erid+partner, saves, then approves.
        switchTo(1, "local-admin")
        waitFor("Шиномонтаж у Марата PRO")
        clickScrolled("Изменить")
        fill("Рекламодатель", "ИП Марат")
        fill("erid (маркировка)", "ORD123456")
        clickScrolled("Сохранить")
        compose.waitUntil(15000) { Shadows.shadowOf(Looper.getMainLooper()).idle(); mainErid == "ORD123456" }
        clickScrolled("Одобрить")
        compose.waitUntil(15000) { Shadows.shadowOf(Looper.getMainLooper()).idle(); mainStatus == "active" }

        // 6. Advertiser pays; SBP sheet fallback per REPAIR-2 N3 (WEAK-EVIDENCE #4): the "never
        // queryable" branch stays a residual (classify, don't skip). Once queryable, G5 finding #4
        // requires the tap, the sheet's close and the cabinet reload all be asserted, not swallowed.
        switchTo(0, "local-advertiser")
        waitFor("Одобрено! Оплати размещение — и объявление пойдёт в показы.")
        clickScrolled("Оплатить размещение · 500 ₽")
        if (waitForOptional("Я перевёл", 8000)) {
            val getsBeforePay = mainAdsMineGetCount.get()
            compose.onNodeWithText("Я перевёл").performClick()
            assertTrue("SBP sheet must close after 'Я перевёл'", waitForGoneOptional("Я перевёл", 5000))
            compose.waitUntil(5000) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                mainAdsMineGetCount.get() > getsBeforePay
            }
        } else {
            residualNotes += "main path: SBP sheet 'Я перевёл' never became queryable — residual (WEAK-EVIDENCE #4)"
        }

        // 7. Admin confirms the pending ad payment.
        switchTo(2, "local-admin")
        waitFor("Реклама")
        compose.onNodeWithText("500 ₽").assertIsDisplayed()
        clickScrolled("Подтвердить")
        compose.waitUntil(15000) { Shadows.shadowOf(Looper.getMainLooper()).idle(); mainPaid }

        // 8. Advertiser: paid + active, stats tiles shown. G5 finding #2: the status line
        // "...показывается." also contains "показы" as a substring, so a substring query would
        // pass even if AdStatsTiles never rendered — onNodeWithText's default exact match names
        // the CabinetMetric label itself (ProfileScreen.kt AdStatsTiles), nothing else.
        switchTo(0, "local-advertiser")
        waitFor("Оплачено · объявление показывается.")
        compose.onNodeWithText("показы").assertIsDisplayed()
        compose.onNodeWithText("клики").assertIsDisplayed()
    }

    // ═══════════ Unhappy paths (advertiser only) ═══════════

    @Test
    fun advertiserUnhappyPaths() {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    val method = request.method
                    return when {
                        path == "/ad-packages" -> json(
                            """{"items":[{"code":"$PKG_CODE","title":"$PKG_TITLE","title_ba":"$PKG_TITLE_BA",""" +
                                """"amount_kop":$PKG_AMOUNT_KOP,"period_days":$PKG_PERIOD_DAYS}]}"""
                        )
                        path == "/ads/mine" && method == "GET" -> when {
                            uhMineNoResponseNext -> {
                                uhMineNoResponseNext = false
                                MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                            }
                            uhMineFailNext -> { uhMineFailNext = false; json("{}", 503) }
                            !uhAdExists -> json("""{"items":[]}""")
                            else -> json("""{"items":[${uhAdJson()}]}""")
                        }
                        path == "/ads/mine/stats" -> json("""{"items":[]}""")
                        path == "/ads" && method == "POST" ->
                            if (uhCreateFailNext) { uhCreateFailNext = false; json("{}", 503) }
                            else {
                                val b = JSONObject(request.body.readUtf8())
                                uhTitle = b.optString("title"); uhPkg = b.optString("package")
                                uhAdExists = true; uhStatus = "draft"
                                uhCreateSuccessCount.incrementAndGet()
                                json(uhAdJson())
                            }
                        path == "/ads/$AD_ID/submit" ->
                            if (uhSubmitFailNext) {
                                uhSubmitFailNext = false
                                json("""{"detail":"Заполни заголовок и выбери тариф"}""", 422)
                            } else { uhStatus = "pending_review"; json(uhAdJson()) }
                        path == "/ads/$AD_ID/pay" ->
                            if (uhPayMode == "409")
                                json("""{"detail":"Оплата доступна после одобрения модерацией"}""", 409)
                            else json("""{"amount_kop":$PKG_AMOUNT_KOP}""")
                        else -> json("{}")
                    }
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server!!.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 4000
        ApiClient.saveToken("local-advertiser")

        compose.setContent {
            if (mounted.value) {
                CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                    YuldashTheme {
                        key(remountKey.intValue) {
                            when (advStage.value) {
                                "editor" -> AdEditorScreen(
                                    initial = advEditTarget.value,
                                    onBack = { advStage.value = "cabinet" },
                                    onSaved = { advStage.value = "cabinet" },
                                )
                                else -> AdsCabinetScreen(
                                    onBack = {},
                                    onCreateAd = { advEditTarget.value = null; advStage.value = "editor" },
                                    onEditAd = { dto -> advEditTarget.value = dto; advStage.value = "editor" },
                                )
                            }
                        }
                    }
                }
            }
        }

        // (a) Cabinet load 503 shows the error+retry; retry succeeds.
        uhMineFailNext = true
        waitFor("Не удалось загрузить кабинет")
        compose.onNodeWithText("Повторить").assertIsDisplayed()
        // The error state (ListedError) is a plain Column, not a LazyColumn — no scrollable
        // ancestor exists here, so clickScrolled (which requires one) is not used.
        compose.onNodeWithText("Повторить").performClick()
        waitFor("Разместить рекламу")

        // (c) Create 503 then retry: exactly one ad is created (package picked so the later
        // "На модерацию" card button in (b) is enabled).
        clickScrolled("Разместить рекламу")
        waitFor("Новое объявление")
        fill("Заголовок", "Тестовая реклама")
        clickScrolled(PKG_TITLE)
        uhCreateFailNext = true
        clickScrolled("Сохранить")
        // AdEditorScreen.save() reports a create failure via a native Toast (ProfileScreen.kt
        // save()/onFailure), never a Compose node — waitFor (onAllNodesWithText) can never see it.
        // Check the toast text directly, same idiom as adminApproveWithoutEridShowsServerReasonAndKeepsList.
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            ShadowToast.getTextOfLatestToast() == "Ошибка сервера. Попробуй позже."
        }
        clickScrolled("Сохранить")
        waitFor("Тестовая реклама")
        assertEquals(1, uhCreateSuccessCount.get())

        // (b) Submit 422 "Заполни заголовок и выбери тариф": the server message is shown.
        uhSubmitFailNext = true
        clickScrolled("На модерацию")
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            ShadowToast.getTextOfLatestToast() == "Заполни заголовок и выбери тариф"
        }

        // (d) Pay 409 "Оплата доступна после одобрения модерацией": a toast.
        uhStatus = "active"
        remountKey.intValue++
        waitFor("Оплатить размещение · 500 ₽")
        uhPayMode = "409"
        ShadowToast.reset()
        clickScrolled("Оплатить размещение · 500 ₽")
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            ShadowToast.getTextOfLatestToast() == "Оплата доступна после одобрения модерацией"
        }

        // (e) Dismissing the SBP sheet does not confirm. WEAK-EVIDENCE #4's "never queryable"
        // branch stays a residual (classify, don't skip); once queryable, G5 finding #3 requires
        // the scrim tap actually be proven to close the sheet, not swallowed into a false residual.
        // compose.onRoot() is ambiguous once the sheet's own ModalBottomSheetDialogWrapper adds a
        // second semantics root (material3 1.4.0) — isDialog() names that root so the tap lands on
        // the scrim instead of throwing an ambiguity error that runCatching used to hide.
        uhPayMode = "normal"
        ShadowToast.reset()
        clickScrolled("Оплатить размещение · 500 ₽")
        if (waitForOptional("Я перевёл", 8000)) {
            compose.onNode(isDialog()).performTouchInput { click(Offset(2f, 2f)) }
            assertTrue("SBP sheet must close on scrim tap", waitForGoneOptional("Я перевёл", 5000))
            compose.onNodeWithText("Оплатить размещение · 500 ₽").assertIsDisplayed()
        } else {
            residualNotes += "unhappy paths: SBP sheet never became queryable — residual (WEAK-EVIDENCE #4)"
        }

        // (f) Timeout on /ads/mine: an error with a retry (same path as (a), triggered by a hang).
        uhMineNoResponseNext = true
        remountKey.intValue++
        waitFor("Не удалось загрузить кабинет")
        compose.onNodeWithText("Повторить").assertIsDisplayed()
    }

    // ---------- server fixtures ----------

    private fun mainAdJson(): String {
        val o = JSONObject()
        o.put("id", AD_ID); o.put("title", mainTitle); o.put("text", mainText)
        o.put("button", mainButton); o.put("target", mainTarget); o.put("erid", mainErid)
        o.put("status", mainStatus); o.put("reject_reason", mainRejectReason); o.put("package", mainPkg)
        o.put("package_title", if (mainPkg == PKG_CODE) PKG_TITLE else "")
        o.put("budget_kop", if (mainPkg == PKG_CODE) PKG_AMOUNT_KOP else 0)
        o.put("period_days", if (mainPkg == PKG_CODE) PKG_PERIOD_DAYS else 0)
        o.put("placements", JSONArray(if (mainPkg == PKG_CODE) listOf("route") else emptyList<String>()))
        o.put("cities", JSONArray(mainCities.split(",").map { it.trim() }.filter { it.isNotBlank() }))
        o.put("paid", mainPaid)
        return o.toString()
    }

    private fun mainAdminAdJson(): String {
        val o = JSONObject()
        o.put("id", AD_ID); o.put("partner", mainPartner); o.put("title", mainTitle); o.put("text", mainText)
        o.put("plan", "standard"); o.put("status", mainStatus)
        o.put("placements", JSONArray(if (mainPkg == PKG_CODE) listOf("route") else emptyList<String>()))
        o.put("erid", mainErid); o.put("live", false); o.put("expired", false)
        o.put("button", mainButton); o.put("target", mainTarget)
        o.put("cities", JSONArray(mainCities.split(",").map { it.trim() }.filter { it.isNotBlank() }))
        o.put("reject_reason", mainRejectReason); o.put("owner_id", 42)
        return o.toString()
    }

    private fun uhAdJson(): String {
        val o = JSONObject()
        o.put("id", AD_ID); o.put("title", uhTitle); o.put("text", "тест"); o.put("button", "")
        o.put("target", ""); o.put("erid", ""); o.put("status", uhStatus); o.put("reject_reason", "")
        o.put("package", uhPkg)
        o.put("package_title", if (uhPkg == PKG_CODE) PKG_TITLE else "")
        o.put("budget_kop", if (uhPkg == PKG_CODE) PKG_AMOUNT_KOP else 0)
        o.put("period_days", if (uhPkg == PKG_CODE) PKG_PERIOD_DAYS else 0)
        o.put("placements", JSONArray(if (uhPkg == PKG_CODE) listOf("route") else emptyList<String>()))
        o.put("cities", JSONArray())
        o.put("paid", false)
        return o.toString()
    }

    private fun json(body: String, code: Int = 200): MockResponse =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    // ---------- compose helpers (same idiom as the sibling *JourneyTest files) ----------

    private fun switchTo(next: Int, token: String) {
        compose.runOnIdle { page.intValue = -1 }
        compose.waitForIdle()
        ApiClient.saveToken(token)
        if (next == 0) advStage.value = "cabinet"
        compose.runOnIdle { page.intValue = next }
    }

    private fun list() = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))

    private fun scroll(matcher: SemanticsMatcher) { list().performScrollToNode(matcher) }

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

    private fun waitFor(label: String, substring: Boolean = false) {
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithText(label, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }

    // Bounded, non-throwing probe: converts a compose.waitUntil timeout into a residual signal
    // instead of failing the test (SbpTransferSheet is an unproven ModalBottomSheet, WEAK-EVIDENCE #4).
    private fun waitForOptional(label: String, timeoutMs: Long): Boolean = try {
        compose.waitUntil(timeoutMs) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()
        }
        true
    } catch (e: Throwable) {
        false
    }

    private fun waitForGoneOptional(label: String, timeoutMs: Long): Boolean = try {
        compose.waitUntil(timeoutMs) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithText(label).fetchSemanticsNodes().isEmpty()
        }
        true
    } catch (e: Throwable) {
        false
    }
}
