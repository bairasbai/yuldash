package com.yuldash.app

import android.app.Application
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.DialogInterface
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
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
import org.robolectric.shadows.ShadowDialog
import java.time.OffsetDateTime
import java.util.concurrent.CopyOnWriteArrayList

/** Actual form, native date/time dialogs and ApiClient; local HTTP replaces the backend only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CreateRidePublishJourneyTest {
    @get:Rule val compose = createComposeRule()
    private val mounted = mutableStateOf(true)
    private val bodies = CopyOnWriteArrayList<String>()
    private val published = CopyOnWriteArrayList<Ride>()
    private lateinit var server: MockWebServer
    private var rejectFirst = false
    private val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
    private val requests = CopyOnWriteArrayList<String>()
    @Volatile private var created: String? = null

    @Before fun setup() {
        ApiClient.resetForTest()
        ApiClient.init(ApplicationProvider.getApplicationContext<Context>())
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests.add("${request.method} ${request.requestUrl!!.encodedPath}")
                    if (request.method == "POST" && request.path == "/rides") {
                        bodies.add(request.body.readUtf8())
                        return if (rejectFirst && bodies.size == 1) json("""{"detail":"Тестовый временный отказ"}""", 503)
                        else {
                            created = bodies.last()
                            json("""{"id":42}""", 201)
                        }
                    }
                    if (request.requestUrl!!.encodedPath == "/driver/rides") {
                        val record = created?.let { JSONObject(it).put("id", 42).put("seats_left", 3).put("status", "active") }
                        return json("""{"items":[${record ?: ""}]}""")
                    }
                    return json("""{"items":[]}""")
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 2000
        ApiClient.saveToken("local-journey-driver")
    }

    @After fun cleanup() {
        compose.runOnIdle { mounted.value = false }
        owner.viewModelStore.clear()
        ApiClient.logout()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server.shutdown()
    }

    @Test fun driverPublishesEnteredRouteDateSeatsAndPriceThroughRealForm() {
        fillForm()
        publishButton().assertIsEnabled().performClick()
        awaitPublished()
        assertEquals(1, bodies.size)
        verifyPayload(bodies.single())
        assertEquals(1, published.size)
        assertEquals("Published callback must preserve the server ride ID", "42", published.single().id)
        assertEquals("Уфа", published.single().from)
        assertEquals("Сибай", published.single().to)
        assertEquals(750, published.single().price)
        assertEquals(3, published.single().seats)
    }

    @Test fun serverRefusalKeepsFormAndRetryPublishesSameEnteredData() {
        rejectFirst = true
        fillForm()
        publishButton().performClick()
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithText("Тестовый временный отказ").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue("Failure must not navigate as success", published.isEmpty())
        assertEquals(1, bodies.size)
        publishButton().assertIsEnabled().performClick()
        awaitPublished()
        assertEquals(2, bodies.size)
        bodies.forEach(::verifyPayload)
        assertEquals(JSONObject(bodies[0]).toString(), JSONObject(bodies[1]).toString())
        assertEquals(1, published.size)
    }

    @Test fun publicationFromCabinetReturnsToReloadedRouteList() {
        val vm = ViewModelProvider(owner, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
                YuldashViewModel(SavedStateHandle(mapOf("yuldash_screen" to "DriverCabinet"))) as T
        })[YuldashViewModel::class.java]
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(LocalViewModelStoreOwner provides owner,
                LocalAppLanguage provides AppLanguage.Ru) { YuldashApp() }
        }
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            requests.contains("GET /driver/rides")
        }
        compose.waitForIdle()
        list().performScrollToNode(hasText("Опубликовать маршрут"))
        compose.onNodeWithText("Опубликовать маршрут").performClick()
        compose.runOnIdle { assertEquals(Screen.CreateRide, vm.screen.value) }
        fillForm(mount = false)
        publishButton().performClick()
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            created != null && vm.screen.value == Screen.DriverCabinet &&
                requests.count { it == "GET /driver/rides" } >= 2
        }
        compose.waitForIdle()
        list().performScrollToNode(hasText("Опубликована"))
        compose.onNodeWithText("Опубликована").assertIsDisplayed()
        compose.onNodeWithText("Уфа → Сибай").assertIsDisplayed()
        assertEquals(1, bodies.size)
        verifyPayload(bodies.single())
        compose.runOnIdle { assertEquals("42", vm.rides.single { it.id == "42" }.id) }
    }

    private fun fillForm(mount: Boolean = true) {
        if (mount) compose.setContent {
            if (mounted.value) CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                CreateRideScreen(onBack = {}, onPublish = { published.add(it) })
            }
        }
        edit("Откуда", "Уфа")
        edit("Куда", "Сибай")
        list().performScrollToNode(hasText("Дата и время"))
        compose.onNodeWithText("Дата и время").performTouchInput { click() }
        compose.runOnIdle {
            val date = ShadowDialog.getLatestDialog() as DatePickerDialog
            date.updateDate(2030, 0, 2)
            date.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        compose.runOnIdle {
            val time = ShadowDialog.getLatestDialog() as TimePickerDialog
            time.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        edit("Мест", "3")
        edit("Цена, ₽", "750")
    }

    private fun list() = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
    private fun edit(label: String, value: String) {
        list().performScrollToNode(hasText(label))
        compose.onNodeWithText(label).performTextReplacement(value)
    }
    private fun publishButton(): SemanticsNodeInteraction {
        list().performScrollToNode(hasTestTag("publish_btn"))
        return compose.onNodeWithTag("publish_btn")
    }
    private fun awaitPublished() {
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            published.isNotEmpty()
        }
    }
    private fun verifyPayload(raw: String) {
        val body = JSONObject(raw)
        assertEquals("Уфа", body.getString("from_city"))
        assertEquals("Сибай", body.getString("to_city"))
        assertEquals(3, body.getInt("seats_total"))
        assertEquals(750, body.getInt("price"))
        assertEquals("regular", body.getString("category"))
        val departure = OffsetDateTime.parse(body.getString("depart_at"))
        assertEquals("2030-01-02", departure.toLocalDate().toString())
    }
    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code)
        .setHeader("Content-Type", "application/json").setBody(body)
}
