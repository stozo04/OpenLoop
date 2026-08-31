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

Run the autonomous check from the repository root:

```powershell
python scripts/run-verification-loops.py --changed
```

The script installs the current debug APK when one is available, grants CAMERA, resets only the onboarding DataStore, verifies the exact first-run copy and video description, taps `LET'S GO!`, verifies persistence, cold-starts again, and proves the returning user reaches Video mode with the back camera bound. It saves UI XML, screenshots, and logcat under the printed evidence directory and exits nonzero on failure.

For manual diagnosis, use `helpers/control.ps1` and `.claude/skills/run-e2e/scripts/uiauto.ps1`.

## Gotchas

- Onboarding is one page now, not three. Recipes that tap through page dots are stale.
- Camera permission is not an onboarding page. The autonomous check grants CAMERA so it can prove the post-onboarding destination.
- The check deletes only `files/datastore/openloop_preferences.preferences_pb`; gallery videos remain intact.
- `run-as` works on debug builds. Release installs are not this skill's target.
- Do not use a user's personal DataStore as the first-run fixture.
- On Windows, use `python` or `py -3`; Git Bash `python3` may resolve to a broken Scripts shim.
