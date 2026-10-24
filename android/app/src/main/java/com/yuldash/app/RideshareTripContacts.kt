package com.yuldash.app

// Связь с водителем после подтверждения брони: телефон, машина с номером, место встречи.
//
// Приёмка 18.10.2026 (находка 1). Сервер отдаёт эти данные в деталях брони участнику подтверждённой
// брони (`contact_unlocked`), но экран «Моя поездка» брал их только из офлайн-паспорта, а паспорт
// писал лишь экран «Детали поездки», открытый уже после подтверждения. Пассажир, который открыл
// поездку из «Моих поездок» кнопкой «Открыть поездку», оставался без телефона водителя, номера
// машины и точки встречи. Теперь экран поездки берёт их из деталей сам и дописывает тот же паспорт —
// без сети на трассе они тоже на месте.

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.BookingDetailsDto
import com.yuldash.app.data.TripPass
import com.yuldash.app.data.TripPassStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Что пассажир знает о водителе после подтверждения: из ответа сервера или своего паспорта. */
internal data class TripContacts(
    val phone: String,
    val car: String,          // «белая Lada Vesta» — цвет и модель
    val plate: String,
    val pickup: String,
    val pickupLat: Double?,
    val pickupLng: Double?,
)

/** Брони, по которым открыты телефон и встреча (сервер решает сам — `contact_unlocked`). */
internal val CONTACT_STATUSES = setOf("confirmed", "onboard")

/** Как часто «Детали поездки» сверяются с сервером, пока бронь ждёт водителя (как опрос «Моей поездки»). */
internal const val PENDING_BOOKING_REFRESH_MS = 12_000L

/**
 * Контакты водителя — только пассажиру и только по живой подтверждённой брони, которую сервер
 * сам назвал открытой. Водителю показывать нечего: в его деталях — его же телефон.
 */
internal fun passengerContacts(d: BookingDetailsDto): TripContacts? =
    if (d.role != "passenger" || !d.contactUnlocked || d.status !in CONTACT_STATUSES) null
    else TripContacts(
        phone = d.driverPhone,
        car = listOf(d.driverCarColor, d.driverCar).filter { it.isNotBlank() }.joinToString(" "),
        plate = d.driverPlate,
        pickup = d.pickup,
        pickupLat = d.pickupLat,
        pickupLng = d.pickupLng,
    )

/** Водитель звонит пассажиру только по живой подтверждённой брони, номер которой сервер открыл. */
internal fun driverCanCallPassenger(d: BookingDetailsDto?, bookingStatus: String, terminal: Boolean): Boolean =
    !terminal && d != null && d.role == "driver" && d.contactUnlocked && d.passengerPhone.isNotBlank() &&
        (bookingStatus.isBlank() || bookingStatus in CONTACT_STATUSES)

/** Поздние details не откатывают уже известную посадку/закрытие брони. */
internal fun offlinePassDetails(d: BookingDetailsDto?, bookingId: Int, status: String): BookingDetailsDto? =
    d?.takeIf { it.bookingId == bookingId && passengerContacts(it) != null && (status.isBlank() || it.status == status) }

/** После повтора кода создаём полный паспорт, а не обновляем код в отсутствующей копии. */
internal suspend fun saveActiveTripBoardingCode(
    context: Context, bookingId: Int, code: String, details: BookingDetailsDto?, expectedGeneration: Long,
    retryMigration: Boolean, shouldContinue: () -> Boolean,
): Boolean = if (details != null) {
    saveTripPass(context, details, expectedGeneration, retryMigration, code, shouldContinue)
} else withContext(Dispatchers.IO) {
    shouldContinue() && TripPassStore.updateBoardingCode(context, bookingId, code, expectedGeneration, retryMigration)
}

/** Те же данные из офлайн-паспорта — на трассе без связи. */
internal fun TripPass.contacts(): TripContacts =
    TripContacts(driverPhone, driverCar, driverPlate, pickup, pickupLat, pickupLng)

/** «белая Lada Vesta · А123ВС 102» — машину у подъезда сверяют по номеру, а не по цвету. */
internal fun tripCarSummary(contacts: TripContacts?, rideCar: String?): String =
    listOf(contacts?.car.orEmpty().ifBlank { rideCar.orEmpty() }, contacts?.plate.orEmpty())
        .filter { it.isNotBlank() }.joinToString(" · ")

/** Поделиться текстом (поездка, ссылка, квитанция) через системный выбор приложения. */
internal fun shareRide(context: Context, text: String, chooserTitle: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, chooserTitle))
}

/** Набрать номер водителя: звонит сам человек, приложение только открывает «Телефон». */
internal fun dialDriver(context: Context, phone: String) {
    if (phone.isBlank()) return
    runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))) }
}

