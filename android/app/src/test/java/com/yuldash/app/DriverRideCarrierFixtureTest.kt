package com.yuldash.app

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.TripPassAuthorityFixture
import com.yuldash.app.data.parseDriverBookingDto
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Общий набор main проверяется на каноническом номере рейса и действующей границе выдачи grant. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DriverRideCarrierFixtureTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Before fun setup() {
        TripPassAuthorityFixture.installSession("http://127.0.0.1:9", 7)
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        DriverGpsActions.revoke()
    }

    @After fun cleanup() {
        DriverGpsActions.revoke()
        ApiClient.resetForTest()
    }

    @Test fun allSharedCasesUseServerCanonicalRideAndRejectMissingAnchors() = runBlocking {
        val stream = requireNotNull(javaClass.classLoader!!.getResourceAsStream("driver-ride-carrier-cases.json"))
        val cases = stream.bufferedReader().use { JSONObject(it.readText()).getJSONArray("cases") }
        var checked = 0
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val anchor = case.getInt("anchor")
            val raw = case.getJSONArray("rows")
            val rows = (0 until raw.length()).map { parseDriverBookingDto(raw.getJSONObject(it)) }
            val closedRaw = case.getJSONArray("closed")
            val closed = (0 until closedRaw.length()).map(closedRaw::getInt).toSet()
            val expected = case.getJSONObject("expect")
            val ride = rows.firstOrNull { it.bookingId == anchor }?.rideId ?: 0
            if (ride > 0) {
                val pick = pickRideCarrier(rows, ride, closed)
                val live = expected.getJSONArray("live")
                assertEquals(case.getString("name"), expected.getInt("ride_id"), pick.rideId)
                assertEquals((0 until live.length()).map(live::getInt).toSet(), pick.liveBookings)
                assertEquals(if (expected.isNull("carrier")) null else expected.getInt("carrier"), pick.carrier)
                assertEquals(if (expected.isNull("carrier_ttl_sec")) null else expected.getInt("carrier_ttl_sec"), pick.carrierTtlSec)
            } else {
                assertNull(driverRideHasLiveBookings(anchor, ApiClient.queueSessionGeneration()) {
                    Result.success(DriverBookingsFeed(rows, false))
                })
                DriverGpsActions.revoke()
                val pending = requireNotNull(DriverGpsActions.begin(anchor))
                var started: Int? = null
                assertFalse(DriverGpsActions.complete(pending, app, AppLanguage.Ru, { true },
                    closed = anchor in closed, fetch = { Result.success(DriverBookingsFeed(rows, false)) },
                    start = { started = it }))
                assertNull(started)
                assertNull(DriverGpsActions.current())
            }
            checked++
        }
        assertEquals(12, checked)
    }
}
