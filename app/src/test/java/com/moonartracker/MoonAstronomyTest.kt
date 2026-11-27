package com.moonartracker

import com.moonartracker.ar.ArProjection
import com.moonartracker.ar.CameraFov
import com.moonartracker.astronomy.CompassSector
import com.moonartracker.astronomy.GeoPosition
import com.moonartracker.astronomy.MoonAstronomy
import com.moonartracker.astronomy.SunAstronomy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoonAstronomyTest {

    @Test
    fun meeusExample47a_geocentricEclipticMatchesReference() {
        val (lambdaDeg, betaDeg, distKm) = MoonAstronomy.calculateGeocentricEcliptic(2448724.5)

        assertEquals(133.162655, lambdaDeg, 0.015)
        assertEquals(-3.229126, betaDeg, 0.015)
        assertEquals(368409.7, distKm, 50.0)
    }

    @Test
    fun meeusExample25a_sunGeocentricEclipticMatchesReference() {
        val (sunLonDeg, distAu) = SunAstronomy.calculateGeocentricEcliptic(2448908.5)

        assertEquals(199.90895, sunLonDeg, 0.01)
        assertEquals(0.99766, distAu, 0.001)
    }

    @Test
    fun topocentricPosition_returnsValidRangesAndParallax() {
        val geo = GeoPosition(latitudeDeg = 60.5544, longitudeDeg = -151.2583, altitudeMeters = 20.0)
        val epochMillis = 1795694400000L
        val pos = MoonAstronomy.calculateMoonPosition(geo, epochMillis)

        assertTrue("Azimuth in [0, 360)", pos.azimuthDeg in 0.0..360.0)
        assertTrue("Altitude in [-90, 90]", pos.altitudeDeg in -90.0..90.0)
        assertTrue("Distance in [356000, 407000] km", pos.distanceKm in 356000.0..407000.0)
        assertTrue("Illumination in [0, 1]", pos.illuminatedFraction in 0.0..1.0)
        assertTrue("Angular diameter ~0.49-0.56 deg", pos.angularDiameterDeg in 0.45..0.60)
    }

    @Test
    fun compassSector_mapsAzimuthQuadrantsCorrectly() {
        assertEquals(CompassSector.NE, CompassSector.fromAzimuth(45.0))
        assertEquals(CompassSector.SE, CompassSector.fromAzimuth(135.0))
        assertEquals(CompassSector.SW, CompassSector.fromAzimuth(225.0))
        assertEquals(CompassSector.NW, CompassSector.fromAzimuth(315.0))
    }

    @Test
    fun sectorVisibilityForecast_findsValidSeAndSwWindowsWithHorizonDip() {
        val seaLevel = GeoPosition(latitudeDeg = 40.0, longitudeDeg = -74.0, altitudeMeters = 0.0)
        val mountain = GeoPosition(latitudeDeg = 40.0, longitudeDeg = -74.0, altitudeMeters = 3000.0)
        val epochMillis = 1795694400000L

        val fSe = MoonAstronomy.calculateSectorVisibilityForecast(seaLevel, CompassSector.SE, epochMillis)
        val fSw = MoonAstronomy.calculateSectorVisibilityForecast(mountain, CompassSector.SW, epochMillis)

        assertEquals(0.0, fSe.horizonDipDeg, 1e-6)
        assertTrue("Higher elevation should dip horizon further", fSw.horizonDipDeg > 1.5)
        assertNotNull("Should find SE visibility window", fSe.windowStartMillis)
        assertNotNull("Should find SW visibility window", fSw.windowStartMillis)

        // Verify midpoint of SE window is actually above horizon and inside E-S [90, 180)
        val midSe = (fSe.windowStartMillis!! + (fSe.windowEndMillis ?: (fSe.windowStartMillis!! + 3600_000L))) / 2L
        val midPos = MoonAstronomy.calculateMoonPosition(seaLevel, midSe)
        assertTrue("Midpoint should be in E-S sector", midPos.azimuthDeg in 90.0..180.0)
        assertTrue("Midpoint should be above horizon threshold", midPos.altitudeDeg >= fSe.visibilityThresholdDeg)
    }

    @Test
    fun arProjection_targetDirectlyAheadProjectsToScreenCenter() {
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
