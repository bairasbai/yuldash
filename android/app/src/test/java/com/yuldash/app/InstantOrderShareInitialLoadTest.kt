package com.yuldash.app

import android.app.Application
import com.yuldash.app.data.TestContacts
import androidx.compose.runtime.CompositionLocalProvider
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
class InstantOrderShareInitialLoadTest : ShareHelpBoundaryHarness() {
    private enum class Bad { HTTP, WRAPPER, WRONG_ORDER, DUPLICATE }
    private enum class Op { CONTACTS, SHARES, REVOKE }
    private data class Attempt(val op: Op, val generation: Long, val job: Job,
        val returned: AtomicBoolean = AtomicBoolean(false), val completed: CountDownLatch = CountDownLatch(1))
    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private val attempts = CopyOnWriteArrayList<Attempt>()
    private val freshStarted = CountDownLatch(1)
    private val freshRelease = CountDownLatch(1)
    @Volatile private var bad: Bad? = Bad.HTTP
    @Volatile private var holdFresh = false
    @Volatile private var revoked = false
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
        path == "/trusted-contacts" -> response("""{"items":[{"id":7,"name":"$contactName","relation":"Синтетический","phone":"+70000000007"}]}""")
        path == "/instant/orders/91/shares" -> {
            if (holdFresh) { freshStarted.countDown(); assertTrue(freshRelease.await(30, TimeUnit.SECONDS)) }
            when (bad) {
                Bad.HTTP -> response("{}", 503)
                Bad.WRAPPER -> response("""{"items":{}}""")
                Bad.WRONG_ORDER -> response("[${row(92)}]")
                Bad.DUPLICATE -> response("[${row()},${row()}]")
                null -> response(if (revoked) "[]" else "[${row()}]")
            }
        }
        path == "/instant/orders/91/share/601" && request.method == "DELETE" -> { revoked = true; response("{}") }
        else -> null
    }
    private suspend fun <T> observe(op: Op, generation: Long, api: suspend () -> Result<T>): Result<T> {
        val a = Attempt(op, generation, currentCoroutineContext()[Job]!!)
        a.job.invokeOnCompletion { a.completed.countDown() }; attempts += a
        return try { api() } finally { a.returned.set(true) }
    }
    private val operations = InstantShareOperations(
        contacts = { generation -> observe(Op.CONTACTS, generation) { TestContacts.read(owner = generation) } },
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
    private fun mount(lang: AppLanguage) {
        language = lang; compose.mainClock.autoAdvance = false
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.setContent { if (mounted.value) CompositionLocalProvider(
            LocalAppLanguage provides language, LocalLifecycleOwner provides owner,
        ) { YuldashTheme {
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
        openShare()
    }
    private fun noSideEffects() {
        assertFalse(clipboard.hasPrimaryClip()); assertNull(Shadows.shadowOf(app).nextStartedActivity); assertNull(ShadowToast.getTextOfLatestToast())
        assertEquals(0, count("POST", "/orders/91/share")); assertEquals(0, count("DELETE", "/share/601"))
    }
    private fun counts(reads: Int) {
        assertEquals(1, count("GET", "/trusted-contacts")); assertEquals(reads, count("GET", "/orders/91/shares"))
        assertEquals(reads, attempts.count { it.op == Op.SHARES }); assertEquals(reads, attempts.count { it.op == Op.CONTACTS })
        assertTrue(attempts.all { it.generation == publication.generation })
        assertTrue(records.filter { it[1] == "/trusted-contacts" || it[1]!!.startsWith("/instant/orders/91/share") }.all { it[2] == "Bearer $tokenA" })
    }
    private fun failed(reads: Int) {
        completed(shareAttempt(reads)); counts(reads); noSideEffects()
        assertTrue(visible(error)); assertTrue(visible(retry)); assertFalse(visible(loading))
        assertFalse(visible("Контакт А")); assertFalse(visible(active)); assertFalse(visible(revoke)); assertFalse(visible(copy)); assertFalse(visible(send))
    }
    private fun recovered(reads: Int) {
        counts(reads); noSideEffects(); assertFalse(visible(error)); assertFalse(visible(retry)); assertFalse(visible(loading))
        assertTrue(visible("Контакт А")); assertFalse(visible("Контакт с сервера после кеша")); assertTrue(visible(active)); assertTrue(visible(revoke))
        assertFalse(visible(copy)); assertFalse(visible(send))
    }
    private fun recover(b: Bad, lang: AppLanguage) {
        bad = b; mount(lang); failed(1); val first = shareAttempt(1); val retainedRetry = text(retry)
        // A changed server name would expose an unintended second contacts HTTP request.
        contactName = "Контакт с сервера после кеша"; bad = null; holdFresh = true
        compose.runOnIdle {
            assertTrue(retainedRetry()); assertTrue(retainedRetry())
            // No main-looper idle between callbacks: the load has not started yet.
            counts(1); noSideEffects()
        }
        await { freshStarted.count == 0L }; val fresh = shareAttempt(2)
        assertNotSame(first.job, fresh.job); assertFalse(fresh.job.isCompleted); assertFalse(fresh.returned.get())
        assertTrue(attempts.last { it.op == Op.CONTACTS }.returned.get())
        assertTrue(visible(loading)); assertFalse(visible(error)); counts(2); noSideEffects()
        compose.runOnIdle { repeat(3) { dispatch(retainedRetry) } }; pump(300)
        counts(2); assertFalse(fresh.job.isCompleted); noSideEffects()
        freshRelease.countDown(); completed(fresh); recovered(2)
        compose.runOnIdle { dispatch(retainedRetry) }; pump(300); recovered(2)
        // A visible active row must revoke the fresh server share, not the malformed first reply.
        click(revoke); await { attempts.any { it.op == Op.REVOKE } }; completed(attempts.last { it.op == Op.REVOKE })
        counts(2); assertEquals(1, count("DELETE", "/share/601")); assertFalse(visible(active)); assertFalse(visible(revoke)); assertTrue(visible("Контакт А"))
        assertEquals(if (lang == AppLanguage.Ru) "Ссылка отозвана" else "Һылтанма кире алынды", ShadowToast.getTextOfLatestToast())
        assertFalse(clipboard.hasPrimaryClip()); assertNull(Shadows.shadowOf(app).nextStartedActivity); assertEquals(0, count("POST", "/orders/91/share"))
    }
    private fun failAgain(lang: AppLanguage) {
        mount(lang); failed(1); val old = shareAttempt(1)
        contactName = "Контакт с сервера после кеша"; bad = Bad.WRONG_ORDER; click(retry); failed(2)
        val second = shareAttempt(2); assertNotSame(old.job, second.job)
        bad = null; click(retry); val third = shareAttempt(3); assertNotSame(second.job, third.job); completed(third); recovered(3)
    }
    private fun reopen(lang: AppLanguage) {
        mount(lang); failed(1); val old = shareAttempt(1); val oldRetry = text(retry)
        click(close); await { !visible(error) && !visible(retry) }; pump(300)
        contactName = "Контакт с сервера после кеша"; bad = null; openShare()
        val fresh = shareAttempt(2); assertNotSame(old.job, fresh.job); completed(fresh); recovered(2)
        compose.runOnIdle { repeat(2) { dispatch(oldRetry) } }; pump(300); recovered(2)
        assertSame(publication, TaxiNavigationState.currentTrip()); assertTrue(TaxiNavigationState.isScreenOwner(publication.generation, publication.publisher))
    }
    @After fun releaseFresh() { freshRelease.countDown() }
    @Test fun http503RecoversWithCachedContactsRu() = recover(Bad.HTTP, AppLanguage.Ru)
    @Test fun http503RecoversWithCachedContactsBa() = recover(Bad.HTTP, AppLanguage.Ba)
    @Test fun wrongWrapperRecoversWithCachedContactsRu() = recover(Bad.WRAPPER, AppLanguage.Ru)
    @Test fun wrongWrapperRecoversWithCachedContactsBa() = recover(Bad.WRAPPER, AppLanguage.Ba)
    @Test fun wrongOrderRecoversWithCachedContactsRu() = recover(Bad.WRONG_ORDER, AppLanguage.Ru)
    @Test fun wrongOrderRecoversWithCachedContactsBa() = recover(Bad.WRONG_ORDER, AppLanguage.Ba)
    @Test fun duplicateIdsRecoverWithCachedContactsRu() = recover(Bad.DUPLICATE, AppLanguage.Ru)
    @Test fun duplicateIdsRecoverWithCachedContactsBa() = recover(Bad.DUPLICATE, AppLanguage.Ba)
    @Test fun repeatedSharesFailureKeepsContactsCacheRu() = failAgain(AppLanguage.Ru)
    @Test fun repeatedSharesFailureKeepsContactsCacheBa() = failAgain(AppLanguage.Ba)
    @Test fun reopenedDialogKeepsContactsCacheAndRejectsOldRetryRu() = reopen(AppLanguage.Ru)
    @Test fun reopenedDialogKeepsContactsCacheAndRejectsOldRetryBa() = reopen(AppLanguage.Ba)
}
