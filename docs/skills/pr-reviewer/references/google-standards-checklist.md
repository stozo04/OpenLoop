# Google Android Standards — Review Checklist

This is the full checklist the PR reviewer evaluates against. Each item maps to an official
Google documentation source. The reviewer must web-search for the latest version of each
source before reviewing — this file provides the structure, not the final word on current
standards.

---

## 1. Architecture

**Source:** https://developer.android.com/topic/architecture

- [ ] Unidirectional data flow: state flows down (ViewModel → UI), events flow up (UI → ViewModel)
- [ ] Single source of truth for each piece of data
- [ ] ViewModel exposes `StateFlow<T>`, never mutable types publicly
- [ ] No `Activity`, `Context`, or `View` references held in ViewModel
- [ ] Dependencies enter via constructor injection + `ViewModelProvider.Factory`
- [ ] Repository interfaces between ViewModel and data sources
- [ ] Separation of concerns across layers (UI / Domain / Data / Platform)
- [ ] Sealed interface/class for UI state with exhaustive `when` matching

**Source:** https://developer.android.com/topic/architecture/recommendations

---

## 2. Jetpack DataStore

**Source:** https://developer.android.com/topic/libraries/architecture/datastore

- [ ] Exactly one `DataStore<Preferences>` instance per file (top-level `Context.dataStore` delegate)
- [ ] Repository interface wrapping DataStore — ViewModel never accesses DataStore directly
- [ ] `IOException` caught on `.data` reads with `.catch { emit(emptyPreferences()) }` fallback
- [ ] Writes use `dataStore.edit { }` (atomic read-modify-write transaction)
- [ ] No `SharedPreferences` anywhere in the codebase
- [ ] No `runBlocking` reads on the main thread
- [ ] Preferences DataStore for simple key-value; Proto DataStore only if typed schemas needed

---

## 3. Runtime Permissions

**Source:** https://developer.android.com/training/permissions/requesting
**Source:** https://developer.android.com/training/permissions/usage-notes

- [ ] `ContextCompat.checkSelfPermission()` called before every permission-gated action
- [ ] `shouldShowRequestPermissionRationale()` used to decide when to show educational UI
- [ ] App degrades gracefully when permission denied (no crash, no empty screen, no blocked flow)
- [ ] No repeated permission prompts after user taps "Don't ask again"
- [ ] Permission request happens in-context (near the feature that needs it)
- [ ] Rationale UI explains WHY the permission is needed, not just WHAT it is
- [ ] User choice is respected — no aggressive nagging to reconsider

---

## 4. Jetpack Compose

**Source:** https://developer.android.com/develop/ui/compose/performance/bestpractices

- [ ] `remember` used to cache expensive calculations in composables
- [ ] Stable keys provided to `LazyColumn`/`LazyVerticalGrid` items via `key` parameter
- [ ] `derivedStateOf` used for computed values from rapidly-changing state
- [ ] Lambda-based modifiers (e.g., `Modifier.offset { }`) for frequently-changing state
- [ ] No state writes after state reads in the same composable (infinite recomposition risk)
- [ ] Material 3 components used where applicable (built-in accessibility semantics)
- [ ] `collectAsStateWithLifecycle()` preferred for collecting Flows in composables

**Source:** https://developer.android.com/develop/ui/compose/performance/stability

---

## 5. CameraX

**Source:** https://developer.android.com/media/camera/camerax

- [ ] Lifecycle-bound via `cameraProvider.bindToLifecycle()`, not manual start/stop
- [ ] `PreviewView` used for camera preview (handles rotation, aspect ratio, scale)
- [ ] No duplicate use cases created (one Preview, one VideoCapture, etc.)
- [ ] Executor properly shut down in `onDestroy()` or equivalent lifecycle callback
- [ ] Configuration via `CameraXConfig` if custom settings needed

**Source:** https://developer.android.com/media/camera/camerax/architecture

---

## 6. Kotlin Coroutines & Flow

**Source:** https://developer.android.com/kotlin/coroutines/coroutines-best-practices

