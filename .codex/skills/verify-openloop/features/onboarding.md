# Onboarding

First launch shows a single full-bleed trust screen (`Free. Forever.`) with `LET'S GO!`. Tapping it writes onboarding complete to DataStore and continues into camera permission or the viewfinder. Returning users skip this screen.

## Sub-features

- `onboarding-show` shows the headline and CTA on a fresh preferences store.
- `onboarding-cta` dismisses onboarding after `LET'S GO!`.
- `onboarding-skip` skips the screen when `has_completed_onboarding` is already true.

## How to get to it (user POV)

- Install a debug build and launch OpenLoop with no onboarding DataStore (first run, or after `.codex/skills/reset-storage/`).
- Cold start after reset: `am start -n io.github.stozo04.openloop/.MainActivity`.

## Driving it with control.ps1

Run the autonomous check from the repository root:

```powershell
python scripts/run-verification-loops.py --loops onboarding
```

Build the current debug APK first. The script verifies its installed hash and installs only when
the bytes differ, grants CAMERA, resets the onboarding DataStore, verifies the first-run copy and
video description, taps `LET'S GO!`, verifies persistence, and cold-starts again to prove Video
mode with the back camera bound. It saves UI XML, screenshots, and logcat under the printed
evidence directory and exits nonzero on failure. This targeted run omits the other loops; the
full PR sweep already includes onboarding and does not need this separate repetition.

For manual diagnosis, use `helpers/control.ps1` and `.codex/skills/run-e2e/scripts/uiauto.ps1`.

## Gotchas

- Onboarding is one page now, not three. Recipes that tap through page dots are stale.
- Camera permission is not an onboarding page. The autonomous check grants CAMERA so it can prove the post-onboarding destination.
- The check deletes `files/datastore/openloop_preferences.preferences_pb`; this also resets the speed-curve intro and saved-loop count. Gallery videos remain intact. Use a dedicated test fixture.
- `run-as` works on debug builds. Release installs are not this skill's target.
- Do not use a user's personal DataStore as the first-run fixture.
- On Windows, use `python` or `py -3`; Git Bash `python3` may resolve to a broken Scripts shim.
