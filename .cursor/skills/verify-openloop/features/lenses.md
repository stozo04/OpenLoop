# Lenses

Live face effects on the camera viewfinder. The bottom-left drawer opens a carousel of lenses (and the Photo Booth tab). A selected lens tracks up to **two** faces at once — same lens on everyone — and is burned into the recording / photo preview. Clearing the lens is tap-to-toggle on the active thumb.

## Sub-features

- `lenses-open` opens the drawer (`Lenses and Photo Booth` / `lens_button`) and shows the Lenses tab (`camera_lenses`) plus carousel (`lens_carousel`).
- `lenses-pick` selects a catalogue thumb by display name / `lens_thumb_*` (Broccoli, Shades, Pizza Face, Football, Dog, Twisted Tongue, Elvis, Cowboy, Vampire).
- `lenses-clear` deselects by tapping the active thumb again, or closes the drawer (`Close lenses` / `lens_close`).
- `lenses-one-face` — with one detectable face, the active lens sticks to that face (preview).
- `lenses-two-faces` — with two detectable faces, **both** get the same lens (preview and, if recording, the saved clip). A third face does not steal a slot.
- `lenses-bake` — record a short clip with a lens on, stop into Trim; the effect is already in the pixels (no editor lens control).
- `lenses-hand-flick` (optional, SPIN lenses only: Broccoli / Pizza Face / Football) — a hand waving near the sticker spins it; needs a real face + hand in frame, not a static scene.

## Autonomous verifier

`lenses-open` / `lenses-pick` / `lenses-clear` are driven by a loop; the rest of this file is the
manual recipe for the sub-features it cannot reach.

> **Not yet seen passing.** The loop was written in a container with no Android SDK and no
> emulator, so it has proved nothing yet — `INVENTORY.md` carries it as `loop not yet run`, not
> `automated`. `scripts/run-verification-loops.py` runs it, so the first pre-PR sweep on a machine
> with an AVD is what settles it. Treat a red result there as a product bug until its evidence
> says otherwise.

```text
python .cursor/skills/verify-openloop/helpers/lenses_loop.py
VERIFY_LENS="Elvis" python .cursor/skills/verify-openloop/helpers/lenses_loop.py
```

It opens the drawer, scrolls the carousel to the named lens (**Vampire** by default — the newest
entry, and the one a registration slip would strand off-screen), wears it, and taps it off again.
Each state is read two ways out of one dump: the active-lens name pill and the thumbnail's own
`selected` flag. Nothing is recorded, and it makes no claim about the lens landing on a face — see
the gotchas below.

## How to get to it (user POV)

- On the camera viewfinder (Video or Camera stills mode), tap the bottom-left control (`Lenses and Photo Booth`).
- Stay on the **Lenses** tab (not Photo Booth). Swipe the carousel; tap a thumb.
- Put one or two faces in frame. Record or take a photo to bake the look.
- Photo Booth is a sibling tab in the same drawer — see [photo-booth](./photo-booth.md).

## Driving it with control.ps1

Preconditions:

- Doctor passes. Viewfinder up (`Start recording` or `Take photo`). Onboarding done. CAMERA granted.
- Evidence dir `lenses/` created.
- Prefer an AVD with a webcam / face scene if proving `lenses-one-face` / `lenses-two-faces`. A blank emulator scene with **zero** faces still proves open/pick/clear UI.

- **Open.** `control.ps1 tap -Label "Lenses and Photo Booth"` (fallback: `Lenses`). Dump: `lens_carousel` and/or lens names (`Broccoli`, `Shades`, …) and `Photo Booth`.
- **Pick.** `control.ps1 tap -Label "Broccoli"` (or another catalogue name). Dump still shows carousel; active thumb is larger (visual — dump may not expose selection state).
- **Clear.** Tap the same thumb again, or `Close lenses`. Drawer may stay open with no active lens.
- **One face.** With a face in the preview, confirm the sticker tracks (screenshot / visual). Dump alone cannot prove tracking.
- **Two faces.** Two people (or two face-like regions the detector accepts) both show the lens. Third face ignored for slots. Visual / recording proof only.
- **Bake.** With a lens selected: [record-clip](./record-clip.md) → Trim. Optional: finish [edit-and-save](./edit-and-save.md) and scrub the gallery preview — lens pixels are in the file.
- **Hand flick.** Only when a SPIN character is on and a hand can enter frame. Not required for the happy path; mark **verified-unreachable** on a face-less AVD scene.
- **Proof.** `drawer.txt`, `picked.txt`, optional `one-face.png` / `two-faces.png`, optional post-save gallery dump.

## Gotchas

- Catalogue lives in `camera/lens/Lens.kt` — do not invent lens names. Tap-to-toggle clears; there is no separate "None" thumb.
- Same lens for every tracked face (no per-face picker). Cap is **2** (`PRD-multi-face-lenses`).
- Emulator without faces: UI open/pick/clear is still valid proof for those sub-features. Do **not** claim `lenses-one-face` / `lenses-two-faces` from a dump with no face.
- Lenses do **not** apply to imported library clips — import bypasses the live CameraEffect.
- Hand flick needs MediaPipe + a flickable (SPIN) lens; Dog / Shades / Elvis / Twisted Tongue are not the spin verb.
- Do not confuse drawer open (`Lenses and Photo Booth`) with the in-drawer tab label (`Lenses`) or Photo Booth arming.
