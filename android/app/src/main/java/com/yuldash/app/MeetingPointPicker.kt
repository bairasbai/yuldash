package com.yuldash.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.yuldash.app.data.MeetingPointInput

/** RS-03 А: «Уточнить место (по желанию)» — точка на карте или ориентир + короткое пояснение.
 *  Ничего не обязательно: не указал — как раньше (место = остановка/начало/конец). */

/** Строка в форме брони под выбором посадки/высадки: текущее значение + «Изменить». */
@Composable
internal fun MeetingPointRow(
    title: String,
    point: MeetingPointInput?,
    enabled: Boolean = true,
    onEdit: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(enabled = enabled, onClickLabel = title) { onEdit() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CanonSpace.sm),
    ) {
        Icon(Icons.Default.LocationOn, contentDescription = null, tint = CanonGreen2)
        Column(Modifier.weight(1f)) {
            Text(title, style = CanonCaption, color = CanonMuted)
            Text(
                when {
                    point == null || point.isEmpty -> appText("По желанию — ориентир или точка на карте", "Телһәләгең буйынса — билгеле урын йәки карта нөктәһе")
                    point.landmarkId != null && point.landmarkTitle.isNotBlank() -> point.landmarkTitle
                    point.note.isNotBlank() -> point.note
                    else -> appText("Точка на карте указана", "Карта нөктәһе күрһәтелгән")
                },
                style = CanonBody, color = CanonText, fontWeight = if (point != null && !point.isEmpty) FontWeight.Bold else FontWeight.Normal,
            )
        }
        Icon(Icons.Default.Edit, contentDescription = appText("Изменить место", "Урынды үҙгәртеү"), tint = CanonMuted)
    }
}

/** Шторка выбора точки: ориентиры села (курируемые), «На карте» (пин в центре), пояснение. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MeetingPointSheet(
    city: String,
    title: String,
    initial: MeetingPointInput?,
    onDone: (MeetingPointInput) -> Unit,
    onDismiss: () -> Unit,
) {
    var lat by remember { mutableStateOf(initial?.lat) }
    var lng by remember { mutableStateOf(initial?.lng) }
    var landmarkId by remember { mutableStateOf(initial?.landmarkId) }
    var landmarkTitle by remember { mutableStateOf(initial?.landmarkTitle ?: "") }
    var note by remember { mutableStateOf(initial?.note ?: "") }
    var showMap by remember { mutableStateOf(false) }
    val isBa = LocalAppLanguageSafe()

    if (showMap) {
        PickupPickerOverlay(
            initial = lat?.let { la -> lng?.let { lg -> com.yandex.mapkit.geometry.Point(la, lg) } },
            onConfirm = { la, lg -> lat = la; lng = lg; landmarkId = null; landmarkTitle = ""; showMap = false },
            onDismiss = { showMap = false },
            hint = title,
        )
        return
    }
    AppModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(CanonSpace.lg),
            verticalArrangement = Arrangement.spacedBy(CanonSpace.md),
        ) {
            Text(title, style = CanonTitle, color = CanonText)
            Text(
                appText("Выбери ориентир или поставь точку на карте", "Билгеле урын һайла йәки картаға нөктә ҡуй"),
                style = CanonCaption, color = CanonMuted,
            )
            PickupSuggestionChips(city = city, selectedId = landmarkId) { p ->
                landmarkId = p.id; landmarkTitle = if (isBa) p.titleBa.ifBlank { p.titleRu } else p.titleRu
                lat = null; lng = null   // координаты возьмутся у ориентира на сервере
            }
            AppButton(
                text = appText("На карте", "Картала"), onClick = { showMap = true },
                style = AppButtonStyle.Secondary, icon = Icons.Default.Map,
            )
            OutlinedTextField(
                value = note, onValueChange = { note = it.take(120) },
                label = { Text(appText("Пояснение: ворота, подъезд, что рядом", "Аңлатма: ҡапҡа, инеү урыны, янында нимә")) },
                modifier = Modifier.fillMaxWidth(), shape = CanonFieldShape, maxLines = 2,
            )
            AnimatedVisibility(visible = lat != null || landmarkId != null || note.isNotBlank()) {
                TextButton(
                    onClick = { onDone(MeetingPointInput(clear = true)) },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(appText("Убрать место", "Урынды алып ташлау"), color = CanonMuted) }
            }
            AppButton(
                text = appText("Готово", "Әҙер"),
                onClick = {
                    onDone(MeetingPointInput(lat = lat, lng = lng, note = note.trim(),
                                             landmarkId = landmarkId, landmarkTitle = landmarkTitle))
                },
            )
        }
    }
}

@Composable
private fun LocalAppLanguageSafe(): Boolean = LocalAppLanguage.current == AppLanguage.Ba

/** RS-03 А: подпись своей точки встречи на экране поездки — ориентир или пояснение, что ставил
 *  пассажир. null — точки нет, показываем общий пин поездки. */
@Composable
internal fun ownMeetingLabel(d: com.yuldash.app.data.BookingDetailsDto?): String? {
    if (d == null) return null
    val lm = if (LocalAppLanguageSafe()) d.meetLandmarkBa.ifBlank { d.meetLandmarkRu } else d.meetLandmarkRu
    return listOf(lm, d.meetNote).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { null }
}


