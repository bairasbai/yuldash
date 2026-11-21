package com.yuldash.app.data

import android.content.SharedPreferences
import org.json.JSONObject

internal data class DriverBankRecipientSetupOwner(
    val userId: Int,
    val generation: Long,
    val apiBase: String,
    val sessionId: String,
) {
    fun isCurrent(): Boolean =
        generation == ApiClient.queueSessionGeneration() && apiBase == ApiClient.apiBase() &&
            userId == ApiClient.myUserId() && sessionId == ApiClient.currentToken()?.let(::parseJwtAuthSessionId)
}

internal fun currentDriverBankRecipientSetupOwner(generation: Long): DriverBankRecipientSetupOwner? {
    val userId = ApiClient.myUserId() ?: return null
    val sessionId = ApiClient.currentToken()?.let(::parseJwtAuthSessionId) ?: return null
    return DriverBankRecipientSetupOwner(userId, generation, ApiClient.apiBase(), sessionId)
        .takeIf(DriverBankRecipientSetupOwner::isCurrent)
}

internal class DriverBankRecipientSetupStore(
    private val prefs: SharedPreferences,
    private val owner: DriverBankRecipientSetupOwner,
) {
    private val scope = JSONObject().put("api", owner.apiBase).put("user", owner.userId)
        .put("session", owner.sessionId).toString()

    fun read(): DriverBankRecipientSetup? {
        if (!owner.isCurrent()) return null
        val stored = prefs.getString(DRIVER_BANK_RECIPIENT_PENDING_SETUP, null)?.let(::JSONObject) ?: return null
        return if (stored.getString("scope") == scope) stored.getJSONObject("setup").toDriverBankRecipientSetup() else null
    }

    fun keep(setup: DriverBankRecipientSetup): Boolean {
        var saved = false
        ApiClient.runIfCurrentSession(owner.generation) {
            if (owner.isCurrent()) {
                val value = JSONObject().put("scope", scope).put("setup", setup.toJson()).toString()
                saved = prefs.edit().putString(DRIVER_BANK_RECIPIENT_PENDING_SETUP, value).commit()
            }
        }
        return saved
    }

    fun clear(setup: DriverBankRecipientSetup) {
        ApiClient.runIfCurrentSession(owner.generation) {
            if (read()?.id == setup.id) prefs.edit().remove(DRIVER_BANK_RECIPIENT_PENDING_SETUP).commit()
        }
    }
}

internal const val DRIVER_BANK_RECIPIENT_PENDING_SETUP = "pending_setup"
