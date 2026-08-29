package com.yuldash.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaxiLocationHeadingTest {
    @Test
    fun `standing still never shows a direction arrow`() {
        assertNull(
            reliableTaxiHeading(
                hasBearing = true,
                bearingDegrees = 125f,
                hasSpeed = true,
                speedMetersPerSecond = 0.4f,
                accuracyMeters = 8f,
                bearingAccuracyDegrees = 5f,
            ),
        )
    }

    @Test
    fun `poor coordinate or bearing accuracy hides the arrow`() {
        assertNull(reliableTaxiHeading(true, 125f, true, 5f, 120f, 5f))
        assertNull(reliableTaxiHeading(true, 125f, true, 5f, 8f, 60f))
    }

    @Test
    fun `reliable moving fix normalizes bearing`() {
        assertEquals(
            350f,
            reliableTaxiHeading(true, -10f, true, 4f, 8f, 5f),
        )
    }

    @Test
    fun `compass smoothing crosses north by the shortest arc`() {
        assertEquals(0f, smoothTaxiCompassHeading(previous = 350f, next = 10f, weight = 0.5f))
    }

    @Test
    fun `approved A and B states use real available direction`() {
        assertEquals(TaxiUserLocationVisual.Dot, taxiUserLocationVisualFor(false, null, null))
        assertEquals(TaxiUserLocationVisual.DotWithDirection, taxiUserLocationVisualFor(false, null, 80f))
        assertEquals(TaxiUserLocationVisual.Arrow, taxiUserLocationVisualFor(false, 120f, 80f))
        assertEquals(TaxiUserLocationVisual.Selected, taxiUserLocationVisualFor(true, 120f, 80f))
    }
}
