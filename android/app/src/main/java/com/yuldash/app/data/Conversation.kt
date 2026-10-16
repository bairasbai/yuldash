package com.yuldash.app.data

import org.json.JSONObject

// Инбокс: модель и ручной разбор; FQName класса/полей и контракт не меняются.
data class ConversationDto(
    val bookingId: Int,
    val peerName: String,
    val route: String,
    val lastMessage: String,
    val peerAvatar: String = "",
    val departAt: String? = null,   // ISO времени выезда — различать треды одного маршрута
    val peerVerified: Boolean = false,   // реальный статус проверки собеседника (с сервера)
    val hasMessages: Boolean? = null,   // null: старый/неизвестный ответ, текст не угадываем
)

internal fun parseConversationDto(o: JSONObject): ConversationDto {
    return ConversationDto(
        bookingId = o.optInt("booking_id"), peerName = o.optString("peer_name"),
        route = o.optString("route"), lastMessage = o.optString("last_message"),
        peerAvatar = o.optString("peer_avatar"), departAt = o.optString("depart_at").ifBlank { null },
        peerVerified = o.optBoolean("peer_verified"),
        hasMessages = o.opt("has_messages") as? Boolean,
    )
}
