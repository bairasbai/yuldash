package com.yuldash.app.data

import android.app.Application
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
internal class SnapshotHistoryWiringTest : SnapshotWiringFixture() {
    @Test fun actualOutOfOrderReceiptsRejectAAfterBButAcceptAlreadyInflightNewerC() = runBlocking {
        val first = CountDownLatch(1); val second = CountDownLatch(1); val third = CountDownLatch(1)
        val releaseA = CountDownLatch(1); val releaseB = CountDownLatch(1); val releaseC = CountDownLatch(1)
        val sequence = AtomicInteger()
        handler = { request -> if (request.path == "/trips/11/receipt") {
            val number = sequence.incrementAndGet()
            when (number) {
                1 -> { first.countDown(); check(releaseA.await(10, TimeUnit.SECONDS)) }
                2 -> { second.countDown(); check(releaseB.await(10, TimeUnit.SECONDS)) }
                3 -> { third.countDown(); check(releaseC.await(10, TimeUnit.SECONDS)) }
            }
            MockResponse().setBody(receiptJson(marker = "receipt-$number").toString())
        } else defaultResponse(request) }
        ApiClient.testTimeoutMs = 8000
        try {
            val a = async(Dispatchers.IO) { ApiClient.getTripReceipt(11).getOrThrow() }
            assertTrue(first.await(10, TimeUnit.SECONDS))
            val b = async(Dispatchers.IO) { ApiClient.getTripReceipt(11).getOrThrow() }
            assertTrue(second.await(10, TimeUnit.SECONDS))
            val c = async(Dispatchers.IO) { ApiClient.getTripReceipt(11).getOrThrow() }
            assertTrue(third.await(10, TimeUnit.SECONDS))
            releaseB.countDown(); val currentB = b.await(); assertTrue(OfflineTripSnapshots.allowsHistory(currentB))
            releaseA.countDown(); val oldA = a.await()
            assertFalse(OfflineTripSnapshots.allowsHistory(oldA)); assertTrue(OfflineTripSnapshots.allowsHistory(currentB))
            releaseC.countDown(); val currentC = c.await()
            assertTrue(OfflineTripSnapshots.allowsHistory(currentC)); assertFalse(OfflineTripSnapshots.allowsHistory(currentB))
            assertFalse(OfflineTripSnapshots.allowsLive(11)); assertNull(TripPassStore.load(context, 11))
        } finally { releaseA.countDown(); releaseB.countDown(); releaseC.countDown() }
    }
    @Test fun receiptForAnotherBookingDoesNotReplaceTheLiveBookingAnd403RevokesOnlyItsHistory() = runBlocking {
        warm(); ApiClient.getBookingDetails(12).getOrThrow()
        assertTrue(OfflineTripSnapshots.allowsLive(12))
        val receipt = ApiClient.getTripReceipt(11).getOrThrow()
        assertTrue(OfflineTripSnapshots.allowsHistory(receipt)); assertTrue(OfflineTripSnapshots.allowsLive(12))
        handler = { request -> if (request.path == "/trips/11/receipt") MockResponse().setResponseCode(403).setBody("{}") else defaultResponse(request) }
        assertTrue(ApiClient.getTripReceipt(11).isFailure)
        assertFalse(OfflineTripSnapshots.allowsHistory(receipt)); assertTrue(OfflineTripSnapshots.allowsLive(12))
    }
    @Test fun queuedAcceptedDoneClosesExactlyBookingWithoutResendingOrGrantingColdLive() = runBlocking {
        warm(); ApiClient.getBookingDetails(12).getOrThrow()
        val action = OutboxAction(1, 11, "trip_status", "done", 1000)
        assertTrue(ApiClient.sendQueuedAction(action, ApiClient.queueSessionGeneration()).isSuccess)
        assertFalse(OfflineTripSnapshots.allowsLive(11)); assertNull(TripPassStore.load(context, 11))
        assertTrue(OfflineTripSnapshots.allowsLive(12))
        assertEquals(1, requests.count { it.method == "POST" && it.path == "/bookings/11/trip-status" })
        val receipt = ApiClient.getTripReceipt(11).getOrThrow(); assertTrue(OfflineTripSnapshots.allowsHistory(receipt))
        assertFalse(OfflineTripSnapshots.allowsLive(11))
    }
}
