# Manual QA — four new AR lenses

**For:** Steven Gates · **Written by:** Claude, 2026-08-15 · **Device work needed:** yes, real hardware, real face

Mirrors `docs/PRD-camera-lenses.md` §11.1 and extends it with the items this change introduced.

**How to read the status column.** Nothing in this document is a claim that a lens looks good on a
person. Where an item says *verified*, it was verified against a **flat portrait in the emulator's
virtual scene** — good enough to prove the plumbing, useless for judging tracking or quality.
Kayley's ruling, verbatim: *"Mona Lisa is bake evidence, not a face."* That is the right framing, and
it is applied throughout.

| Status         | Means                                                                                                                                                                                                                                                  |
| -------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| ✅ **verified** | Actually observed this run on the emulator — each artifact was pulled off the device and opened, not inferred from a log line. Screenshots were reviewed during the run and deliberately **not committed** (owner decision: no screenshots in the PR). |
| ⚠️ **partly**  | Mechanism proven, but the thing you actually care about needs a person                                                                                                                                                                                 |
| ⬜ **yours**    | Not reachable by any agent in this run. Needs you, a real face, real hardware                                                                                                                                                                          |

---

## 0. Steadiness — do this first

⬜ **yours.** Sit still with a lens on. Does it sit still? Move your head fast, then stop. Does it
flicker off when the detector blips, and does it snap back cleanly?

There is deliberately **no smoothing and no hold-last-face** in the code, and this change did not add
any — tuning that blind trades latency for steadiness and risks making it worse. If it jitters, say
so, and it becomes a small, well-understood change: an exponential filter on `FaceSnapshot` plus a
short hold. **Nobody has been able to observe this**, because a painting on a wall does not move.

---

## 1. Placement, per lens, front camera, face centered

⬜ **yours** for all four. Each lens should land **on** your face, not mirrored to the wrong side.

| #   | Lens           | What to look for                                                                   | The specific thing I am worried about                                                                                                                                                                                                                                                                                                                         |
| --- | -------------- | ---------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1.1 | **Football**   | The ball swallows your whole head                                                  | **THE ONE MOST LIKELY TO NEED A TUNE — check this first.** See §6.                                                                                                                                                                                                                                                                                            |
| 1.2 | **Bug Eyes**   | Both eyes enlarged, independently                                                  | Does it read as *funny*, or just as slightly bigger eyes? See §7.                                                                                                                                                                                                                                                                                             |
| 1.3 | **Dog**        | Ears either side of your head, snout on your nose                                  | Ears must not creep over your eyes. Clearance is designed at 0.19 face units.                                                                                                                                                                                                                                                                                 |
| 1.4 | **Pizza Face** | The **slice** swallows your whole head — crust across the brow, tip below the chin | Any forehead, jaw or cheek visible = fails PRD §4b. **Art and geometry changed late** (owner ruled the whole pie dead, `pizza-slice.jpg` accepted), so this is the newest thing in the change and the least looked-at. Watch the **jaw line** specifically: a wedge narrows going down, and the tightest coverage margin in the whole lens sits at y = −0.64. |

## 2. Roll

⬜ **yours.** Tilt your head left and right. The art must roll **with** your head, not against it.
Rotation is derived from the face frame's own axes rather than an Euler angle, so if this is wrong it
is wrong for every lens at once.

## 3. Back camera

⬜ **yours.** Repeat §1 on the back camera. Mirroring must differ between the two cameras, and
**only in x**. If a lens is correct on one camera and mirrored on the other, that is the Lesson 032
mirroring leg.

## 4. Portrait and landscape

⬜ **yours.** Hold the phone both ways. No 90° offset. This is the leg of Lesson 032 that a static
poster cannot exercise, because the emulator scene never rotates.

## 5. Warp behavior

⚠️ **partly.** Bug Eyes was observed enlarging both eyes on the portrait (an A/B against the same scene with no lens). What is **not** verified: that the bulge scales correctly as you move
toward and away from the camera, and that it tracks a moving head. Big Mouth is unchanged and should
behave exactly as it did before — **if it does not, that is a regression in our `WarpSpec` change, and
it is the single most important regression to catch.**

## 6. Football coverage — the top risk in this change

⬜ **yours, and please look at this before anything else.**

On the emulator's portrait, **you could see forehead and hair above the ball and jaw below it.** That
fails PRD §4b's blunt test. Before concluding the lens is wrong, here is the measurement:

- Using the composited features as a ruler (they sit 0.90 face units apart by construction), the ball
  covers roughly **+1.34 to −1.0** face units — matching the designed **+1.40 / −1.20** within
  measurement error. **The geometry is doing exactly what it was told.**
