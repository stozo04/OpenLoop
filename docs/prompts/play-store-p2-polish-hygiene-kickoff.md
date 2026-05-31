# Play Store P2 — Polish & Policy Hygiene Kickoff Prompt

Copy everything below the line into a fresh Claude Code / Cursor session with the OpenLoop repo mounted. This kickoff resolves **P2 — Polish / policy hygiene** from [Issue #39](https://github.com/stozo04/OpenLoop/issues/39).

---

## Session Prompt — Play Store P2 Polish & Policy Hygiene

You are working on **OpenLoop** — an open-source Android camera app (Kotlin/Jetpack Compose) for speed-controlled video loops. Repo: `stozo04/OpenLoop`. Owner: Steven Gates (@stozo04).

**Timeline:** P2 items should land **before or with** public Production (~2 weeks). They are not launch blockers like P0, but improve review completeness and UX on slow renders.

**Depends on:** P1 audio removal (if merged) — update content-rating / store copy to camera-only.

## Critical Rule — Do Not Trust Your Training Data

Web-search `developer.android.com` and Play Console help before stating requirements for content ratings, target audience, IARC questionnaires, or cancel UX during long operations.

## P2 Scope (from Issue #39)

| # | Item | Type |
|---|------|------|
| P2-1 | **Loopifying cancel path** — `ProcessingScreen` consumes Back; no mid-render cancel | Code + UX |
| P2-2 | **Content rating + target audience** — Console forms manual | Docs + owner paste |

**Not in P2:** Issue #40 (background export — better fix for "stuck" feeling), Issue #41 (perf).

## Before Writing Any Code — Read These Files

1. **`CLAUDE.md`**
2. **Every file in `docs/lessons_learned/`** — 013 (cancellation), 015 (predictive back), 016 (deferred progress reads)
3. **`docs/DEFINITION_OF_DONE.md`**
4. **`docs/play-store/content-rating.md`**
5. **`docs/play-store/store-listing.md`** — target audience 13+
6. **`app/src/main/java/.../ui/ProcessingScreen.kt`**
7. **`app/src/main/java/.../ui/OpenLoopViewModel.kt`** — `saveBoomerang()`, render job
8. **`app/src/main/java/.../MainActivity.kt`** — Processing branch BackHandler

## Phase 1 — Branch + baseline

```powershell
git checkout main
git pull --rebase
git checkout -b feature/processing-cancel-and-play-console-p2
.\gradlew.bat clean assembleDebug --console=plain; echo "EXIT=$LASTEXITCODE"
```

## Phase 2 — Web-verify

| Topic | Why |
|-------|-----|
| Predictive back + custom cancel dialogs | `developer.android.com` — confirm dialog + BackHandler interaction at targetSdk 36 |
| Long-running operation UX | Material guidance for progress + cancel |
| Play content rating / IARC | `support.google.com/googleplay/android-developer` content rating |
| Target audience declaration | Play policy for apps without user-generated public content |

## Phase 3 — P2-1: Mid-render cancel (scoped UX)

**Problem:** On slow devices (4K HDR import, 30 s trim), Loopifying can run 30–60+ s. User cannot cancel — feels stuck. Back is intentionally consumed to prevent Activity finish mid-encode (Lesson 015).

**Design (propose in PR, implement minimally):**

1. Add **Cancel** text button on `ProcessingScreen` (not system Back — explicit affordance).
2. On cancel:
   - Cancel render coroutine / call `Transformer.cancel()` (Lesson 013 — propagate `CancellationException`)
   - Delete partial boomerang output if allocated
   - **Keep promoted raw** (matches #40 open question — user can retry from editor)
   - Route to `OpenLoopUiState.BoomerangEditor` with trim/direction/speed preserved
3. Show confirmation dialog: "Stop creating this loop?" — prevents accidental tap.

**Coordination with Issue #40:** When WorkManager lands, cancel must call `WorkManager.cancelUniqueWork()`. Structure cancel behind a `RenderJobHandle` interface if #40 merges soon — or note follow-up issue.

**Out of scope:** Background export notification (that's #40).

### Files likely touched

- `ProcessingScreen.kt` — Cancel button + optional confirm dialog
- `OpenLoopViewModel.kt` — hold `Job` reference for save; `cancelBoomerangRender()`
- `VideoProcessor.kt` — ensure `transformer.cancel()` in finally (already present — verify)
- `OpenLoopNavHostTest.kt` / new test — Cancel visible on Processing screen

### Regression guards

- [ ] Successful render unchanged — no cancel side effects
- [ ] Cancel mid-render does not register partial boomerang in gallery
- [ ] Predictive back still does not finish Activity during render (unless Cancel confirmed)

## Phase 4 — P2-2: Content rating & target audience (owner-operated)

Agent **does not** log into Play Console. Deliver:

1. Open `docs/play-store/content-rating.md` — verify answers still match app (no violence, no UGC network, no ads, camera-only after P1).
2. Produce a **Console walkthrough comment** for Issue #39 with step-by-step: Policy → App content → Content ratings → Start questionnaire → paste answers from doc.
3. Confirm **Target audience 13+** in store listing aligns with questionnaire.

Web-search current IARC question wording if questionnaire changed since doc was written — update `content-rating.md` if drift found.

## Phase 5 — Tests + DoD

```powershell
.\gradlew.bat testDebugUnitTest connectedDebugAndroidTest :app:lintDebug --console=plain
```

Manual on Pixel Fold 10:
- [ ] Start Loopifying on a long import → tap Cancel → confirm → back in editor, selections intact
- [ ] Complete normal save — unaffected

Screenshot: Processing screen with Cancel visible.

## Phase 6 — PR

- Title: `P2: Loopifying cancel + content rating doc sync`
- Link Issue #39
- Separate commits: (1) cancel UX, (2) content-rating doc updates if any

## Behavioral Rules

- Cancel is **explicit button**, not re-enabling system Back mid-render (Lesson 015).
- Keep diff small — no full render architecture rewrite (that's #40).
- Update Play docs if behavior changes.

## When to Stop and Ask Steven

- Cancel leaves orphan raw files user cannot understand — needs copy/messaging decision.
- Content rating questionnaire answers conflict with open-source / camera-only model.
- Cancel implementation conflicts with in-flight #40 branch — coordinate merge order.
