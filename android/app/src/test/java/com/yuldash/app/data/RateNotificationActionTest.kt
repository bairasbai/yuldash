package com.yuldash.app.data

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RateNotificationActionTest {
    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private lateinit var fixture: TripPassAuthorityFixture
    private lateinit var owner: SnapshotOwner
    private val until get() = System.currentTimeMillis() / 1_000L + 24 * 60 * 60
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val keyguard get() = shadowOf(context.getSystemService(KeyguardManager::class.java))

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        server = MockWebServer()
        server.start(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0)
        fixture = TripPassAuthorityFixture()
        TripPassAuthorityFixture.installSession("http://127.0.0.1:${server.port}")
        owner = requireNotNull(OfflineTripSnapshots.currentOwner())
        Outbox.initStores(MemoryDiskPreferences(), MemoryDiskPreferences())
        FcmService.ensureChannels(context)
        keyguard.setIsDeviceLocked(false)
    }

    @After
    fun cleanup() {
        Outbox.clearAll()
        manager.cancelAll()
        fixture.close()
        server.shutdown()
    }

    private fun notification(): Notification? = shadowOf(manager).getNotification("booking_done|27".hashCode())

    private fun ask() {
        deliver(requireNotNull(RateNotificationAction.askAction(context, owner, 27, until).actionIntent))
    }

    private fun deliver(pending: PendingIntent) {
        RateNotificationAction().onReceive(context, shadowOf(pending).savedIntent)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun asksBeforeSaveAndDoesNotSendFromLockedDevice() {
        keyguard.setIsDeviceLocked(true)
        ask()
        val explanation = requireNotNull(notification())
        assertEquals(1, explanation.actions.size)
        assertNull(explanation.publicVersion.actions)
        assertFalse(Outbox.hasPending(context))
        keyguard.setIsDeviceLocked(false)
        ask()
        val confirmation = requireNotNull(notification())
        assertEquals(2, confirmation.actions.size)
        assertFalse(Outbox.hasPending(context))
        keyguard.setIsDeviceLocked(true)
        deliver(confirmation.actions[0].actionIntent)
        assertFalse(Outbox.hasPending(context))
        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
    }

    @Test
    fun oldConfirmationAfterAccountChangeDoesNotSend() {
        ask()
        val confirmation = requireNotNull(notification()).actions[0].actionIntent
        fixture.nextAccount(8)
        deliver(confirmation)
        assertFalse(Outbox.hasPending(context))
        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
    }

    @Test
    fun queueRespectsLockExpiryAndReturnsPreviouslySavedStars() = runBlocking {
        val action = requireNotNull(Outbox.saveRating(context, 27, QueuedRating(owner.namespace, until), owner.generation))
        assertEquals(0, Outbox.count(context, 27))
        assertTrue(Outbox.hasPending(context))
        keyguard.setIsDeviceLocked(true)
        Outbox.flush(context, owner.generation)
        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
        assertTrue(Outbox.contains(context, action))
        keyguard.setIsDeviceLocked(false)
        val message = Outbox.newMessage(29, "Test message")
        assertTrue(Outbox.enqueue(context, message, owner.generation))
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(503))
        Outbox.flush(context, owner.generation)
        assertEquals("/bookings/27/rate", server.takeRequest(1, TimeUnit.SECONDS)?.path)
        assertEquals("/bookings/29/messages", server.takeRequest(1, TimeUnit.SECONDS)?.path)
        assertTrue(Outbox.contains(context, action))
        assertFalse(Outbox.cancelRating(context, 27, owner.namespace, action.id, owner.generation))
        assertTrue(Outbox.acknowledge(context, message, owner.generation))
        server.enqueue(MockResponse().setBody("""{"kept":true,"my_stars":3}"""))
        Outbox.flush(context, owner.generation)
        val request = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        assertEquals("/bookings/27/rate", request.path)
        assertEquals("""{"stars":5,"from_notification":true}""", request.body.readUtf8())
        assertFalse(Outbox.contains(context, action))
        assertTrue(requireNotNull(notification()).extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("3 ★"))
        assertNull(Outbox.saveRating(context, 27, QueuedRating(owner.namespace, 1), owner.generation))
    }

    @Test
    fun cancelledAndUnencryptedDraftsCannotReachNetwork() = runBlocking {
        val action = requireNotNull(Outbox.saveRating(context, 27, QueuedRating(owner.namespace, until), owner.generation))
        assertTrue(Outbox.cancelRating(context, 27, owner.namespace, action.id, owner.generation))
        Outbox.initStores(MemoryDiskPreferences(), null)
        assertNull(Outbox.saveRating(context, 27, QueuedRating(owner.namespace, until), owner.generation))
        Outbox.flush(context, owner.generation)
        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
    }

    @Test
    fun successfulConfirmationSendsExactlyOneRating() {
        server.enqueue(MockResponse().setBody("""{"kept":false,"my_stars":5}"""))
        ask()
        val yes = requireNotNull(notification()).actions[0].actionIntent
        deliver(yes)
        deliver(yes)
        assertEquals("/bookings/27/rate", server.takeRequest(3, TimeUnit.SECONDS)?.path)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (Outbox.hasPending(context) && System.nanoTime() < deadline) Thread.sleep(20)
        assertFalse(Outbox.hasPending(context))
        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
    }
}
