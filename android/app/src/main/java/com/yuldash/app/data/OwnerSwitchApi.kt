package com.yuldash.app.data

import org.json.JSONObject

internal fun JSONObject.toLoginMethods(): List<String> {
    val methods = getJSONArray("methods")
    return (0 until methods.length()).mapNotNull { methods.opt(it) as? String }
}

internal suspend fun ApiClient.ownerSwitches(generation: Long): Result<Map<OwnerSwitchKey, OwnerSwitchDto>> =
    accountSettingsRequest("GET", "/admin/switches", generation).mapCatching(JSONObject::toOwnerSwitches)

internal suspend fun ApiClient.setOwnerSwitch(
    key: OwnerSwitchKey,
    value: Boolean,
    confirm: Boolean,
    generation: Long,
    revision: Long?,
): Result<OwnerSwitchDto> {
    val body = JSONObject().put("value", value).put("confirm", confirm).put("expected_revision", revision)
    return accountSettingsRequest("POST", "/admin/switches/${key.key}", generation, body).mapCatching(JSONObject::toOwnerSwitch)
}
