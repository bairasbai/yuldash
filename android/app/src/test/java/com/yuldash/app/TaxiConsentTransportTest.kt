package com.yuldash.app

import android.content.SharedPreferences
import com.yuldash.app.data.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.lang.reflect.Proxy

/** Actual ApiClient -> OkHttp -> loopback HTTP. Not an Android UI or backend acceptance. */
class TaxiConsentTransportTest {
    @Before fun isolate() { ApiClient.resetForTest(); ApiClient.testTimeoutMs=250 }
    @After fun cleanup() { ApiClient.testTimeoutMs=null; ApiClient.resetForTest() }
    private fun session(): TaxiConsentOwner? {
        val payload=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("""{"sub":"17"}""".toByteArray())
        ApiClient.saveToken("synthetic.$payload.signature")
        ApiClient.testTimeoutMs=250
        return TaxiQuoteConsentApi.owner()
    }
    /** Synthetic SharedPreferences seam only; actual refresh HTTP and ApiClient logic are real. */
    private fun memoryPrefs(): SharedPreferences {
        val data=mutableMapOf<String,String?>()
        lateinit var prefs:SharedPreferences
        prefs=Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,arrayOf(SharedPreferences::class.java)) { _,m,a ->
            when(m.name) {
                "getString" -> data[a!![0] as String] ?: a[1]
                "contains" -> data.containsKey(a!![0] as String)
                "getAll" -> data.toMap()
                "edit" -> {
                    val changes=mutableMapOf<String,String?>()
                    lateinit var editor:SharedPreferences.Editor
                    editor=Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,arrayOf(SharedPreferences.Editor::class.java)) { _,em,ea ->
                        when(em.name) {
                            "putString" -> {changes[ea!![0] as String]=ea[1] as String?;editor}
                            "remove" -> {changes[ea!![0] as String]=null;editor}
                            "commit" -> {changes.forEach { (k,v) -> if(v==null)data.remove(k) else data[k]=v };true}
                            else -> error("Unexpected synthetic Editor method: ${em.name}")
                        }
                    } as SharedPreferences.Editor
                    editor
                }
                else -> error("Unexpected synthetic preferences method: ${m.name}")
            }
        } as SharedPreferences
        return prefs
    }
    private fun syntheticAccess(prefix:String):String {
        val payload=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("""{"sub":"17"}""".toByteArray())
        return "$prefix.$payload.signature"
    }
    private suspend fun refreshSession(server:MockWebServer):TaxiConsentOwner {
        server.enqueue(MockResponse().setBody(JSONObject().put("access_token",syntheticAccess("initial"))
            .put("refresh_token","synthetic-available-refresh").toString()))
        ApiClient.verifyCode("+70000000017","0000","Synthetic").getOrThrow()
        assertEquals("/auth/verify",server.takeRequest(1,TimeUnit.SECONDS)!!.path)
        return requireNotNull(TaxiQuoteConsentApi.owner())
    }
    @Test fun compiledGateIsDefaultOffOrPinnedLoopbackOnly() {
        val owner=session()
        if(BuildConfig.YULDASH_TAXI_QUOTE_CONSENT_LOCAL) {
            assertTrue(BuildConfig.DEBUG);assertTrue(BuildConfig.YULDASH_NATIVE_QA_PINNED_API)
            assertNotNull(owner);assertEquals(TAXI_CONSENT_LOCAL_API,owner!!.binding.origin)
        } else { assertFalse(TaxiQuoteConsentApi.requested);assertNull(owner) }
    }
    @Test fun quoteAndConfirmSendOnlyDefinedJsonOnceAndPreserveActualExpiredOrder()=runBlocking {
        val owner=session()
        if(owner==null) {assertFalse(BuildConfig.YULDASH_TAXI_QUOTE_CONSENT_LOCAL);return@runBlocking}
        val server=MockWebServer();server.start(InetAddress.getByName("127.0.0.1"),8000)
        try {
            for(promo in listOf(15000,0)) {
                server.enqueue(MockResponse().setBody(ConsentFixture.quoteJson(promo).toString()))
            val route=ConsentFixture.route();val quote=TaxiQuoteConsentApi.quote(route,owner){true}.getOrThrow()
            val first=server.takeRequest(1,TimeUnit.SECONDS)!!;assertEquals("/instant/quotes/v1",first.path)
            val quoted=JSONObject(first.body.readUtf8());assertEquals("seat_1_4",quoted.getJSONArray("options").getString(0));assertFalse(quoted.has("scheduled_at"))
                server.enqueue(MockResponse().setBody(ConsentFixture.receiptJson("expired",promo=promo).toString()))
            val body=route.orderBody(TaxiConsentDetails("Synthetic",paymentMethod="cash"),quote)
            val receipt=TaxiQuoteConsentApi.confirm(body,quote,owner){true}.getOrThrow()
            val confirmed=server.takeRequest(1,TimeUnit.SECONDS)!!;assertEquals("/instant/orders/confirmed/v1",confirmed.path)
            assertEquals(body,confirmed.body.readUtf8());assertEquals(77,receipt.order.id);assertEquals("expired",receipt.order.status)
            assertEquals(60000-promo,receipt.order.passengerPriceKop)
            }
            assertEquals(4,server.requestCount)
        } finally {server.shutdown();ApiClient.testTimeoutMs=null}
    }
    @Test fun httpErrorsRedirectAndDisconnectNeverReplayUntilExplicitExactRetry()=runBlocking {
        val owner=session()
        if(owner==null) {assertFalse(BuildConfig.YULDASH_TAXI_QUOTE_CONSENT_LOCAL);return@runBlocking}
        val server=MockWebServer();server.start(InetAddress.getByName("127.0.0.1"),8000)
        try {
            val q=ConsentFixture.quote();val body=ConsentFixture.route().orderBody(TaxiConsentDetails(paymentMethod="cash"),q)
            for(code in listOf(401,307,503)) {
                server.enqueue(MockResponse().setResponseCode(code).setHeader("Location","/forbidden-follow-up").setBody("{}"))
                val before=server.requestCount
                assertTrue(TaxiQuoteConsentApi.confirm(body,q,owner){true}.isFailure)
                assertEquals(before+1,server.requestCount);assertEquals(body,server.takeRequest(1,TimeUnit.SECONDS)!!.body.readUtf8())
            }
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            assertTrue(TaxiQuoteConsentApi.confirm(body,q,owner){true}.isFailure)
            val lost=server.takeRequest(1,TimeUnit.SECONDS)!!.body.readUtf8()
            server.enqueue(MockResponse().setBody(ConsentFixture.receiptJson("done").toString()))
            val replay=TaxiQuoteConsentApi.confirm(body,q,owner){true}.getOrThrow()
            assertEquals(lost,server.takeRequest(1,TimeUnit.SECONDS)!!.body.readUtf8());assertEquals(77,replay.order.id)
            assertEquals(5,server.requestCount)
        } finally {server.shutdown();ApiClient.testTimeoutMs=null}
    }
    @Test fun changedSessionAndDeniedAdmissionSendNothing()=runBlocking {
        val owner=session()
        if(owner==null) {assertFalse(BuildConfig.YULDASH_TAXI_QUOTE_CONSENT_LOCAL);return@runBlocking}
        val server=MockWebServer();server.start(InetAddress.getByName("127.0.0.1"),8000)
        try {
            val q=ConsentFixture.quote();val body=ConsentFixture.route().orderBody(TaxiConsentDetails(),q)
            assertTrue(TaxiQuoteConsentApi.confirm(body,q,owner){false}.isFailure)
            session() // A new login by the same account rotates the durable scope and generation.
            assertFalse(TaxiQuoteConsentApi.current(owner));assertTrue(TaxiQuoteConsentApi.confirm(body,q,owner){true}.isFailure)
            assertEquals(0,server.requestCount)
        } finally {server.shutdown();ApiClient.testTimeoutMs=null}
    }
    @Test fun confirmed401WithUsableRefreshDoesNotRefreshOrRepost()=runBlocking {
        if(!BuildConfig.YULDASH_TAXI_QUOTE_CONSENT_LOCAL) {assertNull(session());return@runBlocking}
        val field=ApiClient::class.java.getDeclaredField("prefs").apply {isAccessible=true}
        val old=field.get(ApiClient);field.set(ApiClient,null)
        val server=MockWebServer();server.start(InetAddress.getByName("127.0.0.1"),8000)
        try {
            val owner=refreshSession(server);field.set(ApiClient,memoryPrefs())
            val q=ConsentFixture.quote();val body=ConsentFixture.route().orderBody(TaxiConsentDetails(),q)
            server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
            val failure=TaxiQuoteConsentApi.confirm(body,q,owner){true}.exceptionOrNull()
            assertEquals(401,(failure as ApiException).status)
            val only=server.takeRequest(1,TimeUnit.SECONDS)!!
            assertEquals("/instant/orders/confirmed/v1",only.path);assertEquals(body,only.body.readUtf8())
            assertEquals("No implicit refresh or second confirmation",2,server.requestCount)
            assertTrue(TaxiQuoteConsentApi.current(owner))
            // Positive control: same session CAN actually refresh and persist its token pair.
            server.enqueue(MockResponse().setBody(JSONObject().put("access_token",syntheticAccess("renewed"))
                .put("refresh_token","synthetic-renewed-refresh").toString()))
            ApiClient.tryRefresh(ApiClient.currentToken(),owner.generation).getOrThrow()
            assertEquals("/auth/refresh",server.takeRequest(1,TimeUnit.SECONDS)!!.path)
            assertEquals(3,server.requestCount);assertTrue(TaxiQuoteConsentApi.current(owner))
            println("SYNTHETIC_HTTP_401 confirmed_posts=1 automatic_refresh_posts=0 automatic_reposts=0 positive_control_refresh_posts=1")
        } finally {field.set(ApiClient,old);server.shutdown();ApiClient.testTimeoutMs=null}
    }
    @Test fun unknownCommitThen401KeepsOriginalBodyPairAndMarkerUntilExplicitReceipt()=runBlocking {
        if(!BuildConfig.YULDASH_TAXI_QUOTE_CONSENT_LOCAL) {assertNull(session());return@runBlocking}
        val field=ApiClient::class.java.getDeclaredField("prefs").apply {isAccessible=true}
        val old=field.get(ApiClient);field.set(ApiClient,null)
        val server=MockWebServer();server.start(InetAddress.getByName("127.0.0.1"),8000)
        try {
            val owner=refreshSession(server);field.set(ApiClient,memoryPrefs())
            val confirmed=AtomicInteger();val refreshes=AtomicInteger();val quotes=AtomicInteger();val bodies=CopyOnWriteArrayList<String>()
            server.dispatcher=object:Dispatcher() {
                override fun dispatch(request:RecordedRequest):MockResponse = when(request.path) {
                    "/instant/quotes/v1" -> {quotes.incrementAndGet();MockResponse().setBody(ConsentFixture.quoteJson().toString())}
                    "/instant/orders/confirmed/v1" -> {
                        bodies+=request.body.clone().readUtf8()
                        when(confirmed.incrementAndGet()) {
                            1 -> MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) // Synthetic row committed, response lost.
                            2 -> MockResponse().setResponseCode(401).setBody("{}")
                            else -> MockResponse().setBody(ConsentFixture.receiptJson("done").toString()) // Existing row77, no new create.
                        }
                    }
                    "/auth/refresh" -> {refreshes.incrementAndGet();MockResponse().setBody(JSONObject().put("access_token",syntheticAccess("unexpected"))
                        .put("refresh_token","synthetic-refresh").toString())}
                    else -> MockResponse().setResponseCode(500)
                }
            }
            var disk:String?=null;val markers=TaxiConsentMarkerJournal({disk},{disk=it;true})
            var time=TaxiConsentTime(ConsentFixture.wall,10000)
            val c=TaxiConsentController(owner,TaxiQuoteConsentApi,markers){time}
            c.refresh(ConsentFixture.route());c.primary();c.confirm(TaxiConsentDetails("Original"))
            assertTrue(c.state.value.unknown);assertEquals(1,confirmed.get());assertNotNull(disk)
            time=TaxiConsentTime(ConsentFixture.wall+999999,999999);c.checkTime()
            c.primary(ConsentFixture.route(listOf("pets")));c.confirm(TaxiConsentDetails("Changed"))
            assertEquals(2,confirmed.get());assertEquals(0,refreshes.get());assertEquals(1,quotes.get())
            assertTrue(c.state.value.unknown);assertNotNull(disk);assertTrue(TaxiQuoteConsentApi.current(owner))
            c.primary();c.confirm(TaxiConsentDetails("Changed again"))
            assertEquals(3,confirmed.get());assertEquals(0,refreshes.get());assertEquals(1,quotes.get())
            assertEquals(1,bodies.distinct().size);assertEquals(77,c.state.value.receipt!!.order.id);assertEquals("done",c.state.value.receipt!!.order.status);assertNull(disk)
            println("SYNTHETIC_UNKNOWN_401 explicit_confirmed_posts=3 automatic_refresh_posts=0 automatic_reposts=0 quote_posts=1 unique_bodies=1 returned_id=77")
        } finally {field.set(ApiClient,old);server.shutdown();ApiClient.testTimeoutMs=null}
    }

}
