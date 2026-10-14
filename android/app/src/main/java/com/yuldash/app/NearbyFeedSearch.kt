package com.yuldash.app

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// «Найти попутку» на вкладке «Карта» ведёт к ленте «Ближайшие поездки» — она на том же экране,
// ниже карты. Раньше кнопка уводила на вкладку «Поездки», а это «Мои поездки» (брони человека),
// — искать попутку там нечем (эмулятор, 14.10.2026).

/**
 * Номер пункта «Ближайшие поездки» в прокручиваемом низе экрана. Перед ним стоят только необязательные
 * пункты: шапка с картой (на невысоком экране она едет вместе со списком) и баннер «на праздник».
 */
internal fun nearbyHeaderIndex(compactHeader: Boolean, seasonalShown: Boolean): Int =
    (if (compactHeader) 1 else 0) + (if (seasonalShown) 1 else 0)

/**
 * Состояние прокрутки ленты. Каждое нажатие «Найти попутку» ([findTick] растёт) плавно ставит
 * заголовок «Ближайшие поездки» наверх списка. Первый показ экрана ([findTick] == 0) не двигает ничего.
 */
@Composable
internal fun rememberNearbyFeedState(findTick: Int, compactHeader: Boolean, seasonalShown: Boolean): LazyListState {
    val state = rememberLazyListState()
    LaunchedEffect(findTick) {
        if (findTick > 0) state.animateScrollToItem(nearbyHeaderIndex(compactHeader, seasonalShown))
    }
    return state
}

/**
 * Кнопка «Все маршруты» под заголовком ленты, пока лента сужена до выбранного маршрута. Без неё
 * человек, выбравший маршрут без поездок, оказался бы в тупике: пустая лента и нечем её расширить.
 */
@Composable
internal fun NearbyRouteReset(onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(appText("Все маршруты", "Бөтә маршруттар"), color = CanonGreen2, style = CanonCaption)
    }
}

/** До явного выбора лента следует активной поездке; «Все маршруты» снимает и её ограничение. */
internal class NearbyRouteSearchState {
    var route by mutableStateOf<PopularRoute?>(null)
        private set
    private var allRoutes by mutableStateOf(false)

    fun choose(value: PopularRoute?) {
        route = value
        allRoutes = value == null
    }

    fun clear() = choose(null)
    fun from(activeTrip: Ride?): String? = if (allRoutes) null else route?.from ?: activeTrip?.from
    fun to(activeTrip: Ride?): String? = if (allRoutes) null else route?.to ?: activeTrip?.to
}
