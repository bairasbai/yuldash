package com.yuldash.app

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuldash.app.data.ApiClient
import com.yuldash.app.ui.theme.YuldashTheme
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.BufferedInputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Production YuldashApp navigation in ComponentActivity. Activity recreation retains VM; no OS process kill. */
@RunWith(AndroidJUnit4::class)
class CompletedNavigationInstrumentedTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext.applicationContext
    private val app get()=context as Application
    private val vm get()=ViewModelProvider(compose.activity)[YuldashViewModel::class.java]
    private val mounted=mutableStateOf(true);private val recreated=AtomicInteger()
    private val token="header.eyJzdWIiOiIxMSJ9.signature"
    private lateinit var server:Loopback
    private val lifecycle=object:Application.ActivityLifecycleCallbacks {
        override fun onActivityPostCreated(activity:Activity,state:Bundle?) {
            if(activity.javaClass==ComponentActivity::class.java) {assertNotNull(state);recreated.incrementAndGet();attach(activity as ComponentActivity)}
        }
        override fun onActivityCreated(activity:Activity,state:Bundle?)=Unit
        override fun onActivityStarted(activity:Activity)=Unit
        override fun onActivityResumed(activity:Activity)=Unit
        override fun onActivityPaused(activity:Activity)=Unit
        override fun onActivityStopped(activity:Activity)=Unit
        override fun onActivitySaveInstanceState(activity:Activity,state:Bundle)=Unit
        override fun onActivityDestroyed(activity:Activity)=Unit
    }
    @Before fun prepare() {
        server=Loopback();ApiClient.resetForTest();ApiClient.init(context)
        ApiClient.testBaseUrl=server.url;ApiClient.testTimeoutMs=60000;ApiClient.saveToken(token);assertFalse(ApiClient.secureStorageUnavailable)
        context.getSharedPreferences("yuldash_prefs",0).edit().putBoolean("onboarding_completed",false).commit();AppPrefs.setLanguage(context,AppLanguage.Ru)
        DeepLink.pendingCompletedBookingId.value=null;DeepLink.pendingRideId.value=null
        app.registerActivityLifecycleCallbacks(lifecycle)
    }
    @After fun cleanup() {
        app.unregisterActivityLifecycleCallbacks(lifecycle);compose.runOnIdle {mounted.value=false};compose.waitForIdle()
        ApiClient.resetForTest();ApiClient.testTimeoutMs=null;server.close();assertTrue(server.failures.toString(),server.failures.isEmpty())
    }
    @Composable private fun Render() {YuldashTheme {if(mounted.value) YuldashApp()}}
    private fun attach(activity:ComponentActivity) {activity.setContent {Render()}}
    private fun mount(language:AppLanguage=AppLanguage.Ru) {
        AppPrefs.setLanguage(context,language)
        compose.runOnUiThread {vm.screen.value=Screen.ActiveTrip;vm.activeBookingId.value=42;vm.startHomeTab.value=HomeTab.Profile;attach(compose.activity)}
        completed()
    }
    private fun completed() {
        // The saved lazy scroll may place the hero offscreen when returning to an edited draft.
        compose.waitUntil(15000) {compose.onAllNodesWithTag("rideshareCompletedList").fetchSemanticsNodes().isNotEmpty()};compose.waitForIdle()
        compose.onNodeWithTag("rideshareCompletedList").assertIsDisplayed()
        assertEquals(Screen.ActiveTrip,vm.screen.value);assertEquals(42,vm.activeBookingId.value);assertNull(vm.activeTrip.value)
    }
    private fun tag(value:String):SemanticsNodeInteraction {
        if(value!="rideshareRatingSubmit") compose.onNodeWithTag("rideshareCompletedList").performScrollToNode(hasTestTag(value))
        return compose.onNodeWithTag(value)
    }
    private fun text(value:String):SemanticsNodeInteraction {
        compose.onNodeWithTag("rideshareCompletedList").performScrollToNode(hasText(value));return compose.onNodeWithText(value)
    }
    private fun receipt(language:AppLanguage) {
        text(if(language==AppLanguage.Ru) "Квитанция" else "Сәфәр квитанцияһы").performTouchInput {click()}
        compose.waitUntil(10000) {vm.screen.value==Screen.TripReceipt};compose.waitForIdle()
        compose.onNodeWithText("Квитанция").assertIsDisplayed()
        compose.waitUntil(10000) {server.records.count {it.first=="GET /trips/42/receipt"}>=2}
        loadedReceipt(language)
    }
    private fun loadedReceipt(language:AppLanguage) {
        val label=if(language==AppLanguage.Ru) "Поездка завершена" else "Сәфәр тамамланды"
        compose.waitUntil(10000) {compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText(label).assertIsDisplayed()
    }
    private fun recreate() {
        val oldActivity=compose.activity;val oldVm=vm
        compose.activityRule.scenario.recreate();compose.waitUntil(15000) {recreated.get()==1};compose.waitForIdle()
        assertNotSame(oldActivity,compose.activity);assertSame("Configuration recreation must retain Activity ViewModel",oldVm,vm)
        assertEquals(Screen.TripReceipt,vm.screen.value)
    }
    private fun back(language:AppLanguage) {
        compose.onNodeWithContentDescription(if(language==AppLanguage.Ru) "Назад" else "Артҡа").performTouchInput {click()};completed()
    }
    private fun hideKeyboard()=compose.runOnIdle {(compose.activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(compose.activity.window.decorView.windowToken,0)}
    private fun shot(name:String,node:SemanticsNodeInteraction) {
        compose.waitForIdle();node.assertIsDisplayed().captureToImage();instrumentation.waitForIdleSync();Thread.sleep(150)
        val b=instrumentation.uiAutomation.takeScreenshot();File(context.filesDir,"b02-completed-navigation-$name.png").outputStream().use {assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,it))};b.recycle()
    }
    @Test fun russianReceiptRecreationAndBackRestoreExactRatingDraftThroughYuldashApp() {
        mount();tag("rideshareStar4").performTouchInput {click()};text("Приехал вовремя").performTouchInput {click()}
        text("Добавить пару слов").performTouchInput {click()};tag("rideshareReviewText").performTextInput("Спасибо за дорогу");hideKeyboard()
        receipt(AppLanguage.Ru);shot("Ru-receipt",compose.onNodeWithText("Квитанция"));recreate();back(AppLanguage.Ru);hideKeyboard()
        tag("rideshareReviewText").assertTextEquals("Спасибо за дорогу");text("Приехал вовремя").assertIsSelected();tag("rideshareRatingSubmit").assertIsEnabled()
        assertEquals(0,server.posts("rate"));shot("Ru-draft",tag("rideshareReviewText"))
        tag("rideshareRatingSubmit").performTouchInput {click()};compose.waitUntil(10000) {server.posts("rate")==1}
        val r=server.records.single {it.first=="POST /bookings/42/rate"};val p=JSONObject(r.third)
        assertEquals(4,p.getInt("stars"));assertEquals("ontime",p.getString("tags"));assertEquals("Спасибо за дорогу",p.getString("text"));assertEquals("Bearer $token",r.second)
        Log.i("B02CompletedNavDevice","verified Ru YuldashApp receipt42->ActivityRecreate1 distinctActivity=true retainedVm=true savedBundle=true->BackCompleted42 draft4/ontime/text ratingPosts=1 manualOnly=true activeTrip=null pid=${Process.myPid()}")
    }
    @Test fun bashkirReceiptRecreationAndBackKeepSavedThanksWithoutRepeatThroughYuldashApp() {
        mount(AppLanguage.Ba);text("«Рәхмәт» әйтеү").performTouchInput {click()};compose.waitUntil(10000) {server.posts("thanks")==1}
        compose.waitUntil(10000) {runCatching {text("Рәхмәт ебәрелде").assertIsEnabled();true}.getOrDefault(false)}
        receipt(AppLanguage.Ba);recreate();loadedReceipt(AppLanguage.Ba);shot("Ba-receipt",compose.onNodeWithText("Квитанция"));back(AppLanguage.Ba)
        text("Рәхмәт һаҡланды").assertIsDisplayed();shot("Ba-saved",text("Рәхмәт һаҡланды"));text("Рәхмәт ебәрелде").performTouchInput {click()}
        assertEquals(1,server.posts("thanks"));assertEquals(0,server.posts("rate"))
        assertEquals("Bearer $token",server.records.single {it.first=="POST /bookings/42/thanks"}.second)
        Log.i("B02CompletedNavDevice","verified Ba YuldashApp receipt42->ActivityRecreate1 distinctActivity=true retainedVm=true savedBundle=true->BackCompleted42 thanksPosts=1 saved=true ratePosts=0 activeTrip=null pid=${Process.myPid()}")
    }
    private inner class Loopback:AutoCloseable {
        val listener=ServerSocket(0,32,InetAddress.getByName("127.0.0.1"));val url="http://127.0.0.1:${listener.localPort}"
        val records=CopyOnWriteArrayList<Triple<String,String?,String>>();val failures=CopyOnWriteArrayList<String>();private val sockets=CopyOnWriteArrayList<Socket>()
        @Volatile private var thanked=false;@Volatile private var closed=false;private val workers=Executors.newCachedThreadPool()
        private val acceptor=thread(name="audit-completed-navigation") {
            try {while(!closed) {val s=listener.accept();sockets+=s;workers.execute {serve(s)}}} catch(e:Exception) {if(!closed) failures+=e.toString()}
        }
        fun posts(kind:String)=records.count {it.first=="POST /bookings/42/$kind"}
        private fun line(input:BufferedInputStream):String {
            val b=ArrayList<Byte>();while(true) {val n=input.read();check(n>=0);if(n==10) break;if(n!=13) b+=n.toByte()};return b.toByteArray().toString(Charsets.US_ASCII)
        }
        private fun serve(s:Socket) {
            try {s.use {
                val input=BufferedInputStream(s.getInputStream());val first=line(input).split(' ');val method=first[0];val path=first[1].substringBefore('?')
                val headers=mutableMapOf<String,String>();while(true) {val h=line(input);if(h.isEmpty()) break;headers[h.substringBefore(':').lowercase()]=h.substringAfter(':').trim()}
                val body=ByteArray(headers["content-length"]?.toIntOrNull() ?: 0);var read=0;while(read<body.size) {val n=input.read(body,read,body.size-read);check(n>0);read+=n}
                val key="$method $path";records+=Triple(key,headers["authorization"],body.toString(Charsets.UTF_8))
                val json=when(key) {
                    "GET /bookings/mine" -> """{"items":[{"id":42,"ride_id":9,"status":"done","seats":1,"from_city":"Уфа","to_city":"Бирск","price":400,"driver_name":"Водитель"}]}"""
                    "GET /bookings/42/role" -> """{"role":"passenger","status":"done","driver_phase":""}"""
                    "GET /bookings/42/messages" -> """{"items":[]}"""
                    "GET /bookings/42/boarding-code" -> """{"code":""}"""
                    "GET /bookings/42/tip" -> """{"already_thanked":$thanked,"driver_name":"Водитель"}"""
                    "GET /trips/42/receipt" -> """{"booking_id":42,"ride_id":9,"role":"passenger","from_city":"Уфа","to_city":"Бирск","amount":400,"pay_method":"cash","paid":true,"counterparty_name":"Водитель","my_stars":0}"""
                    "POST /bookings/42/thanks" -> {thanked=true;"{}"}
                    "POST /bookings/42/rate" -> "{}"
                    else -> {check(!path.startsWith("/bookings/") || method=="GET") {"Unexpected booking write $key"};"""{"items":[],"role":"passenger"}"""}
                }.toByteArray(Charsets.UTF_8)
                val output=s.getOutputStream();output.write("HTTP/1.1 200 Local\r\nContent-Type: application/json\r\nContent-Length: ${json.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII));output.write(json);output.flush()
            }} catch(e:Exception) {if(!closed) failures+=e.toString()} finally {sockets.remove(s)}
        }
        override fun close() {closed=true;listener.close();sockets.forEach {runCatching {it.close()}};workers.shutdownNow();assertTrue(workers.awaitTermination(5,TimeUnit.SECONDS));acceptor.join(5000);assertFalse(acceptor.isAlive)}
    }
}
