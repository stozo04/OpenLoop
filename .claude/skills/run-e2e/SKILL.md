---
name: run-e2e
description: >-
  Run a full end-to-end test of the OpenLoop Android app on a connected emulator/device: build
  + install the debug APK, drive the real capture → trim → editor → save → view-loop flow while
  making exactly ONE modification per editor tab (Trim, Speed, Loop, Filter), watch LogCat for
  crashes/ANRs/MediaCodec errors/reverse-preview timeouts/codec churn the whole time, and write a
  findings report to docs/e2e/<timestamp>.md. Use this whenever the user says "run e2e", "end to
  end test", "/run-e2e", "full app test on the emulator", "exercise the editor tabs", "smoke test
  the boomerang flow", or wants the app driven through its real UI (not unit tests) with a logcat
  findings report at the end. Drives via adb input + uiautomator (NOT screenshots, which hit a
  per-session image limit).
---

# run-e2e — OpenLoop end-to-end emulator test

Drive the real app through its full happy path, change one thing on every editor tab, watch
LogCat the entire time, and hand back a written findings report. The point is to catch the
issues unit tests can't — codec contention, reverse-preview stalls, lifecycle races, confusing
UX — by *actually using the app* and reading what the device logs while you do.

`<skill>` below means this skill's directory. Helper scripts are PowerShell (the project's shell);
run them with `pwsh <skill>/scripts/<name>.ps1 ...`. The app package is
`io.github.stozo04.openloop`.

## 0. Preconditions (check, don't assume)

- An emulator/device is attached: `adb devices` shows one `device`. Capture its serial (e.g.
  `emulator-5556`) into `$Serial` and pass `-s $Serial` to every adb call — never rely on a
  default when more than one may exist.
- A JDK for the build: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"`.
- You're at the repo root for gradle. If the working dir has drifted, call gradle with an explicit
  project dir: `& "<repo>\gradlew.bat" -p "<repo>" ...`.

