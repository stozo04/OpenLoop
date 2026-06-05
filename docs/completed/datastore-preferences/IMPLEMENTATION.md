# Jetpack DataStore — Onboarding Persistence

**Branch:** `feature/datastore-preferences`
**PR:** #5
**Status:** In review

---

## Problem

The app shows the 3-page onboarding carousel on every launch. Returning users are forced through it repeatedly. Decision Log #11 in `docs/PRD-mission-control.md` intentionally deferred persistence during early development — we are now ready to persist it.

## Solution

Implement Jetpack DataStore (Preferences) to persist a `has_completed_onboarding` boolean. On launch, the ViewModel reads DataStore before deciding the first screen. Returning users skip straight to the camera.

## Scope

**In scope:**
- `has_completed_onboarding` flag persisted via Preferences DataStore
- Repository pattern (`UserPreferencesRepository` interface + impl) for testability
- `Initializing` state added to sealed interface for async DataStore read at startup
- ViewModel constructor injection via `ViewModelProvider.Factory`
- `LaunchedEffect` in MainActivity auto-triggers permission check
- Unit tests with `FakeUserPreferencesRepository`

**Out of scope:**
- Settings UI screen
- Other preferences (capture duration, speed, audio toggle) — infrastructure supports them but no values defined yet
- Migration from SharedPreferences (none exists)

## Architecture

### Data Layer

```
Context.dataStore (top-level singleton delegate)
    ↓
UserPreferencesRepositoryImpl (wraps DataStore, catches IOException)
    ↓ implements
UserPreferencesRepository (interface — ViewModel depends on this)
    ↓ injected via Factory
OpenLoopViewModel (reads in init, writes on onboarding completion)
```

### Startup Flow

**First-time user:**
`Initializing` (spinner) → DataStore returns `false` → `Onboarding` → 3 pages → `CheckingPermissions` → permissions → `ReadyToCapture`

**Returning user:**
`Initializing` (spinner) → DataStore returns `true` → `CheckingPermissions` → permissions → `ReadyToCapture`

## Files Changed

**New (3):**
- `app/src/main/java/com/OpenLoop/app/data/UserPreferencesRepository.kt`
- `app/src/main/java/com/OpenLoop/app/data/UserPreferencesRepositoryImpl.kt`
- `docs/ANDROID_STANDARDS.md`

**Modified (9):**
- `gradle/libs.versions.toml` — DataStore dependency
- `app/build.gradle.kts` — DataStore dependency
- `OpenLoopUiState.kt` — `Initializing` state
- `OpenLoopViewModel.kt` — constructor injection, init block, Factory
- `MainActivity.kt` — factory creation, LaunchedEffect, Initializing handling
- `OpenLoopViewModelTest.kt` — FakeRepo, 3 new tests (19 total)
- `CLAUDE.md` — architecture updates
- `docs/PRD-mission-control.md` — Decision Log #11, architecture diagram
- `README.md` — rewrite + merge policy

## Testing

| Test | Validates |
|------|----------|
| `first-time user resolves to Onboarding after init` | DataStore `false` → Onboarding |
| `returning user resolves to CheckingPermissions after init` | DataStore `true` → CheckingPermissions |
| `onOnboardingCompleted persists true to repository` | Write actually happens |
| All 16 existing tests | Unchanged behavior with new constructor |

## Acceptance Criteria

- [x] First-time users see onboarding, then never again after completing it
- [x] Returning users skip straight to permission check / camera
- [x] DataStore file created at `files/datastore/OpenLoop_preferences.preferences_pb`
- [x] IOException on corrupted DataStore falls back to showing onboarding (safe degradation)
- [x] All 19 unit tests pass
- [x] Future preferences can be added with zero restructuring

## Google Standards Compliance

Follows all rules in `docs/ANDROID_STANDARDS.md` Section 4 (DataStore):
- Top-level `Context.dataStore` singleton delegate
- Repository interface between ViewModel and DataStore
- `IOException` caught with `emptyPreferences()` fallback
- Atomic writes via `dataStore.edit { }`
- No SharedPreferences, no `runBlocking`
