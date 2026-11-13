package com.yuldash.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import android.os.SystemClock
import com.yuldash.app.data.ApiException
import com.yuldash.app.data.completedBookingTraveler
import com.yuldash.app.data.PrivateBookingTraveler
import kotlinx.coroutines.delay
import java.io.IOException

private val travelerWhitespace = Regex("[\\p{Z}\\s]+")

internal fun normalizedBookingTravelerName(raw: String): String? {
    if (raw.codePointCount(0, raw.length) !in 1..120) return null
    if (raw.codePoints().noneMatch(Character::isLetter) || raw.codePoints().anyMatch {
            val category = Character.getType(it)
            category in Character.DECIMAL_DIGIT_NUMBER.toInt()..Character.OTHER_NUMBER.toInt() ||
                category in Character.CONTROL.toInt()..Character.SURROGATE.toInt() ||
                category == Character.UNASSIGNED.toInt()
        }) return null
    return raw.replace(travelerWhitespace, " ").trim()
}

@Composable
internal fun visibleBookingTraveler(traveler: PrivateBookingTraveler?): String? {
    var expired by remember(traveler) { mutableStateOf(false) }
    LaunchedEffect(traveler) {
        val remaining = traveler?.deadlineElapsedMs?.minus(SystemClock.elapsedRealtime()) ?: return@LaunchedEffect
        delay(remaining.coerceAtLeast(0L))
        expired = true
    }
    return traveler?.name?.takeIf { !expired && SystemClock.elapsedRealtime() < traveler.deadlineElapsedMs }
}

@Composable
internal fun BookingTravelerText(name: String, bookedBy: String? = null) {
    val traveler = appText("Едет", "Юлсы") + " " + name
    Text(if (bookedBy.isNullOrBlank()) traveler else "$traveler · " + appText("бронировал", "бронланы") + " " + bookedBy,
        color = CanonText, style = CanonBody)
}

@Composable
internal fun CompletedBookingTraveler(bookingId: Int, owner: Long, role: String, bookedBy: String, canAct: () -> Boolean) {
    val current by rememberUpdatedState(canAct)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var traveler by remember(bookingId, owner, role) { mutableStateOf<PrivateBookingTraveler?>(null) }
    LaunchedEffect(bookingId, owner, role, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                while (current()) {
                    val result = completedBookingTraveler(bookingId, owner, role)
                    traveler = result.getOrNull().takeIf { current() }
                    val error = result.exceptionOrNull()
                    val retryable = error is IOException ||
                        (error is ApiException && (error.status == 408 || error.status in 500..599))
                    if (traveler == null && !retryable) break
                    delay(PENDING_BOOKING_REFRESH_MS)
                }
            } finally {
                traveler = null
            }
        }
    }
    if (canAct()) visibleBookingTraveler(traveler)?.let {
        BookingTravelerText(it, bookedBy.takeIf { role == "driver" })
    }
}

@Composable
internal fun BookingTravelerChoice(
    checked: Boolean, name: String, womenOnly: Boolean, guest: Boolean, enabled: Boolean,
    onChecked: (Boolean) -> Unit, onName: (String) -> Unit,
) {
    Surface(modifier = Modifier.fillMaxWidth(), color = CanonSurface, shape = CanonCardShape) {
        Column(Modifier.padding(CanonSpace.md), verticalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
            SettingSwitchRow(Icons.Default.Person, appText("Еду не я", "Мин бармайым"),
                appText("Связь, управление бронью и SOS остаются у тебя.",
                    "Бәйләнеш, бронде идара итеү һәм SOS һиндә ҡала."), checked, enabled, onChecked)
            AnimatedVisibility(checked) {
                when {
                    womenOnly -> Text(appText("В поездку «Только женщины» можно бронировать место только для себя.",
                        "«Тик ҡатын-ҡыҙ» сәфәренә урынды үҙең өсөн генә бронларға була."), color = CanonText, style = CanonBody)
                    guest -> Text(appText("Имя пассажира понадобится после входа.",
                        "Юлсының исеме ингәндән һуң кәрәк буласаҡ."), color = CanonMuted, style = CanonBody)
                    else -> OutlinedTextField(
                        value = name, onValueChange = {
                            onName(it.substring(0, it.offsetByCodePoints(0, minOf(120, it.codePointCount(0, it.length)))))
                        }, enabled = enabled,
                        label = { Text(appText("Имя пассажира", "Юлсының исеме")) },
                        supportingText = {
                            Text(appText("Водитель увидит это имя в своей брони.",
                                "Йөрөтөүсе был исемде үҙ бронендә күрер."))
                        },
                        isError = name.isNotEmpty() && normalizedBookingTravelerName(name) == null,
                        modifier = Modifier.fillMaxWidth(), shape = CanonFieldShape, singleLine = true,
                    )
                }
            }
        }
    }
}
