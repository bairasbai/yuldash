package com.yuldash.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver

/** Both directions are gated: protected NOW must not restore a previous saved request either. */
internal fun <T, S : Any> taxiConsentGatedSaver(noSave: () -> Boolean, delegate: Saver<T, S>): Saver<T, S> = Saver(
    save = { value -> if (noSave()) null else with(delegate) { save(value) } },
    restore = { value -> if (noSave()) null else delegate.restore(value) },
)

@Composable
internal fun <T, S : Any> rememberTaxiConsentSaver(noSave: () -> Boolean, delegate: Saver<T, S>): Saver<T, S> {
    val current = rememberUpdatedState(noSave)
    // The predicate reads the actual scheduled MutableState, not the previous frame's Boolean.
    return remember(delegate) { taxiConsentGatedSaver({ current.value() }, delegate) }
}
