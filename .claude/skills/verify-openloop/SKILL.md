---
name: verify-openloop
description: Drive the OpenLoop Android camera app the way a user does (capture, lenses, stills, photo booth, trim, loop, import, share/library, gallery) on a dedicated emulator. Use when proving a UI or media-pipeline change works, before claiming the app is done, or when a swarm needs a control recipe. Do not use for unit tests, lint, or Play Console copy.
---

# Verify OpenLoop

Agent-facing control skill. Read cold. Do not invent a second launch path.

Primary surface: Android app, package `io.github.stozo04.openloop`, launcher `io.github.stozo04.openloop/.MainActivity`. Jetpack Compose. No accounts, no backend, no API keys.

Also present, not this skill's default: the GitHub Pages store site. Ignore it here.

Sibling skills you must reuse, not copy:

- `.claude/skills/run-e2e/` — full capture → editor → save with logcat scan
- `.claude/skills/run-e2e-pixel-sweep/` — 4-emulator import → save quality gate
- `.claude/skills/reset-storage/` — delete onboarding DataStore only

This skill is the feature map plus a thin `helpers/control.ps1` wrapper. The pixel sweep remains the codec/FGS proof. A feature-map pass that skips a mapped entry point is incomplete.

**Loops vs recipes.** A feature file is a dump/tap recipe. A `helpers/*_loop.py` script is the fail-the-process proof. Onboarding is the first loop. When the owner asks to "verify" a surface, write or run a loop per `docs/guides/verification-loops.md`. `/create-verifier FEATURE_NAME` is the skill that writes the next one. Do not add an isolated Compose `setContent` test and call that the loop. A FAIL is a product bug — fix the app, not the loop.

**Completeness:** Before claiming the feature map is current or adding “missing” recipes, run the gate in `features/README.md` (inventory → diff → no silent `missing`). Global Cursor rule: `feature-map-completeness`. PRDs are optional — OpenLoop shipped many surfaces before PRDs existed; use `strings.xml` + UI chrome first. Worksheet: `features/INVENTORY.md`.

## Launch

Isolation: **one emulator**. Parallel AVDs fight over host CPU and codecs and invent bugs. Never drive the phone in the user's pocket. If `adb devices` shows a physical serial, stop unless `VERIFY_ALLOW_DEVICE=1` and `VERIFY_SERIAL` name a dedicated test device.

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:VERIFY_RUN_ID = Get-Date -Format "yyyyMMdd_HHmmss"
# Start one AVD first (Pixel_6 / Pixel_8 / Pixel_8_API34 are the known ones).
pwsh .cursor/skills/verify-openloop/helpers/control.ps1 launch
```

Ready when `control.ps1 doctor` prints `ok` and `control.ps1 dump` shows one of:

- `LET'S GO!` (onboarding, testTag `onboarding_cta`)
- `Start recording` (camera, video mode)
- `Grant Permission` (in-context camera permission)

