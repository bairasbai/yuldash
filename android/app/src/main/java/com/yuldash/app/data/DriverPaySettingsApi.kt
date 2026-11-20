package com.yuldash.app.data

import org.json.JSONArray
import org.json.JSONObject

internal val directDriverPayMethods = listOf("cash", "sbp")

internal fun driverPayMethodList(array: JSONArray?): List<String> {
    if (array == null) return emptyList()
    val values = (0 until array.length()).mapNotNull { array.opt(it) as? String }.toSet()
    return directDriverPayMethods.filter { it in values }
}

internal data class DriverPaySettingsDto(val methods: List<String>, val updatedAt: String?)

internal fun JSONObject.toDriverPaySettings(): DriverPaySettingsDto =
    DriverPaySettingsDto(driverPayMethodList(getJSONArray("methods")),
        if (isNull("updated_at")) null else getString("updated_at"))

internal suspend fun ApiClient.getDriverPaySettings(generation: Long): Result<DriverPaySettingsDto> =
    accountSettingsRequest("GET", "/driver/pay-settings", generation).mapCatching(JSONObject::toDriverPaySettings)

internal suspend fun ApiClient.setDriverPaySettings(
    methods: List<String>, expectedUpdatedAt: String?, generation: Long,
): Result<DriverPaySettingsDto> {
    val body = JSONObject().put("methods", JSONArray(methods))
        .put("expected_updated_at", expectedUpdatedAt ?: JSONObject.NULL)
    return accountSettingsRequest("PUT", "/driver/pay-settings", generation, body).mapCatching(JSONObject::toDriverPaySettings)
}
