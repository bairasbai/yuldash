package com.yuldash.app

import android.app.Application
import com.yuldash.app.data.TestContacts
import android.content.Intent
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

/** Actual outer caller and actual client Jobs; synthetic local HTTP, no extra Job. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InstantOrderShareOperationsTest : ShareHelpBoundaryHarness() {
    private enum class Op { CONTACTS, SHARES, CREATE, REVOKE }
    private enum class Loss { LEASE, FINISHED, OWNER, GUEST, DONE, CANCELLED }
    private data class Attempt(val op: Op, val generation: Long, val job: Job,
        val returned: AtomicBoolean = AtomicBoolean(false), val completed: CountDownLatch = CountDownLatch(1))
    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private val attempts = CopyOnWriteArrayList<Attempt>()
    private val frames = CopyOnWriteArrayList<Pair<TaxiTripPublication?, Boolean>>()
    private val held = CountDownLatch(1)
    private val unblock = CountDownLatch(1)
    @Volatile private var heldOp: Op? = null
    @Volatile private var stored = false
    @Volatile private var status = "onboard"
    @Volatile private var driver = "Водитель А"
    @Volatile private var mutationCode = 200
    @Volatile private var contactsCode = 200
    private lateinit var publication: TaxiTripPublication
    private var language = AppLanguage.Ru
    private val safety get() = if (language == AppLanguage.Ru) "Безопасность" else "Именлек"
    private val share get() = if (language == AppLanguage.Ru) "Поделиться поездкой" else "Сәфәр менән бүлешеү"
    private val copy get() = if (language == AppLanguage.Ru) "Скопировать" else "Күсереү"
    private val send get() = if (language == AppLanguage.Ru) "Отправить" else "Ебәреү"
    private val revoke get() = if (language == AppLanguage.Ru) "Отозвать" else "Кире алыу"
    private val close get() = if (language == AppLanguage.Ru) "Закрыть" else "Ябыу"
    private val retry get() = if (language == AppLanguage.Ru) "Повторить" else "Ҡабатлау"
    private val loading get() = if (language == AppLanguage.Ru) "Загружаем близких…" else "Яҡындарҙы йөкләйбеҙ…"
    private val unknown get() = if (language == AppLanguage.Ru) "Не удалось проверить ссылку" else "Һылтанманы тикшереп булманы"
    private fun response(body: String, code: Int = 200) = MockResponse().setHeader("Content-Type", "application/json").setResponseCode(code).setBody(body)
    private fun order(id: Int = 91) = """{"id":$id,"status":"$status","role":"passenger","driver_name":"${if (id == 91) driver else "Водитель Б"}","driver_car":"Машина","driver_plate":"ТЕСТ","from_text":"Пункт А","to_text":"Пункт Б","price_estimate":250,"payment_method":"cash"}"""
    private fun row() = """{"id":501,"order_id":91,"contact_id":7,"token":"operation-91"}"""
    override fun customResponse(request: RecordedRequest, path: String): MockResponse? {
        val op = when {
            path == "/trusted-contacts" -> Op.CONTACTS
            path == "/instant/orders/91/shares" -> Op.SHARES
            path == "/instant/orders/91/share" && request.method == "POST" -> Op.CREATE
            path == "/instant/orders/91/share/501" && request.method == "DELETE" -> Op.REVOKE
            else -> null
        }
        // The local server commits mutations before its held reply; cancellation does not undo them.
        if (op == Op.CREATE) stored = true
        if (op == Op.REVOKE) stored = false
        if (op != null && op == heldOp) { held.countDown(); assertTrue(unblock.await(30, TimeUnit.SECONDS)) }
        return when {
            path.startsWith("/ws/instant/") -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.close(1008, "synthetic forbidden") }
            })
            path == "/instant/orders/mine" -> response("""{"items":[${order(if (request.getHeader("Authorization") == "Bearer $tokenB") 92 else 91)}]}""")
            path == "/instant/availability" -> response("""{"enabled":true}""")
            path == "/instant/orders/91" -> response(order())
            op == Op.CONTACTS -> response("""{"items":[{"id":7,"name":"Контакт А","relation":"Синтетический","phone":"+70000000007"}]}""", contactsCode)
            op == Op.SHARES -> response(if (stored) "[${row()}]" else "[]")
            op == Op.CREATE -> response(row(), mutationCode)
            op == Op.REVOKE -> response("{}", mutationCode)
            else -> null
        }
    }
    private suspend fun <T> observe(op: Op, generation: Long, api: suspend () -> Result<T>): Result<T> {
        val attempt = Attempt(op, generation, currentCoroutineContext()[Job]!!)
        attempt.job.invokeOnCompletion { attempt.completed.countDown() }; attempts += attempt
        return try { api() } finally { attempt.returned.set(true) }
    }
    private val operations = InstantShareOperations(
        contacts = { generation -> observe(Op.CONTACTS, generation) { TestContacts.read(owner = generation) } },
        create = { id, contact, generation ->
            assertEquals(91, id); assertEquals(7, contact)
            observe(Op.CREATE, generation) { ApiClient.shareInstantTrip(id, contact, expectedGeneration = generation) }
        },
        revoke = { id, shareId, generation ->
            assertEquals(91, id); assertEquals(501, shareId)
            observe(Op.REVOKE, generation) { ApiClient.revokeInstantShare(id, shareId, expectedGeneration = generation) }
        },
    )
    private fun visible(s: String) = compose.onAllNodesWithText(s).fetchSemanticsNodes().isNotEmpty()
    private fun attempt(op: Op): Attempt {
        await { attempts.any { it.op == op } }; return attempts.last { it.op == op }
    }
    private fun completed(a: Attempt) {
        await { a.job.isCompleted && a.completed.count == 0L }; assertTrue(a.returned.get()); pump(300)
    }
    private fun blocked(op: Op): Attempt {
        await { held.count == 0L }; val a = attempt(op)
        assertFalse(a.job.isCompleted); assertFalse(a.returned.get()); assertEquals(publication.generation, a.generation)
        if (op == Op.SHARES) assertTrue(attempt(Op.CONTACTS).returned.get())
        return a
    }
    private fun mount(lang: AppLanguage = AppLanguage.Ru) {
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
        val action = retain(hasContentDescription(safety)); compose.runOnIdle { dispatch(action) }; click(share)
    }
    private fun prepare(op: Op): List<() -> Boolean> {
        if (op == Op.CONTACTS || op == Op.SHARES) {
            stored = op == Op.SHARES; heldOp = op; mount(); return listOf(text(close))
        }
        mount(); completed(attempt(Op.SHARES)); val contact = text("Контакт А")
        if (op == Op.CREATE) { heldOp = op; click("Контакт А"); return listOf(contact, text(close)) }
        click("Контакт А"); completed(attempt(Op.CREATE)); await { visible(copy) }
        val actions = listOf(contact, text(copy), text(send), text(revoke), text(close))
        ShadowToast.reset(); heldOp = op; click(revoke); return actions
    }
    private fun loss(which: Loss) {
        compose.runOnIdle { when (which) {
            Loss.LEASE -> assertTrue(TaxiNavigationState.claimScreen(publication.generation, Any()))
            Loss.FINISHED -> assertTrue(TaxiNavigationState.finishTrip(publication))
            Loss.OWNER -> ApiClient.saveToken(tokenB)
            Loss.GUEST -> ApiClient.logout()
            Loss.DONE, Loss.CANCELLED -> {
                status = if (which == Loss.DONE) "done" else "cancelled"
                owner.registry.currentState = Lifecycle.State.RESUMED
            }
        } }
        when (which) {
            Loss.LEASE -> { await { !TaxiNavigationState.isScreenOwner(publication.generation, publication.publisher) }; assertSame(publication, TaxiNavigationState.currentTrip()) }
            Loss.FINISHED, Loss.DONE, Loss.CANCELLED -> {
                await { TaxiNavigationState.currentTrip() == null && !TaxiNavigationState.tripOnScreen() }
                assertTrue(TaxiNavigationState.isScreenOwner(publication.generation, publication.publisher))
                if (which != Loss.FINISHED) assertTrue(count("GET", "/orders/91") > 0)
            }
            else -> {
                await { !ApiClient.isCurrentSession(publication.generation) }
                if (which == Loss.OWNER) await { TaxiNavigationState.currentTrip()?.orderId == 92 }
            }
        }
        pump(500)
        if (which == Loss.LEASE) await { frames.lastOrNull()?.let { it.first === publication && !it.second } == true }
    }
    private fun noSideEffects() {
        assertFalse(clipboard.hasPrimaryClip()); assertNull(Shadows.shadowOf(app).nextStartedActivity)
        assertNull(ShadowToast.getTextOfLatestToast())
    }
    private fun counts(op: Op, shares: Int = if (op == Op.CONTACTS) 0 else 1) {
        assertEquals(if (op == Op.CREATE || op == Op.REVOKE) 1 else 0, count("POST", "/orders/91/share"))
        assertEquals(if (op == Op.REVOKE) 1 else 0, count("DELETE", "/share/501"))
        assertEquals(shares, count("GET", "/orders/91/shares"))
        assertFalse(records.any { (it[1] == "/trusted-contacts" || it[1]!!.startsWith("/instant/orders/91/share")) && it[2] == "Bearer $tokenB" })
        assertTrue(attempts.all { it.generation == publication.generation })
    }
    private fun late(op: Op, which: Loss) {
        val actions = prepare(op); val a = blocked(op); loss(which)
        val retained = which == Loss.LEASE || which == Loss.FINISHED
        if (retained) { assertFalse(a.job.isCompleted); assertFalse(a.returned.get()); assertTrue(visible("Водитель А")) }
        unblock.countDown(); completed(a); assertEquals(!retained, a.job.isCancelled)
        compose.runOnIdle { actions.forEach(::dispatch) }; pump(300)
        noSideEffects(); counts(op)
        if (retained) {
            if (op == Op.CONTACTS || op == Op.SHARES) { assertFalse(visible("Контакт А")); assertFalse(visible("Активные ссылки")) }
            if (which == Loss.LEASE) assertSame(publication, TaxiNavigationState.currentTrip())
            else assertNull(TaxiNavigationState.currentTrip())
        } else { assertFalse(visible(copy)); assertFalse(visible("Контакт А")) }
    }
    private fun leaseReturn(op: Op, failedMutation: Boolean = false) {
        val actions = prepare(op); val a = blocked(op)
        if (failedMutation) mutationCode = 503
        loss(Loss.LEASE)
        val lostFrame = frames.indexOfLast { it.first === publication && !it.second }; assertTrue(lostFrame >= 0)
        compose.runOnIdle { assertTrue(TaxiNavigationState.claimScreen(publication.generation, publication.publisher)) }
        await { TaxiNavigationState.isScreenOwner(publication.generation, publication.publisher) &&
            frames.drop(lostFrame + 1).any { it.first === publication && it.second } }
        assertSame(publication, frames.last().first); assertTrue(frames.last().second)
        pump(500); assertSame(publication, TaxiNavigationState.currentTrip()); assertFalse(a.job.isCompleted)
        unblock.countDown(); completed(a); assertFalse(a.job.isCancelled)
        noSideEffects(); counts(op)
        assertTrue("Retired request must require a fresh current read", visible(retry))
        if (op == Op.CONTACTS || op == Op.SHARES) { assertFalse(visible("Контакт А")); assertFalse(visible("Активные ссылки")) }
        else {
            assertTrue(visible(unknown)); assertFalse(visible(copy))
            // A held mutation is unknown, so its retained actions cannot duplicate it.
            compose.runOnIdle { actions.dropLast(1).forEach(::dispatch) }; pump(300); counts(op); noSideEffects()
        }
        val beforeReads = attempts.count { it.op == Op.SHARES }
        heldOp = null; click(retry)
        await { attempts.count { it.op == Op.SHARES } == beforeReads + 1 }
        val fresh = attempts.last { it.op == Op.SHARES }
        assertNotSame(a.job, fresh.job)
        completed(fresh); assertFalse(fresh.job.isCancelled); assertFalse(visible(retry))
        counts(op, if (op == Op.CONTACTS) 1 else 2)
        if (op == Op.CREATE) { assertTrue(visible(copy)); noSideEffects() }
        else if (op == Op.REVOKE) { assertFalse(visible(copy)); assertEquals("Ссылка отозвана", ShadowToast.getTextOfLatestToast()) }
        else assertTrue(visible("Контакт А"))
        click(close); await { !visible(share) && !visible(copy) }
    }
    private fun ordinary(op: Op) {
        prepare(op); val a = blocked(op)
        compose.runOnIdle { driver = "Водитель А обновлён"; owner.registry.currentState = Lifecycle.State.RESUMED }
        await { visible(driver) && count("GET", "/orders/91") > 0 }
        assertSame(publication, TaxiNavigationState.currentTrip()); assertTrue(TaxiNavigationState.isScreenOwner(publication.generation, publication.publisher))
        assertFalse(a.job.isCompleted); unblock.countDown(); completed(a); assertFalse(a.job.isCancelled); counts(op, 1)
        if (op == Op.CREATE) assertEquals("Ссылка готова: Контакт А", ShadowToast.getTextOfLatestToast())
        else if (op == Op.REVOKE) assertEquals("Ссылка отозвана", ShadowToast.getTextOfLatestToast())
        else { assertTrue(visible("Контакт А")); noSideEffects() }
        assertFalse(visible(retry)); click(close); await { !visible(share) && !visible(copy) }
    }
    private fun current(lang: AppLanguage) {
        mount(lang); completed(attempt(Op.SHARES)); click("Контакт А"); completed(attempt(Op.CREATE))
        assertEquals(if (lang == AppLanguage.Ru) "Ссылка готова: Контакт А" else "Һылтанма әҙер: Контакт А", ShadowToast.getTextOfLatestToast())
        click(copy); click(send); val url = ApiClient.apiBase() + "/t/operation-91"
        assertEquals(url, clipboard.primaryClip!!.getItemAt(0).text.toString())
        val chooser = Shadows.shadowOf(app).nextStartedActivity; assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertEquals((if (lang == AppLanguage.Ru) "Следи за моей поездкой в Юлдаш: " else "Юлдашта минең сәфәремде күҙәт: ") + url,
            chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!.getStringExtra(Intent.EXTRA_TEXT))
        clipboard.clearPrimaryClip(); ShadowToast.reset(); val oldCopy = text(copy); val oldSend = text(send)
        click(revoke); completed(attempt(Op.REVOKE))
        assertEquals(if (lang == AppLanguage.Ru) "Ссылка отозвана" else "Һылтанма кире алынды", ShadowToast.getTextOfLatestToast())
        assertFalse(visible(copy)); compose.runOnIdle { dispatch(oldCopy); dispatch(oldSend) }; pump(300)
        assertFalse(clipboard.hasPrimaryClip()); assertNull(Shadows.shadowOf(app).nextStartedActivity)
        counts(Op.REVOKE); click(close); await { !visible(share) && !visible(copy) }
    }
    private fun recoverContacts(lang: AppLanguage) {
        contactsCode = 503; mount(lang); completed(attempt(Op.CONTACTS))
        assertTrue(visible(retry)); assertFalse(visible(loading)); assertEquals(0, count("GET", "/orders/91/shares"))
        contactsCode = 200; click(retry); completed(attempt(Op.SHARES)); assertTrue(visible("Контакт А"))
        assertEquals(2, count("GET", "/trusted-contacts")); assertEquals(1, count("GET", "/orders/91/shares")); noSideEffects()
    }
    @After fun releaseHeld() { unblock.countDown() }
    @Test fun heldContactsAfterLeaseLoss() = late(Op.CONTACTS, Loss.LEASE)
    @Test fun heldContactsAfterFinishedReceipt() = late(Op.CONTACTS, Loss.FINISHED)
    @Test fun heldContactsAfterOwnerChange() = late(Op.CONTACTS, Loss.OWNER)
    @Test fun heldContactsAfterGuest() = late(Op.CONTACTS, Loss.GUEST)
    @Test fun heldContactsAfterPolledDone() = late(Op.CONTACTS, Loss.DONE)
    @Test fun heldContactsAfterPolledCancelled() = late(Op.CONTACTS, Loss.CANCELLED)
    @Test fun heldInitialSharesAfterLeaseLoss() = late(Op.SHARES, Loss.LEASE)
    @Test fun heldInitialSharesAfterFinishedReceipt() = late(Op.SHARES, Loss.FINISHED)
    @Test fun heldInitialSharesAfterOwnerChange() = late(Op.SHARES, Loss.OWNER)
    @Test fun heldInitialSharesAfterGuest() = late(Op.SHARES, Loss.GUEST)
    @Test fun heldInitialSharesAfterPolledDone() = late(Op.SHARES, Loss.DONE)
    @Test fun heldInitialSharesAfterPolledCancelled() = late(Op.SHARES, Loss.CANCELLED)
    @Test fun heldCreateAfterLeaseLoss() = late(Op.CREATE, Loss.LEASE)
    @Test fun heldCreateAfterFinishedReceipt() = late(Op.CREATE, Loss.FINISHED)
    @Test fun heldCreateAfterOwnerChange() = late(Op.CREATE, Loss.OWNER)
    @Test fun heldCreateAfterGuest() = late(Op.CREATE, Loss.GUEST)
    @Test fun heldCreateAfterPolledDone() = late(Op.CREATE, Loss.DONE)
    @Test fun heldCreateAfterPolledCancelled() = late(Op.CREATE, Loss.CANCELLED)
    @Test fun heldRevokeAfterLeaseLoss() = late(Op.REVOKE, Loss.LEASE)
    @Test fun heldRevokeAfterFinishedReceipt() = late(Op.REVOKE, Loss.FINISHED)
    @Test fun heldRevokeAfterOwnerChange() = late(Op.REVOKE, Loss.OWNER)
    @Test fun heldRevokeAfterGuest() = late(Op.REVOKE, Loss.GUEST)
    @Test fun heldRevokeAfterPolledDone() = late(Op.REVOKE, Loss.DONE)
    @Test fun heldRevokeAfterPolledCancelled() = late(Op.REVOKE, Loss.CANCELLED)
    @Test fun contactsReplyAfterAppliedLeaseReturnNeedsFreshLoad() = leaseReturn(Op.CONTACTS)
    @Test fun initialSharesReplyAfterAppliedLeaseReturnNeedsFreshLoad() = leaseReturn(Op.SHARES)
    @Test fun createReplyAfterAppliedLeaseReturnNeedsRecheck() = leaseReturn(Op.CREATE)
    @Test fun revokeReplyAfterAppliedLeaseReturnNeedsRecheck() = leaseReturn(Op.REVOKE)
    @Test fun failedCreateAfterAppliedLeaseReturnNeedsFreshRecheck() = leaseReturn(Op.CREATE, true)
    @Test fun failedRevokeAfterAppliedLeaseReturnNeedsFreshRecheck() = leaseReturn(Op.REVOKE, true)
    @Test fun ordinaryDtoKeepsInitialContactsCurrent() = ordinary(Op.CONTACTS)
    @Test fun ordinaryDtoKeepsInitialSharesCurrent() = ordinary(Op.SHARES)
    @Test fun ordinaryDtoKeepsCreateCurrent() = ordinary(Op.CREATE)
    @Test fun ordinaryDtoKeepsRevokeCurrent() = ordinary(Op.REVOKE)
    @Test fun currentRussianLoadsCreatesRevokesAndCloses() = current(AppLanguage.Ru)
    @Test fun currentBashkirLoadsCreatesRevokesAndCloses() = current(AppLanguage.Ba)
    @Test fun currentRussianContactsFailureCanRetry() = recoverContacts(AppLanguage.Ru)
    @Test fun currentBashkirContactsFailureCanRetry() = recoverContacts(AppLanguage.Ba)
}
