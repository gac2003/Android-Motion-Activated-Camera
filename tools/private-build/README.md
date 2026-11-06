# A private build of the public code

Keep this repository public and its APK free of your accounts, while you still get a full-featured APK of every
commit, built with your own Firebase / Dropbox values and signing key that never appear in the public repository.

How it works: after each build of `main`, the public workflow asks a private repository to build the same commit.
The private repository holds only [`build.yml`](build.yml) plus your variables and secrets, and publishes your APK in
its own (private) Releases.

## Set it up

1. **Create a private repository**, e.g. `motioncam-private` (github.com/new → *Private* → tick *Add a README*).
2. In it, **Add file → Create new file** → name it `.github/workflows/build.yml` → paste the contents of
   [`build.yml`](build.yml) → Commit. (If you build from your own fork, change `PUBLIC_REPO` near the top.)
3. In the private repository: **Settings → Secrets and variables → Actions**:
   * **Variables**: your `MC_…` values (see [SETUP.md](../../SETUP.md#6-build-and-install)).
   * **Secrets**: `MOTIONCAM_KEYSTORE_B64`, `MOTIONCAM_KEYSTORE_PASSWORD`, `MOTIONCAM_KEY_ALIAS`: the same key the
     app on your phones was signed with, so updates install over it.
4. **A token that lets the public repository start the private build**: GitHub → your avatar → *Settings → Developer
   settings → Personal access tokens → Fine-grained tokens → Generate new token*:
   * *Repository access*: **Only select repositories** → the private repository.
   * *Permissions → Repository permissions*: **Contents: Read and write**.
   * An expiry you'll remember to renew (up to a year).
5. In the **public** repository: **Settings → Secrets and variables → Actions**:
   * **Secrets**: `PRIVATE_BUILD_TOKEN` = the token.
   * **Variables**: `PRIVATE_BUILD_REPO` = `<your-name>/motioncam-private`.
   * **Delete the `MC_…` variables here**, so the public APK has no accounts built in.

From the next push on, each public build triggers a personal build. You can also run it by hand: the private
repository's **Actions → Build personal APK → Run workflow** builds the public repository's latest `main`.

**Install** from the private repository's Releases on the phone (signed in to GitHub). Both APKs have the same package
and key, so don't install the public one over your personal one: it would turn the online features off.
