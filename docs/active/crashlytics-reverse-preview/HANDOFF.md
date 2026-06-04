# HANDOFF — Firebase Crashlytics reverse-preview codec issues

**Last updated:** 2026-06-03 (MCP verification session)  
**Owner context:** Steven Gates · OpenLoop (`io.github.stozo04.openloop`)

Use this doc to resume work in a **new Cursor session** without re-triaging from scratch.

---

## Latest MCP verification (PR #62 · pre–1.0.18 rollout)

**When:** 2026-06-03 · **Auth:** `gates.steven@gmail.com` · **Project:** `openloop-8c266`

| Check | Result |
|-------|--------|
| `topVersions` (14d) | **No** `1.0.18 (18)` — fix build not yet reporting to Crashlytics |
| `3a506c4e` · `lastSeenVersion` | **1.0.9 (9)** only · 3 events · last `2026-06-03T18:14:54Z` · Samsung SM-S921B |
| `b09e527` · `lastSeenVersion` | **1.0.17 (17)** only · 1 event · last `2026-06-03T20:40:17Z` · Android 17 (OS) emulator |
| Events on **1.0.18 (18)** for either issue | **None** (expected until testers install PR #62 build) |
| Issue notes | Empty on both issues |

**Interpretation:** Baseline is clean for the verification version line — historical noise is confined to pre-fix builds. **Field proof still blocked** until [PR #62](https://github.com/stozo04/OpenLoop/pull/62) is merged and **1.0.18+** is installed on Samsung + Android 17 (OS) emulator with `google-services.json`, then re-run `/crashlytics-triage` and the checklist in `docs/diagnostics/crashlytics-issue-3a506c4e-verification.md`.

**Root cause (disease vs symptom):** PR #62 treats Samsung **throws**; ping-pong failing to apply / 120 s emulator timeout is **codec slot churn** (preview + 2-pass reverse). See [`../editor-codec-churn/IMPLEMENTATION.md`](../editor-codec-churn/IMPLEMENTATION.md). Follow-up: `playerEpoch` release on reverse loading + `PRE_REVERSE_CODEC_SETTLE_MS` on all devices.

---

## Where we are now

| Item | State |
|------|--------|
| **Branch** | `fix/crashlytics-3a506c4e-pass1-codec-lifecycle` (tracking `origin/main`) |
| **PR** | [#62](https://github.com/stozo04/OpenLoop/pull/62) — **OPEN**, ready for review/merge |
| **Ship version (on branch)** | **1.0.18** (`versionCode` **18**) — use this to filter Crashlytics after rollout |
| **Prior shipped versions with events** | 1.0.9 (`3a506c4e`), 1.0.17 (`b09e527`) |

**Commits on branch (newest first):**

1. `ba6b7f6` — `chore: bump version to 1.0.18 (18) for Crashlytics verification`
2. `11d5664` — `fix(media): harden reverse preview against codec contention (3a506c4e, b09e527)`
3. `6be4378` — `fix(media): treat pass-1 codec teardown as cooperative cancel`

**Not done yet (post-merge):**

- Merge PR #62 and ship a build **≥ 1.0.18** to testers/Play
- Device QA + Crashlytics field verification (7-day zero-new-events watch)
- Close issues in Firebase console only after verification doc checklist passes

---

## Firebase project constants

| Field | Value |
|-------|--------|
| Firebase project | `openloop-8c266` (`.firebaserc` default) |
| Android **appId** (required for MCP) | `1:95815153197:android:c30254bb713d1e6ae96aa4` |
| Package | `io.github.stozo04.openloop` |
| `google-services.json` | `app/google-services.json` (gitignored; required for Crashlytics in builds) |
| CLI auth | `npx firebase-tools@latest login` (same account as console) |

**Cursor:** Firebase marketplace plugin enabled in `.cursor/settings.json`. Agent skill: `.claude/skills/crashlytics-triage/SKILL.md` — invoke with `/crashlytics-triage`.

**MCP quick commands (new session):**

```
firebase_get_environment
crashlytics_get_report  → topIssues (last 7–14 days)
crashlytics_get_issue   → per issue id below
crashlytics_list_events → sample stacks + custom keys
```

---

## Primary issues (in scope for PR #62)

### 1. `3a506c4ecc5bfeff0ab2b56d58f6e1d6` — pass-1 dequeue churn (Samsung)

| | |
|--|--|
| **Title** | `VideoReverser.runDecodeEncodeLoop` |
| **Subtitle** | `java.lang.IllegalStateException` (often empty message at dequeue) |
| **Type** | NON_FATAL (`ReverseCrashlytics.reportPreviewFailure`) |
| **Last seen (pre-fix)** | **1.0.9 (9)** · Samsung SM-S921B · Android 16 |
| **Console** | [Issue 3a506c4e](https://console.firebase.google.com/project/openloop-8c266/crashlytics/app/android:io.github.stozo04.openloop/issues/3a506c4ecc5bfeff0ab2b56d58f6e1d6) |

**Session pattern (2026-06-03, same user):**

1. `Pending dequeue output buffer request cancelled`
2. `Invalid to call at Released state; only valid in executing state`
3. Empty `IllegalStateException` at `dequeueOutputBuffer`

**Root cause (agreed):** MediaCodec used after cancel/release or ExoPlayer/Transformer contention — **not** the 500k-iteration watchdog string.

**Fix on branch:**

- `MediaCodecLifecycle.kt` — classify lifecycle failures; map to `CancellationException` when job inactive
- `runMediaCodecCancellable` around pass-1/pass-2 dequeue paths
- `OpenLoopViewModel` — skip `markReversePreviewFailed` on `CancellationException`
- `BoomerangEditorScreen` — `playerEpoch++` on reverse loading (`ExoPlayer.release()`, not `stop()` alone)
- `VideoReverser.reverse()` — Samsung retry pass 1+2 after `SAMSUNG_CODEC_CONTENTION_RETRY_MS`

---

### 2. `b09e5277491a4d8935210b9914ca52c5` — surface released at decoder configure

| | |
|--|--|
| **Title** | `VideoReverser.openAvcDecoderForReverse` |
| **Subtitle** | `IllegalArgumentException: The surface has been released` |
| **Type** | NON_FATAL |
| **Last seen (pre-fix)** | **1.0.17 (17)** · Google `sdk_gphone16k_x86_64` · Android 17 emulator |
| **Console** | [Issue b09e527](https://console.firebase.google.com/project/openloop-8c266/crashlytics/app/android:io.github.stozo04.openloop/issues/b09e5277491a4d8935210b9914ca52c5) |

**Root cause:** Pass 1 configured decoder against encoder **input Surface** that was already invalid. Rotating to another **decoder name** with the same dead surface cannot work (documented Pixel RTL bug; same class on Android 17 (OS) emulator).

**Fix on branch:**

- `openSurfaceCodecPipeline()` — encoder + surface + decoder; retry once with fresh encoder pair
- `openAvcDecoderForReverse` — on surface-released IAE, **stop** decoder try-order and rethrow
- `shouldRetryMediaCodecContention` — surface-released retries on **non-Samsung** too
- Pass 1 and pass 2 both use `openSurfaceCodecPipeline`

---

## Related issues (same area — watch after ship, not all proven fixed)

From `topIssues` (7-day window, ~2026-06-04). PR #62 may reduce these via shared pipeline; **verify separately.**

| Issue ID | Location | Message | Last version |
|----------|----------|---------|--------------|
| `3da25acc4fcc12975a4e122e8666c8b9` | `transcodeToAllKeyframes` | Surface has been released | 1.0.12 |
| `d848b6c35e6787392fb03eb09d665637` | `awaitEncoderOutputFormat` | AVC encoder did not publish output format in time | 1.0.11 |

**Separate watch (not this PR):** editor OOM `ef2823cfeeb3f1f59ef7308c266a7110` — see `docs/active/editor-memory-oom/IMPLEMENTATION.md`.

---

## Key code touched (PR #62)

| File | Role |
|------|------|
| `app/.../media/MediaCodecLifecycle.kt` | Lifecycle detection, cancel mapping, retry policy |
| `app/.../media/VideoReverser.kt` | `openSurfaceCodecPipeline`, reverse retry loop, cancellable dequeue |
| `app/.../media/DeviceMediaHints.kt` | `SAMSUNG_CODEC_CONTENTION_RETRY_MS`, `SAMSUNG_REVERSE_PASS_MAX_ATTEMPTS` |
| `app/.../ui/EditorPlaylistBind.kt` | `requiresPlayerEpochBumpForReversePreview` |
| `app/.../ui/BoomerangEditorScreen.kt` | Immediate ExoPlayer teardown when reverse loading |
| `app/.../ui/OpenLoopViewModel.kt` | Ignore `CancellationException` in `ensureReversed` failure path |
| `app/.../diagnostics/ReverseCrashlytics.kt` | Non-fatals + custom keys (`reverse_outcome`, etc.) |

**Tests:** `MediaCodecLifecycleTest` (7 cases, exact Crashlytics strings), `EditorPlaylistBindTest`.

**Docs:**

- `docs/diagnostics/crashlytics-issue-3a506c4e-verification.md` — field verification checklist
- `docs/diagnostics/firebase-crashlytics-trimming.md` — product diagnostics / custom keys

---

## What “resolved” means (do not skip)

Historical events on **1.0.9 / 1.0.17** stay in the console forever.

**Proof of fix requires:**

1. Merge PR #62 and distribute **1.0.18+** with `google-services.json`
2. Repro: editor → reverse mode on **Samsung** + **Android 17 (OS) emulator** (b09e527 path)
3. Logcat: no `reverse_preview_failure` for benign cancel/churn
4. Crashlytics MCP: `crashlytics_list_events` for `3a506c4e` and `b09e527` — **no events** with `versionDisplayName` **1.0.18 (18)**
5. **7+ days** zero new events → close issues in console

**Local gates already green on branch:**

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
```

**Device (after merge):**

```bash
./gradlew :app:installDebug
adb shell am instrument -w -e class io.github.stozo04.openloop.media.VideoReverserTest \
  io.github.stozo04.openloop.test/androidx.test.runner.AndroidJUnitRunner
```

---

## Suggested next actions (pick up here)

1. **Review/merge** [PR #62](https://github.com/stozo04/OpenLoop/pull/62).
2. **Internal or Play build** with version **1.0.18**; install on Samsung S24-class device + Android 17 (OS) emulator.
3. Run checklist in `docs/diagnostics/crashlytics-issue-3a506c4e-verification.md`.
4. New session: `/crashlytics-triage` → confirm no new events on **1.0.18** for `3a506c4e` and `b09e527`.
5. If `3da25acc` / `d848b6c35` still fire on 1.0.18, open a follow-up issue/PR (encoder timeout is a different failure mode).

---

## Conversation / references

- Firebase MCP setup: [Crashlytics AI assistance](https://firebase.google.com/docs/crashlytics/ai-assistance-mcp)
- Prior trimming overlay work: `docs/active/editor-trimming-overlay-stuck/`, `trimming-loop.md`
- PR #62 body has test-plan checkboxes for GitHub review