/**
 * F11 — сохранить офлайн-паспорт брони из подтверждённых деталей. Телефон водителя (ПДн) уходит
 * в secure-хранилище TripPassStore и НЕ логируется. [code] — уже загруженный код посадки (экран
 * поездки: второй запрос не нужен); null — дотянуть его с сервера (экран брони). Без кода паспорт
 * неполный — не пишем.
 */
internal suspend fun saveTripPass(
    context: Context,
    d: BookingDetailsDto,
    expectedGeneration: Long,
    retryMigration: Boolean = false,
    code: String? = null,
    shouldContinue: () -> Boolean = { true },
): Boolean {
    if (!shouldContinue() || passengerContacts(d) == null || expectedGeneration != ApiClient.queueSessionGeneration()) return false
    val boardingCode = (code ?: ApiClient.getBoardingCode(d.bookingId).getOrNull())
        ?.takeIf { it.isNotBlank() } ?: return false
    return withContext(Dispatchers.IO) { if (!shouldContinue()) false else TripPassStore.save(
        context,
        TripPass(
            bookingId = d.bookingId,
            fromCity = d.fromCity,
            toCity = d.toCity,
            departAt = d.departAt,
            driverName = d.driverName,
            driverCar = listOf(d.driverCarColor, d.driverCar).filter { it.isNotBlank() }.joinToString(" "),
            driverPlate = d.driverPlate,
            driverPhone = d.driverPhone,
            boardingCode = boardingCode,
            pickup = d.pickup,
            pickupLat = d.pickupLat,
            pickupLng = d.pickupLng,
            price = d.price,
            seats = d.seats,
            paymentNote = "",
            savedAt = System.currentTimeMillis(),
            payMethod = d.payMethod,
            payAmount = d.payAmount,
            pickupStop = d.pickupStop, dropoffStop = d.dropoffStop, rideHasStops = d.rideHasStops,
        ),
        retryMigration = retryMigration,
        expectedGeneration = expectedGeneration,
    ) }
}

/**
 * «Связь и встреча» в «Моей поездке» пассажира: номер водителя (нажал — открылся набор) и точка
 * встречи с кнопкой карты. Машина с номером — в карточке водителя над ней.
 */
@Composable
internal fun ActiveTripContactsCard(contacts: TripContacts, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    AppCard(modifier) {
        Column(Modifier.padding(CanonSpace.lg), verticalArrangement = Arrangement.spacedBy(CanonSpace.md)) {
            if (contacts.phone.isNotBlank()) {
                ContactRow(
                    icon = Icons.Default.Phone,
                    label = appText("Телефон водителя", "Йөрөтөүсенең телефоны"),
                    value = contacts.phone,
                    modifier = Modifier.clip(CanonItemShape).clickable(
                        onClickLabel = appText("Позвонить", "Шылтыратыу"),
                        role = Role.Button,
                    ) { dialDriver(context, contacts.phone) },
                )
            }
            ContactRow(
                icon = Icons.Default.LocationOn,
                label = appText("Место встречи", "Осрашыу урыны"),
                value = contacts.pickup.ifBlank { appText("Уточни точку в чате", "Нөктәне чатта асыҡла") },
            )
            val lat = contacts.pickupLat
            val lng = contacts.pickupLng
            if (lat != null && lng != null) {
                val meet = appText("Место встречи", "Осрашыу урыны")
                AppButton(
                    text = appText("Открыть точку на карте", "Нөктәне картала асыу"),
                    onClick = {
                        val uri = Uri.parse("geo:$lat,$lng?q=$lat,$lng(" + Uri.encode(meet) + ")")
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    },
                    style = AppButtonStyle.Secondary,
                    icon = Icons.Default.Map,
                )
            }
        }
    }
}

@Composable
private fun ContactRow(icon: ImageVector, label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(color = CanonMint, shape = CircleShape) {
            Icon(icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(CanonSpace.sm).size(18.dp))
        }
        Spacer(Modifier.width(CanonSpace.md))
        Column(Modifier.weight(1f)) {
            Text(label, style = CanonMicro, color = CanonMuted)
            Text(value, style = CanonBodyStrong, color = CanonText)
        }
    }
}

/** «Поделиться с близким», а близких ещё нет: не тупик, а кнопка «Добавить близкого» (приёмка 18.10, п.8). */
@Composable
internal fun NoTrustedContactsHint(onAdd: () -> Unit) {
    Text(appText("Сначала добавь доверенный контакт в профиле", "Башта профилдә ышаныслы контакт өҫтә"), color = CanonMuted)
    TextButton(onClick = onAdd, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(appText("Добавить близкого", "Яҡын кешене өҫтәү"), color = CanonGreen2, fontWeight = FontWeight.Bold)
    }
}
