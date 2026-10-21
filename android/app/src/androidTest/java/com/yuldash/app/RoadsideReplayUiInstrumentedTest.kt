package com.yuldash.app

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Actual controls, HTTP, SQL, and rendering; external emergency channels are disabled by the local adapter. */
@RunWith(AndroidJUnit4::class)
class RoadsideReplayUiInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun qa(path: String): JSONObject {
        val connection = URL("http://127.0.0.1:5198$path").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
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
        val booking = fixture.getInt("booking_id")
        val completed = AtomicInteger(0)
        val preview = mutableIntStateOf(0)
        val lastResult = AtomicReference<Result<ApiClient.RoadsideResult>>()
        LocationPrefs.lastLat = null
        LocationPrefs.lastLng = null
        qa("/__qa__/sos-arm")
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides language, LocalNightRide provides dark) {
                YuldashTheme(darkTheme = dark) {
                    Column(Modifier.fillMaxSize().background(CanonBg).systemBarsPadding().padding(CanonSpace.md)) {
                        key(preview.intValue) {
                        val mode = preview.intValue
                        RoadsideHelpAction(key = booking to mode) { lat, lng, owner ->
                            // Mode1 uses the real local200. Modes2/3 only preview channel-count copy.
                            when (mode) {
                                2 -> Result.success(ApiClient.RoadsideResult(0, 2))
                                3 -> Result.success(ApiClient.RoadsideResult(2, 2))
                                else -> ApiClient.roadsideHelp(booking, lat, lng, if (mode == 1) "Synthetic first successful UI" else "Synthetic roadside UI", owner)
                            }.also {
                                lastResult.set(it)
                                instrumentation.sendStatus(0, android.os.Bundle().apply {
                                    putString("ui_language", language.name)
                                    putBoolean("send_success", it.isSuccess)
                                    putString("send_exception", it.exceptionOrNull()?.javaClass?.simpleName)
                                    putInt("send_http", (it.exceptionOrNull() as? com.yuldash.app.data.ApiException)?.status ?: 0)
                                    putBoolean("send_owner_current", ApiClient.isCurrentSession(owner))
                                })
                                completed.incrementAndGet()
                            }
                        }
                        }
                    }
                }
            }
        }
        val ba = language == AppLanguage.Ba
        compose.onNodeWithText(if (ba) "Юлда ҡалдым" else "Застрял на трассе").performClick()
        val confirm = if (ba) "Ярҙам саҡырыу" else "Позвать помощь"
        compose.onNodeWithText(confirm).performClick()
        compose.waitUntil(10000) { completed.get() == 1 }
        compose.waitForIdle()
        compose.onNodeWithText(confirm).performClick()
        compose.waitUntil(10000) { completed.get() == 2 }
        assertTrue("Second explicit retry: ${lastResult.get().exceptionOrNull()}", lastResult.get().isSuccess)
        val message = if (ba)
            "Сигнал Юлдашта һаҡланған. Хәбәрҙәрҙең барып етеүе раҫланмаған. Мөмкин булһа, 112-гә һәм яҡындарыңа шылтырат."
        else "Сигнал уже сохранён в Юлдаш. Доставка сообщений не подтверждена. Позвони 112 и близким, если можешь."
        compose.waitUntil(10000) {
            runCatching { compose.onNodeWithText(message).assertIsDisplayed() }.isSuccess
        }
        // Capture the settled screen after the error toast and dialog fade have finished.
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(4000)
        val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), "network-matrix/roadside-${language.name}-${if (dark) "dark" else "light"}.png")
        assertTrue(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
        file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        image.recycle()
        for (mode in 1..3) {
            compose.runOnIdle { preview.intValue = mode }
            compose.waitForIdle()
            compose.onNodeWithText(if (ba) "Юлда ҡалдым" else "Застрял на трассе").performClick()
            val target = completed.get() + 1
            compose.onNodeWithText(confirm).performClick()
            compose.waitUntil(10000) { completed.get() == target }
            assertTrue(lastResult.get().isSuccess)
            val result = lastResult.get().getOrThrow()
            assertEquals(if (mode == 3) 2 else 0, result.notified)
            assertEquals(if (mode == 1) 0 else 2, result.contactsTotal)
            compose.onNodeWithText(if (ba) "Сигнал Юлдашта һаҡланды." else "Сигнал сохранён в Юлдаш.", substring = true).assertIsDisplayed()
            android.os.SystemClock.sleep(600)
            instrumentation.waitForIdleSync()
            val saved = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            val savedFile = File(instrumentation.targetContext.getExternalFilesDir(null), "network-matrix/roadside-saved-$mode-${language.name}-${if (dark) "dark" else "light"}.png")
            savedFile.outputStream().use { assertTrue(saved.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            saved.recycle()
        }
    }
    @Test fun russianLight() = verify(AppLanguage.Ru, false)
    @Test fun russianDark() = verify(AppLanguage.Ru, true)
    @Test fun bashkirLight() = verify(AppLanguage.Ba, false)
    @Test fun bashkirDark() = verify(AppLanguage.Ba, true)
}
