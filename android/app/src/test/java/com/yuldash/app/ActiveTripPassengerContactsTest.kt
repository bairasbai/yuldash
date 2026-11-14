package com.yuldash.app

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.MemoryDiskPreferences
import com.yuldash.app.data.OfflineTripSnapshots
import com.yuldash.app.data.TripPassStore
import com.yuldash.app.data.TripPassAuthorityFixture
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Приёмка 18.10.2026, находка 1: пассажир открыл подтверждённую поездку из «Моих поездок»
 * (в обход экрана «Детали поездки», офлайн-паспорта ещё нет) — и не видел ни телефона водителя,
 * ни машины с номером, ни места встречи. Настоящий экран «Моя поездка» + HTTP.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ActiveTripPassengerContactsTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mounted = mutableStateOf(false)
    private val requests = CopyOnWriteArrayList<String>()
    private val preparationRequests = CopyOnWriteArrayList<String>()
    private val retryHeld = CountDownLatch(1)
    private val releaseRetry = CountDownLatch(1)
    private val retryReturned = CountDownLatch(1)
    private val freshCodeHeld = CountDownLatch(1)
    private val releaseFreshCode = CountDownLatch(1)
    private val freshCodeReturned = CountDownLatch(1)
    private val releaseOrderedDetails = CountDownLatch(1)
    private val onboardDetailsHeld = CountDownLatch(1)
    private val releaseLaterRolePoll = CountDownLatch(1)
    private val releaseOnboardDetails = CountDownLatch(1)
    private val firstDetailsHeld = CountDownLatch(1)
    private val releaseFirstDetails = CountDownLatch(1)
    private val firstDetailsReturned = CountDownLatch(1)
    private val historyHeld = CountDownLatch(1)
    private val releaseHistory = CountDownLatch(1)
    private val firstRoleHeld = CountDownLatch(1)
    private val releaseFirstRole = CountDownLatch(1)
    private val roleCalls = AtomicInteger()
    private val detailsCalls = AtomicInteger()
    private val codeCalls = AtomicInteger()
    private val detailsStatuses = CopyOnWriteArrayList<String>()
    private lateinit var server: MockWebServer
    private val passPlain = MemoryDiskPreferences()
    private val passSecure = MemoryDiskPreferences()
    @Volatile private var detailsOverride: JSONObject? = null
    private val onboardEpoch = "2031-02-03T09:00:00.123456Z"
    @Volatile private var role = "passenger"
    @Volatile private var status = "confirmed"
    @Volatile private var phase = ""
    @Volatile private var agreementAmount: Int? = null
    @Volatile private var cancelledInTransit = false
    private val tripEnds = AtomicInteger()
    private val acceptedTerminalHint = mutableStateOf<ActiveTripTerminalHint?>(null)
    @Volatile private var unlocked = true
    @Volatile private var codeUnavailable = false
    @Volatile private var holdRetry = false
    @Volatile private var oldRetryCode = "5678"
    @Volatile private var holdFreshCode = false
    @Volatile private var holdOnboardDetails = false
    @Volatile private var preparingRole = false
    @Volatile private var orderInitialDetails = false
    @Volatile private var detailsUnavailable = false
    @Volatile private var holdFirstDetails = false
    @Volatile private var holdHistory = false
    @Volatile private var holdFirstRole = false
    @Volatile private var freezeRoleAfterBoarding = false
    private val reportedOnboard = AtomicBoolean(false)
    private val phone = "+7 900 111-22-33"
    private val pickup = "У ТЦ «Мега», главный вход"
    private var alightingNote = ""
    @Volatile private var meetingVersion = 2
    @Volatile private var pickupPointsFailure = false
    @Volatile private var pickupPointsEmpty = false
    @Volatile private var holdSecondCity = false
    @Volatile private var holdFirstCity = false
    private val releaseFirstCity = CountDownLatch(1)
    private val secondCityHeld = CountDownLatch(1)
    private val releaseSecondCity = CountDownLatch(1)
    private val passengerPhone = "+7 900 555-66-77"

    @Before fun setup() {
        ApiClient.resetForTest()
        TripPassStore.initStores(passPlain, passSecure)
        assertNull(TripPassStore.load(context, 42))
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (preparingRole) {
                        preparationRequests += "${request.method} ${request.path}"
                        return if (request.method == "GET" && request.path == "/bookings/42/role")
                            MockResponse().setHeader("Content-Type", "application/json").setBody(roleJson())
                        else MockResponse().setResponseCode(503).setBody("{}")
                    }
                    requests.add("${request.method} ${request.path}")
                    if (request.method != "GET") return MockResponse().setResponseCode(405)
                    if (request.path?.startsWith("/pickup-points?") == true) {
                        val city = request.requestUrl?.queryParameter("city").orEmpty()
                        if (city == "Уфа" && holdFirstCity && !releaseFirstCity.await(30, TimeUnit.SECONDS))
                            return MockResponse().setResponseCode(503)
                        if (city == "Сибай" && holdSecondCity) {
                            secondCityHeld.countDown()
                            if (!releaseSecondCity.await(30, TimeUnit.SECONDS)) return MockResponse().setResponseCode(503)
                        }
                        if (pickupPointsFailure) return MockResponse().setResponseCode(503).setBody("{}")
                        val rows = if (pickupPointsEmpty) "[]" else """[{"id":7,"city":"$city","title_ru":"Ориентир $city","title_ba":"Урын $city"}]"""
                        return MockResponse().setHeader("Content-Type", "application/json").setBody("""{"items":$rows}""")
                    }
                    if (request.path == "/bookings/42/role") {
                        if (roleCalls.incrementAndGet() == 1 && holdFirstRole) {
                            firstRoleHeld.countDown()
                            if (!releaseFirstRole.await(30, TimeUnit.SECONDS)) return MockResponse().setResponseCode(503)
                        }
                        // This test controls one boarding transition; later routine refreshes belong to the next poll period.
                        if (freezeRoleAfterBoarding && status == "onboard" && !reportedOnboard.compareAndSet(false, true)) {
                            if (!releaseLaterRolePoll.await(30, TimeUnit.SECONDS)) return MockResponse().setResponseCode(503)
                        }
                        return MockResponse().setHeader("Content-Type", "application/json").setBody(roleJson())
                    }
                    if (request.path == "/bookings/42/boarding-code") {
                        val attempt = codeCalls.incrementAndGet()
                        if (codeUnavailable) return MockResponse().setResponseCode(503).setBody("{}")
                        if (holdRetry && attempt == 2) {
                            retryHeld.countDown()
                            if (!releaseRetry.await(30, TimeUnit.SECONDS)) return MockResponse().setResponseCode(503)
                            retryReturned.countDown()
                            return MockResponse().setHeader("Content-Type", "application/json")
                                .setBody(JSONObject().put("code", oldRetryCode).toString())
                        }
                        if (holdFreshCode && attempt >= 3) {
                            freshCodeHeld.countDown()
                            if (!releaseFreshCode.await(30, TimeUnit.SECONDS)) return MockResponse().setResponseCode(503)
                            freshCodeReturned.countDown()
                        }
                    }
                    if (request.path == "/bookings/42/details") {
                        val index = detailsCalls.incrementAndGet()
                        if (index == 1 && orderInitialDetails && !releaseOrderedDetails.await(30, TimeUnit.SECONDS))
                            return MockResponse().setResponseCode(503)
                        // Доказываем порядок: role GET уже начался, только затем details может вернуть 503.
                        if (index == 1 && holdFirstRole && !firstRoleHeld.await(30, TimeUnit.SECONDS))
                            return MockResponse().setResponseCode(503)
                        if (index == 1 && holdFirstDetails) {
                            firstDetailsHeld.countDown()
                            if (!releaseFirstDetails.await(30, TimeUnit.SECONDS)) return MockResponse().setResponseCode(503)
                            firstDetailsReturned.countDown()
                            return MockResponse().setResponseCode(503).setBody("{}")
                        }
                        if (detailsUnavailable) return MockResponse().setResponseCode(503).setBody("{}")
                        if (status == "onboard" && holdOnboardDetails) {
                            onboardDetailsHeld.countDown()
                            if (!releaseOnboardDetails.await(30, TimeUnit.SECONDS)) return MockResponse().setResponseCode(503)
                        }
                        detailsStatuses += status
                        return MockResponse().setHeader("Content-Type", "application/json").setBody(details())
                    }
                    if (request.path == "/bookings/42/messages" && holdHistory) {
                        historyHeld.countDown()
                        if (!releaseHistory.await(30, TimeUnit.SECONDS)) return MockResponse().setResponseCode(503)
                    }
                    val body = when (request.path) {
                        "/trips/42/receipt" -> JSONObject().put("booking_id", 42).put("ride_id", 17)
                            .put("role", role).put("from_city", "Город А").put("to_city", "Город Б")
                            .put("depart_at", "2031-02-03T09:00:00").put("seats", 1).put("amount", agreementAmount ?: 700)
                            .put("pay_method", "cash").put("paid", false).put("driver_name", "Айдар")
                            .put("counterparty_name", "Айдар").put("my_stars", 0).put("my_rating_tags", "").toString()
                        "/bookings/42/tip", "/trips/42/tip" -> """{"already_thanked":false}"""
                        "/bookings/42/messages" -> """{"items":[]}"""
                        "/bookings/42/boarding-code" -> """{"code":"5678"}"""
                        "/bookings/42/details" -> details()
                        else -> return MockResponse().setResponseCode(404).setBody("{}")
                    }
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
                }
            }
            start(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0)
        }
        TripPassAuthorityFixture.installSession(server.url("/").newBuilder().host("127.0.0.1").build().toString().trimEnd('/'))
        ApiClient.testTimeoutMs = 45000
    }

    @After fun cleanup() {
        releaseRetry.countDown()
        releaseFreshCode.countDown()
        releaseOrderedDetails.countDown()
        releaseOnboardDetails.countDown()
        releaseFirstDetails.countDown()
        releaseHistory.countDown()
        releaseFirstRole.countDown()
        releaseSecondCity.countDown()
        releaseFirstCity.countDown()
        compose.runOnIdle { mounted.value = false }
        releaseLaterRolePoll.countDown()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        ApiClient.logout()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        TripPassStore.initStores(MemoryDiskPreferences(), null)
        server.shutdown()
    }

    private fun roleJson(): String = JSONObject().put("role", role).put("status", status).put("driver_phase", phase)
        .put("cancelled_in_transit", cancelledInTransit).put("problem_report_sec", 3600)
        .put("onboard_at", if (status == "onboard") onboardEpoch else JSONObject.NULL).toString()

    /** Сервер открывает связь только участнику подтверждённой брони — так и в bookings.py. */
    private fun details(): String = detailsOverride?.toString() ?: JSONObject()
        .put("booking_id", 42).put("ride_id", 17).put("role", role).put("status", status)
        .put("cancelled_in_transit", cancelledInTransit).put("problem_report_sec", 3600)
        .put("contact_unlocked", unlocked).put("from_city", "Город А").put("to_city", "Город Б")
        .put("depart_at", "2031-02-03T09:00:00").put("price", 700).put("seats", 1)
        .put("pay_method", "cash").put("pay_amount", agreementAmount ?: JSONObject.NULL)
        .put("driver_name", "Айдар").put("driver_car", "Lada Vesta").put("passenger_name", "Гульнара")
        .put("passenger_phone", if (role == "driver" && unlocked) passengerPhone else "")
        .put("driver_phone", if (unlocked) phone else "")
        .put("driver_plate", if (unlocked) "А123ВС 102" else "")
        .put("driver_car_color", if (unlocked) "белая" else "")
        .put("pickup", if (unlocked) pickup else "")
        .put("alight_note", alightingNote)
        .put("meeting_version", meetingVersion)
        .put("onboard_at", if (status == "onboard") onboardEpoch else JSONObject.NULL)
        .toString()

    private fun observed(value: JSONObject): com.yuldash.app.data.BookingDetailsDto = runBlocking {
        detailsOverride = JSONObject(value.toString()).put("ride_id", 17)
            .put("onboard_at", if (value.optString("status") == "onboard") onboardEpoch else JSONObject.NULL)
        ApiClient.getBookingDetails(42).getOrThrow()
    }
    private fun publishPoint(value: JSONObject, state: androidx.compose.runtime.MutableState<com.yuldash.app.data.BookingDetailsDto>) {
        val fresh = observed(value)
        compose.runOnIdle { state.value = fresh }
    }

    @Test fun activePassengerSeesOwnAlightingSeparateFromPickup() {
        alightingNote = "У синих ворот после моста"
        openScreen()
        compose.waitUntil(15000) {
            settle()
            requests.contains("GET /bookings/42/details") && runCatching { scrollTo(alightingNote) }.isSuccess
        }
        compose.onNodeWithText(alightingNote).assertIsDisplayed()
        compose.onNodeWithText("Место высадки").assertIsDisplayed()
        scrollTo(pickup)
        compose.onNodeWithText(pickup).assertIsDisplayed()
    }

    @Test fun ownPointsShowLanguageFallbackAndPinForPassengerIncludingTerminal() {
        val language = mutableStateOf(AppLanguage.Ru)
        val pointJson = JSONObject("""{
            "booking_id":42,"role":"passenger","status":"confirmed",
            "alight_lat":52.7,"alight_lng":58.6,
            "alight_landmark_ru":"у школы","alight_landmark_ba":"мәктәп янында","alight_note":"синие ворота"
        }""")
        val point = mutableStateOf(observed(pointJson))
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides language.value) { OwnMeetingPoints(point.value) }
        }
        compose.onNodeWithText("у школы · синие ворота").assertIsDisplayed()
        compose.onNodeWithText("Маршрут к месту высадки").assertIsEnabled().performClick()
        val chooser = Shadows.shadowOf(context as Application).nextStartedActivity
        assertEquals(android.content.Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val map = chooser.getParcelableExtra<android.content.Intent>(android.content.Intent.EXTRA_INTENT)
        assertTrue(map?.dataString.orEmpty().startsWith("geo:52.7,58.6?q=52.7,58.6("))
        compose.runOnIdle { language.value = AppLanguage.Ba }
        compose.onNodeWithText("мәктәп янында · синие ворота").assertIsDisplayed()
        publishPoint(pointJson.put("alight_landmark_ba", ""), point)
        compose.onNodeWithText("у школы · синие ворота").assertIsDisplayed()
        publishPoint(pointJson.put("alight_landmark_ru", "").put("alight_note", ""), point)
        compose.onNodeWithText("Карталағы нөктә").assertIsDisplayed()
        publishPoint(pointJson.put("alight_lat", JSONObject.NULL).put("alight_lng", JSONObject.NULL), point)
        compose.onNodeWithText("Төшөү урыны").assertDoesNotExist()
        publishPoint(pointJson.put("alight_lat", 52.7).put("alight_lng", 58.6), point)
        compose.runOnIdle { point.value = point.value.copy(role = "driver") }
        compose.onNodeWithText("Төшөү урыны").assertDoesNotExist()
        publishPoint(pointJson.put("role", "passenger").put("status", "done"), point)
        compose.onNodeWithText("Төшөү урыны").assertIsDisplayed()
        publishPoint(pointJson.put("status", "cancelled"), point)
        compose.onNodeWithText("Төшөү урыны").assertIsDisplayed()
    }

    @Test fun ownPickupDoesNotNeedUnlockedContactsAndOnboardCanEditOnlyDropoff() {
        val pointJson = JSONObject("""{
            "booking_id":42,"role":"passenger","status":"pending","contact_unlocked":false,
            "meet_note":"Моя посадка","alight_note":"Моя высадка"
        }""")
        val point = mutableStateOf(observed(pointJson))
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) { PassengerMeetingPoints(point.value) {} }
        }
        compose.onNodeWithText("Моя посадка").assertIsDisplayed()
        compose.onNodeWithText("Моя высадка").assertIsDisplayed()
        compose.onNodeWithText("Изменить место посадки").assertIsEnabled()
        compose.onNodeWithText("Изменить место высадки").assertIsEnabled()
        publishPoint(pointJson.put("status", "onboard"), point)
        compose.onNodeWithText("Моя высадка").assertIsDisplayed()
        compose.onNodeWithText("Изменить место посадки").assertDoesNotExist()
        compose.onNodeWithText("Изменить место высадки").assertIsEnabled()
        publishPoint(pointJson.put("status", "done"), point)
        compose.onNodeWithText("Моя посадка").assertIsDisplayed()
        compose.onNodeWithText("Моя высадка").assertIsDisplayed()
        compose.onNodeWithText("Изменить место высадки").assertDoesNotExist()
    }

    @Test fun meetingEditorKeepsFailedDraftAndClosesOnlyAfterSuccess() {
        val d = observed(JSONObject("""{
            "booking_id":42,"role":"passenger","status":"onboard","alight_note":"старое",
            "alight_landmark_id":18,"alight_landmark_ru":"у школы","alight_landmark_ba":"мәктәп янында"
        }"""))
        val busy = mutableStateOf(false)
        val error = mutableStateOf<String?>(null)
        var sent: com.yuldash.app.data.MeetingPointInput? = null
        var saved: (() -> Unit)? = null
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                BookingMeetingActions(d, busy = busy.value, error = error.value,
                    onPropose = { kind, point, onSaved ->
                        assertEquals("dropoff", kind); sent = point; saved = onSaved; busy.value = true
                    }, onWithdraw = {})
            }
        }
        compose.onNodeWithText("Изменить место высадки").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("новые ворота")
        compose.onNodeWithText("Готово").performClick()
        assertEquals("новые ворота", sent?.note)
        assertEquals(18, sent?.landmarkId)
        compose.runOnIdle { busy.value = false; error.value = "Не получилось сохранить" }
        compose.onNodeWithText("Не получилось сохранить").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertTextContains("новые ворота")
        compose.runOnIdle { saved?.invoke() }
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
    }

    @Test fun landmarkChipsResetCityImmediatelyAndOfferRetryOrMapWhenEmpty() {
        holdFirstCity = true
        val city = mutableStateOf("Уфа")
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                PickupSuggestionChips(city.value, selectedId = null) {}
            }
        }
        compose.onNodeWithText("Загружаем ориентиры…").assertExists()
        releaseFirstCity.countDown()
        fun awaitText(text: String) = compose.waitUntil(15000) {
            settle(); compose.mainClock.advanceTimeBy(100)
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        awaitText("Ориентир Уфа")
        holdSecondCity = true; pickupPointsFailure = true
        compose.runOnIdle { city.value = "Сибай" }
        compose.onNodeWithText("Ориентир Уфа").assertDoesNotExist()
        compose.onNodeWithText("Загружаем ориентиры…").assertExists()
        compose.waitUntil(15000) { settle(); compose.mainClock.advanceTimeBy(100); secondCityHeld.count == 0L }
        releaseSecondCity.countDown()
        awaitText("Повторить")
        pickupPointsFailure = false; pickupPointsEmpty = true
        compose.onNodeWithText("Повторить").performClick()
        awaitText("Ориентиров пока нет — поставь точку на карте")
        compose.onNodeWithText("Ориентир Уфа").assertDoesNotExist()
    }

    @Test fun confirmedPassengerSeesPhoneCarPlateAndMeetingPointWithoutBookingScreen() {
        openScreen()
        // LazyColumn не создаёт невидимую карточку заранее. Ждём применения HTTP-ответа по
        // записанному паспорту, затем прокручиваем настоящий список до контактов.
        compose.waitUntil(15000) {
            settle()
            TripPassStore.load(context, 42)?.driverPhone == phone
        }
        scrollTo(phone)
        compose.onNodeWithText(phone).assertIsDisplayed()
        compose.onNodeWithText("Телефон водителя").assertIsDisplayed()
        scrollTo("белая Lada Vesta · А123ВС 102")
        compose.onNodeWithText("белая Lada Vesta · А123ВС 102").assertIsDisplayed()
        compose.onNodeWithContentDescription("Позвонить").assertExists()
        scrollTo(pickup)
        compose.onNodeWithText(pickup).assertIsDisplayed()
        // Тот же офлайн-паспорт, что пишет экран брони: на трассе без связи номер и точка на месте.
        awaitCompletePassport()
        assertEquals("Второй запрос кода ради паспорта не нужен", 1,
            requests.count { it == "GET /bookings/42/boarding-code" })
    }

    @Test fun pendingPassengerSeesNoContactsAndNothingIsSaved() {
        status = "pending"
        unlocked = false
        openScreen()
        waitForDetails()
        compose.onAllNodesWithText("Телефон водителя").assertCountEquals(0)
        compose.onAllNodesWithText(phone).assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Позвонить").assertCountEquals(0)
        assertNull(TripPassStore.load(context, 42))
    }

    @Test fun driverDoesNotGetPassengerContactsCard() {
        role = "driver"
        openScreen()
        waitForDetails()
        compose.onAllNodesWithText("Телефон водителя").assertCountEquals(0)
        compose.onAllNodesWithText(phone).assertCountEquals(0)
        assertNull(TripPassStore.load(context, 42))
    }

    /** Приёмка 18.10, п.14: у водителя вверху экрана была карточка «Водитель» вместо того, кого он везёт. */
    @Test fun driverSeesWhoHeIsDrivingNotADriverCard() {
        role = "driver"
        openScreen()
        waitForDetails()
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithText("Гульнара").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithText("Гульнара")[0].assertIsDisplayed()
        compose.onAllNodesWithText("Айдар").assertCountEquals(0)          // его же имя ему не нужно
        compose.onAllNodesWithText("Водитель").assertCountEquals(0)
        compose.onAllNodesWithText("Lada Vesta", substring = true).assertCountEquals(0)
        // Решение 23.10.2026: после подтверждения водитель звонит пассажиру — одна кнопка у его имени.
        compose.onAllNodesWithContentDescription("Позвонить").assertCountEquals(1)
        compose.onAllNodesWithText(passengerPhone).assertCountEquals(0)   // номер не печатаем — только звонок
    }

    @Test fun driverCannotCallBeforeConfirmation() {
        role = "driver"
        status = "pending"
        unlocked = false
        openScreen()
        waitForDetails()
        compose.onAllNodesWithContentDescription("Позвонить").assertCountEquals(0)
    }

    @Test fun driverCallsGuardianInsteadOfMinorPassenger() {
        role = "driver"
        val guardianPhone = "+12025550118"
        detailsOverride = JSONObject(details()).put("minor_passenger", true)
            .put("minor_guardian_name", "Тестовый взрослый")
            .put("minor_guardian_phone", guardianPhone)
            .put("passenger_phone", "")
        openScreen()
        waitForDetails()
        scrollTo("Позвонить взрослому")
        compose.onNodeWithText("Тестовый взрослый").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Позвонить").assertCountEquals(0)
        compose.onNodeWithText("Позвонить взрослому").performClick()
        val dial = Shadows.shadowOf(context as Application).nextStartedActivity
        assertEquals(android.content.Intent.ACTION_DIAL, dial.action)
        assertEquals("tel:$guardianPhone", dial.dataString)
    }

    @Test fun retryCodeCreatesCompletePassportFromAlreadyLoadedDetailsWithoutExistingCopy() {
        startWithoutCodeOrPassport()
        codeUnavailable = false
        scrollTo("Повторить загрузку кода")
        compose.onNodeWithText("Повторить загрузку кода").performClick()
        compose.waitUntil(15000) {
            settle()
            TripPassStore.load(context, 42)?.boardingCode == "5678"
        }
        val saved = requireNotNull(TripPassStore.load(context, 42))
        assertEquals(phone, saved.driverPhone)
        assertEquals("белая Lada Vesta", saved.driverCar)
        assertEquals("А123ВС 102", saved.driverPlate)
        assertEquals(pickup, saved.pickup)
        assertEquals("5678", saved.boardingCode)
        assertEquals("Повтор не загружает детали заново", 1, requests.count { it == "GET /bookings/42/details" })
        assertEquals(2, requests.count { it == "GET /bookings/42/boarding-code" })
    }

    @Test fun retryCodeAfterSessionChangeCannotCreatePassportFromPreviousDetails() {
        startWithoutCodeOrPassport()
        codeUnavailable = false
        holdRetry = true
        scrollTo("Повторить загрузку кода")
        compose.onNodeWithText("Повторить загрузку кода").performClick()
        compose.waitUntil(15000) {
            settle()
            retryHeld.count == 0L
        }
        compose.runOnIdle { ApiClient.saveToken(TripPassAuthorityFixture.jwt(8)) }
        releaseRetry.countDown()
        assertTrue(retryReturned.await(5, TimeUnit.SECONDS))
        // Ограниченное наблюдение после позднего ответа: новый вход не получает старый паспорт.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        compose.waitForIdle()
        assertNull(TripPassStore.load(context, 42))
        assertEquals(1, requests.count { it == "GET /bookings/42/details" })
    }

    @Test fun delayedCodeRetryAcrossBoardingReloadsMatchingDetailsAndCreatesPassport() {
        startWithoutCodeOrPassport()
        codeUnavailable = false
        holdRetry = true
        oldRetryCode = "1111"
        holdFreshCode = true
        holdOnboardDetails = true
        freezeRoleAfterBoarding = true
        scrollTo("Повторить загрузку кода")
        compose.onNodeWithText("Повторить загрузку кода").performClick()
        compose.waitUntil(15000) { settle(); retryHeld.count == 0L }
        awaitConfirmedRole()
        alightingNote = "У новых синих ворот"
        meetingVersion = 3
        status = "onboard"
        pumpPollPeriod()
        compose.waitUntil(15000) {
            settle()
            onboardDetailsHeld.count == 0L &&
                compose.onAllNodesWithText("Доехал").fetchSemanticsNodes().isNotEmpty()
        }
        // ROLE уже сменил эпоху: старый код закрыт до новой DETAILS и своего CODE GET.
        compose.waitForIdle()
        assertNull(OfflineTripSnapshots.currentBoardingCode(42)?.takeIf { it.isNotBlank() })
        releaseOnboardDetails.countDown()
        compose.waitUntil(15000) { settle(); freshCodeHeld.count == 0L }
        val latest = requireNotNull(currentTripPrivateDetails(42))
        assertEquals("onboard", latest.status)
        assertEquals(onboardEpoch, latest.boardingUndo.onboardAt)
        assertEquals(alightingNote, latest.alightNote)
        assertEquals(3, latest.meetingVersion)
        releaseRetry.countDown()
        assertTrue(retryReturned.await(5, TimeUnit.SECONDS))
        repeat(20) { settle(); Thread.sleep(10) }
        compose.waitForIdle()
        assertEquals("Новый ответ всё ещё удерживается", 1L, releaseFreshCode.count)
        assertEquals("Новый CODE GET ещё не вернул код", 1L, freshCodeReturned.count)
        assertNull(OfflineTripSnapshots.currentBoardingCode(42)?.takeIf { it.isNotBlank() })
        assertNull(TripPassStore.load(context, 42)?.takeIf { it.boardingCode.isNotBlank() })
        compose.onAllNodesWithText("1111").assertCountEquals(0)
        releaseFreshCode.countDown()
        awaitCompletePassport()
        assertSame(latest, currentTripPrivateDetails(42))
        assertEquals(listOf("confirmed", "onboard"), detailsStatuses.toList())
        assertEquals("Первая попытка, старый повтор и новый GET для новой DETAILS", 3,
            requests.count { it == "GET /bookings/42/boarding-code" })
        assertEquals(1, requests.count { it == "GET /bookings/42/messages" })
    }

    @Test fun failedDetailsAreVisibleAndManualRetryUsesOnlyDetailsRequest() {
        detailsUnavailable = true
        openScreen()
        waitForDetailsError()
        assertNull(TripPassStore.load(context, 42))
        detailsUnavailable = false
        compose.onNodeWithText("Повторить загрузку данных").performClick()
        awaitCompletePassport()
        assertDetailsOnlyRetried()
    }

    @Test fun failedDetailsRecoverAutomaticallyWhenTripPollReachesServerAgain() {
        detailsUnavailable = true
        openScreen()
        waitForDetailsError()
        assertNull(TripPassStore.load(context, 42))
        detailsUnavailable = false
        pumpPollPeriod()
        awaitCompletePassport()
        assertDetailsOnlyRetried()
        compose.onAllNodesWithText("Не удалось загрузить данные поездки").assertCountEquals(0)
    }

    @Test fun staleRetryTapAfterSuccessfulRecoveryDoesNotRequestDetailsAgain() = staleRetryAfterRecovery(false)

    /** CI PR #201: retry() пишет состояние из теста, а GlobalSnapshotManager после сброса Looper молчал. */
    @Test fun staleRetryTapSurvivesLostGlobalSnapshotNotification() = staleRetryAfterRecovery(true)

    private fun staleRetryAfterRecovery(lostNotification: Boolean) {
        detailsUnavailable = true
        val state = ActiveTripDetailsState(42, ApiClient.queueSessionGeneration())
        val retainedErrorTap = state::retry // Кнопка могла остаться в уходящей анимации ошибки.
        compose.setContent {
            ActiveTripDetailsRefresh(state, { true }) {
                state.load({ true }, { "confirmed" }, { "passenger" })
            }
        }
        compose.waitUntil(15000) {
            settle()
            state.failure != null && !state.loading
        }
        val recover: () -> Unit = {
            detailsUnavailable = false
            compose.runOnIdle { state.serverReached(state.failure) }
            compose.waitUntil(15000) {
                settle()
                detailsCalls.get() == 2 && state.failure == null && !state.loading
            }
            compose.runOnIdle { retainedErrorTap() }
            compose.waitForIdle()
        }
        if (lostNotification) withLostGlobalSnapshotNotification(recover) else recover()
        assertFalse("Старое нажатие не начало загрузку", state.loading)
        assertFalse("Успех уже закрыл причину повтора", state.needsReload)
        assertEquals("Старое нажатие не создаёт третий GET", 2, detailsCalls.get())
    }

    @Test fun roleStartedBeforeDetailsFailureDoesNotRetryUntilNextSuccessfulPoll() {
        detailsUnavailable = true
        holdFirstRole = true
        openScreen()
        waitForDetailsError()
        assertEquals("Первый role GET уже начался до отказа details", 0L, firstRoleHeld.count)
        detailsUnavailable = false
        releaseFirstRole.countDown()
        awaitConfirmedRole()
        compose.waitForIdle()
        assertEquals("Поздний initial role не является восстановлением после ошибки", 1, detailsCalls.get())
        compose.onNodeWithText("Не удалось загрузить данные поездки").assertIsDisplayed()
        pumpPollPeriod()
        awaitCompletePassport()
        assertDetailsOnlyRetried()
    }

    @Test fun lateInitialDetailsFailureCannotReplaceNewSuccessfulBoardingDetails() {
        holdFirstDetails = true
        openScreen()
        compose.waitUntil(15000) { settle(); firstDetailsHeld.count == 0L }
        awaitConfirmedRole()
        status = "onboard"
        pumpPollPeriod()
        awaitCompletePassport()
        assertEquals(listOf("onboard"), detailsStatuses.toList())
        val roleReadsAtRelease = roleCalls.get()
        val releasedAt = System.nanoTime()
        releaseFirstDetails.countDown()
        assertTrue(firstDetailsReturned.await(5, TimeUnit.SECONDS))
        compose.waitForIdle()
        settle()
        assertEquals("Без следующего периода ROLE не начинался", roleReadsAtRelease, roleCalls.get())
        assertEquals("Поздний отказ не запускает повтор после успешной загрузки", 2, detailsCalls.get())
        compose.onAllNodesWithText("Не удалось загрузить данные поездки").assertCountEquals(0)
        println("DETAILS_CAUSE source=late-initial-503 elapsedNanos=" + (System.nanoTime() - releasedAt) +
            " roleReads=" + roleCalls.get() + " detailsReads=" + detailsCalls.get())

        val pollStartedAt = System.nanoTime()
        pumpPollPeriod()
        compose.waitUntil(15000) { settle(); detailsCalls.get() == 3 && currentTripPrivateDetails(42) != null }
        assertTrue("Обновление вызвано новым успешным ROLE, а не поздним 503", roleCalls.get() > roleReadsAtRelease)
        assertEquals(listOf("onboard", "onboard"), detailsStatuses.toList())
        println("DETAILS_CAUSE source=next-role-poll-200 elapsedNanos=" + (System.nanoTime() - pollStartedAt) +
            " roleReads=" + roleCalls.get() + " detailsReads=" + detailsCalls.get())
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).performScrollToIndex(0)
        compose.onAllNodesWithText("Не удалось загрузить данные поездки").assertCountEquals(0)
        assertEquals(phone, TripPassStore.load(context, 42)?.driverPhone)
    }

    @Test fun stalledChatHistoryDoesNotBlockContactsCodeOrFullPassport() {
        holdHistory = true
        openScreen()
        compose.waitUntil(15000) { settle(); historyHeld.count == 0L }
        awaitCompletePassport()
        assertEquals("История всё ещё ждёт ответа", 1L, releaseHistory.count)
        assertEquals(1, detailsCalls.get())
        assertEquals(1, requests.count { it == "GET /bookings/42/boarding-code" })
    }

    private fun awaitConfirmedRole() {
        compose.waitUntil(15000) {
            settle()
            compose.onAllNodesWithText("Водитель отметит посадку").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForDetailsError() {
        compose.waitUntil(15000) {
            settle()
            compose.onAllNodesWithText("Не удалось загрузить данные поездки").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Не удалось загрузить данные поездки").assertIsDisplayed()
        compose.onNodeWithText("Повторить загрузку данных").assertIsEnabled()
    }

    private fun awaitCompletePassport() {
        compose.waitUntil(15000) {
            settle()
            TripPassStore.load(context, 42)?.boardingCode == "5678"
        }
        val saved = requireNotNull(TripPassStore.load(context, 42))
        assertEquals(phone, saved.driverPhone)
        assertEquals("белая Lada Vesta", saved.driverCar)
        assertEquals("А123ВС 102", saved.driverPlate)
        assertEquals(pickup, saved.pickup)
    }

    private fun assertDetailsOnlyRetried() {
        assertEquals(2, detailsCalls.get())
        assertEquals(1, requests.count { it == "GET /bookings/42/boarding-code" })
        assertEquals(1, requests.count { it == "GET /bookings/42/messages" })
        assertTrue(requests.all { it.startsWith("GET ") })
    }

    // Запись вне композиции (retry() из теста) экран узнаёт через GlobalSnapshotManager, а он в
    // Robolectric может «уснуть» после сброса Looper между тестами (docs/testing.md, правило 5).
    // Ожидание доставляет её само — как ComposeIdlingResource в waitForIdle.
    private fun settle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        Snapshot.sendApplyNotifications()
    }

    private fun pumpPollPeriod() {
        repeat(140) {
            compose.mainClock.advanceTimeBy(100)
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
            Thread.sleep(5)
        }
        compose.waitForIdle()
    }

    private fun startWithoutCodeOrPassport() {
        codeUnavailable = true
        assertNull(TripPassStore.load(context, 42))
        openScreen()
        compose.waitUntil(15000) {
            settle()
            requests.contains("GET /bookings/42/details") &&
                runCatching { scrollTo(phone) }.isSuccess
        }
        compose.onNodeWithText(phone).assertIsDisplayed()
        compose.waitUntil(15000) {
            settle()
            codeCalls.get() >= 1 && runCatching { scrollTo("Повторить загрузку кода") }.isSuccess
        }
        compose.onNodeWithText("Повторить загрузку кода").assertIsDisplayed()
        assertNull(TripPassStore.load(context, 42)?.takeIf { it.boardingCode.isNotBlank() })
        assertNull(TripPassAuthorityFixture.durable(passSecure, 42))
        assertEquals(1, requests.count { it == "GET /bookings/42/boarding-code" })
    }

    private fun openScreen() {
        // Production GuardedActiveTripDestination монтирует этот экран только после LIVE ROLE.
        // Отдельный HTTP-ledger не подмешивает подготовку в точные счётчики самого экрана.
        preparingRole = true
        try { runBlocking { ApiClient.getTripState(42).getOrThrow() } }
        finally { preparingRole = false }
        assertEquals(listOf("GET /bookings/42/role"), preparationRequests.toList())
        assertNull(OfflineTripSnapshots.projection(42))
        assertNull(OfflineTripSnapshots.privatePass(42))
        assertNull(TripPassStore.load(context, 42))
        assertNull(OfflineTripSnapshots.currentBoardingCode(42))
        assertEquals(0, codeCalls.get())
        assertEquals(status in setOf("confirmed", "onboard"), OfflineTripSnapshots.allowsLive(42))
        orderInitialDetails = !holdFirstRole && status in setOf("confirmed", "onboard")
        val authorityBeforeMount = OfflineTripSnapshots.version.value
        mounted.value = true
        compose.setContent {
            if (mounted.value) {
                CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                    // Без Ride и без координат маршрута — настоящая MapKit в тесте не поднимается.
                    ActiveTripScreen(ride = null, contacts = emptyList(), bookingId = 42,
                        onBack = {}, onTripEnd = { tripEnds.incrementAndGet() }, onSos = {}, terminalHint = acceptedTerminalHint.value)
                }
            }
        }
        if (orderInitialDetails) {
            // ROLE этого mount должен завершиться до DETAILS: одинаковый свежий ROLE не
            // обязан сохранять write-ticket прежней DETAILS ради тестового порядка ответов.
            try {
                val ready = when {
                    role == "driver" -> "Я выехал"
                    status == "onboard" -> "Доехал"
                    else -> "Водитель отметит посадку"
                }
                compose.waitUntil(15000) {
                    settle()
                    requests.contains("GET /bookings/42/role") &&
                        OfflineTripSnapshots.version.value > authorityBeforeMount &&
                        compose.onAllNodesWithText(ready).fetchSemanticsNodes().isNotEmpty()
                }
            } finally { releaseOrderedDetails.countDown() }
        }
    }

    private fun waitForDetails() {
        compose.waitUntil(15000) {
            settle()
            requests.contains("GET /bookings/42/details") && requests.contains("GET /bookings/42/role")
        }
        compose.waitForIdle()
    }

    private fun scrollTo(text: String) {
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasText(text))
    }

    @Test fun driverPhaseChangeRecoversPrivateDetailsWithoutManualRetry() {
        openScreen()
        awaitCompletePassport()
        val before = detailsCalls.get()
        phase = "departed"
        pumpPollPeriod()
        compose.waitUntil(15000) {
            settle()
            detailsCalls.get() > before && currentTripPrivateDetails(42) != null &&
                OfflineTripSnapshots.currentBoardingCode(42) == "5678"
        }
        scrollTo(phone)
        compose.onNodeWithText(phone).assertIsDisplayed()
        compose.onAllNodesWithText("Не удалось загрузить данные поездки").assertCountEquals(0)
        assertTrue(requests.all { it.startsWith("GET ") })
    }

    @Test fun successfulRolePollRefreshesPaymentAgreementOnTheOpenScreen() {
        openScreen()
        awaitCompletePassport()
        val before = detailsCalls.get()
        agreementAmount = 400
        pumpPollPeriod()
        compose.waitUntil(15000) {
            settle()
            detailsCalls.get() > before && currentTripPrivateDetails(42)?.payAmount == 400 &&
                TripPassStore.load(context, 42)?.let { it.payAmount == 400 && it.boardingCode == "5678" } == true
        }
        compose.onAllNodesWithText("Код не сохранён на телефоне").assertCountEquals(0)
        val saved = requireNotNull(TripPassStore.load(context, 42))
        assertEquals("cash", saved.payMethod)
        assertEquals(phone, saved.driverPhone)
        assertEquals("А123ВС 102", saved.driverPlate)
        assertEquals(pickup, saved.pickup)
        scrollTo("Как договорились платить")
        compose.onAllNodesWithText("400", substring = true).onFirst().assertExists()
        assertEquals("cash", currentTripPrivateDetails(42)?.payMethod)
        assertTrue(requests.all { it.startsWith("GET ") })
    }

    @Test fun equalDetailsWithNewProofFetchTheirOwnCodeAndKeepCompletePassport() {
        openScreen()
        awaitCompletePassport()
        val before = requireNotNull(OfflineTripSnapshots.projection(42))
        val codeReads = codeCalls.get()
        pumpPollPeriod()
        compose.waitUntil(15000) {
            settle()
            OfflineTripSnapshots.projection(42)?.proof?.request?.ticket != before.proof.request.ticket &&
                codeCalls.get() > codeReads && OfflineTripSnapshots.currentBoardingCode(42) == "5678" &&
                TripPassStore.load(context, 42)?.boardingCode == "5678"
        }
        val after = requireNotNull(OfflineTripSnapshots.projection(42))
        assertEquals("The visible DETAILS fields are exactly equal", before.details, after.details)
        assertNotSame(before.details, after.details)
        assertNotEquals(before.proof.request.ticket, after.proof.request.ticket)
        assertEquals("A CODE publication must not schedule another CODE", codeReads + 1, codeCalls.get())
        awaitCompletePassport()
        compose.onAllNodesWithText("Код не сохранён на телефоне").assertCountEquals(0)
    }

    @Test fun heldOldCodeCannotAttachToNewEqualDetailsOrShowADiskFailure() {
        openScreen()
        awaitCompletePassport()
        holdRetry = true
        oldRetryCode = "1111"
        holdFreshCode = true
        pumpPollPeriod()
        compose.waitUntil(15000) { settle(); retryHeld.count == 0L }
        val heldDetails = requireNotNull(OfflineTripSnapshots.projection(42))

        // A genuine new screen poll replaces the proof, even though every DTO field is equal.
        pumpPollPeriod()
        compose.waitUntil(15000) { settle(); freshCodeHeld.count == 0L }
        val fresh = requireNotNull(currentTripPrivateDetails(42))
        assertEquals(heldDetails.details, fresh)
        val latest = requireNotNull(OfflineTripSnapshots.projection(42))
        assertNotEquals(heldDetails.proof.request.ticket, latest.proof.request.ticket)
        assertNull(OfflineTripSnapshots.currentBoardingCode(42)?.takeIf { it.isNotBlank() })
        assertNull(TripPassStore.load(context, 42)?.takeIf { it.boardingCode.isNotBlank() })
        compose.onAllNodesWithText("Код не сохранён на телефоне").assertCountEquals(0)

        releaseRetry.countDown()
        assertTrue(retryReturned.await(5, TimeUnit.SECONDS))
        repeat(20) { settle(); Thread.sleep(10) }
        assertEquals(1L, releaseFreshCode.count)
        assertNull(OfflineTripSnapshots.currentBoardingCode(42)?.takeIf { it.isNotBlank() })
        assertNull(TripPassStore.load(context, 42)?.takeIf { it.boardingCode.isNotBlank() })
        compose.onAllNodesWithText("1111").assertCountEquals(0)
        compose.onAllNodesWithText("Код не сохранён на телефоне").assertCountEquals(0)

        releaseFreshCode.countDown()
        assertTrue(freshCodeReturned.await(5, TimeUnit.SECONDS))
        awaitCompletePassport()
        assertEquals("5678", OfflineTripSnapshots.currentBoardingCode(42))
        compose.onAllNodesWithText("Код не сохранён на телефоне").assertCountEquals(0)
    }

    @Test fun refreshedCodeHeldAcrossAccountSwitchCannotSavePreviousPassengerPassport() {
        openScreen()
        awaitCompletePassport()
        holdRetry = true
        pumpPollPeriod()
        compose.waitUntil(15000) { settle(); retryHeld.count == 0L }
        compose.runOnIdle { ApiClient.saveToken(TripPassAuthorityFixture.jwt(8)) }
        releaseRetry.countDown()
        assertTrue(retryReturned.await(5, TimeUnit.SECONDS))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) { settle(); Thread.sleep(10) }
        compose.waitForIdle()
        assertNull(OfflineTripSnapshots.currentBoardingCode(42))
        assertNull(OfflineTripSnapshots.privatePass(42))
        assertNull(TripPassStore.load(context, 42))
        compose.onAllNodesWithText("Код не сохранён на телефоне").assertCountEquals(0)
    }

    @Test fun refreshedCodeHeldAcrossTerminalCannotRestoreOrSaveClosedTrip() {
        openScreen()
        awaitCompletePassport()
        holdRetry = true
        pumpPollPeriod()
        compose.waitUntil(15000) { settle(); retryHeld.count == 0L }
        status = "done"
        runBlocking { ApiClient.getTripState(42).getOrThrow() }
        val terminal = requireNotNull(OfflineTripSnapshots.terminal(42))
        compose.runOnIdle {
            acceptedTerminalHint.value = ActiveTripTerminalHint(42, terminal.status, terminal.role, terminal.inTransit)
        }
        releaseRetry.countDown()
        assertTrue(retryReturned.await(5, TimeUnit.SECONDS))
        compose.waitUntil(15000) {
            settle()
            compose.onAllNodesWithTag("rideshareStar4").fetchSemanticsNodes().isNotEmpty()
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) { settle(); Thread.sleep(10) }
        compose.waitForIdle()
        assertFalse(OfflineTripSnapshots.allowsLive(42))
        assertNull(OfflineTripSnapshots.currentBoardingCode(42))
        assertNull(OfflineTripSnapshots.privatePass(42))
        assertNull(TripPassStore.load(context, 42))
        compose.onAllNodesWithText("Код не сохранён на телефоне").assertCountEquals(0)
    }

    @Test fun lateAcceptedInTransitCancellationKeepsMountedPassengerOnHelp() {
        status = "onboard"
        openScreen()
        awaitCompletePassport()
        val owner = OfflineTripSnapshots.currentOwner()
        val rolesBeforeTerminal = roleCalls.get()
        status = "cancelled"
        cancelledInTransit = true
        runBlocking { ApiClient.getTripState(42).getOrThrow() }
        val accepted = requireNotNull(OfflineTripSnapshots.terminal(42))
        assertEquals(owner, accepted.owner)
        assertTrue(accepted.inTransit)
        assertEquals(rolesBeforeTerminal + 1, roleCalls.get())
        compose.runOnIdle {
            acceptedTerminalHint.value = ActiveTripTerminalHint(42, accepted.status, accepted.role, accepted.inTransit)
        }
        compose.waitUntil(15000) {
            settle()
            compose.onAllNodesWithTag("cancelledInTransit").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Поездку отменили в пути").assertIsDisplayed()
        assertEquals("Help must not depend on a later child ROLE poll", rolesBeforeTerminal + 1, roleCalls.get())
        assertEquals("Terminal cleanup must keep the passenger on help", 0, tripEnds.get())
        assertFalse(OfflineTripSnapshots.allowsLive(42))
        assertNull(OfflineTripSnapshots.currentBoardingCode(42))
    }

    @Test fun lateAuthenticatedTerminalHintReplacesTheAlreadyMountedActiveScreen() {
        openScreen()
        awaitCompletePassport()
        status = "done"
        runBlocking { ApiClient.getTripState(42).getOrThrow() }
        assertEquals("done", OfflineTripSnapshots.terminal(42)?.status)
        compose.runOnIdle { acceptedTerminalHint.value = ActiveTripTerminalHint(42, "done", "passenger") }
        compose.waitUntil(15000) {
            settle()
            requests.contains("GET /trips/42/receipt") &&
                compose.onAllNodesWithTag("rideshareStar4").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithText("Назови код водителю перед посадкой").assertCountEquals(0)
        assertFalse(OfflineTripSnapshots.allowsLive(42))
    }
}
