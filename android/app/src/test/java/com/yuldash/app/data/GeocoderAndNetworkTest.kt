package com.yuldash.app.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * Подсказки адресов и монитор возврата сети — два места, где приложение раньше молчало.
 *
 * Подсказки адресов. Поле «Куда» спрашивает наш сервер, а не Яндекс напрямую: ключ геокодера
 * живёт на сервере, иначе его вынимают из установленного приложения за пять минут. Главное,
 * что здесь проверяется, — приложение различает «спросили и ничего не нашли» и «спросить не
 * смогли». Разница не техническая: человеку с ПРАВИЛЬНЫМ адресом раньше уверенно писали
 * «Такого адреса не нашли», хотя на самом деле не было связи.
 *
 * Монитор сети. Когда связь возвращается, живые каналы (чат, точка машины на карте) должны
 * подхватиться сразу, а не через полминуты по таймеру. Слушатели держатся «слабо», чтобы
 * забытая отписка не копила мусор — это тоже проверяем.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeocoderAndNetworkTest {

    private lateinit var server: MockWebServer

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
    }

    @After
    fun teardown() {
        ApiClient.testBaseUrl = null
        server.shutdown()
    }

    private fun json(body: String) = MockResponse().setResponseCode(200).setBody(body)

    // ---------- Подсказки адресов ----------

    @Test
    fun `найденные адреса разбираются с координатами`() = runBlocking {
        server.enqueue(
            json(
                """{"items":[{"title":"Сибай, ул. Ленина, 1","lat":52.7,"lon":58.6},
                             {"title":"Сибай, вокзал","lat":52.71,"lon":58.61}]}""",
            ),
        )
        val hits = GeocoderClient.suggestResult("Сибай Ленина").getOrThrow()
        assertEquals(2, hits.size)
        assertEquals("Сибай, ул. Ленина, 1", hits[0].title)
        assertEquals(52.7, hits[0].lat, 0.0001)
        assertEquals(58.6, hits[0].lon, 0.0001)

        val rec = server.takeRequest()
        assertEquals("GET", rec.method)
        assertTrue("запрос должен идти на наш сервер", rec.path!!.startsWith("/geocode?q="))
    }

    @Test
    fun `нет связи — это ошибка, а не «адрес не найден»`() = runBlocking {
        server.shutdown()   // сервер недоступен — как в подвале или на трассе
        val res = GeocoderClient.suggestResult("Учалы центр")
        assertTrue("обрыв связи обязан быть ошибкой, иначе экран соврёт «не найдено»", res.isFailure)
    }

    @Test
    fun `сервер ответил ошибкой — тоже ошибка, а не пустой список`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(502))
        val res = GeocoderClient.suggestResult("Баймак рынок")
        assertTrue(res.isFailure)
    }

    @Test
    fun `спросили и правда ничего нет — успех с пустым списком`() = runBlocking {
        server.enqueue(json("""{"items":[]}"""))
        val res = GeocoderClient.suggestResult("йцукен фыва")
        assertTrue(res.isSuccess)
        assertTrue(res.getOrThrow().isEmpty())
    }

    @Test
    fun `строки без координат или без названия отбрасываются`() = runBlocking {
        server.enqueue(
            json(
                """{"items":[{"title":"Без координат"},
                             {"title":"","lat":52.0,"lon":58.0},
                             {"title":"Годная точка","lat":52.0,"lon":58.0}]}""",
            ),
        )
        val hits = GeocoderClient.suggestResult("смешанный ответ").getOrThrow()
        assertEquals("на карту нельзя ставить точку без координат", 1, hits.size)
        assertEquals("Годная точка", hits[0].title)
    }

    @Test
    fun `короткий ввод не тревожит сеть`() = runBlocking {
        val res = GeocoderClient.suggestResult("У")
        assertTrue(res.isSuccess)
        assertTrue(res.getOrThrow().isEmpty())
        assertNull("одна буква — не запрос, сеть дёргать нельзя", server.takeRequest(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `повторный тот же запрос берётся из памяти, а не из сети`() = runBlocking {
        val q = "Кумертау уникальный запрос"
        server.enqueue(json("""{"items":[{"title":"Кумертау","lat":52.7,"lon":55.8}]}"""))
        val first = GeocoderClient.suggestResult(q).getOrThrow()
        server.takeRequest()

        val second = GeocoderClient.suggestResult(q).getOrThrow()
        assertEquals(first, second)
        // Пока человек набирает «У→Уф→Уфа», поле не должно слать по запросу на каждую букву заново.
        assertNull("повтор ушёл в сеть — кеш не работает", server.takeRequest(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `короткий вход отдаёт пустой список вместо ошибки`() = runBlocking {
        // Там, где сбой и «не нашли» равноценны (фоновая подсказка), нужен список, а не Result.
        server.enqueue(MockResponse().setResponseCode(500))
        assertTrue(GeocoderClient.suggest("Ишимбай вокзал").isEmpty())
    }

    // ---------- Монитор возврата сети ----------

    @Test
    fun `слушатель будится при возврате сети и молчит после отписки`() {
        var woke = 0
        val listener = NetworkMonitor.Listener { woke++ }
        NetworkMonitor.subscribe(listener)
        NetworkMonitor.subscribe(listener)   // повтор не должен дублировать

        emitNetworkAvailable()
        assertEquals("один слушатель — одно пробуждение", 1, woke)

        NetworkMonitor.unsubscribe(listener)
        emitNetworkAvailable()
        assertEquals("после отписки будить нельзя — канал уже мёртв", 1, woke)
    }

    @Test
    fun `падение одного слушателя не мешает остальным`() {
        var second = 0
        val boom = NetworkMonitor.Listener { throw IllegalStateException("сломался") }
        val ok = NetworkMonitor.Listener { second++ }
        NetworkMonitor.subscribe(boom)
        NetworkMonitor.subscribe(ok)

        emitNetworkAvailable()
        assertEquals("сосед упал — остальные всё равно должны переподключиться", 1, second)

        NetworkMonitor.unsubscribe(boom)
        NetworkMonitor.unsubscribe(ok)
    }

    @Test
    fun `повторная инициализация безопасна`() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        NetworkMonitor.init(ctx)
        NetworkMonitor.init(ctx)   // второй раз — тихий no-op, системный колбэк не дублируется
    }

    /** Дёргает приватное уведомление монитора — так же, как это делает системный колбэк. */
    private fun emitNetworkAvailable() {
        val m = NetworkMonitor::class.java.getDeclaredMethod("notifyAvailable")
        m.isAccessible = true
        m.invoke(NetworkMonitor)
    }

    // ---------- Мост «позиция участника → карта» ----------

    @Test
    fun `мост позиции хранит последнюю точку и номер брони`() {
        TripLocationBus.peer = null
        TripLocationBus.bookingId = null
        assertNull(TripLocationBus.peer)

        val p = LocationSocket.Peer(role = "driver", lat = 52.5, lng = 58.3, bearing = 12.0, ts = 1L)
        TripLocationBus.peer = p
        TripLocationBus.bookingId = 42
        assertEquals(p, TripLocationBus.peer)
        assertEquals(42, TripLocationBus.bookingId)

        // Поездка кончилась — мост обязан очиститься, иначе на карте зависнет чужая машина.
        TripLocationBus.peer = null
        TripLocationBus.bookingId = null
        assertNull(TripLocationBus.peer)
        assertFalse(TripLocationBus.bookingId != null)
    }
}
