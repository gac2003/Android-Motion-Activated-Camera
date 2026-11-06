# Set up your own MotionCam

Build your own copy of MotionCam that uses **your own** free accounts, so your clips, alerts and quotas don't depend on
anyone else. GitHub builds the APK for you: you don't need Android Studio or a computer that can build Android apps.

**What you'll do** (about 45 minutes, once):

| Step | For | Time |
|---|---|---|
| [1. Fork the repository](#1-fork-the-repository) | everything | 1 min |
| [2. Create your signing key](#2-create-your-signing-key) | installing updates over older versions | 5 min |
| [3. Firebase](#3-firebase-your-phones-alerts-live-view) | Your phones: alerts, live view, remote control | 15 min |
| [4. Google Drive](#4-google-drive) | backup to Google Drive | 10 min |
| [5. Dropbox](#5-dropbox) | backup to Dropbox | 5 min |
| [6. Build and install](#6-build-and-install) | your APK | 5 min |

**Why build your own?** The APK you can download from this project has no online accounts built in. These features
only work in a copy you build with your own free accounts:

| Feature | Needs |
|---|---|
| Google Drive backup and remote folders | step 4 |
| Dropbox backup and remote folders | step 5 |
| Your phones: alerts, live view, remote control, card pictures, synced activity | step 3 |

Everything else works in the download: recording, clips, the player, search and tags, the web viewer, and backup to
your own FTP / SFTP server. If that's all you need, you don't have to build anything.

Every step after 2 is **optional**: skip one and that feature is simply off in your build. You can add the others
later.

> **Keep these private:** your keystore file and its password (step 2). The Firebase, Google and Dropbox values in
> steps 3 to 5 are identifiers (they end up inside every APK), not secrets, but never paste a Dropbox *app secret*
> or a Google *client secret* anywhere: MotionCam doesn't use them.

---

## 1. Fork the repository

<!-- screenshot: setup-fork.png (GitHub: Fork button) -->

1. Sign in to GitHub and click **Fork** at the top of this repository → **Create fork**.
2. In your fork: **Actions** tab → **I understand my workflows, go ahead and enable them**.

Decide your **package name** now: it identifies your app to Google and Firebase. Use something like
`io.github.<your-github-name>.motioncam`. (You can keep the default `com.motioncam`, but it can't be installed next
to someone else's MotionCam.) You'll enter it as `MC_APPLICATION_ID` in step 6.

## 2. Create your signing key

Android only installs an update if it's signed with the same key as the version already installed. Without your own
key, every build gets a new temporary one and you'd have to uninstall (and lose your settings) to update.

You need Java's `keytool` once (it comes with any Java / Android Studio install; on a phone-only setup, any computer
with Java will do):

```bash
keytool -genkeypair -v -keystore motioncam.jks -alias motioncam -keyalg RSA -keysize 4096 -validity 10000
```

Choose a password and answer the questions (any values). Then:

1. Read its **SHA-1 fingerprint** (Google and Firebase need it in steps 3 and 4):
   ```bash
   keytool -list -v -keystore motioncam.jks -alias motioncam
   ```
2. Turn the file into text for GitHub:
   ```bash
   base64 -w0 motioncam.jks > motioncam.jks.b64      # macOS: base64 -i motioncam.jks -o motioncam.jks.b64
   ```
3. In your fork: **Settings → Secrets and variables → Actions → Secrets → New repository secret**, three times:

   | Name | Value |
   |---|---|
   | `MOTIONCAM_KEYSTORE_B64` | the contents of `motioncam.jks.b64` |
   | `MOTIONCAM_KEYSTORE_PASSWORD` | the password you chose |
   | `MOTIONCAM_KEY_ALIAS` | `motioncam` |

<!-- screenshot: setup-github-secrets.png (the three secrets listed) -->

**Back up `motioncam.jks` and its password** somewhere safe, and never commit them. If you lose them, future builds
can't update the installed app.

## 3. Firebase (Your phones, alerts, live view)

<!-- screenshots: setup-firebase-android-app.png · setup-firebase-database.png · setup-firebase-rules.png · setup-firebase-auth.png -->

1. [Firebase console](https://console.firebase.google.com/) → **Create a project** (Google Analytics: not needed).
   The free *Spark* plan is enough.
2. **Add app → Android**: your package name and the SHA-1 from step 2 → **Register app** → download
   `google-services.json` (don't add it to the project; you only copy three values from it):

   | In `google-services.json` | Goes in |
   |---|---|
   | `client[0].api_key[0].current_key` | `MC_FIREBASE_API_KEY` |
   | `client[0].client_info.mobilesdk_app_id` | `MC_FIREBASE_APP_ID` |
   | `project_info.project_id` | `MC_FIREBASE_PROJECT_ID` |

3. **Build → Realtime Database → Create database**: pick a location, start in **locked mode**. Copy the database URL
   at the top (`https://….firebasedatabase.app` or `…firebaseio.com`) → `MC_FIREBASE_DB_URL`.
4. Same page → **Rules** tab → replace everything with the contents of
   [`firebase/database.rules.json`](firebase/database.rules.json) → **Publish**. (Each account can then read only its
   own data, and network-code phones only their encrypted area.)
5. **Build → Authentication → Get started → Sign-in method**:
   * **Anonymous** → Enable → Save (phones joined with a network code).
   * **Google** → Enable → Save. Open it again → *Web SDK configuration* → copy the **Web client ID** →
     `MC_GOOGLE_WEB_CLIENT_ID`. (Optional: without it, phones join with a network code only.)
6. Recommended: [Google Cloud credentials](https://console.cloud.google.com/apis/credentials) (same project) → the
   *Android key* → *Application restrictions* → **Android apps** → add your package name and SHA-1, so the key only
   works from your app.

Free plan limits: 1 GB stored, 10 GB downloaded per month. MotionCam stays far below them except for long live-view
sessions (about 70 MB per hour of watching).

## 4. Google Drive

<!-- screenshots: setup-gcp-drive-api.png · setup-gcp-consent.png · setup-gcp-android-client.png -->

Use the **same Google Cloud project** Firebase created (pick it at the top of the Cloud console).

1. [APIs & Services → Library](https://console.cloud.google.com/apis/library) → **Google Drive API** → **Enable**.
2. [Google Auth Platform](https://console.cloud.google.com/auth/overview) → **Get started**: an app name, your email,
   audience **External**. Then:
   * **Data access** → *Add or remove scopes* → `.../auth/drive.file` → Save.
   * **Audience** → **Publish app** (*In production*). `drive.file` isn't a sensitive scope, so Google doesn't need to
     review it. (If you leave it in *Testing*, add every Google account under *Test users*; their sign-ins then
     expire every 7 days.)
3. **Clients → Create client** → type **Android** → your package name and the SHA-1 from step 2 → Create.

Nothing goes into the build for Drive: Google recognises your app by its package name and signing key.

## 5. Dropbox

<!-- screenshots: setup-dropbox-create.png · setup-dropbox-permissions.png · setup-dropbox-settings.png -->

1. [Dropbox App Console](https://www.dropbox.com/developers/apps) → **Create app** → *Scoped access* →
   **App folder** → a name (clips go to `Dropbox/Apps/<name>`).
2. **Permissions** tab → tick `account_info.read`, `files.metadata.read`, `files.metadata.write`,
   `files.content.read`, `files.content.write`, `sharing.read`, `sharing.write` → **Submit**.
3. **Settings** tab:
   * *OAuth 2 → Redirect URIs*: add `http://localhost:53682/`
   * *Allow public clients (Implicit Grant & PKCE)*: **Allow**
   * Copy the **App key** → `MC_DROPBOX_APP_KEY`. (Not the app secret.)

A new Dropbox app can be used by up to 500 people, plenty for personal use.

## 6. Build and install

<!-- screenshot: setup-github-variables.png (the MC_ variables listed) · setup-release.png (a release with MotionCam.apk) -->

1. In your fork: **Settings → Secrets and variables → Actions → Secrets → New repository secret** for each value
   you have (skip the ones for features you didn't set up). Use **Secrets**, not Variables: a public repository's
   build logs are public, and only secrets are hidden in them.

   | Secret | From |
   |---|---|
   | `MC_APPLICATION_ID` | your package name (step 1) |
   | `MC_FIREBASE_API_KEY`, `MC_FIREBASE_APP_ID`, `MC_FIREBASE_PROJECT_ID`, `MC_FIREBASE_DB_URL` | step 3 |
   | `MC_GOOGLE_WEB_CLIENT_ID` | step 3.5 |
   | `MC_DROPBOX_APP_KEY` | step 5 |

2. **Actions** tab → **Build APK** → **Run workflow** (or push any change to `main`). After about 5 minutes your fork's
   **Releases** has **MotionCam.apk**.
3. On the phone, open your fork's latest release → **MotionCam.apk** → install (allow installing from the browser;
   *Install anyway* if Play Protect asks). Every later build installs over it.

**Your accounts and a public repository**: your fork's Releases are public, so anyone could download that APK and use
your Firebase / Dropbox setup. To keep it to yourself, add a [personal build](#a-personal-build-in-a-private-repository):
then the public APK has no accounts and yours goes to a private repository.

**Check it worked**: Settings → *Your phones* doesn't say "not set up in this build"; Clips → *Remote folders* →
**＋ Add** offers Google Drive and Dropbox.

## A personal build in a private repository

<!-- screenshot: setup-private-release.png (the private repository's release) -->

The workflow can build every commit twice: a plain APK for your public Releases, and your full APK in a **private**
repository's Releases that only you can download.

1. Create a **private** repository, e.g. `Private-MotionCam`, with *Add a README* ticked (it only holds releases).
2. GitHub → your avatar → *Settings → Developer settings → Personal access tokens → Fine-grained tokens → Generate new
   token*: *Only select repositories* → the private repository; *Repository permissions* → **Contents: Read and
   write**; an expiry you'll remember to renew.
3. In your fork's **Secrets**: `PRIVATE_RELEASE_TOKEN` = the token. If the private repository isn't called
   `<you>/Private-MotionCam`, add a **variable** `PRIVATE_RELEASE_REPO` = `<you>/<name>`.

From the next build on, your public release has no accounts and the private repository gets `MotionCam x.y.z
(personal)` with them. Install that one on your phones (open the release while signed in to GitHub); don't install the
public one over it.

## If something doesn't work

| Problem | Fix |
|---|---|
| Google sign-in fails ("error 10", `DEVELOPER_ERROR`) | The package name or SHA-1 doesn't match an Android client (step 4.3) or the Firebase app (step 3.2). Both must use the key from step 2. |
| Google Drive: "access blocked" / app not verified | Publish the app (step 4.2), or add the account as a test user. |
| *Your phones* says "not set up in this build" | A `MC_FIREBASE_*` variable is missing or misspelt; run the build again after fixing it. |
| Phones join but see nothing | The database rules weren't published (step 3.4). |
| Dropbox sign-in page shows an error | Check the redirect URI and *Allow public clients* (step 5.3). |
| "App not installed" when updating | The new APK is signed with a different key: check the three secrets from step 2, then uninstall once and install again. |

## Building on your own computer (optional)

With Android Studio (or the Android SDK, platform 34): copy `credentials.properties.example` to
`credentials.properties`, fill in the same `MC_…` values, then **Build → Build APK(s)** or
`./gradlew assembleDebug` (→ `app/build/outputs/apk/debug/app-debug.apk`). Debug builds are signed with your
computer's debug key: add its SHA-1 (`./gradlew signingReport`) to Firebase and as another Android client in step 4
if you want Google sign-in there too. `credentials.properties` and `*.jks` are never committed.

## Changing things later

* **New Firebase project**: rejoin each phone (Settings → Your phones) and set camera PINs again.
* **New Dropbox app key**: reconnect Dropbox on each phone.
* **New Google Cloud project**: it can't see files the old one created (`drive.file`); set backup up again.
* **New package name**: installs as a separate app with its own settings.
