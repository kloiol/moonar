# Moon AR Tracker (Android / Kotlin)

A real-time celestial Augmented Reality Android application that reads the device's geoposition (GPS), computes the Moon's topocentric position and phase, and overlays the live Moon, its 24-hour trajectory, and the horizon onto the camera feed.

## Architecture

- **Lunar Ephemeris (`astronomy/MoonAstronomy.kt`)**:
  - Jean Meeus (*Astronomical Algorithms*, 2nd ed., Ch. 47) truncated ELP-2000/82 periodic series for geocentric ecliptic longitude, latitude, and distance.
  - Nutation & true obliquity (Ch. 22), Greenwich Apparent Sidereal Time (Ch. 12), and rigorous WGS-84 topocentric parallax correction (Ch. 40) using observer latitude, longitude, and elevation.
  - Bennett's atmospheric refraction correction (Ch. 16), lunar illumination percentage, phase classification, and position angle of the bright limb (Ch. 48).
- **Sensor-Fused Celestial AR (`sensors/OrientationSensorManager.kt`, `ar/ArProjection.kt`)**:
  - Uses Android's `TYPE_ROTATION_VECTOR` (with accelerometer + magnetometer fallback), Gram-Schmidt orthonormalized low-pass smoothing, display rotation remapping, and `GeomagneticField` declination to convert Magnetic North to True North.
  - Reads rear camera physical focal length and sensor dimensions via CameraX/Camera2 to project True North Azimuth/Altitude onto screen pixels with accurate perspective geometry.
- **Live AR Overlay & HUD (`ui/ArOverlayCanvas.kt`, `MainActivity.kt`)**:
  - Renders the Moon at its real-time sky position with its true illuminated phase and bright-limb tilt, a 24-hour trajectory arc, a 3D horizon line with cardinal markers, and an off-screen guidance arrow when the Moon is outside the camera view.
  - Includes a manual GPS override dialog for testing or indoor use.

## Building an APK with GitHub Actions

1. Upload the extracted project files (keeping `.github/workflows/build-apk.yml` and `app/src/main/AndroidManifest.xml` in place) to a GitHub repository.
2. Open the **Actions** tab and run **Build Moon AR Tracker APK** (or push to `main`).
3. Download the compiled `app-debug.apk` from the workflow's **Artifacts** section (`MoonARTracker-debug-apk`).
