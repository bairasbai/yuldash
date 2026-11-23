package com.yuldash.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.InstantOrderDto
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.UUID

internal typealias TaxiRatingRead = suspend (orderId: Int, generation: Long) -> Result<InstantOrderDto>
internal typealias TaxiRatingWrite = suspend (orderId: Int, stars: Int, tags: String, generation: Long) -> Result<Unit>

internal enum class TaxiRatingAvailability { Checking, Retry, Unavailable, Available, Saved }

internal class TaxiCompletedRating(
    initialStars: Int = 0,
    initialTags: List<String> = emptyList(),
    initialPendingStars: Int = 0,
) {
    var availability by mutableStateOf(TaxiRatingAvailability.Checking)
        private set
    var stars by mutableIntStateOf(initialStars)
        private set
    var tags by mutableStateOf(initialTags)
        private set
    var sending by mutableStateOf(false)
        private set
    var pendingStars by mutableIntStateOf(initialPendingStars)
        private set
    val unconfirmed: Boolean get() = pendingStars != 0
    var attempt by mutableIntStateOf(0)
        private set

    fun select(value: Int) {
        if (availability != TaxiRatingAvailability.Available || sending || value !in 1..5) return
        stars = value
        tags = emptyList()
    }

    fun toggle(tag: String) {
        if (availability != TaxiRatingAvailability.Available || sending || stars == 0) return
        tags = if (tag in tags) tags - tag else tags + tag
    }

    fun retry() {
        if (availability != TaxiRatingAvailability.Retry) return
        availability = TaxiRatingAvailability.Checking
        attempt++
    }

    fun refresh(detail: InstantOrderDto?, orderId: Int, role: String) {
        if (detail == null || detail.id != orderId || detail.role != role ||
            detail.status != "done" || detail.myStars !in 0..5
        ) {
            availability = TaxiRatingAvailability.Retry
            return
        }
        availability = when {
            detail.myStars > 0 -> {
                if (detail.myStars == pendingStars) pendingStars = 0
                stars = detail.myStars
                TaxiRatingAvailability.Saved
            }
            unconfirmed -> TaxiRatingAvailability.Retry
            detail.canRate -> TaxiRatingAvailability.Available
            else -> TaxiRatingAvailability.Unavailable
        }
    }

    fun beginSend(): Pair<Int, String>? {
        if (availability != TaxiRatingAvailability.Available || sending || stars == 0) return null
        sending = true
        pendingStars = stars
        return stars to tags.joinToString(",")
    }

    fun finishSend(saved: Boolean) {
        sending = false
        if (saved) {
            pendingStars = 0
            availability = TaxiRatingAvailability.Saved
        } else {
            // Без ответа POST не повторяем запись: сначала читаем состояние заказа.
            availability = TaxiRatingAvailability.Checking
            attempt++
        }
    }
}

private data class RatingOwner(val generation: Long, val state: TaxiCompletedRating)

// Android can restore saved instance state after a process death, when the in-memory
// session generation counter may repeat. Never rebind a draft to another process.
private val ratingProcess = UUID.randomUUID().toString()

private fun ratingOwnerSaver(orderId: Int, role: String) = listSaver<RatingOwner, Any>(
    save = { binding ->
        val (generation, state) = binding
        val sameSession = ApiClient.queueSessionGeneration() == generation
        val saveDraft = sameSession && state.availability != TaxiRatingAvailability.Saved
        listOf(
            ratingProcess,
            orderId,
            role,
            generation,
            if (saveDraft) state.stars else 0,
            if (sameSession) state.pendingStars else 0,
        ) + if (saveDraft) state.tags else emptyList()
    },
    restore = { values ->
        if (values[0] != ratingProcess || values[1] != orderId || values[2] != role) null
        else {
            RatingOwner(
                generation = values[3] as Long,
                state = TaxiCompletedRating(
                    initialStars = values[4] as Int,
                    initialTags = values.drop(6).map { it as String },
                    initialPendingStars = values[5] as Int,
                ),
            )
        }
    },
)

@Composable
internal fun rememberTaxiCompletedRating(
    orderId: Int,
    role: String,
    read: TaxiRatingRead,
    write: TaxiRatingWrite,
): Pair<TaxiCompletedRating?, suspend () -> Unit> {
    val liveGeneration by ApiClient.sessionChanges.collectAsState()
    // Этот экран заказа не становится владельцем новой сессии при смене аккаунта.
    val saver = remember(orderId, role) { ratingOwnerSaver(orderId, role) }
    val (owner, state) = rememberSaveable(orderId, role, saver = saver) {
        RatingOwner(ApiClient.queueSessionGeneration(), TaxiCompletedRating())
    }
    val attempt = state.attempt
    LaunchedEffect(orderId, role, owner, liveGeneration, attempt) {
        if (liveGeneration != owner || ApiClient.queueSessionGeneration() != owner) return@LaunchedEffect
        val detail = read(orderId, owner).getOrNull()
        currentCoroutineContext().ensureActive()
        ApiClient.runIfUnchangedSession(owner) { state.refresh(detail, orderId, role) }
    }
    val submit: suspend () -> Unit = {
        val draft = if (ApiClient.queueSessionGeneration() == owner) state.beginSend() else null
        if (draft != null) {
            val saved = write(orderId, draft.first, draft.second, owner).isSuccess
            currentCoroutineContext().ensureActive()
            ApiClient.runIfUnchangedSession(owner) { state.finishSend(saved) }
        }
    }
    return (if (liveGeneration == owner) state else null) to submit
}

@Composable
internal fun TaxiRatingNotice(
    availability: TaxiRatingAvailability,
    unconfirmed: Boolean,
    retry: () -> Unit,
) {
    val message = when (availability) {
        TaxiRatingAvailability.Checking -> appText("Проверяем оценку…", "Баһаны тикшерәбеҙ…")
        TaxiRatingAvailability.Retry -> if (unconfirmed) {
            appText("Отправка не подтверждена. Проверь оценку ещё раз.", "Ебәреү раҫланманы. Баһаны тағы тикшер.")
        } else {
            appText("Не получилось проверить оценку.", "Баһаны тикшереп булманы.")
        }
        TaxiRatingAvailability.Unavailable ->
            appText("Оценка этой поездки сейчас недоступна.", "Был сәфәргә хәҙер баһа ҡуйып булмай.")
        else -> return
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, style = CanonCaption, color = CanonMuted, textAlign = TextAlign.Center)
        if (availability == TaxiRatingAvailability.Retry) {
            TextButton(onClick = retry, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(appText("Повторить", "Ҡабатлау"), style = CanonBodyStrong, color = CanonGreen2)
            }
        }
    }
}
