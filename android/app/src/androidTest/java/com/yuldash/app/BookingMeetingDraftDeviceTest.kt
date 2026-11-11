package com.yuldash.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.OfflineTripSnapshots
import com.yuldash.app.data.TripPassStore
import com.yuldash.app.ui.theme.YuldashTheme
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Device acceptance for PR 220. Uses the packaged base and a synthetic local HTTP server. */
@RunWith(AndroidJUnit4::class)
class BookingMeetingDraftDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val mounted = mutableStateOf(false)
    private lateinit var server: Loopback

    @Before fun setup() {
        assertTrue(BuildConfig.YULDASH_NATIVE_QA_PINNED_API)
        assertEquals("http://127.0.0.1:8000", BuildConfig.YULDASH_API_BASE_URL)
        server = Loopback()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = 45000
        ApiClient.serverUnreachable.value = false
        ApiClient.init(context)
        ApiClient.logout()
        TripPassStore.init(context)
        OfflineTripSnapshots.init(context)
        runBlocking {
            ApiClient.verifyCode("+70000000000", "000000", "QA passenger").getOrThrow()
        }
        assertEquals(7, OfflineTripSnapshots.currentOwner()?.userId)
    }

    @After fun cleanup() {
        if (!::server.isInitialized) return
        server.release.countDown()
        try {
            if (mounted.value) {
                compose.runOnIdle { mounted.value = false }
                compose.waitForIdle()
            }
            ApiClient.logout()
            ApiClient.resetForTest()
            ApiClient.testTimeoutMs = null
        } finally {
            server.close()
        }
    }

    @Test fun ruLight() = journey(AppLanguage.Ru, false)
    @Test fun ruDark() = journey(AppLanguage.Ru, true)
    @Test fun baLight() = journey(AppLanguage.Ba, false)
    @Test fun baDark() = journey(AppLanguage.Ba, true)

    private fun journey(language: AppLanguage, dark: Boolean) {
        val ba = language == AppLanguage.Ba
        val edit = if (ba) "Ултырыу урынын үҙгәртеү" else "Изменить место посадки"
        val loading = if (ba) "Сәфәр раҫланыуын яңыртабыҙ" else "Обновляем подтверждение поездки"
        val title = if (ba) "Һине ҡайҙан алырға?" else "Где тебя забрать?"
        val save = if (ba) "Әҙер" else "Готово"
        val draft = "QA draft by the gate"
        val editor = compose.onNode(hasSetTextAction())
        mounted.value = true
        compose.setContent {
            YuldashTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalAppLanguage provides language) {
                    if (mounted.value) BookingScreen(
                        ride = bookingLinkStubRide(11).copy(id = "33"),
                        bookingId = 11,
                        ads = emptyList(),
                        adStats = emptyMap(),
                        onBack = {},
                        onMessage = {},
                        onAdImpression = {},
                        onAdClick = {},
                        bookingStatus = "confirmed",
                        onConfirmRide = { _, _, _, _, _ -> error("Unexpected booking creation") },
                    )
                }
            }
        }
        compose.waitUntil(15000) { server.held.count == 0L }
        val details = requireNotNull(OfflineTripSnapshots.projection(11)?.details)
        val generation = ApiClient.queueSessionGeneration()
        compose.onNodeWithText(loading).assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(edit))
        compose.onNodeWithText(edit).assertIsEnabled().performClick()
        compose.onNodeWithText(title).assertIsDisplayed()
        editor.performScrollTo().performTextReplacement(draft)
        editor.assertIsDisplayed().assertTextContains(draft)
        assertTrue(server.posts.isEmpty())
        assertTrue(OfflineTripSnapshots.currentBoardingCode(11).isNullOrBlank())

        server.release.countDown()
        compose.waitUntil(15000) { TripPassStore.load(context, 11)?.boardingCode == "4821" }
        compose.waitForIdle()
        assertEquals("4821", OfflineTripSnapshots.currentBoardingCode(11))
        assertSame(details, OfflineTripSnapshots.projection(11)?.details)
        assertTrue(OfflineTripSnapshots.privateDetails(details))
        assertEquals(generation, ApiClient.queueSessionGeneration())
        editor.assertIsDisplayed().assertTextContains(draft)
        compose.onNodeWithText(save).performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("QA conflict 409").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("QA conflict 409").onLast().assertIsDisplayed()
        val post = server.posts.single()
        assertEquals("Bearer $TOKEN", post.authorization)
        assertEquals(2, post.body.getInt("base_version"))
        assertEquals("pickup", post.body.getString("kind"))
        assertEquals(draft, post.body.getJSONObject("point").getString("note"))
        assertEquals(1, server.requests.count { it == "GET /bookings/11/boarding-code" })
        assertTrue(server.failures.toString(), server.failures.isEmpty())
    }

    private class Loopback : AutoCloseable {
        private val listener = ServerSocket(8000, 16, InetAddress.getByName("127.0.0.1"))
        private val workers = Executors.newCachedThreadPool()
        private val sockets = CopyOnWriteArrayList<Socket>()
        val posts = CopyOnWriteArrayList<Post>()
        val requests = CopyOnWriteArrayList<String>()
        val failures = CopyOnWriteArrayList<String>()
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)

        private val accept = thread(isDaemon = true, name = "pr220-device-loopback") {
            while (!listener.isClosed) {
                val socket = runCatching { listener.accept() }.getOrNull() ?: break
                sockets += socket
                workers.submit {
                    try {
                        socket.use(::respond)
                    } catch (failure: Exception) {
                        if (!listener.isClosed) failures += failure.javaClass.simpleName
                    } finally {
                        sockets -= socket
                    }
                }
            }
        }

        private fun respond(socket: Socket) {
            socket.soTimeout = 15000
            val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
            val request = reader.readLine()?.split(' ') ?: return
            val route = request[0] + " " + request[1].substringBefore('?')
            val headers = generateSequence { reader.readLine()?.takeIf(String::isNotEmpty) }
                .associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
            // All fixture writes use ASCII. Length is therefore both bytes and characters.
            val payload = CharArray(headers["content-length"]?.toInt() ?: 0)
            var read = 0
            while (read < payload.size) {
                val n = reader.read(payload, read, payload.size - read)
                check(n > 0)
                read += n
            }
            requests += route
            val authorized = headers["authorization"] == "Bearer $TOKEN"
            val (status, response) = when {
                route == "POST /auth/verify" ->
                    200 to """{"access_token":"$TOKEN","user":{"name":"QA passenger","role":"passenger"}}"""
                route == "GET /pickup-points" -> 200 to """{"items":[]}"""
                !authorized -> 401 to "{}"
                route == "GET /bookings/11/details" -> 200 to DETAILS
                route == "GET /bookings/11/role" ->
                    200 to """{"role":"passenger","status":"confirmed","driver_phase":"arriving"}"""
                route == "GET /bookings/11/boarding-code" -> {
                    held.countDown()
                    check(release.await(35, TimeUnit.SECONDS))
                    200 to """{"code":"4821"}"""
                }
                route == "POST /bookings/11/meeting-point" -> {
                    posts += Post(headers["authorization"], JSONObject(String(payload)))
                    409 to """{"detail":{"code":"qa_device_conflict","ru":"QA conflict 409","ba":"QA conflict 409"}}"""
                }
                else -> 404 to "{}"
            }
            val bytes = response.toByteArray(Charsets.UTF_8)
            socket.getOutputStream().apply {
                write("HTTP/1.1 $status QA\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                write(bytes)
                flush()
            }
        }

        override fun close() {
            listener.close()
            sockets.forEach { runCatching { it.close() } }
            accept.join(2000)
            workers.shutdownNow()
        }
    }

    private data class Post(val authorization: String?, val body: JSONObject)

    private companion object {
        const val TOKEN = "e30.eyJzdWIiOiI3In0.qa"
        const val DETAILS = """{"booking_id":11,"ride_id":33,"role":"passenger","status":"confirmed",
            "contact_unlocked":true,"from_city":"Уфа","to_city":"Сибай","depart_at":"2026-11-12T10:00:00Z",
            "seats":1,"price":500,"pay_amount":200,"pay_method":"cash","driver_name":"QA Driver",
            "driver_phone":"+70000000000","driver_car":"QA car","driver_plate":"QA plate",
            "pickup":"QA stop","ride_has_stops":true,"meet_lat":55.1,"meet_lng":55.2,"meet_note":"QA initial note",
            "meet_landmark_ru":"QA RU","meet_landmark_ba":"QA BA","meeting_version":2}"""
    }
}
