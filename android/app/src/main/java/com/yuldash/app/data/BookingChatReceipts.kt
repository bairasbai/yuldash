package com.yuldash.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

internal const val CHAT_RECEIPT_BATCH_SIZE = 100
internal const val CHAT_RECEIPT_INTERVAL_MS = 1_500L

internal data class BookingChatMessagePage(
    val messages: List<MessageDto>,
    val nextBeforeId: Int?,
    val hasMore: Boolean,
)

internal data class BookingChatReceipt(
    val messageId: Int,
    val peerDelivered: Boolean = false,
    val peerRead: Boolean = false,
    val myDelivered: Boolean = false,
    val myRead: Boolean = false,
) {
    fun including(item: BookingChatReceipt): BookingChatReceipt {
        require(messageId == item.messageId)
        val readByPeer = peerRead || item.peerRead
        val readByMe = myRead || item.myRead
        return BookingChatReceipt(
            messageId, readByPeer || peerDelivered || item.peerDelivered, readByPeer,
            readByMe || myDelivered || item.myDelivered, readByMe,
        )
    }
}

internal data class BookingChatReceiptPage(
    val since: Instant?,
    val items: List<BookingChatReceipt> = emptyList(),
    val nextAfterId: Int? = null,
    val unread: Int = 0,
)

internal fun bookingChatInstant(value: String?): Instant? {
    if (value.isNullOrBlank()) return null
    return runCatching { Instant.parse(value) }.getOrElse {
        runCatching { LocalDateTime.parse(value).toInstant(ZoneOffset.UTC) }.getOrNull()
    }
}

internal fun MessageDto.canAcknowledge(myId: Int, since: Instant): Boolean =
    id > 0 && senderId > 0 && senderId != myId && !deleted && !hiddenForMe && !fromAdmin &&
        (text.isNotBlank() || !voiceUrl.isNullOrBlank()) && bookingChatInstant(createdAt)?.let { it >= since } == true

internal fun parseBookingChatMessagePage(body: JSONObject): BookingChatMessagePage {
    val messages = body.getJSONArray("messages")
    val nextBeforeId = body.optInt("next_before_id").takeIf { it > 0 }
    val hasMore = body.getBoolean("has_more")
    require(!hasMore || nextBeforeId != null)
    return BookingChatMessagePage(
        List(messages.length()) { parseMessageDto(messages.getJSONObject(it)) }, nextBeforeId, hasMore,
    )
}

internal fun parseBookingChatReceiptPage(body: JSONObject): BookingChatReceiptPage {
    if (!body.getBoolean("open")) return BookingChatReceiptPage(null)
    val since = requireNotNull(bookingChatInstant(body.getString("receipts_since")))
    val rows = body.getJSONArray("items")
    val receipts = List(rows.length()) { index ->
        val row = rows.getJSONObject(index)
        val id = row.getInt("message_id")
        require(id > 0)
        BookingChatReceipt(
            id, row.getBoolean("peer_delivered"), row.getBoolean("peer_read"),
            row.getBoolean("my_delivered"), row.getBoolean("my_read"),
        ).let { it.including(it) }
    }
    return BookingChatReceiptPage(since, receipts, body.optInt("next_after_message_id").takeIf { it > 0 }, body.getInt("unread"))
}

internal fun bookingChatReceiptBody(delivered: Set<Int>, read: Set<Int>): JSONObject {
    require(delivered.size <= CHAT_RECEIPT_BATCH_SIZE && read.size <= CHAT_RECEIPT_BATCH_SIZE)
    require(delivered.all { it > 0 } && read.all { it > 0 })
    return JSONObject().put("delivered_ids", JSONArray(delivered)).put("read_ids", JSONArray(read))
}
