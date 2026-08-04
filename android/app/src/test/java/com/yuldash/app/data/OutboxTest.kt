package com.yuldash.app.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Очередь на время без сети: что человек нажал в туннеле — уйдёт, когда связь вернётся.
 *
 * Зачем очередь вообще. Между Белорецком и Уфой связь пропадает надолго. Пассажир пишет
 * «я на остановке у магазина», нажимает «сел в машину» — и если бы приложение просто
 * показало ошибку, это сообщение он бы уже не написал заново. Поэтому нажатие кладётся
 * в очередь на диск, переживает перезапуск телефона и уходит само при первой же сети.
 *
 * Три правила, каждое из которых закрыто тестом:
 *  1. **Порядок.** «Сел в машину» не должно уйти раньше «еду к остановке».
 *  2. **Очередь не отравляется.** Если сервер ОТВЕТИЛ отказом (бронь уже закрыта), повтор
 *     не поможет — действие снимается. Иначе одно мёртвое сообщение навсегда заблокировало бы
 *     всё, что за ним.
 *  3. **Выход из аккаунта чистит очередь.** Иначе неотправленное прошлого человека ушло бы
 *     от имени следующего, кто вошёл на этом телефоне.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutboxTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var server: MockWebServer

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        Outbox.init(ctx)
        Outbox.clearAll()
    }

    @After
    fun teardown() {
        Outbox.clearAll()
        ApiClient.testBaseUrl = null
        server.shutdown()
    }

    private fun ok() = MockResponse().setResponseCode(200).setBody("{}")

    @Test
    fun `нажатие в туннеле не теряется — оно в очереди`() {
        assertFalse(Outbox.hasPending(ctx))
        Outbox.enqueue(ctx, Outbox.newMessage(bookingId = 7, text = "я у магазина"))
        assertTrue(Outbox.hasPending(ctx))
        assertEquals(1, Outbox.count(ctx, 7))
        assertEquals("очередь считается по своей брони, чужая не в счёт", 0, Outbox.count(ctx, 8))
    }

    @Test
    fun `очередь переживает перезапуск приложения`() {
        Outbox.enqueue(ctx, Outbox.newMessage(7, "переживёт перезапуск"))
        // Имитируем холодный старт: объект помнит только то, что лежит на диске.
        Outbox.init(ctx)
        assertEquals(1, Outbox.count(ctx, 7))
    }

    @Test
    fun `счётчик для экрана меняется при каждом изменении очереди`() {
        val before = Outbox.version.value
        Outbox.enqueue(ctx, Outbox.newMessage(7, "раз"))
        assertTrue("экран не узнает про новое в очереди", Outbox.version.value > before)
    }

    @Test
    fun `связь вернулась — очередь уходит по порядку и пустеет`() = runBlocking {
        Outbox.enqueue(ctx, Outbox.newMessage(7, "еду к остановке"))
        Outbox.enqueue(ctx, Outbox.newTripStatus(7, "boarded"))
        Outbox.enqueue(ctx, Outbox.newDriverStatus(7, "arrived"))
        repeat(3) { server.enqueue(ok()) }

        assertTrue(Outbox.flush(ctx))
        assertFalse("после успешной отправки очередь обязана опустеть", Outbox.hasPending(ctx))

        // Порядок важен: «сел в машину» не может уйти раньше «еду к остановке».
        val first = server.takeRequest()
        assertTrue("первым уходит то, что нажали первым", first.path!!.contains("/messages"))
    }

    @Test
    fun `связи всё ещё нет — очередь остаётся на потом`() = runBlocking {
        Outbox.enqueue(ctx, Outbox.newMessage(7, "не уйдёт"))
        server.shutdown()   // сети как не было, так и нет

        Outbox.flush(ctx)
        assertTrue("при обрыве связи сообщение обязано остаться в очереди", Outbox.hasPending(ctx))
    }

    @Test
    fun `отказ сервера снимает действие, чтобы очередь не встала намертво`() = runBlocking {
        Outbox.enqueue(ctx, Outbox.newMessage(7, "бронь уже закрыта"))
        Outbox.enqueue(ctx, Outbox.newMessage(7, "а это должно уйти"))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"detail":"бронь закрыта"}"""))
        server.enqueue(ok())

        assertTrue(Outbox.flush(ctx))
        assertFalse(
            "одно мёртвое сообщение не имеет права держать всю очередь",
            Outbox.hasPending(ctx),
        )
    }

    @Test
    fun `неизвестный вид действия не блокирует очередь`() = runBlocking {
        // Могло прилететь из старой версии приложения после обновления.
        Outbox.enqueue(ctx, OutboxAction(id = 1, bookingId = 7, kind = "что-то новое", payload = "", createdAt = 0))
        assertTrue(Outbox.flush(ctx))
        assertFalse(Outbox.hasPending(ctx))
    }

    @Test
    fun `выход из аккаунта выбрасывает чужое неотправленное`() {
        Outbox.enqueue(ctx, Outbox.newMessage(7, "личное сообщение прошлого человека"))
        Outbox.clearAll()
        assertFalse(
            "иначе это уйдёт от имени следующего, кто войдёт на этом телефоне",
            Outbox.hasPending(ctx),
        )
    }

    @Test
    fun `битая запись на диске не роняет приложение`() {
        ctx.getSharedPreferences("yuldash_outbox", android.content.Context.MODE_PRIVATE)
            .edit().putString("queue", "это не json").apply()
        assertEquals("испорченный файл читается как пустая очередь", 0, Outbox.count(ctx, 7))
        assertFalse(Outbox.hasPending(ctx))
    }

    @Test
    fun `пустая очередь не дёргает сеть впустую`() = runBlocking {
        assertFalse(Outbox.flush(ctx))
        assertEquals("на пустой очереди запросов быть не должно", 0, server.requestCount)
    }

    @Test
    fun `у каждого действия свой номер`() {
        val a = Outbox.newMessage(7, "раз")
        val b = Outbox.newMessage(7, "два")
        assertTrue("одинаковые номера перепутали бы порядок отправки", a.id != b.id)
        assertEquals("message", a.kind)
        assertEquals("trip_status", Outbox.newTripStatus(7, "boarded").kind)
        assertEquals("driver_status", Outbox.newDriverStatus(7, "arrived").kind)
    }
}
