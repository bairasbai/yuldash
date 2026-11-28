package com.yuldash.app.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriverCurrentOrderLookupTest {
    private data class Trip(
        val id: Int = 81, val driver: Int? = 17, val role: String = "driver", val status: String = "accepted",
    )

    private suspend fun lookup(
        id: suspend () -> Result<Int?>,
        detail: suspend (Int) -> Result<Trip> = { Result.success(Trip()) },
        owner: () -> Boolean = { true },
    ): DriverCurrentOrder<Trip> = lookupDriverCurrentOrder(owner, id, detail) { requested, trip ->
        driverOwnsActiveOrder(requested, 17, trip.id, trip.driver, trip.role, trip.status)
    }

    @Test
    fun assignedActiveStagesAreReadOnly() = runBlocking {
        for (status in listOf("accepted", "arriving", "onboard")) {
            val trip = Trip(status = status)
            assertEquals(DriverCurrentOrder.Active(trip), lookup({ Result.success(81) }, { requested ->
                assertEquals(81, requested)
                Result.success(trip)
            }))
        }
    }

    @Test
    fun explicitNullConflictAndFailureAreDifferent() = runBlocking {
        var openedDetail = false
        val detail: suspend (Int) -> Result<Trip> = { openedDetail = true; Result.success(Trip()) }
        assertEquals(DriverCurrentOrder.None, lookup({ Result.success(null) }, detail))
        assertEquals(DriverCurrentOrder.Conflict, lookup({ Result.failure(DriverCurrentOrderConflict()) }, detail))
        assertEquals(DriverCurrentOrder.Unavailable, lookup({ Result.failure(IllegalStateException()) }, detail))
        assertEquals(DriverCurrentOrder.Unavailable, lookup({ Result.success(0) }, detail))
        assertFalse(openedDetail)
    }

    @Test
    fun malformedOrOutdatedDetailsNeverBecomeActive() = runBlocking {
        for (bad in listOf(Trip(id = 82), Trip(driver = 21), Trip(driver = null),
            Trip(role = "passenger"), Trip(status = "done"), Trip(status = "offered"))) {
            assertEquals(DriverCurrentOrder.Unavailable, lookup({ Result.success(81) }, { Result.success(bad) }))
        }
        assertEquals(DriverCurrentOrder.Unavailable,
            lookup({ Result.success(81) }, { Result.failure(DriverCurrentOrderConflict()) }))
    }

    @Test
    fun oldSessionCannotOpenOrReadDetails() = runBlocking {
        var current = true
        var opened = false
        assertEquals(DriverCurrentOrder.SessionChanged, lookup(
            { current = false; Result.success(81) }, { opened = true; Result.success(Trip()) }, { current },
        ))
        assertFalse(opened)
        current = true
        assertEquals(DriverCurrentOrder.SessionChanged, lookup(
            { Result.success(81) }, { opened = true; current = false; Result.success(Trip()) }, { current },
        ))
        assertTrue(opened)
    }
}
