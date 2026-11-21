package com.yuldash.app.data

import android.app.Application
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
internal class ChatSocketReceiptTest : SnapshotWiringFixture() {
    @Test fun sendsOnlyExplicitIdsAndSeparatesPeerEventFromServerAcknowledgement() = runBlocking {
        val authed = CompletableDeferred<Unit>()
        val sent = CompletableDeferred<JSONObject>()
        val changes = CompletableDeferred<List<ChatSocket.ReceiptEvent>>()
        handler = {
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val frame = JSONObject(text)
                    when (frame.getString("type")) {
                        "auth" -> authed.complete(Unit)
                        "receipt" -> {
                            sent.complete(frame)
                            webSocket.send("""{"type":"receipt","items":[{"message_id":110,"user_id":8,"delivered":true,"read":false}]}""")
                            webSocket.send("""{"type":"receipt_ack","open":true,"receipts_since":"2026-11-21T10:00:00Z","items":[{"message_id":120,"peer_delivered":false,"peer_read":false,"my_delivered":true,"my_read":false}],"next_after_message_id":null,"unread":2}""")
                        }
                    }
                }
            })
        }
        val socket = ChatSocket(42, onMessage = {}, onReceipt = { changes.complete(it) })
        try {
            socket.connect()
            withTimeout(5_000) { authed.await() }
            val reply = requireNotNull(socket.sendReceipt(setOf(120), emptySet()))
            val wire = withTimeout(5_000) { sent.await() }
            assertEquals("[120]", wire.getJSONArray("delivered_ids").toString())
            assertEquals("[]", wire.getJSONArray("read_ids").toString())
            assertEquals(listOf(120), reply.items.map { it.messageId })
            assertTrue(reply.items.single().myDelivered)
            assertEquals(listOf(ChatSocket.ReceiptEvent(110, 8, true, false)), withTimeout(5_000) { changes.await() })
        } finally {
            socket.close()
        }
    }
}
