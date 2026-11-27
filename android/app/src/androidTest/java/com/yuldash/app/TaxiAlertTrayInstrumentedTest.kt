package com.yuldash.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/** Real Android notification slots; synthetic observations, no account, network or FCM. */
@RunWith(AndroidJUnit4::class)
class TaxiAlertTrayInstrumentedTest {
    @Test fun currentEmptyClearsOnlyOfferAndStaleEmptyKeepsReplacement() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("taxiAlertTray") == "isolated")
        assertEquals("http://127.0.0.1:1", BuildConfig.YULDASH_API_BASE_URL)
        val context = instrumentation.targetContext
        val manager = checkNotNull(context.getSystemService(NotificationManager::class.java))
        assertTrue("Notification permission was not granted to the test app", manager.areNotificationsEnabled())

        val channel = "taxi-tray-fixture"
        manager.createNotificationChannel(NotificationChannel(channel, channel, NotificationManager.IMPORTANCE_LOW))
        val reasonTag = TaxiLineService.NOTIF_TAG_CLOSED
        val reasonId = TaxiLineService.NOTIF_ID_CLOSED
        val offerTag = TaxiOfferNotifier.NOTIF_TAG
        val offerId = TaxiOfferNotifier.NOTIF_ID
        val publication = TaxiOfferPublication()
        val deadline = Instant.now().plusSeconds(60).toString()
        val session = 1L
        val reason = "Synthetic line-closed reason"

        fun tray(tag: String?, id: Int) = manager.activeNotifications
            .firstOrNull { it.tag == tag && it.id == id }?.notification
            ?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()

        fun await(message: String, predicate: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + 5_000L
            while (!predicate() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(50)
            assertTrue(message, predicate())
        }

        fun publish(id: Int, from: String) {
            publication.observe(publication.snapshot(session), TaxiOfferPublication.Offer(id, deadline)) {
                TaxiOfferNotifier.show(
                    context, id, from, "Synthetic destination", 285, AppLanguage.Ru, deadline, recipientUserId = null,
                )
            }
            await("Offer $id was not posted") { tray(offerTag, offerId)?.startsWith(from) == true }
        }

        fun empty(ticket: TaxiOfferPublication.Ticket) {
            publication.observe(ticket, null, clear = { TaxiOfferNotifier.cancel(context) })
        }

        try {
            publish(801, "Synthetic offer A")
            manager.notify(reasonTag, reasonId, Notification.Builder(context, channel)
                .setSmallIcon(android.R.drawable.ic_dialog_alert).setContentText(reason).build())
            await("Line reason and A were not both posted") { tray(reasonTag, reasonId) == reason && tray(offerTag, offerId) != null }
            empty(publication.snapshot(session))
            await("Accepted empty observation did not clear A") { tray(offerTag, offerId) == null }
            assertEquals(reason, tray(reasonTag, reasonId))

            val awaitingEmpty = publication.snapshot(session)
            publish(802, "Synthetic offer B")
            empty(awaitingEmpty)
            // Let Android's asynchronous notification queue settle before testing absence of a cancel.
            SystemClock.sleep(750)
            assertTrue("Stale empty observation cleared B", tray(offerTag, offerId)?.startsWith("Synthetic offer B") == true)
            assertEquals(reason, tray(reasonTag, reasonId))
            instrumentation.sendStatus(0, android.os.Bundle().apply { putString("taxi_tray_result", "verified") })
        } finally {
            TaxiOfferNotifier.cancel(context)
            manager.cancel(reasonTag, reasonId)
            manager.deleteNotificationChannel(channel)
        }
    }
}
