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
import androidx.compose.foundation.layout.padding
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
            Text(passengerRatingText(rating), style = CanonBodyStrong, color = CanonText)
        }
    }
    PassengerMeetingPoint(b)
}

/** RS-03 Б: предложение смены места от пассажира — карточка у водителя.
 *  «На карте» — предлагаемая точка; «Согласиться» применяет её, «Оставить прежнее» — отклоняет. */
@Composable
internal fun DriverMeetingProposal(
    b: DriverBookingDto,
    busy: Boolean = false,
    onAnswer: (accept: Boolean) -> Unit,
) {
    val proposal = b.meetingProposal ?: return
    if (b.status != "confirmed" && b.status != "onboard") return
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.fillMaxWidth().padding(top = CanonSpace.xs), verticalArrangement = Arrangement.spacedBy(CanonSpace.xs)) {
        Text(
            if (proposal.kind == "dropoff") appText("Пассажир просит высадить в другом месте", "Пассажир башҡа урында төшөрөүҙе һорай")
            else appText("Пассажир просит встретиться в другом месте", "Пассажир башҡа урында осрашыуҙы һорай"),
            style = CanonCaption, color = CanonText, fontWeight = FontWeight.Bold,
        )
        if (proposal.note.isNotBlank()) Text(proposal.note, style = CanonCaption, color = CanonMuted)
        if (proposal.lat != null && proposal.lng != null) {
            val label = appText("Новое место", "Яңы урын")
            Text(
                appText("На карте →", "Картала →"),
                style = CanonCaption, color = CanonGreen2, fontWeight = FontWeight.Bold,
                modifier = Modifier.heightIn(min = 48.dp).clickable(
                    onClickLabel = appText("Показать предлагаемое место на карте", "Тәҡдим ителгән урынды картала күрһәтеү"),
                ) {
                    val uri = android.net.Uri.parse(
                        "geo:${proposal.lat},${proposal.lng}?q=${proposal.lat},${proposal.lng}(" + android.net.Uri.encode(label) + ")")
                    runCatching {
                        context.startActivity(android.content.Intent.createChooser(
                            android.content.Intent(android.content.Intent.ACTION_VIEW, uri), null))
                    }
                },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
            Button(
                onClick = { onAnswer(true) }, enabled = !busy,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2),
            ) { Text(appText("Согласиться", "Ризалашыу"), fontWeight = FontWeight.Bold) }
            OutlinedButton(
                onClick = { onAnswer(false) }, enabled = !busy,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                shape = RoundedCornerShape(14.dp),
            ) { Text(appText("Оставить прежнее", "Иҫкене ҡалдырыу"), color = CanonMuted) }
        }
    }
}

/** RS-03 А: точное место встречи/высадки пассажира для водителя.
 *  До подтверждения — только факт «место указано» (чужая геоточка закрыта); при confirmed/onboard —
 *  ориентир/пояснение и «Маршрут» в навигатор; после поездки — «место было указано». */
@Composable
internal fun PassengerMeetingPoint(b: DriverBookingDto) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val isBa = LocalAppLanguage.current == AppLanguage.Ba
    val open = b.status == "confirmed" || b.status == "onboard"
    Column(Modifier.fillMaxWidth().padding(top = CanonSpace.xs), verticalArrangement = Arrangement.spacedBy(CanonSpace.xs)) {
        if (!open && b.meetingPointSet) {
            Text(
                if (b.status == "done" || b.status == "cancelled")
                    appText("Место было указано", "Урын күрһәтелгәйне")
                else appText("Место указано — откроется после подтверждения", "Урын күрһәтелгән — раҫлағандан һуң асыла"),
                style = CanonCaption, color = CanonMuted,
            )
        }
        if (open) {
            val lmMeet = if (isBa) b.meetLandmarkBa.ifBlank { b.meetLandmarkRu } else b.meetLandmarkRu
            val meetText = listOf(lmMeet, b.meetNote).filter { it.isNotBlank() }.joinToString(" · ")
            if (meetText.isNotBlank()) Text(meetText, style = CanonCaption, color = CanonText)
            if (b.meetLat != null && b.meetLng != null) {
                val label = appText("Место встречи", "Осрашыу урыны")
                Text(
                    appText("Маршрут к точке →", "Нөктәгә маршрут →"),
                    style = CanonCaption, color = CanonGreen2, fontWeight = FontWeight.Bold,
                    modifier = Modifier.heightIn(min = 48.dp).clickable(
                        onClickLabel = appText("Маршрут к месту встречи", "Осрашыу урынына маршрут"),
                    ) {
                        val uri = android.net.Uri.parse(
                            "geo:${b.meetLat},${b.meetLng}?q=${b.meetLat},${b.meetLng}(" + android.net.Uri.encode(label) + ")")
                        runCatching {
                            context.startActivity(android.content.Intent.createChooser(
                                android.content.Intent(android.content.Intent.ACTION_VIEW, uri), null))
                        }
                    },
                )
            }
            val lmAlight = if (isBa) b.alightLandmarkBa.ifBlank { b.alightLandmarkRu } else b.alightLandmarkRu
            val alightText = listOf(lmAlight, b.alightNote).filter { it.isNotBlank() }.joinToString(" · ")
            if (alightText.isNotBlank()) {
                Text(appText("Высадить: ", "Төшөрөргә: ") + alightText, style = CanonCaption, color = CanonText)
            }
        }
    }
}

/**
 * Рейтинг пассажира у водителя — одна запись для «Пассажиров» и «Ждут подтверждения» (ProfileScreen.kt):
 * «4,8», как на экране поездки, а не «4.8», и не зависит от языка телефона.
 */
internal fun passengerRatingText(rating: Double): String =
    String.format(java.util.Locale.US, "%.1f", rating).replace('.', ',')

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
