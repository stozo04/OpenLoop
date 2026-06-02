# Firebase Analytics — Reporter Abstraction & Staged Rollout

**Status:** Proposed — awaiting sign-off
**Owner:** Steven Gates
**Branch:** `feature/firebase-analytics`
**Related:** [`docs/diagnostics/firebase-crashlytics-trimming.md`](../../diagnostics/firebase-crashlytics-trimming.md), [`docs/lessons_learned/004-viewmodel-no-context-parameters.md`](../../lessons_learned/004-viewmodel-no-context-parameters.md)

---

## 1. Problem statement

`firebase-analytics` is declared in `app/build.gradle.kts` and pulls automatic events (`first_open`, `session_start`, `app_update`, …) into the Firebase console with no code. That's enough to give Crashlytics breadcrumb logs leading up to the non-fatals `ReverseCrashlytics` already records, and it's the reason the dependency exists per the Crashlytics get-started guide.

What's missing is everything the get-started for **Analytics** asks for:

- No `FirebaseAnalytics.getInstance(...)` call anywhere in `app/src/main/` (verified by grep — only the Gradle line references the SDK).
- No `logEvent(...)` calls. The state-machine transitions defined in `OpenLoopUiState` (Recording → Trim → BoomerangEditor → Processing → ReadyToCapture, plus Gallery/Import branches) produce zero analytics signal.
- No `screen_view` instrumentation against `OpenLoopNavHost`. The funnel cannot be reconstructed in the console.
- No mechanism to disable collection (Apache 2.0 app with a privacy policy at `docs/privacy-policy.html` will eventually want a user toggle).

The PRD's stated goals (real-time speed adjustment, filter adoption, gallery usage) cannot be measured without instrumentation. This doc lays out a staged rollout so we can land the abstraction now and add events incrementally without a second refactor.

## 2. Scope

### In

- A thin `AnalyticsReporter` interface in the `diagnostics` package mirroring the `UserPreferencesRepository` shape: interface + production impl + a no-op fallback for tests and when `google-services.json` is absent.
- A Firebase-backed implementation (`FirebaseAnalyticsReporterImpl`) wrapping `FirebaseAnalytics.getInstance(applicationContext)`.
- A 6th `OpenLoopViewModel` constructor parameter + matching `Factory` parameter (additive, defaultable).
- One new bridge call in `MainActivity`'s viewModels delegate, alongside the existing repositories — no `Context` leaks downstream (Lesson 004).
- Unit-testable surface: a fake reporter records calls in-memory so ViewModel tests can assert `boomerang_exported_success` was emitted with the right params.

### Out

- Native-crash NDK capture (separate concern — see Crashlytics audit).
- Server-side BigQuery export wiring (manageable from console later, no code).
- User-facing opt-out toggle UI (parked — see §7).
- Web/iOS — Android only.

## 3. Architecture

```
                  FirebaseAnalytics SDK (auto-events: first_open, session_start)
                            │
                            ▼
MainActivity ──> FirebaseAnalyticsReporterImpl ──┐
                  (or NoOpAnalyticsReporter)     │
                                                 ▼
                                  OpenLoopViewModel.Factory
                                                 │
                                                 ▼
                                       OpenLoopViewModel
                                       (calls reporter.screenView / logEvent)
                                                 │
                                                 ▼
                                       OpenLoopNavHost (screen-view side-effect
                                       per state-machine transition)
```

The reporter is constructed in `MainActivity` (the only place a `Context` exists per Lesson 004) and passed to the `Factory`, exactly like `UserPreferencesRepositoryImpl(applicationContext.dataStore)` is today.

The interface surface stays small — three methods, all the get-started doc actually uses:

```kotlin
interface AnalyticsReporter {
    fun screenView(screenName: String, screenClass: String? = null)
    fun logEvent(name: String, params: Map<String, Any> = emptyMap())
    fun setUserProperty(name: String, value: String?)
}
```

