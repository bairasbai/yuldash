package com.yuldash.app

import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.parseJwtUserId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiClientStateTest {

    @Test
    fun apiBaseAndWsBase_areDerivedFromBuildConfig() {
        assertTrue(ApiClient.apiBase().startsWith("http://") || ApiClient.apiBase().startsWith("https://"))
        assertTrue(ApiClient.wsBase().startsWith("ws://") || ApiClient.wsBase().startsWith("wss://"))
        assertFalse(ApiClient.wsBase().contains("http://"))
        assertFalse(ApiClient.wsBase().contains("https://"))
    }

    @Test
    fun saveToken_updatesInMemoryLoginStateWithoutPrefs() {
        ApiClient.saveToken("unit-token")

        assertTrue(ApiClient.isLoggedIn())
        assertEquals("unit-token", ApiClient.currentToken())
    }

    @Test
    fun saveName_ignoresBlankAndKeepsLastNonBlankName() {
        ApiClient.saveName("Unit User")
        ApiClient.saveName("")

        assertEquals("Unit User", ApiClient.cachedName())
    }

    @Test
    fun parseJwtUserId_readsNumericSubFromBase64UrlPayload() {
        val payload = java.util.Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString("""{"sub":"28"}""".toByteArray(Charsets.UTF_8))

        assertEquals(28, parseJwtUserId("header.$payload.signature"))
    }

    @Test
    fun parseJwtUserId_returnsNullForMalformedOrNonNumericSub() {
        val nonNumeric = java.util.Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString("""{"sub":"abc"}""".toByteArray(Charsets.UTF_8))

        assertNull(parseJwtUserId("header.$nonNumeric.signature"))
        assertNull(parseJwtUserId("not-a-jwt"))
        assertNull(parseJwtUserId("header.%bad.signature"))
    }
}
