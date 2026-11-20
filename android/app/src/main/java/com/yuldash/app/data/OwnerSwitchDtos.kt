package com.yuldash.app.data

import org.json.JSONArray
import org.json.JSONObject

internal const val PAID_INTEGRATIONS_PAUSED = "external_paid_paused"

internal enum class OwnerSwitchKey(val key: String) {
    PaidIntegrations("external.paid"),
    SmsNotices("external.sms_notices"),
    RideshareCard("rideshare_card_payments"),
    Sms("login.sms"),
    Telegram("login.telegram"),
    Vk("login.vk"),
    WhatsApp("login.whatsapp"),
}

internal data class OwnerSwitchChange(
    val value: Boolean,
    val author: String?,
    val createdAt: String,
    val note: String?,
)

internal data class OwnerSwitchDto(
    val stored: Boolean?,
    val effective: Boolean,
    val blockers: List<String>,
    val editable: Boolean,
    val revision: Long?,
    val inFlight: Long?,
    val updatedAt: String?,
    val updatedBy: String?,
    val history: List<OwnerSwitchChange>,
    val localizedBlockers: Map<String, LocalizedSwitchBlocker> = emptyMap(),
)

internal data class LocalizedSwitchBlocker(val code: String, val ru: String, val ba: String)

internal fun localizedSwitchBlockers(rows: JSONArray?): List<LocalizedSwitchBlocker> =
    if (rows == null) emptyList() else (0 until rows.length()).mapNotNull {
        rows.optJSONObject(it)?.let { item ->
            LocalizedSwitchBlocker(item.getString("code"), item.getString("ru"), item.getString("ba"))
        }
    }

internal fun JSONObject.toOwnerSwitches(): Map<OwnerSwitchKey, OwnerSwitchDto> {
    val switches = getJSONObject("switches")
    return OwnerSwitchKey.entries.mapNotNull { key ->
        switches.optJSONObject(key.key)?.let { key to it.toOwnerSwitch() }
    }.toMap()
}

internal fun JSONObject.toOwnerSwitch(): OwnerSwitchDto {
    val blockerRows = getJSONArray("blockers")
    val historyRows = getJSONArray("history")
    return OwnerSwitchDto(
        stored = opt("stored") as? Boolean,
        effective = getBoolean("effective"),
        blockers = List(blockerRows.length()) { index ->
            blockerRows.optJSONObject(index)?.getString("code") ?: blockerRows.getString(index)
        },
        localizedBlockers = localizedSwitchBlockers(blockerRows).associateBy { it.code },
        editable = optBoolean("editable", true),
        revision = safeCount(opt("revision")),
        inFlight = safeCount(opt("in_flight")),
        updatedAt = nullableString("updated_at"),
        updatedBy = nullableString("updated_by_name"),
        history = List(historyRows.length()) { index ->
            val change = historyRows.getJSONObject(index)
            OwnerSwitchChange(
                value = change.getBoolean("new_value"),
                author = change.nullableString("admin_name"),
                createdAt = change.getString("created_at"),
                note = change.nullableString("note"),
            )
        },
    )
}

internal fun ownerSwitchAffected(error: Throwable): Long? {
    val refusal = error as? ApiException ?: return null
    if (refusal.status != 409 || refusal.detailCode != "switch_needs_confirm") return null
    return safeCount(refusal.detail?.opt("affected"))?.takeIf { it > 0 }
}

private fun safeCount(value: Any?): Long? {
    val count = when (value) {
        is Int -> value.toLong()
        is Long -> value
        else -> return null
    }
    return count.takeIf { it in 0..9_007_199_254_740_991L }
}

private fun JSONObject.nullableString(key: String): String? =
    if (isNull(key)) null else optString(key).ifBlank { null }
