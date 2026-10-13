package com.yuldash.app

import android.app.Application
import com.yuldash.app.data.TestContacts
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
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

/** Actual outer callers; synthetic publication ABA/HTTP, observed real coroutine Jobs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InstantSharePublicationBoundaryTest : ShareHelpBoundaryHarness() {
    private enum class Op { CONTACTS, SHARES, CREATE, REVOKE }
    private data class Attempt(val op: Op, val job: Job,
        val returned: AtomicBoolean = AtomicBoolean(false), val completion: CountDownLatch = CountDownLatch(1))
    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private val showA = mutableStateOf(true)
    private val showB = mutableStateOf(false)
    private val attempts = CopyOnWriteArrayList<Attempt>()
    private val frames = CopyOnWriteArrayList<TaxiTripPublication?>()
    private val held = CountDownLatch(1)
    private val unblock = CountDownLatch(1)
    @Volatile private var heldOp: Op? = null
    @Volatile private var stored = false
    @Volatile private var fresh = false
    @Volatile private var driver = "Водитель А"
    private lateinit var publication: TaxiTripPublication
    private var language = AppLanguage.Ru
    private val safety get() = if (language == AppLanguage.Ru) "Безопасность" else "Именлек"
    private val share get() = if (language == AppLanguage.Ru) "Поделиться поездкой" else "Сәфәр менән бүлешеү"
    private val copy get() = if (language == AppLanguage.Ru) "Скопировать" else "Күсереү"
    private val send get() = if (language == AppLanguage.Ru) "Отправить" else "Ебәреү"
    private val close get() = if (language == AppLanguage.Ru) "Закрыть" else "Ябыу"
    private val revoke get() = if (language == AppLanguage.Ru) "Отозвать" else "Кире алыу"
    private val dialog get() = close // Exclusive button of the actual share dialog, including initial load.
    private fun visible(s: String) = compose.onAllNodesWithText(s).fetchSemanticsNodes().isNotEmpty()
    private fun response(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun order() = """{"id":91,"status":"onboard","role":"passenger","driver_name":"$driver","driver_car":"Машина","driver_plate":"ТЕСТ","from_text":"Пункт А","to_text":"Пункт Б","price_estimate":250,"payment_method":"cash"}"""
    private fun row() = """{"id":501,"order_id":91,"contact_id":7,"token":"${if (fresh) "fresh-publication" else "old-publication"}"}"""
    override fun customResponse(request: RecordedRequest, path: String): MockResponse? {
        val op = when {
            path == "/trusted-contacts" -> Op.CONTACTS
            path == "/instant/orders/91/shares" -> Op.SHARES
            path == "/instant/orders/91/share" && request.method == "POST" -> Op.CREATE
            path == "/instant/orders/91/share/501" && request.method == "DELETE" -> Op.REVOKE
            else -> null
        }
        if (op == Op.CREATE) stored = true
        if (op == Op.REVOKE) stored = false
        val body = when (op) {
            Op.CONTACTS -> """[{"id":7,"name":"Контакт А"}]"""
            Op.SHARES -> if (stored) "[${row()}]" else "[]"
            Op.CREATE -> row()
            Op.REVOKE -> "{}"
            null -> null
        }
        if (op != null && op == heldOp) { held.countDown(); assertTrue(unblock.await(30, TimeUnit.SECONDS)) }
        return when {
            body != null -> response(body)
            path.startsWith("/ws/instant/") -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.close(1008, "synthetic forbidden") }
            })
            path == "/instant/orders/mine" -> response("""{"items":[${order()}]}""")
            path == "/instant/orders/91" -> response(order())
            path == "/instant/availability" -> response("""{"enabled":true}""")
            else -> null
        }
    }
    private suspend fun <T> observe(op: Op, api: suspend () -> Result<T>): Result<T> {
        val a = Attempt(op, currentCoroutineContext()[Job]!!)
        a.job.invokeOnCompletion { a.completion.countDown() }; attempts += a
        return try { api() } finally { a.returned.set(true) }
    }
    private val operations = InstantShareOperations(
        contacts = { gen -> observe(Op.CONTACTS) { TestContacts.read(owner = gen) } },
        create = { id, contact, gen -> observe(Op.CREATE) { ApiClient.shareInstantTrip(id, contact, expectedGeneration = gen) } },
        revoke = { id, shareId, gen -> observe(Op.REVOKE) { ApiClient.revokeInstantShare(id, shareId, expectedGeneration = gen) } },
    )
    private fun attempt(op: Op): Attempt { await { attempts.any { it.op == op } }; return attempts.last { it.op == op } }
    private fun completed(a: Attempt, cancelled: Boolean) {
        await { a.job.isCompleted && a.completion.count == 0L }; assertTrue(a.returned.get())
        assertEquals(cancelled, a.job.isCancelled); pump(300)
    }
    private fun openShare() {
        val action = retain(hasContentDescription(safety)); compose.runOnIdle { dispatch(action) }; click(share)
    }
    private fun expand() {
        pump(); compose.onAllNodesWithTag("taxiSheetDragArea").onLast().performTouchInput { swipe(center, center.copy(y = center.y - 160f), 300) }; pump()
    }
    private fun mount(lang: AppLanguage = AppLanguage.Ru, open: Boolean = true) {
        language = lang; compose.mainClock.autoAdvance = false
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.setContent { if (mounted.value) CompositionLocalProvider(
            LocalAppLanguage provides language, LocalLifecycleOwner provides owner,
        ) {
            val trip = TaxiNavigationState.currentTrip(); SideEffect { frames += trip }
            YuldashTheme { Box {
                if (showA.value) InstantOrderScreen(onBack = {}, onLoginRequired = {}, embedded = true,
                    renderNativeMap = false, shareOperations = operations, readShares = { id, gen ->
                        observe(Op.SHARES) { ApiClient.getInstantShares(id, expectedGeneration = gen) }
                    })
                if (showB.value) InstantOrderScreen(onBack = {}, onLoginRequired = {}, embedded = true,
                    renderNativeMap = false)
            } }
        } }
        await { TaxiNavigationState.currentTrip()?.orderId == 91 && TaxiNavigationState.tripOnScreen() }
        publication = TaxiNavigationState.currentTrip()!!
        assertTrue(TaxiNavigationState.isScreenOwner(publication.generation, publication.publisher))
        assertEquals(1, count("GET", "/orders/mine")); expand(); if (open) openShare()
    }
    private fun noSideEffects() {
        assertFalse(clipboard.hasPrimaryClip()); assertNull(Shadows.shadowOf(app).nextStartedActivity)
        assertNull(ShadowToast.getTextOfLatestToast())
    }
    private fun replaceReceipt(): TaxiTripPublication {
        assertTrue(TaxiNavigationState.publishScreen(publication.generation, publication.publisher, "enroute", 92))
        assertEquals(92, TaxiNavigationState.currentTrip()!!.orderId)
        assertTrue(TaxiNavigationState.publishScreen(publication.generation, publication.publisher, "enroute", 91))
        val next = TaxiNavigationState.currentTrip()!!
        assertNotSame(publication, next); assertEquals(publication.orderId, next.orderId)
        assertSame(publication.publisher, next.publisher); assertEquals(publication.generation, next.generation)
        assertTrue(TaxiNavigationState.isScreenOwner(next.generation, next.publisher)); return next
    }
    private fun prepare(op: Op, lang: AppLanguage) {
        if (op == Op.CONTACTS || op == Op.SHARES) { stored = op == Op.SHARES; heldOp = op; mount(lang); return }
        mount(lang); completed(attempt(Op.SHARES), false)
        if (op == Op.CREATE) { heldOp = op; click("Контакт А"); return }
        click("Контакт А"); completed(attempt(Op.CREATE), false); await { visible(copy) }
        ShadowToast.reset(); heldOp = op; click(revoke)
    }
    private fun heldAfterReplacement(op: Op, lang: AppLanguage) {
        prepare(op, lang); await { held.count == 0L }; val old = attempt(op)
        assertFalse(old.job.isCompleted); assertFalse(old.returned.get())
        val n = frames.size
        compose.runOnIdle { replaceReceipt(); assertEquals(n, frames.size) }
        // The intermediate 92 is never applied as a UI frame: this is a controlled same-publisher ABA.
        await { frames.drop(n).any { it !== publication && it?.orderId == 91 } }
        unblock.countDown(); completed(old, true)
        assertFalse(visible(dialog)); assertFalse(visible(copy)); noSideEffects()
        assertEquals(if (op == Op.CREATE || op == Op.REVOKE) 1 else 0, count("POST", "/orders/91/share"))
        assertEquals(if (op == Op.REVOKE) 1 else 0, count("DELETE", "/share/501"))
        assertEquals(if (op == Op.CONTACTS) 0 else 1, count("GET", "/orders/91/shares"))
        // The current replacement can explicitly reopen and read, including a committed held mutation.
        heldOp = null; fresh = true; expand(); openShare()
        await { attempts.count { it.op == Op.SHARES } > (if (op == Op.CONTACTS) 0 else 1) }
        val current = attempts.last { it.op == Op.SHARES }; assertNotSame(old.job, current.job); completed(current, false)
        assertTrue(visible("Контакт А")); assertTrue(visible(dialog)); noSideEffects()
        val expectedShares = if (op == Op.CONTACTS) 1 else 2
        assertEquals(expectedShares, count("GET", "/orders/91/shares"))
        assertEquals(expectedShares, attempts.count { it.op == Op.SHARES })
        assertEquals(if (op == Op.CREATE || op == Op.REVOKE) 1 else 0, count("POST", "/orders/91/share"))
        assertEquals(if (op == Op.REVOKE) 1 else 0, count("DELETE", "/share/501"))
        assertTrue(records.filter { it[1]!!.contains("/shares") }.all { it[2] == "Bearer $tokenA" })
    }
    private fun savedBeforeFrame(lang: AppLanguage, active: Boolean) {
        mount(lang); completed(attempt(Op.SHARES), false)
        val contact = text("Контакт А"); val closeAction = text(close)
        val routes = if (active) {
            click("Контакт А"); completed(attempt(Op.CREATE), false); await { visible(copy) }
            listOf(text(copy), text(send), text(revoke))
        } else listOf(contact)
        ShadowToast.reset(); val priorPosts = count("POST", "/orders/91/share"); val n = frames.size
        compose.runOnIdle {
            replaceReceipt(); routes.forEach { it() }; closeAction()
            assertEquals(n, frames.size); noSideEffects()
            assertEquals(priorPosts, count("POST", "/orders/91/share")); assertEquals(0, count("DELETE", "/share/501"))
        }
        pump(500); noSideEffects(); assertFalse(visible(dialog))
        assertEquals(priorPosts, count("POST", "/orders/91/share")); assertEquals(0, count("DELETE", "/share/501"))
    }
    private fun twoScreens(lang: AppLanguage) {
        stored = true; heldOp = Op.SHARES; mount(lang)
        await { held.count == 0L }; val old = attempt(Op.SHARES); assertFalse(old.job.isCompleted)
        compose.runOnIdle { driver = "Водитель Б"; showB.value = true }
        await { count("GET", "/orders/mine") == 2 && visible(driver) && TaxiNavigationState.currentTrip()?.publisher !== publication.publisher }
        val next = TaxiNavigationState.currentTrip()!!
        assertEquals(91, next.orderId); assertNotSame(publication, next)
        assertFalse(TaxiNavigationState.isScreenOwner(publication.generation, publication.publisher))
        assertTrue(TaxiNavigationState.isScreenOwner(next.generation, next.publisher))
        unblock.countDown(); completed(old, false); noSideEffects()
        assertFalse(visible("Контакт А")); assertEquals(1, count("GET", "/orders/91/shares"))
        // Remove only A; its real onDispose must not release B's lease or publication.
        compose.runOnIdle { showA.value = false }; pump(500)
        assertSame(next, TaxiNavigationState.currentTrip()); assertTrue(TaxiNavigationState.isScreenOwner(next.generation, next.publisher))
        assertTrue(visible("Водитель Б")); heldOp = null; fresh = true; expand(); openShare()
        await { visible("Контакт А") && count("GET", "/orders/91/shares") == 2 }
        assertEquals(0, count("POST", "/orders/91/share")); assertEquals(0, count("DELETE", "/share/501")); noSideEffects()
    }
    private fun ordinary(lang: AppLanguage) {
        prepare(Op.CREATE, lang); await { held.count == 0L }; val old = attempt(Op.CREATE)
        compose.runOnIdle {
            assertTrue(TaxiNavigationState.publishScreen(publication.generation, publication.publisher, "enroute", 91))
            driver = "Водитель А обновлён"; owner.registry.currentState = Lifecycle.State.RESUMED
        }
        await { visible(driver) && count("GET", "/orders/91") > 0 }
        assertSame(publication, TaxiNavigationState.currentTrip()); assertFalse(old.job.isCompleted)
        unblock.countDown(); completed(old, false); assertTrue(visible(copy))
        assertEquals(if (lang == AppLanguage.Ru) "Близкий получит SMS о поездке: Контакт А" else "Яҡын кеше сәфәр тураһында SMS алыр: Контакт А", ShadowToast.getTextOfLatestToast())
        ShadowToast.reset(); click(copy); click(send)
        val url = ApiClient.apiBase() + "/t/old-publication"
        assertEquals(url, clipboard.primaryClip!!.getItemAt(0).text.toString())
        val chooser = Shadows.shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertTrue(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!.getStringExtra(Intent.EXTRA_TEXT)!!.endsWith(url))
        assertEquals(1, count("POST", "/orders/91/share"))
    }
    @After fun releaseHeld() { unblock.countDown() }
    @Test fun contactsAfterSamePublisherAbaRu() = heldAfterReplacement(Op.CONTACTS, AppLanguage.Ru)
    @Test fun contactsAfterSamePublisherAbaBa() = heldAfterReplacement(Op.CONTACTS, AppLanguage.Ba)
    @Test fun sharesAfterSamePublisherAbaRu() = heldAfterReplacement(Op.SHARES, AppLanguage.Ru)
    @Test fun sharesAfterSamePublisherAbaBa() = heldAfterReplacement(Op.SHARES, AppLanguage.Ba)
    @Test fun createAfterSamePublisherAbaRu() = heldAfterReplacement(Op.CREATE, AppLanguage.Ru)
    @Test fun createAfterSamePublisherAbaBa() = heldAfterReplacement(Op.CREATE, AppLanguage.Ba)
    @Test fun revokeAfterSamePublisherAbaRu() = heldAfterReplacement(Op.REVOKE, AppLanguage.Ru)
    @Test fun revokeAfterSamePublisherAbaBa() = heldAfterReplacement(Op.REVOKE, AppLanguage.Ba)
    @Test fun savedContactBeforeReplacementFrameRu() = savedBeforeFrame(AppLanguage.Ru, false)
    @Test fun savedContactBeforeReplacementFrameBa() = savedBeforeFrame(AppLanguage.Ba, false)
    @Test fun savedRoutesBeforeReplacementFrameRu() = savedBeforeFrame(AppLanguage.Ru, true)
    @Test fun savedRoutesBeforeReplacementFrameBa() = savedBeforeFrame(AppLanguage.Ba, true)
    @Test fun twoActualScreensSameIdRu() = twoScreens(AppLanguage.Ru)
    @Test fun twoActualScreensSameIdBa() = twoScreens(AppLanguage.Ba)
    @Test fun ordinaryPublicationAndDtoRu() = ordinary(AppLanguage.Ru)
    @Test fun ordinaryPublicationAndDtoBa() = ordinary(AppLanguage.Ba)
}
