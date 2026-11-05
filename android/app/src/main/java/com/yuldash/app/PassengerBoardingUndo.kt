package com.yuldash.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.BoardingApi
import com.yuldash.app.data.BoardingResult
import com.yuldash.app.data.BoardingUndoTerms
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Снять посадку может только пассажир, отвечая на конкретный свежий запрос водителя. */
@Composable
internal fun PassengerBoardingUndo(bookingId: Int, terms: BoardingUndoTerms, onChanged: (BoardingResult) -> Unit) {
    PassengerBoardingUndoContent(bookingId, terms,
        respond = { id, request, confirm, owner -> BoardingApi.respondUndo(id, request, confirm, owner) }, onChanged = onChanged)
}

@Composable
internal fun PassengerBoardingUndoContent(bookingId: Int, terms: BoardingUndoTerms,
    respond: suspend (Int, String, Boolean, Long) -> Result<BoardingResult>, onChanged: (BoardingResult) -> Unit) {
    val owner = remember(bookingId) { ApiClient.queueSessionGeneration() }
    val scope = rememberCoroutineScope()
    val currentRequest by rememberUpdatedState(terms.requestId)
    var live by remember(bookingId) { mutableStateOf(true) }
    DisposableEffect(bookingId) { live = true; onDispose { live = false } }
    var left by remember(bookingId, terms) { mutableIntStateOf(terms.waitSec) }
    var busy by remember(bookingId, terms.requestId) { mutableStateOf(false) }
    var error by remember(bookingId, terms.requestId) { mutableStateOf<String?>(null) }
    val failed = appText("Не получилось ответить. Проверь сеть и повтори.", "Яуап биреп булманы. Селтәрҙе тикшер ҙә ҡабатла.")
    LaunchedEffect(bookingId, terms, left) {
        if (left > 0) { delay(1_000); left -= 1 }
    }
    AnimatedVisibility(visible = terms.pending && terms.requestId != null && left > 0) {
        Surface(color = CanonSurface, shape = CanonCardShape, modifier = Modifier.fillMaxWidth().testTag("passengerBoardingUndo")) {
            Column(Modifier.padding(CanonSpace.md), verticalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
                Text(appText("Водитель просит исправить посадку", "Йөрөтөүсе ултырыу билдәһен төҙәтергә һорай"), style = CanonBodyStrong, color = CanonText)
                Text(appText("Подтверди только если ты ещё не в машине.", "Машинаға әле ултырмаған булһаң ғына раҫла."), style = CanonBody, color = CanonMuted)
                fun answer(confirm: Boolean) {
                    val id = terms.requestId ?: return
                    if (!live || busy || left <= 0 || owner != ApiClient.queueSessionGeneration()) return
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            respond(bookingId, id, confirm, owner).onSuccess {
                                if (live && owner == ApiClient.queueSessionGeneration() && currentRequest == id) onChanged(it)
                            }.onFailure {
                                if (live && owner == ApiClient.queueSessionGeneration() && currentRequest == id) error = serverSaid(it, failed)
                            }
                        } finally { busy = false }
                    }
                }
                AppButton(text = appText("Я ещё не сел", "Мин әле ултырманым"), onClick = { answer(true) }, loading = busy)
                AppButton(text = appText("Я в машине", "Мин машиналамын"), onClick = { answer(false) }, style = AppButtonStyle.Secondary, enabled = !busy)
                error?.let { Text(it, style = CanonCaption, color = CanonMuted) }
            }
        }
    }
}
