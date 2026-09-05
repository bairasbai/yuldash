package com.yuldash.app

import com.yuldash.app.data.ChatSocket
import com.yuldash.app.data.LocationSocket
import com.yuldash.app.data.TripLocationBus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SocketContractTest {

    @Test
    fun chatIncoming_keepsServerMessageIdentityAndSender() {
        val incoming = ChatSocket.Incoming(id = 15, senderId = 7, text = "Привет", timestamp = "2026-07-03T12:00:00")
        val edited = incoming.copy(text = "Привет еще раз")

        assertEquals(15, incoming.id)
        assertEquals(7, incoming.senderId)
        assertEquals("Привет еще раз", edited.text)
        assertEquals(incoming.timestamp, edited.timestamp)
    }

    @Test
    fun locationPeer_acceptsNullableBearingForPrivacyFriendlyFallbacks() {
        val peer = LocationSocket.Peer(role = "driver", lat = 53.1, lng = 58.2, bearing = null, ts = 123)
        val withBearing = peer.copy(bearing = 91.5)

        assertEquals("driver", peer.role)
        assertNull(peer.bearing)
        assertEquals(91.5, withBearing.bearing!!, 0.0)
    }

    @Test
    fun tripLocationBus_startsEmptyAndCanStoreLatestPeer() {
        TripLocationBus.peer = null
        assertNull(TripLocationBus.peer)

        val peer = LocationSocket.Peer("passenger", 54.0, 56.0, 180.0, 456)
        TripLocationBus.peer = peer

        assertEquals(peer, TripLocationBus.peer)
        TripLocationBus.peer = null
    }
}
