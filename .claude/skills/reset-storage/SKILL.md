---
name: reset-storage
description: Reset OpenLoop's onboarding flag on a connected device/emulator so the first-run onboarding flow shows again on next launch. Use whenever the user says "reset storage", "/reset-storage", "reset onboarding", "show onboarding again", or wants to re-test the first-run experience. This is a fast, surgical, single-purpose delete — it removes ONLY the onboarding DataStore file and touches nothing else (recorded videos, thumbnails, and other state are always preserved).
---

# reset-storage — re-show OpenLoop onboarding (fast)

Delete OpenLoop's onboarding DataStore file on a connected device so the next launch shows
onboarding again. **This skill does exactly one thing and nothing else** — no video counting, no
keep-vs-delete question, no screenshots, no waking the screen. Be fast.

## Ground truth (verified against source — keep these in sync with the code)

- **applicationId / package:** `io.github.stozo04.openloop` (set in `app/build.gradle.kts`).
  (The app was rebranded from OpenLoop; a ghost `com.OpenLoop.app` may still be installed —
  **ignore it**, it is the wrong, old app.)
- **Launcher activity:** `io.github.stozo04.openloop/.MainActivity`.
- **Onboarding flag:** Preferences DataStore named `openloop_preferences`
  (`UserPreferencesRepositoryImpl.kt`), boolean key `has_completed_onboarding`. It **defaults to
  `false`**, so once the backing file is gone, onboarding shows. The file is:
  `/data/data/io.github.stozo04.openloop/files/datastore/openloop_preferences.preferences_pb`

> If onboarding still gets skipped after a reset, the names above have drifted from the code —
> re-check `applicationId` in `app/build.gradle.kts` and the `name =` in
> `UserPreferencesRepositoryImpl.kt`, then update this file.

## Step 1 — Resolve adb and the target device

adb: try `adb` on PATH, else `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`
(on this machine: `C:\Users\gates\AppData\Local\Android\Sdk\platform-tools\adb.exe`).

`adb devices`:
- **No device** → stop, tell the user to start an emulator / connect a device. Don't guess.
- **Exactly one** → use it.
- **Multiple** → ask which, then pass `-s <serial>` on every call.

## Step 2 — Delete the onboarding flag (the whole job)

Force-stop first (so the running process can't rewrite the file from its in-memory cache on exit),
then delete just the one DataStore file. Two commands:

```
adb -s <serial> shell am force-stop io.github.stozo04.openloop
adb -s <serial> shell run-as io.github.stozo04.openloop rm -f files/datastore/openloop_preferences.preferences_pb
```

`run-as` works on **debuggable** builds without root. If it fails with "package not debuggable"
(a release build is installed), say so — don't silently fall through; the fast path needs the
debug build.

That's it. The next launch shows onboarding (the key defaults to `false` when the file is gone).
Tell the user it's done. Only relaunch / screenshot if the user explicitly asks.

## Windows gotchas (only relevant if you do verify)

- Run any adb command that contains an on-device path (`/sdcard/...`) from **PowerShell**, not Git
  Bash — MSYS rewrites the leading-slash path and the pull fails. (Or prefix with `MSYS_NO_PATHCONV=1`.)
- Don't wrap verification in `run-as ... sh -c '...'` from PowerShell — the single quotes get
  mangled and the path argument is dropped (you'll see the app's root dir listing instead of the
  datastore). If you must verify, call `run-as ... ls files/datastore` with the path as a direct
  argument (no `sh -c`).

## Quick reference

| Goal | Command |
|------|---------|
| Reset onboarding (the only mode) | force-stop, then `adb -s <serial> shell run-as io.github.stozo04.openloop rm -f files/datastore/openloop_preferences.preferences_pb` |
| Relaunch (only if asked) | `adb -s <serial> shell am start -n io.github.stozo04.openloop/.MainActivity` |

Full background on the DataStore: `docs/guides/jetpack-datastore-explained.md`.
