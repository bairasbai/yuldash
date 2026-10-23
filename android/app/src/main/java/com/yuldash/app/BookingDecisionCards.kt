package com.yuldash.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.EventSeat
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yandex.mapkit.geometry.Point

/** Приглашение соседу. `showPrice = false` — цена места ещё неизвестна (детали брони не загрузились):
 *  лучше без цены, чем со стоимостью всей брони вместо цены места. */
@Composable
internal fun bookingRideShareText(ride: Ride, showPrice: Boolean = true): String {
    val price = if (showPrice) " ${ride.price} ₽." else ""
    return appText(
        "Еду ${ride.from} → ${ride.to}, ${ride.timeText()}.$price Поехали вместе в Юлдаше 👇\nhttps://yulbash.ru",
        "${ride.from} → ${ride.to}, ${ride.timeText()}.$price Әйҙә бергә — Юлдашта 👇\nhttps://yulbash.ru",
    )
}

@Composable
internal fun BookingRouteDecisionCard(
    ride: Ride,
    hasBooking: Boolean,
    distanceText: String?,
    fromPoint: Point?,
    toPoint: Point?,
    contactUnlocked: Boolean,
    onShare: () -> Unit,
    pending: Boolean = false,   // бронь есть, а её детали ещё не пришли: не гадаем цифры по тому, откуда открыли
) {
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "${ride.from} → ${ride.to}",
                        color = CanonText,
                        fontSize = 24.sp,
                        lineHeight = 30.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CalendarMonth, contentDescription = null, tint = CanonMuted, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            listOfNotNull(ride.timeText(), distanceText).filter { it.isNotBlank() }.joinToString(" · "),
                            color = CanonMuted,
                            fontSize = 14.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = onShare) {
                    Icon(Icons.Default.Share, contentDescription = appText("Поделиться", "Бүлешеү"), tint = CanonGreen2)
                }
            }
            if (fromPoint != null && toPoint != null) {
                BookingRouteMapPreview(
                    modifier = Modifier.height(184.dp),
                    from = ride.from,
                    to = ride.to,
                    fromPoint = fromPoint,
                    toPoint = toPoint,
                    contactUnlocked = contactUnlocked,
                    showPrivacyNotice = false,
                )
            } else {
                RouteMapUnavailableCard(Modifier.height(148.dp))
            }
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BookingDecisionMetric(
                    icon = Icons.Default.Payments,
                    value = if (pending) "—" else "${ride.price} ₽",
                    label = if (hasBooking) appText("Стоимость брони", "Бронь хаҡы")
                        else appText("Цена за место", "Урын хаҡы"),
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                BookingDecisionMetric(
                    icon = Icons.Default.EventSeat,
                    value = if (pending) "—" else ride.seats.toString(),
                    label = if (hasBooking) appText("Мест в брони", "Брондәге урындар")
                        else appText("Свободных мест", "Буш урын"),
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun BookingDecisionMetric(
    icon: ImageVector,
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier, color = CanonBg, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 13.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(19.dp))
            Text(value, color = CanonText, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(label, color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp, textAlign = TextAlign.Center, maxLines = 2)
        }
    }
}
