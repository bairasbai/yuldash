package com.yuldash.app

import android.Manifest
import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TripLocationPermissionEffectTest {
    @get:Rule val compose = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val owner = object : LifecycleOwner { override val lifecycle = LifecycleRegistry(this) }
    private fun ride() = Ride("7", "Сибай", "Уфа", "", driver = "Водитель", car = "", price = 500,
        seats = 3, rating = 5.0, verified = true, boosted = false)

    @Test fun initialLiveGrantThenPermissionAndIrreversibleRevokeAreObservedOnResume() {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val gps = DriverGpsGrant()
        val identity = DriverGpsOwner("https://fixture", 1, 7, "process")
        val ticket = gps.begin(identity, 42, 100, 1000)!!
        assertTrue(gps.complete(ticket, identity, 8, 42, Long.MAX_VALUE, 110, 1010))
        val calls = mutableListOf<String>()
        compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                TripLocationPermissionEffect(42, ride(), AppLanguage.Ru, 42, 7, currentSession = { 7 },
                    start = { id, lang, driver -> calls += "start:$id:$lang:$driver" }, stop = { calls += "stop" },
                    driverGrant = { gps.current(identity, 120, 1020) }, onPermission = gps::permission)
            }
        }
        compose.runOnIdle { assertEquals(listOf("stop"), calls) }
        compose.runOnIdle {
            owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
            assertEquals(listOf("stop"), calls) // не добавляем новое правило остановки при уходе в фон
            owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        compose.runOnIdle { assertEquals(listOf("stop", "start:42:Ru:true"), calls) }
        compose.runOnIdle {
            owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
            owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        compose.runOnIdle { assertEquals("stop", calls.last()); assertEquals(3, calls.size) }
        compose.runOnIdle {
            owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
            owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        compose.runOnIdle { assertEquals("stop", calls.last()); assertEquals(1, calls.count { it.startsWith("start:") }) }
    }

    @Test fun grantDoesNotBypassBookingActiveTripRoleOrSessionOwnerGuards() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val id = mutableStateOf<Int?>(null)
        val active = mutableStateOf<Ride?>(ride())
        val driverId = mutableStateOf<Int?>(42)
        val session = mutableStateOf(7L)
        var current = 7L
        val calls = mutableListOf<String>()
        compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                TripLocationPermissionEffect(id.value, active.value, AppLanguage.Ru, driverId.value, session.value,
                    currentSession = { current }, start = { bid, _, driver -> calls += "start:$bid:$driver" }, stop = { calls += "stop" },
                    driverGrant = { null }, onPermission = {})
            }
        }
        compose.runOnIdle { assertEquals(listOf("stop"), calls); id.value = 42; active.value = null }
        compose.runOnIdle { assertEquals("stop", calls.last()); active.value = ride() }
        compose.runOnIdle { assertEquals("OS permission alone cannot grant driver publication", "stop", calls.last()); driverId.value = 99 }
        compose.runOnIdle { assertEquals("start:42:false", calls.last()); current = 8; id.value = 43 }
        compose.runOnIdle { assertEquals("stop", calls.last()); session.value = 8 }
        compose.runOnIdle { assertEquals("start:43:false", calls.last()); id.value = null }
        compose.runOnIdle { assertEquals("stop", calls.last()) }
    }
}
