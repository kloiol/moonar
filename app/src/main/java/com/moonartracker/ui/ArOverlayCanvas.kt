package com.moonartracker.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import com.moonartracker.ar.ArProjection
import com.moonartracker.ar.CameraFov
import com.moonartracker.astronomy.MoonPosition
import com.moonartracker.astronomy.SkyPoint
import com.moonartracker.sensors.OrientationState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private val CARDINAL_LABELS = listOf(
    0.0 to "N",
    45.0 to "NE",
    90.0 to "E",
    135.0 to "SE",
    180.0 to "S",
    225.0 to "SW",
    270.0 to "W",
    315.0 to "NW"
)

/**
 * Real-time AR Canvas overlay that projects the Moon, its true illuminated phase,
 * its 24-hour trajectory arc, the horizon line, cardinal directions, and off-screen
 * directional guidance onto the live camera feed.
 */
@Composable
fun ArOverlayCanvas(
    modifier: Modifier = Modifier,
    orientation: OrientationState,
    moonPosition: MoonPosition?,
    trajectory: List<SkyPoint>,
    cameraFov: CameraFov,
    showHorizon: Boolean,
    showTrajectory: Boolean
) {
    Canvas(modifier = modifier) {
        if (!orientation.isReady || moonPosition == null) return@Canvas

        val w = size.width
        val h = size.height
        val matrix = orientation.rotationMatrix3x3
        val decl = orientation.magneticDeclinationDeg

        // 1. Center aiming crosshair
        drawCrosshair(w, h)

        // 2. Projected 3D horizon line & cardinal directions
        if (showHorizon) {
            drawHorizonAndCardinals(matrix, decl, w, h, cameraFov)
        }

        // 3. Projected 24-hour lunar trajectory arc
        if (showTrajectory && trajectory.size >= 2) {
            drawMoonTrajectory(trajectory, matrix, decl, w, h, cameraFov)
        }

        // 4. Projected Moon position (on-screen render or off-screen guidance arrow)
        val moonProj = ArProjection.projectCelestialPoint(
            azimuthTrueDeg = moonPosition.azimuthDeg,
            altitudeDeg = moonPosition.altitudeDeg,
            magneticDeclinationDeg = decl,
            deviceToWorldMatrix3x3 = matrix,
            screenWidthPx = w,
            screenHeightPx = h,
            cameraFov = cameraFov
        )

        if (moonProj.isOnScreen) {
            drawMoonOverlay(
                center = Offset(moonProj.screenX, moonProj.screenY),
                moon = moonPosition,
                pixelsPerDegree = w / cameraFov.horizontalFovDeg.toFloat()
            )
        } else {
            drawOffscreenGuidance(
                w = w,
                h = h,
                directionRad = moonProj.edgeDirectionRad,
                angularOffsetDeg = moonProj.angularOffsetDeg,
                isBelowHorizon = moonPosition.altitudeDeg < 0.0
            )
        }
    }
}

private fun DrawScope.drawCrosshair(w: Float, h: Float) {
    val cx = w * 0.5f
    val cy = h * 0.5f
    val gap = 12f
    val arm = 26f
    val color = Color.White.copy(alpha = 0.45f)
    drawLine(color, Offset(cx - arm, cy), Offset(cx - gap, cy), strokeWidth = 2f)
    drawLine(color, Offset(cx + gap, cy), Offset(cx + arm, cy), strokeWidth = 2f)
    drawLine(color, Offset(cx, cy - arm), Offset(cx, cy - gap), strokeWidth = 2f)
    drawLine(color, Offset(cx, cy + gap), Offset(cx, cy + arm), strokeWidth = 2f)
}

