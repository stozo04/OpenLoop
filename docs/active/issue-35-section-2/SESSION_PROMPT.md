# Session prompt — Issue #35 · Section 2: Component consistency

Copy everything below the `---` line into a **new Cursor session** to implement GitHub Issue #35, section 2.

---

## Your task

Implement **Issue #35 · Section 2 — Component consistency (mostly extractions, not redesigns)** for the OpenLoop Android app.

**GitHub:** https://github.com/stozo04/OpenLoop/issues/35

This is **not** a redesign. Extract shared components, wire every call site to them, and replace emoji/text glyphs with Material vector icons. Preserve behavior, accessibility, and existing test tags.

---

## Required reading (do this first)

1. Root `CLAUDE.md`
2. **Every file** in `docs/lessons_learned/` (especially 014 exhaustive `when`, 016 deferred reads, 017 androidTest/no mockk)
3. `docs/PRD-mission-control.md` (UI layer)
4. `docs/ANDROID_STANDARDS.md` (Compose, a11y §7, touch targets)
5. `docs/DEFINITION_OF_DONE.md` (build + lint + tests + on-device screenshot before "done")
6. `docs/STATIC_ANALYSIS.md`

**Do not trust training data for Android APIs.** Web-search `developer.android.com` before claiming how Compose/Material3 APIs work.

---

## Context — what's already done

### Section 1 ✅ (commit `f3e5269`, branch `feature/gallery-selection-delete`)

Design-system foundations are complete:

| Asset | Location |
|-------|----------|
| Color tokens | `app/src/main/java/io/github/stozo04/openloop/ui/theme/Color.kt` |
| Typography (Space Grotesk / Inter / JetBrains Mono) | `ui/theme/Type.kt` → `OpenLoopTypography`, `TimerTextStyle` |
| Shape scale (8 / 12 / 16 / 24 / 32 dp) | `ui/theme/Shape.kt` → `OpenLoopShapes` |
| Theme | `ui/theme/Theme.kt` → `OpenLoopTheme`, `shutterGradient()` |
| Shared background | `ui/theme/Background.kt` → `OpenLoopBackground` |

All post-onboarding screens now use `MaterialTheme.typography.*` and `MaterialTheme.shapes.*`. Trim handle readouts were revamped to `HandleValueBubble` (START/END tooltips) — **do not regress this**.

### Section 3 partial ✅ (same branch, separate work)

Gallery delete undo/snackbar and camera "30s" badge fix are already shipped on this branch. **Out of scope for section 2.**

---

## Section 2 — three checkboxes (your deliverables)

### 1. One shared back button

**Goal:** Promote the editor's `CircleIconButton` pattern to a **shared, public** composable. One size, one icon (`Icons.AutoMirrored.Filled.ArrowBack`) everywhere a "back" affordance appears.

**Current call sites (audit before coding):**

| Screen | File | Current impl | Size | Icon | `contentDescription` |
|--------|------|--------------|------|------|----------------------|
| Trim | `TrimScreen.kt` | Inline `Box` + `clickable` | 48 dp (`HANDLE_SIZE`) | ArrowBack | `"Discard clip"` |
| Editor | `BoomerangEditorScreen.kt` | `private CircleIconButton` | 56 dp (`CONTROL_SIZE`) | ArrowBack | `"Back to trim"` |
| Gallery | `GalleryScreen.kt` → `GalleryTopBar` | Inline `Box` | 56 dp | ArrowBack | `"Back to camera"` |

**Not in scope:** `CameraScreen.kt` → `HomeButton` (lime circle, **gallery** icon — navigation to gallery, not "back"). Leave it alone.

**Implementation guidance:**

- New file suggestion: `app/src/main/java/io/github/stozo04/openloop/ui/components/BackButton.kt` (or `ui/components/CircleIconButton.kt` if you want a generic glass circle + a thin `BackButton` wrapper).
- Pick **one** touch target — **56 dp** is the majority (editor + gallery) and exceeds the 48 dp minimum. Trim's 48 dp back should adopt the shared size.
- Glass styling is already unified: `OverlayWhite` fill + `OverlayWhiteBorder` border + white icon tint. Keep that.
- Preserve per-screen **`contentDescription`** strings (they describe different actions: discard vs back to trim vs back to camera).
- Preserve **`testTag`s**: `trim_back`, `editor_back`. Add one to gallery if missing.
- Use `Role.Button` + `clickable` (editor/gallery already do; trim should match).
- Hoist for testability (project pattern: `ShutterButton`, `GetStartedButton`, `TrimScreenContent`).

**Acceptance:** A user sees the same back control on Trim, Editor, and Gallery (size, shape, glass, arrow icon).

---

