package com.yuldash.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.ApiException
import com.yuldash.app.data.BookingDetailsDto
import kotlinx.coroutines.launch

@Composable
internal fun ActiveRidePayEditor(detail: BookingDetailsDto, generation: Long, isCurrent: () -> Boolean) {
    val scope = rememberCoroutineScope()
    val currentScreen by rememberUpdatedState(isCurrent)
    var method by remember(detail.bookingId, generation) { mutableStateOf(detail.payMethod) }
    var amount by remember(detail.bookingId, generation) { mutableStateOf(detail.payAmount?.toString().orEmpty()) }
    var editing by remember(detail.bookingId, generation) { mutableStateOf(false) }
    var dirty by remember(detail.bookingId, generation) { mutableStateOf(false) }
    var busy by remember(detail.bookingId, generation) { mutableStateOf(false) }
    var needsRead by remember(detail.bookingId, generation) { mutableStateOf(false) }
    var message by remember(detail.bookingId, generation) { mutableStateOf<String?>(null) }
    val allowed = if (detail.role == "driver") payMethodKeys else passengerPayChoices(detail.driverPayMethods)
    val recorded = method == detail.payMethod
    val confirmed = appText("Записали. Вторая сторона это видит.", "Яҙҙыҡ. Икенсе яҡ быны күрә.")
    val check = appText("Сохранение не подтвердилось. Проверь договорённость.", "Һаҡлау раҫланманы. Килешеүҙе тикшер.")
    val unavailable = appText("Не удалось сохранить. Проверь сеть.", "Һаҡлап булманы. Селтәрҙе тикшер.")

    fun apply(fresh: BookingDetailsDto) {
        method = fresh.payMethod
        amount = fresh.payAmount?.toString().orEmpty()
        dirty = false
        needsRead = false
    }

    LaunchedEffect(detail.payMethod, detail.payAmount) {
        if (!dirty && !busy && !needsRead) apply(detail)
    }

    fun save(readOnly: Boolean) {
        if (busy || !currentScreen() || !ApiClient.isCurrentSession(generation)) return
        if (!readOnly && (needsRead || !dirty || (!recorded && method !in allowed))) return
        val methodUpdate = method.takeUnless { recorded }
        val amountUpdate = amount.toIntOrNull()
        busy = true
        message = null
        scope.launch {
            try {
                val sent = if (readOnly) null else ApiClient.setPayAgreement(
                    detail.bookingId, methodUpdate, amountUpdate, expectedGeneration = generation,
                )
                if (!currentScreen() || !ApiClient.isCurrentSession(generation)) return@launch
                val refusal = sent?.exceptionOrNull() as? ApiException
                if (refusal != null && refusal.detailCode != "pay_method_not_accepted") {
                    message = refusal.message ?: unavailable
                    return@launch
                }
                val fresh = ApiClient.getBookingDetails(detail.bookingId, expectedGeneration = generation).getOrNull()
                if (!currentScreen() || !ApiClient.isCurrentSession(generation)) return@launch
                if (fresh == null) {
                    needsRead = true
                    message = check
                } else {
                    apply(fresh)
                    message = when {
                        refusal != null -> refusal.message
                        readOnly -> null
                        sent?.isSuccess == true ||
                            ((methodUpdate == null || methodUpdate == fresh.payMethod) &&
                                (amountUpdate == null || amountUpdate == fresh.payAmount)) -> {
                            editing = false
                            confirmed
                        }
                        else -> check
                    }
                }
            } finally {
                busy = false
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
        PayAgreementBlock(
            editable = editing && !needsRead,
            method = if (editing && !needsRead) method else detail.payMethod,
            amountText = amount,
            summaryAmount = detail.payAmount,
            onMethod = { method = it; dirty = true; message = null },
            onAmount = { amount = it; dirty = true; message = null },
            options = allowed,
            enabled = !busy,
        )
        message?.let { Text(it, style = CanonCaption, color = CanonMuted) }
        if (!editing && !needsRead) {
            AppButton(
                appText("Изменить договорённость", "Килешеүҙе үҙгәртергә"),
                onClick = { apply(detail); editing = true; message = null }, style = AppButtonStyle.Secondary,
            )
        } else AppButton(
            if (needsRead) appText("Проверить договорённость", "Килешеүҙе тикшерергә") else appText("Сохранить", "Һаҡларға"),
            onClick = { save(needsRead) },
            loading = busy, enabled = !busy && (needsRead || (dirty && (recorded || method in allowed))),
            style = AppButtonStyle.Secondary,
        )
    }
}
