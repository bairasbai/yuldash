package com.yuldash.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yuldash.app.data.ApiException
import com.yuldash.app.data.DriverBookingDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Живёт у владельца запросов кабинета, а не у исчезающей при прокрутке строки. */
internal class DriverMeetingAnswerState(private val isCurrent: () -> Boolean = { true }) {
    private val pending = mutableStateMapOf<Int, Boolean>()
    private val failures = mutableStateMapOf<Int, String>()
    private val choices = mutableMapOf<Int, Boolean>()
    private val proposals = mutableMapOf<Int, String>()
    fun isBusy(id: Int): Boolean = pending[id] == true
    fun errorFor(id: Int, proposalId: String): String? = failures[id].takeIf { proposals[id] == proposalId }
    fun choiceFor(id: Int): Boolean = choices[id] ?: true

    fun answer(scope: CoroutineScope, id: Int, proposalId: String, accept: Boolean, fallback: String,
               request: suspend () -> Result<*>, onRefresh: () -> Unit) {
        if (isBusy(id) || !isCurrent()) return
        pending[id] = true; failures.remove(id)
        choices[id] = accept; proposals[id] = proposalId
        scope.launch {
            try {
                val result = request()
                if (!isCurrent()) return@launch
                result.onSuccess { onRefresh() }.onFailure { e ->
                    failures[id] = (e as? ApiException)?.message ?: fallback
                    if ((e as? ApiException)?.detailCode == "meeting_point_stale") onRefresh()
                }
            } finally { pending.remove(id) }
        }
    }
}

@Composable
internal fun DriverMeetingAnswerRow(b: DriverBookingDto, state: DriverMeetingAnswerState?,
                                    onAnswer: (Int, String, Int, Boolean) -> Unit) {
    val proposal = b.meetingProposal ?: return
    val busy = state?.isBusy(b.bookingId) == true
    Column(verticalArrangement = Arrangement.spacedBy(CanonSpace.xs)) {
        DriverMeetingProposal(b, busy = busy) { accept -> onAnswer(b.bookingId, proposal.proposalId, b.meetingVersion, accept) }
        if (busy) Text(appText("Отправляем…", "Ебәрәбеҙ…"), style = CanonCaption, color = CanonMuted)
        state?.errorFor(b.bookingId, proposal.proposalId)?.let { message ->
            Text(message, style = CanonCaption, color = CanonText)
            TextButton(enabled = !busy, modifier = Modifier.heightIn(min = 48.dp),
                onClick = { onAnswer(b.bookingId, proposal.proposalId, b.meetingVersion, state.choiceFor(b.bookingId)) }) {
                Text(appText("Повторить", "Ҡабатлау"), style = CanonCaption, color = CanonGreen2)
            }
        }
    }
}