Debug APK path: `app/build/outputs/apk/debug/app-debug.apk`. Install with `adb install -r -g` (the `-g` grants runtime permissions; still grant CAMERA explicitly). `gradlew :app:installDebug` can fail with a stale serial — assemble then `adb install`, same as `run-e2e`. If `install -r` fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` (Play or release already on the AVD), `control.ps1 launch` uninstalls `io.github.stozo04.openloop` and installs the debug APK. That wipe is expected; onboarding will show.

If launch fails, stop. Do not tap some other OpenLoop build (`com.OpenLoop.app` is a ghost package — ignore it).

## Doctor

Read-only. Run before the first drive, after any failed drive, and whenever the instance looks wrong.

```powershell
pwsh .cursor/skills/verify-openloop/helpers/control.ps1 doctor
```

Must confirm: serial is an emulator (or an explicitly allowed test device), `io.github.stozo04.openloop` is installed, `versionName`/`versionCode` match this checkout (`1.0.49` / `49` on the branch this skill was written against — re-read `app/build.gradle.kts` if they drifted), and `adb get-state` is `device`.

If doctor fails, relaunch or abort. Never drive an instance you did not start.

## Drive

Harness: `uiauto.ps1` (uiautomator dump → tap by text or content-desc). Screenshots hit a per-session image limit; the dump is the eyes.

```powershell
pwsh .cursor/skills/verify-openloop/helpers/control.ps1 dump
pwsh .cursor/skills/verify-openloop/helpers/control.ps1 tap -Label "Start recording"
```

Prefer these handles, in order:

1. Visible text / content-desc (`Start recording`, `Stop recording`, `Take photo`, `Save boomerang`, `Gallery`, `Flip Camera`, `Lenses and Photo Booth`, `Import a video`, `SEND`, `LET'S GO!`, `Trim`, `Speed`, `Loop`, `Filter`, lens names like `Broccoli`)
2. Compose `testTag` values listed in `features/` (uiautomator may not expose testTags — dump first; if a tag is missing, tap the content-desc)
3. Coordinates only when a control has no desc (legacy shutter note in `run-e2e` used `540,2155` on 1080×2400). The shutter **does** have desc: `Start recording` / `Stop recording` / `Take photo` / `Start photo booth`. Use those. Pinch zoom has no tap label — see `features/pinch-zoom.md`.

Feature recipes live in `features/`. Start from the map README. Drive the mapped entry points.

## Evidence

Directory: output of `control.ps1 evidence-dir` (default `%TEMP%\openloop-verify\<run>`). Survives cleanup. `tmp/` in this repo is gitignored; do not commit dumps.

For every proof capture:

- The uiautomator dump before and after the action (`dump-before.txt`, `dump-after.txt`)
- The tap line (label + coordinates `uiauto` printed)
- Side effect: a new `boom_*.mp4` / gallery tile for save flows; onboarding gone after `LET'S GO!`
- Logcat slice for crashes (`AndroidRuntime`, `FATAL EXCEPTION`) when the flow touches Media3 / save

Standards:

- Exercise the real UI. Do not set `OpenLoopUiState` from a test-only host and call that a user proof.
- Capture the action and the resulting screen, not only the last dump.
- A save is unproven until gallery (or the share sheet for `boom_*.mp4`) shows the file.
- Unit tests and Robolectric are not a substitute for this skill.
- `run-e2e` dry of screenshots is still a real drive — say "confirmed via dump/logcat" when you did not see pixels.

Name artifacts `$VERIFY_EVIDENCE_DIR/<feature-id>/`.

## Cleanup

Kill what this run started. Never `pkill` / `adb shell pkill` by name.

```powershell
pwsh .cursor/skills/verify-openloop/helpers/control.ps1 cleanup
```

Force-stops `io.github.stozo04.openloop` on **this serial only**. Leaves the APK installed. Does not delete `$VERIFY_EVIDENCE_DIR`. Does not wipe gallery clips unless the recipe says so. Does not reset onboarding unless you ran `.claude/skills/reset-storage/`.

After cleanup, confirm the evidence directory still exists and is non-empty if a feature was driven.

Leave the emulator running unless you started it for this run; if you started it, shut that AVD down, not every emulator on the machine.

## Helpers

`helpers/control.ps1` is the wrapper. Invocation is in Launch / Doctor / Drive / Cleanup above.

It calls `.claude/skills/run-e2e/scripts/uiauto.ps1` for dump/tap. Do not reimplement dump parsing.

Onboarding repeatable loop (adb + Python 3 stdlib, no Gradle): `python3 .cursor/skills/verify-openloop/helpers/onboarding_loop.py`. Recipe: `features/onboarding.md`. Pattern and remaining loops: `docs/guides/verification-loops.md`.

For the full editor-tab + logcat report, run `.claude/skills/run-e2e/SKILL.md` and keep that report under `docs/e2e/`. That satisfies **edit-and-save** when you also store the dumps in `$VERIFY_EVIDENCE_DIR/edit-and-save/`. Single-tab claims use `features/edit-trim.md`, `edit-speed.md`, `edit-loop.md`, `edit-filter.md`, `edit-delete.md`, or `edit-save.md`.
