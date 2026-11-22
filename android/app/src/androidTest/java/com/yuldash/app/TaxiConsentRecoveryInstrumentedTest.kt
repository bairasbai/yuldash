package com.yuldash.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Process
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
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

    private fun openPassengerRoute() {
        val file = File(qaDir, "session.json")
        val session = JSONObject(file.readText())
        check(file.delete())
        ApiClient.saveToken(session.getString("access"))
        val owner = requireNotNull(TaxiQuoteConsentApi.owner())
        assertEquals(session.getInt("uid"), owner.userId)
        // Supply the last known pickup exactly as the passenger picker reads it. Neither a live
        // location provider nor an external map/geocoder should participate in the fixture.
        for (permission in listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) {
            check(context.checkSelfPermission(permission) == PackageManager.PERMISSION_DENIED)
        }
        compose.runOnUiThread {
            LocationPrefs.lastLat = 52.591
            LocationPrefs.lastLng = 58.317
        }
        compose.setContent {
            YuldashTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalAppLanguage provides language, LocalNightRide provides dark) {
                    InstantOrderScreen(onBack = {}, onLoginRequired = { error("Session lost") }, renderNativeMap = false)
                }
            }
        }
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

        captureSearchCard("roundtrip-resolved")
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
        File(qaDir, "seed-pid").writeText(Process.myPid().toString())
        capture("interrupted")
        File(qaDir, "force-stop.ready").createNewFile()
        // The host deliberately kills this active instrumentation process after checking SQL.
        SystemClock.sleep(120_000)
        fail("Host did not force-stop the waiting process")
    }

    @Test fun coldEntryChecksOrClosesOriginalAttempt() {
        assertNotEquals(File(qaDir, "seed-pid").readText(), Process.myPid().toString())
        checkNotNull(TaxiQuoteConsentApi.owner())
        compose.setContent {
            YuldashTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalAppLanguage provides language, LocalNightRide provides dark) {
                    InstantOrderScreen(onBack = {}, onLoginRequired = { error("Session lost") }, renderNativeMap = false)
                }
            }
        }
        waitForTag("taxiConsentCheckResult")
        compose.onNodeWithTag("taxiConsentConfirm").assertDoesNotExist()
        rendezvous("cold")
        val checkLabel = if (language == AppLanguage.Ba) "Заказ һөҙөмтәһен тикшереү" else "Проверить результат заказа"
        capture("cold-entry", checkLabel)
        click("taxiConsentCheckResult")
        if (args.getString("case") == "committed") {
            captureSearchCard("resolved", requireNotNull(args.getString("orderId")).toInt())
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

    private fun captureSearchCard(name: String, id: Int? = null) {
        val container = if (id != null) hasTestTag("taxiOrderCard-$id") else
            SemanticsMatcher("published passenger order card") {
                SemanticsProperties.TestTag in it.config &&
                    it.config[SemanticsProperties.TestTag].matches(Regex("taxiOrderCard-[1-9][0-9]*"))
            }
        val title = if (language == AppLanguage.Ba) "Машина эҙләйбеҙ" else "Ищем машину"
        val headingOnCard = hasText(title) and hasAnyAncestor(container)
        compose.waitUntil(30_000) {
            compose.onAllNodes(headingOnCard).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag("taxiConsentCheckResult").fetchSemanticsNodes().isEmpty()
        }
        compose.onNode(headingOnCard).assertIsDisplayed()
        capture(name, title)
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
        compose.waitForIdle()
        val automation = instrumentation.uiAutomation
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val root = automation.rootInActiveWindow
            if (root?.packageName?.toString() == context.packageName && (visibleLabel == null ||
                    root.findAccessibilityNodeInfosByText(visibleLabel).any { it.isVisibleToUser })) {
                instrumentation.waitForIdleSync()
                val screenshot = automation.takeScreenshot()
                val bitmap = if (screenshot?.config == Bitmap.Config.HARDWARE) {
                    try { screenshot.copy(Bitmap.Config.ARGB_8888, false) } finally { screenshot.recycle() }
                } else screenshot
                if (bitmap != null) try {
                    if (hasAppContent(bitmap)) {
                        File(qaDir, "$name.png").outputStream().use {
                            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                        }
                        return
                    }
                } finally { bitmap.recycle() }
            }
            SystemClock.sleep(100)
        }
        fail("No drawn application frame for $name with the expected foreground content")
    }

    /** System bars alone must never turn a solid transition frame into an accepted screenshot. */
    private fun hasAppContent(bitmap: Bitmap): Boolean {
        val samples = ArrayList<Int>(64 * 64)
        for (row in 0 until 64) for (col in 0 until 64) {
            val x = bitmap.width * (4 + col * 92 / 64) / 100
            val y = bitmap.height * (12 + row * 76 / 64) / 100
            samples += bitmap.getPixel(x, y)
        }
        val background = samples.groupingBy { it }.eachCount().maxBy { it.value }.key
        return samples.count { pixel ->
            kotlin.math.abs(Color.red(pixel) - Color.red(background)) > 12 ||
                kotlin.math.abs(Color.green(pixel) - Color.green(background)) > 12 ||
                kotlin.math.abs(Color.blue(pixel) - Color.blue(background)) > 12
        } >= 8
    }
}
