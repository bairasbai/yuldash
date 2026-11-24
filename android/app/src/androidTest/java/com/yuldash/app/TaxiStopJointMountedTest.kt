package com.yuldash.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.InstantOrderDto
import com.yuldash.app.data.TAXI_CONSENT_LOCAL_API
import com.yuldash.app.data.TaxiActiveWait
import com.yuldash.app.data.TaxiStopControl
import com.yuldash.app.ui.theme.YuldashTheme
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Mount real screens in one disposable guest; only the authorized host fixture may seed roles. */
@RunWith(AndroidJUnit4::class)
class TaxiStopJointMountedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val selected = "00000000-0000-4000-a000-000000000002"
    private val editedRoute = "00000000-0000-4000-a000-000000000005"
    private val wait = "00000000-0000-4000-a000-000000000006"
    private val orderId = 77

    @Before fun loopbackOnly() {
        assumeTrue(args.getString("tf09JointFixture") == "true")
        check(instrumentation is StorageAuditRunner && BuildConfig.DEBUG && BuildConfig.YULDASH_NATIVE_QA_PINNED_API)
        check(ApiClient.apiBase() == TAXI_CONSENT_LOCAL_API)
        ApiClient.init(context)
        check(ApiClient.apiBase() == TAXI_CONSENT_LOCAL_API)
        for (permission in listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) {
            check(context.checkSelfPermission(permission) == PackageManager.PERMISSION_DENIED)
        }
    }

    private fun seed(role: String) {
        check(args.getString("role") == role)
        val path = File(context.filesDir, "taxi-stop-joint/session.json")
        val session = JSONObject(path.readText())
        check(path.delete())
        val uid = if (role == "driver") 71 else 72
        check(session.getInt("uid") == uid)
        ApiClient.saveToken(session.getString("access"))
        check(ApiClient.myUserId() == uid)
    }

    private fun read(role: String): InstantOrderDto = runBlocking {
        ApiClient.getInstantOrder(orderId, expectedGeneration = ApiClient.queueSessionGeneration()).getOrThrow().also {
            check(it.id == orderId && it.role == role && it.status == "onboard")
        }
    }

    private fun confirmActive(order: InstantOrderDto) {
        val control = order.stopControl as TaxiStopControl.V1
        assertTrue(order.standing)
        assertFalse(control.admission)
        assertTrue(control.passengerEdit)
        assertEquals(TaxiActiveWait.Planned(wait, selected), control.active)
    }

    private fun mountDriver(order: InstantOrderDto) {
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru, LocalNightRide provides false) {
                YuldashTheme(darkTheme = false) {
                    InstantDriverTripScreen(orderId, onBack = {}, onFinished = {}, initialOrder = order,
                        observeRemote = false, mapContent = { Box(it) })
                }
            }
        }
    }

    private fun waitText(text: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun driverSelectsSecondLiteralDuplicate() {
        seed("driver")
        val before = read("driver")
        assertTrue((before.stopControl as TaxiStopControl.V1).admission)
        assertEquals(before.stops[0].copy(waypointId = selected), before.stops[1])
        mountDriver(before)
        compose.onNodeWithTag("taxiStopAction").performScrollTo().performClick()
        compose.onNodeWithTag("taxiPlannedStop0").assertIsDisplayed()
        compose.onNodeWithTag("taxiPlannedStop1").performClick()
        waitText("Поехали")
        confirmActive(read("driver"))
    }

    @Test fun passengerEditsOnlyOtherDuplicateDuringOffDrain() {
        seed("passenger")
        val before = read("passenger")
        confirmActive(before)
        val shown = mutableStateOf(before)
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba, LocalNightRide provides true) {
                YuldashTheme(darkTheme = true) {
                    TaxiTripScreen(order = shown.value, onCancel = {}, onOrderUpdated = { shown.value = it },
                        enableLiveTracking = false, mapContent = { Box(it) },
                        ownerGeneration = ApiClient.queueSessionGeneration(),
                        readShares = { _, _ -> Result.success(emptyList()) })
                }
            }
        }
        compose.onNodeWithTag("taxiSheetDragArea").performTouchInput { swipeUp(durationMillis = 600) }
        compose.onNodeWithTag("taxiReplaceStop1").assertDoesNotExist()
        compose.onNodeWithTag("taxiRemoveStop1").assertDoesNotExist()
        compose.onNodeWithTag("taxiReplaceStop0").performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).performTextInput("joint-fixture-replacement")
        waitText("Synthetic replacement")
        compose.onNodeWithText("Synthetic replacement").performClick()
        compose.waitUntil(15_000) { shown.value.stops.firstOrNull()?.text == "Synthetic replacement" }
        val after = read("passenger")
        confirmActive(after)
        assertEquals(editedRoute, (after.stopControl as TaxiStopControl.V1).routeRevision)
        assertEquals(before.stops[1], after.stops[1])
    }

    @Test fun driverDrainsSameWaitWithNewRouteRevision() {
        seed("driver")
        val before = read("driver")
        confirmActive(before)
        assertEquals(editedRoute, (before.stopControl as TaxiStopControl.V1).routeRevision)
        mountDriver(before)
        compose.onNodeWithText("Поехали").performScrollTo().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Поехали").fetchSemanticsNodes().isEmpty() }
        val after = read("driver")
        assertFalse(after.standing)
        assertEquals(TaxiStopControl.Legacy, after.stopControl)
    }

    @Test fun passengerReadsIdleAfterDriverDrains() {
        seed("passenger")
        val after = read("passenger")
        assertFalse(after.standing)
        assertEquals(TaxiStopControl.Legacy, after.stopControl)
        assertTrue(after.stops.none { it.waypointId != null })
    }
}
