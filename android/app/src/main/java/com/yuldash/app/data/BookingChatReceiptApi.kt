package com.yuldash.app.data

internal suspend fun ApiClient.getBookingChatMessagePage(
    bookingId: Int, beforeId: Int?, generation: Long,
): Result<BookingChatMessagePage> {
    val cursor = beforeId?.let { "&before_id=$it" }.orEmpty()
    val result = authorized("GET", "/bookings/$bookingId/message-page?limit=100$cursor", null,
        owner = generation)
    if (beforeId == null && (result.exceptionOrNull() as? ApiException)?.status == 404) {
        return readBookingMessages(bookingId, generation).map { BookingChatMessagePage(it, null, false) }
    }
    return result.mapCatching(::parseBookingChatMessagePage)
}

internal suspend fun ApiClient.getBookingChatReceipts(
    bookingId: Int, afterId: Int?, generation: Long, limit: Int = 500,
): Result<BookingChatReceiptPage> {
    val cursor = afterId?.let { "&after_message_id=$it" }.orEmpty()
    val result = authorized("GET", "/bookings/$bookingId/receipts?limit=$limit$cursor", null,
        owner = generation)
    if (afterId == null && (result.exceptionOrNull() as? ApiException)?.status == 404) {
        return Result.success(BookingChatReceiptPage(null))
    }
    return result.mapCatching(::parseBookingChatReceiptPage)
}

internal suspend fun ApiClient.confirmBookingChatReceipts(
    bookingId: Int, delivered: Set<Int>, read: Set<Int>, generation: Long,
): Result<BookingChatReceiptPage> = authorized(
    "POST", "/bookings/$bookingId/receipts", bookingChatReceiptBody(delivered, read),
    owner = generation,
).mapCatching(::parseBookingChatReceiptPage)

internal suspend fun ApiClient.deliverBookingChatReceiptHint(
    bookingId: Int, messageId: Int, owner: BookingChatReceiptOwner,
) {
    val generation = owner.generation
    val recipient = owner.userId
    val page = getBookingChatReceipts(bookingId, null, generation, limit = 1).getOrNull() ?: return
    val since = page.since ?: return
    val message = getBookingChatMessagePage(bookingId, null, generation).getOrNull()?.messages
        ?.firstOrNull { it.id == messageId && it.canAcknowledge(recipient, since) } ?: return
    val storage = BookingChatReceiptStore(owner, bookingId)
    if (runCatching { storage.save(listOf(message), read = false) }.isFailure) return
    val wait = runCatching { storage.claim(System.currentTimeMillis()) }.getOrNull() ?: return
    // If foreground or another hint has just sent a batch, the durable queue waits for reconnection/opening.
    if (wait > 0) return
    val ids = setOf(messageId)
    val reply = confirmBookingChatReceipts(bookingId, ids, emptySet(), generation).getOrNull() ?: return
    runCatching {
        if (reply.since == since) storage.settle(ids, reply.items)
        else if (reply.since == null) storage.clear()
    }
}
