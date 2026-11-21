package com.yuldash.app

import com.yuldash.app.data.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.UUID

/** Real production state machine; only clock, IO boundary and opaque marker storage are synthetic. */
class TaxiConsentControllerTest {
    private class H {
        val owner=TaxiConsentOwner(7,17,TaxiConsentBinding(ConsentFixture.SCOPE,TAXI_CONSENT_LOCAL_API))
        var current=true;var disk:String?=null;var writable=true
        var time=TaxiConsentTime(ConsentFixture.wall,10000)
        var quotes=0;val bodies=mutableListOf<String>();var creations=0
        val journal=TaxiConsentMarkerJournal({disk},{if(writable){disk=it;true}else false})
        var read:suspend(TaxiConsentRoute)->Result<TaxiConsentQuote> = {Result.success(ConsentFixture.quote())}
        var send:suspend(String)->Result<TaxiConsentReceipt> = {creations++;Result.success(ConsentFixture.receipt())}
        val port=object:TaxiConsentPort {
            override fun current(owner:TaxiConsentOwner)=current&&owner==this@H.owner
            override fun publish(owner:TaxiConsentOwner,action:()->Unit):Boolean {if(!current(owner))return false;action();return true}
            override suspend fun quote(route:TaxiConsentRoute,owner:TaxiConsentOwner,admit:()->Boolean):Result<TaxiConsentQuote>{check(admit());quotes++;return read(route)}
            override suspend fun confirm(body:String,original:TaxiConsentQuote,owner:TaxiConsentOwner,admit:()->Boolean):Result<TaxiConsentReceipt>{check(admit());bodies+=body;return send(body)}
        }
        fun controller()=TaxiConsentController(owner,port,journal){time}
        suspend fun ready(c:TaxiConsentController) {c.refresh(ConsentFixture.route());c.primary();assertTrue(c.state.value.review)}
    }
    @Test fun positiveAndControlRequireSeparateExplicitConfirmation()=runBlocking {
        for(promo in listOf(0,15000)) {
            val h=H();h.read={Result.success(ConsentFixture.quote(promo))};val c=h.controller()
            h.send={h.creations++;Result.success(ConsentFixture.receipt(promo=promo))}
            h.ready(c);assertEquals(60000-promo,c.state.value.quote!!.payableKop);assertEquals(0,h.bodies.size)
            c.confirm(TaxiConsentDetails());assertEquals(1,h.bodies.size);assertEquals(77,c.state.value.receipt!!.order.id);assertEquals(60000-promo,c.state.value.receipt!!.order.passengerPriceKop);assertNull(h.disk)
            c.confirm(TaxiConsentDetails());c.primary();assertEquals(1,h.bodies.size)
        }
    }
    @Test fun expiryRefreshOnlyThenNewExplicitAction()=runBlocking {
        val h=H();val c=h.controller();h.ready(c);h.time=h.time.copy(elapsed=130124)
        c.checkTime();assertTrue(c.state.value.expired)
        h.time=TaxiConsentTime(ConsentFixture.wall+120124,130124)
        h.read={Result.success(ConsentFixture.quote(0).copy(deadline=h.time.wall+120000,expiresAt="2026-11-21T12:04:00.124Z"))}
        c.confirm(TaxiConsentDetails());assertEquals(2,h.quotes);assertEquals(0,h.bodies.size);assertTrue(c.state.value.updated)
        assertEquals(60000,c.state.value.quote!!.payableKop)
        c.confirm(TaxiConsentDetails());assertEquals(1,h.bodies.size)
    }
    @Test fun slowQuoteSleepAndClockRollbackCannotExtendLock()=runBlocking {
        val h=H();val c=h.controller();h.read={h.time=h.time.copy(elapsed=130124);Result.success(ConsentFixture.quote())}
        c.refresh(ConsentFixture.route());assertTrue(c.state.value.expired)
        h.time=TaxiConsentTime(ConsentFixture.wall,10000);c.checkTime();assertTrue(c.state.value.expired)
        val h2=H();val c2=h2.controller();h2.ready(c2);h2.time=h2.time.copy(wall=h2.time.wall-1);c2.checkTime();assertTrue(c2.state.value.expired)
    }
    @Test fun changedOptionsBeforeEffectCannotConfirmOldQuote()=runBlocking {
        val h=H();val c=h.controller();h.ready(c)
        val changed=ConsentFixture.route(listOf("big_luggage"));c.confirm(TaxiConsentDetails(),changed)
        assertEquals(0,h.bodies.size);assertEquals(2,h.quotes);assertEquals(changed,c.state.value.quotedFor)
    }
    @Test fun staleQuoteFromDifferentInputAndAccountIgnored()=runBlocking {
        val h=H();val c=h.controller();val first=CompletableDeferred<Result<TaxiConsentQuote>>()
        h.read={first.await()};val a=launch(start=CoroutineStart.UNDISPATCHED){c.refresh(ConsentFixture.route())}
        val changed=ConsentFixture.route(listOf("big_luggage"));h.read={Result.success(ConsentFixture.quote(0))};c.refresh(changed)
        first.complete(Result.success(ConsentFixture.quote()));a.join();assertEquals(changed,c.state.value.quotedFor);assertEquals(60000,c.state.value.quote!!.payableKop)
        val second=CompletableDeferred<Result<TaxiConsentQuote>>();h.read={second.await()}
        val b=launch(start=CoroutineStart.UNDISPATCHED){c.refresh(ConsentFixture.route())};h.current=false
        second.complete(Result.success(ConsentFixture.quote()));b.join();c.checkTime();assertNull(c.state.value.quote);assertTrue(c.state.value.blocked)
    }
    @Test fun validCommittedReceiptSurvivesGpsInputDriftAndDoubleTap()=runBlocking {
        val h=H();val c=h.controller();h.ready(c);val reply=CompletableDeferred<Result<TaxiConsentReceipt>>();h.send={reply.await()}
        val job=launch(start=CoroutineStart.UNDISPATCHED){c.confirm(TaxiConsentDetails("Original"))}
        c.confirm(TaxiConsentDetails("Double tap"));c.refresh(ConsentFixture.route(listOf("big_luggage")))
        reply.complete(Result.success(ConsentFixture.receipt("expired")));job.join()
        assertEquals(1,h.bodies.size);assertEquals("expired",c.state.value.receipt!!.order.status);assertNull(h.disk)
    }
    @Test fun unknownOutcomeBeforeTtlReplaysSameBytesWithChangedFormAndTerminalReceipt()=runBlocking {
        val h=H();val c=h.controller();h.ready(c)
        h.send={h.creations++;Result.failure(IOException("Synthetic lost response after commit"))}
        c.confirm(TaxiConsentDetails("Original",forName="Synthetic recipient",paymentMethod="cash"))
        val original=h.bodies.single();assertTrue(c.state.value.unknown);assertNotNull(h.disk)
        h.time=TaxiConsentTime(ConsentFixture.wall+9999999,9999999);c.checkTime()
        c.refresh(ConsentFixture.route(listOf("big_luggage")));c.primary(null)
        assertEquals(1,h.quotes);assertTrue(c.state.value.review)
        h.send={Result.success(ConsentFixture.receipt("done"))};c.confirm(TaxiConsentDetails("Changed",paymentMethod="sbp"),null)
        assertEquals(listOf(original,original),h.bodies);assertEquals(1,h.creations);assertEquals(77,c.state.value.receipt!!.order.id);assertNull(h.disk)
    }
    @Test fun unknownErrorsIncluding404AndConflictNeverProveAbsence()=runBlocking {
        for(error in listOf(IOException("offline"),ApiException(401,"unauthorized"),ApiException(404,"missing"),ApiException(409,"conflict","taxi_consent_key_conflict"),ApiException(409,"refresh","taxi_quote_refresh_required"))) {
            val h=H();val c=h.controller();h.ready(c);h.send={Result.failure(IOException("unknown"))};c.confirm(TaxiConsentDetails())
            h.send={Result.failure(error)};c.primary();c.confirm(TaxiConsentDetails());assertTrue(c.state.value.unknown);assertNotNull(h.disk);assertNull(c.state.value.receipt)
        }
    }
    @Test fun knownFirstDenialReleasesOnlyOwnMarkerAndRequiresNewQuote()=runBlocking {
        val h=H();val c=h.controller();h.ready(c);h.send={Result.failure(ApiException(409,"Refresh","taxi_quote_refresh_required"))}
        c.confirm(TaxiConsentDetails());assertNull(h.disk);assertFalse(c.state.value.unknown);assertNull(c.state.value.quote);assertEquals(TaxiConsentProblem.REFRESH,c.state.value.problem)
    }
    @Test fun pendingQuoteDoesNotCreateOrClearPreviousState()=runBlocking {
        val h=H();val c=h.controller();h.read={Result.failure(ApiException(409,"pending","taxi_quote_price_pending"))}
        c.refresh(ConsentFixture.route());c.primary();c.confirm(TaxiConsentDetails())
        assertEquals(TaxiConsentProblem.PRICE_PENDING,c.state.value.problem);assertEquals(0,h.bodies.size);assertNull(h.disk)
    }
    @Test fun closeRestartAndSameAccountNewLoginNeverReconstructPost()=runBlocking {
        val h=H();val c=h.controller();h.ready(c);h.send={Result.failure(IOException("unknown"))};c.confirm(TaxiConsentDetails("Private RAM only"));val stored=h.disk!!
        val row=JSONArray(stored).getJSONObject(0);assertEquals(setOf("v","slot","scope","origin"),row.keys().asSequence().toSet())
        assertFalse(stored.contains("Private"));assertFalse(stored.contains("quote_id"));assertFalse(stored.contains(ConsentFixture.KEY))
        c.close();val restored=h.controller();restored.primary();restored.confirm(TaxiConsentDetails());assertTrue(restored.state.value.blocked);assertEquals(1,h.bodies.size)
        val other=TaxiConsentBinding(UUID.randomUUID().toString(),TAXI_CONSENT_LOCAL_API)
        assertFalse(h.journal.pending(other));assertEquals(stored,h.disk)
    }
    @Test fun ownerChangeAndDisposalSuppressLateUiButOnlyOwnValidReceiptMayClearMarker()=runBlocking {
        for(changeAccount in listOf(false,true)) {
            val h=H();val c=h.controller();h.ready(c);val reply=CompletableDeferred<Result<TaxiConsentReceipt>>();h.send={reply.await()}
            val job=launch(start=CoroutineStart.UNDISPATCHED){c.confirm(TaxiConsentDetails())};c.close();if(changeAccount)h.current=false
            reply.complete(Result.success(ConsentFixture.receipt()));job.join();assertNull(c.state.value.receipt)
            if(changeAccount)assertNotNull(h.disk) else assertNull(h.disk)
        }
    }
    @Test fun storageFailureNeverSendsAndMarkerCleanupFailureDoesNotHideReceipt()=runBlocking {
        val h=H();val c=h.controller();h.ready(c);h.writable=false;c.confirm(TaxiConsentDetails());assertEquals(0,h.bodies.size);assertTrue(c.state.value.blocked)
        val h2=H();val c2=h2.controller();h2.ready(c2);h2.send={h2.writable=false;Result.success(ConsentFixture.receipt())}
        c2.confirm(TaxiConsentDetails());assertEquals(77,c2.state.value.receipt!!.order.id);assertNotNull(h2.disk)
    }
    @Test fun twoLiveViewsShareBarrierButCannotEraseEachOthersOpaqueMarker()=runBlocking {
        val h=H();val a=h.controller();val b=h.controller();h.ready(a);h.ready(b);h.send={Result.failure(IOException("unknown"))}
        a.confirm(TaxiConsentDetails());b.confirm(TaxiConsentDetails());assertEquals(1,h.bodies.size);assertTrue(b.state.value.blocked)
        val marker=h.disk;val foreign=TaxiConsentBinding(UUID.randomUUID().toString(),TAXI_CONSENT_LOCAL_API)
        assertFalse(h.journal.pending(foreign));assertEquals(marker,h.disk)
    }
}
