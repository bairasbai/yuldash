package com.yuldash.app

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.yuldash.app.data.*
import com.yuldash.app.ui.theme.YuldashTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TaxiConsentControlsTest {
    @get:Rule val compose = createComposeRule()

    private val owner = TaxiConsentOwner(7, 17, TaxiConsentBinding(ConsentFixture.SCOPE, TAXI_CONSENT_LOCAL_API))
    private var storage: String? = null
    private val markers = TaxiConsentMarkerJournal({ storage }, { storage = it; true })
    private val requests = mutableListOf<String>()
    private var continueClicks = 0

    private fun showColdAttempt(language: AppLanguage, closeResult: TaxiAttemptResult) {
        markers.mark(owner.binding, ConsentFixture.KEY)
        // MOCK — no production transport, coordinates, account or system location is used.
        val port = object : TaxiConsentPort {
            override fun current(owner: TaxiConsentOwner) = owner == this@TaxiConsentControlsTest.owner
            override fun publish(owner: TaxiConsentOwner, action: () -> Unit): Boolean {
                if (!current(owner)) return false
                action()
                return true
            }
            private fun unexpected(operation: String): Nothing {
                requests += operation
                error("Unexpected operation in recovery: $operation")
            }
            override suspend fun quote(route: TaxiConsentRoute, owner: TaxiConsentOwner, admit: () -> Boolean): Result<TaxiConsentQuote> =
                unexpected("quote")
            override suspend fun confirm(body: String, original: TaxiConsentQuote, owner: TaxiConsentOwner, admit: () -> Boolean): Result<TaxiConsentReceipt> =
                unexpected("confirm")
            override suspend fun card(orderId: Int, owner: TaxiConsentOwner, admit: () -> Boolean): Result<InstantOrderDto> =
                unexpected("card")
            override suspend fun result(key: String, close: Boolean, owner: TaxiConsentOwner, admit: () -> Boolean): Result<TaxiAttemptResult> {
                check(admit() && current(owner) && key == ConsentFixture.KEY)
                requests += if (close) "close" else "lookup"
                return Result.success(if (close) closeResult else TaxiAttemptResult.LookupUnknown)
            }
        }
        val controller = TaxiConsentController(owner, port, markers) { TaxiConsentTime(ConsentFixture.wall, 10_000) }
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides language, LocalNightRide provides (language == AppLanguage.Ba)) {
                YuldashTheme(darkTheme = language == AppLanguage.Ba) {
                    TaxiConsentControls(controller, route = null, details = TaxiConsentDetails(),
                        onCreated = { error("Unverified order opened") }, onClosedContinue = { continueClicks++ })
                }
            }
        }
    }

    @Test fun lookupAndDismissNeverReleaseColdAttempt_thenExplicitCloseOffersRoute() {
        showColdAttempt(AppLanguage.Ru, TaxiAttemptResult.Closed)
        compose.onNodeWithTag("taxiConsentCheckResult").assertIsDisplayed()
        compose.onNodeWithTag("taxiConsentTotal").assertDoesNotExist()
        compose.onNodeWithTag("taxiConsentPrimary").assertDoesNotExist()
        compose.onNodeWithTag("taxiConsentClose").performClick()
        compose.onNodeWithTag("taxiConsentCloseCancel").performClick()
        compose.runOnIdle { assertTrue(requests.isEmpty()) }
        compose.onNodeWithTag("taxiConsentCheckResult").performClick()
        compose.onNodeWithTag("taxiConsentNotice").assertTextContains("Сервер пока не подтвердил", substring = true)
        compose.onNodeWithTag("taxiConsentPrimary").assertDoesNotExist()
        compose.onNodeWithTag("taxiConsentClose").performClick()
        compose.onNodeWithTag("taxiConsentCloseConfirm").performClick()
        compose.onNodeWithTag("taxiConsentNotice").assertTextContains("Заказ не был создан", substring = true)
        compose.runOnIdle {
            assertEquals(listOf("lookup", "close"), requests)
            assertFalse(markers.pending(owner.binding))
            assertEquals(0, continueClicks)
        }
        compose.onNodeWithTag("taxiConsentPrimary").performClick()
        compose.runOnIdle { assertEquals(1, continueClicks) }
    }

    @Test fun bashkirRetiredNoticeSurvivesUnknownInDarkTheme() {
        showColdAttempt(AppLanguage.Ba, TaxiAttemptResult.Retired)
        compose.onNodeWithTag("taxiConsentClose").performClick()
        compose.onNodeWithTag("taxiConsentCloseConfirm").performClick()
        compose.onNodeWithTag("taxiConsentNotice").assertTextContains("Заказ булдырылған", substring = true)
        compose.onNodeWithTag("taxiConsentCheckResult").performClick()
        compose.onNodeWithTag("taxiConsentNotice").assertTextContains("Заказ булдырылған", substring = true)
        compose.onNodeWithTag("taxiConsentPrimary").assertDoesNotExist()
        compose.onNodeWithTag("taxiConsentTotal").assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(markers.pending(owner.binding))
            assertEquals(listOf("close", "lookup"), requests)
        }
    }
}
