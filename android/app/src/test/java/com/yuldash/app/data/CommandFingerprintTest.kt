package com.yuldash.app.data

import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CommandFingerprintTest {
    @Test
    fun sharedWireFingerprints() {
        val path = "tools/fixtures/rs05-command-fingerprint.json"
        val fixture = generateSequence(File(System.getProperty("user.dir").orEmpty())) { it.parentFile }
            .map { File(it, path) }.first { it.isFile }
        val examples = JSONArray(fixture.readText())
        for (index in 0 until examples.length()) {
            val example = examples.getJSONObject(index)
            val name = example.getString("name")
            val scope = example.getString("scope")
            val target = if (example.isNull("target_id")) null else example.getLong("target_id")
            val wire = if (example.isNull("wire_body")) null else example.getString("wire_body")
            val body = wire?.takeUnless { it == "null" }?.let(::JSONObject)
            val canonical = example.getString("canonical_body")
            val envelope = """{"body":$canonical,"scope":"$scope","target_id":$target}"""
            val expected = MessageDigest.getInstance("SHA-256").digest(envelope.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val snapshot = CommandFingerprint.create(scope, target, body)
            assertEquals(name, expected, snapshot.payloadHash)
            assertEquals(name, canonical, snapshot.wireBody ?: "null")
        }
    }

    @Test
    fun refusesBodyThatCannotBeSent() {
        for (value in listOf("\ud800", "я".repeat(64 * 1024))) {
            assertThrows(IllegalArgumentException::class.java) {
                CommandFingerprint.create("ride.create", null, JSONObject().put("bad", value))
            }
        }
    }
}
