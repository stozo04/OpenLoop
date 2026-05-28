# TEST_COVERAGE.md — OpenRang Testing Strategy

This document defines how, where, and why we test. It follows Google's official Android testing guidance and serves as the single source of truth for all testing decisions in OpenRang.

---

## The Two Test Directories

Android projects have two distinct test source sets. Understanding the difference is fundamental.

### `app/src/test/` — Local Unit Tests

Tests here run on your **development machine's JVM**. No device. No emulator. Fast — milliseconds per test.

They cannot access Android framework APIs (`Context`, `Activity`, Compose rendering) unless mocked or faked. Use these for pure logic: state transitions, business rules, data processing, repository behavior.

**Source:** [Build Local Unit Tests](https://developer.android.com/training/testing/local-tests)

### `app/src/androidTest/` — Instrumented Tests

Tests here run on an **actual Android device or emulator**. The app is built, installed, and a test app runs alongside it that injects commands and reads UI state. Slower — seconds per test.

These have full access to the Android framework: real Compose rendering, `Context`, device sensors, file system. Use these when you need to verify what the user actually sees on screen.

**Source:** [Build Instrumented Tests](https://developer.android.com/training/testing/instrumented-tests)

### Decision Rule

If your test doesn't need a real Android device to prove its point, it goes in `test/`. If it needs to render UI, touch the screen, or access device APIs, it goes in `androidTest/`.

**Source:** [Test in Android Studio](https://developer.android.com/studio/test/test-in-android-studio)

---

## Testing Pyramid

Google recommends a pyramid-shaped distribution: many small fast tests at the base, fewer large slow tests at the top.

### Unit Tests (most tests — `test/`)

Fast, isolated, deterministic. Test one class or function at a time with dependencies replaced by fakes or mocks.

**What to test:**
- ViewModels — every state transition, every edge case
- Repositories — read/write behavior, error handling, fallbacks
- Utility classes — string manipulation, data transforms, math
- Domain logic — any business rules or data processing

**What NOT to test here:**
- Activities or Fragments — they contain mostly framework code
- Compose layouts — rendering requires the real Compose engine
- DI configuration — tested implicitly by integration tests

**Source:** [What to Test in Android](https://developer.android.com/training/testing/fundamentals/what-to-test)

### Component / Integration Tests (`test/` or `androidTest/`)

Verify interactions between two or more classes working together. For example, a ViewModel reading from a fake Repository. Keep these local (in `test/`) where possible for speed.

### Screen UI Tests (`androidTest/`)

Verify critical user interactions on a single screen — clicking buttons, typing in forms, checking visible states. One test class per screen is a good starting point.

For Compose: use the [Compose Testing APIs](https://developer.android.com/develop/ui/compose/testing) (`createComposeRule`, `onNodeWithText`, `performClick`, semantic matchers).

**Source:** [Test Your Compose Layout](https://developer.android.com/develop/ui/compose/testing)

### End-to-End Tests (`androidTest/`)

Full user flows across multiple screens. Most expensive to write and maintain. Reserve for critical paths only.

**Why the pyramid matters:** A bug caught by a unit test costs minutes to fix. The same bug caught by an end-to-end test can take days and involve multiple team members.

**Source:** [Testing Strategies](https://developer.android.com/training/testing/fundamentals/strategies)

---

## Frameworks & Tools

| Tool | Purpose | Used In |
|------|---------|--------|
| **JUnit 4** | Test runner and assertions | `test/` |
| **MockK** | Kotlin-first mocking library | `test/` (for Android framework classes like `Context`, `Log`) |
| **Fakes** | Hand-written test doubles using real interfaces | `test/` (preferred over mocks for Flow-based interfaces) |
| **kotlinx-coroutines-test** | `TestDispatcher`, `runTest`, virtual time control | `test/` |
| **Compose UI Test** | `createComposeRule`, semantic matchers, UI assertions | `androidTest/` |
| **AndroidJUnit4** | Instrumented test runner | `androidTest/` |

### Fakes Over Mocks

Google recommends fakes for Flow-based interfaces because they're more readable and maintainable. A `FakeUserPreferencesRepository` backed by `MutableStateFlow` is clearer than a MockK setup for the same interface.

Use MockK for Android framework classes that can't easily be faked (e.g., `Context`, `Log`, `VideoRecordEvent`).

**Source:** [Testing Fundamentals](https://developer.android.com/training/testing/fundamentals)

---

## Coroutine Testing

All ViewModel coroutines use `viewModelScope`, which requires replacing `Dispatchers.Main` in tests.

### MainDispatcherRule

A JUnit `TestWatcher` that swaps `Dispatchers.Main` with a `TestDispatcher` before each test and resets it after:

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val testDispatcher: TestDispatcher = UnconfinedTestDispatcher()
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(testDispatcher)
    }
    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
```

### UnconfinedTestDispatcher vs StandardTestDispatcher

| Dispatcher | Behavior | Use When |
|-----------|----------|----------|
| `UnconfinedTestDispatcher` | Runs coroutines **eagerly** (blocking). Simpler test code. | Default choice. Most tests. |
| `StandardTestDispatcher` | Queues coroutines. You control execution with `advanceUntilIdle()`, `advanceTimeBy()`. | Testing timing, delays, concurrency, or observing intermediate states. |

OpenRang defaults to `UnconfinedTestDispatcher` for simplicity. The `startBurstCapture` test uses `advanceTimeBy(1500)` to simulate the auto-stop timer.

**Source:** [Testing Kotlin Coroutines on Android](https://developer.android.com/kotlin/coroutines/test)

---

## Compose UI Testing

Compose tests use a `ComposeTestRule` to set content, find nodes via the semantics tree, and assert their state.

**Key APIs:**
- `createComposeRule()` — creates a test rule without needing an Activity
- `setContent { }` — sets the Compose UI under test
- `onNodeWithText("text")` — finds a node by displayed text
- `onNodeWithContentDescription("desc")` — finds a node by accessibility label
- `performClick()` — simulates a tap
- `assertIsDisplayed()` — verifies visibility
- `printToLog()` — dumps the semantics tree for debugging

**Source:** [Testing APIs](https://developer.android.com/develop/ui/compose/testing/apis) · [Semantics in Testing](https://developer.android.com/develop/ui/compose/testing/semantics)

---

## Current Test Inventory

### Local Unit Tests (`app/src/test/`) — 24 tests

| Test | Category | What It Validates |
|------|----------|------------------|
| `first-time user resolves to Onboarding after init` | DataStore | DataStore `false` → Onboarding state |
| `returning user resolves to CheckingPermissions after init` | DataStore | DataStore `true` → CheckingPermissions state |
| `onOnboardingCompleted persists true to repository` | DataStore | Write to DataStore actually happens |
| `onOnboardingCompleted handles IOException gracefully` | Error | Failed write still transitions to CheckingPermissions |
| `onOnboardingCompleted transitions to CheckingPermissions` | State | Onboarding → CheckingPermissions |
| `onPermissionsChecked when granted transitions to ReadyToCapture` | State | Permission grant → camera ready |
| `onPermissionsChecked when denied transitions to PermissionDenied` | State | Permission deny → denied screen |
| `showPermissionRationale transitions to PermissionRationale` | Permissions | Denied-once → educational rationale (Issue #11) |
| `onRationaleAcknowledged transitions to CheckingPermissions` | Permissions | Acknowledge rationale → re-check (Issue #11) |
| `onRationaleDeclined transitions to PermissionDenied` | Permissions | "Not now" cancel → blocked-but-recoverable screen (Issue #11) |
| `rationale flow ending in grant reaches ReadyToCapture` | Permissions | Full rationale → grant path (Issue #11) |
| `rationale flow ending in denial reaches PermissionDenied` | Permissions | Full rationale → denial path (Issue #11) |
| `resetToCapture transitions state back to ReadyToCapture` | State | State reset works from any state |
| `startBurstCapture when not ready does not transition or call camera` | Guard | Guard clause prevents recording from wrong state |
| `startBurstCapture successfully starts recording and delays automatic stop` | Capture | Full capture lifecycle with 1500ms time advance |
| `startBurstCapture failures fallback state gracefully` | Error | Camera error → graceful fallback to ReadyToCapture |
| `stopBurstCapture cancels coroutine job and stops recording` | Capture | Clean shutdown of recording |
| `video record event finalize transitions to LoopingPreview on success` | Capture | Successful recording → preview screen |
| `video record event finalize transitions back to ReadyToCapture on error` | Error | Failed recording → graceful fallback |
| `navigateToGallery transitions state to Gallery` | Navigation | Gallery navigation works |
| `navigateBackFromGallery transitions state to ReadyToCapture` | Navigation | Back from gallery works |
| `loadRecordedVideos with missing directory returns empty list` | Storage | Empty state handled correctly |
| `deleteVideo removes files and reloads empty list` | Storage | Deletion flow works |
| `recordedVideos flow starts as empty list` | State | Initial state is clean |

### Instrumented UI Tests (`app/src/androidTest/`) — 10 tests

| Test | What It Guards |
|------|---------------|
| `page0_nextButton_isDisplayedAndCentered` | Button centering regression (page 1) |
| `page1_backAndNextButtons_areDisplayedAndCentered` | Button centering regression (page 2) |
| `page2_ctaButton_isDisplayedAndFillsWidth` | CTA layout regression (page 3) |
| `page0_doesNotShowPage1OrPage2Controls` | Mutual exclusivity of page controls |
| `page1_doesNotShowPage0OrPage2Controls` | Mutual exclusivity of page controls |
| `page2_doesNotShowPage0OrPage1Controls` | Mutual exclusivity of page controls |
| `rationaleVariant_showsGrantAndCancel_hidesSettings` | Rationale screen shows "Grant"/"Not now", hides Settings (Issue #11) |
| `denialVariant_showsTryAgainAndSettings` | Denial screen shows "Try Again"/"Open Device Settings" (Issue #11) |
| `noSecondaryAction_rendersPrimaryOnly` | Omitting the secondary action hides the second button (Issue #11) |
| `primaryAndSecondaryClicks_invokeTheirCallbacks` | Both buttons fire their callbacks (Issue #11) |

---

## Coverage Gaps (Known)

These are areas that need tests but don't have them yet:

| Area | Why It's Missing | Priority |
|------|-----------------|----------|
| `UserPreferencesRepositoryImpl` | Needs instrumented test with real DataStore | Medium |
| `CameraManager` | Hardware-dependent, hard to unit test | Low (manual testing) |
| Accessibility (contrast, touch targets) | Needs automated accessibility checks | High |
| Screen UI tests beyond onboarding | Gallery, Camera, Preview screens untested | Medium |
| Error recovery flows | Corrupted DataStore, permission revocation mid-use | Medium |

---

## Adding New Tests

When adding a new feature, follow this checklist:

1. **Write unit tests first** (`test/`) — cover every state transition, error path, and edge case in the ViewModel
2. **Use fakes for repositories** — implement the interface with `MutableStateFlow` under the hood
3. **Use MockK for framework classes** — `Context`, `Log`, Android SDK types
4. **Add UI tests if layout matters** (`androidTest/`) — if a button position, visibility, or centering is critical, guard it with a Compose UI test
5. **Name tests descriptively** — use backtick-delimited names: `` `returning user skips onboarding on second launch` ``
6. **Test error paths** — if something can fail, test that it fails gracefully

---

## Sources

All testing standards in this document are sourced from Google's official Android developer documentation:

- [Fundamentals of Testing Android Apps](https://developer.android.com/training/testing/fundamentals)
- [What to Test in Android](https://developer.android.com/training/testing/fundamentals/what-to-test)
- [Testing Strategies](https://developer.android.com/training/testing/fundamentals/strategies)
- [Build Local Unit Tests](https://developer.android.com/training/testing/local-tests)
- [Build Instrumented Tests](https://developer.android.com/training/testing/instrumented-tests)
- [Test in Android Studio](https://developer.android.com/studio/test/test-in-android-studio)
- [Test Your Compose Layout](https://developer.android.com/develop/ui/compose/testing)
- [Compose Testing APIs](https://developer.android.com/develop/ui/compose/testing/apis)
- [Semantics in Compose Testing](https://developer.android.com/develop/ui/compose/testing/semantics)
- [Testing Kotlin Coroutines on Android](https://developer.android.com/kotlin/coroutines/test)
- [Testing Kotlin Flows on Android](https://developer.android.com/kotlin/flow/test)
