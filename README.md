<div align="center">

# MotionCam

**Turn spare Android phones into motion-activated security cameras: no ads, no subscriptions, no cloud of ours.**

[![Latest release](https://img.shields.io/github/v/release/gac2003/Android-Motion-Activated-Camera?label=download&color=ffc107)](https://github.com/gac2003/Android-Motion-Activated-Camera/releases/latest)
[![Android 11+](https://img.shields.io/badge/Android-11%2B-3ddc84)](#get-it)
[![MIT license](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

<!-- screenshots: home.png · camera-mode.png · clips.png · player.png (portrait, 1080 px wide) -->
<img src="docs/screenshots/preview-focus-peaking.jpg" width="200" alt="Camera mode: live preview with Pro controls">&nbsp;
<img src="docs/screenshots/clips.jpg" width="200" alt="Clips grouped by day">&nbsp;
<img src="docs/screenshots/player-timeline.jpg" width="200" alt="Player with the filmstrip">&nbsp;
<img src="docs/screenshots/collections.jpg" width="200" alt="Collections">

</div>

## Why

Apps like Alfred and IP Webcam are full of ads, lock features behind subscriptions, or have no manual camera
controls. MotionCam records only when something moves, gives you a phone camera app's Pro controls, and keeps your
clips on **your** phone and **your** cloud.

## What it does

| | |
|---|---|
| 🎥 **Records on motion** | Starts within a fraction of a second, keeps going until things are still. Detection zones and a light filter ignore glare, reflections and lights switching on. |
| 🎛️ **Pro controls** | Pick the exact lens; set exposure, ISO, shutter, focus (with focus peaking), white balance, zoom, resolution and frame rate. |
| 📱 **Your phones, connected** | Other phones see each camera's status and latest clip, get instant alerts with a picture, watch live (smooth video with your own Cloudflare key) and change its settings, over any network. |
| ☁️ **Backup you own** | Google Drive, Dropbox or your own FTP/SFTP server. Protect clips from clean-up, organise them in shared collections, tag and search them. |
| 🔒 **Private** | No accounts of ours, no ads, no analytics. Live features run on a Firebase project, and with your own build it's yours. |

Tested running 24/7 on a Galaxy S23 Ultra and S20 FE.

## Get it

| | Download the app | Set up your own (full features) |
|---|---|---|
| **Effort** | 2 minutes | About 45 minutes, once |
| **Recording, clips, player, search, web viewer** | ✅ | ✅ |
| **Backup to your own FTP / SFTP server** | ✅ | ✅ |
| **Google Drive and Dropbox backup** | ❌ | ✅ with **your own** free accounts |
| **Your phones: alerts, live view and remote control between phones** | ❌ | ✅ with **your own** free Firebase project |
| **Push alerts with MotionCam closed** | ❌ | ✅ optional, a send-only key from the same project |
| **Smooth live video on any network** | ❌ | ✅ optional, with **your own** Cloudflare account (free tier) |
| **How** | [Download the APK](https://github.com/gac2003/Android-Motion-Activated-Camera/releases/latest/download/MotionCam.apk) on the phone, open it, allow installing (*Install anyway* if Play Protect asks) | Fork this repo and follow **[SETUP.md](SETUP.md)**: GitHub builds the APK for you, no Android Studio needed |

> [!IMPORTANT]
> **The downloadable APK has no online accounts built in.** Google Drive, Dropbox and connecting your phones
> (alerts, live view, remote control) only work in a copy you [build yourself](SETUP.md) with your own free
> accounts; in the download they say "needs your own build". Everything on one phone works, and so does backup to
> your own FTP / SFTP server (a home NAS, for example).

New versions install over the old one and keep your settings and clips.

## Quick start

1. **Open MotionCam.** The welcome screen asks where to start (and, in your own build, offers to connect your phones).
2. **Point a phone at the room**: open the **Camera** tab. The preview shows at once, so you can set the lens,
   exposure and focus first, and draw **ZONES** to ignore parts of the picture.
3. **Tap START.** Lock the phone; it keeps watching with the screen off. Clips appear in **Clips**, and on your other
   phones in **Home** and **Activity**.
4. **Keep it running**: Settings → *Storage & protection* → **Exempt from battery optimisation**. Keep the phone
   plugged in; capping charging (and the CPU, if your phone allows it) keeps it cool.

Everything else (backup, alerts, the player, search, landscape) is in the **[User guide](docs/USER_GUIDE.md)**.

## Documentation

* **[User guide](docs/USER_GUIDE.md)**: every screen and feature.
* **[Set up your own build](SETUP.md)**: fork, add your free Firebase, Google, Dropbox (and optionally push and
  Cloudflare) credentials, get your APK.
* **[Privacy](PRIVACY.md)**: what the app accesses and where data goes.

## Forks

This is a personal project: issues and feature requests aren't taken. You're welcome to fork it and make it your own
([SETUP.md](SETUP.md) walks you through building your copy).

## License

[MIT](LICENSE)
