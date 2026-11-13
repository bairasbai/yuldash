package com.yuldash.app

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
import androidx.compose.material3.Text
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive

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

@Composable
private fun LocalAppLanguageSafe(): Boolean = LocalAppLanguage.current == AppLanguage.Ba

internal fun hasOwnPickup(d: com.yuldash.app.data.BookingDetailsDto?): Boolean = d?.role == "passenger" &&
    (d.meetLat != null || d.meetNote.isNotBlank() || d.meetLandmarkRu.isNotBlank() || d.meetLandmarkBa.isNotBlank())

/** Свои данные из ответа сервера доступны пассажиру независимо от открытия чужих контактов. */
@Composable
internal fun OwnMeetingPoints(details: com.yuldash.app.data.BookingDetailsDto?, isCurrent: () -> Boolean = { currentOwnBookingDetails(details) != null }) {
    val d = details?.takeIf { it.role == "passenger" } ?: return
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val latest by androidx.compose.runtime.rememberUpdatedState(d)
    fun current() = scope.isActive && latest === d && isCurrent()
    TripAuthorityContent(::current) {
        Column(verticalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
            OwnMeetingPoint(d, pickup = true, isCurrent = ::current)
            OwnMeetingPoint(d, pickup = false, isCurrent = ::current)
        }
    }
}

@Composable
private fun OwnMeetingPoint(d: com.yuldash.app.data.BookingDetailsDto, pickup: Boolean, isCurrent: () -> Boolean) {
    val ru = if (pickup) d.meetLandmarkRu else d.alightLandmarkRu
    val ba = if (pickup) d.meetLandmarkBa else d.alightLandmarkBa
    val note = if (pickup) d.meetNote else d.alightNote
    val text = listOf(if (LocalAppLanguageSafe()) ba.ifBlank { ru } else ru, note)
        .filter { it.isNotBlank() }.joinToString(" · ")
    val lat = if (pickup) d.meetLat else d.alightLat
    val lng = if (pickup) d.meetLng else d.alightLng
    if (text.isBlank() && (lat == null || lng == null)) return
    val context = androidx.compose.ui.platform.LocalContext.current
    val title = if (pickup) appText("Место посадки", "Ултырыу урыны") else appText("Место высадки", "Төшөү урыны")
    AppCard {
        Column(Modifier.fillMaxWidth().padding(CanonSpace.lg), verticalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
            Text(title, style = CanonBodyStrong, color = CanonText)
            Text(text.ifBlank { appText("Точка на карте", "Карталағы нөктә") }, style = CanonBody, color = CanonText)
            if (lat != null && lng != null) {
                AppButton(
                    text = if (pickup) appText("Маршрут к месту посадки", "Ултырыу урынына маршрут")
                           else appText("Маршрут к месту высадки", "Төшөү урынына маршрут"),
                    style = AppButtonStyle.Secondary, icon = Icons.Default.Map,
                    onClick = {
                        if (!isCurrent()) return@AppButton
                        val uri = android.net.Uri.parse("geo:$lat,$lng?q=$lat,$lng(" + android.net.Uri.encode(title) + ")")
                        runCatching { context.startActivity(android.content.Intent.createChooser(
                            android.content.Intent(android.content.Intent.ACTION_VIEW, uri), null)) }
                    },
                )
            }
        }
    }
}

