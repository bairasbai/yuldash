package com.yuldash.app

import android.app.Application
import com.yuldash.app.data.TestContacts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
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

/** Actual outer caller. Raw click and boundary share one main-loop turn before dialog composition. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InstantShareQueuedBoundaryTest : ShareHelpBoundaryHarness() {
    private enum class Boundary { OWNER, GUEST, UNMOUNT }
    private data class Attempt(val op: String, val generation: Long, val job: Job,
        val returned: AtomicBoolean = AtomicBoolean(false), val completed: CountDownLatch = CountDownLatch(1))
    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private val attempts = CopyOnWriteArrayList<Attempt>()
    private val frames = CopyOnWriteArrayList<TaxiTripPublication?>()
    private var mounts = 0
    private var disposals = 0
    private val freshStarted = CountDownLatch(1)
    private val freshRelease = CountDownLatch(1)
    @Volatile private var holdFresh = false
    private var language = AppLanguage.Ru
    private val safety get() = if (language == AppLanguage.Ru) "Безопасность" else "Именлек"
    private val share get() = if (language == AppLanguage.Ru) "Поделиться поездкой" else "Сәфәр менән бүлешеү"
    private val close get() = if (language == AppLanguage.Ru) "Закрыть" else "Ябыу"
    private val active get() = if (language == AppLanguage.Ru) "Активные ссылки" else "Әүҙем һылтанмалар"
    private val revoke get() = if (language == AppLanguage.Ru) "Отозвать" else "Кире алыу"
    private val loading get() = if (language == AppLanguage.Ru) "Загружаем близких…" else "Яҡындарҙы йөкләйбеҙ…"
    private fun response(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    override fun customResponse(request: RecordedRequest, path: String): MockResponse? {
        val b = request.getHeader("Authorization") == "Bearer $tokenB"
        return when {
            path.startsWith("/ws/instant/") -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.close(1008, "synthetic forbidden") }
            })
            path == "/instant/orders/mine" -> response("""{"items":[{"id":91,"status":"onboard","role":"passenger","driver_name":"Водитель ${if (b) "Б" else "А"}","driver_car":"Машина","driver_plate":"ТЕСТ","from_text":"Пункт А","to_text":"Пункт Б","price_estimate":250,"payment_method":"cash"}]}""")
            path == "/instant/availability" -> response("""{"enabled":true}""")
            path == "/trusted-contacts" -> {
                if (holdFresh) { freshStarted.countDown(); assertTrue(freshRelease.await(30, TimeUnit.SECONDS)) }
                response("""[{"id":7,"name":"Контакт ${if (b) "Б" else "А"}","relation":"Синтетический","phone":"+70000000007"}]""")
            }
            path == "/instant/orders/91/shares" -> response("""[{"id":701,"order_id":91,"contact_id":7,"token":"queued-current"}]""")
            else -> null
        }
    }
    private suspend fun <T> observe(op: String, generation: Long, api: suspend () -> Result<T>): Result<T> {
        val a = Attempt(op, generation, currentCoroutineContext()[Job]!!)
        a.job.invokeOnCompletion { a.completed.countDown() }; attempts += a
        return try { api() } finally { a.returned.set(true) }
    }
    private val operations = InstantShareOperations(
        contacts = { generation -> observe("contacts", generation) { TestContacts.read(owner = generation) } },
        create = { id, contact, generation -> observe("create", generation) { ApiClient.shareInstantTrip(id, contact, expectedGeneration = generation) } },
        revoke = { id, shareId, generation -> observe("revoke", generation) { ApiClient.revokeInstantShare(id, shareId, expectedGeneration = generation) } },
    )
    private fun visible(s: String) = compose.onAllNodesWithText(s).fetchSemanticsNodes().isNotEmpty()
    private fun dialogPresent() = compose.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty()
    private fun expandSafety() {
        pump(); compose.onNodeWithTag("taxiSheetDragArea").performTouchInput { swipe(center, center.copy(y = center.y - 160f), 300) }; pump()
        val action = retain(hasContentDescription(safety)); compose.runOnIdle { dispatch(action) }
    }
    private fun noSideEffects() {
        assertFalse(clipboard.hasPrimaryClip()); assertNull(Shadows.shadowOf(app).nextStartedActivity)
        assertNull(ShadowToast.getTextOfLatestToast())
        assertEquals(0, count("POST", "/orders/91/share")); assertEquals(0, count("DELETE", "/share/701"))
        assertFalse(attempts.any { it.op == "create" || it.op == "revoke" })
    }
    private fun zeroReads() {
        assertTrue("No API entry, including cache fast path", attempts.isEmpty())
        assertEquals(0, count("GET", "/trusted-contacts")); assertEquals(0, count("GET", "/orders/91/shares"))
        noSideEffects()
    }
    private fun mount(lang: AppLanguage): TaxiTripPublication {
        language = lang; compose.mainClock.autoAdvance = false
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.setContent { if (mounted.value) CompositionLocalProvider(
            LocalAppLanguage provides language, LocalLifecycleOwner provides owner,
        ) {
            DisposableEffect(Unit) { mounts++; onDispose { disposals++ } }
            val trip = TaxiNavigationState.currentTrip()
            SideEffect { frames += trip }
            YuldashTheme {
                InstantOrderScreen(onBack = {}, onLoginRequired = {}, embedded = true, renderNativeMap = false,
                    shareOperations = operations, readShares = { id, generation ->
                        assertEquals(91, id)
                        observe("shares", generation) { ApiClient.getInstantShares(id, expectedGeneration = generation) }
                    })
            }
        } }
        await { TaxiNavigationState.currentTrip()?.orderId == 91 && visible("Водитель А") }
        assertEquals(1, mounts); assertEquals(0, disposals); assertEquals(1, count("GET", "/orders/mine"))
        assertEquals(0, count("GET", "/orders/91")); zeroReads(); expandSafety()
        return TaxiNavigationState.currentTrip()!!
    }
    private fun queued(boundary: Boundary, lang: AppLanguage) {
        val old = mount(lang); val oldShare = text(share); val frameCount = frames.size
        compose.runOnIdle {
            assertTrue(oldShare())
            when (boundary) {
                Boundary.OWNER -> ApiClient.saveToken(tokenB)
                Boundary.GUEST -> ApiClient.logout()
                Boundary.UNMOUNT -> mounted.value = false
            }
            assertEquals(frameCount, frames.size); assertEquals(0, disposals); zeroReads()
        }
        when (boundary) {
            Boundary.OWNER -> await { TaxiNavigationState.currentTrip()?.let { it.generation != old.generation && visible("Водитель Б") } == true }
            Boundary.GUEST -> await { visible(if (lang == AppLanguage.Ru) "Войди, чтобы заказать машину" else "Машина заказлар өсөн ин") }
            Boundary.UNMOUNT -> {
                await { disposals == 1 && !TaxiNavigationState.tripOnScreen() && !TaxiNavigationState.isScreenOwner(old.generation, old.publisher) }
                assertSame(old, TaxiNavigationState.currentTrip())
            }
        }
        pump(500); zeroReads(); assertFalse(dialogPresent()); assertFalse(visible(active)); assertFalse(visible("Контакт А"))
        compose.runOnIdle { oldShare() }; pump(300); zeroReads(); assertFalse(dialogPresent())
        val expectedToken = if (boundary == Boundary.UNMOUNT) tokenA else tokenB
        when (boundary) {
            Boundary.GUEST -> compose.runOnIdle { ApiClient.saveToken(tokenB) }
            Boundary.UNMOUNT -> compose.runOnIdle { mounted.value = true }
            else -> Unit
        }
        await { TaxiNavigationState.currentTrip()?.let { it !== old && visible(if (boundary == Boundary.UNMOUNT) "Водитель А" else "Водитель Б") } == true }
        val fresh = TaxiNavigationState.currentTrip()!!
        assertEquals(91, fresh.orderId); assertNotSame(old.publisher, fresh.publisher)
        assertTrue(TaxiNavigationState.isScreenOwner(fresh.generation, fresh.publisher))
        if (boundary == Boundary.UNMOUNT) { assertEquals(old.generation, fresh.generation); assertEquals(2, mounts); assertEquals(1, disposals) }
        else { assertNotEquals(old.generation, fresh.generation); assertEquals(1, mounts); assertEquals(0, disposals) }
        assertEquals(2, count("GET", "/orders/mine")); zeroReads()
        compose.runOnIdle { oldShare() }; pump(300); zeroReads(); assertFalse(dialogPresent())
        expandSafety(); holdFresh = true; click(share)
        await { freshStarted.count == 0L && attempts.size == 1 }
        val contacts = attempts.single(); assertEquals("contacts", contacts.op)
        assertEquals(fresh.generation, contacts.generation); assertFalse(contacts.job.isCompleted); assertFalse(contacts.returned.get())
        assertTrue(visible(loading)); assertTrue(visible(close)); assertTrue(dialogPresent()); assertEquals(1, count("GET", "/trusted-contacts")); assertEquals(0, count("GET", "/orders/91/shares"))
        freshRelease.countDown()
        await { attempts.size == 2 && contacts.job.isCompleted && contacts.completed.count == 0L }
        val shares = attempts.last(); assertEquals("shares", shares.op); assertSame(contacts.job, shares.job)
        assertTrue(contacts.returned.get()); assertTrue(shares.returned.get()); assertTrue(shares.job.isCompleted)
        assertEquals(0L, shares.completed.count); assertFalse(shares.job.isCancelled)
        assertTrue(attempts.all { it.generation == fresh.generation })
        pump(300); assertTrue(visible(if (boundary == Boundary.UNMOUNT) "Контакт А" else "Контакт Б"))
        assertTrue(visible(active)); assertTrue(visible(revoke)); assertFalse(visible(loading))
        assertEquals(1, count("GET", "/trusted-contacts")); assertEquals(1, count("GET", "/orders/91/shares")); noSideEffects()
        assertTrue(records.filter { it[1] == "/trusted-contacts" || it[1] == "/instant/orders/91/shares" }.all { it[2] == "Bearer $expectedToken" })
        assertEquals(listOf("Bearer $tokenA", "Bearer $expectedToken"), records.filter { it[1] == "/instant/orders/mine" }.map { it[2] })
        assertSame(fresh, TaxiNavigationState.currentTrip())
    }
    @After fun releaseFresh() { freshRelease.countDown() }
    @Test fun ownerChangedBeforeFirstContactsRequestRu() = queued(Boundary.OWNER, AppLanguage.Ru)
    @Test fun ownerChangedBeforeFirstContactsRequestBa() = queued(Boundary.OWNER, AppLanguage.Ba)
    @Test fun guestBeforeFirstContactsRequestRu() = queued(Boundary.GUEST, AppLanguage.Ru)
    @Test fun guestBeforeFirstContactsRequestBa() = queued(Boundary.GUEST, AppLanguage.Ba)
    @Test fun unmountedBeforeFirstContactsRequestRu() = queued(Boundary.UNMOUNT, AppLanguage.Ru)
    @Test fun unmountedBeforeFirstContactsRequestBa() = queued(Boundary.UNMOUNT, AppLanguage.Ba)
}
