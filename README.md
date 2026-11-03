Vibe coded Android app that turns your phone into a security surveillance camera.
After trying the popular apps like Alfred and IPCam, they were either filled with ads, locked features behind subscriptions, or lacked manual camera adjustments.
This app was mainly built to be:
1. Turn your phone into a security camera that can run 24/7
2. Give manual control over the camera including manual focus, ISO, shutter speed, etc that the popular apps lack
3. Easily browse recorded footage in-app
4. Only record when motion is detected to save storage space and not record useless footage

Tested on a S23 Ultra and S20 FE which ran 24/7.
Cap phone at 70% CPU usage and turn off fast charging to reduce temperatures.

Android APK can be downloaded from the artifacts of the GitHub Actions.

# MotionCam

A motion-triggered security camera for Android (built for a Galaxy S20 FE, works on any Android 11+ phone).

* Records only when something moves; keeps recording until there has been no motion for N seconds (default 30, adjustable 10–120).
* **Pick the exact lens** (main / ultra-wide / telephoto / front) – not just "front" or "rear".
* **Pro Video style controls**: EV, ISO, shutter speed, manual focus (with focus peaking), white balance, zoom, resolution/fps.
* 1080p / 30 fps H.264 by default (720p, 4K, 24/60 fps and HEVC are options).
* Runs as a foreground service so it keeps working with the screen off.
* **Remote viewing without Wi-Fi/LAN**: built-in web page with live MJPEG + clip download, reachable over USB tethering, `adb forward`, Bluetooth tethering or the phone's own hotspot.
* Protects the phone (all optional): pauses the camera when hot, stops when the battery is low, can delete old clips.

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
1. Open the app. The live preview starts right away (no monitoring needed), so you can set everything up first.
2. Use the bottom strip like the Pro Video mode:
   * **LENS / FORMAT** – pick the exact camera and resolution/fps.
   * **EV, ISO, SPEED, FOCUS, WB, ZOOM** – yellow values are AUTO and show what the camera is currently using
     (e.g. ISO 320, 1/60). Drag the slider to switch that control to manual, starting from the current value;
     tap AUTO to go back. Tap the value text to type an exact number.
   * **FOCUS** shows green focus peaking while it is open.
   * **ZONES** shows detected motion in red; drag on the image to mask areas (dark = ignored). The slider is sensitivity.
3. Tap **START MONITORING**, then lock the phone. Stop from the notification or the app.
4. Settings → *Exempt from battery optimisation*. Set *Video rotation* explicitly if the phone is on a fixed stand.

Clips are saved to **Movies/MotionCam** (visible in Gallery and over USB file transfer).
The **Clips** screen shows thumbnails by day; tapping a clip opens the **player**, which plays every following clip
back to back like one continuous video. Below the video is a Samsung-style filmstrip: the playing clip is expanded
into its frames (tap it to collapse/expand), pinch to resize, drag to scrub (stops at the clip's end while expanded).
Tap the video to pause/play, double-tap left/right to skip 5 s; the ⋮ menu has share / open with / delete.

Long-press any control button on the main screen to choose which buttons are shown and their order.

## Motion detection tips (peephole / windows)
* *Light filter → Strong* ignores reflections, glare and light sweeping across the scene; *Normal* ignores
  lights switching on/off and exposure changes.
* Mask everything outside the peephole circle with **ZONES**.
* Raise *Motion must last at least* (e.g. 500 ms) to ignore brief flashes.

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
* Motion analysis runs 8×/s on a 1280x720 luma stream. The recorder is permanently connected to the camera and
  prepared in advance, so a clip starts roughly 0.25 s (the *motion must last* time) after motion begins.
* Manual white balance uses the camera's presets (incandescent … shade) rather than free Kelvin values, because raw gains are not portable across phones.
* Lenses hidden behind Samsung's multi-camera are offered if the phone exposes them to apps; if a lens is missing from the list, Samsung does not expose it to third-party apps.
* Android 14 does not allow camera foreground services to auto-start at boot, so monitoring must be started by opening the app.
* A debug-signed APK is fine for personal use.
