package com.yuldash.app

import android.app.Application
import androidx.compose.runtime.key
import androidx.compose.ui.test.onAllNodesWithText
import com.yuldash.app.data.ApiClient
import com.yuldash.app.ui.theme.YuldashTheme
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RoadsideHelpOwnerBoundaryTest : ShareHelpBoundaryHarness() {
    private fun mountHelp(kind: String = "instant") {
        compose.mainClock.autoAdvance = false
        compose.setContent { if (mounted.value) key(slot.value) { YuldashTheme {
            val id = target.value
            RoadsideHelpAction(key = id) { lat, lng, generation -> when (kind) {
                "booking" -> ApiClient.roadsideHelp(id, lat, lng, "", expectedGeneration = generation)
                "parcel" -> ApiClient.parcelRoadsideHelp(id, lat, lng, expectedGeneration = generation)
                else -> ApiClient.instantRoadsideHelp(id, lat, lng, expectedGeneration = generation)
            } }
        } } }; pump()
    }
    private fun confirm(): () -> Boolean { click("Застрял на трассе"); return text("Позвать помощь") }
    private fun current(kind: String, path: String) {
        mountHelp(kind); val a = confirm(); compose.runOnIdle { dispatch(a) }
        await { count("POST", "/stuck") == 1 }; await { ShadowToast.getTextOfLatestToast() == null && compose.onAllNodesWithText("Помощь вызвана: близкие и поддержка получили твои координаты.").fetchSemanticsNodes().isNotEmpty() }
        val q = records.single { it[0] == "POST" }; assertEquals(path, q[1]); assertEquals("Bearer $tokenA", q[2])
        val body = JSONObject(q[3]!!); assertEquals(54.735, body.getDouble("lat"), 0.00001); assertEquals(55.958, body.getDouble("lng"), 0.00001)
    }
    @Test fun currentInstantAdapterHasOwnedPayload() { current("instant", "/instant/orders/91/stuck") }
    @Test fun currentBookingAdapterHasOwnedPayload() { current("booking", "/bookings/91/stuck") }
    @Test fun currentParcelAdapterHasOwnedPayload() { current("parcel", "/parcels/91/stuck") }
    @Test fun oldConfirmCannotUseNextOwner() { mountHelp(); changeOwner(confirm()); assertEquals(0, count("POST", "/stuck")) }
    @Test fun oldConfirmCannotUseGuest() { mountHelp(); changeOwner(confirm(), true); assertEquals(0, count("POST", "/stuck")) }
    @Test fun oldConfirmCannotUseReplacementTarget() { mountHelp(); val a = confirm(); compose.runOnIdle { target.value = 92 }; pump(); compose.runOnIdle { dispatch(a) }; pump(); assertEquals(0, count("POST", "/stuck")) }
    @Test fun disposedConfirmCannotUseRemountedHelp() { mountHelp(); val a = confirm(); compose.runOnIdle { slot.value++ }; pump(); compose.runOnIdle { dispatch(a) }; pump(); assertEquals(0, count("POST", "/stuck")) }
    @Test fun duplicateConfirmWhileHeldSendsOnce() { holdPath = "/instant/orders/91/stuck"; mountHelp(); val a = confirm(); compose.runOnIdle { dispatch(a); dispatch(a) }; await { started.count == 0L }; pump(); assertEquals(1, count("POST", "/stuck")); finishHeld() }
    @Test fun cancelledConfirmCannotReplayBeforeFrame() { mountHelp(); val a = confirm(); val cancel = text("Отмена"); compose.runOnIdle { dispatch(cancel); dispatch(a) }; pump(); assertEquals(0, count("POST", "/stuck")) }
    @Test fun retainedConfirmCannotReplayAfterSuccess() { mountHelp(); val a = confirm(); compose.runOnIdle { dispatch(a) }; await { count("POST", "/stuck") == 1 }; pump(1500); compose.runOnIdle { dispatch(a) }; pump(); assertEquals(1, count("POST", "/stuck")) }
    @Test fun failedSignalCanRetryCurrentDialog() { failMutation = true; mountHelp(); val a = confirm(); compose.runOnIdle { dispatch(a) }; await { ShadowToast.getTextOfLatestToast() != null }; failMutation = false; val retry = text("Позвать помощь"); compose.runOnIdle { dispatch(retry) }; await { count("POST", "/stuck") == 2 }; pump(1000); assertEquals(2, count("POST", "/stuck")) }
    @Test fun heldOldTargetCannotMarkNewTargetSent() { holdPath = "/instant/orders/91/stuck"; mountHelp(); val a = confirm(); compose.runOnIdle { dispatch(a) }; await { started.count == 0L }; compose.runOnIdle { target.value = 92 }; pump(); finishHeld(); assertTrue(compose.onAllNodesWithText("Застрял на трассе").fetchSemanticsNodes().isNotEmpty()) }
    @Test fun heldFailureCannotToastForNextOwner() { failMutation = true; holdPath = "/instant/orders/91/stuck"; mountHelp(); val a = confirm(); compose.runOnIdle { dispatch(a) }; await { started.count == 0L }; compose.runOnIdle { ApiClient.saveToken(tokenB) }; finishHeld(); assertNull(ShadowToast.getTextOfLatestToast()) }
    @Test fun coordinatesBelongToTapBeforeCoroutineStarts() { mountHelp(); val a = confirm(); compose.runOnIdle { dispatch(a); LocationPrefs.lastLat = 1.0; LocationPrefs.lastLng = 2.0 }; await { count("POST", "/stuck") == 1 }; val body = JSONObject(records.single { it[0] == "POST" }[3]!!); assertEquals(54.735, body.getDouble("lat"), 0.00001) }
    @Test fun currentSignalCanOmitMissingCoordinates() { LocationPrefs.lastLat = null; LocationPrefs.lastLng = null; mountHelp(); val a = confirm(); compose.runOnIdle { dispatch(a) }; await { count("POST", "/stuck") == 1 }; val body = JSONObject(records.single { it[0] == "POST" }[3]!!); assertFalse(body.has("lat")); assertFalse(body.has("lng")) }
    @Test fun actualTaxiSafetyCanSendCurrentSignal() { mountTaxiSafety(); val a = confirm(); compose.runOnIdle { dispatch(a) }; await { count("POST", "/stuck") == 1 }; assertEquals("/instant/orders/91/stuck", records.single { it[0] == "POST" }[1]) }
    @Test fun actualTaxiHelpRejectsParentRetirementBeforeFrame() { mountTaxiSafety(); val a = confirm(); compose.runOnIdle { parentCurrent.value = false; dispatch(a) }; pump(); assertEquals(0, count("POST", "/stuck")) }
    @Test fun oldConfirmationCannotSendFromReopenedDialog() { mountHelp(); val old = confirm(); click("Отмена"); pump(); val current = confirm(); compose.runOnIdle { dispatch(old) }; pump(); assertEquals(0, count("POST", "/stuck")); compose.runOnIdle { dispatch(current) }; await { count("POST", "/stuck") == 1 } }
    @Test fun oldCancelCannotCloseReopenedConfirmation() { mountHelp(); confirm(); val old = text("Отмена"); compose.runOnIdle { dispatch(old) }; pump(); val current = confirm(); compose.runOnIdle { dispatch(old) }; pump(); assertTrue(compose.onAllNodesWithText("Позвать помощь").fetchSemanticsNodes().isNotEmpty()); compose.runOnIdle { dispatch(current) }; await { count("POST", "/stuck") == 1 } }
}
