# Lesson 017 — Instrumented tests can't use mockk; and when a mocks return value gains meaning, sweep every call site

> Origin: PR #19 (slice 01 review) — test work for WARNING-1 (NavHost) and REC-2 (null-start guard).

## What went wrong / what was learned

Two distinct test-infrastructure facts cost time this session:

1. **`mockk` is `testImplementation` only — it is NOT on the `androidTest` classpath.** The new
   instrumented `OpenRangNavHostTest` needed an `OpenRangViewModel` + `CameraManager`, but the JVM
   unit fakes (`FakeUserPreferencesRepository`, etc. in `src/test/`) and `mockk` are **invisible**
   to `src/androidTest/`. You cannot `mockk(...)` in an instrumented test.

2. **A behavior change made a previously-meaningless mock return value meaningful.** Lesson 008
   established `every { cameraManager.startRecording(any(), any()) } returns null` as a *convenience*
   (the tests didn't care about the return). REC-2 then made `null` mean **"could not start →
   abort"**. Every test that mocked `returns null` purely for convenience suddenly exercised the
   abort path and broke. They had to be swept to `returns fakeRecording`, leaving `null` only in the
   one test that actually tests the abort.

## Pattern

- **In `androidTest`, write real fakes inline (or construct real objects); don't reach for mockk.**
  The build set has Compose UI test + `test.ext.junit` + Espresso, but no `mockk-android`. Minimal
  hand fakes are fine and live in the test file:
  ```kotlin
  private class NoopPreferencesRepository : UserPreferencesRepository {
      override val hasCompletedOnboarding: Flow<Boolean> = MutableStateFlow(true)
      override suspend fun setOnboardingCompleted(completed: Boolean) {}
  }
  // CameraManager(ApplicationProvider.getApplicationContext()) — constructible; binds nothing until startCamera().
  ```
  (If a future instrumented test genuinely needs mocking, that requires adding
  `androidTestImplementation(libs.mockk.android)` — a deliberate dependency change, not a given.)

- **`createAndroidComposeRule<ComponentActivity>()` needs the test manifest activity**, provided by
  `androidx.compose.ui.test.manifest` (`debugImplementation`). Use it (not `createComposeRule()`)
  whenever the test needs an `Activity` API such as `onBackPressedDispatcher` (Lesson 015).

- **When you change what a return value *means*, grep every stub of it.** A value that was "don't
  care" becoming "signal" is a silent break across the suite. After the change, the sentinel value
  should appear in exactly the test(s) that assert the new behavior; everywhere else returns the
  success value. Introduce a shared success fixture (`private val fakeRecording: Recording =
  mockk(relaxed = true)`) so the intent ("this start succeeds") is explicit at each site.

## Detection checklist

- Grep `src/androidTest/` for `mockk` / `io.mockk` — any hit won't compile (wrong source set).
- After a behavior change to a method's return contract, grep the test tree for the old stub
  (`returns null`, `returns false`, …) and confirm each remaining one is intentional, not leftover.
- An instrumented test using `activity.onBackPressedDispatcher` (or any `Activity` member) must use
  `createAndroidComposeRule<ComponentActivity>()`, never `createComposeRule()`.
- Still applies: actually run `:app:testDebugUnitTest` and `:app:connectedDebugAndroidTest` and read
  the result counts — `BUILD SUCCESSFUL` alone doesn't prove tests ran (Lesson 008).

## Reference

- [Build instrumented tests](https://developer.android.com/training/testing/instrumented-tests) — the `androidTest` source set & classpath.
- [Test in Jetpack Compose](https://developer.android.com/develop/ui/compose/testing) — `createComposeRule` vs `createAndroidComposeRule`.
- Extends [[008-jvm-test-file-and-dispatcher-pitfalls]] (which set up the `returns null` convenience that REC-2 made meaningful).
