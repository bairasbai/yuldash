package com.yuldash.app

// Подпись маршрута на экране поездки: «Сибай → Уфа».
//
// Зачем отдельно. Поездка-заглушка из `bookingLinkStubRide` (пуш, ссылка, «Открыть поездку» в «Едут с
// тобой») приходит с ПУСТЫМИ `from` и `to`. Пустая строка — не `null`, поэтому прежнее
// `ride?.from ?: запасной` запасной вариант не брало: на экране было «— → —», а карточка погоды
// пропадала. Города тогда берём из деталей брони, которые экран и так грузит с сервера.

/** Первый непустой город из списка (поездка → детали брони → офлайн-паспорт); пустая строка — «нет города». */
internal fun tripRouteCity(vararg candidates: String?): String =
    candidates.firstNotNullOfOrNull { city -> city?.trim()?.takeIf { it.isNotEmpty() } }.orEmpty()

/** «Сибай → Уфа»; `null`, пока неизвестен хотя бы один из городов. */
internal fun tripRouteLabel(from: String, to: String): String? =
    if (from.isBlank() || to.isBlank()) null else "$from → $to"

/** Подпись маршрута из поездки с запасом в деталях брони: пустой `ride.from` / `ride.to` уступает деталям. */
internal fun tripRouteLabel(rideFrom: String?, rideTo: String?, detailsFrom: String?, detailsTo: String?): String? =
    tripRouteLabel(tripRouteCity(rideFrom, detailsFrom), tripRouteCity(rideTo, detailsTo))
