package com.yuldash.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.ViewGroup
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * PATH-13, close person (trusted contact) — B06/B08.
 *
 * Что было. Добавленный близкий получал id=0 на экране: `ApiClient.addContact` звал сервер,
 * но выбрасывал созданный id (`Result<Unit>`). Пока сессия жила, «Поделиться поездкой»
 * отправляло серверу `contact_id: 0` — тот отвечал 404 «Контакт не найден» (D13-1). Тот же
 * id-0 объект не давал убрать контакт с сервера при удалении в той же сессии — SMS и SOS
 * продолжали бы идти человеку, которого «убрали» только на экране (D13-2). Третий слой:
 * экран доверенных контактов держал свой список слияния (`merged`) на ссылке родительского
 * списка, а не на его содержимом — починка id родителя до этого экрана не долетала (D13-3).
 *
 * Тесты держат реальный `YuldashApp`/`YuldashViewModel` и настоящий MockWebServer — никаких
 * моков системы под тестом.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TrustedContactShareJourneyTest {
    @get:Rule val compose = createComposeRule()
    private val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
    private val mounted = mutableStateOf(true)
    private lateinit var vm: YuldashViewModel
    private lateinit var server: MockWebServer

    // ---- request/response tracking ----
    private val requests = CopyOnWriteArrayList<String>()
    private val addBodies = CopyOnWriteArrayList<String>()
    private val deleteRequests = CopyOnWriteArrayList<Int>()
    private val shareBodies = CopyOnWriteArrayList<String>()
    private val revokeRequests = CopyOnWriteArrayList<String>()
    private val contactsSeed = CopyOnWriteArrayList<JSONObject>()
    private val sharesByBooking = java.util.concurrent.ConcurrentHashMap<Int, MutableList<Triple<Int, Int, String>>>()
    private val sharesByOrder = java.util.concurrent.ConcurrentHashMap<Int, MutableList<Triple<Int, Int, String>>>()

    @Volatile private var nextContactId = 55
    @Volatile private var nextShareId = 900
    @Volatile private var addContactStatus = 200
    @Volatile private var addLatch: CountDownLatch? = null
    @Volatile private var deleteContactStatus = 200
    @Volatile private var deleteContactDrop = false
    @Volatile private var addContactReturnsNoId = false
    @Volatile private var shareStatusCode = 200
    @Volatile private var shareDetail: String? = null
    @Volatile private var revokeStatusCode = 200
    @Volatile private var sharesGetStatusCode = 200
    @Volatile private var instantShareStatusCode = 200
    @Volatile private var instantRevokeStatusCode = 200
    @Volatile private var instantOrderStatus = "searching"
    private val instantCreates = CopyOnWriteArrayList<String>()
    private val instantEstimates = CopyOnWriteArrayList<String>()

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("yuldash_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        ApiClient.resetForTest()
        ApiClient.init(context)
        ApiClient.saveToken("local-passenger")
        LocationPrefs.lastLat = 54.735
        LocationPrefs.lastLng = 55.958
        LocationPrefs.sharingEnabled = false
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    requests.add("${request.method} $path")

                    // ---- trusted contacts ----
                    if (path == "/trusted-contacts" && request.method == "GET") {
                        val items = contactsSeed.joinToString(",") { it.toString() }
                        return json("""{"items":[$items]}""")
                    }
                    if (path == "/trusted-contacts" && request.method == "POST") {
                        val body = request.body.readUtf8()
                        addBodies.add(body)
                        addLatch?.await(10, TimeUnit.SECONDS)
                        if (addContactStatus != 200) return json("""{"detail":"Тестовый отказ добавления"}""", addContactStatus)
                        val obj = JSONObject(body)
                        // Old-server contract check: no created id in the response at all.
                        val id = if (addContactReturnsNoId) 0 else nextContactId++
                        return json(
                            JSONObject()
                                .put("id", id)
                                .put("name", obj.optString("name"))
                                .put("relation", obj.optString("relation"))
                                .put("phone", obj.optString("phone"))
                                .put("notify_by_default", obj.optBoolean("notify_by_default"))
                                .toString()
                        )
                    }
                    val delContact = Regex("^/trusted-contacts/(\\d+)$").matchEntire(path)
                    if (delContact != null && request.method == "DELETE") {
                        val id = delContact.groupValues[1].toInt()
                        deleteRequests.add(id)
                        // DISCONNECT_AT_START is meant to fire before the request is read; this
                        // Dispatcher only gets to answer AFTER that point, so it never actually
                        // triggers here (verified: the client sees a false 200, not a failure).
                        // DISCONNECT_AFTER_REQUEST is the policy meant for exactly this timing.
                        if (deleteContactDrop) return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                        if (deleteContactStatus != 200) return json("{}", deleteContactStatus)
                        return json("{}")
                    }

                    // ---- rideshare booking (ActiveTripScreen) ----
                    if (Regex("^/bookings/(\\d+)/role$").matches(path)) return json("""{"role":"passenger","status":"confirmed"}""")
                    if (Regex("^/bookings/(\\d+)/messages$").matches(path)) return json("""{"items":[]}""")
                    if (Regex("^/bookings/(\\d+)/boarding-code$").matches(path)) return json("""{"code":"1234"}""")
                    if (Regex("^/bookings/(\\d+)/details$").matches(path)) {
                        return json("""{"booking_id":42,"ride_id":7,"role":"passenger","status":"confirmed","from_city":"Пункт А","to_city":"Пункт Б","contact_unlocked":false}""")
                    }
                    val sharesGet = Regex("^/bookings/(\\d+)/shares$").matchEntire(path)
                    if (sharesGet != null && request.method == "GET") {
                        if (sharesGetStatusCode != 200) return json("{}", sharesGetStatusCode)
                        val bid = sharesGet.groupValues[1].toInt()
                        val items = sharesByBooking[bid].orEmpty().joinToString(",") { (id, cid, tok) ->
                            """{"id":$id,"contact_id":$cid,"token":"$tok"}"""
                        }
                        return json("""{"items":[$items]}""")
                    }
                    val shareMatch = Regex("^/bookings/(\\d+)/share$").matchEntire(path)
                    if (shareMatch != null && request.method == "POST") {
                        val bid = shareMatch.groupValues[1].toInt()
                        val body = request.body.readUtf8()
                        shareBodies.add(body)
                        if (shareStatusCode != 200) {
                            val detail = shareDetail
                            return if (detail != null) json("""{"detail":"$detail"}""", shareStatusCode) else json("{}", shareStatusCode)
                        }
                        val cid = JSONObject(body).optInt("contact_id")
                        val sid = nextShareId++
                        val token = "tok$sid"
                        sharesByBooking.getOrPut(bid) { mutableListOf() }.add(Triple(sid, cid, token))
                        return json("""{"id":$sid,"contact_id":$cid,"token":"$token"}""")
                    }
                    val revokeMatch = Regex("^/bookings/(\\d+)/share/(\\d+)$").matchEntire(path)
                    if (revokeMatch != null && request.method == "DELETE") {
                        val bid = revokeMatch.groupValues[1].toInt()
                        val sid = revokeMatch.groupValues[2].toInt()
                        revokeRequests.add("$bid:$sid")
                        if (revokeStatusCode != 200) return json("{}", revokeStatusCode)
                        sharesByBooking[bid]?.removeAll { it.first == sid }
                        return json("{}")
                    }

                    // ---- taxi order (InstantOrderScreen), positive control ----
                    if (path == "/instant/availability") return json("""{"enabled":true}""")
                    if (path == "/instant/orders/mine") return json("""{"items":[]}""")
                    if (path == "/places/saved") return json("""{"items":[{"id":10,"kind":"home","label":"Дом","address":"Уфа, Ленина 10","lat":54.751,"lng":56.001}]}""")
                    if (path == "/places/recent") return json("""{"items":[]}""")
                    if (path == "/instant/nearby-drivers") return json("""{"drivers":[]}""")
                    if (path == "/instant/estimate") {
                        instantEstimates.add(request.body.readUtf8())
                        return json("""{"price":250,"distance_km":4.0,"eta_min":12.0,"zone":"city","category":"standard","tariff_id":1,"options":[{"category":"standard","price":250,"open":true}]}""")
                    }
                    if (path == "/instant/orders" && request.method == "POST") {
                        instantCreates.add(request.body.readUtf8())
                        return json(instantOrder(), 201)
                    }
                    if (path == "/instant/orders/71") return json(instantOrder())
                    if (path == "/places/saved/10/used") return json("{}")
                    val instantSharesGet = Regex("^/instant/orders/(\\d+)/shares$").matchEntire(path)
                    if (instantSharesGet != null && request.method == "GET") {
                        val oid = instantSharesGet.groupValues[1].toInt()
                        val items = sharesByOrder[oid].orEmpty().joinToString(",") { (id, cid, tok) ->
                            """{"id":$id,"contact_id":$cid,"token":"$tok"}"""
                        }
                        return json("""{"items":[$items]}""")
                    }
                    val instantShareMatch = Regex("^/instant/orders/(\\d+)/share$").matchEntire(path)
                    if (instantShareMatch != null && request.method == "POST") {
                        val oid = instantShareMatch.groupValues[1].toInt()
                        if (instantShareStatusCode != 200) return json("{}", instantShareStatusCode)
                        val cid = JSONObject(request.body.readUtf8()).optInt("contact_id")
                        val sid = nextShareId++
                        val token = "tok$sid"
                        sharesByOrder.getOrPut(oid) { mutableListOf() }.add(Triple(sid, cid, token))
                        return json("""{"id":$sid,"contact_id":$cid,"token":"$token"}""")
                    }
                    val instantRevokeMatch = Regex("^/instant/orders/(\\d+)/share/(\\d+)$").matchEntire(path)
                    if (instantRevokeMatch != null && request.method == "DELETE") {
                        val oid = instantRevokeMatch.groupValues[1].toInt()
                        val sid = instantRevokeMatch.groupValues[2].toInt()
                        if (instantRevokeStatusCode != 200) return json("{}", instantRevokeStatusCode)
                        sharesByOrder[oid]?.removeAll { it.first == sid }
                        return json("{}")
                    }

                    return json("{}", 404)
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 1000
        vm = ViewModelProvider(owner, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
                YuldashViewModel(SavedStateHandle(mapOf("yuldash_screen" to "Home", "yuldash_tab" to "Map"))) as T
        })[YuldashViewModel::class.java]
    }

    @After fun cleanup() {
        compose.runOnIdle { mounted.value = false }
        owner.viewModelStore.clear()
        LocationPrefs.lastLat = null
        LocationPrefs.lastLng = null
        LocationPrefs.sharingEnabled = false
        ApiClient.logout()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server.shutdown()
    }

    // ============================================================
    // D13-1 — a contact added this session must share with its real server id.
    // ============================================================
    @Test fun newContactSharesTripWithServerId() {
        mountApp()
        compose.runOnIdle { vm.screen.value = Screen.TrustedContacts }
        waitFor("Добавить контакт")
        addContact("Айгуль", "Сестра", "+7 927 111-22-33")
        waitForToast("Контакт добавлен")

        // Production transition (YuldashApp.kt:1489/:1494) — no remount, same app instance (REPAIR-2 B1).
        compose.runOnIdle {
            vm.activeBookingId.value = 42
            vm.activeTrip.value = null
            vm.screen.value = Screen.ActiveTrip
        }
        scrollToAndClick("Поделиться поездкой с близким")
        waitFor("Айгуль")
        compose.onNodeWithText("Айгуль").performClick()
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            shareBodies.isNotEmpty()
        }
        assertEquals("share must carry the server id, not the optimistic 0", 55, JSONObject(shareBodies.single()).getInt("contact_id"))

        // Anti-masking: no GET /trusted-contacts between the add and the share (id came from the POST response).
        val addIdx = requests.indexOfFirst { it == "POST /trusted-contacts" }
        val shareIdx = requests.indexOfFirst { it == "POST /bookings/42/share" }
        assertTrue("add must precede share", addIdx in 0 until shareIdx)
        val getsBetween = requests.subList(addIdx + 1, shareIdx).count { it == "GET /trusted-contacts" }
        assertEquals(0, getsBetween)

        waitFor("Ссылка для близкого")
        compose.onNodeWithText("Отправить").performClick()
        val app = ApplicationProvider.getApplicationContext<Application>()
        val started = Shadows.shadowOf(app).nextStartedActivity
        assertNotNull("share must launch a chooser", started)
        val inner = started.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        assertNotNull("chooser must wrap the send intent", inner)
        val text = inner!!.getStringExtra(Intent.EXTRA_TEXT)
        // F-13 G5 finding 5: contains("/t/") would also accept a garbled or wrong-token link.
        // Assert the exact text, built the same way the product does (BASE + "/t/" + token).
        val expectedLink = server.url("/").toString().trimEnd('/') + "/t/tok900"
        assertEquals(
            "EXTRA_TEXT must carry exactly this share's live link",
            "Следи за моей поездкой в Юлдаш: $expectedLink", text
        )

        compose.onNodeWithText("Отозвать").performClick()
        waitForToast("Ссылка отозвана")
        waitFor("Кому отправить поездку")
        // F-13 G5 finding 5: isNotEmpty() would also accept a revoke sent for the wrong
        // booking/share. Assert the exact "bid:sid" pair this revoke must carry.
        assertEquals(listOf("42:900"), revokeRequests)
    }

    // ============================================================
    // D13-2 — deleting a contact added this session must reach the server.
    // ============================================================
    @Test fun newContactDeletionReachesServer() {
        mountApp()
        compose.runOnIdle { vm.screen.value = Screen.TrustedContacts }
        waitFor("Добавить контакт")

        // Case a: the add already returned before the delete dialog opens.
        addContact("Айгуль", "Сестра", "+7 927 111-22-33")
        waitForToast("Контакт добавлен")
        compose.onNodeWithContentDescription("Убрать контакт").performClick()
        waitFor("Убрать Айгуль?")
        compose.onNodeWithText("Убрать").performClick()
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            deleteRequests.contains(55)
        }
        waitFor("Пока нет контактов")

        // Case b: the delete dialog opens on the still-optimistic id-0 row (REPAIR-2 N1 deterministic hold).
        ApiClient.testTimeoutMs = 15000
        addLatch = CountDownLatch(1)
        addContact("Рустам", "Брат", "+7 927 444-55-66")
        waitFor("Рустам")
        compose.onNodeWithContentDescription("Убрать контакт").performClick()
        waitFor("Убрать Рустам?")
        // F-13 G5 finding 1: case a already left this exact toast text as "latest" (:306 above;
        // a successful delete shows none), so a plain wait here would be vacuous — it could pass
        // before the id reconciliation (YuldashApp.kt:1549-1551) has even run, letting the
        // confirm below race the still-optimistic id 0. Reset first (before releasing the held
        // POST) so the wait can only be satisfied by the real, new toast onAddContact shows AFTER
        // the id update (same statement block, no suspension point between them).
        ShadowToast.reset()
        assertNull("must be genuinely cleared, not stale from case a", ShadowToast.getTextOfLatestToast())
        addLatch?.countDown()
        waitForToast("Контакт добавлен")
        ApiClient.testTimeoutMs = 1000
        compose.onNodeWithText("Убрать").performClick()
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            deleteRequests.contains(56)
        }
        waitFor("Пока нет контактов")

        // Unhappy: a real network failure (connection drop) on delete must restore the row with
        // the honest toast. Same staleness risk as case b above (finding 1): case b's own toast
        // is still "latest" here, so reset before waiting.
        ShadowToast.reset()
        addContact("Динара", "Мама", "+7 927 777-88-99")
        waitForToast("Контакт добавлен")
        deleteContactDrop = true
        compose.onNodeWithContentDescription("Убрать контакт").performClick()
        waitFor("Убрать Динара?")
        compose.onNodeWithText("Убрать").performClick()
        waitForToast("Не получилось убрать. Проверь сеть и повтори.")
        waitFor("Динара")

        // Clean up before the next scenario: only one contact must be visible at a time, or the
        // generic "Убрать контакт" content description below becomes ambiguous.
        deleteContactDrop = false
        compose.onNodeWithContentDescription("Убрать контакт").performClick()
        waitFor("Убрать Динара?")
        compose.onNodeWithText("Убрать").performClick()
        waitFor("Пока нет контактов")

        // F-13 G5 finding 2: the roadmap named this scenario "DELETE returns 503", but the
        // shipped test above only drove a hard connection drop (no HTTP response at all), which
        // hits ApiClient's generic IOException fallback — deleteContactStatus (:74) was declared
        // but never actually set, so a real status-code failure was never exercised. A genuine
        // 503 carries a body, so serverSaid (UiKit.kt:573-574) shows the by-status text
        // (ApiClient.kt:134) instead of the roadmap's assumed toast — recorded here as the real,
        // tested behaviour (spec deviation), not a defect.
        addContact("Заринэ", "Тётя", "+7 927 888-99-00")
        waitForToast("Контакт добавлен")
        deleteContactStatus = 503
        compose.onNodeWithContentDescription("Убрать контакт").performClick()
        waitFor("Убрать Заринэ?")
        compose.onNodeWithText("Убрать").performClick()
        waitForToast("Ошибка сервера. Попробуй позже.")
        waitFor("Заринэ")
    }

    // ============================================================
    // onAddContact fallback: an old-server response with no created id must trigger a
    // fresh GET to reconcile, not leave the contact stuck at id=0.
    // ============================================================
    @Test fun addContactRefetchesWhenServerOmitsCreatedId() {
        mountApp()
        compose.runOnIdle { vm.screen.value = Screen.TrustedContacts }
        waitFor("Добавить контакт")
        addContactReturnsNoId = true
        contactsSeed.add(
            JSONObject().put("id", 90).put("name", "Ляйсан").put("relation", "Соседка")
                .put("phone", "+7 927 600-00-01").put("notify_by_default", true)
        )
        addContact("Ляйсан", "Соседка", "+7 927 600-00-01")
        waitForToast("Контакт добавлен")
        addContactReturnsNoId = false
        // dto.id was 0 -> onAddContact's else-branch refetches GET /trusted-contacts, which now
        // returns the real id; the delete below must reach the server with THAT id, not 0.
        waitFor("Ляйсан")
        compose.onNodeWithContentDescription("Убрать контакт").performClick()
        waitFor("Убрать Ляйсан?")
        compose.onNodeWithText("Убрать").performClick()
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            deleteRequests.contains(90)
        }
        waitFor("Пока нет контактов")
    }

    // ============================================================
    // Delete-confirm id resolution: fallback through serverContacts, and the documented
    // local-only-removal default when the phone is unknown everywhere.
    // ============================================================
    @Test fun deleteResolvesIdViaServerContactsOrStaysLocalOnly() {
        contactsSeed.add(
            JSONObject().put("id", 91).put("name", "Тимур").put("relation", "Сосед")
                .put("phone", "+7 927 600-00-02").put("notify_by_default", true)
        )
        mountApp()
        compose.runOnIdle { vm.screen.value = Screen.TrustedContacts }
        waitFor("Добавить контакт")

        // F-13 G5 finding 3: the app-level load (YuldashApp.kt's own LaunchedEffect(sessionVersion))
        // already resolves this phone to id=91 in the PARENT list too, so appending a SECOND,
        // id-0 copy never becomes the visible row — merged.distinctBy{phone} keeps the FIRST
        // (already-resolved) one — and confirm never reaches the serverContacts fallback at all.
        // Replace the parent's own entry with an id-0 copy instead: the screen's OWN independent
        // GET (LaunchedEffect(reloadKey) above) still resolves serverContacts to id=91, so the
        // visible row is id-0 and confirm must fall back to it.
        waitFor("Тимур")
        compose.runOnIdle {
            val idx = vm.trustedContacts.indexOfFirst { it.phone == "+7 927 600-00-02" }
            assertTrue("the app-level load must have already resolved this phone", idx >= 0)
            vm.trustedContacts[idx] = vm.trustedContacts[idx].copy(id = 0)
        }
        waitFor("Тимур")
        compose.onNodeWithContentDescription("Убрать контакт").performClick()
        waitFor("Убрать Тимур?")
        compose.onNodeWithText("Убрать").performClick()
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            deleteRequests.contains(91)
        }
        waitFor("Пока нет контактов")

        // A phone unknown to both the parent list and the server stays a local-only removal —
        // no DELETE is ever sent (documented residual, not a defect).
        val deletesBefore = deleteRequests.size
        compose.runOnIdle {
            vm.trustedContacts.add(TrustedContact("Земфира", "Тётя", "+7 927 600-00-03", true, id = 0))
        }
        waitFor("Земфира")
        compose.onNodeWithContentDescription("Убрать контакт").performClick()
        waitFor("Убрать Земфира?")
        compose.onNodeWithText("Убрать").performClick()
        waitFor("Пока нет контактов")
        assertEquals("a phone unknown everywhere must never reach the server", deletesBefore, deleteRequests.size)
    }

    // ============================================================
    // F-13 G5 finding 4 — YuldashApp.kt:1550 reconciled "the first entry with this phone", not
    // the optimistic contact just added. A stale same-phone id-0 leftover (a prior failed
    // reconciliation, or a fast re-add) could steal this response's id, leaving the real new
    // contact stuck at id=0 (D13-1 still reachable via this path).
    // ============================================================
    @Test fun secondAddWithSameStalePhoneReconcilesItsOwnEntryNotTheFirst() {
        mountApp()
        compose.runOnIdle { vm.screen.value = Screen.TrustedContacts }
        waitFor("Добавить контакт")
        // A pre-existing stale id-0 entry for the same phone (e.g. a leftover optimistic add that
        // never got its id) must not steal the id meant for a fresh, unrelated add of that phone.
        compose.runOnIdle {
            vm.trustedContacts.add(TrustedContact("Дублёр", "Друг", "+7 927 500-00-01", true, id = 0))
        }
        addContact("Ильдар", "Брат", "+7 927 500-00-01")
        waitForToast("Контакт добавлен")
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            vm.trustedContacts.any { it.id > 0 }
        }
        assertEquals(
            "the stale same-phone duplicate must not survive reconciliation as a separate entry",
            1, vm.trustedContacts.size
        )
        val resolved = vm.trustedContacts.single()
        assertEquals("the surviving entry must be the contact actually just added", "Ильдар", resolved.name)
        assertTrue("its own optimistic entry must get its own id, not stay at 0", resolved.id > 0)
    }

    // ============================================================
    // Unhappy paths around the share sheet and the add flow (D13-3 + regressions).
    // ============================================================
    @Test fun shareUnhappyPaths() {
        mountApp()

        // No contacts yet: the sheet must offer to add one, and send no POST.
        goToBooking(41)
        compose.onNodeWithText("Поделиться поездкой с близким").performClick()
        waitFor("Сначала добавь доверенный контакт в профиле")
        assertTrue(shareBodies.isEmpty())

        // D13-3: addContact fails on the server -> the optimistic row must not survive the rollback.
        compose.runOnIdle { vm.screen.value = Screen.TrustedContacts }
        waitFor("Добавить контакт")
        addContactStatus = 503
        addContact("Заилда", "Тётя", "+7 927 000-00-01")
        waitForToast("Не получилось. Проверь сеть и повтори")
        waitFor("Пока нет контактов")
        addContactStatus = 200

        // A real (server-known) contact for the remaining scenarios.
        addContact("Гульнара", "Подруга", "+7 927 000-00-02")
        waitForToast("Контакт добавлен")

        // Share fails with the server's own explanation.
        goToBooking(43)
        compose.onNodeWithText("Поделиться поездкой с близким").performClick()
        waitFor("Гульнара")
        assertTrue("nothing was clicked yet -> no POST", shareBodies.isEmpty())
        shareStatusCode = 429
        shareDetail = "Слишком много сообщений близким за сутки. Попробуй позже."
        compose.onNodeWithText("Гульнара").performClick()
        waitForToast("Слишком много сообщений близким за сутки. Попробуй позже.")
        shareStatusCode = 200
        shareDetail = null

        // Share fails with a bare server error -> the generic by-status text is shown.
        goToBooking(44)
        compose.onNodeWithText("Поделиться поездкой с близким").performClick()
        waitFor("Гульнара")
        shareStatusCode = 503
        compose.onNodeWithText("Гульнара").performClick()
        waitForToast("Ошибка сервера. Попробуй позже.")
        shareStatusCode = 200

        // Revoke fails -> the active link must stay.
        goToBooking(45)
        compose.onNodeWithText("Поделиться поездкой с близким").performClick()
        waitFor("Гульнара")
        compose.onNodeWithText("Гульнара").performClick()
        waitFor("Ссылка для близкого")
        revokeStatusCode = 503
        compose.onNodeWithText("Отозвать").performClick()
        waitForToast("Ошибка сервера. Попробуй позже.")
        compose.onNodeWithText("Ссылка для близкого").assertIsDisplayed()
        revokeStatusCode = 200

        // The shares list cannot be read -> the contact choice is shown regardless (no crash).
        goToBooking(46)
        sharesGetStatusCode = 500
        compose.onNodeWithText("Поделиться поездкой с близким").performClick()
        waitFor("Кому отправить поездку")
        compose.onNodeWithText("Гульнара").assertIsDisplayed()
        sharesGetStatusCode = 200

        // F-13 G5 finding 5: closing the sheet without picking anyone must send no POST. The
        // sheet is a real material3 ModalBottomSheet — its own dialog wrapper adds a second
        // semantics root, so isDialog() names that root and a scrim tap dismisses it (same
        // proven technique as the SBP sheet fix, AdsCabinetPaymentJourneyTest.kt:685).
        goToBooking(47)
        compose.onNodeWithText("Поделиться поездкой с близким").performClick()
        waitFor("Гульнара")
        val shareCountBeforeDismiss = shareBodies.size
        compose.onNode(isDialog()).performTouchInput { click(Offset(2f, 2f)) }
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithText("Гульнара").fetchSemanticsNodes().isEmpty()
        }
        assertEquals(
            "closing the sheet without picking anyone must send no POST",
            shareCountBeforeDismiss, shareBodies.size
        )
    }

    // ============================================================
    // Positive control: taxi share already uses server-loaded contacts (no id-0 bug here).
    // ============================================================
    @Test fun taxiShareUsesServerContacts() {
        contactsSeed.add(
            JSONObject().put("id", 80).put("name", "Гульнара").put("relation", "Подруга")
                .put("phone", "+7 927 900-00-01").put("notify_by_default", true)
        )
        chooseAddress()
        compose.onNodeWithText("Заказать").performClick()
        waitFor("Ищем машину")
        instantOrderStatus = "accepted"
        waitFor("Тестовый водитель едет")

        // Волна 160: делёжка поездкой ушла с главного экрана под «Безопасность» (TripSafetySheet) —
        // прямой кнопки «Поделиться поездкой» тут больше нет (см. TaxiTripScreen.kt/TaxiSheet.kt).
        compose.onNodeWithText("Безопасность").performClick()
        waitFor("Поделиться поездкой")
        compose.onNodeWithText("Поделиться поездкой").performClick()
        waitFor("Гульнара")
        compose.onNodeWithText("Гульнара").performClick()
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            sharesByOrder[71].orEmpty().isNotEmpty()
        }
        waitFor("Активные ссылки")
        compose.onNodeWithText("Отозвать").performClick()
        waitForToast("Ссылка отозвана")
    }

    // ---------------- helpers ----------------

    private fun mountApp() {
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(
                LocalViewModelStoreOwner provides owner, LocalAppLanguage provides AppLanguage.Ru,
                LocalPoolingNativeMapEnabled provides false,
            ) {
                YuldashTheme { YuldashApp() }
            }
        }
    }

    private fun goToBooking(id: Int) {
        compose.runOnIdle { vm.screen.value = Screen.Home }
        compose.runOnIdle {
            vm.activeBookingId.value = id
            vm.activeTrip.value = null
            vm.screen.value = Screen.ActiveTrip
        }
        // Докручиваем строку в кадр (LazyColumn) — дальше вызывающий тест сам решает, кликать
        // сразу или сперва проверить состояние экрана.
        scrollTo("Поделиться поездкой с близким")
    }

    private fun addContact(name: String, relation: String, phone: String) {
        openDialogWithTextField { compose.onNodeWithText("Добавить контакт").performClick() }
        compose.onNodeWithText("Имя").performTextInput(name)
        compose.onNodeWithText("Кто это (сестра, сын…)").performTextInput(relation)
        compose.onNodeWithText("Телефон").performTextInput(phone)
        compose.onNodeWithText("Добавить").performClick()
    }

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

    private fun waitFor(text: String) {
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForToast(text: String) {
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            ShadowToast.getTextOfLatestToast() == text
        }
    }

    /** Прокрутить LazyColumn до строки с текстом и тапнуть по ней.
     *  «Поделиться поездкой с близким» (ShareTripRow) лежит ниже видимой области w411dp-h891dp:
     *  под Robolectric строки LazyColumn ниже экрана ещё не созданы — обычный onNodeWithText
     *  их не находит, пока их не докрутить (см. AccessibilityDeep3/4ContentTest.scrollTo). */
    private fun scrollToAndClick(text: String) {
        scrollTo(text)
        compose.onNodeWithText(text).performClick()
    }

    private fun scrollTo(text: String) {
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(text))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    private fun chooseAddress() {
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                InstantOrderScreen(onBack = {}, onLoginRequired = {}, embedded = true, payMethod = PayMethods.CASH, renderNativeMap = false)
            }
        }
        waitFor("Дом")
        compose.onNode(hasText("Дом") and hasClickAction()).performClick()
        waitFor("Заказать")
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            instantEstimates.isNotEmpty() && !compose.onNodeWithText("Заказать").fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
        }
    }

    private fun instantOrder() = """{"id":71,"status":"$instantOrderStatus","role":"passenger","from_lat":54.735,"from_lng":55.958,"to_lat":54.751,"to_lng":56.001,"from_text":"Моя позиция","to_text":"Уфа, Ленина 10","category":"standard","payment_method":"cash","price_estimate":250,"price_final":250,"driver_name":"Тестовый водитель","driver_car":"Тестовая машина","driver_plate":"А001АА","can_rate":true}"""

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code)
        .setHeader("Content-Type", "application/json").setBody(body)
}
