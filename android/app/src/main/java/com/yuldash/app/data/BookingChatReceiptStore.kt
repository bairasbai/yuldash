package com.yuldash.app.data

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

internal data class BookingChatReceiptOwner(val userId: Int, val generation: Long, val apiBase: String, val sessionId: String)

/** Full pending messages stay in encrypted preferences until the exact IDs are settled. */
internal class BookingChatReceiptStore(private val owner: BookingChatReceiptOwner, bookingId: Int) {
    data class Entry(val message: MessageDto, val read: Boolean)
    private data class Pending(val entries: Map<Int, Entry> = emptyMap(), val startedAt: Long = 0)

    private val key = "$PREFIX$bookingId"
    private val session = JSONObject().put("user", owner.userId).put("api", owner.apiBase)
        .put("session", owner.sessionId).toString()

    fun pending(limit: Int): List<Entry> = access { prefs -> load(prefs).entries.values.take(limit) }.getOrThrow()

    fun save(messages: List<MessageDto>, read: Boolean) = access { prefs ->
        val old = load(prefs)
        val entries = old.entries.toMutableMap()
        for (message in messages) {
            entries[message.id] = Entry(message, read || entries[message.id]?.read == true)
        }
        if (entries != old.entries) save(prefs, old.copy(entries = entries))
    }.getOrThrow()

    fun settle(requested: Set<Int>, items: List<BookingChatReceipt>) = access { prefs ->
        val old = load(prefs)
        val confirmed = items.associateBy { it.messageId }
        val remaining = old.entries.filter { (id, row) ->
            if (id !in requested) true else confirmed[id]?.let { item -> !item.myRead && (row.read || !item.myDelivered) } == true
        }
        if (remaining != old.entries) save(prefs, old.copy(entries = remaining))
    }.getOrThrow()

    fun claim(now: Long): Long = access { prefs ->
        val old = load(prefs)
        val remaining = if (now >= old.startedAt) (old.startedAt + CHAT_RECEIPT_INTERVAL_MS - now).coerceAtLeast(0) else 0
        if (remaining == 0L) save(prefs, old.copy(startedAt = now))
        remaining
    }.getOrThrow()

    fun clear() = access { prefs ->
        if (prefs.contains(key)) check(prefs.edit().remove(key).commit())
    }.getOrThrow()

    private fun load(prefs: SharedPreferences): Pending {
        val raw = prefs.getString(key, null) ?: return Pending()
        val value = JSONObject(raw)
        if (value.getString("owner") != session) return Pending()
        val rows = value.getJSONArray("messages")
        val entries = List(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            val message = parseMessageDto(row)
            message.id to Entry(message, row.getBoolean("read"))
        }.toMap()
        return Pending(entries, value.getLong("started_at"))
    }

    private fun save(prefs: SharedPreferences, pending: Pending) {
        val rows = JSONArray()
        for ((message, read) in pending.entries.values) {
            rows.put(JSONObject().put("id", message.id).put("sender_id", message.senderId).put("text", message.text)
                .put("voice_url", message.voiceUrl).put("created_at", message.createdAt).put("read", read))
        }
        val record = JSONObject().put("owner", session).put("started_at", pending.startedAt).put("messages", rows)
        check(prefs.edit().putString(key, record.toString()).commit()) { "Chat receipt commit failed" }
    }

    private fun <T : Any> access(action: (SharedPreferences) -> T): Result<T> =
        runCatching { checkNotNull(ApiClient.bookingChatReceiptStorage(owner, action)) }

    companion object {
        private const val PREFIX = "booking_chat_receipt:"

        fun clearAll(prefs: SharedPreferences) {
            runCatching {
                val keys = prefs.all.keys.filter { it.startsWith(PREFIX) }
                if (keys.isNotEmpty()) prefs.edit().apply { keys.forEach { remove(it) } }.commit()
            }
        }
    }
}
