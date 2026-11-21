package com.yuldash.app

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.BookingCommandApi
import com.yuldash.app.data.CardCheckoutAction
import com.yuldash.app.data.CardCheckoutOffer
import com.yuldash.app.data.CardConfirmationOutcome
import com.yuldash.app.data.RideshareBankStatus
import com.yuldash.app.data.RideshareCardCheckoutApi
import com.yuldash.app.data.SavedCardConfirmation
import com.yuldash.app.data.TripReceiptDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal enum class CheckoutFailure { Read, Storage, Confirm, Link }

internal data class CardCheckoutView(
    val checked: Boolean = false,
    val offer: CardCheckoutOffer? = null,
    val selected: CardCheckoutOffer? = null,
    val pending: SavedCardConfirmation? = null,
    val outcome: CardConfirmationOutcome? = null,
    val failure: CheckoutFailure? = null,
    val busy: Boolean = false,
)

internal data class CardCheckoutControls(
    val view: CardCheckoutView,
    val offer: CardCheckoutOffer?,
    val canOpenBank: Boolean,
    val holdsDirect: Boolean,
    val refresh: () -> Unit,
    val choose: () -> Unit,
    val dismiss: () -> Unit,
    val confirm: () -> Unit,
    val repeat: () -> Unit,
    val close: () -> Unit,
    val openBank: () -> Unit,
) {
    val canRepeat get() = view.outcome == CardConfirmationOutcome.Unseen && view.pending?.canRepeat == true
    val showsError get() = view.failure != null && (view.failure != CheckoutFailure.Read || view.pending != null)
    val visible get() = view.selected != null || view.pending != null || offer != null || canOpenBank || showsError
}

private fun CardCheckoutView.eligibleOffer(bank: CardPaymentRead): CardCheckoutOffer? {
    if (!checked || pending != null || failure == CheckoutFailure.Storage || bank.loading || bank.error) return null
    val status = bank.payment?.payment
    val value = offer ?: return null
    return when (value.action) {
        CardCheckoutAction.Retry -> value.takeIf {
            status == RideshareBankStatus.Canceled && bank.payment?.paymentAttemptId == value.retryAfterPaymentId
        }
        CardCheckoutAction.Resume -> value.takeIf { status == RideshareBankStatus.Queued }
        CardCheckoutAction.Start -> value.takeIf {
            status in listOf(RideshareBankStatus.Unavailable, RideshareBankStatus.NotStarted, RideshareBankStatus.Canceled)
        }
    }
}

