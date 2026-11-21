package com.yuldash.app

import com.yuldash.app.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

/** Synthetic data; uses the real decoder and money formatter, no Android runtime. */
internal object ConsentFixture {
    const val ID = "01f03e58-ab18-4ce5-8f90-d467a66cafbd"
    const val KEY = "f2172172-f2cd-4ee3-a810-7e94c2055921"
    const val SCOPE = "f11e1441-baad-4cd9-91cf-514096674407"
    val wall = 1795262400000L
    fun route(options: List<String> = listOf("seat_1_4"), stops: List<TaxiStop> = emptyList(), round: Boolean = false) =
        TaxiConsentRoute.normalized(52.5,58.1,52.6,58.2,"Synthetic A","Synthetic B","standard",options,stops,round,if(round)60 else 0)
    fun quoteJson(promo: Int = 15000, key: String = KEY) = JSONObject().put("schema","taxi-initial-consent-v1")
        .put("quote_id",ID).put("request_key",key).put("snapshot_digest","a".repeat(64)).put("pricing_inputs_digest","b".repeat(64))
        .put("expires_at","2026-11-21T12:02:00.123456").put("price_kop",60000).put("promo_discount_kop",promo).put("payable_kop",60000-promo)
        .put("breakdown",JSONObject().put("ride_price_kop",45000).put("pickup_fee_kop",5000).put("options_fee_kop",10000).put("weather_fee_kop",0))
        .put("distance_km",17.0).put("eta_min",30.0).put("round_trip",false)
    fun quote(promo: Int = 15000) = readTaxiConsentQuote(quoteJson(promo))
    fun orderJson(status: String = "searching", payable: Int? = null, promo: Int = 15000) = JSONObject()
        .put("id",77).put("role","passenger").put("status",status).put("from_lat",52.5).put("from_lng",58.1)
        .put("to_lat",52.6).put("to_lng",58.2).put("from_text","Synthetic A").put("to_text","Synthetic B")
        .put("category","standard").put("price_estimate",600).put("passenger_price_kop",payable ?: (60000-promo)).put("promo_discount_kop",promo)
    fun receiptJson(status: String = "searching", payable: Int? = null, promo: Int = 15000) = JSONObject()
        .put("order",orderJson(status,payable,promo)).put("consent",JSONObject().put("quote",quoteJson(promo)).put("accepted",quoteJson(promo)))
    fun receipt(status: String = "searching", promo: Int = 15000) = readTaxiConsentReceipt(receiptJson(status,promo=promo),quote(promo),17,ApiClient::parseTaxiConsentOrder)
}