/** Один блок своих точек и команд в деталях брони и в основной поездке. */
@Composable
internal fun PassengerMeetingPoints(details: com.yuldash.app.data.BookingDetailsDto?, isCurrentParent: (() -> Boolean)? = null, onChanged: () -> Unit) {
    val d = details?.takeIf { it.role == "passenger" } ?: return
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val session = remember(d.bookingId) { com.yuldash.app.data.ApiClient.queueSessionGeneration() }
    val latest by androidx.compose.runtime.rememberUpdatedState(d)
    fun mounted() = scope.isActive && latest === d && com.yuldash.app.data.ApiClient.isCurrentSession(session) && (isCurrentParent?.invoke() != false)
    fun current() = mounted() && currentOwnBookingDetails(d) != null
    var busy by remember(d.bookingId) { mutableStateOf(false) }
    var error by remember(d.bookingId) { mutableStateOf<String?>(null) }
    val failed = appText("Не получилось изменить место. Повторить?", "Урынды үҙгәртеп булманы. Ҡабатлайынмы?")
    fun submit(onSaved: () -> Unit = {}, request: suspend () -> Result<*>) {
        if (busy || !current()) return
        busy = true; error = null
        scope.launch {
            try {
                if (!current()) return@launch
                val result = request()
                if (!mounted()) return@launch
                result.onSuccess { onSaved(); onChanged() }.onFailure { e ->
                    error = (e as? com.yuldash.app.data.ApiException)?.message ?: failed
                    if ((e as? com.yuldash.app.data.ApiException)?.detailCode == "meeting_point_stale") onChanged()
                }
            } finally { busy = false }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
        OwnMeetingPoints(d, ::current)
        BookingMeetingActions(d, busy = busy, error = error, isCurrent = ::current,
            onPropose = { kind, point, onSaved -> submit(onSaved) {
                com.yuldash.app.data.ApiClient.proposeMeetingPoint(d.bookingId, kind, point, d.meetingVersion, owner = session)
            } },
            onWithdraw = { d.meetingProposal?.let { proposal -> submit {
                com.yuldash.app.data.ApiClient.withdrawMeetingPoint(d.bookingId, proposal.proposalId, d.meetingVersion, owner = session)
            } } })
        error?.let { Text(it, style = CanonCaption, color = CanonText) }
    }
}

internal fun meetingAfterStopChange(previous: String?, next: String?, point: MeetingPointInput?): MeetingPointInput? =
    point.takeIf { previous == next }

internal fun meetingPointDraft(d: com.yuldash.app.data.BookingDetailsDto, kind: String, isBa: Boolean = false): MeetingPointInput {
    val pickup = kind == "pickup"
    val ru = if (pickup) d.meetLandmarkRu else d.alightLandmarkRu
    val ba = if (pickup) d.meetLandmarkBa else d.alightLandmarkBa
    return MeetingPointInput(
        lat = if (pickup) d.meetLat else d.alightLat, lng = if (pickup) d.meetLng else d.alightLng,
        landmarkId = if (pickup) d.meetLandmarkId else d.alightLandmarkId,
        note = if (pickup) d.meetNote else d.alightNote,
        landmarkTitle = if (isBa) ba.ifBlank { ru } else ru,
    )
}

internal fun meetingEditableKinds(status: String): List<String> = when (status) {
    "pending", "confirmed" -> listOf("pickup", "dropoff")
    "onboard" -> listOf("dropoff")
    else -> emptyList()
}

/** RS-03 Б: блок «место» на экране поездки пассажира.
 *  Есть предложение — «ждём ответа» + «Отозвать»; pending — «Изменить место» (сразу);
 *  confirmed/onboard — «Предложить другое место». Точку меняет только пассажир. */
@Composable
internal fun BookingMeetingActions(
    details: com.yuldash.app.data.BookingDetailsDto,
    busy: Boolean = false,
    error: String? = null,
    isCurrent: () -> Boolean = { currentOwnBookingDetails(details) != null },
    onPropose: (kind: String, point: MeetingPointInput, onSaved: () -> Unit) -> Unit,
    onWithdraw: () -> Unit,
) {
    if (details.role != "passenger") return
    val actionScope = androidx.compose.runtime.rememberCoroutineScope()
    val latest by androidx.compose.runtime.rememberUpdatedState(details)
    fun current() = actionScope.isActive && latest === details && isCurrent()
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
                    onClick = { if (current()) onWithdraw() }, enabled = !busy,
                    style = AppButtonStyle.Secondary,
                )
            } else {
                var editing by remember { mutableStateOf<String?>(null) }
                meetingEditableKinds(status).forEach { kind ->
                    AppButton(
                        text = if (kind == "pickup") appText("Изменить место посадки", "Ултырыу урынын үҙгәртеү")
                               else appText("Изменить место высадки", "Төшөү урынын үҙгәртеү"),
                        onClick = { if (current()) editing = kind }, enabled = !busy,
                        style = AppButtonStyle.Secondary, icon = Icons.Default.Edit,
                    )
                }
                if (status == "confirmed" || status == "onboard") {
                    Text(
                        appText("Пока водитель не ответил — действует прежнее место.", "Йөрөтөүсе яуап биргәнсе — иҫке урын ҡала."),
                        style = CanonCaption, color = CanonMuted,
                    )
                }
                when (editing?.takeIf { current() && it in meetingEditableKinds(status) }) {
                    "pickup" -> MeetingPointSheet(
                        city = details.pickupStop?.takeIf { it.isNotBlank() } ?: details.fromCity,
                        title = appText("Где тебя забрать?", "Һине ҡайҙан алырға?"),
                        initial = meetingPointDraft(details, "pickup", LocalAppLanguageSafe()),
                        onDone = { if (current()) onPropose("pickup", it) { editing = null } },
                        onDismiss = { editing = null }, busy = busy, error = error,
                    )
                    "dropoff" -> MeetingPointSheet(
                        city = details.dropoffStop?.takeIf { it.isNotBlank() } ?: details.toCity,
                        title = appText("Где тебя высадить?", "Һине ҡайҙа төшөрөргә?"),
                        initial = meetingPointDraft(details, "dropoff", LocalAppLanguageSafe()),
                        onDone = { if (current()) onPropose("dropoff", it) { editing = null } },
                        onDismiss = { editing = null }, busy = busy, error = error,
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

/** Черновик участка и его точек меняется согласованно; владелец экрана не дублирует правила сброса. */
internal class BookingMeetingDraft(
    private val ride: Ride?,
    pickupState: androidx.compose.runtime.MutableState<String?>,
    dropoffState: androidx.compose.runtime.MutableState<String?>,
    meetingState: androidx.compose.runtime.MutableState<MeetingPointInput?>,
    alightingState: androidx.compose.runtime.MutableState<MeetingPointInput?>,
) {
    var pickup by pickupState
    var dropoff by dropoffState
    var meeting by meetingState
    var alighting by alightingState
    fun selectPickup(next: String?) {
        val nextDropoff = ride?.let { keepValidDropoff(it, next, dropoff) }
        meeting = meetingAfterStopChange(pickup, next, meeting)
        alighting = meetingAfterStopChange(dropoff, nextDropoff, alighting)
        pickup = next; dropoff = nextDropoff
    }
    fun selectDropoff(next: String?) {
        alighting = meetingAfterStopChange(dropoff, next, alighting)
        dropoff = next
    }
}

@Composable
internal fun rememberBookingMeetingDraft(ride: Ride?, session: Long, guest: RestoredGuestBooking? = null): BookingMeetingDraft {
    val pickup = androidx.compose.runtime.saveable.rememberSaveable(ride?.id, session) {
        mutableStateOf(if (guest != null) guest.pickup else ride?.let(::initialPickupStop))
    }
    val dropoff = androidx.compose.runtime.saveable.rememberSaveable(ride?.id, session) {
        mutableStateOf(if (guest != null) guest.dropoff else ride?.let(::initialDropoffStop))
    }
    val meeting = androidx.compose.runtime.saveable.rememberSaveable(ride?.id, session, stateSaver = com.yuldash.app.data.MeetingPointInputSaver) { mutableStateOf<MeetingPointInput?>(null) }
    val alighting = androidx.compose.runtime.saveable.rememberSaveable(ride?.id, session, stateSaver = com.yuldash.app.data.MeetingPointInputSaver) { mutableStateOf<MeetingPointInput?>(null) }
    return remember(ride, session, pickup, dropoff, meeting, alighting) { BookingMeetingDraft(ride, pickup, dropoff, meeting, alighting) }
}