- That subject's head-plus-hair spans about **+1.86 to −1.45** units, against the `Lens.kt` reference
  table's crown at **+1.25** and chin at **−1.00**. It is roughly **50 % larger relative to its own
  features** than the table assumes — a Renaissance portrait with a famously high forehead and a lot
  of hair.

So the portrait is an outlier, not a verdict. **Kayley ruled: keep the measured 4.7, do not tune to
the painting, real-face coverage is your call** (`kayley-1786832985064`).

**If a real face shows the same thing**, the fix is one constant — `widthInUnits` scales both axes:

| `widthInUnits` | Coverage above / below the eye line |
| -------------- | ----------------------------------- |
| 4.7 (shipped)  | +1.40 / −1.20                       |
| 5.2            | +1.59 / −1.39                       |

Worth knowing *why* the football is the only lens with this exposure: it is a **horizontal ellipse**
(`artAspect` 0.571 — wider than tall) covering a head that is taller than it is wide. Pizza's quad is
a square at aspect ~1.0 and spans +1.40 / **−2.20** from a similar width, so it has far more vertical
reach for the same nominal size — and on the same portrait Pizza covered the head completely while
Football did not. That is **shape**, not sloppiness.

## 7. Is Bug Eyes actually funny?

⬜ **yours — a judgment call, not a bug.**

What was actually established: on the emulator's painted portrait, **both generic eye circles
visibly fire independently and the nose bridge stays clean** (an A/B against the same scene with no lens). Kayley ruled that this clears the *blob kill* — and ruled just as explicitly
that **it is not a shareable-look pass and not verified on a face** (`kayley-1786833197885`). The
real two-eye read, and whether it is funny, are yours.

At `radiusInUnits = 0.36` / `strength = 0.75` the effect looked **modest** on the painting — clearly
visible in an A/B, less obviously a gag on its own. Treat that as a hint, not a measurement; Kayley's
instruction was **do not lock new constants off the Mona Lisa**.

Your bar was *catchy, funny, shareable* and *"do not cheap out"*. If it reads as tame on a real face,
raising `strength` toward ~0.9 and/or `radius` toward ~0.45 is a **two-constant change with no new
capability** — no shader work, no new ACK needed.

## 8. Both media, and the pipeline

✅ **verified** for the mechanism, on Football:

- The lens **is baked into the saved photo** — pulled the JPEG off the device, opened
  it, looked at it. Full lens, no UI chrome. GOAL §5 asked whether the preview-bitmap capture path
  drops the lens. **It does not.** No work item.
- The lens **is baked into the saved video** — pulled the 720×1280 H.264 clip, extracted frame 60 with
  ffmpeg, looked at it. Full lens.
- The recording **finalized cleanly with a lens active**: `Capture finalized (26886ms)`, and **no
  `ERROR_SOURCE_INACTIVE`** anywhere in logcat. That is the Lesson 012 / 031 failure class staying dead.

**Per-lens media capture — 7 of the 8 artifacts, individually captured and individually looked at:**

| Lens                   | Saved photo                               | Saved video                  |
| ---------------------- | ----------------------------------------- | ---------------------------- |
| Football               | ✅                                         | ✅                            |
| Pizza Face (**slice**) | ✅                                         | ✅                            |
| Bug Eyes               | ✅ (subtle but present vs an unlensed A/B) | ✅ (clear vs an unlensed A/B) |
| Dog                    | ✅                                         | ❌ **not captured**           |