private fun DrawScope.drawHorizonAndCardinals(
    matrix: FloatArray,
    declinationDeg: Double,
    w: Float,
    h: Float,
    fov: CameraFov
) {
    val horizonColor = Color(0xFF4FC3F7).copy(alpha = 0.50f)
    val dashEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 12f), 0f)

    // Sample horizon every 5 degrees in azimuth
    var prevPt: Offset? = null
    for (azStep in 0..360 step 5) {
        val proj = ArProjection.projectCelestialPoint(
            azimuthTrueDeg = azStep.toDouble(),
            altitudeDeg = 0.0,
            magneticDeclinationDeg = declinationDeg,
            deviceToWorldMatrix3x3 = matrix,
            screenWidthPx = w,
            screenHeightPx = h,
            cameraFov = fov
        )
        if (proj.isInFront && proj.screenX > -w && proj.screenX < 2 * w &&
            proj.screenY > -h && proj.screenY < 2 * h
        ) {
            val curr = Offset(proj.screenX, proj.screenY)
            prevPt?.let { prev ->
                if ((curr - prev).getDistance() < w * 0.5f) {
                    drawLine(
                        color = horizonColor,
                        start = prev,
                        end = curr,
                        strokeWidth = 2.5f,
                        pathEffect = dashEffect
                    )
                }
            }
            prevPt = curr
        } else {
            prevPt = null
        }
    }

    val textPaint = Paint().apply {
        color = android.graphics.Color.argb(220, 130, 220, 255)
        textSize = 34f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
        setShadowLayer(6f, 0f, 0f, android.graphics.Color.BLACK)
    }

    for ((az, label) in CARDINAL_LABELS) {
        val proj = ArProjection.projectCelestialPoint(
            azimuthTrueDeg = az,
            altitudeDeg = 0.0,
            magneticDeclinationDeg = declinationDeg,
            deviceToWorldMatrix3x3 = matrix,
            screenWidthPx = w,
            screenHeightPx = h,
            cameraFov = fov
        )
        if (proj.isOnScreen) {
            drawLine(
                color = Color(0xFF81D4FA),
                start = Offset(proj.screenX, proj.screenY - 14f),
                end = Offset(proj.screenX, proj.screenY + 14f),
                strokeWidth = 3f
            )
            drawContext.canvas.nativeCanvas.drawText(
                label,
                proj.screenX,
                proj.screenY - 22f,
                textPaint
            )
        }
    }
}

private fun DrawScope.drawMoonTrajectory(
    trajectory: List<SkyPoint>,
    matrix: FloatArray,
    declinationDeg: Double,
    w: Float,
    h: Float,
    fov: CameraFov
) {
    val aboveColor = Color(0xFFFFD54F).copy(alpha = 0.65f)
    val belowColor = Color(0xFF90A4AE).copy(alpha = 0.35f)
    val dashedBelow = PathEffect.dashPathEffect(floatArrayOf(10f, 14f), 0f)

    var prevPt: Offset? = null
    for (pt in trajectory) {
        val proj = ArProjection.projectCelestialPoint(
            azimuthTrueDeg = pt.azimuthDeg,
            altitudeDeg = pt.altitudeDeg,
            magneticDeclinationDeg = declinationDeg,
            deviceToWorldMatrix3x3 = matrix,
            screenWidthPx = w,
            screenHeightPx = h,
            cameraFov = fov
        )
        if (proj.isInFront && proj.screenX > -w && proj.screenX < 2 * w &&
            proj.screenY > -h && proj.screenY < 2 * h
        ) {
            val curr = Offset(proj.screenX, proj.screenY)
            prevPt?.let { prev ->
                if ((curr - prev).getDistance() < w * 0.5f) {
                    val isAbove = pt.altitudeDeg >= 0.0
                    drawLine(
                        color = if (isAbove) aboveColor else belowColor,
                        start = prev,
                        end = curr,
                        strokeWidth = if (isAbove) 4f else 2.5f,
                        cap = StrokeCap.Round,
                        pathEffect = if (isAbove) null else dashedBelow
                    )
                }
            }
            if (proj.isOnScreen) {
                drawCircle(
                    color = if (pt.altitudeDeg >= 0.0) Color(0xFFFFE082) else Color(0xFF90A4AE),
                    radius = 4.5f,
                    center = curr
                )
            }
            prevPt = curr
        } else {
            prevPt = null
        }
    }
}

private fun DrawScope.drawMoonOverlay(
    center: Offset,
    moon: MoonPosition,
    pixelsPerDegree: Float
) {
    val isBelow = moon.altitudeDeg < 0.0
    // Real angular radius in pixels, clamped to a minimum visible size for easy AR tracking
    val trueRadiusPx = (moon.angularDiameterDeg.toFloat() * 0.5f) * pixelsPerDegree
    val displayRadius = trueRadiusPx.coerceIn(34f, 68f)

    val glowColor = if (isBelow) Color(0xFF90A4AE) else Color(0xFFFFF59D)

    // Soft atmospheric glow halo
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                glowColor.copy(alpha = if (isBelow) 0.25f else 0.45f),
                glowColor.copy(alpha = 0.0f)
            ),
            center = center,
            radius = displayRadius * 2.4f
        ),
        radius = displayRadius * 2.4f,
        center = center
    )

    // Illuminated lunar disk rotated to match bright-limb orientation relative to zenith
    val limbRotationDeg = (moon.brightLimbAngleDeg - moon.parallacticAngleDeg).toFloat()
    drawMoonPhaseDisk(
        center = center,
        radius = displayRadius,
        illuminatedFraction = moon.illuminatedFraction.toFloat(),
        rotationDeg = limbRotationDeg,
        dimmed = isBelow
    )

    // Outer AR targeting reticle
    val ringRadius = displayRadius + 16f
    val ringColor = if (isBelow) Color(0xFFB0BEC5).copy(alpha = 0.7f) else Color(0xFFFFD54F)
    drawCircle(
        color = ringColor,
        radius = ringRadius,
        center = center,
        style = Stroke(
            width = 3f,
            pathEffect = if (isBelow) PathEffect.dashPathEffect(floatArrayOf(12f, 10f)) else null
        )
    )

    // Label below Moon reticle
    val labelPaint = Paint().apply {
        color = if (isBelow) android.graphics.Color.LTGRAY else android.graphics.Color.WHITE
        textSize = 32f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
        setShadowLayer(8f, 0f, 0f, android.graphics.Color.BLACK)
    }
    val subPaint = Paint().apply {
        color = if (isBelow) android.graphics.Color.LTGRAY else android.graphics.Color.rgb(255, 224, 130)
        textSize = 26f
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
        setShadowLayer(6f, 0f, 0f, android.graphics.Color.BLACK)
    }

    val title = if (isBelow) "MOON (BELOW HORIZON)" else "MOON"
    val coords = String.format(
        "Az %.1f deg | Alt %.1f deg | %.0f%%",
        moon.azimuthDeg,
        moon.altitudeDeg,
        moon.illuminatedFraction * 100.0
    )
    drawContext.canvas.nativeCanvas.drawText(title, center.x, center.y + ringRadius + 38f, labelPaint)
    drawContext.canvas.nativeCanvas.drawText(coords, center.x, center.y + ringRadius + 70f, subPaint)
}

