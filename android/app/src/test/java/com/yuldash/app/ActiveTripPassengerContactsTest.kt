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
import com.yuldash.app.data.TripPassStore
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
    private val retryHeld = CountDownLatch(1)
    private val releaseRetry = CountDownLatch(1)
    private val retryReturned = CountDownLatch(1)
    private val firstDetailsHeld = CountDownLatch(1)
    private val releaseFirstDetails = CountDownLatch(1)
    private val firstDetailsReturned = CountDownLatch(1)
    private val historyHeld = CountDownLatch(1)
    private val releaseHistory = CountDownLatch(1)
    private val firstRoleHeld = CountDownLatch(1)
    private val releaseFirstRole = CountDownLatch(1)
    private val roleCalls = AtomicInteger()
    private val detailsCalls = AtomicInteger()
    private val detailsStatuses = CopyOnWriteArrayList<String>()
    private lateinit var server: MockWebServer
    @Volatile private var role = "passenger"
    @Volatile private var status = "confirmed"
    @Volatile private var unlocked = true
    @Volatile private var codeUnavailable = false
    @Volatile private var holdRetry = false
    @Volatile private var detailsUnavailable = false
    @Volatile private var holdFirstDetails = false
    @Volatile private var holdHistory = false
    @Volatile private var holdFirstRole = false
    private val phone = "+7 900 111-22-33"
    private val pickup = "У ТЦ «Мега», главный вход"
    private var alightingNote = ""
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
        ApiClient.logout() // Без настоящего входа: живой сокет чата этому экрану не нужен.
        TripPassStore.initStores(MemoryDiskPreferences(), MemoryDiskPreferences())
        assertNull(TripPassStore.load(context, 42))
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
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
                    if (request.path == "/bookings/42/role" && roleCalls.incrementAndGet() == 1 && holdFirstRole) {
                        firstRoleHeld.countDown()
                        if (!releaseFirstRole.await(30, TimeUnit.SECONDS)) return MockResponse().setResponseCode(503)
                    }
                    if (request.path == "/bookings/42/boarding-code") {
                        if (codeUnavailable) return MockResponse().setResponseCode(503).setBody("{}")
                        if (holdRetry) {
                            retryHeld.countDown()
                            if (!releaseRetry.await(30, TimeUnit.SECONDS)) return MockResponse().setResponseCode(503)
                            retryReturned.countDown()
                        }
                    }
                    if (request.path == "/bookings/42/details") {
                        val index = detailsCalls.incrementAndGet()
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
                        detailsStatuses += status
                        return MockResponse().setHeader("Content-Type", "application/json").setBody(details())
                    }
                    if (request.path == "/bookings/42/messages" && holdHistory) {
                        historyHeld.countDown()
                        if (!releaseHistory.await(30, TimeUnit.SECONDS)) return MockResponse().setResponseCode(503)
                    }
                    val body = when (request.path) {
                        "/bookings/42/role" -> """{"role":"$role","status":"$status","driver_phase":"waiting"}"""
                        "/bookings/42/messages" -> """{"items":[]}"""
                        "/bookings/42/boarding-code" -> """{"code":"5678"}"""
                        "/bookings/42/details" -> details()
                        else -> return MockResponse().setResponseCode(404).setBody("{}")
                    }
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 45000
    }

    @After fun cleanup() {
        releaseRetry.countDown()
        releaseFirstDetails.countDown()
        releaseHistory.countDown()
        releaseFirstRole.countDown()
        releaseSecondCity.countDown()
        releaseFirstCity.countDown()
        compose.runOnIdle { mounted.value = false }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        ApiClient.logout()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        TripPassStore.initStores(MemoryDiskPreferences(), null)
        server.shutdown()
    }

    /** Сервер открывает связь только участнику подтверждённой брони — так и в bookings.py. */
    private fun details(): String = JSONObject()
        .put("booking_id", 42).put("ride_id", 17).put("role", role).put("status", status)
        .put("contact_unlocked", unlocked).put("from_city", "Город А").put("to_city", "Город Б")
        .put("depart_at", "2031-02-03T09:00:00").put("price", 700).put("seats", 1)
        .put("driver_name", "Айдар").put("driver_car", "Lada Vesta").put("passenger_name", "Гульнара")
        .put("passenger_phone", if (role == "driver" && unlocked) passengerPhone else "")
        .put("driver_phone", if (unlocked) phone else "")
        .put("driver_plate", if (unlocked) "А123ВС 102" else "")
        .put("driver_car_color", if (unlocked) "белая" else "")
        .put("pickup", if (unlocked) pickup else "")
        .put("alight_note", alightingNote)
        .toString()

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
        val point = mutableStateOf(com.yuldash.app.data.parseBookingDetailsDto(JSONObject("""{
            "booking_id":42,"role":"passenger","status":"confirmed",
            "alight_lat":52.7,"alight_lng":58.6,
            "alight_landmark_ru":"у школы","alight_landmark_ba":"мәктәп янында","alight_note":"синие ворота"
        }""")))
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
        compose.runOnIdle { point.value = point.value.copy(alightLandmarkBa = "") }
        compose.onNodeWithText("у школы · синие ворота").assertIsDisplayed()
        compose.runOnIdle { point.value = point.value.copy(alightLandmarkRu = "", alightNote = "") }
        compose.onNodeWithText("Карталағы нөктә").assertIsDisplayed()
        compose.runOnIdle { point.value = point.value.copy(role = "driver") }
        compose.onNodeWithText("Төшөү урыны").assertDoesNotExist()
        compose.runOnIdle { point.value = point.value.copy(role = "passenger", status = "done") }
        compose.onNodeWithText("Төшөү урыны").assertIsDisplayed()
        compose.runOnIdle { point.value = point.value.copy(status = "cancelled") }
        compose.onNodeWithText("Төшөү урыны").assertIsDisplayed()
        compose.runOnIdle { point.value = point.value.copy(status = "confirmed", alightLat = null, alightLng = null) }
        compose.onNodeWithText("Төшөү урыны").assertDoesNotExist()
    }

    @Test fun ownPickupDoesNotNeedUnlockedContactsAndOnboardCanEditOnlyDropoff() {
        val point = mutableStateOf(com.yuldash.app.data.parseBookingDetailsDto(JSONObject("""{
            "booking_id":42,"role":"passenger","status":"pending","contact_unlocked":false,
            "meet_note":"Моя посадка","alight_note":"Моя высадка"
        }""")))
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) { PassengerMeetingPoints(point.value) {} }
        }
        compose.onNodeWithText("Моя посадка").assertIsDisplayed()
        compose.onNodeWithText("Моя высадка").assertIsDisplayed()
        compose.onNodeWithText("Изменить место посадки").assertIsEnabled()
        compose.onNodeWithText("Изменить место высадки").assertIsEnabled()
        compose.runOnIdle { point.value = point.value.copy(status = "onboard") }
        compose.onNodeWithText("Моя высадка").assertIsDisplayed()
        compose.onNodeWithText("Изменить место посадки").assertDoesNotExist()
        compose.onNodeWithText("Изменить место высадки").assertIsEnabled()
        compose.runOnIdle { point.value = point.value.copy(status = "done") }
        compose.onNodeWithText("Моя посадка").assertIsDisplayed()
        compose.onNodeWithText("Моя высадка").assertIsDisplayed()
        compose.onNodeWithText("Изменить место высадки").assertDoesNotExist()
    }

    @Test fun meetingEditorKeepsFailedDraftAndClosesOnlyAfterSuccess() {
        val d = com.yuldash.app.data.parseBookingDetailsDto(JSONObject("""{
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
        compose.waitUntil(15000) {
            settle()
            TripPassStore.load(context, 42)?.driverPhone == phone
        }
        val saved = requireNotNull(TripPassStore.load(context, 42))
        assertEquals("А123ВС 102", saved.driverPlate)
        assertEquals(pickup, saved.pickup)
        assertEquals("5678", saved.boardingCode)
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
        compose.runOnIdle { ApiClient.saveToken("local-account-B") }
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
        scrollTo("Повторить загрузку кода")
        compose.onNodeWithText("Повторить загрузку кода").performClick()
        compose.waitUntil(15000) { settle(); retryHeld.count == 0L }
        awaitConfirmedRole()
        status = "onboard"
        pumpPollPeriod()
        compose.waitUntil(15000) {
            settle()
            detailsStatuses.lastOrNull() == "onboard"
        }
        releaseRetry.countDown()
        awaitCompletePassport()
        assertEquals(listOf("confirmed", "onboard"), detailsStatuses.toList())
        assertEquals("Код не запрашивается заново при обновлении деталей", 2,
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
        releaseFirstDetails.countDown()
        assertTrue(firstDetailsReturned.await(5, TimeUnit.SECONDS))
        pumpPollPeriod()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).performScrollToIndex(0)
        compose.onAllNodesWithText("Не удалось загрузить данные поездки").assertCountEquals(0)
        assertEquals("Поздний отказ не запускает повтор после успешной загрузки", 2, detailsCalls.get())
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
        openScreen()
        compose.waitUntil(15000) {
            settle()
            requests.contains("GET /bookings/42/details") &&
                runCatching { scrollTo(phone) }.isSuccess
        }
        compose.onNodeWithText(phone).assertIsDisplayed()
        compose.waitUntil(15000) {
            settle()
            compose.onAllNodesWithText("Повторить загрузку кода").fetchSemanticsNodes().isNotEmpty()
        }
        assertNull(TripPassStore.load(context, 42))
        assertEquals(1, requests.count { it == "GET /bookings/42/boarding-code" })
    }

    private fun openScreen() {
        mounted.value = true
        compose.setContent {
            if (mounted.value) {
                CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                    // Без Ride и без координат маршрута — настоящая MapKit в тесте не поднимается.
                    ActiveTripScreen(ride = null, contacts = emptyList(), bookingId = 42,
                        onBack = {}, onTripEnd = {}, onSos = {})
                }
            }
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
}
