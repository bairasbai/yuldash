package com.yuldash.app

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yuldash.app.data.ApiClient
import com.yuldash.app.ui.theme.YuldashTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Actual outer caller, ApiClient cache and client Jobs; all server data are synthetic. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InstantContactsLoadBoundaryTest : ShareHelpBoundaryHarness() {
    private enum class Bad { WRAPPER, ROW, DUPLICATE, FIELD }
    private enum class Op { CONTACTS, SHARES, REVOKE }
    private data class Attempt(val op: Op, val generation: Long, val job: Job,
        val returned: AtomicBoolean = AtomicBoolean(false), val completed: CountDownLatch = CountDownLatch(1))
    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private val attempts = CopyOnWriteArrayList<Attempt>()
    private val frames = CopyOnWriteArrayList<Pair<TaxiTripPublication?, Boolean>>()
    private val freshStarted = CountDownLatch(1)
    private val freshRelease = CountDownLatch(1)
    @Volatile private var bad: Bad? = Bad.WRAPPER
    @Volatile private var holdFresh = false
    @Volatile private var contactName = "Контакт А"
    private var language = AppLanguage.Ru
    private lateinit var publication: TaxiTripPublication
    private val safety get() = if (language == AppLanguage.Ru) "Безопасность" else "Именлек"
    private val share get() = if (language == AppLanguage.Ru) "Поделиться поездкой" else "Сәфәр менән бүлешеү"
    private val error get() = if (language == AppLanguage.Ru) "Не удалось загрузить близких" else "Яҡындарҙы йөкләп булманы"
    private val retry get() = if (language == AppLanguage.Ru) "Повторить" else "Ҡабатлау"
    private val loading get() = if (language == AppLanguage.Ru) "Загружаем близких…" else "Яҡындарҙы йөкләйбеҙ…"
    private val active get() = if (language == AppLanguage.Ru) "Активные ссылки" else "Әүҙем һылтанмалар"
    private val revoke get() = if (language == AppLanguage.Ru) "Отозвать" else "Кире алыу"
    private val close get() = if (language == AppLanguage.Ru) "Закрыть" else "Ябыу"
    private val copy get() = if (language == AppLanguage.Ru) "Скопировать" else "Күсереү"
    private val send get() = if (language == AppLanguage.Ru) "Отправить" else "Ебәреү"
    private fun response(body: String, code: Int = 200) = MockResponse().setHeader("Content-Type", "application/json").setResponseCode(code).setBody(body)
    private fun row(orderId: Int = 91) = """{"id":601,"order_id":$orderId,"contact_id":7,"token":"initial-current-91"}"""
    override fun customResponse(request: RecordedRequest, path: String): MockResponse? = when {
        path.startsWith("/ws/instant/") -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.close(1008, "synthetic forbidden") }
        })
        path == "/instant/orders/mine" -> response("""{"items":[{"id":91,"status":"onboard","role":"passenger","driver_name":"Водитель А","driver_car":"Машина","driver_plate":"ТЕСТ","from_text":"Пункт А","to_text":"Пункт Б","price_estimate":250,"payment_method":"cash"}]}""")
        path == "/instant/availability" -> response("""{"enabled":true}""")
        path == "/trusted-contacts" -> {
            if (holdFresh) { freshStarted.countDown(); assertTrue(freshRelease.await(30, TimeUnit.SECONDS)) }
            response(when (bad) {
                Bad.WRAPPER -> """{"items":{}}"""
                Bad.ROW -> "[7]"
                Bad.DUPLICATE -> """[{"id":7,"name":"Old"},{"id":7,"name":"Other"}]"""
                Bad.FIELD -> """[{"id":7,"name":"Old","notify_by_default":"true"}]"""
                null -> """[{"id":7,"name":"$contactName","relation":"Синтетический","phone":"+70000000007"}]"""
            })
        }
        path == "/instant/orders/91/shares" -> response("[${row()}]")
        else -> null
    }
    private suspend fun <T> observe(op: Op, generation: Long, api: suspend () -> Result<T>): Result<T> {
        val a = Attempt(op, generation, currentCoroutineContext()[Job]!!)
        a.job.invokeOnCompletion { a.completed.countDown() }; attempts += a
        return try { api() } finally { a.returned.set(true) }
    }
    private val operations = InstantShareOperations(
        contacts = { generation -> observe(Op.CONTACTS, generation) { ApiClient.getContacts(expectedGeneration = generation) } },
        revoke = { id, shareId, generation ->
            assertEquals(91, id); assertEquals(601, shareId)
            observe(Op.REVOKE, generation) { ApiClient.revokeInstantShare(id, shareId, expectedGeneration = generation) }
        },
    )
    private fun visible(s: String) = compose.onAllNodesWithText(s).fetchSemanticsNodes().isNotEmpty()
    private fun completed(a: Attempt) {
        await { a.job.isCompleted && a.completed.count == 0L }; assertTrue(a.returned.get()); assertFalse(a.job.isCancelled); pump(300)
    }
    private fun shareAttempt(n: Int): Attempt {
        await { attempts.count { it.op == Op.SHARES } >= n }
        return attempts.filter { it.op == Op.SHARES }[n - 1]
    }
    private fun openShare() {
        val action = retain(hasContentDescription(safety)); compose.runOnIdle { dispatch(action) }; click(share)
    }
    private fun mount(lang: AppLanguage, open: Boolean = true) {
        language = lang; compose.mainClock.autoAdvance = false
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.setContent { if (mounted.value) CompositionLocalProvider(
            LocalAppLanguage provides language, LocalLifecycleOwner provides owner,
        ) {
            val trip = TaxiNavigationState.currentTrip()
            val owns = trip != null && TaxiNavigationState.isScreenOwner(trip.generation, trip.publisher)
            SideEffect { frames += trip to owns }
            YuldashTheme {
            InstantOrderScreen(onBack = {}, onLoginRequired = {}, embedded = true, renderNativeMap = false,
                shareOperations = operations, readShares = { id, generation ->
                    assertEquals(91, id)
                    observe(Op.SHARES, generation) { ApiClient.getInstantShares(id, expectedGeneration = generation) }
                })
        } } }
        await { TaxiNavigationState.currentTrip()?.orderId == 91 && TaxiNavigationState.tripOnScreen() }
        publication = TaxiNavigationState.currentTrip()!!
        assertTrue(TaxiNavigationState.isScreenOwner(publication.generation, publication.publisher))
        assertEquals(1, count("GET", "/orders/mine")); assertEquals(0, count("GET", "/orders/91"))
        pump(); compose.onNodeWithTag("taxiSheetDragArea").performTouchInput { swipe(center, center.copy(y = center.y - 160f), 300) }; pump()
        if (open) openShare()
    }
    private fun noSideEffects() {
        assertFalse(clipboard.hasPrimaryClip()); assertNull(Shadows.shadowOf(app).nextStartedActivity); assertNull(ShadowToast.getTextOfLatestToast())
        assertEquals(0, count("POST", "/orders/91/share")); assertEquals(0, count("DELETE", "/share/601"))
    }

    private fun counts(contacts: Int, shares: Int) {
        assertEquals(contacts, count("GET", "/trusted-contacts")); assertEquals(shares, count("GET", "/orders/91/shares"))
        assertEquals(contacts, attempts.count { it.op == Op.CONTACTS }); assertEquals(shares, attempts.count { it.op == Op.SHARES })
        assertTrue(attempts.all { it.generation == publication.generation })
        assertTrue(records.filter { it[1] == "/trusted-contacts" || it[1]!!.startsWith("/instant/orders/91/share") }.all { it[2] == "Bearer $tokenA" })
    }
    private fun failed(contacts: Int) {
        counts(contacts, 0); noSideEffects()
        assertTrue("Retired/invalid contacts must show error", visible(error)); assertTrue(visible(retry)); assertFalse(visible(loading))
        for (s in listOf("Контакт А", "Old", "Other", active, revoke, copy, send)) assertFalse(visible(s))
    }
    private fun recover(contactsBefore: Int) {
        val retainedRetry = text(retry); bad = null; holdFresh = true
        compose.runOnIdle { assertTrue(retainedRetry()); assertTrue(retainedRetry()); counts(contactsBefore, 0); noSideEffects() }
        await { freshStarted.count == 0L }
        val fresh = attempts.last { it.op == Op.CONTACTS }; assertFalse(fresh.job.isCompleted); assertFalse(fresh.returned.get())
        counts(contactsBefore + 1, 0); assertTrue(visible(loading)); assertFalse(visible(error))
        compose.runOnIdle { repeat(3) { dispatch(retainedRetry) } }; pump(300)
        counts(contactsBefore + 1, 0); noSideEffects(); assertFalse(fresh.job.isCompleted)
        freshRelease.countDown(); val shares = shareAttempt(1); assertSame(fresh.job, shares.job); completed(shares)
        counts(contactsBefore + 1, 1); noSideEffects(); assertTrue(visible("Контакт А")); assertTrue(visible(active)); assertTrue(visible(revoke))
        for (s in listOf(error, retry, loading, "Old", "Other", copy, send)) assertFalse(visible(s))
        compose.runOnIdle { dispatch(retainedRetry) }; pump(300); counts(contactsBefore + 1, 1); noSideEffects()
        assertSame(publication, TaxiNavigationState.currentTrip()); assertTrue(TaxiNavigationState.isScreenOwner(publication.generation, publication.publisher))
    }
    private fun malformed(b: Bad, lang: AppLanguage) {
        bad = b; mount(lang); await { attempts.any { it.op == Op.CONTACTS } }
        val old = attempts.last { it.op == Op.CONTACTS }; completed(old); failed(1); recover(1)
        assertNotSame(old.job, attempts.last { it.op == Op.CONTACTS }.job)
    }
    private fun beforeFirstRequest(lang: AppLanguage) {
        bad = null; mount(lang, open = false)
        val safetyAction = retain(hasContentDescription(safety)); compose.runOnIdle { dispatch(safetyAction) }
        val shareAction = text(share)
        compose.runOnIdle {
            assertTrue(shareAction()); assertTrue(TaxiNavigationState.claimScreen(publication.generation, Any()))
            counts(0, 0); noSideEffects()
        }
        // Lease is lost before the dialog's first composition/effect; not an unapplied-frame race.
        await { frames.lastOrNull()?.let { it.first === publication && !it.second } == true && visible(close) }
        pump(500); assertSame(publication, TaxiNavigationState.currentTrip()); counts(0, 0)
        val lostFrame = frames.indexOfLast { it.first === publication && !it.second }; assertTrue(lostFrame >= 0)
        compose.runOnIdle { assertTrue(TaxiNavigationState.claimScreen(publication.generation, publication.publisher)) }
        await { frames.drop(lostFrame + 1).any { it.first === publication && it.second } }
        pump(500); failed(0); recover(0)
    }
    @After fun releaseFresh() { freshRelease.countDown() }
    @Test fun lostLeaseBeforeFirstContactsRequestRu() = beforeFirstRequest(AppLanguage.Ru)
    @Test fun lostLeaseBeforeFirstContactsRequestBa() = beforeFirstRequest(AppLanguage.Ba)
    @Test fun wrongWrapperContactsRecoverRu() = malformed(Bad.WRAPPER, AppLanguage.Ru)
    @Test fun wrongWrapperContactsRecoverBa() = malformed(Bad.WRAPPER, AppLanguage.Ba)
    @Test fun scalarContactsRowRecoversRu() = malformed(Bad.ROW, AppLanguage.Ru)
    @Test fun scalarContactsRowRecoversBa() = malformed(Bad.ROW, AppLanguage.Ba)
    @Test fun duplicateContactsRecoverRu() = malformed(Bad.DUPLICATE, AppLanguage.Ru)
    @Test fun duplicateContactsRecoverBa() = malformed(Bad.DUPLICATE, AppLanguage.Ba)
    @Test fun wrongContactsFieldRecoversRu() = malformed(Bad.FIELD, AppLanguage.Ru)
    @Test fun wrongContactsFieldRecoversBa() = malformed(Bad.FIELD, AppLanguage.Ba)
}
