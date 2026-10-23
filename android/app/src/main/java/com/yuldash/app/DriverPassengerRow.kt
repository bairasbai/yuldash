package com.yuldash.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
            BookingSegmentNote(b.pickupStop, b.dropoffStop, b.rideHasStops, b.fromCity, b.toCity)
        }
        b.passengerRating?.let { rating ->
            Icon(Icons.Default.Star, contentDescription = null, tint = CanonStar, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(CanonSpace.xs))
            // Русская запись рейтинга — «4,8», как на экране поездки, а не «4.8».
            Text(String.format(java.util.Locale.US, "%.1f", rating).replace('.', ','), style = CanonBodyStrong, color = CanonText)
        }
    }
}

/**
 * Оценка пассажира водителем в кабинете (ProfileScreen.kt, «Пассажиры — оцените после поездки»).
 * Оценка ставится в ДВА шага. Раньше первое касание сразу уходило на сервер: промахнулся пальцем
 * по первой звезде — человеку упал единицей рейтинг, и вернуть было нечем. Теперь звёзды
 * выбираешь, потом подтверждаешь.
 */
@Composable
internal fun ColumnScope.PassengerStarsRating(b: DriverBookingDto, onRate: (Int, Int, (Boolean) -> Unit) -> Unit) {
    var stars by remember(b.bookingId, b.myStars) { mutableStateOf(b.myStars) }
    var editing by remember(b.bookingId, b.myStars) { mutableStateOf(b.myStars == 0) }
    var sending by remember(b.bookingId, b.myStars) { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        (1..5).forEach { n ->
            val lit = n <= stars
            // Звезда «зажигается»: цвет и размер догоняют выбор — оценка
            // ощущается нажатием, а не молчаливой сменой картинки.
            val starTint by animateColorAsState(if (lit) CanonStar else CanonMuted, tween(CanonMotion.NORMAL), label = "starTint")
            val starScale by animateFloatAsState(if (lit) 1.14f else 1f, tween(CanonMotion.NORMAL), label = "starScale")
            // Тач-цель ≥48dp (иконка визуально 34dp внутри).
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .then(
                        if (editing && !sending) Modifier.clickable { stars = n } else Modifier
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Star,
                    contentDescription = starsText(n),
                    tint = starTint,
                    modifier = Modifier.size(34.dp).scale(starScale),
                )
            }
        }
    }
    // Уже оценил — показываем сколько и даём переставить. Сервер оценку
    // разрешает изменить, значит и в приложении это не тупик.
    if (!editing) {
        TextButton(
            onClick = { editing = true },
            modifier = Modifier.heightIn(min = 48.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = CanonGreen2),
        ) { Text(appText("Изменить", "Үҙгәртеү"), fontWeight = FontWeight.Bold, fontSize = 14.sp) }
    }
    // Подпись под звёздами = состояние словами. Незрячим она же читает оценку.
    Text(
        when {
            !editing -> appText("Твоя оценка: ${starsText(stars)}", "Һинең баһаң: ${starsText(stars)}")
            stars == 0 -> appText("Выбери оценку", "Баһа һайла")
            else -> appText("Выбрано ${starsText(stars)} — подтверди", "${starsText(stars)} һайланды — раҫла")
        },
        color = CanonMuted, fontSize = 12.sp,
    )
    AnimatedVisibility(visible = editing && stars > 0) {
        Column(verticalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
            Button(
                onClick = {
                    sending = true
                    onRate(b.bookingId, stars) { ok ->
                        sending = false
                        // Не ушло — возвращаем как было, чтобы на экране не
                        // осталась «поставленная» оценка, которой нет на сервере.
                        if (ok) editing = false else stars = b.myStars
                    }
                },
                enabled = !sending,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2),
            ) {
                Text(
                    if (sending) appText("Отправляем…", "Ебәрәбеҙ…")
                    else appText("Отправить оценку", "Баһаны ебәреү"),
                    fontWeight = FontWeight.Bold,
                )
            }
            // Отмена нужна только когда есть что отменять — уже поставленную раньше оценку.
            if (b.myStars > 0) {
                OutlinedButton(
                    onClick = { stars = b.myStars; editing = false },
                    enabled = !sending,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = RoundedCornerShape(14.dp),
                ) { Text(appText("Отмена", "Кире алыу"), color = CanonMuted) }
            }
        }
    }
}
