# Code to remove later

Whoever works on this project next (person or AI assistant): the items below are temporary and should be deleted
once their condition is met. Each place in the code carries a comment starting with `TODO-REMOVE`, so
searching the project for that word finds them all. Delete this file when the list is empty.

## AI leftovers clean-up (added in 1.1.86)

**What it is.** Builds 1.1.78 – 1.1.85 had on-device AI features (person-aware focus, describing clips, search by
meaning, faces). They were removed in 1.1.86. This code clears what those builds left on phones and in backup
folders: files, settings, automatic clip tags starting with "✦ ", and a ".ai" subfolder of small files.

**When it can go.** Once every phone that had one of those builds runs 1.1.87 or later, has been opened once, and
the camera phone has uploaded at least one clip to each backup folder. Ask the owner to confirm before removing.

**What to remove (search for `TODO-REMOVE`):**

1. `app/src/main/java/com/motioncam/Leftovers.kt`: the whole file.
2. `MotionCamApp.kt`: the `Leftovers.clean(this)` call in `onCreate`.
3. `cloud/TagFiles.kt`: the line in `load()` that skips tags starting with `Leftovers.MARK`.
4. `cloud/CloudSync.kt`: the `leftoversDue` / `cleanLeftovers` functions and the block in `lane()` that calls them.

**After removing:** the app should compile with no reference to `Leftovers` left. Nothing else depends on it.
