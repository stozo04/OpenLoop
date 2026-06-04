# RESEARCH — Galaxy S23 Save failure: pass-2 reverse emits zero frames

> **Status:** Forensic analysis complete (2026-06-04)
> **Device:** Samsung Galaxy S23, SM-S911U (US / Snapdragon 8 Gen 2), Android 13 (API 33)
> **Log:** user-captured unfiltered logcat `openloop-reverse.txt` (47,994 lines), capture window 13:26:30–13:27:31
> **Companion spec:** [`IMPLEMENTATION.md`](./IMPLEMENTATION.md)

This document is the line-level evidence behind the spec. It exists so the next session (or PR reviewer)
does not have to re-derive the failure chain from a 5.7 MB logcat.

---

## 1. Symptom

User taps Save (green check) in the boomerang editor. Render fails; Pixel 6/8/10 emulators pass the same
flow end-to-end with zero errors.

```
13:27:23.254 E/WM-WorkerWrapper: Work [...BoomerangRenderWorker, boomerang_render] failed because it threw an exception/error
13:27:23.254 E/WM-WorkerWrapper: androidx.media3.transformer.ExportException: Asset loader error
    at androidx.media3.transformer.ExoPlayerAssetLoader$PlayerListener.onTracksChanged(ExoPlayerAssetLoader.java:460)
Caused by: java.lang.IllegalStateException: The asset loader has no audio or video track to output.
```

