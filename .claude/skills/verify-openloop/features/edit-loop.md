# Edit — Loop

Chooses the playback direction pattern for the trimmed clip: forward-only, reverse-only, or the classic boomerang ping-pong. Directions that need a reversed media file trigger on-device reverse (`Loopifying..`) and can take a long time; forward-only does not.

## Sub-features

- `loop-open` — toolbar `Loop` → `loop_tab_panel`, title `Select loop direction`.
- `loop-forward` — pick `Forward loop` (fast path; no reverse encode).
- `loop-boomerang` — `Forward then reverse` (default product direction; picking it again may be a no-op).
- `loop-reverse` — `Reverse loop` or `Reverse then forward` (exercises `VideoReverser`; wait up to ~120 s).
- `loop-help` — info control `What do the loop icons mean?` → help dialog → `Got it`.
- `loop-reverse-failed` — if reverse fails: `Couldn't loop that clip` / `TRY AGAIN` / `SEND DEBUG REPORT` / degrade to forward (lesson 020 / 026).

## How to get to it (user POV)

- In the editor, tap **Loop**.
- Tap a direction chip. Wait if the preview says `Loopifying..`.

## Driving it with control.ps1

Preconditions:

- Doctor passes. Editor available.
- Evidence dir `edit-loop/` created.
- Start logcat before reverse-heavy picks (see `run-e2e`).

- **Open.** `control.ps1 tap -Label "Loop"`. Dump: `loop_tab_panel`, direction chips.
- **Fast proof.** Tap `Forward loop`. Preview should keep playing without a long `Loopifying..`.
- **Reverse proof (optional / slow).** Tap `Reverse then forward` or `Reverse loop`. Wait for overlay to clear (≤120 s). Dump after: no stuck `Loopifying..`; preview playing or reverse-failed panel.
- **Help (optional).** Tap the loop info control → dialog → `Got it`.
- **Proof.** `loop-forward.txt`; if reverse run: `loop-reverse-before.txt`, `loop-reverse-after.txt`, logcat scan.

## Gotchas

- Default is already **Forward then reverse** (Boomerang). Re-tapping it does **not** prove a change — use `Forward loop` for smoke, a reverse chip when you mean to stress the pipeline.
- `Preview unavailable` / looks gate issues after reverse failure are findings — see lesson 026.
- Reverse cost depends on OEM / resolution (`DeviceMediaHints`); Pixel AVDs still can take tens of seconds.
- Direction chips use testTags `direction_chip_*` — prefer visible labels for `control.ps1 tap`.
- Full path: [edit-and-save](./edit-and-save.md).
