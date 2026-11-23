package com.yuldash.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaxiRouteResumeTimeoutTest {
    @Test
    fun `only the explicit system route resume cancellation matches`() {
        assertTrue(isTaxiRouteResumeTimeout("cancelled", "system", "route_resume_timeout"))
    }

    @Test
    fun `passenger driver and legacy cancellations keep the existing messages`() {
        assertFalse(isTaxiRouteResumeTimeout("cancelled", "passenger", "route_resume_timeout"))
        assertFalse(isTaxiRouteResumeTimeout("cancelled", "driver", "route_resume_timeout"))
        assertFalse(isTaxiRouteResumeTimeout("cancelled", null, "route_resume_timeout"))
        assertFalse(isTaxiRouteResumeTimeout("cancelled", "system", null))
        assertFalse(isTaxiRouteResumeTimeout("cancelled", "", "route_resume_timeout"))
        assertFalse(isTaxiRouteResumeTimeout("cancelled", "system", ""))
        assertFalse(isTaxiRouteResumeTimeout("cancelled", "system", "no_show"))
        assertFalse(isTaxiRouteResumeTimeout("cancelled", "system", "route_resume_timeout "))
    }

    @Test
    fun `retained cancellation metadata is ignored outside cancelled status`() {
        for (status in listOf("accepted", "searching", "expired", "done")) {
            assertFalse(isTaxiRouteResumeTimeout(status, "system", "route_resume_timeout"))
        }
    }
}
