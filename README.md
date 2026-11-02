# MotionCam

A motion-triggered security camera for Android (built for a Galaxy S20 FE, works on any Android 11+ phone).

* Records only when something moves; keeps recording until there has been no motion for N seconds (default 30, adjustable 10–120).
* **Pick the exact lens** (main / ultra-wide / telephoto / front) – not just "front" or "rear".
* **Pro Video style controls**: EV, ISO, shutter speed, manual focus (with focus peaking), white balance, zoom, resolution/fps.
* 1080p / 30 fps H.264 by default (720p, 4K, 24/60 fps and HEVC are options).
* Runs as a foreground service so it keeps working with the screen off.
* **Remote viewing without Wi-Fi/LAN**: built-in web page with live MJPEG + clip download, reachable over USB tethering, `adb forward`, Bluetooth tethering or the phone's own hotspot.
* Protects the phone: pauses the camera when hot, stops when the battery is low, deletes old clips automatically.

## Build the APK

### Option A – GitHub (no tools needed)
1. Create a GitHub repository and push this folder to it.
2. Open the **Actions** tab → *Build APK* → latest run → download the **MotionCam-debug-apk** artifact (zip containing `app-debug.apk`).
3. Copy the APK to the phone and install it (allow "install unknown apps" for your file manager/browser).

### Option B – Android Studio
Open this folder in Android Studio (Hedgehog or newer), let it sync, then *Build → Build APK(s)*, or press Run with the phone connected over USB.

### Option C – command line
With the Android SDK installed (`ANDROID_HOME` set, platform 34): `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.

## First run
1. Open the app, tap **START MONITORING**, grant camera (and notification) permissions.
2. Use the bottom strip like the Pro Video mode: tap **LENS** to choose the camera, **FOCUS** → drag to focus manually (green overlay = sharp edges), **ISO / SPEED** to fix exposure, etc.
3. Settings → *Exempt from battery optimisation*. Set *Video rotation* explicitly if the phone is on a fixed stand.
4. Lock the phone. Monitoring continues; stop it from the notification or by reopening the app.

Clips are saved to **Movies/MotionCam** (visible in Gallery and over USB file transfer).

## Remote viewing
The web viewer password is shown/changed in Settings (user name: `admin`). The app lists the phone's addresses, e.g. `http://192.168.42.129:8080`.

| Link | How |
|---|---|
| USB tethering | Settings → Connections → Mobile Hotspot and Tethering → *USB tethering*; browse to the tether address on the PC |
| USB debugging | `adb forward tcp:8080 tcp:8080` then open `http://localhost:8080` |
| Bluetooth | Pair, enable *Bluetooth tethering* on the phone; low frame rate |
| Hotspot | Join the phone's hotspot from the laptop |

Do not forward the port to the internet; it is a plain-HTTP server with a simple password.

## Notes and limits
* Motion analysis uses a 1280x720 luma stream sampled ~4×/s; recording starts ~0.5–1 s after motion is detected (the camera session is rebuilt to attach the recorder).
* Manual white balance uses the camera's presets (incandescent … shade) rather than free Kelvin values, because raw gains are not portable across phones.
* Lenses hidden behind Samsung's multi-camera are offered if the phone exposes them to apps; if a lens is missing from the list, Samsung does not expose it to third-party apps.
* Android 14 does not allow camera foreground services to auto-start at boot, so monitoring must be started by opening the app.
* A debug-signed APK is fine for personal use.
