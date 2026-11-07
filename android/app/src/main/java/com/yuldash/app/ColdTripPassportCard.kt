package com.yuldash.app

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Pin
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import com.yuldash.app.data.ColdTripPassport
import com.yuldash.app.data.OfflineTripSnapshots
import kotlinx.coroutines.isActive

@Composable
internal fun ColdTripPassportCard(value: ColdTripPassport, isCurrent: () -> Boolean) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(value)
    fun current() = scope.isActive && latest === value && isCurrent() && OfflineTripSnapshots.canDrawCold(value)
    val pass = value.pass
    AppCard(Modifier.fillMaxWidth().tripAuthorityDraw(::current)) {
        Column(Modifier.padding(CanonSpace.lg), verticalArrangement = Arrangement.spacedBy(CanonSpace.md)) {
            Row {
                Icon(Icons.Default.Lock, contentDescription = null, tint = CanonGreen2)
                Spacer(Modifier.width(CanonSpace.sm))
                Text(appText("Паспорт поездки", "Сәфәр паспорты"), style = CanonBodyStrong, color = CanonText)
            }
            TripPassRow(Icons.Default.Route, appText("Маршрут", "Юл"), "${pass.fromCity} → ${pass.toCity}")
            if (pass.departAt.isNotBlank()) {
                TripPassRow(Icons.Default.Schedule, appText("Время", "Ваҡыт"), formatDepart(pass.departAt))
            }
            val driver = listOf(pass.driverName, pass.driverCar).filter { it.isNotBlank() }.joinToString(" · ")
            if (driver.isNotBlank()) TripPassRow(Icons.Default.Person, appText("Водитель", "Йөрөтөүсе"), driver)
            if (pass.driverPlate.isNotBlank()) {
                TripPassRow(Icons.Default.DirectionsCar, appText("Госномер", "Дәүләт номеры"), pass.driverPlate)
            }
            if (pass.driverPhone.isNotBlank()) {
                TripPassRow(Icons.Default.Phone, appText("Телефон водителя", "Йөрөтөүсе телефоны"), pass.driverPhone)
                AppButton(appText("Позвонить", "Шылтыратыу"), onClick = {
                    if (current()) dialDriver(context, pass.driverPhone)
                }, style = AppButtonStyle.Secondary, icon = Icons.Default.Phone)
            }
            BookingSegmentNote(pass.pickupStop, pass.dropoffStop, pass.rideHasStops, pass.fromCity, pass.toCity)
            val meeting = pass.contacts()
            val meetingLabel = (if (LocalAppLanguage.current == AppLanguage.Ba) meeting.pickupBa else meeting.pickup)
                .ifBlank { if (meeting.exactMeeting) appText("Точка на карте", "Карталағы нөктә") else "" }
            if (meetingLabel.isNotBlank()) {
                TripPassRow(Icons.Default.LocationOn, appText("Точка сбора", "Йыйылыу урыны"), meetingLabel)
            }
            val lat = meeting.pickupLat
            val lng = meeting.pickupLng
            if (lat != null && lng != null) {
                val label = appText("Место встречи", "Осрашыу урыны")
                AppButton(appText("Открыть точку на карте", "Нөктәне картала асыу"), onClick = {
                    if (!current()) return@AppButton
                    val uri = Uri.parse("geo:$lat,$lng?q=$lat,$lng(" + Uri.encode(label) + ")")
                    runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, uri), null)) }
                }, style = AppButtonStyle.Secondary, icon = Icons.Default.Map)
            }
            if (pass.boardingCode.isNotBlank()) {
                TripPassRow(Icons.Default.Pin, appText("Код посадки", "Ултырыу коды"), pass.boardingCode)
            }
            if (pass.payAmount != null || pass.price > 0 || pass.payMethod != null) {
                val method = pass.payMethod?.takeIf { it in payMethodKeys }?.let { payMethodLabel(it) }
                    ?: appText("Способ оплаты не сохранён", "Түләү ысулы һаҡланмаған")
                val amount = kopToRub(rideshareRubToKop(pass.effectivePayAmount))
                TripPassRow(Icons.Default.Payments, appText("Оплата", "Түләү"), "$amount · $method")
            }
        }
    }
}
