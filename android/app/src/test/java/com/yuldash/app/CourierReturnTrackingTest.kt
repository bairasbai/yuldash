package com.yuldash.app

import android.Manifest
import android.app.Application
import android.content.Intent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ParcelDto
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CourierReturnTrackingTest {
    @get:Rule val compose = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Before fun prepare() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        while (shadowOf(app).nextStartedService != null) { }
        while (shadowOf(app).nextStoppedService != null) { }
    }

    private fun parcel(id: Int, status: String) = ParcelDto(
        id = id, senderId = 1, courierId = 2,
        fromCity = "Уфа", toCity = "Уфа",
        fromLat = null, fromLng = null, toLat = null, toLng = null,
        size = "small", description = "", receiverName = "", receiverPhone = "",
        feeKop = 10000, status = status, confirmCode = "",
        createdAt = "2026-09-12T00:00:00Z", acceptedAt = null, deliveredAt = null,
        courier = null,
    )

    private fun assertCourierIntent(intent: Intent?) {
        assertNotNull("Expected an actual Android service intent", intent)
        assertEquals(CourierLocationService::class.java.name, intent!!.component?.className)
    }

    @Test fun onlyActiveOutboundParcelsStartTrackingAndReturnStatesAreExcluded() {
        compose.setContent {
            CourierLiveLocationLink(listOf(parcel(41, "accepted"), parcel(42, "in_transit"),
                parcel(43, "returning"), parcel(44, "returned")))
        }
        compose.runOnIdle {
            val intent = shadowOf(app).nextStartedService
            assertCourierIntent(intent)
            // docs/decisions.md: return journeys deliberately do not expose live courier GPS.
            assertArrayEquals("Return states must not expose live GPS", intArrayOf(41, 42),
                intent!!.getIntArrayExtra(CourierLocationService.EXTRA_PARCELS))
            assertNull(shadowOf(app).nextStoppedService)
        }
    }

    @Test fun sameParcelKeepsTrackingInTransitAndStopsWhenReturnBegins() {
        val parcels = mutableStateOf(listOf(parcel(42, "accepted")))
        compose.setContent { CourierLiveLocationLink(parcels.value) }
        compose.runOnIdle {
            assertCourierIntent(shadowOf(app).nextStartedService)
            parcels.value = listOf(parcel(42, "in_transit"))
        }
        compose.runOnIdle {
            assertNull("Going in transit must not stop tracking", shadowOf(app).nextStoppedService)
            parcels.value = listOf(parcel(42, "returning"))
        }
        compose.runOnIdle {
            assertCourierIntent(shadowOf(app).nextStoppedService)
            assertNull("Return journey must not restart tracking", shadowOf(app).nextStartedService)
            parcels.value = listOf(parcel(42, "returned"))
        }
        compose.runOnIdle {
            assertNull("Returned delivery must not restart tracking", shadowOf(app).nextStartedService)
        }
    }

    @Test fun alreadyReturnedParcelRequestsStopWithoutStartingService() {
        compose.setContent { CourierLiveLocationLink(listOf(parcel(42, "returned"))) }
        compose.runOnIdle {
            assertCourierIntent(shadowOf(app).nextStoppedService)
            assertNull(shadowOf(app).nextStartedService)
        }
    }
}
