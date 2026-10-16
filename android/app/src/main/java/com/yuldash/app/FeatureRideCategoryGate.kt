package com.yuldash.app

import androidx.compose.runtime.Composable

/** Только две серверные категории доставки. История и другие категории сюда не входят. */
internal fun rideCategoryClosed(category: String, features: FeatureSet = FeatureFlags.current): Boolean =
    category in setOf("parcel", "cargo") && !features.isOn(Feature.Parcels)

/**
 * Категории формы «ищу попутку». «Посылка» и «Груз» — доставка: пока она закрыта, их не предлагаем
 * (сервер на них отвечает 403 «скоро»). Остальные — как были.
 */
internal fun requestCategories(features: FeatureSet = FeatureFlags.current): List<Pair<String, LocalizedText>> = listOf(
    "regular" to LocalizedText("Обычная", "Ғәҙәти"),
    "urgent" to LocalizedText("Срочно", "Ашығыс"),
    "parcel" to LocalizedText("Посылка", "Бандероль"),
    "cargo" to LocalizedText("Груз", "Йөк"),
    "kids" to LocalizedText("С детьми", "Балалар менән"),
).filterNot { rideCategoryClosed(it.first, features) }

@Composable
internal fun RideCategoryBlocked(category: String, onBack: () -> Unit, onFindRide: () -> Unit, onRegular: (() -> Unit)? = null): Boolean {
    if (!rideCategoryClosed(category, rememberFeatures())) return false
    ComingSoonScreen(ComingSoonKind.Delivery, onBack, onFindRide,
        secondaryLabel = if (onRegular == null) null else appText("Обычная", "Ябай"), onSecondary = onRegular)
    return true
}
