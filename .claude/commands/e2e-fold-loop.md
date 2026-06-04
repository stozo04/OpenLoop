# /e2e-fold-loop — Multi-device E2E bug-fix loop (google-pro-fold-video.mp4)

> **Status: GOAL MET 2026-06-04** (branch `feature/e2e-fold-loop-fixes`). Iteration 1 found and
> fixed BUG-1 (pass-1 jitter subsampling → 15 fps + corrupted reverse half, commit `731b26b`);
> iteration 2 confirmed a zero-change clean sweep on all three devices. Reports + state:
> `docs/e2e/fold-loop/`. BUG-2 (skip-at-input corruption for >30 fps sources) remains open —
> see STATE.md. Re-run this loop after any media-pipeline change, or whenever a Pixel-family
> regression is suspected.

## THE GOAL

**A fully working, shareable Boomerang produced by OpenLoop on each of three emulators —
Pixel 6, Pixel 8, Pixel 10 Pro Fold — exercising every editor option.** On EVERY device, in a
single run:

1. `google-pro-fold-video.mp4` imports cleanly (lands on the Trim screen).
2. **Trim** — the window can be changed and the change takes effect.
3. **Speed** — playback speed can be changed and takes effect.
4. **Loop types** — every direction works, including reverse-needing ones (real reverse
   preview, no timeout, no "forward only" fallback, no failure overlay).
5. **Looks** — a non-Original filter previews without error.
6. **Save** — a real `boom_*.mp4` + share sheet (`BoomerangRenderWorker` SUCCESS in logcat).
7. The file is a **genuine boomerang**: duration = trim × speed × direction, a real reversed
   half at full frame rate, mirror-point frames match, clean visual quality (no
   scratchy/corrupt/green/black/frozen frames) — decided programmatically by the quality gate.
8. Gallery playback works and the logcat scan is clean (no crashes, ANRs, codec errors,
   120 s timeouts).

