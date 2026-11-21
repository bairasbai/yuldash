package com.yuldash.app.data

import android.app.Application
import com.yuldash.app.ChatDeliveryMark
import com.yuldash.app.TripChatReceiptView
import com.yuldash.app.TripChatReceiptSync
import com.yuldash.app.deliveryMark
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
internal class BookingChatReceiptsTest : SnapshotWiringFixture() {
    private val cutoff = "2026-11-21T10:00:00Z"
    private val since = Instant.parse(cutoff)

    private fun session(userId: Int, tokenMarker: Char): BookingChatReceiptOwner {
        val claims = JSONObject().put("sub", userId.toString()).put("sid", tokenMarker.toString().repeat(64)).toString()
        val body = Base64.getUrlEncoder().withoutPadding().encodeToString(claims.toByteArray(Charsets.UTF_8))
        ApiClient.saveToken("e30.$body.synthetic")
        field("secureAuthPrefs", MemoryDiskPreferences())
        return requireNotNull(ApiClient.bookingChatReceiptOwner(ApiClient.queueSessionGeneration()))
    }

    private fun message(id: Int, sender: Int = 8, created: String? = cutoff) = MessageDto(
        id, "Сообщение $id", sender, createdAt = created,
    )

    private fun wireMessage(id: Int) = JSONObject().put("id", id).put("sender_id", 8)
        .put("text", "Сообщение $id").put("created_at", cutoff)

    private fun flags(id: Int, delivered: Boolean, read: Boolean = false) = JSONObject()
        .put("message_id", id).put("peer_delivered", false).put("peer_read", false)
        .put("my_delivered", delivered).put("my_read", read)

    private fun page(items: JSONArray = JSONArray()) = JSONObject().put("open", true).put("receipts_since", cutoff)
        .put("items", items).put("next_after_message_id", JSONObject.NULL).put("unread", 2)

    @Test fun oldAndRemovedRowsNeverQualifyAndReadImpliesDeliveryForOnlyItsId() {
        assertFalse(message(110, created = "2026-11-21T09:59:59Z").canAcknowledge(7, since))
        assertFalse(message(110, created = null).canAcknowledge(7, since))
        assertFalse(message(110, sender = 7).canAcknowledge(7, since))
        assertFalse(message(110).copy(deleted = true).canAcknowledge(7, since))
        assertFalse(message(110).copy(hiddenForMe = true).canAcknowledge(7, since))
        assertTrue(message(120, created = "2026-11-21T10:00:00").canAcknowledge(7, since))
        assertTrue(message(120, created = "2026-11-21T13:00:00+03:00").canAcknowledge(7, since))
        val received = BookingChatReceipt(120, peerRead = true).including(BookingChatReceipt(120))
        assertTrue(received.peerDelivered)
        val view = TripChatReceiptView(since, mapOf(110 to BookingChatReceipt(110), 120 to received))
        assertEquals(ChatDeliveryMark.Read, deliveryMark(message(120, 7), 7, false, false, view))
        assertEquals(ChatDeliveryMark.Sent, deliveryMark(message(110, 7), 7, false, false, view))
        assertEquals(ChatDeliveryMark.None, deliveryMark(message(109, 7), 7, false, false, view))
    }

    @Test fun durableExactIdsRetainReadWrittenDuringDeliveryAndRejectReplacedOwner() {
        val owner = session(7, 'a')
        val first = BookingChatReceiptStore(owner, 42)
        first.save(listOf(message(120)), read = false)
        val reopened = BookingChatReceiptStore(owner, 42)
        assertEquals(listOf(message(120)), reopened.pending(100).map { it.message })
        assertTrue(BookingChatReceiptStore(owner, 43).pending(100).isEmpty())
        val sent = reopened.pending(100).map { it.message.id }.toSet()
        assertEquals(setOf(120), sent)
        reopened.save(listOf(message(120)), read = true)
        first.settle(sent, listOf(BookingChatReceipt(120, myDelivered = true)))
        assertTrue(reopened.pending(100).single().read)
        assertEquals(0L, first.claim(10_000))
        assertEquals(1_499L, reopened.claim(10_001))
        first.settle(sent, listOf(BookingChatReceipt(120, myRead = true)))
        assertTrue(reopened.pending(100).isEmpty())
        session(7, 'b')
        assertTrue(runCatching { first.save(listOf(message(110)), read = false) }.isFailure)
    }

