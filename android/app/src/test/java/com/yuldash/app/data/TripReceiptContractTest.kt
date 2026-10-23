package com.yuldash.app.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.receiptPersonalSegment
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
class TripReceiptContractTest {
    private lateinit var server:MockWebServer
    @Before fun setup() {
        ApiClient.resetForTest();ApiClient.init(ApplicationProvider.getApplicationContext<Application>())
        ApiClient.saveToken("synthetic-receipt-contract-a")
        server=MockWebServer().apply{start()};ApiClient.testBaseUrl=server.url("/").toString().trimEnd('/')
    }
    @After fun cleanup(){ApiClient.resetForTest();server.shutdown()}
    private fun raw(pickup:Any?=JSONObject.NULL,dropoff:Any?=JSONObject.NULL)=JSONObject()
        .put("booking_id",42).put("role","passenger").put("from_city","Сибай").put("to_city","Акъяр")
        .put("pickup_stop",pickup).put("dropoff_stop",dropoff)

    @Test fun parserPreservesMissingNullEmptyAndPublishedStopsWithoutLegacyGuessing() {
        val missing=parseTripReceiptDto(JSONObject());assertNull(missing.pickupStop);assertNull(missing.dropoffStop)
        for(value in listOf(JSONObject.NULL,"","Ургаза")) {
            val dto=parseTripReceiptDto(raw(value,value))
            val expected=if(value===JSONObject.NULL)null else value
            assertEquals(expected,dto.pickupStop);assertEquals(expected,dto.dropoffStop)
        }
    }
    @Test fun personalSegmentNeedsBothKnownAndOnlyEmptyStringResolvesParentEndpoint() {
        assertNull(receiptPersonalSegment(parseTripReceiptDto(raw())))
        assertNull(receiptPersonalSegment(parseTripReceiptDto(raw("Ургаза",JSONObject.NULL))))
        assertNull(receiptPersonalSegment(parseTripReceiptDto(raw(JSONObject.NULL,"Бурибай"))))
        assertEquals("Сибай → Акъяр",receiptPersonalSegment(parseTripReceiptDto(raw("",""))))
        assertEquals("Ургаза → Акъяр",receiptPersonalSegment(parseTripReceiptDto(raw("Ургаза",""))))
        assertEquals("Ургаза → Бурибай",receiptPersonalSegment(parseTripReceiptDto(raw("Ургаза","Бурибай"))))
        assertNull(receiptPersonalSegment(parseTripReceiptDto(raw(" ","Бурибай"))))
    }
    @Test fun actualApiParsesRawPrivateStopsAndKeepsExistingOwnRatingAndAmount()=runBlocking<Unit> {
        val body=raw("Ургаза","Бурибай").put("amount",300).put("pay_method","cash").put("my_stars",5).put("driver_verified",true)
        server.enqueue(MockResponse().setBody(body.toString()))
        val dto=ApiClient.getTripReceipt(42,ApiClient.queueSessionGeneration()).getOrThrow()
        assertEquals("Ургаза",dto.pickupStop);assertEquals("Бурибай",dto.dropoffStop)
        assertEquals(5,dto.myStars);assertEquals(300,dto.amount);assertEquals("cash",dto.payMethod);assertTrue(dto.driverVerified)
        val request=server.takeRequest();assertEquals("GET",request.method);assertEquals("/trips/42/receipt",request.path)
        assertEquals("Bearer synthetic-receipt-contract-a",request.getHeader("Authorization"))
    }
    @Test fun allFourEntriesRejectCapturedGenerationBeforeAnyHttp()=runBlocking<Unit> {
        val old=ApiClient.queueSessionGeneration();ApiClient.saveToken("synthetic-receipt-contract-b")
        assertTrue(ApiClient.getTripReceipt(42,old).isFailure)
        assertTrue(ApiClient.getBookingTipInfo(42,old).isFailure)
        assertTrue(ApiClient.bookingLostItem(42,old).isFailure)
        assertTrue(ApiClient.sayBookingThanks(42,old).isFailure)
        assertEquals("Stale receipt owner must not start HTTP",0,server.requestCount)
    }
    @Test fun currentWarmActionsKeepOriginalPathsAndBodies()=runBlocking<Unit> {
        val generation=ApiClient.queueSessionGeneration()
        server.enqueue(MockResponse().setBody("{}"));ApiClient.sayBookingThanks(42,generation).getOrThrow()
        val thanks=server.takeRequest();assertEquals("POST",thanks.method);assertEquals("/bookings/42/thanks",thanks.path)
        assertEquals(0,JSONObject(thanks.body.readUtf8()).length())
        server.enqueue(MockResponse().setBody("""{"chat_open_until":"2030-01-02T10:00:00Z"}"""))
        assertEquals("2030-01-02T10:00:00Z",ApiClient.bookingLostItem(42,generation).getOrThrow())
        val lost=server.takeRequest();assertEquals("POST",lost.method);assertEquals("/bookings/42/lost-item",lost.path)
        assertEquals(0,JSONObject(lost.body.readUtf8()).length())
    }
}
