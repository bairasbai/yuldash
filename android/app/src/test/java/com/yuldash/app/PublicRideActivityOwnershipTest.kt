package com.yuldash.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.ui.theme.YuldashTheme
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Two real Activity/default-VM owners; public intents use only Activity entry points.
 * Explicit Compose attachment controls host ordering, as in PublicRideNavigationBoundaryTest.
 * Android's choice between onCreate and onNewIntent belongs to the separate native gate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PublicRideActivityOwnershipTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val controllers = mutableListOf<ActivityController<MainActivity>>()
    private lateinit var lower: ActivityController<MainActivity>
    private lateinit var upper: ActivityController<MainActivity>
    private lateinit var server: MockWebServer
    private val mounted = mutableStateOf(true)
    private val showUpper = mutableStateOf(false)
    private val requests = CopyOnWriteArrayList<String>()
    private val rideAttempts = ConcurrentHashMap<Int, AtomicInteger>()
    private val firstStarted = CountDownLatch(1)
    private val releaseFirst = CountDownLatch(1)
    private val firstDispatched = CountDownLatch(1)
    @Volatile private var holdFirst42 = false
    @Volatile private var holdPrivate77 = false
    @Volatile private var privateCode = 200
    @Volatile private var rejectPublicCode = 503
    @Volatile private var failPublic42 = false
    @Volatile private var holdPublic42 = false
    private val publicStarted = CountDownLatch(1)
    private val releasePublic = CountDownLatch(1)
    private var attached = false

    @Before fun setup() {
        ApiClient.resetForTest()
        ApiClient.init(context)
        ApiClient.saveToken("header.eyJzdWIiOiIxMSJ9.signature")
        clearSignals()
        context.getSharedPreferences("yuldash_prefs", 0).edit().clear()
            .putBoolean("onboarding_completed", false).putString("mode_last", "pooling")
            .putBoolean("mode_hint_shown", true).commit()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    requests += "${request.method} $path"
                    val rideId = path.removePrefix("/rides/").toIntOrNull()
                    if (rideId != null) {
                        val attempt = rideAttempts.computeIfAbsent(rideId) { AtomicInteger() }.incrementAndGet()
                        if (holdFirst42 && rideId == 42 && attempt == 1) {
                            firstStarted.countDown()
                            if (!releaseFirst.await(35, TimeUnit.SECONDS)) return json("{}", 503)
                            firstDispatched.countDown()
                        }
                        if (holdPublic42 && rideId == 42) {
                            publicStarted.countDown()
                            if (!releasePublic.await(35, TimeUnit.SECONDS)) return json("{}",503)
                        }
                        return if (failPublic42 && rideId == 42) json("{}",rejectPublicCode) else json(ride(rideId, attempt))
                    }
                    if (holdPrivate77 && path == "/bookings/77/role") {
                        firstStarted.countDown()
                        if (!releaseFirst.await(35, TimeUnit.SECONDS)) return json("{}",503)
                        firstDispatched.countDown()
                        return json("""{"booking_id":77,"ride_id":7,"role":"passenger","status":"pending","contact_unlocked":false}""", privateCode)
                    }
                    return when (path) {
                        "/instant/orders/active" -> json("{}", 503)
                        "/rides/near", "/bookings/mine" -> json("""{"items":[],"count":0}""")
                        else -> json("""{"items":[],"role":"passenger","unread":0}""")
                    }
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 60_000
    }

    private fun json(body: String, status: Int = 200) = MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "application/json").setBody(body)
    private fun from(id: Int, attempt: Int) = "Ссылка $id, ответ $attempt"
    private fun ride(id: Int, attempt: Int) = """{"id":$id,"from_city":"${from(id, attempt)}","to_city":"Конечный пункт","depart_at":"2030-01-02T10:00:00+03:00","seats_total":3,"seats_left":3,"price":750,"category":"regular","driver_id":9,"driver_name":"Тестовый водитель","driver_verified":true}"""
    private fun intent(id: Int) = Intent(Intent.ACTION_VIEW, Uri.parse("https://yulbash.ru/r/$id"), context, MainActivity::class.java)
    private fun model(actor: ActivityController<MainActivity>) = ViewModelProvider(actor.get())[YuldashViewModel::class.java]
    private fun create(link: Int? = null): ActivityController<MainActivity> {
        val actor = Robolectric.buildActivity(MainActivity::class.java,
            link?.let(::intent) ?: Intent(context, MainActivity::class.java)).create()
        controllers += actor
        model(actor).apply { screen.value = Screen.Home; startHomeTab.value = HomeTab.Map; persistNav() }
        return actor
    }
    private fun deliver(actor: ActivityController<MainActivity>, id: Int) = compose.runOnIdle {
        MainActivity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java)
            .apply { isAccessible = true }.invoke(actor.get(), intent(id))
    }
    private fun clearSignals() {
        DeepLink.pendingBookingChatId.value = null; DeepLink.pendingCompletedBookingId.value = null
        NavSignals.openInstantChat.value = 0; NavSignals.openInstantOrder.value = false
        NavSignals.openDriverCabinet.value = false; NavSignals.openAdsCabinet.value = false
        NavSignals.openPartnerCabinet.value = false
        DeepLink.pendingParcels.value = false; DeepLink.pendingSupport.value = false
        DeepLink.pendingFairness.value = false; DeepLink.pendingRequestsFeed.value = false
        DeepLink.pendingRequestResponsesId.value = null; DeepLink.pendingApplicationScreen.value = null
    }
    private fun tick() {
        compose.mainClock.advanceTimeBy(100)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        Snapshot.sendApplyNotifications()
    }
    private fun pump(frames: Int = 10) { repeat(frames) { tick(); Thread.sleep(10) }; compose.waitForIdle() }
    private fun await(check: () -> Boolean) {
        try { compose.waitUntil(15_000) { tick(); check() } }
        catch (error: Throwable) { throw AssertionError(diagnostic(), error) }
    }
    private fun mount() {
        compose.mainClock.autoAdvance = false
        attached = true
        compose.setContent {
            if (mounted.value) Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).testTag("lowerPublicHost")) {
                    CompositionLocalProvider(LocalViewModelStoreOwner provides lower.get(), LocalPoolingNativeMapEnabled provides false) {
                        YuldashTheme { YuldashApp() }
                    }
                }
                if (showUpper.value) Box(Modifier.weight(1f).testTag("upperPublicHost")) {
                    CompositionLocalProvider(LocalViewModelStoreOwner provides upper.get(), LocalPoolingNativeMapEnabled provides false) {
                        YuldashTheme { YuldashApp() }
                    }
                }
            }
        }
        pump()
    }
    private fun diagnostic() = "lower=${if (::lower.isInitialized) "${model(lower).screen.value}/${model(lower).selectedRide.value?.id}" else "not-created"}; " +
        "upper=${if (::upper.isInitialized) "${model(upper).screen.value}/${model(upper).selectedRide.value?.id}" else "not-created"}; requests=$requests"
    private fun selected(actor: ActivityController<MainActivity>, id: Int, attempt: Int, host: String) {
        val vm = model(actor)
        assertEquals(diagnostic(), Screen.Booking, vm.screen.value)
        assertEquals(diagnostic(), id.toString(), vm.selectedRide.value?.id)
        assertEquals(diagnostic(), from(id, attempt), vm.selectedRide.value?.from)
        assertNull("Public viewing must not acquire a booking: ${diagnostic()}", vm.activeBookingId.value)
        await {
            compose.onAllNodes(hasText(from(id, attempt), substring = true) and hasAnyAncestor(hasTestTag(host)), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertFalse("Public viewing must not write bookings", requests.any { it.startsWith("POST /bookings") })
    }

    @Test fun upperColdViewCannotBeConsumedByTheAlreadyMountedLowerHome() {
        lower = create()
        mount()
        await { requests.any { it == "GET /rides/near" } }
        upper = create(42)
        assertNotSame(model(lower), model(upper))
        // The lower App is the only mounted consumer during this bounded scheduling window.
        // Owner-local delivery does no /rides/42 work until the upper App is attached.
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1500)
        while (System.nanoTime() < deadline && model(lower).selectedRide.value == null) { tick(); Thread.sleep(10) }
        compose.runOnIdle { showUpper.value = true }
        await { model(upper).screen.value == Screen.Booking || model(lower).screen.value == Screen.Booking }
        pump()
        selected(upper, 42, 1, "upperPublicHost")
        assertEquals(diagnostic(), Screen.Home, model(lower).screen.value)
        assertNull(diagnostic(), model(lower).selectedRide.value)
        assertEquals(diagnostic(), 1, rideAttempts[42]?.get())
    }

    @Test fun pendingPublicDestinationsOfTwoActivitiesNeverTransferBetweenHosts() {
        lower = create(42)
        upper = create(43)
        assertNotSame(model(lower), model(upper))
        showUpper.value = true
        mount()
        await {
            (model(lower).screen.value == Screen.Booking && model(upper).screen.value == Screen.Booking) ||
                (model(lower).selectedRide.value?.id?.let { it != "42" } == true) ||
                (model(upper).selectedRide.value?.id?.let { it != "43" } == true)
        }
        pump()
        selected(lower, 42, 1, "lowerPublicHost")
        selected(upper, 43, 1, "upperPublicHost")
        assertEquals(diagnostic(), 1, rideAttempts[42]?.get())
        assertEquals(diagnostic(), 1, rideAttempts[43]?.get())
    }

    @Test fun anotherWindowsPendingBookingCannotCancelAnExplicitPublicLink() {
        lower = create()
        model(lower).requestBookingDestination(77, 11, false)
        upper = create(43)
        showUpper.value = true
        mount()
        await { model(upper).screen.value == Screen.Booking || model(upper).pendingBookingNavigation.value != null }
        pump()
        selected(upper, 43, 1, "upperPublicHost")
        assertNull(diagnostic(), model(upper).pendingBookingNavigation.value)
    }

    @Test fun anotherWindowsPendingScreenCannotCancelAnExplicitPublicLink() {
        lower = create()
        model(lower).requestScreenDestination(Screen.InstantOrder, 11)
        upper = create(43)
        showUpper.value = true
        mount()
        await { model(upper).screen.value == Screen.Booking || model(upper).screen.value == Screen.InstantOrder }
        pump()
        selected(upper, 43, 1, "upperPublicHost")
        assertNull(diagnostic(), model(upper).pendingScreenNavigation.value)
    }

    @Test fun repeatedSamePublicIntentUsesTheNewDeliveryAndOldHttpCannotReplaceANewerRide() {
        lower = create()
        upper = create()
        showUpper.value = true
        mount()
        holdFirst42 = true
        deliver(upper, 42)
        await { firstStarted.count == 0L }
        deliver(upper, 42)
        // The first response remains withheld. A new tap of the same URI belongs to the upper host.
        await { model(upper).screen.value == Screen.Booking && model(upper).selectedRide.value?.from == from(42, 2) }
        selected(upper, 42, 2, "upperPublicHost")
        assertEquals(diagnostic(), Screen.Home, model(lower).screen.value)
        deliver(upper, 43)
        await { model(upper).selectedRide.value?.id == "43" }
        selected(upper, 43, 1, "upperPublicHost")
        releaseFirst.countDown()
        assertTrue("The older HTTP fixture must actually finish", firstDispatched.await(8, TimeUnit.SECONDS))
        pump(30)
        selected(upper, 43, 1, "upperPublicHost")
        assertEquals(diagnostic(), Screen.Home, model(lower).screen.value)
        assertNull(diagnostic(), model(lower).selectedRide.value)
        assertEquals(diagnostic(), 2, rideAttempts[42]?.get())
    }

    private fun oldPrivateCannotReplaceNewPublicFailure(code: Int, privateFirst: Boolean) {
        lower=create();upper=create();showUpper.value=true;mount()
        holdPrivate77=true;privateCode=code;failPublic42=true;holdPublic42=privateFirst
        compose.runOnIdle { model(upper).requestBookingDestination(77,11,false) }
        await { firstStarted.count==0L }
        deliver(upper,42)
        await { rideAttempts[42]?.get()==1 }
        if (privateFirst) {
            await { publicStarted.count==0L }
            releaseFirst.countDown();assertTrue(firstDispatched.await(8,TimeUnit.SECONDS));pump(10)
            releasePublic.countDown()
        } else {
            pump(10);releaseFirst.countDown();assertTrue(firstDispatched.await(8,TimeUnit.SECONDS))
        }
        pump(20)
        assertEquals(diagnostic(),Screen.Home,model(upper).screen.value)
        assertNull(diagnostic(),model(upper).selectedRide.value)
        assertNull(diagnostic(),model(upper).pendingBookingNavigation.value)
        compose.onNodeWithTag("rideLinkFailure").assertExists()
        compose.onAllNodesWithTag("bookingLinkFailure").assertCountEquals(0)
        failPublic42=false;holdPublic42=false
        compose.onNodeWithTag("rideLinkRetry").performClick()
        await { model(upper).selectedRide.value?.id=="42" }
        selected(upper,42,2,"upperPublicHost")
        assertEquals(diagnostic(),Screen.Home,model(lower).screen.value)
    }
    @Test fun newPublicFailureRejectsOlderPrivateSuccessArrivingLater()=oldPrivateCannotReplaceNewPublicFailure(200,false)
    @Test fun newPublicFailureRejectsOlderPrivateFailureArrivingLater()=oldPrivateCannotReplaceNewPublicFailure(503,false)
    @Test fun newPublicFailureRejectsOlderPrivateSuccessArrivingFirst()=oldPrivateCannotReplaceNewPublicFailure(200,true)
    @Test fun newPublicFailureRejectsOlderPrivateFailureArrivingFirst()=oldPrivateCannotReplaceNewPublicFailure(503,true)

    @Test fun foreignPendingBookingKeepsItsOwnReplyAndDoesNotHideThePublicFailure() {
        lower=create();upper=create();showUpper.value=true;mount()
        holdPrivate77=true;failPublic42=true
        compose.runOnIdle { model(lower).requestBookingDestination(77,11,false) }
        await { firstStarted.count==0L }
        deliver(upper,42)
        await { compose.onAllNodesWithTag("rideLinkFailure").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(diagnostic(),77,model(lower).pendingBookingNavigation.value?.bookingId)
        assertNull(diagnostic(),model(upper).pendingBookingNavigation.value)
        releaseFirst.countDown();assertTrue(firstDispatched.await(8,TimeUnit.SECONDS))
        await { model(lower).screen.value==Screen.Booking };pump(20)
        assertEquals(diagnostic(),77,model(lower).activeBookingId.value)
        assertEquals(diagnostic(),Screen.Home,model(upper).screen.value)
        compose.onNodeWithTag("rideLinkFailure").assertExists()
        failPublic42=false;compose.onNodeWithTag("rideLinkRetry").performClick()
        await { model(upper).selectedRide.value?.id=="42" }
        selected(upper,42,2,"upperPublicHost")
        assertEquals(diagnostic(),77,model(lower).activeBookingId.value)
    }

    private fun bashkirPublicFailure(code: Int) {
        lower=create();upper=create()
        model(upper).language.value=AppLanguage.Ba;model(upper).persistNav()
        showUpper.value=true;failPublic42=true;rejectPublicCode=code;mount()
        deliver(upper,42)
        await { compose.onAllNodesWithTag("rideLinkFailure").fetchSemanticsNodes().isNotEmpty() }
        val title=if(code==404) "Сәфәрҙе асып булмай" else "Сәфәрҙе асып булманы"
        compose.onNodeWithText(title).assertExists()
        compose.onAllNodesWithText(if(code==404) "Поездка недоступна" else "Не получилось открыть поездку").assertCountEquals(0)
        if(code==404) {
            compose.onNodeWithText("Ябыу").performClick();pump()
            assertEquals(Screen.Home,model(upper).screen.value)
        } else {
            failPublic42=false;compose.onNodeWithText("Ҡабатлау").performClick()
            await { model(upper).selectedRide.value?.id=="42" }
            selected(upper,42,2,"upperPublicHost")
        }
        assertEquals(AppLanguage.Ba,model(upper).language.value)
    }
    @Test fun bashkirUnavailablePublicRideDialogUsesTheReceivingWindowsLanguage()=bashkirPublicFailure(404)
    @Test fun bashkirRetryPublicRideDialogUsesTheReceivingWindowsLanguage()=bashkirPublicFailure(503)

    @After fun cleanup() {
        releaseFirst.countDown();releasePublic.countDown()
        if (attached) { compose.runOnIdle { mounted.value = false }; pump(3) }
        controllers.reversed().forEach { it.destroy() }
        clearSignals()
        ApiClient.resetForTest(); ApiClient.testTimeoutMs = null
        if (::server.isInitialized) server.shutdown()
    }
}
