package com.yuldash.app

import com.yuldash.app.data.MeetingPointInput
import com.yuldash.app.data.MeetingPointInputSaver
import com.yuldash.app.data.decodeMeetingPoint
import com.yuldash.app.data.encodeMeetingPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RS-03 А: точка встречи/высадки в приложении — тело брони и состояние формы.
 *
 * Сервер (`backend/tests/test_meeting_points.py`) и сайт (`webapp/tests/meeting-point-test.mjs`)
 * проверяют тот же контракт: пусто — ключа нет; ориентир/булавка — meeting/alighting уходят;
 * clear сбрасывает. Здесь — Android-сторона: toJson не несёт пустоту и обрезает пояснение до 120,
 * сейвер состояния восстанавливает точку после смерти процесса.
 */
class MeetingPointTest {

    @Test
    fun `пустая точка — пустое тело и isEmpty`() {
        assertTrue(MeetingPointInput().isEmpty)
        assertTrue(MeetingPointInput(note = "").isEmpty)
        assertFalse(MeetingPointInput(note = "у школы").isEmpty)
        assertFalse(MeetingPointInput(clear = true).isEmpty)
        assertEquals(0, MeetingPointInput().toJson().length())
    }

    @Test
    fun `тело несёт только заданное, пояснение — до 120 знаков`() {
        val j = MeetingPointInput(landmarkId = 7, note = "у школы").toJson()
        assertEquals(7, j.getInt("landmark_id"))
        assertEquals("у школы", j.getString("note"))
        assertFalse(j.has("lat") || j.has("clear"))
        val longNote = "д".repeat(200)
        assertEquals(120, MeetingPointInput(note = longNote).toJson().getString("note").length)
        val pin = MeetingPointInput(lat = 52.7, lng = 58.6).toJson()
        assertEquals(52.7, pin.getDouble("lat"), 1e-9)
        assertFalse(pin.has("note") && pin.getString("note").isNotEmpty())
    }

    @Test
    fun `clear доезжает как clear=true`() {
        val j = MeetingPointInput(clear = true).toJson()
        assertTrue(j.getBoolean("clear"))
    }

    @Test
    fun `состояние формы переживает смерть процесса — сейвер туда-обратно`() {
        val point = MeetingPointInput(lat = 52.7, lng = 58.6, note = "синие ворота | школа",
                                      landmarkId = 7, landmarkTitle = "у мечети")
        assertEquals(point, decodeMeetingPoint(encodeMeetingPoint(point)))
        // Пустота тоже восстанавливается честно — как null.
        assertNull(decodeMeetingPoint(encodeMeetingPoint(null)))
    }

    @Test
    fun `офлайн-паспорт брони пишется только пассажиру`() {
        // RS-03 А (план, Android п.1): паспорт с кодом посадки — документ пассажира; до фикса
        // он писался и на устройстве ВОДИТЕЛЯ при открытии деталей подтверждённой брони.
        val src = java.io.File("src/main/java/com/yuldash/app/BookingActiveTripScreen.kt").readText()
        assertTrue(
            "saveTripPass зовётся только при role == \"passenger\" — иначе код посадки у водителя",
            src.contains("loaded.contactUnlocked && loaded.role == \"passenger\""),
        )
    }
}
