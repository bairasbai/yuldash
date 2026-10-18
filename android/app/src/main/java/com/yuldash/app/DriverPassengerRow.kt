package com.yuldash.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yuldash.app.data.DriverBookingDto

/** Private booking data, including the passenger's chosen boarding place. */
@Composable
internal fun PassengerRow(b: DriverBookingDto) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).background(CanonMint, CircleShape), contentAlignment = Alignment.Center) {
            Text(b.passengerName.take(1).uppercase(), style = CanonBodyStrong, color = CanonGreen2)
        }
        Spacer(Modifier.width(CanonSpace.sm))
        Column(Modifier.weight(1f)) {
            Text(b.passengerName, style = CanonBodyStrong, color = CanonText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (b.route.isNotBlank()) Text(b.route, style = CanonCaption, color = CanonMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            PickupStopNote(b.pickupStop, b.rideHasStops)
        }
        b.passengerRating?.let { rating ->
            Icon(Icons.Default.Star, contentDescription = null, tint = CanonStar, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(CanonSpace.xs))
            Text(String.format(java.util.Locale.US, "%.1f", rating), style = CanonBodyStrong, color = CanonText)
        }
    }
}
