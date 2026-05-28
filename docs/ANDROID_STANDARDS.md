# Android Development Standards — OpenRang

**Rule: If Google recommends it, we follow it. No exceptions.**

This document codifies the Android development best practices that govern all OpenRang code. Every standard here links back to official Google documentation. When in doubt, the Google source wins.

Last reviewed: 2026-05-28

---

## 1. Architecture

**Google Guide:** [Guide to App Architecture](https://developer.android.com/topic/architecture)

**Google Recommendations:** [Architecture Recommendations](https://developer.android.com/topic/architecture/recommendations)

### Mandatory Principles

**Separation of concerns.** Every class, file, and package has one clearly defined responsibility. UI code does not contain business logic. Data access does not live in ViewModels. Activities and Fragments (if used) contain only UI and OS-interaction logic.

**Unidirectional data flow (UDF).** State flows down from ViewModel to UI. Events flow up from UI to ViewModel. The UI never directly mutates state — it calls ViewModel functions that produce new state.

**Single source of truth (SSOT).** Every piece of data has exactly one owner. For user preferences, that owner is the DataStore repository. For video metadata, it's the filesystem scan in the ViewModel. No duplicated state.

**Drive UI from data models.** UI state is a data class or sealed interface observed by the UI layer. The UI is a pure function of that state — given the same state, it renders the same output.

### Layered Architecture

```
UI Layer          →  Compose screens, state collection
Domain Layer      →  (optional) Use cases if business logic grows complex
Data Layer        →  Repositories, DataStore, filesystem access
Platform Layer    →  CameraX, Media3, system APIs
```

**Google Reference:** [UI Layer](https://developer.android.com/topic/architecture/ui-layer) · [Data Layer](https://developer.android.com/topic/architecture/data-layer)

### ViewModel Rules

- One ViewModel per screen (or per feature, at current app scale)
- Expose state as `StateFlow<T>`, never as mutable types
- Use `viewModelScope` for all coroutines — it auto-cancels on ViewModel teardown
- Never hold references to Activity, Context, or View in a ViewModel (use `AndroidViewModel` only if unavoidable, prefer constructor injection)
- Dependencies enter via constructor injection + `ViewModelProvider.Factory`

**Google Reference:** [ViewModel Overview](https://developer.android.com/topic/libraries/architecture/viewmodel)

---

## 2. Jetpack Compose

**Google Guide:** [Compose Best Practices](https://developer.android.com/develop/ui/compose/performance/bestpractices)

### Performance Rules

**Use `remember` to cache expensive calculations.** Composable functions can run on every frame during animations. Never compute file I/O, sorting, or filtering inline without `remember`.

**Provide stable keys to lazy layouts.** `LazyColumn` and `LazyVerticalGrid` must use the `key` parameter tied to a stable identifier (e.g., `video.id`) to minimize unnecessary recompositions.

**Defer state reads.** Wrap rapidly-changing state in lambda modifiers (e.g., `Modifier.offset { }` instead of `Modifier.offset()`) to limit recomposition scope to the drawing phase.

**Use `derivedStateOf` for computed values.** When state A changes frequently but the UI only cares about a derived condition (e.g., "is list scrolled past threshold"), use `derivedStateOf` to avoid excess recomposition.

**Never write state that has already been read.** Writing to a state variable that was read earlier in the same composable causes an infinite recomposition loop.

### Compose-Specific Conventions

- Use `collectAsStateWithLifecycle()` (from `lifecycle-runtime-compose`) to collect Flows in composables — it's lifecycle-aware and pauses collection when the UI is stopped
- Prefer immutable state. UI state classes should be `data class` or `data object` — never mutable
- Material 3 composables (Button, Switch, Checkbox) come with built-in accessibility semantics — use them instead of raw `Box` + `clickable` when possible

**Google Reference:** [Compose Phases and Performance](https://developer.android.com/develop/ui/compose/performance/phases) · [Stability in Compose](https://developer.android.com/develop/ui/compose/performance/stability)

---

## 3. Kotlin Coroutines & Flow

**Google Guide:** [Coroutines Best Practices](https://developer.android.com/kotlin/coroutines/coroutines-best-practices)

### Rules

**Expose `suspend` functions for one-shot operations.** Repository methods that perform a single read or write should be `suspend` functions.

**Expose `Flow<T>` for observable data.** Data that changes over time (preferences, database queries, sensor readings) should be exposed as `Flow`.

**Use `flowOn` for dispatcher changes.** Never hardcode dispatcher switching inside a composable or ViewModel. Use `flowOn(Dispatchers.IO)` in the repository/data layer to move work off the main thread.

**Catch specific exceptions.** Prefer `catch { if (it is IOException) ... }` over catching `Exception` or `Throwable`. Let unexpected exceptions propagate and crash visibly rather than silently swallowing them.

**Use `viewModelScope` and `lifecycleScope`.** Never create raw `CoroutineScope()` instances in ViewModels or Activities. The provided scopes auto-cancel on teardown and prevent leaks.

**Google Reference:** [Kotlin Flows on Android](https://developer.android.com/kotlin/flow) · [Lifecycle-Aware Coroutines](https://developer.android.com/topic/libraries/architecture/coroutines)

---

## 4. Data Storage — Jetpack DataStore

**Google Guide:** [DataStore](https://developer.android.com/topic/libraries/architecture/datastore)

### Rules

**Use DataStore, not SharedPreferences.** DataStore is the modern replacement. It uses coroutines and Flow for async access, prevents main-thread blocking, and provides transactional writes. SharedPreferences is not permitted in new code.

**Exactly one DataStore instance per file.** The `preferencesDataStore` delegate must be a top-level extension property on `Context`. Creating multiple instances for the same file causes data corruption.

**Wrap DataStore behind a repository interface.** The ViewModel depends on the interface, never on the DataStore directly. This enables testing with fakes.

**Handle IOException on reads.** DataStore files can be corrupted. Always `.catch { if (it is IOException) emit(emptyPreferences()) }` with a safe fallback.

**Writes are atomic.** Use `dataStore.edit { }` which performs a read-modify-write in a single transaction. Never read-then-write separately.

**Preferences DataStore for simple key-value pairs.** Use Proto DataStore only when you need typed schemas, nested objects, or schema evolution guarantees.

**Google Reference:** [Working with Preferences DataStore (Codelab)](https://developer.android.com/codelabs/android-preferences-datastore) · [Proto DataStore (Codelab)](https://developer.android.com/codelabs/android-proto-datastore)

---

## 5. CameraX

**Google Guide:** [CameraX Overview](https://developer.android.com/media/camera/camerax)

### Rules

**Use CameraX, not Camera2.** CameraX provides device-agnostic compatibility, automatic lifecycle management, and consistent behavior across 1000+ Android devices. Camera2 is lower-level and only justified for features CameraX doesn't support.

**Bind to lifecycle, don't manage start/stop manually.** Use `cameraProvider.bindToLifecycle()` instead of placing start/stop calls in `onResume()`/`onPause()`. CameraX handles lifecycle transitions automatically.

**Use `PreviewView` for camera preview.** It handles display rotation, aspect ratio, and scale type automatically. Embed via Compose's `AndroidView`.

**Respect use case limits.** CameraX supports one instance each of Preview, VideoCapture, ImageAnalysis, and ImageCapture simultaneously. Don't create duplicate use cases.

**Google Reference:** [CameraX Architecture](https://developer.android.com/media/camera/camerax/architecture) · [Getting Started with CameraX (Codelab)](https://developer.android.com/codelabs/camerax-getting-started)

---

## 6. Testing

**Google Guide:** [Fundamentals of Testing](https://developer.android.com/training/testing/fundamentals)

### Testing Pyramid

**Unit tests (most tests here).** Fast, run on JVM, no Android framework dependency. Test ViewModels, repositories, utility classes, and business logic. Use JUnit 4, MockK for mocking, and `kotlinx-coroutines-test` for coroutine testing.

**Component/integration tests.** Verify interactions between classes (e.g., ViewModel + Repository). Still local where possible.

**UI tests (fewer, more targeted).** Verify critical user interactions on a single screen. Use Compose Testing APIs (`createComposeRule`, `onNodeWithText`, `performClick`). One test class per screen is a good starting point.

**End-to-end tests (fewest).** Full user flows across multiple screens. Most expensive to write and maintain. Reserve for critical paths.

### Rules

**Write unit tests for every file with business logic.** ViewModels, repositories, data processing classes. Aim for high coverage on state transitions.

**Do NOT unit test Activities, Compose layouts directly, or DI configuration.** These are covered by UI and integration tests.

**Use `TestDispatcher` for coroutine tests.** Replace `Dispatchers.Main` with `UnconfinedTestDispatcher` or `StandardTestDispatcher` via a test rule. This gives deterministic, non-flaky coroutine behavior.

**Use fakes over mocks when practical.** A `FakeUserPreferencesRepository` that uses `MutableStateFlow` is more readable and maintainable than a MockK setup for Flow-based interfaces.

**Catch bugs early.** A bug caught by a unit test costs minutes to fix. The same bug caught by an end-to-end test can take days and involve multiple people.

**Google Reference:** [Testing Strategies](https://developer.android.com/training/testing/fundamentals/strategies) · [What to Test](https://developer.android.com/training/testing/fundamentals/what-to-test) · [Test Compose Layouts](https://developer.android.com/develop/ui/compose/testing) · [Testing Coroutines](https://developer.android.com/kotlin/coroutines/test)

---

## 7. Accessibility

**Google Guide:** [Build Accessible Apps](https://developer.android.com/guide/topics/ui/accessibility)

### Rules

**Touch targets: 48dp minimum.** Every interactive element must have a touch target of at least 48x48dp. Compose's `Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)` enforces this.

**Color contrast: 4.5:1 for text, 3:1 for non-text.** Text smaller than 18sp (or bold text smaller than 14sp) requires a foreground-to-background contrast ratio of at least 4.5:1. Large text and non-text elements (icons, borders) require 3:1.

**Content descriptions on meaningful images.** Use `contentDescription` on `Image` and `Icon` composables. Decorative elements get `contentDescription = null` to be skipped by screen readers.

**Use semantic roles.** Compose's built-in components (Button, Switch, Checkbox) expose roles automatically. When building custom interactive elements, set `Role.Button`, `Role.Switch`, etc. via the `semantics` modifier.

**Don't rely on color alone.** Use shapes, patterns, text labels, or haptic feedback alongside color to convey information. This helps users with color vision deficiencies.

**Test with TalkBack.** Manually navigate the app using TalkBack (Android's screen reader) before shipping. Automated tools catch structure issues; manual testing catches usability issues.

**Google Reference:** [Accessibility Principles](https://developer.android.com/guide/topics/ui/accessibility/principles) · [Test Accessibility](https://developer.android.com/guide/topics/ui/accessibility/testing)

---

## 8. Google Play Store Requirements

**Google Guide:** [Core App Quality](https://developer.android.com/docs/quality-guidelines/core-app-quality)

### Current Requirements (verified 2026-05-28)

**Target API level.** New apps and app updates submitted to Google Play must currently target at least **API 35 (Android 15)** — in force since **August 31, 2025** (the extension window closed November 1, 2025). The floor is expected to rise to **API 36 (Android 16)** around **August 2026** on Google's annual cadence, but as of this review the [requirements page](https://developer.android.com/google/play/requirements/target-sdk) had **not** published an exact date for the API 36 requirement — re-verify before release rather than trusting this line. (Wear OS, Android Automotive, and Android TV trail by one level — not relevant to OpenRang.)

> **OpenRang status — satisfied ([Issue #7](https://github.com/stozo04/OpenRang/issues/7)):** the app targets **API 36** (`app/build.gradle.kts`: `compileSdk`/`targetSdk` 36, `minSdk` 26), clearing the current Play floor of API 35 — going straight to 36 to avoid a second bump when the floor rises. Native libraries are 16 KB page-aligned (CameraX/Media3 upgraded, uncompressed packaging). See the [Android 16 hub](android-16/README.md) for the behavior-change detail behind the upgrade.

**64-bit support.** All apps must include 64-bit native libraries if they include any native code.

**User data policies.** Apps must comply with Google Play's User Data policies. OpenRang is privacy-first (zero network, zero tracking), which simplifies compliance.

**App quality signals.** Google monitors user-perceived crash rate, ANR rate, and user loss rate. Keep crash rate below thresholds by handling exceptions gracefully, never blocking the main thread, and testing on a variety of devices.

**Google Reference:** [Target API Level Requirements](https://developer.android.com/google/play/requirements/target-sdk) · [Google Play Policies](https://developer.android.com/distribute/play-policies) · [App Quality](https://developer.android.com/quality)

---

## 9. Performance

**Google Guide:** [App Performance](https://developer.android.com/topic/performance)

### Rules

**Never block the main thread.** All file I/O, network calls, database queries, and heavy computation must run on background dispatchers (`Dispatchers.IO` or `Dispatchers.Default`). DataStore and CameraX handle this internally; custom code must do it explicitly.

**Use Baseline Profiles for startup optimization.** Baseline Profiles improve cold-start speed by ~30% by enabling ahead-of-time compilation for critical code paths. Consider adding them before Play Store launch.

**Profile before optimizing.** Use Android Studio's CPU Profiler and Jetpack Macro benchmark to identify actual bottlenecks. Don't guess — measure.

**Minimize allocations in hot paths.** Recording callbacks, frame processing, and animation composables should avoid object creation where possible.

**Google Reference:** [Baseline Profiles in Compose](https://developer.android.com/develop/ui/compose/performance/baseline-profiles)

---

## 10. Project Conventions (OpenRang-Specific)

These conventions apply specifically to OpenRang and are consistent with the Google standards above.

**State machine.** All UI state is modeled as a `sealed interface` with exhaustive `when` matching. No string-based or enum-based navigation.

**Single ViewModel.** App has fewer than 10 screens — a single ViewModel with `StateFlow` is sufficient. Migrate to per-screen ViewModels or Jetpack Navigation only when screen count justifies it.

**Repository pattern for all data access.** Every external data source (DataStore, filesystem, future network) gets a repository interface + implementation. ViewModels depend on interfaces only.

**Dark-only theme.** Design system uses `darkColorScheme` exclusively. Glassmorphic vaporwave aesthetic with NeonCoral/NeonPurple palette.

**File-based video storage.** Videos persist in `filesDir/videos/`, thumbnails in `filesDir/thumbnails/`. No Room database unless relational queries become necessary.

**Test naming.** Use backtick-delimited descriptive names: `` `returning user resolves to CheckingPermissions after init` ``.

---

## 11. Android Version Targeting (API 36 / Android 16)

**Google Guide:** [Behavior changes — targeting Android 16](https://developer.android.com/about/versions/16/behavior-changes-16) · **OpenRang detail:** [`docs/android-16/`](android-16/README.md)

These rules are **in force** — OpenRang targets **API 36** (see §8). Each was carried as `Status: pending — Issue #7` during the docs-prep phase and flipped to satisfied when the [007 upgrade](completed/007-target-sdk-upgrade/IMPLEMENTATION.md) landed, keeping this doc honest rather than aspirational ([Lesson 007](lessons_learned/007-standards-doc-must-match-code.md)).

**Target the current Play floor.** `compileSdk` and `targetSdk` track Google Play's required level (see §8). Bump in a dedicated upgrade, never bundled with feature work ([Lesson 005](lessons_learned/005-play-store-target-api-level.md)).
`Status: satisfied (Issue #7)` — `compileSdk`/`targetSdk` = 36 in `app/build.gradle.kts`.

**Edge-to-edge is mandatory at target 36.** The `windowOptOutEdgeToEdgeEnforcement` opt-out is removed, so every screen must consume `WindowInsets` — no interactive element (shutter, home, gallery delete, onboarding arrows) may sit under the status/navigation bars or display cutout. `MainActivity` calls `enableEdgeToEdge()`, and every screen consumes insets (`safeDrawingPadding` on onboarding; `statusBarsPadding`/`navigationBarsPadding` on camera, gallery, and preview). The permission and loading screens are centered content that never reaches the bars.
`Status: satisfied (Issue #7)`.

**Predictive back is default-on at target 36.** `android:enableOnBackInvokedCallback` defaults to `true`, and `onBackPressed` / `KEYCODE_BACK` stop being dispatched. Back must route through the `OpenRangUiState` state machine using supported back-navigation APIs — never an ad-hoc back flag (see §10 — all navigation goes through the sealed-interface state machine). Implemented with Compose `BackHandler` in `GalleryScreen` and `PreviewScreen` (each calls the ViewModel's navigation method); `enableOnBackInvokedCallback="true"` is set on `<application>` in the manifest.
`Status: satisfied (Issue #7)`.

**UI must be adaptive on large screens.** At target 36, orientation / resizability / aspect-ratio restrictions are ignored on displays ≥ `sw600dp`. The camera viewfinder and gallery must survive landscape and resize rather than assuming fixed portrait. A temporary opt-out (`PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY`) exists but is explicitly transitional — we made the UI adaptive instead (no opt-out, no fixed `screenOrientation`). Gallery uses an adaptive grid (`GridCells.Adaptive`); the camera and preview control rows are width-capped and centered so they don't stretch to the display edges.
`Status: satisfied in code (Issue #7) — pending an on-device ≥600dp visual pass.`

---

## Quick Reference Links

| Topic | Google Documentation |
|-------|---------------------|
| Architecture Guide | https://developer.android.com/topic/architecture |
| Architecture Recommendations | https://developer.android.com/topic/architecture/recommendations |
| Jetpack Compose Performance | https://developer.android.com/develop/ui/compose/performance/bestpractices |
| Coroutines Best Practices | https://developer.android.com/kotlin/coroutines/coroutines-best-practices |
| DataStore | https://developer.android.com/topic/libraries/architecture/datastore |
| CameraX Overview | https://developer.android.com/media/camera/camerax |
| Testing Fundamentals | https://developer.android.com/training/testing/fundamentals |
| Accessibility | https://developer.android.com/guide/topics/ui/accessibility |
| Core App Quality | https://developer.android.com/docs/quality-guidelines/core-app-quality |
| Play Store Target SDK | https://developer.android.com/google/play/requirements/target-sdk |
| Android 16 Behavior Changes (targeting) | https://developer.android.com/about/versions/16/behavior-changes-16 |
| Android 16 Behavior Changes (all apps) | https://developer.android.com/about/versions/16/behavior-changes-all |
| Kotlin Flows | https://developer.android.com/kotlin/flow |
| ViewModel | https://developer.android.com/topic/libraries/architecture/viewmodel |
