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
    val unread: Int? = null,
)

internal fun parseConversationDto(o: JSONObject): ConversationDto {
    return ConversationDto(
        bookingId = o.optInt("booking_id"), peerName = o.optString("peer_name"),
        route = o.optString("route"), lastMessage = o.optString("last_message"),
        peerAvatar = o.optString("peer_avatar"), departAt = o.optString("depart_at").ifBlank { null },
        peerVerified = o.optBoolean("peer_verified"),
        hasMessages = o.opt("has_messages") as? Boolean,
        unread = o.optInt("unread", -1).takeIf { it >= 0 },
    )
}

/** Сообщение чата с сервера. */
data class MessageDto(
    val id: Int,
    val text: String,
    val senderId: Int,
    val voiceUrl: String? = null,
    val deleted: Boolean = false,
    val edited: Boolean = false,
    val flag: String = "",           // анти-фишинг (B8-6): "warn" → плашка-предупреждение получателю
    val fromAdmin: Boolean = false,  // официальность (B8-9): бейдж «Юлдаш ✓» у сообщений админа/системы
    val hiddenForMe: Boolean = false,
    val requestId: String = "",
    val createdAt: String? = null,
)

internal fun parseMessageDto(o: JSONObject): MessageDto =
    MessageDto(
        id = o.optInt("id"),
        text = o.optString("text"),
        senderId = o.optInt("sender_id"),
        voiceUrl = o.optNullableString("voice_url"),
        deleted = o.optBoolean("deleted"),
        edited = o.optBoolean("edited"),
        flag = o.optString("flag"),
        fromAdmin = o.optBoolean("from_admin"),
        hiddenForMe = o.optBoolean("hidden_for_me"),
        requestId = o.optString("request_id"),
        createdAt = o.optNullableString("created_at") ?: o.optNullableString("timestamp"),
    )
