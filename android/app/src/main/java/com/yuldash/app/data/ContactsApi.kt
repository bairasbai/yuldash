package com.yuldash.app.data

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException

/** Доверенный контакт — кому уходят статус поездки и SOS. */
data class ContactDto(
    val id: Int,
    val name: String,
    val relation: String,
    val phone: String,
    val notifyByDefault: Boolean,
)

/** Разбор строки контакта; строгую проверку формы делает [ContactsApi.list]. */
internal fun parseContactDto(o: JSONObject): ContactDto {
    return ContactDto(
        id = o.optInt("id"),
        name = o.optString("name"),
        relation = o.optString("relation"),
        phone = o.optString("phone"),
        notifyByDefault = o.optBoolean("notify_by_default"),
    )
}

/**
 * HTTP домена «доверенные контакты»: запрос и разбор ответа, больше ничего. Кеш, порядок
 * ответов и состояние — в [ContactsRepository]; транспорт, сессия и обновление токена —
 * общие, в [ApiClient]. Первый домен, вырезанный из ApiClient (decisions.md, «Слои Android-экрана»).
 */
internal object ContactsApi {

    suspend fun list(owner: Long): Result<List<ContactDto>> =
        ApiClient.authorized("GET", "/trusted-contacts", null, owner).mapCatching { obj ->
            // Malformed success must remain a failure and never become accepted state.
            val arr = obj.getJSONArray("items")
            val ids = HashSet<Int>()
            (0 until arr.length()).map { i ->
                val row = arr.getJSONObject(i)
                val id = row.get("id")
                require(id is Number)
                val integral = id.toLong()
                require(integral in 1..Int.MAX_VALUE.toLong() && id.toDouble() == integral.toDouble())
                require(ids.add(integral.toInt()))
                require(row.get("name") is String)
                for (field in listOf("relation", "phone")) require(!row.has(field) || row.get(field) is String)
                require(!row.has("notify_by_default") || row.get("notify_by_default") is Boolean)
                parseContactDto(row)
            }
        }

    /** POST — upsert по телефону: тот же номер меняет поля, сохраняя id. */
    suspend fun add(owner: Long, name: String, relation: String, phone: String, notifyByDefault: Boolean): Result<Unit> =
        ApiClient.authorized(
            "POST", "/trusted-contacts",
            JSONObject().put("name", name).put("relation", relation).put("phone", phone).put("notify_by_default", notifyByDefault),
            owner, oneShot = true,
        ).map { }

    /**
     * Убрать доверенный контакт. Сервер умел это с самого начала, а приложение не звало:
     * контакт удалялся ТОЛЬКО на экране и возвращался после перезапуска. То есть человек,
     * которому уходит твоя геопозиция в поездке и SOS-сигнал ночью, оставался в списке
     * навсегда — хотя отношения меняются: бывший, поссорились, ошиблись номером.
     * Сервер сначала гасит шаринги этого контакта, потом сам контакт.
     */
    suspend fun delete(owner: Long, contactId: Int): Result<Unit> =
        ApiClient.authorized("DELETE", "/trusted-contacts/$contactId", null, owner, oneShot = true).map { }

    /** Ответ потерян или битый, а запрос мог дойти: сервер, возможно, уже всё изменил. */
    fun isUncertain(error: Throwable?): Boolean =
        error is JSONException || (error is IOException && error !is ConnectException &&
            error !is UnknownHostException && error !is NoRouteToHostException)
}
