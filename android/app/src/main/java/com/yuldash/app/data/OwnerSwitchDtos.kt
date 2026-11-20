package com.yuldash.app.data

import org.json.JSONObject

internal enum class OwnerLoginSwitch(val key: String) {
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
    val inFlight: Long?,
    val updatedAt: String?,
    val updatedBy: String?,
    val history: List<OwnerSwitchChange>,
)

internal fun JSONObject.toOwnerSwitches(): Map<OwnerLoginSwitch, OwnerSwitchDto> {
    val switches = getJSONObject("switches")
    return OwnerLoginSwitch.entries.mapNotNull { key ->
        switches.optJSONObject(key.key)?.let { key to it.toOwnerSwitch() }
    }.toMap()
}

internal fun JSONObject.toOwnerSwitch(): OwnerSwitchDto {
    val blockerRows = getJSONArray("blockers")
    val historyRows = getJSONArray("history")
    return OwnerSwitchDto(
        stored = opt("stored") as? Boolean,
        effective = getBoolean("effective"),
        blockers = List(blockerRows.length()) { blockerRows.getString(it) },
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
