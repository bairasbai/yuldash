package com.yuldash.app

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yuldash.app.data.ApiClient

/** Эту поездку опубликовал я сам: водитель открыл её из ленты, по ссылке или из поиска. */
internal fun isOwnRide(ride: Ride, myId: Int? = ApiClient.myUserId()): Boolean =
    ride.driverId > 0 && myId != null && ride.driverId == myId

/**
 * Низ «Деталей поездки», когда водитель открыл СВОЮ поездку (приёмка 18.10, п.12).
 *
 * Раньше он видел «Забронировать место» и «Написать»: бронь сервер всё равно отклонял («Нельзя
 * бронировать собственную поездку»), а «Написать» вело в общий список чатов. Теперь вместо кнопок —
 * спокойная подсказка, где на самом деле живут брони его пассажиров.
 */
@Composable
internal fun OwnRideBar() {
    Surface(color = CanonSurface, shadowElevation = CanonDepth.sheet) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = CanonSpace.lg, vertical = CanonSpace.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.DirectionsCar, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(CanonSpace.md))
            Text(
                appText("Это твоя поездка. Брони пассажиров — в кабинете водителя.",
                    "Был — һинең сәфәрең. Пассажирҙарҙың брондәре — йөрөтөүсе кабинетында."),
                color = CanonMuted, style = CanonCaption,
            )
        }
    }
}
