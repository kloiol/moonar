# Moon AR Tracker (Android / Kotlin)

A real-time celestial Augmented Reality Android application that reads the device's geoposition (GPS + elevation), computes topocentric Moon and Sun positions, and overlays the active body, its 24-hour trajectory, and the horizon onto the camera feed.

## Features

- **Lunar Ephemeris & Visibility Forecast (`astronomy/MoonAstronomy.kt`)**:
  - Jean Meeus (*Astronomical Algorithms*, 2nd ed., Ch. 47) truncated ELP-2000/82 periodic series, nutation & true obliquity (Ch. 22), GAST (Ch. 12), WGS-84 topocentric parallax (Ch. 40), Bennett atmospheric refraction (Ch. 16), and lunar phase/bright-limb angle (Ch. 48).
  - **Moon Visibility** button calculates when the Moon will next rise, reach peak altitude, and set from the phone's current latitude, longitude, and height (including geometric horizon dip from observer elevation).
- **Solar Ephemeris & AR Mode (`astronomy/SunAstronomy.kt`)**:
  - **Show Sun / Show Moon** toggle switches the live AR overlay, 24-hour sky trajectory arc, off-screen guidance arrow, and telemetry HUD between the Moon and the Sun (Meeus Ch. 25 solar coordinates + twilight phase).
- **Sensor-Fused Celestial AR (`sensors/OrientationSensorManager.kt`, `ar/ArProjection.kt`, `ui/ArOverlayCanvas.kt`)**:
  - Uses `TYPE_ROTATION_VECTOR` (with accelerometer + magnetometer fallback), Gram-Schmidt orthonormalized low-pass smoothing, display rotation remapping, `GeomagneticField` true-north declination, and Camera2 physical FOV intrinsics.

## Building & Releasing an APK with GitHub Actions

1. Upload the extracted project files (keeping `.github/workflows/build-apk.yml` and `app/src/main/AndroidManifest.xml` in place) to a GitHub repository.
2. Push to `main`/`master` or trigger **Build Moon AR Tracker APK** manually in the **Actions** tab.
3. Once the workflow finishes, download `MoonARTracker-v1.0.<run_number>-debug.apk` directly from the repository's **Releases** tab (it is also uploaded as a workflow artifact).
