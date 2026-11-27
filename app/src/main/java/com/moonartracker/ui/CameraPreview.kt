package com.moonartracker.ui

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.view.Surface
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.moonartracker.ar.CameraFov
import kotlin.math.PI
import kotlin.math.atan

/**
 * Full-screen CameraX rear camera preview that also inspects Camera2 lens & sensor
 * characteristics to report the device's true horizontal & vertical Field of View.
 */
@OptIn(ExperimentalCamera2Interop::class)
@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    displayRotation: Int,
    onFovMeasured: (CameraFov) -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current

    AndroidView(
        modifier = modifier,
        factory = { context: Context ->
            val previewView = PreviewView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }

            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                try {
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val selector = CameraSelector.DEFAULT_BACK_CAMERA
                    cameraProvider.unbindAll()
                    val camera = cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview)

                    val camera2Info = Camera2CameraInfo.from(camera.cameraInfo)
                    val focalLengths = camera2Info.getCameraCharacteristic(
                        CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
                    )
                    val sensorSize = camera2Info.getCameraCharacteristic(
                        CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE
                    )

                    if (focalLengths != null && focalLengths.isNotEmpty() && sensorSize != null) {
                        val f = focalLengths[0].toDouble()
                        val sensorW = sensorSize.width.toDouble()
                        val sensorH = sensorSize.height.toDouble()
                        if (f > 0.0 && sensorW > 0.0 && sensorH > 0.0) {
                            val fovLongDeg = 2.0 * atan(sensorW / (2.0 * f)) * (180.0 / PI)
                            val fovShortDeg = 2.0 * atan(sensorH / (2.0 * f)) * (180.0 / PI)
                            val isPortrait = displayRotation == Surface.ROTATION_0 ||
                                displayRotation == Surface.ROTATION_180
                            val fov = if (isPortrait) {
                                CameraFov(horizontalFovDeg = fovShortDeg, verticalFovDeg = fovLongDeg)
                            } else {
                                CameraFov(horizontalFovDeg = fovLongDeg, verticalFovDeg = fovShortDeg)
                            }
                            onFovMeasured(fov)
                        }
                    }
                } catch (_: Exception) {
                    // Keep default CameraFov fallback
                }
            }, ContextCompat.getMainExecutor(context))

            previewView
        }
    )
}
