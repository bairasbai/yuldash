package com.yuldash.app

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.FcmService
import com.yuldash.app.data.OfflineTripSnapshots
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URI

@RunWith(AndroidJUnit4::class)
class ChatReceiptRuntimeInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun authorizedChatAcrossClients() {
        val arguments = InstrumentationRegistry.getArguments()
        val profile = arguments.getString("chatReceiptProbe").orEmpty()
        assumeTrue(profile in setOf("receiver", "sender"))
        assertEquals("http://127.0.0.1:8000", BuildConfig.YULDASH_API_BASE_URL)
        assertTrue(localJson("/__qa/stand").optString("sms") == "mock")
        val fixture = localJson("/__qa/chat/fixture/$profile")
        val bookingId = fixture.getInt("booking")
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        ApiClient.saveToken(fixture.getString("token"))
        runBlocking { OfflineTripSnapshots.ensureResetCommitted() }
        val language = if (arguments.getString("chatLang") == "ba") AppLanguage.Ba else AppLanguage.Ru
        val dark = arguments.getString("chatTheme") == "dark"
        val oldLanguage = AppPrefs.language(context)
        val oldTheme = ThemePrefs.darkOverride
        AppPrefs.setLanguage(context, language)
        persistThemeChoice(context, dark)
        context.getSharedPreferences("yuldash_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("onboarding_completed", true).commit()
        val launch = Intent(context, MainActivity::class.java)
            .putExtra(FcmService.EXTRA_PUSH_TYPE, "chat")
            .putExtra(FcmService.EXTRA_PUSH_ID, bookingId.toString())
            .putExtra("recipient_user_id", fixture.getInt("recipient").toString())
        try {
            ActivityScenario.launch<MainActivity>(launch).use {
                awaitNode(hasTestTag("tripChatCard") or hasTestTag("tripChatScreen"))
                if (compose.onAllNodes(hasTestTag("tripChatScreen")).fetchSemanticsNodes().isEmpty()) {
                    if (compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().size == 1) {
                        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("tripChatCard"))
                    }
                    compose.onNodeWithTag("tripChatCard").performClick()
                }
                awaitNode(hasTestTag("tripChatScreen"))
                if (profile == "receiver") {
                    awaitNode(hasText(fixture.getString("message")))
                    val generation = ApiClient.queueSessionGeneration()
                    val latest = fixture.getInt("latest")
                    val earlier = fixture.getInt("earlier")
                    compose.waitUntil(25_000) {
                        runBlocking {
                            ApiClient.getBookingChatReceipts(bookingId, null, generation).getOrNull()?.items
                                ?.firstOrNull { it.messageId == latest }?.myRead == true
                        }
                    }
                    val old = runBlocking {
                        ApiClient.getBookingChatReceipts(bookingId, null, generation).getOrThrow().items.single { it.messageId == earlier }
                    }
                    assertFalse(old.myDelivered)
                    assertFalse(old.myRead)
                } else {
                    compose.onNode(hasSetTextAction()).performTextInput(fixture.getString("message"))
                    val send = appTextFor(language, "Отправить", "Ебәреү")
                    compose.onNodeWithContentDescription(send).performClick()
                    awaitNode(hasContentDescription(appTextFor(language, "Сообщение прочитано", "Хәбәр уҡылған")))
                }
            }
        } finally {
            AppPrefs.setLanguage(context, oldLanguage)
            persistThemeChoice(context, oldTheme)
        }
    }

    private fun awaitNode(matcher: SemanticsMatcher) {
        compose.waitUntil(35_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(matcher).assertExists()
    }

    private fun localJson(path: String): JSONObject {
        val connection = URI("http://127.0.0.1:8000$path").toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 3_000
        connection.readTimeout = 5_000
        return try {
            assertEquals(200, connection.responseCode)
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }
}
