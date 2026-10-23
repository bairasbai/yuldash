package com.yuldash.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.yuldash.app.data.DriverBookingDto

// Окно «Снять поездку?» кабинета водителя (ProfileScreen.kt, DriverCabinetContent). Вынесено сюда,
// когда появилось предупреждение про пассажиров в машине (решение 23.10.2026): отмена «в пути»
// водителю оставлена, но он должен знать, что делает, — довёз, значит «Завершить рейс»; снимает —
// пассажирам придёт срочное уведомление, а близкие, с кем они поделились поездкой, увидят по
// ссылке «отменили в пути». Про SMS не обещаем: на проде SMS выключены (сторож SosNoSmsPromiseGuardTest).

/** Сколько броней этой поездки сейчас «в пути» (пассажир в машине). 0 — нет, или старый сервер без `ride_id`. */
internal fun onboardPassengers(rideId: Int?, bookings: List<DriverBookingDto>): Int =
    if (rideId == null || rideId <= 0) 0 else bookings.count { it.rideId == rideId && it.status == "onboard" }

@Composable
internal fun rideCancelOnboardWarning(onboard: Int): String = appText(
    "В машине пассажиры: $onboard. Если довёз — нажми «Завершить рейс». Если снимаешь — им придёт уведомление, " +
        "а близкие, с кем они поделились поездкой, увидят по ссылке, что поездку отменили в пути.",
    "Машинала юлаусылар: $onboard. Илтеп еткерһәң — «Рейсты тамамлау» төймәһенә баҫ. Алаһың икән — уларға хәбәр килә, " +
        "ә улар сәфәр менән бүлешкән яҡындар һылтанма буйынса сәфәрҙең юлда кире алынғанын күрә.",
)

/**
 * Текст окна «Снять поездку?»: сколько людей рассчитывали на эту машину и — если кто-то уже в ней —
 * предупреждение отдельной тёплой плашкой. «Все брони будут отменены» — правда, но безличная:
 * одно дело снять пустой рейс, другое — оставить на дороге троих (аудит сценариев 30.08).
 * [booked] — мест всего минус свободные; [onboard] — брони «в пути» ([onboardPassengers]).
 */
@Composable
internal fun RideCancelConfirmText(from: String, to: String, booked: Int, onboard: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(CanonSpace.md)) {
        Text(appText(
            "$from → $to. " + when (booked) {
                0 -> "Пассажиров пока нет — отменяем только объявление."
                1 -> "Один человек уже забронировал место. Бронь снимется, ему придёт уведомление."
                else -> "Места забронировали $booked человек. Все брони снимутся, всем придёт уведомление."
            },
            "$from → $to. " + when (booked) {
                0 -> "Юлаусылар әлегә юҡ — тик белдереүҙе генә кире алабыҙ."
                1 -> "Бер кеше урын брондаған. Брон кире алына, уға хәбәр китә."
                else -> "Урындарҙы $booked кеше брондаған. Бөтә брондар кире алына, барыһына ла хәбәр китә."
            },
        ))
        if (onboard > 0) {
            Surface(color = CanonWarnBg, shape = CanonFieldShape, modifier = Modifier.fillMaxWidth().testTag("rideCancelOnboardWarning")) {
                Row(Modifier.padding(CanonSpace.md), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = CanonWarn, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(CanonSpace.sm))
                    Text(rideCancelOnboardWarning(onboard), style = CanonCaption, color = CanonText)
                }
            }
        }
    }
}