`FirebaseAnalyticsReporterImpl` translates `Map<String, Any>` into a `Bundle` (Long/Double/String/Boolean per Analytics' supported param types) and routes `screenView` through `FirebaseAnalytics.Event.SCREEN_VIEW`. `NoOpAnalyticsReporter` is a singleton object that silently drops every call — used in tests and as the fallback when `google-services.json` is absent (mirrors the `ReverseCrashlytics.crashlyticsOrNull()` pattern).

## 4. Staged rollout — option 1, 2, 3

The same interface ships for all three; we move along the ladder as we add value, not as we refactor.

### Option 1 — Ship the abstraction only

Land the interface, the Firebase impl, and the wiring. **No `screenView` or `logEvent` calls anywhere yet.** This is reversible-friendly: zero behavior change vs today (auto-events still flow, Crashlytics breadcrumbs still benefit). The abstraction is now there for when we want it. Used in CI / fresh-clone builds (no `google-services.json`) the bridge falls back to `NoOpAnalyticsReporter`.

**Cost:** ~150 LOC across 3 new files + 2 line additions in existing files.

### Option 2 — Add screen tracking

Wire `screenView` exactly once per `OpenLoopUiState` discriminator in `OpenLoopNavHost` (a `LaunchedEffect(state::class)` side-effect). Funnel reconstructs in the Firebase console: % of users who pass Onboarding → Camera → Trim → Editor → Processing → Gallery.

**Mapping** (deliberately omits `Initializing` — too short-lived to be useful):

| State | screen_name |
|---|---|
| `Onboarding` | `onboarding` |
| `CheckingPermissions` / `PermissionRationale` / `PermissionDenied` | `permission_<kind>` |
| `ReadyToCapture` | `camera` |
| `Recording` | `recording` |
| `Trim` | `trim` |
| `BoomerangEditor` | `editor` |
| `Processing` | `processing` |
| `ImportingVideo` | `import` |
| `Gallery` | `gallery` |

**Cost:** ~15 LOC added to `OpenLoopNavHost`.

### Option 3 — Custom events for the PRD's success metrics

Layer in domain events at the moments that already matter to the state machine and PRD goals. Event names are snake_case ≤40 chars (Analytics constraint); params are Long/Double/String/Boolean only.

| When | Event | Params |
|---|---|---|
| Burst capture finalizes successfully | `burst_captured` | `duration_ms: Long` |
| Trim NEXT pressed → BoomerangEditor | `trim_completed` | `window_ms: Long` |
| Editor direction chip tap | `editor_direction_changed` | `mode: String` |
| Editor speed slider settle (debounced) | `editor_speed_changed` | `speed: Double` |
| Editor filter chip tap | `editor_filter_applied` | `filter: String` |
| Export completes | `boomerang_exported_success` | `mode: String`, `speed: Double`, `filter: String`, `duration_ms: Long` |
| Export fails | `boomerang_exported_failure` | `outcome: String` (mirrors the Crashlytics key) |
| Gallery video opens | `gallery_loop_opened` | (none) |
| Gallery batch delete commits | `gallery_loops_deleted` | `count: Long` |
| Library import completes | `video_imported_success` | `duration_ms: Long` |
| Library import fails | `video_imported_failure` | `reason: String` |
| Onboarding final page "LET'S GO" tap | `onboarding_completed` | (none) |

Each call site is one line in `OpenLoopViewModel` next to the existing `ReverseCrashlytics.reportPreviewFailure(...)` and `userPreferencesRepository.setOnboardingCompleted(...)` calls. They land alongside the feature work that introduces them — they don't all need to ship at once.

**Cost:** ~12 one-line additions across `OpenLoopViewModel`, paid per-event as features ship.

### Recommendation

Land **Option 1 + Option 2 in this PR** (the abstraction is cheap and screen tracking gives an immediate funnel). Pick up Option 3 events incrementally as the boomerang-rollout slices ship — that way each slice's PR owns its event addition rather than this PR carrying all twelve. The interface supports it without further refactor.

## 5. Implementation steps

1. **New files**
   - `app/src/main/java/io/github/stozo04/openloop/diagnostics/AnalyticsReporter.kt` — interface + `NoOpAnalyticsReporter` object.
   - `app/src/main/java/io/github/stozo04/openloop/diagnostics/FirebaseAnalyticsReporterImpl.kt` — Firebase wrapper. Constructor takes `applicationContext: Context` (only place a Context lives downstream of MainActivity — already the pattern for `UserPreferencesRepositoryImpl` consumers).
2. **Existing-file edits (additive)**
   - `OpenLoopViewModel.kt`: add `private val analytics: AnalyticsReporter` as the 6th constructor param. Add the matching parameter to the nested `Factory`. No call sites populated in this PR (option 1) — except the navhost side-effect for option 2.
   - `MainActivity.kt`: construct `FirebaseAnalyticsReporterImpl(applicationContext)` in the `viewModels { … Factory(…) }` block, alongside the existing repository constructions.
   - `OpenLoopNavHost` (option 2 only): a `LaunchedEffect(state::class)` block that calls `viewModel.onScreenShown(state)`; the VM forwards to `analytics.screenView(...)` using the mapping in §4.
3. **Tests**
   - `app/src/test/java/io/github/stozo04/openloop/diagnostics/FakeAnalyticsReporter.kt` — records a `List<Recorded>` of every call for assertions.
   - Add fake into the existing `OpenLoopViewModelTest` constructor (one line) so existing tests don't break.
   - (Option 2) one new test: route to each state, assert one `screen_view` per transition with the mapped name.
4. **Verification (per DEFINITION_OF_DONE.md)**
   - `:app:lintDebug` clean (no new errors).
   - Unit tests green.
   - Run on emulator with `google-services.json` present, navigate Onboarding → Camera → Trim → Editor → Gallery, confirm the events show in **Firebase Console → Analytics → DebugView** (requires `adb shell setprop debug.firebase.analytics.app io.github.stozo04.openloop`).
   - Without `google-services.json`: build still succeeds; reporter silently no-ops (manually verified — no `FirebaseAnalytics.getInstance` crash).

## 6. Testing plan

- **Unit:** `FakeAnalyticsReporter` swapped in via the VM constructor. ViewModel tests added per option 3 as events land.
- **Integration:** Manually verified once with Firebase DebugView. Not a unit-testable boundary — Firebase SDK has no public test harness for `logEvent`.
- **Crashlytics interaction:** `ReverseCrashlytics` continues to call `FirebaseCrashlytics.getInstance()` directly (unchanged). Analytics events become breadcrumbs automatically — verify by triggering a reverse-preview failure and inspecting the Crashlytics issue's **Logs** tab for the preceding `screen_view` and any custom events.
- **Without `google-services.json`:** `FirebaseAnalytics.getInstance()` throws inside `Impl` — catch + degrade to `NoOpAnalyticsReporter` in the constructor (same defensive pattern as `crashlyticsOrNull()`).

## 7. Open questions (parked)

- **User-facing opt-out toggle.** Apache 2.0 + privacy policy means we should eventually surface a Settings switch that toggles `FirebaseAnalytics.setAnalyticsCollectionEnabled(false)` and `FirebaseCrashlytics.setCrashlyticsCollectionEnabled(false)`. Out of scope for this PR; tracked separately.
- **DebugView screenshot in PR.** Per DEFINITION_OF_DONE the change needs the app-running screenshot. DebugView from the Firebase console satisfies the "events flow end-to-end" check; include both an emulator screenshot of the running app and a DebugView screenshot in the PR.
- **One-time test fatal crash.** Independent of this PR but worth doing in the same release — `throw RuntimeException("Crashlytics test")` once on a debug build to confirm fatals land in the console. Flagged in the Crashlytics audit too.

## 8. Acceptance criteria

- `:app` builds clean both with and without `app/google-services.json`.
- `:app:lintDebug` reports zero new errors against the baseline.
- `OpenLoopViewModelTest` and any new tests are green.
- With `google-services.json` present: launching the app produces `screen_view` events in Firebase DebugView matching the §4 mapping for the screens visited.
- Without `google-services.json`: no crash, no Firebase initialization error in logcat, app runs identically to today.
- Screenshot of running app attached to PR per DEFINITION_OF_DONE.

## 9. References

- [Get started with Google Analytics for Android](https://firebase.google.com/docs/analytics/android/get-started)
- [Log events (Android)](https://firebase.google.com/docs/analytics/android/events)
- [Configure data collection and usage](https://firebase.google.com/docs/analytics/ios/configure-data-collection) — same opt-out APIs exist on Android
- `docs/lessons_learned/004-viewmodel-no-context-parameters.md` — pattern this PR follows for the bridge
- `docs/PRD-mission-control.md` — overall architecture this slots into
