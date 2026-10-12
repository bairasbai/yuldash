package com.yuldash.app.data

/**
 * Доверенные контакты входа, текущего В МОМЕНТ ВЫЗОВА — те же умолчания, что были у
 * ApiClient.getContacts/addContact/deleteContact до пилота слоёв (decisions.md, «Слои
 * Android-экрана»). Сценарии и проверки старых тестов не менялись: поменялся только адрес API.
 */
internal object TestContacts {
    private val repository get() = ContactsRepository.shared
    private val now get() = ApiClient.queueSessionGeneration()

    suspend fun read(owner: Long? = null, force: Boolean = false) = repository.read(owner ?: now, force)

    suspend fun add(name: String, relation: String, phone: String, notifyByDefault: Boolean, owner: Long? = null) =
        repository.add(owner ?: now, name, relation, phone, notifyByDefault)

    suspend fun delete(contactId: Int, owner: Long? = null) = repository.delete(owner ?: now, contactId)

    /** Сколько раз сервер мог изменить список — сигнал, по которому экраны перечитывают его. */
    val changes: Long get() = repository.state.value.changes
}