Anything short of that on any device is a bug: find it, root-cause it, fix it, prove the fix —
**without breaking the verified Samsung S23 path**. **The loop is DONE when one full iteration
passes 1–8 on all three devices with ZERO code changes during that iteration** (a sweep that
needed a mid-run fix doesn't count; the next iteration must confirm).

## Session bootstrap (context does not persist between sessions)

1. Read `CLAUDE.md` + every numbered lesson in `docs/lessons_learned/` (013, 018–023 are the
   media-pipeline ones you'll need).
2. Read `docs/e2e/fold-loop/STATE.md` — iteration number, open bugs with fix-attempt counts,
   what to do first. Missing = iteration 1: create it (template at bottom).
3. Read `.claude/skills/run-e2e-pixel-sweep/SKILL.md` in full — **it IS the mechanics** (the
   scripted prep/drive/gate phases, validated 2026-06-04). Its parent `run-e2e` skill supplies
   `scan-logcat.ps1` + the signature catalog and the honesty rules; both apply verbatim.
4. Git: branch `feature/e2e-fold-loop-fixes` (create from `main` if absent). Never commit to
   `main`. Never commit the video (gitignored).
5. **Do not trust training data.** Before any claim about Android API behavior or any fix
   design, WebSearch `developer.android.com` scoped to the repo's actual versions (re-read the
   table in `CLAUDE.md` / `gradle/libs.versions.toml` — currently targetSdk 36, Kotlin 2.3.21,
   Media3 1.10.1, CameraX 1.6.1). Pure-math fixes verified by device evidence in front of you
   are exempt; API usage is not.

## Running one iteration

1. Verify preconditions (the sweep skill lists them — video present + hash match, AVDs, ffmpeg,
   JAVA_HOME, no emulator running).
2. Build once: `:app:assembleDebug` genuinely green (`BUILD SUCCESSFUL` + exit 0 + zero `e:`).
3. Per device (Pixel_6 → Pixel_8 → Pixel_10_Pro_Fold, sequential, cold boots): run the sweep
   skill's four phases — `sweep-prep.ps1` → `drive-flow.ps1` (add `-Fold` for the Fold) →
   `quality-gate.ps1` → `scan-logcat.ps1` → `emu kill`.
4. Any FAIL → Bug handling protocol below.
5. Write the iteration report + update STATE.md (formats below), checkpoint-commit.

## Bug handling protocol

1. **Root-cause first** — logcat lines + code reading + artifact forensics (pull the cached
   reversed clip from `cache/scratch/reversed/` to isolate reverser vs Transformer; pull
   evidence frames). Name the defective `file:line`. "It's flaky" is not a root cause.
2. **Check STATE.md** — a bug with 3 failed fix attempts STOPS the loop; escalate with
   everything you know. Do not thrash.
3. **Samsung no-touch rule:** a fix requiring edits to the Samsung carve-out or reverse codec
   selection (`VideoReverser.kt` carve-out/try-order/sticky-fallback, `ReverseEncoderSelection.kt`,
   anything gated on `isSamsungDevice()`) → STOP and ask the user first. Fixes elsewhere in
   those files are allowed but must be minimal, and any behavior change that also reaches the
   Samsung path (BUG-1's was: at-cap sources now feed all frames through pass 1) must be called
   out LOUDLY in the report + STATE.md with an S23 re-verify recommendation.
4. **Fix** → full JVM suite green (`:app:testDebugUnitTest`, 191+ passing, 0 failures — count
   from the XML results, not `BUILD SUCCESSFUL`) → `:app:lintDebug` zero new errors → rebuild →
   re-run the failing device → confirm the logcat/gate signature is GONE (never verify by UI
   appearance alone) → **`pm clear` before the re-run or the trim-keyed reverse cache serves
   the pre-fix artifact**.
5. **Checkpoint commit**: `fix(e2e-loop): <what> — <device(s)>, iter <N>`. Real fix only — no
   scratch artifacts, logcat dumps, or pulled videos. Devices that passed earlier re-run on the
   post-fix APK.

## Known-bug field guide (verified forensics — priors, not gospel)

Open at HEAD (the 2026-06-04 sweeps did NOT trigger these — they need a wedged codec, which
emulator software codecs don't reproduce):

1. **No in-pipeline progress deadline** — pass 2's `while(!outputDone)` has no wall-clock
   budget; a codec spinning `INFO_TRY_AGAIN_LATER` rides to the ViewModel's 120 s timeout.
   Symptom: `Timed out after 120s` with no exception from `VideoReverser`.
2. **Timeout abandons, doesn't reap** — `select{onTimeout}` cancels without awaiting
   (`OpenLoopViewModel.kt:~866`); a worker blocked in a codec call leaks decoder+encoder+surface,
   and a retry stacks a second 2-codec reverse on top. Symptom: second reverse fails after a
   first timed out; codec-component counts climb.
3. **Save path has no timeout** — `BoomerangRenderWorker` → `reverse()` runs un-budgeted; the
   same stall = indefinite "Render worker reported failure". Same disease as #1, two symptoms.
4. **BUG-2 (fold-loop iter 1):** when pass-1 subsampling legitimately engages (>30 fps source),
   it drops *compressed* samples → broken P-frame chains → moving-region macroblock smear.
   Fix design: decide ENCODE/SKIP at decoder output (render selectively), not at input. Needs
   a synthetic 60 fps fixture to verify. See STATE.md.

Signature → meaning map and refuted dead ends: `run-e2e-pixel-sweep/SKILL.md` ("Reading
failures") and `run-e2e/references/logcat-signatures.md`. Two standing rules: **emulators run
host software codecs** — a green sweep proves nothing about the physical Fold/S23 wedges, and
a timeout is NOT a crash — report them separately.

## Iteration report + state

Report: `docs/e2e/fold-loop/<yyyy-MM-dd_HHmmss>-iter-<N>.md` — per-device step table, gate
metrics, scan count table, bugs → root cause → fix commit, honest "could not verify" list.
(Iters 1–2 in that folder are the worked examples.)

STATE.md keeps: iteration number + last result per device, consecutive clean sweeps, open bugs
with fix-attempt counts (max 3), fixed bugs with commits, "next iteration starts with".

## Loop exit conditions

- **SUCCESS:** clean sweep (items 1–8, all devices, zero code changes this iteration) →
  announce, summarize fixes on the branch, recommend a PR (full Definition-of-Done gate applies
  at PR time). Don't schedule another iteration.
- **ESCALATE:** 3 failed fix attempts on one bug; the video missing/hash-changed; a fix needs
  Samsung-path changes; emulator infra broken (AVD won't boot twice in a row).
- Otherwise: finish, write state, let the loop schedule the next iteration.

---

## Lessons learned (iteration 1–2 retrospective, 2026-06-04)

### What worked — keep doing

- **Bounds-derived gestures over hardcoded coordinates.** The only reason the Fold's
  2076×2152 inner display cost nothing. Now enforced by `drive-flow.ps1`.
- **Capture logcat to a file first, poll the file for terminal events**
  (`ensureReversed.(ok|timeout|fail)`, `Worker result … BoomerangRenderWorker`). Reliable,
  resumable, and the file doubles as the scan input and the evidence record.
- **The quality gate caught a real shipping bug a "did it save?" check would have passed.**
  Per-half fps math + extracted frames found the 15 fps/corrupted reverse half; SSIM alone
  would NOT have (corrupt half still scored 0.88). Frames > metrics.
- **Artifact forensics for root-causing:** pulling the cached reversed clip isolated pass-1
  vs pass-2 vs Transformer in one step; frame timestamps (`ffprobe -show_entries frame=pts_time`)
  turned "looks stuttery" into "33,222 < 33,333, floor comparison, file:line".
- **Cold boots + one emulator at a time + `pm clear`:** zero false codec findings, zero cache
  poisoning across two iterations and two validation runs.
- **Slow 1300 ms trim drags:** 5/5 first-try success; the feared trim-handle bug never fired.
- **Checkpoint commits with the device evidence in the message** — the fix commit reads as a
  self-contained bug report.

### What did NOT work — removed from the procedure

- `run-as … cat > /sdcard/…` for pulling outputs (0 bytes, silently) and PowerShell pipes for
  binary (mangled). Replaced by `cmd /c "adb exec-out run-as … cat > file"` everywhere.
- Hardcoded tap coordinates (broke on the Fold). Replaced by bounds math.
- Watching for the "Loop saved / View" snackbar (expires before a dump-tap cycle). Replaced by
  tapping the newest-first grid tile.
- Substring label taps where a dialog is involved ("Allow" matched the question text, not the
  button). Replaced by exact-match taps.
- Bash-based boot monitors with `adb` in tight loops (timed out twice / flaky on Windows).
  Replaced by plain PowerShell poll loops inside the prep script.
- `am start` immediately after `pm clear` on a cold-booting device — pm clear's async data
  wipe SIGKILLs the fresh process seconds later (looks like a launch crash + scares the scan
  with `process died`). Prep now clears early, settles 8 s, and the flow self-heals.

### What I wish I'd known at iteration-1 start (now encoded in the sweep skill)

- The POST_NOTIFICATIONS dialog ambushes the first save on a fresh install.
- Folding the Fold AVD locks the screen; the unfold needs the wakeup → `wm dismiss-keyguard` →
  wakeup dance, and the emulator clock can jump after unlock.
- The reverse cache is trim-keyed and survives reinstalls — re-verifying a reverser fix
  without `pm clear` replays the pre-fix artifact.
- The export's reversed half comes from the SAME cache as the preview (the "export uses
  full-rate source" comment was stale) — preview-side quality decisions ship in the file.
- B&W look ⇒ U=V=128 chroma in the export: programmatic proof the filter applied, and a trap
  for any naive "flat chroma = corruption" check.
- The photo picker's pushed-video label is "Video taken on <date> … duration …" + "Done".
- All three AVDs report the same model string (`sdk_gphone16k_x86_64`) — identify by
  `adb emu avd name`.
- Renders are deterministic per trim window (SSIM identical to 6 d.p. across runs) — a changed
  metric means a changed pipeline, not noise.

### STATE.md template (iteration 1 only)

```markdown
# E2E Fold Loop — State
- Iteration: <N> (last run <timestamp>)
- Source video: <path> <bytes> <duration/res/fps>
- AVD names: <pixel6> | <pixel8> | <fold>
- Last iteration result: P6 <PASS/FAIL> · P8 <PASS/FAIL> · Fold <PASS/FAIL>
- Consecutive clean sweeps: <0 or 1>
## Open bugs
- [BUG-<n>] <one-liner> — root cause <file:line or "unknown">, fix attempts <k>/3, status
## Fixed this loop
- [BUG-<n>] <one-liner> — commit <hash>, verified on <devices>
## Next iteration starts with
- <e.g. "re-verify BUG-3 fix on Pixel 8 first, then full sweep">
```
