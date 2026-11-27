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
import com.moonartracker.astronomy.CompassSector
import com.moonartracker.astronomy.GeoPosition
import com.moonartracker.astronomy.MoonAstronomy
import com.moonartracker.astronomy.MoonPosition
import com.moonartracker.astronomy.MoonSectorVisibilityForecast
import com.moonartracker.astronomy.SkyPoint
import com.moonartracker.astronomy.SunAstronomy
import com.moonartracker.astronomy.SunPosition
import com.moonartracker.astronomy.TrackedBody
import com.moonartracker.location.LocationRepository
import com.moonartracker.sensors.OrientationSensorManager
import com.moonartracker.ui.ArOverlayCanvas
import com.moonartracker.ui.CameraPreview
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    var trackedBody by remember { mutableStateOf(TrackedBody.MOON) }
    var showHorizon by remember { mutableStateOf(true) }
    var showTrajectory by remember { mutableStateOf(true) }
    var showManualDialog by remember { mutableStateOf(false) }
    var sectorForecast by remember { mutableStateOf<MoonSectorVisibilityForecast?>(null) }

    var moonPosition by remember { mutableStateOf<MoonPosition?>(null) }
    var sunPosition by remember { mutableStateOf<SunPosition?>(null) }
    var trajectory by remember { mutableStateOf<List<SkyPoint>>(emptyList()) }

    val cameraPointing = if (orientationState.isReady) {
        ArProjection.getCameraPointingAzAlt(
            orientationState.rotationMatrix3x3,
            orientationState.magneticDeclinationDeg
        )
    } else {
        null
    }
    val aimedSector = cameraPointing?.let { (az, _) -> CompassSector.fromAzimuth(az) } ?: CompassSector.SE

    // Real-time 1 Hz ephemeris loop for Moon & Sun + active body trajectory
    LaunchedEffect(locationState.position, trackedBody) {
        val geo = locationState.position ?: return@LaunchedEffect
        var lastTrajectoryCalc = 0L
        while (true) {
            val now = System.currentTimeMillis()
            orientationManager.updateGeomagneticDeclination(geo, now)
            moonPosition = MoonAstronomy.calculateMoonPosition(geo, now, applyRefraction = true)
            sunPosition = SunAstronomy.calculateSunPosition(geo, now, applyRefraction = true)

            if (now - lastTrajectoryCalc > 60_000L || trajectory.isEmpty()) {
                trajectory = when (trackedBody) {
                    TrackedBody.MOON -> MoonAstronomy.calculateTrajectory(geo, now)
                    TrackedBody.SUN -> SunAstronomy.calculateTrajectory(geo, now)
                }
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
                    trackedBody = trackedBody,
                    orientation = orientationState,
                    moonPosition = moonPosition,
                    sunPosition = sunPosition,
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
                    CelestialTelemetryCard(
                        trackedBody = trackedBody,
                        moon = moonPosition,
                        sun = sunPosition,
                        geo = locationState.position,
                        locationStatus = locationState.statusText,
                        declinationDeg = orientationState.magneticDeclinationDeg,
                        cameraPointing = cameraPointing,
                        aimedSector = aimedSector
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

                // Bottom AR Controls (2 clean rows)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .safeDrawingPadding()
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .align(Alignment.BottomCenter),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                trackedBody = if (trackedBody == TrackedBody.MOON) {
                                    TrackedBody.SUN
                                } else {
                                    TrackedBody.MOON
                                }
                                trajectory = emptyList()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (trackedBody == TrackedBody.SUN) {
                                    Color(0xDD475569)
                                } else {
                                    Color(0xDDEA580C)
                                },
                                contentColor = Color.White
                            )
                        ) {
                            Text(
                                text = if (trackedBody == TrackedBody.MOON) "Show Sun" else "Show Moon",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Button(
                            onClick = {
                                val geo = locationState.position
                                if (geo != null) {
                                    sectorForecast = MoonAstronomy.calculateSectorVisibilityForecast(
                                        geo = geo,
                                        sector = aimedSector,
                                        startEpochMillis = System.currentTimeMillis()
                                    )
                                } else {
                                    showManualDialog = true
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xDD1D4ED8),
                                contentColor = Color.White
                            )
                        ) {
                            Text(
                                text = "Moon in ${aimedSector.shortLabel}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
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
                                selectedContainerColor = if (trackedBody == TrackedBody.SUN) {
                                    Color(0xCCEA580C)
                                } else {
                                    Color(0xCCD97706)
                                },
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
    }

    sectorForecast?.let { forecast ->
        MoonSectorVisibilityDialog(
            forecast = forecast,
            geo = locationState.position,
            onSelectSector = { newSector ->
                val geo = locationState.position
                if (geo != null) {
                    sectorForecast = MoonAstronomy.calculateSectorVisibilityForecast(
                        geo = geo,
                        sector = newSector,
                        startEpochMillis = System.currentTimeMillis()
                    )
                }
            },
            onDismiss = { sectorForecast = null }
        )
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
private fun CelestialTelemetryCard(
    trackedBody: TrackedBody,
    moon: MoonPosition?,
    sun: SunPosition?,
    geo: GeoPosition?,
    locationStatus: String,
    declinationDeg: Double,
    cameraPointing: Pair<Double, Double>?,
    aimedSector: CompassSector
) {
    val headerTitle = if (trackedBody == TrackedBody.SUN) "SUN AR TRACKER" else "MOON AR TRACKER"
    val headerColor = if (trackedBody == TrackedBody.SUN) Color(0xFFFFB74D) else Color(0xFFFFD54F)
    val activeAlt = if (trackedBody == TrackedBody.SUN) sun?.altitudeDeg else moon?.altitudeDeg

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
                text = headerTitle,
                color = headerColor,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = when {
                    activeAlt == null -> locationStatus
                    activeAlt >= 0.0 -> "ABOVE HORIZON"
                    else -> "BELOW HORIZON"
                },
                color = when {
                    activeAlt == null -> Color(0xFF94A3B8)
                    activeAlt >= 0.0 -> Color(0xFF4ADE80)
                    else -> Color(0xFFF87171)
                },
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        if (trackedBody == TrackedBody.MOON && moon != null) {
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
        } else if (trackedBody == TrackedBody.SUN && sun != null) {
            Text(
                text = String.format(
                    "Sun   Az: %6.2f deg   Alt: %+6.2f deg   Dist: %.4f AU",
                    sun.azimuthDeg,
                    sun.altitudeDeg,
                    sun.distanceAu
                ),
                color = Color.White,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = String.format(
                    "Sky: %s   RA: %.1f deg  Dec: %+.1f deg",
                    sun.twilightPhase,
                    sun.rightAscensionDeg,
                    sun.declinationDeg
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
                    String.format("Aim: %s %.0f deg / %+.0f deg (Decl %+.1f)", aimedSector.shortLabel, az, alt, declinationDeg)
                } ?: "",
                color = Color(0xFF94A3B8),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun MoonSectorVisibilityDialog(
    forecast: MoonSectorVisibilityForecast,
    geo: GeoPosition?,
    onSelectSector: (CompassSector) -> Unit,
    onDismiss: () -> Unit
) {
    val timeFmt = remember { SimpleDateFormat("EEE, MMM d  HH:mm", Locale.getDefault()) }
    val now = System.currentTimeMillis()

    fun formatDuration(diffMillis: Long): String {
        val diffMin = (diffMillis / 60_000L).coerceAtLeast(0L)
        val days = diffMin / 1440L
        val hours = (diffMin % 1440L) / 60L
        val mins = diffMin % 60L
        return when {
            days > 0 -> "${days}d ${hours}h ${mins}m"
            hours > 0 -> "${hours}h ${mins}m"
            else -> "${mins}m"
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Moon in ${forecast.sector.shortLabel} (${forecast.sector.rangeLabel})",
                color = if (forecast.isCurrentlyVisibleInSector) Color(0xFF4ADE80) else Color(0xFFFFD54F),
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Quick quadrant selector row (defaults to current camera aim)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (sec in CompassSector.entries) {
                        FilterChip(
                            selected = sec == forecast.sector,
                            onClick = { onSelectSector(sec) },
                            label = { Text(sec.shortLabel, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF1D4ED8),
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }

                Text(
                    text = forecast.summaryNote,
                    color = Color(0xFFE2E8F0),
                    fontSize = 13.sp
                )

                geo?.let {
                    Text(
                        text = String.format(
                            "Pos: %.4f, %.4f | Height: %.1f m\nHorizon Dip: -%.3f deg (Threshold: %.2f deg)",
                            it.latitudeDeg,
                            it.longitudeDeg,
                            forecast.observerAltitudeMeters,
                            forecast.horizonDipDeg,
                            forecast.visibilityThresholdDeg
                        ),
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Text(
                    text = String.format(
                        "Moon Now: Az %.1f deg | Alt %+.1f deg",
                        forecast.currentAzimuthDeg,
                        forecast.currentAltitudeDeg
                    ),
                    color = Color.White,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )

                forecast.windowStartMillis?.let { startMs ->
                    val enterMeta = if (forecast.enterAzimuthDeg != null && forecast.enterAltitudeDeg != null) {
                        String.format("Az %.1f deg, Alt %+.1f deg", forecast.enterAzimuthDeg, forecast.enterAltitudeDeg)
                    } else {
                        ""
                    }
                    val startLabel = if (forecast.isCurrentlyVisibleInSector) {
                        "Visible Now ($enterMeta)"
                    } else {
                        "Enters ${forecast.sector.shortLabel}:\n${timeFmt.format(Date(startMs))} (in ${formatDuration(startMs - now)})\n$enterMeta"
                    }
                    Text(
                        text = startLabel,
                        color = Color(0xFFFFE082),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                forecast.peakMillis?.let { peakMs ->
                    val peakMeta = if (forecast.peakAzimuthDeg != null && forecast.peakAltitudeDeg != null) {
                        String.format("Max Alt %+.1f deg at Az %.1f deg", forecast.peakAltitudeDeg, forecast.peakAzimuthDeg)
                    } else {
                        ""
                    }
                    Text(
                        text = "Peak in ${forecast.sector.shortLabel}:\n${timeFmt.format(Date(peakMs))}\n$peakMeta",
                        color = Color(0xFFCBD5E1),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                forecast.windowEndMillis?.let { endMs ->
                    val durationStr = forecast.windowStartMillis?.let { startMs ->
                        " (visible ${formatDuration(endMs - startMs)})"
                    } ?: ""
                    val exitMeta = if (forecast.exitAzimuthDeg != null && forecast.exitAltitudeDeg != null) {
                        String.format("Az %.1f deg, Alt %+.1f deg", forecast.exitAzimuthDeg, forecast.exitAltitudeDeg)
                    } else {
                        ""
                    }
                    Text(
                        text = "Exits ${forecast.sector.shortLabel}:\n${timeFmt.format(Date(endMs))}$durationStr\n$exitMeta",
                        color = Color(0xFF93C5FD),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
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
            text = "Camera and Location permissions are needed to compute the Moon and Sun positions and overlay them in real time.",
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
        title = { Text("Observer Geoposition & Height") },
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
                    label = { Text("Height / Altitude (meters)") },
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
