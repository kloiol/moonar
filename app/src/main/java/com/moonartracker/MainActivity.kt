package com.moonartracker

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.SensorManager
import android.os.Bundle
import android.view.Surface
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface as ComposeSurface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.moonartracker.ar.ArProjection
import com.moonartracker.ar.CameraFov
import com.moonartracker.astronomy.GeoPosition
import com.moonartracker.astronomy.MoonAstronomy
import com.moonartracker.astronomy.MoonPosition
import com.moonartracker.astronomy.SkyPoint
import com.moonartracker.location.LocationRepository
import com.moonartracker.sensors.OrientationSensorManager
import com.moonartracker.ui.ArOverlayCanvas
import com.moonartracker.ui.CameraPreview
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private lateinit var orientationManager: OrientationSensorManager
    private lateinit var locationRepository: LocationRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        orientationManager = OrientationSensorManager(this)
        locationRepository = LocationRepository(this)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                MoonArScreen(
                    orientationManager = orientationManager,
                    locationRepository = locationRepository,
                    displayRotation = getCurrentDisplayRotation()
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun getCurrentDisplayRotation(): Int {
        return try {
            display?.rotation ?: windowManager.defaultDisplay.rotation
        } catch (_: Exception) {
            Surface.ROTATION_0
        }
    }

    override fun onResume() {
        super.onResume()
        orientationManager.displayRotation = getCurrentDisplayRotation()
        orientationManager.start()
    }

    override fun onPause() {
        super.onPause()
        orientationManager.stop()
        locationRepository.stopUpdates()
    }
}

