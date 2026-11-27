# Moon AR Tracker (Android / Kotlin)

A real-time celestial Augmented Reality Android application that reads the device's geoposition (GPS + elevation), computes topocentric Moon and Sun positions, and overlays the active body, its 24-hour trajectory, and the horizon onto the camera feed.

## Features

- **Aimed Compass Sector Moon Visibility (`astronomy/MoonAstronomy.kt`)**:
  - Tap **Moon in NE / SE / SW / NW** (automatically matched to the direction your camera is pointing) to calculate when the Moon will be visible above your elevation-adjusted horizon inside that compass quadrant (`NE: N-E`, `SE: E-S`, `SW: S-W`, `NW: W-N`), including entry time, peak altitude, exit time, and duration.
  - Uses Jean Meeus (*Astronomical Algorithms*, 2nd ed., Ch. 47) truncated ELP-2000/82 series, WGS-84 topocentric parallax, Bennett atmospheric refraction, and geometric horizon dip from phone height.
- **Solar Ephemeris & AR Mode (`astronomy/SunAstronomy.kt`)**:
  - Tap **Show Sun / Show Moon** to switch the live AR overlay, 24-hour trajectory arc, guidance arrow, and HUD between the Moon and the Sun.
- **In-Place APK Updates**:
  - Uses a committed fixed `app/debug.keystore` and auto-incrementing `versionCode` tied to `GITHUB_RUN_NUMBER` so new GitHub Releases install directly over previous versions without uninstalling.

## Building & Releasing an APK with GitHub Actions

1. Upload the extracted project files (keeping `.github/workflows/build-apk.yml`, `app/debug.keystore`, and `app/src/main/AndroidManifest.xml` in place) to a GitHub repository.
2. Push to `main`/`master` or trigger **Build Moon AR Tracker APK** manually in the **Actions** tab.
3. Download `MoonARTracker-v1.0.<run_number>-debug.apk` from the **Releases** tab.
