package com.yuldash.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.yandex.mapkit.geometry.Point
import com.yuldash.app.data.OfflineTripSnapshots
import com.yuldash.app.data.TaxiQuoteConsentApi
import com.yuldash.app.data.TaxiStop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

// Android parcels Maps and ArrayLists recursively, including Compose's Parcelable values.
private fun SaveableStateRegistry.fields() = HashMap(performSave().mapValues { ArrayList(it.value) })

private fun registry(saved: Any?, canSave: (Any) -> Boolean): SaveableStateRegistry? {
    val fields = saved as? Map<*, *> ?: return null
    val values = fields.entries.associate { (name, value) ->
        (name as? String ?: return null) to (value as? List<*> ?: return null)
    }
    return SaveableStateRegistry(values, canSave)
}

/** The outer envelope owns the entire taxi screen, including the scheduled-order receipt. */
internal fun taxiOrderRegistrySaver(
    namespace: String?,
    requestedOrder: Int,
    current: () -> Boolean,
    canSave: (Any) -> Boolean,
): Saver<SaveableStateRegistry, Any> = Saver(
    save = { state ->
        if (namespace != null && current()) arrayListOf(namespace, requestedOrder, state.fields()) else null
    },
    restore = { saved ->
        val values = saved as? List<*>
        if (namespace != null && current() && values?.size == 3 &&
            values[0] == namespace && values[1] == requestedOrder
        ) registry(values[2], canSave) else null
    },
)

/** Compose compares key equality when replacing a live subtree, but derives saved field keys
 * from its hash. Restart live session effects on a new generation while keeping the same
 * positional hash when that durable login is reopened by another process. The full namespace
 * and requested order are checked separately by the outer saver before any field is restored.
 */
internal data class TaxiCompositionOwner(val generation: Long, val identity: String?, val durable: Boolean) {
    override fun hashCode(): Int = identity?.hashCode() ?: generation.hashCode()
}

@Composable
internal fun TaxiOrderSession(generation: Long, requestedOrder: Int, content: @Composable () -> Unit) {
    val observed by OfflineTripSnapshots.owner.collectAsState()
    val live = observed?.takeIf { it.generation == generation && OfflineTripSnapshots.current(it) }
    val owner = live?.takeIf(OfflineTripSnapshots::cacheEnabled)
    val namespace = owner?.namespace
    val parent = LocalSaveableStateRegistry.current
    val canSave: (Any) -> Boolean = { parent?.canBeSaved(it) ?: true }
    // Only the stable login namespace participates in the restored children's positional keys.
    // Continue checking the original live owner when Android asks to save, even before recomposition.
    val saver = remember(owner, requestedOrder, parent) {
        taxiOrderRegistrySaver(namespace, requestedOrder, { owner != null && OfflineTripSnapshots.cacheEnabled(owner) }, canSave)
    }
    val state = rememberSaveable(owner, generation, requestedOrder, saver = saver) { SaveableStateRegistry(null, canSave) }
    key(TaxiCompositionOwner(generation, live?.namespace, owner != null), requestedOrder) {
        CompositionLocalProvider(LocalSaveableStateRegistry provides state, content = content)
    }
}

/** Kept above the checking/picker transition so Android may save twice before the form is drawn. */
internal class TaxiPickerDraft(
    private val revisions: TaxiDraftRevision,
    private val canSave: (Any) -> Boolean,
    private var fields: SaveableStateRegistry = SaveableStateRegistry(null, canSave),
    time: Long? = null,
    epoch: Int = 0,
    private var bornAt: String? = revisions.read(),
) {
    val scheduledTime = mutableStateOf(time)
    private var revision by mutableIntStateOf(epoch)
    val submittedRevision get() = bornAt
    fun wasCompleted(): Boolean = bornAt != null && bornAt != revisions.read()

    suspend fun acknowledge(submitted: String?) = withContext(NonCancellable + Dispatchers.IO) {
        revisions.complete(submitted)
    }

    @Composable
    fun Form(content: @Composable () -> Unit) {
        key(revision) { CompositionLocalProvider(LocalSaveableStateRegistry provides fields, content = content) }
    }

    fun clear() {
        bornAt = revisions.read()
        fields = SaveableStateRegistry(null, canSave)
        scheduledTime.value = null
        revision++
    }

    internal fun save(consentRequested: Boolean): Any? {
        val time = scheduledTime.value
        if (consentRequested && time == null || bornAt == null || bornAt != revisions.read()) return null
        return arrayListOf(bornAt, time, revision, fields.fields())
    }

    companion object {
        internal fun restore(saved: Any, consentRequested: Boolean, revisions: TaxiDraftRevision, canSave: (Any) -> Boolean): TaxiPickerDraft? {
            val values = saved as? List<*> ?: return null
            if (values.size != 4 || values[0] !is String || values[0] != revisions.read()) return null
            val time = values[1]
            if ((time != null && time !is Long) || (consentRequested && time == null)) return null
            val epoch = values[2] as? Int ?: return null
            val fields = registry(values[3], canSave) ?: return null
            return TaxiPickerDraft(revisions, canSave, fields, time as Long?, epoch, values[0] as String)
        }
    }
}

@Composable
internal fun rememberTaxiPickerDraft(generation: Long): TaxiPickerDraft {
    val context = LocalContext.current
    val revisions = remember(generation, context) { TaxiDraftRevision.forSession(context, generation) }
    val parent = LocalSaveableStateRegistry.current
    val canSave: (Any) -> Boolean = { parent?.canBeSaved(it) ?: true }
    val saver = remember(revisions, parent) {
        Saver<TaxiPickerDraft, Any>(
            save = { it.save(TaxiQuoteConsentApi.requested) },
            restore = { TaxiPickerDraft.restore(it, TaxiQuoteConsentApi.requested, revisions, canSave) },
        )
    }
    return rememberSaveable(revisions, saver = saver) { TaxiPickerDraft(revisions, canSave) }
}

internal val TaxiStopDraftSaver = listSaver<List<TaxiStop>, Any>(
    save = { stops ->
        stops.flatMap { listOf(it.lat, it.lng, it.text, it.done) }
    },
    restore = { saved ->
        if (saved.size > 12 || saved.size % 4 != 0) return@listSaver null
        saved.chunked(4).map { row ->
            val lat = row[0] as? Double ?: return@listSaver null
            val lng = row[1] as? Double ?: return@listSaver null
            val text = row[2] as? String ?: return@listSaver null
            val done = row[3] as? Boolean ?: return@listSaver null
            if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return@listSaver null
            TaxiStop(lat, lng, text, done)
        }
    },
)

internal fun taxiPickupTimeCurrent(time: Long?, now: Long = System.currentTimeMillis()) =
    time == null || time > now

/**
 * Yandex Point is not Bundle-saveable. Persist only latitude/longitude so an unfinished
 * taxi route survives Activity recreation without retaining any SDK object.
 */
internal val InstantPointStateSaver = Saver<MutableState<Point?>, DoubleArray>(
    save = { state ->
        state.value?.let { point -> doubleArrayOf(point.latitude, point.longitude) }
            ?: doubleArrayOf()
    },
    restore = { saved ->
        mutableStateOf(
            saved.takeIf { it.size == 2 }?.let { Point(it[0], it[1]) },
        )
    },
)
