package com.yuldash.app

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.OfflineTripSnapshots
import com.yuldash.app.ui.theme.YuldashTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real YuldashApp routes/holder; fresh VM from observed handle snapshot is not an OS process kill. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class,qualifiers="w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompletedNavigationRestorationTest {
    @get:Rule val compose=createComposeRule()
    private val restoration=StateRestorationTester(compose)
    private class Owner:ViewModelStoreOwner { override val viewModelStore=ViewModelStore() }
    private val owner=mutableStateOf(Owner()); private val mounted=mutableStateOf(true)
    private var contentMounted=false
    private var observedSaveableRegistry: androidx.compose.runtime.saveable.SaveableStateRegistry? = null
    private val allOwners=mutableListOf(owner.value)
    private lateinit var handle:SavedStateHandle;private lateinit var vm:YuldashViewModel
    private lateinit var server:MockWebServer
    private val records=CopyOnWriteArrayList<Triple<String,String?,String>>()
    private val mineCalls=AtomicInteger();private val started=CountDownLatch(1);private val release=CountDownLatch(1);private val dispatched=CountDownLatch(1)
    @Volatile private var heldDetailsRide:Ride?=null
    @Volatile private var preselectedHeldRide:Ride?=null
    private val heldDetailsCalls=AtomicInteger()
    private val failures=CopyOnWriteArrayList<String>()
    private var heldDetails=false;private var heldStatus=200
    @Volatile private var serverBooking=42
    @Volatile private var thanked=false
    private val tokenA="header.eyJzdWIiOiIxMSJ9.signature";private val tokenB="header.eyJzdWIiOiIzMyJ9.signature"
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private fun awaitApiBackgroundCompletion()=runBlocking {
        // Observe only the test-owned API coroutine job, never auth/key fields.
        val scope=ApiClient::class.java.getDeclaredField("bg").apply {isAccessible=true}.get(ApiClient) as CoroutineScope
        val parent=requireNotNull(scope.coroutineContext[Job])
        try {
            withTimeout(10_000) {
                while(true) {
                    val children=parent.children.toList()
                    if(children.isEmpty()) break
                    children.forEach {it.join()}
                }
            }
        } catch(failure:TimeoutCancellationException) {
            throw AssertionError("Fixture API jobs still running; local origin/server retained. Abort this isolated run.",failure)
        }
    }
    private fun awaitSnapshotCleanup()=runBlocking {
        withTimeout(5_000) {while(!OfflineTripSnapshots.ensureResetCommitted()) delay(10)}
    }
    private fun terminalDetails(id:Int):String=JSONObject().put("booking_id",id).put("ride_id",9)
        .put("role","passenger").put("status","done").put("contact_unlocked",false)
        .put("from_city","Уфа").put("to_city","Бирск").put("driver_name","Водитель").put("seats",1).toString()
    @Before fun prepare() {
        context.getSharedPreferences("yuldash_prefs",Context.MODE_PRIVATE).edit()
            .putBoolean("onboarding_completed",false).commit()
        awaitApiBackgroundCompletion();awaitSnapshotCleanup()
        ApiClient.resetForTest()
        server=MockWebServer().apply {
            dispatcher=object:Dispatcher() {
                override fun dispatch(request:RecordedRequest):MockResponse {
                    val path=request.requestUrl!!.encodedPath;val key="${request.method} $path"
                    records+=Triple(key,request.getHeader("Authorization"),request.body.readUtf8())
                    if(path=="/bookings/mine") {
                        val id=serverBooking;mineCalls.incrementAndGet()
                        return json("""{"items":[{"id":$id,"ride_id":9,"status":"done","seats":1,"from_city":"Уфа","to_city":"Бирск","price":400,"driver_name":"Водитель"}]}""")
                    }
                    val id=path.split('/').getOrNull(2)?.toIntOrNull() ?: 42
                    val body=when {
                        path=="/bookings/$id/details" -> {
                            if(request.getHeader("Authorization")!="Bearer "+tokenA) return json("{}",403)
                            if(heldDetails && id==42 && heldDetailsCalls.incrementAndGet()==1) {
                                heldDetailsRide=vm.selectedRide.value
                                if(heldDetailsRide!==preselectedHeldRide) failures+="held details candidate changed before dispatch"
                                started.countDown()
                                if(!release.await(40,TimeUnit.SECONDS)) failures+="details timeout"
                                val response=json(terminalDetails(id),heldStatus)
                                dispatched.countDown()
                                return response
                            }
                            terminalDetails(id)
                        }
                        path=="/bookings/$id/role" -> """{"role":"passenger","status":"done","driver_phase":""}"""
                        path=="/bookings/$id/messages" -> """{"items":[]}"""
                        path=="/bookings/$id/boarding-code" -> """{"code":""}"""
                        path=="/bookings/$id/tip" -> """{"already_thanked":$thanked,"driver_name":"Водитель"}"""
                        path=="/trips/$id/receipt" -> """{"booking_id":$id,"ride_id":9,"role":"passenger","from_city":"Уфа","to_city":"Бирск","amount":400,"pay_method":"cash","paid":true,"counterparty_name":"Водитель","my_stars":0}"""
                        key=="POST /bookings/$id/thanks" -> { thanked=true;"{}" }
                        key=="POST /bookings/$id/rate" -> "{}"
                        request.method=="POST" && path.startsWith("/bookings/") -> { failures+="Unexpected write $key";return json("{}",405) }
                        else -> """{"items":[],"role":"passenger"}"""
                    }
                    return json(body)
                }
            }
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        ApiClient.testBaseUrl = "http://127.0.0.1:${server.port}"
        ApiClient.testTimeoutMs = 60000
        ApiClient.init(context);ApiClient.logout();awaitSnapshotCleanup();ApiClient.saveToken(tokenA)
        assertEquals(11,OfflineTripSnapshots.currentOwner()?.userId)
        assertFalse("Initial login must not invent live authority",OfflineTripSnapshots.allowsLive(42))
        assertNull(OfflineTripSnapshots.terminal(42))
        newVm(mapOf("yuldash_screen" to Screen.ActiveTrip.name,"yuldash_active_bid" to 42,"yuldash_tab" to HomeTab.Profile.name))
    }
    private fun json(body:String,status:Int=200)=MockResponse().setResponseCode(status).setHeader("Content-Type","application/json").setBody(body)
    private fun newVm(snapshot:Map<String,Any?>) {
        handle=SavedStateHandle(snapshot)
        vm=ViewModelProvider(owner.value,object:ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T:ViewModel> create(modelClass:Class<T>):T=YuldashViewModel(handle) as T
        })[YuldashViewModel::class.java]
    }
    @After fun cleanup() {
        release.countDown()
        if(!::server.isInitialized) {awaitApiBackgroundCompletion();awaitSnapshotCleanup();return}
        if(contentMounted) {compose.runOnIdle {mounted.value=false};pump(300)}
        allOwners.forEach {it.viewModelStore.clear()}
        ApiClient.logout();awaitApiBackgroundCompletion();awaitSnapshotCleanup()
        ApiClient.resetForTest();ApiClient.testTimeoutMs=null;server.shutdown()
        assertTrue(failures.toString(),failures.isEmpty())
    }
    private fun pump(ms:Long=700) {
        repeat((ms/100).toInt()) {compose.mainClock.advanceTimeBy(100);Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100));Thread.sleep(10)}
        compose.waitForIdle()
    }
    private fun await(check:()->Boolean) {
        try {
            compose.waitUntil(12000) {
                compose.mainClock.advanceTimeBy(100)
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                Snapshot.sendApplyNotifications()
                check()
            }
        } catch(failure:ComposeTimeoutException) {
            val bid=vm.activeBookingId.value
            throw AssertionError("NAV5 completed await screen="+vm.screen.value+" booking="+bid+
                " selectedId="+vm.selectedRide.value?.id+" activeNull="+(vm.activeTrip.value==null)+
                " pending="+(vm.pendingBookingNavigation.value!=null || vm.pendingScreenNavigation.value!=null || vm.pendingPublicRideNavigation.value!=null)+
                " revision="+vm.privateNavigationRevision+" owner11="+(OfflineTripSnapshots.currentOwner()?.userId==11)+
                " roleGET="+records.count{it.first=="GET /bookings/"+bid+"/role"}+
                " detailsGET="+records.count{it.first=="GET /bookings/"+bid+"/details"}+
                " receiptGET="+records.count{it.first=="GET /trips/"+bid+"/receipt"}+
                " mineGET="+mineCalls.get()+" terminal="+(bid?.let(OfflineTripSnapshots::terminal)!=null),failure)
        }
    }
    private fun mount() {
        contentMounted=true
        restoration.setContent {
            if(mounted.value) CompositionLocalProvider(LocalViewModelStoreOwner provides owner.value) {
                observedSaveableRegistry = androidx.compose.runtime.saveable.LocalSaveableStateRegistry.current
                YuldashTheme {YuldashApp()}
            }
        }
        await {compose.onAllNodesWithTag("rideshareCompletedHero").fetchSemanticsNodes().isNotEmpty()};pump()
    }
    @Suppress("DEPRECATION")
    private fun completedList(): SemanticsNodeInteraction {
        val hidden = SemanticsMatcher.keyIsDefined(SemanticsProperties.InvisibleToUser)
        val list = hasTestTag("rideshareCompletedList") and !hidden and !hasAnyAncestor(hidden)
        await { compose.onAllNodes(list, useUnmergedTree = true).fetchSemanticsNodes().size == 1 }
        return compose.onNode(list, useUnmergedTree = true)
    }
    private fun tag(value:String):SemanticsNodeInteraction {
        if (value != "rideshareRatingSubmit") {
            val list = completedList()
            await { runCatching { list.performScrollToNode(hasTestTag(value)) }.isSuccess }
        }
        return compose.onNodeWithTag(value)
    }
    private fun text(value:String):SemanticsNodeInteraction {
        completedList().performScrollToNode(hasText(value));return compose.onNodeWithText(value)
    }
    private fun draft() {
        tag("rideshareStar4").performClick();text("Приехал вовремя").performClick();text("Добавить пару слов").performClick()
        tag("rideshareReviewText").performTextInput("Спасибо за дорогу")
    }
    private fun checkDraft() {
        assertEquals("Спасибо за дорогу",tag("rideshareReviewText").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        text("Приехал вовремя").assertIsSelected();tag("rideshareReviewText").assertIsEnabled()
    }
    private fun receipt() {text("Квитанция").performClick();await {vm.screen.value==Screen.TripReceipt};pump();compose.onNodeWithText("Квитанция").assertIsDisplayed()}
    private fun back() {compose.onNodeWithContentDescription("Назад").performClick();pump()}
    private fun awaitCompleted() {await {compose.onAllNodesWithTag("rideshareCompletedHero").fetchSemanticsNodes().isNotEmpty()};pump();assertEquals(Screen.ActiveTrip,vm.screen.value)}
    private fun posts(kind:String,id:Int=42)=records.count {it.first=="POST /bookings/$id/$kind"}
    private fun assertRating(id:Int=42) {
        tag("rideshareRatingSubmit").performClick();await {posts("rate",id)==1};pump()
        val row=records.single {it.first=="POST /bookings/$id/rate"};val p=JSONObject(row.third)
        assertEquals(4,p.getInt("stars"));assertEquals("ontime",p.getString("tags"));assertEquals("Спасибо за дорогу",p.getString("text"));assertEquals("Bearer $tokenA",row.second)
    }
    private fun freshVmRestore() {
        pump();val snapshot=handle.keys().associateWith {handle.get<Any?>(it)}
        assertEquals(Screen.TripReceipt.name,snapshot["yuldash_screen"]);assertEquals(42,snapshot["yuldash_active_bid"])
        compose.runOnIdle {val old=owner.value;owner.value=Owner();allOwners+=owner.value;newVm(snapshot);old.viewModelStore.clear()}
        restoration.emulateSavedInstanceStateRestore();pump();assertEquals(Screen.TripReceipt,vm.screen.value)
    }
    @Test fun receiptBackRetainsExactDraftAndManualPayload() {
        mount();draft();receipt();back();awaitCompleted();checkDraft();assertEquals(0,posts("rate"));assertRating()
    }
    @Test fun supportBackRetainsExactDraftWithoutAutomaticPost() {
        var initialBooking: Int? = null
        var initialRide: Ride? = null
        var initialSession = 0L
        var initialRevision = 0L
        var initialOwner: com.yuldash.app.data.SnapshotOwner? = null
        fun diagnostic(stage: String) {
            // Test-owned current reference only: never print DTO fields, text or request payloads.
            val receipt = synchronized(OfflineTripSnapshots) {
                val histories = OfflineTripSnapshots::class.java.getDeclaredField("histories").apply { isAccessible = true }
                    .get(OfflineTripSnapshots) as Map<*, *>
                (histories[42] as? Pair<*, *>)?.first as? com.yuldash.app.data.TripReceiptDto
            }
            fun nodes(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size
            val ontimeText = "Приехал вовремя"
            val draftText = "Спасибо за дорогу"
            fun containsSynthetic(value: Any?, target: String): Boolean = when (value) {
                is String -> value == target
                is androidx.compose.runtime.State<*> -> containsSynthetic(value.value, target)
                is Map<*, *> -> value.values.any { containsSynthetic(it, target) }
                is Iterable<*> -> value.any { containsSynthetic(it, target) }
                is Array<*> -> value.any { containsSynthetic(it, target) }
                is android.os.Bundle -> value.keySet().any { containsSynthetic(value.get(it), target) }
                else -> false
            }
            // Observe the existing root registry; do not replace it or print its keys/values.
            val saved = compose.runOnIdle { requireNotNull(observedSaveableRegistry).performSave() }
            val savedExactDraft = containsSynthetic(saved, draftText)
            val savedOntime = containsSynthetic(saved, "ontime")
            if (stage == "beforeSupport") {
                assertTrue("Saved-state observer must see the existing synthetic draft before navigation", savedExactDraft)
                assertTrue("Saved-state observer must see the selected synthetic tag before navigation", savedOntime)
            }
            val submitEnabled = compose.onAllNodesWithTag("rideshareRatingSubmit").fetchSemanticsNodes()
                .count { !it.config.contains(SemanticsProperties.Disabled) }
            val ontimeVisible = compose.onAllNodesWithText(ontimeText).fetchSemanticsNodes().size
            val ontimeSelected = compose.onAllNodesWithText(ontimeText).fetchSemanticsNodes()
                .count { it.config.contains(SemanticsProperties.Selected) && it.config[SemanticsProperties.Selected] }
            println("NAV7 support stage="+stage+
                " sameBooking="+(vm.activeBookingId.value == initialBooking)+
                " sameRide="+(vm.selectedRide.value == initialRide)+
                " sameRideRef="+(vm.selectedRide.value === initialRide)+
                " sameSession="+(ApiClient.queueSessionGeneration() == initialSession)+
                " sameOwner="+(OfflineTripSnapshots.currentOwner() == initialOwner)+
                " sameRevision="+(vm.privateNavigationRevision == initialRevision)+
                " form="+nodes("rideshareReviewText")+" submitEnabled="+submitEnabled+
                " ontimeVisible="+ontimeVisible+" ontimeSelected="+ontimeSelected+
                " savedExactDraft="+savedExactDraft+
                " savedOntime="+savedOntime)

            println("NAV6 support stage="+stage+" historyRef="+(receipt?.let { System.identityHashCode(it) } ?: 0)+
                " historyCurrent="+(receipt?.let(OfflineTripSnapshots::allowsHistory) == true)+
                " card="+nodes("rideshareRatingCard")+" form="+nodes("rideshareReviewText")+
                " loader="+nodes("rideshareReceiptLoading")+" error="+nodes("rideshareReceiptError")+
                " receiptGET="+records.count { it.first=="GET /trips/42/receipt" })
        }
        mount();draft()
        initialBooking = vm.activeBookingId.value; initialRide = vm.selectedRide.value
        initialSession = ApiClient.queueSessionGeneration(); initialRevision = vm.privateNavigationRevision
        initialOwner = OfflineTripSnapshots.currentOwner()
        diagnostic("beforeSupport")
        text("Нужна помощь?").performClick();await {vm.screen.value==Screen.Support};pump()
        diagnostic("onSupport")
        back();awaitCompleted()
        diagnostic("afterReturnBeforeExactCheck")
        checkDraft();assertEquals(0,posts("rate"))
    }
    @Test fun confirmedThanksSurvivesReceiptRoundTripWithoutAnotherPost() {
        mount()
        await { compose.onAllNodesWithTag("rideshareRatingCard").fetchSemanticsNodes().isNotEmpty() }
        text("Квитанция")
        val availableThanks = hasText("Сказать «Рәхмәт»") and hasClickAction() and isEnabled()
        await { compose.onAllNodes(availableThanks).fetchSemanticsNodes().size == 1 }
        compose.onNode(availableThanks).performClick()
        await { posts("thanks") == 1 }
        await { compose.onAllNodesWithText("Благодарность сохранена").fetchSemanticsNodes().isNotEmpty() }
        pump();receipt();back();awaitCompleted()
        await { compose.onAllNodesWithTag("rideshareRatingCard").fetchSemanticsNodes().isNotEmpty() }
        text("Квитанция")
        val sentThanks = hasText("Рәхмәт передано") and hasClickAction() and isEnabled()
        await { compose.onAllNodes(sentThanks).fetchSemanticsNodes().size == 1 }
        text("Благодарность сохранена").assertIsDisplayed()
        compose.onNode(sentThanks).performClick()
        pump()
        assertEquals(1, posts("thanks"))
    }
    @Test fun savedCompositionWithRetainedVmRestoresReceiptBackAndDraft() {
        mount();draft();receipt();restoration.emulateSavedInstanceStateRestore();pump();back();awaitCompleted();checkDraft();assertEquals(0,posts("rate"))
    }
    @Test fun freshVmFromObservedSavedSnapshotRestoresReceiptBackDraftAndBooking() {
        mount();draft();receipt();freshVmRestore();back()
        assertEquals("Back from restored receipt must return to the completed booking",Screen.ActiveTrip,vm.screen.value)
        awaitCompleted();await {vm.selectedRide.value!=null};checkDraft();assertEquals("42",vm.selectedRide.value!!.id);assertNull(vm.activeTrip.value)
        assertEquals(0,posts("rate"));assertRating()
    }
    @Test fun anotherBookingCannotInheritSavedCompletedDraftThroughAppHolder() {
        mount();draft();receipt();compose.runOnIdle {vm.activeBookingId.value=43;vm.selectedRide.value=null;serverBooking=43};back();awaitCompleted()
        tag("rideshareRatingCard");compose.onNodeWithTag("rideshareReviewText").assertDoesNotExist();tag("rideshareRatingSubmit").assertIsNotEnabled()
        tag("rideshareStar2").performClick();tag("rideshareRatingSubmit").performClick();await {posts("rate",43)==1}
        assertEquals(0,posts("rate",42));assertEquals(2,JSONObject(records.single {it.first=="POST /bookings/43/rate"}.third).getInt("stars"))
    }
    private fun preselectHeldDetailsContext() {
        assertFalse("The known candidate must be selected before the first composition",contentMounted)
        val candidate=bookingLinkStubRide(42)
        preselectedHeldRide=candidate
        vm.selectedRide.value=candidate
        assertSame(candidate,vm.selectedRide.value)
        assertEquals(42,vm.activeBookingId.value);assertNull(vm.activeTrip.value)
        assertFalse("A selected stub must not install live authority",OfflineTripSnapshots.allowsLive(42))
        assertNull("Terminal authority must still arrive through the real ROLE read",OfflineTripSnapshots.terminal(42))
    }
    private fun assertHeldDetailsContextIsCurrent() {
        assertEquals(42,vm.activeBookingId.value)
        assertEquals(bookingLinkStubRide(42),vm.selectedRide.value)
        assertSame("The first composition and the action must use the preselected candidate",
            preselectedHeldRide,vm.selectedRide.value)
        assertSame("The dispatched validation must still use the preselected candidate",
            preselectedHeldRide,heldDetailsRide)
    }
    private fun releaseDetails() {
        assertEquals("The actual DETAILS request must have started before the route action",0L,started.count)
        assertEquals("The held response must still be pending until after the route action",1L,dispatched.count)
        release.countDown();await {dispatched.count==0L};pump()
    }
    private fun assertHeldDetailsDidNotWriteOrReadReceiptZero() {
        assertFalse(records.any {it.first.startsWith("POST /bookings/")})
        assertFalse(records.any {it.first=="GET /trips/0/receipt"})
    }
    @Test fun failedHeldRestoreCannotReplaceReceiptRouteWithHome() {
        heldDetails=true;heldStatus=503;preselectHeldDetailsContext();mount();await {started.count==0L};assertHeldDetailsContextIsCurrent()
        val selectedBeforeReceipt=vm.selectedRide.value
        assertEquals(bookingLinkStubRide(42),selectedBeforeReceipt)
        receipt();releaseDetails()
        assertEquals(Screen.TripReceipt,vm.screen.value);compose.onNodeWithText("Квитанция").assertIsDisplayed();assertSame(selectedBeforeReceipt,vm.selectedRide.value)
        assertEquals(42,vm.activeBookingId.value);assertEquals("42",vm.selectedRide.value?.id);assertNull(vm.activeTrip.value)
        assertHeldDetailsDidNotWriteOrReadReceiptZero()
    }
    @Test fun heldRestoreCannotReplaceNewBookingAfterCurrentLoaderCompletes() {
        heldDetails=true;preselectHeldDetailsContext();mount();await {started.count==0L};assertHeldDetailsContextIsCurrent()
        compose.runOnIdle {serverBooking=43;vm.activeBookingId.value=43;vm.selectedRide.value=null};pump()
        await {vm.selectedRide.value?.id=="43"}
        val currentRide=vm.selectedRide.value
        assertEquals(bookingLinkStubRide(43),currentRide)
        releaseDetails()
        assertEquals(43,vm.activeBookingId.value);assertEquals("43",vm.selectedRide.value!!.id);assertNull(vm.activeTrip.value);assertEquals(Screen.ActiveTrip,vm.screen.value)
        assertSame(currentRide,vm.selectedRide.value);assertHeldDetailsDidNotWriteOrReadReceiptZero()
    }
    @Test fun heldRestoreFromPreviousLoginCannotReopenPreviousReceiptContext() {
        heldDetails=true;preselectHeldDetailsContext();mount();await {started.count==0L};assertHeldDetailsContextIsCurrent();receipt();compose.runOnIdle {ApiClient.saveToken(tokenB)};pump()
        // saveToken replaces API authority; this fixture does not invoke the separate endSession VM cleanup.
        assertEquals(Screen.Notifications,vm.screen.value)
        compose.onNodeWithText("Уведомления").assertIsDisplayed()
        compose.onNodeWithTag("rideshareCompletedHero").assertDoesNotExist()
        compose.onNodeWithTag("rideshareRatingCard").assertDoesNotExist()
        val selectedAfterSwitch=vm.selectedRide.value
        assertTrue("Only null or the exact empty fixture candidate may remain after API account replacement",
            selectedAfterSwitch==null || selectedAfterSwitch==bookingLinkStubRide(42))
        assertNull(vm.activeTrip.value)
        val bookingAfterSwitch=vm.activeBookingId.value
        val revisionAfterSwitch=vm.privateNavigationRevision
        val ownerAfterSwitch=OfflineTripSnapshots.currentOwner()
        val sessionAfterSwitch=ApiClient.queueSessionGeneration()
        assertEquals(33,ownerAfterSwitch?.userId)
        assertFalse(OfflineTripSnapshots.allowsLive(42));assertNull(OfflineTripSnapshots.terminal(42))
        releaseDetails()
        assertEquals(Screen.Notifications,vm.screen.value);compose.onNodeWithText("Уведомления").assertIsDisplayed()
        assertTrue("A late response from the previous account must not change the post-switch candidate reference",
            vm.selectedRide.value===selectedAfterSwitch)
        assertEquals(bookingAfterSwitch,vm.activeBookingId.value)
        assertEquals(revisionAfterSwitch,vm.privateNavigationRevision)
        assertTrue(OfflineTripSnapshots.currentOwner()==ownerAfterSwitch)
        assertEquals(sessionAfterSwitch,ApiClient.queueSessionGeneration())
        assertNull(vm.activeTrip.value)
        assertFalse(OfflineTripSnapshots.allowsLive(42));assertNull(OfflineTripSnapshots.terminal(42))
        compose.onNodeWithTag("rideshareCompletedHero").assertDoesNotExist()
        compose.onNodeWithTag("rideshareRatingCard").assertDoesNotExist()
        assertEquals(0,posts("rate"))
        assertFalse(records.any {it.first=="GET /trips/42/receipt" && it.second=="Bearer $tokenB"})
        assertFalse(records.any {it.first=="GET /trips/0/receipt"})
        assertHeldDetailsDidNotWriteOrReadReceiptZero()
    }
    @Test fun savedNavigationTrailReconstructsOrderWithoutRestoringBusinessObjects() {
        val saved=SavedStateHandle();val first=YuldashViewModel(saved)
        first.screen.value=Screen.TripReceipt;first.activeBookingId.value=42;first.navHistory.addAll(listOf(Screen.Notifications,Screen.ActiveTrip));first.persistNav()
        val restored=YuldashViewModel(SavedStateHandle(saved.keys().associateWith {saved.get<Any?>(it)}))
        assertEquals(listOf(Screen.Notifications,Screen.ActiveTrip),restored.navHistory.toList());assertEquals(Screen.TripReceipt,restored.screen.value)
        assertEquals(42,restored.activeBookingId.value);assertNull(restored.selectedRide.value);assertNull(restored.activeTrip.value)
    }
    @Test fun clearingUserDataImmediatelyRemovesSavedNavigationTrail() {
        val saved=SavedStateHandle();val first=YuldashViewModel(saved);first.navHistory.add(Screen.ActiveTrip);first.persistNav();first.clearUserData()
        val restored=YuldashViewModel(SavedStateHandle(saved.keys().associateWith {saved.get<Any?>(it)}))
        assertTrue(restored.navHistory.isEmpty())
    }
    private fun standaloneReceiptAction(
        receiptParent: (() -> Boolean)? = null,
        genericParent: (() -> Boolean)? = null,
    ): Pair<() -> Boolean, AtomicInteger> {
        val opens = AtomicInteger()
        var httpReceipt: com.yuldash.app.data.TripReceiptDto? = null
        val receiptLabel = "Квитанция"
        contentMounted = true
        restoration.setContent {
            if (mounted.value) CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                YuldashTheme {
                    RideshareCompletedScreen(
                        bookingId = 42, ride = bookingLinkStubRide(42), fallbackRole = "passenger",
                        fallbackPayMethod = "cash", fallbackPayAmount = null,
                        onClose = {}, onOpenReceipt = { opens.incrementAndGet() }, onSupport = {},
                        loadReceipt = { id -> ApiClient.getTripReceipt(id).onSuccess { httpReceipt = it } },
                        isCurrentParent = genericParent, isCurrentReceiptParent = receiptParent,
                    )
                }
            }
        }
        await {
            httpReceipt?.let(OfflineTripSnapshots::allowsHistory) == true &&
                compose.onAllNodesWithText(receiptLabel).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(OfflineTripSnapshots.allowsHistory(requireNotNull(httpReceipt)))
        assertTrue(records.any { it.first == "GET /trips/42/receipt" })
        assertEquals(0, opens.get())
        compose.onNodeWithTag("rideshareCompletedList").performScrollToNode(hasText(receiptLabel))
        val node = compose.onNodeWithText(receiptLabel).assertIsDisplayed().assertIsEnabled()
        val action = requireNotNull(node.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action)
        return action to opens
    }
    private fun assertStandaloneReceiptDidNotWrite() {
        assertFalse(records.any { it.first.startsWith("POST /bookings/") })
    }
    @Test fun standaloneCurrentReceiptWithActualHistoryCanOpen() {
        val (action, opens) = standaloneReceiptAction()
        compose.runOnIdle { assertTrue(action()); assertEquals(1, opens.get()) }
        assertStandaloneReceiptDidNotWrite()
    }
    @Test fun standaloneDisposedReceiptCallbackWithoutExplicitParentCannotOpen() {
        val (action, opens) = standaloneReceiptAction()
        compose.runOnIdle { mounted.value = false }
        pump()
        compose.runOnIdle { assertTrue(action()); assertEquals(0, opens.get()) }
        assertStandaloneReceiptDidNotWrite()
    }
    @Test fun explicitReceiptParentDenialCannotFallBackToAllowedGenericParent() {
        val (action, opens) = standaloneReceiptAction(receiptParent = { false }, genericParent = { true })
        compose.runOnIdle { assertTrue(action()); assertEquals(0, opens.get()) }
        assertStandaloneReceiptDidNotWrite()
    }
}
