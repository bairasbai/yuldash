package com.yuldash.app.data

import java.time.Instant
import org.json.JSONObject

private const val RECIPIENT_PATH = "/driver/bank-recipient"
private val recipientVersionPattern = Regex("[a-f0-9]{32}")

internal enum class DriverBankRecipientState(val wire: String) {
    Disabled("disabled"),
    Unconfigured("unconfigured"),
    Configured("configured"),
    RequiresReconnect("requires_reconnect"),
}

internal data class DriverBankRecipient(
    val state: DriverBankRecipientState,
    val version: String?,
    val canRevoke: Boolean,
)

internal data class DriverBankRecipientSetup(
    val id: String,
    val version: String?,
    val expiresAt: Instant,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("setup_id", id)
        .put("version", version ?: JSONObject.NULL)
        .put("mode", "synthetic")
        .put("expires_at", expiresAt.toString())
}

internal data class DriverBankRecipientConfirmation(val version: String)

private fun JSONObject.recipientVersion(): String? =
    if (get("version") == JSONObject.NULL) null
    else getString("version").also { require(recipientVersionPattern.matches(it)) }

private fun JSONObject.toDriverBankRecipient(): DriverBankRecipient = DriverBankRecipient(
    state = DriverBankRecipientState.entries.first { it.wire == getString("state") },
    version = recipientVersion(),
    canRevoke = getBoolean("can_revoke"),
)

internal fun JSONObject.toDriverBankRecipientSetup(): DriverBankRecipientSetup {
    require(getString("mode") == "synthetic")
    return DriverBankRecipientSetup(
        id = getString("setup_id").also { require(recipientVersionPattern.matches(it)) },
        version = recipientVersion(),
        expiresAt = Instant.parse(getString("expires_at")),
    )
}

private fun JSONObject.toDriverBankRecipientConfirmation(): DriverBankRecipientConfirmation =
    DriverBankRecipientConfirmation(requireNotNull(recipientVersion()))

internal suspend fun ApiClient.getDriverBankRecipient(generation: Long): Result<DriverBankRecipient> =
    accountSettingsRequest("GET", RECIPIENT_PATH, generation).mapCatching(JSONObject::toDriverBankRecipient)

internal suspend fun ApiClient.startDriverBankRecipient(
    version: String?, generation: Long,
): Result<DriverBankRecipientSetup> {
    val body = JSONObject().put("version", version ?: JSONObject.NULL)
    return accountSettingsRequest("POST", "$RECIPIENT_PATH/setup", generation, body)
        .mapCatching(JSONObject::toDriverBankRecipientSetup)
}

internal suspend fun ApiClient.confirmDriverBankRecipient(
    setup: DriverBankRecipientSetup, generation: Long,
): Result<DriverBankRecipientConfirmation> {
    val body = JSONObject().put("consent", true).put("token", "synthetic-${setup.id}")
    return accountSettingsRequest("POST", "$RECIPIENT_PATH/setup/${setup.id}/confirm", generation, body)
        .mapCatching(JSONObject::toDriverBankRecipientConfirmation)
}

internal suspend fun ApiClient.getDriverBankRecipientReceipt(
    setup: DriverBankRecipientSetup, generation: Long,
): Result<DriverBankRecipientConfirmation?> =
    accountSettingsRequest("GET", "$RECIPIENT_PATH/setup/${setup.id}/receipt", generation).mapCatching {
        if (it.get("confirmation") == JSONObject.NULL) null
        else it.getJSONObject("confirmation").toDriverBankRecipientConfirmation()
    }

internal suspend fun ApiClient.revokeDriverBankRecipient(
    version: String, generation: Long,
): Result<DriverBankRecipient> =
    accountSettingsRequest("POST", "$RECIPIENT_PATH/revoke", generation, JSONObject().put("version", version))
        .mapCatching(JSONObject::toDriverBankRecipient)
