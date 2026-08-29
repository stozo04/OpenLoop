# Photo capture (stills)

Camera mode takes a single still from the live preview (including any active lens). It skips Trim / editor / Loopify. The photo lands in the in-app Gallery and Photos, then opens the share sheet — same dual-write + share pattern as a saved loop.

## Sub-features

- `photo-mode` switches the capture selector to Camera (`capture_mode_selector`, label `Camera`). Shutter desc becomes `Take photo`.
- `photo-capture` taps `Take photo`, gets a still without opening Trim.
- `photo-with-lens` (optional) — lens selected first ([lenses](./lenses.md)); still shows the effect (preview snapshot is WYSIWYG).
- `photo-library-share` — after capture, share sheet and/or `Saved to Photos`, and a gallery still (`Captured photo`).

## How to get to it (user POV)

- On the viewfinder, tap **Camera** on the mode selector (not Video, not Photo Booth).
- Tap the shutter (`Take photo`).
- Share or dismiss; open Gallery to see the still.

## Driving it with control.ps1

Preconditions:

- Doctor passes. Viewfinder up. Booth disarmed (no `Start photo booth`).
- Evidence dir `photo-capture/` created.

- **Mode.** Dump. If shutter is `Start recording`, `control.ps1 tap -Label "Camera"`. Dump shutter desc: `Take photo`.
- **Capture.** `control.ps1 tap -Label "Take photo"`. Must **not** land on `TRIM YOUR VIDEO`.
- **Aftermath.** Share sheet and/or snackbar `Saved to Photos`. BACK if needed. Gallery → still preview (`Captured photo` / `Close photo`).
- **With lens.** Open [lenses](./lenses.md), pick one, then capture. Visual proof that the still includes the sticker.
- **Proof.** `mode.txt`, `after-capture.txt`, optional `gallery-still.txt`.

## Gotchas

- Shutter desc is the mode signal: `Take photo` vs `Start recording` vs `Start photo booth`.
- Stills are preview resolution (not full-sensor `ImageCapture`) — product decision in `PRD-photo-capture.md`.
- Flip Camera works in photo mode; prove desc exists if needed.
- Do not call a booth strip this feature — that is [photo-booth](./photo-booth.md).
- Completing share/library proof can reuse [share-and-library](./share-and-library.md) recipes.
