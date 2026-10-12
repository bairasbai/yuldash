package com.yuldash.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Что приложение знает о доверенных контактах входа [owner] (поколение сессии [ApiClient]).
 *
 * [contacts] — последний принятый ответ сервера; null — для этого входа его ещё нет.
 * [changes] растёт, когда сервер мог изменить список: добавление или удаление завершилось,
 * либо ответ на него потерялся. Кто показывает список, по росту [changes] перечитывает его.
 */
internal data class ContactsState(
    val owner: Long = 0L,
    val contacts: List<ContactDto>? = null,
    val changes: Long = 0L,
)

/**
 * Единственный источник правды о доверенных контактах (decisions.md, «Слои Android-экрана»).
 *
 * Гонку «поздний ответ затирает свежий» закрывает одно правило в одном месте. Чтения и
 * события «список устарел» берут номера из одного счётчика [ticks]; ответ чтения принимается,
 * только если отправлен ПОСЛЕ последнего устаревания и ПОСЛЕ уже принятого ответа. Экраны
 * не держат своих ревизий и флагов: показывают [state] и зовут suspend-методы из своего
 * viewModelScope.
 */
internal class ContactsRepository(private val clock: () -> Long = System::currentTimeMillis) {
    private val lock = Any()
    private val mutableState = MutableStateFlow(ContactsState())
    /**
     * Published under the repository lock, so the order of states is the order of decisions.
     * Collectors must dispatch (Dispatchers.Main, Compose effects), never run inline here: the
     * session-reset listener takes this lock while holding ApiClient's session lock.
     */
    val state: StateFlow<ContactsState> = mutableState.asStateFlow()

    // Guarded by lock. One counter numbers both reads and invalidations.
    private var ticks = 0L
    private var outdatedAt = 0L  // tick of the last event after which older replies are untrustworthy
    private var acceptedAt = 0L  // tick of the read whose list is in state
    private var acceptedTime = 0L

    init {
        // Logout or another account: names and phones leave memory together with the response cache.
        ApiClient.onSessionReset {
            synchronized(lock) {
                outdatedAt = ++ticks
                mutableState.value = ContactsState(changes = mutableState.value.changes)
            }
        }
    }

    /**
     * Список входа [owner]: из памяти, пока он свежий и ничем не устарел, иначе с сервера.
     * [force] — не доверять ничему, что было до этого вызова. Возвращает ответ ЭТОГО чтения,
     * даже если в [state] он не попал: ответ, отправленный позже, уже новее.
     */
    suspend fun read(owner: Long, force: Boolean = false): Result<List<ContactDto>> {
        val caller = currentCoroutineContext()
        val sent = synchronized(lock) {
            caller.ensureActive() // A cancelled forced read must not discard a valid list.
            if (!ApiClient.isCurrentSession(owner)) return ApiClient.staleSession()
            val list = mutableState.value.contacts
            if (!force && list != null && upToDate(owner) && clock() - acceptedTime < TTL_MS) return Result.success(list)
            if (force) outdatedAt = ++ticks
            ++ticks
        }
        val result = ContactsApi.list(owner)
        synchronized(lock) {
            caller.ensureActive() // A caller that has left changes nothing shared.
            if (!ApiClient.isCurrentSession(owner)) return ApiClient.staleSession()
            val list = result.getOrNull()
            if (list != null && sent > outdatedAt && sent > acceptedAt) {
                acceptedAt = sent
                acceptedTime = clock()
                mutableState.value = ContactsState(owner, list, mutableState.value.changes)
            }
        }
        return result
    }

    /** POST — upsert по телефону. Никогда не повторяется сам: после потерянного ответа — только чтение. */
    suspend fun add(owner: Long, name: String, relation: String, phone: String, notifyByDefault: Boolean): Result<Unit> =
        mutate(owner, deletedId = null) { ContactsApi.add(owner, name, relation, phone, notifyByDefault) }

    suspend fun delete(owner: Long, contactId: Int): Result<Unit> =
        mutate(owner, deletedId = contactId) { ContactsApi.delete(owner, contactId) }

    /** Ответ на изменение потерян или битый: сервер мог успеть, список надо перечитать. */
    fun isUnknownOutcome(error: Throwable?): Boolean = ContactsApi.isUncertain(error)

    private suspend fun mutate(owner: Long, deletedId: Int?, send: suspend () -> Result<Unit>): Result<Unit> {
        val result = try {
            send()
        } catch (cancelled: CancellationException) {
            // The request may already be committed: the caller left, but the list may be outdated.
            serverMayHaveChanged(owner, deletedId)
            throw cancelled
        }
        if (result.isSuccess || isUnknownOutcome(result.exceptionOrNull())) serverMayHaveChanged(owner, deletedId)
        return if (result.isSuccess && !sameSession(owner)) ApiClient.staleSession() else result
    }

    private fun serverMayHaveChanged(owner: Long, deletedId: Int?) = synchronized(lock) {
        if (!sameSession(owner)) return@synchronized
        val known = mutableState.value
        // One DELETE whose id an up-to-date list already lacks changes nothing: the list stays valid.
        val reflected = deletedId != null && upToDate(owner) && known.contacts?.none { it.id == deletedId } == true
        outdatedAt = ++ticks // Either way, replies sent before the change settled are not trusted.
        if (reflected) {
            acceptedAt = ++ticks
            return@synchronized
        }
        mutableState.value = known.copy(changes = known.changes + 1)
    }

    /** Guarded by lock: the list in state belongs to [owner] and nothing has outdated it since. */
    private fun upToDate(owner: Long) = mutableState.value.owner == owner && acceptedAt > outdatedAt

    private fun sameSession(owner: Long) = ApiClient.queueSessionGeneration() == owner

    companion object {
        /** Как личные ответы в кеше ApiClient: 90 секунд. */
        private const val TTL_MS = 90_000L

        val shared: ContactsRepository by lazy { ContactsRepository() }
    }
}
