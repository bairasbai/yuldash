package com.yuldash.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/** RS-02 А: загрузка по участкам своего рейса — «Сибай → Ургаза: занято 3 из 3».
 *  Водитель видит, кто где садится и выходит, без имён. Рейс без остановок или
 *  несошедшийся список — ничего не рисуем (там достаточно общего «свободно N»). */
@Composable
internal fun RideLegsLoad(ride: Ride) {
    if (ride.waypoints.isEmpty() || ride.seatsByLeg.size != ride.waypoints.size + 1 || ride.seatsTotal <= 0) return
    val points = listOf(ride.from) + ride.waypoints + listOf(ride.to)
    Column(verticalArrangement = Arrangement.spacedBy(CanonSpace.xs)) {
        for (i in 0 until points.lastIndex) {
            val занято = (ride.seatsTotal - ride.seatsByLeg[i]).coerceAtLeast(0)
            Text(
                appText("${points[i]} → ${points[i + 1]}: занято $занято из ${ride.seatsTotal}",
                        "${points[i]} → ${points[i + 1]}: ${ride.seatsTotal} урындан $занято билдәле"),
                style = CanonCaption, color = CanonMuted,
            )
        }
    }
}