/**
 * Draws a geometrically accurate lunar phase disk where the bright limb faces
 * along [rotationDeg] (0 deg = Up/Zenith, clockwise).
 */
private fun DrawScope.drawMoonPhaseDisk(
    center: Offset,
    radius: Float,
    illuminatedFraction: Float,
    rotationDeg: Float,
    dimmed: Boolean
) {
    val darkColor = Color(0xFF1A202C).copy(alpha = if (dimmed) 0.7f else 0.92f)
    val litColor = if (dimmed) Color(0xFFCFD8DC) else Color(0xFFFFF8E1)

    withTransform({
        rotate(degrees = rotationDeg - 90f, pivot = center)
    }) {
        // Dark base disk
        drawCircle(color = darkColor, radius = radius, center = center)

        val k = illuminatedFraction.coerceIn(0f, 1f)
        if (k > 0.01f) {
            // Right semicircle (bright limb side)
            val rect = Rect(
                offset = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2f, radius * 2f)
            )
            drawArc(
                color = litColor,
                startAngle = -90f,
                sweepAngle = 180f,
                useCenter = true,
                topLeft = rect.topLeft,
                size = rect.size
            )

            // Terminator ellipse
            val terminatorScale = abs(2f * k - 1f)
            val ellipseWidth = (radius * 2f * terminatorScale).coerceAtLeast(0.5f)
            val ellipseRect = Rect(
                left = center.x - ellipseWidth * 0.5f,
                top = center.y - radius,
                right = center.x + ellipseWidth * 0.5f,
                bottom = center.y + radius
            )
            drawOval(
                color = if (k >= 0.5f) litColor else darkColor,
                topLeft = ellipseRect.topLeft,
                size = ellipseRect.size
            )
        }
    }
}

private fun DrawScope.drawOffscreenGuidance(
    w: Float,
    h: Float,
    directionRad: Float,
    angularOffsetDeg: Double,
    isBelowHorizon: Boolean
) {
    val cx = w * 0.5f
    val cy = h * 0.5f
    val rx = w * 0.40f
    val ry = min(h * 0.34f, h * 0.5f - 180f).coerceAtLeast(100f)

    val tipX = cx + cos(directionRad) * rx
    val tipY = cy + sin(directionRad) * ry

    val arrowColor = if (isBelowHorizon) Color(0xFF90A4AE) else Color(0xFFFFD54F)
    val arrowSize = 34f
    val backAngle1 = directionRad + (150.0 * PI / 180.0).toFloat()
    val backAngle2 = directionRad - (150.0 * PI / 180.0).toFloat()

    val path = Path().apply {
        moveTo(tipX, tipY)
        lineTo(tipX + cos(backAngle1) * arrowSize, tipY + sin(backAngle1) * arrowSize)
        lineTo(tipX + cos(backAngle2) * arrowSize, tipY + sin(backAngle2) * arrowSize)
        close()
    }
    drawPath(path = path, color = arrowColor)

    val labelDist = 56f
    val labelX = tipX - cos(directionRad) * labelDist
    val labelY = tipY - sin(directionRad) * labelDist + 10f

    val paint = Paint().apply {
        color = if (isBelowHorizon) android.graphics.Color.LTGRAY else android.graphics.Color.rgb(255, 224, 130)
        textSize = 30f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
        setShadowLayer(8f, 0f, 0f, android.graphics.Color.BLACK)
    }

    val text = String.format("Moon %.0f deg", angularOffsetDeg)
    drawContext.canvas.nativeCanvas.drawText(text, labelX, labelY, paint)
}