### 2. One shared `PrimaryButton`

**Goal:** Single flat-lime CTA: one fill (`ElectricLime`), one radius, one height, one label style. Reuse for the three text CTAs listed in the issue.

**Current call sites:**

| Screen | File | Composable / block | Height | Shape | Label | Notes |
|--------|------|-------------------|--------|-------|-------|-------|
| Onboarding | `OnboardingScreen.kt` | `GetStartedButton` | 64 dp | `shapes.extraLarge` (32) | `"LET'S GO!"` | Has `testTag("onboarding_cta")`, tested in `OnboardingScreenTest` |
| Trim | `TrimScreen.kt` | Inline NEXT `Box` | 56 dp | `shapes.medium` (16) | `"NEXT"` | Disabled state: grey glass + dim text. `testTag("next_button")`, `Role.Button`, `indication = null` |
| Gallery | `GalleryScreen.kt` | `LoopingVideoOverlay` close pill | ~auto | `shapes.extraLarge` (32) | `"CLOSE PREVIEW ✕"` | Full-width-ish pill at bottom of dialog |

**Explicitly OUT of scope for `PrimaryButton`:**

- Editor **Save** checkmark (circle icon button — different control class)
- Gallery **Import** lime circle (icon-only primary)
- `MainActivity` permission / `ImportTooLongDialog` Material3 `Button`s (different surface; not listed in issue)
- Gallery empty-state import text link (section 3 empty-state modernization)

**Implementation guidance:**

- New file: `app/src/main/java/io/github/stozo04/openloop/ui/components/PrimaryButton.kt`
- **Decide and document one canonical spec** (suggested starting point — validate visually with owner):
  - Height: **56 dp** (matches Trim NEXT; onboarding drops from 64 → 56 for consistency)
  - Shape: `MaterialTheme.shapes.extraLarge` (32 dp pill) OR `medium` (16) — pick one; issue wants them identical
  - Label: `MaterialTheme.typography.labelLarge` + `LimeInk` on enabled; disabled variant needed for Trim NEXT
  - Width: `fillMaxWidth()` with optional `widthIn(max = 520.dp)` where screens already cap (Trim)
- Support **`enabled`**, **`onClick`**, **`modifier`**, **`testTag`**, optional **`text`**
- Trim NEXT disabled semantics must stay: non-clickable when `(endMs - startMs) < MIN_TRIM_MS`
- **`GetStartedButton`**: either becomes a thin wrapper over `PrimaryButton` or is deleted and call sites use `PrimaryButton` directly — keep `testTag("onboarding_cta")` either way
- **Do not add ripple/haptics yet** — that's section 3 ("tactile feedback"). Keep `indication = null` if that's what call sites have today, unless you need Material default for a11y (discuss in PR if changing)

**Acceptance:** LET'S GO / NEXT / CLOSE PREVIEW look like the same button family (fill, radius, height, type).

---

### 3. Replace emoji/text glyphs with vector icons

**Goal:** No emoji as iconography. Uniform Material vector language.

| Location | File | Current | Target |
|----------|------|---------|--------|
| Gallery thumbnail fallback | `GalleryScreen.kt` → `VideoThumbnailCard` | `"🎬"` Text 28 sp | `Icons.Outlined.Movie` (or `VideoLibrary`) |
| Gallery close preview | `GalleryScreen.kt` → `LoopingVideoOverlay` | `"CLOSE PREVIEW ✕"` | Label `"CLOSE PREVIEW"` + trailing `Icons.Filled.Close` **or** icon-only with `contentDescription` — use `PrimaryButton` from task 2 |
| Direction chips | `BoomerangEditorScreen.kt` → `DirectionChipButton` | Unicode `"▶▶" "◀◀" "▶◀" "◀▶"` | Drawn `ImageVector`s or composed Material icons so they match the editor's icon language |

**Also clean up:**

- Delete unused `app/src/main/res/drawable/ic_film_slate.xml` if still present (lint: `UnusedResources`). Gallery already uses `ArrowBack`; the slate drawable is dead.

**Direction chip guidance (hardest item):**

- Issue says "consider drawn glyphs" — acceptable approaches:
  1. Custom `ImageVector` builder (two chevrons / forward-reverse pairs) in `ui/components/` or `res/drawable/`
  2. Row of two small `Icon`s per chip (e.g. double `PlayArrow`)
- Preserve **`accessibilityLabel`** on each chip (`"Forward"`, `"Reverse"`, etc.) — TalkBack must not regress
- Preserve **`testTag("direction_chip_${chip.mode.name}")`**
- Selected/unselected colors stay: lime fill + `LimeInk` glyph vs glass + white glyph

**Acceptance (epic-level):** No emoji used as iconography on the listed screens.

