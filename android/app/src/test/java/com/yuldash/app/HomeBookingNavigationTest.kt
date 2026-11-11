package com.yuldash.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.OfflineTripSnapshots
import com.yuldash.app.data.TripPassStore
import com.yuldash.app.data.awaitOfflineWriteSnapshotReset02
import com.yuldash.app.data.ChatSocket
import com.yuldash.app.data.SnapshotWiringCleanupGuard
import com.yuldash.app.data.finishOfflineWriteFixture02
import com.yuldash.app.data.poisonOfflinePassportFixture03
import kotlinx.coroutines.Job
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import java.net.InetAddress
import com.yuldash.app.ui.theme.YuldashTheme
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Full App/Home UI and actual Activity saved-state. Synthetic accounts/local HTTP only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeBookingNavigationTest {
    companion object { private val cleanupGuard = SnapshotWiringCleanupGuard() }
    private val requestJobs = RequestJobs()
    private var setupEntered = false
    private var serverStarted = false
    private var sessionInstalled = false
    private var contentSet = false
    private val ownTrace: (String) -> Unit = {
        if (it.startsWith("вошли в вызов ")) requestJobs.record()
    }
    @get:Rule val compose = createComposeRule(effectContext = requestJobs)
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val mounted = mutableStateOf(true)
    private val tokenA = "header.eyJzdWIiOiIxMSJ9.signature"
    private val tokenB = "header.eyJzdWIiOiIxMiJ9.signature"
    private var actorCreated = false
    private val actor by lazy {
        Robolectric.buildActivity(MainActivity::class.java).create().also { actorCreated = true }
    }
    private lateinit var vm: YuldashViewModel
    private lateinit var server: MockWebServer
    private val records = CopyOnWriteArrayList<Triple<String,String,String?>>()
    private val bodies = CopyOnWriteArrayList<String>()
    private val started = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val dispatched = CountDownLatch(1)
    private var heldPath: String? = null
    private var existing = false
    @Volatile private var status = "pending"
    private var withResponses = false
    private var accepted = false
    @Before fun setup() {
        cleanupGuard.requireHealthy()
        check(ApiClient.testTrace == null)
        setupEntered = true
        ApiClient.resetForTest()
        context.getSharedPreferences("yuldash_prefs",0).edit().clear()
            .putBoolean("onboarding_completed",false).putString("mode_last","pooling")
            .putBoolean("mode_hint_shown",true).commit()
        server = MockWebServer().apply {
            dispatcher = object: Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    records += Triple(request.method!!,path,request.getHeader("Authorization"))
                    // This fixture's bookings belong to A. Match backend booking_and_ride_for_user.
                    if (request.getHeader("Authorization") == "Bearer $tokenB") {
                        if (path.startsWith("/bookings/42/") || path.startsWith("/trips/42/"))
                            return json("{}", 403)
                        if (path == "/bookings/mine") return json("""{"items":[]}""")
                    }
                    val bookingBody = if (request.method == "POST" && path == "/bookings") request.body.readUtf8().also { bodies += it } else null
                    if (path == heldPath) {
                        started.countDown(); assertTrue(release.await(35,TimeUnit.SECONDS)); dispatched.countDown()
                    }
                    return json(when {
                        path == "/rides/near" -> """{"items":[{"id":7,"from_city":"Пункт А","to_city":"Пункт Б","depart_at":"2030-01-02T10:00:00+03:00","seats_total":3,"seats_left":3,"price":750,"category":"regular","driver_id":9,"driver_name":"Тестовый водитель","driver_verified":true}],"count":1}"""
                        path == "/rides/7" -> """{"id":7,"from_city":"Пункт А","to_city":"Пункт Б","depart_at":"2030-01-02T10:00:00+03:00","seats_total":3,"seats_left":3,"price":750,"category":"regular","driver_id":9,"driver_name":"Тестовый водитель","driver_verified":true}"""
                        path == "/rides/9" -> """{"id":9,"from_city":"Баймак","to_city":"Сибай","depart_at":"2030-01-03T10:00:00+03:00","seats_total":4,"seats_left":4,"price":350,"category":"regular","driver_id":10,"driver_name":"Другой водитель","driver_verified":true}"""
                        request.method == "POST" && path == "/bookings" -> {
                            existing = true
                            val rideId = JSONObject(requireNotNull(bookingBody)).getInt("ride_id")
                            JSONObject().put("id", if (rideId == 9) 46 else 42).put("ride_id", rideId).put("status", status).toString()
                        }
                        path == "/bookings/46/details" -> """{"booking_id":46,"ride_id":9,"role":"passenger","status":"pending","from_city":"Баймак","to_city":"Сибай","depart_at":"2030-01-03T10:00:00+03:00","seats":1,"price":350,"contact_unlocked":false}"""
                        path == "/bookings/mine" && accepted -> """{"items":[{"id":44,"ride_id":8,"status":"confirmed","seats":1,"price":900,"from_city":"Новый пункт А","to_city":"Новый пункт Б","driver_name":"Новый водитель"}]}"""
                        path == "/bookings/mine" && existing -> """{"items":[{"id":42,"ride_id":7,"status":"$status","seats":1,"price":750,"from_city":"Пункт А","to_city":"Пункт Б","driver_name":"Тестовый водитель"}]}"""
                        path == "/requests/mine" && withResponses -> """{"items":[{"id":43,"from_city":"Новый пункт А","to_city":"Новый пункт Б","seats":1,"max_price":900,"status":"active","category":"regular"}]}"""
                        path == "/requests/43/responses" -> """{"items":[{"id":61,"driver_id":10,"driver_name":"Новый водитель","price":900,"current_price":900,"status":"pending","can_accept":true,"last_offer_by":"driver"}]}"""
                        path == "/responses/61/accept" -> { accepted=true; """{"booking_id":44}""" }
                        path == "/bookings/42/details" -> """{"booking_id":42,"ride_id":7,"role":"passenger","status":"$status","from_city":"Пункт А","to_city":"Пункт Б","depart_at":"2030-01-02T10:00:00+03:00","seats":1,"price":750,"contact_unlocked":${status == "confirmed"}}"""
                        path == "/bookings/42/boarding-code" && status == "confirmed" -> """{"code":"1234"}"""
                        path == "/bookings/44/role" && accepted -> """{"role":"passenger","status":"confirmed"}"""
                        path == "/bookings/44/details" && accepted -> """{"booking_id":44,"ride_id":8,"role":"passenger","status":"confirmed","from_city":"Новый пункт А","to_city":"Новый пункт Б","depart_at":"2030-01-02T10:00:00+03:00","seats":1,"price":900,"contact_unlocked":true}"""
                        path == "/bookings/44/boarding-code" && accepted -> """{"code":"1234"}"""
                        path == "/bookings/42/role" -> """{"role":"passenger","status":"$status"}"""
                        path == "/trips/42/receipt" -> """{"booking_id":42,"ride_id":7,"role":"passenger","from_city":"Пункт А","to_city":"Пункт Б","depart_at":"2030-01-02T10:00:00+03:00","seats":1,"amount":400,"pay_method":"cash","paid":false,"driver_name":"ReceiptDriver42","counterparty_name":"ReceiptDriver42","my_stars":0,"my_rating_tags":""}"""
                        path == "/trips/42/tip" -> """{"already_thanked":false}"""
                        path == "/bookings/42/cancel" -> """{"status":"cancelled"}"""
                        else -> """{"items":[],"role":"passenger"}"""
                    },if(path=="/instant/orders/active") 503 else 200)
                }
            }
            start(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0)
        }
        serverStarted = true
        ApiClient.testBaseUrl = server.url("/").newBuilder().host("127.0.0.1").build().toString().trimEnd('/')
        ApiClient.testTimeoutMs = 60000
        ApiClient.testTrace = ownTrace
        ApiClient.init(context)
        awaitOfflineWriteSnapshotReset02()
        ApiClient.saveToken(tokenA)
        sessionInstalled = true
        vm=ViewModelProvider(actor.get())[YuldashViewModel::class.java]
        vm.screen.value=Screen.Home; vm.startHomeTab.value=HomeTab.Map; vm.persistNav()
    }
    private fun json(body:String,code:Int=200)=MockResponse().setResponseCode(code).setHeader("Content-Type","application/json").setBody(body)
    private fun pump(ms:Long=500) { repeat((ms/100).toInt()) { compose.mainClock.advanceTimeBy(100); Shadows.shadowOf(Looper.getMainLooper()).idle(); Snapshot.sendApplyNotifications(); Thread.sleep(10) }; compose.waitForIdle() }
    private fun await(check:()->Boolean)=compose.waitUntil(15000) { compose.mainClock.advanceTimeBy(100); Shadows.shadowOf(Looper.getMainLooper()).idle(); Snapshot.sendApplyNotifications(); check() }
    private fun mount(saver: StateRestorationTester? = null) {
        contentSet = true
        compose.mainClock.autoAdvance = false
        val body: @androidx.compose.runtime.Composable () -> Unit = {
            if (mounted.value) CompositionLocalProvider(LocalViewModelStoreOwner provides actor.get(), LocalPoolingNativeMapEnabled provides false) {
                YuldashTheme { YuldashApp() }
            }
        }
        if (saver == null) compose.setContent(body) else saver.setContent(body)
        pump()
    }
    private fun openPublicRide(id: Int) {
        compose.runOnIdle {
            MainActivity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java).apply { isAccessible = true }
                .invoke(actor.get(), Intent(Intent.ACTION_VIEW, Uri.parse("https://yulbash.ru/r/" + id)))
        }
        await { vm.screen.value == Screen.Booking && vm.selectedRide.value?.id == id.toString() && vm.pendingPublicRideNavigation.value == null }
        pump(1500)
        assertNull(vm.activeBookingId.value)
    }
    private fun chooseTwoSeats() {
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasContentDescription("Больше мест"))
        compose.onNodeWithContentDescription("Больше мест").performClick()
        pump()
        compose.onNodeWithText("Забронировать 2 места").assertIsEnabled()
    }
    private fun click(text:String) { await { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }; compose.onNodeWithText(text).performClick(); pump() }
    private fun capture(text:String):()->Boolean { await { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }; return compose.onNodeWithText(text).fetchSemanticsNode().config[SemanticsActions.OnClick].action!! }
    private fun choosePublicRide() {
        mount(); assertNull(vm.selectedRide.value); assertNull(vm.activeBookingId.value)
        await { records.any { it.second=="/rides/near" } }
        val scroll=compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
        repeat(12) { if(compose.onAllNodesWithText("Поехать").fetchSemanticsNodes().isEmpty()) { scroll.performTouchInput { swipeUp() }; pump() } }
        compose.onNodeWithText("Поехать").performClick(); pump()
        assertEquals(Screen.Booking,vm.screen.value); assertEquals("7",vm.selectedRide.value?.id)
    }
    private fun openExisting() {
        existing=true; mount(); click("Поездки"); click(if(status=="pending") "Подробнее" else "Открыть поездку")
        await { vm.activeBookingId.value == 42 && vm.pendingBookingNavigation.value == null }
        assertEquals(42,vm.activeBookingId.value)
    }
    private fun back() { compose.onNodeWithContentDescription("Назад").performClick(); pump() }
    private fun raw(type:String,id:Int=44) { MainActivity::class.java.getDeclaredMethod("onNewIntent",Intent::class.java).apply { isAccessible=true }.invoke(actor.get(),Intent(context,MainActivity::class.java).putExtra("type",type).putExtra("id",id.toString()).putExtra("recipient_user_id", "11")) }
    private fun deliver(type:String) { compose.runOnIdle { raw(type) }; await { vm.pendingScreenNavigation.value==null }; pump() }
    private fun heldCreate() { heldPath="/bookings"; choosePublicRide(); click("Забронировать место"); await { started.count==0L } }
    private fun finishOld() { release.countDown(); assertTrue(dispatched.await(8,TimeUnit.SECONDS)); pump(1500) }
    private fun posts(path:String)=records.count { it.first=="POST" && it.second==path }
    @After fun cleanup() {
        try {
            cleanupGuard.requireHealthy()
            if (!setupEntered) return
            release.countDown()
            if (contentSet) {
                compose.runOnIdle { mounted.value = false }
                pump(300)
            }
            if (actorCreated) {
                actor.get().viewModelStore.clear()
                actor.destroy()
            }
            if (::vm.isInitialized) vm.clearUserData()
            requestJobs.awaitCompleted(if (serverStarted) server.port else null)
            if (sessionInstalled) ApiClient.logout()
            finishOfflineWriteFixture02(cleanupGuard) {
                check(ApiClient.testTrace == null || ApiClient.testTrace === ownTrace)
                if (serverStarted) server.shutdown()
                ApiClient.resetForTest()
                ApiClient.testTimeoutMs = null
            }
        } catch (failure: Throwable) {
            poisonOfflinePassportFixture03(cleanupGuard, failure)
        } finally {
            if (ApiClient.testTrace === ownTrace) ApiClient.testTrace = null
        }
    }
    @Test fun otherPublicRideAfterBookingRemountStartsItsOwnSeatsAndSegment() {
        mount()
        openPublicRide(7)
        chooseTwoSeats()
        back()
        pump(1500) // the Booking holder saves A before a different public ride mounts
        openPublicRide(9)
        compose.onNodeWithText("Посадка: Баймак").assertExists()
        compose.onNodeWithText("Высадка: Сибай").assertExists()
        compose.onNodeWithText("Забронировать место").assertIsEnabled().performClick()
        await { posts("/bookings") == 1 && vm.activeBookingId.value == 46 }
        val sent = JSONObject(bodies.single())
        assertEquals(9, sent.getInt("ride_id"))
        assertEquals(1, sent.getInt("seats"))
        assertEquals("Баймак", sent.getString("pickup_stop"))
        assertEquals("Сибай", sent.getString("dropoff_stop"))
        assertFalse(sent.has("meeting"))
        assertFalse(sent.has("alighting"))
    }

    @Test fun samePublicRideKeepsDraftAfterReturnAndSavedStateRestore() {
        val saver = StateRestorationTester(compose)
        mount(saver)
        openPublicRide(9)
        chooseTwoSeats()
        back()
        pump(1500)
        openPublicRide(9)
        compose.onNodeWithText("Забронировать 2 места").assertIsEnabled()
        saver.emulateSavedInstanceStateRestore()
        pump(1500)
        compose.onNodeWithText("Забронировать 2 места").assertIsEnabled().performClick()
        await { posts("/bookings") == 1 }
        val sent = JSONObject(bodies.single())
        assertEquals(9, sent.getInt("ride_id"))
        assertEquals(2, sent.getInt("seats"))
        assertEquals("Баймак", sent.getString("pickup_stop"))
        assertEquals("Сибай", sent.getString("dropoff_stop"))
    }

    @Test fun publicRideDraftCannotCrossAccountBoundary() {
        mount()
        openPublicRide(9)
        chooseTwoSeats()
        back()
        pump(1500)
        compose.runOnIdle { ApiClient.saveToken(tokenB) }
        pump()
        openPublicRide(9)
        assertEquals(12, OfflineTripSnapshots.currentOwner()?.userId)
        compose.onNodeWithText("Забронировать место").assertIsEnabled()
        compose.onAllNodesWithText("Забронировать 2 места").assertCountEquals(0)
        assertEquals(0, posts("/bookings"))
    }

    @Test fun retainedDifferentRideSubmitCannotBookTheNewPublicRide() {
        mount()
        openPublicRide(7)
        chooseTwoSeats()
        val oldSubmit = capture("Забронировать 2 места")
        back()
        pump(1500)
        compose.onAllNodesWithText("Забронировать 2 места").assertCountEquals(0)
        openPublicRide(9)
        compose.onNodeWithText("Забронировать место").assertIsEnabled()
        compose.runOnIdle { oldSubmit() }
        pump(1500)
        assertEquals(0, posts("/bookings"))
        assertEquals("9", vm.selectedRide.value?.id)
        assertNull(vm.activeBookingId.value)
        compose.onNodeWithText("Забронировать место").assertIsEnabled()
    }

    @Test fun publicHomeCreatesOnePendingBookingWithExactRide() {
        choosePublicRide(); val confirm=capture("Забронировать место")
        compose.runOnIdle { confirm(); confirm() }; await { vm.activeBookingId.value==42 }; pump()
        assertEquals(Screen.Booking,vm.screen.value); assertNull(vm.activeTrip.value); assertEquals(1,posts("/bookings"))
        assertEquals(7,JSONObject(bodies.single()).getInt("ride_id")); compose.onNodeWithText("Отменить бронь").assertExists()
    }
    @Test fun lateCreateAfterBackDoesNotReopenBooking() { heldCreate(); back(); finishOld(); assertEquals(Screen.Home,vm.screen.value); assertNull(vm.activeBookingId.value) }
    @Test fun lateCreateCannotReplaceNewerAdDestination() { heldCreate(); deliver("ad"); finishOld(); assertEquals(Screen.AdsCabinet,vm.screen.value); assertNull(vm.activeBookingId.value) }
    @Test fun lateCreateCannotReplaceNewerRequestDestination() { heldCreate(); deliver("request"); finishOld(); assertEquals(Screen.RequestResponses,vm.screen.value); assertEquals(44,vm.responsesRequestId.value); assertNull(vm.activeBookingId.value) }
    @Test fun retainedConfirmAfterBackCannotCreateBooking() { choosePublicRide(); val confirm=capture("Забронировать место"); back(); compose.runOnIdle { confirm() }; pump(1500); assertEquals(0,posts("/bookings")); assertEquals(Screen.Home,vm.screen.value) }
    @Test fun retainedConfirmCannotCreateUnderNextAccount() { choosePublicRide(); val confirm=capture("Забронировать место"); compose.runOnIdle { ApiClient.saveToken(tokenB); confirm() }; pump(1500); assertEquals(0,posts("/bookings")) }
    @Test fun retainedCancelCannotCancelUnderNextAccount() { openExisting(); click("Отменить бронь"); val cancel=capture("Да, отменить"); compose.runOnIdle { ApiClient.saveToken(tokenB); cancel() }; pump(1500); assertEquals(0,posts("/bookings/42/cancel")) }
    @Test fun lateCancellationCannotReplaceNewerRequest() { heldPath="/bookings/42/cancel"; openExisting(); click("Отменить бронь"); click("Да, отменить"); await { started.count==0L }; deliver("request"); finishOld(); assertEquals(Screen.RequestResponses,vm.screen.value); assertEquals(44,vm.responsesRequestId.value); assertEquals(42,vm.activeBookingId.value) }
    @Test fun doubleCancelInSameFrameSendsOneMutation() { heldPath="/bookings/42/cancel"; openExisting(); click("Отменить бронь"); val cancel=capture("Да, отменить"); compose.runOnIdle { cancel(); cancel() }; await { started.count==0L }; pump(); assertEquals(1,posts("/bookings/42/cancel")); finishOld() }
    @Test fun homeExistingSelectionPersistsBeforeNextComposeEffect() {
        existing=true; mount(); click("Поездки"); val open=capture("Подробнее"); val bundle=Bundle()
        compose.runOnIdle { open(); actor.saveInstanceState(bundle) }
        val next=Robolectric.buildActivity(MainActivity::class.java).create(bundle)
        try { val restored=ViewModelProvider(next.get())[YuldashViewModel::class.java]; assertEquals(Screen.Booking,restored.screen.value); assertEquals(42,restored.activeBookingId.value); assertEquals(HomeTab.Rides,restored.startHomeTab.value); assertTrue(restored.navHistory.contains(Screen.Home)) } finally { next.destroy() }
    }
    @Test fun retainedHomePrivateRowCannotOpenUnderNextAccount() {
        existing=true; mount(); click("Поездки"); val open=capture("Подробнее")
        compose.runOnIdle { ApiClient.saveToken(tokenB); open(); assertEquals(Screen.Home,vm.screen.value); assertNull(vm.activeBookingId.value) }; pump()
    }
    @Test fun homeConfirmedTripAndBackKeepRidesTab() { status="confirmed"; openExisting(); await { vm.screen.value == Screen.ActiveTrip && vm.activeTrip.value?.id == "7" }; assertEquals(Screen.ActiveTrip,vm.screen.value); assertEquals(bookingLinkStubRide(42),vm.selectedRide.value); assertEquals("7",vm.activeTrip.value?.id); back(); assertEquals(Screen.Home,vm.screen.value); assertEquals(HomeTab.Rides,vm.startHomeTab.value) }

    @Test fun myTripsWithDifferentBookingAndRideIdsAcceptRemoteReceiptAndShowRating() {
        status = "confirmed"
        openExisting()
        await { vm.screen.value == Screen.ActiveTrip && vm.activeTrip.value?.id == "7" }
        assertEquals(42, vm.activeBookingId.value)
        assertEquals(bookingLinkStubRide(42), vm.selectedRide.value)
        assertTrue(records.any { it.first == "GET" && it.second == "/bookings/42/role" && it.third == "Bearer $tokenA" })
        status = "done"
        compose.waitUntil(15_000) {
            compose.mainClock.advanceTimeBy(100)
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
            Snapshot.sendApplyNotifications()
            OfflineTripSnapshots.terminal(42)?.status == "done"
        }
        await { records.any { it.first == "GET" && it.second == "/trips/42/receipt" && it.third == "Bearer $tokenA" } }
        await { compose.onAllNodesWithText("Рәхмәт, ReceiptDriver42!").fetchSemanticsNodes().isNotEmpty() }
        val completedList = compose.onNodeWithTag("rideshareCompletedList")
        completedList.performScrollToNode(hasTestTag("rideshareStar4"))
        compose.onNodeWithTag("rideshareStar4").assertIsDisplayed()
        completedList.performScrollToNode(hasText("ReceiptDriver42", substring = true))
        compose.onAllNodesWithText("ReceiptDriver42", substring = true).onFirst().assertIsDisplayed()
        completedList.performScrollToNode(hasText("400", substring = true))
        compose.onAllNodesWithText("400", substring = true).onFirst().assertExists()
        compose.onAllNodesWithText("Не получилось загрузить детали. Основные данные поездки сохранены.").assertCountEquals(0)
        assertFalse(records.any { it.second == "/trips/7/receipt" })
        back()
        assertEquals(Screen.Home, vm.screen.value)
        assertEquals(HomeTab.Map, vm.startHomeTab.value)
        assertNull(vm.activeTrip.value)
        // The closed booking remains a history intent; it grants no live/private capability.
        assertEquals(42, vm.activeBookingId.value)
        assertFalse(OfflineTripSnapshots.allowsLive(42))
        assertNull(OfflineTripSnapshots.currentBoardingCode(42))
        assertNull(OfflineTripSnapshots.privatePass(42))
    }

    @Test fun retainedConfirmedTripActionCannotOpenUnderNextAccount() {
        existing = true
        status = "confirmed"
        mount()
        click("Поездки")
        val open = capture("Открыть поездку")
        val roleReads = records.count { it.second == "/bookings/42/role" }
        compose.runOnIdle { ApiClient.saveToken(tokenB); open() }
        pump(1500)
        assertEquals(roleReads, records.count { it.second == "/bookings/42/role" })
        assertTrue(vm.screen.value != Screen.ActiveTrip)
        assertNull(vm.activeBookingId.value)
    }

    @Test fun retainedConfirmedTripActionAfterBackCannotReopenTrip() {
        existing = true
        status = "confirmed"
        mount()
        click("Поездки")
        val oldNodeId = compose.onNodeWithText("Открыть поездку").fetchSemanticsNode().id
        val open = capture("Открыть поездку")
        compose.runOnIdle { open() }
        await { vm.screen.value == Screen.ActiveTrip && vm.pendingBookingNavigation.value == null }
        // Finish the outgoing Home animation so this is a retained disposed callback, not its rebound live node.
        pump(1500)
        compose.onAllNodesWithText("Открыть поездку").assertCountEquals(0)
        back()
        assertEquals(Screen.Home, vm.screen.value)
        assertNotEquals("Back must create a new Home action node", oldNodeId,
            compose.onNodeWithText("Открыть поездку").fetchSemanticsNode().id)
        val roleReads = records.count { it.second == "/bookings/42/role" }
        compose.runOnIdle { open() }
        pump(1500)
        assertEquals(Screen.Home, vm.screen.value)
        assertEquals(roleReads, records.count { it.second == "/bookings/42/role" })
        assertEquals(HomeTab.Rides, vm.startHomeTab.value)
    }

    @Test fun heldConfirmedTripReadCannotOpenAfterAccountSwitch() {
        existing = true
        status = "confirmed"
        mount()
        click("Поездки")
        heldPath = "/bookings/42/role"
        click("Открыть поездку")
        await { started.count == 0L }
        compose.runOnIdle { ApiClient.saveToken(tokenB) }
        finishOld()
        assertEquals(12, OfflineTripSnapshots.currentOwner()?.userId)
        assertNull(vm.activeTrip.value)
        assertFalse(OfflineTripSnapshots.allowsLive(42))
        assertNull(OfflineTripSnapshots.privatePass(42))
        assertNull(OfflineTripSnapshots.currentBoardingCode(42))
        assertNull(currentTripPrivateDetails(42))
        assertNull(TripPassStore.load(context, 42))
        compose.onAllNodesWithText("Пункт А", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Пункт Б", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Тестовый водитель", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("1234").assertCountEquals(0)
        compose.onAllNodesWithText("Код посадки").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Позвонить").assertCountEquals(0)
        assertFalse(records.any { it.second == "/trips/42/receipt" })
        assertFalse(records.any { it.first == "POST" && it.second.startsWith("/bookings/42/") })
        println("HOME_HELD_AUTH roleOld=" + records.count { it.second == "/bookings/42/role" && it.third == "Bearer $tokenA" } +
            " roleNew=" + records.count { it.second == "/bookings/42/role" && it.third == "Bearer $tokenB" } +
            " detailsNew=" + records.count { it.second == "/bookings/42/details" && it.third == "Bearer $tokenB" } +
            " live=false private=false gps=false")
    }
    @Test fun acceptedResponseFromHomeReplacesPreviousBookingRide() {
        withResponses=true; openExisting(); back(); assertEquals("42",vm.selectedRide.value?.id)
        click("Заявка"); click("Посмотреть отклики"); click("Принять")
        await { vm.screen.value==Screen.ActiveTrip && vm.activeBookingId.value==44 }; pump(1500)
        assertEquals(1,posts("/responses/61/accept")); assertEquals(bookingLinkStubRide(44),vm.selectedRide.value)
        assertEquals("Новый пункт А",vm.activeTrip.value?.from); assertEquals("Новый пункт Б",vm.activeTrip.value?.to)
        assertEquals("8",vm.activeTrip.value?.id)
    }
    @Test fun acceptedResponseKeepsCurrentRouteAfterBackHome() = acceptedResponseRouteAfterBackHome()
    @Test fun acceptedZeroPriceKeepsCurrentRouteAfterBackHome() = acceptedResponseRouteAfterBackHome(0)
    private fun acceptedResponseRouteAfterBackHome(expectedPrice: Int = 900) {
        if (expectedPrice == 0) {
            val delegate = server.dispatcher
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val response = delegate.dispatch(request)
                    if (accepted && request.requestUrl!!.encodedPath == "/bookings/44/details") {
                        response.setBody(JSONObject(requireNotNull(response.getBody()).readUtf8()).put("price", 0).toString())
                    }
                    return response
                }
            }
        }
        val expectedOwner = requireNotNull(OfflineTripSnapshots.currentOwner())
        assertEquals(11, expectedOwner.userId)
        val expectedSession = ApiClient.queueSessionGeneration()
        fun currentRouteReady(): Boolean {
            val projection = OfflineTripSnapshots.projection(44) ?: return false
            val details = projection.details
            return OfflineTripSnapshots.currentOwner() == expectedOwner &&
                projection.owner == expectedOwner && expectedOwner.generation == expectedSession &&
                ApiClient.isCurrentSession(expectedSession) && details.bookingId == 44 &&
                details.rideId == 8 && details.role == "passenger" && details.status == "confirmed" &&
                details.fromCity == "Новый пункт А" && details.toCity == "Новый пункт Б" &&
                OfflineTripSnapshots.freshDetails(details) && OfflineTripSnapshots.privateDetails(details) &&
                OfflineTripSnapshots.allowsLive(44)
        }
        withResponses = true
        openExisting()
        back()
        click("Заявка")
        click("Посмотреть отклики")
        click("Принять")
        await { vm.screen.value == Screen.ActiveTrip && vm.activeBookingId.value == 44 }
        assertEquals(1, posts("/responses/61/accept"))
        await { currentRouteReady() }
        pump(1500)
        compose.runOnIdle {
            assertTrue("Current accepted route must be ready before leaving ActiveTrip", currentRouteReady())
        }
        val routeText = "Новый пункт А → Новый пункт Б"
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasText(routeText))
        compose.onNodeWithText(routeText).assertIsDisplayed()
        back()
        if (vm.screen.value == Screen.RequestResponses) {
            assertEquals(Screen.RequestResponses, vm.screen.value)
            back()
        }
        assertEquals(Screen.Home, vm.screen.value)
        if (vm.startHomeTab.value != HomeTab.Map) click("Карта")
        compose.runOnIdle {
            assertEquals(Screen.Home, vm.screen.value)
            assertEquals(HomeTab.Map, vm.startHomeTab.value)
            assertTrue("Current owner and live route must survive Back to Home", currentRouteReady())
            assertEquals(44, vm.activeBookingId.value)
            val mapInput = requireNotNull(vm.activeTrip.value)
            assertEquals("Новый пункт А", mapInput.from)
            assertEquals("Новый пункт Б", mapInput.to)
            assertEquals("8", mapInput.id)
            assertEquals(expectedPrice, requireNotNull(currentTripPrivateDetails(44)).price)
            assertEquals(expectedPrice, mapInput.price)
            assertEquals(if (expectedPrice == 0) "0 ₽" else "900 ₽", kopToRub(rideshareRubToKop(mapInput.price)))
            assertEquals(bookingLinkStubRide(44), vm.selectedRide.value)
            if (expectedPrice == 0) {
                assertEquals(0, mapInput.copy(price = 900).currentBookingMapRide(44)?.price)
            }
        }
        assertEquals(1, posts("/responses/61/accept"))
    }
    @Test fun roleOnlyAndUnaccepted201CannotSupplyMapPriceOrSendMapperRequests() {
        status = "confirmed"
        val delegate = server.dispatcher
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                delegate.dispatch(request).also {
                    if (request.requestUrl!!.encodedPath == "/bookings/42/details") it.setResponseCode(201)
                }
        }
        lateinit var caller: Job
        val unaccepted = runBlocking {
            withContext(requestJobs) {
                caller = requireNotNull(coroutineContext[Job])
                assertNotNull(caller.parent)
                val dto = ApiClient.getBookingDetails(42).getOrThrow()
                assertEquals("confirmed", ApiClient.getTripState(42).getOrThrow().status)
                dto
            }
        }
        assertTrue(caller.isCompleted)
        assertTrue(OfflineTripSnapshots.allowsLive(42))
        assertEquals(750, unaccepted.price)
        listOf(unaccepted, unaccepted.copy()).forEach {
            assertFalse(OfflineTripSnapshots.freshDetails(it))
            assertFalse(OfflineTripSnapshots.privateDetails(it))
        }
        assertNull(currentTripPrivateDetails(42))
        assertEquals(listOf("GET" to "/bookings/42/details", "GET" to "/bookings/42/role"),
            records.map { it.first to it.second })
        val beforeMapper = records.toList()
        val candidate = bookingLinkStubRide(42)
        assertSame(candidate, candidate.currentBookingMapRide(42))
        assertEquals(0, candidate.price)
        assertEquals(beforeMapper, records.toList())
    }
    @Test fun homeRowSupersedesHeldPrivateNotification() {
        existing=true;mount();click("Поездки");val open=capture("Подробнее")
        heldPath="/bookings/44/role";compose.runOnIdle { raw("booking") };await { started.count==0L }
        compose.runOnIdle { open() };pump();finishOld()
        assertEquals(Screen.Booking,vm.screen.value);assertEquals(42,vm.activeBookingId.value)
        assertNull(vm.pendingBookingNavigation.value)
    }
    @Test fun guestHomeCanOpenPublicRideWithoutSendingPrivateBooking() {
        ApiClient.logout();choosePublicRide();click("Забронировать место");pump(1000)
        assertEquals(Screen.Login,vm.screen.value);assertEquals(0,posts("/bookings"));assertNull(vm.activeBookingId.value)
    }
    private fun markedPendingNotificationUsesServerStatus(language: AppLanguage) {
        compose.runOnIdle { vm.language.value = language }
        mount()
        val notification = Intent(context, MainActivity::class.java)
            .putExtra("type", "booking").putExtra("id", "42")
            .putExtra("recipient_user_id", "11")
            .putExtra(MainActivity.EXTRA_NAVIGATION_DELIVERY_ID, "pending-status-${language.name}")
        compose.runOnIdle {
            MainActivity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java)
                .apply { isAccessible = true }.invoke(actor.get(), notification)
        }
        await { vm.activeBookingId.value == 42 && vm.pendingBookingNavigation.value == null }
        pump()
        assertEquals(Screen.Booking, vm.screen.value)
        assertTrue(records.any { it.second == "/bookings/42/role" && it.third == "Bearer $tokenA" })
        compose.onNodeWithText(if (language == AppLanguage.Ru) "Отменить бронь" else "Бронде кире алыу")
            .assertExists().assertIsEnabled()
        assertEquals(0, posts("/bookings/42/cancel"))
    }
    @Test fun markedPendingNotificationShowsEnabledCancellationInRussian() = markedPendingNotificationUsesServerStatus(AppLanguage.Ru)
    @Test fun markedPendingNotificationShowsEnabledCancellationInBashkir() = markedPendingNotificationUsesServerStatus(AppLanguage.Ba)

    /** Observes actual API callers; never creates a Job or replaces a dispatcher. */
    private class RequestJobs : ThreadContextElement<Job?>, AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<RequestJobs>
        private val current = ThreadLocal<Job?>()
        private val started = java.util.concurrent.CopyOnWriteArrayList<Job>()
        @Volatile private var missingParent = false

        override fun updateThreadContext(context: CoroutineContext): Job? =
            current.get().also { current.set(context[Job]) }

        override fun restoreThreadContext(context: CoroutineContext, oldState: Job?) {
            current.set(oldState)
        }

        fun record() {
            current.get()?.let { job ->
                started += job
                val caller = job.parent
                if (caller == null) missingParent = true else started += caller
            }
        }

        fun awaitCompleted(port: Int?) = runBlocking {
            withTimeout(5_000) {
                check(!missingParent) { "Owned caller completion unavailable" }
                val field = ChatSocket::class.java.getDeclaredField("client\$delegate")
                    .apply { isAccessible = true }
                val lazyClient = field.get(null) as Lazy<*>
                val client = lazyClient.takeIf { it.isInitialized() }?.value as? okhttp3.OkHttpClient
                while (started.any { !it.isCompleted } || client?.dispatcher?.let {
                        it.runningCalls() + it.queuedCalls()
                    }.orEmpty().any { it.request().url.host == "127.0.0.1" && it.request().url.port == port }) {
                    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                    delay(10)
                }
            }
        }
    }
}
