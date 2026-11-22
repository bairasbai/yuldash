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
        val recoveryCalls = mutableListOf<Pair<String, Boolean>>()
        val cardReads = mutableListOf<Int>()
        var lookup: suspend (Boolean) -> Result<TaxiAttemptResult> = { Result.success(TaxiAttemptResult.LookupUnknown) }
        var fetch: suspend (Int) -> Result<InstantOrderDto> = { Result.success(ConsentFixture.receipt("done").order) }
        val port=object:TaxiConsentPort {
            override fun current(owner:TaxiConsentOwner)=current&&owner==this@H.owner
            override fun publish(owner:TaxiConsentOwner,action:()->Unit):Boolean {if(!current(owner))return false;action();return true}
            override suspend fun quote(route:TaxiConsentRoute,owner:TaxiConsentOwner,admit:()->Boolean):Result<TaxiConsentQuote>{check(admit());quotes++;return read(route)}
            override suspend fun confirm(body:String,original:TaxiConsentQuote,owner:TaxiConsentOwner,admit:()->Boolean):Result<TaxiConsentReceipt>{check(admit());bodies+=body;return send(body)}
            override suspend fun result(key: String, close: Boolean, owner: TaxiConsentOwner, admit: () -> Boolean): Result<TaxiAttemptResult> {
                check(admit())
                recoveryCalls += key to close
                return lookup(close)
            }
            override suspend fun card(orderId: Int, owner: TaxiConsentOwner, admit: () -> Boolean): Result<InstantOrderDto> {
                check(admit())
                cardReads += orderId
                return fetch(orderId)
            }
        }
        suspend fun loseResponse(c: TaxiConsentController) {
            ready(c)
            send = { Result.failure(IOException("lost")) }
            c.confirm(TaxiConsentDetails("Private RAM only"))
        }
        fun controller()=TaxiConsentController(owner,port,journal){time}
        suspend fun ready(c:TaxiConsentController) {c.refresh(ConsentFixture.route());c.primary();assertTrue(c.state.value.review)}
    }
    @Test fun positiveAndControlRequireSeparateExplicitConfirmation()=runBlocking {
        for(promo in listOf(0,15000)) {
            val h=H();h.read={Result.success(ConsentFixture.quote(promo))};val c=h.controller()
            h.send={h.creations++;Result.success(ConsentFixture.receipt(promo=promo))}
            h.ready(c);assertEquals(60000-promo,c.state.value.quote!!.payableKop);assertEquals(0,h.bodies.size)
            c.confirm(TaxiConsentDetails());assertEquals(1,h.bodies.size);assertEquals(77,c.state.value.receipt!!.order.id);assertEquals(60000-promo,c.state.value.receipt!!.order.passengerPriceKop);assertTrue(c.deliverOrder({true}) {});assertNull(h.disk)
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
        assertEquals(1,h.bodies.size);assertEquals("expired",c.state.value.receipt!!.order.status);assertTrue(c.deliverOrder({true}) {});assertNull(h.disk)
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
        assertEquals(listOf(original,original),h.bodies);assertEquals(1,h.creations);assertEquals(77,c.state.value.receipt!!.order.id);assertTrue(c.deliverOrder({true}) {});assertNull(h.disk)
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
        val row=JSONArray(stored).getJSONObject(0);assertEquals(setOf("v","request_key","close_requested","scope","origin"),row.keys().asSequence().toSet())
        assertFalse(stored.contains("Private"));assertFalse(stored.contains("quote_id"));assertEquals(ConsentFixture.KEY,row.getString("request_key"))
        c.close();val restored=h.controller();restored.primary();restored.confirm(TaxiConsentDetails());assertTrue(restored.state.value.blocked);assertEquals(1,h.bodies.size)
        val other=TaxiConsentBinding(UUID.randomUUID().toString(),TAXI_CONSENT_LOCAL_API)
        assertFalse(h.journal.pending(other));assertEquals(stored,h.disk)
    }
    @Test fun ownerChangeAndDisposalLeaveMarkerForNextLiveView()=runBlocking {
        for(changeAccount in listOf(false,true)) {
            val h=H();val c=h.controller();h.ready(c);val reply=CompletableDeferred<Result<TaxiConsentReceipt>>();h.send={reply.await()}
            val job=launch(start=CoroutineStart.UNDISPATCHED){c.confirm(TaxiConsentDetails())};c.close();if(changeAccount)h.current=false
            reply.complete(Result.success(ConsentFixture.receipt()));job.join();assertNull(c.state.value.receipt)
            assertNotNull(h.disk)
        }
    }
    @Test fun storageFailureNeverSendsAndMarkerCleanupFailureDoesNotHideReceipt()=runBlocking {
        val h=H();val c=h.controller();h.ready(c);h.writable=false;c.confirm(TaxiConsentDetails());assertEquals(0,h.bodies.size);assertTrue(c.state.value.blocked)
        val h2=H();val c2=h2.controller();h2.ready(c2);h2.send={h2.writable=false;Result.success(ConsentFixture.receipt())}
        c2.confirm(TaxiConsentDetails());assertEquals(77,c2.state.value.receipt!!.order.id);assertTrue(c2.deliverOrder({true}) {});assertNotNull(h2.disk)
    }
    @Test fun twoLiveViewsShareBarrierButCannotEraseEachOthersOpaqueMarker()=runBlocking {
        val h=H();val a=h.controller();val b=h.controller();h.ready(a);h.ready(b);h.send={Result.failure(IOException("unknown"))}
        a.confirm(TaxiConsentDetails());b.confirm(TaxiConsentDetails());assertEquals(1,h.bodies.size);assertTrue(b.state.value.blocked)
        val marker=h.disk;val foreign=TaxiConsentBinding(UUID.randomUUID().toString(),TAXI_CONSENT_LOCAL_API)
        assertFalse(h.journal.pending(foreign));assertEquals(marker,h.disk)
    }
    @Test fun restartUsesExplicitLookupAndCurrentCardEvenWhenTerminalPriceChanged() = runBlocking {
        val h = H()
        val first = h.controller()
        h.loseResponse(first)
        first.close()
        val c = h.controller()
        c.checkTime()
        c.routeChanged(ConsentFixture.route())
        c.primary()
        assertTrue(c.state.value.recoverable)
        assertFalse(c.state.value.canReplay)
        assertTrue(h.recoveryCalls.isEmpty())
        c.checkResult()
        assertEquals(TaxiConsentRecovery.LOOKUP_UNKNOWN, c.state.value.recovery)
        assertTrue(c.state.value.blocked)
        h.lookup = { Result.success(TaxiAttemptResult.Committed(77, 45000)) }
        h.fetch = { Result.success(ConsentFixture.receipt("done", promo = 0).order) }
        c.checkResult()
        assertEquals(listOf(ConsentFixture.KEY to false, ConsentFixture.KEY to false), h.recoveryCalls)
        assertEquals(listOf(77), h.cardReads)
        assertEquals(60000, c.state.value.recoveredOrder!!.passengerPriceKop)
        assertEquals("done", c.state.value.recoveredOrder!!.status)
        assertNotNull(h.disk)
        assertFalse(c.deliverOrder({ false }) { fail("not visible") })
        assertTrue(c.deliverOrder({ true }) { assertEquals(77, it.id) })
        assertFalse(c.deliverOrder({ true }) { fail("duplicate delivery") })
        assertNull(h.disk)
        assertEquals(1, h.bodies.size)
    }

    @Test fun closeIntentFencesAllViewsBeforeWireAndAfterLoss() = runBlocking {
        val h = H()
        val original = h.controller()
        h.loseResponse(original)
        val other = h.controller()
        val reply = CompletableDeferred<Result<TaxiAttemptResult>>()
        h.lookup = { reply.await() }
        val close = launch(start = CoroutineStart.UNDISPATCHED) { other.closeAttempt() }
        assertTrue(JSONArray(h.disk).getJSONObject(0).getBoolean("close_requested"))
        original.primary()
        original.confirm(TaxiConsentDetails())
        assertFalse(original.state.value.canReplay)
        assertEquals(1, h.bodies.size)
        other.closeAttempt()
        other.checkResult()
        assertEquals(listOf(ConsentFixture.KEY to true), h.recoveryCalls)
        reply.complete(Result.failure(IOException("lost close response")))
        close.join()
        assertTrue(other.state.value.blocked)
        assertEquals(TaxiConsentRecovery.UNAVAILABLE, other.state.value.recovery)
        other.close()
        val restored = h.controller()
        h.lookup = { Result.success(TaxiAttemptResult.Closed) }
        restored.closeAttempt()
        assertNull(h.disk)
        restored.routeChanged(ConsentFixture.route())
        restored.checkTime()
        assertEquals(TaxiConsentRecovery.CLOSED, restored.state.value.recovery)
        assertEquals(1, h.quotes)
        restored.primary()
        assertEquals(2, h.quotes)
        assertTrue(restored.state.value.review)
        assertEquals(1, h.bodies.size)
    }

    @Test fun retiredUnverifiedAndFailedCardPreserveBarrier() = runBlocking {
        for ((reply, report) in listOf(TaxiAttemptResult.Retired to TaxiConsentRecovery.RETIRED,
            TaxiAttemptResult.Unverified to TaxiConsentRecovery.UNVERIFIED)) {
            val h = H()
            val c = h.controller()
            h.loseResponse(c)
            h.lookup = { Result.success(reply) }
            c.closeAttempt()
            assertEquals(report, c.state.value.recovery)
            h.lookup = { Result.success(TaxiAttemptResult.LookupUnknown) }
            c.checkResult()
            assertEquals(report, c.state.value.recovery)
            assertTrue(c.state.value.blocked)
            assertNotNull(h.disk)
        }
        val h = H()
        val c = h.controller()
        h.loseResponse(c)
        h.lookup = { Result.success(TaxiAttemptResult.Committed(77, 45000)) }
        h.fetch = { Result.failure(ApiException(404, "deleted")) }
        c.checkResult()
        assertEquals(TaxiConsentRecovery.CARD_UNAVAILABLE, c.state.value.recovery)
        assertNull(c.state.value.recoveredOrder)
        assertNotNull(h.disk)
        assertFalse(c.state.value.canReplay)
    }

    @Test fun v1MalformedReplacedOrForeignMarkersDoNotAuthorizeLookupOrClose() = runBlocking {
        val h = H()
        val row = org.json.JSONObject().put("v", 1).put("slot", ConsentFixture.ID)
            .put("scope", h.owner.binding.scope).put("origin", TAXI_CONSENT_LOCAL_API)
        for (value in listOf(JSONArray().put(row).toString(), "broken", "[]", "[]garbage", JSONArray().put(row).toString() + "trailing")) {
            h.disk = value
            val c = h.controller()
            c.primary(ConsentFixture.route())
            c.checkResult()
            c.closeAttempt()
            assertTrue(c.state.value.blocked)
            assertFalse(c.state.value.recoverable)
            assertEquals(value, h.disk)
        }
        h.disk = null
        val c = h.controller()
        h.loseResponse(c)
        val original = h.disk!!
        h.disk = original.replace(ConsentFixture.KEY, UUID.randomUUID().toString())
        val replacement = h.disk
        c.closeAttempt()
        c.primary()
        c.confirm(TaxiConsentDetails())
        assertEquals(replacement, h.disk)
        assertTrue(h.recoveryCalls.isEmpty())
        assertEquals(1, h.bodies.size)
        h.disk = null
        c.checkTime()
        assertTrue(c.state.value.recoverable)
        assertFalse(c.state.value.canReplay)
        c.checkResult()
        assertEquals(listOf(ConsentFixture.KEY to false), h.recoveryCalls)
        assertTrue(JSONArray(h.disk).getJSONObject(0).getBoolean("close_requested"))
    }

    @Test fun cancelOrOwnerChangeDuringLookupCannotClearOrNavigate() = runBlocking {
        for (logout in listOf(false, true)) {
            val h = H()
            val c = h.controller()
            h.loseResponse(c)
            val reply = CompletableDeferred<Result<TaxiAttemptResult>>()
            h.lookup = { reply.await() }
            val job = launch(start = CoroutineStart.UNDISPATCHED) { c.checkResult() }
            if (logout) h.current = false else job.cancel()
            reply.complete(Result.success(TaxiAttemptResult.Committed(77,45000)))
            job.join()
            assertTrue(h.cardReads.isEmpty())
            assertNull(c.state.value.recoveredOrder)
            assertNotNull(h.disk)
            if (logout) assertFalse(c.deliverOrder({true}) { fail("logged out") })
            else assertFalse(c.state.value.recovering)
        }
    }

    @Test fun storageFailureBeforeCloseNeverTransmitsOrUnblocks() = runBlocking {
        val h = H()
        val c = h.controller()
        h.loseResponse(c)
        val bytes = h.disk
        h.writable = false
        c.closeAttempt()
        assertTrue(c.state.value.blocked)
        assertTrue(h.recoveryCalls.isEmpty())
        assertEquals(bytes, h.disk)
    }
}
