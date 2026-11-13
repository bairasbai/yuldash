package com.yuldash.app

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.ApiException
import com.yuldash.app.data.DriverBookingDto
import java.io.IOException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
internal fun PendingGuardianContactAction(booking: DriverBookingDto, owner: Long) {
    key(booking) {
        GuardianContactAction(
            booking.bookingId, owner,
            readPhone = { ApiClient.getPendingGuardianPhone(booking.bookingId, owner) },
        )
    }
}

@Composable
internal fun CompletedGuardianContactAction(bookingId: Int, owner: Long, canAct: () -> Boolean) {
    val parentCurrent by rememberUpdatedState(canAct)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var available by remember(bookingId, owner) { mutableStateOf(false) }
    var retryable by remember(bookingId, owner) { mutableStateOf(false) }
    var attempt by remember(bookingId, owner) { mutableStateOf(0) }
    var resumeGeneration by remember(bookingId, owner, lifecycle) { mutableStateOf(0) }
    fun current() = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
        ApiClient.isCurrentSession(owner) && parentCurrent()

    DisposableEffect(bookingId, owner, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                resumeGeneration++
                available = false
                retryable = false
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(bookingId, owner, lifecycle, attempt) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val generation = resumeGeneration
            available = false
            retryable = false
            if (!current()) return@repeatOnLifecycle
            val result = ApiClient.getCompletedGuardianPhone(bookingId, owner)
            if (!current() || resumeGeneration != generation) return@repeatOnLifecycle
            available = result.getOrNull() != null
            retryable = result.exceptionOrNull().isRetryableGuardianRead()
        }
    }
    if (available && current()) {
        val generation = resumeGeneration
        key(generation) {
            GuardianContactAction(
                bookingId, owner,
                canAct = { current() && resumeGeneration == generation },
                readPhone = { ApiClient.getCompletedGuardianPhone(bookingId, owner) },
                onUnavailable = { available = false },
            )
        }
    } else if (retryable && current()) {
        TextButton(onClick = {
            if (retryable && current()) {
                retryable = false
                attempt++
            }
        }) {
            Text(appText("Проверить контакт взрослого", "Оло кешенең бәйләнешен тикшерергә"))
        }
    }
}

private fun Throwable?.isRetryableGuardianRead(): Boolean =
    this is IOException || (this is ApiException && (status == 408 || status == 429 || status >= 500))

@Composable
private fun GuardianContactAction(
    bookingId: Int,
    owner: Long,
    readPhone: suspend () -> Result<String?>,
    canAct: () -> Boolean = { true },
    onUnavailable: (() -> Unit)? = null,
) {
    val parentCurrent by rememberUpdatedState(canAct)
    key(bookingId, owner) {
        var open by remember { mutableStateOf(false) }
        fun current() = ApiClient.isCurrentSession(owner) && parentCurrent()
        TextButton(onClick = { if (current()) open = true }) {
            Text(appText("Контакт взрослого", "Оло кешенең бәйләнеше"))
        }
        if (open && current()) {
            GuardianContactDialog(bookingId, owner, { open && current() }, readPhone, onUnavailable) { open = false }
        }
    }
}

@Composable
private fun GuardianContactDialog(
    bookingId: Int,
    owner: Long,
    isOpen: () -> Boolean,
    readPhone: suspend () -> Result<String?>,
    onUnavailable: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var busy by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    fun current() = isOpen() && scope.isActive && ApiClient.isCurrentSession(owner)
    suspend fun read(dial: Boolean) {
        busy = true
        failed = false
        val result = readPhone()
        if (!current()) return
        val phone = result.getOrNull()
        if (phone == null && onUnavailable != null && !result.exceptionOrNull().isRetryableGuardianRead()) {
            onDismiss()
            onUnavailable()
            return
        }
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
