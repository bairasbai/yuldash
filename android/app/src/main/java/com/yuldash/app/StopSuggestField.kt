package com.yuldash.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.SettlementDto
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Остановка — только из общего справочника, без адресного геокодера и платных запросов. */
@Composable
internal fun StopSuggestField(value: String, onValueChange: (String) -> Unit, endpoints: List<String>) {
    val language = LocalAppLanguage.current
    val errors = rideStopErrorMessages()
    val failureText = appText("Не получилось загрузить подсказки. Повтори.", "Тәҡдимдәрҙе йөкләп булманы. Ҡабатла.")
    var editing by remember { mutableStateOf(false) }
    var items by remember { mutableStateOf<List<SettlementDto>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(value, editing, retry) {
        items = emptyList(); failure = null; loading = false
        if (!editing || value.isBlank() || !rideStopTextAllowed(value)) return@LaunchedEffect
        loading = true
        delay(250)
        val result = ApiClient.searchSettlements(rideStopName(value), 10)
        currentCoroutineContext().ensureActive()
        result
            .onSuccess { items = it.filter { s -> rideStopTextAllowed(rideStopPickText(language, s)) } }
            .onFailure { failure = serverSaid(it, failureText) }
        loading = false
    }
    val shown = items.filterNot { rideStopIsEndpoint(it, endpoints) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CanonSpace.xs)) {
        OutlinedTextField(
            value = value,
            onValueChange = { editing = true; onValueChange(it) },
            label = { Text(appText("Остановка по пути", "Юл ыңғайындағы туҡталыш")) },
            placeholder = { Text(appText("Например, Темясово", "Мәҫәлән, Темәс")) },
            singleLine = true,
            shape = CanonFieldShape,
            modifier = Modifier.fillMaxWidth(),
            isError = editing && value.isNotBlank() && !rideStopTextAllowed(value),
        )
        AnimatedVisibility(editing && value.isNotBlank(),
            enter = fadeIn(tween(CanonMotion.QUICK)), exit = fadeOut(tween(CanonMotion.QUICK))) {
            Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
                Column(Modifier.fillMaxWidth().padding(CanonSpace.sm), verticalArrangement = Arrangement.spacedBy(CanonSpace.xs)) {
                    when {
                        !rideStopTextAllowed(value) -> Text(errors.getValue(RideStopProblem.Invalid), style = CanonCaption, color = CanonRed)
                        loading -> {
                            LinearProgressIndicator(Modifier.fillMaxWidth(), color = CanonGreen2)
                            Text(appText("Ищем остановки…", "Туҡталыштарҙы эҙләйбеҙ…"), style = CanonCaption, color = CanonMuted)
                        }
                        failure != null -> {
                            Text(failure.orEmpty(), style = CanonCaption, color = CanonRed)
                            TextButton(onClick = { retry++ }, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(appText("Повторить", "Ҡабатлау"), style = CanonBodyStrong)
                            }
                        }
                        shown.isEmpty() -> Text(appText("Не нашли. Попробуй другое название.", "Табылманы. Башҡа атама яҙып ҡара."), style = CanonCaption, color = CanonMuted)
                        else -> {
                            shown.forEach { town ->
                                Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) {
                                    editing = false
                                    onValueChange(rideStopPickText(language, town))
                                }.padding(CanonSpace.sm)) {
                                    Text(rideStopPickText(language, town), style = CanonBodyStrong, color = CanonText)
                                    if (town.region.isNotBlank()) Text(town.region, style = CanonCaption, color = CanonMuted)
                                }
                            }
                            if (needsOsmCredit(shown)) OsmCreditRow()
                        }
                    }
                }
            }
        }
    }
}