(Pizza was re-captured end to end after the owner's slice ruling, against an installed build verified by SHA-1.)

Every one of these was pulled off the device and opened — none is inferred. The Bug Eyes rows are
A/B comparisons against an unlensed frame of the same scene, because the effect is modest enough
that a single frame is not self-evidently lensed.

⬜ **The one real gap: no saved video for Dog.** Not a code concern — Dog's photo is captured, its
live preview is captured, and the bake mechanism is a *single shared shader* with one effect
targeting `PREVIEW | VIDEO_CAPTURE`, already demonstrated in three other saved videos. It is a
tooling gap: the lens-tray scroll needed to reach Dog (last of seven) kept mis-landing under UI
automation, and after several attempts the taps started escaping the app entirely. I stopped rather
than keep generating stray navigation. **Worth thirty seconds of your time when you have the phone
in your hand.**

⬜ **yours:** record with a lens, then run it through **trim → speed → reverse → Looks → save**. The
baked lens must survive the whole pipeline, and a lensed clip is a codec input none of the OEM lanes
have seen before.

## 9. Switch lenses mid-recording

⬜ **yours.** Start recording, change lens, stop. The clip must finalize normally. Guarded by
`OpenLoopViewModelTest` and by the attach-once design (Lesson 031), and the emulator recording
finalized cleanly — but not with a lens *switch* mid-flight.

## 10. Walk out of frame

⬜ **yours.** Step out of shot. The preview must return to a clean pass-through with **no ghost art**
left on screen. Not observable here: the portrait never leaves the frame.

## 11. The football's left tip

⬜ **yours, cosmetic, and known.** The source `football.jpg` **clips the ball at the left edge of the
frame** — the ball runs off the photo. In the shipped art that is a flat chord 62 rows tall, 10.6 % of
the art height.

It was **not** repaired, deliberately: mirroring the intact right tip flips the lighting (the key
light is upper-left), mirroring the whole ball reverses the Wilson script you ruled must stay, and
inpainting a product photo is inventing content. Kayley accepted the art with the clip known.

**It was not visible in any live render** — it sits at the far left of the ball, outside the head, and
does not read at render scale. If you want it gone, drop an **unclipped football photo** at
`football.jpg` and re-run:

```text
python swarm/tools/key_art.py football.jpg app/src/main/res/drawable-nodpi/lens_football_art.webp \
    --thumb app/src/main/res/drawable-nodpi/lens_football.webp --close 10 --pad 0.015 --erode 3 --edge-white 230
```

Then copy the printed `artAspect` into `Lens.Football` — it is a measurement, so it moves when the
file moves.

## 12. OEM lanes

⬜ **yours.** The Samsung RTL lane and `run-e2e-pixel-sweep` were **not run** in this change. PRD §9
R7 flags a new GL stage in the capture path as exactly this repo's historical risk surface
(Lessons 019 / 021 / 023 / 027), and a lensed clip is a new kind of input to the reverse pipeline.

---

## What was verified, and on what

Everything below was observed on **emulator-5556 = the Pixel_8 AVD**, against the stock virtual-scene
portrait reached with `adb emu automation play <sdk>/emulator/resources/macros/Walk_to_image_room`.
Evidence files are in the run capture.

| Claim                                                                               | Status                                                                                                                                                                                                                                                                                                                                                  |
| ----------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| All **seven** lenses appear in the carousel with accessibility labels               | ✅ Broccoli, Shades, Big Mouth, Bug Eyes, Pizza Face, Football, Dog                                                                                                                                                                                                                                                                                      |
| The one `CameraEffect` is attached to preview **and** video                         | ✅ `Lens output targets=3 size=1280x960`                                                                                                                                                                                                                                                                                                                 |
| Analysis and effect streams are the **same shape** (Lesson 032 pin holding)         | ✅ analysis `640x480`, output `1280x960` — both 4:3                                                                                                                                                                                                                                                                                                      |
| Face found in the scene, face frame built, art anchored to it                       | ✅ **all four** new lenses — Football, Bug Eyes, Dog, Pizza Face. Pizza has since been **re-arted and re-measured** for the owner-accepted slice; see the slice row below. Every Pizza capture is taken against an install verified by SHA-1 to carry the same art bytes as the repo, after an earlier capture from a stale install had to be retracted. |
| Lens baked into the saved photo                                                     | ✅ Football                                                                                                                                                                                                                                                                                                                                              |
| Lens baked into the saved video                                                     | ✅ Football                                                                                                                                                                                                                                                                                                                                              |
| Recording finalizes cleanly with a lens active                                      | ✅ no `ERROR_SOURCE_INACTIVE`                                                                                                                                                                                                                                                                                                                            |
| No crash across install → carousel → 4 lens switches → photo → 27 s recording       | ✅ no FATAL, no `AndroidRuntime` exception                                                                                                                                                                                                                                                                                                               |
| Dog ears clear the eyes                                                             | ✅ on this subject, matching the designed 0.19-unit clearance                                                                                                                                                                                                                                                                                            |
| Bug Eyes — both eye circles fire independently, nose bridge clean (the *blob kill*) | ✅ A/B against the unlensed frame. **Not** a shareable-look pass; not verified on a face.                                                                                                                                                                                                                                                                |
| Football hides the whole head                                                       | ❌ **not on this subject** — see §6                                                                                                                                                                                                                                                                                                                      |

## What no agent in this run could verify, and why

1. **Anything involving a moving head** — roll, yaw, steadiness, jitter, flicker-off. The scene's
   face is a painting on a wall; it does not move, blink, or turn.
2. **Front-camera mirroring.** The virtual scene is on the back camera.
3. **Device rotation.** The scene does not rotate.
4. **Whether any of it looks good on a human being.** A flat portrait tests geometry, not appeal.
5. **The editor pipeline on a lensed clip** — trim / speed / reverse / Looks.
6. **Any real hardware.** Everything above is one emulator. This repo's own history (Lessons 012,
   019, 021, 023, 027) is a catalogue of things that were green on an emulator and broken on a Samsung.
