package com.yuldash.app.data

import java.math.BigDecimal
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

internal object CommandFingerprint {
    const val KEY_PREFIX = "cmd1_"
    private const val MAX_BODY_BYTES = 64 * 1024
    private const val MAX_DEPTH = 32

    data class Snapshot(val wireBody: String?, val payloadHash: String)

    /** The serialized body is the snapshot to send with the key and hash. */
    fun create(scope: String, targetId: Long?, body: JSONObject? = null): Snapshot {
        val wireBody = body?.let { canonical(it, 1) }
        require(wireBody == null || wireBody.toByteArray(Charsets.UTF_8).size <= MAX_BODY_BYTES) {
            "Command JSON body is too large"
        }
        val envelope = "{\"body\":${wireBody ?: "null"},\"scope\":${jsonString(scope)},\"target_id\":$targetId}"
        val hash = MessageDigest.getInstance("SHA-256").digest(envelope.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return Snapshot(wireBody, hash)
    }

    private fun canonical(value: Any?, depth: Int): String {
        require(depth <= MAX_DEPTH) { "Command JSON is too deeply nested" }
        return when {
            value == null || value === JSONObject.NULL -> "null"
            value is Boolean -> value.toString()
            value is String -> jsonString(value)
            value is Number -> decimalText(value)
            value is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") {
                canonical(value.get(it), depth + 1)
            }
            value is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") {
                jsonString(it) + ":" + canonical(value.get(it), depth + 1)
            }
            else -> throw IllegalArgumentException("Unsupported command JSON value")
        }
    }

    private fun decimalText(value: Number): String {
        val decimal = BigDecimal(value.toString())
        require(decimal.precision() <= 350) { "Invalid number in command JSON" }
        if (decimal.signum() == 0) return "0"
        val exponent = decimal.precision().toLong() - decimal.scale() - 1
        require(exponent in -324L..308L) { "Number outside command JSON range" }
        return decimal.stripTrailingZeros().toPlainString()
    }

    private fun jsonString(value: String): String = buildString {
        append('"')
        for ((index, character) in value.withIndex()) {
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\t' -> append("\\t")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\u000c' -> append("\\f")
                in '\u0000'..'\u001f' -> append("\\u%04x".format(character.code))
                else -> {
                    require(!character.isHighSurrogate() || value.getOrNull(index + 1)?.isLowSurrogate() == true)
                    require(!character.isLowSurrogate() || value.getOrNull(index - 1)?.isHighSurrogate() == true)
                    append(character)
                }
            }
        }
        append('"')
    }
}