- [ ] `viewModelScope` used for all ViewModel coroutines (auto-cancels on teardown)
- [ ] `suspend` functions for one-shot operations, `Flow<T>` for observable data
- [ ] `flowOn(Dispatchers.IO)` in data layer for background work, not in ViewModel/UI
- [ ] Specific exception types caught (`IOException`, not bare `Exception` or `Throwable`)
- [ ] No raw `CoroutineScope()` instances in ViewModels or Activities
- [ ] `lifecycleScope` used in Activities/Fragments (if applicable)

**Source:** https://developer.android.com/kotlin/flow

---

## 7. Testing

**Source:** https://developer.android.com/training/testing/fundamentals

- [ ] Unit tests exist for every file with business logic (ViewModel, Repository, processors)
- [ ] Fakes preferred over mocks for Flow-based interfaces (more readable, more maintainable)
- [ ] `TestDispatcher` (Unconfined or Standard) replaces `Dispatchers.Main` via test rule
- [ ] UI regression tests for layout-critical composables (Compose Testing APIs)
- [ ] Test names are descriptive (backtick-delimited in Kotlin)
- [ ] No unit tests for Activities, Compose layouts directly, or DI configuration
- [ ] Tests cover error paths and edge cases, not just happy paths

**Source:** https://developer.android.com/training/testing/fundamentals/strategies
**Source:** https://developer.android.com/develop/ui/compose/testing

---

## 8. Accessibility

**Source:** https://developer.android.com/guide/topics/ui/accessibility

- [ ] Touch targets >= 48dp x 48dp on all interactive elements
- [ ] Color contrast >= 4.5:1 for text < 18sp (or bold < 14sp)
- [ ] Color contrast >= 3:1 for large text and non-text elements (icons, borders)
- [ ] `contentDescription` set on meaningful `Image` and `Icon` composables
- [ ] Decorative elements have `contentDescription = null` (skipped by screen readers)
- [ ] Semantic roles set on custom interactive elements (`Role.Button`, `Role.Switch`, etc.)
- [ ] Information not conveyed by color alone (shapes, text, patterns, haptics as backup)
- [ ] TalkBack navigation tested manually before shipping

**Source:** https://developer.android.com/guide/topics/ui/accessibility/principles

---

## 9. Google Play Store Readiness

**Source:** https://developer.android.com/google/play/requirements/target-sdk

- [ ] `targetSdk` meets current Play Store minimum (web-search for the latest deadline)
- [ ] `compileSdk` >= `targetSdk`
- [ ] 64-bit native libraries included if any native code is present
- [ ] No unnecessary permissions declared in AndroidManifest.xml
- [ ] User data policies met (no undisclosed data collection or transmission)
- [ ] App handles exceptions gracefully (no unhandled crashes — affects vitals score)
- [ ] Main thread never blocked (ANR risk — affects vitals score)

**Source:** https://developer.android.com/docs/quality-guidelines/core-app-quality
**Source:** https://developer.android.com/distribute/play-policies

---

## 10. Latest Android Version Behavior Changes

**Source:** https://developer.android.com/about/versions (check latest stable)

- [ ] Review behavior changes for apps targeting the latest API level
- [ ] No deprecated APIs used that are removed in the target SDK
- [ ] Permission model changes addressed (if any in the target version)
- [ ] Storage access changes addressed (if any)
- [ ] Foreground service restrictions addressed (if applicable)
- [ ] Camera/media API changes reviewed (relevant for OpenRang)

---

## How to Use This Checklist

1. **Not every item applies to every PR.** A docs-only PR doesn't need CameraX checks.
   Review what's relevant to the changed files.

2. **Web-search before checking.** The URLs above are starting points. Google updates
   their docs frequently. Search for the latest version before making a determination.

3. **Context matters.** An item might technically fail but be acceptable given a logged
   decision in `PRD-mission-control.md`. Flag the tension, don't auto-fail.

4. **Severity ranking:**
   - **FAIL** = will cause crashes, data loss, Play Store rejection, or legal risk
   - **WARNING** = works today but will break soon (deadline approaching, deprecation)
   - **RECOMMENDATION** = would improve quality but isn't required by Google