@Composable
internal fun rememberRideshareCardCheckout(
    receipt: TripReceiptDto?,
    generation: Long,
    bank: CardPaymentRead,
    canAct: () -> Boolean,
): CardCheckoutControls {
    val passenger = receipt?.takeIf { it.role == "passenger" }
    val bookingId = passenger?.bookingId
    val active by rememberUpdatedState(canAct)
    val currentReceipt by rememberUpdatedState(passenger)
    val currentBank by rememberUpdatedState(bank)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val phase by lifecycle.currentStateFlow.collectAsState()
    val resumed = phase.isAtLeast(Lifecycle.State.RESUMED)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember(bookingId, generation) { mutableStateOf(CardCheckoutView()) }
    fun current() = bookingId != null && active() && currentReceipt?.bookingId == bookingId &&
        ApiClient.isCurrentSession(generation) && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    fun update(change: (CardCheckoutView) -> CardCheckoutView) {
        if (current()) state.value = change(state.value)
    }

    suspend fun resolve(
        owner: BookingCommandApi.CommandOwner,
        saved: SavedCardConfirmation,
        close: Boolean = false,
    ): SavedCardConfirmation? {
        val outcome = RideshareCardCheckoutApi.outcome(owner, saved, close).getOrThrow()
        update { it.copy(outcome = outcome) }
        if (outcome == CardConfirmationOutcome.Unseen || !current()) return saved
        val observed = ApiClient.rideshareCardPayment(saved.bookingId, generation).getOrThrow()
        if (!current()) return saved
        if (outcome == CardConfirmationOutcome.Applied) {
            val prior = saved.retryAfterPaymentId
            if (observed.payment == RideshareBankStatus.Unavailable || observed.payment == RideshareBankStatus.NotStarted ||
                (observed.payment == RideshareBankStatus.Canceled && prior != null && observed.paymentAttemptId == prior)) return saved
        }
        return if (RideshareCardCheckoutApi.release(owner, saved).getOrThrow()) null
        else RideshareCardCheckoutApi.read(owner, saved.bookingId).getOrThrow()
    }

    suspend fun load() {
        val id = bookingId ?: return
        val owner: BookingCommandApi.CommandOwner?
        val saved: SavedCardConfirmation?
        try {
            owner = if (RideshareCardCheckoutApi.mayHaveSavedConfirmation(generation)) {
                BookingCommandApi.commandOwner(generation).getOrThrow()
            } else null
            saved = owner?.let { RideshareCardCheckoutApi.read(it, id).getOrThrow() }
        } catch (stop: CancellationException) {
            throw stop
        } catch (_: Exception) {
            update { it.copy(failure = CheckoutFailure.Storage, offer = null) }
            return
        }
        update { it.copy(checked = true, pending = saved, offer = null, outcome = null, failure = null) }
        if (!current()) return
        val pending = if (saved != null && owner != null) resolve(owner, saved) else null
        val status = currentBank.payment?.payment
        val canReadTerms = pending == null && currentReceipt?.paid == false && status != null &&
            !currentBank.loading && !currentBank.error && status !in setOf(
                RideshareBankStatus.WaitingBank, RideshareBankStatus.Reconciling, RideshareBankStatus.Succeeded,
            )
        val offer = if (canReadTerms && current()) RideshareCardCheckoutApi.terms(id, generation).getOrThrow() else null
        update { it.copy(pending = pending, offer = offer) }
    }

    fun launch(failure: CheckoutFailure, block: suspend (Int) -> Unit) {
        val id = bookingId ?: return
        if (state.value.busy || !current()) return
        update { it.copy(busy = true, failure = null) }
        scope.launch {
            try {
                if (current()) block(id)
            } catch (stop: CancellationException) {
                throw stop
            } catch (_: Exception) {
                update { it.copy(failure = failure) }
            } finally {
                state.value = state.value.copy(busy = false)
            }
        }
    }

    LaunchedEffect(bookingId, generation, resumed, bank.payment, bank.loading, bank.error) {
        if (resumed && !bank.loading) launch(CheckoutFailure.Read) { load() }
    }

    suspend fun submit(owner: BookingCommandApi.CommandOwner, command: SavedCardConfirmation) {
        RideshareCardCheckoutApi.submit(owner, command)
        if (current()) {
            currentBank.refresh()
            load()
        }
    }

    val view = state.value
    val offer = view.eligibleOffer(bank)
    val payment = bank.payment?.takeIf { it.payment == RideshareBankStatus.WaitingBank && !bank.loading && !bank.error }?.paymentAttemptId
    return CardCheckoutControls(
        view, offer, bookingId != null && payment != null,
        bookingId != null && (!view.checked || view.busy || view.selected != null || view.pending != null || view.failure == CheckoutFailure.Storage),
        refresh = {
            bank.refresh()
            launch(CheckoutFailure.Read) { load() }
        },
        choose = { if (offer != null) update { it.copy(selected = offer) } },
        dismiss = { update { it.copy(selected = null) } },
        confirm = {
            val selected = state.value.selected
            if (selected != null) launch(CheckoutFailure.Confirm) { id ->
                update { it.copy(selected = null, offer = null) }
                val fresh = RideshareCardCheckoutApi.terms(id, generation).getOrThrow()
                if (current()) {
                    if (fresh != selected) {
                        update { it.copy(offer = fresh, failure = CheckoutFailure.Confirm) }
                        currentBank.refresh()
                    } else {
                        val owner = BookingCommandApi.commandOwner(generation).getOrThrow()
                        if (current()) {
                            val saved = RideshareCardCheckoutApi.reserve(owner, id, selected).getOrThrow()
                            if (saved == null) load()
                            else if (current()) {
                                update { it.copy(pending = saved) }
                                submit(owner, saved)
                            }
                        }
                    }
                }
            }
        },
        repeat = {
            val pending = state.value.pending
            if (pending?.canRepeat == true && state.value.outcome == CardConfirmationOutcome.Unseen) {
                launch(CheckoutFailure.Confirm) { id ->
                    val owner = BookingCommandApi.commandOwner(generation).getOrThrow()
                    if (RideshareCardCheckoutApi.read(owner, id).getOrThrow()?.key == pending.key && current()) {
                        val result = RideshareCardCheckoutApi.outcome(owner, pending).getOrThrow()
                        if (result == CardConfirmationOutcome.Unseen && current()) submit(owner, pending)
                        else if (current()) load()
                    }
                }
            }
        },
        close = {
            val pending = state.value.pending
            if (pending != null) launch(CheckoutFailure.Read) { id ->
                val owner = BookingCommandApi.commandOwner(generation).getOrThrow()
                if (RideshareCardCheckoutApi.read(owner, id).getOrThrow()?.key == pending.key && current()) {
                    resolve(owner, pending, close = true)
                    if (current()) {
                        currentBank.refresh()
                        load()
                    }
                }
            }
        },
        openBank = {
            if (payment != null) launch(CheckoutFailure.Link) { id ->
                val url = RideshareCardCheckoutApi.link(id, payment, generation).getOrThrow()
                val latest = currentBank.payment
                if (current() && latest?.paymentAttemptId == payment && latest.payment == RideshareBankStatus.WaitingBank) {
                    checkNotNull(url)
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
            }
        },
    )
}

@Composable
internal fun RideshareCardCheckoutActions(control: CardCheckoutControls, insideSummary: Boolean) {
    if (!control.visible) return
    if (insideSummary) CardCheckoutButtons(control, true)
    else AppCard {
        Column(Modifier.padding(CanonSpace.md), verticalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
            CardCheckoutButtons(control, false)
        }
    }
}

@Composable
private fun CardCheckoutButtons(control: CardCheckoutControls, insideSummary: Boolean) {
    val view = control.view
    val selected = view.selected
    if (selected != null) {
        Text(appText("Сумма к оплате", "Түләү суммаһы") + " · " + formatTaxiKop(selected.amountKop),
            style = CanonBodyStrong, color = CanonText)
        AppButton(appText("Подтвердить оплату", "Түләүҙе раҫларға"), control.confirm, enabled = !view.busy)
        TextButton(onClick = control.dismiss, enabled = !view.busy) { Text(appText("Отмена", "Баш тартыу")) }
    } else if (control.offer != null) {
        val label = when (control.offer.action) {
            CardCheckoutAction.Start -> appText("Оплатить картой", "Карта менән түләргә")
            CardCheckoutAction.Retry -> appText("Повторить оплату картой", "Карта менән түләүҙе ҡабатларға")
            CardCheckoutAction.Resume -> appText("Продолжить оплату", "Түләүҙе дауам итергә")
        }
        AppButton(label, control.choose, enabled = !view.busy)
    }
    if (view.pending != null) {
        val status = if (view.outcome == CardConfirmationOutcome.Applied)
            appText("Проверяем состояние принятой оплаты.", "Ҡабул ителгән түләүҙең торошон тикшерәбеҙ.")
        else appText("Проверяем, принято ли подтверждение.", "Раҫлауҙың ҡабул ителеүен тикшерәбеҙ.")
        Text(status, style = CanonBody, color = CanonText)
        if (control.canRepeat) TextButton(onClick = control.repeat, enabled = !view.busy) {
            Text(appText("Повторить отправку", "Ебәреүҙе ҡабатларға"))
        }
        if (view.outcome != CardConfirmationOutcome.Applied && view.outcome != CardConfirmationOutcome.Closed) {
            TextButton(onClick = control.close, enabled = !view.busy) {
                Text(appText("Отменить подтверждение", "Раҫлауҙы кире алырға"))
            }
        }
    }
    if (control.canOpenBank) {
        AppButton(appText("Перейти в банк", "Банкка күсергә"), control.openBank, enabled = !view.busy)
    }
    if (control.showsError) {
        val error = when (view.failure) {
            CheckoutFailure.Storage -> appText("Не удалось проверить сохранённую оплату.", "Һаҡланған түләүҙе тикшереп булманы.")
            CheckoutFailure.Link -> appText("Ссылка банка недоступна. Обнови статус.", "Банк һылтанмаһы асыҡ түгел. Торошто яңырт.")
            CheckoutFailure.Confirm -> appText("Условия изменились или подтверждение не отправилось. Обнови статус.",
                "Шарттар үҙгәрҙе йәки раҫлау ебәрелмәне. Торошто яңырт.")
            CheckoutFailure.Read, null -> appText("Не получилось проверить оплату. Повтори проверку.",
                "Түләүҙе тикшереп булманы. Тикшереүҙе ҡабатла.")
        }
        Text(error, style = CanonBody, color = CanonText)
    }
    if (!insideSummary && (view.pending != null || control.showsError)) {
        TextButton(onClick = control.refresh, enabled = !view.busy) {
            Text(appText("Проверить оплату", "Түләүҙе тикшерергә"))
        }
    }
}
