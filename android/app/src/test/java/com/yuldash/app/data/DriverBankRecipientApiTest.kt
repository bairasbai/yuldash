package com.yuldash.app.data

import java.time.Instant
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class DriverBankRecipientApiTest {
    private val id = "a".repeat(32)
    private val time = Instant.parse("2030-01-01T00:00:00Z")

    @Test fun storedSetupContainsOnlyRecoveryFields() {
        val original = DriverBankRecipientSetup(id = id, version = null, expiresAt = time)
        val stored = original.toJson()
        assertEquals(original, stored.toDriverBankRecipientSetup())
        assertFalse(stored.has("token"))
        assertEquals(setOf("mode", "setup_id", "version", "expires_at"), stored.keys().asSequence().toSet())
    }

    @Test fun setupParserRejectsUnrecognizedModeAndUnsafeIdentifier() {
        val valid = DriverBankRecipientSetup(id = id, version = "b".repeat(32), expiresAt = time)
        assertThrows(IllegalArgumentException::class.java) {
            valid.toJson().put("mode", "live").toDriverBankRecipientSetup()
        }
        assertThrows(IllegalArgumentException::class.java) {
            valid.toJson().put("setup_id", "../revoke").toDriverBankRecipientSetup()
        }
    }

    @Test fun stableLoginIdIsSeparateFromAccountId() {
        val originalLogin = "c".repeat(64)
        val nextLogin = "d".repeat(64)
        fun jwt(sid: String?): String {
            val claims = JSONObject().put("sub", "7").put("sid", sid ?: JSONObject.NULL)
            return "header." + Base64.getUrlEncoder().withoutPadding().encodeToString(claims.toString().toByteArray()) + ".sig"
        }
        assertEquals(7, parseJwtUserId(jwt(originalLogin)))
        assertEquals(7, parseJwtUserId(jwt(nextLogin)))
        assertEquals(originalLogin, parseJwtAuthSessionId(jwt(originalLogin)))
        assertEquals(nextLogin, parseJwtAuthSessionId(jwt(nextLogin)))
        assertNull(parseJwtAuthSessionId(jwt(null)))
        assertNull(parseJwtAuthSessionId(jwt("inactive")))
    }
}
