package com.yuldash.app

import androidx.compose.runtime.Composable

/** Exact system closure when the search for a new driver after a route change ran out of time. */
internal fun isTaxiRouteResumeTimeout(status: String, cancelBy: String?, cancelReason: String?): Boolean =
    status == "cancelled" && cancelBy == "system" && cancelReason == "route_resume_timeout"

@Composable
internal fun taxiPassengerCancelledTitle(routeResumeTimedOut: Boolean, noShow: Boolean): String = when {
    routeResumeTimedOut -> appText("Не нашли водителя", "Йөрөтөүсе табылманы")
    noShow -> appText("Поездка не состоялась", "Сәфәр булманы")
    else -> appText("Заказ отменён", "Заказ ҡабул ителмәне")
}

@Composable
internal fun taxiRouteResumeTimeoutSubtitle(): String = appText(
    "После изменения маршрута не нашли водителя. Если поездка ещё нужна, оформи новый заказ.",
    "Маршрут үҙгәргәндән һуң йөрөтөүсе табылманы. Сәфәр әле лә кәрәк булһа, яңы заказ бир.",
)
