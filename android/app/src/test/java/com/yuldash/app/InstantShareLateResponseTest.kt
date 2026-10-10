package com.yuldash.app

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.test.*
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.TripShareDto
import com.yuldash.app.ui.theme.YuldashTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
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
import java.util.concurrent.atomic.AtomicReference

/** Actual component + actual loopback API; caller Job completion precedes late UI assertions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InstantShareLateResponseTest : ShareHelpBoundaryHarness() {
    private enum class Retire { OWNER, GUEST, PARENT, TARGET, CLOSE, DISPOSE }
    private data class Read(
        val orderId: Int, val ordinal: Int, val generation: Long, val job: Job,
        val returned: AtomicBoolean = AtomicBoolean(false),
        val completed: CountDownLatch = CountDownLatch(1),
        val cause: AtomicReference<String?> = AtomicReference(null),
    )
    private data class Retained(val actions: List<() -> Boolean>, val close: () -> Boolean)
    private val reads = CopyOnWriteArrayList<Read>()
    private val ordinals = ConcurrentHashMap<Int, AtomicInteger>()
    private val stored = ConcurrentHashMap<Int, Boolean>()
    private val tokens = ConcurrentHashMap<Int, String>()
    private val held = CountDownLatch(1)
    private val unblock = CountDownLatch(1)
    @Volatile private var holdOrdinal = 2
    @Volatile private var createFails = true
    @Volatile private var deleteFails = true
    @Volatile private var commitDelete = true
    @Volatile private var listFails = false
    private var language = AppLanguage.Ru
    private val copyLabel get() = if (language == AppLanguage.Ru) "Скопировать" else "Күсереү"
    private val sendLabel get() = if (language == AppLanguage.Ru) "Отправить" else "Ебәреү"
    private val moreLabel get() = if (language == AppLanguage.Ru) "Поделиться ещё" else "Йәнә бүлешеү"
    private val revokeLabel get() = if (language == AppLanguage.Ru) "Отозвать" else "Кире алыу"
    private val closeLabel get() = if (language == AppLanguage.Ru) "Закрыть" else "Ябыу"
    private val retryLabel get() = if (language == AppLanguage.Ru) "Повторить" else "Ҡабатлау"
    private val titleLabel get() = if (language == AppLanguage.Ru) "Ссылка для близкого" else "Яҡын кеше өсөн һылтанма"
    private fun row(id: Int) = """{"id":${410 + id},"contact_id":7,"order_id":$id,"token":"${tokens[id] ?: "stored-$id"}"}"""
    override fun customResponse(request: RecordedRequest, path: String): MockResponse? {
        val match = Regex("/instant/orders/(\\d+)/(shares|share(?:/\\d+)?)").matchEntire(path) ?: return null
        val id = match.groupValues[1].toInt()
        if (request.method == "GET") {
            val ordinal = records.count { it[0] == "GET" && it[1] == path }
            if (id == 91 && ordinal == holdOrdinal) {
                held.countDown(); assertTrue("HTTP gate was not released", unblock.await(30, TimeUnit.SECONDS))
            }
            return response(if (stored[id] == true) "[${row(id)}]" else "[]", if (ordinal > 1 && listFails) 503 else 200)
        }
        if (request.method == "POST") {
            stored[id] = true; tokens[id] = if (createFails) "late-$id" else "active-$id"
            return response(row(id), if (createFails) 503 else 200)
        }
        if (request.method == "DELETE") {
            if (commitDelete) stored[id] = false
            return response("""{"ok":true}""", if (deleteFails) 503 else 200)
        }
        return null
    }
    private fun response(body: String, code: Int) = MockResponse().setHeader("Content-Type", "application/json").setResponseCode(code).setBody(body)
    private suspend fun observedRead(id: Int, generation: Long): Result<List<TripShareDto>> {
        // No extra coroutineScope/withContext: this is the actual action's caller Job.
        val read = Read(id, ordinals.computeIfAbsent(id) { AtomicInteger() }.incrementAndGet(), generation, currentCoroutineContext()[Job]!!)
        read.job.invokeOnCompletion { cause -> read.cause.set(cause?.javaClass?.simpleName ?: "normal"); read.completed.countDown() }
        reads += read
        return try { ApiClient.getInstantShares(id, expectedGeneration = generation) }
        finally { read.returned.set(true) }
    }
    private fun shown(s: String) = compose.onAllNodesWithText(s).fetchSemanticsNodes().isNotEmpty()
    private fun read(ordinal: Int): Read {
        await { reads.any { it.orderId == 91 && it.ordinal == ordinal } }
        return reads.single { it.orderId == 91 && it.ordinal == ordinal }
    }
    private fun completed(read: Read) {
        await { read.job.isCompleted && read.completed.count == 0L }
        assertTrue("Actual API read must have returned/cancelled", read.returned.get())
        assertNotNull(read.cause.get())
        pump(300) // Apply UI only after the Job and its component finally have completed.
        compose.waitForIdle()
    }
    private fun blocked(ordinal: Int): Read {
        await { held.count == 0L }
        val read = read(ordinal)
        assertFalse("Held caller must still be unfinished", read.job.isCompleted)
        assertFalse(read.returned.get())
        assertEquals(ApiClient.queueSessionGeneration(), read.generation)
        assertEquals(ordinal, count("GET", "/orders/91/shares"))
        return read
    }
    private fun finish(read: Read) { unblock.countDown(); completed(read) }
    private fun mount(lang: AppLanguage = AppLanguage.Ru) {
        language = lang; compose.mainClock.autoAdvance = false
        compose.setContent { if (mounted.value) key(slot.value) {
            val renderedId = target.value
            CompositionLocalProvider(LocalAppLanguage provides language) { YuldashTheme {
                InstantShareDialog(renderedId, { dismisses++ },
                    isCurrentParent = { parentCurrent.value && target.value == renderedId }, readShares = ::observedRead)
            } }
        } }
        completed(read(1)); text("Контакт А")
    }
    private fun create(): Retained {
        mount(); val retained = Retained(listOf(text("Контакт А")), text(closeLabel))
        click("Контакт А"); return retained
    }
    private fun existing(): Retained {
        createFails = false; mount(); click("Контакт А"); await { shown(copyLabel) }; pump(300)
        val retained = Retained(listOf(text(copyLabel), text(sendLabel), text(moreLabel), text(revokeLabel)), text(closeLabel))
        ShadowToast.reset(); click(revokeLabel); return retained
    }
    private fun retire(which: Retire, retained: Retained) {
        compose.runOnIdle { when (which) {
            Retire.OWNER -> ApiClient.saveToken(tokenB)
            Retire.GUEST -> ApiClient.logout()
            Retire.PARENT -> parentCurrent.value = false
            Retire.TARGET -> target.value = 92
            Retire.CLOSE -> dispatch(retained.close)
            Retire.DISPOSE -> slot.value++
        } }
        pump(300)
    }
    private fun assertRetired(which: Retire, retained: Retained, read: Read, revoke: Boolean) {
        completed(read)
        compose.runOnIdle { retained.actions.forEach { dispatch(it) }; dispatch(retained.close) }; pump(300)
        assertFalse("Old response must not publish a live link", shown(copyLabel))
        assertNull("Old response must not publish any toast", ShadowToast.getTextOfLatestToast())
        assertFalse(clipboard.hasPrimaryClip())
        assertNull("No external chooser from retained action", Shadows.shadowOf(app).nextStartedActivity)
        assertEquals(if (which == Retire.CLOSE) 1 else 0, dismisses)
        assertEquals(1, count("POST", "/share"))
        assertEquals(if (revoke) 1 else 0, count("DELETE", "/share/501"))
        assertFalse(records.any { it[2] == "Bearer $tokenB" })
        if (which == Retire.TARGET || which == Retire.DISPOSE) assertTrue("Disposed action must be cancelled", read.job.isCancelled)
    }
    private fun late(which: Retire, revoke: Boolean) {
        val retained = if (revoke) existing() else create()
        val read = blocked(2); retire(which, retained); assertFalse(read.job.isCompleted)
        finish(read); assertRetired(which, retained, read, revoke)
        if (which == Retire.PARENT) {
            compose.runOnIdle { parentCurrent.value = true }; pump(300); click(retryLabel)
            val fresh = read(3); completed(fresh); assertNotSame(read.job, fresh.job)
            assertEquals(!revoke, shown(copyLabel)); assertEquals(1, count("POST", "/share"))
        }
    }
    private fun lateRetry(which: Retire) {
        holdOrdinal = 3; listFails = true; val retained = create(); completed(read(2)); assertTrue(shown(retryLabel))
        listFails = false; val retry = text(retryLabel); click(retryLabel); val read = blocked(3)
        retire(which, retained); assertFalse(read.job.isCompleted); finish(read)
        assertRetired(which, retained.copy(actions = retained.actions + retry), read, false)
    }
    private fun currentRoutes(expectedToken: String) {
        assertTrue(shown(copyLabel)); click(copyLabel); click(sendLabel)
        val url = ApiClient.apiBase() + "/t/" + expectedToken
        assertEquals(url, clipboard.primaryClip!!.getItemAt(0).text.toString())
        val chooser = Shadows.shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action); assertTrue(send.getStringExtra(Intent.EXTRA_TEXT)!!.endsWith(url))
        assertNull(Shadows.shadowOf(app).nextStartedActivity)
    }
    private fun pendingRoutes(hold: Boolean, lang: AppLanguage = AppLanguage.Ru) {
        language = lang; createFails = false; commitDelete = false
        if (!hold) { holdOrdinal = 0; listFails = true }
        // The initial GET must succeed before we fail only the reconciliation read.
        listFails = false; mount(lang); click("Контакт А"); await { shown(copyLabel) }; pump(300)
        val actions = listOf(text(copyLabel), text(sendLabel), text(moreLabel), text(revokeLabel))
        ShadowToast.reset(); if (!hold) listFails = true; click(revokeLabel)
        val read = if (hold) blocked(2) else read(2).also { completed(it); assertTrue(shown(retryLabel)) }
        compose.runOnIdle { actions.forEach { dispatch(it) } }; pump(300)
        assertFalse(clipboard.hasPrimaryClip()); assertNull(Shadows.shadowOf(app).nextStartedActivity)
        assertEquals(1, count("DELETE", "/share/501")); assertEquals(1, count("POST", "/share"))
        assertTrue("Retained share-more must not clear the pending link", shown(titleLabel))
        if (hold) finish(read) else { listFails = false; click(retryLabel); completed(read(3)) }
        assertTrue("Recheck preserved record must restore the same link", shown(copyLabel))
        currentRoutes("active-91")
    }
    @After fun releaseRead() { unblock.countDown() }
    @Test fun heldCreateAfterOwnerChange() = late(Retire.OWNER, false)
    @Test fun heldCreateAfterGuest() = late(Retire.GUEST, false)
    @Test fun heldCreateAfterParentRetirement() = late(Retire.PARENT, false)
    @Test fun heldCreateAfterTargetChange() = late(Retire.TARGET, false)
    @Test fun heldCreateAfterClose() = late(Retire.CLOSE, false)
    @Test fun heldCreateAfterDisposeAndRemount() = late(Retire.DISPOSE, false)
    @Test fun heldRevokeAfterOwnerChange() = late(Retire.OWNER, true)
    @Test fun heldRevokeAfterGuest() = late(Retire.GUEST, true)
    @Test fun heldRevokeAfterParentRetirement() = late(Retire.PARENT, true)
    @Test fun heldRevokeAfterTargetChange() = late(Retire.TARGET, true)
    @Test fun heldRevokeAfterClose() = late(Retire.CLOSE, true)
    @Test fun heldRevokeAfterDisposeAndRemount() = late(Retire.DISPOSE, true)
    @Test fun heldRetryAfterOwnerChange() = lateRetry(Retire.OWNER)
    @Test fun heldRetryAfterGuest() = lateRetry(Retire.GUEST)
    @Test fun heldRetryAfterParentRetirement() = lateRetry(Retire.PARENT)
    @Test fun heldRetryAfterTargetChange() = lateRetry(Retire.TARGET)
    @Test fun heldRetryAfterClose() = lateRetry(Retire.CLOSE)
    @Test fun heldRetryAfterDisposeAndRemount() = lateRetry(Retire.DISPOSE)
    @Test fun currentHeldCreateCompletesAndRoutes() { create(); val read = blocked(2); finish(read); assertFalse(read.job.isCancelled); assertNull(ShadowToast.getTextOfLatestToast()); currentRoutes("late-91") }
    @Test fun currentHeldRevokeCompletesAndRetiresLink() { val retained = existing(); val read = blocked(2); finish(read); assertFalse(read.job.isCancelled); assertFalse(shown(copyLabel)); assertFalse(shown(revokeLabel)); assertEquals("Ссылка отозвана", ShadowToast.getTextOfLatestToast()); compose.runOnIdle { retained.actions.take(3).forEach { dispatch(it) } }; pump(300); assertFalse(clipboard.hasPrimaryClip()); assertNull(Shadows.shadowOf(app).nextStartedActivity) }
    @Test fun currentHeldRetryCreateRecoversWithoutNewMutation() { holdOrdinal = 3; listFails = true; create(); completed(read(2)); listFails = false; click(retryLabel); val read = blocked(3); finish(read); currentRoutes("late-91"); assertEquals(1, count("POST", "/share")); assertEquals(3, count("GET", "/orders/91/shares")) }
    @Test fun currentHeldRetryRevokePreservesLinkAndManualRetry() {
        holdOrdinal = 3; commitDelete = false; listFails = true; existing(); completed(read(2)); assertTrue(shown(retryLabel))
        listFails = false; click(retryLabel); val read = blocked(3); finish(read); assertFalse(read.job.isCancelled)
        currentRoutes("active-91"); commitDelete = true; deleteFails = false; click(revokeLabel)
        await { !shown(copyLabel) && !shown(revokeLabel) }; assertEquals(2, count("DELETE", "/share/501"))
    }
    @Test fun pendingHeldRoutesDoNotCopySendOrClearLink() = pendingRoutes(true)
    @Test fun pendingFailedReadRoutesDoNotCopySendOrClearLink() = pendingRoutes(false)
    @Test fun bashkirPendingRoutesRecoverThePreservedLink() = pendingRoutes(false, AppLanguage.Ba)
    @Test fun duplicateRetryWhileHeldReadsOnce() { holdOrdinal = 3; listFails = true; create(); completed(read(2)); listFails = false; val retry = text(retryLabel); compose.runOnIdle { dispatch(retry); dispatch(retry) }; val read = blocked(3); assertEquals(3, count("GET", "/orders/91/shares")); finish(read); assertTrue(shown(copyLabel)); assertEquals(1, count("POST", "/share")) }
}
