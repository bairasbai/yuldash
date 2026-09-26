package com.yuldash.app

// F-12 (PATH-12, administrator): admin decisions must actually reach the other side.
//
// Covers three real chains end to end, on real screens through MockWebServer:
//  1. D12-1: a support reply that fails on the server must keep the admin's typed text and say so
//     (regression for AdminSupportScreen.kt's AdminSupportThread/AdminSupportScreen split).
//  2. A courier application decision (reject with reason, resubmit, approve) must reach the real
//     applicant screen, through the real Settings -> "Кабинет админа" -> "Курьеры" path, and the
//     admin cabinet row must be absent for a non-admin.
//  3. A support ticket and a parcel dispute must both reach the user/reporter: reply+close visible
//     to the user, and a dispute resolution (with a 503-then-retry and a client-blocked conflict)
//     visible to the reporter.
//
// Unhappy paths (empty queue, 503 list with retry, cancelled dialog sends no POST, a thread-open
// failure) are covered by small dedicated tests below the three main ones.

import android.app.Application
import android.os.Looper
import android.view.ViewGroup
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.ui.theme.YuldashTheme
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdminDecisionJourneyTest {
    @get:Rule val compose = createComposeRule()

    private var server: MockWebServer? = null
    private val requests = CopyOnWriteArrayList<String>()
    private val page = mutableIntStateOf(0)
    private val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
    private lateinit var vm: YuldashViewModel
    private var courierOpened = false

    // ---- Support ticket state (shared shape for D12-1, the ticket+dispute test, and the small unhappy tests) ----
    @Volatile private var ticketId = 21
    @Volatile private var ticketUserName = "Марат"
    @Volatile private var ticketSubject = ""
    @Volatile private var ticketStatus = "open"
    private val ticketMessages = CopyOnWriteArrayList<JSONObject>()
    @Volatile private var replyShouldFail = false
    @Volatile private var closeShouldFail = false
    @Volatile private var listShouldFail = false
    @Volatile private var listEmpty = false
    @Volatile private var threadOpenShouldFail = false

    // ---- Courier application state ----
    @Volatile private var courierStatus = "pending"
    @Volatile private var courierRejectReason = ""

    // ---- Incident (dispute) state ----
    @Volatile private var incidentStatus = "under_review"
    @Volatile private var incidentResolution = ""
    @Volatile private var incidentFault = ""
    @Volatile private var incidentNote = ""
    @Volatile private var incidentResolveShouldFail = false

    private companion object {
        const val ADMIN_TOKEN = "local-admin-f12"
        const val APPLICANT_TOKEN = "local-applicant-f12"
        const val PASSENGER_TOKEN = "local-passenger-f12"
        const val USER_TOKEN = "local-user-f12"
    }

    @Before fun setup() {
        ApiClient.resetForTest()
        ApiClient.init(ApplicationProvider.getApplicationContext())
        ApiClient.testTimeoutMs = 2000
    }

    @After fun cleanup() {
        runCatching { compose.runOnIdle { page.intValue = -1 } }
        runCatching { compose.waitForIdle() }
        ApiClient.logout()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server?.shutdown()
        server = null
    }

    // ============================== 1. D12-1: reply failure keeps text ==============================

    @Test fun adminSupportReplyFailureKeepsTextAndTellsAdmin() {
        ticketMessages.add(msg(1, "user", "Здравствуйте, где мой перевод?"))
        startServer(::dispatchAll)
        ApiClient.saveToken(ADMIN_TOKEN)
        replyShouldFail = true
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                YuldashTheme { AdminSupportScreen(onBack = {}) }
            }
        }
        waitFor(ticketUserName)
        compose.onNodeWithText(ticketUserName).performClick()
        waitFor("Здравствуйте, где мой перевод?")

        val replyText = "Разбираемся, перевод уже в пути"
        compose.onNode(hasSetTextAction()).performTextReplacement(replyText)
        compose.onNodeWithText("Ответить").performClick()
        waitFor("Ответ не отправлен. Текст сохранён — проверь сеть и попробуй ещё раз.")
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            editableText() == replyText
        }
        assertEquals(1, requests.count { it == "POST /admin/support/tickets/$ticketId/reply" })

        // Retry: same text, this time the server accepts it and the field clears.
        compose.onNodeWithText("Ответить").performClick()
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            requests.count { it == "POST /admin/support/tickets/$ticketId/reply" } == 2
        }
        waitFor(replyText)
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            editableText() == ""
        }

        // Close: same failure-keeps-state, then success shape for the parent's onClose.
        closeShouldFail = true
        compose.onNodeWithText("Закрыть").performClick()
        waitFor("Не получилось закрыть обращение. Проверь сеть и попробуй ещё раз.")

        // A later successful reply must not leave that close error stuck on screen (G5 finding #7).
        compose.onNode(hasSetTextAction()).performTextReplacement("Ещё раз проверим и вернёмся")
        compose.onNodeWithText("Ответить").performClick()
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            editableText() == ""
        }
        compose.onAllNodesWithText("Не получилось закрыть обращение. Проверь сеть и попробуй ещё раз.")
            .assertCountEquals(0)

        compose.onNodeWithText("Закрыть").performClick()
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            ticketStatus == "closed"
        }
    }

    // ================== 2. Courier decision reaches the applicant, through the real cabinet ==================

    @Test fun adminCabinetCourierDecisionReachesApplicant() {
        startServer(::dispatchAll)
        vm = ViewModelProvider(owner, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                YuldashViewModel(SavedStateHandle(mapOf("yuldash_screen" to "Settings"))) as T
        })[YuldashViewModel::class.java]
        ApiClient.saveToken(ADMIN_TOKEN)
        compose.setContent {
            CompositionLocalProvider(
                LocalViewModelStoreOwner provides owner,
                LocalAppLanguage provides AppLanguage.Ru,
                LocalPoolingNativeMapEnabled provides false,
            ) {
                YuldashTheme {
                    key(page.intValue) {
                        when (page.intValue) {
                            0, 2 -> YuldashApp()
                            1 -> CourierOnboardingScreen(onBack = {}, onOpenCourier = { courierOpened = true })
                        }
                    }
                }
            }
        }

        // Admin: Settings -> "Кабинет админа" -> "Курьеры" -> reject with a reason.
        compose.waitUntil(15000) { Shadows.shadowOf(Looper.getMainLooper()).idle(); vm.isAdmin.value }
        compose.waitForIdle()
        scrollTo(hasText("Кабинет админа"))
        compose.onNodeWithText("Кабинет админа").performClick()
        waitFor("Единый центр управления Юлдашем. Виден только администратору.")
        compose.waitForIdle()
        scrollTo(hasText("Курьеры"))
        compose.onNodeWithText("Курьеры").performClick()
        waitFor("Тимур Валиев")
        compose.onNodeWithText("Отклонить").performClick()
        compose.onNode(hasText("Почему отклоняешь", substring = true) and hasSetTextAction())
            .performTextReplacement("Селфи не совпадает с документом")
        compose.onNodeWithText("Отклонить с причиной").performClick()
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            ShadowToast.getTextOfLatestToast() == "Заявка отклонена"
        }
        assertEquals(1, requests.count { it == "POST /admin/courier-applications/11/reject" })
        assertEquals("rejected", courierStatus)
        assertEquals("Селфи не совпадает с документом", courierRejectReason)

        // Applicant: sees the rejection and its reason, resubmits (selfie is reused, no picker needed).
        switchTo(1, APPLICANT_TOKEN)
        waitFor("Заявку пока отклонили")
        waitFor("Причина: Селфи не совпадает с документом")
        compose.onNodeWithText("Подать снова").performClick()
        // The Rejected screen's button and the Form's submit button share the same label, and the
        // Form's button sits far down its own LazyColumn (not composed until scrolled to) — so wait
        // for the Rejected screen alone to leave, then scroll the Form into view before clicking.
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithText("Заявку пока отклонили").fetchSemanticsNodes().isEmpty()
        }
        compose.waitForIdle()
        scrollTo(hasText("Подать снова"))
        compose.onNodeWithText("Подать снова").performClick()
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            requests.contains("POST /courier/apply")
        }
        assertEquals("pending", courierStatus)
        waitFor("Заявка на проверке")

        // Admin: approves the fresh pending application (vm.screen already points at AdminCourier).
        switchTo(0, ADMIN_TOKEN)
        waitFor("Тимур Валиев")
        compose.onNodeWithText("Одобрить").performClick()
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            ShadowToast.getTextOfLatestToast() == "Курьер одобрен"
        }
        assertEquals("approved", courierStatus)

        // Applicant: sees the approval and opens courier mode.
        switchTo(1, APPLICANT_TOKEN)
        waitFor("Поздравляем — ты курьер Юлдаша!")
        compose.onNodeWithText("В режим курьера").performClick()
        compose.waitForIdle()
        assertTrue(courierOpened)

        // Wrong participant: a passenger's Settings has no "Кабинет админа" row.
        val meCountBefore = requests.count { it == "GET /me" }
        compose.runOnIdle { page.intValue = -1 }
        compose.waitForIdle()
        ApiClient.saveToken(PASSENGER_TOKEN)
        compose.runOnIdle {
            vm.isAdmin.value = false
            vm.screen.value = Screen.Settings
            page.intValue = 2
        }
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            requests.count { it == "GET /me" } > meCountBefore
        }
        compose.waitForIdle()
        compose.onAllNodesWithText("Кабинет админа").assertCountEquals(0)
    }

    // ============ 3. Support ticket + dispute resolution both reach the user/reporter ============

    @Test fun supportTicketAndDisputeResolutionReachUser() {
        ticketId = 31
        ticketUserName = "Алсу"
        startServer(::dispatchAll)

        ApiClient.saveToken(USER_TOKEN)
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                YuldashTheme {
                    key(page.intValue) {
                        when (page.intValue) {
                            0 -> SupportTicketsScreen(onBack = {}, onOpenTicket = {})
                            1 -> AdminSupportScreen(onBack = {})
                            2 -> SupportTicketScreen(ticketId = ticketId, onBack = {})
                            3 -> AdminIncidentsScreen(onBack = {})
                            4 -> {
                                var openId by remember { mutableStateOf<Int?>(null) }
                                val id = openId
                                if (id == null) FairnessCenterScreen(onBack = {}, onOpenIncident = { openId = it })
                                else IncidentDetailScreen(incidentId = id, onBack = { openId = null })
                            }
                        }
                    }
                }
            }
        }

        // (a) User creates a ticket.
        // The always-on hero card and the empty-list nudge both carry the exact label "Новое
        // обращение" (SupportChatScreen.kt:159, :188 — same onNew callback, intentional double
        // affordance), so a plain text match is ambiguous; take the first as scrollTo() already does.
        waitFor("Новое обращение")
        compose.onAllNodesWithText("Новое обращение").onFirst().performClick()
        waitFor("Сообщение")
        compose.onNode(hasText("Сообщение") and hasSetTextAction())
            .performTextReplacement("Курьер не позвонил и оставил посылку у соседей")
        compose.onNodeWithText("Отправить").performClick()
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            requests.contains("POST /support/tickets")
        }

        // (b) Admin reads it, replies, then closes.
        switchTo(1, ADMIN_TOKEN)
        waitFor(ticketUserName)
        compose.onNodeWithText(ticketUserName).performClick()
        // The queue row previews the ticket's own last message (AdminSupportScreen.kt:235), so waiting
        // for that text can return before the thread itself has loaded. Wait for the reply field - it
        // exists only once the thread is composed - then confirm the right ticket opened.
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
        }
        waitFor("Курьер не позвонил и оставил посылку у соседей")
        compose.onNode(hasSetTextAction()).performTextReplacement("Уже разбираемся, свяжемся с курьером")
        compose.onNodeWithText("Ответить").performClick()
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            requests.contains("POST /admin/support/tickets/$ticketId/reply")
        }
        waitFor("Уже разбираемся, свяжемся с курьером")
        // That text can also be the reply field's own still-populated content while the POST is in
        // flight, so "Закрыть" could be tapped while busy and dropped. Wait for the field to clear -
        // the same signal :149-152 use - before it.
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            editableText() == ""
        }
        compose.onNodeWithText("Закрыть").performClick()
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            ticketStatus == "closed"
        }

        // (c) User sees the admin's reply and the closed state.
        switchTo(2, USER_TOKEN)
        waitFor("Уже разбираемся, свяжемся с курьером")
        waitFor("Обращение закрыто. Напиши — и оно снова откроется.")

        // (d) Dispute: admin resolves incident #5, first call fails, retry resolves it.
        switchTo(3, ADMIN_TOKEN)
        waitFor("Посылку повредили")
        incidentResolveShouldFail = true
        openDialogWithTextField { compose.onNodeWithText("Принять решение").performClick() }
        waitFor("Решение по спору #5")
        compose.onNodeWithText("Предупреждение").performClick()
        // Fault defaults to "Вторая сторона" (AdminIncidentsScreen.kt:459) - pick a different one so the
        // POST body assertion below actually proves the choice reaches the server, not just the default.
        compose.onNodeWithText("Оба").performClick()
        val decisionNote = "Проверили обе стороны: курьер оставил без звонка, извинились и выдали бонус"
        compose.onNode(hasText("Объяснение для обеих сторон") and hasSetTextAction())
            .performTextReplacement(decisionNote)
        compose.onNodeWithText("Сохранить решение").performClick()
        // The dialog's own errFallback (AdminIncidentsScreen.kt:465): a real HTTP 503 with a body
        // would surface the server's text instead (ApiClient.errorMessage), so the stand drops the
        // connection (SocketPolicy.NO_RESPONSE) to reproduce the roadmap's exact fallback wording.
        waitFor("Не получилось сохранить решение. Проверь сеть.")
        compose.onNodeWithText("Сохранить решение").performClick()
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            incidentStatus == "resolved"
        }
        assertEquals(2, requests.count { it == "POST /admin/incidents/5/resolve" })
        assertEquals("warning", incidentResolution)
        assertEquals("both", incidentFault)

        // (e) A reporter-at-fault + punishment combo stays blocked on the client (no extra POST).
        compose.onNodeWithText("Решённые").performClick()
        waitFor("Пересмотреть решение")
        val resolvedPostCount = requests.count { it == "POST /admin/incidents/5/resolve" }
        openDialogWithTextField { compose.onNodeWithText("Пересмотреть решение").performClick() }
        waitFor("Решение по спору #5")
        compose.onNodeWithText("Страйк").performClick()
        compose.onNodeWithText("Заявитель").performClick()
        waitFor("Вина на заявителе", substring = true)
        compose.onNodeWithText("Сохранить решение").assertIsNotEnabled()
        compose.onNodeWithText("Отмена").performClick()
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithText("Решение по спору #5").fetchSemanticsNodes().isEmpty()
        }
        assertEquals(resolvedPostCount, requests.count { it == "POST /admin/incidents/5/resolve" })

        // (f) Reporter: FairnessCenterScreen -> IncidentDetailScreen shows the decision text.
        switchTo(4, USER_TOKEN)
        waitFor("Посылку повредили")
        compose.onNodeWithText("Посылку повредили").performClick()
        waitFor(decisionNote)
    }

    // ============================== Unhappy paths ==============================

    @Test fun emptySupportQueueShowsFriendlyEmptyState() {
        listEmpty = true
        startServer(::dispatchAll)
        ApiClient.saveToken(ADMIN_TOKEN)
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                YuldashTheme { AdminSupportScreen(onBack = {}) }
            }
        }
        waitFor("Обращений нет")
    }

    @Test fun adminSupportListFailureShowsRetryThenLoads() {
        ticketUserName = "Ринат"
        listShouldFail = true
        startServer(::dispatchAll)
        ApiClient.saveToken(ADMIN_TOKEN)
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                YuldashTheme { AdminSupportScreen(onBack = {}) }
            }
        }
        waitFor("Не удалось загрузить. Проверь сеть.")
        compose.onNodeWithText("Повторить").performClick()
        waitFor("Ринат")
    }

    @Test fun cancellingResolveDialogSendsNoPost() {
        startServer(::dispatchAll)
        ApiClient.saveToken(ADMIN_TOKEN)
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                YuldashTheme { AdminIncidentsScreen(onBack = {}) }
            }
        }
        waitFor("Посылку повредили")
        openDialogWithTextField { compose.onNodeWithText("Принять решение").performClick() }
        waitFor("Решение по спору #5")
        compose.onNodeWithText("Отмена").performClick()
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithText("Решение по спору #5").fetchSemanticsNodes().isEmpty()
        }
        assertTrue(requests.none { it.startsWith("POST /admin/incidents") })
    }

    @Test fun openingThreadFailureLeavesQueueUsable() {
        ticketUserName = "Ильнар"
        threadOpenShouldFail = true
        startServer(::dispatchAll)
        ApiClient.saveToken(ADMIN_TOKEN)
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                YuldashTheme { AdminSupportScreen(onBack = {}) }
            }
        }
        waitFor(ticketUserName)
        compose.onNodeWithText(ticketUserName).performClick()
        // Waiting for the GET to be merely recorded only proves it was sent, not that the 503 was
        // processed (G5 finding #3). Wait for the admin's actual feedback instead - previously there
        // was none at all (a silent failure); a retry-worthy toast now fires from onOpen's onFailure.
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            ShadowToast.getTextOfLatestToast() != null
        }
        assertEquals("Не удалось загрузить. Проверь сеть.", ShadowToast.getTextOfLatestToast())
        // The queue stays usable: nothing crashes, the row is still there to tap again.
        compose.onNodeWithText("Обращения").assertExists()
        compose.onNodeWithText(ticketUserName).assertExists()
    }

    // ============================== Shared server plumbing ==============================

    private fun dispatchAll(r: RecordedRequest): MockResponse {
        val path = r.requestUrl!!.encodedPath
        val method = r.method
        return when {
            path == "/me" -> json("""{"role":"${roleFor(r)}"}""")
            path == "/me/standing" -> json(
                """{"standing":"ok","strikes":0,"warnings":0,"reliability":92,"suspended_until":null,""" +
                    """"suspend_reason":"","rating_shield":false,"active_incidents":1,"can_act":true}"""
            )
            path == "/safety/policy" -> json(
                """{"strikes_to_limit":2,"strikes_to_suspend":3,"suspend_1_days":3,"suspend_2_days":7,""" +
                    """"suspend_3_days":30,"strike_decay_days":180}"""
            )

            path == "/admin/support/tickets" && method == "GET" -> when {
                listShouldFail -> { listShouldFail = false; json("""{"detail":"Сервис недоступен"}""", 503) }
                listEmpty -> json("""{"items":[]}""")
                else -> json("""{"items":[${adminTicketRowJson()}]}""")
            }
            path == "/admin/support/tickets/$ticketId" && method == "GET" -> when {
                threadOpenShouldFail -> { threadOpenShouldFail = false; json("""{"detail":"Сервис недоступен"}""", 503) }
                else -> json(ticketJson())
            }
            path == "/admin/support/tickets/$ticketId/reply" && method == "POST" -> when {
                replyShouldFail -> { replyShouldFail = false; json("""{"detail":"Сервис недоступен"}""", 503) }
                else -> {
                    val body = JSONObject(r.body.readUtf8()).optString("body")
                    ticketMessages.add(msg(ticketMessages.size + 1, "admin", body))
                    json(ticketJson())
                }
            }
            path == "/admin/support/tickets/$ticketId/close" && method == "POST" -> when {
                closeShouldFail -> { closeShouldFail = false; json("""{"detail":"Сервис недоступен"}""", 503) }
                else -> { ticketStatus = "closed"; json(ticketJson()) }
            }
            path == "/support/tickets" && method == "GET" -> json("""{"unread":0,"items":[]}""")
            path == "/support/tickets" && method == "POST" -> {
                val b = JSONObject(r.body.readUtf8())
                ticketSubject = b.optString("subject")
                ticketMessages.add(msg(1, "user", b.optString("body")))
                json(ticketJson())
            }
            path == "/support/tickets/$ticketId" && method == "GET" -> json(ticketJson())

            path == "/admin/courier-applications" && method == "GET" -> json("""{"items":[${courierAppJson()}]}""")
            path == "/admin/courier-applications/11/reject" && method == "POST" -> {
                courierStatus = "rejected"
                courierRejectReason = JSONObject(r.body.readUtf8()).optString("reason")
                json("{}")
            }
            path == "/admin/courier-applications/11/approve" && method == "POST" -> {
                courierStatus = "approved"
                json("{}")
            }
            path == "/courier/application" && method == "GET" -> json("""{"application": ${courierAppJson()}}""")
            path == "/courier/apply" && method == "POST" -> {
                courierStatus = "pending"; courierRejectReason = ""
                json(courierAppJson())
            }

            path == "/admin/incidents" && method == "GET" -> {
                val status = r.requestUrl!!.queryParameter("status")
                if (status == incidentStatus) json("""{"items":[${incidentJson()}]}""") else json("""{"items":[]}""")
            }
            path == "/admin/incidents/5/resolve" && method == "POST" -> when {
                incidentResolveShouldFail -> {
                    incidentResolveShouldFail = false
                    // A dropped connection (not a well-formed HTTP error) so the dialog's local
                    // errFallback path runs (see the test's note above).
                    MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                }
                else -> {
                    val b = JSONObject(r.body.readUtf8())
                    incidentResolution = b.optString("resolution")
                    incidentFault = b.optString("fault")
                    incidentNote = b.optString("note")
                    incidentStatus = "resolved"
                    json(incidentJson())
                }
            }
            path == "/incidents/mine" && method == "GET" -> json("""{"items":[${incidentJson()}]}""")
            path == "/incidents/5" && method == "GET" -> json(incidentJson())

            else -> json("{}", 404)
        }
    }

    private fun roleFor(r: RecordedRequest): String = when (r.getHeader("Authorization")) {
        "Bearer $ADMIN_TOKEN" -> "admin"
        "Bearer $PASSENGER_TOKEN" -> "passenger"
        else -> "user"
    }

    private fun ticketJson(): String = JSONObject()
        .put("id", ticketId).put("subject", ticketSubject)
        .put("status", ticketStatus)
        .put("created_at", "2026-09-24T09:00:00Z").put("updated_at", "2026-09-24T09:00:00Z")
        .put("messages", JSONArray(ticketMessages))
        .toString()

    private fun adminTicketRowJson(): String = JSONObject()
        .put("id", ticketId).put("user_id", 5).put("user_name", ticketUserName)
        .put("subject", ticketSubject)
        .put("status", ticketStatus)
        .put("last_message", ticketMessages.lastOrNull()?.optString("body") ?: "")
        .put("last_sender", ticketMessages.lastOrNull()?.optString("sender") ?: "user")
        .put("message_count", ticketMessages.size)
        .put("created_at", "2026-09-24T09:00:00Z").put("updated_at", "2026-09-24T09:00:00Z")
        .toString()

    private fun msg(id: Int, sender: String, body: String): JSONObject = JSONObject()
        .put("id", id).put("sender", sender).put("body", body).put("created_at", "2026-09-24T09:05:00Z")

    private fun courierAppJson(): String = JSONObject()
        .put("id", 11).put("transport", "car").put("status", courierStatus)
        .put("selfie_url", "https://cdn.example.com/selfie-11.jpg")
        .put("full_name", "Тимур Валиев").put("car_plate", "Х123УХ102").put("rules_accepted", true)
        .put("invited_by", JSONObject.NULL)
        .put("reject_reason", courierRejectReason)
        .put("created_at", "2026-09-20T08:00:00Z")
        .put("reviewed_at", JSONObject.NULL)
        .put("user_id", 77).put("name", "Тимур Валиев").put("phone", "+79990000002")
        .toString()

    private fun incidentJson(): String = JSONObject()
        .put("id", 5).put("booking_id", JSONObject.NULL)
        .put("type", "parcel_damage").put("severe", false)
        .put("status", incidentStatus)
        .put("reporter_role", "passenger")
        .put("description", "Пришло вскрытым, не хватает наушников")
        .put("respondent_statement", "")
        .put("responded_at", JSONObject.NULL)
        .put("resolution", incidentResolution)
        .put("fault", incidentFault)
        .put("resolution_note", incidentNote)
        .put("compensation_kop", 0)
        .put("appeal_text", "").put("appeal_status", "")
        .put("created_at", "2026-09-23T12:00:00Z").put("updated_at", "2026-09-23T12:00:00Z")
        .put("resolved_at", if (incidentStatus == "resolved") "2026-09-24T09:10:00Z" else JSONObject.NULL)
        .put("my_role", "reporter").put("other_name", "Динара (курьер)")
        .put("evidence_urls", JSONArray()).put("respondent_evidence_urls", JSONArray())
        .put("booking_route", "Уфа → Стерлитамак")
        .put("reporter_id", 41).put("reporter_name", "Алсу").put("reporter_phone", "+79990000041")
        .put("respondent_id", 42).put("respondent_name", "Динара (курьер)").put("respondent_phone", "+79990000042")
        .toString()

    private fun json(body: String, code: Int = 200): MockResponse =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun startServer(dispatch: (RecordedRequest) -> MockResponse) {
        val s = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests.add("${request.method} ${request.requestUrl!!.encodedPath}")
                    return dispatch(request)
                }
            }
            start()
        }
        server = s
        ApiClient.testBaseUrl = s.url("/").toString().trimEnd('/')
    }

    private fun switchTo(next: Int, token: String) {
        compose.runOnIdle { page.intValue = -1 }
        compose.waitForIdle()
        ApiClient.saveToken(token)
        compose.runOnIdle { page.intValue = next }
    }

    private fun scrollTo(matcher: SemanticsMatcher) {
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(matcher)
    }

    private fun editableText(): String =
        compose.onNode(hasSetTextAction()).fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private fun waitFor(text: String, substring: Boolean = false) {
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Robolectric-only: a platform-width (WRAP_CONTENT) dialog holding a text field re-measures
     * forever here, so Compose never idles. The freshly shown window is widened to MATCH_PARENT
     * before the first idle wait (same seam as ParcelCreationDeliveryJourneyTest).
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
}
