package com.yuldash.app

import android.app.Application
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.InstantOrderDto
import com.yuldash.app.data.TripShareDto
import com.yuldash.app.ui.theme.YuldashTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Actual trip → safety → share, actual API Job; synthetic DTO transitions and local HTTP. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TaxiTripShareCallerTest : ShareHelpBoundaryHarness() {
    private enum class Retire { DONE, CANCELLED, TARGET, OWNER, GUEST }
    private data class Read(val id: Int, val ordinal: Int, val generation: Long, val job: Job,
        val returned: AtomicBoolean = AtomicBoolean(false), val completed: CountDownLatch = CountDownLatch(1))
    private val order = mutableStateOf<InstantOrderDto?>(null)
    private val frames = CopyOnWriteArrayList<Pair<Int, String>>()
    private val reads = CopyOnWriteArrayList<Read>()
    private val ordinals = ConcurrentHashMap<Int, AtomicInteger>()
    private val stored = ConcurrentHashMap<Int, Boolean>()
    private val tokens = ConcurrentHashMap<Int, String>()
    private val held = CountDownLatch(1)
    private val unblock = CountDownLatch(1)
    @Volatile private var holdOrdinal = 0
    @Volatile private var createFails = true
    @Volatile private var listFails = false
    @Volatile private var commitDelete = true
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
    private fun row(id: Int) = """{"id":${410 + id},"order_id":$id,"contact_id":7,"token":"${tokens[id] ?: "stored-$id"}"}"""
    private fun response(body: String, code: Int = 200) = MockResponse().setHeader("Content-Type", "application/json").setResponseCode(code).setBody(body)
    override fun customResponse(request: RecordedRequest, path: String): MockResponse? {
        val match = Regex("/instant/orders/(\\d+)/(shares|share(?:/\\d+)?)").matchEntire(path) ?: return null
        val id = match.groupValues[1].toInt()
        return when (request.method) {
            "GET" -> {
                val ordinal = records.count { it[0] == "GET" && it[1] == path }
                if (id == 91 && ordinal == holdOrdinal) {
                    held.countDown(); assertTrue("Held HTTP response must be released", unblock.await(30, TimeUnit.SECONDS))
                }
                response(if (stored[id] == true) "[${row(id)}]" else "[]", if (ordinal > 1 && listFails) 503 else 200)
            }
            "POST" -> { stored[id] = true; tokens[id] = if (createFails) "late-$id" else "active-$id"; response(row(id), if (createFails) 503 else 200) }
            "DELETE" -> { if (commitDelete) stored[id] = false; response("{}", 503) }
            else -> null
        }
    }
    private suspend fun observedRead(id: Int, generation: Long): Result<List<TripShareDto>> {
        // Observe the real component caller, with no wrapper Job or coroutineScope.
        val read = Read(id, ordinals.computeIfAbsent(id) { AtomicInteger() }.incrementAndGet(), generation, currentCoroutineContext()[Job]!!)
        read.job.invokeOnCompletion { read.completed.countDown() }; reads += read
        return try { ApiClient.getInstantShares(id, expectedGeneration = generation) }
        finally { read.returned.set(true) }
    }
    private fun visible(label: String) = compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()
    private fun read(ordinal: Int, id: Int = 91): Read {
        await { reads.any { it.id == id && it.ordinal == ordinal } }
        return reads.single { it.id == id && it.ordinal == ordinal }
    }
    private fun completed(read: Read) {
        await { read.job.isCompleted && read.completed.count == 0L }
        assertTrue(read.returned.get()); pump(300)
    }
    private fun blocked(): Read {
        await { held.count == 0L }; val read = read(holdOrdinal)
        assertFalse(read.job.isCompleted); assertFalse(read.returned.get())
        assertEquals(ApiClient.queueSessionGeneration(), read.generation)
        assertEquals(holdOrdinal, count("GET", "/orders/91/shares")); return read
    }
    @Composable private fun FrameProbe() {
        val shown = order.value!!
        SideEffect { frames += shown.id to shown.status }
    }
    private fun mount(lang: AppLanguage = AppLanguage.Ru) {
        language = lang; order.value = runBlocking { ApiClient.getInstantOrder(91).getOrThrow() }
        compose.mainClock.autoAdvance = false
        compose.setContent { if (mounted.value) key(slot.value) {
            FrameProbe()
            CompositionLocalProvider(LocalAppLanguage provides language) { YuldashTheme {
                TaxiTripScreen(order.value!!, onCancel = {}, onMinimize = {}, enableLiveTracking = false,
                    mapContent = { Box(it) }, readShares = ::observedRead)
            } }
        } }
        pump(); compose.onNodeWithTag("taxiSheetDragArea").performTouchInput { swipe(center, center.copy(y = center.y - 160f), 300) }; pump()
    }
    private fun openShare() {
        val action = retain(hasContentDescription(safety)); compose.runOnIdle { dispatch(action) }
        click(share)
    }
    private fun start(revoking: Boolean, retrying: Boolean): List<() -> Boolean> {
        holdOrdinal = if (retrying) 3 else 2; listFails = retrying
        mount(); openShare(); completed(read(1)); val contact = text("Контакт А")
        val actions: List<() -> Boolean>
        if (revoking) {
            createFails = false; click("Контакт А"); await { visible(copy) }; pump(300)
            actions = listOf(contact, text(copy), text(send), text(more), text(revoke), text(close))
            ShadowToast.reset(); click(revoke)
        } else {
            actions = listOf(contact, text(close)); click("Контакт А")
        }
        if (retrying) { completed(read(2)); assertTrue(visible(retry)); listFails = false; click(retry) }
        return actions
    }
    private fun retire(which: Retire) {
        compose.runOnIdle { when (which) {
            Retire.DONE -> order.value = order.value!!.copy(status = "done")
            Retire.CANCELLED -> order.value = order.value!!.copy(status = "cancelled")
            Retire.TARGET -> order.value = order.value!!.copy(id = 92)
            Retire.OWNER -> ApiClient.saveToken(tokenB)
            Retire.GUEST -> ApiClient.logout()
        } }
        if (which in setOf(Retire.DONE, Retire.CANCELLED, Retire.TARGET)) {
            val expected = order.value!!.let { it.id to it.status }
            await { frames.lastOrNull() == expected }
        }
        pump(300)
    }
    private fun noSideEffects() {
        assertFalse(clipboard.hasPrimaryClip()); assertNull(Shadows.shadowOf(app).nextStartedActivity)
        assertNull(ShadowToast.getTextOfLatestToast())
    }
    private fun counts(revoking: Boolean, gets: Int) {
        assertEquals(1, count("POST", "/orders/91/share")); assertEquals(if (revoking) 1 else 0, count("DELETE", "/share/501"))
        assertEquals(gets, count("GET", "/orders/91/shares"))
        assertFalse(records.any { it[2] == "Bearer $tokenB" })
    }
    private fun routes(id: Int, token: String) {
        assertTrue(visible(copy)); click(copy); click(send)
        val url = ApiClient.apiBase() + "/t/" + token
        assertEquals(url, clipboard.primaryClip!!.getItemAt(0).text.toString())
        val chooser = Shadows.shadowOf(app).nextStartedActivity; assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val payload = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, payload.action); assertTrue(payload.getStringExtra(Intent.EXTRA_TEXT)!!.endsWith(url))
        assertNull(Shadows.shadowOf(app).nextStartedActivity)
        assertEquals(id, records.last { it[0] == "POST" && it[1]!!.endsWith("/share") }[1]!!.split('/')[3].toInt())
    }
    private fun late(which: Retire, revoking: Boolean, retrying: Boolean) {
        val actions = start(revoking, retrying); val old = blocked(); retire(which)
        if (which != Retire.TARGET) { assertFalse(old.job.isCompleted); assertFalse(old.returned.get()) }
        unblock.countDown(); completed(old)
        assertEquals(which == Retire.TARGET, old.job.isCancelled)
        compose.runOnIdle { actions.forEach(::dispatch) }; pump(300)
        noSideEffects(); assertFalse(visible(copy)); counts(revoking, holdOrdinal)
        if (which == Retire.TARGET) {
            assertFalse(visible(title)); assertFalse(visible(share)); assertEquals(0, count("GET", "/orders/92/shares"))
            openShare(); completed(read(1, 92)); val freshContact = text("Контакт А")
            compose.runOnIdle { actions.forEach(::dispatch) }; pump(300)
            assertTrue(visible(share)); noSideEffects(); counts(revoking, holdOrdinal)
            assertEquals(1, count("GET", "/orders/92/shares")); assertEquals(0, count("POST", "/orders/92/share"))
            createFails = false; compose.runOnIdle { dispatch(freshContact) }; await { visible(copy) }
            routes(92, "active-92"); assertEquals(1, count("POST", "/orders/92/share"))
        } else {
            assertTrue("Old terminal close must not dismiss the pending dialog", visible(if (revoking) title else share))
            assertTrue("Terminal dialog must keep its unknown result", visible(retry))
        }
    }
    private fun phaseReturn(revoking: Boolean, retrying: Boolean) {
        commitDelete = false
        val actions = start(revoking, retrying).dropLast(1); val old = blocked()
        retire(Retire.DONE); assertFalse(old.job.isCompleted); assertFalse(old.returned.get())
        compose.runOnIdle { order.value = order.value!!.copy(status = "onboard") }
        await { frames.lastOrNull() == (91 to "onboard") }; pump(300)
        assertFalse(old.job.isCompleted); assertFalse(old.returned.get())
        unblock.countDown(); completed(old); assertFalse(old.job.isCancelled)
        assertTrue("Returned caller must require a fresh read", visible(retry)); assertFalse(visible(copy)); noSideEffects()
        compose.runOnIdle { actions.forEach(::dispatch) }; pump(300); noSideEffects(); counts(revoking, holdOrdinal)
        click(retry); val fresh = read(holdOrdinal + 1); completed(fresh)
        assertNotSame(old.job, fresh.job); assertFalse(fresh.job.isCancelled); assertFalse(visible(retry))
        if (revoking) assertNotNull(ShadowToast.getTextOfLatestToast()) else assertNull(ShadowToast.getTextOfLatestToast())
        routes(91, if (revoking) "active-91" else "late-91"); counts(revoking, holdOrdinal + 1)
    }
    private fun oldAction(which: Retire, fromSafety: Boolean) {
        mount(); val safetyAction = retain(hasContentDescription(safety)); compose.runOnIdle { dispatch(safetyAction) }
        val action = if (fromSafety) text(share) else { click(share); completed(read(1)); text("Контакт А") }
        ShadowToast.reset(); val gets = count("GET", "/orders/91/shares"); val contacts = count("GET", "/trusted-contacts")
        retire(which); compose.runOnIdle { dispatch(action) }; pump(300)
        noSideEffects(); assertEquals(0, count("POST", "/share")); assertEquals(0, count("DELETE", "/share/501"))
        assertEquals(gets, count("GET", "/orders/91/shares")); assertEquals(contacts, count("GET", "/trusted-contacts"))
        assertEquals(0, count("GET", "/orders/92/shares")); assertFalse(visible(copy))
        assertFalse(records.any { it[2] == "Bearer $tokenB" })
    }
    private fun current(lang: AppLanguage) {
        createFails = false; mount(lang); openShare(); completed(read(1)); click("Контакт А"); await { visible(copy) }; pump(300)
        assertEquals(if (lang == AppLanguage.Ru) "Ссылка готова: Контакт А" else "Һылтанма әҙер: Контакт А", ShadowToast.getTextOfLatestToast())
        routes(91, "active-91"); counts(false, 1)
        click(close); await { !visible(title) && !visible(share) }; assertFalse(visible(copy))
    }
    @After fun releaseHeld() { unblock.countDown() }
    @Test fun currentRussianScreenSharesAndCloses() = current(AppLanguage.Ru)
    @Test fun currentBashkirScreenSharesAndCloses() = current(AppLanguage.Ba)
    @Test fun doneRetiresSafetyShare() = oldAction(Retire.DONE, true)
    @Test fun cancelledRetiresSafetyShare() = oldAction(Retire.CANCELLED, true)
    @Test fun replacementRetiresSafetyShare() = oldAction(Retire.TARGET, true)
    @Test fun ownerChangeRetiresSafetyShare() = oldAction(Retire.OWNER, true)
    @Test fun guestRetiresSafetyShare() = oldAction(Retire.GUEST, true)
    @Test fun doneRetiresContact() = oldAction(Retire.DONE, false)
    @Test fun cancelledRetiresContact() = oldAction(Retire.CANCELLED, false)
    @Test fun replacementRetiresContact() = oldAction(Retire.TARGET, false)
    @Test fun ownerChangeRetiresContact() = oldAction(Retire.OWNER, false)
    @Test fun guestRetiresContact() = oldAction(Retire.GUEST, false)
    @Test fun heldCreateReadAfterDone() = late(Retire.DONE, false, false)
    @Test fun heldCreateReadAfterCancelled() = late(Retire.CANCELLED, false, false)
    @Test fun heldCreateReadAfterReplacement() = late(Retire.TARGET, false, false)
    @Test fun heldRevokeReadAfterDone() = late(Retire.DONE, true, false)
    @Test fun heldRevokeReadAfterCancelled() = late(Retire.CANCELLED, true, false)
    @Test fun heldRevokeReadAfterReplacement() = late(Retire.TARGET, true, false)
    @Test fun heldCreateRetryAfterDone() = late(Retire.DONE, false, true)
    @Test fun heldCreateRetryAfterCancelled() = late(Retire.CANCELLED, false, true)
    @Test fun heldCreateRetryAfterReplacement() = late(Retire.TARGET, false, true)
    @Test fun heldRevokeRetryAfterDone() = late(Retire.DONE, true, true)
    @Test fun heldRevokeRetryAfterCancelled() = late(Retire.CANCELLED, true, true)
    @Test fun heldRevokeRetryAfterReplacement() = late(Retire.TARGET, true, true)
    @Test fun phaseReturnsBeforeCreateRead() = phaseReturn(false, false)
    @Test fun phaseReturnsBeforeRevokeRead() = phaseReturn(true, false)
    @Test fun phaseReturnsBeforeCreateRetry() = phaseReturn(false, true)
    @Test fun phaseReturnsBeforeRevokeRetry() = phaseReturn(true, true)
}
