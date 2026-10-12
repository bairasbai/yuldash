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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Actual ComponentActivity.recreate and saved Compose state; no cold process or full app navigation. */
@RunWith(AndroidJUnit4::class)
class CompletedRestorationInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext.applicationContext
    private val app get() = context as Application
    private val mounted = mutableStateOf(true)
    private var language = AppLanguage.Ru
    private val recreated = AtomicInteger(); private val closes = AtomicInteger()
    private val tokenA = "header.eyJzdWIiOiIxMSJ9.signature"
    private lateinit var server: Loopback
    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityPostCreated(activity: Activity, state: Bundle?) {
            if (activity is ComponentActivity && activity.javaClass == ComponentActivity::class.java) {
                assertNotNull("Recreated Activity must receive saved state", state)
                recreated.incrementAndGet(); attach(activity)
            }
        }
        override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
    @Before fun prepare() {
        server = Loopback(); ApiClient.resetForTest(); ApiClient.init(context)
        ApiClient.testBaseUrl = server.url; ApiClient.testTimeoutMs = 60000; ApiClient.saveToken(tokenA)
        assertFalse(ApiClient.secureStorageUnavailable)
        app.registerActivityLifecycleCallbacks(lifecycle)
    }
    @After fun cleanup() {
        server.release.countDown(); app.unregisterActivityLifecycleCallbacks(lifecycle)
        compose.runOnIdle { mounted.value = false }; compose.waitForIdle()
        ApiClient.resetForTest(); ApiClient.testTimeoutMs = null; server.close()
        assertTrue(server.failures.toString(), server.failures.isEmpty())
    }
    @Composable private fun Render() {
        YuldashTheme { CompositionLocalProvider(LocalAppLanguage provides language) {
            if (mounted.value) RideshareCompletedScreen(42, null, "passenger", "cash", 400,
                onClose = { closes.incrementAndGet(); mounted.value = false }, onOpenReceipt = {}, onSupport = {})
        } }
    }
    // Initial and recreated compositions must use the same saveable-state callsite.
    private fun attach(activity: ComponentActivity) { activity.setContent { Render() } }
    private fun mount() { compose.runOnUiThread { attach(compose.activity) }; compose.waitUntil(15000) { server.receipts.get() == 1 && server.tips.get() == 1 }; compose.waitForIdle() }
    private fun tag(value: String): SemanticsNodeInteraction {
        if (value != "rideshareRatingSubmit") compose.onNodeWithTag("rideshareCompletedList").performScrollToNode(hasTestTag(value))
        return compose.onNodeWithTag(value)
    }
    private fun text(value: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("rideshareCompletedList").performScrollToNode(hasText(value)); return compose.onNodeWithText(value)
    }
    private fun hideKeyboard() = compose.runOnIdle {
        (compose.activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0)
    }
    private fun recreate() {
        val old = compose.activity
        compose.activityRule.scenario.recreate()
        compose.waitUntil(15000) { recreated.get() == 1 && server.receipts.get() == 2 && server.tips.get() == 2 }
        compose.waitForIdle(); assertNotSame(old, compose.activity)
    }
    private fun shot(name: String, target: SemanticsNodeInteraction) {
        compose.waitForIdle(); target.assertIsDisplayed().captureToImage(); instrumentation.waitForIdleSync(); Thread.sleep(150)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(context.filesDir, "b02-completed-restoration-$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }; bitmap.recycle()
    }
    @Test fun russianPendingRatingRecreatesEditableDraftAndOnlyManualResubmission() {
        server.held = "rating"; mount()
        tag("rideshareStar4").performTouchInput { click() }; text("Приехал вовремя").performTouchInput { click() }
        text("Добавить пару слов").performTouchInput { click() }; tag("rideshareReviewText").performTextInput("Спасибо за дорогу"); hideKeyboard()
        compose.onNodeWithTag("rideshareRatingSubmit").performTouchInput { click() }
        compose.waitUntil(10000) { server.started.count == 0L }
        tag("rideshareReviewText").assertIsNotEnabled(); shot("Ru-pending", tag("rideshareReviewText"))
        recreate(); hideKeyboard()
        assertEquals("Спасибо за дорогу", tag("rideshareReviewText").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        tag("rideshareReviewText").assertIsEnabled(); text("Приехал вовремя").assertIsSelected()
        compose.onNodeWithTag("rideshareRatingSubmit").assertIsEnabled(); assertEquals(1, server.posts("rating"))
        shot("Ru-restored", tag("rideshareReviewText"))
        compose.onNodeWithTag("rideshareRatingSubmit").performTouchInput { click() }
        compose.waitUntil(10000) { server.posts("rating") == 2 }
        compose.waitUntil(10000) { runCatching { text("Оценка сохранена").assertIsDisplayed(); true }.getOrDefault(false) }
        val payloads = server.records.filter { it.first == "POST /bookings/42/rate" }.map { JSONObject(it.third) }
        assertEquals(2, payloads.size); payloads.forEach { assertEquals(4,it.getInt("stars")); assertEquals("ontime",it.getString("tags")); assertEquals("Спасибо за дорогу",it.getString("text")) }
        server.release.countDown(); compose.waitUntil(10000) { server.replied.count == 0L }
        compose.onNodeWithTag("rideshareRatingSubmit").performTouchInput { click() }; compose.waitUntil(5000) { closes.get() == 1 }
        assertEquals(2, server.posts("rating")); assertTrue(server.records.filter { it.first != "GET /health" }.all { it.second == "Bearer $tokenA" })
        Log.i("B02CompletedRestoreDevice", "verified Ru ActivityRecreate=1 distinctActivity=true savedBundle=true draft4/ontime/text preserved receiptGets=2 tipGets=2 ratingPosts=2 manualOnly=true close=1 pid=${Process.myPid()}")
    }
    @Test fun bashkirCommittedThanksRecreatesSavedFlagWithoutStuckBusyOrRepeat() {
        language = AppLanguage.Ba; server.held = "thanks"; mount()
        val label = "«Рәхмәт» әйтеү"; val old = text(label).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        text(label).performTouchInput { click() }; compose.waitUntil(10000) { server.started.count == 0L }
        text(label).assertIsNotEnabled(); shot("Ba-pending", text(label))
        recreate()
        compose.waitUntil(10000) { runCatching { text("Рәхмәт ебәрелде").assertIsEnabled(); true }.getOrDefault(false) }
        text("Рәхмәт һаҡланды").assertIsDisplayed(); shot("Ba-restored", text("Рәхмәт һаҡланды"))
        text("Рәхмәт ебәрелде").performTouchInput { click() }; compose.runOnIdle { old(); old() }
        assertEquals(1, server.posts("thanks")); server.release.countDown(); compose.waitUntil(10000) { server.replied.count == 0L }
        text("Рәхмәт ебәрелде").assertIsEnabled(); assertEquals(1,server.posts("thanks"))
        assertTrue(server.records.filter { it.first != "GET /health" }.all { it.second == "Bearer $tokenA" })
        Log.i("B02CompletedRestoreDevice", "verified Ba ActivityRecreate=1 distinctActivity=true savedBundle=true receiptGets=2 tipGets=2 thanksPosts=1 saved=true retainedOldBlocked=true pid=${Process.myPid()}")
    }
    private inner class Loopback : AutoCloseable {
        val listener = ServerSocket(0,32,InetAddress.getByName("127.0.0.1")); val url = "http://127.0.0.1:${listener.localPort}"
        val records = CopyOnWriteArrayList<Triple<String,String?,String>>(); val failures = CopyOnWriteArrayList<String>(); val sockets = CopyOnWriteArrayList<Socket>()
        val receipts = AtomicInteger(); val tips = AtomicInteger(); val started = CountDownLatch(1); val release = CountDownLatch(1); val replied = CountDownLatch(1)
        @Volatile var held: String? = null
        @Volatile private var thanked = false
        @Volatile private var closed = false
        private val workers = Executors.newCachedThreadPool()
        private val acceptor = thread(name="audit-completed-restore") {
            try { while(!closed) { val socket=listener.accept(); sockets += socket; workers.execute { serve(socket) } } }
            catch(e:Exception) { if(!closed) failures += e.toString() }
        }
        fun posts(kind:String) = records.count { it.first == "POST /bookings/42/" + if(kind=="rating") "rate" else "thanks" }
        private fun line(input:BufferedInputStream):String {
            val bytes=ArrayList<Byte>(); while(true) { val n=input.read(); check(n>=0); if(n==10) break; if(n!=13) bytes += n.toByte() }; return bytes.toByteArray().toString(Charsets.US_ASCII)
        }
        private fun serve(socket:Socket) {
            try { socket.use {
                val input=BufferedInputStream(socket.getInputStream()); val first=line(input).split(' '); val method=first[0]; val path=first[1]
                val headers=mutableMapOf<String,String>(); while(true) { val h=line(input); if(h.isEmpty()) break;headers[h.substringBefore(':').lowercase()]=h.substringAfter(':').trim() }
                val body=ByteArray(headers["content-length"]?.toIntOrNull() ?: 0); var read=0
                while(read<body.size) { val n=input.read(body,read,body.size-read); check(n>0);read+=n }
                val key="$method $path"; records += Triple(key,headers["authorization"],body.toString(Charsets.UTF_8))
                val kind=when(key) { "GET /trips/42/receipt" -> "receipt"; "GET /bookings/42/tip" -> "tip"; "POST /bookings/42/rate" -> "rating"; "POST /bookings/42/thanks" -> "thanks"; "GET /health" -> "health"; else -> error("Unexpected $key") }
                if(kind=="receipt") receipts.incrementAndGet(); if(kind=="tip") tips.incrementAndGet(); if(kind=="thanks") thanked=true
                val isHeld=kind==held && posts(kind)==1
                if(isHeld) { started.countDown(); if(!release.await(40,TimeUnit.SECONDS)) failures += "$kind timeout" }
                val response=when(kind) {
                    "receipt" -> """{"booking_id":42,"ride_id":9,"role":"passenger","from_city":"Уфа","to_city":"Бирск","amount":400,"pay_method":"cash","paid":true,"counterparty_name":"Водитель","my_stars":0}"""
                    "tip" -> """{"driver_name":"Водитель","already_thanked":$thanked}"""
                    else -> "{}"
                }.toByteArray(Charsets.UTF_8)
                val output=socket.getOutputStream(); output.write("HTTP/1.1 200 Local\r\nContent-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII));output.write(response);output.flush()
                if(isHeld) replied.countDown()
            } } catch(e:Exception) { if(!closed) failures += e.toString() } finally { sockets.remove(socket) }
        }
        override fun close() {
            closed=true; listener.close(); sockets.forEach { runCatching { it.close() } }; workers.shutdownNow()
            assertTrue(workers.awaitTermination(5,TimeUnit.SECONDS));acceptor.join(5000);assertFalse(acceptor.isAlive)
        }
    }
}
