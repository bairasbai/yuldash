package com.yuldash.app

import androidx.compose.runtime.Composable

/** Close new taxi entry on OFF/unknown while keeping a read-only retry for an old order. */
@Composable
internal fun TaxiRestoreFailure(onRetry: () -> Unit, onBack: () -> Unit, content: @Composable () -> Unit) {
    FeatureGate(
        open = { it.isOn(Feature.Taxi) },
        kind = ComingSoonKind.Taxi,
        onBack = onBack,
        onFindRide = onBack,
        embedded = true,
        secondaryLabel = appText("Проверить мой заказ", "Заказымды тикшереү"),
        onSecondary = onRetry,
        content = content,
    )
}