class TaxiQuoteConsentTest {
    @Test fun strictUtcFractionOffsetAndZones() {
        val original = TimeZone.getDefault()
        try {
            for (zone in listOf("UTC","Asia/Yekaterinburg")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                val expected = ConsentFixture.wall+120123
                assertEquals(expected,taxiConsentDeadline("2026-11-21T12:02:00.123456"))
                assertEquals(expected,taxiConsentDeadline("2026-11-21T12:02:00.123456Z"))
                assertEquals(expected,taxiConsentDeadline("2026-11-21T17:02:00.123456+05:00"))
                assertEquals(expected,taxiConsentDeadline("2026-11-21T07:02:00.123456-05:00"))
            }
            for (bad in listOf("", "2026-11-21", "2026-11-21T12:02:00Z ", "2026-02-30T12:00:00Z", "2026-11-21T24:00:00Z",
                "2026-11-21 12:02:00", "2026-11-21T12:02:60Z", "2026-11-21T12:02:00+25:00", "2026-11-21T12:02:00.1234567891Z")) assertNull(bad,taxiConsentDeadline(bad))
        } finally { TimeZone.setDefault(original) }
    }
    @Test fun equalitySleepWallRollbackAndOverflowExpire() {
        val s = TaxiConsentStamp(1000,100,2000)
        assertTrue(s.fresh(1999,1099)); assertFalse(s.fresh(2000,1099)); assertFalse(s.fresh(1500,1100))
        assertFalse(s.fresh(999,101)); assertFalse(s.fresh(1100,99)); assertFalse(TaxiConsentStamp(Long.MIN_VALUE,0,100).fresh(1,1))
    }
    @Test fun oneNormalizedRouteForBothBodies() {
        val stops = listOf(TaxiStop(52.51,58.11,"First"),TaxiStop(52.52,58.12,"Second"))
        val a = ConsentFixture.route(listOf("seat_1_4"," big_luggage ","seat_1_4"),stops,true)
        assertEquals(listOf("big_luggage","seat_1_4"),a.options)
        val estimate=a.quoteBody();val order=JSONObject(a.orderBody(TaxiConsentDetails("Synthetic note",paymentMethod="cash"),ConsentFixture.quote()))
        for (key in estimate.keys()) assertEquals(key,estimate.get(key).toString(),order.get(key).toString())
        assertEquals("First",order.getJSONArray("waypoints").getJSONObject(0).getString("text"))
        assertEquals("Second",order.getJSONArray("waypoints").getJSONObject(1).getString("text"))
        assertNotEquals(a,ConsentFixture.route(a.options,stops.reversed(),true))
        assertNotEquals(a,ConsentFixture.route(listOf("seat_1_4"),stops,true))
        assertFalse(order.has("scheduled_at")); assertFalse(order.has("payable_kop"));assertFalse(order.has("price"))
        assertEquals(ConsentFixture.ID,order.getString("quote_id")); assertEquals(ConsentFixture.KEY,order.getString("request_key"))
        val all = listOf("seat_0_1","seat_1_4","seat_4_7","booster","wheelchair","guide_dog","stroller","pets","big_luggage","charger")
        for (option in all) {
            val selected=ConsentFixture.route(listOf(option),stops,true)
            val requested=JSONObject(selected.orderBody(TaxiConsentDetails(),ConsentFixture.quote()))
            assertEquals(option,selected.quoteBody().getJSONArray("options").getString(0))
            assertEquals(option,requested.getJSONArray("options").getString(0))
            assertEquals(60,requested.getInt("return_wait_min"));assertTrue(requested.getBoolean("round_trip"))
        }
    }
    @Test fun strictFinancialSnapshotRejectsMalformedAndInconsistentMoney() {
        val q=ConsentFixture.quote();assertEquals(45000,q.payableKop)
        val corrupt = listOf<(JSONObject)->Unit>(
            {it.put("payable_kop",60000)}, {it.put("price_kop","60000")}, {it.put("payable_kop",45000.1)},
            {it.put("price_kop",Long.MAX_VALUE)}, {it.getJSONObject("breakdown").put("weather_fee_kop",1)},
            {it.put("quote_id","1-1-1-1-1")}, {it.put("expires_at","garbage")}, {it.put("eta_min","30")})
        corrupt.forEach { mutate -> val o=ConsentFixture.quoteJson();mutate(o);assertTrue(runCatching{readTaxiConsentQuote(o)}.isFailure) }
    }
    @Test fun firstAndRetryCopyAlwaysDescribeNetAmountInBothLanguages() {
        for (promo in listOf(15000,0)) {
            val copy=taxiConsentMoneyCopy(ConsentFixture.quote(promo));val shown=if(promo==0)"600 ₽" else "450 ₽"
            assertEquals(shown,copy.total);assertTrue(copy.ru.endsWith("$shown."));assertTrue(copy.ba.endsWith("$shown."))
            assertTrue(copy.ru.contains("600 ₽"));assertTrue(copy.ba.contains("600 ₽"))
        }
    }
    @Test fun receiptUsesActualIdStatusAndCurrentAmountEvenAfterTerminal() {
        for (s in listOf("searching","expired","cancelled","done")) {
            val r=readTaxiConsentReceipt(ConsentFixture.receiptJson(s,if(s=="done")60000 else 45000),ConsentFixture.quote(),17,ApiClient::parseTaxiConsentOrder)
            assertEquals(77,r.order.id);assertEquals(s,r.order.status);assertEquals(45000,r.accepted.payableKop)
        }
        for (mutate in listOf<(JSONObject)->Unit>({it.getJSONObject("order").put("role","driver")},
            {it.getJSONObject("order").put("passenger_id",18)}, {it.getJSONObject("order").put("status","scheduled")},
            {it.getJSONObject("consent").getJSONObject("quote").put("snapshot_digest","c".repeat(64))})) {
            val o=ConsentFixture.receiptJson();mutate(o);assertTrue(runCatching{readTaxiConsentReceipt(o,ConsentFixture.quote(),17,ApiClient::parseTaxiConsentOrder)}.isFailure)
        }
    }
    @Test fun environmentCannotEnableProductionOrUnpinnedBuild() {
        for (b in listOf(false,true)) for(r in listOf(false,true)) for(p in listOf(false,true)) {
            assertEquals(if(b&&r&&p)TAXI_CONSENT_LOCAL_API else null,taxiConsentEnvironment(b,r,p,TAXI_CONSENT_LOCAL_API))
            assertNull(taxiConsentEnvironment(b,r,p,"https://example.invalid"))
        }
    }
}
