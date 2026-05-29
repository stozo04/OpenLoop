# Boomerang Rollout — Delivery Plan

Companion to [`../boomerang-editor/IMPLEMENTATION.md`](../boomerang-editor/IMPLEMENTATION.md).

The parent doc answers **"what are we building?"** This folder answers **"in what
order do we ship it?"** — broken into 7 thin vertical slices, each one a single PR
that ships a user-visible improvement on top of the previous slice.

---

## Why thin slices

A change is only useful if a user can feel it after `adb install`. Slices like
"data-layer plumbing for X" are invisible and skipped here — within a slice, the
technical work is whatever is needed to deliver that one user-visible promise.

The §15 ordering in the parent IMPLEMENTATION.md was a *technical* sequencing
(data → state → processor → screen). What follows is a **delivery** sequencing:
each slice is a self-contained PR that an outside contributor could pick up cold,
implement, prove with the Definition-of-Done gate, and merge — without needing
any other slice in flight.

Today the app's user-facing surface is:

- 3-step Onboarding.
- Live camera viewfinder with front/back lens toggle; 1.5 s auto-stop burst recording.
- Gallery view (currently empty in practice — captures save to the gallery, but the
  1.5 s default is too short for anyone to use it meaningfully yet).

Every slice below builds on top of that baseline.

---

## The 7 slices

| # | Slice | After this ships, the user can… |
|---|-------|-----------------------------------|
| [01](../../completed/boomerang-rollout/01-capture-variable-length.md) | **Variable-length capture (≤30 s)** | …tap shutter to start, tap again to stop, auto-cap at 30 s. Clip lands in gallery. |
| [02](./02-auto-route-trim-and-default-save.md) | **Auto-route to Trim screen + default-render Save** | …after capture, app drops them on a Trim screen; drag start/end handles; tap `NEXT` and a default `fwd→rev` 2× 1-rep boomerang renders and saves. Back to camera. |
| [03](./03-editor-direction-tab.md) | **Tabbed Editor + Direction tab** | …after `NEXT` on the trim screen, the editor opens with a Direction tab (4 options); pick a direction and tap the save checkmark. |
| [04](./04-editor-speed-tab.md) | **Speed tab** | …drag a horizontal slider 0.25× – 3× (default 2×). Preview re-binds with new speed live. |
| [05](./05-editor-repetitions-tab.md) | **Repetitions tab** | …pick 1 / 2 / 3 / 4. Preview loops the cycle that many times. |
| [06](./06-share-sheet-and-return.md) | **Share sheet + return-to-camera** | …Save now pops the Android share sheet on the rendered MP4; on dismiss, snackbar "Saved — view in gallery" → back to camera. |
| [07](./07-gallery-tap-to-edit.md) | **Gallery tap-to-edit + raw/boomerang distinction** | …tap a raw clip in the gallery to enter the same Trim → Editor flow. Filter chip All / Boomerangs / Raw. Long-press boomerang to re-edit from its source raw. |

Each slice's doc is structured the same way: **Problem → Scope (this slice only)
→ UX deltas → Technical deltas (file-level) → Testing plan → Acceptance criteria.**

---

## How to use this folder

1. **Pick the lowest-numbered open slice.** Slices are ordered for a reason — slice
   N+1 assumes everything in slice N is shipped and merged.
2. **Cut a branch:** `feature/boomerang-slice-<NN>-<short-name>` (e.g. `feature/boomerang-slice-03-direction-tab`).
3. **Read the slice doc end-to-end.** It is the PRD for that PR.
4. **Cross-reference the parent.** [`../boomerang-editor/IMPLEMENTATION.md`](../boomerang-editor/IMPLEMENTATION.md)
   has the deep-dive architecture (storage model, state machine, Media3 pipeline,
   design tokens) the slice docs reference but don't repeat.
5. **Apply the Definition-of-Done gate.** See [`../../DEFINITION_OF_DONE.md`](../../DEFINITION_OF_DONE.md) —
   debug + release green, unit + instrumented tests, *actually run the app + screenshot*.
6. **Open the PR with the screenshot attached** and the slice doc's acceptance
   checklist filled in.

---

## What ships when the rollout is complete

After slice 07 merges, OpenRang has feature parity with the proprietary Boomerang
app's core editor (capture → trim → direction / speed / reps → save → share) —
minus the ads, IAP, watermark, and PRO-locked options. Out of scope for this
rollout (deliberate): audio handling beyond strip, multi-clip composition, cloud
sync, filters/stickers, auto-detection of best loop point. Those become candidates
for a v1.5 rollout once v1 is dogfooded.

---

## Reference: UI inspiration

The structural patterns (separate trim screen with `NEXT`, tabbed editor with
icon-only bottom tab bar, duration indicator at bottom of the preview, back arrow
top-left + save checkmark top-right) are drawn from screenshots of the reference
Boomerang app that Steven shared. OpenRang's visual language stays
project-native: dark theme only, `NeonCoral` / `NeonPurple` / `GlassWhite` /
`DeepCharcoal` tokens (see PRD §3 "Design System Tokens" and Lesson 001 for the
hex-literal rule). The reference app's white bottom sheets become glassmorphic
dark panels; its blue accent becomes our `NeonPurple` (with `NeonCoral` gradient
for primary actions).
