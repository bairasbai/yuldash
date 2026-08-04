package com.yuldash.app

import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.TripPass
import com.yuldash.app.data.TripPassStore
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Офлайн-паспорт поездки. Замер покрытия 2026-08-04 показал у этого класса **0%** — при том,
 * что внутри лежит телефон водителя, то есть персональные данные.
 *
 * Почему это важнее обычного «поднять покрытие». Паспорт — единственное, что человек видит
 * на трассе без связи: маршрут, машина, телефон водителя, код посадки. Если сохранение или
 * чтение сломается, пассажир на перевале Белорецк↔Уфа останется с пустым экраном и без
 * возможности позвонить. Проверять это на живой трассе поздно.
 *
 * Тесты держат три обещания: что сохранённое читается обратно без потерь, что удаление
 * действительно удаляет (телефон не остаётся на устройстве после поездки) и что кривой
 * или урезанный JSON не роняет приложение.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TripPassTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun pass(id: Int = 7) = TripPass(
        bookingId = id,
        fromCity = "Баймак",
        toCity = "Сибай",
        departAt = "2026-08-05T08:00:00",
        driverName = "Айдар",
        driverCar = "Lada Vesta, белая",
        driverPhone = "+79990000001",
        boardingCode = "4821",
        pickup = "у автовокзала",
        pickupLat = 52.5911,
        pickupLng = 58.3178,
        price = 350,
        seats = 2,
        paymentNote = "наличными",
        savedAt = 1_754_300_000_000L,
    )

    @After
    fun tearDown() {
        TripPassStore.clearAll()
    }

    @Test
    fun `паспорт переживает запись и чтение без потерь`() {
        TripPassStore.init(ctx)
        TripPassStore.save(ctx, pass())
        val got = TripPassStore.load(ctx, 7)
        assertNotNull("паспорт не прочитался — на трассе экран будет пуст", got)
        assertEquals("Баймак", got!!.fromCity)
        assertEquals("Сибай", got.toCity)
        assertEquals("Айдар", got.driverName)
        // Телефон — то, ради чего паспорт и нужен: позвонить водителю без сети.
        assertEquals("+79990000001", got.driverPhone)
        assertEquals("4821", got.boardingCode)
        assertEquals(52.5911, got.pickupLat!!, 0.00001)
        assertEquals(350, got.price)
        assertEquals(2, got.seats)
    }

    @Test
    fun `удаление стирает паспорт — телефон не остаётся на устройстве`() {
        TripPassStore.init(ctx)
        TripPassStore.save(ctx, pass(11))
        assertNotNull(TripPassStore.load(ctx, 11))
        TripPassStore.remove(ctx, 11)
        assertNull("после поездки телефон водителя не должен лежать на телефоне", TripPassStore.load(ctx, 11))
    }

    @Test
    fun `код посадки обновляется, остальное не теряется`() {
        TripPassStore.init(ctx)
        TripPassStore.save(ctx, pass(12))
        TripPassStore.updateBoardingCode(ctx, 12, "9999")
        val got = TripPassStore.load(ctx, 12)!!
        assertEquals("9999", got.boardingCode)
        assertEquals("код обновили — остальное должно уцелеть", "Айдар", got.driverName)
        assertEquals("+79990000001", got.driverPhone)
    }

    @Test
    fun `чужой номер брони не отдаёт паспорт`() {
        TripPassStore.init(ctx)
        TripPassStore.save(ctx, pass(13))
        assertNull(TripPassStore.load(ctx, 14))
    }

    @Test
    fun `урезанный JSON не роняет — недостающее берётся по умолчанию`() {
        // Старая версия приложения могла сохранить меньше полей. Читать это должно молча.
        val p = TripPass.fromJson(JSONObject("""{"booking_id":5,"from_city":"Уфа"}"""))
        assertEquals(5, p.bookingId)
        assertEquals("Уфа", p.fromCity)
        assertEquals("", p.driverPhone)
        assertEquals("мест по умолчанию одно, а не ноль", 1, p.seats)
        assertNull(p.pickupLat)
    }

    @Test
    fun `координаты точки сбора могут отсутствовать и это не ломает круг`() {
        val noGeo = pass(15).copy(pickupLat = null, pickupLng = null)
        val back = TripPass.fromJson(noGeo.toJson())
        assertNull(back.pickupLat)
        assertNull(back.pickupLng)
        assertEquals("у автовокзала", back.pickup)
    }
}