/** RS-03 Б: блок «место» на экране поездки пассажира.
 *  Есть предложение — «ждём ответа» + «Отозвать»; pending — «Изменить место» (сразу);
 *  confirmed/onboard — «Предложить другое место». Точку меняет только пассажир. */
@Composable
internal fun BookingMeetingActions(
    details: com.yuldash.app.data.BookingDetailsDto,
    ride: Ride,
    busy: Boolean = false,
    onPropose: (kind: String, point: MeetingPointInput) -> Unit,
    onWithdraw: () -> Unit,
) {
    if (details.role != "passenger") return
    val status = details.status
    if (status != "pending" && status != "confirmed" && status != "onboard") return
    val proposal = details.meetingProposal
    AppCard {
        Column(Modifier.fillMaxWidth().padding(CanonSpace.lg), verticalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
            if (proposal != null) {
                Text(appText("Ждём ответа водителя — пока действует прежнее место", "Йөрөтөүсөнөң яуабын көтәбеҙ — әлегә иҫке урын ҡала"),
                    style = CanonCaption, color = CanonMuted)
                AppButton(
                    text = appText("Отозвать предложение", "Тәҡдимде кире алыу"),
                    onClick = onWithdraw, enabled = !busy,
                    style = AppButtonStyle.Secondary,
                )
            } else {
                var editing by remember { mutableStateOf<String?>(null) }
                val isPickup = status != "onboard"   // в машине меняется только высадка
                AppButton(
                    text = if (status == "pending") appText("Изменить место", "Урынды үҙгәртеү")
                          else appText("Предложить другое место", "Башҡа урын тәҡдим итеү"),
                    onClick = { editing = if (isPickup) "pickup" else "dropoff" }, enabled = !busy,
                    style = AppButtonStyle.Secondary, icon = Icons.Default.Edit,
                )
                if (status == "confirmed" || status == "onboard") {
                    Text(
                        appText("Пока водитель не ответил — действует прежнее место.", "Йөрөтөүсе яуап биргәнсе — иҫке урын ҡала."),
                        style = CanonCaption, color = CanonMuted,
                    )
                }
                when (editing) {
                    "pickup" -> MeetingPointSheet(
                        city = details.pickupStop?.takeIf { it.isNotBlank() } ?: ride.from,
                        title = appText("Где тебя забрать?", "Һине ҡайҙан алырға?"),
                        initial = MeetingPointInput(lat = details.meetLat, lng = details.meetLng,
                            note = details.meetNote, landmarkTitle = details.meetLandmarkRu),
                        onDone = { onPropose("pickup", it); editing = null },
                        onDismiss = { editing = null },
                    )
                    "dropoff" -> MeetingPointSheet(
                        city = details.dropoffStop?.takeIf { it.isNotBlank() } ?: ride.to,
                        title = appText("Где тебя высадить?", "Һине ҡайҙа төшөрөргә?"),
                        initial = MeetingPointInput(lat = details.alightLat, lng = details.alightLng,
                            note = details.alightNote, landmarkTitle = details.alightLandmarkRu),
                        onDone = { onPropose("dropoff", it); editing = null },
                        onDismiss = { editing = null },
                    )
                }
            }
        }
    }
}


/** RS-03 А: обе строки «уточнить место» под выбором участка + шторки выбора. Ничего не
 *  обязательно: блок раскрывается по тапу, пусто — бронь как раньше. */
@Composable
internal fun BookingMeetingPoints(
    ride: Ride,
    pickupStop: String?,
    dropoffStop: String?,
    meetPickup: MeetingPointInput?,
    onMeetPickup: (MeetingPointInput) -> Unit,
    meetDropoff: MeetingPointInput?,
    onMeetDropoff: (MeetingPointInput) -> Unit,
    enabled: Boolean = true,
) {
    var editing by remember { mutableStateOf<String?>(null) }   // "pickup" | "dropoff" | null
    AppCard {
        Column(Modifier.fillMaxWidth().padding(CanonSpace.lg), verticalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
            Text(appText("Точное место (по желанию)", "Теүәл урын (телһәң)"), style = CanonBodyStrong, color = CanonText)
            Text(
                appText("Водитель увидит точку после подтверждения брони.", "Йөрөтөүсе нөктәне броньде раҫлағас күрер."),
                style = CanonCaption, color = CanonMuted,
            )
            MeetingPointRow(
                title = appText("Где тебя забрать?", "Һине ҡайҙан алырға?"),
                point = meetPickup, enabled = enabled,
                onEdit = { editing = "pickup" },
            )
            MeetingPointRow(
                title = appText("Где тебя высадить?", "Һине ҡайҙа төшөрөргә?"),
                point = meetDropoff, enabled = enabled,
                onEdit = { editing = "dropoff" },
            )
        }
    }
    when (editing) {
        "pickup" -> MeetingPointSheet(
            city = pickupStop?.takeIf { it.isNotBlank() } ?: ride.from,
            title = appText("Где тебя забрать?", "Һине ҡайҙан алырға?"),
            initial = meetPickup,
            onDone = { onMeetPickup(it); editing = null },
            onDismiss = { editing = null },
        )
        "dropoff" -> MeetingPointSheet(
            city = dropoffStop?.takeIf { it.isNotBlank() } ?: ride.to,
            title = appText("Где тебя высадить?", "Һине ҡайҙа төшөрөргә?"),
            initial = meetDropoff,
            onDone = { onMeetDropoff(it); editing = null },
            onDismiss = { editing = null },
        )
    }
}
