package com.moonartracker.astronomy

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

data class GeoPosition(
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val altitudeMeters: Double = 0.0
)

enum class CompassSector(
    val shortLabel: String,
    val rangeLabel: String,
    val startAzDeg: Double,
    val endAzDeg: Double
) {
    NE("NE", "N - E (0 to 90 deg)", 0.0, 90.0),
    SE("SE", "E - S (90 to 180 deg)", 90.0, 180.0),
    SW("SW", "S - W (180 to 270 deg)", 180.0, 270.0),
    NW("NW", "W - N (270 to 360 deg)", 270.0, 360.0);

    companion object {
        fun fromAzimuth(azimuthDeg: Double): CompassSector {
            val az = MoonAstronomy.normalize360(azimuthDeg)
            return when {
                az < 90.0 -> NE
                az < 180.0 -> SE
                az < 270.0 -> SW
                else -> NW
            }
        }
    }
}

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

data class MoonSectorVisibilityForecast(
    val sector: CompassSector,
    val isCurrentlyVisibleInSector: Boolean,
    val currentAzimuthDeg: Double,
    val currentAltitudeDeg: Double,
    val observerAltitudeMeters: Double,
    val horizonDipDeg: Double,
    val visibilityThresholdDeg: Double,
    val windowStartMillis: Long?,
    val enterAzimuthDeg: Double?,
    val enterAltitudeDeg: Double?,
    val windowEndMillis: Long?,
    val exitAzimuthDeg: Double?,
    val exitAltitudeDeg: Double?,
    val peakMillis: Long?,
    val peakAzimuthDeg: Double?,
    val peakAltitudeDeg: Double?,
    val summaryNote: String
)

