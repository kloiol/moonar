package com.moonartracker.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.moonartracker.astronomy.GeoPosition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LocationState(
    val position: GeoPosition? = null,
    val isManualOverride: Boolean = false,
    val statusText: String = "Acquiring GPS fix..."
)

/**
 * Provides real-time device geoposition via Google Play Services FusedLocationProviderClient
 * with automatic fallback to Android's native LocationManager, plus optional manual coordinates.
 */
class LocationRepository(context: Context) {

    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)
    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _state = MutableStateFlow(LocationState())
    val state: StateFlow<LocationState> = _state.asStateFlow()

    private var isManual = false

    private val fusedCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            if (isManual) return
            result.lastLocation?.let { updateFromLocation(it, "GPS") }
        }
    }

    private val nativeListener = LocationListener { location ->
        if (!isManual) {
            updateFromLocation(location, "GPS (Native)")
        }
    }

    @SuppressLint("MissingPermission")
    fun startUpdates() {
        if (isManual) return
        try {
            fusedClient.lastLocation.addOnSuccessListener { loc ->
                if (!isManual && loc != null) {
                    updateFromLocation(loc, "GPS (Cached)")
                }
            }
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L)
                .setMinUpdateIntervalMillis(2000L)
                .build()
            fusedClient.requestLocationUpdates(request, fusedCallback, Looper.getMainLooper())
        } catch (_: Exception) {
            startNativeFallback()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startNativeFallback() {
        try {
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            for (provider in providers) {
                if (locationManager.isProviderEnabled(provider)) {
                    locationManager.getLastKnownLocation(provider)?.let {
                        if (!isManual) updateFromLocation(it, "GPS (Native)")
                    }
                    locationManager.requestLocationUpdates(
                        provider,
                        5000L,
                        5f,
                        nativeListener,
                        Looper.getMainLooper()
                    )
                }
            }
        } catch (_: Exception) {
            _state.value = _state.value.copy(statusText = "Location unavailable — set manually")
        }
    }

    fun stopUpdates() {
        try {
            fusedClient.removeLocationUpdates(fusedCallback)
        } catch (_: Exception) {
        }
        try {
            locationManager.removeUpdates(nativeListener)
        } catch (_: Exception) {
        }
    }

    fun setManualPosition(geo: GeoPosition) {
        isManual = true
        _state.value = LocationState(
            position = geo,
            isManualOverride = true,
            statusText = "Manual Override"
        )
    }

    fun clearManualOverride() {
        isManual = false
        _state.value = _state.value.copy(
            isManualOverride = false,
            statusText = "Acquiring GPS fix..."
        )
        startUpdates()
    }

    private fun updateFromLocation(location: Location, source: String) {
        _state.value = LocationState(
            position = GeoPosition(
                latitudeDeg = location.latitude,
                longitudeDeg = location.longitude,
                altitudeMeters = if (location.hasAltitude()) location.altitude else 0.0
            ),
            isManualOverride = false,
            statusText = source
        )
    }
}
