# Issue #40 — Background Export Kickoff Prompt

Copy everything below the line into a fresh Claude Code / Cursor session with the OpenLoop repo mounted. This kickoff implements **[Issue #40 — P0: Survive backgrounding during Loopifying](https://github.com/stozo04/OpenLoop/issues/40)** (WorkManager + `mediaProcessing` FGS).

---

## Session Prompt — Issue #40 Background Export During Loopifying

You are working on **OpenLoop** — an open-source Android camera app (Kotlin/Jetpack Compose) for speed-controlled video loops. Repo: `stozo04/OpenLoop`. Package: `io.github.stozo04.openloop`. Owner: Steven Gates (@stozo04).

**Severity: P0 for public Production** (owner timeline: ~2 weeks). Internal testing can proceed with a "stay on screen" warning until this ships — **do not go public without it**.

## Problem (plain English)

When the user taps Save, **"Loopifying…"** runs `VideoReverser` + Media3 `Transformer` inside `viewModelScope` in `OpenLoopViewModel.saveBoomerang()`. If the user presses **Home**, switches apps, locks the screen, or closes the Fold cover, Android can kill the process — the loop fails with no notification and no recovery.

**Back is already handled correctly** (consumed). This issue is **leaving the app**, not Back navigation.

## Critical Rule — Do Not Trust Your Training Data

Web-search `developer.android.com` for: WorkManager long-running workers, `mediaProcessing` foreground service type, `FOREGROUND_SERVICE_MEDIA_PROCESSING`, FGS timeouts (6 h/24 h), `POST_NOTIFICATIONS` on API 33+, Android 16 progress-centric notifications. Verify WorkManager version against `developer.android.com/jetpack/androidx/releases/work`.

## Authoritative spec

**Issue #40 on GitHub** — full implementation plan. This kickoff is the session execution guide. On conflict, Issue #40 wins after owner confirmation.

**Related:** Issue #39 (audit), Issue #41 (perf — orthogonal), P2 cancel button (coordinate — cancel must cancel WorkManager work).

## Before Writing Any Code — Read These Files

1. **`CLAUDE.md`**
2. **Every file in `docs/lessons_learned/`** — 004 (Context), 013 (cancel/narrow catch), 015 (back during processing)
3. **`docs/DEFINITION_OF_DONE.md`**
4. **`docs/ANDROID_STANDARDS.md`**
5. **`app/src/main/java/.../ui/OpenLoopViewModel.kt`** — `saveBoomerang()`
6. **`app/src/main/java/.../media/VideoProcessor.kt`** — `runTransformer()`, cancellation
7. **`app/src/main/java/.../ui/ProcessingScreen.kt`**
8. **`app/src/main/java/.../MainActivity.kt`** — Factory wiring for `VideoProcessor` / `VideoReverser`
9. **Issue #40** — acceptance criteria

## Phase 1 — Branch + baseline

```powershell
git checkout main
git pull --rebase
git checkout -b feature/issue-40-background-export
.\gradlew.bat clean assembleDebug --console=plain; echo "EXIT=$LASTEXITCODE"
```

## Phase 2 — Web-verify (mandatory searches)

Document URLs in PR:

| API / library | Verify |
|---------------|--------|
| WorkManager 2.x latest stable | `setForegroundAsync`, `CoroutineWorker`, `WorkInfo` progress |
| `ForegroundInfo` + `FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING` | API 29+ service type param |
| Manifest merge for `SystemForegroundService` | `android:foregroundServiceType="mediaProcessing"` |
| Play Console FGS declaration | Policy → App content → Foreground service permissions |
| `POST_NOTIFICATIONS` request timing | API 33+ — when to request for export notification |

## Phase 3 — Implement (follow Issue #40 phases)

### 3a. Gradle + Manifest

- Add `androidx.work:work-runtime-ktx` (verify latest version via web search)
- Permissions: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROCESSING`, `POST_NOTIFICATIONS`
- Merge WorkManager foreground service with `mediaProcessing` type

### 3b. `BoomerangRenderWorker`

New: `app/src/main/java/io/github/stozo04/openloop/work/BoomerangRenderWorker.kt`

- Input `Data`: paths, trim, mode, speed, filter, repetitions, raw_id, output_path, return_to_gallery
- `setForegroundAsync()` before encode with progress notification channel **"Loop export"** (low importance)
- Call existing `VideoProcessor.renderBoomerang()` — reuse MainActivity wiring pattern via `Application` context
- **Transformer on Main:** today `runTransformer` uses `Dispatchers.Main` — preserve or refactor carefully; document decision
- Progress: `setProgressAsync()` throttled ~1 Hz → notification + WorkInfo
- Success: `registerBoomerang`, `discardScratch`
- Failure/cancel: delete partial output, `Result.failure()` — **no retry loop** for user-initiated render

### 3c. ViewModel refactor

`saveBoomerang()`:

1. Promote scratch → raw (sync, unchanged)
2. Allocate boomerang path
3. `enqueueUniqueWork("render_${scratchUuid}", KEEP, request)`
4. `_uiState = Processing`
5. Observe `WorkManager.getWorkInfoByIdFlow(workId)` → map progress → `_renderProgress`
6. On `SUCCEEDED`: emit Share event, navigate (gallery vs camera per `importedSession`)
7. On `FAILED`: `failBackToEditor`

**Keep** `ensureReversed()` in ViewModel for editor preview — not in Worker.

### 3d. UI + permissions

- `ProcessingScreen`: copy — **"You can leave — check your notification for progress."**
- Request `POST_NOTIFICATIONS` on first save (API 33+) — if denied, show in-app-only progress + explain background may not survive
- Notification tap → `PendingIntent` to `MainActivity` `SINGLE_TOP`

### 3e. Play Console note for owner

Add comment template in PR: declare `mediaProcessing` FGS in Console.

## Phase 4 — Tests

**Unit:** Worker Data keys round-trip; failure deletes partial file (mock storage)

**Instrumented:**

- Enqueue worker with tiny clip → `SUCCEEDED`
- Optional: Home key during work → still completes (Fold, long timeout)

Update `OpenLoopViewModelTest` — save enqueues work (inject `WorkManager` test double or robolectric if project pattern exists).

## Phase 5 — Manual QA (Pixel Fold 10 — required)

1. Save 15 s F→R → Home at ~20% → wait → return → loop in gallery ✅
2. 4K import save → Home → completes ✅
3. Notification shows progress, dismisses on complete ✅
4. Deny POST_NOTIFICATIONS → in-app still works; document background behavior ✅
5. Fold cover close during render → completes ✅

## Phase 6 — DoD + PR

Full `docs/DEFINITION_OF_DONE.md` gate including lint + release build + screenshot of notification + Processing screen.

PR title: `Issue #40 — WorkManager background Loopifying export`
Closes #40

## Edge cases (from Issue #40)

| Scenario | Handling |
|----------|----------|
| Duplicate Save | `enqueueUniqueWork` KEEP/REPLACE on scratch UUID |
| Raw promoted, render fails | Keep raw — user retries (owner decision) |
| Share sheet | Defer until Activity resumed (existing `onShareSheetClosed` pattern) |
| Force-stop | Work cancelled — acceptable |

## Behavioral Rules

- Do not rewrite `VideoReverser` / pipeline logic — **move orchestration only**
- Lesson 013: never swallow `CancellationException`
- Coordinate with P2 cancel: expose `cancelRenderWork()` calling `WorkManager.cancelUniqueWork`

## When to Stop and Ask Steven

- Transformer cannot run off Main without major refactor — propose `runBlocking(Main)` in Worker with perf note
- WorkManager + Media3 interaction fails on API 26 device — need minSdk verification
- Notification UX choice: indeterminate vs percent bar (recommend percent; Android 16 ProgressStyle optional)
- Merge conflict with P2 cancel branch

## Out of Scope

- Issue #41 perf optimizations (CodecDB Lite, pre-scale)
- Replacing `VideoReverser` algorithm
