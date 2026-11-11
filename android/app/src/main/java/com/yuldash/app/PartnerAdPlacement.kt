package com.yuldash.app

/** Код места показа с сервера → место в приложении. Незнакомое имя (сервер завёл новое,
 *  приложение ещё не знает) → null: такое объявление просто не покажем в этом слоте,
 *  вместо того чтобы уронить разбор всего списка рекламы. */
internal fun adPlacementOf(code: String): AdPlacement? = when (code.trim().lowercase()) {
    "route" -> AdPlacement.Route
    "rideslist" -> AdPlacement.RidesList
    "nearby" -> AdPlacement.Nearby
    "tripdetails" -> AdPlacement.TripDetails
    "profile" -> AdPlacement.Profile
    "help" -> AdPlacement.Help
    else -> null
}
