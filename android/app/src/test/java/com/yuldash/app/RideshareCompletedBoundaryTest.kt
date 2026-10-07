package com.yuldash.app

import android.content.Context
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** Real Compose callbacks; deliberately non-cancellable dependency models a result racing disposal. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RideshareCompletedBoundaryTest {
    @get:Rule val compose = createComposeRule()
    private val mounted = mutableStateOf(true)
    private val booking = mutableStateOf(42)
    private val calls = mutableListOf<Pair<String, Int>>()
    private var closes = 0
    private var receipts = 0
    private var supports = 0
    private var held: String? = null
    private var pending: Continuation<Result<Unit>>? = null
    private var scope: CoroutineScope? = null
    private var actionJob: Job? = null
    private val tokenA = "header.eyJzdWIiOiIxMSJ9.signature"
    private val tokenB = "header.eyJzdWIiOiIzMyJ9.signature"

    @Before fun prepare() {
        ApiClient.resetForTest()
        ApiClient.init(ApplicationProvider.getApplicationContext<Context>())
        ApiClient.saveToken(tokenA)
    }
    @After fun cleanup() {
        pending?.resume(Result.success(Unit)); pending = null
        compose.runOnIdle { mounted.value = false }
        ApiClient.resetForTest()
    }
    private suspend fun action(kind: String, id: Int): Result<Unit> {
        calls += kind to id
        return if (held == kind && id == 42) suspendCoroutine { pending = it } else Result.success(Unit)
    }
    private fun mount() {
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                RideshareCompletedScreen(booking.value, null, "passenger", "cash", 400,
                    onClose = { closes++ }, onOpenReceipt = { receipts++ }, onSupport = { supports++ },
                    loadReceipt = { id -> Result.success(TripReceiptDto(id, 9, "passenger", "Уфа", "Бирск",
                        "2026-10-07T10:00:00", 1, 400, "cash", true, "Водитель", true, "Водитель", 0, "")) },
                    rateBooking = { id, stars, _, _ -> assertEquals(4, stars); action("rating", id) },
                    loadThanks = { Result.success(TipInfoDto("Водитель", false, null)) },
                    sendThanks = { action("thanks", it) }, openLostItem = { action("lost", it) })
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("rideshareStar4").performScrollTo().performClick()
    }
    private fun node(kind: String): SemanticsNodeInteraction {
        val matcher = when (kind) {
            "thanks" -> hasText("Сказать «Рәхмәт»")
            "lost" -> hasTestTag("rideshareLostItem")
            "receipt" -> hasText("Квитанция")
            "support" -> hasText("Нужна помощь?")
            else -> null
        }
        if (matcher != null) compose.onNodeWithTag("rideshareCompletedList").performScrollToNode(matcher)
        return when (kind) {
        "rating" -> compose.onNodeWithTag("rideshareRatingSubmit")
        "thanks" -> compose.onNodeWithText("Сказать «Рәхмәт»")
        "lost" -> compose.onNodeWithTag("rideshareLostItem")
        "receipt" -> compose.onNodeWithText("Квитанция")
        "support" -> compose.onNodeWithText("Нужна помощь?")
        else -> error(kind)
        }
    }
    private fun click(kind: String): () -> Boolean {
        val n = node(kind)
        return n.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
    }
    private fun boundary(kind: String) {
        compose.runOnIdle {
            when (kind) {
                "unmount" -> mounted.value = false
                "booking" -> booking.value = 43
                "session" -> ApiClient.saveToken(tokenB)
                else -> error(kind)
            }
        }
        compose.waitForIdle()
    }
    private fun retained(kind: String, end: String) {
        mount(); val old = click(kind)
        boundary(end)
        val before = Triple(closes, receipts, supports)
        compose.runOnIdle { old() }
        compose.waitForIdle()
        assertEquals("Disposed navigation callback was invoked", before, Triple(closes, receipts, supports))
        assertTrue("Old action reached dependency: $calls", calls.isEmpty())
        if (end == "booking") {
            // New booking still accepts its own receipt action.
            node("receipt").performScrollTo().performClick()
            assertEquals(before.second + 1, receipts)
        }
    }
    private fun late(kind: String, end: String, failure: Boolean = false) {
        held = kind; mount()
        val old = click(kind)
        scope = CompletedBoundaryProbe.captures(old, CoroutineScope::class.java).single()
        val beforeJobs = scope!!.coroutineContext[Job]!!.children.toSet()
        node(kind).performClick()
        compose.waitUntil(5000) { pending != null }
        actionJob = scope!!.coroutineContext[Job]!!.children.single { it !in beforeJobs }
        boundary(end)
        // Inspect abandoned state too: merely hiding its screen does not prove the callback was rejected.
        val states = CompletedBoundaryProbe.captures(old, MutableState::class.java).associateWith { it.value }
        pending!!.resume(if (failure) Result.failure(IllegalStateException("late-private-error")) else Result.success(Unit))
        pending = null
        compose.waitUntil(5000) { Shadows.shadowOf(Looper.getMainLooper()).idle(); actionJob!!.isCompleted }
        compose.waitForIdle()
        assertEquals("Late result wrote to disposed state", states, states.keys.associateWith { it.value })
        assertEquals(listOf(kind to 42), calls)
        if (end == "booking") {
            compose.onNodeWithText("late-private-error", substring = true).assertDoesNotExist()
            compose.onNodeWithTag("rideshareStar4").performScrollTo().performClick()
            node(kind).performClick()
            compose.waitUntil(5000) { calls.contains(kind to 43) }
        }
    }
    @Test fun ordinaryCompletedActionsRemainAvailable() {
        mount()
        node("receipt").performScrollTo().performClick(); assertEquals(1, receipts)
        node("support").performScrollTo().performClick(); assertEquals(1, supports)
        node("thanks").performScrollTo().performClick()
        node("lost").performScrollTo().performClick()
        node("rating").performClick()
        assertEquals(listOf("thanks" to 42, "lost" to 42, "rating" to 42), calls)
        node("rating").performClick(); assertEquals(1, closes)
    }
    @Test fun retainedReceiptAfterUnmount() = retained("receipt", "unmount")
    @Test fun retainedReceiptAfterBookingChange() = retained("receipt", "booking")
    @Test fun retainedReceiptAfterSessionChange() = retained("receipt", "session")
    @Test fun retainedSupportAfterUnmount() = retained("support", "unmount")
    @Test fun retainedSupportAfterBookingChange() = retained("support", "booking")
    @Test fun retainedSupportAfterSessionChange() = retained("support", "session")
    @Test fun retainedRatingAfterUnmount() = retained("rating", "unmount")
    @Test fun retainedRatingAfterBookingChange() = retained("rating", "booking")
    @Test fun retainedRatingAfterSessionChange() = retained("rating", "session")
    @Test fun retainedThanksAfterUnmount() = retained("thanks", "unmount")
    @Test fun retainedThanksAfterBookingChange() = retained("thanks", "booking")
    @Test fun retainedThanksAfterSessionChange() = retained("thanks", "session")
    @Test fun retainedLostItemAfterUnmount() = retained("lost", "unmount")
    @Test fun retainedLostItemAfterBookingChange() = retained("lost", "booking")
    @Test fun retainedLostItemAfterSessionChange() = retained("lost", "session")
    @Test fun lateRatingAfterUnmount() = late("rating", "unmount")
    @Test fun lateRatingAfterBookingChange() = late("rating", "booking")
    @Test fun lateRatingErrorAfterSessionChange() = late("rating", "session", true)
    @Test fun lateThanksAfterUnmount() = late("thanks", "unmount")
    @Test fun lateThanksAfterBookingChange() = late("thanks", "booking")
    @Test fun lateThanksErrorAfterSessionChange() = late("thanks", "session", true)
    @Test fun lateLostItemAfterUnmount() = late("lost", "unmount")
    @Test fun lateLostItemAfterBookingChange() = late("lost", "booking")
    @Test fun lateLostItemErrorAfterSessionChange() = late("lost", "session", true)
}

internal object CompletedBoundaryProbe {
    fun <T> captures(root: Any, type: Class<T>): List<T> {
        val seen = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        val found = mutableListOf<T>()
        fun visit(value: Any?, depth: Int) {
            if (value == null || depth > 10 || !seen.add(value)) return
            if (type.isInstance(value)) { found += type.cast(value); return }
            if (value is MutableState<*>) { visit(value.value, depth + 1); return }
            if (value !is kotlin.Function<*> && !value.javaClass.name.contains("Clickable")) return
            generateSequence(value.javaClass as Class<*>?) { it.superclass }.flatMap { it.declaredFields.asSequence() }
                .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .filter { value is kotlin.Function<*> || it.name in listOf("onClick", "onLongClick") }
                .forEach { it.isAccessible = true; visit(it.get(value), depth + 1) }
        }
        visit(root, 0)
        return found
    }
}
