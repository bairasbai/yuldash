package com.yuldash.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.ContactDto
import com.yuldash.app.data.ContactsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun ContactDto.toTrustedContact() = TrustedContact(name, relation, phone, notifyByDefault, id)

/**
 * Состояние и логика экрана «Доверенные контакты» для входа [owner].
 *
 * Живёт ровно столько, сколько экран ([rememberScreenViewModel]): уход с экрана отменяет
 * viewModelScope, и поздний ответ просто некому показать. Список — правда [ContactsRepository],
 * общая для всех экранов; здесь только то, что принадлежит экрану: «занят», «ошибка», тосты.
 *
 * [isCurrent] — экран всё ещё тот, что открыт: навигация может смениться на кадр раньше, чем
 * копия экрана уйдёт из композиции. Это единственный «забор» экрана, и стоит он здесь, а не в кнопках.
 */
internal class ContactsViewModel(
    private val owner: Long,
    private val isCurrent: () -> Boolean = { true },
    private val repository: ContactsRepository = ContactsRepository.shared,
) : ViewModel() {

    /** [contacts] == null — у этого входа ещё нет принятого списка; экран рисует то, что знает родитель. */
    data class UiState(
        val contacts: List<TrustedContact>? = null,
        val busy: Boolean = true,
        val loadError: Boolean = false,
    ) {
        val actionsEnabled: Boolean get() = !busy && !loadError && contacts != null
    }

    sealed interface Event {
        data object Added : Event
        data class Failed(val error: Throwable, val deleting: Boolean) : Event
    }

    private val mutableUi = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = mutableUi.asStateFlow()

    private val mutableEvents = Channel<Event>(Channel.BUFFERED)
    /** Тосты: каждый — одному экрану и ровно один раз. */
    val events: Flow<Event> = mutableEvents.receiveAsFlow()

    private var session: Job? = null
    /** До какого [com.yuldash.app.data.ContactsState.changes] экран уже показал правду или честно пытался. */
    private var shownChanges = 0L

    /** Экран показан: свежий список с сервера, дальше — следить за изменениями. */
    fun start() {
        if (session != null || !active()) return
        session = viewModelScope.launch {
            // Main, not Main.immediate: changes that settle within one main-thread turn reach the
            // screen together, as they did when a frame picked them up — one read, not one per change.
            launch(Dispatchers.Main) {
                repository.state.collect { state ->
                    val list = state.contacts?.takeIf { state.owner == owner }
                    mutableUi.update { it.copy(contacts = list?.map(ContactDto::toTrustedContact)) }
                    catchUp()
                }
            }
            // A cancelled mutation of a closed copy of this screen can still be waiting for its reply.
            work { load(force = true) }
        }
    }

    fun retry() {
        if (mutableUi.value.busy || !active()) return
        mutableUi.update { it.copy(loadError = false) }
        work { load(force = false) }
    }

    /** false — сейчас нельзя (загрузка, ошибка, другой вход, экран уже ушёл): диалог остаётся открытым. */
    fun add(name: String, relation: String, phone: String): Boolean =
        mutate(deleting = false) { repository.add(owner, name, relation, phone, notifyByDefault = true) }

    fun delete(contact: TrustedContact): Boolean =
        contact.id > 0 && mutate(deleting = true) { repository.delete(owner, contact.id) }

    private fun mutate(deleting: Boolean, send: suspend () -> Result<Unit>): Boolean {
        // Checked synchronously: a confirmation tapped twice in one frame sends once.
        if (!mutableUi.value.actionsEnabled || !active()) return false
        work {
            val result = send()
            if (!active()) return@work // The screen has gone: no read and no toast for it.
            val error = result.exceptionOrNull()
            // Read only; never repeat the mutation after an unknown reply.
            if (error == null || repository.isUnknownOutcome(error)) load(force = false)
            else mutableEvents.trySend(Event.Failed(error, deleting))
            if (!deleting && error == null && !mutableUi.value.loadError) mutableEvents.trySend(Event.Added)
        }
        return true
    }

    /** Чужое изменение завершилось (закрытая копия экрана, другой вызов): один раз показать правду. */
    private fun catchUp() {
        if (!mutableUi.value.busy && active() && repository.state.value.changes > shownChanges) work { load(force = false) }
    }

    private fun active() = isCurrent() && ApiClient.isCurrentSession(owner)

    private suspend fun load(force: Boolean) {
        if (!active()) return
        val attempted = repository.state.value.changes
        val result = repository.read(owner, force)
        // A failed read waits for Retry, unless the server changed again meanwhile.
        shownChanges = maxOf(shownChanges, attempted)
        mutableUi.update { it.copy(loadError = result.isFailure) }
    }

    private fun work(block: suspend () -> Unit) {
        mutableUi.update { it.copy(busy = true) }
        viewModelScope.launch {
            try { block() } finally { mutableUi.update { it.copy(busy = false) } }
            catchUp()
        }
    }
}
