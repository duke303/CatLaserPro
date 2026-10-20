# Cat Laser PRO

A DIY laser toy for cats, built around an ESP32-S3. Two digital servos move a small laser
dot across the floor with lifelike, organic motion. You control it from a web UI, a native
Android app or a tilt-controlled M5Stick remote.

> **Laser safety:** this project drives a laser. Use only a low-power laser module that is
> safe for your use case, never aim at eyes (cat, human or otherwise) or reflective surfaces,
> and read [docs/HARDWARE.md](docs/HARDWARE.md#laser-safety) before building.

## Features

- **12 motion patterns** - Random, Crazy, Circle, Manual, Zigzag, Figure 8, Spiral, Pendulum,
  Corner Jumps, Stalk+Sprint, an extended Lissajous generator and a **Cat Mode** behaviour state
  machine (lurk, watch, pounce, pause, flee).
- **Cat Energy** - one slider (Lazy ... Crazy) derives speed, acceleration, noise, jump and
  pause probability. A *Motion Engine* gives direct control instead.
- **Layered motion engine** - pattern engine, organic noise, hotzone weighting, morph blending
  between patterns, a pattern mixer, and a trajectory engine (velocity/acceleration limits plus
  spring-damper) driving the servos at 200 Hz on a dedicated core.
- **Playlist** with per-pattern enable, shuffle, duration and pause.
- **Safety gate** - NTP time window, session timer with breaks, pause between patterns and a
  calibration lock. Every laser switching action goes through one function.
- **Control surfaces** - built-in web UI, native Android app (full feature parity plus phone
  sensor steering), M5StickC Plus2 tilt remote over UDP (< 5 ms latency).
- **Convenience** - mDNS (`catlaser.local`), WiFi access-point fallback, over-the-air updates,
  settings can be persisted on the device.

## Repository layout

```
CatLaserPro/
|-- firmware/
|   |-- CatLaserPro/          ESP32-S3 firmware (Arduino sketch)
|   `-- CatLaserRemote/       M5StickC Plus2 tilt remote (Arduino sketch)
|-- android/                  Android app (Kotlin, Jetpack Compose)
|-- docs/
|   |-- HARDWARE.md           parts, wiring, laser safety
|   |-- API.md                HTTP + UDP interface
|   `-- ANDROID_APP.md        app menu structure, sensor steering, build notes
`-- .github/workflows/        CI build for the Android app
```

## Quick start

### 1. Build the hardware

See [docs/HARDWARE.md](docs/HARDWARE.md) for the parts list and wiring. In short: a Seeed
XIAO ESP32-S3, two servos for pan and tilt (the firmware is tuned for DS115 digital servos),
and a laser module switched by a MOSFET on GPIO9. Servos need their own power supply.

### 2. Flash the firmware

1. Install the **Arduino IDE 2.x** and, via the Boards Manager, the **esp32 by Espressif**
   board package, **version 3.x** (the sketch uses the pin-based `ledcAttach` API).
2. Install the library **ESP32Servo** via the Library Manager. Everything else ships with the
   ESP32 board package.
3. Open `firmware/CatLaserPro/CatLaserPro.ino`.
4. In the same folder copy `secrets.h.example` to `secrets.h` and fill in your WiFi
   credentials, a fallback access-point password and an OTA password. `secrets.h` is ignored
   by git.
5. Select **Tools -> Board -> XIAO_ESP32S3**, **USB CDC On Boot -> Enabled**, then upload.
6. Open the Serial Monitor at 115200 baud. After connecting to your WiFi the device prints its
   IP address. If it cannot join your network it opens its own access point
   (`AP_SSID` from `secrets.h`).

Later updates can go over the air: choose the network port `catlaser` in the Arduino IDE and
enter your `OTA_PASSWORD`.

### 3. Open the web UI

Browse to `http://catlaser.local` or to the IP address from the Serial Monitor. Start with
**Servo range** and **Calibration** to set the centre and limits of both axes so the dot stays
inside your play area, then press **Save calibration**.

> The web UI uses Google Fonts. When the device is only reachable through its own access
> point (no internet) the page falls back to default fonts and works the same.

### 4. Android app (optional)

Build it in Android Studio or grab the debug APK from the GitHub Actions artifact. See
[docs/ANDROID_APP.md](docs/ANDROID_APP.md). On first start enter the device IP under
**Setup -> Connection**.

### 5. M5Stick remote (optional)

1. Install the **M5StickCPlus2** library.
2. In `firmware/CatLaserRemote/` copy `secrets.h.example` to `secrets.h` and enter your WiFi
   credentials and the IP address of the CatLaser.
3. Select the board **M5StickC-Plus2** and upload.

Button A toggles steering (the current pose becomes the zero point), button B toggles the
laser. Roll steers pan, pitch steers tilt. When no packet arrives for 5 seconds the CatLaser
returns to its playlist.

## Documentation

| Document | Content |
|---|---|
| [docs/HARDWARE.md](docs/HARDWARE.md) | Parts, wiring, power, laser safety |
| [docs/API.md](docs/API.md) | HTTP endpoints, status JSON, UDP protocol |
| [docs/ANDROID_APP.md](docs/ANDROID_APP.md) | App menus, phone-sensor steering, building |

## Known limitations

- **No authentication** on the HTTP and UDP interfaces. Run the device only on a network you
  trust.
- The NTP time window uses a fixed time zone string (`NTP_TZ` in the sketch, currently
  Central European Time). Change it for your region.
- The Android app has been written against the firmware's API but sensor axis directions can
  differ between phone models; use the per-axis *Invert direction* switch if the dot moves the
  wrong way.
- HTTP is cleartext by design (the ESP32 does not speak HTTPS); the app allows it explicitly.

## License

[MIT](LICENSE)
