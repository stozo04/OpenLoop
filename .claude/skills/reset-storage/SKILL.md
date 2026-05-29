---
name: reset-storage
description: >
  Reset OpenRang's on-device state so the onboarding flow shows again on a connected emulator
  or physical device. Use this whenever the user says "reset storage", "/reset-storage",
  "reset onboarding", "show onboarding again", "clear app data", "wipe the app", or otherwise
  wants to re-test the first-run / onboarding experience. ALWAYS confirm first whether to KEEP
  recorded videos (reset onboarding only — a surgical DataStore delete) or DELETE everything
  (a full app-data wipe), because the two paths destroy different things. Handles adb device
  selection including the multiple-device / ambiguous case, and verifies onboarding reappears.
---

# reset-storage — re-show OpenRang onboarding

OpenRang decides whether to show onboarding by reading **one Jetpack DataStore preference**.
This skill clears that state on a connected device so the next launch shows onboarding again —
useful for testing the first-run flow.

## Ground truth (OpenRang specifics)

- **Package / applicationId:** `com.openrang.app` (debug build has no suffix — same id).
- **Launcher activity:** `com.openrang.app/.MainActivity`.
- **Onboarding flag:** Preferences DataStore named `openrang_preferences`, boolean key
  `has_completed_onboarding`. It **defaults to `false`**, so once the value is gone, onboarding
  shows. The backing file is:
  `/data/data/com.openrang.app/files/datastore/openrang_preferences.preferences_pb`
- **Recorded videos/thumbnails** live separately under `files/videos/` and `files/thumbnails/`.
  This is why the keep-vs-delete-videos choice matters: the two reset modes touch different files.

## Step 1 — Resolve adb and the target device

Find adb (prefer PATH, else the SDK location):
- Try `adb` on PATH first.
- Else use `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`
  (on this machine: `C:\Users\gates\AppData\Local\Android\Sdk\platform-tools\adb.exe`).

Then `adb devices` and resolve the target:
- **No device** → stop and tell the user to start an emulator / connect a device. Don't guess.
- **Exactly one `device`** → use it.
- **Multiple devices** → ask the user which one, then pass `-s <serial>` (or set
  `$env:ANDROID_SERIAL`) on every subsequent adb call so nothing is ambiguous.
- **A ghost `offline` entry** (a dead emulator port) → clear it with
  `adb kill-server; adb start-server`, then re-list. Use `-s <serial>` per device, not bare
  `adb shell` (which errors with "more than one device/emulator").

Confirm the app is installed before continuing: `adb -s <serial> shell pm list packages | grep openrang`.
If it's not installed, tell the user to install/run it first (e.g. `adb install -r app/build/outputs/apk/debug/app-debug.apk`).

## Step 2 — Decide: keep videos, or delete everything (ASK FIRST)

This is the decision the skill exists to get right. First, count the recorded clips so the question
is only asked when it actually matters (debuggable build):
```
adb -s <serial> shell run-as com.openrang.app sh -c 'ls -1 files/videos 2>/dev/null | wc -l'
```
- **0 videos** → the keep-vs-delete choice is moot (nothing to lose). Skip the question and proceed
  surgically (Mode A). Tell the user you're doing so and why.
- **>0 videos** → if the user's request already made it explicit (e.g. "reset onboarding but keep my
  clips" → keep; "wipe the whole app" → delete), honor that. Otherwise, **ask** before touching anything:
  - **Keep videos (default, recommended)** — reset onboarding only. Deletes just the DataStore file;
    recorded clips and thumbnails survive.
  - **Delete everything** — full app-data wipe (onboarding, videos, thumbnails, all preferences).

Don't assume — destroying someone's recorded clips is not reversible, so when in doubt, ask and
default to keep them.

## Step 3 — Execute the chosen reset

### Mode A — Keep videos (surgical DataStore delete)
Force-stop first so the running process can't rewrite the file from its in-memory cache on exit,
then delete just the DataStore file:
```
adb -s <serial> shell am force-stop com.openrang.app
adb -s <serial> shell run-as com.openrang.app rm -f files/datastore/openrang_preferences.preferences_pb
```
`run-as` works on **debuggable** builds without root. If it fails with
"run-as: package not debuggable" (a release build is installed), say so and offer Mode B (full
wipe) or a reinstallation of the debug build — don't silently fall through.

### Mode B — Delete everything (full wipe)
`pm clear` stops the app and erases all of its data in one step:
```
adb -s <serial> shell pm clear com.openrang.app
```

## Step 4 — Verify onboarding reappears

Relaunch and confirm:
```
adb -s <serial> shell am start -W -n com.openrang.app/.MainActivity   # expect Status: ok, no Error
```
Optionally capture a screenshot as proof (this is the project's Definition-of-Done habit — see
`docs/DEFINITION_OF_DONE.md`). Write it to a file **on the device, then pull it** — don't pipe
`exec-out screencap -p > file`: on foldables / multi-display devices (e.g. Pixel Fold) a
"Multiple displays were found" warning bleeds into stdout and corrupts the PNG.
```
adb -s <serial> shell screencap -p /sdcard/onboarding_reset.png
adb -s <serial> pull /sdcard/onboarding_reset.png onboarding_reset.png
adb -s <serial> shell rm -f /sdcard/onboarding_reset.png
```
**On Windows, run adb commands that contain an on-device path (`/sdcard/...`) from PowerShell, not
Git Bash** — MSYS rewrites the leading-slash path into a Windows path and the pull fails with
"failed to stat remote object 'C:/Program Files/Git/sdcard/...'". (Or prefix the command with
`MSYS_NO_PATHCONV=1`.) On a foldable you can also target one screen with `screencap -d <display-id>`
(`adb shell dumpsys SurfaceFlinger --display-id` lists them).

Read the screenshot to confirm an onboarding page (the skater / "No Subscriptions & No Ads", or any
carousel page with the "LET'S GO!" button) is showing, then report what you did (mode chosen, device,
whether videos were kept) and the result.

## Quick reference

| Goal | Command |
|------|---------|
| Reset onboarding, keep videos | `adb -s <serial> shell run-as com.openrang.app rm -f files/datastore/openrang_preferences.preferences_pb` (force-stop first) |
| Full wipe (incl. videos) | `adb -s <serial> shell pm clear com.openrang.app` |
| Relaunch | `adb -s <serial> shell am start -n com.openrang.app/.MainActivity` |
| Confirm onboarding flag gone | next launch shows onboarding (key defaults to `false`) |

Full background on the DataStore: `docs/guides/jetpack-datastore-explained.md`.
