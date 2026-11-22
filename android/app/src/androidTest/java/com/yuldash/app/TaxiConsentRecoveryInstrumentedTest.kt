package com.yuldash.app

import android.content.Context
import android.graphics.Bitmap
import android.os.Process
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.TAXI_CONSENT_LOCAL_API
import com.yuldash.app.data.TAXI_CONSENT_PENDING
import com.yuldash.app.data.TAXI_CONSENT_PREFS
import com.yuldash.app.data.TaxiConsentDetails
import com.yuldash.app.data.TaxiConsentRoute
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

    @Test fun confirmThenWaitForForceStop() {
        val file = File(qaDir, "session.json")
        val session = JSONObject(file.readText())
        check(file.delete())
        ApiClient.saveToken(session.getString("access"))
        val owner = requireNotNull(TaxiQuoteConsentApi.owner())
        assertEquals(session.getInt("uid"), owner.userId)
        val route = TaxiConsentRoute.normalized(52.591, 58.317, 52.716, 58.664,
            "QA Origin", "QA Destination", "standard", emptyList(), emptyList(), false, 0)
        compose.setContent {
            YuldashTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalAppLanguage provides language, LocalNightRide provides dark) {
                    TaxiConsentControls(rememberTaxiConsentController(owner), route,
                        TaxiConsentDetails(paymentMethod = "cash"), onCreated = { error("Confirmation response must be lost") })
                }
            }
        }
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
        capture("cold-entry")
        rendezvous("cold")
        click("taxiConsentCheckResult")
        if (args.getString("case") == "committed") {
            waitForTag("taxiOrderCard-${requireNotNull(args.getString("orderId")).toInt()}")
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
        }
        capture("resolved")
        assertFalse(TaxiQuoteConsentApi.markers(context).pending(requireNotNull(TaxiQuoteConsentApi.owner()).binding))
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

    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(qaDir, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }
}
