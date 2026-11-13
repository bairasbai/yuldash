package com.yuldash.app.data

import org.json.JSONObject

internal object RequestTravelerApi {
    const val CONFIRMATION_REQUIRED = "request_traveler_confirmation_required"

    suspend fun checkSupport(owner: Long): Result<Unit> {
        val capability = ApiClient.authorized("GET", "/requests/capabilities", null, owner)
        val error = capability.exceptionOrNull()
        if (error != null && (error !is ApiException || error.status !in setOf(404, 405))) return Result.failure(error)
        if (capability.getOrNull()?.opt("for_another") != true) {
            return Result.failure(ApiException(409, "", "request_traveler_unsupported"))
        }
        return Result.success(Unit)
    }

    suspend fun offer(id: Int, owner: Long): Result<JSONObject> =
        ApiClient.authorized("GET", "/responses/$id/traveler-confirmation", null, owner)

    suspend fun confirm(id: Int, body: JSONObject, key: String, owner: Long): Result<Int> =
        ApiClient.authorized("POST", "/responses/$id/traveler-confirmation", body, owner,
            oneShot = true, idempotencyKey = key).mapCatching {
            it.getInt("booking_id").also { id -> require(id > 0) }
        }

    suspend fun consent(id: Int, version: String, owner: Long): Result<Unit> =
        ApiClient.authorized("POST", "/responses/$id/traveler-driver-consent", JSONObject().put("version", version),
            owner, oneShot = true).mapCatching { require(it.optString("state") == "waiting_for_owner") }
}
