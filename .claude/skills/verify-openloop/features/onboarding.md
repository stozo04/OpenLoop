# Onboarding

First launch shows a single full-bleed trust screen (`Free. Forever.`) with `LET'S GO!`. Tapping it writes onboarding complete to DataStore and continues into camera permission or the viewfinder. Returning users skip this screen.

## Sub-features

- `onboarding-show` shows the headline and CTA on a fresh preferences store.
- `onboarding-cta` dismisses onboarding after `LET'S GO!`.
- `onboarding-skip` skips the screen when `has_completed_onboarding` is already true.

## How to get to it (user POV)

- Install a debug build and launch OpenLoop with no onboarding DataStore (first run, or after `.claude/skills/reset-storage/`).
- Cold start after reset: `am start -n io.github.stozo04.openloop/.MainActivity`.

## Driving it with control.ps1

Preconditions:

- One emulator connected (`adb devices` shows exactly one `emulator-*\tdevice`, or set `VERIFY_SERIAL`).
- Debug APK installed, or present at `app/build/outputs/apk/debug/app-debug.apk` (the script installs with `-r -g` if missing). Do not invoke Gradle.

Run the repeatable loop (Linux cloud agents and any host with `adb` + Python 3 stdlib):

```bash
python3 .cursor/skills/verify-openloop/helpers/onboarding_loop.py
```

Optional env: `VERIFY_SERIAL`, `VERIFY_EVIDENCE_DIR` (default `/tmp/openloop-verify/<timestamp>/onboarding`), `VERIFY_ALLOW_DEVICE=1` with `VERIFY_SERIAL` for a dedicated test phone.

The script resets onboarding (force-stop + delete `files/datastore/openloop_preferences.preferences_pb`), then drives two launch fixtures:

**First run (`LaunchKind.FirstRun`).** Assert dump contains `Free. Forever.`, badges `No Subscriptions · No Ads` and `Open source · 100% on your phone`, CTA `LET'S GO!`, and video content-desc `Looping demo of a boomerang video`. Tap `LET'S GO!`. Wait until the CTA is gone and the DataStore file exists. Evidence: `first-run.xml`, `first-run.png`, `after-cta.xml`.

**Returning launch (`LaunchKind.Returning`).** Force-stop without deleting DataStore; relaunch. Dump must show `Start recording`, `Video`, and `Flip Camera`, and must **not** show `LET'S GO!`. Presence of the word `Camera` on mode chips is not photo mode — prove video mode from `Start recording`. Prove back-facing camera from logcat: a line containing `Camera bound (lens=back)` (saved as `returning-logcat.txt`). Flip Camera content-desc alone is not enough. Evidence: `returning.xml`, `returning.png`.

Exit 0 prints `PASS serial=... first-run=onboarding returning=video+back evidence=...`. Exit 1 prints `FAIL ...` with the missing assertion.

On Windows, the same flow can still be driven manually with `helpers/control.ps1` and `.claude/skills/run-e2e/scripts/uiauto.ps1`.

## Gotchas

- Onboarding is one page now, not three. Recipes that tap through page dots are stale.
- Camera permission is **not** an onboarding page. It is in-context at the shutter (`Grant Permission` / `We need a quick permission`). The loop grants CAMERA up front; if `Grant Permission` appears on the returning launch, the loop fails.
- `reset-storage` deletes only the onboarding DataStore. It keeps gallery videos. Force-stop first or the process rewrites the file on exit.
- `run-as` works on debug builds. Release installs are not this skill's target.
- Do not use a user's personal DataStore as the first-run fixture.
- Isolated Compose tests (`OnboardingScreenTest` `setContent`) are not this loop. Pattern, script internals, and remaining loops: `docs/guides/verification-loops.md`.
