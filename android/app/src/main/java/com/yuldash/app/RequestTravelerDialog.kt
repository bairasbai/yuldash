package com.yuldash.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.ApiException
import com.yuldash.app.data.RequestTravelerApi
import java.util.UUID
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
internal fun RequestTravelerConsentDialog(price: Int, busy: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(appText("Принять встречную цену?", "Ҡаршы хаҡты ҡабул итергәме?")) },
        text = { Text(formatTaxiKop(rideshareRubToKop(price))) },
        confirmButton = {
            TextButton(enabled = !busy, onClick = onConfirm) { Text(appText("Подтвердить цену", "Хаҡты раҫлау")) }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text(appText("Закрыть", "Ябыу")) }
        },
    )
}

@Composable
internal fun RequestTravelerDialog(
    responseId: Int, owner: Long, parentCurrent: () -> Boolean, onDismiss: () -> Unit, onAccepted: (Int) -> Unit,
) {
    val currentParent by rememberUpdatedState(parentCurrent)
    val accepted by rememberUpdatedState(onAccepted)
    val scope = rememberCoroutineScope()
    fun current() = currentParent() && ApiClient.isCurrentSession(owner)
    var offer by remember(responseId, owner) { mutableStateOf<JSONObject?>(null) }
    var lastVersion by remember(responseId, owner) { mutableStateOf<String?>(null) }
    var reload by remember(responseId, owner) { mutableIntStateOf(0) }
    var loading by remember(responseId, owner) { mutableStateOf(true) }
    var busy by remember(responseId, owner) { mutableStateOf(false) }
    var error by remember(responseId, owner) { mutableStateOf<String?>(null) }
    var other by remember(responseId, owner) { mutableStateOf<Boolean?>(null) }
    var name by remember(responseId, owner) { mutableStateOf("") }
    var minor by remember(responseId, owner) { mutableStateOf(false) }
    var guardian by remember(responseId, owner) { mutableStateOf("") }
    var phone by remember(responseId, owner) { mutableStateOf("") }
    var original by remember(responseId, owner) { mutableStateOf<Pair<String, JSONObject>?>(null) }
    val failed = appText("Не получилось проверить условия. Повтори попытку.", "Шарттарҙы тикшереп булманы. Ҡабатлап ҡара.")

    LaunchedEffect(responseId, owner, reload) {
        loading = true
        error = null
        offer = null
        RequestTravelerApi.offer(responseId, owner).onSuccess { fresh ->
            if (!current()) return@onSuccess
            val bookingId = fresh.optInt("booking_id")
            if (fresh.optString("state") == "accepted" && bookingId > 0) {
                accepted(bookingId)
                return@onSuccess
            }
            if (fresh.optString("version").length != 64) {
                error = failed
                return@onSuccess
            }
            if (lastVersion != fresh.optString("version")) {
                original = null
                other = true.takeIf { fresh.optBoolean("for_another") }
                name = ""; minor = false; guardian = ""; phone = ""
            }
            lastVersion = fresh.optString("version")
            offer = fresh
        }.onFailure { if (current()) error = serverSaid(it, failed) }
        if (current()) loading = false
    }

    val row = offer
    val canSubmit = !loading && !busy && row != null && other != null &&
        (row.optString("last_offer_by") != "passenger" || row.optBoolean("driver_consented")) &&
        (other == false || normalizedBookingTravelerName(name) != null) &&
        (!minor || (!row.optBoolean("no_minors") && guardian.isNotBlank() && phone.isNotBlank()))
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(appText("Подтверждение пассажира", "Юлсыны раҫлау")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
                if (loading) Text(appText("Проверяем условия…", "Шарттарҙы тикшерәбеҙ…"))
                error?.let { Text(it, color = CanonRed) }
                if (row != null && !loading) {
                    Text(row.optString("from_city") + " → " + row.optString("to_city"))
                    Text(formatDepart(row.optString("depart_at")) + " · " + formatTaxiKop(rideshareRubToKop(row.optInt("price"))))
                    Text(appText("Количество мест", "Урындар һаны") + " · " + row.optInt("seats"))
                    if (row.optString("last_offer_by") == "passenger" && !row.optBoolean("driver_consented")) {
                        Text(appText("Ждём согласия водителя на встречную цену.", "Йөрөтөүсенең ҡаршы хаҡҡа ризалығын көтәбеҙ."))
                    }
                    val editable = !busy && original == null
                    if (!row.optBoolean("for_another")) {
                        TextButton(onClick = { other = false; name = "" }, enabled = editable) { Text(appText("Еду я", "Мин барам")) }
                        TextButton(onClick = { other = true }, enabled = editable) { Text(appText("Едет другой человек", "Башҡа кеше бара")) }
                    }
                    if (other == true) OutlinedTextField(name, { name = it.take(120) }, Modifier.fillMaxWidth(), enabled = editable,
                        label = { Text(appText("Имя пассажира", "Юлсының исеме")) }, singleLine = true)
                    if (!row.optBoolean("no_minors")) MinorPassengerBlock(
                        checked = minor, guardianName = guardian, guardianPhone = phone,
                        onChecked = { if (editable) { minor = it; if (!it) { guardian = ""; phone = "" } } },
                        onName = { if (editable) guardian = it }, onPhone = { if (editable) phone = it },
                    )
                }
                TextButton(onClick = { reload++ }, enabled = !busy && !loading) {
                    Text(appText("Проверить отклик", "Яуапты тикшереү"))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = canSubmit, onClick = {
                if (busy || !canSubmit || !current() || row == null) return@TextButton
                val attempt = original ?: (UUID.randomUUID().toString() to JSONObject()
                    .put("version", row.optString("version")).put("for_another", other == true)
                    .put("minor_passenger", minor).apply {
                        if (other == true) put("traveler_name", normalizedBookingTravelerName(name))
                        if (minor) { put("minor_guardian_name", guardian.trim()); put("minor_guardian_phone", phone.trim()) }
                    })
                original = attempt
                busy = true
                error = null
                scope.launch {
                    RequestTravelerApi.confirm(responseId, attempt.second, attempt.first, owner)
                        .onSuccess { if (current()) accepted(it) }
                        .onFailure {
                            if (!current()) return@onFailure
                            val apiError = it as? ApiException
                            val status = apiError?.status
                            if (status != null && status in 400..499 && status != 408) {
                                original = null
                                if (status != 422 && status != 429 && apiError.detailCode != "guardian_phone_invalid" &&
                                    apiError.detailCode != "booking_traveler_women_only"
                                ) {
                                    offer = null
                                    lastVersion = null
                                    other = null; name = ""; minor = false; guardian = ""; phone = ""
                                }
                            }
                            error = serverSaid(it, failed)
                        }
                    if (current()) busy = false
                }
            }) {
                Text(if (original == null) appText("Подтвердить пассажира", "Юлсыны раҫлау")
                else appText("Повторить тот же запрос", "Шул уҡ һорауҙы ҡабатлау"))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(appText("Закрыть", "Ябыу")) } },
    )
}
