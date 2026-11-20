package com.yuldash.app

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.yuldash.app.data.TaxiStop
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual production saver in a minimal Compose host; not full taxi UI/API acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TaxiStopDraftSaverTest {
    @get:Rule val compose = createComposeRule()
    private val stops = listOf(TaxiStop(1.0, 2.0, "First"), TaxiStop(3.0, 4.0, "Second", true))

    @Test fun sameOwnerActivityRestorationKeepsOrderedStops() {
        val restoration = StateRestorationTester(compose)
        var edit: (List<TaxiStop>) -> Unit = {}
        restoration.setContent {
            val saver = remember { taxiStopDraftSaver(11, 5L) }
            var draft by rememberSaveable(saver = saver) { mutableStateOf(emptyList<TaxiStop>()) }
            edit = { draft = it }
            Text(draft.joinToString { "${it.text}:${it.done}" }.ifEmpty { "empty" })
        }
        compose.runOnIdle { edit(stops) }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("First:false, Second:true").assertTextEquals("First:false, Second:true")
        // A successful order/reset stores empty, never the previously submitted route.
        compose.runOnIdle { edit(emptyList()) }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("empty").assertTextEquals("empty")
    }

    @Test fun logoutAndReloginDoNotInheritLiveDraft() {
        val owner = mutableStateOf<Int?>(11)
        val generation = mutableStateOf(5L)
        var edit: (List<TaxiStop>) -> Unit = {}
        compose.setContent {
            key(generation.value) {
                val saver = remember(owner.value, generation.value) { taxiStopDraftSaver(owner.value, generation.value) }
                var draft by rememberSaveable(owner.value, generation.value, saver = saver) {
                    mutableStateOf(emptyList<TaxiStop>())
                }
                edit = { draft = it }
                Text(draft.joinToString { it.text }.ifEmpty { "empty" })
            }
        }
        compose.runOnIdle { edit(stops) }
        compose.runOnIdle { owner.value = null; generation.value = 6L }
        compose.onNodeWithText("empty").assertTextEquals("empty")
        compose.runOnIdle { owner.value = 11; generation.value = 7L }
        compose.onNodeWithText("empty").assertTextEquals("empty")
    }

    @Test fun capturedStateRejectsDifferentOwnerSessionAndColdProcess() {
        val saved = requireNotNull(TaxiStopDraftCodec.save(stops, 11, 5L, "process-A"))
        assertEquals(stops, TaxiStopDraftCodec.restore(saved, 11, 5L, "process-A"))
        assertNull(TaxiStopDraftCodec.restore(saved, 22, 5L, "process-A"))
        assertNull(TaxiStopDraftCodec.restore(saved, 11, 6L, "process-A"))
        assertNull(TaxiStopDraftCodec.restore(saved, null, 5L, "process-A"))
        // Simulated process identity change, NOT an OS process-kill test.
        assertNull(TaxiStopDraftCodec.restore(saved, 11, 5L, "process-B"))
    }

    @Test fun longTitlesRoundTripVerbatimAndExtremePayloadRejectsWholeRoute() {
        val route = listOf(200, 201, 1024).mapIndexed { index, length ->
            TaxiStop(1.0 + index, 2.0 + index, "Я".repeat(length), index == 2)
        }
        val saved = requireNotNull(TaxiStopDraftCodec.save(route, 11, 5L, "A"))
        assertEquals(route, TaxiStopDraftCodec.restore(saved, 11, 5L, "A"))
        // A defensive ceiling is not truncation or permission to restore partial routes.
        assertNull(TaxiStopDraftCodec.save(route.toMutableList().apply {
            this[1] = this[1].copy(text = "Я".repeat(1025))
        }, 11, 5L, "A"))
        assertNull(TaxiStopDraftCodec.restore(saved.toMutableList().apply {
            this[10] = "Я".repeat(1025)
        }, 11, 5L, "A"))
    }

    @Test fun malformedAndLegacyStateAreRejectedWithoutPartialRoute() {
        val valid = requireNotNull(TaxiStopDraftCodec.save(stops, 11, 5L, "A"))
        assertNull(TaxiStopDraftCodec.restore(emptyList(), 11, 5L, "A"))
        assertNull(TaxiStopDraftCodec.restore(listOf(1.0, 2.0, "legacy"), 11, 5L, "A"))
        assertNull(TaxiStopDraftCodec.restore(valid.dropLast(1), 11, 5L, "A"))
        assertNull(TaxiStopDraftCodec.restore(valid.toMutableList().apply { this[4] = Double.NaN }, 11, 5L, "A"))
        assertNull(TaxiStopDraftCodec.restore(valid.toMutableList().apply { this[8] = 91.0 }, 11, 5L, "A"))
        assertNull(TaxiStopDraftCodec.save(stops + stops, 11, 5L, "A"))
        assertNull(TaxiStopDraftCodec.save(stops, null, 5L, "A"))
        assertEquals(emptyList<TaxiStop>(), TaxiStopDraftCodec.restore(
            requireNotNull(TaxiStopDraftCodec.save(emptyList(), 11, 5L, "A")), 11, 5L, "A"))
    }
}
