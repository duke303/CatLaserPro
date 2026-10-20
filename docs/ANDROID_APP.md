# Android app

A native Android app (Kotlin, Jetpack Compose, Material 3) for the CatLaser. It covers the full
feature set of the built-in web UI, organised into five main sections, and adds phone-sensor
steering, a background quick-access notification and a home-screen widget.

It talks to the device over HTTP (settings) and UDP port 4210 (joystick - the same protocol as
the M5Stick remote). Minimum Android version: 8.0 (API 26).

## Build

### Option A - Android Studio

1. Install [Android Studio](https://developer.android.com/studio).
2. **File -> Open** and choose the `android/` folder of this repository.
3. Wait for the Gradle sync (first time takes a few minutes and needs internet; accept the SDK
   licences / "Install missing SDK package" if asked).
4. **Build -> Build Bundle(s) / APK(s) -> Build APK(s)**, then click **locate**. The file is
   `app/build/outputs/apk/debug/app-debug.apk`.
5. Copy it to your phone and open it. Allow installing from unknown sources once when asked.

Or run the app directly on a USB-connected phone with USB debugging enabled.

### Option B - GitHub Actions

The workflow in `.github/workflows/android-build.yml` builds a debug APK on every push to
`android/`. Open the **Actions** tab, pick the latest run and download the artifact
`CatLaserPro-debug-apk`. The debug APK is auto-signed and installable as is.

### Toolchain

The versions are matched to each other - upgrade them together or not at all:

- Gradle 8.13 (wrapper included), Android Gradle Plugin 8.13.0
- Kotlin 2.2.0 with the Compose compiler plugin 2.2.0
- compileSdk 35, targetSdk 34, minSdk 26, JDK 17 or newer

If Android Studio offers an upgrade to a newer AGP, decline it.

The application id is `com.catlaser.pro2`, so the app installs next to any earlier build
that used a different id. Change `applicationId` in `app/build.gradle.kts` if you want a
different one.

## First start

1. Open **Setup -> Connection**.
2. Enter the device IP address (printed on the serial monitor; `catlaser.local` does not work on
   every Android device) and press **Save & connect**. The badge in the top bar turns to
   *Connected*.
3. Phone and device must be on the same network (or the phone joined to the device's access
   point).

## Menu structure

| Section | Sub-menus and content |
|---|---|
| **Control** | Laser on/off, brightness, dim during sprint - Cat Energy slider and presets - live preview with trail and hotzone - current pattern, progress, next pattern - status |
| **Patterns** | *Playlist*: on/off, shuffle, next, active patterns, duration, pause - *Single*: all 12 patterns - *Lissajous*: presets, direction, frequency A/B, phase, amplitude, rotation, drift, breathing, tempo, save |
| **Motion** | *Motion Engine*: max speed, acceleration, braking, sprint speed, tilt speed, softness, save - *Noise & Hotzone* - *Mixer & Morph* |
| **Manual** | *Joystick*: touch pad over UDP, mirror X/Y - *Sensor control*: steer with the phone - *Sensor setup* |
| **Setup** | *Servo*: range and calibration - *Time & safety*: safety gate, NTP window, session timer - *Connection*: host/IP, UDP port, background service, device info, OTA |

Sliders send debounced values while you drag and the final value on release. **Save** buttons
persist the current state on the device, exactly like in the web UI.

## Phone-sensor steering

Optional and fully configurable. The app detects which sensors your phone has; only available
ones can be selected.

1. **Manual -> Sensor setup**: check the detected sensors, choose how you hold the phone
   (flat or upright) and assign a sensor per axis (default: tilt left/right for pan, tilt
   forward/back for tilt). If the dot moves the wrong way use **Invert direction**.
2. **Manual -> Sensor control**: hold the pad and tilt the phone. The pose at the moment you
   press counts as the centre (auto zero). Releasing stops steering; after 5 s without a
   signal the device returns to its playlist.
3. Extras, individually switchable: **light sensor -> laser brightness** (calibrate
   dark/bright), **proximity sensor -> action** and **shake -> action** (laser on/off or next
   pattern).

Sensors are only active while the app is in the foreground and the function is needed.

## Background service and widget

- **Setup -> Connection -> Background service** shows a notification with *Laser ON/OFF* and
  *Next pattern* that works even when the app is closed.
- A home-screen widget offers the same two actions.

## Notes

- Cleartext HTTP is allowed in the manifest because the ESP32 does not provide HTTPS.
- All settings of the app (host, port, mirroring, sensor configuration) are stored locally
  with DataStore.
