package com.yuldash.app

import androidx.compose.runtime.Composable

/** Только две серверные категории доставки. История и другие категории сюда не входят. */
internal fun rideCategoryClosed(category: String, features: FeatureSet = FeatureFlags.current): Boolean =
    category in setOf("parcel", "cargo") && !features.isOn(Feature.Parcels)

@Composable
internal fun RideCategoryBlocked(category: String, onBack: () -> Unit, onFindRide: () -> Unit, onRegular: (() -> Unit)? = null): Boolean {
    if (!rideCategoryClosed(category, rememberFeatures())) return false
    ComingSoonScreen(ComingSoonKind.Delivery, onBack, onFindRide,
        secondaryLabel = if (onRegular == null) null else appText("Обычная", "Ябай"), onSecondary = onRegular)
    return true
}
