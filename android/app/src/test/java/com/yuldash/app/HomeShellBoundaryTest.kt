package com.yuldash.app

import android.os.Looper
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual HomeShell slot callbacks; no server/VM/private-domain claims. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeShellBoundaryTest {
    @get:Rule val compose=createComposeRule()
    private val mounted=mutableStateOf(true)
    private val callbacks=mutableMapOf<HomeTab,(HomeTab)->Unit>()
    private val accepted=mutableListOf<HomeTab>()
    private var allow=true
    private var synchronized:HomeTab?=null
    @Before fun setup() {NavSignals.activeTaxiTrip.value=0;NavSignals.taxiOrderOnScreen.value=false;NavSignals.taxiTripOnScreen.value=false}
    private fun pump(ms:Long=500) {repeat((ms/100).toInt()) {compose.mainClock.advanceTimeBy(100);Shadows.shadowOf(Looper.getMainLooper()).idle();Snapshot.sendApplyNotifications()};compose.waitForIdle()}
    private fun mount() {
        compose.mainClock.autoAdvance=false
        compose.setContent {if(mounted.value) CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
            HomeShell(initialTab=HomeTab.Rides,onTabChange={synchronized=it},onSelectTab={tab,commit -> if(allow) {accepted+=tab;commit()}}) {tab,select ->
                callbacks[tab]=select
                Text("BODY_$tab",Modifier.fillMaxSize())
            }
        }}
        pump()
    }
    @After fun cleanup() {compose.runOnIdle {mounted.value=false};pump(300)}
    @Test fun currentChildSelectorAndInitialSyncHaveDifferentMeaning() {
        mount();assertEquals(HomeTab.Rides,synchronized);assertTrue(accepted.isEmpty())
        compose.runOnIdle {callbacks.getValue(HomeTab.Rides)(HomeTab.Chat)};pump();assertEquals(listOf(HomeTab.Chat),accepted);assertEquals(HomeTab.Chat,synchronized);compose.onNodeWithText("BODY_Chat").assertExists()
    }
    @Test fun sameFrameOldChildCannotSupersedeNewBottomChoice() {
        mount();val child=callbacks.getValue(HomeTab.Rides);val bottom=compose.onNodeWithText("Профиль").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle {bottom();child(HomeTab.Chat)};pump();assertEquals(listOf(HomeTab.Profile),accepted);assertEquals(HomeTab.Profile,synchronized)
    }
    @Test fun recomposedOutgoingChildCannotSwitchTabs() {
        mount();compose.onNodeWithText("Профиль").performClick();compose.mainClock.advanceTimeBy(16);Snapshot.sendApplyNotifications();compose.waitForIdle()
        compose.onNodeWithText("BODY_Rides").assertExists()
        val outgoing=callbacks.getValue(HomeTab.Rides)
        compose.runOnIdle {outgoing(HomeTab.Chat)};pump();assertEquals(listOf(HomeTab.Profile),accepted);assertEquals(HomeTab.Profile,synchronized)
    }
    @Test fun rejectedParentKeepsInternalTabAndDoesNotNotifyNewTab() {
        mount();allow=false;compose.runOnIdle {callbacks.getValue(HomeTab.Rides)(HomeTab.Chat)};pump();assertEquals(HomeTab.Rides,synchronized);assertTrue(accepted.isEmpty());compose.onNodeWithText("BODY_Rides").assertExists()
    }
    @Test fun disposedSlotCannotCallParentSelection() {
        mount();val old=callbacks.getValue(HomeTab.Rides);compose.runOnIdle {mounted.value=false};pump();compose.runOnIdle {old(HomeTab.Chat)};assertTrue(accepted.isEmpty());assertEquals(HomeTab.Rides,synchronized)
    }
}
