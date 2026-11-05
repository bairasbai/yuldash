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
    @Test
    fun `посадка и высадка редактируются до посадки а в машине только высадка`() {
        assertEquals(listOf("pickup", "dropoff"), meetingEditableKinds("pending"))
        assertEquals(listOf("pickup", "dropoff"), meetingEditableKinds("confirmed"))
        assertEquals(listOf("dropoff"), meetingEditableKinds("onboard"))
        assertTrue(meetingEditableKinds("done").isEmpty())
        assertTrue(meetingEditableKinds("cancelled").isEmpty())
    }

    @Test
    fun `живые контакты используют свою точку и оба языка вместо общего пина`() {
        val d = com.yuldash.app.data.parseBookingDetailsDto(org.json.JSONObject("""{
            "booking_id":7,"role":"passenger","status":"confirmed","contact_unlocked":true,
            "pickup":"общий сбор","pickup_lat":54.0,"pickup_lng":55.0,
            "meet_lat":52.7,"meet_lng":58.6,"meet_note":"синие ворота",
            "meet_landmark_ru":"у школы","meet_landmark_ba":"мәктәп янында"
        }"""))
        val c = passengerContacts(d)!!
        assertEquals("у школы · синие ворота", c.pickup)
        assertEquals("мәктәп янында · синие ворота", c.pickupBa)
        assertEquals(52.7, c.pickupLat!!, 0.00001)
        assertEquals(58.6, c.pickupLng!!, 0.00001)
        assertTrue(c.exactMeeting)
        assertNull(passengerContacts(d.copy(role = "driver")))
        assertNull(passengerContacts(d.copy(status = "cancelled")))
        val pinOnly = passengerContacts(d.copy(meetNote = "", meetLandmarkRu = "", meetLandmarkBa = ""))!!
        assertEquals("", pinOnly.pickup) // UI подпишет булавку, а не чужой общий пин.
        val fallback = passengerContacts(d.copy(meetLat = null, meetLng = null, meetNote = "", meetLandmarkRu = "", meetLandmarkBa = ""))!!
        assertEquals("общий сбор", fallback.pickup)
        assertEquals(54.0, fallback.pickupLat!!, 0.00001)
    }

    @Test
    fun `смена остановки сбрасывает только затронутую точку повторный выбор сохраняет черновик`() {
        val pickup = MeetingPointInput(landmarkId = 7, note = "у школы")
        val dropoff = MeetingPointInput(lat = 52.7, lng = 58.6, note = "ворота")
        assertEquals(pickup, meetingAfterStopChange("Сибай", "Сибай", pickup))
        assertNull(meetingAfterStopChange("Сибай", "Ургаза", pickup))
        assertEquals(dropoff, meetingAfterStopChange("Акъяр", "Акъяр", dropoff))
        assertNull(meetingAfterStopChange("Ургаза", null, dropoff)) // высадка стала недопустима после новой посадки
        assertNull(meetingAfterStopChange(null, "Акъяр", null))
    }

    @Test
    fun `ориентир из деталей сохраняет id и оба имени когда меняется только пояснение`() {
        val d = com.yuldash.app.data.parseBookingDetailsDto(org.json.JSONObject("""{
            "booking_id":7,"role":"passenger","status":"confirmed",
            "meet_landmark_id":17,"meet_landmark_ru":"у школы","meet_landmark_ba":"мәктәп янында",
            "meet_lat":52.7,"meet_lng":58.6,"meet_note":"старое",
            "alight_landmark_id":18,"alight_landmark_ru":"у моста","alight_landmark_ba":"күпер янында"
        }"""))
        for ((kind, id) in listOf("pickup" to 17, "dropoff" to 18)) {
            val edited = meetingPointDraft(d, kind, isBa = true).copy(note = "новое пояснение")
            val restored = decodeMeetingPoint(encodeMeetingPoint(edited))!!
            assertEquals(id, restored.toJson().getInt("landmark_id"))
            assertEquals("новое пояснение", restored.toJson().getString("note"))
            assertEquals(if (kind == "pickup") "мәктәп янында" else "күпер янында", restored.landmarkTitle)
        }
        val old = com.yuldash.app.data.parseBookingDetailsDto(org.json.JSONObject("""{"meet_landmark_id":null}"""))
        assertNull(old.meetLandmarkId)
        assertNull(old.alightLandmarkId)
    }

    @Test
    fun `черновик участка сохраняет точки при повторе и сбрасывает при ручной и автоматической смене`() {
        val ride = Ride(id = "7", from = "Сибай", to = "Акъяр", time = "", driver = "Водитель", car = "", price = 500,
            seats = 3, rating = 5.0, verified = true, boosted = false, waypoints = listOf("Ургаза", "Бурибай"))
        val pickup = MeetingPointInput(landmarkId = 7)
        val alight = MeetingPointInput(note = "ворота")
        val draft = BookingMeetingDraft(ride, androidx.compose.runtime.mutableStateOf("Сибай"),
            androidx.compose.runtime.mutableStateOf("Ургаза"), androidx.compose.runtime.mutableStateOf(pickup), androidx.compose.runtime.mutableStateOf(alight))
        draft.selectPickup("Сибай")
        assertEquals(pickup, draft.meeting)
        assertEquals(alight, draft.alighting)
        draft.selectDropoff("Акъяр")
        assertEquals(pickup, draft.meeting)
        assertNull(draft.alighting)
        draft.alighting = alight
        draft.selectPickup("Ургаза")
        assertNull(draft.meeting)
        assertEquals(alight, draft.alighting) // прежняя конечная высадка остаётся допустимой
        draft.selectDropoff("Бурибай")
        draft.alighting = alight
        draft.selectPickup("Бурибай")
        assertNull(draft.dropoff)
        assertNull(draft.alighting) // в той же остановке высадка больше недопустима
    }

    @Test fun `условия исправления посадки не вытесняют поля точных мест из деталей`() {
        val dto = com.yuldash.app.data.parseBookingDetailsDto(org.json.JSONObject("""{
            "booking_id":42,"role":"passenger","status":"onboard","onboard_at":"2026-10-24T12:00:00.123456Z",
            "boarding_undo_pending":true,"boarding_undo_request_id":"undo-42","boarding_undo_wait_sec":120,
            "meet_lat":52.7,"meet_lng":58.6,"meet_note":"у ворот","meet_landmark_id":17,
            "alight_lat":52.8,"alight_lng":58.7,"alight_note":"у моста","alight_landmark_id":18,
            "meeting_version":4,"meeting_proposal":{"proposal_id":"meeting-42","kind":"dropoff","point":{"clear":true}}
        }"""))
        assertTrue(dto.boardingUndo.pending)
        assertEquals("undo-42", dto.boardingUndo.requestId)
        assertEquals("2026-10-24T12:00:00.123456Z", dto.boardingUndo.onboardAt)
        assertEquals(17, dto.meetLandmarkId); assertEquals(18, dto.alightLandmarkId)
        assertEquals("у ворот", dto.meetNote); assertEquals("у моста", dto.alightNote)
        assertEquals(4, dto.meetingVersion); assertTrue(dto.meetingProposal!!.clear)
    }

}
