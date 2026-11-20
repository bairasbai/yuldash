package com.yuldash.app

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import com.yuldash.app.data.TaxiStop
import java.util.UUID

/** Activity recreation only. A cold process must not trust a prior login's coordinates. */
internal object TaxiStopDraftCodec {
    // Defensive saved-state ceiling, not a geocoder/API label limit. Preserve long
    // titles verbatim; at most three labels bound this payload to 3072 UTF-16 units.
    private const val MAX_TITLE_LENGTH = 1024
    private val processNonce = UUID.randomUUID().toString()

    fun save(stops: List<TaxiStop>, ownerId: Int?, generation: Long,
             process: String = processNonce): ArrayList<Any>? {
        if (ownerId == null || ownerId <= 0 || stops.size > 3 || stops.any { !valid(it) }) return null
        return arrayListOf<Any>(1, ownerId, generation, process).apply {
            stops.forEach { add(it.lat); add(it.lng); add(it.text); add(it.done) }
        }
    }

    fun restore(saved: List<Any>, ownerId: Int?, generation: Long,
                process: String = processNonce): List<TaxiStop>? {
        if (ownerId == null || ownerId <= 0 || saved.size !in 4..16 || (saved.size - 4) % 4 != 0
            || saved[0] != 1 || saved[1] != ownerId || saved[2] != generation || saved[3] != process) return null
        val stops = ArrayList<TaxiStop>()
        for (i in 4 until saved.size step 4) {
            val lat = saved[i] as? Double ?: return null
            val lng = saved[i + 1] as? Double ?: return null
            val text = saved[i + 2] as? String ?: return null
            val done = saved[i + 3] as? Boolean ?: return null
            val stop = TaxiStop(lat, lng, text, done)
            if (!valid(stop)) return null
            stops.add(stop)
        }
        return stops
    }

    private fun valid(stop: TaxiStop): Boolean = stop.lat.isFinite() && stop.lng.isFinite()
        && stop.lat in -90.0..90.0 && stop.lng in -180.0..180.0 && stop.text.length <= MAX_TITLE_LENGTH
}

internal fun taxiStopDraftSaver(ownerId: Int?, generation: Long) =
    Saver<MutableState<List<TaxiStop>>, ArrayList<Any>>(
        save = { TaxiStopDraftCodec.save(it.value, ownerId, generation) },
        restore = { saved -> TaxiStopDraftCodec.restore(saved, ownerId, generation)?.let { mutableStateOf(it) } },
    )
