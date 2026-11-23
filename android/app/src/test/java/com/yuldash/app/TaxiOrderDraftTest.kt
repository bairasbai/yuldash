package com.yuldash.app

import android.app.Application
import android.os.Parcel
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.saveable.SaverScope
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.SnapshotOwner
import com.yuldash.app.data.TaxiStop
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TaxiOrderDraftTest {
    private val owner = SnapshotOwner(41, "66666666-6666-4666-8666-666666666666", 8, "https://example.test")
    private val scope = SaverScope { true }
    private val prefs = ApplicationProvider.getApplicationContext<Application>().getSharedPreferences("tf10", 0)
    private fun revisions() = TaxiDraftRevision(prefs) { true }
    private val stops = listOf(
        TaxiStop(52.5123456789, -179.999997, "QA — Ә / 🚌", false),
        TaxiStop(52.5123456790, -179.999998, "QA — Ә / 🚌", true),
    )

    @Suppress("DEPRECATION")
    private fun parcel(value: Any): Any {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeValue(value)
            parcel.setDataPosition(0)
            requireNotNull(parcel.readValue(javaClass.classLoader))
        } finally {
            parcel.recycle()
        }
    }

    @Test fun orderedStopsRetainExactCoordinatesDuplicateLabelsAndFlagsAcrossParcel() {
        val encoded = with(TaxiStopDraftSaver) { requireNotNull(scope.save(stops)) }
        assertEquals(stops, TaxiStopDraftSaver.restore(parcel(encoded)))
        assertNull(TaxiStopDraftSaver.restore(listOf(0.0, Double.NaN, "bad", false)))
        assertNull(TaxiStopDraftSaver.restore(listOf(40.0, 70.0, "incomplete")))
    }

    @Test fun coldGenerationCanReadOnlyItsOwnLoginOriginAndRequestedContext() {
        val original = SaveableStateRegistry(null) { true }
        original.registerProvider("receipt") { mutableStateOf("QA destination") }
        var eligible = true
        fun saver(namespace: String?, request: Int = 17) = taxiOrderRegistrySaver(namespace, request, { eligible }) { true }
        val active = saver(owner.namespace)
        val encoded = parcel(with(active) { requireNotNull(scope.save(original)) })
        val cold = owner.copy(generation = 1)
        val previousTree = TaxiCompositionOwner(owner.generation, owner.namespace, true)
        val coldTree = TaxiCompositionOwner(cold.generation, cold.namespace, true)
        assertNotEquals(previousTree, coldTree)
        assertEquals(previousTree.hashCode(), coldTree.hashCode())
        val receipt = saver(cold.namespace).restore(encoded)?.consumeRestored("receipt")
        assertEquals("QA destination", (receipt as androidx.compose.runtime.MutableState<*>).value)
        assertNull(saver(owner.copy(userId = 42).namespace).restore(encoded))
        assertNull(saver(owner.copy(scope = "77777777-7777-4777-8777-777777777777").namespace).restore(encoded))
        assertNull(saver(owner.copy(origin = "https://another.test").namespace).restore(encoded))
        assertNull(saver(null).restore(encoded))
        assertNull(saver(owner.namespace, 0).restore(encoded))
        assertNull(active.restore(mapOf("receipt" to "untagged old Activity")))
        eligible = false
        assertNull(with(active) { scope.save(original) })
        assertNull(active.restore(encoded))
    }

    private fun populatedDraft(time: Long?) = TaxiPickerDraft(
        revisions(), { true }, SaveableStateRegistry(null) { true }.apply {
            registerProvider("stops") { with(TaxiStopDraftSaver) { scope.save(stops) } }
            registerProvider("pickup") { "QA pickup" }
            registerProvider("destination") { "QA destination" }
            registerProvider("comment") { "QA comment" }
            registerProvider("contact") { "QA contact" }
            registerProvider("options") { "QA options" }
        }, time,
    )

    @Test fun wholeDraftSurvivesParcelAndSecondSaveBeforePickerReturns() {
        val draft = populatedDraft(12345L)
        val saved = parcel(requireNotNull(draft.save(true)))
        val restored = requireNotNull(TaxiPickerDraft.restore(saved, true, revisions()) { true })
        assertEquals(12345L, restored.scheduledTime.value)
        assertEquals(saved, restored.save(true))
        // Expired pickup stays scheduled, editable, and ineligible to submit as immediate.
        assertFalse(taxiPickupTimeCurrent(restored.scheduledTime.value, now = 12345L))
        assertTrue(taxiPickupTimeCurrent(restored.scheduledTime.value, now = 12344L))
        assertTrue(taxiPickupTimeCurrent(null, now = 12345L))
        restored.scheduledTime.value = null
        assertNull(restored.save(true))
    }

    @Test fun protectedNowRejectsTheEntirePickerInBothDirections() {
        val draft = populatedDraft(null)
        val previouslyUnprotected = requireNotNull(draft.save(false))
        val ordinary = requireNotNull(TaxiPickerDraft.restore(parcel(previouslyUnprotected), false, revisions()) { true })
        assertNull(ordinary.scheduledTime.value)
        assertEquals(previouslyUnprotected, ordinary.save(false))
        assertNull(draft.save(true))
        assertNull(TaxiPickerDraft.restore(previouslyUnprotected, true, revisions()) { true })
        draft.scheduledTime.value = 12345L
        assertNotNull(draft.save(true))
        draft.scheduledTime.value = null
        assertNull(draft.save(true))
    }

    @Test fun completionWhileStoppedInvalidatesTheOldBundleAndAllowsTheNextDraft() {
        val draft = populatedDraft(12345L)
        val stopped = parcel(requireNotNull(draft.save(false)))
        val submitted = draft.submittedRevision
        revisions().complete(submitted)
        assertNull(TaxiPickerDraft.restore(stopped, false, revisions()) { true })
        assertNull(draft.save(false))
        assertTrue(draft.wasCompleted())
        draft.clear()
        assertNull(draft.scheduledTime.value)
        val fresh = populatedDraft(54321L)
        val freshSaved = parcel(requireNotNull(fresh.save(false)))
        // A delayed repeat acknowledgement for the previous version leaves the new form valid.
        revisions().complete(submitted)
        assertNotNull(TaxiPickerDraft.restore(freshSaved, false, revisions()) { true })
        assertEquals(1, prefs.all.size)
        assertTrue(prefs.all.values.single() is String)
    }
}
