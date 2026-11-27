package com.moonartracker.astronomy

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

enum class TrackedBody {
    MOON,
    SUN
}

data class SunPosition(
    val azimuthDeg: Double,           // 0 = True North, 90 = East, 180 = South, 270 = West
    val altitudeDeg: Double,          // Apparent altitude (with atmospheric refraction if enabled)
    val geometricAltitudeDeg: Double, // Geometric altitude (without refraction)
    val distanceAu: Double,           // Earth-Sun distance in AU
    val rightAscensionDeg: Double,    // Apparent Right Ascension (deg)
    val declinationDeg: Double,       // Apparent Declination (deg)
    val angularDiameterDeg: Double,   // Apparent angular diameter (~0.52 to 0.54 deg)
    val twilightPhase: String         // Daylight, Civil/Nautical/Astronomical Twilight, Night
)

/**
 * Solar Ephemeris and Horizontal Coordinate Engine.
 *
 * Implements Jean Meeus, "Astronomical Algorithms" (2nd ed., Chapter 25)
 * with nutation, true obliquity, Greenwich Apparent Sidereal Time, and
 * Bennett's atmospheric refraction formula.
 */
object SunAstronomy {

    private const val DEG2RAD = PI / 180.0
    private const val RAD2DEG = 180.0 / PI
    private const val SUN_RADIUS_AU = 0.00465047

    /**
     * Computes apparent geocentric ecliptic longitude (deg) and radius vector (AU)
     * for a given Julian Ephemeris Date (JDE).
     */
    fun calculateGeocentricEcliptic(jde: Double): Pair<Double, Double> {
        val t = (jde - 2451545.0) / 36525.0
        val t2 = t * t
        val l0 = MoonAstronomy.normalize360(280.46646 + 36000.76983 * t + 0.0003032 * t2)
        val m = MoonAstronomy.normalize360(357.52911 + 35999.05029 * t - 0.0001537 * t2)
        val mRad = m * DEG2RAD

        val c = (1.914602 - 0.004817 * t - 0.000014 * t2) * sin(mRad) +
            (0.019993 - 0.000101 * t) * sin(2.0 * mRad) +
            0.000289 * sin(3.0 * mRad)

        val trueLon = MoonAstronomy.normalize360(l0 + c)
        val omega = MoonAstronomy.normalize360(125.04 - 1934.136 * t) * DEG2RAD
        val apparentLon = MoonAstronomy.normalize360(trueLon - 0.00569 - 0.00478 * sin(omega))

        val ecc = 0.016708634 - 0.000042037 * t - 0.0000001267 * t2
        val trueAnomaly = (m + c) * DEG2RAD
        val radiusAu = (1.000001018 * (1.0 - ecc * ecc)) / (1.0 + ecc * cos(trueAnomaly))

        return Pair(apparentLon, radiusAu)
    }

