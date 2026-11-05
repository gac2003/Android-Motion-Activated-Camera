# Set up your own copy of MotionCam

MotionCam builds and runs without any accounts: recording, clips, the player and the web viewer work out of the box.
The online features each need **your own** (free) credentials, so your copy never depends on anyone else's projects
or quotas:

| Feature | Needs | Values for the build |
|---|---|---|
| Google Drive backup / folders | A Google Cloud project with an Android OAuth client | none (matched by package name + signing key) |
| Dropbox backup / folders | A Dropbox app | `MC_DROPBOX_APP_KEY` |
| Live connection (status, alerts, live view, remote control, sync) | A Firebase project | `MC_FIREBASE_*`, `MC_GOOGLE_WEB_CLIENT_ID` |
| FTP / SFTP | Nothing (your own server) | none |

Anything you leave out is simply switched off in your build ("not set up in this build"). You can add or change
credentials later the same way; see [Changing credentials later](#changing-credentials-later).

---

## 1. Package name and signing key

Google and Firebase recognise your app by its **package name** and the **SHA-1 fingerprint of the key that signs it**.

* **Package name**: `com.motioncam` by default. To use your own (recommended if you publish builds), set
  `MC_APPLICATION_ID`, e.g. `io.github.yourname.motioncam`.
* **Signing key**: every build is signed. Get the SHA-1 of each key you use (you can register several):
  * Local debug builds: `./gradlew signingReport` (look for *Variant: debug*, *SHA1*), or
    `keytool -list -v -keystore ~/.android/debug.keystore -storepass android`.
  * Your release key: create one once with
    `keytool -genkeypair -v -keystore motioncam.jks -alias motioncam -keyalg RSA -keysize 4096 -validity 10000`
    and read its SHA-1 with `keytool -list -v -keystore motioncam.jks`. **Never commit it** (`*.jks` is git-ignored).

## 2. Where the values go

* **Local builds** (Android Studio / command line): copy `credentials.properties.example` to
  `credentials.properties` in the project folder and fill it in. That file is git-ignored.
* **GitHub Actions builds** (your fork): repository → *Settings → Secrets and variables → Actions*:
  * **Variables** tab: the `MC_…` names from `credentials.properties.example` (they are identifiers, not secrets).
  * **Secrets** tab, for a release-signed APK: `MOTIONCAM_KEYSTORE_B64` (`base64 -w0 motioncam.jks`),
    `MOTIONCAM_KEYSTORE_PASSWORD`, `MOTIONCAM_KEY_ALIAS`. Without them the fork builds with a temporary debug key.
  * Builds are uploaded as a workflow artifact. To also publish GitHub Releases from your fork, change the
    repository name in the last step of `.github/workflows/build.yml`.

Environment variables with the same names also work (they win over `credentials.properties`).

## 3. Google Drive

1. [Google Cloud console](https://console.cloud.google.com/) → create a project (or reuse your Firebase project from
   step 5 — it already is a Google Cloud project).
2. *APIs & Services → Library* → enable **Google Drive API**.
3. *APIs & Services → OAuth consent screen* (Google Auth Platform) → User type **External**, fill in the app name and
   your email. Under *Data access* add the scope `…/auth/drive.file` (MotionCam only ever sees files it created).
   * While the app is in **Testing**, add each Google account that will use it under *Audience → Test users*
     (sign-ins then expire after 7 days). `drive.file` is not a sensitive scope, so you can instead **Publish** the
     app (*In production*) without a Google review.
4. *APIs & Services → Credentials → Create credentials → OAuth client ID* → type **Android** → your package name and
   SHA-1. Create one per signing key (debug and release).

Nothing goes into the build: Google matches the package name and signing key. If sign-in fails with error 10
(`DEVELOPER_ERROR`), the package name or SHA-1 of the installed APK doesn't match an Android OAuth client.

## 4. Dropbox

1. [Dropbox App Console](https://www.dropbox.com/developers/apps) → **Create app** → *Scoped access* → **App folder**
   → a name (clips go to `Dropbox/Apps/<name>`).
2. *Permissions* tab → tick `account_info.read`, `files.metadata.read`, `files.metadata.write`,
   `files.content.read`, `files.content.write`, `sharing.read`, `sharing.write` → **Submit**.
3. *Settings* tab:
   * *OAuth 2 → Redirect URIs*: add `http://localhost:53682/`
   * *Allow public clients (Implicit Grant & PKCE)*: **Allow**
   * Copy the **App key** → `MC_DROPBOX_APP_KEY`. The app secret is never needed — don't put it anywhere.
4. A new app is in *Development* status (up to 500 users, enough for personal use). Use *Apply for production* if you
   need more.

## 5. Firebase (live connection)

1. [Firebase console](https://console.firebase.google.com/) → **Add project** (Google Analytics not needed). The free
   *Spark* plan is enough.
2. *Project settings → Your apps → Add app → Android*: your package name and SHA-1 (add every key's SHA-1 under the
   app's fingerprints). Download `google-services.json` — **don't** add it to the project; just copy values from it:
   * `client[0].api_key[0].current_key` → `MC_FIREBASE_API_KEY`
   * `client[0].client_info.mobilesdk_app_id` → `MC_FIREBASE_APP_ID`
   * `project_info.project_id` → `MC_FIREBASE_PROJECT_ID`
3. *Build → Realtime Database → Create database* (pick a location, start in **locked mode**). Copy its URL (e.g.
   `https://<project>-default-rtdb.<region>.firebasedatabase.app` or `…firebaseio.com`) → `MC_FIREBASE_DB_URL`.
   Then *Rules* tab → paste the contents of [`firebase/database.rules.json`](firebase/database.rules.json) →
   **Publish**. These rules let each Google account read only its own data, and network-code phones only the
   encrypted path of their code.
4. *Build → Authentication → Get started → Sign-in method*:
   * **Anonymous** → enable (used by phones joined with a network code).
   * **Google** → enable, then open it again: *Web SDK configuration → Web client ID* → `MC_GOOGLE_WEB_CLIENT_ID`
     (optional: without it the live connection offers network codes only).
5. Recommended: [Google Cloud console](https://console.cloud.google.com/apis/credentials) → the project's
   *Android key* → *Application restrictions → Android apps* → add your package name + SHA-1s, so the key only works
   from your app.

Free-plan limits worth knowing: 1 GB stored, 10 GB downloaded per month, 100 phones connected at once. MotionCam
stays far below them except for long live-view sessions (about 70 MB per hour of watching). If the monthly download
limit is reached, Firebase pauses the database until the next month.

## 6. Build

* Android Studio: open the folder, *Build → Build APK(s)*.
* Command line: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
  (or `assembleRelease` with the signing environment variables from step 2).
* GitHub: push to your fork; the *Build APK* workflow produces `MotionCam.apk`.

Check: Settings → Live connection should not say "not set up in this build"; Remote Folders → Dropbox should open
Dropbox's sign-in page.

## Changing credentials later

* **Firebase**: phones on the new build connect to the new project; join each phone again (Live connection) and set
  camera PINs again. Data in the old project is left behind.
* **Dropbox**: reconnect Dropbox on each phone (folders and remote folders), since sign-ins belong to the app key.
* **Google Drive**: a different Cloud project can't see files the old one created (`drive.file`); set backup up
  again and pick or create a folder. Keeping the same project but adding a new signing key only needs another
  Android OAuth client.
* **Package name**: a different package installs as a separate app (its own settings and clips folder access).
