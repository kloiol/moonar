package com.moonartracker.astronomy

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

data class GeoPosition(
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val altitudeMeters: Double = 0.0
)

data class MoonPosition(
    val azimuthDeg: Double,           // 0 = True North, 90 = East, 180 = South, 270 = West
    val altitudeDeg: Double,          // Apparent topocentric altitude (includes refraction if enabled)
    val geometricAltitudeDeg: Double, // Topocentric geometric altitude (without refraction)
    val distanceKm: Double,           // Geocentric Earth-Moon center distance (km)
    val rightAscensionDeg: Double,    // Topocentric Right Ascension (deg)
    val declinationDeg: Double,       // Topocentric Declination (deg)
    val illuminatedFraction: Double,  // 0.0 to 1.0
    val phaseAngleDeg: Double,        // 0 = Full, 180 = New
    val isWaxing: Boolean,
    val phaseName: String,
    val brightLimbAngleDeg: Double,   // Position angle of the bright limb relative to celestial north
    val parallacticAngleDeg: Double,  // Angle between zenith and celestial north at Moon's position
    val angularDiameterDeg: Double    // Apparent angular diameter in degrees (~0.49 to 0.56 deg)
)

data class SkyPoint(
    val epochMillis: Long,
    val azimuthDeg: Double,
    val altitudeDeg: Double
)

/**
 * High-accuracy Lunar Ephemeris and Topocentric Coordinate Engine.
 *
 * Implements Jean Meeus, "Astronomical Algorithms" (2nd ed., Chapter 47 / truncated ELP-2000/82),
 * nutation & true obliquity (Chapter 22), Greenwich Apparent Sidereal Time (Chapter 12),
 * WGS-84 rigorous topocentric parallax (Chapter 40), lunar phase & bright limb (Chapter 48),
 * and Bennett's atmospheric refraction formula (Chapter 16).
 */
object MoonAstronomy {

    private const val DEG2RAD = PI / 180.0
    private const val RAD2DEG = 180.0 / PI
    private const val EARTH_EQUATORIAL_RADIUS_KM = 6378.137
    private const val WGS84_FLATTENING = 1.0 / 298.257223563
    private const val MOON_RADIUS_KM = 1737.4

    // Meeus Table 47.A: Periodic terms for lunar longitude (l) and distance (r)
    // Columns: D, M, M', F, coeff_l (0.000001 deg), coeff_r (0.001 km)
    private val TERMS_LR = arrayOf(
        intArrayOf(0, 0, 1, 0, 6288774, -20905355),
        intArrayOf(2, 0, -1, 0, 1274027, -3699111),
        intArrayOf(2, 0, 0, 0, 658314, -2955968),
        intArrayOf(0, 0, 2, 0, 213618, -569925),
        intArrayOf(0, 1, 0, 0, -185116, 48888),
        intArrayOf(0, 0, 0, 2, -114332, -3149),
        intArrayOf(2, 0, -2, 0, 58793, 246158),
        intArrayOf(2, -1, -1, 0, 57066, -152138),
        intArrayOf(2, 0, 1, 0, 53322, -170733),
        intArrayOf(2, -1, 0, 0, 45758, -204586),
        intArrayOf(0, 1, -1, 0, -40923, -129620),
        intArrayOf(1, 0, 0, 0, -34720, 108743),
        intArrayOf(0, 1, 1, 0, -30383, 104755),
        intArrayOf(2, 0, 0, -2, 15327, 10321),
        intArrayOf(0, 0, 1, 2, -12528, 0),
        intArrayOf(0, 0, 1, -2, 10980, 79661),
        intArrayOf(4, 0, -1, 0, 10675, -34782),
        intArrayOf(0, 0, 3, 0, 10034, -23210),
        intArrayOf(4, 0, -2, 0, 8548, -21636),
        intArrayOf(2, 1, -1, 0, -7888, 24208),
        intArrayOf(2, 1, 0, 0, -6766, 30824),
        intArrayOf(1, 0, -1, 0, -5163, -8379),
        intArrayOf(1, 1, 0, 0, 4987, -16675),
        intArrayOf(2, -1, 1, 0, 4036, -12831),
        intArrayOf(2, 0, 2, 0, 3994, -10445),
        intArrayOf(4, 0, 0, 0, 3861, -11650),
        intArrayOf(2, 0, -3, 0, 3665, 14403),
        intArrayOf(0, 1, -2, 0, -2689, -7003),
        intArrayOf(2, 0, -1, 2, -2602, 0),
        intArrayOf(2, -1, -2, 0, 2390, 10056)
    )