---

## Constraints — do NOT regress

- **Accessibility:** ≥ 48 dp touch targets, real `contentDescription`s, trim handle semantics, speed slider semantics
- **Compose perf:** deferred high-frequency reads (Lesson 016) — don't read `recordingElapsedMs` / `renderProgress` at screen root
- **Copy:** "Loopifying…" unchanged between editor shimmer and processing screen
- **State machine:** no changes to `OpenLoopUiState` routing unless strictly required
- **Tests:** existing androidTest tags must keep working (`onboarding_cta`, `next_button`, `trim_back`, `editor_back`, `direction_chip_*`, `gallery_*`)
- **Scope discipline:** extractions + icon swap only — no section 3 work (haptics, empty state, delete flow)

---

## Suggested implementation order

1. Create `ui/components/` package + shared **`BackButton`** (or promoted **`CircleIconButton`**) → migrate Trim, Editor, Gallery
2. Create **`PrimaryButton`** → migrate `GetStartedButton`, Trim NEXT, Gallery close preview (task 3 close label can land here)
3. **Vector icons** — gallery fallback + direction chips (+ remove `ic_film_slate` if unused)
4. Run verification gate (below)
5. Update Issue #35: check off section 2 boxes, add progress comment with commit hash

---

## Verification gate (`docs/DEFINITION_OF_DONE.md`)

Before calling done or opening a PR:

```bash
./gradlew :app:assembleDebug :app:assembleRelease
./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest   # if emulator/device available
./gradlew :app:lintDebug
```

- Exit code 0, zero `e:` compile errors
- `:app:lintDebug` — no **new** errors (baseline policy in `lint-baseline.xml`)
- Run app on device/emulator; capture screenshot showing at least: Trim (back + NEXT), Editor (back + direction chips), Gallery (back + thumbnail grid)
- Manual QA checklist in PR description

---

## Files you'll touch (starting list)

```
app/src/main/java/io/github/stozo04/openloop/ui/
├── components/                    ← NEW (BackButton, PrimaryButton, maybe DirectionChipIcons)
├── OnboardingScreen.kt            ← GetStartedButton → PrimaryButton
├── TrimScreen.kt                  ← back + NEXT
├── BoomerangEditorScreen.kt       ← extract CircleIconButton; direction chip glyphs
├── GalleryScreen.kt               ← back, 🎬 fallback, CLOSE PREVIEW
└── CameraScreen.kt                ← read only (HomeButton stays)

app/src/androidTest/.../
├── OnboardingScreenTest.kt
├── TrimScreenTest.kt
└── (add/update component tests if hoisted composables warrant it)

app/src/main/res/drawable/
└── ic_film_slate.xml              ← delete if unused
```

---

## Branch / git

- Current branch: **`feature/gallery-selection-delete`**
- Latest relevant commit: **`f3e5269`** (section 1)
- Commit section 2 on the same branch unless owner directs otherwise
- **Do not commit** unless explicitly asked; owner may want one PR for gallery work + section 2 or a split — ask if unclear

---

## Pushback / decisions to surface before coding

If anything below is ambiguous, **ask the owner** rather than guessing:

1. **PrimaryButton height:** 56 dp (Trim) vs 64 dp (onboarding today) — issue implies normalize; confirm visual preference
2. **PrimaryButton radius:** `extraLarge` (32 pill) vs `medium` (16) — pick one and apply everywhere
3. **Direction chips:** custom vectors vs double Material icons — show options if unsure
4. **CLOSE PREVIEW:** text + icon vs icon-only with a11y label
5. Whether section 2 should be **its own PR** off `main` or stacked on `feature/gallery-selection-delete`

---

## Success criteria (section 2 complete)

- [ ] Shared back button — same size, glass, `ArrowBack` on Trim / Editor / Gallery
- [ ] Shared `PrimaryButton` — LET'S GO / NEXT / CLOSE PREVIEW unified
- [ ] No emoji iconography (gallery fallback, close preview, direction chips use vectors)
- [ ] All existing instrumented tests green; no new lint errors
- [ ] Issue #35 section 2 checkboxes checked + comment posted

---

## Reference — theme tokens (use these, no inline brand hex)

```kotlin
// Primary CTA
ElectricLime, LimeInk
MaterialTheme.typography.labelLarge
MaterialTheme.shapes.extraLarge  // or .medium — pick one for PrimaryButton

// Glass back button
OverlayWhite, OverlayWhiteBorder, Color.White (icon tint)
CircleShape

// Disabled primary (Trim NEXT)
OverlayWhite fill, Color.White.copy(alpha = 0.4f) label
```

Good luck — this should be a focused extraction pass, not a rewrite.
