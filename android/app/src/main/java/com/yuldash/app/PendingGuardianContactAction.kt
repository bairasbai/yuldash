package com.yuldash.app

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.DriverBookingDto
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
internal fun PendingGuardianContactAction(booking: DriverBookingDto, owner: Long) {
    key(booking, owner) {
        var open by remember { mutableStateOf(false) }
        TextButton(onClick = { if (ApiClient.isCurrentSession(owner)) open = true }) {
            Text(appText("Контакт взрослого", "Оло кешенең бәйләнеше"))
        }
        if (open && ApiClient.isCurrentSession(owner)) {
            PendingGuardianContactDialog(booking.bookingId, owner, { open }) { open = false }
        }
    }
}

@Composable
private fun PendingGuardianContactDialog(bookingId: Int, owner: Long, isOpen: () -> Boolean, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var busy by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    fun current() = isOpen() && scope.isActive && ApiClient.isCurrentSession(owner)
    suspend fun read(dial: Boolean) {
        busy = true
        failed = false
        val phone = ApiClient.getPendingGuardianPhone(bookingId, owner).getOrNull()
        if (!current()) return
        busy = false
        failed = phone == null
        if (dial && phone != null) {
            ApiClient.runIfCurrentSession(owner) { if (current()) dialDriver(context, phone) }
        }
    }
    LaunchedEffect(bookingId, owner) { read(dial = false) }
    TripAuthorityContent(::current) {
        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = CanonSurface,
            title = { Text(appText("Взрослый на связи", "Оло кеше бәйләнештә"), color = CanonText) },
            text = {
                Text(
                    when {
                        busy -> appText("Проверяем контакт…", "Бәйләнеште тикшерәбеҙ…")
                        failed -> appText("Контакт недоступен. Обнови бронь или попробуй ещё раз.",
                            "Бәйләнеш юҡ. Бронде яңырт йәки тағы һынап ҡара.")
                        else -> appText("Можно связаться со взрослым по этой брони.", "Был бронь буйынса оло кеше менән бәйләнешкә сығырға була.")
                    },
                    color = CanonText,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (!busy && current()) {
                            busy = true
                            val dial = !failed
                            scope.launch { read(dial) }
                        }
                    },
                    enabled = !busy,
                ) {
                    Text(if (failed) appText("Повторить", "Ҡабатлау")
                        else appText("Позвонить взрослому", "Оло кешегә шылтыратырға"))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(appText("Закрыть", "Ябыу")) }
            },
        )
    }
}
