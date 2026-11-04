# MotionCam Privacy Policy

_Last updated: November 4, 2026 (protection marks)_

MotionCam is an open-source Android app that turns a phone into a motion-activated security camera.
It is a personal project with no company, servers, accounts, advertising or analytics behind it.

## Summary

- MotionCam **does not collect, sell or share** any personal data, and the developer receives **no data at all**.
- Video, audio and settings stay **on your phone**, unless you choose to back clips up to a cloud service or
  server **you** control (Google Drive, Dropbox, FTP or SFTP).
- There are **no ads, no analytics, no tracking and no crash reporting**.

## What the app accesses and why

| Access | Why | Where the data goes |
|---|---|---|
| Camera | Live preview, motion detection and recording | Clips are saved on the phone (Movies/MotionCam) |
| Microphone (optional) | Sound in recorded clips, if "Record audio" is on | Inside the saved clips only |
| Notifications | Shows that monitoring is running, and warnings (e.g. phone too hot, backup problem) | Shown on the phone only |
| Local network | The optional built-in web viewer lets *your own* devices watch the camera over USB tethering, Wi-Fi, Bluetooth tethering or the phone's hotspot. It is off by default and protected by a password you set | Stays between your phone and your viewing device |
| Internet | Only for optional cloud backup that you set up | Only to the service you connect (see below) |
| Videos on the phone (optional) | To show clips recorded by an earlier install of the app | Stays on the phone |

Motion detection runs entirely on the phone. Frames are analysed in memory and immediately discarded; nothing
is sent anywhere for analysis.

## Optional cloud backup

Cloud backup is **off unless you turn it on** and connect an account or server yourself. When enabled,
MotionCam uploads copies of your recorded clips to that destination so footage survives if the phone is
damaged or stolen. MotionCam talks **directly** from your phone to the service; nothing passes through
any server run by the developer.

### Google Drive

- MotionCam requests only the `drive.file` permission. This lets the app see and manage **only the files and
  folders it creates itself** (its backup folder and the clips in it). It **cannot** see, read or change any
  other files in your Google Drive.
- The app uses this access only to create its backup folder, upload clips, list its own uploaded clips, check
  available storage space, and delete its own older clips when you have enabled automatic clean-up.
- Your Google sign-in token is stored **encrypted on your phone** (Android Keystore). It is never sent to the
  developer or any third party.
- **Remote folders (optional):** another device signed in to the same Google account can use the same
  `drive.file` access to list and stream the clips and thumbnails MotionCam uploaded, for playback.
- **Protection marks (optional):** to keep chosen clips from automatic clean-up, MotionCam adds hidden "app
  properties" to those clip files: the phone's name, the collection names involved and a date. Only MotionCam can
  see them; they are removed together with the clip or when any of your phones removes the protection.

MotionCam's use and transfer of information received from Google APIs adheres to the
[Google API Services User Data Policy](https://developers.google.com/terms/api-services-user-data-policy),
including the Limited Use requirements. Data from Google APIs is used only to provide the backup and viewing
features you turned on; it is not used for advertising, not sold, not shared with third parties, and not read by humans.

### Dropbox

- MotionCam uses Dropbox's **App folder** access: it can only use the folder `Apps/MotionCam` in your Dropbox
  and cannot see anything else.
- It uses this access to upload clips, list its own clips, check available space, and delete its own older
  clips when automatic clean-up is enabled. The sign-in token is stored encrypted on your phone.
- **Viewer links (optional):** "Create viewer link" makes a Dropbox view-only link to the backup folder. Anyone
  who has that link can view the clips, until you tap "Revoke link". Opening someone's viewer link in Remote
  folders only reads (streams) the clips.

### FTP / SFTP

- Uploads go to the server and folder you enter. Your server address, username and password or key are stored
  **encrypted on your phone** and used only to connect to that server.

### Thumbnails and remote folders

- With cloud backup on, a small thumbnail picture of each clip is uploaded next to it (in a `.thumbnails`
  subfolder) so your other devices can show it. This can be turned off in Settings.
- Remote folders never upload, change or delete clips. Clips you play are cached on the viewing phone (deleted
  automatically when the cache is full, or with "Clear downloaded remote clips"). Collections you make for them stay
  on that phone.
- **Protection marks:** protecting a clip (by hand or through a collection) stores a small mark in the backup folder
  (Google Drive: hidden app properties on the clip; Dropbox/FTP/SFTP: tiny files in a `.keep` subfolder) with the
  phone's name and collection names, so all your phones can see why a clip is kept. Nothing is sent anywhere else.

## Your control over your data

- **Delete clips:** in the app (Clips screen) or with any file manager/gallery (Movies/MotionCam).
- **Stop cloud backup:** turn it off or tap Disconnect in the app's settings.
- **Revoke access:** you can also remove MotionCam's access at any time from
  [your Google Account permissions](https://myaccount.google.com/permissions) or
  [Dropbox connected apps](https://www.dropbox.com/account/connected_apps).
  Copies already uploaded stay in your cloud account until you delete them there.
- **Uninstalling** the app deletes its settings and stored sign-in tokens from the phone. Recorded clips in
  Movies/MotionCam remain until you delete them.

## Children

MotionCam is not directed at children and does not knowingly collect any information from anyone.

## Changes

If this policy changes, the updated version will be published at this address with a new "Last updated" date.

## Contact

Questions or requests: open an issue at
[github.com/gac2003/Android-Motion-Activated-Camera/issues](https://github.com/gac2003/Android-Motion-Activated-Camera/issues).
