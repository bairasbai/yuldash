package com.yuldash.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
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
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val mounted = mutableStateOf(true)
    private val tokenA = "header.eyJzdWIiOiIxMSJ9.signature"
    private val tokenB = "header.eyJzdWIiOiIxMiJ9.signature"
    private val actor by lazy { Robolectric.buildActivity(MainActivity::class.java).create() }
    private lateinit var vm: YuldashViewModel
    private lateinit var server: MockWebServer
    private val records = CopyOnWriteArrayList<Triple<String,String,String?>>()
    private val bodies = CopyOnWriteArrayList<String>()
    private val started = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val dispatched = CountDownLatch(1)
    private var heldPath: String? = null
    private var existing = false
    private var status = "pending"
    private var withResponses = false
    private var accepted = false
    @Before fun setup() {
        ApiClient.resetForTest(); ApiClient.init(context); ApiClient.saveToken(tokenA)
        context.getSharedPreferences("yuldash_prefs",0).edit().clear()
            .putBoolean("onboarding_completed",false).putString("mode_last","pooling")
            .putBoolean("mode_hint_shown",true).commit()
        server = MockWebServer().apply {
            dispatcher = object: Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    records += Triple(request.method!!,path,request.getHeader("Authorization"))
                    if (request.method == "POST" && path == "/bookings") bodies += request.body.readUtf8()
                    if (path == heldPath) {
                        started.countDown(); assertTrue(release.await(35,TimeUnit.SECONDS)); dispatched.countDown()
                    }
                    return json(when {
                        path == "/rides/near" -> """{"items":[{"id":7,"from_city":"Пункт А","to_city":"Пункт Б","depart_at":"2030-01-02T10:00:00+03:00","seats_total":3,"seats_left":3,"price":750,"category":"regular","driver_id":9,"driver_name":"Тестовый водитель","driver_verified":true}],"count":1}"""
                        request.method == "POST" && path == "/bookings" -> { existing=true; """{"id":42,"ride_id":7,"status":"$status"}""" }
                        path == "/bookings/mine" && accepted -> """{"items":[{"id":44,"ride_id":8,"status":"confirmed","seats":1,"price":900,"from_city":"Новый пункт А","to_city":"Новый пункт Б","driver_name":"Новый водитель"}]}"""
                        path == "/bookings/mine" && existing -> """{"items":[{"id":42,"ride_id":7,"status":"$status","seats":1,"price":750,"from_city":"Пункт А","to_city":"Пункт Б","driver_name":"Тестовый водитель"}]}"""
                        path == "/requests/mine" && withResponses -> """{"items":[{"id":43,"from_city":"Новый пункт А","to_city":"Новый пункт Б","seats":1,"max_price":900,"status":"active","category":"regular"}]}"""
                        path == "/requests/43/responses" -> """{"items":[{"id":61,"driver_id":10,"driver_name":"Новый водитель","price":900,"current_price":900,"status":"pending","can_accept":true,"last_offer_by":"driver"}]}"""
                        path == "/responses/61/accept" -> { accepted=true; """{"booking_id":44}""" }
                        path == "/bookings/42/details" -> """{"booking_id":42,"ride_id":7,"role":"passenger","status":"$status","from_city":"Пункт А","to_city":"Пункт Б","contact_unlocked":false}"""
                        path == "/bookings/42/role" -> """{"role":"passenger","status":"$status"}"""
                        path == "/bookings/42/cancel" -> """{"status":"cancelled"}"""
                        else -> """{"items":[],"role":"passenger"}"""
                    },if(path=="/instant/orders/active") 503 else 200)
                }
            }; start()
        }
        ApiClient.testBaseUrl=server.url("/").toString().trimEnd('/'); ApiClient.testTimeoutMs=60000
        vm=ViewModelProvider(actor.get())[YuldashViewModel::class.java]
        vm.screen.value=Screen.Home; vm.startHomeTab.value=HomeTab.Map; vm.persistNav()
    }
    private fun json(body:String,code:Int=200)=MockResponse().setResponseCode(code).setHeader("Content-Type","application/json").setBody(body)
    private fun pump(ms:Long=500) { repeat((ms/100).toInt()) { compose.mainClock.advanceTimeBy(100); Shadows.shadowOf(Looper.getMainLooper()).idle(); Snapshot.sendApplyNotifications(); Thread.sleep(10) }; compose.waitForIdle() }
    private fun await(check:()->Boolean)=compose.waitUntil(15000) { compose.mainClock.advanceTimeBy(100); Shadows.shadowOf(Looper.getMainLooper()).idle(); Snapshot.sendApplyNotifications(); check() }
    private fun mount() { compose.mainClock.autoAdvance=false; compose.setContent { if(mounted.value) CompositionLocalProvider(LocalViewModelStoreOwner provides actor.get(),LocalPoolingNativeMapEnabled provides false) { YuldashTheme { YuldashApp() } } }; pump() }
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
        assertEquals(42,vm.activeBookingId.value)
    }
    private fun back() { compose.onNodeWithContentDescription("Назад").performClick(); pump() }
    private fun raw(type:String,id:Int=44) { MainActivity::class.java.getDeclaredMethod("onNewIntent",Intent::class.java).apply { isAccessible=true }.invoke(actor.get(),Intent(context,MainActivity::class.java).putExtra("type",type).putExtra("id",id.toString())) }
    private fun deliver(type:String) { compose.runOnIdle { raw(type) }; await { vm.pendingScreenNavigation.value==null }; pump() }
    private fun heldCreate() { heldPath="/bookings"; choosePublicRide(); click("Забронировать место"); await { started.count==0L } }
    private fun finishOld() { release.countDown(); assertTrue(dispatched.await(8,TimeUnit.SECONDS)); pump(1500) }
    private fun posts(path:String)=records.count { it.first=="POST" && it.second==path }
    @After fun cleanup() { release.countDown(); compose.runOnIdle { mounted.value=false }; pump(300); actor.destroy(); vm.clearUserData(); ApiClient.resetForTest(); ApiClient.testTimeoutMs=null; server.shutdown() }
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
    @Test fun homeConfirmedTripAndBackKeepRidesTab() { status="confirmed"; openExisting(); assertEquals(Screen.ActiveTrip,vm.screen.value); assertEquals("42",vm.activeTrip.value?.id); back(); assertEquals(Screen.Home,vm.screen.value); assertEquals(HomeTab.Rides,vm.startHomeTab.value) }
    @Test fun acceptedResponseFromHomeReplacesPreviousBookingRide() {
        withResponses=true; openExisting(); back(); assertEquals("42",vm.selectedRide.value?.id)
        click("Заявка"); click("Посмотреть отклики"); click("Принять")
        await { vm.screen.value==Screen.ActiveTrip && vm.activeBookingId.value==44 }; pump(1500)
        assertEquals(1,posts("/responses/61/accept")); assertEquals("44",vm.selectedRide.value?.id)
        assertEquals("Новый пункт А",vm.selectedRide.value?.from); assertEquals("Новый пункт Б",vm.selectedRide.value?.to)
        assertEquals("44",vm.activeTrip.value?.id)
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
}
