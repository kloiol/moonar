package com.moonartracker.ar

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

data class ProjectedPoint(
    val screenX: Float,
    val screenY: Float,
    val isInFront: Boolean,
    val isOnScreen: Boolean,
    val angularOffsetDeg: Double, // Angle between camera optical axis (-Z) and target
    val edgeDirectionRad: Float   // Direction from screen center toward off-screen target
)

data class CameraFov(
    val horizontalFovDeg: Double = 55.0,
    val verticalFovDeg: Double = 70.0
)

/**
 * Pure 3D-to-2D celestial AR projection math.
 *
 * Maps a celestial target given by True North Azimuth (deg) and Altitude (deg)
 * into screen pixel coordinates using the Android device rotation matrix
 * (mapping device coordinates -> Magnetic ENU world coordinates) and the
 * local magnetic declination.
 */
object ArProjection {

    private const val DEG2RAD = PI / 180.0
    private const val RAD2DEG = 180.0 / PI

    /**
     * Converts True North (azimuthDeg, altitudeDeg) into a unit vector in
     * Magnetic ENU coordinates (X = Magnetic East, Y = Magnetic North, Z = Up).
     *
     * Since True Azimuth = Magnetic Azimuth + Declination (east positive),
     * Magnetic Azimuth = True Azimuth - Declination.
     */
    fun celestialToMagneticEnu(
        azimuthTrueDeg: Double,
        altitudeDeg: Double,
        magneticDeclinationDeg: Double
    ): FloatArray {
        val azMagRad = (azimuthTrueDeg - magneticDeclinationDeg) * DEG2RAD
        val altRad = altitudeDeg * DEG2RAD
        val cosAlt = cos(altRad)
        val east = (cosAlt * sin(azMagRad)).toFloat()
        val north = (cosAlt * cos(azMagRad)).toFloat()
        val up = sin(altRad).toFloat()
        return floatArrayOf(east, north, up)
    }

    /**
     * Projects a celestial target onto the camera screen using a 3x3 rotation matrix
     * [worldToDeviceRotation] (row-major 9 floats) where Android's `rotationMatrix` R
     * transforms device -> world, so R^T transforms world -> device.
     *
     * In device screen coordinates (already remapped for current display rotation):
     *   +X = right edge of screen
     *   +Y = top edge of screen
     *   -Z = out the back of the device (rear camera optical axis)
     */
    fun projectCelestialPoint(
        azimuthTrueDeg: Double,
        altitudeDeg: Double,
        magneticDeclinationDeg: Double,
        deviceToWorldMatrix3x3: FloatArray,
        screenWidthPx: Float,
        screenHeightPx: Float,
        cameraFov: CameraFov
    ): ProjectedPoint {
        val worldVec = celestialToMagneticEnu(azimuthTrueDeg, altitudeDeg, magneticDeclinationDeg)

        // Transform world -> device using transpose of deviceToWorldMatrix3x3
        val devX = deviceToWorldMatrix3x3[0] * worldVec[0] +
            deviceToWorldMatrix3x3[3] * worldVec[1] +
            deviceToWorldMatrix3x3[6] * worldVec[2]
        val devY = deviceToWorldMatrix3x3[1] * worldVec[0] +
            deviceToWorldMatrix3x3[4] * worldVec[1] +
            deviceToWorldMatrix3x3[7] * worldVec[2]
        val devZ = deviceToWorldMatrix3x3[2] * worldVec[0] +
            deviceToWorldMatrix3x3[5] * worldVec[1] +
            deviceToWorldMatrix3x3[8] * worldVec[2]

        // Camera optical axis is -Z in device coordinates
        val forward = -devZ
        val isInFront = forward > 0.05f
        val angularOffsetDeg = acos(forward.toDouble().coerceIn(-1.0, 1.0)) * RAD2DEG

        val halfW = screenWidthPx * 0.5f
        val halfH = screenHeightPx * 0.5f
        val tanHalfHFov = tan((cameraFov.horizontalFovDeg * 0.5) * DEG2RAD).toFloat()
        val tanHalfVFov = tan((cameraFov.verticalFovDeg * 0.5) * DEG2RAD).toFloat()

        // Screen Y grows downward while device +Y grows upward
        val edgeDirectionRad = atan2(-devY, devX)

        if (!isInFront) {
            return ProjectedPoint(
                screenX = halfW + cos(edgeDirectionRad) * screenWidthPx,
                screenY = halfH + sin(edgeDirectionRad) * screenHeightPx,
                isInFront = false,
                isOnScreen = false,
                angularOffsetDeg = angularOffsetDeg,
                edgeDirectionRad = edgeDirectionRad
            )
        }

        val ndcX = (devX / forward) / tanHalfHFov
        val ndcY = (devY / forward) / tanHalfVFov

        val screenX = halfW + ndcX * halfW
        val screenY = halfH - ndcY * halfH

        val margin = 40f
        val isOnScreen = screenX >= -margin && screenX <= screenWidthPx + margin &&
            screenY >= -margin && screenY <= screenHeightPx + margin

        return ProjectedPoint(
            screenX = screenX,
            screenY = screenY,
            isInFront = true,
            isOnScreen = isOnScreen,
            angularOffsetDeg = angularOffsetDeg,
            edgeDirectionRad = edgeDirectionRad
        )
    }

    /**
     * Computes camera center azimuth (True North, deg) and pitch/altitude (deg)
     * for displaying live phone pointing telemetry.
     */
    fun getCameraPointingAzAlt(
        deviceToWorldMatrix3x3: FloatArray,
        magneticDeclinationDeg: Double
    ): Pair<Double, Double> {
        // Rear camera optical axis is (0, 0, -1) in device frame.
        // Multiply by deviceToWorldMatrix3x3 to get world vector:
        val east = -deviceToWorldMatrix3x3[2].toDouble()
        val north = -deviceToWorldMatrix3x3[5].toDouble()
        val up = -deviceToWorldMatrix3x3[8].toDouble()

        val altDeg = acos((-up).coerceIn(-1.0, 1.0)) * RAD2DEG - 90.0
        var azMagDeg = atan2(east, north) * RAD2DEG
        if (azMagDeg < 0.0) azMagDeg += 360.0
        var azTrueDeg = (azMagDeg + magneticDeclinationDeg) % 360.0
        if (azTrueDeg < 0.0) azTrueDeg += 360.0

        return Pair(azTrueDeg, altDeg)
    }
}
