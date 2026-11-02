package com.yuldash.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yuldash.app.data.MeetingPointInput

/** Шторка выбора точки: ориентиры села (курируемые), «На карте» (пин в центре), пояснение. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MeetingPointSheet(
    city: String,
    title: String,
    initial: MeetingPointInput?,
    onDone: (MeetingPointInput) -> Unit,
    onDismiss: () -> Unit,
    busy: Boolean = false,
    error: String? = null,
) {
    var lat by rememberSaveable(city) { mutableStateOf(initial?.lat) }
    var lng by rememberSaveable(city) { mutableStateOf(initial?.lng) }
    var landmarkId by rememberSaveable(city) { mutableStateOf(initial?.landmarkId) }
    var landmarkTitle by rememberSaveable(city) { mutableStateOf(initial?.landmarkTitle ?: "") }
    var note by rememberSaveable(city) { mutableStateOf(initial?.note ?: "") }
    var showMap by rememberSaveable(city) { mutableStateOf(false) }
    val isBa = LocalAppLanguage.current == AppLanguage.Ba

    if (showMap) {
        PickupPickerOverlay(
            initial = lat?.let { la -> lng?.let { lg -> com.yandex.mapkit.geometry.Point(la, lg) } },
            onConfirm = { la, lg -> lat = la; lng = lg; landmarkId = null; landmarkTitle = ""; showMap = false },
            onDismiss = { showMap = false },
            hint = title,
        )
        return
    }
    AppModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(CanonSpace.lg),
            verticalArrangement = Arrangement.spacedBy(CanonSpace.md),
        ) {
            Text(title, style = CanonTitle, color = CanonText)
            error?.let { Text(it, style = CanonCaption, color = CanonText) }
            Text(
                appText("Выбери ориентир или поставь точку на карте", "Билгеле урын һайла йәки картаға нөктә ҡуй"),
                style = CanonCaption, color = CanonMuted,
            )
            PickupSuggestionChips(city = city, selectedId = landmarkId, enabled = !busy) { p ->
                if (!busy) {
                    landmarkId = p.id; landmarkTitle = if (isBa) p.titleBa.ifBlank { p.titleRu } else p.titleRu
                    lat = null; lng = null   // координаты возьмутся у ориентира на сервере
                }
            }
            AppButton(
                text = appText("На карте", "Картала"), onClick = { showMap = true }, enabled = !busy,
                style = AppButtonStyle.Secondary, icon = Icons.Default.Map,
            )
            OutlinedTextField(
                value = note, onValueChange = { note = it.take(120) },
                label = { Text(appText("Пояснение: ворота, подъезд, что рядом", "Аңлатма: ҡапҡа, инеү урыны, янында нимә")) },
                modifier = Modifier.fillMaxWidth(), shape = CanonFieldShape, maxLines = 2, enabled = !busy,
            )
            AnimatedVisibility(visible = lat != null || landmarkId != null || note.isNotBlank()) {
                TextButton(
                    onClick = { onDone(MeetingPointInput(clear = true)) }, enabled = !busy,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(appText("Убрать место", "Урынды алып ташлау"), color = CanonMuted) }
            }
            AppButton(
                text = appText("Готово", "Әҙер"), enabled = !busy, loading = busy,
                onClick = {
                    onDone(MeetingPointInput(lat = lat, lng = lng, note = note.trim(),
                                             landmarkId = landmarkId, landmarkTitle = landmarkTitle))
                },
            )
        }
    }
}