    @Test fun receiptBatchBoundsAndLegacyFallbackKeepPriorReceiptsHidden() = runBlocking {
        val owner = session(7, 'a')
        handler = { request ->
            if (request.path == "/bookings/42/messages") MockResponse().setBody(JSONArray().put(wireMessage(120)).toString())
            else MockResponse().setResponseCode(404).setBody("{}")
        }
        val history = ApiClient.getBookingChatMessagePage(42, null, owner.generation).getOrThrow()
        assertEquals(listOf(120), history.messages.map { it.id })
        assertFalse(history.hasMore)
        assertNull(ApiClient.getBookingChatReceipts(42, null, owner.generation).getOrThrow().since)
        val exact = bookingChatReceiptBody(setOf(120), emptySet())
        assertEquals("[120]", exact.getJSONArray("delivered_ids").toString())
        assertEquals("[]", exact.getJSONArray("read_ids").toString())
        assertTrue(runCatching { bookingChatReceiptBody((1..101).toSet(), emptySet()) }.isFailure)
    }

    @Test fun mobilePushHintFetchesFullExactMessageBeforeSendingDeliveryAndNeverRead() = runBlocking {
        val owner = session(7, 'a')
        handler = { request ->
            val path = request.requestUrl!!.encodedPath
            when {
                path.endsWith("/message-page") -> MockResponse().setBody(JSONObject()
                    .put("messages", JSONArray().put(wireMessage(120))).put("has_more", false).toString())
                path.endsWith("/receipts") && request.method == "POST" ->
                    MockResponse().setBody(page(JSONArray().put(flags(120, true))).toString())
                path.endsWith("/receipts") -> MockResponse().setBody(page().toString())
                else -> defaultResponse(request)
            }
        }
        ApiClient.fireBookingChatReceiptHint(42, 120, 9, owner.generation)
        ApiClient.fireBookingChatReceiptHint(42, 120, 7, owner.generation)
        withTimeout(5_000) {
            while (requests.none { it.method == "POST" && it.path == "/bookings/42/receipts" }) delay(10)
        }
        val chat = requests.filter { it.path?.startsWith("/bookings/42/") == true }
        assertEquals(listOf("GET", "GET", "POST"), chat.map { it.method })
        assertTrue(chat[1].path!!.contains("/message-page"))
        val body = JSONObject(chat.last().body.clone().readUtf8())
        assertEquals("[120]", body.getJSONArray("delivered_ids").toString())
        assertEquals("[]", body.getJSONArray("read_ids").toString())
    }

    @Test fun realtimeReadSurvivesOlderReceiptPageFinishingAfterItsFrame() = runBlocking {
        val owner = session(7, 'a')
        val sync = TripChatReceiptSync(42, owner, this, { true }, { emptyList() }, { null })
        val secondPage = CountDownLatch(1)
        val releasePage = CountDownLatch(1)
        handler = { MockResponse().setBody(page(JSONArray().put(flags(110, false))).toString()) }
        sync.refresh()
        withTimeout(5_000) { while (110 !in sync.view.items) delay(10) }
        handler = { request ->
            if (request.requestUrl?.queryParameter("after_message_id") == "110") {
                secondPage.countDown()
                check(releasePage.await(10, TimeUnit.SECONDS))
                MockResponse().setBody(page(JSONArray().put(flags(120, false))).toString())
            } else MockResponse().setBody(page(JSONArray().put(flags(110, false)))
                .put("next_after_message_id", 110).toString())
        }
        sync.refresh()
        try {
            withTimeout(5_000) { while (secondPage.count != 0L) delay(10) }
            sync.receive(listOf(ChatSocket.ReceiptEvent(110, 8, delivered = true, read = true)))
            assertTrue(sync.view.items.getValue(110).peerRead)
        } finally { releasePage.countDown() }
        withTimeout(5_000) { while (120 !in sync.view.items) delay(10) }
        assertTrue(sync.view.items.getValue(110).peerRead)
        assertFalse(sync.view.items.getValue(120).peerRead)
    }
}
