package com.yuldash.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Process
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.TAXI_CONSENT_LOCAL_API
import com.yuldash.app.data.TAXI_CONSENT_PENDING
import com.yuldash.app.data.TAXI_CONSENT_PREFS
import com.yuldash.app.data.TaxiQuoteConsentApi
import com.yuldash.app.ui.theme.YuldashTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor

/** run.sh owns the isolated API, SQL assertions and force-stop between these two device phases. */
@RunWith(AndroidJUnit4::class)
class TaxiConsentRecoveryInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private val context get() = instrumentation.targetContext
    private val qaDir get() = File(context.filesDir, "taxi-consent-qa")
    private val language get() = if (args.getString("language") == "ba") AppLanguage.Ba else AppLanguage.Ru
    private val dark get() = args.getString("dark") == "true"

    @Before fun isolatedFixtureOnly() {
        assumeTrue(args.getString("taxiConsentFixture") == "true")
        check(instrumentation is StorageAuditRunner && BuildConfig.DEBUG)
        check(BuildConfig.YULDASH_NATIVE_QA_PINNED_API && TaxiQuoteConsentApi.requested)
        ApiClient.init(context)
        check(ApiClient.apiBase() == TAXI_CONSENT_LOCAL_API)
    }

    private fun installFixtureSession(): JSONObject {
        val file = File(qaDir, "session.json")
        val session = JSONObject(file.readText())
        check(file.delete())
        ApiClient.saveToken(session.getString("access"))
        val owner = requireNotNull(TaxiQuoteConsentApi.owner())
        assertEquals(session.getInt("uid"), owner.userId)
        return session
    }

    private fun showPassengerScreen() {
        compose.setContent {
            YuldashTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalAppLanguage provides language, LocalNightRide provides dark) {
                    InstantOrderScreen(onBack = {}, onLoginRequired = { error("Session lost") }, renderNativeMap = false)
                }
            }
        }
    }

    private fun openPassengerRoute() {
        installFixtureSession()
        // Supply the last known pickup exactly as the passenger picker reads it. Neither a live
        // location provider nor an external map/geocoder should participate in the fixture.
        for (permission in listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) {
            check(context.checkSelfPermission(permission) == PackageManager.PERMISSION_DENIED)
        }
        compose.runOnUiThread {
            LocationPrefs.lastLat = 52.591
            LocationPrefs.lastLng = 58.317
        }
        showPassengerScreen()
        val destination = hasText("QA Destination") and hasClickAction()
        compose.waitUntil(30_000) { compose.onAllNodes(destination).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(destination).performScrollTo().performClick()
    }

    @Test fun roundTripRetainsWaitAndCreatesOrder() {
        openPassengerRoute()
        click("taxiConsentPrimary")
        waitForTag("taxiConsentConfirm")
        compose.onNodeWithText("600 ₽ − скидка 150 ₽. Итого к подтверждению: 450 ₽.").assertIsDisplayed()
        click("taxiConsentReviewCancel")
        click("taxiOrderDetails")
        val roundTrip = isToggleable() and hasAnyAncestor(hasTestTag("taxiRoundTripOptions"))
        compose.waitUntil(30_000) { compose.onAllNodes(roundTrip).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(roundTrip).performScrollTo().performClick()
        compose.onNode(roundTrip).assertIsOn()
        compose.onNodeWithTag("taxiRoundTripWait120").performScrollTo().performClick()
        compose.onNodeWithTag("taxiRoundTripWait120").assertIsSelected()
        compose.onNode(roundTrip).assertIsOn()
        click("taxiConsentPrimary")
        waitForTag("taxiConsentConfirm")
        compose.onNodeWithText("1020 ₽ − скидка 150 ₽. Итого к подтверждению: 870 ₽.").assertIsDisplayed()
        click("taxiConsentConfirm")

        captureOrderCard("roundtrip-resolved", "Ищем машину")
    }

    @Test fun confirmThenWaitForForceStop() {
        openPassengerRoute()
        click("taxiConsentPrimary")
        waitForTag("taxiConsentConfirm")
        val summary = if (language == AppLanguage.Ba) "600 ₽ − ташлама 150 ₽. Раҫларға бөтәһе: 450 ₽."
                      else "600 ₽ − скидка 150 ₽. Итого к подтверждению: 450 ₽."
        compose.onNodeWithText(summary).assertIsDisplayed()
        capture("quote-review")
        rendezvous("review")
        click("taxiConsentConfirm")
        waitForTag("taxiConsentCheckResult")
        compose.onNodeWithTag("taxiConsentNotice").assertIsDisplayed()
        val rows = JSONArray(context.getSharedPreferences(TAXI_CONSENT_PREFS, Context.MODE_PRIVATE)
            .getString(TAXI_CONSENT_PENDING, null) ?: error("Missing recovery marker"))
        assertEquals(1, rows.length())
        val marker = rows.getJSONObject(0)
        assertEquals(setOf("v", "request_key", "scope", "origin", "close_requested"), marker.keys().asSequence().toSet())
        assertEquals(2, marker.getInt("v"))
        assertFalse(marker.getBoolean("close_requested"))
        capture("interrupted")
        awaitProcessReplacement()
    }

    private fun awaitProcessReplacement() {
        File(qaDir, "seed-pid").writeText(Process.myPid().toString())
        File(qaDir, "force-stop.ready").createNewFile()
        // The host stops this active instrumentation process via force-stop or an in-place APK update.
        SystemClock.sleep(120_000)
        fail("Host did not replace the waiting process")
    }

    @Test fun coldEntryChecksOrClosesOriginalAttempt() {
        assertNotEquals(File(qaDir, "seed-pid").readText(), Process.myPid().toString())
        checkNotNull(TaxiQuoteConsentApi.owner())
        showPassengerScreen()
        waitForTag("taxiConsentCheckResult")
        compose.onNodeWithTag("taxiConsentConfirm").assertDoesNotExist()
        rendezvous("cold")
        val checkLabel = if (language == AppLanguage.Ba) "Заказ һөҙөмтәһен тикшереү" else "Проверить результат заказа"
        capture("cold-entry", checkLabel)
        click("taxiConsentCheckResult")
        if (args.getString("case") == "committed") {
            val expiredTitle = if (language == AppLanguage.Ba) "Яҡында әлегә бер кем дә юҡ" else "Рядом пока никого"
            captureOrderCard("resolved", expiredTitle, requireNotNull(args.getString("orderId")).toInt())
        } else {
            val message = if (language == AppLanguage.Ba)
                "Сервер заказды әлегә раҫламаны. Тағы тикшерергә йәки ынтылышты тамамларға мөмкин."
            else "Сервер пока не подтвердил заказ. Можно проверить ещё раз или завершить попытку."
            compose.waitUntil(30_000) {
                compose.onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty()
            }
            click("taxiConsentClose")
            click("taxiConsentCloseCancel")
            rendezvous("cancel-close")
            click("taxiConsentClose")
            click("taxiConsentCloseConfirm")
            val closed = if (language == AppLanguage.Ba) "Ынтылыш тамамланды. Заказ булдырылманы."
                         else "Попытка завершена. Заказ не был создан."
            compose.waitUntil(30_000) { compose.onAllNodesWithText(closed).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(closed).assertIsDisplayed()
            capture("resolved", closed)
        }
        assertFalse(TaxiQuoteConsentApi.markers(context).pending(requireNotNull(TaxiQuoteConsentApi.owner()).binding))
    }

    @Test fun seedThreeIssuedAttemptsAndWaitForRestart() {
        val keys = installFixtureSession().getJSONArray("request_keys")
        assertEquals(3, keys.length())
        val binding = requireNotNull(TaxiQuoteConsentApi.owner()).binding
        val rows = JSONArray()
        repeat(keys.length()) { index ->
            rows.put(JSONObject().put("scope", binding.scope).put("origin", binding.origin)
                .put("v", 2).put("request_key", keys.getString(index)).put("close_requested", false))
        }
        val prefs = context.getSharedPreferences(TAXI_CONSENT_PREFS, Context.MODE_PRIVATE)
        check(prefs.edit().putString(TAXI_CONSENT_PENDING, rows.toString()).commit())
        awaitProcessReplacement()
    }

    @Test fun coldEntryChecksAndClosesOnlyThirdAttempt() {
        assertNotEquals(File(qaDir, "seed-pid").readText(), Process.myPid().toString())
        val prefs = context.getSharedPreferences(TAXI_CONSENT_PREFS, Context.MODE_PRIVATE)
        fun rows(): List<String> {
            val stored = JSONArray(requireNotNull(prefs.getString(TAXI_CONSENT_PENDING, null)))
            return (0 until stored.length()).map { stored.getJSONObject(it).toString() }
        }
        val original = rows()
        assertEquals(3, original.size)
        showPassengerScreen()
        click("taxiConsentAttemptPicker")
        fun label(number: Int) = if (language == AppLanguage.Ba) "Ынтылыш № $number" else "Попытка $number"
        for (number in 1..3) {
            val option = hasTestTag("taxiConsentAttemptOption$number") and hasText(label(number))
            compose.onNode(option).assertIsDisplayed()
        }
        click("taxiConsentAttemptOption3")
        capture("third-selected", label(3))
        rendezvous("selected")
        click("taxiConsentCheckResult")
        val unknown = if (language == AppLanguage.Ba)
            "Сервер заказды әлегә раҫламаны. Тағы тикшерергә йәки ынтылышты тамамларға мөмкин."
        else "Сервер пока не подтвердил заказ. Можно проверить ещё раз или завершить попытку."
        compose.waitUntil(30_000) { compose.onAllNodesWithText(unknown).fetchSemanticsNodes().isNotEmpty() }
        click("taxiConsentClose")
        click("taxiConsentCloseCancel")
        assertTrue("Lookup and dismissal must preserve every record", original == rows())
        rendezvous("cancel-close")
        click("taxiConsentClose")
        click("taxiConsentCloseConfirm")
        compose.waitUntil(30_000) { rows().size == 2 }
        assertTrue("Only the selected record may be removed", original.take(2) == rows())
        click("taxiConsentAttemptPicker")
        compose.onNode(hasTestTag("taxiConsentAttemptOption2") and hasText(label(2))).assertIsDisplayed()
        compose.onNodeWithTag("taxiConsentAttemptOption3").assertDoesNotExist()
    }

    private fun captureOrderCard(name: String, title: String, id: Int? = null) {
        val container = if (id != null) hasTestTag("taxiOrderCard-$id") else
            SemanticsMatcher("published passenger order card") {
                SemanticsProperties.TestTag in it.config &&
                    it.config[SemanticsProperties.TestTag].matches(Regex("taxiOrderCard-[1-9][0-9]*"))
            }
        val headingOnCard = hasText(title) and hasAnyAncestor(container)
        compose.waitUntil(30_000) {
            compose.onAllNodes(headingOnCard).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag("taxiConsentCheckResult").fetchSemanticsNodes().isEmpty()
        }
        compose.onNode(headingOnCard).assertIsDisplayed()
        capture(name, title)
        val tag = compose.onNode(container).fetchSemanticsNode().config[SemanticsProperties.TestTag]
        File(qaDir, "$name-order-id").writeText(tag.removePrefix("taxiOrderCard-").toInt().toString())
    }

    private fun waitForTag(tag: String) {
        compose.waitUntil(30_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(tag).assertIsDisplayed()
    }

    private fun click(tag: String) {
        waitForTag(tag)
        compose.onNodeWithTag(tag).assertIsEnabled().performClick()
    }

    private fun rendezvous(step: String) {
        File(qaDir, "$step.ready").createNewFile()
        val deadline = SystemClock.elapsedRealtime() + 90_000
        while (!File(qaDir, "$step.continue").exists() && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(100)
        }
        check(File(qaDir, "$step.continue").exists()) { "Host did not verify $step" }
    }

    private fun capture(name: String, visibleLabel: String? = null) {
        val automation = instrumentation.uiAutomation
        val label = visibleLabel?.let { compose.onNodeWithText(it, useUnmergedTree = true) }
        val deadline = SystemClock.elapsedRealtime() + 15_000
        var evidence = "No screenshot returned"
        var last: Bitmap? = null
        try {
            while (SystemClock.elapsedRealtime() < deadline) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                val foreground = automation.rootInActiveWindow?.packageName?.toString()
                var labelDrawn = label == null
                var labelRect: Rect? = null
                if (foreground == context.packageName && label != null) {
                    // PixelCopy commits an actual frame. Compose virtual text nodes are not searchable
                    // via AccessibilityNodeInfo.findAccessibilityNodeInfosByText on all API levels.
                    val copy = label.captureToImage().asAndroidBitmap()
                    try { labelDrawn = variedSamples(copy, Rect(0, 0, copy.width, copy.height)) >= 8 }
                    finally { copy.recycle() }
                    val bounds = label.fetchSemanticsNode().boundsInWindow
                    val offset = IntArray(2)
                    instrumentation.runOnMainSync {
                        val inWindow = IntArray(2)
                        compose.activity.window.decorView.apply {
                            getLocationOnScreen(offset)
                            getLocationInWindow(inWindow)
                        }
                        offset[0] -= inWindow[0]
                        offset[1] -= inWindow[1]
                    }
                    labelRect = Rect(floor(bounds.left).toInt() + offset[0], floor(bounds.top).toInt() + offset[1],
                        ceil(bounds.right).toInt() + offset[0], ceil(bounds.bottom).toInt() + offset[1])
                }
                last?.recycle()
                val screenshot = automation.takeScreenshot()
                last = if (screenshot?.config == Bitmap.Config.HARDWARE) {
                    try { screenshot.copy(Bitmap.Config.ARGB_8888, false) } finally { screenshot.recycle() }
                } else screenshot
                val bitmap = last
                if (bitmap != null) {
                    val viewport = Rect(bitmap.width / 25, bitmap.height * 12 / 100,
                        bitmap.width * 96 / 100, bitmap.height * 88 / 100)
                    val visible = variedSamples(bitmap, viewport)
                    val labelPixels = labelRect?.let { variedSamples(bitmap, it) }
                    evidence = "foreground=$foreground viewport=$visible labelDrawn=$labelDrawn labelPixels=$labelPixels bounds=$labelRect"
                    if (foreground == context.packageName && labelDrawn && visible >= 8 &&
                        (label == null || (labelPixels ?: 0) >= 8)) {
                        saveBitmap(bitmap, name)
                        return
                    }
                }
                SystemClock.sleep(100)
            }
            last?.let { saveBitmap(it, "$name-failed") }
            fail("No drawn application frame for $name: $evidence")
        } finally { last?.recycle() }
    }

    private fun saveBitmap(bitmap: Bitmap, name: String) {
        File(qaDir, "$name.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    /** Counts samples whose color differs visibly from the dominant color of this rectangle. */
    private fun variedSamples(bitmap: Bitmap, requested: Rect): Int {
        val region = Rect(requested)
        if (!region.intersect(0, 0, bitmap.width, bitmap.height)) return 0
        val samples = ArrayList<Int>(64 * 64)
        for (row in 0 until 64) for (col in 0 until 64) {
            val x = region.left + col * region.width() / 64
            val y = region.top + row * region.height() / 64
            samples += bitmap.getPixel(x, y)
        }
        val background = samples.groupingBy { it }.eachCount().maxBy { it.value }.key
        return samples.count { pixel ->
            kotlin.math.abs(Color.red(pixel) - Color.red(background)) > 12 ||
                kotlin.math.abs(Color.green(pixel) - Color.green(background)) > 12 ||
                kotlin.math.abs(Color.blue(pixel) - Color.blue(background)) > 12
        }
    }
}
