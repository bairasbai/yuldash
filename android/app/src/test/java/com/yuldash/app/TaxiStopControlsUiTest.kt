package com.yuldash.app

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.yuldash.app.data.*
import com.yuldash.app.ui.theme.YuldashTheme
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TaxiStopControlsUiTest {
    @get:Rule val compose = createComposeRule()
    private val first = TaxiStop(52.5, 58.1, "Synthetic", waypointId = "first")
    private val second = first.copy(waypointId = "second")
    private val future = TaxiStop(52.6, 58.2, "Synthetic future", waypointId = "future")
    private val idle = TaxiStopControl.V1(true, "route", null, null)
    private val active = TaxiStopControl.V1(false, "route", "wait", TaxiActiveWait.Planned("wait", "second"))

    @After fun cleanup() = ApiClient.resetForTest()

    @Test fun driverChoosesSecondIdenticalStop_thenUncertainAckReads_thenExactResumeWhileOff() {
        TripPassAuthorityFixture.installSession("http://127.0.0.1:9", 7)
        val generation = ApiClient.queueSessionGeneration()
        val order = ApiClient.parseTaxiConsentOrder(ConsentFixture.orderJson("onboard").put("role", "driver"))
            .copy(stops = listOf(first, second, future), stopControl = idle)
        val current = mutableStateOf(order)
        val operations = mutableListOf<Any>()
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba, LocalNightRide provides true) {
                YuldashTheme(darkTheme = true) {
                    TaxiDriverStopControls(current.value, generation, { current.value = it }, send = { id, command, gen ->
                        check(id == order.id && gen == generation)
                        operations += command
                        if (command is TaxiStopCommand.Planned) Result.failure(IOException("Synthetic lost response"))
                        else Result.success(JSONObject().put("ok", true).put("standing", false).put("order",
                            ConsentFixture.orderJson("onboard").put("role", "driver").put("standing", false)))
                    }, read = { id, gen ->
                        check(id == order.id && gen == generation)
                        operations += "read"
                        when (operations.size) {
                            1 -> Result.success(order)
                            3, 4 -> Result.success(order.copy(standing = true, stopControl = active))
                            else -> Result.success(order.copy(stops = emptyList(), stopControl = TaxiStopControl.Legacy))
                        }
                    })
                }
            }
        }
        compose.onNodeWithTag("taxiStopAction").performClick()
        compose.onNodeWithText("Ҡайҙа туҡтанығыҙ?").assertIsDisplayed()
        compose.onNodeWithTag("taxiPlannedStop0").assertIsDisplayed()
        compose.onNodeWithTag("taxiPlannedStop1").performClick()
        compose.runOnIdle {
            assertEquals(listOf("read", TaxiStopCommand.Planned(null, "route", "second"), "read"), operations)
        }
        compose.onNodeWithText("Киттек").performClick()
        compose.runOnIdle {
            assertEquals(listOf("read", TaxiStopCommand.Planned(null, "route", "second"), "read",
                "read", TaxiStopCommand.Resume("wait"), "read"), operations)
        }
    }

    @Test fun typedConflictAndFailedRefreshNeverFallBackToLegacyOrRepeatTheMutation() {
        TripPassAuthorityFixture.installSession("http://127.0.0.1:9", 7)
        val generation = ApiClient.queueSessionGeneration()
        val order = ApiClient.parseTaxiConsentOrder(ConsentFixture.orderJson("onboard").put("role", "driver"))
            .copy(stopControl = idle)
        val operations = mutableListOf<Any>()
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                YuldashTheme {
                    TaxiDriverStopControls(order, generation, {}, send = { _, command, _ ->
                        operations += command
                        Result.failure(ApiException(409, "Synthetic conflict", "taxi_wait_stale"))
                    }, read = { _, _ ->
                        operations += "read"
                        if (operations.size == 1) Result.success(order)
                        else Result.failure(IOException("Synthetic unavailable"))
                    })
                }
            }
        }
        compose.onNodeWithTag("taxiStopAction").performClick()
        compose.onNodeWithTag("taxiUnplannedStop").performClick()
        compose.onNodeWithText("Состояние остановки изменилось. Проверь его перед новым действием.").assertIsDisplayed()
        compose.onNodeWithTag("taxiStopAction").assertDoesNotExist()
        compose.onNodeWithText("Обновить остановки").performClick()
        compose.runOnIdle { assertEquals(listOf("read", TaxiStopCommand.Unplanned(null), "read", "read"), operations) }
    }

    @Test fun lostStartWithSameBaselineRequiresSeparateReadAndExplicitSameCommand() {
        TripPassAuthorityFixture.installSession("http://127.0.0.1:9", 7)
        val generation = ApiClient.queueSessionGeneration()
        val order = ApiClient.parseTaxiConsentOrder(ConsentFixture.orderJson("onboard").put("role", "driver"))
            .copy(stops = listOf(first, second), stopControl = idle.copy(waitRevision = "idleBefore"))
        val current = mutableStateOf(order)
        val operations = mutableListOf<Any>()
        val exact = TaxiStopCommand.Planned("idleBefore", "route", "second")
        var reads = 0
        var sends = 0
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                YuldashTheme {
                    TaxiDriverStopControls(current.value, generation, { current.value = it },
                        send = { id, command, gen ->
                            check(id == order.id && gen == generation && command == exact)
                            operations += command
                            sends++
                            Result.failure(IOException("Synthetic transport loss"))
                        }, read = { id, gen ->
                            check(id == order.id && gen == generation)
                            operations += "read"
                            reads++
                            Result.success(if (reads <= 4) order else order.copy(standing = true, stopControl = active))
                        })
                }
            }
        }
        compose.onNodeWithTag("taxiStopAction").performClick()
        compose.onNodeWithTag("taxiPlannedStop1").performClick()
        compose.onNodeWithTag("taxiRetrySameWaitStart").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("read", exact, "read"), operations) }
        compose.onNodeWithText("Обновить остановки").performClick()
        compose.onNodeWithTag("taxiRetrySameWaitStart").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, sends) }
        compose.onNodeWithTag("taxiRetrySameWaitStart").performClick()
        compose.runOnIdle { assertEquals(listOf("read", exact, "read", "read", "read", exact, "read"), operations) }
        compose.onNodeWithTag("taxiRetrySameWaitStart").assertDoesNotExist()
        compose.onNodeWithText("Поехали").assertIsDisplayed()
    }

    @Test fun armedRetryDoesNotSendAfterItsLaunchReadLosesTypedCapability() {
        TripPassAuthorityFixture.installSession("http://127.0.0.1:9", 7)
        val generation = ApiClient.queueSessionGeneration()
        val order = ApiClient.parseTaxiConsentOrder(ConsentFixture.orderJson("onboard").put("role", "driver"))
            .copy(stopControl = idle)
        var reads = 0
        val commands = mutableListOf<TaxiStopCommand>()
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                YuldashTheme {
                    TaxiDriverStopControls(order, generation, {}, send = { _, command, _ ->
                        commands += command
                        Result.failure(IOException("Synthetic transport loss"))
                    }, read = { _, _ ->
                        reads++
                        Result.success(if (reads < 4) order else order.copy(stopControl = TaxiStopControl.Legacy))
                    })
                }
            }
        }
        compose.onNodeWithTag("taxiStopAction").performClick()
        compose.onNodeWithTag("taxiUnplannedStop").performClick()
        compose.onNodeWithText("Обновить остановки").performClick()
        compose.onNodeWithTag("taxiRetrySameWaitStart").performClick()
        compose.onNodeWithTag("taxiRetrySameWaitStart").assertDoesNotExist()
        compose.onNodeWithText("Обновить остановки").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(4, reads)
            assertEquals(listOf(TaxiStopCommand.Unplanned(null)), commands)
        }
    }

    @Test fun lostPrepareCanBeRepeatedManuallyButDoesNotStartWaiting() {
        TripPassAuthorityFixture.installSession("http://127.0.0.1:9", 7)
        val generation = ApiClient.queueSessionGeneration()
        val assignedPoint = "12b49230-26dc-48a4-b1c3-b36efc57d812"
        val assignedRoute = "be8bbb09-f8aa-4383-b673-f7003cf6b187"
        val raw = ConsentFixture.orderJson("onboard").put("role", "driver").put("standing", false)
            .put("stops", JSONArray().put(JSONObject().put("lat", first.lat).put("lng", first.lng)
                .put("text", first.text).put("done", false).put("waypoint_id", assignedPoint)))
            .put("stop_control", JSONObject().put("admission_enabled", true).put("route_revision", assignedRoute)
                .put("wait_revision", JSONObject.NULL).put("active_wait", JSONObject.NULL))
        val prepared = ApiClient.parseTaxiConsentOrder(raw)
        val before = prepared.copy(stops = listOf(first.copy(waypointId = null)),
            stopControl = idle.copy(routeRevision = null))
        val current = mutableStateOf(before)
        val operations = mutableListOf<Any>()
        var reads = 0
        var prepares = 0
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                YuldashTheme {
                    TaxiDriverStopControls(current.value, generation, { current.value = it }, send = { id, cmd, owner ->
                        check(id == before.id && owner == generation)
                        operations += cmd
                        when (cmd) {
                            TaxiStopCommand.Prepare -> {
                                prepares++
                                if (prepares == 1) Result.failure(IOException("Synthetic pre-send failure"))
                                else Result.success(JSONObject().put("ok", true).put("standing", false).put("order", raw))
                            }
                            TaxiStopCommand.Planned(null, assignedRoute, assignedPoint) -> Result.failure(IOException("Synthetic loss"))
                            else -> error("Unexpected command")
                        }
                    }, read = { id, owner ->
                        check(id == before.id && owner == generation)
                        operations += "read"
                        reads++
                        Result.success(if (reads <= 4) before else prepared)
                    })
                }
            }
        }
        compose.onNodeWithTag("taxiStopAction").performClick()
        compose.onNodeWithTag("taxiRetrySamePrepare").assertDoesNotExist()
        compose.onNodeWithTag("taxiPlannedStop0").assertDoesNotExist()
        compose.onNodeWithText("Обновить остановки").performClick()
        compose.onNodeWithTag("taxiRetrySamePrepare").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf("read", TaxiStopCommand.Prepare, "read", "read"), operations) }
        compose.onNodeWithTag("taxiRetrySamePrepare").performClick()
        compose.onNodeWithTag("taxiPlannedStop0").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf("read", TaxiStopCommand.Prepare, "read", "read", "read", TaxiStopCommand.Prepare, "read"), operations)
        }
        compose.onNodeWithTag("taxiPlannedStop0").performClick()
        compose.runOnIdle {
            assertEquals(TaxiStopCommand.Planned(null, assignedRoute, assignedPoint), operations[8])
        }
    }

    @Test fun lostOffResumeCanOnlyRetrySameExplicitWaitAfterTwoManualActions() {
        exerciseOffResumeRetry(replaceBeforeRetry = false)
    }

    @Test fun replacementWaitAtRetryLaunchNeverInheritsResume() {
        exerciseOffResumeRetry(replaceBeforeRetry = true)
    }

    private fun exerciseOffResumeRetry(replaceBeforeRetry: Boolean) {
        TripPassAuthorityFixture.installSession("http://127.0.0.1:9", 7)
        val generation = ApiClient.queueSessionGeneration()
        val rawIdle = ConsentFixture.orderJson("onboard").put("role", "driver").put("standing", false)
        val drained = ApiClient.parseTaxiConsentOrder(rawIdle)
        val order = drained.copy(stops = listOf(first, second), standing = true, stopControl = active)
        val replacement = order.copy(stopControl = active.copy(waitRevision = "rivalWait",
            active = TaxiActiveWait.Planned("rivalWait", "second")))
        val current = mutableStateOf(order)
        val operations = mutableListOf<Any>()
        val exact = TaxiStopCommand.Resume("wait")
        var reads = 0
        var sends = 0
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba, LocalNightRide provides true) {
                YuldashTheme(darkTheme = true) {
                    TaxiDriverStopControls(current.value, generation, { current.value = it }, send = { id, command, gen ->
                        check(id == order.id && gen == generation && command == exact)
                        operations += command
                        sends++
                        if (sends == 1) Result.failure(IOException("Synthetic transport loss"))
                        else Result.success(JSONObject().put("ok", true).put("standing", false).put("order", rawIdle))
                    }, read = { id, gen ->
                        check(id == order.id && gen == generation)
                        operations += "read"
                        reads++
                        Result.success(when {
                            reads == 4 && replaceBeforeRetry -> replacement
                            reads <= 4 -> order
                            else -> drained
                        })
                    })
                }
            }
        }
        compose.onNodeWithText("Киттек").performClick()
        compose.onNodeWithTag("taxiRetrySameWaitResume").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("read", exact, "read"), operations) }
        compose.onNodeWithText("Туҡталыштарҙы яңыртыу").performClick()
        compose.onNodeWithTag("taxiRetrySameWaitResume").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, sends) }
        compose.onNodeWithTag("taxiRetrySameWaitResume").performClick()
        compose.onNodeWithTag("taxiRetrySameWaitResume").assertDoesNotExist()
        if (replaceBeforeRetry) {
            compose.onNodeWithText("Туҡталыштарҙы яңыртыу").assertIsDisplayed()
            compose.runOnIdle { assertEquals(listOf("read", exact, "read", "read", "read"), operations) }
        } else {
            compose.onNodeWithText("Киттек").assertDoesNotExist()
            compose.runOnIdle {
                assertEquals(listOf("read", exact, "read", "read", "read", exact, "read"), operations)
                assertEquals(drained, current.value)
            }
        }
    }

    @Test fun legacyLaunchGetForPreviouslyTypedActionDoesNotSendAnyMutation() {
        TripPassAuthorityFixture.installSession("http://127.0.0.1:9", 7)
        val generation = ApiClient.queueSessionGeneration()
        val order = ApiClient.parseTaxiConsentOrder(ConsentFixture.orderJson("onboard").put("role", "driver"))
            .copy(stopControl = idle)
        var reads = 0
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                YuldashTheme {
                    TaxiDriverStopControls(order, generation, {}, send = { _, _, _ ->
                        error("Stale launch must not send")
                    }, read = { _, _ ->
                        reads++
                        Result.success(order.copy(stopControl = TaxiStopControl.Legacy))
                    })
                }
            }
        }
        compose.onNodeWithTag("taxiStopAction").performClick()
        compose.onNodeWithTag("taxiUnplannedStop").performClick()
        compose.onNodeWithText("Обновить остановки").performClick()
        compose.onNodeWithTag("taxiStopAction").assertDoesNotExist()
        compose.runOnIdle { assertEquals(2, reads) }
    }

    @Test fun passengerCanRemoveOrReplaceSecondLiteralDuplicateOnlyWhenV3Advertised() {
        val firstActive = TaxiActiveWait.Planned("wait", "first")
        val order = ApiClient.parseTaxiConsentOrder(ConsentFixture.orderJson("onboard"))
            .copy(stops = listOf(first, second, future), standing = true,
                stopControl = active.copy(active = firstActive, passengerEdit = true))
        val changes = mutableListOf<Pair<String, Int>>()
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                YuldashTheme {
                    TaxiPassengerStopRows(order, "Туҡталышты алыу",
                        onReplaceStop = { changes += "replace" to it }, onRemoveStop = { changes += "remove" to it })
                }
            }
        }
        compose.onNodeWithText("Йөрөтөүсе көтә").assertIsDisplayed()
        compose.onNodeWithTag("taxiRemoveStop0").assertDoesNotExist()
        compose.onNodeWithTag("taxiReplaceStop0").assertDoesNotExist()
        compose.onNodeWithTag("taxiRemoveStop1").performClick()
        compose.onNodeWithTag("taxiReplaceStop1").performClick()
        compose.runOnIdle { assertEquals(listOf("remove" to 1, "replace" to 1), changes) }
    }

    @Test fun passengerSeesOnlyChosenRowLockedAndCanTapOrdinaryFutureEdit() {
        val order = ApiClient.parseTaxiConsentOrder(ConsentFixture.orderJson("onboard"))
            .copy(stops = listOf(first.copy(done = true), second, future), standing = true, stopControl = active)
        val removes = mutableListOf<Int>()
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                YuldashTheme { TaxiPassengerStopRows(order, "Убрать остановку") { removes += it } }
            }
        }
        compose.onNodeWithText("Водитель ждёт").assertIsDisplayed()
        compose.onNodeWithTag("taxiRemoveStop0").assertDoesNotExist()
        compose.onNodeWithTag("taxiRemoveStop1").assertDoesNotExist()
        compose.onNodeWithTag("taxiReplaceStop2").assertDoesNotExist()
        compose.onNodeWithTag("taxiRemoveStop2").performClick()
        compose.runOnIdle { assertEquals(listOf(2), removes) }
    }
}
