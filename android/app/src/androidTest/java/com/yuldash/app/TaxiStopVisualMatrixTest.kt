package com.yuldash.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.InstantOrderDto
import com.yuldash.app.data.TAXI_CONSENT_LOCAL_API
import com.yuldash.app.ui.theme.YuldashTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic independent visual checks; no transport, providers, personal data or image files. */
@RunWith(AndroidJUnit4::class)
class TaxiStopVisualMatrixTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val previousTheme = ThemePrefs.darkOverride

    @After fun restoreTheme() { ThemePrefs.darkOverride = previousTheme }

    @Test fun mountedStopVisualCell() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("tf09VisualFixture") == "true")
        check(instrumentation is StorageAuditRunner && BuildConfig.DEBUG && BuildConfig.YULDASH_NATIVE_QA_PINNED_API)
        check(ApiClient.apiBase() == TAXI_CONSENT_LOCAL_API)
        val role = requireNotNull(args.getString("role"))
        check(role == "driver" || role == "passenger")
        val lang = when (args.getString("language")) {
            "ru" -> AppLanguage.Ru
            "ba" -> AppLanguage.Ba
            else -> error("Unsupported visual language")
        }
        val dark = when (args.getString("theme")) {
            "light" -> false
            "dark" -> true
            else -> error("Unsupported visual theme")
        }
        val context = instrumentation.targetContext
        for (permission in listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) {
            check(context.checkSelfPermission(permission) == PackageManager.PERMISSION_DENIED)
        }
        ApiClient.init(context)
        check(ApiClient.apiBase() == TAXI_CONSENT_LOCAL_API && !ApiClient.isLoggedIn())
        // Synthetic only; each cell starts with fresh app data and a fresh instrumentation invocation.
        val token = if (role == "driver") "e30.eyJzdWIiOiI3MSJ9.visual-fixture" else "e30.eyJzdWIiOiI3MiJ9.visual-fixture"
        ApiClient.saveToken(token)
        val generation = ApiClient.queueSessionGeneration()
        check(ApiClient.myUserId() == if (role == "driver") 71 else 72)
        ThemePrefs.darkOverride = dark
        val order = visualOrder(role)
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides lang, LocalNightRide provides null) {
                YuldashTheme(darkTheme = dark) {
                    Box(Modifier.fillMaxSize().testTag("tf09VisualRoot")) {
                        if (role == "driver") {
                            InstantDriverTripScreen(order.id, onBack = {}, onFinished = {}, initialOrder = order,
                                observeRemote = false, mapContent = { Box(it) })
                        } else {
                            TaxiTripScreen(order, onCancel = {}, enableLiveTracking = false, mapContent = { Box(it) },
                                ownerGeneration = generation, readShares = { _, _ -> Result.success(emptyList()) })
                        }
                    }
                }
            }
        }
        if (role == "driver") {
            val resume = if (lang == AppLanguage.Ru) "Поехали" else "Киттек"
            compose.onNodeWithText(resume).performScrollTo().assertIsDisplayed()
        } else {
            compose.onNodeWithTag("taxiSheetDragArea").performTouchInput { swipeUp(durationMillis = 600) }
            compose.onNodeWithTag("taxiReplaceStop0").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("taxiReplaceStop1").assertDoesNotExist()
            compose.onNodeWithTag("taxiRemoveStop1").assertDoesNotExist()
            val waiting = if (lang == AppLanguage.Ru) "Водитель ждёт" else "Йөрөтөүсе көтә"
            compose.onNodeWithText(waiting).performScrollTo().assertIsDisplayed()
        }
        // Capture the rendered surface after real scroll/semantics. Never save or export image bytes.
        val frame = compose.onNodeWithTag("tf09VisualRoot").captureToImage()
        check(frame.width > 0 && frame.height > 0)
        val pixels = frame.toPixelMap()
        // Exact CanonSurface colors for the two themes; the surface should occupy >5% of this view.
        val expectedSurface = if (dark) 0xff192420.toInt() else 0xffffffff.toInt()
        var visibleSurface = 0
        var sampled = 0
        for (y in 0 until frame.height step 4) for (x in 0 until frame.width step 4) {
            sampled++
            if (pixels[x, y].toArgb() == expectedSurface) visibleSurface++
        }
        check(visibleSurface * 20 > sampled)
    }

    private fun visualOrder(role: String): InstantOrderDto {
        // TaxiTripScreen can force nighttime based on pickup coordinates. Use an equatorial
        // synthetic noon so actual sun never overrides the independently stated visual theme.
        val hour = Math.floorMod(System.currentTimeMillis(), 86_400_000L).toDouble() / 3_600_000
        val lng = (12.0 - hour) * 15
        check(!SunTime.isDark(0.0, lng, System.currentTimeMillis()))
        fun id(value: Int) = "00000000-0000-4000-a000-00000000000$value"
        fun row(value: Int) = JSONObject().put("lat", 0.2).put("lng", lng).put("text", "Synthetic stop")
            .put("done", false).put("waypoint_id", id(value))
        val control = JSONObject().put("admission_enabled", false).put("passenger_edit_v1", true)
            .put("route_revision", id(3)).put("wait_revision", id(4))
            .put("active_wait", JSONObject().put("kind", "planned").put("wait_id", id(4)).put("waypoint_id", id(2)))
        val wire = JSONObject().put("id", 77).put("role", role).put("status", "onboard").put("standing", true)
            .put("stops", JSONArray().put(row(1)).put(row(2))).put("stop_control", control)
            .put("from_lat", 0.0).put("from_lng", lng).put("from_text", "Synthetic pickup")
            .put("to_lat", 0.3).put("to_lng", lng).put("to_text", "Synthetic destination")
            .put("category", "standard").put("price_estimate", 600).put("passenger_price_kop", 60000)
        return ApiClient.parseTaxiConsentOrder(wire)
    }
}