    // Meeus Table 47.B: Periodic terms for lunar latitude (b)
    // Columns: D, M, M', F, coeff_b (0.000001 deg)
    private val TERMS_B = arrayOf(
        intArrayOf(0, 0, 0, 1, 5128122),
        intArrayOf(0, 0, 1, 1, 280602),
        intArrayOf(0, 0, 1, -1, 277693),
        intArrayOf(2, 0, 0, -1, 173237),
        intArrayOf(2, 0, -1, 1, 55413),
        intArrayOf(2, 0, -1, -1, 46271),
        intArrayOf(2, 0, 0, 1, 32573),
        intArrayOf(0, 0, 2, 1, 17198),
        intArrayOf(2, 0, 1, -1, 9266),
        intArrayOf(0, 0, 2, -1, 8822),
        intArrayOf(2, -1, 0, -1, 8216),
        intArrayOf(2, 0, -2, -1, 4324),
        intArrayOf(2, 0, 1, 1, 4200),
        intArrayOf(2, 1, 0, -1, -3359),
        intArrayOf(2, -1, -1, 1, 2463),
        intArrayOf(2, -1, 0, 1, 2211),
        intArrayOf(2, -1, -1, -1, 2065),
        intArrayOf(0, 1, -1, -1, -1870),
        intArrayOf(4, 0, -1, -1, 1828),
        intArrayOf(0, 1, 0, 1, -1794)
    )

    /**
     * Converts Unix epoch milliseconds (UTC) to Julian Date (UT).
     */
    fun epochMillisToJulianDate(epochMillis: Long): Double {
        return epochMillis / 86400000.0 + 2440587.5
    }

    /**
     * Approximate Delta-T (TT - UT) in seconds for contemporary dates (2020-2040 ~ 69.2s).
     */
    fun approxDeltaTSeconds(jdUt: Double): Double {
        val year = 2000.0 + (jdUt - 2451545.0) / 365.25
        return when {
            year >= 2005.0 && year <= 2050.0 -> {
                val t = year - 2000.0
                62.92 + 0.32217 * t + 0.005589 * t * t
            }
            year >= 1986.0 && year < 2005.0 -> {
                val t = year - 2000.0
                63.86 + 0.3345 * t - 0.060374 * t * t + 0.0017275 * t * t * t
            }
            else -> 69.2
        }
    }

