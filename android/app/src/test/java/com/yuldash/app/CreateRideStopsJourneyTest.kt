package com.yuldash.app

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
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

/** Настоящая форма + справочник + публикация через HTTP; при отказе остановка остаётся. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CreateRideStopsJourneyTest {
    @get:Rule val compose = createComposeRule()
    private val mounted = mutableStateOf(true)
    private val bodies = CopyOnWriteArrayList<String>()
    private val queries = CopyOnWriteArrayList<String>()
    private val published = CopyOnWriteArrayList<Ride>()
    private lateinit var server: MockWebServer
    @Volatile private var reject = false
    private val stop = "Берёзовка (Иглинский р-н)"

    @Before fun prepare() {
        ApiClient.resetForTest()
        ApiClient.init(ApplicationProvider.getApplicationContext<Context>())
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    if (path == "/settlements") {
                        val query = request.requestUrl!!.queryParameter("q").orEmpty()
                        queries += query
                        if (query == "Берёзовка") return json("""{"items":[{"id":1,"name_ru":"Берёзовка","name_ba":"Ҡайынлыҡ","kind":"village","region":"РБ","district":"Иглинский р-н","lat":54,"lng":56},{"id":2,"name_ru":"Берёзовка","kind":"village","region":"РБ","district":"Уфимский р-н","lat":54,"lng":56}]}""")
                    }
                    if (request.method == "POST" && path == "/rides") {
                        bodies += request.body.readUtf8()
                        return if (reject) json("""{"detail":"Остановка временно недоступна"}""", 422) else json("""{"id":42}""", 201)
                    }
                    return json("""{"items":[]}""")
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 2000
        ApiClient.saveToken("local-stop-driver")
    }

    @After fun cleanup() {
        compose.runOnIdle { mounted.value = false }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        ApiClient.logout()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server.shutdown()
    }

    private fun mount() {
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                CreateRideScreen(onBack = {}, onPublish = { published += it }, prefillDate = "02.01.2030, 10:00")
            }
        }
        edit("Откуда", "Уфа")
        edit("Куда", "Сибай")
        list().performScrollToNode(hasText("Добавить остановку"))
        compose.onNodeWithText("Добавить остановку").performClick()
        edit("Остановка по пути", stop)
    }

    @Test fun villageSuggestionsDistinguishNamesakesAndSelectedStopSurvivesServerRefusal() {
        reject = true
        mount()
        compose.mainClock.advanceTimeBy(300)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300))
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithText("Берёзовка (Уфимский р-н)").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Берёзовка (Уфимский р-н)").assertExists()
        // Поле и первый пункт имеют одинаковое имя: выбираем именно кликабельную строку справочника.
        val choice = hasText(stop) and hasClickAction() and !hasSetTextAction()
        list().performScrollToNode(choice)
        compose.onNode(choice).performClick()
        publish()
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            runCatching { list().performScrollToNode(hasText("Остановка временно недоступна")) }.isSuccess
        }
        compose.onNodeWithText("Остановка временно недоступна").assertIsDisplayed()
        assertTrue(published.isEmpty())
        list().performScrollToNode(hasText("Остановка по пути"))
        compose.onNodeWithText("Остановка по пути").assertTextContains(stop)
        reject = false
        publish()
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            published.isNotEmpty()
        }
        assertEquals(2, bodies.size)
        bodies.forEach { assertEquals(stop, JSONObject(it).getString("waypoints")) }
        assertEquals(listOf(stop), published.single().waypoints)
    }

    @Test fun privateOrUnknownStopNeverReachesRidePublicationAndInputStays() {
        mount()
        val invalid = "ул. Ленина 5, +7 900 111-22-33"
        edit("Остановка по пути", invalid)
        publish()
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            runCatching { list().performScrollToNode(hasText("Выбери село или город из подсказок. Точное место договоритесь в чате.")) }.isSuccess
        }
        assertTrue(bodies.isEmpty())
        assertFalse(queries.any { it.contains("Ленина") || it.contains("900") })
        list().performScrollToNode(hasText("Остановка по пути"))
        compose.onNodeWithText("Остановка по пути").assertTextContains(invalid)
    }

    private fun edit(label: String, value: String) {
        list().performScrollToNode(hasText(label))
        compose.onNodeWithText(label).performTextReplacement(value)
    }
    private fun publish() {
        list().performScrollToNode(hasTestTag("publish_btn"))
        compose.onNodeWithTag("publish_btn").performClick()
    }
    private fun list() = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
}