/**
 * High-accuracy Lunar Ephemeris and Topocentric Coordinate Engine.
 *
 * Implements Jean Meeus, "Astronomical Algorithms" (2nd ed., Chapter 47 / truncated ELP-2000/82),
 * nutation & true obliquity (Chapter 22), Greenwich Apparent Sidereal Time (Chapter 12),
 * WGS-84 rigorous topocentric parallax (Chapter 40), lunar phase & bright limb (Chapter 48),
 * horizon dip from observer elevation, and Bennett's atmospheric refraction formula (Chapter 16).
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

    fun epochMillisToJulianDate(epochMillis: Long): Double {
        return epochMillis / 86400000.0 + 2440587.5
    }

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

    fun calculateHorizonDipDeg(altitudeMeters: Double): Double {
        if (altitudeMeters <= 0.0) return 0.0
        val hKm = altitudeMeters / 1000.0
        val ratio = (EARTH_EQUATORIAL_RADIUS_KM / (EARTH_EQUATORIAL_RADIUS_KM + hKm)).coerceIn(-1.0, 1.0)
        return acos(ratio) * RAD2DEG
    }

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

    fun calculateMoonPosition(
        geo: GeoPosition,
        epochMillis: Long,
        applyRefraction: Boolean = true
    ): MoonPosition {
        val jdUt = epochMillisToJulianDate(epochMillis)
        val jde = jdUt + approxDeltaTSeconds(jdUt) / 86400.0
        val t = (jde - 2451545.0) / 36525.0

        val (lambdaDeg, betaDeg, distanceKm) = calculateGeocentricEcliptic(jde)

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

        val sinLambda = sin(apparentLambdaRad)
        val cosLambda = cos(apparentLambdaRad)
        val sinEps = sin(trueEpsRad)
        val cosEps = cos(trueEpsRad)

        val raGeoRad = atan2(sinLambda * cosEps - tan(betaRad) * sinEps, cosLambda)
        val decGeoRad = asin((sin(betaRad) * cosEps + cos(betaRad) * sinEps * sinLambda).coerceIn(-1.0, 1.0))

        val tUt = (jdUt - 2451545.0) / 36525.0
        val gmstDeg = normalize360(
            280.46061837 +
                360.98564736629 * (jdUt - 2451545.0) +
                0.000387933 * tUt * tUt -
                tUt * tUt * tUt / 38710000.0
        )
        val gastDeg = normalize360(gmstDeg + (deltaPsiArcsec / 3600.0) * cosEps)
        val lstRad = normalize360(gastDeg + geo.longitudeDeg) * DEG2RAD

        val hGeoRad = lstRad - raGeoRad

        val latRad = geo.latitudeDeg * DEG2RAD
        val bOverA = 1.0 - WGS84_FLATTENING
        val u = atan(bOverA * tan(latRad))
        val altKm = geo.altitudeMeters / 1000.0
        val rhoSinPhiPrime = bOverA * sin(u) + (altKm / EARTH_EQUATORIAL_RADIUS_KM) * sin(latRad)
        val rhoCosPhiPrime = cos(u) + (altKm / EARTH_EQUATORIAL_RADIUS_KM) * cos(latRad)

        val sinPi = (EARTH_EQUATORIAL_RADIUS_KM / distanceKm).coerceIn(-1.0, 1.0)

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

        val sinLat = sin(latRad)
        val cosLat = cos(latRad)
        val sinDec = sin(decTopoRad)
        val cosDec = cos(decTopoRad)
        val cosH = cos(hTopoRad)
        val sinH = sin(hTopoRad)

        val geomAltRad = asin((sinLat * sinDec + cosLat * cosDec * cosH).coerceIn(-1.0, 1.0))
        val geomAltDeg = geomAltRad * RAD2DEG

        val azFromSouthRad = atan2(sinH, cosH * sinLat - tan(decTopoRad) * cosLat)
        val azimuthDeg = normalize360(azFromSouthRad * RAD2DEG + 180.0)

        val apparentAltDeg = if (applyRefraction && geomAltDeg > -1.0) {
            val hClamped = geomAltDeg.coerceAtLeast(-1.0)
            val rArcmin = 1.02 / tan((hClamped + 10.3 / (hClamped + 5.11)) * DEG2RAD)
            geomAltDeg + rArcmin / 60.0
        } else {
            geomAltDeg
        }

        val parallacticAngleDeg = atan2(sinH, tan(latRad) * cosDec - sinDec * cosH) * RAD2DEG

        val (sunLonDeg, sunDistAu) = SunAstronomy.calculateGeocentricEcliptic(jde)
        val sunDistKm = sunDistAu * 149_597_870.7
        val sunLonRad = sunLonDeg * DEG2RAD
        val sunRaRad = atan2(sin(sunLonRad) * cosEps, cos(sunLonRad))
        val sunDecRad = asin((sin(sunLonRad) * sinEps).coerceIn(-1.0, 1.0))

        val cosPsi = (sin(sunDecRad) * sinDecGeo + cos(sunDecRad) * cosDecGeo * cos(sunRaRad - raGeoRad))
            .coerceIn(-1.0, 1.0)
        val psiRad = acos(cosPsi)
        val phaseAngleRad = atan2(sunDistKm * sin(psiRad), distanceKm - sunDistKm * cos(psiRad))
        val illuminatedFraction = ((1.0 + cos(phaseAngleRad)) / 2.0).coerceIn(0.0, 1.0)

        val lonDiff = normalize360(lambdaDeg - sunLonDeg)
        val isWaxing = lonDiff < 180.0
        val phaseName = classifyMoonPhase(lonDiff)

        val brightLimbRad = atan2(
            cos(sunDecRad) * sin(sunRaRad - raGeoRad),
            sin(sunDecRad) * cosDecGeo - cos(sunDecRad) * sinDecGeo * cos(sunRaRad - raGeoRad)
        )
        val brightLimbDeg = normalize360(brightLimbRad * RAD2DEG)

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

    private fun isVisibleInSector(
        pos: MoonPosition,
        sector: CompassSector,
        dipDeg: Double
    ): Boolean {
        val thresholdDeg = -(pos.angularDiameterDeg * 0.5) - dipDeg
        if (pos.altitudeDeg < thresholdDeg) return false
        val az = normalize360(pos.azimuthDeg)
        return az >= sector.startAzDeg && az < sector.endAzDeg
    }

    /**
     * Calculates when the Moon will be visible (above the observer's elevation-adjusted horizon)
     * AND inside the target compass [sector] (e.g. SE = E to S, SW = S to W).
     *
     * Scans forward up to [maxSearchDays] days (default 30 days = full lunar cycle) in 10-minute
     * steps and refines entry/exit boundaries with binary search to ~1-second precision.
     */
    fun calculateSectorVisibilityForecast(
        geo: GeoPosition,
        sector: CompassSector,
        startEpochMillis: Long,
        maxSearchDays: Int = 30
    ): MoonSectorVisibilityForecast {
        val dipDeg = calculateHorizonDipDeg(geo.altitudeMeters)
        val nowPos = calculateMoonPosition(geo, startEpochMillis, applyRefraction = true)
        val thresholdDeg = -(nowPos.angularDiameterDeg * 0.5) - dipDeg
        val currentlyInSector = isVisibleInSector(nowPos, sector, dipDeg)

        val stepMillis = 10 * 60_000L
        val endMillis = startEpochMillis + maxSearchDays * 86_400_000L

        var windowStart: Long? = if (currentlyInSector) startEpochMillis else null
        var windowEnd: Long? = null

        var prevT = startEpochMillis
        var prevIn = currentlyInSector

        var t = startEpochMillis + stepMillis
        while (t <= endMillis) {
            val pos = calculateMoonPosition(geo, t, applyRefraction = true)
            val currIn = isVisibleInSector(pos, sector, dipDeg)

            if (!prevIn && currIn && windowStart == null) {
                windowStart = bisectSectorTransition(geo, sector, prevT, t, dipDeg, entering = true)
            } else if (prevIn && !currIn && windowStart != null && windowEnd == null) {
                windowEnd = bisectSectorTransition(geo, sector, prevT, t, dipDeg, entering = false)
                break
            }

            prevT = t
            prevIn = currIn
            t += stepMillis
        }

        val enterPos = windowStart?.let { calculateMoonPosition(geo, it, applyRefraction = true) }
        val exitPos = windowEnd?.let { calculateMoonPosition(geo, it, applyRefraction = true) }

        var peakTime: Long? = null
        var peakAlt = -999.0
        var peakAz: Double? = null

        if (windowStart != null) {
            val scanEnd = (windowEnd ?: (windowStart + 12 * 3_600_000L))
                .coerceAtMost(windowStart + 24 * 3_600_000L)
            var scan = windowStart
            while (scan <= scanEnd) {
                val p = calculateMoonPosition(geo, scan, applyRefraction = true)
                if (p.altitudeDeg > peakAlt) {
                    peakAlt = p.altitudeDeg
                    peakAz = p.azimuthDeg
                    peakTime = scan
                }
                scan += 5 * 60_000L
            }
        }

        val summaryNote = when {
            currentlyInSector ->
                "The Moon is visible in ${sector.shortLabel} (${sector.rangeLabel}) right now."
            windowStart != null ->
                "Next visible window in ${sector.shortLabel} (${sector.rangeLabel}) found below."
            else ->
                "The Moon does not pass visibly through ${sector.shortLabel} in the next $maxSearchDays days from this latitude."
        }

        return MoonSectorVisibilityForecast(
            sector = sector,
            isCurrentlyVisibleInSector = currentlyInSector,
            currentAzimuthDeg = nowPos.azimuthDeg,
            currentAltitudeDeg = nowPos.altitudeDeg,
            observerAltitudeMeters = geo.altitudeMeters,
            horizonDipDeg = dipDeg,
            visibilityThresholdDeg = thresholdDeg,
            windowStartMillis = windowStart,
            enterAzimuthDeg = enterPos?.azimuthDeg,
            enterAltitudeDeg = enterPos?.altitudeDeg,
            windowEndMillis = windowEnd,
            exitAzimuthDeg = exitPos?.azimuthDeg,
            exitAltitudeDeg = exitPos?.altitudeDeg,
            peakMillis = peakTime,
            peakAzimuthDeg = peakAz,
            peakAltitudeDeg = if (peakTime != null) peakAlt else null,
            summaryNote = summaryNote
        )
    }

    private fun bisectSectorTransition(
        geo: GeoPosition,
        sector: CompassSector,
        tLow: Long,
        tHigh: Long,
        dipDeg: Double,
        entering: Boolean
    ): Long {
        var lo = tLow
        var hi = tHigh
        for (i in 0 until 12) {
            val mid = lo + (hi - lo) / 2L
            val pos = calculateMoonPosition(geo, mid, applyRefraction = true)
            val inside = isVisibleInSector(pos, sector, dipDeg)
            if (entering) {
                if (!inside) lo = mid else hi = mid
            } else {
                if (inside) lo = mid else hi = mid
            }
        }
        return lo + (hi - lo) / 2L
    }

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
