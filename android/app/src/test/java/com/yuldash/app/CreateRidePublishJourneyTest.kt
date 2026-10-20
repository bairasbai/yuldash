package com.yuldash.app

import android.app.Application
import android.content.Context
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
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/** Actual form, Compose departure dialog and ApiClient; local HTTP replaces the backend only. */
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
    private lateinit var originalLocale: Locale
    private lateinit var originalFormatLocale: Locale
    private lateinit var originalDisplayLocale: Locale

    @Before fun setup() {
        originalLocale = Locale.getDefault()
        originalFormatLocale = Locale.getDefault(Locale.Category.FORMAT)
        originalDisplayLocale = Locale.getDefault(Locale.Category.DISPLAY)
        Locale.setDefault(Locale.US)
        Locale.setDefault(Locale.Category.FORMAT, Locale.FRANCE)
        Locale.setDefault(Locale.Category.DISPLAY, Locale.JAPAN)
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
        Locale.setDefault(originalLocale)
        Locale.setDefault(Locale.Category.FORMAT, originalFormatLocale)
        Locale.setDefault(Locale.Category.DISPLAY, originalDisplayLocale)
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

    @Test fun bashkirPublicationUsesTranslatedDeparturePicker() {
        fillForm(language = AppLanguage.Ba)
        publishButton().assertIsEnabled().performClick()
        awaitPublished()
        assertEquals(1, bodies.size)
        verifyPayload(bodies.single())
        assertEquals("42", published.single().id)
    }

    @Test fun cancelledDepartureDraftKeepsPreviousDateAndReopensWithSavedValues() {
        fillForm()
        val description = "Дата и время: 02.01.2030, 09:45"
        fun reopen() {
            list().performScrollToNode(hasContentDescription(description))
            compose.onNodeWithContentDescription(description).performClick()
        }
        reopen()
        chooseDeparture(RIDE_DEPARTURE_DAY, 4, 3)
        compose.onNodeWithTag(RIDE_DEPARTURE_MINUTE).performScrollTo().performTextReplacement("17")
        compose.onNodeWithTag(RIDE_DEPARTURE_CANCEL).performClick()
        compose.onNodeWithContentDescription(description).assertExists()
        reopen()
        compose.onNodeWithTag(RIDE_DEPARTURE_DAY).assertContentDescriptionEquals("День: 2")
        compose.onNodeWithTag(RIDE_DEPARTURE_YEAR).assertContentDescriptionEquals("Год: 2030")
        compose.onNodeWithTag(RIDE_DEPARTURE_HOUR).assert(hasText("09"))
        compose.onNodeWithTag(RIDE_DEPARTURE_MINUTE).assert(hasText("45"))
        compose.onNodeWithTag(RIDE_DEPARTURE_CANCEL).performClick()
        assertTrue(bodies.isEmpty())
        assertTrue(published.isEmpty())
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
        // Кнопка — в карточке «маршрутов нет», а та появляется только после ОТВЕТА на запрос;
        // пока он не разобран, на её месте заглушка загрузки. В CI тест искал кнопку раньше.
        awaitInList("Опубликовать маршрут")
        compose.onNodeWithText("Опубликовать маршрут").performClick()
        compose.runOnIdle { assertEquals(Screen.CreateRide, vm.screen.value) }
        fillForm(mount = false)
        publishButton().performClick()
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            created != null && vm.screen.value == Screen.DriverCabinet &&
                requests.count { it == "GET /driver/rides" } >= 2
        }
        // Повторный запрос ушёл — но карточка «Опубликована» рисуется по его ответу. Ждём её саму.
        awaitInList("Опубликована")
        compose.onNodeWithText("Опубликована").assertIsDisplayed()
        // Маршрут и статус — в одной карточке, но прокрутка к статусу не обязана показать и
        // маршрут: высота строк зависит от шрифтов, и на Linux (CI) «Уфа → Сибай» оставалась за
        // краем экрана — узел есть, но не виден. Прокручиваем к самому маршруту.
        list().performScrollToNode(hasText("Уфа → Сибай"))
        compose.onNodeWithText("Уфа → Сибай").assertIsDisplayed()
        assertEquals(1, bodies.size)
        verifyPayload(bodies.single())
        compose.runOnIdle { assertEquals("42", vm.rides.single { it.id == "42" }.id) }
    }

    private fun fillForm(mount: Boolean = true, language: AppLanguage = AppLanguage.Ru) {
        if (mount) compose.setContent {
            if (mounted.value) CompositionLocalProvider(LocalAppLanguage provides language) {
                CreateRideScreen(onBack = {}, onPublish = { published.add(it) })
            }
        }
        val isBa = language == AppLanguage.Ba
        edit(if (isBa) "Ҡайҙан" else "Откуда", "Уфа")
        edit(if (isBa) "Ҡайҙа" else "Куда", "Сибай")
        val dateLabel = if (isBa) "Дата һәм ваҡыт" else "Дата и время"
        val datePlaceholder = if (isBa) "Дата һәм ваҡыт һайла" else "Выбери дату и время"
        val initialDateDescription = "$dateLabel: $datePlaceholder"
        list().performScrollToNode(hasContentDescription(initialDateDescription))
        compose.onNodeWithContentDescription(initialDateDescription)
            .assertIsDisplayed().assertHasClickAction().performClick()
        compose.onNodeWithTag(RIDE_DEPARTURE_DIALOG).assertExists()
        compose.onNodeWithTag(RIDE_DEPARTURE_CONFIRM).assert(hasText(if (isBa) "Һаҡларға" else "Сохранить"))
        compose.onNodeWithTag(RIDE_DEPARTURE_CANCEL).assert(hasText(if (isBa) "Баш тартыу" else "Отмена"))
        chooseDeparture(RIDE_DEPARTURE_YEAR, 2030, 2030 - LocalDate.now().year)
        chooseDeparture(RIDE_DEPARTURE_MONTH, 1, 0)
        compose.onNodeWithTag(RIDE_DEPARTURE_MONTH)
            .assertContentDescriptionEquals(if (isBa) "Ай: Ғинуар" else "Месяц: Январь")
        chooseDeparture(RIDE_DEPARTURE_DAY, 2, 1)
        compose.onNodeWithTag(RIDE_DEPARTURE_HOUR).performScrollTo().performTextReplacement("09")
        compose.onNodeWithTag(RIDE_DEPARTURE_MINUTE).performScrollTo().performTextReplacement("45")
        compose.onNodeWithTag(RIDE_DEPARTURE_CONFIRM).assertIsEnabled().performClick()
        assertEquals(Locale.US, Locale.getDefault())
        assertEquals(Locale.FRANCE, Locale.getDefault(Locale.Category.FORMAT))
        assertEquals(Locale.JAPAN, Locale.getDefault(Locale.Category.DISPLAY))
        compose.onNodeWithContentDescription("$dateLabel: 02.01.2030, 09:45")
            .assertExists().assertHasClickAction()
        edit(if (isBa) "Урын" else "Мест", "3")
        edit(if (isBa) "Хаҡ, ₽" else "Цена, ₽", "750")
    }

    private fun chooseDeparture(tag: String, value: Int, index: Int) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.onNodeWithTag("$tag-options").performScrollToIndex(index)
        compose.onNodeWithTag("$tag-option-$value").performClick()
    }

    private fun list() = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
    // Ждём, пока строка появится в списке: удачная прокрутка к ней и есть признак, что экран
    // дорисован по ответу сервера, а не только что запрос ушёл.
    private fun awaitInList(text: String) = compose.waitUntil(15000) {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        runCatching { list().performScrollToNode(hasText(text)) }.isSuccess
    }
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
        assertEquals(9, departure.hour)
        assertEquals(45, departure.minute)
    }
    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code)
        .setHeader("Content-Type", "application/json").setBody(body)
}
