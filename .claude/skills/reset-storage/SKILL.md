---
name: reset-storage
description: Reset OpenLoop's preferences DataStore on a selected test device/emulator so onboarding shows on next launch. Use when the user says "reset storage", "/reset-storage", "reset onboarding", or "show onboarding again". Deleting this file also resets the speed-curve intro and saved-loop count; recorded videos and thumbnails are preserved.
---

# reset-storage — re-show OpenLoop onboarding (fast)

Follow [shared operating instructions](../../../docs/OPERATING_INSTRUCTIONS.md) for authorization, scope, verification, and blocker reporting.

Delete OpenLoop's preferences DataStore file on the selected test device so the next launch shows
onboarding again. The same file also holds `has_seen_speed_curve_intro` and `saved_loop_count`.
State these reset effects before acting. If the request requires preserving those values, do not
use this whole-file delete. Preserve media; do not substitute `pm clear` or uninstall.

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
- **Multiple** → use the serial already selected in the conversation; ask only if the target remains ambiguous. Pass `-s <serial>` on every call.

## Step 2 — Delete the onboarding flag (the whole job)

Force-stop first (so the running process can't rewrite the file from its in-memory cache on exit),
then delete just the one DataStore file. Two commands:

```text
adb -s <serial> shell am force-stop io.github.stozo04.openloop
adb -s <serial> shell run-as io.github.stozo04.openloop rm -f files/datastore/openloop_preferences.preferences_pb
```

`run-as` works on **debuggable** builds without root. If it fails with "package not debuggable"
(a release build is installed), say so — don't silently fall through; the fast path needs the
debug build.

Check each command's exit code immediately. Then run
`adb -s <serial> shell run-as io.github.stozo04.openloop ls files/datastore` and require a successful
directory listing without `openloop_preferences.preferences_pb`. A failed listing is not proof of
deletion. Report the file reset and the three affected preferences; only claim the onboarding UI
was verified if you launched and observed it. Relaunch / screenshot only if requested.

## Windows gotchas

- Run any adb command that contains an on-device path (`/sdcard/...`) from **PowerShell**, not Git
  Bash — MSYS rewrites the leading-slash path and the pull fails. (Or prefix with `MSYS_NO_PATHCONV=1`.)
- Don't wrap verification in `run-as ... sh -c '...'` from PowerShell — the single quotes get
  mangled and the path argument is dropped (you'll see the app's root dir listing instead of the
  datastore). If you must verify, call `run-as ... ls files/datastore` with the path as a direct
  argument (no `sh -c`).

## Quick reference

| Goal                             | Command                                                                                                                              |
| -------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------ |
| Reset onboarding (the only mode) | force-stop, then `adb -s <serial> shell run-as io.github.stozo04.openloop rm -f files/datastore/openloop_preferences.preferences_pb` |
| Relaunch (only if asked)         | `adb -s <serial> shell am start -n io.github.stozo04.openloop/.MainActivity`                                                         |

DataStore reference: [Google's DataStore guide](https://developer.android.com/topic/libraries/architecture/datastore). Read the current keys in `data/UserPreferencesRepositoryImpl.kt` before resetting; the preferences file is shared.
