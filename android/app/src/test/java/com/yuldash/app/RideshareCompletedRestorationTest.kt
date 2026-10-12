package com.yuldash.app

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Saved Compose state, actual Completed/API/HTTP. Not Activity recreation or process death. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RideshareCompletedRestorationTest {
    @get:Rule val compose = createComposeRule()
    private val restoration = StateRestorationTester(compose)
    private val mounted = mutableStateOf(true)
    private val booking = mutableStateOf(42)
    private lateinit var server: MockWebServer
    private val records = CopyOnWriteArrayList<Triple<String, String?, String>>()
    private val timeouts = CopyOnWriteArrayList<String>()
    private val receipts = AtomicInteger(); private val tips = AtomicInteger()
    private val started = CountDownLatch(1); private val release = CountDownLatch(1)
    private val dispatched = CountDownLatch(1)
    private var heldKind: String? = null
    private var commitBeforeHold = false
    @Volatile private var thanked = false
    @Volatile private var serverStars = 0
    @Volatile private var serverTags = ""
    private var closes = 0
    private val tokenA = "header.eyJzdWIiOiIxMSJ9.signature"
    private val tokenB = "header.eyJzdWIiOiIzMyJ9.signature"

    @Before fun prepare() {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val key = "${request.method} ${request.path}"; val body = request.body.readUtf8()
                    records += Triple(key, request.getHeader("Authorization"), body)
                    val id = request.path.orEmpty().split('/').getOrNull(2)?.toIntOrNull() ?: 42
                    val kind = when (key) {
                        "GET /trips/$id/receipt" -> "receipt"
                        "GET /bookings/$id/tip" -> "tip"
                        "POST /bookings/$id/rate" -> "rating"
                        "POST /bookings/$id/thanks" -> "thanks"
                        "POST /bookings/$id/lost-item" -> "lost"
                        else -> return MockResponse().setResponseCode(404).setBody("{}")
                    }
                    when (kind) { "receipt" -> receipts.incrementAndGet(); "tip" -> tips.incrementAndGet() }
                    fun commit() {
                        if (kind == "thanks") thanked = true
                        if (kind == "rating") { val payload = JSONObject(body); serverStars = payload.getInt("stars"); serverTags = payload.optString("tags") }
                    }
                    if (kind in listOf("rating", "thanks", "lost")) {
                        if (commitBeforeHold) commit()
                        if (kind == heldKind && records.count { it.first == key } == 1) {
                            started.countDown(); if (!release.await(40, TimeUnit.SECONDS)) timeouts += kind
                            dispatched.countDown()
                        }
                        if (!commitBeforeHold) commit()
                    }
                    val response = when (kind) {
                        "receipt" -> """{"booking_id":$id,"ride_id":9,"role":"passenger","from_city":"Уфа","to_city":"Бирск","amount":400,"pay_method":"cash","paid":true,"counterparty_name":"Водитель","my_stars":${if (id == 42) serverStars else 0},"my_rating_tags":"${if (id == 42) serverTags else ""}"}"""
                        "tip" -> """{"driver_name":"Водитель","already_thanked":$thanked}"""
                        else -> "{}"
                    }
                    return MockResponse().setResponseCode(200).setBody(response)
                }
            }; start()
        }
        ApiClient.resetForTest(); ApiClient.init(ApplicationProvider.getApplicationContext<Context>())
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/'); ApiClient.testTimeoutMs = 60000; ApiClient.saveToken(tokenA)
    }
    @After fun cleanup() {
        release.countDown(); compose.runOnIdle { mounted.value = false }; pump(300)
        ApiClient.resetForTest(); ApiClient.testTimeoutMs = null; server.shutdown()
        assertTrue("Fixture timeout: $timeouts", timeouts.isEmpty())
    }
    private fun pump(ms: Long = 700) {
        repeat((ms / 100).toInt()) { compose.mainClock.advanceTimeBy(100); Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100)); Thread.sleep(10) }
        compose.waitForIdle()
    }
    private fun await(predicate: () -> Boolean) = compose.waitUntil(12000) {
        compose.mainClock.advanceTimeBy(100); Shadows.shadowOf(Looper.getMainLooper()).idle(); predicate()
    }
    private fun mount() {
        restoration.setContent {
            if (mounted.value) CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                RideshareCompletedScreen(booking.value, null, "passenger", "cash", 400,
                    onClose = { closes++ }, onOpenReceipt = {}, onSupport = {})
            }
        }
        await { receipts.get() == 1 && tips.get() == 1 }; pump()
    }
    private fun restore() {
        val r = receipts.get(); val t = tips.get()
        restoration.emulateSavedInstanceStateRestore()
        await { receipts.get() >= r + 1 && tips.get() >= t + 1 }; pump()
    }
    private fun tag(value: String): SemanticsNodeInteraction {
        if (value != "rideshareRatingSubmit") compose.onNodeWithTag("rideshareCompletedList").performScrollToNode(hasTestTag(value))
        return compose.onNodeWithTag(value)
    }
    private fun text(value: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("rideshareCompletedList").performScrollToNode(hasText(value)); return compose.onNodeWithText(value)
    }
    private fun draft() {
        tag("rideshareStar4").performClick(); pump(300)
        text("Приехал вовремя").performClick(); text("Добавить пару слов").performClick()
        tag("rideshareReviewText").performTextInput("Спасибо за дорогу")
    }
    private fun checkDraft() {
        assertEquals("Спасибо за дорогу", tag("rideshareReviewText").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        text("Приехал вовремя").assertIsSelected()
    }
    private fun node(kind: String) = when (kind) {
        "rating" -> tag("rideshareRatingSubmit")
        "thanks" -> text("Сказать «Рәхмәт»")
        else -> tag("rideshareLostItem")
    }
    private fun key(kind: String, id: Int = 42) = "POST /bookings/$id/" + when (kind) { "rating" -> "rate"; "thanks" -> "thanks"; else -> "lost-item" }
    private fun posts(kind: String, id: Int = 42) = records.count { it.first == key(kind, id) }
    private fun start(kind: String): () -> Boolean {
        heldKind = kind; mount(); if (kind == "rating") draft()
        val callback = node(kind).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        node(kind).performClick(); await { started.count == 0L }; pump(); node(kind).assertIsNotEnabled()
        return callback
    }
    private fun releaseOld() { release.countDown(); await { dispatched.count == 0L }; pump() }

    @Test fun draftSurvivesRestoreAndManualRatingSendsExactPayload() {
        mount(); draft(); restore(); checkDraft(); assertEquals(0, posts("rating"))
        node("rating").performClick(); await { posts("rating") == 1 }; pump()
        val payload = JSONObject(records.single { it.first == key("rating") }.third)
        assertEquals(4, payload.getInt("stars")); assertEquals("ontime", payload.getString("tags")); assertEquals("Спасибо за дорогу", payload.getString("text"))
        val personal = records.filterNot { it.first == "GET /health" }
        assertTrue("Personal requests: $personal", personal.all { it.second == "Bearer $tokenA" })
        assertTrue("Public health must not carry bearer", records.filter { it.first == "GET /health" }.all { it.second == null })
    }
    @Test fun confirmedRatingRestoresDoneWithoutDuplicatePost() {
        serverStars = 4; serverTags = "ontime"; mount(); restore(); node("rating").performClick()
        assertEquals(1, closes); assertEquals(0, posts("rating"))
    }
    @Test fun confirmedThanksRestoresWithoutDuplicatePost() {
        thanked = true; mount(); restore(); text("Благодарность сохранена").assertIsDisplayed()
        text("Рәхмәт передано").performClick(); assertEquals(0, posts("thanks"))
    }
    @Test fun confirmedLostOpeningSurvivesWithoutAutomaticRepeat() {
        mount(); node("lost").performClick(); await { posts("lost") == 1 }; pump(); restore()
        text("Чат поездки снова открыт").assertIsDisplayed(); node("lost").performClick(); assertEquals(1, posts("lost"))
    }
    @Test fun pendingRatingRestoresEditableDraftWithoutAutomaticPost() {
        start("rating"); restore(); checkDraft(); tag("rideshareReviewText").assertIsEnabled(); node("rating").assertIsEnabled()
        assertEquals(1, posts("rating")); node("rating").performClick(); await { posts("rating") == 2 }; pump()
        val payloads = records.filter { it.first == key("rating") }.map { JSONObject(it.third).toString() }
        assertEquals(payloads[0], payloads[1]); releaseOld(); assertEquals(2, posts("rating"))
    }
    @Test fun pendingThanksRestoresReadyAfterFreshUnsentSnapshotWithoutAutomaticPost() {
        start("thanks"); restore(); node("thanks").assertIsEnabled(); assertEquals(1, posts("thanks"))
        releaseOld(); node("thanks").assertIsEnabled(); assertEquals(1, posts("thanks"))
        // Early GET=false is only a snapshot; the old server action may commit after it.
    }
    @Test fun committedPendingThanksRestoresSavedStateWithoutStuckBusy() {
        commitBeforeHold = true; start("thanks"); restore()
        text("Рәхмәт передано").assertIsEnabled(); text("Благодарность сохранена").assertIsDisplayed()
        text("Рәхмәт передано").performClick(); releaseOld(); assertEquals(1, posts("thanks"))
    }
    @Test fun pendingLostRestoresButtonWithoutAutomaticRepeat() {
        start("lost"); restore(); node("lost").assertIsEnabled(); assertEquals(1, posts("lost"))
        releaseOld(); node("lost").assertIsEnabled(); assertEquals(1, posts("lost"))
        // A second explicit POST has server-side effects; this test deliberately does not send it.
    }
    @Test fun anotherBookingCannotRestorePreviousRatingDraft() {
        mount(); draft(); compose.runOnIdle { booking.value = 43 }; restore()
        tag("rideshareRatingCard"); compose.onNodeWithTag("rideshareReviewText").assertDoesNotExist(); node("rating").assertIsNotEnabled()
        tag("rideshareStar2").performClick(); node("rating").performClick(); await { posts("rating", 43) == 1 }
        assertEquals(0, posts("rating", 42)); assertEquals(2, JSONObject(records.single { it.first == key("rating", 43) }.third).getInt("stars"))
    }
    @Test fun anotherLoginCannotRestorePreviousDraftOrUsePreviousToken() {
        mount(); draft(); compose.runOnIdle { ApiClient.saveToken(tokenB) }; restore()
        tag("rideshareRatingCard"); compose.onNodeWithTag("rideshareReviewText").assertDoesNotExist(); node("rating").assertIsNotEnabled()
        tag("rideshareStar2").performClick(); node("rating").performClick(); await { posts("rating") == 1 }
        assertEquals("Bearer $tokenB", records.single { it.first == key("rating") }.second)
    }
    private fun retained(kind: String) {
        val old = start(kind); restore(); compose.runOnIdle { old(); old() }; pump()
        assertEquals(1, posts(kind)); releaseOld(); compose.runOnIdle { old() }; pump(); assertEquals(1, posts(kind))
    }
    @Test fun retainedRatingBeforeRestorationCannotSendFromOldScope() = retained("rating")
    @Test fun retainedThanksBeforeRestorationCannotSendFromOldScope() = retained("thanks")
    @Test fun retainedLostBeforeRestorationCannotSendFromOldScope() = retained("lost")
}
