# Lesson 008 — JVM unit tests: use real temp dirs for File, and share one TestDispatcher

## What went wrong

`OpenLoopViewModelTest.kt` was committed in a state that **never compiled and never ran green**. Three distinct problems were stacked:

1. **Stale mock signature.** The test stubbed `cameraManager.startRecording(any(), capture(slot))` with `slot<Consumer<VideoRecordEvent>>()` and `just Runs`. The real signature is `startRecording(File, (VideoRecordEvent) -> Unit): Recording?` — a Kotlin function type, returning a nullable `Recording`. Result: `Type mismatch` compile errors. `just Runs` only works for `Unit`-returning calls; a `Recording?` return needs `returns null`.

2. **Mocking `java.io.File`.** `cacheDir`/`filesDir` were `mockk<File>(relaxed = true)`. The JDK constructor `File(File parent, String child)` reads `parent.path` **as a package-private field**, not via a getter — so mock can't intercept it, and it's `null`, throwing `NullPointerException: Cannot invoke "String.isEmpty()" because "<parameter1>.path" is null` deep inside `File.<init>`.

3. **Two schedulers fighting.** The test used `MainDispatcherRule` (its own `UnconfinedTestDispatcher`) *and* a bare `runTest { }` (which creates a **different** scheduler). `advanceTimeBy(1500)` advanced the `runTest` scheduler, but the `viewModelScope` `delay(1500)` was registered on the *Main* dispatcher's scheduler — so the auto-stop never fired and the `verify { stopRecording() }` failed.

## Pattern

- **Don't mock `File`.** Use a real directory via JUnit's `TemporaryFolder` rule and stub the `Context` getters to return it:
  ```kotlin
  @get:Rule val tempFolder = TemporaryFolder()
  // in @Before:
  cacheDir = tempFolder.newFolder("cache")
  filesDir = tempFolder.newFolder("files")
  every { context.cacheDir } returns cacheDir
  every { context.filesDir } returns filesDir
  ```
  File logic then runs against the real filesystem (fast, no Android needed) and assertions can check real `exists()`/paths.

- **Match the real signature.** For a function returning a value, use `returns <value>` (e.g. `returns null`), never `just Runs`. For a Kotlin function-type callback, capture with `slot<(T) -> Unit>()` and invoke via `slot.captured.invoke(event)`.

- **One scheduler.** When a test needs virtual time, bind `runTest` to the rule's dispatcher so they share a scheduler, and prefer `advanceUntilIdle()` over `advanceTimeBy(n)` (the latter does not run tasks scheduled at exactly `now + n`):
  ```kotlin
  fun `auto-stop fires only after the delay`() = runTest(mainDispatcherRule.testDispatcher) {
      // start the capture, then advance virtual time past the timer
      advanceUntilIdle()
      verify(exactly = 1) { cameraManager.stopRecording() }
  }
  ```

## Detection checklist

- Before trusting "the tests pass," actually run `:app:testDebugUnitTest` — a green local IDE state can mask a suite that the Gradle compiler rejects.
- Grep for `mockk<File>` / `mockk(relaxed = true)` assigned to a `File` — each is a latent NPE if passed to a `File(parent, child)` constructor.
- Grep for `just Runs` on a call whose real return type isn't `Unit`.
- Any `runTest { }` in a class that also has a `MainDispatcherRule` should be `runTest(rule.testDispatcher) { }`.

## Reference

- [Testing Kotlin coroutines on Android](https://developer.android.com/kotlin/coroutines/test) — injecting test dispatchers, virtual time.
- [Build local unit tests](https://developer.android.com/training/testing/local-tests) — JVM tests, `TemporaryFolder`.
- Surfaced while adding test coverage for Issue #11 (permission rationale flow).