## 1. Build + install

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
& "<repo>\gradlew.bat" -p "<repo>" :app:assembleDebug --console=plain   # confirm BUILD SUCCESSFUL, exit 0
adb -s $Serial install -r -g "<repo>\app\build\outputs\apk\debug\app-debug.apk"   # -g grants runtime perms
adb -s $Serial shell pm grant io.github.stozo04.openloop android.permission.CAMERA
```
`gradlew :app:installDebug` can fail with a stale "device not found" serial — prefer
`assembleDebug` then `adb install` as above. Record versionName/versionCode for the report
(read `app/build.gradle*` or `adb shell dumpsys package io.github.stozo04.openloop | findstr version`).

## 2. Start the logcat capture FIRST, then launch

Capture to a file in the background so nothing is missed, then launch the app:

```powershell
adb -s $Serial logcat -c
# run this in the background (Bash/PowerShell run_in_background) so it streams for the whole run:
adb -s $Serial logcat > C:\Users\<you>\AppData\Local\Temp\openloop_e2e\logcat_<timestamp>.txt
adb -s $Serial shell am start -n io.github.stozo04.openloop/.MainActivity
```

For *terminal* events (reverse done, render finished) use the **Monitor** tool with a `tail -f |
grep` filter rather than polling — but note Monitor only sees lines appended **after** it arms, so
fast events can complete before it starts; always also grep the file directly to confirm. The
reverse preview can run up to **120 s before it times out**, so size waits accordingly (don't
declare a stall before then).

## 3. Drive the flow — one modification per editor tab

Read the screen with `pwsh <skill>/scripts/uiauto.ps1 -Action dump` (prints every visible
text/content-desc + bounds). Tap by label with `-Action tap -Label "<text>"` (matches text OR
content-desc, entities decoded, taps the element center). **Do not rely on screenshots** — the
image API rejects them after a per-session limit; the uiautomator dump is the reliable eyes.

Steps:
1. **Record** a clip: tap the shutter (~`540,2155` on 1080×2400), wait ~3–4 s, tap again to stop.
   Lands on the **Trim** screen.
2. **Trim tab** — change the trim window by dragging a handle. The handles are touchy:
   - First switch off gesture nav so an edge drag doesn't fire Android's back gesture (which pops
     "Discard this clip?"): `adb -s $Serial shell cmd overlay enable com.android.internal.systemui.navbar.threebutton`.
   - Use a **slow** drag (~1200–1400 ms) landing squarely on the handle row; fast swipes are read
     as flings and ignored. Confirm the duration label changed (e.g. `00:00.0 – 00:04.1` →
     `– 00:02.7`). If a "Discard this clip?" dialog appears, tap **Keep** and retry inset from the edge.
   - If the handle won't grab after a few tries, that's itself a finding (see the known trim-handle
     bug) — note it and move on; don't burn the whole run fighting it.
3. **Speed tab** — the speed control is a **SeekBar** ("Playback speed"); the 0.5x/1x/2x are tick
   labels. It responds to a **tap on the track**, not a drag. Tap near a tick position to change it
   and confirm "Current speed" updated.
4. **Loop tab** — pick a non-default direction (default is "Forward then reverse" / ping-pong).
   Choosing "Forward loop" needs no reverse (reliable, fast). Choosing a reverse-needing direction
   exercises `VideoReverser` — budget up to 120 s and expect the "showing forward only" fallback if
   it times out. Note: switching *between* reverse-needing directions reuses the cached reversed
   file (no new reverse); only a **trim change** invalidates that cache.
5. **Filter tab** — pick a non-Original look (e.g. B&W). If a "Preview unavailable" banner shows,
   capture it as a finding (often stale reverse-error state).
6. **Create/Save** — tap the confirm/save control (content-desc "Save boomerang", top-right). A
   share sheet opens for `boom_*.mp4` on success — dismiss with BACK.
7. **View loop** — open the gallery (top-left icon), tap the newest clip, confirm it plays
   (`ExoPlayerImpl: Init` for a fresh player after the save).

At each step, after the action, glance at the live logcat (or grep the file) for anything in the
signature catalog (`<skill>/references/logcat-signatures.md`) before moving on.

## 4. Scan the logs

```powershell
pwsh <skill>/scripts/scan-logcat.ps1 -LogFile <the captured logcat file>
```
This prints a count table (CRASH / TIMEOUT / CHURN classes) plus the reverse-pipeline terminal
events and the render-worker outcome. Any nonzero **CRASH** row is a hard finding. **CHURN** rows
(reclaim pressure, dead-thread races, codec-component count) are advisory — a high
`Created component [c2.*]` count vs. what the flow needs signals resource churn worth investigating.
Full meaning of each signature: `<skill>/references/logcat-signatures.md`.

## 5. Write the report

Stop the background logcat capture. Write the report to `docs/e2e/<timestamp>.md` (create
`docs/e2e/` if needed; `timestamp` = `Get-Date -Format "yyyy-MM-dd_HHmmss"`) using the structure in
`<skill>/references/report-template.md`. Cover: build/device header, verdict, per-step flow table,
the scan count table with quoted lines for nonzero crash/timeout rows, numbered findings (separate
hard findings from smells, each with a concrete next step), and an honest "what could not be
verified" list. Then tell the user the report path and the headline verdict.

## Honesty rules (these are the point of the skill)

- A step you skipped or couldn't complete is a finding, not something to paper over. Say so.
- If you confirmed something only from logcat (not visually, because screenshots are rate-limited),
  say "confirmed via logcat" — don't imply you saw it.
- Don't conflate buckets: a 120 s **timeout** / "forward only" fallback is a *different* issue from
  a **crash** (`surface has been released`, `dequeueOutputBuffer` ISE). Report them separately.
- Distinguish *your* friction (e.g. failed handle drags inflating codec churn) from a real defect,
  but if the control fought you, that's a real UX signal — record it.
