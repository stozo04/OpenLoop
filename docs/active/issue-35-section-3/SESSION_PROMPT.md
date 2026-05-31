# Session prompt — Issue #35 · Section 3: UX safety & feel (remaining)

Copy everything below the `---` line into a **new Cursor session** to finish GitHub Issue #35, section 3.

---

## Your task

Implement the **remaining** items in **Issue #35 · Section 3 — UX safety & feel** for the OpenLoop Android app.

**GitHub:** https://github.com/stozo04/OpenLoop/issues/35

This is **polish**, not a redesign. Add tactile feedback to primary capture/save/CTA controls and modernize the gallery empty state. Preserve behavior, accessibility, existing test tags, and everything already shipped in sections 1–2 and the gallery-delete work.

---

## Required reading (do this first)

1. Root `CLAUDE.md`
2. **Every file** in `docs/lessons_learned/` (especially 014 exhaustive `when`, 016 deferred reads, 017 androidTest/no mockk, 015 predictive back)
3. `docs/PRD-mission-control.md` (UI layer + Gallery spec)
4. `docs/ANDROID_STANDARDS.md` (Compose, a11y §7, touch targets, haptics)
5. `docs/DEFINITION_OF_DONE.md` (build + lint + tests + on-device screenshot before "done")
6. `docs/STATIC_ANALYSIS.md`

**Do not trust training data for Android APIs.** Web-search `developer.android.com` before claiming how Compose indication, `HapticFeedbackType`, or `Snackbar` APIs work.

---

## Context — what's already done

### Section 1 ✅ (commit `f3e5269`)

`ui/theme/` tokens, typography/shape adoption, `OpenLoopBackground`.

### Section 2 ✅ (commit `096a6d1`)

| Asset | Location |
|-------|----------|
| Glass back | `ui/components/CircleIconButton.kt`, `BackButton.kt` |
| Primary CTA | `ui/components/PrimaryButton.kt` (56 dp, `extraLarge` pill, `indication = null` today) |
| Direction glyphs | `ui/components/DirectionChipIcon.kt` |

### Section 3 — already shipped on `feature/gallery-selection-delete` ✅

**Do not re-implement; verify and leave intact:**

| Checkbox | What shipped | Where to look |
|----------|--------------|---------------|
| Delete confirm / undo | Deferred delete + Undo snackbar; no per-tile trash | `OpenLoopViewModel.requestDeleteVideos` / `undoPendingDeletion` / `commitPendingDeletion`; `MainActivity` `BoomerangEvent.LoopsDeleted`; `GalleryScreen` → `GalleryContent` + `visibleVideos` |
| Camera "30s" badge | Misleading idle badge removed or restyled (no fake button beside lens toggle) | `CameraScreen.kt` — confirm no non-interactive control mimics the 54 dp flip button |

Reference doc: `docs/active/gallery-selection-delete/IMPLEMENTATION.md`.

### Follow-up (out of scope here)