    /**
     * Computes geocentric ecliptic longitude (deg), latitude (deg), and distance (km)
     * for a given Julian Ephemeris Date (JDE / TT).
     */
    fun calculateGeocentricEcliptic(jde: Double): Triple<Double, Double, Double> {
        val t = (jde - 2451545.0) / 36525.0
        val t2 = t * t
        val t3 = t2 * t
        val t4 = t3 * t

        val lp = normalize360(
            218.3164477 + 481267.88123421 * t - 0.0015786 * t2 + t3 / 538841.0 - t4 / 65194000.0
        )
        val d = normalize360(
            297.8501921 + 445267.1114034 * t - 0.0018819 * t2 + t3 / 545868.0 - t4 / 113065000.0
        )
        val m = normalize360(
            357.5291092 + 35999.0502909 * t - 0.0001536 * t2 + t3 / 24490000.0
        )
        val mp = normalize360(
            134.9633964 + 477198.8675055 * t + 0.0087414 * t2 + t3 / 69699.0 - t4 / 14712000.0
        )
        val f = normalize360(
            93.2720950 + 483202.0175233 * t - 0.0036539 * t2 - t3 / 3526000.0 + t4 / 863310000.0
        )

        val a1 = normalize360(119.75 + 131.849 * t) * DEG2RAD
        val a2 = normalize360(53.09 + 479264.290 * t) * DEG2RAD
        val a3 = normalize360(313.45 + 481266.484 * t) * DEG2RAD
        val e = 1.0 - 0.002516 * t - 0.0000074 * t2
        val e2 = e * e

        var sumL = 0.0
        var sumR = 0.0
        for (term in TERMS_LR) {
            val cD = term[0]
            val cM = term[1]
            val cMp = term[2]
            val cF = term[3]
            val arg = (cD * d + cM * m + cMp * mp + cF * f) * DEG2RAD
            val eFactor = when (abs(cM)) {
                1 -> e
                2 -> e2
                else -> 1.0
            }
            sumL += term[4] * eFactor * sin(arg)
            sumR += term[5] * eFactor * cos(arg)
        }

        // Additional additive terms for longitude (Meeus p. 338)
        sumL += 3958.0 * sin(a1) +
            1962.0 * sin((lp - f) * DEG2RAD) +
            318.0 * sin(a2)

        var sumB = 0.0
        for (term in TERMS_B) {
            val cD = term[0]
            val cM = term[1]
            val cMp = term[2]
            val cF = term[3]
            val arg = (cD * d + cM * m + cMp * mp + cF * f) * DEG2RAD
            val eFactor = when (abs(cM)) {
                1 -> e
                2 -> e2
                else -> 1.0
            }
            sumB += term[4] * eFactor * sin(arg)
        }

        // Additional additive terms for latitude (Meeus p. 338)
        sumB += -2235.0 * sin(lp * DEG2RAD) +
            382.0 * sin(a3) +
            175.0 * sin((a1 - f * DEG2RAD)) +
            175.0 * sin((a1 + f * DEG2RAD)) +
            127.0 * sin((lp - mp) * DEG2RAD) -
            115.0 * sin((lp + mp) * DEG2RAD)

        val lambdaDeg = normalize360(lp + sumL / 1_000_000.0)
        val betaDeg = sumB / 1_000_000.0
        val distanceKm = 385000.56 + sumR / 1000.0

        return Triple(lambdaDeg, betaDeg, distanceKm)
    }

    /**
     * Computes Sun's apparent geocentric ecliptic longitude (deg) and distance (AU)
     * for lunar phase and bright-limb orientation (Meeus Ch. 25).
     */
    private fun calculateSunPosition(jde: Double): Pair<Double, Double> {
        val t = (jde - 2451545.0) / 36525.0
        val l0 = normalize360(280.46646 + 36000.76983 * t + 0.0003032 * t * t)
        val m = normalize360(357.52911 + 35999.05029 * t - 0.0001537 * t * t)
        val mRad = m * DEG2RAD
        val c = (1.914602 - 0.004817 * t - 0.000014 * t * t) * sin(mRad) +
            (0.019993 - 0.000101 * t) * sin(2.0 * mRad) +
            0.000289 * sin(3.0 * mRad)
        val trueLon = normalize360(l0 + c)
        val omega = normalize360(125.04 - 1934.136 * t) * DEG2RAD
        val apparentLon = normalize360(trueLon - 0.00569 - 0.00478 * sin(omega))
        val ecc = 0.016708634 - 0.000042037 * t - 0.0000001267 * t * t
        val trueAnomaly = (m + c) * DEG2RAD
        val radiusAu = (1.000001018 * (1.0 - ecc * ecc)) / (1.0 + ecc * cos(trueAnomaly))
        return Pair(apparentLon, radiusAu * 149_597_870.7) // distance in km
    }

