package com.moonartracker.sensors

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import com.moonartracker.astronomy.GeoPosition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

data class OrientationState(
    val rotationMatrix3x3: FloatArray = floatArrayOf(
        1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 1f
    ),
    val magneticDeclinationDeg: Double = 0.0,
    val sensorAccuracy: Int = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM,
    val isReady: Boolean = false
)

/**
 * Manages real-time device orientation using Android's fused TYPE_ROTATION_VECTOR sensor
 * (with automatic fallback to Accelerometer + Magnetometer), screen rotation remapping,
 * exponential matrix smoothing, and GeomagneticField true-north declination.
 */
class OrientationSensorManager(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationVectorSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accelSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private val _state = MutableStateFlow(OrientationState())
    val state: StateFlow<OrientationState> = _state.asStateFlow()

    private val rawMatrix = FloatArray(9)
    private val remappedMatrix = FloatArray(9)
    private val smoothedMatrix = floatArrayOf(
        1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 1f
    )
    private var hasInitializedSmoothing = false

    private val accelValues = FloatArray(3)
    private val magValues = FloatArray(3)
    private var hasAccel = false
    private var hasMag = false

    @Volatile
    var displayRotation: Int = Surface.ROTATION_0

    @Volatile
    private var currentDeclinationDeg: Double = 0.0

    @Volatile
    private var currentAccuracy: Int = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM

    fun updateGeomagneticDeclination(geo: GeoPosition, timeMillis: Long) {
        val field = GeomagneticField(
            geo.latitudeDeg.toFloat(),
            geo.longitudeDeg.toFloat(),
            geo.altitudeMeters.toFloat(),
            timeMillis
        )
        currentDeclinationDeg = field.declination.toDouble()
    }

    fun start() {
        hasInitializedSmoothing = false
        if (rotationVectorSensor != null) {
            sensorManager.registerListener(this, rotationVectorSensor, SensorManager.SENSOR_DELAY_GAME)
        } else {
            accelSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
            magSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rawMatrix, event.values)
                processRotationMatrix(rawMatrix)
            }
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, accelValues, 0, 3)
                hasAccel = true
                if (hasMag && SensorManager.getRotationMatrix(rawMatrix, null, accelValues, magValues)) {
                    processRotationMatrix(rawMatrix)
                }
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, magValues, 0, 3)
                hasMag = true
                if (hasAccel && SensorManager.getRotationMatrix(rawMatrix, null, accelValues, magValues)) {
                    processRotationMatrix(rawMatrix)
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        currentAccuracy = accuracy
    }

    private fun processRotationMatrix(r: FloatArray) {
        val (axisX, axisY) = when (displayRotation) {
            Surface.ROTATION_90 -> Pair(SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X)
            Surface.ROTATION_180 -> Pair(SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y)
            Surface.ROTATION_270 -> Pair(SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X)
            else -> Pair(SensorManager.AXIS_X, SensorManager.AXIS_Y)
        }
        SensorManager.remapCoordinateSystem(r, axisX, axisY, remappedMatrix)

        // Exponential smoothing + Gram-Schmidt re-orthonormalization to eliminate sensor jitter
        if (!hasInitializedSmoothing) {
            System.arraycopy(remappedMatrix, 0, smoothedMatrix, 0, 9)
            hasInitializedSmoothing = true
        } else {
            val alpha = 0.22f
            for (i in 0..8) {
                smoothedMatrix[i] = smoothedMatrix[i] * (1f - alpha) + remappedMatrix[i] * alpha
            }
            orthonormalize3x3(smoothedMatrix)
        }

        _state.value = OrientationState(
            rotationMatrix3x3 = smoothedMatrix.copyOf(),
            magneticDeclinationDeg = currentDeclinationDeg,
            sensorAccuracy = currentAccuracy,
            isReady = true
        )
    }

    private fun orthonormalize3x3(m: FloatArray) {
        // Row 0
        var r0x = m[0]; var r0y = m[1]; var r0z = m[2]
        val n0 = sqrt(r0x * r0x + r0y * r0y + r0z * r0z).coerceAtLeast(1e-6f)
        r0x /= n0; r0y /= n0; r0z /= n0

        // Row 1 orthogonalized against Row 0
        var r1x = m[3]; var r1y = m[4]; var r1z = m[5]
        val dot01 = r0x * r1x + r0y * r1y + r0z * r1z
        r1x -= dot01 * r0x; r1y -= dot01 * r0y; r1z -= dot01 * r0z
        val n1 = sqrt(r1x * r1x + r1y * r1y + r1z * r1z).coerceAtLeast(1e-6f)
        r1x /= n1; r1y /= n1; r1z /= n1

        // Row 2 = Row 0 x Row 1
        val r2x = r0y * r1z - r0z * r1y
        val r2y = r0z * r1x - r0x * r1z
        val r2z = r0x * r1y - r0y * r1x

        m[0] = r0x; m[1] = r0y; m[2] = r0z
        m[3] = r1x; m[4] = r1y; m[5] = r1z
        m[6] = r2x; m[7] = r2y; m[8] = r2z
    }
}
