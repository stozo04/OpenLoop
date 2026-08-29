# Photo booth

Photo booth is a self-driving 5-4-3-2-1 countdown, three stills, composited into a vertical strip (white borders, OpenLoop + date footer, color or B&W). The user arms booth from the lenses / Photo Booth tab, then uses the shutter (`Start photo booth`). Between shots they can swap lenses.

## Sub-features

- `booth-arm` opens Photo Booth (`camera_booth_tab`) and chooses Color or Black & White (`booth_choice_colored` / `booth_choice_bw`).
- `booth-start` starts from the shutter desc `Start photo booth`.
- `booth-countdown` shows `booth_countdown_digit` / `booth_shot_progress` (`Shot N of 3`).
- `booth-done` lands on a strip preview or save path, not the video Trim screen.

## How to get to it (user POV)

- On the camera viewfinder, open the shared drawer (`Lenses and Photo Booth` / `lens_button`) — same entry as [lenses](./lenses.md).
- Choose the Photo Booth tab. Pick Color or Black & White.
- Tap the shutter (desc is `Start photo booth` while armed, not `Start recording`).

## Driving it with control.ps1

Preconditions:

- Doctor passes. Camera is up. Onboarding done. CAMERA granted.
- Video recording is **not** in progress (`Stop recording` absent).
- Evidence dir `photo-booth/` created.
- This run can wait through three countdowns (do not background the app).

- **Open tray.** `control.ps1 tap -Label "Lenses"` or `Lenses and Photo Booth`. Dump: `lens_carousel` and/or `Photo Booth`.
- **Arm.** `control.ps1 tap -Label "Photo Booth"`. Dump: `Color`, `Black & White` or `Black & White` as `camera_booth_black_white`. Pick Color (`booth_choice_colored`). Close chrome if needed (`Turn off photo booth` is the *off* control — do not tap it to start).
- **Idle shutter.** Dump shutter desc is `Start photo booth` (`boothArmed`).
- **Start.** `control.ps1 tap -Label "Start photo booth"`. Dump shows a large digit (`booth_countdown_digit`) and `Shot 1 of 3` (`booth_shot_progress`). Optional `Swap lenses between shots`.
- **Finish.** Wait for three captures. Resulting state is a strip / still preview, **not** `TRIM YOUR VIDEO`.
- **Proof.** `tray.txt`, `armed.txt`, `countdown.txt`, `done.txt`.

## Gotchas

- If you tap `Start recording` you are in video capture, not booth. Dump the shutter desc every time.
- `Turn off photo booth` (`booth_tab_close`) disarms. Do not use it as a start control.
- Lenses (`lens_thumb_*`, `Broccoli`, `Shades`, …) can be on during booth; swapping mid-countdown is allowed, not required for the happy path.
- Do not use the user's face on a personal device. Emulator camera (scene / webcam) is enough to prove UI state even if ML Kit finds zero faces.
- A strip in Gallery (`Captured photo`) after the sequence is the side effect. Trim/boomerang save is a different feature.