    /**
     * Computes full topocentric Moon position, phase, and orientation for the given
     * observer location and UTC timestamp.
     */
    fun calculateMoonPosition(
        geo: GeoPosition,
        epochMillis: Long,
        applyRefraction: Boolean = true
    ): MoonPosition {
        val jdUt = epochMillisToJulianDate(epochMillis)
        val jde = jdUt + approxDeltaTSeconds(jdUt) / 86400.0
        val t = (jde - 2451545.0) / 36525.0

        val (lambdaDeg, betaDeg, distanceKm) = calculateGeocentricEcliptic(jde)

        // Nutation in longitude and obliquity (Meeus Ch. 22, low-precision ~0.5 arcsec)
        val omega = normalize360(125.04452 - 1934.136261 * t) * DEG2RAD
        val lSun = normalize360(280.4665 + 36000.7698 * t) * DEG2RAD
        val lMoon = normalize360(218.3165 + 481267.8813 * t) * DEG2RAD

        val deltaPsiArcsec = -17.20 * sin(omega) - 1.32 * sin(2.0 * lSun) -
            0.23 * sin(2.0 * lMoon) + 0.21 * sin(2.0 * omega)
        val deltaEpsArcsec = 9.20 * cos(omega) + 0.57 * cos(2.0 * lSun) +
            0.10 * cos(2.0 * lMoon) - 0.09 * cos(2.0 * omega)

        val eps0Deg = 23.0 + 26.0 / 60.0 + (21.448 - 46.8150 * t - 0.00059 * t * t + 0.001813 * t * t * t) / 3600.0
        val trueEpsRad = (eps0Deg + deltaEpsArcsec / 3600.0) * DEG2RAD
        val apparentLambdaRad = normalize360(lambdaDeg + deltaPsiArcsec / 3600.0) * DEG2RAD
        val betaRad = betaDeg * DEG2RAD

        // Geocentric Right Ascension & Declination
        val sinLambda = sin(apparentLambdaRad)
        val cosLambda = cos(apparentLambdaRad)
        val sinEps = sin(trueEpsRad)
        val cosEps = cos(trueEpsRad)

        val raGeoRad = atan2(sinLambda * cosEps - tan(betaRad) * sinEps, cosLambda)
        val decGeoRad = asin((sin(betaRad) * cosEps + cos(betaRad) * sinEps * sinLambda).coerceIn(-1.0, 1.0))

        // Greenwich Mean & Apparent Sidereal Time (Meeus Ch. 12)
        val tUt = (jdUt - 2451545.0) / 36525.0
        val gmstDeg = normalize360(
            280.46061837 +
                360.98564736629 * (jdUt - 2451545.0) +
                0.000387933 * tUt * tUt -
                tUt * tUt * tUt / 38710000.0
        )
        val gastDeg = normalize360(gmstDeg + (deltaPsiArcsec / 3600.0) * cosEps)
        val lstRad = normalize360(gastDeg + geo.longitudeDeg) * DEG2RAD

        // Geocentric local hour angle
        val hGeoRad = lstRad - raGeoRad

        // WGS-84 observer geocentric coordinates (Meeus Ch. 11 & Ch. 40)
        val latRad = geo.latitudeDeg * DEG2RAD
        val bOverA = 1.0 - WGS84_FLATTENING
        val u = atan(bOverA * tan(latRad))
        val altKm = geo.altitudeMeters / 1000.0
        val rhoSinPhiPrime = bOverA * sin(u) + (altKm / EARTH_EQUATORIAL_RADIUS_KM) * sin(latRad)
        val rhoCosPhiPrime = cos(u) + (altKm / EARTH_EQUATORIAL_RADIUS_KM) * cos(latRad)

        // Equatorial horizontal parallax
        val sinPi = (EARTH_EQUATORIAL_RADIUS_KM / distanceKm).coerceIn(-1.0, 1.0)

        // Rigorous topocentric RA and Dec (Meeus eq. 40.2 & 40.3)
        val cosDecGeo = cos(decGeoRad)
        val sinDecGeo = sin(decGeoRad)
        val deltaRaRad = atan2(
            -rhoCosPhiPrime * sinPi * sin(hGeoRad),
            cosDecGeo - rhoCosPhiPrime * sinPi * cos(hGeoRad)
        )
        val raTopoRad = raGeoRad + deltaRaRad
        val hTopoRad = hGeoRad - deltaRaRad
        val decTopoRad = atan2(
            (sinDecGeo - rhoSinPhiPrime * sinPi) * cos(deltaRaRad),
            cosDecGeo - rhoCosPhiPrime * sinPi * cos(hGeoRad)
        )

        // Horizontal coordinates (Azimuth from North clockwise, Altitude)
        val sinLat = sin(latRad)
        val cosLat = cos(latRad)
        val sinDec = sin(decTopoRad)
        val cosDec = cos(decTopoRad)
        val cosH = cos(hTopoRad)
        val sinH = sin(hTopoRad)

        val geomAltRad = asin((sinLat * sinDec + cosLat * cosDec * cosH).coerceIn(-1.0, 1.0))
        val geomAltDeg = geomAltRad * RAD2DEG

        // Azimuth measured clockwise from North (0 = N, 90 = E, 180 = S, 270 = W)
        val azFromSouthRad = atan2(sinH, cosH * sinLat - tan(decTopoRad) * cosLat)
        val azimuthDeg = normalize360(azFromSouthRad * RAD2DEG + 180.0)

        // Atmospheric refraction (Bennett's formula, Meeus eq. 16.4)
        val apparentAltDeg = if (applyRefraction && geomAltDeg > -1.0) {
            val hClamped = geomAltDeg.coerceAtLeast(-1.0)
            val rArcmin = 1.02 / tan((hClamped + 10.3 / (hClamped + 5.11)) * DEG2RAD)
            geomAltDeg + rArcmin / 60.0
        } else {
            geomAltDeg
        }

        // Parallactic angle q (angle between zenith and celestial north pole at Moon)
        val parallacticAngleDeg = atan2(sinH, tan(latRad) * cosDec - sinDec * cosH) * RAD2DEG

        // Solar position for lunar phase, illumination, and bright limb angle (Meeus Ch. 48)
        val (sunLonDeg, sunDistKm) = calculateSunPosition(jde)
        val sunLonRad = sunLonDeg * DEG2RAD
        val sunRaRad = atan2(sin(sunLonRad) * cosEps, cos(sunLonRad))
        val sunDecRad = asin((sin(sunLonRad) * sinEps).coerceIn(-1.0, 1.0))

        // Geocentric elongation psi
        val cosPsi = (sin(sunDecRad) * sinDecGeo + cos(sunDecRad) * cosDecGeo * cos(sunRaRad - raGeoRad))
            .coerceIn(-1.0, 1.0)
        val psiRad = acos(cosPsi)
        val phaseAngleRad = atan2(sunDistKm * sin(psiRad), distanceKm - sunDistKm * cos(psiRad))
        val illuminatedFraction = ((1.0 + cos(phaseAngleRad)) / 2.0).coerceIn(0.0, 1.0)

        val lonDiff = normalize360(lambdaDeg - sunLonDeg)
        val isWaxing = lonDiff < 180.0
        val phaseName = classifyMoonPhase(lonDiff)

        // Position angle of the bright limb (Meeus eq. 48.5)
        val brightLimbRad = atan2(
            cos(sunDecRad) * sin(sunRaRad - raGeoRad),
            sin(sunDecRad) * cosDecGeo - cos(sunDecRad) * sinDecGeo * cos(sunRaRad - raGeoRad)
        )
        val brightLimbDeg = normalize360(brightLimbRad * RAD2DEG)

        // Apparent angular diameter
        val angularDiameterDeg = 2.0 * asin((MOON_RADIUS_KM / distanceKm).coerceIn(-1.0, 1.0)) * RAD2DEG

        return MoonPosition(
            azimuthDeg = azimuthDeg,
            altitudeDeg = apparentAltDeg,
            geometricAltitudeDeg = geomAltDeg,
            distanceKm = distanceKm,
            rightAscensionDeg = normalize360(raTopoRad * RAD2DEG),
            declinationDeg = decTopoRad * RAD2DEG,
            illuminatedFraction = illuminatedFraction,
            phaseAngleDeg = phaseAngleRad * RAD2DEG,
            isWaxing = isWaxing,
            phaseName = phaseName,
            brightLimbAngleDeg = brightLimbDeg,
            parallacticAngleDeg = parallacticAngleDeg,
            angularDiameterDeg = angularDiameterDeg
        )
    }

    /**
     * Generates the Moon's apparent sky trajectory over the next [hoursAhead] hours
     * (and [hoursBehind] hours behind) at [stepMinutes] intervals.
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
            val pos = calculateMoonPosition(geo, t, applyRefraction = true)
            points.add(SkyPoint(t, pos.azimuthDeg, pos.altitudeDeg))
            t += stepMillis
        }
        return points
    }

    private fun classifyMoonPhase(lonDiffDeg: Double): String {
        val d = normalize360(lonDiffDeg)
        return when {
            d < 11.25 || d >= 348.75 -> "New Moon"
            d < 78.75 -> "Waxing Crescent"
            d < 101.25 -> "First Quarter"
            d < 168.75 -> "Waxing Gibbous"
            d < 191.25 -> "Full Moon"
            d < 258.75 -> "Waning Gibbous"
            d < 281.25 -> "Last Quarter"
            else -> "Waning Crescent"
        }
    }

    fun normalize360(deg: Double): Double {
        var r = deg % 360.0
        if (r < 0.0) r += 360.0
        return r
    }
}
