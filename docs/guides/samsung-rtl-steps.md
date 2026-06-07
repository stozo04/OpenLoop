# Testing OpenLoop on a Samsung Device via Remote Test Lab (RTL)

Repeatable steps for smoke-testing a build on a real Galaxy device (e.g. S23) using
[Samsung Remote Test Lab](https://developer.samsung.com/remote-test-lab) — no physical
device needed. Samsung doesn't ship emulator images; RTL is the standard way to test on
Galaxy hardware.

> Heads-up: the file name says "RTL", not "emulator" — RTL devices are **real phones in
> Samsung's lab**, streamed to your browser. That's why camera capture shows a wall and
> why you test the media pipeline via **import** instead.

---

## 1. One-time setup

### a. Reserve a device

Log in at the RTL site, pick a device (S23 = `SM-S911U`), and start a session. Keep the
browser tab open — the streamed device screen is how you drive the app.

### b. Get the RDB client

In the RTL device toolbar choose **Test → Remote Debug Bridge**. Download the RDB app
from the link on that panel (lands as `rdb.zip` → extract; ours lives at
`C:\Users\gates\Downloads\rdb\rdb.exe`).

### c. Put adb on your PATH (the gotcha that costs an hour)

RDB shells out to `adb` and **fails silently** if it can't find it. The SDK copy is at:

```
%LOCALAPPDATA%\Android\Sdk\platform-tools
```

Add it to the **user PATH** (Settings → Environment Variables, or):

```powershell
$pt = "$env:LOCALAPPDATA\Android\Sdk\platform-tools"
$userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
if ($userPath -split ';' -notcontains $pt) {
    [Environment]::SetEnvironmentVariable('Path', ($userPath.TrimEnd(';') + ';' + $pt), 'User')
}
```

**Critical:** Windows processes snapshot PATH at launch. If RDB (or your terminal) was
already running when you changed PATH, it still has the old one. **Close RDB and your
terminals, reopen them**, then verify with `adb version` in the fresh terminal. If the
RTL page still shows the "add the ADB path…" instruction text after RDB starts, refresh
the RTL tab.

---

## 2. Connect

1. Start `rdb.exe` (from a terminal opened *after* the PATH change).
2. In the RTL browser page: **Test → Remote Debug Bridge → Connect**.
3. Verify the bridge:

```powershell
adb devices
# → localhost:<port>    device     (port changes every session!)
```

4. Identify the device (replace the port with yours — and use `-s` on **every** command;
   a stale `ANDROID_SERIAL` from a previous session will otherwise hijack plain `adb`):

```powershell
adb -s localhost:52172 shell getprop ro.product.model     # SM-S911U
adb -s localhost:52172 shell getprop ro.build.version.sdk # API level
```

Keep the remote screen on (tap it if it blanks) — RTL drops USB debugging when the
screen sleeps.

---

## 3. Build, install, run

```powershell
# Build (signed release if keystore.properties is present; use assembleDebug for debug)
.\gradlew.bat :app:assembleRelease

# Install (add -d if it complains about downgrades)
adb -s localhost:52172 install -r app\build\outputs\apk\release\app-release.apk

# Logcat — own terminal window, leave running; shows live output AND saves evidence
adb -s localhost:52172 logcat -c
adb -s localhost:52172 logcat -v time | Tee-Object -FilePath "$env:USERPROFILE\openloop-s23-release.txt"

# Launch
adb -s localhost:52172 shell am start -n io.github.stozo04.openloop/.MainActivity
```

If install fails with a signature mismatch (older debug/Play build on the lab device):

```powershell
adb -s localhost:52172 uninstall io.github.stozo04.openloop
```

### Git Bash (MINGW64) variant

Two bash-specific snags: backslash paths get eaten (`app\build\...` → use forward
slashes), and a shell opened before the PATH change won't see `adb` — fix in-session
with the `export` below, or just open a fresh Git Bash.

```bash
export PATH="$PATH:/c/Users/gates/AppData/Local/Android/Sdk/platform-tools"  # if adb not found

adb -s localhost:52172 install -r app/build/outputs/apk/release/app-release.apk
adb -s localhost:52172 logcat -c
adb -s localhost:52172 logcat -v time | tee ~/openloop-s23-release.txt   # own window
adb -s localhost:52172 shell am start -n io.github.stozo04.openloop/.MainActivity
```

---

## 4. Smoke-test flow

Drive on the RTL screen: onboarding → permissions → **import a clip** (capture just
films the lab wall; import exercises the same pipeline) → trim → one change per editor
tab (Direction / Speed / Looks) → **Save (green check)** → gallery playback.

The Save path is the historical S23 weak spot — watch it closely.

### What to grep for afterwards

```powershell
Select-String -Path "$env:USERPROFILE\openloop-s23-release.txt" -Pattern `
  "FATAL|AndroidRuntime|ClassNotFoundException|NoSuchMethodException|Resources\`$NotFoundException|OpenLoopReverse.*E/"
```

On a **release** build specifically:

| Signature | Likely cause |
|---|---|
| `ClassNotFoundException` / `NoSuchMethodException` | R8 strict full mode stripped a reflectively-used class/constructor → add an explicit keep rule in `app/proguard-rules.pro` |
| `Resources$NotFoundException` | Optimized resource shrinking removed a runtime-only resource → `tools:keep` in a `res/raw/keep.xml` |
| `MediaCodec` errors / reverse timeouts | Device codec behavior — see `docs/lessons_learned/020` and `023` |

---

## 5. Known limitations (learned the hard way)

- **`connectedAndroidTest` cannot drive `localhost:` serials** — Gradle's UTP refuses
  them. Install with `:app:installDebug`, then run instrumented tests with
  `adb shell am instrument` directly.
- **`connectedAndroidTest` uninstalls the app afterwards**, destroying cache/files
  evidence. If you're debugging stateful behavior, `adb pull` what you need first.
- The `localhost:<port>` **port changes every RTL session** — never hardcode it; check
  `adb devices` each time and pass `-s` explicitly.
- RTL sessions have a **time limit** (credits) — budget the test before starting.

---

*Origin: 2026-06-07 session — S23 release-build smoke test for the AGP 9 migration
(PR #69). The PATH-snapshot gotcha and stale-`ANDROID_SERIAL` hijack both bit us that
day; everything above is verified working.*
