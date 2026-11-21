package com.yuldash.app.data

import java.util.Base64
import org.json.JSONObject

/** Идентификаторы уже полученного токена для привязки клиентского состояния к входу. */
internal fun parseJwtUserId(token: String): Int? = parseJwtClaims(token)?.optString("sub")?.toIntOrNull()

internal fun parseJwtAuthSessionId(token: String): String? =
    (parseJwtClaims(token)?.opt("sid") as? String)?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }

private fun parseJwtClaims(token: String): JSONObject? = runCatching {
    val payload = token.split(".").getOrNull(1) ?: return null
    val pad = (4 - payload.length % 4) % 4
    val normalized = payload + "=".repeat(pad)
    val json = String(Base64.getUrlDecoder().decode(normalized), Charsets.UTF_8)
    JSONObject(json)
}.getOrNull()
