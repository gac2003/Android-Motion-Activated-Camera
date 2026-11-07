# MotionCam user guide

> **Downloaded the APK?** It has no online accounts built in, so the parts marked **(own build)** below are off:
> Google Drive and Dropbox backup, and connecting your phones. They need a copy you build yourself with your own free
> accounts: see [SETUP.md](../SETUP.md). Everything else, including backup to your own FTP / SFTP server, works.

* [The app at a glance](#the-app-at-a-glance)
* [Camera mode](#camera-mode)
* [Clips and the player](#clips-and-the-player)
* [Search and tags](#search-and-tags)
* [Backup and remote folders](#backup-and-remote-folders)
* [Your phones: alerts, live view, remote control](#your-phones-alerts-live-view-remote-control)
* [Watching from a computer](#watching-from-a-computer)
* [Landscape](#landscape)
* [Tips and limits](#tips-and-limits)

---

## The app at a glance

<!-- screenshot: tabs-overview.png (Home tab, portrait) -->

Five tabs: **Home · Activity · Camera · Clips · Settings**. Each tab comes back as you left it, and the app reopens
where you were.

| Tab | What's there |
|---|---|
| **Home** | This phone's Camera mode row, one card per camera (latest picture, state, battery, last motion), warnings (offline, too hot), and the latest activity. |
| **Activity** | What happened at your cameras, by day and by phone, kept forever by default. Filter by kind, jump to a day with the calendar, ⋮ → *Delete activity* removes exactly what you choose. |
| **Camera** | This phone as a camera, full screen: preview, Pro controls, Start / Stop. |
| **Clips** | *On this phone* or *Remote folders*: clips by day, Collections, Search, Timeline. |
| **Settings** | Every setting in categories, with **Search settings** at the top. |

## Camera mode

<!-- screenshot: camera-mode.png (portrait, ISO open) · camera-zones.png (ZONES open, a masked area) -->

* **The strip at the bottom**, like a Pro video mode:
  * **LENS / FORMAT**: the exact camera (main, ultra-wide, telephoto, front) and resolution / frame rate.
  * **EV, ISO, SPEED, FOCUS, WB, ZOOM**: yellow values are automatic and show what the camera uses right now. Drag
    the slider to go manual from that value; **AUTO** goes back; tap the value to type an exact number.
  * **FOCUS** shows green focus peaking (sharp edges) while it is open.
  * **ZONES** shows motion in red; drag on the picture to mask areas (dark = ignored). Its slider is the sensitivity.
  * **CROP** records only part of the picture, e.g. a door peephole: drag the corners in, move the frame, tap the
    text for a shape (square, 4:3, 16:9…), then **Done** (or **Cancel**, or **Turn off crop** for the whole
    picture); the other controls are hidden until then, and Back leaves cropping. The clip is that area at the camera's full
    detail, so it's smaller in proportion (a quarter of the picture ≈ a quarter of the file size), and motion
    outside it is ignored. Your other phones can set it too, from the camera's live view.
  * Long-press any button to choose which buttons show and in what order.
* **START / STOP** monitoring. Stop also works from the notification. Lock the phone: it keeps watching with the
  screen off and dims the camera's frame rate between clips to save power.
* **⚙** holds everything about this camera: the live preview on/off (at the top), lens and controls, recording,
  motion detection, storage, battery and heat limits, and shortcuts to the web viewer and Your phones.
* **The red eye** (top) shows while another phone watches this camera; tap it to see who.
* **Short clip when you stop** (optional, Settings → Camera & recording): picking the phone up to stop it often
  starts a clip. One shorter than 1 s (adjustable) is discarded, with a few seconds to **Undo**.

Clips are saved in **Movies/MotionCam** (visible in Gallery and over USB).

## Clips and the player

<!-- screenshot: clips.png (On this phone, a few days) · clips-select.png (2 clips selected) · player.png (playing, controls shown) -->

* **Clips** are grouped by day (tap a day to fold it, ⋮ for the day's actions). Each shows its time and length, and
  its marks: 🔒 protected in the cloud, ☁ backed up, ★ favourite.
* **Hold** a clip to select (drag to select more). The bar offers Share, Favourite, Back up, Delete and **More**
  (collections, tags, protect, open in another app, show in file manager).
* **Collections** group clips without moving them (a clip can be in several). **Favourites** is one.
* **Timeline** plays everything from the newest day's first clip.

**The player** plays clips back to back like one video, full screen:

* Tap to show or hide the controls (they fade while playing). The **pill** under the picture shows the time and
  play / pause.
* **Filmstrip**: the playing clip opens into its frames; drag to scrub, pinch for more or fewer frames, tap another
  clip to jump.
* **Double-tap** left or right to skip (1 s by default; the **±** button changes it, down to frame by frame).
* **Pinch** to zoom into the picture, drag to look around.
* **⋮**: favourite, collections, tags, save / back up, protect, share, delete.

## Search and tags

<!-- screenshot: search.png (Search open: a tag included, one excluded) · search-bar.png (Clips filtered, yellow bar) -->

**Search** (next to Collections) filters the clips shown:

* **Date**: Today, Yesterday, Last 7 / 30 days, or **Pick dates…** for a calendar (a day, or two taps for a range).
* **Tags** and **Collections**: tap once to include ✓, again to exclude ✕, once more to clear. **Any / All** decides
  whether a clip needs one or all of the included ones. With many, type to find one.
* **Length** and **File size**: quick ranges or your own minimum / maximum.
* **All filters / Any filter**: how the different kinds combine.

The button shows how many clips match. While filtered, a yellow bar lists the filters (tap one to drop it) with
**Clear**. Each place (this phone, each remote folder, each collection) keeps its own search.

**Tags** (select clips → More → *Tags…*, or ⋮ in the player): tick tags, or type a new one. A remote folder's tags
live in the folder, so every phone sees them. Tags go with a clip when it's saved, backed up, moved or copied.

## Backup and remote folders

*FTP / SFTP: every copy. Google Drive and Dropbox: **(own build)**.*

<!-- screenshot: remote-folders.png (list with one backup folder) · backup-to.png (Back up to… sheet) · add-folder.png (choosing a folder) -->

A **remote folder** is a cloud folder of clips: on **Google Drive**, **Dropbox**, a Dropbox viewer link, or your
own **FTP / FTPS / SFTP** server. The same folder is where one phone backs up and where your other phones watch.

**Add one**: Clips → *Remote folders* → **＋ Add** → pick the service and the account (or add one) → pick or create a
folder → choose whether this phone backs up its new clips there.

**Backing up**

* New clips upload by themselves: queued, resumed after a dropped connection, retried, also after a reboot. Options:
  Wi-Fi only, charging only, original / 720p / 480p, delete the phone copy once backed up.
* **Back up to…** (select clips → *Back up*): ticks show which folders have the clips. Tick to upload, untick to
  remove them from that folder (confirmed; the phone copies stay).
* **Folder settings** (the folder's ⋮ → *Backup settings*) are shared by every phone using that folder: when the
  cloud is full (delete oldest or pause), a size cap, delete after N days. Any phone that has the folder can change
  them, also one that only watches it.
* MotionCam only ever sees its own files: Drive's `drive.file` access, Dropbox's app folder (`Apps/<app name>`), and
  only its own `MC_….mp4` clips are ever deleted.

**Protecting clips** from clean-up: select → More → *Protect in cloud*, or put them in a protected collection
(Favourites by default). Collections → **Protected in cloud** lists them with the reason; any phone can remove a
protection.

**Watching a remote folder**: the same gallery and player, with **shared collections** and tags (the same on every
phone, synced within seconds), **Save to phone**, **Move or copy to…** another remote folder (also on another
service or another account), and **Delete from cloud** (protected clips are always kept). Clips stream when played
and are cached.

**Moving and copying** runs in the background with a progress notification; **Remote folders → Transfers** shows
each batch (pause, resume, stop, retry failed). Within the same Google Drive or Dropbox account the service copies the
clips itself, so nothing downloads; to another account or service each clip streams straight across, 3 at a time. A
Move deletes the originals once its clips are across (protected ones are kept), and unfinished transfers continue
the next time MotionCam opens. Clips that are already in the other folder are skipped (and a Move keeps them where
they were); MotionCam asks first, and you can copy them anyway under a new name (`…_2.mp4`).

* **Google Drive**: sign in with the same Google account the recording phone backs up to.
* **Dropbox**: the same account, or a **viewer link** made on the recording phone (folder ⋮ → *Backup settings* →
  *Create viewer link*), which is read-only.
* **FTP / SFTP**: the server login (a read-only account is best for viewing).

## Your phones: alerts, live view, remote control

***(own build)**: needs a copy with your own Firebase project ([SETUP.md](../SETUP.md)).*

<!-- screenshot: home-cards.png (Home with 2-3 camera cards) · camera-screen.png (a camera opened from its card) · alert.png (a motion notification) -->

Settings → **Your phones** connects your phones over the internet (outgoing connections only, so it works on hotel
and guest Wi-Fi too). Join every phone with the same **Google account** or a **network code**. A phone that should
be a camera needs a **PIN**.

* **Home** shows every camera: state, battery, temperature warnings, last motion, and its latest picture.
* **Alerts** that don't nag: one motion alert per camera per few minutes, battery low, too hot, offline (only for a
  camera that was monitoring), quiet hours, mute per camera. **Stay connected** gets them instantly with the app
  closed; otherwise MotionCam checks about every 15 minutes. Nothing is lost: **Activity** keeps everything.
* **Tap a camera** for its screen: live view (a frame every 2 s), a high-quality **Screenshot**, its controls and
  settings (sent when you tap **Save**), and:
  * **Stop monitoring / Start monitoring**: no detection or recording; the camera stays on and reachable.
  * **Shut off camera** (red): the camera closes. With **Standby** on (default) it stays reachable and can be started
    again from any phone; otherwise only on the phone itself.
* **Clip pictures**: once a clip is saved, its alert and its row in Activity show a small picture of it (the camera
  keeps a week of them). A camera's Home card shows its latest clip, not the last live view or screenshot.
* **A motion alert opens its clip** from the camera's backup folder (add that folder once under Remote folders).
  If the clip is in several of your folders, it plays from one of them; MotionCam only asks which when the copies differ
  (another size or length).
* **One controller at a time**: your first change takes the remote; other phones see who is changing it.
* **PIN**: protects controls, settings and (by default) the live view. Requests are signed with it, never sent; 5
  wrong PINs lock remote control for a while.

## Watching from a computer

Settings → **Web viewer**: a live picture and clip downloads in any browser, without internet. User `admin`, password
in Settings. The app lists the addresses to open.

| Connection | How |
|---|---|
| USB tethering | Phone: *Hotspot & tethering* → *USB tethering*; open the listed address on the computer |
| USB debugging | `adb forward tcp:8080 tcp:8080`, then open `http://localhost:8080` |
| Bluetooth | Pair, turn on *Bluetooth tethering* (low frame rate) |
| Hotspot | Join the phone's hotspot from the computer |

It's plain HTTP with a simple password: never forward its port to the internet.

## Landscape

<!-- screenshot: landscape-camera.png · landscape-clips.png -->

Turn the phone sideways and every screen uses the width (portrait stays the same):

* The tabs become a **rail** on the left (**‹** hides their names; remembered).
* **Camera mode** works like a phone's camera app: status pills top left; on the right the controls, ⚙, a round
  **Start / Stop** and **FILL / FIT** (the picture uses the whole screen behind the controls, or stays clear of them).
* **Clips**: *On this phone / Remote folders* move into a narrow column beside the tabs; as many clips per row as fit.
* **Home** in two columns, **Settings** in two panes, the phone's status bar hidden.

## Tips and limits

* **Peepholes and windows**: *Light filter → Strong* ignores reflections and glare; mask everything outside the
  peephole with ZONES; raise *Motion must last at least* (e.g. 500 ms) to ignore flashes.
* **Fixed stand**: set *Video rotation* explicitly (Settings → Camera & recording) so clips are always upright.
* **Heat**: the camera pauses itself at the temperature limit (Settings → Storage & protection) and Home says so.
  Slow charging and a CPU cap keep a 24/7 phone cool.
* Android 14+ doesn't let camera apps start by themselves after a reboot: open MotionCam once after restarting the phone.
* Some phones hide extra lenses from other apps; if a lens is missing from LENS, the phone doesn't offer it.
* Manual white balance uses the camera's presets (incandescent … shade): raw colour gains differ between phones.
