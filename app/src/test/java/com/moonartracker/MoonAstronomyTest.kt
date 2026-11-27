package com.moonartracker

import com.moonartracker.ar.ArProjection
import com.moonartracker.ar.CameraFov
import com.moonartracker.astronomy.GeoPosition
import com.moonartracker.astronomy.MoonAstronomy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoonAstronomyTest {

    @Test
    fun meeusExample47a_geocentricEclipticMatchesReference() {
        // Jean Meeus, Astronomical Algorithms (2nd ed.), Example 47.a:
        // 1992 April 12, 0h TD -> JDE = 2448724.5
        // Expected: lambda = 133.162655 deg, beta = -3.229126 deg, Delta = 368409.7 km
        val (lambdaDeg, betaDeg, distKm) = MoonAstronomy.calculateGeocentricEcliptic(2448724.5)

        assertEquals(133.162655, lambdaDeg, 0.015)
        assertEquals(-3.229126, betaDeg, 0.015)
        assertEquals(368409.7, distKm, 50.0)
    }

    @Test
    fun topocentricPosition_returnsValidRangesAndParallax() {
        val geo = GeoPosition(latitudeDeg = 60.5544, longitudeDeg = -151.2583, altitudeMeters = 20.0)
        // 2026-11-27 12:00:00 UTC
        val epochMillis = 1795694400000L
        val pos = MoonAstronomy.calculateMoonPosition(geo, epochMillis)

        assertTrue("Azimuth in [0, 360)", pos.azimuthDeg in 0.0..360.0)
        assertTrue("Altitude in [-90, 90]", pos.altitudeDeg in -90.0..90.0)
        assertTrue("Distance in [356000, 407000] km", pos.distanceKm in 356000.0..407000.0)
        assertTrue("Illumination in [0, 1]", pos.illuminatedFraction in 0.0..1.0)
        assertTrue("Angular diameter ~0.49-0.56 deg", pos.angularDiameterDeg in 0.45..0.60)
    }

    @Test
    fun arProjection_targetDirectlyAheadProjectsToScreenCenter() {
        // Device held upright facing True North with 0 deg declination:
        // Device +X = East (1,0,0), Device +Y = Up (0,0,1), Device +Z = South (0,-1,0)
        // So rear camera (-Z) points North (0,1,0).
        val deviceToWorld = floatArrayOf(
            1f, 0f, 0f,
            0f, 0f, -1f,
            0f, 1f, 0f
        )
        val proj = ArProjection.projectCelestialPoint(
            azimuthTrueDeg = 0.0,
            altitudeDeg = 0.0,
            magneticDeclinationDeg = 0.0,
            deviceToWorldMatrix3x3 = deviceToWorld,
            screenWidthPx = 1080f,
            screenHeightPx = 1920f,
            cameraFov = CameraFov(55.0, 70.0)
        )

        assertTrue(proj.isInFront)
        assertTrue(proj.isOnScreen)
        assertEquals(540f, proj.screenX, 1.0f)
        assertEquals(960f, proj.screenY, 1.0f)
        assertEquals(0.0, proj.angularOffsetDeg, 0.1)
    }
}
