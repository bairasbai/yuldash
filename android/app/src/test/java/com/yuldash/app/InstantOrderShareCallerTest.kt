package com.yuldash.app

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.TripShareDto
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
import java.util.concurrent.atomic.AtomicInteger

/** Actual outer screen, publication and lease, local HTTP and actual client Job. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InstantOrderShareCallerTest : ShareHelpBoundaryHarness() {
    private enum class Retire { LEASE, FINISHED, OWNER, GUEST, DONE, CANCELLED }
    private data class Read(val ordinal: Int, val generation: Long, val job: Job,
        val returned: AtomicBoolean = AtomicBoolean(false), val completed: CountDownLatch = CountDownLatch(1))
    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private val reads = CopyOnWriteArrayList<Read>()
    private val ordinals = AtomicInteger()
    private val held = CountDownLatch(1)
    private val unblock = CountDownLatch(1)
    @Volatile private var status = "onboard"
    @Volatile private var driver = "Водитель А"
    @Volatile private var stored = false
    @Volatile private var token = ""
    @Volatile private var holdOrdinal = 0
    @Volatile private var createFails = true
    @Volatile private var listFails = false
    private lateinit var publication: TaxiTripPublication
    private var language = AppLanguage.Ru
    private val safety get() = if (language == AppLanguage.Ru) "Безопасность" else "Именлек"
    private val share get() = if (language == AppLanguage.Ru) "Поделиться поездкой" else "Сәфәр менән бүлешеү"
    private val copy get() = if (language == AppLanguage.Ru) "Скопировать" else "Күсереү"
    private val send get() = if (language == AppLanguage.Ru) "Отправить" else "Ебәреү"
    private val more get() = if (language == AppLanguage.Ru) "Поделиться ещё" else "Йәнә бүлешеү"
    private val revoke get() = if (language == AppLanguage.Ru) "Отозвать" else "Кире алыу"
    private val close get() = if (language == AppLanguage.Ru) "Закрыть" else "Ябыу"
    private val retry get() = if (language == AppLanguage.Ru) "Повторить" else "Ҡабатлау"
    private val title get() = if (language == AppLanguage.Ru) "Ссылка для близкого" else "Яҡын кеше өсөн һылтанма"
    private fun order(id: Int = 91) = """{"id":$id,"status":"$status","role":"passenger","driver_name":"${if (id == 91) driver else "Водитель Б"}","driver_car":"Машина","driver_plate":"ТЕСТ","from_text":"Пункт А","to_text":"Пункт Б","price_estimate":250,"payment_method":"cash"}"""
    private fun row() = """{"id":501,"order_id":91,"contact_id":7,"token":"$token"}"""
    private fun response(body: String, code: Int = 200) = MockResponse().setHeader("Content-Type", "application/json").setResponseCode(code).setBody(body)
    override fun customResponse(request: RecordedRequest, path: String): MockResponse? = when {
        path.startsWith("/ws/instant/") -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            // Explicit synthetic terminal transport; real GPS/location delivery is outside this test.
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.close(1008, "synthetic forbidden") }
        })
        path == "/instant/orders/mine" -> response("""{"items":[${order(if (request.getHeader("Authorization") == "Bearer $tokenB") 92 else 91)}]}""")
        path == "/instant/availability" -> response("""{"enabled":true}""")
        path == "/instant/orders/91" -> response(order())
        path == "/instant/orders/91/shares" -> {
            val ordinal = count("GET", "/orders/91/shares")
            if (ordinal == holdOrdinal) {
                held.countDown(); assertTrue("Release held response", unblock.await(30, TimeUnit.SECONDS))
            }
            response(if (stored) "[${row()}]" else "[]", if (ordinal > 1 && listFails) 503 else 200)
        }
        path == "/instant/orders/91/share" && request.method == "POST" -> {
            stored = true; token = if (createFails) "late-91" else "active-91"
            response(row(), if (createFails) 503 else 200)
        }
        path == "/instant/orders/91/share/501" && request.method == "DELETE" -> {
            stored = false; response("{}", 503)
        }
        else -> null
    }
    private suspend fun observedRead(id: Int, generation: Long): Result<List<TripShareDto>> {
        assertEquals(91, id)
        val read = Read(ordinals.incrementAndGet(), generation, currentCoroutineContext()[Job]!!)
        read.job.invokeOnCompletion { read.completed.countDown() }; reads += read
        return try { ApiClient.getInstantShares(id, expectedGeneration = generation) }
        finally { read.returned.set(true) }
    }
    private fun visible(label: String) = compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()
    private fun read(ordinal: Int): Read {
        await { reads.any { it.ordinal == ordinal } }; return reads.single { it.ordinal == ordinal }
    }
    private fun completed(read: Read) {
        await { read.job.isCompleted && read.completed.count == 0L }
        assertTrue(read.returned.get()); pump(300)
    }
    private fun blocked(): Read {
        await { held.count == 0L }; val read = read(holdOrdinal)
        assertFalse(read.job.isCompleted); assertFalse(read.returned.get())
        assertEquals(publication.generation, read.generation); return read
    }
    private fun mount(lang: AppLanguage = AppLanguage.Ru) {
        language = lang; compose.mainClock.autoAdvance = false
        // Restore still runs; keep actual polling stopped until a test resumes its real lifecycle.
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.setContent { if (mounted.value) CompositionLocalProvider(
            LocalAppLanguage provides language, LocalLifecycleOwner provides owner,
        ) { YuldashTheme {
            InstantOrderScreen(onBack = {}, onLoginRequired = {}, embedded = true,
                renderNativeMap = false, readShares = ::observedRead)
        } } }
        await { TaxiNavigationState.currentTrip()?.orderId == 91 && TaxiNavigationState.tripOnScreen() }
        publication = TaxiNavigationState.currentTrip()!!
        assertTrue(TaxiNavigationState.isScreenOwner(publication.generation, publication.publisher))
        assertEquals(1, count("GET", "/orders/mine")); assertEquals(0, count("GET", "/orders/91"))
        pump(); compose.onNodeWithTag("taxiSheetDragArea").performTouchInput { swipe(center, center.copy(y = center.y - 160f), 300) }; pump()
    }
    private fun openShare() {
        val action = retain(hasContentDescription(safety)); compose.runOnIdle { dispatch(action) }; click(share)
    }
    private fun start(revoking: Boolean, retrying: Boolean): List<() -> Boolean> {
        holdOrdinal = if (retrying) 3 else 2; listFails = retrying
        mount(); openShare(); completed(read(1)); val contact = text("Контакт А")
        val actions: List<() -> Boolean>
        if (revoking) {
            createFails = false; click("Контакт А"); await { visible(copy) }; pump(300)
            actions = listOf(contact, text(copy), text(send), text(more), text(revoke), text(close))
            ShadowToast.reset(); click(revoke)
        } else { actions = listOf(contact, text(close)); click("Контакт А") }
        if (retrying) { completed(read(2)); assertTrue(visible(retry)); listFails = false; click(retry) }
        return actions
    }
    private fun retire(which: Retire) {
        compose.runOnIdle { when (which) {
            Retire.LEASE -> assertTrue(TaxiNavigationState.claimScreen(publication.generation, Any()))
            Retire.FINISHED -> assertTrue(TaxiNavigationState.finishTrip(publication))
            Retire.OWNER -> ApiClient.saveToken(tokenB)
            Retire.GUEST -> ApiClient.logout()
            Retire.DONE, Retire.CANCELLED -> {
                status = if (which == Retire.DONE) "done" else "cancelled"
                owner.registry.currentState = Lifecycle.State.RESUMED
            }
        } }
        if (which == Retire.LEASE) {
            await { !TaxiNavigationState.isScreenOwner(publication.generation, publication.publisher) }
            assertSame(publication, TaxiNavigationState.currentTrip())
        } else if (which == Retire.FINISHED || which in setOf(Retire.DONE, Retire.CANCELLED)) {
            await { TaxiNavigationState.currentTrip() == null && !TaxiNavigationState.tripOnScreen() }
            assertTrue(TaxiNavigationState.isScreenOwner(publication.generation, publication.publisher))
            if (which != Retire.FINISHED) assertTrue(count("GET", "/orders/91") > 0)
        } else {
            await { !ApiClient.isCurrentSession(publication.generation) }
            if (which == Retire.OWNER) await { TaxiNavigationState.currentTrip()?.orderId == 92 }
        }
        pump(500)
    }
    private fun noSideEffects() {
        assertFalse(clipboard.hasPrimaryClip()); assertNull(Shadows.shadowOf(app).nextStartedActivity)
        assertNull(ShadowToast.getTextOfLatestToast())
    }
    private fun counts(revoking: Boolean, gets: Int) {
        assertEquals(1, count("POST", "/orders/91/share")); assertEquals(if (revoking) 1 else 0, count("DELETE", "/share/501"))
        assertEquals(gets, count("GET", "/orders/91/shares"))
        assertFalse(records.any { it[1]!!.startsWith("/instant/orders/91/share") && it[2] == "Bearer $tokenB" })
        assertTrue(reads.all { it.generation == publication.generation })
    }
    private fun late(which: Retire, revoking: Boolean, retrying: Boolean = false) {
        val actions = start(revoking, retrying); val old = blocked(); retire(which)
        val staysMounted = which in setOf(Retire.LEASE, Retire.FINISHED)
        if (staysMounted) { assertFalse(old.job.isCompleted); assertFalse(old.returned.get()) }
        unblock.countDown(); completed(old); assertEquals(!staysMounted, old.job.isCancelled)
        compose.runOnIdle { actions.forEach(::dispatch) }; pump(300)
        noSideEffects(); assertFalse(visible(copy)); counts(revoking, holdOrdinal)
        if (staysMounted) {
            assertTrue(visible(if (revoking) title else share)); assertTrue(visible(retry))
            assertTrue(visible("Водитель А")); assertEquals(0, count("GET", "/orders/91"))
            if (which == Retire.LEASE) assertSame(publication, TaxiNavigationState.currentTrip())
            else assertNull(TaxiNavigationState.currentTrip())
        } else { assertFalse(visible(title)); assertFalse(visible("Контакт А")) }
    }
    private fun oldAction(which: Retire, fromSafety: Boolean) {
        mount(); val action = retain(hasContentDescription(safety)); compose.runOnIdle { dispatch(action) }
        val old = if (fromSafety) text(share) else { click(share); completed(read(1)); text("Контакт А") }
        val gets = count("GET", "/orders/91/shares"); val contacts = count("GET", "/trusted-contacts")
        retire(which); compose.runOnIdle { dispatch(old) }; pump(300)
        noSideEffects(); assertEquals(0, count("POST", "/share")); assertEquals(0, count("DELETE", "/share/501"))
        assertEquals(gets, count("GET", "/orders/91/shares")); assertEquals(contacts, count("GET", "/trusted-contacts"))
    }
    private fun routes(expected: String) {
        assertTrue(visible(copy)); click(copy); click(send)
        val url = ApiClient.apiBase() + "/t/" + expected
        assertEquals(url, clipboard.primaryClip!!.getItemAt(0).text.toString())
        val chooser = Shadows.shadowOf(app).nextStartedActivity; assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val payload = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, payload.action); assertTrue(payload.getStringExtra(Intent.EXTRA_TEXT)!!.endsWith(url))
        assertNull(Shadows.shadowOf(app).nextStartedActivity)
    }
    private fun freshDto(revoking: Boolean) {
        start(revoking, false); val old = blocked()
        compose.runOnIdle { driver = "Водитель А обновлён"; owner.registry.currentState = Lifecycle.State.RESUMED }
        await { visible(driver) && count("GET", "/orders/91") > 0 }
        assertSame(publication, TaxiNavigationState.currentTrip())
        assertTrue(TaxiNavigationState.isScreenOwner(publication.generation, publication.publisher))
        assertFalse(old.job.isCompleted); assertFalse(old.returned.get())
        unblock.countDown(); completed(old); assertFalse(old.job.isCancelled)
        assertFalse(visible(retry)); counts(revoking, 2)
        if (revoking) {
            assertFalse(visible(copy)); assertFalse(visible(title)); assertTrue(visible(share)); assertTrue(visible("Контакт А"))
            assertEquals("Ссылка отозвана", ShadowToast.getTextOfLatestToast()); assertFalse(clipboard.hasPrimaryClip())
            assertNull(Shadows.shadowOf(app).nextStartedActivity)
            click(close); await { !visible(share) && !visible("Контакт А") }
        } else { assertNull(ShadowToast.getTextOfLatestToast()); routes("late-91") }
    }
    private fun current(lang: AppLanguage) {
        createFails = false; mount(lang); openShare(); completed(read(1)); click("Контакт А"); await { visible(copy) }; pump(300)
        assertEquals(if (lang == AppLanguage.Ru) "Ссылка готова: Контакт А" else "Һылтанма әҙер: Контакт А", ShadowToast.getTextOfLatestToast())
        routes("active-91"); counts(false, 1); click(close); await { !visible(title) && !visible(share) }
        assertSame(publication, TaxiNavigationState.currentTrip())
    }
    @After fun releaseHeld() { unblock.countDown() }
    @Test fun leaseRetiresSafetyShare() = oldAction(Retire.LEASE, true)
    @Test fun finishedReceiptRetiresSafetyShare() = oldAction(Retire.FINISHED, true)
    @Test fun leaseRetiresContact() = oldAction(Retire.LEASE, false)
    @Test fun finishedReceiptRetiresContact() = oldAction(Retire.FINISHED, false)
    @Test fun heldCreateReadAfterLeaseLoss() = late(Retire.LEASE, false)
    @Test fun heldRevokeReadAfterLeaseLoss() = late(Retire.LEASE, true)
    @Test fun heldCreateRetryAfterLeaseLoss() = late(Retire.LEASE, false, true)
    @Test fun heldRevokeRetryAfterLeaseLoss() = late(Retire.LEASE, true, true)
    @Test fun heldCreateReadAfterFinishedReceipt() = late(Retire.FINISHED, false)
    @Test fun heldRevokeReadAfterFinishedReceipt() = late(Retire.FINISHED, true)
    @Test fun heldCreateRetryAfterFinishedReceipt() = late(Retire.FINISHED, false, true)
    @Test fun heldRevokeRetryAfterFinishedReceipt() = late(Retire.FINISHED, true, true)
    @Test fun heldCreateReadAfterPolledDone() = late(Retire.DONE, false)
    @Test fun heldRevokeReadAfterPolledDone() = late(Retire.DONE, true)
    @Test fun heldCreateReadAfterPolledCancelled() = late(Retire.CANCELLED, false)
    @Test fun heldRevokeReadAfterPolledCancelled() = late(Retire.CANCELLED, true)
    @Test fun heldCreateReadAfterOwnerChange() = late(Retire.OWNER, false)
    @Test fun heldRevokeReadAfterOwnerChange() = late(Retire.OWNER, true)
    @Test fun heldCreateReadAfterGuest() = late(Retire.GUEST, false)
    @Test fun heldRevokeReadAfterGuest() = late(Retire.GUEST, true)
    @Test fun ordinaryDtoRefreshKeepsCreateRecheckCurrent() = freshDto(false)
    @Test fun ordinaryDtoRefreshKeepsRevokeRecheckCurrent() = freshDto(true)
    @Test fun currentOuterRussianScreenSharesAndCloses() = current(AppLanguage.Ru)
    @Test fun currentOuterBashkirScreenSharesAndCloses() = current(AppLanguage.Ba)
}
