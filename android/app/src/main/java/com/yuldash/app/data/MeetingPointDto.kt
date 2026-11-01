package com.yuldash.app.data

import org.json.JSONObject

/** RS-03 А: точное место встречи/высадки при брони — по желанию пассажира.
 *  Точка на карте (lat/lng) или курируемый ориентир (landmarkId) плюс короткое пояснение.
 *  clear=true — снять ранее указанное место. */
data class MeetingPointInput(
    val lat: Double? = null,
    val lng: Double? = null,
    val note: String = "",
    val landmarkId: Int? = null,
    val clear: Boolean = false,
    val landmarkTitle: String = "",   // только для UI (подпись в строке); на сервер уходит landmarkId
) {
    val isEmpty: Boolean get() = lat == null && lng == null && note.isBlank() && landmarkId == null && !clear

    fun toJson(): JSONObject = JSONObject().apply {
        if (lat != null) put("lat", lat)
        if (lng != null) put("lng", lng)
        if (note.isNotBlank()) put("note", note.take(120))
        if (landmarkId != null) put("landmark_id", landmarkId)
        if (clear) put("clear", true)
    }
}

/** Состояние формы переживает смену конфигурации и смерть процесса (как остальная форма брони). */
internal fun encodeMeetingPoint(v: MeetingPointInput?): String =
    if (v == null) "0" else "1" + JSONObject().apply {
        if (v.lat != null) put("lat", v.lat); if (v.lng != null) put("lng", v.lng)
        put("note", v.note); if (v.landmarkId != null) put("lid", v.landmarkId)
        if (v.clear) put("clear", true); put("lt", v.landmarkTitle)
    }.toString()

internal fun decodeMeetingPoint(encoded: String): MeetingPointInput? {
    if (!encoded.startsWith("1")) return null
    return runCatching {
        val o = JSONObject(encoded.substring(1))
        MeetingPointInput(
            lat = if (o.has("lat")) o.getDouble("lat") else null,
            lng = if (o.has("lng")) o.getDouble("lng") else null,
            note = o.optString("note"),
            landmarkId = if (o.has("lid")) o.getInt("lid") else null,
            clear = o.optBoolean("clear"),
            landmarkTitle = o.optString("lt"),
        )
    }.getOrNull()
}

val MeetingPointInputSaver = androidx.compose.runtime.saveable.Saver<MeetingPointInput?, String>(
    save = { v -> encodeMeetingPoint(v) },
    restore = { encoded -> decodeMeetingPoint(encoded) },
)