    /**
     * Computes apparent topocentric horizontal coordinates (Azimuth, Altitude)
     * and equatorial metadata for the Sun at [geo] and [epochMillis].
     */
    fun calculateSunPosition(
        geo: GeoPosition,
        epochMillis: Long,
        applyRefraction: Boolean = true
    ): SunPosition {
        val jdUt = MoonAstronomy.epochMillisToJulianDate(epochMillis)
        val jde = jdUt + MoonAstronomy.approxDeltaTSeconds(jdUt) / 86400.0
        val t = (jde - 2451545.0) / 36525.0

        val (apparentLonDeg, radiusAu) = calculateGeocentricEcliptic(jde)

        val omega = MoonAstronomy.normalize360(125.04 - 1934.136 * t) * DEG2RAD
        val eps0Deg = 23.0 + 26.0 / 60.0 +
            (21.448 - 46.8150 * t - 0.00059 * t * t + 0.001813 * t * t * t) / 3600.0
        val trueEpsRad = (eps0Deg + 0.00256 * cos(omega)) * DEG2RAD
        val lambdaRad = apparentLonDeg * DEG2RAD

        val raRad = atan2(cos(trueEpsRad) * sin(lambdaRad), cos(lambdaRad))
        val decRad = asin((sin(trueEpsRad) * sin(lambdaRad)).coerceIn(-1.0, 1.0))

        // Greenwich Apparent Sidereal Time
        val tUt = (jdUt - 2451545.0) / 36525.0
        val gmstDeg = MoonAstronomy.normalize360(
            280.46061837 +
                360.98564736629 * (jdUt - 2451545.0) +
                0.000387933 * tUt * tUt -
                tUt * tUt * tUt / 38710000.0
        )
        val deltaPsiDeg = (-17.20 * sin(omega)) / 3600.0
        val gastDeg = MoonAstronomy.normalize360(gmstDeg + deltaPsiDeg * cos(trueEpsRad))
        val lstRad = MoonAstronomy.normalize360(gastDeg + geo.longitudeDeg) * DEG2RAD
        val hourAngleRad = lstRad - raRad

        val latRad = geo.latitudeDeg * DEG2RAD
        val sinLat = sin(latRad)
        val cosLat = cos(latRad)
        val sinDec = sin(decRad)
        val cosDec = cos(decRad)
        val cosH = cos(hourAngleRad)
        val sinH = sin(hourAngleRad)

        val geomAltRad = asin((sinLat * sinDec + cosLat * cosDec * cosH).coerceIn(-1.0, 1.0))
        val geomAltDeg = geomAltRad * RAD2DEG

        val azFromSouthRad = atan2(sinH, cosH * sinLat - tan(decRad) * cosLat)
        val azimuthDeg = MoonAstronomy.normalize360(azFromSouthRad * RAD2DEG + 180.0)

        val apparentAltDeg = if (applyRefraction && geomAltDeg > -1.0) {
            val hClamped = geomAltDeg.coerceAtLeast(-1.0)
            val rArcmin = 1.02 / tan((hClamped + 10.3 / (hClamped + 5.11)) * DEG2RAD)
            geomAltDeg + rArcmin / 60.0
        } else {
            geomAltDeg
        }

        val angularDiameterDeg = 2.0 * asin((SUN_RADIUS_AU / radiusAu).coerceIn(-1.0, 1.0)) * RAD2DEG
        val twilightPhase = when {
            apparentAltDeg >= -0.26 -> "Daylight"
            apparentAltDeg >= -6.0 -> "Civil Twilight"
            apparentAltDeg >= -12.0 -> "Nautical Twilight"
            apparentAltDeg >= -18.0 -> "Astronomical Twilight"
            else -> "Night"
        }

        return SunPosition(
            azimuthDeg = azimuthDeg,
            altitudeDeg = apparentAltDeg,
            geometricAltitudeDeg = geomAltDeg,
            distanceAu = radiusAu,
            rightAscensionDeg = MoonAstronomy.normalize360(raRad * RAD2DEG),
            declinationDeg = decRad * RAD2DEG,
            angularDiameterDeg = angularDiameterDeg,
            twilightPhase = twilightPhase
        )
    }

    /**
     * Generates the Sun's apparent sky trajectory across [hoursBehind]..[hoursAhead].
     */
    fun calculateTrajectory(
        geo: GeoPosition,
        centerEpochMillis: Long,
        hoursBehind: Int = 6,
        hoursAhead: Int = 18,
        stepMinutes: Int = 30
    ): List<SkyPoint> {
        val stepMillis = stepMinutes * 60_000L
        val start = centerEpochMillis - hoursBehind * 3_600_000L
        val end = centerEpochMillis + hoursAhead * 3_600_000L
        val points = mutableListOf<SkyPoint>()
        var t = start
        while (t <= end) {
            val pos = calculateSunPosition(geo, t, applyRefraction = true)
            points.add(SkyPoint(t, pos.azimuthDeg, pos.altitudeDeg))
            t += stepMillis
        }
        return points
    }
}
