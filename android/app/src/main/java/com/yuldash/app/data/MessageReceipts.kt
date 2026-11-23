package com.yuldash.app.data

import org.json.JSONObject

internal fun parseBookingSendReceipt(body: JSONObject, key: String?, myUserId: () -> Int?): MessageDto {
    val message = parseMessageDto(body)
    if (body.has("hidden_for_me")) check(body.opt("hidden_for_me") == true &&
        body.opt("id") is Number && body.optDouble("id") == message.id.toDouble() && message.id > 0 &&
        body.opt("sender_id") is Number && body.optDouble("sender_id") == message.senderId.toDouble() &&
        message.senderId == myUserId() && !key.isNullOrBlank() && message.requestId == key) {
        "Unconfirmed hidden receipt"
    }
    return message
}

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
    )
