# Crashlytics issue `3a506c4e` — verification checklist

**Issue:** `VideoReverser.runDecodeEncodeLoop` · `IllegalStateException` (NON_FATAL)  
**Firebase:** [openloop-8c266 / issue 3a506c4ecc5bfeff0ab2b56d58f6e1d6](https://console.firebase.google.com/project/openloop-8c266/crashlytics/app/android:io.github.stozo04.openloop/issues/3a506c4ecc5bfeff0ab2b56d58f6e1d6)  
**Branch:** `fix/crashlytics-3a506c4e-pass1-codec-lifecycle` · **Ship:** 1.0.18 (18) · **Handoff:** [`docs/active/crashlytics-reverse-preview/HANDOFF.md`](../active/crashlytics-reverse-preview/HANDOFF.md)

Historical events (e.g. **1.0.9**) will remain in the console. Success means **no new events** after a build that includes this fix ships to testers/Play.

---

## What the fix does

| Layer | Behavior |
|-------|----------|
| **Cancel / teardown** | `MediaCodecLifecycle` maps Released/cancelled dequeue failures to `CancellationException` when the reverse job is no longer active — no `reverse_preview_failure` non-fatal, no forward fallback snackbar. |
| **Active Samsung contention** | Immediate `EditorPlaylistBind.teardownPlayerForReversePreview` (no 150ms debounce while Trimming/Loopifying); one retry of pass 1+2 after `SAMSUNG_CODEC_CONTENTION_RETRY_MS` when dequeue fails with lifecycle errors while the job is still active. |
| **Mainline (already on `main`)** | `EditorPlaylistBind.shouldHoldPlaylist`, Samsung pre/post-transform settle delays, software AVC codec pairing, `ReverseScratchJanitor`, preview 480p short-side cap on Samsung. |

---

## Pre-ship (developer)

1. Build **release** with `app/google-services.json` present.
2. `./gradlew :app:testDebugUnitTest` — includes `MediaCodecLifecycleTest`, `EditorPlaylistBindTest`.
3. `./gradlew :app:lintDebug` — no new errors.

---

## Device QA (Samsung strongly recommended)

Use the same class of repro as the Crashlytics session (camera AVC trim ~8s, ping-pong / reverse mode).

1. **Enter editor from Trim** — overlay shows Trimming/Loopifying; confirm preview does **not** flash forward playback before reverse completes.
2. **Rapid churn** — toggle direction or nudge trim handles twice while overlay is visible; expect overlay to settle or retry, **not** forced forward-only snackbar every time.
3. **Background** — start reverse, press Home, return; no crash; cancel path should not spam failures.
4. **Success path** — reverse completes, ping-pong preview plays, Save works.

---

## Field verification (Crashlytics MCP or console)

After the fixed build is on devices (note **versionName** / **versionCode**):

1. `crashlytics_get_report` · `topIssues` · filter last 14 days · app `1:95815153197:android:c30254bb713d1e6ae96aa4`.
2. Confirm issue `3a506c4ecc5bfeff0ab2b56d58f6e1d6` has **no new events** on builds ≥ the fix version.
3. Optional: `crashlytics_list_events` on that issue — last event timestamp should be **before** the fixed rollout.
4. Watch `reverse_preview_failure` custom keys — Released/cancelled/empty `IllegalStateException` should drop; timeouts (`Timed out after 120s`) are a separate bucket.

Use skill `/crashlytics-triage` for the full prioritize → investigate flow.

### Baseline MCP snapshot (2026-06-03, before 1.0.18 rollout)

| Issue | Last version | Last event (UTC) | Events on 1.0.18 (18) |
|-------|--------------|------------------|------------------------|
| `3a506c4e` | 1.0.9 (9) | 2026-06-03T18:14:54Z | 0 |
| `b09e527` | 1.0.17 (17) | 2026-06-03T20:40:17Z | 0 |

`topVersions` (14d) did not list `1.0.18 (18)` — no Crashlytics traffic from the PR #62 ship line yet.

---

## Related: issue `b09e527` (surface released at decoder configure)

**Issue:** `VideoReverser.openAvcDecoderForReverse` · `IllegalArgumentException: The surface has been released`  
**Console:** [b09e5277491a4d8935210b9914ca52c5](https://console.firebase.google.com/project/openloop-8c266/crashlytics/app/android:io.github.stozo04.openloop/issues/b09e5277491a4d8935210b9914ca52c5)

Sample (1.0.17): pass 1 on **Google Android 17 (OS) emulator** (`sdk_gphone16k_x86_64`; not API level 17 — `minSdk` is 26) — encoder input [Surface] invalid before `decoder.configure`. Fix: `openSurfaceCodecPipeline` recreates encoder+surface once; do not rotate decoder names on a dead surface; outer `reverse()` retry when `isMediaCodecSurfaceReleasedFailure`.

---

## Close criteria

- [ ] Fixed build in Play internal / production track used by repro devices  
- [ ] 7+ days with zero new events on `3a506c4e` for that build line  
- [ ] 7+ days with zero new events on `b09e527` for that build line  
- [ ] No regression in editor OOM issue `ef2823cf` (separate watch)
