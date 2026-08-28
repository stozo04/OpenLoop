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

- Doctor passes.
- Onboarding flag reset if this checkout already completed it:
  `adb -s $Serial shell am force-stop io.github.stozo04.openloop`
  then the `rm` in `.claude/skills/reset-storage/SKILL.md` (DataStore file `files/datastore/openloop_preferences.preferences_pb`).
- Evidence dir `onboarding/` created.

- **See the screen.** Launch. Run `control.ps1 dump`. The dump contains `Free. Forever.` and `LET'S GO!` (testTag `onboarding_cta`). Content-desc `Looping demo of a boomerang video` may appear for the hero video.
- **Continue.** `control.ps1 tap -Label "LET'S GO!"`. After a short wait, dump no longer shows `LET'S GO!`. Next dump shows `Grant Permission` or `Start recording` (or `Take photo` if stills mode).
- **Returning user.** Launch again without reset. Dump does **not** show `LET'S GO!`.
- **Proof.** Save dumps as `show.txt`, `after-cta.txt`, `second-launch.txt`.

## Gotchas

- Onboarding is one page now, not three. Recipes that tap through page dots are stale.
- Camera permission is **not** an onboarding page. It is in-context at the shutter (`Grant Permission` / `We need a quick permission`).
- `reset-storage` deletes only the onboarding DataStore. It keeps gallery videos. Force-stop first or the process rewrites the file on exit.
- `run-as` works on debug builds. Release installs are not this skill's target.
- Do not use a user's personal DataStore as the first-run fixture.