- Camera preview teardown log noise → [#36](https://github.com/stozo04/OpenLoop/issues/36)

---

## Section 3 — your deliverables (two checkboxes)

### 1. Add tactile feedback to primary actions

**Goal:** Primary CTAs and the main capture/save affordances should feel responsive: visible press feedback + light haptics where appropriate. Section 2 intentionally kept `indication = null` on `PrimaryButton` — this task adds polish.

**In scope — audit and update:**

| Control | File | Current press feedback | Haptics today |
|---------|------|------------------------|---------------|
| `PrimaryButton` | `ui/components/PrimaryButton.kt` | `indication = null` | None |
| Onboarding CTA | `OnboardingScreen.kt` → `GetStartedButton` | via `PrimaryButton` | None |
| Trim NEXT | `TrimScreen.kt` | via `PrimaryButton` | None |
| Gallery close preview | `GalleryScreen.kt` → `LoopingVideoOverlay` | via `PrimaryButton` | None |
| Shutter (record/stop) | `CameraScreen.kt` → `ShutterButton` | `indication = null` | None |
| Editor save | `BoomerangEditorScreen.kt` → `SaveCheckmark` | `indication = null` | None |

**Existing pattern to reuse (do not reinvent):**

- **Press scale:** `VideoThumbnailCard` in `GalleryScreen.kt` — `MutableInteractionSource` + `collectIsPressedAsState` + `animateFloatAsState` (0.93f when pressed, 0.92f when selected).
- **Haptics:** `BoomerangEditorScreen.kt` — `LocalHapticFeedback` + `HapticFeedbackType.SegmentTick` on speed detents and look-chip changes. Web-search which `HapticFeedbackType` fits confirm/tap on primary actions (e.g. `ContextClick` / `Confirm` — verify on current API).

**Implementation guidance:**

- Prefer **one shared approach** in `PrimaryButton` (optional `enablePressFeedback: Boolean = true`) so all three CTAs stay consistent.
- Press scale on `PrimaryButton` should respect `enabled = false` (Trim NEXT when window &lt; `MIN_TRIM_MS` — no spurious haptic/scale).
- For **ShutterButton** and **SaveCheckmark**: add scale and/or ripple consistent with the design system; fire haptic on successful **start** record and on **save** tap when `enabled`.
- **Do not** add haptics to disabled controls or to every scroll/drag (slider/look chips already have their own detents).
- Keep **REC-1** on `CameraScreen`: shutter progress still uses `progressFraction: () -> Float` in draw scope; don't read `recordingElapsedMs` at screen root.
- Preserve all **`testTag`s**: `onboarding_cta`, `next_button`, `editor_save`, etc.

**Explicitly OUT of scope:**

- Gallery thumbnail long-press scale (already done)
- Editor speed slider / look-chip haptics (already done)
- `MainActivity` permission / `ImportTooLongDialog` Material3 `Button`s
- Replacing `HomeButton` / import lime circle styling

**Acceptance:** LET'S GO, NEXT (enabled), CLOSE PREVIEW, shutter, and save show visible press feedback; capture and save give a light haptic on successful tap.

---

### 2. Modernize the gallery empty state

**Goal:** Replace the text-only empty state with an icon + a filled primary **"Record a loop"** CTA. Keep import as a secondary path.

**Current implementation:**

```347:381:app/src/main/java/io/github/stozo04/openloop/ui/GalleryScreen.kt
/** Empty state: no loops yet, with an import affordance (slice 07). */
@Composable
private fun EmptyGalleryState(onImportVideo: () -> Unit) {
    // "NO LOOPS YET" headline + body + lime text link "…or import one"
}
```

**Target UX (per epic):**

- **Icon:** Material vector (e.g. `Icons.Outlined.Videocam`, `Icons.Outlined.Movie`, or `Icons.Outlined.VideoLibrary`) — no emoji.
- **Primary CTA:** `PrimaryButton` — label e.g. `"RECORD A LOOP"` (match existing ALL-CAPS CTA voice: LET'S GO / NEXT).
- **Action:** Navigate to camera — same as leaving gallery: `viewModel.navigateBackFromGallery()` wired from `MainActivity` as `onBackClick` today. Hoist a dedicated `onRecordLoop: () -> Unit` on `EmptyGalleryState` / `GalleryContent` if clearer than overloading `onBackClick`.
- **Secondary:** Keep import affordance (`R.string.gallery_import_empty_state` — "…or import one") below the primary button; can remain a text link or a subdued secondary control — **do not remove import**.
- **Typography:** `MaterialTheme.typography.*` only; use theme tokens for colors.

**Implementation guidance:**

- Hoist `EmptyGalleryState` for testability (project pattern: `GalleryContent`, `GetStartedButton`) or add `testTag("gallery_empty_record")` on the primary CTA.
- Update `GalleryScreenTest` (and any snapshot of empty copy) when strings/layout change.
- Empty state only shows when `videos.isEmpty()` in `GalleryContent` — no ViewModel change required unless you add analytics later.

**Acceptance:** With zero loops, user sees icon + lime primary "Record a loop" → camera; import still available.

---

## Constraints — do NOT regress

- **Delete undo flow:** optimistic hide, Undo snackbar, `visibleVideos`, selection mode, `gallery_*` test tags.
- **Accessibility:** ≥ 48 dp touch targets; real `contentDescription`s; trim handle + speed slider semantics unchanged.
- **Compose perf:** Lesson 016 — no high-frequency reads at screen roots.
- **Copy:** "Loopifying…" unchanged.
- **State machine:** no new `OpenLoopUiState` values unless strictly required; keep `OpenLoopNavHost` `when` exhaustive (Lesson 014).
- **Shared components:** extend `PrimaryButton` rather than duplicating lime pills.
- **Scope discipline:** section 3 only — no section 1/2 rework, no #36 camera unbind unless owner expands scope.

---

## Suggested implementation order

1. **Extend `PrimaryButton`** — press scale (+ optional haptic callback or internal haptic on click when enabled).
2. **Wire haptics/scale** on `ShutterButton` + `SaveCheckmark`.
3. **Refactor `EmptyGalleryState`** — icon + `PrimaryButton` + `onRecordLoop`; thread lambda through `GalleryScreen` / `MainActivity`.
4. **Tests** — `GalleryScreenTest` empty state; spot-check `OnboardingScreenTest` / `TrimScreenTest` still green.
5. Run verification gate (below).
6. Update Issue #35: check off section 3 remaining boxes, comment with commit hash; close epic if all acceptance criteria met.

---

## Verification gate (`docs/DEFINITION_OF_DONE.md`)

Before calling done or opening a PR:

```bash
./gradlew :app:assembleDebug :app:assembleRelease
./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest   # if emulator/device available
./gradlew :app:lintDebug
```

- Exit code 0, zero `e:` compile errors
- `:app:lintDebug` — no **new** errors (baseline policy)
- Run app on device/emulator; capture screenshot showing:
  - Gallery **empty** state (icon + RECORD CTA + import link)
  - At least one **primary CTA press** state if feasible (or describe manual QA)
  - Camera shutter tap (optional haptic — note device)
- Manual QA checklist in PR description

---

## Files you'll touch (starting list)

```
app/src/main/java/io/github/stozo04/openloop/ui/
├── components/PrimaryButton.kt     ← press scale + haptic hook
├── OnboardingScreen.kt             ← (via PrimaryButton)
├── TrimScreen.kt                   ← (via PrimaryButton)
├── GalleryScreen.kt                ← LoopingVideoOverlay + EmptyGalleryState (+ hoist?)
├── CameraScreen.kt                 ← ShutterButton
├── BoomerangEditorScreen.kt        ← SaveCheckmark
└── MainActivity.kt                 ← wire onRecordLoop for empty state (if new lambda)

app/src/main/res/values/strings.xml ← optional new copy for "Record a loop"
app/src/androidTest/.../GalleryScreenTest.kt
app/src/androidTest/.../OnboardingScreenTest.kt   ← if CTA behavior changes
```

---

## Branch / git

- Branch: **`feature/gallery-selection-delete`** (or `main` if owner merged gallery work — confirm with `git branch`)
- Prior commits: `f3e5269` (§1), `096a6d1` (§2)
- **Do not commit** unless explicitly asked; owner may want one PR for the full epic branch.

---

## Pushback / decisions to surface before coding

Ask the owner rather than guessing:

1. **Press feedback style:** scale-only vs Material ripple vs both (epic mentions both; gallery cards use scale only today).
2. **Empty-state icon:** `Videocam` vs `Movie` vs `VideoLibrary`.
3. **"Record a loop" copy:** exact string and whether import stays a text link or becomes a second button.
4. **Haptic intensity:** same `SegmentTick` as editor chips vs `ContextClick` / `Confirm` for primary taps.
5. Whether section 3 should **close Issue #35** entirely after this commit or leave the epic open for #36.

---

## Success criteria (section 3 complete → epic done)

- [ ] Tactile feedback on primary actions (PrimaryButton + shutter + save)
- [ ] Gallery empty state modernized (icon + primary Record CTA + import retained)
- [ ] Section 3 checkboxes `[x]` on #35; acceptance criteria for delete + a11y + perf still true
- [ ] All existing instrumented tests green; no new lint errors
- [ ] Issue #35 comment with commit hash (and close epic if owner agrees)

---

## Reference — reuse these tokens/components

```kotlin
// Primary CTA (extend, don't duplicate)
PrimaryButton(text, onClick, enabled, testTag, trailingIcon)
ElectricLime, LimeInk, MaterialTheme.shapes.extraLarge

// Press-scale reference
VideoThumbnailCard → animateFloatAsState + collectIsPressedAsState

// Haptics reference
LocalHapticFeedback.current.performHapticFeedback(...)

// Gallery → camera
viewModel.navigateBackFromGallery()  // MainActivity onBackClick today
```

Good luck — this should be a focused polish pass that finishes the #35 epic.
