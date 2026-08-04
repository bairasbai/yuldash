package com.yuldash.app.data

import kotlinx.coroutines.runBlocking
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Живые каналы: чат, гео-позиция участника поездки и сигнал «обнови карту».
 *
 * Почему это важнее обычного «поднять покрытие». Замер 2026-08-04 показал у всех четырёх
 * сокет-классов **ровно 0%** — ни одной проверки. А через них идёт то, что человек видит
 * в реальном времени: сообщение водителю «я у подъезда», точка машины на карте, обновление
 * ленты. Молчаливая поломка здесь выглядит как «приложение зависло»: экран открыт, всё
 * нарисовано, просто ничего не приходит.
 *
 * Отдельно проверяем безопасность: токен уходит ПЕРВЫМ сообщением внутри соединения,
 * а не в адресе. Адрес WebSocket попадает в логи прокси целиком — токен в нём означал бы
 * чужой вход в аккаунт по логам сервера.
 *
 * И отдельно — что канал не долбится вечно: после закрытия экрана переподключений быть
 * не должно, иначе телефон греется в кармане.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebSocketClientsTest {

    private lateinit var server: MockWebServer

    /** Всё, что клиент прислал на сервер, в порядке прихода. */
    private val fromClient = LinkedBlockingQueue<String>()

    /** Серверная сторона соединения — через неё пушим клиенту. */
    @Volatile private var serverSide: WebSocket? = null

    /** Все открытые серверные стороны: незакрытая держит MockWebServer и роняет teardown. */
    private val allServerSides = java.util.Collections.synchronizedList(mutableListOf<WebSocket>())

    @Before
    fun setup() = runBlocking {
        server = MockWebServer()
        server.start()
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        // Сокеты подключаются только залогиненными: логинимся обычным путём, чтобы токен лёг
        // туда же, куда кладёт его настоящий вход (в тестах prefs нет — токен живёт в поле).
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"access_token":"jwt_socket","refresh_token":"ref","name":"Айгуль"}"""),
        )
        ApiClient.verifyCode(phone = "+79990000001", code = "1234", name = "Айгуль")
        server.takeRequest()
        Unit
    }

    @After
    fun teardown() {
        synchronized(allServerSides) { allServerSides.forEach { it.close(1000, null) } }
        ApiClient.logout()
        ApiClient.testBaseUrl = null
        server.shutdown()
    }

    /** Ставит в очередь одно WebSocket-соединение и запоминает всё, что по нему пришло. */
    private fun enqueueSocket(): CountDownLatch {
        val opened = CountDownLatch(1)
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                    serverSide = webSocket
                    allServerSides.add(webSocket)
                    opened.countDown()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    fromClient.offer(text)
                }
            }),
        )
        return opened
    }

    private fun awaitOpen(latch: CountDownLatch) =
        assertTrue("соединение не установилось", latch.await(5, TimeUnit.SECONDS))

    /** Адрес, по которому клиент постучался (это обычный HTTP-запрос апгрейда). */
    private fun connectedPath(): String? = server.takeRequest(5, TimeUnit.SECONDS)?.path

    private fun nextFromClient(): String? = fromClient.poll(5, TimeUnit.SECONDS)

    // ---------- Чат ----------

    @Test
    fun `чат здоровается токеном внутри соединения, а не в адресе`() {
        val opened = enqueueSocket()
        val chat = ChatSocket(bookingId = 42, onMessage = {})
        chat.connect()
        awaitOpen(opened)

        val path = connectedPath()
        assertEquals("/ws/bookings/42", path)
        // Адрес WebSocket целиком попадает в логи прокси — токена в нём быть не должно.
        assertFalse("токен утёк в адрес соединения", path!!.contains("jwt_socket"))

        val first = JSONObject(nextFromClient()!!)
        assertEquals("auth", first.optString("type"))
        assertEquals("jwt_socket", first.optString("token"))
        chat.close()
    }

    @Test
    fun `входящее сообщение разбирается целиком, включая метку админа и предупреждение`() {
        val opened = enqueueSocket()
        val got = LinkedBlockingQueue<ChatSocket.Incoming>()
        val chat = ChatSocket(bookingId = 7, onMessage = { got.offer(it) })
        chat.connect()
        awaitOpen(opened)
        nextFromClient()   // auth

        serverSide!!.send(
            """{"type":"message","id":5,"sender_id":9,"text":"Я у подъезда",
               "timestamp":"2026-08-05T08:00:00","flag":"warn","from_admin":true}""",
        )
        val msg = got.poll(5, TimeUnit.SECONDS)
        assertTrue("сообщение не дошло до экрана", msg != null)
        assertEquals(5, msg!!.id)
        assertEquals(9, msg.senderId)
        assertEquals("Я у подъезда", msg.text)
        assertEquals("2026-08-05T08:00:00", msg.timestamp)
        assertEquals("warn", msg.flag)          // анти-фишинг: экран покажет плашку
        assertTrue(msg.fromAdmin)               // бейдж «Юлдаш ✓»
        chat.close()
    }

    @Test
    fun `служебные пакеты и битый JSON не доходят до экрана и не роняют чат`() {
        val opened = enqueueSocket()
        val got = LinkedBlockingQueue<ChatSocket.Incoming>()
        val chat = ChatSocket(bookingId = 7, onMessage = { got.offer(it) })
        chat.connect()
        awaitOpen(opened)
        nextFromClient()

        serverSide!!.send("""{"type":"typing","sender_id":9}""")   // не сообщение
        serverSide!!.send("""{это вообще не json""")               // мусор из сети
        serverSide!!.send("""{"type":"message","id":1,"text":"живое"}""")

        val msg = got.poll(5, TimeUnit.SECONDS)
        assertEquals("до экрана должно дойти только настоящее сообщение", "живое", msg?.text)
        assertNull("лишних сообщений быть не должно", got.poll(300, TimeUnit.MILLISECONDS))
        chat.close()
    }

    @Test
    fun `отправка уходит на сервер, а до соединения честно возвращает false`() {
        val chat = ChatSocket(bookingId = 7, onMessage = {})
        assertFalse("без соединения отправка не могла удаться", chat.send("привет"))

        val opened = enqueueSocket()
        chat.connect()
        awaitOpen(opened)
        nextFromClient()   // auth

        assertTrue(chat.send("еду"))
        val sent = JSONObject(nextFromClient()!!)
        assertEquals("message", sent.optString("type"))
        assertEquals("еду", sent.optString("text"))
        chat.close()
    }

    @Test
    fun `чат заказа такси и чат посылки идут по своим адресам`() {
        val opened = enqueueSocket()
        val order = ChatSocket.forOrder(orderId = 31, onMessage = {})
        order.connect()
        awaitOpen(opened)
        assertEquals("/ws/instant/31/chat", connectedPath())
        order.close()

        val opened2 = enqueueSocket()
        val parcel = ChatSocket.forParcel(parcelId = 88, onMessage = {})
        parcel.connect()
        awaitOpen(opened2)
        assertEquals("/ws/parcel/88/chat", connectedPath())
        parcel.close()
    }

    @Test
    fun `экран узнаёт о подключении и об обрыве`() {
        val opened = enqueueSocket()
        val states = LinkedBlockingQueue<Boolean>()
        val chat = ChatSocket(bookingId = 7, onMessage = {}, onConnected = { states.offer(it) })
        chat.connect()
        awaitOpen(opened)
        assertEquals(true, states.poll(5, TimeUnit.SECONDS))

        serverSide!!.close(1000, "пока")
        assertEquals(false, states.poll(5, TimeUnit.SECONDS))
        chat.close()
    }

    // ---------- Гео-позиция участника поездки ----------

    @Test
    fun `гео-канал здоровается токеном и слушает свой адрес`() {
        val opened = enqueueSocket()
        val loc = LocationSocket(bookingId = 12, onPeer = {})
        loc.connect()
        awaitOpen(opened)

        assertEquals("/ws/trip/12/location", connectedPath())
        val first = JSONObject(nextFromClient()!!)
        assertEquals("auth", first.optString("type"))
        assertEquals("jwt_socket", first.optString("token"))
        loc.close()
    }

    @Test
    fun `позиция другого участника разбирается, отсутствующий курс остаётся пустым`() {
        val opened = enqueueSocket()
        val peers = LinkedBlockingQueue<LocationSocket.Peer>()
        val loc = LocationSocket(bookingId = 12, onPeer = { peers.offer(it) })
        loc.connect()
        awaitOpen(opened)
        nextFromClient()

        serverSide!!.send("""{"type":"loc","role":"driver","lat":52.5911,"lng":58.3178,"bearing":90.0,"ts":1754300000}""")
        val a = peers.poll(5, TimeUnit.SECONDS)!!
        assertEquals("driver", a.role)
        assertEquals(52.5911, a.lat, 0.00001)
        assertEquals(58.3178, a.lng, 0.00001)
        assertEquals(90.0, a.bearing!!, 0.001)
        assertEquals(1754300000L, a.ts)

        // Телефон без компаса курс не отдаёт — приходит null, и это нормально.
        serverSide!!.send("""{"type":"loc","role":"rider","lat":52.6,"lng":58.3,"bearing":null,"ts":1754300100}""")
        val b = peers.poll(5, TimeUnit.SECONDS)!!
        assertNull("курса нет — стрелку рисовать нечем, но точка должна показаться", b.bearing)
        loc.close()
    }

    @Test
    fun `своя позиция уходит с меткой времени, курс добавляется только когда он есть`() {
        val opened = enqueueSocket()
        val loc = LocationSocket(bookingId = 12, onPeer = {})
        loc.connect()
        awaitOpen(opened)
        nextFromClient()   // auth

        assertTrue(loc.sendLoc(lat = 52.5, lng = 58.3, bearing = 45.0))
        val withBearing = JSONObject(nextFromClient()!!)
        assertEquals("loc", withBearing.optString("type"))
        assertEquals(52.5, withBearing.optDouble("lat"), 0.0001)
        assertEquals(45.0, withBearing.optDouble("bearing"), 0.0001)
        assertTrue("метка времени обязательна — по ней экран поймёт, что точка свежая", withBearing.optLong("ts") > 0)

        assertTrue(loc.sendLoc(lat = 52.6, lng = 58.4))
        val noBearing = JSONObject(nextFromClient()!!)
        assertFalse("курса нет — поля быть не должно", noBearing.has("bearing"))
        loc.close()
    }

    @Test
    fun `без соединения отправка позиции возвращает false, а не делает вид, что ушла`() {
        val loc = LocationSocket(bookingId = 12, onPeer = {})
        assertFalse(loc.sendLoc(52.0, 58.0))
    }

    // ---------- Сигнал «обнови карту» ----------

    @Test
    fun `карта обновляется по сигналу и молчит на всём остальном`() {
        val opened = enqueueSocket()
        val refreshes = LinkedBlockingQueue<Long>()
        val feed = MapFeedSocket(onRefresh = { refreshes.offer(1L) })
        feed.connect()
        awaitOpen(opened)

        assertEquals("/ws/map", connectedPath())
        val first = JSONObject(nextFromClient()!!)
        assertEquals("auth", first.optString("type"))

        serverSide!!.send("""{"type":"ping"}""")
        serverSide!!.send("""не json""")
        assertNull("на служебные пакеты карта дёргаться не должна", refreshes.poll(300, TimeUnit.MILLISECONDS))

        serverSide!!.send("""{"type":"refresh"}""")
        assertTrue("сигнал не дошёл — карта осталась бы на 25-секундном опросе", refreshes.poll(5, TimeUnit.SECONDS) != null)
        feed.close()
    }

    @Test
    fun `без входа в аккаунт карта не подключается вовсе`() {
        ApiClient.logout()
        server.takeRequest(2, TimeUnit.SECONDS)   // сам выход шлёт POST /auth/logout — он не в счёт

        val feed = MapFeedSocket(onRefresh = {})
        feed.connect()
        // Ни одного соединения: аноним остаётся на обычном опросе, канал не поднимается.
        assertNull(server.takeRequest(500, TimeUnit.MILLISECONDS))
        feed.close()
    }

    // ---------- Живая позиция такси и посылки ----------

    @Test
    fun `канал такси и канал посылки идут по своим адресам`() {
        val opened = enqueueSocket()
        val order = InstantLocationSocket(orderId = 55, onPeer = {})
        order.connect()
        awaitOpen(opened)
        assertEquals("/ws/instant/55/location", connectedPath())
        order.close()

        val opened2 = enqueueSocket()
        val parcel = InstantLocationSocket.forParcel(parcelId = 66, onPeer = {})
        parcel.connect()
        awaitOpen(opened2)
        assertEquals("/ws/parcel/66/location", connectedPath())
        parcel.close()
    }

    @Test
    fun `позиция машины такси доходит до экрана заказа`() {
        val opened = enqueueSocket()
        val peers = LinkedBlockingQueue<LocationSocket.Peer>()
        val order = InstantLocationSocket(orderId = 55, onPeer = { peers.offer(it) })
        order.connect()
        awaitOpen(opened)
        connectedPath()

        val auth = JSONObject(nextFromClient()!!)
        assertEquals("auth", auth.optString("type"))
        assertEquals("jwt_socket", auth.optString("token"))

        serverSide!!.send("""{"type":"loc","role":"driver","lat":54.7,"lng":55.9,"bearing":180.0,"ts":1754300500}""")
        val p = peers.poll(5, TimeUnit.SECONDS)!!
        assertEquals("driver", p.role)
        assertEquals(54.7, p.lat, 0.0001)
        assertEquals(180.0, p.bearing!!, 0.001)

        serverSide!!.send("""{"type":"status","state":"arrived"}""")   // не позиция
        serverSide!!.send("""мусор""")
        assertNull("до карты должны доходить только координаты", peers.poll(300, TimeUnit.MILLISECONDS))
        order.close()
    }

    @Test
    fun `своя позиция по такси-заказу уходит с меткой времени`() {
        val opened = enqueueSocket()
        val order = InstantLocationSocket(orderId = 55, onPeer = {})
        order.connect()
        awaitOpen(opened)
        connectedPath()
        nextFromClient()   // auth

        assertTrue(order.sendLoc(lat = 54.7, lng = 55.9, bearing = 10.0))
        val sent = JSONObject(nextFromClient()!!)
        assertEquals("loc", sent.optString("type"))
        assertEquals(10.0, sent.optDouble("bearing"), 0.001)
        assertTrue(sent.optLong("ts") > 0)

        assertTrue(order.sendLoc(lat = 54.8, lng = 56.0))
        assertFalse("курса нет — поля быть не должно", JSONObject(nextFromClient()!!).has("bearing"))
        order.close()
    }

    @Test
    fun `без соединения позиция по заказу не делает вид, что ушла`() {
        assertFalse(InstantLocationSocket(orderId = 55, onPeer = {}).sendLoc(54.0, 55.0))
    }

    @Test
    fun `без входа в аккаунт живая позиция не подключается`() {
        ApiClient.logout()
        server.takeRequest(2, TimeUnit.SECONDS)   // сам выход шлёт POST /auth/logout

        val order = InstantLocationSocket(orderId = 55, onPeer = {})
        order.connect()
        assertNull(server.takeRequest(500, TimeUnit.MILLISECONDS))
        order.close()
    }

    // ---------- Не долбиться вечно ----------

    @Test
    fun `после закрытия канала клиент больше не стучится на сервер`() {
        val opened = enqueueSocket()
        val chat = ChatSocket(bookingId = 7, onMessage = {})
        chat.connect()
        awaitOpen(opened)
        connectedPath()    // забираем сам запрос на подключение, дальше очередь должна быть пуста
        nextFromClient()

        chat.close()
        serverSide!!.close(1000, "сервер тоже закрыл")
        // Экран закрыт — реконнекта быть не должно, иначе телефон греется в кармане.
        assertNull(
            "закрытый чат продолжает переподключаться",
            server.takeRequest(2, TimeUnit.SECONDS),
        )
    }
}