@Composable
private fun MoonArScreen(
    orientationManager: OrientationSensorManager,
    locationRepository: LocationRepository,
    displayRotation: Int
) {
    val context = LocalContext.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasCameraPermission = results[Manifest.permission.CAMERA] == true || hasCameraPermission
        hasLocationPermission = results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            results[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
            hasLocationPermission
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission || !hasLocationPermission) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    DisposableEffect(hasLocationPermission) {
        if (hasLocationPermission) {
            locationRepository.startUpdates()
        }
        onDispose {
            locationRepository.stopUpdates()
        }
    }

    val orientationState by orientationManager.state.collectAsState()
    val locationState by locationRepository.state.collectAsState()

    var cameraFov by remember { mutableStateOf(CameraFov()) }
    var showHorizon by remember { mutableStateOf(true) }
    var showTrajectory by remember { mutableStateOf(true) }
    var showManualDialog by remember { mutableStateOf(false) }

    var moonPosition by remember { mutableStateOf<MoonPosition?>(null) }
    var trajectory by remember { mutableStateOf<List<SkyPoint>>(emptyList()) }

    // Real-time 1 Hz lunar ephemeris calculation loop
    LaunchedEffect(locationState.position) {
        val geo = locationState.position ?: return@LaunchedEffect
        var lastTrajectoryCalc = 0L
        while (true) {
            val now = System.currentTimeMillis()
            orientationManager.updateGeomagneticDeclination(geo, now)
            moonPosition = MoonAstronomy.calculateMoonPosition(geo, now, applyRefraction = true)

            if (now - lastTrajectoryCalc > 60_000L || trajectory.isEmpty()) {
                trajectory = MoonAstronomy.calculateTrajectory(geo, now)
                lastTrajectoryCalc = now
            }
            delay(1000L)
        }
    }

    ComposeSurface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black
    ) {
        if (!hasCameraPermission) {
            PermissionPrompt(
                onRequestPermissions = {
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.CAMERA,
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }
            )
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                CameraPreview(
                    modifier = Modifier.fillMaxSize(),
                    displayRotation = displayRotation,
                    onFovMeasured = { measured -> cameraFov = measured }
                )

                ArOverlayCanvas(
                    modifier = Modifier.fillMaxSize(),
                    orientation = orientationState,
                    moonPosition = moonPosition,
                    trajectory = trajectory,
                    cameraFov = cameraFov,
                    showHorizon = showHorizon,
                    showTrajectory = showTrajectory
                )

                // Top Telemetry HUD
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .safeDrawingPadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .align(Alignment.TopCenter),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    MoonTelemetryCard(
                        moon = moonPosition,
                        geo = locationState.position,
                        locationStatus = locationState.statusText,
                        declinationDeg = orientationState.magneticDeclinationDeg,
                        cameraPointing = if (orientationState.isReady) {
                            ArProjection.getCameraPointingAzAlt(
                                orientationState.rotationMatrix3x3,
                                orientationState.magneticDeclinationDeg
                            )
                        } else {
                            null
                        }
                    )

                    if (orientationState.sensorAccuracy == SensorManager.SENSOR_STATUS_UNRELIABLE ||
                        orientationState.sensorAccuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    Color(0xCC7F1D1D),
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "Low compass accuracy — move phone in a figure-8 to calibrate",
                                color = Color(0xFFFEE2E2),
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                // Bottom AR Controls
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .safeDrawingPadding()
                        .padding(horizontal = 12.dp, vertical = 12.dp)
                        .align(Alignment.BottomCenter),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = showHorizon,
                        onClick = { showHorizon = !showHorizon },
                        label = { Text("Horizon", fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = Color(0xAA0F172A),
                            selectedContainerColor = Color(0xCC0284C7),
                            labelColor = Color.White,
                            selectedLabelColor = Color.White
                        )
                    )
                    FilterChip(
                        selected = showTrajectory,
                        onClick = { showTrajectory = !showTrajectory },
                        label = { Text("24h Path", fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = Color(0xAA0F172A),
                            selectedContainerColor = Color(0xCCD97706),
                            labelColor = Color.White,
                            selectedLabelColor = Color.White
                        )
                    )
                    OutlinedButton(
                        onClick = { showManualDialog = true },
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color(0xAA0F172A),
                            contentColor = Color.White
                        )
                    ) {
                        Text(
                            text = if (locationState.isManualOverride) "GPS: Manual" else "Set GPS",
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }

    if (showManualDialog) {
        ManualLocationDialog(
            currentPosition = locationState.position,
            isManual = locationState.isManualOverride,
            onDismiss = { showManualDialog = false },
            onApply = { geo ->
                locationRepository.setManualPosition(geo)
                showManualDialog = false
            },
            onUseLiveGps = {
                locationRepository.clearManualOverride()
                showManualDialog = false
            }
        )
    }
}

@Composable
private fun MoonTelemetryCard(
    moon: MoonPosition?,
    geo: GeoPosition?,
    locationStatus: String,
    declinationDeg: Double,
    cameraPointing: Pair<Double, Double>?
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xB80B1120), shape = RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "MOON AR TRACKER",
                color = Color(0xFFFFD54F),
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = moon?.let {
                    if (it.altitudeDeg >= 0.0) "ABOVE HORIZON" else "BELOW HORIZON"
                } ?: locationStatus,
                color = when {
                    moon == null -> Color(0xFF94A3B8)
                    moon.altitudeDeg >= 0.0 -> Color(0xFF4ADE80)
                    else -> Color(0xFFF87171)
                },
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        if (moon != null) {
            Text(
                text = String.format(
                    "Moon  Az: %6.2f deg   Alt: %+6.2f deg   Dist: %.0f km",
                    moon.azimuthDeg,
                    moon.altitudeDeg,
                    moon.distanceKm
                ),
                color = Color.White,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = String.format(
                    "Phase: %s (%.1f%% lit)   RA: %.1f deg  Dec: %+.1f deg",
                    moon.phaseName,
                    moon.illuminatedFraction * 100.0,
                    moon.rightAscensionDeg,
                    moon.declinationDeg
                ),
                color = Color(0xFFCBD5E1),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        } else {
            Text(
                text = "Waiting for geoposition (GPS or Set GPS)...",
                color = Color(0xFFCBD5E1),
                fontSize = 12.sp
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = geo?.let {
                    String.format(
                        "Pos: %.4f, %.4f (%.0fm)",
                        it.latitudeDeg,
                        it.longitudeDeg,
                        it.altitudeMeters
                    )
                } ?: "Pos: --",
                color = Color(0xFF94A3B8),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = cameraPointing?.let { (az, alt) ->
                    String.format("Cam: %.0f deg / %+.0f deg (Decl %+.1f)", az, alt, declinationDeg)
                } ?: "",
                color = Color(0xFF94A3B8),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun PermissionPrompt(onRequestPermissions: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Moon AR Tracker",
            color = Color(0xFFFFD54F),
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Camera and Location permissions are needed to compute the Moon's topocentric position and overlay it in real time.",
            color = Color(0xFFCBD5E1),
            fontSize = 14.sp
        )
        Spacer(modifier = Modifier.height(20.dp))
        Button(onClick = onRequestPermissions) {
            Text("Grant Camera & Location Access")
        }
    }
}

@Composable
private fun ManualLocationDialog(
    currentPosition: GeoPosition?,
    isManual: Boolean,
    onDismiss: () -> Unit,
    onApply: (GeoPosition) -> Unit,
    onUseLiveGps: () -> Unit
) {
    var latText by remember { mutableStateOf(currentPosition?.latitudeDeg?.toString() ?: "60.5544") }
    var lonText by remember { mutableStateOf(currentPosition?.longitudeDeg?.toString() ?: "-151.2583") }
    var altText by remember { mutableStateOf(currentPosition?.altitudeMeters?.toString() ?: "20.0") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Observer Geoposition") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = latText,
                    onValueChange = { latText = it },
                    label = { Text("Latitude (deg, -90 to 90)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = lonText,
                    onValueChange = { lonText = it },
                    label = { Text("Longitude (deg, -180 to 180)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = altText,
                    onValueChange = { altText = it },
                    label = { Text("Altitude (meters)") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val lat = latText.toDoubleOrNull()?.coerceIn(-90.0, 90.0)
                    val lon = lonText.toDoubleOrNull()?.coerceIn(-180.0, 180.0)
                    val alt = altText.toDoubleOrNull() ?: 0.0
                    if (lat != null && lon != null) {
                        onApply(GeoPosition(lat, lon, alt))
                    }
                }
            ) {
                Text("Apply")
            }
        },
        dismissButton = {
            Row {
                if (isManual) {
                    TextButton(onClick = onUseLiveGps) {
                        Text("Use Live GPS")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
}
