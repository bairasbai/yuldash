package com.yuldash.app

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuldash.app.data.ApiClient
import com.yuldash.app.ui.theme.YuldashTheme
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference

/** Opt-in loopback proof: real SQL commit, truncated HTTP reply, actual error controls. */
@RunWith(AndroidJUnit4::class)
class SafetyUnknownUiInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun qa(path: String, method: String = "POST"): JSONObject {
        val connection = URL("http://127.0.0.1:5198$path").openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 3000
        connection.readTimeout = 3000
        return try {
            assertEquals(200, connection.responseCode)
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }
    @After fun cleanup() { ApiClient.logout() }
    private fun verify(language: AppLanguage, dark: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Explicit local network matrix only", InstrumentationRegistry.getArguments().getString("networkMatrix") == "local")
        val fixture = qa("/__qa__/sos-bootstrap")
        ApiClient.init(instrumentation.targetContext)
        ApiClient.testBaseUrl = "http://127.0.0.1:5198"
        ApiClient.testTimeoutMs = 2000
        ApiClient.saveToken(fixture.getString("token"))
        for (permission in listOf(android.Manifest.permission.ACCESS_COARSE_LOCATION, android.Manifest.permission.ACCESS_FINE_LOCATION)) {
            instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, permission)
        }
        LocationPrefs.lastLat = null
        LocationPrefs.lastLng = null
        val booking = fixture.getInt("booking_id")
        val phase = mutableIntStateOf(0)
        val roadside = AtomicReference<Result<ApiClient.RoadsideResult>?>()
        val baseline = qa("/__qa__/sos-stats", "GET").getInt("events")
        qa("/__qa__/sos-arm?mode=eof")
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides language, LocalNightRide provides dark) {
                YuldashTheme(darkTheme = dark) {
                    if (phase.intValue == 0) {
                        SosScreen(onBack = {}, onLoginRequired = { fail("Synthetic owner must stay logged in") }, bookingId = booking)
                    } else {
                        Column(Modifier.fillMaxSize().background(CanonBg).systemBarsPadding().padding(CanonSpace.md)) {
                            RoadsideHelpAction(key = booking) { lat, lng, owner ->
                                ApiClient.roadsideHelp(booking, lat, lng, "Synthetic first unknown reply", owner).also { roadside.set(it) }
                            }
                        }
                    }
                }
            }
        }
        val ba = language == AppLanguage.Ba
        val submit = if (ba) "Ярҙамға хәбәр итеү" else "Сообщить поддержке"
        // Match the interactive button, not the section title with the same wording.
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(submit) and hasClickAction())
        compose.onNode(hasText(submit) and hasClickAction()).performScrollTo().performClick()
        val error = if (ba) "Ебәрелеүҙе раҫлап булманы" else "Не удалось подтвердить отправку"
        compose.waitUntil(10000) { runCatching { compose.onNodeWithText(error).assertExists() }.isSuccess }
        compose.onNodeWithText(error).performScrollTo().assertIsDisplayed()
        assertEquals(baseline + 1, qa("/__qa__/sos-stats", "GET").getInt("events"))
        saveImage("sos-first-unknown", language, dark)

        compose.runOnIdle { phase.intValue = 1 }
        compose.waitForIdle()
        qa("/__qa__/sos-arm?mode=eof")
        compose.onNodeWithText(if (ba) "Юлда ҡалдым" else "Застрял на трассе").performClick()
        compose.onNodeWithText(if (ba) "Ярҙам саҡырыу" else "Позвать помощь").performClick()
        compose.waitUntil(10000) { roadside.get() != null }
        assertTrue("Actual truncated reply must be unknown, not a saved-success response", roadside.get()!!.exceptionOrNull() is IOException)
        assertEquals(baseline + 2, qa("/__qa__/sos-stats", "GET").getInt("events"))
        android.os.SystemClock.sleep(350)
        saveImage("roadside-first-unknown", language, dark)
    }
    private fun saveImage(prefix: String, language: AppLanguage, dark: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), "network-matrix/$prefix-${language.name}-${if (dark) "dark" else "light"}.png")
        assertTrue(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
        file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        image.recycle()
    }
    @Test fun russianLight() = verify(AppLanguage.Ru, false)
    @Test fun russianDark() = verify(AppLanguage.Ru, true)
    @Test fun bashkirLight() = verify(AppLanguage.Ba, false)
    @Test fun bashkirDark() = verify(AppLanguage.Ba, true)
}