[`ExoPlayerAssetLoader.onTracksChanged` (1.10.1)](https://github.com/androidx/media/blob/1.10.1/libraries/transformer/src/main/java/androidx/media3/transformer/ExoPlayerAssetLoader.java)
throws this when the item's ExoPlayer selects **neither** an audio nor a video track. Our
`EditedMediaItem`s strip audio by design, so this means: *no selectable video track*. The Transformer
error is the **symptom**; the broken input was produced 3 seconds earlier.

## 2. The failure chain (timestamps from the log)

| Time | Evidence (line) | Event |
|------|-----------------|-------|
| 13:27:16.74 | 35323 | `BoomerangRenderWorker` starts |
| 13:27:16.82 | 35589 | `VideoReverser` reverse start: 1280x720@30, trim 540..4559ms; codecs: `c2.qti.avc.encoder` (HW) + `c2.android.avc.decoder` (SW, Samsung carve-out) |
| 13:27:19.877 | 40174 | **Pass 1 OK** — `MPEG4Writer: Received total (120/0) buffers and encoded 120 frames` |
| 13:27:19.90 | 40225 | Pass 2 plan valid — `reverse pass2: 1280x720, frames=120, sync=120` (all-keyframe intermediate is good) |
| 13:27:21.09 | 41172 | Pass-2 decoder stalls — `CCodecBufferChannel [c2.android.avc.decoder#271] elapsed: n=12 [in=0 pipeline=0 out=8]` |
| 13:27:22.765 | 41513 | Pass-2 encoder at EOS — `QC2Comp [avcE_44] Returning empty EOS buffer as-is as the codec is not started yet` |
| 13:27:22.789 | 41617 | **Pass 2 "succeeds" with nothing** — `MPEG4Writer: Received total (0/0) buffers and encoded 0 frames`; file is 598 bytes (`ftruncate mOffset:598` — moov shell, zero samples) |
| 13:27:22.797 | 41651 | Transformer starts; forward clip's asset loader (`ExoPlayerImpl 3b4426c`) runs ~430ms |
| 13:27:23.235 | 44205 | Second asset loader (`de509a7`) opens the **empty reversed file** |
| 13:27:23.254 | 44275 | `ExportException` ← `IllegalStateException: no audio or video track to output` |

**The editor preview reverse failed identically and earlier** (854x480 preview-res pass at
13:27:04–09): pass 1 encoded 118 frames; pass 2 wrote **0 frames** at 13:27:09.653 (line 32342, same
598-byte shell). The preview's "reverse half" the user saw before Save was already an empty clip —
silently. Pass-2 zero-frame output was **2-for-2 deterministic** in this session.

## 3. Three independent confirmations that zero frames reached the pass-2 encoder

1. **`GraphicBufferSource` (the encoder's input surface).**
   Pass 1: `setting dataspace: 0x104` → `got buffer with new dataSpace #101` 93ms later → frames flow.
   Pass 2 (both runs): `setting dataspace: 0x104` → **never a `got buffer` line** → at teardown,
   `released unpopulated slots: [0..63]` — all 64 slots never held a buffer.

2. **Worker progress telemetry.** `WM-WorkProgressUpdater` published 0 → 9 → 22 → 39% smoothly through
   pass 1 (lines 35519–40030), then jumped **39 → 80** (line 41689) — 80% is `REVERSE_BUDGET`, set
   *after* `reverse()` returns. Pass 2's `emitted` counter (`VideoReverser.kt` pass-2 loop) never
   incremented even once, so `onProgress` never fired in `0.5..1.0` of the reverse budget.

3. **CCodec buffer channel.** Pass-1 decoders log
   `CCodecBuffers [...:Output] popFromStashAndRegister: ... output format changed` (lines 28119, 36488)
   — the moment decoded output becomes deliverable to the app. The pass-2 decoders (`#960` preview,
   `#271` export) **never log output-format registration**. Their decoded buffers sit stashed
   (`out=8`), the app's `dequeueOutputBuffer` only ever sees `INFO_TRY_AGAIN_LATER`, and the only
   buffer ever delivered is the empty EOS — so `render` is never true and
   `encoder.signalEndOfInputStream()` is the encoder's first and only input.

Timing corroborates: both pass-2 runs took ~2.7–2.8s despite a 2.25× pixel-count difference
(854x480 vs 1280x720) — wall clock is dominated by ~120 iterations of 10ms dequeue timeouts, not by
decoding.

## 4. Why pass 2 exits "successfully" with zero frames

In `reverseAllKeyframeVideo`, the encoder — having received only `signalEndOfInputStream()` — emits
`INFO_OUTPUT_FORMAT_CHANGED` followed by an empty EOS buffer. `drainToMuxer` dutifully starts the muxer
on the format change (13:27:22.768, `MP4WtrCtrlHlpLooper Started`) and sees the EOS 21ms later. The
loop's exit condition (`EOS flag && muxerStarted`) is satisfied; nothing throws; `reverse()` returns the
598-byte file. **There is no success criterion anywhere in the reverse pipeline** — that is the root
app-level defect (spec G1/A1).

## 5. Codec-pairing analysis (why S23, why not Pixel, why not pass 1)

- The Samsung carve-out (`openAvcDecoderForReverse` → `samsungSoftwareAvcDecoderTryOrder`) forces the
  AOSP **software** decoder. It was built from **Exynos** RTL findings (S24/S921B); the US S23 is
  **Snapdragon**. On this device the carve-out resolves to `c2.android.avc.decoder` (the preferred
  `c2.google.h264.decoder` is not installed), paired with the `c2.qti.avc.encoder` HW surface encoder.
- Pass 1 uses the **same pairing on the same device and works** (120/118 frames). The deterministic
  differentiator is pass 2's mode of operation: per-frame `seekTo` reverse feeding of the all-keyframe
  intermediate. The vendor-level mechanism (why Samsung's CCodec stack never completes output-format
  registration in this mode) is **not determinable from logcat** — treat as a device behavior to detect
  and route around, not to fix.
- The platform-default **HW decoder works on this device**: this same log shows `c2.qti.avc.decoder`
  decoding for the ExoPlayer editor preview and for the Transformer's own asset loaders throughout the
  session. Media3 itself ships decoder fallback for Samsung-specific codec failures
  ([androidx/media#2189](https://github.com/androidx/media/issues/2189),
  [androidx/media#2362](https://github.com/androidx/media/issues/2362),
  [androidx/media#2751](https://github.com/androidx/media/issues/2751)) — but `VideoReverser` bypasses
  Media3 and hand-picks codecs, so it needs its own fallback.
- Pixels never enter the carve-out (`createDecoderByType` → platform default) — hence 0 errors there.
- The pass-2 loop itself follows the canonical CTS/bigflake pattern
  (`render = info.size > 0`, EOS via `signalEndOfInputStream`):
  [MediaCodec docs](https://developer.android.com/reference/android/media/MediaCodec),
  [bigflake MediaCodec FAQ](https://bigflake.com/mediacodec/),
  [CTS DecodeEditEncodeTest](https://github.com/PhilLab/Android-MediaCodec-Examples/blob/master/DecodeEditEncodeTest.java).
  The app-side loop is not doing anything undocumented.

**HEAD reproduces this.** Traced against `ReverseEncoderSelection.kt` at HEAD: on the S23 the
`c2.google.*` codecs don't exist, `isSamsungVendorAvcCodec` excludes `android.avc`/`exynos`/`c2.sec`
*encoders* but the decoder try-order still lands on `c2.android.avc.decoder`, and the encoder ranking
still picks `c2.qti.avc.encoder` — the exact observed pairing.

## 6. Secondary defects confirmed in this session

1. **Cache poisoning** — `VideoReverser.reverse()` accepts any cached file with `length() > 0`; the
   598-byte shell passes. Every subsequent Save of the same trim window is an instant cache-hit
   failure until scratch is cleared. (Spec §5.1/§5.2; A2.)
2. **`ExportException` escapes `BoomerangRenderWorker`** — `doWork` catches `CancellationException`,
   `IOException`, `RuntimeException`; `androidx.media3.transformer.ExportException` extends `Exception`
   directly and matched **none of them**. The log shows WorkManager's generic
   *"failed because it threw an exception/error"* path, meaning `deletePartialOutput` never ran and the
   worker's designed failure path was bypassed. (Spec §5.5 — corrected; A9.)
3. **The installed APK predates branch instrumentation.** The log contains **zero `OpenLoopReverse`
   lines** despite `ReversePreviewLog` (commit `7930600`) being an ancestor of HEAD and no log-stripping
   in ProGuard rules — the S23 was running an older build. Codec-selection outcome is unchanged at HEAD
   (see §5), but **rebuild from HEAD before re-testing** so `reverse.pass2.done outBytes=…` /
   `encoder.try_order` telemetry is present in the next capture.

## 7. What a fix must achieve (feeds the spec)

| Layer | Requirement | Spec home |
|-------|-------------|-----------|
| Correctness | Pass 2 with 0 muxed samples must throw, never return | §5.2 (G1/A1) |
| Correctness | Cache must validate sample presence, not `length() > 0` | §5.1/§5.2 (G2/A2) |
| Correctness | Worker must catch `ExportException` so cleanup + failure path run | §5.5 (A9) |
| S23 functionality | Zero-frame failure should retry with the **platform-default decoder** (skip the SW carve-out) — validation alone leaves S23 users unable to save | §5.8 (gated step) |
| UX | Loud, friendly failure + Send debug report on save path | §4/§5.6 |

## 7b. Counter-evidence: S25 succeeds with the same pairing (added 2026-06-04, 14:09 capture)

Galaxy S25 (SM-S931B, **API 35**, Snapdragon 8 Elite/SM8750), running a **current-HEAD debug build**
(full `OpenLoopReverse` telemetry present), log `openloop-reverse-s25.txt`:

| Run | Pairing | Pass 1 | Pass 2 | Result |
|-----|---------|--------|--------|--------|
| Preview (854x480) | `c2.qti.avc.encoder` + `c2.android.avc.decoder` | 115 frames | **113 frames, 384,937 B** ✓ | `ensureReversed.ok` |
| Save (1280x720) | same | 115 frames | **113 frames, 695,690 B** ✓ | `Worker result SUCCESS` |

The **same codec pairing that wedged on the S23 works on the S25**. This narrows §5's conclusion:
the SW-decoder carve-out pairing is *not* family-wide broken on Snapdragon Samsungs. Two variables
differ from the failing S23 capture — device/OS (API 33 vs 35) **and** app build (the S23 ran a stale
build lacking the codec-settle delays and unified encoder path; the S25 log shows
`reverse.settle | preReverseDelayMs=400` and `ensureReversed.settle | delayMs=500`, absent from the
S23 log). Until the S23 is re-tested on a current build, both hypotheses are live:

- **H1 — already fixed at HEAD:** the S23 zero-frame wedge was codec-slot contention that the
  settle/unify commits (`4fed93a`, `7930600`, `11d5664`) resolved. §5.8 decoder fallback then becomes
  unnecessary; validation ships as insurance.
- **H2 — S23/API-33-specific:** the older CCodec stack wedges regardless of build. §5.8 stands.

**The deciding experiment is Phase 0 on the S23 with a current-HEAD build** (pull poisoned cache
first — it is still the only real-world invalid fixture).

Minor new observation (not this bug): pass 2 emits 113 of 115 fed frames on both S25 runs — 2 tail
frames consistently lost at EOS drain. Candidate follow-up for the spec's §10 table.

## 7c. VERDICT: H2 confirmed (2026-06-04, 14:19 capture)

Re-ran the identical flow on the S23 with the **same current-HEAD debug build** that succeeded on the
S25 (log: `openloop-reverse-s23-head.txt`). Result: **reproduced exactly** — same codec pairing, same
zero-frame pass 2, same 598-byte shells, same `ExportException` escaping the worker:

| Run | Pass 1 | Pass 2 | Telemetry |
|-----|--------|--------|-----------|
| Preview (854x480) | 69 frames (skipped=39) | **0 frames**, `outBytes=598` | `reverse.complete attempts=1`; `ensureReversed.ok bytes=598` |
| Save (1280x720) | 108 frames | **0 frames**, `outBytes=598` | → `ExportException: Asset loader error` |

Conclusions locked in:

1. **Device/OS-stack difference, not app build**: S23/API 33 wedges, S25/API 35 succeeds, byte-identical
   APK and codec names. The settle/unify commits (H1) did **not** fix the S23.
2. **The existing retry never engages** — `attempts=1` on the zero-frame run; nothing throws. The
   spec's zero-frame `require`/throw (§5.2) is the necessary hook for any retry to fire.
3. **Encoder exonerated with config proof**: `muxer.format_changed` fires at EOS with a fully-formed
   encoder output format (preview run echoes `frame-rate=19` — pass-1's effective rate after
   subsampling; export echoes 30). Configured, alive, starved.
4. **Fixtures secured** (pulled via `run-as` before any cleanup) to `C:\Users\gates\openloop-fixtures\s23\`:
   `reversed-empty-1.mp4` + `reversed-empty-2.mp4` (the 598 B poisoned outputs), `scaled-480-input.mp4`
   + `raw-capture.mp4` (the exact inputs). The empty shells become validator unit-test fixtures; the
   inputs enable a faithful instrumented repro of the §5.8 fallback on-device.

§5.8 (decoder-fallback retry, gated on S23 verification) **stands**. §8's Exynos question remains open;
the API-33-vs-35 split is now the stronger axis to investigate than Snapdragon-vs-Exynos.

## 7d. Fallback pairing experiments + fix verification (2026-06-04, S23 via Samsung RTL)

The zero-frame wedge reproduces in an **isolated instrumented test** with a 12-frame 320x240
synthetic clip (`VideoReverserTest` via `am instrument`) — no camera, no editor, no codec churn.
That made the §5.8 fallback testable on-device in minutes. Pairing matrix measured on the S23:

| Pass-2 pairing (decoder → encoder) | Result |
|------------------------------------|--------|
| `c2.android.avc.decoder` (SW, carve-out) → `c2.qti.avc.encoder` (HW) | **Wedge**: 0 samples, encoder surface never receives a frame |
| `c2.qti.avc.decoder` (HW, platform default) → `c2.qti.avc.encoder` (HW) | **Worse**: `CodecException: Error 0xe` at `queueInputBuffer`, ~130ms into pass 1 |
| `c2.android.avc.decoder` (SW) → **`c2.android.avc.encoder` (SW)** | **WORKS**: 12/12 samples muxed, luma-ramp reversal-correctness assertions pass |

So §5.8's original hypothesis (flip the *decoder*) was **falsified on-device**; the shipped fallback
flips the **encoder** to software on the zero-frame retry. A process-scoped sticky
(`zeroFrameEncoderWedgeSticky`) skips the doomed HW attempt on subsequent reverses — telemetry from
the green run shows the first reverse at `attempts=2` and every later one at `attempts=1` on
`c2.android.avc.encoder`. Deliberately not persisted (reboot/codec updates may fix the device;
rediscovery costs one attempt).

**Verification:** `ReverseOutputValidatorAndroidTest` + `VideoReverserTest` = **11/11 green on the
S23** (the same suite that was 5-passed/6-failed before the fallback), including the cache-poison
regression (`cache_invalid reason=no_video_track bytes=598 — deleting poisoned cache` observed) and
reversal correctness. JVM suite 189/189. Note: Gradle's `connectedDebugAndroidTest` UTP device
provider cannot drive RTL's localhost-tunnel adb — use `adb shell am instrument -w -e class …`
directly.

Remaining manual check for A10: one real capture → loop → save through the app UI on the S23.

## 8. Open questions

- Did the S23 preview visibly play a reverse half at all, or did the loop look forward-only? (The
  reversed preview clip was empty — ExoPlayer likely skipped it as a ~0-duration item.) Worth one
  manual observation when re-testing on the rebuilt APK.
- Does the zero-frame wedge also occur on **Exynos** S23/S24 with the `c2.google.h264.decoder` pairing,
  or is it Snapdragon-/`c2.android.avc.decoder`-specific? The decoder-fallback retry covers both, but
  the answer decides whether the carve-out should be gated on `Build.SOC_MANUFACTURER` (API 31+).
